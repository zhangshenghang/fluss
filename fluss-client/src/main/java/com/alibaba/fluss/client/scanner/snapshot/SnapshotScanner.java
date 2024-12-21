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

package com.alibaba.fluss.client.scanner.snapshot;

import com.alibaba.fluss.annotation.PublicEvolving;
import com.alibaba.fluss.client.scanner.RemoteFileDownloader;
import com.alibaba.fluss.client.scanner.ScanRecord;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.FlussRuntimeException;
import com.alibaba.fluss.metadata.KvFormat;
import com.alibaba.fluss.utils.CloseableIterator;
import com.alibaba.fluss.utils.CloseableRegistry;
import com.alibaba.fluss.utils.FileUtils;
import com.alibaba.fluss.utils.IOUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static com.alibaba.fluss.utils.concurrent.LockUtils.inLock;

/**
 * A scanner to scan the snapshot data described by {@link SnapshotScan}.
 *
 * <p>When the scanner is created, It will download the given snapshot files from the remote storage
 * and create a reader to read the files into records asynchronously.
 *
 * <p>In the {@link SnapshotScanner#poll(Duration)} method:
 *
 * <ul>
 *   <li>if the reader is not ready in given time, return an empty iterator
 *   <li>If the reader is ready, always return the reader if there remains any data in the reader.
 *       Otherwise, return null
 * </ul>
 *
 * @since 0.1
 */
@PublicEvolving
public class SnapshotScanner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotScanner.class);

    public static final CloseableIterator<ScanRecord> NO_DATA_AVAILABLE =
            CloseableIterator.emptyIterator();

    private final Path snapshotLocalDirectory;
    private final RemoteFileDownloader remoteFileDownloader;
    private final KvFormat kvFormat;
    private final SnapshotScan snapshotScan;

    private final ReentrantLock lock = new ReentrantLock();

    @GuardedBy("lock")
    private final Condition readerIsReady = lock.newCondition();

    private final AtomicBoolean closed;

    private volatile SnapshotFilesReader snapshotFilesReader;

    @Nullable private volatile Throwable initSnapshotFilesReaderException = null;

    public SnapshotScanner(
            Configuration conf,
            KvFormat kvFormat,
            RemoteFileDownloader remoteFileDownloader,
            SnapshotScan snapshotScan) {
        this.kvFormat = kvFormat;
        // create a directory to store the snapshot files
        this.snapshotLocalDirectory =
                Paths.get(
                        conf.getString(ConfigOptions.CLIENT_SCANNER_IO_TMP_DIR),
                        String.format("kv-snapshots-%s", UUID.randomUUID()));
        this.remoteFileDownloader = remoteFileDownloader;
        this.snapshotScan = snapshotScan;
        this.closed = new AtomicBoolean(false);
        initReaderAsynchronously();
    }

    /**
     * Fetch data from snapshot.
     *
     * <p>If the snapshot file reader is not ready in given maximum block time, return an empty
     * iterator. If the reader is ready, always return the reader if there remains any record in the
     * reader, otherwise, return null.
     *
     * @param timeout The maximum time to block (must not be greater than {@link Long#MAX_VALUE}
     *     milliseconds)
     */
    @Nullable
    public CloseableIterator<ScanRecord> poll(Duration timeout) {
        // note: we don't throw exception if the scanner is closed since in flink access pattern,
        // the scanner will be closed by source reader thread after finished reading all records,
        // but the fetcher thread may still calling poll method
        ensureNoException();
        return inLock(
                lock,
                () -> {
                    try {
                        if (snapshotFilesReader == null) {
                            // wait for the reader to be ready,
                            if (!readerIsReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                                // reader is still not ready
                                return NO_DATA_AVAILABLE;
                            }
                        }
                        return snapshotFilesReader.hasNext()
                                ? CloseableIterator.wrap(snapshotFilesReader)
                                : null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new FlussRuntimeException(
                                "Interrupted when waiting for snapshot files reader.", e);
                    }
                });
    }

    /**
     * Checks whether the scanner is at its end.
     *
     * @return True, if the scanner has read all the records in the snapshot data or closed, false
     *     otherwise.
     */
    public boolean reachedEnd() {
        // if closed, return true directly
        if (closed.get()) {
            return true;
        }
        final SnapshotFilesReader reader = snapshotFilesReader;
        return reader != null && !reader.hasNext();
    }

    /**
     * Ensure that the scanner hasn't encountered any exception.
     *
     * @throws FlussRuntimeException If any exception has been thrown during snapshot reader
     *     initialization
     */
    private void ensureNoException() {
        if (initSnapshotFilesReaderException != null) {
            throw new FlussRuntimeException(
                    "Failed to initialize snapshot files reader.",
                    initSnapshotFilesReaderException);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            IOUtils.closeQuietly(snapshotFilesReader);
            FileUtils.deleteDirectoryQuietly(snapshotLocalDirectory.toFile());
        }
    }

    private void initReaderAsynchronously() {
        CompletableFuture.runAsync(
                () ->
                        inLock(
                                lock,
                                () -> {
                                    CloseableRegistry closeableRegistry = new CloseableRegistry();
                                    try {
                                        if (!snapshotLocalDirectory.toFile().mkdirs()) {
                                            throw new IOException(
                                                    String.format(
                                                            "Failed to create directory %s for storing kv snapshot files.",
                                                            snapshotLocalDirectory));
                                        }
                                        closeableRegistry.registerCloseable(
                                                () ->
                                                        FileUtils.deleteDirectoryQuietly(
                                                                snapshotLocalDirectory.toFile()));
                                        // todo: refactor transferAllToDirectory method to
                                        // return a future so that we won't need to runAsync using
                                        // the default thread pool
                                        LOG.info(
                                                "Start to download kv snapshot files to local directory for bucket {}.",
                                                snapshotScan.getTableBucket());
                                        remoteFileDownloader.transferAllToDirectory(
                                                snapshotScan.getFsPathAndFileNames(),
                                                snapshotLocalDirectory,
                                                closeableRegistry);
                                        snapshotFilesReader =
                                                new SnapshotFilesReader(
                                                        kvFormat,
                                                        snapshotLocalDirectory,
                                                        snapshotScan.getTableSchema(),
                                                        snapshotScan.getProjectedFields());
                                        readerIsReady.signalAll();
                                    } catch (Throwable e) {
                                        IOUtils.closeQuietly(closeableRegistry);
                                        initSnapshotFilesReaderException = e;
                                    } finally {
                                        IOUtils.closeQuietly(closeableRegistry);
                                    }
                                }));
    }
}
