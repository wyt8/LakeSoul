// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

//! The [`datafusion::datasource::file_format::FileFormat`] implementation for the LakeSoul Parquet format with metadata.

use arrow::array::{ArrayRef, StringArray, UInt64Array};
use arrow::record_batch::RecordBatch;
use async_trait::async_trait;
use rand::distr::SampleString;
use std::any::Any;
use std::collections::{HashMap, HashSet};
use std::fmt::{self, Debug};
use std::sync::Arc;

use arrow::datatypes::{DataType, Field, Schema, SchemaBuilder, SchemaRef};
use datafusion::catalog::Session;
use datafusion::common::parsers::CompressionTypeVariant;
use datafusion::common::{DFSchema, GetExt, Statistics, project_schema};
use datafusion::datasource::file_format::file_compression_type::FileCompressionType;
use datafusion::datasource::file_format::parquet::ParquetFormatFactory;
use datafusion::datasource::listing::ListingOptions;
use datafusion::datasource::physical_plan::FileSource;
#[allow(deprecated)]
use datafusion::datasource::physical_plan::parquet::ParquetExecBuilder;
use datafusion::error::DataFusionError;
use datafusion::execution::TaskContext;
use datafusion::logical_expr::dml::InsertOp;
use datafusion::physical_expr::{
    EquivalenceProperties, LexOrdering, LexRequirement, create_physical_expr,
};
use datafusion::physical_plan::execution_plan::{Boundedness, EmissionType};
use datafusion::physical_plan::filter::FilterExec;
use datafusion::physical_plan::projection::ProjectionExec;
use datafusion::physical_plan::stream::RecordBatchStreamAdapter;
use datafusion::physical_plan::union::UnionExec;
use datafusion::physical_plan::{
    DisplayAs, DisplayFormatType, Distribution, ExecutionPlanProperties, Partitioning,
    PlanProperties, SendableRecordBatchStream,
};
use datafusion::prelude::{ident, lit};
use datafusion::sql::TableReference;
use datafusion::{
    datasource::{
        file_format::{FileFormat, parquet::ParquetFormat},
        physical_plan::{FileScanConfig, FileSinkConfig},
    },
    error::Result,
    physical_plan::{ExecutionPlan, PhysicalExpr},
};
use futures::StreamExt;
use lakesoul_io::async_writer::{AsyncBatchWriter, MultiPartAsyncWriter};
use lakesoul_io::datasource::file_format::{
    compute_project_column_indices, flatten_file_scan_config,
};
use lakesoul_io::datasource::physical_plan::MergeParquetExec;
use lakesoul_io::helpers::{
    columnar_values_to_partition_desc, columnar_values_to_sub_path, get_columnar_values,
    partition_desc_from_file_scan_config,
};
use lakesoul_io::lakesoul_io_config::LakeSoulIOConfig;
use lakesoul_metadata::{MetaDataClient, MetaDataClientRef};
use object_store::{ObjectMeta, ObjectStore};
use proto::proto::entity::TableInfo;

use crate::catalog::{commit_data, parse_table_info_partitions};
use crate::lakesoul_table::helpers::create_io_config_builder_from_table_info;
use log::debug;
use tokio::sync::Mutex;
use tokio::task::JoinHandle;

/// The wrapper of the [`ParquetFormat`] with LakeSoul metadata. It is used to read and write data files while interacting with LakeSoul metadata.
pub struct LakeSoulMetaDataParquetFormat {
    /// The inner [`ParquetFormat`].
    parquet_format: Arc<ParquetFormat>,
    /// The metadata client.
    client: MetaDataClientRef,
    /// The table info.
    table_info: Arc<TableInfo>,
    /// The io config.
    conf: LakeSoulIOConfig,
}

impl Debug for LakeSoulMetaDataParquetFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LakeSoulMetaDataParquetFormat").finish()
    }
}

impl LakeSoulMetaDataParquetFormat {
    pub async fn new(
        client: MetaDataClientRef,
        parquet_format: Arc<ParquetFormat>,
        table_info: Arc<TableInfo>,
        conf: LakeSoulIOConfig,
    ) -> crate::error::Result<Self> {
        debug!("LakeSoulMetaDataParquetFormat::new, conf: {:?}", conf);
        Ok(Self {
            parquet_format,
            client,
            table_info,
            conf,
        })
    }

