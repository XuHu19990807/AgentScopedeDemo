package com.cetc28.skill.resource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosSkillArchiveTest {

    @Test
    void parsesSingleRootArchiveAndKeepsResources() throws Exception {
        NacosSkillArchive archive = NacosSkillArchive.parse(zip(Map.of(
                "demo/SKILL.md", """
                        ---
                        name: demo
                        description: demo skill
                        ---

                        # demo

                        See references/guide.md.
                        """.getBytes(StandardCharsets.UTF_8),
                "demo/references/guide.md", "guide".getBytes(StandardCharsets.UTF_8))));

        assertTrue(archive.skillMd().contains("# demo"));
        assertEquals(List.of("references/guide.md"), archive.resourcePaths());
        assertEquals("guide", new String(archive.resources().get("references/guide.md"), StandardCharsets.UTF_8));
    }

    @Test
    void acceptsRootLevelSkillMd() throws Exception {
        NacosSkillArchive archive = NacosSkillArchive.parse(zip(Map.of(
                "SKILL.md", "# root\n\nbody".getBytes(StandardCharsets.UTF_8),
                "references/a.md", "a".getBytes(StandardCharsets.UTF_8))));

        assertEquals(List.of("references/a.md"), archive.resourcePaths());
    }

    @Test
    void rejectsZipSlipAndMultipleRoots() {
        assertThrows(IOException.class, () -> NacosSkillArchive.parse(zip(Map.of(
                "../evil.md", "evil".getBytes(StandardCharsets.UTF_8)))));

        assertThrows(IOException.class, () -> NacosSkillArchive.parse(zip(Map.of(
                "a/SKILL.md", "# a".getBytes(StandardCharsets.UTF_8),
                "b/references/x.md", "x".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void skipsHiddenAndMacMetadata() throws Exception {
        NacosSkillArchive archive = NacosSkillArchive.parse(zip(Map.of(
                "demo/SKILL.md", "# demo".getBytes(StandardCharsets.UTF_8),
                "demo/.env", "secret".getBytes(StandardCharsets.UTF_8),
                "demo/references/.hidden.md", "hidden".getBytes(StandardCharsets.UTF_8),
                "demo/references/ok.md", "ok".getBytes(StandardCharsets.UTF_8),
                "demo/__MACOSX/._x", "junk".getBytes(StandardCharsets.UTF_8),
                "demo/.DS_Store", "junk".getBytes(StandardCharsets.UTF_8))));

        assertEquals(List.of("references/ok.md"), archive.resourcePaths());
    }

    @Test
    void binaryResourceReadsAsBase64() {
        byte[] binary = new byte[]{0, (byte) 0xFF, 1};
        NacosSkillArchive archive = new NacosSkillArchiveAccessor().parse(Map.of(
                "demo/SKILL.md", "# demo".getBytes(StandardCharsets.UTF_8),
                "demo/assets/logo.bin", binary));

        NacosSkillResources resources = new NacosSkillResources(() -> archive);
        assertTrue(resources.read("assets/logo.bin").orElseThrow().startsWith("base64:"));
        assertTrue(resources.read("missing.bin").isEmpty());
        assertFalse(resources.list().contains("SKILL.md"));
    }

    @Test
    void mapsConsoleResourceIdentifiersAndSkipsNonResources() throws Exception {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("references::schemas.md", "{\"type\":\"object\"}");
        raw.put("scripts::quick_validate.py", "print('ok')");
        raw.put("LICENSE.txt", "license");
        raw.put("SKILL.md", "# should be skipped");
        raw.put(".env", "secret");
        raw.put("assets::logo.bin", "not-really-binary");

        NacosSkillArchive archive = NacosSkillArchive.fromConsoleResources(raw);

        Set<String> paths = new HashSet<>(archive.resourcePaths());
        assertEquals(Set.of("references/schemas.md", "scripts/quick_validate.py",
                "LICENSE.txt", "assets/logo.bin"), paths);
        assertEquals("{\"type\":\"object\"}",
                new String(archive.resources().get("references/schemas.md"), StandardCharsets.UTF_8));
        assertEquals("# should be skipped", archive.skillMd());
    }

    @Test
    void consoleSkillMdParameterWinsOverMapEntry() throws Exception {
        NacosSkillArchive archive = NacosSkillArchive.fromConsoleResources(
                Map.of("SKILL.md", "# from map", "references::a.md", "a"),
                "# from parameter");

        assertEquals("# from parameter", archive.skillMd());
        assertEquals(Set.of("references/a.md"), Set.copyOf(archive.resourcePaths()));
    }

    @Test
    void consoleSkillWithOnlySkillMdIsAccepted() throws Exception {
        NacosSkillArchive archive = NacosSkillArchive.fromConsoleResources(Map.of(), "# only md");

        assertEquals("# only md", archive.skillMd());
        assertTrue(archive.resourcePaths().isEmpty());
    }

    @Test
    void rejectsEmptyConsoleResources() {
        assertThrows(IOException.class, () -> NacosSkillArchive.fromConsoleResources(Map.of()));
    }

    /** 小工具：避免每个测试都写 try/catch。 */
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 测试里构造 archive 需要受检异常包装。 */
    private static final class NacosSkillArchiveAccessor {
        NacosSkillArchive parse(Map<String, byte[]> entries) {
            try {
                return NacosSkillArchive.parse(zip(entries));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
