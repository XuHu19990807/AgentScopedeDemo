package com.cetc28.identity;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 知识权限范围：管理员不受限；普通用户 = 用户直接授权 ∪ 角色授权。
 *
 * <p>检索入口（自动 RAG / agentic RAG 工具）都通过这里取范围，保证两条链路权限一致。
 * 结果缓存 30 秒，授权变更时由管理服务主动失效。
 */
@Service
public class KnowledgeScopeService {

    private static final long CACHE_TTL_MS = 30_000;

    private final AuthService authService;
    private final IdentityProperties properties;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    public KnowledgeScopeService(AuthService authService, IdentityProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    /**
     * @param admin      管理员：不受限
     * @param denyAll    无任何授权且配置为拒绝
     * @param domains    有效知识域（admin/denyAll 时无意义）
     */
    public record Scope(boolean admin, boolean denyAll, Set<String> domains) {
        public boolean unrestricted() {
            return admin;
        }
    }

    private record Cached(Scope scope, long expiresAt) {
    }

    public Scope scopeFor(String userId) {
        String key = userId == null || userId.isBlank() ? IdentityPrincipal.ANONYMOUS : userId;
        Cached cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return cached.scope();

        IdentityPrincipal principal = authService.principalOrAnonymous(key);
        Scope scope;
        if (principal.admin()) {
            scope = new Scope(true, false, Set.of());
        } else {
            Set<String> domains = new LinkedHashSet<>(principal.domains());
            if (domains.isEmpty()) {
                if (properties.getKnowledge().isDenyAllWhenUnassigned()) {
                    scope = new Scope(false, true, Set.of());
                } else {
                    domains.addAll(parseDomains(properties.getKnowledge().getDefaultReadDomains()));
                    scope = new Scope(false, false, domains);
                }
            } else {
                scope = new Scope(false, false, domains);
            }
        }
        cache.put(key, new Cached(scope, now + CACHE_TTL_MS));
        return scope;
    }

    /** 传给检索器的域集合：null=不受限，空集=拒绝，非空=过滤。 */
    public Set<String> filterDomains(String userId) {
        Scope scope = scopeFor(userId);
        if (scope.admin()) return null;
        return scope.domains();
    }

    public boolean canRead(String userId, String domain) {
        Scope scope = scopeFor(userId);
        if (scope.admin()) return true;
        if (scope.denyAll()) return false;
        if (domain == null || domain.isBlank()) return false;
        return scope.domains().contains(domain.trim());
    }

    public void invalidate(String userId) {
        if (userId != null) cache.remove(userId);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private static Set<String> parseDomains(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return result;
        for (String part : value.split(",")) {
            String domain = part.trim();
            if (!domain.isEmpty()) result.add(domain);
        }
        return result;
    }
}
