package com.cetc28.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 表格数据 → Excel（.xlsx）。表头加粗并冻结首行，列宽按内容自适应。
 */
public final class ExcelWorkbookWriter {

    private static final int MAX_COLUMN_WIDTH_CHARS = 60;
    private static final int MAX_SHEET_NAME = 31;

    private ExcelWorkbookWriter() {
    }

    public record SheetSpec(String name, List<String> headers, List<List<String>> rows) {
    }

    public static byte[] toXlsx(List<SheetSpec> sheets) throws IOException {
        List<SheetSpec> valid = sheets == null ? List.of()
                : sheets.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("没有可写入的数据");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);

            Set<String> usedNames = new LinkedHashSet<>();
            for (int index = 0; index < valid.size(); index++) {
                SheetSpec spec = valid.get(index);
                String name = uniqueSheetName(spec.name(), index, usedNames);
                XSSFSheet sheet = workbook.createSheet(name);
                List<String> headers = spec.headers() == null ? List.of() : spec.headers();
                List<List<String>> rows = spec.rows() == null ? List.of() : spec.rows();
                int[] widths = new int[Math.max(headers.size(), rows.stream()
                        .mapToInt(row -> row == null ? 0 : row.size()).max().orElse(0))];
                int rowIndex = 0;
                if (!headers.isEmpty()) {
                    XSSFRow headerRow = sheet.createRow(rowIndex++);
                    for (int column = 0; column < headers.size(); column++) {
                        String value = headers.get(column) == null ? "" : headers.get(column);
                        writeCell(headerRow, column, value);
                        headerRow.getCell(column).setCellStyle(headerStyle);
                        widths[column] = Math.max(widths[column], displayWidth(value));
                    }
                    sheet.createFreezePane(0, 1);
                }
                for (List<String> values : rows) {
                    XSSFRow row = sheet.createRow(rowIndex++);
                    if (values == null) continue;
                    for (int column = 0; column < values.size() && column < widths.length; column++) {
                        String value = values.get(column) == null ? "" : values.get(column);
                        writeCell(row, column, value);
                        widths[column] = Math.max(widths[column], displayWidth(value));
                    }
                }
                for (int column = 0; column < widths.length; column++) {
                    int chars = Math.min(MAX_COLUMN_WIDTH_CHARS, Math.max(8, widths[column] + 2));
                    sheet.setColumnWidth(column, chars * 256);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 解析 Markdown 管道表格；没有表格时返回 null。 */
    public static SheetSpec fromMarkdownTable(String markdown, String sheetName) {
        if (markdown == null || markdown.isBlank()) return null;
        List<List<String>> rows = new ArrayList<>();
        boolean hasSeparator = false;
        for (String raw : markdown.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("|")) continue;
            List<String> cells = splitCells(line);
            boolean separator = !cells.isEmpty() && cells.stream()
                    .allMatch(cell -> cell.matches(":?-{2,}:?"));
            if (separator) {
                hasSeparator = true;
                continue;
            }
            rows.add(cells);
        }
        if (!hasSeparator || rows.size() < 2) return null;
        List<String> headers = rows.remove(0);
        return new SheetSpec(sheetName, headers, rows);
    }

    private static List<String> splitCells(String line) {
        String inner = line;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static void writeCell(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        if (isNumeric(value)) {
            try {
                cell.setCellValue(Double.parseDouble(value));
                return;
            } catch (NumberFormatException ignored) {
                // 回退为文本
            }
        }
        cell.setCellValue(value);
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty() || value.length() > 15) return false;
        return value.matches("-?\\d+(\\.\\d+)?");
    }

    private static int displayWidth(String value) {
        if (value == null) return 0;
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            width += value.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width;
    }

    private static String uniqueSheetName(String raw, int index, Set<String> used) {
        String name = raw == null ? "" : raw.trim().replaceAll("[\\\\/?*\\[\\]:]", "_");
        if (name.isEmpty()) name = "Sheet" + (index + 1);
        if (name.length() > MAX_SHEET_NAME) name = name.substring(0, MAX_SHEET_NAME);
        String candidate = name;
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            String tail = "_" + suffix++;
            candidate = name.length() + tail.length() > MAX_SHEET_NAME
                    ? name.substring(0, MAX_SHEET_NAME - tail.length()) + tail
                    : name + tail;
        }
        return candidate;
    }

    /** 供调用方构造：把若干行（首行为表头）直接包装成 SheetSpec。 */
    public static SheetSpec spec(String name, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new SheetSpec(name, List.of(), List.of());
        }
        List<List<String>> copy = new ArrayList<>(rows);
        List<String> headers = copy.remove(0);
        return new SheetSpec(name, headers, copy);
    }
}
