package com.cetc28.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerRegistryTest {

    @TempDir
    Path dir;

    private McpServerRegistry registry(String name) {
        return new McpServerRegistry(dir.resolve(name).toString());
    }

    @Test
    void missingFileLoadsAsEmptyList() {
        assertTrue(registry("nope.json").load().isEmpty());
    }

    @Test
    void uiDefinitionRoundTrips() {
        McpServerRegistry reg = registry("servers.json");
        reg.upsert(McpServerDefinition.sse("remote", "http://localhost:8081/mcp/sse", McpServerDefinition.SOURCE_UI));

        List<McpServerDefinition> loaded = registry("servers.json").load();
        assertEquals(1, loaded.size());
        assertEquals("remote", loaded.get(0).getName());
        assertEquals(McpServerDefinition.TRANSPORT_SSE, loaded.get(0).getTransport());
        assertEquals("http://localhost:8081/mcp/sse", loaded.get(0).getUrl());
    }

    @Test
    void upsertReplacesSameName() {
        McpServerRegistry reg = registry("servers.json");
        reg.upsert(McpServerDefinition.sse("svc", "http://a:1/sse", McpServerDefinition.SOURCE_UI));
        reg.upsert(McpServerDefinition.sse("svc", "http://b:2/sse", McpServerDefinition.SOURCE_UI));

        List<McpServerDefinition> loaded = reg.load();
        assertEquals(1, loaded.size());
        assertEquals("http://b:2/sse", loaded.get(0).getUrl());
    }

    @Test
    void nonUiSourcesAreNotPersisted() {
        McpServerRegistry reg = registry("servers.json");
        reg.upsert(McpServerDefinition.sse("from-yml", "http://y:1/sse", McpServerDefinition.SOURCE_YML));
        reg.upsert(McpServerDefinition.stdio("from-nacos", "npx", List.of("-y", "x"), McpServerDefinition.SOURCE_NACOS));
        assertFalse(Files.exists(dir.resolve("servers.json")));
        assertTrue(reg.load().isEmpty());
    }

    @Test
    void removeDeletesOnlyUiEntry() {
        McpServerRegistry reg = registry("servers.json");
        reg.upsert(McpServerDefinition.sse("keep", "http://k:1/sse", McpServerDefinition.SOURCE_UI));
        reg.remove("other");
        assertEquals(1, reg.load().size());
        reg.remove("keep");
        assertTrue(reg.load().isEmpty());
    }

    @Test
    void corruptFileIsBackedUpAndIgnored() throws Exception {
        Path file = dir.resolve("broken.json");
        Files.write(file, "{ not json ]".getBytes(StandardCharsets.UTF_8));

        McpServerRegistry reg = new McpServerRegistry(file.toString());
        assertTrue(reg.load().isEmpty());
        assertTrue(Files.exists(dir.resolve("broken.json.bak")), "坏文件应改名为 .bak");
        assertFalse(Files.exists(file), "原路径应清空，后续 upsert 可正常写入");

        reg.upsert(McpServerDefinition.sse("after", "http://a:1/sse", McpServerDefinition.SOURCE_UI));
        assertEquals(1, reg.load().size());
    }

    @Test
    void invalidEntriesInFileAreFiltered() throws Exception {
        Path file = dir.resolve("mixed.json");
        String json = """
                [
                  {"name":"good","transport":"sse","url":"http://g:1/sse","enabled":true,"source":"ui"},
                  {"name":"bad-no-transport","url":"http://b:1"},
                  {"name":"bad-stdio-no-command","transport":"stdio"}
                ]""";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        List<McpServerDefinition> loaded = new McpServerRegistry(file.toString()).load();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).getName());
    }
}
