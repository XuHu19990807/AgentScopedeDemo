package com.cetc28.modelconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigStoreTest {

    @TempDir
    Path tempDir;

    private ModelConfigStore store(String fileName) {
        Path file = tempDir.resolve(fileName);
        ModelConfigStore store = new ModelConfigStore(new ObjectMapper(), file.toString(), null);
        ReflectionTestUtils.setField(store, "seedChatBaseUrl", "https://api.example.com/v1");
        ReflectionTestUtils.setField(store, "seedChatApiKey", "sk-seed");
        ReflectionTestUtils.setField(store, "seedChatModel", "deepseek-chat");
        ReflectionTestUtils.setField(store, "seedChatTemperature", 0.7d);
        ReflectionTestUtils.setField(store, "seedChatMaxTokens", 4096);
        ReflectionTestUtils.setField(store, "seedEmbeddingBaseUrl", "https://api.example.com/v1");
        ReflectionTestUtils.setField(store, "seedEmbeddingApiKey", "sk-embed");
        ReflectionTestUtils.setField(store, "seedEmbeddingModel", "qwen-embed");
        ReflectionTestUtils.setField(store, "seedEmbeddingMaxTokens", 512);
        ReflectionTestUtils.setField(store, "seedEmbeddingDimension", 1024);
        ReflectionTestUtils.setField(store, "seedCollectionVersion", "v1");
        store.load();
        return store;
    }

    @Test
    void seedsFromPropertiesOnFirstStart() {
        ModelConfigStore store = store("seeded.json");
        ModelConfig config = store.current();

        assertEquals(1, config.chatProfiles().size());
        assertEquals(1, config.embeddingProfiles().size());
        assertEquals("default", config.defaultChatId());
        assertEquals("default", config.defaultEmbeddingId());
        assertEquals("deepseek-chat", config.chatProfiles().get(0).modelName());
        assertEquals(1024, config.embeddingProfiles().get(0).dimension());
        assertEquals("v1", config.collectionVersion());
        assertTrue(Files.exists(store.file()));
    }

    @Test
    void updatePersistsAndReloadsFromFile() {
        ModelConfigStore store = store("updated.json");
        ChatProfile extra = new ChatProfile("alt", "备用模型", "https://api.example.com/v1", "sk-alt",
                "deepseek-reasoner", 0.5d, 8192, true, false);
        ModelConfig next = store.update(current -> current.withChatProfiles(
                List.of(current.chatProfiles().get(0), extra), current.defaultChatId()));

        assertEquals(1, next.version());
        assertEquals(2, next.chatProfiles().size());

        ModelConfigStore reloaded = store("updated.json");
        ModelConfig config = reloaded.current();
        assertEquals(2, config.chatProfiles().size());
        assertEquals("alt", config.chatProfiles().get(1).id());
        assertEquals("备用模型", config.chatProfiles().get(1).name());
    }

    @Test
    void resetToSeedRestoresSingleDefaultProfile() {
        ModelConfigStore store = store("reset.json");
        store.update(current -> current.withChatProfiles(List.of(), current.defaultChatId()));
        assertTrue(store.current().chatProfiles().isEmpty());

        ModelConfig restored = store.resetToSeed();
        assertFalse(restored.chatProfiles().isEmpty());
        assertEquals("default", restored.defaultChatId());
        assertEquals(2, restored.version());
    }
}
