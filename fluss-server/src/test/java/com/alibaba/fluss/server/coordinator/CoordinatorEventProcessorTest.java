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

package com.alibaba.fluss.server.coordinator;

import com.alibaba.fluss.cluster.ServerNode;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.FencedLeaderEpochException;
import com.alibaba.fluss.exception.InvalidCoordinatorException;
import com.alibaba.fluss.metadata.Schema;
import com.alibaba.fluss.metadata.TableBucket;
import com.alibaba.fluss.metadata.TableBucketReplica;
import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.metadata.TablePartition;
import com.alibaba.fluss.metadata.TablePath;
import com.alibaba.fluss.rpc.messages.CommitKvSnapshotResponse;
import com.alibaba.fluss.server.coordinator.event.CommitKvSnapshotEvent;
import com.alibaba.fluss.server.coordinator.event.CoordinatorEvent;
import com.alibaba.fluss.server.coordinator.event.CoordinatorEventManager;
import com.alibaba.fluss.server.coordinator.event.CreatePartitionEvent;
import com.alibaba.fluss.server.coordinator.event.DropPartitionEvent;
import com.alibaba.fluss.server.coordinator.statemachine.BucketState;
import com.alibaba.fluss.server.coordinator.statemachine.ReplicaState;
import com.alibaba.fluss.server.entity.CommitKvSnapshotData;
import com.alibaba.fluss.server.kv.snapshot.CompletedSnapshot;
import com.alibaba.fluss.server.kv.snapshot.ZooKeeperCompletedSnapshotHandleStore;
import com.alibaba.fluss.server.metadata.ServerMetadataCache;
import com.alibaba.fluss.server.metadata.ServerMetadataCacheImpl;
import com.alibaba.fluss.server.metrics.group.TestingMetricGroups;
import com.alibaba.fluss.server.testutils.KvTestUtils;
import com.alibaba.fluss.server.utils.TableAssignmentUtils;
import com.alibaba.fluss.server.zk.NOPErrorHandler;
import com.alibaba.fluss.server.zk.ZooKeeperClient;
import com.alibaba.fluss.server.zk.ZooKeeperExtension;
import com.alibaba.fluss.server.zk.data.BucketAssignment;
import com.alibaba.fluss.server.zk.data.LeaderAndIsr;
import com.alibaba.fluss.server.zk.data.PartitionAssignment;
import com.alibaba.fluss.server.zk.data.TableAssignment;
import com.alibaba.fluss.server.zk.data.TabletServerRegistration;
import com.alibaba.fluss.server.zk.data.ZkData.PartitionIdsZNode;
import com.alibaba.fluss.server.zk.data.ZkData.TableIdsZNode;
import com.alibaba.fluss.testutils.common.AllCallbackWrapper;
import com.alibaba.fluss.types.DataTypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.alibaba.fluss.server.coordinator.CoordinatorTestUtils.verifyBucketForPartitionInState;
import static com.alibaba.fluss.server.coordinator.CoordinatorTestUtils.verifyBucketForTableInState;
import static com.alibaba.fluss.server.coordinator.CoordinatorTestUtils.verifyReplicaForPartitionInState;
import static com.alibaba.fluss.server.coordinator.CoordinatorTestUtils.verifyReplicaForTableInState;
import static com.alibaba.fluss.server.coordinator.statemachine.BucketState.OfflineBucket;
import static com.alibaba.fluss.server.coordinator.statemachine.BucketState.OnlineBucket;
import static com.alibaba.fluss.server.coordinator.statemachine.ReplicaState.OfflineReplica;
import static com.alibaba.fluss.server.coordinator.statemachine.ReplicaState.OnlineReplica;
import static com.alibaba.fluss.testutils.common.CommonTestUtils.retry;
import static com.alibaba.fluss.testutils.common.CommonTestUtils.waitUtil;
import static com.alibaba.fluss.testutils.common.CommonTestUtils.waitValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for {@link CoordinatorEventProcessor}. */
class CoordinatorEventProcessorTest {

    private static final TableDescriptor TEST_TABLE =
            TableDescriptor.builder()
                    .schema(Schema.newBuilder().column("a", DataTypes.INT()).build())
                    .distributedBy(3, "a")
                    .build();

    private static final int N_BUCKETS = 3;
    private static final int REPLICATION_FACTOR = 3;

