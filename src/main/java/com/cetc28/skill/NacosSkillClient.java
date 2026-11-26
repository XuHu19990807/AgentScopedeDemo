package com.cetc28.skill;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.cetc28.config.NacosAvailabilityGate;
import com.cetc28.config.NacosConfig;
import com.cetc28.config.NacosTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos AI Skill 客户端。
 *
 * <p>运行时接口（默认，{@code nacos.skill.discovery=client}）：</p>
 * <ul>
 *   <li>列表：Client API {@code GET /v3/client/ai/skills/search}（唯一可用的运行时枚举接口）；</li>
 *   <li>内容：官方 SDK {@code AiService.skill().downloadSkillZipByLabel/ByVersion}；</li>
 *   <li>下载兜底：Client API {@code GET /v3/client/ai/skills}（version/label 二选一）。</li>
 * </ul>
 *
 * <p>兼容模式（{@code nacos.skill.discovery=console}）保留旧的 Console API 列表/详情，
 * 用于需要查看草稿技能的场景。</p>
 */
@Component
public class NacosSkillClient {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOMAIN = "skill";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_SKILL_ZIP_BYTES = 20 * 1024 * 1024;
    private static final int MAX_CONSOLE_RESOURCES = 1000;
    private static final int MAX_CONSOLE_RESOURCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_CONSOLE_TOTAL_BYTES = 20 * 1024 * 1024;
    private static final String LATEST_LABEL = "latest";

    private final RestClient restClient;
    private final RestClient zipRestClient;

    @Autowired
    private NacosConfig nacosConfig;

    @Autowired
    private NacosAvailabilityGate availabilityGate;

    @Autowired
    private NacosTokenProvider tokenProvider;

    @Autowired(required = false)
    private AiService aiService;

    public NacosSkillClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();

