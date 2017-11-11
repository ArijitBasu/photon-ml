/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml

import java.util.{List => JList}

import scala.collection.JavaConverters._

import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import scopt.OptionParser

import com.linkedin.photon.ml.data.avro._
import com.linkedin.photon.ml.io.deprecated.FieldNamesType
import com.linkedin.photon.ml.util._

/**
 * A class that builds feature index map as an independent Spark job. Recommended when the feature space is large,
 * typically when there are more than 200k unique features.
 *
 * The job expects three required arguments and three optional ones:
 *
 * input-paths: The input paths of the data
 * partition-num: The number of partitions to break the storage into. This is merely introduced as an optimization at
 *   the index building stage, so that we don't need to shuffle all features into the same partition. A heuristically
 *   good number would be 1-10, or even as large as 20; but please avoid setting it into an arbitrarily large number.
 *   Use just 1 or 2 if you find the indexing job is already fast enough.
 * output-path: The output path
 * date-range: Date range for the input data represented in the form start.date-end.date, e.g. 20150501-20150631
 * date-range-days-ago: Date range for the input data represented in the form start.daysAgo-end.daysAgo, e.g. 90-1
 * add-intercept: whether include INTERCEPT in the map (optional, default: true).
 * data-format: Input data format. (optional, default: TRAINING_EXAMPLE, options:
 *   [TRAINING_EXAMPLE|RESPONSE_PREDICTION])
 * feature-shard-id-to-feature-section-keys-map: A map between the feature shard id and it's corresponding feature
 *   section keys, in the following format: shardId1:sectionKey1,sectionKey2|shardId2:sectionKey2,sectionKey3.
 * feature-shard-id-to-intercept-map: A map between the feature shard id and a boolean variable that decides whether a
 *   dummy feature should be added to the corresponding shard in order to learn an intercept, for example, in the
 *   following format: shardId1:true|shardId2:false. The default is the "add-intercept" setting for all or unspecified
 *   shard ids.
 */
