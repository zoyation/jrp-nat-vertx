package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * sockets消息处理器
 */
@Slf4j
public class SocksProxyHandler extends AbstractProxyHandler {
    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();
    /**
     * 代理转发目标信息缓存
     */
    private final Map<Integer, TargetInfo> targetMap = new ConcurrentHashMap<>();

    /**
     * 代理转发目标信息缓存
     */
    @Data
    private static class TargetInfo {
        /**
         * 代理转发地址，格式：ip:port
         */
        private String proxyPass;
        /**
         * 原始请求host
         */
        private String host;
        /**
         * 原始请求host
         */
        private int port;

        /**
         * @param host 请求 host
         * @param port 请求端口
         * @return
         */
        public static TargetInfo of(String host, int port) {
            TargetInfo targetInfo = new TargetInfo();
            targetInfo.setProxyPass(host + ":" + port);
            targetInfo.setHost(host);
            targetInfo.setPort(port);
            return targetInfo;
        }
    }

    public SocksProxyHandler(Vertx vertx) {
        super(vertx);
    }

    @Override
    public void closeSocket(Integer clientId) {
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            log.debug("收到断开连接请求，关闭SOCKS TCP连接[{}]。", clientId);
            netSocketMap.remove(clientId);
            targetMap.remove(clientId);
            netSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        NetSocket netSocket = netSocketMap.get(clientId);
        TargetInfo targetInfo = targetMap.get(clientId);
        if (netSocket != null && targetInfo != null) {
            sendTcpData(targetInfo.getHost(), targetInfo.getProxyPass(), data, netSocket);
        } else {
            synchronized (netSocketMap) {
                netSocket = netSocketMap.get(clientId);
                targetInfo = targetMap.get(clientId);
                if (netSocket != null && targetInfo != null) {
                    sendTcpData(targetInfo.getHost(), targetInfo.getProxyPass(), data, netSocket);
                } else {
                    //首次创建TCP连接，格式：TCP:IP:PORT
                    String connectStr = data.toString();
                    String protocol = connectStr.substring(0, connectStr.indexOf(":"));
                    String targetHost = connectStr.substring(connectStr.indexOf(":") + 1, connectStr.lastIndexOf(":"));
                    int targetPort = data.getInt(protocol.length() + targetHost.length() + 2);
                    final SocketAddress socketAddress = SocketAddress.inetSocketAddress(targetPort, targetHost);
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, targetHost, targetPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                    NetClientOptions clientOptions = new NetClientOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    clientOptions.setTrustAll(true);
                    clientOptions.setConnectTimeout(CONNECT_TIMEOUT);
                    NetClient netClient = vertx.createNetClient(clientOptions);
                    netClient.connect(socketAddress, asyncResult -> {
                        try {
                            if (asyncResult.succeeded()) {
                                NetSocket proxySocket = asyncResult.result();
                                proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                netSocketMap.put(clientId, proxySocket);
                                targetMap.put(clientId, TargetInfo.of(targetHost, targetPort));
                                proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                proxySocket.closeHandler(ch -> {
                                    if (webSocket != null && netSocketMap.remove(clientId) != null) {
                                        targetMap.remove(clientId);
                                        log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                        webSocket.write(closeBuffer(msgId));
                                    }
                                });
                                proxySocket.handler(response -> {
                                    if (webSocket != null && netSocketMap.get(clientId) != null) {
                                        log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                        webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                    } else {
                                        log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                    }
                                });
                                // 返回给服务端代表连接成功
                                webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId));
                                log.info("内网代理连接到{}:{}成功！", socketAddress.host(), socketAddress.port());
                            } else {
                                log.error("内网代理连接到{}:{}失败：{}！", socketAddress.host(), socketAddress.port(), asyncResult.cause().getMessage(), asyncResult.cause());
                            }
                        } catch (Exception e) {
                            log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                            webSocket.write(closeBuffer(msgId));
                        } finally {
                            downLatch.countDown();
                        }
                    });
                    try {
                        downLatch.await();
                    } catch (InterruptedException e) {
                        log.error("转发服务连接处理异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                        webSocket.write(closeBuffer(msgId));
                    }
                }
            }
        }
    }

    /**
     * 发送TCP数据
     *
     * @param originHost 原始服务主机
     * @param proxyPass  代理服务地址
     * @param data       数据
     * @param netSocket  数据发送对象
     */
    private static void sendTcpData(String originHost, String proxyPass, Buffer data, NetSocket netSocket) {
        if (data.toString().contains("Host:")) {
            //替换Host和Referer值，避免被内网服务器拦截，尤其是跨域请求
            netSocket.write(Buffer.buffer(data.toString().replaceAll("Host: .*", "Host: " + originHost).replaceAll("Referer:.*", "referer: " + proxyPass)));
        } else {
            netSocket.write(data);
        }
        if (netSocket.writeQueueFull()) {
            netSocket.pause();
            netSocket.drainHandler((done) -> netSocket.resume());
        }
    }

    @Override
    public void close() throws IOException {
        if (!netSocketMap.isEmpty()) {
            log.info("停止SOCKS TCP转发服务");
            netSocketMap.values().forEach(NetSocket::close);
            netSocketMap.clear();
            targetMap.clear();
        }
    }
}
