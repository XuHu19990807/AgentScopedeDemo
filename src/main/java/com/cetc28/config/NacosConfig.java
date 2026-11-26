package com.cetc28.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Nacos 配置 — 绑定 application.yml 中 nacos.*
 *
 * 支持总开关 + 分特性开关，并带超时/熔断参数，避免 Nacos 不可用时拖慢启动。
 */
@ConfigurationProperties(prefix = "nacos")
public class NacosConfig {

    /** 服务发现是否启用（未启用时 prompt/skill/MCP 自动降级为本地资源） */
    private boolean enabled = false;
    private String serverAddr = "127.0.0.1:8848";
    private String namespace = "";
    private String username = "nacos";
    private String password = "nacos";

    /** Nacos AI Skill 管理控制台地址（控制台 8080）；仅在 skill.discovery=console 时使用 */
    private String skillServerAddr = "127.0.0.1:8080";
    /**
     * Skill 控制台 API 基地址；为空时使用 http://{@link #skillServerAddr}。
     * 仅用于 /v3/console/ai/skills/**（兼容旧行为，默认不再走该路径）。
     */
    private String skillConsoleBaseUrl = "";
    /**
     * Skill 客户端 API 基地址；为空时使用 http://{@link #serverAddr}/nacos。
     * 用于 /v3/client/ai/skills 搜索与下载。
     */
    private String skillClientBaseUrl = "";
    /** Skill 命名空间 */
    private String skillNamespace = "public";

    /** AI 配置 group（旧版 prompt 模板存储的 group，仅兼容开关开启时使用） */
    private String promptGroup = "nacos-ai-prompt";

    /** 单次 Nacos 调用超时（毫秒） */
    private long timeoutMs = 3000;

    /** 分特性开关与特性级参数 */
    private PromptFeature prompt = new PromptFeature();
    private SkillFeature skill = new SkillFeature();
    private McpFeature mcp = new McpFeature();

    /** 熔断配置 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    // ========== MCP 服务发现（naming 模式） ==========
    /** 监听的服务名（未设置时自动扫描所有含 "mcp" 的服务） */
    private String serviceName = "mcp-server";
    /** 额外监听的服务名列表 */
    private List<String> serviceNames;
    /** 逗号分隔的服务白名单；为空表示不做自动发现 */
    private String discoverServices = "";
    /** 定时扫描间隔（毫秒） */
    private long scanIntervalMs = 30000;
    /** 分页扫描每页大小 */
    private int discoverPageSize = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSkillServerAddr() { return skillServerAddr; }
    public void setSkillServerAddr(String skillServerAddr) { this.skillServerAddr = skillServerAddr; }
    public String getSkillConsoleBaseUrl() { return skillConsoleBaseUrl; }
    public void setSkillConsoleBaseUrl(String skillConsoleBaseUrl) { this.skillConsoleBaseUrl = skillConsoleBaseUrl; }
    public String getSkillClientBaseUrl() { return skillClientBaseUrl; }
    public void setSkillClientBaseUrl(String skillClientBaseUrl) { this.skillClientBaseUrl = skillClientBaseUrl; }
    public String getSkillNamespace() { return skillNamespace; }
    public void setSkillNamespace(String skillNamespace) { this.skillNamespace = skillNamespace; }
    public String getPromptGroup() { return promptGroup; }
    public void setPromptGroup(String promptGroup) { this.promptGroup = promptGroup; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public PromptFeature getPrompt() { return prompt; }
    public void setPrompt(PromptFeature prompt) { this.prompt = prompt; }
    public SkillFeature getSkill() { return skill; }
    public void setSkill(SkillFeature skill) { this.skill = skill; }
    public McpFeature getMcp() { return mcp; }
    public void setMcp(McpFeature mcp) { this.mcp = mcp; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public List<String> getServiceNames() { return serviceNames; }
    public void setServiceNames(List<String> serviceNames) { this.serviceNames = serviceNames; }
    public String getDiscoverServices() { return discoverServices; }
    public void setDiscoverServices(String discoverServices) { this.discoverServices = discoverServices; }
    public long getScanIntervalMs() { return scanIntervalMs; }
    public void setScanIntervalMs(long scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
    public int getDiscoverPageSize() { return discoverPageSize; }
    public void setDiscoverPageSize(int discoverPageSize) { this.discoverPageSize = discoverPageSize; }

    public boolean isPromptEnabled() { return enabled && prompt.isEnabled(); }
    public boolean isSkillEnabled() { return enabled && skill.isEnabled(); }
    public boolean isMcpEnabled() { return enabled && mcp.isEnabled(); }

    /** 单个特性的开关 */
    public static class Feature {
        private boolean enabled = true;

        public Feature() {}

        public Feature(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Prompt 特性：默认只读 Prompt Registry；旧版 ConfigService 读取默认关闭 */
    public static class PromptFeature extends Feature {
        /** 是否兼容读取旧版 ConfigService（dataId={agentId}.json, group=nacos-ai-prompt） */
        private boolean legacyConfigFallback = false;

        public boolean isLegacyConfigFallback() { return legacyConfigFallback; }
        public void setLegacyConfigFallback(boolean legacyConfigFallback) {
            this.legacyConfigFallback = legacyConfigFallback;
        }
    }

    /** Skill 特性：运行时通过 Client API 搜索、通过 SDK 下载 ZIP */
    public static class SkillFeature extends Feature {
        /** client（默认，官方运行时接口）| console（旧行为，可看草稿） */
        private String discovery = "client";
        /** 列表增量刷新间隔（毫秒），0 表示关闭定时刷新 */
        private long refreshIntervalMs = 30000;

        public String getDiscovery() { return discovery; }
        public void setDiscovery(String discovery) { this.discovery = discovery; }
        public long getRefreshIntervalMs() { return refreshIntervalMs; }
        public void setRefreshIntervalMs(long refreshIntervalMs) {
            // Spring @Scheduled 的 fixedDelay 必须为正数；关闭刷新请用 skill.enabled=false
            this.refreshIntervalMs = Math.max(1000, refreshIntervalMs);
        }
    }

    /** MCP 特性：auto = Registry 优先、naming 兜底 */
    public static class McpFeature extends Feature {
        /** auto | registry | naming */
        private String discoveryMode = "auto";
        /** 指定的 MCP 资源名；为空时通过 Client API 搜索全部 */
        private List<String> registryNames = List.of();
        /** SSE 端点缺省路径 */
        private String ssePath = "/mcp/sse";

        public String getDiscoveryMode() { return discoveryMode; }
        public void setDiscoveryMode(String discoveryMode) { this.discoveryMode = discoveryMode; }
        public List<String> getRegistryNames() { return registryNames; }
        public void setRegistryNames(List<String> registryNames) { this.registryNames = registryNames; }
        public String getSsePath() { return ssePath; }
        public void setSsePath(String ssePath) { this.ssePath = ssePath; }
    }

    /** 熔断参数 */
    public static class CircuitBreaker {
        private int failureThreshold = 3;
        private long cooldownSeconds = 300;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(long cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }
}
