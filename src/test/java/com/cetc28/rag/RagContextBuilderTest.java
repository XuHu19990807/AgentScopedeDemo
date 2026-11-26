package com.cetc28.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 注入块的格式契约：[K1] 编号、来源、行号、以及"无依据就说没有"的硬约束 */
class RagContextBuilderTest {

    private static RagContextBuilder builder(int maxChars) {
        RagProperties props = new RagProperties();
        props.getInject().setMaxChars(maxChars);
        return new RagContextBuilder(props);
    }

    @Test
    void buildsNumberedCitationsWithSources() {
        RetrievalResult result = new RetrievalResult("预算多少",
                List.of(
                        new RetrievalResult.Hit("doc:knowledge:a:1", "预算约 285 万元，含光纤铺设。",
                                "方案.txt", "预算", 12, 18, 0.83, "hybrid"),
                        new RetrievalResult.Hit("doc:knowledge:b:2", "带宽需扩容到 4000Mbps。",
                                "需求.txt", "", 3, 5, 0.61, "bm25")),
                false, null, 42, 10, 4);

        RagContextBuilder.InjectedContext ctx = builder(6000).build(result);

        assertFalse(ctx.isEmpty());
        assertEquals(2, ctx.citations().size());
        assertEquals("K1", ctx.citations().get(0).id());
        assertEquals("K2", ctx.citations().get(1).id());
        assertEquals("doc:knowledge:a:1", ctx.citations().get(0).chunkId(), "引用必须能回查到块 key");
        assertEquals("方案.txt", ctx.citations().get(0).filename());

        String block = ctx.promptBlock();
        assertTrue(block.contains("[K1] 来源: 方案.txt"), "引用头部格式必须稳定，前端按它解析");
        assertTrue(block.contains("第 12-18 行"), "必须带行号，便于人工核对");
        assertTrue(block.contains("相关度 0.83"));
        assertTrue(block.contains("只依据这些片段作答"), "缺少防编造规则");
        assertTrue(block.contains("知识库中没有找到足够的信息"), "缺少证据不足时的兜底话术");
    }

    @Test
    void emptyResultProducesNoBlock() {
        RagContextBuilder.InjectedContext ctx = builder(6000)
                .build(RetrievalResult.empty("预算多少", null));
        assertTrue(ctx.isEmpty());
        assertEquals("", ctx.promptBlock());
        assertFalse(ctx.degraded());
    }

    @Test
    void degradationIsPropagated() {
        RagContextBuilder.InjectedContext ctx = builder(6000)
                .build(RetrievalResult.empty("预算多少", "embedding 不可用，已降级为全文检索"));
        assertTrue(ctx.degraded());
        assertEquals("embedding 不可用，已降级为全文检索", ctx.degradedReason());
    }

    @Test
    void budgetTruncatesTailHits() {
        String longContent = "很长的正文内容。".repeat(400);
        RetrievalResult result = new RetrievalResult("问",
                List.of(
                        new RetrievalResult.Hit("k1", longContent, "a.txt", "", 1, 1, 1.0, "bm25"),
                        new RetrievalResult.Hit("k2", longContent, "b.txt", "", 2, 2, 0.9, "bm25")),
                false, null, 1, 2, 0);

        RagContextBuilder.InjectedContext ctx = builder(600).build(result);
        assertTrue(ctx.promptBlock().length() <= 900,
                "注入块必须受 maxChars 约束，实际 " + ctx.promptBlock().length());
        assertTrue(ctx.citations().size() < 2, "预算不足时应放弃尾部片段而不是无限增长");
    }

    @Test
    void summarizeMentionsFilesAndTopFragments() {
        RetrievalResult result = new RetrievalResult("预算多少",
                List.of(new RetrievalResult.Hit("k1", "预算约 285 万元。", "方案.txt", "预算", 12, 12, 0.8, "hybrid")),
                false, null, 5, 1, 1);
        String summary = builder(6000).summarize(result);
        assertTrue(summary.contains("方案.txt"));
        assertTrue(summary.contains("285"), "摘要里要有一句可判断的相关片段");
        assertEquals("", builder(6000).summarize(RetrievalResult.empty("x", null)));
    }
}
