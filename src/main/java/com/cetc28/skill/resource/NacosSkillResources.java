package com.cetc28.skill.resource;

import io.agentscope.harness.agent.skill.SkillResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Nacos Skill 的懒加载资源读取器。
 *
 * <p>创建句柄时不做任何网络请求；模型第一次读取资源时才触发 ZIP 下载/解析，
 * 之后同一句柄内复用解析结果。跨轮缓存由 {@code NacosSkillRepository} 负责。</p>
 */
public final class NacosSkillResources implements SkillResources {

    private final Supplier<NacosSkillArchive> archiveLoader;

    private volatile NacosSkillArchive archive;
    private volatile boolean loaded;

    public NacosSkillResources(Supplier<NacosSkillArchive> archiveLoader) {
        this.archiveLoader = archiveLoader;
    }

    @Override
    public Optional<String> read(String path) {
        return readBinary(path).map(SkillResourcePaths::decodeText);
    }

    @Override
    public Optional<byte[]> readBinary(String path) {
        NacosSkillArchive current = archive();
        if (current == null) return Optional.empty();
        String relative = SkillResourcePaths.normalize(path);
        if (relative == null) return Optional.empty();
        return Optional.ofNullable(current.resources().get(relative));
    }

    @Override
    public List<String> list() {
        NacosSkillArchive current = archive();
        if (current == null) return List.of();
        return List.copyOf(new ArrayList<>(current.resources().keySet()));
    }

    private NacosSkillArchive archive() {
        if (loaded) return archive;
        synchronized (this) {
            if (!loaded) {
                try {
                    archive = archiveLoader.get();
                } catch (RuntimeException e) {
                    archive = null;
                } finally {
                    loaded = true;
                }
            }
            return archive;
        }
    }
}
