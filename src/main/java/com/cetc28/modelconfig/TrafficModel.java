package com.cetc28.modelconfig;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 动态模型代理：每次调用时才解析当前生效的模型配置，并把调用量、token、延迟、错误上报给
 * {@link ModelTrafficService}。
 *
 * <p>Agent 在启动时就把 {@link Model} 绑定到 HarnessAgent 上，因此这里不能在构造期固定委托对象，
 * 必须通过 {@link Supplier} 在每次 stream 时再解析——这样后台改动配置后无需重启即可生效。
 */
public class TrafficModel implements Model {

    private final Supplier<ModelTarget> resolver;
    private final ModelTrafficService traffic;
    private final String kind;
    private final String agentId;
    private final String fallbackName;

    public TrafficModel(Supplier<ModelTarget> resolver, ModelTrafficService traffic,
                        String kind, String agentId, String fallbackName) {
        this.resolver = resolver;
        this.traffic = traffic;
        this.kind = kind == null ? "chat" : kind;
        this.agentId = agentId;
        this.fallbackName = fallbackName == null ? "unknown" : fallbackName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ModelTarget target;
        long start = System.nanoTime();
        try {
            target = resolver.get();
        } catch (RuntimeException e) {
            traffic.recordFailure(kind, null, fallbackName, agentId, 0, e.getMessage());
            throw e;
        }
        AtomicReference<ChatUsage> lastUsage = new AtomicReference<>(new ChatUsage(0, 0, 0));
        AtomicBoolean recorded = new AtomicBoolean(false);
        return target.model().stream(messages, tools, options)
                .doOnNext(response -> {
                    if (response != null && response.getUsage() != null) {
                        lastUsage.set(response.getUsage());
                    }
                })
                .doOnError(error -> {
                    if (recorded.compareAndSet(false, true)) {
                        traffic.recordFailure(kind, target.profileId(), target.modelName(), agentId,
                                elapsedMs(start), rootMessage(error));
                    }
                })
                .doFinally(signal -> {
                    if (signal != SignalType.ON_COMPLETE) return;
                    if (!recorded.compareAndSet(false, true)) return;
                    ChatUsage usage = lastUsage.get();
                    traffic.record(TrafficCall.ok(kind, target.profileId(), target.modelName(), agentId,
                            usage == null ? 0 : usage.getInputTokens(),
                            usage == null ? 0 : usage.getOutputTokens(),
                            usage == null ? 0 : usage.getCachedTokens(),
                            elapsedMs(start)));
                });
    }

    @Override
    public String getModelName() {
        try {
            ModelTarget target = resolver.get();
            return target == null ? fallbackName : target.modelName();
        } catch (RuntimeException e) {
            return fallbackName;
        }
    }

    @Override
    public int getContextWindowSize() {
        try {
            ModelTarget target = resolver.get();
            return target == null ? 32_000 : target.model().getContextWindowSize();
        } catch (RuntimeException e) {
            return 32_000;
        }
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        ModelTarget target = safeResolve();
        return target == null || target.model().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        ModelTarget target = safeResolve();
        return target == null || target.model().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public boolean supportsToolChoiceSpecific() {
        ModelTarget target = safeResolve();
        return target == null || target.model().supportsToolChoiceSpecific();
    }

    private ModelTarget safeResolve() {
        try {
            return resolver.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
