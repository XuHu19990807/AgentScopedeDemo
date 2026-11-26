package com.cetc28.agentchat.base;

import com.cetc28.mcp.McpHubService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 信息查询Agent — HarnessAgent worker（支持 Nacos skill，无会话持久化）
 */
@Component
public class BasicInfoAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是信息查询Agent，负责通过各类服务和工具查询基本信息。
            可用工具：
            <!--LOCAL_TOOLS-->
            请根据用户的需求，选择合适的工具获取信息并整理回答。
            """;

    @Autowired
    private McpHubService mcpHubService;

    public BasicInfoAgent(Model chatModel,
                          @Value("${agent.system.agents.basic-info.temperature:0.3}") double temperature) {
        super("basic-info", "信息查询Agent", "通过MCP服务查询天气预报、新闻资讯、实时数据等基本信息",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.WITH_MCP; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("BasicInfoAgent", prompt, toolkit);
        log.info("信息查询Agent initialized — MCP工具: {}, skillRepository={}",
                mcpHubService.getSharedToolkit().getToolSchemas().size(),
                skillRepository != null ? skillRepository.getSource() : "none");
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
