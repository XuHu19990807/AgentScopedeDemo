package com.cetc28.mcp;

import com.cetc28.config.AgentSystemConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP Hub 服务 — 使用 AgentScope 原生 McpClientWrapper
 *
 * MCP 工具通过 Toolkit.registerMcpClient() 自动注册为 AgentTool，
 * 无需手写 McpToolAdapter 和 McpToolRegistry。
 *
 * 连接治理（断连自愈）：
 * - 每个 MCP Server 以 ManagedServer 登记：定义(如何连) + 当前连接 + 状态机 + 重试时间；
 * - UI/配置文件添加的定义持久化到 ./data/mcp-servers.json，应用重启后自动恢复连接；
 * - 定时健康检查（ping 限时）发现断连后重建客户端（SDK 的 SSE session 是服务端状态，
 *   服务端重启后旧客户端必然失效，唯一可靠自愈方式是完整重建）；
 * - 指数退避重试，服务恢复后自动回到 CONNECTED；
 * - callTool 连接类失败时触发 requestReconnect（去抖），本轮错误上抛、下轮恢复。
 */
@Service
public class McpHubService {

    private static final Logger log = LoggerFactory.getLogger(McpHubService.class);

    /** 状态机（字符串常量便于 JSON 展示） */
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_CONNECTING = "CONNECTING";
    public static final String STATUS_RECONNECTING = "RECONNECTING";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final Toolkit sharedToolkit = new Toolkit();

    /** name -> 受管连接（含未连上的：失败条目保持可见并由重试环恢复） */
    private final Map<String, ManagedServer> managed = new ConcurrentHashMap<>();

    /** 健康探测 + 重连统一在此单线程串行执行，天然去抖，不占用 Spring 调度线程 */
    private final ExecutorService mcpIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-health-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean sweepInFlight = new AtomicBoolean();

    @Autowired
    @Lazy
    private AgentSystemConfig agentSystemConfig;

    @Autowired
    private McpServerRegistry registry;

    @Autowired
    private Environment environment;

    /** 允许通过 UI 启动的 stdio 命令白名单 */
    @Value("${mcp.stdio.allowed-commands:npx,node,uvx,python,python3}")
    private String allowedCommands = "npx,node,uvx,python,python3";

    @Value("${mcp.connect-timeout-ms:10000}")
    private long connectTimeoutMs = 10000;

    @Value("${mcp.call-timeout-ms:60000}")
    private long callTimeoutMs = 60000;

    @Value("${mcp.health-timeout-ms:5000}")
    private long healthTimeoutMs = 5000;

    @Value("${mcp.reconnect-initial-delay-ms:10000}")
    private long reconnectInitialDelayMs = 10000;

    @Value("${mcp.reconnect-max-delay-ms:300000}")
    private long reconnectMaxDelayMs = 300000;

    @Value("${mcp.auto-reconnect-enabled:true}")
    private boolean autoReconnectEnabled = true;

    // ============================================================
    // 受管连接
    // ============================================================

    static class ManagedServer {
        final McpServerDefinition definition;
        final Object lock = new Object();
        volatile McpClientWrapper wrapper;          // null = 当前无活动连接
        volatile String status = STATUS_CONNECTING;
        volatile int attempts;                      // 本轮故障以来的连续失败次数
        volatile String lastError;
        volatile long lastConnectedAt;
        volatile long nextRetryAt;                  // 到达该时刻才再次尝试
        volatile int toolCount;

        ManagedServer(McpServerDefinition definition) {
            this.definition = definition;
        }
    }

    // ============================================================
    // 连接入口（UI / Nacos / 启动加载）
    // ============================================================

    public boolean connectStdio(String name, String command, boolean enabled) {
        return connectStdio(name, command, "", enabled);
    }

    /**
     * 连接 Stdio MCP，支持参数（如 "npx -y @modelcontextprotocol/server-everything"）。
     * 命令必须在白名单内，避免通过 Web 接口执行任意程序。
     */
    public boolean connectStdio(String name, String command, String args, boolean enabled) {
        if (!enabled || name == null || name.isBlank() || command == null || command.isBlank()) return false;
        if (!commandAllowed(command)) {
            log.warn("拒绝连接 Stdio MCP '{}'：命令 '{}' 不在白名单 {} 内", name, command, allowedCommands);
            return false;
        }
        McpServerDefinition def = McpServerDefinition.stdio(
                name.trim(), command.trim(), splitArgs(args), McpServerDefinition.SOURCE_UI);
        return connectManaged(def);
    }

