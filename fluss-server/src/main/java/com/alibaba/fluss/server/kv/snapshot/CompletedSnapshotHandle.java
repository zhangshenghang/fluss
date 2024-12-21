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

package com.alibaba.fluss.server.kv.snapshot;

import com.alibaba.fluss.fs.FSDataInputStream;
import com.alibaba.fluss.fs.FileSystem;
import com.alibaba.fluss.fs.FsPath;
import com.alibaba.fluss.utils.IOUtils;
import com.alibaba.fluss.utils.Preconditions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/* This file is based on source code of Apache Flink Project (https://flink.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * A handle to a completed snapshot which contains the metadata file path to the completed snapshot.
 * It is as a wrapper around a {@link CompletedSnapshot} to make the referenced completed snapshot
 * retrievable through a simple get call.
 */
public class CompletedSnapshotHandle {

    private final FsPath metadataFilePath;

    public CompletedSnapshotHandle(FsPath metadataFilePath) {
        Preconditions.checkNotNull(metadataFilePath);
        this.metadataFilePath = metadataFilePath;
    }

    /**
     * Creates a {@link CompletedSnapshotHandle} from a given metadata file path. The metadata file
     * path must be a valid {@link FsPath} URI string.
     */
    public static CompletedSnapshotHandle fromMetadataPath(String metadataFilePath) {
        return new CompletedSnapshotHandle(new FsPath(metadataFilePath));
    }

    public CompletedSnapshot retrieveCompleteSnapshot() throws IOException {
        FSDataInputStream in = openInputStream();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        IOUtils.copyBytes(in, outputStream);
        return CompletedSnapshotJsonSerde.fromJson(outputStream.toByteArray());
    }

    public FSDataInputStream openInputStream() throws IOException {
        return metadataFilePath.getFileSystem().open(metadataFilePath);
    }

    /**
     * Gets the file system that stores the file state.
     *
     * @return The file system that stores the file state.
     * @throws IOException Thrown if the file system cannot be accessed.
     */
    private FileSystem getFileSystem() throws IOException {
        return FileSystem.get(metadataFilePath.toUri());
    }

    public FsPath getMetadataFilePath() {
        return metadataFilePath;
    }

    public void discard() throws Exception {
        final FileSystem fs = getFileSystem();

        IOException actualException = null;
        boolean success = true;
        try {
            success = fs.delete(metadataFilePath, false);
        } catch (IOException e) {
            actualException = e;
        }

        if (!success || actualException != null) {
            if (fs.exists(metadataFilePath)) {
                throw Optional.ofNullable(actualException)
                        .orElse(
                                new IOException(
                                        "Unknown error caused the file '"
                                                + metadataFilePath
                                                + "' to not be deleted."));
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CompletedSnapshotHandle that = (CompletedSnapshotHandle) o;
        return Objects.equals(metadataFilePath, that.metadataFilePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadataFilePath);
    }

    @Override
    public String toString() {
        return "CompletedSnapshotHandle{" + "filePath=" + metadataFilePath + '}';
    }
}
