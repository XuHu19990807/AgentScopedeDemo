# 数字地球（Cesium 3D / OpenLayers 2D）接入说明

本文说明 AgentChat-v2 独立数字地球窗口的组成、离线部署、配置、协议与排障。实现遵循“主前端不迁移框架、子应用独立构建、SDK 外置、智能体主动上图”的约束；数字地球不再占用问答页左侧，而是独立窗口（适合双屏：左屏地球、右屏对话）。

## 1. 总体结构

```
AgentChat-v2/
├─ earth-ui/                         Vue 2.7 子应用源码（仅构建期需要 Node）
│  ├─ public/config.js              部署后可改的地图配置
│  ├─ public/demo.json              ?demo=1 的演示数据
│  └─ src/{App.vue,components,utils}
├─ src/main/resources/static/
│  ├─ earth/                        earth-ui 构建产物（提交到仓库，独立页面）
│  └─ js/map-bridge.js              问答页 ↔ 地球窗口的 BroadcastChannel 桥
├─ map-assets/                      Cesium / OpenLayers 离线 SDK（不入 jar）
│  ├─ cesium/{Cesium.js,Widgets,Workers,Assets,ThirdParty}
│  ├─ ol/{ol.js,ol.css}
│  └─ basemap/natural-earth-ii/{0..5}/{x}/{y}.jpg
└─ src/main/java/com/cetc28/
   ├─ map/{MapCommand,MapFeature,MapView,MapLegendItem,CityGazetteer,MapPrompt}
   ├─ chat/MapCommandService
   └─ tool/MapTool
```

数据流：

1. 模型判断问题涉及地点/区域/路线/分布，调用 `map_render`（或 `map_view` / `map_clear`）。
2. `MapTool` 从 `RuntimeContext.getSessionId()` 取 `conversationId`；`MapCommandService` 用本地 `cities.json` 解析城市，做限额/裁剪，把命令写入当前 `RunContext`。
3. `MapCommandService` 通过 `RunRegistry.activeFor(conversationId)` 找到当前 run，用 `SsePublisher` 推送 `map` 事件。
4. 问答页 `map-bridge.js` 通过 `BroadcastChannel` 把命令广播给 `/earth/index.html` 独立窗口；`App.vue` 调 Cesium/OL 场景渲染。没有地球窗口时只保存状态，窗口打开后通过 `hello/sync` 补齐。
5. `ChatService.finish` 把本轮 `mapCommands` 写入 `ChatMessage`，历史接口随消息返回；重新打开会话时前端回放最后一条助手消息的命令。
6. 地球窗口内滚轮放大/缩小到配置阈值时，自动在 3D 地球与 2D 地图之间切换，不再使用 3D/2D 按钮。

不依赖编排层的 attributes：worker 里的工具调用同样能通过 `conversationId` 找到主 run 并推送，因此 Multi Agent 模式也可上图。

## 2. 离线 SDK 放置

当前工作区已放入一份用于本机验收的资源：Cesium **1.95.0**（`Build/Cesium/`）、OpenLayers **6.15.1**
浏览器单文件包（用 esbuild 从 npm 包打出，暴露 `window.ol`），以及 Natural Earth 1:50m 栅格切出的
z0–z5 瓦片（`basemap/natural-earth-ii/`，22.5 MB）。生产环境可保留，也可替换成内网已有版本：

```
map-assets/
├─ cesium/          # 对应 Cesium 包 Build/Cesium/ 下的内容
│  ├─ Cesium.js
│  ├─ Widgets/
│  ├─ Workers/
│  ├─ Assets/
│  └─ ThirdParty/
├─ ol/
│  ├─ ol.js         # OpenLayers v6.15.1 单文件构建
│  └─ ol.css
└─ basemap/
   └─ natural-earth-ii/{0..5}/{x}/{y}.jpg
```

注意：

