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

package com.alibaba.fluss.server.zk.data;

import com.alibaba.fluss.metadata.PhysicalTablePath;
import com.alibaba.fluss.metadata.Schema;
import com.alibaba.fluss.metadata.TableBucket;
import com.alibaba.fluss.metadata.TablePartition;
import com.alibaba.fluss.metadata.TablePath;
import com.alibaba.fluss.utils.json.JsonSerdeUtils;

import javax.annotation.Nullable;

/** The data and path stored in ZooKeeper nodes (znodes). */
public final class ZkData {

    // ------------------------------------------------------------------------------------------
    // ZNodes under "/metadata/"
    // ------------------------------------------------------------------------------------------

    /**
     * The znode for databases. The znode path is:
     *
     * <p>/metadata/databases
     */
    public static final class DatabasesZNode {
        public static String path() {
            return "/metadata/databases";
        }
    }

    /**
     * The znode for a database. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]
     */
    public static final class DatabaseZNode {
        public static String path(String databaseName) {
            return DatabasesZNode.path() + "/" + databaseName;
        }
    }

    /**
     * The znode for tables. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]/tables
     */
    public static final class TablesZNode {
        public static String path(String databaseName) {
            return DatabaseZNode.path(databaseName) + "/tables";
        }
    }

    /**
     * The znode for a table which stores the logical metadata of a table: schema (id), properties,
     * buckets, etc. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]/tables/[tableName]
     */
    public static final class TableZNode {

        public static String path(TablePath tablePath) {
            return path(tablePath.getDatabaseName(), tablePath.getTableName());
        }

        public static String path(String databaseName, String tableName) {
            return TablesZNode.path(databaseName) + "/" + tableName;
        }

        /**
         * Extracts the database name and table name from the given zookeeper path. If the given
         * path is not a valid {@link TableZNode} path, returns null.
         */
        @Nullable
        public static TablePath parsePath(String zkPath) {
            String prefix = "/metadata/databases/";
            if (!zkPath.startsWith(prefix)) {
                return null;
            }
            String[] split = zkPath.substring(prefix.length()).split("/tables/");
            if (split.length != 2) {
                return null;
            }
            TablePath tablePath = TablePath.of(split[0], split[1]);
            return tablePath.isValid() ? tablePath : null;
        }

        public static byte[] encode(TableRegistration tableRegistration) {
            return JsonSerdeUtils.writeValueAsBytes(
                    tableRegistration, TableRegistrationJsonSerde.INSTANCE);
        }

        public static TableRegistration decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, TableRegistrationJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for schemas of a table. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]/tables/[tableName]/schemas
     */
    public static final class SchemasZNode {
        public static String path(TablePath tablePath) {
            return TableZNode.path(tablePath) + "/schemas";
        }
    }

    /**
     * The znode for a table which stores the schema information of a specific schema. The znode
     * path is:
     *
     * <p>/metadata/databases/[databaseName]/tables/[tableName]/schemas/[schemaId]
     */
    public static final class SchemaZNode {
        public static String path(TablePath tablePath, int schemaId) {
            return SchemasZNode.path(tablePath) + "/" + schemaId;
        }

        public static byte[] encode(Schema schema) {
            return schema.toJsonBytes();
        }

        public static Schema decode(byte[] json) {
            return Schema.fromJsonBytes(json);
        }
    }

    /**
     * The znode for a table which stores the partitions information. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]/tables/[tableName]/partitions
     */
    public static final class PartitionsZNode {

        public static String path(TablePath tablePath) {
            return TableZNode.path(tablePath) + "/partitions";
        }
    }

    /**
     * The znode for a partition of a table. The znode path is:
     *
     * <p>/metadata/databases/[databaseName]/tables/[tableName]/partitions/[partitionName]
     */
    public static final class PartitionZNode {

        /**
         * Extracts the table path and the partition name from the given zookeeper path. If the
         * given path is not a valid {@link PartitionZNode} path, returns null. The partition name
         * of the returned {@link PhysicalTablePath} is will never be null.
         */
        @Nullable
        public static PhysicalTablePath parsePath(String zkPath) {
            String[] split = zkPath.split("/partitions/");
            if (split.length != 2) {
                return null;
            }
            TablePath tablePath = TableZNode.parsePath(split[0]);
            if (tablePath == null) {
                return null;
            }
            String partitionName = split[1];
            PhysicalTablePath physicalTablePath = PhysicalTablePath.of(tablePath, partitionName);
            if (physicalTablePath.isValid()) {
                return physicalTablePath;
            } else {
                return null;
            }
        }

        public static String path(TablePath tablePath, String partitionName) {
            return PartitionsZNode.path(tablePath) + "/" + partitionName;
        }

