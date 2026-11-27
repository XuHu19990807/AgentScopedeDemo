package com.cetc28.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 动态上下文注入预算（RAG / 附件 / 用户画像）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.context")
public class ContextProperties {

    private int knowledgeMaxChars = 6000;
    private int attachmentMaxChars = 12000;
    private int profileMaxChars = 1500;
}
