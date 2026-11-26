package com.cetc28.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库原文存储：保存/读取/复制/删除、扩展名归一化、docId 校验。
 */
class KnowledgeStorageTest {

    @TempDir
    Path tempDir;

    private KnowledgeStorage storage;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.getStorage().setDir(tempDir.toString());
        storage = new KnowledgeStorage(props);
        storage.init();
    }

    @Test
    void saveLoadAndDeleteRoundTrip() {
        byte[] bytes = "传输网割接手册".getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.save("abc123", ".md", bytes));
        assertTrue(storage.exists("abc123", ".md"), "保存后应能查到原文");

        Optional<byte[]> loaded = storage.load("abc123", ".md");
        assertTrue(loaded.isPresent());
        assertArrayEquals(bytes, loaded.get());

        assertTrue(storage.delete("abc123", ".md"));
        assertFalse(storage.exists("abc123", ".md"), "删除后不应再存在");
        assertTrue(storage.load("abc123", ".md").isEmpty());
    }

    @Test
    void copyPreparesOriginalForRename() {
        byte[] bytes = "原文内容".getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.save("old1", ".txt", bytes));
        assertTrue(storage.copy("old1", "new1", ".txt"));
        assertArrayEquals(bytes, storage.load("new1", ".txt").orElseThrow());
        assertFalse(storage.copy("ffffffff", "new2", ".txt"), "源文件不存在时复制应返回 false");
    }

    @Test
    void normalizeExtRejectsWeirdValues() {
        assertEquals(".md", KnowledgeStorage.normalizeExt("md"));
        assertEquals(".pdf", KnowledgeStorage.normalizeExt(".PDF"));
        assertEquals(".bin", KnowledgeStorage.normalizeExt(null));
        assertEquals(".bin", KnowledgeStorage.normalizeExt(" "));
        assertEquals(".bin", KnowledgeStorage.normalizeExt("../../etc/passwd"), "非法扩展名用 .bin 兜底");
        assertEquals(".bin", KnowledgeStorage.normalizeExt(".toolongextension"));
    }

    @Test
    void rejectsIllegalDocId() {
        assertThrows(IllegalArgumentException.class, () -> storage.exists("!!!", ".md"),
                "docId 只允许十六进制，全部非法字符时应当直接拒绝");
    }

    @Test
    void dirIsCreated() {
        assertTrue(tempDir.toFile().exists());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), storage.dir());
    }
}
