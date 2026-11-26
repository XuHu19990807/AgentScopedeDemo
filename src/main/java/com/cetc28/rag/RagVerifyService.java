package com.cetc28.rag;

import com.cetc28.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回答可验证性检查：把答案拆句，逐句与证据块比对相似度，标出"没有依据"的句子。
 *
 * <p>用途不是"判定对错"（那需要事实核查），而是让"模型编造"这件事变得可见：
 * 如果一句话在任何检索片段里都找不到支撑，它的 supported 就是 false，
 * 前端/评审可以直接看到 unsupportedRatio。
 *
 * <p>相似度优先用 embedding 余弦；embedding 不可用时退化为字符 bigram Jaccard，
 * 精度下降但仍能捕捉"整句都是新事实"的情况。
 */
@Component
public class RagVerifyService {

    private static final Logger log = LoggerFactory.getLogger(RagVerifyService.class);

    private final KnowledgeRetriever retriever;
    private final EmbeddingService embeddingService;
    private final RagProperties props;

    public RagVerifyService(KnowledgeRetriever retriever,
                            EmbeddingService embeddingService, RagProperties props) {
        this.retriever = retriever;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    public record SentenceCheck(String text, String bestChunkId, String bestChunkSource,
                                double score, boolean supported) {}

    public record VerifyResult(String query, List<SentenceCheck> sentences, double unsupportedRatio,
                               boolean passed, int evidenceCount, String degradedReason) {}

    public VerifyResult verify(String query, String answer, List<String> chunkIds) {
        List<Evidence> evidence = collectEvidence(query, chunkIds);
        if (evidence.isEmpty()) {
            return new VerifyResult(query, List.of(), 0.0, false, 0, "没有可用的证据片段");
        }

        List<String> sentences = splitSentences(answer);
        boolean degraded = false;
        List<float[]> sentenceVectors = new ArrayList<>();
        List<float[]> evidenceVectors = new ArrayList<>();
        if (embeddingService.isEnabled()) {
            List<String> texts = new ArrayList<>(sentences);
            for (Evidence e : evidence) texts.add(e.content);
            List<float[]> vectors = embeddingService.embedBatch(texts);
            if (vectors.size() == texts.size() && vectors.stream().allMatch(v -> v != null)) {
                sentenceVectors = new ArrayList<>(vectors.subList(0, sentences.size()));
                evidenceVectors = new ArrayList<>(vectors.subList(sentences.size(), vectors.size()));
            } else {
                degraded = true;
            }
        } else {
            degraded = true;
        }

        List<SentenceCheck> checks = new ArrayList<>();
        int unsupported = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            double best = -1;
            Evidence bestEvidence = null;
            for (int j = 0; j < evidence.size(); j++) {
                double similarity;
                if (!sentenceVectors.isEmpty() && !evidenceVectors.isEmpty()) {
                    similarity = cosine(sentenceVectors.get(i), evidenceVectors.get(j));
                } else {
                    similarity = jaccard(sentence, evidence.get(j).content);
                }
                if (similarity > best) {
                    best = similarity;
                    bestEvidence = evidence.get(j);
                }
            }
            boolean supported = best >= props.getVerify().getSupportThreshold();
            if (!supported) unsupported++;
            checks.add(new SentenceCheck(sentence,
                    bestEvidence == null ? null : bestEvidence.chunkId,
                    bestEvidence == null ? null : bestEvidence.source,
                    round(best), supported));
        }
        double ratio = checks.isEmpty() ? 0.0 : (double) unsupported / checks.size();
        boolean passed = !checks.isEmpty() && ratio <= 1.0 - props.getVerify().getSupportThreshold();
        log.info("知识库回答核查：句子 {}，无依据 {}，unsupportedRatio={}，降级={}",
                checks.size(), unsupported, String.format("%.2f", ratio), degraded);
        return new VerifyResult(query, checks, round(ratio), passed, evidence.size(),
                degraded ? "embedding 不可用，已退化为字符重合度比对" : null);
    }

    private record Evidence(String chunkId, String source, String content) {}

    private List<Evidence> collectEvidence(String query, List<String> chunkIds) {
        List<Evidence> evidence = new ArrayList<>();
        if (chunkIds != null && !chunkIds.isEmpty()) {
            for (String chunkId : chunkIds) {
                ChunkView hit = retriever.readChunk(chunkId);
                if (hit != null && hit.content() != null && !hit.content().isBlank()) {
                    evidence.add(new Evidence(hit.chunkId(), source(hit), hit.content()));
                }
            }
        }
        if (evidence.isEmpty()) {
            RetrievalResult result = retriever.retrieve(query);
            for (RetrievalResult.Hit hit : result.hits()) {
                evidence.add(new Evidence(hit.chunkId(),
                        (hit.filename() == null ? "" : hit.filename()) + " " + hit.location(),
                        hit.content()));
            }
        }
        return evidence;
    }

    private static String source(ChunkView hit) {
        StringBuilder sb = new StringBuilder(hit.filename() == null ? "" : hit.filename());
        if (hit.headingPath() != null && !hit.headingPath().isBlank()) {
            sb.append(" · ").append(hit.headingPath());
        }
        if (hit.lineStart() > 0) sb.append(" · 第 ").append(hit.lineStart()).append(" 行");
        return sb.toString();
    }

    /** 按中英文句末标点切句，过滤过短片段（编号、表格残行等） */
    static List<String> splitSentences(String answer) {
        List<String> sentences = new ArrayList<>();
        if (answer == null || answer.isBlank()) return sentences;
        String[] parts = answer.split("(?<=[。！？!?；;\\n])");
        for (String part : parts) {
            String sentence = part.trim();
            if (sentence.length() < 6) continue;
            if (sentence.startsWith("|")) continue;
            if (sentence.matches("^\\[K\\d+].*")) continue;
            sentences.add(sentence);
            if (sentences.size() >= 40) break;
        }
        return sentences;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 字符 bigram 重合度（中文不需要分词，短句比对足够） */
    static double jaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        if (text == null) return grams;
        String clean = text.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= clean.length(); i++) {
            grams.add(clean.substring(i, i + 2));
        }
        return grams;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
