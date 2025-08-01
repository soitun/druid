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

package org.apache.druid.sql.calcite.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.ImmutableDruidDataSource;
import org.apache.druid.client.ImmutableDruidServer;
import org.apache.druid.client.TimelineServerView;
import org.apache.druid.client.selector.HighestPriorityTierSelectorStrategy;
import org.apache.druid.client.selector.HistoricalFilter;
import org.apache.druid.client.selector.RandomServerSelectorStrategy;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.client.selector.TierSelectorStrategy;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.TimelineLookup;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.timeline.partition.SingleElementPartitionChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * This class is used for testing and benchmark
 */
public class TestTimelineServerView implements TimelineServerView
{
  private static final DruidServerMetadata DUMMY_SERVER = new DruidServerMetadata(
      "dummy",
      "dummy:15723",
      null,
      0,
      ServerType.HISTORICAL,
      "dummy",
      0
  );
  private static final DruidServerMetadata DUMMY_SERVER_REALTIME = new DruidServerMetadata(
      "dummy2",
      "dummy2:15723",
      null,
      0,
      ServerType.REALTIME,
      "dummy",
      0
  );
  private static final DruidServerMetadata DUMMY_BROKER = new DruidServerMetadata(
      "dummy3",
      "dummy3:15723",
      null,
      0,
      ServerType.BROKER,
      "dummy",
      0
  );
  private List<DataSegment> segments = new ArrayList<>();
  private List<DataSegment> realtimeSegments = new ArrayList<>();
  private List<DataSegment> brokerSegments = new ArrayList<>();

  private List<Pair<Executor, SegmentCallback>> segmentCallbackExecs = new ArrayList<>();
  private List<Pair<Executor, TimelineCallback>> timelineCallbackExecs = new ArrayList<>();

  public TestTimelineServerView(List<DataSegment> segments)
  {
    this(segments, Collections.emptyList());
  }

  public TestTimelineServerView(List<DataSegment> segments, List<DataSegment> realtimeSegments)
  {
    this.segments.addAll(segments);
    this.realtimeSegments.addAll(realtimeSegments);
  }

