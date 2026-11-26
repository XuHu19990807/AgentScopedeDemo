package com.cetc28.modelconfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTrafficServiceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> aggregate) {
        return (Map<String, Object>) aggregate.get("totals");
    }

    private static long number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }

    @Test
    void aggregatesRecordedCalls() {
        ModelTrafficService service = new ModelTrafficService(null);
        service.record(TrafficCall.ok("chat", "default", "deepseek-chat", "chat", 10, 20, 5, 120));
        service.record(TrafficCall.failed("chat", "default", "deepseek-chat", "chat", 300, "上游返回 401"));
        service.record(TrafficCall.ok("embedding", "default", "qwen-embed", null, 8, 0, 0, 40));

        Map<String, Object> aggregate = service.aggregate("24h");
        Map<String, Object> totals = totals(aggregate);

        assertEquals("24h", aggregate.get("window"));
        assertEquals(3L, number(totals, "calls"));
        assertEquals(1L, number(totals, "errors"));
        assertEquals(18L, number(totals, "inputTokens"));
        assertEquals(20L, number(totals, "outputTokens"));
        assertEquals(5L, number(totals, "cachedTokens"));
        assertEquals(38L, number(totals, "totalTokens"));
        assertEquals(2, ((List<?>) aggregate.get("byProfile")).size());
        assertEquals(2, ((List<?>) aggregate.get("byKind")).size());
        assertEquals(24, ((List<?>) aggregate.get("series")).size());
        assertEquals(3, ((List<?>) aggregate.get("recent")).size());
        assertEquals(3L, service.totalCalls());
        assertTrue(((Number) ((Map<String, Object>) aggregate.get("totals")).get("avgLatencyMs")).longValue() > 0);
    }

    @Test
    void windowsUseExpectedBucketCounts() {
        ModelTrafficService service = new ModelTrafficService(null);
        service.record(TrafficCall.ok("chat", "default", "deepseek-chat", "chat", 1, 1, 0, 10));

        assertEquals(12, ((List<?>) service.aggregate("1h").get("series")).size());
        assertEquals(24, ((List<?>) service.aggregate("24h").get("series")).size());
        assertEquals(7, ((List<?>) service.aggregate("7d").get("series")).size());
    }

    @Test
    void resetClearsCounters() {
        ModelTrafficService service = new ModelTrafficService(null);
        service.record(TrafficCall.ok("chat", "default", "deepseek-chat", "chat", 1, 1, 0, 10));
        assertEquals(1L, service.totalCalls());

        service.reset();

        assertEquals(0L, service.totalCalls());
        assertEquals(0, ((List<?>) service.aggregate("24h").get("recent")).size());
        assertEquals(0L, number(totals(service.aggregate("24h")), "calls"));
    }

    @Test
    void recentBufferDropsOldCallsBeyondCapacity() {
        ModelTrafficService service = new ModelTrafficService(null);
        for (int i = 0; i < 520; i++) {
            service.record(TrafficCall.ok("chat", "default", "deepseek-chat", "chat", 1, 1, 0, 5));
        }

        assertEquals(50, ((List<?>) service.aggregate("24h").get("recent")).size());
        assertEquals(520L, number(totals(service.aggregate("24h")), "calls"));
    }
}
