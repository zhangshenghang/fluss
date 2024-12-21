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

package com.alibaba.fluss.server.tablet;

import com.alibaba.fluss.annotation.VisibleForTesting;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.IllegalConfigurationException;
import com.alibaba.fluss.metrics.registry.MetricRegistry;
import com.alibaba.fluss.rpc.GatewayClientProxy;
import com.alibaba.fluss.rpc.RpcClient;
import com.alibaba.fluss.rpc.RpcServer;
import com.alibaba.fluss.rpc.gateway.CoordinatorGateway;
import com.alibaba.fluss.rpc.metrics.ClientMetricGroup;
import com.alibaba.fluss.rpc.netty.server.RequestsMetrics;
import com.alibaba.fluss.server.ServerBase;
import com.alibaba.fluss.server.kv.KvManager;
import com.alibaba.fluss.server.kv.snapshot.DefaultCompletedKvSnapshotCommitter;
import com.alibaba.fluss.server.log.LogManager;
import com.alibaba.fluss.server.log.remote.RemoteLogManager;
import com.alibaba.fluss.server.metadata.ServerMetadataCache;
import com.alibaba.fluss.server.metadata.ServerMetadataCacheImpl;
import com.alibaba.fluss.server.metrics.ServerMetricUtils;
import com.alibaba.fluss.server.metrics.group.TabletServerMetricGroup;
import com.alibaba.fluss.server.replica.ReplicaManager;
import com.alibaba.fluss.server.zk.ZooKeeperClient;
import com.alibaba.fluss.server.zk.ZooKeeperUtils;
import com.alibaba.fluss.server.zk.data.TabletServerRegistration;
import com.alibaba.fluss.shaded.zookeeper3.org.apache.zookeeper.KeeperException;
import com.alibaba.fluss.utils.ExceptionUtils;
import com.alibaba.fluss.utils.clock.SystemClock;
import com.alibaba.fluss.utils.concurrent.FlussScheduler;
import com.alibaba.fluss.utils.concurrent.FutureUtils;
import com.alibaba.fluss.utils.concurrent.Scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.fluss.config.ConfigOptions.BACKGROUND_THREADS;

/**
 * Tablet server implementation. The tablet server is responsible to manage the log tablet and kv
 * tablet.
 */
public class TabletServer extends ServerBase {

    private static final String SERVER_NAME = "TabletServer";

    private static final Logger LOG = LoggerFactory.getLogger(TabletServer.class);

    private static final long ZOOKEEPER_REGISTER_TOTAL_WAIT_TIME_MS = 60 * 1000L;
    private static final long ZOOKEEPER_REGISTER_RETRY_INTERVAL_MS = 3 * 1000L;

    private final int serverId;

    /** The lock to guard startup / shutdown / manipulation methods. */
    private final Object lock = new Object();

    private final CompletableFuture<Result> terminationFuture;

    private final AtomicBoolean isShutDown = new AtomicBoolean(false);

    @GuardedBy("lock")
    private RpcServer rpcServer;

    @GuardedBy("lock")
    private RpcClient rpcClient;

    @GuardedBy("lock")
    private ClientMetricGroup clientMetricGroup;

    @GuardedBy("lock")
    private TabletService tabletService;

    @GuardedBy("lock")
    private MetricRegistry metricRegistry;

    @GuardedBy("lock")
    private TabletServerMetricGroup tabletServerMetricGroup;

    @GuardedBy("lock")
    private ServerMetadataCache metadataCache;

    @GuardedBy("lock")
    private LogManager logManager;

    @GuardedBy("lock")
    private KvManager kvManager;

    @GuardedBy("lock")
    private ReplicaManager replicaManager;

    @GuardedBy("lock")
    private @Nullable RemoteLogManager remoteLogManager = null;

    @GuardedBy("lock")
    private Scheduler scheduler;

    @GuardedBy("lock")
    private ZooKeeperClient zkClient;

    public TabletServer(Configuration conf) {
        super(conf);
        validateConfigs(conf);
        this.terminationFuture = new CompletableFuture<>();
        this.serverId = conf.getInt(ConfigOptions.TABLET_SERVER_ID);
    }

    public static void main(String[] args) {
        Configuration configuration = loadConfiguration(args, TabletServer.class.getSimpleName());
        TabletServer tabletServer = new TabletServer(configuration);
        startServer(tabletServer);
    }

    @Override
    protected void startServices() throws Exception {
        synchronized (lock) {
            LOG.info("Initializing Tablet services.");

            // for metrics
            this.metricRegistry = MetricRegistry.create(conf, pluginManager);
            this.tabletServerMetricGroup =
                    ServerMetricUtils.createTabletServerGroup(
                            metricRegistry,
                            ServerMetricUtils.validateAndGetClusterId(conf),
                            conf.getString(ConfigOptions.TABLET_SERVER_HOST),
                            serverId);

            this.zkClient = ZooKeeperUtils.startZookeeperClient(conf, this);

            this.metadataCache = new ServerMetadataCacheImpl();

            this.scheduler = new FlussScheduler(conf.get(BACKGROUND_THREADS));
            scheduler.startup();

            this.logManager =
                    LogManager.create(conf, zkClient, scheduler, SystemClock.getInstance());
            logManager.startup();

            this.kvManager = KvManager.create(conf, zkClient, logManager);
            kvManager.startup();

            // rpc client to sent request to the tablet server where the leader replica is located
            // to fetch log.
            this.clientMetricGroup =
                    new ClientMetricGroup(metricRegistry, SERVER_NAME + "-" + serverId);
            this.rpcClient = RpcClient.create(conf, clientMetricGroup);

            CoordinatorGateway coordinatorGateway =
                    GatewayClientProxy.createGatewayProxy(
                            metadataCache::getCoordinatorServer,
                            rpcClient,
                            CoordinatorGateway.class);

            this.replicaManager =
                    new ReplicaManager(
                            conf,
                            scheduler,
                            logManager,
                            kvManager,
                            zkClient,
                            serverId,
                            metadataCache,
                            rpcClient,
                            coordinatorGateway,
                            DefaultCompletedKvSnapshotCommitter.create(rpcClient, metadataCache),
                            this,
                            tabletServerMetricGroup);
            replicaManager.startup();

            this.tabletService =
                    new TabletService(
                            conf,
                            serverId,
                            remoteFileSystem,
                            zkClient,
                            replicaManager,
                            metadataCache);

            RequestsMetrics requestsMetrics =
                    RequestsMetrics.createTabletServerRequestMetrics(tabletServerMetricGroup);
            this.rpcServer =
                    RpcServer.create(
                            conf,
                            conf.getString(ConfigOptions.TABLET_SERVER_HOST),
                            conf.getString(ConfigOptions.TABLET_SERVER_PORT),
                            tabletService,
                            requestsMetrics);
            rpcServer.start();

            registerTabletServer();
        }
    }

