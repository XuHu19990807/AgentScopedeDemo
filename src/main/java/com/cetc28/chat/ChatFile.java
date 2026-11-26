package com.cetc28.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话附件元数据，存 Redis HASH chat:file:{id} 的 meta 字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatFile {

    private String id;
    private String userId;
    private String conversationId;
    private String name;
    /** 物理文件名：{id}_{sanitizedName} */
    private String storedName;
    private String ext;
    private long size;
    private int chars;
    private int chunks;
    /** 前 200 字符摘要，仅用于上传响应 */
    private String preview;
    /** 来源：upload（用户上传）| generated（模型生成） */
    private String source;
    private long createdAt;
}
