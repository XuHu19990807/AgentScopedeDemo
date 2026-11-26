package com.cetc28.rag;

import com.cetc28.rag.KnowledgeBaseService.FileMeta;
import com.cetc28.rag.milvus.MilvusBm25Store;
import com.cetc28.rag.milvus.MilvusRagStore;
import com.cetc28.service.DocumentTextExtractor;
import com.cetc28.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库工作台编辑服务：分页视图、非法输入与顺序校验。
 *
 * <p>Milvus 与 embedding 全部用 mock，保证单测不依赖外部服务。
 */
class KnowledgeEditServiceTest {

    private KnowledgeBaseService knowledgeBaseService;
    private MilvusRagStore store;
    private MilvusBm25Store bm25Store;
    private EmbeddingService embeddingService;
    private RagProperties props;
    private KnowledgeStorage storage;
    private DocumentTextExtractor extractor;
    private DocumentChunker chunker;
    private KnowledgeChunkState chunkState;
    private KnowledgeEditService service;

    private static final String DOC_ID = "1234abcd5678ef90";
    private static final String FILENAME = "duanxu.md";

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        store = mock(MilvusRagStore.class);
        bm25Store = mock(MilvusBm25Store.class);
        embeddingService = mock(EmbeddingService.class);
        props = new RagProperties();
        storage = mock(KnowledgeStorage.class);
        extractor = mock(DocumentTextExtractor.class);
        chunker = mock(DocumentChunker.class);
        chunkState = mock(KnowledgeChunkState.class);
        service = new KnowledgeEditService(knowledgeBaseService, store, bm25Store, embeddingService,
                props, storage, extractor, chunker, chunkState);

        when(knowledgeBaseService.fileMetaOrNull(FILENAME)).thenReturn(meta(FILENAME, DOC_ID, 3));
    }

    private static FileMeta meta(String filename, String docId, int chunks) {
        return new FileMeta(filename, 1234L, "2026-01-01T00:00:00Z", chunks, null, docId, 1000.0, true, 0, 0L);
    }

    private static ChunkView view(int index, String content) {
        return new ChunkView(KnowledgeBaseService.chunkKey(DOC_ID, index), DOC_ID, index, content, content,
                FILENAME, "# 标题", index * 10 + 1, index * 10 + 9, 0.5);
    }

    @Test
    void pageSlicesAndPreviewsChunks() {
        when(bm25Store.listByDoc(eq(DOC_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(view(0, "第一段内容"), view(1, "第二段内容"), view(2, "第三段内容")));
        when(chunkState.listByDoc(DOC_ID)).thenReturn(List.of());

        KnowledgeEditService.ChunkPage page = service.page(FILENAME, 1, 2, null);
        assertEquals(3, page.total());
        assertEquals(2, page.pages());
        assertEquals(2, page.items().size());
        assertEquals(3, page.enabledCount());
        assertEquals(0, page.disabledCount());
        assertEquals(KnowledgeBaseService.chunkKey(DOC_ID, 0), page.items().get(0).chunkId());
        assertEquals("第一段内容", page.items().get(0).preview());
    }

    @Test
    void pageFiltersByQueryAndClampsOutOfRangePage() {
        when(bm25Store.listByDoc(eq(DOC_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(view(0, "预算 196 万元"), view(1, "割接窗口 240 分钟")));
        when(chunkState.listByDoc(DOC_ID)).thenReturn(List.of());

        KnowledgeEditService.ChunkPage page = service.page(FILENAME, 99, 10, "预算");
        assertEquals(1, page.total());
        assertEquals(1, page.page());
        assertEquals(1, page.items().size());
    }

    @Test
    void pageRejectsUnknownDocument() {
        assertThrows(IllegalArgumentException.class, () -> service.page("nope.md", 1, 20, null));
    }

    @Test
    void reorderRejectsStaleChunkList() {
        when(bm25Store.listByDoc(eq(DOC_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(view(0, "a"), view(1, "b")));
        when(chunkState.listByDoc(DOC_ID)).thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> service.reorderChunks(FILENAME, List.of(KnowledgeBaseService.chunkKey(DOC_ID, 0))),
                "分段集合不一致时必须拒绝，避免覆盖别人的改动");
    }

    @Test
    void sanitizeFilenameBlocksPathAndIllegalChars() {
        assertEquals("duanxu.md", KnowledgeEditService.sanitizeFilename("  duanxu.md "));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename(null));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename("../x.md"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename("a/b.md"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename("a:b.md"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEditService.sanitizeFilename("a".repeat(256)));
    }

    @Test
    void updateChunkRequiresNonEmptyContent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateChunk(KnowledgeBaseService.chunkKey(DOC_ID, 0), "   "));
    }

    @Test
    void detailRejectsUnknownChunk() {
        when(bm25Store.listByDoc(eq(DOC_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(view(0, "a")));
        when(chunkState.listByDoc(DOC_ID)).thenReturn(List.of());
        when(knowledgeBaseService.allFileMetas()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.detail(KnowledgeBaseService.chunkKey(DOC_ID, 5)));
    }
}