    public StdioMcpClientWrapper connectStdio(String name, String command,
                                                List<String> args, Map<String, String> env) {
        McpServerDefinition def = McpServerDefinition.stdio(
                name, command, args, McpServerDefinition.SOURCE_UI);
        connectManaged(def);
        ManagedServer ms = managed.get(name);
        McpClientWrapper w = ms == null ? null : ms.wrapper;
        return w instanceof StdioMcpClientWrapper stdio ? stdio : null;
    }

    public boolean connectSse(String name, String url, boolean enabled) {
        return connectSse(name, url, enabled, McpServerDefinition.SOURCE_UI);
    }

    /** Nacos 发现等内部来源用带 source 的重载：nacos 来源不写持久化文件 */
    public boolean connectSse(String name, String url, boolean enabled, String source) {
        if (!enabled || name == null || name.isEmpty() || url == null || url.isEmpty()) return false;
        McpServerDefinition def = McpServerDefinition.sse(name.trim(), url.trim(), source);
        return connectManaged(def);
    }

    public SseMcpClientWrapper connectSse(String name, String url) {
        connectSse(name, url, true);
        ManagedServer ms = managed.get(name);
        McpClientWrapper w = ms == null ? null : ms.wrapper;
        return w instanceof SseMcpClientWrapper sse ? sse : null;
    }

    /**
     * 登记/替换定义并同步尝试连接。无论成败条目都保留（失败也有 UI 可见性与自动重试），
     * ui 来源成败都持久化 — "先添加、服务后启动"的场景靠这一点恢复。
     */
    private boolean connectManaged(McpServerDefinition def) {
        // 同名替换：先关闭旧条目持有的活动连接，防止泄漏与 Toolkit 残留
        ManagedServer prev = managed.get(def.getName());
        if (prev != null) {
            synchronized (prev.lock) {
                McpClientWrapper old = prev.wrapper;
                prev.wrapper = null;
                if (old != null) {
                    try { sharedToolkit.removeMcpClient(old.getName()).block(Duration.ofSeconds(5)); }
                    catch (Exception ignored) { /* Toolkit 条目不存在时忽略 */ }
                    closeQuietly(old);
                }
            }
        }
        ManagedServer ms = new ManagedServer(def);
        managed.put(def.getName(), ms);
        if (McpServerDefinition.SOURCE_UI.equals(def.getSource())) {
            registry.upsert(def);
        }
        return attemptConnect(ms);
    }

    // ============================================================
    // 建连/重建（唯一入口，全部经 mcpIO 串行或调用方同步等待）
    // ============================================================

    /** 完整建连：拆旧连接 → 依据定义重建客户端 → 注册 Toolkit → 刷新 Agent 工具 */
    private boolean attemptConnect(ManagedServer ms) {
        synchronized (ms.lock) {
            McpServerDefinition def = ms.definition;
            boolean toolkitChanged = false;
            McpClientWrapper old = ms.wrapper;
            ms.wrapper = null;
            if (old != null) {
                removeOldClient(old);
                toolkitChanged = true;
            }
            McpClientWrapper w = null;
            try {
                if (McpServerDefinition.TRANSPORT_STDIO.equals(def.getTransport())
                        && !commandAllowed(def.getCommand())) {
                    throw new IllegalStateException(
                            "命令 '" + def.getCommand() + "' 不在白名单内");
                }
                ms.status = STATUS_CONNECTING;
                w = buildWrapper(def);
                w.initialize().block(Duration.ofMillis(Math.max(1000, connectTimeoutMs)));
                if (!w.isInitialized()) {
                    throw new IllegalStateException("initialize 未完成（服务不可达或握手失败）");
                }
                sharedToolkit.registerMcpClient(w)
                        .block(Duration.ofMillis(Math.max(1000, connectTimeoutMs)));
                ms.wrapper = w;
                ms.toolCount = countTools(w);
                ms.status = STATUS_CONNECTED;
                ms.attempts = 0;
                ms.lastError = null;
                ms.lastConnectedAt = System.currentTimeMillis();
                ms.nextRetryAt = 0;
                log.info("MCP '{}' connected ({} tools)", def.getName(), ms.toolCount);
                refreshAgents();
                return true;
            } catch (Exception e) {
                closeQuietly(w);
                long now = System.currentTimeMillis();
                ms.attempts++;
                ms.toolCount = 0;   // 无活动连接时不展示历史工具数
                ms.lastError = rootMessage(e);
                ms.status = STATUS_FAILED;
                ms.nextRetryAt = now + backoffMs(ms.attempts, reconnectInitialDelayMs,
                        reconnectMaxDelayMs, Math.random());
                log.warn("MCP '{}' connect failed (第{}次): {}，{}ms 后重试",
                        def.getName(), ms.attempts, ms.lastError, ms.nextRetryAt - now);
                if (toolkitChanged) refreshAgents();
                return false;
            }
        }
    }

