package com.cetc28.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理接口保护：
 * - 本机访问（127.0.0.1 / ::1）直接放行，方便本地使用；
 * - 远程访问必须携带 X-Admin-Token（配置 app.admin-token）。
 *
 * 这样即使部署在内网，也不会因为 MCP 管理接口导致任意命令执行。
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    @Value("${app.admin-token:}")
    private String adminToken = "";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isLoopback(request.getRemoteAddr())) return true;
        if (adminToken != null && !adminToken.isBlank()
                && adminToken.equals(request.getHeader("X-Admin-Token"))) {
            return true;
        }
        log.warn("拒绝远程管理请求: {} {} from {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"管理接口需要管理员令牌\"}");
        return false;
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }
}
