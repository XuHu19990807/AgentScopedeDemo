/**
 * 运行时配置：默认值 + window.EARTH_CONFIG 深合并。
 * 该模块不做任何网络请求，也不依赖 Vue。
 */

export const DEFAULT_CONFIG = {
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
  plot: {
    stylesUrl: './plot-styles.json',
    iconBase: './plot-icons/'
  },
  autoSwitch: {
    enabled: true,
    to2dHeight: 1500000,
    to3dZoom: 3.5,
    debounceMs: 180
  }
}

const BASEMAP_TYPES = ['natural-earth-ii', 'xyz', 'image', 'none']

export function isPlainObject (value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/**
 * 深合并两个配置对象。数组整体替换，不递归合并。
 * 返回新对象，不修改 base / extra。
 */
export function deepMerge (base, extra) {
  if (Array.isArray(base)) {
    return Array.isArray(extra) ? extra.slice() : base.slice()
  }
  if (!isPlainObject(base)) {
    return isPlainObject(extra) ? Object.assign({}, extra) : extra
  }

  const out = Object.assign({}, base)
  if (!isPlainObject(extra)) return out

  Object.keys(extra).forEach((key) => {
    const value = extra[key]
    if (value === undefined) return
    if (isPlainObject(value) && isPlainObject(out[key])) {
      out[key] = deepMerge(out[key], value)
    } else if (Array.isArray(value)) {
      out[key] = value.slice()
    } else {
      out[key] = value
    }
  })
  return out
}

/** 确保目录型 URL 以 / 结尾。 */
export function ensureTrailingSlash (value, fallback) {
  const text = typeof value === 'string' && value.trim() ? value.trim() : fallback
  if (!text) return '/'
  return text.endsWith('/') ? text : text + '/'
}

function normalizeInitialView (value) {
  const defaults = DEFAULT_CONFIG.initialView
  const input = isPlainObject(value) ? value : {}
  let lon = Number(input.lon)
  let lat = Number(input.lat)
  let height = Number(input.height)
  let zoom = Number(input.zoom)

  if (!Number.isFinite(lon) || lon < -180 || lon > 180) lon = defaults.lon
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) lat = defaults.lat
  if (!Number.isFinite(height) || height <= 0) height = defaults.height
  if (!Number.isFinite(zoom)) zoom = defaults.zoom
  zoom = Math.min(22, Math.max(0, zoom))

  return { lon, lat, height, zoom }
}

/**
 * 读取并规范化配置。
 * @param {object} [raw] 显式配置；省略时读取 window.EARTH_CONFIG。
 */
export function loadConfig (raw) {
  const source = raw !== undefined
    ? raw
    : (typeof window !== 'undefined' ? window.EARTH_CONFIG : null)

  const merged = deepMerge(DEFAULT_CONFIG, isPlainObject(source) ? source : {})

  merged.cesiumBase = ensureTrailingSlash(merged.cesiumBase, DEFAULT_CONFIG.cesiumBase)
  merged.olBase = ensureTrailingSlash(merged.olBase, DEFAULT_CONFIG.olBase)
  merged.initialMode = merged.initialMode === '2d' ? '2d' : '3d'
  merged.initialView = normalizeInitialView(merged.initialView)
  merged.theme = merged.theme === 'light' ? 'light' : 'dark'

  const plot = isPlainObject(merged.plot) ? merged.plot : {}
  merged.plot = {
    stylesUrl: typeof plot.stylesUrl === 'string' && plot.stylesUrl.trim()
      ? plot.stylesUrl.trim()
      : DEFAULT_CONFIG.plot.stylesUrl,
    iconBase: ensureTrailingSlash(plot.iconBase, DEFAULT_CONFIG.plot.iconBase)
  }

  const basemap = isPlainObject(merged.basemap) ? merged.basemap : {}
  const type = String(basemap.type || '').toLowerCase()
  merged.basemap = Object.assign({}, basemap, {
    type: BASEMAP_TYPES.indexOf(type) >= 0 ? type : 'natural-earth-ii'
  })

  const maxFeatures = Math.floor(Number(merged.maxFeatures))
  merged.maxFeatures = Number.isFinite(maxFeatures) && maxFeatures > 0
    ? maxFeatures
    : DEFAULT_CONFIG.maxFeatures

  const auto = isPlainObject(merged.autoSwitch) ? merged.autoSwitch : {}
  const to2dHeight = Number(auto.to2dHeight)
  const to3dZoom = Number(auto.to3dZoom)
  const debounceMs = Number(auto.debounceMs)
  merged.autoSwitch = {
    enabled: auto.enabled !== false,
    to2dHeight: Number.isFinite(to2dHeight) && to2dHeight >= 100000 && to2dHeight <= 50000000
      ? to2dHeight
      : DEFAULT_CONFIG.autoSwitch.to2dHeight,
    to3dZoom: Number.isFinite(to3dZoom) && to3dZoom >= 0 && to3dZoom <= 22
      ? to3dZoom
      : DEFAULT_CONFIG.autoSwitch.to3dZoom,
    debounceMs: Number.isFinite(debounceMs) && debounceMs >= 80 && debounceMs <= 1000
      ? Math.round(debounceMs)
      : DEFAULT_CONFIG.autoSwitch.debounceMs
  }

  return merged
}

export default loadConfig
