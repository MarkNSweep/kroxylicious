/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Netty inbound handler implementing the MCP Streamable HTTP transport ({@code POST /mcp}) and
 * the legacy HTTP with Server-Sent Events (SSE) transport ({@code GET /sse}, {@code POST /messages}).
 */
public class McpHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpHttpServerHandler.class);

    private final McpSessionManager sessionManager;
    private final McpProtocolHandler protocolHandler;

    /**
     * Creates an MCP HTTP server handler.
     *
     * @param sessionManager session manager
     * @param protocolHandler protocol handler
     */
    public McpHttpServerHandler(McpSessionManager sessionManager, McpProtocolHandler protocolHandler) {
        Objects.requireNonNull(sessionManager, "sessionManager");
        Objects.requireNonNull(protocolHandler, "protocolHandler");
        this.sessionManager = sessionManager;
        this.protocolHandler = protocolHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.OPTIONS.equals(req.method())) {
            handleOptions(ctx);
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        String path = decoder.path();

        if (path.equals("/mcp")) {
            handleStreamableHttp(ctx, req);
        }
        else if (HttpMethod.GET.equals(req.method()) && (path.equals("/sse") || path.equals("/mcp/sse"))) {
            handleSseConnect(ctx);
        }
        else if (HttpMethod.POST.equals(req.method()) && (path.equals("/messages") || path.equals("/message") || path.equals("/mcp/messages"))) {
            handlePostMessage(ctx, req, decoder);
        }
        else {
            sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
        }
    }

    /**
     * Streamable HTTP transport: each POST carries one JSON-RPC message and the reply is returned in the
     * response body. There is no server-initiated stream, so GET is not allowed.
     */
    private void handleStreamableHttp(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!HttpMethod.POST.equals(req.method())) {
            FullHttpResponse response = newResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "text/plain; charset=UTF-8");
            response.headers().set(HttpHeaderNames.ALLOW, "POST, OPTIONS");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }
        Optional<String> jsonRpcResponse = protocolHandler.handleMessage(req.content().toString(StandardCharsets.UTF_8));
        if (jsonRpcResponse.isPresent()) {
            ctx.writeAndFlush(newResponse(HttpResponseStatus.OK, jsonRpcResponse.get(), "application/json; charset=UTF-8"))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        else {
            sendResponse(ctx, HttpResponseStatus.ACCEPTED, "Accepted");
        }
    }

    private void handleOptions(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        addCorsHeaders(response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    private void handleSseConnect(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        addCorsHeaders(response);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

        String sessionId = sessionManager.createSession(ctx.channel());
        String endpointUri = "/messages?sessionId=" + sessionId;
        sessionManager.sendEvent(sessionId, "endpoint", endpointUri);

        LOGGER.atInfo()
                .addKeyValue("sessionId", sessionId)
                .log("Client connected to MCP SSE stream");
    }

    private void handlePostMessage(ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder decoder) {
        String sessionId = extractSessionId(decoder);
        if (sessionId == null) {
            sendResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Missing 'sessionId' query parameter");
            return;
        }

        if (!sessionManager.hasSession(sessionId)) {
            sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Session not found or expired: " + sessionId);
            return;
        }

        String body = req.content().toString(StandardCharsets.UTF_8);

        // Acknowledge HTTP POST
        sendResponse(ctx, HttpResponseStatus.ACCEPTED, "Accepted");

        // Process message and send response down SSE stream if applicable
        Optional<String> jsonRpcResponse = protocolHandler.handleMessage(body);
        jsonRpcResponse.ifPresent(resp -> sessionManager.sendEvent(sessionId, "message", resp));
    }

    @Nullable
    private String extractSessionId(QueryStringDecoder decoder) {
        var params = decoder.parameters().get("sessionId");
        if (params != null && !params.isEmpty()) {
            return params.get(0);
        }
        return null;
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        ctx.writeAndFlush(newResponse(status, message, "text/plain; charset=UTF-8")).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    private FullHttpResponse newResponse(HttpResponseStatus status, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        addCorsHeaders(response);
        return response;
    }

    private void addCorsHeaders(HttpResponse response) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, Accept");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sessionManager.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.atError()
                .setCause(cause)
                .log("Exception caught in MCP HTTP server handler");
        sessionManager.removeChannel(ctx.channel());
        ctx.close().addListener(ChannelFutureListener.CLOSE);
    }
}
