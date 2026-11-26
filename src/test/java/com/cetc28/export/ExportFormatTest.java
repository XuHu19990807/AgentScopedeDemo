package com.cetc28.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExportFormatTest {

    @Test
    void detectsFormatFromFileName() {
        assertEquals(ExportFormat.XLSX, ExportFormat.fromFileName("预算表.XLSX"));
        assertEquals(ExportFormat.DOCX, ExportFormat.fromFileName("方案.docx"));
        assertEquals(ExportFormat.MD, ExportFormat.fromFileName("notes.markdown"));
        assertEquals(ExportFormat.CSV, ExportFormat.fromFileName("data.csv"));
        assertNull(ExportFormat.fromFileName("无扩展名"));
        assertNull(ExportFormat.fromFileName("a.pdf"));
        assertNull(ExportFormat.fromFileName(null));
    }

    @Test
    void detectsIntentOnlyWhenActionAndFormatBothPresent() {
        assertEquals(ExportFormat.XLSX, ExportFormat.fromUserIntent("把这些数据保存成 Excel"));
        assertEquals(ExportFormat.XLSX, ExportFormat.fromUserIntent("导出成电子表格"));
        assertEquals(ExportFormat.DOCX, ExportFormat.fromUserIntent("整理成 word 文档发我"));
        assertEquals(ExportFormat.TXT, ExportFormat.fromUserIntent("存成 txt"));
        assertEquals(ExportFormat.MD, ExportFormat.fromUserIntent("保存为 markdown"));
        assertEquals(ExportFormat.CSV, ExportFormat.fromUserIntent("export as csv"));
        assertNull(ExportFormat.fromUserIntent("这个表格怎么算出来的"));
        assertNull(ExportFormat.fromUserIntent("帮我保存一下"));
        assertNull(ExportFormat.fromUserIntent(null));
    }

    @Test
    void ensureExtensionAppendsOrReplaces() {
        assertEquals("a.xlsx", ExportFormat.ensureExtension("a.xlsx", ExportFormat.XLSX));
        assertEquals("a.docx", ExportFormat.ensureExtension("a.txt", ExportFormat.DOCX));
        assertEquals("导出文件.txt", ExportFormat.ensureExtension("", ExportFormat.TXT));
        assertEquals("报表.md", ExportFormat.ensureExtension("报表", ExportFormat.MD));
    }
}
