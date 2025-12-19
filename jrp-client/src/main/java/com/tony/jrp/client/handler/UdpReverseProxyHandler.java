package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.WebSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * tcp消息处理器
 */
@Slf4j
public class UdpReverseProxyHandler extends AbstractProxyHandler {
    /**
     * udp缓存
     */
    private final Map<Integer, DatagramSocket> datagramSocketMap = new ConcurrentHashMap<>();
    /**
     * udp最新读或者写时间缓存
     */
    private final Map<Integer, Long> udpReadOrWriteTimeMap = new ConcurrentHashMap<>();
    private final long cacheTimerId;

    public UdpReverseProxyHandler(Vertx vertx) {
        super(vertx);
        cacheTimerId = vertx.setPeriodic(1000, (id) -> {
            //1秒内没有操作的进行清理
            udpReadOrWriteTimeMap.entrySet().removeIf(entry -> {
                Integer clientAddress = entry.getKey();
                boolean timeout = entry.getValue() + 1000L < System.currentTimeMillis();
                if (timeout) {
                    DatagramSocket remove = datagramSocketMap.remove(clientAddress);
                    if (remove != null) {
                        remove.close();
                    }
                }
                return timeout;
            });
        });
    }

    @Override
    public void closeSocket(Integer clientId) {
        DatagramSocket datagramSocket = datagramSocketMap.get(clientId);
        if (datagramSocket != null) {
            log.debug("收到断开连接请求，关闭UDP连接[{}]。", clientId);
            datagramSocketMap.remove(clientId);
            udpReadOrWriteTimeMap.remove(clientId);
            datagramSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        String originHost = clientProxy.getHost();
        int originPort = clientProxy.getPort();
        DatagramSocket netSocket = datagramSocketMap.get(clientId);
        if (netSocket != null) {
            sendUdpData(clientId, data, netSocket, originPort, originHost);
        } else {
            synchronized (datagramSocketMap) {
                netSocket = datagramSocketMap.get(clientId);
                if (netSocket != null) {
                    //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
                    sendUdpData(clientId, data, netSocket, originPort, originHost);
                } else {
                    log.info("收到UPD数据[{}]，准备发送到[{}:{}]！", clientId, originHost, originPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                    DatagramSocketOptions clientOptions = new DatagramSocketOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    clientOptions.setReusePort(true);
                    DatagramSocket netClient = vertx.createDatagramSocket(clientOptions);
                    netClient.exceptionHandler(e -> {
                        log.error("转发udp消息异常：{}", e.getMessage(), e);
                        DatagramSocket remove = datagramSocketMap.remove(clientId);
                        if (remove != null) {
                            remove.close();
                        }
                        udpReadOrWriteTimeMap.remove(clientId);
                    });
                    netClient.handler(socket -> {
                        log.debug("udp原始服务已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                        //Integer remotePort = proxy.getRemote_port();
                        webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + socket.data().length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(socket.data()));
                    });
                    netClient.send(data, originPort, originHost, rs -> {
                        if (rs.succeeded()) {
                            datagramSocketMap.put(clientId, netClient);
                            udpReadOrWriteTimeMap.put(clientId, System.currentTimeMillis());
                        } else {
                            Throwable e = rs.cause();
                            log.error("转发udp消息到原始服务异常：{}，数据：{}", e.getMessage(), data.toString(), e);
                        }
                        downLatch.countDown();
                    });
                    try {
                        boolean await = downLatch.await(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (!await) {
                            log.error("udp转发连接超时，删除缓存");
                            datagramSocketMap.remove(clientId);
                            udpReadOrWriteTimeMap.remove(clientId);
                        }
                    } catch (InterruptedException e) {
                        log.error("udp转发服务连接处理异常：{}，删除缓存", e.getMessage(), e);
                        datagramSocketMap.remove(clientId);
                        udpReadOrWriteTimeMap.remove(clientId);
                    }
                }
            }
        }
    }

    /**
     * 发送UDP数据
     *
     * @param clientId   客户端ID
     * @param data       数据
     * @param netSocket  数据发送对象
     * @param originPort 原始服务端口
     * @param originHost 原始服务主机
     */
    private void sendUdpData(Integer clientId, Buffer data, DatagramSocket netSocket, int originPort, String originHost) {
        netSocket.send(data, originPort, originHost, rs -> {
            if (rs.failed()) {
                Throwable e = rs.cause();
                log.error("转发udp消息到原始服务异常：{}", e.getMessage(), e);
                DatagramSocket remove = datagramSocketMap.remove(clientId);
                if (remove != null) {
                    remove.close();
                }
                udpReadOrWriteTimeMap.remove(clientId);
            } else {
                udpReadOrWriteTimeMap.put(clientId, System.currentTimeMillis());
            }
        });
    }

    @Override
    public void close() throws IOException {
        vertx.cancelTimer(cacheTimerId);
        if (!datagramSocketMap.isEmpty()) {
            log.info("停止UDP转发服务");
            datagramSocketMap.values().forEach(DatagramSocket::close);
            udpReadOrWriteTimeMap.clear();
        }
    }
}
