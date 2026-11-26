package com.cetc28.service;

import com.cetc28.dto.StreamEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 并行编排的"汇总"阶段。
 *
 * 各 worker 并发跑完后，把它们的结论交给主对话模型（chatModel，不挂工具）合成一段
 * 去重、结构化的最终答复；失败或未配置时退化为按 Agent 分节拼接，保证总有输出。
 */
@Service
public class OrchestrationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationSummaryService.class);

    private static final String SYSTEM_PROMPT = """
            你是多个 Agent 协作的汇总器。输入包含用户的原始请求，以及若干 Agent 各自独立得出的结论。
            请把它们合并成一份结构清晰、可直接交付给用户的中文答复：
            1. 合并重复内容，保留具体数字、指标与结论，不要罗列"某个 Agent 说了什么"；
            2. 不要提及 Agent、计划、并行、编排等内部实现细节，也不要编造输入中没有的数据；
            3. 用标题分节，篇幅与信息量匹配，不要空话。
            """;

    private final com.cetc28.modelconfig.ModelRuntime modelRuntime;

    @Value("${agent.orchestration.summary-per-agent-max-chars:6000}")
    private int perAgentMaxChars = 6000;

    @Value("${agent.orchestration.summary-total-max-chars:18000}")
    private int totalMaxChars = 18000;

    @Value("${agent.orchestration.summary-temperature:0.3}")
    private double temperature = 0.3;

    public OrchestrationSummaryService(com.cetc28.modelconfig.ModelRuntime modelRuntime) {
        this.modelRuntime = modelRuntime;
    }

    /**
     * 汇总各 Agent 的结论，输出 text 事件流；任何异常都退化为拼接文本。
     */
    public Flux<StreamEvent> summarize(String userRequest, List<AgentOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return Flux.just(new StreamEvent("text", Map.of("delta", "（没有可汇总的 Agent 结果）")));
        }
        String prompt = buildPrompt(userRequest, outputs, perAgentMaxChars, totalMaxChars);
        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(SYSTEM_PROMPT).build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build()
        );
        GenerateOptions options = GenerateOptions.builder().temperature(temperature).build();

        return Flux.defer(() -> modelRuntime.modelFor("orchestrator-summary").stream(messages, List.of(), options))
                .map(OrchestrationSummaryService::textOf)
                .filter(text -> !text.isEmpty())
                .map(text -> new StreamEvent("text", Map.of("delta", text)))
                .onErrorResume(error -> {
                    log.warn("编排汇总失败，退化为拼接输出: {}", rootMessage(error));
                    return Flux.just(new StreamEvent("text", Map.of("delta", fallbackJoin(outputs))));
                });
    }

    /** 从单条响应里提取文本内容（流式分片按原样返回） */
    static String textOf(io.agentscope.core.model.ChatResponse response) {
        if (response == null || response.getContent() == null) return "";
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /**
     * 组装汇总输入：原始请求 + 各 Agent 分节结论（单节与总长均有上限）。
     * 包内可见便于单测。
     */
    static String buildPrompt(String userRequest, List<AgentOutput> outputs,
                              int perAgentMaxChars, int totalMaxChars) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[用户原始请求]\n").append(userRequest == null ? "" : userRequest.trim()).append("\n\n");
        prompt.append("[各 Agent 结论]\n");
        int remaining = Math.max(0, totalMaxChars);
        for (AgentOutput output : outputs) {
            if (remaining <= 0) {
                prompt.append("\n（其余 Agent 结论因总长上限已省略）");
                break;
            }
            String body = output.ok()
                    ? nullToEmpty(output.text()).trim()
                    : "（该 Agent 执行失败：" + nullToEmpty(output.error()) + "）\n" + nullToEmpty(output.text()).trim();
            if (body.isEmpty()) body = "（无有效输出）";
            int cap = Math.min(Math.max(0, perAgentMaxChars), remaining);
            String section = body.length() > cap ? body.substring(0, cap) + "\n（内容已截断）" : body;
            prompt.append("\n### ").append(output.agentName()).append('\n').append(section).append('\n');
            remaining -= section.length();
        }
        return prompt.toString();
    }

    /** 汇总不可用时的兜底：按 Agent 分节拼接原始结论 */
    public static String fallbackJoin(List<AgentOutput> outputs) {
        StringBuilder text = new StringBuilder();
        for (AgentOutput output : outputs) {
            if (text.length() > 0) text.append("\n\n");
            text.append("## ").append(output.agentName()).append('\n');
            String body = nullToEmpty(output.text()).trim();
            if (!output.ok() && !nullToEmpty(output.error()).isBlank()) {
                text.append("（执行失败：").append(output.error()).append("）\n");
            }
            text.append(body.isEmpty() ? "（无有效输出）" : body);
        }
        return text.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getClass().getSimpleName();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
