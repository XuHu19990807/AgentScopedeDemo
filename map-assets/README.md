# 数字地球离线静态资源（map-assets）

本目录**不打进 jar**，由 Spring Boot 的 `/map-assets/**` 路由映射到外部目录，便于在内网替换/升级 Cesium 与 OpenLayers。

当前工作区已放入一份用于本机验收的资源：

- `cesium/`：Cesium **1.95.0** 的 `Build/Cesium/` 内容；
- `ol/`：OpenLayers **6.15.1** 的浏览器单文件包（用 esbuild 从 npm 包打出，暴露 `window.ol`）；
- `basemap/natural-earth-ii/`：Natural Earth 1:50m 栅格切出的 z0–z5 瓦片（22.5 MB，公共领域），供 2D/3D 在区域级查看更清晰的底图。

生产环境可以保留这份资源，也可以替换成内网已有的 Cesium / OpenLayers 版本。

## 目录约定

```
map-assets/
├─ README.md
├─ cesium/
│  ├─ Cesium.js
│  ├─ Widgets/
│  ├─ Workers/
│  ├─ Assets/
│  └─ ThirdParty/
├─ ol/
│  ├─ ol.js
│  └─ ol.css
└─ basemap/
   └─ natural-earth-ii/
      └─ {0..5}/{x}/{y}.jpg
```

## 从哪里拷贝

### Cesium

取 Cesium 发行包中的 `Build/Cesium/`，把该目录**里面的内容**复制到 `map-assets/cesium/`，确保
`map-assets/cesium/Cesium.js`、`map-assets/cesium/Workers/`、`map-assets/cesium/Assets/Textures/NaturalEarthII/`
都存在。

说明：

- 页面会把 `window.CESIUM_BASE_URL` 设为 `/map-assets/cesium/`，Cesium 从这里加载 Workers、Assets、Widgets 静态资源。
- 默认底图优先使用 `basemap/natural-earth-ii/`；没有该目录时回退到 Cesium 自带的
  `Assets/Textures/NaturalEarthII`（低分辨率、全球、完全离线），不访问 Cesium Ion。
- 如果内网有更高清的瓦片服务，可在 `earth/config.js` 把 `basemap.type` 改为 `xyz` 并填写 `basemap.url`。
- Cesium 版本建议 1.80+；本项目只使用 `Viewer`、`UrlTemplateImageryProvider`、`Entity` 等长期稳定 API。
  1.9x 与 1.104+ 的默认底图参数差异已在 `CesiumScene.vue` 中同时传 `baseLayer:false` 与 `imageryProvider:false` 兼容。

### OpenLayers

当前 `ol/ol.js` 是为本项目按需打出的 **OpenLayers 6.15.1** 单文件包，暴露：

`ol.Map`、`ol.View`、`ol.Feature`、`ol.Observable`、`ol.layer.Tile/Image/Vector/Graticule`、
`ol.source.Vector/XYZ/ImageStatic`、`ol.tilegrid.TileGrid`、`ol.geom.Point/LineString/Polygon`、
`ol.style.Circle/Icon/Fill/Stroke/Style/Text`、`ol.proj`。

如果内网已有官方完整构建的 `ol.js`/`ol.css`，直接覆盖即可；只要上述命名空间可用，页面不需要改。

### 高分辨率底图（可选）

`basemap/natural-earth-ii/` 的瓦片目录结构为 `{z}/{x}/{y}.jpg`，采用 **TMS**（y=0 在南）方向，
与 Cesium 的 `{reverseY}` 语义一致。当前提供 z0–z5，2D 自动适配的最大级别为 5。

`earth/config.js` 中通过 `basemap.baseUrl` 指向它：

```js
basemap: {
  type: 'natural-earth-ii',
  baseUrl: '/map-assets/basemap/natural-earth-ii/'
}
```

留空 `baseUrl` 时，3D/2D 都会回退到 `cesiumBase + 'Assets/Textures/NaturalEarthII/'`。

## 部署方式

### 方式一：外部目录（推荐）

把 `map-assets/` 放在应用工作目录下，启动时默认读取 `./map-assets`。也可以用环境变量或配置覆盖：

```bash
# Linux
export MAP_ASSETS_DIR=/data/agentchat/map-assets
java -jar agentchat-v2.jar

# 或 application.yml
app:
  map:
    assets-dir: /data/agentchat/map-assets
```

Spring Boot 会把 `/map-assets/**` 映射到该目录（`WebConfig#addResourceHandlers`）。

### 方式二：classpath 兜底

若希望随 jar 一起发布，可把资源放到 `src/main/resources/static/map-assets/`，`WebConfig` 会作为第二优先级的
`classpath:/static/map-assets/` 兜底。注意 Cesium 完整包通常有几十 MB，会显著增大 jar。

### Nginx 前置（推荐）

```nginx
location /map-assets/ {
    alias /data/agentchat/map-assets/;
    expires 7d;
    add_header Cache-Control "public";
}

# SSE 必须关闭缓冲
location /api/chat/stream {
    proxy_pass http://127.0.0.1:8092;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 600s;
}

# Cesium Worker 需要 blob:；若配置了 CSP，请放行
# add_header Content-Security-Policy "worker-src 'self' blob:; child-src 'self' blob:;";
```

## 验证

1. 启动应用，浏览器打开 `http://<host>:8092/`。
2. 点顶栏地球按钮，应打开独立“数字地球”窗口（约 1000×1000，可拖到左屏），问答页不再显示内嵌地图。
3. 也可直接访问 `http://<host>:8092/earth/index.html?demo=1` 查看内置示例标注。
4. 在聊天里问“请在地图上标出杭州、上海、南京三个城市”，独立窗口应出现 3 个标注与图例。
5. 在 3D 滚轮放大到区域级应自动切 2D；在 2D 滚轮缩小到世界级应自动切回 3D。窗口内不应再有 3D/2D 按钮。
6. 若显示“数字地球资源未加载”，检查：
   - `/map-assets/cesium/Cesium.js` 是否 200；
   - `/map-assets/ol/ol.js`、`/map-assets/ol/ol.css` 是否 200；
   - `/map-assets/basemap/natural-earth-ii/0/0/0.jpg` 是否 200；
   - `app.map.assets-dir` 是否指向正确目录；
   - 浏览器控制台是否有 404 或 CSP 报错。

## 浏览器与内网环境

- 建议 Chrome / Edge 109（Windows 7 上可用的最后一批版本）。
- 不支持 IE11（Vue 2 构建目标为 Chrome 80+，Cesium/OL 也不支持 IE）。
- 运行期不需要 Node.js；Node 只在开发机构建 `earth-ui` 时需要（建议 Node 16）。
- 完全离线：不加载 Cesium Ion、天地图或任何在线底图；底图来自本地 `basemap/natural-earth-ii`
  或 Cesium 自带 NaturalEarthII，也可配置内网 XYZ 服务。
