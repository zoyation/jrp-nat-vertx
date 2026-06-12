package com.tony.jrp.client.service.impl;

import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
import com.tony.jrp.client.handler.AbstractProxyHandler;
import com.tony.jrp.client.handler.ForwardProxyHandler;
import com.tony.jrp.client.handler.TcpReverseProxyHandler;
import com.tony.jrp.client.handler.UdpReverseProxyHandler;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ServiceType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.model.RegisterResult;
import com.tony.jrp.common.utils.CPUUtils;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
     * 重连间隔5秒
     */
    public static final int RECONNECT_DELAY = 5000;
    /**
     * ping间隔2秒
     */
    public static final int PING_DELAY = 2000;
    public static final String JRP_CLIENT_CONFIG = "jrp-client-config";
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

    private HttpServer server;
    private final Object serverLock = new Object();
    private int registerPort;
    private String registerHost;
    private volatile ClientRegister register = null;
    //registerWebSocket为null，未注册
    private volatile WebSocket registerWebSocket = null;
    private volatile Long pingTimerId = null;
    private final AtomicInteger reconnectionTimes = new AtomicInteger(0);
    private String errorMessage = "";
    Map<Integer, ClientProxy> remotePortClientMap = new ConcurrentHashMap<>();
    private final Map<ServiceType, AbstractProxyHandler> handlerMap = new ConcurrentHashMap<>();

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
            log.info("停止[{}]穿透转发服务", entry.getKey().name());
            entry.getValue().close();
        }
        handlerMap.clear();
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
        String webUrl = path + "/web";
        router.route(HttpMethod.GET, "/").handler(ctx -> ctx.redirect(webUrl));
        router.route(HttpMethod.GET, webUrl + "/*").handler(dist);
        //获取配置信息
        router.route(HttpMethod.GET, path + "/config/list").handler(ctx -> configService.list(ctx));
        //保存配置信息
        router.route(HttpMethod.POST, path + "/config/save").handler(ctx -> {
            if (this.register != null && this.register.isUpdated()) {
                log.info("标识需要发送更新穿透配置信息");
                this.register.setUpdated(false);
            }
            configService.save(ctx);
        });
        //更新穿透状态
        router.route(HttpMethod.GET, path + "/config/status").handler(ctx -> configService.end(() -> {
            RegisterStatus registerStatus = registerWebSocket == null ? RegisterStatus.fail(errorMessage, registerHost) : RegisterStatus.ok("穿透成功！", registerHost);
            return Json.encode(registerStatus);
        }, ctx));
        server.requestHandler(router);
        server.listen(port);
        if (log.isInfoEnabled()) {
            log.info("start server success，可浏览器访问[http://127.0.0.1:{}{}]进行穿透配置。", port, webUrl);
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
                // 构建更新消息
                ClientRegister register = new ClientRegister();
                register.setId(CPUUtils.getCpuId());
                register.setToken(properties.getToken());
                register.setUsername(properties.getUsername());
                register.setPassword(properties.getPassword());
                this.register.setUpdated(false);
                // 处理代理配置
                List<ClientProxy> updatedProxies = new ArrayList<>();
                for (ClientProxy proxy : newConfig.getRemote_proxies()) {
                    ClientProxy updatedProxy = new ClientProxy();
                    updatedProxy.setId(proxy.getId());
                    updatedProxy.setName(proxy.getName());
                    updatedProxy.setType(proxy.getType());
                    updatedProxy.setRemote_port(proxy.getRemote_port());

                    // 格式 host:port
                    String proxyPass = proxy.getProxy_pass();
                    if (StringUtils.hasText(proxyPass)) {
                        updatedProxy.setProxy_pass(proxyPass);
                        // 去掉http://、https://等前缀
                        String lowerCasePass = proxyPass.toLowerCase().trim();
                        boolean https = lowerCasePass.startsWith("https");
                        updatedProxy.setHttps(https);
                        proxyPass = lowerCasePass.replaceAll("^https?://", "");
                        int index = proxyPass.lastIndexOf(":");
                        if (index > 0) {
                            updatedProxy.setHost(proxyPass.substring(0, index));
                            updatedProxy.setPort(Integer.parseInt(proxyPass.substring(index + 1)));
                        } else {
                            updatedProxy.setHost(proxyPass);
                            updatedProxy.setPort(https ? 443 : 80);
                        }
                    }
                    updatedProxies.add(updatedProxy);
                }
                register.setProxies(updatedProxies);

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
            startRegister(newConfig.getRemote_proxies());
        }
    }

    /**
     * 开始注册
     *
     * @param remoteProxies 穿透配置信息
     */
    private void startRegister(List<ClientProxy> remoteProxies) {
        try {
            List<ClientProxy> registerProxies;
            if (remoteProxies != null) {
                registerProxies = remoteProxies;
            } else {
                registerProxies = Collections.emptyList();
            }
            if (registerProxies.isEmpty()) {
                log.warn("没有穿透配置信息，配置穿透信息后进行注册！");
                return;
            }
            ClientRegister register = new ClientRegister();
            register.setId(CPUUtils.getCpuId());
            register.setToken(properties.getToken());
            register.setUsername(properties.getUsername());
            register.setPassword(properties.getPassword());
            for (ClientProxy proxy : registerProxies) {
                //格式 host:port
                String proxyPass = proxy.getProxy_pass();
                if (StringUtils.hasText(proxyPass)) {
                    //去掉http://、https://等前缀
                    String lowerCasePass = proxyPass.toLowerCase().trim();
                    boolean https = lowerCasePass.startsWith("https");
                    proxy.setHttps(https);
                    proxyPass = lowerCasePass.replaceAll("^https?://", "");
                    int index = proxyPass.lastIndexOf(":");
                    if (index > 0) {
                        proxy.setHost(proxyPass.substring(0, index));
                        proxy.setPort(Integer.parseInt(proxyPass.substring(index + 1)));
                    } else {
                        proxy.setHost(proxyPass);
                        proxy.setPort(https ? 443 : 80);
                    }
                }
            }
            register.setProxies(registerProxies);
            remotePortClientMap = registerProxies.stream().filter(r -> r.getRemote_port() != null).collect(Collectors.toMap(ClientProxy::getRemote_port, r -> r));
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
                                    this.closeAndCreateProxyHandler();
                                    register.setUpdated(false);
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
                                    updateProxies(register, registerResult);
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
                            receiveData(webSocket, buffer, msgType);
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
                    webSocket.close();
                }
            }
        });
        return registerPromise.future();
    }

    /**
     * @param register       注册信息
     * @param registerResult 注册结果
     *                       更新代理成功后代理数据（外网端口可能是服务端返回）
     */
    private void updateProxies(ClientRegister register, RegisterResult registerResult) {
        List<ClientProxy> proxies = registerResult.getProxies();
        if (proxies != null) {
            configService.save(proxies);
            register.setProxies(proxies);
        } else {
            proxies = register.getProxies();
        }
        remotePortClientMap = proxies.stream().collect(Collectors.toMap(ClientProxy::getRemote_port, r -> r));
        this.register = register;
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
    }

    /**
     * 接收到服务端返回的消息
     *
     * @param webSocket websocket隧道
     * @param buffer    数据
     * @param msgType   消息类型
     */
    private void receiveData(WebSocket webSocket, Buffer buffer, byte msgType) {
        //代理端口
        Integer remotePort = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_LEN).getUnsignedShort(0);
        //请求唯一标识,代理端口之后开始取
        Integer requestId = buffer.getBuffer(TYPE_PORT_LEN, TYPE_PORT_REQUEST_ID_LEN).getInt(0);
        //获取消息标识：代理端口+请求id，消息类型之后取
        Buffer msgId = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_REQUEST_ID_LEN);
        //收到外网穿透服务器发送的客户端请求通知
        Buffer data = buffer.getBuffer(TYPE_PORT_REQUEST_ID_LEN, buffer.length());
        log.debug("收到外网穿透服务器转发的客户端请求消息[{}]！", requestId);
        ClientProxy proxy = remotePortClientMap.get(remotePort);
        if (proxy == null) {
            log.warn("未找到代理端口[{}]对应的客户端！", remotePort);
            return;
        }
        switch (proxy.getType()) {
            case HTTP:
            case HTTPS:
            case TCP:
                handlerMap.get(ServiceType.TCP).handle(webSocket, msgType, msgId, requestId, proxy, data);
                break;
            case UDP:
                handlerMap.get(ServiceType.UDP).handle(webSocket, msgType, msgId, requestId, proxy, data);
                break;
            case HTTP_PROXY:
            case HTTPS_PROXY:
            case SOCKS4:
            case SOCKS5:
            case SMART_PROXY:
                handlerMap.get(ServiceType.SMART_PROXY).handle(webSocket, msgType, msgId, requestId, proxy, data);
                break;
        }
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

