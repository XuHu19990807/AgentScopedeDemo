package com.cetc28.chat;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理：列表、新建、查看、重命名、删除。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationStore store;
    private final ChatFileService fileService;

    @Value("${chat.history-limit:50}")
    private int defaultHistoryLimit = 50;

    public ConversationController(ConversationStore store, ChatFileService fileService) {
        this.store = store;
        this.fileService = fileService;
    }

    @GetMapping
    public List<Conversation> list(HttpServletRequest request) {
        return store.list(UserContext.from(request), 200);
    }

    @PostMapping
    public Conversation create(HttpServletRequest request) {
        return store.create(UserContext.from(request));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id,
                                   @RequestParam(value = "limit", required = false) Integer limit,
                                   HttpServletRequest request) {
        String userId = UserContext.from(request);
        Conversation conversation = store.get(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        int size = limit != null && limit > 0 ? Math.min(limit, 200) : defaultHistoryLimit;
        List<ChatMessage> messages = store.recentMessages(id, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", conversation);
        result.put("messages", messages);
        result.put("hasMore", store.messageCount(id) > messages.size());
        return result;
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id,
                                        @RequestParam(value = "limit", required = false) Integer limit,
                                        HttpServletRequest request) {
        String userId = UserContext.from(request);
        store.get(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        int size = limit != null && limit > 0 ? Math.min(limit, 200) : defaultHistoryLimit;
        List<ChatMessage> messages = store.recentMessages(id, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("hasMore", store.messageCount(id) > messages.size());
        return result;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> rename(@PathVariable String id,
                                      @RequestBody Map<String, String> body,
                                      HttpServletRequest request) {
        String userId = UserContext.from(request);
        store.get(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        String title = body.getOrDefault("title", "").trim();
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (title.length() > 80) title = title.substring(0, 80);
        store.rename(id, title);
        return Map.of("success", true, "title", title);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        String userId = UserContext.from(request);
        store.get(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        try {
            fileService.deleteByConversation(id, userId);
        } catch (Exception e) {
            log.warn("清理会话附件失败 cid={}: {}", id, e.getMessage());
        }
        store.delete(id, userId);
        return Map.of("success", true);
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(HttpServletRequest request) {
        String userId = UserContext.from(request);
        try {
            fileService.deleteByUser(userId);
        } catch (Exception e) {
            log.warn("清理用户附件失败 uid={}: {}", userId, e.getMessage());
        }
        store.deleteAll(userId);
        return Map.of("success", true);
    }
}
