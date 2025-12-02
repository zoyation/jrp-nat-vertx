package com.tony.jrp.client.service.impl;

import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
import com.tony.jrp.client.handler.*;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.model.RegisterResult;
import com.tony.jrp.common.utils.CPUUtils;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.core.Future;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 客户端-穿透配置管理
 */
@Component
@Slf4j
public class ProxyClientManager implements InitializingBean {
    public static final int CONNECT_TIMEOUT = 1000;
    public static final int IDLE_TIMEOUT = 10;
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * 消息类型和端口byte长度
     */
    public static final int TYPE_PORT_LEN = JRPMsgType.TYPE_LEN + 2;
    /**
     * 消息类型、端口和请求id byte长度
     */
    public static final int TYPE_PORT_REQUEST_ID_LEN = JRPMsgType.TYPE_LEN + 2 + 4;
    @Autowired
    protected Vertx vertx;
    @Autowired
    protected IConfigService configService;
    /**
     * 固定参数配置信息
     */
    @Autowired
    protected ProxyClientProperties properties;
    private final Object serverLock = new Object();
    List<ClientProxy> clientProxyList = new ArrayList<>();
    private HttpServer server;
    private int registerPort;
    private String registerHost;
    private final ScheduledExecutorService registerService = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> registerSchedule = null;
    private Integer reconnectionTimes = 0;
    //registerWebSocket为null，未注册
    private volatile WebSocket registerWebSocket = null;
    private Long pingTimerId = null;
    private String errorMessage = "";
    //private final Map<String, NetSocket> netSocketMap = new ConcurrentHashMap<>();

    //private final Map<String, DatagramSocket> datagramSocketMap = new ConcurrentHashMap<>();
    /**
     * TCP代理处理器
     */
    private AbstractProxyHandler tcpProxyHandler = null;
    /**
     * UDP代理处理器
     */
    private AbstractProxyHandler udpProxyHandler = null;

    /**
     * http正向代理穿透处理器
     */
    private AbstractProxyHandler httpForwardHandler = null;
    /**
     * socks4/5正向代理穿透处理器
     */
    private AbstractProxyHandler socksProxyHandler = null;

