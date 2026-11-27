package com.cetc28.identity;

import com.cetc28.chat.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户与角色管理 API（管理台使用）。
 *
 * <p>访问控制由 {@link com.cetc28.config.AdminGuard} 负责：本机 / X-Admin-Token / 已登录的 admin 角色。
 */
@RestController
@RequestMapping("/api/admin/identity")
public class IdentityAdminController {

    private final IdentityAdminService service;

    public IdentityAdminController(IdentityAdminService service) {
        this.service = service;
    }

    public record CreateUserRequest(String loginName, String password, String displayName, String email,
                                    String phone, List<String> roles, List<String> domains) {
    }

    public record UpdateUserRequest(String displayName, String email, String phone, Boolean enabled) {
    }

    public record PasswordRequest(String password) {
    }

    public record RolesRequest(List<String> roles) {
    }

    public record DomainsRequest(List<String> domains) {
    }

    public record RoleRequest(String code, String name, String description) {
    }

    /* ---------------- 用户 ---------------- */

    @GetMapping("/users")
    public Map<String, Object> listUsers(@RequestParam(required = false) String query,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return service.listUsers(query, limit, offset);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserRequest body, HttpServletRequest request) {
        return service.createUser(body.loginName(), body.password(), body.displayName(), body.email(),
                body.phone(), body.roles(), body.domains(), actor(request));
    }

    @PatchMapping("/users/{userId}")
    public Map<String, Object> updateUser(@PathVariable String userId,
                                          @RequestBody UpdateUserRequest body,
                                          HttpServletRequest request) {
        return service.updateUser(userId, body.displayName(), body.email(), body.phone(),
                body.enabled(), actor(request));
    }

    @PutMapping("/users/{userId}/password")
    public Map<String, Object> updatePassword(@PathVariable String userId,
                                              @RequestBody PasswordRequest body,
                                              HttpServletRequest request) {
        service.updatePassword(userId, body.password(), actor(request));
        return Map.of("ok", true);
    }

    @PutMapping("/users/{userId}/roles")
    public Map<String, Object> setRoles(@PathVariable String userId,
                                        @RequestBody RolesRequest body,
                                        HttpServletRequest request) {
        return service.setRoles(userId, body.roles(), actor(request));
    }

    @PutMapping("/users/{userId}/domains")
    public Map<String, Object> setDomains(@PathVariable String userId,
                                          @RequestBody DomainsRequest body,
                                          HttpServletRequest request) {
        return service.setDomains(userId, body.domains(), actor(request));
    }

    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable String userId, HttpServletRequest request) {
        service.deleteUser(userId, actor(request));
        return Map.of("ok", true);
    }

    @GetMapping("/users/{userId}/profile")
    public Map<String, Object> profile(@PathVariable String userId) {
        return service.profileOf(userId);
    }

    /* ---------------- 角色 ---------------- */

    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles() {
        return service.listRoles();
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody RoleRequest body, HttpServletRequest request) {
        return service.createRole(body.code(), body.name(), body.description(), actor(request));
    }

    @PatchMapping("/roles/{roleId}")
    public Map<String, Object> updateRole(@PathVariable String roleId,
                                          @RequestBody RoleRequest body,
                                          HttpServletRequest request) {
        return service.updateRole(roleId, body.name(), body.description(), actor(request));
    }

    @DeleteMapping("/roles/{roleId}")
    public Map<String, Object> deleteRole(@PathVariable String roleId, HttpServletRequest request) {
        service.deleteRole(roleId, actor(request));
        return Map.of("ok", true);
    }

    @PutMapping("/roles/{roleId}/domains")
    public Map<String, Object> setRoleDomains(@PathVariable String roleId,
                                              @RequestBody DomainsRequest body,
                                              HttpServletRequest request) {
        return service.setRoleDomains(roleId, body.domains(), actor(request));
    }

    /* ---------------- 知识域 / 审计 ---------------- */

    @GetMapping("/domains")
    public List<Map<String, Object>> listDomains() {
        return service.listDomains();
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "100") int limit) {
        return service.listAudit(limit);
    }

    private static String actor(HttpServletRequest request) {
        String userId = UserContext.from(request);
        return userId == null || userId.isBlank() ? "system" : userId;
    }
}