    private McpClientWrapper buildWrapper(McpServerDefinition def) {
        McpClientWrapper w;
        if (McpServerDefinition.TRANSPORT_SSE.equals(def.getTransport())) {
            w = new SseMcpClientWrapper(def.getName(), def.getUrl(), callTimeoutMs);
        } else {
            w = new StdioMcpClientWrapper(def.getName(), def.getCommand(),
                    def.getArgs(), Map.of(), callTimeoutMs);
        }
        // 连接类失败时回调 hub 触发重连（去抖在 requestReconnect 内部）
        if (w instanceof SseMcpClientWrapper sse) {
            sse.setReconnectHint(() -> requestReconnect(def.getName()));
        } else if (w instanceof StdioMcpClientWrapper stdio) {
            stdio.setReconnectHint(() -> requestReconnect(def.getName()));
        }
        return w;
    }

    private void removeOldClient(McpClientWrapper old) {
        try { sharedToolkit.removeMcpClient(old.getName()).block(Duration.ofMillis(5000)); }
        catch (Exception ignored) { }
        closeQuietly(old);
    }

    private int countTools(McpClientWrapper wrapper) {
        try {
            if (wrapper instanceof SseMcpClientWrapper sse) {
                return sse.getCachedTools() == null ? 0 : sse.getCachedTools().size();
            }
            if (wrapper instanceof StdioMcpClientWrapper stdio) {
                return stdio.getCachedTools() == null ? 0 : stdio.getCachedTools().size();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return 0;
    }

    private void closeQuietly(McpClientWrapper wrapper) {
        if (wrapper == null) return;
        try {
            wrapper.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }

    // ============================================================
    // 重连触发：手动 / 调用失败去抖 / 定时健康检查
    // ============================================================

    /** UI 手动重连：立即重试（清零退避），不受 auto-reconnect 开关限制 */
    public boolean reconnectNow(String name) {
        ManagedServer ms = managed.get(name);
        if (ms == null) return false;
        synchronized (ms.lock) {
            ms.attempts = 0;
            ms.nextRetryAt = 0;
            ms.status = STATUS_CONNECTING;
            ms.lastError = null;
        }
        mcpIO.submit(() -> attemptConnect(ms));
        return true;
    }

    /**
     * callTool 失败触发的去抖重连：仅当当前是 CONNECTED 才改状态并排队重试，
     * 避免一次断连引发 N 个工具调用并发重连。
     */
    public void requestReconnect(String name) {
        if (!autoReconnectEnabled) return;
        ManagedServer ms = managed.get(name);
        if (ms == null) return;
        synchronized (ms.lock) {
            if (!STATUS_CONNECTED.equals(ms.status)) return;   // 已在重连/失败等待中
            ms.status = STATUS_RECONNECTING;
            ms.attempts = 0;
            ms.lastError = "工具调用失败，触发重连";
            ms.nextRetryAt = System.currentTimeMillis();
        }
        mcpIO.submit(() -> attemptConnect(ms));
    }

    /** 定时健康检查：探测存活 + 到点重试 */
    @Scheduled(fixedDelayString = "${mcp.health-check-interval-ms:30000}",
            initialDelayString = "${mcp.health-check-interval-ms:30000}")
    public void healthCheckTick() {
        if (!autoReconnectEnabled || managed.isEmpty()) return;
        if (!sweepInFlight.compareAndSet(false, true)) return;
        mcpIO.submit(() -> {
            try {
                runHealthSweep();
            } catch (Exception e) {
                log.warn("MCP 健康检查轮异常: {}", e.getMessage());
            } finally {
                sweepInFlight.set(false);
            }
        });
    }

    /** 包内可见供单测直接调用 */
    void runHealthSweep() {
        long now = System.currentTimeMillis();
        for (ManagedServer ms : managed.values()) {
            String status = ms.status;
            if (STATUS_DISABLED.equals(status) || STATUS_CONNECTING.equals(status)) continue;
            if (STATUS_CONNECTED.equals(status)) {
                if (!probeServer(ms)) {
                    log.warn("MCP '{}' 健康检查失败，进入重连", ms.definition.getName());
                    synchronized (ms.lock) {
                        ms.status = STATUS_RECONNECTING;
                        ms.attempts = 0;
                        ms.lastError = "健康检查失败";
                        ms.nextRetryAt = now;
                    }
                    attemptConnect(ms);
                }
            } else if (now >= ms.nextRetryAt) {
                attemptConnect(ms);
            }
        }
    }

    private boolean probeServer(ManagedServer ms) {
        McpClientWrapper w = ms.wrapper;
        if (w == null) return false;
        long blockMs = Math.max(500, healthTimeoutMs) + 3000;
        Boolean ok = null;
        try {
            if (w instanceof SseMcpClientWrapper sse) {
                ok = sse.probe(healthTimeoutMs).block(Duration.ofMillis(blockMs));
            } else if (w instanceof StdioMcpClientWrapper stdio) {
                ok = stdio.probe(healthTimeoutMs).block(Duration.ofMillis(blockMs));
            } else {
                return w.isInitialized();   // 未知类型：不强行重连
            }
        } catch (Exception e) {
            return false;
        }
        return ok == null || ok;   // block 超时返回 null 视为仍存活（ping 内部已限时），保守放行
    }

    /** 指数退避：initial × 2^(n-1)，封顶 max，乘 0.8~1.2 抖动；纯函数供单测 */
    static long backoffMs(int attempts, long initialMs, long maxMs, double jitter01) {
        int n = Math.max(1, attempts);
        double base = initialMs * Math.pow(2, Math.min(n - 1, 24));
        double capped = Math.min(base, maxMs);
        double j = 0.8 + 0.4 * Math.min(Math.max(jitter01, 0.0), 0.999999);
        return Math.max(1000, Math.round(capped * j));
    }

    // ============================================================
    // 启动恢复：yml mcp.servers + ./data/mcp-servers.json
    // ============================================================

    @EventListener(ApplicationReadyEvent.class)
    public void restoreManagedServersOnStartup() {
        Map<String, McpServerDefinition> merged = new LinkedHashMap<>();
        try {
            List<McpServerDefinition> ymlDefs = Binder.get(environment)
                    .bind("mcp.servers", Bindable.listOf(McpServerDefinition.class))
                    .orElseGet(ArrayList::new);
            for (McpServerDefinition def : ymlDefs) {
                if (def == null || !def.isValid()) {
                    log.warn("跳过无效的 mcp.servers 条目: {}", def == null ? "null" : def.getName());
                    continue;
                }
                if (def.getSource() == null || def.getSource().isBlank()
                        || McpServerDefinition.SOURCE_UI.equals(def.getSource())) {
                    def.setSource(McpServerDefinition.SOURCE_YML);
                }
                merged.put(def.getName(), def);
            }
        } catch (Exception e) {
            log.warn("解析 yml mcp.servers 失败: {}", e.getMessage());
        }
        // 持久化文件优先于 yml 同名条目（运行期改过就以运行期为准）
        for (McpServerDefinition def : registry.load()) {
            merged.put(def.getName(), def);
        }
        if (merged.isEmpty()) return;
        for (McpServerDefinition def : merged.values()) {
            ManagedServer ms = new ManagedServer(def);
            if (!def.isEnabled()) {
                ms.status = STATUS_DISABLED;
                managed.put(def.getName(), ms);
                continue;
            }
            managed.put(def.getName(), ms);
            mcpIO.submit(() -> attemptConnect(ms));
        }
        log.info("MCP 启动恢复：已提交 {} 个 Server 的异步连接任务", merged.size());
    }

    // ============================================================
    // 断开与查询
    // ============================================================

    public boolean disconnect(String name) {
        ManagedServer removed = managed.remove(name);
        if (removed == null) return false;
        synchronized (removed.lock) {
            McpClientWrapper old = removed.wrapper;
            removed.wrapper = null;
            if (old != null) {
                removeOldClient(old);
                log.info("MCP '{}' disconnected", name);
            }
            removed.status = STATUS_DISABLED;
        }
        registry.remove(name);
        refreshAgents();
        return true;
    }

    public Toolkit getSharedToolkit() { return sharedToolkit; }

    /** 当前活动连接（供 AgentSystemConfig 注册到各 Agent Toolkit） */
    public List<McpClientWrapper> getClients() {
        List<McpClientWrapper> list = new ArrayList<>();
        for (ManagedServer ms : managed.values()) {
            if (STATUS_CONNECTED.equals(ms.status) && ms.wrapper != null) {
                list.add(ms.wrapper);
            }
        }
        return list;
    }

    /**
     * 按 MCP Server 分组获取工具（含描述）— 供提示词格式化为 AITEST 风格的分组展示
     *
     * @return Map<serverName, List<Tool>>
     */
    public Map<String, List<McpSchema.Tool>> getMcpToolsByServer() {
        Map<String, List<McpSchema.Tool>> result = new LinkedHashMap<>();
        for (ManagedServer ms : managed.values()) {
            McpClientWrapper c = ms.wrapper;
            if (c == null || !STATUS_CONNECTED.equals(ms.status)) continue;
            List<McpSchema.Tool> tools = null;
            // 兼容两类 wrapper，均暴露 getCachedTools()
            if (c instanceof SseMcpClientWrapper sse) {
                tools = sse.getCachedTools();
            } else if (c instanceof StdioMcpClientWrapper stdio) {
                tools = stdio.getCachedTools();
            }
            if (tools != null && !tools.isEmpty()) {
                result.put(c.getName(), tools);
            }
        }
        return result;
    }

    /** 管理页状态列表：包含断开/重连中的条目（旧实现失败即消失） */
    public List<Map<String, Object>> getServerInfoList() {
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> names = new ArrayList<>(managed.keySet());
        Collections.sort(names);
        for (String name : names) {
            ManagedServer ms = managed.get(name);
            if (ms == null) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name);
            info.put("transport", ms.definition.getTransport());
            info.put("target", ms.definition.targetSummary());
            info.put("status", ms.status);
            info.put("connected", STATUS_CONNECTED.equals(ms.status));
            info.put("toolCount", ms.toolCount);
            info.put("attempts", ms.attempts);
            info.put("lastError", ms.lastError);
            info.put("lastConnectedAt", ms.lastConnectedAt);
            info.put("nextRetryAt", ms.nextRetryAt);
            info.put("source", ms.definition.getSource());
            info.put("enabled", ms.definition.isEnabled());
            list.add(info);
        }
        return list;
    }

    private void refreshAgents() {
        if (agentSystemConfig != null) {
            try {
                agentSystemConfig.refreshAgentTools();
            } catch (Exception e) {
                log.warn("刷新 Agent 工具集失败: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down MCP Hub...");
        for (ManagedServer ms : managed.values()) {
            McpClientWrapper w = ms.wrapper;
            ms.wrapper = null;
            closeQuietly(w);
        }
        managed.clear();
        mcpIO.shutdownNow();
    }

    // ============================================================
    // 工具方法（保留原语义）
    // ============================================================

    private boolean commandAllowed(String command) {
        if (command == null) return false;
        String executable = command.trim();
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        if (slash >= 0) executable = executable.substring(slash + 1);
        if (executable.endsWith(".exe") || executable.endsWith(".cmd") || executable.endsWith(".bat")) {
            executable = executable.substring(0, executable.lastIndexOf('.'));
        }
        for (String allowed : allowedCommands.split(",")) {
            if (allowed.trim().equalsIgnoreCase(executable)) return true;
        }
        return false;
    }

    /** 拆分参数，支持双引号包裹的片段 */
    static List<String> splitArgs(String raw) {
        List<String> args = new ArrayList<>();
        if (raw == null || raw.isBlank()) return args;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : raw.trim().toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) args.add(current.toString());
        return args;
    }
}
