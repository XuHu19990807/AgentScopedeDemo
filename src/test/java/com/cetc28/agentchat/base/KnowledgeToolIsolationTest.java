package com.cetc28.agentchat.base;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索工具隔离回归测试。
 *
 * <p>契约：ToolGroup.KNOWLEDGE（含 searchKnowledge / readChunk / knowledgeOverview）
 * 只能由 KnowledgeAgent 使用。理由与 FILE 组相同 —— 检索工具一旦外泄给其他 Agent，
 * 它们会在不该检索的场景"顺手查一下"，把无关片段塞进上下文，反而干扰回答。
 * 主链路的自动注入不走工具（见 ChatService#retrieveKnowledge），所以这里只约束工具归属。
 */
class KnowledgeToolIsolationTest {

    @Test
    void onlyKnowledgeAgentUsesKnowledgeToolGroup() throws Exception {
        List<String> groupAgents = instantiateAllAgentSubclasses().stream()
                .filter(a -> a.getToolGroup() == AgentBase.ToolGroup.KNOWLEDGE)
                .map(AgentBase::getAgentId)
                .sorted()
                .toList();

        assertEquals(List.of("knowledge"), groupAgents,
                "ToolGroup.KNOWLEDGE 只能由知识Agent(knowledge)使用");
    }

    @Test
    void knowledgeAgentMustUseKnowledgeToolGroup() throws Exception {
        AgentBase knowledge = instantiateAllAgentSubclasses().stream()
                .filter(a -> a.getAgentId().equals("knowledge"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 KnowledgeAgent（agentId=knowledge）"));

        assertEquals(AgentBase.ToolGroup.KNOWLEDGE, knowledge.getToolGroup(),
                "KnowledgeAgent 必须持有 KNOWLEDGE 工具组，否则提示词里的"
                        + "「优先从知识库检索」就是空话（这正是改造前的问题）");
    }

    @Test
    void fileAndKnowledgeGroupsDoNotOverlap() throws Exception {
        List<AgentBase> agents = instantiateAllAgentSubclasses();
        assertTrue(agents.stream().noneMatch(a -> a.getToolGroup() == AgentBase.ToolGroup.FILE
                        && a.getAgentId().equals("knowledge")),
                "knowledge Agent 不应持有文件工具组");
        assertTrue(agents.stream().noneMatch(a -> a.getToolGroup() == AgentBase.ToolGroup.KNOWLEDGE
                        && a.getAgentId().equals("file")),
                "file Agent 不应持有知识库检索工具组");
    }

    private static List<AgentBase> instantiateAllAgentSubclasses() throws Exception {
        URL classesLocation = AgentBase.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(classesLocation, "无法定位编译后的 classes 目录");
        File baseDir = new File(new File(classesLocation.toURI()), "com/cetc28/agentchat/base");
        assertTrue(baseDir.isDirectory(), "未找到 agent.base 编译目录: " + baseDir.getAbsolutePath());

        List<AgentBase> agents = new ArrayList<>();
        File[] classFiles = baseDir.listFiles((dir, name) -> name.endsWith(".class"));
        assertNotNull(classFiles, "agent.base 目录读取失败");
        for (File f : classFiles) {
            String className = "com.cetc28.agentchat.base." + f.getName().replace(".class", "");
            Class<?> clazz = Class.forName(className);
            if (clazz == AgentBase.class || !AgentBase.class.isAssignableFrom(clazz)) continue;
            if (Modifier.isAbstract(clazz.getModifiers())) continue;
            Constructor<?> ctor = clazz.getConstructor(Model.class, double.class);
            agents.add((AgentBase) ctor.newInstance(null, 0.5));
        }
        return agents;
    }
}

