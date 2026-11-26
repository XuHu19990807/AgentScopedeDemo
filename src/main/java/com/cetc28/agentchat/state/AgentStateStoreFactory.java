package com.cetc28.agentchat.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

/**
 * 为每个 Agent 生成独立的 {@link AgentStateStore}。
 *
 * <p>这是修复旧工程最严重缺陷的关键：旧实现把框架传入的 {@code userId} 当成
 * {@code agentId} 拼进 key（{@code agentstate:{userId}:{sessionId}:{key}}），导致同一
 * conversationId 下 chat / deep / orchestrator 共用同一个 {@code agent_state} 槽位，
 * 切换模式会互相覆盖甚至串读历史。
 *
 * <p>新实现的 key 布局：
 *
 * <pre>
 *   agentchat:v2:agentstate:{agentId}:{userId}:{sessionId}:{key}
 * </pre>
 *
 * <p>AgentStateStore 的接口契约不变——调用方仍按 {@code (userId, sessionId)} 存取，
 * agentId 只体现在每个 Agent 各自持有的 store 实例里。
 */
@Component
public class AgentStateStoreFactory {

    private final UnifiedJedis jedis;
    private final ObjectMapper mapper;

    @Value("${app.state.prefix:agentchat:v2:}")
    private String prefix;

    public AgentStateStoreFactory(UnifiedJedis jedis, ObjectMapper mapper) {
        this.jedis = jedis;
        this.mapper = mapper;
    }

    public AgentStateStore create(String agentId) {
        return new RedisAgentStateStore(jedis, mapper,
                prefix + "agentstate:" + agentId + ":");
    }
}
