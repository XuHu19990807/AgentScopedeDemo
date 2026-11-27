package com.cetc28.rag;

import com.cetc28.rag.milvus.MilvusBm25Store;
import com.cetc28.rag.milvus.MilvusRagStore;
import com.cetc28.rag.milvus.StoredChunk;
import com.cetc28.service.DocumentTextExtractor;
import com.cetc28.service.EmbeddingService;
import com.cetc28.service.FileCapabilityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库服务（Milvus 版）。
 *
 * <p>职责：
 * <ul>
 *   <li>上传：提取文本 → 父子块切分 → 批量向量化 → 双集合写入（失败回滚）；</li>
 *   <li>文件清单：Redis hash 保存展示用元数据（大小/时间/块数），唯一事实来源仍是 Milvus；</li>
 *   <li>查询：文件内检索走 BM25 集合（带 filename 过滤），语义检索走稠密集合。</li>
 * </ul>
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    public static final String FILE_METADATA_KEY = "knowledge:files";
    public static final String CHUNK_ID_PREFIX = "doc:knowledge:";

    private final DocumentTextExtractor extractor;
    private final DocumentChunker chunker;
    private final MilvusRagStore store;
    private final MilvusBm25Store bm25Store;
    private final RagProperties props;
    private final EmbeddingService embeddingService;
    private final ObjectMapper mapper;
    private final FileCapabilityChecker capabilityChecker;
    private final KnowledgeStorage storage;
    private final KnowledgeChunkState chunkState;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public KnowledgeBaseService(DocumentTextExtractor extractor,
                                DocumentChunker chunker,
                                MilvusRagStore store,
                                MilvusBm25Store bm25Store,
                                RagProperties props,
                                EmbeddingService embeddingService,
                                ObjectMapper mapper,
                                FileCapabilityChecker capabilityChecker,
                                KnowledgeStorage storage,
                                KnowledgeChunkState chunkState) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.store = store;
        this.bm25Store = bm25Store;
        this.props = props;
        this.embeddingService = embeddingService;
        this.mapper = mapper;
        this.capabilityChecker = capabilityChecker;
        this.storage = storage;
        this.chunkState = chunkState;
    }

    public record UploadResult(boolean success, String message, int chunkCount) {
    }

    /**
     * 启动自检：块硬上限 + 标题前缀不能超过 embedding 的 token 预算，否则块尾会被 embedding 服务截断，
     * 造成"块里有、检索不到"。这里只告警，不阻断启动（不同 embedding 模型预算不同）。
     */
    @PostConstruct
    void warnChunkBudget() {
        int maxChars = props.getChunk().getMaxChars();
        int maxTokens = props.getEmbed().getDimension() >= 0 ? embeddingMaxTokens() : 0;
        int budget = maxTokens - 60;
        if (maxTokens > 0 && maxChars + 120 > budget) {
            log.warn("切块硬上限 {} 字符 + 标题/文件名前缀 120 超过 embedding 预算 {} token，"
                            + "块尾可能被 embedding 截断；建议调小 rag.chunk.max-chars 或换更大上下文的 embedding 模型",
                    maxChars, budget);
        }
    }

    private int embeddingMaxTokens() {
        try {
            return embeddingService.maxTokens();
        } catch (Exception e) {
            return 0;
        }
    }

    public record FileMeta(String filename, long fileSize, String uploadTime, int chunkCount,
                           String handledBy, String docId, double sortOrder, boolean hasOriginal,
                           int disabledCount, long updatedAt, String domain) {
        /** 兼容旧调用点（无编辑态字段）。 */
        public FileMeta(String filename, long fileSize, String uploadTime, int chunkCount,
                        String handledBy, String docId) {
            this(filename, fileSize, uploadTime, chunkCount, handledBy, docId, 0, false, 0, 0, null);
        }

        public FileMeta(String filename, long fileSize, String uploadTime, int chunkCount,
                        String handledBy, String docId, double sortOrder, boolean hasOriginal,
                        int disabledCount, long updatedAt) {
            this(filename, fileSize, uploadTime, chunkCount, handledBy, docId, sortOrder, hasOriginal,
                    disabledCount, updatedAt, null);
        }

        /** 空域归一到 default，便于筛选与展示。 */
        public String effectiveDomain() {
            return domain == null || domain.isBlank() ? StoredChunk.DEFAULT_DOMAIN : domain.trim();
        }

        public String formattedSize() {
            if (fileSize < 1024) return fileSize + " B";
            if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
            return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public FileMeta withSortOrder(double order) {
            return new FileMeta(filename, fileSize, uploadTime, chunkCount, handledBy, docId,
                    order, hasOriginal, disabledCount, updatedAt, domain);
        }

        public FileMeta withChunkCount(int count) {
            return new FileMeta(filename, fileSize, uploadTime, count, handledBy, docId,
                    sortOrder, hasOriginal, disabledCount, System.currentTimeMillis(), domain);
        }

        public FileMeta withDisabledCount(int count) {
            return new FileMeta(filename, fileSize, uploadTime, chunkCount, handledBy, docId,
                    sortOrder, hasOriginal, count, System.currentTimeMillis(), domain);
        }

        public FileMeta withOriginal(boolean original) {
            return new FileMeta(filename, fileSize, uploadTime, chunkCount, handledBy, docId,
                    sortOrder, original, disabledCount, updatedAt, domain);
        }

        public FileMeta withDomain(String newDomain) {
            return new FileMeta(filename, fileSize, uploadTime, chunkCount, handledBy, docId,
                    sortOrder, hasOriginal, disabledCount, updatedAt, newDomain);
        }
    }

    public record ChunkEntry(String id, String content, int lineNumber, String headingPath) {
    }

    public record SearchResultEntry(String content, String filename, int lineNumber, String headingPath,
                                    double score) {
    }

    public record KnowledgeStats(boolean indexExists, long chunks, long vectors, int dimension,
                                 boolean vectorReady, int schemaVersion, List<String> indexFields,
                                 int files, boolean embeddingEnabled, String embeddingTarget,
                                 String lastEmbeddingError, int embeddingFailures,
                                 int disabledChunks, int storedOriginals) {
    }

    // ========== 上传 ==========

    public UploadResult uploadFile(MultipartFile file, Map<String, String> extraMetadata) {
        return uploadFile(file, extraMetadata, null, null);
    }

    public UploadResult uploadFile(MultipartFile file, Map<String, String> extraMetadata, String filenameOverride) {
        return uploadFile(file, extraMetadata, filenameOverride, null);
    }

    /**
     * @param domain 知识域；为空时取 extraMetadata 的 domain，再缺省为 {@code default}
     */
    public UploadResult uploadFile(MultipartFile file, Map<String, String> extraMetadata,
                                   String filenameOverride, String domain) {
        if (file == null || file.isEmpty()) return new UploadResult(false, "文件为空", 0);
        String filename = filenameOverride != null && !filenameOverride.isBlank()
                ? filenameOverride : file.getOriginalFilename();
        if (filename == null || filename.isBlank()) return new UploadResult(false, "文件名为空", 0);
        String ext = DocumentTextExtractor.extensionOf(filename);
        if (!extractor.supports(ext)) {
            return new UploadResult(false, extractor.unsupportedMessage(), 0);
        }
        if (!embeddingService.isEnabled()) {
            return new UploadResult(false, "embedding 未启用，无法写入 Milvus 向量库（检查 agentscope.embedding 配置）", 0);
        }
        String docId = docIdOf(filename);
        String domainKey = normalizeDomain(domain, extraMetadata);
        try {
            String text = extractor.extract(file, ext);
            if (text == null || text.isBlank()) {
                return new UploadResult(false, "未能从文件中提取到文本内容", 0);
            }
            List<DocumentChunker.ParentChunk> chunks = chunker.chunkWithParents(
                    text,
                    props.getChunk().getTargetChars(),
                    props.getChunk().getOverlapChars(),
                    props.getChunk().getMaxChunksPerFile(),
                    props.getChunk().getParentMaxChars(),
                    props.getChunk().getMaxChars());
            List<StoredChunk> stored = new ArrayList<>();
            List<String> embedTexts = new ArrayList<>();
            int skipped = 0;
            for (DocumentChunker.ParentChunk parentChunk : chunks) {
                DocumentChunker.Chunk chunk = parentChunk.chunk();
                String content = chunk.content();
                if (content == null || content.isBlank()) continue;
                boolean headingOnly = chunk.headingPath() != null && content.trim().equals(chunk.headingPath().trim());
                if (!headingOnly && content.trim().length() < Math.max(2, props.getChunk().getMinChars())) {
                    skipped++;
                    continue;
                }
                int index = stored.size();
                StoredChunk storedChunk = new StoredChunk(
                        chunkKey(docId, index),
                        docId,
                        index,
                        content,
                        parentChunk.parentText(),
                        filename,
                        chunk.headingPath(),
                        chunk.lineStart(),
                        chunk.lineEnd(),
                        chunk.contentHash(),
                        domainKey);
                stored.add(storedChunk);
                embedTexts.add(storedChunk.embeddingText());
            }
            if (stored.isEmpty()) {
                return new UploadResult(false, "文件切分后没有可索引的内容", 0);
            }
            List<float[]> vectors = embedWithRetry(embedTexts);
            List<StoredChunk> toWrite = new ArrayList<>(stored.size());
            List<float[]> vectorToWrite = new ArrayList<>(stored.size());
            for (int i = 0; i < stored.size(); i++) {
                float[] vector = vectors.size() > i ? vectors.get(i) : null;
                if (vector == null || vector.length == 0) continue;
                toWrite.add(stored.get(i));
                vectorToWrite.add(vector);
            }
            if (toWrite.isEmpty()) {
                return new UploadResult(false, "全部文本块向量化失败：" + embeddingService.getLastError(), 0);
            }
            // 幂等：同名文件先删旧块，避免重复行；旧墓碑也一并清理（序号已重置）
            store.deleteDoc(docId);
            chunkState.removeByDoc(docId);
            store.upsert(toWrite, vectorToWrite);
            storage.save(docId, ext, file.getBytes());
            FileMeta existing = fileMetaOrNull(filename);
            double sortOrder = existing != null && existing.sortOrder() > 0
                    ? existing.sortOrder() : System.currentTimeMillis();
            FileMeta meta = new FileMeta(filename, file.getSize(), Instant.now().toString(), toWrite.size(),
                    capabilityChecker == null ? null : capabilityChecker.findCapableProcessor(filename).orElse(null),
                    docId, sortOrder, true, 0, System.currentTimeMillis(), domainKey);
            saveMetadata(meta);
            String message = "上传成功：解析 " + toWrite.size() + " 个块"
                    + (skipped > 0 ? "（跳过 " + skipped + " 个过短片段）" : "")
                    + (toWrite.size() < stored.size() ? "，其中 " + (stored.size() - toWrite.size()) + " 个块向量化失败已跳过" : "");
            log.info("知识库文件入库：{} → {} 个块（docId={}）", filename, toWrite.size(), docId);
            return new UploadResult(true, message, toWrite.size());
        } catch (Exception e) {
            log.error("知识库文件入库失败：{}", filename, e);
            return new UploadResult(false, "入库失败：" + e.getMessage(), 0);
        }
    }

    private List<float[]> embedWithRetry(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(embeddingService.embedBatch(texts));
        int retries = Math.max(0, props.getEmbed().getRetries());
        for (int round = 0; round < retries; round++) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = i < vectors.size() ? vectors.get(i) : null;
                if (vector == null || vector.length == 0) missing.add(i);
            }
            if (missing.isEmpty()) break;
            log.info("有 {} 个块向量化失败，第 {} 次重试", missing.size(), round + 1);
            for (int index : missing) {
                float[] vector = embeddingService.embed(texts.get(index));
                if (vector != null && vector.length > 0 && index < vectors.size()) vectors.set(index, vector);
            }
        }
        return vectors;
    }

    // ========== 文件清单 ==========

    public List<FileMeta> listUploadedFiles() {
        List<FileMeta> metas = readMetadata();
        if (!metas.isEmpty()) return metas;
        return rebuildMetadataFromChunks();
    }

    /** 供编辑服务复用：全量元数据（已按展示顺序排序）。 */
    public List<FileMeta> allFileMetas() {
        return readMetadata();
    }

    public FileMeta fileMetaOrNull(String filename) {
        if (filename == null) return null;
        for (FileMeta meta : readMetadata()) {
            if (filename.equals(meta.filename())) return meta;
        }
        return null;
    }

    public List<FileMeta> readMetadata() {
        if (redisTemplate == null) return List.of();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(FILE_METADATA_KEY);
            List<FileMeta> metas = new ArrayList<>();
            for (Object value : entries.values()) {
                if (value == null) continue;
                FileMeta meta;
                try {
                    meta = mapper.readValue(String.valueOf(value), FileMeta.class);
                } catch (Exception e) {
                    log.debug("解析文件元数据失败：{}", e.getMessage());
                    continue;
                }
                // 兼容 Redis 时代的旧元数据：没有 docId 时按文件名重算，Milvus 里也没有对应块则清理
                if (meta.docId() == null || meta.docId().isBlank()) {
                    String docId = docIdOf(meta.filename());
                    List<com.cetc28.rag.ChunkView> existing = bm25Store.listByDoc(docId, 2000);
                    if (existing.isEmpty()) {
                        log.info("清理历史知识库元数据（Milvus 中无对应块）：{}", meta.filename());
                        removeMetadata(meta.filename());
                        continue;
                    }
                    meta = new FileMeta(meta.filename(), meta.fileSize(), meta.uploadTime(),
                            existing.size(), meta.handledBy(), docId);
                    saveMetadata(meta);
                }
                metas.add(meta);
            }
            metas.sort((a, b) -> {
                if (a.sortOrder() > 0 || b.sortOrder() > 0) {
                    int byOrder = Double.compare(b.sortOrder(), a.sortOrder());
                    if (byOrder != 0) return byOrder;
                }
                int byUpdated = Long.compare(b.updatedAt(), a.updatedAt());
                if (byUpdated != 0) return byUpdated;
                return Comparator.comparing(FileMeta::filename).compare(a, b);
            });
            return metas;
        } catch (Exception e) {
            log.warn("读取知识库文件清单失败（Redis 不可用）：{}", e.getMessage());
            return List.of();
        }
    }

    public void saveMetadata(FileMeta meta) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForHash().put(FILE_METADATA_KEY, meta.filename(), mapper.writeValueAsString(meta));
        } catch (Exception e) {
            log.warn("保存知识库文件元数据失败（不影响检索）：{}", e.getMessage());
        }
    }

    public void removeMetadata(String filename) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForHash().delete(FILE_METADATA_KEY, filename);
        } catch (Exception e) {
            log.warn("删除知识库文件元数据失败：{}", e.getMessage());
        }
    }

    /** Redis 丢失时从 Milvus 反推文件清单，保证管理页不空。 */
    private List<FileMeta> rebuildMetadataFromChunks() {
        List<com.cetc28.rag.ChunkView> chunks;
        try {
            chunks = bm25Store.listAll(10_000);
        } catch (Exception e) {
            return List.of();
        }
        Map<String, List<com.cetc28.rag.ChunkView>> byFile = new LinkedHashMap<>();
        for (com.cetc28.rag.ChunkView chunk : chunks) {
            byFile.computeIfAbsent(chunk.filename() == null ? "" : chunk.filename(), k -> new ArrayList<>()).add(chunk);
        }
        List<FileMeta> metas = new ArrayList<>();
        for (Map.Entry<String, List<com.cetc28.rag.ChunkView>> entry : byFile.entrySet()) {
            String filename = entry.getKey();
            FileMeta meta = new FileMeta(filename, 0, null, entry.getValue().size(), null, docIdOf(filename));
            metas.add(meta);
            saveMetadata(meta);
        }
        metas.sort(Comparator.comparing(FileMeta::filename));
        return metas;
    }

    // ========== 查询 ==========

    public List<ChunkEntry> getFileChunks(String filename) {
        List<com.cetc28.rag.ChunkView> chunks = bm25Store.listByDoc(docIdOf(filename), 2000);
        List<ChunkEntry> entries = new ArrayList<>(chunks.size());
        for (com.cetc28.rag.ChunkView chunk : chunks) {
            entries.add(new ChunkEntry(chunk.chunkId(), chunk.content(), chunk.lineStart(), chunk.headingPath()));
        }
        return entries;
    }

    public List<SearchResultEntry> searchInFile(String filename, String query, int limit) {
        List<com.cetc28.rag.ChunkView> hits = bm25Store.search(query, Math.max(1, limit),
                MilvusBm25Store.FILENAME + " == " + MilvusBm25Store.quote(filename));
        List<SearchResultEntry> entries = new ArrayList<>(hits.size());
        for (com.cetc28.rag.ChunkView hit : hits) {
            entries.add(new SearchResultEntry(hit.content(), hit.filename(), hit.lineStart(),
                    hit.headingPath(), hit.score()));
        }
        return entries;
    }

    public List<SearchResultEntry> similaritySearch(String query, int topK) {
        int limit = topK > 0 ? topK : props.getRetrieve().getTopK();
        List<MilvusRagStore.DenseHit> hits = store.searchDense(query, limit,
                props.getRetrieve().getMinVectorSimilarity());
        List<String> ids = new ArrayList<>(hits.size());
        for (MilvusRagStore.DenseHit hit : hits) ids.add(hit.chunkId());
        Map<String, com.cetc28.rag.ChunkView> views = new LinkedHashMap<>();
        for (com.cetc28.rag.ChunkView view : bm25Store.getByIds(ids)) views.put(view.chunkId(), view);
        List<SearchResultEntry> entries = new ArrayList<>(hits.size());
        for (MilvusRagStore.DenseHit hit : hits) {
            com.cetc28.rag.ChunkView view = views.get(hit.chunkId());
            if (view == null) continue;
            entries.add(new SearchResultEntry(view.content(), view.filename(), view.lineStart(),
                    view.headingPath(), hit.score()));
        }
        return entries;
    }

    public List<SearchResultEntry> searchContent(String query, int limit) {
        List<com.cetc28.rag.ChunkView> hits = bm25Store.search(query, Math.max(1, limit));
        List<SearchResultEntry> entries = new ArrayList<>(hits.size());
        for (com.cetc28.rag.ChunkView hit : hits) {
            entries.add(new SearchResultEntry(hit.content(), hit.filename(), hit.lineStart(),
                    hit.headingPath(), hit.score()));
        }
        return entries;
    }

    public KnowledgeStats stats() {
        long chunks = safeCount(() -> bm25Store.count());
        long vectors = safeCount(store::count);
        int dimension = store.dimension() > 0 ? store.dimension() : embeddingService.getDimension();
        boolean vectorReady = store.available();
        List<String> fields = List.of("id", "doc_id", "chunk_id", "content", "parent_text", "filename",
                "heading_path", "line_start", "line_end", "chunk_index", "sparse");
        List<FileMeta> metas = listUploadedFiles();
        int originals = 0;
        for (FileMeta meta : metas) {
            if (meta.hasOriginal()) originals++;
        }
        return new KnowledgeStats(
                bm25Store.exists() || store.exists(),
                chunks,
                vectors,
                dimension,
                vectorReady,
                2,
                fields,
                metas.size(),
                embeddingService.isEnabled(),
                embeddingService.describeTarget(),
                embeddingService.getLastError(),
                (int) embeddingService.getFailureCount(),
                chunkState.countAll(),
                originals);
    }

    private static long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== 删除 ==========

    public boolean deleteFile(String filename) {
        if (filename == null || filename.isBlank()) return false;
        String docId = docIdOf(filename);
        store.deleteDoc(docId);
        chunkState.removeByDoc(docId);
        FileMeta meta = fileMetaOrNull(filename);
        String ext = DocumentTextExtractor.extensionOf(filename);
        storage.delete(docId, ext);
        if (meta != null && meta.hasOriginal()) {
            // 扩展名可能因历史数据不一致而不同，清理时两种都试一次
            storage.delete(docId, ".bin");
        }
        removeMetadata(filename);
        log.info("知识库文件已删除：{}（docId={}）", filename, docId);
        return true;
    }

    public String detectFileType(String filename) {
        String ext = DocumentTextExtractor.extensionOf(filename);
        return switch (ext) {
            case ".pdf" -> "PDF 文档（" + ext + "）";
            case ".docx" -> "Word 文档（" + ext + "）";
            case ".doc" -> "Word 97-2003 文档（" + ext + "）";
            case ".xlsx", ".xls" -> "Excel 表格（" + ext + "）";
            case ".pptx", ".ppt" -> "PowerPoint 演示文稿（" + ext + "）";
            case ".md", ".markdown" -> "Markdown 文档（" + ext + "）";
            case ".csv", ".tsv" -> "CSV/TSV 数据表格（" + ext + "）";
            case ".json", ".jsonl" -> "JSON 数据（" + ext + "）";
            case ".html", ".htm" -> "HTML 网页（" + ext + "）";
            case ".xml" -> "XML 数据（" + ext + "）";
            case ".yaml", ".yml" -> "YAML 配置（" + ext + "）";
            case ".rtf" -> "RTF 富文本文档（" + ext + "）";
            case ".odt" -> "OpenDocument 文本文档（" + ext + "）";
            case ".ods" -> "OpenDocument 表格（" + ext + "）";
            case ".odp" -> "OpenDocument 演示文稿（" + ext + "）";
            case ".epub" -> "EPUB 电子书（" + ext + "）";
            case ".eml", ".msg" -> "邮件文件（" + ext + "）";
            case ".wps", ".et", ".dps" -> "WPS Office 文档（" + ext + "）";
            default -> "文本文件（" + (ext.isEmpty() ? "无扩展名" : ext) + "）";
        };
    }

    // ========== 工具 ==========

    /** 域归一：显式参数优先，其次 extraMetadata.domain，最后 default；长度截断到 64。 */
    public static String normalizeDomain(String domain, Map<String, String> extraMetadata) {
        String value = domain;
        if ((value == null || value.isBlank()) && extraMetadata != null) {
            value = extraMetadata.get("domain");
        }
        if (value == null || value.isBlank()) return StoredChunk.DEFAULT_DOMAIN;
        String trimmed = value.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    public static String normalizeDomain(String domain) {
        return normalizeDomain(domain, null);
    }

    /** 文档所属域（读元数据；未知文档回 default）。 */
    public String domainOf(String filename) {
        FileMeta meta = fileMetaOrNull(filename);
        return meta == null ? StoredChunk.DEFAULT_DOMAIN : meta.effectiveDomain();
    }

    public static String docIdOf(String filename) {
        return DocumentChunker.sha256(filename == null ? "" : filename).substring(0, 16);
    }

    public static String chunkKey(String docId, int chunkIndex) {
        return CHUNK_ID_PREFIX + docId + ":" + chunkIndex;
    }

    public static String docIdFromChunkId(String chunkId) {
        if (chunkId == null) return "";
        String withoutPrefix = chunkId.startsWith(CHUNK_ID_PREFIX) ? chunkId.substring(CHUNK_ID_PREFIX.length()) : chunkId;
        int colon = withoutPrefix.lastIndexOf(':');
        return colon > 0 ? withoutPrefix.substring(0, colon) : withoutPrefix;
    }
}