        // ZIP 包可能较大，不能复用普通读超时
        SimpleClientHttpRequestFactory zipFactory = new SimpleClientHttpRequestFactory();
        zipFactory.setConnectTimeout(3000);
        zipFactory.setReadTimeout(30_000);
        this.zipRestClient = RestClient.builder().requestFactory(zipFactory).build();
    }

    private boolean enabled() {
        return nacosConfig != null && nacosConfig.isSkillEnabled()
                && availabilityGate != null && availabilityGate.isAvailable(DOMAIN);
    }

    private boolean consoleMode() {
        return "console".equalsIgnoreCase(nacosConfig.getSkill().getDiscovery());
    }

    /**
     * 控制台 API 基地址：/v3/console/ai/skills/** 与 /v3/auth/user/login。
     * 仅 console 兼容模式使用。
     */
    String consoleBaseUrl() {
        return normalizeBaseUrl(nacosConfig.getSkillConsoleBaseUrl(),
                "http://" + nacosConfig.getSkillServerAddr());
    }

    /**
     * 客户端 API 基地址：/v3/client/ai/skills/**。
     * 默认 http://{server-addr}/nacos。
     */
    String clientBaseUrl() {
        String server = firstServerAddr();
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            server = "http://" + server;
        }
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        if (!server.endsWith("/nacos")) {
            server = server + "/nacos";
        }
        return normalizeBaseUrl(nacosConfig.getSkillClientBaseUrl(), server);
    }

    private String firstServerAddr() {
        String raw = nacosConfig.getServerAddr();
        if (raw == null || raw.isBlank()) return "127.0.0.1:8848";
        return raw.split(",")[0].trim();
    }

    private static String normalizeBaseUrl(String configured, String fallback) {
        String value = configured == null || configured.isBlank() ? fallback : configured.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    // ========== 技能列表 ==========

    /**
     * 获取 Nacos 上当前可见的 AI 技能列表（分页拉取，最多 10 页）。
     */
    public List<SkillEntry> listSkills() {
        if (!enabled()) return List.of();
        return consoleMode() ? listFromConsole() : listFromClientApi();
    }

    /**
     * 官方运行时枚举：Client API 3.18 {@code GET /v3/client/ai/skills/search}。
     * 只返回 online 且可见的 Skill，pageItems 不含版本/标签。
     */
    private List<SkillEntry> listFromClientApi() {
        List<SkillEntry> all = new ArrayList<>();
        String base = clientBaseUrl();
        try {
            for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
                String url = UriComponentsBuilder.fromUriString(base)
                        .path("/v3/client/ai/skills/search")
                        .queryParam("namespaceId", nacosConfig.getSkillNamespace())
                        .queryParam("pageNo", pageNo)
                        .queryParam("pageSize", PAGE_SIZE)
                        .build()
                        .encode()
                        .toUriString();
                String result = httpGet(url, base, restClient);
                if (result == null || result.isBlank()) break;

                JsonNode root = JSON.readTree(result);
                JsonNode data = root.get("data");
                if (data == null || !data.isObject()) break;
                JsonNode pageItems = data.get("pageItems");
                if (pageItems == null || !pageItems.isArray() || pageItems.isEmpty()) break;

                for (JsonNode item : pageItems) {
                    String name = item.path("name").asText(null);
                    if (name == null || name.isBlank()) continue;
                    String desc = item.path("description").asText("");
                    String updateTime = item.hasNonNull("updateTime")
                            ? item.get("updateTime").asText() : null;
                    all.add(new SkillEntry(name, desc, null, updateTime));
                }
                if (pageItems.size() < PAGE_SIZE) break;
            }
            availabilityGate.recordSuccess(DOMAIN);
            log.info("Discovered {} Nacos skills via Client API", all.size());
            return all;
        } catch (Exception e) {
            availabilityGate.recordFailure(DOMAIN, "skills-search");
            log.warn("Failed to list Nacos skills via Client API: {}", e.getMessage());
            return all.isEmpty() ? List.of() : all;
        }
    }

    /**
     * 兼容模式：旧控制台列表接口。官方 Console API 无 username 参数，这里不再发送。
     */
    private List<SkillEntry> listFromConsole() {
        List<SkillEntry> all = new ArrayList<>();
        String base = consoleBaseUrl();
        try {
            for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
                String url = UriComponentsBuilder.fromUriString(base)
                        .path("/v3/console/ai/skills/list")
                        .queryParam("namespaceId", nacosConfig.getSkillNamespace())
                        .queryParam("pageNo", pageNo)
                        .queryParam("pageSize", PAGE_SIZE)
                        .build()
                        .encode()
                        .toUriString();
                String result = httpGet(url, base, restClient);
                if (result == null || result.isBlank()) break;

                JsonNode root = JSON.readTree(result);
                JsonNode data = root.get("data");
                if (data == null || !data.isObject()) break;
                JsonNode pageItems = data.get("pageItems");
                if (pageItems == null || !pageItems.isArray() || pageItems.isEmpty()) break;

                for (JsonNode item : pageItems) {
                    String name = item.path("name").asText(null);
                    if (name == null || name.isBlank()) continue;
                    String desc = item.path("description").asText("");
                    String latestVersion = null;
                    JsonNode labels = item.get("labels");
                    if (labels != null && labels.isObject() && labels.has(LATEST_LABEL)) {
                        latestVersion = labels.get(LATEST_LABEL).asText();
                    }
                    String updateTime = item.hasNonNull("updateTime")
                            ? item.get("updateTime").asText() : null;
                    all.add(new SkillEntry(name, desc, latestVersion, updateTime));
                }
                if (pageItems.size() < PAGE_SIZE) break;
            }
            availabilityGate.recordSuccess(DOMAIN);
            log.info("Discovered {} Nacos skills via Console API (compat mode)", all.size());
            return all;
        } catch (Exception e) {
            availabilityGate.recordFailure(DOMAIN, "skills-list");
            log.warn("Failed to list Nacos skills via Console API: {}", e.getMessage());
            return all.isEmpty() ? List.of() : all;
        }
    }

    // ========== 技能内容 ==========

    /**
     * 优先用官方 SDK 下载完整 ZIP：显式版本走 {@code downloadSkillZipByVersion}，
     * 否则走 {@code downloadSkillZipByLabel(name, "latest")}（latest 是标签，不是版本号）。
     */
    public byte[] downloadSkillZip(String skillName, String version) {
        if (!nacosConfig.isSkillEnabled()) return null;

        if (aiService != null && availabilityGate.isAvailable(DOMAIN)) {
            try {
                byte[] zip = isPinned(version)
                        ? aiService.skill().downloadSkillZipByVersion(skillName, version)
                        : aiService.skill().downloadSkillZipByLabel(skillName, LATEST_LABEL);
                if (zip != null && zip.length > 0) {
                    if (zip.length > MAX_SKILL_ZIP_BYTES) {
                        log.warn("Nacos skill zip for '{}' is too large: {} bytes", skillName, zip.length);
                        return null;
                    }
                    availabilityGate.recordSuccess(DOMAIN);
                    return zip;
                }
            } catch (NacosException e) {
                if (isNotFound(e)) {
                    log.info("Nacos skill '{}' has no online version ({}); trying Client API",
                            skillName, e.getErrMsg());
                } else {
                    availabilityGate.recordFailure(DOMAIN, "downloadSkillZip:" + skillName);
                    log.warn("Nacos SDK download skill '{}' failed: {}", skillName, e.getErrMsg());
                }
            } catch (Exception e) {
                availabilityGate.recordFailure(DOMAIN, "downloadSkillZip:" + skillName);
                log.warn("Nacos SDK download skill '{}' failed: {}", skillName, e.getMessage());
            }
        }

        return downloadZipViaClientApi(skillName, version);
    }

    /**
     * Client API 3.4 兜底：{@code GET /v3/client/ai/skills}，version 与 label 二选一。
     */
    private byte[] downloadZipViaClientApi(String skillName, String version) {
        String base = clientBaseUrl();
        String url = buildClientZipUrl(base, skillName, version);
        try {
            ResponseEntity<byte[]> response = httpGetForEntity(url, base, zipRestClient);
            byte[] body = response == null ? null : response.getBody();
            if (body == null || body.length == 0) {
                log.warn("Nacos skill Client API returned empty body for '{}'", skillName);
                return null;
            }
            if (body.length > MAX_SKILL_ZIP_BYTES) {
                log.warn("Nacos skill zip for '{}' is too large: {} bytes", skillName, body.length);
                return null;
            }
            availabilityGate.recordSuccess(DOMAIN);
            return body;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Nacos skill '{}' not found on Client API (draft or offline)", skillName);
            return null;
        } catch (RestClientResponseException e) {
            availabilityGate.recordFailure(DOMAIN, "downloadSkillZip");
            log.warn("Nacos skill Client API failed for '{}': status={}, body={}",
                    skillName, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            return null;
        } catch (Exception e) {
            availabilityGate.recordFailure(DOMAIN, "downloadSkillZip");
            log.warn("Nacos skill Client API failed for '{}': {}; url={}", skillName, e.getMessage(), url);
            return null;
        }
    }

    String buildClientZipUrl(String base, String skillName, String version) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base)
                .path("/v3/client/ai/skills")
                .queryParam("namespaceId", nacosConfig.getSkillNamespace())
                .queryParam("name", skillName);
        if (isPinned(version)) {
            builder.queryParam("version", version);
        } else {
            builder.queryParam("label", LATEST_LABEL);
        }
        return builder.build().encode().toUriString();
    }

    /**
     * 兼容模式下的 Console 版本详情（含 draft）。非 console 模式直接返回 null：
     * 官方运行时只服务 online 资源，草稿不应出现在应用侧。
     */
    public SkillVersion getSkillVersion(String skillName, String version) {
        if (!consoleMode() || !enabled()) return null;
        String base = consoleBaseUrl();
        try {
            String ver = resolveConsoleVersion(skillName, version);
            if (ver == null) return null;
            String url = UriComponentsBuilder.fromUriString(base)
                    .path("/v3/console/ai/skills/version")
                    .queryParam("namespaceId", nacosConfig.getSkillNamespace())
                    .queryParam("skillName", skillName)
                    .queryParam("version", ver)
                    .build()
                    .encode()
                    .toUriString();
            String result = httpGet(url, base, restClient);
            if (result == null || result.isBlank()) return null;

            JsonNode root = JSON.readTree(result);
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) return null;

            String skillMd = data.hasNonNull("skillMd") ? data.get("skillMd").asText() : null;
            Map<String, String> resources = new LinkedHashMap<>();
            JsonNode resourceNode = data.get("resource");
            if (resourceNode != null && resourceNode.isObject()) {
                long totalBytes = 0;
                for (Map.Entry<String, JsonNode> entry : resourceNode.properties()) {
                    if (resources.size() >= MAX_CONSOLE_RESOURCES) {
                        log.warn("Nacos console resources for '{}' exceed {} entries, truncated",
                                skillName, MAX_CONSOLE_RESOURCES);
                        break;
                    }
                    JsonNode value = entry.getValue();
                    if (value == null || !value.isObject()) continue;
                    if (!value.hasNonNull("content")) continue;

                    String identifier = value.hasNonNull("resourceIdentifier")
                            ? value.get("resourceIdentifier").asText()
                            : entry.getKey();
                    String content = value.get("content").asText();
                    if (identifier == null || identifier.isBlank() || content == null) continue;

                    int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_CONSOLE_RESOURCE_BYTES) {
                        log.warn("Nacos console resource '{}' of '{}' is too large: {} bytes",
                                identifier, skillName, bytes);
                        continue;
                    }
                    if (totalBytes + bytes > MAX_CONSOLE_TOTAL_BYTES) {
                        log.warn("Nacos console resources for '{}' exceed {} bytes, truncated",
                                skillName, MAX_CONSOLE_TOTAL_BYTES);
                        break;
                    }
                    totalBytes += bytes;
                    resources.put(identifier, content);
                }
            }
            availabilityGate.recordSuccess(DOMAIN);
            return new SkillVersion(skillMd, resources);
        } catch (Exception e) {
            availabilityGate.recordFailure(DOMAIN, "getSkillVersion");
            log.warn("Failed to get Nacos skill version for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * 兼容模式的版本解析：显式版本直接用；否则取 labels.latest，再取第一个 online 版本。
     */
    private String resolveConsoleVersion(String skillName, String version) {
        if (isPinned(version)) return version;
        try {
            String url = UriComponentsBuilder.fromUriString(consoleBaseUrl())
                    .path("/v3/console/ai/skills")
                    .queryParam("namespaceId", nacosConfig.getSkillNamespace())
                    .queryParam("skillName", skillName)
                    .build()
                    .encode()
                    .toUriString();
            String result = httpGet(url, consoleBaseUrl(), restClient);
            if (result == null || result.isBlank()) return null;

            JsonNode data = JSON.readTree(result).path("data");
            JsonNode labels = data.get("labels");
            if (labels != null && labels.isObject() && labels.has(LATEST_LABEL)) {
                String latest = labels.get(LATEST_LABEL).asText("");
                if (!latest.isBlank()) return latest;
            }
            JsonNode versions = data.get("versions");
            if (versions != null && versions.isArray()) {
                for (JsonNode v : versions) {
                    if ("online".equals(v.path("status").asText(""))) {
                        String ver = v.path("version").asText("");
                        if (!ver.isBlank()) return ver;
                    }
                }
                if (!versions.isEmpty()) {
                    String ver = versions.get(0).path("version").asText("");
                    if (!ver.isBlank()) return ver;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to resolve console skill version for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    // ========== HTTP 帮助方法 ==========

    private static boolean isPinned(String version) {
        return version != null && !version.isBlank() && !LATEST_LABEL.equals(version);
    }

    private static boolean isNotFound(NacosException e) {
        int code = e.getErrCode();
        if (code == NacosException.NOT_FOUND || code == NacosException.RESOURCE_NOT_FOUND
                || code == 20004) {
            return true;
        }
        String msg = e.getErrMsg();
        return msg != null && msg.toLowerCase().contains("not found");
    }

    /** 带 accessToken 的 GET；401/403 时刷新 token 重试一次。 */
    private String httpGet(String url, String baseUrl, RestClient client) {
        try {
            return executeGet(url, baseUrl, client);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden first) {
            tokenProvider.invalidate(baseUrl);
            return executeGet(url, baseUrl, client);
        }
    }

    private String executeGet(String url, String baseUrl, RestClient client) {
        String token = tokenProvider.getToken(baseUrl);
        return client.get()
                .uri(url)
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.set("accessToken", token);
                    }
                })
                .retrieve()
                .body(String.class);
    }

    private ResponseEntity<byte[]> httpGetForEntity(String url, String baseUrl, RestClient client) {
        try {
            return executeGetForEntity(url, baseUrl, client);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden first) {
            tokenProvider.invalidate(baseUrl);
            return executeGetForEntity(url, baseUrl, client);
        }
    }

    private ResponseEntity<byte[]> executeGetForEntity(String url, String baseUrl, RestClient client) {
        String token = tokenProvider.getToken(baseUrl);
        return client.get()
                .uri(url)
                .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.set("accessToken", token);
                    }
                })
                .retrieve()
                .toEntity(byte[].class);
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    // ========== 内部类型 ==========

    /**
     * 技能条目。
     *
     * @param latestVersion 兼容模式下的 labels.latest；client 模式为 null（表示 latest 标签）
     * @param updateTime    资源更新时间，用于增量刷新与缓存失效
     */
    public record SkillEntry(String name, String description, String latestVersion, String updateTime) {}

    /**
     * 控制台版本详情：SKILL.md 与资源（资源 key 保留 Nacos 的 {@code type::path} 形式）。
     */
    public record SkillVersion(String skillMd, Map<String, String> resources) {}
}
