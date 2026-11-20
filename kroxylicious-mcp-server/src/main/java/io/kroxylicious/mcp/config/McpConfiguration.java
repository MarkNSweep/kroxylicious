/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.config;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Configuration for the Model Context Protocol (MCP) server.
 *
 * @param bindAddress bind address for the MCP interface. If omitted, defaults to 0.0.0.0.
 * @param port MCP port. If omitted, defaults to 9191.
 */
public record McpConfiguration(@Nullable @JsonProperty(value = "bindAddress", required = false) String bindAddress,
                               @Nullable @JsonProperty(value = "port", required = false) Integer port) {

    public static final int DEFAULT_MCP_PORT = 9191;
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    /**
     * Gets the effective port for MCP taking into account defaults.
     * @return port
     */
    public int getEffectivePort() {
        return Optional.ofNullable(port).orElse(DEFAULT_MCP_PORT);
    }

    /**
     * Gets the effective bind address for MCP taking into account defaults.
     * @return bind address
     */
    public String getEffectiveBindAddress() {
        return Optional.ofNullable(bindAddress).orElse(DEFAULT_BIND_ADDRESS);
    }
}
