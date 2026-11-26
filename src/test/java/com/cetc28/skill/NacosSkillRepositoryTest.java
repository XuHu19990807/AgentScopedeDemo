package com.cetc28.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosSkillRepositoryTest {

    @Test
    void skillContentComesFromSdkZipAndResourcesAreCached() throws Exception {
        FakeClient client = new FakeClient();
        client.entries = List.of(new NacosSkillClient.SkillEntry("demo", "demo desc", null, "t1"));
        client.zip = zip(Map.of(
                "demo/SKILL.md", """
                        ---
                        name: demo
                        description: demo desc
                        ---

                        # demo

                        See references/a.md.
                        """.getBytes(StandardCharsets.UTF_8),
                "demo/references/a.md", "alpha".getBytes(StandardCharsets.UTF_8)));

        NacosSkillRepository repository = new NacosSkillRepository();
        ReflectionTestUtils.setField(repository, "nacosSkillClient", client);
        repository.init();

        List<AgentSkill> skills = repository.getAllSkills();
        assertEquals(1, skills.size());
        assertEquals("demo", skills.get(0).getName());
        assertEquals("demo desc", skills.get(0).getDescription());
        assertFalse(skills.get(0).getSkillContent().contains("description:"));
        assertEquals(1, client.downloads);

        SkillResources resources = repository.resourcesFor("demo", null);
        assertEquals(1, client.downloads);

        assertEquals(List.of("references/a.md"), resources.list());
        assertEquals("alpha", resources.read("references/a.md").orElseThrow());
        assertEquals(1, client.downloads);
    }

    @Test
    void fallsBackToConsoleResourcesWhenZipIsUnavailable() {
        FakeClient client = new FakeClient();
        client.entries = List.of(new NacosSkillClient.SkillEntry("demo", "demo desc", "v1", "t1"));
        client.content = "# demo\n\nSee references/data.json.";
        client.zip = null;
        client.resources = Map.of("references::data.json", "{\"enabled\":true}");

        NacosSkillRepository repository = new NacosSkillRepository();
        ReflectionTestUtils.setField(repository, "nacosSkillClient", client);
        repository.init();

        assertEquals(1, repository.getAllSkills().size());
        assertEquals(1, client.downloads);
        assertEquals(1, client.versionCalls);

        SkillResources resources = repository.resourcesFor("demo", null);
        assertEquals(1, client.downloads);

        assertTrue(resources.list().contains("references/data.json"));
        assertEquals("{\"enabled\":true}", resources.read("references/data.json").orElseThrow());
        assertEquals(1, client.downloads);
        assertEquals(1, client.versionCalls);

        // 第二次读取命中 repository 的 archiveCache，不再下载。
        assertEquals("{\"enabled\":true}",
                repository.resourcesFor("demo", null).read("references/data.json").orElseThrow());
        assertEquals(1, client.downloads);
        assertEquals(1, client.versionCalls);
    }

    @Test
    void failedContentIsNotRetriedEveryCall() {
        FakeClient client = new FakeClient();
        client.entries = List.of(new NacosSkillClient.SkillEntry("broken", "desc", null, "t1"));
        client.zip = null;

        NacosSkillRepository repository = new NacosSkillRepository();
        ReflectionTestUtils.setField(repository, "nacosSkillClient", client);
        repository.init();

        assertEquals(0, repository.getAllSkills().size());
        assertEquals(0, repository.getAllSkills().size());
        assertEquals(1, client.downloads);
        assertEquals(0, repository.getAllSkills().size());
        assertEquals(1, client.downloads);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final class FakeClient extends NacosSkillClient {
        private List<SkillEntry> entries = List.of();
        private String content;
        private byte[] zip;
        private Map<String, String> resources = Map.of();
        private int downloads;
        private int versionCalls;

        @Override
        public List<SkillEntry> listSkills() {
            return entries;
        }

        @Override
        public byte[] downloadSkillZip(String skillName, String version) {
            downloads++;
            return zip;
        }

        @Override
        public SkillVersion getSkillVersion(String skillName, String version) {
            versionCalls++;
            if (content == null && resources.isEmpty()) return null;
            return new SkillVersion(content, resources);
        }
    }
}
