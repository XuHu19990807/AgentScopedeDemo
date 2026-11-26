package com.cetc28.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 混合检索的融合与过滤逻辑（不依赖 Redis） */
class HybridFusionTest {

    @Test
    void rrfSumsBothListsAndKeepsOrder() {
        var scores = HybridRetriever.fuseKeys(List.of("a", "b"), List.of("b", "c"), 60);

        assertEquals(1.0 / 61, scores.get("a"), 1e-9, "a 只在 BM25 第 1 位");
        assertEquals(1.0 / 62 + 1.0 / 61, scores.get("b"), 1e-9, "b 在两路都出现，分数应相加");
        assertEquals(1.0 / 62, scores.get("c"), 1e-9, "c 只在向量第 2 位");
        assertEquals(-1.0 / 61, -scores.get("a"), 1e-9);
    }

    @Test
    void rrfHandlesEmptyAndSingleLists() {
        assertTrue(HybridRetriever.fuseKeys(List.of(), List.of(), 60).isEmpty());
        assertEquals(1, HybridRetriever.fuseKeys(List.of("x"), List.of(), 60).size());
        assertEquals(1, HybridRetriever.fuseKeys(List.of(), List.of("y"), 60).size());
        // 同一 key 在不同排名出现时，各名次分数累加
        var dup = HybridRetriever.fuseKeys(List.of("z", "z"), List.of("z"), 60);
        assertEquals(1.0 / 61 + 1.0 / 62 + 1.0 / 61, dup.get("z"), 1e-9);
    }

    @Test
    void normalizeCollapsesWhitespaceAndCapsLength() {
        assertEquals("北京 数据中心", HybridRetriever.normalize("  北京 \n\t 数据中心  "));
        String longQuery = "测".repeat(500);
        assertEquals(200, HybridRetriever.normalize(longQuery).length(),
                "检索 query 必须截断，防止超长输入打爆 query 解析");
        assertEquals("", HybridRetriever.normalize(null));
    }

    @Test
    void retrievalResultEmptyCarriesReason() {
        RetrievalResult empty = RetrievalResult.empty("预算多少", "embedding 不可用");
        assertTrue(empty.isEmpty());
        assertTrue(empty.degraded(), "降级必须被标记，前端才能提示");
        assertEquals("embedding 不可用", empty.degradedReason());
        assertEquals(0, empty.hits().size());
    }

    @Test
    void hitLocationPrefersHeadingAndLineRange() {
        RetrievalResult.Hit hit = new RetrievalResult.Hit("doc:knowledge:abc:3", "正文",
                "方案.txt", "预算", 12, 18, 0.83, "hybrid");
        assertTrue(hit.location().contains("预算"));
        assertTrue(hit.location().contains("12-18"), "行号区间必须出现在引用位置里");
        assertEquals("正文", hit.snippet(4), "短内容不应被截断");
    }
}
