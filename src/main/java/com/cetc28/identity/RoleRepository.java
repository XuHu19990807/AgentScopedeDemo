package com.cetc28.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** AUTH_ROLE 的 JDBC 访问。 */
@Repository
public class RoleRepository {

    private static final RowMapper<UserRole> MAPPER = (rs, rowNum) -> new UserRole(
            rs.getString("ROLE_ID"), rs.getString("ROLE_CODE"), rs.getString("ROLE_NAME"),
            rs.getString("ROLE_DESC"), rs.getInt("BUILT_IN") == 1,
            rs.getLong("CREATED_AT"), rs.getLong("UPDATED_AT"));

    private final JdbcTemplate jdbc;

    public RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserRole> list() {
        return jdbc.query("SELECT * FROM AUTH_ROLE ORDER BY BUILT_IN DESC, ROLE_CODE", MAPPER);
    }

    public Optional<UserRole> findById(String roleId) {
        List<UserRole> list = jdbc.query("SELECT * FROM AUTH_ROLE WHERE ROLE_ID = ?", MAPPER, roleId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<UserRole> findByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        List<UserRole> list = jdbc.query("SELECT * FROM AUTH_ROLE WHERE ROLE_CODE = ?", MAPPER,
                code.trim().toLowerCase(Locale.ROOT));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void insert(UserRole role) {
        jdbc.update("INSERT INTO AUTH_ROLE (ROLE_ID, ROLE_CODE, ROLE_NAME, ROLE_DESC, BUILT_IN, "
                        + "CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)",
                role.id(), role.code(), role.name(), role.description(), role.builtIn() ? 1 : 0,
                role.createdAt(), role.updatedAt());
    }

    public void update(String roleId, String name, String description, long updatedAt) {
        jdbc.update("UPDATE AUTH_ROLE SET ROLE_NAME = ?, ROLE_DESC = ?, UPDATED_AT = ? WHERE ROLE_ID = ?",
                name, description, updatedAt, roleId);
    }

    public void delete(String roleId) {
        jdbc.update("DELETE FROM AUTH_ROLE WHERE ROLE_ID = ?", roleId);
    }
}
