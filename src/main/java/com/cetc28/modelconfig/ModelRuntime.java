package com.cetc28.modelconfig;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时模型解析器：持有配置快照，按 Agent / 按会话解析出实际 Model 实例。
 *
 * <p>所有对外的 Model 都是 {@link TrafficModel} 代理，底层 OpenAIChatModel 按 profileId 缓存，
 * 配置版本变化时整体失效重建。
 */
@Component
public class ModelRuntime {

    private static final Logger log = LoggerFactory.getLogger(ModelRuntime.class);

    private final ModelConfigStore store;
    private final ModelTrafficService traffic;
    private final Map<String, Model> delegates = new ConcurrentHashMap<>();

    private volatile ModelConfig snapshot = ModelConfig.empty();
    private volatile int revision = -1;

    public ModelRuntime(ModelConfigStore store, ModelTrafficService traffic) {
        this.store = store;
        this.traffic = traffic;
    }

    @PostConstruct
    public void init() {
        refreshFromStore();
    }

    /** 配置更新后调用：重新读取快照并让缓存的模型实例失效。 */
    public synchronized void refreshFromStore() {
        ModelConfig next = store.current();
        snapshot = next;
        revision = next.version();
        delegates.clear();
        log.info("模型运行时已刷新：version={}, defaultChat={}", revision, next.defaultChatId());
    }

    public ModelConfig config() {
        return snapshot;
    }

    public int revision() {
        return revision;
    }

    public Optional<ChatProfile> chatProfile(String profileId) {
        return snapshot.findChat(profileId).filter(ChatProfile::usable);
    }

    public Optional<ChatProfile> defaultChat() {
        return snapshot.defaultChat();
    }

    public Optional<EmbeddingProfile> embeddingProfile(String profileId) {
        return snapshot.findEmbedding(profileId).filter(EmbeddingProfile::usable);
    }

    public Optional<EmbeddingProfile> defaultEmbedding() {
        return snapshot.defaultEmbedding();
    }

    public String collectionVersion() {
        String version = snapshot.collectionVersion();
        return version == null || version.isBlank() ? "v1" : version;
    }

    /** Agent 默认模型：未单独映射时使用全局默认配置。 */
    public Model modelFor(String agentId) {
        return new TrafficModel(() -> resolveForAgent(agentId), traffic, "chat", agentId, "unresolved");
    }

    /** 会话级固定模型；profileId 为空时等同于 Agent 默认模型。 */
    public Model pinnedModel(String profileId, String agentId) {
        if (profileId == null || profileId.isBlank()) return modelFor(agentId);
        String key = profileId.trim();
        return new TrafficModel(() -> resolvePinned(key), traffic, "chat", agentId, key);
    }

    public ModelTarget resolveForAgent(String agentId) {
        ChatProfile profile = snapshot.chatForAgent(agentId)
                .or(() -> snapshot.defaultChat())
                .orElseThrow(() -> new IllegalStateException("没有可用的语言模型配置，请先在管理控制台配置"));
        return new ModelTarget(profile.id(), profile.modelName(), delegate(profile));
    }

    public ModelTarget resolvePinned(String profileId) {
        Optional<ChatProfile> pinned = chatProfile(profileId);
        if (pinned.isPresent()) {
            ChatProfile profile = pinned.get();
            return new ModelTarget(profile.id(), profile.modelName(), delegate(profile));
        }
        return resolveForAgent(null);
    }

    private Model delegate(ChatProfile profile) {
        return delegates.computeIfAbsent(profile.id() + "#" + revision, key -> build(profile));
    }

    private Model build(ChatProfile profile) {
        GenerateOptions options = GenerateOptions.builder()
                .temperature(profile.temperature())
                .maxTokens(profile.maxTokens())
                .build();
        return OpenAIChatModel.builder()
                .modelName(profile.modelName())
                .apiKey(profile.apiKey())
                .baseUrl(profile.baseUrl())
                .generateOptions(options)
                .build();
    }
}
