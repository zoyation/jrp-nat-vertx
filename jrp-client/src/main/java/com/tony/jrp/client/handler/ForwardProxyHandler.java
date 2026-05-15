package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ProxyProto;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.streams.ReadStream;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static com.tony.jrp.common.utils.JRPConstants.IP_PORT_SEPARATOR;

/**
 * sockets消息处理器
 */
@Slf4j
public class ForwardProxyHandler extends AbstractProxyHandler {
    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, ReadStream<?>> socketMap = new ConcurrentHashMap<>();

    public ForwardProxyHandler(Vertx vertx) {
        super(vertx);
    }

    @Override
    public void closeSocket(Integer clientId) {
        ReadStream<?> socket = socketMap.get(clientId);
        if (socket != null) {
            log.debug("收到断开连接请求，关闭代理连接[{}]。", clientId);
            socketMap.remove(clientId);
            if (socket instanceof NetSocket) {
                ((NetSocket) socket).close();
                log.debug("关闭TCP代理连接[{}]！", clientId);
            } else if (socket instanceof DatagramSocket) {
                ((DatagramSocket) socket).close();
                log.debug("关闭UDP代理连接[{}]！", clientId);
            }
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        ReadStream<?> socket = socketMap.get(clientId);
        if (socket != null) {
            sendData(data, socket);
        } else {
            synchronized (socketMap) {
                socket = socketMap.get(clientId);
                if (socket != null) {
                    sendData(data, socket);
                } else {
                    //首次创建TCP/UDP连接，格式：协议类型ProxyProto里枚举值（1字节）HOST(域名或IP):PORT（2字节）
                    ProxyProto protocol = ProxyProto.getByProto(data.getByte(0));
                    StringBuilder targetHostBuilder = new StringBuilder();
                    for (int i = 1; i < data.length(); i++) {
                        if (data.getByte(i) == IP_PORT_SEPARATOR) {
                            break;
                        }
                        targetHostBuilder.append((char) data.getByte(i));
                    }
                    String targetHost = targetHostBuilder.toString();
                    int targetPort;
                    try {
                        targetPort = data.getBuffer(1 + targetHost.length() + 1, 1 + targetHost.length() + 1 + 2).getUnsignedShort(0);
                    } catch (Exception e) {
                        log.error("解析代理请求数据异常：{}", e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                    Buffer sendData = (protocol == ProxyProto.HTTP || protocol == ProxyProto.HTTPS) ? data.getBuffer(1 + targetHost.length() + 1 + 2, data.length()) : Buffer.buffer();
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, targetHost, targetPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    if (protocol == ProxyProto.SOCK5_UDP) {
                        // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                        DatagramSocketOptions clientOptions = new DatagramSocketOptions();
                        clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                        clientOptions.setSendBufferSize(BUFFER_SIZE);
                        DatagramSocket netClient = vertx.createDatagramSocket(clientOptions);
                        netClient.exceptionHandler(e -> {
                            log.error("转发udp消息异常：{}", e.getMessage(), e);
                            DatagramSocket remove = (DatagramSocket) socketMap.remove(clientId);
                            if (remove != null) {
                                remove.close();
                            }
                        });
                        netClient.handler(packet -> {
                            log.debug("udp原始服务已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                            //Integer remotePort = proxy.getRemote_port();
                            webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + packet.data().length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(packet.data()));
                        });
                        netClient.send(data, targetPort, targetHost, rs -> {
                            if (rs.succeeded()) {
                                socketMap.put(clientId, netClient);
                            } else {
                                Throwable e = rs.cause();
                                log.error("转发udp消息到原始服务异常：{}，数据：{}", e.getMessage(), data, e);
                            }
                            downLatch.countDown();
                        });
                    } else {
                        // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                        NetClientOptions clientOptions = new NetClientOptions();
                        clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                        clientOptions.setSendBufferSize(BUFFER_SIZE);
                        clientOptions.setTrustAll(true);
                        clientOptions.setConnectTimeout(CONNECT_TIMEOUT);
                        clientOptions.setTcpKeepAlive(true);
                        NetClient netClient = vertx.createNetClient(clientOptions);
                        netClient.connect(targetPort, targetHost, asyncResult -> {
                            try {
                                if (asyncResult.succeeded()) {
                                    NetSocket proxySocket = asyncResult.result();
                                    proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                    log.debug("cache clientId:{}",clientId);
                                    socketMap.put(clientId, proxySocket);
                                    proxySocket.exceptionHandler(e -> log.error("代理转发服务异常：{}", e.getMessage(), e));
                                    proxySocket.closeHandler(ch -> {
                                        if (webSocket != null && socketMap.remove(clientId) != null) {
                                            log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                            webSocket.write(closeBuffer(msgId));
                                        }
                                    });
                                    // 返回给服务端代表连接成功
                                    if (webSocket != null) {
                                        proxySocket.handler(response -> {
                                            if (socketMap.get(clientId) != null) {
                                                log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                                webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                            } else {
                                                log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                            }
                                        });
                                        log.info("内网代理连接到{}:{}成功！", targetHost, targetPort);
                                        if (sendData.length() > 0 && !isConnectRequest(sendData)) {
                                            //http、https请求，发送数据
                                            log.debug("http、https请求[{}:{}]，发送数据" ,targetHost, targetPort);
                                            sendData(sendData, proxySocket);
                                        } else {
                                            //非http请求，返回给代理服务端代表连接成功
                                            log.debug("非http请求[{}:{}]，返回给代理服务端代表连接成功" ,targetHost, targetPort);
                                            webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId));
                                        }
                                    }
                                } else {
                                    log.error("内网代理连接到{}:{}失败：{}！", targetHost, targetPort, asyncResult.cause().getMessage(), asyncResult.cause());
                                    webSocket.write(closeBuffer(msgId));
                                }
                            } catch (Exception e) {
                                log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                webSocket.write(closeBuffer(msgId));
                            } finally {
                                downLatch.countDown();
                            }
                        });
                    }
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

    // 提取的辅助方法
    private boolean isConnectRequest(Buffer buffer) {
        if (buffer.length() < 7) {
            return false;
        }
        return buffer.getByte(0) == 'C' &&
                buffer.getByte(1) == 'O' &&
                buffer.getByte(2) == 'N' &&
                buffer.getByte(3) == 'N' &&
                buffer.getByte(4) == 'E' &&
                buffer.getByte(5) == 'C' &&
                buffer.getByte(6) == 'T';
    }

    /**
     * 发送TCP或UDP数据
     *
     * @param data   数据
     * @param stream 数据发送对象
     */
    private static void sendData(Buffer data, ReadStream<?> stream) {
        if (stream instanceof NetSocket) {
            //TCP数据
            NetSocket netSocket = (NetSocket) stream;
            netSocket.write(data);
            if (netSocket.writeQueueFull()) {
                netSocket.pause();
                netSocket.drainHandler((done) -> netSocket.resume());
            }
        } else {
            //udp数据
            DatagramSocket socket = (DatagramSocket) stream;
            //后续发送UDP数据，格式：协议类型ProxyProto里枚举值（1字节）HOST(域名或IP):PORT（2字节）
            StringBuilder targetHostBuilder = new StringBuilder();
            for (int i = 1; i < data.length(); i++) {
                if (data.getByte(i) == IP_PORT_SEPARATOR) {
                    break;
                }
                targetHostBuilder.append((char) data.getByte(i));
            }
            String targetHost = targetHostBuilder.toString();
            int targetPort = data.getBuffer(1 + targetHost.length() + 1, 1 + targetHost.length() + 1 + 2).getUnsignedShort(0);
            socket.send(data.getBuffer(1 + targetHost.length() + 1 + 2, data.length()), targetPort, targetHost);
        }
    }

    @Override
    public void close() throws IOException {
        if (!socketMap.isEmpty()) {
            log.info("停止代理转发服务");
            socketMap.forEach((key, socket) -> {
                if (socket instanceof NetSocket) {
                    ((NetSocket) socket).close();
                    log.debug("关闭SOCKS TCP连接[{}]！", key);
                } else if (socket instanceof DatagramSocket) {
                    ((DatagramSocket) socket).close();
                    log.debug("关闭SOCKS UDP连接[{}]！", key);
                }
            });
            socketMap.clear();
        }
    }
}
