/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;

import static org.assertj.core.api.Assertions.assertThat;

class McpSessionManagerTest {

    private McpSessionManager sessionManager;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        sessionManager = new McpSessionManager();
        channel = new EmbeddedChannel();
    }

    @AfterEach
    void tearDown() {
        sessionManager.close();
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void createAndRetrieveSession() {
        String sessionId = sessionManager.createSession(channel);
        assertThat(sessionId).isNotNull().isNotEmpty();
        assertThat(sessionManager.hasSession(sessionId)).isTrue();
        assertThat(sessionManager.getChannel(sessionId)).contains(channel);
    }

    @Test
    void removeSessionById() {
        String sessionId = sessionManager.createSession(channel);
        sessionManager.removeSession(sessionId);
        assertThat(sessionManager.hasSession(sessionId)).isFalse();
    }

    @Test
    void removeSessionByChannel() {
        String sessionId = sessionManager.createSession(channel);
        sessionManager.removeChannel(channel);
        assertThat(sessionManager.hasSession(sessionId)).isFalse();
    }

    @Test
    void sendEventToActiveSession() {
        String sessionId = sessionManager.createSession(channel);
        boolean sent = sessionManager.sendEvent(sessionId, "endpoint", "/messages?sessionId=" + sessionId);
        assertThat(sent).isTrue();
        Object msg = channel.readOutbound();
        assertThat(msg).isNotNull();
    }

    @Test
    void sendEventToUnknownSessionReturnsFalse() {
        boolean sent = sessionManager.sendEvent("unknown-id", "endpoint", "/messages");
        assertThat(sent).isFalse();
    }
}
