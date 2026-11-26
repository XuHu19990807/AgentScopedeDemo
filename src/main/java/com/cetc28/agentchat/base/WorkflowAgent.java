package com.cetc28.agentchat.base;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工作流 Agent — 通过 {@code run_workflow} 工具触发内置三节点流水线，
 * 不再覆写框架 Agent 的 process/processStream（那是 2.0 中不存在的旁路）。
 */
@Component
public class WorkflowAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是工作流 Agent。当用户需要按固定流水线（需求分析 → 信息检索 → 方案生成）
            产出一份结构化方案时，调用 run_workflow 工具，把用户请求完整传入；
            拿到工具结果后，整理成条理清晰的最终回答。不要臆造工具未返回的内容。
            """;

    public WorkflowAgent(Model chatModel,
                         @Value("${agent.system.agents.workflow.temperature:0.3}") double temperature) {
        super("workflow", "工作流Agent", "执行多步骤工作流，每节点流式输出",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.WORKFLOW; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("WorkflowAgent", prompt, toolkit);
        log.info("工作流Agent initialized — 通过 run_workflow 工具驱动, skillRepository={}",
                skillRepository != null ? skillRepository.getSource() : "none");
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
