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

package com.alibaba.fluss.client.write;

import com.alibaba.fluss.annotation.Internal;
import com.alibaba.fluss.client.lakehouse.LakeTableBucketAssigner;
import com.alibaba.fluss.client.metadata.MetadataUpdater;
import com.alibaba.fluss.client.metrics.WriterMetricGroup;
import com.alibaba.fluss.client.write.RecordAccumulator.RecordAppendResult;
import com.alibaba.fluss.cluster.Cluster;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.FlussRuntimeException;
import com.alibaba.fluss.exception.IllegalConfigurationException;
import com.alibaba.fluss.exception.RecordTooLargeException;
import com.alibaba.fluss.metadata.PhysicalTablePath;
import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.metadata.TableInfo;
import com.alibaba.fluss.rpc.gateway.TabletServerGateway;
import com.alibaba.fluss.rpc.metrics.ClientMetricGroup;
import com.alibaba.fluss.utils.CopyOnWriteMap;
import com.alibaba.fluss.utils.concurrent.ExecutorThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.alibaba.fluss.config.ConfigOptions.NoKeyAssigner.ROUND_ROBIN;
import static com.alibaba.fluss.config.ConfigOptions.NoKeyAssigner.STICKY;

/**
 * A client that write records to server.
 *
 * <p>The writer consists of a pool of buffer space that holds records that haven't yet been
 * transmitted to the tablet server as well as a background I/O thread that is responsible for
 * turning these records into requests and transmitting them to the cluster. Failure to close the
 * {@link WriterClient} after use will leak these resources.
 *
 * <p>The send method is asynchronous. When called, it adds the log record to a buffer of pending
 * record sends and immediately returns. This allows the wrote record to batch together individual
 * records for efficiency.
 */
@ThreadSafe
@Internal
public class WriterClient {
    private static final Logger LOG = LoggerFactory.getLogger(WriterClient.class);

    public static final String SENDER_THREAD_PREFIX = "fluss-write-sender";
    /**
     * {@link ConfigOptions#CLIENT_WRITER_MAX_INFLIGHT_REQUESTS_PER_BUCKET} should be less than or
     * equal to this value when idempotence producer enabled to ensure message ordering.
     */
    private static final int MAX_IN_FLIGHT_REQUESTS_PER_BUCKET_FOR_IDEMPOTENCE = 5;

    private final Configuration conf;
    private final int maxRequestSize;
    private final long totalMemorySize;
    private final RecordAccumulator accumulator;
    private final Sender sender;
    private final ExecutorService ioThreadPool;
    private final MetadataUpdater metadataUpdater;
    private final Map<PhysicalTablePath, BucketAssigner> bucketAssignerMap = new CopyOnWriteMap<>();
    private final IdempotenceManager idempotenceManager;
    private final WriterMetricGroup writerMetricGroup;

    public WriterClient(
            Configuration conf,
            MetadataUpdater metadataUpdater,
            ClientMetricGroup clientMetricGroup) {
        try {
            this.conf = conf;
            this.metadataUpdater = metadataUpdater;
            this.maxRequestSize =
                    (int) conf.get(ConfigOptions.CLIENT_WRITER_REQUEST_MAX_SIZE).getBytes();
            this.totalMemorySize =
                    conf.get(ConfigOptions.CLIENT_WRITER_BUFFER_MEMORY_SIZE).getBytes();
            this.idempotenceManager = buildIdempotenceManager();
            this.writerMetricGroup = new WriterMetricGroup(clientMetricGroup);

            short acks = configureAcks(idempotenceManager.idempotenceEnabled());
            int retries = configureRetries(idempotenceManager.idempotenceEnabled());
            this.accumulator = new RecordAccumulator(conf, idempotenceManager, writerMetricGroup);
            this.sender = newSender(acks, retries);
            this.ioThreadPool = createThreadPool();
            ioThreadPool.submit(sender);
        } catch (Throwable t) {
            close(Duration.ofMillis(0));
            throw new FlussRuntimeException("Failed to construct writer", t);
        }
    }

    /**
     * Asynchronously send a record to a table and invoke the provided callback when to send has
     * been acknowledged.
     */
    public void send(WriteRecord record, WriteCallback callback) {
        doSend(record, callback);
    }