- `Cesium.js` 必须在 `map-assets/cesium/` 根下；`CESIUM_BASE_URL` 由子应用自动设为 `/map-assets/cesium/`，Cesium 据此找 `Workers/`、`Assets/`、`Widgets/`。
- 默认优先使用 `map-assets/basemap/natural-earth-ii/{z}/{x}/{y}.jpg`（TMS，y=0 在南；z0–z5）；该目录不存在时回退到 Cesium 自带的 `Assets/Textures/NaturalEarthII`（仅 z0–z2，较模糊）。
- 当前 `map-assets/ol/ol.js` 是按需打出的 OpenLayers 6.15.1 单文件包，暴露 `ol.Map/View/Feature/layer/source/tilegrid/geom/style（含 Icon/Fill/Stroke/Text）/proj`；内网若已有官方完整 `ol.js`，覆盖后只要这些命名空间存在即可。
- Cesium 1.95.0 已实测；`CesiumScene.vue` 同时传 `baseLayer:false` 与 `imageryProvider:false`，兼容 1.9x 与 1.104+ 的默认底图参数差异。
- 不要把 `map-assets` 打进 jar。生产环境通过 `app.map.assets-dir` 指向外部目录，升级 SDK 不需要重新打包。
- 如果内网只提供 `ol.js` 而没有 `ol.css`，2D 仍可用，但控件样式会缺失；建议一并提供。

Spring 静态映射（`WebConfig`）：

```yaml
app:
  map:
    assets-dir: ${MAP_ASSETS_DIR:./map-assets}
```

`/map-assets/**` 优先读外部目录，找不到时回退 `classpath:/static/map-assets/`（可用于把一份最小 SDK 随 jar 发布）。

## 3. 构建与部署

### 3.1 后端

```bash
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar target/agentchat-v2-2.1.0.jar
```

`earth/`、`map-assets/` 不需要 Maven 插件；`earth/` 作为普通静态资源打包，`map-assets/` 由外部目录提供。

### 3.2 子应用（仅修改 earth-ui 时需要）

目标内网是 Win7 + Linux，运行期不需要 Node；构建机建议 Node 16（Vue CLI 5 支持范围），高版本 Node 也可构建，但不要依赖 Node 18+ 专有 API。

```bash
cd earth-ui
npm ci
npm run build
# 输出到 ../src/main/resources/static/earth/
```

`vue.config.js` 已配置 `outputDir`、`publicPath`（生产 `./`，适配 `/earth/` 子路径）和 devServer 代理（8081 → 8092 的 `/api`、`/map-assets`）。本地联调：

```bash
# 终端 1：后端
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
# 终端 2：子应用（可选，直接看 /earth/ 页面）
cd earth-ui && npm run serve
```

生产只需要浏览器访问 `http://<host>:8092/`，无需启动 8081。数字地球可单独打开 `http://<host>:8092/earth/index.html`；问答页顶栏地球按钮会以独立窗口打开它。

### 3.3 Nginx / 反向代理

- `text/event-stream` 必须关闭缓冲，否则 `map` 事件会和正文一样被攒住：

  ```nginx
  location /api/chat/stream {
      proxy_pass http://127.0.0.1:8092;
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_buffering off;
      proxy_cache off;
      proxy_read_timeout 3600s;
  }
  ```

- `/map-assets/` 建议 `alias` 到外部 SDK 目录，并开启 gzip（Cesium.js 压缩后收益明显）。
- 如果配置了 CSP，至少允许：

  ```text
  script-src 'self' 'unsafe-eval';   # Cesium 部分版本需要 eval
  worker-src 'self' blob:;           # Cesium Worker
  img-src 'self' data: blob:;
  connect-src 'self';
  ```

  具体以实际 Cesium 版本为准；先不加 CSP 验证通过，再逐项收紧。

## 4. 前端配置（部署后可改）

`src/main/resources/static/earth/config.js` 在构建时复制，部署后可直接编辑：

```js
window.EARTH_CONFIG = {
  cesiumBase: '/map-assets/cesium/',
  olBase: '/map-assets/ol/',
  initialMode: '3d',                 // 3d | 2d
  initialView: { lon: 105, lat: 35, height: 12000000, zoom: 3 },
  basemap: {
    type: 'natural-earth-ii',          // natural-earth-ii | xyz | image | none
    baseUrl: '/map-assets/basemap/natural-earth-ii/' // 留空时回退 Cesium 自带瓦片
  },
  maxFeatures: 500,
  theme: 'dark',
  autoSwitch: {
    enabled: true,
    to2dHeight: 1500000,   // 3D 相机高度 <= 该值（米）时切 2D
    to3dZoom: 3.5,         // 2D 层级 <= 该值时切回 3D
    debounceMs: 180        // 滚轮停止后判定，避免连续滚动抖动
  }
}
```

