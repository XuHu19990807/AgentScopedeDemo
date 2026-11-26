package com.cetc28.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Nacos 客户端配置 — 创建 ConfigService / NamingService / AiService Bean
 *
 * <p>AI 资源（Prompt / Skill / MCP）统一走官方 SDK {@link AiService}，
 * 仅“资源枚举”因 SDK 无对应接口而使用 Client API。</p>
 */
@Configuration
@EnableConfigurationProperties(NacosConfig.class)
public class NacosClientConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosClientConfig.class);

    private Properties buildProps(NacosConfig nacosConfig) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, nacosConfig.getServerAddr());
        if (nacosConfig.getNamespace() != null && !nacosConfig.getNamespace().isBlank()) {
            props.put(PropertyKeyConst.NAMESPACE, nacosConfig.getNamespace());
        }
        if (nacosConfig.getUsername() != null && !nacosConfig.getUsername().isBlank()) {
            props.put(PropertyKeyConst.USERNAME, nacosConfig.getUsername());
        }
        if (nacosConfig.getPassword() != null) {
            props.put(PropertyKeyConst.PASSWORD, nacosConfig.getPassword());
        }
        return props;
    }

    @Bean(destroyMethod = "shutDown")
    @ConditionalOnProperty(prefix = "nacos", name = "enabled", havingValue = "true")
    public ConfigService nacosConfigService(NacosConfig nacosConfig) {
        if (!nacosConfig.isEnabled()) {
            log.info("Nacos disabled, using local resources (prompts/skills)");
            return null;
        }
        try {
            ConfigService cs = NacosFactory.createConfigService(buildProps(nacosConfig));
            log.info("Nacos ConfigService created: serverAddr={}, namespace='{}'",
                    nacosConfig.getServerAddr(), nacosConfig.getNamespace());
            return cs;
        } catch (Exception e) {
            log.warn("Nacos ConfigService init failed (will use local fallback): {}", e.getMessage());
            return null;
        }
    }

    /**
     * NamingService — 仅用于 MCP 的 naming 兜底发现（保留旧行为）
     */
    @Bean(destroyMethod = "shutDown")
    @ConditionalOnProperty(prefix = "nacos", name = "enabled", havingValue = "true")
    public NamingService nacosNamingService(NacosConfig nacosConfig) {
        if (!nacosConfig.isEnabled()) {
            return null;
        }
        try {
            NamingService ns = NamingFactory.createNamingService(buildProps(nacosConfig));
            log.info("Nacos NamingService created: serverAddr={}, namespace='{}'",
                    nacosConfig.getServerAddr(), nacosConfig.getNamespace());
            return ns;
        } catch (Exception e) {
            log.warn("Nacos NamingService init failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AiService — 官方 AI 资源 SDK：Prompt / Skill / MCP / Agent / AgentSpec
     *
     * <p>一个实例对应一个命名空间，应用生命周期内复用，退出时 shutdown。</p>
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "nacos", name = "enabled", havingValue = "true")
    public AiService nacosAiService(NacosConfig nacosConfig) {
        if (!nacosConfig.isEnabled()) {
            return null;
        }
        try {
            AiService aiService = AiFactory.createAiService(buildProps(nacosConfig));
            log.info("Nacos AiService created: serverAddr={}, namespace='{}'",
                    nacosConfig.getServerAddr(), nacosConfig.getNamespace());
            return aiService;
        } catch (Exception e) {
            log.warn("Nacos AiService init failed (Prompt/Skill/MCP 将降级): {}", e.getMessage());
            return null;
        }
    }
}
