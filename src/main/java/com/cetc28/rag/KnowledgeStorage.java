package com.cetc28.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 知识库原文存储：上传成功后把原始文件落到本地磁盘，供"原文预览/重新解析"使用。
 *
 * <p>命名规则 {@code {docId}{ext}}（docId 是文件名的 sha256 前 16 位），因此重命名文档时需要
 * 复制一份新文件；删除文档时同步清理。历史文档没有原文，调用方需要自行降级。
 */
@Component
public class KnowledgeStorage {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStorage.class);

    private final RagProperties props;
    private Path root;

    public KnowledgeStorage(RagProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        try {
            root = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            log.info("知识库原文目录：{}", root);
        } catch (Exception e) {
            root = Paths.get("data/knowledge").toAbsolutePath().normalize();
            log.warn("创建知识库原文目录失败（降级到 {}）：{}", root, e.getMessage());
        }
    }

    public String dir() {
        return root == null ? props.getStorage().getDir() : root.toString();
    }

    public boolean save(String docId, String ext, byte[] bytes) {
        if (docId == null || bytes == null) return false;
        try {
            Path target = pathOf(docId, ext);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            log.warn("保存知识库原文失败（docId={}）：{}", docId, e.getMessage());
            return false;
        }
    }

    public Optional<byte[]> load(String docId, String ext) {
        if (docId == null) return Optional.empty();
        Path path = pathOf(docId, ext);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            log.warn("读取知识库原文失败（{}）：{}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean exists(String docId, String ext) {
        return docId != null && Files.isRegularFile(pathOf(docId, ext));
    }

    public boolean delete(String docId, String ext) {
        if (docId == null) return false;
        try {
            return Files.deleteIfExists(pathOf(docId, ext));
        } catch (Exception e) {
            log.warn("删除知识库原文失败（docId={}）：{}", docId, e.getMessage());
            return false;
        }
    }

    /** 重命名/重建时把原文复制到新 docId 下。 */
    public boolean copy(String fromDocId, String toDocId, String ext) {
        if (fromDocId == null || toDocId == null) return false;
        Path source = pathOf(fromDocId, ext);
        if (!Files.isRegularFile(source)) return false;
        try {
            Path target = pathOf(toDocId, ext);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            log.warn("复制知识库原文失败（{} → {}）：{}", fromDocId, toDocId, e.getMessage());
            return false;
        }
    }

    /** 允许的扩展名（含点，小写）；空扩展名用 .bin 兜底。 */
    public static String normalizeExt(String ext) {
        if (ext == null || ext.isBlank()) return ".bin";
        String value = ext.trim().toLowerCase();
        if (!value.startsWith(".")) value = "." + value;
        if (!value.matches("\\.[a-z0-9]{1,10}")) return ".bin";
        return value;
    }

    private Path pathOf(String docId, String ext) {
        String safeId = docId.replaceAll("[^0-9a-fA-F]", "");
        if (safeId.isEmpty() || safeId.length() > 32) {
            throw new IllegalArgumentException("非法 docId：" + docId);
        }
        return root.resolve(safeId + normalizeExt(ext));
    }
}