- `basemap.type = 'xyz'` 时填 `url`（支持 `{z}/{x}/{y}`）与可选 `extent`；适合内网瓦片服务。
- `basemap.type = 'image'` 时填 `url` 与 `extent`（`[minLon,minLat,maxLon,maxLat]`），适合单张世界影像。
- `natural-earth-ii` 默认读取 `basemap.baseUrl` 的 z0–z5 离线瓦片；2D 用同一瓦片（TMS 反向 Y），不依赖在线底图。留空 `baseUrl` 时回退 Cesium 自带 z0–z2 瓦片。
- 2D 自动适配最多到 z5（`basemap.type=natural-earth-ii`）；3D 自动适配对多要素保留至少 6°×4° 的区域跨度，避免离线底图被放大得过糊。
- `coordSystem` 预留给 GCJ-02 偏移场景；当前实现按 WGS84 渲染。
- `autoSwitch`：3D 高度 ≤ `to2dHeight` 时切到 2D；2D 层级 ≤ `to3dZoom` 时切回 3D。两者之间留滞回区，避免来回抖动；`debounceMs` 控制滚轮停止后的判定间隔。设为 `enabled:false` 可关闭自动切换（此时仍可通过后端 `view.mode` 切模式）。

修改 `config.js` 后浏览器刷新即可，不需要重新构建。

## 5. 地图命令协议

SSE 事件名：`map`。`data` 即命令对象：

```json
{
  "id": "m_ab12cd34ef56",
  "protocol": "agentchat.plot/v1",
  "seq": 3,
  "type": "replace",
  "title": "东部新一线城市",
  "subtitle": "共 15 个城市",
  "view": { "mode": "3d", "fit": true },
  "features": [
    { "kind": "point", "name": "杭州", "city": "杭州",
      "style": "city.primary", "label": "杭州" },
    { "kind": "line", "name": "沪杭", "cities": ["上海", "杭州"],
      "style": "route.alert" },
    { "kind": "polygon", "name": "长三角", "coords": [[118.8,30.2],[122,30.2],[122,32.2],[118.8,32.2]],
      "style": "area" }
  ],
  "createdAt": 1760000000000
}
```

- `type`：`replace`（清空旧要素再画）、`view`（只改视角）、`clear`（清空）。
- 坐标顺序统一 `[经度, 纬度]`，WGS84；纬度范围按 Web Mercator 限制到 `[-85.05, 85.05]`。
- `style` 是**语义 id**，不是颜色：如 `city` / `city.primary` / `route` / `route.alert` / `area`；后端只校验字符格式，未知 id 由前端回落到 `point`/`line`/`polygon`。颜色、图标、线宽、虚线、图例文案全部由前端 `plot-styles.json` 决定，详见 [plot-protocol.md](plot-protocol.md)。
- `kind`：`point` / `line` / `polygon`；子应用也兼容 `polyline`、`route`、`area` 别名。
- 线要素可用 `cities: ["上海","杭州"]` 让后端按城市库解析，也可直接写 `coords`；`coords` 优先。
- 后端不再下发 `legend`；前端按实际要素的 style 生成图例。旧命令的 `legend`/旧 style 仍被兼容。
- 限额：要素 ≤ 500、单要素坐标 ≤ 2000、总坐标点 ≤ 10000、命令 ≤ 256KB、单会话保留 ≤ 20 条命令。
- `render` 成功会替换当前 run 的命令列表，因此历史只保留最终一张图；`view`/`clear` 追加。

问答页与独立窗口通过同源 `BroadcastChannel('agentchat-earth-v1')` 通信，消息格式：

```json
{ "channel": "agentchat-earth-v1", "from": "chat|earth", "chatId": "c_...", "type": "...", "payload": {}, "ts": 1760000000000 }
```

- 窗口 URL 带 `?chatId=`；手动打开没有 `chatId` 时，地球收到第一条聊天消息后绑定。
- 握手：地球打开后发 `hello`（每 2s 重试，最多 10 次）；问答页收到后回 `sync`，携带 `theme`、`conversationId`、`commands`、`title`、`count`。问答页刷新后也会用 `hello/sync` 重新对齐。
- 问答页 → 地球：`hello`、`sync`、`command`、`clear`、`theme`、`conversation`
- 地球 → 问答页：`hello`、`ready`、`rendered`、`error`、`select`、`mode`、`bye`
- `command` 的 payload 为 `{ command, commandId }`；`select` 的 payload 为要素对象（含 `name`/`label`/`city` 等）。
- 只在同一浏览器、同一 origin、同一 profile 内生效；不同浏览器 profile 或不同域名不保证同步。

