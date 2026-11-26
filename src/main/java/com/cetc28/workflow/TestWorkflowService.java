package com.cetc28.workflow;

import com.cetc28.service.ModelService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 测试用 Graph 工作流服务 — 三节点流水线，每节点独立流式输出
 */
@Service
public class TestWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(TestWorkflowService.class);

    @Autowired
    private ModelService modelService;

    @Autowired
    private Model chatModel;

    /**
     * 执行三节点工作流，每节点输出依次通过 Flux 发射
     */
    public Flux<String> executeWorkflow(String userMessage) {
        return Flux.concat(
                nodeStep("🔍 需求分析",
                        "你是需求分析师。请分析以下用户请求的核心需求，"
                                + "列出需要解决的关键问题（2-3点），直接输出不要客套：\n\n" + userMessage),

                nodeStep("📚 信息检索",
                        "你是信息检索员。针对以下用户需求，请列出需要查询哪些信息，"
                                + "以及这些信息对解决问题的作用。直接输出不要客套：\n\n" + userMessage),

                nodeStep("📋 方案生成",
                        "你是方案汇总专家。用户的需求是：" + userMessage + "\n\n"
                                + "请基于以上分析生成一个完整的、可直接展示给用户的最终回答。"
                                + "要求结构清晰、内容完整。直接输出最终回答：")
        );
    }

    private Flux<String> nodeStep(String nodeName, String prompt) {
        String header = "\n━━━ " + nodeName + " ━━━\n\n";
        return Flux.<String>create(sink -> {
            sink.next(header);
            String response = modelService.call(prompt);
            if (response != null && !response.isEmpty()) {
                sink.next(response);
            }
            sink.complete();
        }).doOnSubscribe(s -> log.info("[工作流] 节点 '{}' 开始执行", nodeName))
          .doOnComplete(() -> log.info("[工作流] 节点 '{}' 执行完成", nodeName));
    }

    public Flux<String> nodeStepWithTools(String nodeName, String prompt, Toolkit toolkit) {
        String header = "\n━━━ " + nodeName + " ━━━\n\n";
        return Flux.<String>create(sink -> {
            sink.next(header);
            var agent = ReActAgent.builder()
                    .name(nodeName)
                    .sysPrompt(prompt)
                    .model(chatModel)
                    .toolkit(toolkit)
                    .build();
            Msg response = agent.call(List.of(Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(prompt).build())
                    .build())).block();
            String text = response != null ? response.getTextContent() : "";
            if (!text.isEmpty()) sink.next(text);
            sink.complete();
        }).doOnSubscribe(s -> log.info("[工作流] 节点 '{}' 开始执行 (带工具)", nodeName))
          .doOnComplete(() -> log.info("[工作流] 节点 '{}' 执行完成", nodeName));
    }
}
