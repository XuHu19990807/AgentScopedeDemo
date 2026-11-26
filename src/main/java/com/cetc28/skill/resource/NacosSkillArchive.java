package com.cetc28.skill.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Nacos Skill ZIP 包的内存解析结果。
 *
 * <p>Nacos 的 Skill 是目录包：ZIP 内必须有一个 {@code SKILL.md}，其余条目是
 * {@code references/}、{@code assets/} 等资源。这里做 Zip Slip、Zip Bomb、
 * 隐藏文件和多根目录校验，解析后不落盘。</p>
 */
public final class NacosSkillArchive {

    private static final int MAX_ZIP_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1000;

    private final String skillMd;
    private final Map<String, byte[]> resources;

    private NacosSkillArchive(String skillMd, Map<String, byte[]> resources) {
        this.skillMd = skillMd;
        this.resources = Map.copyOf(resources);
    }

    public String skillMd() {
        return skillMd;
    }

    public Map<String, byte[]> resources() {
        return resources;
    }

    /**
     * 解析 ZIP。失败时抛 {@link IOException}，由调用方决定降级策略。
     */
    public static NacosSkillArchive parse(byte[] zipBytes) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IOException("Nacos skill zip is empty");
        }
        if (zipBytes.length > MAX_ZIP_BYTES) {
            throw new IOException("Nacos skill zip is too large: " + zipBytes.length + " bytes");
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("Nacos skill zip has too many entries");
                }
                String rawName = entry.getName().replace('\\', '/');
                if (rawName.endsWith("/")) {
                    continue; // 目录条目
                }
                if (isIgnored(rawName)) {
                    continue;
                }
                String name = SkillResourcePaths.normalize(rawName);
                if (name == null) {
                    throw new IOException("Illegal zip entry path: " + entry.getName());
                }
                long remaining = MAX_TOTAL_BYTES - total;
                if (remaining <= 0) {
                    throw new IOException("Nacos skill zip expands beyond " + MAX_TOTAL_BYTES + " bytes");
                }
                byte[] data = readEntry(zip, SkillResourcePaths.MAX_FILE_BYTES, remaining);
                total += data.length;
                if (entries.put(name, data) != null) {
                    throw new IOException("Duplicate zip entry: " + name);
                }
            }
        }

        if (entries.isEmpty()) {
            throw new IOException("Nacos skill zip contains no files");
        }

        String root = resolveRoot(entries.keySet());
        String skillMdPath = root.isEmpty() ? "SKILL.md" : root + "/SKILL.md";
        byte[] skillMdBytes = entries.remove(skillMdPath);
        if (skillMdBytes == null) {
            throw new IOException("Nacos skill zip must contain SKILL.md at its root");
        }
        String skillMd = stripBom(SkillResourcePaths.decodeText(skillMdBytes));
        if (skillMd.isBlank()) {
            throw new IOException("Nacos skill SKILL.md is blank");
        }

        Map<String, byte[]> resources = new LinkedHashMap<>();
        String prefix = root.isEmpty() ? "" : root + "/";
        for (Map.Entry<String, byte[]> item : entries.entrySet()) {
            String name = item.getKey();
            String relative = prefix.isEmpty() ? name : name.substring(prefix.length());
            if (relative.isBlank() || "SKILL.md".equals(relative) || SkillResourcePaths.isHidden(relative)) {
                continue;
            }
            resources.put(relative, item.getValue());
        }
        return new NacosSkillArchive(skillMd, resources);
    }

    /**
     * 从 Nacos 控制台 {@code data.resource} 构造资源集合。
     *
     * <p>key 形如 {@code references::schemas.md} 或根级 {@code LICENSE.txt}，
     * 这里统一转成相对路径。控制台只有文本内容，二进制资源仍应优先走 ZIP。</p>
     */
    public static NacosSkillArchive fromConsoleResources(Map<String, String> rawResources) throws IOException {
        return fromConsoleResources(rawResources, null);
    }

    /**
     * 同上，但可显式传入 SKILL.md 正文（控制台 {@code data.skillMd} 与 {@code data.resource} 分开返回）。
     */
    public static NacosSkillArchive fromConsoleResources(Map<String, String> rawResources, String skillMd)
            throws IOException {
        if (rawResources == null || rawResources.isEmpty()) {
            if (skillMd == null || skillMd.isBlank()) {
                throw new IOException("Nacos console skill has no resources");
            }
            rawResources = Map.of();
        }

        String archiveSkillMd = skillMd == null ? "" : skillMd;
        Map<String, byte[]> resources = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        for (Map.Entry<String, String> entry : rawResources.entrySet()) {
            if (++count > MAX_ENTRIES) {
                throw new IOException("Nacos console skill has too many resources");
            }
            String rawPath = entry.getKey();
            String content = entry.getValue();
            if (rawPath == null || rawPath.isBlank() || content == null) continue;

            int separator = rawPath.indexOf("::");
            String path = separator >= 0
                    ? rawPath.substring(0, separator) + "/" + rawPath.substring(separator + 2)
                    : rawPath;
            String relative = SkillResourcePaths.normalize(path);
            if (relative == null) {
                throw new IOException("Illegal console resource path: " + rawPath);
            }
            if ("SKILL.md".equals(relative)) {
                if (archiveSkillMd.isBlank()) archiveSkillMd = content;
                continue;
            }
            if (SkillResourcePaths.isHidden(relative)) {
                continue;
            }

            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            if (data.length > SkillResourcePaths.MAX_FILE_BYTES) {
                throw new IOException("Console resource exceeds " + SkillResourcePaths.MAX_FILE_BYTES + " bytes: " + relative);
            }
            total += data.length;
            if (total > MAX_TOTAL_BYTES) {
                throw new IOException("Nacos console resources expand beyond " + MAX_TOTAL_BYTES + " bytes");
            }
            if (resources.put(relative, data) != null) {
                throw new IOException("Duplicate console resource: " + relative);
            }
        }

        if (resources.isEmpty() && archiveSkillMd.isBlank()) {
            throw new IOException("Nacos console skill has no usable resources");
        }
        return new NacosSkillArchive(archiveSkillMd, resources);
    }

    /**
     * 规范要求所有条目位于同一个顶层目录。也兼容根目录直接放 SKILL.md 的包。
     */
    private static String resolveRoot(Set<String> names) throws IOException {
        if (names.contains("SKILL.md")) {
            return "";
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String name : names) {
            int slash = name.indexOf('/');
            if (slash < 0) {
                roots.add("");
            } else {
                roots.add(name.substring(0, slash));
            }
        }
        if (roots.size() != 1) {
            throw new IOException("Zip entries must be under a single root directory");
        }
        String root = roots.iterator().next();
        if (root.isEmpty() || !names.contains(root + "/SKILL.md")) {
            throw new IOException("Nacos skill zip must contain SKILL.md at its root");
        }
        return root;
    }

    private static byte[] readEntry(ZipInputStream zip, int maxFileBytes, long maxTotalBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > maxFileBytes) {
                throw new IOException("Zip entry exceeds " + maxFileBytes + " bytes");
            }
            if (total > maxTotalBytes) {
                throw new IOException("Nacos skill zip expands beyond " + MAX_TOTAL_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isIgnored(String rawName) {
        String name = rawName.replace('\\', '/');
        if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return true;
        String base = name.substring(name.lastIndexOf('/') + 1);
        return ".DS_Store".equals(base) || base.startsWith("._");
    }

    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    /** 调试/测试用：列出资源路径。 */
    public List<String> resourcePaths() {
        return new ArrayList<>(resources.keySet());
    }
}