## 6. 工具与提示词

| 工具 | 用途 | 关键参数 |
| --- | --- | --- |
| `map_render` | 标注地点/路线/区域并取景 | `title`、`featuresJson`，可选 `subtitle`、`viewJson` |
| `map_view` | 只调整视角/模式 | `mode`、`lon`、`lat`、`height`、`zoom`、`fit`、`fly` |
| `map_clear` | 清空标注 | 无 |

`featuresJson` 是 JSON 数组；点优先写 `city`（由 `data/cities.json` 的 125 个城市解析），线优先写 `cities`，无法用城市表达时再写 `coords`，面写 `coords`。`style` 只写语义 id，不要写颜色/图标/线宽。工具返回紧凑 JSON（`ok` / `rendered` / `skipped` / `pushed` / `message`），不回传大段坐标。

前端标绘外观集中在 `earth-ui/public/plot-styles.json` 与 `public/plot-icons/`：

- 改颜色、线宽、虚线、图例文案：只改 JSON，重新构建或直接替换部署目录文件。
- 换图标：把 SVG/PNG/JPG 放到 `plot-icons/`，在样式里写 `icon` 文件名；也可以写完整 URL、根路径或 `/plot-images/`（映射到 jar 外部的 `./plot-images`，可用 `PLOT_IMAGES_DIR` 改路径）。跨域图片需要 CORS，CSP 需放行 `img-src`。
- 新增样式：在 `styles` 加 id，并让工具/模型使用该 id；后端不需要加白名单。
- 校验：`cd earth-ui && npm run validate:styles`。

提示词：

- `prompts/chat.txt`、`prompts/deep.txt` 的“地图”节要求“地点/区域/路线先上图、坐标不编造、同一轮只上图一次、最终回答不输出 JSON”。
- `prompts/orchestrator.txt` 允许 orchestrator 直接调 `map_render`，不必派子智能体；`ChatAgent`/`DeepChatAgent` 等 worker 也注册了地图工具。
- Nacos 旧提示词兜底：`AgentBase.loadAgentPrompt` 发现 toolkit 含 `map_render` 且提示词没有 `## 地图联动` 时，追加 `MapPrompt.ADDENDUM`；因此不必先改 Nacos 也能用。

## 7. 交互行为

- 问答页不再有内嵌地图，聊天区占满宽度；顶栏地球按钮打开独立窗口（约 1000×1000，可拖到左屏），重复点击复用同一窗口。
- 回答产生 `map` 事件时实时上图；地球窗口未打开时命令保存在问答页内存中，打开后自动补齐。
- 助手消息底部“地图”按钮会先打开地球窗口，再回放该轮标注。
- 打开历史会话时回放最后一条带 `mapCommands` 的助手消息；没有则清空。
- 点击标注只把“介绍一下X”填入输入框，不自动发送。
- 主题切换通过 `theme:changed` 与 `BroadcastChannel` 同步到地球窗口。
- 地球窗口内不再有 3D/2D 按钮；顶栏显示“3D 地球/2D 地图”状态。滚轮放大到 3D 高度 ≤ `autoSwitch.to2dHeight` 自动切 2D；在 2D 滚轮缩小到层级 ≤ `autoSwitch.to3dZoom` 自动切回 3D。程序化取景（`fit`/`flyTo`）不会触发自动切换。
- 地球窗口会显示“已连接对话/独立窗口”状态；`/earth/index.html` 也可以不带聊天页单独打开查看底图。

## 8. 排障

