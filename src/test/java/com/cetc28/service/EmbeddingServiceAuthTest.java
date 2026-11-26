package com.cetc28.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对"本地无鉴权 embedding 服务"的单测。
 *
 * <p>背景：内网自建 embedding（Ollama / vLLM / TEI 等）不校验密钥，
 * 旧实现要求 api-key 非空才启用、且无条件发送 {@code Authorization: Bearer <key>}，
 * 导致无 key 部署根本用不上。这里用 JDK 自带 HttpServer 起一个假的 /v1/embeddings，
 * 断言：无 key 时能正常取到向量且请求不携带认证头；有 key 时仍照常发送。
 */
class EmbeddingServiceAuthTest {

    private static final String RESPONSE_BODY =
            "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                    + "\"embedding\":[0.1,0.2,0.3]}],\"model\":\"bge-m3\"}";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startFakeServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            lastHeaders.set(exchange.getRequestHeaders());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopFakeServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private EmbeddingService newService(String apiKey) {
        EmbeddingService service = new EmbeddingService();
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "model", "bge-m3");
        ReflectionTestUtils.setField(service, "maxTokens", 512);
        return service;
    }

    @Test
    void blankApiKeyStillEnabledAndSendsNoAuthHeader() {
        EmbeddingService service = newService("");

        assertTrue(service.isEnabled(), "api-key 为空时也应当可用（本地无鉴权服务）");

        float[] vector = service.embed("预算多少");

        assertNotNull(vector, "无 key 时也应成功拿到向量，错误：" + service.getLastError());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector, 1e-6f);
        assertEquals(3, service.getDimension());

        Headers headers = lastHeaders.get();
        assertNotNull(headers, "假服务端应当收到请求");
        assertNull(headers.getFirst("Authorization"), "无 key 时不应发送 Authorization 头");
        assertTrue(lastBody.get().contains("\"input\":\"预算多少\""),
                "请求体应包含 input，实际：" + lastBody.get());
    }

    @Test
    void configuredApiKeyIsSentAsBearerToken() {
        EmbeddingService service = newService("sk-test-123");

        assertTrue(service.isEnabled());
        assertNotNull(service.embed("预算多少"));
        assertEquals("Bearer sk-test-123", lastHeaders.get().getFirst("Authorization"));
    }

    @Test
    void missingBaseUrlOrModelKeepsServiceDisabled() {
        EmbeddingService noBaseUrl = newService("");
        ReflectionTestUtils.setField(noBaseUrl, "baseUrl", "");
        assertFalse(noBaseUrl.isEnabled(), "base-url 为空时必须保持禁用");

        EmbeddingService noModel = newService("");
        ReflectionTestUtils.setField(noModel, "model", "  ");
        assertFalse(noModel.isEnabled(), "model 为空白时必须保持禁用");
    }
}
