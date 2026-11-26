package com.cetc28.mcp;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.listener.NacosMcpServerEvent;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.cetc28.config.NacosAvailabilityGate;
import com.cetc28.config.NacosConfig;
import com.cetc28.config.NacosTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos MCP 服务发现。
 *
 * <p>两种来源，按 {@code nacos.mcp.discovery-mode} 选择：</p>
 * <ul>
 *   <li>{@code registry}（官方 AI Registry）：Client API 搜索 + SDK 读取
 *       {@code backendEndpoints}，按 protocol/address/port/path 建连，支持订阅变更；</li>
 *   <li>{@code naming}（旧行为）：按 {@code nacos.discover-services} /
 *       {@code service-name} 订阅 Nacos 服务实例；</li>
 *   <li>{@code auto}（默认）：Registry 有结果时用 Registry，否则回退 naming。</li>
 * </ul>
 *
 * 安全约束：只连接显式配置或注册在 Nacos 的 MCP 实例；不执行实例 metadata 里的
 * 外部命令；Nacos 不可用时由闸门熔断。
 */
@Service
public class NacosMcpDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(NacosMcpDiscoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOMAIN = "mcp";
    private static final String REGISTRY_OWNER_PREFIX = "registry:";

    @Autowired(required = false)
    private NacosConfig nacosConfig;

    @Autowired(required = false)
    private McpHubService mcpHubService;

    @Autowired(required = false)
    private NamingService namingService;

    @Autowired(required = false)
    private AiService aiService;

    @Autowired(required = false)
    private NacosTokenProvider tokenProvider;

    @Autowired(required = false)
    private NacosAvailabilityGate availabilityGate;

    private final RestClient restClient;

    /** 已连接的 MCP 实例名 */
    private final Set<String> connectedServers = ConcurrentHashMap.newKeySet();
    /** 实例名 -> 所属来源（naming 服务名 / registry:{mcpName}） */
    private final Map<String, String> instanceOwnerService = new ConcurrentHashMap<>();
    /** 已订阅的 naming 服务名 */
    private final Set<String> subscribedServices = ConcurrentHashMap.newKeySet();
    /** 已订阅的 Registry MCP 名 */
    private final Map<String, AbstractNacosMcpServerListener> mcpListeners = new ConcurrentHashMap<>();
    /** URL -> 实例名，用于两种来源之间的去重 */
    private final Map<String, String> urlToInstance = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public NacosMcpDiscoveryService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    private boolean enabled() {
        return nacosConfig != null && nacosConfig.isMcpEnabled() && mcpHubService != null
                && (namingService != null || aiService != null)
                && (availabilityGate == null || availabilityGate.isAvailable(DOMAIN));
    }

    private String mode() {
        String mode = nacosConfig.getMcp().getDiscoveryMode();
        return mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase();
    }

    @PostConstruct
    public void init() {
        if (!enabled()) {
            log.info("Nacos MCP discovery disabled (nacos.enabled/mcp.enabled=false or Nacos unavailable)");
            return;
        }
        try {
            scan();
            initialized = true;
            log.info("Nacos MCP discovery initialized (mode={}), subscribed {} service(s), connected {} MCP instance(s)",
                    mode(), subscribedServices.size(), connectedServers.size());
        } catch (Exception e) {
            recordFailure("discovery-init", e);
        }
    }

    /**
     * 定时扫描：发现新资源，并在启动失败后重试初始化。
     */
    @Scheduled(fixedDelayString = "${nacos.scan-interval-ms:30000}")
    public void periodicScan() {
        if (!enabled()) return;
        try {
            boolean first = !initialized;
            scan();
            if (first) {
                initialized = true;
                log.info("Nacos MCP discovery recovered, connected {} MCP instance(s)", connectedServers.size());
            }
        } catch (Exception e) {
            recordFailure("periodic-scan", e);
        }
    }

    private void scan() {
        String mode = mode();
        boolean registryFound = false;
        if (!"naming".equals(mode)) {
            registryFound = scanRegistry();
        }
        if ("naming".equals(mode) || ("auto".equals(mode) && !registryFound)) {
            scanNaming();
        }
    }

    // ========== Registry 模式 ==========

    /**
     * @return 是否发现至少一个 Registry MCP 资源
     */
    private boolean scanRegistry() {
        if (aiService == null) return false;
        List<String> names = resolveRegistryNames();
        if (names.isEmpty()) {
            if (!initialized) {
                log.info("Nacos AI Registry has no MCP resource in namespace '{}'", namespaceId());
            }
            return false;
        }
        boolean found = false;
        for (String name : names) {
            try {
                McpServerDetailInfo info = aiService.mcp().getMcpServer(name);
                if (info == null) {
                    log.debug("Nacos MCP '{}' not found in AI Registry", name);
                    continue;
                }
                found = true;
                applyRegistryEndpoints(name, info);
                subscribeRegistryMcp(name);
            } catch (NacosException e) {
                if (isNotFound(e)) {
                    log.debug("Nacos MCP '{}' not found in AI Registry", name);
                } else {
                    recordFailure("getMcpServer:" + name, e);
                }
            } catch (Exception e) {
                recordFailure("getMcpServer:" + name, e);
            }
        }
        if (found && availabilityGate != null) availabilityGate.recordSuccess(DOMAIN);
        return found;
    }

    private List<String> resolveRegistryNames() {
        List<String> configured = nacosConfig.getMcp().getRegistryNames();
        if (configured != null && !configured.isEmpty()) {
            return configured.stream()
                    .filter(n -> n != null && !n.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
        return searchRegistryMcpNames();
    }

    /**
     * Client API 3.17 {@code GET /v3/client/ai/mcp/search}：运行时枚举 MCP 资源。
     */
    private List<String> searchRegistryMcpNames() {
        List<String> names = new ArrayList<>();
        String base = clientBaseUrl();
        try {
            String url = UriComponentsBuilder.fromUriString(base)
                    .path("/v3/client/ai/mcp/search")
                    .queryParam("namespaceId", namespaceId())
                    .queryParam("pageNo", 1)
                    .queryParam("pageSize", 100)
                    .build()
                    .encode()
                    .toUriString();
            String body = httpGet(url, base);
            if (body == null || body.isBlank()) return names;
            JsonNode data = JSON.readTree(body).path("data");
            JsonNode pageItems = data.get("pageItems");
            if (pageItems == null || !pageItems.isArray()) return names;
            for (JsonNode item : pageItems) {
                String name = item.path("name").asText(null);
                if (name != null && !name.isBlank()) names.add(name);
            }
        } catch (Exception e) {
            log.debug("Failed to search AI Registry MCP resources: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 将某个 Registry MCP 的 backendEndpoints 同步为本地连接：新增连接、移除失效连接。
     */
    private void applyRegistryEndpoints(String mcpName, McpServerDetailInfo info) {
        Map<String, String> desired = new LinkedHashMap<>();
        List<McpEndpointInfo> endpoints = info.getBackendEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            log.debug("Nacos MCP '{}' has no backend endpoint registered", mcpName);
        } else {
            for (McpEndpointInfo endpoint : endpoints) {
                String url = endpointUrl(endpoint);
                if (url == null) continue;
                desired.put(url, registryInstanceName(mcpName, url));
            }
        }

        String owner = REGISTRY_OWNER_PREFIX + mcpName;
        Set<String> stale = new HashSet<>();
        instanceOwnerService.forEach((instance, ownerService) -> {
            if (owner.equals(ownerService) && !desired.containsValue(instance)) {
                stale.add(instance);
            }
        });
        for (String instance : stale) {
            disconnect(instance);
        }

        desired.forEach((url, instance) -> {
            if (connectedServers.contains(instance)) return;
            String existing = urlToInstance.get(url);
            if (existing != null && connectedServers.contains(existing)) return;
            connectSse(instance, url, owner);
        });
    }

    /**
     * 由端点信息组装 SSE URL。非 SSE 端点（如 streamable-http/stdio）本轮不支持，
     * 打 WARN 后跳过，避免影响现有 naming 连接。
     */
    private String endpointUrl(McpEndpointInfo endpoint) {
        if (endpoint == null) return null;
        String address = endpoint.getAddress();
        if (address == null || address.isBlank() || endpoint.getPort() <= 0) {
            return null;
        }
        String protocol = endpoint.getProtocol() == null ? "" : endpoint.getProtocol().trim().toLowerCase();
        String path = endpoint.getPath();
        if (path == null || path.isBlank()) {
            path = nacosConfig.getMcp().getSsePath();
        }
        if (!path.startsWith("/")) path = "/" + path;

        if ("sse".equals(protocol)) {
            return "http://" + address + ":" + endpoint.getPort() + path;
        }
        if (protocol.isEmpty() && path.endsWith("/sse")) {
            return "http://" + address + ":" + endpoint.getPort() + path;
        }
        log.warn("Nacos MCP endpoint protocol '{}' is not supported by this client yet "
                        + "(only SSE); address={}:{}, path={} will be skipped",
                protocol, address, endpoint.getPort(), path);
        return null;
    }

    private String registryInstanceName(String mcpName, String url) {
        return "nacos:" + mcpName + ":" + url.replaceFirst("^https?://", "").replace('/', '_');
    }

    private void subscribeRegistryMcp(String mcpName) {
        if (aiService == null || mcpListeners.containsKey(mcpName)) return;
        AbstractNacosMcpServerListener listener = new AbstractNacosMcpServerListener() {
            @Override
            public void onEvent(NacosMcpServerEvent event) {
                McpServerDetailInfo info = event.getMcpServerDetailInfo();
                if (info == null) return;
                log.info("Nacos MCP '{}' changed, reconciling endpoints...", mcpName);
                try {
                    applyRegistryEndpoints(mcpName, info);
                } catch (Exception e) {
                    log.warn("Failed to apply Nacos MCP '{}' update: {}", mcpName, e.getMessage());
                }
            }
        };
        try {
            aiService.mcp().subscribeMcpServer(mcpName, null, listener);
            mcpListeners.put(mcpName, listener);
            log.info("Subscribed to Nacos AI Registry MCP '{}'", mcpName);
        } catch (NacosException e) {
            if (!isNotFound(e)) {
                recordFailure("subscribeMcpServer:" + mcpName, e);
            }
        } catch (Exception e) {
            log.debug("Failed to subscribe MCP '{}': {}", mcpName, e.getMessage());
        }
    }

    private void connectSse(String instanceName, String url, String owner) {
        boolean connected = mcpHubService.connectSse(instanceName, url, true, McpServerDefinition.SOURCE_NACOS);
        if (connected) {
            connectedServers.add(instanceName);
            instanceOwnerService.put(instanceName, owner);
            urlToInstance.put(url, instanceName);
            log.info("Nacos: connected MCP '{}' -> {}", instanceName, url);
        } else {
            log.warn("Nacos: failed to connect MCP '{}' -> {}", instanceName, url);
            if (availabilityGate != null) availabilityGate.recordFailure(DOMAIN, "connect:" + instanceName);
        }
    }

    private void disconnect(String instanceName) {
        log.info("Nacos: MCP instance '{}' disappeared, disconnecting", instanceName);
        mcpHubService.disconnect(instanceName);
        connectedServers.remove(instanceName);
        instanceOwnerService.remove(instanceName);
        urlToInstance.values().removeIf(instanceName::equals);
    }

    // ========== naming 模式（旧行为，保留） ==========

    private void scanNaming() {
        if (namingService == null) return;
        List<String> services = resolveServiceNames();
        if (services.isEmpty()) {
            if (!initialized) {
                log.info("Nacos MCP discovery has no configured service; set nacos.discover-services to enable naming mode");
            }
            return;
        }
        for (String sn : services) {
            if (subscribedServices.contains(sn)) continue;
            try {
                List<Instance> instances = namingService.selectInstances(sn, true);
                for (Instance inst : instances) {
                    connectNamingInstance(inst, sn);
                }
                subscribeToService(sn);
            } catch (Exception e) {
                recordFailure("selectInstances:" + sn, e);
            }
        }
        if (availabilityGate != null) availabilityGate.recordSuccess(DOMAIN);
    }

    private List<String> resolveServiceNames() {
        List<String> names = new ArrayList<>();
        if (nacosConfig.getDiscoverServices() != null && !nacosConfig.getDiscoverServices().isBlank()) {
            Arrays.stream(nacosConfig.getDiscoverServices().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(names::add);
        }
        if (nacosConfig.getServiceNames() != null) {
            names.addAll(nacosConfig.getServiceNames());
        }
        if (names.isEmpty() && nacosConfig.getServiceName() != null
                && !nacosConfig.getServiceName().isBlank()) {
            names.add(nacosConfig.getServiceName());
        }
        List<String> deduped = new ArrayList<>();
        for (String n : names) {
            if (!deduped.contains(n)) deduped.add(n);
        }
        return deduped;
    }

    private void subscribeToService(String serviceName) {
        if (namingService == null || subscribedServices.contains(serviceName)) return;
        try {
            namingService.subscribe(serviceName, namingEventHandler);
            subscribedServices.add(serviceName);
            log.info("Subscribed to Nacos service '{}'", serviceName);
        } catch (Exception e) {
            log.warn("Failed to subscribe to service '{}': {}", serviceName, e.getMessage());
        }
    }

    private final EventListener namingEventHandler = this::handleNamingEvent;

    private void handleNamingEvent(Event event) {
        if (!(event instanceof NamingEvent namingEvent)) return;
        String eventServiceName = namingEvent.getServiceName();
        if (!resolveServiceNames().contains(eventServiceName)) return;
        log.debug("Nacos naming event for service '{}': {} instances",
                eventServiceName, namingEvent.getInstances().size());

        Set<String> currentNames = new HashSet<>();
        for (Instance inst : namingEvent.getInstances()) {
            if (!inst.isEnabled() || !inst.isHealthy()) continue;
            String name = buildInstanceName(inst);
            currentNames.add(name);
            if (!connectedServers.contains(name)) {
                connectNamingInstance(inst, eventServiceName);
            }
        }

        for (String name : connectedServers) {
            String ownerService = instanceOwnerService.get(name);
            if (!eventServiceName.equals(ownerService)) continue;
            if (!currentNames.contains(name)) {
                disconnect(name);
            }
        }
    }

    private void connectNamingInstance(Instance inst, String serviceName) {
        String name = buildInstanceName(inst);
        if (connectedServers.contains(name)) return;

        // 只使用 IP:端口推导 SSE 地址；不执行注册中心里的命令（安全）
        String path = nacosConfig.getMcp().getSsePath();
        if (path == null || path.isBlank()) path = "/mcp/sse";
        if (!path.startsWith("/")) path = "/" + path;
        String url = "http://" + inst.getIp() + ":" + inst.getPort() + path;

        String existing = urlToInstance.get(url);
        if (existing != null && connectedServers.contains(existing)) {
            log.debug("Nacos: MCP endpoint {} already connected as '{}', skipping '{}'",
                    url, existing, name);
            return;
        }

        // source=nacos：不写入本地持久化文件；断连自愈交给 McpHubService 健康检查
        boolean connected = mcpHubService.connectSse(name, url, true, McpServerDefinition.SOURCE_NACOS);
        if (connected) {
            connectedServers.add(name);
            instanceOwnerService.put(name, serviceName);
            urlToInstance.put(url, name);
            log.info("Nacos: connected MCP '{}' from service '{}'", name, serviceName);
        } else {
            log.warn("Nacos: failed to connect MCP '{}' (service '{}')", name, serviceName);
            if (availabilityGate != null) availabilityGate.recordFailure(DOMAIN, "connect:" + name);
        }
    }

    private String buildInstanceName(Instance inst) {
        return "sse:" + inst.getIp() + "_" + inst.getPort();
    }

    // ========== 帮助方法 ==========

    private String namespaceId() {
        String ns = nacosConfig.getNamespace();
        return ns == null || ns.isBlank() ? "public" : ns;
    }

    private String clientBaseUrl() {
        String server = nacosConfig.getServerAddr() == null ? "127.0.0.1:8848"
                : nacosConfig.getServerAddr().split(",")[0].trim();
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            server = "http://" + server;
        }
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        if (!server.endsWith("/nacos")) server = server + "/nacos";
        return server;
    }

    private String httpGet(String url, String baseUrl) {
        String token = tokenProvider == null ? null : tokenProvider.getToken(baseUrl);
        return restClient.get()
                .uri(url)
                .headers(headers -> {
                    if (token != null && !token.isBlank()) headers.set("accessToken", token);
                })
                .retrieve()
                .body(String.class);
    }

    private static boolean isNotFound(NacosException e) {
        int code = e.getErrCode();
        return code == NacosException.NOT_FOUND || code == NacosException.RESOURCE_NOT_FOUND
                || code == 20004;
    }

    private void recordFailure(String operation, Exception e) {
        if (availabilityGate != null) availabilityGate.recordFailure(DOMAIN, operation);
        log.warn("Nacos MCP {} failed: {}", operation, e.getMessage());
    }

    @PreDestroy
    public void destroy() {
        if (aiService != null) {
            mcpListeners.forEach((name, listener) -> {
                try {
                    aiService.mcp().unsubscribeMcpServer(name, null, listener);
                } catch (Exception e) {
                    log.debug("Nacos unsubscribe MCP '{}' skipped: {}", name, e.getMessage());
                }
            });
        }
        mcpListeners.clear();

        if (namingService != null) {
            for (String svc : subscribedServices) {
                try {
                    namingService.unsubscribe(svc, namingEventHandler);
                } catch (Exception e) {
                    log.debug("Nacos unsubscribe from '{}' skipped: {}", svc, e.getMessage());
                }
            }
        }
        subscribedServices.clear();
        connectedServers.clear();
        instanceOwnerService.clear();
        urlToInstance.clear();
        log.info("Nacos MCP discovery shutdown complete");
    }
}
