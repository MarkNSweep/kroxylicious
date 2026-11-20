/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigurationTest {

    @Test
    void defaultConfigurationValues() {
        var config = new McpConfiguration(null, null);
        assertThat(config.getEffectivePort()).isEqualTo(McpConfiguration.DEFAULT_MCP_PORT);
        assertThat(config.getEffectiveBindAddress()).isEqualTo(McpConfiguration.DEFAULT_BIND_ADDRESS);
    }

    @Test
    void customConfigurationValues() {
        var config = new McpConfiguration("127.0.0.1", 9999);
        assertThat(config.getEffectivePort()).isEqualTo(9999);
        assertThat(config.getEffectiveBindAddress()).isEqualTo("127.0.0.1");
    }
}
