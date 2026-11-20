/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * Netty channel initializer configuring HTTP and SSE codecs and the MCP handler.
 */
public class McpChannelInitializer extends ChannelInitializer<Channel> {

    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    private final McpSessionManager sessionManager;
    private final McpProtocolHandler protocolHandler;

    /**
     * Creates an MCP channel initializer.
     *
     * @param sessionManager session manager
     * @param protocolHandler protocol handler
     */
    public McpChannelInitializer(McpSessionManager sessionManager, McpProtocolHandler protocolHandler) {
        Objects.requireNonNull(sessionManager, "sessionManager");
        Objects.requireNonNull(protocolHandler, "protocolHandler");
        this.sessionManager = sessionManager;
        this.protocolHandler = protocolHandler;
    }

    @Override
    protected void initChannel(Channel ch) {
        ch.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH))
                .addLast("mcpHandler", new McpHttpServerHandler(sessionManager, protocolHandler));
    }
}
