// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package com.dmetasoul.lakesoul.tables

import com.alibaba.fastjson.JSON
import com.dmetasoul.lakesoul.meta.DBConfig.{LAKESOUL_HASH_PARTITION_SPLITTER, LAKESOUL_RANGE_PARTITION_SPLITTER, TableInfoProperty}
import com.dmetasoul.lakesoul.meta.entity._
import com.dmetasoul.lakesoul.meta._
import com.dmetasoul.lakesoul.tables.execution.LakeSoulTableOperations
import org.apache.hadoop.fs.Path
import org.apache.spark.internal.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.arrow.{CompactBucketIO, CompressDataFileInfo}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.execution.datasources.v2.merge.parquet.batch.merge_operator.MergeOperator
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.lakesoul.catalog.LakeSoulCatalog
import org.apache.spark.sql.lakesoul.exception.LakeSoulErrors
import org.apache.spark.sql.lakesoul.sources.LakeSoulSourceUtils
import org.apache.spark.sql.lakesoul.utils.SparkUtil.tryWithResource
import org.apache.spark.sql.lakesoul.utils.{MetaInfo, SparkUtil, TableInfo, TimestampFormatter}
import org.apache.spark.sql.lakesoul.{LakeSoulOptions, LakeSoulUtils, PartitionFilter, SnapshotManagement}
import org.apache.spark.{SerializableWritable, TaskContext}

import java.util.{TimeZone, UUID}
import scala.collection.JavaConverters._

