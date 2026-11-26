package com.cetc28.chat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个浏览器分配匿名 userId（cookie），用于会话隔离。
 * 不是登录体系；接入 JWT 后只需把这里换成解析 token。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserContextFilter extends OncePerRequestFilter {

    private static final int ONE_YEAR = 365 * 24 * 3600;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (UserContext.COOKIE.equals(cookie.getName()) && UserContext.isValid(cookie.getValue())) {
                    userId = cookie.getValue();
                    break;
                }
            }
        }
        if (userId == null) {
            userId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Cookie cookie = new Cookie(UserContext.COOKIE, userId);
            cookie.setPath("/");
            cookie.setMaxAge(ONE_YEAR);
            cookie.setHttpOnly(true);
            response.addCookie(cookie);
        }
        request.setAttribute(UserContext.ATTR, userId);
        filterChain.doFilter(request, response);
    }
}
