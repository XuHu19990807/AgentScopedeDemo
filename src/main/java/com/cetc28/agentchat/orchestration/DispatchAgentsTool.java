package com.cetc28.agentchat.orchestration;

import com.cetc28.agentchat.base.AgentBase;
import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.agentchat.middleware.ContextInjectionMiddleware;
import com.cetc28.dto.StreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型驱动的多 Agent 编排工具。
 *
 * <p>orchestrator 不再由 Java 解析计划文本/关键词，而是通过一次 tool call 明确
 * “把哪些子任务交给哪些 Agent”。工具内部用官方 Agent API
 * （{@code AgentBase.process(msg, userId, sessionId)}）并发或串行执行 worker，
 * 把结构化结果作为 tool result 返回给 orchestrator，由模型综合成最终回答。
 *
 * <p>worker 的开始/结束事件通过 {@link RunEventSink} 合并进 SSE 流，
 * 因此前端仍能看到 {@code agent_start}/{@code agent_result}。
 */
@Component
public class DispatchAgentsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(DispatchAgentsTool.class);
    private static final Set<String> EXCLUDED = Set.of("orchestrator", "chat", "deep");

    private final AgentRegistry registry;
    private final ObjectMapper objectMapper;

    @Value("${agent.orchestration.parallel-max-agents:6}")
    private int maxAgents;

    @Value("${agent.orchestration.parallel-worker-timeout-seconds:300}")
    private int workerTimeoutSeconds;

    public DispatchAgentsTool(AgentRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "dispatch_agents";
    }

    @Override
    public String getDescription() {
        return "把用户请求拆成若干子任务，分派给具备相应能力的子智能体执行，并返回每个子智能体的结果。"
                + "需要多领域协作、或需要并行处理时调用。子智能体看不到本对话历史，"
                + "因此 instruction 必须自包含（写清目标、输入、期望输出）。"
                + "可用 agentId 见系统提示中的子智能体清单。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> task = Map.of(
                "type", "object",
                "properties", Map.of(
                        "agentId", Map.of("type", "string", "description", "子智能体 id"),
                        "instruction", Map.of("type", "string",
                                "description", "自包含的子任务说明：目标、已知信息、期望输出格式")),
                "required", List.of("agentId", "instruction"));
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "tasks", Map.of("type", "array", "items", task,
                                "description", "要执行的子任务列表"),
                        "parallel", Map.of("type", "boolean",
                                "description", "true=并发执行（默认），false=按顺序执行并把前序结果带给后续")),
                "required", List.of("tasks"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("dispatch_agents failed: {}", e.toString());
                    return Mono.just(ToolResultBlock.error("编排执行失败: " + e.getMessage()));
                });
    }

    private ToolResultBlock execute(ToolCallParam param) throws Exception {
        RuntimeContext rc = param.getRuntimeContext();
        String userId = rc == null || rc.getUserId() == null ? "anonymous" : rc.getUserId();
        String sessionId = rc == null || rc.getSessionId() == null ? "default" : rc.getSessionId();
        RunEventSink sink = rc == null ? null : rc.get(RunEventSink.KEY, RunEventSink.class);
        String sharedContext = rc == null ? null
                : rc.get(ContextInjectionMiddleware.CONTEXT_KEY, String.class);

        List<TaskSpec> specs = parse(param.getInput());
        if (specs.isEmpty()) {
            return ToolResultBlock.error("tasks 不能为空");
        }
        if (specs.size() > maxAgents) {
            specs = specs.subList(0, maxAgents);
        }
        boolean parallel = !Boolean.FALSE.equals(param.getInput().get("parallel"));
        int concurrency = parallel ? Math.min(specs.size(), Math.max(1, maxAgents)) : 1;

        List<TaskSpec> finalSpecs = specs;
        List<TaskResult> results = Flux.fromIterable(finalSpecs)
                .flatMapSequential(spec -> runOne(spec, userId, sessionId, sharedContext, sink),
                        concurrency)
                .collectList()
                .block();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("results", results == null ? List.of() : results);
        String json = objectMapper.writeValueAsString(payload);
        return ToolResultBlock.text("以下是各子智能体的原始输出（JSON）。请基于它们综合回答用户，"
                + "不要逐字复述内部结构：\n" + json);
    }

    private Mono<TaskResult> runOne(TaskSpec spec, String userId, String sessionId,
                                    String sharedContext, RunEventSink sink) {
        AgentBase worker = registry.getAgent(spec.agentId()).orElse(null);
        if (worker == null || EXCLUDED.contains(spec.agentId()) || !worker.isEnabled()) {
            return Mono.just(TaskResult.failed(spec, "未知或不可调度的 agentId: " + spec.agentId()));
        }
        if (sink != null) {
            sink.emit("agent_start", Map.of(
                    "agentId", spec.agentId(),
                    "name", worker.getAgentName(),
                    "task", truncate(spec.instruction(), 200)));
        }
        return Mono.fromCallable(() -> worker.process(spec.instruction(), userId, sessionId, sharedContext))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(workerTimeoutSeconds))
                .map(output -> {
                    if (sink != null) {
                        sink.emit("agent_result", Map.of(
                                "agentId", spec.agentId(),
                                "name", worker.getAgentName(),
                                "output", output == null ? "" : output));
                    }
                    return TaskResult.ok(spec, worker.getAgentName(), output);
                })
                .onErrorResume(e -> {
                    worker.interrupt(userId, sessionId);
                    if (sink != null) {
                        sink.emit("agent_error", Map.of(
                                "agentId", spec.agentId(),
                                "name", worker.getAgentName(),
                                "error", e.toString()));
                    }
                    return Mono.just(TaskResult.failed(spec, e.toString()));
                });
    }

    @SuppressWarnings("unchecked")
    private List<TaskSpec> parse(Map<String, Object> input) {
        List<TaskSpec> specs = new ArrayList<>();
        Object raw = input == null ? null : input.get("tasks");
        if (!(raw instanceof List<?> list)) {
            return specs;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object id = map.get("agentId");
            Object instruction = map.get("instruction");
            if (id == null || instruction == null || instruction.toString().isBlank()) {
                continue;
            }
            specs.add(new TaskSpec(id.toString(), instruction.toString()));
        }
        return specs;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private record TaskSpec(String agentId, String instruction) {
    }

    public record TaskResult(String agentId, String name, boolean ok, String output, String error) {
        static TaskResult ok(TaskSpec spec, String name, String output) {
            return new TaskResult(spec.agentId(), name, true, output, null);
        }

        static TaskResult failed(TaskSpec spec, String error) {
            return new TaskResult(spec.agentId(), spec.agentId(), false, null, error);
        }
    }
}
