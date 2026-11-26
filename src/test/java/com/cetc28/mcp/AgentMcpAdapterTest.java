package com.cetc28.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命名空间回归测试：共享 Toolkit 里是原始工具名，Agent 工具集里是带前缀的视图。
 * 启动能力清单 / 本地工具过滤都必须用 {@link AgentMcpAdapter#prefixOf} 的结果，
 * 否则两边交集恒为空（历史 bug：启动日志永远显示 MCP工具"（无）"）。
 */
class AgentMcpAdapterTest {

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
                .name(name)
                .description("desc")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                .build();
    }

    private static McpClientWrapper wrapper(String serverId, String... toolNames) {
        return new McpClientWrapper(serverId) {
            @Override
            public Mono<Void> initialize() {
                initialized = true;
                return Mono.empty();
            }

            @Override
            public Mono<List<McpSchema.Tool>> listTools() {
                return Mono.fromSupplier(() -> Arrays.stream(toolNames).map(AgentMcpAdapterTest::tool).toList());
            }

            @Override
            public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
                return Mono.empty();
            }

            @Override
            public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                           Map<String, Object> context) {
                return Mono.empty();
            }

            @Override
            public void close() {
                initialized = false;
            }
        };
    }

    @Test
    void prefixSanitizesServerId() {
        assertEquals("probe_mcp__", AgentMcpAdapter.prefixOf("probe-mcp"));
        assertEquals("sse_10_1_1_5_9000__", AgentMcpAdapter.prefixOf("sse:10.1.1.5:9000"));
        assertEquals("mcp__", AgentMcpAdapter.prefixOf(" "));
    }

    @Test
    void registeredToolNamesCarryPrefix() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(
                new AgentMcpAdapter("probe-mcp", wrapper("probe-mcp", "getNetworkTestData"), Set.of())).block();

        assertTrue(toolkit.getToolNames().contains("probe_mcp__getNetworkTestData"),
                "Agent 工具集内的 MCP 工具名必须带 serverId 前缀，实际: " + toolkit.getToolNames());
    }

    @Test
    void auditNameEqualsRegisteredName() {
        String raw = "getNetworkTestData";
        String auditName = AgentMcpAdapter.prefixOf("probe-mcp") + raw;
        assertEquals("probe_mcp__getNetworkTestData", auditName);
    }
}
