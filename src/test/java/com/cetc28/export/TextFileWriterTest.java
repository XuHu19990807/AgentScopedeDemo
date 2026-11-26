package com.cetc28.export;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFileWriterTest {

    @Test
    void writesUtf8WithoutBomForText() {
        byte[] bytes = TextFileWriter.write("你好", ExportFormat.TXT);
        assertEquals("你好", new String(bytes, StandardCharsets.UTF_8));
        assertArrayEquals("你好".getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    void writesBomForCsv() {
        byte[] bytes = TextFileWriter.write("a,b\n1,2", ExportFormat.CSV);
        assertEquals(3, bytes.length - "a,b\n1,2".getBytes(StandardCharsets.UTF_8).length);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
    }

    @Test
    void convertsMarkdownTableToCsv() {
        String markdown = """
                | 项目 | 金额 |
                | --- | --- |
                | A | 100 |
                | B | 200 |
                """;
        String csv = TextFileWriter.markdownTableToCsv(markdown);
        assertEquals("项目,金额\nA,100\nB,200\n", csv);
    }

    @Test
    void escapesCommasAndQuotes() {
        String markdown = """
                | 名称 | 说明 |
                | --- | --- |
                | a | 含,逗号 |
                | b | 含"引号" |
                """;
        String csv = TextFileWriter.markdownTableToCsv(markdown);
        assertTrue(csv.contains("\"含,逗号\""));
        assertTrue(csv.contains("\"含\"\"引号\"\"\""));
    }

    @Test
    void emptyContentBecomesSingleSpace() {
        byte[] bytes = TextFileWriter.write("", ExportFormat.TXT);
        assertEquals(" ", new String(bytes, StandardCharsets.UTF_8));
    }
}
