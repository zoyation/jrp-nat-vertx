const { defineConfig } = require('@vue/cli-service')

// 设置默认API URL
const API_URL = process.env.VUE_APP_API_URL || 'http://localhost:8000/jrp-client'

console.log('Using API URL:', API_URL)

module.exports = defineConfig({
  transpileDependencies: true,
  publicPath: 'auto',
  outputDir: '../jrp-client/src/main/resources/dist',
  devServer: {
    host: '0.0.0.0',
    port: 0,
    proxy: {
      '/config': {
        target: API_URL,
        changeOrigin: true,
        logLevel: 'debug' // 启用代理调试日志
      }
    }
  }
 }
)
