package com.cetc28.agentchat.orchestration;

import com.cetc28.agentchat.base.AgentBase;
import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.agentchat.middleware.ContextInjectionMiddleware;
import com.cetc28.dto.StreamEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 编排入口：只负责把 orchestrator 的事件流与 {@link DispatchAgentsTool} 产生的
 * worker 事件流合并成一条 SSE 流。
 *
 * <p>真正的“选谁、干什么、串行还是并行”由模型在 tool call 中决定，
 * Java 侧不再解析计划文件、关键词或 JSON 计划。
 */
@Service
public class OrchestrationService {

    private final AgentRegistry registry;

    public OrchestrationService(AgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param context 本轮参考材料（知识库片段/附件文本）。写入 RuntimeContext 后，
     *                dispatch_agents 会把它原样转交给每个 worker，保证子智能体
     *                与主智能体看到同一份证据，而不是各自重复检索。
     */
    public Flux<StreamEvent> stream(String userInput, String userId, String conversationId,
                                    String context) {
        return stream(userInput, userId, conversationId, context, null);
    }

    /**
     * @param modelProfileId 会话级模型配置 id；非空时 orchestrator 使用该模型，worker 仍按各自 Agent
     *                       的模型映射（agentProfiles）执行。
     */
    public Flux<StreamEvent> stream(String userInput, String userId, String conversationId,
                                    String context, String modelProfileId) {
        AgentBase orchestrator = registry.getAgent("orchestrator")
                .orElseThrow(() -> new IllegalStateException("orchestrator 未注册"));
        HarnessAgent target = orchestrator.variant(modelProfileId);

        return Flux.create(emitter -> {
            RunEventSink sink = new RunEventSink(emitter);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put(RunEventSink.KEY, sink);
            if (context != null && !context.isBlank()) {
                attributes.put(ContextInjectionMiddleware.CONTEXT_KEY, context);
            }

            // 主编排流本身也是 worker 事件的消费者：两者都写到同一个 FluxSink，
            // 由 Flux.create 串行化；主编排流结束时 complete 一次即可。
            Disposable disposable = orchestrator
                    .processRichStreamWithContext(target, userInput, userId, conversationId, context, attributes)
                    .subscribe(emitter::next, emitter::error, emitter::complete);

            emitter.onCancel(disposable::dispose);
            emitter.onDispose(disposable::dispose);
        });
    }
}