| 现象 | 排查 |
| --- | --- |
| 地球窗口显示“数字地球资源未加载” | 检查 `/map-assets/cesium/Cesium.js`、`/map-assets/cesium/Widgets/widgets.css`、`/map-assets/ol/ol.js`、`/map-assets/ol/ol.css` 是否 200；看 Network 与 Console |
| 点顶栏地球按钮没有窗口 | 浏览器拦截弹窗；允许本站弹窗，或手动打开 `/earth/index.html`。页面会 toast 提示 |
| 地球窗口显示“独立窗口” | 问答页没打开或不同 origin/profile；`BroadcastChannel` 仅同源同 profile 生效 |
| 地球窗口不随回答变化 | 确认地球窗口与问答页同源同 profile；Console 看 `hello/sync/command`；后端日志看 `map` 事件与 `pushed=true` |
| 3D 只有网格球 | 检查 `basemap.baseUrl` 或 `Assets/Textures/NaturalEarthII/` 是否存在；看 Network 的 `{z}/{x}/{reverseY}.jpg` |
| 2D 空白/只有经纬网 | 检查 `ol.js` 是否为 v6.x 单文件构建；检查 `basemap.baseUrl` 下 `{z}/{x}/{y}.jpg` 是否 200（z0 应从 `0/0/0.jpg` 开始） |
| 滚轮不切换 3D/2D | 检查 `autoSwitch.enabled`；3D 需放大到高度 ≤1.5M，2D 需缩小到层级 ≤3.5；控制台看 `auto-mode` 是否发出 |
| 历史会话无图 | 旧消息没有 `mapCommands` 字段属正常；新消息刷新后应回放 |
| 点击标注无反应 | 确认 `select` 消息在 Console 出现；检查问答页 `onMapSelect` 是否注册 |
| SSE 正文正常但地图事件延迟 | Nginx/网关对 `text/event-stream` 开了缓冲；按 3.3 关闭 |
| Win7 打不开 | 使用 Chrome/Edge 109（最后支持 Win7 的版本）；IE11 不支持 |

## 9. 验收清单

1. 放置 SDK → 启动后端 → 打开 `/`，聊天页无内嵌地图；顶栏地球按钮打开独立 `/earth/index.html?chatId=...` 窗口。
2. 提问“东部重要的新一线城市有哪些”，独立窗口出现城市标注、标题与图例；问答页仍可继续对话。
3. 点击标注，问答页输入框出现“介绍一下杭州”。
4. 关闭地球窗口再重新打开，最后一张图自动同步；刷新问答页/重新打开会话，历史标注可回放。
5. 在 3D 滚轮放大到区域级，自动切 2D 地图；在 2D 滚轮缩小到世界级，自动切回 3D；不再有 3D/2D 按钮。
6. 程序化 `fit`/回放不触发自动切换；`autoSwitch.enabled=false` 时滚轮不切换。
7. 移除 `map-assets`，地球窗口显示中文兜底页；聊天不受影响。
8. Multi Agent 模式下由 worker 调 `map_render`，地球窗口同样收到 `map` 事件。
9. 双屏：把地球窗口拖到左屏、问答窗口留在右屏，两边独立操作。
10. `mvn test`、`mvn -DskipTests package` 通过；`earth-ui npm run build` 通过。

## 10. 已知限制

- 默认底图为 Natural Earth 1:50m 离线瓦片（z0–z5），适合“标注 + 区域关系”，仍不适合街道级底图；需要街道级时配置内网 XYZ 瓦片。
- 2D 相邻城市的标签可能重叠（已关闭整要素 declutter，保证点不被隐藏）；放大或点击可区分，暂未做标签避让。
- 未实现 GCJ-02 纠偏、3D Tiles、地形、绘制编辑、地图反查问答。
- 跨窗口同步依赖 `BroadcastChannel`，要求同一浏览器、同一 origin、同一 profile；不同 profile/域名不会同步。浏览器不支持时地球仍可独立查看，但不会自动接收新指令。
- 独立窗口由 `window.open` 打开；若被浏览器拦截，需要允许本站弹窗或手动打开 `/earth/index.html`。窗口位置由用户拖到左屏，未调用操作系统级双屏 API。
- 自动切换只响应鼠标滚轮；触屏 pinch 暂不触发。阈值可在 `config.js` 的 `autoSwitch` 调整，建议保留 1.5M/3.5 的滞回区。
- Cesium 版本跨度较大（1.9x–1.1xx）时，`Viewer` 的底图参数已做兼容（同时传 `baseLayer:false` 与 `imageryProvider:false`）；仍建议在内网固定一个版本并用验收清单回归。
- 地图命令只持久化在助手消息里，不单独建表；旧版本消息没有该字段时按空处理。
