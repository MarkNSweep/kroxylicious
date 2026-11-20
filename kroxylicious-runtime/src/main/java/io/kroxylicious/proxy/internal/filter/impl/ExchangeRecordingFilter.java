/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter.impl;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.kroxylicious.kafka.common.message.ApiVersionsResponseData;
import io.kroxylicious.kafka.common.message.RequestHeaderData;
import io.kroxylicious.kafka.common.message.ResponseHeaderData;
import io.kroxylicious.kafka.common.message.SaslAuthenticateResponseData;
import io.kroxylicious.kafka.common.message.SaslHandshakeResponseData;
import io.kroxylicious.kafka.common.protocol.ApiKeys;
import io.kroxylicious.kafka.common.protocol.ApiMessage;
import io.kroxylicious.kafka.common.protocol.Errors;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilter;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.tag.ThreadSafe;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Records the last {@value #MAX_EXCHANGES} request/response exchanges per connection for MCP
 * inspection. Record-bearing APIs (Produce, Fetch) have their payload stripped; only metadata is
 * kept.
 *
 * <p>This filter is a singleton. The same instance is added to every connection's filter chain.
 * Per-session state is held in a {@link ConcurrentHashMap} keyed by session id; within a session
 * all access is on the single Netty event-loop thread so no additional locking is needed for the
 * per-session structures. Cross-session reads from the MCP tool are synchronised on the session
 * state's exchange deque.
 *
 * <p>The owner of the connection must call {@link #removeSession(String)} when the connection
 * closes.
 */
@ThreadSafe
public final class ExchangeRecordingFilter implements RequestFilter, ResponseFilter {

    /** Maximum exchanges kept per connection. */
    public static final int MAX_EXCHANGES = 5;

    /**
     * Kafka API keys whose messages carry record batches. For these APIs the record payload is
     * stripped from stored exchanges; only the metadata fields are kept.
     */
    private static final Set<ApiKeys> RECORD_BEARING_APIS = EnumSet.of(ApiKeys.PRODUCE, ApiKeys.FETCH);

    private static final ExchangeRecordingFilter INSTANCE = new ExchangeRecordingFilter();

    // -------------------------------------------------------------------------
    // Public data model
    // -------------------------------------------------------------------------

    /**
     * One completed request/response exchange.
     *
     * @param sessionId      session id of the connection
     * @param clientId       client id from the request header, or {@code null} if not set
     * @param virtualCluster virtual cluster the client connected to
     * @param apiKey         Kafka API key name
     * @param apiVersion     Kafka API version
     * @param correlationId  correlation id shared by the request and response
     * @param timestamp      when the request was first seen
     * @param requestFields  metadata fields from the request (record payloads are absent for record-bearing APIs)
     * @param responseFields metadata fields from the response, or {@code null} if the response has not been seen
     * @param recordsOmitted {@code true} when the API carries records and the payload was stripped
     */
    public record Exchange(
            String sessionId,
            @Nullable String clientId,
            String virtualCluster,
            String apiKey,
            short apiVersion,
            int correlationId,
            Instant timestamp,
            Map<String, Object> requestFields,
            @Nullable Map<String, Object> responseFields,
            boolean recordsOmitted) {
    }

    /**
     * A snapshot of one session's exchanges.
     *
     * @param sessionId      session id
     * @param virtualCluster virtual cluster name
     * @param clientId       client id, or {@code null} if none was seen
     * @param exchanges      ordered list of exchanges, oldest first
     */
    public record SessionSnapshot(
            String sessionId,
            String virtualCluster,
            @Nullable String clientId,
            List<Exchange> exchanges) {
    }

    // -------------------------------------------------------------------------
    // Per-session state
    // -------------------------------------------------------------------------

    private static final class SessionState {
        @Nullable
        String clientId;
        final String virtualCluster;
        /**
         * In-flight requests waiting for their response, keyed by correlationId.
         * Written and read only on the connection's event-loop thread — no synchronisation needed.
         */
        final Map<Integer, Exchange> pending = new HashMap<>();
        /**
         * Ring of completed exchanges. Access is guarded by this object's monitor because the MCP
         * tool reads it from a different thread.
         */
        final ArrayDeque<Exchange> exchanges = new ArrayDeque<>(MAX_EXCHANGES);

        SessionState(String virtualCluster) {
            this.virtualCluster = virtualCluster;
        }
    }

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    private ExchangeRecordingFilter() {
    }

    /**
     * Gets the singleton instance.
     *
     * @return the instance
     */
    public static ExchangeRecordingFilter instance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Filter interface
    // -------------------------------------------------------------------------

    @Override
    public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey,
                                                          short apiVersion,
                                                          RequestHeaderData header,
                                                          ApiMessage body,
                                                          FilterContext context) {
        String sessionId = context.sessionId();
        SessionState state = sessions.computeIfAbsent(sessionId,
                k -> new SessionState(context.getVirtualClusterName()));

        String clientId = header.clientId();
        if (state.clientId == null && clientId != null) {
            state.clientId = clientId;
        }

        boolean strip = RECORD_BEARING_APIS.contains(apiKey);
        Map<String, Object> requestFields = buildRequestFields(apiKey, apiVersion, header, body, strip);

        Exchange pending = new Exchange(
                sessionId,
                state.clientId,
                state.virtualCluster,
                apiKey.name(),
                apiVersion,
                header.correlationId(),
                Instant.now(),
                requestFields,
                null,
                strip);

        state.pending.put(header.correlationId(), pending);

        return context.forwardRequest(header, body);
    }

    @Override
    public CompletionStage<ResponseFilterResult> onResponse(ApiKeys apiKey,
                                                            short apiVersion,
                                                            ResponseHeaderData header,
                                                            ApiMessage body,
                                                            FilterContext context) {
        String sessionId = context.sessionId();
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            Exchange pending = state.pending.remove(header.correlationId());
            if (pending != null) {
                boolean strip = pending.recordsOmitted();
                Map<String, Object> responseFields = buildResponseFields(apiKey, apiVersion, header, body, strip);
                Exchange completed = new Exchange(
                        pending.sessionId(),
                        pending.clientId(),
                        pending.virtualCluster(),
                        pending.apiKey(),
                        pending.apiVersion(),
                        pending.correlationId(),
                        pending.timestamp(),
                        pending.requestFields(),
                        responseFields,
                        strip);
                synchronized (state.exchanges) {
                    if (state.exchanges.size() == MAX_EXCHANGES) {
                        state.exchanges.pollFirst();
                    }
                    state.exchanges.addLast(completed);
                }
            }
        }
        return context.forwardResponse(header, body);
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    /**
     * Removes all state for a session. Must be called when the connection closes.
     *
     * @param sessionId session id of the closed connection
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of all sessions whose client id matches {@code clientId}.
     *
     * @param clientId client id to look up
     * @return unmodifiable list of session snapshots, one per matching session
     */
    public List<SessionSnapshot> snapshotByClientId(String clientId) {
        List<SessionSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
            SessionState state = entry.getValue();
            if (clientId.equals(state.clientId)) {
                List<Exchange> copy;
                synchronized (state.exchanges) {
                    copy = List.copyOf(state.exchanges);
                }
                result.add(new SessionSnapshot(entry.getKey(), state.virtualCluster, state.clientId, copy));
            }
        }
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Field extraction helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildRequestFields(ApiKeys apiKey, short apiVersion,
                                                           RequestHeaderData header, ApiMessage body,
                                                           boolean strip) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("apiKey", apiKey.name());
        fields.put("apiVersion", (int) apiVersion);
        fields.put("correlationId", header.correlationId());
        fields.put("clientId", header.clientId());
        if (strip) {
            fields.put("recordsOmitted", true);
        }
        return Collections.unmodifiableMap(fields);
    }

    private static Map<String, Object> buildResponseFields(ApiKeys apiKey, short apiVersion,
                                                            ResponseHeaderData header, ApiMessage body,
                                                            boolean strip) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("apiKey", apiKey.name());
        fields.put("apiVersion", (int) apiVersion);
        fields.put("correlationId", header.correlationId());
        if (strip) {
            fields.put("recordsOmitted", true);
        }
        addErrorFields(fields, body);
        return Collections.unmodifiableMap(fields);
    }

    /**
     * Adds the error code and message of responses used during connection setup (ApiVersions and
     * SASL). SASL auth bytes are deliberately not recorded because they carry credential material.
     */
    private static void addErrorFields(Map<String, Object> fields, ApiMessage body) {
        if (body instanceof SaslAuthenticateResponseData sasl) {
            putErrorCode(fields, sasl.errorCode());
            fields.put("errorMessage", sasl.errorMessage());
        }
        else if (body instanceof SaslHandshakeResponseData handshake) {
            putErrorCode(fields, handshake.errorCode());
            fields.put("mechanisms", List.copyOf(handshake.mechanisms()));
        }
        else if (body instanceof ApiVersionsResponseData apiVersions) {
            putErrorCode(fields, apiVersions.errorCode());
        }
    }

    private static void putErrorCode(Map<String, Object> fields, short errorCode) {
        fields.put("errorCode", errorCode);
        fields.put("error", Errors.forCode(errorCode).name());
    }
}
