package com.cetc28.modelconfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding 模型配置（OpenAI 兼容 /embeddings 接口）。
 *
 * <p>dimension=0 表示首次调用时自动探测；一旦写入检出维度，变化会触发向量库重建流程。
 */
public record EmbeddingProfile(
        String id,
        String name,
        String baseUrl,
        String apiKey,
        String modelName,
        int maxTokens,
        int dimension,
        boolean enabled,
        boolean builtin) {

    public static EmbeddingProfile seed(String baseUrl, String apiKey, String modelName,
                                        int maxTokens, int dimension) {
        return new EmbeddingProfile("default", "默认 Embedding", baseUrl, apiKey, modelName,
                maxTokens, dimension, true, true).normalized();
    }

    public EmbeddingProfile normalized() {
        int tokens = maxTokens <= 0 ? 512 : Math.min(maxTokens, 32_768);
        int dim = dimension < 0 ? 0 : dimension;
        return new EmbeddingProfile(
                trim(id),
                trim(name) == null || trim(name).isEmpty() ? trim(modelName) : trim(name),
                trim(baseUrl),
                apiKey == null ? "" : apiKey.trim(),
                trim(modelName),
                tokens,
                dim,
                enabled,
                builtin);
    }

    public void validate() {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Embedding 配置缺少 id");
        if (!id.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Embedding id 只能包含字母、数字、下划线、点和横线");
        }
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("Embedding 模型名不能为空");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("base-url 不能为空");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("base-url 必须以 http:// 或 https:// 开头");
        }
        if (maxTokens <= 0) throw new IllegalArgumentException("max-tokens 必须大于 0");
        if (dimension < 0) throw new IllegalArgumentException("dimension 不能为负数（0 表示自动探测）");
    }

    public boolean usable() {
        return enabled
                && modelName != null && !modelName.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    public EmbeddingProfile withApiKey(String newKey) {
        if (newKey == null || newKey.isBlank() || newKey.contains("****")) return this;
        return new EmbeddingProfile(id, name, baseUrl, newKey.trim(), modelName,
                maxTokens, dimension, enabled, builtin);
    }

    public EmbeddingProfile withBuiltin(boolean value) {
        return new EmbeddingProfile(id, name, baseUrl, apiKey, modelName,
                maxTokens, dimension, enabled, value);
    }

    public EmbeddingProfile withDimension(int detected) {
        return new EmbeddingProfile(id, name, baseUrl, apiKey, modelName,
                maxTokens, Math.max(0, detected), enabled, builtin);
    }

    public Map<String, Object> toView() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("baseUrl", baseUrl);
        map.put("apiKey", "");
        map.put("apiKeyMasked", ChatProfile.mask(apiKey));
        map.put("modelName", modelName);
        map.put("maxTokens", maxTokens);
        map.put("dimension", dimension);
        map.put("enabled", enabled);
        map.put("builtin", builtin);
        map.put("usable", usable());
        return map;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
