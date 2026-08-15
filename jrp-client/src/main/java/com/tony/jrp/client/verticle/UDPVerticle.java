package com.tony.jrp.client.verticle;

import com.tony.jrp.client.service.impl.SecurityService;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.UserProxy;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
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
    public static final String AUTHORIZATION = "Authorization";
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
            //String clientAddress = socketAddress.toString();
            // 请求唯一标识
            int requestId = socketAddress.hashCode();
            this.cacheRequest(requestId, packet);
            requestIdTimestamps.put(requestId, System.currentTimeMillis());
            //代理端口（int转byte,32位，4字节）+请求唯一标识（和clientAddress绑定的int整数,32位，4字节）
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE).appendBytes(remotePortByte).appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            this.datagramSocket.send(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + packet.data().length()).appendByte(JRPMsgType.RECEIVE.getCode()).appendBuffer(msgId).appendBuffer(packet.data())
                    , p2pSocketAddress.port(), p2pSocketAddress.host());
        });
        datagramSocket.listen(clientProxy.getLocal_port(), "0.0.0.0", (res) -> {
            if (res.succeeded()) {
                log.info("UDP内网穿透代理服务启动成功，代理端口：{}。", clientProxy.getLocal_port());
            } else {
                log.error("端口[{}]]UDP内网穿透代理服务启动失败：{}", clientProxy.getLocal_port(), res.cause().getMessage(), res.cause());
            }
        });
        httpServer = vertx.createHttpServer(new HttpServerOptions().setReusePort(true));
        Router router = Router.router(vertx);
        router.get("/").handler(context -> {
            HttpServerRequest request = context.request();
            HttpServerResponse response = context.response();
            //尝试HTTP用户名密码信息验证
            HostAndPort authority = request.authority();
            log.debug("UDP客户端[{}]请求验证通过，返回成功提示信息!", authority);
            MultiMap headers = response.headers();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
            headers.set("Pragma", "no-cache");
            headers.set("Expires", "0");
            response.end(Buffer.buffer("UDP请求用户名密码验证通过!"));
        });
        httpServer.requestHandler(router);
        httpServer.listen(remotePort);
        cleanUpId = vertx.setPeriodic(1000, (id) -> this.cleanupExpiredRequests());
    }

    @Override
    protected void closeRequest(DatagramPacket request) {
    }

    /**
     * 清理请求id缓存
     */
    private void cleanupExpiredRequests() {
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
            datagramSocket.send(data, p2pSocketAddress.port(), p2pSocketAddress.host());
        }
    }

    @Override
    public void stop() throws Exception {
        log.info("清理端口[{}]下代理和缓存！", clientProxy.getRemote_port());
        vertx.cancelTimer(cleanUpId);
        requestIdTimestamps.clear();
        //datagramSocket.close();
        //httpServer.close();
        super.stop();
    }
}
