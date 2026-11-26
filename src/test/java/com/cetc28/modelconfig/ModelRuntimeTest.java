package com.cetc28.modelconfig;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRuntimeTest {

    private ModelConfigStore store;
    private ModelRuntime runtime;

    private static ChatProfile chat(String id, String modelName, boolean enabled) {
        return new ChatProfile(id, "名称-" + id, "https://api.example.com/v1", "sk-" + id,
                modelName, 0.7d, 4096, enabled, false);
    }

    private static EmbeddingProfile embedding(String id, String modelName, int dimension) {
        return new EmbeddingProfile(id, "嵌入-" + id, "https://api.example.com/v1", "sk-embed",
                modelName, 512, dimension, true, false);
    }

    @BeforeEach
    void setUp() {
        store = mock(ModelConfigStore.class);
        ModelConfig config = new ModelConfig(3,
                List.of(chat("default", "deepseek-chat", true), chat("alt", "deepseek-reasoner", true),
                        chat("off", "gpt-4o", false)),
                List.of(embedding("default", "qwen-embed", 1024)),
                "default", "default",
                Map.of("knowledge", "alt"),
                "v7", System.currentTimeMillis());
        when(store.current()).thenReturn(config);
        runtime = new ModelRuntime(store, mock(ModelTrafficService.class));
        runtime.init();
    }

    @Test
    void resolvesAgentMappingAndDefault() {
        Model mapped = runtime.modelFor("knowledge");
        assertEquals("deepseek-reasoner", mapped.getModelName());

        Model other = runtime.modelFor("chat");
        assertEquals("deepseek-chat", other.getModelName());
        assertInstanceOf(TrafficModel.class, other);
    }

    @Test
    void pinnedModelFallsBackWhenProfileUnusable() {
        Model pinned = runtime.pinnedModel("off", "chat");
        assertEquals("deepseek-chat", pinned.getModelName());

        Model missing = runtime.pinnedModel("missing", "chat");
        assertEquals("deepseek-chat", missing.getModelName());

        assertEquals("deepseek-chat", runtime.pinnedModel(null, "chat").getModelName());
    }

    @Test
    void exposesEmbeddingAndCollectionVersion() {
        Optional<EmbeddingProfile> embedding = runtime.defaultEmbedding();
        assertEquals("qwen-embed", embedding.orElseThrow().modelName());
        assertEquals(1024, embedding.orElseThrow().dimension());
        assertEquals("v7", runtime.collectionVersion());
        assertEquals(3, runtime.revision());
    }

    @Test
    void refreshInvalidatesCachedDelegates() {
        Model first = runtime.modelFor("chat");
        assertEquals("deepseek-chat", first.getModelName());

        ChatProfile changed = chat("default", "deepseek-v9", true);
        ModelConfig next = new ModelConfig(4, List.of(changed),
                List.of(embedding("default", "qwen-embed", 1024)),
                "default", "default", Map.of(), "v7", System.currentTimeMillis());
        when(store.current()).thenReturn(next);
        runtime.refreshFromStore();

        assertEquals("deepseek-v9", runtime.modelFor("chat").getModelName());
        assertEquals(4, runtime.revision());
    }

    @Test
    void throwsWhenNoUsableProfileConfigured() {
        ModelConfigStore emptyStore = mock(ModelConfigStore.class);
        when(emptyStore.current()).thenReturn(ModelConfig.empty());
        ModelRuntime emptyRuntime = new ModelRuntime(emptyStore, mock(ModelTrafficService.class));
        emptyRuntime.init();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> emptyRuntime.modelFor("chat").stream(List.of(), List.of(), null));
        assertEquals("没有可用的语言模型配置，请先在管理控制台配置", error.getMessage());
    }
}
