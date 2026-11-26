package com.cetc28.modelconfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型配置快照：语言模型、Embedding 模型、默认值、按 Agent 的模型映射、Milvus 集合版本。
 *
 * <p>不可变对象，所有修改都返回新实例；持久化与版本号由 {@link ModelConfigStore} 统一管理。
 */
public record ModelConfig(
        int version,
        List<ChatProfile> chatProfiles,
        List<EmbeddingProfile> embeddingProfiles,
        String defaultChatId,
        String defaultEmbeddingId,
        Map<String, String> agentProfiles,
        String collectionVersion,
        long updatedAt) {

    public ModelConfig {
        chatProfiles = chatProfiles == null ? List.of() : List.copyOf(chatProfiles);
        embeddingProfiles = embeddingProfiles == null ? List.of() : List.copyOf(embeddingProfiles);
        agentProfiles = agentProfiles == null ? Map.of() : Map.copyOf(agentProfiles);
        collectionVersion = collectionVersion == null || collectionVersion.isBlank()
                ? "v1" : collectionVersion.trim();
    }

    public static ModelConfig empty() {
        return new ModelConfig(0, List.of(), List.of(), null, null, Map.of(), "v1", 0L);
    }

    public Optional<ChatProfile> findChat(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return chatProfiles.stream().filter(p -> id.equals(p.id())).findFirst();
    }

    public Optional<EmbeddingProfile> findEmbedding(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return embeddingProfiles.stream().filter(p -> id.equals(p.id())).findFirst();
    }

    /** 默认语言模型：优先 defaultChatId，其次第一个可用配置。 */
    public Optional<ChatProfile> defaultChat() {
        Optional<ChatProfile> byDefault = findChat(defaultChatId).filter(ChatProfile::usable);
        if (byDefault.isPresent()) return byDefault;
        return chatProfiles.stream().filter(ChatProfile::usable).findFirst();
    }

    /** 默认 Embedding：优先 defaultEmbeddingId，其次第一个可用配置。 */
    public Optional<EmbeddingProfile> defaultEmbedding() {
        Optional<EmbeddingProfile> byDefault = findEmbedding(defaultEmbeddingId).filter(EmbeddingProfile::usable);
        if (byDefault.isPresent()) return byDefault;
        return embeddingProfiles.stream().filter(EmbeddingProfile::usable).findFirst();
    }

    /** 指定 Agent 绑定的语言模型；未绑定返回空。 */
    public Optional<ChatProfile> chatForAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        String profileId = agentProfiles.get(agentId);
        if (profileId == null || profileId.isBlank()) return Optional.empty();
        return findChat(profileId).filter(ChatProfile::usable);
    }

    public boolean reserveChatId(String id) {
        return chatProfiles.stream().noneMatch(p -> p.id().equals(id));
    }

    public ModelConfig withChatProfiles(List<ChatProfile> profiles, String newDefaultChatId) {
        return new ModelConfig(version, profiles, embeddingProfiles, newDefaultChatId,
                defaultEmbeddingId, agentProfiles, collectionVersion, updatedAt);
    }

    public ModelConfig withEmbeddingProfiles(List<EmbeddingProfile> profiles, String newDefaultEmbeddingId) {
        return new ModelConfig(version, chatProfiles, profiles, defaultChatId,
                newDefaultEmbeddingId, agentProfiles, collectionVersion, updatedAt);
    }

    public ModelConfig withAgentProfiles(Map<String, String> mapping) {
        return new ModelConfig(version, chatProfiles, embeddingProfiles, defaultChatId,
                defaultEmbeddingId, mapping, collectionVersion, updatedAt);
    }

    public ModelConfig withCollectionVersion(String newCollectionVersion) {
        return new ModelConfig(version, chatProfiles, embeddingProfiles, defaultChatId,
                defaultEmbeddingId, agentProfiles, newCollectionVersion, updatedAt);
    }

    public ModelConfig withVersion(int newVersion, long newUpdatedAt) {
        return new ModelConfig(newVersion, chatProfiles, embeddingProfiles, defaultChatId,
                defaultEmbeddingId, agentProfiles, collectionVersion, newUpdatedAt);
    }

    /** 管理端完整视图：密钥脱敏，agent 映射只保留仍存在的模型。 */
    public Map<String, Object> toView() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("updatedAt", updatedAt);
        map.put("collectionVersion", collectionVersion);
        map.put("defaultChatId", defaultChatId);
        map.put("defaultEmbeddingId", defaultEmbeddingId);
        List<Map<String, Object>> chats = new ArrayList<>();
        for (ChatProfile profile : chatProfiles) chats.add(profile.toView());
        map.put("chatProfiles", chats);
        List<Map<String, Object>> embeddings = new ArrayList<>();
        for (EmbeddingProfile profile : embeddingProfiles) embeddings.add(profile.toView());
        map.put("embeddingProfiles", embeddings);
        map.put("agentProfiles", new LinkedHashMap<>(agentProfiles));
        return map;
    }

    /** 对话侧只读视图：不暴露 base-url / 密钥。 */
    public List<Map<String, Object>> toPublicModels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChatProfile profile : chatProfiles) {
            if (!profile.usable()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", profile.id());
            item.put("name", profile.name());
            item.put("modelName", profile.modelName());
            item.put("default", profile.id().equals(defaultChat().map(ChatProfile::id).orElse(null)));
            list.add(item);
        }
        return list;
    }
}
