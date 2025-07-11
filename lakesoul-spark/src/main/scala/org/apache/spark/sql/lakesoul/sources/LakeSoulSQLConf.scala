// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.spark.sql.lakesoul.sources

import org.apache.spark.internal.config.{ConfigBuilder, ConfigEntry}
import org.apache.spark.sql.internal.SQLConf

object LakeSoulSQLConf {

  def buildConf(key: String): ConfigBuilder = SQLConf.buildConf(s"spark.dmetasoul.lakesoul.$key")

  val SCHEMA_AUTO_MIGRATE: ConfigEntry[Boolean] =
    buildConf("schema.autoMerge.enabled")
      .doc("If true, enables schema merging on appends and on overwrites.")
      .booleanConf
      .createWithDefault(false)

  val USE_DELTA_FILE: ConfigEntry[Boolean] =
    buildConf("deltaFile.enabled")
      .doc("If true, enables delta files on specific scene(e.g. upsert).")
      .booleanConf
      .createWithDefault(true)

  // drop table await time
  val DROP_TABLE_WAIT_SECONDS: ConfigEntry[Int] =
    buildConf("drop.table.wait.seconds")
      .doc(
        """
          |When dropping table or partition, we need wait a few seconds for the other commits to be completed.
        """.stripMargin)
      .intConf
      .createWithDefault(1)

  val ALLOW_FULL_TABLE_UPSERT: ConfigEntry[Boolean] =
    buildConf("full.partitioned.table.scan.enabled")
      .doc("If true, enables full table scan when upsert.")
      .booleanConf
      .createWithDefault(false)

  val PARQUET_COMPRESSION: ConfigEntry[String] =
    buildConf("parquet.compression")
      .doc(
        """
          |Parquet compression type.
        """.stripMargin)
      .stringConf
      .createWithDefault("snappy")

  val PARQUET_COMPRESSION_ENABLE: ConfigEntry[Boolean] =
    buildConf("parquet.compression.enable")
      .doc(
        """
          |Whether to use parquet compression.
        """.stripMargin)
      .booleanConf
      .createWithDefault(true)

  val BUCKET_SCAN_MULTI_PARTITION_ENABLE: ConfigEntry[Boolean] =
    buildConf("bucket.scan.multi.partition.enable")
      .doc(
        """
          |Hash partitioned table can read multi-partition data partitioned by hash keys without shuffle,
          |this parameter controls whether this feature is enabled or not.
          |Using this feature, the parallelism will equal to hash bucket num.
        """.stripMargin)
      .booleanConf
      .createWithDefault(false)

  val PART_MERGE_ENABLE: ConfigEntry[Boolean] =
    buildConf("part.merge.enable")
      .doc(
        """
          |If true, part files merging will be used to avoid OOM when it has too many delta files.
        """.stripMargin)
      .booleanConf
      .createWithDefault(false)

  val PART_MERGE_FILE_MINIMUM_NUM: ConfigEntry[Int] =
    buildConf("part.merge.file.minimum.num")
      .doc(
        """
          |If delta file num more than this count, we will check for part merge.
        """.stripMargin)
      .intConf
      .createWithDefault(5)

  val SNAPSHOT_CACHE_EXPIRE: ConfigEntry[Int] =
    buildConf("snapshot.cache.expire.seconds")
      .doc(
        """
          |Expire snapshot cache in seconds
        """.stripMargin)
      .intConf
      .createWithDefault(1)

  val NATIVE_IO_ENABLE: ConfigEntry[Boolean] =
    buildConf("native.io.enable")
      .doc(
        """
          |If ture, NativeIO would be enabled for both read and write
        """.stripMargin)
      .booleanConf
      .createWithDefault(true)

  val NATIVE_IO_CDC_COLUMN: ConfigEntry[String] =
    buildConf("native.io.cdc_column")
      .doc(
        """
          |If empty, table have no cdc column
        """.stripMargin)
      .stringConf
      .createWithDefault("")

  val NATIVE_IO_IS_COMPACTED: ConfigEntry[String] =
    buildConf("native.io.is_compacted")
      .doc(
        """
          |If ture, Native Reader would read data as compacted data
        """.stripMargin)
      .stringConf
      .createWithDefault("false")


