package com.cetc28.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具的 Agent 视图：给工具名加 "serverId__" 前缀，避免多个 MCP Server 或本地工具重名。
 *
 * 关键点：
 * - 不持有连接，close() 是 no-op，避免 Agent 生命周期误关共享连接；
 * - callTool 会去掉前缀再转发给真实连接；
 * - 可选工具白名单（为空表示全部）。
 */
public class AgentMcpAdapter extends McpClientWrapper {

    private final McpClientWrapper delegate;
    private final String prefix;
    private final Set<String> allowedTools;

    public AgentMcpAdapter(String serverId, McpClientWrapper delegate, Set<String> allowedTools) {
        super(serverId);
        this.delegate = delegate;
        this.prefix = prefixOf(serverId);
        this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    }

    /**
     * Agent 视图里的工具名前缀（serverId 消毒后 + "__"）。
     * 供提示词渲染、启动清单等需要与 {@link Toolkit#registerMcpClient} 注册结果对齐的地方复用。
     */
    public static String prefixOf(String serverId) {
        return sanitize(serverId) + "__";
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "mcp";
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }

    @Override
    public Mono<Void> initialize() {
        // 真实连接由 McpHubService 负责初始化
        return delegate.initialize();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools().map(tools -> {
            List<McpSchema.Tool> mapped = new ArrayList<>();
            for (McpSchema.Tool tool : tools) {
                if (!allowedTools.isEmpty() && !allowedTools.contains(tool.name())) continue;
                McpSchema.Tool prefixed = McpSchema.Tool.builder()
                        .name(prefix + tool.name())
                        .title(tool.title())
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .outputSchema(tool.outputSchema())
                        .annotations(tool.annotations())
                        .meta(tool.meta())
                        .build();
                cachedTools.put(prefixed.name(), prefixed);
                mapped.add(prefixed);
            }
            return mapped;
        });
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return delegate.callTool(strip(name), arguments);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                    Map<String, Object> context) {
        return delegate.callTool(strip(name), arguments, context);
    }

    @Override
    public void close() {
        // 共享连接，不在这里关闭
    }

    private String strip(String name) {
        if (name != null && name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        return name;
    }

    public String getPrefix() {
        return prefix;
    }
}
