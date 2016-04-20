/*
 * Copyright (c) 2015 WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.transport.http.netty.listener;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.Constants;
import org.wso2.carbon.transport.http.netty.NettyCarbonMessage;
import org.wso2.carbon.transport.http.netty.config.ListenerConfiguration;
import org.wso2.carbon.transport.http.netty.internal.NettyTransportContextHolder;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.util.concurrent.ExecutorService;

/**
 * A class that directly dispatch events to engine without going through worker pool.
 */
public class WorkerPoolDispatchingSourceHandler extends SourceHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoolDispatchingSourceHandler.class);

    private ListenerConfiguration listenerConfiguration;


    public WorkerPoolDispatchingSourceHandler(
               ConnectionManager connectionManager, ListenerConfiguration listenerConfiguration)
               throws Exception {
        super(connectionManager);
        this.listenerConfiguration = listenerConfiguration;


    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Start the server connection Timer
        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                   .executeAtSourceConnectionInitiation(Integer.toString(ctx.hashCode()));
        this.ctx = ctx;
        this.targetChannelPool = connectionManager.getTargetChannelPool();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {

            cMsg = (NettyCarbonMessage) setUPCarbonMessage(msg);
            cMsg.setProperty(org.wso2.carbon.transport.http.netty.common.Constants.IS_DISRUPTOR_ENABLE,
                             listenerConfiguration.getEnableDisruptor());
            cMsg.setProperty(org.wso2.carbon.transport.http.netty.common.Constants.EXECUTOR_WORKER_POOL_SIZE,
                             listenerConfiguration.getExecHandlerThreadPoolSize());


            CarbonCallback carbonCallback = (CarbonCallback) cMsg.getProperty(Constants.CALL_BACK);
            ExecutorService executorService = listenerConfiguration.getExecutorService();
            cMsg.setProperty(org.wso2.carbon.transport.http.netty.common.Constants.EXECUTOR_WORKER_POOL,
                             executorService);

            boolean continueRequest = NettyTransportContextHolder.getInstance().getHandlerExecutor()
                    .executeRequestContinuationValidator(cMsg, carbonMessage -> {
                        CarbonCallback responseCallback = (CarbonCallback) cMsg.getProperty(Constants.CALL_BACK);
                        responseCallback.done(carbonMessage);
                    });
            if (continueRequest) {
                executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            NettyTransportContextHolder.getInstance().getMessageProcessor()
                                    .receive(cMsg, carbonCallback);
                        } catch (Exception e) {
                            log.error("Error occured inside messaging engine", e);
                        }
                    }
                });
            }

        } else {
            if (cMsg != null) {
                if (msg instanceof HttpContent) {
                    HttpContent httpContent = (HttpContent) msg;
                    cMsg.addHttpContent(httpContent);
                    if (msg instanceof LastHttpContent) {
                        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                                   .executeAtSourceRequestSending(cMsg);
                        cMsg.setEndOfMsgAdded(true);
                    }
                }
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        NettyTransportContextHolder.getInstance().getHandlerExecutor()
                   .executeAtSourceConnectionTermination(Integer.toString(ctx.hashCode()));
        connectionManager.notifyChannelInactive();
    }
}