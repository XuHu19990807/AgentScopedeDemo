package com.cetc28.dto;

import lombok.Data;

@Data
public class ChatResponse {
    private boolean success;
    private String content;
    private String error;

    public static ChatResponse success(String content) {
        ChatResponse resp = new ChatResponse();
        resp.success = true;
        resp.content = content;
        return resp;
    }

    public static ChatResponse error(String error) {
        ChatResponse resp = new ChatResponse();
        resp.success = false;
        resp.error = error;
        return resp;
    }
}
