package com.cetc28.rag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索质量评测：往知识库写入一份带明确事实的文档，用一组口语化问题测 Recall@5 / MRR@5。
 *
 * <p>默认不执行（需要真实 Milvus + embedding 服务），运行方式：
 * <pre>mvn -o test -Dtest=RagEvalTest -Drag.eval=true</pre>
 *
 * <p>这是"可验证"的一部分：RAG 效果不能只靠人工感觉，要有可重复的量化基线。
 * 目前语料只有 1 个文件，评测集为手写的 12 条事实型问题；扩语料后应把问题集补到 50 条以上，
 * 并加入「改写/同义/跨段组合」三类问题。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
class RagEvalTest {

    private static final String FILENAME = "rag-eval-华东专线.txt";

    private static final String DOC = """
            华东区光纤专线升级方案

            一、背景
            华东节点现有互联网专线带宽为 600Mbps，高峰期丢包率约 1.2%，已无法支撑影像回传业务。

            二、目标
            本次升级目标带宽为 2000Mbps，要求在 2025年6月底 前完成割接。

            三、设备清单
            华为 S6730-H 万兆交换机 2 台，华为 NE8000 核心路由器 1 台，配套光模块 8 个。

            四、预算
            项目总预算 168 万元，含设备采购、线路施工与测试验收。

            五、实施与风险
            施工窗口仅限周末，需在 4 小时内完成切换；联系人：张工（网络运维部）。

            六、验收标准
            割接后连续 72 小时无丢包，带宽实测不低于 1800Mbps。
            """;

    /** 问题 → 期望命中的关键片段（任一命中即算召回成功） */
    private static final Object[][] CASES = {
            {"这次专线升级的目标带宽是多少", new String[]{"2000Mbps"}},
            {"什么时候必须完成割接", new String[]{"2025年6月底"}},
            {"这个项目一共要花多少钱", new String[]{"168"}},
            {"打算用哪款交换机", new String[]{"S6730"}},
            {"核心路由器是什么型号", new String[]{"NE8000"}},
            {"升级之前带宽有多大", new String[]{"600Mbps"}},
            {"施工时间有什么限制", new String[]{"周末"}},
            {"出事找谁对接", new String[]{"张工"}},
            {"要买几台交换机", new String[]{"2 台"}},
            {"这是哪个区域的方案", new String[]{"华东"}},
            {"割接后怎么算通过验收", new String[]{"72 小时"}},
            {"光模块需要多少个", new String[]{"8 个"}},
    };

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeRetriever retriever;

    @BeforeAll
    void upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", FILENAME, "text/plain", DOC.getBytes(StandardCharsets.UTF_8));
        KnowledgeBaseService.UploadResult result = knowledgeBaseService.uploadFile(file, null, FILENAME);
        assertTrue(result.success(), "评测文档写入失败: " + result.message());
        Thread.sleep(300); // 等 Milvus 可见性
    }

    @AfterAll
    void cleanup() {
        try {
            knowledgeBaseService.deleteFile(FILENAME);
        } catch (Exception ignored) {
            // 评测数据清理失败不影响结论
        }
    }

    @Test
    void recallAndMrr() {
        int topK = 5;
        List<Double> reciprocalRanks = new ArrayList<>();
        StringBuilder table = new StringBuilder("\n检索评测（hybrid, top-5）\n");
        int recalled = 0;

        for (Object[] item : CASES) {
            String question = (String) item[0];
            String[] keywords = (String[]) item[1];
            RetrievalResult result = retriever.retrieve(question);
            List<RetrievalResult.Hit> hits = result.hits();

            int rank = 0;
            for (int i = 0; i < Math.min(topK, hits.size()); i++) {
                String content = hits.get(i).content();
                if (containsAny(content, keywords)) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0) {
                recalled++;
                reciprocalRanks.add(1.0 / rank);
            } else {
                reciprocalRanks.add(0.0);
            }
            table.append(String.format("  %-22s rank=%s  hits=%d  degraded=%s%n",
                    question, rank == 0 ? "MISS" : String.valueOf(rank), hits.size(), result.degraded()));
        }

        double recall = (double) recalled / CASES.length;
        double mrr = reciprocalRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        table.append(String.format("  Recall@%d=%.3f  MRR@%d=%.3f  (%d/%d)%n",
                topK, recall, topK, mrr, recalled, CASES.length));
        System.out.print(table);

        assertTrue(recall >= 0.8, "Recall@5 低于 0.8: " + recall);
        assertTrue(mrr >= 0.6, "MRR@5 低于 0.6: " + mrr);
    }

    private static boolean containsAny(String content, String[] keywords) {
        if (content == null) return false;
        for (String keyword : keywords) {
            if (content.contains(keyword)) return true;
        }
        return false;
    }
}
