package com.cetc28.map;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 地图视角指令：只描述相机，不承载业务要素。
 *
 * <p>经度在前、纬度在后（WGS84）。fit=true 时前端按要素范围自动取景；
 * fly=false 时不做飞行动画。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MapView {

    /** 2d | 3d；为空表示保持当前模式 */
    private String mode;

    private Double lon;
    private Double lat;

    /** Cesium 相机高度（米） */
    private Double height;

    /** OpenLayers 缩放级别 */
    private Double zoom;

    /** 是否按要素范围自动取景 */
    private Boolean fit;

    /** 是否使用飞行动画；false 表示直接定位 */
    private Boolean fly;
}
