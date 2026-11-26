package com.cetc28.controller;

import com.cetc28.dto.ChatResponse;
import com.cetc28.mcp.McpHubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAdminControllerTest {

    @Mock
    private McpHubService mcpHubService;

    @InjectMocks
    private McpAdminController controller;

    @Test
    void reconnectFoundReturnsSuccess() {
        when(mcpHubService.reconnectNow("svc")).thenReturn(true);
        ChatResponse resp = controller.reconnect("svc");
        assertTrue(resp.isSuccess());
        assertTrue(resp.getContent().contains("svc"));
        verify(mcpHubService).reconnectNow("svc");
    }

    @Test
    void reconnectUnknownReturnsError() {
        when(mcpHubService.reconnectNow("ghost")).thenReturn(false);
        ChatResponse resp = controller.reconnect("ghost");
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("ghost"));
    }

    @Test
    void connectSseFailureSurfacesError() {
        when(mcpHubService.connectSse("svc", "http://x:1/sse", true)).thenReturn(false);
        ChatResponse resp = controller.connectSse("svc", "http://x:1/sse", true);
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("svc"));
    }

    @Test
    void listServersPassesThroughHubFields() {
        when(mcpHubService.getServerInfoList()).thenReturn(List.of(
                Map.of("name", "svc", "status", "FAILED", "attempts", 3L)));
        List<Map<String, Object>> servers = controller.listServers();
        assertEquals(1, servers.size());
        assertEquals("FAILED", servers.get(0).get("status"));
        assertEquals(3L, servers.get(0).get("attempts"));
    }

    @Test
    void disconnectPassesThrough() {
        when(mcpHubService.disconnect("svc")).thenReturn(true);
        assertTrue(controller.disconnect("svc").isSuccess());
    }
}
