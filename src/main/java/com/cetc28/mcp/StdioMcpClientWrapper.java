package com.cetc28.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 原生 Stdio MCP 客户端包装
 */
public class StdioMcpClientWrapper extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClientWrapper.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final long callTimeoutMs;
    private McpSyncClient mcpClient;
    /** 连接类异常时通知 hub 触发重连（可为 null，如单测直接构造） */
    private volatile Runnable reconnectHint;

    public StdioMcpClientWrapper(String name, String command, List<String> args, Map<String, String> env) {
        this(name, command, args, env, 60000);
    }

    public StdioMcpClientWrapper(String name, String command, List<String> args, Map<String, String> env,
                                 long callTimeoutMs) {
        super(name);
        this.command = command;
        this.args = args;
        this.env = env;
        this.callTimeoutMs = callTimeoutMs > 0 ? callTimeoutMs : 60000;
    }

    public void setReconnectHint(Runnable reconnectHint) {
        this.reconnectHint = reconnectHint;
    }

    private void notifyReconnect() {
        Runnable hint = reconnectHint;
        if (hint != null) {
            try { hint.run(); } catch (Exception ignored) { }
        }
    }

    @Override
    public Mono<Void> initialize() {
        return Mono.fromRunnable(() -> {
            // 幂等：避免 McpHubService 与 registerMcpClient 双重初始化
            if (initialized && mcpClient != null) {
                return;
            }
            try {
                ServerParameters.Builder paramsBuilder = ServerParameters.builder(command);
                if (args != null) args.forEach(paramsBuilder::arg);
                if (env != null) env.forEach(paramsBuilder::addEnvVar);

                StdioClientTransport transport = new StdioClientTransport(
                        paramsBuilder.build(), McpJsonMapper.getDefault());
                this.mcpClient = McpClient.sync(transport)
                        .requestTimeout(Duration.ofMillis(callTimeoutMs))
                        .build();
                mcpClient.initialize();
                initialized = true;
                log.info("MCP Stdio '{}' initialized", getName());
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize MCP Stdio: " + getName(), e);
            }
        });
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return Mono.fromCallable(() -> {
            if (!initialized) initialize().block();
            if (mcpClient == null) return List.of();
            McpSchema.ListToolsResult result = mcpClient.listTools();
            List<McpSchema.Tool> tools = result.tools();
            for (McpSchema.Tool t : tools) {
                cachedTools.put(t.name(), t);
            }
            return tools;
        });
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        McpSyncClient client = mcpClient;
        if (client == null || !isInitialized()) {
            notifyReconnect();
            return Mono.error(new IllegalStateException("MCP Stdio '" + getName() + "' 未连接"));
        }
        log.info("【MCP工具调用】server={}, tool={}, args={}", getName(), name, arguments);
        long start = System.currentTimeMillis();
        return Mono.fromCallable(() -> {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(name, arguments));
            log.info("【MCP工具结果】server={}, tool={}, 耗时={}ms",
                    getName(), name, System.currentTimeMillis() - start);
            return result;
        }).onErrorResume(e -> {
            // 连接类失败：通知 hub 触发重连（去抖），错误原样上抛，本轮不盲目重试避免工具副作用
            notifyReconnect();
            return Mono.error(e);
        });
    }

    /**
     * 健康探测：子进程退出后 SDK 会把客户端标记为 closed，isInitialized() 快速失败；
     * 仍做一次限时 ping 兜底（进程活着但会话卡死的情况）。
     */
    public Mono<Boolean> probe(long timeoutMs) {
        return Mono.fromCallable(() -> {
            McpSyncClient client = mcpClient;
            if (client == null || !isInitialized() || !client.isInitialized()) return false;
            client.ping();
            return true;
        }).subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                .onErrorResume(this::isPingUnsupported, e -> Mono.just(true))
                .onErrorReturn(false);
    }

    /** ping 是可选能力：收到 -32601 说明子进程与会话都活着，不应触发重连 */
    private boolean isPingUnsupported(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("-32601") || m.contains("Method not found")
                    || m.contains("not found: ping"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                     Map<String, Object> context) {
        return callTool(name, arguments);
    }

    @Override
    public void close() {
        if (mcpClient != null) {
            try { mcpClient.close(); } catch (Exception e) {
                log.warn("Error closing MCP Stdio '{}': {}", getName(), e.getMessage());
            }
        }
        initialized = false;
    }

    /**
     * 获取该 MCP 客户端缓存的全部工具（含描述）— 供提示词按服务器分组展示
     */
    public List<McpSchema.Tool> getCachedTools() {
        return new ArrayList<>(cachedTools.values());
    }
}
