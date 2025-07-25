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

package org.apache.druid.msq.indexing;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.error.DruidException;
import org.apache.druid.msq.exec.WorkerFailureListener;
import org.apache.druid.msq.indexing.MSQWorkerTaskLauncher.MSQWorkerTaskLauncherConfig;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

public class MSQWorkerTaskLauncherTest
{

  MSQWorkerTaskLauncher target;

  @Before
  public void setUp()
  {
    target = new MSQWorkerTaskLauncher(
        "controller-id",
        "foo",
        Mockito.mock(OverlordClient.class),
        ImmutableMap.of(),
        TimeUnit.SECONDS.toMillis(5),
        new MSQWorkerTaskLauncherConfig()
    );
  }

  @Test
  public void testRetryInactiveTasks()
  {
    target.reportFailedInactiveWorker(1);
    target.retryInactiveTasksIfNeeded(5);

    Assert.assertEquals(target.getWorkersToRelaunch(), ImmutableSet.of(1));
  }

  @Test
  public void testMultipleWorkerFailureRegistration()
  {
    target.start(getWorkerFailureListener());
    Assert.assertThrows(DruidException.class, () -> target.start(getWorkerFailureListener()));
  }

  private static WorkerFailureListener getWorkerFailureListener()
  {
    return (a, b) -> {
    };
  }
}
