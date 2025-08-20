package com.tony.jrp.server.verticle;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.*;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * udp穿透服务
 */
@Slf4j
public class UDPVerticle extends AbstractProxyVerticle {
    public static final String AUTHORIZATION = "Authorization";
    /**
     * udp请求处理对象
     */
    private DatagramSocket datagramSocket;

    HttpServer httpServer;

    public UDPVerticle(ServerWebSocket serverSocket, SecurityService securityService, ClientRegister clientRegister, ClientProxy clientProxy) {
        super(serverSocket, securityService, clientRegister, clientProxy);
    }

    @Override
    public void init() {
        Integer remotePort = clientProxy.getRemote_port();
        // 创建TCP服务器
        DatagramSocketOptions options = new DatagramSocketOptions();
        options.setReceiveBufferSize(BUFFER_SIZE);
        options.setSendBufferSize(BUFFER_SIZE);
        options.setReusePort(true);
        datagramSocket = vertx.createDatagramSocket(options);
        datagramSocket.exceptionHandler(e -> log.error("UDP异常:{}，移除服务端和客户端缓存!", e.getMessage(), e));
        datagramSocket.handler(packet -> {
            SocketAddress socketAddress = packet.sender();
            log.debug("[{}] 收到UDP数据!", socketAddress.toString());
            if (securityService.authorized(socketAddress.host())) {
                String clientAddress = socketAddress.toString();
                //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
                String msgId = remotePort.toString().length() + remotePort.toString() + clientAddress.length() + clientAddress;
                serverSocket.write(Buffer.buffer(msgId).appendBuffer(packet.data()));
            } else {
                log.warn("收到自客户端[{}]的UDP数据，客户端未通过认证，不做处理!", socketAddress);
            }
        });
        datagramSocket.listen(remotePort, "0.0.0.0", (res) -> {
            if (res.succeeded()) {
                log.info("UDP内网穿透代理服务启动成功，代理端口：{}。", remotePort);
            } else {
                log.error("端口[{}]]UDP内网穿透代理服务启动失败：{}", remotePort, res.cause().getMessage(), res.cause());
            }
        });
        httpServer = vertx.createHttpServer(new HttpServerOptions().setReusePort(true));
        Router router = Router.router(vertx);
        router.get("/").handler(context -> {
            HttpServerRequest request = context.request();
            HttpServerResponse response = context.response();
            //尝试HTTP用户名密码信息验证
            HostAndPort authority = request.authority();
            String host = authority.host();
            if (securityService.authorized(host) || securityService.checkHttpAuth(clientRegister, host, request.method().name(), request.getHeader(AUTHORIZATION))) {
                log.debug("UDP客户端[{}]请求验证通过，返回成功提示信息!", authority);
                MultiMap headers = response.headers();
                headers.set("Content-Type", "text/html; charset=utf-8");
                headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
                headers.set("Pragma", "no-cache");
                headers.set("Expires", "0");
                response.end(Buffer.buffer("UDP请求用户名密码验证通过!"));
            } else {
                log.warn("[{}]UDP未授权访问:{}，浏览器弹窗输入认证信息！", authority, remotePort);
                Map<String, String> authenticateHead = securityService.getAuthenticateHead(host);
                for (Map.Entry<String, String> entry : authenticateHead.entrySet()) {
                    response.putHeader(entry.getKey(), entry.getValue());
                }
                response.setStatusCode(HttpResponseStatus.UNAUTHORIZED.code()).end();
            }
        });
        httpServer.requestHandler(router);
        httpServer.listen(remotePort);
    }

    @Override
    public void writeData(String msgId, String clientAddress, Buffer realData) {
        log.debug("收到内网代理服务返回数据并返回给客户端[{}]。", clientAddress);
        //发送udp数据
        if (datagramSocket != null) {
            String[] hostAndPort = clientAddress.split(":");
            datagramSocket.send(realData, Integer.parseInt(hostAndPort[1]), hostAndPort[0]);
        }
    }

    @Override
    public void stop() {
        log.info("清理端口[{}]下代理和缓存！", clientProxy.getRemote_port());
        datagramSocket.close();
        httpServer.close();
    }
}
