/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.mcp.McpInspectionContext;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Handles JSON-RPC 2.0 requests for the Model Context Protocol (MCP) inspection server.
 */
public class McpProtocolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpProtocolHandler.class);
    private static final String JSONRPC_VERSION = "2.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpInspectionContext inspectionContext;
    private final ObjectMapper objectMapper;

    /**
     * Creates a protocol handler with the given inspection context and object mapper.
     *
     * @param inspectionContext inspection context
     * @param objectMapper object mapper for JSON operations
     */
    public McpProtocolHandler(McpInspectionContext inspectionContext, ObjectMapper objectMapper) {
        Objects.requireNonNull(inspectionContext, "inspectionContext");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.inspectionContext = inspectionContext;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes an incoming JSON-RPC 2.0 payload string.
     *
     * @param jsonText the JSON request payload
     * @return optional containing the JSON-RPC response string, or empty if it is a notification
     */
    public Optional<String> handleMessage(String jsonText) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonText);
        }
        catch (JsonProcessingException e) {
            LOGGER.atWarn()
                    .setCause(e)
                    .log("Failed to parse JSON-RPC request");
            return Optional.of(buildErrorResponse(null, -32700, "Parse error: " + e.getMessage()));
        }

        if (!root.isObject()) {
            return Optional.of(buildErrorResponse(null, -32600, "Invalid Request: payload must be a JSON object"));
        }

        JsonNode idNode = root.get("id");
        JsonNode methodNode = root.get("method");

        if (methodNode == null || !methodNode.isTextual()) {
            return Optional.of(buildErrorResponse(idNode, -32600, "Invalid Request: 'method' field is required and must be a string"));
        }

        String method = methodNode.asText();
        JsonNode paramsNode = root.get("params");

        // Handle notifications (no id)
        if (idNode == null || idNode.isNull()) {
            handleNotification(method);
            return Optional.empty();
        }

        try {
            Object result = dispatchMethod(method, paramsNode);
            return Optional.of(buildSuccessResponse(idNode, result));
        }
        catch (McpMethodNotFoundException e) {
            return Optional.of(buildErrorResponse(idNode, -32601, "Method not found: " + method));
        }
        catch (McpInvalidParamsException e) {
            return Optional.of(buildErrorResponse(idNode, -32602, "Invalid params: " + e.getMessage()));
        }
        catch (Exception e) {
            LOGGER.atError()
                    .setCause(e)
                    .addKeyValue("method", method)
                    .log("Error processing MCP request");
            return Optional.of(buildErrorResponse(idNode, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private void handleNotification(String method) {
        LOGGER.atDebug()
                .addKeyValue("method", method)
                .log("Received MCP notification");
        // Handled notifications like notifications/initialized
    }

    private Object dispatchMethod(String method, @Nullable JsonNode params) throws Exception {
        return switch (method) {
            case "initialize" -> handleInitialize();
            case "ping" -> Map.of();
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            case "resources/list" -> handleResourcesList();
            case "resources/read" -> handleResourcesRead(params);
            case "prompts/list" -> handlePromptsList();
            case "prompts/get" -> handlePromptsGet(params);
            default -> throw new McpMethodNotFoundException(method);
        };
    }

    private Object handleInitialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false),
                        "resources", Map.of("subscribe", false, "listChanged", false),
                        "prompts", Map.of("listChanged", false)),
                "serverInfo", Map.of(
                        "name", "kroxylicious-mcp-server",
                        "version", "0.27.0-SNAPSHOT"));
    }

    private Object handleToolsList() {
        var tools = List.of(
                Map.of(
                        "name", "get_proxy_status",
                        "description", "Get current status, lifecycle state, and uptime of the Kroxylicious proxy.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of(
                        "name", "get_proxy_config",
                        "description", "Get current Kroxylicious proxy configuration.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of(
                        "name", "list_virtual_clusters",
                        "description", "List configured virtual clusters, gateways, and target clusters.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of(
                        "name", "list_filter_definitions",
                        "description", "List configured filter definitions and default filters.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of(
                        "name", "list_connected_clients",
                        "description", "List the client ids of currently connected clients, with the time each client's ApiVersions request was seen.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())),
                Map.of(
                        "name", "get_metrics_summary",
                        "description", "Get a summary of registered Micrometer metrics in the proxy.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())));
        return Map.of("tools", tools);
    }

    private Object handleToolsCall(@Nullable JsonNode params) throws Exception {
        if (params == null || !params.hasNonNull("name")) {
            throw new McpInvalidParamsException("'name' parameter is required for tools/call");
        }
        String toolName = params.get("name").asText();
        Object data = switch (toolName) {
            case "get_proxy_status" -> inspectionContext.getProxyStatus();
            case "get_proxy_config" -> inspectionContext.getConfiguration();
            case "list_virtual_clusters" -> inspectionContext.getVirtualClusters();
            case "list_filter_definitions" -> inspectionContext.getFilterDefinitions();
            case "list_connected_clients" -> inspectionContext.getConnectedClients();
            case "get_metrics_summary" -> inspectionContext.getMetricsSummary();
            default -> throw new McpInvalidParamsException("Unknown tool: " + toolName);
        };

        String textContent = data instanceof String s ? s : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", textContent)),
                "isError", false);
    }

    private Object handleResourcesList() {
        var resources = List.of(
                Map.of(
                        "uri", "kroxylicious://config",
                        "name", "Proxy Configuration",
                        "mimeType", "application/json",
                        "description", "The active Kroxylicious configuration"),
                Map.of(
                        "uri", "kroxylicious://status",
                        "name", "Proxy Status",
                        "mimeType", "application/json",
                        "description", "Current proxy runtime lifecycle state and stats"),
                Map.of(
                        "uri", "kroxylicious://virtual-clusters",
                        "name", "Virtual Clusters",
                        "mimeType", "application/json",
                        "description", "Configured virtual clusters and gateways"),
                Map.of(
                        "uri", "kroxylicious://filters",
                        "name", "Filter Definitions",
                        "mimeType", "application/json",
                        "description", "Configured filter definitions and defaults"),
                Map.of(
                        "uri", "kroxylicious://metrics",
                        "name", "Metrics Summary",
                        "mimeType", "application/json",
                        "description", "Summary of active proxy metrics"));
        return Map.of("resources", resources);
    }

    private Object handleResourcesRead(@Nullable JsonNode params) throws Exception {
        if (params == null || !params.hasNonNull("uri")) {
            throw new McpInvalidParamsException("'uri' parameter is required for resources/read");
        }
        String uri = params.get("uri").asText();
        Object data = switch (uri) {
            case "kroxylicious://config" -> inspectionContext.getConfiguration();
            case "kroxylicious://status" -> inspectionContext.getProxyStatus();
            case "kroxylicious://virtual-clusters" -> inspectionContext.getVirtualClusters();
            case "kroxylicious://filters" -> inspectionContext.getFilterDefinitions();
            case "kroxylicious://metrics" -> inspectionContext.getMetricsSummary();
            default -> throw new McpInvalidParamsException("Unknown resource URI: " + uri);
        };

        String textContent = data instanceof String s ? s : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        return Map.of(
                "contents", List.of(Map.of(
                        "uri", uri,
                        "mimeType", "application/json",
                        "text", textContent)));
    }

    private Object handlePromptsList() {
        var prompts = List.of(
                Map.of(
                        "name", "inspect-proxy",
                        "description", "Inspect and summarize Kroxylicious proxy configuration and status.",
                        "arguments", List.of()),
                Map.of(
                        "name", "diagnose-cluster",
                        "description", "Diagnose virtual cluster configuration and routing.",
                        "arguments", List.of(
                                Map.of(
                                        "name", "clusterName",
                                        "description", "Name of the virtual cluster to inspect",
                                        "required", true))));
        return Map.of("prompts", prompts);
    }

    private Object handlePromptsGet(@Nullable JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            throw new McpInvalidParamsException("'name' parameter is required for prompts/get");
        }
        String promptName = params.get("name").asText();
        return switch (promptName) {
            case "inspect-proxy" -> Map.of(
                    "description", "Inspect Kroxylicious proxy",
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", Map.of(
                                    "type", "text",
                                    "text", "Please inspect the Kroxylicious proxy configuration and summarize the virtual clusters, filters, and health status."))));
            case "diagnose-cluster" -> {
                String clusterName = params.hasNonNull("arguments") && params.get("arguments").hasNonNull("clusterName")
                        ? params.get("arguments").get("clusterName").asText()
                        : "<clusterName>";
                yield Map.of(
                        "description", "Diagnose virtual cluster: " + clusterName,
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", Map.of(
                                        "type", "text",
                                        "text", "Please diagnose configuration and connectivity for virtual cluster '" + clusterName + "'."))));
            }
            default -> throw new McpInvalidParamsException("Unknown prompt: " + promptName);
        };
    }

    private String buildSuccessResponse(JsonNode idNode, Object result) {
        Map<String, Object> response = Map.of(
                "jsonrpc", JSONRPC_VERSION,
                "id", idNode,
                "result", result);
        try {
            return objectMapper.writeValueAsString(response);
        }
        catch (JsonProcessingException e) {
            LOGGER.atError()
                    .setCause(e)
                    .log("Failed to serialize JSON-RPC response");
            return buildErrorResponse(idNode, -32603, "Failed to serialize response: " + e.getMessage());
        }
    }

    private String buildErrorResponse(@Nullable JsonNode idNode, int code, String message) {
        try {
            Map<String, Object> error = Map.of("code", code, "message", message);
            Map<String, Object> response = idNode != null && !idNode.isNull()
                    ? Map.of("jsonrpc", JSONRPC_VERSION, "id", idNode, "error", error)
                    : Map.of("jsonrpc", JSONRPC_VERSION, "error", error);
            return objectMapper.writeValueAsString(response);
        }
        catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}}";
        }
    }

    private static class McpMethodNotFoundException extends RuntimeException {
        McpMethodNotFoundException(String method) {
            super(method);
        }
    }

    private static class McpInvalidParamsException extends RuntimeException {
        McpInvalidParamsException(String message) {
            super(message);
        }
    }
}
