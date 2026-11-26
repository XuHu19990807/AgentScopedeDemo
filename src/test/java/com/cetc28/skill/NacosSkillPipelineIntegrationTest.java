package com.cetc28.skill;

import com.cetc28.config.NacosConfig;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试 — 直连本地 Nacos skill server (127.0.0.1:8080) 验证技能加载管道。
 * 需要外部 Nacos，默认不随 `mvn test` 运行：
 *   mvn test -Dgroups=integration
 */
@Tag("integration")
class NacosSkillPipelineIntegrationTest {

    @Test
    void loadSkillContentFromNacos() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                isNacosReachable(), "Nacos skill server 不可达，跳过集成测试");

        NacosConfig cfg = new NacosConfig();
        cfg.setEnabled(true);
        cfg.setServerAddr("127.0.0.1:8848");
        cfg.setSkillServerAddr("127.0.0.1:8080");
        cfg.setUsername("nacos");
        cfg.setPassword("nacos");
        cfg.setSkillNamespace("public");

        NacosSkillClient client = new NacosSkillClient();
        setField(client, "nacosConfig", cfg);

        NacosSkillRepository repo = new NacosSkillRepository();
        setField(repo, "nacosSkillClient", client);
        repo.init(); // 从 Nacos 拉取技能列表

        List<String> names = repo.getAllSkillNames();
        System.out.println("Nacos skills: " + names);
        assertFalse(names.isEmpty(), "应能从 Nacos 拉到技能");

        // 关键验证：所有 getSkill/getAllSkills 返回的技能内容必须非空（不允许 "must have content" 异常）
        for (AgentSkill s : repo.getAllSkills()) {
            System.out.println("skill '" + s.getName() + "' content len=" + s.getSkillContent().length());
            assertFalse(s.getSkillContent().isBlank(), s.getName() + " 内容不应为空");
        }

        AgentSkill findSkills = repo.getSkill("find-skills");
        assertNotNull(findSkills, "find-skills 应可加载");
        System.out.println("find-skills 内容开头: " + findSkills.getSkillContent().substring(0, Math.min(100, findSkills.getSkillContent().length())));

        // 资源列表允许为空（技能可能没有资源），但不能因 ZIP 地址错误抛异常。
        assertNotNull(repo.resourcesFor("find-skills", null).list());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Nacos skill server 是否可达（8080 端口） */
    private boolean isNacosReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8080), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
