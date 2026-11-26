<template>
  <div class="earth-app" :class="['theme-' + theme, { 'is-fatal': !!fatalError }]">
    <div class="scene-host">
      <component
        v-if="sceneComponent"
        :is="sceneComponent"
        :key="mode"
        ref="scene"
        :features="features"
        :view="view"
        :render-token="renderToken"
        :selected-id="selectedId"
        :theme="theme"
        @select="onSelect"
        @error="onSceneError"
        @auto-mode="onAutoMode"
      />

      <div v-if="loading" class="loading-mask">
        <span class="loading-spinner" aria-hidden="true"></span>
        <span class="loading-text">正在加载数字地球…</span>
      </div>

      <div v-if="fatalError" class="fatal-view">
        <div class="fatal-panel">
          <div class="fatal-mark" aria-hidden="true">!</div>
          <h1 class="fatal-title">数字地球加载失败</h1>
          <p class="fatal-message">{{ fatalError }}</p>
          <div class="fatal-hint">
            <p>请检查以下静态资源是否可由当前服务访问：</p>
            <ul>
              <li><code>{{ config.cesiumBase }}Cesium.js</code></li>
              <li><code>{{ config.cesiumBase }}Widgets/widgets.css</code></li>
              <li><code>{{ config.olBase }}ol.js</code></li>
              <li><code>{{ config.olBase }}ol.css</code></li>
            </ul>
            <p>默认应由服务端 <code>/map-assets/</code> 提供；可修改 <code>public/config.js</code> 中的 <code>cesiumBase</code> / <code>olBase</code>。</p>
          </div>
        </div>
      </div>
    </div>

    <header class="toolbar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">◈</span>
        <span class="brand-title" :title="title">{{ title }}</span>
      </div>
      <div class="toolbar-actions">
        <span class="link-state" :class="{ online: linked }">
          {{ linked ? '已连接对话' : '独立窗口' }}
        </span>
        <span
          class="mode-indicator"
          :class="'mode-' + mode"
          :title="mode === '3d' ? '滚轮放大到区域级会自动切换到 2D 地图' : '滚轮缩小到世界级会自动切换到 3D 地球'"
        >
          <span class="mode-dot" aria-hidden="true"></span>
          {{ mode === '3d' ? '3D 地球' : '2D 地图' }}
        </span>
        <button type="button" class="tool-btn" @click="resetView">复位</button>
      </div>
    </header>

    <div v-if="legend.length" class="legend" aria-label="图例">
      <div v-for="(item, index) in legend" :key="index" class="legend-item">
        <span v-if="item.iconUrl" class="legend-icon" aria-hidden="true">
          <img :src="item.iconUrl" alt="" />
        </span>
        <span
          v-else
          class="legend-swatch"
          :class="'legend-' + item.kind"
          :style="legendSwatchStyle(item)"
          aria-hidden="true"
        ></span>
        <span class="legend-label">{{ item.label }}</span>
      </div>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast" role="status">{{ toast }}</div>
    </transition>
  </div>
</template>

<script>
import CesiumScene from './components/CesiumScene.vue'
import OlScene from './components/OlScene.vue'
import { loadConfig } from './utils/config'
import { loadVendors } from './utils/vendors'
import { buildLegend, loadPlotStyles } from './plot/style-catalog'
import {
  post as bridgePost,
  onMessage,
  hello as bridgeHello,
  close as bridgeClose
} from './utils/bridge'

function safeColor (value) {
  if (typeof value !== 'string') return ''
  const color = value.trim()
  if (/^#[0-9a-f]{3,8}$/i.test(color)) return color
  if (/^rgba?\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}(?:\s*,\s*(?:0|1|0?\.\d+))?\s*\)$/i.test(color)) {
    return color
  }
  return ''
}