        public static byte[] encode(TablePartition partition) {
            return partition.toJsonBytes();
        }

        public static TablePartition decode(byte[] json) {
            return TablePartition.fromJsonBytes(json);
        }
    }

    /**
     * The znode used to generate a sequence unique id for a table. The znode path is:
     *
     * <p>/metadata/table_seqid
     */
    public static final class TableSequenceIdZNode {
        public static String path() {
            return "/metadata/table_seqid";
        }
    }

    /**
     * The znode used to generate a sequence unique id for a partition. The znode path is:
     *
     * <p>/metadata/partition_seqid
     */
    public static final class PartitionSequenceIdZNode {
        public static String path() {
            return "/metadata/partition_seqid";
        }
    }

    /**
     * The znode used to generate a unique id for writer. The znode path is:
     *
     * <p>/metadata/writer_id
     */
    public static final class WriterIdZNode {
        public static String path() {
            return "/metadata/writer_id";
        }
    }

    // ------------------------------------------------------------------------------------------
    // ZNodes under "/coordinators/"
    // ------------------------------------------------------------------------------------------

    /**
     * The znode for the active coordinator. The znode path is:
     *
     * <p>/coordinators/active
     *
     * <p>Note: introduce standby coordinators in the future for znode "/coordinators/standby/".
     */
    public static final class CoordinatorZNode {
        public static String path() {
            return "/coordinators/active";
        }

        public static byte[] encode(CoordinatorAddress coordinatorAddress) {
            return JsonSerdeUtils.writeValueAsBytes(
                    coordinatorAddress, CoordinatorAddressJsonSerde.INSTANCE);
        }

