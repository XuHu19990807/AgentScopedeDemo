package com.cetc28.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → Word（.docx）。覆盖对话答复里最常见的结构：
 * 标题、段落、有序/无序列表、引用、代码块、管道表格、行内粗体/斜体/行内代码。
 */
public final class MarkdownDocWriter {

    private static final Pattern INLINE = Pattern.compile("\\*\\*[^*]+\\*\\*|`[^`]+`|\\*[^*]+\\*");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+.*");
    private static final Pattern ORDERED = Pattern.compile("^\\d+[.)]\\s+.*");
    private static final Pattern HR = Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(":?-{2,}:?");

    private MarkdownDocWriter() {
    }

    public static byte[] toDocx(String markdown) throws IOException {
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (text.isBlank()) {
                doc.createParagraph().createRun().setText("（无内容）");
                doc.write(out);
                return out.toByteArray();
            }
            List<String> table = new ArrayList<>();
            StringBuilder code = new StringBuilder();
            boolean inCode = false;
            for (String raw : text.split("\n", -1)) {
                String trimmed = raw.trim();
                if (trimmed.startsWith("```")) {
                    if (inCode) {
                        flushCode(doc, code);
                        code.setLength(0);
                        inCode = false;
                    } else {
                        flushTable(doc, table);
                        inCode = true;
                    }
                    continue;
                }
                if (inCode) {
                    code.append(raw).append('\n');
                    continue;
                }
                if (trimmed.startsWith("|")) {
                    table.add(trimmed);
                    continue;
                }
                flushTable(doc, table);
                if (trimmed.isEmpty()) {
                    doc.createParagraph();
                } else if (trimmed.startsWith("#")) {
                    heading(doc, trimmed);
                } else if (BULLET.matcher(trimmed).matches()) {
                    listItem(doc, trimmed.substring(2).trim());
                } else if (ORDERED.matcher(trimmed).matches()) {
                    listItem(doc, trimmed.replaceFirst("^\\d+[.)]\\s+", ""));
                } else if (trimmed.startsWith(">")) {
                    quote(doc, trimmed.substring(1).trim());
                } else if (HR.matcher(trimmed).matches()) {
                    doc.createParagraph();
                } else {
                    paragraph(doc, trimmed);
                }
            }
            if (inCode) flushCode(doc, code);
            flushTable(doc, table);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void heading(XWPFDocument doc, String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        String title = line.substring(Math.min(level, line.length())).trim();
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 2 ? 200 : 120);
        p.setSpacingAfter(80);
        XWPFRun run = p.createRun();
        run.setBold(true);
        run.setFontSize(switch (Math.min(level, 4)) {
            case 1 -> 20;
            case 2 -> 16;
            case 3 -> 14;
            default -> 12;
        });
        run.setText(title);
    }

    private static void paragraph(XWPFDocument doc, String line) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(60);
        addRuns(p, line);
    }

    private static void listItem(XWPFDocument doc, String content) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        p.setSpacingAfter(30);
        XWPFRun bullet = p.createRun();
        bullet.setText("• ");
        addRuns(p, content);
    }

    private static void quote(XWPFDocument doc, String content) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(240);
        XWPFRun run = p.createRun();
        run.setItalic(true);
        run.setColor("666666");
        run.setText(content);
    }

    private static void flushCode(XWPFDocument doc, StringBuilder code) {
        String body = code.toString().replaceAll("\\n+$", "");
        if (body.isEmpty()) return;
        for (String line : body.split("\n", -1)) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(240);
            p.setSpacingAfter(0);
            XWPFRun run = p.createRun();
            run.setFontFamily("Consolas");
            run.setFontSize(9);
            run.setColor("333333");
            run.setText(line.isEmpty() ? " " : line);
        }
        doc.createParagraph();
    }

    private static void flushTable(XWPFDocument doc, List<String> lines) {
        if (lines.isEmpty()) return;
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(splitCells(line));
        }
        lines.clear();
        boolean hasHeader = rows.size() >= 2 && rows.get(1).stream().allMatch(cell -> TABLE_SEPARATOR.matcher(cell).matches());
        if (hasHeader) {
            rows.remove(1);
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        if (columns == 0) return;
        XWPFTable table = doc.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            List<String> cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                String value = c < cells.size() ? cells.get(c) : "";
                XWPFTableCell cell = row.getCell(c);
                XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
                XWPFRun run = p.createRun();
                run.setText(value);
                if (hasHeader && r == 0) {
                    run.setBold(true);
                }
            }
        }
        doc.createParagraph();
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

    private static void addRuns(XWPFParagraph p, String text) {
        Matcher matcher = INLINE.matcher(text);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                p.createRun().setText(text.substring(last, matcher.start()));
            }
            String token = matcher.group();
            if (token.startsWith("**")) {
                XWPFRun run = p.createRun();
                run.setBold(true);
                run.setText(token.substring(2, token.length() - 2));
            } else if (token.startsWith("`")) {
                XWPFRun run = p.createRun();
                run.setFontFamily("Consolas");
                run.setColor("B03060");
                run.setText(token.substring(1, token.length() - 1));
            } else {
                XWPFRun run = p.createRun();
                run.setItalic(true);
                run.setText(token.substring(1, token.length() - 1));
            }
            last = matcher.end();
        }
        if (last < text.length()) {
            p.createRun().setText(text.substring(last));
        }
    }
}
