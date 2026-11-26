package com.cetc28.rag;

/**
 * 回答中的一条知识库引用（SSE sources 事件与消息持久化共用）。
 *
 * @param id       本次回答内的编号，如 K1（仅在单次请求内稳定）
 * @param chunkId  文本块 key，可用于 readChunk 回查
 * @param filename 来源文件
 * @param location 标题路径 + 行号，便于人工核对
 * @param score    检索相关度
 * @param snippet  片段摘要（截断，避免把整块塞进消息记录）
 */
public record Citation(String id, String chunkId, String filename, String location,
                       double score, String snippet) {
}
