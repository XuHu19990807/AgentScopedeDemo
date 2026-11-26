package com.cetc28.agentchat.base;

import com.cetc28.mcp.McpHubService;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件分析Agent — 分析对话中上传的文件，结合知识库 RAG + Skill + MCP 处理
 *
 * 工作流：
 * 1. listUploadedFiles 查看已上传文件
 * 2. detectFileType 识别文件类型（扩展名 + 内容）
 * 3. searchInFile / getFileContent 用 RAG 检索文件内容
 * 4. 加载 Nacos Skill 获取处理指南
 * 5. 调用对应 MCP 工具解决问题
 */
@Component
public class FileAgent extends AgentBase {

    private static final String FALLBACK_PROMPT = """
            你是文件分析Agent，负责分析用户上传的文件并解决相关问题。

            处理流程：
            1. 用 listUploadedFiles 查看知识库中已上传的文件（用户可能在对话中上传了文件）
            2. 用 detectFileType 识别文件类型，了解处理建议
            3. 用 searchInFile 在文件内检索用户关心的关键词（RAG 全文检索）
            4. 用 getFileContent 读取相关内容块
            5. 根据文件内容和类型，调用对应的 MCP 工具完成处理

            可用工具：
            <!--LOCAL_TOOLS-->

            请根据用户问题，结合文件类型和文件内容给出准确回答。
            """;

    @Autowired
    private McpHubService mcpHubService;

    public FileAgent(Model chatModel,
                     @Value("${agent.system.agents.file.temperature:0.3}") double temperature) {
        super("file", "文件分析Agent", "分析上传文件，结合知识库RAG与技能处理",
                chatModel, temperature);
    }

    @Override
    public ToolGroup getToolGroup() { return ToolGroup.FILE; }

    @Override
    public void initialize(Toolkit toolkit) {
        setToolkit(toolkit);
        String prompt = loadAgentPrompt(toolkit, FALLBACK_PROMPT);
        this.harnessAgent = createWorkerAgent("FileAgent", prompt, toolkit);
        log.info("文件分析Agent initialized — MCP工具: {}, skillRepository={}",
                mcpHubService.getSharedToolkit().getToolSchemas().size(),
                skillRepository != null ? skillRepository.getSource() : "none");
        watchNacosPrompt(() -> initialize(toolkit));
    }
}
