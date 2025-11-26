package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ServiceType;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * sockets消息处理器
 */
@Slf4j
public class SocksProxyHandler extends AbstractProxyHandler {
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();

    public SocksProxyHandler(Vertx vertx) {
        super(vertx);
    }

    @Override
    public void closeSocket(Integer clientId) {
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            log.debug("收到断开连接请求，关闭TCP连接[{}]。", clientId);
            netSocketMap.remove(clientId);
            netSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, String proxyPass, Buffer data) {
        int originPort;
        String originHost;
        boolean https;
        URL url;
        try {
            url = new URL(proxyPass);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        originPort = url.getPort();
        originHost = url.getHost();
        String protocol = url.getProtocol().toLowerCase();
        https = protocol.equals(ServiceType.HTTPS.name().toLowerCase());
        if (originPort == -1) {
            originPort = https ? 443 : 80;
        }
        final SocketAddress socketAddress = SocketAddress.inetSocketAddress(originPort, originHost);
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
            sendTcpData(originHost, proxyPass, data, netSocket);
        } else {
            synchronized (netSocketMap) {
                netSocket = netSocketMap.get(clientId);
                if (netSocket != null) {
                    sendTcpData(originHost, proxyPass, data, netSocket);
                } else {
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, originHost, originPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                    NetClientOptions clientOptions = new NetClientOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    if (https) {
                        clientOptions.setSsl(true);
                        clientOptions.setTrustAll(true);
                    }
                    NetClient netClient = vertx.createNetClient(clientOptions);
                    netClient.connect(socketAddress, asyncResult -> {
                        try {
                            if (asyncResult.succeeded()) {
                                NetSocket proxySocket = asyncResult.result();
                                proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                netSocketMap.put(clientId, proxySocket);
                                proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                proxySocket.closeHandler(ch -> {
                                    if (webSocket != null && netSocketMap.remove(clientId) != null) {
                                        log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                        webSocket.write(closeBuffer(msgId));
                                    }
                                });
                                proxySocket.handler(response -> {
                                    if (webSocket != null && netSocketMap.get(clientId) != null) {
                                        log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                        //消息标志符+客户端远程ID(ip+端口)长度2位+远程ID
                                        //Integer remotePort = proxy.getRemote_port();
                                        webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                    } else {
                                        log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                    }
                                });
                                //转发返回消息到内网真实服务器
                                if (data.length() > 0) {
                                    sendTcpData(originHost, proxyPass, data, proxySocket);
                                }
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
            //替换Host和Referer值，避免被内网服务器拦截
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
            log.info("停止TCP转发服务");
            netSocketMap.values().forEach(NetSocket::close);
            netSocketMap.clear();
        }
    }
}
