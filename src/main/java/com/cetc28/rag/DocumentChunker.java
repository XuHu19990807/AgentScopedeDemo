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
 * <p>旧实现按行切块：一行一个块，表格被拆散、长段落被切碎、"第几章"与正文分离，
 * 检索出来的片段往往脱离上下文无法阅读。这里改为：
 * <ul>
 *   <li>按空行/标题/表格分组为语义单元（unit）；</li>
 *   <li>把单元打包到目标字符数（默认 360，需与 embedding token 预算匹配）；</li>
 *   <li>块头记录所属标题路径，块与块之间保留少量重叠，避免答案正好被切在边界。</li>
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

    private static final int MAX_HEADING_CHARS = 80;

    public record Chunk(String content, String headingPath, int lineStart, int lineEnd, String contentHash) {
        /** 用于喂给 embedding 的文本：标题路径 + 正文，让脱离原文的块也能被正确检索 */
        public String embeddingText() {
            if (headingPath == null || headingPath.isBlank()) return content;
            if (content.startsWith(headingPath)) return content;
            return headingPath + "\n" + content;
        }
    }

    private record Unit(String text, String heading, int lineStart, int lineEnd) {}

    /** 块 + 它所属的父块（小节）正文：子块负责命中，父块负责给模型足够上下文。 */
    public record ParentChunk(Chunk chunk, String parentText) {
    }

    /**
     * 切块并生成父块。
     *
     * <p>父块 = 同一标题路径下、总长不超过 parentMaxChars 的连续子块拼起来的正文。
     * 检索命中子块时注入父块，既保留精确命中，又避免模型只看到半句话。
     */
    public List<ParentChunk> chunkWithParents(String text, int targetChars, int overlapChars,
                                              int maxChunks, int parentMaxChars) {
        List<Chunk> chunks = chunk(text, targetChars, overlapChars, maxChunks);
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
            parentText = truncate(parentText, parentLimit);
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

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    public List<Chunk> chunk(String text, int targetChars, int overlapChars, int maxChunks) {
        if (text == null || text.isBlank()) return List.of();
        int target = Math.max(120, targetChars);
        int overlap = Math.max(0, Math.min(overlapChars, target / 2));
        int limit = maxChunks > 0 ? maxChunks : Integer.MAX_VALUE;

        Assembler assembler = new Assembler(target, overlap, limit);
        for (Unit unit : units(text)) {
            if (assembler.out.size() >= limit) break;
            assembler.add(unit);
        }
        assembler.finish();
        return assembler.out;
    }

    // ========== 语义单元 ==========

    private List<Unit> units(String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        List<Unit> units = new ArrayList<>();
        String heading = "";
        StringBuilder para = new StringBuilder();
        int paraStart = 0, paraEnd = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int lineNo = i + 1;

            if (trimmed.isEmpty()) {
                flushPara(units, para, heading, paraStart, paraEnd);
                continue;
            }
            if (trimmed.startsWith("|")) {
                flushPara(units, para, heading, paraStart, paraEnd);
                // 表格整组保留：把连续表格行当作一个单元，避免表头与数据行被切到不同块
                StringBuilder table = new StringBuilder(trimmed);
                int tableStart = lineNo, tableEnd = lineNo;
                while (i + 1 < lines.length && lines[i + 1].trim().startsWith("|")) {
                    i++;
                    tableEnd = i + 1;
                    table.append('\n').append(lines[i].trim());
                }
                units.add(new Unit(table.toString(), heading, tableStart, tableEnd));
                continue;
            }
            if (isHeading(trimmed)) {
                flushPara(units, para, heading, paraStart, paraEnd);
                heading = trimmed;
                units.add(new Unit(trimmed, heading, lineNo, lineNo));
                continue;
            }
            if (para.length() == 0) paraStart = lineNo;
            if (para.length() > 0) para.append('\n');
            para.append(trimmed);
            paraEnd = lineNo;
        }
        flushPara(units, para, heading, paraStart, paraEnd);
        return units;
    }

    private void flushPara(List<Unit> units, StringBuilder para, String heading, int start, int end) {
        if (para.length() == 0) return;
        units.add(new Unit(para.toString(), heading, start, end));
        para.setLength(0);
    }

    private boolean isHeading(String line) {
        if (line.length() > MAX_HEADING_CHARS) return false;
        if (line.endsWith("。") || line.endsWith("；") || line.endsWith(";")) return false;
        return HEADING.matcher(line).matches();
    }

    // ========== 打包 ==========

    private final class Assembler {
        final List<Chunk> out = new ArrayList<>();
        final int target;
        final int overlap;
        final int limit;
        final StringBuilder buf = new StringBuilder();
        String heading = "";
        int start = 0, end = 0;
        /** buf 里只有上一块的尾部重叠内容，还没有归属当前单元 */
        boolean tailOnly = false;

        Assembler(int target, int overlap, int limit) {
            this.target = target;
            this.overlap = overlap;
            this.limit = limit;
        }

        void add(Unit unit) {
            if (buf.length() > 0 && !tailOnly && buf.length() + 1 + unit.text().length() > target) {
                flush();
            }
            if (tailOnly || buf.length() == 0) {
                heading = unit.heading();
                start = unit.lineStart();
                tailOnly = false;
            }
            if (unit.text().length() > target) {
                addSplit(unit);
                return;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(unit.text());
            end = unit.lineEnd();
        }

        private void addSplit(Unit unit) {
            for (String piece : splitLong(unit.text(), target)) {
                if (out.size() >= limit) {
                    buf.setLength(0);
                    return;
                }
                if (buf.length() > 0 && !tailOnly) flush();
                if (buf.length() == 0) {
                    heading = unit.heading();
                    start = unit.lineStart();
                }
                tailOnly = false;
                if (buf.length() > 0) buf.append('\n');
                buf.append(piece);
                end = unit.lineEnd();
                flush();
            }
        }

        void flush() {
            if (buf.length() == 0 || out.size() >= limit) {
                buf.setLength(0);
                tailOnly = false;
                return;
            }
            String content = buf.toString().trim();
            if (!content.isEmpty()) {
                out.add(new Chunk(content, heading, start, end, sha256(content)));
            }
            String tail = (overlap > 0 && content.length() > overlap * 2) ? tailOf(content, overlap) : "";
            buf.setLength(0);
            if (!tail.isEmpty() && out.size() < limit) {
                buf.append(tail);
                tailOnly = true;
            } else {
                tailOnly = false;
            }
        }

        void finish() {
            flush();
        }
    }

    /** 超长单元按句末标点切开，切不动就按字符数硬切（保证每块不超过 max） */
    static List<String> splitLong(String text, int max) {
        List<String> pieces = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int hardEnd = Math.min(text.length(), i + max);
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int cut = lastSentenceBoundary(text, i + Math.max(1, max / 3), hardEnd);
                if (cut > i) end = cut;
            }
            String piece = text.substring(i, end).trim();
            if (!piece.isEmpty()) pieces.add(piece);
            i = end;
        }
        return pieces;
    }

    private static int lastSentenceBoundary(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
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
