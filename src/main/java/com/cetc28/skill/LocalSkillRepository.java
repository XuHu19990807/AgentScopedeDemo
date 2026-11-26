package com.cetc28.skill;

import com.cetc28.skill.resource.SkillDirectoryResources;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 本地技能仓库：扫描 ./skills/{name}/SKILL.md。
 *
 * Nacos 关闭或不可用时，这是技能的唯一来源，保证"关掉 Nacos 仍然有技能可用"。
 */
@Component
public class LocalSkillRepository implements AgentSkillRepository, LazyResourceCapable {

    private static final Logger log = LoggerFactory.getLogger(LocalSkillRepository.class);
    private static final long TTL_MS = 60_000;

    @Value("${skill.local-dir:skills}")
    private String skillsDir = "skills";

    private volatile Map<String, AgentSkill> cache = Map.of();
    private volatile long loadedAt = 0;

    @Override
    public List<String> getAllSkillNames() {
        refreshIfNeeded();
        return new ArrayList<>(cache.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        refreshIfNeeded();
        return new ArrayList<>(cache.values());
    }

    @Override
    public AgentSkill getSkill(String name) {
        refreshIfNeeded();
        return cache.get(name);
    }

    /**
     * 返回轻量资源句柄，不读磁盘；真正 read 时才访问技能目录。
     */
    @Override
    public SkillResources resourcesFor(String name, RuntimeContext context) {
        refreshIfNeeded();
        AgentSkill skill = cache.get(name);
        if (skill == null) return SkillResources.empty();
        return skill.getOriginDir()
                .<SkillResources>map(SkillDirectoryResources::new)
                .orElseGet(SkillResources::empty);
    }

    @Override
    public boolean skillExists(String name) {
        refreshIfNeeded();
        return cache.containsKey(name);
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
        return new AgentSkillRepositoryInfo("Local", skillsDir, false);
    }

    @Override
    public String getSource() {
        return "local";
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
        // 共享实例，不清理缓存（避免影响其他 Agent）
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < TTL_MS) return;
        synchronized (this) {
            if (now - loadedAt < TTL_MS) return;
            cache = scan();
            loadedAt = now;
        }
    }

    private Map<String, AgentSkill> scan() {
        Path root = Path.of(skillsDir);
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        Map<String, AgentSkill> result = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) return;
                try {
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                    if (content.startsWith("\uFEFF")) {
                        content = content.substring(1);
                    }
                    if (content.isBlank()) return;

                    MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(content);
                    String skillContent = parsed.hasFrontmatter() ? parsed.getContent() : content;
                    if (skillContent == null || skillContent.isBlank()) {
                        log.warn("本地技能正文为空，已跳过 {}（frontmatter 后必须有正文）", skillFile);
                        return;
                    }

                    String description = null;
                    if (parsed.hasFrontmatter()) {
                        Object value = parsed.getMetadata().get("description");
                        if (value != null) description = String.valueOf(value).trim();
                    }
                    if (description == null || description.isBlank()) {
                        description = firstLine(skillContent);
                    }

                    String name = dir.getFileName().toString();
                    result.put(name, AgentSkill.builder()
                            .metadata(parsed.hasFrontmatter() ? parsed.getMetadata() : Map.of())
                            .name(name)
                            .description(description)
                            .skillContent(skillContent)
                            .source("local")
                            .originDir(dir.toAbsolutePath().normalize())
                            .build());
                } catch (Exception e) {
                    log.warn("读取本地技能失败 {}: {}", skillFile, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("扫描本地技能目录失败 {}: {}", root, e.getMessage());
        }
        if (!result.isEmpty()) {
            log.info("Loaded {} local skill(s) from {}", result.size(), root.toAbsolutePath());
        }
        return result;
    }

    private String firstLine(String content) {
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String text = trimmed.replaceFirst("^#+\\s*", "");
            return text.length() > 120 ? text.substring(0, 120) : text;
        }
        return "";
    }
}
