/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.kroxylicious.kafka.common.message.ApiVersionsRequestData;
import io.kroxylicious.kafka.common.message.RequestHeaderData;
import io.kroxylicious.proxy.filter.ApiVersionsRequestFilter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.tag.ThreadSafe;

/**
 * Records the client id and the time of each connection's API_VERSIONS request.
 * <p>
 * This filter is a singleton. The same instance is added to every connection, in every virtual cluster,
 * so it is invoked by many threads at once. It holds a single map of records, keyed by session id.
 * The owner of the connection must call {@link #remove(String)} when the connection closes.
 * </p>
 * <p>
 * No other request type is recorded. Requests with no client id are not recorded.
 * </p>
 */
@ThreadSafe
public final class ClientIdRecordingFilter implements ApiVersionsRequestFilter {

    private static final ClientIdRecordingFilter INSTANCE = new ClientIdRecordingFilter();

    /**
     * A recorded client.
     *
     * @param sessionId the session id of the client's connection
     * @param virtualCluster the name of the virtual cluster the client connected to
     * @param clientId the client id sent in the request header
     * @param timestamp when the API_VERSIONS request was seen
     */
    public record ClientRecord(String sessionId, String virtualCluster, String clientId, Instant timestamp) {
        /**
         * Creates a record.
         *
         * @param sessionId the session id of the client's connection
         * @param virtualCluster the name of the virtual cluster the client connected to
         * @param clientId the client id sent in the request header
         * @param timestamp when the API_VERSIONS request was seen
         */
        public ClientRecord {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(virtualCluster, "virtualCluster");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    private final ConcurrentMap<String, ClientRecord> records = new ConcurrentHashMap<>();

    private ClientIdRecordingFilter() {
    }

    /**
     * Gets the singleton instance.
     *
     * @return the instance
     */
    public static ClientIdRecordingFilter instance() {
        return INSTANCE;
    }

    /**
     * Gets a snapshot of the recorded clients.
     *
     * @return an immutable copy of the current records
     */
    public List<ClientRecord> connectedClients() {
        return List.copyOf(records.values());
    }

    /**
     * Removes the record for a connection. Does nothing if there is none.
     *
     * @param sessionId the session id of the connection that closed
     */
    public void remove(String sessionId) {
        records.remove(sessionId);
    }

    @Override
    public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                     FilterContext context) {
        String clientId = header.clientId();
        if (clientId != null) {
            String sessionId = context.sessionId();
            records.put(sessionId, new ClientRecord(sessionId, context.getVirtualClusterName(), clientId, Instant.now()));
        }
        return context.forwardRequest(header, request);
    }
}
