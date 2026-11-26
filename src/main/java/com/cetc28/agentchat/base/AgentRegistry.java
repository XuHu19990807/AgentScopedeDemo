package com.cetc28.agentchat.base;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent注册中心 — 管理所有Agent的注册与发现。
 *
 * 启用/禁用状态直接取自 Agent 自身（由 AgentSystemConfig 依据配置设置），
 * 不再维护独立的状态表。
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, AgentBase> agents = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Agent注册中心已启动");
    }

    public void register(AgentBase agent) {
        agents.put(agent.getAgentId(), agent);
        log.info("Agent '{}' ({}) 已注册", agent.getAgentName(), agent.getAgentId());
    }

    public Optional<AgentBase> getAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public List<AgentBase> getEnabledAgents() {
        return agents.values().stream()
                .filter(AgentBase::isEnabled)
                .toList();
    }

    public boolean isAgentEnabled(String agentId) {
        return getAgent(agentId).map(AgentBase::isEnabled).orElse(false);
    }

    public int getEnabledAgentCount() {
        return (int) agents.values().stream().filter(AgentBase::isEnabled).count();
    }
}
