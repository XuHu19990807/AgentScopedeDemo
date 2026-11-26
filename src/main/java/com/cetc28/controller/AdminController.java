package com.cetc28.controller;

import com.cetc28.agentchat.base.AgentBase;
import com.cetc28.agentchat.base.AgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理控制台的只读聚合接口（概览页用）。
 *
 * <p>只暴露展示所需的最小字段，避免把 Agent 内部配置（模型密钥、工具实例）带到前端。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AgentRegistry agentRegistry;

    public AdminController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentBase agent : agentRegistry.getEnabledAgents()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", agent.getAgentId());
            item.put("name", agent.getAgentName());
            item.put("description", agent.getDescription());
            item.put("enabled", agent.isEnabled());
            item.put("skillCount", agent.getSkillNames().size());
            AgentBase.ToolGroup group = safeToolGroup(agent);
            item.put("toolGroup", group == null ? null : group.name());
            result.add(item);
        }
        return result;
    }

    private static AgentBase.ToolGroup safeToolGroup(AgentBase agent) {
        try {
            return agent.getToolGroup();
        } catch (Exception e) {
            return null;
        }
    }
}
