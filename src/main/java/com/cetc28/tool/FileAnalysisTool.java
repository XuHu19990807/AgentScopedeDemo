package com.cetc28.tool;

import com.cetc28.rag.KnowledgeBaseService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件分析工具 — 供 Agent 在对话中分析已进入全局知识库的文件
 *
 * 能力：列出已上传文件、识别文件类型、文件内全文检索(RAG)、读取内容块。
 * 注意：对话内直接上传的附件不会写入全局知识库，由 ChatService 提取文本后
 * 注入上下文（见 ChatFileService / FileContextBuilder）；本工具只面向知识库文件。
 */
@Component
public class FileAnalysisTool {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Tool(description = "列出知识库中已上传的所有文件（名称、大小、文本块数）")
    public String listUploadedFiles() {
        List<KnowledgeBaseService.FileMeta> files = knowledgeBaseService.listUploadedFiles();
        if (files.isEmpty()) {
            return "当前知识库中没有已上传的文件。请先上传文件。";
        }
        return files.stream()
                .map(f -> String.format("  - %s（%s，%d 个文本块%s）",
                        f.filename(), f.formattedSize(), f.chunkCount(),
                        f.handledBy() != null && !f.handledBy().isBlank()
                                ? "，由 " + f.handledBy() + " 处理，无 embedding 向量" : ""))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "识别文件类型，给出处理建议和可用的处理方式")
    public String detectFileType(
            @ToolParam(name = "filename", description = "文件名（含扩展名）") String filename) {
        String type = knowledgeBaseService.detectFileType(filename);
        String suggestion;
        String lower = filename != null ? filename.toLowerCase() : "";
        if (lower.endsWith(".pdf")) {
            suggestion = "PDF 文档。适合全文阅读、段落提取。可调用 PDF 处理 MCP 工具进行文档解析。";
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            suggestion = "Excel 表格。适合数值分析、数据统计。可调用表格处理 MCP 工具进行数据提取、报表生成。";
        } else if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            suggestion = "Word 文档。适合文本阅读。可调用文档处理 MCP 工具进行内容摘要。";
        } else if (lower.endsWith(".txt")) {
            suggestion = "纯文本文件。适合全文搜索、信息检索。可用知识库 RAG 全文检索。";
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            suggestion = "Markdown 文档。适合全文检索、标题/段落摘要。";
        } else if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            suggestion = "CSV/TSV 数据表格。适合数据分析。可调用表格处理 MCP 工具。";
        } else if (lower.endsWith(".json") || lower.endsWith(".jsonl")) {
            suggestion = "JSON 结构化数据。适合解析字段、提取结构化信息。";
        } else if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            suggestion = "YAML 配置。适合读取配置项与层级结构。";
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            suggestion = "HTML 网页。正文文本可检索；脚本/样式在提取时已移除。";
        } else if (lower.endsWith(".xml")) {
            suggestion = "XML 结构化数据。文本可检索，标签/属性在提取时会被去除。";
        } else {
            suggestion = "通用文件。建议先用知识库 RAG 全文检索了解内容，再决定处理方式。";
        }
        return String.format("""
                文件类型识别: %s

                【处理建议】
                %s

                【处理步骤】
                1. 用 searchInFile 在文件内检索用户关心的关键词
                2. 用 getFileContent 读取相关内容块
                3. 根据内容判断是否需要调用对应的 MCP 工具进行进一步处理
                """, type, suggestion);
    }

    @Tool(description = "在指定文件中全文检索关键词（知识库 RAG），返回匹配的内容块")
    public String searchInFile(
            @ToolParam(name = "filename", description = "文件名（含扩展名）") String filename,
            @ToolParam(name = "query", description = "检索关键词") String query,
            @ToolParam(name = "limit", description = "返回条数，默认5") int limit) {
        List<KnowledgeBaseService.SearchResultEntry> results =
                knowledgeBaseService.searchInFile(filename, query, limit <= 0 ? 5 : limit);
        if (results.isEmpty()) {
            return String.format("文件 '%s' 中未找到与 '%s' 相关的内容。", filename, query);
        }
        return results.stream()
                .map(r -> String.format("  [第%d行] %s", r.lineNumber(), r.content()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "读取指定文件的内容块（前若干行），了解文件大致内容")
    public String getFileContent(
            @ToolParam(name = "filename", description = "文件名（含扩展名）") String filename,
            @ToolParam(name = "maxLines", description = "最多返回行数，默认20") int maxLines) {
        List<KnowledgeBaseService.ChunkEntry> chunks = knowledgeBaseService.getFileChunks(filename);
        if (chunks.isEmpty()) {
            return String.format("文件 '%s' 暂无内容或不存在。", filename);
        }
        int limit = maxLines <= 0 ? 20 : maxLines;
        return chunks.stream()
                .limit(limit)
                .map(c -> String.format("  [第%d行] %s", c.lineNumber(), c.content()))
                .collect(Collectors.joining("\n"));
    }
}
