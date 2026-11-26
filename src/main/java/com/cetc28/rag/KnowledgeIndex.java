package com.cetc28.rag;

import com.cetc28.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库索引（RediSearch v2 schema：全文 + HNSW 向量）。
 *
 * <p>职责：
 * <ul>
 *   <li>建索引（含向量字段）与版本管理，旧 schema 自动重建；</li>
 *   <li>写入/删除文本块（chunk）；</li>
 *   <li>BM25 与 KNN 两路检索的底层查询；</li>
 *   <li>启动后台迁移：把逐行切块的旧数据规范成 docId/chunkIndex 结构并补向量。</li>
 * </ul>
 *
 * <p>设计要点：索引如果建不出向量字段（embedding 未配置/探测失败），仍然建成纯文本索引，
 * 检索退化为 BM25；后续 embedding 恢复可用时按 {@code hard 30s} 节流自动升级索引并补向量。
 *
 * <p>注意：知识库已迁移到 Milvus（{@code com.cetc28.rag.milvus}），本类仅作为 Redis 时代的参考实现
 * 与回归对照保留，不再注册为 Spring 组件。
 */
public class KnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);

    public static final String INDEX_NAME = "idx_knowledge";
    public static final String PREFIX = "doc:knowledge:";
    public static final String VERSION_KEY = "knowledge:index:version";
    public static final String DIM_KEY = "knowledge:index:dim";
    public static final int SCHEMA_VERSION = 2;
    public static final String FIELD_VECTOR = "vector";
    public static final String FIELD_SCORE = "vector_score";

    private static final String[] CHUNK_FIELDS = {
            "content", "filename", "docId", "chunkIndex", "headingPath",
            "lineStart", "lineEnd", "uploadTime", "fileSize", "contentHash"
    };

    private static final long ENSURE_INTERVAL_MS = 30_000L;

    private final UnifiedJedis jedis;
    private final RagProperties props;
    private final EmbeddingService embeddingService;

    private final Object ensureLock = new Object();
    private final AtomicBoolean migrationScheduled = new AtomicBoolean(false);
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-index-migrator");
        t.setDaemon(true);
        return t;
    });

    private volatile long lastEnsureAt = 0L;
    private volatile int dimension = -1;
    private volatile boolean indexExists = false;

    public KnowledgeIndex(UnifiedJedis jedis, RagProperties props, EmbeddingService embeddingService) {
        this.jedis = jedis;
        this.props = props;
        this.embeddingService = embeddingService;
    }

    public record ChunkHit(String key, String content, String filename, String docId, int chunkIndex,
                           String headingPath, int lineStart, int lineEnd, double score) {
        public String citationLocation() {
            StringBuilder sb = new StringBuilder();
            if (headingPath != null && !headingPath.isBlank()) sb.append(headingPath);
            if (lineStart > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("第 ").append(lineStart);
                if (lineEnd > lineStart) sb.append('-').append(lineEnd);
                sb.append(" 行");
            }
            return sb.toString();
        }
    }

    public record IndexStats(boolean exists, int schemaVersion, long numDocs, boolean vectorReady,
                             int dimension, List<String> fields) {}

    // ==================== 索引生命周期 ====================

    /** 保证索引存在且 schema 与当前 embedding 维度匹配（节流，可重复调用） */
    public void ensureIndex() {
        long now = System.currentTimeMillis();
        if (now - lastEnsureAt < ENSURE_INTERVAL_MS && indexExists) return;
        synchronized (ensureLock) {
            now = System.currentTimeMillis();
            if (now - lastEnsureAt < ENSURE_INTERVAL_MS && indexExists) return;
            lastEnsureAt = now;
            try {
                int dim = resolveDimension();
                boolean exists = indexExists();
                Integer storedDim = intOrNull(str(jedis.get(DIM_KEY)));

                if (!exists) {
                    createIndex(dim);
                    jedis.set(VERSION_KEY, String.valueOf(SCHEMA_VERSION));
                    jedis.set(DIM_KEY, String.valueOf(dim));
                    log.info("知识库索引已创建（schema v{}，向量维度 {}）", SCHEMA_VERSION, dim > 0 ? dim : "无");
                    return;
                }
                if (dim > 0 && (storedDim == null || storedDim != dim)) {
                    // embedding 在启动后才可用，或维度变了：重建索引并补向量
                    log.info("知识库索引需要升级为向量模式：storedDim={}, currentDim={}", storedDim, dim);
                    jedis.ftDropIndex(INDEX_NAME);
                    createIndex(dim);
                    jedis.set(VERSION_KEY, String.valueOf(SCHEMA_VERSION));
                    jedis.set(DIM_KEY, String.valueOf(dim));
                    scheduleBackfill();
                }
            } catch (Exception e) {
                indexExists = false;
                log.error("知识库索引初始化失败：{}（检索将退化为不可用，对话不受影响）", e.getMessage());
            }
        }
    }

    private int resolveDimension() {
        int configured = props.getEmbed().getDimension();
        if (configured > 0) return configured;
        int detected = embeddingService.getDimension();
        if (detected > 0) return detected;
        if (embeddingService.isEnabled()) {
            float[] probe = embeddingService.embed("维度探测");
            if (probe != null && probe.length > 0) return probe.length;
        }
        return 0;
    }

    private boolean indexExists() throws Exception {
        try {
            Map<String, Object> info = jedis.ftInfo(INDEX_NAME);
            indexExists = info != null && !info.isEmpty();
        } catch (Exception e) {
            indexExists = false;
        }
        return indexExists;
    }

    private void createIndex(int dim) {
        List<SchemaField> fields = new ArrayList<>();
        fields.add(TextField.of("content").weight(1.0));
        fields.add(TextField.of("headingPath").weight(0.5));
        fields.add(TagField.of("filename"));
        fields.add(TagField.of("docId"));
        fields.add(NumericField.of("chunkIndex"));
        fields.add(NumericField.of("uploadTime"));
        fields.add(NumericField.of("fileSize"));
        if (dim > 0) {
            fields.add(VectorField.builder()
                    .fieldName(FIELD_VECTOR)
                    .algorithm(VectorField.VectorAlgorithm.HNSW)
                    .addAttribute("TYPE", "FLOAT32")
                    .addAttribute("DIM", dim)
                    .addAttribute("DISTANCE_METRIC", "COSINE")
                    .build());
        }
        dimension = dim;
        jedis.ftCreate(INDEX_NAME,
                FTCreateParams.createParams()
                        .on(IndexDataType.HASH)
                        .addPrefix(PREFIX)
                        .language("chinese"),
                fields);
    }

    public boolean vectorReady() {
        try {
            Integer dim = intOrNull(str(jedis.get(DIM_KEY)));
            return dim != null && dim > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public int dimension() {
        if (dimension > 0) return dimension;
        Integer dim = intOrNull(str(jedis.get(DIM_KEY)));
        return dim == null ? 0 : dim;
    }

    // ==================== 写入 ====================

    public static String docIdOf(String filename) {
        String hash = DocumentChunker.sha256(filename == null ? "" : filename);
        return hash.substring(0, 16);
    }

    public static String chunkKey(String docId, int chunkIndex) {
        return PREFIX + docId + ":" + chunkIndex;
    }

    /**
     * 写入一个文本块；向量为空时只写文本（全文检索仍可用）。
     */
    public void storeChunk(String key, String content, String filename, String docId, int chunkIndex,
                           String headingPath, int lineStart, int lineEnd, long uploadTime,
                           long fileSize, String contentHash, float[] vector) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("content", content == null ? "" : content);
        fields.put("filename", filename == null ? "" : filename);
        fields.put("docId", docId == null ? "" : docId);
        fields.put("chunkIndex", String.valueOf(chunkIndex));
        fields.put("headingPath", headingPath == null ? "" : headingPath);
        fields.put("lineStart", String.valueOf(lineStart));
        fields.put("lineEnd", String.valueOf(lineEnd));
        fields.put("uploadTime", String.valueOf(uploadTime));
        fields.put("fileSize", String.valueOf(fileSize));
        fields.put("contentHash", contentHash == null ? "" : contentHash);
        jedis.hset(key, fields);
        if (vector != null && vector.length > 0) {
            jedis.hset(key.getBytes(StandardCharsets.UTF_8),
                    Map.of(FIELD_VECTOR.getBytes(StandardCharsets.UTF_8), toBytes(vector)));
        }
    }

    public void storeVector(String key, float[] vector) {
        if (vector == null || vector.length == 0) return;
        jedis.hset(key.getBytes(StandardCharsets.UTF_8),
                Map.of(FIELD_VECTOR.getBytes(StandardCharsets.UTF_8), toBytes(vector)));
    }

    public int deleteByDocId(String docId) {
        return deleteByTag("docId", docId);
    }

    /** 批量删除指定块（上传回滚、覆盖旧块时使用） */
    public int deleteKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        long removed = jedis.del(keys.toArray(new String[0]));
        return (int) removed;
    }

    public List<String> keysByDocId(String docId) {
        return tagSearchKeys("docId", docId);
    }

    public List<String> keysByFilename(String filename) {
        return tagSearchKeys("filename", filename);
    }

    private int deleteByTag(String field, String value) {
        List<String> keys = tagSearchKeys(field, value);
        if (keys.isEmpty()) return 0;
        long removed = jedis.del(keys.toArray(new String[0]));
        return (int) removed;
    }

    private List<String> tagSearchKeys(String field, String value) {
        List<String> keys = new ArrayList<>();
        try {
            Query query = new Query("@" + field + ":{" + RedisSearchEscaper.tagValue(value) + "}")
                    .returnFields("content")
                    .limit(0, 10_000);
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            for (Document doc : result.getDocuments()) keys.add(doc.getId());
        } catch (Exception e) {
            log.warn("按 {} 查询文本块失败：{}", field, e.getMessage());
        }
        return keys;
    }

    // ==================== 读取 ====================

    public ChunkHit getChunk(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            List<String> values = jedis.hmget(key, CHUNK_FIELDS);
            if (values == null || values.size() < CHUNK_FIELDS.length || values.get(0) == null) return null;
            return new ChunkHit(key, values.get(0), values.get(1), values.get(2),
                    parseInt(values.get(3), 0), values.get(4),
                    parseInt(values.get(5), 0), parseInt(values.get(6), 0), 0.0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出某个文件的全部块（按 chunkIndex 升序），供"查看文件内容"用 */
    public List<ChunkHit> listByFilename(String filename) {
        List<ChunkHit> hits = new ArrayList<>();
        if (filename == null || filename.isBlank()) return hits;
        try {
            Query query = new Query("@filename:{" + RedisSearchEscaper.tagValue(filename) + "}")
                    .returnFields(CHUNK_FIELDS)
                    .limit(0, 10_000);
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            for (Document doc : result.getDocuments()) hits.add(toHit(doc, 0.0));
            hits.sort(java.util.Comparator.comparingInt(ChunkHit::chunkIndex));
        } catch (Exception e) {
            log.warn("列出文件块失败（{}）：{}", filename, e.getMessage());
        }
        return hits;
    }

    /** 列出全部块（元数据自愈、运维排查用） */
    public List<ChunkHit> listAll(int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        try {
            Query query = new Query("*").returnFields(CHUNK_FIELDS).limit(0, Math.max(1, limit));
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            for (Document doc : result.getDocuments()) hits.add(toHit(doc, 0.0));
        } catch (Exception e) {
            log.warn("列出全部文本块失败：{}", e.getMessage());
        }
        return hits;
    }

    /**
     * 元数据自愈：按文件名聚合块数、最早上传时间、文件大小。
     *
     * @return filename → {chunkCount, earliestUploadTime, fileSize}
     */
    public Map<String, long[]> aggregateFiles(int maxScan) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        List<String> keys = scanKeys(PREFIX + "*");
        int scanned = 0;
        for (String key : keys) {
            if (++scanned > maxScan) break;
            try {
                List<String> v = jedis.hmget(key, "filename", "uploadTime", "fileSize");
                if (v == null || v.get(0) == null || v.get(0).isBlank()) continue;
                long[] entry = agg.computeIfAbsent(v.get(0), k -> new long[]{0L, Long.MAX_VALUE, 0L});
                entry[0]++;
                long uploadTime = parseLong(v.get(1), 0);
                if (uploadTime > 0 && uploadTime < entry[1]) entry[1] = uploadTime;
                long size = parseLong(v.get(2), 0);
                if (size > entry[2]) entry[2] = size;
            } catch (Exception e) {
                log.debug("聚合块 {} 失败：{}", key, e.getMessage());
            }
        }
        return agg;
    }

    public long countVectors(int maxScan) {
        try {
            List<String> keys = scanKeys(PREFIX + "*");
            if (keys.size() > maxScan) return -1;
            long count = 0;
            for (String key : keys) {
                if (jedis.hexists(key, FIELD_VECTOR)) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== 检索 ====================

    public List<ChunkHit> searchText(String queryText, int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        if (queryText == null || queryText.isBlank() || limit <= 0) return hits;
        try {
            hits = doSearchText(RedisSearchEscaper.textQuery(queryText), limit);
            // 中文长句在 RediSearch 里会被当成一个整词（查询侧分词器不认识中文），
            // 此时退化成"二元组 OR"再查一次：索引侧按中文分词存的是词，二元组能命中其中一部分。
            if (hits.isEmpty() && !queryText.contains(" ") && queryText.length() >= 4) {
                String broadened = broadenCjk(queryText);
                if (broadened != null) {
                    hits = doSearchText(broadened, limit);
                }
            }
        } catch (Exception e) {
            log.warn("全文检索失败：{}", e.getMessage());
        }
        return hits;
    }

    private List<ChunkHit> doSearchText(String escapedQuery, int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        Query query = new Query(escapedQuery)
                .returnFields(CHUNK_FIELDS)
                .setWithScores()
                .limit(0, limit);
        SearchResult result = jedis.ftSearch(INDEX_NAME, query);
        for (Document doc : result.getDocuments()) {
            hits.add(toHit(doc, doc.getScore()));
        }
        return hits;
    }

    /**
     * 把一段没有空格的中文查询拆成二元组的 OR 查询，最多取 12 个，避免查询过长。
     * 返回 null 表示不适合拆分（太少或全是 ASCII）。
     */
    static String broadenCjk(String query) {
        List<String> grams = new ArrayList<>();
        int cjk = 0;
        for (int i = 0; i < query.length(); i++) {
            if (isCjk(query.charAt(i))) cjk++;
        }
        if (cjk < 3) return null;
        for (int i = 0; i + 2 <= query.length() && grams.size() < 12; i++) {
            String gram = query.substring(i, i + 2);
            if (isCjk(gram.charAt(0)) || isCjk(gram.charAt(1))) {
                grams.add(RedisSearchEscaper.textQuery(gram));
            }
        }
        if (grams.size() < 2) return null;
        return String.join(" | ", grams);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    public List<ChunkHit> searchInFile(String filename, String queryText, int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        try {
            Query query = new Query("@" + "filename" + ":{" + RedisSearchEscaper.tagValue(filename) + "} "
                    + RedisSearchEscaper.textQuery(queryText))
                    .returnFields(CHUNK_FIELDS)
                    .setWithScores()
                    .limit(0, limit);
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            for (Document doc : result.getDocuments()) hits.add(toHit(doc, doc.getScore()));
        } catch (Exception e) {
            log.warn("文件内检索失败（{}）：{}", filename, e.getMessage());
        }
        return hits;
    }

    public List<ChunkHit> searchVector(float[] vector, int limit) {
        List<ChunkHit> hits = new ArrayList<>();
        if (vector == null || vector.length == 0 || limit <= 0) return hits;
        if (!vectorReady()) return hits;
        try {
            Query query = new Query("*=>[KNN $k @" + FIELD_VECTOR + " $BLOB AS " + FIELD_SCORE + "]")
                    .addParam("k", limit)
                    .addParam("BLOB", toBytes(vector))
                    .returnFields(concat(CHUNK_FIELDS, FIELD_SCORE))
                    .dialect(2)
                    .limit(0, limit);
            SearchResult result = jedis.ftSearch(INDEX_NAME, query);
            double minSimilarity = props.getRetrieve().getMinVectorSimilarity();
            for (Document doc : result.getDocuments()) {
                double distance = parseDouble(doc.getString(FIELD_SCORE), 2.0);
                // COSINE 距离 ∈ [0,2]，转成相似度；KNN 结果的顺序由 Jedis 原样返回，
                // 不保证按距离排序（实测 RediSearch 会按哈希顺序返回），必须自己排。
                double similarity = Math.max(0.0, 1.0 - distance);
                if (similarity < minSimilarity) continue;
                hits.add(toHit(doc, similarity));
            }
            hits.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
        } catch (Exception e) {
            log.warn("向量检索失败（降级为全文检索）：{}", e.getMessage());
        }
        return hits;
    }

    private ChunkHit toHit(Document doc, double score) {
        return new ChunkHit(
                doc.getId(),
                doc.getString("content"),
                doc.getString("filename"),
                doc.getString("docId"),
                parseInfoInt(doc.getString("chunkIndex")),
                doc.getString("headingPath"),
                parseInfoInt(doc.getString("lineStart")),
                parseInfoInt(doc.getString("lineEnd")),
                score);
    }

    private static int parseInfoInt(String value) {
        return parseInt(value, 0);
    }

    // ==================== 统计 ====================

    public IndexStats stats() {
        boolean exists;
        long numDocs = 0;
        List<String> fields = new ArrayList<>();
        try {
            Map<String, Object> info = jedis.ftInfo(INDEX_NAME);
            exists = info != null && !info.isEmpty();
            if (exists) {
                Object docs = info.get("num_docs");
                numDocs = docs instanceof Number ? ((Number) docs).longValue() : parseLong(String.valueOf(docs), 0);
                Object attrs = info.get("attributes");
                if (attrs instanceof List<?> list) {
                    for (Object item : list) {
                        String identifier = attributeIdentifier(item);
                        if (identifier != null && !identifier.isBlank() && !fields.contains(identifier)) {
                            fields.add(identifier);
                        }
                    }
                }
            }
        } catch (Exception e) {
            exists = false;
        }
        indexExists = exists;
        return new IndexStats(exists, SCHEMA_VERSION, numDocs, vectorReady(), dimension(), fields);
    }

    /** FT.INFO 的一条 attribute 可能是 map，也可能是扁平的 [key, value, key, value...] 数组 */
    private static String attributeIdentifier(Object item) {
        if (item instanceof Map<?, ?> map) {
            return firstString(map, "identifier", "attribute");
        }
        if (item instanceof List<?> pair) {
            for (int i = 0; i + 1 < pair.size(); i += 2) {
                String key = asString(pair.get(i));
                if ("identifier".equals(key) || "attribute".equals(key)) {
                    return asString(pair.get(i + 1));
                }
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    /** FT.INFO 的嵌套 map 键值可能是 String 也可能是 byte[]，两种都认 */
    private static String firstString(Map<?, ?> map, String... keys) {
        for (Object rawKey : map.keySet()) {
            String key = asString(rawKey);
            for (String want : keys) {
                if (want.equals(key)) {
                    return asString(map.get(rawKey));
                }
            }
        }
        return null;
    }

    // ==================== 启动迁移 / 补向量 ====================

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!props.isEnabled()) {
            log.info("RAG 已关闭（rag.enabled=false），跳过知识库索引初始化");
            return;
        }
        background.submit(() -> {
            try {
                ensureIndex();
                migrateLegacyChunks();
                backfillMissingVectors();
            } catch (Exception e) {
                log.warn("知识库后台迁移失败（不影响对话）：{}", e.getMessage());
            }
        });
    }

    /** embedding 后补：索引重建后给存量块补向量 */
    private void scheduleBackfill() {
        if (!migrationScheduled.compareAndSet(false, true)) return;
        background.submit(() -> {
            try {
                backfillMissingVectors();
            } catch (Exception e) {
                log.warn("补向量失败：{}", e.getMessage());
            } finally {
                migrationScheduled.set(false);
            }
        });
    }

    /**
     * 迁移旧版逐行切块数据：key = doc:knowledge:{uuid}，无 docId/chunkIndex。
     * 规范为 doc:knowledge:{docId}:{lineNumber}，补 headingPath=""/contentHash，删除旧 key。
     */
    void migrateLegacyChunks() {
        List<String> keys = scanKeys(PREFIX + "*");
        int migrated = 0;
        for (String key : keys) {
            try {
                if (isNewLayout(key)) continue;
                List<String> v = jedis.hmget(key, "content", "filename", "lineNumber",
                        "uploadTime", "fileSize");
                if (v == null || v.get(0) == null || v.get(1) == null) continue;
                String content = v.get(0);
                String filename = v.get(1);
                int lineNo = parseInt(v.get(2), 0);
                long uploadTime = parseLong(v.get(3), System.currentTimeMillis());
                long fileSize = parseLong(v.get(4), 0);
                String docId = docIdOf(filename);
                String newKey = chunkKey(docId, lineNo);
                if (!jedis.exists(newKey)) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("content", content);
                    fields.put("filename", filename);
                    fields.put("docId", docId);
                    fields.put("chunkIndex", String.valueOf(lineNo));
                    fields.put("headingPath", "");
                    fields.put("lineStart", String.valueOf(lineNo));
                    fields.put("lineEnd", String.valueOf(lineNo));
                    fields.put("uploadTime", String.valueOf(uploadTime));
                    fields.put("fileSize", String.valueOf(fileSize));
                    fields.put("contentHash", DocumentChunker.sha256(content));
                    jedis.hset(newKey, fields);
                }
                jedis.del(key);
                migrated++;
            } catch (Exception e) {
                log.debug("迁移块 {} 失败：{}", key, e.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("知识库旧结构迁移完成：{} 个文本块已规范为 docId/chunkIndex 结构", migrated);
        }
    }

    private boolean isNewLayout(String key) {
        String rest = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        // 新 key 形如 {docId}:{index}，旧 key 是单个 uuid
        return rest.chars().filter(c -> c == ':').count() >= 1;
    }

    /** 给缺少 vector 字段的块补向量（分批调用 embedding） */
    void backfillMissingVectors() {
        if (!vectorReady()) {
            if (resolveDimension() > 0) {
                ensureIndex();
                if (vectorReady()) return; // 重建索引后 num_docs 保留，下面继续补向量
            }
            log.info("向量字段不可用，跳过补向量（检索退化为纯全文）");
            return;
        }
        List<String> keys = scanKeys(PREFIX + "*");
        List<String> pending = new ArrayList<>();
        List<String> pendingKeys = new ArrayList<>();
        for (String key : keys) {
            if (!jedis.hexists(key, FIELD_VECTOR)) {
                List<String> v = jedis.hmget(key, "content");
                if (v != null && v.get(0) != null && !v.get(0).isBlank()) {
                    pendingKeys.add(key);
                    pending.add(v.get(0));
                }
            }
        }
        if (pending.isEmpty()) return;

        int batchSize = Math.max(1, props.getEmbed().getBatchSize());
        int embedded = 0;
        for (int i = 0; i < pending.size(); i += batchSize) {
            int end = Math.min(pending.size(), i + batchSize);
            List<String> batch = pending.subList(i, end);
            List<float[]> vectors = embeddingService.embedBatch(batch);
            for (int j = 0; j < batch.size(); j++) {
                float[] vec = j < vectors.size() ? vectors.get(j) : null;
                if (vec != null) {
                    storeVector(pendingKeys.get(i + j), vec);
                    embedded++;
                }
            }
        }
        log.info("知识库补向量完成：{}/{} 个文本块已生成向量", embedded, pending.size());
    }

    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        do {
            var result = jedis.scan(cursor, new redis.clients.jedis.params.ScanParams().match(pattern).count(500));
            cursor = result.getCursor();
            keys.addAll(result.getResult());
        } while (!"0".equals(cursor));
        return keys;
    }

    // ==================== 工具方法 ====================

    private static String[] concat(String[] fields, String extra) {
        String[] all = Arrays.copyOf(fields, fields.length + 1);
        all[fields.length] = extra;
        return all;
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) buffer.putFloat(v);
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length % 4 != 0) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int parseInt(String value, int fallback) {
        Integer parsed = intOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
