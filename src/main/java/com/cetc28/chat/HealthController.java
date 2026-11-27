package com.cetc28.chat;

import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.config.NacosAvailabilityGate;
import com.cetc28.config.NacosConfig;
import com.cetc28.identity.AuthService;
import com.cetc28.identity.DmSchemaInitializer;
import com.cetc28.identity.IdentityProperties;
import com.cetc28.mcp.McpHubService;
import com.cetc28.rag.milvus.MilvusClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.UnifiedJedis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件健康状态，供前端/运维查看降级情况。
 *
 * <p>知识库向量库已从 Redis Stack 迁到 Milvus：{@code redisSearch} 字段为兼容旧前端保留，
 * 现在表示"知识库检索是否可用"（实际探测 Milvus）；同时新增 {@code vectorStore} / {@code milvus}。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final UnifiedJedis jedis;
    private final NacosConfig nacosConfig;
    private final NacosAvailabilityGate nacosGate;
    private final McpHubService mcpHubService;
    private final AgentRegistry agentRegistry;
    private final Environment environment;
    private final MilvusClientProvider milvus;
    private final com.cetc28.modelconfig.ModelRuntime modelRuntime;
    private final JdbcTemplate jdbcTemplate;
    private final DmSchemaInitializer schemaInitializer;
    private final IdentityProperties identityProperties;
    private final AuthService authService;

    public HealthController(UnifiedJedis jedis,
                            NacosConfig nacosConfig,
                            NacosAvailabilityGate nacosGate,
                            McpHubService mcpHubService,
                            AgentRegistry agentRegistry,
                            Environment environment,
                            MilvusClientProvider milvus,
                            com.cetc28.modelconfig.ModelRuntime modelRuntime,
                            JdbcTemplate jdbcTemplate,
                            DmSchemaInitializer schemaInitializer,
                            IdentityProperties identityProperties,
                            AuthService authService) {
        this.jedis = jedis;
        this.nacosConfig = nacosConfig;
        this.nacosGate = nacosGate;
        this.mcpHubService = mcpHubService;
        this.agentRegistry = agentRegistry;
        this.environment = environment;
        this.milvus = milvus;
        this.modelRuntime = modelRuntime;
        this.jdbcTemplate = jdbcTemplate;
        this.schemaInitializer = schemaInitializer;
        this.identityProperties = identityProperties;
        this.authService = authService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean redisUp = pingRedis();
        boolean milvusUp = pingMilvus();
        boolean modelConfigured = modelRuntime.defaultChat().isPresent()
                || hasText(environment.getProperty("agentscope.model.openai.api-key"));

        result.put("status", redisUp && milvusUp ? "up" : "degraded");
        result.put("redis", redisUp ? "up" : "down");
        // 兼容字段：旧前端用它显示"检索"是否可用，迁移后语义=知识库检索（Milvus）
        result.put("redisSearch", milvusUp ? "up" : (redisUp ? "missing" : "unknown"));
        result.put("vectorStore", "milvus");
        result.put("milvus", milvusUp ? "up" : "down");
        if (!milvusUp) result.put("milvusError", milvus.getLastError());
        result.put("model", modelConfigured ? "configured" : "missing_key");
        result.put("nacos", nacosConfig.isEnabled() ? nacosGate.describe() : "disabled");
        result.put("mcpServers", mcpHubService.getServerInfoList().size());
        result.put("agents", agentRegistry.getEnabledAgentCount());
        result.put("dm", pingDm() ? "up" : "down");
        result.put("identity", identityStatus());
        return result;
    }

    private String identityStatus() {
        boolean schemaReady = schemaInitializer.schemaReady();
        if (!schemaReady) {
            String error = schemaInitializer.lastError();
            return error == null || error.isBlank() ? "schema_pending" : "schema_error: " + error;
        }
        return "ready"
                + "（local" + (authService.ssoEnabled() ? ",sso" : "")
                + (identityProperties.getAuth().getAnonymous().isEnabled() ? ",anonymous" : "") + "）";
    }

    private boolean pingDm() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1 FROM DUAL", Integer.class);
            return value != null && value == 1;
        } catch (Exception e) {
            log.debug("DM ping failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean pingRedis() {
        try {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            log.debug("Redis ping failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean pingMilvus() {
        try {
            return milvus.available();
        } catch (Exception e) {
            log.debug("Milvus health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
