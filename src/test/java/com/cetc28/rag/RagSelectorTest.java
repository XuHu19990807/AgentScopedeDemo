package com.cetc28.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选择阶段（去重 + 同标题/同文档配额 + 截断）的单元测试。
 */
class RagSelectorTest {

    private final RagSelector selector = new RagSelector();

    private static ChunkView view(int index, String filename, String heading, String content, double score) {
        return new ChunkView("doc:knowledge:x:" + index, "x", index, content, content,
                filename, heading, 1, 2, score);
    }

    private static RagProperties props(int maxPerDoc, int maxPerHeading, boolean dedupe) {
        RagProperties props = new RagProperties();
        props.getRetrieve().setMaxPerDoc(maxPerDoc);
        props.getRetrieve().setMaxPerHeading(maxPerHeading);
        props.getRetrieve().setDedupe(dedupe);
        return props;
    }

    @Test
    void dedupesIdenticalContent() {
        List<ChunkView> ranked = List.of(
                view(1, "a.md", "H", "同一条内容", 0.9),
                view(2, "b.md", "H", "同一条内容", 0.8),
                view(3, "c.md", "H", "另一条内容", 0.7));

        List<ChunkView> selected = selector.select(ranked, 10, props(4, 2, true));

        assertEquals(2, selected.size());
        assertEquals("a.md", selected.get(0).filename());
        assertEquals("c.md", selected.get(1).filename());
    }

    @Test
    void keepsDuplicatesWhenDedupeDisabled() {
        List<ChunkView> ranked = List.of(
                view(1, "a.md", "H", "同一条内容", 0.9),
                view(2, "b.md", "H", "同一条内容", 0.8));

        List<ChunkView> selected = selector.select(ranked, 10, props(4, 2, false));

        assertEquals(2, selected.size());
    }

    @Test
    void enforcesHeadingQuota() {
        List<ChunkView> ranked = List.of(
                view(1, "a.md", "第一章", "甲", 0.9),
                view(2, "a.md", "第一章", "乙", 0.8),
                view(3, "a.md", "第一章", "丙", 0.7),
                view(4, "a.md", "第二章", "丁", 0.6));

        List<ChunkView> selected = selector.select(ranked, 10, props(4, 2, true));

        assertEquals(3, selected.size());
        assertEquals(List.of("甲", "乙", "丁"), selected.stream().map(ChunkView::content).toList());
    }

    @Test
    void enforcesDocumentQuota() {
        List<ChunkView> ranked = List.of(
                view(1, "a.md", "第一章", "甲", 0.9),
                view(2, "a.md", "第二章", "乙", 0.8),
                view(3, "a.md", "第三章", "丙", 0.7),
                view(4, "b.md", "第一章", "丁", 0.6));

        List<ChunkView> selected = selector.select(ranked, 10, props(2, 5, true));

        assertEquals(3, selected.size());
        assertEquals(List.of("甲", "乙", "丁"), selected.stream().map(ChunkView::content).toList());
    }

    @Test
    void truncatesToLimit() {
        List<ChunkView> ranked = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ranked.add(view(i, "f" + i + ".md", "H" + i, "内容" + i, 0.9 - i * 0.01));
        }

        List<ChunkView> selected = selector.select(ranked, 3, props(10, 10, true));

        assertEquals(3, selected.size());
        assertEquals("f0.md", selected.get(0).filename());
        assertEquals("f2.md", selected.get(2).filename());
    }

    @Test
    void blankHeadingDoesNotCountTowardsHeadingQuota() {
        List<ChunkView> ranked = List.of(
                view(1, "a.md", "", "甲", 0.9),
                view(2, "a.md", "", "乙", 0.8),
                view(3, "a.md", "", "丙", 0.7));

        List<ChunkView> selected = selector.select(ranked, 10, props(10, 1, true));

        assertEquals(3, selected.size());
    }

    @Test
    void contentHashIgnoresWhitespaceAndCase() {
        ChunkView a = view(1, "a.md", "H", "  华东  专线\n扩容 ", 0.9);
        ChunkView b = view(2, "a.md", "H", "华东 专线 扩容", 0.9);
        ChunkView c = view(3, "a.md", "H", "华东专线扩容二期", 0.9);

        assertEquals(RagSelector.contentHash(a), RagSelector.contentHash(b));
        assertNotEquals(RagSelector.contentHash(a), RagSelector.contentHash(c));
        assertTrue(RagSelector.contentHash(a).matches("[0-9a-f]{16}"));
    }
}
