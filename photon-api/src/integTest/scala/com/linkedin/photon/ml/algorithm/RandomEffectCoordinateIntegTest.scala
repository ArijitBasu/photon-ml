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
package com.linkedin.photon.ml.algorithm

import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.test.SparkTestUtils
import com.linkedin.photon.ml.util.{GameTestUtils, MathUtils}

/**
 * Integration tests for the [[RandomEffectCoordinate]] implementation.
 */
class RandomEffectCoordinateIntegTest extends SparkTestUtils with GameTestUtils {
  import RandomEffectCoordinateIntegTest._

  private val randomEffectType = "random-effect-1"
  private val featureShardId = "shard1"

  @DataProvider
  def numEntitiesDataProvider(): Array[Array[Integer]] = {
    Array(Array(1), Array(2), Array(10))
  }

  @Test(dataProvider = "numEntitiesDataProvider")
  def testUpdateModel(numEntities: Int): Unit = sparkTest("testUpdateModel") {
    val (coordinate, model) = generateRandomEffectCoordinateAndModel(
      randomEffectType,
      featureShardId,
      numEntities,
      NUM_TRAINING_SAMPLES,
      DIMENSIONALITY)

    // Score before model update
    val score = coordinate.score(model)
    assertTrue(score.scores.map(_._2).collect.forall(MathUtils.isAlmostZero))

    // Update model
    val (newModel, _) = coordinate.updateModel(model)
    assertNotEquals(newModel, model)

    // Score after model update
    val newScore = coordinate.score(newModel)
    assertFalse(newScore.scores.map(_._2).collect.forall(MathUtils.isAlmostZero))
  }

  @Test(dataProvider = "numEntitiesDataProvider")
  def testScore(numEntities: Int): Unit = sparkTest("testScore") {
    val (coordinate, model) = generateRandomEffectCoordinateAndModel(
      randomEffectType,
      featureShardId,
      numEntities,
      NUM_TRAINING_SAMPLES,
      DIMENSIONALITY)

    val score = coordinate.score(model)

    assertEquals(score.scores.count, numEntities * NUM_TRAINING_SAMPLES)
    assertTrue(score.scores.map(_._2).collect.forall(MathUtils.isAlmostZero))
  }
}

object RandomEffectCoordinateIntegTest {
  private val NUM_TRAINING_SAMPLES = 1000
  private val DIMENSIONALITY = 10
}
