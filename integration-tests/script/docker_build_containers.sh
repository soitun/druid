#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

# Build Druid Cluster Image
set -e

if [ -z "$MYSQL_DRIVER_CLASSNAME" ]
then
  export MYSQL_DRIVER_CLASSNAME="com.mysql.jdbc.Driver"
fi

if [ -z "$DRUID_INTEGRATION_TEST_JVM_RUNTIME" ]
then
  echo "\$DRUID_INTEGRATION_TEST_JVM_RUNTIME is not set. Building druid-cluster with default Java version"
  docker build -t druid/cluster --build-arg ZK_VERSION --build-arg KAFKA_VERSION --build-arg CONFLUENT_VERSION --build-arg MYSQL_VERSION --build-arg MARIA_VERSION --build-arg MYSQL_DRIVER_CLASSNAME $SHARED_DIR/docker
else
  echo "\$DRUID_INTEGRATION_TEST_JVM_RUNTIME is set with value ${DRUID_INTEGRATION_TEST_JVM_RUNTIME}"
  case "${DRUID_INTEGRATION_TEST_JVM_RUNTIME}" in
  11 | 17 | 21)
    echo "Build druid-cluster with Java $DRUID_INTEGRATION_TEST_JVM_RUNTIME"
    docker build -t druid/cluster \
      --build-arg JDK_VERSION=$DRUID_INTEGRATION_TEST_JVM_RUNTIME-slim-bullseye \
      --build-arg ZK_VERSION \
      --build-arg KAFKA_VERSION \
      --build-arg CONFLUENT_VERSION \
      --build-arg MYSQL_VERSION \
      --build-arg MARIA_VERSION \
      --build-arg MYSQL_DRIVER_CLASSNAME \
      --build-arg APACHE_ARCHIVE_MIRROR_HOST \
      $SHARED_DIR/docker
    ;;
  *)
    echo "Invalid JVM Runtime given. Stopping"
    exit 1
    ;;
  esac
fi

# Build Hadoop docker if needed
if [ -n "$DRUID_INTEGRATION_TEST_BUILD_HADOOP_DOCKER" ] && [ "$DRUID_INTEGRATION_TEST_BUILD_HADOOP_DOCKER" == true ]
then
    docker build -t druid-it/hadoop:9.9.9  --build-arg APACHE_ARCHIVE_MIRROR_HOST $HADOOP_DOCKER_DIR
fi
