package com.cetc28.identity;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 身份信息在请求内的传递载体。过滤器写入，控制器/工具读取。
 */
public final class IdentityContext {

    public static final String ATTR = "agentchat.identity";

    private IdentityContext() {
    }

    public static IdentityPrincipal from(HttpServletRequest request) {
        if (request == null) return IdentityPrincipal.anonymous(IdentityPrincipal.ANONYMOUS);
        Object value = request.getAttribute(ATTR);
        if (value instanceof IdentityPrincipal principal) return principal;
        return IdentityPrincipal.anonymous(IdentityPrincipal.ANONYMOUS);
    }
}
