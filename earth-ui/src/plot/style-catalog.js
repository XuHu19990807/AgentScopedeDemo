/**
 * 标绘样式目录：把后端发来的语义 style id 解析成图标/颜色/线型。
 *
 * 后端不再维护颜色白名单；内网要换样式时只改 public/plot-styles.json
 * 和 public/plot-icons/，Java 不需要重新编译。
 */

const KINDS = ['point', 'line', 'polygon']
const MAX_INHERIT_DEPTH = 5

export const DEFAULT_CATALOG = {
  version: 1,
  defaults: {
    point: {
      color: '#38bdf8',
      radius: 5,
      strokeColor: '#0b1220',
      strokeWidth: 1.5,
      labelColor: '#e2e8f0',
      labelBackground: 'rgba(15,23,42,.78)',
      labelOffsetY: -16
    },
    line: {
      color: '#22d3ee',
      width: 2.5,
      dash: null,
      labelColor: '#e2e8f0',
      labelBackground: 'rgba(15,23,42,.78)'
    },
    polygon: {
      color: '#22c55e',
      fillAlpha: 0.18,
      width: 2,
      dash: null,
      labelColor: '#e2e8f0',
      labelBackground: 'rgba(15,23,42,.78)'
    }
  },
  themes: {
    dark: { labelColor: '#e2e8f0', labelBackground: 'rgba(15,23,42,.78)' },
    light: { labelColor: '#0f172a', labelBackground: 'rgba(255,255,255,.92)' }
  },
  selection: { color: '#fbbf24', width: 3 },
  styles: {
    point: { kind: 'point', label: '标注点' },
    line: { kind: 'line', label: '线' },
    polygon: { kind: 'polygon', label: '面' }
  },
  aliases: {
    default: 'point',
    primary: 'point',
    secondary: 'point',
    highlight: 'point',
    danger: 'polygon'
  }
}

let catalog = DEFAULT_CATALOG
let options = { iconBase: './plot-icons/', theme: 'dark' }
const warned = new Set()