class FeatureIndexingJob(
    val sc: SparkContext,
    val inputPaths: Seq[Path],
    val partitionNum: Int,
    val outputPath: String,
    val addIntercept: Boolean,
    val fieldNames: AvroFieldNames,
    val featureShardIdToFeatureSectionKeysMap: Option[Map[String, Set[String]]] = None,
    val featureShardIdToInterceptMap: Option[Map[String, Boolean]] = None) {

  private val logger: PhotonLogger = new PhotonLogger(new Path(outputPath, "_log"), sc)

  /**
   * Ensures that the output path exists.
   *
   * @param outputPath A path to the output dir as a [[String]]
   * @return A Hadoop [[Path]] object to the given path, which is guaranteed to exist
   */
  private def ensureOutputPath(outputPath: String): Path = {
    val path = new Path(outputPath)
    val fs = path.getFileSystem(new Configuration())
    if (fs.exists(path)) {
      fs.delete(path, true)
    }
    fs.mkdirs(path)
    path
  }

  /**
   * Given a raw input data [[RDD]], generate the partitioned unique features names grouped by hash code
   *
   * @param inputRdd An [[RDD]] of raw input [[GenericRecord]]
   * @param addIntercept Whether to add an intercept feature to the list of unique features
   * @param featureSections An optional set of feature bags to combine into one index
   * @return RDD[(hash key, Iterable[unique feature name])]
   */
  private def partitionedUniqueFeatures(
      inputRdd: RDD[GenericRecord],
      addIntercept: Boolean,
      featureSections: Option[Set[String]] = None): RDD[(Int, Iterable[String])] = {

    // Copy it to avoid serialization of the entire class
    val fieldNamesRef = fieldNames
    val keyedFeaturesRDD = inputRdd.flatMap { record: GenericRecord =>

      // Step 1: extract feature names. If feature sections are specified, combine them -- otherwise, use the default
      // feature section for this data format
      val features = featureSections match {
        case Some(sections) =>
          sections
            .map(section => Option(record.get(section))
              .getOrElse(throw new IllegalArgumentException(s"Feature section not found: $section"))
              .asInstanceOf[JList[GenericRecord]]
              .asScala
              .toSet)
            .reduce(_ ++ _)
        case _ =>
          record.get(fieldNamesRef.FEATURES)
            .asInstanceOf[JList[GenericRecord]]
            .asScala
            .toSet
      }

      features.map(f =>
        Utils.getFeatureKey(f, fieldNamesRef.NAME, fieldNamesRef.TERM, Constants.DELIMITER))

    }.mapPartitions{ iter =>
      // Step 2. map features to (hashCode, featureName)
      iter.toSet[String].map(f => (f.hashCode, f)).iterator
    }

    val keyedFeaturesUnionedRDD = if (addIntercept) {
      val interceptRDD = sc.parallelize(List[(Int, String)](
          Constants.INTERCEPT_KEY.hashCode() -> Constants.INTERCEPT_KEY))
      keyedFeaturesRDD.union(interceptRDD)
    } else {
      keyedFeaturesRDD
    }

    // Step 3. distinct and group by hashcode
    // (@note integer's hashcode is still itself, this trick saves shuffle data size)
    keyedFeaturesUnionedRDD.distinct().groupByKey(new HashPartitioner(partitionNum))
  }

  /**
   * Build and write the index map.
   *
   * @param featuresRdd An RDD of the features
   * @param outputPath Path to write the index map file
   * @param namespace Unique namespace for the index map (default: global)
   */
  private def buildIndexMap(
      featuresRdd: RDD[(Int, Iterable[String])],
      outputPath: String,
      namespace: String = IndexMap.GLOBAL_NS): Unit = {

    val projectRdd = featuresRdd.mapPartitionsWithIndex{ case (idx, iter) =>
      var i: Int = 0
      // NOTE PalDB writer within the same JVM might stomp on each other and generate corrupted data, it's safer to
      // lock the write. This will only block writing operations within the same JVM
      PalDBIndexMapBuilder.WRITER_LOCK.synchronized {
        val mapBuilder = new PalDBIndexMapBuilder().init(outputPath, idx, namespace)

        while (iter.hasNext) {
          val tuple = iter.next()
          val features = tuple._2
          features.foreach { feature =>
            mapBuilder.put(feature, i)
            i += 1
          }
        }

        mapBuilder.close()
      }
      Array[Int](i).toIterator
    }

    // Trigger run
    val num = projectRdd.sum().toInt
    logger.info(s"Total number of features indexed: [$num]")
  }

  /**
   * Run the configured [[FeatureIndexingJob]].
   */
  def run(): Unit = {
    val inputRdd = AvroUtils.readAvroFiles(sc, inputPaths.map(_.toString), 10)

    ensureOutputPath(outputPath)

    featureShardIdToFeatureSectionKeysMap match {
      case Some(shardToSectionsMap) =>
        // If a shard to feature section set was specified, build an index for each shard
        shardToSectionsMap.foreach { case (shardId, featureSections) =>
          // Get whether we should add an intercept for this shard, defaulting to the global "addIntercept" setting
          val addShardIntercept = featureShardIdToInterceptMap
            .getOrElse(Map())
            .getOrElse(shardId, addIntercept)

          val featuresRdd = partitionedUniqueFeatures(inputRdd, addShardIntercept, Some(featureSections))
          buildIndexMap(featuresRdd, outputPath, shardId)
        }

      case _ =>
        // Otherwise, build a global index
        val featuresRdd = partitionedUniqueFeatures(inputRdd, addIntercept)
        buildIndexMap(featuresRdd, outputPath)
    }
  }
}

object FeatureIndexingJob {

  /**
   * Entry point to the job.
   *
   * @param args The command line arguments for the job
   */
  def main(args: Array[String]): Unit = {
    val params = parseArgs(args)

    val sc: SparkContext = SparkContextConfiguration.asYarnClient(
      new SparkConf(),
      "build-feature-index-map",
      useKryo = true)

    // Handle date range input
    val dateRangeOpt = IOUtils.resolveRange(
      params.dateRangeOpt.map(DateRange.fromDateString),
      params.dateRangeDaysAgoOpt.map(DaysRange.fromDaysString))
    val inputPathsWithRanges = dateRangeOpt
      .map { dateRange =>
        IOUtils.getInputPathsWithinDateRange(
          params.inputPaths,
          dateRange,
          sc.hadoopConfiguration,
          errorOnMissing = false)
      }
      .getOrElse(params.inputPaths.toSeq)

    new FeatureIndexingJob(
      sc,
      inputPathsWithRanges,
      params.partitionNum,
      params.outputPath,
      params.addIntercept,
      params.fieldNames,
      params.featureShardIdToFeatureSectionKeysMap)
      .run()
  }

