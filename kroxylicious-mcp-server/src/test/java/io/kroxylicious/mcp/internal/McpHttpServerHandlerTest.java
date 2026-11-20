/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import io.kroxylicious.mcp.McpInspectionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpHttpServerHandlerTest {

    private McpSessionManager sessionManager;
    private McpProtocolHandler protocolHandler;
    private McpInspectionContext inspectionContext;
    private EmbeddedChannel sseChannel;

    @BeforeEach
    void setUp() {
        sessionManager = new McpSessionManager();
        inspectionContext = mock(McpInspectionContext.class);
        protocolHandler = new McpProtocolHandler(inspectionContext, new ObjectMapper());
        sseChannel = new EmbeddedChannel(new McpHttpServerHandler(sessionManager, protocolHandler));
    }

    @AfterEach
    void tearDown() {
        sessionManager.close();
        if (sseChannel != null) {
            sseChannel.finishAndReleaseAll();
        }
    }

    @Test
    void getSseConnectsAndSendsEndpointEvent() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/sse");
        sseChannel.writeInbound(req);

        HttpResponse httpResponse = sseChannel.readOutbound();
        assertThat(httpResponse).isNotNull();
        assertThat(httpResponse.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(httpResponse.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/event-stream; charset=UTF-8");

        HttpContent sseContent = sseChannel.readOutbound();
        assertThat(sseContent).isNotNull();
        String payload = sseContent.content().toString(StandardCharsets.UTF_8);
        assertThat(payload).contains("event: endpoint\r\ndata: /messages?sessionId=");
    }

    @Test
    void postMessageWithoutSessionReturnsBadRequest() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/messages");
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    }

    @Test
    void postMessageWithUnknownSessionReturnsNotFound() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/messages?sessionId=non-existent");
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }

    @Test
    void postMessageDispatchesAndSendsResponseToSseChannel() {
        // Connect SSE first
        var sseReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/sse");
        sseChannel.writeInbound(sseReq);

        sseChannel.readOutbound(); // consume HttpResponse
        HttpContent sseContent = sseChannel.readOutbound();
        String payload = sseContent.content().toString(StandardCharsets.UTF_8);
        String sessionId = payload.substring(payload.indexOf("sessionId=") + "sessionId=".length()).trim();

        when(inspectionContext.getProxyStatus()).thenReturn(Map.of("state", "RUNNING"));

        // Post tool call request on a separate channel (simulating client POST connection)
        var postChannel = new EmbeddedChannel(new McpHttpServerHandler(sessionManager, protocolHandler));
        String rpcJson = """
                {
                  "jsonrpc": "2.0",
                  "id": "req-1",
                  "method": "tools/call",
                  "params": {
                    "name": "get_proxy_status",
                    "arguments": {}
                  }
                }
                """;
        var postReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/messages?sessionId=" + sessionId,
                Unpooled.wrappedBuffer(rpcJson.getBytes(StandardCharsets.UTF_8)));
        postChannel.writeInbound(postReq);

        FullHttpResponse postResponse = postChannel.readOutbound();
        assertThat(postResponse.status()).isEqualTo(HttpResponseStatus.ACCEPTED);

        // Verify response was sent down the SSE channel
        HttpContent sseResponse = sseChannel.readOutbound();
        assertThat(sseResponse).isNotNull();
        String sseData = sseResponse.content().toString(StandardCharsets.UTF_8);
        assertThat(sseData).contains("event: message");
        assertThat(sseData).contains("RUNNING");
        assertThat(sseData).contains("req-1");
        postChannel.finishAndReleaseAll();
    }

    @Test
    void postToMcpReturnsJsonRpcResponseInBody() {
        when(inspectionContext.getProxyStatus()).thenReturn(Map.of("state", "RUNNING"));
        String rpcJson = """
                {"jsonrpc": "2.0", "id": "req-2", "method": "tools/call", "params": {"name": "get_proxy_status", "arguments": {}}}
                """;
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp",
                Unpooled.wrappedBuffer(rpcJson.getBytes(StandardCharsets.UTF_8)));
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/json; charset=UTF-8");
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertThat(body).contains("req-2", "RUNNING");
        response.release();
    }

    @Test
    void postNotificationToMcpReturnsAccepted() {
        String rpcJson = """
                {"jsonrpc": "2.0", "method": "notifications/initialized"}
                """;
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp",
                Unpooled.wrappedBuffer(rpcJson.getBytes(StandardCharsets.UTF_8)));
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.ACCEPTED);
        response.release();
    }

    @Test
    void getToMcpReturnsMethodNotAllowed() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.METHOD_NOT_ALLOWED);
        assertThat(response.headers().get(HttpHeaderNames.ALLOW)).isEqualTo("POST, OPTIONS");
        response.release();
    }

    @Test
    void optionsReturnsOkWithCorsHeaders() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/sse");
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
    }

    @Test
    void unknownPathReturnsNotFound() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/unknown");
        sseChannel.writeInbound(req);

        FullHttpResponse response = sseChannel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
    }
}
