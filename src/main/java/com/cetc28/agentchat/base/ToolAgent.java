package com.cetc28.agentchat.base;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具Agent — HarnessAgent worker（支持 Nacos skill，无会话持久化）
 */
@Component
public class ToolAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是工具Agent，负责调用各种外部工具和API来完成用户请求。
            可用工具：
            <!--LOCAL_TOOLS-->
            请根据用户需求选择合适的工具并调用，如果工具不足以完成任务，请说明限制。
            """;

    public ToolAgent(Model chatModel,
                     @Value("${agent.system.agents.tool.temperature:0.2}") double temperature) {
        super("tool", "工具Agent", "外部工具与API调用",
                chatModel, temperature);
    }

    /**
     * 工具Agent 负责"外部工具与 API 调用"，MCP 工具正是外部工具的主要来源，
     * 因此使用 WITH_MCP 工具组（本地工具 + 已连接的 MCP 工具）。
     */
    @Override
    public ToolGroup getToolGroup() { return ToolGroup.WITH_MCP; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("ToolAgent", prompt, toolkit);
        log.info("工具Agent initialized, skillRepository={}",
                skillRepository != null ? skillRepository.getSource() : "none");
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
