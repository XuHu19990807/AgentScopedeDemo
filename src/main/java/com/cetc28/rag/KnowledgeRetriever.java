package com.cetc28.rag;

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

    /** 按 chunkId 回读一个块（回答校验/引用回读使用） */
    ChunkView readChunk(String chunkId);
}
