/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.util.Set;

/**
 * The proxy metric categories documented in the user guide, each exposed as one MCP tool.
 * Meter names are the Micrometer registry names, which differ from the Prometheus names
 * (for example Prometheus adds {@code _total} to counters and {@code _bytes} to size summaries).
 */
enum McpMetricCategory {

    CONNECTION(
            "get_connection_metrics",
            "Get current values of the proxy connection metrics: client-to-proxy and proxy-to-server connection counts, "
                    + "active connection gauges, errors and client disconnects by cause.",
            Set.of(
                    "kroxylicious_client_to_proxy_connections",
                    "kroxylicious_client_to_proxy_errors",
                    "kroxylicious_client_to_proxy_active_connections",
                    "kroxylicious_client_to_proxy_disconnects",
                    "kroxylicious_proxy_to_server_connections",
                    "kroxylicious_proxy_to_server_errors",
                    "kroxylicious_proxy_to_server_active_connections")),

    MESSAGE(
            "get_message_metrics",
            "Get current values of the proxy message metrics: Kafka request and response counts and size distributions, "
                    + "on both the downstream (client) and upstream (broker) sides.",
            Set.of(
                    "kroxylicious_client_to_proxy_request",
                    "kroxylicious_proxy_to_server_request",
                    "kroxylicious_server_to_proxy_response",
                    "kroxylicious_proxy_to_client_response",
                    "kroxylicious_client_to_proxy_request_size",
                    "kroxylicious_proxy_to_server_request_size",
                    "kroxylicious_server_to_proxy_response_size",
                    "kroxylicious_proxy_to_client_response_size")),

    RECONFIGURATION(
            "get_reconfiguration_metrics",
            "Get current values of the proxy reconfiguration metrics: reconfiguration outcomes, duration, "
                    + "and per-virtual-cluster operation outcomes.",
            Set.of(
                    "kroxylicious_reconfigure_total",
                    "kroxylicious_reconfigure_duration_seconds",
                    "kroxylicious_reconfigure_clusters_affected_total")),

    VIRTUAL_CLUSTER_LIFECYCLE(
            "get_virtual_cluster_lifecycle_metrics",
            "Get current values of the virtual cluster lifecycle metrics: current state of each virtual cluster, "
                    + "time spent in each state, and state transition counts.",
            Set.of(
                    "kroxylicious_virtual_cluster_state",
                    "kroxylicious_virtual_cluster_state_duration_seconds",
                    "kroxylicious_virtual_cluster_transitions_total")),

    AUTHENTICATION(
            "get_authentication_metrics",
            "Get current values of the client authentication metrics: authentication outcomes by virtual cluster and mechanism.",
            Set.of("kroxylicious_client_auth_total"));

    private final String toolName;
    private final String description;
    private final Set<String> meterNames;

    McpMetricCategory(String toolName, String description, Set<String> meterNames) {
        this.toolName = toolName;
        this.description = description;
        this.meterNames = meterNames;
    }

    String toolName() {
        return toolName;
    }

    String description() {
        return description;
    }

    Set<String> meterNames() {
        return meterNames;
    }
}
