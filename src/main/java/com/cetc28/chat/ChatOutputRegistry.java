package com.cetc28.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本轮生成的文件暂存区：工具（模型调用）写入，{@link ChatService} 在收尾时取走。
 *
 * 工具拿不到 SSE 通道，只能通过这个按 conversationId 索引的注册表把产物带回
 * ChatService.finish()；同一会话由 RunRegistry 保证串行，因此按会话聚合是安全的。
 */
@Component
public class ChatOutputRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatOutputRegistry.class);

    private final Map<String, List<ChatAttachment>> pending = new ConcurrentHashMap<>();

    /** 记录一个生成文件；同一 fileId 只保留一次。 */
    public void add(String conversationId, ChatAttachment attachment) {
        if (conversationId == null || conversationId.isBlank() || attachment == null) return;
        List<ChatAttachment> list = pending.computeIfAbsent(conversationId, key -> new ArrayList<>());
        synchronized (list) {
            for (ChatAttachment existing : list) {
                if (existing.getId() != null && existing.getId().equals(attachment.getId())) return;
            }
            list.add(attachment);
        }
        log.debug("登记生成文件 cid={}, file={}", conversationId, attachment.getName());
    }

    /** 取走并清空该会话的生成文件。 */
    public List<ChatAttachment> drain(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        List<ChatAttachment> list = pending.remove(conversationId);
        if (list == null) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /** 丢弃残留（新一轮生成开始时调用）。 */
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (pending.remove(conversationId) != null) {
            log.debug("清理上一轮残留的生成文件 cid={}", conversationId);
        }
    }
}
