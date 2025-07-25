/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.msq.sql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.catalog.MetadataCatalog;
import org.apache.druid.catalog.model.DatasourceProjectionMetadata;
import org.apache.druid.catalog.model.ResolvedTable;
import org.apache.druid.catalog.model.TableId;
import org.apache.druid.catalog.model.table.DatasourceDefn;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.data.input.impl.AggregateProjectionSpec;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.InvalidInput;
import org.apache.druid.frame.FrameType;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.msq.exec.MSQTasks;
import org.apache.druid.msq.exec.QueryKitSpecFactory;
import org.apache.druid.msq.exec.ResultsContext;
import org.apache.druid.msq.indexing.LegacyMSQSpec;
import org.apache.druid.msq.indexing.MSQControllerTask;
import org.apache.druid.msq.indexing.MSQTuningConfig;
import org.apache.druid.msq.indexing.QueryDefMSQSpec;
import org.apache.druid.msq.indexing.destination.DataSourceMSQDestination;
import org.apache.druid.msq.indexing.destination.DurableStorageMSQDestination;
import org.apache.druid.msq.indexing.destination.ExportMSQDestination;
import org.apache.druid.msq.indexing.destination.MSQDestination;
import org.apache.druid.msq.indexing.destination.MSQSelectDestination;
import org.apache.druid.msq.indexing.destination.MSQTerminalStageSpecFactory;
import org.apache.druid.msq.indexing.destination.TaskReportMSQDestination;
import org.apache.druid.msq.kernel.QueryDefinition;
import org.apache.druid.msq.util.MSQTaskQueryMakerUtils;
import org.apache.druid.msq.util.MultiStageQueryContext;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.server.QueryResponse;
import org.apache.druid.server.lookup.cache.LookupLoadingSpec;
import org.apache.druid.sql.calcite.parser.DruidSqlIngest;
import org.apache.druid.sql.calcite.parser.DruidSqlInsert;
import org.apache.druid.sql.calcite.parser.DruidSqlReplace;
import org.apache.druid.sql.calcite.planner.ColumnMappings;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.planner.QueryUtils;
import org.apache.druid.sql.calcite.rel.DruidQuery;
import org.apache.druid.sql.calcite.rel.Grouping;
import org.apache.druid.sql.calcite.run.QueryMaker;
import org.apache.druid.sql.calcite.run.SqlResults;
import org.apache.druid.sql.calcite.table.RowSignatures;
import org.apache.druid.sql.destination.ExportDestination;
import org.apache.druid.sql.destination.IngestDestination;
import org.apache.druid.sql.destination.TableDestination;
import org.apache.druid.sql.hook.DruidHook;
import org.apache.druid.sql.http.ResultFormat;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

public class MSQTaskQueryMaker implements QueryMaker
{
  public static final String USER_KEY = "__user";

  private static final Granularity DEFAULT_SEGMENT_GRANULARITY = Granularities.ALL;

  private final IngestDestination targetDataSource;
  private final OverlordClient overlordClient;
  private final PlannerContext plannerContext;
  private final ObjectMapper jsonMapper;
  private final List<Entry<Integer, String>> fieldMapping;
  private final MSQTerminalStageSpecFactory terminalStageSpecFactory;
  private final QueryKitSpecFactory queryKitSpecFactory;

  MSQTaskQueryMaker(
      @Nullable final IngestDestination targetDataSource,
      final OverlordClient overlordClient,
      final PlannerContext plannerContext,
      final ObjectMapper jsonMapper,
      final List<Entry<Integer, String>> fieldMapping,
      final MSQTerminalStageSpecFactory terminalStageSpecFactory,
      final QueryKitSpecFactory queryKitSpecFactory
  )
  {
    this.targetDataSource = targetDataSource;
    this.overlordClient = Preconditions.checkNotNull(overlordClient, "indexingServiceClient");
    this.plannerContext = Preconditions.checkNotNull(plannerContext, "plannerContext");
    this.jsonMapper = Preconditions.checkNotNull(jsonMapper, "jsonMapper");
    this.fieldMapping = Preconditions.checkNotNull(fieldMapping, "fieldMapping");
    this.terminalStageSpecFactory = terminalStageSpecFactory;
    this.queryKitSpecFactory = queryKitSpecFactory;
  }

