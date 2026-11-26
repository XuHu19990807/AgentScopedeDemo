package com.cetc28.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把检索结果组装成注入给模型的可读上下文，并生成引用列表。
 *
 * <p>上下文里显式给出引用规则（只依据片段、标注 [K#]、证据不足就承认），
 * 因为这比在系统提示词里泛泛写"可以检索知识库"更能约束模型不编造。
 */
@Component
public class RagContextBuilder {

    private final RagProperties props;

    public RagContextBuilder(RagProperties props) {
        this.props = props;
    }

    public record InjectedContext(String promptBlock, List<Citation> citations,
                                  boolean degraded, String degradedReason) {
        public boolean isEmpty() {
            return citations == null || citations.isEmpty();
        }

        public static InjectedContext none(String reason) {
            return new InjectedContext("", List.of(), reason != null, reason);
        }
    }

    public InjectedContext build(RetrievalResult result) {
        if (result == null) return InjectedContext.none(null);
        if (result.isEmpty()) {
            return new InjectedContext("", List.of(), result.degraded(), result.degradedReason());
        }

        int maxChars = Math.max(500, props.getInject().getMaxChars());
        StringBuilder block = new StringBuilder();
        block.append("[知识库检索结果]\n")
                .append("以下是从内部知识库检索到的资料片段，是回答该问题的依据。要求：\n")
                .append("1. 只依据这些片段作答，不要补充片段中没有的事实；\n")
                .append("2. 引用某条片段时，在句末用编号标注来源，例如 [K1]；\n")
                .append("3. 如果片段不足以回答，直接说明「知识库中没有找到足够的信息」，不要编造。\n");

        List<Citation> citations = new ArrayList<>();
        int index = 0;
        for (RetrievalResult.Hit hit : result.hits()) {
            String id = "K" + (++index);
            String location = hit.location();
            StringBuilder header = new StringBuilder("\n[").append(id).append("] 来源: ")
                    .append(blankToFallback(hit.filename(), "未命名文件"));
            if (!location.isBlank()) header.append(" · ").append(location);
            header.append(String.format(" (相关度 %.2f)", hit.score()));

            String body = hit.content() == null ? "" : hit.content().trim();
            int budget = maxChars - block.length() - header.length() - 2;
            if (budget <= 80) break;
            if (body.length() > budget) body = body.substring(0, budget) + "…";

            block.append(header).append('\n').append(body).append('\n');
            citations.add(new Citation(id, hit.chunkId(), hit.filename(), location,
                    hit.score(), hit.snippet(120)));
        }
        return new InjectedContext(block.toString(), citations, result.degraded(), result.degradedReason());
    }

    /** 给 orchestrator 看的检索摘要（不塞全文，避免挤占规划上下文） */
    public String summarize(RetrievalResult result) {
        if (result == null || result.isEmpty()) return "";
        Set<String> files = new LinkedHashSet<>();
        for (RetrievalResult.Hit hit : result.hits()) {
            files.add(blankToFallback(hit.filename(), "未命名文件"));
        }
        StringBuilder sb = new StringBuilder("[知识库检索摘要] 命中 ")
                .append(result.hits().size()).append(" 条，来源文件：")
                .append(String.join("、", files))
                .append("。相关片段：");
        int count = 0;
        for (RetrievalResult.Hit hit : result.hits()) {
            if (count++ >= 3) break;
            sb.append("\n- ").append(hit.snippet(80));
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
