package com.cetc28.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行编排汇总的纯函数单测：提示词组装、截断、兜底拼接。
 */
class OrchestrationSummaryTest {

    private static AgentOutput ok(String name, String text) {
        return new AgentOutput(name, name, text, AgentOutput.STATUS_OK, null, 10);
    }

    private static AgentOutput failed(String name) {
        return new AgentOutput(name, name, "", AgentOutput.STATUS_ERROR, "连接超时", 10);
    }

    @Test
    void buildPromptSectionsCarryRequestAndAgentNames() {
        String prompt = OrchestrationSummaryService.buildPrompt("设计一个办公网络",
                List.of(ok("网络规划Agent", "链路预算 120 万元"), ok("信息查询Agent", "北京晴")), 6000, 18000);

        assertTrue(prompt.contains("[用户原始请求]"));
        assertTrue(prompt.contains("设计一个办公网络"));
        assertTrue(prompt.contains("### 网络规划Agent"));
        assertTrue(prompt.contains("链路预算 120 万元"));
        assertTrue(prompt.contains("### 信息查询Agent"));
    }

    @Test
    void buildPromptTruncatesOverlongSection() {
        String longText = "x".repeat(200);
        String prompt = OrchestrationSummaryService.buildPrompt("q", List.of(ok("A", longText)), 30, 18000);

        assertTrue(prompt.contains("（内容已截断）"));
        assertTrue(prompt.length() < longText.length());
    }

    @Test
    void buildPromptRespectsTotalCapAndMarksOmission() {
        String prompt = OrchestrationSummaryService.buildPrompt("q",
                List.of(ok("A", "a".repeat(100)), ok("B", "b".repeat(100))), 100, 50);

        assertTrue(prompt.contains("（其余 Agent 结论因总长上限已省略）"));
        assertTrue(prompt.contains("### A"));
        assertTrue(!prompt.contains("### B"));
    }

    @Test
    void buildPromptMarksFailedAgent() {
        String prompt = OrchestrationSummaryService.buildPrompt("q", List.of(failed("工具Agent")), 6000, 18000);

        assertTrue(prompt.contains("该 Agent 执行失败：连接超时"));
    }

    @Test
    void fallbackJoinFormatsSectionsAndErrors() {
        String text = OrchestrationSummaryService.fallbackJoin(List.of(
                ok("网络规划Agent", "方案 A"),
                failed("信息查询Agent")));

        assertTrue(text.contains("## 网络规划Agent"));
        assertTrue(text.contains("方案 A"));
        assertTrue(text.contains("## 信息查询Agent"));
        assertTrue(text.contains("执行失败：连接超时"));
    }

    @Test
    void fallbackJoinHandlesEmptyList() {
        assertEquals("", OrchestrationSummaryService.fallbackJoin(List.of()));
    }
}