  @Override
  public QueryResponse<Object[]> runQuery(final DruidQuery druidQuery)
  {
    Hook.QUERY_PLAN.run(druidQuery.getQuery());
    plannerContext.dispatchHook(DruidHook.NATIVE_PLAN, druidQuery.getQuery());

    final String taskId = MSQTasks.controllerTaskId(plannerContext.getSqlQueryId());

    final Map<String, Object> taskContext = new HashMap<>();
    taskContext.put(LookupLoadingSpec.CTX_LOOKUP_LOADING_MODE, plannerContext.getLookupLoadingSpec().getMode());
    if (plannerContext.getLookupLoadingSpec().getMode() == LookupLoadingSpec.Mode.ONLY_REQUIRED) {
      taskContext.put(LookupLoadingSpec.CTX_LOOKUPS_TO_LOAD, plannerContext.getLookupLoadingSpec().getLookupsToLoad());
    }

    final List<Pair<SqlTypeName, ColumnType>> typeList = getTypes(druidQuery, fieldMapping, plannerContext);

    final ResultsContext resultsContext = new ResultsContext(
        typeList.stream().map(typeInfo -> typeInfo.lhs).collect(Collectors.toList()),
        SqlResults.Context.fromPlannerContext(plannerContext)
    );
    ColumnMappings columnMappings = QueryUtils.buildColumnMappings(fieldMapping, druidQuery.getOutputRowSignature());

    final LegacyMSQSpec querySpec = makeLegacyMSQSpec(
        targetDataSource,
        druidQuery,
        druidQuery.getQuery().context(),
        columnMappings,
        plannerContext,
        terminalStageSpecFactory
    );

    final MSQControllerTask controllerTask = new MSQControllerTask(
        taskId,
        querySpec,
        MSQTaskQueryMakerUtils.maskSensitiveJsonKeys(plannerContext.getSql()),
        plannerContext.queryContextMap(),
        resultsContext.getSqlResultsContext(),
        resultsContext.getSqlTypeNames(),
        typeList.stream().map(typeInfo -> typeInfo.rhs).collect(Collectors.toList()),
        taskContext
    );

    FutureUtils.getUnchecked(overlordClient.runTask(taskId, controllerTask), true);
    return QueryResponse.withEmptyContext(Sequences.simple(Collections.singletonList(new Object[]{taskId})));
  }

  public static LegacyMSQSpec makeLegacyMSQSpec(
      @Nullable final IngestDestination targetDataSource,
      final DruidQuery druidQuery,
      final QueryContext queryContext,
      ColumnMappings columnMappings,
      final PlannerContext plannerContext,
      final MSQTerminalStageSpecFactory terminalStageSpecFactory
  )
  {
    final MSQDestination destination = buildMSQDestination(
        targetDataSource,
        columnMappings,
        plannerContext,
        terminalStageSpecFactory
    );

    final Map<String, Object> nativeQueryContextOverrides = buildOverrideContext(druidQuery.getQuery(), plannerContext, destination);

    final LegacyMSQSpec querySpec =
        LegacyMSQSpec.builder()
               .query(druidQuery.getQuery())
               .queryContext(queryContext.override(nativeQueryContextOverrides))
               .columnMappings(columnMappings)
               .destination(destination)
               .assignmentStrategy(MultiStageQueryContext.getAssignmentStrategy(plannerContext.queryContext()))
               .tuningConfig(makeMSQTuningConfig(plannerContext))
               .build();

    MSQTaskQueryMakerUtils.validateRealtimeReindex(querySpec.getContext(), querySpec.getDestination(), druidQuery.getQuery());

    return querySpec;
  }

