package com.cetc28.controller;

import com.cetc28.dto.ChatResponse;
import com.cetc28.mcp.McpHubService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 管理控制器
 *
 * 工具信息直接从 McpHubService 的共享 Toolkit 获取，
 * 不再依赖 McpToolRegistry。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpAdminController {

    @Autowired
    private McpHubService mcpHubService;

    @GetMapping("/servers")
    public List<Map<String, Object>> listServers() {
        return mcpHubService.getServerInfoList();
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        var toolkit = mcpHubService.getSharedToolkit();
        var schemas = toolkit.getToolSchemas();
        return Map.of(
                "totalTools", schemas.size(),
                "tools", schemas.stream()
                        .map(s -> Map.of("name", s.getName(), "description", s.getDescription()))
                        .collect(Collectors.toList())
        );
    }

    @PostMapping("/connect/stdio")
    public ChatResponse connectStdio(@RequestParam String name,
                                      @RequestParam String command,
                                      @RequestParam(required = false, defaultValue = "") String args,
                                      @RequestParam(defaultValue = "true") boolean enabled) {
        boolean success = mcpHubService.connectStdio(name, command, args, enabled);
        return success ? ChatResponse.success("MCP Stdio '" + name + "' connected")
                : ChatResponse.error("Failed to connect MCP Stdio '" + name + "'");
    }

    @PostMapping("/connect/sse")
    public ChatResponse connectSse(@RequestParam String name,
                                    @RequestParam String url,
                                    @RequestParam(defaultValue = "true") boolean enabled) {
        boolean success = mcpHubService.connectSse(name, url, enabled);
        return success ? ChatResponse.success("MCP SSE '" + name + "' connected")
                : ChatResponse.error("Failed to connect MCP SSE '" + name + "'");
    }

    @DeleteMapping("/disconnect")
    public ChatResponse disconnect(@RequestParam String name) {
        boolean success = mcpHubService.disconnect(name);
        return success ? ChatResponse.success("MCP '" + name + "' disconnected")
                : ChatResponse.error("MCP '" + name + "' not found");
    }

    /** 手动立即重连（清零退避）；断连/失败的条目也能通过此接口恢复 */
    @PostMapping("/reconnect")
    public ChatResponse reconnect(@RequestParam String name) {
        boolean found = mcpHubService.reconnectNow(name);
        return found ? ChatResponse.success("已触发重连 MCP '" + name + "'")
                : ChatResponse.error("MCP '" + name + "' 不存在");
    }
}
