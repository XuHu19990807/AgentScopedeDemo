package com.cetc28.config;

import com.cetc28.identity.IdentityContext;
import com.cetc28.identity.IdentityPrincipal;
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
 * - 已登录用户：只有 admin 角色可通过，普通用户一律拒绝；
 * - 未登录/匿名：本机访问（127.0.0.1 / ::1）放行，方便本地使用；
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
        // 登录态优先：普通用户即使在本机也不能进入管理接口
        IdentityPrincipal identity = IdentityContext.from(request);
        if (identity != null && identity.authenticated()) {
            if (identity.admin()) return true;
            log.warn("拒绝非管理员访问管理接口: {} {} user={}", request.getMethod(),
                    request.getRequestURI(), identity.userId());
            deny(response);
            return false;
        }
        if (isLoopback(request.getRemoteAddr())) return true;
        if (adminToken != null && !adminToken.isBlank()
                && adminToken.equals(request.getHeader("X-Admin-Token"))) {
            return true;
        }
        log.warn("拒绝远程管理请求: {} {} from {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr());
        deny(response);
        return false;
    }

    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"管理接口需要管理员令牌或管理员登录\",\"code\":\"forbidden\"}");
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }
}
