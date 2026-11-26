<template>
  <div ref="root" class="scene-view cesium-scene"></div>
</template>

<script>
import { loadConfig } from '../utils/config'
import { resolveFeatureStyle } from '../plot/style-catalog'

function finiteNumber (value) {
  return typeof value === 'number' && Number.isFinite(value)
}

function validLonLat (lon, lat) {
  return finiteNumber(lon) && finiteNumber(lat) &&
    lon >= -180 && lon <= 180 &&
    lat >= -90 && lat <= 90
}

function colorOf (Cesium, value, fallback) {
  try {
    const color = Cesium.Color.fromCssColorString(value || fallback)
    if (color) return color
  } catch (error) { /* 样式表颜色写错时保持地图可用 */ }
  return Cesium.Color.fromCssColorString(fallback)
}

function coordinatePair (coord) {
  if (Array.isArray(coord)) {
    return [Number(coord[0]), Number(coord[1])]
  }
  if (coord && typeof coord === 'object') {
    const lon = coord.lon !== undefined ? coord.lon : coord.lng
    return [Number(lon), Number(coord.lat)]
  }
  return [NaN, NaN]
}

function pairsFrom (coords) {
  if (!Array.isArray(coords) || !coords.length) return []

  // 兼容 [lon, lat, lon, lat] 扁平数组。
  if (typeof coords[0] === 'number') {
    const pairs = []
    for (let i = 0; i + 1 < coords.length; i += 2) {
      const pair = [Number(coords[i]), Number(coords[i + 1])]
      if (validLonLat(pair[0], pair[1])) pairs.push(pair)
    }
    return pairs
  }

  return coords
    .map(coordinatePair)
    .filter(function (pair) { return validLonLat(pair[0], pair[1]) })
}

function pointOf (feature) {
  let lon = feature.lon === null || feature.lon === undefined ? NaN : Number(feature.lon)
  let lat = feature.lat === null || feature.lat === undefined ? NaN : Number(feature.lat)

  if (!validLonLat(lon, lat)) {
    const raw = feature.coordinates !== undefined ? feature.coordinates : feature.coords
    if (Array.isArray(raw) && raw.length) {
      if (Array.isArray(raw[0])) {
        lon = Number(raw[0][0])
        lat = Number(raw[0][1])
      } else {
        lon = Number(raw[0])
        lat = Number(raw[1])
      }
    } else if (raw && typeof raw === 'object') {
      lon = Number(raw.lon !== undefined ? raw.lon : raw.lng)
      lat = Number(raw.lat)
    }
  }

  return validLonLat(lon, lat) ? [lon, lat] : null
}

function polygonRings (feature) {
  const raw = feature.coords !== undefined ? feature.coords : feature.coordinates
  if (!Array.isArray(raw) || !raw.length) return []

  const nested = Array.isArray(raw[0]) && Array.isArray(raw[0][0])
  const rings = nested ? raw.map(pairsFrom) : [pairsFrom(raw)]
  return rings.filter(function (ring) { return ring.length >= 3 })
}

function normalizeKind (feature) {
  const raw = String(feature.kind || '').toLowerCase()
  if (raw === 'point') return 'point'
  if (raw === 'label') return 'label'
  if (raw === 'polyline' || raw === 'line' || raw === 'route') return 'polyline'
  if (raw === 'polygon' || raw === 'area') return 'polygon'

  if (pointOf(feature)) return 'point'
  const rawCoords = feature.coords !== undefined ? feature.coords : feature.coordinates
  if (Array.isArray(rawCoords)) {
    if (Array.isArray(rawCoords[0]) && Array.isArray(rawCoords[0][0])) return 'polygon'
    return rawCoords.length >= 3 ? 'polygon' : 'polyline'
  }
  return 'point'
}

