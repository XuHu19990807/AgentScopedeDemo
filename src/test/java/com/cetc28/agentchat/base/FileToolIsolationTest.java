package com.cetc28.agentchat.base;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件分析工具隔离回归测试。
 *
 * 契约：ToolGroup.FILE 只能由 FileAgent 使用。
 * 文件分析类工具（FileAnalysisTool：listUploadedFiles / detectFileType / searchInFile / getFileContent）
 * 只注册在 FILE 工具组（见 AgentSystemConfig#buildToolGroups），且全工程只有 FileAgent
 * 返回 ToolGroup.FILE。若有人给其他 Agent 换用 FILE 工具组、或新增 Agent 占用 FILE，
 * 本测试立即失败 —— 文件能力一旦外泄，orchestrator 会把非文件问题也派给文件Agent。
 */
class FileToolIsolationTest {

    @Test
    void onlyFileAgentUsesFileToolGroup() throws Exception {
        List<String> fileGroupAgents = instantiateAllAgentSubclasses().stream()
                .filter(a -> a.getToolGroup() == AgentBase.ToolGroup.FILE)
                .map(AgentBase::getAgentId)
                .sorted()
                .toList();

        assertEquals(List.of("file"), fileGroupAgents,
                "ToolGroup.FILE 只能由文件分析Agent(file)使用。"
                        + "其他 Agent 拿到文件工具会引偏 orchestrator 的意图判断，"
                        + "导致非文件问题也被派给文件Agent。");
    }

    @Test
    void fileAgentMustUseFileToolGroup() throws Exception {
        AgentBase file = instantiateAllAgentSubclasses().stream()
                .filter(a -> a.getAgentId().equals("file"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 FileAgent（agentId=file）"));

        assertEquals(AgentBase.ToolGroup.FILE, file.getToolGroup(),
                "FileAgent 必须持有 ToolGroup.FILE，否则文件工具无处注册。");
    }

    /** 扫描 com.cetc28.agentchat.base 下全部 AgentBase 具体子类，以 (Model, double) 构造器实例化 */
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
        assertTrue(agents.size() >= 7,
                "应扫描到 orchestrator + 6 个 worker 共 7 个 Agent，实际 " + agents.size());
        return agents;
    }
}

