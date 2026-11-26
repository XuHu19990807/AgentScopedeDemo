# 智能标绘协议与样式扩展

本文约定“后端只描述画什么，前端决定长什么样”的边界。改颜色、图标、线型时不需要改 Java。

## 1. 职责边界

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| 后端 `MapCommandService` | 解析 `featuresJson`、城市名解析、几何校验、限额、`style` 语义 id 字符校验、生成 `MapCommand` | 颜色、图标、线宽、图例文案 |
| 前端 `plot-styles.json` | 把 `style` id 解析成颜色/图标/虚线/标签样式 | 经纬度、城市解析、业务语义 |
| 前端场景 | 按解析结果绘制 Cesium Entity / OpenLayers Feature | 决定有哪些 style |

`MapCommand.protocol` 当前为 `agentchat.plot/v1`。旧命令没有该字段时按 v1 处理。

## 2. 命令结构

```json
{
  "id": "m_xxx",
  "protocol": "agentchat.plot/v1",
  "seq": 1,
  "type": "replace",
  "title": "东部新一线城市",
  "subtitle": "共 9 个城市",
  "view": { "fit": true },
  "features": [
    { "id": "f_1", "kind": "point", "name": "杭州", "city": "杭州", "style": "city.primary" },
    { "id": "f_2", "kind": "line", "name": "沪杭", "cities": ["上海", "杭州"], "style": "route" }
  ],
  "createdAt": 1700000000000
}
```

- `type`：`replace` / `view` / `clear`。
- `features[].kind`：`point` / `line` / `polygon`。
- `features[].style`：前端样式 id，不是颜色。允许 `[a-z0-9][a-z0-9._-]{0,63}`；未知 id 前端回落 `point` / `line` / `polygon`。
- 点：优先 `city`（后端城市库解析），也可 `lon`/`lat`。
- 线：`cities` 按顺序解析，或直接 `coords`；`coords` 优先。
- 面：`coords` 至少 3 点，后端自动闭合。
- 后端不再生成 `legend`；旧字段保留用于兼容，前端忽略。

## 3. 样式目录

文件：`earth-ui/public/plot-styles.json`，构建后位于 `/earth/plot-styles.json`。

```json
{
  "defaults": {
    "point": { "color": "#38bdf8", "radius": 5 },
    "line": { "color": "#22d3ee", "width": 2.5 },
    "polygon": { "color": "#22c55e", "fillAlpha": 0.18 }
  },
  "themes": {
    "dark": { "labelColor": "#e2e8f0" },
    "light": { "labelColor": "#0f172a" }
  },
  "styles": {
    "city.primary": {
      "kind": "point",
      "base": "city",
      "label": "重点城市",
      "color": "#f59e0b",
      "icon": "city-primary.svg",
      "iconSize": 30
    },
    "site.base-station": {
      "kind": "point",
      "base": "point",
      "label": "通信基站",
      "color": "#22c55e",
      "icon": "base-station.svg",
      "iconSize": 24
    },
    "target.aircraft": {
      "kind": "point",
      "base": "point",
      "label": "飞机",
      "color": "#f59e0b",
      "icon": "aircraft.svg",
      "iconSize": 28
    },
    "route.alert": {
      "kind": "line",
      "base": "route",
      "label": "警示路线",
      "color": "#ef4444",
      "dash": [10, 6]
    }
  },
  "aliases": { "primary": "city.primary" }
}
```

字段：

- `base`：继承父样式，最多 5 层；子字段覆盖父字段。
- `kind`：`point` / `line` / `polygon`。
- 点：`color`、`radius`、`strokeColor`、`strokeWidth`、`icon`、`iconSize`、`labelOffsetY`。
- 线：`color`、`width`、`dash`（如 `[10,6]`）。
- 面：`color`、`fillAlpha`、`width`。
- 标签：`labelColor`、`labelBackground`、`labelOffsetY`。
- 图例文案来自 `label`；同一 style 只出现一次。
- `aliases`：兼容旧 `primary/secondary/...`；新命令建议直接用语义 id。

图标默认放在 `earth-ui/public/plot-icons/`，此时 `icon` 只写文件名，`public/config.js` 的 `plot.iconBase` 决定前缀。

`icon` 也支持直接写图片地址：

- 完整 URL：`"icon": "https://gis.example.com/icons/city.png"`
- 根路径：`"icon": "/static/icons/city.png"`
- 协议相对：`"icon": "//gis.example.com/icons/city.png"`
- `data:` URI：适合少量内置小图标

也可以把 `plot.iconBase` 整体改成已有图片目录，例如 `/plot-images/`、`/static/icons/` 或 `https://gis.example.com/icons/`，然后样式里仍只写文件名。

如果图片已经在服务器本地目录，推荐直接放到 jar 外部的 `./plot-images/`（可用 `PLOT_IMAGES_DIR` 或 `app.plot.images-dir` 改路径），Spring 会把它映射为 `/plot-images/**`，然后：

```json
"icon": "/plot-images/city.png"
```

或设置 `plot.iconBase: '/plot-images/'` 后只写 `"icon": "city.png"`。这样不用重新打包 jar，替换图片后刷新页面即可（该路径已 no-cache）。

**跨域图片需要图片服务器允许 CORS**（否则 Cesium 纹理和 OpenLayers Icon 可能加载失败），同时 Nginx/CSP 的 `img-src` 要放行该域名；内网优先使用同源地址。

SVG 建议显式写 `width` / `height`（如 32），否则 Cesium 的 billboard 可能取不到固有尺寸；PNG/JPG 也可以直接使用。图标从坐标点向上绘制，标签会自动上移 `iconSize` 像素，避免盖住图标。

## 4. 如何新增一个样式（只改前端）

1. 在 `plot-styles.json` 的 `styles` 加一个 id，例如 `city.warning`。
2. 可选：在 `plot-icons/` 放 `warning.svg`，填写 `icon: "warning.svg"`。
3. 运行 `cd earth-ui && npm run validate:styles`。
4. `npm run build`。
5. 让模型/工具使用 `"style":"city.warning"`。后端不需要加白名单。

改颜色/线型同理：改 JSON 后重新构建，或部署后直接替换 `/earth/plot-styles.json`（服务端对该文件 `no-cache`）。图标文件也可直接替换。

## 5. 校验与构建

```bash
cd earth-ui
npm run validate:styles
npm run build
```

校验内容：JSON 可解析、`styles` 结构、`base` 存在且继承链有效、`kind` 合法、`dash` 为正数数组、`color` 为十六进制、相对图标文件存在（URL/根路径/data URI 跳过本地检查）、`aliases` 指向存在的样式。

校验失败不会阻断运行时：`loadPlotStyles` 会打印警告并回落到内置 `DEFAULT_CATALOG`。

## 6. 兼容与边界

- 旧命令的 `primary/secondary/highlight/danger` 通过 `aliases` 映射。
- 旧命令的 `legend` 被前端忽略，图例按实际要素重新生成。
- 超过 500 要素、单要素 2000 点、总 10000 点、featuresJson 64KB、命令 256KB 时后端仍会拒绝/截断。
- 3D 使用 Cesium `billboard`/`point`、`polyline`（可虚线）、`polygon`；2D 使用 OpenLayers `Icon`/`Circle`、`Stroke.lineDash`、`Fill`。
- 3D 的 `polygon.outlineWidth` 在部分 WebGL 实现上会被忽略，这是 Cesium 限制；2D 边框正常。