  val NATIVE_IO_PREFETCHER_BUFFER_SIZE: ConfigEntry[Int] =
    buildConf("native.io.prefetch.buffer.size")
      .doc(
        """
          |If NATIVE_IO_ENABLE=true, NATIVE_IO_PREFETCHER_BUFFER_SIZE of batches will be buffered while native-io prefetching
        """.stripMargin)
      .intConf
      .createWithDefault(1)

  val NATIVE_IO_WRITE_MAX_ROW_GROUP_SIZE: ConfigEntry[Int] =
    buildConf("native.io.write.max.rowgroup.size")
      .doc(
        """
          |If NATIVE_IO_ENABLE=true, NATIVE_IO_WRITE_MAX_ROW_GROUP_SIZE of rows will be used to write a new row group
      """.stripMargin)
      .intConf
      .createWithDefault(100000)

  val NATIVE_IO_THREAD_NUM: ConfigEntry[Int] =
    buildConf("native.io.thread.num")
      .doc(
        """
          |If NATIVE_IO_ENABLE=true, tokio::runtime::Runtime will be build with NATIVE_IO_THREAD_NUM thread_num
        """.stripMargin)
      .intConf
      .createWithDefault(2)

  val NATIVE_IO_READER_AWAIT_TIMEOUT: ConfigEntry[Int] =
    buildConf("native.io.await.timeout")
      .doc(
        """
          |If NATIVE_IO_ENABLE=true, timeout for each iterate will be set to NATIVE_IO_READER_AWAIT_TIMEOUT mills
        """.stripMargin)
      .intConf
      .createWithDefault(10000)

  val RENAME_COMPACTED_FILE: ConfigEntry[Boolean] =
    buildConf("lakesoul.compact.rename")
      .doc(
        """
          |If NATIVE_IO_ENABLE=true, timeout for each iterate will be set to NATIVE_IO_READER_AWAIT_TIMEOUT mills
        """.stripMargin)
      .booleanConf
      .createWithDefault(false)

  val SCAN_FILE_NUMBER_LIMIT: ConfigEntry[Int] =
    buildConf("scan.file.number.limit")
      .doc(
        """
          |If SCAN_FILE_NUMBER_LIMIT < Int.MaxValue, Scan will scan file with number less than SCAN_FILE_NUMBER_LIMIT per file group
        """.stripMargin)
      .intConf
      .createWithDefault(Int.MaxValue)


  val COMPACTION_TASK: ConfigEntry[Boolean] =
    buildConf("scan.file.size.limit")
      .doc(
        """
          |If SCAN_FILE_NUMBER_LIMIT < Int.MaxValue, Scan will scan file with number less than SCAN_FILE_NUMBER_LIMIT per file group
        """.stripMargin)
      .booleanConf
      .createWithDefault(false)

  val COMPACTION_LEVEL1_FILE_NUM_LIMIT: ConfigEntry[Int] =
    buildConf("compaction.level1.file.number.limit")
      .doc(
        """
          |COMPACTION LEVEL1 SINGLE TASK READ FILE NUMBER, DEFAULT IS 20.
        """.stripMargin)
      .intConf
      .createWithDefault(20)

  val COMPACTION_LEVEL1_FILE_MERGE_SIZE_LIMIT: ConfigEntry[String] =
    buildConf("compaction.level1.file.merge.size.limit")
      .doc(
        """
          |COMPACTION LEVEL1 SINGLE TASK MERGE SIZE. Default is 1GB.
        """.stripMargin)
      .stringConf
      .createWithDefault("1GB")

  val COMPACTION_LEVEL1_FILE_MERGE_NUM_LIMIT: ConfigEntry[Int] =
    buildConf("compaction.level1.file.merge.num.limit")
      .doc(
        """
          |COMPACTION LEVEL1 SINGLE TASK MERGE NUM. Default is 5.
        """.stripMargin)
      .intConf
      .createWithDefault(5)

  val COMPACTION_LEVEL_MAX_FILE_SIZE: ConfigEntry[String] =
    buildConf("compaction.level.max.file.size")
      .doc(
        """
          |COMPACTION LEVEL FILE SIZE, DEFAULT IS 5G.
        """.stripMargin)
      .stringConf
      .createWithDefault("5GB")
}
