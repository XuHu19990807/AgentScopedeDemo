package com.cetc28.rag.milvus;

import com.cetc28.rag.ChunkView;
import com.cetc28.rag.RagProperties;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 词法索引（Milvus 原生全文检索）。
 *
 * <p>与 AgentScope 的 {@code MilvusStore} 分集合存放：框架的集合 schema 固定为
 * 稠密向量 + 元数据，无法追加 sparse 字段；而 BM25 需要 Text 字段 + FunctionType.BM25
 * 自动生成稀疏向量 + SPARSE_INVERTED_INDEX。跨集合用 chunkId 关联。
 *
 * <p>为什么不用 Milvus 的 hybridSearch 一次完成：融合权重需要按业务调（rrf-k、dense/bm25
 * 权重、文档级去重、rerank），放在应用层更容易演进，也方便双路降级。
 */
@Component
public class MilvusBm25Store {

    private static final Logger log = LoggerFactory.getLogger(MilvusBm25Store.class);

    public static final String F_ID = "id";
    public static final String F_DOC_ID = "doc_id";
    public static final String F_CHUNK_ID = "chunk_id";
    public static final String FILENAME = "filename";
    public static final String F_HEADING = "heading_path";
    public static final String F_CONTENT = "content";
    public static final String F_PARENT = "parent_text";
    public static final String F_LINE_START = "line_start";
    public static final String F_LINE_END = "line_end";
    public static final String F_CHUNK_INDEX = "chunk_index";
    public static final String F_DOMAIN = "domain";
    public static final String F_SPARSE = "sparse";
    public static final String FUNCTION_BM25 = "bm25_fn";

    private static final List<String> OUT_FIELDS = List.of(
            F_ID, F_DOC_ID, F_CHUNK_ID, FILENAME, F_HEADING, F_CONTENT, F_PARENT,
            F_LINE_START, F_LINE_END, F_CHUNK_INDEX, F_DOMAIN);

    private final MilvusClientProvider clients;
    private final RagProperties props;

    private volatile boolean ensured;
    private volatile String lastError;

    public MilvusBm25Store(MilvusClientProvider clients, RagProperties props) {
        this.clients = clients;
        this.props = props;
    }

