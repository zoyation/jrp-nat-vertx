# P2P 直连功能规范文档

## 1. 功能概述

在现有的 WebSocket 中转架构基础上，增加 P2P 直连能力。当穿透配置启用 P2P 时，客户端尝试通过 NAT 打洞建立直连，打洞成功后使用本地反向代理实现直连访问，失败时回退到服务器中转模式。

## 2. 业务场景

### 场景 1: HTTP/HTTPS 穿透启用 P2P
1. 用户在前端配置穿透信息，勾选"启用 P2P"
2. 服务端保存配置，ClientProxy.enable_p2p = true
3. 客户端（用户模式）启动时，读取配置中 enable_p2p = true 的穿透
4. 客户端连接到服务器 P2P 打洞端口，发起打洞请求
5. 打洞成功：建立 P2P 直连隧道，监听本地访问端口
6. 打洞失败：回退到传统 WebSocket 中转模式
7. 访问方式：用户访问 127.0.0.1:本地访问端口，通过本地反向代理和 P2P 隧道直连到内网服务

### 场景 2: TCP 穿透启用 P2P
1. 类似 HTTP/HTTPS 场景
2. 建立 TCP P2P 隧道
3. 透明转发 TCP 数据流

### 场景 3: 打洞失败回退
1. P2P 打洞超时（默认 30 秒）
2. NAT 类型不支持打洞
3. 网络防火墙限制
4. 自动回退到 WebSocket 中转，对用户透明

## 3. 架构设计

### 3.1 整体架构

```
外网用户
    |
    v
访问方式选择
    ├─ 方式1: 127.0.0.1:本地端口 (用户模式客户端)
    │   └─ 本地反向代理 → P2P 隧道 → 内网服务 (直连)
    │
    └─ 方式2: 服务器外网端口 (传统模式)
        └─ 服务器中转 → WebSocket 隧道 → 内网服务
```

### 3.2 P2P 打洞协议

采用 STUN-like 打洞方式：
1. 客户端 A 和客户端 B 都连接到服务器打洞端口
2. 交换对方的公网 IP:Port
3. 同时向对方发送 UDP 包
4. 成功建立 P2P 连接
5. 使用心跳维持连接

### 3.3 组件设计

#### 服务器端新增组件
- **P2PServerVerticle**: 处理 P2P 打洞请求，维护 P2P 会话
- **P2PSessionManager**: 管理 P2P 会话状态
- **P2P 端口监听**: 新增 `p2p-port` 配置参数（默认 3000）

#### 客户端新增组件
- **P2PClientManager**: 管理 P2P 打洞和连接
- **P2PTunnel**: P2P 隧道封装，处理数据传输
- **UserModeStartup**: 用户模式启动入口
- **LocalProxyServer**: 本地反向代理服务器，监听本地访问端口

### 3.4 消息协议设计

#### P2P 注册消息（客户端 → 服务器）
```json
{
  "type": "p2p_register",
  "client_id": "client-uuid",
  "proxy_id": "proxy-uuid",
  "local_ip": "192.168.1.100",
  "local_port": 8080
}
```

#### P2P 打洞请求（客户端 → 服务器）
```json
{
  "type": "p2p_hole_punch",
  "client_id": "client-uuid",
  "proxy_id": "proxy-uuid",
  "target_client_id": "target-uuid"
}
```

#### P2P 打洞响应（服务器 → 客户端）
```json
{
  "type": "p2p_hole_punch_response",
  "target_public_ip": "1.2.3.4",
  "target_public_port": 50000
}
```

#### P2P 数据传输（点对点）
```
[P2P_DATA][proxy_id][seq][data]
```

## 4. 数据模型修改

### 4.1 ClientProxy 模型（已有字段，确认使用）
```java
// d:\IdeaProjects\java-tony\jrp-nat-vertx\jrp-common\src\main\java\com\tony\jrp\common\model\ClientProxy.java
public class ClientProxy {
    // 现有字段...
    private boolean enable_p2p;  // 已存在，无需修改
}
```

### 4.2 配置参数新增

#### 服务器端配置（jrp-server/src/main/resources/application.yml）
```yaml
vertx:
  jrp:
    # 现有配置...
    p2p-port: 3000              # P2P 打洞端口（新增）
    p2p-timeout: 30             # P2P 打洞超时时间（秒）（新增）
```

#### 客户端配置（jrp-client/src/main/resources/application.yml）
```yaml
vertx:
  jrp:
    # 现有配置...
    p2p-port: 3000              # P2P 打洞端口（新增）
    user-mode: false            # 是否启用用户模式启动（新增）
    user-mode-port-start: 5000  # 用户模式本地端口起始范围（新增）
    user-mode-port-end: 6000    # 用户模式本地端口结束范围（新增）
    p2p-reconnect-times: 3      # P2P 连接重试次数（新增）
```

## 5. 技术实现方案

### 5.1 前端修改（jrp-client-web）

#### 5.1.1 表单字段增强
- 文件：`jrp-client-web/src/components/ProxyForm.vue`
- 修改：在穿透配置表单中增加"启用 P2P"复选框
- 位置：在"启用路由规则"复选框下方
- 逻辑：仅 HTTP/HTTPS/TCP/UDP 类型可勾选 P2P

