package com.cetc28.agentchat.base;

import com.cetc28.agentchat.middleware.ContextInjectionMiddleware;
import com.cetc28.agentchat.state.AgentStateStoreFactory;
import com.cetc28.config.NacosPromptManager;
import com.cetc28.dto.StreamEvent;
import com.cetc28.map.MapPrompt;
import com.cetc28.mcp.McpHubService;
import com.cetc28.modelconfig.ModelRuntime;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 基类 —— 只负责把「Agent 蓝图」装配成官方 {@link HarnessAgent}，并把
 * 框架的 {@code AgentEvent} 映射成前端 SSE 使用的 {@link StreamEvent}。
 *
 * <p>架构约束（与 AgentScope 2.0 对齐）：
 *
 * <ul>
 *   <li>所有 Agent 都是 HarnessAgent，不再保留 ReActAgent 双轨；
 *   <li>身份键固定为 {@code (userId, sessionId)}：userId 来自请求用户，sessionId 是会话 ID，
 *       二者绝不互相赋值；
 *   <li>每个 Agent 使用独立的 {@code AgentStateStore}（key 前缀含 agentId），同一会话下
 *       chat / deep / orchestrator / worker 的 AgentState 互不覆盖；
 *   <li>动态上下文由 {@link ContextInjectionMiddleware} 在 onReasoning 阶段以 USER 消息注入，
 *       system prompt 只保留静态角色与规则；
 *   <li>停止生成通过 {@code HarnessAgent.getDelegate().interrupt(userId, sessionId)} 精确中断
 *       对应会话，而不是中断整个 Agent。
 * </ul>
 */
public abstract class AgentBase {

    public enum ToolGroup { NONE, BASE, WITH_MCP, NETWORK, FILE, KNOWLEDGE, ORCHESTRATOR, WORKFLOW }

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final String agentId;
    protected final String agentName;
    protected final String description;
    protected final Model chatModel;
    protected final double temperature;

    /** 官方 HarnessAgent；由 initialize() 装配，热更新时整体替换。 */
    protected volatile HarnessAgent harnessAgent;
    protected volatile boolean enabled = true;
    protected Toolkit toolkit;
    protected String systemPrompt;

    /** 运行时的模型解析器（动态配置）；测试环境或早期初始化阶段可能为 null。 */
    @Autowired(required = false)
    protected ModelRuntime modelRuntime;

    /** 会话级模型变体：profileId → 绑定该模型的 HarnessAgent（同一 Agent 共享 toolkit/systemPrompt）。 */
    private final Map<String, HarnessAgent> modelVariants = new ConcurrentHashMap<>();

    /** 主 Agent 是否启用持久化（记忆/compaction）；变体沿用同一设置。 */
    private volatile boolean persistentAgent = true;

    @Autowired(required = false)
    private AgentStateStoreFactory stateStoreFactory;

    @Autowired(required = false)
    protected NacosPromptManager nacosPromptManager;

    @Autowired(required = false)
    protected McpHubService mcpHubService;

    /** 该 Agent 的 skill 仓库（由 AgentSystemConfig 按 skill-names 注入）。 */
    protected AgentSkillRepository skillRepository;

    @Value("${agentscope.workspace.path:.agentscope/workspace}")
    protected String workspacePath;

    // ====== 权限引擎配置（默认 BYPASS 保持兼容；deny 规则任意模式下均生效） ======
    @Value("${agent.permission.mode:BYPASS}")
    private String permissionMode;

    @Value("${agent.permission.deny-tools:}")
    private String denyTools;

    @Value("${agent.permission.allow-tools:}")
    private String allowTools;

    // ====== Harness 工程化开关 ======
    @Value("${agent.harness.workspace-enabled:true}")
    private boolean workspaceEnabled;

    @Value("${agent.harness.memory-enabled:true}")
    private boolean memoryEnabled;

    @Value("${agent.harness.plan-mode-enabled:true}")
    private boolean planModeEnabled;

