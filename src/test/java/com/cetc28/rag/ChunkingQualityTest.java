package com.cetc28.rag;

import com.cetc28.service.DocumentTextExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用多格式真实语料校验切片质量契约：
 * 1) 块长不超过 硬上限 + 重叠；
 * 2) 表格数据行不被切断（整行出现在某个块里）；
 * 3) 短段落（≤200 字）不被切断；
 * 4) 切点落在句末标点或行边界，不切在句子中间。
 */
class ChunkingQualityTest {

    private static final int TARGET = 360;
    private static final int OVERLAP = 48;
    private static final int HARD = 400;

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void corpusFilesRespectChunkingContracts() throws Exception {
        Path corpus = Path.of("src/test/resources/rag-eval/corpus");
        assertTrue(Files.isDirectory(corpus), "缺少评测语料目录: " + corpus.toAbsolutePath());

        List<Path> files;
        try (Stream<Path> stream = Files.list(corpus)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        assertFalse(files.isEmpty(), "评测语料为空");

        int checked = 0;
        for (Path file : files) {
            String ext = DocumentTextExtractor.extensionOf(file.getFileName().toString());
            String text = extractor.extract(file, ext);
            assertFalse(text.isBlank(), "解析结果为空: " + file);

            List<DocumentChunker.ParentChunk> parents =
                    chunker.chunkWithParents(text, TARGET, OVERLAP, 5000, 1200, HARD);
            assertFalse(parents.isEmpty(), "切块结果为空: " + file);

            List<String> contents = new ArrayList<>();
            for (DocumentChunker.ParentChunk parent : parents) {
                String content = parent.chunk().content();
                assertFalse(content.isBlank(), "出现空块: " + file);
                assertTrue(content.length() <= HARD + OVERLAP,
                        "块超长(" + content.length() + "): " + file);
                assertCutAtBoundary(content, ext, file);
                assertTrue(parent.parentText().length() <= 1200 + OVERLAP,
                        "父块超长: " + file);
                contents.add(content);
            }

            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.length() > 200) {
                    continue;
                }
                if (trimmed.startsWith("|")) {
                    if (trimmed.matches("\\|[-|: ]+\\|")) {
                        continue; // Markdown 分隔行会被重写
                    }
                    assertTrue(contents.stream().anyMatch(c -> c.contains(trimmed)),
                            "表格行被切断或丢失: " + file + " -> " + trimmed);
                } else if (!trimmed.startsWith("#") && !trimmed.startsWith("```")) {
                    assertTrue(contents.stream().anyMatch(c -> c.contains(trimmed)),
                            "短段落被切断或丢失: " + file + " -> " + trimmed);
                }
            }
            checked++;
        }
        assertTrue(checked >= 18, "应至少覆盖 18 个多格式语料，实际 " + checked);
    }

    private static void assertCutAtBoundary(String content, String ext, Path file) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        // CSV/JSON/HTML 是逐行记录格式，行完整性由短行 intact 断言保证，这里不再要求句末标点
        if (".json".equals(ext) || ".csv".equals(ext) || ".html".equals(ext)) {
            return;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        boolean ok = "。！？；!?;|".indexOf(last) >= 0
                || last == '」' || last == '）' || last == ')'
                || trimmed.startsWith("|") || trimmed.startsWith("```")
                || trimmed.matches("(?s).*\\n(\\|.*|```\\w*|#+ .*)$");
        assertTrue(ok, "切点不在句末/行边界(" + last + "): " + file
                + " -> ..." + trimmed.substring(Math.max(0, trimmed.length() - 30)));
    }
}
