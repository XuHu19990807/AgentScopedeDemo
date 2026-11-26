/*
 * 数字地球运行时配置。
 * 该文件会原样拷贝到构建产物目录，可在部署后直接修改，无需重新构建。
 * 所有路径均相对于当前站点根路径（不是 earth-ui 目录）。
 */
window.EARTH_CONFIG = {
  cesiumBase: '/map-assets/cesium/',
  olBase: '/map-assets/ol/',
  initialMode: '3d',
  initialView: { lon: 105, lat: 35, height: 12000000, zoom: 3 },
  basemap: {
    type: 'natural-earth-ii',
    // 可选的离线底图瓦片目录；留空时回退到 Cesium SDK 自带的 NaturalEarthII。
    baseUrl: '/map-assets/basemap/natural-earth-ii/'
  },
  maxFeatures: 500,
  theme: 'dark',
  // 标绘外观目录：改 plot-styles.json / plot-icons 即可换颜色、图标、线型，无需改 Java。
  plot: {
    stylesUrl: './plot-styles.json',
    iconBase: './plot-icons/'
  },
  // 滚轮缩放时在 3D 地球与 2D 地图之间自动切换：
  // 3D 高度 <= to2dHeight 切到 2D；2D 层级 <= to3dZoom 切回 3D，中间留滞回区。
  autoSwitch: {
    enabled: true,
    to2dHeight: 1500000,
    to3dZoom: 3.5,
    debounceMs: 180
  }
};
