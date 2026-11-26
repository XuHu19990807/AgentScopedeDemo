package com.cetc28.map;

/**
 * 地图能力提示词。Nacos 上的旧提示词不会包含地图说明，因此各 Agent 在加载提示词后
 * 统一调用 {@link #ensure(String)} 追加一段，保证地图工具可用时模型知道怎么用。
 */
public final class MapPrompt {

    /** 用于判重的稳定标题；不要改成工具名，工具清单里本来就会出现 map_render。 */
    public static final String MARKER = "## 地图联动";

    public static final String ADDENDUM = """

            ## 地图联动

            - 当用户询问地点、城市、区域、路线、分布，或明确要求“在地图上标出/查看”时，先调用 `map_render` 把结果上图，再用文字回答。
            - 用户上传/粘贴地图描述、行程、区域说明时，把其中可定位的地点、路线、范围转成 `map_render` 要素，不要只做文字总结。
            - `featuresJson` 是 JSON 数组：
              点：`[{"kind":"point","name":"杭州","city":"杭州","style":"city.primary"}]`；
              线：`[{"kind":"line","name":"沪杭路线","cities":["上海","杭州"],"style":"route"}]`；
              面：`[{"kind":"polygon","name":"长三角","coords":[[118.8,30.2],[122.0,30.2],[122.0,32.2],[118.8,32.2]],"style":"area"}]`。
            - 经度在前、纬度在后（WGS84）。城市点优先写 `city`；路线优先用 `cities` 按顺序表达，只有无法用城市表达时才写 `coords`；不要自己编造经纬度。
            - `style` 是语义 id（如 city、city.primary、route、route.alert、area、point、line、polygon），具体图标/颜色/线宽由前端 `plot-styles.json` 决定；不要传颜色、图标、线宽等外观参数，未知 style 会自动回落默认。
            - 业务点：用户明确提到“通信基站/基站”时用 `site.base-station`，提到“飞机/航班”时用 `target.aircraft`；普通地点仍用 `city`/`city.primary`，不要把所有点都套成业务样式。
            - 同一轮回答只调用一次 `map_render`；需要切换视角用 `map_view`，清空用 `map_clear`。
            - 最终回答不要输出 JSON、经纬度或工具参数；用一句话说明已在地图上标注即可。
            """;

    private MapPrompt() {
    }

    /** 已包含地图说明时原样返回，避免 Nacos 热更新/重复 initialize 造成多段重复。 */
    public static String ensure(String prompt) {
        if (prompt == null || prompt.isBlank()) return ADDENDUM;
        if (prompt.contains(MARKER)) return prompt;
        return prompt + ADDENDUM;
    }
}
