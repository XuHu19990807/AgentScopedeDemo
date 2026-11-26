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
        if (isBlank(url) || isBlank(key)) {
            return RerankResult.unchanged(candidates, "rerank 未配置 base-url/api-key");
        }
        List<ChunkView> limited = candidates.size() > Math.max(1, topN)
                ? new ArrayList<>(candidates.subList(0, Math.max(1, topN)))
                : candidates;
        List<String> documents = new ArrayList<>(limited.size());
        for (ChunkView chunk : limited) documents.add(ChunkView.textOf(chunk));
        Map<String, Object> body = Map.of(
                "model", props.getRerank().getModel(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", limited.size()));
        try {
            long startedAt = System.nanoTime();
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
                lastError = "rerank 响应缺少 results";
                recordTraffic(false, startedAt, lastError);
                return RerankResult.unchanged(candidates, lastError);
            }
            List<ChunkView> reranked = new ArrayList<>(limited.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= limited.size()) continue;
                double score = item.path("relevance_score").asDouble(0.0);
                reranked.add(withScore(limited.get(index), score));
            }
            if (reranked.isEmpty()) {
                lastError = "rerank 结果为空";
                recordTraffic(false, startedAt, lastError);
                return RerankResult.unchanged(candidates, lastError);
            }
            reranked.sort(Comparator.comparingDouble(ChunkView::score).reversed());
            lastError = null;
            recordTraffic(true, startedAt, null);
            return new RerankResult(reranked, true, null);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("rerank 调用失败，回退混合检索原序：{}", e.getMessage());
            recordTraffic(false, System.nanoTime(), lastError);
            return RerankResult.unchanged(candidates, lastError);
        }
    }

    private static ChunkView withScore(ChunkView chunk, double score) {
        return new ChunkView(chunk.chunkId(), chunk.docId(), chunk.chunkIndex(), chunk.content(),
                chunk.parentText(), chunk.filename(), chunk.headingPath(), chunk.lineStart(),
                chunk.lineEnd(), score);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
