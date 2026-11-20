/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter.impl;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.kroxylicious.kafka.common.message.ApiVersionsRequestData;
import io.kroxylicious.kafka.common.message.RequestHeaderData;
import io.kroxylicious.kafka.common.protocol.ApiKeys;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientIdRecordingFilterTest {

    private static final String SESSION_1 = "client-id-recording-test-session-1";
    private static final String SESSION_2 = "client-id-recording-test-session-2";

    @Mock
    private FilterContext context;

    // the filter is a singleton, so tests must remove what they record
    private final ClientIdRecordingFilter filter = ClientIdRecordingFilter.instance();
    private final ApiVersionsRequestData request = new ApiVersionsRequestData();

    @BeforeEach
    void setUp() {
        when(context.forwardRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void tearDown() {
        filter.remove(SESSION_1);
        filter.remove(SESSION_2);
    }

    @Test
    void recordsClientIdAndTimestamp() {
        // given
        when(context.sessionId()).thenReturn(SESSION_1);
        when(context.getVirtualClusterName()).thenReturn("my-cluster");
        var before = Instant.now();

        // when
        CompletionStage<RequestFilterResult> result = filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header("my-app"), request, context);

        // then
        assertThat(result).isCompleted();
        assertThat(filter.connectedClients())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.sessionId()).isEqualTo(SESSION_1);
                    assertThat(r.virtualCluster()).isEqualTo("my-cluster");
                    assertThat(r.clientId()).isEqualTo("my-app");
                    assertThat(r.timestamp()).isBetween(before, Instant.now());
                });
    }

    @Test
    void doesNotRecordNullClientId() {
        // when
        filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header(null), request, context);

        // then
        assertThat(filter.connectedClients()).isEmpty();
    }

    @Test
    void recordsEachConnection() {
        // given
        when(context.sessionId()).thenReturn(SESSION_1, SESSION_2);
        when(context.getVirtualClusterName()).thenReturn("my-cluster");

        // when
        filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header("app-a"), request, context);
        filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header("app-b"), request, context);

        // then
        assertThat(filter.connectedClients()).extracting(ClientIdRecordingFilter.ClientRecord::clientId)
                .containsExactlyInAnyOrder("app-a", "app-b");
    }

    @Test
    void removeDeletesTheConnectionsRecord() {
        // given
        when(context.sessionId()).thenReturn(SESSION_1, SESSION_2);
        when(context.getVirtualClusterName()).thenReturn("my-cluster");
        filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header("app-a"), request, context);
        filter.onApiVersionsRequest(ApiKeys.API_VERSIONS.latestVersion(), header("app-b"), request, context);

        // when
        filter.remove(SESSION_1);

        // then
        assertThat(filter.connectedClients()).extracting(ClientIdRecordingFilter.ClientRecord::clientId)
                .containsExactly("app-b");
    }

    private static RequestHeaderData header(String clientId) {
        return new RequestHeaderData().setClientId(clientId);
    }
}
