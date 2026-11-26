# earth-ui — 数字地球独立窗口

AgentChat-v2 的数字地球子应用。Vue 2.7 + Vue CLI 5，构建为纯静态资源，作为**独立窗口**打开（不在问答页内嵌 iframe），适合双屏：左屏地球、右屏对话。

- 3D：Cesium（运行时从 `cesiumBase` 动态加载，不打包进 bundle）
- 2D：OpenLayers（运行时从 `olBase` 动态加载，不打包进 bundle）
- 完全离线：不依赖 CDN、在线字体或 Cesium Ion；底图使用本地 Natural Earth II 瓦片
- 无外部字体、无 `v-html`，所有界面文案为中文
- 3D / 2D 不再由按钮切换：滚轮放大到区域级自动切 2D，缩小到世界级自动切回 3D

## 环境要求

- Node.js **16**（见 `.nvmrc`）
- npm 8+
- 首次安装依赖需要网络；运行与构建产物本身完全离线

```bash
cd earth-ui
nvm use 16        # 或手动切换到 Node 16
npm install
```

## 开发

```bash
npm run serve
```

- 开发地址：`http://127.0.0.1:8081/`
- 开发服务器已代理 `/api` 与 `/map-assets` 到 `http://127.0.0.1:8092`（Spring Boot）
- 直接访问 `http://127.0.0.1:8081/?demo=1` 可加载 `public/demo.json` 中的示例城市
- 开发模式 `publicPath` 为 `/`；生产构建为 `./`

## 构建

```bash
npm run build
```

产物直接输出到：

```text
../src/main/resources/static/earth/
```

构建后目录示例：

```text
src/main/resources/static/earth/
├── index.html
├── config.js
├── demo.json
└── static/
    ├── css/
    └── js/
```

Spring Boot 启动后（默认端口 8092）通过以下地址访问：

```text
http://127.0.0.1:8092/earth/index.html
```

`WebConfig` 同时把 `/earth`、`/earth/` 302 到 `/earth/index.html`，所以也可以直接打开 `/earth`。`index.html` 中的资源使用相对路径（`publicPath: './'`），部署到任意子路径都能工作。

问答页顶栏的地球按钮会打开独立窗口，窗口 URL 带当前对话的 `chatId`：

```js
const url = new URL('/earth/index.html', window.location.origin);
url.searchParams.set('chatId', chatId);
window.open(url, 'agentchat-earth', 'popup=yes,width=1000,height=1000,left=0,top=0');
```

> 问答页与地球窗口必须同源、同一浏览器 profile，跨窗口同步依赖 `BroadcastChannel`。窗口位置由用户拖到左屏，未调用操作系统级双屏 API。

> `public/config.js` 会被原样复制到构建产物根目录。部署后可直接修改 `static/earth/config.js` 调整运行时配置，无需重新构建；重新构建会覆盖该文件。

构建命令失败时优先确认 Node 版本为 16，以及依赖是否完整安装（`node_modules` 不应提交到版本库）。

本次交付已在 Node.js v24.16.0 / npm 11.13.0 下执行 `npm install` 与 `npm run build`，构建成功；使用 Node 16（`.nvmrc`）同样受支持。

## 运行时配置

`public/config.js` 在应用脚本之前加载：

```js
window.EARTH_CONFIG = {
  cesiumBase: '/map-assets/cesium/',
  olBase: '/map-assets/ol/',
  initialMode: '3d',
  initialView: { lon: 105, lat: 35, height: 12000000, zoom: 3 },
  basemap: {
    type: 'natural-earth-ii',
    baseUrl: '/map-assets/basemap/natural-earth-ii/'
  },
  maxFeatures: 500,
  theme: 'dark',
  autoSwitch: {
    enabled: true,
    to2dHeight: 1500000,
    to3dZoom: 3.5,
    debounceMs: 180
  }
};
```

`src/utils/config.js` 会与默认配置深合并并做规范化：

| 字段 | 说明 |
| --- | --- |
| `cesiumBase` | Cesium 资源根目录，自动补齐末尾 `/` |
| `olBase` | OpenLayers 资源根目录，自动补齐末尾 `/` |
| `initialMode` | `3d` 或 `2d`；缺失或非法时用 `3d` |
| `initialView` | `{ lon, lat, height, zoom }`，用于初始视角与“复位” |
| `basemap` | 底图配置，见下 |
| `maxFeatures` | 单次 `replace` 最多渲染的要素数，默认 500 |
| `theme` | `dark` / `light` |
| `autoSwitch` | 3D/2D 自动切换阈值，见下 |

