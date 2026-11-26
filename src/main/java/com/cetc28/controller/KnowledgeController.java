package com.cetc28.controller;

import com.cetc28.rag.KnowledgeBaseService;
import com.cetc28.rag.KnowledgeEditService;
import com.cetc28.rag.KnowledgeRetriever;
import com.cetc28.rag.KnowledgeStorage;
import com.cetc28.rag.RagProperties;
import com.cetc28.rag.RagVerifyService;
import com.cetc28.rag.RetrievalResult;
import com.cetc28.rag.milvus.MilvusRagStore;
import com.cetc28.service.DocumentTextExtractor;
import com.cetc28.service.EmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private RagVerifyService verifyService;

    @Autowired
    private KnowledgeEditService editService;

    @Autowired
    private KnowledgeStorage storage;

    @Autowired
    private RagProperties props;

    @Autowired
    private MilvusRagStore store;

    @Autowired
    private EmbeddingService embeddingService;

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        KnowledgeBaseService.UploadResult result = knowledgeBaseService.uploadFile(file, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.success());
        resp.put("message", result.message());
        resp.put("chunkCount", result.chunkCount());
        return resp;
    }

    @GetMapping("/files")
    public List<KnowledgeBaseService.FileMeta> listFiles() {
        return knowledgeBaseService.listUploadedFiles();
    }

    @GetMapping("/content")
    public List<KnowledgeBaseService.ChunkEntry> getFileContent(@RequestParam String filename) {
        return knowledgeBaseService.getFileChunks(filename);
    }

    @GetMapping("/ftsearch")
    public List<KnowledgeBaseService.SearchResultEntry> fullTextSearch(
            @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return knowledgeBaseService.searchContent(q, limit);
    }

    /**
     * 统一检索入口：hybrid（默认，BM25 + 向量 RRF）/ bm25 / vector。
     *
     * <p>返回结构里带 score、来源文件、标题路径与行号 —— 这是"可验证"的基础：
     * 前端/评测脚本能据此核对模型引用的片段是否真的存在。
     *
     * @param minScore 可选分数下限（0~1，融合后的归一化分数）
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "6") int topK,
                                      @RequestParam(defaultValue = "hybrid") String mode,
                                      @RequestParam(required = false) Double minScore) {
        KnowledgeRetriever.Mode parsed = switch (mode == null ? "" : mode.toLowerCase()) {
            case "bm25", "text", "fulltext" -> KnowledgeRetriever.Mode.BM25;
            case "vector", "semantic" -> KnowledgeRetriever.Mode.VECTOR;
            default -> KnowledgeRetriever.Mode.HYBRID;
        };
        RetrievalResult result = retriever.retrieve(q, parsed, topK, minScore);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query", q);
        resp.put("mode", parsed.name().toLowerCase());
        resp.put("tookMs", result.tookMs());
        resp.put("degraded", result.degraded());
        if (result.degradedReason() != null) resp.put("degradedReason", result.degradedReason());
        resp.put("bm25Count", result.bm25Count());
        resp.put("vectorCount", result.vectorCount());
        List<Map<String, Object>> hits = new ArrayList<>();
        for (RetrievalResult.Hit hit : result.hits()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("chunkId", hit.chunkId());
            h.put("filename", hit.filename());
            h.put("headingPath", hit.headingPath());
            h.put("location", hit.location());
            h.put("lineStart", hit.lineStart());
            h.put("lineEnd", hit.lineEnd());
            h.put("score", hit.score());
            h.put("matchedBy", hit.mode());
            h.put("content", hit.content());
            hits.add(h);
        }
        resp.put("hits", hits);
        return resp;
    }

    /** 索引与向量状态：判断"知识库到底有没有东西可检索"不用再猜 */
    @GetMapping("/stats")
    public KnowledgeBaseService.KnowledgeStats stats() {
        return knowledgeBaseService.stats();
    }

    /** 语义检索（保留旧路径，内部走混合检索的向量路，返回结构与 /search 一致） */
    @GetMapping("/semantic")
    public Map<String, Object> semanticSearch(@RequestParam String q,
                                              @RequestParam(defaultValue = "5") int topK) {
        return search(q, topK, "vector", null);
    }

    /**
     * 回答可验证：把答案拆句，逐句检查是否有知识库证据支撑。
     *
     * <p>chunkIds 传本轮引用过的块（前端从 sources 事件里拿）；不传则按 query 现场检索取证。
     */
    @PostMapping("/verify")
    public RagVerifyService.VerifyResult verify(@RequestBody VerifyRequest request) {
        return verifyService.verify(request.query(), request.answer(), request.chunkIds());
    }

    @DeleteMapping("/files/{filename:.+}")
    public Map<String, Object> deleteFile(@PathVariable String filename) {
        boolean ok = knowledgeBaseService.deleteFile(filename);
        return Map.of("success", ok, "message", ok ? "已删除" : "删除失败");
    }

    // ========== 知识库工作台：分段编辑 / 文档管理 ==========

    /** 分段分页列表（含已停用分段，按 index 排序）。 */
    @GetMapping("/files/{filename:.+}/chunks")
    public KnowledgeEditService.ChunkPage pageChunks(@PathVariable String filename,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     @RequestParam(required = false) String q) {
        return editService.page(filename, page, pageSize, q);
    }

    @GetMapping("/chunk")
    public KnowledgeEditService.ChunkDetail chunk(@RequestParam String chunkId) {
        return editService.detail(chunkId);
    }

    /** 下载/预览原文（历史文档可能没有原文，返回 404 + 提示）。 */
    @GetMapping("/files/{filename:.+}/original")
    public ResponseEntity<?> original(@PathVariable String filename) {
        KnowledgeBaseService.FileMeta meta = knowledgeBaseService.fileMetaOrNull(filename);
        if (meta == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "文档不存在"));
        }
        String ext = DocumentTextExtractor.extensionOf(filename);
        Optional<byte[]> bytes = storage.load(meta.docId(), ext);
        if (bytes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "该文档没有留存原文（历史数据），请重新上传"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes.get());
    }

    /** 检索与编辑相关的只读配置，供"设置"页展示。 */
    @GetMapping("/settings")
    public KnowledgeSettings settings() {
        return new KnowledgeSettings(
                "milvus",
                store.collectionName(),
                props.getMilvus().getBm25Collection() + "_" + props.getMilvus().getCollectionVersion(),
                store.dimension(),
                embeddingService.describeTarget(),
                embeddingService.isEnabled(),
                props.getRerank().isEnabled(),
                props.getQueryExpansion().isEnabled(),
                props.getChunk().getTargetChars(),
                props.getChunk().getParentMaxChars(),
                props.getEdit().getMaxReembedChunks(),
                props.getEdit().getMaxPageSize(),
                props.getEdit().getMaxChunkChars(),
                storage.dir(),
                props.getRetrieve().getDenseWeight(),
                props.getRetrieve().getBm25Weight(),
                props.getRetrieve().getRrfK(),
                props.getRetrieve().getTopK(),
                props.getRetrieve().getCandidateLimit(),
                props.getRetrieve().getMinVectorSimilarity());
    }

    @PostMapping("/chunk/update")
    public KnowledgeEditService.ChunkDetail updateChunk(@RequestBody ChunkUpdateRequest request) {
        return editService.updateChunk(request.chunkId(), request.content());
    }

    @PostMapping("/chunk/enabled")
    public Map<String, Object> setChunkEnabled(@RequestBody ChunkEnabledRequest request) {
        boolean enabled = request.enabled() == null || request.enabled();
        editService.setChunkEnabled(request.chunkId(), enabled);
        return Map.of("success", true, "enabled", enabled);
    }

    @PostMapping("/files/{filename:.+}/rename")
    public KnowledgeEditService.RenameResult rename(@PathVariable String filename,
                                                    @RequestBody RenameRequest request) {
        return editService.rename(filename, request.filename());
    }

    @PostMapping("/files/{filename:.+}/reparse")
    public KnowledgeEditService.ChunkPage reparse(@PathVariable String filename) {
        return editService.reparse(filename);
    }

    @PostMapping("/files/{filename:.+}/chunks/reorder")
    public KnowledgeEditService.ChunkPage reorderChunks(@PathVariable String filename,
                                                        @RequestBody ChunkOrderRequest request) {
        return editService.reorderChunks(filename, request.chunkIds());
    }

    @PostMapping("/files/order")
    public List<KnowledgeBaseService.FileMeta> reorderFiles(@RequestBody FileOrderRequest request) {
        return editService.reorderFiles(request.filenames());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleEditError(RuntimeException error) {
        String message = error.getMessage() == null ? "操作失败" : error.getMessage();
        HttpStatus status = error instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST
                : message.contains("不可用") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    public record VerifyRequest(String query, String answer, List<String> chunkIds) {}

    public record ChunkUpdateRequest(String chunkId, String content) {}

    public record ChunkEnabledRequest(String chunkId, Boolean enabled) {}

    public record RenameRequest(String filename) {}

    public record ChunkOrderRequest(List<String> chunkIds) {}

    public record FileOrderRequest(List<String> filenames) {}

    public record KnowledgeSettings(String provider, String denseCollection, String bm25Collection,
                                    int dimension, String embeddingTarget, boolean embeddingEnabled,
                                    boolean rerankEnabled, boolean queryExpansionEnabled,
                                    int chunkTargetChars, int chunkParentChars,
                                    int maxReembedChunks, int maxPageSize, int maxChunkChars,
                                    String storageDir, double denseWeight, double bm25Weight,
                                    int rrfK, int topK, int candidateLimit, double minVectorSimilarity) {
    }
}
