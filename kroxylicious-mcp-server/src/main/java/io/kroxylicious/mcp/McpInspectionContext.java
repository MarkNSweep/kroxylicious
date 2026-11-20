/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Inspection context providing runtime proxy state to the MCP server.
 */
public interface McpInspectionContext {

    /**
     * Gets current status and health of the proxy.
     *
     * @return map describing proxy status
     */
    Map<String, Object> getProxyStatus();

    /**
     * Gets current proxy configuration.
     *
     * @return current configuration object or map
     */
    Object getConfiguration();

    /**
     * Gets configured virtual clusters and their gateway/target details.
     *
     * @return list of virtual cluster descriptors
     */
    List<Map<String, Object>> getVirtualClusters();

    /**
     * Gets configured filter definitions.
     *
     * @return list of filter definitions
     */
    List<Map<String, Object>> getFilterDefinitions();

    /**
     * Gets the currently connected clients. Each entry holds the virtual cluster name, session id,
     * client id and the time the client's API_VERSIONS request was seen.
     *
     * @return list of connected client descriptors
     */
    List<Map<String, Object>> getConnectedClients();

    /**
     * Gets a summary of registered metrics: the meter count, the meter names, and for each meter
     * its type, description, base unit, series count, and its labels (each label name with the
     * sorted values currently registered for it).
     *
     * @return map summarizing proxy metrics
     */
    Map<String, Object> getMetricsSummary();

    /**
     * Gets the current values of the meters with the given names. Each entry describes one meter
     * (one name and tag combination) and holds its {@code name}, {@code type}, {@code tags} and
     * {@code measurements} (statistic name to current value).
     *
     * @param meterNames registry names of the meters to read
     * @return list of meter descriptors, empty if no matching meter has been registered yet
     */
    List<Map<String, Object>> getMetrics(Set<String> meterNames);

    /**
     * Gets the last 5 request/response exchanges for every connection made by the application identified by {@code clientId}.
     * Returns one entry per matching connection; each entry describes the session and carries its
     * ordered list of exchanges (oldest first).
     * <p>
     * When {@code clientId} is {@code null} or no connections match, an empty list is returned.
     *
     * @param clientId the Kafka client id to look up
     * @return list of session descriptors, one per matching connection, possibly empty
     */
    List<Map<String, Object>> getClientExchanges(@Nullable String clientId);
}
