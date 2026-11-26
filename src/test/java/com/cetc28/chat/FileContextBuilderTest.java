package com.cetc28.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileContextBuilderTest {

    private final FileContextBuilder builder = new FileContextBuilder();

    @Test
    void returnsEmptyWhenNoFiles() {
        assertEquals("", builder.build("问题", List.of(), 12000));
        assertEquals("", builder.build("问题", null, 12000));
    }

    @Test
    void includesHeaderNameAndContent() {
        String context = builder.build("总结一下", List.of(file("报告.pdf", "第一段内容", "第二段内容")), 12000);
        assertTrue(context.contains("【用户上传的文件】"));
        assertTrue(context.contains("报告.pdf"));
        assertTrue(context.contains("第一段内容"));
        assertTrue(context.contains("第二段内容"));
        assertTrue(context.contains("文件内容开始"));
    }

    @Test
    void respectsBudget() {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 30; i++) chunks.add("第" + i + "段：" + "内容".repeat(300));
        String context = builder.build("问题", List.of(file("长文档.txt", chunks)), 2000);
        assertTrue(context.length() <= 2000, "上下文超预算: " + context.length());
    }

    @Test
    void prioritizesChunksMatchingQuestion() {
        List<String> chunks = new ArrayList<>();
        chunks.add("标题：年度报告");
        for (int i = 0; i < 20; i++) chunks.add("无关段落 " + i + " " + "填充".repeat(60));
        chunks.add("目标关键词：网络规划与带宽测算结果");
        String context = builder.build("请分析网络规划和带宽", List.of(file("报告.txt", chunks)), 900);
        assertTrue(context.contains("目标关键词"), "应优先选入与问题相关的片段");
    }

    @Test
    void marksOmissions() {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) chunks.add("段落 " + i + " " + "内容".repeat(200));
        String context = builder.build("问题", List.of(file("长文.txt", chunks)), 900);
        assertTrue(context.contains("省略") || context.contains("节选"), "应标记内容被截断");
    }

    @Test
    void includesAllFiles() {
        String context = builder.build("问题", List.of(
                file("a.txt", "文件A内容"),
                file("b.txt", "文件B内容")), 12000);
        assertTrue(context.contains("a.txt"));
        assertTrue(context.contains("b.txt"));
        assertTrue(context.contains("文件A内容"));
        assertTrue(context.contains("文件B内容"));
    }

    @Test
    void ignoresBlankQuestion() {
        String context = builder.build("", List.of(file("a.txt", "内容")), 12000);
        assertFalse(context.isBlank());
        assertTrue(context.contains("内容"));
    }

    private ChatFileService.LoadedFile file(String name, String... texts) {
        return file(name, List.of(texts));
    }

    private ChatFileService.LoadedFile file(String name, List<String> texts) {
        List<ChatFileChunk> chunks = new ArrayList<>();
        int line = 1;
        int total = 0;
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            int lines = (int) text.chars().filter(ch -> ch == '\n').count();
            chunks.add(new ChatFileChunk(i, line, line + lines, text));
            line += lines + 1;
            total += text.length();
        }
        ChatFile meta = ChatFile.builder()
                .id("f_" + name.hashCode())
                .userId("u_test")
                .name(name)
                .ext(".txt")
                .size(total)
                .chars(total)
                .chunks(chunks.size())
                .build();
        return new ChatFileService.LoadedFile(meta, chunks);
    }
}
