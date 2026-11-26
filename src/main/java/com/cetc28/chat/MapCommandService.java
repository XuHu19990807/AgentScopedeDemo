package com.cetc28.chat;

import com.cetc28.map.CityGazetteer;
import com.cetc28.map.MapCommand;
import com.cetc28.map.MapFeature;
import com.cetc28.map.MapView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 地图指令服务：校验工具参数、生成 {@link MapCommand}、写入当前 run 并通过 SSE 推送。
 *
 * <p>放在 chat 包而不是 map 包，是为了复用 {@link RunRegistry}/{@link SsePublisher}
 * 而不让 map 包反向依赖 chat。工具只负责把模型的 JSON 参数传进来，所有坐标解析、
 * 限额与容错都在这里做。
 */
@Service
public class MapCommandService {

    private static final Logger log = LoggerFactory.getLogger(MapCommandService.class);

    private static final int MAX_FEATURES = 500;
    private static final int MAX_COORDS_PER_FEATURE = 2000;
    private static final int MAX_TOTAL_POINTS = 10_000;
    private static final int MAX_FEATURES_JSON_BYTES = 64 * 1024;
    private static final int MAX_VIEW_JSON_BYTES = 4 * 1024;
    private static final int MAX_COMMAND_BYTES = 256 * 1024;
    private static final int MAX_COMMANDS = 20;

    private static final int MAX_TITLE = 120;
    private static final int MAX_SUBTITLE = 200;
    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 300;
    private static final int MAX_CITIES = 50;

    /**
     * style 是前端样式表的语义 id，不是颜色/CSS。
     * 后端只做字符安全校验，不维护白名单，避免前端新增样式还要改 Java。
     */
    private static final Pattern STYLE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final RunRegistry runRegistry;
    private final SsePublisher publisher;
    private final CityGazetteer gazetteer;
    private final ObjectMapper mapper;

    public MapCommandService(RunRegistry runRegistry, SsePublisher publisher,
                             CityGazetteer gazetteer, ObjectMapper mapper) {
        this.runRegistry = runRegistry;
        this.publisher = publisher;
        this.gazetteer = gazetteer;
        this.mapper = mapper;
    }

    // ==================== 对外方法 ====================

