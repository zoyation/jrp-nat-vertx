package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.model.RegisterResult;
import com.tony.jrp.server.config.JRPServerProperties;
import com.tony.jrp.server.service.IReverseService;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.vertx.core.http.HttpServerOptions.DEFAULT_MAX_WEBSOCKET_FRAME_SIZE;
import static io.vertx.core.http.HttpServerOptions.DEFAULT_MAX_WEBSOCKET_MESSAGE_SIZE;

/**
 * 穿透服务端-代理转发服务管理
 */
@Component
@Slf4j
public class ProxyServerManager implements InitializingBean {
    public static final int IDLE_TIMEOUT = 10;
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
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
    protected IReverseService reverseService;

    /**
     * 所有注册成功的内外穿透代理信息
     */
    protected final Map<String, ClientRegister> registerMap = new ConcurrentHashMap<>();

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
                if (securityService.authorize(request.method().name(), host, authorization)) {
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
        HttpServer vertxHttpServer = vertx.createHttpServer(serverOptions);
        vertxHttpServer.webSocketHandler(serverWebSocket -> {
            SocketAddress remoteAddress = serverWebSocket.remoteAddress();
            String textHandlerID = serverWebSocket.textHandlerID();
            serverWebSocket.handler(buffer -> {
                if (buffer != null) {
                    String registerInfo = buffer.toString();
                    String prettily;
                    try {
                        prettily = new JsonObject(registerInfo).encodePrettily();
                        log.info("收到来自[{}]的服务注册信息:\r\n{}", remoteAddress, prettily);
                    } catch (Exception e) {
                        log.error("收到来自[{}]的非法注册信息:\r\n{}", remoteAddress, registerInfo, e);
                        serverWebSocket.close();
                        return;
                    }
                    ClientRegister clientRegister = Json.decodeValue(registerInfo, ClientRegister.class);
                    if (clientRegister != null && this.properties.getToken().equals(clientRegister.getToken())) {
                        reverseService.startReverseProxy(clientRegister, serverWebSocket).onSuccess(res -> {
                            long serverPing = 0;
                            try {
                                final AtomicBoolean pongReceived = new AtomicBoolean(true);
                                serverWebSocket.pongHandler(pongFrame -> {
                                    log.debug("Pong received:{}", pongFrame.toString());
                                    pongReceived.set(true);
                                });
                                serverPing = vertx.setPeriodic(2000, id -> {
                                    if (pongReceived.get()) {
                                        pongReceived.set(false);
                                        serverWebSocket.writePing(Buffer.buffer("server ping"));
                                    } else {
                                        log.warn("来自[{}]的websocket连接没有pong返回！", remoteAddress);
                                    }
                                });
                                long finalServerPing = serverPing;
                                serverWebSocket.closeHandler(handler -> {
                                    vertx.cancelTimer(finalServerPing);
                                    ClientRegister remove = registerMap.remove(textHandlerID);
                                    if (remove != null) {
                                        log.warn("websocket[{}]连接关闭，开始停止代理：{}", remoteAddress, remove);
                                        reverseService.stopReverseProxy(remove, serverWebSocket)
                                                .onSuccess(proxySuccess -> log.info("停止代理成功！"))
                                                .onFailure(err -> log.error("停止代理失败：{}", err.getMessage(), err));
                                    } else {
                                        log.warn("websocket[{}]关闭，没有代理信息！", remoteAddress);
                                    }
                                });
                                serverWebSocket.exceptionHandler(err -> {
                                    log.error("websocket[{}]代理通信异常：{}，执行关闭！", remoteAddress, err.getMessage(), err);
                                    serverWebSocket.close();
                                });
                                log.info("来自[{}]的服务注册成功,textHandlerID[{}]:\r\n{}", remoteAddress, textHandlerID, prettily);
                                serverWebSocket.write(Buffer.buffer(Json.encode(RegisterResult.success("注册成功！"))));
                                registerMap.put(textHandlerID, clientRegister);
                            } catch (Exception e) {
                                log.error("来自[{}]的服务注册失败:{}", remoteAddress, e.getMessage(), e);
                                if (serverPing > 0) {
                                    vertx.cancelTimer(serverPing);
                                }
                                if (!serverWebSocket.isClosed()) {
                                    serverWebSocket.end(Buffer.buffer(Json.encode(RegisterResult.error(e.getMessage()))));
                                    serverWebSocket.close();
                                }
                                log.warn("websocket[{}]注册异常，开始停止代理：{}", remoteAddress, clientRegister);
                                reverseService.stopReverseProxy(clientRegister, serverWebSocket)
                                        .onSuccess(proxySuccess -> log.info("停止注册异常代理成功！"))
                                        .onFailure(err -> log.error("停止注册异常代理失败：{}", err.getMessage(), err));
                            }
                        }).onFailure(res -> {
                            log.error("来自[{}]的服务注册失败:{}", remoteAddress, res.getMessage(), res);
                            serverWebSocket.end(Buffer.buffer(Json.encode(RegisterResult.error(res.getMessage()))));
                        });
                    } else {
                        log.warn("来自[{}]的非法请求，参数无效，操作失败！", remoteAddress.host());
                        serverWebSocket.end(Buffer.buffer(Json.encode(RegisterResult.error("非法请求，操作失败！"))));
                    }
                } else {
                    log.warn("来自[{}]的非法无参请求，操作失败！", remoteAddress.host());
                    serverWebSocket.end(Buffer.buffer(Json.encode(RegisterResult.error("无参数，操作失败！"))));
                }
            });
        });
//        vertxHttpServer.requestHandler(request -> {
//            HttpServerResponse response = request.response();
//            response.putHeader("content-type", "text/plain");
//            response.setStatusCode(HttpResponseStatus.OK.code()).end("Hello from jrp-server!");
//        });
        vertxHttpServer.exceptionHandler(err -> log.error("代理配置服务访问异常：{}", err.getMessage(), err));
        vertxHttpServer.invalidRequestHandler(request -> {
            //n: Invalid escape sequence: %%3
            log.error("[{}]代理配置服务非法访问invalid异常!", request.remoteAddress());
            request.response().setStatusCode(HttpResponseStatus.UNAUTHORIZED.code()).end();
        });
        vertxHttpServer.listen(this.properties.getRegisterPort());
    }

    private static HttpServerOptions getHttpServerOptions() {
        HttpServerOptions serverOptions = new HttpServerOptions();
        serverOptions.setRegisterWebSocketWriteHandlers(true);
        serverOptions.setMaxWebSocketMessageSize(BUFFER_SIZE * 2);
        serverOptions.setMaxWebSocketFrameSize(BUFFER_SIZE * 4);
        serverOptions.setIdleTimeout(IDLE_TIMEOUT);
        serverOptions.setTcpKeepAlive(true);
        return serverOptions;
    }
}
