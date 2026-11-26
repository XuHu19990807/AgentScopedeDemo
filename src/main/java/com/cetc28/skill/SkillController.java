package com.cetc28.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 技能管理：列表、内容预览、手动刷新。本地 + Nacos 双源。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final LocalSkillRepository localRepository;
    private final NacosSkillRepository nacosRepository;

    public SkillController(LocalSkillRepository localRepository, NacosSkillRepository nacosRepository) {
        this.localRepository = localRepository;
        this.nacosRepository = nacosRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(localRepository.getAllSkillNames());
        names.addAll(nacosRepository.getAllSkillNames());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names) {
            AgentSkill skill = resolve(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("description", skill != null && skill.getDescription() != null ? skill.getDescription() : "");
            item.put("available", skill != null);
            item.put("source", localRepository.getSkill(name) != null ? "local" : "nacos");
            result.add(item);
        }
        return result;
    }

    @GetMapping("/{name}")
    public Map<String, Object> get(@PathVariable String name) {
        AgentSkill skill = resolve(name);
        if (skill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在或内容不可用: " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", skill.getName());
        result.put("description", skill.getDescription());
        result.put("content", skill.getSkillContent());
        result.put("resources", listResources(skill));
        return result;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        nacosRepository.reload();
        int count = localRepository.getAllSkillNames().size() + nacosRepository.getAllSkillNames().size();
        return Map.of("success", true, "count", count);
    }

    private AgentSkill resolve(String name) {
        AgentSkill local = localRepository.getSkill(name);
        if (local != null) return local;
        return nacosRepository.getSkill(name);
    }

    /**
     * 预览接口用于排查资源是否可见；Nacos 技能会在这里触发一次 ZIP 下载，
     * 但对话主链路不会经过该接口。
     */
    private List<String> listResources(AgentSkill skill) {
        try {
            SkillResources resources = "local".equals(skill.getSource())
                    ? localRepository.resourcesFor(skill.getName(), null)
                    : nacosRepository.resourcesFor(skill.getName(), null);
            return resources.list();
        } catch (Exception e) {
            return List.of();
        }
    }
}