    public Map<String, Object> render(String conversationId, String title, String subtitle,
                                      String featuresJson, String viewJson) {
        RunContext run = activeRun(conversationId);
        if (run == null) return fail("当前没有进行中的对话，无法上图");

        if (isBlank(featuresJson)) return fail("featuresJson 不能为空");
        if (utf8Length(featuresJson) > MAX_FEATURES_JSON_BYTES) {
            return fail("featuresJson 过大（上限 64KB），请减少标注数量");
        }

        List<MapFeature> features;
        List<String> skippedNames = new ArrayList<>();
        try {
            features = parseFeatures(featuresJson, skippedNames);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        if (features.isEmpty()) {
            return fail(skippedNames.isEmpty()
                    ? "没有可渲染的要素，请检查 featuresJson"
                    : "没有可渲染的要素，未识别的城市：" + String.join("、", skippedNames));
        }

        MapView view;
        try {
            view = parseView(viewJson, true, true);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }

        MapCommand command = MapCommand.builder()
                .id(newCommandId())
                .protocol(MapCommand.PROTOCOL)
                .seq(run.nextMapSeq())
                .type(MapCommand.TYPE_REPLACE)
                .title(clip(firstNonBlank(title, "地图标注"), MAX_TITLE))
                .subtitle(clip(subtitle, MAX_SUBTITLE))
                .view(view)
                .features(features)
                .createdAt(System.currentTimeMillis())
                .build();

        boolean truncated = enforceCommandSize(command);
        if (command.getFeatures().isEmpty()) {
            return fail("标注内容过大，请减少标注数量或简化描述");
        }

        synchronized (run) {
            if (run.isFinished()) return fail("当前对话已结束，无法上图");
            run.replaceMapCommands(command);
        }
        boolean pushed = publisher.send(run.getEmitter(), "map", command);

        Map<String, Object> result = ok(command.getFeatures().size(), skippedNames.size(),
                pushed, command.getId(), command.getTitle(),
                "已在地图上标注 " + command.getFeatures().size() + " 个要素");
        if (!skippedNames.isEmpty()) result.put("skippedNames", skippedNames);
        if (truncated) result.put("truncated", true);
        return result;
    }

    public Map<String, Object> view(String conversationId, String mode, Double lon, Double lat,
                                    Double height, Double zoom, Boolean fit, Boolean fly) {
        RunContext run = activeRun(conversationId);
        if (run == null) return fail("当前没有进行中的对话，无法调整地图视角");

        MapView view;
        try {
            view = buildView(mode, lon, lat, height, zoom, fit, fly);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        if (view == null) {
            return fail("请至少提供一个视角参数：mode/lon/lat/height/zoom/fit/fly");
        }

        MapCommand command = MapCommand.builder()
                .id(newCommandId())
                .protocol(MapCommand.PROTOCOL)
                .seq(run.nextMapSeq())
                .type(MapCommand.TYPE_VIEW)
                .view(view)
                .createdAt(System.currentTimeMillis())
                .build();

        if (!append(run, command)) return fail("当前对话已结束，无法调整地图视角");
        boolean pushed = publisher.send(run.getEmitter(), "map", command);
        Map<String, Object> result = ok(0, 0, pushed, command.getId(), null, "已调整地图视角");
        return result;
    }

    public Map<String, Object> clear(String conversationId) {
        RunContext run = activeRun(conversationId);
        if (run == null) return fail("当前没有进行中的对话，无法清空地图");

        int cleared = run.mapCommands().size();
        MapCommand command = MapCommand.builder()
                .id(newCommandId())
                .protocol(MapCommand.PROTOCOL)
                .seq(run.nextMapSeq())
                .type(MapCommand.TYPE_CLEAR)
                .title("已清空地图")
                .createdAt(System.currentTimeMillis())
                .build();

        if (!append(run, command)) return fail("当前对话已结束，无法清空地图");
        boolean pushed = publisher.send(run.getEmitter(), "map", command);
        Map<String, Object> result = ok(0, 0, pushed, command.getId(), null, "已清空地图");
        result.put("cleared", cleared);
        return result;
    }

    // ==================== 解析与校验 ====================

    private List<MapFeature> parseFeatures(String featuresJson, List<String> skippedNames) {
        JsonNode root;
        try {
            root = mapper.readTree(featuresJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("featuresJson 不是合法 JSON");
        }
        JsonNode array = root != null && root.isArray() ? root : root == null ? null : root.get("features");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("featuresJson 必须是数组，或 {\"features\":[...]} 对象");
        }
        if (array.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("要素数量超过上限 " + MAX_FEATURES);
        }

        List<MapFeature> features = new ArrayList<>();
        int[] totalPoints = {0};
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            MapFeature feature = parseFeature(node, i, skippedNames, totalPoints);
            if (feature != null) features.add(feature);
        }
        return features;
    }

    private MapFeature parseFeature(JsonNode node, int index, List<String> skippedNames, int[] totalPoints) {
        if (node == null || !node.isObject()) {
            skippedNames.add("第 " + (index + 1) + " 项");
            return null;
        }
        String kind = lower(text(node, "kind"));
        if (isBlank(kind)) kind = MapFeature.KIND_POINT;
        if (!MapFeature.KIND_POINT.equals(kind)
                && !MapFeature.KIND_LINE.equals(kind)
                && !MapFeature.KIND_POLYGON.equals(kind)) {
            skippedNames.add(firstNonBlank(text(node, "name"), text(node, "city"), "第 " + (index + 1) + " 项"));
            return null;
        }

        String name = firstNonBlank(text(node, "name"), text(node, "label"), text(node, "city"));
        String style = text(node, "style");
        String description = clip(text(node, "description"), MAX_DESCRIPTION);
        String id = clip(firstNonBlank(text(node, "id"), "f_" + (index + 1)), 64);

        if (MapFeature.KIND_POINT.equals(kind)) {
            String city = text(node, "city");
            String province = text(node, "province");
            CityGazetteer.CityEntry entry = null;
            if (!isBlank(city)) {
                entry = gazetteer.resolve(city, province);
                if (entry == null) {
                    skippedNames.add(firstNonBlank(name, city));
                    return null;
                }
            }

            Double lon;
            Double lat;
            if (entry != null) {
                lon = entry.getLon();
                lat = entry.getLat();
                city = entry.getName();
                province = entry.getProvince();
                if (isBlank(name)) name = city;
            } else {
                lon = number(node, "lon");
                lat = number(node, "lat");
                if (!validLonLat(lon, lat)) {
                    skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
                    return null;
                }
            }

            if (isBlank(name)) name = "标注点";
            return MapFeature.builder()
                    .id(id)
                    .kind(MapFeature.KIND_POINT)
                    .name(clip(name, MAX_NAME))
                    .label(clip(firstNonBlank(text(node, "label"), name), MAX_NAME))
                    .city(clip(city, MAX_NAME))
                    .province(clip(province, MAX_NAME))
                    .lon(lon)
                    .lat(lat)
                    .style(normalizeStyleToken(style, MapFeature.KIND_POINT))
                    .description(description)
                    .build();
        }

        // 线/面：coords 优先；线在没有 coords 时可用 cities 由城市库解析。
        JsonNode coordsNode = node.get("coords");
        int minimum = MapFeature.KIND_POLYGON.equals(kind) ? 3 : 2;
        List<List<Double>> coords = new ArrayList<>();

        if (coordsNode != null && coordsNode.isArray() && coordsNode.size() >= minimum) {
            for (JsonNode point : coordsNode) {
                if (point == null || !point.isArray() || point.size() < 2) {
                    skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
                    return null;
                }
                Double lon = asDouble(point.get(0));
                Double lat = asDouble(point.get(1));
                if (!validLonLat(lon, lat)) {
                    skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
                    return null;
                }
                coords.add(List.of(lon, lat));
            }
        } else if (MapFeature.KIND_LINE.equals(kind) && (coordsNode == null || coordsNode.isNull())) {
            coords = resolveCities(node.get("cities"), name, index, skippedNames);
            if (coords == null) return null;
        } else {
            skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
            return null;
        }

        if (coords.size() > MAX_COORDS_PER_FEATURE) {
            skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
            return null;
        }
        if (MapFeature.KIND_POLYGON.equals(kind)) {
            List<Double> first = coords.get(0);
            List<Double> last = coords.get(coords.size() - 1);
            if (!first.get(0).equals(last.get(0)) || !first.get(1).equals(last.get(1))) {
                coords.add(List.of(first.get(0), first.get(1)));
            }
        }
        int points = coords.size();
        if (totalPoints[0] + points > MAX_TOTAL_POINTS) {
            skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项") + "（坐标点超限）");
            return null;
        }
        totalPoints[0] += points;

        if (isBlank(name)) name = MapFeature.KIND_POLYGON.equals(kind) ? "区域" : "路线";
        return MapFeature.builder()
                .id(id)
                .kind(kind)
                .name(clip(name, MAX_NAME))
                .label(clip(firstNonBlank(text(node, "label"), name), MAX_NAME))
                .coords(coords)
                .style(normalizeStyleToken(style, kind))
                .description(description)
                .build();
    }

    /**
     * 把城市名数组解析成折线坐标；任一项无法唯一解析时不生成该路线。
     */
    private List<List<Double>> resolveCities(JsonNode citiesNode, String name, int index,
                                             List<String> skippedNames) {
        if (citiesNode == null || !citiesNode.isArray()
                || citiesNode.size() < 2 || citiesNode.size() > MAX_CITIES) {
            skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
            return null;
        }
        List<List<Double>> coords = new ArrayList<>();
        for (JsonNode cityNode : citiesNode) {
            String cityName = cityNode == null || !cityNode.isTextual() ? null : cityNode.asText();
            if (isBlank(cityName)) {
                skippedNames.add(firstNonBlank(name, "第 " + (index + 1) + " 项"));
                return null;
            }
            CityGazetteer.CityEntry entry = gazetteer.resolve(cityName);
            if (entry == null || entry.getLon() == null || entry.getLat() == null) {
                skippedNames.add(cityName);
                return null;
            }
            coords.add(List.of(entry.getLon(), entry.getLat()));
        }
        return coords;
    }

    private String normalizeStyleToken(String raw, String kind) {
        String style = lower(raw);
        if (isBlank(style) || !STYLE_PATTERN.matcher(style).matches()) {
            return MapFeature.KIND_POINT.equals(kind) ? "point"
                    : MapFeature.KIND_POLYGON.equals(kind) ? "area" : "route";
        }
        return style;
    }

    private MapView parseView(String viewJson, boolean forRender, boolean hasFeatures) {
        if (isBlank(viewJson)) {
            return forRender && hasFeatures ? MapView.builder().fit(true).build() : null;
        }
        if (utf8Length(viewJson) > MAX_VIEW_JSON_BYTES) {
            throw new IllegalArgumentException("viewJson 过大（上限 4KB）");
        }
        JsonNode node;
        try {
            node = mapper.readTree(viewJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("viewJson 不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("viewJson 必须是 JSON 对象");
        }

        String mode = lower(text(node, "mode"));
        if (!isBlank(mode) && !"2d".equals(mode) && !"3d".equals(mode)) {
            throw new IllegalArgumentException("mode 只能是 2d 或 3d");
        }
        Double lon = number(node, "lon");
        Double lat = number(node, "lat");
        if ((lon == null) != (lat == null)) {
            throw new IllegalArgumentException("lon 与 lat 必须同时提供");
        }
        if (lon != null && !validLonLat(lon, lat)) {
            throw new IllegalArgumentException("经纬度超出范围");
        }
        Double height = number(node, "height");
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height 必须大于 0");
        }
        Double zoom = number(node, "zoom");
        if (zoom != null && (zoom < 0 || zoom > 22)) {
            throw new IllegalArgumentException("zoom 必须在 0-22 之间");
        }
        Boolean fit = bool(node, "fit");
        Boolean fly = bool(node, "fly");
        if (forRender && fit == null && lon == null && hasFeatures) fit = true;

        return MapView.builder()
                .mode(isBlank(mode) ? null : mode)
                .lon(lon)
                .lat(lat)
                .height(height)
                .zoom(zoom)
                .fit(fit)
                .fly(fly)
                .build();
    }

    private MapView buildView(String mode, Double lon, Double lat, Double height, Double zoom,
                              Boolean fit, Boolean fly) {
        String normalizedMode = lower(mode);
        if (!isBlank(normalizedMode) && !"2d".equals(normalizedMode) && !"3d".equals(normalizedMode)) {
            throw new IllegalArgumentException("mode 只能是 2d 或 3d");
        }
        if ((lon == null) != (lat == null)) {
            throw new IllegalArgumentException("lon 与 lat 必须同时提供");
        }
        if (lon != null && !validLonLat(lon, lat)) {
            throw new IllegalArgumentException("经纬度超出范围");
        }
        if (height != null && height <= 0) {
            throw new IllegalArgumentException("height 必须大于 0");
        }
        if (zoom != null && (zoom < 0 || zoom > 22)) {
            throw new IllegalArgumentException("zoom 必须在 0-22 之间");
        }
        if (isBlank(normalizedMode) && lon == null && height == null && zoom == null
                && fit == null && fly == null) {
            return null;
        }
        return MapView.builder()
                .mode(isBlank(normalizedMode) ? null : normalizedMode)
                .lon(lon)
                .lat(lat)
                .height(height)
                .zoom(zoom)
                .fit(fit)
                .fly(fly)
                .build();
    }

    // ==================== 命令与限额 ====================

    private boolean append(RunContext run, MapCommand command) {
        synchronized (run) {
            if (run.isFinished()) return false;
            while (run.mapCommands().size() >= MAX_COMMANDS) {
                run.mapCommands().remove(0);
            }
            run.addMapCommand(command);
            return true;
        }
    }

    /** 超过 256KB 时先清描述、再截断描述、最后从尾部丢要素；返回是否做过裁剪。 */
    private boolean enforceCommandSize(MapCommand command) {
        if (commandBytes(command) <= MAX_COMMAND_BYTES) return false;

        boolean trimmed = true;
        for (MapFeature feature : command.getFeatures()) feature.setDescription(null);
        if (commandBytes(command) <= MAX_COMMAND_BYTES) return trimmed;

        for (int limit : new int[]{200, 100}) {
            for (MapFeature feature : command.getFeatures()) {
                feature.setDescription(clip(feature.getDescription(), limit));
            }
            if (commandBytes(command) <= MAX_COMMAND_BYTES) return trimmed;
        }

        while (commandBytes(command) > MAX_COMMAND_BYTES && command.getFeatures().size() > 1) {
            command.getFeatures().remove(command.getFeatures().size() - 1);
        }
        if (commandBytes(command) > MAX_COMMAND_BYTES) {
            // 单要素仍过大：压缩线/面坐标点，至少保留 2/3 个点；多边形要保持闭合。
            for (MapFeature feature : command.getFeatures()) {
                if (feature.getCoords() != null && feature.getCoords().size() > 100) {
                    boolean polygon = MapFeature.KIND_POLYGON.equals(feature.getKind());
                    int keep = polygon ? 3 : 2;
                    int end = Math.min(feature.getCoords().size(), Math.max(keep, 100));
                    List<List<Double>> reduced = new ArrayList<>(feature.getCoords().subList(0, end));
                    if (polygon && !reduced.isEmpty()) {
                        reduced.add(List.of(reduced.get(0).get(0), reduced.get(0).get(1)));
                    }
                    feature.setCoords(reduced);
                }
            }
        }
        return trimmed;
    }

    private int commandBytes(MapCommand command) {
        try {
            return mapper.writeValueAsBytes(command).length;
        } catch (Exception e) {
            log.warn("地图命令序列化失败: {}", e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    // ==================== 工具 ====================

    private RunContext activeRun(String conversationId) {
        if (isBlank(conversationId)) return null;
        RunContext run = runRegistry.activeFor(conversationId);
        return run == null || run.isFinished() ? null : run;
    }

    private String newCommandId() {
        return "m_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Map<String, Object> ok(int rendered, int skipped, boolean pushed,
                                   String commandId, String title, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("rendered", rendered);
        result.put("skipped", skipped);
        result.put("pushed", pushed);
        result.put("commandId", commandId);
        if (title != null) result.put("title", title);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        return asDouble(value);
    }

    private Double asDouble(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isTextual()) {
            String text = value.asText().trim();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private boolean validLonLat(Double lon, Double lat) {
        return lon != null && lat != null
                && lon >= -180 && lon <= 180
                && lat >= -85.05 && lat <= 85.05;
    }

    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }
}