#### 5.1.2 代理列表显示
- 文件：`jrp-client-web/src/views/ProxyList.vue`
- 修改：列表中增加"是否启用 P2P"列
- 显示：显示勾选图标或文本"是/否"

### 5.2 服务器端修改

#### 5.2.1 配置类增强
- 文件：`jrp-server/src/main/java/com/tony/jrp/server/config/JrpConfig.java`
- 修改：增加 P2P 相关配置字段
```java
private Integer p2pPort = 3000;
private Integer p2pTimeout = 30;
```

#### 5.2.2 新增 P2PServerVerticle
- 文件：`jrp-server/src/main/java/com/tony/jrp/server/verticle/P2PServerVerticle.java`（新建）
- 功能：
  1. 监听 UDP P2P 端口
  2. 处理 P2P 注册请求
  3. 协助 NAT 打洞（交换公网地址）
  4. 维护 P2P 会话状态
  5. 处理 P2P 心跳保活

#### 5.2.3 P2PSessionManager 实现
- 文件：`jrp-server/src/main/java/com/tony/jrp/server/manager/P2PSessionManager.java`（新建）
- 功能：
  1. 存储 P2P 会话信息（ConcurrentHashMap）
  2. 管理会话生命周期
  3. 清理过期会话
  4. 提供会话查询接口

#### 5.2.4 ProxyServerManager 修改
- 文件：`jrp-server/src/main/java/com/tony/jrp/server/service/impl/ProxyServerManager.java`
- 修改：
  1. 部署 P2PServerVerticle（在 WebSocket 注册 Verticle 之后）
  2. 从配置读取 p2pPort
  3. 处理 P2P 相关消息

### 5.3 客户端修改

#### 5.3.1 配置类增强
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/config/JrpClientConfig.java`
- 修改：增加用户模式和 P2P 配置字段
```java
private boolean userMode = false;
private Integer userModePortStart = 5000;
private Integer userModePortEnd = 6000;
private Integer p2pReconnectTimes = 3;
```

#### 5.3.2 新增 P2PClientManager
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/service/P2PClientManager.java`（新建）
- 功能：
  1. 连接服务器 P2P 端口
  2. 发起 P2P 注册
  3. 执行 NAT 打洞
  4. 建立 P2P 隧道
  5. 维护 P2P 心跳
  6. 处理 P2P 断线重连
  7. 打洞失败回调

#### 5.3.3 新增 P2PTunnel
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/tunnel/P2PTunnel.java`（新建）
- 功能：
  1. 封装 P2P 数据传输
  2. 处理数据分片和重组
  3. 实现可靠传输（ACK 机制）
  4. 管理连接状态

#### 5.3.4 新增 LocalProxyServer
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/server/LocalProxyServer.java`（新建）
- 功能：
  1. 监听本地访问端口（127.0.0.1）
  2. 接收本地用户的连接请求
  3. 转发请求到 P2P 隧道或 WebSocket 隧道
  4. 返回响应给本地用户

#### 5.3.5 ProxyClientManager 修改
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/service/impl/ProxyClientManager.java`
- 修改：
  1. 启动时检查 userMode 配置
  2. 如果是用户模式，为每个 enable_p2p = true 的代理启动 P2PClientManager
  3. P2P 打洞成功后，启动 LocalProxyServer
  4. P2P 打洞失败时，记录日志并跳过该代理
  5. 提供 P2P 状态查询接口

#### 5.3.6 主启动类修改
- 文件：`jrp-client/src/main/java/com/tony/jrp/client/JrpClientApplication.java`
- 修改：根据 userMode 配置选择启动模式
  - 传统模式：现有逻辑，仅启动 WebSocket 连接
  - 用户模式：启动 WebSocket 连接 + P2P 连接 + 本地代理服务器

### 5.4 P2P 打洞流程

```
客户端 A                        服务器                     客户端 B
   |                              |                           |
   |------ 1. Register P2P ------>|                           |
   |                              |                           |
   |                              |------ 2. Notify B ------->|
   |                              |                           |
   |                              |<----- 3. B Register -----|
   |                              |                           |
   |<----- 4. Send B's Info ------|                           |
   |                              |                           |
   |------ 5. Hole Punch to B ----|--------> (UDP) ---------->|
   |                              |                           |
   |                              |<-------- (UDP) ----------|
   |<------ (UDP) ----------------|                           |
   |                              |                           |
   |<----- 6. P2P Established ----|                           |
   |                              |                           |
   |<========================== P2P Data Tunnel ============>|
