package com.cetc28.rag.milvus;

/**
 * 入库时使用的规范化文本块（与具体向量库解耦）。
 *
 * <p>content 是干净正文（用于注入与展示）；embeddingText 额外拼上文档名与章节路径，
 * 让脱离原文的块也能被语义检索命中。
 */
public record StoredChunk(
        String chunkId,
        String docId,
        int chunkIndex,
        String content,
        String parentText,
        String filename,
        String headingPath,
        int lineStart,
        int lineEnd,
        String contentHash) {

    public String embeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("文档《").append(filename == null ? "" : filename).append("》");
        if (headingPath != null && !headingPath.isBlank()) {
            sb.append("｜章节：").append(headingPath);
        }
        sb.append('\n').append(content == null ? "" : content);
        return sb.toString();
    }
}
