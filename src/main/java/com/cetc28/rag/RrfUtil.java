package com.cetc28.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RRF 融合与文本归一化工具（纯函数，便于单测与两种检索实现共用）。
 */
public final class RrfUtil {

    private RrfUtil() {
    }

    /** 无权重 RRF：score = Σ 1/(k + rank) */
    public static Map<String, Double> fuseKeys(List<String> first, List<String> second, int rrfK) {
        return fuseWeighted(first, second, rrfK, 1.0, 1.0);
    }

    /**
     * 加权 RRF：每一路先按名次算 1/(k+rank)，再乘该路权重。
     *
     * @param first     第一路 key（按相关度从高到低）
     * @param second    第二路 key
     * @param rrfK      RRF 常数
     * @param firstWeight 第一路权重（稠密）
     * @param secondWeight 第二路权重（BM25）
     */
    public static Map<String, Double> fuseWeighted(List<String> first, List<String> second, int rrfK,
                                                   double firstWeight, double secondWeight) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < first.size(); i++) {
            scores.merge(first.get(i), firstWeight / (rrfK + i + 1.0), Double::sum);
        }
        for (int i = 0; i < second.size(); i++) {
            scores.merge(second.get(i), secondWeight / (rrfK + i + 1.0), Double::sum);
        }
        return scores;
    }

    /** 按融合分降序返回 key */
    public static List<String> orderByScore(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());
        List<String> ordered = new ArrayList<>(entries.size());
        for (Map.Entry<String, Double> entry : entries) ordered.add(entry.getKey());
        return ordered;
    }

    /** BM25 分数无上界，压缩到 0~1 便于展示与阈值过滤 */
    public static double bm25Score(double raw) {
        if (raw <= 0) return 0.0;
        return raw / (raw + 1.0);
    }

    public static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static String normalize(String query) {
        if (query == null) return "";
        String normalized = query.replaceAll("\\s+", " ").trim();
        while (normalized.length() > 200) {
            normalized = normalized.substring(0, 200);
        }
        return normalized;
    }

    /** 合并多路 key（去重、保序），用于查询扩展后的二次融合 */
    public static List<String> mergeKeys(List<List<String>> lists) {
        Set<String> all = new LinkedHashSet<>();
        for (List<String> list : lists) all.addAll(list);
        return new ArrayList<>(all);
    }

    /** 名称去重工具：同一 chunkId 只保留最高分的候选 */
    public static <T> Map<String, T> dedupeByKey(List<T> items, java.util.function.Function<T, String> keyFn,
                                                 Comparator<T> scoreComparator) {
        Map<String, T> best = new HashMap<>();
        for (T item : items) {
            best.merge(keyFn.apply(item), item, (a, b) -> scoreComparator.compare(a, b) >= 0 ? a : b);
        }
        return best;
    }
}
