package com.cetc28.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos accessToken 提供者 — 仅用于 Client API 调用。
 *
 * <p>官方 3.3 默认开启 Client 鉴权，运行时枚举资源（Skill/MCP 搜索）必须带
 * {@code accessToken} 请求头。这里用官方 v3 登录接口获取并缓存 token，
 * 过期前刷新；调用方遇到 401/403 时调用 {@link #invalidate(String)} 后重试一次。</p>
 *
 * <p>Prompt / Skill 内容 / MCP 详情走 SDK，token 由 SDK 内部管理，不经过这里。</p>
 */
@Component
public class NacosTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(NacosTokenProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** token 提前 60 秒过期，避免边界 */
    private static final long EXPIRE_SKEW_SECONDS = 60;

    private final RestClient restClient;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Object loginLock = new Object();

    @Autowired
    private NacosConfig nacosConfig;

    public NacosTokenProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 获取指定 Nacos 基地址的 accessToken；登录失败返回 null（调用方可选择匿名请求）。
     *
     * @param baseUrl 例如 http://127.0.0.1:8848/nacos 或 http://127.0.0.1:8080
     */
    public String getToken(String baseUrl) {
        String key = normalize(baseUrl);
        Token cached = tokens.get(key);
        if (cached != null && cached.valid()) return cached.value;
        synchronized (loginLock) {
            cached = tokens.get(key);
            if (cached != null && cached.valid()) return cached.value;
            Token fresh = login(key);
            if (fresh != null) {
                tokens.put(key, fresh);
                return fresh.value;
            }
            return null;
        }
    }

    /** token 失效（401/403）时调用，下次 getToken 会重新登录 */
    public void invalidate(String baseUrl) {
        tokens.remove(normalize(baseUrl));
    }

    private Token login(String baseUrl) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("username", nacosConfig.getUsername());
            form.add("password", nacosConfig.getPassword());
            String body = restClient.post()
                    .uri(baseUrl + "/v3/auth/user/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) return null;
            JsonNode root = JSON.readTree(body);
            JsonNode tokenNode = root.get("accessToken");
            if (tokenNode == null || tokenNode.asText().isBlank()) return null;
            long ttl = root.path("tokenTtl").asLong(18000);
            long expiresAt = System.currentTimeMillis()
                    + Math.max(30, ttl - EXPIRE_SKEW_SECONDS) * 1000L;
            log.debug("Nacos accessToken acquired from {}, ttl={}s", baseUrl, ttl);
            return new Token(tokenNode.asText(), expiresAt);
        } catch (Exception e) {
            log.debug("Nacos login failed at {}: {}", baseUrl, e.getMessage());
            return null;
        }
    }

    private static String normalize(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record Token(String value, long expiresAt) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }
}