    @Value("${agent.harness.max-iters:12}")
    private int maxIters;

    public boolean isPlanModeEnabled() { return planModeEnabled; }

    /** 主模型故障时自动降级的备用模型（AIConfig 未配置 fallback 时为 null）。 */
    @Autowired(required = false)
    @Qualifier("fallbackChatModel")
    private Model fallbackChatModel;

    public AgentBase(String agentId, String agentName, String description,
                     Model chatModel, double temperature) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.description = description;
        this.chatModel = chatModel;
        this.temperature = temperature;
    }

    /** 有状态 Agent（chat / deep / orchestrator）：保留会话持久化与框架记忆。 */
    protected HarnessAgent createHarnessAgent(String name, String sysPrompt, Toolkit toolkit) {
        return doBuildHarness(name, sysPrompt, toolkit, true);
    }

    /** 无状态 worker：不启用框架记忆流水线，但仍有独立 state store 与 compaction。 */
    protected HarnessAgent createWorkerAgent(String name, String sysPrompt, Toolkit toolkit) {
        return doBuildHarness(name, sysPrompt, toolkit, false);
    }

    private HarnessAgent doBuildHarness(String name, String sysPrompt, Toolkit toolkit, boolean persistent) {
        return doBuildHarness(name, sysPrompt, toolkit, persistent, effectiveModel());
    }

    private HarnessAgent doBuildHarness(String name, String sysPrompt, Toolkit toolkit,
                                        boolean persistent, Model model) {
        this.systemPrompt = sysPrompt;
        this.persistentAgent = persistent;
        var builder = HarnessAgent.builder()
                .name(name)
                .agentId(agentId)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .permissionContext(buildPermissionContext())
                // 动态上下文（RAG / 附件 / 画像）统一走 onReasoning 注入，system prompt 不再变化
                .middleware(new ContextInjectionMiddleware())
                // 对话产品不需要 shell 与任意文件读写；workspace 只用于 AGENTS.md / MEMORY.md / skills
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                // token 水位触发的 compaction，替代写死的「30 条压到 10 条」
                .compaction(CompactionConfig.builder()
                        .triggerMessages(50)
                        .reserved(20_000)
                        .keepMessages(20)
                        .build());

        if (skillRepository != null) {
            builder.skillRepository(skillRepository);
        }
        if (fallbackChatModel != null) {
            builder.fallbackModel(fallbackChatModel);
        }

        // 每个 Agent 一份独立 state store：key 中含 agentId + userId + sessionId
        if (stateStoreFactory != null) {
            builder.stateStore(stateStoreFactory.create(agentId));
        }

        // workspace 根目录只配置一次，官方 IsolationScope.USER 会按 userId 再按 agentId 分区
        if (workspaceEnabled && workspacePath != null && !workspacePath.isBlank()) {
            builder.workspace(Path.of(workspacePath));
        }

        if (persistent) {
            if (memoryEnabled) {
                // 官方两层记忆：memory/YYYY-MM-DD.md + MEMORY.md；flush 节流避免每轮一次额外 LLM
                builder.memory(MemoryConfig.builder()
                        .model(model)
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                        .consolidationMinGap(Duration.ofMinutes(30))
                        .dailyFileRetentionDays(90)
                        .sessionRetentionDays(180)
                        .build());
            } else {
                // 记忆总闸：同时关闭 flush / consolidation，不留空转的写盘流水线
                builder.disableMemoryHooks().disableMemoryTools();
            }
            if (workspaceEnabled && memoryEnabled) {
                // 官方 WorkspaceContextMiddleware 默认启用：把 AGENTS.md / MEMORY.md 作为静态上下文注入
                // （无需显式 enable，只有 disableWorkspaceContext 开关）
            } else {
                builder.disableWorkspaceContext();
            }
            // 计划模式只给 orchestrator，避免 worker 也暴露 plan_* 工具
            if (planModeEnabled && "orchestrator".equals(agentId)) {
                builder.enablePlanMode().planFileDirectory("plans");
            }
        } else {
            // worker：不写 MEMORY.md、不跑 flush，但仍保留会话内的 AgentState（用于工具调用链）
            builder.disableMemoryHooks().disableMemoryTools().disableWorkspaceContext();
        }
        return builder.build();
    }

    /**
     * 当前生效的主模型：优先走运行时动态解析（后台改配置后下一次调用即生效），
     * 未装配 ModelRuntime 时回退到构造时注入的静态模型。
     */
    public Model effectiveModel() {
        return modelRuntime != null ? modelRuntime.modelFor(agentId) : chatModel;
    }

    /**
     * 会话级模型变体：为指定模型配置构建一个独立 HarnessAgent（沿用同一 toolkit / systemPrompt /
     * 记忆开关），首次构建后缓存。profileId 为空或运行时不可用时返回主 Agent。
     */
    public HarnessAgent variant(String profileId) {
        if (profileId == null || profileId.isBlank() || modelRuntime == null) return harnessAgent;
        HarnessAgent cached = modelVariants.get(profileId);
        if (cached != null) return cached;
        synchronized (modelVariants) {
            cached = modelVariants.get(profileId);
            if (cached != null) return cached;
            if (systemPrompt == null || toolkit == null || harnessAgent == null) return harnessAgent;
            HarnessAgent built = doBuildHarness(agentName, systemPrompt, toolkit,
                    persistentAgent, modelRuntime.pinnedModel(profileId, agentId));
            modelVariants.put(profileId, built);
            log.info("Agent '{}' 已生成模型变体：profile={}", agentId, profileId);
            return built;
        }
    }

    public void clearModelVariants() {
        modelVariants.clear();
    }

    private PermissionContextState buildPermissionContext() {
        PermissionMode mode;
        try {
            mode = PermissionMode.fromString(permissionMode);
            if (mode == null) mode = PermissionMode.BYPASS;
        } catch (IllegalArgumentException e) {
            log.warn("未知权限模式 '{}'，回退 BYPASS", permissionMode);
            mode = PermissionMode.BYPASS;
        }
        var b = PermissionContextState.builder().mode(mode);
        addPermissionRules(b, denyTools, PermissionBehavior.DENY);
        addPermissionRules(b, allowTools, PermissionBehavior.ALLOW);
        return b.build();
    }

    private void addPermissionRules(PermissionContextState.Builder b, String csv, PermissionBehavior behavior) {
        if (csv == null || csv.isBlank()) return;
        for (String name : csv.split(",")) {
            String tool = name.trim();
            if (tool.isEmpty()) continue;
            PermissionRule rule = new PermissionRule(tool, "", behavior, "config");
            if (behavior == PermissionBehavior.DENY) {
                b.addDenyRule("config", rule);
            } else if (behavior == PermissionBehavior.ALLOW) {
                b.addAllowRule("config", rule);
            }
        }
    }

    public abstract ToolGroup getToolGroup();

    public abstract void initialize(Toolkit toolkit);

    /**
     * 从 Nacos 加载 Agent 提示词（Nacos → 本地 prompts/{agentId}.txt → 硬编码兜底），
     * 并替换 <!--LOCAL_TOOLS--> / <!--MCP_TOOLS--> 占位符。
     */
    protected String loadAgentPrompt(Toolkit toolkit, String hardcodedFallback) {
        String template = null;
        if (nacosPromptManager != null) {
            template = nacosPromptManager.getPrompt(agentId);
        }
        if (template == null || template.isBlank()) {
            template = hardcodedFallback;
        }
        if (template == null) return hardcodedFallback != null ? hardcodedFallback : "";

        String prompt = template;
        if (prompt.contains("<!--LOCAL_TOOLS-->")) {
            prompt = prompt.replace("<!--LOCAL_TOOLS-->", formatLocalTools(toolkit));
        }
        if (prompt.contains("<!--MCP_TOOLS-->")) {
            prompt = prompt.replace("<!--MCP_TOOLS-->", formatMcpTools());
        }
        // Nacos 上的旧提示词不会包含地图说明；只要该 Agent 实际持有地图工具，就补一段兜底规则。
        if (hasMapTool(toolkit)) {
            prompt = MapPrompt.ensure(prompt);
        }
        return prompt;
    }

    private boolean hasMapTool(Toolkit toolkit) {
        if (toolkit == null) return false;
        return toolkit.getToolSchemas().stream()
                .anyMatch(schema -> schema != null && "map_render".equals(schema.getName()));
    }

    protected Set<String> mcpToolNames() {
        if (mcpHubService == null) return Set.of();
        Map<String, List<McpSchema.Tool>> toolsByServer = mcpHubService.getMcpToolsByServer();
        if (toolsByServer == null || toolsByServer.isEmpty()) return Set.of();
        Set<String> names = new HashSet<>();
        toolsByServer.forEach((server, tools) -> {
            if (tools == null) return;
            String prefix = com.cetc28.mcp.AgentMcpAdapter.prefixOf(server);
            for (McpSchema.Tool tool : tools) {
                if (tool != null && tool.name() != null) names.add(prefix + tool.name());
            }
        });
        return names;
    }

    protected String formatLocalTools(Toolkit toolkit) {
        if (toolkit == null) return "（无本地工具）";
        Set<String> mcpNames = mcpToolNames();
        var schemas = toolkit.getToolSchemas();
        if (schemas == null || schemas.isEmpty()) return "（无本地工具）";
        String tools = schemas.stream()
                .filter(s -> !mcpNames.contains(s.getName()))
                .map(s -> "  - " + s.getName()
                        + (s.getDescription() != null && !s.getDescription().isBlank() ? ": " + s.getDescription() : ""))
                .collect(Collectors.joining("\n"));
        return tools.isBlank() ? "（无本地工具）" : tools;
    }

    protected String formatMcpTools() {
        if (mcpHubService == null) return "（当前无已连接的 MCP 工具）";
        Map<String, List<McpSchema.Tool>> toolsByServer = mcpHubService.getMcpToolsByServer();
        if (toolsByServer.isEmpty()) return "（当前无已连接的 MCP 工具）";
        return toolsByServer.entrySet().stream()
                .map(entry -> {
                    String prefix = com.cetc28.mcp.AgentMcpAdapter.prefixOf(entry.getKey());
                    String tools = entry.getValue().stream()
                            .map(t -> "  - " + prefix + t.name()
                                    + (t.description() != null && !t.description().isBlank()
                                        ? ": " + t.description() : ""))
                            .collect(Collectors.joining("\n"));
                    return "[" + entry.getKey() + "]\n" + tools;
                })
                .collect(Collectors.joining("\n\n"));
    }

    protected String formatToolkitTools(Toolkit toolkit) {
        if (toolkit == null) return "（无工具）";
        var schemas = toolkit.getToolSchemas();
        if (schemas == null || schemas.isEmpty()) return "（无工具）";
        return schemas.stream()
                .map(s -> "  - " + s.getName()
                        + (s.getDescription() != null && !s.getDescription().isBlank() ? ": " + s.getDescription() : ""))
                .collect(Collectors.joining("\n"));
    }

    protected void watchNacosPrompt(Runnable onChange) {
        if (nacosPromptManager != null) {
            nacosPromptManager.watchPrompt(agentId, onChange);
        }
    }

    public void setSkillRepository(AgentSkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    // ==================== 调用入口 ====================

    public String process(String userMessage, String userId, String sessionId) throws Exception {
        return process(userMessage, userId, sessionId, null, null);
    }

    public String process(String userMessage, String userId, String sessionId, String context) throws Exception {
        return process(userMessage, userId, sessionId, context, null);
    }

    public String process(String userMessage, String userId, String sessionId,
                          String context, Map<String, Object> attributes) throws Exception {
        HarnessAgent agent = harnessAgent;
        if (agent == null) return "";
        RuntimeContext ctx = buildRuntimeContext(userId, sessionId, context, attributes);
        Msg response = agent.call(userMessage, ctx).block();
        return response != null ? response.getTextContent() : "";
    }

    public Flux<StreamEvent> processRichStream(String userMessage, String userId, String sessionId) {
        return processRichStreamWithContext(userMessage, userId, sessionId, null);
    }

    public Flux<StreamEvent> processRichStreamWithContext(String userMessage, String userId,
                                                          String sessionId, String context) {
        return processRichStream(harnessAgent, userMessage, userId, sessionId, context, null);
    }

    public Flux<StreamEvent> processRichStreamWithContext(String userMessage, String userId,
                                                          String sessionId, String context,
                                                          Map<String, Object> attributes) {
        return processRichStream(harnessAgent, userMessage, userId, sessionId, context, attributes);
    }

    /** 指定 HarnessAgent 实例（会话级模型变体）的流式调用。 */
    public Flux<StreamEvent> processRichStreamWithContext(HarnessAgent target, String userMessage,
                                                          String userId, String sessionId,
                                                          String context) {
        return processRichStream(target, userMessage, userId, sessionId, context, null);
    }

    /** 指定 HarnessAgent 实例（会话级模型变体）的流式调用，附带 attributes。 */
    public Flux<StreamEvent> processRichStreamWithContext(HarnessAgent target, String userMessage,
                                                          String userId, String sessionId,
                                                          String context, Map<String, Object> attributes) {
        return processRichStream(target, userMessage, userId, sessionId, context, attributes);
    }

    private Flux<StreamEvent> processRichStream(HarnessAgent target, String userMessage, String userId,
                                                String sessionId, String context,
                                                Map<String, Object> attributes) {
        return Flux.defer(() -> {
            Flux<io.agentscope.core.event.AgentEvent> events =
                    eventStream(target, userMessage, userId, sessionId, context, attributes);
            if (events == null) return Flux.empty();
            return events.concatMap(event -> {
                StreamEvent mapped = mapEvent(event);
                return mapped == null ? Flux.<StreamEvent>empty() : Flux.just(mapped);
            });
        });
    }

    private Flux<io.agentscope.core.event.AgentEvent> eventStream(HarnessAgent target, String userMessage,
                                                                  String userId, String sessionId,
                                                                  String context,
                                                                  Map<String, Object> attributes) {
        HarnessAgent agent = target != null ? target : harnessAgent;
        if (agent == null) return null;
        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userMessage).build())
                .build();
        RuntimeContext ctx = buildRuntimeContext(userId, sessionId, context, attributes);
        return agent.streamEvents(msg, ctx);
    }

    /** 构建 RuntimeContext：userId / sessionId 分离，context 与 attributes 交给中间件。 */
    private RuntimeContext buildRuntimeContext(String userId, String sessionId,
                                               String context, Map<String, Object> attributes) {
        var b = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId == null || userId.isBlank() ? "anonymous" : userId);
        if (context != null && !context.isBlank()) {
            b.put(ContextInjectionMiddleware.CONTEXT_KEY, context);
        }
        if (attributes != null) {
            attributes.forEach(b::put);
        }
        return b.build();
    }

    /**
     * 中断指定会话的在途调用。HarnessAgent 本身只暴露无参 interrupt()，
     * 精确中断需要下钻到 delegate 的 {@code interrupt(userId, sessionId)}。
     */
    public void interrupt(String userId, String sessionId) {
        HarnessAgent agent = harnessAgent;
        if (agent == null) return;
        try {
            agent.getDelegate().interrupt(userId, sessionId);
        } catch (Exception e) {
            log.warn("Agent '{}' interrupt failed for session {}: {}", agentId, sessionId, e.getMessage());
        }
    }

    private StreamEvent mapEvent(io.agentscope.core.event.AgentEvent event) {
        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA
                && event instanceof TextBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text != null && !text.isEmpty()) {
                return new StreamEvent("text", Map.of("delta", text));
            }
        } else if (event.getType() == AgentEventType.THINKING_BLOCK_DELTA
                && event instanceof ThinkingBlockDeltaEvent delta) {
            String text = delta.getDelta();
            if (text != null && !text.isEmpty()) {
                return new StreamEvent("thinking", Map.of("delta", text));
            }
        } else if (event.getType() == AgentEventType.TOOL_CALL_START
                && event instanceof ToolCallStartEvent tcs) {
            return new StreamEvent("tool_call", Map.of(
                    "name", nz(tcs.getToolCallName()),
                    "callId", nz(tcs.getToolCallId())));
        } else if (event.getType() == AgentEventType.TOOL_CALL_END
                && event instanceof ToolCallEndEvent tce) {
            return new StreamEvent("tool_call_end", Map.of(
                    "name", nz(tce.getToolCallName()),
                    "callId", nz(tce.getToolCallId())));
        } else if (event.getType() == AgentEventType.TOOL_RESULT_START
                && event instanceof ToolResultStartEvent trs) {
            return new StreamEvent("tool_result_start", Map.of(
                    "name", nz(trs.getToolCallName()),
                    "callId", nz(trs.getToolCallId())));
        } else if (event.getType() == AgentEventType.TOOL_RESULT_END
                && event instanceof ToolResultEndEvent tre) {
            return new StreamEvent("tool_result_end", Map.of(
                    "name", nz(tre.getToolCallName()),
                    "callId", nz(tre.getToolCallId()),
                    "state", tre.getState() == null ? "" : tre.getState().name()));
        } else if (event.getType() == AgentEventType.TOOL_RESULT_TEXT_DELTA
                && event instanceof io.agentscope.core.event.ToolResultTextDeltaEvent trtd) {
            String text = trtd.getDelta();
            if (text != null && !text.isEmpty()) {
                return new StreamEvent("tool_result", Map.of(
                        "delta", text,
                        "callId", nz(trtd.getToolCallId())));
            }
        } else if (event.getType() == AgentEventType.MODEL_CALL_END
                && event instanceof ModelCallEndEvent mce) {
            // 每次模型调用的用量：上层累加到 RunContext 并透传给前端（token 统计 / 流量）
            ChatUsage usage = mce.getUsage();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("replyId", nz(mce.getReplyId()));
            data.put("inputTokens", usage == null ? 0 : usage.getInputTokens());
            data.put("outputTokens", usage == null ? 0 : usage.getOutputTokens());
            data.put("cachedTokens", usage == null ? 0 : usage.getCachedTokens());
            data.put("totalTokens", usage == null ? 0 : usage.getTotalTokens());
            return new StreamEvent("model_usage", data);
        } else if (event.getType() == AgentEventType.AGENT_END
                && event instanceof AgentEndEvent end) {
            // 官方事件流的最后一个事件：模型/工具循环已经结束，之后只剩 onAgent 中间件的
            // 记忆 flush / session offload 等后处理。把它透传给上层，让 UI 可以先结束。
            return new StreamEvent("agent_end", Map.of("replyId", nz(end.getReplyId())));
        }
        return null;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    public void setToolkit(Toolkit toolkit) { this.toolkit = toolkit; }
    public HarnessAgent getHarnessAgent() { return harnessAgent; }
    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getSkillNames() {
        if (skillRepository == null) return List.of();
        try {
            return skillRepository.getAllSkillNames();
        } catch (Exception e) {
            log.warn("Failed to list skills for agent '{}': {}", agentId, e.getMessage());
            return List.of();
        }
    }

    /** 供 DispatchAgentsTool 判断是否可作为 worker 被调度。 */
    public Agent asAgent() {
        return harnessAgent;
    }
}
