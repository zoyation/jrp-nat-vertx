package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOCKS5中转服务
 */
@Slf4j
public class Socks5Verticle extends AbstractProxyVerticle<NetSocket> {
    // SOCKS5协议版本号
    private static final byte SOCKS5_VERSION = 0x05;
    // 无需认证方法
    private static final byte SOCKS5_AUTH_METHOD_NONE = 0x00;
    // 用户名密码认证方法
    private static final byte SOCKS5_AUTH_METHOD_USERNAME_PASSWORD = 0x02;
    // 认证响应第一个字节，版本号（固定为0x01）
    private static final byte SOCKS5_AUTH_VERSION = 0x01;
    // 认证响应第二个字节，认证成功 0x00
    private static final byte SOCKS5_AUTH_SUCCESS = 0x00;
    // 认证响应第二个字节，认证失败 0x01
    private static final byte SOCKS5_AUTH_FAILURE = 0x01;
    //命令：0x01=CONNECT，0x02=BIND，0x03=UDP ASSOCIATE
    // CONNECT命令（建立TCP连接）
    private static final byte SOCKS5_CMD_CONNECT = 0x01;
    // BIND命令，反向连接（FTP等）
    private static final byte SOCKS5_CMD_BIND = 0x02;
    //UDP关联命令
    private static final byte SOCKS5_CMD_UDP_ASSOCIATE = 0x03;
    // IPv4地址类型
    private static final byte SOCKS5_ADDR_TYPE_IPV4 = 0x01;
    // 域名地址类型
    private static final byte SOCKS5_ADDR_TYPE_DOMAIN = 0x03;
    // SOCKS5响应：成功
    private static final byte SOCKS5_REPLY_SUCCEEDED = 0x00;
    // SOCKS5响应：通用失败
    private static final byte SOCKS5_REPLY_GENERAL_FAILURE = 0x01;
    /**
     * 在类中添加UDP服务器映射
     */
    private final Map<String, NetServer> udpServerMap = new ConcurrentHashMap<>();

    // 在类中添加UDP端口分配方法
    private int allocateUdpPort() {
        // 简单实现：使用固定范围分配UDP端口
        return 10000 + (int) (Math.random() * 10000);
    }

    private NetServer server;
    byte[] remotePortByte;

    public Socks5Verticle(ServerWebSocket serverSocket, SecurityService securityService,
                          ClientRegister clientRegister, ClientProxy clientProxy) {
        super(serverSocket, securityService, clientRegister, clientProxy);
        int remotePort = clientProxy.getRemote_port();
        remotePortByte = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
    }

