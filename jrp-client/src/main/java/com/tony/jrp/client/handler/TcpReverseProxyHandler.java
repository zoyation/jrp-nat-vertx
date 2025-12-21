package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * tcp消息处理器
 */
@Slf4j
public class TcpReverseProxyHandler extends AbstractProxyHandler {

    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();

    public TcpReverseProxyHandler(Vertx vertx) {
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
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        String proxyPass = clientProxy.getProxy_pass();
        int originPort = clientProxy.getPort();
        String originHost = clientProxy.getHost();
        boolean https = clientProxy.isHttps();
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            sendTcpData(clientProxy, data, netSocket);
        } else {
            synchronized (netSocketMap) {
                netSocket = netSocketMap.get(clientId);
                if (netSocket != null) {
                    sendTcpData(clientProxy, data, netSocket);
                } else {
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, originHost, originPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                    NetClientOptions clientOptions = new NetClientOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    clientOptions.setConnectTimeout(CONNECT_TIMEOUT);
                    if (https) {
                        clientOptions.setSsl(true);
                        clientOptions.setTrustAll(true);
                    }
                    NetClient netClient = vertx.createNetClient(clientOptions);
                    netClient.connect(originPort, originHost, asyncResult -> {
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
                                        webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                    } else {
                                        log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                    }
                                });
                                //转发返回消息到内网真实服务器
                                if (data.length() > 0) {
                                    sendTcpData(clientProxy, data, proxySocket);
                                }
                                log.info("内网代理连接到{}:{}成功！", originHost, originPort);
                            } else {
                                log.error("内网代理连接到{}:{}失败：{}！", originHost, originPort, asyncResult.cause().getMessage(), asyncResult.cause());
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
     * @param clientProxy 代理配置信息
     * @param data        数据
     * @param netSocket   数据发送对象
     */
    private static void sendTcpData(ClientProxy clientProxy, Buffer data, NetSocket netSocket) {
        String dataStr = data.toString();
        if (dataStr.contains("Host:")) {
            //替换Host和Referer值，避免被内网服务器拦截
            // 替换 Host 值
            dataStr = dataStr.replaceAll("Host: .*", "Host: " + clientProxy.getHost() + ":" + clientProxy.getPort());
            // 替换 Referer 值，保持协议一致性
            // 更完善的处理方式
            if (dataStr.contains("Referer:")) {
                //获取Referer值
                int index = dataStr.indexOf("Referer: ");
                String referer = dataStr.substring(index + "Referer: ".length(), dataStr.indexOf("\r\n", index));
                int uriIndex = referer.indexOf("/", 7);
                if (uriIndex != -1) {
                    dataStr = dataStr.replace(referer, clientProxy.getProxy_pass() + referer.substring(uriIndex));
                } else {
                    dataStr = dataStr.replace(referer, clientProxy.getProxy_pass());
                }
            }
            netSocket.write(Buffer.buffer(dataStr));
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