function finiteOrNull (value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

export default {
  name: 'EarthApp',
  components: { CesiumScene, OlScene },
  data () {
    const config = loadConfig()
    return {
      config: config,
      mode: '3d',
      features: [],
      view: null,
      legend: [],
      title: '数字地球',
      selectedId: null,
      theme: config.theme,
      capabilities: { cesium: false, ol: false },
      fatalError: '',
      loading: true,
      renderToken: 0,
      toast: '',
      conversationId: null,
      unsubscribeMessage: null,
      toastTimer: null,
      linked: false,
      pendingSync: null,
      autoSwitching: false,
      lastAutoSwitchAt: 0
    }
  },
  computed: {
    sceneComponent () {
      if (this.fatalError) return null
      if (this.mode === '3d' && this.capabilities.cesium) return CesiumScene
      if (this.mode === '2d' && this.capabilities.ol) return OlScene
      return null
    }
  },
  mounted () {
    this.unsubscribeMessage = onMessage(this.handleMessage)
    this.bootstrap()
  },
  beforeDestroy () {
    if (this.unsubscribeMessage) {
      this.unsubscribeMessage()
      this.unsubscribeMessage = null
    }
    bridgeClose()
    if (this.toastTimer) {
      clearTimeout(this.toastTimer)
      this.toastTimer = null
    }
  },
  methods: {
    async bootstrap () {
      try {
        await loadPlotStyles(this.config.plot.stylesUrl, {
          iconBase: this.config.plot.iconBase,
          theme: this.theme
        })
        const result = await loadVendors(this.config)
        const capabilities = {
          cesium: !!(result && result.cesium),
          ol: !!(result && result.ol)
        }
        this.capabilities = capabilities

        if (!capabilities.cesium && !capabilities.ol) {
          this.fail(
            'Cesium 与 OpenLayers 均未能加载。请确认 ' +
            this.config.cesiumBase + 'Cesium.js 与 ' +
            this.config.olBase + 'ol.js 可访问。'
          )
          return
        }

        let next = this.config.initialMode === '2d' ? '2d' : '3d'
        if (next === '3d' && !capabilities.cesium) next = '2d'
        else if (next === '2d' && !capabilities.ol) next = '3d'

        if ((next === '3d' && !capabilities.cesium) || (next === '2d' && !capabilities.ol)) {
          this.fail('没有可用的地图引擎，请检查 Cesium / OpenLayers 静态资源。')
          return
        }

        this.mode = next
        this.loading = false

        await this.$nextTick()
        this.postToParent('ready', {
          mode: this.mode,
          capabilities: {
            cesium: capabilities.cesium,
            ol: capabilities.ol
          }
        })
        bridgeHello({
          mode: this.mode,
          capabilities: {
            cesium: capabilities.cesium,
            ol: capabilities.ol
          }
        })

        if (this.pendingSync) {
          const pending = this.pendingSync
          this.pendingSync = null
          this.applySync(pending)
        }

        if (this.isDemo()) {
          this.loadDemo()
        }
      } catch (error) {
        console.error('[earth] 初始化失败：', error)
        this.fail(error && error.message ? error.message : '数字地球初始化失败')
      }
    },

    isDemo () {
      try {
        return new URLSearchParams(window.location.search).get('demo') === '1'
      } catch (error) {
        return false
      }
    },

    async loadDemo () {
      try {
        const response = await fetch('demo.json', { cache: 'no-store' })
        if (!response.ok) throw new Error('HTTP ' + response.status)
        const data = await response.json()
        if (!data || typeof data !== 'object') throw new Error('demo.json 格式不正确')
        const command = Object.assign({}, data, { type: 'replace' })
        this.applyCommand(command, command.commandId !== undefined ? command.commandId : 'demo')
      } catch (error) {
        console.warn('[earth] 示例数据加载失败：', error)
        this.showToast('示例数据加载失败')
      }
    },

    fail (message) {
      this.loading = false
      this.fatalError = String(message || '数字地球加载失败')
      this.postError(this.fatalError, true)
    },

    postToParent (type, payload) {
      try {
        bridgePost(type, payload)
      } catch (error) {
        console.warn('[earth] 向父页面发送消息失败：', error)
      }
    },

    postError (message, fatal) {
      this.postToParent('error', {
        message: String(message || '未知错误'),
        fatal: !!fatal
      })
    },

    setTheme (value) {
      const next = value === 'light' ? 'light' : (value === 'dark' ? 'dark' : null)
      if (!next || next === this.theme) return
      this.theme = next
      this.refreshLegend()
    },

    showToast (text, duration) {
      this.toast = String(text || '')
      if (this.toastTimer) clearTimeout(this.toastTimer)
      this.toastTimer = setTimeout(() => {
        this.toast = ''
        this.toastTimer = null
      }, typeof duration === 'number' ? duration : 3200)
    },

    onAutoMode (payload) {
      if (!payload || typeof payload !== 'object') return
      if (this.loading || this.fatalError || this.autoSwitching) return

      const target = String(payload.mode || '').toLowerCase()
      if (target !== '3d' && target !== '2d') return
      if (target === this.mode) return

      if (target === '3d' && !this.capabilities.cesium) {
        this.showToast('3D 引擎不可用，继续使用 2D 地图')
        return
      }
      if (target === '2d' && !this.capabilities.ol) {
        this.showToast('2D 引擎不可用，继续使用 3D 地球')
        return
      }

      const now = Date.now()
      if (now - this.lastAutoSwitchAt < 400) return

      const nextView = Object.assign({}, payload, { fit: false, fly: false })
      this.autoSwitching = true
      this.view = nextView
      const changed = this.switchMode(target)
      if (!changed) {
        this.autoSwitching = false
        return
      }

      this.$nextTick(() => {
        // 场景是刚挂载的，先让它按当前窗口尺寸量一次，再套用视角；
        // 否则 OL 可能沿用挂载瞬间的半宽尺寸，表现为地图只铺满左侧。
        this.resizeScene()
        const ok = this.applySceneView(nextView)
        this.lastAutoSwitchAt = Date.now()
        this.autoSwitching = false
        if (!ok) {
          this.showToast('地图场景尚未就绪')
          return
        }
        setTimeout(() => this.resizeScene(), 120)
        this.showToast(target === '2d' ? '已自动切换到 2D 地图' : '已自动切换到 3D 地球', 1600)
        this.postToParent('mode', { mode: target })
      })
    },

    async applySync (payload) {
      if (!payload || typeof payload !== 'object') return
      this.linked = true
      if (payload.theme !== undefined) this.setTheme(payload.theme)
      if (payload.conversationId !== undefined) {
        this.conversationId = payload.conversationId === null ? null : String(payload.conversationId)
      }

      const commands = Array.isArray(payload.commands) ? payload.commands : []
      if (!commands.length) {
        this.features = []
        this.legend = []
        this.title = payload.title ? String(payload.title) : '数字地球'
        this.view = null
        this.selectedId = null
        this.renderToken += 1
        return
      }

      // 同步时从干净状态开始，避免旧会话的标注残留。
      this.features = []
      this.legend = []
      this.view = null
      this.selectedId = null
      this.renderToken += 1

      for (const command of commands) {
        if (!command || typeof command !== 'object') continue
        await this.applyCommand(command, command.id !== undefined ? command.id : null)
      }
      if (payload.title) this.title = String(payload.title)
    },

    resetView () {
      const scene = this.$refs.scene
      if (!scene || typeof scene.resetView !== 'function') {
        this.showToast('场景尚未就绪')
        return
      }
      try {
        scene.resetView()
      } catch (error) {
        console.warn('[earth] 复位视角失败：', error)
      }
    },

    handleMessage (type, payload) {
      if (type === 'sync') {
        if (this.loading) {
          this.pendingSync = payload
          return
        }
        this.linked = true
        this.applySync(payload).catch((error) => {
          console.warn('[earth] 同步问答页状态失败：', error)
        })
        return
      }

      if (type === 'hello') {
        this.linked = true
        return
      }

      if (type === 'init') {
        this.linked = true
        if (payload && typeof payload === 'object') {
          if (payload.theme !== undefined) this.setTheme(payload.theme)
          if (payload.conversationId !== undefined && payload.conversationId !== null) {
            this.conversationId = String(payload.conversationId)
          }
        }
        return
      }

      if (type === 'command') {
        this.linked = true
        if (!payload || typeof payload !== 'object') {
          this.postError('command 载荷格式不正确', false)
          return
        }
        const command = payload.command && typeof payload.command === 'object'
          ? payload.command
          : payload
        const commandId = payload.commandId !== undefined
          ? payload.commandId
          : command.commandId
        this.applyCommand(command, commandId)
        return
      }

      if (type === 'clear') {
        this.linked = true
        const commandId = payload && typeof payload === 'object' ? payload.commandId : payload
        this.applyCommand({ type: 'clear' }, commandId)
        return
      }

      if (type === 'theme') {
        this.linked = true
        const value = payload && typeof payload === 'object' ? payload.theme : payload
        this.setTheme(value)
        return
      }

      if (type === 'conversation') {
        this.linked = true
        // 只记录会话标识；聊天页负责 clear / 回放，避免重复清空。
        if (payload && typeof payload === 'object') {
          const id = payload.conversationId !== undefined ? payload.conversationId : payload.id
          this.conversationId = id === undefined || id === null ? null : String(id)
        } else {
          this.conversationId = payload === undefined || payload === null ? null : String(payload)
        }
      }
    },

    async applyCommand (command, commandId) {
      try {
        if (!command || typeof command !== 'object') {
          throw new Error('命令格式不正确')
        }

        const type = String(command.type || '').toLowerCase()
        let applyViewAfter = false
        let targetView = null

        if (type === 'replace') {
          const nextView = command.view && typeof command.view === 'object'
            ? command.view
            : null

          if (nextView && nextView.mode) this.switchMode(nextView.mode)

          this.title = command.title !== undefined && command.title !== null
            ? String(command.title)
            : '数字地球'
          this.features = this.normalizeFeatures(command.features)
          this.legend = buildLegend(this.features, this.theme)
          this.view = nextView
          this.selectedId = null
          this.renderToken += 1

          targetView = nextView || { fit: true }
          applyViewAfter = true
        } else if (type === 'view') {
          const nextView = this.extractView(command)
          if (nextView.mode) this.switchMode(nextView.mode)
          this.view = nextView
          targetView = nextView
          applyViewAfter = true
        } else if (type === 'clear') {
          this.features = []
          this.legend = []
          this.view = null
          this.selectedId = null
          this.renderToken += 1
        } else {
          throw new Error('未知的命令类型：' + (command.type || '空'))
        }

        await this.$nextTick()

        if (applyViewAfter && !this.applySceneView(targetView)) {
          throw new Error('地图场景尚未就绪')
        }

        this.postToParent('rendered', {
          commandId: commandId === undefined ? null : commandId,
          mode: this.mode,
          count: this.features.length
        })
      } catch (error) {
        console.error('[earth] 执行命令失败：', error)
        this.postError(error && error.message ? error.message : '执行命令失败', false)
      }
    },

    switchMode (next) {
      const target = String(next || '').toLowerCase()
      if (target !== '3d' && target !== '2d') return false
      if (target === this.mode) return false
      if (target === '3d' && !this.capabilities.cesium) return false
      if (target === '2d' && !this.capabilities.ol) return false
      this.mode = target
      this.selectedId = null
      return true
    },

    extractView (command) {
      const view = {}
      if (command.view && typeof command.view === 'object') {
        Object.keys(command.view).forEach((key) => {
          view[key] = command.view[key]
        })
      } else {
        ['fit', 'lon', 'lat', 'height', 'zoom', 'mode', 'fly'].forEach((key) => {
          if (command[key] !== undefined) view[key] = command[key]
        })
      }
      if (command.mode !== undefined && view.mode === undefined) view.mode = command.mode
      return view
    },

    applySceneView (view) {
      const scene = this.$refs.scene
      if (!scene || typeof scene.applyView !== 'function') return false
      scene.applyView(view)
      return true
    },

    resizeScene () {
      const scene = this.$refs.scene
      if (!scene || typeof scene.resize !== 'function') return
      try {
        scene.resize()
      } catch (error) {
        console.warn('[earth] 场景尺寸刷新失败：', error)
      }
    },

    normalizeFeatures (input) {
      if (!Array.isArray(input)) return []
      const max = this.config.maxFeatures || 500
      if (input.length > max) {
        console.warn('[earth] 要素数量 ' + input.length + ' 超过上限，仅渲染前 ' + max + ' 个。')
      }

      const out = []
      const limit = Math.min(input.length, max)
      for (let index = 0; index < limit; index++) {
        const raw = input[index]
        if (!raw || typeof raw !== 'object') continue

        const feature = {
          id: raw.id !== undefined && raw.id !== null ? String(raw.id) : 'feature-' + (index + 1),
          name: typeof raw.name === 'string' ? raw.name : '',
          kind: typeof raw.kind === 'string' ? raw.kind : '',
          city: typeof raw.city === 'string' ? raw.city : '',
          style: typeof raw.style === 'string'
            ? raw.style
            : (typeof raw.styleKey === 'string' ? raw.styleKey : '')
        }

        const lon = finiteOrNull(raw.lon)
        const lat = finiteOrNull(raw.lat)
        if (lon !== null) feature.lon = lon
        if (lat !== null) feature.lat = lat
        if (raw.coords !== undefined) feature.coords = raw.coords
        if (raw.coordinates !== undefined) feature.coordinates = raw.coordinates

        out.push(feature)
      }
      return out
    },

    refreshLegend () {
      this.legend = buildLegend(this.features, this.theme)
    },

    legendSwatchStyle (item) {
      if (!item || typeof item !== 'object') return {}
      const color = safeColor(item.color)
      return color ? { background: color } : {}
    },

    onSelect (payload) {
      if (!payload || typeof payload !== 'object') return
      this.selectedId = payload.id !== undefined ? payload.id : null
      this.renderToken += 1
      this.postToParent('select', payload)
    },

    onSceneError (message) {
      const text = typeof message === 'string' && message
        ? message
        : '地图场景初始化失败'
      console.warn('[earth] ' + this.mode + ' 场景错误：' + text)

      if (this.fatalError) return

      if (this.mode === '3d' && this.capabilities.ol) {
        this.mode = '2d'
        this.selectedId = null
        this.showToast('3D 初始化失败，已切换 2D')
        return
      }

      this.fail(text)
    }
  }
}
</script>
