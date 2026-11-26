# 标绘图标目录（外部）

把内网已有图片放在本目录，即可通过 `/plot-images/<文件名>` 引用，不需要打进 jar。

例如：

```json
"city.existing": {
  "kind": "point",
  "base": "city",
  "label": "已有图标",
  "icon": "/plot-images/city.png",
  "iconSize": 32
}
```

或者只改 `earth/config.js`：

```js
plot: {
  stylesUrl: './plot-styles.json',
  iconBase: '/plot-images/'
}
```

然后 `plot-styles.json` 中 `"icon": "city.png"`。

支持 PNG/JPG/WebP/GIF/SVG。SVG 建议写死 `width`/`height`（如 32），否则 Cesium billboard 可能显示异常。

部署目录可通过 `PLOT_IMAGES_DIR` 或 `app.plot.images-dir` 改到任意本地路径；该目录由 Spring 映射为 `/plot-images/**`，并设置 no-cache，替换图片后刷新即可。

图片仍然必须由浏览器通过 URL 访问；直接写 `D:\icons\city.png` 这类本地文件路径浏览器无法读取。
