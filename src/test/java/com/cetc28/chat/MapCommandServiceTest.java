package com.cetc28.chat;

import com.cetc28.map.CityGazetteer;
import com.cetc28.map.MapCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MapCommandServiceTest {

    @Mock
    private RunRegistry runRegistry;

    @Mock
    private SsePublisher publisher;

    private final ObjectMapper mapper = new ObjectMapper();
    private CityGazetteer gazetteer;
    private MapCommandService service;
    private RunContext run;

    @BeforeEach
    void setUp() {
        gazetteer = new CityGazetteer(mapper);
        gazetteer.load();
        service = new MapCommandService(runRegistry, publisher, gazetteer, mapper);
        run = new RunContext("c1", "u1", ChatMode.CHAT, mock(SseEmitter.class));
        when(runRegistry.activeFor("c1")).thenReturn(run);
        when(publisher.send(any(), eq("map"), any())).thenReturn(true);
    }

    @Test
    void renderResolvesCityNamesAndPublishesReplaceCommand() {
        Map<String, Object> result = service.render("c1", "东部城市", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"},"
                        + "{\"kind\":\"point\",\"name\":\"上海\",\"city\":\"上海市\"}]",
                null);

        assertEquals(true, result.get("ok"));
        assertEquals(2, result.get("rendered"));
        assertEquals(1, run.mapCommands().size());
        MapCommand command = run.mapCommands().get(0);
        assertEquals(MapCommand.TYPE_REPLACE, command.getType());
        assertEquals("东部城市", command.getTitle());
        assertEquals(2, command.getFeatures().size());
        assertNotNull(command.getFeatures().get(0).getLon());
        assertEquals("杭州", command.getFeatures().get(0).getCity());
        assertEquals(Boolean.TRUE, command.getView().getFit());
        assertEquals(MapCommand.PROTOCOL, command.getProtocol());
        assertTrue(command.getLegend() == null || command.getLegend().isEmpty());
    }

    @Test
    void replaceClearsPreviousCommands() {
        service.render("c1", "第一次", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}]", null);
        service.render("c1", "第二次", null,
                "[{\"kind\":\"point\",\"name\":\"上海\",\"city\":\"上海\"}]", null);

        assertEquals(1, run.mapCommands().size());
        assertEquals("第二次", run.mapCommands().get(0).getTitle());
    }

    @Test
    void unknownCityIsSkippedAndReported() {
        Map<String, Object> result = service.render("c1", "测试", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"},"
                        + "{\"kind\":\"point\",\"name\":\"不存在\",\"city\":\"不存在\"}]",
                null);

        assertEquals(true, result.get("ok"));
        assertEquals(1, result.get("rendered"));
        assertEquals(1, result.get("skipped"));
        assertTrue(result.get("skippedNames").toString().contains("不存在"));
    }

    @Test
    void allUnknownFeaturesFailWithoutPublishing() {
        Map<String, Object> result = service.render("c1", "测试", null,
                "[{\"kind\":\"point\",\"name\":\"不存在\",\"city\":\"不存在\"}]", null);

        assertEquals(false, result.get("ok"));
        assertTrue(run.mapCommands().isEmpty());
    }

    @Test
    void lineAndPolygonUseCoordinates() {
        Map<String, Object> result = service.render("c1", "路线", null,
                "[{\"kind\":\"line\",\"name\":\"沪杭\",\"coords\":[[120.15,30.27],[121.47,31.23]]},"
                        + "{\"kind\":\"polygon\",\"name\":\"区域\",\"coords\":[[118.8,30.2],[122.0,30.2],[122.0,32.2]]}]",
                null);

        assertEquals(true, result.get("ok"));
        MapCommand command = run.mapCommands().get(0);
        assertEquals(2, command.getFeatures().size());
        assertEquals("route", command.getFeatures().get(0).getStyle());
        assertEquals("area", command.getFeatures().get(1).getStyle());
        // 多边形自动闭合
        assertEquals(4, command.getFeatures().get(1).getCoords().size());
    }

    @Test
    void lineCanBeBuiltFromCitiesAndKeepsSemanticStyle() {
        Map<String, Object> result = service.render("c1", "城市路线", null,
                "[{\"kind\":\"line\",\"name\":\"沪杭\",\"cities\":[\"上海\",\"杭州\"],\"style\":\"route.alert\"}]",
                null);

        assertEquals(true, result.get("ok"));
        MapCommand command = run.mapCommands().get(0);
        assertEquals(1, command.getFeatures().size());
        assertEquals(2, command.getFeatures().get(0).getCoords().size());
        assertEquals("route.alert", command.getFeatures().get(0).getStyle());
    }

    @Test
    void unknownStyleTokenFallsBackByKind() {
        Map<String, Object> result = service.render("c1", "未知样式", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\",\"style\":\"city.neon\"},"
                        + "{\"kind\":\"line\",\"name\":\"路线\",\"coords\":[[120.15,30.27],[121.47,31.23]],\"style\":\"my-route-2\"},"
                        + "{\"kind\":\"point\",\"name\":\"上海\",\"city\":\"上海\",\"style\":\"bad style!\"}]",
                null);

        assertEquals(true, result.get("ok"));
        MapCommand command = run.mapCommands().get(0);
        assertEquals("city.neon", command.getFeatures().get(0).getStyle());
        assertEquals("my-route-2", command.getFeatures().get(1).getStyle());
        assertEquals("point", command.getFeatures().get(2).getStyle());
    }

    @Test
    void lineWithUnknownCityIsSkipped() {
        Map<String, Object> result = service.render("c1", "路线", null,
                "[{\"kind\":\"line\",\"name\":\"沪杭\",\"cities\":[\"上海\",\"不存在\"]}]",
                null);

        assertEquals(false, result.get("ok"));
        assertTrue(result.get("error").toString().contains("不存在"));
    }

    @Test
    void viewAndClearAppendCommands() {
        service.render("c1", "杭州", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}]", null);

        Map<String, Object> view = service.view("c1", "2d", 120.15, 30.27, null, 8.0, null, true);
        assertEquals(true, view.get("ok"));
        assertEquals(2, run.mapCommands().size());
        assertEquals(MapCommand.TYPE_VIEW, run.mapCommands().get(1).getType());
        assertEquals("2d", run.mapCommands().get(1).getView().getMode());

        Map<String, Object> clear = service.clear("c1");
        assertEquals(true, clear.get("ok"));
        assertEquals(3, run.mapCommands().size());
        assertEquals(MapCommand.TYPE_CLEAR, run.mapCommands().get(2).getType());
        assertEquals(2, clear.get("cleared"));
    }

    @Test
    void noActiveRunReturnsError() {
        when(runRegistry.activeFor("c2")).thenReturn(null);
        Map<String, Object> result = service.render("c2", "测试", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}]", null);
        assertEquals(false, result.get("ok"));
        assertTrue(result.get("error").toString().contains("没有进行中的对话"));
    }

    @Test
    void finishedRunReturnsError() {
        run.markFinished();
        Map<String, Object> result = service.render("c1", "测试", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}]", null);
        assertEquals(false, result.get("ok"));
    }

    @Test
    void rejectsInvalidView() {
        Map<String, Object> result = service.render("c1", "测试", null,
                "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}]",
                "{\"mode\":\"4d\"}");
        assertEquals(false, result.get("ok"));
    }

    @Test
    void rejectsTooManyFeatures() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) json.append(',');
            json.append("{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\"}");
        }
        json.append(']');

        Map<String, Object> result = service.render("c1", "测试", null, json.toString(), null);
        assertEquals(false, result.get("ok"));
        assertTrue(result.get("error").toString().contains("上限"));
    }
}
