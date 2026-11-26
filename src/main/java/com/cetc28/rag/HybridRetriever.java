package com.cetc28.rag;

import com.cetc28.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：BM25 全文 + 向量 KNN，用 RRF（Reciprocal Rank Fusion）融合。
 *
 * <p>为什么两路：中文短查询下 BM25 对专有名词/型号/金额命中很准，但用户换个说法就查不到；
 * 向量对"预算大概多少钱"这类口语化提问更稳，但对数字和型号不敏感。两路互补后取并集排序，
 * 比任何单路都稳。
 *
 * <p>鲁棒性：任何一路失败都不抛出，只是把 degraded 标出来并降级到另一路。
 *
 * <p>注意：知识库检索已迁移到 Milvus（{@code com.cetc28.rag.milvus.MilvusHybridRetriever}），
 * 本类仅作为 Redis 时代的参考实现与回归对照保留，不再注册为 Spring 组件；新的检索入口统一为
 * {@link KnowledgeRetriever}。
 */
public class HybridRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final KnowledgeIndex index;
    private final EmbeddingService embeddingService;
    private final RagProperties props;

    public HybridRetriever(KnowledgeIndex index, EmbeddingService embeddingService, RagProperties props) {
        this.index = index;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    public RetrievalResult retrieve(String query) {
        return retrieve(query, Mode.HYBRID);
    }

    public RetrievalResult retrieve(String query, Mode mode) {
        return retrieve(query, mode, props.getRetrieve().getTopK(), null);
    }

    /**
     * 检索入口（带调用方覆盖项）。
     *
     * @param topK     API 指定的返回条数（≤0 时用配置值）
     * @param minScore API 指定的分数下限（null 时用配置值）
     */
    public RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore) {
        long start = System.currentTimeMillis();
        if (!props.isEnabled()) {
            return RetrievalResult.empty(query, "RAG 已关闭");
        }
        String normalized = normalize(query);
        if (normalized.length() < 2) {
            return RetrievalResult.empty(query, "问题过短，跳过检索");
        }

        index.ensureIndex();

        int candidateLimit = Math.max(1, props.getRetrieve().getCandidateLimit());
        Set<String> reasons = new LinkedHashSet<>();
        List<KnowledgeIndex.ChunkHit> bm25Hits = List.of();
        List<KnowledgeIndex.ChunkHit> vectorHits = List.of();

        if (mode != Mode.VECTOR) {
            bm25Hits = index.searchText(normalized, candidateLimit);
        }
        if (mode != Mode.BM25) {
            float[] vector = embeddingService.embed(normalized);
            if (vector == null) {
                reasons.add("embedding 不可用，已降级为全文检索");
            } else {
                vectorHits = index.searchVector(vector, candidateLimit);
            }
        }

        List<RetrievalResult.Hit> hits = mode == Mode.BM25
                ? fromSingle(bm25Hits, "bm25")
                : fromSingle(vectorHits, "vector");

        if (mode == Mode.HYBRID) {
            hits = fuse(bm25Hits, vectorHits, props.getRetrieve().getRrfK());
        }

        hits = applyFilters(hits, topK, minScore);
        long took = System.currentTimeMillis() - start;
        if (took > props.getRetrieve().getTimeoutMs()) {
            log.warn("知识库检索耗时 {}ms，超过预期 {}ms（query={}）", took, props.getRetrieve().getTimeoutMs(), normalized);
        }
        log.info("知识库检索完成：mode={}, query={}, bm25={}, vector={}, hits={}, took={}ms, degraded={}",
                mode, normalized, bm25Hits.size(), vectorHits.size(), hits.size(), took, reasons);

        return new RetrievalResult(query, hits, !reasons.isEmpty(), String.join("；", reasons),
                took, bm25Hits.size(), vectorHits.size());
    }

    /**
     * 单路结果：保留可比较的真实分数，而不是按名次编造。
     *
     * <p>向量路 = 余弦相似度（0~1，可配 min-vector-similarity 过滤）；BM25 路做
     * {@code s/(1+s)} 压缩到 0~1，方便和相似度共用 min-score 阈值与前端展示。
     */
    private List<RetrievalResult.Hit> fromSingle(List<KnowledgeIndex.ChunkHit> raw, String mode) {
        List<RetrievalResult.Hit> hits = new ArrayList<>();
        for (KnowledgeIndex.ChunkHit hit : raw) {
            if (hit.content() == null || hit.content().isBlank()) continue;
            double score = "vector".equals(mode) ? clamp01(hit.score()) : bm25Score(hit.score());
            hits.add(toHit(hit, score, mode));
        }
        hits.sort(Comparator.comparingDouble(RetrievalResult.Hit::score).reversed());
        return hits;
    }

    /** BM25 分数无上界，压缩到 0~1 便于展示与阈值过滤 */
    static double bm25Score(double raw) {
        if (raw <= 0) return 0.0;
        return raw / (raw + 1.0);
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** RRF 融合两路结果；同一块在两边都出现时分数相加，并标记为 hybrid */
    private List<RetrievalResult.Hit> fuse(List<KnowledgeIndex.ChunkHit> bm25Hits,
                                           List<KnowledgeIndex.ChunkHit> vectorHits, int rrfK) {
        Map<String, KnowledgeIndex.ChunkHit> byKey = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        Set<String> inBoth = new LinkedHashSet<>();

        for (int i = 0; i < bm25Hits.size(); i++) {
            KnowledgeIndex.ChunkHit hit = bm25Hits.get(i);
            byKey.putIfAbsent(hit.key(), hit);
            scores.merge(hit.key(), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            KnowledgeIndex.ChunkHit hit = vectorHits.get(i);
            if (byKey.containsKey(hit.key())) inBoth.add(hit.key());
            byKey.putIfAbsent(hit.key(), hit);
            scores.merge(hit.key(), 1.0 / (rrfK + i + 1), Double::sum);
        }

        List<RetrievalResult.Hit> hits = new ArrayList<>();
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            KnowledgeIndex.ChunkHit hit = byKey.get(entry.getKey());
            if (hit == null || hit.content() == null || hit.content().isBlank()) continue;
            String mode = inBoth.contains(entry.getKey()) ? "hybrid"
                    : (bm25Hits.stream().anyMatch(h -> h.key().equals(entry.getKey())) ? "bm25" : "vector");
            hits.add(toHit(hit, entry.getValue() / max, mode));
        }
        hits.sort(Comparator.comparingDouble(RetrievalResult.Hit::score).reversed());
        return hits;
    }

    static Map<String, Double> fuseKeys(List<String> first, List<String> second, int rrfK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < first.size(); i++) scores.merge(first.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        for (int i = 0; i < second.size(); i++) scores.merge(second.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        return scores;
    }

    /** 阈值过滤 + 单文件条数上限 + topK 截断 */
    private List<RetrievalResult.Hit> applyFilters(List<RetrievalResult.Hit> hits, int requestedTopK,
                                                   Double requestedMinScore) {
        int maxPerDoc = Math.max(1, props.getRetrieve().getMaxPerDoc());
        int topK = Math.max(1, requestedTopK > 0 ? requestedTopK : props.getRetrieve().getTopK());
        double minScore = requestedMinScore != null ? requestedMinScore : props.getRetrieve().getMinScore();
        Map<String, Integer> perDoc = new HashMap<>();
        List<RetrievalResult.Hit> filtered = new ArrayList<>();
        for (RetrievalResult.Hit hit : hits) {
            if (hit.score() < minScore) continue;
            String doc = hit.filename() == null ? "" : hit.filename();
            int used = perDoc.getOrDefault(doc, 0);
            if (used >= maxPerDoc) continue;
            perDoc.put(doc, used + 1);
            filtered.add(hit);
            if (filtered.size() >= topK) break;
        }
        return filtered;
    }

    private RetrievalResult.Hit toHit(KnowledgeIndex.ChunkHit hit, double score, String mode) {
        return new RetrievalResult.Hit(hit.key(), hit.content(), hit.filename(), hit.headingPath(),
                hit.lineStart(), hit.lineEnd(), score, mode);
    }

    @Override
    public ChunkView readChunk(String chunkId) {
        KnowledgeIndex.ChunkHit hit = index.getChunk(chunkId);
        if (hit == null) return null;
        return new ChunkView(hit.key(), hit.docId(), hit.chunkIndex(), hit.content(), null,
                hit.filename(), hit.headingPath(), hit.lineStart(), hit.lineEnd(), hit.score());
    }

    static String normalize(String query) {
        if (query == null) return "";
        String normalized = query.replaceAll("\\s+", " ").trim();
        while (normalized.length() > 200) {
            normalized = normalized.substring(0, 200);
        }
        return normalized;
    }
}
