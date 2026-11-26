package com.cetc28.modelconfig;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 模型配置的持久化中心。
 *
 * <p>优先级：Redis（{@code model:config:v1}）→ 本地文件（{@code data/model-config.json}）→ yml 播种。
 * 每次更新都会递增 {@code version}，{@link ModelRuntime} 以此判断是否需要重建模型实例。
 */
@Component
public class ModelConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigStore.class);
    private static final String REDIS_KEY = "model:config:v1";

    private final ObjectMapper mapper;
    private final Path file;
    private final StringRedisTemplate redis;

    @Value("${agentscope.model.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String seedChatBaseUrl;
    @Value("${agentscope.model.openai.api-key:}")
    private String seedChatApiKey;
    @Value("${agentscope.model.openai.chat-model:deepseek-chat}")
    private String seedChatModel;
    @Value("${agentscope.model.openai.temperature:0.7}")
    private double seedChatTemperature;
    @Value("${agentscope.model.openai.max-tokens:4096}")
    private int seedChatMaxTokens;

    @Value("${agentscope.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String seedEmbeddingBaseUrl;
    @Value("${agentscope.embedding.api-key:}")
    private String seedEmbeddingApiKey;
    @Value("${agentscope.embedding.model:qwen3.7-text-embedding}")
    private String seedEmbeddingModel;
    @Value("${agentscope.embedding.max-tokens:512}")
    private int seedEmbeddingMaxTokens;
    @Value("${rag.embed.dimension:0}")
    private int seedEmbeddingDimension;
    @Value("${rag.milvus.collection-version:v1}")
    private String seedCollectionVersion;

    private volatile ModelConfig current = ModelConfig.empty();

    public ModelConfigStore(ObjectMapper mapper,
                            @Value("${agentchat.model.config-file:data/model-config.json}") String configFile,
                            @Autowired(required = false) StringRedisTemplate redis) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.file = Paths.get(configFile).toAbsolutePath().normalize();
        this.redis = redis;
    }

    @PostConstruct
    public void load() {
        ModelConfig loaded = readRedis();
        if (loaded == null) loaded = readFile();
        if (loaded == null || loaded.chatProfiles().isEmpty()) {
            loaded = seed();
            log.info("模型配置首次初始化：播种 {} 个语言模型、{} 个 Embedding 配置",
                    loaded.chatProfiles().size(), loaded.embeddingProfiles().size());
            persist(loaded);
        }
        current = loaded;
        log.info("模型配置已加载：version={}, chatProfiles={}, defaultChat={}",
                loaded.version(), loaded.chatProfiles().size(), loaded.defaultChatId());
    }

    public ModelConfig current() {
        return current;
    }

    public Path file() {
        return file;
    }

    /** 原子更新：递增版本并持久化，返回新快照。 */
    public synchronized ModelConfig update(UnaryOperator<ModelConfig> mutator) {
        ModelConfig next = mutator.apply(current);
        if (next == null) throw new IllegalArgumentException("模型配置更新失败：mutator 返回 null");
        next = next.withVersion(current.version() + 1, System.currentTimeMillis());
        persist(next);
        current = next;
        return next;
    }

    /** 用 yml 中的初始值覆盖当前配置（应急恢复）。 */
    public synchronized ModelConfig resetToSeed() {
        ModelConfig next = seed().withVersion(current.version() + 1, System.currentTimeMillis());
        persist(next);
        current = next;
        return next;
    }

    private ModelConfig seed() {
        ChatProfile chat = ChatProfile.seed(seedChatBaseUrl, seedChatApiKey, seedChatModel,
                seedChatTemperature, seedChatMaxTokens);
        EmbeddingProfile embedding = EmbeddingProfile.seed(seedEmbeddingBaseUrl, seedEmbeddingApiKey,
                seedEmbeddingModel, seedEmbeddingMaxTokens, seedEmbeddingDimension);
        return new ModelConfig(0,
                List.of(chat),
                List.of(embedding),
                chat.id(),
                embedding.id(),
                Map.of(),
                seedCollectionVersion == null || seedCollectionVersion.isBlank() ? "v1" : seedCollectionVersion,
                System.currentTimeMillis());
    }

    private ModelConfig readRedis() {
        if (redis == null) return null;
        try {
            String json = redis.opsForValue().get(REDIS_KEY);
            if (json == null || json.isBlank()) return null;
            return mapper.readValue(json, ModelConfig.class);
        } catch (Exception e) {
            log.warn("读取 Redis 模型配置失败，回退本地文件：{}", e.getMessage());
            return null;
        }
    }

    private ModelConfig readFile() {
        try {
            if (!Files.exists(file)) return null;
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return null;
            return mapper.readValue(bytes, ModelConfig.class);
        } catch (Exception e) {
            log.warn("读取本地模型配置失败：{}", e.getMessage());
            return null;
        }
    }

    private void persist(ModelConfig config) {
        String json;
        try {
            json = mapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("序列化模型配置失败：" + e.getMessage(), e);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("写入本地模型配置失败（{}）：{}", file, e.getMessage());
        }
        if (redis != null) {
            try {
                redis.opsForValue().set(REDIS_KEY, json);
            } catch (Exception e) {
                log.warn("写入 Redis 模型配置失败，已保留本地文件兜底：{}", e.getMessage());
            }
        }
    }
}
