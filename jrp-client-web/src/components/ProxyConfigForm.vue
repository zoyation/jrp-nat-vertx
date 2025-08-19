<template>
    <div>
        <div v-if="isLoading" class="loading-indicator">
            Loading configuration...
        </div>
        <div v-else-if="error" class="error-message">
            {{ error }}
        </div>
        <div v-else>
            <el-card class="proxy-config-form">
                <!-- 为表单添加 ref 和 rules -->
                <el-form
                        ref="proxyConfigFormRef"
                        :model="configData"
                        :rules="rules"
                        label-width="0px"
                >
                    <!-- Remote Proxies -->
                    <el-card class="proxies-section">
                        <h2>内网穿透客户端-穿透配置</h2>
                        <div>内网穿透状态：<span v-if="configData.success&&!changeFlag" :style="configData.success?'color:green':'color:red'">{{configData.message}}</span></div>
                        <el-table
                                :data="configData.remote_proxies"
                                style="width: 100%"
                                class="proxy-table"
                        >
                            <el-table-column prop="name" label="服务名称">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`remote_proxies[${$index}].name`"
                                            :rules="rules.name"
                                            
                                    >
                                        <el-input v-model="row.name" size="large" class="table-input"/>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column prop="proxy_pass" label="服务地址">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`remote_proxies[${$index}].proxy_pass`"
                                            :rules="rules.proxy_pass"
                                            
                                    >
                                        <el-input v-model="row.proxy_pass" size="large" class="table-input"/>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column prop="type" label="穿透类型">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`remote_proxies[${$index}].type`"
                                            :rules="rules.type"
                                            
                                    >
                                        <el-select v-model="row.type" size="large" class="table-select">
                                            <el-option label="HTTP协议" value="HTTP"/>
                                            <el-option label="TCP协议" value="TCP"/>
                                            <el-option label="UDP协议" value="UDP"/>
                                        </el-select>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column prop="remote_port" label="穿透外网访问端口">
                                <template #default="{ row, $index }">
                                    <el-form-item
                                            :prop="`remote_proxies[${$index}].remote_port`"
                                            :rules="rules.remote_port"
                                            
                                    >
                                        <el-input v-model.number="row.remote_port" type="number" :min="0" size="large" class="table-input"/>
                                    </el-form-item>
                                </template>
                            </el-table-column>
                            <el-table-column label="穿透外网访问地址">
                                <template #default="{ row }">
                                    <span v-if="configData.success&&row.remote_port&&!changeFlag">
                                        <a 
                                            :href="(row.type=='HTTP'?'http://':'tcp://') + configData.remoteHost + ':' + row.remote_port"
                                            target="_blank"
                                            style="color: #409eff; text-decoration: underline;"
                                        >
                                            {{row.type=='HTTP'?'http://':'tcp://'}}{{configData.remoteHost+':'+row.remote_port}}
                                        </a>
                                    </span>
                                </template>
                            </el-table-column>
                            <el-table-column label="操作" width="100">
                                <template #default="{ $index }">
                                    <el-button
                                            type="danger"
                                            size="large"
                                            @click="removeProxy($index)"
                                    >删除
                                    </el-button>
                                </template>
                            </el-table-column>
                        </el-table>
                        <div style="margin-top: 20px; text-align: right">
                            <el-button type="primary" @click="addProxy">添加配置</el-button>
                            <el-button type="primary" @click="resetConfig">还原配置</el-button>
                            <el-button type="primary" @click="saveConfig">保存并启用穿透</el-button>
                        </div>
                    </el-card>
                </el-form>
            </el-card>
        </div>
    </div>
</template>

<script setup>
    import {ref, reactive, onMounted, onUnmounted} from 'vue';
    import { ElMessage, ElMessageBox } from 'element-plus'
    import apiService from '@/services/api';

    let statusInterval;

    // 添加表单引用
    const proxyConfigFormRef = ref();

    onMounted(() => {
      fetchConfig();
        statusInterval = setInterval(updateStatus, 2000);
    });

    onUnmounted(() => {
      clearInterval(statusInterval);
    });

    const configData = reactive({
      success: false,
      message: '',
      remoteHost: '',
      remote_proxies: [
        {
          name: '',
          type: 'HTTP',
          remote_port: null,
          proxy_pass: ''
        }
      ]
    });

    // 添加表单校验规则
    const rules = {
        name: [
            { required: true, message: '请输入服务名称', trigger: 'blur' },
            { min: 1, max: 50, message: '长度应在 1 到 50 个字符之间', trigger: 'blur' }
        ],
        proxy_pass: [
            { required: true, message: '请输入服务地址', trigger: 'blur' },
            { validator: validateProxyPass, trigger: 'blur' }
        ],
        type: [
            { required: true, message: '请选择穿透类型', trigger: 'change' }
        ],
        remote_port: [
            { required: true, message: '请输入外网访问端口', trigger: 'blur' },
            { type: 'number', min: 1, max: 65535, message: '端口应在 1-65535 之间', trigger: 'blur' }
        ]
    };

    // 自定义校验函数 - 服务地址格式校验
    function validateProxyPass(rule, value, callback) {
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

    const isLoading = ref(false);
    const error = ref(null);
    let changeFlag = ref(false);

    function updateStatus() {
        apiService.status()
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
        const response = await apiService.getConfig();
        configData.remote_proxies = response;
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
            apiService.getConfig().then((response)=>{
                configData.remote_proxies = response;
                ElMessage({
                    type: 'success',
                    message: '还原配置成功',
                })
                configData.success = false;
                configData.message = '';
                changeFlag.value=false;
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
      configData.remote_proxies.push({
        name: '',
        type: 'HTTP',
        remote_port: null,
        proxy_pass: ''
      });
    }

    function removeProxy(index) {
      configData.remote_proxies.splice(index, 1);
    }

    // 修改保存函数以包含表单校验
    function saveConfig() {
        proxyConfigFormRef.value.validate((valid) => {
            if (valid) {
                ElMessageBox.confirm(
                    '确定要保存配置吗？',
                    'Warning',
                    {
                        confirmButtonText: '确定',
                        cancelButtonText: '取消',
                        type: 'warning',
                    }
                ).then(() => {
                    apiService.saveConfig(configData.remote_proxies)
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
</script>

<style scoped>
    :deep(.el-table__header th) {
        font-size: 16px; /* 可根据需要调整大小 */
        font-weight: bold;
    }
    .proxy-config-form {
      max-width: max(calc(100vw - 1000px),1200px);
      margin: 20px auto;
    }

    .proxies-section {
      position: relative;
    }

    .proxy-table {
      min-height: 300px;
      max-height: 70vh;
      overflow-y: auto;
    }

    .proxies-section .el-card__body {
      padding-bottom: 60px;
    }

    .proxies-section > div[style*="margin-top"] {
      position: absolute;
      bottom: 10px;
      right: 10px;
      margin-top: 0 !important;
    }

    .proxy-group {
      margin-bottom: 20px;
    }

    .remove-btn {
      margin-top: 10px;
    }

    .add-btn {
      margin-top: 10px;
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
</style>