### 自动切换

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后滚轮不再切换，仍可由后端 `view.mode` 切模式 |
| `to2dHeight` | `1500000` | 3D 相机高度 ≤ 该值（米）时切 2D |
| `to3dZoom` | `3.5` | 2D 层级 ≤ 该值时切 3D |
| `debounceMs` | `180` | 滚轮停止后判定间隔 |

3D 切 2D 的目标层级按 `round(5 - log2(height / to2dHeight))` 计算并限制在 `0..5`；2D 切 3D 的高度按 `to2dHeight * 2^(5 - zoom)` 计算并限制在 `1.5e6..5e7`。两者之间留滞回区，避免来回抖动；程序化 `fit`/`flyTo` 不触发切换。

### 底图配置

`natural-earth-ii`（默认，离线推荐）：

```js
basemap: {
  type: 'natural-earth-ii',
  baseUrl: '/map-assets/basemap/natural-earth-ii/'
}
```

- 3D 使用 `${baseUrl}{z}/{x}/{reverseY}.jpg`；`baseUrl` 留空时回退 `${cesiumBase}Assets/Textures/NaturalEarthII/`
- 2D 使用同一目录的 TMS 瓦片，自定义 tile grid，反向 Y；当前瓦片提供到 z5，2D 自动适配最多到 z5
- 瓦片加载失败时移除底图并保留经纬网（3D 改为 `GridImageryProvider`）
- 不请求 Cesium Ion
- 多城市自动适配在 3D 中至少保留 6°×4° 的区域跨度，避免低分辨率底图被放得过大

`xyz`：

```js
basemap: {
  type: 'xyz',
  url: 'https://example.com/tiles/{z}/{x}/{y}.png', // 建议本地离线瓦片
  projection: 'EPSG:3857' // 或 'EPSG:4326'
}
```

`image`（单张图片贴图）：

```js
basemap: {
  type: 'image',
  url: '/map-assets/basemap/china.png',
  extent: [73, 3, 136, 54] // [west, south, east, north]，EPSG:4326
}
```

`none`：不加载底图；2D 仍显示经纬网。

### 静态资源目录

应用本身不包含 Cesium / OpenLayers 发行文件，需要由服务端提供：

```text
map-assets/
├── cesium/
│   ├── Cesium.js
│   ├── Widgets/widgets.css
│   ├── Workers/...
│   ├── ThirdParty/...
│   └── Assets/
├── ol/
│   ├── ol.js
│   └── ol.css
└── basemap/
    └── natural-earth-ii/{0..5}/{x}/{y}.jpg
```

当前工作区已放入 Cesium **1.95.0**（`Build/Cesium/` 内容）与 OpenLayers **6.15.1** 单文件包，以及 Natural Earth 1:50m 切出的 z0–z5 瓦片。Cesium 的 `Widgets`、`Workers`、`Assets` 目录需与 `Cesium.js` 同级放置，`window.CESIUM_BASE_URL` 会自动指向 `cesiumBase`。Natural Earth II 瓦片为公有领域数据，无需 Ion token。

`CesiumScene.vue` 同时传 `baseLayer:false` 与 `imageryProvider:false`，兼容 1.9x 与 1.104+ 的默认底图参数；生产环境仍建议在内网固定一个 Cesium 版本并回归。

## 与问答页通信

独立窗口与问答页通过同源 `BroadcastChannel('agentchat-earth-v1')` 通信，消息格式：

```json
{
  "channel": "agentchat-earth-v1",
  "from": "chat|earth",
  "chatId": "c_...",
  "type": "...",
  "payload": {},
  "ts": 1760000000000
}
```

- 窗口 URL 带 `?chatId=`；手动打开没有 `chatId` 时，地球收到第一条聊天消息后绑定。
- 握手：地球打开后发 `hello`（每 2s 重试，最多 10 次）；问答页收到后回 `sync`，携带 `theme`、`conversationId`、`commands`、`title`、`count`。问答页刷新后也会用 `hello/sync` 重新对齐。
- 问答页 → 地球：`hello`、`sync`、`command`、`clear`、`theme`、`conversation`
- 地球 → 问答页：`hello`、`ready`、`rendered`、`error`、`select`、`mode`、`bye`
- `command` 的 payload 为 `{ command, commandId }`；`select` 的 payload 为要素对象（含 `name`/`label`/`city` 等）。
- 只在同一浏览器、同一 origin、同一 profile 内生效；不同浏览器 profile 或不同域名不保证同步。浏览器不支持 `BroadcastChannel` 时地球仍可独立查看，但不会自动接收新指令。

