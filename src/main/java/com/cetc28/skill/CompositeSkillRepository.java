package com.cetc28.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 组合技能仓库：按顺序合并多个来源（本地优先，其次 Nacos），同名技能以先出现者为准。
 */
public class CompositeSkillRepository implements AgentSkillRepository, LazyResourceCapable {

    private final List<AgentSkillRepository> delegates;

    public CompositeSkillRepository(List<AgentSkillRepository> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    @Override
    public List<String> getAllSkillNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (AgentSkillRepository delegate : delegates) {
            try {
                names.addAll(delegate.getAllSkillNames());
            } catch (Exception ignored) {
                // 单个来源失败不影响其他来源
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        Map<String, AgentSkill> merged = new LinkedHashMap<>();
        for (AgentSkillRepository delegate : delegates) {
            try {
                for (AgentSkill skill : delegate.getAllSkills()) {
                    merged.putIfAbsent(skill.getName(), skill);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public AgentSkill getSkill(String name) {
        for (AgentSkillRepository delegate : delegates) {
            try {
                AgentSkill skill = delegate.getSkill(name);
                if (skill != null) return skill;
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    @Override
    public boolean skillExists(String name) {
        for (AgentSkillRepository delegate : delegates) {
            try {
                if (delegate.skillExists(name)) return true;
            } catch (Exception ignored) {
                // try next
            }
        }
        return false;
    }

    /**
     * 资源跟随“同名技能先出现者”的合并规则：本地优先，其次 Nacos。
     * 只有真正实现 LazyResourceCapable 的来源才能提供懒加载资源。
     */
    @Override
    public SkillResources resourcesFor(String name, RuntimeContext context) {
        for (AgentSkillRepository delegate : delegates) {
            try {
                if (!delegate.skillExists(name)) continue;
                if (delegate instanceof LazyResourceCapable lazy) {
                    SkillResources resources = lazy.resourcesFor(name, context);
                    if (resources != null) return resources;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return SkillResources.empty();
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
        if (delegates.isEmpty()) return new AgentSkillRepositoryInfo("Composite", "empty", false);
        return delegates.get(0).getRepositoryInfo();
    }

    @Override
    public String getSource() {
        return "composite";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void close() {
        // 共享实例，不关闭底层仓库
    }
}
