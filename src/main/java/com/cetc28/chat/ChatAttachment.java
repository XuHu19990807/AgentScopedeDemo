package com.cetc28.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息上的附件引用，随 ChatMessage 持久化，用于历史回显与重新生成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {

    private String id;
    private String name;
    private long size;
    private int chars;

    public static ChatAttachment of(ChatFile file) {
        return ChatAttachment.builder()
                .id(file.getId())
                .name(file.getName())
                .size(file.getSize())
                .chars(file.getChars())
                .build();
    }
}
