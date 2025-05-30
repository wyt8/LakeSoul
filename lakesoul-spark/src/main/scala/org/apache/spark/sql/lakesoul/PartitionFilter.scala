// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.spark.sql.lakesoul

import com.dmetasoul.lakesoul.meta.{DBConfig, DBUtil, DataFileInfo, DataOperation, MetaUtils, PartitionInfoScala, SparkMetaVersion}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.analysis.{Resolver, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeReference, Cast, Equality, Expression, IsNotNull, Literal, NamedExpression}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.lakesoul.utils.{PartitionFilterInfo, SparkUtil, TableInfo}
import org.apache.spark.sql.types.{BooleanType, StructField, StructType}
import org.apache.spark.sql.{Column, DataFrame, Dataset, SparkSession}

import java.util.UUID
import scala.collection.JavaConverters.mapAsScalaMapConverter

object PartitionFilter extends Logging {

  private def filterFromAllPartitionInfo(snapshot: Snapshot, table_info: TableInfo, filters: Seq[Expression]): Seq[PartitionFilterInfo] = {
    val spark = SparkSession.active
    val allPartitions = SparkUtil.allPartitionFilterInfoDF(snapshot)

    import spark.implicits._

    val filteredParts = filterFileList(
      table_info.range_partition_schema,
      allPartitions,
      filters).as[PartitionFilterInfo].collect()
    filteredParts.foreach(p => {
      snapshot.recordPartitionInfoRead(PartitionInfoScala(
        p.table_id,
        p.range_value,
        p.read_version,
        p.read_files.map(UUID.fromString),
        p.expression,
        p.commit_op
      ))
    })
    filteredParts
  }

  def partitionsForScan(snapshot: Snapshot, filters: Seq[Expression]): Seq[PartitionFilterInfo] = {
    val table_info = snapshot.getTableInfo

    val spark = SparkSession.active
    val partitionFilters = filters.flatMap { filter =>
      LakeSoulUtils.splitMetadataAndDataPredicates(filter, table_info.range_partition_columns, spark)._1
    }
    snapshot.getPartitionInfoFromCache(partitionFilters) match {
      case Some(info) => info.toSeq
      case None =>
        val infos = if (partitionFilters.isEmpty) {
          logInfo(s"no partition filter for table ${table_info.table_path}")
          filterFromAllPartitionInfo(snapshot, table_info, partitionFilters)
        } else {
          val equalExprs = partitionFilters.collect {
            case Equality(UnresolvedAttribute(nameParts), lit@Literal(_, _)) =>
              val colName = nameParts.last
              val colValue = lit.toString()
              colName -> colValue
            case Equality(NamedExpression(n), lit@Literal(_, _)) =>
              val colName = n._1
              val colValue = lit.toString()
              colName -> colValue
          }.toMap
          // remove useless isnotnull/true expr
          val newPartitionFilters = partitionFilters.filter({
            case IsNotNull(AttributeReference(name, _, _, _)) => equalExprs.nonEmpty && !equalExprs.contains(name)
            case Literal(v, BooleanType) if v.asInstanceOf[Boolean] => false
            case _ => true
          })

          if (table_info.range_partition_columns.nonEmpty && table_info.range_partition_columns.forall(p => {
            equalExprs.contains(p)
          })) {
            // optimize for all partition equality filter
            val partDesc = table_info.range_partition_columns.map(p => {
              val colValue = equalExprs(p)
              s"$p=$colValue"
            }).mkString(DBConfig.LAKESOUL_RANGE_PARTITION_SPLITTER)
            val partInfo = SparkMetaVersion.getSinglePartitionInfo(table_info.table_id, partDesc, "")
            logInfo(s"All equality partition filters $equalExprs for table ${table_info.table_path}, using" +
              s" desc $partDesc")
            if (partInfo == null) return Seq.empty
            snapshot.recordPartitionInfoRead(partInfo)
            Seq(PartitionFilterInfo(
              partDesc,
              equalExprs,
              partInfo.version,
              table_info.table_id,
              partInfo.read_files.map(u => u.toString),
              partInfo.expression,
              partInfo.commit_op
            ))
          } else if (equalExprs.size == newPartitionFilters.size) {
            // optimize for partial equality filter (no non-eq filter)
            // using ts query
            val partQuery = equalExprs.map({ case (k, v) => s"$k=$v" }).mkString(" & ")
            logInfo(s"Partial equality partition filters $equalExprs for table ${table_info.table_path}, using" +
              s" query $partQuery")
            SparkMetaVersion.getPartitionInfoByEqFilters(table_info.table_id, partQuery)
              .map(partInfo => {
                PartitionFilterInfo(
                  partInfo.range_value,
                  DBUtil.parsePartitionDesc(partInfo.range_value).asScala.toMap,
                  partInfo.version,
                  table_info.table_id,
                  partInfo.read_files.map(u => u.toString),
                  partInfo.expression,
                  partInfo.commit_op
                )
              })
          } else {
            // non-equality filter, we still need to get all partitions from meta
            logInfo(s"at least one non-equality filter exist $newPartitionFilters, read all partition info")
            filterFromAllPartitionInfo(snapshot, table_info, newPartitionFilters)
          }
        }
        snapshot.putPartitionInfoCache(partitionFilters, infos)
        infos
    }
  }


