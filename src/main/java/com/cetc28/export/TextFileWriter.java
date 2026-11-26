package com.cetc28.export;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本类文件写出：CSV 使用 UTF-8 BOM（Excel 双击打开不乱码），其余 UTF-8。
 */
public final class TextFileWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextFileWriter() {
    }

    public static byte[] write(String content, ExportFormat format) {
        String text = content == null ? "" : content;
        if (text.isEmpty()) {
            text = " ";
        }
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (format == ExportFormat.CSV) {
            byte[] result = new byte[UTF8_BOM.length + body.length];
            System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
            System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
            return result;
        }
        return body;
    }

    /** Markdown 表格 → CSV 文本（单元格里的逗号/引号做转义）。 */
    public static String markdownTableToCsv(String markdown) {
        if (markdown == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : markdown.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("|")) continue;
            String inner = line;
            if (inner.startsWith("|")) inner = inner.substring(1);
            if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
            String[] cells = inner.split("\\|", -1);
            boolean separator = true;
            for (String cell : cells) {
                if (!cell.trim().matches(":?-{2,}:?")) {
                    separator = false;
                    break;
                }
            }
            if (separator) continue;
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) out.append(',');
                out.append(escapeCsv(cells[i].trim()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String escapeCsv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
