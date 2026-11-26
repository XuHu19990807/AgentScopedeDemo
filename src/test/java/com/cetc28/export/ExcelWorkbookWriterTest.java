package com.cetc28.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelWorkbookWriterTest {

    @Test
    void writesMultipleSheetsWithHeaderStyle() throws Exception {
        var sheets = List.of(
                new ExcelWorkbookWriter.SheetSpec("预算", List.of("项目", "金额"),
                        List.of(List.of("A", "100"), List.of("B", "200"))),
                new ExcelWorkbookWriter.SheetSpec("说明", List.of("备注"), List.of(List.of("测试"))));
        byte[] bytes = ExcelWorkbookWriter.toXlsx(sheets);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            XSSFSheet first = workbook.getSheetAt(0);
            assertEquals("预算", first.getSheetName());
            assertEquals("项目", first.getRow(0).getCell(0).getStringCellValue());
            assertTrue(first.getRow(0).getCell(0).getCellStyle().getFont().getBold());
            Cell amount = first.getRow(1).getCell(1);
            assertEquals(CellType.NUMERIC, amount.getCellType());
            assertEquals(100d, amount.getNumericCellValue());
            assertTrue(first.getPaneInformation() != null && first.getPaneInformation().isFreezePane());
        }
    }

    @Test
    void sanitizesIllegalSheetNames() throws Exception {
        var spec = new ExcelWorkbookWriter.SheetSpec("a/b:c*d?", List.of("h"), List.of(List.of("v")));
        byte[] bytes = ExcelWorkbookWriter.toXlsx(List.of(spec));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("a_b_c_d_", workbook.getSheetAt(0).getSheetName());
        }
    }

    @Test
    void rejectsEmptyData() {
        assertThrows(IllegalArgumentException.class, () -> ExcelWorkbookWriter.toXlsx(List.of()));
    }

    @Test
    void parsesMarkdownTable() {
        String markdown = """
                | 项目 | 金额 |
                | --- | --- |
                | A | 100 |
                """;
        ExcelWorkbookWriter.SheetSpec spec = ExcelWorkbookWriter.fromMarkdownTable(markdown, "Sheet1");
        assertEquals(List.of("项目", "金额"), spec.headers());
        assertEquals(1, spec.rows().size());
        assertEquals(List.of("A", "100"), spec.rows().get(0));
    }

    @Test
    void returnsNullWhenNoMarkdownTable() {
        assertNull(ExcelWorkbookWriter.fromMarkdownTable("只有文字没有表格", "Sheet1"));
        assertNull(ExcelWorkbookWriter.fromMarkdownTable("| a | b |\n| c | d |", "Sheet1"));
    }
}
