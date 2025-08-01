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

package org.apache.druid.query;

import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.column.ValueType;

import java.util.List;

/**
 * Contains dimension names used while emitting metrics.
 */
public class DruidMetrics
{
  // Query dimensions
  public static final String DATASOURCE = "dataSource";
  public static final String TYPE = "type";
  public static final String INTERVAL = "interval";
  public static final String ID = "id";
  public static final String SUBQUERY_ID = "subQueryId";
  public static final String STATUS = "status";
  public static final String ENGINE = "engine";
  public static final String DURATION = "duration";
  public static final String SUCCESS = "success";

  // Task dimensions
  public static final String TASK_ID = "taskId";
  public static final String GROUP_ID = "groupId";
  public static final String TASK_TYPE = "taskType";
  public static final String TASK_STATUS = "taskStatus";
  public static final String DESCRIPTION = "description";

  // Ingestion dimensions
  public static final String PARTITIONING_TYPE = "partitioningType";
  public static final String TASK_INGESTION_MODE = "taskIngestionMode";
  public static final String TASK_ACTION_TYPE = "taskActionType";
  public static final String STREAM = "stream";
  public static final String PARTITION = "partition";
  public static final String SUPERVISOR_ID = "supervisorId";

  public static final String TAGS = "tags";

  public static final String CATEGORY = "category";
  public static final String WORKER_VERSION = "workerVersion";

  public static int findNumComplexAggs(List<AggregatorFactory> aggs)
  {
    int retVal = 0;
    for (AggregatorFactory agg : aggs) {
      if (agg.getIntermediateType().is(ValueType.COMPLEX)) {
        retVal++;
      }
    }
    return retVal;
  }

  public static <T> QueryMetrics<?> makeRequestMetrics(
      final GenericQueryMetricsFactory queryMetricsFactory,
      final QueryToolChest<T, Query<T>> toolChest,
      final Query<T> query,
      final String remoteAddr
  )
  {
    QueryMetrics<? super Query<T>> queryMetrics;
    if (toolChest != null) {
      queryMetrics = toolChest.makeMetrics(query);
    } else {
      queryMetrics = queryMetricsFactory.makeMetrics(query);
    }
    queryMetrics.remoteAddress(remoteAddr);
    return queryMetrics;
  }
}