class LakeSoulTable(df: => Dataset[Row], snapshotManagement: SnapshotManagement)
  extends LakeSoulTableOperations with Logging {
  /**
    * Apply an alias to the LakeSoulTableRel. This is similar to `Dataset.as(alias)` or
    * SQL `tableName AS alias`.
    *
    */
  def as(alias: String): LakeSoulTable = new LakeSoulTable(df.as(alias), snapshotManagement)

  /**
    * Apply an alias to the LakeSoulTableRel. This is similar to `Dataset.as(alias)` or
    * SQL `tableName AS alias`.
    *
    */
  def alias(alias: String): LakeSoulTable = as(alias)


  /**
    * Get a DataFrame (that is, Dataset[Row]) representation of this LakeSoulTableRel.
    *
    */
  def toDF: Dataset[Row] = df


  /**
    * Delete data from the table that match the given `condition`.
    *
    * @param condition Boolean SQL expression
    */
  def delete(condition: String): Unit = {
    delete(functions.expr(condition))
  }

  /**
    * Delete data from the table that match the given `condition`.
    *
    * @param condition Boolean SQL expression
    */
  def delete(condition: Column): Unit = {
    executeDelete(Some(condition.expr))
  }

  /**
    * Delete data from the table.
    *
    */
  def delete(): Unit = {
    executeDelete(None)
  }


  /**
    * Update rows in the table based on the rules defined by `set`.
    *
    * Scala example to increment the column `data`.
    * {{{
    *    import org.apache.spark.sql.functions._
    *
    *    lakeSoulTable.update(Map("data" -> col("data") + 1))
    * }}}
    *
    * @param set rules to update a row as a Scala map between target column names and
    *            corresponding update expressions as Column objects.
    */
  def update(set: Map[String, Column]): Unit = {
    executeUpdate(set, None)
  }

  /**
    * Update rows in the table based on the rules defined by `set`.
    *
    * Java example to increment the column `data`.
    * {{{
    *    import org.apache.spark.sql.Column;
    *    import org.apache.spark.sql.functions;
    *
    *    lakeSoulTable.update(
    *      new HashMap<String, Column>() {{
    *        put("data", functions.col("data").plus(1));
    *      }}
    *    );
    * }}}
    *
    * @param set rules to update a row as a Java map between target column names and
    *            corresponding update expressions as Column objects.
    */
  def update(set: java.util.Map[String, Column]): Unit = {
    executeUpdate(set.asScala.toMap, None)
  }

  /**
    * Update data from the table on the rows that match the given `condition`
    * based on the rules defined by `set`.
    *
    * Scala example to increment the column `data`.
    * {{{
    *    import org.apache.spark.sql.functions._
    *
    *    lakeSoulTable.update(
    *      col("date") > "2018-01-01",
    *      Map("data" -> col("data") + 1))
    * }}}
    *
    * @param condition boolean expression as Column object specifying which rows to update.
    * @param set       rules to update a row as a Scala map between target column names and
    *                  corresponding update expressions as Column objects.
    */
  def update(condition: Column, set: Map[String, Column]): Unit = {
    executeUpdate(set, Some(condition))
  }

  /**
    * Update data from the table on the rows that match the given `condition`
    * based on the rules defined by `set`.
    *
    * Java example to increment the column `data`.
    * {{{
    *    import org.apache.spark.sql.Column;
    *    import org.apache.spark.sql.functions;
    *
    *    lakeSoulTable.update(
    *      functions.col("date").gt("2018-01-01"),
    *      new HashMap<String, Column>() {{
    *        put("data", functions.col("data").plus(1));
    *      }}
    *    );
    * }}}
    *
    * @param condition boolean expression as Column object specifying which rows to update.
    * @param set       rules to update a row as a Java map between target column names and
    *                  corresponding update expressions as Column objects.
    */
  def update(condition: Column, set: java.util.Map[String, Column]): Unit = {
    executeUpdate(set.asScala.toMap, Some(condition))
  }

  /**
    * Update rows in the table based on the rules defined by `set`.
    *
    * Scala example to increment the column `data`.
    * {{{
    *    lakeSoulTable.updateExpr(Map("data" -> "data + 1")))
    * }}}
    *
    * @param set rules to update a row as a Scala map between target column names and
    *            corresponding update expressions as SQL formatted strings.
    */
  def updateExpr(set: Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set), None)
  }

  /**
    * Update rows in the table based on the rules defined by `set`.
    *
    * Java example to increment the column `data`.
    * {{{
    *    lakeSoulTable.updateExpr(
    *      new HashMap<String, String>() {{
    *        put("data", "data + 1");
    *      }}
    *    );
    * }}}
    *
    * @param set rules to update a row as a Java map between target column names and
    *            corresponding update expressions as SQL formatted strings.
    */
  def updateExpr(set: java.util.Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set.asScala.toMap), None)
  }

  /**
    * Update data from the table on the rows that match the given `condition`,
    * which performs the rules defined by `set`.
    *
    * Scala example to increment the column `data`.
    * {{{
    *    lakeSoulTable.update(
    *      "date > '2018-01-01'",
    *      Map("data" -> "data + 1"))
    * }}}
    *
    * @param condition boolean expression as SQL formatted string object specifying
    *                  which rows to update.
    * @param set       rules to update a row as a Scala map between target column names and
    *                  corresponding update expressions as SQL formatted strings.
    */
  def updateExpr(condition: String, set: Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set), Some(functions.expr(condition)))
  }

  /**
    * Update data from the table on the rows that match the given `condition`,
    * which performs the rules defined by `set`.
    *
    * Java example to increment the column `data`.
    * {{{
    *    lakeSoulTable.update(
    *      "date > '2018-01-01'",
    *      new HashMap<String, String>() {{
    *        put("data", "data + 1");
    *      }}
    *    );
    * }}}
    *
    * @param condition boolean expression as SQL formatted string object specifying
    *                  which rows to update.
    * @param set       rules to update a row as a Java map between target column names and
    *                  corresponding update expressions as SQL formatted strings.
    */
  def updateExpr(condition: String, set: java.util.Map[String, String]): Unit = {
    executeUpdate(toStrColumnMap(set.asScala.toMap), Some(functions.expr(condition)))
  }


  /**
    * Upsert LakeSoul table with source dataframe.
    *
    * Example:
    * {{{
    *   lakeSoulTable.upsert(sourceDF)
    *   lakeSoulTable.upsert(sourceDF, "range_col1='a' and range_col2='b'")
    * }}}
    *
    * @param source    source dataframe
    * @param condition you can define a condition to filter LakeSoul data
    */
  def upsert(source: DataFrame, condition: String = ""): Unit = {
    executeUpsert(this, source, condition)
  }

  /**
    * Upsert LakeSoul join table with delta dataframe from a dimension table.
    *
    * Example:
    * {{{
    *   lakeSoulTable.upsertOnJoinKey(deltaDF, Seq("uuid"))
    *   lakeSoulTable.upsertOnJoinKey(deltaDF, Seq("uuid"), Seq("range1=1", "range2=2")
    * }}}
    *
    * @param deltaDF       delta dataframe from a dimension table
    * @param joinKey       used to join with fact table
    * @param partitionDesc used to join with data in specific range partition
    * @param condition     you can define a condition to filter LakeSoul data
    */
  def upsertOnJoinKey(deltaDF: DataFrame, joinKey: Seq[String], partitionDesc: Seq[String] = Seq.empty[String], condition: String = ""): Unit = {
    executeUpsertOnJoinKey(deltaDF, joinKey, partitionDesc, condition)
  }

  /**
    * Upsert LakeSoul join table with delta dataframe from fact table.
    *
    * Example:
    * {{{
    *   lakeSoulTable.joinWithTablePathsAndUpsert(deltaDF, Seq("s3://lakesoul/dimension_table"))
    *   lakeSoulTable.joinWithTablePathsAndUpsert(deltaDF, Seq("s3://lakesoul/dimension_table"), Seq(Seq("range1=1","range2=2")))
    * }}}
    *
    * @param deltaDF            delta dataframe from fact table(join table)
    * @param tablePaths         paths of dimension tables which need to join with deltaDf
    * @param tablePartitionDesc used to join with data in specific range partition
    * @param condition          you can define a condition to filter LakeSoul data
    */
  def joinWithTablePathsAndUpsert(deltaDF: DataFrame, tablePaths: Seq[String], tablePartitionDesc: Seq[Seq[String]] = Seq.empty[Seq[String]], condition: String = ""): Unit = {
    executeJoinWithTablePathsAndUpsert(deltaDF, tablePaths, tablePartitionDesc, condition)
  }

  /**
    * Upsert LakeSoul join table with delta dataframe from fact table.
    *
    * Example:
    * {{{
    *   lakeSoulTable.joinWithTableNamesAndUpsert(deltaDF, Seq("dimension_table_name"))
    *   lakeSoulTable.joinWithTableNamesAndUpsert(deltaDF, Seq("dimension_table_name""), Seq(Seq("range1=1","range2=2")))
    * }}}
    *
    * @param deltaDF            left table delta dataframe
    * @param tableNames         names of dimension tables which need to join with deltaDf
    * @param tablePartitionDesc used to join with data in specific range partition
    * @param condition          you can define a condition to filter LakeSoul data
    */
  def joinWithTableNamesAndUpsert(deltaDF: DataFrame, tableNames: Seq[String], tablePartitionDesc: Seq[Seq[String]] = Seq.empty[Seq[String]], condition: String = ""): Unit = {
    executeJoinWithTableNamesAndUpsert(deltaDF, tableNames, tablePartitionDesc, condition)
  }

  //by default, force perform compaction on whole table
  def compaction(condition: String = "",
                 force: Boolean = true,
                 mergeOperatorInfo: Map[String, Any] = Map.empty,
                 hiveTableName: String = "",
                 hivePartitionName: String = "",
                 cleanOldCompaction: Boolean = false,
                 fileNumLimit: Option[Int] = None,
                 newBucketNum: Option[Int] = None,
                 fileSizeLimit: Option[String] = None,
                ): Unit = {
    val newMergeOpInfo = mergeOperatorInfo.map(m => {
      val key =
        if (!m._1.startsWith(LakeSoulUtils.MERGE_OP_COL)) {
          s"${LakeSoulUtils.MERGE_OP_COL}${m._1}"
        } else {
          m._1
        }
      val value = m._2 match {
        case cls: MergeOperator[Any] => cls.getClass.getName
        case name: String => name
        case _ => throw LakeSoulErrors.illegalMergeOperatorException(m._2)
      }
      (key, value)
    })

    val parsedFileSizeLimit = fileSizeLimit.map(DBUtil.parseMemoryExpression)
    executeCompaction(df, snapshotManagement, condition, force, newMergeOpInfo, hiveTableName, hivePartitionName, cleanOldCompaction, fileNumLimit, newBucketNum, parsedFileSizeLimit)
  }

  def newCompaction(conditionStr: String = "",
                    hiveTableName: String = "",
                    hivePartitionName: String = "",
                    cleanOldCompaction: Boolean = false,
                    fileNumLimit: Option[Int] = None,
                    fileSizeLimit: Option[String] = None,
                    newBucketNum: Option[Int] = None
                   ): Unit = {
    val tableInfo = snapshotManagement.getTableInfoOnly
    val tablePath = tableInfo.table_path.toString
    val tableHashBucketNum = if (newBucketNum.isDefined) newBucketNum.get else tableInfo.bucket_num
    val parsedFileNumLimit = if (fileNumLimit.isDefined) fileNumLimit.get else Int.MaxValue
    val parsedFileSizeLimit = if (fileSizeLimit.isDefined) fileSizeLimit.map(DBUtil.parseMemoryExpression).get else Long
      .MaxValue
    val condition = conditionStr match {
      case "" => None
      case _: String => Option(expr(conditionStr).expr)
    }
    val spark = SparkSession.active

    def executeCompactOnePartition(part: PartitionInfoScala): Unit = {
      val files = DataOperation.getSinglePartitionDataInfo(part)
      if (files.nonEmpty) {
        val bucketToFiles = if (tableInfo.hash_partition_columns.isEmpty) {
          Seq(files)
        } else {
          files.groupBy(_.file_bucket_id).values.toSeq
        }
        val fileRDD = spark.sparkContext.parallelize(bucketToFiles, bucketToFiles.size)
        val configuration = new SerializableWritable(spark.sessionState.newHadoopConf())
        val partitionValues = part.range_value
        val compactResult = fileRDD.map {
          dataFileInfo => {
            val taskId = TaskContext.get().partitionId()
            val needDealFileInfo = dataFileInfo.map(file => {
              new CompressDataFileInfo(file.path, file.size, file.file_exist_cols, file.modification_time)
            }).toList.asJava
            tryWithResource(new CompactBucketIO(
              configuration.value,
              needDealFileInfo,
              tableInfo,
              tablePath,
              partitionValues,
              tableHashBucketNum,
              parsedFileNumLimit,
              parsedFileSizeLimit,
              tableInfo.bucket_num != tableHashBucketNum,
              taskId
            )) { compactionBucketIO =>
              val partitionDescAndFilesMap = compactionBucketIO.startCompactTask().asScala
              partitionDescAndFilesMap.flatMap(result => {
                val (partitionDesc, flushResult) = result
                val array = flushResult.asScala.map(
                  f => DataFileInfo(partitionDesc, f.getFilePath, "add", f.getFileSize, f.getTimestamp,
                    f.getFileExistCols))
                array
              }).toSeq
            }
          }
        }
        val dataFileInfoSeq = compactResult.flatMap(ff => ff).collect().toSeq
        if (dataFileInfoSeq.nonEmpty) {
          commitMetadata(dataFileInfoSeq, partitionValues, tableInfo, part)
        } else {
          println(s"[WARN] read file size is ${files.length}, but without file created after compaction")
        }
      }

      def commitMetadata(dataFileInfo: Seq[DataFileInfo], rangePartition: String, tableInfo: TableInfo,
                         readPartitionInfo: PartitionInfoScala): Unit = {
        val add_file_arr_buf = List.newBuilder[DataCommitInfo]
        val addUUID = UUID.randomUUID()
        val timestampFormatter =
          TimestampFormatter("yyy-MM-dd", java.util.TimeZone.getDefault)
        val discardFileInfo = dataFileInfo
          .filter(file => file.range_partitions.equals(CompactBucketIO.DISCARD_FILE_LIST_KEY))
        logInfo(s"compaction discarded files $discardFileInfo")
        val discardCompressedFileList = discardFileInfo.map { file =>
          DiscardCompressedFileInfo.newBuilder()
            .setFilePath(file.path)
            .setTablePath(tableInfo.table_path_s.get)
            .setPartitionDesc(rangePartition)
            .setTimestamp(file.modification_time)
            .setTDate(timestampFormatter.format(file.modification_time))
            .build()
        }
        val dataFileInfoAfterFilter = dataFileInfo
          .filter(file => !file.range_partitions.equals(CompactBucketIO.DISCARD_FILE_LIST_KEY))
        val fileOps = dataFileInfoAfterFilter.map { file =>
          DataFileOp.newBuilder()
            .setPath(file.path)
            .setFileOp(FileOp.add)
            .setSize(file.size)
            .setFileExistCols(file.file_exist_cols)
            .build()
        }
        add_file_arr_buf += DataCommitInfo.newBuilder()
          .setTableId(tableInfo.table_id)
          .setPartitionDesc(rangePartition)
          .setCommitId(
            Uuid.newBuilder().setHigh(addUUID.getMostSignificantBits).setLow(addUUID.getLeastSignificantBits).build())
          .addAllFileOps(fileOps.toList.asJava)
          .setCommitOp(CommitOp.CompactionCommit)
          .setTimestamp(System.currentTimeMillis())
          .setCommitted(false)
          .build()

        val add_partition_info_arr_buf = List.newBuilder[PartitionInfoScala]
        add_partition_info_arr_buf += PartitionInfoScala(
          table_id = tableInfo.table_id,
          range_value = rangePartition,
          read_files = Array(addUUID)
        )

        val meta_info = MetaInfo(
          table_info = tableInfo,
          dataCommitInfo = add_file_arr_buf.result().toArray,
          partitionInfoArray = add_partition_info_arr_buf.result().toArray,
          commit_type = CommitType("compaction"),
          query_id = "",
          batch_id = -1,
          readPartitionInfo = Array(readPartitionInfo)
        )

        MetaCommit.doMetaCommit(meta_info, changeSchema = false)
        MetaCommit.recordDiscardFileInfo(discardCompressedFileList.toList.asJava)
      }
    }

    if (condition.isDefined) {
      val partitionFilters = Seq(condition.get).flatMap { filter =>
        LakeSoulUtils.splitMetadataAndDataPredicates(filter, tableInfo.range_partition_columns, spark)._1
      }
      if (partitionFilters.isEmpty) {
        throw LakeSoulErrors.partitionColumnNotFoundException(condition.get, 0)
      }
      val partitionFilterInfo = PartitionFilter.partitionsForScan(snapshotManagement.snapshot, partitionFilters)
      if (partitionFilterInfo.nonEmpty) {
        if (partitionFilterInfo.length < 1) {
          throw LakeSoulErrors.partitionColumnNotFoundException(condition.get, 0)
        } else if (partitionFilterInfo.length > 1) {
          throw LakeSoulErrors.partitionColumnNotFoundException(condition.get, partitionFilterInfo.size)
        }
        val partitionInfo = SparkMetaVersion.getSinglePartitionInfo(
          tableInfo.table_id,
          partitionFilterInfo.head.range_value,
          ""
        )
        executeCompactOnePartition(partitionInfo)
      }
    } else {
      val allInfo = SparkMetaVersion.getAllPartitionInfo(tableInfo.table_id)
      val partitionsNeedCompact = allInfo.filter(_.read_files.length >= 1)
      partitionsNeedCompact.foreach(part => {
        executeCompactOnePartition(part)
      })
    }

    if (newBucketNum.isDefined) {
      val properties = SparkMetaVersion.dbManager.getTableInfoByTableId(tableInfo.table_id).getProperties
      val newProperties = JSON.parseObject(properties);
      newProperties.put(TableInfoProperty.HASH_BUCKET_NUM, newBucketNum.get.toString)
      SparkMetaVersion.dbManager.updateTableProperties(tableInfo.table_id, newProperties.toJSONString)
      snapshotManagement.updateSnapshot()
    }
  }

    def setCompactionTtl(days: Int): LakeSoulTable = {
      executeSetCompactionTtl(snapshotManagement, days)
      this
    }

  def setPartitionTtl(days: Int): LakeSoulTable = {
    executeSetPartitionTtl(snapshotManagement, days)
    this
  }

  def onlySaveOnceCompaction(value: Boolean): LakeSoulTable = {
    executeSetOnlySaveOnceCompactionValue(snapshotManagement, value)
    this
  }

  def cancelCompactionTtl(): Boolean = {
    executeCancelCompactionTtl(snapshotManagement)
    true
  }

  def cancelPartitionTtl(): Boolean = {
    executeCancelPartitionTtl(snapshotManagement)
    true
  }

  def dropTable(): Boolean = {
    executeDropTable(snapshotManagement)
    true
  }

  def truncateTable(): Boolean = {
    executeTruncateTable(snapshotManagement)
    true
  }

  def dropPartition(condition: String): Unit = {
    dropPartition(functions.expr(condition).expr)
  }

  def dropPartition(condition: Expression): Unit = {
    assert(snapshotManagement.snapshot.getTableInfo.range_partition_columns.nonEmpty,
      s"Table `${snapshotManagement.table_path}` is not a range partitioned table, dropTable command can't use on it.")
    executeDropPartition(snapshotManagement, condition)
  }

  def rollbackPartition(partitionValue: String, toVersionNum: Int): Unit = {
    SparkMetaVersion.rollbackPartitionInfoByVersion(snapshotManagement.getTableInfoOnly.table_id, partitionValue, toVersionNum)
  }

  def rollbackPartition(partitionValue: String, toTime: String, timeZoneID: String = ""): Unit = {
    val timeZone =
      if (timeZoneID.equals("") || !TimeZone.getAvailableIDs.contains(timeZoneID)) TimeZone.getDefault
      else TimeZone.getTimeZone(timeZoneID)
    val endTime = TimestampFormatter.apply(timeZone).parse(toTime) / 1000
    val version = SparkMetaVersion.getLastedVersionUptoTime(snapshotManagement.getTableInfoOnly.table_id, partitionValue, endTime)
    if (version < 0) {
      println("No version found in Table before time")
    } else {
      rollbackPartition(partitionValue, version)
    }
  }

  def cleanupPartitionData(partitionDesc: String, toTime: String, timeZoneID: String = ""): Unit = {
    val timeZone =
      if (timeZoneID.equals("") || !TimeZone.getAvailableIDs.contains(timeZoneID)) TimeZone.getDefault
      else TimeZone.getTimeZone(timeZoneID)
    val endTime = TimestampFormatter.apply(timeZone).parse(toTime) / 1000
    assert(snapshotManagement.snapshot.getTableInfo.range_partition_columns.nonEmpty,
      s"Table `${snapshotManagement.table_path}` is not a range partitioned table, dropTable command can't use on it.")
    executeCleanupPartition(snapshotManagement, partitionDesc, endTime)
  }
}

