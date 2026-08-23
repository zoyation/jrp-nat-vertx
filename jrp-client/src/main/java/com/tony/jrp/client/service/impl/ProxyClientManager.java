package com.tony.jrp.client.service.impl;

import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
import com.tony.jrp.client.handler.AbstractProxyHandler;
import com.tony.jrp.client.handler.ForwardProxyHandler;
import com.tony.jrp.client.handler.TcpReverseProxyHandler;
import com.tony.jrp.client.handler.UdpReverseProxyHandler;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.client.utils.UdpFragmentUtil;
import com.tony.jrp.client.verticle.AbstractProtocolVerticle;
import com.tony.jrp.client.verticle.ForwardProxyVerticle;
import com.tony.jrp.client.verticle.TCPVerticle;
import com.tony.jrp.client.verticle.UDPVerticle;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ServiceType;
import com.tony.jrp.common.model.*;
import com.tony.jrp.common.utils.ClientIdUtils;
import com.tony.jrp.common.utils.PortConverter;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 客户端-穿透配置管理
 */
@Component
@Slf4j
public class ProxyClientManager implements InitializingBean {
    /**
     * 连接超时1秒
     */
    public static final int CONNECT_TIMEOUT = 1000;
    /**
     * 注册超时1秒
     */
    public static final int REGISTER_TIMEOUT = 1000;
    /**
     * 空闲超时 10秒
     */
    public static final int IDLE_TIMEOUT = 4;
    /**
     * 缓冲区大小256KB
     */
    public static final int BUFFER_SIZE = 256 * 1024;
    /**
     * 写队列最大长度4 * 1024 * 256 * 1024=1G
     */
    public static final int WRITE_QUEUE_MAX_SIZE = 4 * 1024;
    /**
     * 消息类型和端口byte长度
     */
    public static final int TYPE_PORT_LEN = JRPMsgType.TYPE_LEN + 2;
    /**
     * 消息类型、端口和请求id byte长度
     */
    public static final int TYPE_PORT_REQUEST_ID_LEN = JRPMsgType.TYPE_LEN + 2 + 4;

    /**
     * 远程端口byte数组长度。
     */
    public static final int REMOTE_PORT_LEN = 2;
    /**
     * 请求唯一ID（int类型）对应byte数组长度，4字节。
     */
    public static final int REQUEST_ID_LEN = 4;
    /**
     * 重连间隔5秒
     */
    public static final int RECONNECT_DELAY = 5000;
    /**
     * ping间隔2秒
     */
    public static final int PING_DELAY = 2000;
    public static final String JRP_CLIENT_CONFIG = "jrp-client-config";
    public static final int KEEPALIVE_DELAY = 1000;
    public static final Buffer KEEP_ALIVE_BUFFER = Buffer.buffer().appendByte(JRPMsgType.UDP_TUNNEL_KEEPALIVE.getCode());
    /**
     * 用户端P2P心跳超时时间（毫秒），超过该时间未收到内网服务心跳，认为打洞链路失效，触发重新打洞
     */
    public static final int P2P_HEARTBEAT_TIMEOUT = 30 * 1000;
    /**
     * 用户端P2P心跳超时检查间隔（毫秒）
     */
    public static final int P2P_HEARTBEAT_CHECK_DELAY = 5 * 1000;
    @Autowired
    protected Vertx vertx;
    /**
     * 配置服务
     */
    @Autowired
    protected IConfigService configService;
    /**
     * 固定参数配置信息
     */
    @Autowired
    protected ProxyClientProperties properties;
    @Autowired
    SecurityService securityService;
    private HttpServer server;
    private final Object serverLock = new Object();
    private int registerPort;
    private String registerHost;
    private volatile ClientRegister register = null;
    //registerWebSocket为null，未注册
    private volatile WebSocket registerWebSocket = null;
    private volatile Long pingTimerId = null;
    /**
     * 全局UDP分片重组缓存清理定时器id。
     * 分片缓存清理不能只依赖UDPVerticle的定时器（无UDP代理时不会启动），
     * 否则未完成的分片缓存永不清理，导致内存泄漏且残留会话ID撞号丢数据。
     */
    private volatile Long fragmentCleanupTimerId = null;
    private final AtomicInteger reconnectionTimes = new AtomicInteger(0);
    private String errorMessage = "";
    /**
     * 远程端口和客户端映射
     */
    Map<Integer, ClientProxy> remotePortClientMap = new ConcurrentHashMap<>();
    /**
     * 远程端口和UDP Socket映射
     */
    Map<Integer, DatagramSocket> remotePortUdpSocketMap = new ConcurrentHashMap<>();
    /**
     * 远程端口和发送地址映射
     */
    Map<Integer, SocketAddress> remotePortSenderMap = new ConcurrentHashMap<>();
    /**
     * 服务类型和处理器映射
     */
    private final Map<ServiceType, AbstractProxyHandler> handlerMap = new ConcurrentHashMap<>();
    /**
     * 知名端口（0-1023）‌：这些端口通常被系统服务或标准应用协议占用
     * 动态/私有端口（49152-65535）：这些端口由操作系统临时分配给客户端进程，用于短期通信，例如浏览器发起的UDP请求。
     * 注册端口（1024-49151）‌：这些端口可由用户进程或应用程序动态分配，常见于自定义服务或特定软件。
     * 用户端P2P穿透Verticle部署ID映射，key：本地监听端口。
     */
    private final Map<Integer, String> verticleDeploymentIdMap = new ConcurrentHashMap<>();
    /**
     * 用户端P2P打洞DatagramSocket映射，key：本地监听端口。
     */
    private final Map<Integer, DatagramSocket> userP2PUdpSocketMap = new ConcurrentHashMap<>();
    /**
     * 用户端P2P心跳超时检查定时器id
     */
    private volatile Long p2pHeartbeatTimerId = null;
    /**
     * 用户端P2P最后心跳时间，key：本地监听端口，value：最后收到心跳的时间戳（毫秒）
     */
    private final Map<Integer, Long> p2pLastHeartbeatMap = new ConcurrentHashMap<>();
    /**
     * 用户端P2P代理配置，key：本地监听端口，供心跳超时重新打洞使用
     */
    private final Map<Integer, UserProxy> userProxyMap = new ConcurrentHashMap<>();
    /**
     * HTTP请求行路径提取正则
     */
    private static final Pattern HTTP_PATH_PATTERN = Pattern.compile("^\\S+\\s+(\\S+)\\s+", Pattern.CASE_INSENSITIVE);

    @Data
    private static class RegisterStatus {
        /**
         * 是否成功
         */
        private Boolean success;
        /**
         * 消息
         */
        private String message;
        /**
         * 服务端地址
         */
        private String remoteHost;

