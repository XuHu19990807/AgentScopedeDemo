package com.cetc28.rag;

import java.util.List;

/**
 * 知识库检索抽象：屏蔽底层向量库（Milvus / 旧版 Redis）差异。
 *
 * <p>上层 {@code ChatService}、控制器、检索工具只依赖本接口，切换实现时不动业务代码。
 */
public interface KnowledgeRetriever {

    enum Mode { HYBRID, BM25, VECTOR }

    RetrievalResult retrieve(String query);

    RetrievalResult retrieve(String query, Mode mode);

    /**
     * @param topK     API 指定的返回条数（≤0 时用配置值）
     * @param minScore API 指定的分数下限（null 时用配置值）
     */
    RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore);

    /**
     * 带域过滤的检索：domain 为空表示全库。默认实现忽略域参数，便于旧实现平滑兼容。
     */
    default RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore, String domain) {
        return retrieve(query, mode, topK, minScore);
    }

    /** 按 chunkId 回读一个块（回答校验/引用回读使用） */
    ChunkView readChunk(String chunkId);

    /**
     * 检索诊断：返回各阶段候选与耗时，供评测和排障使用。
     * 实现可返回 {@code null}；默认实现抛异常提示不支持。
     */
    default Diagnosis diagnose(String query, Mode mode, Integer topK, String domain) {
        throw new UnsupportedOperationException("当前检索实现不支持诊断");
    }

    /** 各阶段候选（均已按该阶段分数降序）。 */
    record Diagnosis(
            String query,
            String mode,
            String domain,
            long tookMs,
            List<ChunkView> dense,
            List<ChunkView> bm25,
            List<ChunkView> fused,
            List<ChunkView> reranked,
            List<ChunkView> selected,
            List<String> degraded) {

        public Diagnosis {
            dense = dense == null ? List.of() : List.copyOf(dense);
            bm25 = bm25 == null ? List.of() : List.copyOf(bm25);
            fused = fused == null ? List.of() : List.copyOf(fused);
            reranked = reranked == null ? List.of() : List.copyOf(reranked);
            selected = selected == null ? List.of() : List.copyOf(selected);
            degraded = degraded == null ? List.of() : List.copyOf(degraded);
        }
    }
}
