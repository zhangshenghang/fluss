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

package com.alibaba.fluss.exception;

import com.alibaba.fluss.annotation.PublicEvolving;

/**
 * Exception thrown when a resource is not found on the remote storage.
 *
 * <p>A resource can be a log segment, any of the indexes or any which was stored in remote storage
 * for a particular log segment.
 */
@PublicEvolving
public class RemoteResourceNotFoundException extends RemoteStorageException {
    private static final long serialVersionUID = 1L;

    public RemoteResourceNotFoundException(final String message) {
        super(message);
    }

    public RemoteResourceNotFoundException(final Throwable cause) {
        super("Requested remote resource was not found", cause);
    }

    public RemoteResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}