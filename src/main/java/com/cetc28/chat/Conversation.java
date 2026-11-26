package com.cetc28.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话元数据，存 Redis HASH chat:conv:{id}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    private String id;
    private String userId;
    private String title;
    private String mode;
    /** 会话级模型配置 id（对应 modelconfig 的 ChatProfile.id）；空表示跟随全局默认 */
    private String model;
    private long createdAt;
    private long updatedAt;
    private int messageCount;
}
