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

package com.alibaba.fluss.row.arrow;

import com.alibaba.fluss.annotation.Internal;
import com.alibaba.fluss.annotation.VisibleForTesting;
import com.alibaba.fluss.exception.FlussRuntimeException;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.memory.BufferAllocator;
import com.alibaba.fluss.shaded.arrow.org.apache.arrow.vector.VectorSchemaRoot;
import com.alibaba.fluss.types.RowType;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static com.alibaba.fluss.utils.concurrent.LockUtils.inLock;

/**
 * A pool that pools {@link ArrowWriter}. See Javadoc of {@link VectorSchemaRoot} for more
 * information about pooling.
 */
@ThreadSafe
@Internal
public class ArrowWriterPool implements ArrowWriterProvider {

    private final BufferAllocator allocator;

    @GuardedBy("lock")
    private final Map<String, Deque<ArrowWriter>> freeWriters;

    @GuardedBy("lock")
    private boolean closed = false;

    private final ReentrantLock lock = new ReentrantLock();

    public ArrowWriterPool(BufferAllocator allocator) {
        this.allocator = allocator;
        this.freeWriters = new HashMap<>();
    }

    @Override
    public void recycleWriter(ArrowWriter writer) {
        inLock(
                lock,
                () -> {
                    if (closed) {
                        // close the vector schema root in place
                        writer.root.close();
                    } else {
                        Deque<ArrowWriter> roots =
                                freeWriters.computeIfAbsent(
                                        writer.tableSchemaId, k -> new ArrayDeque<>());
                        writer.increaseEpoch();
                        roots.add(writer);
                    }
                });
    }

    @Override
    public ArrowWriter getOrCreateWriter(
            long tableId, int schemaId, int maxSizeInBytes, RowType schema) {
        final String tableSchemaId = tableId + "-" + schemaId;
        return inLock(
                lock,
                () -> {
                    if (closed) {
                        throw new FlussRuntimeException(
                                "Arrow VectorSchemaRoot pool closed while getting/creating root.");
                    }
                    Deque<ArrowWriter> writers = freeWriters.get(tableSchemaId);
                    if (writers != null && !writers.isEmpty()) {
                        return initialize(writers.pollFirst(), maxSizeInBytes);
                    } else {
                        return initialize(
                                new ArrowWriter(
                                        tableSchemaId, maxSizeInBytes, schema, allocator, this),
                                maxSizeInBytes);
                    }
                });
    }

    private ArrowWriter initialize(ArrowWriter writer, int maxSizeInBytes) {
        writer.reset(maxSizeInBytes);
        return writer;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            for (Deque<ArrowWriter> writers : freeWriters.values()) {
                for (ArrowWriter writer : writers) {
                    writer.root.close();
                }
            }
            freeWriters.clear();
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    public Map<String, Deque<ArrowWriter>> freeWriters() {
        return freeWriters;
    }
}
