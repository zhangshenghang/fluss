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

package com.alibaba.fluss.utils;

import com.alibaba.fluss.annotation.Internal;

import javax.annotation.Nullable;

import static java.lang.String.format;

/** Utilities for working with {@link WrappingProxy}. */
@Internal
public final class WrappingProxyUtils {

    static final int SAFETY_NET_MAX_ITERATIONS = 128;

    private WrappingProxyUtils() {
        throw new AssertionError();
    }

    /**
     * Expects a proxy, and returns the unproxied delegate.
     *
     * @param wrappingProxy The initial proxy.
     * @param <T> The type of the delegate. Note that all proxies in the chain must be assignable to
     *     T.
     * @return The unproxied delegate.
     */
    @SuppressWarnings("unchecked")
    public static <T> T stripProxy(@Nullable final WrappingProxy<T> wrappingProxy) {
        if (wrappingProxy == null) {
            return null;
        }

        T delegate = wrappingProxy.getWrappedDelegate();

        int numProxiesStripped = 0;
        while (delegate instanceof WrappingProxy) {
            throwIfSafetyNetExceeded(++numProxiesStripped);
            delegate = ((WrappingProxy<T>) delegate).getWrappedDelegate();
        }

        return delegate;
    }

    private static void throwIfSafetyNetExceeded(final int numProxiesStripped) {
        if (numProxiesStripped >= SAFETY_NET_MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    format(
                            "Already stripped %d proxies. "
                                    + "Are there loops in the object graph?",
                            SAFETY_NET_MAX_ITERATIONS));
        }
    }
}
