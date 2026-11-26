package com.cetc28.config;

import com.cetc28.agentchat.base.AgentBase;
import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.agentchat.orchestration.DispatchAgentsTool;
import com.cetc28.mcp.AgentMcpAdapter;
import com.cetc28.mcp.McpHubService;
import com.cetc28.skill.CompositeSkillRepository;
import com.cetc28.skill.FilteredSkillRepository;
import com.cetc28.skill.LocalSkillRepository;
import com.cetc28.skill.NacosSkillRepository;
import com.cetc28.tool.DocumentOutputTool;
import com.cetc28.tool.ExternalApiTool;
import com.cetc28.tool.FileAnalysisTool;
import com.cetc28.tool.MapTool;
import com.cetc28.tool.NetworkPlanTool;
import com.cetc28.tool.SchemeQueryTool;
import com.cetc28.tool.TimeTool;
import com.cetc28.tool.WeatherTool;
import com.cetc28.workflow.WorkflowTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent系统配置 — 初始化所有 Agent，注入 Nacos 技能和提示词
 *
 * 参考 AITEST 的 AgentSystemConfig：
 * - 每个 Agent 根据 application.yml 中 skill-names 配置创建 FilteredSkillRepository
 *   （"*" = 全部技能，空 = 无技能，逗号分隔 = 指定技能列表）
 * - 提示词由各 Agent 在 initialize() 中通过 NacosPromptManager 加载
 */
