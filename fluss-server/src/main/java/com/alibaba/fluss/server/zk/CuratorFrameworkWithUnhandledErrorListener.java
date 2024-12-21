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

package com.alibaba.fluss.server.zk;

import com.alibaba.fluss.shaded.curator5.org.apache.curator.framework.CuratorFramework;
import com.alibaba.fluss.shaded.curator5.org.apache.curator.framework.api.UnhandledErrorListener;
import com.alibaba.fluss.utils.Preconditions;

import java.io.Closeable;

/**
 * A wrapper for curatorFramework and unHandledErrorListener which should be unregistered from
 * curatorFramework before closing it.
 */
public class CuratorFrameworkWithUnhandledErrorListener implements Closeable {

    private final CuratorFramework client;

    private final UnhandledErrorListener listener;

    public CuratorFrameworkWithUnhandledErrorListener(
            CuratorFramework client, UnhandledErrorListener listener) {
        this.client = Preconditions.checkNotNull(client);
        this.listener = Preconditions.checkNotNull(listener);
    }

    @Override
    public void close() {
        client.getUnhandledErrorListenable().removeListener(listener);
        client.close();
    }

    public CuratorFramework asCuratorFramework() {
        return client;
    }
}
