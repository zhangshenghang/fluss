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

package com.alibaba.fluss.rpc.metrics;

import com.alibaba.fluss.metrics.CharacterFilter;
import com.alibaba.fluss.metrics.groups.AbstractMetricGroup;
import com.alibaba.fluss.metrics.registry.MetricRegistry;

import java.util.Map;

/** The metric group for clients. */
public class ClientMetricGroup extends AbstractMetricGroup {

    private static final String NAME = "client";

    private final String clientId;

    public ClientMetricGroup(MetricRegistry registry, String clientId) {
        super(registry, new String[] {NAME}, null);
        this.clientId = clientId;
    }

    @Override
    protected String getGroupName(CharacterFilter filter) {
        return NAME;
    }

    @Override
    protected final void putVariables(Map<String, String> variables) {
        variables.put("client_id", clientId);
    }

    public MetricRegistry getMetricRegistry() {
        return registry;
    }

    public ConnectionMetricGroup createConnectionMetricGroup(String serverId) {
        return new ConnectionMetricGroup(registry, serverId, this);
    }
}
