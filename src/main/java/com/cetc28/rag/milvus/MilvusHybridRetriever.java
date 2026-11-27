package com.cetc28.rag.milvus;

import com.cetc28.rag.ChunkView;
import com.cetc28.rag.KnowledgeRetriever;
import com.cetc28.rag.RagProperties;
import com.cetc28.rag.RagQueryExpander;
import com.cetc28.rag.RagRerankService;
import com.cetc28.rag.RagSelector;
import com.cetc28.rag.RetrievalResult;
import com.cetc28.rag.RrfUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 混合检索：稠密（框架 {@link MilvusRagStore}，可切换原生 HNSW）+ BM25（{@link MilvusBm25Store}）双路召回，
 * 加权 RRF 融合，可选查询扩展、批量精排与多样性选择。
 *
 * <p>大库场景下的精度控制点：两路各取独立候选数（200/300），相似度阈值默认后置到精排之后，
 * 精排后再用 {@link RagSelector} 做去重与同文档/同章节配额，最后截断到 topK。
 */
@Component
@Primary
public class MilvusHybridRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(MilvusHybridRetriever.class);

    private final MilvusRagStore denseStore;
    private final MilvusBm25Store bm25Store;
    private final RagProperties props;
    private final RagRerankService rerankService;
    private final RagQueryExpander queryExpander;
    private final RagSelector selector;

    public MilvusHybridRetriever(MilvusRagStore denseStore,
                                 MilvusBm25Store bm25Store,
                                 RagProperties props,
                                 RagRerankService rerankService,
                                 RagQueryExpander queryExpander,
                                 RagSelector selector) {
        this.denseStore = denseStore;
        this.bm25Store = bm25Store;
        this.props = props;
        this.rerankService = rerankService;
        this.queryExpander = queryExpander;
        this.selector = selector;
    }

    @Override
    public RetrievalResult retrieve(String query) {
        return retrieveInDomains(query, Mode.HYBRID, -1, null, null);
    }

    @Override
    public RetrievalResult retrieve(String query, Mode mode) {
        return retrieveInDomains(query, mode, -1, null, null);
    }

    @Override
    public RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore) {
        return retrieveInDomains(query, mode, topK, minScore, null);
    }

    @Override
    public RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore, String domain) {
        return retrieveInDomains(query, mode, topK, minScore,
                domain == null || domain.isBlank() ? null : List.of(domain.trim()));
    }

    @Override
    public RetrievalResult retrieveInDomains(String query, Mode mode, int topK, Double minScore,
                                             Collection<String> domains) {
        PipelineResult result = run(query, mode, topK, minScore, domains, false);
        if (result.emptyReason() != null) {
            return RetrievalResult.empty(query, result.emptyReason());
        }
        List<RetrievalResult.Hit> hits = new ArrayList<>(result.selected().size());
        for (ChunkView view : result.selected()) {
            hits.add(new RetrievalResult.Hit(
                    view.chunkId(),
                    view.text(),
                    view.filename(),
                    view.headingPath(),
                    view.lineStart(),
                    view.lineEnd(),
                    view.score(),
                    matchedBy(result.mode(), result.denseScores(), result.bm25Scores(), view.chunkId())));
        }
        boolean degraded = !result.degraded().isEmpty();
        return new RetrievalResult(query, hits, degraded,
                degraded ? String.join("；", result.degraded()) : null,
                result.tookMs(), result.bm25Scores().size(), result.denseScores().size());
    }

    @Override
    public Diagnosis diagnose(String query, Mode mode, Integer topK, String domain) {
        return diagnoseInDomains(query, mode, topK,
                domain == null || domain.isBlank() ? null : List.of(domain.trim()));
    }

    @Override
    public Diagnosis diagnoseInDomains(String query, Mode mode, Integer topK, Collection<String> domains) {
        PipelineResult result = run(query, mode, topK == null ? -1 : topK, null, domains, true);
        return new Diagnosis(query, result.mode().name(), result.domain(), result.tookMs(),
                result.denseViews(), result.bm25Views(), result.fused(), result.reranked(),
                result.selected(), result.degraded());
    }

    @Override
    public ChunkView readChunk(String chunkId) {
        return bm25Store.getById(chunkId);
    }

    private PipelineResult run(String query, Mode mode, int topK, Double minScore,
                               Collection<String> domains, boolean diagnostics) {
        long started = System.currentTimeMillis();
        String normalized = RrfUtil.normalize(query);
        Mode effective = mode == null ? Mode.HYBRID : mode;
        List<String> domainKeys = normalizeDomains(domains);
        String domainLabel = domainKeys == null ? null : String.join(",", domainKeys);
        if (normalized.isBlank()) {
            return PipelineResult.empty(query, effective, domainLabel, "空查询", started);
        }
        if (domainKeys != null && domainKeys.isEmpty()) {
            return PipelineResult.empty(query, effective, domainLabel, "当前用户没有可用知识域授权", started);
        }
        RagProperties.Retrieve cfg = props.getRetrieve();
        int limit = topK > 0 ? topK : Math.max(1, cfg.getTopK());
        int denseLimit = Math.max(limit, Math.max(1, cfg.getDenseCandidateLimit()));
        int bm25Limit = Math.max(limit, Math.max(1, cfg.getBm25CandidateLimit()));
        double floor = minScore != null ? minScore : cfg.getMinScore();
        double minSimilarity = cfg.getMinVectorSimilarity();
        boolean preSimilarity = "pre".equalsIgnoreCase(cfg.getSimilarityFilter());

        List<String> degraded = new ArrayList<>();
        List<String> denseKeys = new ArrayList<>();
        Map<String, Double> denseScores = new LinkedHashMap<>();
        if (effective != Mode.BM25) {
            try {
                for (MilvusRagStore.DenseHit hit : denseHits(normalized, denseLimit, domainKeys,
                        preSimilarity ? minSimilarity : null)) {
                    denseKeys.add(hit.chunkId());
                    denseScores.put(hit.chunkId(), hit.score());
                }
            } catch (Exception e) {
                degraded.add("向量检索降级：" + e.getMessage());
                log.warn("稠密检索失败：{}", e.getMessage());
            }
        }

        List<ChunkView> bm25Views = new ArrayList<>();
        if (effective != Mode.VECTOR) {
            try {
                bm25Views = bm25Store.search(normalized, bm25Limit, MilvusBm25Store.domainsFilter(domainKeys));
            } catch (Exception e) {
                degraded.add("BM25 检索降级：" + e.getMessage());
                log.warn("BM25 检索失败：{}", e.getMessage());
            }
        }
        List<String> bm25Keys = new ArrayList<>(bm25Views.size());
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        for (ChunkView view : bm25Views) {
            bm25Keys.add(view.chunkId());
            bm25Scores.put(view.chunkId(), view.score());
        }

        if (denseKeys.isEmpty() && bm25Keys.isEmpty()) {
            String reason = describeFailure(effective, degraded);
            return PipelineResult.empty(query, effective, domainLabel, reason, started);
        }

        // 低分时用改写查询补 BM25 召回（成本低、只增不减）
        String bm25Filter = MilvusBm25Store.domainsFilter(domainKeys);
        if (effective == Mode.HYBRID && props.getQueryExpansion().isEnabled() && belowTrigger(denseScores, bm25Scores)) {
            for (String expanded : queryExpander.expand(normalized)) {
                try {
                    for (ChunkView hit : bm25Store.search(expanded, bm25Limit, bm25Filter)) {
                        if (bm25Scores.containsKey(hit.chunkId())) continue;
                        bm25Keys.add(hit.chunkId());
                        bm25Scores.put(hit.chunkId(), hit.score());
                        bm25Views.add(hit);
                    }
                } catch (Exception e) {
                    log.debug("扩展查询检索失败（忽略）：{}", e.getMessage());
                    break;
                }
            }
        }

        Map<String, Double> fused = fuse(effective, denseKeys, bm25Keys);
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(RrfUtil.orderByScore(fused)));
        List<ChunkView> views = bm25Store.getByIds(ordered);
        if (views.isEmpty()) {
            return PipelineResult.empty(query, effective, domainLabel, "命中索引但元数据缺失（BM25 集合不可用？）", started);
        }
        Map<String, ChunkView> viewById = new LinkedHashMap<>();
        for (ChunkView view : views) viewById.put(view.chunkId(), view);

        List<ChunkView> fusedViews = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            ChunkView view = viewById.get(key);
            if (view == null) continue;
            fusedViews.add(withScore(view, fused.getOrDefault(key, 0.0)));
        }
        fusedViews.sort(Comparator.comparingDouble(ChunkView::score).reversed());

        // 域兜底过滤（BM25 用 expr、原生稠密用 payload expr；框架稠密路径只能事后过滤）
        List<ChunkView> floored = new ArrayList<>(fusedViews.size());
        for (ChunkView view : fusedViews) {
            if (domainKeys != null && !domainKeys.contains(view.effectiveDomain())) continue;
            if (floor > 0 && view.score() < floor) continue;
            floored.add(view);
        }

        boolean timedOut = System.currentTimeMillis() - started > Math.max(200, cfg.getTimeoutMs());
        List<ChunkView> reranked = floored;
        if (props.getRerank().isEnabled() && !timedOut && floored.size() > 1) {
            RagRerankService.RerankResult result = rerankService.rerank(
                    normalized, floored, Math.max(limit, props.getRerank().getTopN()));
            if (result.applied()) {
                reranked = result.chunks();
            } else if (result.reason() != null) {
                degraded.add(result.reason());
            }
        }

        List<ChunkView> afterSimilarity = reranked;
        if (!preSimilarity && minSimilarity > 0 && !denseScores.isEmpty()) {
            afterSimilarity = new ArrayList<>(reranked.size());
            for (ChunkView view : reranked) {
                Double denseScore = denseScores.get(view.chunkId());
                if (denseScore != null && denseScore < minSimilarity) continue;
                afterSimilarity.add(view);
            }
        }

        List<ChunkView> selected = selector.select(afterSimilarity, limit, props);

        List<ChunkView> denseViews = diagnostics ? denseViews(denseKeys, denseScores) : List.of();
        return new PipelineResult(query, effective, domainLabel, denseViews, bm25Views, fusedViews,
                reranked, selected, degraded, System.currentTimeMillis() - started,
                denseScores, bm25Scores, null);
    }

    /**
     * 域集合归一：null 表示不限（管理员/内部的兼容调用）；否则去空、去重。
     * 返回空列表表示明确"无任何可用知识域"，调用方应直接返回空结果。
     */
    private static List<String> normalizeDomains(Collection<String> domains) {
        if (domains == null) return null;
        List<String> keys = new ArrayList<>();
        for (String domain : domains) {
            if (domain == null) continue;
            String value = domain.trim();
            if (!value.isEmpty() && !keys.contains(value)) keys.add(value);
        }
        return keys;
    }

    private List<MilvusRagStore.DenseHit> denseHits(String query, int limit, List<String> domains,
                                                   Double threshold) {
        String denseMode = props.getMilvus().getDenseMode();
        boolean nativeAllowed = denseMode != null && !"framework".equalsIgnoreCase(denseMode)
                && denseStore.nativeAvailable();
        if (nativeAllowed) {
            try {
                String filter = MilvusBm25Store.domainsFilter(domains) == null ? null
                        : "payload[\"" + MilvusRagStore.PAYLOAD_DOMAIN + "\"] in "
                        + MilvusBm25Store.domainList(domains);
                return denseStore.searchNative(query, limit, filter,
                        props.getMilvus().getIndex().getEfSearch());
            } catch (Exception e) {
                denseStore.disableNative(e.getMessage());
            }
        }
        return denseStore.searchDense(query, limit, threshold);
    }

    private List<ChunkView> denseViews(List<String> denseKeys, Map<String, Double> denseScores) {
        if (denseKeys.isEmpty()) return List.of();
        List<ChunkView> views = bm25Store.getByIds(denseKeys);
        Map<String, ChunkView> byId = new LinkedHashMap<>();
        for (ChunkView view : views) byId.put(view.chunkId(), view);
        List<ChunkView> result = new ArrayList<>(denseKeys.size());
        for (String key : denseKeys) {
            ChunkView view = byId.get(key);
            if (view != null) result.add(withScore(view, denseScores.getOrDefault(key, 0.0)));
        }
        return result;
    }

    private Map<String, Double> fuse(Mode mode, List<String> denseKeys, List<String> bm25Keys) {
        if (mode == Mode.VECTOR) {
            return rankOnly(denseKeys, props.getRetrieve().getDenseWeight());
        }
        if (mode == Mode.BM25) {
            return rankOnly(bm25Keys, props.getRetrieve().getBm25Weight());
        }
        return RrfUtil.fuseWeighted(denseKeys, bm25Keys, Math.max(1, props.getRetrieve().getRrfK()),
                props.getRetrieve().getDenseWeight(), props.getRetrieve().getBm25Weight());
    }

    private Map<String, Double> rankOnly(List<String> keys, double weight) {
        Map<String, Double> scores = new LinkedHashMap<>();
        int k = Math.max(1, props.getRetrieve().getRrfK());
        for (int i = 0; i < keys.size(); i++) {
            scores.put(keys.get(i), weight / (k + i + 1.0));
        }
        return scores;
    }

    private boolean belowTrigger(Map<String, Double> denseScores, Map<String, Double> bm25Scores) {
        double best = 0.0;
        for (double score : denseScores.values()) best = Math.max(best, score);
        for (double score : bm25Scores.values()) best = Math.max(best, RrfUtil.bm25Score(score));
        return best < props.getQueryExpansion().getTriggerScore();
    }

    private static ChunkView withScore(ChunkView view, double score) {
        return new ChunkView(view.chunkId(), view.docId(), view.chunkIndex(), view.content(),
                view.parentText(), view.filename(), view.headingPath(), view.lineStart(), view.lineEnd(),
                score, view.domain());
    }

    private static String matchedBy(Mode mode, Map<String, Double> dense, Map<String, Double> bm25, String chunkId) {
        boolean d = dense.containsKey(chunkId);
        boolean b = bm25.containsKey(chunkId);
        if (d && b) return "hybrid";
        if (d) return "vector";
        if (b) return "bm25";
        return mode == Mode.VECTOR ? "vector" : "bm25";
    }

    private static String describeFailure(Mode mode, List<String> degraded) {
        if (!degraded.isEmpty()) return String.join("；", degraded);
        if (mode == Mode.VECTOR) return "无向量命中";
        if (mode == Mode.BM25) return "无词法命中";
        return "知识库为空或无匹配内容";
    }

    private record PipelineResult(
            String query,
            Mode mode,
            String domain,
            List<ChunkView> denseViews,
            List<ChunkView> bm25Views,
            List<ChunkView> fused,
            List<ChunkView> reranked,
            List<ChunkView> selected,
            List<String> degraded,
            long tookMs,
            Map<String, Double> denseScores,
            Map<String, Double> bm25Scores,
            String emptyReason) {

        static PipelineResult empty(String query, Mode mode, String domain, String reason, long started) {
            return new PipelineResult(query, mode, domain, List.of(), List.of(), List.of(), List.of(),
                    List.of(), reason == null ? List.of() : List.of(reason),
                    System.currentTimeMillis() - started, Map.of(), Map.of(), reason);
        }
    }
}
