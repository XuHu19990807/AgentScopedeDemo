package com.cetc28.workflow;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 把原三节点工作流包装成官方 {@link AgentTool}，由模型自主决定是否调用。
 *
 * <p>旧的 {@code WorkflowAgent.process(...)} 覆写是与框架 Agent API 平行的旁路，
 * 在 2.0 架构下改为工具后，工作流与其它能力一样参与 ReAct 推理与事件流。
 */
@Component
public class WorkflowTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private final TestWorkflowService workflowService;

    public WorkflowTool(TestWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public String getName() {
        return "run_workflow";
    }

    @Override
    public String getDescription() {
        return "执行内置的多步骤工作流（需求分析 → 信息检索 → 方案生成），"
                + "返回三个节点的完整文本。适合需要按固定流水线产出一份结构化方案的请求。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "request", Map.of(
                                "type", "string",
                                "description", "要交给工作流处理的完整用户请求")),
                "required", List.of("request"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object raw = param.getInput() == null ? null : param.getInput().get("request");
        if (raw == null || raw.toString().isBlank()) {
            return Mono.just(ToolResultBlock.error("request 不能为空"));
        }
        String request = raw.toString();
        return Mono.fromCallable(() -> {
                    List<String> parts = workflowService.executeWorkflow(request).collectList().block();
                    return parts == null ? "" : String.join("", parts);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ToolResultBlock::text)
                .onErrorResume(e -> {
                    log.warn("工作流执行失败: {}", e.toString());
                    return Mono.just(ToolResultBlock.error("工作流执行失败: " + e.getMessage()));
                });
    }
}
