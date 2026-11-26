const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  // 生产环境使用相对路径，便于以 /earth/index.html 被 iframe 引用。
  publicPath: process.env.NODE_ENV === 'production' ? './' : '/',
  // 构建产物直接输出到 Spring Boot 静态资源目录。
  outputDir: '../src/main/resources/static/earth',
  assetsDir: 'static',
  productionSourceMap: false,
  lintOnSave: false,
  devServer: {
    port: 8081,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8092',
        changeOrigin: true
      },
      '/map-assets': {
        target: 'http://127.0.0.1:8092',
        changeOrigin: true
      }
    }
  }
})
