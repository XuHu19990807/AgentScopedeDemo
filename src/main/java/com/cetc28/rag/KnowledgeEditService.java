package com.cetc28.rag;

import com.cetc28.rag.milvus.MilvusBm25Store;
import com.cetc28.rag.milvus.MilvusRagStore;
import com.cetc28.rag.milvus.StoredChunk;
import com.cetc28.service.DocumentTextExtractor;
import com.cetc28.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 知识库工作台的编辑服务：分段编辑/启用停用/拖拽重排、文档重命名/重新解析/排序。
 *
 * <p>设计要点：
 * <ul>
 *   <li>逻辑分段 = 各文档内按 index 排序的全部片段（含已停用片段，停用信息在 Redis 墓碑里）；</li>
 *   <li>单段操作（编辑/停用/启用）只重嵌该段，其余受影响分段仅更新 parent_text（parent 不参与向量化）；</li>
 *   <li>重排/重命名/重解析会改变 chunkId，必须整篇重建（受 {@code rag.edit.max-reembed-chunks} 限制）；</li>
 *   <li>所有写操作按 docId 加锁，避免并发互相覆盖。</li>
 * </ul>
 */
@Service
public class KnowledgeEditService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEditService.class);

    private static final int PREVIEW_CHARS = 120;

    private final KnowledgeBaseService knowledgeBaseService;
    private final MilvusRagStore store;
    private final MilvusBm25Store bm25Store;
    private final EmbeddingService embeddingService;
    private final RagProperties props;
    private final KnowledgeStorage storage;
    private final DocumentTextExtractor extractor;
    private final DocumentChunker chunker;
    private final KnowledgeChunkState chunkState;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public KnowledgeEditService(KnowledgeBaseService knowledgeBaseService,
                                MilvusRagStore store,
                                MilvusBm25Store bm25Store,
                                EmbeddingService embeddingService,
                                RagProperties props,
                                KnowledgeStorage storage,
                                DocumentTextExtractor extractor,
                                DocumentChunker chunker,
                                KnowledgeChunkState chunkState) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.store = store;
        this.bm25Store = bm25Store;
        this.embeddingService = embeddingService;
        this.props = props;
        this.storage = storage;
        this.extractor = extractor;
        this.chunker = chunker;
        this.chunkState = chunkState;
    }

    // ========== 视图对象 ==========

    public record ChunkItem(int index, String chunkId, String headingPath, int lineStart, int lineEnd,
                            int chars, String preview, boolean enabled) {
    }

    public record ChunkPage(String docId, String filename, int total, int enabledCount, int disabledCount,
                            int page, int pageSize, int pages, List<ChunkItem> items) {
    }

    public record ChunkDetail(String chunkId, String docId, String filename, int index, String headingPath,
                              int lineStart, int lineEnd, String content, String parentText,
                              boolean enabled, int chars) {
    }

    public record RenameResult(String oldFilename, String filename, String docId, int chunkCount,
                               int disabledCount) {
    }

    private record LogicalChunk(int index, String chunkId, String content, String parentText,
                                String headingPath, int lineStart, int lineEnd, boolean enabled) {
        LogicalChunk withContent(String value) {
            return new LogicalChunk(index, chunkId, value, parentText, headingPath, lineStart, lineEnd, enabled);
        }

        LogicalChunk withParent(String value) {
            return new LogicalChunk(index, chunkId, content, value, headingPath, lineStart, lineEnd, enabled);
        }

        LogicalChunk withIdentity(String newChunkId, int newIndex) {
            return new LogicalChunk(newIndex, newChunkId, content, parentText, headingPath,
                    lineStart, lineEnd, enabled);
        }
    }

    // ========== 查询 ==========

    public ChunkPage page(String filename, int page, int pageSize, String query) {
        KnowledgeBaseService.FileMeta meta = requireMeta(filename);
        int size = clampPageSize(pageSize);
        int current = Math.max(1, page);
        List<LogicalChunk> chunks = loadChunks(meta.docId());
        String needle = query == null ? "" : query.trim().toLowerCase();
        List<LogicalChunk> filtered = new ArrayList<>();
        for (LogicalChunk chunk : chunks) {
            if (needle.isEmpty() || matches(chunk, needle)) filtered.add(chunk);
        }
        int total = filtered.size();
        int pages = Math.max(1, (total + size - 1) / size);
        if (current > pages) current = pages;
        int from = (current - 1) * size;
        int to = Math.min(total, from + size);
        List<ChunkItem> items = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            LogicalChunk chunk = filtered.get(i);
            items.add(new ChunkItem(chunk.index(), chunk.chunkId(), chunk.headingPath(),
                    chunk.lineStart(), chunk.lineEnd(), chunk.content() == null ? 0 : chunk.content().length(),
                    preview(chunk.content()), chunk.enabled()));
        }
        int enabled = 0;
        for (LogicalChunk chunk : chunks) {
            if (chunk.enabled()) enabled++;
        }
        return new ChunkPage(meta.docId(), meta.filename(), total, enabled, chunks.size() - enabled,
                current, size, pages, items);
    }

    public ChunkDetail detail(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId 不能为空");
        String docId = KnowledgeBaseService.docIdFromChunkId(chunkId);
        String filename = filenameOf(docId);
        for (LogicalChunk chunk : loadChunks(docId)) {
            if (chunk.chunkId().equals(chunkId)) {
                return new ChunkDetail(chunk.chunkId(), docId, filename, chunk.index(), chunk.headingPath(),
                        chunk.lineStart(), chunk.lineEnd(), chunk.content(), chunk.parentText(), chunk.enabled(),
                        chunk.content() == null ? 0 : chunk.content().length());
            }
        }
        throw new IllegalArgumentException("分段不存在：" + chunkId);
    }

    // ========== 单段编辑 / 启停 ==========

    public ChunkDetail updateChunk(String chunkId, String content) {
        String docId = KnowledgeBaseService.docIdFromChunkId(chunkId);
        String filename = filenameOf(docId);
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("分段内容不能为空");
        if (value.length() > maxChunkChars()) {
            throw new IllegalArgumentException("分段内容超过上限 " + maxChunkChars() + " 字符");
        }
        return withLock(docId, () -> {
            List<LogicalChunk> chunks = loadChunks(docId);
            int index = indexOf(chunks, chunkId);
            LogicalChunk target = chunks.get(index);
            if (!target.enabled()) throw new IllegalStateException("该分段已停用，请先启用再编辑");
            List<LogicalChunk> updated = new ArrayList<>(chunks);
            updated.set(index, target.withContent(value));
            List<LogicalChunk> repacked = repackParents(updated);

            StoredChunk stored = toStored(docId, filename, repacked.get(index),
                    knowledgeBaseService.domainOf(filename));
            float[] vector = embedSingle(stored.embeddingText());
            store.deleteDenseChunks(List.of(chunkId));
            bm25Store.deleteByIds(List.of(chunkId));
            store.addDense(List.of(stored), List.of(vector));
            bm25Store.insert(List.of(stored));
            updateChangedParents(docId, filename, chunks, repacked);
            touchMeta(filename, enabledCount(repacked), repacked.size() - enabledCount(repacked));
            log.info("知识库分段已编辑：{} [{}]（{} 字符）", filename, chunkId, value.length());
            return detail(chunkId);
        });
    }

    public void setChunkEnabled(String chunkId, boolean enabled) {
        String docId = KnowledgeBaseService.docIdFromChunkId(chunkId);
        String filename = filenameOf(docId);
        withLock(docId, () -> {
            List<LogicalChunk> chunks = loadChunks(docId);
            int index = indexOf(chunks, chunkId);
            LogicalChunk target = chunks.get(index);
            List<LogicalChunk> updated = new ArrayList<>(chunks);
            if (enabled) {
                if (target.enabled()) return null;
                if (!chunkState.available()) throw new IllegalStateException("Redis 不可用，无法启用分段");
                KnowledgeChunkState.DisabledChunk tomb = chunkState.get(chunkId)
                        .orElseThrow(() -> new IllegalStateException("停用记录不存在，请重新解析该文档"));
                updated.set(index, new LogicalChunk(target.index(), chunkId, tomb.content(), target.parentText(),
                        tomb.headingPath(), tomb.lineStart(), tomb.lineEnd(), true));
                List<LogicalChunk> repacked = repackParents(updated);
                StoredChunk stored = toStored(docId, filename, repacked.get(index),
                        knowledgeBaseService.domainOf(filename));
                float[] vector = embedSingle(stored.embeddingText());
                store.addDense(List.of(stored), List.of(vector));
                bm25Store.insert(List.of(stored));
                chunkState.enable(chunkId);
                updateChangedParents(docId, filename, chunks, repacked);
                touchMeta(filename, enabledCount(repacked), repacked.size() - enabledCount(repacked));
                log.info("知识库分段已启用：{} [{}]", filename, chunkId);
            } else {
                if (!target.enabled()) return null;
                if (!chunkState.available()) throw new IllegalStateException("Redis 不可用，无法停用分段");
                List<LogicalChunk> removed = new ArrayList<>(chunks);
                removed.set(index, new LogicalChunk(target.index(), chunkId, target.content(), target.parentText(),
                        target.headingPath(), target.lineStart(), target.lineEnd(), false));
                List<LogicalChunk> repacked = repackParents(removed);
                store.deleteDenseChunks(List.of(chunkId));
                bm25Store.deleteByIds(List.of(chunkId));
                chunkState.disable(new KnowledgeChunkState.DisabledChunk(chunkId, docId, filename,
                        target.index(), target.content(), target.parentText(), target.headingPath(),
                        target.lineStart(), target.lineEnd(), System.currentTimeMillis()));
                updateChangedParents(docId, filename, chunks, repacked);
                touchMeta(filename, enabledCount(repacked), repacked.size() - enabledCount(repacked));
                log.info("知识库分段已停用：{} [{}]", filename, chunkId);
            }
            return null;
        });
    }

    // ========== 分段重排 ==========

    public ChunkPage reorderChunks(String filename, List<String> chunkIdsInOrder) {
        KnowledgeBaseService.FileMeta meta = requireMeta(filename);
        if (chunkIdsInOrder == null || chunkIdsInOrder.isEmpty()) {
            throw new IllegalArgumentException("缺少分段顺序");
        }
        return withLock(meta.docId(), () -> {
            List<LogicalChunk> chunks = loadChunks(meta.docId());
            Set<String> expected = new LinkedHashSet<>();
            for (LogicalChunk chunk : chunks) expected.add(chunk.chunkId());
            if (!expected.equals(new LinkedHashSet<>(chunkIdsInOrder))) {
                throw new IllegalStateException("分段顺序与最新数据不一致，请刷新后重试");
            }
            List<LogicalChunk> reordered = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunkIdsInOrder.size(); i++) {
                LogicalChunk chunk = find(chunks, chunkIdsInOrder.get(i));
                reordered.add(new LogicalChunk(i, KnowledgeBaseService.chunkKey(meta.docId(), i),
                        chunk.content(), chunk.parentText(), chunk.headingPath(), chunk.lineStart(),
                        chunk.lineEnd(), chunk.enabled()));
            }
            List<LogicalChunk> repacked = repackParents(reordered);
            rebuild(meta.docId(), meta.docId(), filename, repacked);
            log.info("知识库分段已重排：{}（{} 段）", filename, repacked.size());
            return page(filename, 1, clampPageSize(20), null);
        });
    }

    // ========== 文档重命名 / 重新解析 / 排序 ==========

    public RenameResult rename(String filename, String newFilename) {
        KnowledgeBaseService.FileMeta meta = requireMeta(filename);
        String target = sanitizeFilename(newFilename);
        if (target.equals(filename)) throw new IllegalArgumentException("新文件名与原文件名相同");
        if (knowledgeBaseService.fileMetaOrNull(target) != null) {
            throw new IllegalStateException("已存在同名文档：" + target);
        }
        String newDocId = KnowledgeBaseService.docIdOf(target);
        return withLock(meta.docId(), () -> {
            List<LogicalChunk> chunks = loadChunks(meta.docId());
            List<LogicalChunk> renamed = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                LogicalChunk chunk = chunks.get(i);
                renamed.add(new LogicalChunk(i, KnowledgeBaseService.chunkKey(newDocId, i), chunk.content(),
                        chunk.parentText(), chunk.headingPath(), chunk.lineStart(), chunk.lineEnd(),
                        chunk.enabled()));
            }
            String ext = DocumentTextExtractor.extensionOf(filename);
            rebuild(meta.docId(), newDocId, target, renamed);
            storage.copy(meta.docId(), newDocId, ext);
            knowledgeBaseService.removeMetadata(filename);
            List<KnowledgeChunkState.DisabledChunk> tombs = new ArrayList<>();
            int enabled = 0;
            for (LogicalChunk chunk : renamed) {
                if (chunk.enabled()) {
                    enabled++;
                } else {
                    tombs.add(new KnowledgeChunkState.DisabledChunk(chunk.chunkId(), newDocId, target,
                            chunk.index(), chunk.content(), chunk.parentText(), chunk.headingPath(),
                            chunk.lineStart(), chunk.lineEnd(), System.currentTimeMillis()));
                }
            }
            chunkState.replaceAll(newDocId, tombs);
            chunkState.removeByDoc(meta.docId());
            KnowledgeBaseService.FileMeta newMeta = new KnowledgeBaseService.FileMeta(target, meta.fileSize(),
                    meta.uploadTime(), enabled, meta.handledBy(), newDocId,
                    meta.sortOrder() > 0 ? meta.sortOrder() : System.currentTimeMillis(),
                    meta.hasOriginal() || storage.exists(newDocId, ext), tombs.size(), System.currentTimeMillis());
            knowledgeBaseService.saveMetadata(newMeta);
            log.info("知识库文档已重命名：{} → {}（docId {} → {}）", filename, target, meta.docId(), newDocId);
            return new RenameResult(filename, target, newDocId, enabled, tombs.size());
        });
    }

    public ChunkPage reparse(String filename) {
        KnowledgeBaseService.FileMeta meta = requireMeta(filename);
        String ext = DocumentTextExtractor.extensionOf(filename);
        byte[] bytes = storage.load(meta.docId(), ext)
                .orElseThrow(() -> new IllegalStateException("该文档没有留存原文，无法重新解析；请重新上传"));
        return withLock(meta.docId(), () -> {
            Path temp = null;
            try {
                temp = Files.createTempFile("rag-reparse-", ext.isEmpty() ? ".bin" : ext);
                Files.write(temp, bytes);
                String text = extractor.extract(temp, ext);
                if (text == null || text.isBlank()) throw new IllegalStateException("重新解析没有得到文本内容");
                List<DocumentChunker.ParentChunk> parents = chunker.chunkWithParents(text,
                        props.getChunk().getTargetChars(), props.getChunk().getOverlapChars(),
                        props.getChunk().getMaxChunksPerFile(), props.getChunk().getParentMaxChars(),
                        props.getChunk().getMaxChars());
                List<LogicalChunk> chunks = new ArrayList<>();
                int index = 0;
                for (DocumentChunker.ParentChunk parent : parents) {
                    DocumentChunker.Chunk chunk = parent.chunk();
                    String content = chunk.content();
                    if (content == null || content.isBlank()) continue;
                    boolean headingOnly = chunk.headingPath() != null
                            && content.trim().equals(chunk.headingPath().trim());
                    if (!headingOnly && content.trim().length() < Math.max(2, props.getChunk().getMinChars())) {
                        continue;
                    }
                    chunks.add(new LogicalChunk(index, KnowledgeBaseService.chunkKey(meta.docId(), index),
                            content, parent.parentText(), chunk.headingPath(), chunk.lineStart(),
                            chunk.lineEnd(), true));
                    index++;
                }
                if (chunks.isEmpty()) throw new IllegalStateException("重新解析后没有可索引的内容");
                rebuild(meta.docId(), meta.docId(), filename, chunks);
                chunkState.removeByDoc(meta.docId());
                knowledgeBaseService.saveMetadata(meta.withChunkCount(chunks.size()).withDisabledCount(0));
                log.info("知识库文档已重新解析：{}（{} 段）", filename, chunks.size());
                return page(filename, 1, clampPageSize(20), null);
            } catch (Exception e) {
                throw new IllegalStateException("重新解析失败：" + e.getMessage(), e);
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                        // 临时文件清理失败不影响主流程
                    }
                }
            }
        });
    }

    public List<KnowledgeBaseService.FileMeta> reorderFiles(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) throw new IllegalArgumentException("缺少文档顺序");
        List<KnowledgeBaseService.FileMeta> metas = knowledgeBaseService.readMetadata();
        Set<String> known = new LinkedHashSet<>();
        Map<String, KnowledgeBaseService.FileMeta> byName = new LinkedHashMap<>();
        for (KnowledgeBaseService.FileMeta meta : metas) {
            known.add(meta.filename());
            byName.put(meta.filename(), meta);
        }
        if (!known.equals(new LinkedHashSet<>(filenames))) {
            throw new IllegalStateException("文档顺序与最新清单不一致，请刷新后重试");
        }
        double order = filenames.size() * 1000.0;
        for (String filename : filenames) {
            KnowledgeBaseService.FileMeta meta = byName.get(filename);
            if (meta == null) continue;
            knowledgeBaseService.saveMetadata(meta.withSortOrder(order));
            order -= 1000.0;
        }
        return knowledgeBaseService.listUploadedFiles();
    }

    // ========== 内部：重建 / 持久化 ==========

    private void rebuild(String oldDocId, String newDocId, String filename, List<LogicalChunk> repacked) {
        int enabled = enabledCount(repacked);
        ensureCap(enabled);
        List<StoredChunk> stored = new ArrayList<>(enabled);
        List<String> texts = new ArrayList<>(enabled);
        String domain = knowledgeBaseService.domainOf(filename);
        for (LogicalChunk chunk : repacked) {
            if (!chunk.enabled()) continue;
            StoredChunk sc = toStored(newDocId, filename, chunk, domain);
            stored.add(sc);
            texts.add(sc.embeddingText());
        }
        List<float[]> vectors = embedAll(texts);
        store.deleteDoc(oldDocId);
        if (!oldDocId.equals(newDocId)) store.deleteDoc(newDocId);
        if (!stored.isEmpty()) {
            store.addDense(stored, vectors);
            bm25Store.insert(stored);
        }
    }

    private void updateChangedParents(String docId, String filename,
                                      List<LogicalChunk> before, List<LogicalChunk> after) {
        Map<String, String> previous = new LinkedHashMap<>();
        for (LogicalChunk chunk : before) previous.put(chunk.chunkId(), chunk.parentText());
        List<StoredChunk> changed = new ArrayList<>();
        String domain = knowledgeBaseService.domainOf(filename);
        for (LogicalChunk chunk : after) {
            if (!chunk.enabled()) continue;
            String old = previous.get(chunk.chunkId());
            if (old != null && !old.equals(chunk.parentText())) {
                changed.add(toStored(docId, filename, chunk, domain));
            }
        }
        if (!changed.isEmpty()) bm25Store.replace(changed);
    }

    private List<LogicalChunk> repackParents(List<LogicalChunk> chunks) {
        int parentLimit = Math.max(400, props.getChunk().getParentMaxChars());
        List<LogicalChunk> result = new ArrayList<>(chunks.size());
        int start = 0;
        while (start < chunks.size()) {
            String heading = chunks.get(start).headingPath() == null ? "" : chunks.get(start).headingPath();
            int end = start + 1;
            while (end < chunks.size()) {
                String next = chunks.get(end).headingPath() == null ? "" : chunks.get(end).headingPath();
                if (!next.equals(heading)) break;
                end++;
            }
            StringBuilder parent = new StringBuilder();
            for (int i = start; i < end; i++) {
                String content = chunks.get(i).content();
                if (content == null || content.isBlank()) continue;
                if (parent.length() > 0) parent.append("\n\n");
                parent.append(content);
            }
            String text = truncate(parent.toString(), parentLimit);
            if (!heading.isBlank() && !text.startsWith(heading)) {
                text = truncate(heading + "\n\n" + text, parentLimit);
            }
            for (int i = start; i < end; i++) {
                result.add(chunks.get(i).withParent(text));
            }
            start = end;
        }
        return result;
    }

    private StoredChunk toStored(String docId, String filename, LogicalChunk chunk, String domain) {
        return new StoredChunk(chunk.chunkId(), docId, chunk.index(), chunk.content(), chunk.parentText(),
                filename, chunk.headingPath(), chunk.lineStart(), chunk.lineEnd(),
                DocumentChunker.sha256(chunk.content() == null ? "" : chunk.content()),
                domain);
    }

    private List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        List<float[]> vectors = embeddingService.embedBatch(texts);
        int retries = Math.max(0, props.getEmbed().getRetries());
        for (int round = 0; round < retries; round++) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = i < vectors.size() ? vectors.get(i) : null;
                if (vector == null || vector.length == 0) missing.add(i);
            }
            if (missing.isEmpty()) break;
            for (int index : missing) {
                float[] vector = embeddingService.embed(texts.get(index));
                if (vector != null && vector.length > 0 && index < vectors.size()) vectors.set(index, vector);
            }
        }
        List<float[]> valid = new ArrayList<>(vectors.size());
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("向量化失败：" + embeddingService.getLastError());
            }
            valid.add(vector);
        }
        return valid;
    }

    private float[] embedSingle(String text) {
        float[] vector = embeddingService.embed(text);
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("向量化失败：" + embeddingService.getLastError());
        }
        return vector;
    }

    // ========== 内部：数据读取与元数据 ==========

    private List<LogicalChunk> loadChunks(String docId) {
        TreeMap<Integer, LogicalChunk> merged = new TreeMap<>();
        List<ChunkView> views = bm25Store.listByDoc(docId, Math.max(2000, props.getChunk().getMaxChunksPerFile()));
        for (ChunkView view : views) {
            merged.put(view.chunkIndex(), new LogicalChunk(view.chunkIndex(), view.chunkId(), view.content(),
                    view.parentText(), view.headingPath(), view.lineStart(), view.lineEnd(), true));
        }
        for (KnowledgeChunkState.DisabledChunk tomb : chunkState.listByDoc(docId)) {
            merged.put(tomb.chunkIndex(), new LogicalChunk(tomb.chunkIndex(), tomb.chunkId(), tomb.content(),
                    tomb.parentText(), tomb.headingPath(), tomb.lineStart(), tomb.lineEnd(), false));
        }
        return new ArrayList<>(merged.values());
    }

    private KnowledgeBaseService.FileMeta requireMeta(String filename) {
        KnowledgeBaseService.FileMeta meta = knowledgeBaseService.fileMetaOrNull(filename);
        if (meta == null) throw new IllegalArgumentException("文档不存在：" + filename);
        return meta;
    }

    private String filenameOf(String docId) {
        for (KnowledgeBaseService.FileMeta meta : knowledgeBaseService.allFileMetas()) {
            if (meta.docId() != null && meta.docId().equals(docId)) return meta.filename();
        }
        return "";
    }

    private void touchMeta(String filename, int chunkCount, int disabledCount) {
        KnowledgeBaseService.FileMeta meta = knowledgeBaseService.fileMetaOrNull(filename);
        if (meta == null) return;
        KnowledgeBaseService.FileMeta updated = meta.withChunkCount(Math.max(0, chunkCount))
                .withDisabledCount(Math.max(0, disabledCount));
        knowledgeBaseService.saveMetadata(updated);
    }

    private int indexOf(List<LogicalChunk> chunks, String chunkId) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkId().equals(chunkId)) return i;
        }
        throw new IllegalArgumentException("分段不存在：" + chunkId);
    }

    private LogicalChunk find(List<LogicalChunk> chunks, String chunkId) {
        return chunks.get(indexOf(chunks, chunkId));
    }

    private static int enabledCount(List<LogicalChunk> chunks) {
        int count = 0;
        for (LogicalChunk chunk : chunks) {
            if (chunk.enabled()) count++;
        }
        return count;
    }

    private void ensureCap(int enabled) {
        int cap = Math.max(1, props.getEdit().getMaxReembedChunks());
        if (enabled > cap) {
            throw new IllegalStateException("文档分段数 " + enabled + " 超过单次重建上限 " + cap
                    + "，请调大 rag.edit.max-reembed-chunks 或拆分文档");
        }
    }

    private int clampPageSize(int pageSize) {
        int max = Math.max(1, props.getEdit().getMaxPageSize());
        int size = pageSize <= 0 ? 20 : pageSize;
        return Math.min(size, max);
    }

    private int maxChunkChars() {
        return Math.max(200, props.getEdit().getMaxChunkChars());
    }

    private <T> T withLock(String docId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(docId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static boolean matches(LogicalChunk chunk, String needle) {
        String content = chunk.content() == null ? "" : chunk.content().toLowerCase();
        String heading = chunk.headingPath() == null ? "" : chunk.headingPath().toLowerCase();
        return content.contains(needle) || heading.contains(needle);
    }

    private static String preview(String content) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** 文档名清洗：禁止路径分隔符与 Windows 非法字符，长度 ≤255。 */
    public static String sanitizeFilename(String filename) {
        if (filename == null) throw new IllegalArgumentException("文件名不能为空");
        String value = filename.trim().replaceAll("[\\p{Cntrl}]", "");
        if (value.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        if (value.length() > 255) throw new IllegalArgumentException("文件名过长（最多 255 字符）");
        if (value.startsWith(".")) throw new IllegalArgumentException("文件名不能以点开头");
        if (value.matches(".*[/\\\\:*?\"<>|].*")) {
            throw new IllegalArgumentException("文件名不能包含 / \\ : * ? \" < > |");
        }
        if (value.contains("..")) throw new IllegalArgumentException("文件名不能包含 ..");
        return value;
    }
}
