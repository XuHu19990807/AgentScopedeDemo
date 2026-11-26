package com.cetc28.modelconfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一套通用语言模型配置（OpenAI 兼容接口）。
 *
 * <p>密钥只在这里明文保存，对外展示统一走 {@link #toView()} 脱敏。
 */
public record ChatProfile(
        String id,
        String name,
        String baseUrl,
        String apiKey,
        String modelName,
        double temperature,
        int maxTokens,
        boolean enabled,
        boolean builtin) {

    public static ChatProfile seed(String baseUrl, String apiKey, String modelName,
                                   double temperature, int maxTokens) {
        return new ChatProfile("default", "默认模型", baseUrl, apiKey, modelName,
                temperature, maxTokens, true, true).normalized();
    }

    /** 补齐默认值并去掉首尾空白；不会抛异常，非法值留给 {@link #validate()} 判断。 */
    public ChatProfile normalized() {
        double temp = temperature;
        if (Double.isNaN(temp) || temp < 0) temp = 0.7;
        if (temp > 2) temp = 2;
        int tokens = maxTokens <= 0 ? 4096 : Math.min(maxTokens, 128_000);
        return new ChatProfile(
                trim(id),
                trim(name) == null || trim(name).isEmpty() ? trim(modelName) : trim(name),
                trim(baseUrl),
                apiKey == null ? "" : apiKey.trim(),
                trim(modelName),
                temp,
                tokens,
                enabled,
                builtin);
    }

    /** 校验必填项；失败抛出可直接返回给前端的 IllegalArgumentException。 */
    public void validate() {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("模型配置缺少 id");
        if (!id.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("模型 id 只能包含字母、数字、下划线、点和横线");
        }
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("模型名不能为空");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("base-url 不能为空");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("base-url 必须以 http:// 或 https:// 开头");
        }
        if (maxTokens <= 0) throw new IllegalArgumentException("max-tokens 必须大于 0");
        if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("temperature 必须在 0~2 之间");
    }

    public boolean usable() {
        return enabled
                && modelName != null && !modelName.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    public ChatProfile withApiKey(String newKey) {
        // 前端回传的占位密钥（空/****）表示"不修改"
        if (newKey == null || newKey.isBlank() || newKey.contains("****")) return this;
        return new ChatProfile(id, name, baseUrl, newKey.trim(), modelName,
                temperature, maxTokens, enabled, builtin);
    }

    public ChatProfile withBuiltin(boolean value) {
        return new ChatProfile(id, name, baseUrl, apiKey, modelName, temperature, maxTokens, enabled, value);
    }

    /** 对外视图：密钥脱敏，只保留后 4 位。 */
    public Map<String, Object> toView() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("baseUrl", baseUrl);
        map.put("apiKey", "");
        map.put("apiKeyMasked", mask(apiKey));
        map.put("modelName", modelName);
        map.put("temperature", temperature);
        map.put("maxTokens", maxTokens);
        map.put("enabled", enabled);
        map.put("builtin", builtin);
        map.put("usable", usable());
        return map;
    }

    public static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