object LakeSoulTable {
  /**
    * Create a LakeSoulTableRel for the data at the given `path`.
    *
    * Note: This uses the active SparkSession in the current thread to read the table data. Hence,
    * this throws error if active SparkSession has not been set, that is,
    * `SparkSession.getActiveSession()` is empty.
    *
    */
  def forPath(path: String): LakeSoulTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }

    forPath(sparkSession, path)
  }

  /**
    * uncache all or one table from snapshotmanagement
    * partiton time travel needs to clear snapshot version info to avoid conflict with other read tasks
    * for example
    * LakeSoulTable.forPath(tablePath,"range=range1",0).toDF.show()
    * LakeSoulTable.uncached(tablePath)
    *
    * @param path
    */
  def uncached(path: String = ""): Unit = {
    if (path.equals("")) {
      SnapshotManagement.clearCache()
    } else {
      val p = SparkUtil.makeQualifiedTablePath(new Path(path)).toUri.toString
      if (!LakeSoulSourceUtils.isLakeSoulTableExists(p)) {
        println("table not in lakesoul. Please check table path")
        return
      }
      SnapshotManagement.invalidateCache(p)
    }
  }


  /**
    * Create a LakeSoulTableRel for the data at the given `path` with time travel of one paritition .
    *
    */
  def forPath(path: String, partitionDesc: String, partitionVersion: Int): LakeSoulTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }

    forPath(sparkSession, path, partitionDesc, partitionVersion)
  }

  /** Snapshot Query to endTime
    */
  def forPathSnapshot(path: String, partitionDesc: String, endTime: String, timeZone: String = ""): LakeSoulTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }

    forPath(sparkSession, path, partitionDesc, "1970-01-01 00:00:00", endTime, timeZone, LakeSoulOptions.ReadType.SNAPSHOT_READ)
  }

  /** Incremental Query from startTime to now
    */
  def forPathIncremental(path: String, partitionDesc: String, startTime: String, endTime: String, timeZone: String = ""): LakeSoulTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }

    forPath(sparkSession, path, partitionDesc, startTime, endTime, timeZone, LakeSoulOptions.ReadType.INCREMENTAL_READ)
  }

  /**
    * Create a LakeSoulTableRel for the data at the given `path` using the given SparkSession.
    */
  def forPath(sparkSession: SparkSession, path: String): LakeSoulTable = {
    val p = SparkUtil.makeQualifiedTablePath(new Path(path)).toUri.toString
    if (LakeSoulUtils.isLakeSoulTable(sparkSession, new Path(p))) {
      new LakeSoulTable(sparkSession.read.format(LakeSoulSourceUtils.SOURCENAME).load(p),
        SnapshotManagement(p))
    } else {
      throw LakeSoulErrors.tableNotExistsException(p)
    }
  }

  def forPath(sparkSession: SparkSession, path: String, partitionDesc: String, partitionVersion: Int): LakeSoulTable = {
    val p = SparkUtil.makeQualifiedTablePath(new Path(path)).toUri.toString
    if (LakeSoulUtils.isLakeSoulTable(sparkSession, new Path(p))) {
      new LakeSoulTable(sparkSession.read.format(LakeSoulSourceUtils.SOURCENAME).load(p),
        SnapshotManagement(p, partitionDesc, partitionVersion))
    } else {
      throw LakeSoulErrors.tableNotExistsException(path)
    }
  }

  /*
  *   startTime 2022-10-01 13:45:30
  *   endTime 2022-10-01 13:46:30
  * */
  def forPath(sparkSession: SparkSession, path: String, partitionDesc: String, startTimeStamp: String, endTimeStamp: String, timeZone: String, readType: String): LakeSoulTable = {
    val timeZoneID = if (timeZone.equals("") || !TimeZone.getAvailableIDs.contains(timeZone)) TimeZone.getDefault.getID else timeZone
    val startTime = TimestampFormatter.apply(TimeZone.getTimeZone(timeZoneID)).parse(startTimeStamp)
    val endTime = TimestampFormatter.apply(TimeZone.getTimeZone(timeZoneID)).parse(endTimeStamp)
    val p = SparkUtil.makeQualifiedTablePath(new Path(path)).toUri.toString
    if (LakeSoulUtils.isLakeSoulTable(sparkSession, new Path(p))) {
      if (endTime < 0) {
        println("No version found in Table before time")
        null
      } else {
        new LakeSoulTable(sparkSession.read.format(LakeSoulSourceUtils.SOURCENAME).load(p),
          SnapshotManagement(p, partitionDesc, startTime / 1000, endTime / 1000, readType))
      }

    } else {
      throw LakeSoulErrors.tableNotExistsException(path)
    }
  }


  /**
    * Create a LakeSoulTableRel using the given table name using the given SparkSession.
    *
    * Note: This uses the active SparkSession in the current thread to read the table data. Hence,
    * this throws error if active SparkSession has not been set, that is,
    * `SparkSession.getActiveSession()` is empty.
    */
  def forName(tableOrViewName: String): LakeSoulTable = {
    forName(tableOrViewName, LakeSoulCatalog.showCurrentNamespace().mkString("."))
  }

  def forName(tableOrViewName: String, namespace: String): LakeSoulTable = {
    val sparkSession = SparkSession.getActiveSession.getOrElse {
      throw new IllegalArgumentException("Could not find active SparkSession")
    }
    forName(sparkSession, tableOrViewName, namespace)
  }

  /**
    * Create a LakeSoulTableRel using the given table or view name using the given SparkSession.
    */
  def forName(sparkSession: SparkSession, tableName: String): LakeSoulTable = {
    forName(sparkSession, tableName, LakeSoulCatalog.showCurrentNamespace().mkString("."))
  }

  def forName(sparkSession: SparkSession, tableName: String, namespace: String): LakeSoulTable = {
    val (exists, tablePath) = SparkMetaVersion.isShortTableNameExists(tableName, namespace)
    if (exists) {
      new LakeSoulTable(sparkSession.table(s"$namespace.$tableName"),
        SnapshotManagement(tablePath, namespace))
    } else {
      throw LakeSoulErrors.notALakeSoulTableException(tableName)
    }
  }

  def isLakeSoulTable(tablePath: String): Boolean = {
    LakeSoulUtils.isLakeSoulTable(tablePath)
  }

  def registerMergeOperator(spark: SparkSession, className: String, funName: String): Unit = {
    LakeSoulUtils.getClass(className).getConstructors()(0)
      .newInstance()
      .asInstanceOf[MergeOperator[Any]]
      .register(spark, funName)
  }

  class TableCreator {
    private[this] val options = new scala.collection.mutable.HashMap[String, String]
    private[this] var writeData: Dataset[_] = _
    private[this] var tablePath: String = _

    def data(data: Dataset[_]): TableCreator = {
      writeData = data
      this
    }

    def path(path: String): TableCreator = {
      tablePath = path
      this
    }

    //set range partition columns, join with a comma
    def rangePartitions(rangePartitions: String): TableCreator = {
      options.put("rangePartitions", rangePartitions)
      this
    }

    def rangePartitions(rangePartitions: Seq[String]): TableCreator = {
      options.put("rangePartitions", rangePartitions.mkString(LAKESOUL_RANGE_PARTITION_SPLITTER))
      this
    }

    //set hash partition columns, join with a comma
    def hashPartitions(hashPartitions: String): TableCreator = {
      options.put("hashPartitions", hashPartitions)
      this
    }

    def hashPartitions(hashPartitions: Seq[String]): TableCreator = {
      options.put("hashPartitions", hashPartitions.mkString(LAKESOUL_HASH_PARTITION_SPLITTER))
      this
    }

    def hashBucketNum(hashBucketNum: Int): TableCreator = {
      options.put("hashBucketNum", hashBucketNum.toString)
      this
    }

    def hashBucketNum(hashBucketNum: String): TableCreator = {
      options.put("hashBucketNum", hashBucketNum)
      this
    }

    //set a short table name
    def shortTableName(shortTableName: String): TableCreator = {
      options.put("shortTableName", shortTableName)
      this
    }

    def tableProperty(kv: (String, String)): TableCreator = {
      options.put(kv._1, kv._2)
      this
    }

    def create(): Unit = {
      val writer = writeData.write.format(LakeSoulSourceUtils.NAME).mode("overwrite")
      options.foreach(f => writer.option(f._1, f._2))
      writer.save(tablePath)
    }

  }

  def createTable(data: Dataset[_], tablePath: String): TableCreator =
    new TableCreator().data(data).path(tablePath)

}
