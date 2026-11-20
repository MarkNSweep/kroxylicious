/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;

import io.kroxylicious.mcp.config.McpConfiguration;
import io.kroxylicious.mcp.internal.McpChannelInitializer;
import io.kroxylicious.mcp.internal.McpProtocolHandler;
import io.kroxylicious.mcp.internal.McpSessionManager;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Netty-based Model Context Protocol (MCP) server for Kroxylicious.
 * Exposes inspection endpoints (tools, resources, prompts) over HTTP + Server-Sent Events (SSE).
 */
public class McpServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServer.class);

    private final McpConfiguration configuration;
    private final McpSessionManager sessionManager;
    private final McpProtocolHandler protocolHandler;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Class<? extends ServerChannel> channelClass;

    private @Nullable Channel serverChannel;

    /**
     * Creates an MCP server.
     *
     * @param configuration MCP configuration
     * @param inspectionContext proxy inspection context
     * @param bossGroup Netty boss event loop group
     * @param workerGroup Netty worker event loop group
     * @param channelClass Netty server channel class
     * @param objectMapper object mapper
     */
    public McpServer(McpConfiguration configuration,
                     McpInspectionContext inspectionContext,
                     EventLoopGroup bossGroup,
                     EventLoopGroup workerGroup,
                     Class<? extends ServerChannel> channelClass,
                     ObjectMapper objectMapper) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(inspectionContext, "inspectionContext");
        Objects.requireNonNull(bossGroup, "bossGroup");
        Objects.requireNonNull(workerGroup, "workerGroup");
        Objects.requireNonNull(channelClass, "channelClass");
        Objects.requireNonNull(objectMapper, "objectMapper");

        this.configuration = configuration;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.channelClass = channelClass;
        this.sessionManager = new McpSessionManager();
        this.protocolHandler = new McpProtocolHandler(inspectionContext, objectMapper);
    }

    /**
     * Creates an MCP server with a default ObjectMapper.
     *
     * @param configuration MCP configuration
     * @param inspectionContext proxy inspection context
     * @param bossGroup Netty boss event loop group
     * @param workerGroup Netty worker event loop group
     * @param channelClass Netty server channel class
     */
    public McpServer(McpConfiguration configuration,
                     McpInspectionContext inspectionContext,
                     EventLoopGroup bossGroup,
                     EventLoopGroup workerGroup,
                     Class<? extends ServerChannel> channelClass) {
        this(configuration, inspectionContext, bossGroup, workerGroup, channelClass, new ObjectMapper());
    }

    /**
     * Starts the MCP server and binds to the configured host and port.
     *
     * @return future that completes when the server is bound
     */
    public CompletableFuture<Void> startup() {
        var bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(channelClass)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new McpChannelInitializer(sessionManager, protocolHandler));

        String bindAddress = configuration.getEffectiveBindAddress();
        int port = configuration.getEffectivePort();

        LOGGER.atInfo()
                .addKeyValue("bindAddress", bindAddress)
                .addKeyValue("port", port)
                .log("Binding MCP server endpoint");

        var future = new CompletableFuture<Void>();
        bootstrap.bind(bindAddress, port).addListener((ChannelFutureListener) channelFuture -> ForkJoinPool.commonPool().execute(() -> {
            if (channelFuture.cause() != null) {
                future.completeExceptionally(channelFuture.cause());
            }
            else {
                this.serverChannel = channelFuture.channel();
                LOGGER.atInfo()
                        .addKeyValue("boundPort", getBoundPort())
                        .log("MCP server started successfully");
                future.complete(null);
            }
        }));

        return future;
    }

    /**
     * Gets the actual bound port (useful when configured with port 0).
     *
     * @return bound port
     * @throws IllegalStateException if server is not bound
     */
    public int getBoundPort() {
        if (serverChannel == null || !serverChannel.isActive()) {
            throw new IllegalStateException("MCP server channel is not active");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Shuts down the MCP server and closes active SSE sessions.
     *
     * @return future that completes when shutdown is done
     */
    public CompletableFuture<Void> shutdown() {
        var future = new CompletableFuture<Void>();
        sessionManager.close();
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().addListener((ChannelFutureListener) closeFuture -> {
                if (closeFuture.cause() != null) {
                    future.completeExceptionally(closeFuture.cause());
                }
                else {
                    future.complete(null);
                }
            });
        }
        else {
            future.complete(null);
        }
        return future;
    }

    @Override
    public void close() {
        shutdown().join();
    }
}
