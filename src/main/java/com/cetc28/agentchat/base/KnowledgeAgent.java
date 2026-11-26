package com.cetc28.agentchat.base;

import com.cetc28.mcp.McpHubService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识Agent — HarnessAgent worker（支持 Nacos skill，无会话持久化）
 *
 * <p>工具集为 {@link ToolGroup#KNOWLEDGE}：基础工具 + 知识库检索工具 + MCP。
 * 对话主链路（CHAT/DEEP）会在进入模型前自动检索并注入片段；本 Agent 走的是
 * agentic 路径——模型可以自己改写查询、多轮检索、回读原文，适合需要交叉核对的复杂问题。
 */
@Component
public class KnowledgeAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是知识Agent，负责内部知识库的检索与基于证据的回答 (RAG)。

            工作方式：
            1. 拿到问题后，先用 searchKnowledge 检索（可以把用户问题改写成更利于检索的关键词，例如拆出型号、金额、时间）；
            2. 一次没查到时，换用同义词、上级概念或更短的词再查一次，最多 3 次；
            3. 需要核对引用是否准确时，用 readChunk 回读原文；
            4. 不确定知识库里有什么时，用 knowledgeOverview 查看文件清单。

            回答规则：
            - 只依据检索到的片段回答，句末用 [K1]、[K2] 标注依据；
            - 片段不足时明确说明「知识库中没有找到足够的信息」，不要用常识补全；
            - 引用要能对应到具体文件与位置，便于人工复核。

            可用工具：
            <!--LOCAL_TOOLS-->
            """;

    @Autowired
    private McpHubService mcpHubService;

    public KnowledgeAgent(Model chatModel,
                          @Value("${agent.system.agents.knowledge.temperature:0.5}") double temperature) {
        super("knowledge", "知识Agent", "知识检索与增强生成 (RAG)",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.KNOWLEDGE; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("KnowledgeAgent", prompt, toolkit);
        log.info("知识Agent initialized — 工具数: {}, MCP工具: {}, skillRepository={}",
                toolkit.getToolSchemas().size(),
                mcpHubService.getSharedToolkit().getToolSchemas().size(),
                skillRepository != null ? skillRepository.getSource() : "none");
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
