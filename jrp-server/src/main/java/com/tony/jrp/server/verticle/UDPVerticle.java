package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * udp穿透服务
 */
@Slf4j
public class UDPVerticle extends AbstractProxyVerticle {
    /**
     * 用户udp请求客户端package
     */
    private final Map<String, DatagramSocket> datagramSocketMap = new ConcurrentHashMap<>();
    /**
     * udp客户端信息缓存
     */
    private final Map<String, SocketAddress> clientUdpPackageMap = new ConcurrentHashMap<>();
    /**
     * udp客户端最后读写时间缓存
     */
    private final Map<String, Long> clientUdpReadWriteTimeMap = new ConcurrentHashMap<>();
    long timeoutPeriodic;

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
        DatagramSocket datagramSocket = vertx.createDatagramSocket(options);
        datagramSocket.exceptionHandler(e -> log.error("UDP异常:{}，移除服务端和客户端缓存!", e.getMessage(), e));
        datagramSocket.handler(packet -> {
            SocketAddress socketAddress = packet.sender();
            log.debug("[{}] 收到UDP数据!", socketAddress.toString());
            if (securityService.authorized(socketAddress.host())) {
                String clientAddress = socketAddress.toString();
                //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
                String msgId = remotePort.toString().length() + remotePort.toString() + clientAddress.length() + clientAddress;
                clientUdpPackageMap.put(clientAddress, packet.sender());
                datagramSocketMap.put(clientAddress, datagramSocket);
                clientUdpReadWriteTimeMap.put(clientAddress, System.currentTimeMillis());
                serverSocket.write(Buffer.buffer(msgId).appendBuffer(packet.data()));
            } else {
                log.warn("收到自客户端[{}]的UDP数据，客户端未通过认证，不做处理!", socketAddress);
            }
        });
        datagramSocket.listen(remotePort, "*", (res) -> {
            if (res.succeeded()) {
                log.info("UDP内网穿透代理服务启动成功，代理端口：{}。", remotePort);
            } else {
                log.error("端口[{}]]UDP内网穿透代理服务启动失败：{}", remotePort, res.cause().getMessage(), res.cause());
            }
        });
        //1秒检测一次是否没有进行通信，如果没有则清理缓存
        int ideTimeout = IDLE_TIMEOUT * 1000;
        timeoutPeriodic = vertx.setPeriodic(1000, (id) -> clientUdpReadWriteTimeMap.entrySet().removeIf(entry -> {
            String clientAddress = entry.getKey();
            boolean timeout = entry.getValue() + ideTimeout < System.currentTimeMillis();
            if (timeout) {
                clientUdpPackageMap.remove(clientAddress);
                clientUdpReadWriteTimeMap.remove(clientAddress);
            }
            return timeout;
        }));
    }

    @Override
    public void writeData(String msgId, String clientAddress, Buffer realData) {
        DatagramSocket datagramSocket = datagramSocketMap.get(clientAddress);
        SocketAddress socketAddress = clientUdpPackageMap.get(clientAddress);
        boolean closeMsg = realData.toString().endsWith(JRPMsgType.CLOSE.getCode());
        if (socketAddress != null) {
            if (closeMsg) {
                log.debug("收到内网代理服务返回的关闭信息[{}]，关闭连接或移除缓存。", clientAddress);
                //移除udp缓存
                clientUdpPackageMap.remove(clientAddress);
                datagramSocketMap.remove(clientAddress);
                clientUdpReadWriteTimeMap.remove(clientAddress);
            } else {
                log.debug("收到内网代理服务返回数据并返回给客户端[{}]。", clientAddress);
                //发送udp数据
                if (datagramSocket != null) {
                    clientUdpReadWriteTimeMap.put(clientAddress, System.currentTimeMillis());
                    datagramSocket.send(realData, socketAddress.port(), socketAddress.host());
                }
            }
        } else if (closeMsg) {
            log.warn("收到内网代理服务返回的udp关闭消息，客户端[{}]连接已经失效，不做处理！", clientAddress);
        } else {
            log.warn("收到内网代理服务返回消息，但是客户端[{}]连接已经失效，发送关闭udp连接消息到内网代理服务！", clientAddress);
            serverSocket.write(Buffer.buffer(msgId).appendBuffer(Buffer.buffer(JRPMsgType.CLOSE.getCode())));
        }
    }

    @Override
    public void stop() {
        log.info("清理端口[{}]下代理和缓存！", clientProxy.getRemote_port());
        vertx.cancelTimer(timeoutPeriodic);
        datagramSocketMap.values().forEach(DatagramSocket::close);
        datagramSocketMap.clear();
        clientUdpPackageMap.clear();
        clientUdpReadWriteTimeMap.clear();
    }
}
