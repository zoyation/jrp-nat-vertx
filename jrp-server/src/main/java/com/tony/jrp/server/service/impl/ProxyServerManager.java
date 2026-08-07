package com.tony.jrp.server.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.model.RegisterResult;
import com.tony.jrp.common.model.UserProxy;
import com.tony.jrp.server.config.JRPServerProperties;
import com.tony.jrp.server.model.RegisterInfo;
import com.tony.jrp.server.service.IRegisterService;
import com.tony.jrp.server.service.ITraversalService;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.tony.jrp.common.enums.JRPMsgType.TYPE_PORT_LEN;

/**
 * 穿透服务端-代理转发服务管理
 */
@Component
@Slf4j
public class ProxyServerManager implements InitializingBean {
    public static final int IDLE_TIMEOUT = 4;
    public static final int BUFFER_SIZE = 256 * 1024;
    /**
     * 上线
     */
    public static final int STATUS_ONLINE = 1;
    /**
     * 下线
     */
    public static final int STATUS_OFFLINE = 2;
    public static final int PING_DELAY = 2000;
    @Autowired
    protected Vertx vertx;
    /**
     * 配置信息
     */
    @Autowired
    protected JRPServerProperties properties;

    @Autowired
    protected SecurityService securityService;
    /**
     * 请求转发服务
     */
    @Autowired
    protected ITraversalService reverseService;
    /**
     * 注册信息管理
     */
    @Autowired
    protected IRegisterService registerService;

    /**
     * 所有注册成功的内外穿透代理信息
     */
    protected final Map<String, RegisterInfo> registerMap = new ConcurrentHashMap<>();
    /**
     * 所有注册成功的内外穿透代理IP集合，用于校验p2p穿透IP的合法性
     */
    protected final Set<String> registerIpSet = Collections.synchronizedSet(new HashSet<>());

    /**
     * 内网穿透服务客户端打洞服务
     */
    @Data
    private static class TunnelInfo {
        private int remotePort;
        @JsonIgnore
        private ServerWebSocket userWebsocket;
        private SocketAddress userRemoteAddress;
        @JsonIgnore
        private ServerWebSocket lanWebsocket;
        private SocketAddress lanRemoteAddress;
    }

    final Map<Integer, TunnelInfo> tunnelMap = new ConcurrentHashMap<>();

    /**
     * 暴露P2P会话管理器为Spring Bean，供TraversalServiceImpl注入
     */

    @Override
    public void afterPropertiesSet() {
        this.startServer();
        this.startRegisterListener();
    }

    /**
     * 启动管理服务
     */
    private void startServer() {
        Router router = Router.router(vertx);
        Route route = properties.getPagePath() != null ? router.route(properties.getPagePath()) : router.route();
        route.blockingHandler(ctx -> {
            HttpServerRequest request = ctx.request();
            HttpServerResponse response = ctx.response();
            if (request.method() == HttpMethod.HEAD) {
                log.info("HEAD from remote：{}", request.remoteAddress().toString());
                response.setStatusCode(HttpResponseStatus.OK.code()).end();
            } else {
                log.info("remote：{}", request.remoteAddress().toString());
                String host = request.remoteAddress().host();
                String authorization = request.headers().get("authorization");
                //401 Unauthorized
                //www-authenticate: Basic realm="Restricted Area"
                //authorization:Basic bG9uZ3J1YW46TFJANjg4MDc4
                if (securityService.authorize(properties.getUsername(), properties.getPassword(), request.method().name(), host, authorization)) {
                    response.putHeader("content-type", "text/plain");
                    response.setStatusCode(HttpResponseStatus.OK.code());
                    response.end("Welcome to use jrp-server! Your WAN IP is [" + host + "]!");
                } else {
                    response.headers().set("www-authenticate", securityService.getWWWAuthenticate(host));
                    response.setStatusCode(HttpResponseStatus.UNAUTHORIZED.code()).end();
                }
            }
        });
        vertx.createHttpServer().requestHandler(router).listen(properties.getPagePort());
    }