  def filesForScan(snapshot: Snapshot,
                   filters: Seq[Expression]): Array[DataFileInfo] = {
    val t0 = System.currentTimeMillis()
    if (filters.length < 1) {
      val partitionArray = snapshot.getPartitionInfoArray
      val ret = SparkMetaVersion.getTableDataInfoCached(partitionArray, snapshot)
      logInfo(s"get all table data info ${System.currentTimeMillis() - t0}ms")
      ret
    } else {
      val partitionFiltered = partitionsForScan(snapshot, filters)
      logInfo(s"partitionsForScan ${System.currentTimeMillis() - t0}ms")
      val partitionInfo = partitionFiltered.map(p => {
        PartitionInfoScala(
          p.table_id,
          p.range_value,
          p.read_version,
          p.read_files.map(UUID.fromString),
          p.expression,
          p.commit_op
        )
      }).toArray
      val ret = SparkMetaVersion.getTableDataInfoCached(partitionInfo, snapshot)
      logInfo(s"get table filtered partition's data info ${System.currentTimeMillis() - t0}ms")
      ret
    }
  }

  def filterFileList(partitionSchema: StructType,
                     files: Seq[DataFileInfo],
                     partitionFilters: Seq[Expression]): Seq[DataFileInfo] = {
    val spark = SparkSession.active
    import spark.implicits._
    val partitionsMatched = filterFileList(partitionSchema,
      files.map(f => PartitionFilterInfo(
        f.range_partitions,
        MetaUtils.getPartitionMapFromKey(f.range_partitions),
        0,
        ""
      )).toDF,
      partitionFilters).as[PartitionFilterInfo].collect()
    files.filter(f => partitionsMatched.exists(p => p.range_value == f.range_partitions))
  }

