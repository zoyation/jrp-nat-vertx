package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ProxyProto;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.streams.ReadStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

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

    /**
     * 复用的TCP客户端实例（线程安全，可并发使用）
     */
    private volatile NetClient tcpClient;

    /**
     * TCP客户端初始化锁
     */
    private final Object tcpClientLock = new Object();

    public ForwardProxyHandler(Vertx vertx) {
        super(vertx);
    }

    /**
     * 获取或创建复用的TCP客户端
     * 使用双重检查锁定确保线程安全
     *
     * @return NetClient实例
     */
    private NetClient getOrCreateTcpClient() {
        if (tcpClient == null) {
            synchronized (tcpClientLock) {
                if (tcpClient == null) {
                    log.info("初始化复用TCP客户端...");
                    NetClientOptions clientOptions = new NetClientOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    clientOptions.setTrustAll(true);
                    clientOptions.setConnectTimeout(CONNECT_TIMEOUT);
                    clientOptions.setTcpKeepAlive(true);
                    // 设置空闲超时，避免连接长时间无数据被防火墙关闭
                    clientOptions.setIdleTimeout(IDLE_TIMEOUT);
                    clientOptions.setIdleTimeoutUnit(java.util.concurrent.TimeUnit.SECONDS);
                    // 允许重用地址，避免端口耗尽问题
                    clientOptions.setReuseAddress(true);
                    clientOptions.setReusePort(true);
                    // 禁用Nagle算法，减少延迟
                    clientOptions.setTcpNoDelay(true);
                    // 快速打开TCP连接（如果系统支持）
                    clientOptions.setTcpFastOpen(true);

                    tcpClient = vertx.createNetClient(clientOptions);
                    log.info("复用TCP客户端初始化完成");
                }
            }
        }
        return tcpClient;
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
    public void receiveMsgAndProxy(Consumer<Buffer> bufferConsumer, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        ReadStream<?> socket = socketMap.get(clientId);
        if (socket != null) {
            sendData(data, socket);
        } else {
            synchronized (socketMap) {
                socket = socketMap.get(clientId);
                if (socket != null) {
                    sendData(data, socket);
                } else {
                    //首次创建TCP/UDP连接，格式：协议类型ProxyProto里枚举值（1字节）+ 地址类型(1字节: 0x01=IPv4, 0x03=域名, 0x04=IPv6) + 地址内容 + 分隔符(1字节) + 端口(2字节)
                    ProxyProto protocol = ProxyProto.getByProto(data.getByte(0));
                    if (protocol == null) {
                        log.warn("代理请求数据格式错误或连接已经关闭！");
                        log.debug("关闭客户端[{}]对应的内容！", clientId);
                        bufferConsumer.accept(closeBuffer(msgId));
                        return;
                    }

                    // 读取地址类型
                    byte addrType = data.getByte(1);
                    String targetHost;
                    int offset = 2; // 当前偏移量

                    // 根据地址类型解析目标地址
                    if (addrType == 0x01) {
                        // IPv4地址 (4字节)
                        if (data.length() < offset + 4) {
                            log.warn("IPv4地址数据长度不足");
                            bufferConsumer.accept(closeBuffer(msgId));
                            return;
                        }
                        StringBuilder ipBuilder = new StringBuilder();
                        for (int i = 0; i < 4; i++) {
                            if (i > 0) ipBuilder.append(".");
                            ipBuilder.append(data.getByte(offset + i) & 0xFF);
                        }
                        targetHost = ipBuilder.toString();
                        offset += 4;
                    } else if (addrType == 0x04) {
                        // IPv6地址 (16字节)
                        if (data.length() < offset + 16) {
                            log.warn("IPv6地址数据长度不足");
                            bufferConsumer.accept(closeBuffer(msgId));
                            return;
                        }
                        byte[] ipv6Bytes = data.getBytes(offset, offset + 16);
                        try {
                            java.net.InetAddress inetAddress = java.net.InetAddress.getByAddress(ipv6Bytes);
                            targetHost = inetAddress.getHostAddress();
                        } catch (Exception e) {
                            log.error("解析IPv6地址失败: {}", e.getMessage(), e);
                            bufferConsumer.accept(closeBuffer(msgId));
                            return;
                        }
                        offset += 16;
                    } else if (addrType == 0x03) {
                        // 域名地址
                        // 查找分隔符位置
                        int separatorPos = -1;
                        for (int i = offset; i < data.length(); i++) {
                            if (data.getByte(i) == IP_PORT_SEPARATOR) {
                                separatorPos = i;
                                break;
                            }
                        }
                        if (separatorPos == -1) {
                            log.warn("未找到域名分隔符");
                            bufferConsumer.accept(closeBuffer(msgId));
                            return;
                        }
                        targetHost = data.getString(offset, separatorPos);
                        offset = separatorPos;
                    } else {
                        log.warn("不支持的地址类型: {}", addrType);
                        bufferConsumer.accept(closeBuffer(msgId));
                        return;
                    }

                    if (!StringUtils.hasText(targetHost)) {
                        log.warn("不能解析目标host,关闭客户端[{}]对应的内容!", clientId);
                        bufferConsumer.accept(closeBuffer(msgId));
                        return;
                    }

                    // 跳过分隔符，读取端口
                    offset++; // 跳过IP_PORT_SEPARATOR
                    int targetPort;
                    try {
                        targetPort = data.getBuffer(offset, offset + 2).getUnsignedShort(0);
                    } catch (Exception e) {
                        log.warn("不能解析目标端口,关闭客户端[{}]对应的内容!", clientId);
                        bufferConsumer.accept(closeBuffer(msgId));
                        return;
                    }

                    // 计算HTTP/HTTPS数据的起始位置
                    Buffer sendData = (protocol == ProxyProto.HTTP || protocol == ProxyProto.HTTPS) ? data.getBuffer(offset + 2, data.length()) : Buffer.buffer();
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！地址类型:{}", clientId, targetHost, targetPort, addrType);
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
                            bufferConsumer.accept(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + packet.data().length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(packet.data()));
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
                        // 使用复用的TCP客户端进行连接
                        NetClient netClient = getOrCreateTcpClient();
                        netClient.connect(targetPort, targetHost, asyncResult -> {
                            try {
                                if (asyncResult.succeeded()) {
                                    NetSocket proxySocket = asyncResult.result();
                                    proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                    log.debug("cache clientId:{}", clientId);
                                    socketMap.put(clientId, proxySocket);
                                    proxySocket.exceptionHandler(e -> {
                                        String errorMsg = e.getMessage();
                                        // 区分不同类型的异常
                                        if (errorMsg != null && errorMsg.contains("reset")) {
                                            log.warn("代理连接[clientId={}]被对端重置：{}，目标服务[{}:{}]",
                                                    clientId, errorMsg, targetHost, targetPort);
                                        } else if (errorMsg != null && errorMsg.contains("timed out")) {
                                            log.warn("代理连接[clientId={}]超时：{}，目标服务[{}:{}]",
                                                    clientId, errorMsg, targetHost, targetPort);
                                        } else {
                                            log.error("代理转发服务异常[clientId={}]：{}，目标服务[{}:{}]",
                                                    clientId, errorMsg, targetHost, targetPort, e);
                                        }
                                        // 清理资源并通知服务端
                                        if (socketMap.remove(clientId) != null && bufferConsumer != null) {
                                            bufferConsumer.accept(closeBuffer(msgId));
                                        }
                                    });
                                    proxySocket.closeHandler(ch -> {
                                        if (bufferConsumer != null && socketMap.remove(clientId) != null) {
                                            log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                            bufferConsumer.accept(closeBuffer(msgId));
                                        }
                                    });
                                    // 返回给服务端代表连接成功
                                    if (bufferConsumer != null) {
                                        proxySocket.handler(response -> {
                                            if (socketMap.get(clientId) != null) {
                                                log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                                bufferConsumer.accept(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                            } else {
                                                log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                            }
                                        });
                                        log.info("内网代理连接到{}:{}成功！", targetHost, targetPort);
                                        if (sendData.length() > 0 && !isConnectRequest(sendData)) {
                                            //http、https请求，发送数据
                                            log.debug("http、https请求[{}:{}]，发送数据", targetHost, targetPort);
                                            sendData(sendData, proxySocket);
                                        } else {
                                            //非http请求，返回给代理服务端代表连接成功
                                            log.debug("非http请求[{}:{}]，返回给代理服务端代表连接成功", targetHost, targetPort);
                                            bufferConsumer.accept(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId));
                                        }
                                    }
                                } else {
                                    Throwable cause = asyncResult.cause();
                                    String errorMsg = cause.getMessage();

                                    // 详细记录连接失败原因
                                    if (errorMsg != null && errorMsg.contains("Cannot assign requested address")) {
                                        log.error("内网代理连接失败[clientId={}]：无法分配请求的地址，目标服务[{}:{}]，地址类型:{}，可能原因：1)IPv6地址格式错误或系统不支持 2)本地端口耗尽 3)目标地址不可达",
                                                clientId, targetHost, targetPort, addrType, cause);
                                    } else if (errorMsg != null && errorMsg.contains("Connection refused")) {
                                        log.warn("内网代理连接被拒绝[clientId={}]：目标服务[{}:{}]未启动或拒绝连接",
                                                clientId, targetHost, targetPort);
                                    } else if (errorMsg != null && errorMsg.contains("timed out")) {
                                        log.warn("内网代理连接超时[clientId={}]：目标服务[{}:{}]响应超时",
                                                clientId, targetHost, targetPort);
                                    } else if (errorMsg != null && errorMsg.contains("No route to host")) {
                                        log.error("内网代理连接失败[clientId={}]：无路由到主机，目标服务[{}:{}]，网络不可达",
                                                clientId, targetHost, targetPort);
                                    } else {
                                        log.error("内网代理连接到{}:{}失败：{}！", targetHost, targetPort, errorMsg, cause);
                                    }
                                    bufferConsumer.accept(closeBuffer(msgId));
                                }
                            } catch (Exception e) {
                                log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                bufferConsumer.accept(closeBuffer(msgId));
                            } finally {
                                downLatch.countDown();
                            }
                        });
                    }
                    try {
                        downLatch.await();
                    } catch (InterruptedException e) {
                        log.error("转发服务连接处理异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                        bufferConsumer.accept(closeBuffer(msgId));
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
            //后续发送UDP数据，格式：协议类型ProxyProto里枚举值（1字节）+ 地址类型(1字节) + 地址内容 + 分隔符(1字节) + 端口(2字节) + 实际数据
            byte addrType = data.getByte(1);
            int offset = 2;
            String targetHost;

            // 根据地址类型解析目标地址
            if (addrType == 0x01) {
                // IPv4地址 (4字节)
                StringBuilder ipBuilder = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    if (i > 0) ipBuilder.append(".");
                    ipBuilder.append(data.getByte(offset + i) & 0xFF);
                }
                targetHost = ipBuilder.toString();
                offset += 4;
            } else if (addrType == 0x04) {
                // IPv6地址 (16字节)
                byte[] ipv6Bytes = data.getBytes(offset, offset + 16);
                try {
                    java.net.InetAddress inetAddress = java.net.InetAddress.getByAddress(ipv6Bytes);
                    targetHost = inetAddress.getHostAddress();
                } catch (Exception e) {
                    log.error("解析UDP IPv6地址失败: {}", e.getMessage(), e);
                    return;
                }
                offset += 16;
            } else if (addrType == 0x03) {
                // 域名地址，查找分隔符
                int separatorPos = -1;
                for (int i = offset; i < data.length(); i++) {
                    if (data.getByte(i) == IP_PORT_SEPARATOR) {
                        separatorPos = i;
                        break;
                    }
                }
                if (separatorPos == -1) {
                    log.error("UDP数据中未找到域名分隔符");
                    return;
                }
                targetHost = data.getString(offset, separatorPos);
                offset = separatorPos;
            } else {
                log.error("UDP数据中不支持的地址类型: {}", addrType);
                return;
            }

            // 跳过分隔符，读取端口
            offset++;
            int targetPort = data.getBuffer(offset, offset + 2).getUnsignedShort(0);
            offset += 2;

            // 发送实际数据
            socket.send(data.getBuffer(offset, data.length()), targetPort, targetHost);
        }
    }

    @Override
    public void close() throws IOException {
        // 关闭复用的TCP客户端
        if (tcpClient != null) {
            log.info("关闭复用TCP客户端...");
            tcpClient.close();
            tcpClient = null;
            log.info("复用TCP客户端已关闭");
        }

        // 关闭所有代理连接
        if (!socketMap.isEmpty()) {
            log.info("停止代理转发服务，清理{}个连接", socketMap.size());
            socketMap.forEach((key, socket) -> {
                try {
                    if (socket instanceof NetSocket) {
                        ((NetSocket) socket).close();
                        log.debug("关闭TCP代理连接[{}]！", key);
                    } else if (socket instanceof DatagramSocket) {
                        ((DatagramSocket) socket).close();
                        log.debug("关闭UDP代理连接[{}]！", key);
                    }
                } catch (Exception e) {
                    log.warn("关闭连接[{}]时发生异常: {}", key, e.getMessage());
                }
            });
            socketMap.clear();
        }
    }
}
