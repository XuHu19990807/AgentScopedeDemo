package com.cetc28.chat;

import java.util.List;
import java.util.Optional;

/**
 * 会话与消息存储。产品数据（会话/消息）与 AgentScope 的 agentstate 分离：
 * agentstate 只负责 Agent 上下文，这里负责用户可见的对话历史。
 */
public interface ConversationStore {

    Conversation create(String userId);

    Optional<Conversation> get(String conversationId, String userId);

    List<Conversation> list(String userId, int limit);

    void rename(String conversationId, String title);

    void updateMode(String conversationId, String mode);

    /** 记录会话级模型配置 id；默认实现为空，便于测试替身不感知该能力。 */
    default void updateModel(String conversationId, String modelId) {
    }

    /** 追加一条消息并返回带 seq/id 的实例 */
    ChatMessage appendMessage(String conversationId, ChatMessage message);

    List<ChatMessage> recentMessages(String conversationId, int limit);

    long messageCount(String conversationId);

    /** 保留 seq <= keepSeq 的消息（用于重新生成） */
    void truncate(String conversationId, long keepSeq);

    void delete(String conversationId, String userId);

    void deleteAll(String userId);
}
