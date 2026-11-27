package com.cetc28.controller;

import com.cetc28.modelconfig.ChatProfile;
import com.cetc28.modelconfig.EmbeddingProfile;
import com.cetc28.modelconfig.ModelConfig;
import com.cetc28.modelconfig.ModelConfigStore;
import com.cetc28.modelconfig.ModelRuntime;
import com.cetc28.modelconfig.ModelTrafficService;
import com.cetc28.modelconfig.RagReindexService;
import com.cetc28.rag.milvus.MilvusRagStore;
import com.cetc28.service.EmbeddingService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型配置管理（需要管理员令牌，由 {@code AdminGuard} 拦截 {@code /api/admin/**}）。
 *
 * <p>语言模型与 Embedding 配置分别维护；Embedding 维度变化必须重建索引，
 * 因此「应用 Embedding」会校验维度并可选触发异步重建。
 */
@RestController
@RequestMapping("/api/admin/models")
public class ModelAdminController {

    private static final Logger log = LoggerFactory.getLogger(ModelAdminController.class);

    private final ModelConfigStore store;
    private final ModelRuntime runtime;
    private final ModelTrafficService traffic;
    private final EmbeddingService embeddingService;
    private final MilvusRagStore ragStore;
    private final RagReindexService reindexService;

    public ModelAdminController(ModelConfigStore store,
                                ModelRuntime runtime,
                                ModelTrafficService traffic,
                                EmbeddingService embeddingService,
                                MilvusRagStore ragStore,
                                RagReindexService reindexService) {
        this.store = store;
        this.runtime = runtime;
        this.traffic = traffic;
        this.embeddingService = embeddingService;
        this.ragStore = ragStore;
        this.reindexService = reindexService;
    }

    @GetMapping
    public Map<String, Object> config() {
        ModelConfig config = store.current();
        Map<String, Object> view = new LinkedHashMap<>(config.toView());
        view.put("collectionVersion", runtime.collectionVersion());
        view.put("configFile", store.file().toString());
        view.put("defaultChatModelName", runtime.defaultChat().map(ChatProfile::modelName).orElse(null));
        view.put("defaultEmbeddingModelName",
                runtime.defaultEmbedding().map(EmbeddingProfile::modelName).orElse(null));
        view.put("embeddingDimension", embeddingService.getDimension());
        view.put("reindex", reindexService.progress());
        return view;
    }

    // ------------------------------------------------------------------ 语言模型

    @PostMapping("/chat")
    public Map<String, Object> saveChat(@RequestBody ChatProfile incoming) {
        ChatProfile prepared = mergeKey(incoming, true);
        String id = prepared.id();
        if (id == null || id.isBlank()) {
            id = generateId(prepared.modelName(), store.current().chatProfiles().stream()
                    .map(ChatProfile::id).toList());
            prepared = new ChatProfile(id, prepared.name(), prepared.baseUrl(), prepared.apiKey(),
                    prepared.modelName(), prepared.temperature(), prepared.maxTokens(),
                    prepared.enabled(), prepared.builtin());
        }
        prepared.validate();
        ChatProfile saved = prepared;
        store.update(config -> {
            List<ChatProfile> list = new ArrayList<>(config.chatProfiles());
            int index = indexOfChat(list, saved.id());
            if (index >= 0) {
                list.set(index, saved.withBuiltin(list.get(index).builtin()));
            } else {
                list.add(saved);
            }
            String defaultId = pickDefaultChat(list, config.defaultChatId());
            return config.withChatProfiles(list, defaultId);
        });
        runtime.refreshFromStore();
        return ok("语言模型已保存", store.current());
    }

    @DeleteMapping("/chat/{id}")
    public Map<String, Object> deleteChat(@PathVariable String id) {
        ModelConfig config = store.current();
        if (config.findChat(id).isEmpty()) throw new IllegalArgumentException("模型不存在：" + id);
        long usableCount = config.chatProfiles().stream().filter(ChatProfile::usable).count();
        boolean deletingUsable = config.findChat(id).map(ChatProfile::usable).orElse(false);
        if (deletingUsable && usableCount <= 1) {
            throw new IllegalStateException("至少保留一个可用的语言模型");
        }
        store.update(current -> {
            List<ChatProfile> list = new ArrayList<>(current.chatProfiles());
            list.removeIf(p -> p.id().equals(id));
            Map<String, String> mapping = new LinkedHashMap<>(current.agentProfiles());
            mapping.values().removeIf(id::equals);
            String defaultId = id.equals(current.defaultChatId())
                    ? list.stream().filter(ChatProfile::usable).map(ChatProfile::id).findFirst().orElse(null)
                    : current.defaultChatId();
            return current.withChatProfiles(list, defaultId).withAgentProfiles(mapping);
        });
        runtime.refreshFromStore();
        return ok("语言模型已删除", store.current());
    }

    @PostMapping("/chat/{id}/test")
    public Map<String, Object> testChat(@PathVariable String id) {
        ChatProfile profile = store.current().findChat(id)
                .orElseThrow(() -> new IllegalArgumentException("模型不存在：" + id));
        profile.validate();
        long startedAt = System.nanoTime();
        try {
            Model model = OpenAIChatModel.builder()
                    .apiKey(profile.apiKey())
                    .baseUrl(profile.baseUrl())
                    .modelName(profile.modelName())
                    .generateOptions(GenerateOptions.builder()
                            .temperature(profile.temperature())
                            .maxTokens(Math.min(profile.maxTokens(), 256))
                            .build())
                    .build();
            Msg message = Msg.builder().role(MsgRole.USER).textContent("连通性测试，请只回复 pong").build();
            List<ChatResponse> responses = model.stream(List.of(message), List.of(), null)
                    .collectList()
                    .block(Duration.ofSeconds(30));
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            ChatResponse last = responses == null || responses.isEmpty()
                    ? null : responses.get(responses.size() - 1);
            String sample = responses == null ? "" : responses.stream()
                    .map(this::textOf)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(java.util.stream.Collectors.joining());
            traffic.record(com.cetc28.modelconfig.TrafficCall.ok(
                    "chat-test", profile.id(), profile.modelName(), null,
                    last != null && last.getUsage() != null ? last.getUsage().getInputTokens() : 0,
                    last != null && last.getUsage() != null ? last.getUsage().getOutputTokens() : 0,
                    0, latencyMs));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ok", true);
            view.put("latencyMs", latencyMs);
            view.put("modelName", profile.modelName());
            view.put("sample", sample.length() > 120 ? sample.substring(0, 120) : sample);
            return view;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            traffic.record(com.cetc28.modelconfig.TrafficCall.failed(
                    "chat-test", profile.id(), profile.modelName(), null, latencyMs, e.getMessage()));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ok", false);
            view.put("latencyMs", latencyMs);
            view.put("modelName", profile.modelName());
            view.put("error", e.getMessage());
            return view;
        }
    }

    @PostMapping("/default")
    public Map<String, Object> setDefault(@RequestBody DefaultRequest request) {
        if (request == null || request.kind() == null || request.id() == null) {
            throw new IllegalArgumentException("缺少 kind 或 id");
        }
        String kind = request.kind().toLowerCase(Locale.ROOT);
        store.update(config -> switch (kind) {
            case "chat" -> {
                ChatProfile profile = config.findChat(request.id())
                        .orElseThrow(() -> new IllegalArgumentException("模型不存在：" + request.id()));
                if (!profile.usable()) throw new IllegalArgumentException("该模型当前不可用，无法设为默认");
                yield new ModelConfig(config.version(), config.chatProfiles(), config.embeddingProfiles(),
                        profile.id(), config.defaultEmbeddingId(), config.agentProfiles(),
                        config.collectionVersion(), config.updatedAt());
            }
            case "embedding" -> {
                EmbeddingProfile profile = config.findEmbedding(request.id())
                        .orElseThrow(() -> new IllegalArgumentException("Embedding 配置不存在：" + request.id()));
                if (!profile.usable()) throw new IllegalArgumentException("该 Embedding 配置当前不可用");
                yield new ModelConfig(config.version(), config.chatProfiles(), config.embeddingProfiles(),
                        config.defaultChatId(), profile.id(), config.agentProfiles(),
                        config.collectionVersion(), config.updatedAt());
            }
            default -> throw new IllegalArgumentException("kind 只能是 chat 或 embedding");
        });
        runtime.refreshFromStore();
        return ok("默认模型已更新", store.current());
    }

    @PostMapping("/agent-map")
    public Map<String, Object> setAgentMap(@RequestBody Map<String, String> mapping) {
        if (mapping == null) throw new IllegalArgumentException("缺少 agent 映射");
        ModelConfig config = store.current();
        Map<String, String> cleaned = new LinkedHashMap<>();
        mapping.forEach((agentId, profileId) -> {
            if (agentId == null || agentId.isBlank()) return;
            if (profileId == null || profileId.isBlank()) return;
            config.findChat(profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Agent " + agentId + " 绑定的模型不存在：" + profileId));
            cleaned.put(agentId, profileId);
        });
        store.update(current -> current.withAgentProfiles(cleaned));
        runtime.refreshFromStore();
        return ok("Agent 模型映射已保存", store.current());
    }

    // ------------------------------------------------------------------ Embedding

    @PostMapping("/embedding")
    public Map<String, Object> saveEmbedding(@RequestBody EmbeddingProfile incoming) {
        EmbeddingProfile prepared = mergeKey(incoming, false);
        String id = prepared.id();
        if (id == null || id.isBlank()) {
            id = generateId(prepared.modelName(), store.current().embeddingProfiles().stream()
                    .map(EmbeddingProfile::id).toList());
            prepared = new EmbeddingProfile(id, prepared.name(), prepared.baseUrl(), prepared.apiKey(),
                    prepared.modelName(), prepared.maxTokens(), prepared.dimension(),
                    prepared.enabled(), prepared.builtin());
        }
        prepared.validate();
        EmbeddingProfile saved = prepared;
        store.update(config -> {
            List<EmbeddingProfile> list = new ArrayList<>(config.embeddingProfiles());
            int index = indexOfEmbedding(list, saved.id());
            if (index >= 0) {
                list.set(index, saved.withBuiltin(list.get(index).builtin()));
            } else {
                list.add(saved);
            }
            String currentDefault = config.defaultEmbeddingId();
            boolean stillPresent = currentDefault != null
                    && list.stream().anyMatch(p -> p.id().equals(currentDefault));
            String defaultId = stillPresent ? currentDefault
                    : list.stream().filter(EmbeddingProfile::usable)
                    .map(EmbeddingProfile::id).findFirst().orElse(null);
            return config.withEmbeddingProfiles(list, defaultId);
        });
        runtime.refreshFromStore();
        return ok("Embedding 配置已保存", store.current());
    }

    /**
     * 删除 Embedding 配置。
     *
     * <p>当前正在应用的配置不允许直接删除（避免向量维度与已有索引不一致），
     * 需先在「应用」里切换其它配置；同时至少保留一个可用的配置。
     */
    @DeleteMapping("/embedding/{id}")
    public Map<String, Object> deleteEmbedding(@PathVariable String id) {
        ModelConfig config = store.current();
        EmbeddingProfile target = config.findEmbedding(id)
                .orElseThrow(() -> new IllegalArgumentException("Embedding 配置不存在：" + id));
        if (id.equals(config.defaultEmbeddingId())) {
            throw new IllegalStateException("当前正在应用的 Embedding 配置不能删除，请先应用其它配置");
        }
        long usableCount = config.embeddingProfiles().stream().filter(EmbeddingProfile::usable).count();
        if (target.usable() && usableCount <= 1) {
            throw new IllegalStateException("至少保留一个可用的 Embedding 配置");
        }
        store.update(current -> {
            List<EmbeddingProfile> list = new ArrayList<>(current.embeddingProfiles());
            list.removeIf(profile -> profile.id().equals(id));
            return current.withEmbeddingProfiles(list, current.defaultEmbeddingId());
        });
        runtime.refreshFromStore();
        return ok("Embedding 配置已删除", store.current());
    }

    @PostMapping("/embedding/{id}/test")
    public Map<String, Object> testEmbedding(@PathVariable String id) {
        EmbeddingProfile profile = store.current().findEmbedding(id)
                .orElseThrow(() -> new IllegalArgumentException("Embedding 配置不存在：" + id));
        profile.validate();
        long startedAt = System.nanoTime();
        int dimension = embeddingService.probeDimension(profile);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ok", dimension > 0);
        view.put("latencyMs", latencyMs);
        view.put("modelName", profile.modelName());
        view.put("dimension", dimension);
        if (dimension <= 0) view.put("error", embeddingService.getLastError());
        return view;
    }

    /**
     * 应用 Embedding 配置：先探测维度，维度变化且未要求重建时返回 409；
     * 需要重建时递增集合版本并异步重建索引。
     */
    @PostMapping("/embedding/apply")
    public Map<String, Object> applyEmbedding(@RequestBody ApplyEmbeddingRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("缺少 Embedding 配置 id");
        }
        EmbeddingProfile profile = store.current().findEmbedding(request.id())
                .orElseThrow(() -> new IllegalArgumentException("Embedding 配置不存在：" + request.id()));
        profile.validate();
        int probed = embeddingService.probeDimension(profile);
        if (probed <= 0) {
            throw new IllegalStateException("Embedding 连通性测试失败，无法应用：" +
                    (embeddingService.getLastError() == null ? "未知错误" : embeddingService.getLastError()));
        }
        int previous = runtime.defaultEmbedding().map(EmbeddingProfile::dimension).orElse(0);
        if (previous <= 0) previous = embeddingService.getDimension();
        boolean dimensionChanged = previous > 0 && probed != previous;
        boolean rebuild = Boolean.TRUE.equals(request.rebuild());
        if (dimensionChanged && !rebuild) {
            throw new IllegalStateException("新配置维度 " + probed + " 与原维度 " + previous
                    + " 不一致，切换后必须重建索引（请勾选“重建索引”）");
        }
        boolean needRebuild = dimensionChanged || rebuild;
        store.update(config -> {
            List<EmbeddingProfile> profiles = new ArrayList<>(config.embeddingProfiles());
            for (int i = 0; i < profiles.size(); i++) {
                EmbeddingProfile candidate = profiles.get(i);
                if (candidate.id().equals(profile.id()) && candidate.dimension() != probed) {
                    profiles.set(i, candidate.withDimension(probed));
                }
            }
            ModelConfig next = new ModelConfig(config.version(), config.chatProfiles(),
                    profiles, config.defaultChatId(), profile.id(),
                    config.agentProfiles(), config.collectionVersion(), config.updatedAt());
            if (needRebuild) next = next.withCollectionVersion(bumpVersion(config.collectionVersion()));
            return next;
        });
        runtime.refreshFromStore();
        ragStore.resetHolder();
        boolean started = needRebuild && reindexService.start("Embedding 配置切换，开始重建索引");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("applied", true);
        view.put("dimension", probed);
        view.put("previousDimension", previous);
        view.put("dimensionChanged", dimensionChanged);
        view.put("collectionVersion", runtime.collectionVersion());
        view.put("reindex", started);
        view.put("progress", reindexService.progress());
        return view;
    }

    @GetMapping("/embedding/reindex")
    public Map<String, Object> reindexProgress() {
        return reindexService.progress();
    }

    // ------------------------------------------------------------------ 流量

    @GetMapping("/traffic")
    public Map<String, Object> traffic(@RequestParam(defaultValue = "24h") String window) {
        return traffic.aggregate(window);
    }

    @PostMapping("/traffic/reset")
    public Map<String, Object> resetTraffic() {
        traffic.reset();
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ 内部工具

    private Map<String, Object> ok(String message, ModelConfig config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("message", message);
        view.put("version", config.version());
        view.put("config", config.toView());
        return view;
    }

    private ChatProfile mergeKey(ChatProfile incoming, boolean chat) {
        if (incoming == null) throw new IllegalArgumentException("缺少配置内容");
        ChatProfile normalized = incoming.normalized();
        if (normalized.id() == null || normalized.id().isBlank()) return normalized;
        return store.current().findChat(normalized.id())
                .map(existing -> normalized.withApiKey(existing.apiKey()))
                .orElse(normalized);
    }

    private EmbeddingProfile mergeKey(EmbeddingProfile incoming, boolean chat) {
        if (incoming == null) throw new IllegalArgumentException("缺少配置内容");
        EmbeddingProfile normalized = incoming.normalized();
        if (normalized.id() == null || normalized.id().isBlank()) return normalized;
        return store.current().findEmbedding(normalized.id())
                .map(existing -> normalized.withApiKey(existing.apiKey()))
                .orElse(normalized);
    }

    private int indexOfChat(List<ChatProfile> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    private int indexOfEmbedding(List<EmbeddingProfile> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    private String pickDefaultChat(List<ChatProfile> list, String currentDefault) {
        if (currentDefault != null && list.stream().anyMatch(p -> p.id().equals(currentDefault) && p.usable())) {
            return currentDefault;
        }
        return list.stream().filter(ChatProfile::usable).map(ChatProfile::id).findFirst().orElse(null);
    }

    private String generateId(String modelName, List<String> existing) {
        String base = modelName == null ? "model" : modelName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isBlank()) base = "model";
        if (base.length() > 40) base = base.substring(0, 40);
        if (!existing.contains(base)) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!existing.contains(candidate)) return candidate;
        }
        return base + "-" + System.currentTimeMillis();
    }

    private String bumpVersion(String version) {
        if (version == null || version.isBlank()) return "v2";
        String digits = version.replaceAll("^\\D+", "");
        if (digits.isEmpty()) return version + "2";
        try {
            return "v" + (Integer.parseInt(digits) + 1);
        } catch (NumberFormatException e) {
            return "v" + System.currentTimeMillis();
        }
    }

    private String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        StringBuilder text = new StringBuilder();
        for (Object block : response.getContent()) {
            if (block instanceof TextBlock textBlock) text.append(textBlock.getText());
        }
        return text.toString();
    }

    public record DefaultRequest(String kind, String id) {
    }

    public record ApplyEmbeddingRequest(String id, Boolean rebuild) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        String message = e.getMessage() == null ? "操作失败" : e.getMessage();
        HttpStatus status = message.contains("不可用") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        log.warn("模型配置操作被拒绝：{}", message);
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
