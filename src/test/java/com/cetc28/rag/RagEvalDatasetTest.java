package com.cetc28.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据集驱动的检索评测（多格式语料 + 120 题）。
 *
 * <p>默认不执行；本地或发版前用下面的命令跑（需要 Milvus 与 embedding 可用）：
 *
 * <pre>
 *   mvn test -Dtest=RagEvalDatasetTest -Drag.eval=true
 * </pre>
 *
 * <p>语料与题目由 {@code scripts/gen_rag_eval.py} 生成。指标口径：
 * 命中判定 = top-k 里出现目标文件，或片段正文包含该题关键词；nDCG@10 用二值增益。
 */
@SpringBootTest
@Tag("eval")
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvalDatasetTest {

    private static final String BASE = "rag-eval/";
    private static final String CORPUS = BASE + "corpus/";
    private static final String DATASET = BASE + "dataset.jsonl";

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private ObjectMapper mapper;

    private final List<String> uploaded = new ArrayList<>();

    record Case(String id, String domain, String question, String file, List<String> keywords) {
    }

    @BeforeAll
    void beforeAll() throws Exception {
        List<Case> cases = loadCases(mapper);
        Set<String> files = new LinkedHashSet<>();
        for (Case item : cases) files.add(item.file());
        for (String file : files) {
            String path = CORPUS + file;
            try (InputStream in = RagEvalDatasetTest.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("缺少评测语料：" + path);
                MockMultipartFile multipart = new MockMultipartFile(
                        "file", file, contentType(file), in.readAllBytes());
                KnowledgeBaseService.UploadResult result = knowledgeBaseService.uploadFile(multipart, null, file);
                if (!result.success()) throw new IllegalStateException("上传失败 " + file + "：" + result.message());
                uploaded.add(file);
            }
        }
        Thread.sleep(800);
    }

    @AfterAll
    void afterAll() {
        for (String file : uploaded) {
            try {
                knowledgeBaseService.deleteFile(file);
            } catch (Exception ignored) {
                // 清理失败不影响评测结论
            }
        }
    }

    @Test
    void dataset() throws Exception {
        List<Case> cases = loadCases(mapper);
        int topK = 20;
        Map<String, Score> overall = new LinkedHashMap<>();
        Map<String, Score> byDomain = new LinkedHashMap<>();

        StringBuilder report = new StringBuilder("\n=== RAG 数据集评测 ===\n");
        Map<String, Integer> degradedReasons = new LinkedHashMap<>();
        List<Long> tooks = new ArrayList<>();
        for (Case item : cases) {
            RetrievalResult result = retriever.retrieve(item.question());
            tooks.add(result.tookMs());
            List<RetrievalResult.Hit> hits = result.hits();
            if (result.degraded()) {
                String reason = result.degradedReason() == null ? "未知降级" : result.degradedReason();
                degradedReasons.merge(reason, 1, Integer::sum);
            }
            Score total = overall.computeIfAbsent("ALL", key -> new Score());
            Score domain = byDomain.computeIfAbsent(item.domain(), key -> new Score());
            int rank = firstRelevant(hits, item, topK);
            total.add(hits, item, rank);
            domain.add(hits, item, rank);
            if (rank < 0) {
                report.append("MISS ").append(item.id()).append(" ").append(item.question())
                        .append(" → ").append(hits.isEmpty() ? "无结果" : hits.get(0).filename()).append('\n');
            }
        }
        report.append('\n');
        for (Map.Entry<String, Score> entry : byDomain.entrySet()) {
            report.append(String.format("%-8s %s%n", entry.getKey(), entry.getValue().summary()));
        }
        report.append(String.format("%-8s %s%n", "ALL", overall.get("ALL").summary()));
        if (!degradedReasons.isEmpty()) {
            report.append("降级次数：").append(degradedReasons).append('\n');
        }
        report.append(String.format("耗时：avg=%.0fms p95=%.0fms max=%dms%n",
                tooks.stream().mapToLong(Long::longValue).average().orElse(0),
                percentile(tooks, 0.95),
                tooks.stream().mapToLong(Long::longValue).max().orElse(0)));
        System.out.println(report);

        Score all = overall.get("ALL");
        assertTrue(all.recallAt(6) >= doubleProp("rag.eval.recall6", 0.80),
                "Recall@6 低于阈值：" + all.summary());
        assertTrue(all.recallAt(20) >= doubleProp("rag.eval.recall20", 0.90),
                "Recall@20 低于阈值：" + all.summary());
        assertTrue(all.mrr() >= doubleProp("rag.eval.mrr", 0.70), "MRR 低于阈值：" + all.summary());
        assertTrue(all.ndcg(10) >= doubleProp("rag.eval.ndcg", 0.70), "nDCG@10 低于阈值：" + all.summary());
        assertTrue(percentile(tooks, 0.95) <= doubleProp("rag.eval.p95ms", 1500),
                "检索 p95 超过阈值：" + percentile(tooks, 0.95) + "ms");
        for (Map.Entry<String, Score> entry : byDomain.entrySet()) {
            assertTrue(entry.getValue().recallAt(6) >= doubleProp("rag.eval.domainRecall6", 0.70),
                    "域 " + entry.getKey() + " 的 Recall@6 过低：" + entry.getValue().summary());
        }
    }

    private static double percentile(List<Long> values, double ratio) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double doubleProp(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 首个相关结果的 0-based 名次；top-k 内没有则 -1。 */
    private static int firstRelevant(List<RetrievalResult.Hit> hits, Case item, int topK) {
        int limit = Math.min(topK, hits.size());
        for (int i = 0; i < limit; i++) {
            RetrievalResult.Hit hit = hits.get(i);
            if (item.file().equals(hit.filename())) return i;
            String content = hit.content() == null ? "" : hit.content();
            for (String keyword : item.keywords()) {
                if (!keyword.isBlank() && content.contains(keyword)) return i;
            }
        }
        return -1;
    }

    private static List<Case> loadCases(ObjectMapper mapper) throws Exception {
        List<Case> cases = new ArrayList<>();
        String text;
        try (InputStream in = RagEvalDatasetTest.class.getClassLoader().getResourceAsStream(DATASET)) {
            if (in == null) throw new IllegalStateException("缺少数据集：" + DATASET);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            cases.add(mapper.readValue(line, Case.class));
        }
        return cases;
    }

    private static String contentType(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    /** 累积式指标：Recall@6/@20、MRR@6、nDCG@10。 */
    private static final class Score {
        private int total;
        private int hit6;
        private int hit20;
        private double reciprocal = 0.0;
        private double dcg = 0.0;
        private double idcg = 0.0;

        void add(List<RetrievalResult.Hit> hits, Case item, int rank) {
            total++;
            if (rank >= 0 && rank < 6) hit6++;
            if (rank >= 0 && rank < 20) hit20++;
            if (rank >= 0 && rank < 6) reciprocal += 1.0 / (rank + 1);
            // 每题只有一条理想答案：DCG 取第一个相关名的增益，IDCG@10 = 1
            if (rank >= 0 && rank < 10) dcg += 1.0 / (Math.log(rank + 2) / Math.log(2));
            idcg += 1.0;
        }

        private static boolean isRelevant(RetrievalResult.Hit hit, Case item) {
            if (item.file().equals(hit.filename())) return true;
            String content = hit.content() == null ? "" : hit.content();
            for (String keyword : item.keywords()) {
                if (!keyword.isBlank() && content.contains(keyword)) return true;
            }
            return false;
        }

        double recallAt(int k) {
            return total == 0 ? 0 : (k <= 6 ? hit6 : hit20) / (double) total;
        }

        double mrr() {
            return total == 0 ? 0 : reciprocal / total;
        }

        double ndcg(int k) {
            return idcg == 0 ? 0 : dcg / idcg;
        }

        String summary() {
            return String.format("cases=%d Recall@6=%.3f Recall@20=%.3f MRR@6=%.3f nDCG@10=%.3f",
                    total, recallAt(6), recallAt(20), mrr(), ndcg(10));
        }
    }
}
