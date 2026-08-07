<template>
    <div v-if="isLoading" class="loading-indicator">
            Loading configuration...
        </div>
        <div v-else-if="error" class="error-message">
            {{ error }}
        </div>
        <div v-else class="main-content">
            <!-- 配置表单区域 -->
            <el-card class="proxy-config-form">
                <!-- 为表单添加 ref 和 rules -->
                <el-form
                        ref="proxyConfigFormRef"
                        :model="configData"
                        :rules="rules"
                        label-width="0px"
                >
                         <!-- 标题和简介 -->
                        <div class="config-header">
                            <h3 class="config-title">⚙️ P2P内网访问配置（在外P2P访问内网服务）</h3>
                            <div class="header-buttons">
                                <div class="status-info">
                                    <span class="status-label">服务器连接状态：</span>
                                    <span v-if="configData.success&&!changeFlag"
                                          :class="configData.success ? 'status-success' : 'status-error'">
                                        {{configData.message}}
                                    </span>
                                </div>
                                <el-button type="primary" @click="updateStatus" :disabled="changeFlag" class="action-btn">
                                    🔄 刷新状态
                                </el-button>
                                <el-button type="primary" @click="addProxy" class="action-btn">➕ 添加配置</el-button>
                                <el-button type="warning" @click="resetConfig" class="action-btn">🔄 还原配置</el-button>
                                <el-button type="success" @click="saveConfig" class="action-btn">💾 保存并启用穿透</el-button>
                            </div>
                        </div>
                        
                        <el-table
                                ref="proxyTableRef"
                                :data="configData.user_proxies"
                                style="width: 100%"
                                class="proxy-table"
                        >
                            <el-table-column type="index" label="序号" width="60" align="center">
                            </el-table-column>
                            <el-table-column prop="name" label="服务名称">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`user_proxies[${$index}].name`"
                                            :rules="rules.name"

                                    >
                                        <el-input v-model="row.name" size="large" class="table-input"/>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column prop="type" label="穿透类型" width="180">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`user_proxies[${$index}].type`"
                                            :rules="rules.type"
                                    >
                                        <el-select v-model="row.type" size="large" class="table-select" @change="handleTypeChange($index)">
                                              <el-option label="HTTP端口映射" value="HTTP" title="将HTTP请求转发到指定端口"/>
                                              <el-option label="HTTPS端口映射" value="HTTPS" title="将HTTPS请求转发到指定端口"/>
                                              <el-option label="TCP端口映射" value="TCP" title="将TCP流量转发到指定端口"/>
                                              <el-option label="UDP端口映射" value="UDP" title="将UDP流量转发到指定端口"/>
                                              <el-option label="HTTP代理" value="HTTP_PROXY" title="使用HTTP协议进行代理转发"/>
                                              <el-option label="HTTPS代理" value="HTTPS_PROXY" title="使用HTTPS协议进行代理转发"/>
                                              <el-option label="SOCKS4代理" value="SOCKS4" title="使用SOCKS4协议进行代理转发"/>
                                              <el-option label="SOCKS5代理" value="SOCKS5" title="使用SOCKS5协议进行代理转发"/>
                                              <el-option label="智能代理" value="SMART_PROXY" title="同时支持HTTP代理、HTTPS代理、SOCKS4和SOCKS5代理"/>
                                        </el-select>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column prop="remote_port" label="穿透端口（服务端）" width="220">
                                <template #default="{ row, $index }">
                                    <el-form-item :prop="`user_proxies[${$index}].remote_port`" :rules="rules.remote_port">
                                        <el-input 
                                            v-model.number="row.remote_port" 
                                            type="number" 
                                            :min="0" 
                                            size="large" 
                                            class="table-input"
                                            placeholder="P2P打洞或转发访问"
                                        />
                                    </el-form-item>
                                </template>
                            </el-table-column>

                            <el-table-column prop="local_port" label="本地端口（P2P直连访问）" width="220">
                                <template #default="{ row, $index }">
                                    <el-form-item :prop="`user_proxies[${$index}].local_port`" :rules="rules.local_port">
                                        <el-input
                                            v-model.number="row.local_port"
                                            type="number"
                                            :min="0"
                                            size="large"
                                            class="table-input"
                                            placeholder="打洞后直连使用"
                                        />
                                    </el-form-item>
                                </template>
                            </el-table-column>

                             <el-table-column label="本地服务地址（打洞成功使用）">
                                <template #default="{ row }">
                                    <span v-if="configData.success&&row.local_port">
                                        <a v-if="(row.type=='HTTP'||row.type=='HTTPS')&&row.enable"
                                                :href="(row.type.toLowerCase()+'://') + '127.0.0.1:' + row.local_port"
                                                target="_blank"
                                                style="color: #409eff; text-decoration: underline;"
                                        >
                                            {{row.type.toLowerCase()+'://'}}{{configData.remoteHost+':'+row.remote_port}}
                                        </a>
                                        <div v-if="(row.type!='HTTP'&&row.type!='HTTPS')&&row.enable"
                                        >
                                            {{configData.remoteHost+':'+row.remote_port}}
                                        </div>
                                    </span>
                                </template>
                             </el-table-column>
                            <el-table-column label="穿透外网地址（打洞失败使用）">
                                <template #default="{ row }">
                                    <span v-if="configData.success&&row.remote_port&&!changeFlag">
                                        <a v-if="(row.type=='HTTP'||row.type=='HTTPS')&&row.enable"
                                                :href="(row.type.toLowerCase()+'://') + configData.remoteHost + ':' + row.remote_port"
                                                target="_blank"
                                                style="color: #409eff; text-decoration: underline;"
                                        >
                                            {{row.type.toLowerCase()+'://'}}{{configData.remoteHost+':'+row.remote_port}}
                                        </a>
                                        <div v-if="(row.type!='HTTP'&&row.type!='HTTPS')&&row.enable"
                                        >
                                            {{configData.remoteHost+':'+row.remote_port}}
                                        </div>
                                    </span>
                                </template>
                            </el-table-column>
                            <el-table-column label="启用状态" width="150" align="center">
                                <template #default="{ row }">
                                    <el-switch
                                        v-model="row.enable"
                                        active-text="启用"
                                        inactive-text="停用"
                                    />
                                </template>
                            </el-table-column>
                            <el-table-column label="操作" width="100">
                                <template #default="{ $index }">
                                    <el-button
                                            type="danger"
                                            @click="removeProxy($index)"
                                    >删除
                                    </el-button>
                                </template>
                            </el-table-column>
                        </el-table>
                        <!-- 穿透类型说明 - 放在最下方 -->
                        <div class="proxy-type-section">
                            <h3 class="section-title">📖 穿透类型说明</h3>
                            <div class="proxy-type-description">
                                <el-descriptions :column="3" border size="small">
                                    <el-descriptions-item label="HTTP端口映射">
                                        将HTTP请求转发到指定端口，支持按路径前缀路由到不同本地服务
                                    </el-descriptions-item>
                                    <el-descriptions-item label="HTTPS端口映射">
                                        将HTTPS请求转发到指定端口，支持按路径前缀路由到不同本地服务
                                    </el-descriptions-item>
                                    <el-descriptions-item label="TCP端口映射">
                                        将TCP流量转发到指定端口，适用于数据库、SSH等
                                    </el-descriptions-item>
                                    <el-descriptions-item label="UDP端口映射">
                                        将UDP流量转发到指定端口，适用于DNS、视频流等
                                    </el-descriptions-item>
                                    <el-descriptions-item label="HTTP代理">
                                        使用HTTP协议进行代理转发，无需填写本地地址
                                    </el-descriptions-item>
                                    <el-descriptions-item label="HTTPS代理">
                                        使用HTTPS协议进行代理转发，无需填写本地地址
                                    </el-descriptions-item>
                                    <el-descriptions-item label="SOCKS4代理">
                                        使用SOCKS4协议进行代理转发，无需填写本地地址
                                    </el-descriptions-item>
                                    <el-descriptions-item label="SOCKS5代理">
                                        使用SOCKS5协议进行代理转发，无需填写本地地址
                                    </el-descriptions-item>
                                    <el-descriptions-item label="智能代理" :span="3">
                                        同时支持HTTP代理、HTTPS代理、SOCKS4和SOCKS5代理进行正向代理穿透，无需填写本地地址
                                    </el-descriptions-item>
                                </el-descriptions>
                            </div>
                        </div>
                    </el-form>
            </el-card>
        </div>
