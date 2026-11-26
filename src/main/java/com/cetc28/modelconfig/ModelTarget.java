package com.cetc28.modelconfig;

import io.agentscope.core.model.Model;

/**
 * 一次调用实际解析到的模型目标：配置 id、模型名、底层 Model 实例。
 */
public record ModelTarget(String profileId, String modelName, Model model) {
}
