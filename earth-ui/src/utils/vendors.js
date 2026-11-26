/**
 * 动态加载 Cesium / OpenLayers 的脚本与样式。
 *
 * 约定：
 *  - Cesium 脚本：${cesiumBase}Cesium.js，暴露 window.Cesium
 *  - OL 脚本：    ${olBase}ol.js，暴露 window.ol
 *  - 两者互不阻塞：一个失败时另一个仍可使用。
 *  - CSS 加载失败只警告，不阻断地图初始化。
 */
import { loadConfig, ensureTrailingSlash } from './config'

const SCRIPT_TIMEOUT = 20000
const CSS_TIMEOUT = 15000

function loadCss (href, key) {
  if (!href) return Promise.resolve(false)

  const selector = 'link[data-earth-css="' + key + '"]'
  const existing = document.querySelector(selector)
  if (existing && existing.dataset.earthLoaded === '1') return Promise.resolve(true)
  if (existing && existing.dataset.earthLoaded === '0') return Promise.resolve(false)

  return new Promise(function (resolve) {
    const link = existing || document.createElement('link')
    let settled = false
    let timer = null

    function finish (ok) {
      if (settled) return
      settled = true
      if (timer) clearTimeout(timer)
      link.removeEventListener('load', onLoad)
      link.removeEventListener('error', onError)
      link.dataset.earthLoaded = ok ? '1' : '0'
      if (!ok) {
        console.warn('[earth] 样式加载失败（不阻断地图）：' + href)
      }
      resolve(ok)
    }

    function onLoad () { finish(true) }
    function onError () { finish(false) }

    link.addEventListener('load', onLoad)
    link.addEventListener('error', onError)
    timer = setTimeout(function () { finish(false) }, CSS_TIMEOUT)

    if (!existing) {
      link.rel = 'stylesheet'
      link.href = href
      link.dataset.earthCss = key
      document.head.appendChild(link)
    }
  })
}

function loadScript (src, key, globalName) {
  if (window[globalName]) return Promise.resolve(true)

  const selector = 'script[data-earth-vendor="' + key + '"]'
  const existing = document.querySelector(selector)

  if (existing && existing.dataset.earthLoaded === '1' && !window[globalName]) {
    return Promise.reject(new Error('脚本已存在但未找到 window.' + globalName + '：' + src))
  }

  return new Promise(function (resolve, reject) {
    const script = existing || document.createElement('script')
    let settled = false
    let timer = null

    function cleanup () {
      if (timer) clearTimeout(timer)
      script.removeEventListener('load', onLoad)
      script.removeEventListener('error', onError)
    }

    function finish (ok, error) {
      if (settled) return
      settled = true
      cleanup()
      if (ok) {
        script.dataset.earthLoaded = '1'
        resolve(true)
      } else {
        script.dataset.earthLoaded = '0'
        reject(error)
      }
    }

    function onLoad () {
      if (window[globalName]) {
        finish(true)
      } else {
        finish(false, new Error('脚本已加载但未暴露 window.' + globalName + '：' + src))
      }
    }

    function onError () {
      finish(false, new Error('脚本加载失败：' + src))
    }

    script.addEventListener('load', onLoad)
    script.addEventListener('error', onError)
    timer = setTimeout(function () {
      finish(false, new Error('脚本加载超时（' + (SCRIPT_TIMEOUT / 1000) + ' 秒）：' + src))
    }, SCRIPT_TIMEOUT)

    if (!existing) {
      script.src = src
      script.async = true
      script.dataset.earthVendor = key
      document.head.appendChild(script)
    }
  })
}

function reasonOf (result) {
  if (result.status === 'rejected') {
    const reason = result.reason
    return reason && reason.message ? reason.message : String(reason || '未知错误')
  }
  return ''
}

/**
 * 并行加载两个地图引擎。
 * @param {object} [config] 已规范化的配置；省略时读取 window.EARTH_CONFIG。
 * @returns {Promise<{cesium: boolean, ol: boolean}>} 能力标记
 */
export async function loadVendors (config) {
  const cfg = config || loadConfig()
  const cesiumBase = ensureTrailingSlash(cfg.cesiumBase, '/map-assets/cesium/')
  const olBase = ensureTrailingSlash(cfg.olBase, '/map-assets/ol/')

  // 必须在 Cesium.js 执行前设置，Cesium 用它定位 Workers / Assets。
  window.CESIUM_BASE_URL = cesiumBase

  const cssTasks = [
    loadCss(cesiumBase + 'Widgets/widgets.css', 'cesium'),
    loadCss(olBase + 'ol.css', 'ol')
  ]

  const scriptTasks = [
    window.Cesium
      ? Promise.resolve(true)
      : loadScript(cesiumBase + 'Cesium.js', 'cesium', 'Cesium'),
    window.ol
      ? Promise.resolve(true)
      : loadScript(olBase + 'ol.js', 'ol', 'ol')
  ]

  const results = await Promise.allSettled(scriptTasks)
  const cesium = results[0].status === 'fulfilled' && !!window.Cesium
  const ol = results[1].status === 'fulfilled' && !!window.ol

  if (!cesium) {
    console.warn('[earth] Cesium 不可用，将仅使用 2D 模式。' + reasonOf(results[0]))
  }
  if (!ol) {
    console.warn('[earth] OpenLayers 不可用，将仅使用 3D 模式。' + reasonOf(results[1]))
  }

  // 样式可以晚于脚本，但启动前尽量就绪；失败已在上方 warn。
  await Promise.allSettled(cssTasks)

  return { cesium, ol }
}

export default loadVendors