        public static CoordinatorAddress decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, CoordinatorAddressJsonSerde.INSTANCE);
        }
    }

    // ------------------------------------------------------------------------------------------
    // ZNodes under "/tabletservers/"
    // ------------------------------------------------------------------------------------------

    /**
     * The znode can be used to generate a sequence unique id for a TabletServer. The znode path is:
     *
     * <p>/tabletservers/seqid
     */
    public static final class ServerSequenceIdZNode {
        public static String path() {
            return "/tabletservers/seqid";
        }
    }

    /**
     * The znode for TabletServers. The znode path is:
     *
     * <p>/tabletservers/ids
     */
    public static final class ServerIdsZNode {
        public static String path() {
            return "/tabletservers/ids";
        }
    }

    /**
     * The znode for a registered TabletServer information. The znode path is:
     *
     * <p>/tabletservers/ids/[serverId]
     */
    public static final class ServerIdZNode {
        public static String path(int serverId) {
            return ServerIdsZNode.path() + "/" + serverId;
        }

        public static byte[] encode(TabletServerRegistration tsRegistration) {
            return JsonSerdeUtils.writeValueAsBytes(
                    tsRegistration, TabletServerRegistrationJsonSerde.INSTANCE);
        }

        public static TabletServerRegistration decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, TabletServerRegistrationJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for table ids which list all the registered table ids. The znode path is:
     *
     * <p>/tabletservers/tables
     */
    public static final class TableIdsZNode {
        public static String path() {
            return "/tabletservers/tables";
        }
    }

    /**
     * The znode for table ids which list all the registered table ids. The znode path is:
     *
     * <p>/tabletservers/partitions
     */
    public static final class PartitionIdsZNode {
        public static String path() {
            return "/tabletservers/partitions";
        }
    }

    /**
     * The znode for a table id which stores the assignment and location of a table. The table id is
     * generated by {@link TableSequenceIdZNode} and is guaranteed to be unique in the cluster. The
     * znode path is:
     *
     * <p>/tabletservers/tables/[tableId]
     */
    public static final class TableIdZNode {
        public static String path(long tableId) {
            return TableIdsZNode.path() + "/" + tableId;
        }

        public static byte[] encode(TableAssignment tableAssignment) {
            return JsonSerdeUtils.writeValueAsBytes(
                    tableAssignment, TableAssignmentJsonSerde.INSTANCE);
        }

        public static TableAssignment decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, TableAssignmentJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for a partition id which stores the assignment and location of the partition. The
     * partition id is generated by {@link TableSequenceIdZNode} and is guaranteed to be unique in
     * the cluster. The znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]
     */
    public static final class PartitionIdZNode {
        public static String path(long partitionId) {
            return PartitionIdsZNode.path() + "/" + partitionId;
        }

        public static byte[] encode(PartitionAssignment tableAssignment) {
            return JsonSerdeUtils.writeValueAsBytes(
                    tableAssignment, PartitionAssignmentJsonSerde.INSTANCE);
        }

        public static PartitionAssignment decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, PartitionAssignmentJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for buckets of a table/partition.
     *
     * <p>For a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets
     *
     * <p>For a partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets
     */
    public static final class BucketIdsZNode {
        public static String pathOfTable(long tableId) {
            return TableIdZNode.path(tableId) + "/buckets";
        }

        public static String pathOfPartition(long partitionId) {
            return PartitionIdZNode.path(partitionId) + "/buckets";
        }

        public static String path(TableBucket tableBucket) {
            if (tableBucket.getPartitionId() != null) {
                return PartitionIdZNode.path(tableBucket.getPartitionId()) + "/buckets";
            } else {
                return TableIdZNode.path(tableBucket.getTableId()) + "/buckets";
            }
        }
    }

    /**
     * The znode for the leadership and isr information of a bucket of a table or partition.
     *
     * <p>For a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]/leader_isr
     *
     * <p>For a partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets/[bucketId]/leader_isr
     */
    public static final class LeaderAndIsrZNode {
        public static String path(TableBucket tableBucket) {
            return BucketIdZNode.path(tableBucket) + "/leader_isr";
        }

        public static byte[] encode(LeaderAndIsr leaderAndIsr) {
            return JsonSerdeUtils.writeValueAsBytes(leaderAndIsr, LeaderAndIsrJsonSerde.INSTANCE);
        }

        public static LeaderAndIsr decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, LeaderAndIsrJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for a bucket of a table or partition.
     *
     * <p>For a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]
     *
     * <p>For a partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets/[bucketId]
     */
    public static final class BucketIdZNode {
        public static String path(TableBucket tableBucket) {
            return BucketIdsZNode.path(tableBucket) + "/" + tableBucket.getBucket();
        }
    }

    /**
     * The znode for the snapshots of a bucket of a table or partition.
     *
     * <p>For a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]/snapshots
     *
     * <p>For a partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets/[bucketId]/snapshots
     */
    public static final class BucketSnapshotsZNode {
        public static String path(TableBucket tableBucket) {
            return BucketIdZNode.path(tableBucket) + "/snapshots";
        }
    }

    /**
     * The znode for the one snapshot of a bucket of a table or partition.
     *
     * <p>For a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]/snapshots/[snapshotId]
     *
     * <p>For a partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets/[bucketId]/snapshots/[snapshotId]
     */
    public static final class BucketSnapshotIdZNode {

        public static String path(TableBucket tableBucket, long snapshotId) {
            return BucketSnapshotsZNode.path(tableBucket) + "/" + snapshotId;
        }

        public static byte[] encode(BucketSnapshot bucketSnapshot) {
            return JsonSerdeUtils.writeValueAsBytes(
                    bucketSnapshot, BucketSnapshotJsonSerde.INSTANCE);
        }

        public static BucketSnapshot decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, BucketSnapshotJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode used to generate a sequence unique id for a snapshot of a table bucket.
     *
     * <p>For bucket belongs to a table, the znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]/snapshot_seqid
     *
     * <p>For bucket belongs to a table partition, the znode path is:
     *
     * <p>/tabletservers/partitions/[partitionId]/buckets/[bucketId]/snapshot_seqid
     */
    public static final class BucketSnapshotSequenceIdZNode {

        public static String path(TableBucket tableBucket) {
            return BucketIdZNode.path(tableBucket) + "/snapshot_seqid";
        }
    }

    /**
     * The znode for the remote logs' path of a table bucket. The znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/buckets/[bucketId]/remote_logs
     */
    public static final class BucketRemoteLogsZNode {
        public static String path(TableBucket tableBucket) {
            return BucketIdZNode.path(tableBucket) + "/remote_logs";
        }

        public static byte[] encode(RemoteLogManifestHandle remoteLogManifestHandle) {
            return JsonSerdeUtils.writeValueAsBytes(
                    remoteLogManifestHandle, RemoteLogManifestHandleJsonSerde.INSTANCE);
        }

        public static RemoteLogManifestHandle decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, RemoteLogManifestHandleJsonSerde.INSTANCE);
        }
    }

    /**
     * The znode for the info of lake data for a table. The znode path is:
     *
     * <p>/tabletservers/tables/[tableId]/laketable
     */
    public static final class LakeTableZNode {
        public static String path(long tableId) {
            return TableIdZNode.path(tableId) + "/laketable";
        }

        public static byte[] encode(LakeTableSnapshot lakeTableSnapshot) {
            return JsonSerdeUtils.writeValueAsBytes(
                    lakeTableSnapshot, LakeTableSnapshotJsonSerde.INSTANCE);
        }

        public static LakeTableSnapshot decode(byte[] json) {
            return JsonSerdeUtils.readValue(json, LakeTableSnapshotJsonSerde.INSTANCE);
        }
    }
}
