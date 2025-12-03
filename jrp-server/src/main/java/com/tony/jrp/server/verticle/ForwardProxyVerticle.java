package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.SocksProxyProto;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.model.ForwardProxyRequest;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正向代理方式穿透服务，支持如下代理穿透方式：
 * 1. SOCKS5协议TCP/UDP代理穿透，支持HTTP认证：
 * 如果用户端无SOCKS5认证，比如google浏览器配置为"C:\Program Files\Google\Chrome\Application\chrome.exe" --proxy-server="socks5://127.0.0.1:1080"
 * 需要先浏览器访问“http://代理服务器IP:代理端口”，比如“http//127.0.0.1:1080”进行HTTP认证。
 * 2. SOCKS4协议TCP代理穿透，SOCKS4协议默认无认证，这里实现增加了基于http认证授权host访问，需要先浏览器访问“http://代理服务器IP:代理端口”，比如“http//127.0.0.1:1080”进行HTTP认证。
 * 3. HTTP代理协议穿透，基于HTTP协议，必须通过http用户名密码认证。
 */
@Slf4j
public class ForwardProxyVerticle extends AbstractProtocolVerticle<ForwardProxyRequest> {
    // SOCKS4协议版本号
    private static final byte SOCKS4_VERSION = 0x04;
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
    // BIND命令，反向连接（FTP等），不支持
//    private static final byte SOCKS5_CMD_BIND = 0x02;
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
    public static final String HOST_START = "Host: ";
    /**
     * 在类中添加UDP服务器映射
     */
    private final Map<String, NetServer> udpServerMap = new ConcurrentHashMap<>();

    /**
     * 创建TCP服务器用于SOCKS5
     */
    private NetServer server;
    /**
     * socks5监听端口对应字节
     */
    byte[] remotePortByte;

    // 在类中添加UDP端口分配方法
    private int allocateUdpPort() {
        // 简单实现：使用固定范围分配UDP端口
        return 10000 + (int) (Math.random() * 10000);
    }


    public ForwardProxyVerticle(ServerWebSocket serverSocket, SecurityService securityService,
                                ClientRegister clientRegister, ClientProxy clientProxy) {
        super(serverSocket, securityService, clientRegister, clientProxy);
        int remotePort = clientProxy.getRemote_port();
        remotePortByte = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
    }