  @Override
  public Optional<? extends TimelineLookup<String, ServerSelector>> getTimeline(TableDataSource table)
  {
    for (DataSegment segment : segments) {
      if (!segment.getDataSource().equals(table.getName())) {
        continue;
      }

      VersionedIntervalTimeline<String, ServerSelector> timelineLookup = new VersionedIntervalTimeline<String, ServerSelector>(
          Comparator.naturalOrder()
      );
      TierSelectorStrategy st = new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy());
      ServerSelector sss = new ServerSelector(segment, st, HistoricalFilter.IDENTITY_FILTER);

      PartitionChunk<ServerSelector> partitionChunk = new SingleElementPartitionChunk(sss);
      timelineLookup.add(segment.getInterval(), segment.getVersion(), partitionChunk);
      return Optional.of(timelineLookup);
    }
    return Optional.empty();
  }

  @Override
  public List<ImmutableDruidServer> getDruidServers()
  {
    // do not return broker on purpose to mimic behavior of BrokerServerView
    final ImmutableDruidDataSource dataSource = new ImmutableDruidDataSource("DUMMY", Collections.emptyMap(), segments);
    final ImmutableDruidServer server = new ImmutableDruidServer(
        DUMMY_SERVER,
        0L,
        ImmutableMap.of("src", dataSource),
        1
    );
    final ImmutableDruidDataSource dataSource2 = new ImmutableDruidDataSource(
        "DUMMY2",
        Collections.emptyMap(),
        realtimeSegments
    );
    final ImmutableDruidServer realtimeServer = new ImmutableDruidServer(
        DUMMY_SERVER_REALTIME,
        0L,
        ImmutableMap.of("src", dataSource2),
        1
    );
    return ImmutableList.of(server, realtimeServer);
  }

  @Override
  public void registerSegmentCallback(Executor exec, final SegmentCallback callback)
  {
    for (final DataSegment segment : segments) {
      exec.execute(() -> callback.segmentAdded(DUMMY_SERVER, segment));
    }
    for (final DataSegment segment : realtimeSegments) {
      exec.execute(() -> callback.segmentAdded(DUMMY_SERVER_REALTIME, segment));
    }
    exec.execute(callback::segmentViewInitialized);
    segmentCallbackExecs.add(new Pair<>(exec, callback));
  }

  @Override
  public void registerTimelineCallback(final Executor exec, final TimelineCallback callback)
  {
    for (DataSegment segment : segments) {
      exec.execute(() -> callback.segmentAdded(DUMMY_SERVER, segment));
    }
    for (final DataSegment segment : realtimeSegments) {
      exec.execute(() -> callback.segmentAdded(DUMMY_SERVER_REALTIME, segment));
    }
    exec.execute(callback::timelineInitialized);
    timelineCallbackExecs.add(new Pair<>(exec, callback));
  }

  @Override
  public <T> QueryRunner<T> getQueryRunner(DruidServer server)
  {
    throw new UnsupportedOperationException();
  }

  @Override
  public void registerServerCallback(Executor exec, ServerCallback callback)
  {
    // Do nothing
  }

  public void addSegment(DataSegment segment, ServerType serverType)
  {
    final Pair<DruidServerMetadata, List<DataSegment>> whichServerAndSegments =
        getDummyServerAndSegmentsForType(serverType);
    final DruidServerMetadata whichServer = whichServerAndSegments.lhs;
    whichServerAndSegments.rhs.add(segment);
    segmentCallbackExecs.forEach(
        execAndCallback -> execAndCallback.lhs.execute(() -> execAndCallback.rhs.segmentAdded(whichServer, segment))
    );
    timelineCallbackExecs.forEach(
        execAndCallback -> execAndCallback.lhs.execute(() -> execAndCallback.rhs.segmentAdded(whichServer, segment))
    );
  }

  public void removeSegment(DataSegment segment, ServerType serverType)
  {
    final Pair<DruidServerMetadata, List<DataSegment>> whichServerAndSegments =
        getDummyServerAndSegmentsForType(serverType);
    final DruidServerMetadata whichServer = whichServerAndSegments.lhs;
    whichServerAndSegments.rhs.remove(segment);
    segmentCallbackExecs.forEach(
        execAndCallback -> execAndCallback.lhs.execute(() -> execAndCallback.rhs.segmentRemoved(whichServer, segment))
    );
    timelineCallbackExecs.forEach(
        execAndCallback -> execAndCallback.lhs.execute(() -> {
          execAndCallback.rhs.serverSegmentRemoved(whichServer, segment);

          // Fire segmentRemoved if all replicas have been removed.
          if (!segments.contains(segment) && !brokerSegments.contains(segment) && !realtimeSegments.contains(segment)) {
            execAndCallback.rhs.segmentRemoved(segment);
          }
        })
    );
  }

  public void invokeSegmentSchemasAnnouncedDummy()
  {
    for (Pair<Executor, TimelineCallback> timelineCallbackExec : timelineCallbackExecs) {
      timelineCallbackExec.rhs.segmentSchemasAnnounced(null);
    }
  }

  private Pair<DruidServerMetadata, List<DataSegment>> getDummyServerAndSegmentsForType(ServerType serverType)
  {
    final DruidServerMetadata whichServer;
    final List<DataSegment> whichSegments;
    switch (serverType) {
      case BROKER:
        whichServer = DUMMY_BROKER;
        whichSegments = brokerSegments;
        break;
      case REALTIME:
        whichServer = DUMMY_SERVER_REALTIME;
        whichSegments = realtimeSegments;
        break;
      default:
        whichServer = DUMMY_SERVER;
        whichSegments = segments;
        break;
    }
    return new Pair<>(whichServer, whichSegments);
  }
}
