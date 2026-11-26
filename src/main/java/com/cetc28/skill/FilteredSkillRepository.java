package com.cetc28.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Skill 仓库代理 — 只暴露允许的指定技能，实现每个 Agent 的技能隔离。
 *
 * 参考 AITEST 的 FilteredSkillRegistry。
 * 每个 Agent 可持有自己的 FilteredSkillRepository 实例，
 * 通过 skill-names 配置（"*" = 全部）过滤 Nacos 技能。
 */
public class FilteredSkillRepository implements AgentSkillRepository, LazyResourceCapable {

    private final AgentSkillRepository delegate;
    private final Set<String> allowedNames;
    private final boolean allowAll;

    public FilteredSkillRepository(AgentSkillRepository delegate, List<String> allowedNames) {
        this.delegate = delegate;
        if (allowedNames == null || allowedNames.isEmpty()) {
            this.allowedNames = Set.of();
            this.allowAll = false;
        } else if (allowedNames.contains("*")) {
            this.allowedNames = Set.of();
            this.allowAll = true;
        } else {
            this.allowedNames = Set.copyOf(allowedNames);
            this.allowAll = false;
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (!allowAll && !allowedNames.contains(name)) return null;
        return delegate.getSkill(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        if (allowAll) return delegate.getAllSkillNames();
        return delegate.getAllSkillNames().stream()
                .filter(allowedNames::contains)
                .toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        if (allowAll) return delegate.getAllSkills();
        return delegate.getAllSkills().stream()
                .filter(s -> allowedNames.contains(s.getName()))
                .toList();
    }

    @Override
    public boolean skillExists(String name) {
        if (!allowAll && !allowedNames.contains(name)) return false;
        return delegate.skillExists(name);
    }

    @Override
    public SkillResources resourcesFor(String name, RuntimeContext context) {
        if (!allowAll && !allowedNames.contains(name)) return SkillResources.empty();
        if (delegate instanceof LazyResourceCapable lazy) {
            return lazy.resourcesFor(name, context);
        }
        return SkillResources.empty();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return delegate.save(skills, overwrite);
    }

    @Override
    public boolean delete(String name) {
        return delegate.delete(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return delegate.getRepositoryInfo();
    }

    @Override
    public String getSource() {
        return delegate.getSource() + "(filtered)";
    }

    @Override
    public void setWriteable(boolean writeable) {
        delegate.setWriteable(writeable);
    }

    @Override
    public boolean isWriteable() {
        return delegate.isWriteable();
    }

    @Override
    public void close() {
        // 过滤视图共享底层仓库，不能在 Agent 生命周期结束时关闭它
    }
}
