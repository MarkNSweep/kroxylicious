/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp;

import java.util.List;
import java.util.Map;

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
     * Gets a summary of registered metrics.
     *
     * @return map summarizing proxy metrics
     */
    Map<String, Object> getMetricsSummary();
}
