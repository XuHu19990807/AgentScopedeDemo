package com.cetc28.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 对话入口：SSE 流式生成 + 停止。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final Executor executor;

    @Value("${app.sse.timeout-seconds:120}")
    private long timeoutSeconds = 120;

    public ChatController(ChatService chatService,
                          @Qualifier("agentTaskExecutor") Executor executor) {
        this.chatService = chatService;
        this.executor = executor;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatStreamRequest request, HttpServletRequest http) {
        String userId = UserContext.from(http);
        if (request.getConversationId() != null && !request.getConversationId().isBlank()
                && chatService.isBusy(request.getConversationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上一条消息还在生成中");
        }

        SseEmitter emitter = new SseEmitter(Math.max(30, timeoutSeconds) * 1000L);
        emitter.onTimeout(() -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already completed
            }
        });
        emitter.onError(error -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already completed
            }
        });
        executor.execute(() -> chatService.start(request, userId, emitter));
        return emitter;
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String runId = body.get("runId");
        boolean stopped = chatService.stop(runId, UserContext.from(http));
        return Map.of("success", stopped);
    }
}