    fn client(&self) -> MetaDataClientRef {
        self.client.clone()
    }

    pub fn table_info(&self) -> Arc<TableInfo> {
        self.table_info.clone()
    }

    pub async fn default_listing_options() -> Result<ListingOptions> {
        Ok(ListingOptions::new(Arc::new(
            Self::new(
                Arc::new(
                    MetaDataClient::from_env()
                        .await
                        .map_err(|e| DataFusionError::External(Box::new(e)))?,
                ),
                Arc::new(ParquetFormat::new().with_force_view_types(false)),
                Arc::new(TableInfo::default()),
                LakeSoulIOConfig::default(),
            )
            .await
            .map_err(|e| DataFusionError::External(Box::new(e)))?,
        )))
    }
}

#[async_trait]
impl FileFormat for LakeSoulMetaDataParquetFormat {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn get_ext(&self) -> String {
        ParquetFormatFactory::new().get_ext()
    }

    fn get_ext_with_compression(
        &self,
        file_compression_type: &FileCompressionType,
    ) -> Result<String> {
        let ext = self.get_ext();
        match file_compression_type.get_variant() {
            CompressionTypeVariant::UNCOMPRESSED => Ok(ext),
            _ => Err(DataFusionError::Internal(
                "Parquet FileFormat does not support compression.".into(),
            )),
        }
    }

    async fn infer_schema(
        &self,
        state: &dyn Session,
        store: &Arc<dyn ObjectStore>,
        objects: &[ObjectMeta],
    ) -> Result<SchemaRef> {
        self.parquet_format
            .infer_schema(state, store, objects)
            .await
    }

    async fn infer_stats(
        &self,
        state: &dyn Session,
        store: &Arc<dyn ObjectStore>,
        table_schema: SchemaRef,
        object: &ObjectMeta,
    ) -> Result<Statistics> {
        self.parquet_format
            .infer_stats(state, store, table_schema, object)
            .await
    }