    /**
     * 启动代理注册服务
     */
    private void startRegisterListener() {
        HttpServerOptions serverOptions = getHttpServerOptions();
        serverOptions.setReusePort(true);
        HttpServer vertxHttpServer = vertx.createHttpServer(serverOptions);
        //初始化websocket注册处理器
        vertxHttpServer.webSocketHandler(serverWebSocket -> {
            SocketAddress remoteAddress = serverWebSocket.remoteAddress();
            String textHandlerID = serverWebSocket.textHandlerID();
            serverWebSocket.handler(buffer -> {
                Buffer resultBuffer = Buffer.buffer(JRPMsgType.REGISTER_RESULT.codeArray());
                if (buffer != null && buffer.length() > 0 && buffer.getByte(0) == JRPMsgType.REGISTER.getCode()) {
                    String registerJson = buffer.getString(1, buffer.length());
                    String prettily;
                    try {
                        prettily = new JsonObject(registerJson).encodePrettily();
                        log.info("收到来自[{}]的服务注册信息:\n{}", remoteAddress, prettily);
                    } catch (Exception e) {
                        log.error("收到来自[{}]的非法注册信息:\n{}", remoteAddress, registerJson, e);
                        serverWebSocket.close();
                        return;
                    }
                    ClientRegister clientRegister = Json.decodeValue(registerJson, ClientRegister.class);
                    if (clientRegister != null && this.properties.getToken().equals(clientRegister.getToken())) {
                        log.info("开始启动来自[{}]的注册信息。", remoteAddress);
                        reverseService.start(clientRegister, serverWebSocket).onSuccess(res -> {
                            long serverPing = 0;
                            try {
                                final AtomicBoolean pongReceived = new AtomicBoolean(true);
                                serverWebSocket.pongHandler(pongFrame -> {
                                    log.debug("Pong received:{}", pongFrame.toString());
                                    pongReceived.set(true);
                                });
                                serverPing = vertx.setPeriodic(PING_DELAY, id -> {
                                    if (pongReceived.get()) {
                                        pongReceived.set(false);
                                        serverWebSocket.writePing(Buffer.buffer("server ping"));
                                    } else {
                                        log.warn("来自[{}]的websocket连接没有pong返回！", remoteAddress);
                                    }
                                });
                                long finalServerPing = serverPing;
                                serverWebSocket.closeHandler(handler -> {
                                    registerIpSet.remove(remoteAddress.host());
                                    vertx.cancelTimer(finalServerPing);
                                    RegisterInfo remove = registerMap.remove(textHandlerID);
                                    if (remove != null) {
                                        log.warn("websocket[{}]连接关闭，开始停止代理：{}", remoteAddress, remove);
                                        reverseService.stop(remove.getProxies(), serverWebSocket)
                                                .onSuccess(proxySuccess -> log.info("停止代理成功[{}]！", remoteAddress))
                                                .onFailure(err -> log.error("停止代理失败：{}", err.getMessage(), err));
                                        remove.setStatus(STATUS_OFFLINE);
                                        remove.setOffline_time(new Timestamp(System.currentTimeMillis()));
                                        remove.setRemark("连接关闭");
                                        //更新注册信息
                                        registerService.update(remove);
                                    } else {
                                        log.warn("websocket[{}]关闭，没有代理信息！", remoteAddress);
                                    }
                                });
                                serverWebSocket.exceptionHandler(err -> {
                                    log.error("websocket[{}]代理通信异常：{}，执行关闭！", remoteAddress, err.getMessage(), err);
                                    serverWebSocket.close();
                                });
                                RegisterInfo registerInfo = new RegisterInfo();
                                registerInfo.setWebSocket(serverWebSocket);
                                registerInfo.setId(textHandlerID);
                                registerInfo.setHost(remoteAddress.host());
                                registerInfo.setPort(remoteAddress.port());
                                registerInfo.setClient_id(clientRegister.getId());
                                registerInfo.setName(clientRegister.getName());
                                registerInfo.setToken(clientRegister.getToken());
                                registerInfo.setUsername(clientRegister.getUsername());
                                registerInfo.setPassword(clientRegister.getPassword());
                                registerInfo.setProxies(clientRegister.getProxies());
                                registerInfo.setUserProxies(clientRegister.getUserProxies());
                                registerInfo.setRegister_time(new Timestamp(System.currentTimeMillis()));
                                registerInfo.setStatus(STATUS_ONLINE);
                                registerMap.put(textHandlerID, registerInfo);
                                registerService.add(registerInfo);
                                log.info("来自[{}]的服务注册成功,textHandlerID[{}]:\r\n{}", remoteAddress, textHandlerID, prettily);
                                RegisterResult success = RegisterResult.success("注册成功！");
                                success.setProxies(clientRegister.getProxies());
                                registerIpSet.add(remoteAddress.host());
                                serverWebSocket.write(resultBuffer.appendBuffer(Buffer.buffer(Json.encode(success))));
                            } catch (Exception e) {
                                log.error("来自[{}]的服务注册失败:{}", remoteAddress, e.getMessage(), e);
                                if (serverPing > 0) {
                                    vertx.cancelTimer(serverPing);
                                }
                                if (!serverWebSocket.isClosed()) {
                                    serverWebSocket.end(resultBuffer.appendBuffer(Buffer.buffer(Json.encode(RegisterResult.error(e.getMessage())))));
                                    serverWebSocket.close();
                                }
                                log.warn("websocket[{}]注册异常，开始停止代理：{}", remoteAddress, clientRegister);
                                reverseService.stop(clientRegister.getProxies(), serverWebSocket)
                                        .onSuccess(proxySuccess -> log.info("停止注册异常代理成功！"))
                                        .onFailure(err -> log.error("停止注册异常代理失败：{}", err.getMessage(), err));
                            }
                        }).onFailure(res -> {
                            log.error("来自[{}]的服务注册失败:{}", remoteAddress, res.getMessage(), res);
                            serverWebSocket.end(resultBuffer.appendBuffer(Buffer.buffer(Json.encode(RegisterResult.error(res.getMessage())))));
                        });
                    } else {
                        log.warn("来自[{}]的非法请求，参数无效，操作失败！", remoteAddress.host());
                        serverWebSocket.end(resultBuffer.appendBuffer(Buffer.buffer(Json.encode(RegisterResult.error("非法请求，操作失败！")))));
                    }
                } else {
                    log.warn("来自[{}]的非法无参请求，操作失败！", remoteAddress.host());
                    serverWebSocket.end(resultBuffer.appendBuffer(Buffer.buffer(Json.encode(RegisterResult.error("无参数，操作失败！")))));
                }
            });
        });

        //初始化udp服务用于打洞，用户端、内网客户端都通过该服务打洞
        vertx.createDatagramSocket(new DatagramSocketOptions().setReusePort(true)).listen(this.properties.getRegisterPort(), "0.0.0.0", ar -> {
            if (ar.succeeded()) {
                log.info("代理配置服务UDP服务启动成功，端口[{}]", this.properties.getRegisterPort());
                DatagramSocket result = ar.result();
                result.handler(packet -> {
                    //校验remoteIp是否和用户注册的IP一致
                    if (!registerIpSet.contains(packet.sender().host())) {
                        log.warn("来自[{}]的打洞请求失败，不允许跨域访问！", packet.sender());
                        return;
                    }
                    //查找需要打洞访问的内网服务中转端口
                    Buffer buffer = packet.data();
                    byte msgType = buffer.getByte(0);
                    int remotePort = buffer.getBuffer(JRPMsgType.TYPE_LEN, TYPE_PORT_LEN).getUnsignedShort(0);
                    synchronized (tunnelMap) {
                        //如果已经存在隧道，则直接转发打洞地址到用户端
                        if (msgType == JRPMsgType.UDP_TUNNEL_REQUEST.getCode()) {
                            ServerWebSocket userServerSocket = null;
                            for (RegisterInfo registerInfo : registerMap.values()) {
                                Optional<UserProxy> first = registerInfo.getUserProxies().stream().filter(proxy -> proxy.isEnable() && proxy.getRemote_port().equals(remotePort)).findFirst();
                                if (first.isPresent()) {
                                    userServerSocket = registerInfo.getWebSocket();
                                    break;
                                }
                            }
                            ServerWebSocket lanServerSocket = null;
                            for (RegisterInfo registerInfo : registerMap.values()) {
                                Optional<ClientProxy> first = registerInfo.getProxies().stream().filter(proxy -> proxy.isEnable() && proxy.isEnable_p2p() && proxy.getRemote_port().equals(remotePort)).findFirst();
                                if (first.isPresent()) {
                                    lanServerSocket = registerInfo.getWebSocket();
                                    break;
                                }
                            }
                            if (lanServerSocket != null && userServerSocket != null) {
                                TunnelInfo tunnelInfo = new TunnelInfo();
                                tunnelInfo.setRemotePort(remotePort);
                                tunnelInfo.setUserWebsocket(userServerSocket);
                                tunnelInfo.setLanWebsocket(lanServerSocket);
                                tunnelInfo.setUserRemoteAddress(packet.sender());
                                tunnelMap.put(remotePort, tunnelInfo);
                                String sender = packet.sender().toString();
                                log.info("来自[{}]的打洞请求成功，转发打洞地址到内网客户端！", sender);
                                byte[] remotePortByte = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
                                lanServerSocket.write(Buffer.buffer(TYPE_PORT_LEN + sender.length()).appendByte(JRPMsgType.UDP_TUNNEL_REQUEST.getCode()).appendBytes(remotePortByte).appendBuffer(Buffer.buffer(sender)));
                            } else {
                                log.warn("来自[{}]的打洞请求失败，找不到对应的内网服务中转端口{}！", packet.sender(), remotePort);
                            }
                        } else {
                            TunnelInfo tunnelInfo = tunnelMap.get(remotePort);
                            String sender = packet.sender().toString();
                            tunnelInfo.setLanRemoteAddress(packet.sender());
                            log.info("来自[{}]的打洞请求成功，转发打洞地址到用户端！", sender);
                            byte[] remotePortByte = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
                            result.send(Buffer.buffer(TYPE_PORT_LEN + sender.length()).appendByte(JRPMsgType.UDP_TUNNEL_RESPONSE.getCode()).appendBytes(remotePortByte).appendBuffer(Buffer.buffer(sender)),
                                    tunnelInfo.getUserRemoteAddress().port(), tunnelInfo.getUserRemoteAddress().host());
                        }
                    }
                });
            } else {
                log.error("代理配置服务UDP服务启动失败：{}", ar.cause().getMessage(), ar.cause());
            }
        });
        vertxHttpServer.exceptionHandler(err -> log.error("代理配置服务访问异常：{}", err.getMessage(), err));
        vertxHttpServer.invalidRequestHandler(request -> {
            //n: Invalid escape sequence: %%3
            log.error("[{}]代理配置服务非法访问invalid异常!", request.remoteAddress());
            request.response().setStatusCode(HttpResponseStatus.UNAUTHORIZED.code()).end();
        });
        vertxHttpServer.listen(this.properties.getRegisterPort()).onSuccess(res -> {
            log.info("代理配置服务HTTP服务启动成功，端口[{}]", this.properties.getRegisterPort());
        });
    }

    private HttpServerOptions getHttpServerOptions() {
        HttpServerOptions serverOptions = new HttpServerOptions();
        serverOptions.setRegisterWebSocketWriteHandlers(true);
        serverOptions.setMaxWebSocketMessageSize(BUFFER_SIZE);
        serverOptions.setMaxWebSocketFrameSize(BUFFER_SIZE);
        serverOptions.setIdleTimeout(IDLE_TIMEOUT);
        serverOptions.setTcpKeepAlive(true);
        if (this.properties.isSsl()) {
            serverOptions.setSsl(true);
            if (StringUtils.hasText(properties.getCertPath()) && StringUtils.hasText(properties.getKeyPath())) {
                serverOptions.setKeyCertOptions(new PemKeyCertOptions().setCertPath(properties.getCertPath()).setKeyPath(properties.getKeyPath()));
            } else {
                serverOptions.setKeyCertOptions(securityService.getKeyCertOptions());
            }
        }
        return serverOptions;
    }
}
