package com.cetc28.memory;

import com.cetc28.chat.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆管理。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final UserMemoryService memoryService;

    public MemoryController(UserMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<Map<String, String>> list(HttpServletRequest request) {
        String userId = UserContext.from(request);
        List<Map<String, String>> result = new ArrayList<>();
        memoryService.list(userId).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("key", entry.getKey());
                    item.put("value", entry.getValue());
                    result.add(item);
                });
        return result;
    }

    @PostMapping
    public Map<String, Object> remember(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = UserContext.from(request);
        String key = body.getOrDefault("key", "").trim();
        String value = body.getOrDefault("value", "").trim();
        if (key.isEmpty() || value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key 和 value 不能为空");
        }
        memoryService.remember(userId, key, value);
        return Map.of("success", true);
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> delete(@PathVariable String key, HttpServletRequest request) {
        memoryService.delete(UserContext.from(request), key);
        return Map.of("success", true);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest request) {
        memoryService.clear(UserContext.from(request));
        return Map.of("success", true);
    }
}
