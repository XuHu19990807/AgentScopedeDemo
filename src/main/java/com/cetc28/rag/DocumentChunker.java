package com.cetc28.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构化文本切块。
 *
 * <p>切块目标是"检索块可读、可回答"：段落不被切两半、句子不在中间断、表格行不跨块。
 * 实现分三层：
 * <ul>
 *   <li>分块（{@link Kind}）：把解析后的 Markdown 文本切成 HEADING / PARAGRAPH / LIST /
 *       CODE / TABLE / SEPARATOR 语义块；docx/pptx 的段落之间有空行，天然成段；</li>
 *   <li>打包（Assembler）：以 {@code hard = max(targetChars, maxChars)} 为上限按块贪心打包——
 *       宁可块略大（最多到 hard），也不把段落/表格行切两半；只有单个块本身超过 hard 才切分；</li>
 *   <li>切分（split）：段落类优先在前向溢出窗口内找句末标点，其次后向找；表格按"每片重复表头、
 *       行不拆"分片，超宽行按未转义的 {@code |} 拆单元格。</li>
 * </ul>
 *
 * <p>纯函数，无外部依赖，便于单测。
 */
@Component
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    /** 标题识别：Markdown 标题、第X章/节、一、、（一）、1.2 等常见中文文档写法 */
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+\\S.{0,78}"
                    + "|第\\s*[一二三四五六七八九十百零〇0-9]{1,4}\\s*[章节篇部]\\s*.{0,60}"
                    + "|[一二三四五六七八九十]{1,3}\\s*[、.．]\\s*\\S.{0,60}"
                    + "|[（(]\\s*[一二三四五六七八九十]{1,3}\\s*[）)]\\s*\\S.{0,60}"
                    + "|[0-9]{1,2}(?:\\.[0-9]{1,2}){1,3}\\s+\\S.{0,60})$");

    /** 列表项：- / * / + / 1. / 1) / （1） */
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[-*+]\\s+\\S|\\d{1,3}[.)、]\\s*\\S|[（(]\\d{1,3}[）)]\\s*\\S).*$");

    /** 解析器插入的页分隔行：=== 第 N 页 === */
    private static final Pattern PAGE_SEPARATOR = Pattern.compile("^={2,}\\s*第\\s*\\d+\\s*页\\s*={2,}$");

    private static final String FENCE = "```";

    private static final int MAX_HEADING_CHARS = 80;

    /** 切分时前向溢出窗口上限：向前多找句末标点，避免切出很短的碎块 */
    private static final int MAX_OVERSHOOT = 80;

    private static final char[] SENTENCE_ENDS = {'。', '！', '？', '；', '!', '?', ';'};

    /** 块类型：决定切分策略 */
    public enum Kind {HEADING, PARAGRAPH, LIST, CODE, TABLE, SEPARATOR}

    public record Chunk(String content, String headingPath, int lineStart, int lineEnd, String contentHash) {
        /** 用于喂给 embedding 的文本：标题路径 + 正文，让脱离原文的块也能被正确检索 */
        public String embeddingText() {
            if (headingPath == null || headingPath.isBlank()) return content;
            if (content.startsWith(headingPath)) return content;
            return headingPath + "\n" + content;
        }
    }

    /** 块 + 它所属的父块（小节）正文：子块负责命中，父块负责给模型足够上下文。 */
    public record ParentChunk(Chunk chunk, String parentText) {
    }

    private record Block(Kind kind, String text, String heading, int lineStart, int lineEnd) {}

    /** 切块（旧签名）：硬上限取 targetChars，保持与既有调用/测试的打包行为兼容 */
    public List<Chunk> chunk(String text, int targetChars, int overlapChars, int maxChunks) {
        return chunk(text, targetChars, overlapChars, maxChunks, Math.max(120, targetChars));
    }

    /**
     * 切块（带硬上限）。
     *
     * @param maxChars 单块硬上限（字符）：打包不超过该值；只有单个语义块超过它才切分
     */
    public List<Chunk> chunk(String text, int targetChars, int overlapChars, int maxChunks, int maxChars) {
        if (text == null || text.isBlank()) return List.of();
        int target = Math.max(120, targetChars);
        int hard = Math.max(target, maxChars > 0 ? maxChars : target);
        int overlap = Math.max(0, Math.min(overlapChars, hard / 4));
        int limit = maxChunks > 0 ? maxChunks : Integer.MAX_VALUE;

        Assembler assembler = new Assembler(hard, overlap, limit);
        for (Block block : blocks(text)) {
            if (assembler.out.size() >= limit) {
                log.warn("切块数量达到上限 {}，剩余内容被忽略（文件可能过大）", limit);
                break;
            }
            assembler.add(block);
        }
        assembler.finish();
        return assembler.out;
    }

    /** 切块并生成父块（旧签名） */
    public List<ParentChunk> chunkWithParents(String text, int targetChars, int overlapChars,
                                              int maxChunks, int parentMaxChars) {
        return chunkWithParents(text, targetChars, overlapChars, maxChunks, parentMaxChars,
                Math.max(120, targetChars));
    }

    /**
     * 切块并生成父块。
     *
     * <p>父块 = 同一标题路径下、总长不超过 parentMaxChars 的连续子块拼起来的正文。
     * 检索命中子块时注入父块，既保留精确命中，又避免模型只看到半句话。
     */
    public List<ParentChunk> chunkWithParents(String text, int targetChars, int overlapChars,
                                              int maxChunks, int parentMaxChars, int maxChars) {
        List<Chunk> chunks = chunk(text, targetChars, overlapChars, maxChunks, maxChars);
        if (chunks.isEmpty()) return List.of();
        int parentLimit = Math.max(400, parentMaxChars > 0 ? parentMaxChars : 1200);
        List<ParentChunk> result = new ArrayList<>(chunks.size());
        int i = 0;
        while (i < chunks.size()) {
            String heading = chunks.get(i).headingPath();
            StringBuilder parent = new StringBuilder();
            int j = i;
            while (j < chunks.size() && sameHeading(chunks.get(j).headingPath(), heading)) {
                String addition = chunks.get(j).content();
                boolean empty = parent.length() == 0;
                if (!empty && parent.length() + 1 + addition.length() > parentLimit) break;
                if (!empty) parent.append('\n');
                parent.append(addition);
                j++;
                if (parent.length() >= parentLimit) break;
            }
            if (j == i) j = i + 1;
            // 标题路径只作前缀补一次；块正文里通常已含标题行，避免重复出现
            String parentText = parent.toString();
            if (heading != null && !heading.isBlank() && !parentText.startsWith(heading)) {
                parentText = heading + "\n" + parentText;
            }
            parentText = truncateAtBoundary(parentText, parentLimit);
            for (int k = i; k < j; k++) {
                result.add(new ParentChunk(chunks.get(k), parentText));
            }
            i = j;
        }
        return result;
    }

    private static boolean sameHeading(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    /** 父块截断：优先回退到行边界，其次句末标点，避免把一句话截成半句 */
    static String truncateAtBoundary(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int floor = max - Math.max(40, max / 10);
        for (int i = max; i >= floor; i--) {
            if (text.charAt(i - 1) == '\n') return text.substring(0, i).trim();
        }
        for (int i = max; i >= floor; i--) {
            char c = text.charAt(i - 1);
            for (char end : SENTENCE_ENDS) {
                if (c == end) return text.substring(0, i).trim();
            }
        }
        return text.substring(0, max).trim();
    }

    // ========== 语义块 ==========

    private List<Block> blocks(String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        List<Block> blocks = new ArrayList<>();
        String heading = "";
        StringBuilder para = new StringBuilder();
        StringBuilder list = new StringBuilder();
        int paraStart = 0, paraEnd = 0;
        int listStart = 0, listEnd = 0;
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        int codeStart = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int lineNo = i + 1;

            if (inCode) {
                code.append('\n').append(line);
                if (trimmed.startsWith(FENCE)) {
                    blocks.add(new Block(Kind.CODE, code.toString(), heading, codeStart, lineNo));
                    code.setLength(0);
                    inCode = false;
                }
                continue;
            }
            if (trimmed.startsWith(FENCE)) {
                flushList(blocks, list, heading, listStart, listEnd);
                flushPara(blocks, para, heading, paraStart, paraEnd);
                inCode = true;
                codeStart = lineNo;
                code.append(trimmed);
                continue;
            }
            if (trimmed.isEmpty()) {
                flushList(blocks, list, heading, listStart, listEnd);
                flushPara(blocks, para, heading, paraStart, paraEnd);
                continue;
            }
            if (PAGE_SEPARATOR.matcher(trimmed).matches()) {
                flushList(blocks, list, heading, listStart, listEnd);
                flushPara(blocks, para, heading, paraStart, paraEnd);
                blocks.add(new Block(Kind.SEPARATOR, trimmed, heading, lineNo, lineNo));
                continue;
            }
            if (trimmed.startsWith("|")) {
                flushList(blocks, list, heading, listStart, listEnd);
                flushPara(blocks, para, heading, paraStart, paraEnd);
                // 表格整组保留：连续表格行作为一个块，表头与数据行不会被拆到不同块
                StringBuilder table = new StringBuilder(trimmed);
                int tableStart = lineNo, tableEnd = lineNo;
                while (i + 1 < lines.length && lines[i + 1].trim().startsWith("|")) {
                    i++;
                    tableEnd = i + 1;
                    table.append('\n').append(lines[i].trim());
                }
                blocks.add(new Block(Kind.TABLE, table.toString(), heading, tableStart, tableEnd));
                continue;
            }
            if (isHeading(trimmed)) {
                flushList(blocks, list, heading, listStart, listEnd);
                flushPara(blocks, para, heading, paraStart, paraEnd);
                heading = trimmed;
                blocks.add(new Block(Kind.HEADING, trimmed, heading, lineNo, lineNo));
                continue;
            }
            if (LIST_ITEM.matcher(trimmed).matches()) {
                flushPara(blocks, para, heading, paraStart, paraEnd);
                if (list.length() == 0) listStart = lineNo;
                if (list.length() > 0) list.append('\n');
                list.append(trimmed);
                listEnd = lineNo;
                continue;
            }
            flushList(blocks, list, heading, listStart, listEnd);
            if (para.length() == 0) paraStart = lineNo;
            if (para.length() > 0) para.append('\n');
            para.append(trimmed);
            paraEnd = lineNo;
        }
        flushList(blocks, list, heading, listStart, listEnd);
        flushPara(blocks, para, heading, paraStart, paraEnd);
        return blocks;
    }

    private void flushPara(List<Block> blocks, StringBuilder para, String heading, int start, int end) {
        if (para.length() == 0) return;
        blocks.add(new Block(Kind.PARAGRAPH, para.toString(), heading, start, end));
        para.setLength(0);
    }

    private void flushList(List<Block> blocks, StringBuilder list, String heading, int start, int end) {
        if (list.length() == 0) return;
        blocks.add(new Block(Kind.LIST, list.toString(), heading, start, end));
        list.setLength(0);
    }

    private boolean isHeading(String line) {
        if (line.length() > MAX_HEADING_CHARS) return false;
        if (line.endsWith("。") || line.endsWith("；") || line.endsWith(";")) return false;
        return HEADING.matcher(line).matches();
    }

    // ========== 打包 ==========

    private final class Assembler {
        final List<Chunk> out = new ArrayList<>();
        final int hard;
        final int overlap;
        final int limit;
        final StringBuilder buf = new StringBuilder();
        String heading = "";
        int start = 0, end = 0;
        /** buf 里只有上一块的尾部重叠内容，还没有归属当前块 */
        boolean tailOnly = false;

        Assembler(int hard, int overlap, int limit) {
            this.hard = hard;
            this.overlap = overlap;
            this.limit = limit;
        }

        void add(Block block) {
            if (block.text().length() > hard) {
                if (buf.length() > 0) flush(false);
                addSplit(block);
                return;
            }
            if (buf.length() > 0 && !tailOnly && buf.length() + 1 + block.text().length() > hard) {
                flush(true);
            } else if (tailOnly && buf.length() + 1 + block.text().length() > hard + overlap) {
                // 块本身接近硬上限时丢弃上一块的尾部重叠，避免整块超出 hard + overlap
                buf.setLength(0);
                tailOnly = false;
            }
            if (tailOnly || buf.length() == 0) {
                heading = block.heading();
                start = block.lineStart();
                tailOnly = false;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(block.text());
            end = block.lineEnd();
        }

        /** 超长块按类型切分；分片各自成块、不互相重叠，保证分片只含完整段落/句子/表格行 */
        private void addSplit(Block block) {
            List<String> pieces = block.kind() == Kind.TABLE
                    ? splitTable(block.text(), hard)
                    : splitLong(block.text(), hard, 0);
            if (pieces.size() > 1) {
                log.debug("语义块超长被切分：kind={} chars={} pieces={}",
                        block.kind(), block.text().length(), pieces.size());
            }
            for (String piece : pieces) {
                if (out.size() >= limit) return;
                buf.setLength(0);
                tailOnly = false;
                heading = block.heading();
                start = block.lineStart();
                end = block.lineEnd();
                buf.append(piece);
                flush(false);
            }
        }

        /** @param carryOverlap 是否把尾部重叠带入下一块（切分片之间不带重叠） */
        void flush(boolean carryOverlap) {
            if (buf.length() == 0 || out.size() >= limit) {
                buf.setLength(0);
                tailOnly = false;
                return;
            }
            String content = buf.toString().trim();
            if (!content.isEmpty()) {
                out.add(new Chunk(content, heading, start, end, sha256(content)));
            }
            buf.setLength(0);
            if (carryOverlap && overlap > 0 && content.length() > overlap * 2 && out.size() < limit) {
                buf.append(tailOf(content, overlap));
                tailOnly = true;
            } else {
                tailOnly = false;
            }
        }

        void finish() {
            flush(false);
        }
    }

    // ========== 切分 ==========

    /**
     * 段落类块切分：
     * <ol>
     *   <li>先在前向溢出窗口 [hard, hard + overshoot] 内找句末标点/换行（宁可切长一点也不断句）；</li>
     *   <li>找不到再在后向 [i + hard/2, hard] 找；</li>
     *   <li>都没有才硬切（单句超长/压缩 JSON），并告警。</li>
     * </ol>
     */
    static List<String> splitLong(String text, int hard) {
        return splitLong(text, hard, 0);
    }

    static List<String> splitLong(String text, int hard, int overlap) {
        List<String> pieces = new ArrayList<>();
        int max = Math.max(120, hard - Math.max(0, overlap));
        int overshoot = Math.min(MAX_OVERSHOOT, max / 4);
        int i = 0;
        while (i < text.length()) {
            if (i > 0) {
                while (i < text.length() && text.charAt(i) == '\n') i++;
                if (i >= text.length()) break;
            }
            int hardEnd = Math.min(text.length(), i + max);
            if (hardEnd >= text.length()) {
                addPiece(pieces, text.substring(i));
                break;
            }
            int end = forwardBoundary(text, hardEnd, Math.min(text.length(), hardEnd + overshoot));
            if (end <= i) {
                end = backwardBoundary(text, i + Math.max(1, max / 2), hardEnd);
            }
            if (end <= i) {
                end = hardEnd;
                log.warn("切分处找不到句末/换行边界，按字符硬切：offset={} max={}", i, max);
            }
            addPiece(pieces, text.substring(i, end));
            i = end;
        }
        return pieces;
    }

    private static void addPiece(List<String> pieces, String raw) {
        String piece = raw.trim();
        if (!piece.isEmpty()) pieces.add(piece);
    }

    private static int forwardBoundary(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || isSentenceEnd(c)) return i + 1;
        }
        return -1;
    }

    private static int backwardBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '\n' || isSentenceEnd(c)) return i + 1;
        }
        return -1;
    }

    private static boolean isSentenceEnd(char c) {
        for (char end : SENTENCE_ENDS) {
            if (c == end) return true;
        }
        return false;
    }

    /**
     * 表格切分：每片都带表头与分隔行，数据行完整；单行超预算时按未转义的 | 拆单元格重建行。
     */
    static List<String> splitTable(String table, int hard) {
        String[] lines = table.split("\n", -1);
        if (lines.length <= 2) return List.of(table);
        String header = lines[0];
        boolean hasSeparator = isSeparatorRow(lines[1]);
        String separator = hasSeparator ? lines[1] : null;
        String prefix = separator == null ? header + "\n|---|" : header + "\n" + separator;
        int start = hasSeparator ? 2 : 1;

        List<String> pieces = new ArrayList<>();
        if (prefix.length() >= hard) {
            // 表头本身超长：先按单元格拆表头
            for (String headerPiece : fitRow(header, Math.max(60, hard - 8))) {
                pieces.add(headerPiece + "\n|---|");
            }
            start = lines.length;
        }
        int budget = Math.max(60, hard - prefix.length() - 1);
        StringBuilder rows = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            for (String row : fitRow(lines[i], budget)) {
                if (rows.length() > 0 && rows.length() + 1 + row.length() > budget) {
                    pieces.add(prefix + "\n" + rows);
                    rows.setLength(0);
                }
                if (rows.length() > 0) rows.append('\n');
                rows.append(row);
            }
        }
        if (rows.length() > 0) {
            pieces.add(prefix + "\n" + rows);
        }
        return pieces.isEmpty() ? List.of(table) : pieces;
    }

    /** 单行超预算时按未转义 | 拆单元格重建为多行，保证单元格文本零丢失 */
    private static List<String> fitRow(String row, int budget) {
        if (row.length() <= budget) return List.of(row);
        List<String> cells = splitCells(row);
        if (cells.size() <= 1) {
            return splitLong(row, budget, 0);
        }
        List<String> rows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String cell : cells) {
            for (String piece : splitLong(cell, Math.max(20, budget - 4), 0)) {
                String candidate = "| " + piece + " |";
                if (current.length() + 1 + candidate.length() > budget && current.length() > 0) {
                    rows.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append(' ');
                current.append(candidate);
            }
        }
        if (current.length() > 0) rows.add(current.toString());
        return rows;
    }

    private static List<String> splitCells(String row) {
        String body = row.trim();
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        String[] parts = body.split("(?<!\\\\)\\|", -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            String cell = part.trim();
            if (!cell.isEmpty()) cells.add(cell);
        }
        return cells.isEmpty() ? List.of(row) : cells;
    }

    private static boolean isSeparatorRow(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) return false;
        String inner = trimmed.replace("|", "").replace(":", "").replace("-", "").replace(" ", "");
        return inner.isEmpty() && trimmed.contains("-");
    }

    /** 取尾部重叠片段，尽量从行首开始，避免把一句话切成两半 */
    private static String tailOf(String content, int overlap) {
        int start = content.length() - overlap;
        int newline = content.indexOf('\n', start);
        if (newline > 0 && newline < content.length() - overlap / 2) {
            start = newline + 1;
        }
        return content.substring(start).trim();
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算内容哈希失败，退化为 hashCode：{}", e.getMessage());
            return Integer.toHexString(text.hashCode());
        }
    }
}
