# P2P 直连功能任务计划

- [x] 任务 1: 前端增加 P2P 配置界面
    - 1.1: ProxyForm.vue 增加"启用 P2P"复选框
    - 1.2: ProxyList.vue 增加 P2P 状态列显示
    - 1.3: 表单验证和提交逻辑适配

- [x] 任务 2: 服务器端配置扩展
    - 2.1: application.yml 增加 P2P 端口和超时配置
    - 2.2: JrpConfig 增加 p2pPort 和 p2pTimeout 字段

- [ ] 任务 3: 创建 P2P 会话管理器
    - 3.1: 创建 P2PSessionManager.java 类
    - 3.2: 实现 P2P 会话存储和查询
    - 3.3: 实现会话生命周期管理
    - 3.4: 实现会话超时清理

- [x] 任务 4: 创建 P2P 服务器 Verticle
    - 4.1: 创建 P2PServerVerticle.java 类
    - 4.2: 实现 UDP 服务器监听
    - 4.3: 处理 P2P 注册请求
    - 4.4: 实现 NAT 打洞协助逻辑
    - 4.5: 实现 P2P 心跳处理

- [x] 任务 5: 服务器集成 P2P 服务
    - 5.1: ProxyServerManager 部署 P2PServerVerticle
    - 5.2: P2P 端口启动和异常处理

- [x] 任务 6: 客户端配置扩展
    - 6.1: application.yml 增加用户模式和 P2P 配置
    - 6.2: JrpClientConfig 增加用户模式和 P2P 相关字段

- [x] 任务 7: 创建 P2P 隧道封装
    - 7.1: 创建 P2PTunnel.java 类
    - 7.2: 实现 P2P 数据封装和解析
    - 7.3: 实现可靠传输机制（ACK）
    - 7.4: 实现连接状态管理

- [x] 任务 8: 创建本地代理服务器
    - 8.1: 创建 LocalProxyServer.java 类
    - 8.2: 实现本地端口监听
    - 8.3: 实现 HTTP/TCP 请求转发到 P2P 隧道
    - 8.4: 实现响应返回给本地用户

- [x] 任务 9: 创建 P2P 客户端管理器
    - 9.1: 创建 P2PClientManager.java 类
    - 9.2: 实现连接服务器 P2P 端口
    - 9.3: 实现 P2P 注册逻辑
    - 9.4: 实现 NAT 打洞逻辑
    - 9.5: 实现 P2P 心跳维护
    - 9.6: 实现断线重连机制
    - 9.7: 实现打洞失败回退

- [x] 任务 10: 客户端集成 P2P 功能
    - 10.1: ProxyClientManager 识别用户模式配置
    - 10.2: ProxyClientManager 为 P2P 代理启动 P2PClientManager
    - 10.3: ProxyClientManager 处理 P2P 成功回调
    - 10.4: ProxyClientManager 处理 P2P 失败回退
    - 10.5: ProxyClientManager 提供 P2P 状态查询

- [x] 任务 11: 客户端启动模式适配
    - 11.1: JrpClientApplication 读取 userMode 配置
    - 11.2: 传统模式启动逻辑
    - 11.3: 用户模式启动逻辑
    - 11.4: 启动模式日志输出

- [x] 任务 12: 端到端测试和调优
    - 12.1: 配置测试场景
    - 12.2: P2P 成功场景测试
    - 12.3: P2P 失败回退测试
    - 12.4: 多 P2P 代理并发测试
    - 12.5: 性能测试和优化
    - 12.6: 异常场景测试