    @Override
    protected CompletableFuture<Result> closeAsync(Result result) {
        if (isShutDown.compareAndSet(false, true)) {
            CompletableFuture<Void> serviceShutdownFuture = stopServices();

            serviceShutdownFuture.whenComplete(
                    ((Void ignored2, Throwable serviceThrowable) -> {
                        if (serviceThrowable != null) {
                            terminationFuture.completeExceptionally(serviceThrowable);
                        } else {
                            terminationFuture.complete(result);
                        }
                    }));
        }

        return terminationFuture;
    }

    @Override
    protected CompletableFuture<Result> getTerminationFuture() {
        return terminationFuture;
    }

    private void registerTabletServer() throws Exception {
        long startTime = System.currentTimeMillis();
        TabletServerRegistration tabletServerRegistration =
                new TabletServerRegistration(
                        rpcServer.getHostname(), rpcServer.getPort(), startTime);

        while (true) {
            try {
                zkClient.registerTabletServer(serverId, tabletServerRegistration);
                break;
            } catch (KeeperException.NodeExistsException nodeExistsException) {
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime >= ZOOKEEPER_REGISTER_TOTAL_WAIT_TIME_MS) {
                    LOG.error(
                            "Tablet server id {} register to Zookeeper exceeded total retry time of {} ms. "
                                    + "Aborting registration attempts.",
                            serverId,
                            ZOOKEEPER_REGISTER_TOTAL_WAIT_TIME_MS);
                    throw nodeExistsException;
                }

                LOG.warn(
                        "Tablet server id {} already registered in Zookeeper. "
                                + "retrying register after {} ms....",
                        serverId,
                        ZOOKEEPER_REGISTER_RETRY_INTERVAL_MS);
                try {
                    Thread.sleep(ZOOKEEPER_REGISTER_RETRY_INTERVAL_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    CompletableFuture<Void> stopServices() {
        synchronized (lock) {
            Throwable exception = null;

            try {
                if (tabletServerMetricGroup != null) {
                    tabletServerMetricGroup.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            final Collection<CompletableFuture<Void>> terminationFutures = new ArrayList<>(2);
            try {
                if (metricRegistry != null) {
                    terminationFutures.add(metricRegistry.closeAsync());
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (rpcServer != null) {
                    terminationFutures.add(rpcServer.closeAsync());
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (tabletService != null) {
                    tabletService.shutdown();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (zkClient != null) {
                    zkClient.close();
                }

                // TODO currently, rpc client don't have timeout logic. After implementing the
                // timeout logic, we need to move the closure of rpc client to after the closure of
                // replica manager.
                if (rpcClient != null) {
                    rpcClient.close();
                }

                if (clientMetricGroup != null) {
                    clientMetricGroup.close();
                }

                // We must shut down the scheduler early because otherwise, the scheduler could
                // touch other resources that might have been shutdown and cause exceptions.
                if (scheduler != null) {
                    scheduler.shutdown();
                }

                if (kvManager != null) {
                    kvManager.shutdown();
                }

                if (remoteLogManager != null) {
                    remoteLogManager.close();
                }

                if (logManager != null) {
                    logManager.shutdown();
                }

                if (replicaManager != null) {
                    replicaManager.shutdown();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            if (exception != null) {
                terminationFutures.add(FutureUtils.completedExceptionally(exception));
            }
            return FutureUtils.completeAll(terminationFutures);
        }
    }

    @Override
    protected String getServerName() {
        return SERVER_NAME;
    }

    @VisibleForTesting
    public int getServerId() {
        return serverId;
    }

    @VisibleForTesting
    public ReplicaManager getReplicaManager() {
        return replicaManager;
    }

    private static void validateConfigs(Configuration conf) {
        Optional<Integer> serverId = conf.getOptional(ConfigOptions.TABLET_SERVER_ID);
        if (!serverId.isPresent()) {
            throw new IllegalConfigurationException(
                    String.format("Configuration %s must be set.", ConfigOptions.TABLET_SERVER_ID));
        }

        if (serverId.get() < 0) {
            throw new IllegalConfigurationException(
                    String.format(
                            "Invalid configuration for %s, it must be greater than or equal 0.",
                            ConfigOptions.TABLET_SERVER_ID.key()));
        }

        if (conf.get(ConfigOptions.REMOTE_DATA_DIR) == null) {
            throw new IllegalConfigurationException(
                    String.format("Configuration %s must be set.", ConfigOptions.REMOTE_DATA_DIR));
        }
    }
}
