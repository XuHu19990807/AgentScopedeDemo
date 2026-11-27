package com.cetc28.identity;

import com.cetc28.rag.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 用户 / 角色 / 知识授权 / 审计的管理端业务逻辑。
 *
 * <p>约定：
 * <ul>
 *   <li>受限操作写审计（AUTH_AUDIT），审计失败不影响主流程；</li>
 *   <li>任何授权变化后立即失效 {@link KnowledgeScopeService} 的缓存；</li>
 *   <li>知识域（domain）是现有 RAG 的唯一隔离维度，这里负责把域授予用户/角色。</li>
 * </ul>
 */
@Service
public class IdentityAdminService {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdminService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final GrantRepository grants;
    private final AuditRepository audit;
    private final PasswordHasher hasher;
    private final KnowledgeBaseService knowledgeBase;
    private final KnowledgeScopeService scope;
    private final AuthService authService;
    private final UserProfileService profileService;

    public IdentityAdminService(UserRepository users,
                                RoleRepository roles,
                                GrantRepository grants,
                                AuditRepository audit,
                                PasswordHasher hasher,
                                KnowledgeBaseService knowledgeBase,
                                KnowledgeScopeService scope,
                                AuthService authService,
                                UserProfileService profileService) {
        this.users = users;
        this.roles = roles;
        this.grants = grants;
        this.audit = audit;
        this.hasher = hasher;
        this.knowledgeBase = knowledgeBase;
        this.scope = scope;
        this.authService = authService;
        this.profileService = profileService;
    }

    /* ---------------- 用户 ---------------- */

    public Map<String, Object> listUsers(String query, int limit, int offset) {
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        int start = Math.max(0, offset);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserAccount user : users.list(query, pageSize, start)) {
            items.add(userView(user));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", users.count(query));
        payload.put("items", items);
        return payload;
    }

    public Map<String, Object> userView(UserAccount user) {
        List<UserRole> userRoles = users.findRoles(user.id());
        List<String> domains = domainsOf(user.id());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", user.id());
        item.put("loginName", user.loginName());
        item.put("displayName", user.displayName());
        item.put("email", user.email());
        item.put("phone", user.phone());
        item.put("source", user.source());
        item.put("externalId", user.externalId());
        item.put("enabled", user.enabled());
        item.put("hasPassword", user.passwordHash() != null && !user.passwordHash().isBlank());
        item.put("roles", userRoles.stream().map(UserRole::code).toList());
        item.put("roleNames", userRoles.stream().map(UserRole::name).toList());
        item.put("domains", domains);
        item.put("createdAt", user.createdAt());
        item.put("updatedAt", user.updatedAt());
        item.put("lastLoginAt", user.lastLoginAt());
        return item;
    }

    public Map<String, Object> createUser(String loginName, String password, String displayName,
                                          String email, String phone, List<String> roleCodes,
                                          List<String> domains, String actorId) {
        String normalized = authService.normalizeLoginName(loginName);
        if (normalized.isBlank()) throw IdentityException.badRequest("登录名不能为空");
        if (password == null || password.length() < 6) throw IdentityException.badRequest("密码至少 6 位");
        if (users.findByLoginName(normalized).isPresent()) {
            throw IdentityException.conflict("登录名已存在: " + normalized);
        }
        long now = System.currentTimeMillis();
        UserAccount user = new UserAccount(
                IdentityIds.user(), normalized, hasher.hash(password),
                blankToNull(displayName), blankToNull(email), blankToNull(phone),
                UserAccount.SOURCE_LOCAL, null, true, now, now, 0);
        users.insert(user);
        users.replaceUserRoles(user.id(), resolveRoleIds(roleCodes, true), now);
        grants.replaceSubjectDomains(GrantRepository.SUBJECT_USER, user.id(), cleanDomains(domains), now);
        scope.invalidate(user.id());
        audit(actorId, "user_create", user.id(), "login=" + normalized);
        return userView(users.findById(user.id()).orElseThrow());
    }

