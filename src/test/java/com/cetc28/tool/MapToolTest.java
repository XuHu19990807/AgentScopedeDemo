package com.cetc28.tool;

import com.cetc28.chat.MapCommandService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapToolTest {

    @Mock
    private MapCommandService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private MapTool tool;
    private RuntimeContext context;

    @BeforeEach
    void setUp() {
        tool = new MapTool(service, mapper);
        context = mock(RuntimeContext.class);
        when(context.getSessionId()).thenReturn("c1");
    }

    @Test
    void renderUsesSessionAsConversationAndReturnsJson() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("rendered", 2);
        payload.put("pushed", true);
        payload.put("commandId", "m_1");
        when(service.render(eq("c1"), eq("东部城市"), isNull(), anyString(), isNull()))
                .thenReturn(payload);

        String json = tool.render("东部城市", "[{\"kind\":\"point\",\"city\":\"杭州\"}]", null, null, context);
        JsonNode node = mapper.readTree(json);
        assertEquals(true, node.path("ok").asBoolean());
        assertEquals(2, node.path("rendered").asInt());
        verify(service).render(eq("c1"), eq("东部城市"), isNull(), anyString(), isNull());
    }

    @Test
    void viewDelegatesAllParameters() throws Exception {
        when(service.view("c1", "2d", 120.15, 30.27, null, 8.0, true, false))
                .thenReturn(Map.of("ok", true, "message", "已调整地图视角"));

        String json = tool.view("2d", 120.15, 30.27, null, 8.0, true, false, context);
        assertEquals(true, mapper.readTree(json).path("ok").asBoolean());
    }

    @Test
    void clearDelegates() throws Exception {
        when(service.clear("c1")).thenReturn(Map.of("ok", true, "cleared", 3));
        String json = tool.clear(context);
        assertEquals(3, mapper.readTree(json).path("cleared").asInt());
    }

    @Test
    void convertsExceptionsToErrorJson() throws Exception {
        when(service.render(eq("c1"), eq("坏数据"), isNull(), anyString(), isNull()))
                .thenThrow(new IllegalStateException("featuresJson 不是合法 JSON"));

        String json = tool.render("坏数据", "not-json", null, null, context);
        JsonNode node = mapper.readTree(json);
        assertEquals(false, node.path("ok").asBoolean());
        assertTrue(node.path("error").asText().contains("不是合法 JSON"));
    }
}
