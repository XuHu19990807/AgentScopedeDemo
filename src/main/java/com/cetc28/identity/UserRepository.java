package com.cetc28.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** AUTH_USER 与 AUTH_USER_ROLE 的 JDBC 访问。 */
@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getString("USER_ID"),
            rs.getString("LOGIN_NAME"),
            rs.getString("PASSWORD_HASH"),
            rs.getString("DISPLAY_NAME"),
            rs.getString("EMAIL"),
            rs.getString("PHONE_NO"),
            rs.getString("USER_SOURCE"),
            rs.getString("EXTERNAL_ID"),
            rs.getInt("ENABLED") == 1,
            rs.getLong("CREATED_AT"),
            rs.getLong("UPDATED_AT"),
            rs.getLong("LAST_LOGIN_AT"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findById(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        List<UserAccount> list = jdbc.query(
                "SELECT * FROM AUTH_USER WHERE USER_ID = ?", MAPPER, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserAccount> findByLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) return Optional.empty();
        List<UserAccount> list = jdbc.query(
                "SELECT * FROM AUTH_USER WHERE LOGIN_NAME = ?", MAPPER,
                loginName.trim().toLowerCase(Locale.ROOT));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserAccount> findBySourceAndExternalId(String source, String externalId) {
        if (source == null || externalId == null || externalId.isBlank()) return Optional.empty();
        List<UserAccount> list = jdbc.query(
                "SELECT * FROM AUTH_USER WHERE USER_SOURCE = ? AND EXTERNAL_ID = ?", MAPPER,
                source, externalId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<UserAccount> list(String query, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT T.*, ROWNUM RN FROM (SELECT * FROM AUTH_USER");
        List<Object> args = new ArrayList<>();
        appendQuery(sql, args, query);
        sql.append(" ORDER BY CREATED_AT DESC) T WHERE ROWNUM <= ?) WHERE RN > ?");
        args.add(Math.max(1, limit));
        args.add(Math.max(0, offset));
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM AUTH_USER");
        List<Object> args = new ArrayList<>();
        appendQuery(sql, args, query);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public void insert(UserAccount user) {
        jdbc.update("INSERT INTO AUTH_USER (USER_ID, LOGIN_NAME, PASSWORD_HASH, DISPLAY_NAME, EMAIL, "
                        + "PHONE_NO, USER_SOURCE, EXTERNAL_ID, ENABLED, CREATED_AT, UPDATED_AT, LAST_LOGIN_AT) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                user.id(), user.loginName(), user.passwordHash(), user.displayName(), user.email(),
                user.phone(), user.source(), user.externalId(), user.enabled() ? 1 : 0,
                user.createdAt(), user.updatedAt(), user.lastLoginAt());
    }

    public void updateProfile(String userId, String displayName, String email, String phone,
                              boolean enabled, long updatedAt) {
        jdbc.update("UPDATE AUTH_USER SET DISPLAY_NAME = ?, EMAIL = ?, PHONE_NO = ?, ENABLED = ?, "
                        + "UPDATED_AT = ? WHERE USER_ID = ?",
                displayName, email, phone, enabled ? 1 : 0, updatedAt, userId);
    }

    public void updatePassword(String userId, String passwordHash, long updatedAt) {
        jdbc.update("UPDATE AUTH_USER SET PASSWORD_HASH = ?, UPDATED_AT = ? WHERE USER_ID = ?",
                passwordHash, updatedAt, userId);
    }

    public void touchLogin(String userId, long at) {
        jdbc.update("UPDATE AUTH_USER SET LAST_LOGIN_AT = ?, UPDATED_AT = ? WHERE USER_ID = ?",
                at, at, userId);
    }

    public void setEnabled(String userId, boolean enabled, long updatedAt) {
        jdbc.update("UPDATE AUTH_USER SET ENABLED = ?, UPDATED_AT = ? WHERE USER_ID = ?",
                enabled ? 1 : 0, updatedAt, userId);
    }

    public List<String> findRoleIds(String userId) {
        return jdbc.queryForList(
                "SELECT ROLE_ID FROM AUTH_USER_ROLE WHERE USER_ID = ?", String.class, userId);
    }

    public List<UserRole> findRoles(String userId) {
        return jdbc.query("SELECT R.* FROM AUTH_ROLE R JOIN AUTH_USER_ROLE UR ON R.ROLE_ID = UR.ROLE_ID "
                        + "WHERE UR.USER_ID = ? ORDER BY R.ROLE_CODE",
                (rs, rowNum) -> new UserRole(rs.getString("ROLE_ID"), rs.getString("ROLE_CODE"),
                        rs.getString("ROLE_NAME"), rs.getString("ROLE_DESC"),
                        rs.getInt("BUILT_IN") == 1, rs.getLong("CREATED_AT"), rs.getLong("UPDATED_AT")),
                userId);
    }

    public void replaceUserRoles(String userId, List<String> roleIds, long now) {
        jdbc.update("DELETE FROM AUTH_USER_ROLE WHERE USER_ID = ?", userId);
        for (String roleId : roleIds) {
            jdbc.update("INSERT INTO AUTH_USER_ROLE (USER_ID, ROLE_ID, CREATED_AT) VALUES (?, ?, ?)",
                    userId, roleId, now);
        }
    }

    public long countByRole(String roleId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUTH_USER_ROLE WHERE ROLE_ID = ?", Long.class, roleId);
        return count == null ? 0 : count;
    }

    private void appendQuery(StringBuilder sql, List<Object> args, String query) {
        if (query == null || query.isBlank()) return;
        String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        sql.append(" WHERE LOWER(LOGIN_NAME) LIKE ? OR LOWER(DISPLAY_NAME) LIKE ? "
                + "OR LOWER(EXTERNAL_ID) LIKE ?");
        args.add(like);
        args.add(like);
        args.add(like);
    }
}
