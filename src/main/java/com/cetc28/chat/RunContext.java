package com.cetc28.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;

/**
 * 一次生成运行的上下文。所有可变状态都挂在 run 上，
 * 不再使用全局静态的 emitter 状态（原 StreamingContext 会导致多用户互相打断）。
 */
public class RunContext {

    private final String runId;
    private final String conversationId;
    private final String userId;
    private final ChatMode mode;
    private final SseEmitter emitter;
    private final long startedAt = System.currentTimeMillis();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private volatile Disposable subscription;
    private volatile ScheduledFuture<?> heartbeat;

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final List<ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());
    /** callId → 记录，支持并行工具调用按 id 精确定位（不再依赖"最后一个 running"） */
    private final Map<String, ToolCallRecord> toolsById = Collections.synchronizedMap(new LinkedHashMap<>());
    /**
     * 刚结束、还没测到"之后等待模型"时长的工具记录。
     * 下一个可见事件（text/thinking/tool_call…）到达时结算，收尾时兜底结算。
     */
    private volatile ToolCallRecord awaitingWait;
    private final Map<String, Integer> usage = new LinkedHashMap<>();

    /** 本轮用户原文，用于收尾时判断“是否需要把答复导出成文件”。 */
    private volatile String userText = "";

    /** 本轮选择的模型配置 id 与实际模型名（用于 meta/usage/消息持久化）。 */
    private volatile String modelId = "";
    private volatile String modelName = "";
    private volatile int modelCalls = 0;

    /** 本轮注入的知识库引用（随消息一起持久化，便于事后核对答案依据）。 */
    private final List<com.cetc28.rag.Citation> citations = Collections.synchronizedList(new ArrayList<>());

    /** 本轮地图指令（replace/view/clear），随助手消息持久化以支持历史回放。 */
    private final List<com.cetc28.map.MapCommand> mapCommands = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger mapSeq = new AtomicInteger();

    public RunContext(String conversationId, String userId, ChatMode mode, SseEmitter emitter) {
        this.runId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.conversationId = conversationId;
        this.userId = userId;
        this.mode = mode;
        this.emitter = emitter;
    }

    public String getRunId() { return runId; }
    public String getConversationId() { return conversationId; }
    public String getUserId() { return userId; }
    public ChatMode getMode() { return mode; }
    public SseEmitter getEmitter() { return emitter; }
    public long getStartedAt() { return startedAt; }

    public Disposable getSubscription() { return subscription; }
    public void setSubscription(Disposable subscription) { this.subscription = subscription; }

    public ScheduledFuture<?> getHeartbeat() { return heartbeat; }
    public void setHeartbeat(ScheduledFuture<?> heartbeat) { this.heartbeat = heartbeat; }

    public AtomicBoolean cancelled() { return cancelled; }
    public boolean isCancelled() { return cancelled.get(); }

    /** @return true 表示本次调用负责结束该 run */
    public boolean markFinished() { return finished.compareAndSet(false, true); }
    public boolean isFinished() { return finished.get(); }

    public StringBuilder content() { return content; }
    public StringBuilder reasoning() { return reasoning; }
    public List<ToolCallRecord> toolCalls() { return toolCalls; }
    public Map<String, ToolCallRecord> toolsById() { return toolsById; }

    public ToolCallRecord getAwaitingWait() { return awaitingWait; }
    public void setAwaitingWait(ToolCallRecord record) { this.awaitingWait = record; }

    public Map<String, Integer> usage() { return usage; }

    /**
     * 累加一次模型调用的 token 用量（来自 MODEL_CALL_END 事件）。
     * 每个模型调用都会单独触发，因此多轮工具循环会累计成整轮总量。
     */
    public synchronized void addUsage(int inputTokens, int outputTokens, int cachedTokens, int totalTokens) {
        modelCalls++;
        mergeUsage("inputTokens", inputTokens);
        mergeUsage("outputTokens", outputTokens);
        mergeUsage("cachedTokens", cachedTokens);
        mergeUsage("totalTokens", totalTokens);
        usage.put("modelCalls", modelCalls);
    }

    private void mergeUsage(String key, int delta) {
        if (delta <= 0) return;
        usage.merge(key, delta, Integer::sum);
    }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId == null ? "" : modelId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName == null ? "" : modelName; }

    public int getModelCalls() { return modelCalls; }

    public String getUserText() { return userText; }
    public void setUserText(String userText) { this.userText = userText == null ? "" : userText; }

    public List<com.cetc28.map.MapCommand> mapCommands() { return mapCommands; }

    public int nextMapSeq() { return mapSeq.incrementAndGet(); }

    public void addMapCommand(com.cetc28.map.MapCommand command) {
        if (command != null) mapCommands.add(command);
    }

    /** 一轮回答通常只保留最终一次 replace；清空后加入，避免历史回放时重复叠加。 */
    public void replaceMapCommands(com.cetc28.map.MapCommand command) {
        mapCommands.clear();
        if (command != null) mapCommands.add(command);
    }

    public List<com.cetc28.rag.Citation> citations() { return citations; }
    public void setCitations(List<com.cetc28.rag.Citation> values) {
        citations.clear();
        if (values != null) citations.addAll(values);
    }

    /** 中断上游流；幂等 */
    public void dispose() {
        cancelled.set(true);
        Disposable d = subscription;
        if (d != null && !d.isDisposed()) {
            try {
                d.dispose();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