```

## 6. 边界条件和异常处理

### 6.1 网络异常
- **P2P 连接超时**: 超过配置的超时时间（默认 30 秒）未建立连接，回退到中转模式
- **P2P 连接断开**: 尝试重连配置次数（默认 3 次），仍失败则回退
- **本地端口占用**: 递增分配端口（start ~ end 范围内）
- **防火墙阻止**: 记录日志，提示用户检查防火墙设置

### 6.2 配置异常
- **无效配置**: P2P 端口范围错误，使用默认值
- **配置缺失**: user-mode 启用但未配置端口范围，使用默认值
- **代理冲突**: 同一代理同时启用 P2P 和路由规则，优先使用 P2P

### 6.3 NAT 类型限制
- **对称型 NAT**: 打洞成功率低，快速回退
- **锥型 NAT**: 打洞成功率高
- **无法识别 NAT**: 尝试打洞，超时后回退

### 6.4 并发控制
- **多用户访问**: LocalProxyServer 使用 Vert.x 事件循环处理并发
- **资源限制**: 限制最大 P2P 连接数（可配置）
- **内存管理**: P2P 会话超时自动清理

## 7. 数据流路径

### 7.1 P2P 直连路径（成功）
```
用户浏览器 → 127.0.0.1:本地端口
    → LocalProxyServer (HTTP 请求解析)
    → P2PTunnel (封装数据包)
    → UDP Socket (P2P 传输)
    → 内网服务
```

### 7.2 中转回退路径（P2P 失败）
```
用户浏览器 → 服务器外网端口:remote_port
    → TCPVerticle/HTTPVerticle
    → WebSocket 隧道
    → ProxyClientManager
    → TcpReverseProxyHandler
    → 内网服务
```

## 8. 预期结果

### 8.1 功能效果
1. 用户可在前端配置穿透时选择"启用 P2P"
2. 客户端支持用户模式启动（通过 application.yml 配置）
3. P2P 打洞成功时，用户通过本地端口直连访问，延迟更低
4. P2P 打洞失败时，自动回退到中转模式，不影响使用
5. 支持多个穿透同时启用 P2P

### 8.2 性能指标
- P2P 打洞成功率：> 80%（锥型 NAT 环境）
- P2P 连接延迟：< 50ms（局域网环境）
- P2P 回退超时：≤ 30 秒
- 本地代理吞吐量：≥ 100MB/s

### 8.3 兼容性
- 向后兼容：现有配置无需修改，功能不受影响
- 协议兼容：P2P 模式不影响 WebSocket 中转协议
- 跨平台：支持 Windows、Linux、macOS

## 9. 受影响文件清单

### 9.1 前端文件
- **jrp-client-web/src/components/ProxyForm.vue** (修改)
  - 增加"启用 P2P"复选框
  - 表单验证逻辑

- **jrp-client-web/src/views/ProxyList.vue** (修改)
  - 增加 P2P 状态列显示

### 9.2 服务器端文件
- **jrp-server/src/main/resources/application.yml** (修改)
  - 增加 P2P 端口和超时配置

- **jrp-server/src/main/java/com/tony/jrp/server/config/JrpConfig.java** (修改)
  - 增加 p2pPort、p2pTimeout 字段

- **jrp-server/src/main/java/com/tony/jrp/server/verticle/P2PServerVerticle.java** (新建)
  - P2P 服务器核心逻辑

- **jrp-server/src/main/java/com/tony/jrp/server/manager/P2PSessionManager.java** (新建)
  - P2P 会话管理

- **jrp-server/src/main/java/com/tony/jrp/server/service/impl/ProxyServerManager.java** (修改)
  - 部署 P2PServerVerticle

### 9.3 客户端文件
- **jrp-client/src/main/resources/application.yml** (修改)
  - 增加用户模式和 P2P 配置

- **jrp-client/src/main/java/com/tony/jrp/client/config/JrpClientConfig.java** (修改)
  - 增加 userMode、端口范围、重试次数字段

- **jrp-client/src/main/java/com/tony/jrp/client/service/P2PClientManager.java** (新建)
  - P2P 客户端核心逻辑

- **jrp-client/src/main/java/com/tony/jrp/client/tunnel/P2PTunnel.java** (新建)
  - P2P 隧道封装

- **jrp-client/src/main/java/com/tony/jrp/client/server/LocalProxyServer.java** (新建)
  - 本地反向代理服务器

- **jrp-client/src/main/java/com/tony/jrp/client/service/impl/ProxyClientManager.java** (修改)
  - 集成 P2P 功能

- **jrp-client/src/main/java/com/tony/jrp/client/JrpClientApplication.java** (修改)
  - 支持用户模式启动

### 9.4 模型文件
- **jrp-common/src/main/java/com/tony/jrp/common/model/ClientProxy.java** (无需修改)
  - enable_p2p 字段已存在

## 10. 实现注意事项

1. **线程安全**: P2P 会话管理使用 ConcurrentHashMap
2. **资源释放**: P2P 连接断开时正确释放 Socket 和内存
3. **日志记录**: 详细记录 P2P 打洞过程，便于故障排查
4. **配置热加载**: 支持运行时修改 P2P 配置（可选）
5. **安全考虑**: P2P 连接需要 token 验证，防止未授权访问
6. **测试覆盖**: 需要测试各种 NAT 环境和异常场景

## 11. 后续扩展方向

1. 支持 UDP 穿透的 P2P
2. P2P 传输加密（DTLS）
3. P2P 带宽统计和限速
4. WebRTC P2P 支持（浏览器直连）
5. P2P 中继节点（当打洞失败时通过其他客户端中转）