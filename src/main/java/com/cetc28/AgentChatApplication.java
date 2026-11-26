package com.cetc28;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentChat v2 —— 对齐 AgentScope Java 2.0 的多 Agent 智能体工程。
 *
 * <p>与旧工程的关键差异：
 * <ul>
 *   <li>身份键严格区分 userId / sessionId，每个 Agent 独立 AgentState 槽位；
 *   <li>RAG、附件、用户画像等动态上下文以 USER 消息注入，system prompt 保持稳定；
 *   <li>编排由模型通过 {@code dispatch_agents} 工具驱动，不再用 Java 关键词路由；
 *   <li>长期记忆走官方 MEMORY.md + flush/consolidation 流水线，并按 userId 隔离。
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.cetc28")
@EnableAsync
@EnableScheduling
public class AgentChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentChatApplication.class, args);
    }
}
