package com.cetc28.identity;

import java.util.Set;

/**
 * 当前请求的完整身份：userId 兼容现有会话/记忆隔离，roles/domains 用于知识权限。
 *
 * @param sessionToken 当前会话令牌（用于 iframe 跨站场景回传给前端作 Bearer），匿名时为 null
 */
public record IdentityPrincipal(String userId,
                                String loginName,
                                String displayName,
                                Set<String> roles,
                                Set<String> domains,
                                boolean admin,
                                boolean authenticated,
                                String source,
                                String sessionToken) {

    public static final String ANONYMOUS = "anonymous";

    public static IdentityPrincipal anonymous(String userId) {
        return new IdentityPrincipal(
                userId == null || userId.isBlank() ? ANONYMOUS : userId,
                null, null, Set.of(), Set.of(), false, false, "anonymous", null);
    }

    public static IdentityPrincipal of(String userId, String loginName, String displayName,
                                       Set<String> roles, Set<String> domains, String source,
                                       String sessionToken) {
        boolean admin = roles != null && roles.stream().anyMatch(code -> "admin".equalsIgnoreCase(code));
        return new IdentityPrincipal(userId, loginName, displayName,
                roles == null ? Set.of() : roles,
                domains == null ? Set.of() : domains,
                admin, true, source, sessionToken);
    }

    public boolean hasRole(String code) {
        return code != null && roles.stream().anyMatch(item -> item.equalsIgnoreCase(code));
    }
}
