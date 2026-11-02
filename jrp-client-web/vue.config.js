const { defineConfig } = require('@vue/cli-service')

// 设置默认API URL
const API_URL = process.env.VUE_APP_API_URL || 'http://localhost:8000/jrp-client'

console.log('Using API URL:', API_URL)

module.exports = defineConfig({
  transpileDependencies: true,
  publicPath: 'auto',
  outputDir: '../jrp-client/src/main/resources/dist',
  chainWebpack: config => {
    // Vue 3.5 esm-bundler 需要显式定义该编译期特性标志，
    // @vue/cli-service 5.0 仅定义了 __VUE_OPTIONS_API__ 和 __VUE_PROD_DEVTOOLS__
    config.plugin('feature-flags').tap(args => {
      const flags = args[0] || {}
      flags.__VUE_PROD_HYDRATION_MISMATCH_DETAILS__ = 'false'
      return args
    })
  },
  devServer: {
    client: {
      overlay: {
        runtimeErrors: (error) => {
          const ignoreErrors = [
            "ResizeObserver loop limit exceeded",
            "ResizeObserver loop completed with undelivered notifications.",
          ];
          if (ignoreErrors.includes(error.message)) {
            return false; // 返回 false 表示不在遮罩层显示
          }
          return true;
        },
      },
    },
    host: '0.0.0.0',
    port: 0,
    compress: false,
    proxy: {
      '/config': {
        target: API_URL,
        changeOrigin: true,
        logLevel: 'debug' // 启用代理调试日志
      },
      '/log': {
        target: API_URL,
        changeOrigin: true,
        timeout: 0,
        proxyTimeout: 0,
        logLevel: 'debug' // 启用代理调试日志
      }
    }
  }
 }
)
