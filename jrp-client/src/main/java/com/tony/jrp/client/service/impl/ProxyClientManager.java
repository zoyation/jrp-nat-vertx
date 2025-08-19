package com.tony.jrp.client.service.impl;

import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
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
    @Autowired
    protected Vertx vertx;
    @Autowired
    protected IConfigService configService;
    /**
     * 固定参数配置信息
     */
    @Autowired
    protected ProxyClientProperties properties;
    int registerPort;
    String registerHost;
    private HttpServer server;
    private final Object serverLock = new Object();
    //registerWebSocket为null，未注册
    private volatile WebSocket registerWebSocket = null;
    private Long pingTimerId = null;
    List<ClientProxy> clientProxyList = new ArrayList<>();
    String errorMessage = "";
    private final Map<String, NetSocket> netSocketMap = new ConcurrentHashMap<>();
    private final Map<String, DatagramSocket> datagramSocketMap = new ConcurrentHashMap<>();
    ScheduledExecutorService registerService = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> registerSchedule = null;
    private Integer reconnectionTimes = 0;

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
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        String[] registerAddress = properties.getRegisterAddress().split(":");
        registerPort = registerAddress.length == 2 ? Integer.parseInt(registerAddress[1]) : 80;
        registerHost = registerAddress[0];

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
            log.info("start server success，可浏览器访问[{http://127.0.0.1:{}{}}]进行穿透配置。", port, webUrl);
        }
        return server;
    }

    private void restartServer(JsonObject result) {
        ProxyClientConfig newConfig = Json.decodeValue(result.toString(), ProxyClientConfig.class);
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
                Map<String, ClientProxy> remotePortClientMap = registerProxies.stream().collect(Collectors.toMap(r -> r.getRemote_port().toString(), r -> r));
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
    private Boolean tryRegister(ClientRegister register, Map<String, ClientProxy> remotePortClientMap) {
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
                                String prefix = buffer.getString(0, 1);
                                JRPMsgType headMsgType = prefix.equals("{") ? JRPMsgType.REGISTER_RESULT : JRPMsgType.GET;
                                switch (headMsgType) {
                                    case GET:
                                        //代理端口长度1位
                                        int portLen = Integer.parseInt(prefix);
                                        //代理端口
                                        String remotePort = buffer.getString(1, 1 + portLen);
                                        //请求唯一标识长度
                                        int clientLen = Integer.parseInt(buffer.getString(1 + portLen, 1 + portLen + 2));
                                        //请求唯一标识（IP+端口）
                                        String clientId = buffer.getString(1 + portLen + 2, 1 + portLen + 2 + clientLen);
                                        //代理端口长度1位+代理端口+请求唯一标识长度+请求唯一标识（IP+端口）
                                        String msgId = portLen + remotePort + clientLen + clientId;
                                        //收到外网穿透服务器发送的客户端请求通知
                                        Buffer data = buffer.getBuffer(1 + portLen + 2 + clientLen, buffer.length());
                                        log.debug("收到外网穿透服务器转发的客户端请求消息[{}]！", clientId);
                                        try {
                                            if (data.toString().equals(JRPMsgType.CLOSE.getCode())) {
                                                NetSocket netSocket = netSocketMap.get(clientId);
                                                DatagramSocket datagramSocket = datagramSocketMap.get(clientId);
                                                if (netSocket != null || datagramSocket != null) {
                                                    if (netSocket != null) {
                                                        log.debug("收到断开连接请求，关闭TCP连接[{}]。", clientId);
                                                        netSocketMap.remove(clientId);
                                                        netSocket.close();
                                                    }
                                                    if (datagramSocket != null) {
                                                        log.debug("收到断开连接请求，关闭UDP连接[{}]。", clientId);
                                                        datagramSocketMap.remove(clientId);
                                                        datagramSocket.close();
                                                    }
                                                } else {
                                                    log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
                                                }
                                            } else {
                                                ClientProxy proxy = remotePortClientMap.get(remotePort);
                                                vertx.executeBlocking(() -> {
                                                    try {
                                                        receiveMsgAndProxy(msgId, clientId, proxy, data);
                                                        return true;
                                                    } catch (MalformedURLException e) {
                                                        log.error("接受消息失败：{}", e.getMessage(), e);
                                                        return false;
                                                    }
                                                });
                                            }
                                        } catch (Exception e) {
                                            log.error("接受消息失败：{}", e.getMessage(), e);
                                        }
                                        break;
                                    case REGISTER_RESULT:
                                        try {
                                            RegisterResult registerResult = Json.decodeValue(buffer, RegisterResult.class);
                                            if (registerResult.isSuccess()) {
                                                result.set(true);
                                                log.info("注册成功：\r\n{}", new JsonObject(buffer).encodePrettily());
                                                for (ClientProxy proxy : register.getProxies()) {
                                                    //HTTP，HTTPS、TCP、UDP、SOCKS4、SOCKS5
                                                    String message = "HTTP服务[{}]代理后外网地址：[http://{}:{}]！";
                                                    switch (proxy.getType()) {
                                                        case HTTP:
                                                            message = "HTTP服务[{}]代理后外网地址：[http://{}:{}]！";
                                                            break;
                                                        case HTTPS:
                                                            message = "HTTPS服务[{}]代理后外网地址：[https://{}:{}]！";
                                                            break;
                                                        case TCP:
                                                            message = "TCP服务[{}]代理后外网地址：[{}:{}]！";
                                                            break;
                                                        case UDP:
                                                            message = "UDP服务[{}]代理后外网地址：[{}:{}]！";
                                                            break;
                                                        case SOCKS4:
                                                            message = "SOCKS4服务[{}]代理后外网地址：[{}:{}]！";
                                                            break;
                                                        case SOCKS5:
                                                            message = "SOCKS5服务[{}]代理后外网地址：[{}:{}]！";
                                                            break;
                                                    }
                                                    log.info(message, proxy.getProxy_pass(), registerHost, proxy.getRemote_port());
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
                                                errorMessage = registerResult.getMsg();
                                                log.error("注册失败：{}", errorMessage);
                                            }
                                        } catch (Exception e) {
                                            errorMessage = e.getMessage();
                                            log.error("注册异常：{}", errorMessage, e);
                                        } finally {
                                            registerCountDown.countDown();
                                        }
                                        break;
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
                            String registerInfo = Json.encode(register);
                            log.info("开始发送注册消息：\r\n{}", register);
                            webSocket.write(Buffer.buffer(registerInfo)).onComplete((rt) -> {
                                if (rt.succeeded()) {
                                    log.info("发送注册消息成功，等待返回，注册消息为：\r\n{}", register);
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

    private static WebSocketClientOptions getWebSocketClientOptions() {
        WebSocketClientOptions options = new WebSocketClientOptions();
        options.setTcpKeepAlive(true);
        options.setConnectTimeout(CONNECT_TIMEOUT);
        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setMaxMessageSize(BUFFER_SIZE * 2);
        options.setMaxFrameSize(BUFFER_SIZE * 4);
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
            if (!netSocketMap.isEmpty()) {
                log.info("停止转发服务");
                netSocketMap.values().forEach(NetSocket::close);
            }
            if (registerWebSocket != null && !registerWebSocket.isClosed() && close) {
                log.info("关闭registerWebSocket");
                registerWebSocket.close();
            }
        } catch (Exception e) {
            log.error("closeWebSocket error：{}", e.getMessage(), e);
        } finally {
            registerWebSocket = null;
            netSocketMap.clear();
            promise.complete();
        }
        return promise.future();
    }

    /**
     * 接受消息，发请求到内网服务并返回结果
     *
     * @param msgId    消息id
     * @param clientId 请求唯一标识（IP+端口）
     * @param proxy    代理配置信息
     * @param data     数据
     * @throws MalformedURLException url异常
     */
    private void receiveMsgAndProxy(String msgId, String clientId, ClientProxy proxy, Buffer data) throws MalformedURLException {
        ServiceType type = proxy.getType();
        String proxyPass = proxy.getProxy_pass();
        int originPort;
        String originHost;
        try {
            URL url = new URL(proxyPass);
            originPort = url.getPort();
            originHost = url.getHost();
        } catch (Exception e) {
            String[] ipPort = proxyPass.split(":");
            originHost = ipPort[0];
            originPort = Integer.parseInt(ipPort[1]);
        }
        switch (type) {
            case HTTP:
            case HTTPS:
            case TCP: {
                final SocketAddress socketAddress = SocketAddress.inetSocketAddress(originPort, originHost);
                NetSocket netSocket = netSocketMap.get(clientId);
                if (netSocket != null) {
                    //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
                    sendTcpData(data, netSocket);
                } else {
                    synchronized (netSocketMap) {
                        netSocket = netSocketMap.get(clientId);
                        if (netSocket != null) {
                            sendTcpData(data, netSocket);
                        } else {
                            log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, socketAddress.host(), socketAddress.port());
                            CountDownLatch downLatch = new CountDownLatch(1);
                            // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                            NetClientOptions clientOptions = new NetClientOptions();
                            clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                            clientOptions.setSendBufferSize(BUFFER_SIZE);
                            NetClient netClient = vertx.createNetClient(clientOptions);
                            netClient.connect(socketAddress, asyncResult -> {
                                try {
                                    if (asyncResult.succeeded()) {
                                        NetSocket proxySocket = asyncResult.result();
                                        proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                        netSocketMap.put(clientId, proxySocket);
                                        proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                        proxySocket.closeHandler(ch -> {
                                            if (registerWebSocket != null && netSocketMap.remove(clientId) != null) {
                                                log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                                            }
                                        });
                                        proxySocket.handler(response -> {
                                            if (registerWebSocket != null && netSocketMap.get(clientId) != null) {
                                                log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                                //消息标志符+客户端远程ID(ip+端口)长度2位+远程ID
                                                //Integer remotePort = proxy.getRemote_port();
                                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendBuffer(response));
                                            } else {
                                                log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                            }
                                        });
                                        //转发返回消息到内网真实服务器
                                        if (data.length() > 0) {
                                            proxySocket.write(data);
                                        }
                                        log.info("内网代理连接到{}:{}成功！", socketAddress.host(), socketAddress.port());
                                    } else {
                                        log.error("内网代理连接到{}:{}失败：{}！", socketAddress.host(), socketAddress.port(), asyncResult.cause().getMessage(), asyncResult.cause());
                                    }
                                } catch (Exception e) {
                                    log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                    registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                                } finally {
                                    downLatch.countDown();
                                }
                            });
                            try {
                                downLatch.await();
                            } catch (InterruptedException e) {
                                log.error("转发服务连接处理异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                            }
                        }
                    }
                }
                break;
            }
            case UDP: {
                final SocketAddress socketAddress = SocketAddress.inetSocketAddress(originPort, originHost);
                DatagramSocket netSocket = datagramSocketMap.get(clientId);
                if (netSocket != null) {
                    //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
                    sendUdpData(msgId, clientId, data, netSocket, originPort, originHost);
                } else {
                    synchronized (datagramSocketMap) {
                        netSocket = datagramSocketMap.get(clientId);
                        if (netSocket != null) {
                            //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
                            sendUdpData(msgId, clientId, data, netSocket, originPort, originHost);
                        } else {
                            log.info("收到UPD数据[{}]，准备发送到[{}:{}]！", clientId, socketAddress.host(), socketAddress.port());
                            CountDownLatch downLatch = new CountDownLatch(1);
                            // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                            DatagramSocketOptions clientOptions = new DatagramSocketOptions();
                            clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                            clientOptions.setSendBufferSize(BUFFER_SIZE);
                            DatagramSocket netClient = vertx.createDatagramSocket(clientOptions);
                            netClient.exceptionHandler(e -> {
                                log.error("转发udp消息socket异常：{}，发送关闭信息给服务端", e.getMessage(), e);
                                datagramSocketMap.remove(clientId);
                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                            });
                            netClient.handler(socket -> {
                                log.debug("udp原始服务已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendBuffer(socket.data()));
                            });
                            netClient.send(data, originPort, originHost, rs -> {
                                if (rs.succeeded()) {
                                    datagramSocketMap.put(clientId, netClient);
                                } else {
                                    Throwable e = rs.cause();
                                    log.error("转发udp消息到原始服务异常：{}，发送关闭信息给服务端", e.getMessage(), e);
                                    registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                                }
                            });
                            try {
                                downLatch.await();
                            } catch (InterruptedException e) {
                                log.error("udp转发服务连接处理异常：{}，发送关闭信息给服务端", e.getMessage(), e);
                                datagramSocketMap.remove(clientId);
                                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private static void sendTcpData(Buffer data, NetSocket netSocket) {
        netSocket.write(data);
        if (netSocket.writeQueueFull()) {
            netSocket.pause();
            netSocket.drainHandler((done) -> netSocket.resume());
        }
    }

    /**
     * 发送UDP数据
     *
     * @param msgId      消息ID
     * @param clientId   客户端ID
     * @param data       数据
     * @param netSocket  数据发送对象
     * @param originPort 原始服务端口
     * @param originHost 原始服务主机
     */
    private void sendUdpData(String msgId, String clientId, Buffer data, DatagramSocket netSocket, int originPort, String originHost) {
        netSocket.send(data, originPort, originHost, rs -> {
            if (rs.failed()) {
                Throwable e = rs.cause();
                log.error("转发udp消息到原始服务异常：{}，发送关闭信息给服务端", e.getMessage(), e);
                datagramSocketMap.remove(clientId);
                registerWebSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendString(JRPMsgType.CLOSE.getCode()));
            }
        });
    }
}

