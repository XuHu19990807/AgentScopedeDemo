package com.cetc28.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分段"停用"状态（Redis 墓碑）。
 *
 * <p>停用一个分段 = 从 Milvus 双集合删掉该行 + 在 Redis 记一条墓碑（保留正文/行号/序号），
 * 这样启用时无需重新解析原文件即可恢复。墓碑存放在 {@code knowledge:chunks:disabled}，
 * field=chunkId，value=JSON。Redis 不可用时停用/启用直接报错（避免出现"以为停用了其实还在检索"）。
 */
@Component
public class KnowledgeChunkState {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkState.class);

    public static final String DISABLED_KEY = "knowledge:chunks:disabled";

    public record DisabledChunk(String chunkId, String docId, String filename, int chunkIndex,
                                String content, String parentText, String headingPath,
                                int lineStart, int lineEnd, long disabledAt) {
        public DisabledChunk withIndex(String newChunkId, int newIndex) {
            return new DisabledChunk(newChunkId, docId, filename, newIndex, content, parentText,
                    headingPath, lineStart, lineEnd, disabledAt);
        }
    }

    private final ObjectMapper mapper;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public KnowledgeChunkState(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public boolean available() {
        if (redisTemplate == null) return false;
        try {
            redisTemplate.hasKey(DISABLED_KEY);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void disable(DisabledChunk chunk) {
        requireRedis();
        try {
            redisTemplate.opsForHash().put(DISABLED_KEY, chunk.chunkId(), mapper.writeValueAsString(chunk));
        } catch (Exception e) {
            throw new IllegalStateException("记录分段停用状态失败：" + e.getMessage(), e);
        }
    }

    public void disableAll(List<DisabledChunk> chunks) {
        requireRedis();
        if (chunks == null || chunks.isEmpty()) return;
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (DisabledChunk chunk : chunks) {
                values.put(chunk.chunkId(), mapper.writeValueAsString(chunk));
            }
            redisTemplate.opsForHash().putAll(DISABLED_KEY, values);
        } catch (Exception e) {
            throw new IllegalStateException("记录分段停用状态失败：" + e.getMessage(), e);
        }
    }

    /** 删除墓碑并返回它（启用操作需要墓碑里的正文）。 */
    public Optional<DisabledChunk> enable(String chunkId) {
        requireRedis();
        try {
            Object value = redisTemplate.opsForHash().get(DISABLED_KEY, chunkId);
            if (value == null) return Optional.empty();
            redisTemplate.opsForHash().delete(DISABLED_KEY, chunkId);
            return Optional.of(mapper.readValue(String.valueOf(value), DisabledChunk.class));
        } catch (Exception e) {
            throw new IllegalStateException("读取分段停用状态失败：" + e.getMessage(), e);
        }
    }

    public Optional<DisabledChunk> get(String chunkId) {
        if (redisTemplate == null) return Optional.empty();
        try {
            Object value = redisTemplate.opsForHash().get(DISABLED_KEY, chunkId);
            if (value == null) return Optional.empty();
            return Optional.of(mapper.readValue(String.valueOf(value), DisabledChunk.class));
        } catch (Exception e) {
            log.debug("读取分段墓碑失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<DisabledChunk> listByDoc(String docId) {
        if (redisTemplate == null || docId == null) return List.of();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(DISABLED_KEY);
            List<DisabledChunk> result = new ArrayList<>();
            for (Object value : entries.values()) {
                if (value == null) continue;
                try {
                    DisabledChunk chunk = mapper.readValue(String.valueOf(value), DisabledChunk.class);
                    if (docId.equals(chunk.docId())) result.add(chunk);
                } catch (Exception e) {
                    log.debug("解析分段墓碑失败：{}", e.getMessage());
                }
            }
            result.sort((a, b) -> Integer.compare(a.chunkIndex(), b.chunkIndex()));
            return result;
        } catch (Exception e) {
            log.warn("读取分段墓碑失败（Redis 不可用）：{}", e.getMessage());
            return List.of();
        }
    }

    public int countAll() {
        if (redisTemplate == null) return 0;
        try {
            Long size = redisTemplate.opsForHash().size(DISABLED_KEY);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    public int countByDoc(String docId) {
        return listByDoc(docId).size();
    }

    public void removeByDoc(String docId) {
        if (redisTemplate == null) return;
        List<DisabledChunk> chunks = listByDoc(docId);
        if (chunks.isEmpty()) return;
        try {
            Object[] keys = chunks.stream().map(DisabledChunk::chunkId).toArray();
            redisTemplate.opsForHash().delete(DISABLED_KEY, keys);
        } catch (Exception e) {
            log.warn("清理分段墓碑失败：{}", e.getMessage());
        }
    }

    public void replaceAll(String docId, List<DisabledChunk> chunks) {
        requireRedis();
        removeByDoc(docId);
        disableAll(chunks);
    }

    private void requireRedis() {
        if (redisTemplate == null) {
            throw new IllegalStateException("Redis 不可用，无法停用/启用分段");
        }
    }
}
