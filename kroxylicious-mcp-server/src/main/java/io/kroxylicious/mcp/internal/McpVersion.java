/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.mcp.internal;

/**
 * Provides the version of the MCP server.
 * The version comes from the {@code Implementation-Version} entry in the jar manifest,
 * which the build sets to the project version.
 */
public final class McpVersion {

    private static final String UNKNOWN = "unknown";
    private static final String VERSION = lookupVersion();

    private McpVersion() {
    }

    /**
     * Returns the MCP server version, or {@code unknown} if it cannot be determined
     * (for example when running from a class directory rather than a jar).
     *
     * @return the version
     */
    public static String get() {
        return VERSION;
    }

    private static String lookupVersion() {
        String version = McpVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : UNKNOWN;
    }
}
