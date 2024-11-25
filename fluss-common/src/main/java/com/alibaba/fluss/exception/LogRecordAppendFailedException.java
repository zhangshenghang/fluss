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

import com.alibaba.fluss.annotation.Internal;

/** Log record append failed exception. */
@Internal
public class LogRecordAppendFailedException extends FlussRuntimeException {

    public LogRecordAppendFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public LogRecordAppendFailedException(String message) {
        super(message);
    }

    public LogRecordAppendFailedException(Throwable cause) {
        super(cause);
    }
}