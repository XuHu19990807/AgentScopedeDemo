package com.cetc28.chat;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前请求的匿名用户标识（cookie dsh_uid）。后续接入登录体系时只需替换这里的来源。
 */
public final class UserContext {

    public static final String ATTR = "dsh.userId";
    public static final String COOKIE = "dsh_uid";

    private UserContext() {
    }

    public static String from(HttpServletRequest request) {
        if (request == null) return "anonymous";
        Object value = request.getAttribute(ATTR);
        return value != null ? value.toString() : "anonymous";
    }

    public static boolean isValid(String userId) {
        return userId != null && userId.matches("[A-Za-z0-9_-]{1,64}");
    }
}
