package com.cetc28.chat;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发起一次对话生成的请求。
 */
@Data
public class ChatStreamRequest {

    /** 为空时自动新建会话 */
    private String conversationId;

    /** 消息正文；只发文件时可为空 */
    private String content;

    /** chat | deep | orchestrate，默认 chat */
    private String mode;

    /**
     * 仅 orchestrate 生效：true = 各 Agent 并行执行、再由汇总模型合成最终答复；
     * false/null = 沿用串行接力（前序输出累积进下一个 Agent 的上下文）。
     */
    private Boolean parallel;

    /** 本轮引用的上传文件（最多 5 个） */
    @Size(max = 5, message = "最多上传 5 个文件")
    private List<String> fileIds;

    /** 重新生成：保留到该 seq（通常为用户消息的 seq），删除其后的消息 */
    private Long regenerateFromSeq;

    /** 本轮使用的模型配置 id（modelconfig 的 ChatProfile.id）；为空时用全局默认 */
    private String modelId;
}
