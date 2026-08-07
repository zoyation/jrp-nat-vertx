<template>
  <div id="app" class="page-container">
    <!-- 公共头部 -->
    <div class="app-header">
      <h1 class="app-title">JRP 内网穿透管理</h1>
      <p class="app-desc">JRP（Java Remote Proxy），高性能内网穿透工具，java-tony（公众号）开发，包括服务端和客户端，
      支持多种协议转发，包括HTTP、HTTPS、TCP、UDP以及SOCKS代理等，支持P2P打洞。
      使用JRP，您可以在外轻松安全访问内网服务，实现远程访问、调试和部署。</p>
    </div>

    <!-- 选项卡切换 -->
    <el-tabs v-model="activeTab" type="border-card" class="main-tabs">
      <el-tab-pane name="proxy">
        <template #label>
          <span class="tab-label">🌐 内网穿透代理配置</span>
        </template>
        <ProxyConfigForm />
      </el-tab-pane>
      <el-tab-pane name="p2p">
        <template #label>
          <span class="tab-label">🔗 用户端P2P代理配置</span>
        </template>
        <P2PConfigForm />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import ProxyConfigForm from './components/ProxyConfigForm.vue'
import P2PConfigForm from './components/P2PConfigForm.vue'

export default {
  name: 'App',
  components: {
    ProxyConfigForm,
    P2PConfigForm
  },
  data() {
    return {
      activeTab: 'proxy'
    }
  }
}
</script>

<style>
#app {
  font-family: Avenir, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #2c3e50;
}

/* 页面容器 - 全屏布局 */
.page-container {
  width: 100vw;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
  box-sizing: border-box;
}

body {
  overflow: hidden;
}

/* 公共头部 */
.app-header {
  text-align: center;
  margin-bottom: 20px;
}

.app-title {
  font-size: 28px;
  font-weight: bold;
  color: #fff;
  margin: 0 0 8px 0;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

.app-desc {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
  margin: 0;
}

/* 选项卡样式 */
.main-tabs {
  width: calc(100vw - 60px);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
}

.main-tabs :deep(.el-tabs__header) {
  background: #f5f7fa;
  margin: 0;
  border-bottom: 2px solid #e4e7ed;
}

.main-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.main-tabs :deep(.el-tabs__item) {
  font-size: 15px;
  font-weight: 500;
  padding: 0 30px;
  height: 48px;
  line-height: 48px;
  color: #606266;
  transition: all 0.3s ease;
}

.main-tabs :deep(.el-tabs__item.is-active) {
  color: #667eea;
  font-weight: bold;
}

.main-tabs :deep(.el-tabs__content) {
  padding: 0;
}

.tab-label {
  font-size: 15px;
}
</style>