### 问答页 → 地球

| type | payload | 行为 |
| --- | --- | --- |
| `sync` | `{ theme?, conversationId?, commands?, title?, count? }` | 全量对齐；有 commands 时先清空再按序回放 |
| `command` | `{ commandId, command }` | 执行 `replace` / `view` / `clear`，完成后回 `rendered` |
| `clear` | `{ commandId? }` | 清空要素与图例，回 `rendered` |
| `theme` | `'dark' \| 'light'` 或 `{ theme }` | 切换主题 |
| `conversation` | `{ conversationId }` 或 ID | 只记录，不清空 |

命令格式：

```js
// 替换全部要素
{
  type: 'replace',
  protocol: 'agentchat.plot/v1',
  title: '长三角城市群',
  features: [
    {
      id: 'hz',
      name: '杭州',
      kind: 'point',          // point | line | polygon
      city: '杭州',
      lon: 120.1551,
      lat: 30.2741,
      style: 'city.primary'   // 语义 id，外观由 plot-styles.json 决定
    },
    {
      id: 'route',
      kind: 'line',
      name: '沪杭',
      cities: ['上海', '杭州'],
      style: 'route.alert'
    }
  ],
  view: { fit: true }
}

// 只调整视角
{ type: 'view', view: { lon: 120.15, lat: 30.27, height: 800000, fly: true } }
{ type: 'view', view: { fit: true, fly: false } }

// 清空
{ type: 'clear' }
```

要素字段：

- 点：`lon` / `lat`，或 `coordinates: [lon, lat]`；也可写 `city` 由后端解析
- 线：`coords: [[lon, lat], ...]`（`coordinates` 亦可），或 `cities: ['上海','杭州']`
- 面：`coords: [[lon, lat], ...]` 或 `[[[lon, lat], ...]]`；渲染时自动闭合
- `id` 会转为字符串；重复 ID 在渲染时自动加后缀
- `style` 是语义样式 id（如 `city` / `city.primary` / `route` / `route.alert` / `area`），**不控制颜色**；颜色/图标/线宽/虚线/图例文案由 `public/plot-styles.json` 解析，未知 id 回落 `point`/`line`/`polygon`
- 后端不再生成 `legend`；图例由前端按实际 style 自动生成。旧命令的 `legend` 被忽略，旧 `primary/secondary/...` 通过 aliases 兼容
- 一次 `replace` 超过 `maxFeatures` 时只渲染前 `maxFeatures` 个，并在控制台警告

### 标绘样式扩展

- 样式文件：`public/plot-styles.json`；图标：`public/plot-icons/`
- 改颜色/线宽/虚线/图例文案：只改 JSON
- 换图标：把 SVG 放到 `plot-icons/`，样式里写 `icon: 'xxx.svg'`；也可以写完整图片地址（`https://...`、`/static/icons/city.png`、`/plot-images/city.png`、`data:`），或把 `plot.iconBase` 指向已有图片目录。`/plot-images/` 映射到 jar 外部的 `./plot-images`，可用 `PLOT_IMAGES_DIR` 改路径。跨域图片需要图片服务器允许 CORS
- SVG 需写 `width`/`height`（如 32），否则 Cesium billboard 可能显示异常；PNG/JPG 可直接使用
- 新增样式：在 `styles` 里加 id，并让后端/模型使用该 id；后端不加白名单
- 校验：`npm run validate:styles`；详细字段见 `../docs/plot-protocol.md`

`view` 支持：

- `fit: true`：按当前要素范围自适应；单点使用固定高度/缩放
- `lon` + `lat`：飞到该点；3D 可带 `height`（默认 1500000），2D 可带 `zoom`
- `zoom`：2D 缩放级别
- `mode`：`3d` / `2d`；后端显式切模式时使用（滚轮自动切换不走这个字段）
- `fly: false`：关闭飞行动画（3D duration 0；2D 立即跳转）
- 无有效参数：回到 `initialView`

