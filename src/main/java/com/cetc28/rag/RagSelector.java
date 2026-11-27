package com.cetc28.rag;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 精排之后的"选择"阶段：去重 + 来源多样性 + 截断。
 *
 * <p>大候选集精排后常常出现同一段落在不同文件/不同块里重复、或者单个文件的连续段落
 * 霸占全部名额。这里按 内容哈希 → 标题路径 → 文件 三级约束收敛，保证注入模型的上下文
 * 覆盖更多独立事实，而不是同一段话的多个副本。
 */
@Component
public class RagSelector {

    /**
     * 按精排顺序挑选，保持输入顺序（输入应为分数降序）。
     *
     * @param ranked  精排后的候选（分数降序）
     * @param limit   最终条数
     * @param props   检索配置（maxPerDoc / maxPerHeading / dedupe）
     * @return 选择后的列表（长度 &lt;= limit）
     */
    public List<ChunkView> select(List<ChunkView> ranked, int limit, RagProperties props) {
        if (ranked == null || ranked.isEmpty()) return List.of();
        int max = Math.max(1, limit);
        RagProperties.Retrieve retrieve = props == null ? new RagProperties.Retrieve() : props.getRetrieve();
        int maxPerDoc = Math.max(1, retrieve.getMaxPerDoc());
        int maxPerHeading = Math.max(1, retrieve.getMaxPerHeading());
        boolean dedupe = retrieve.isDedupe();

        Set<String> seenHashes = new HashSet<>();
        Map<String, Integer> byDoc = new LinkedHashMap<>();
        Map<String, Integer> byHeading = new LinkedHashMap<>();
        List<ChunkView> picked = new ArrayList<>(Math.min(max, ranked.size()));

        for (ChunkView chunk : ranked) {
            if (chunk == null) continue;
            if (dedupe) {
                String hash = contentHash(chunk);
                if (!hash.isEmpty() && !seenHashes.add(hash)) continue;
            }
            String doc = chunk.filename() == null ? "" : chunk.filename();
            if (byDoc.getOrDefault(doc, 0) >= maxPerDoc) continue;
            String heading = chunk.headingPath() == null ? "" : chunk.headingPath().trim();
            if (!heading.isEmpty() && byHeading.getOrDefault(heading, 0) >= maxPerHeading) continue;

            picked.add(chunk);
            byDoc.merge(doc, 1, Integer::sum);
            if (!heading.isEmpty()) byHeading.merge(heading, 1, Integer::sum);
            if (picked.size() >= max) break;
        }
        return picked;
    }

    /** 正文归一化哈希（去掉空白差异，大小写不敏感）；用于"同一段落重复"去重。 */
    static String contentHash(ChunkView chunk) {
        String text = ChunkView.textOf(chunk);
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase();
        if (normalized.length() > 2000) normalized = normalized.substring(0, 2000);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return normalized;
        }
    }
}