    /** 建集合（幂等）。失败抛异常由调用方降级。 */
    public synchronized void ensureCollection() {
        if (ensured) return;
        MilvusClientV2 client = clients.client();
        String name = clients.bm25Collection();
        HasCollectionReq hasReq = HasCollectionReq.builder().collectionName(name).build();
        if (!Boolean.TRUE.equals(client.hasCollection(hasReq))) {
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_ID).dataType(DataType.VarChar).maxLength(64)
                    .isPrimaryKey(true).autoID(false).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_DOC_ID).dataType(DataType.VarChar).maxLength(128).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_CHUNK_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FILENAME).dataType(DataType.VarChar).maxLength(512).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_HEADING).dataType(DataType.VarChar).maxLength(1024).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_LINE_START).dataType(DataType.Int32).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_LINE_END).dataType(DataType.Int32).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_CHUNK_INDEX).dataType(DataType.Int32).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_DOMAIN).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_PARENT).dataType(DataType.VarChar).maxLength(8192).build());
            // 正文单独开启分词（中文用于 BM25 词法召回；写入时不需要传 sparse，由 Function 生成）
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_CONTENT).dataType(DataType.VarChar).maxLength(8192)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", analyzerType()))
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(F_SPARSE).dataType(DataType.SparseFloatVector).build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name(FUNCTION_BM25)
                    .description("全文检索：content -> sparse")
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(F_CONTENT))
                    .outputFieldNames(List.of(F_SPARSE))
                    .build());

            IndexParam sparseIndex = IndexParam.builder()
                    .fieldName(F_SPARSE)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build();

            CreateCollectionReq request = CreateCollectionReq.builder()
                    .collectionName(name)
                    .description("RAG 词法索引（BM25）")
                    .collectionSchema(schema)
                    .indexParams(List.of(sparseIndex))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build();
            client.createCollection(request);
            log.info("已创建 Milvus BM25 集合 {}（analyzer={}）", name, analyzerType());
        }
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
        clients.ensureStrongConsistency(name);
        ensured = true;
        lastError = null;
    }

    private String analyzerType() {
        String analyzer = props.getMilvus().getAnalyzer();
        return analyzer == null || analyzer.isBlank() ? "chinese" : analyzer;
    }

    /** 写入一批块，返回写入条数。 */
    public int insert(List<StoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return 0;
        ensureCollection();
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (StoredChunk chunk : chunks) {
            JsonObject row = new JsonObject();
            row.addProperty(F_ID, chunk.chunkId());
            row.addProperty(F_DOC_ID, nullSafe(chunk.docId()));
            row.addProperty(F_CHUNK_ID, chunk.chunkId());
            row.addProperty(FILENAME, nullSafe(chunk.filename()));
            row.addProperty(F_HEADING, nullSafe(chunk.headingPath()));
            row.addProperty(F_LINE_START, chunk.lineStart());
            row.addProperty(F_LINE_END, chunk.lineEnd());
            row.addProperty(F_CHUNK_INDEX, chunk.chunkIndex());
            row.addProperty(F_DOMAIN, chunk.effectiveDomain());
            row.addProperty(F_PARENT, nullSafe(chunk.parentText()));
            row.addProperty(F_CONTENT, nullSafe(chunk.content()));
            rows.add(row);
        }
        var resp = clients.client().insert(InsertReq.builder()
                .collectionName(clients.bm25Collection())
                .data(rows)
                .build());
        return (int) resp.getInsertCnt();
    }

    /** BM25 检索（全库）。 */
    public List<ChunkView> search(String query, int limit) {
        return search(query, limit, null);
    }

    /** BM25 检索，可带 Milvus 布尔过滤表达式（如按文件名过滤）。 */
    public List<ChunkView> search(String query, int limit, String filter) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        ensureCollection();
        var builder = SearchReq.builder()
                .collectionName(clients.bm25Collection())
                .annsField(F_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .data(List.of(new EmbeddedText(query)))
                .limit((long) limit)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .outputFields(OUT_FIELDS);
        if (filter != null && !filter.isBlank()) builder.filter(filter);
        SearchResp resp = clients.client().search(builder.build());
        List<ChunkView> views = new ArrayList<>();
        for (SearchResp.SearchResult result : firstGroup(resp)) {
            ChunkView view = toView(result.getEntity(), result.getScore() == null ? 0.0 : result.getScore());
            if (view != null) views.add(view);
        }
        return views;
    }

    private List<SearchResp.SearchResult> firstGroup(SearchResp resp) {
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results == null || results.isEmpty() || results.get(0) == null) return List.of();
        return results.get(0);
    }

    public ChunkView getById(String chunkId) {
        List<ChunkView> views = getByIds(List.of(chunkId));
        return views.isEmpty() ? null : views.get(0);
    }

    /** 按 chunkId 批量回读（分批，避免过滤表达式过长）。 */
    public List<ChunkView> getByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        ensureCollection();
        Map<String, ChunkView> found = new LinkedHashMap<>();
        int batchSize = 100;
        for (int start = 0; start < chunkIds.size(); start += batchSize) {
            List<String> batch = chunkIds.subList(start, Math.min(chunkIds.size(), start + batchSize));
            StringBuilder filter = new StringBuilder(F_ID).append(" in [");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) filter.append(',');
                filter.append(quote(batch.get(i)));
            }
            filter.append(']');
            QueryReq request = QueryReq.builder()
                    .collectionName(clients.bm25Collection())
                    .filter(filter.toString())
                    .outputFields(OUT_FIELDS)
                    .limit((long) batch.size())
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build();
            QueryResp resp = clients.client().query(request);
            if (resp.getQueryResults() == null) continue;
            for (QueryResp.QueryResult result : resp.getQueryResults()) {
                ChunkView view = toView(result.getEntity(), 0.0);
                if (view != null && view.chunkId() != null) found.put(view.chunkId(), view);
            }
        }
        List<ChunkView> ordered = new ArrayList<>(found.size());
        for (String id : chunkIds) {
            ChunkView view = found.get(id);
            if (view != null) ordered.add(view);
        }
        return ordered;
    }

    /** 某文档的全部块（按块序号排序）。 */
    public List<ChunkView> listByDoc(String docId, int limit) {
        if (docId == null || docId.isBlank()) return List.of();
        ensureCollection();
        QueryReq request = QueryReq.builder()
                .collectionName(clients.bm25Collection())
                .filter(F_DOC_ID + " == " + quote(docId))
                .outputFields(OUT_FIELDS)
                .limit((long) Math.max(1, limit))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
        return queryViews(request);
    }

    /** 全库块（用于从存储反推文件清单）。 */
    public List<ChunkView> listAll(int limit) {
        ensureCollection();
        QueryReq request = QueryReq.builder()
                .collectionName(clients.bm25Collection())
                .filter(F_ID + " != ''")
                .outputFields(OUT_FIELDS)
                .limit((long) Math.max(1, limit))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build();
        return queryViews(request);
    }

    private List<ChunkView> queryViews(QueryReq request) {
        QueryResp resp = clients.client().query(request);
        List<ChunkView> views = new ArrayList<>();
        if (resp.getQueryResults() == null) return views;
        for (QueryResp.QueryResult result : resp.getQueryResults()) {
            ChunkView view = toView(result.getEntity(), 0.0);
            if (view != null) views.add(view);
        }
        views.sort((a, b) -> Integer.compare(a.chunkIndex(), b.chunkIndex()));
        return views;
    }

    /** 删除某文档的全部块（返回删除条数）。 */
    public int deleteDoc(String docId) {
        if (docId == null || docId.isBlank()) return 0;
        ensureCollection();
        var resp = clients.client().delete(DeleteReq.builder()
                .collectionName(clients.bm25Collection())
                .filter(F_DOC_ID + " == " + quote(docId))
                .build());
        return (int) resp.getDeleteCnt();
    }

    /** 按 chunkId 批量删除（分段停用/编辑用）。 */
    public int deleteByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return 0;
        ensureCollection();
        int removed = 0;
        int batchSize = 100;
        for (int start = 0; start < chunkIds.size(); start += batchSize) {
            List<String> batch = chunkIds.subList(start, Math.min(chunkIds.size(), start + batchSize));
            StringBuilder filter = new StringBuilder(F_ID).append(" in [");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) filter.append(',');
                filter.append(quote(batch.get(i)));
            }
            filter.append(']');
            var resp = clients.client().delete(DeleteReq.builder()
                    .collectionName(clients.bm25Collection())
                    .filter(filter.toString())
                    .build());
            removed += (int) resp.getDeleteCnt();
        }
        return removed;
    }

    /** 覆盖写入若干块（先删后插，保证 parent_text 等字段同步更新）。 */
    public int replace(List<StoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return 0;
        List<String> ids = new ArrayList<>(chunks.size());
        for (StoredChunk chunk : chunks) ids.add(chunk.chunkId());
        deleteByIds(ids);
        return insert(chunks);
    }

    public long count() {
        ensureCollection();
        QueryReq request = QueryReq.builder()
                .collectionName(clients.bm25Collection())
                .filter("")
                .outputFields(List.of("count(*)"))
                .build();
        QueryResp resp = clients.client().query(request);
        if (resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) return 0L;
        Object value = resp.getQueryResults().get(0).getEntity().get("count(*)");
        if (value instanceof Number number) return number.longValue();
        return 0L;
    }

    public boolean exists() {
        try {
            return Boolean.TRUE.equals(clients.client().hasCollection(
                    HasCollectionReq.builder().collectionName(clients.bm25Collection()).build()));
        } catch (Exception e) {
            return false;
        }
    }

    public String getLastError() {
        return lastError;
    }

    private ChunkView toView(Map<String, Object> entity, double score) {
        if (entity == null) return null;
        String chunkId = str(entity.get(F_CHUNK_ID));
        if (chunkId == null || chunkId.isBlank()) chunkId = str(entity.get(F_ID));
        if (chunkId == null || chunkId.isBlank()) return null;
        return new ChunkView(
                chunkId,
                str(entity.get(F_DOC_ID)),
                intOf(entity.get(F_CHUNK_INDEX)),
                str(entity.get(F_CONTENT)),
                str(entity.get(F_PARENT)),
                str(entity.get(FILENAME)),
                str(entity.get(F_HEADING)),
                intOf(entity.get(F_LINE_START)),
                intOf(entity.get(F_LINE_END)),
                score,
                str(entity.get(F_DOMAIN)));
    }

    /** 域过滤表达式；domain 为空时返回 null（不过滤）。 */
    public static String domainFilter(String domain) {
        if (domain == null || domain.isBlank()) return null;
        return F_DOMAIN + " == " + quote(domain.trim());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Milvus 过滤表达式里的字符串字面量：转义反斜杠与单双引号。 */
    public static String quote(String value) {
        if (value == null) return "''";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
        return "\"" + escaped + "\"";
    }
}
