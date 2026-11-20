/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kroxylicious.mcp.McpInspectionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpProtocolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpInspectionContext inspectionContext;
    private McpProtocolHandler protocolHandler;

    @BeforeEach
    void setUp() {
        inspectionContext = mock(McpInspectionContext.class);
        protocolHandler = new McpProtocolHandler(inspectionContext, objectMapper);
    }

    @Test
    void handleInitialize() throws Exception {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test-client", "version": "1.0.0"}
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(responseNode.get("id").asInt()).isEqualTo(1);
        assertThat(responseNode.get("result").get("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(responseNode.get("result").get("serverInfo").get("name").asText()).isEqualTo("kroxylicious-mcp-server");
        assertThat(responseNode.get("result").get("capabilities").has("tools")).isTrue();
        assertThat(responseNode.get("result").get("capabilities").has("resources")).isTrue();
        assertThat(responseNode.get("result").get("capabilities").has("prompts")).isTrue();
    }

    @Test
    void handlePing() throws Exception {
        String request = """
                {"jsonrpc": "2.0", "id": 2, "method": "ping"}
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.get("id").asInt()).isEqualTo(2);
        assertThat(responseNode.get("result").isObject()).isTrue();
    }

    @Test
    void handleNotificationDoesNotReturnResponse() {
        String notification = """
                {"jsonrpc": "2.0", "method": "notifications/initialized"}
                """;

        var responseOpt = protocolHandler.handleMessage(notification);
        assertThat(responseOpt).isEmpty();
    }

    @Test
    void handleToolsList() throws Exception {
        String request = """
                {"jsonrpc": "2.0", "id": 3, "method": "tools/list"}
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        JsonNode tools = responseNode.get("result").get("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).extracting(t -> t.get("name").asText())
                .containsExactlyInAnyOrder("get_proxy_status", "get_proxy_config", "list_virtual_clusters", "list_filter_definitions", "list_connected_clients",
                        "get_metrics_summary");
    }

    @Test
    void handleToolsCallConnectedClients() throws Exception {
        when(inspectionContext.getConnectedClients()).thenReturn(List.of(
                Map.of("virtualCluster", "my-cluster", "sessionId", "s1", "clientId", "my-app", "timestamp", "2026-01-01T00:00:00Z")));

        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 11,
                  "method": "tools/call",
                  "params": {
                    "name": "list_connected_clients",
                    "arguments": {}
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.get("result").get("isError").asBoolean()).isFalse();
        assertThat(responseNode.get("result").get("content").get(0).get("text").asText()).contains("my-app", "2026-01-01T00:00:00Z");
    }

    @Test
    void handleToolsCallStatus() throws Exception {
        when(inspectionContext.getProxyStatus()).thenReturn(Map.of("state", "STARTED", "uptimeMillis", 12345L));

        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "tools/call",
                  "params": {
                    "name": "get_proxy_status",
                    "arguments": {}
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.get("result").get("isError").asBoolean()).isFalse();
        JsonNode content = responseNode.get("result").get("content");
        assertThat(content.get(0).get("text").asText()).contains("STARTED");
    }

    @Test
    void handleToolsCallVirtualClusters() throws Exception {
        when(inspectionContext.getVirtualClusters()).thenReturn(List.of(Map.of("clusterName", "my-cluster", "targetCluster", "target-cluster")));

        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 5,
                  "method": "tools/call",
                  "params": {
                    "name": "list_virtual_clusters",
                    "arguments": {}
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.get("result").get("content").get(0).get("text").asText()).contains("my-cluster");
    }

    @Test
    void handleResourcesList() throws Exception {
        String request = """
                {"jsonrpc": "2.0", "id": 6, "method": "resources/list"}
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        JsonNode resources = responseNode.get("result").get("resources");
        assertThat(resources.isArray()).isTrue();
        assertThat(resources).extracting(r -> r.get("uri").asText())
                .contains("kroxylicious://config", "kroxylicious://status", "kroxylicious://virtual-clusters", "kroxylicious://filters", "kroxylicious://metrics");
    }

    @Test
    void handleResourcesRead() throws Exception {
        when(inspectionContext.getConfiguration()).thenReturn(Map.of("virtualClusters", List.of()));

        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "resources/read",
                  "params": {
                    "uri": "kroxylicious://config"
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        JsonNode contents = responseNode.get("result").get("contents");
        assertThat(contents.get(0).get("uri").asText()).isEqualTo("kroxylicious://config");
        assertThat(contents.get(0).get("mimeType").asText()).isEqualTo("application/json");
    }

    @Test
    void handlePromptsList() throws Exception {
        String request = """
                {"jsonrpc": "2.0", "id": 8, "method": "prompts/list"}
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        JsonNode prompts = responseNode.get("result").get("prompts");
        assertThat(prompts).extracting(p -> p.get("name").asText())
                .contains("inspect-proxy", "diagnose-cluster");
    }

    @Test
    void handlePromptsGet() throws Exception {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 9,
                  "method": "prompts/get",
                  "params": {
                    "name": "diagnose-cluster",
                    "arguments": {
                      "clusterName": "demo-cluster"
                    }
                  }
                }
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        JsonNode messages = responseNode.get("result").get("messages");
        assertThat(messages.get(0).get("content").get("text").asText()).contains("demo-cluster");
    }

    @Test
    void handleUnknownMethod() throws Exception {
        String request = """
                {"jsonrpc": "2.0", "id": 10, "method": "unknown/method"}
                """;

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.has("error")).isTrue();
        assertThat(responseNode.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void handleMalformedJson() throws Exception {
        String request = "not json";

        var responseOpt = protocolHandler.handleMessage(request);
        assertThat(responseOpt).isPresent();

        JsonNode responseNode = objectMapper.readTree(responseOpt.get());
        assertThat(responseNode.has("error")).isTrue();
        assertThat(responseNode.get("error").get("code").asInt()).isEqualTo(-32700);
    }
}
