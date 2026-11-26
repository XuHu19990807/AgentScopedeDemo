package com.cetc28.tool;

import com.cetc28.chat.MapCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 让智能体在地图上标注地点/路线/区域。
 *
 * <p>坐标不信任模型：点优先用 {@code city}，由本地城市库解析；线/面才接受 coords。
 * 具体校验、限额与 SSE 推送都在 {@link MapCommandService}。
 */
@Component
public class MapTool {

    private static final Logger log = LoggerFactory.getLogger(MapTool.class);

    private final MapCommandService mapCommandService;
    private final ObjectMapper mapper;

    public MapTool(MapCommandService mapCommandService, ObjectMapper mapper) {
        this.mapCommandService = mapCommandService;
        this.mapper = mapper;
    }

    @Tool(name = "map_render",
            description = "在地球上标注一组地点、路线或区域，并自动调整视角。"
                    + "用户询问城市/地点/区域分布/路线、要求“在地图上标出/查看”，"
                    + "或上传的地图描述需要落到地球时调用。"
                    + "featuresJson 必须是 JSON 数组："
                    + "[{\"kind\":\"point\",\"name\":\"杭州\",\"city\":\"杭州\",\"style\":\"city.primary\"},"
                    + "{\"kind\":\"line\",\"name\":\"沪杭路线\",\"cities\":[\"上海\",\"杭州\"],\"style\":\"route\"},"
                    + "{\"kind\":\"line\",\"name\":\"自定义路线\",\"coords\":[[120.15,30.27],[121.47,31.23]]},"
                    + "{\"kind\":\"polygon\",\"name\":\"长三角\",\"coords\":[[118.8,30.2],[122.0,30.2],[122.0,32.2],[118.8,32.2]],\"style\":\"area\"}]。"
                    + "坐标顺序是 [经度,纬度]；点优先写 city；路线优先按城市顺序写 cities，"
                    + "只有无法用城市表达时才写 coords；不要自己编造经纬度。"
                    + "style 是语义 id（如 city、city.primary、route、route.alert、area、point、line、polygon），"
                    + "由前端样式表解释；不要传颜色、图标、线宽等外观参数，未知 style 会自动回落默认。"
                    + "业务点：通信基站用 site.base-station，飞机用 target.aircraft；"
                    + "只有用户明确提到基站/飞机时才使用，普通地点仍用 city/city.primary。"
                    + "同一轮回答只调用一次；最终回答不要输出 JSON 或坐标。")
    public String render(
            @ToolParam(name = "title", required = true,
                    description = "地图标题，例如“东部新一线城市分布”") String title,
            @ToolParam(name = "featuresJson", required = true,
                    description = "要素 JSON 数组，见工具说明；最多 500 个要素") String featuresJson,
            @ToolParam(name = "subtitle", required = false,
                    description = "可选副标题，例如“共 15 个城市”") String subtitle,
            @ToolParam(name = "viewJson", required = false,
                    description = "可选视角 JSON，例如 {\"fit\":true} 或 {\"lon\":120.15,\"lat\":30.27,\"height\":800000}") String viewJson,
            RuntimeContext context) {
        try {
            return write(mapCommandService.render(
                    conversationId(context), title, subtitle, featuresJson, viewJson));
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(name = "map_view",
            description = "调整地球视角，不改变已有标注。用户说“放大/定位到某地/切到 2D/3D”时调用；"
                    + "参数至少提供一个。fit=true 按当前标注范围取景。")
    public String view(
            @ToolParam(name = "mode", required = false,
                    description = "2d 或 3d；不传保持当前模式") String mode,
            @ToolParam(name = "lon", required = false,
                    description = "经度，-180 到 180；与 lat 同时提供") Double lon,
            @ToolParam(name = "lat", required = false,
                    description = "纬度，-85.05 到 85.05；与 lon 同时提供") Double lat,
            @ToolParam(name = "height", required = false,
                    description = "3D 相机高度（米）") Double height,
            @ToolParam(name = "zoom", required = false,
                    description = "2D 缩放级别 0-22") Double zoom,
            @ToolParam(name = "fit", required = false,
                    description = "true 表示按当前标注范围自动取景") Boolean fit,
            @ToolParam(name = "fly", required = false,
                    description = "false 表示不做飞行动画") Boolean fly,
            RuntimeContext context) {
        try {
            return write(mapCommandService.view(
                    conversationId(context), mode, lon, lat, height, zoom, fit, fly));
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(name = "map_clear",
            description = "清空地球上的所有标注。用户说“清空地图/取消标注”时调用。")
    public String clear(RuntimeContext context) {
        try {
            return write(mapCommandService.clear(conversationId(context)));
        } catch (Exception e) {
            return error(e);
        }
    }

    private String conversationId(RuntimeContext context) {
        return context == null ? null : context.getSessionId();
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"地图结果序列化失败\"}";
        }
    }

    private String error(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("地图工具执行失败: {}", message);
        try {
            return mapper.writeValueAsString(Map.of("ok", false, "error", message));
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"地图工具执行失败\"}";
        }
    }
}
