package com.cetc28.rag;

import com.cetc28.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 回答可验证性：逐句取证 + 相似度判定 + 降级说明 */
class RagVerifyServiceTest {

    @Test
    void splitSentencesKeepsChinesePunctuation() {
        List<String> sentences = RagVerifyService.splitSentences(
                "北京带宽是1000Mbps。预算约285万元！是否需要扩容到4000Mbps？");
        assertEquals(3, sentences.size(), "句子切分应保留以中文标点结尾的句子，实际 " + sentences);
        assertEquals("北京带宽是1000Mbps。", sentences.get(0));
        assertEquals("预算约285万元！", sentences.get(1));
        assertEquals("是否需要扩容到4000Mbps？", sentences.get(2));
    }

    @Test
    void splitSentencesDropsTooShortFragments() {
        List<String> sentences = RagVerifyService.splitSentences("好的。北京数据中心需要扩容带宽。");
        assertEquals(List.of("北京数据中心需要扩容带宽。"), sentences,
                "过短片段（<6 字符）不参与核查，否则噪声句子会拉低通过率");
    }

    @Test
    void cosineAndJaccardAreSane() {
        assertEquals(1.0, RagVerifyService.cosine(new float[]{1, 0, 0}, new float[]{1, 0, 0}), 1e-6);
        assertEquals(0.0, RagVerifyService.cosine(new float[]{1, 0, 0}, new float[]{0, 1, 0}), 1e-6);
        assertEquals(0.0, RagVerifyService.cosine(null, new float[]{1}), 1e-6,
                "缺失向量不能抛异常，只能给 0 分");

        assertEquals(1.0, RagVerifyService.jaccard("预算约285万元", "预算约285万元"), 1e-6);
        assertEquals(0.0, RagVerifyService.jaccard("华为路由器", "天气晴朗"), 1e-6);
        assertTrue(RagVerifyService.jaccard("预算约285万元，含光纤铺设", "预算约285万元") > 0.4,
                "部分重合应得到中间分数，否则降级模式下永远判无依据");
    }

    @Test
    void verifyWithoutEvidenceFailsLoudly() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(RetrievalResult.empty("q", "空索引"));

        RagVerifyService service = service(retriever);
        RagVerifyService.VerifyResult result = service.verify("预算多少", "预算约 285 万元。", List.of());

        assertFalse(result.passed());
        assertEquals(0, result.evidenceCount());
        assertEquals("没有可用的证据片段", result.degradedReason());
    }

    @Test
    void verifyMarksUnsupportedSentence() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new RetrievalResult("预算多少",
                List.of(new RetrievalResult.Hit("doc:knowledge:a:1",
                        "预算约285万元含光纤铺设设备采购施工调试", "方案.txt", "预算", 12, 12, 0.9, "hybrid")),
                false, null, 3, 1, 1));

        RagVerifyService service = service(retriever);
        RagVerifyService.VerifyResult result = service.verify("预算多少",
                "预算约285万元含光纤铺设。华为路由器性能强劲。", List.of());

        assertEquals(2, result.sentences().size());
        assertTrue(result.sentences().get(0).supported(), "第一句有证据支撑（字符重合度高）");
        assertFalse(result.sentences().get(1).supported(), "第二句知识库里没有依据，必须被标出来");
        assertEquals(0.5, result.unsupportedRatio(), 1e-6);
        assertTrue(result.sentences().get(0).bestChunkSource().contains("方案.txt"),
                "有依据的句子要能指回来源文件");
    }

    private static RagVerifyService service(KnowledgeRetriever retriever) {
        RagProperties props = new RagProperties();
        props.getVerify().setSupportThreshold(0.3);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.isEnabled()).thenReturn(false); // 走字符重合度路径，测试不依赖外部 embedding
        return new RagVerifyService(retriever, embedding, props);
    }
}
