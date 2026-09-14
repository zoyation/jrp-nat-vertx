package com.tony.jrp.client.verticle;

import com.tony.jrp.client.service.impl.SecurityService;
import com.tony.jrp.client.utils.UdpFragmentUtil;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.UserProxy;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * udp穿透服务
 */
@Slf4j
public class UDPVerticle extends AbstractProtocolVerticle<DatagramPacket> {
    /**
     * udp请求处理对象
     */
    private DatagramSocket datagramSocket;

    HttpServer httpServer;
    /**
     * 清理定时任务id
     */
    long cleanUpId = 0;
    /**
     * 添加requestId管理
     */
    private final Map<Integer, Long> requestIdTimestamps = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT = 30000; // 30秒超时

    public UDPVerticle(String ipv4, DatagramSocket serverSocket, SocketAddress socketAddress, SecurityService securityService, UserProxy clientProxy) {
        super(ipv4, serverSocket, socketAddress, securityService, clientProxy);
    }

    @Override
    public void init() {
        int remotePort = clientProxy.getRemote_port();
        byte[] remotePortByte = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
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
            //本地监听端口收到的是用户原始UDP数据报，从未经过分片，无需重组（分片只发生在P2P通道上）
            //注意：不能对用户数据调用assemble，否则用户数据首字节恰好等于分片类型码时会被误判丢弃
            Buffer packetData = packet.data();
            //String clientAddress = socketAddress.toString();
            // 请求唯一标识
            int requestId = requestIdGenerator.incrementAndGet();
            this.cacheRequest(requestId, packet);
            requestIdTimestamps.put(requestId, System.currentTimeMillis());
            //代理端口（int转byte,32位，4字节）+请求唯一标识（和clientAddress绑定的int整数,32位，4字节）
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE).appendBytes(remotePortByte).appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            //上行数据必须通过P2P打洞通道socket（super.datagramSocket）发送，
            //否则源端口与本机打洞映射端口不一致，内网服务端sender校验会丢弃数据
            UdpFragmentUtil.sendWithFragment(super.datagramSocket, requestId, Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + packetData.length()).appendByte(JRPMsgType.RECEIVE.getCode()).appendBuffer(msgId).appendBuffer(packetData)
                    , p2pSocketAddress.port(), p2pSocketAddress.host());
        });
        datagramSocket.listen(clientProxy.getLocal_port(), "0.0.0.0", (res) -> {
            if (res.succeeded()) {
                log.info("UDP内网穿透代理服务启动成功，代理端口：{}。", clientProxy.getLocal_port());
            } else {
                log.error("端口[{}]]UDP内网穿透代理服务启动失败：{}", clientProxy.getLocal_port(), res.cause().getMessage(), res.cause());
            }
        });
        cleanUpId = vertx.setPeriodic(1000, (id) -> this.cleanupExpiredRequests());
    }

    @Override
    protected void closeRequest(DatagramPacket request) {
    }

    /**
     * 清理请求id缓存
     */
    private void cleanupExpiredRequests() {
        //清理超时未完成的分片缓存
        UdpFragmentUtil.cleanupExpired();
        long now = System.currentTimeMillis();
        requestIdTimestamps.entrySet().removeIf(entry ->
        {
            boolean remove = now - entry.getValue() > REQUEST_TIMEOUT;
            if (remove) {
                Integer requestId = entry.getKey();
                log.debug("清理过期的请求id:{}", requestId);
                this.removeCacheAndClose(requestId);
            }
            return remove;
        });
    }

    @Override
    public void backData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer data) {
        log.debug("收到内网代理服务返回数据并返回给客户端[{}]。", requestId);
        DatagramPacket datagramPacket = this.getRequest(requestId);
        if (datagramPacket != null) {
            //内网服务端响应数据应返回给原始请求方（用户），而不是P2P对端（内网服务端）
            SocketAddress sender = datagramPacket.sender();
            UdpFragmentUtil.sendWithFragment(datagramSocket, requestId, data, sender.port(), sender.host());
        }
    }

    @Override
    public void stop() throws Exception {
        log.info("清理端口[{}]下代理和缓存！", clientProxy.getRemote_port());
        vertx.cancelTimer(cleanUpId);
        requestIdTimestamps.clear();
        //关闭本地监听UDP服务
        if (datagramSocket != null) {
            datagramSocket.close();
        }
        super.stop();
    }
}
