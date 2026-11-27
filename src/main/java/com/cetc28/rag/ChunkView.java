package com.cetc28.rag;

/**
 * 统一的文本块视图（与存储实现解耦）。
 *
 * @param chunkId   稳定块 ID（docId:序号），引用与校验都靠它
 * @param docId     文档 ID（文件名 sha256 前 16 位）
 * @param chunkIndex 文档内序号
 * @param content   块正文
 * @param parentText 父块（小节）正文，注入模型时优先使用，可为空
 * @param filename  来源文件名
 * @param headingPath 标题路径
 * @param lineStart 起始行号（1 基）
 * @param lineEnd   结束行号
 * @param score     检索分数（未检索时为 0）
 * @param domain    业务域（检索隔离用；空视为 default）
 */
public record ChunkView(
        String chunkId,
        String docId,
        int chunkIndex,
        String content,
        String parentText,
        String filename,
        String headingPath,
        int lineStart,
        int lineEnd,
        double score,
        String domain) {

    public static final String DEFAULT_DOMAIN = "default";

    /** 兼容旧构造：域缺省为 default。 */
    public ChunkView(String chunkId, String docId, int chunkIndex, String content, String parentText,
                     String filename, String headingPath, int lineStart, int lineEnd, double score) {
        this(chunkId, docId, chunkIndex, content, parentText, filename, headingPath, lineStart, lineEnd,
                score, DEFAULT_DOMAIN);
    }

    /** 生效域：空值归一为 default。 */
    public String effectiveDomain() {
        return domain == null || domain.isBlank() ? DEFAULT_DOMAIN : domain;
    }

    /** 注入/重排时使用的正文：有父块用父块，否则用块本身。 */
    public String text() {
        return parentText == null || parentText.isBlank() ? content : parentText;
    }

    public static String textOf(ChunkView chunk) {
        if (chunk == null) return "";
        String text = chunk.text();
        return text == null ? "" : text;
    }

    public String location() {
        StringBuilder sb = new StringBuilder();
        if (headingPath != null && !headingPath.isBlank()) sb.append(headingPath);
        if (lineStart > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("第 ").append(lineStart).append('-').append(lineEnd).append(" 行");
        }
        return sb.toString();
    }
}
