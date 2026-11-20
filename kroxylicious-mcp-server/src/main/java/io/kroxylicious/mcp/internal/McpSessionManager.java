/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Manages active MCP Server-Sent Events (SSE) sessions and dispatches events.
 */
public class McpSessionManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSessionManager.class);

    private final ConcurrentMap<String, Channel> sessions = new ConcurrentHashMap<>();

    /**
     * Creates an empty MCP session manager.
     */
    public McpSessionManager() {
        // Intentionally empty
    }

    /**
     * Creates a new MCP session for the given SSE channel.
     *
     * @param sseChannel the active SSE channel
     * @return generated session identifier
     */
    public String createSession(Channel sseChannel) {
        Objects.requireNonNull(sseChannel, "sseChannel");
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, sseChannel);
        LOGGER.atDebug()
                .addKeyValue("sessionId", sessionId)
                .log("Created MCP SSE session");
        return sessionId;
    }

    /**
     * Checks if a session with the given ID exists and is active.
     *
     * @param sessionId the session identifier
     * @return true if the session exists and the channel is active
     */
    public boolean hasSession(@Nullable String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Channel channel = sessions.get(sessionId);
        return channel != null && channel.isActive();
    }

    /**
     * Gets the SSE channel for a session ID.
     *
     * @param sessionId the session ID
     * @return optional containing the active channel if present
     */
    public Optional<Channel> getChannel(@Nullable String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Channel channel = sessions.get(sessionId);
        if (channel != null && channel.isActive()) {
            return Optional.of(channel);
        }
        return Optional.empty();
    }

    /**
     * Removes a session by ID.
     *
     * @param sessionId the session ID to remove
     */
    public void removeSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Channel removed = sessions.remove(sessionId);
        if (removed != null) {
            LOGGER.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .log("Removed MCP SSE session");
        }
    }

    /**
     * Removes any session associated with the specified channel.
     *
     * @param channel the channel to remove
     */
    public void removeChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(channel)) {
                LOGGER.atDebug()
                        .addKeyValue("sessionId", entry.getKey())
                        .log("Removed MCP SSE session on channel close");
                return true;
            }
            return false;
        });
    }

    /**
     * Sends an SSE event to the specified session.
     *
     * @param sessionId the target session ID
     * @param eventName the SSE event name
     * @param data the payload data
     * @return true if the event was queued/sent, false if the session was not found or inactive
     */
    public boolean sendEvent(String sessionId, String eventName, String data) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(data, "data");

        Channel channel = sessions.get(sessionId);
        if (channel == null || !channel.isActive()) {
            return false;
        }

        String eventString = "event: " + eventName + "\r\ndata: " + data + "\r\n\r\n";
        channel.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(eventString, StandardCharsets.UTF_8)))
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        return true;
    }

    @Override
    public void close() {
        sessions.forEach((sessionId, channel) -> {
            if (channel.isActive()) {
                channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
            }
        });
        sessions.clear();
    }
}