  private static MSQDestination buildMSQDestination(final IngestDestination targetDataSource,
      final ColumnMappings columnMappings, final PlannerContext plannerContext,
      final MSQTerminalStageSpecFactory terminalStageSpecFactory)
  {
    final QueryContext sqlQueryContext = plannerContext.queryContext();
    final Object segmentGranularity = getSegmentGranularity(plannerContext);
    final List<Interval> replaceTimeChunks = getReplaceIntervals(sqlQueryContext);
    final MSQDestination destination;

    if (targetDataSource instanceof ExportDestination) {
      destination = buildExportDestination((ExportDestination) targetDataSource, sqlQueryContext);
    } else if (targetDataSource instanceof TableDestination) {
      destination = buildTableDestination(
          targetDataSource,
          columnMappings,
          plannerContext,
          terminalStageSpecFactory,
          segmentGranularity,
          sqlQueryContext,
          replaceTimeChunks
      );
    } else {
      final MSQSelectDestination msqSelectDestination = MultiStageQueryContext.getSelectDestination(sqlQueryContext);
      if (msqSelectDestination.equals(MSQSelectDestination.TASKREPORT)) {
        destination = TaskReportMSQDestination.instance();
      } else if (msqSelectDestination.equals(MSQSelectDestination.DURABLESTORAGE)) {
        destination = DurableStorageMSQDestination.instance();
      } else {
        throw InvalidInput.exception(
            "Unsupported select destination [%s] provided in the query context. MSQ can currently write the select results to "
            + "[%s]",
            msqSelectDestination.getName(),
            Arrays.stream(MSQSelectDestination.values())
                  .map(MSQSelectDestination::getName)
                  .collect(Collectors.joining(","))
        );
      }
    }
    return destination;
  }

  private static Map<String, Object> buildOverrideContext(
      final Query<?> query,
      final PlannerContext plannerContext,
      final MSQDestination destination)
  {
    final QueryContext sqlQueryContext = plannerContext.queryContext();
    final Map<String, Object> nativeQueryContextOverrides = new HashMap<>();

    // Add appropriate finalization to native query context.
    final boolean finalizeAggregations = MultiStageQueryContext.isFinalizeAggregations(sqlQueryContext);
    nativeQueryContextOverrides.put(QueryContexts.FINALIZE_KEY, finalizeAggregations);

    // This flag is to ensure backward compatibility, as brokers are upgraded after indexers/middlemanagers.
    nativeQueryContextOverrides.put(MultiStageQueryContext.WINDOW_FUNCTION_OPERATOR_TRANSFORMATION, true);
    boolean isReindex = MSQControllerTask.isReplaceInputDataSourceTask(query, destination);
    if (isReindex) {
      nativeQueryContextOverrides.put(MultiStageQueryContext.CTX_IS_REINDEX, isReindex);
    }
    nativeQueryContextOverrides.putAll(sqlQueryContext.asMap());

    // adding user
    nativeQueryContextOverrides.put(USER_KEY, plannerContext.getAuthenticationResult().getIdentity());

    final String msqMode = MultiStageQueryContext.getMSQMode(sqlQueryContext);
    if (msqMode != null) {
      MSQMode.populateDefaultQueryContext(msqMode, nativeQueryContextOverrides);
    }

    // Use the latest row-based frame type. The default is an older type, to ensure compatibility during rolling
    // updates. Since the Broker is updated last, it's safe to set this property on the Broker.
    nativeQueryContextOverrides.putIfAbsent(
        MultiStageQueryContext.CTX_ROW_BASED_FRAME_TYPE,
        (int) FrameType.latestRowBased().version()
    );

    // Add the start time.
    nativeQueryContextOverrides.put(MultiStageQueryContext.CTX_START_TIME, DateTimes.nowUtc().toString());

    return nativeQueryContextOverrides;
  }

