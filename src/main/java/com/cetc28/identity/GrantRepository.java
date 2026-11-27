package com.cetc28.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** AUTH_KB_GRANT 的 JDBC 访问：主体（角色/用户）→ 知识域。 */
@Repository
public class GrantRepository {

    public static final String SUBJECT_ROLE = "role";
    public static final String SUBJECT_USER = "user";
    public static final String ACCESS_READ = "read";
    public static final String ACCESS_WRITE = "write";

    private static final RowMapper<KbGrant> MAPPER = (rs, rowNum) -> new KbGrant(
            rs.getString("GRANT_ID"), rs.getString("SUBJECT_TYPE"), rs.getString("SUBJECT_ID"),
            rs.getString("DOMAIN_KEY"), rs.getString("ACCESS_LEVEL"), rs.getLong("CREATED_AT"));

    private final JdbcTemplate jdbc;

    public GrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<KbGrant> listAll() {
        return jdbc.query("SELECT * FROM AUTH_KB_GRANT ORDER BY SUBJECT_TYPE, SUBJECT_ID, DOMAIN_KEY", MAPPER);
    }

    public List<KbGrant> listBySubject(String subjectType, String subjectId) {
        return jdbc.query("SELECT * FROM AUTH_KB_GRANT WHERE SUBJECT_TYPE = ? AND SUBJECT_ID = ? "
                + "ORDER BY DOMAIN_KEY", MAPPER, subjectType, subjectId);
    }

    /** 用户直接授权 + 所属角色授权的并集。 */
    public List<KbGrant> listEffective(Collection<String> roleIds, String userId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM AUTH_KB_GRANT WHERE (");
        if (roleIds != null && !roleIds.isEmpty()) {
            sql.append("(SUBJECT_TYPE = ? AND SUBJECT_ID IN (");
            sql.append("?,".repeat(roleIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")) OR ");
            args.add(SUBJECT_ROLE);
            args.addAll(roleIds);
        }
        sql.append("(SUBJECT_TYPE = ? AND SUBJECT_ID = ?)) ORDER BY DOMAIN_KEY");
        args.add(SUBJECT_USER);
        args.add(userId);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public void replaceSubjectDomains(String subjectType, String subjectId, List<String> domains, long now) {
        jdbc.update("DELETE FROM AUTH_KB_GRANT WHERE SUBJECT_TYPE = ? AND SUBJECT_ID = ?",
                subjectType, subjectId);
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) continue;
            jdbc.update("INSERT INTO AUTH_KB_GRANT (GRANT_ID, SUBJECT_TYPE, SUBJECT_ID, DOMAIN_KEY, "
                            + "ACCESS_LEVEL, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
                    IdentityIds.grant(), subjectType, subjectId, domain.trim(), ACCESS_READ, now);
        }
    }

    public void deleteBySubject(String subjectType, String subjectId) {
        jdbc.update("DELETE FROM AUTH_KB_GRANT WHERE SUBJECT_TYPE = ? AND SUBJECT_ID = ?",
                subjectType, subjectId);
    }

    public List<String> listDistinctDomains() {
        return jdbc.queryForList("SELECT DISTINCT DOMAIN_KEY FROM AUTH_KB_GRANT ORDER BY DOMAIN_KEY",
                String.class);
    }
}
