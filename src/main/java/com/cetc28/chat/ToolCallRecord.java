package com.cetc28.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具调用的结构化记录，用于 SSE 工具卡片与历史回放。
 *
 * <p>计时口径：一次工具调用被拆成三个可解释的片段，避免把"模型生成时间"
 * 混进"工具执行时间"里：
 *
 * <pre>
 * tool_call(start) ──callMs──▶ tool_call(end) ──toolMs──▶ tool_result(start)
 *                                              └─resultMs─▶ tool_result(end)
 * tool_result(end) ──waitMs──▶ 下一次可见输出（模型继续生成）
 * </pre>
 *
 * <ul>
 *   <li>{@code callMs}：模型生成工具调用参数（含首 token 等待）；</li>
 *   <li>{@code toolMs}：工具真正执行的时间，可与 MCP 客户端日志的 "耗时=xxxms" 对齐；</li>
 *   <li>{@code resultMs}：结果回传/序列化时间，通常几十毫秒；</li>
 *   <li>{@code waitMs}：结果返回后等待模型继续输出的时间，<b>不属于工具耗时</b>；</li>
 *   <li>{@code durationMs}：旧字段，表示"开窗到关窗"的整卡窗口，仅为兼容历史数据保留。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {

    private String callId;
    private String server;
    private String name;

    @Builder.Default
    private Map<String, Object> args = new LinkedHashMap<>();

    /** ok | error | cancelled | running */
    private String status;

    /** 旧字段：卡片窗口总长（call_start → result_end），兼容历史消息 */
    private long durationMs;

    /** 结果预览（截断） */
    private String preview;

    private boolean truncated;

    /** 开始时间（tool_call 事件到达） */
    private long startedAt;

    /** 工具调用参数生成结束（tool_call_end 事件到达）；0 表示框架未发该事件 */
    private long callEndedAt;

    /** 结果开始回传（tool_result_start 事件到达）；0 表示框架未发该事件 */
    private long resultStartedAt;

    /** 结束时间（tool_result_end 事件到达） */
    private long endedAt;

    /** 模型生成调用参数耗时 */
    private long callMs;

    /** 工具执行耗时（可与日志对齐的主数字） */
    private long toolMs;

    /** 结果回传耗时 */
    private long resultMs;

    /** 结果返回后等待模型继续输出的耗时（不计入工具耗时） */
    private long waitMs;

    /** 框架给出的结果状态：SUCCESS / ERROR / INTERRUPTED / DENIED */
    private String state;
}