    @RegisterExtension
    public static final AllCallbackWrapper<ZooKeeperExtension> ZOO_KEEPER_EXTENSION_WRAPPER =
            new AllCallbackWrapper<>(new ZooKeeperExtension());

    private static ZooKeeperClient zookeeperClient;
    private static MetaDataManager metaDataManager;

    private CoordinatorEventProcessor eventProcessor;
    private final String defaultDatabase = "db";
    private ServerMetadataCache serverMetadataCache;
    private TestCoordinatorChannelManager testCoordinatorChannelManager;
    private CompletedSnapshotStoreManager completedSnapshotStoreManager;
    private AutoPartitionManager autoPartitionManager;

    @BeforeAll
    static void baseBeforeAll() throws Exception {
        zookeeperClient =
                ZOO_KEEPER_EXTENSION_WRAPPER
                        .getCustomExtension()
                        .getZooKeeperClient(NOPErrorHandler.INSTANCE);
        metaDataManager = new MetaDataManager(zookeeperClient);
        // register 3 tablet servers
        for (int i = 0; i < 3; i++) {
            zookeeperClient.registerTabletServer(
                    i, new TabletServerRegistration("host" + i, 1000, System.currentTimeMillis()));
        }
    }

    @BeforeEach
    void beforeEach() {
        serverMetadataCache = new ServerMetadataCacheImpl();
        // set a test channel manager for the context
        testCoordinatorChannelManager = new TestCoordinatorChannelManager();
        completedSnapshotStoreManager = new CompletedSnapshotStoreManager(1, 1, zookeeperClient);
        autoPartitionManager =
                new AutoPartitionManager(serverMetadataCache, zookeeperClient, new Configuration());
        eventProcessor =
                new CoordinatorEventProcessor(
                        zookeeperClient,
                        serverMetadataCache,
                        testCoordinatorChannelManager,
                        completedSnapshotStoreManager,
                        autoPartitionManager,
                        TestingMetricGroups.COORDINATOR_METRICS);
        eventProcessor.startup();
        metaDataManager.createDatabase(defaultDatabase, false);
    }

    @AfterEach
    void afterEach() {
        eventProcessor.shutdown();
        metaDataManager.dropDatabase(defaultDatabase, false, true);
        // clear the assignment info for all tables;
        ZOO_KEEPER_EXTENSION_WRAPPER.getCustomExtension().cleanupPath(TableIdsZNode.path());
        ZOO_KEEPER_EXTENSION_WRAPPER.getCustomExtension().cleanupPath(PartitionIdsZNode.path());
    }

    @Test
    void testCreateAndDropTable() throws Exception {
        CoordinatorContext coordinatorContext = eventProcessor.getCoordinatorContext();
        // make sure all request to gateway should be successful
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                eventProcessor.getCoordinatorContext(), testCoordinatorChannelManager);
        // create a table,
        TablePath t1 = TablePath.of(defaultDatabase, "create_drop_t1");
        TableDescriptor tableDescriptor = TEST_TABLE;
        int nBuckets = 3;
        int replicationFactor = 3;
        TableAssignment tableAssignment =
                TableAssignmentUtils.generateAssignment(
                        nBuckets, replicationFactor, new int[] {0, 1, 2});
        long t1Id = metaDataManager.createTable(t1, tableDescriptor, tableAssignment, false);

        TablePath t2 = TablePath.of(defaultDatabase, "create_drop_t2");
        long t2Id = metaDataManager.createTable(t2, tableDescriptor, tableAssignment, false);

        verifyTableCreated(coordinatorContext, t2Id, tableAssignment, nBuckets, replicationFactor);

        // drop the table;
        metaDataManager.dropTable(t1, false);

        verifyTableDropped(coordinatorContext, t1Id);

        // replicas and buckets for t2 should still be online
        verifyBucketForTableInState(coordinatorContext, t2Id, nBuckets, BucketState.OnlineBucket);
        verifyReplicaForTableInState(
                coordinatorContext, t2Id, nBuckets * replicationFactor, ReplicaState.OnlineReplica);

        // shutdown event processor and delete the table node for t2 from zk
        // to mock the case that the table hasn't been deleted completely
        // , but the coordinator shut down
        eventProcessor.shutdown();
        metaDataManager.dropTable(t2, false);

