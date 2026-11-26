package com.cetc28.map;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条地图指令，作为 SSE {@code map} 事件的 data 原样下发给前端。
 *
 * <ul>
 *   <li>{@code replace}：用 features 替换当前图层（一轮回答通常只有一条）</li>
 *   <li>{@code view}：只调整视角，不动要素</li>
 *   <li>{@code clear}：清空要素</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MapCommand {

    public static final String TYPE_REPLACE = "replace";
    public static final String TYPE_VIEW = "view";
    public static final String TYPE_CLEAR = "clear";

    /** 前后端共享的标绘协议版本；前端可据此做兼容，缺失时按 v1 处理。 */
    public static final String PROTOCOL = "agentchat.plot/v1";

    private String id;

    /** 协议版本，例如 agentchat.plot/v1。 */
    private String protocol;

    /** 同一轮 run 内递增，用于前端去重/排序 */
    private int seq;

    /** replace | view | clear */
    private String type;

    private String title;
    private String subtitle;
    private MapView view;

    @Builder.Default
    private List<MapFeature> features = new ArrayList<>();

    @Builder.Default
    private List<MapLegendItem> legend = new ArrayList<>();

    private long createdAt;
}