export default {
  name: 'CesiumScene',
  props: {
    features: { type: Array, default: function () { return [] } },
    view: { type: Object, default: null },
    renderToken: { type: Number, default: 0 },
    selectedId: { type: [String, Number], default: null },
    theme: { type: String, default: 'dark' }
  },
  data () {
    return {
      config: loadConfig(),
      viewer: null,
      handler: null,
      resizeObserver: null,
      windowResizeHandler: null,
      resizeTimer: null,
      sizeTimer: null,
      renderErrorHandler: null,
      creditContainer: null,
      gridLayer: null,
      destroyed: false,
      errorEmitted: false,
      wheelHandler: null,
      wheelTimer: null,
      wheelDelta: 0,
      autoSwitchAt: 0
    }
  },
  watch: {
    renderToken () {
      this.renderFeatures()
    },
    theme () {
      this.applyTheme()
      this.renderFeatures()
    }
  },
  mounted () {
    try {
      this.init()
    } catch (error) {
      this.emitError(error)
    }
  },
  beforeDestroy () {
    this.destroyed = true

    if (this.resizeObserver) {
      try { this.resizeObserver.disconnect() } catch (error) { /* ignore */ }
      this.resizeObserver = null
    }
    if (this.windowResizeHandler) {
      window.removeEventListener('resize', this.windowResizeHandler)
      this.windowResizeHandler = null
    }
    if (this.resizeTimer) {
      clearTimeout(this.resizeTimer)
      this.resizeTimer = null
    }
    if (this.sizeTimer) {
      clearInterval(this.sizeTimer)
      this.sizeTimer = null
    }
    if (this.wheelTimer) {
      clearTimeout(this.wheelTimer)
      this.wheelTimer = null
    }
    if (this.wheelHandler && this.viewer && this.viewer.canvas) {
      try { this.viewer.canvas.removeEventListener('wheel', this.wheelHandler) } catch (error) { /* ignore */ }
      this.wheelHandler = null
    }
    if (this.handler) {
      try { this.handler.destroy() } catch (error) { /* ignore */ }
      this.handler = null
    }
    if (this.viewer && !this.viewer.isDestroyed()) {
      try { this.viewer.destroy() } catch (error) { /* ignore */ }
    }
    this.viewer = null
    this.creditContainer = null
  },
  methods: {
    init () {
      const Cesium = window.Cesium
      if (!Cesium) throw new Error('Cesium 未加载')

      this.Cesium = Cesium

      // 将 Cesium 版权容器放到一个隐藏节点中，满足离线界面的干净外观。
      this.creditContainer = document.createElement('div')
      this.creditContainer.className = 'earth-credit-hidden'
      this.creditContainer.style.display = 'none'
      this.$el.appendChild(this.creditContainer)

      this.viewer = new Cesium.Viewer(this.$el, {
        // 1.104+ 用 baseLayer:false 关闭默认底图；旧版本（如 1.9x）认 imageryProvider:false。
        // 同时传两个，未识别的选项会被 Cesium 忽略，避免离线环境请求 Ion/Bing 底图。
        baseLayer: false,
        imageryProvider: false,
        terrainProvider: new Cesium.EllipsoidTerrainProvider(),
        animation: false,
        timeline: false,
        baseLayerPicker: false,
        geocoder: false,
        homeButton: false,
        sceneModePicker: false,
        navigationHelpButton: false,
        fullscreenButton: false,
        infoBox: false,
        selectionIndicator: false,
        creditContainer: this.creditContainer
      })

      this.applyTheme()
      this.setupBaseMap()
      this.applyInitialView(false)
      this.renderFeatures()
      this.bindPick()
      this.bindResize()
      this.bindAutoSwitch()
      this.$nextTick(() => this.resize())
      this.resizeTimer = setTimeout(() => {
        this.resizeTimer = null
        this.resize()
      }, 180)
      this.sizeTimer = setInterval(() => this.checkSize(), 700)

      if (this.viewer.scene && this.viewer.scene.renderError) {
        this.renderErrorHandler = (scene, error) => {
          this.emitError(error || new Error('Cesium 渲染异常'))
        }
        this.viewer.scene.renderError.addEventListener(this.renderErrorHandler)
      }
    },

    setupBaseMap () {
      const Cesium = this.Cesium
      const viewer = this.viewer
      if (!viewer || viewer.isDestroyed()) return

      viewer.imageryLayers.removeAll()

      const basemap = this.config.basemap || { type: 'none' }
      const type = String(basemap.type || 'none').toLowerCase()

      if (type === 'none') return

      try {
        if (type === 'natural-earth-ii') {
          const hasCustom = !!basemap.baseUrl
          const base = String(
            basemap.baseUrl ||
            (this.config.cesiumBase + 'Assets/Textures/NaturalEarthII/')
          )
          const provider = new Cesium.UrlTemplateImageryProvider({
            url: base + '{z}/{x}/{reverseY}.jpg',
            tilingScheme: new Cesium.GeographicTilingScheme(),
            minimumLevel: 0,
            maximumLevel: hasCustom ? 5 : 2,
            credit: 'Natural Earth II'
          })
          const layer = viewer.imageryLayers.addImageryProvider(provider)
          let failed = false

          provider.errorEvent.addEventListener(() => {
            if (failed || this.destroyed) return
            failed = true
            // 延迟到事件派发之后修改图层集合，避免重入。
            window.setTimeout(() => {
              if (this.destroyed || !this.viewer || this.viewer.isDestroyed()) return
              try { this.viewer.imageryLayers.remove(layer, true) } catch (error) { /* ignore */ }
              console.warn('[earth] Natural Earth II 底图瓦片加载失败，已切换为网格底图。')
              this.addGridLayer()
            }, 0)
          })
        } else if (type === 'xyz') {
          const url = String(basemap.url || '')
          if (!url) throw new Error('basemap.type=xyz 但未配置 basemap.url')
          const projection = String(basemap.projection || 'EPSG:3857').toUpperCase()
          const options = {
            url: url,
            tilingScheme: projection === 'EPSG:4326'
              ? new Cesium.GeographicTilingScheme()
              : new Cesium.WebMercatorTilingScheme()
          }
          if (Number.isFinite(Number(basemap.minimumLevel))) options.minimumLevel = Number(basemap.minimumLevel)
          if (Number.isFinite(Number(basemap.maximumLevel))) options.maximumLevel = Number(basemap.maximumLevel)
          if (basemap.credit) options.credit = String(basemap.credit)
          viewer.imageryLayers.addImageryProvider(new Cesium.UrlTemplateImageryProvider(options))
        } else if (type === 'image') {
          const url = String(basemap.url || '')
          if (!url) throw new Error('basemap.type=image 但未配置 basemap.url')
          const extent = Array.isArray(basemap.extent) ? basemap.extent.map(Number) : null
          if (!extent || extent.length !== 4 || !extent.every(Number.isFinite)) {
            throw new Error('basemap.extent 需要 [west, south, east, north] 四个数字')
          }
          if (extent[0] >= extent[2] || extent[1] >= extent[3]) {
            throw new Error('basemap.extent 的范围不合法')
          }
          viewer.imageryLayers.addImageryProvider(new Cesium.SingleTileImageryProvider({
            url: url,
            rectangle: Cesium.Rectangle.fromDegrees(extent[0], extent[1], extent[2], extent[3]),
            credit: basemap.credit ? String(basemap.credit) : undefined
          }))
        } else {
          console.warn('[earth] 未知的底图类型：' + type)
        }
      } catch (error) {
        console.warn('[earth] 底图初始化失败，使用网格底图：', error)
        this.addGridLayer()
      }
    },

    addGridLayer () {
      if (!this.viewer || this.viewer.isDestroyed() || this.gridLayer) return
      try {
        const dark = this.theme !== 'light'
        const provider = new this.Cesium.GridImageryProvider({
          cells: 8,
          color: this.Cesium.Color.fromCssColorString(dark ? '#475569' : '#94a3b8').withAlpha(0.55),
          glowColor: this.Cesium.Color.fromCssColorString(dark ? '#1e293b' : '#cbd5e1').withAlpha(0.25),
          backgroundColor: this.Cesium.Color.TRANSPARENT,
          tileWidth: 256,
          tileHeight: 256
        })
        this.gridLayer = this.viewer.imageryLayers.addImageryProvider(provider)
      } catch (error) {
        console.warn('[earth] 网格底图创建失败：', error)
      }
    },

    themeColors () {
      const dark = this.theme !== 'light'
      return {
        globe: dark ? '#0b1220' : '#dbeafe',
        background: dark ? '#050b16' : '#e8f1fb',
        labelFill: dark ? '#e2e8f0' : '#1e293b',
        labelOutline: dark ? '#0b1220' : '#ffffff',
        labelBackground: dark ? 'rgba(15, 23, 42, 0.74)' : 'rgba(255, 255, 255, 0.86)',
        pointOutline: dark ? '#0b1220' : '#ffffff'
      }
    },

    applyTheme () {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const Cesium = this.Cesium
      const colors = this.themeColors()
      try {
        this.viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString(colors.globe)
        this.viewer.scene.backgroundColor = Cesium.Color.fromCssColorString(colors.background)
        this.viewer.scene.globe.enableLighting = false
      } catch (error) {
        console.warn('[earth] 应用主题失败：', error)
      }
      if (this.viewer.cesiumWidget && this.viewer.cesiumWidget.creditContainer) {
        this.viewer.cesiumWidget.creditContainer.style.display = 'none'
      }
    },

    applyInitialView (animate) {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const iv = this.config.initialView
      if (!iv) return
      const destination = this.Cesium.Cartesian3.fromDegrees(iv.lon, iv.lat, iv.height)
      try {
        if (animate === false) {
          this.viewer.camera.setView({ destination: destination })
        } else {
          this.viewer.camera.flyTo({ destination: destination, duration: 1.0 })
        }
      } catch (error) {
        console.warn('[earth] 初始视角设置失败：', error)
      }
    },

    renderFeatures () {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const list = Array.isArray(this.features) ? this.features : []
      this.viewer.entities.removeAll()

      const usedIds = Object.create(null)
      for (let index = 0; index < list.length; index++) {
        const feature = list[index]
        if (!feature || typeof feature !== 'object') continue

        let id = feature.id !== undefined && feature.id !== null
          ? String(feature.id)
          : 'feature-' + (index + 1)
        let uniqueId = id
        let suffix = 1
        while (usedIds[uniqueId]) {
          suffix += 1
          uniqueId = id + '-' + suffix
        }
        usedIds[uniqueId] = true

        try {
          const entity = this.buildEntity(feature, uniqueId)
          if (entity) this.viewer.entities.add(entity)
        } catch (error) {
          console.warn('[earth] 要素渲染失败：' + uniqueId, error)
        }
      }
    },

    buildEntity (feature, entityId) {
      const Cesium = this.Cesium
      const kind = normalizeKind(feature)
      const style = resolveFeatureStyle(feature, this.theme)
      const selected = this.selectedId !== null && this.selectedId !== undefined &&
        String(this.selectedId) === String(entityId)
      const color = colorOf(Cesium, style.color, '#38bdf8')
      const selectedColor = colorOf(Cesium, style.selectedColor, '#fbbf24')
      const labelColor = colorOf(Cesium, style.labelColor, '#e2e8f0')
      const labelBackground = colorOf(Cesium, style.labelBackground, 'rgba(15,23,42,.78)')
      const name = typeof feature.name === 'string' && feature.name
        ? feature.name
        : (typeof feature.city === 'string' ? feature.city : '')

      const properties = new Cesium.PropertyBag({
        kind: kind,
        city: typeof feature.city === 'string' ? feature.city : '',
        style: style.id
      })

      if (kind === 'point' || kind === 'label') {
        const point = pointOf(feature)
        if (!point) return null

        const entity = {
          id: entityId,
          name: name || entityId,
          position: Cesium.Cartesian3.fromDegrees(point[0], point[1], 0),
          properties: properties
        }

        if (kind === 'point') {
          if (style.iconUrl) {
            const size = Number(style.iconSize) || 26
            entity.billboard = {
              image: style.iconUrl,
              width: size,
              height: size,
              verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
              disableDepthTestDistance: Number.POSITIVE_INFINITY
            }
          } else {
            entity.point = {
              pixelSize: (Number(style.radius) || 5) * 2 + (selected ? 4 : 0),
              color: selected ? selectedColor : color,
              outlineColor: colorOf(Cesium, style.strokeColor, '#0b1220'),
              outlineWidth: Number(style.strokeWidth) || 1.5,
              disableDepthTestDistance: Number.POSITIVE_INFINITY
            }
          }
        }

        if (name) {
          const labelOnly = kind === 'label'
          entity.label = {
            text: name,
            font: (labelOnly ? '15px ' : '13px ') +
              '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", sans-serif',
            fillColor: labelColor,
            outlineColor: colorOf(Cesium, '#0b1220', '#0b1220'),
            outlineWidth: 2,
            style: Cesium.LabelStyle.FILL_AND_OUTLINE,
            showBackground: true,
            backgroundColor: labelBackground,
            backgroundPadding: new Cesium.Cartesian2(8, 4),
            pixelOffset: new Cesium.Cartesian2(0, labelOnly ? 0 : (Number(style.labelOffsetY) || -16)),
            verticalOrigin: labelOnly ? Cesium.VerticalOrigin.CENTER : Cesium.VerticalOrigin.BOTTOM,
            horizontalOrigin: Cesium.HorizontalOrigin.CENTER,
            disableDepthTestDistance: Number.POSITIVE_INFINITY
          }
        }

        return entity
      }

      if (kind === 'polyline') {
        const raw = feature.coords !== undefined ? feature.coords : feature.coordinates
        const pairs = pairsFrom(raw)
        if (pairs.length < 2) return null

        const flat = []
        pairs.forEach(function (pair) { flat.push(pair[0], pair[1]) })
        const lineColor = selected ? selectedColor : color
        const material = Array.isArray(style.dash) && style.dash.length >= 2
          ? new Cesium.PolylineDashMaterialProperty({
            color: lineColor,
            dashLength: Math.max(4, Number(style.dash[0]) + Number(style.dash[1]))
          })
          : new Cesium.ColorMaterialProperty(lineColor)

        return {
          id: entityId,
          name: name || entityId,
          properties: properties,
          polyline: {
            positions: Cesium.Cartesian3.fromDegreesArray(flat),
            width: (Number(style.width) || 2.5) + (selected ? 1.5 : 0),
            material: material,
            arcType: Cesium.ArcType.GEODESIC
          }
        }
      }

      if (kind === 'polygon') {
        const rings = polygonRings(feature)
        if (!rings.length) return null

        const toFlat = function (ring) {
          const flat = []
          ring.forEach(function (pair) { flat.push(pair[0], pair[1]) })
          return flat
        }

        const outer = Cesium.Cartesian3.fromDegreesArray(toFlat(rings[0]))
        const holes = rings.slice(1).map(function (ring) {
          return new Cesium.PolygonHierarchy(Cesium.Cartesian3.fromDegreesArray(toFlat(ring)))
        })
        const hierarchy = new Cesium.PolygonHierarchy(outer, holes)
        const alpha = Number(style.fillAlpha)

        return {
          id: entityId,
          name: name || entityId,
          properties: properties,
          polygon: {
            hierarchy: hierarchy,
            material: color.withAlpha(Number.isFinite(alpha) ? alpha : 0.18),
            outline: true,
            outlineColor: selected ? selectedColor : color,
            outlineWidth: Number(style.width) || 2
          }
        }
      }

      return null
    },

    bindPick () {
      const Cesium = this.Cesium
      const viewer = this.viewer
      if (!viewer || viewer.isDestroyed()) return

      this.handler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas)
      this.handler.setInputAction((movement) => {
        try {
          const picked = viewer.scene.pick(movement.position)
          if (!picked || !picked.id || typeof picked.id !== 'object') return

          const entity = picked.id
          if (!entity.id) return

          const properties = entity.properties
          const read = (key) => {
            if (!properties) return ''
            const value = properties[key]
            if (value && typeof value.getValue === 'function') {
              return value.getValue(Cesium.JulianDate.now())
            }
            return value
          }

          this.$emit('select', {
            id: String(entity.id),
            name: entity.name || '',
            kind: read('kind') || '',
            city: read('city') || ''
          })
        } catch (error) {
          console.warn('[earth] 拾取要素失败：', error)
        }
      }, Cesium.ScreenSpaceEventType.LEFT_CLICK)
    },

    bindResize () {
      const viewer = this.viewer
      if (!viewer) return

      if (typeof ResizeObserver !== 'undefined') {
        this.resizeObserver = new ResizeObserver(() => this.resize())
        this.resizeObserver.observe(this.$el)
      }

      this.windowResizeHandler = () => this.resize()
      window.addEventListener('resize', this.windowResizeHandler)
    },

    resize () {
      if (!this.viewer || this.viewer.isDestroyed()) return
      try { this.viewer.resize() } catch (error) { /* ignore */ }
    },

    checkSize () {
      if (!this.viewer || this.viewer.isDestroyed() || this.destroyed) return
      const canvas = this.viewer.canvas
      const el = this.$el
      if (!canvas || !el) return
      if (Math.abs(canvas.clientWidth - el.clientWidth) > 1 ||
          Math.abs(canvas.clientHeight - el.clientHeight) > 1) {
        this.resize()
      }
    },

    bindAutoSwitch () {
      const config = this.config.autoSwitch || {}
      if (!config.enabled) return
      const viewer = this.viewer
      if (!viewer || !viewer.canvas) return

      const debounce = Number(config.debounceMs) > 0 ? Number(config.debounceMs) : 180
      this.wheelHandler = (event) => {
        this.wheelDelta = Number(event.deltaY) || 0
        if (this.wheelTimer) clearTimeout(this.wheelTimer)
        this.wheelTimer = setTimeout(() => {
          this.wheelTimer = null
          this.checkAutoSwitch()
        }, debounce)
      }
      viewer.canvas.addEventListener('wheel', this.wheelHandler, { passive: true })
    },

    checkAutoSwitch () {
      const config = this.config.autoSwitch || {}
      if (!config.enabled || this.destroyed || !this.viewer || this.viewer.isDestroyed()) return
      if (this.wheelDelta >= 0) return

      const now = Date.now()
      if (now - this.autoSwitchAt < 1000) return

      let height
      try {
        height = this.viewer.camera.positionCartographic.height
      } catch (error) {
        return
      }
      const threshold = Number(config.to2dHeight) > 0 ? Number(config.to2dHeight) : 1500000
      if (!Number.isFinite(height) || height > threshold) return

      let lon = 0
      let lat = 0
      try {
        const carto = this.viewer.camera.positionCartographic
        lon = this.Cesium.Math.toDegrees(carto.longitude)
        lat = this.Cesium.Math.toDegrees(carto.latitude)
      } catch (error) {
        return
      }
      lon = Math.max(-180, Math.min(180, lon))
      lat = Math.max(-85, Math.min(85, lat))

      const hasCustomBase = !!(this.config.basemap && this.config.basemap.baseUrl)
      const maxZoom = hasCustomBase ? 5 : 2
      const zoom = Math.max(0, Math.min(maxZoom, Math.round(5 - Math.log2(height / threshold))))

      this.autoSwitchAt = now
      this.$emit('auto-mode', { mode: '2d', lon: lon, lat: lat, zoom: zoom })
    },

    computeBbox () {
      const points = []

      function push (pair) {
        if (pair && validLonLat(pair[0], pair[1])) points.push(pair)
      }

      const list = Array.isArray(this.features) ? this.features : []
      list.forEach(function (feature) {
        if (!feature || typeof feature !== 'object') return
        const kind = normalizeKind(feature)

        if (kind === 'point' || kind === 'label') {
          push(pointOf(feature))
        } else if (kind === 'polyline') {
          const raw = feature.coords !== undefined ? feature.coords : feature.coordinates
          pairsFrom(raw).forEach(push)
        } else if (kind === 'polygon') {
          polygonRings(feature).forEach(function (ring) {
            ring.forEach(push)
          })
        }
      })

      if (!points.length) return null

      let west = points[0][0]
      let east = points[0][0]
      let south = points[0][1]
      let north = points[0][1]
      const unique = Object.create(null)

      points.forEach(function (pair) {
        west = Math.min(west, pair[0])
        east = Math.max(east, pair[0])
        south = Math.min(south, pair[1])
        north = Math.max(north, pair[1])
        unique[pair[0].toFixed(6) + ',' + pair[1].toFixed(6)] = true
      })

      return {
        west: west,
        east: east,
        south: south,
        north: north,
        count: Object.keys(unique).length
      }
    },

    applyView (view) {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const v = view && typeof view === 'object' ? view : {}
      const duration = v.fly === false ? 0 : 1.2

      if (v.fit === true) {
        const box = this.computeBbox()
        if (!box) {
          this.flyToInitial(duration)
        } else if (box.count <= 1) {
          this.flyToPoint(box.west, box.south, 800000, duration)
        } else {
          // 离线底图为低分辨率世界影像；过近的自动适配会糊成一片，
          // 因此给区域级视角一个最小跨度，保留城市群的相对位置。
          let west = box.west
          let east = box.east
          let south = box.south
          let north = box.north
          const minLonSpan = 6
          const minLatSpan = 4
          const lonSpan = east - west
          const latSpan = north - south
          if (lonSpan < minLonSpan) {
            const pad = (minLonSpan - lonSpan) / 2
            west -= pad
            east += pad
          }
          if (latSpan < minLatSpan) {
            const pad = (minLatSpan - latSpan) / 2
            south -= pad
            north += pad
          }
          // 再留一点边缘余量，避免最外侧城市的标签贴边或被裁掉。
          const padLon = Math.max((east - west) * 0.12, 0.4)
          const padLat = Math.max((north - south) * 0.12, 0.3)
          west -= padLon
          east += padLon
          south = Math.max(-85, south - padLat)
          north = Math.min(85, north + padLat)
          this.flyToRectangle({ west: west, east: east, south: south, north: north }, duration)
        }
        return
      }

      if (validLonLat(Number(v.lon), Number(v.lat))) {
        const height = Number(v.height) > 0 ? Number(v.height) : 1500000
        this.flyToPoint(Number(v.lon), Number(v.lat), height, duration)
        return
      }

      // 无 fit / 无有效 lon-lat：回到配置的初始视角。
      this.flyToInitial(duration)
    },

    flyToPoint (lon, lat, height, duration) {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const destination = this.Cesium.Cartesian3.fromDegrees(lon, lat, height)
      if (duration > 0) {
        this.viewer.camera.flyTo({ destination: destination, duration: duration })
      } else {
        this.viewer.camera.setView({ destination: destination })
      }
    },

    flyToRectangle (box, duration) {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const Cesium = this.Cesium
      let west = box.west
      let east = box.east
      let south = box.south
      let north = box.north

      // 退化矩形（例如一条南北向线）补一点宽高，避免相机无法定位。
      if (east - west < 0.0001) {
        west -= 0.01
        east += 0.01
      }
      if (north - south < 0.0001) {
        south -= 0.01
        north += 0.01
      }

      const destination = Cesium.Rectangle.fromDegrees(west, south, east, north)
      if (duration > 0) {
        this.viewer.camera.flyTo({ destination: destination, duration: duration })
      } else {
        this.viewer.camera.setView({ destination: destination })
      }
    },

    flyToInitial (duration) {
      if (!this.viewer || this.viewer.isDestroyed()) return
      const iv = this.config.initialView
      if (!iv) return
      const destination = this.Cesium.Cartesian3.fromDegrees(iv.lon, iv.lat, iv.height)
      if (duration > 0) {
        this.viewer.camera.flyTo({ destination: destination, duration: duration })
      } else {
        this.viewer.camera.setView({ destination: destination })
      }
    },

    resetView () {
      try {
        this.flyToInitial(1.0)
      } catch (error) {
        console.warn('[earth] 复位视角失败：', error)
      }
    },

    emitError (error) {
      if (this.errorEmitted || this.destroyed) return
      this.errorEmitted = true
      const message = error && error.message ? error.message : String(error || 'Cesium 初始化失败')
      console.error('[earth] Cesium 错误：', error)
      this.$emit('error', message)
    }
  }
}
</script>
