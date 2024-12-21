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

package com.alibaba.fluss.rpc.protocol;

import com.alibaba.fluss.rpc.messages.FetchLogRequest;
import com.alibaba.fluss.rpc.messages.GetTableRequest;
import com.alibaba.fluss.rpc.netty.server.RequestChannel;
import com.alibaba.fluss.rpc.netty.server.RpcRequest;
import com.alibaba.fluss.shaded.netty4.io.netty.buffer.EmptyByteBuf;
import com.alibaba.fluss.shaded.netty4.io.netty.buffer.UnpooledByteBufAllocator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The test for {@link RequestChannel}. */
public class RequestChannelTest {

    @Test
    void testRequestPriority() throws Exception {
        RequestChannel channel = new RequestChannel(100);

        // 1. request with same priority score. Use FIFO.
        List<RpcRequest> rpcRequests = new ArrayList<>();
        // push rpc requests
        for (int i = 0; i < 100; i++) {
            RpcRequest rpcRequest =
                    new RpcRequest(
                            ApiKeys.GET_TABLE.id,
                            (short) 0,
                            i,
                            null,
                            new GetTableRequest(),
                            new EmptyByteBuf(new UnpooledByteBufAllocator(true, true)),
                            null);
            channel.putRequest(rpcRequest);
            rpcRequests.add(rpcRequest);
        }
        // pop rpc requests
        for (int i = 0; i < 100; i++) {
            RpcRequest gotRequest = channel.pollRequest(100);
            assertThat(gotRequest).isEqualTo(rpcRequests.get(i));
        }

        // 2. request with different priority score. Should be ordered by priority score.
        RpcRequest rpcRequest1 =
                new RpcRequest(
                        ApiKeys.GET_TABLE.id,
                        (short) 0,
                        3,
                        null,
                        new GetTableRequest(),
                        new EmptyByteBuf(new UnpooledByteBufAllocator(true, true)),
                        null);
        RpcRequest rpcRequest2 =
                new RpcRequest(
                        ApiKeys.FETCH_LOG.id,
                        (short) 0,
                        100,
                        null,
                        new FetchLogRequest().setMaxBytes(100).setFollowerServerId(2),
                        new EmptyByteBuf(new UnpooledByteBufAllocator(true, true)),
                        null);
        channel.putRequest(rpcRequest1);
        channel.putRequest(rpcRequest2);
        RpcRequest rpcRequest = channel.pollRequest(100);
        assertThat(rpcRequest).isEqualTo(rpcRequest2);
        rpcRequest = channel.pollRequest(100);
        assertThat(rpcRequest).isEqualTo(rpcRequest1);
    }
}
