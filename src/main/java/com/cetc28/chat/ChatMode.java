package com.cetc28.chat;

/**
 * 对话模式。
 * CHAT  — 默认单 Agent 直答（deepseek-chat）
 * DEEP  — 深度思考（deepseek-reasoner）
 * ORCHESTRATE — 保留的多 Agent 编排
 */
public enum ChatMode {
    CHAT("chat"),
    DEEP("deep"),
    ORCHESTRATE("orchestrate");

    private final String value;

    ChatMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ChatMode from(String raw) {
        if (raw == null || raw.isBlank()) return CHAT;
        for (ChatMode mode : values()) {
            if (mode.value.equalsIgnoreCase(raw.trim())) return mode;
        }
        return CHAT;
    }
}
