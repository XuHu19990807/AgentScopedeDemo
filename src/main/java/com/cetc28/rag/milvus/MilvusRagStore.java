package com.cetc28.rag.milvus;

import com.cetc28.rag.RagProperties;
import com.cetc28.service.EmbeddingService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.MilvusStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 稠密向量集合（框架 {@link MilvusStore} + {@link SimpleKnowledge}）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>写：调用方已完成批量向量化，这里只负责组织 {@link Document} 并写入框架 store
 *       —— 避免 {@code SimpleKnowledge.addDocuments} 逐块串行再 embed 一遍。</li>
 *   <li>读：走 {@code SimpleKnowledge.retrieve}，保留框架的检索语义（limit / scoreThreshold 过滤、按分降序）。</li>
 *   <li>元数据以 BM25 集合为准，稠密检索只返回 chunkId + score。</li>
 * </ul>
 */
@Component
public class MilvusRagStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusRagStore.class);

    public static final String PAYLOAD_FILENAME = "filename";
    public static final String PAYLOAD_HEADING = "heading_path";
    public static final String PAYLOAD_LINE_START = "line_start";
    public static final String PAYLOAD_LINE_END = "line_end";
    public static final String PAYLOAD_CHUNK_INDEX = "chunk_index";
    public static final String PAYLOAD_PARENT = "parent_text";

    private static final Duration OP_TIMEOUT = Duration.ofSeconds(20);

    /** 稠密检索命中：只携带 chunkId 与相似度，元数据回 BM25 集合补齐。 */
    public record DenseHit(String chunkId, double score) {
    }

    private final MilvusClientProvider clients;
    private final MilvusBm25Store bm25Store;
    private final MilvusEmbeddingModel embeddingModel;
    private final EmbeddingService embeddingService;
    private final RagProperties props;

    private final AtomicReference<Holder> holder = new AtomicReference<>();
    private final Object lock = new Object();
    private volatile String lastError;

    private record Holder(SimpleKnowledge knowledge, MilvusStore store, String collection) {
    }

    public MilvusRagStore(MilvusClientProvider clients,
                          MilvusBm25Store bm25Store,
                          MilvusEmbeddingModel embeddingModel,
                          EmbeddingService embeddingService,
                          RagProperties props) {
        this.clients = clients;
        this.bm25Store = bm25Store;
        this.embeddingModel = embeddingModel;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    public String collectionName() {
        return clients.denseCollection();
    }

    /**
     * embedding 配置切换（维度变化、集合版本自增）后调用：丢弃缓存的 MilvusStore/SimpleKnowledge，
     * 下一次访问会按新的集合名与维度重建。
     */
    public void resetHolder() {
        synchronized (lock) {
            holder.set(null);
            lastError = null;
        }
        log.info("稠密向量集合缓存已重置，下次访问使用：{}", collectionName());
    }

    private Holder holder() {
        Holder current = holder.get();
        if (current != null && current.collection().equals(collectionName())) return current;
        synchronized (lock) {
            Holder again = holder.get();
            if (again != null && again.collection().equals(collectionName())) return again;
            try {
                int dimension = resolveDimension();
                MilvusClientV2 client = clients.client();
                String collection = collectionName();
                boolean exists = Boolean.TRUE.equals(client.hasCollection(
                        HasCollectionReq.builder().collectionName(collection).build()));
                MilvusStore.Builder builder = MilvusStore.builder()
                        .uri(props.getMilvus().getUri())
                        .collectionName(collection)
                        .dimensions(dimension)
                        .connectTimeoutMs(props.getMilvus().getConnectTimeoutMs())
                        .metricType(IndexParam.MetricType.valueOf(props.getMilvus().getMetric()))
                        .databaseName(props.getMilvus().getDatabase());
                if (!isBlank(props.getMilvus().getToken())) {
                    builder.token(props.getMilvus().getToken());
                } else if (!isBlank(props.getMilvus().getUsername())) {
                    builder.username(props.getMilvus().getUsername())
                            .password(props.getMilvus().getPassword());
                }
                MilvusStore store = builder.build();
                if (exists) {
                    try {
                        client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
                    } catch (Exception loadError) {
                        log.debug("稠密集合 {} 加载请求返回：{}", collection, loadError.getMessage());
                    }
                }
                clients.ensureStrongConsistency(collection);
                SimpleKnowledge knowledge = SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(store)
                        .build();
                Holder created = new Holder(knowledge, store, collection);
                holder.set(created);
                lastError = null;
                log.info("稠密向量集合就绪：{}（dimensions={}，metric={}）",
                        collection, dimension, props.getMilvus().getMetric());
                return created;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("稠密向量集合 {} 初始化失败：{}", collectionName(), e.getMessage());
                throw new IllegalStateException("Milvus 稠密集合不可用：" + e.getMessage(), e);
            }
        }
    }

    private int resolveDimension() {
        int configured = props.getEmbed().getDimension();
        if (configured > 0) return configured;
        int detected = embeddingService.getDimension();
        if (detected > 0) return detected;
        float[] probe = embeddingService.embed("dimension-probe");
        detected = probe != null && probe.length > 0 ? probe.length : embeddingService.getDimension();
        if (detected <= 0) {
            throw new IllegalStateException("无法确定 embedding 维度（检查 agentscope.embedding 配置）");
        }
        return detected;
    }

    /** 写入一批块：稠密集合 + BM25 集合，任一失败则双删回滚。 */
    public void upsert(List<StoredChunk> chunks, List<float[]> vectors) {
        if (chunks == null || chunks.isEmpty()) return;
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("向量数量与块数量不一致");
        }
        Holder current = holder();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            StoredChunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            double[] embedding = new double[vector.length];
            for (int d = 0; d < vector.length; d++) embedding[d] = vector[d];
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(PAYLOAD_FILENAME, chunk.filename());
            payload.put(PAYLOAD_HEADING, chunk.headingPath());
            payload.put(PAYLOAD_LINE_START, chunk.lineStart());
            payload.put(PAYLOAD_LINE_END, chunk.lineEnd());
            payload.put(PAYLOAD_CHUNK_INDEX, chunk.chunkIndex());
            if (chunk.parentText() != null) payload.put(PAYLOAD_PARENT, chunk.parentText());
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunk.embeddingText()).build())
                    .docId(chunk.docId())
                    .chunkId(chunk.chunkId())
                    .payload(payload)
                    .build();
            Document document = new Document(metadata);
            document.setEmbedding(embedding);
            documents.add(document);
        }
        try {
            current.store().add(documents).block(OP_TIMEOUT);
            int inserted = bm25().insert(chunks);
            log.debug("写入 {} 个块（Milvus 稠密 + {} 条 BM25）", documents.size(), inserted);
        } catch (Exception e) {
            rollback(chunks);
            throw new IllegalStateException("写入 Milvus 失败：" + e.getMessage(), e);
        }
    }

    private MilvusBm25Store bm25() {
        return bm25Store;
    }

    /** 只写稠密集合（调用方已算好向量；编辑/启用单个分段时使用）。 */
    public void addDense(List<StoredChunk> chunks, List<float[]> vectors) {
        if (chunks == null || chunks.isEmpty()) return;
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("向量数量与块数量不一致");
        }
        Holder current = holder();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(toDocument(chunks.get(i), vectors.get(i)));
        }
        try {
            current.store().add(documents).block(OP_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("写入 Milvus 稠密集合失败：" + e.getMessage(), e);
        }
    }

    /** 按 chunkId 删除稠密集合中的行（先用 chunk_id 过滤查出主键 id，再按 id 删）。 */
    public int deleteDenseChunks(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return 0;
        try {
            MilvusClientV2 client = clients.client();
            int removed = 0;
            int batchSize = 100;
            for (int start = 0; start < chunkIds.size(); start += batchSize) {
                List<String> batch = chunkIds.subList(start, Math.min(chunkIds.size(), start + batchSize));
                StringBuilder filter = new StringBuilder("chunk_id in [");
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) filter.append(',');
                    filter.append(quote(batch.get(i)));
                }
                filter.append(']');
                QueryResp resp = client.query(QueryReq.builder()
                        .collectionName(collectionName())
                        .filter(filter.toString())
                        .outputFields(List.of("id"))
                        .limit((long) batch.size())
                        .build());
                if (resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) continue;
                List<Object> ids = new ArrayList<>();
                for (QueryResp.QueryResult result : resp.getQueryResults()) {
                    Object id = result.getEntity().get("id");
                    if (id != null) ids.add(id);
                }
                if (ids.isEmpty()) continue;
                var del = client.delete(io.milvus.v2.service.vector.request.DeleteReq.builder()
                        .collectionName(collectionName())
                        .ids(ids)
                        .build());
                removed += (int) del.getDeleteCnt();
            }
            return removed;
        } catch (Exception e) {
            log.warn("删除稠密集合分段失败：{}", e.getMessage());
            return 0;
        }
    }

    private static String quote(String value) {
        if (value == null) return "' '".trim();
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Document toDocument(StoredChunk chunk, float[] vector) {
        double[] embedding = new double[vector.length];
        for (int d = 0; d < vector.length; d++) embedding[d] = vector[d];
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_FILENAME, chunk.filename());
        payload.put(PAYLOAD_HEADING, chunk.headingPath());
        payload.put(PAYLOAD_LINE_START, chunk.lineStart());
        payload.put(PAYLOAD_LINE_END, chunk.lineEnd());
        payload.put(PAYLOAD_CHUNK_INDEX, chunk.chunkIndex());
        if (chunk.parentText() != null) payload.put(PAYLOAD_PARENT, chunk.parentText());
        DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text(chunk.embeddingText()).build())
                .docId(chunk.docId())
                .chunkId(chunk.chunkId())
                .payload(payload)
                .build();
        Document document = new Document(metadata);
        document.setEmbedding(embedding);
        return document;
    }

    /** 删除一个文档的全部块（两个集合都删）。 */
    public int deleteDoc(String docId) {
        if (docId == null || docId.isBlank()) return 0;
        int removed = 0;
        try {
            Boolean ok = holder().store().delete(docId).block(OP_TIMEOUT);
            if (Boolean.TRUE.equals(ok)) removed++;
        } catch (Exception e) {
            log.warn("删除稠密集合文档 {} 失败：{}", docId, e.getMessage());
        }
        try {
            removed += bm25().deleteDoc(docId);
        } catch (Exception e) {
            log.warn("删除 BM25 文档 {} 失败：{}", docId, e.getMessage());
        }
        return removed;
    }

    private void rollback(List<StoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        String docId = chunks.get(0).docId();
        try {
            deleteDoc(docId);
            log.warn("写入失败，已回滚文档 {} 的 {} 个块", docId, chunks.size());
        } catch (Exception e) {
            log.error("回滚文档 {} 失败，可能残留脏数据：{}", docId, e.getMessage());
        }
    }

    /** 稠密检索（框架 SimpleKnowledge 路径）。 */
    public List<DenseHit> searchDense(String query, int limit, Double scoreThreshold) {
        if (query == null || query.isBlank()) return List.of();
        RetrieveConfig.Builder config = RetrieveConfig.builder().limit(Math.max(1, limit));
        if (scoreThreshold != null) config.scoreThreshold(scoreThreshold);
        List<Document> documents = holder().knowledge()
                .retrieve(query, config.build())
                .block(Duration.ofMillis(Math.max(500, props.getRetrieve().getTimeoutMs())));
        List<DenseHit> hits = new ArrayList<>();
        if (documents != null) {
            for (Document document : documents) {
                String chunkId = document.getMetadata() == null ? null : document.getMetadata().getChunkId();
                if (chunkId == null || chunkId.isBlank()) continue;
                hits.add(new DenseHit(chunkId, document.getScore() == null ? 0.0 : document.getScore()));
            }
        }
        return hits;
    }

    public long count() {
        try {
            MilvusClientV2 client = clients.client();
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName())
                    .filter("")
                    .outputFields(List.of("count(*)"))
                    .build());
            if (resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) return 0;
            Object value = resp.getQueryResults().get(0).getEntity().get("count(*)");
            return value instanceof Number n ? n.longValue() : 0;
        } catch (Exception e) {
            log.debug("稠密集合计数失败：{}", e.getMessage());
            return 0;
        }
    }

    public boolean exists() {
        try {
            return Boolean.TRUE.equals(clients.client().hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName()).build()));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean available() {
        try {
            holder();
            bm25().ensureCollection();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String describe() {
        return props.getMilvus().getUri() + " / " + collectionName();
    }

    /** 当前集合使用的向量维度；集合未就绪时回落到配置/探测值（可能为 0）。 */
    public int dimension() {
        try {
            return holder().store.getDimensions();
        } catch (Exception e) {
            return props.getEmbed().getDimension() > 0
                    ? props.getEmbed().getDimension() : embeddingService.getDimension();
        }
    }

    public String getLastError() {
        return lastError;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