        private RegisterStatus(Boolean success, String message, String remoteHost) {
            this.success = success;
            this.message = message;


            this.remoteHost = remoteHost;
        }

        public static RegisterStatus fail(String message, String remoteHost) {
            return new RegisterStatus(false, message, remoteHost);
        }

        public static RegisterStatus ok(String message, String remoteHost) {
            return new RegisterStatus(true, message, remoteHost);
        }
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        init();
    }

    public void init() throws IOException {
        String registerAddress = properties.getRegisterAddress();
        //使用lastIndexOf支持ipv6地址解析。
        int lastIndex = registerAddress.lastIndexOf(":");
        registerPort = Integer.parseInt(registerAddress.substring(lastIndex + 1));
        registerHost = registerAddress.substring(0, lastIndex);

        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(configService.getConfigStore());
        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
        retriever.getConfig().onComplete(json -> {
            JsonObject result = json.result();
            restartServer(result);
        });
        retriever.listen(change -> {
            JsonObject oldConfig = change.getPreviousConfiguration();
            JsonObject newConfig = change.getNewConfiguration();
            if (log.isDebugEnabled()) {
                log.debug("config change!");
                log.debug("old config:{}", oldConfig);
                log.debug("new config:{}", newConfig);
            }
            if (this.register != null && !this.register.isUpdated()) {
                //非更新配置才需要重新创建代理处理器
                restartServer(newConfig);
            }
            //eventBus.publish(CONFIG_CHANGE, json);
        });
    }

    /**
     * 创建代理处理器
     */
    private void closeAndCreateProxyHandler() throws IOException {
        closeProxyHandler();
        handlerMap.put(ServiceType.TCP, new TcpReverseProxyHandler(vertx));
        handlerMap.put(ServiceType.UDP, new UdpReverseProxyHandler(vertx));
        handlerMap.put(ServiceType.SMART_PROXY, new ForwardProxyHandler(vertx));
    }

    /**
     * 关闭代理处理器
     */
    private void closeProxyHandler() throws IOException {
        for (Map.Entry<ServiceType, AbstractProxyHandler> entry : handlerMap.entrySet()) {
            log.info("停止转发穿透服务[{}]", entry.getKey().name());
            entry.getValue().close();
        }
        handlerMap.clear();
        for (Map.Entry<Integer, DatagramSocket> entry : remotePortUdpSocketMap.entrySet()) {
            log.info("停止P2P穿透服务[{}]", entry.getKey());
            entry.getValue().close();
        }
        remotePortUdpSocketMap.clear();
        //关闭用户端P2P穿透服务（Verticle和DatagramSocket）
        closeUserP2P();
    }

    /**
     * 关闭用户端P2P穿透服务，清理P2P打洞Verticle和DatagramSocket：
     * 1.卸载已部署的穿透Verticle，触发Verticle#stop关闭其持有的NetServer、DatagramSocket等资源；
     * 2.关闭打洞阶段（未成功部署Verticle）遗留的DatagramSocket，DatagramSocket#close为幂等操作，重复关闭安全。
     */
    private void closeUserP2P() {
        //取消用户端P2P心跳超时检查定时器
        if (p2pHeartbeatTimerId != null) {
            log.info("取消用户端P2P心跳超时检查定时器:{}", p2pHeartbeatTimerId);
            vertx.cancelTimer(p2pHeartbeatTimerId);
            p2pHeartbeatTimerId = null;
        }
        //清空用户端P2P心跳时间记录和配置映射
        p2pLastHeartbeatMap.clear();
        userProxyMap.clear();
        //卸载并停止用户端P2P穿透Verticle
        for (Map.Entry<Integer, String> entry : verticleDeploymentIdMap.entrySet()) {
            log.info("停止用户端P2P穿透服务[{}]", entry.getKey());
            vertx.undeploy(entry.getValue()).onComplete(r -> {
                if (r.failed()) {
                    log.error("停止用户端P2P穿透服务[{}]失败：{}", entry.getKey(), r.cause().getMessage(), r.cause());
                }
            });
        }
        verticleDeploymentIdMap.clear();
        //关闭用户端P2P打洞DatagramSocket
        for (Map.Entry<Integer, DatagramSocket> entry : userP2PUdpSocketMap.entrySet()) {
            log.info("停止用户端P2P穿透UDP服务[{}]", entry.getKey());
            entry.getValue().close();
        }
        userP2PUdpSocketMap.clear();
    }

    private HttpServer startServer(ProxyClientConfig newConfig) {
        if (log.isInfoEnabled()) {
            log.info("begin start server...");
        }
        String path = newConfig.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        Integer port = newConfig.getPort();
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        StaticHandler dist = StaticHandler.create("dist");
        String webAndPrefixPath = path;
        router.route(HttpMethod.GET, "/").handler(ctx -> ctx.redirect(webAndPrefixPath));
        //获取配置信息
        router.route(HttpMethod.GET, path + "/config/listRemoteProxies").handler(ctx -> configService.listRemoteProxies(ctx));
        //保存配置信息
        router.route(HttpMethod.POST, path + "/config/saveRemoteProxies").handler(ctx -> {
            if (this.register != null && this.register.isUpdated()) {
                log.info("标识需要发送更新穿透配置信息");
                this.register.setUpdated(false);
            }
            configService.saveRemoteProxies(ctx);
        });
        //更新穿透状态
        router.route(HttpMethod.GET, path + "/config/statusRemoteProxies").handler(ctx -> configService.end(() -> {
            RegisterStatus registerStatus = registerWebSocket == null ? RegisterStatus.fail(errorMessage, registerHost) : RegisterStatus.ok("穿透成功！", registerHost);
            //附加P2P状态信息
            LinkedHashMap<String, Object> statusResult = new LinkedHashMap<>();
            statusResult.put("success", registerStatus.getSuccess());
            statusResult.put("message", registerStatus.getMessage());
            statusResult.put("remoteHost", registerStatus.getRemoteHost());
            return Json.encode(statusResult);
        }, ctx));
        //p2p获取配置信息
        router.route(HttpMethod.GET, path + "/config/listUserProxies").handler(ctx -> configService.listUserProxies(ctx));
        //p2p保存配置信息
        router.route(HttpMethod.POST, path + "/config/saveUserProxies").handler(ctx -> {
            if (this.register != null && this.register.isUpdated()) {
                log.info("标识需要发送更新p2p穿透配置信息");
                this.register.setUpdated(false);
            }
            configService.saveUserProxies(ctx);
        });
        //p2p更新穿透状态
        router.route(HttpMethod.GET, path + "/config/statusUserProxies").handler(ctx -> configService.end(() -> {
            RegisterStatus registerStatus = registerWebSocket == null ? RegisterStatus.fail(errorMessage, registerHost) : RegisterStatus.ok("穿透成功！", registerHost);
            //附加P2P状态信息
            LinkedHashMap<String, Object> statusResult = new LinkedHashMap<>();
            statusResult.put("success", registerStatus.getSuccess());
            statusResult.put("message", registerStatus.getMessage());
            statusResult.put("remoteHost", registerStatus.getRemoteHost());
            return Json.encode(statusResult);
        }, ctx));
        //其它前端页面
        router.route(HttpMethod.GET, webAndPrefixPath + "/*").handler(dist);
        server.requestHandler(router);
        server.listen(port);
        if (log.isInfoEnabled()) {
            log.info("start server success，可浏览器访问[http://127.0.0.1:{}{}]进行穿透配置。", port, webAndPrefixPath);
        }
        return server;
    }

