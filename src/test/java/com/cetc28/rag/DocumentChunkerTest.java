package com.cetc28.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化切块的行为契约。
 *
 * <p>这些断言对应"检索不到"的真实故障：块过大被 embedding 静默截断、表格被切散、
 * 标题不随块走导致向量语义漂移。
 */
class DocumentChunkerTest {

    private static final int TARGET = 200;
    private static final int OVERLAP = 40;

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void chunksAreBoundedAndDoNotLoseContent() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            text.append("第").append(i).append("段：北京数据中心网络升级项目的测试内容，包含设备清单说明。\n\n");
        }
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text.toString(), TARGET, OVERLAP, 1000);

        assertTrue(chunks.size() > 1, "40 段文本应当被切成多块，实际 " + chunks.size());
        for (DocumentChunker.Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= TARGET + OVERLAP + 80,
                    "块长度超出预算： " + chunk.content().length());
            assertFalse(chunk.content().isBlank(), "不应产生空块");
        }
        String joined = chunks.stream().map(DocumentChunker.Chunk::content).reduce("", (a, b) -> a + b);
        assertTrue(joined.contains("第1段"), "首段内容丢失");
        assertTrue(joined.contains("第40段"), "末段内容丢失");
    }

    @Test
    void headingPathFollowsChunk() {
        // 前一段故意超过 target（120 是 chunk() 的下限），保证「## 预算」小节重新起块
        String text = """
                # 项目实施方案

                现状描述：北京数据中心现有核心路由器 2 台，接入交换机 10 台，出口带宽 1000Mbps，
                机房位于亦庄经济技术开发区 A 座三层，机柜 12 个，双路市电加 UPS 后备 30 分钟。

                ## 预算

                预算约 285 万元，含光纤铺设、设备采购、施工调试。
                """;
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, 120, 0, 100);

        DocumentChunker.Chunk budget = chunks.stream()
                .filter(c -> c.content().contains("285"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到包含预算正文的块"));
        assertTrue(budget.headingPath().contains("预算"),
                "预算段落必须带上小节标题，实际 headingPath=" + budget.headingPath());
        assertTrue(budget.embeddingText().contains("预算"),
                "向量化文本应包含标题路径，否则同义改写检索会漂移");
    }

    @Test
    void tableRowsStayInOneChunk() {
        String text = """
                # 设备清单

                | 设备 | 数量 |
                | 路由器 | 2 |
                | 交换机 | 10 |
                """;
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, TARGET, 0, 100);

        long tableChunks = chunks.stream().filter(c -> c.content().contains("| 路由器 | 2 |")).count();
        assertEquals(1, tableChunks, "表格行必须落在同一个块里");
        DocumentChunker.Chunk table = chunks.stream()
                .filter(c -> c.content().contains("| 路由器 | 2 |"))
                .findFirst().orElseThrow();
        assertTrue(table.content().contains("| 交换机 | 10 |"), "表头与数据行不能被切散");
        assertTrue(table.content().contains("| 设备 | 数量 |"), "表头必须与数据行同块");
    }

    @Test
    void longParagraphSplitsAtSentenceBoundary() {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            line.append("这是第").append(i).append("句用于验证长段落切分的内容说明。");
        }
        List<DocumentChunker.Chunk> chunks = chunker.chunk(line.toString(), TARGET, 0, 100);

        assertTrue(chunks.size() > 1, "长段落必须被切分");
        for (DocumentChunker.Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= TARGET + 80, "切分后仍超长: " + chunk.content().length());
            assertTrue(chunk.content().trim().endsWith("。"),
                    "切分点应落在句号后，实际: " + chunk.content());
        }
    }

    @Test
    void maxChunksIsHonored() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            text.append("段落").append(i).append("：一段足够长的中文内容，用于触发切块上限。\n\n");
        }
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text.toString(), TARGET, 0, 3);
        assertEquals(3, chunks.size(), "maxChunks 必须生效，防止超大文件打爆索引");
    }

    @Test
    void contentHashIsStableAndDistinguishes() {
        String a = "北京数据中心带宽 1000Mbps，需要扩容到 4000Mbps。";
        String b = "北京数据中心带宽 1000Mbps，需要扩容到 8000Mbps。";

        List<DocumentChunker.Chunk> first = chunker.chunk(a, TARGET, 0, 10);
        List<DocumentChunker.Chunk> second = chunker.chunk(a, TARGET, 0, 10);
        List<DocumentChunker.Chunk> other = chunker.chunk(b, TARGET, 0, 10);

        assertEquals(first.get(0).contentHash(), second.get(0).contentHash(),
                "同一内容重复上传必须得到相同 hash（用于去重）");
        assertNotEquals(first.get(0).contentHash(), other.get(0).contentHash(),
                "不同内容不能得到相同 hash");
        assertEquals(64, first.get(0).contentHash().length(), "sha256 十六进制应为 64 字符");
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(chunker.chunk(null, TARGET, OVERLAP, 10).isEmpty());
        assertTrue(chunker.chunk("   \n\n  \t ", TARGET, OVERLAP, 10).isEmpty());
    }

    @Test
    void lineNumbersPointAtOriginalLines() {
        String text = "第一行正文。\n第二行正文。\n\n第三段开始。\n";
        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, TARGET, 0, 10);
        assertFalse(chunks.isEmpty());
        assertEquals(1, chunks.get(0).lineStart(), "首块起始行应为 1");
        assertTrue(chunks.get(0).lineEnd() >= 2, "首块应覆盖前两行，实际 " + chunks.get(0).lineEnd());
    }
}
