package com.tony.jrp.server.verticle;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

/**
 * udp穿透服务
 */
@Slf4j
public class UDPVerticle extends AbstractProxyVerticle {
    /**
     * udp请求处理对象
     */
    DatagramSocket datagramSocket;

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
    }
}