        // start the coordinator
        eventProcessor =
                new CoordinatorEventProcessor(
                        zookeeperClient,
                        serverMetadataCache,
                        testCoordinatorChannelManager,
                        completedSnapshotStoreManager,
                        autoPartitionManager,
                        TestingMetricGroups.COORDINATOR_METRICS);
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                testCoordinatorChannelManager,
                Arrays.stream(zookeeperClient.getSortedTabletServerList())
                        .boxed()
                        .collect(Collectors.toSet()));
        eventProcessor.startup();
        // make sure the table can still be deleted successfully
        retry(
                Duration.ofMinutes(1),
                () -> assertThat(zookeeperClient.getTableAssignment(t2Id)).isEmpty());

        // no replica and bucket for t2 should exist in the context
        assertThat(coordinatorContext.getAllBucketsForTable(t2Id)).isEmpty();
        assertThat(coordinatorContext.getAllReplicasForTable(t2Id)).isEmpty();
    }

    @Test
    void testDropTableWithRetry() throws Exception {
        // make request to some server should fail, but delete will still be successful
        // finally with retry logic
        int failedServer = 0;
        CoordinatorTestUtils.makeSendLeaderAndStopRequestFailContext(
                testCoordinatorChannelManager,
                Arrays.stream(zookeeperClient.getSortedTabletServerList())
                        .boxed()
                        .collect(Collectors.toSet()),
                Collections.singleton(failedServer));
        // create a table,
        TablePath t1 = TablePath.of(defaultDatabase, "tdrop");
        final long t1Id = createTable(t1, new int[] {0, 1, 2});

        final CoordinatorContext coordinatorContext = eventProcessor.getCoordinatorContext();

        // retry until the create table t1 has been handled by coordinator
        // otherwise, when receive create table event, it can't find the schema of the table
        // since it has been deleted by the following code) which cause delete
        // won't don anything
        // todo: may need to fix this case;
        waitUtil(
                () -> coordinatorContext.getTablePathById(t1Id) != null,
                Duration.ofMinutes(1),
                "Fail to wait for coordinator handling create table event for table " + t1Id);

        // drop the table;
        metaDataManager.dropTable(t1, false);

        // retry until the assignment has been deleted from zk, then it means
        // the table has been deleted successfully
        retry(
                Duration.ofMinutes(1),
                () -> assertThat(zookeeperClient.getTableAssignment(t1Id)).isEmpty());
    }

    @Test
    void testServerBecomeOnlineAndOfflineLine() throws Exception {
        CoordinatorContext coordinatorContext = eventProcessor.getCoordinatorContext();
        // make sure all request to gateway should be successful
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                eventProcessor.getCoordinatorContext(), testCoordinatorChannelManager);
        // assume a new server become online;
        // check the server has been added into coordinator context
        ZooKeeperClient client =
                ZOO_KEEPER_EXTENSION_WRAPPER
                        .getCustomExtension()
                        .createZooKeeperClient(NOPErrorHandler.INSTANCE);
        int newlyServerId = 3;
        TabletServerRegistration tabletServerRegistration =
                new TabletServerRegistration("host3", 1234, System.currentTimeMillis());
        client.registerTabletServer(newlyServerId, tabletServerRegistration);

        // retry until the tablet server register event is been handled
        retry(
                Duration.ofMinutes(1),
                () ->
                        assertThat(coordinatorContext.getLiveTabletServers())
                                .containsKey(newlyServerId));

        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                eventProcessor.getCoordinatorContext(), testCoordinatorChannelManager);
        verifyTabletServer(coordinatorContext, newlyServerId, tabletServerRegistration);

        // we try to assign a replica to this newly server, every thing will
        // be fine
        // t1: {bucket0: [0, 3, 2], bucket1: [3, 2, 0]}, t2: {bucket0: [3]}
        MetaDataManager metaDataManager = new MetaDataManager(zookeeperClient);
        TableAssignment table1Assignment =
                TableAssignment.builder()
                        .add(0, BucketAssignment.of(0, 3, 2))
                        .add(1, BucketAssignment.of(3, 2, 0))
                        .build();

        TablePath table1Path = TablePath.of(defaultDatabase, "t1");
        long table1Id =
                metaDataManager.createTable(table1Path, TEST_TABLE, table1Assignment, false);

        TableAssignment table2Assignment =
                TableAssignment.builder().add(0, BucketAssignment.of(3)).build();
        TablePath table2Path = TablePath.of(defaultDatabase, "t2");
        long table2Id =
                metaDataManager.createTable(table2Path, TEST_TABLE, table2Assignment, false);

        // retry until the table2 been created
        retry(
                Duration.ofMinutes(1),
                () ->
                        assertThat(
                                        coordinatorContext.getBucketLeaderAndIsr(
                                                new TableBucket(table2Id, 0)))
                                .isNotEmpty());

        // now, assume the server 3 is down;
        client.close();

        // retry until the server has been removed from coordinator context
        retry(
                Duration.ofMinutes(1),
                () ->
                        assertThat(
                                        new HashSet<>(
                                                coordinatorContext.getLiveTabletServers().keySet()))
                                .doesNotContain(newlyServerId));

        // check replica state
        // all replicas should be online but the replica in the down server
        // should be offline
        verifyReplicaOnlineOrOffline(
                coordinatorContext,
                table1Id,
                table1Assignment,
                Collections.singleton(newlyServerId));
        verifyReplicaOnlineOrOffline(
                coordinatorContext,
                table2Id,
                table2Assignment,
                Collections.singleton(newlyServerId));

        // now, check bucket state
        TableBucket t1Bucket0 = new TableBucket(table1Id, 0);
        TableBucket t1Bucket1 = new TableBucket(table1Id, 1);
        TableBucket t2Bucket0 = new TableBucket(table2Id, 0);
        // t1 bucket 0 should still be online since the leader is alive
        assertThat(coordinatorContext.getBucketState(t1Bucket0)).isEqualTo(OnlineBucket);
        // t1 bucket 1 should reelect a leader since the leader is not alive
        // the bucket whose leader is in the server should be online a again, but the leadership
        // should change the leader for bucket2 of t1 should change since the leader fail
        assertThat(coordinatorContext.getBucketState(t1Bucket1)).isEqualTo(OnlineBucket);
        // leader should change to replica2, leader epoch should be 1
        CoordinatorTestUtils.checkLeaderAndIsr(zookeeperClient, t1Bucket1, 1, 2);

        // the bucket with no any other available servers should be still offline,
        // t2 bucket0 should still be offline
        assertThat(coordinatorContext.getBucketState(t2Bucket0)).isEqualTo(OfflineBucket);

        // assume the server that comes again
        zookeeperClient.registerTabletServer(newlyServerId, tabletServerRegistration);
        // retry until the server has been added to coordinator context
        retry(
                Duration.ofMinutes(1),
                () ->
                        assertThat(
                                        new HashSet<>(
                                                coordinatorContext.getLiveTabletServers().keySet()))
                                .contains(newlyServerId));

        // make sure the bucket that remains in offline should be online again
        // since the server become online
        // bucket0 for t2 should then be online
        // retry until the state changes
        retry(
                Duration.ofMinutes(1),
                () ->
                        assertThat(coordinatorContext.getBucketState(t2Bucket0))
                                .isEqualTo(OnlineBucket));

        // make sure all the replica will be online again
        verifyReplicaOnlineOrOffline(
                coordinatorContext, table1Id, table1Assignment, Collections.emptySet());
        verifyReplicaOnlineOrOffline(
                coordinatorContext, table2Id, table2Assignment, Collections.emptySet());

        // let's restart to check everything is ok
        eventProcessor.shutdown();
        eventProcessor =
                new CoordinatorEventProcessor(
                        zookeeperClient,
                        serverMetadataCache,
                        testCoordinatorChannelManager,
                        completedSnapshotStoreManager,
                        autoPartitionManager,
                        TestingMetricGroups.COORDINATOR_METRICS);
        CoordinatorContext newCoordinatorContext = eventProcessor.getCoordinatorContext();

        // in this test case, so make requests to gateway should always be
        // successful for when start up, it will send request to tablet servers
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                testCoordinatorChannelManager,
                Arrays.stream(zookeeperClient.getSortedTabletServerList())
                        .boxed()
                        .collect(Collectors.toSet()));
        eventProcessor.startup();

        // check every thing is ok
        // all replicas should be online again
        verifyReplicaOnlineOrOffline(
                newCoordinatorContext, table1Id, table1Assignment, Collections.emptySet());
        verifyReplicaOnlineOrOffline(
                newCoordinatorContext, table2Id, table2Assignment, Collections.emptySet());
        // all bucket should be online
        assertThat(newCoordinatorContext.getBucketState(t1Bucket0)).isEqualTo(OnlineBucket);
        assertThat(newCoordinatorContext.getBucketState(t1Bucket1)).isEqualTo(OnlineBucket);
        assertThat(newCoordinatorContext.getBucketState(t2Bucket0)).isEqualTo(OnlineBucket);
    }

    @Test
    void testRestartTriggerReplicaToOffline() throws Exception {
        // case1: coordinator server restart, and first set the replica to online
        // but the request to the replica server fail which will then cause it offline
        MetaDataManager metaDataManager = new MetaDataManager(zookeeperClient);
        TableAssignment tableAssignment =
                TableAssignment.builder()
                        .add(0, BucketAssignment.of(0, 1, 2))
                        .add(1, BucketAssignment.of(1, 2, 0))
                        .build();
        TablePath tablePath = TablePath.of(defaultDatabase, "t_restart");
        long table1Id = metaDataManager.createTable(tablePath, TEST_TABLE, tableAssignment, false);

        // let's restart
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                eventProcessor.getCoordinatorContext(), testCoordinatorChannelManager);
        eventProcessor.shutdown();
        eventProcessor =
                new CoordinatorEventProcessor(
                        zookeeperClient,
                        serverMetadataCache,
                        testCoordinatorChannelManager,
                        completedSnapshotStoreManager,
                        autoPartitionManager,
                        TestingMetricGroups.COORDINATOR_METRICS);
        CoordinatorContext coordinatorContext = eventProcessor.getCoordinatorContext();
        int failedServer = 0;
        CoordinatorTestUtils.makeSendLeaderAndStopRequestFailContext(
                testCoordinatorChannelManager,
                Arrays.stream(zookeeperClient.getSortedTabletServerList())
                        .boxed()
                        .collect(Collectors.toSet()),
                Collections.singleton(failedServer));
        eventProcessor.startup();

        // all buckets should be online
        TableBucket t1Bucket0 = new TableBucket(table1Id, 0);
        TableBucket t1Bucket1 = new TableBucket(table1Id, 1);
        // retry until the bucket0 change leader to 1
        retry(
                Duration.ofMinutes(1),
                () -> {
                    Optional<LeaderAndIsr> leaderAndIsr =
                            zookeeperClient.getLeaderAndIsr(t1Bucket0);
                    assertThat(leaderAndIsr).isPresent();
                    assertThat(leaderAndIsr.get().leader()).isEqualTo(1);
                });

        // check the changed leader and isr info
        CoordinatorTestUtils.checkLeaderAndIsr(zookeeperClient, t1Bucket0, 1, 1);
        retry(
                Duration.ofMinutes(1),
                () -> {
                    assertThat(coordinatorContext.getBucketState(t1Bucket0))
                            .isEqualTo(OnlineBucket);
                    assertThat(coordinatorContext.getBucketState(t1Bucket1))
                            .isEqualTo(OnlineBucket);
                });
        // only replica0 will be offline
        verifyReplicaOnlineOrOffline(
                coordinatorContext, table1Id, tableAssignment, Collections.singleton(failedServer));
    }

    @Test
    void testAddBucketCompletedSnapshot(@TempDir Path tempDir) throws Exception {
        ZooKeeperCompletedSnapshotHandleStore completedSnapshotHandleStore =
                new ZooKeeperCompletedSnapshotHandleStore(zookeeperClient);
        TablePath t1 = TablePath.of(defaultDatabase, "t_completed_snapshot");
        final long t1Id = createTable(t1, new int[] {0, 1, 2});
        CoordinatorEventManager coordinatorEventManager =
                eventProcessor.getCoordinatorEventManager();
        int snapshotNum = 2;
        int bucketLeaderEpoch = 0;
        int coordinatorEpoch = 0;
        for (int i = 0; i < N_BUCKETS; i++) {
            TableBucket tableBucket = new TableBucket(t1Id, i);
            // wait until the leader is elected
            waitValue(
                    () -> zookeeperClient.getLeaderAndIsr(tableBucket),
                    Duration.ofMinutes(1),
                    "leader not elected");
            for (int snapshot = 0; snapshot < snapshotNum; snapshot++) {
                CompletedSnapshot completedSnapshot =
                        KvTestUtils.mockCompletedSnapshot(tempDir, tableBucket, snapshot);
                CompletableFuture<CommitKvSnapshotResponse> responseCompletableFuture =
                        new CompletableFuture<>();
                coordinatorEventManager.put(
                        new CommitKvSnapshotEvent(
                                new CommitKvSnapshotData(
                                        completedSnapshot, coordinatorEpoch, bucketLeaderEpoch),
                                responseCompletableFuture));

                // get the response
                responseCompletableFuture.get();

                // get completed snapshot
                CompletedSnapshot gotCompletedSnapshot =
                        completedSnapshotHandleStore
                                .get(tableBucket, snapshot)
                                .get()
                                .retrieveCompleteSnapshot();
                // check the gotten snapshot
                assertThat(gotCompletedSnapshot).isEqualTo(completedSnapshot);
            }
        }

        // we check invalid case
        TableBucket tableBucket = new TableBucket(t1Id, 0);

        // in valid bucket leader epoch
        int invalidBucketLeaderEpoch = -1;
        CompletedSnapshot completedSnapshot =
                KvTestUtils.mockCompletedSnapshot(tempDir, tableBucket, 2);
        CompletableFuture<CommitKvSnapshotResponse> responseCompletableFuture =
                new CompletableFuture<>();
        coordinatorEventManager.put(
                new CommitKvSnapshotEvent(
                        new CommitKvSnapshotData(
                                completedSnapshot, coordinatorEpoch, invalidBucketLeaderEpoch),
                        responseCompletableFuture));
        assertThatThrownBy(responseCompletableFuture::get)
                .cause()
                .isInstanceOf(FencedLeaderEpochException.class);

        // invalid coordinator epoch
        int invalidCoordinatorEpoch = 1;
        completedSnapshot = KvTestUtils.mockCompletedSnapshot(tempDir, tableBucket, 2);
        responseCompletableFuture = new CompletableFuture<>();
        coordinatorEventManager.put(
                new CommitKvSnapshotEvent(
                        new CommitKvSnapshotData(
                                completedSnapshot, bucketLeaderEpoch, invalidCoordinatorEpoch),
                        responseCompletableFuture));
        assertThatThrownBy(responseCompletableFuture::get)
                .cause()
                .isInstanceOf(InvalidCoordinatorException.class);
    }

    @Test
    void testCreateAndDropPartition() throws Exception {
        CoordinatorContext coordinatorContext = eventProcessor.getCoordinatorContext();
        // make sure all request to gateway should be successful
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                eventProcessor.getCoordinatorContext(), testCoordinatorChannelManager);
        // create a partitioned table
        TablePath tablePath = TablePath.of(defaultDatabase, "partition_table");
        TableDescriptor tablePartitionTableDescriptor = getPartitionedTable();
        long tableId =
                metaDataManager.createTable(tablePath, tablePartitionTableDescriptor, null, false);

        retry(
                Duration.ofMinutes(1),
                // retry util the table has been put into context
                () -> assertThat(coordinatorContext.getTablePathById(tableId)).isNotNull());

        // create partition
        long partition1Id = zookeeperClient.getPartitionIdAndIncrement();
        long partition2Id = zookeeperClient.getPartitionIdAndIncrement();
        int nBuckets = 3;
        int replicationFactor = 3;
        String partition1Name = "2024";
        String partition2Name = "2025";
        Map<Integer, BucketAssignment> assignments =
                TableAssignmentUtils.generateAssignment(
                                nBuckets, replicationFactor, new int[] {0, 1, 2})
                        .getBucketAssignments();
        PartitionAssignment partitionAssignment = new PartitionAssignment(tableId, assignments);
        zookeeperClient.registerPartitionAssignment(partition1Id, partitionAssignment);
        zookeeperClient.registerPartitionAssignment(partition2Id, partitionAssignment);

        CoordinatorEvent createPartitionEvent =
                new CreatePartitionEvent(
                        tablePath, tableId, partition1Id, partition1Name, partitionAssignment);
        eventProcessor.process(createPartitionEvent);
        createPartitionEvent =
                new CreatePartitionEvent(
                        tablePath, tableId, partition2Id, partition2Name, partitionAssignment);
        eventProcessor.process(createPartitionEvent);
        verifyPartitionCreated(
                coordinatorContext,
                new TablePartition(tableId, partition1Id),
                partitionAssignment,
                nBuckets,
                replicationFactor);
        verifyPartitionCreated(
                coordinatorContext,
                new TablePartition(tableId, partition2Id),
                partitionAssignment,
                nBuckets,
                replicationFactor);

        // drop the partition
        DropPartitionEvent dropPartitionEvent = new DropPartitionEvent(tableId, partition1Id);
        eventProcessor.process(dropPartitionEvent);

        verifyPartitionDropped(coordinatorContext, tableId, partition1Id);

        // now, drop the table and restart the coordinator event processor,
        // the partition2 should be dropped
        eventProcessor.shutdown();
        metaDataManager.dropTable(tablePath, false);

        // start the coordinator
        eventProcessor =
                new CoordinatorEventProcessor(
                        zookeeperClient,
                        serverMetadataCache,
                        testCoordinatorChannelManager,
                        completedSnapshotStoreManager,
                        autoPartitionManager,
                        TestingMetricGroups.COORDINATOR_METRICS);
        CoordinatorTestUtils.makeSendLeaderAndStopRequestAlwaysSuccess(
                testCoordinatorChannelManager,
                Arrays.stream(zookeeperClient.getSortedTabletServerList())
                        .boxed()
                        .collect(Collectors.toSet()));
        eventProcessor.startup();
        verifyPartitionDropped(coordinatorContext, tableId, partition2Id);
    }

    // todo: add test resume drop partition

    private void verifyTableCreated(
            CoordinatorContext coordinatorContext,
            long tableId,
            TableAssignment tableAssignment,
            int nBuckets,
            int replicationFactor)
            throws Exception {
        int replicasCount = nBuckets * replicationFactor;
        // retry until the all replicas in t2 is online
        retry(
                Duration.ofMinutes(1),
                () -> {
                    // we use method replicaCounts instead of getAllReplicasForTable in here
                    // for use getAllReplicasForTable will cause ConcurrentModificationException
                    // in here
                    assertThat(replicaCounts(coordinatorContext, tableId)).isEqualTo(replicasCount);
                    assertThat(
                                    coordinatorContext.areAllReplicasInState(
                                            tableId, ReplicaState.OnlineReplica))
                            .isTrue();
                });
        // make sure all should be online
        retry(
                Duration.ofMinutes(1),
                () ->
                        verifyBucketForTableInState(
                                coordinatorContext, tableId, nBuckets, BucketState.OnlineBucket));
        retry(
                Duration.ofMinutes(1),
                () ->
                        verifyReplicaForTableInState(
                                coordinatorContext,
                                tableId,
                                nBuckets * replicationFactor,
                                ReplicaState.OnlineReplica));
        for (TableBucket tableBucket : coordinatorContext.getAllBucketsForTable(tableId)) {
            CoordinatorTestUtils.checkLeaderAndIsr(
                    zookeeperClient,
                    tableBucket,
                    0,
                    tableAssignment
                            .getBucketAssignment(tableBucket.getBucket())
                            .getReplicas()
                            .get(0));
        }
    }

    private void verifyPartitionCreated(
            CoordinatorContext coordinatorContext,
            TablePartition tablePartition,
            TableAssignment tableAssignment,
            int nBuckets,
            int replicationFactor)
            throws Exception {
        int replicasCount = nBuckets * replicationFactor;
        // retry until the all replicas in t2 is online
        retry(
                Duration.ofMinutes(1),
                () -> {
                    // we use method replicaCounts instead of getAllReplicasForTable in here
                    // for use getAllReplicasForTable will cause ConcurrentModificationException
                    // in here
                    assertThat(replicaCounts(coordinatorContext, tablePartition))
                            .isEqualTo(replicasCount);
                    assertThat(
                                    coordinatorContext.areAllReplicasInState(
                                            tablePartition, ReplicaState.OnlineReplica))
                            .isTrue();
                });
        // make sure all should be online
        retry(
                Duration.ofMinutes(1),
                () ->
                        verifyBucketForPartitionInState(
                                coordinatorContext,
                                tablePartition,
                                nBuckets,
                                BucketState.OnlineBucket));
        retry(
                Duration.ofMinutes(1),
                () ->
                        verifyReplicaForPartitionInState(
                                coordinatorContext,
                                tablePartition,
                                nBuckets * replicationFactor,
                                ReplicaState.OnlineReplica));
        for (TableBucket tableBucket :
                coordinatorContext.getAllBucketsForPartition(
                        tablePartition.getTableId(), tablePartition.getPartitionId())) {
            CoordinatorTestUtils.checkLeaderAndIsr(
                    zookeeperClient,
                    tableBucket,
                    0,
                    tableAssignment
                            .getBucketAssignment(tableBucket.getBucket())
                            .getReplicas()
                            .get(0));
        }
    }

    private void verifyTableDropped(CoordinatorContext coordinatorContext, long tableId) {
        // retry until the assignment has been deleted from zk, then it means
        // the table/partition has been deleted successfully
        retry(
                Duration.ofMinutes(1),
                () -> assertThat(zookeeperClient.getTableAssignment(tableId)).isEmpty());
        // no replica and bucket for the table/partition should exist in the context
        assertThat(coordinatorContext.getAllBucketsForTable(tableId)).isEmpty();
        assertThat(coordinatorContext.getAllReplicasForTable(tableId)).isEmpty();
    }

    private void verifyPartitionDropped(
            CoordinatorContext coordinatorContext, long tableId, long partitionId) {
        // retry until the assignment has been deleted from zk, then it means
        // the table/partition has been deleted successfully
        retry(
                Duration.ofMinutes(1),
                () -> assertThat(zookeeperClient.getPartitionAssignment(partitionId)).isEmpty());
        // no replica and bucket for the partition should exist in the context
        assertThat(coordinatorContext.getAllBucketsForPartition(tableId, partitionId)).isEmpty();
        assertThat(coordinatorContext.getAllReplicasForPartition(tableId, partitionId)).isEmpty();
        retry(
                Duration.ofMinutes(1),
                () -> assertThat(zookeeperClient.getPartitionAssignment(partitionId)).isEmpty());
    }

    private void verifyTabletServer(
            CoordinatorContext coordinatorContext,
            int serverId,
            TabletServerRegistration expectedServerRegistration) {
        ServerNode tabletServer = coordinatorContext.getLiveTabletServers().get(serverId);
        assertThat(tabletServer.id()).isEqualTo(serverId);
        assertThat(tabletServer.host()).isEqualTo(expectedServerRegistration.getHost());
        assertThat(tabletServer.port()).isEqualTo(expectedServerRegistration.getPort());
    }

    private void verifyReplicaOnlineOrOffline(
            CoordinatorContext coordinatorContext,
            long tableId,
            TableAssignment assignment,
            Set<Integer> expectedOfflineReplicas) {
        // iterate each bucket and the replicas
        assignment
                .getBucketAssignments()
                .forEach(
                        (bucketId, replicas) -> {
                            TableBucket bucket = new TableBucket(tableId, bucketId);
                            // iterate each replicas
                            for (Integer replica : replicas.getReplicas()) {
                                TableBucketReplica bucketReplica =
                                        new TableBucketReplica(bucket, replica);
                                // if expected to be offline
                                if (expectedOfflineReplicas.contains(replica)) {
                                    retry(
                                            Duration.ofMinutes(1),
                                            () ->
                                                    assertThat(
                                                                    coordinatorContext
                                                                            .getReplicaState(
                                                                                    bucketReplica))
                                                            .isEqualTo(OfflineReplica));

                                } else {
                                    // otherwise, should be online
                                    retry(
                                            Duration.ofMinutes(1),
                                            () -> {
                                                assertThat(
                                                                coordinatorContext.getReplicaState(
                                                                        bucketReplica))
                                                        .isEqualTo(OnlineReplica);
                                            });
                                }
                            }
                        });
    }

    private int replicaCounts(CoordinatorContext coordinatorContext, long tableId) {
        Map<Integer, List<Integer>> tableAssignments =
                new HashMap<>(coordinatorContext.getTableAssignment(tableId));
        return tableAssignments.values().stream().mapToInt(List::size).sum();
    }

    private int replicaCounts(
            CoordinatorContext coordinatorContext, TablePartition tablePartition) {
        Map<Integer, List<Integer>> tableAssignments =
                new HashMap<>(coordinatorContext.getPartitionAssignment(tablePartition));
        return tableAssignments.values().stream().mapToInt(List::size).sum();
    }

    private long createTable(TablePath tablePath, int[] servers) {
        TableAssignment tableAssignment =
                TableAssignmentUtils.generateAssignment(N_BUCKETS, REPLICATION_FACTOR, servers);
        return metaDataManager.createTable(
                tablePath, CoordinatorEventProcessorTest.TEST_TABLE, tableAssignment, false);
    }

    private TableDescriptor getPartitionedTable() {
        return TableDescriptor.builder()
                .schema(
                        Schema.newBuilder()
                                .column("a", DataTypes.INT())
                                .column("b", DataTypes.STRING())
                                .build())
                .distributedBy(3)
                .partitionedBy("a")
                .build();
    }
}