</template>

<script setup>
    import {ref, reactive, onMounted, nextTick} from 'vue';
    import { ElMessage, ElMessageBox } from 'element-plus'
    import apiService from '@/services/api';

    //let statusInterval;

    // 添加表单引用
    const proxyConfigFormRef = ref();
    const proxyTableRef = ref();

    onMounted(() => {
      fetchConfig();
      updateStatus();
    });

    //onUnmounted(() => {
    //  clearInterval(statusInterval);
    //});

    const configData = reactive({
      success: false,
      message: '',
      remoteHost: '',
      user_proxies: [
        {
          name: '',
          type: 'HTTP',
          remote_port: null,
          local_port: null,
          routes: [],
          enable: true
        }
      ]
    });

    // 处理穿透类型变化
    function handleTypeChange(index) {
        const row = configData.user_proxies[index];
        // 非HTTP/HTTPS类型时，禁用路由规则
        if (!['HTTP', 'HTTPS'].includes(row.type)) {
            row.enable_route_rules = false;
        }
    }
    // 添加表单校验规则
    const rules = {
        name: [
            { required: true, message: '请输入服务名称', trigger: 'blur' },
            { min: 1, max: 50, message: '长度应在 1 到 50 个字符之间', trigger: 'blur' }
        ],
        proxy_pass: [
            { validator: validateProxyPass, trigger: 'blur' }
        ],
        type: [
            { required: true, message: '请选择穿透类型', trigger: 'change' }
        ],
        remote_port: [
            { validator: validatePort, trigger: 'blur' }
        ],
        local_port: [
            { validator: validatePort, trigger: 'blur' }
        ]
    };

    // 自定义校验函数 - 服务地址格式校验
    function validateProxyPass(rule, value, callback) {
        // 获取当前行的索引
        const index = parseInt(rule.field.match(/\[(\d+)\]/)[1]);
        const currentType = configData.user_proxies[index].type;
        const enableRouteRules = configData.user_proxies[index].enable_route_rules;

        // 如果是代理类型，则proxy_pass可以为空
        const proxyTypes = ['HTTP_PROXY', 'HTTPS_PROXY', 'SOCKS4', 'SOCKS5', 'SMART_PROXY'];
        if (proxyTypes.includes(currentType)) {
            return callback(); // 代理类型不需要校验proxy_pass
        }

        // 如果是HTTP/HTTPS类型且启用了路由规则，则proxy_pass可以为空
        if (['HTTP', 'HTTPS'].includes(currentType) && enableRouteRules) {
            return callback(); // 启用路由规则时不需要校验proxy_pass
        }

        // 非代理类型且未启用路由规则必须填写proxy_pass
        if (!value) {
            return callback(new Error('请输入服务地址'));
        }
        // 简单的URL格式校验
        const urlPattern = /^((https|http|ftp|rtsp|mms)?:\/\/)[^\s]+/;
        const ipPattern = /^(\d{1,3}\.){3}\d{1,3}:\d+$/;
        if (urlPattern.test(value) || ipPattern.test(value)) {
            callback();
        } else {
            callback(new Error('请输入有效的服务地址格式'));
        }
    }

    // 自定义校验函数 - 远程端口校验
    function validatePort(rule, value, callback) {
        // 如果为空，提示需要录入
        if (value === null || value === undefined || value === '') {
            return callback(new Error('请输入端口号'));
        }
        
        // 如果有值，则校验端口范围
        if (typeof value === 'number') {
            if (value < 1 || value > 65535) {
                return callback(new Error('端口应在 1-65535 之间'));
            }
        }
        
        callback();
    }

    const isLoading = ref(false);
    const error = ref(null);
    let changeFlag = ref(false);

    function updateStatus() {
        apiService.statusUser()
        .then((data) => {
            configData.success=data.success;
            configData.message=data.message;
            configData.remoteHost=data.remoteHost;
        });
    }

    async function fetchConfig() {
      isLoading.value = true;
      error.value = null;
      try {
        const response = await apiService.getUserConfig();
        configData.user_proxies = response;
      } catch (err) {
        error.value = 'Failed to load configuration';
        console.error('Error fetching config:', err);
      } finally {
        isLoading.value = false;
      }
    }

    function resetConfig() {
        ElMessageBox.confirm(
        '确定要还原配置吗？',
        'Warning',
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning',
        }
        ).then(() => {
            apiService.getUserConfig().then((response)=>{
                configData.user_proxies = response;
                ElMessage({
                    type: 'success',
                    message: '还原配置成功',
                })
                configData.success = false;
                configData.message = '';
                changeFlag.value=false;
                updateStatus();
            }).catch(() => {
                ElMessage({
                type: 'info',
                message: '还原配置失败',
                })
            });
        }).catch(() => {
          ElMessage({
            type: 'info',
            message: '已取消还原配置',
          })
        });
    }

    function addProxy() {
      changeFlag.value = true;
      configData.user_proxies.push({
        name: '',
        type: 'HTTP',
        remote_port: null,
        proxy_pass: '',
        routes: [],
        enable_route_rules: false,
        enable_p2p: false,
        enable: true
      });
      
      // 使用 nextTick 等待 DOM 更新后滚动到底部
      nextTick(() => {
        setTimeout(() => {
          if (proxyTableRef.value) {
            // 尝试多种可能的滚动容器选择器
            const selectors = [
              '.el-table__body-wrapper',
              '.el-scrollbar__wrap',
              '.el-table .el-table__body-wrapper'
            ];
            
            let scrollContainer = null;
            for (const selector of selectors) {
              scrollContainer = proxyTableRef.value.$el.querySelector(selector);
              if (scrollContainer && scrollContainer.scrollHeight > scrollContainer.clientHeight) {
                break;
              }
            }
            
            if (scrollContainer) {
              // 强制滚动到底部
              scrollContainer.scrollTop = scrollContainer.scrollHeight;
              // 再次确认滚动位置
              requestAnimationFrame(() => {
                scrollContainer.scrollTop = scrollContainer.scrollHeight;
              });
            }
          }
        }, 100);
      });
    }

    function removeProxy(index) {
      configData.user_proxies.splice(index, 1);
    }

    // 修改保存函数以包含表单校验
    function saveConfig() {
        proxyConfigFormRef.value.validate((valid) => {
            if (valid) {
                // 校验穿透端口是否重复
                const portValidationResult = validateDuplicatePorts();
                if (!portValidationResult.valid) {
                    ElMessage({
                        type: 'error',
                        message: portValidationResult.message,
                    });
                    return false;
                }

                // 校验路由规则配置
                const routeValidationResult = validateRouteRules();
                if (!routeValidationResult.valid) {
                    ElMessage({
                        type: 'error',
                        message: routeValidationResult.message,
                    });
                    return false;
                }

                ElMessageBox.confirm(
                    '确定要保存配置吗？',
                    'Warning',
                    {
                        confirmButtonText: '确定',
                        cancelButtonText: '取消',
                        type: 'warning',
                    }
                ).then(() => {
                    apiService.saveUserConfig(configData.user_proxies)
                    .then(()=>{
                        configData.success = false;
                        configData.message = '';
                        changeFlag.value = true;
                        ElMessage({
                            type: 'success',
                            message: '保存配置成功',
                        });
                        configData.success = false;
                        configData.message = '';
                        changeFlag.value=false;
                        setTimeout(() => {
                            fetchConfig()
                            updateStatus();
                        },3000);
                    }).catch(() => {
                        ElMessage({
                        type: 'info',
                        message: '保存配置失败',
                        });
                    });
                }).catch(() => {
                    ElMessage({
                        type: 'info',
                        message: '已取消保存配置',
                    })
                });
            } else {
                ElMessage({
                    type: 'error',
                    message: '表单填写有误，请检查后重新提交',
                });
                return false;
            }
        });
    }

    // 校验穿透端口是否重复
    function validateDuplicatePorts() {
        const portMap = {};
        for (let i = 0; i < configData.user_proxies.length; i++) {
            const proxy = configData.user_proxies[i];
            if (proxy.remote_port !== null && proxy.remote_port !== undefined && proxy.remote_port !== '') {
                const key = proxy.remote_port;
                if (!portMap[key]) {
                    portMap[key] = [];
                }
                portMap[key].push({ index: i, type: proxy.type });
            }
        }
        for (const [port, entries] of Object.entries(portMap)) {
            if (entries.length > 1) {
                return {
                    valid: false,
                    message: `穿透端口 ${port} 重复，请为每条配置设置不同的端口`
                };
            }
        }
        return { valid: true, message: '' };
    }

    // 校验路由规则配置
    function validateRouteRules() {
        for (let i = 0; i < configData.user_proxies.length; i++) {
            const proxy = configData.user_proxies[i];
            // 如果是HTTP/HTTPS类型且启用了路由规则，必须有至少一条路由规则
            if (['HTTP', 'HTTPS'].includes(proxy.type) && proxy.enable_route_rules) {
                if (!proxy.routes || proxy.routes.length === 0) {
                    return {
                        valid: false,
                        message: `第${i + 1}条配置（${proxy.name || '未命名'}）启用了路由规则，请至少添加一条路由规则`
                    };
                }
            }
        }
        return { valid: true, message: '' };
    }
