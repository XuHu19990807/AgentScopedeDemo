package com.cetc28.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {

    @TempDir
    Path temp;

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsUtf8Text() throws Exception {
        Path file = temp.resolve("a.txt");
        Files.writeString(file, "你好\nhello", StandardCharsets.UTF_8);
        assertEquals("你好\nhello", extractor.extract(file, ".txt"));
    }

    @Test
    void fallsBackToGb18030() throws Exception {
        Path file = temp.resolve("gbk.txt");
        Files.write(file, "中文测试".getBytes(Charset.forName("GBK")));
        assertEquals("中文测试", extractor.extract(file, ".txt"));
    }

    @Test
    void extractsMarkdownCsvAndJsonAsText() throws Exception {
        Path md = temp.resolve("readme.md");
        Files.writeString(md, "# 标题\n内容", StandardCharsets.UTF_8);
        assertTrue(extractor.extract(md, ".md").contains("# 标题"));

        Path csv = temp.resolve("data.csv");
        Files.writeString(csv, "a,b\n1,2", StandardCharsets.UTF_8);
        assertTrue(extractor.extract(csv, ".csv").contains("1,2"));

        Path json = temp.resolve("data.json");
        Files.writeString(json, "{\"k\":\"v\"}", StandardCharsets.UTF_8);
        assertTrue(extractor.extract(json, ".json").contains("\"k\""));
    }

    @Test
    void stripsHtmlAndXmlMarkup() throws Exception {
        Path html = temp.resolve("a.html");
        Files.writeString(html, "<html><head><style>x{}</style></head><body><h1>标题</h1><script>bad()</script>正文</body></html>",
                StandardCharsets.UTF_8);
        String text = extractor.extract(html, ".html");
        assertTrue(text.contains("标题"));
        assertTrue(text.contains("正文"));
        assertFalse(text.contains("<h1>"));
        assertFalse(text.contains("bad()"));
    }

    @Test
    void extractsXlsxWithSheetMarker() throws Exception {
        Path file = temp.resolve("data.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("名称");
            row.createCell(1).setCellValue(42);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        String text = extractor.extract(file, ".xlsx");
        assertTrue(text.contains("Sheet: 数据"));
        assertTrue(text.contains("名称"));
        assertTrue(text.contains("42"));
    }

    @Test
    void extractsDocx() throws Exception {
        Path file = temp.resolve("data.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Hello DOCX");
            try (OutputStream out = Files.newOutputStream(file)) {
                document.write(out);
            }
        }
        assertTrue(extractor.extract(file, ".docx").contains("Hello DOCX"));
    }

    @Test
    void extractsPdfWithPageMarker() throws Exception {
        Path file = temp.resolve("a.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Hello PDF");
                content.endText();
            }
            document.save(file.toFile());
        }
        String text = extractor.extract(file, ".pdf");
        assertTrue(text.contains("第 1 页"));
        assertTrue(text.contains("Hello PDF"));
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThrows(UnsupportedOperationException.class,
                () -> extractor.extract(temp.resolve("a.exe"), ".exe"));
    }

    @Test
    void truncatesLongText() throws Exception {
        ReflectionTestUtils.setField(extractor, "maxExtractChars", 10);
        Path file = temp.resolve("long.txt");
        Files.writeString(file, "0123456789ABCDEFGHIJ", StandardCharsets.UTF_8);
        String text = extractor.extract(file, ".txt");
        assertTrue(text.length() < 100);
        assertTrue(text.contains("已截断"));
    }

    @Test
    void reportsSupportedExtensions() {
        assertTrue(extractor.supports(".pdf"));
        assertTrue(extractor.supports(".md"));
        assertFalse(extractor.supports(".exe"));
        assertEquals(".PDF", DocumentTextExtractor.extensionOf("A.PDF").toUpperCase());
    }

    // ========== 新增契约：段落分隔、单元格截断、Tika 兜底 ==========

    @Test
    void docxParagraphsAreSeparatedByBlankLines() throws Exception {
        Path file = temp.resolve("paras.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("第一段内容。");
            document.createParagraph().createRun().setText("第二段内容。");
            try (OutputStream out = Files.newOutputStream(file)) {
                document.write(out);
            }
        }
        String text = extractor.extract(file, ".docx");
        assertTrue(text.contains("第一段内容。"), text);
        assertTrue(text.contains("第一段内容。\n\n第二段内容。"),
                "docx 相邻段落之间必须保留空行，实际: " + text.replace("\n", "\\n"));
    }

    @Test
    void truncatesOverlongExcelCell() throws Exception {
        Path file = temp.resolve("long-cell.xlsx");
        String cell = "x".repeat(3000);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("长单元格");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(cell);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        String text = extractor.extract(file, ".xlsx");
        assertTrue(text.contains("…"), "超长单元格应带省略号");
        for (String line : text.split("\n")) {
            assertTrue(line.length() <= 1100, "单元格未被截断，行长度 " + line.length());
        }
    }

    @Test
    void extractsRtfThroughTika() throws Exception {
        Path file = temp.resolve("note.rtf");
        Files.writeString(file, rtf("Hello RTF from Tika."), StandardCharsets.US_ASCII);
        String text = extractor.extract(file, ".rtf");
        assertTrue(text.contains("Hello RTF from Tika"), "Tika 应能解析 .rtf，实际: " + text);
    }

    @Test
    void corruptedDocxFallsBackToTikaText() throws Exception {
        Path file = temp.resolve("broken.docx");
        Files.writeString(file, rtf("Fallback content works."), StandardCharsets.US_ASCII);
        String text = extractor.extract(file, ".docx");
        assertTrue(text.contains("Fallback content works"),
                "损坏的 docx 应回退到 Tika 文本，实际: " + text);
    }

    @Test
    void supportsTikaFallbackExtensions() {
        assertTrue(extractor.supports(".rtf"));
        assertTrue(extractor.supports(".odt"));
        assertTrue(extractor.supports(".ods"));
        assertTrue(extractor.supports(".wps"));
        assertTrue(extractor.unsupportedMessage().contains("rtf"));
    }

    private static String rtf(String body) {
        return "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Calibri;}}\\f0\\fs22 " + body + "\\par}";
    }
}