    @Override
    public void init() {
        Integer remotePort = clientProxy.getRemote_port();
        // 创建TCP服务器用于SOCKS5
        NetServerOptions options = new NetServerOptions();
        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setReceiveBufferSize(BUFFER_SIZE);
        options.setSendBufferSize(BUFFER_SIZE);
        server = this.vertx.createNetServer(options);
        // 处理SOCKS5连接请求，和基于端口的反向代理转发处理方式不一样：多了握手处理、认证处理（可选），握手和认证处理完后，从SOCKS5请求数据中获取目标服务地址和端口
        server.connectHandler(netSocket -> {
            netSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            SocketAddress socketAddress = netSocket.remoteAddress();
            log.debug("[{}] SOCKS5客户端连接!", socketAddress.toString());
            String clientAddress = socketAddress.toString();
            // 请求唯一标识
            int requestId = socketAddress.hashCode();
            // 设置初始握手处理器
            netSocket.handler(handshakeHandler(netSocket));
            // 连接关闭处理
            netSocket.closeHandler(voidHandler -> {
                log.debug("SOCKS5客户端[{}]连接关闭！", clientAddress);
                if (this.hasRequest(requestId)) {
                    this.closeRequest(requestId, netSocket);
                    Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                            .appendBytes(remotePortByte)
                            .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
                    log.debug("SOCKS5客户端连接关闭，发送关闭连接消息到被代理端[{}]！", clientAddress);
                    serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length())
                            .appendByte(JRPMsgType.CLOSE.getCode())
                            .appendBuffer(msgId));
                }
                // 清理UDP资源
                if (udpServerMap.containsKey(clientAddress)) {
                    NetServer udpServer = udpServerMap.remove(clientAddress);
                    udpServer.close();
                    log.debug("清理客户端[{}]的UDP资源", clientAddress);
                }
            });

            netSocket.exceptionHandler(err ->
                    log.error("SOCKS5客户端[{}]异常：{}！", clientAddress, err.getMessage(), err));
        });

        server.listen(remotePort, res -> {
            if (res.succeeded()) {
                log.info("SOCKS5内网穿透代理服务启动成功，代理端口：{}。", remotePort);
            } else {
                log.error("端口[{}]SOCKS5内网穿透代理服务启动失败：{}",
                        remotePort, res.cause().getMessage(), res.cause());
            }
        });
    }

    @Override
    protected void closeRequest(NetSocket request) {
        request.close();
    }

    /**
     * 1.处理SOCKS5握手阶段
     *
     * @param socket5 SOCKS5客户端socket
     * @return 处理器
     */
    private Handler<Buffer> handshakeHandler(NetSocket socket5) {
        SocketAddress remoteAddress = socket5.remoteAddress();
        return buffer -> {
            // SOCKS5握手阶段处理：收到消息VERSION+NMETHODS+METHODS
            if (buffer.length() < 2) {
                log.warn("握手请求报文不正确：{}", remoteAddress);
                socket5.close();
                return;
            }
            // VERSION：协议版本号，1个字节，socks5为0x05
            byte version = buffer.getByte(0);
            if (version != SOCKS5_VERSION) {
                log.warn("协议版本号不匹配：{}", remoteAddress);
                socket5.close();
                return;
            }
            //NMETHODS：客户端支持的认证方法数量，1个字节，1~255
            int nMethods = buffer.getByte(1) & 0xFF;
            if (buffer.length() < 2 + nMethods) {
                log.warn("支持的认证方法数量不正确：{}", remoteAddress);
                socket5.close();
                return;
            }
            // 检查支持的认证方法
            byte selectedMethod = (byte) 0xFF; // 默认不支持
            for (int i = 0; i < nMethods; i++) {
                byte method = buffer.getByte(2 + i);
                //优先使用认证
                if (method == SOCKS5_AUTH_METHOD_USERNAME_PASSWORD) {
                    selectedMethod = SOCKS5_AUTH_METHOD_USERNAME_PASSWORD;
                    break;
                } else if (method == SOCKS5_AUTH_METHOD_NONE) {
                    selectedMethod = SOCKS5_AUTH_METHOD_NONE;
                    break;
                }
            }
            if (selectedMethod == (byte) 0xFF) {
                // 不支持的认证方法
                log.warn("认证方法不支持：{}", remoteAddress);
                socket5.write(Buffer.buffer(new byte[]{SOCKS5_VERSION, (byte) 0xFF}));
                socket5.close();
                return;
            }
            // 发送认证方法选择
            socket5.write(Buffer.buffer(new byte[]{SOCKS5_VERSION, selectedMethod}));
            if (selectedMethod == SOCKS5_AUTH_METHOD_USERNAME_PASSWORD) {
                // 切换到用户名密码认证处理阶段
                socket5.handler(authHandler(socket5));
            } else {
                // 无需认证，直接进入请求处理阶段
                socket5.handler(requestHandler(socket5));
            }
        };
    }

    /**
     * 3.SOCKS5请求处理处理器：建立连接，转发数据
     *
     * @param clientSocket SOCKS5客户端socket
     */
    private Handler<Buffer> requestHandler(NetSocket clientSocket) {
        return buffer -> {
            if (buffer.length() < 7) {
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                return;
            }
            //SOCKS版本号：0x05代表SOCKS5
            byte version = buffer.getByte(0);
            //命令：0x01=CONNECT，0x02=BIND，0x03=UDP ASSOCIATE
            //0x01（CONNECT）：建立TCP连接（如代理HTTP）。
            //0x02（BIND）：反向连接（FTP等）。
            //0x03（UDP ASSOCIATE）：建立UDP代理通道。
            byte cmd = buffer.getByte(1);
            //目标地址类型：0x01=IPv4，0x03=域名，0x04=IPv6
            byte addrType = buffer.getByte(3);
            if (version != SOCKS5_VERSION || (cmd != SOCKS5_CMD_CONNECT && cmd != SOCKS5_CMD_UDP_ASSOCIATE)) {
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                return;
            }
            try {
                //目标地址。取决于 ATYP。
                String targetHost;
                //目标端口（大端序）
                int targetPort;
                int addrLength;

                if (addrType == SOCKS5_ADDR_TYPE_IPV4) {
                    // IPv4地址
                    if (buffer.length() < 10) {
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                        return;
                    }
                    StringBuilder ipBuilder = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) ipBuilder.append(".");
                        ipBuilder.append(buffer.getByte(4 + i) & 0xFF);
                    }
                    targetHost = ipBuilder.toString();
                    targetPort = ((buffer.getByte(8) & 0xFF) << 8) | (buffer.getByte(9) & 0xFF);
                    addrLength = 10;
                } else if (addrType == SOCKS5_ADDR_TYPE_DOMAIN) {
                    // 域名地址
                    int domainLength = buffer.getByte(4) & 0xFF;
                    if (buffer.length() < 7 + domainLength) {
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                        return;
                    }
                    targetHost = buffer.getString(5, 5 + domainLength);
                    targetPort = ((buffer.getByte(5 + domainLength) & 0xFF) << 8) |
                            (buffer.getByte(6 + domainLength) & 0xFF);
                    addrLength = 7 + domainLength;
                } else {
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                    return;
                }

                //认证检查
                if (!securityService.authorized(clientSocket.remoteAddress().host())) {
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
                    return;
                }

                // 发送成功响应
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_SUCCEEDED);
                if (cmd == SOCKS5_CMD_UDP_ASSOCIATE) {
                    // 处理UDP关联请求
                    //handleUdpAssociate(clientSocket, clientAddress, msgId);
                    return;
                }
                int requestId = clientSocket.hashCode();
                this.cacheRequest(requestId, clientSocket);
                // 通知内网代理建立连接
                Buffer requestData = Buffer.buffer()
                        .appendString(targetHost)
                        .appendByte((byte) ':')
                        .appendInt(targetPort);
                Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                        .appendBytes(remotePortByte)
                        .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
                // 将SOCKS5客户端加入管理
                serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + requestData.length())
                        .appendByte(JRPMsgType.RECEIVE.getCode())
                        .appendBuffer(msgId)
                        .appendBuffer(requestData));

            } catch (Exception e) {
                log.error("处理SOCKS5请求失败:{}", e.getMessage(), e);
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> this.closeRequest(0, clientSocket));
            }
        };
    }

    /**
     * 发送SOCKS5响应
     */
    private Future<Void> sendSocks5Reply(NetSocket socket, byte replyCode) {
        Buffer reply = Buffer.buffer(new byte[]{
                SOCKS5_VERSION,           // 版本
                replyCode,                // 回复码
                0x00,                     // 保留字段
                SOCKS5_ADDR_TYPE_IPV4,    // 地址类型
                0x00, 0x00, 0x00, 0x00,   // 绑定地址(0.0.0.0)
                0x00, 0x00                // 绑定端口(0)
        });
        return socket.write(reply);
    }

    /**
     * 2.用户名密码认证处理器
     */
    private Handler<Buffer> authHandler(NetSocket clientSocket) {
        return buffer -> {
            if (buffer.length() < 3) {
                clientSocket.close();
                return;
            }

            byte authVersion = buffer.getByte(0);
            if (authVersion != SOCKS5_AUTH_VERSION) {
                sendAuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
                clientSocket.close();
                return;
            }

            // 用户名长度
            int usernameLength = buffer.getByte(1) & 0xFF;
            if (buffer.length() < 2 + usernameLength + 1) {
                clientSocket.close();
                return;
            }
            // 用户名
            String username = buffer.getString(2, 2 + usernameLength);

            // 密码长度
            int passwordPos = 2 + usernameLength;
            int passwordLength = buffer.getByte(passwordPos) & 0xFF;
            if (buffer.length() < passwordPos + 1 + passwordLength) {
                clientSocket.close();
                return;
            }
            // 密码
            String password = buffer.getString(passwordPos + 1, passwordPos + 1 + passwordLength);
            // 验证用户名密码
            if (validateCredentials(username, password)) {
                securityService.addAuthorizedHost(clientSocket.remoteAddress().host());
                // 发送成功响应
                sendAuthReply(clientSocket, SOCKS5_AUTH_SUCCESS);
                // 成功后请求处理
                clientSocket.handler(requestHandler(clientSocket));
            } else {
                // 发送失败响应
                sendAuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
                clientSocket.close();
            }
        };
    }

    /**
     * 处理UDP关联请求
     */
    private void handleUdpAssociate(NetSocket clientSocket, String clientAddress, Buffer msgId) {
        try {
            // 分配UDP端口
            int udpPort = allocateUdpPort();

            // 创建UDP服务器
            NetServer udpServer = vertx.createNetServer(new NetServerOptions()
                    .setIdleTimeout(IDLE_TIMEOUT)
                    .setReceiveBufferSize(BUFFER_SIZE)
                    .setSendBufferSize(BUFFER_SIZE));

            // 处理UDP数据
            udpServer.connectHandler(udpSocket -> {
                udpSocket.handler(buffer -> {
                    // 处理来自客户端的UDP数据
                    handleUdpData(udpSocket, clientSocket, buffer);
                });

                udpSocket.exceptionHandler(err ->
                        log.error("UDP连接异常: {}", err.getMessage(), err));
            });

            // 启动UDP服务器
            udpServer.listen(udpPort, res -> {
                if (res.succeeded()) {
                    // 保存UDP服务器引用
                    udpServerMap.put(clientAddress, udpServer);

                    // 发送成功响应，包含分配的UDP端口
                    sendSocks5UdpReply(clientSocket, SOCKS5_REPLY_SUCCEEDED, udpPort);

                    log.debug("UDP关联成功，分配端口: {}", udpPort);
                } else {
                    log.error("UDP服务器启动失败: {}", res.cause().getMessage());
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE);
                    clientSocket.close();
                }
            });
        } catch (Exception e) {
            log.error("处理UDP关联请求失败", e);
            sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE);
            clientSocket.close();
        }
    }

    /**
     * 处理UDP数据转发
     */
    private void handleUdpData(NetSocket udpSocket, NetSocket clientSocket, Buffer buffer) {
        // 这里实现UDP数据的实际转发逻辑
        // 需要解析SOCKS5 UDP请求格式并转发到目标地址
        log.debug("收到UDP数据，长度: {}", buffer.length());
    }

    /**
     * 发送SOCKS5 UDP关联响应
     */
    private void sendSocks5UdpReply(NetSocket socket, byte replyCode, int bindPort) {
        Buffer reply = Buffer.buffer(new byte[]{
                SOCKS5_VERSION,           // 版本
                replyCode,                // 回复码
                0x00,                     // 保留字段
                SOCKS5_ADDR_TYPE_IPV4,    // 地址类型
                0x00, 0x00, 0x00, 0x00,   // 绑定地址(0.0.0.0)
                (byte) ((bindPort >> 8) & 0xFF),  // 绑定端口高字节
                (byte) (bindPort & 0xFF)          // 绑定端口低字节
        });
        socket.write(reply);
    }

    /**
     * 发送认证响应 认证成功返回：0x01,0x00;认证失败返回：0x01,0x01;
     */
    private void sendAuthReply(NetSocket socket, byte authStatus) {
        socket.write(Buffer.buffer(new byte[]{SOCKS5_AUTH_VERSION, authStatus}));
    }

    /**
     * 验证用户名密码
     */
    private boolean validateCredentials(String username, String password) {
        return username != null && username.equals(this.clientRegister.getName()) &&
                password != null && password.equals(this.clientRegister.getPassword());
    }

    @Override
    public void writeData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer realData) {
        NetSocket clientNetSocket = this.getRequest(requestId);
        if (clientNetSocket != null) {
            String clientAddress = clientNetSocket.remoteAddress().toString();
            if (JRPMsgType.CLOSE == msgType) {
                log.debug("收到内网代理服务返回的关闭信息[{}]，关闭SOCKS5连接。", clientAddress);
                this.closeRequest(requestId, clientNetSocket);
            } else if (JRPMsgType.RESPONSE == msgType) {
                log.debug("收到内网代理服务返回数据并返回给SOCKS5客户端[{}]。", clientAddress);
                clientNetSocket.write(realData);
                if (clientNetSocket.writeQueueFull()) {
                    clientNetSocket.pause();
                    clientNetSocket.drainHandler(done -> clientNetSocket.resume());
                }
            } else {
                log.warn("收到内网代理服务返回数据[{}]，消息类型[{}]不匹配！", clientAddress, msgType);
            }
        } else if (JRPMsgType.CLOSE == msgType) {
            log.warn("收到内网代理服务返回的关闭消息，SOCKS5客户端[{}]连接已经失效，不做处理！", requestId);
        } else {
            log.warn("收到内网代理服务返回消息，但是SOCKS5客户端[{}]连接已经失效，发送关闭连接消息到内网代理服务！", requestId);
            serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length())
                    .appendByte(JRPMsgType.CLOSE.getCode())
                    .appendBuffer(msgId));
        }
    }


    @Override
    public void stop() throws Exception {
        log.info("清理端口[{}]下SOCKS5代理和缓存！", clientProxy.getRemote_port());
        //clientSocketMap.values().forEach(NetSocket::close);
        // 关闭所有UDP服务器
        udpServerMap.values().forEach(NetServer::close);
        udpServerMap.clear();

        server.close();
        //clientSocketMap.clear();
        super.stop();
    }
}