    /// Create a physical plan for the scan LakeSoul table.
    /// The overall process is as follows:
    /// 1. Get the predicate from the filters.
    /// 2. Get each file metadata from the file scan config.
    /// 3. Create [`datafusion::datasource::physical_plan::parquet::ParquetExec`] for each file.
    /// 4. Merge the [`datafusion::datasource::physical_plan::parquet::ParquetExec`]s according to the partition columns.
    /// 5. Apply the operations on the merged [`datafusion::physical_plan::ExecutionPlan`].
    async fn create_physical_plan(
        &self,
        state: &dyn Session,
        conf: FileScanConfig,
        filters: Option<&Arc<dyn PhysicalExpr>>,
    ) -> Result<Arc<dyn ExecutionPlan>> {
        info!(
            "LakeSoulMetaDataParquetFormat::create_physical_plan with conf= {:?}, filters= {:?}",
            &conf, &filters
        );
        // If enable pruning then combine the filters to build the predicate.
        // If disable pruning then set the predicate to None, thus readers
        // will not prune data based on the statistics.
        let predicate = self
            .parquet_format
            .enable_pruning()
            .then(|| filters.cloned())
            .flatten();

        let file_schema = conf.file_schema.clone();
        let mut builder = SchemaBuilder::from(file_schema.fields());
        for field in &conf.table_partition_cols {
            builder.push(Field::new(field.name(), field.data_type().clone(), false));
        }

        let table_schema = Arc::new(builder.finish());

        let projection = conf.projection.clone();
        let target_schema = project_schema(&table_schema, projection.as_ref())?;

        let merged_projection = compute_project_column_indices(
            table_schema.clone(),
            target_schema.clone(),
            self.conf.primary_keys_slice(),
            &self.conf.cdc_column(),
        );
        let merged_schema = project_schema(&table_schema, merged_projection.as_ref())?;

        // files to read
        let flatten_conf = flatten_file_scan_config(
            state,
            self.parquet_format.clone(),
            conf,
            self.conf.primary_keys_slice(),
            &self.conf.cdc_column(),
            self.conf.partition_schema(),
            target_schema.clone(),
        )
        .await?;

        let mut inputs_map: HashMap<
            String,
            (Arc<HashMap<String, String>>, Vec<Arc<dyn ExecutionPlan>>),
        > = HashMap::new();
        let mut column_nullable = HashSet::<String>::new();

        for config in &flatten_conf {
            let (partition_desc, partition_columnar_value) =
                partition_desc_from_file_scan_config(config)?;
            let partition_columnar_value = Arc::new(partition_columnar_value);

            let parquet_exec = Arc::new({
                debug!(
                    "create parquet exec with config= {:?}, predicate= {:?}",
                    &config, &predicate
                );
                #[allow(deprecated)]
                let mut builder = ParquetExecBuilder::new(config.clone());
                if let Some(predicate) = predicate.clone() {
                    builder = builder.with_predicate(predicate);
                }
                builder.build()
            });
            for field in parquet_exec.schema().fields().iter() {
                if field.is_nullable() {
                    column_nullable.insert(field.name().clone());
                }
            }

            if let Some((_, inputs)) = inputs_map.get_mut(&partition_desc) {
                inputs.push(parquet_exec);
            } else {
                inputs_map.insert(
                    partition_desc.clone(),
                    (partition_columnar_value.clone(), vec![parquet_exec]),
                );
            }
        }

        let merged_schema = SchemaRef::new(Schema::new(
            merged_schema
                .fields()
                .iter()
                .map(|field| {
                    Field::new(
                        field.name(),
                        field.data_type().clone(),
                        field.is_nullable() | column_nullable.contains(field.name()),
                    )
                })
                .collect::<Vec<_>>(),
        ));

        let mut partitioned_exec = Vec::new();
        for (_, (partition_columnar_values, inputs)) in inputs_map {
            let merge_exec = Arc::new(MergeParquetExec::new_with_inputs(
                merged_schema.clone(),
                inputs,
                self.conf.clone(),
                partition_columnar_values.clone(),
            )?) as Arc<dyn ExecutionPlan>;
            partitioned_exec.push(merge_exec);
        }
        let exec = if partitioned_exec.len() > 1 {
            Arc::new(UnionExec::new(partitioned_exec)) as Arc<dyn ExecutionPlan>
        } else {
            partitioned_exec.first().unwrap().clone()
        };

        let cdc_column = self.conf.cdc_column();
        let exec = if !cdc_column.is_empty() {
            let dfschema = DFSchema::try_from(exec.schema().as_ref().clone())?;
            let cdc_filter = ident(cdc_column).not_eq(lit("delete"));
            let expr =
                create_physical_expr(&cdc_filter, &dfschema, state.execution_props())?;

            Arc::new(FilterExec::try_new(expr, exec)?)
        } else {
            exec
        };

        if target_schema.fields().len() < merged_schema.fields().len() {
            let mut projection_expr = vec![];
            for field in target_schema.fields() {
                projection_expr.push((
                    datafusion::physical_expr::expressions::col(
                        field.name(),
                        &merged_schema,
                    )?,
                    field.name().clone(),
                ));
            }
            Ok(Arc::new(ProjectionExec::try_new(projection_expr, exec)?))
        } else {
            Ok(exec)
        }
    }

    /// Create a physical plan for the write LakeSoul table.
    /// The overall process is as follows:
    /// 1. Check if the insert operation is overwrite.
    /// 2. Create a [`LakeSoulHashSinkExec`] for the input plan.
    /// 3. Return the physical plan.
    async fn create_writer_physical_plan(
        &self,
        input: Arc<dyn ExecutionPlan>,
        _state: &dyn Session,
        conf: FileSinkConfig,
        order_requirements: Option<LexRequirement>,
    ) -> Result<Arc<dyn ExecutionPlan>> {
        if conf.insert_op == InsertOp::Overwrite {
            return Err(DataFusionError::NotImplemented(
                "Overwrites are not implemented yet for Parquet".to_string(),
            ));
        }

        Ok(Arc::new(
            LakeSoulHashSinkExec::new(
                input,
                order_requirements,
                self.table_info(),
                self.client(),
            )
            .await?,
        ) as _)
    }

    fn file_source(&self) -> Arc<dyn FileSource> {
        self.parquet_format
            .file_source()
            .with_statistics(Statistics::default())
    }
}

/// Execution plan for writing record batches to a [`LakeSoulParquetSink`]
pub struct LakeSoulHashSinkExec {
    /// Input plan that produces the record batches to be written.
    input: Arc<dyn ExecutionPlan>,

    /// Schema describing the structure of the output data.
    sink_schema: SchemaRef,

