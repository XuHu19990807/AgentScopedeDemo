package com.cetc28.skill;

import com.cetc28.config.NacosConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 Client API 下载 URL：latest 是标签，不能塞进 version 参数。
 */
class NacosSkillClientTest {

    private NacosSkillClient clientWithNamespace(String namespace) {
        NacosConfig config = new NacosConfig();
        config.setServerAddr("127.0.0.1:8848");
        config.setSkillNamespace(namespace);
        NacosSkillClient client = new NacosSkillClient();
        ReflectionTestUtils.setField(client, "nacosConfig", config);
        return client;
    }

    @Test
    void latestVersionBecomesLabel() {
        NacosSkillClient client = clientWithNamespace("public");
        String url = client.buildClientZipUrl(client.clientBaseUrl(), "demo", "latest");

        assertTrue(url.contains("/nacos/v3/client/ai/skills"), url);
        assertTrue(url.contains("label=latest"), url);
        assertFalse(url.contains("version="), url);
        assertTrue(url.contains("namespaceId=public"), url);
    }

    @Test
    void nullAndBlankVersionBecomeLabel() {
        NacosSkillClient client = clientWithNamespace("public");

        assertTrue(client.buildClientZipUrl(client.clientBaseUrl(), "demo", null).contains("label=latest"));
        assertTrue(client.buildClientZipUrl(client.clientBaseUrl(), "demo", "  ").contains("label=latest"));
    }

    @Test
    void explicitVersionIsPinned() {
        NacosSkillClient client = clientWithNamespace("public");
        String url = client.buildClientZipUrl(client.clientBaseUrl(), "demo", "1.2.0");

        assertTrue(url.contains("version=1.2.0"), url);
        assertFalse(url.contains("label="), url);
    }

    @Test
    void clientBaseUrlAppendsNacosContextAndCustomOverrideWins() {
        NacosSkillClient client = clientWithNamespace("public");
        assertTrue(client.clientBaseUrl().endsWith("/nacos"), client.clientBaseUrl());

        NacosConfig config = new NacosConfig();
        config.setSkillClientBaseUrl("http://nacos.example.com:8848/nacos");
        ReflectionTestUtils.setField(client, "nacosConfig", config);
        assertTrue(client.clientBaseUrl().startsWith("http://nacos.example.com:8848/nacos"));
    }
}
