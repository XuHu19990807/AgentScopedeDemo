package com.cetc28.chat;

import com.cetc28.service.DocumentTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 对话附件服务：校验、落盘、文本提取、分块、Redis 元数据、归属解析、清理。
 *
 * Redis：
 *   chat:file:{id}          HASH  meta（ChatFile JSON）
 *   chat:file:{id}:chunks   LIST  ChatFileChunk JSON
 *   chat:conv:{cid}:files   SET   fileId
 * 全部 7 天 TTL。
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    private static final String FILE_PREFIX = "chat:file:";
    private static final String CHUNK_SUFFIX = ":chunks";
    private static final String CONV_FILES_PREFIX = "chat:conv:";
    private static final String FILES_SUFFIX = ":files";
    private static final String META_FIELD = "meta";
    private static final int PREVIEW_CHARS = 200;
    private static final int SCAN_COUNT = 500;

    /** 允许模型生成的文件扩展名（下载后可直接打开）。 */
    private static final Set<String> EXPORT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".csv", ".json", ".log", ".xml", ".yaml", ".yml",
            ".docx", ".xlsx");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final DocumentTextExtractor extractor;
    private final Executor fileExecutor;

    /** 可选：用于 SCAN 清理，Redis 不可用时为 null。 */
    private UnifiedJedis jedis;

    @Value("${chat.files.enabled:true}")
    private boolean enabled = true;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir = "./uploads";

    @Value("${chat.files.max-size-mb:20}")
    private int maxSizeMb = 20;

    @Value("${chat.files.max-files:5}")
    private int maxFiles = 5;

    @Value("${chat.files.chunk-chars:1200}")
    private int chunkChars = 1200;

    @Value("${chat.files.chunk-overlap:200}")
    private int chunkOverlap = 200;

    @Value("${chat.files.ttl-days:7}")
    private int ttlDays = 7;

    @Value("${chat.files.extract-timeout-seconds:30}")
    private int extractTimeoutSeconds = 30;

    @Value("${chat.files.export-max-mb:10}")
    private int exportMaxMb = 10;

    public ChatFileService(StringRedisTemplate redis,
                           ObjectMapper mapper,
                           DocumentTextExtractor extractor,
                           @Qualifier("chatFileExecutor") Executor fileExecutor) {
        this.redis = redis;
        this.mapper = mapper;
        this.extractor = extractor;
        this.fileExecutor = fileExecutor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJedis(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    // ========== 上传 ==========

    public ChatFile save(MultipartFile file, String userId) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件上传未启用");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空");
        }
        String original = file.getOriginalFilename();
        String safeName = sanitizeFilename(original);
        String ext = DocumentTextExtractor.extensionOf(safeName);
        if (!extractor.supports(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, extractor.unsupportedMessage());
        }
        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件超过 " + maxSizeMb + "MB 限制");
        }

        String id = "f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String storedName = id + "_" + safeName;
        Path target = pathOf(userId, storedName);
        try {
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败: " + e.getMessage());
        }

        String text;
        try {
            text = extractWithTimeout(target, ext);
        } catch (ResponseStatusException e) {
            deleteQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteQuietly(target);
            log.warn("文件解析失败 name={}: {}", safeName, e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "文件解析失败，可能已加密或损坏: " + safeMessage(e));
        }
        if (text == null || text.isBlank()) {
            deleteQuietly(target);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "无法从该文件提取文本，可能是扫描件或空文件");
        }

        List<ChatFileChunk> chunks = chunk(text);
        long now = System.currentTimeMillis();
        ChatFile meta = ChatFile.builder()
                .id(id)
                .userId(userId)
                .name(safeName)
                .storedName(storedName)
                .ext(ext)
                .size(file.getSize())
                .chars(text.length())
                .chunks(chunks.size())
                .preview(preview(text))
                .createdAt(now)
                .build();
        try {
            store(meta, chunks);
        } catch (Exception e) {
            deleteQuietly(target);
            log.warn("保存附件元数据失败 id={}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件服务暂不可用，请稍后重试");
        }
        log.info("对话附件已保存: id={}, name={}, {} bytes, {} chars, {} chunks",
                id, safeName, file.getSize(), text.length(), chunks.size());
        return meta;
    }

    // ========== 生成文件（模型产出） ==========

    /**
     * 保存模型生成的文件（docx/xlsx/txt 等）：只落盘 + 登记元数据，不做文本提取。
     * 生成文件与上传附件共用下载接口 {@code GET /api/chat/files/{id}}，同属 7 天 TTL。
     */
    public ChatFile saveGenerated(String userId, String conversationId, String fileName,
                                  byte[] bytes, String previewText) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件功能未启用");
        }
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生成的文件内容为空");
        }
        String safeName = sanitizeFilename(fileName);
        String ext = DocumentTextExtractor.extensionOf(safeName);
        if (!EXPORT_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持导出为 " + (ext.isEmpty() ? "无扩展名文件" : ext)
                            + "；可选 .txt .md .csv .json .docx .xlsx");
        }
        long maxBytes = (long) Math.max(1, exportMaxMb) * 1024 * 1024;
        if (bytes.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "生成文件超过 " + exportMaxMb + "MB 限制");
        }

        String id = "f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String storedName = id + "_" + safeName;
        Path target = pathOf(userId, storedName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "保存生成文件失败: " + e.getMessage());
        }

        ChatFile meta = ChatFile.builder()
                .id(id)
                .userId(userId)
                .conversationId(conversationId)
                .name(safeName)
                .storedName(storedName)
                .ext(ext)
                .size(bytes.length)
                .chars(0)
                .chunks(0)
                .preview(preview(previewText == null ? "" : previewText))
                .source("generated")
                .createdAt(System.currentTimeMillis())
                .build();
        try {
            store(meta, List.of());
            if (conversationId != null && !conversationId.isBlank()) {
                redis.opsForSet().add(convFilesKey(conversationId), id);
                redis.expire(convFilesKey(conversationId), ttl());
            }
        } catch (Exception e) {
            deleteQuietly(target);
            log.warn("保存生成文件元数据失败 id={}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件服务暂不可用，请稍后重试");
        }
        log.info("生成文件已保存: id={}, name={}, {} bytes, cid={}", id, safeName, bytes.length, conversationId);
        return meta;
    }

    /** 上传响应视图（不含物理路径）。 */
    public Map<String, Object> toView(ChatFile file) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fileId", file.getId());
        view.put("name", file.getName());
        view.put("size", file.getSize());
        view.put("chars", file.getChars());
        view.put("chunks", file.getChunks());
        view.put("preview", file.getPreview() == null ? "" : file.getPreview());
        return view;
    }

    private String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > PREVIEW_CHARS ? flat.substring(0, PREVIEW_CHARS) + "…" : flat;
    }

    // ========== 读取 / 绑定 ==========

    public List<LoadedFile> resolve(List<String> fileIds, String userId) {
        if (fileIds == null || fileIds.isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String id : fileIds) {
            if (id != null && !id.isBlank()) unique.add(id.trim());
        }
        if (unique.isEmpty()) return List.of();
        if (unique.size() > maxFiles) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多上传 " + maxFiles + " 个文件");
        }

        List<LoadedFile> result = new ArrayList<>(unique.size());
        for (String id : unique) {
            ChatFile meta = loadMeta(id);
            if (!userId.equals(meta.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在: " + id);
            }
            List<String> rawChunks;
            try {
                rawChunks = redis.opsForList().range(chunksKey(id), 0, -1);
            } catch (DataAccessException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件服务暂不可用");
            }
            if (rawChunks == null || rawChunks.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.GONE, "附件已过期，请重新上传: " + meta.getName());
            }
            List<ChatFileChunk> chunks = new ArrayList<>(rawChunks.size());
            for (String raw : rawChunks) {
                try {
                    chunks.add(mapper.readValue(raw, ChatFileChunk.class));
                } catch (Exception e) {
                    log.warn("跳过损坏的附件分块 id={}: {}", id, e.getMessage());
                }
            }
            if (chunks.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.GONE, "附件已过期，请重新上传: " + meta.getName());
            }
            result.add(new LoadedFile(meta, chunks));
        }
        return result;
    }

    /** 把附件绑定到会话；失败只记日志，不影响对话。 */
    public void bind(List<LoadedFile> files, String conversationId) {
        if (files == null || files.isEmpty() || conversationId == null) return;
        for (LoadedFile loaded : files) {
            ChatFile meta = loaded.meta();
            try {
                if (meta.getConversationId() != null && !meta.getConversationId().equals(conversationId)) {
                    redis.opsForSet().remove(convFilesKey(meta.getConversationId()), meta.getId());
                }
                meta.setConversationId(conversationId);
                redis.opsForHash().put(metaKey(meta.getId()), META_FIELD, mapper.writeValueAsString(meta));
                redis.expire(metaKey(meta.getId()), ttl());
                redis.opsForSet().add(convFilesKey(conversationId), meta.getId());
                redis.expire(convFilesKey(conversationId), ttl());
            } catch (Exception e) {
                log.warn("绑定附件到会话失败 id={}, cid={}: {}", meta.getId(), conversationId, e.getMessage());
            }
        }
    }

    // ========== 下载 / 删除 ==========

    public Download download(String fileId, String userId) {
        ChatFile meta = loadMeta(fileId);
        if (!userId.equals(meta.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        Path path = pathOf(meta);
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.GONE, "附件已过期，请重新上传");
        }
        return new Download(path, meta.getName());
    }

    public boolean delete(String fileId, String userId) {
        ChatFile meta = loadMeta(fileId);
        if (!userId.equals(meta.getUserId())) {
            return false;
        }
        deleteInternal(meta);
        return true;
    }

    public void deleteByConversation(String conversationId, String userId) {
        if (conversationId == null) return;
        try {
            Set<String> ids = redis.opsForSet().members(convFilesKey(conversationId));
            if (ids != null) {
                for (String id : ids) {
                    try {
                        ChatFile meta = loadMeta(id);
                        if (userId == null || userId.equals(meta.getUserId())) {
                            deleteInternal(meta);
                        }
                    } catch (ResponseStatusException ignored) {
                        // 已过期，跳过
                    }
                }
            }
            redis.delete(convFilesKey(conversationId));
        } catch (Exception e) {
            log.warn("清理会话附件失败 cid={}: {}", conversationId, e.getMessage());
        }
    }

    /** 删除某用户的全部附件（清空会话时调用）。 */
    public void deleteByUser(String userId) {
        if (userId == null || jedis == null) return;
        for (String key : scanKeys(FILE_PREFIX + "*")) {
            if (key.endsWith(CHUNK_SUFFIX)) continue;
            try {
                String id = key.substring(FILE_PREFIX.length());
                ChatFile meta = loadMeta(id);
                if (userId.equals(meta.getUserId())) {
                    deleteInternal(meta);
                }
            } catch (Exception ignored) {
                // 数据损坏/已过期，跳过
            }
        }
    }

    private void deleteInternal(ChatFile meta) {
        try {
            if (meta.getConversationId() != null) {
                redis.opsForSet().remove(convFilesKey(meta.getConversationId()), meta.getId());
            }
            redis.delete(metaKey(meta.getId()));
            redis.delete(chunksKey(meta.getId()));
        } catch (Exception e) {
            log.warn("删除附件 Redis 键失败 id={}: {}", meta.getId(), e.getMessage());
        }
        deleteQuietly(pathOf(meta));
    }

    // ========== 状态 / 清理 ==========

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("maxSizeMb", maxSizeMb);
        status.put("maxFiles", maxFiles);
        status.put("ttlDays", ttlDays);
        status.put("supported", extractor.supportedExtensions());
        return status;
    }

    /** 每天凌晨清理磁盘上超过 TTL 且 Redis 已无元数据的孤儿文件。 */
    @Scheduled(cron = "${chat.files.cleanup-cron:0 30 3 * * *}")
    public void cleanupOrphanFiles() {
        if (!enabled) return;
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        long deadline = System.currentTimeMillis() - ttl().toMillis();
        try (var stream = Files.walk(root, 2)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String name = path.getFileName().toString();
                    int idx = name.indexOf('_');
                    if (idx <= 0) return;
                    String id = name.substring(0, idx);
                    if (Files.getLastModifiedTime(path).toMillis() > deadline) return;
                    if (Boolean.TRUE.equals(redis.hasKey(metaKey(id)))) return;
                    Files.deleteIfExists(path);
                    log.info("清理孤儿附件: {}", path);
                } catch (Exception ignored) {
                    // 单个文件失败不影响整体
                }
            });
        } catch (Exception e) {
            log.debug("扫描附件目录失败: {}", e.getMessage());
        }
    }

    // ========== 内部 ==========

    private String extractWithTimeout(Path target, String ext) throws Exception {
        FutureTask<String> task = new FutureTask<>(() -> extractor.extract(target, ext));
        fileExecutor.execute(task);
        try {
            return task.get(Math.max(5, extractTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "文件解析超时，请尝试更小的文件");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件解析被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof UnsupportedOperationException) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, cause.getMessage());
            }
            throw e;
        }
    }

    private void store(ChatFile meta, List<ChatFileChunk> chunks) throws Exception {
        String metaJson = mapper.writeValueAsString(meta);
        List<String> chunkJson = new ArrayList<>(chunks.size());
        for (ChatFileChunk chunk : chunks) {
            chunkJson.add(mapper.writeValueAsString(chunk));
        }
        redis.opsForHash().put(metaKey(meta.getId()), META_FIELD, metaJson);
        redis.expire(metaKey(meta.getId()), ttl());
        if (!chunkJson.isEmpty()) {
            redis.opsForList().rightPushAll(chunksKey(meta.getId()), chunkJson);
            redis.expire(chunksKey(meta.getId()), ttl());
        }
    }

    private ChatFile loadMeta(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件 ID 无效");
        }
        try {
            Object raw = redis.opsForHash().get(metaKey(fileId), META_FIELD);
            if (raw == null) {
                throw new ResponseStatusException(HttpStatus.GONE, "附件已过期，请重新上传");
            }
            return mapper.readValue(raw.toString(), ChatFile.class);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件服务暂不可用");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.GONE, "附件已损坏，请重新上传");
        }
    }

    private List<ChatFileChunk> chunk(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int len = text.length();
        int size = Math.max(200, chunkChars);
        int overlap = Math.max(0, Math.min(chunkOverlap, size / 2));
        List<ChatFileChunk> chunks = new ArrayList<>();
        int cursor = 0;
        int line = 1;
        int index = 0;
        while (cursor < len) {
            int end = Math.min(len, cursor + size);
            if (end < len) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > cursor + size / 2) end = newline + 1;
            }
            if (end < len && end > cursor && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            if (end <= cursor) end = Math.min(len, cursor + 1);
            String part = text.substring(cursor, end);
            if (!part.isBlank()) {
                int endLine = line + countNewlines(part);
                chunks.add(new ChatFileChunk(index++, line, endLine, part));
            }
            if (end >= len) break;
            int next = Math.max(cursor + 1, end - overlap);
            line += countNewlines(text.substring(cursor, next));
            cursor = next;
        }
        if (chunks.isEmpty()) {
            chunks.add(new ChatFileChunk(0, 1, 1, text));
        }
        return chunks;
    }

    private int countNewlines(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') count++;
        }
        return count;
    }

    private Path pathOf(ChatFile meta) {
        return pathOf(meta.getUserId(), meta.getStoredName());
    }

    private Path pathOf(String userId, String storedName) {
        Path dir = Paths.get(uploadDir, sanitizeUserId(userId)).toAbsolutePath().normalize();
        Path path = dir.resolve(storedName).normalize();
        if (!path.startsWith(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法文件路径");
        }
        return path;
    }

    private String metaKey(String id) {
        return FILE_PREFIX + id;
    }

    private String chunksKey(String id) {
        return FILE_PREFIX + id + CHUNK_SUFFIX;
    }

    private String convFilesKey(String conversationId) {
        return CONV_FILES_PREFIX + conversationId + FILES_SUFFIX;
    }

    private Duration ttl() {
        return Duration.ofDays(Math.max(1, ttlDays));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.debug("删除文件失败 {}: {}", path, e.getMessage());
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        if (jedis == null) return keys;
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        try {
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                if (result == null) break;
                cursor = result.getCursor();
                if (result.getResult() != null) keys.addAll(result.getResult());
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            log.warn("SCAN 附件失败 pattern='{}': {}", pattern, e.getMessage());
        }
        return keys;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    // ========== 文件名/用户 ID 净化 ==========

    /** 只保留文件名本身，去掉路径、控制字符与非法字符，防止路径穿越。 */
    public static String sanitizeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "upload_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (name.length() > 120) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) ext = name.substring(dot);
            name = name.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return name;
    }

    /** userId 只允许安全字符，防止当作目录名时穿越。 */
    public static String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) return "default";
        String safe = userId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) return "default";
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    public record LoadedFile(ChatFile meta, List<ChatFileChunk> chunks) {
    }

    public record Download(Path path, String name) {
    }
}
