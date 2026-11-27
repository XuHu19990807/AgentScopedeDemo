package com.cetc28.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录会话存储（Redis）。
 *
 * <p>key：{@code {app.state.prefix}auth:sess:{sha256(token)}}，HASH 存 userId/createdAt，
 * TTL = app.auth.session-ttl-seconds，每次访问滑动续期。Redis 中不落明文令牌。
 */
@Component
public class AuthSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionStore.class);
    private static final String PREFIX = "auth:sess:";

    private final UnifiedJedis jedis;
    private final IdentityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthSessionStore(UnifiedJedis jedis, IdentityProperties properties) {
        this.jedis = jedis;
        this.properties = properties;
    }

    public record Session(String token, String userId, long createdAt, long expiresAt) {
    }

    public Session create(String userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        long ttl = Math.max(60, properties.getAuth().getSessionTtlSeconds());
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("userId", userId);
        hash.put("createdAt", String.valueOf(now));
        String key = key(token);
        jedis.hset(key, hash);
        jedis.expire(key, ttl);
        return new Session(token, userId, now, now + ttl * 1000);
    }

    /** 返回 userId；无效/过期/已被强制下线返回 null。命中时滑动续期。 */
    public String resolve(String token) {
        if (token == null || token.isBlank()) return null;
        String key = key(token);
        String userId;
        try {
            userId = jedis.hget(key, "userId");
            if (userId != null && !userId.isBlank()) {
                String createdAt = jedis.hget(key, "createdAt");
                long created = createdAt == null ? 0 : parseLong(createdAt, 0);
                if (created > 0 && created <= revokedAt(userId)) {
                    // 管理员改密/停用/删除后，旧会话立即失效
                    jedis.del(key);
                    return null;
                }
                jedis.expire(key, Math.max(60, properties.getAuth().getSessionTtlSeconds()));
                return userId;
            }
        } catch (RuntimeException e) {
            log.warn("读取登录会话失败（Redis 不可用？）: {}", e.getMessage());
            return null;
        }
        return null;
    }

    /** 强制该用户的全部现有会话失效（改密、停用、删除用户时调用）。 */
    public void revoke(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            String key = properties.getState().getPrefix() + "auth:revoked:" + userId;
            jedis.set(key, String.valueOf(System.currentTimeMillis()));
            jedis.expire(key, Math.max(60, properties.getAuth().getSessionTtlSeconds()));
        } catch (RuntimeException e) {
            log.warn("强制会话失效失败: {}", e.getMessage());
        }
    }

    private long revokedAt(String userId) {
        try {
            String value = jedis.get(properties.getState().getPrefix() + "auth:revoked:" + userId);
            return value == null ? 0 : parseLong(value, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public void delete(String token) {
        if (token == null || token.isBlank()) return;
        try {
            jedis.del(key(token));
        } catch (RuntimeException e) {
            log.warn("删除登录会话失败: {}", e.getMessage());
        }
    }

    /** 登录失败计数：窗口内达到上限后拒绝。返回当前失败次数。 */
    public long recordFailure(String username) {
        if (username == null || username.isBlank()) return 0;
        String key = properties.getState().getPrefix() + "auth:fail:" + username.trim().toLowerCase();
        try {
            long count = jedis.incr(key);
            if (count == 1) {
                jedis.expire(key, Math.max(60, properties.getAuth().getLoginFailWindowSeconds()));
            }
            return count;
        } catch (RuntimeException e) {
            log.warn("记录登录失败次数失败: {}", e.getMessage());
            return 0;
        }
    }

    public void clearFailures(String username) {
        if (username == null || username.isBlank()) return;
        try {
            jedis.del(properties.getState().getPrefix() + "auth:fail:" + username.trim().toLowerCase());
        } catch (RuntimeException e) {
            log.warn("清理登录失败计数失败: {}", e.getMessage());
        }
    }

    public long failures(String username) {
        if (username == null || username.isBlank()) return 0;
        try {
            String value = jedis.get(properties.getState().getPrefix() + "auth:fail:"
                    + username.trim().toLowerCase());
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String key(String token) {
        return properties.getState().getPrefix() + PREFIX + sha256(token);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