    /// Optional required sort order for output data.
    sort_order: Option<LexRequirement>,

    /// The table info of LakeSoul table.
    table_info: Arc<TableInfo>,

    /// The metadata client.
    metadata_client: MetaDataClientRef,

    /// The range partitions.
    range_partitions: Arc<Vec<String>>,

    /// The properties of the plan.
    properties: PlanProperties,
}

impl Debug for LakeSoulHashSinkExec {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "LakeSoulHashSinkExec schema: {:?}", self.sink_schema)
    }
}

impl LakeSoulHashSinkExec {
    /// Create a plan to write to `sink`
    pub async fn new(
        input: Arc<dyn ExecutionPlan>,
        sort_order: Option<LexRequirement>,
        table_info: Arc<TableInfo>,
        metadata_client: MetaDataClientRef,
    ) -> Result<Self> {
        let (range_partitions, _) = parse_table_info_partitions(&table_info.partitions)
            .map_err(|_| {
            DataFusionError::External("parse table_info.partitions failed".into())
        })?;
        let range_partitions = Arc::new(range_partitions);
        Ok(Self {
            input,
            sink_schema: make_sink_schema(),
            sort_order,
            table_info,
            metadata_client,
            range_partitions,
            properties: PlanProperties::new(
                EquivalenceProperties::new(make_sink_schema()),
                Partitioning::UnknownPartitioning(1),
                EmissionType::Incremental,
                Boundedness::Bounded,
            ),
        })
    }

    /// Input execution plan
    pub fn input(&self) -> &Arc<dyn ExecutionPlan> {
        &self.input
    }

    /// Optional sort order for output data
    pub fn sort_order(&self) -> &Option<LexRequirement> {
        &self.sort_order
    }

    pub fn table_info(&self) -> Arc<TableInfo> {
        self.table_info.clone()
    }

    pub fn metadata_client(&self) -> MetaDataClientRef {
        self.metadata_client.clone()
    }

    #[instrument(skip(context, input, table_info, partitioned_file_path_and_row_count))]
    async fn pull_and_sink(
        input: Arc<dyn ExecutionPlan>,
        partition: usize,
        context: Arc<TaskContext>,
        table_info: Arc<TableInfo>,
        range_partitions: Arc<Vec<String>>,
        write_id: String,
        partitioned_file_path_and_row_count: Arc<
            Mutex<HashMap<String, (Vec<String>, u64)>>,
        >,
    ) -> Result<u64> {
        debug!("{}", input.name());
        let mut data = input.execute(partition, context.clone())?;
        // O(nm), n = number of data fields, m = number of range partitions
        let schema_projection_excluding_range = data
            .schema()
            .fields()
            .iter()
            .enumerate()
            .filter_map(
                |(idx, field)| match range_partitions.contains(field.name()) {
                    true => None,
                    false => Some(idx),
                },
            )
            .collect::<Vec<_>>();

        let mut row_count = 0;
        // let mut async_writer = MultiPartAsyncWriter::try_new(lakesoul_io_config).await?;
        let mut partitioned_writer = HashMap::<String, Box<MultiPartAsyncWriter>>::new();
        while let Some(batch) = data.next().await.transpose()? {
            debug!("write record_batch with {} rows", batch.num_rows());
            let columnar_values = get_columnar_values(&batch, range_partitions.clone())?;
            let partition_desc = columnar_values_to_partition_desc(&columnar_values);
            debug!("{partition_desc}");
            let batch_excluding_range =
                batch.project(&schema_projection_excluding_range)?;
            let file_absolute_path = format!(
                "{}{}part-{}_{:0>4}.parquet",
                table_info.table_path,
                columnar_values_to_sub_path(&columnar_values),
                write_id,
                partition
            );

            if !partitioned_writer.contains_key(&partition_desc) {
                let mut config = create_io_config_builder_from_table_info(
                    table_info.clone(),
                    HashMap::new(),
                    HashMap::new(),
                )
                .map_err(|e| DataFusionError::External(Box::new(e)))?
                .with_files(vec![file_absolute_path])
                .with_schema(batch_excluding_range.schema())
                .build();
                let writer = MultiPartAsyncWriter::try_new_with_context(
                    &mut config,
                    context.clone(),
                )
                .await?;
                partitioned_writer.insert(partition_desc.clone(), Box::new(writer));
            }

            if let Some(async_writer) = partitioned_writer.get_mut(&partition_desc) {
                row_count += batch_excluding_range.num_rows();
                async_writer
                    .write_record_batch(batch_excluding_range)
                    .await?;
            }
        }

        // TODO: apply rolling strategy
        for (partition_desc, writer) in partitioned_writer.into_iter() {
            {
                let mut partitioned_file_path_and_row_count_locked =
                    partitioned_file_path_and_row_count.lock().await;
                let file_absolute_path = writer.absolute_path();
                let num_rows = writer.nun_rows();
                if let Some(file_path_and_row_count) =
                    partitioned_file_path_and_row_count_locked.get_mut(&partition_desc)
                {
                    file_path_and_row_count.0.push(file_absolute_path);
                    file_path_and_row_count.1 += num_rows;
                } else {
                    partitioned_file_path_and_row_count_locked.insert(
                        partition_desc.clone(),
                        (vec![file_absolute_path], num_rows),
                    );
                }
                // release guard
            }
            writer.flush_and_close().await?;
        }

        Ok(row_count as u64)
    }

