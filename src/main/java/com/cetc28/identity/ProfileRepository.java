package com.cetc28.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户画像（{@code USR_PROFILE}）访问层。画像为键值对：
 *   counter:turns / pref:last-mode / pref:last-model / topic:&lt;词&gt; / pref:&lt;记忆键&gt;
 */
@Repository
public class ProfileRepository {

    private static final RowMapper<ProfileEntry> MAPPER = (rs, rowNum) -> new ProfileEntry(
            rs.getString("PROFILE_ID"),
            rs.getString("USER_ID"),
            rs.getString("PROFILE_KEY"),
            rs.getString("PROFILE_VALUE"),
            rs.getString("SOURCE_TYPE"),
            rs.getLong("UPDATED_AT"));

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProfileEntry> listByUser(String userId) {
        return jdbc.query("SELECT * FROM USR_PROFILE WHERE USER_ID = ? ORDER BY UPDATED_AT DESC",
                MAPPER, userId);
    }

    public Optional<ProfileEntry> findByKey(String userId, String key) {
        List<ProfileEntry> list = jdbc.query(
                "SELECT * FROM USR_PROFILE WHERE USER_ID = ? AND PROFILE_KEY = ?",
                MAPPER, userId, key);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void upsert(String userId, String key, String value, String sourceType) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE USR_PROFILE SET PROFILE_VALUE = ?, SOURCE_TYPE = ?, UPDATED_AT = ? "
                        + "WHERE USER_ID = ? AND PROFILE_KEY = ?",
                trim(value, 2000), sourceType, now, userId, trim(key, 256));
        if (updated > 0) return;
        jdbc.update("INSERT INTO USR_PROFILE (PROFILE_ID, USER_ID, PROFILE_KEY, PROFILE_VALUE, SOURCE_TYPE, UPDATED_AT) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                IdentityIds.profile(), userId, trim(key, 256), trim(value, 2000), sourceType, now);
    }

    public void deleteByKey(String userId, String key) {
        jdbc.update("DELETE FROM USR_PROFILE WHERE USER_ID = ? AND PROFILE_KEY = ?", userId, key);
    }

    public void deleteByUser(String userId) {
        jdbc.update("DELETE FROM USR_PROFILE WHERE USER_ID = ?", userId);
    }

    public void deleteBySource(String userId, String sourceType) {
        jdbc.update("DELETE FROM USR_PROFILE WHERE USER_ID = ? AND SOURCE_TYPE = ?", userId, sourceType);
    }

    public void deleteByPrefix(String userId, String prefix) {
        jdbc.update("DELETE FROM USR_PROFILE WHERE USER_ID = ? AND PROFILE_KEY LIKE ?",
                userId, prefix + "%");
    }

    public int countByUser(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM USR_PROFILE WHERE USER_ID = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 只保留最近 {@code keep} 行自动画像，防止主题词无限增长（手动记忆不裁剪）。 */
    public void prune(String userId, int keep) {
        jdbc.update("DELETE FROM USR_PROFILE WHERE USER_ID = ? AND SOURCE_TYPE = 'auto' "
                        + "AND PROFILE_ID NOT IN ("
                        + "SELECT PROFILE_ID FROM (SELECT PROFILE_ID FROM USR_PROFILE "
                        + "WHERE USER_ID = ? AND SOURCE_TYPE = 'auto' ORDER BY UPDATED_AT DESC) WHERE ROWNUM <= ?)",
                userId, userId, Math.max(1, keep));
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