### 地球 → 问答页

| type | payload | 时机 |
| --- | --- | --- |
| `hello` | `{ mode, capabilities }` | 启动/重连时 |
| `ready` | `{ mode, capabilities: { cesium, ol } }` | 初始化完成、场景挂载后 |
| `rendered` | `{ commandId, mode, count }` | 命令执行并应用视角后 |
| `select` | `{ id, name, kind, city }` | 用户点击要素 |
| `error` | `{ message, fatal }` | 命令失败（非致命）或双引擎不可用（致命） |
| `mode` | `'3d' \| '2d'` | 滚轮自动切换或后端命令切换后 |
| `bye` | 无 | 窗口关闭前 |

问答页的桥接实现见 `src/main/resources/static/js/map-bridge.js`；地球侧实现见 `src/utils/bridge.js`。

## 3D / 2D 回退

- Cesium 加载失败、OpenLayers 可用：只启用 2D，`ready.mode` 为 `2d`
- OpenLayers 加载失败、Cesium 可用：只启用 3D
- 3D 场景初始化异常且 OL 可用：自动切到 2D，并提示“3D 初始化失败，已切换 2D”
- 2D 初始化异常：显示错误页并回传 `error.fatal = true`
- 两个引擎都失败：致命错误页，列出需要检查的资源路径

工具栏只显示当前模式（`3D 地球` / `2D 地图`）与“复位”，不提供 3D/2D 按钮；不可用的引擎不会被切换到。

## 目录结构

```text
earth-ui/
├── package.json
├── vue.config.js
├── babel.config.js
├── .nvmrc
├── .gitignore
├── README.md
├── public/
│   ├── index.html
│   ├── config.js
│   └── demo.json
└── src/
    ├── main.js
    ├── App.vue
    ├── styles.css
    ├── components/
    │   ├── CesiumScene.vue
    │   └── OlScene.vue
    └── utils/
        ├── config.js
        ├── vendors.js
        └── bridge.js
```

## 主题

根元素使用 `theme-dark` / `theme-light` 类。Cesium 的 `globe.baseColor`、要素颜色、OL 经纬网与标注会随主题重渲染；切换通过问答页 `theme` 消息或 `sync.theme` 完成。

## 常见问题

**页面显示“数字地球加载失败”**

检查 `config.js` 中的 `cesiumBase` / `olBase`，以及错误页列出的 `Cesium.js`、`widgets.css`、`ol.js`、`ol.css` 是否返回 200。生产环境这些路径由 Spring Boot 的静态资源目录提供。

**只显示 2D 或只显示 3D**

查看浏览器控制台 `[earth]` 日志。某个引擎脚本 404 或超时会触发降级，另一个引擎仍可用。

**3D 底图是网格 / 2D 底图消失**

Natural Earth II 瓦片缺失或路径不对。确认 `basemap.baseUrl`（默认 `/map-assets/basemap/natural-earth-ii/`）下 `0/0/0.jpg` 等文件存在；若留空 `baseUrl`，则确认 `${cesiumBase}Assets/Textures/NaturalEarthII/0/0/0.jpg`。3D 会自动改用 `GridImageryProvider`，2D 会移除底图并保留经纬网。

**`xyz` / `image` 底图空白**

检查 URL、投影、`extent` 以及跨域 CORS。离线部署时优先把瓦片放到 `/map-assets/` 下，避免外部请求。

**地球窗口显示“独立窗口”，收不到问答页指令**

确认问答页与地球窗口同源、同一浏览器 profile；手动打开时 URL 是否带 `?chatId=`。问答页刷新后会通过 `hello/sync` 重新对齐，若仍未连接，关闭地球窗口后从问答页顶栏重新打开。

**点顶栏按钮没有窗口**

浏览器拦截了 `window.open`。允许本站弹窗，或手动打开 `/earth/index.html`；页面会 toast 提示。

**滚轮不切换 3D/2D**

检查 `autoSwitch.enabled`；3D 需放大到高度 ≤ `to2dHeight`，2D 需缩小到层级 ≤ `to3dZoom`。程序化 `fit`/`flyTo` 不触发自动切换。

## 许可

- 应用代码：随 AgentChat-v2 项目使用
- Cesium：Apache-2.0
- OpenLayers：BSD-2-Clause
- Natural Earth II 影像：公有领域
