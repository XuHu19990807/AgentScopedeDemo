package com.cetc28.rag.milvus;

import com.cetc28.service.EmbeddingService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 把项目现有的 {@link EmbeddingService} 适配成 AgentScope 的 {@link EmbeddingModel}。
 *
 * <p>为什么不直接用框架的 OpenAITextEmbedding：现有服务已经处理了 DashScope 兼容模式、
 * 无鉴权内网模型、按 max-tokens 截断、失败降级与维度探测，适配比重写更稳。
 */
@Component
public class MilvusEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(MilvusEmbeddingModel.class);

    private final EmbeddingService embeddingService;

    public MilvusEmbeddingModel(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public Mono<double[]> embed(ContentBlock block) {
        String text = extractText(block);
        return Mono.fromCallable(() -> {
                    float[] vector = embeddingService.embed(text);
                    if (vector == null) {
                        throw new IllegalStateException("向量化失败：" + embeddingService.getLastError());
                    }
                    double[] result = new double[vector.length];
                    for (int i = 0; i < vector.length; i++) result[i] = vector[i];
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String extractText(ContentBlock block) {
        if (block instanceof TextBlock textBlock) return textBlock.getText();
        if (block == null) return "";
        String text = block.toString();
        log.debug("非文本 ContentBlock（{}），按 toString 向量化", block.getClass().getSimpleName());
        return text;
    }

    @Override
    public String getModelName() {
        return embeddingService.describeTarget();
    }

    @Override
    public int getDimensions() {
        return embeddingService.getDimension();
    }
}
