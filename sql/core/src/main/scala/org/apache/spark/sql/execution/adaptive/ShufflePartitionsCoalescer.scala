/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.adaptive

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.MapOutputStatistics
import org.apache.spark.internal.Logging

object ShufflePartitionsCoalescer extends Logging {

  /**
   * Coalesce the same range of partitions (`firstPartitionIndex`` to `lastPartitionIndex`, the
   * start is inclusive and the end is exclusive) from multiple shuffles. This method assumes that
   * all the shuffles have the same number of partitions, and the partitions of same index will be
   * read together by one task.
   *
   * The strategy used to determine the number of coalesced partitions is described as follows.
   * To determine the number of coalesced partitions, we have a target size for a coalesced
   * partition. Once we have size statistics of all shuffle partitions, we will do
   * a pass of those statistics and pack shuffle partitions with continuous indices to a single
   * coalesced partition until adding another shuffle partition would cause the size of a
   * coalesced partition to be greater than the target size.
   *
   * For example, we have two shuffles with the following partition size statistics:
   *  - shuffle 1 (5 partitions): [100 MiB, 20 MiB, 100 MiB, 10MiB, 30 MiB]
   *  - shuffle 2 (5 partitions): [10 MiB,  10 MiB, 70 MiB,  5 MiB, 5 MiB]
   * Assuming the target size is 128 MiB, we will have 4 coalesced partitions, which are:
   *  - coalesced partition 0: shuffle partition 0 (size 110 MiB)
   *  - coalesced partition 1: shuffle partition 1 (size 30 MiB)
   *  - coalesced partition 2: shuffle partition 2 (size 170 MiB)
   *  - coalesced partition 3: shuffle partition 3 and 4 (size 50 MiB)
   *
   *  @return An array of partition indices which represents the coalesced partitions. For example,
   *          [0, 2, 3] means 3 coalesced partitions: [0, 2), [2, 3), [3, lastPartitionIndex]
   */
  def coalescePartitions(
      mapOutputStatistics: Array[MapOutputStatistics],
      firstPartitionIndex: Int,
      lastPartitionIndex: Int,
      advisoryTargetSize: Long,
      minNumPartitions: Int = 1): Array[Int] = {
    // If `minNumPartitions` is very large, it is possible that we need to use a value less than
    // `advisoryTargetSize` as the target size of a coalesced task.
    val totalPostShuffleInputSize = mapOutputStatistics.map(_.bytesByPartitionId.sum).sum
    // The max at here is to make sure that when we have an empty table, we only have a single
    // coalesced partition.
    // There is no particular reason that we pick 16. We just need a number to prevent
    // `maxTargetSize` from being set to 0.
    val maxTargetSize = math.max(
      math.ceil(totalPostShuffleInputSize / minNumPartitions.toDouble).toLong, 16)
    val targetSize = math.min(maxTargetSize, advisoryTargetSize)

    logInfo(s"advisory target size: $advisoryTargetSize, actual target size $targetSize.")

    // Make sure these shuffles have the same number of partitions.
    val distinctNumShufflePartitions =
      mapOutputStatistics.map(stats => stats.bytesByPartitionId.length).distinct
    // The reason that we are expecting a single value of the number of shuffle partitions
    // is that when we add Exchanges, we set the number of shuffle partitions
    // (i.e. map output partitions) using a static setting, which is the value of
    // `spark.sql.shuffle.partitions`. Even if two input RDDs are having different
    // number of partitions, they will have the same number of shuffle partitions
    // (i.e. map output partitions).
    assert(
      distinctNumShufflePartitions.length == 1,
      "There should be only one distinct value of the number of shuffle partitions " +
        "among registered Exchange operators.")

    val splitPoints = ArrayBuffer[Int]()
    splitPoints += firstPartitionIndex
    var coalescedSize = 0L
    var i = firstPartitionIndex
    while (i < lastPartitionIndex) {
      // We calculate the total size of i-th shuffle partitions from all shuffles.
      var totalSizeOfCurrentPartition = 0L
      var j = 0
      while (j < mapOutputStatistics.length) {
        totalSizeOfCurrentPartition += mapOutputStatistics(j).bytesByPartitionId(i)
        j += 1
      }

      // If including the `totalSizeOfCurrentPartition` would exceed the target size, then start a
      // new coalesced partition.
      if (i > firstPartitionIndex && coalescedSize + totalSizeOfCurrentPartition > targetSize) {
        splitPoints += i
        // reset postShuffleInputSize.
        coalescedSize = totalSizeOfCurrentPartition
      } else {
        coalescedSize += totalSizeOfCurrentPartition
      }
      i += 1
    }

    splitPoints.toArray
  }
}