  /**
   * Configuration parameters for the job.
   */
  private case class Params(
    inputPaths: Set[Path] = Set.empty,
    partitionNum: Int = 1,
    outputPath: String = "",
    dateRangeOpt: Option[String] = None,
    dateRangeDaysAgoOpt: Option[String] = None,
    addIntercept: Boolean = true,
    fieldNames: AvroFieldNames = TrainingExampleFieldNames,
    featureShardIdToFeatureSectionKeysMap: Option[Map[String, Set[String]]] = None,
    featureShardIdToInterceptMap: Option[Map[String, Boolean]] = None) {

    override def toString: String = List(
      s"Input parameters:",
      s"inputPaths: ${inputPaths.mkString(", ")}",
      s"partitionNum: $partitionNum",
      s"outputPath: $outputPath",
      s"dateRangeOpt: $dateRangeOpt",
      s"dateRangeDaysAgoOpt: $dateRangeDaysAgoOpt",
      s"addIntercept: $addIntercept",
      s"fieldNames: $fieldNames",
      s"featureShardIdToFeatureSectionKeysMap: $featureShardIdToFeatureSectionKeysMap",
      s"featureShardIdToInterceptMap: $featureShardIdToInterceptMap"
    ).mkString("\n")
  }

  /**
   * Parse command line arguments into a Params object.
   *
   * @param args Command line arguments in Scopt format
   * @return A parsed [[Params]] object
   */
  private def parseArgs(args: Array[String]): Params = {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("Feature-Indexing-Job") {
      opt[String]("input-paths")
        .required()
        .text("The input path of the data.")
        .action((x, c) => c.copy(inputPaths = x.split(",").map(new Path(_)).toSet))

      opt[Int]("partition-num")
        .required()
        .text("The number of partitions to break the storage into. This is merely introduced as an optimization at " +
          "the index building stage, so that we don't need to shuffle all features into the same partition. A " +
          "heuristically good number could be 1-10, or even as large as 20; but please avoid setting it into an " +
          "arbitrarily large number. Use just 1 or 2 if you find the job is already fast enough.")
        .action((x, c) => c.copy(partitionNum = x))

      opt[String]("output-path")
        .required()
        .text("The output path")
        .action((x, c) => c.copy(outputPath = x))

      opt[String]("date-range")
        .text(s"Date range for the input data represented in the form start.date-end.date, e.g. 20150501-20150631")
        .action((x, c) => c.copy(dateRangeOpt = Some(x)))

      opt[String]("date-range-days-ago")
        .text(s"Date range for the input data represented in the form start.daysAgo-end.daysAgo, e.g. 90-1")
        .action((x, c) => c.copy(dateRangeDaysAgoOpt = Some(x)))

      opt[Boolean]("add-intercept")
        .text("whether include INTERCEPT in the map (default: true)")
        .action((x, c) => c.copy(addIntercept = x))

      opt[String]("data-format")
        .text("Input data format. (default: TRAINING_EXAMPLE, options: [TRAINING_EXAMPLE|RESPONSE_PREDICTION]).")
        .action((x, c) => {
          val fieldNamesType = FieldNamesType.withName(x)
          c.copy(fieldNames = fieldNamesType match {
            case FieldNamesType.RESPONSE_PREDICTION => ResponsePredictionFieldNames
            case FieldNamesType.TRAINING_EXAMPLE => TrainingExampleFieldNames
            case _ => throw new IllegalArgumentException(
              s"Input training file's field name type cannot be $fieldNamesType")
          })
        })

      opt[String]("feature-shard-id-to-feature-section-keys-map")
        .text(s"A map between the feature shard id and it's corresponding feature section keys, in the following " +
          s"format: shardId1:sectionKey1,sectionKey2|shardId2:sectionKey2,sectionKey3.")
        .action((x, c) => c.copy(featureShardIdToFeatureSectionKeysMap =
          Some(x.split("\\|")
            .map { line => line.split(":") match {
              case Array(key, names) => (key, names.split(",").map(_.trim).toSet)
              case Array(key) => (key, Set[String]())
            }}
            .toMap)))

      opt[String]("feature-shard-id-to-intercept-map")
        .text(s"A map between the feature shard id and a boolean variable that decides whether a dummy feature " +
          s"should be added to the corresponding shard in order to learn an intercept, for example, in the " +
          s"following format: shardId1:true|shardId2:false. The default is true for all shard ids.")
        .action((x, c) => c.copy(featureShardIdToInterceptMap =
          Some(x.split("\\|")
            .map { line => line.split(":") match {
              case Array(key, flag) => (key, flag.toBoolean)
              case Array(key) => (key, true)
            }}
            .toMap)))

      help("help").text("Prints usage text.")
    }

    parser.parse(args, defaultParams).getOrElse {
      throw new IllegalArgumentException(s"Parsing the command line arguments failed.\n" +
        s"Input arguments are: ${args.mkString(", ")}).")
    }
  }
}
