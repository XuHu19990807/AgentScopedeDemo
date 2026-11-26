package com.cetc28.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSkillRepositoryTest {

    private static AgentSkill skill(String name, String content) {
        return AgentSkill.builder().name(name).description(name + "-desc").skillContent(content).source("test").build();
    }

    @Test
    void localSkillWinsOnNameConflict() {
        FakeRepo local = new FakeRepo("local", Map.of("demo", skill("demo", "local-content")));
        FakeRepo remote = new FakeRepo("remote", Map.of("demo", skill("demo", "remote-content"),
                "extra", skill("extra", "remote-extra")));

        CompositeSkillRepository composite = new CompositeSkillRepository(List.of(local, remote));

        assertEquals("local-content", composite.getSkill("demo").getSkillContent());
        assertTrue(composite.getAllSkillNames().contains("extra"));
        assertEquals(2, composite.getAllSkillNames().size());
    }

    @Test
    void toleratesFailingDelegate() {
        AgentSkillRepository broken = new FakeRepo("broken", Map.of()) {
            @Override
            public List<String> getAllSkillNames() {
                throw new IllegalStateException("boom");
            }
        };
        FakeRepo local = new FakeRepo("local", Map.of("demo", skill("demo", "ok")));
        CompositeSkillRepository composite = new CompositeSkillRepository(List.of(broken, local));
        assertEquals(List.of("demo"), composite.getAllSkillNames());
        assertEquals("ok", composite.getSkill("demo").getSkillContent());
    }

    private static class FakeRepo implements AgentSkillRepository {
        private final String source;
        private final Map<String, AgentSkill> skills;

        FakeRepo(String source, Map<String, AgentSkill> skills) {
            this.source = source;
            this.skills = new LinkedHashMap<>(skills);
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(skills.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.get(name);
        }

        @Override
        public boolean skillExists(String name) {
            return skills.containsKey(name);
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean overwrite) {
            return false;
        }

        @Override
        public boolean delete(String name) {
            return false;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, source, false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
        }

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
