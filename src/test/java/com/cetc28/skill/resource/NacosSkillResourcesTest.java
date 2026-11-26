package com.cetc28.skill.resource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Skill 资源文件：ZIP 解析出 references/assets 等资源，懒加载读取器能按路径取内容。
 */
class NacosSkillResourcesTest {

    private static byte[] zipWithResources() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "probe/SKILL.md", "# Probe\n\nSee references/guide.md\n");
            put(zip, "probe/references/guide.md", "# Guide\n\nGUIDE-TOKEN-12345\n");
            put(zip, "probe/assets/sample.txt", "ASSET-42\n");
            put(zip, "probe/scripts/hello.sh", "echo hello-resource\n");
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void archiveExposesResourcesUnderTypeDirectories() throws IOException {
        NacosSkillArchive archive = NacosSkillArchive.parse(zipWithResources());

        assertEquals(Set.of("references/guide.md", "assets/sample.txt", "scripts/hello.sh"),
                Set.copyOf(archive.resourcePaths()));
        assertTrue(new String(archive.resources().get("references/guide.md"), StandardCharsets.UTF_8)
                .contains("GUIDE-TOKEN-12345"));
    }

    @Test
    void resourcesReadTextAndBinaryByPath() throws IOException {
        NacosSkillArchive archive = NacosSkillArchive.parse(zipWithResources());
        NacosSkillResources resources = new NacosSkillResources(() -> archive);

        assertEquals(Optional.of("# Guide\n\nGUIDE-TOKEN-12345\n"), resources.read("references/guide.md"));
        assertEquals("ASSET-42\n",
                new String(resources.readBinary("assets/sample.txt").orElseThrow(), StandardCharsets.UTF_8));
        assertTrue(resources.read("scripts/hello.sh").orElseThrow().startsWith("echo hello-resource"));
        assertTrue(resources.read("references/missing.md").isEmpty());
        assertTrue(resources.read("../../etc/passwd").isEmpty());
        assertEquals(3, resources.list().size());
    }

    @Test
    void loaderIsCalledOncePerHandle() throws IOException {
        AtomicInteger loads = new AtomicInteger();
        NacosSkillArchive archive = NacosSkillArchive.parse(zipWithResources());
        NacosSkillResources resources = new NacosSkillResources(() -> {
            loads.incrementAndGet();
            return archive;
        });

        resources.list();
        resources.read("references/guide.md");
        resources.readBinary("assets/sample.txt");

        assertEquals(1, loads.get());
    }

    @Test
    void failedLoaderDegradesToEmpty() {
        NacosSkillResources resources = new NacosSkillResources(() -> {
            throw new IllegalStateException("nacos down");
        });

        assertTrue(resources.list().isEmpty());
        assertTrue(resources.read("references/guide.md").isEmpty());
    }
}
