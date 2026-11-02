<template>
    <div class="main-content">
        <el-card class="log-viewer-card">
            <!-- 标题和操作按钮 -->
            <div class="config-header">
                <h3 class="config-title">📄 客户端日志</h3>
                <div class="header-buttons">
                    <el-switch
                            v-model="autoScroll"
                            active-text="自动滚动"
                            class="auto-scroll-switch"
                    />
                    <el-button type="warning" @click="clearLog" class="action-btn">🧹 清空日志</el-button>
                    <el-button :type="live ? 'danger' : 'success'" @click="toggleLive" class="action-btn">
                        {{ live ? '⏸ 暂停实时日志' : '▶ 开始实时日志' }}
                    </el-button>
                </div>
            </div>

            <!-- 实时日志输出区域 -->
            <div ref="logContainer" class="log-console">
                <div v-if="!live && !logContent" class="log-placeholder">
                    点击「开始实时日志」查看客户端实时输出
                </div>
                <pre v-else class="log-content">{{ logContent }}</pre>
            </div>

            <!-- 历史日志文件列表 -->
            <div class="history-section">
                <div class="history-header">
                    <h3 class="section-title">📦 历史日志文件</h3>
                    <el-button type="primary" link @click="loadLogFiles">🔄 刷新列表</el-button>
                </div>
                <el-table
                        :data="logFiles"
                        style="width: 100%"
                        size="small"
                        border
                        class="log-file-table"
                        max-height="240"
                        empty-text="暂无历史日志文件"
                >
                    <el-table-column prop="name" label="文件名" min-width="280" show-overflow-tooltip/>
                    <el-table-column label="大小" width="120" align="center">
                        <template #default="{ row }">{{ formatSize(row.size) }}</template>
                    </el-table-column>
                    <el-table-column label="最后修改时间" width="200" align="center">
                        <template #default="{ row }">{{ formatTime(row.lastModified) }}</template>
                    </el-table-column>
                    <el-table-column label="操作" width="120" align="center">
                        <template #default="{ row }">
                            <el-button type="primary" size="small" @click="downloadLog(row.name)">下载</el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
        </el-card>
    </div>
</template>

<script setup>
    import {nextTick, onMounted, onUnmounted, ref} from 'vue';
    import {ElMessage} from 'element-plus';
    import apiService from '@/services/api';

    const live = ref(false);
    const logContent = ref('');
    const autoScroll = ref(true);
    const logFiles = ref([]);
    const logContainer = ref();

    let eventSource = null;
    // 最多保留512KB日志内容，防止DOM过大
    const MAX_LOG_LENGTH = 512 * 1024;

    onMounted(() => {
        loadLogFiles();
    });

    onUnmounted(() => {
        stopLive();
    });

    function toggleLive() {
        if (live.value) {
            stopLive();
        } else {
            startLive();
        }
    }

    function startLive() {
        if (eventSource) {
            return;
        }
        eventSource = new EventSource(apiService.getLogTailUrl());
        eventSource.onmessage = (e) => {
            appendLog(e.data);
        };
        // SSE连接异常时由浏览器自动重连，这里仅记录日志，不打断
        eventSource.onerror = () => {
            console.warn('实时日志连接异常，正在尝试重连...');
        };
        live.value = true;
    }

    function stopLive() {
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
        live.value = false;
    }

    function appendLog(text) {
        if (!text) {
            return;
        }
        logContent.value += text;
        // 超长时从头部截断
        if (logContent.value.length > MAX_LOG_LENGTH) {
            logContent.value = logContent.value.slice(-MAX_LOG_LENGTH);
        }
        if (autoScroll.value) {
            nextTick(() => {
                if (logContainer.value) {
                    logContainer.value.scrollTop = logContainer.value.scrollHeight;
                }
            });
        }
    }

    function clearLog() {
        logContent.value = '';
    }

    function loadLogFiles() {
        apiService.getLogFiles().then((data) => {
            logFiles.value = data || [];
        }).catch(() => {
            ElMessage({type: 'error', message: '获取历史日志文件列表失败'});
        });
    }

    function downloadLog(name) {
        const url = apiService.getLogDownloadUrl(name);
        const a = document.createElement('a');
        a.href = url;
        a.download = name.split('/').pop();
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    function formatSize(size) {
        if (size === null || size === undefined) {
            return '-';
        }
        if (size < 1024) {
            return size + ' B';
        }
        if (size < 1024 * 1024) {
            return (size / 1024).toFixed(2) + ' KB';
        }
        return (size / 1024 / 1024).toFixed(2) + ' MB';
    }

    function formatTime(ts) {
        if (!ts) {
            return '-';
        }
        const d = new Date(ts);
        const pad = (n) => (n < 10 ? '0' + n : '' + n);
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
            + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    }
</script>

<style scoped>
    .main-content {
        width: 100%;
    }

    .log-viewer-card {
        width: 100%;
        background: rgba(255, 255, 255, 0.98);
        border-radius: 12px;
        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    }

    .config-header {
        margin-bottom: 10px;
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    .config-title {
        font-size: 18px;
        font-weight: bold;
        color: #2c3e50;
        margin: 0;
        padding: 10px 0;
        border-bottom: 2px solid #667eea;
    }

    .header-buttons {
        display: flex;
        gap: 12px;
        align-items: center;
    }

    .auto-scroll-switch {
        margin-right: 8px;
    }

    .action-btn {
        min-width: 140px;
        font-weight: bold;
        transition: all 0.3s ease;
    }

    .action-btn:hover {
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    }

    /* 实时日志输出区域 */
    .log-console {
        height: calc(100vh - 560px);
        min-height: 200px;
        background: #1e1e1e;
        border-radius: 6px;
        padding: 12px;
        overflow-y: auto;
        box-sizing: border-box;
        border: 1px solid #333;
        margin-bottom: 16px;
    }

    .log-content {
        margin: 0;
        font-family: 'Consolas', 'Courier New', 'Menlo', monospace;
        font-size: 13px;
        line-height: 1.6;
        color: #d4d4d4;
        white-space: pre-wrap;
        word-break: break-all;
    }

    .log-placeholder {
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #6b6b6b;
        font-size: 14px;
    }

    /* 历史日志文件列表 */
    .history-section {
        text-align: left;
    }

    .history-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 8px;
    }

    .section-title {
        font-size: 16px;
        font-weight: bold;
        color: #2c3e50;
        margin: 0;
        padding-bottom: 8px;
        border-bottom: 2px solid #667eea;
        display: inline-block;
    }

    .log-file-table :deep(.el-table__header th) {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
        font-weight: bold;
    }
</style>
