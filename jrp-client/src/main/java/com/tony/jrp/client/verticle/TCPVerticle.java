package com.tony.jrp.client.verticle;

import com.tony.jrp.client.service.impl.SecurityService;
import com.tony.jrp.client.utils.UdpFragmentUtil;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ServiceType;
import com.tony.jrp.common.model.UserProxy;
import com.tony.jrp.common.utils.PortConverter;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP穿透服务
 */
@Slf4j
public class TCPVerticle extends AbstractProtocolVerticle<NetSocket> {

    public static final String CERTIFICATE_UNKNOWN = "certificate_unknown";
    public static final String AUTHORIZATION = "Authorization";
    public static final String X_REAL_IP = "X-Real-IP";

    public TCPVerticle(String ipv4, DatagramSocket serverSocket, SocketAddress socketAddress, SecurityService securityService, UserProxy clientProxy) {
        super(ipv4, serverSocket, socketAddress, securityService, clientProxy);
    }

    @Override
    public void init() {
        int remotePort = clientProxy.getRemote_port();
        byte[] remotePortByte = PortConverter.getRemotePortByte(remotePort);
        // 创建TCP服务器
        NetServerOptions options = new NetServerOptions();
        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setReceiveBufferSize(BUFFER_SIZE);
        options.setSendBufferSize(BUFFER_SIZE);
        if (clientProxy.getType() == ServiceType.HTTPS) {
            options.setSsl(true);
            options.setKeyCertOptions(securityService.getKeyCertOptions());
        }
        NetServer server = this.vertx.createNetServer(options);
        boolean httpFlag = clientProxy.getType() == ServiceType.HTTP || clientProxy.getType() == ServiceType.HTTPS;
        // 处理连接请求
        server.connectHandler(clientSocket -> {
            clientSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            SocketAddress socketAddress = clientSocket.remoteAddress();
            log.debug("[{}] 创建连接!", socketAddress.toString());
            String clientAddress = socketAddress.toString();
            // 请求唯一标识
            int requestId = requestIdGenerator.incrementAndGet();
            //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
            //String msgId = remotePort.toString().length() + remotePort.toString() + clientAddress.length() + clientAddress;
            //代理端口（int转byte,32位，4字节）+请求唯一标识（和clientAddress绑定的int整数,32位，4字节）
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE).appendBytes(remotePortByte).appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            //log.info("客户端[{}]连接:{}", clientAddress, remotePort);
            String host = socketAddress.host();
            //延迟获取是否为http请求，http类型请求创建连接后会马上收到数据，‌SSH协议请求不会收到数据，需要通知被代理客户端连接后返回数据。
            AtomicBoolean receiveDataFlag = new AtomicBoolean(false);
            Handler<Buffer> dataHandler = data -> {
                receiveDataFlag.set(true);
                if (!httpFlag && securityService.isHTTPRequest(data)) {
                    log.warn("[{}]-[{}]类型服务，授权通过，不支持HTTP(S)访问:{}！", clientAddress, clientProxy.getType().name(), remotePort);
                    //String warnResponse = securityService.getHttpWarnResponse();
                    clientSocket.end(Buffer.buffer(securityService.getOKResponse()));
                    this.removeCacheAndClose(requestId);
                    clientSocket.close();
                } else {
                    log.debug("客户端[{}-[{}]类型服务访问权限验证通过，转发消息!", clientAddress, clientProxy.getType().name());
                    this.cacheRequest(requestId, clientSocket);
                    if (securityService.isHTTPRequest(data)) {
                        //移除data里面的Authorization: Digest
                        data = securityService.removeHead(data.toString(), AUTHORIZATION);
                        //请求头中添加原始请求IP
                        data = securityService.addHead(data.toString(), X_REAL_IP, clientAddress);
                    }
                    UdpFragmentUtil.sendWithFragment(datagramSocket, requestId, Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + data.length()).appendByte(JRPMsgType.RECEIVE.getCode()).appendBuffer(msgId).appendBuffer(data)
                            , p2pSocketAddress.port(), p2pSocketAddress.host());
                }
            };
            Handler<Void> closeHandler = voidHandler -> {
                log.debug("客户端[{}]连接关闭！", clientAddress);
                if (this.cachedRequest(requestId)) {
                    this.removeCacheAndClose(requestId);
                    //log.warn("客户端连接关闭，丢弃收到的内网代理服务器返回信息，并通知内网服务器断开连接[{}]！", clientAddress);
                    //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
                    log.debug("客户端连接关闭，发送关闭连接消息到被代理端[{}]！", clientAddress);
                    datagramSocket.send(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length()).appendByte(JRPMsgType.CLOSE.getCode()).appendBuffer(msgId)
                            , p2pSocketAddress.port(), p2pSocketAddress.host());
                }
            };
            clientSocket.exceptionHandler(err -> log.error("客户端[{}]异常：{}！", clientAddress, err.getMessage(), err));
            clientSocket.handler(dataHandler);
            clientSocket.closeHandler(closeHandler);
            //授权通过，如果是非HTTP、SSH类TCP代理（这儿不能通过NetSocket判断创建连接是不是HTTP请求），才通知客户端初始化。
            //http类型请求创建连接后会马上收到数据；SSH协议请求不会收到数据，需要通知被代理客户端连接后返回数据。延迟判断httpRequestFlag如果为false，判断是ssh等协议连接，通知被代理端初始化。
            vertx.setTimer(200, (id) -> {
                if (!httpFlag && !receiveDataFlag.get()) {
                    //关闭历史未移除连接
                    this.removeCacheAndClose(requestId);
                    log.debug("发送来自客户端[{}]的非HTTP初始化请求!", clientAddress);
                    this.cacheRequest(requestId, clientSocket);
                    UdpFragmentUtil.sendWithFragment(datagramSocket, requestId, Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length()).appendByte(JRPMsgType.RECEIVE.getCode()).appendBuffer(msgId)
                            , p2pSocketAddress.port(), p2pSocketAddress.host());
                }
            });
        }).exceptionHandler(err -> {
            String message = err.getMessage();
            if (message.contains(CERTIFICATE_UNKNOWN)) {
                log.warn("端口[{}]TCP内网穿透代理服务证书不安全：{}", remotePort, message, err);
            } else {
                log.error("端口[{}]TCP内网穿透代理服务异常：{}", remotePort, message, err);
            }
        });
        server.listen(clientProxy.getLocal_port(), res -> {
            // 监听端口
            if (res.succeeded()) {
                log.info("[{}]内网穿透服务启动成功，代理端口：{}。", clientProxy.getType().name(), clientProxy.getLocal_port());
            } else {
                log.error("端口[{}]-[{}]内网穿透代理服务启动失败：{}", clientProxy.getLocal_port(), clientProxy.getType().name(), res.cause().getMessage(), res.cause());
            }
        });
    }


    @Override
    protected void closeRequest(NetSocket request) {
        request.close();
    }

    @Override
    public void backData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer data) {
        NetSocket clientNetSocket = this.getRequest(requestId);
        if (clientNetSocket != null) {
            if (JRPMsgType.CLOSE == msgType) {
                log.debug("收到内网代理服务返回的关闭信息[{}]，关闭连接或移除缓存。", p2pSocketAddress);
                this.removeCacheAndClose(requestId);
            } else if (JRPMsgType.RESPONSE == msgType) {
                log.debug("收到内网代理服务返回数据并返回给客户端[{}]。", p2pSocketAddress);
                clientNetSocket.write(data);
                if (clientNetSocket.writeQueueFull()) {
                    clientNetSocket.pause();
                    clientNetSocket.drainHandler(done -> clientNetSocket.resume());
                }
            } else {
                log.warn("收到内网代理服务返回数据[{}]，消息类型[{}]不匹配！", p2pSocketAddress, msgType);
            }
        } else if (JRPMsgType.CLOSE == msgType) {
            log.warn("收到内网代理服务返回的关闭消息，客户端[{}]连接已经失效，不做处理！", requestId);
        } else {
            log.warn("收到内网代理服务返回消息，但是客户端[{}]连接已经失效，发送关闭连接消息到内网代理服务！", requestId);
            datagramSocket.send(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length()).appendByte(JRPMsgType.CLOSE.getCode()).appendBuffer(msgId)
                    , p2pSocketAddress.port(), p2pSocketAddress.host());
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }
}
