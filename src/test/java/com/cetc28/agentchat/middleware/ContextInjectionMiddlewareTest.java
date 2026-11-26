package com.cetc28.agentchat.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextInjectionMiddleware 单测：动态上下文必须以 USER 消息注入，
 * 而不是改写 system prompt（否则破坏前缀缓存与 compaction）。
 */
class ContextInjectionMiddlewareTest {

    private final ContextInjectionMiddleware middleware = new ContextInjectionMiddleware();

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private List<Msg> run(RuntimeContext ctx, List<Msg> messages) {
        AtomicReference<List<Msg>> captured = new AtomicReference<>();
        middleware.onReasoning(null, ctx, new ReasoningInput(messages, List.of(), null), input -> {
            captured.set(input.messages());
            return reactor.core.publisher.Flux.empty();
        }).blockLast();
        assertNotNull(captured.get());
        return captured.get();
    }

    @Test
    void injectsContextAsUserMessageBeforeLastUserTurn() {
        RuntimeContext ctx = RuntimeContext.builder()
                .put(ContextInjectionMiddleware.CONTEXT_KEY, "plan: [knowledge, tool]")
                .build();
        List<Msg> out = run(ctx, List.of(user("你是一个知识Agent"), user("查一下规范")));

        assertEquals(3, out.size(), "原消息应保留，并插入一条参考材料");
        assertTrue(out.get(1).getTextContent().contains("plan: [knowledge, tool]"));
        assertEquals("查一下规范", out.get(2).getTextContent(), "注入消息应位于最后一条用户消息之前");
        assertTrue(Boolean.TRUE.equals(out.get(1).getMetadata().get(ContextInjectionMiddleware.INJECTED_META)));
    }

    @Test
    void passesThroughWhenNoContext() {
        List<Msg> out = run(RuntimeContext.builder().build(), List.of(user("你好")));
        assertEquals(1, out.size());
        assertEquals("你好", out.get(0).getTextContent());
    }

    @Test
    void passesThroughWhenBlankContext() {
        RuntimeContext ctx = RuntimeContext.builder()
                .put(ContextInjectionMiddleware.CONTEXT_KEY, "   ")
                .build();
        List<Msg> out = run(ctx, List.of(user("你好")));
        assertEquals(1, out.size());
    }
}
