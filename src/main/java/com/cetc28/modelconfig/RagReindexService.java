package com.cetc28.modelconfig;

import com.cetc28.rag.KnowledgeBaseService;
import com.cetc28.rag.KnowledgeEditService;
import com.cetc28.rag.milvus.MilvusRagStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedding 配置切换后的索引重建：把已有文档按原文重新解析、重新切块、重新写入新集合。
 *
 * <p>只处理保存了原文的文档；历史遗留（无原文）文档计入 failed 并在结果里提示需要重新上传。
 * 重建过程异步执行，进度写入 Redis {@code knowledge:reindex:progress} 供前端轮询。
 */
@Service
public class RagReindexService {

    private static final Logger log = LoggerFactory.getLogger(RagReindexService.class);
    private static final String PROGRESS_KEY = "knowledge:reindex:progress";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEditService editService;
    private final MilvusRagStore ragStore;
    private final StringRedisTemplate redis;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rag-reindex");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Progress current = Progress.idle();

    public RagReindexService(KnowledgeBaseService knowledgeBaseService,
                             KnowledgeEditService editService,
                             MilvusRagStore ragStore,
                             @Autowired(required = false) StringRedisTemplate redis) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.editService = editService;
        this.ragStore = ragStore;
        this.redis = redis;
    }

    public record Progress(boolean running, int total, int done, int failed, long startedAt,
                           long finishedAt, String message, String currentFile) {
        static Progress idle() {
            return new Progress(false, 0, 0, 0, 0L, 0L, "尚未执行重建", null);
        }
    }

    public Map<String, Object> progress() {
        Progress p = current;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("running", p.running());
        view.put("total", p.total());
        view.put("done", p.done());
        view.put("failed", p.failed());
        view.put("startedAt", p.startedAt());
        view.put("finishedAt", p.finishedAt());
        view.put("message", p.message());
        view.put("currentFile", p.currentFile());
        return view;
    }

    /** 是否已有重建任务在执行（避免重复触发）。 */
    public boolean isRunning() {
        return current.running();
    }

    /** 启动一次后台重建；返回 false 表示已有一个在跑。 */
    public synchronized boolean start(String reason) {
        if (current.running()) return false;
        Progress initial = new Progress(true, 0, 0, 0, System.currentTimeMillis(), 0L,
                reason == null ? "开始重建索引" : reason, null);
        current = initial;
        publish(initial);
        executor.submit(this::rebuild);
        return true;
    }

    private void rebuild() {
        int total = 0;
        int done = 0;
        int failed = 0;
        long startedAt = current.startedAt();
        try {
            ragStore.resetHolder();
            List<KnowledgeBaseService.FileMeta> files = knowledgeBaseService.allFileMetas();
            total = files.size();
            for (KnowledgeBaseService.FileMeta file : files) {
                current = new Progress(true, total, done, failed, startedAt, 0L,
                        "正在重建：" + file.filename(), file.filename());
                publish(current);
                try {
                    if (!file.hasOriginal()) {
                        failed++;
                        log.info("重建跳过无原文文档：{}", file.filename());
                        continue;
                    }
                    editService.reparse(file.filename());
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("重建文档失败 {}：{}", file.filename(), e.getMessage());
                }
            }
            String message = failed == 0
                    ? "重建完成：共 " + total + " 个文档"
                    : "重建完成：成功 " + done + "，失败 " + failed + "（历史文档无原文，请重新上传）";
            current = new Progress(false, total, done, failed, startedAt, System.currentTimeMillis(), message, null);
            publish(current);
            log.info("索引重建结束：total={}, done={}, failed={}", total, done, failed);
        } catch (Exception e) {
            current = new Progress(false, total, done, failed, startedAt, System.currentTimeMillis(),
                    "重建失败：" + e.getMessage(), null);
            publish(current);
            log.error("索引重建异常：{}", e.getMessage(), e);
        }
    }

    private void publish(Progress progress) {
        if (redis == null) return;
        try {
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("running", String.valueOf(progress.running()));
            hash.put("total", String.valueOf(progress.total()));
            hash.put("done", String.valueOf(progress.done()));
            hash.put("failed", String.valueOf(progress.failed()));
            hash.put("startedAt", String.valueOf(progress.startedAt()));
            hash.put("finishedAt", String.valueOf(progress.finishedAt()));
            hash.put("message", progress.message() == null ? "" : progress.message());
            hash.put("currentFile", progress.currentFile() == null ? "" : progress.currentFile());
            redis.opsForHash().putAll(PROGRESS_KEY, hash);
        } catch (Exception e) {
            log.debug("写入重建进度失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
