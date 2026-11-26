package com.cetc28.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 退避纯函数：initial × 2^(n-1)，封顶 max，乘 0.8~1.2 抖动，下限 1000ms */
class McpReconnectPolicyTest {

    private static final long INITIAL = 10_000;
    private static final long MAX = 300_000;

    @Test
    void firstFailureWaitsAboutInitialDelay() {
        assertEquals(8_000, McpHubService.backoffMs(1, INITIAL, MAX, 0.0));
        assertEquals(10_000, McpHubService.backoffMs(1, INITIAL, MAX, 0.5));
        assertEquals(12_000, McpHubService.backoffMs(1, INITIAL, MAX, 0.999999));
    }

    @Test
    void delayDoublesPerAttempt() {
        assertEquals(20_000, McpHubService.backoffMs(2, INITIAL, MAX, 0.5));
        assertEquals(40_000, McpHubService.backoffMs(3, INITIAL, MAX, 0.5));
        assertEquals(80_000, McpHubService.backoffMs(4, INITIAL, MAX, 0.5));
        assertEquals(160_000, McpHubService.backoffMs(5, INITIAL, MAX, 0.5));
    }

    @Test
    void delayIsCappedAtMax() {
        assertEquals(MAX, McpHubService.backoffMs(20, INITIAL, MAX, 0.5));
        // 极端 attempts 不能溢出成负数或异常
        assertTrue(McpHubService.backoffMs(1000, INITIAL, MAX, 0.5) > 0);
    }

    @Test
    void neverBelowOneSecondFloor() {
        assertEquals(1_000, McpHubService.backoffMs(1, 100, 500, 0.0));
    }

    @Test
    void attemptsBelowOneTreatedAsFirst() {
        assertEquals(McpHubService.backoffMs(1, INITIAL, MAX, 0.5),
                McpHubService.backoffMs(0, INITIAL, MAX, 0.5));
        assertEquals(McpHubService.backoffMs(1, INITIAL, MAX, 0.5),
                McpHubService.backoffMs(-3, INITIAL, MAX, 0.5));
    }

    @Test
    void jitterClampedIntoValidRange() {
        assertEquals(McpHubService.backoffMs(1, INITIAL, MAX, 0.0),
                McpHubService.backoffMs(1, INITIAL, MAX, -5.0));
        assertEquals(McpHubService.backoffMs(1, INITIAL, MAX, 0.999999),
                McpHubService.backoffMs(1, INITIAL, MAX, 42.0));
    }
}