  public static QueryDefMSQSpec makeQueryDefMSQSpec(
      @Nullable final IngestDestination targetDataSource,
      final QueryContext queryContext,
      final ColumnMappings columnMappings,
      final PlannerContext plannerContext,
      final MSQTerminalStageSpecFactory terminalStageSpecFactory,
      final QueryDefinition queryDef
  )
  {
    final MSQDestination destination = buildMSQDestination(
        targetDataSource,
        columnMappings,
        plannerContext,
        terminalStageSpecFactory
    );

    final QueryDefMSQSpec querySpec = new QueryDefMSQSpec.Builder()
        .columnMappings(columnMappings)
        .destination(destination)
        .assignmentStrategy(MultiStageQueryContext.getAssignmentStrategy(plannerContext.queryContext()))
        .tuningConfig(makeMSQTuningConfig(plannerContext))
        .queryDef(queryDef.withOverriddenContext(buildOverrideContext(null, plannerContext, destination)))
        .build();

    return querySpec;
  }

  /**
   * Simpler version of {@link #makeResultsContext(DruidQuery, List, PlannerContext)}; without any support for intermediate types.
   *
   * @throws DruidException if the query is not finalized
   */
  public static ResultsContext makeSimpleResultContext(
      QueryDefinition queryDef,
      RelDataType rowType,
      List<Entry<Integer, String>> fieldMapping,
      PlannerContext plannerContext)
  {
    RowSignature outputRowSignature = queryDef.getOutputRowSignature();
    List<Pair<SqlTypeName, ColumnType>> types = new ArrayList<>();

    if (!MultiStageQueryContext.isFinalizeAggregations(plannerContext.queryContext())) {
      throw DruidException.defensive("Non-finalized execution is not supported!");
    }

    for (final Entry<Integer, String> entry : fieldMapping) {
      final String queryColumn = outputRowSignature.getColumnName(entry.getKey());
      final SqlTypeName sqlTypeName = rowType.getFieldList().get(entry.getKey()).getType().getSqlTypeName();
      final ColumnType columnType = outputRowSignature.getColumnType(queryColumn).orElse(ColumnType.STRING);
      types.add(Pair.of(sqlTypeName, columnType));
    }

    ResultsContext resultsContext = new ResultsContext(
        types.stream().map(p -> p.lhs).collect(Collectors.toList()),
        SqlResults.Context.fromPlannerContext(plannerContext)
    );
    return resultsContext;
  }

  /**
   * Creates a {@link ResultsContext} for the given arguments.
   *
   * The {@link ResultsContext} may contain intermediate types depending on the finalized state.
   */
  public static ResultsContext makeResultsContext(DruidQuery druidQuery, List<Entry<Integer, String>> fieldMapping,
      PlannerContext plannerContext)
  {
    final List<Pair<SqlTypeName, ColumnType>> types = getTypes(druidQuery, fieldMapping, plannerContext);
    final ResultsContext resultsContext = new ResultsContext(
        types.stream().map(p -> p.lhs).collect(Collectors.toList()),
        SqlResults.Context.fromPlannerContext(plannerContext)
    );
    return resultsContext;
  }

