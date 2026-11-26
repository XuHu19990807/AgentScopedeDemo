package com.cetc28.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nacos 可用性闸门（按特性隔离）。
 *
 * Nacos 是提示词/Skill/MCP 发现的动态来源，但它不可用时不能让应用启动被拖慢、
 * 也不能每个 Agent 都重试一遍。这里做三件事：
 * 1. 按特性（prompt / skill / mcp）分别记录连续失败次数；
 * 2. 某特性达到阈值后只熔断该特性，不影响其他特性；
 * 3. 熔断只告警一次，避免日志刷屏。
 */
@Component
public class NacosAvailabilityGate {

    private static final Logger log = LoggerFactory.getLogger(NacosAvailabilityGate.class);

    /** 未指定特性时的默认域（兼容旧调用） */
    public static final String DEFAULT_DOMAIN = "default";

    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Value("${nacos.circuit-breaker.failure-threshold:3}")
    private int failureThreshold = 3;

    @Value("${nacos.circuit-breaker.cooldown-seconds:300}")
    private long cooldownSeconds = 300;

    private State state(String domain) {
        return states.computeIfAbsent(domain == null ? DEFAULT_DOMAIN : domain, ignored -> new State());
    }

    /** 当前是否允许调用 Nacos（默认域） */
    public boolean isAvailable() {
        return isAvailable(DEFAULT_DOMAIN);
    }

    /** 指定特性当前是否允许调用 Nacos */
    public boolean isAvailable(String domain) {
        State state = state(domain);
        long until = state.blockedUntil.get();
        if (until == 0) return true;
        if (System.currentTimeMillis() >= until) {
            // 熔断窗口结束，给 Nacos 一次机会；下次再失败会重新告警
            state.blockedUntil.compareAndSet(until, 0);
            state.warningLogged.set(false);
            return true;
        }
        return false;
    }

    public void recordSuccess() {
        recordSuccess(DEFAULT_DOMAIN);
    }

    public void recordSuccess(String domain) {
        State state = state(domain);
        state.consecutiveFailures.set(0);
        state.blockedUntil.set(0);
        state.warningLogged.set(false);
    }

    public void recordFailure(String operation) {
        recordFailure(DEFAULT_DOMAIN, operation);
    }

    public void recordFailure(String domain, String operation) {
        State state = state(domain);
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures < failureThreshold) return;
        long until = System.currentTimeMillis() + Math.max(1, cooldownSeconds) * 1000L;
        state.blockedUntil.set(until);
        if (state.warningLogged.compareAndSet(false, true)) {
            log.warn("Nacos {} 连续失败 {} 次（最近一次: {}），仅暂停该特性的 Nacos 调用 {} 秒，其余特性不受影响",
                    domain, failures, operation, cooldownSeconds);
        }
    }

    /** 健康检查用 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        states.forEach((domain, state) -> {
            long until = state.blockedUntil.get();
            if (until == 0) return;
            long left = Math.max(0, (until - System.currentTimeMillis()) / 1000);
            if (sb.length() > 0) sb.append(", ");
            sb.append(domain).append(":degraded(").append(left).append("s)");
        });
        return sb.length() == 0 ? "up" : sb.toString();
    }

    private static final class State {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong blockedUntil = new AtomicLong();
        private final AtomicBoolean warningLogged = new AtomicBoolean();
    }
}
