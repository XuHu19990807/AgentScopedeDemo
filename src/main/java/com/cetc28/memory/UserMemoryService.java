package com.cetc28.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆（产品级事实来源），按 userId 维度存储在 Redis：
 *   mem:{userId}:pref  HASH  key -> value
 *
 * 注意：与 AgentScope 的 MEMORY.md / MemoryConfig 不是同一层——
 * 这里记住的是"用户是谁、偏好什么"，AgentScope 记忆管理的是"本次会话的上下文"。
 */
@Service
public class UserMemoryService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);
    private static final String PREFIX = "mem:";
    private static final int MAX_KEY_LENGTH = 80;
    private static final int MAX_VALUE_LENGTH = 500;

    private final UnifiedJedis jedis;

    public UserMemoryService(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    private String hashKey(String userId) {
        return PREFIX + userId + ":pref";
    }

    public void remember(String userId, String key, String value) {
        if (!valid(userId) || key == null || key.isBlank() || value == null || value.isBlank()) return;
        String safeKey = trim(key, MAX_KEY_LENGTH);
        String safeValue = trim(value, MAX_VALUE_LENGTH);
        try {
            jedis.hset(hashKey(userId), safeKey, safeValue);
            log.info("记忆已写入 user={}, key={}", userId, safeKey);
        } catch (Exception e) {
            log.warn("写入记忆失败 user={}: {}", userId, e.getMessage());
        }
    }

    public Map<String, String> list(String userId) {
        if (!valid(userId)) return Map.of();
        try {
            Map<String, String> raw = jedis.hgetAll(hashKey(userId));
            return raw == null ? Map.of() : new LinkedHashMap<>(raw);
        } catch (Exception e) {
            log.warn("读取记忆失败 user={}: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    public int count(String userId) {
        return list(userId).size();
    }

    public void delete(String userId, String key) {
        if (!valid(userId) || key == null || key.isBlank()) return;
        try {
            jedis.hdel(hashKey(userId), key);
        } catch (Exception e) {
            log.warn("删除记忆失败 user={}: {}", userId, e.getMessage());
        }
    }

    public void clear(String userId) {
        if (!valid(userId)) return;
        try {
            jedis.del(hashKey(userId));
        } catch (Exception e) {
            log.warn("清空记忆失败 user={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 生成注入 system prompt 的记忆片段。记忆数量很小，直接全量返回并按查询词做简单匹配排序。
     */
    public String recallContext(String userId, String query, int limit) {
        Map<String, String> memories = list(userId);
        if (memories.isEmpty()) return "";
        List<Map.Entry<String, String>> entries = new ArrayList<>(memories.entrySet());
        String normalized = query == null ? "" : query.toLowerCase();
        entries.sort((a, b) -> Integer.compare(score(b, normalized), score(a, normalized)));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, String> entry : entries) {
            if (count++ >= limit) break;
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 极简规则：用户明确说"记住/以后/我偏好"时，把这句话存为一条记忆。
     * 不做 LLM 提炼，避免额外调用与不可控写入。
     */
    public void rememberIfRequested(String userId, String message) {
        if (!valid(userId) || message == null || message.isBlank()) return;
        String text = message.trim();
        // 问句不是稳定事实，存进去会污染后续对话（例如“我的钥匙是什么颜色？”）。
        if (text.endsWith("?") || text.endsWith("？")) {
            return;
        }
        if (!text.matches(".*(记住|请记住|以后都|以后请|我喜欢|我偏好|我习惯|我的[^，。！？\\n]{1,12}是).*")) {
            return;
        }
        String value = trim(text.replaceAll("\\s+", " "), MAX_VALUE_LENGTH);
        String key = "mem_" + Integer.toHexString(value.hashCode());
        remember(userId, key, value);
    }

    private int score(Map.Entry<String, String> entry, String query) {
        if (query.isBlank()) return 0;
        int score = 0;
        String key = entry.getKey().toLowerCase();
        String value = entry.getValue().toLowerCase();
        for (String term : query.split("[\\s,，。.!?！？;；:：]+")) {
            if (term.length() < 2) continue;
            if (key.contains(term)) score += 2;
            if (value.contains(term)) score += 1;
        }
        return score;
    }

    private boolean valid(String userId) {
        return userId != null && userId.matches("[A-Za-z0-9_-]{1,64}");
    }

    private String trim(String text, int max) {
        String value = text.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }
}