    public Map<String, Object> updateUser(String userId, String displayName, String email, String phone,
                                          Boolean enabled, String actorId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> IdentityException.notFound("用户不存在: " + userId));
        if (Boolean.FALSE.equals(enabled) && isLastAdmin(userId)) {
            throw IdentityException.conflict("不能停用唯一的管理员账号");
        }
        if (Boolean.FALSE.equals(enabled)) {
            authService.revokeSessions(userId);
        }
        users.updateProfile(userId, blankToNull(displayName), blankToNull(email), blankToNull(phone),
                enabled == null ? user.enabled() : enabled, System.currentTimeMillis());
        audit(actorId, "user_update", userId, null);
        return userView(users.findById(userId).orElseThrow());
    }

    public void updatePassword(String userId, String password, String actorId) {
        if (password == null || password.length() < 6) throw IdentityException.badRequest("密码至少 6 位");
        users.findById(userId).orElseThrow(() -> IdentityException.notFound("用户不存在: " + userId));
        users.updatePassword(userId, hasher.hash(password), System.currentTimeMillis());
        authService.revokeSessions(userId);
        audit(actorId, "user_password", userId, null);
    }

    public Map<String, Object> setRoles(String userId, List<String> roleCodes, String actorId) {
        users.findById(userId).orElseThrow(() -> IdentityException.notFound("用户不存在: " + userId));
        List<String> ids = resolveRoleIds(roleCodes, false);
        boolean keepsAdmin = ids.stream().map(roles::findById).flatMap(Optional::stream)
                .anyMatch(role -> UserRole.ADMIN_CODE.equalsIgnoreCase(role.code()));
        if (!keepsAdmin && isLastAdmin(userId)) {
            throw IdentityException.conflict("不能移除唯一管理员的管理员角色");
        }
        users.replaceUserRoles(userId, ids, System.currentTimeMillis());
        scope.invalidate(userId);
        audit(actorId, "user_roles", userId, String.join(",", roleCodes == null ? List.of() : roleCodes));
        return userView(users.findById(userId).orElseThrow());
    }

    public Map<String, Object> setDomains(String userId, List<String> domains, String actorId) {
        users.findById(userId).orElseThrow(() -> IdentityException.notFound("用户不存在: " + userId));
        grants.replaceSubjectDomains(GrantRepository.SUBJECT_USER, userId, cleanDomains(domains),
                System.currentTimeMillis());
        scope.invalidate(userId);
        audit(actorId, "user_domains", userId, String.join(",", cleanDomains(domains)));
        return userView(users.findById(userId).orElseThrow());
    }

    public void deleteUser(String userId, String actorId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> IdentityException.notFound("用户不存在: " + userId));
        if (UserAccount.SOURCE_LOCAL.equals(user.source()) && isLastAdmin(userId)) {
            throw IdentityException.conflict("不能删除唯一的管理员账号");
        }
        authService.revokeSessions(userId);
        grants.deleteBySubject(GrantRepository.SUBJECT_USER, userId);
        users.replaceUserRoles(userId, List.of(), System.currentTimeMillis());
        users.setEnabled(userId, false, System.currentTimeMillis());
        scope.invalidate(userId);
        audit(actorId, "user_delete", userId, "login=" + user.loginName());
    }

    /* ---------------- 角色 ---------------- */

    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserRole role : roles.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("roleId", role.id());
            item.put("code", role.code());
            item.put("name", role.name());
            item.put("description", role.description());
            item.put("builtIn", role.builtIn());
            item.put("userCount", users.countByRole(role.id()));
            item.put("domains", grants.listBySubject(GrantRepository.SUBJECT_ROLE, role.id())
                    .stream().map(KbGrant::domain).toList());
            item.put("createdAt", role.createdAt());
            item.put("updatedAt", role.updatedAt());
            items.add(item);
        }
        return items;
    }

    public Map<String, Object> createRole(String code, String name, String description, String actorId) {
        String normalized = normalizeRoleCode(code);
        if (normalized.isBlank()) throw IdentityException.badRequest("角色编码不能为空");
        if (roles.findByCode(normalized).isPresent()) {
            throw IdentityException.conflict("角色编码已存在: " + normalized);
        }
        long now = System.currentTimeMillis();
        UserRole role = new UserRole(IdentityIds.role(), normalized,
                name == null || name.isBlank() ? normalized : name.trim(),
                blankToNull(description), false, now, now);
        roles.insert(role);
        audit(actorId, "role_create", role.id(), "code=" + normalized);
        return listRoles().stream().filter(item -> role.id().equals(item.get("roleId"))).findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    public Map<String, Object> updateRole(String roleId, String name, String description, String actorId) {
        UserRole role = roles.findById(roleId)
                .orElseThrow(() -> IdentityException.notFound("角色不存在: " + roleId));
        roles.update(roleId, name == null || name.isBlank() ? role.name() : name.trim(),
                blankToNull(description), System.currentTimeMillis());
        scope.invalidateAll();
        audit(actorId, "role_update", roleId, null);
        return listRoles().stream().filter(item -> roleId.equals(item.get("roleId"))).findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    public void deleteRole(String roleId, String actorId) {
        UserRole role = roles.findById(roleId)
                .orElseThrow(() -> IdentityException.notFound("角色不存在: " + roleId));
        if (role.builtIn()) throw IdentityException.forbidden("内置角色不能删除");
        long memberCount = users.countByRole(roleId);
        if (memberCount > 0) throw IdentityException.conflict("角色仍有 " + memberCount + " 个用户，请先解除");
        grants.deleteBySubject(GrantRepository.SUBJECT_ROLE, roleId);
        roles.delete(roleId);
        scope.invalidateAll();
        audit(actorId, "role_delete", roleId, "code=" + role.code());
    }

    public Map<String, Object> setRoleDomains(String roleId, List<String> domains, String actorId) {
        roles.findById(roleId).orElseThrow(() -> IdentityException.notFound("角色不存在: " + roleId));
        grants.replaceSubjectDomains(GrantRepository.SUBJECT_ROLE, roleId, cleanDomains(domains),
                System.currentTimeMillis());
        scope.invalidateAll();
        audit(actorId, "role_domains", roleId, String.join(",", cleanDomains(domains)));
        return listRoles().stream().filter(item -> roleId.equals(item.get("roleId"))).findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    /* ---------------- 知识域与审计 ---------------- */

    /** 知识域清单：知识库中实际存在的域 ∪ 已被授予的域，附带文件/分片统计。 */
    public List<Map<String, Object>> listDomains() {
        Map<String, int[]> stats = new TreeMap<>();
        try {
            for (KnowledgeBaseService.FileMeta meta : knowledgeBase.listUploadedFiles()) {
                String domain = meta.effectiveDomain();
                int[] value = stats.computeIfAbsent(domain, key -> new int[2]);
                value[0] += 1;
                value[1] += meta.chunkCount();
            }
        } catch (Exception e) {
            log.warn("读取知识库文件清单失败，仅返回已授权域: {}", e.getMessage());
        }
        for (String domain : grants.listDistinctDomains()) {
            stats.computeIfAbsent(domain, key -> new int[2]);
        }
        stats.computeIfAbsent("default", key -> new int[2]);

        List<Map<String, Object>> items = new ArrayList<>();
        stats.forEach((domain, counts) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            item.put("files", counts[0]);
            item.put("chunks", counts[1]);
            items.add(item);
        });
        return items;
    }

    public List<AuditEntry> listAudit(int limit) {
        return audit.list(Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500)));
    }

    public Map<String, Object> profileOf(String userId) {
        return profileService.profileOf(userId);
    }

    /* ---------------- 内部 ---------------- */

    private List<String> domainsOf(String userId) {
        List<String> roleIds = users.findRoleIds(userId);
        Set<String> domains = new LinkedHashSet<>();
        for (KbGrant grant : grants.listEffective(roleIds, userId)) {
            if (grant.domain() != null && !grant.domain().isBlank()) domains.add(grant.domain());
        }
        return new ArrayList<>(domains);
    }

    private List<String> resolveRoleIds(List<String> roleCodes, boolean defaultUserRole) {
        List<String> codes = new ArrayList<>();
        if (roleCodes != null) {
            for (String code : roleCodes) {
                if (code != null && !code.isBlank()) codes.add(code.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (codes.isEmpty() && defaultUserRole) codes.add(UserRole.USER_CODE);
        List<String> ids = new ArrayList<>();
        for (String code : new LinkedHashSet<>(codes)) {
            UserRole role = roles.findByCode(code)
                    .orElseThrow(() -> IdentityException.badRequest("角色不存在: " + code));
            ids.add(role.id());
        }
        return ids;
    }

    private boolean isLastAdmin(String userId) {
        Optional<UserAccount> user = users.findById(userId);
        if (user.isEmpty()) return false;
        boolean isAdmin = users.findRoles(userId).stream()
                .anyMatch(role -> UserRole.ADMIN_CODE.equalsIgnoreCase(role.code()));
        if (!isAdmin) return false;
        Optional<UserRole> adminRole = roles.findByCode(UserRole.ADMIN_CODE);
        long adminCount = adminRole.map(role -> users.countByRole(role.id())).orElse(0L);
        return adminCount <= 1;
    }

    private void audit(String actorId, String action, String targetId, String detail) {
        try {
            audit.insert(actorId, action, targetId, detail, null);
        } catch (Exception e) {
            log.warn("审计写入失败 action={} target={}: {}", action, targetId, e.getMessage());
        }
    }

    private static List<String> cleanDomains(List<String> domains) {
        List<String> cleaned = new ArrayList<>();
        if (domains == null) return cleaned;
        for (String domain : domains) {
            if (domain == null) continue;
            String value = domain.trim();
            if (value.isEmpty()) continue;
            if (value.length() > 200) value = value.substring(0, 200);
            if (!cleaned.contains(value)) cleaned.add(value);
        }
        return cleaned;
    }

    private static String normalizeRoleCode(String code) {
        if (code == null) return "";
        return code.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