    @Data
    private static class RegisterStatus {
        private Boolean success;
        private String message;
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
            restartServer(newConfig);
            //eventBus.publish(CONFIG_CHANGE, json);
        });
    }

    /**
     * 创建代理处理器
     */
    private void closeAndCreateProxyHandler() throws IOException {
        closeProxySocket();
        tcpProxyHandler = new TcpReverseProxyHandler(vertx);
        udpProxyHandler = new UdpReverseProxyHandler(vertx);
        httpForwardHandler = new HttpForwardProxyHandler(vertx);
        socksProxyHandler = new Socks5ProxyHandler(vertx);
    }

    private void closeProxySocket() throws IOException {
        if (tcpProxyHandler != null) {
            log.info("停止TCP穿透转发服务");
            tcpProxyHandler.close();
        }
        if (udpProxyHandler != null) {
            log.info("停止UDP穿透转发服务");
            udpProxyHandler.close();
        }
        if (httpForwardHandler != null) {
            log.info("停止http正向代理穿透转发服务");
            httpForwardHandler.close();
        }
        if (socksProxyHandler != null) {
            log.info("停止socks正向代理穿透转发服务");
            socksProxyHandler.close();
        }
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
        router.route(HttpMethod.POST, path + "/config/save").handler(ctx -> configService.save(ctx));
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
        if (result.containsKey("jrp-client-config")) {
            jsonStr = result.getString("jrp-client-config");
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
        reloadProxies(newConfig.getRemote_proxies());
    }

    public void reloadProxies(List<ClientProxy> remoteProxies) {
        reconnectionTimes = 0;
        if (registerSchedule != null) {
            log.info("停止注册任务...");
            registerSchedule.cancel(true);
            log.info("停止注册任务完成");
        }
        this.closeSocket(true).onSuccess(success -> {
            try {
                ClientRegister register = new ClientRegister();
                register.setId(CPUUtils.getCpuId());
                register.setToken(properties.getToken());
                register.setUsername(properties.getUsername());
                register.setPassword(properties.getPassword());
                List<ClientProxy> registerProxies = new ArrayList<>();
                if (remoteProxies != null) {
                    registerProxies.addAll(remoteProxies);
                }
                register.setProxies(registerProxies);
                Map<Integer, ClientProxy> remotePortClientMap = registerProxies.stream().collect(Collectors.toMap(ClientProxy::getRemote_port, r -> r));
                log.info("开始注册...");
                vertx.executeBlocking(() -> tryRegister(register, remotePortClientMap)).onComplete(result -> {
                    if (registerSchedule != null) {
                        registerSchedule.cancel(true);
                    }
                    //间隔五秒进行断线判断，如果断线重新注册
                    registerSchedule = registerService.scheduleWithFixedDelay(() -> {
                        if (registerWebSocket == null) {
                            if (reconnectionTimes >= properties.getReconnectionTimes()) {
                                log.warn("与外网穿透服务断开连接或未注册，断线重连次数已达限制次数[{}]，不再重连!", properties.getReconnectionTimes());
                                registerSchedule.cancel(false);
                            } else {
                                reconnectionTimes = reconnectionTimes + 1;
                                log.info("与外网穿透服务断开连接或未注册，尝试第[{}]次注册...", reconnectionTimes);
                                if (tryRegister(register, remotePortClientMap)) {
                                    reconnectionTimes = 0;
                                }
                            }
                        }
                    }, 5, 5, TimeUnit.SECONDS);
                });
            } catch (Exception e) {//注册失败
                String errorMessage = e.getMessage();
                log.error("内网穿透注册失败：{}", errorMessage, e);
                registerWebSocket = null;
            }
        });
    }

    /**
     * @param register            穿透注册信息
     * @param remotePortClientMap 穿透注册信息map
     */
    private Boolean tryRegister(ClientRegister register, Map<Integer, ClientProxy> remotePortClientMap) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        if (registerWebSocket == null) {
            synchronized (ProxyClientManager.this) {
                if (registerWebSocket == null) {
                    WebSocketClientOptions options = getWebSocketClientOptions();
                    CountDownLatch registerCountDown = new CountDownLatch(1);
                    WebSocketConnectOptions connectOptions = new WebSocketConnectOptions().setPort(registerPort).setHost(registerHost).setURI("/").setConnectTimeout(CONNECT_TIMEOUT);
                    connectOptions.setRegisterWriteHandlers(true);
                    vertx.createWebSocketClient(options).connect(connectOptions).onComplete(webSocket -> {
                        // 设置处理pong的回调
                        try {
                            final AtomicBoolean pongReceived = new AtomicBoolean(true);
                            webSocket.pongHandler(pongFrame -> {
                                pongReceived.set(true);
                                log.debug("Pong received:{}", pongFrame.toString());
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
                                    case REGISTER_RESULT:
                                        try {
                                            RegisterResult registerResult = Json.decodeValue(buffer.getBuffer(1, buffer.length()), RegisterResult.class);
                                            if (registerResult.isSuccess()) {
                                                result.set(true);
                                                log.info("注册成功：\r\n{}", new JsonObject(buffer.getBuffer(1, buffer.length())).encodePrettily());
                                                this.closeAndCreateProxyHandler();
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
                                                                message = "HTTP代理服务穿透后外网地址：[http://%s:%s]！";
                                                                logMessage = String.format(message, registerHost, proxy.getRemote_port());
                                                                break;
                                                            case HTTPS_PROXY:
                                                                message = "HTTPS代理服务穿透后外网地址：[https://%s:%s]！";
                                                                logMessage = String.format(message, registerHost, proxy.getRemote_port());
                                                                break;
                                                            case SOCKS4:
                                                                message = "SOCKS4代理服务穿透后外网地址：[%s:%s]！";
                                                                logMessage = String.format(message, registerHost, proxy.getRemote_port());
                                                                break;
                                                            case SOCKS5:
                                                                message = "SOCKS5代理服务穿透后外网地址：[%s:%s]！";
                                                                logMessage = String.format(message, registerHost, proxy.getRemote_port());
                                                                break;
                                                        }
                                                        log.info(logMessage);
                                                    }
                                                }
                                                clientProxyList = register.getProxies();
                                                registerWebSocket = webSocket;
                                                webSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                                pingTimerId = vertx.setPeriodic(5000, id -> {
                                                    if (pongReceived.get()) {
                                                        pongReceived.set(false);
                                                        webSocket.writePing(Buffer.buffer("ping")).timeout(1, TimeUnit.SECONDS).onComplete(prs -> {
                                                            if (!prs.succeeded()) {
                                                                log.error("ping失败：{}", prs.cause().getMessage(), prs.cause());
                                                                vertx.cancelTimer(id);
                                                                closeSocket(true);
                                                            }
                                                        });
                                                    } else {
                                                        log.warn("未收到服务端[{}]pong消息！", registerWebSocket.remoteAddress().toString());
                                                    }
                                                });
                                            } else {
                                                webSocket.close();
                                                errorMessage = registerResult.getMsg();
                                                log.error("注册失败：{}", errorMessage);
                                            }
                                        } catch (Exception e) {
                                            webSocket.close();
                                            errorMessage = e.getMessage();
                                            log.error("注册异常：{}", errorMessage, e);
                                        } finally {
                                            registerCountDown.countDown();
                                        }
                                        break;
                                    case CLOSE:
                                    case RECEIVE:
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
                                        switch (proxy.getType()) {
                                            case HTTP:
                                            case HTTPS:
                                            case TCP:
                                                tcpProxyHandler.handle(webSocket, msgType, msgId, requestId, proxy, data);
                                                break;
                                            case UDP:
                                                udpProxyHandler.handle(webSocket, msgType, msgId, requestId, proxy, data);
                                                break;
                                            case HTTP_PROXY:
                                            case HTTPS_PROXY:
                                                httpForwardHandler.handle(webSocket, msgType, msgId, requestId, proxy, data);
                                                break;
                                            case SOCKS4:
                                            case SOCKS5:
                                                socksProxyHandler.handle(webSocket, msgType, msgId, requestId, proxy, data);
                                                break;
                                        }
                                }
                            });
                            webSocket.closeHandler(closeHandler -> {
                                log.warn("websocket 连接断开：{}", webSocket.remoteAddress());
                                if (registerWebSocket == null) {
                                    registerCountDown.countDown();
                                }
                                closeSocket(false);
                            });
                            webSocket.exceptionHandler(err -> {
                                log.error("websocket 连接异常：{}", err.getMessage(), err);
                                if (registerWebSocket == null) {
                                    registerCountDown.countDown();
                                }
                                closeSocket(true);
                            });
                            String registerInfo = Json.encodePrettily(register);
                            log.info("开始发送注册消息：\r\n{}", registerInfo);
                            webSocket.write(Buffer.buffer(JRPMsgType.REGISTER.codeArray()).appendBuffer(Buffer.buffer(registerInfo))).onComplete((rt) -> {
                                if (rt.succeeded()) {
                                    log.info("发送注册消息成功，等待返回!");
                                } else {
                                    errorMessage = rt.cause().getMessage();
                                    log.info("发送注册消息失败，error：{}", rt.cause().getMessage(), rt.cause());
                                    registerCountDown.countDown();
                                }
                            });
                        } catch (Exception e) {
                            closeSocket(true);
                            registerCountDown.countDown();
                            errorMessage = e.getMessage();
                            log.error("websocket 连接初始化异常：{}", e.getMessage(), e);
                        }
                    }, err -> {
                        errorMessage = err.getMessage();
                        log.error("websocket 初始化异常：{}", err.getMessage(), err);
                        registerCountDown.countDown();
                    });
                    try {
                        boolean countDown = registerCountDown.await(10, TimeUnit.SECONDS);
                        if (!countDown) {
                            log.warn("websocket 注册超时！");
                        }
                    } catch (InterruptedException err) {
                        log.error("websocket 初始化异常：{}", err.getMessage(), err);
                    }
                } else {
                    result.set(true);
                }
            }
        } else {
            result.set(true);
        }
        return result.get();

    }

    private WebSocketClientOptions getWebSocketClientOptions() {
        WebSocketClientOptions options = new WebSocketClientOptions();
        options.setTcpKeepAlive(true);
        options.setConnectTimeout(CONNECT_TIMEOUT);
        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setMaxMessageSize(BUFFER_SIZE * 2);
        options.setMaxFrameSize(BUFFER_SIZE * 4);
        options.setSsl(this.properties.getSsl());
        options.setTrustAll(true);
        options.setVerifyHost(false);
        return options;
    }

    /**
     * 关闭socket
     *
     * @param close 是否调用registerWebSocket的关闭方法
     * @return Future<Boolean> 关闭结果
     */
    private Future<Boolean> closeSocket(Boolean close) {
        Promise<Boolean> promise = Promise.promise();
        try {
            if (pingTimerId != null) {
                log.info("取消ping任务:{}", pingTimerId);
                vertx.cancelTimer(pingTimerId);
                pingTimerId = null;
            }
            this.closeProxySocket();
//            if (!netSocketMap.isEmpty()) {
//                log.info("停止TCP转发服务");
//                netSocketMap.values().forEach(NetSocket::close);
//                netSocketMap.clear();
//            }
//            if (!datagramSocketMap.isEmpty()) {
//                log.info("停止UDP转发服务");
//                datagramSocketMap.values().forEach(DatagramSocket::close);
//                udpReadOrWriteTimeMap.clear();
//            }
            if (registerWebSocket != null && !registerWebSocket.isClosed() && close) {
                log.info("关闭registerWebSocket");
                registerWebSocket.close();
            }
        } catch (Exception e) {
            log.error("closeWebSocket error：{}", e.getMessage(), e);
        } finally {
            registerWebSocket = null;
//            netSocketMap.clear();
//            datagramSocketMap.clear();
//            udpReadOrWriteTimeMap.clear();
            promise.complete();
        }
        return promise.future();
    }
}

