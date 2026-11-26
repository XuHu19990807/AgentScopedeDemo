package com.cetc28.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazySkillRepositoryTest {

    @Test
    void compositeForwardsResourcesToTheFirstMatchingSource() {
        LazyFake local = new LazyFake("local", Map.of("demo", skill("demo")), resources("local"));
        LazyFake remote = new LazyFake("Nacos", Map.of("demo", skill("demo")), resources("remote"));
        CompositeSkillRepository composite = new CompositeSkillRepository(List.of(local, remote));

        assertEquals("local", composite.resourcesFor("demo", null).read("x").orElseThrow());
    }

    @Test
    void filteredRepositoryHonoursAllowList() {
        LazyFake source = new LazyFake("local", Map.of("demo", skill("demo")), resources("value"));
        FilteredSkillRepository allowed = new FilteredSkillRepository(source, List.of("demo"));
        FilteredSkillRepository denied = new FilteredSkillRepository(source, List.of("other"));

        assertEquals("value", allowed.resourcesFor("demo", null).read("x").orElseThrow());
        assertTrue(denied.resourcesFor("demo", null).read("x").isEmpty());
        assertTrue(denied.resourcesFor("demo", null).list().isEmpty());
    }

    @Test
    void compositeReturnsEmptyForNonLazyDelegate() {
        NonLazyFake nonLazy = new NonLazyFake("plain", Map.of("demo", skill("demo")));
        CompositeSkillRepository composite = new CompositeSkillRepository(List.of(nonLazy));

        assertTrue(composite.resourcesFor("demo", null).list().isEmpty());
    }

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name).skillContent("body").source("test").build();
    }

    private static SkillResources resources(String value) {
        return new SkillResources() {
            @Override
            public Optional<String> read(String path) {
                return "x".equals(path) ? Optional.of(value) : Optional.empty();
            }

            @Override
            public Optional<byte[]> readBinary(String path) {
                return read(path).map(text -> text.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public List<String> list() {
                return List.of("x");
            }
        };
    }

    private static class NonLazyFake implements AgentSkillRepository {
        private final String source;
        private final Map<String, AgentSkill> skills;

        NonLazyFake(String source, Map<String, AgentSkill> skills) {
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

    private static final class LazyFake extends NonLazyFake implements LazyResourceCapable {
        private final SkillResources resources;

        LazyFake(String source, Map<String, AgentSkill> skills, SkillResources resources) {
            super(source, skills);
            this.resources = resources;
        }

        @Override
        public SkillResources resourcesFor(String name, RuntimeContext context) {
            return resources;
        }
    }
}