    /**
     * Invoking this method makes all buffered records immediately available to send (even if <code>
     * linger.ms</code> is greater than 0) and blocks on the completion of the requests associated
     * with these records. The post-condition of <code>flush()</code> is that any previously sent
     * record will have completed (e.g. <code>Future.isDone() == true</code>). A request is
     * considered completed when it is successfully acknowledged according to the <code>acks</code>
     * configuration you have specified or else it results in an error.
     *
     * <p>Other threads can continue sending records while one thread is blocked waiting for a flush
     * call to complete, however no guarantee is made about the completion of records sent after the
     * flush call begins.
     */
    public void flush() {
        LOG.trace("Flushing accumulated records in writer.");
        long start = System.currentTimeMillis();
        accumulator.beginFlush();
        try {
            accumulator.awaitFlushCompletion();
        } catch (InterruptedException e) {
            throw new FlussRuntimeException("Flush interrupted." + e);
        }
        LOG.trace(
                "Flushed accumulated records in writer in {} ms.",
                System.currentTimeMillis() - start);
    }

    private void doSend(WriteRecord record, WriteCallback callback) {
        try {
            throwIfWriterClosed();

            ensureValidRecordSize(record.getEstimatedSizeInBytes());

            // maybe create bucket assigner.
            PhysicalTablePath physicalTablePath = record.getPhysicalTablePath();
            Cluster cluster = metadataUpdater.getCluster();
            BucketAssigner bucketAssigner =
                    bucketAssignerMap.computeIfAbsent(
                            physicalTablePath,
                            k -> createBucketAssigner(physicalTablePath, conf, cluster));

            // Append the record to the accumulator.
            int bucketId =
                    bucketAssigner.assignBucket(record.getBucketKey(), record.getRow(), cluster);
            RecordAppendResult result =
                    accumulator.append(
                            record, callback, cluster, bucketId, bucketAssigner.abortIfBatchFull());

            if (result.abortRecordForNewBatch) {
                int prevBucketId = bucketId;
                bucketAssigner.onNewBatch(cluster, prevBucketId);
                bucketId =
                        bucketAssigner.assignBucket(
                                record.getBucketKey(), record.getRow(), cluster);
                LOG.trace(
                        "Retrying append due to new batch creation for table {} bucket {}, the old bucket was {}.",
                        physicalTablePath,
                        bucketId,
                        prevBucketId);
                result = accumulator.append(record, callback, cluster, bucketId, false);
            }

            if (result.batchIsFull || result.newBatchCreated) {
                LOG.trace(
                        "Waking up the sender since table {} bucket {} is either full or getting a new batch",
                        record.getPhysicalTablePath(),
                        bucketId);
                // TODO add the wakeup logic refer to Kafka.
            }
        } catch (Exception e) {
            throw new FlussRuntimeException(e);
        }
    }

    /** Validate that the record size isn't too large. */
    private void ensureValidRecordSize(int size) {
        if (size > totalMemorySize) {
            throw new RecordTooLargeException(
                    "The message is "
                            + size
                            + " bytes when serialized which is larger than the total memory buffer "
                            + "you have configured with the "
                            + ConfigOptions.CLIENT_WRITER_BUFFER_MEMORY_SIZE.key()
                            + " configuration.");
        }
    }

    // Verify that writer instance has not been closed. This method throws IllegalStateException if
    // writer has already been closed.
    private void throwIfWriterClosed() {
        if (sender == null || !sender.isRunning()) {
            throw new IllegalStateException(
                    "Cannot perform operation after writer has been closed");
        }
    }

