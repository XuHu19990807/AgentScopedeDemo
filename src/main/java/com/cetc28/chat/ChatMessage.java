package com.cetc28.chat;

import com.cetc28.map.MapCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条对话消息。用户消息只有 content；助手消息还包含 reasoning / toolCalls / usage。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_ERROR = "error";

    /** 会话内序号，从 1 开始；Redis LIST 的下标即 seq - 1 */
    private long seq;

    private String id;
    private String role;
    private String content;

    /** 推理过程（reasoning / thinking） */
    private String reasoning;

    @Builder.Default
    private List<ToolCallRecord> toolCalls = new ArrayList<>();

    /** 用户消息携带的附件；旧消息为空列表 */
    @Builder.Default
    private List<ChatAttachment> attachments = new ArrayList<>();

    /** 助手消息生成的文件（Word/Excel/TXT 等）；旧消息为空列表 */
    @Builder.Default
    private List<ChatAttachment> outputs = new ArrayList<>();

    /** 本轮地图指令，用于刷新/切换会话后回放；旧消息为空列表 */
    @Builder.Default
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MapCommand> mapCommands = new ArrayList<>();

    /** completed | cancelled | error */
    private String status;

    private String error;

    /** chat | deep | orchestrate */
    private String mode;

    /**
     * 助手消息回答所依据的知识库片段（K1、K2…）。旧消息为空列表。
     * 与 SSE 的 sources 事件内容一致，用于刷新页面后仍能核对答案出处。
     */
    @Builder.Default
    private List<com.cetc28.rag.Citation> sources = new ArrayList<>();

    private String model;

    /** 实际使用的模型配置 id（modelconfig 的 ChatProfile.id）；旧消息为空 */
    private String modelId;

    /** 本轮实际耗时（墙钟，毫秒）；0 表示旧消息未记录 */
    private long elapsedMs;

    /** 本轮所有工具占用时间的并集（毫秒，并行不重复累加）；0 表示旧消息未记录 */
    private long toolsWallMs;

    @Builder.Default
    private Map<String, Integer> usage = new LinkedHashMap<>();

    private long createdAt;
}