    async fn wait_for_commit(
        join_handles: Vec<JoinHandle<Result<u64>>>,
        client: MetaDataClientRef,
        table_name: String,
        partitioned_file_path_and_row_count: Arc<
            Mutex<HashMap<String, (Vec<String>, u64)>>,
        >,
    ) -> Result<u64> {
        let count = futures::future::join_all(join_handles)
            .await
            .iter()
            .try_fold(0u64, |counter, result| match &result {
                Ok(Ok(count)) => Ok(counter + count),
                Ok(Err(e)) => Err(DataFusionError::Execution(format!("{}", e))),
                Err(e) => Err(DataFusionError::Execution(format!("{}", e))),
            })?;
        let partitioned_file_path_and_row_count =
            partitioned_file_path_and_row_count.lock().await;

        for (partition_desc, (files, _)) in partitioned_file_path_and_row_count.iter() {
            commit_data(client.clone(), &table_name, partition_desc.clone(), files)
                .await
                .map_err(|e| DataFusionError::External(Box::new(e)))?;
            debug!(
                "table: {} insert success at {:?}",
                &table_name,
                std::time::SystemTime::now()
            )
        }
        Ok(count)
    }
}

impl DisplayAs for LakeSoulHashSinkExec {
    fn fmt_as(&self, _t: DisplayFormatType, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "LakeSoulHashSinkExec")
    }
}

impl ExecutionPlanProperties for LakeSoulHashSinkExec {
    fn output_partitioning(&self) -> &Partitioning {
        &self.properties.partitioning
    }

    fn output_ordering(&self) -> Option<&LexOrdering> {
        None
    }

    fn boundedness(&self) -> Boundedness {
        Boundedness::Bounded
    }

    fn pipeline_behavior(&self) -> EmissionType {
        EmissionType::Incremental
    }

    fn equivalence_properties(&self) -> &EquivalenceProperties {
        &self.properties.eq_properties
    }
}

impl ExecutionPlan for LakeSoulHashSinkExec {
    fn name(&self) -> &str {
        "LakeSoulHashSinkExec"
    }

    /// Return a reference to Any that can be used for downcasting
    fn as_any(&self) -> &dyn Any {
        self
    }

    /// Get the schema for this execution plan
    fn schema(&self) -> SchemaRef {
        self.sink_schema.clone()
    }

    fn properties(&self) -> &PlanProperties {
        &self.properties
    }

    fn required_input_distribution(&self) -> Vec<Distribution> {
        // DataSink is responsible for dynamically partitioning its
        // own input at execution time, and so requires a single input partition.
        vec![Distribution::SinglePartition; self.children().len()]
    }

    fn required_input_ordering(&self) -> Vec<Option<LexRequirement>> {
        // The input order is either explicitly set (such as by a ListingTable),
        // or require that the [FileSinkExec] gets the data in the order the
        // input produced it (otherwise the optimizer may choose to reorder
        // the input which could result in unintended / poor UX)
        //
        // More rationale:
        // https://github.com/apache/arrow-datafusion/pull/6354#discussion_r1195284178
        match &self.sort_order {
            Some(requirements) => vec![Some(requirements.clone())],
            None => vec![],
        }
    }

    fn maintains_input_order(&self) -> Vec<bool> {
        vec![false]
    }

