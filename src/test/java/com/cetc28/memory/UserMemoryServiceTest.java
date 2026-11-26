package com.cetc28.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.UnifiedJedis;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    @Mock
    private UnifiedJedis jedis;

    private UserMemoryService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryService(jedis);
    }

    @Test
    void storesExplicitMemoryStatement() {
        service.rememberIfRequested("u1", "请记住：蓝色的钥匙在抽屉里。");
        verify(jedis).hset(anyString(), anyString(), anyString());
    }

    @Test
    void skipsQuestionsSoTheyDoNotPolluteLaterTurns() {
        // 回归：旧正则把「我的钥匙是什么颜色？」这类问句也存成了长期记忆
        service.rememberIfRequested("u1", "我的钥匙是什么颜色？");
        service.rememberIfRequested("u1", "我的钥匙是什么颜色?");
        verify(jedis, never()).hset(anyString(), anyString(), anyString());
    }

    @Test
    void skipsUnrelatedStatements() {
        service.rememberIfRequested("u1", "今天天气不错");
        verify(jedis, never()).hset(anyString(), anyString(), anyString());
    }
}