    @Override
    public void init() {
        //创建HTTP认证服务
        Integer remotePort = clientProxy.getRemote_port();
        // 创建TCP服务器用于SOCKS5
        NetServerOptions options = new NetServerOptions();
        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setReceiveBufferSize(BUFFER_SIZE);
        options.setSendBufferSize(BUFFER_SIZE);
        //TCP监听
        server = this.vertx.createNetServer(options);
        // 处理SOCKS5连接请求，和基于端口的反向代理转发处理方式不一样：多了握手处理、认证处理（可选），握手和认证处理完后，从SOCKS5请求数据中获取目标服务地址和端口
        server.connectHandler(netSocket -> {
            netSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            SocketAddress socketAddress = netSocket.remoteAddress();
            log.debug("[{}] SOCKS5客户端连接!", socketAddress.toString());
            String clientAddress = socketAddress.toString();
            // 请求唯一标识
            int requestId = socketAddress.hashCode();
            // 连接关闭处理
            netSocket.closeHandler(voidHandler -> {
                log.debug("SOCKS5客户端[{}]连接关闭！", clientAddress);
                if (this.hasRequest(requestId)) {
                    this.removeCacheAndClose(requestId);
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

            netSocket.exceptionHandler(err -> {
                this.removeCacheAndClose(requestId);
                netSocket.close();
                log.error("SOCKS5客户端[{}]异常：{}！", clientAddress, err.getMessage(), err);
            });
            // 设置http认证和协议处理器
            netSocket.handler(httpAuthAndProtocolHandler(netSocket));
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
    protected void closeRequest(ForwardProxyRequest request) {
        request.close();
    }

    /**
     * http认证和协议处理
     * 当穿透类型为socks4和socks5（无用户名、密码认证的socks5）时，需要先通过浏览器访问"http://代理ip:代理端口"进行认证，然后才能通过socks协议进行UDP、TCP转发，否则直接代理关闭连接。
     * http代理也需要先进行http认证，基于host缓存认证信息。
     *
     * @param socket 客户端socket
     * @return 处理器
     */
    private Handler<Buffer> httpAuthAndProtocolHandler(NetSocket socket) {
        SocketAddress remoteAddress = socket.remoteAddress();
        return buffer -> {
            //为提高安全性，增加http认证，基于host缓存认证信息。
            // socks5无认证（用户名、密码认证）时，需要先通过浏览器访问"http://代理ip:代理端口"进行认证，然后才能通过socks5协议进行UDP、TCP转发，否则直接代理关闭连接。
            boolean httpFlag = securityService.isHTTPRequest(buffer);
            if (httpFlag) {
                //处理http认证
                String host = remoteAddress.host();
                int remotePort = remoteAddress.port();
                ///authorized：非HTTP请求通过HTTP认证过，或者缓存过请求信息
                boolean authorized = securityService.authorized(host);
                //substrate.office.com:443,http://192.168.1.13:1081/
                String[] methodAndUrl = buffer.toString().split(" ", 3);
                HttpMethod method = HttpMethod.valueOf(methodAndUrl[0]);
                String url = methodAndUrl[1];
                //是否为http认证请求，当url包括“:port”或者“:port/”，port为穿透代理外网端口，则表示为http认证请求
                boolean authRequest = url.contains(":") && (url.endsWith(":" + clientProxy.getRemote_port()) || url.contains(":" + clientProxy.getRemote_port() + "/"));
                if (authorized || securityService.authorizeHttp(clientRegister, host, buffer)) {
                    if (authRequest) {
                        log.debug("[{}]正向代理穿透HTTP认证成功！", host);
                        //代理本身端口用于认证，认证成功返回
                        socket.end(Buffer.buffer(securityService.getOKResponse()));
                        socket.close();
                    } else {
                        /*
                         如果是http和https代理协议报文，按http代理转发
                         CONNECT substrate.office.com:443 HTTP/1.0
                         Host: substrate.office.com:443
                         Content-Length: 0
                         Proxy-Connection: Keep-Alive
                         Pragma: no-cache
                         /r/n
                         /r/n
                         */
                        //http报文：
                        //GET http://192.168.1.13:1081/ HTTP/1.1
                        //Host: 192.168.1.13:1081
                        //Proxy-Connection: keep-alive
                        //Upgrade-Insecure-Requests: 1
                        //User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0
                        //Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
                        //Accept-Encoding: gzip, deflate
                        //Accept-Language: zh-CN,zh;q=0.9
                        ///r/n
                        ///r/n
                        //判断是否包含Proxy-Connection请求头，如果包含，是http代理协议请求，建立http代理协议穿透连接
                        StringTokenizer requestLines = new StringTokenizer(buffer.toString(), "\r\n");
                        //判断是否包含Proxy-Connection请求头，如果包含，是http代理协议请求，建立http代理协议穿透连接
                        boolean proxy = false;
                        // 提取目标主机和端口示例逻辑
                        String targetHost = null;
                        Integer targetPort = null;
                        while (requestLines.hasMoreTokens()) {
                            String requestLine = requestLines.nextToken();
                            if (requestLine.startsWith("Proxy-Connection")) {
                                proxy = true;
                            } else if (requestLine.startsWith(HOST_START)) {
                                /*
                                Host: substrate.office.com:443
                                Host: 192.168.0.89:8001
                                Host: 192.168.0.89
                                */
                                String hostInHeader = requestLine.substring(HOST_START.length());
                                if (hostInHeader.contains(":")) {
                                    String[] hostPort = hostInHeader.split(":");
                                    targetHost = hostPort[0];
                                    targetPort = Integer.parseInt(hostPort[1]);
                                } else {
                                    targetHost = hostInHeader;
                                    targetPort = 80; // 默认HTTP端口
                                }
                            }
                            if (proxy && targetHost != null) {
                                break;
                            }
                        }
                        if (proxy && targetHost != null) {
                            //转发请求
                            log.debug("转发来自[{}]的正向代理穿透HTTP请求到内网客户端！", remoteAddress);
                            //http代理协议，建立http或https穿透连接
                            this.sendConnectInfo(socket, HttpMethod.CONNECT == method ? SocksProxyProto.HTTPS : SocksProxyProto.HTTP, targetHost, targetPort);
                        } else {
                            log.warn("关闭来自[{}]的非正向代理穿透请求！", remoteAddress);
                            socket.end(Buffer.buffer(securityService.getOKResponse()));
                            socket.close();
                        }
                    }
                } else {
                    if (authRequest) {
                        log.warn("[{}]未授权访问代理:{}，浏览器弹窗输入认证信息！", remoteAddress, remotePort);
                        // 将重定向响应写入socket
                        socket.end(Buffer.buffer(securityService.getAuthenticateResponse(host)));
                        socket.close();
                    } else {
                        log.warn("[{}]未授权访问穿透代理，直接关闭！", remoteAddress);
                        socket.close();
                    }
                }
            } else {
                //判断是否为sock4、sock5代理协议
                if (buffer.length() < 2) {
                    log.warn("握手请求报文不正确：{}", remoteAddress);
                    socket.close();
                    return;
                }
                // VERSION：协议版本号，1个字节，socks5为0x05
                byte version = buffer.getByte(0);
                if (version == SOCKS4_VERSION) {
                    //报文格式：VERSION(1) + CD(1) + DSTPORT(2) + DSTIP(4) + USERID(var) + NULL(1)
                    //获取CD 1字节 操作代码，连接请求值为1
                    byte cmd = buffer.getByte(1);
                    //获取DSTPORT	2字节	目标服务器的端口号
                    int targetPort = ((buffer.getByte(2) & 0xFF) << 8) | (buffer.getByte(3) & 0xFF);
                    //获取DSTIP	4字节	目标服务器的IP地址
                    StringBuilder ipBuilder = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) ipBuilder.append(".");
                        ipBuilder.append(buffer.getByte(4 + i) & 0xFF);
                    }

                    String dstIP = ipBuilder.toString();
                    //通过dstIP判断是否为socks4a协议
                    // socks4a协议格式：0x04（版本）+ 0x01（CONNECT命令）+ 目标端口（2字节）+ 目标IP（4字节，通常填0.0.0.1）+ 域名（以NULL结尾）+ 用户ID（以NULL结尾）
                    boolean socks4a = dstIP.startsWith("0.0.0.");
                    StringBuilder domainBuilder = new StringBuilder();
                    if (socks4a) {
                        for (int i = 8; i < buffer.length(); i++) {
                            if (buffer.getByte(i) == 0) {
                                break;
                            }
                            domainBuilder.append((char) buffer.getByte(i));
                        }
                        //比如www.example.com
                        String domain = domainBuilder.toString();
                        log.debug("来自[{}]的SOCKS4a代理穿透请求，目标域名为：{}", remoteAddress, domain);
                    }
                    //获取USERID
                    StringBuilder userIdBuilder = new StringBuilder();
                    for (int i = 8 + (socks4a ? domainBuilder.length() + 1 : 0); i < buffer.length(); i++) {
                        if (buffer.getByte(i) == 0) {
                            break;
                        }
                        userIdBuilder.append((char) buffer.getByte(i));
                    }
                    String userId = userIdBuilder.toString();
                    if (clientRegister.getUsername().equals(userId)) {
                        log.debug("来自[{}]的SOCKS4代理穿透请求认证通过！", remoteAddress);
                        String targetHost = socks4a ? domainBuilder.toString() : dstIP;
                        sendConnectInfo(socket, SocksProxyProto.SOCK4A_TCP, targetHost, targetPort);
                    } else {
                        log.warn("来自[{}]的SOCKS4代理穿透请求认证失败！", remoteAddress);
                        socket.close();
                    }
                } else if (version == SOCKS5_VERSION) {
                    //NMETHODS：客户端支持的认证方法数量，1个字节，1~255
                    int nMethods = buffer.getByte(1) & 0xFF;
                    if (buffer.length() < 2 + nMethods) {
                        log.warn("支持的认证方法数量不正确：{}", remoteAddress);
                        socket.close();
                        return;
                    }
                    log.debug("来自[{}]的SOCKS5代理请求！", remoteAddress);
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
                        socket.write(Buffer.buffer(new byte[]{SOCKS5_VERSION, (byte) 0xFF}));
                        socket.close();
                        return;
                    }
                    // 发送认证方法选择
                    socket.write(Buffer.buffer(new byte[]{SOCKS5_VERSION, selectedMethod}));
                    if (selectedMethod == SOCKS5_AUTH_METHOD_USERNAME_PASSWORD) {
                        // 切换到用户名密码认证处理阶段
                        socket.handler(socks5AuthHandler(socket));
                    } else {
                        // 无需认证，尝试和内网建立连接
                        socket.handler(socks5RequestHandler(socket));
                    }
                } else {
                    log.warn("socks协议版本号不匹配：{}", remoteAddress);
                    socket.close();
                }
            }
        };
    }

    /**
     * 3.SOCKS5请求处理处理器：尝试和内网建立连接
     *
     * @param clientSocket SOCKS5客户端socket
     * @return 处理器
     */
    private Handler<Buffer> socks5RequestHandler(NetSocket clientSocket) {
        return buffer -> {
            if (buffer.length() < 7) {
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                return;
            }
            //SOCKS版本号：0x05代表SOCKS5
            byte version = buffer.getByte(0);
            /*
            命令：0x01=CONNECT，0x02=BIND，0x03=UDP ASSOCIATE

            0x01（CONNECT）：建立TCP连接（如代理HTTP）。
            0x02（BIND）：反向连接（FTP等）。
            0x03（UDP ASSOCIATE）：建立UDP代理通道。
             */
            byte cmd = buffer.getByte(1);
            //目标地址类型：0x01=IPv4，0x03=域名，0x04=IPv6
            byte addrType = buffer.getByte(3);
            SocketAddress clientAddress = clientSocket.remoteAddress();
            if (version != SOCKS5_VERSION || (cmd != SOCKS5_CMD_CONNECT && cmd != SOCKS5_CMD_UDP_ASSOCIATE)) {
                log.warn("SOCKS5请求报文不支持：{}", clientAddress);
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                return;
            }
            try {
                //目标地址。取决于 ATYP。
                String targetHost;
                //目标端口（大端序）
                int targetPort;
                //int addrLength;
                if (addrType == SOCKS5_ADDR_TYPE_IPV4) {
                    // IPv4地址
                    if (buffer.length() < 10) {
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                        return;
                    }
                    StringBuilder ipBuilder = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) ipBuilder.append(".");
                        ipBuilder.append(buffer.getByte(4 + i) & 0xFF);
                    }
                    targetHost = ipBuilder.toString();
                    targetPort = ((buffer.getByte(8) & 0xFF) << 8) | (buffer.getByte(9) & 0xFF);
                    //addrLength = 10;
                } else if (addrType == SOCKS5_ADDR_TYPE_DOMAIN) {
                    // 域名地址
                    int domainLength = buffer.getByte(4) & 0xFF;
                    if (buffer.length() < 7 + domainLength) {
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                        return;
                    }
                    targetHost = buffer.getString(5, 5 + domainLength);
                    targetPort = ((buffer.getByte(5 + domainLength) & 0xFF) << 8) |
                            (buffer.getByte(6 + domainLength) & 0xFF);
                    //addrLength = 7 + domainLength;
                } else {
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                    return;
                }

                //认证检查
                if (!securityService.authorized(clientAddress.host())) {
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                } else {
                    SocksProxyProto socksProxyProto = cmd == SOCKS5_CMD_UDP_ASSOCIATE ? SocksProxyProto.SOCK5_UDP : SocksProxyProto.SOCK5_TCP;
                    if (socksProxyProto == SocksProxyProto.SOCK5_UDP) {
                        //添加UDP代理
                    }
                    sendConnectInfo(clientSocket, socksProxyProto, targetHost, targetPort);
                }
            } catch (Exception e) {
                log.error("处理SOCKS5请求失败:{}", e.getMessage(), e);
                sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
            }
        };
    }

    /**
     * 发送连接信息给内网代理程序，并缓存连接
     *
     * @param clientSocket    SOCKS5客户端socket
     * @param socksProxyProto 代理协议
     * @param targetHost      目标地址
     * @param targetPort      目标端口
     */
    private void sendConnectInfo(NetSocket clientSocket, SocksProxyProto socksProxyProto, String targetHost, int targetPort) {
        int requestId = clientSocket.hashCode();
        // 通知内网代理建立连接
        byte[] portBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) targetPort).array();
        Buffer target = Buffer.buffer(1 + targetHost.length() + 1 + portBytes.length)
                .appendByte(socksProxyProto.getProto())
                .appendString(targetHost)
                .appendByte((byte) ':')
                .appendBytes(portBytes);
        Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                .appendBytes(remotePortByte)
                .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
        this.cacheRequest(requestId, ForwardProxyRequest.create(clientSocket, socksProxyProto, targetHost, targetPort));
        //转发连接信息给穿透客户端
        this.serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + target.length())
                .appendByte(JRPMsgType.RECEIVE.getCode())
                .appendBuffer(msgId)
                .appendBuffer(target));
        //1秒后没收到创建连接成功消息，关闭连接
        vertx.setTimer(1000, id -> {
            this.removeCacheAndClose(requestId);
        });
    }

    /**
     * 数据处理器：接收和转发用户端数据到内网代理程序
     */
    private Handler<Buffer> dataHandler(Buffer msgId) {
        return buffer -> {
            // 将SOCKS5客户端加入管理
            serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + buffer.length())
                    .appendByte(JRPMsgType.RECEIVE.getCode())
                    .appendBuffer(msgId)
                    .appendBuffer(buffer));
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
    private Handler<Buffer> socks5AuthHandler(NetSocket clientSocket) {
        return buffer -> {
            if (buffer.length() < 3) {
                clientSocket.close();
                return;
            }

            byte authVersion = buffer.getByte(0);
            if (authVersion != SOCKS5_AUTH_VERSION) {
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
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
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_SUCCESS);
                // 成功后请求处理
                clientSocket.handler(socks5RequestHandler(clientSocket));
            } else {
                // 发送失败响应
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
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
     *
     * @param udpSocket    UDP套接字
     * @param clientSocket 客户端套接字
     * @param buffer       数据缓冲区
     */
    private void handleUdpData(NetSocket udpSocket, NetSocket clientSocket, Buffer buffer) {
        // 这里实现UDP数据的实际转发逻辑
        // 需要解析SOCKS5 UDP请求格式并转发到目标地址
        log.debug("收到UDP数据，长度: {}", buffer.length());
    }

    /**
     * 发送SOCKS5 UDP关联响应
     *
     * @param socket    套接字
     * @param replyCode 回复码
     * @param bindPort  绑定端口
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
     *
     * @param socket     连接对象
     * @param authStatus 认证状态
     */
    private void sendSocks5AuthReply(NetSocket socket, byte authStatus) {
        socket.write(Buffer.buffer(new byte[]{SOCKS5_AUTH_VERSION, authStatus}));
    }

    /**
     * 验证用户名密码
     *
     * @param username 用户名
     * @param password 密码
     * @return 验证结果
     */
    private boolean validateCredentials(String username, String password) {
        return username != null && username.equals(this.clientRegister.getName()) &&
                password != null && password.equals(this.clientRegister.getPassword());
    }

    @Override
    public void writeData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer realData) {
        ForwardProxyRequest proxyRequest = this.getRequest(requestId);
        if (proxyRequest != null) {
            NetSocket clientNetSocket = proxyRequest.getSocket();
            SocksProxyProto proxyProto = proxyRequest.getProxyProto();
            String clientAddress = clientNetSocket.remoteAddress().toString();
            if (JRPMsgType.CLOSE == msgType) {
                log.debug("收到内网代理服务返回的关闭信息[{}]，关闭SOCKS5连接。", clientAddress);
                this.removeCacheAndClose(requestId);
            } else if (JRPMsgType.RESPONSE == msgType) {
                log.debug("收到内网代理服务返回数据并返回给SOCKS5客户端[{}]。", clientAddress);
                //SOCK5_TCP或SOCK4_TCP穿透客户端创建TCP连接成功后会返回空消息(realData.length()==0)
                if (realData.length() == 0) {
                    //接收和转发来自用户端的数据
                    clientNetSocket.handler(dataHandler(msgId));
                    if (SocksProxyProto.SOCK5_TCP == proxyProto) {
                        // 发送socks5穿透隧道创建成功响应
                        log.debug("穿透客户端创建TCP连接成功，发送socks5 TCP隧道创建成功响应。");
                        sendSocks5Reply(clientNetSocket, SOCKS5_REPLY_SUCCEEDED).onSuccess(done -> {
                            proxyRequest.setTunnelStatus(true);
                        });
                    }
                } else {
                    clientNetSocket.write(realData);
                    if (clientNetSocket.writeQueueFull()) {
                        clientNetSocket.pause();
                        clientNetSocket.drainHandler(done -> clientNetSocket.resume());
                    }
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