function isObject (value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function clone (value) {
  if (Array.isArray(value)) return value.map(clone)
  if (!isObject(value)) return value
  const out = {}
  Object.keys(value).forEach((key) => { out[key] = clone(value[key]) })
  return out
}

/** 合并默认值、主题覆盖和样式继承链。数组（如 dash）整体替换。 */
export function mergeStyle (base, extra) {
  const out = Object.assign({}, base)
  if (!isObject(extra)) return out
  Object.keys(extra).forEach((key) => {
    if (extra[key] !== undefined) out[key] = clone(extra[key])
  })
  return out
}

export function resolveStyleId (feature) {
  const kind = KINDS.indexOf(feature && feature.kind) >= 0 ? feature.kind : 'point'
  const raw = feature && typeof feature.style === 'string' ? feature.style.trim() : ''
  const styles = (catalog && catalog.styles) || {}
  const aliases = (catalog && catalog.aliases) || {}
  if (!raw) return kind
  if (styles[raw]) return raw
  if (aliases[raw] && styles[aliases[raw]]) return aliases[raw]
  if (!warned.has(raw)) {
    warned.add(raw)
    console.warn('[plot] 未知样式 "' + raw + '"，已回落默认样式')
  }
  return kind
}

/**
 * 解析样式继承链（子样式覆盖父样式），返回从根到叶的数组。
 * 继承超过 5 层或出现环时停止，避免配置文件写错导致死循环。
 */
export function styleChain (styles, id) {
  const chain = []
  const seen = new Set()
  let current = id
  while (current && styles[current] && chain.length < MAX_INHERIT_DEPTH) {
    if (seen.has(current)) break
    seen.add(current)
    const item = styles[current]
    chain.unshift(item)
    current = typeof item.base === 'string' ? item.base : ''
  }
  return chain
}

export function configurePlotStyles (nextCatalog, nextOptions) {
  if (isObject(nextCatalog)) {
    catalog = nextCatalog
    warned.clear()
  }
  if (isObject(nextOptions)) {
    options = Object.assign({}, options, nextOptions)
  }
  return catalog
}

export function getPlotCatalog () {
  return catalog
}

export function plotIconUrl (file) {
  if (!file || typeof file !== 'string') return ''
  if (/^(?:https?:|data:|\/)/i.test(file)) return file
  const base = options.iconBase || './plot-icons/'
  return base.replace(/\/?$/, '/') + file.replace(/^\.?\//, '')
}

/**
 * 解析单个要素的最终外观。未知 style 会回落到 point/line/polygon。
 */
export function resolveFeatureStyle (feature, theme) {
  const styles = (catalog && catalog.styles) || {}
  const defaults = (catalog && catalog.defaults) || {}
  const id = resolveStyleId(feature || {})
  const chain = styleChain(styles, id)
  // 几何类型以要素 kind 为准；样式里的 kind 只用于缺省/图例。
  const featureKind = feature && KINDS.indexOf(feature.kind) >= 0 ? feature.kind : ''
  const kind = featureKind ||
    chain.reduce((found, item) => found || (KINDS.indexOf(item.kind) >= 0 ? item.kind : ''), '') ||
    'point'
  const themeName = theme || options.theme || 'dark'
  const themeDefaults = (catalog && catalog.themes && catalog.themes[themeName]) || {}

  let merged = mergeStyle(defaults[kind] || {}, themeDefaults)
  chain.forEach((item) => { merged = mergeStyle(merged, item) })

  merged.id = id
  merged.kind = KINDS.indexOf(merged.kind) >= 0 ? merged.kind : kind
  merged.label = merged.label || id
  merged.iconUrl = plotIconUrl(merged.icon)
  if (merged.iconUrl) {
    // 图标锚点在底部，标签上移图标高度，避免标签底色盖住图标。
    merged.labelOffsetY = (Number(merged.labelOffsetY) || 0) - (Number(merged.iconSize) || 30)
  }
  const selection = (catalog && catalog.selection) || {}
  merged.selectedColor = selection.color || '#fbbf24'
  merged.selectedWidth = Number(selection.width) || 3
  return merged
}

/**
 * 由图例要素生成图例项；同一 style 只保留一项，顺序按要素出现顺序。
 */
export function buildLegend (features, theme) {
  const items = []
  const seen = new Set()
  ;(Array.isArray(features) ? features : []).forEach((feature) => {
    const style = resolveFeatureStyle(feature, theme)
    if (seen.has(style.id)) return
    seen.add(style.id)
    items.push({
      style: style.id,
      label: style.label || style.id,
      kind: style.kind,
      color: style.color,
      iconUrl: style.iconUrl,
      dash: style.dash
    })
  })
  return items
}

/** 静态校验：给 scripts/validate-plot-styles.mjs 和运行时加载共用。 */
export function validatePlotStyles (input) {
  const errors = []
  if (!isObject(input)) return ['根节点必须是对象']
  const styles = input.styles
  if (!isObject(styles)) return ['styles 必须是对象']
  Object.keys(styles).forEach((id) => {
    const item = styles[id]
    if (!isObject(item)) {
      errors.push('styles.' + id + ' 必须是对象')
      return
    }
    if (item.base && !styles[item.base]) {
      errors.push('样式 ' + id + ' 的 base 不存在：' + item.base)
    }
    if (item.base && styleChain(styles, id).length <= 1) {
      errors.push('样式 ' + id + ' 的继承链无效（可能成环）')
    }
    if (item.kind && KINDS.indexOf(item.kind) < 0) {
      errors.push('样式 ' + id + ' 的 kind 非法：' + item.kind)
    }
    if (item.dash && (!Array.isArray(item.dash) || item.dash.length < 2 ||
      item.dash.some((n) => !Number.isFinite(Number(n)) || Number(n) <= 0))) {
      errors.push('样式 ' + id + ' 的 dash 必须是两个正数')
    }
  })
  return errors
}

export async function loadPlotStyles (url, nextOptions) {
  if (isObject(nextOptions)) configurePlotStyles(null, nextOptions)
  const target = url || './plot-styles.json'
  try {
    const response = await fetch(target, { cache: 'no-cache' })
    if (!response.ok) throw new Error('HTTP ' + response.status)
    const data = await response.json()
    const errors = validatePlotStyles(data)
    if (errors.length) console.warn('[plot] 样式表校验有警告：' + errors.join('；'))
    configurePlotStyles(data)
    return data
  } catch (error) {
    console.warn('[plot] 加载样式表失败，使用内置默认样式：' + error.message)
    configurePlotStyles(DEFAULT_CATALOG)
    return DEFAULT_CATALOG
  }
}

export default {
  DEFAULT_CATALOG,
  configurePlotStyles,
  loadPlotStyles,
  resolveFeatureStyle,
  buildLegend,
  plotIconUrl,
  resolveStyleId,
  validatePlotStyles
}
