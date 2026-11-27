package com.cetc28.chat;

import com.cetc28.identity.AuthService;
import com.cetc28.identity.AuthSessionStore;
import com.cetc28.identity.IdentityContext;
import com.cetc28.identity.IdentityException;
import com.cetc28.identity.IdentityPrincipal;
import com.cetc28.identity.IdentityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 身份解析过滤器：把"大系统登录态 / 本地登录会话 / 匿名"统一解析成 userId。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>本地会话 cookie（{@code app.auth.cookie-name}，默认 dsh_sid）；</li>
 *   <li>{@code Authorization: Bearer <session 或 SSO 令牌>}；</li>
 *   <li>{@code X-SSO-Token} 请求头 / {@code ?sso=} 查询参数（大系统跳转/iframe）；</li>
 *   <li>反向代理受信头（仅白名单来源）；</li>
 *   <li>匿名 cookie（{@code app.auth.anonymous.enabled=true} 时，兼容旧行为）。</li>
 * </ol>
 *
 * <p>无论哪条路径，最终都写回 {@link UserContext#ATTR}，因此现有 16 处
 * {@code UserContext.from(request)} 无需改动即可拿到真实用户。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);

    private static final int ONE_YEAR = 365 * 24 * 3600;
    private static final String BEARER_PREFIX = "Bearer ";

    private final IdentityProperties properties;
    private final AuthService authService;
    private final AuthSessionStore sessions;

    public UserContextFilter(IdentityProperties properties, AuthService authService, AuthSessionStore sessions) {
        this.properties = properties;
        this.authService = authService;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        IdentityPrincipal principal = null;
        try {
            principal = resolve(request, response);
        } catch (IdentityException e) {
            // 令牌无效：不阻断整页静态资源，API 统一按未认证处理
            log.debug("身份解析失败 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        } catch (Exception e) {
            log.warn("身份解析异常（按未认证处理）: {}", e.getMessage());
        }
        if (principal == null) {
            principal = resolveAnonymous(request, response);
        }

        request.setAttribute(IdentityContext.ATTR, principal);
        request.setAttribute(UserContext.ATTR, principal.userId());

        if (requiresAuthentication(request) && !principal.authenticated()) {
            writeUnauthorized(response, request);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private IdentityPrincipal resolve(HttpServletRequest request, HttpServletResponse response) {
        IdentityPrincipal principal = null;

        String cookieToken = cookieValue(request, properties.getAuth().getCookieName());
        if (cookieToken != null) {
            principal = authService.principalFromSession(cookieToken);
        }

        if (principal == null) {
            String header = request.getHeader("Authorization");
            String bearer = header != null && header.startsWith(BEARER_PREFIX)
                    ? header.substring(BEARER_PREFIX.length()).trim() : null;
            if (bearer != null && !bearer.isEmpty()) {
                principal = authService.principalFromSession(bearer);
                if (principal == null) {
                    principal = authService.principalFromSsoToken(bearer);
                }
            }
        }

        if (principal == null) {
            String ssoHeader = request.getHeader("X-SSO-Token");
            String token = ssoHeader != null && !ssoHeader.isBlank() ? ssoHeader.trim() : request.getParameter("sso");
            // 页面跳转场景：换取完整会话并把令牌从地址栏清掉（前端还会 replaceState 兜底）
            if (token != null && !token.isBlank()) {
                AuthService.LoginResult login = authService.exchangeSso(token, clientIp(request));
                writeSessionCookie(request, response, login.sessionToken());
                principal = login.principal();
            }
        }

        if (principal == null && properties.getAuth().getSso().getTrustedHeader().isEnabled()
                && trustedProxy(request.getRemoteAddr())) {
            IdentityProperties.TrustedHeader header = properties.getAuth().getSso().getTrustedHeader();
            String externalUser = request.getHeader(header.getUserHeader());
            if (externalUser != null && !externalUser.isBlank()) {
                String displayName = request.getHeader(header.getNameHeader());
                List<String> roles = splitHeader(request.getHeader(header.getRolesHeader()));
                List<String> domains = splitHeader(request.getHeader(header.getDomainsHeader()));
                principal = authService.trustedHeaderPrincipal(externalUser.trim(), displayName, roles,
                        domains, clientIp(request));
            }
        }
        return principal;
    }

    private IdentityPrincipal resolveAnonymous(HttpServletRequest request, HttpServletResponse response) {
        boolean anonymousEnabled = properties.getAuth().getAnonymous().isEnabled();
        String userId = null;
        if (anonymousEnabled) {
            String value = cookieValue(request, UserContext.COOKIE);
            if (value != null && UserContext.isValid(value)) userId = value;
        }
        if (userId == null) {
            userId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            if (anonymousEnabled) {
                Cookie cookie = new Cookie(UserContext.COOKIE, userId);
                cookie.setPath("/");
                cookie.setMaxAge(ONE_YEAR);
                cookie.setHttpOnly(true);
                response.addCookie(cookie);
            }
        }
        return authService.anonymous(userId);
    }

    private boolean requiresAuthentication(HttpServletRequest request) {
        if (properties.getAuth().getAnonymous().isEnabled()) return false;
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) return false;
        if (uri.startsWith("/api/auth/")) return false;
        if ("/api/health".equals(uri)) return false;
        if ("/api/models".equals(uri)) return false;
        return true;
    }

    private void writeSessionCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(properties.getAuth().getCookieName(), token);
        cookie.setPath("/");
        cookie.setMaxAge(Math.max(60, (int) properties.getAuth().getSessionTtlSeconds()));
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);
    }

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"需要登录\",\"code\":\"unauthorized\"}");
    }

    private boolean trustedProxy(String remoteAddr) {
        if (remoteAddr == null) return false;
        List<String> configured = new ArrayList<>();
        String raw = properties.getAuth().getSso().getTrustedHeader().getTrustedProxies();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) configured.add(part.trim());
            }
        }
        if (configured.isEmpty()) return false;
        for (String proxy : configured) {
            if ("*".equals(proxy)) return true;
            if (proxy.endsWith("*") && remoteAddr.startsWith(proxy.substring(0, proxy.length() - 1))) return true;
            if (proxy.equals(remoteAddr)) return true;
        }
        return false;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (name == null) return null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static List<String> splitHeader(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        for (String part : value.split("[,;]")) {
            String item = part.trim();
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
