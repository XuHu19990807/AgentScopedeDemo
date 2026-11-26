package com.cetc28.modelconfig;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型流量监控：内存环形缓冲（最近 500 条，用于分钟级窗口与延迟分位）+ 按天聚合（用于 24h/7d）
 * + Redis 按天持久化（重启后仍能看到历史调用量）。
 */
@Service
public class ModelTrafficService {

    private static final Logger log = LoggerFactory.getLogger(ModelTrafficService.class);
    private static final int RECENT_CAP = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final Deque<TrafficCall> recent = new ConcurrentLinkedDeque<>();
    private final Map<String, DayStat> dayStats = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final AtomicBoolean redisWarned = new AtomicBoolean(false);

    public ModelTrafficService(@Autowired(required = false) StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void warmUp() {
        if (redis == null) return;
        try {
            for (int i = 0; i < 8; i++) {
                LocalDate day = LocalDate.now(ZONE).minusDays(i);
                String key = redisKey(day);
                Map<Object, Object> entries = redis.opsForHash().entries(key);
                if (entries == null || entries.isEmpty()) continue;
                for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                    String field = String.valueOf(entry.getKey());
                    String[] parts = field.split("\\|", 2);
                    if (parts.length != 2) continue;
                    DayStat stat = dayStat(day.format(DAY), parts[0], parts[1]);
                    String[] values = String.valueOf(entry.getValue()).split(",", 7);
                    if (values.length >= 6) {
                        stat.calls.set(parseLong(values[0]));
                        stat.errors.set(parseLong(values[1]));
                        stat.inputTokens.set(parseLong(values[2]));
                        stat.outputTokens.set(parseLong(values[3]));
                        stat.cachedTokens.set(parseLong(values[4]));
                        stat.sumLatency.set(parseLong(values[5]));
                    }
                    if (values.length >= 7 && !values[6].isBlank()) stat.modelName = values[6];
                }
            }
        } catch (Exception e) {
            log.debug("预热模型流量历史失败：{}", e.getMessage());
        }
    }

    /** 记录一次调用；线程安全，Redis 写失败不影响主流程。 */
    public void record(TrafficCall call) {
        if (call == null) return;
        recent.addLast(call);
        while (recent.size() > RECENT_CAP) {
            recent.pollFirst();
        }
        String day = dayKey(call.ts());
        DayStat stat = dayStat(day, call.kind(), call.profileId());
        stat.calls.incrementAndGet();
        if (call.ok()) {
            stat.inputTokens.addAndGet(call.inputTokens());
            stat.outputTokens.addAndGet(call.outputTokens());
            stat.cachedTokens.addAndGet(call.cachedTokens());
        } else {
            stat.errors.incrementAndGet();
        }
        stat.sumLatency.addAndGet(Math.max(0, call.latencyMs()));
        if (call.modelName() != null && !call.modelName().isBlank()) stat.modelName = call.modelName();
        persist(day, stat, call);
    }

    public void recordFailure(String kind, String profileId, String modelName, String agentId,
                              long latencyMs, String error) {
        record(TrafficCall.failed(kind, profileId, modelName, agentId, latencyMs, error));
    }

    public void reset() {
        recent.clear();
        dayStats.clear();
        if (redis != null) {
            try {
                for (int i = 0; i < 8; i++) {
                    redis.delete(redisKey(LocalDate.now(ZONE).minusDays(i)));
                }
            } catch (Exception e) {
                log.debug("清理 Redis 流量历史失败：{}", e.getMessage());
            }
        }
    }

    public long totalCalls() {
        return dayStats.values().stream().mapToLong(s -> s.calls.get()).sum();
    }

