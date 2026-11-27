package com.cetc28.tool;

import com.cetc28.identity.KnowledgeScopeService;
import com.cetc28.rag.ChunkView;
import com.cetc28.rag.KnowledgeBaseService;
import com.cetc28.rag.KnowledgeRetriever;
import com.cetc28.rag.RetrievalResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索工具 — 供 KnowledgeAgent 在对话中主动多轮检索（agentic RAG）。
 *
 * <p>与自动注入（{@code ChatService} 在对话开始前检索一次）互补：
 * 自动注入解决"一问一答"的常见情况；本工具让模型可以改写查询、追问细节、
 * 回读原文，适合"先查清单再逐个核对"这类任务。
 *
 * <p>返回文本里同时给出 [K#] 编号与 chunkId，模型既能按编号引用，也能用
 * {@code readChunk} 回读原文核对。
 */
@Component
public class KnowledgeSearchTool {

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeScopeService knowledgeScopeService;

    @Tool(description = "在内部知识库中检索资料（关键词 + 语义混合检索），返回带编号的片段与来源。"
            + "回答时必须用 [K1]、[K2] 的形式标注依据；若结果为空，说明知识库中确实没有相关资料，不要编造。")
    public String searchKnowledge(
            @ToolParam(name = "query", description = "检索问题或关键词，可以是用户原问题或改写后的查询") String query,
            @ToolParam(name = "topK", description = "返回片段条数，默认 5，最大 10") int topK,
            RuntimeContext context) {

        if (query == null || query.isBlank()) return "检索词为空，请给出要查询的问题或关键词。";
        String userId = userIdOf(context);
        KnowledgeScopeService.Scope scope = knowledgeScopeService.scopeFor(userId);
        if (scope.denyAll()) {
            return "当前账号没有知识库访问授权，无法检索知识库。请告知用户联系管理员分配知识域权限。";
        }
        Set<String> domains = scope.admin() ? null : scope.domains();
        RetrievalResult result = retriever.retrieveInDomains(
                query, KnowledgeRetriever.Mode.HYBRID, -1, null, domains);
        if (result.isEmpty()) {
            String reason = result.degradedReason() == null ? "" : "（" + result.degradedReason() + "）";
            return "知识库中没有检索到与「" + query + "」相关的内容" + reason + "。"
                    + "请如实告知用户知识库缺少该信息，不要编造。";
        }

        int limit = topK <= 0 ? 5 : Math.min(topK, 10);
        StringBuilder sb = new StringBuilder("知识库检索结果（query=").append(query)
                .append("，命中 ").append(result.hits().size()).append(" 条，耗时 ")
                .append(result.tookMs()).append("ms）");
        if (result.degraded()) {
            sb.append("\n注意：").append(result.degradedReason());
        }
        int index = 0;
        for (RetrievalResult.Hit hit : result.hits()) {
            if (index >= limit) break;
            String id = "K" + (++index);
            sb.append("\n\n[").append(id).append("] 来源: ")
                    .append(hit.filename() == null || hit.filename().isBlank() ? "未命名文件" : hit.filename());
            String location = hit.location();
            if (!location.isBlank()) sb.append(" · ").append(location);
            sb.append(String.format("（相关度 %.2f，chunk=%s）", hit.score(), hit.chunkId()));
            sb.append('\n').append(hit.content() == null ? "" : hit.content().trim());
        }
        sb.append("\n\n引用规则：只依据以上片段作答，句末标注 [K1] 这样的编号；"
                + "片段不足时直接说明「知识库中没有足够的信息」。");
        return sb.toString();
    }

    @Tool(description = "按 chunkId 回读知识库中某个文本块的原文（chunkId 来自 searchKnowledge 结果中的 chunk=...），"
            + "用于核对引用是否准确")
    public String readChunk(
            @ToolParam(name = "chunkId", description = "文本块 key，形如 doc:knowledge:{docId}:{序号}") String chunkId,
            RuntimeContext context) {
        if (chunkId == null || chunkId.isBlank()) return "chunkId 为空。";
        ChunkView hit = retriever.readChunk(chunkId.trim());
        if (hit == null) return "未找到文本块：" + chunkId + "（可能已被删除或重新上传）。";
        if (!knowledgeScopeService.canRead(userIdOf(context), hit.effectiveDomain())) {
            return "无权访问该文本块：它所属的知识域不在当前账号的授权范围内。";
        }
        return "来源: " + (hit.filename() == null ? "未知" : hit.filename())
                + (hit.headingPath() == null || hit.headingPath().isBlank() ? "" : " · " + hit.headingPath())
                + (hit.lineStart() > 0 ? " · 第 " + hit.lineStart() + "-" + hit.lineEnd() + " 行" : "")
                + "\n\n" + hit.content();
    }

    @Tool(description = "查看知识库总体情况：已索引文件数、文本块数、向量检索是否可用。"
            + "在需要判断「知识库里到底有什么」时调用")
    public String knowledgeOverview(RuntimeContext context) {
        KnowledgeScopeService.Scope scope = knowledgeScopeService.scopeFor(userIdOf(context));
        if (scope.denyAll()) {
            return "当前账号没有知识库访问授权，无法查看知识库内容。";
        }
        KnowledgeBaseService.KnowledgeStats stats = knowledgeBaseService.stats();
        List<KnowledgeBaseService.FileMeta> allFiles = knowledgeBaseService.listUploadedFiles();
        List<KnowledgeBaseService.FileMeta> files = scope.admin() ? allFiles : filterByDomains(allFiles, scope.domains());
        StringBuilder sb = new StringBuilder("知识库概况：")
                .append(files.size()).append(" 个文件，")
                .append(stats.chunks()).append(" 个文本块，")
                .append(stats.vectors() + " 个已生成向量")
                .append("；向量检索").append(stats.vectorReady() ? "可用" : "不可用（仅全文检索）");
        if (!scope.admin()) {
            sb.append("（仅统计当前账号可访问的知识域）");
        }
        if (files.isEmpty()) {
            sb.append("\n当前账号可访问的知识库为空，或尚未上传文档。");
            return sb.toString();
        }
        sb.append("\n文件清单：");
        for (KnowledgeBaseService.FileMeta file : files) {
            sb.append("\n  - ").append(file.filename())
                    .append("（").append(file.formattedSize()).append("，")
                    .append(file.chunkCount()).append(" 个文本块）");
        }
        return sb.toString();
    }

    private static List<KnowledgeBaseService.FileMeta> filterByDomains(
            List<KnowledgeBaseService.FileMeta> files, Set<String> domains) {
        List<KnowledgeBaseService.FileMeta> result = new ArrayList<>();
        if (files == null) return result;
        for (KnowledgeBaseService.FileMeta file : files) {
            if (domains.contains(file.effectiveDomain())) result.add(file);
        }
        return result;
    }

    private static String userIdOf(RuntimeContext context) {
        return context == null ? null : context.getUserId();
    }
}
