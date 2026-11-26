package com.cetc28.agentchat.base;

import com.cetc28.map.MapPrompt;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 统筹Agent — HarnessAgent（带 Redis 会话持久化 + compaction + skill）
 *
 * 提示词从 Nacos 加载（dataId=orchestrator.json, group=nacos-ai-prompt），
 * Nacos 不可用时降级为本地 prompts/orchestrator.txt。
 */
@Component
public class OrchestratorAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是统筹Agent —— 多智能体协作的调度中心。你的职责不是亲自查资料，而是判断
            该由哪些子智能体完成，然后用 dispatch_agents 工具分派，最后综合它们的输出回答用户。

            可调度子智能体：
            <!--AGENT_LIST-->

            决策规则：
            1. 能直接回答的简单问题（闲聊、常识、对上一轮的改写/总结）直接回答，不要调用工具。
            2. 企业文档事实/规范/合同条款 → knowledge；实时或外部数据、计算、API → tool；
               组网/拓扑/带宽/设备选型 → network-planning；分析上传文件 → file；
               需要固定流水线产出完整方案 → workflow。
            3. 只选必要的子智能体；单个能完成就不要拆成多个。
            4. 子智能体看不到本对话历史，instruction 必须自包含：背景、目标、已知信息、
               期望输出格式。需要前序结果时，把前序输出摘录进下一条 instruction。
            5. 任务之间无依赖时 parallel=true；有先后依赖时 parallel=false，按 tasks 顺序执行。
            6. 拿到 results 后写最终回答：合并重复、保留 [K1] 引用、不要暴露 JSON 或内部字段，
               不要逐字复述子智能体全文。

            计划模式（可选）：复杂任务可先 plan_enter，把计划写入 plan_write，完成后 plan_exit；
            简单任务不要使用。
            """;

    @Autowired
    private AgentRegistry agentRegistry;

    /** 是否启用计划模式（决定是否提示词追加 plan_enter/plan_write/plan_exit 说明） */
    @Value("${agent.harness.plan-mode-enabled:true}")
    private boolean planModeEnabled;

    public OrchestratorAgent(Model chatModel,
                             @Value("${agent.system.agents.orchestrator.temperature:0.3}") double temperature) {
        super("orchestrator", "统筹Agent", "中央调度，任务分解与分配",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.ORCHESTRATOR; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String agentList = agentRegistry.getEnabledAgents().stream()
                .filter(a -> !a.getAgentId().equals("orchestrator"))
                .filter(a -> !a.getAgentId().equals("chat"))
                .filter(a -> !a.getAgentId().equals("deep"))
                .map(a -> String.format("  - %s (%s): %s", a.getAgentName(), a.getAgentId(), a.getDescription()))
                .collect(Collectors.joining("\n"));

        // 1. 从 Nacos 加载模板（Nacos → 本地 prompts/orchestrator.txt → 硬编码兜底）
        String template = null;
        if (nacosPromptManager != null) {
            template = nacosPromptManager.getPrompt(agentId);
        }
        // 2.0 架构下统筹 Agent 必须通过 dispatch_agents 派发子智能体。
        // 旧版 Nacos 提示词要求“只输出 JSON 计划”，会覆盖本地新提示词并导致编排失效，
        // 因此这里做一次能力校验：不含 dispatch_agents 的远端模板直接丢弃。
        if (template != null && !template.isBlank() && !template.contains("dispatch_agents")) {
            log.warn("Nacos prompt for '{}' does not mention dispatch_agents (legacy JSON-plan prompt); "
                    + "falling back to local prompt", agentId);
            template = nacosPromptManager.loadLocalPrompt(agentId);
        }
        if (template == null || template.isBlank()) {
            template = FALLBACK_PROMPT;
        }
        // 2. 替换 Agent 列表占位符
        String prompt = template.replace("<!--AGENT_LIST-->", agentList);

        // 2.5 计划模式说明：复杂任务把计划落到 workspace/plans/，便于用户审阅与后续追踪
        if (planModeEnabled) {
            prompt = prompt + "\n\n"
                    + "计划管理：复杂任务可先 plan_enter 进入计划模式，用 plan_write 把「目标/子任务/"
                    + "负责 Agent/验收标准」写入计划文件，任务完成后再 plan_exit。";
        }

        // 3. HarnessAgent（有状态）：会话持久化 + compaction + Nacos skill
        //    统筹 Agent 也持有地图工具，补一段地图规则，避免 Nacos 旧模板缺少上图说明。
        prompt = MapPrompt.ensure(prompt);
        this.harnessAgent = createHarnessAgent("OrchestratorAgent", prompt, toolkit);
        log.info("统筹Agent initialized with HarnessAgent, skillRepository={}",
                skillRepository != null ? skillRepository.getSource() : "none");

        // 4. Nacos 热更新监听
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
