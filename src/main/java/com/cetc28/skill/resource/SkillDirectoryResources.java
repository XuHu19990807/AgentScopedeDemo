package com.cetc28.skill.resource;

import io.agentscope.harness.agent.skill.SkillResources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地技能目录的懒加载资源读取器。
 *
 * <p>构造和 {@link #list()} 都不预读文件内容；只有模型真正请求某个资源时才读磁盘。
 * 技能目录在扫描时已经确定为 {@code <skills>/<name>}，这里再次做目录逃逸校验。</p>
 */
public final class SkillDirectoryResources implements SkillResources {

    private static final long LIST_TTL_MS = 5_000;

    private final Path root;
    private final Path realRoot;

    private volatile List<String> cachedList;
    private volatile long cachedAt;

    public SkillDirectoryResources(Path root) {
        this.root = root.toAbsolutePath().normalize();
        Path real;
        try {
            real = this.root.toRealPath();
        } catch (IOException e) {
            real = this.root;
        }
        this.realRoot = real;
    }

    @Override
    public Optional<String> read(String path) {
        return readBytes(path).map(SkillResourcePaths::decodeText);
    }

    @Override
    public Optional<byte[]> readBinary(String path) {
        return readBytes(path);
    }

    @Override
    public List<String> list() {
        long now = System.currentTimeMillis();
        List<String> local = cachedList;
        if (local != null && now - cachedAt < LIST_TTL_MS) {
            return local;
        }
        synchronized (this) {
            if (cachedList != null && now - cachedAt < LIST_TTL_MS) {
                return cachedList;
            }
            List<String> scanned = scan();
            cachedList = scanned;
            cachedAt = now;
            return scanned;
        }
    }

    private List<String> scan() {
        if (!Files.isDirectory(root)) return List.of();
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !"SKILL.md".equals(path))
                    .filter(path -> !SkillResourcePaths.isHidden(path))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private Optional<byte[]> readBytes(String rawPath) {
        String relative = SkillResourcePaths.normalize(rawPath);
        if (relative == null || "SKILL.md".equals(relative)) return Optional.empty();
        Path file = resolveInsideRoot(relative);
        if (file == null || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > SkillResourcePaths.MAX_FILE_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path resolveInsideRoot(String relative) {
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) return null;
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) return null;
            return real;
        } catch (IOException e) {
            return null;
        }
    }
}
