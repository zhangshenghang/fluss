/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.server.metrics.group;

import com.alibaba.fluss.metadata.PhysicalTablePath;
import com.alibaba.fluss.metrics.CharacterFilter;
import com.alibaba.fluss.metrics.Counter;
import com.alibaba.fluss.metrics.MeterView;
import com.alibaba.fluss.metrics.MetricNames;
import com.alibaba.fluss.metrics.ThreadSafeSimpleCounter;
import com.alibaba.fluss.metrics.groups.AbstractMetricGroup;
import com.alibaba.fluss.metrics.registry.MetricRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The metric group for tablet server. */
public class TabletServerMetricGroup extends AbstractMetricGroup {

    private static final String NAME = "tabletserver";

    private final Map<PhysicalTablePath, PhysicalTableMetricGroup> metricGroupByPhysicalTable =
            new ConcurrentHashMap<>();

    protected final String clusterId;
    protected final String hostname;
    protected final int serverId;

    // ---- metrics ----
    private final Counter replicationBytesIn;
    private final Counter replicationBytesOut;

    public TabletServerMetricGroup(
            MetricRegistry registry, String clusterId, String hostname, int serverId) {
        super(registry, new String[] {clusterId, hostname, NAME}, null);
        this.clusterId = clusterId;
        this.hostname = hostname;
        this.serverId = serverId;

        replicationBytesIn = new ThreadSafeSimpleCounter();
        meter(MetricNames.REPLICATION_IN_RATE, new MeterView(replicationBytesIn));
        replicationBytesOut = new ThreadSafeSimpleCounter();
        meter(MetricNames.REPLICATION_OUT_RATE, new MeterView(replicationBytesOut));
    }

    @Override
    protected final void putVariables(Map<String, String> variables) {
        variables.put("cluster_id", clusterId);
        variables.put("host", hostname);
        variables.put("server_id", String.valueOf(serverId));
    }

    @Override
    protected String getGroupName(CharacterFilter filter) {
        return NAME;
    }

    public Counter replicationBytesIn() {
        return replicationBytesIn;
    }

    public Counter replicationBytesOut() {
        return replicationBytesOut;
    }

    // ------------------------------------------------------------------------
    //  table buckets groups
    // ------------------------------------------------------------------------
    public BucketMetricGroup addPhysicalTableBucketMetricGroup(
            PhysicalTablePath physicalTablePath, int bucket, boolean isKvTable) {
        PhysicalTableMetricGroup physicalTableMetricGroup =
                metricGroupByPhysicalTable.computeIfAbsent(
                        physicalTablePath,
                        table ->
                                new PhysicalTableMetricGroup(
                                        registry, physicalTablePath, isKvTable, this));
        return physicalTableMetricGroup.addBucketMetricGroup(bucket);
    }

    public void removeTableBucketMetricGroup(PhysicalTablePath physicalTablePath, int bucket) {
        // get the metric group of the physical table
        PhysicalTableMetricGroup physicalTableMetricGroup =
                metricGroupByPhysicalTable.get(physicalTablePath);
        // if get the physical table metric group
        if (physicalTableMetricGroup != null) {
            // remove the bucket metric group
            physicalTableMetricGroup.removeBucketMetricGroup(bucket);
            // if no any bucket groups remain in the physical table metrics group,
            // close and remove the physical table metric group
            if (physicalTableMetricGroup.bucketGroupsCount() == 0) {
                physicalTableMetricGroup.close();
                metricGroupByPhysicalTable.remove(physicalTablePath);
            }
        }
    }
}
