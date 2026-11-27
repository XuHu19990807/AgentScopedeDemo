package com.cetc28.service;

import com.cetc28.modelconfig.EmbeddingProfile;
import com.cetc28.modelconfig.ModelRuntime;
import com.cetc28.modelconfig.ModelTrafficService;
import com.cetc28.modelconfig.TrafficCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Embedding 服务 — 调用 OpenAI 兼容的 embedding API（如 DashScope 通义千问）。
 *
 * <p>配置来源优先取管理控制台里的「默认 Embedding 配置」（{@link ModelRuntime#defaultEmbedding()}），
 * 未初始化/未配置时回退到 yml 的 {@code agentscope.embedding.*}，保证老部署零改动可用。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${agentscope.embedding.api-key:}")
    private String apiKey;

    @Value("${agentscope.embedding.base-url:}")
    private String baseUrl;

    @Value("${agentscope.embedding.model:}")
    private String model;

    @Value("${agentscope.embedding.dimension:0}")
    private int configuredDimension;

    /** 模型最大上下文长度（tokens），超长输入会被截断 */
    @Value("${agentscope.embedding.max-tokens:512}")
    private int maxTokens;

    @Autowired(required = false)
    private ModelRuntime modelRuntime;

    @Autowired(required = false)
    private ModelTrafficService traffic;

    private volatile int detectedDimension = 0;
    /** 已探测维度的 profile id；切换 embedding 配置时重置探测值 */
    private volatile String detectedProfileId;

    /** 连续失败计数：用于"首次失败打 ERROR、后续降噪"以及让上传流程判断语义检索是否可用 */
    private final java.util.concurrent.atomic.AtomicLong failureCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastError;

    /** 一次调用实际使用的 embedding 目标。 */
    private record Effective(String profileId, String baseUrl, String apiKey, String model,
                             int maxTokens, int dimension) {
    }

    private Effective effective() {
        if (modelRuntime != null) {
            try {
                Optional<EmbeddingProfile> profile = modelRuntime.defaultEmbedding();
                if (profile.isPresent()) {
                    EmbeddingProfile p = profile.get();
                    int limit = p.maxTokens() > 0 ? p.maxTokens() : maxTokens;
                    return new Effective(p.id(), p.baseUrl(), p.apiKey(), p.modelName(), limit, p.dimension());
                }
            } catch (Exception e) {
                log.debug("读取动态 Embedding 配置失败，回退 yml：{}", e.getMessage());
            }
        }
        return new Effective("default", baseUrl, apiKey, model, maxTokens, configuredDimension);
    }

    /** 当前生效的 embedding token 上限（供切块预算自检）。 */
    public int maxTokens() {
        return effective().maxTokens();
    }

    /**
     * 是否可用：只看 base-url 与 model。
     *
     * <p>api-key 允许为空 —— 内网自建的 embedding 服务（Ollama / vLLM / TEI / Xinference 等）
     * 通常不做鉴权，强制要求密钥会把这些部署挡在门外。
     */
    private static boolean enabled(Effective eff) {
        return eff.baseUrl() != null && !eff.baseUrl().isBlank()
                && eff.model() != null && !eff.model().isBlank();
    }

    private RestClient restClient(Effective eff) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        // 仅在配置了密钥时携带认证头：给不鉴权的本地服务发 "Bearer " 有些实现会直接 401
        if (eff.apiKey() != null && !eff.apiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + eff.apiKey());
        }
        return builder.build();
    }

    /**
     * 将单段文本转为 embedding 向量（超长文本自动截断到模型上限）
     * @return float[] 向量，未配置时返回 null
     */
    public float[] embed(String text) {
        Effective eff = effective();
        if (!enabled(eff)) return null;
        long start = System.nanoTime();
        try {
            // 超长文本截断，避免 "the model maximum context length is 512 tokens"
            String truncated = truncateToMaxTokens(text, eff.maxTokens());
            if (truncated == null || truncated.isBlank()) return null;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", eff.model());
            body.put("input", truncated);
            String response = restClient(eff).post()
                    .uri(eff.baseUrl() + "/embeddings")
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = JSON.readTree(response);
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode embedding = data.get(0).path("embedding");
                if (embedding.isArray()) {
                    float[] vec = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vec[i] = (float) embedding.get(i).asDouble();
                    }
                    recordDimension(eff, vec.length);
                    failureCount.set(0);
                    lastError = null;
                    recordTraffic(eff, true, start, null);
                    return vec;
                }
            }
            recordFailure("响应中没有 embedding 数组：" + abbreviate(response));
            recordTraffic(eff, false, start, "响应中没有 embedding 数组");
            return null;
        } catch (Exception e) {
            recordFailure(e.getMessage());
            recordTraffic(eff, false, start, e.getMessage());
            return null;
        }
    }

    /**
     * 用指定配置做一次维度探测（不改变默认配置），失败返回 0。
     * 用于「应用 Embedding 配置」前的兼容性校验：维度变了就必须重建索引。
     */
    public int probeDimension(EmbeddingProfile profile) {
        if (profile == null) return 0;
        Effective eff = new Effective(profile.id(), profile.baseUrl(), profile.apiKey(), profile.modelName(),
                profile.maxTokens() > 0 ? profile.maxTokens() : maxTokens, profile.dimension());
        if (!enabled(eff)) return 0;
        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", eff.model());
            body.put("input", "维度探测");
            String response = restClient(eff).post()
                    .uri(eff.baseUrl() + "/embeddings")
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode data = JSON.readTree(response).path("data");
            if (data.isArray() && data.size() > 0) {
                int size = data.get(0).path("embedding").size();
                recordTraffic(eff, true, start, null);
                return size;
            }
            recordTraffic(eff, false, start, "响应中没有 embedding 数组");
            return 0;
        } catch (Exception e) {
            recordFailure("维度探测失败：" + e.getMessage());
            recordTraffic(eff, false, start, e.getMessage());
            return 0;
        }
    }

    /**
     * 记录一次失败。首次失败打 ERROR（带上配置目标，便于定位 key/model 配错或内网不通），
     * 之后只每 50 次汇总一次，避免逐块上传时刷屏；成功的调用会重置计数。
     */
    private void recordFailure(String message) {
        lastError = message;
        long n = failureCount.incrementAndGet();
        if (n == 1) {
            log.error("Embedding 调用失败（后续同类失败只计数不刷屏）：{}；请检查管理控制台的 Embedding 配置（{}）",
                    message, describeTarget());
        } else if (n % 50 == 0) {
            log.error("Embedding 已连续失败 {} 次，最后一次：{}", n, message);
        }
    }

    private void recordDimension(Effective eff, int dimension) {
        if (dimension <= 0) return;
        if (!eff.profileId().equals(detectedProfileId)) {
            detectedProfileId = eff.profileId();
            detectedDimension = 0;
        }
        if (detectedDimension == 0) {
            detectedDimension = dimension;
            log.info("Embedding 维度探测为 {}（模型 {}，配置 {}）", dimension, eff.model(), eff.profileId());
        }
    }

    private void recordTraffic(Effective eff, boolean ok, long startNanos, String error) {
        if (traffic == null) return;
        long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
        if (ok) {
            traffic.record(TrafficCall.ok("embedding", eff.profileId(), eff.model(), null, 0, 0, 0, latencyMs));
        } else {
            traffic.record(TrafficCall.failed("embedding", eff.profileId(), eff.model(), null, latencyMs, error));
        }
    }

    private static String abbreviate(String text) {
        if (text == null) return "null";
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    /** 当前 embedding 目标描述（日志/报错用，不含密钥） */
    public String describeTarget() {
        Effective eff = effective();
        return "base-url=" + eff.baseUrl() + ", model=" + eff.model()
                + ", api-key=" + (eff.apiKey() == null || eff.apiKey().isBlank() ? "无（不发送认证头）" : "已配置");
    }

    /** 最近一次失败原因；成功调用后会清空 */
    public String getLastError() {
        return lastError;
    }

    /** 连续失败次数（成功调用后归零） */
    public long getFailureCount() {
        return failureCount.get();
    }

    /**
     * 批量文本转向量（OpenAI 兼容接口在一次请求里传 input 数组）
     *
     * <p>批量失败时退化为逐条调用，让单条坏数据不拖垮整批；返回列表与输入一一对应，
     * 失败位置为 null（调用方按"该块无向量"处理，不影响全文检索）。
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return results;
        Effective eff = effective();
        if (!enabled(eff)) {
            for (int i = 0; i < texts.size(); i++) results.add(null);
            return results;
        }

        List<String> prepared = new ArrayList<>(texts.size());
        for (String t : texts) {
            String truncated = truncateToMaxTokens(t, eff.maxTokens());
            prepared.add(truncated == null ? "" : truncated);
        }

        long start = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", eff.model());
            body.put("input", prepared);
            String response = restClient(eff).post()
                    .uri(eff.baseUrl() + "/embeddings")
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode data = JSON.readTree(response).path("data");
            float[][] parsed = new float[prepared.size()][];
            int next = 0;
            if (data.isArray()) {
                for (JsonNode item : data) {
                    JsonNode embedding = item.path("embedding");
                    if (!embedding.isArray()) continue;
                    float[] vec = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vec[i] = (float) embedding.get(i).asDouble();
                    }
                    int idx = item.has("index") ? item.path("index").asInt(-1) : -1;
                    if (idx < 0 || idx >= parsed.length || parsed[idx] != null) {
                        while (next < parsed.length && parsed[next] != null) next++;
                        idx = next < parsed.length ? next : -1;
                    }
                    if (idx >= 0) parsed[idx] = vec;
                }
            }

            int failed = 0;
            for (int i = 0; i < parsed.length; i++) {
                float[] v = parsed[i];
                if (v == null) {
                    // 批响应缺项时单独补一次，避免整块静默失去语义检索能力
                    v = embed(texts.get(i));
                }
                if (v == null) {
                    failed++;
                } else {
                    recordDimension(eff, v.length);
                }
                results.add(v);
            }
            if (failed == 0) {
                failureCount.set(0);
                lastError = null;
                recordTraffic(eff, true, start, null);
            } else {
                recordFailure(failed + "/" + prepared.size() + " 条向量生成失败：" + abbreviate(response));
                recordTraffic(eff, false, start, failed + " 条失败");
            }
            return results;
        } catch (Exception e) {
            recordFailure("批量调用失败，退化为逐条：" + e.getMessage());
            recordTraffic(eff, false, start, e.getMessage());
            for (String t : texts) results.add(embed(t));
            return results;
        }
    }

    /**
     * 批量文本转向量（兼容旧调用：内部走 embedBatch，失败位置为 null）
     */
    public List<float[]> embedAll(List<String> texts) {
        return embedBatch(texts);
    }

    public boolean isEnabled() {
        return enabled(effective());
    }

    public int getDimension() {
        Effective eff = effective();
        int configured = eff.dimension();
        return configured > 0 ? configured : detectedDimension;
    }

    /**
     * 将文本截断到指定 token 数以内
     *
     * 采用保守估算（bge-embedding 等 BERT 系分词器实测比旧算法更密）：
     *   CJK/数字/标点/符号/非 ASCII ≈ 1 token/字符
     *   ASCII 字母 ≈ 0.5 token/字符，空白 ≈ 0.5
     * 保证估算值 ≥ 真实 token 数，从而输入不超模型上下文上限（如 512 tokens）
     */
    private String truncateToMaxTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        if (maxTokens <= 0) return text;

        // 预留安全余量：旧算法 492 估算实测 → 530 实际（bge 分词更密），故加大到 60
        double budget = maxTokens - 60;

        double tokens = 0;
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            tokens += estimateTokenWeight(text.charAt(i));
            if (tokens > budget) {
                end = i;
                break;
            }
        }
        if (end < text.length()) {
            log.warn("Embedding 输入超长，已截断 {} → {} 字符（估算 {} tokens，限 {}）",
                    text.length(), end, Math.round(tokens), maxTokens);
            return text.substring(0, end);
        }
        return text;
    }

    /**
     * 单字符 token 数保守估算（宁多勿少，避免 bge 等 BERT 系分词器超限）
     */
    private double estimateTokenWeight(char c) {
        if (isCjk(c)) return 1.0;              // CJK：约 1 token/字符
        if (c > 0x7F) return 1.0;              // 非 ASCII（全角/中文标点/emoji/其他符号）：保守 1 token
        if (Character.isLetter(c)) return 0.5; // ASCII 字母：BERT 子词约 0.33-0.5，取 0.5
        if (Character.isDigit(c)) return 1.0;  // ASCII 数字：孤立数字常 1 token/个
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') return 0.5; // 空白
        return 1.0;                            // ASCII 标点/特殊字符：1 token/个
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_JAMO;
    }
}
