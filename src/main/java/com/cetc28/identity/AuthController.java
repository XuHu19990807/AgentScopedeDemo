package com.cetc28.identity;

import com.cetc28.chat.UserContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录 / 单点登录 / 当前身份。所有响应都包含 {@code authenticated}，前端据此渲染登录态。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final IdentityProperties properties;

    public AuthController(AuthService authService, IdentityProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    public record LoginRequest(String username, String password) {
    }

    public record SsoRequest(String token) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(
                body == null ? null : body.username(),
                body == null ? null : body.password(),
                clientIp(request));
        writeSessionCookie(request, response, result.sessionToken(), result.expiresAt());
        return sessionPayload(result.principal(), result.sessionToken(), result.expiresAt());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = tokenFrom(request);
        authService.logout(token, clientIp(request));
        clearSessionCookie(request, response);
        return Map.of("ok", true);
    }

    /** 大系统跳转 / iframe postMessage 拿到的令牌，换取本系统会话。 */
    @PostMapping("/sso/exchange")
    public Map<String, Object> exchange(@RequestBody SsoRequest body,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if (body == null || body.token() == null || body.token().isBlank()) {
            throw IdentityException.badRequest("缺少 SSO 令牌");
        }
        AuthService.LoginResult result = authService.exchangeSso(body.token().trim(), clientIp(request));
        writeSessionCookie(request, response, result.sessionToken(), result.expiresAt());
        return sessionPayload(result.principal(), result.sessionToken(), result.expiresAt());
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        IdentityPrincipal principal = IdentityContext.from(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authenticated", principal.authenticated());
        payload.put("userId", principal.userId());
        payload.put("loginName", principal.loginName());
        payload.put("displayName", principal.displayName());
        payload.put("roles", new ArrayList<>(principal.roles()));
        payload.put("domains", new ArrayList<>(principal.domains()));
        payload.put("admin", principal.admin());
        payload.put("source", principal.source());
        payload.put("loginRequired", !properties.getAuth().getAnonymous().isEnabled());
        payload.put("ssoEnabled", authService.ssoEnabled());
        payload.put("sessionToken", principal.sessionToken());
        payload.put("embed", Map.of("allowedOrigins", allowedOrigins()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(payload);
    }

    private Map<String, Object> sessionPayload(IdentityPrincipal principal, String token, long expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authenticated", principal.authenticated());
        payload.put("userId", principal.userId());
        payload.put("loginName", principal.loginName());
        payload.put("displayName", principal.displayName());
        payload.put("roles", new ArrayList<>(principal.roles()));
        payload.put("domains", new ArrayList<>(principal.domains()));
        payload.put("admin", principal.admin());
        payload.put("source", principal.source());
        payload.put("sessionToken", token);
        payload.put("expiresAt", expiresAt);
        payload.put("embed", Map.of("allowedOrigins", allowedOrigins()));
        return payload;
    }

    private List<String> allowedOrigins() {
        List<String> origins = new ArrayList<>();
        String raw = properties.getEmbed().getAllowedOrigins();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) origins.add(part.trim());
            }
        }
        return origins;
    }

    private void writeSessionCookie(HttpServletRequest request, HttpServletResponse response,
                                    String token, long expiresAt) {
        Cookie cookie = new Cookie(properties.getAuth().getCookieName(), token);
        cookie.setPath("/");
        long seconds = Math.max(60, expiresAt > 0 ? (expiresAt - System.currentTimeMillis()) / 1000
                : properties.getAuth().getSessionTtlSeconds());
        cookie.setMaxAge((int) Math.min(seconds, Integer.MAX_VALUE));
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
    }

    private void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(properties.getAuth().getCookieName(), "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
    }

    private String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        String cookieName = properties.getAuth().getCookieName();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) return cookie.getValue();
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