</script>

<style scoped>
    .main-content {
       width: 100%;
    }

    /* 配置表单样式 */
    .proxy-config-form {
        width: 100%;
        background: rgba(255, 255, 255, 0.98);
        border-radius: 12px;
        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
    }

    /* 卡片内标题和简介样式 */
    .header-in-card {
        padding-bottom: 10px;
        border-bottom: 2px solid #ebeef5;
    }

    .card-main-title {
        font-size: 24px;
        font-weight: bold;
        color: #2c3e50;
        margin: 0 0 12px 0;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
    }

    .card-intro {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        padding: 12px;
        background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
        border-radius: 6px;
        border-left: 3px solid #667eea;
    }

    .card-intro .intro-label {
        font-weight: bold;
        color: #2c3e50;
        font-size: 14px;
        white-space: nowrap;
    }

    .card-intro .intro-text {
        color: #34495e;
        line-height: 1.6;
        font-size: 13px;
    }

    /* 配置标题样式 */
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

    .status-info {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 16px;
        background: #f8f9fa;
        border-radius: 6px;
        margin-right: 8px;
    }

    /* 状态样式 */
    .status-label {
        font-weight: bold;
        color: #2c3e50;
    }

    .status-success {
        color: #67c23a;
        font-weight: bold;
    }

    .status-error {
        color: #f56c6c;
        font-weight: bold;
    }

    .refresh-btn {
        margin-left: auto;
    }

    /* 表格样式 */
    .proxy-table {
        height: calc(100vh - 410px);
        overflow-y: auto;
    }

    :deep(.el-table__header th) {
        font-size: 15px;
        font-weight: bold;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
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

    /* 调整表单元素样式 */
    :deep(.el-form-item) {
        margin-bottom: 0;
    }

    :deep(.el-form-item__content) {
        line-height: normal;
    }

    :deep(.el-form-item__error) {
        position: absolute;
        padding-top: 2px;
    }

    /* 添加表格内输入框和选择框的样式 */
    .table-input {
        width: 100%;
    }

    .table-select {
        width: 100%;
    }

    /* 穿透类型说明样式 */
    .proxy-type-section {
        margin-bottom: 0px;
        text-align: left;
    }

    .section-title {
        font-size: 16px;
        font-weight: bold;
        color: #2c3e50;
        margin: 0 0 10px 0;
        padding-bottom: 8px;
        border-bottom: 2px solid #667eea;
        display: inline-block;
    }

    .proxy-type-description {
        padding: 10px;
    }

    .proxy-type-description :deep(.el-descriptions__label) {
        font-weight: bold;
        width: 120px;
        background: #f5f7fa;
    }

    .proxy-type-description :deep(.el-descriptions__content) {
        color: #606266;
    }

    /* 加载和错误提示样式 */
    .loading-indicator,
    .error-message {
        text-align: center;
        padding: 50px;
        font-size: 18px;
        color: white;
    }

    .error-message {
        color: #ff6b6b;
        background: rgba(255, 255, 255, 0.9);
        border-radius: 8px;
        margin: 20px;
    }
</style>