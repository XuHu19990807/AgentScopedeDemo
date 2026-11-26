package com.cetc28.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型调用服务 — 封装 ReActAgent 提供便捷 LLM 调用
 *
 * Model 接口只提供了 stream() 没有 call()，
 * 需要通过 ReActAgent 包装来实现同步调用。
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private ReActAgent defaultAgent;

    public ModelService(Model chatModel) {
        // ReActAgent 是 Model 的标准包装，提供 call() 和 streamEvents()
        this.defaultAgent = ReActAgent.builder()
                .name("Assistant")
                .model(chatModel)
                .build();
    }

    /**
     * 同步调用 LLM
     */
    public String call(String prompt) {
        Msg response = defaultAgent.call(List.of(Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build())).block();
        return response != null ? response.getTextContent() : "";
    }

    /**
     * 流式调用
     */
    public Flux<String> callStream(String prompt) {
        return Flux.create(sink -> {
            defaultAgent.streamEvents(Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(prompt).build())
                            .build())
                    .doOnNext(event -> {
                        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA
                                && event instanceof TextBlockDeltaEvent delta) {
                            String text = delta.getDelta();
                            if (text != null && !text.isEmpty()) sink.next(text);
                        }
                    })
                    .doOnComplete(sink::complete)
                    .doOnError(sink::error)
                    .subscribe();
        });
    }
}
