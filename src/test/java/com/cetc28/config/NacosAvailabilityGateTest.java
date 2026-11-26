package com.cetc28.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosAvailabilityGateTest {

    @Test
    void opensCircuitAfterThresholdAndRecovers() {
        NacosAvailabilityGate gate = new NacosAvailabilityGate();
        assertTrue(gate.isAvailable());

        gate.recordFailure("test-1");
        gate.recordFailure("test-2");
        assertTrue(gate.isAvailable(), "未达到阈值时应仍可用");

        gate.recordFailure("test-3");
        assertFalse(gate.isAvailable(), "达到阈值后应熔断");

        gate.recordSuccess();
        assertTrue(gate.isAvailable(), "成功后应恢复");
    }
}
