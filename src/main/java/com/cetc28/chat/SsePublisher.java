package com.cetc28.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 输出封装：统一序列化、心跳与关闭，发送失败视为客户端断开。
 */
@Component
public class SsePublisher {

    private static final Logger log = LoggerFactory.getLogger(SsePublisher.class);

    private final ObjectMapper mapper;

    public SsePublisher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public boolean send(SseEmitter emitter, String event, Object data) {
        if (emitter == null) return false;
        try {
            String payload = data instanceof String raw ? raw : mapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(payload));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）event={}: {}", event, e.getMessage());
            return false;
        }
    }

    public boolean sendMap(SseEmitter emitter, String event, Map<String, Object> data) {
        return send(emitter, event, data);
    }

    public void ping(SseEmitter emitter) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    public void complete(SseEmitter emitter) {
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed
        }
    }
}
