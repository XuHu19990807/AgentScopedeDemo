package com.cetc28.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSkillRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSkillFromDirectory() throws Exception {
        Path skillDir = tempDir.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# 回答风格\n\n- 先给结论\n");

        LocalSkillRepository repository = repository();

        assertTrue(repository.getAllSkillNames().contains("demo"));
        assertEquals("回答风格", repository.getSkill("demo").getDescription());
        assertTrue(repository.getSkill("demo").getSkillContent().contains("先给结论"));
    }

    @Test
    void loadsFrontmatterAndReferencesLazily() throws Exception {
        Path skillDir = tempDir.resolve("demo");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: frontmatter-name-is-ignored
                description: 前端规范
                ---

                # 正文

                使用 references/guide.md。
                """);
        Files.writeString(skillDir.resolve("references/guide.md"), "guide-content");

        LocalSkillRepository repository = repository();
        AgentSkill skill = repository.getSkill("demo");

        assertEquals("demo", skill.getName(), "本地技能名应保持目录名，避免 skill-names 失效");
        assertEquals("前端规范", skill.getDescription());
        assertFalse(skill.getSkillContent().contains("description:"));
        assertEquals(skillDir.toAbsolutePath().normalize(), skill.getOriginDir().orElseThrow());

        SkillResources resources = repository.resourcesFor("demo", null);
        assertEquals(List.of("references/guide.md"), resources.list());
        assertEquals("guide-content", resources.read("references/guide.md").orElseThrow());
        assertTrue(resources.read("SKILL.md").isEmpty(), "SKILL.md 本身不算资源");
    }

    @Test
    void binaryResourceIsBase64AndPathTraversalIsRejected() throws Exception {
        Path skillDir = tempDir.resolve("demo");
        Files.createDirectories(skillDir.resolve("assets"));
        Files.writeString(skillDir.resolve("SKILL.md"), "# demo\n\nbody\n");
        Files.write(skillDir.resolve("assets/logo.bin"), new byte[]{0, (byte) 0xFF, 1});

        SkillResources resources = repository().resourcesFor("demo", null);

        String text = resources.read("assets/logo.bin").orElseThrow();
        assertTrue(text.startsWith("base64:"));
        assertTrue(resources.read("../SKILL.md").isEmpty());
        assertTrue(resources.read("/etc/passwd").isEmpty());
        assertTrue(resources.read("assets/../../SKILL.md").isEmpty());
    }

    @Test
    void missingDirectoryReturnsEmpty() {
        LocalSkillRepository repository = new LocalSkillRepository();
        ReflectionTestUtils.setField(repository, "skillsDir", tempDir.resolve("not-exists").toString());
        assertTrue(repository.getAllSkillNames().isEmpty());
    }

    private LocalSkillRepository repository() {
        LocalSkillRepository repository = new LocalSkillRepository();
        ReflectionTestUtils.setField(repository, "skillsDir", tempDir.toString());
        return repository;
    }
}
