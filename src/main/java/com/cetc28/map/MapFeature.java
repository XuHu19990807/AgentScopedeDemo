package com.cetc28.map;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 地图上的一个要素：点、线或面。
 *
 * <p>坐标统一为 WGS84，经度在前、纬度在后。point 使用 lon/lat（或 city 由
 * 本地城市库解析）；line/polygon 使用 coords。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MapFeature {

    public static final String KIND_POINT = "point";
    public static final String KIND_LINE = "line";
    public static final String KIND_POLYGON = "polygon";

    private String id;

    /** point | line | polygon */
    private String kind;

    private String name;
    private String label;
    private String city;
    private String province;

    private Double lon;
    private Double lat;

    /** 线/面坐标，[[lon,lat], ...] */
    private List<List<Double>> coords;

    /**
     * 语义样式 id，例如 city、city.primary、route、route.alert、area。
     *
     * <p>后端只约定 id，不约定颜色/图标/线宽；外观由地球前端
     * {@code plot-styles.json} 解析。未知 id 由前端回落默认样式。
     */
    private String style;

    private String description;
}
