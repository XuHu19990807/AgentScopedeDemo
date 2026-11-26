package com.cetc28.mcp;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 原生 SSE MCP 客户端包装
 */
public class SseMcpClientWrapper extends McpClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(SseMcpClientWrapper.class);

    private final String url;
    private final long callTimeoutMs;
    private McpSyncClient mcpClient;
    /** 连接类异常时通知 hub 触发重连（可为 null，如单测直接构造） */
    private volatile Runnable reconnectHint;

    public SseMcpClientWrapper(String name, String url) {
        this(name, url, 60000);
    }

    public SseMcpClientWrapper(String name, String url, long callTimeoutMs) {
        super(name);
        this.url = url;
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
            // 幂等：避免 McpHubService 与 Toolkit.registerMcpClient 双重初始化，
            // 否则会创建两个 McpSyncClient / 两个 SSE 流，响应互相串流被丢弃
            if (initialized && mcpClient != null) {
                return;
            }
            try {
                if (!checkServerReachable(url)) {
                    log.warn("MCP SSE '{}' at {} not reachable, skipping", getName(), url);
                    return;
                }
                HttpClientSseClientTransport transport =
                        HttpClientSseClientTransport.builder(url).build();
                this.mcpClient = McpClient.sync(transport)
                        .requestTimeout(Duration.ofMillis(callTimeoutMs))
                        .build();
                mcpClient.initialize();
                initialized = true;
                log.info("MCP SSE '{}' initialized at {}", getName(), url);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize MCP SSE: " + getName(), e);
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
        log.info("【MCP工具调用】server={}, tool={}, args={}", getName(), name, arguments);
        long start = System.currentTimeMillis();
        McpSyncClient client = mcpClient;
        if (client == null) {
            notifyReconnect();
            return Mono.error(new IllegalStateException("MCP SSE '" + getName() + "' 未连接"));
        }
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
     * 健康探测：ping MCP server。SSE 断线后 session 失效但 SDK 客户端可能仍显示 initialized，
     * 因此必须真实 ping，并限时 timeoutMs（超时/异常都判不健康）。
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

    /** 能收到 -32601 恰恰证明会话活着；IOException/超时才是不健康 */
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
                log.warn("Error closing MCP SSE '{}': {}", getName(), e.getMessage());
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

    /**
     * TCP 端口探测 — 检查服务器是否可达。
     *
     * 注意：不能用 HTTP GET 探测 SSE 端点，因为 SSE 是流式连接永不关闭，
     * GET 会一直阻塞到超时导致误判不可达。TCP 连接只验证端口可达，不打开 SSE 流。
     */
    private boolean checkServerReachable(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            // URI 对默认端口返回 -1，需要按 scheme 推导，否则 InetSocketAddress 会抛异常
            int port = uri.getPort();
            if (port <= 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
