package com.cetc28.rag;

import java.util.List;

/**
 * 一次检索的完整结果（含降级原因，便于前端/日志解释"为什么没检索到"）。
 */
public record RetrievalResult(String query,
                              List<Hit> hits,
                              boolean degraded,
                              String degradedReason,
                              long tookMs,
                              int bm25Count,
                              int vectorCount) {

    /**
     * @param chunkId    文本块的 Redis key（doc:knowledge:{docId}:{seq}），用于回查与引用校验
     * @param score      融合后的分数（RRF；单路模式下为原始分数归一化值）
     * @param mode       bm25 / vector / hybrid
     */
    public record Hit(String chunkId, String content, String filename, String headingPath,
                      int lineStart, int lineEnd, double score, String mode) {

        public String location() {
            StringBuilder sb = new StringBuilder();
            if (headingPath != null && !headingPath.isBlank()) sb.append(headingPath);
            if (lineStart > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("第 ").append(lineStart);
                if (lineEnd > lineStart) sb.append('-').append(lineEnd);
                sb.append(" 行");
            }
            return sb.toString();
        }

        public String snippet(int max) {
            if (content == null) return "";
            String flat = content.replaceAll("\\s+", " ").trim();
            return flat.length() <= max ? flat : flat.substring(0, max) + "…";
        }
    }

    public static RetrievalResult empty(String query, String reason) {
        return new RetrievalResult(query, List.of(), reason != null, reason, 0L, 0, 0);
    }

    public boolean isEmpty() {
        return hits == null || hits.isEmpty();
    }
}
