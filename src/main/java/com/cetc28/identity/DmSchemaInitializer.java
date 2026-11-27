package com.cetc28.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 达梦表结构初始化：启动时按 {@code db/dm-schema.sql} 幂等建表/建索引，并写入内置角色与管理员。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>DM 不支持通用的 {@code CREATE TABLE IF NOT EXISTS}（且不同版本行为不一致），
 *       这里逐条执行并识别"对象已存在"错误后跳过；</li>
 *   <li>初始化失败只记录错误、不阻断应用启动——DM 故障时对话仍可匿名使用，
 *       状态通过 {@code /api/health} 的 dm/identity 字段暴露。</li>
 * </ul>
 */
@Component
public class DmSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DmSchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final IdentityProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;

    @Value("${app.auth.bootstrap-admin.username:admin}")
    private String bootstrapUsername;
    @Value("${app.auth.bootstrap-admin.password:admin123}")
    private String bootstrapPassword;

    private volatile boolean schemaReady = false;
    private volatile String lastError = null;

    public DmSchemaInitializer(JdbcTemplate jdbc,
                               IdentityProperties properties,
                               UserRepository users,
                               RoleRepository roles,
                               PasswordHasher passwordHasher) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getAuth().getSchemaInit().isEnabled()) {
            log.warn("用户体系建表已关闭（app.auth.schema-init.enabled=false），跳过初始化");
            return;
        }
        try {
            executeSchema();
            seedRoles();
            seedBootstrapAdmin();
            schemaReady = true;
            log.info("用户体系数据表就绪（schema={}）", jdbc.queryForObject("SELECT USER FROM DUAL", String.class));
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("用户体系初始化失败（DM 不可用？），将以匿名模式继续运行: {}", e.toString());
        }
    }

    public boolean schemaReady() {
        return schemaReady;
    }

    public String lastError() {
        return lastError;
    }

    private void executeSchema() throws Exception {
        List<String> statements = loadStatements();
        int created = 0;
        for (String statement : statements) {
            try {
                jdbc.execute(statement);
                created++;
            } catch (Exception e) {
                if (isAlreadyExists(e)) {
                    log.debug("DDL 对象已存在，跳过: {}", firstLine(statement));
                } else {
                    log.warn("DDL 执行失败（继续尝试后续语句）: {} -> {}", firstLine(statement), e.getMessage());
                }
            }
        }
        log.info("用户体系 DDL 执行完成：{} 条语句，本次新建/变更 {} 条", statements.size(), created);
    }

    private List<String> loadStatements() throws Exception {
        try (InputStream input = new ClassPathResource("db/dm-schema.sql").getInputStream()) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder cleaned = new StringBuilder();
            for (String line : sql.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
                cleaned.append(line).append('\n');
            }
            List<String> statements = new ArrayList<>();
            for (String part : cleaned.toString().split(";")) {
                if (!part.isBlank()) statements.add(part.trim());
            }
            return statements;
        }
    }

    private void seedRoles() {
        long now = System.currentTimeMillis();
        ensureRole(UserRole.ADMIN_CODE, "管理员", "系统管理员：可管理用户、角色与知识授权", now);
        ensureRole(UserRole.USER_CODE, "普通用户", "默认角色：仅能使用对话与已授权知识", now);
    }

    private void ensureRole(String code, String name, String description, long now) {
        if (roles.findByCode(code).isPresent()) return;
        roles.insert(new UserRole(IdentityIds.role(), code, name, description, true, now, now));
        log.info("已创建内置角色: {}", code);
    }

    private void seedBootstrapAdmin() {
        String username = bootstrapUsername == null ? "" : bootstrapUsername.trim().toLowerCase();
        if (username.isBlank() || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            log.warn("未配置引导管理员账号/密码（app.auth.bootstrap-admin.*），跳过创建");
            return;
        }
        UserAccount existing = users.findByLoginName(username).orElse(null);
        if (existing == null) {
            long now = System.currentTimeMillis();
            UserAccount created = new UserAccount(IdentityIds.user(), username,
                    passwordHasher.hash(bootstrapPassword), "系统管理员", null, null,
                    UserAccount.SOURCE_LOCAL, null, true, now, now, 0);
            users.insert(created);
            roles.findByCode(UserRole.ADMIN_CODE).ifPresent(role ->
                    users.replaceUserRoles(created.id(), List.of(role.id()), now));
            log.warn("已创建引导管理员 {}（请尽快登录后修改密码）", username);
            return;
        }
        List<UserRole> existingRoles = users.findRoles(existing.id());
        boolean hasAdmin = existingRoles.stream()
                .anyMatch(role -> UserRole.ADMIN_CODE.equalsIgnoreCase(role.code()));
        if (!hasAdmin) {
            roles.findByCode(UserRole.ADMIN_CODE).ifPresent(role ->
                    users.replaceUserRoles(existing.id(),
                            concatRoleIds(existingRoles, role.id()), System.currentTimeMillis()));
        }
    }

    private List<String> concatRoleIds(List<UserRole> existing, String extraRoleId) {
        List<String> ids = new ArrayList<>();
        for (UserRole role : existing) ids.add(role.id());
        if (!ids.contains(extraRoleId)) ids.add(extraRoleId);
        return ids;
    }

    private static boolean isAlreadyExists(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 6) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("已存在") || lower.contains("exists") || lower.contains("duplicate")
                        || lower.contains("同名")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String firstLine(String statement) {
        int index = statement.indexOf('\n');
        return index < 0 ? statement : statement.substring(0, index);
    }
}
