package com.cetc28.rag;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询扩展（可选，默认关闭）：混合检索分数偏低时，让模型把问题改写成 1-2 条检索式，
 * 再用这些检索式补召回。只补召回、不改变原查询的名次，避免改写本身带偏结果。
 */
@Component
public class RagQueryExpander {

    private static final Logger log = LoggerFactory.getLogger(RagQueryExpander.class);

    private static final String PROMPT = """
            你是知识库检索查询改写助手。把用户问题改写成 %d 条适合全文/向量检索的短查询。
            要求：保留专有名词与数字；每条一行；不要编号、不要解释、不要输出任何多余文字。
            用户问题：%s
            """;

    private final com.cetc28.modelconfig.ModelRuntime modelRuntime;
    private final RagProperties props;

    public RagQueryExpander(com.cetc28.modelconfig.ModelRuntime modelRuntime, RagProperties props) {
        this.modelRuntime = modelRuntime;
        this.props = props;
    }

    /** 返回改写后的查询（不含原查询）；未启用或失败时返回空列表。 */
    public List<String> expand(String query) {
        if (!props.getQueryExpansion().isEnabled() || query == null || query.isBlank()) return List.of();
        int maxQueries = Math.max(1, props.getQueryExpansion().getMaxQueries());
        try {
            Msg message = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(PROMPT.formatted(maxQueries, query))
                    .build();
            ChatResponse response = modelRuntime.modelFor("rag-query-expander")
                    .stream(List.of(message), List.of(), null)
                    .takeLast(1)
                    .blockLast(Duration.ofMillis(Math.max(300, props.getQueryExpansion().getTimeoutMs())));
            if (response == null || response.getContent() == null) return List.of();
            StringBuilder text = new StringBuilder();
            for (Object block : response.getContent()) {
                if (block instanceof TextBlock textBlock) text.append(textBlock.getText()).append('\n');
            }
            Set<String> queries = new LinkedHashSet<>();
            for (String line : text.toString().split("\\R")) {
                String cleaned = line.replaceFirst("^[\\s\\d.、)）-]+", "").trim();
                if (cleaned.length() < 2 || cleaned.length() > 120) continue;
                if (cleaned.equalsIgnoreCase(query.trim())) continue;
                queries.add(cleaned);
                if (queries.size() >= maxQueries) break;
            }
            return new ArrayList<>(queries);
        } catch (Exception e) {
            log.debug("查询扩展失败（忽略）：{}", e.getMessage());
            return List.of();
        }
    }
}
