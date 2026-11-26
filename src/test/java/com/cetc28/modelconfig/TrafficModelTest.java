package com.cetc28.modelconfig;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TrafficModelTest {

    private static Model delegate(String name, ChatUsage usage) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ChatResponse response = new ChatResponse("reply-1", List.<ContentBlock>of(), usage, Map.of(), "stop");
                return Flux.just(response);
            }

            @Override
            public String getModelName() {
                return name;
            }
        };
    }

    @Test
    void recordsUsageAndLatencyOnSuccess() {
        ModelTrafficService traffic = mock(ModelTrafficService.class);
        TrafficModel model = new TrafficModel(
                () -> new ModelTarget("default", "deepseek-chat",
                        delegate("deepseek-chat", new ChatUsage(11, 22, 33, 1.5d))),
                traffic, "chat", "chat-agent", "default");

        assertEquals(1L, model.stream(List.of(), List.of(), null).count().block());

        verify(traffic).record(org.mockito.ArgumentMatchers.argThat(call ->
                call.ok()
                        && "default".equals(call.profileId())
                        && "deepseek-chat".equals(call.modelName())
                        && call.inputTokens() == 11
                        && call.outputTokens() == 22
                        && call.cachedTokens() == 33
                        && call.totalTokens() == 33
                        && call.error() == null));
    }

    @Test
    void recordsFailureWhenResolverThrows() {
        ModelTrafficService traffic = mock(ModelTrafficService.class);
        TrafficModel model = new TrafficModel(
                () -> {
                    throw new IllegalStateException("没有可用的语言模型配置，请先在管理控制台配置");
                },
                traffic, "chat", "chat-agent", "default");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> model.stream(List.of(), List.of(), null));
        assertEquals("没有可用的语言模型配置，请先在管理控制台配置", error.getMessage());
        verify(traffic).recordFailure(eq("chat"), isNull(), eq("default"), eq("chat-agent"),
                eq(0L), contains("没有可用的语言模型配置"));
    }

    @Test
    void recordsFailureOnceWhenDelegateErrors() {
        ModelTrafficService traffic = mock(ModelTrafficService.class);
        AtomicInteger subscriptions = new AtomicInteger();
        Model failing = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                subscriptions.incrementAndGet();
                return Flux.error(new RuntimeException("上游返回 401"));
            }

            @Override
            public String getModelName() {
                return "deepseek-chat";
            }
        };
        TrafficModel model = new TrafficModel(
                () -> new ModelTarget("default", "deepseek-chat", failing),
                traffic, "chat", "chat-agent", "default");

        assertThrows(RuntimeException.class, () -> model.stream(List.of(), List.of(), null).blockLast());
        assertEquals(1, subscriptions.get());
        verify(traffic).recordFailure(eq("chat"), eq("default"), eq("deepseek-chat"), eq("chat-agent"),
                anyLong(), contains("401"));
        verify(traffic, never()).record(any(TrafficCall.class));
    }

    @Test
    void fallsBackToSeedNameWhenUnresolvable() {
        ModelTrafficService traffic = mock(ModelTrafficService.class);
        TrafficModel model = new TrafficModel(
                () -> {
                    throw new IllegalStateException("未配置");
                },
                traffic, "chat", "chat-agent", "seed-name");

        assertEquals("seed-name", model.getModelName());
        assertEquals(32_000, model.getContextWindowSize());
        verify(traffic, never()).record(any(TrafficCall.class));
    }
}
