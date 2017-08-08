/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.featran.transformers

import com.spotify.featran.FeatureBuilder
import com.twitter.algebird._

import scala.util.hashing.MurmurHash3

object HashOneHotEncoder {
  /**
   * Transform a collection of categorical features to binary columns, with at most a single
   * one-value. Similar to [[OneHotEncoder]] but uses MurmursHash3 to hash features into buckets
   * to reduce CPU and memory overhead.
   *
   * Missing values are transformed to [0.0, 0.0, ...].
   *
   * @param hashBucketSize number of buckets, or 0 to infer from data with HyperLogLog
   */
  def apply(name: String, hashBucketSize: Int = 0): Transformer[String, HLL, Int] =
    new HashOneHotEncoder(name, hashBucketSize)
}

private class HashOneHotEncoder(name: String, hashBucketSize: Int)
  extends BaseHashHotEncoder[String](name, hashBucketSize) {
  override def prepare(a: String): HLL = hllMonoid.toHLL(a)

  override def buildFeatures(a: Option[String], c: Int, fb: FeatureBuilder[_]): Unit = {
    fb.init(c)
    a match {
      case Some(x) =>
        val i = HashEncoder.bucket(x, c)
        fb.skip(i)
        fb.add(name + '_' + i, 1.0)
        fb.skip(math.max(0, c - i - 1))
      case None =>
        fb.skip(c)
    }
  }
}

private abstract class BaseHashHotEncoder[A](name: String, hashBucketSize: Int)
  extends Transformer[A, HLL, Int](name) {

  private val hllBits = 12
  implicit protected val hllMonoid = new HyperLogLogMonoid(hllBits)

  def prepare(a: A): HLL

  private def present(reduction: HLL): Int =
    if (hashBucketSize == 0) reduction.estimatedSize.toInt else hashBucketSize

  override val aggregator: Aggregator[A, HLL, Int] =
    if (hashBucketSize == 0) {
      Aggregators.from[A](prepare).to(present)
    } else {
      // dummy aggregator
      new Aggregator[A, HLL, Int] {
        override def prepare(input: A): HLL = SparseHLL(4, Map.empty)
        override def semigroup: Semigroup[HLL] = Semigroup.from[HLL]((x, _) => x)
        override def present(reduction: HLL): Int = hashBucketSize
      }
    }
  override def featureDimension(c: Int): Int = c
  override def featureNames(c: Int): Seq[String] = names(c).toSeq

  override def encodeAggregator(c: Option[Int]): Option[String] = c.map(_.toString)
  override def decodeAggregator(s: Option[String]): Option[Int] = s.map(_.toInt)
}

private object HashEncoder {
  def bucket(x: String, c: Int): Int = (MurmurHash3.stringHash(x) % c + c) % c
}