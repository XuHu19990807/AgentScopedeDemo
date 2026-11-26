package com.cetc28.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStackConfigTest {

    /**
     * AgentScope 的 ChatUsage 会把计算属性 totalTokens 序列化进 Redis，
     * 但反序列化时没有对应 setter。严格模式会导致整份 AgentState 读取失败，
     * 表现为多轮对话丢失上下文。
     */
    @Test
    void chatUsageWithComputedTotalTokensCanRoundTrip() throws Exception {
        ObjectMapper mapper = new RedisStackConfig().redisStackObjectMapper();
        ChatUsage usage = new ChatUsage(12, 7, 3, 1.25);

        String json = mapper.writeValueAsString(usage);
        assertTrue(json.contains("totalTokens"), "序列化结果应包含计算属性 totalTokens");

        ChatUsage parsed = assertDoesNotThrow(() -> mapper.readValue(json, ChatUsage.class));
        assertEquals(12, parsed.getInputTokens());
        assertEquals(7, parsed.getOutputTokens());
        assertEquals(3, parsed.getCachedTokens());
        assertEquals(1.25, parsed.getTime());
    }
}
