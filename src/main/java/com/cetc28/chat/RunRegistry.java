package com.cetc28.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行注册表：runId -> RunContext，以及 conversationId -> runId 的互斥索引。
 * 同一会话同一时刻只允许一个生成任务。
 */
@Component
public class RunRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);

    private final Map<String, RunContext> byRun = new ConcurrentHashMap<>();
    private final Map<String, String> byConversation = new ConcurrentHashMap<>();

    public RunContext register(String conversationId, String userId, ChatMode mode, SseEmitter emitter) {
        RunContext context = new RunContext(conversationId, userId, mode, emitter);
        byRun.put(context.getRunId(), context);
        byConversation.put(conversationId, context.getRunId());
        return context;
    }

    public RunContext get(String runId) {
        if (runId == null) return null;
        return byRun.get(runId);
    }

    public RunContext activeFor(String conversationId) {
        if (conversationId == null) return null;
        String runId = byConversation.get(conversationId);
        return runId == null ? null : byRun.get(runId);
    }

    public boolean isBusy(String conversationId) {
        RunContext context = activeFor(conversationId);
        return context != null && !context.isFinished();
    }

    public void unregister(RunContext context) {
        if (context == null) return;
        byRun.remove(context.getRunId());
        byConversation.remove(context.getConversationId(), context.getRunId());
    }

    /** 取消运行：标记 + 中断上游 */
    public boolean cancel(String runId) {
        RunContext context = get(runId);
        if (context == null) return false;
        log.info("取消运行 {} (conversation={})", runId, context.getConversationId());
        context.dispose();
        return true;
    }

    public int activeCount() {
        return byRun.size();
    }
}
