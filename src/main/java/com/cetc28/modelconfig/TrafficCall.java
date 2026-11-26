package com.cetc28.modelconfig;

/**
 * 一次模型调用的流量记录（内存环形缓冲与聚合统计的最小单元）。
 *
 * @param ts           调用结束时间（epoch millis）
 * @param kind         chat / embedding / rerank
 * @param profileId    模型配置 id
 * @param modelName    实际请求的模型名
 * @param agentId      发起调用的 Agent（embedding/rerank 为空）
 * @param inputTokens  输入 token
 * @param outputTokens 输出 token
 * @param cachedTokens 命中前缀缓存的 token
 * @param totalTokens  总 token
 * @param latencyMs    端到端耗时
 * @param ok           是否成功
 * @param error        失败原因（成功为 null）
 */
public record TrafficCall(
        long ts,
        String kind,
        String profileId,
        String modelName,
        String agentId,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        int totalTokens,
        long latencyMs,
        boolean ok,
        String error) {

    public static TrafficCall ok(String kind, String profileId, String modelName, String agentId,
                                 int in, int out, int cached, long latencyMs) {
        int total = in + out;
        return new TrafficCall(System.currentTimeMillis(), kind, profileId, modelName, agentId,
                in, out, cached, total, latencyMs, true, null);
    }

    public static TrafficCall failed(String kind, String profileId, String modelName, String agentId,
                                     long latencyMs, String error) {
        String message = error == null || error.isBlank() ? "调用失败" : error;
        if (message.length() > 300) message = message.substring(0, 300);
        return new TrafficCall(System.currentTimeMillis(), kind, profileId, modelName, agentId,
                0, 0, 0, 0, latencyMs, false, message);
    }

    public String key() {
        return (kind == null ? "chat" : kind) + ":" + (profileId == null ? "unknown" : profileId);
    }
}
