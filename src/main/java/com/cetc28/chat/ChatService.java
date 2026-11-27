package com.cetc28.chat;

import com.cetc28.agentchat.base.AgentBase;
import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.dto.StreamEvent;
import com.cetc28.export.ExportFormat;
import com.cetc28.identity.KnowledgeScopeService;
import com.cetc28.identity.UserProfileService;
import com.cetc28.memory.UserMemoryService;
import com.cetc28.modelconfig.ChatProfile;
import com.cetc28.modelconfig.ModelRuntime;
import com.cetc28.rag.KnowledgeRetriever;
import com.cetc28.rag.RagContextBuilder;
import com.cetc28.rag.RagProperties;
import com.cetc28.rag.RetrievalResult;
import com.cetc28.agentchat.orchestration.OrchestrationService;
import com.cetc28.map.MapCommand;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 对话生成服务：模式路由 → 订阅 Agent 事件 → SSE 输出 → 持久化。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int TOOL_PREVIEW_LIMIT = 4000;
    private static final String DEFAULT_FILE_PROMPT = "请阅读我上传的文件，概括内容并回答我的问题。";

    private final AgentRegistry agentRegistry;
    private final ConversationStore store;
    private final RunRegistry runRegistry;
    private final SsePublisher publisher;
    private final OrchestrationService coordinationService;
    private final UserMemoryService memoryService;
    private final ChatFileService fileService;
    private final FileContextBuilder fileContextBuilder;
    private final GeneratedFileService generatedFileService;
    private final ChatOutputRegistry outputRegistry;
    private final ScheduledExecutorService heartbeatScheduler;
    private final KnowledgeRetriever knowledgeRetriever;
    private final KnowledgeScopeService knowledgeScopeService;
    private final UserProfileService userProfileService;
    private final RagContextBuilder ragContextBuilder;
    private final RagProperties ragProperties;
    private final ModelRuntime modelRuntime;

    @Value("${chat.heartbeat-seconds:15}")
    private long heartbeatSeconds = 15;

    /**
     * 事件空闲上限：超过该时长没有任何 SSE 事件，就主动收尾。
     * <p>覆盖三种情况：模型/工具卡死、记忆 flush 卡死、编排 side 事件流未完成。
     * 正常流式输出时每个 delta 都会重置计时，不会误杀。
     */
    @Value("${app.sse.idle-timeout-seconds:180}")
    private long idleTimeoutSeconds = 180;

    @Value("${chat.title-max-length:30}")
    private int titleMaxLength = 30;

    @Value("${chat.max-content-length:32000}")
    private int maxContentLength = 32000;

    /** 用户明确要求“保存/导出为文件”但模型没调工具时，是否自动把答复转成文件。 */
    @Value("${chat.files.export-fallback-enabled:true}")
    private boolean exportFallbackEnabled = true;

    @Value("${agentscope.model.openai.chat-model:deepseek-chat}")
    private String chatModelName;

    @Value("${agentscope.model.reasoner.chat-model:deepseek-reasoner}")
    private String reasonerModelName;

    public ChatService(AgentRegistry agentRegistry,
                       ConversationStore store,
                       RunRegistry runRegistry,
                       SsePublisher publisher,
                       OrchestrationService coordinationService,
                       UserMemoryService memoryService,
                       ChatFileService fileService,
                       FileContextBuilder fileContextBuilder,
                       GeneratedFileService generatedFileService,
                       ChatOutputRegistry outputRegistry,
                       KnowledgeRetriever knowledgeRetriever,
                       KnowledgeScopeService knowledgeScopeService,
                       UserProfileService userProfileService,
                       RagContextBuilder ragContextBuilder,
                       RagProperties ragProperties,
                       ModelRuntime modelRuntime,
                       @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.agentRegistry = agentRegistry;
        this.store = store;
        this.runRegistry = runRegistry;
        this.publisher = publisher;
        this.coordinationService = coordinationService;
        this.memoryService = memoryService;
        this.fileService = fileService;
        this.fileContextBuilder = fileContextBuilder;
        this.generatedFileService = generatedFileService;
        this.outputRegistry = outputRegistry;
        this.knowledgeRetriever = knowledgeRetriever;
        this.knowledgeScopeService = knowledgeScopeService;
        this.userProfileService = userProfileService;
        this.ragContextBuilder = ragContextBuilder;
        this.ragProperties = ragProperties;
        this.modelRuntime = modelRuntime;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    public boolean isBusy(String conversationId) {
        return runRegistry.isBusy(conversationId);
    }

    /**
     * 启动一次生成。所有异常都通过 SSE error 事件返回，不向调用线程抛出。
     */
    public void start(ChatStreamRequest request, String userId, SseEmitter emitter) {
        RunContext run = null;
        try {
            String typedContent = request.getContent() == null ? "" : request.getContent().trim();
            if (typedContent.length() > maxContentLength) {
                sendError(emitter, "message_too_long", "消息超过 " + maxContentLength + " 字符");
                publisher.complete(emitter);
                return;
            }

            List<ChatFileService.LoadedFile> files;
            try {
                files = fileService.resolve(request.getFileIds(), userId);
            } catch (ResponseStatusException e) {
                sendError(emitter, "attachment_invalid",
                        e.getReason() == null ? "附件无效或已过期" : e.getReason());
                publisher.complete(emitter);
                return;
            }
            if (typedContent.isEmpty() && files.isEmpty()) {
                sendError(emitter, "empty_message", "消息不能为空");
                publisher.complete(emitter);
                return;
            }

            String content = typedContent.isEmpty() ? DEFAULT_FILE_PROMPT : typedContent;
            ChatMode requestedMode = ChatMode.from(request.getMode());
            // 附件内容已直接注入上下文；orchestrate 的 worker 无法访问对话附件，降级为直答
            ChatMode mode = requestedMode == ChatMode.ORCHESTRATE && !files.isEmpty()
                    ? ChatMode.CHAT : requestedMode;
            if (mode != requestedMode) {
                log.info("附件消息从 {} 降级为 {} 直答", requestedMode.value(), mode.value());
            }
            // 并行 + 汇总仅对编排模式生效
            boolean parallel = Boolean.TRUE.equals(request.getParallel()) && mode == ChatMode.ORCHESTRATE;

            Conversation conversation = resolveConversation(request, userId);
            ModelSelection selection = resolveModelSelection(request, conversation);

            if (request.getRegenerateFromSeq() != null && request.getRegenerateFromSeq() >= 0) {
                store.truncate(conversation.getId(), request.getRegenerateFromSeq());
            }

            List<ChatAttachment> attachments = files.stream()
                    .map(loaded -> ChatAttachment.of(loaded.meta()))
                    .collect(Collectors.toList());
            ChatMessage userMessage = ChatMessage.builder()
                    .role(ChatMessage.ROLE_USER)
                    .content(content)
                    .attachments(attachments)
                    .status(ChatMessage.STATUS_COMPLETED)
                    .mode(mode.value())
                    .model(selection.modelName())
                    .modelId(selection.profileId())
                    .build();
            store.appendMessage(conversation.getId(), userMessage);
            // 先读取本轮之前的长期画像/记忆，再写入本轮内容：避免用户刚说的话立刻被当成
            // “历史记忆”重复注入，也避免问句被误存为偏好。
            String memoryContext = userProfileService.buildContext(userId, content);
            memoryService.rememberIfRequested(userId, content);
            if (conversation.getTitle() == null || conversation.getTitle().isBlank()) {
                String titleSource = typedContent.isEmpty() && !attachments.isEmpty()
                        ? attachments.get(0).getName()
                        : typedContent;
                store.rename(conversation.getId(), titleOf(titleSource));
            }
            store.updateMode(conversation.getId(), mode.value());
            if (selection.profileId() != null && !selection.profileId().equals(conversation.getModel())) {
                store.updateModel(conversation.getId(), selection.profileId());
            }
            if (!files.isEmpty()) {
                fileService.bind(files, conversation.getId());
            }

            run = runRegistry.register(conversation.getId(), userId, mode, emitter);
            run.setUserText(content);
            run.setModelId(selection.profileId());
            run.setModelName(selection.modelName());
            // 上一轮若异常中断，注册表可能残留文件；新一轮开始先清空
            outputRegistry.clear(conversation.getId());
            startHeartbeat(run);

            String fileContext = fileContextBuilder.build(content, files);
            if (!fileContext.isBlank()) {
                log.info("文件上下文注入: files={}, contextChars={}", files.size(), fileContext.length());
            }
            // 知识库检索在 meta 之前完成：失败/为空都不影响对话，只是不带引用
            KnowledgeContext knowledge = retrieveKnowledge(content, files, run, userId);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("conversationId", conversation.getId());
            meta.put("runId", run.getRunId());
            meta.put("userSeq", userMessage.getSeq());
            meta.put("mode", mode.value());
            meta.put("parallel", parallel);
            meta.put("attachments", attachments);
            meta.put("modelId", selection.profileId());
            meta.put("modelName", selection.modelName());
            if (selection.fallbackReason() != null) {
                meta.put("modelFallbackReason", selection.fallbackReason());
            }
            publisher.send(emitter, "meta", meta);
            sendSources(emitter, knowledge);

            Flux<StreamEvent> events = eventsFor(mode, content, conversation.getId(), userId,
                    fileContext, memoryContext, knowledge, parallel, selection.profileId());
            RunContext finalRun = run;
            Disposable disposable = events
                    .doOnNext(event -> onEvent(finalRun, event))
                    .doOnError(error -> onError(finalRun, error))
                    .onErrorResume(error -> Flux.empty())
                    // 空闲看门狗：任何一段（模型、工具、记忆 flush、编排 side 流）
                    // 超过 idleTimeoutSeconds 没有事件，就强制收尾，避免永久挂起。
                    .timeout(Duration.ofSeconds(idleTimeoutSeconds), Flux.defer(() -> {
                        log.warn("对话空闲超时，强制收尾: run={}, timeout={}s",
                                finalRun.getRunId(), idleTimeoutSeconds);
                        finish(finalRun, "timeout", null);
                        return Flux.empty();
                    }))
                    .doOnComplete(() -> finish(finalRun, ChatMessage.STATUS_COMPLETED, null))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            finish(finalRun, ChatMessage.STATUS_CANCELLED, null);
                        }
                    })
                    .subscribe();
            run.setSubscription(disposable);
            log.info("对话生成开始: conversation={}, run={}, mode={}, files={}, user={}",
                    conversation.getId(), run.getRunId(), mode.value(), files.size(), userId);
        } catch (Exception e) {
            log.error("对话生成启动失败: {}", e.getMessage(), e);
            if (run != null) {
                finish(run, ChatMessage.STATUS_ERROR, e);
            } else {
                sendError(emitter, "start_failed", e.getMessage() == null ? "启动失败" : e.getMessage());
                publisher.complete(emitter);
            }
        }
    }

    /** 停止生成；返回 false 表示 run 不存在或不属于当前用户 */
    public boolean stop(String runId, String userId) {
        RunContext run = runRegistry.get(runId);
        if (run == null) return false;
        if (userId != null && !userId.equals(run.getUserId())) return false;
        // 先按 (userId, conversationId) 中断框架里的在途 ReAct 循环，再释放 SSE 订阅；
        // 只 dispose() 会留下继续跑完的模型调用，工具副作用与 token 都收不回来。
        for (AgentBase agent : agentRegistry.getEnabledAgents()) {
            try {
                agent.interrupt(run.getUserId(), run.getConversationId());
            } catch (Exception e) {
                log.debug("interrupt {} failed: {}", agent.getAgentId(), e.getMessage());
            }
        }
        run.dispose();
        finish(run, ChatMessage.STATUS_CANCELLED, null);
        return true;
    }

    private Conversation resolveConversation(ChatStreamRequest request, String userId) {
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<Conversation> existing = store.get(conversationId, userId);
            if (existing.isEmpty()) {
                throw new IllegalArgumentException("会话不存在或无权访问");
            }
            return existing.get();
        }
        return store.create(userId);
    }

    private Flux<StreamEvent> eventsFor(ChatMode mode, String content, String conversationId,
                                        String userId, String fileContext, String memoryContext,
                                        KnowledgeContext knowledge, boolean parallel,
                                        String modelProfileId) {
        if (mode == ChatMode.ORCHESTRATE) {
            String merged = (fileContext == null || fileContext.isBlank())
                    ? content
                    : fileContext + "\n\n[用户问题]\n" + content;
            // 参考材料统一注入 RuntimeContext：orchestrator 与 worker 共享同一份证据，
            // 由 dispatch_agents 工具负责转发，避免子智能体各自重复检索。
            StringBuilder orchestrationContext = new StringBuilder();
            if (memoryContext != null && !memoryContext.isBlank()) {
                orchestrationContext.append("[用户偏好与画像]\n").append(memoryContext);
            }
            if (knowledge != null && !knowledge.injected().isEmpty()) {
                if (orchestrationContext.length() > 0) orchestrationContext.append("\n\n");
                orchestrationContext.append(knowledge.injected().promptBlock());
            }
            String context = orchestrationContext.length() == 0 ? null : orchestrationContext.toString();
            return coordinationService.stream(merged, userId, conversationId, context, modelProfileId);
        }
        String agentId = mode == ChatMode.DEEP ? "deep" : "chat";
        Optional<AgentBase> agent = agentRegistry.getAgent(agentId);
        if (agent.isEmpty()) {
            return Flux.just(new StreamEvent("error", Map.of("message", agentId + " Agent 未注册")));
        }
        StringBuilder context = new StringBuilder();
        if (memoryContext != null && !memoryContext.isBlank()) {
            context.append("[用户偏好与画像]\n").append(memoryContext);
        }
        if (knowledge != null && !knowledge.injected().isEmpty()) {
            if (context.length() > 0) context.append("\n\n");
            context.append(knowledge.injected().promptBlock());
        }
        if (fileContext != null && !fileContext.isBlank()) {
            if (context.length() > 0) context.append("\n\n");
            context.append(fileContext);
        }
        String injected = context.length() == 0 ? null : context.toString();
        HarnessAgent target = agent.get().variant(modelProfileId);
        return agent.get().processRichStreamWithContext(target, content, userId, conversationId, injected);
    }

    /** 检索结果 + 组装后的注入上下文（二者都要用：注入给模型，原始结果给编排器） */
    private record KnowledgeContext(RetrievalResult result, RagContextBuilder.InjectedContext injected) {}

    /**
     * 对话前的知识库检索。
     *
     * <p>设计约束：检索失败永远不能阻断对话 —— 知识库只是"可选证据"，
     * 不是对话的前置依赖。因此这里吞掉所有异常并降级为空上下文。
     */
    private KnowledgeContext retrieveKnowledge(String content, List<ChatFileService.LoadedFile> files,
                                              RunContext run, String userId) {
        if (!ragProperties.isEnabled() || !ragProperties.getInject().isEnabled()) {
            return new KnowledgeContext(RetrievalResult.empty(content, "RAG 已关闭"),
                    RagContextBuilder.InjectedContext.none(null));
        }
        if (ragProperties.getInject().isSkipWhenAttachments() && files != null && !files.isEmpty()) {
            log.info("本轮带 {} 个附件，跳过知识库自动注入（附件内容优先）", files.size());
            return new KnowledgeContext(RetrievalResult.empty(content, null),
                    RagContextBuilder.InjectedContext.none(null));
        }
        // 角色/用户授权决定可见知识域：null=不限（管理员），空集合=无授权（直接跳过）
        Set<String> domains = knowledgeScopeService.filterDomains(userId);
        if (domains != null && domains.isEmpty()) {
            log.info("用户 {} 没有知识域授权，跳过知识库注入", userId);
            return new KnowledgeContext(RetrievalResult.empty(content, "当前账号没有可用知识域授权"),
                    RagContextBuilder.InjectedContext.none(null));
        }
        try {
            RetrievalResult result = knowledgeRetriever.retrieveInDomains(
                    content, KnowledgeRetriever.Mode.HYBRID, -1, null, domains);
            RagContextBuilder.InjectedContext injected = ragContextBuilder.build(result);
            if (!injected.isEmpty()) {
                run.setCitations(injected.citations());
                log.info("知识库注入: query={}, hits={}, chars={}, degraded={}",
                        content, injected.citations().size(),
                        injected.promptBlock() == null ? 0 : injected.promptBlock().length(),
                        injected.degraded());
            } else {
                log.info("知识库无可用片段: query={}, degraded={}, reason={}",
                        content, result.degraded(), result.degradedReason());
            }
            return new KnowledgeContext(result, injected);
        } catch (Exception e) {
            log.warn("知识库检索失败（本次对话不带引用继续）：{}", e.getMessage());
            return new KnowledgeContext(RetrievalResult.empty(content, "检索异常: " + e.getMessage()),
                    RagContextBuilder.InjectedContext.none("检索异常"));
        }
    }

    /** 把引用推给前端；没有引用且没降级时不发事件，避免噪声 */
    private void sendSources(SseEmitter emitter, KnowledgeContext knowledge) {
        if (knowledge == null) return;
        RagContextBuilder.InjectedContext injected = knowledge.injected();
        if (injected.isEmpty() && !injected.degraded()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("citations", injected.citations());
        payload.put("degraded", injected.degraded());
        if (injected.degradedReason() != null) payload.put("reason", injected.degradedReason());
        payload.put("tookMs", knowledge.result() == null ? 0 : knowledge.result().tookMs());
        publisher.send(emitter, "sources", payload);
    }

    private void onEvent(RunContext run, StreamEvent event) {
        boolean agentEnded = false;
        synchronized (run) {
            if (run.isCancelled() || run.isFinished()) return;
            String type = event.type();
            Map<?, ?> data = event.data() instanceof Map<?, ?> map ? map : Map.of();
            // 下一个"可见进展"到达时，结算上一个工具"结果返回后等待模型"的时长
            if (isProgressEvent(type)) recordWait(run);
            switch (type) {
                case "text" -> {
                    String delta = str(data, "delta");
                    if (!delta.isEmpty()) {
                        run.content().append(delta);
                        publisher.send(run.getEmitter(), "delta", Map.of("text", delta));
                    }
                }
                case "thinking" -> {
                    String delta = str(data, "delta");
                    if (!delta.isEmpty()) {
                        run.reasoning().append(delta);
                        publisher.send(run.getEmitter(), "reasoning", Map.of("text", delta));
                    }
                }
                case "tool_call" -> openTool(run, str(data, "callId"), str(data, "name"));
                case "tool_call_end" -> markToolCallEnd(run, str(data, "callId"));
                case "tool_result_start" -> markToolResultStart(run, str(data, "callId"));
                case "tool_result" -> {
                    String callId = str(data, "callId");
                    markToolResultStart(run, callId);
                    appendToolResult(run, callId, str(data, "delta"));
                }
                case "tool_result_end" -> closeTool(run, str(data, "callId"), str(data, "state"), null);
                // 编排过程事件：只透传给前端展示"哪个 Agent 在执行"，不计入正文/工具记录
                case "agent_start", "agent_result" -> publisher.send(run.getEmitter(), type, toStringMap(data));
                // 官方 AGENT_END：模型/工具循环已经结束，后面只剩记忆 flush / session offload。
                // 先记下来，出了同步块再收尾，避免在锁内关闭 SSE。
                case "agent_end" -> agentEnded = true;
                // 每次模型调用的用量：累加进本轮统计，并实时推给前端
                case "model_usage" -> {
                    int inputTokens = num(data, "inputTokens");
                    int outputTokens = num(data, "outputTokens");
                    int cachedTokens = num(data, "cachedTokens");
                    int totalTokens = num(data, "totalTokens");
                    run.addUsage(inputTokens, outputTokens, cachedTokens, totalTokens);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("inputTokens", inputTokens);
                    payload.put("outputTokens", outputTokens);
                    payload.put("cachedTokens", cachedTokens);
                    payload.put("totalTokens", totalTokens);
                    payload.put("modelName", run.getModelName());
                    payload.put("modelCalls", run.getModelCalls());
                    publisher.send(run.getEmitter(), "model_usage", payload);
                }
                case "error" -> {
                    String message = str(data, "message");
                    throw new IllegalStateException(message.isEmpty() ? "生成失败" : message);
                }
                default -> {
                    // 其余事件（model_call 等）暂不直接输出
                }
            }
        }
        if (agentEnded) {
            // 关键：不再等记忆后处理。finish 会持久化消息、发 done、关闭 SSE；
            // 上游的 flush/offload 继续在后台执行，完成后 doOnComplete 因 markFinished 幂等空转。
            log.info("收到 AGENT_END，提前结束本轮（记忆后处理继续在后台执行）: run={}", run.getRunId());
            finish(run, ChatMessage.STATUS_COMPLETED, null);
        }
    }

    /** 会产生"用户可见进展"的事件：用于结算上一个工具的 waitMs */
    private boolean isProgressEvent(String type) {
        return switch (type) {
            case "text", "thinking", "tool_call", "tool_result_start" -> true;
            default -> false;
        };
    }

    private void onError(RunContext run, Throwable error) {
        log.warn("对话生成失败 run={}: {}", run.getRunId(), error.getMessage());
        finish(run, ChatMessage.STATUS_ERROR, error);
    }

    private void finish(RunContext run, String status, Throwable error) {
        if (run == null || !run.markFinished()) return;
        ScheduledFuture<?> heartbeat = run.getHeartbeat();
        if (heartbeat != null) heartbeat.cancel(false);

        String content;
        String reasoning;
        List<ToolCallRecord> tools;
        Map<String, Integer> usage;
        List<MapCommand> mapCommands;
        synchronized (run) {
            closeRunningTools(run, status);
            recordWait(run);
            content = run.content().toString();
            reasoning = run.reasoning().toString();
            tools = new ArrayList<>(run.toolCalls());
            usage = new LinkedHashMap<>(run.usage());
            mapCommands = new ArrayList<>(run.mapCommands());
        }

        long elapsedMs = Math.max(0, System.currentTimeMillis() - run.getStartedAt());
        long toolsWallMs = toolsWallMs(tools);

        List<ChatAttachment> outputs;
        try {
            outputs = collectOutputs(run, content);
        } catch (Exception e) {
            // 导出兜底失败不能影响收尾：否则 done 永远不会发送。
            log.warn("收集本轮输出文件失败: {}", e.getMessage(), e);
            outputs = List.of();
        }

        ChatMessage assistant = ChatMessage.builder()
                .role(ChatMessage.ROLE_ASSISTANT)
                .content(content)
                .reasoning(reasoning)
                .toolCalls(tools)
                .outputs(outputs)
                .mapCommands(mapCommands)
                .status(status)
                .error(error != null ? safeMessage(error) : null)
                .mode(run.getMode().value())
                .model(run.getModelName().isBlank() ? modelName(run.getMode()) : run.getModelName())
                .modelId(run.getModelId().isBlank() ? null : run.getModelId())
                .usage(usage)
                .sources(new ArrayList<>(run.citations()))
                .elapsedMs(elapsedMs)
                .toolsWallMs(toolsWallMs)
                .build();

        try {
            store.appendMessage(run.getConversationId(), assistant);
        } catch (Exception e) {
            log.error("保存助手消息失败 conversation={}: {}", run.getConversationId(), e.getMessage());
        }

        // 画像沉淀：只统计正常完成的一轮，避免失败/取消污染偏好统计
        if (ChatMessage.STATUS_COMPLETED.equals(status)) {
            try {
                userProfileService.recordTurn(run.getUserId(), run.getConversationId(),
                        run.getMode().value(), run.getModelName(), run.getUserText());
            } catch (Exception e) {
                log.debug("记录用户画像失败（忽略）：{}", e.getMessage());
            }
        }

        for (ChatAttachment output : assistant.getOutputs()) {
            publisher.send(run.getEmitter(), "file_created", fileEvent(output));
        }

        if (!usage.isEmpty()) {
            publisher.send(run.getEmitter(), "usage", usage);
        }
        if (error != null) {
            sendError(run.getEmitter(), "generation_failed", safeMessage(error));
        }
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("assistantSeq", assistant.getSeq());
        done.put("status", status);
        done.put("elapsedMs", elapsedMs);
        done.put("toolsWallMs", toolsWallMs);
        done.put("toolsCount", tools.size());
        try {
            publisher.send(run.getEmitter(), "done", done);
        } catch (Exception e) {
            log.warn("发送 done 事件失败: {}", e.getMessage());
        } finally {
            // 无论 done 是否成功，都必须释放 emitter 和 run 注册，避免下一次请求被判忙。
            publisher.complete(run.getEmitter());
            runRegistry.unregister(run);
        }

        log.info("对话生成结束: run={}, status={}, chars={}, tools={}, {}ms",
                run.getRunId(), status, content.length(), tools.size(),
                System.currentTimeMillis() - run.getStartedAt());
    }

    /** 汇总本轮生成的文件；模型没调工具但用户明确要求导出时，兜底把答复转成文件。 */
    private List<ChatAttachment> collectOutputs(RunContext run, String content) {
        List<ChatAttachment> outputs = new ArrayList<>(outputRegistry.drain(run.getConversationId()));
        if (!outputs.isEmpty() || !exportFallbackEnabled) return outputs;
        ExportFormat format = ExportFormat.fromUserIntent(run.getUserText());
        if (format == null || content == null || content.isBlank()) return outputs;
        try {
            ChatFile file = generatedFileService.exportAnswer(run.getConversationId(), content, format);
            outputs.add(ChatAttachment.of(file));
            log.info("答复已按用户要求导出: cid={}, ext={}, file={}",
                    run.getConversationId(), format.extension(), file.getName());
        } catch (Exception e) {
            log.warn("兜底导出失败 cid={}, ext={}: {}",
                    run.getConversationId(), format.extension(), e.getMessage());
        }
        return outputs;
    }

    private Map<String, Object> fileEvent(ChatAttachment file) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", file.getId());
        payload.put("name", file.getName());
        payload.put("size", file.getSize());
        payload.put("ext", extensionOf(file.getName()));
        payload.put("downloadUrl", "/api/chat/files/" + file.getId());
        return payload;
    }

    private String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    private void startHeartbeat(RunContext run) {
        if (heartbeatSeconds <= 0) return;
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (run.isFinished() || run.isCancelled()) return;
            publisher.ping(run.getEmitter());
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        run.setHeartbeat(future);
    }

    /**
     * 打开一次工具调用。callId 优先使用框架的 toolCallId：
     * 并行调用时按 id 精确配对，不再依赖"最后一个 running"的线性假设。
     */
    private void openTool(RunContext run, String rawCallId, String rawName) {
        String name = rawName == null || rawName.isBlank() ? "tool" : rawName;
        String server = name.contains("__") ? name.substring(0, name.indexOf("__")) : "local";
        String callId = rawCallId == null || rawCallId.isBlank()
                ? "t_" + (run.toolCalls().size() + 1)
                : rawCallId;
        // 模型可能在不同轮次复用同一个 callId，冲突时加后缀，保证一对一
        if (run.toolsById().containsKey(callId)) {
            callId = callId + "#" + (run.toolCalls().size() + 1);
        }
        long now = System.currentTimeMillis();
        ToolCallRecord record = ToolCallRecord.builder()
                .callId(callId)
                .server(server)
                .name(name)
                .status("running")
                .startedAt(now)
                .build();
        run.toolCalls().add(record);
        run.toolsById().put(callId, record);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("name", name);
        payload.put("server", server);
        payload.put("args", Map.of());
        payload.put("startedAt", now);
        publisher.send(run.getEmitter(), "tool_start", payload);
    }

    /** 模型生成完调用参数、工具开始真正执行 */
    private void markToolCallEnd(RunContext run, String callId) {
        ToolCallRecord record = findTool(run, callId);
        if (record == null || !"running".equals(record.getStatus())) return;
        // 结果都开始回传了才收到 call_end，说明该事件的边界晚于执行，忽略即可
        if (record.getResultStartedAt() > 0) return;
        long now = System.currentTimeMillis();
        if (record.getCallEndedAt() == 0) {
            record.setCallEndedAt(now);
            record.setCallMs(Math.max(0, now - record.getStartedAt()));
            // 模型已给完参数、工具开始执行：让卡片从"运行中…"变成"执行中…"
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("callId", record.getCallId());
            payload.put("callMs", record.getCallMs());
            publisher.send(run.getEmitter(), "tool_exec", payload);
        }
    }

    /**
     * 结果开始回传。
     * <p>实测（AgentScope 2.0 + MCP）：工具真正的执行发生在 result_start → result_end 之间，
     * 而 call_end → result_start 只有几毫秒（派发/参数绑定），因此这里只记录时间点，
     * 真正的"工具耗时"在 {@link #closeTool} 里从 call_end 算到 result_end。</p>
     */
    private void markToolResultStart(RunContext run, String callId) {
        ToolCallRecord record = findTool(run, callId);
        if (record != null) markToolResultStart(record);
    }

    private void markToolResultStart(ToolCallRecord record) {
        if (record.getResultStartedAt() == 0) record.setResultStartedAt(System.currentTimeMillis());
    }

    /**
     * 关闭一次工具调用：定格整卡窗口并推 tool_end。
     * 幂等：同一 callId 重复关闭不会重算耗时、不会重复推事件。
     */
    private void closeTool(RunContext run, String callId, String state, String forcedStatus) {
        ToolCallRecord record = findTool(run, callId);
        if (record == null || !"running".equals(record.getStatus())) return;

        long now = System.currentTimeMillis();
        markToolResultStart(record);
        // 工具耗时 = 模型给出调用参数(call_end) → 结果结束(result_end)，
        // 含派发、执行、结果回传；框架没发 call_end 时退回 call_start（会含参数生成时间）
        long execStart = record.getCallEndedAt() > 0 && record.getCallEndedAt() <= now
                ? record.getCallEndedAt()
                : record.getStartedAt();
        record.setToolMs(Math.max(0, now - execStart));
        record.setEndedAt(now);
        record.setResultMs(Math.max(0, now - record.getResultStartedAt()));
        record.setDurationMs(Math.max(0, now - record.getStartedAt()));
        record.setState(state == null ? "" : state);
        record.setStatus(forcedStatus != null ? forcedStatus : statusOf(state));

        // 只有正常结束的工具才需要统计"之后等待模型"；收尾时会兜底结算
        if ("ok".equals(record.getStatus()) && record.getWaitMs() == 0) {
            run.setAwaitingWait(record);
        }
        // 与 MCP 客户端日志的"耗时=xxxms"对照用：toolMs 应接近该值
        log.info("工具调用结束: run={}, call={}, name={}, callMs={}, toolMs={}, resultMs={}, state={}",
                run.getRunId(), record.getCallId(), record.getName(),
                record.getCallMs(), record.getToolMs(), record.getResultMs(), record.getState());
        publisher.send(run.getEmitter(), "tool_end", toolEndPayload(record));
    }

    /** 收尾时把仍然 running 的工具关掉，避免卡片永远停在"运行中" */
    private void closeRunningTools(RunContext run, String finishStatus) {
        String forced = switch (finishStatus == null ? "" : finishStatus) {
            case ChatMessage.STATUS_CANCELLED -> "cancelled";
            case ChatMessage.STATUS_ERROR -> "error";
            default -> "ok";
        };
        for (ToolCallRecord record : new ArrayList<>(run.toolCalls())) {
            if ("running".equals(record.getStatus())) {
                closeTool(run, record.getCallId(), record.getState(), forced);
            }
        }
    }

    /** 结算"结果返回后等待模型继续输出"的时长；下一个可见事件或收尾时调用，幂等 */
    private void recordWait(RunContext run) {
        ToolCallRecord pending = run.getAwaitingWait();
        if (pending == null) return;
        run.setAwaitingWait(null);
        long waitMs = Math.max(0, System.currentTimeMillis() - pending.getEndedAt());
        pending.setWaitMs(waitMs);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", pending.getCallId());
        payload.put("waitMs", waitMs);
        publisher.send(run.getEmitter(), "tool_wait", payload);
    }

    private Map<String, Object> toolEndPayload(ToolCallRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", record.getCallId());
        payload.put("name", record.getName());
        payload.put("server", record.getServer());
        payload.put("status", record.getStatus());
        payload.put("state", record.getState());
        payload.put("callMs", record.getCallMs());
        payload.put("toolMs", record.getToolMs());
        payload.put("resultMs", record.getResultMs());
        payload.put("waitMs", record.getWaitMs());
        payload.put("startedAt", record.getStartedAt());
        payload.put("endedAt", record.getEndedAt());
        // 兼容旧前端/旧数据
        payload.put("durationMs", record.getDurationMs());
        payload.put("preview", record.getPreview() == null ? "" : record.getPreview());
        payload.put("truncated", record.isTruncated());
        return payload;
    }

    private String statusOf(String state) {
        if (state == null || state.isBlank() || "SUCCESS".equalsIgnoreCase(state)) return "ok";
        if ("INTERRUPTED".equalsIgnoreCase(state) || "DENIED".equalsIgnoreCase(state)) return "cancelled";
        return "error";
    }

    private void appendToolResult(RunContext run, String callId, String delta) {
        if (delta == null || delta.isEmpty()) return;
        ToolCallRecord record = findTool(run, callId);
        if (record == null) return;
        String existing = record.getPreview() == null ? "" : record.getPreview();
        String combined = existing + delta;
        if (combined.length() > TOOL_PREVIEW_LIMIT) {
            combined = combined.substring(0, TOOL_PREVIEW_LIMIT);
            record.setTruncated(true);
        }
        record.setPreview(combined);
    }

    /** 按 callId 找记录；id 缺失时退回最后一个 running（兼容不带 id 的旧事件） */
    private ToolCallRecord findTool(RunContext run, String callId) {
        if (callId != null && !callId.isBlank()) {
            ToolCallRecord byId = run.toolsById().get(callId);
            if (byId != null) return byId;
        }
        List<ToolCallRecord> tools = run.toolCalls();
        for (int i = tools.size() - 1; i >= 0; i--) {
            ToolCallRecord record = tools.get(i);
            if ("running".equals(record.getStatus())) return record;
        }
        return null;
    }

    /**
     * 工具占用墙钟时间：各次调用"执行区间"的并集（并行时不会重复累加）。
     * <p>区间取 call_end → result_end，与卡片上的"执行 X"同口径；
     * 模型生成调用参数的那段时间算在模型里。</p>
     */
    private long toolsWallMs(List<ToolCallRecord> tools) {
        List<long[]> spans = new ArrayList<>();
        for (ToolCallRecord tool : tools) {
            if (tool.getStartedAt() <= 0) continue;
            long start = tool.getCallEndedAt() > 0 && tool.getCallEndedAt() <= tool.getEndedAt()
                    ? tool.getCallEndedAt()
                    : tool.getStartedAt();
            long end = tool.getEndedAt() > 0 ? tool.getEndedAt() : start;
            spans.add(new long[]{start, Math.max(start, end)});
        }
        if (spans.isEmpty()) return 0;
        spans.sort((a, b) -> Long.compare(a[0], b[0]));
        long total = 0;
        long curStart = spans.get(0)[0];
        long curEnd = spans.get(0)[1];
        for (int i = 1; i < spans.size(); i++) {
            long[] span = spans.get(i);
            if (span[0] <= curEnd) {
                curEnd = Math.max(curEnd, span[1]);
            } else {
                total += curEnd - curStart;
                curStart = span[0];
                curEnd = span[1];
            }
        }
        return total + (curEnd - curStart);
    }

    private String titleOf(String content) {
        String title = content.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) return "新对话";
        if (title.length() > titleMaxLength) {
            title = title.substring(0, titleMaxLength) + "…";
        }
        return title;
    }

    private String modelName(ChatMode mode) {
        return mode == ChatMode.DEEP ? reasonerModelName : chatModelName;
    }

    /** 本轮模型选择：profileId + 实际模型名 + 回退原因。 */
    private record ModelSelection(String profileId, String modelName, String fallbackReason) {}

    /**
     * 解析本轮使用的模型：请求 modelId → 会话已记录模型 → 全局默认。
     * 请求的配置不存在/被禁用时回退默认，并通过 meta.modelFallbackReason 告知前端。
     */
    private ModelSelection resolveModelSelection(ChatStreamRequest request, Conversation conversation) {
        if (modelRuntime == null) {
            return new ModelSelection(null, modelName(ChatMode.from(request.getMode())), null);
        }
        String requested = request.getModelId();
        if (requested == null || requested.isBlank()) requested = conversation.getModel();
        ChatProfile fallback = modelRuntime.defaultChat().orElse(null);
        if (requested != null && !requested.isBlank()) {
            Optional<ChatProfile> profile = modelRuntime.chatProfile(requested);
            if (profile.isPresent()) {
                return new ModelSelection(profile.get().id(), profile.get().modelName(), null);
            }
            if (fallback != null) {
                return new ModelSelection(fallback.id(), fallback.modelName(), "所选模型不可用，已回退默认模型");
            }
        }
        if (fallback != null) {
            return new ModelSelection(fallback.id(), fallback.modelName(), null);
        }
        return new ModelSelection(null, modelName(ChatMode.from(request.getMode())), "尚未配置语言模型，使用启动参数");
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message == null ? "" : message);
        publisher.send(emitter, "error", payload);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String str(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    private int num(Map<?, ?> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Map<?,?> → Map<String,Object>，供事件透传（SSE 序列化要求字符串键） */
    private static Map<String, Object> toStringMap(Map<?, ?> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        data.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
