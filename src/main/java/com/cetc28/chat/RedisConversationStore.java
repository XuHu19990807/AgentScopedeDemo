package com.cetc28.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis 实现：
 *   chat:conv:{cid}          HASH
 *   chat:msg:{cid}           LIST（index = seq - 1）
 *   chat:user:{uid}:convs    ZSET（score = updatedAt）
 */
@Component
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);

    private static final String CONV_PREFIX = "chat:conv:";
    private static final String MSG_PREFIX = "chat:msg:";
    private static final String INDEX_SUFFIX = ":convs";
    private static final String USER_PREFIX = "chat:user:";
    private static final int SCAN_COUNT = 500;

    private final UnifiedJedis jedis;
    private final ObjectMapper mapper;

    public RedisConversationStore(UnifiedJedis jedis, ObjectMapper mapper) {
        this.jedis = jedis;
        this.mapper = mapper;
    }

    private String convKey(String id) {
        return CONV_PREFIX + id;
    }

    private String msgKey(String id) {
        return MSG_PREFIX + id;
    }

    private String indexKey(String userId) {
        return USER_PREFIX + userId + INDEX_SUFFIX;
    }

    @Override
    public Conversation create(String userId) {
        long now = System.currentTimeMillis();
        String id = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("id", id);
        hash.put("userId", userId);
        hash.put("title", "");
        hash.put("mode", ChatMode.CHAT.value());
        hash.put("createdAt", String.valueOf(now));
        hash.put("updatedAt", String.valueOf(now));
        hash.put("messageCount", "0");
        jedis.hset(convKey(id), hash);
        jedis.zadd(indexKey(userId), now, id);
        return toConversation(hash);
    }

    @Override
    public Optional<Conversation> get(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        Map<String, String> hash = jedis.hgetAll(convKey(conversationId));
        if (hash == null || hash.isEmpty()) return Optional.empty();
        Conversation conv = toConversation(hash);
        if (userId != null && !userId.equals(conv.getUserId())) return Optional.empty();
        return Optional.of(conv);
    }

    @Override
    public List<Conversation> list(String userId, int limit) {
        List<String> ids = jedis.zrevrange(indexKey(userId), 0, Math.max(0, limit - 1));
        if (ids == null || ids.isEmpty()) return List.of();
        List<Conversation> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Map<String, String> hash = jedis.hgetAll(convKey(id));
            if (hash == null || hash.isEmpty()) {
                jedis.zrem(indexKey(userId), id);
                continue;
            }
            result.add(toConversation(hash));
        }
        return result;
    }

    @Override
    public void rename(String conversationId, String title) {
        if (conversationId == null || title == null) return;
        jedis.hset(convKey(conversationId), "title", title);
    }

    @Override
    public void updateMode(String conversationId, String mode) {
        if (conversationId == null || mode == null) return;
        jedis.hset(convKey(conversationId), "mode", mode);
    }

    @Override
    public void updateModel(String conversationId, String modelId) {
        if (conversationId == null || modelId == null) return;
        jedis.hset(convKey(conversationId), "model", modelId);
    }

    @Override
    public ChatMessage appendMessage(String conversationId, ChatMessage message) {
        long seq = jedis.llen(msgKey(conversationId)) + 1;
        long now = System.currentTimeMillis();
        message.setSeq(seq);
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId("m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
        if (message.getCreatedAt() == 0) message.setCreatedAt(now);
        try {
            jedis.rpush(msgKey(conversationId), mapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("updatedAt", String.valueOf(now));
        updates.put("messageCount", String.valueOf(seq));
        jedis.hset(convKey(conversationId), updates);
        String userId = jedis.hget(convKey(conversationId), "userId");
        if (userId != null) {
            jedis.zadd(indexKey(userId), now, conversationId);
        }
        return message;
    }

    @Override
    public List<ChatMessage> recentMessages(String conversationId, int limit) {
        long total = jedis.llen(msgKey(conversationId));
        if (total <= 0) return List.of();
        int size = (int) Math.min(total, Math.max(1, limit));
        long from = total - size;
        List<String> raws = jedis.lrange(msgKey(conversationId), from, -1);
        if (raws == null || raws.isEmpty()) return List.of();
        List<ChatMessage> messages = new ArrayList<>(raws.size());
        for (String raw : raws) {
            try {
                messages.add(mapper.readValue(raw, ChatMessage.class));
            } catch (Exception e) {
                log.warn("跳过损坏的消息记录: {}", e.getMessage());
            }
        }
        return messages;
    }

    @Override
    public long messageCount(String conversationId) {
        return jedis.llen(msgKey(conversationId));
    }

    @Override
    public void truncate(String conversationId, long keepSeq) {
        if (keepSeq <= 0) {
            jedis.del(msgKey(conversationId));
        } else {
            jedis.ltrim(msgKey(conversationId), 0, keepSeq - 1);
        }
        long now = System.currentTimeMillis();
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("updatedAt", String.valueOf(now));
        updates.put("messageCount", String.valueOf(Math.max(0, keepSeq)));
        jedis.hset(convKey(conversationId), updates);
    }

    @Override
    public void delete(String conversationId, String userId) {
        jedis.del(convKey(conversationId));
        jedis.del(msgKey(conversationId));
        if (userId != null) {
            jedis.zrem(indexKey(userId), conversationId);
        }
        // 清理该会话在 AgentScope 里的上下文（agentstate:{agentId}:{convId}:*）
        deleteAgentStates(conversationId);
    }

    @Override
    public void deleteAll(String userId) {
        List<String> ids = jedis.zrevrange(indexKey(userId), 0, -1);
        if (ids != null) {
            for (String id : ids) {
                jedis.del(convKey(id));
                jedis.del(msgKey(id));
                deleteAgentStates(id);
            }
        }
        jedis.del(indexKey(userId));
    }

    private void deleteAgentStates(String conversationId) {
        Set<String> keys = scanKeys("agentstate:*:" + conversationId + ":*");
        if (keys.isEmpty()) return;
        String[] array = keys.toArray(new String[0]);
        int batch = 500;
        for (int i = 0; i < array.length; i += batch) {
            int end = Math.min(array.length, i + batch);
            String[] slice = new String[end - i];
            System.arraycopy(array, i, slice, 0, slice.length);
            jedis.del(slice);
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        try {
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                if (result == null) break;
                cursor = result.getCursor();
                if (result.getResult() != null) keys.addAll(result.getResult());
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            log.warn("SCAN 失败 pattern='{}': {}", pattern, e.getMessage());
        }
        return keys;
    }

    private Conversation toConversation(Map<String, String> hash) {
        return Conversation.builder()
                .id(hash.get("id"))
                .userId(hash.get("userId"))
                .title(hash.getOrDefault("title", ""))
                .mode(hash.getOrDefault("mode", ChatMode.CHAT.value()))
                .model(hash.get("model"))
                .createdAt(parseLong(hash.get("createdAt")))
                .updatedAt(parseLong(hash.get("updatedAt")))
                .messageCount((int) parseLong(hash.get("messageCount")))
                .build();
    }

    private long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
