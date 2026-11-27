package com.cetc28.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** AUTH_AUDIT 的 JDBC 访问。 */
@Repository
public class AuditRepository {

    private static final RowMapper<AuditEntry> MAPPER = (rs, rowNum) -> new AuditEntry(
            rs.getString("LOG_ID"), rs.getString("USER_ID"), rs.getString("ACTION_NAME"),
            rs.getString("TARGET_ID"), rs.getString("DETAIL"), rs.getString("CLIENT_IP"),
            rs.getLong("CREATED_AT"));

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String userId, String action, String targetId, String detail, String clientIp) {
        jdbc.update("INSERT INTO AUTH_AUDIT (LOG_ID, USER_ID, ACTION_NAME, TARGET_ID, DETAIL, "
                        + "CLIENT_IP, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?)",
                IdentityIds.audit(), userId, action, targetId, truncate(detail, 1900), clientIp,
                System.currentTimeMillis());
    }

    public List<AuditEntry> list(int limit) {
        return jdbc.query("SELECT * FROM (SELECT T.*, ROWNUM RN FROM (SELECT * FROM AUTH_AUDIT "
                        + "ORDER BY CREATED_AT DESC) T WHERE ROWNUM <= ?) WHERE RN > 0",
                MAPPER, Math.max(1, limit));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
