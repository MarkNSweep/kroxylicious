/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import io.kroxylicious.mcp.config.McpConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServerTest {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private McpInspectionContext inspectionContext;
    private McpServer server;

    @BeforeEach
    void setUp() {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        inspectionContext = mock(McpInspectionContext.class);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown().join();
        }
        bossGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Test
    void serverStartupAndShutdown() throws Exception {
        var config = new McpConfiguration("127.0.0.1", 0);
        server = new McpServer(config, inspectionContext, bossGroup, workerGroup, NioServerSocketChannel.class);
        server.startup().join();

        int port = server.getBoundPort();
        assertThat(port).isPositive();

        // Connect via HTTP client
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest optionsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/sse"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> optionsResp = client.send(optionsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(optionsResp.statusCode()).isEqualTo(200);
        assertThat(optionsResp.headers().firstValue("access-control-allow-origin")).contains("*");

        server.shutdown().join();
    }

    @Test
    void endToEndMcpFlow() throws Exception {
        when(inspectionContext.getProxyStatus()).thenReturn(Map.of("state", "RUNNING"));
        when(inspectionContext.getVirtualClusters()).thenReturn(List.of(Map.of("name", "test-cluster")));

        var config = new McpConfiguration("127.0.0.1", 0);
        server = new McpServer(config, inspectionContext, bossGroup, workerGroup, NioServerSocketChannel.class);
        server.startup().join();

        int port = server.getBoundPort();

        HttpClient client = HttpClient.newHttpClient();

        // 1. Initiate SSE connection in background or stream
        // Testing POST without session first
        HttpRequest postBad = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/messages"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> badResp = client.send(postBad, HttpResponse.BodyHandlers.ofString());
        assertThat(badResp.statusCode()).isEqualTo(400);
    }
}