    private void restartServer(JsonObject result) {
        if (result == null) {
            log.error("result is null");
            return;
        }
        String jsonStr;
        if (result.containsKey(JRP_CLIENT_CONFIG)) {
            jsonStr = result.getString(JRP_CLIENT_CONFIG);
        } else {
            jsonStr = result.toString();
        }
        ProxyClientConfig newConfig = Json.decodeValue(jsonStr, ProxyClientConfig.class);
        synchronized (serverLock) {
            HttpServer olderServer = this.server;
            if (olderServer != null) {
                this.server = null;
                olderServer.close(r -> {
                    if (r.failed()) {
                        if (log.isErrorEnabled()) {
                            log.error("close server failed:{}", r.cause().getMessage(), r.cause());
                        }
                    } else {
                        if (log.isInfoEnabled()) {
                            log.info("close server success!");
                        }
                    }
                });
            }
            this.server = startServer(newConfig);
        }
        reconnectionTimes.set(0);

        if (registerWebSocket != null) {
            // 已建立连接，直接发送更新代理注册信息消息
            log.info("检测到已有连接，直接发送代理配置更新消息");
            try {
                this.register.setUpdated(false);
                ClientRegister register = getClientRegister(newConfig);
                // 发送更新消息
                String updateJson = Json.encodePrettily(register);
                log.info("发送代理配置更新消息：\n{}", updateJson);
                registerWebSocket.write(Buffer.buffer(JRPMsgType.PROXIES_UPDATE.codeArray()).appendBuffer(Buffer.buffer(updateJson)))
                        .onSuccess(v -> log.info("发送代理配置更新消息成功"))
                        .onFailure(e -> log.error("发送代理配置更新消息失败：{}", e.getMessage(), e));
            } catch (Exception e) {
                log.error("构建或发送代理配置更新消息异常：{}", e.getMessage(), e);
            }
        } else {
            // 未建立连接，开始注册
            startRegister(newConfig);
        }
    }

    private ClientRegister getClientRegister(ProxyClientConfig newConfig) {
        List<ClientProxy> registerProxies = newConfig.getRemote_proxies();
        ClientRegister register = new ClientRegister();
        register.setId(ClientIdUtils.getClientId());
        register.setId("2");
        register.setToken(properties.getToken());
        register.setUsername(properties.getUsername());
        register.setPassword(properties.getPassword());
        for (ClientProxy proxy : registerProxies) {
            //格式 host:port
            String proxyPass = proxy.getProxy_pass();
            if (StringUtils.hasText(proxyPass)) {
                parseProxyPass(proxy, proxyPass);
            }
            // 解析路由规则的proxy_pass
            if (proxy.getRoutes() != null) {
                for (RouteRule route : proxy.getRoutes()) {
                    String routePass = route.getProxy_pass();
                    if (StringUtils.hasText(routePass)) {
                        parseProxyPass(route, routePass);
                    }
                }
            }
        }
        register.setProxies(registerProxies);
        register.setUserProxies(newConfig.getUser_proxies());
        return register;
    }

    /**
     * 解析proxy_pass地址，提取host、port、path。
     * 支持格式如：http://host:port/path、https://host/path、host:port/path 等。
     * 类似nginx的proxy_pass，当包含路径时，转发请求会加上该路径前缀。
     *
     * @param proxy     需要设置解析结果的ClientProxy对象
     * @param proxyPass 原始proxy_pass地址
     */
    private static void parseProxyPass(ClientProxy proxy, String proxyPass) {
        proxy.setProxy_pass(proxyPass);
        String lowerCasePass = proxyPass.toLowerCase().trim();
        boolean https = lowerCasePass.startsWith("https");
        proxy.setHttps(https);
        // 去掉协议前缀
        String hostPortPath = lowerCasePass.replaceAll("^https?://", "");
        // 分离host:port和path（取第一个/作为路径起始）
        String path = "";
        int slashIdx = hostPortPath.indexOf("/");
        if (slashIdx > 0) {
            path = hostPortPath.substring(slashIdx);
            hostPortPath = hostPortPath.substring(0, slashIdx);
        }
        proxy.setPath(path);
        // 解析host和port
        int index = hostPortPath.lastIndexOf(":");
        if (index > 0) {
            proxy.setHost(hostPortPath.substring(0, index));
            proxy.setPort(Integer.parseInt(hostPortPath.substring(index + 1)));
        } else {
            proxy.setHost(hostPortPath);
            proxy.setPort(https ? 443 : 80);
        }
    }

    /**
     * 开始注册
     *
     * @param newConfig 穿透配置信息
     */
    private void startRegister(ProxyClientConfig newConfig) {
        try {
            if (CollectionUtils.isEmpty(newConfig.getRemote_proxies()) && CollectionUtils.isEmpty(newConfig.getUser_proxies())) {
                log.warn("没有穿透配置信息，配置穿透信息后进行注册！");
                return;
            }
            ClientRegister register = getClientRegister(newConfig);
            remotePortClientMap = newConfig.getRemote_proxies().stream().filter(r -> r.getRemote_port() != null).collect(Collectors.toMap(ClientProxy::getRemote_port, r -> r));
            log.info("开始注册...");
            if (reconnectionTimes.get() < properties.getReconnectionTimes()) {
                vertx.setTimer(1, registerTimerHandler(register));
            }
        } catch (Exception e) {//注册失败
            String errorMessage = e.getMessage();
            log.error("内网穿透注册失败：{}", errorMessage, e);
            registerWebSocket = null;
        }
    }


    /**
     * 注册处理器
     *
     * @param register 注册信息
     * @return 定时器ID
     */
    private Handler<Long> registerTimerHandler(ClientRegister register) {
        return id -> tryRegister(register).onComplete(r -> {
            if (r.succeeded() && r.result()) {
                reconnectionTimes.set(0);
                log.info("内网穿透注册成功！");
            } else {
                log.error("内网穿透注册失败！");
                if (reconnectionTimes.get() >= properties.getReconnectionTimes()) {
                    log.warn("与外网穿透服务断开连接或未注册，断线重连次数已达限制次数[{}]，不再重连!", properties.getReconnectionTimes());
                } else {
                    reconnectionTimes.incrementAndGet();
                    //5秒后重连
                    vertx.setTimer(RECONNECT_DELAY, registerTimerHandler(register));
                }
            }
        });
    }