  public static List<Pair<SqlTypeName, ColumnType>> getTypes(
      final DruidQuery druidQuery,
      final List<Entry<Integer, String>> fieldMapping,
      final PlannerContext plannerContext
  )
  {
    RelDataType outputRowType = druidQuery.getOutputRowType();
    RowSignature outputRowSignature = druidQuery.getOutputRowSignature();
    // For assistance computing return types if !finalizeAggregations.
    final Map<String, ColumnType> aggregationIntermediateTypeMap;
    if (MultiStageQueryContext.isFinalizeAggregations(plannerContext.queryContext())) {
      /* Not needed */
      aggregationIntermediateTypeMap = Collections.emptyMap();
    } else {
      aggregationIntermediateTypeMap = buildAggregationIntermediateTypeMap(druidQuery);
    }

    final List<Pair<SqlTypeName, ColumnType>> retVal = new ArrayList<>();

    for (final Entry<Integer, String> entry : fieldMapping) {
      final String queryColumn = outputRowSignature.getColumnName(entry.getKey());

      final SqlTypeName sqlTypeName;

      if (aggregationIntermediateTypeMap.containsKey(queryColumn)) {
        final ColumnType druidType = aggregationIntermediateTypeMap.get(queryColumn);
        sqlTypeName = new RowSignatures.ComplexSqlType(SqlTypeName.OTHER, druidType, true).getSqlTypeName();
      } else {
        sqlTypeName = outputRowType.getFieldList().get(entry.getKey()).getType().getSqlTypeName();
      }

      final ColumnType columnType = outputRowSignature.getColumnType(queryColumn).orElse(ColumnType.STRING);

      retVal.add(Pair.of(sqlTypeName, columnType));
    }

    return retVal;
  }

  private static Map<String, ColumnType> buildAggregationIntermediateTypeMap(final DruidQuery druidQuery)
  {
    final Grouping grouping = druidQuery.getGrouping();

    if (grouping == null) {
      return Collections.emptyMap();
    }

    final Map<String, ColumnType> retVal = new HashMap<>();

    for (final AggregatorFactory aggregatorFactory : grouping.getAggregatorFactories()) {
      retVal.put(aggregatorFactory.getName(), aggregatorFactory.getIntermediateType());
    }

    return retVal;
  }

  private static Object getSegmentGranularity(PlannerContext plannerContext)
  {
    Object segmentGranularity =
        Optional.ofNullable(plannerContext.queryContext().get(DruidSqlInsert.SQL_INSERT_SEGMENT_GRANULARITY))
                .orElseGet(() -> {
                  try {
                    return plannerContext.getJsonMapper().writeValueAsString(DEFAULT_SEGMENT_GRANULARITY);
                  }
                  catch (JsonProcessingException e) {
                    // This would only be thrown if we are unable to serialize the DEFAULT_SEGMENT_GRANULARITY,
                    // which we don't expect to happen.
                    throw DruidException.defensive().build(e, "Unable to serialize DEFAULT_SEGMENT_GRANULARITY");
                  }
                });
    return segmentGranularity;
  }

  private static List<Interval> getReplaceIntervals(QueryContext sqlQueryContext)
  {
    final List<Interval> replaceTimeChunks =
        Optional.ofNullable(sqlQueryContext.get(DruidSqlReplace.SQL_REPLACE_TIME_CHUNKS))
                .map(
                    s -> {
                      if (s instanceof String && "all".equals(StringUtils.toLowerCase((String) s))) {
                        return Intervals.ONLY_ETERNITY;
                      } else {
                        final String[] parts = ((String) s).split("\\s*,\\s*");
                        final List<Interval> intervals = new ArrayList<>();

                        for (final String part : parts) {
                          intervals.add(Intervals.of(part));
                        }

                        return intervals;
                      }
                    }
                )
                .orElse(null);
    return replaceTimeChunks;
  }

  private static MSQDestination buildExportDestination(ExportDestination targetDataSource, QueryContext sqlQueryContext)
  {
    final MSQDestination destination;
    ExportDestination exportDestination = targetDataSource;
    ResultFormat format = ResultFormat.fromString(sqlQueryContext.getString(DruidSqlIngest.SQL_EXPORT_FILE_FORMAT));

    destination = new ExportMSQDestination(
        exportDestination.getStorageConnectorProvider(),
        format
    );
    return destination;
  }

