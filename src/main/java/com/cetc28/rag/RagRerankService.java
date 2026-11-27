package com.cetc28.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 检索结果重排（可选，默认关闭）。
 *
 * <p>混合检索召回的是"块"，rerank 用交叉编码器直接对 query-块 打分，能把
 * "关键词命中但语义无关"的块压下去，是召回质量提升里性价比最高的一步。
 * 使用 DashScope 兼容的 gte-rerank 接口；失败时原样返回，绝不阻断检索。
 */
@Component
public class RagRerankService {

    private static final Logger log = LoggerFactory.getLogger(RagRerankService.class);

    /** rerank 结果：chunks 已按重排分降序（失败时为原列表）。 */
    public record RerankResult(List<ChunkView> chunks, boolean applied, String reason) {
        static RerankResult unchanged(List<ChunkView> chunks, String reason) {
            return new RerankResult(chunks, false, reason);
        }
    }

    private final RagProperties props;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String defaultApiKey;
    private volatile String lastError;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cetc28.modelconfig.ModelTrafficService traffic;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cetc28.modelconfig.ModelRuntime modelRuntime;

    public RagRerankService(RagProperties props,
                            ObjectMapper mapper,
                            @Value("${agentscope.embedding.api-key:}") String defaultApiKey) {
        this.props = props;
        this.mapper = mapper;
        this.defaultApiKey = defaultApiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) Math.max(200L, props.getRerank().getTimeoutMs());
        factory.setConnectTimeout(Duration.ofMillis(Math.min(2000, timeout)));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public RerankResult rerank(String query, List<ChunkView> candidates, int topN) {
        if (candidates == null || candidates.size() <= 1 || !props.getRerank().isEnabled()) {
            return RerankResult.unchanged(candidates, null);
        }
        String url = props.getRerank().getBaseUrl();
        String key = isBlank(props.getRerank().getApiKey()) ? defaultApiKey : props.getRerank().getApiKey();
        if (isBlank(key)) key = configuredEmbeddingApiKey();
        if (isBlank(url) || isBlank(key)) {
            return RerankResult.unchanged(candidates, "rerank 未配置 base-url/api-key");
        }
        int maxCandidates = Math.max(1, props.getRerank().getMaxCandidates());
        int batchSize = Math.max(1, props.getRerank().getBatchSize());
        List<ChunkView> limited = candidates.size() > maxCandidates
                ? new ArrayList<>(candidates.subList(0, maxCandidates))
                : new ArrayList<>(candidates);

        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        String firstError = null;
        int succeededBatches = 0;
        long startedAt = System.nanoTime();
        for (int start = 0; start < limited.size(); start += batchSize) {
            List<ChunkView> batch = limited.subList(start, Math.min(limited.size(), start + batchSize));
            List<String> documents = new ArrayList<>(batch.size());
            for (ChunkView chunk : batch) documents.add(ChunkView.textOf(chunk));
            Map<String, Object> body = Map.of(
                    "model", props.getRerank().getModel(),
                    "input", Map.of("query", query, "documents", documents),
                    "parameters", Map.of("return_documents", false, "top_n", batch.size()));
            try {
                String response = client.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                JsonNode root = mapper.readTree(response);
                JsonNode results = root.path("output").path("results");
                if (!results.isArray()) results = root.path("results");
                if (!results.isArray()) {
                    firstError = firstError == null ? "rerank 响应缺少 results" : firstError;
                    continue;
                }
                Map<String, Double> batchScores = parseBatchScores(batch, results);
                if (batchScores.isEmpty()) {
                    firstError = firstError == null ? "rerank 结果为空" : firstError;
                    continue;
                }
                scores.putAll(batchScores);
                succeededBatches++;
            } catch (Exception e) {
                firstError = firstError == null ? e.getMessage() : firstError;
                log.warn("rerank 分批调用失败（该批保留原分数）：{}", e.getMessage());
            }
        }

        if (succeededBatches == 0) {
            lastError = firstError == null ? "rerank 调用失败" : firstError;
            recordTraffic(false, startedAt, lastError);
            return RerankResult.unchanged(candidates, lastError);
        }

        List<ChunkView> merged = new ArrayList<>(limited.size());
        for (ChunkView chunk : limited) {
            Double score = scores.get(chunk.chunkId());
            merged.add(score == null ? chunk : withScore(chunk, score));
        }
        merged.sort(Comparator.comparingDouble(ChunkView::score).reversed());
        int keep = topN > 0 ? Math.min(topN, merged.size()) : merged.size();
        if (merged.size() > keep) merged = new ArrayList<>(merged.subList(0, keep));

        lastError = null;
        boolean partial = succeededBatches * batchSize < limited.size() && firstError != null;
        recordTraffic(true, startedAt, partial ? firstError : null);
        return new RerankResult(merged, true, partial ? "部分批次精排失败，已保留原分数：" + firstError : null);
    }

    /**
     * 把单批响应映射成 chunkId → 精排分。响应里的 index 是"批内下标"，
     * 必须用批的局部列表做映射，否则多批合并会串位。
     */
    Map<String, Double> parseBatchScores(List<ChunkView> batch, JsonNode results) {
        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= batch.size()) continue;
            double score = item.path("relevance_score").asDouble(0.0);
            scores.put(batch.get(index).chunkId(), score);
        }
        return scores;
    }

    private static ChunkView withScore(ChunkView chunk, double score) {
        return new ChunkView(chunk.chunkId(), chunk.docId(), chunk.chunkIndex(), chunk.content(),
                chunk.parentText(), chunk.filename(), chunk.headingPath(), chunk.lineStart(),
                chunk.lineEnd(), score, chunk.effectiveDomain());
    }

    public String getLastError() {
        return lastError;
    }

    private void recordTraffic(boolean ok, long startedAtNanos, String error) {
        if (traffic == null) return;
        long latencyMs = Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        String model = props.getRerank().getModel();
        if (ok) {
            traffic.record(com.cetc28.modelconfig.TrafficCall.ok("rerank", "rerank", model, null, 0, 0, 0, latencyMs));
        } else {
            traffic.record(com.cetc28.modelconfig.TrafficCall.failed("rerank", "rerank", model, null, latencyMs, error));
        }
    }

    /** rerank 未单独配 key 时，沿用当前默认 Embedding 配置里的 DashScope key（同一账号通用）。 */
    private String configuredEmbeddingApiKey() {
        if (modelRuntime == null) return null;
        try {
            return modelRuntime.defaultEmbedding()
                    .map(com.cetc28.modelconfig.EmbeddingProfile::apiKey)
                    .filter(key -> !isBlank(key))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
