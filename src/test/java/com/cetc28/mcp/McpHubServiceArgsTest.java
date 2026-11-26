package com.cetc28.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHubServiceArgsTest {

    @Test
    void splitsPlainArguments() {
        assertEquals(List.of("npx", "-y", "@modelcontextprotocol/server-everything"),
                McpHubService.splitArgs("npx -y @modelcontextprotocol/server-everything"));
    }

    @Test
    void keepsQuotedArgumentsTogether() {
        assertEquals(List.of("a b", "c"), McpHubService.splitArgs("\"a b\" c"));
    }

    @Test
    void handlesBlank() {
        assertTrue(McpHubService.splitArgs(null).isEmpty());
        assertTrue(McpHubService.splitArgs("   ").isEmpty());
    }
}