    /**
     * @param register 穿透注册信息
     * @return true:注册成功，false:注册失败
     */
    private Future<Boolean> tryRegister(ClientRegister register) {
        if (reconnectionTimes.get() == 0) {
            log.info("与外网穿透服务断开或首次注册或配置变动，开始注册...");
        } else {
            log.info("与外网穿透服务断开连接或注册失败，尝试第[{}]次注册...", reconnectionTimes);
        }
        WebSocketClientOptions options = getWebSocketClientOptions();
        Promise<Boolean> registerPromise = Promise.promise();
        WebSocketConnectOptions connectOptions = new WebSocketConnectOptions().setPort(registerPort).setHost(registerHost).setURI("/").setConnectTimeout(CONNECT_TIMEOUT);
        connectOptions.setRegisterWriteHandlers(true);
        AtomicReference<WebSocket> currentWebSocket = new AtomicReference<>();
        vertx.createWebSocketClient(options).connect(connectOptions).onComplete(webSocket -> {
            currentWebSocket.set(webSocket);
            // 设置处理pong的回调
            try {
                final AtomicBoolean pongReceived = new AtomicBoolean(true);
                webSocket.pongHandler(pongFrame -> {
                    pongReceived.set(true);
                    log.debug("Pong received:{}", pongFrame.toString());
                });
                webSocket.closeHandler(closeHandler -> {
                    log.warn("websocket 连接断开：{}", webSocket.remoteAddress());
                    if (registerWebSocket == null) {
                        registerPromise.tryComplete(false);
                    }
                    try {
                        if (pingTimerId != null) {
                            log.info("取消ping任务:{}", pingTimerId);
                            vertx.cancelTimer(pingTimerId);
                        }
                        this.closeProxyHandler();
                    } catch (Exception e) {
                        log.error("closeWebSocket error：{}", e.getMessage(), e);
                    } finally {
                        pingTimerId = null;
                        if (registerWebSocket != null) {
                            registerWebSocket = null;
                            //5秒后重连
                            log.info("与外网穿透服务断开连接或注册失败，尝试第[{}]次注册...", reconnectionTimes);
                            vertx.setTimer(RECONNECT_DELAY, registerTimerHandler(register));
                        }
                    }
                });
                webSocket.handler(buffer -> {
                    //如果是服务端返回的请求消息buffer前面放的是端口位数1位整数+端口+请求唯一标识长度2位整数+请求唯一标识（IP+端口）；如果是注册结果消息JSON串第一个字符为{
                    byte msgType = buffer.getByte(0);
                    JRPMsgType jrpMsgType = JRPMsgType.getByCode(msgType);
                    //代理端口
                    Integer remotePort = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_LEN).getUnsignedShort(0);
                    if (jrpMsgType == null) {
                        log.error("未知消息类型：{}", buffer);
                        webSocket.close();
                        return;
                    }
                    switch (jrpMsgType) {
                        case REGISTER_RESULT: {
                            try {
                                RegisterResult registerResult = Json.decodeValue(buffer.getBuffer(1, buffer.length()), RegisterResult.class);
                                if (registerResult.isSuccess()) {
                                    //初始化端口转发穿透服务处理器
                                    this.closeAndCreateProxyHandler();
                                    //启动全局分片重组缓存清理，确保无UDP代理Verticle时分片缓存也能被清理
                                    if (fragmentCleanupTimerId == null) {
                                        fragmentCleanupTimerId = vertx.setPeriodic(1000, id -> UdpFragmentUtil.cleanupExpired());
                                    }
                                    try {
                                        //初始化支持P2P穿透的内网穿透，用于管理P2P客户端连接
                                        this.initClientP2P(register.getProxies().stream().filter(r -> r.isEnable() && r.isEnable_p2p()).collect(Collectors.toList()));
                                        this.initUserP2P(register.getUserProxies().stream().filter(UserProxy::isEnable).collect(Collectors.toList()));
                                    } catch (Exception e) {
                                        log.error("P2P初始化异常（不影响中转模式）: {}", e.getMessage(), e);
                                    }
                                    //设置状态为更新状态
                                    register.setUpdated(true);
                                    //更新代理信息
                                    updateProxies(register, registerResult);
                                    registerWebSocket = webSocket;
                                    webSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                    //发送ping
                                    pingTimerId = vertx.setPeriodic(PING_DELAY, id -> {
                                        if (pongReceived.get()) {
                                            pongReceived.set(false);
                                            webSocket.writePing(Buffer.buffer("ping")).timeout(1, TimeUnit.SECONDS).onFailure(cause -> {
                                                log.error("ping失败：{}", cause.getMessage(), cause);
                                                //vertx.cancelTimer(id);
                                                //webSocket.close();
                                            });
                                        } else {
                                            log.warn("未收到服务端[{}]pong消息！", registerWebSocket.remoteAddress().toString());
                                        }
                                    });
                                    log.info("注册成功：\n{}", new JsonObject(buffer.getBuffer(1, buffer.length())).encodePrettily());
                                    registerPromise.tryComplete(true);
                                } else {
                                    registerWebSocket = null;
                                    webSocket.close();
                                    errorMessage = registerResult.getMsg();
                                    log.error("注册失败：{}", errorMessage);
                                    registerPromise.tryComplete(false);
                                }
                            } catch (Throwable e) {
                                webSocket.close();
                                errorMessage = e.getMessage();
                                log.error("注册异常：{}", errorMessage, e);
                                registerPromise.tryComplete(false);
                            }
                            break;
                        }
                        case PROXIES_UPDATE_RESULT: {
                            try {
                                RegisterResult registerResult = Json.decodeValue(buffer.getBuffer(1, buffer.length()), RegisterResult.class);
                                if (registerResult.isSuccess()) {
                                    //输出更新代理信息成功
                                    log.info("更新代理信息成功：{}", registerResult.getMsg());
                                    register.setUpdated(true);
                                    //更新代理信息
                                    updateProxies(register, registerResult);
                                    //初始化原始服务内网客户端支持P2P穿透的内网穿透，用于管理P2P客户端连接
                                    this.initClientP2P(register.getProxies().stream().filter(r -> r.isEnable() && r.isEnable_p2p()).collect(Collectors.toList()));
                                    //初始化用户端P2P
                                    this.initUserP2P(register.getUserProxies().stream().filter(UserProxy::isEnable).collect(Collectors.toList()));
                                } else {
                                    log.error("更新代理信息失败：{}", registerResult.getMsg());
                                }
                            } catch (Throwable e) {
                                errorMessage = e.getMessage();
                                log.error("更新代理信息异常：{}", errorMessage, e);
                            }
                            break;
                        }
                        case CLOSE:
                        case RECEIVE: {
                            receiveData(remotePort, webSocket, buffer, msgType);
                            break;
                        }
                        case UDP_TUNNEL_REQUEST: {
                            udpTunnelRequest(buffer, remotePort);
                            break;
                        }
                    }
                });

                webSocket.exceptionHandler(err -> {
                    log.error("websocket 连接异常：{}", err.getMessage(), err);
                    webSocket.close();
                    if (this.registerWebSocket == null) {
                        registerPromise.tryComplete(false);
                    }
                });
                String registerInfo = Json.encodePrettily(register);
                log.info("开始发送注册消息：\n{}", registerInfo);
                webSocket.write(Buffer.buffer(JRPMsgType.REGISTER.codeArray()).appendBuffer(Buffer.buffer(registerInfo))).onComplete((rt) -> {
                    if (rt.succeeded()) {
                        log.info("发送注册消息成功，等待返回!");
                    } else {
                        errorMessage = rt.cause().getMessage();
                        log.info("发送注册消息失败，error：{}", rt.cause().getMessage(), rt.cause());
                        registerPromise.complete(false);
                    }
                });
            } catch (Exception e) {
                webSocket.close();
                errorMessage = e.getMessage();
                log.error("websocket 连接初始化异常：{}", e.getMessage(), e);
                registerPromise.complete(false);
            }
        }, err -> {
            //websocket初始化异常
            errorMessage = err.getMessage();
            log.error("websocket初始化异常：{}", err.getMessage(), err);
            registerPromise.tryComplete(false);
        });
        //注册超时设置
        vertx.setTimer(REGISTER_TIMEOUT, id -> {
            if (registerPromise.tryComplete(false)) {
                log.warn("websocket 注册超时！");
                //关闭socket，避免超时重复注册时导致端口占用错误
                WebSocket webSocket = currentWebSocket.get();
                if (webSocket != null) {
                    log.warn("注册超时，关闭websocket！");
                    webSocket.close();
                }
            }
        });
        return registerPromise.future();
    }

    /**
     * udp打洞请求
     *
     * @param buffer     udp打洞请求消息
     * @param remotePort udp打洞请求消息中的远程端口
     */
    private void udpTunnelRequest(Buffer buffer, Integer remotePort) {
        //发送外网端口号到注册端口，服务器收到后可和注册配置关联起来
        String userRemoteAddress = buffer.getBuffer(TYPE_PORT_LEN, buffer.length()).toString();
        String userRemoteHost = userRemoteAddress.substring(0, userRemoteAddress.indexOf(":"));
        int userRemotePort = Integer.parseInt(userRemoteAddress.substring(userRemoteAddress.indexOf(":") + 1));
        log.info("收到P2P用户端打洞外网地址：{}", userRemoteAddress);
        DatagramSocket datagramSocket = remotePortUdpSocketMap.getOrDefault(remotePort, vertx.createDatagramSocket());
        remotePortUdpSocketMap.put(remotePort, datagramSocket);
        datagramSocket.handler(packet -> {
            if (packet.data().getByte(0) == JRPMsgType.UDP_TUNNEL_KEEPALIVE.getCode()) {
                log.debug("收到P2P用户端[{}]UDP心跳包", packet.sender());
                remotePortSenderMap.put(remotePort, packet.sender());
                vertx.setTimer(KEEPALIVE_DELAY, id -> {
                    log.debug("返回UDP心跳包到P2P用户端[{}]", packet.sender());
                    datagramSocket.send(KEEP_ALIVE_BUFFER, packet.sender().port(), packet.sender().host());
                });
            } else {
                log.debug("收到P2P用户端[{}]UDP数据包", packet.sender());
                this.receiveP2PData(datagramSocket, packet);
            }
        });
        log.info("发送udp消息到注册地址（信令服务器）：{}", registerHost + ":" + registerPort);
        datagramSocket.send(Buffer.buffer().appendByte(JRPMsgType.UDP_TUNNEL_RESPONSE.getCode()).appendBytes(PortConverter.getRemotePortByte(remotePort)), registerPort, registerHost);
        //用户端外网ip和端口收到上面信令服务器转发的消息后会访问当前服务的外网地址，这儿延迟1秒发送，确保用户端收到并发送udp消息到当前服务外网地址后才发送端口号给用户端，避免用户端收到端口号后丢弃数据包导致打洞失败
        vertx.setTimer(KEEPALIVE_DELAY, id -> {
            log.info("发送udp打洞保活消息到P2P用户端地址：{}", userRemoteAddress);
            datagramSocket.send(KEEP_ALIVE_BUFFER, userRemotePort, userRemoteHost);
        });
    }

    /**
     * 初始化用户端P2P
     *
     * @param userProxyList 用户端P2P列表
     */
    private void initUserP2P(List<UserProxy> userProxyList) {
        if (CollectionUtils.isEmpty(userProxyList)) {
            return;
        }
        for (UserProxy userProxy : userProxyList) {
            if (!userProxy.isEnable()) {
                continue;
            }
            synchronized (ProxyClientManager.this) {
                if (verticleDeploymentIdMap.containsKey(userProxy.getLocal_port())) {
                    log.warn("已存在端口为[{}]的代理信息，不做处理！", userProxy.getLocal_port());
                    continue;
                }
            }
            //保存配置，供心跳超时重新打洞使用
            userProxyMap.put(userProxy.getLocal_port(), userProxy);
            createUserP2PSocket(userProxy);
        }
        //启动用户端P2P心跳超时检查，超过心跳超时时间未收到内网服务心跳，认为打洞链路失效，触发重新打洞
        if (p2pHeartbeatTimerId == null) {
            p2pHeartbeatTimerId = vertx.setPeriodic(P2P_HEARTBEAT_CHECK_DELAY, id -> checkP2PHeartbeatTimeout());
            log.info("启动用户端P2P心跳超时检查定时器:{}", p2pHeartbeatTimerId);
        }
    }

    /**
     * 创建用户端P2P打洞DatagramSocket、设置数据接收handler并发送UDP打洞请求
     *
     * @param userProxy 用户端P2P配置
     */
    private void createUserP2PSocket(UserProxy userProxy) {
        Integer localPort = userProxy.getLocal_port();
        //启动本地监听，打洞成功后，发送外网端口号到注册端口
        DatagramSocketOptions options = new DatagramSocketOptions().setReusePort(true).setReuseAddress(true);
        DatagramSocket datagramSocket = vertx.createDatagramSocket(options);
        //保存打洞DatagramSocket，WebSocket关闭时统一关闭
        userP2PUdpSocketMap.put(localPort, datagramSocket);
        //记录打洞发起时间，作为心跳超时检测的基准时间
        p2pLastHeartbeatMap.put(localPort, System.currentTimeMillis());
        datagramSocket.handler(packet -> {
            Buffer buffer = packet.data();
            JRPMsgType jrpMsgType = JRPMsgType.getByCode(buffer.getByte(0));
            if (jrpMsgType == JRPMsgType.UDP_TUNNEL_RESPONSE) {
                //打洞成功，链路已打通，更新心跳时间
                p2pLastHeartbeatMap.put(localPort, System.currentTimeMillis());
                //信令服务器返回的内网服务外网地址（IP:端口号）
                String lanRemoteAddress = buffer.getBuffer(JRPMsgType.TYPE_PORT_LEN, buffer.length()).toString();
                log.info("UDP打洞成功，内网服务的外网打洞地址：{}", lanRemoteAddress);
                //启动本地服务，处理内网穿透数据转发
                ServiceType serviceType = userProxy.getType();
                AbstractProtocolVerticle<?> verticle;
                String ipv4 = "127.0.0.1";
                String[] serviceRemoteAddress = lanRemoteAddress.split(":");
                SocketAddress socketAddress = SocketAddress.inetSocketAddress(Integer.parseInt(serviceRemoteAddress[1]), serviceRemoteAddress[0]);
                switch (serviceType) {
                    case HTTPS:
                    case HTTP:
                    case TCP: {
                        verticle = new TCPVerticle(ipv4, datagramSocket, socketAddress, securityService, userProxy);
                        break;
                    }
                    case UDP: {
                        verticle = new UDPVerticle(ipv4, datagramSocket, socketAddress, securityService, userProxy);
                        break;
                    }
                    case HTTP_PROXY:
                    case HTTPS_PROXY:
                    case SOCKS4:
                    case SOCKS5:
                    case SMART_PROXY:
                        verticle = new ForwardProxyVerticle(ipv4, datagramSocket, socketAddress, securityService, userProxy);
                        break;
                    default:
                        throw new IllegalStateException("不支持穿透类型：" + serviceType.name() + "！");
                }
                vertx.deployVerticle(verticle)
                        .onSuccess(id -> {
                            //保存部署ID，WebSocket关闭时卸载Verticle
                            verticleDeploymentIdMap.put(userProxy.getLocal_port(), id);
                            log.info("发送心跳到内网服务：{}", socketAddress);
                            //内网可能收到也可能收不到，取决于网络类型
                            datagramSocket.send(KEEP_ALIVE_BUFFER, socketAddress.port(), socketAddress.host());
                        })
                        .onFailure(Throwable::printStackTrace);
                datagramSocket.handler(p2pPacket -> {
                    //分片数据重组，未接收完整时等待后续分片
                    Buffer data = UdpFragmentUtil.assemble(datagramSocket, p2pPacket.sender(), p2pPacket.data());
                    if (data == null) {
                        return;
                    }
                    JRPMsgType msgType = data.length() > 0 ? JRPMsgType.getByCode(data.getByte(0)) : null;
                    if (JRPMsgType.UDP_TUNNEL_KEEPALIVE == msgType) {
                        //收到内网服务心跳，链路存活，更新心跳时间
                        p2pLastHeartbeatMap.put(localPort, System.currentTimeMillis());
                        log.debug("发送心跳到内网服务：{}", socketAddress);
                        //内网可能收到也可能收不到，取决于网络类型
                        datagramSocket.send(KEEP_ALIVE_BUFFER, socketAddress.port(), socketAddress.host());
                    } else {
                        //收到内网服务数据，链路存活，更新心跳时间
                        p2pLastHeartbeatMap.put(localPort, System.currentTimeMillis());
                        log.info("收到来自内网服务地址[{}]的数据", p2pPacket.sender());
                        //消息前缀为：消息标志符，后面是消息id：即代理端口位数（一位整数1024到49151，4或者5）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
                        //获取代理端口字符串长度（代理到外网的穿透访问端口，一位整数，比如1024则长度为4,49151则长度为5）
                        //外网访问端口，整数，比如1024
                        Integer remotePort = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN).getUnsignedShort(0);
                        //int clientStrLen = Integer.parseInt(data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN).toString());
                        //clientAddress = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
                        Integer requestId = data.getBuffer(JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN).getInt(0);
                        //获取消息标识：代理端口+请求id
                        Buffer msgId = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN);
                        Buffer realData = data.getBuffer(JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN, data.length());
                        verticle.backData(msgType, msgId, requestId, realData);
                    }
                });
            }
        });
        //发送UDP打洞请求到信令服务器
        sendHolePunchRequest(userProxy, datagramSocket);
    }

    /**
     * 发送UDP打洞请求到信令服务器
     *
     * @param userProxy      用户端P2P配置
     * @param datagramSocket 打洞DatagramSocket
     */
    private void sendHolePunchRequest(UserProxy userProxy, DatagramSocket datagramSocket) {
        log.info("发送UDP打洞请求到信令服务器[{}:{}]", registerHost, registerPort);
        byte[] remotePortByte = PortConverter.getRemotePortByte(userProxy.getRemote_port());
        datagramSocket.send(Buffer.buffer().appendByte(JRPMsgType.UDP_TUNNEL_REQUEST.getCode()).appendBytes(remotePortByte),
                registerPort, registerHost);
    }

    /**
     * 检查用户端P2P心跳超时，超过超时时间未收到内网服务心跳，认为打洞链路失效，触发重新打洞
     */
    private void checkP2PHeartbeatTimeout() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> entry : p2pLastHeartbeatMap.entrySet()) {
            Integer localPort = entry.getKey();
            long timeout = now - entry.getValue();
            if (timeout > P2P_HEARTBEAT_TIMEOUT) {
                log.warn("端口[{}]P2P心跳超时（{}ms未收到内网服务心跳），重新打洞！", localPort, timeout);
                redoHolePunch(localPort);
            }
        }
    }

    /**
     * 用户端P2P重新打洞：卸载旧Verticle、关闭旧DatagramSocket后重新创建并发送UDP打洞请求
     *
     * @param localPort 本地监听端口
     */
    private void redoHolePunch(Integer localPort) {
        synchronized (ProxyClientManager.this) {
            //卸载并停止用户端P2P穿透Verticle
            String deploymentId = verticleDeploymentIdMap.remove(localPort);
            if (deploymentId != null) {
                log.info("端口[{}]重新打洞，卸载用户端P2P穿透服务[{}]！", localPort, deploymentId);
                vertx.undeploy(deploymentId).onComplete(r -> {
                    if (r.failed()) {
                        log.error("停止用户端P2P穿透服务[{}]失败：{}", localPort, r.cause().getMessage(), r.cause());
                    }
                });
            }
            //关闭旧DatagramSocket（打洞未成功时无Verticle，需在此关闭；DatagramSocket#close为幂等操作，重复关闭安全）
            DatagramSocket oldSocket = userP2PUdpSocketMap.remove(localPort);
            if (oldSocket != null) {
                log.info("端口[{}]重新打洞，关闭旧DatagramSocket！", localPort);
                oldSocket.close();
            }
            UserProxy userProxy = userProxyMap.get(localPort);
            if (userProxy == null) {
                log.warn("未找到端口[{}]对应的用户端P2P配置，无法重新打洞！", localPort);
                return;
            }
            //重新创建DatagramSocket并发送UDP打洞请求
            createUserP2PSocket(userProxy);
        }
    }

    /**
     * 收到P2P用户端数据，处理P2P穿透数据
     *
     * @param datagramSocket UDP socket
     * @param packet         数据包
     */
    private void receiveP2PData(DatagramSocket datagramSocket, DatagramPacket packet) {
        //如果是服务端返回的请求消息buffer前面放的是端口位数1位整数+端口+请求唯一标识长度2位整数+请求唯一标识（IP+端口）；如果是注册结果消息JSON串第一个字符为
        //分片数据重组，未接收完整时等待后续分片
        Buffer buffer = UdpFragmentUtil.assemble(datagramSocket, packet.sender(), packet.data());
        if (buffer == null) {
            return;
        }
        byte msgType = buffer.getByte(0);
        JRPMsgType jrpMsgType = JRPMsgType.getByCode(msgType);
        //代理端口
        Integer remotePort = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_LEN).getUnsignedShort(0);
        if (jrpMsgType == null) {
            log.error("未知p2p消息类型：{}", buffer);
            return;
        }
        switch (jrpMsgType) {
            case CLOSE:
            case RECEIVE: {
                //请求唯一标识,代理端口之后开始取
                Integer requestId = buffer.getBuffer(TYPE_PORT_LEN, TYPE_PORT_REQUEST_ID_LEN).getInt(0);
                //获取消息标识：代理端口+请求id，消息类型之后取
                Buffer msgId = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_REQUEST_ID_LEN);
                //收到外网穿透服务器发送的客户端请求通知
                Buffer data = buffer.getBuffer(TYPE_PORT_REQUEST_ID_LEN, buffer.length());
                log.debug("收到用户端p2p请求消息[{}]！", requestId);
                ClientProxy proxy = resolveProxy(remotePort, data);
                if (proxy == null) {
                    log.warn("未找到代理端口[{}]对应的客户端配置！", remotePort);
                    return;
                }
                SocketAddress sender = packet.sender();
                SocketAddress cacheSender = remotePortSenderMap.get(remotePort);
                if (cacheSender == null) {
                    log.warn("未找到代理端口[{}]对应的客户端发送者，可能是非法请求！", remotePort);
                    return;
                }
                if (!(cacheSender.host().equals(sender.host()) && cacheSender.port() == sender.port())) {
                    log.warn("代理端口[{}]对应的客户端发送者[{}]与当前发送者[{}]不一致，可能是非法请求！", remotePort, cacheSender, sender);
                    return;
                }
                Consumer<Buffer> bufferConsumer = (Buffer backData) -> UdpFragmentUtil.sendWithFragment(datagramSocket, requestId, backData, sender.port(), sender.host());
                switch (proxy.getType()) {
                    case HTTP:
                    case HTTPS:
                    case TCP:
                        handlerMap.get(ServiceType.TCP).handle(bufferConsumer, msgType, msgId, requestId, proxy, data);
                        break;
                    case UDP:
                        handlerMap.get(ServiceType.UDP).handle(bufferConsumer, msgType, msgId, requestId, proxy, data);
                        break;
                    case HTTP_PROXY:
                    case HTTPS_PROXY:
                    case SOCKS4:
                    case SOCKS5:
                    case SMART_PROXY:
                        handlerMap.get(ServiceType.SMART_PROXY).handle(bufferConsumer, msgType, msgId, requestId, proxy, data);
                        break;
                }
                break;
            }
        }
    }

    /**
     * 初始化支持P2P穿透的内网穿透，用于管理P2P客户端连接
     *
     * @param p2pProxies 用户端p2p穿透信息
     */
    private void initClientP2P(List<ClientProxy> p2pProxies) {
        for (ClientProxy proxy : p2pProxies) {
            if (proxy.isEnable_p2p()) {
                DatagramSocket datagramSocket = vertx.createDatagramSocket();
                remotePortUdpSocketMap.put(proxy.getRemote_port(), datagramSocket);
            }
        }
    }

    /**
     * 更新代理信息
     *
     * @param register       注册信息
     * @param registerResult 注册结果
     *                       更新代理成功后代理数据（外网端口可能是服务端返回）
     */
    private void updateProxies(ClientRegister register, RegisterResult registerResult) {
        List<ClientProxy> proxies = registerResult.getProxies();
        //保存服务器中转穿透信息
        if (proxies != null) {
            configService.saveRemoteProxies(proxies);
            register.setProxies(proxies);
        } else {
            proxies = register.getProxies();
        }
        //保存用户端p2p穿透信息
        List<UserProxy> userProxies = registerResult.getUserProxies();
        if (userProxies != null) {
            configService.saveUserProxies(userProxies);
            register.setUserProxies(userProxies);
        }
        remotePortClientMap = proxies.stream().collect(Collectors.toMap(ClientProxy::getRemote_port, r -> r, (a, b) -> a));
        this.register = register;
        //输出穿透信息
        log.info("服务器中转穿透信息：");
        for (ClientProxy proxy : register.getProxies()) {
            if (proxy.getType() != null) {
                //HTTP，HTTPS、TCP、UDP、SOCKS4、SOCKS5
                String message;
                String logMessage = "";
                switch (proxy.getType()) {
                    case HTTP:
                        message = "HTTP服务[%s]穿透后外网地址：[http://%s:%s]！";
                        logMessage = String.format(message, proxy.getProxy_pass(), registerHost, proxy.getRemote_port());
                        break;
                    case HTTPS:
                        message = "HTTPS服务[{%s}]穿透后外网地址：[https://%s:%s]！";
                        logMessage = String.format(message, proxy.getProxy_pass(), registerHost, proxy.getRemote_port());
                        break;
                    case TCP:
                        message = "TCP服务[{%s}]穿透后外网地址：[%s:%s]！";
                        logMessage = String.format(message, proxy.getProxy_pass(), registerHost, proxy.getRemote_port());
                        break;
                    case UDP:
                        message = "UDP服务[{%s}]穿透后外网地址：[%s:%s]！";
                        logMessage = String.format(message, proxy.getProxy_pass(), registerHost, proxy.getRemote_port());
                        break;
                    case HTTP_PROXY:
                        message = "HTTP代理服务穿透后外网代理地址：[http://%s:%s]！";
                        logMessage = String.format(message, registerHost, proxy.getRemote_port());
                        break;
                    case HTTPS_PROXY:
                        message = "HTTPS代理服务穿透后外网代理地址：[https://%s:%s]！";
                        logMessage = String.format(message, registerHost, proxy.getRemote_port());
                        break;
                    case SOCKS4:
                        message = "SOCKS4代理服务穿透后外网代理地址：[%s:%s]！";
                        logMessage = String.format(message, registerHost, proxy.getRemote_port());
                        break;
                    case SOCKS5:
                        message = "SOCKS5代理服务穿透后外网代理地址：[%s:%s]！";
                        logMessage = String.format(message, registerHost, proxy.getRemote_port());
                        break;
                    case SMART_PROXY:
                        message = "智能代理(同时支持http代理、https代理、socks4、socks4a、socks5)服务穿透后外网代理地址：[%s:%s]！";
                        logMessage = String.format(message, registerHost, proxy.getRemote_port());
                        break;
                }
                log.info(logMessage);
            }
        }
        //输出用户端p2p穿透信息
        log.info("用户端p2p穿透信息：");
        for (UserProxy proxy : register.getUserProxies()) {
            if (proxy.getType() != null) {
                //HTTP，HTTPS、TCP、UDP、SOCKS4、SOCKS5
                String message;
                String logMessage = "";
                switch (proxy.getType()) {
                    case HTTP:
                        message = "HTTP服务[外网转发端口%s]P2P穿透后本地地址：[http://127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case HTTPS:
                        message = "HTTPS服务[外网转发端口%s]P2P穿透后本地地址：[https://127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case TCP:
                        message = "TCP服务[外网转发端口%s]穿透后本地地址：[127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case UDP:
                        message = "UDP服务[外网转发端口%s]穿透后本地地址：[127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case HTTP_PROXY:
                        message = "HTTP代理服务[外网转发端口%s]穿透后外网代理地址：[http://127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case HTTPS_PROXY:
                        message = "HTTPS代理服务[外网转发端口%s]穿透后外网代理地址：[https://127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case SOCKS4:
                        message = "SOCKS4代理服务[外网转发端口%s]穿透后外网代理地址：[127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case SOCKS5:
                        message = "SOCKS5代理服务[外网转发端口%s]穿透后外网代理地址：[127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                    case SMART_PROXY:
                        message = "智能代理(同时支持http代理、https代理、socks4、socks4a、socks5)服务[外网转发端口%s]穿透后外网代理地址：[127.0.0.1:%s]！";
                        logMessage = String.format(message, proxy.getRemote_port(), proxy.getLocal_port());
                        break;
                }
                log.info(logMessage);
            }
        }
    }

    /**
     * 接收到服务端返回的消息
     *
     * @param remotePort 端口
     * @param webSocket  websocket隧道
     * @param buffer     数据
     * @param msgType    消息类型
     */
    private void receiveData(Integer remotePort, WebSocket webSocket, Buffer buffer, byte msgType) {
        //请求唯一标识,代理端口之后开始取
        Integer requestId = buffer.getBuffer(TYPE_PORT_LEN, TYPE_PORT_REQUEST_ID_LEN).getInt(0);
        //获取消息标识：代理端口+请求id，消息类型之后取
        Buffer msgId = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_REQUEST_ID_LEN);
        //收到外网穿透服务器发送的客户端请求通知
        Buffer data = buffer.getBuffer(TYPE_PORT_REQUEST_ID_LEN, buffer.length());
        log.debug("收到外网穿透服务器转发的客户端请求消息[{}]！", requestId);
        ClientProxy proxy = resolveProxy(remotePort, data);
        if (proxy == null) {
            log.warn("未找到代理端口[{}]对应的客户端！", remotePort);
            return;
        }
        switch (proxy.getType()) {
            case HTTP:
            case HTTPS:
            case TCP:
                handlerMap.get(ServiceType.TCP).handle(webSocket::write, msgType, msgId, requestId, proxy, data);
                break;
            case UDP:
                handlerMap.get(ServiceType.UDP).handle(webSocket::write, msgType, msgId, requestId, proxy, data);
                break;
            case HTTP_PROXY:
            case HTTPS_PROXY:
            case SOCKS4:
            case SOCKS5:
            case SMART_PROXY:
                handlerMap.get(ServiceType.SMART_PROXY).handle(webSocket::write, msgType, msgId, requestId, proxy, data);
                break;
        }
    }

    /**
     * 按端口和请求路径解析代理配置（最长前缀匹配路由规则）
     *
     * @param remotePort 远程端口
     * @param data       请求数据
     * @return 匹配到的ClientProxy或RouteRule
     */
    private ClientProxy resolveProxy(Integer remotePort, Buffer data) {
        ClientProxy proxy = remotePortClientMap.get(remotePort);
        if (proxy == null) {
            return null;
        }
        List<RouteRule> routes = proxy.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return proxy;
        }
        // 按HTTP请求路径前缀匹配
        String dataStr = data.toString();
        Matcher matcher = HTTP_PATH_PATTERN.matcher(dataStr);
        if (!matcher.find()) {
            return proxy;
        }
        // HTTP请求路径
        String requestPath = matcher.group(1);
        RouteRule bestMatch = null;
        int bestLen = -1;
        for (RouteRule route : routes) {
            String location = route.getLocation();
            if (location == null || location.isEmpty() || "/".equals(location)) {
                if (bestMatch == null) {
                    bestMatch = route;
                    bestLen = 0;
                }
                continue;
            }
            if (requestPath.startsWith(location) && location.length() > bestLen) {
                bestMatch = route;
                bestLen = location.length();
            }
        }
        if (bestMatch != null) {
            log.debug("路径[{}]路由匹配：port={}, location=[{}] -> {}:{}", requestPath, remotePort,
                    bestMatch.getLocation(), bestMatch.getHost(), bestMatch.getPort());
        }
        return bestMatch != null ? bestMatch : proxy;
    }

    /**
     * 获取websocket客户端配置
     *
     * @return WebSocketClientOptions
     */
    private WebSocketClientOptions getWebSocketClientOptions() {
        WebSocketClientOptions options = new WebSocketClientOptions();
        options.setTcpKeepAlive(true);
        options.setConnectTimeout(CONNECT_TIMEOUT);
        options.setIdleTimeout(IDLE_TIMEOUT);
        //设置最大消息长度，4M
        options.setMaxMessageSize(BUFFER_SIZE);
        //设置最大帧长度，8M
        options.setMaxFrameSize(BUFFER_SIZE);
        //设置ssl
        options.setSsl(this.properties.getSsl());
        options.setTrustAll(true);
        options.setVerifyHost(false);
        return options;
    }
}