  private static MSQDestination buildTableDestination(
      IngestDestination targetDataSource,
      ColumnMappings columnMappings,
      PlannerContext plannerContext,
      MSQTerminalStageSpecFactory terminalStageSpecFactory,
      Object segmentGranularity,
      final QueryContext sqlQueryContext,
      final List<Interval> replaceTimeChunks)
  {
    final MSQDestination destination;
    Granularity segmentGranularityObject;
    try {
      segmentGranularityObject =
          plannerContext.getJsonMapper().readValue((String) segmentGranularity, Granularity.class);
    }
    catch (Exception e) {
      throw DruidException.defensive()
                          .build(
                              e,
                              "Unable to deserialize the provided segmentGranularity [%s]. "
                              + "This is populated internally by Druid and therefore should not occur. "
                              + "Please contact the developers if you are seeing this error message.",
                              segmentGranularity
                          );
    }

    final List<String> segmentSortOrder = MultiStageQueryContext.getSortOrder(sqlQueryContext);

    MSQTaskQueryMakerUtils.validateContextSortOrderColumnsExist(
        segmentSortOrder,
        Sets.newHashSet(columnMappings.getOutputColumns())
    );


    final List<AggregateProjectionSpec> projectionSpecs = getProjections(targetDataSource, plannerContext);

    final DataSourceMSQDestination dataSourceDestination = new DataSourceMSQDestination(
        targetDataSource.getDestinationName(),
        segmentGranularityObject,
        segmentSortOrder,
        replaceTimeChunks,
        null,
        projectionSpecs,
        terminalStageSpecFactory.createTerminalStageSpec(
            plannerContext
        )
    );
    MultiStageQueryContext.validateAndGetTaskLockType(sqlQueryContext, dataSourceDestination.isReplaceTimeChunks());
    destination = dataSourceDestination;
    return destination;
  }

  private static MSQTuningConfig makeMSQTuningConfig(final PlannerContext plannerContext)
  {
    final QueryContext sqlQueryContext = plannerContext.queryContext();
    final int maxNumTasks = MultiStageQueryContext.getMaxNumTasks(sqlQueryContext);

    if (maxNumTasks < 2) {
      throw InvalidInput.exception(
          "MSQ context maxNumTasks [%,d] cannot be less than 2, since at least 1 controller and 1 worker is necessary",
          maxNumTasks
      );
    }

    // This parameter is used internally for the number of worker tasks only, so we subtract 1
    final int maxNumWorkers = maxNumTasks - 1;
    final int rowsPerSegment = MultiStageQueryContext.getRowsPerSegment(sqlQueryContext);
    final int maxRowsInMemory = MultiStageQueryContext.getRowsInMemory(sqlQueryContext);
    final Integer maxNumSegments = MultiStageQueryContext.getMaxNumSegments(sqlQueryContext);
    final IndexSpec indexSpec = MultiStageQueryContext.getIndexSpec(sqlQueryContext, plannerContext.getJsonMapper());
    MSQTuningConfig tuningConfig = new MSQTuningConfig(maxNumWorkers, maxRowsInMemory, rowsPerSegment, maxNumSegments, indexSpec);
    return tuningConfig;
  }

  private static List<AggregateProjectionSpec> getProjections(
      IngestDestination targetDataSource,
      PlannerContext plannerContext
  )
  {
    final List<AggregateProjectionSpec> projectionSpecs;
    final MetadataCatalog metadataCatalog = plannerContext.getPlannerToolbox().catalogResolver().getMetadataCatalog();
    final ResolvedTable tableMetadata = metadataCatalog.resolveTable(
        TableId.datasource(targetDataSource.getDestinationName())
    );
    if (tableMetadata != null) {
      final List<DatasourceProjectionMetadata> projectionMetadata = tableMetadata.decodeProperty(
          DatasourceDefn.PROJECTIONS_KEYS_PROPERTY
      );
      if (projectionMetadata != null) {
        projectionSpecs = projectionMetadata.stream()
                                            .map(DatasourceProjectionMetadata::getSpec)
                                            .collect(Collectors.toList());
      } else {
        projectionSpecs = null;
      }
    } else {
      projectionSpecs = null;
    }
    return projectionSpecs;
  }
}
