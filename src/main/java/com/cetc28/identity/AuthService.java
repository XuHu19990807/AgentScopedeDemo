package com.cetc28.identity;

import com.cetc28.identity.SsoTokenService.SsoClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 身份核心服务：本地登录、SSO 交换、会话解析、对外部用户建档。
 *
 * <p>数据分工：AUTH_* 落 DM；登录会话落 Redis（{@link AuthSessionStore}）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final GrantRepository grants;
    private final AuditRepository audit;
    private final PasswordHasher passwordHasher;
    private final AuthSessionStore sessions;
    private final SsoTokenService ssoTokenService;
    private final IdentityProperties properties;

    public AuthService(UserRepository users,
                       RoleRepository roles,
                       GrantRepository grants,
                       AuditRepository audit,
                       PasswordHasher passwordHasher,
                       AuthSessionStore sessions,
                       SsoTokenService ssoTokenService,
                       IdentityProperties properties) {
        this.users = users;
        this.roles = roles;
        this.grants = grants;
        this.audit = audit;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
        this.ssoTokenService = ssoTokenService;
        this.properties = properties;
    }

    public record LoginResult(IdentityPrincipal principal, String sessionToken, long expiresAt) {
    }

    /* ---------------- 登录 / 登出 ---------------- */

    public LoginResult login(String username, String password, String clientIp) {
        String loginName = normalizeLoginName(username);
        if (loginName.isBlank()) throw IdentityException.badRequest("用户名不能为空");
        if (password == null || password.isEmpty()) throw IdentityException.badRequest("密码不能为空");

        int maxFailures = Math.max(1, properties.getAuth().getLoginMaxFailures());
        if (sessions.failures(loginName) >= maxFailures) {
            throw IdentityException.tooManyRequests("登录失败次数过多，请稍后再试");
        }

        UserAccount account;
        try {
            account = users.findByLoginName(loginName).orElse(null);
        } catch (DataAccessException e) {
            throw IdentityException.unavailable("身份服务不可用：" + e.getMostSpecificCause().getMessage());
        }
        if (account == null || account.passwordHash() == null || account.passwordHash().isBlank()) {
            sessions.recordFailure(loginName);
            audit(clientIp, "login_failed", loginName, "用户不存在或不支持密码登录");
            throw IdentityException.unauthorized("用户名或密码错误");
        }
        if (!account.enabled()) {
            audit(clientIp, "login_failed", account.id(), "账号已禁用");
            throw IdentityException.forbidden("账号已被禁用，请联系管理员");
        }
        if (!passwordHasher.verify(password, account.passwordHash())) {
            sessions.recordFailure(loginName);
            audit(clientIp, "login_failed", account.id(), "密码错误");
            throw IdentityException.unauthorized("用户名或密码错误");
        }

        sessions.clearFailures(loginName);
        users.touchLogin(account.id(), System.currentTimeMillis());
        IdentityPrincipal principal = principalOrAnonymous(account.id());
        AuthSessionStore.Session session = sessions.create(account.id());
        audit(clientIp, "login_success", account.id(), "本地账号登录");
        return new LoginResult(withToken(principal, session.token()), session.token(), session.expiresAt());
    }

    public void logout(String token, String clientIp) {
        if (token == null || token.isBlank()) return;
        String userId = sessions.resolve(token);
        sessions.delete(token);
        if (userId != null) audit(clientIp, "logout", userId, null);
    }

    /* ---------------- SSO ---------------- */

    public LoginResult exchangeSso(String token, String clientIp) {
        SsoClaims claims = ssoTokenService.verify(token);
        UserAccount account = upsertExternalUser(claims.userId(), claims.displayName());
        if (!account.enabled()) {
            throw IdentityException.forbidden("大系统账号已被禁用");
        }
        List<String> assignedRoles = ensureRoles(claims.roles());
        if (!assignedRoles.isEmpty()) {
            List<String> existing = new ArrayList<>();
            for (UserRole role : users.findRoles(account.id())) existing.add(role.id());
            for (String roleId : assignedRoles) if (!existing.contains(roleId)) existing.add(roleId);
            users.replaceUserRoles(account.id(), existing, System.currentTimeMillis());
        }
        users.touchLogin(account.id(), System.currentTimeMillis());
        syncSsoDomains(account.id(), claims.domains(), clientIp);
        IdentityPrincipal principal = principalOrAnonymous(account.id());
        AuthSessionStore.Session session = sessions.create(account.id());
        audit(clientIp, "sso_login", account.id(),
                "系统=" + (claims.system() == null ? "-" : claims.system()));
        return new LoginResult(withToken(principal, session.token()), session.token(), session.expiresAt());
    }

    /** 受信代理头模式：网关已完成登录态校验，这里只做本地建档与角色同步。 */
    public IdentityPrincipal trustedHeaderPrincipal(String externalUserId, String displayName,
                                                    List<String> roleCodes, List<String> domainKeys,
                                                    String clientIp) {
        if (externalUserId == null || externalUserId.isBlank()) return null;
        UserAccount account = upsertExternalUser(externalUserId, displayName);
        if (!account.enabled()) return null;
        List<String> assigned = ensureRoles(roleCodes);
        if (!assigned.isEmpty()) {
            List<String> existing = new ArrayList<>();
            for (UserRole role : users.findRoles(account.id())) existing.add(role.id());
            boolean changed = false;
            for (String roleId : assigned) {
                if (!existing.contains(roleId)) {
                    existing.add(roleId);
                    changed = true;
                }
            }
            if (changed) users.replaceUserRoles(account.id(), existing, System.currentTimeMillis());
        }
        syncSsoDomains(account.id(), domainKeys, clientIp);
        audit(clientIp, "header_login", account.id(), "受信代理头登录");
        return principalOrAnonymous(account.id());
    }

    /** 大系统令牌/网关头带的知识域视为权威授权：非空时覆盖该用户的直接授权，角色授权保持不变。 */
    private void syncSsoDomains(String userId, List<String> domainKeys, String clientIp) {
        if (domainKeys == null || domainKeys.isEmpty()) return;
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String raw : domainKeys) {
            if (raw == null) continue;
            for (String part : raw.split("[,;\\s]+")) {
                String key = part.trim();
                if (key.isEmpty() || key.length() > 200) continue;
                cleaned.add(key);
                if (cleaned.size() >= 50) break;
            }
            if (cleaned.size() >= 50) break;
        }
        if (cleaned.isEmpty()) return;
        try {
            grants.replaceSubjectDomains(GrantRepository.SUBJECT_USER, userId,
                    new ArrayList<>(cleaned), System.currentTimeMillis());
            audit(clientIp, "sso_domains", userId, "同步知识域：" + String.join(",", cleaned));
        } catch (DataAccessException e) {
            log.warn("同步 SSO 知识域失败: {}", e.getMessage());
        }
    }

    private UserAccount upsertExternalUser(String externalId, String displayName) {
        String external = externalId.trim();
        Optional<UserAccount> existing = users.findBySourceAndExternalId(UserAccount.SOURCE_SSO, external);
        if (existing.isPresent()) {
            UserAccount account = existing.get();
            if (displayName != null && !displayName.isBlank()
                    && !displayName.equals(account.displayName())) {
                users.updateProfile(account.id(), displayName, account.email(), account.phone(),
                        account.enabled(), System.currentTimeMillis());
                return users.findById(account.id()).orElse(account);
            }
            return account;
        }
        String loginName = externalLoginName(external);
        long now = System.currentTimeMillis();
        UserAccount created = new UserAccount(IdentityIds.user(), loginName, null,
                displayName == null || displayName.isBlank() ? external : displayName,
                null, null, UserAccount.SOURCE_SSO, external, true, now, now, 0);
        try {
            users.insert(created);
        } catch (DataAccessException e) {
            // 并发首登：另一线程已建档，回读
            return users.findBySourceAndExternalId(UserAccount.SOURCE_SSO, external)
                    .orElseThrow(() -> new IdentityException(
                            org.springframework.http.HttpStatus.CONFLICT,
                            "外部用户建档失败：" + e.getMostSpecificCause().getMessage()));
        }
        return created;
    }

    private String externalLoginName(String external) {
        String candidate = external.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.@-]", "_");
        if (candidate.length() > 100) candidate = candidate.substring(0, 100);
        if (candidate.isBlank()) candidate = "sso_user";
        Optional<UserAccount> clash = users.findByLoginName(candidate);
        if (clash.isEmpty()) return candidate;
        return "sso_" + sha256Hex(external).substring(0, 16);
    }

    private List<String> ensureRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return List.of();
        List<String> roleIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<String> parts = new ArrayList<>();
        for (String raw : roleCodes) {
            if (raw == null) continue;
            for (String part : raw.split("[,;\\s]+")) {
                if (!part.isBlank()) parts.add(part);
            }
        }
        for (String raw : parts) {
            String code = raw.trim().toLowerCase(Locale.ROOT);
            if (code.length() > 120) continue;
            UserRole role = roles.findByCode(code).orElseGet(() -> {
                String name = code;
                UserRole created = new UserRole(IdentityIds.role(), code, name, "SSO 自动同步角色", false, now, now);
                try {
                    roles.insert(created);
                    return created;
                } catch (DataAccessException e) {
                    return roles.findByCode(code).orElse(null);
                }
            });
            if (role != null && !roleIds.contains(role.id())) roleIds.add(role.id());
        }
        return roleIds;
    }

    /* ---------------- 会话与主体解析 ---------------- */

    /** 使该用户现有会话全部失效（改密/停用/删除后调用）。 */
    public void revokeSessions(String userId) {
        sessions.revoke(userId);
    }

    public IdentityPrincipal principalFromSession(String sessionToken) {
        String userId = sessions.resolve(sessionToken);
        if (userId == null) return null;
        Optional<IdentityPrincipal> principal = findPrincipal(userId);
        if (principal.isEmpty()) {
            sessions.delete(sessionToken);
            return null;
        }
        return withToken(principal.get(), sessionToken);
    }

    /** 无状态 SSO 令牌（Authorization/X-SSO-Token）：只校验并解析身份，不新建会话。 */
    public IdentityPrincipal principalFromSsoToken(String token) {
        if (token == null || token.isBlank()) return null;
        SsoClaims claims = ssoTokenService.verify(token);
        Optional<UserAccount> account = users.findBySourceAndExternalId(UserAccount.SOURCE_SSO, claims.userId());
        if (account.isEmpty()) return null;
        return findPrincipal(account.get().id()).orElse(null);
    }

    public Optional<IdentityPrincipal> findPrincipal(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        try {
            UserAccount account = users.findById(userId).orElse(null);
            if (account == null || !account.enabled()) return Optional.empty();
            List<UserRole> userRoles = users.findRoles(userId);
            Set<String> roleCodes = new LinkedHashSet<>();
            List<String> roleIds = new ArrayList<>();
            for (UserRole role : userRoles) {
                roleCodes.add(role.code());
                roleIds.add(role.id());
            }
            Set<String> domains = new LinkedHashSet<>();
            grants.listEffective(roleIds, userId).forEach(grant -> domains.add(grant.domain()));
            return Optional.of(IdentityPrincipal.of(account.id(), account.loginName(),
                    account.display(), roleCodes, domains, account.source(), null));
        } catch (DataAccessException e) {
            log.warn("读取用户身份失败（DM 不可用？）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 工具/非请求线程使用：库中没有该用户（匿名）时返回匿名主体。 */
    public IdentityPrincipal principalOrAnonymous(String userId) {
        if (userId == null || userId.isBlank()) return IdentityPrincipal.anonymous(IdentityPrincipal.ANONYMOUS);
        return findPrincipal(userId).orElseGet(() -> IdentityPrincipal.anonymous(userId));
    }

    public IdentityPrincipal anonymous(String userId) {
        return IdentityPrincipal.anonymous(userId);
    }

    public boolean ssoEnabled() {
        return ssoTokenService.enabled();
    }

    public Set<String> effectiveDomains(String userId) {
        return principalOrAnonymous(userId).domains();
    }

    public void audit(String clientIp, String action, String targetId, String detail) {
        try {
            audit.insert(null, action, targetId, detail, clientIp);
        } catch (DataAccessException e) {
            log.debug("写审计日志失败: {}", e.getMessage());
        }
    }

    public void auditAs(String userId, String clientIp, String action, String targetId, String detail) {
        try {
            audit.insert(userId, action, targetId, detail, clientIp);
        } catch (DataAccessException e) {
            log.debug("写审计日志失败: {}", e.getMessage());
        }
    }

    private IdentityPrincipal withToken(IdentityPrincipal principal, String token) {
        return new IdentityPrincipal(principal.userId(), principal.loginName(), principal.displayName(),
                principal.roles(), principal.domains(), principal.admin(), principal.authenticated(),
                principal.source(), token);
    }

    public static String normalizeLoginName(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
