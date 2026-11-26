package com.cetc28.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 核心配置 — 使用 AgentScope ModelRegistry 注册模型
 */
@Configuration
public class AIConfig {

    @Value("${agentscope.model.openai.api-key}")
    private String apiKey;

    @Value("${agentscope.model.openai.base-url}")
    private String baseUrl;

    @Value("${agentscope.model.openai.chat-model}")
    private String chatModelName;

    @Value("${agentscope.model.openai.temperature:0.7}")
    private double temperature;

    @Value("${agentscope.model.openai.max-tokens:4096}")
    private int maxTokens;

    // ====== 备用模型（主模型故障自动降级，HarnessAgent.fallbackModel） ======
    @Value("${agentscope.model.openai.fallback.api-key:}")
    private String fallbackApiKey;

    @Value("${agentscope.model.openai.fallback.base-url:}")
    private String fallbackBaseUrl;

    @Value("${agentscope.model.openai.fallback.chat-model:}")
    private String fallbackChatModelName;

    @Value("${agentscope.model.openai.fallback.temperature:0.7}")
    private double fallbackTemperature;

    // ====== 深度思考模型（mode=deep），未配置时回落到主模型 ======
    @Value("${agentscope.model.reasoner.api-key:}")
    private String reasonerApiKey;

    @Value("${agentscope.model.reasoner.base-url:}")
    private String reasonerBaseUrl;

    @Value("${agentscope.model.reasoner.chat-model:}")
    private String reasonerModelName;

    @Value("${agentscope.model.reasoner.temperature:0.6}")
    private double reasonerTemperature;

    @Value("${agentscope.model.reasoner.max-tokens:8192}")
    private int reasonerMaxTokens;

    @Bean
    @org.springframework.context.annotation.Primary
    public Model chatModel() {
        return OpenAIChatModel.builder()
                .modelName(chatModelName)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .generateOptions(GenerateOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .build();
    }

    /**
     * 备用模型 Bean — 未配置 fallback.chat-model 时返回 null（Spring 跳过注册），
     * AgentBase 通过 @Autowired(required=false) 注入后为 null，不启用降级。
     */
    @Bean
    public Model fallbackChatModel() {
        if (fallbackChatModelName == null || fallbackChatModelName.isBlank()) {
            return null;
        }
        return OpenAIChatModel.builder()
                .modelName(fallbackChatModelName)
                .apiKey(fallbackApiKey != null && !fallbackApiKey.isBlank() ? fallbackApiKey : apiKey)
                .baseUrl(fallbackBaseUrl != null && !fallbackBaseUrl.isBlank() ? fallbackBaseUrl : baseUrl)
                .generateOptions(GenerateOptions.builder()
                        .temperature(fallbackTemperature)
                        .maxTokens(maxTokens)
                        .build())
                .build();
    }

    /**
     * 深度思考模型 Bean — 未配置 reasoner.chat-model 时复用主模型配置。
     */
    @Bean
    public Model reasonerModel() {
        boolean configured = reasonerModelName != null && !reasonerModelName.isBlank();
        String modelName = configured ? reasonerModelName : chatModelName;
        String key = reasonerApiKey != null && !reasonerApiKey.isBlank() ? reasonerApiKey : apiKey;
        String url = reasonerBaseUrl != null && !reasonerBaseUrl.isBlank() ? reasonerBaseUrl : baseUrl;
        return OpenAIChatModel.builder()
                .modelName(modelName)
                .apiKey(key)
                .baseUrl(url)
                .generateOptions(GenerateOptions.builder()
                        .temperature(configured ? reasonerTemperature : temperature)
                        .maxTokens(configured ? reasonerMaxTokens : maxTokens)
                        .build())
                .build();
    }

    @Bean
    public ModelRegistry modelRegistryInitializer(Model chatModel) {
        ModelRegistry.register("default", chatModel);
        return null; // Static注册器，Bean仅用于触发初始化
    }
}
