package com.cetc28.chat;

/**
 * 文件文本分块，index 从 0 开始；行号用于回答引用。
 */
public record ChatFileChunk(int index, int startLine, int endLine, String text) {
}