@Configuration
public class AgentSystemConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentSystemConfig.class);

    @Autowired
    private List<AgentBase> agents;

    @Autowired
    private WeatherTool weatherTool;

    @Autowired
    private ExternalApiTool externalApiTool;

    @Autowired
    private TimeTool timeTool;

    @Autowired
    private NetworkPlanTool networkPlanTool;

    @Autowired
    private SchemeQueryTool schemeQueryTool;

    @Autowired
    private FileAnalysisTool fileAnalysisTool;

    @Autowired
    private com.cetc28.tool.KnowledgeSearchTool knowledgeSearchTool;

    @Autowired
    private DocumentOutputTool documentOutputTool;

    @Autowired
    private MapTool mapTool;

    @Autowired
    private DispatchAgentsTool dispatchAgentsTool;

    @Autowired
    private WorkflowTool workflowTool;

    @Autowired
    private McpHubService mcpHubService;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private NacosSkillRepository nacosSkillRepository;

    @Autowired(required = false)
    private LocalSkillRepository localSkillRepository;

    @Autowired(required = false)
    private NacosConfig nacosConfig;

    private volatile boolean initialized = false;

    @PostConstruct
    public void initializeAgents() {
        log.info("=== 动态初始化Agent系统 ===");
        doInitializeAgents();
        initialized = true;
        log.info("=== Agent系统初始化完成: {}个Agent在线 ===", agentRegistry.getEnabledAgentCount());
    }

    public void refreshAgentTools() {
        if (!initialized) return;
        log.info("=== 刷新Agent工具集 ===");
        doInitializeAgents();
        log.info("=== Agent工具集刷新完成 ===");
    }

    private void doInitializeAgents() {
        Toolkit sharedMcp = mcpHubService.getSharedToolkit();
        Map<AgentBase.ToolGroup, Toolkit> toolGroups = buildToolGroups(sharedMcp);

        // 第一遍：注册 Agent + 注入 per-agent skill 仓库
        for (AgentBase agent : agents) {
            String agentId = agent.getAgentId();
            boolean enabled = environment.getProperty(
                    "agent.system.agents." + agentId + ".enabled", boolean.class, true);
            if (!enabled) agent.setEnabled(false);
            agentRegistry.register(agent);

            // 根据 skill-names 注入过滤后的 skill 仓库
            AgentSkillRepository skillRepo = buildFilteredSkillRepository(agentId);
            if (skillRepo != null) {
                agent.setSkillRepository(skillRepo);
            }
        }

        // 第二遍：初始化所有启用 Agent。
        // 顺序约束：先初始化全部 worker，最后初始化 orchestrator（有状态父 Agent），
        // 保证 orchestrator 构建时其依赖的 worker 已就绪。
        // 注意：必须用"Agent 视图"的命名（serverId__tool）比对，不能用共享 Toolkit 的原始名，
        // 否则交集恒为空，启动清单永远显示"（无）"。
        Set<String> mcpToolNames = namespacedMcpToolNames();
        List<AgentBase> enabledAgents = new ArrayList<>();

        AgentBase orchestrator = null;
        for (AgentBase agent : agents) {
            if (!agent.isEnabled()) continue;
            String id = agent.getAgentId();
            if ("orchestrator".equals(id)) {
                orchestrator = agent;
                continue;
            }
            enabledAgents.add(agent);
            initializeAgent(agent, toolGroups, mcpToolNames);
        }
        if (orchestrator != null) {
            enabledAgents.add(orchestrator);
            initializeAgent(orchestrator, toolGroups, mcpToolNames);
        }

        // 打印 Agent 能力清单总览
        log.info("=================== Agent 能力清单 ===================");
        for (AgentBase agent : enabledAgents) {
            Toolkit tk = toolGroups.get(agent.getToolGroup());
            Set<String> agentToolNames = tk != null ? tk.getToolNames() : Set.of();
            List<String> agentMcpTools = agentToolNames.stream()
                    .filter(mcpToolNames::contains)
                    .sorted()
                    .collect(Collectors.toList());
            log.info("Agent[{}] {} → MCP工具: {} | 技能: {}",
                    agent.getAgentId(), agent.getAgentName(),
                    agentMcpTools.isEmpty() ? "（无）" : String.join(", ", agentMcpTools),
                    String.join(", ", agent.getSkillNames()));
        }
        if (!mcpToolNames.isEmpty()) {
            log.info("当前已连接 MCP 服务器: {}", mcpHubService.getServerInfoList());
        }
        log.info("======================================================");
    }

    /** 初始化单个 Agent（构建 HarnessAgent + 注入工具集 + 记录 MCP 工具/技能清单） */
    private void initializeAgent(AgentBase agent, Map<AgentBase.ToolGroup, Toolkit> toolGroups, Set<String> mcpToolNames) {
        Toolkit tk = toolGroups.get(agent.getToolGroup());
        if (tk == null) {
            log.warn("Agent '{}' 工具组 {} 未找到", agent.getAgentId(), agent.getToolGroup());
            tk = toolGroups.get(AgentBase.ToolGroup.BASE);
        }
        agent.initialize(tk);

        Set<String> agentToolNames = tk.getToolNames();
        List<String> agentMcpTools = agentToolNames.stream()
                .filter(mcpToolNames::contains)
                .sorted()
                .collect(Collectors.toList());
        List<String> agentSkills = agent.getSkillNames();

        log.info("Agent '{}' ({}) 初始化完成 | 工具组: {} | MCP工具: {} | 技能: {}",
                agent.getAgentName(), agent.getAgentId(), agent.getToolGroup(),
                agentMcpTools.isEmpty() ? "无" : agentMcpTools,
                agentSkills.isEmpty() ? "无" : agentSkills);
    }

    /**
     * 为指定 Agent 构建过滤后的 skill 仓库
     * @return null 表示该 Agent 无技能配置
     */
    private AgentSkillRepository buildFilteredSkillRepository(String agentId) {
        List<String> allowed = getAllowedSkillNames(agentId);
        if (allowed == null || allowed.isEmpty()) return null; // 未配置或显式禁用

        // 本地技能始终可用；Nacos 只在开启 skill 特性时作为附加来源
        List<AgentSkillRepository> repos = new ArrayList<>();
        if (localSkillRepository != null) {
            repos.add(localSkillRepository);
        }
        if (nacosSkillRepository != null && nacosConfig != null && nacosConfig.isSkillEnabled()) {
            repos.add(nacosSkillRepository);
        }
        if (repos.isEmpty()) return null;

        AgentSkillRepository base = repos.size() == 1 ? repos.get(0) : new CompositeSkillRepository(repos);
        AgentSkillRepository filtered = new FilteredSkillRepository(base, allowed);
        List<String> actualSkills = filtered.getAllSkillNames();
        log.info("Agent '{}' 加载 {} 个技能: [{}]",
                agentId, actualSkills.size(), String.join(", ", actualSkills));
        return filtered;
    }

    /** 从配置读取某个 Agent 的允许技能列表 */
    private List<String> getAllowedSkillNames(String agentId) {
        String raw = environment.getProperty("agent.system.agents." + agentId + ".skill-names");
        if (raw == null) return null; // 未配置 → 不创建 skill 仓库
        if (raw.isBlank()) return List.of();
        if ("*".equals(raw.trim())) return List.of("*");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private Map<AgentBase.ToolGroup, Toolkit> buildToolGroups(Toolkit sharedMcp) {
        EnumMap<AgentBase.ToolGroup, Toolkit> groups = new EnumMap<>(AgentBase.ToolGroup.class);

        groups.put(AgentBase.ToolGroup.NONE, new Toolkit());

        Toolkit base = new Toolkit();
        registerCommonTools(base);
        groups.put(AgentBase.ToolGroup.BASE, base);

        Toolkit withMcp = new Toolkit();
        registerCommonTools(withMcp);
        registerMcpClients(withMcp);
        groups.put(AgentBase.ToolGroup.WITH_MCP, withMcp);

        Toolkit network = new Toolkit();
        registerCommonTools(network);
        network.registerTool(networkPlanTool);
        network.registerTool(schemeQueryTool);
        registerMcpClients(network);
        groups.put(AgentBase.ToolGroup.NETWORK, network);

        // FILE: 基础工具 + 文件分析工具 + MCP（仅供 FileAgent 使用）
        //
        // ⚠️ 隔离约束：文件分析类工具（FileAnalysisTool 及文件处理类 MCP）只能注册在此工具组，
        // 且全工程只有 FileAgent 返回 ToolGroup.FILE（见 FileAgent#getToolGroup）。
        // 禁止把 fileAnalysisTool 加进 BASE / WITH_MCP / NETWORK 等共享工具组 —— 文件能力一旦外泄，
        // orchestrator 会认为其他 Agent 也能处理文件，进而把非文件问题也派给文件Agent。
        // 回归保护见 src/test/.../agent/base/FileToolIsolationTest.java。
        Toolkit file = new Toolkit();
        registerCommonTools(file);
        file.registerTool(fileAnalysisTool);
        registerMcpClients(file);
        groups.put(AgentBase.ToolGroup.FILE, file);

        // KNOWLEDGE: 基础工具 + 知识库检索工具 + MCP（仅供 KnowledgeAgent 使用）
        //
        // 隔离思路与 FILE 组一致：检索类工具只在知识 Agent 手上，避免其它 Agent 在
        // 不该检索的场景里"顺手查一下"，把无关片段塞进上下文反而干扰回答。
        // 注意：对话主链路的自动检索注入不经过工具，见 ChatService#retrieveForChat。
        Toolkit knowledge = new Toolkit();
        registerCommonTools(knowledge);
        knowledge.registerTool(knowledgeSearchTool);
        registerMcpClients(knowledge);
        groups.put(AgentBase.ToolGroup.KNOWLEDGE, knowledge);

        // ORCHESTRATOR: 暴露地图三件套 + dispatch_agents。
        // 统筹 Agent 本身不查资料，但用户直接说“在地图上标出…”时可以直接上图。
        Toolkit orchestrator = new Toolkit();
        orchestrator.registerTool(mapTool);
        orchestrator.registerAgentTool(dispatchAgentsTool);
        groups.put(AgentBase.ToolGroup.ORCHESTRATOR, orchestrator);

        // WORKFLOW: 只暴露 run_workflow，保留原三节点流水线能力
        Toolkit workflow = new Toolkit();
        workflow.registerAgentTool(workflowTool);
        groups.put(AgentBase.ToolGroup.WORKFLOW, workflow);

        return groups;
    }

    /** 所有对话/信息类 Agent 共享的基础工具；地图工具随基础能力一起下发。 */
    private void registerCommonTools(Toolkit toolkit) {
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(externalApiTool);
        toolkit.registerTool(timeTool);
        toolkit.registerTool(documentOutputTool);
        toolkit.registerTool(mapTool);
    }

    /**
     * 将已连接的 MCP 客户端直接注册到目标 Toolkit。
     *
     * 这是 AgentScope 正确的 MCP 集成方式（registerMcpClient 内部生成可执行工具）。
     * 不能只拷贝 Schema（无执行器），也不能拷贝 registerMcpClient 生成的 AgentTool
     * （跨 Toolkit 调用会因内部绑定失效）。直接注册 McpClientWrapper 最可靠。
     */
    /**
     * 与 Agent 工具集对齐的 MCP 工具全名集合（serverId 消毒前缀 + "__" + 原始工具名）。
     *
     * 共享 Toolkit 注册的是原始 McpClientWrapper（工具名不带前缀），而 Agent 工具集
     * 注册的是 {@link AgentMcpAdapter}（带命名空间前缀）。两边命名必须显式对齐，
     * 否则启动清单/本地工具过滤都会失配。
     */
    private Set<String> namespacedMcpToolNames() {
        Map<String, List<McpSchema.Tool>> toolsByServer = mcpHubService.getMcpToolsByServer();
        Set<String> names = new HashSet<>();
        if (toolsByServer == null) return names;
        toolsByServer.forEach((server, tools) -> {
            if (tools == null) return;
            String prefix = AgentMcpAdapter.prefixOf(server);
            for (McpSchema.Tool tool : tools) {
                if (tool != null && tool.name() != null) {
                    names.add(prefix + tool.name());
                }
            }
        });
        return names;
    }

    private void registerMcpClients(Toolkit target) {
        for (var wrapper : mcpHubService.getClients()) {
            try {
                // 用带命名空间前缀的视图注册，避免不同 Server 的同名工具冲突
                target.registerMcpClient(new com.cetc28.mcp.AgentMcpAdapter(
                        wrapper.getName(), wrapper, java.util.Set.of())).block();
                log.debug("Registered MCP client '{}' into agent toolkit", wrapper.getName());
            } catch (Exception e) {
                log.warn("Failed to register MCP client '{}' into agent toolkit: {}",
                        wrapper.getName(), e.getMessage());
            }
        }
    }
}