    fn benefits_from_input_partitioning(&self) -> Vec<bool> {
        // DataSink is responsible for dynamically partitioning its
        // own input at execution time.
        vec![false]
    }

    fn children(&self) -> Vec<&Arc<dyn ExecutionPlan>> {
        vec![&self.input]
    }

    fn with_new_children(
        self: Arc<Self>,
        children: Vec<Arc<dyn ExecutionPlan>>,
    ) -> Result<Arc<dyn ExecutionPlan>> {
        println!("len is {}", children.len());

        Ok(Arc::new(Self {
            input: if children.is_empty() {
                self.input.clone()
            } else {
                children[0].clone()
            },
            sink_schema: self.sink_schema.clone(),
            sort_order: self.sort_order.clone(),
            table_info: self.table_info.clone(),
            range_partitions: self.range_partitions.clone(),
            metadata_client: self.metadata_client.clone(),
            properties: self.properties.clone(),
        }))
    }

    /// Execute the plan and return a stream of `RecordBatch`es for
    /// the specified partition.
    #[instrument(skip(self, context))]
    fn execute(
        &self,
        partition: usize,
        context: Arc<TaskContext>,
    ) -> Result<SendableRecordBatchStream> {
        if partition != 0 {
            return Err(DataFusionError::NotImplemented(
                "FileSinkExec can only be called on partition 0!".to_string(),
            ));
        }
        let num_input_partitions = self.input.output_partitioning().partition_count();
        debug!("num_input_partitions {}", num_input_partitions);
        // launch one async task per *input* partition
        let mut join_handles = vec![];

        let write_id = rand::distr::Alphanumeric.sample_string(&mut rand::rng(), 16);

        let partitioned_file_path_and_row_count =
            Arc::new(Mutex::new(HashMap::<String, (Vec<String>, u64)>::new()));
        for i in 0..num_input_partitions {
            let sink_task = tokio::spawn(Self::pull_and_sink(
                self.input().clone(),
                i,
                context.clone(),
                self.table_info(),
                self.range_partitions.clone(),
                write_id.clone(),
                partitioned_file_path_and_row_count.clone(),
            ));
            // // In a separate task, wait for each input to be done
            // // (and pass along any errors, including panic!s)
            join_handles.push(sink_task);
        }

        let table_ref = TableReference::Partial {
            schema: self.table_info().table_namespace.clone().into(),
            table: self.table_info().table_name.clone().into(),
        };
        let join_handle = tokio::spawn(Self::wait_for_commit(
            join_handles,
            self.metadata_client(),
            table_ref.to_string(),
            partitioned_file_path_and_row_count,
        ));

        // });

        // let abort_helper = Arc::new(AbortOnDropMany(join_handles));

        let sink_schema = self.sink_schema.clone();
        // let count = futures::future::join_all(join_handles).await;
        // for (columnar_values, result) in partitioned_file_path_and_row_count.lock().await.iter() {
        //     match commit_data(self.metadata_client(), self.table_info().table_name.as_str(), &result.0).await {
        //         Ok(()) => todo!(),
        //         Err(_) => todo!(),
        //     }
        // }

        let stream = futures::stream::once(async move {
            match join_handle.await {
                Ok(Ok(count)) => Ok(make_sink_batch(count, String::from(""))),
                Ok(Err(e)) => {
                    debug!("{e:?}");
                    Ok(make_sink_batch(u64::MAX, e.to_string()))
                }
                Err(e) => {
                    debug!("{e:?}");
                    Ok(make_sink_batch(u64::MAX, e.to_string()))
                }
            }
        })
        .boxed();

        Ok(Box::pin(RecordBatchStreamAdapter::new(sink_schema, stream)))
    }
}

fn make_sink_batch(count: u64, msg: String) -> RecordBatch {
    let count_array = Arc::new(UInt64Array::from(vec![count])) as ArrayRef;
    let msg_array = Arc::new(StringArray::from(vec![msg])) as ArrayRef;
    RecordBatch::try_from_iter_with_nullable(vec![
        ("count", count_array, false),
        ("msg", msg_array, false),
    ])
    .unwrap()
}

fn make_sink_schema() -> SchemaRef {
    // define a schema.
    Arc::new(Schema::new(vec![
        Field::new("count", DataType::UInt64, false),
        Field::new("msg", DataType::Utf8, false),
    ]))
}
