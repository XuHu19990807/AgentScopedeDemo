package com.cetc28.dto;

/**
 * SSE 流式事件数据结构
 *
 * @param type 事件类型:
 *             thinking   - 分析中
 *             plan      - 任务计划
 *             agent_start - 子Agent开始执行
 *             text_chunk - 子Agent输出的文本片段
 *             agent_result - 子Agent执行完成
 *             agent_error - 子Agent执行错误
 *             mcp_call  - MCP工具调用
 *             mcp_result - MCP工具返回结果
 *             mcp_error - MCP工具调用错误
 *             complete  - 全部执行完成
 *             error     - 整体错误
 * @param data 事件内容
 */
public record StreamEvent(String type, Object data) {}
