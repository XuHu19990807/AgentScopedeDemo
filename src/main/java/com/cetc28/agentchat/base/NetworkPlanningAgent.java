package com.cetc28.agentchat.base;

import com.cetc28.mcp.McpHubService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 信息规划Agent — HarnessAgent worker（支持 Nacos skill，无会话持久化）
 */
@Component
public class NetworkPlanningAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是信息规划Agent，负责链路信息规划，按工作流分步处理。
            可用工具：
            <!--LOCAL_TOOLS-->
            请使用可用工具完成距离计算、设备推荐、子网规划、成本估算等任务。
            """;

    @Autowired
    private McpHubService mcpHubService;

    public NetworkPlanningAgent(Model chatModel,
                                @Value("${agent.system.agents.network-planning.temperature:0.4}") double temperature) {
        super("network-planning", "信息规划Agent", "链路信息规划，按工作流分步处理",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.NETWORK; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("NetworkPlanningAgent", prompt, toolkit);
        log.info("信息规划Agent initialized — 本地5工具, MCP%d个, skillRepository=%s".formatted(
                mcpHubService.getSharedToolkit().getToolSchemas().size(),
                skillRepository != null ? skillRepository.getSource() : "none"));
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
