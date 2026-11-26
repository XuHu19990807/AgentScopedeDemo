package com.cetc28.service;

/**
 * 一个 worker Agent 在编排中的产出快照，供汇总阶段使用。
 *
 * @param agentId    Agent 标识
 * @param agentName  展示名
 * @param text       该 Agent 输出的正文（失败时可能为空或部分内容）
 * @param status     ok | error | timeout
 * @param error      失败原因（成功时为 null）
 * @param durationMs 执行耗时
 */
public record AgentOutput(
        String agentId,
        String agentName,
        String text,
        String status,
        String error,
        long durationMs
) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_TIMEOUT = "timeout";

    public boolean ok() {
        return STATUS_OK.equals(status);
    }

    public int chars() {
        return text == null ? 0 : text.length();
    }
}