    /** 聚合视图：totals、按 kind/profile 分解、时间序列、最近调用。窗口支持 1h/24h/7d。 */
    public Map<String, Object> aggregate(String window) {
        long now = System.currentTimeMillis();
        long spanMs = spanOf(window);
        long from = now - spanMs;

        List<TrafficCall> calls = new ArrayList<>(recent);
        calls.sort(Comparator.comparingLong(TrafficCall::ts));
        List<TrafficCall> inWindow = calls.stream().filter(c -> c.ts() >= from).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", window);
        result.put("from", from);
        result.put("to", now);
        result.put("generatedAt", now);
        result.put("sampledCalls", calls.size());
        result.put("redisAvailable", redis != null);

        long totalCalls = 0;
        long totalErrors = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;
        long latencySum = 0;
        Map<String, long[]> byKind = new LinkedHashMap<>();
        Map<String, long[]> byProfile = new LinkedHashMap<>();
        Map<String, String> profileNames = new LinkedHashMap<>();
        for (DayStat stat : dayStats.values()) {
            if (stat.ts < from) continue;
            totalCalls += stat.calls.get();
            totalErrors += stat.errors.get();
            inputTokens += stat.inputTokens.get();
            outputTokens += stat.outputTokens.get();
            cachedTokens += stat.cachedTokens.get();
            latencySum += stat.sumLatency.get();
            long[] kind = byKind.computeIfAbsent(stat.kind, k -> new long[4]);
            kind[0] += stat.calls.get();
            kind[1] += stat.errors.get();
            kind[2] += stat.sumLatency.get();
            kind[3] += stat.inputTokens.get() + stat.outputTokens.get();
            String key = stat.kind + ":" + stat.profileId;
            long[] profile = byProfile.computeIfAbsent(key, k -> new long[6]);
            profile[0] += stat.calls.get();
            profile[1] += stat.errors.get();
            profile[2] += stat.sumLatency.get();
            profile[3] += stat.inputTokens.get();
            profile[4] += stat.outputTokens.get();
            profile[5] = stat.ts;
            if (stat.modelName != null) profileNames.putIfAbsent(key, stat.modelName);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("calls", totalCalls);
        totals.put("errors", totalErrors);
        totals.put("errorRate", totalCalls == 0 ? 0.0 : round((double) totalErrors / totalCalls, 4));
        totals.put("inputTokens", inputTokens);
        totals.put("outputTokens", outputTokens);
        totals.put("cachedTokens", cachedTokens);
        totals.put("totalTokens", inputTokens + outputTokens);
        totals.put("avgLatencyMs", totalCalls == 0 ? 0 : Math.round((double) latencySum / totalCalls));
        totals.put("rpm", rpm(inWindow, now));
        totals.put("p95LatencyMs", p95(inWindow));
        result.put("totals", totals);

        List<Map<String, Object>> kinds = new ArrayList<>();
        byKind.forEach((kind, v) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", kind);
            item.put("calls", v[0]);
            item.put("errors", v[1]);
            item.put("avgLatencyMs", v[0] == 0 ? 0 : Math.round((double) v[2] / v[0]));
            item.put("totalTokens", v[3]);
            kinds.add(item);
        });
        result.put("byKind", kinds);

        List<Map<String, Object>> profiles = new ArrayList<>();
        byProfile.forEach((key, v) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String[] parts = key.split(":", 2);
            item.put("kind", parts[0]);
            item.put("profileId", parts.length > 1 ? parts[1] : "unknown");
            item.put("modelName", profileNames.getOrDefault(key, ""));
            item.put("calls", v[0]);
            item.put("errors", v[1]);
            item.put("avgLatencyMs", v[0] == 0 ? 0 : Math.round((double) v[2] / v[0]));
            item.put("inputTokens", v[3]);
            item.put("outputTokens", v[4]);
            item.put("totalTokens", v[3] + v[4]);
            profiles.add(item);
        });
        profiles.sort((a, b) -> Long.compare((long) b.get("calls"), (long) a.get("calls")));
        result.put("byProfile", profiles);

        result.put("series", series(window, inWindow, from, now));

