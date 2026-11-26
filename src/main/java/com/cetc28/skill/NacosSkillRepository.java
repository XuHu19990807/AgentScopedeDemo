package com.cetc28.skill;

import com.cetc28.config.NacosConfig;
import com.cetc28.skill.resource.NacosSkillArchive;
import com.cetc28.skill.resource.NacosSkillResources;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.harness.agent.skill.LazyResourceCapable;
import io.agentscope.harness.agent.skill.SkillResources;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos Skill 仓库 — AgentScope 标准接口实现
 *
 * <p>元数据通过 Client API 搜索获取，SKILL.md 与资源来自 SDK 下载的 ZIP（懒加载并缓存）。
 * 资源文件不随 {@link #getAllSkills()} 加载：只有模型调用
 * {@code load_skill_through_path} 读取 references/assets 时，才通过
 * {@link NacosSkillResources} 读取已缓存的 ZIP。</p>
 */
@Component
public class NacosSkillRepository implements AgentSkillRepository, LazyResourceCapable {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillRepository.class);
    private static final long CONTENT_FAILURE_BACKOFF_MS = 30_000;
    private static final long ARCHIVE_FAILURE_BACKOFF_MS = 60_000;
    /** 连续多少次扫描为空才认为技能确实被全部下线 */
    private static final int EMPTY_CLEAR_THRESHOLD = 3;

    @Autowired
    private NacosSkillClient nacosSkillClient;

    @Autowired
    private NacosConfig nacosConfig;

    /** 技能元数据缓存：name -> SkillEntry */
    private volatile Map<String, NacosSkillClient.SkillEntry> skillsCache = new LinkedHashMap<>();
    /** 连续空结果计数，用于区分“暂时不可用”与“技能全部下线” */
    private int emptyStreak = 0;

    /** SKILL.md 内容缓存：name@version -> content */
    private final Map<String, String> contentCache = new ConcurrentHashMap<>();
    /** SKILL.md 拉取失败负缓存：name@version -> 可重试时间戳 */
    private final Map<String, Long> contentFailureUntil = new ConcurrentHashMap<>();

    /** 解压后的资源缓存：name@version -> archive */
    private final Map<String, NacosSkillArchive> archiveCache = new ConcurrentHashMap<>();
    /** 资源拉取失败负缓存：name@version -> 可重试时间戳 */
    private final Map<String, Long> archiveFailureUntil = new ConcurrentHashMap<>();
    /** 同一技能的 ZIP 下载 single-flight 锁 */
    private final Map<String, Object> archiveLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
        log.info("NacosSkillRepository initialized with {} skills", skillsCache.size());
    }

    @Override
    public List<String> getAllSkillNames() {
        return new ArrayList<>(skillsCache.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return skillsCache.values().stream()
                .map(this::toAgentSkillOrNull)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public AgentSkill getSkill(String name) {
        NacosSkillClient.SkillEntry meta = skillsCache.get(name);
        if (meta == null) return null;
        AgentSkill skill = toAgentSkillOrNull(meta);
        if (skill == null) {
            log.warn("Skill '{}' skipped: content unavailable", name);
            return null;
        }
        log.info("【Skill调用】agent 读取技能: {} ({})", name, meta.description());
        return skill;
    }

    @Override
    public boolean skillExists(String name) {
        return skillsCache.containsKey(name);
    }

    /**
     * 返回轻量资源句柄，不触发网络；真正 read 时才下载/解析 ZIP。
     */
    @Override
    public SkillResources resourcesFor(String name, RuntimeContext context) {
        NacosSkillClient.SkillEntry meta = skillsCache.get(name);
        if (meta == null) return SkillResources.empty();
        return new NacosSkillResources(() -> loadArchive(name, meta.latestVersion()));
    }

    /**
     * 获取（并缓存）指定版本的完整 Skill ZIP 解析结果。同一 key 并发请求只下载一次。
     *
     * @param version null / "latest" 表示使用 latest 标签
     */
    public NacosSkillArchive loadArchive(String name, String version) {
        String key = archiveKey(name, version);
        NacosSkillArchive cached = archiveCache.get(key);
        if (cached != null) return cached;

        Long retryAfter = archiveFailureUntil.get(key);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return null;
        }

        Object lock = archiveLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            cached = archiveCache.get(key);
            if (cached != null) return cached;

            byte[] zip = nacosSkillClient.downloadSkillZip(name, version);
            NacosSkillArchive archive = parseZip(name, zip);
            if (archive == null) {
                archive = loadArchiveFromConsole(name, version);
            }
            if (archive != null) {
                archiveCache.put(key, archive);
                archiveFailureUntil.remove(key);
                return archive;
            }
            archiveFailureUntil.put(key, System.currentTimeMillis() + ARCHIVE_FAILURE_BACKOFF_MS);
            return null;
        }
    }

    private NacosSkillArchive parseZip(String name, byte[] zip) {
        if (zip == null) return null;
        try {
            return NacosSkillArchive.parse(zip);
        } catch (IOException e) {
            log.warn("解析 Nacos skill ZIP 失败 {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 兼容模式（skill.discovery=console）下从控制台版本详情读取资源。
     * 仅在 ZIP 不可用且开启了 console 模式时才会真正发请求。
     */
    private NacosSkillArchive loadArchiveFromConsole(String name, String version) {
        NacosSkillClient.SkillVersion detail = nacosSkillClient.getSkillVersion(name, version);
        if (detail == null
                || ((detail.resources() == null || detail.resources().isEmpty())
                    && (detail.skillMd() == null || detail.skillMd().isBlank()))) {
            return null;
        }
        try {
            NacosSkillArchive archive = NacosSkillArchive.fromConsoleResources(detail.resources(), detail.skillMd());
            log.info("Nacos skill '{}' loaded {} resources from console fallback",
                    name, archive.resources().size());
            return archive;
        } catch (IOException e) {
            log.warn("解析 Nacos console 资源失败 {}: {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        log.warn("NacosSkillRepository is read-only (save not supported)");
        return false;
    }

    @Override
    public boolean delete(String name) {
        log.warn("NacosSkillRepository is read-only (delete not supported)");
        return false;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("Nacos", nacosSkillClient.toString(), false);
    }

    @Override
    public String getSource() {
        return "Nacos";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // Nacos Skill 仓库只读
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void close() {
        contentCache.clear();
        contentFailureUntil.clear();
        archiveCache.clear();
        archiveFailureUntil.clear();
    }

    /**
     * 定时增量刷新：只失效新增/变化/删除的技能条目，未变化的技能继续命中缓存。
     */
    @Scheduled(fixedDelayString = "${nacos.skill.refresh-interval-ms:30000}")
    public void scheduledRefresh() {
        if (!nacosConfig.isSkillEnabled()) return;
        if (nacosConfig.getSkill().getRefreshIntervalMs() <= 0) return;
        reload();
    }

    /**
     * 重新从 Nacos 拉取技能列表。版本或 updateTime 变化的技能会清掉内容与资源缓存。
     */
    public void reload() {
        List<NacosSkillClient.SkillEntry> entries = nacosSkillClient.listSkills();
        if (entries == null || entries.isEmpty()) {
            // 空列表可能是“Nacos 暂时不可用”也可能是“技能全被下线”：
            // 连续多次为空才清理，避免抖动期间丢失技能。
            if (++emptyStreak >= EMPTY_CLEAR_THRESHOLD && !skillsCache.isEmpty()) {
                log.info("Nacos returned no skills for {} consecutive scans, clearing {} cached skills",
                        emptyStreak, skillsCache.size());
                for (String name : new ArrayList<>(skillsCache.keySet())) {
                    invalidateSkillCaches(name);
                }
                skillsCache = new LinkedHashMap<>();
                emptyStreak = 0;
            } else {
                log.debug("Nacos returned empty skills, preserving existing {} skills", skillsCache.size());
            }
            return;
        }
        emptyStreak = 0;
        Map<String, NacosSkillClient.SkillEntry> newMap = new LinkedHashMap<>();
        for (NacosSkillClient.SkillEntry e : entries) {
            newMap.put(e.name(), e);
        }

        // 已删除的技能：清理其缓存
        for (String name : new ArrayList<>(skillsCache.keySet())) {
            if (!newMap.containsKey(name)) {
                invalidateSkillCaches(name);
            }
        }
        // 新增或变化的技能：清理该技能的缓存
        newMap.forEach((name, entry) -> {
            NacosSkillClient.SkillEntry old = skillsCache.get(name);
            if (old == null || changed(old, entry)) {
                invalidateSkillCaches(name);
            }
        });

        skillsCache = newMap;
        log.info("Loaded {} skills from Nacos", newMap.size());
    }

    private static boolean changed(NacosSkillClient.SkillEntry oldEntry,
                                   NacosSkillClient.SkillEntry newEntry) {
        return !Objects.equals(oldEntry.latestVersion(), newEntry.latestVersion())
                || !Objects.equals(oldEntry.updateTime(), newEntry.updateTime());
    }

    /** 清掉某个技能的所有版本缓存（content/archive/负缓存锁） */
    private void invalidateSkillCaches(String name) {
        String prefix = name + "@";
        contentCache.keySet().removeIf(key -> key.startsWith(prefix));
        contentFailureUntil.keySet().removeIf(key -> key.startsWith(prefix));
        archiveCache.keySet().removeIf(key -> key.startsWith(prefix));
        archiveFailureUntil.keySet().removeIf(key -> key.startsWith(prefix));
        archiveLocks.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ========== 内部方法 ==========

    private String archiveKey(String name, String version) {
        return name + "@" + (version == null || version.isBlank() ? "latest" : version);
    }

    private String contentKey(NacosSkillClient.SkillEntry meta) {
        return archiveKey(meta.name(), meta.latestVersion());
    }

    /**
     * 构建 AgentSkill；内容不可用时返回 null（跳过，而不是抛异常连累其他技能）。
     * SKILL.md 的 frontmatter 只用于补充元数据，正文写入 skillContent。
     */
    private AgentSkill toAgentSkillOrNull(NacosSkillClient.SkillEntry meta) {
        String content = getSkillContentCached(meta);
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(content);
            String skillContent = parsed.hasFrontmatter() ? parsed.getContent() : content;
            if (skillContent == null || skillContent.isBlank()) {
                log.warn("Skill '{}' skipped: SKILL.md has no body after frontmatter", meta.name());
                return null;
            }

            String description = meta.description();
            if ((description == null || description.isBlank()) && parsed.hasFrontmatter()) {
                Object value = parsed.getMetadata().get("description");
                if (value != null) description = String.valueOf(value).trim();
            }
            if (description == null) description = "";

            return AgentSkill.builder()
                    .metadata(parsed.hasFrontmatter() ? parsed.getMetadata() : Map.of())
                    .name(meta.name())
                    .description(description)
                    .skillContent(skillContent)
                    .source("Nacos")
                    .build();
        } catch (RuntimeException e) {
            log.warn("Skill '{}' skipped: invalid SKILL.md ({})", meta.name(), e.getMessage());
            return null;
        }
    }

    /**
     * 懒加载 SKILL.md 内容并缓存；内容来自 SDK ZIP（含 SKILL.md 与资源）。
     * 失败做 30 秒负缓存，避免一次抖动导致每轮重试。
     */
    private String getSkillContentCached(NacosSkillClient.SkillEntry meta) {
        String key = contentKey(meta);
        String cached = contentCache.get(key);
        if (cached != null && !cached.isEmpty()) return cached;

        Long retryAfter = contentFailureUntil.get(key);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return null;
        }

        NacosSkillArchive archive = loadArchive(meta.name(), meta.latestVersion());
        String content = archive == null ? null : archive.skillMd();
        if (content != null && !content.isBlank()) {
            contentCache.put(key, content);
            contentFailureUntil.remove(key);
            return content;
        }
        contentFailureUntil.put(key, System.currentTimeMillis() + CONTENT_FAILURE_BACKOFF_MS);
        return null;
    }
}
