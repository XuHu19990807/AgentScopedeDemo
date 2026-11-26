<template>
  <div ref="root" class="scene-view ol-scene"></div>
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

function hexToRgba (hex, alpha) {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(String(hex || ''))
  if (!match) return hex
  let body = match[1]
  if (body.length === 3) {
    body = body.split('').map(function (ch) { return ch + ch }).join('')
  }
  const r = parseInt(body.slice(0, 2), 16)
  const g = parseInt(body.slice(2, 4), 16)
  const b = parseInt(body.slice(4, 6), 16)
  return 'rgba(' + r + ', ' + g + ', ' + b + ', ' + alpha + ')'
}

export default {
  name: 'OlScene',
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
      map: null,
      source: null,
      vectorLayer: null,
      baseLayer: null,
      baseErrorKey: null,
      baseMapFailed: false,
      graticuleLayer: null,
      resizeObserver: null,
      windowResizeHandler: null,
      destroyed: false,
      errorEmitted: false,
      wheelHandler: null,
      wheelTimer: null,
      wheelDelta: 0,
      autoSwitchAt: 0,
      resizeTimer: null,
      sizeTimer: null
    }
  },
  watch: {
    renderToken () {
      this.renderFeatures()
    },
    theme () {
      this.updateGraticuleStyle()
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
    if (this.wheelTimer) {
      clearTimeout(this.wheelTimer)
      this.wheelTimer = null
    }
    if (this.resizeTimer) {
      clearTimeout(this.resizeTimer)
      this.resizeTimer = null
    }
    if (this.sizeTimer) {
      clearInterval(this.sizeTimer)
      this.sizeTimer = null
    }
    if (this.wheelHandler && this.map) {
      try { this.map.getViewport().removeEventListener('wheel', this.wheelHandler) } catch (error) { /* ignore */ }
      this.wheelHandler = null
    }
    this.unlistenBaseError()
    if (this.map) {
      try { this.map.setTarget(null) } catch (error) { /* ignore */ }
    }
    this.map = null
    this.source = null
    this.vectorLayer = null
    this.baseLayer = null
    this.graticuleLayer = null
  },
  methods: {
    init () {
      const ol = window.ol
      if (!ol) throw new Error('OpenLayers 未加载')
      this.ol = ol

      const iv = this.config.initialView
      this.map = new ol.Map({
        target: this.$el,
        layers: [],
        view: new ol.View({
          projection: 'EPSG:4326',
          center: [iv.lon, iv.lat],
          zoom: iv.zoom
        })
      })

      this.setupBaseMap()
      this.setupGraticule()

      this.source = new ol.source.Vector()
      this.vectorLayer = new ol.layer.Vector({
        source: this.source,
        // 关闭整要素 declutter：否则相邻城市的标签碰撞会把点本身一起隐藏，
        // 用户会误以为某个城市没上图。标签重叠交由缩放/点选解决。
        declutter: false
      })
      this.map.addLayer(this.vectorLayer)

      this.renderFeatures()
      this.bindEvents()
      this.bindResize()
      this.bindAutoSwitch()

      // 组件刚插入 DOM 时父容器可能还没完成布局（尤其是 3D→2D 自动切换），
      // 这里在下一帧和稍后各量一次，避免 OL 只按半宽创建 viewport。
      this.$nextTick(() => this.resize())
      this.resizeTimer = setTimeout(() => {
        this.resizeTimer = null
        this.resize()
      }, 180)
      // 拖动窗口/跨屏移动/浏览器缩放时，个别环境不会稳定触发 ResizeObserver。
      // 定期对比目标尺寸，发现 OL 仍按旧尺寸绘制时补一次 updateSize。
      this.sizeTimer = setInterval(() => this.checkSize(), 700)
    },

    checkSize () {
      if (!this.map || this.destroyed) return
      const el = this.$el
      const size = this.map.getSize()
      if (!size) {
        this.resize()
        return
      }
      if (Math.abs(size[0] - el.clientWidth) > 1 || Math.abs(size[1] - el.clientHeight) > 1) {
        this.resize()
      }
    },

    resize () {
      if (!this.map || this.destroyed) return
      try { this.map.updateSize() } catch (error) { /* ignore */ }
    },

    setupBaseMap () {
      const ol = this.ol
      const basemap = this.config.basemap || { type: 'none' }
      const type = String(basemap.type || 'none').toLowerCase()

      if (type === 'none') return

      try {
        if (type === 'natural-earth-ii') {
          // Natural Earth II 瓦片可随 Cesium SDK 分发，也可放在独立的 basemap.baseUrl。
          const hasCustom = !!basemap.baseUrl
          const base = String(
            basemap.baseUrl ||
            (this.config.cesiumBase + 'Assets/Textures/NaturalEarthII/')
          )
          const resolutions = [
            360 / 512,
            360 / 1024,
            360 / 2048,
            360 / 4096,
            360 / 8192,
            360 / 16384
          ]
          const tileGrid = new ol.tilegrid.TileGrid({
            resolutions: hasCustom ? resolutions : resolutions.slice(0, 3),
            tileSize: 256,
            origin: [-180, 90],
            extent: [-180, -90, 180, 90]
          })

          const source = new ol.source.XYZ({
            projection: 'EPSG:4326',
            tileGrid: tileGrid,
            tileUrlFunction: function (tileCoord) {
              if (!tileCoord) return undefined
              const z = tileCoord[0]
              const x = tileCoord[1]
              const y = tileCoord[2]
              const maxY = (1 << z) - 1
              if (y < 0 || y > maxY) return undefined

              // 世界范围在 z 级为 2^(z+1) 列，横向循环，避免越界请求。
              const width = 2 << z
              const wrappedX = ((x % width) + width) % width
              const reverseY = maxY - y
              return base + z + '/' + wrappedX + '/' + reverseY + '.jpg'
            },
            wrapX: false
          })

          this.baseErrorKey = source.on('tileloaderror', () => {
            this.handleBaseMapError()
          })

          this.baseLayer = new ol.layer.Tile({ source: source })
          this.map.addLayer(this.baseLayer)
        } else if (type === 'xyz') {
          const url = String(basemap.url || '')
          if (!url) throw new Error('basemap.type=xyz 但未配置 basemap.url')
          this.baseLayer = new ol.layer.Tile({
            source: new ol.source.XYZ({
              url: url,
              projection: basemap.projection || 'EPSG:3857'
            })
          })
          this.map.addLayer(this.baseLayer)
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
          this.baseLayer = new ol.layer.Image({
            source: new ol.source.ImageStatic({
              url: url,
              imageExtent: extent,
              projection: 'EPSG:4326'
            })
          })
          this.map.addLayer(this.baseLayer)
        } else {
          console.warn('[earth] 未知的底图类型：' + type)
        }
      } catch (error) {
        console.warn('[earth] 2D 底图初始化失败：', error)
        this.baseLayer = null
      }
    },

    handleBaseMapError () {
      if (this.baseMapFailed || this.destroyed) return
      this.baseMapFailed = true
      console.warn('[earth] Natural Earth II 瓦片加载失败，已移除 2D 底图（保留经纬网）。')
      this.unlistenBaseError()
      if (this.map && this.baseLayer) {
        try { this.map.removeLayer(this.baseLayer) } catch (error) { /* ignore */ }
      }
      this.baseLayer = null
    },

    unlistenBaseError () {
      if (this.baseErrorKey && this.ol && this.ol.Observable &&
          typeof this.ol.Observable.unByKey === 'function') {
        try { this.ol.Observable.unByKey(this.baseErrorKey) } catch (error) { /* ignore */ }
      }
      this.baseErrorKey = null
    },

    graticuleColor () {
      return this.theme === 'light'
        ? 'rgba(71, 85, 105, 0.30)'
        : 'rgba(148, 163, 184, 0.28)'
    },

    setupGraticule () {
      const ol = this.ol
      if (!ol || !ol.layer || typeof ol.layer.Graticule !== 'function') return

      try {
        this.graticuleLayer = new ol.layer.Graticule({
          strokeStyle: new ol.style.Stroke({
            color: this.graticuleColor(),
            width: 1
          }),
          showLabels: false,
          wrapX: false
        })
        this.map.addLayer(this.graticuleLayer)
      } catch (error) {
        console.warn('[earth] 经纬网初始化失败：', error)
        this.graticuleLayer = null
      }
    },

    updateGraticuleStyle () {
      if (!this.graticuleLayer || typeof this.graticuleLayer.setStrokeStyle !== 'function') return
      try {
        this.graticuleLayer.setStrokeStyle(new this.ol.style.Stroke({
          color: this.graticuleColor(),
          width: 1
        }))
      } catch (error) {
        console.warn('[earth] 更新经纬网样式失败：', error)
      }
    },

    themeColors () {
      const dark = this.theme !== 'light'
      return {
        labelFill: dark ? '#e2e8f0' : '#1e293b',
        labelOutline: dark ? '#0b1220' : '#ffffff',
        labelBackground: dark ? 'rgba(15, 23, 42, 0.74)' : 'rgba(255, 255, 255, 0.86)',
        pointOutline: dark ? '#0b1220' : '#ffffff'
      }
    },

    renderFeatures () {
      if (!this.source || this.destroyed) return
      const ol = this.ol
      const list = Array.isArray(this.features) ? this.features : []
      this.source.clear()

      const usedIds = Object.create(null)
      for (let index = 0; index < list.length; index++) {
        const raw = list[index]
        if (!raw || typeof raw !== 'object') continue

        const kind = normalizeKind(raw)
        let id = raw.id !== undefined && raw.id !== null
          ? String(raw.id)
          : 'feature-' + (index + 1)
        let uniqueId = id
        let suffix = 1
        while (usedIds[uniqueId]) {
          suffix += 1
          uniqueId = id + '-' + suffix
        }
        usedIds[uniqueId] = true

        try {
          const geometry = this.buildGeometry(raw, kind)
          if (!geometry) continue

          const feature = new ol.Feature({ geometry: geometry })
          feature.setId(uniqueId)
          feature.set('name', typeof raw.name === 'string' ? raw.name : '')
          feature.set('kind', kind)
          feature.set('city', typeof raw.city === 'string' ? raw.city : '')
          feature.set('styleKey', String(raw.style || raw.styleKey || 'default').toLowerCase())
          feature.setStyle(this.buildStyle(feature, kind, raw))
          this.source.addFeature(feature)
        } catch (error) {
          console.warn('[earth] 2D 要素渲染失败：' + uniqueId, error)
        }
      }
    },

    buildGeometry (raw, kind) {
      const ol = this.ol

      if (kind === 'point' || kind === 'label') {
        const point = pointOf(raw)
        return point ? new ol.geom.Point(point) : null
      }

      if (kind === 'polyline') {
        const coords = raw.coords !== undefined ? raw.coords : raw.coordinates
        const pairs = pairsFrom(coords)
        return pairs.length >= 2 ? new ol.geom.LineString(pairs) : null
      }

      if (kind === 'polygon') {
        const rings = polygonRings(raw)
        if (!rings.length) return null
        // 外环 + 内环（洞）；OL 会自动闭合未闭合的环。
        return new ol.geom.Polygon(rings)
      }

      return null
    },

    buildStyle (feature, kind, raw) {
      const ol = this.ol
      const style = resolveFeatureStyle(raw, this.theme)
      const colors = this.themeColors()
      const selected = this.selectedId !== null && this.selectedId !== undefined &&
        String(this.selectedId) === String(feature.getId())
      const zIndex = selected ? 20 : 10
      const color = selected ? style.selectedColor : style.color

      if (kind === 'point' || kind === 'label') {
        const options = { zIndex: zIndex }
        const name = typeof raw.name === 'string' ? raw.name : ''

        if (kind === 'point') {
          if (style.iconUrl && typeof ol.style.Icon === 'function') {
            const size = Number(style.iconSize) || 26
            options.image = new ol.style.Icon({
              src: style.iconUrl,
              scale: size / 32,
              anchor: [0.5, 1],
              anchorXUnits: 'fraction',
              anchorYUnits: 'fraction'
            })
          } else {
            options.image = new ol.style.Circle({
              radius: (Number(style.radius) || 5) + (selected ? 2 : 0),
              fill: new ol.style.Fill({ color: color }),
              stroke: new ol.style.Stroke({
                color: style.strokeColor || colors.pointOutline,
                width: Number(style.strokeWidth) || 2
              })
            })
          }
        }

        if (name) {
          options.text = new ol.style.Text({
            text: name,
            offsetY: Number(style.labelOffsetY) || -16,
            font: '13px "Microsoft YaHei", "PingFang SC", "Noto Sans SC", sans-serif',
            fill: new ol.style.Fill({ color: style.labelColor || colors.labelFill }),
            stroke: new ol.style.Stroke({ color: colors.labelOutline, width: 3 }),
            backgroundFill: new ol.style.Fill({ color: style.labelBackground || colors.labelBackground }),
            padding: [3, 6, 3, 6],
            overflow: true,
            textAlign: 'center'
          })
        }

        return new ol.style.Style(options)
      }

      if (kind === 'polyline') {
        return new ol.style.Style({
          zIndex: zIndex,
          stroke: new ol.style.Stroke({
            color: color,
            width: (Number(style.width) || 2.5) + (selected ? 1.5 : 0),
            lineCap: 'round',
            lineJoin: 'round',
            lineDash: Array.isArray(style.dash) && style.dash.length >= 2
              ? style.dash.map(Number)
              : undefined
          })
        })
      }

      if (kind === 'polygon') {
        const alpha = Number(style.fillAlpha)
        return new ol.style.Style({
          zIndex: zIndex,
          fill: new ol.style.Fill({
            color: hexToRgba(style.color, Number.isFinite(alpha) ? alpha : 0.18)
          }),
          stroke: new ol.style.Stroke({
            color: color,
            width: (Number(style.width) || 2) + (selected ? 1.5 : 0)
          })
        })
      }

      return undefined
    },

    bindEvents () {
      const map = this.map
      if (!map) return

      map.on('singleclick', (event) => {
        try {
          const feature = map.forEachFeatureAtPixel(event.pixel, function (candidate) {
            return candidate
          }, { hitTolerance: 6 })

          if (!feature) return
          this.$emit('select', {
            id: feature.getId(),
            name: feature.get('name') || '',
            kind: feature.get('kind') || '',
            city: feature.get('city') || ''
          })
        } catch (error) {
          console.warn('[earth] 2D 拾取要素失败：', error)
        }
      })

      map.on('pointermove', (event) => {
        if (event.dragging) return
        try {
          const hit = map.hasFeatureAtPixel(event.pixel, { hitTolerance: 6 })
          const target = map.getTargetElement()
          if (target) target.style.cursor = hit ? 'pointer' : ''
        } catch (error) {
          /* ignore */
        }
      })
    },

    bindResize () {
      if (!this.map) return

      if (typeof ResizeObserver !== 'undefined') {
        this.resizeObserver = new ResizeObserver(() => this.resize())
        this.resizeObserver.observe(this.$el)
      }

      this.windowResizeHandler = () => this.resize()
      window.addEventListener('resize', this.windowResizeHandler)
    },

    bindAutoSwitch () {
      const config = this.config.autoSwitch || {}
      if (!config.enabled || !this.map) return

      const debounce = Number(config.debounceMs) > 0 ? Number(config.debounceMs) : 180
      const viewport = this.map.getViewport()
      this.wheelHandler = (event) => {
        this.wheelDelta = Number(event.deltaY) || 0
        if (this.wheelTimer) clearTimeout(this.wheelTimer)
        this.wheelTimer = setTimeout(() => {
          this.wheelTimer = null
          this.checkAutoSwitch()
        }, debounce)
      }
      viewport.addEventListener('wheel', this.wheelHandler, { passive: true })
    },

    checkAutoSwitch () {
      const config = this.config.autoSwitch || {}
      if (!config.enabled || this.destroyed || !this.map) return
      if (this.wheelDelta <= 0) return

      const now = Date.now()
      if (now - this.autoSwitchAt < 1000) return

      const view = this.map.getView()
      const zoom = view.getZoom()
      const threshold = Number(config.to3dZoom) >= 0 ? Number(config.to3dZoom) : 3.5
      if (!Number.isFinite(zoom) || zoom > threshold) return

      const center = view.getCenter()
      if (!Array.isArray(center) || !Number.isFinite(center[0]) || !Number.isFinite(center[1])) {
        return
      }

      const baseHeight = Number(config.to2dHeight) > 0 ? Number(config.to2dHeight) : 1500000
      const height = Math.max(1500000, Math.min(50000000, baseHeight * Math.pow(2, 5 - zoom)))

      this.autoSwitchAt = now
      this.$emit('auto-mode', {
        mode: '3d',
        lon: Math.max(-180, Math.min(180, center[0])),
        lat: Math.max(-85, Math.min(85, center[1])),
        height: Math.round(height)
      })
    },

    computeBbox () {
      const points = []

      function push (pair) {
        if (pair && validLonLat(pair[0], pair[1])) points.push(pair)
      }

      const list = Array.isArray(this.features) ? this.features : []
      list.forEach(function (raw) {
        if (!raw || typeof raw !== 'object') return
        const kind = normalizeKind(raw)

        if (kind === 'point' || kind === 'label') {
          push(pointOf(raw))
        } else if (kind === 'polyline') {
          const coords = raw.coords !== undefined ? raw.coords : raw.coordinates
          pairsFrom(coords).forEach(push)
        } else if (kind === 'polygon') {
          polygonRings(raw).forEach(function (ring) {
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
      if (!this.map || this.destroyed) return
      const v = view && typeof view === 'object' ? view : {}
      const duration = v.fly === false ? 0 : 800

      if (v.fit === true) {
        const box = this.computeBbox()
        // 离线 Natural Earth II 默认只有 z0–2；basemap.baseUrl 指向的增强瓦片可到 z5。
        const basemapType = this.config.basemap && this.config.basemap.type
        const hasCustomTiles = !!(this.config.basemap && this.config.basemap.baseUrl)
        const maxZoom = basemapType === 'natural-earth-ii'
          ? (hasCustomTiles ? 5 : 2)
          : 8
        if (!box) {
          this.resetViewToInitial(duration)
        } else if (box.count <= 1) {
          this.moveTo([box.west, box.south], maxZoom, duration)
        } else {
          let west = box.west
          let east = box.east
          let south = box.south
          let north = box.north
          if (east - west < 0.0001) { west -= 0.01; east += 0.01 }
          if (north - south < 0.0001) { south -= 0.01; north += 0.01 }

          this.map.getView().fit([west, south, east, north], {
            padding: [50, 50, 50, 50],
            maxZoom: maxZoom,
            duration: duration
          })
        }
        return
      }

      if (validLonLat(Number(v.lon), Number(v.lat))) {
        const zoom = Number(v.zoom)
        this.moveTo(
          [Number(v.lon), Number(v.lat)],
          Number.isFinite(zoom) ? zoom : null,
          duration
        )
        return
      }

      // 无 fit / 无有效 lon-lat：回到配置的初始视角。
      this.resetViewToInitial(duration)
    },

    moveTo (center, zoom, duration) {
      if (!this.map || this.destroyed) return
      const view = this.map.getView()
      if (!duration) {
        view.setCenter(center)
        if (zoom !== null && zoom !== undefined) view.setZoom(zoom)
        return
      }

      const options = { center: center, duration: duration }
      if (zoom !== null && zoom !== undefined) options.zoom = zoom
      view.animate(options)
    },

    resetViewToInitial (duration) {
      if (!this.map || this.destroyed) return
      const iv = this.config.initialView
      if (!iv) return
      this.moveTo([iv.lon, iv.lat], iv.zoom, duration === undefined ? 500 : duration)
    },

    resetView () {
      try {
        this.resetViewToInitial(500)
      } catch (error) {
        console.warn('[earth] 2D 复位视角失败：', error)
      }
    },

    emitError (error) {
      if (this.errorEmitted || this.destroyed) return
      this.errorEmitted = true
      const message = error && error.message ? error.message : String(error || 'OpenLayers 初始化失败')
      console.error('[earth] OpenLayers 错误：', error)
      this.$emit('error', message)
    }
  }
}
</script>
