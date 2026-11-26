package com.cetc28.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.List;

/**
 * Redis Stack 配置 — RediSearch 索引 + AgentStateStore（Redis 持久化）
 *
 * 统一使用 Jedis（UnifiedJedis），连接参数来自 spring.data.redis.*，
 * 避免 Jedis 与 Lettuce 两套客户端配置不一致。
 */
@Configuration
public class RedisStackConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStackConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public UnifiedJedis unifiedJedis(RedisProperties properties) {
        // 优先使用 Spring 绑定的属性（含 database/timeout），同时兼容 @Value 兜底
        String resolvedHost = properties.getHost() != null ? properties.getHost() : host;
        int resolvedPort = properties.getPort() > 0 ? properties.getPort() : port;
        String resolvedPassword = properties.getPassword() != null && !properties.getPassword().isBlank()
                ? properties.getPassword() : (password == null || password.isBlank() ? null : password);
        int database = Math.max(0, properties.getDatabase());
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(5);

        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(resolvedPassword)
                .database(database)
                .connectionTimeoutMillis((int) Math.max(500, timeout.toMillis()))
                .socketTimeoutMillis((int) Math.max(1000, timeout.toMillis()))
                .build();

        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        log.info("Creating Jedis pool: host={}, port={}, database={}, timeout={}ms",
                resolvedHost, resolvedPort, database, timeout.toMillis());
        return new JedisPooled(poolConfig, new HostAndPort(resolvedHost, resolvedPort), clientConfig);
    }

    @Bean
    public ObjectMapper redisStackObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // AgentScope 的 ChatUsage 序列化时会写出计算属性 totalTokens，但反序列化只认
        // inputTokens/outputTokens/cachedTokens/time。若严格模式报 unknown property，
        // RedisAgentStateStore.get 会吞掉异常并返回空状态，导致每轮对话都从零开始。
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public boolean initRedisSearchIndexes(UnifiedJedis unifiedJedis) {
        try {
            // idx_knowledge 由 com.cetc28.rag.KnowledgeIndex 负责创建（含 VECTOR/HNSW 字段与版本管理），
            // 这里不再建旧 schema —— 两处 schema 会互相覆盖，导致向量字段丢失。
            createIndexIfNotExists(unifiedJedis, "idx_memory", "doc:memory:",
                    List.of(
                            redis.clients.jedis.search.schemafields.TextField.of("content").weight(1.0),
                            redis.clients.jedis.search.schemafields.TextField.of("userId").weight(0.5),
                            redis.clients.jedis.search.schemafields.TextField.of("key").weight(0.3),
                            redis.clients.jedis.search.schemafields.TextField.of("type").weight(0.3)
                    ));

            log.info("Redis Stack (RediSearch) indexes initialized successfully");
        } catch (Exception e) {
            log.warn("RediSearch index init skipped: {}", e.getMessage());
        }
        return true;
    }

    private void createIndexIfNotExists(UnifiedJedis jedis, String indexName,
                                        String prefix,
                                        List<redis.clients.jedis.search.schemafields.SchemaField> fields) {
        try {
            jedis.ftInfo(indexName);
        } catch (Exception e) {
            try {
                redis.clients.jedis.search.FTCreateParams params =
                        redis.clients.jedis.search.FTCreateParams.createParams()
                                .on(redis.clients.jedis.search.IndexDataType.HASH)
                                .addPrefix(prefix)
                                .language("chinese");
                jedis.ftCreate(indexName, params, fields);
                log.info("Created RediSearch index: {} (prefix: {}, language: chinese)", indexName, prefix);
            } catch (Exception ex) {
                log.warn("Could not create RediSearch index '{}': {}", indexName, ex.getMessage());
            }
        }
    }

}
