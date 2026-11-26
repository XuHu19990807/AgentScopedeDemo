package com.cetc28.rag.milvus;

import com.cetc28.rag.ChunkView;
import com.cetc28.rag.KnowledgeRetriever;
import com.cetc28.rag.RagProperties;
import com.cetc28.rag.RagQueryExpander;
import com.cetc28.rag.RagRerankService;
import com.cetc28.rag.RetrievalResult;
import com.cetc28.rag.RrfUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 混合检索：稠密（框架 {@link MilvusRagStore}）+ BM25（{@link MilvusBm25Store}）双路召回，
 * 加权 RRF 融合，可选 rerank 与查询扩展。
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

    public MilvusHybridRetriever(MilvusRagStore denseStore,
                                 MilvusBm25Store bm25Store,
                                 RagProperties props,
                                 RagRerankService rerankService,
                                 RagQueryExpander queryExpander) {
        this.denseStore = denseStore;
        this.bm25Store = bm25Store;
        this.props = props;
        this.rerankService = rerankService;
        this.queryExpander = queryExpander;
    }

    @Override
    public RetrievalResult retrieve(String query) {
        return retrieve(query, Mode.HYBRID, -1, null);
    }

    @Override
    public RetrievalResult retrieve(String query, Mode mode) {
        return retrieve(query, mode, -1, null);
    }

    @Override
    public RetrievalResult retrieve(String query, Mode mode, int topK, Double minScore) {
        long started = System.currentTimeMillis();
        String normalized = RrfUtil.normalize(query);
        if (normalized.isBlank()) return RetrievalResult.empty(query, "空查询");
        Mode effective = mode == null ? Mode.HYBRID : mode;
        int limit = topK > 0 ? topK : Math.max(1, props.getRetrieve().getTopK());
        int candidateLimit = Math.max(limit, props.getRetrieve().getCandidateLimit());
        double floor = minScore != null ? minScore : props.getRetrieve().getMinScore();

        List<String> denseKeys = new ArrayList<>();
        Map<String, Double> denseScores = new LinkedHashMap<>();
        String denseError = null;
        if (effective != Mode.BM25) {
            try {
                Double threshold = props.getRetrieve().getMinVectorSimilarity();
                for (MilvusRagStore.DenseHit hit : denseStore.searchDense(normalized, candidateLimit, threshold)) {
                    denseKeys.add(hit.chunkId());
                    denseScores.put(hit.chunkId(), hit.score());
                }
            } catch (Exception e) {
                denseError = e.getMessage();
                log.warn("稠密检索失败：{}", e.getMessage());
            }
        }

        List<String> bm25Keys = new ArrayList<>();
        Map<String, Double> bm25Scores = new LinkedHashMap<>();
        String bm25Error = null;
        if (effective != Mode.VECTOR) {
            try {
                for (ChunkView hit : bm25Store.search(normalized, candidateLimit)) {
                    bm25Keys.add(hit.chunkId());
                    bm25Scores.put(hit.chunkId(), hit.score());
                }
            } catch (Exception e) {
                bm25Error = e.getMessage();
                log.warn("BM25 检索失败：{}", e.getMessage());
            }
        }

        if (denseKeys.isEmpty() && bm25Keys.isEmpty()) {
            String reason = describeFailure(effective, denseError, bm25Error);
            return RetrievalResult.empty(query, reason);
        }

        // 低分时用改写查询补 BM25 召回（成本低、只增不减）
        if (effective == Mode.HYBRID && props.getQueryExpansion().isEnabled() && belowTrigger(denseScores, bm25Scores)) {
            for (String expanded : queryExpander.expand(normalized)) {
                try {
                    for (ChunkView hit : bm25Store.search(expanded, candidateLimit)) {
                        bm25Keys.add(hit.chunkId());
                        bm25Scores.put(hit.chunkId(), hit.score());
                    }
                } catch (Exception e) {
                    log.debug("扩展查询检索失败（忽略）：{}", e.getMessage());
                    break;
                }
            }
        }

        Map<String, Double> fused = fuse(effective, denseKeys, bm25Keys);
        Set<String> ordered = new LinkedHashSet<>(RrfUtil.orderByScore(fused));
        List<ChunkView> views = bm25Store.getByIds(new ArrayList<>(ordered));
        if (views.isEmpty()) {
            return RetrievalResult.empty(query, "命中索引但元数据缺失（BM25 集合不可用？）");
        }
        Map<String, ChunkView> viewById = new LinkedHashMap<>();
        for (ChunkView view : views) viewById.put(view.chunkId(), view);

        List<ChunkView> candidates = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            ChunkView view = viewById.get(key);
            if (view == null) continue;
            candidates.add(withScore(view, fused.getOrDefault(key, 0.0)));
        }
        candidates.sort(Comparator.comparingDouble(ChunkView::score).reversed());

        boolean degraded = false;
        String degradedReason = null;
        if (effective == Mode.HYBRID && denseError != null) {
            degraded = true;
            degradedReason = "向量检索降级：" + denseError;
        } else if (effective == Mode.HYBRID && bm25Error != null) {
            degraded = true;
            degradedReason = "BM25 检索降级：" + bm25Error;
        } else if (effective == Mode.VECTOR && denseError != null) {
            degraded = true;
            degradedReason = "向量检索不可用：" + denseError;
        } else if (effective == Mode.BM25 && bm25Error != null) {
            degraded = true;
            degradedReason = "BM25 检索不可用：" + bm25Error;
        }

        List<ChunkView> filtered = applyFilters(candidates, floor);
        boolean timedOut = System.currentTimeMillis() - started > Math.max(200, props.getRetrieve().getTimeoutMs());
        if (props.getRerank().isEnabled() && !timedOut && filtered.size() > 1) {
            RagRerankService.RerankResult result = rerankService.rerank(
                    normalized, filtered, Math.max(limit, props.getRerank().getTopN()));
            if (result.applied()) {
                filtered = result.chunks();
            } else if (result.reason() != null) {
                degraded = true;
                degradedReason = degradedReason == null ? result.reason() : degradedReason + "；" + result.reason();
            }
        }
        List<ChunkView> finalViews = filtered.size() > limit ? new ArrayList<>(filtered.subList(0, limit)) : filtered;

        List<RetrievalResult.Hit> hits = new ArrayList<>(finalViews.size());
        for (ChunkView view : finalViews) {
            hits.add(new RetrievalResult.Hit(
                    view.chunkId(),
                    view.text(),
                    view.filename(),
                    view.headingPath(),
                    view.lineStart(),
                    view.lineEnd(),
                    view.score(),
                    matchedBy(effective, denseScores, bm25Scores, view.chunkId())));
        }
        long took = System.currentTimeMillis() - started;
        return new RetrievalResult(query, hits, degraded, degradedReason, took,
                bm25Scores.size(), denseScores.size());
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

    private List<ChunkView> applyFilters(List<ChunkView> candidates, double floor) {
        List<ChunkView> filtered = new ArrayList<>(candidates.size());
        Map<String, Integer> perDoc = new LinkedHashMap<>();
        int maxPerDoc = Math.max(1, props.getRetrieve().getMaxPerDoc());
        for (ChunkView view : candidates) {
            if (floor > 0 && view.score() < floor) continue;
            String doc = view.filename() == null ? "" : view.filename();
            int used = perDoc.getOrDefault(doc, 0);
            if (used >= maxPerDoc) continue;
            perDoc.put(doc, used + 1);
            filtered.add(view);
        }
        return filtered;
    }

    private static ChunkView withScore(ChunkView view, double score) {
        return new ChunkView(view.chunkId(), view.docId(), view.chunkIndex(), view.content(),
                view.parentText(), view.filename(), view.headingPath(), view.lineStart(), view.lineEnd(), score);
    }

    private static String matchedBy(Mode mode, Map<String, Double> dense, Map<String, Double> bm25, String chunkId) {
        boolean d = dense.containsKey(chunkId);
        boolean b = bm25.containsKey(chunkId);
        if (d && b) return "hybrid";
        if (d) return "vector";
        if (b) return "bm25";
        return mode == Mode.VECTOR ? "vector" : "bm25";
    }

    private static String describeFailure(Mode mode, String denseError, String bm25Error) {
        if (mode == Mode.VECTOR) return denseError != null ? denseError : "无向量命中";
        if (mode == Mode.BM25) return bm25Error != null ? bm25Error : "无词法命中";
        if (denseError != null && bm25Error != null) return "向量与词法检索均不可用：" + denseError + "；" + bm25Error;
        return "知识库为空或无匹配内容";
    }

    @Override
    public ChunkView readChunk(String chunkId) {
        return bm25Store.getById(chunkId);
    }
}