    private IdempotenceManager buildIdempotenceManager() {
        boolean idempotenceEnabled =
                conf.getBoolean(ConfigOptions.CLIENT_WRITER_ENABLE_IDEMPOTENCE);
        int maxInflightRequestPerBucket =
                conf.getInt(ConfigOptions.CLIENT_WRITER_MAX_INFLIGHT_REQUESTS_PER_BUCKET);
        if (idempotenceEnabled
                && maxInflightRequestPerBucket
                        > MAX_IN_FLIGHT_REQUESTS_PER_BUCKET_FOR_IDEMPOTENCE) {
            throw new IllegalConfigurationException(
                    "The value of "
                            + ConfigOptions.CLIENT_WRITER_MAX_INFLIGHT_REQUESTS_PER_BUCKET.key()
                            + " should be less than or equal to "
                            + MAX_IN_FLIGHT_REQUESTS_PER_BUCKET_FOR_IDEMPOTENCE
                            + " when idempotence writer enabled to ensure message ordering.");
        }

        TabletServerGateway tabletServerGateway = metadataUpdater.newRandomTabletServerClient();
        return idempotenceEnabled
                ? new IdempotenceManager(true, maxInflightRequestPerBucket, tabletServerGateway)
                : new IdempotenceManager(false, maxInflightRequestPerBucket, tabletServerGateway);
    }

    private short configureAcks(boolean idempotenceEnabled) {
        String acks = conf.get(ConfigOptions.CLIENT_WRITER_ACKS);
        short ack;
        if (acks.equals("all")) {
            ack = Short.parseShort("-1");
        } else {
            ack = Short.parseShort(acks);
        }

        if (idempotenceEnabled && ack != -1) {
            throw new IllegalConfigurationException(
                    "Must set "
                            + ConfigOptions.CLIENT_WRITER_ACKS.key()
                            + " to 'all' in order to use the idempotent writer. Otherwise "
                            + "we cannot guarantee idempotence.");
        }

        return ack;
    }

    private int configureRetries(boolean idempotenceEnabled) {
        int retries = conf.getInt(ConfigOptions.CLIENT_WRITER_RETRIES);
        if (idempotenceEnabled && retries == 0) {
            throw new IllegalConfigurationException(
                    "Must set "
                            + ConfigOptions.CLIENT_WRITER_RETRIES.key()
                            + " to non-zero when using the idempotent writer. Otherwise "
                            + "we cannot guarantee idempotence.");
        }
        return retries;
    }

    private Sender newSender(short acks, int retries) {
        return new Sender(
                accumulator,
                (int) conf.get(ConfigOptions.CLIENT_REQUEST_TIMEOUT).toMillis(),
                maxRequestSize,
                acks,
                retries,
                metadataUpdater,
                idempotenceManager,
                writerMetricGroup);
    }

    public void close(Duration timeout) {
        LOG.info("Closing writer.");

        writerMetricGroup.close();

        if (sender != null) {
            sender.initiateClose();
        }

        if (ioThreadPool != null) {
            ioThreadPool.shutdown();

            try {
                if (!ioThreadPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    ioThreadPool.shutdownNow();

                    if (!ioThreadPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        LOG.error("Failed to shutdown writer.");
                    }
                }
            } catch (InterruptedException e) {
                ioThreadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (sender != null) {
            sender.forceClose();
        }
        LOG.info("Writer closed.");
    }

    private ExecutorService createThreadPool() {
        return Executors.newFixedThreadPool(1, new ExecutorThreadFactory(SENDER_THREAD_PREFIX));
    }

    private BucketAssigner createBucketAssigner(
            PhysicalTablePath physicalTablePath, Configuration conf, Cluster cluster) {
        TableInfo tableInfo = cluster.getTableOrElseThrow(physicalTablePath.getTablePath());
        int bucketNumber = cluster.getBucketCount(physicalTablePath.getTablePath());
        TableDescriptor tableDescriptor = tableInfo.getTableDescriptor();
        List<String> bucketKeys = tableInfo.getTableDescriptor().getBucketKey();
        if (!bucketKeys.isEmpty()) {
            if (tableDescriptor.isDataLakeEnabled()) {
                // if lake is enabled, use lake table bucket assigner
                return new LakeTableBucketAssigner(tableDescriptor, bucketNumber);
            } else {
                return new HashBucketAssigner(bucketNumber);
            }
        } else {
            ConfigOptions.NoKeyAssigner noKeyAssigner =
                    conf.get(ConfigOptions.CLIENT_WRITER_BUCKET_NO_KEY_ASSIGNER);
            if (noKeyAssigner == ROUND_ROBIN) {
                return new RoundRobinBucketAssigner(physicalTablePath);
            } else if (noKeyAssigner == STICKY) {
                return new StickyBucketAssigner(physicalTablePath);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported append only row bucket assigner: " + noKeyAssigner);
            }
        }
    }
}
