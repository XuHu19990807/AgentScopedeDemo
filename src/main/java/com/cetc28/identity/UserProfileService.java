package com.cetc28.identity;

import com.cetc28.config.ContextProperties;
import com.cetc28.memory.UserMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户画像：把"长期使用习惯与行为偏好"沉淀到 DM，并在每轮对话注入上下文。
 *
 * <p>画像来源分两类：
 * <ul>
 *   <li>自动：累计轮次、常用模式/模型、高频主题词、最近活动（{@link #recordTurn}）；</li>
 *   <li>显式：用户说"记住…"写入的长期记忆（Redis 为实时来源，同时镜像到 DM，见 {@link #syncExplicitPrefs}）。</li>
 * </ul>
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private static final String KEY_TURNS = "counter:turns";
    private static final String KEY_LAST_MODE = "pref:last-mode";
    private static final String KEY_LAST_MODEL = "pref:last-model";
    private static final String TOPIC_PREFIX = "topic:";
    private static final String EXPLICIT_PREFIX = "pref:";
    private static final int MAX_TOPICS = 40;
    private static final int MAX_KEYWORDS_PER_TURN = 3;
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "什么", "怎么", "如何", "可以", "一个",
            "我们", "你们", "他们", "请", "帮我", "一下", "现在", "然后", "已经", "需要", "以及",
            "关于", "进行", "这个", "那个", "还是", "就是", "不是", "没有", "问题", "内容", "文档");

    private final ProfileRepository profiles;
    private final AuthService authService;
    private final UserMemoryService memoryService;
    private final ContextProperties contextProperties;

    public UserProfileService(ProfileRepository profiles,
                              AuthService authService,
                              UserMemoryService memoryService,
                              ContextProperties contextProperties) {
        this.profiles = profiles;
        this.authService = authService;
        this.memoryService = memoryService;
        this.contextProperties = contextProperties;
    }

    /* ---------------- 上下文注入 ---------------- */

    /**
     * 生成注入对话的动态上下文：用户画像 + 显式长期记忆。
     * 匿名用户只注入显式记忆（没有可持久化的画像）。
     */
    public String buildContext(String userId, String query) {
        StringBuilder sb = new StringBuilder();
        String profileBlock = profileBlock(userId);
        if (!profileBlock.isBlank()) {
            sb.append("[用户画像]\n").append(profileBlock).append('\n');
        }
        String memory = memoryService.recallContext(userId, query, 5);
        if (memory != null && !memory.isBlank()) {
            sb.append("[用户长期记忆]\n").append(memory).append('\n');
        }
        String text = sb.toString().trim();
        int max = Math.max(200, contextProperties.getProfileMaxChars());
        if (text.length() > max) text = text.substring(0, max) + "\n…（画像已截断）";
        return text;
    }

    private String profileBlock(String userId) {
        if (!isPersistedUser(userId)) return "";
        List<ProfileEntry> entries;
        try {
            entries = profiles.listByUser(userId);
        } catch (DataAccessException e) {
            log.debug("读取用户画像失败 user={}: {}", userId, e.getMessage());
            return "";
        }
        if (entries.isEmpty()) return "";
        IdentityPrincipal principal = authService.principalOrAnonymous(userId);
        StringBuilder sb = new StringBuilder();
        if (principal.authenticated()) {
            sb.append("- 称呼：").append(principal.displayName() == null ? principal.userId() : principal.displayName());
            if (!principal.roles().isEmpty()) sb.append("（角色：").append(String.join("、", principal.roles())).append("）");
            sb.append('\n');
        }
        int turns = 0;
        String lastMode = null;
        String lastModel = null;
        List<ProfileEntry> topics = new ArrayList<>();
        List<ProfileEntry> explicit = new ArrayList<>();
        for (ProfileEntry entry : entries) {
            String key = entry.key() == null ? "" : entry.key();
            if (KEY_TURNS.equals(key)) {
                turns = parseInt(entry.value());
            } else if (KEY_LAST_MODE.equals(key)) {
                lastMode = entry.value();
            } else if (KEY_LAST_MODEL.equals(key)) {
                lastModel = entry.value();
            } else if (key.startsWith(TOPIC_PREFIX)) {
                topics.add(entry);
            } else if (key.startsWith(EXPLICIT_PREFIX) && ProfileEntry.SOURCE_MANUAL.equals(entry.sourceType())) {
                explicit.add(entry);
            }
        }
        if (turns > 0) sb.append("- 历史对话轮次：").append(turns).append('\n');
        if (lastMode != null && !lastMode.isBlank()) {
            sb.append("- 常用对话模式：").append(modeLabel(lastMode)).append('\n');
        }
        if (lastModel != null && !lastModel.isBlank()) {
            sb.append("- 常用模型：").append(lastModel).append('\n');
        }
        topics.sort(Comparator.comparingInt((ProfileEntry entry) -> parseInt(entry.value())).reversed());
        if (!topics.isEmpty()) {
            List<String> words = new ArrayList<>();
            for (ProfileEntry topic : topics) {
                if (words.size() >= 5) break;
                words.add(topic.key().substring(TOPIC_PREFIX.length()) + "×" + parseInt(topic.value()));
            }
            sb.append("- 关注主题：").append(String.join("、", words)).append('\n');
        }
        for (ProfileEntry entry : explicit) {
            String key = entry.key().substring(EXPLICIT_PREFIX.length());
            if (!key.startsWith("mem_")) {
                sb.append("- ").append(key).append("：").append(entry.value()).append('\n');
            }
        }
        sb.append("- 使用建议：沿用以上偏好与习惯；用户明确纠正时以本轮指令为准。");
        return sb.toString().trim();
    }

    /* ---------------- 行为采集 ---------------- */

    public void recordTurn(String userId, String conversationId, String mode, String modelId, String content) {
        if (!isPersistedUser(userId)) return;
        try {
            List<ProfileEntry> existing = profiles.listByUser(userId);
            int turns = 0;
            for (ProfileEntry entry : existing) {
                if (KEY_TURNS.equals(entry.key())) turns = parseInt(entry.value());
            }
            profiles.upsert(userId, KEY_TURNS, String.valueOf(turns + 1), ProfileEntry.SOURCE_AUTO);
            if (mode != null && !mode.isBlank()) {
                profiles.upsert(userId, KEY_LAST_MODE, mode, ProfileEntry.SOURCE_AUTO);
            }
            if (modelId != null && !modelId.isBlank()) {
                profiles.upsert(userId, KEY_LAST_MODEL, modelId, ProfileEntry.SOURCE_AUTO);
            }
            for (String keyword : keywords(content)) {
                String key = TOPIC_PREFIX + keyword;
                int count = 0;
                for (ProfileEntry entry : existing) {
                    if (key.equals(entry.key())) count = parseInt(entry.value());
                }
                profiles.upsert(userId, key, String.valueOf(count + 1), ProfileEntry.SOURCE_AUTO);
            }
            profiles.prune(userId, MAX_TOPICS);
        } catch (DataAccessException e) {
            log.debug("记录用户画像失败 user={}: {}", userId, e.getMessage());
        }
    }

    public void recordLogin(String userId) {
        if (!isPersistedUser(userId)) return;
        try {
            profiles.upsert(userId, "pref:last-login", String.valueOf(System.currentTimeMillis()),
                    ProfileEntry.SOURCE_AUTO);
        } catch (DataAccessException e) {
            log.debug("记录登录时间失败 user={}: {}", userId, e.getMessage());
        }
    }

    /* ---------------- 显式记忆镜像 ---------------- */

    /** Redis 是显式记忆的实时来源；这里做 DM 镜像，保证 Redis 数据丢失后偏好可恢复。 */
    public void syncExplicitPrefs(String userId) {
        if (!isPersistedUser(userId)) return;
        try {
            Map<String, String> memories = memoryService.list(userId);
            profiles.deleteBySource(userId, ProfileEntry.SOURCE_MANUAL);
            int count = 0;
            for (Map.Entry<String, String> entry : memories.entrySet()) {
                if (count++ >= 20) break;
                profiles.upsert(userId, EXPLICIT_PREFIX + entry.getKey(), entry.getValue(),
                        ProfileEntry.SOURCE_MANUAL);
            }
        } catch (DataAccessException e) {
            log.debug("同步显式记忆失败 user={}: {}", userId, e.getMessage());
        }
    }

    public void deleteExplicit(String userId, String memoryKey) {
        if (!isPersistedUser(userId) || memoryKey == null) return;
        try {
            profiles.deleteByKey(userId, EXPLICIT_PREFIX + memoryKey);
        } catch (DataAccessException e) {
            log.debug("删除显式记忆镜像失败 user={}: {}", userId, e.getMessage());
        }
    }

    public void deleteTopic(String userId, String keyword) {
        if (!isPersistedUser(userId) || keyword == null || keyword.isBlank()) return;
        try {
            profiles.deleteByKey(userId, TOPIC_PREFIX + keyword.trim());
        } catch (DataAccessException e) {
            log.debug("删除画像主题失败 user={}: {}", userId, e.getMessage());
        }
    }

    /* ---------------- 查询 ---------------- */

    public Map<String, Object> profileOf(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        IdentityPrincipal principal = authService.principalOrAnonymous(userId);
        result.put("userId", principal.userId());
        result.put("displayName", principal.displayName());
        result.put("authenticated", principal.authenticated());
        result.put("roles", principal.roles());
        result.put("domains", principal.domains());
        result.put("admin", principal.admin());
        List<ProfileEntry> entries = isPersistedUser(userId) ? profiles.listByUser(userId) : List.of();
        int turns = 0;
        List<Map<String, Object>> preferences = new ArrayList<>();
        List<Map<String, Object>> topics = new ArrayList<>();
        for (ProfileEntry entry : entries) {
            String key = entry.key() == null ? "" : entry.key();
            if (KEY_TURNS.equals(key)) {
                turns = parseInt(entry.value());
            } else if (key.startsWith(TOPIC_PREFIX)) {
                Map<String, Object> topic = new LinkedHashMap<>();
                topic.put("keyword", key.substring(TOPIC_PREFIX.length()));
                topic.put("count", parseInt(entry.value()));
                topics.add(topic);
            } else if (key.startsWith("pref:")) {
                Map<String, Object> pref = new LinkedHashMap<>();
                pref.put("key", key.substring("pref:".length()));
                pref.put("value", entry.value());
                pref.put("source", entry.sourceType());
                pref.put("updatedAt", entry.updatedAt());
                preferences.add(pref);
            }
        }
        topics.sort(Comparator.comparingInt((Map<String, Object> topic) -> (Integer) topic.get("count")).reversed());
        result.put("turns", turns);
        result.put("preferences", preferences);
        result.put("topics", topics);
        result.put("memories", memoryService.list(userId));
        return result;
    }

    /* ---------------- 内部工具 ---------------- */

    private boolean isPersistedUser(String userId) {
        if (userId == null || userId.isBlank() || IdentityPrincipal.ANONYMOUS.equals(userId)) return false;
        try {
            return authService.findPrincipal(userId).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> keywords(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;
        String normalized = content.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            String word = token.trim();
            if (word.length() < 2 || word.length() > 12) continue;
            if (STOPWORDS.contains(word)) continue;
            if (result.contains(word)) continue;
            result.add(word);
            if (result.size() >= MAX_KEYWORDS_PER_TURN) break;
        }
        return result;
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "chat" -> "对话";
            case "deep" -> "深度思考";
            case "orchestrate" -> "多 Agent";
            default -> mode;
        };
    }
}