  /**
    * Filters the given [[Dataset]] by the given `partitionFilters`, returning those that match.
    *
    * @param files            The active files, which contains the partition value
    *                         information
    * @param partitionFilters Filters on the partition columns
    */
  def filterFileList(partitionSchema: StructType,
                     files: DataFrame,
                     partitionFilters: Seq[Expression]): DataFrame = {
    val rewrittenFilters = rewritePartitionFilters(
      partitionSchema,
      files.sparkSession.sessionState.conf.resolver,
      partitionFilters)

    // If there are no filters, return all files
    if (rewrittenFilters.isEmpty) {
      logInfo("No partition filters, returning all files")
      return files
    }

    // Function to extract OR components from an expression
    def extractOrComponents(expr: Expression): Seq[Expression] = expr match {
      case org.apache.spark.sql.catalyst.expressions.Or(left, right) =>
        extractOrComponents(left) ++ extractOrComponents(right)
      case other => Seq(other)
    }

    // Combine all filters with AND
    val combinedFilter = rewrittenFilters.reduceLeftOption(And).getOrElse(Literal(true))

    // Extract all OR components
    val orComponents = extractOrComponents(combinedFilter)

    // Get the spark session and import implicits once to avoid scoping issues
    val spark = SparkSession.active
    import spark.implicits._

    if (orComponents.length > 1) {
      // We have OR components, process them separately
      logInfo(s"Processing ${orComponents.length} OR components separately for better performance")

      // For very large number of OR components, process in batches to avoid overwhelming the query optimizer
      val batchSize = 50 // Adjust based on testing for optimal performance
      if (orComponents.length > batchSize) {
        logInfo(s"Large number of OR components detected (${orComponents.length}), processing in batches of $batchSize")

        // Process OR components in batches, but avoid using distinct() on map columns
        val batches = orComponents.grouped(batchSize).toSeq

        // Apply each batch filter and collect just the range_value (partition key)
        // This avoids the problem with map type in set operations
        val allRangeValues = batches.zipWithIndex.flatMap { case (batch, idx) =>
          logInfo(s"Processing batch ${idx + 1}/${batches.length} with ${batch.length} OR components")

          // Combine batch components with OR
          val batchFilter = batch.reduce((left, right) =>
            org.apache.spark.sql.catalyst.expressions.Or(left, right))
          val columnFilter = new Column(batchFilter)

          // Only select range_value column for deduplication to avoid map type issues
          files.filter(columnFilter)
            .select("range_value")
            .distinct()
            .collect()
            .map(_.getString(0))
        }.distinct

        // Now filter the original dataframe by the collected range_values
        logInfo(s"Found ${allRangeValues.length} unique partition values after filtering")
        if (allRangeValues.isEmpty) {
          // Return empty DataFrame with the same schema
          files.filter(lit(false))
        } else {
          files.filter($"range_value".isin(allRangeValues: _*))
        }
      } else {
        // For smaller number of OR components, use a similar approach to avoid map issues
        val allRangeValues = orComponents.flatMap { comp =>
          logDebug(s"Processing OR component: $comp")
          val columnFilter = new Column(comp)
          files.filter(columnFilter)
            .select("range_value")
            .distinct()
            .collect()
            .map(_.getString(0))
        }.distinct

        // Now filter the original dataframe by the collected range_values
        logInfo(s"Found ${allRangeValues.length} unique partition values after filtering")
        if (allRangeValues.isEmpty) {
          // Return empty DataFrame with the same schema
          files.filter(lit(false))
        } else {
          files.filter($"range_value".isin(allRangeValues: _*))
        }
      }
    } else {
      // No OR components, process as normal AND filter
      logInfo("No OR components detected, processing with normal filter")
      val columnFilter = new Column(combinedFilter)
      files.filter(columnFilter)
    }
  }

  /**
    * Rewrite the given `partitionFilters` to be used for filtering partition values.
    * We need to explicitly resolve the partitioning columns here because the partition columns
    * are stored as keys of a Map type instead of attributes in the DataFileInfo schema (below) and thus
    * cannot be resolved automatically.
    * e.g. (cast('range_partitions.zc as string) = ff)
    *
    * @param partitionFilters        Filters on the partition columns
    * @param partitionColumnPrefixes The path to the `partitionValues` column, if it's nested
    */
  def rewritePartitionFilters(partitionSchema: StructType,
                              resolver: Resolver,
                              partitionFilters: Seq[Expression],
                              partitionColumnPrefixes: Seq[String] = Nil): Seq[Expression] = {
    partitionFilters.map(_.transformUp {
      case a: Attribute =>
        // If we have a special column name, e.g. `a.a`, then an UnresolvedAttribute returns
        // the column name as '`a.a`' instead of 'a.a', therefore we need to strip the backticks.
        val unquoted = a.name.stripPrefix("`").stripSuffix("`")
        val partitionCol = partitionSchema.find { field => resolver(field.name, unquoted) }
        partitionCol match {
          case Some(StructField(name, dataType, _, _)) =>
            Cast(
              UnresolvedAttribute(partitionColumnPrefixes ++ Seq("range_partitions", name)),
              dataType)
          case None =>
            // This should not be able to happen, but the case was present in the original code so
            // we kept it to be safe.
            UnresolvedAttribute(partitionColumnPrefixes ++ Seq("range_partitions", a.name))
        }
    })
  }


}