        List<Map<String, Object>> recentItems = new ArrayList<>();
        for (int i = inWindow.size() - 1; i >= 0 && recentItems.size() < 50; i--) {
            TrafficCall call = inWindow.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ts", call.ts());
            item.put("kind", call.kind());
            item.put("profileId", call.profileId());
            item.put("modelName", call.modelName());
            item.put("agentId", call.agentId());
            item.put("inputTokens", call.inputTokens());
            item.put("outputTokens", call.outputTokens());
            item.put("totalTokens", call.totalTokens());
            item.put("latencyMs", call.latencyMs());
            item.put("ok", call.ok());
            item.put("error", call.error());
            recentItems.add(item);
        }
        result.put("recent", recentItems);
        return result;
    }

    private List<Map<String, Object>> series(String window, List<TrafficCall> calls, long from, long now) {
        List<Map<String, Object>> points = new ArrayList<>();
        if ("7d".equalsIgnoreCase(window)) {
            for (int i = 6; i >= 0; i--) {
                LocalDate day = LocalDate.now(ZONE).minusDays(i);
                String dayKey = day.format(DAY);
                long dayStart = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
                long callsCount = 0;
                long errors = 0;
                for (DayStat stat : dayStats.values()) {
                    if (!stat.day.equals(dayKey)) continue;
                    callsCount += stat.calls.get();
                    errors += stat.errors.get();
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("t", dayStart);
                point.put("calls", callsCount);
                point.put("errors", errors);
                points.add(point);
            }
            return points;
        }
        long step = "1h".equalsIgnoreCase(window) ? 5 * 60_000L : 60 * 60_000L;
        int buckets = "1h".equalsIgnoreCase(window) ? 12 : 24;
        long start = (now / step) * step - (long) (buckets - 1) * step;
        for (int i = 0; i < buckets; i++) {
            long bucketStart = start + i * step;
            long bucketEnd = bucketStart + step;
            long callsCount = calls.stream().filter(c -> c.ts() >= bucketStart && c.ts() < bucketEnd).count();
            long errors = calls.stream().filter(c -> c.ts() >= bucketStart && c.ts() < bucketEnd && !c.ok()).count();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", bucketStart);
            point.put("calls", callsCount);
            point.put("errors", errors);
            points.add(point);
        }
        return points;
    }

    private long rpm(List<TrafficCall> calls, long now) {
        long from = now - 60_000L;
        return calls.stream().filter(c -> c.ts() >= from).count();
    }

    private long p95(List<TrafficCall> calls) {
        if (calls.isEmpty()) return 0;
        long[] latencies = calls.stream().mapToLong(TrafficCall::latencyMs).sorted().toArray();
        int index = (int) Math.min(latencies.length - 1, Math.ceil(latencies.length * 0.95) - 1);
        return latencies[Math.max(0, index)];
    }

    private long spanOf(String window) {
        if ("1h".equalsIgnoreCase(window)) return 60 * 60_000L;
        if ("7d".equalsIgnoreCase(window)) return 7 * 24 * 60 * 60_000L;
        return 24 * 60 * 60_000L;
    }

    private DayStat dayStat(String day, String kind, String profileId) {
        String key = day + "|" + kind + "|" + (profileId == null ? "unknown" : profileId);
        return dayStats.computeIfAbsent(key, k -> new DayStat(day, kind,
                profileId == null ? "unknown" : profileId, dayStart(day)));
    }

    private long dayStart(String day) {
        try {
            return LocalDate.parse(day, DAY).atStartOfDay(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String dayKey(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate().format(DAY);
    }

    private String redisKey(LocalDate day) {
        return "model:traffic:day:" + day.format(DAY);
    }

    private void persist(String day, DayStat stat, TrafficCall call) {
        if (redis == null) return;
        try {
            String key = "model:traffic:day:" + day;
            String field = stat.kind + "|" + stat.profileId;
            String value = stat.calls.get() + "," + stat.errors.get() + "," + stat.inputTokens.get()
                    + "," + stat.outputTokens.get() + "," + stat.cachedTokens.get() + ","
                    + stat.sumLatency.get() + "," + (stat.modelName == null ? "" : stat.modelName);
            redis.opsForHash().put(key, field, value);
            redis.expire(key, java.time.Duration.ofDays(8));
        } catch (Exception e) {
            if (redisWarned.compareAndSet(false, true)) {
                log.warn("模型流量写入 Redis 失败（不影响调用）：{}", e.getMessage());
            }
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static final class DayStat {
        private final String day;
        private final String kind;
        private final String profileId;
        private final long ts;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cachedTokens = new AtomicLong();
        private final AtomicLong sumLatency = new AtomicLong();
        private volatile String modelName;

        private DayStat(String day, String kind, String profileId, long ts) {
            this.day = day;
            this.kind = kind;
            this.profileId = profileId;
            this.ts = ts;
        }
    }
}
