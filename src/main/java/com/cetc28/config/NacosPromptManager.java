package com.cetc28.config;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos Prompt 管理器 — 从官方 Prompt Registry 读取 Agent 提示词模板。
 *
 * <p>数据来源（按优先级）：</p>
 * <ol>
 *   <li>Prompt Registry：{@code AiService.prompt().getPrompt(promptKey)}，支持订阅热更新；</li>
 *   <li>旧版 ConfigService（dataId={agentId}.json, group=nacos-ai-prompt），
 *       仅当 {@code nacos.prompt.legacy-config-fallback=true} 时启用；</li>
 *   <li>本地 classpath:prompts/{agentId}.txt。</li>
 * </ol>
 *
 * <p>应用不再向 Nacos 写入 Prompt：发布/审核走 Nacos 控制台，运行时只读。</p>
 */
@Component
public class NacosPromptManager {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptManager.class);
    private static final String DOMAIN = "prompt";
    private static final int PROMPT_NOT_FOUND_CODE = 20004;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired(required = false)
    private AiService aiService;

    /** 仅在 legacy-config-fallback=true 时使用 */
    @Autowired(required = false)
    private ConfigService configService;

    @Autowired
    private NacosConfig nacosConfig;

    @Autowired
    private NacosAvailabilityGate availabilityGate;

    /** Registry 模板缓存：agentId -> template */
    private final Map<String, String> registryCache = new ConcurrentHashMap<>();

    /** Registry 订阅：agentId -> listener */
    private final Map<String, AbstractNacosPromptListener> registryListeners = new ConcurrentHashMap<>();

    /** 旧版 ConfigService 监听：dataId -> listener */
    private final Map<String, Listener> legacyListeners = new ConcurrentHashMap<>();

    /** 订阅失败的 agent（prompt 尚未在 UI 创建），定时重试 */
    private final Map<String, Runnable> pendingWatches = new ConcurrentHashMap<>();

    /** 本地 prompt 缓存：Nacos 不可用时避免反复读 classpath */
    private final Map<String, String> localPromptCache = new ConcurrentHashMap<>();

    private boolean legacyEnabled() {
        return nacosConfig.getPrompt().isLegacyConfigFallback() && configService != null;
    }

    /**
     * 获取 Agent 提示词模板
     * 优先级：Prompt Registry → 旧版 ConfigService（开关）→ 本地 classpath
     */
    public String getPrompt(String agentId) {
        if (!nacosConfig.isPromptEnabled()) {
            return loadLocalPrompt(agentId);
        }

        String fromRegistry = getFromRegistry(agentId);
        if (fromRegistry != null) {
            return fromRegistry;
        }

        if (legacyEnabled()) {
            String fromConfig = getFromLegacyConfig(agentId);
            if (fromConfig != null) {
                return fromConfig;
            }
        }

        return loadLocalPrompt(agentId);
    }

    private String getFromRegistry(String agentId) {
        String cached = registryCache.get(agentId);
        if (cached != null) return cached;
        if (aiService == null || !availabilityGate.isAvailable(DOMAIN)) return null;
        try {
            Prompt prompt = aiService.prompt().getPrompt(agentId);
            if (prompt == null || prompt.getTemplate() == null || prompt.getTemplate().isBlank()) {
                return null;
            }
            availabilityGate.recordSuccess(DOMAIN);
            registryCache.put(agentId, prompt.getTemplate());
            log.debug("Loaded prompt '{}' from Prompt Registry (version={})", agentId, prompt.getVersion());
            return prompt.getTemplate();
        } catch (NacosException e) {
            if (isNotFound(e)) {
                log.debug("Prompt '{}' not found in Prompt Registry", agentId);
                return null;
            }
            availabilityGate.recordFailure(DOMAIN, "getPrompt:" + agentId);
            log.warn("Failed to read prompt '{}' from Prompt Registry: {}", agentId, e.getErrMsg());
            return null;
        } catch (Exception e) {
            availabilityGate.recordFailure(DOMAIN, "getPrompt:" + agentId);
            log.warn("Failed to read prompt '{}' from Prompt Registry: {}", agentId, e.getMessage());
            return null;
        }
    }

    private String getFromLegacyConfig(String agentId) {
        if (!availabilityGate.isAvailable(DOMAIN)) return null;
        String dataId = legacyDataId(agentId);
        try {
            String content = configService.getConfig(dataId, nacosConfig.getPromptGroup(),
                    nacosConfig.getTimeoutMs());
            availabilityGate.recordSuccess(DOMAIN);
            if (content == null || content.isEmpty()) return null;
            String template = extractTemplate(content);
            log.debug("Loaded prompt '{}' from legacy ConfigService (deprecated)", agentId);
            return template;
        } catch (NacosException e) {
            availabilityGate.recordFailure(DOMAIN, "getConfig:" + agentId);
            log.debug("Failed to read legacy prompt '{}': {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * 订阅热更新。Registry 为主；旧版配置监听仅在 legacy 开关开启时注册。
     */
    public void watchPrompt(String agentId, Runnable onChange) {
        if (!nacosConfig.isPromptEnabled()) return;

        subscribeRegistry(agentId, onChange);

        if (legacyEnabled()) {
            subscribeLegacy(agentId, onChange);
        }
    }

    private void subscribeRegistry(String agentId, Runnable onChange) {
        if (aiService == null || registryListeners.containsKey(agentId)) return;
        if (!availabilityGate.isAvailable(DOMAIN)) {
            pendingWatches.put(agentId, onChange);
            return;
        }
        // 先确认 Prompt 已发布：直接 subscribe 一个不存在的 key 会让 SDK 内部
        // 每 10 秒轮询一次并持续打 WARN，这里改为“存在才订阅，不存在定时重试”。
        if (!registryPromptExists(agentId)) {
            pendingWatches.put(agentId, onChange);
            log.info("Prompt '{}' not published yet; will retry subscription later", agentId);
            return;
        }
        // 订阅成功时 SDK 会“同步”回调一次监听器（NacosAiService$PromptServiceDelegate#subscribePrompt
        // 内部直接 invoker.invoke(...)），这条事件不是变更，必须丢弃：否则 onChange 触发的 Agent
        // 重新初始化会再次 watchPrompt，而占位登记尚未完成，会无限递归并抛 StackOverflowError。
        // 用一个“订阅中”标志只屏蔽 subscribePrompt 调用期间同步投递的事件，
        // 之后真正的变更（异步投递）不会被误吞，也不会漏掉启动后的第一次变更。
        final AtomicBoolean subscribing = new AtomicBoolean(true);
        AbstractNacosPromptListener listener = new AbstractNacosPromptListener() {
            @Override
            public void onEvent(NacosPromptEvent event) {
                if (subscribing.get()) {
                    log.debug("Prompt '{}' subscribed (initial callback ignored)", agentId);
                    return;
                }
                log.info("Prompt '{}' changed in Nacos Prompt Registry, hot-reloading...", agentId);
                registryCache.remove(agentId);
                safeRun(agentId, onChange);
            }
        };
        // 先登记再订阅：subscribePrompt 期间的同步回调会触发 onChange 重入 subscribeRegistry，
        // 提前占位可让方法开头的 containsKey 判断生效，避免递归。
        registryListeners.put(agentId, listener);
        try {
            Prompt initial = aiService.prompt().subscribePrompt(agentId, null, null, listener);
            pendingWatches.remove(agentId);
            if (initial != null && initial.getTemplate() != null) {
                registryCache.put(agentId, initial.getTemplate());
            }
            log.info("Prompt Registry subscription registered for '{}'", agentId);
        } catch (NacosException e) {
            registryListeners.remove(agentId);
            if (isNotFound(e)) {
                // prompt 可能稍后在控制台创建，加入待重试队列
                pendingWatches.put(agentId, onChange);
                log.info("Prompt '{}' not published yet; will retry subscription later", agentId);
                return;
            }
            availabilityGate.recordFailure(DOMAIN, "subscribePrompt:" + agentId);
            pendingWatches.put(agentId, onChange);
            log.warn("Failed to subscribe prompt '{}': {}", agentId, e.getErrMsg());
        } catch (Exception e) {
            registryListeners.remove(agentId);
            availabilityGate.recordFailure(DOMAIN, "subscribePrompt:" + agentId);
            pendingWatches.put(agentId, onChange);
            log.warn("Failed to subscribe prompt '{}': {}", agentId, e.getMessage());
        } finally {
            subscribing.set(false);
        }
    }

    /**
     * 判断 Prompt 是否已在 Registry 发布（同时顺手填充缓存）。
     */
    private boolean registryPromptExists(String agentId) {
        try {
            Prompt prompt = aiService.prompt().getPrompt(agentId);
            if (prompt != null && prompt.getTemplate() != null && !prompt.getTemplate().isBlank()) {
                registryCache.put(agentId, prompt.getTemplate());
                availabilityGate.recordSuccess(DOMAIN);
                return true;
            }
            return false;
        } catch (NacosException e) {
            if (!isNotFound(e)) {
                availabilityGate.recordFailure(DOMAIN, "getPrompt:" + agentId);
                log.warn("Failed to check prompt '{}' in Prompt Registry: {}", agentId, e.getErrMsg());
            }
            return false;
        }
    }

    private void subscribeLegacy(String agentId, Runnable onChange) {
        String dataId = legacyDataId(agentId);
        if (legacyListeners.containsKey(dataId)) return;
        try {
            Listener listener = new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("Prompt '{}' changed in legacy Nacos config, hot-reloading...", agentId);
                    safeRun(agentId, onChange);
                }
            };
            configService.addListener(dataId, nacosConfig.getPromptGroup(), listener);
            legacyListeners.put(dataId, listener);
            log.info("Legacy prompt listener registered for '{}' ({})", agentId, dataId);
        } catch (NacosException e) {
            log.warn("Failed to register legacy Nacos listener for '{}': {}", agentId, e.getMessage());
        }
    }

    private void safeRun(String agentId, Runnable onChange) {
        try {
            onChange.run();
        } catch (Throwable e) {
            // 回调运行在 Nacos 事件线程上，吞掉 Throwable 避免把 SDK 的通知线程一起打挂
            log.warn("Prompt reload for '{}' failed: {}", agentId, e.toString());
        }
    }

    /**
     * 定时重试“prompt 尚未发布”的订阅，使之后在控制台创建的 Prompt 也能热更新。
     */
    @Scheduled(fixedDelayString = "${nacos.prompt.watch-retry-ms:60000}")
    public void retryPendingWatches() {
        if (pendingWatches.isEmpty()) return;
        pendingWatches.forEach((agentId, onChange) -> {
            if (!nacosConfig.isPromptEnabled() || aiService == null) return;
            if (!availabilityGate.isAvailable(DOMAIN)) return;
            subscribeRegistry(agentId, onChange);
        });
    }

    /**
     * 从 JSON 配置中提取 template 字段（兼容纯文本），仅旧版配置中心使用
     */
    private String extractTemplate(String content) {
        try {
            var root = JSON.readTree(content);
            var template = root.get("template");
            if (template != null && template.isTextual()) {
                return template.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON，作为纯文本
        }
        return content;
    }

    private static boolean isNotFound(NacosException e) {
        int code = e.getErrCode();
        if (code == NacosException.NOT_FOUND || code == NacosException.RESOURCE_NOT_FOUND
                || code == PROMPT_NOT_FOUND_CODE) {
            return true;
        }
        String msg = e.getErrMsg();
        return msg != null && msg.toLowerCase().contains("not found");
    }

    private String legacyDataId(String agentId) {
        return agentId + ".json";
    }

    /**
     * 从本地 classpath:prompts/ 加载
     */
    public String loadLocalPrompt(String agentId) {
        String cached = localPromptCache.get(agentId);
        if (cached != null) return cached;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("prompts/" + agentId + ".txt")) {
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                localPromptCache.put(agentId, content);
                log.debug("Loaded prompt '{}' from local file", agentId);
                return content;
            }
        } catch (Exception e) {
            log.debug("Failed to read local prompt '{}': {}", agentId, e.getMessage());
        }
        log.warn("No prompt found for '{}' (Nacos or local)", agentId);
        return null;
    }

    @PreDestroy
    public void shutdown() {
        if (aiService != null) {
            registryListeners.forEach((agentId, listener) -> {
                try {
                    aiService.prompt().unsubscribePrompt(agentId, null, null, listener);
                } catch (Exception e) {
                    log.debug("Unsubscribe prompt '{}' skipped: {}", agentId, e.getMessage());
                }
            });
        }
        registryListeners.clear();
        pendingWatches.clear();

        if (configService != null) {
            legacyListeners.forEach((dataId, listener) -> {
                try {
                    configService.removeListener(dataId, nacosConfig.getPromptGroup(), listener);
                } catch (Exception e) {
                    log.debug("Remove legacy prompt listener '{}' skipped: {}", dataId, e.getMessage());
                }
            });
        }
        legacyListeners.clear();
        registryCache.clear();
    }
}
