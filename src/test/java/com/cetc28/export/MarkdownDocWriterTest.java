package com.cetc28.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDocWriterTest {

    @Test
    void producesValidDocx() throws Exception {
        byte[] bytes = MarkdownDocWriter.toDocx("# 标题\n\n正文 **加粗** 结束");
        assertTrue(bytes.length > 100);
        assertEquals('P', (char) bytes[0]);
        assertEquals('K', (char) bytes[1]);
    }

    @Test
    void rendersHeadingsBoldAndLarge() throws Exception {
        byte[] bytes = MarkdownDocWriter.toDocx("# 一级标题\n\n## 二级标题");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFParagraph first = doc.getParagraphs().get(0);
            assertTrue(first.getRuns().get(0).isBold());
            assertTrue(first.getRuns().get(0).getFontSize() >= 16);
        }
    }

    @Test
    void rendersTableWithHeaderAndRows() throws Exception {
        String markdown = """
                前置说明
                | 项目 | 金额 |
                | --- | --- |
                | A | 100 |
                | B | 200 |
                之后
                """;
        byte[] bytes = MarkdownDocWriter.toDocx(markdown);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals(1, doc.getTables().size());
            XWPFTable table = doc.getTables().get(0);
            assertEquals(3, table.getRows().size());
            assertEquals(2, table.getRow(0).getTableCells().size());
            assertTrue(table.getRow(0).getCell(0).getParagraphs().get(0)
                    .getRuns().get(0).isBold());
            assertFalse(table.getRow(1).getCell(0).getParagraphs().get(0)
                    .getRuns().get(0).isBold());
            assertEquals("A", table.getRow(1).getCell(0).getText());
        }
    }

    @Test
    void rendersBulletAndCodeBlock() throws Exception {
        byte[] bytes = MarkdownDocWriter.toDocx("- 第一项\n```java\nint a = 1;\n```");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            String bullet = paragraphs.get(0).getText();
            assertTrue(bullet.startsWith("• "));
            assertTrue(paragraphs.stream().anyMatch(p -> "int a = 1;".equals(p.getText())));
        }
    }

    @Test
    void emptyMarkdownProducesPlaceholder() throws Exception {
        byte[] bytes = MarkdownDocWriter.toDocx("");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals("（无内容）", doc.getParagraphs().get(0).getText());
        }
    }
}
