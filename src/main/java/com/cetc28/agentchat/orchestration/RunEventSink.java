package com.cetc28.agentchat.orchestration;

import com.cetc28.dto.StreamEvent;
import reactor.core.publisher.FluxSink;

/**
 * 运行期事件出口：编排工具把 worker 的开始/结束/错误写入这条通道，
 * 由 {@link OrchestrationService} 合并进同一条 SSE 流。
 *
 * <p>使用 {@link FluxSink} 而不是共享的 {@code Sinks.Many}：{@code Flux.create}
 * 会对多线程 {@code next()} 做串行化，worker 从各自线程并发写也不会出现
 * {@code FAIL_NON_SERIALIZED} 导致事件流永远不完成。
 */
public final class RunEventSink {

    public static final String KEY = "runEventSink";

    private final FluxSink<StreamEvent> sink;

    public RunEventSink(FluxSink<StreamEvent> sink) {
        this.sink = sink;
    }

    public void emit(StreamEvent event) {
        if (event != null) {
            sink.next(event);
        }
    }

    public void emit(String type, Object data) {
        emit(new StreamEvent(type, data));
    }
}
