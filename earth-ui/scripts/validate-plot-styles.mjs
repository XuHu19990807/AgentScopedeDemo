import { readFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Node 16 会把 earth-ui 下的 .js 当成 CommonJS；这里用 data: URL 把同一个
// style-catalog.js 当 ESM 加载，避免在 Node 16 上直接 import 失败。
const catalogSource = readFileSync(new URL('../src/plot/style-catalog.js', import.meta.url), 'utf8')
const { validatePlotStyles } = await import(
  'data:text/javascript;base64,' + Buffer.from(catalogSource, 'utf8').toString('base64')
)

const here = dirname(fileURLToPath(import.meta.url))
const publicDir = resolve(here, '..', 'public')
const stylesPath = join(publicDir, 'plot-styles.json')
const iconDir = join(publicDir, 'plot-icons')

const errors = []
let data
try {
  data = JSON.parse(readFileSync(stylesPath, 'utf8'))
} catch (error) {
  console.error('无法读取 ' + stylesPath + '：' + error.message)
  process.exit(1)
}

errors.push(...validatePlotStyles(data))

const styles = data.styles || {}
Object.keys(styles).forEach((id) => {
  const item = styles[id]
  if (!item || typeof item !== 'object') return
  if (item.icon) {
    // icon 可以是完整 URL / 根路径 / data URI；只有相对文件名才检查本地 plot-icons。
    const remote = /^(?:https?:|data:|\/)/i.test(item.icon)
    if (!remote && !existsSync(join(iconDir, item.icon))) {
      errors.push('样式 ' + id + ' 的图标不存在：plot-icons/' + item.icon)
    }
  }
  if (item.color && !/^#(?:[0-9a-f]{3,8})$/i.test(item.color)) {
    errors.push('样式 ' + id + ' 的 color 建议使用十六进制：' + item.color)
  }
})

const aliases = data.aliases || {}
Object.keys(aliases).forEach((from) => {
  if (!styles[aliases[from]]) {
    errors.push('别名 ' + from + ' 指向不存在的样式：' + aliases[from])
  }
})

if (errors.length) {
  console.error('plot-styles.json 校验失败：')
  errors.forEach((message) => console.error(' - ' + message))
  process.exit(1)
}

console.log('plot-styles.json 校验通过：' + Object.keys(styles).length + ' 个样式，' +
  Object.keys(aliases).length + ' 个别名')
