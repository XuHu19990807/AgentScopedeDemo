package com.cetc28.agentchat.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 版 {@link AgentStateStore}，按 {@code (userId, sessionId)} 二元组存取。
 *
 * <p>框架契约（AgentStateStore Javadoc）：调用方传入的是 userId 与 sessionId，
 * 二者由实现自行拼装，调用方不得预先拼接。本实现的 key 布局：
 *
 * <pre>
 *   {keyPrefix}{userId}:{sessionId}:{key}       -> 单个 State JSON
 *   {keyPrefix}{userId}:{sessionId}:{key}:list  -> Redis LIST
 * </pre>
 *
 * <p>{@code keyPrefix} 由 {@link AgentStateStoreFactory} 注入，形如
 * {@code agentchat:v2:agentstate:{agentId}:}，因此同一会话下不同 Agent 的
 * AgentState 天然隔离，不会再出现 chat / deep / orchestrator 互相覆盖的问题。
 */
public class RedisAgentStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentStateStore.class);
    private static final String LIST_SUFFIX = ":list";
    private static final int SCAN_COUNT = 500;
    private static final String ANONYMOUS = "anonymous";

    private final UnifiedJedis jedis;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    public RedisAgentStateStore(UnifiedJedis jedis, ObjectMapper mapper, String keyPrefix) {
        this.jedis = jedis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
    }

    private static String namespace(String userId) {
        return userId == null || userId.isBlank() ? ANONYMOUS : userId;
    }

    private String stateKey(String userId, String sessionId, String key) {
        return keyPrefix + namespace(userId) + ":" + sessionId + ":" + key;
    }

    private String listKey(String userId, String sessionId, String key) {
        return stateKey(userId, sessionId, key) + LIST_SUFFIX;
    }

    private String sessionPattern(String userId, String sessionId) {
        return keyPrefix + namespace(userId) + ":" + sessionId + ":*";
    }

    private String userPattern(String userId) {
        return keyPrefix + namespace(userId) + ":*";
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        try {
            jedis.set(stateKey(userId, sessionId, key), mapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize state", e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        String listK = listKey(userId, sessionId, key);
        jedis.del(listK);
        if (states == null || states.isEmpty()) return;
        try {
            String[] payload = new String[states.size()];
            for (int i = 0; i < states.size(); i++) {
                payload[i] = mapper.writeValueAsString(states.get(i));
            }
            jedis.rpush(listK, payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize state list", e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String json = jedis.get(stateKey(userId, sessionId, key));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, type));
        } catch (IOException e) {
            log.warn("Failed to deserialize state for {}/{}/{}: {}", namespace(userId), sessionId, key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        List<String> jsons = jedis.lrange(listKey(userId, sessionId, key), 0, -1);
        if (jsons == null || jsons.isEmpty()) return List.of();
        List<T> result = new ArrayList<>(jsons.size());
        for (String json : jsons) {
            try {
                result.add(mapper.readValue(json, type));
            } catch (IOException e) {
                log.warn("Failed to deserialize list item: {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return !scanKeys(sessionPattern(userId, sessionId)).isEmpty();
    }

    @Override
    public void delete(String userId, String sessionId) {
        deleteKeys(scanKeys(sessionPattern(userId, sessionId)));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        jedis.del(stateKey(userId, sessionId, key));
        jedis.del(listKey(userId, sessionId, key));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Set<String> keys = scanKeys(userPattern(userId));
        String prefix = keyPrefix + namespace(userId) + ":";
        Set<String> sessionIds = new LinkedHashSet<>();
        for (String key : keys) {
            String rest = key.substring(prefix.length());
            int idx = rest.indexOf(':');
            if (idx > 0) sessionIds.add(rest.substring(0, idx));
        }
        return sessionIds;
    }

    /** 用 SCAN 替代 KEYS，避免阻塞 Redis。 */
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
            log.warn("SCAN failed for pattern '{}': {}", pattern, e.getMessage());
        }
        return keys;
    }

    private void deleteKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        String[] array = keys.toArray(new String[0]);
        int batch = 500;
        for (int i = 0; i < array.length; i += batch) {
            int end = Math.min(array.length, i + batch);
            String[] slice = new String[end - i];
            System.arraycopy(array, i, slice, 0, slice.length);
            jedis.del(slice);
        }
    }
}
