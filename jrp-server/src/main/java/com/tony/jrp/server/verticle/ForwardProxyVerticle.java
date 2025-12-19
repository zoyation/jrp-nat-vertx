package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ProxyProto;
import com.tony.jrp.common.enums.ServiceType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.model.ProxyRequest;
import com.tony.jrp.server.model.UdpRequest;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.StringJoiner;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tony.jrp.common.utils.JRPConstants.IP_PORT_SEPARATOR;

/**
 * 正向代理方式穿透服务，支持如下代理穿透方式：
 * 1. SOCKS5协议TCP/UDP代理穿透，支持HTTP认证：
 * 如果用户端无SOCKS5认证，比如google浏览器配置为"C:\Program Files\Google\Chrome\Application\chrome.exe" --proxy-server="socks5://127.0.0.1:1080"
 * 需要先浏览器访问“http://代理服务器IP:代理端口”，比如“http//127.0.0.1:1080”进行HTTP认证。
 * 2. SOCKS4协议TCP代理穿透，SOCKS4协议默认无认证，这里实现增加了基于http认证授权host访问，需要先浏览器访问“http://代理服务器IP:代理端口”，比如“http//127.0.0.1:1080”进行HTTP认证。
 * 3. HTTP代理协议穿透，基于HTTP协议，必须通过http用户名密码认证。
 */
@Slf4j
public class ForwardProxyVerticle extends AbstractProtocolVerticle<ProxyRequest> {
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
    // IPv6地址类型
    private static final byte SOCKS5_ADDR_TYPE_IPV6 = 0x04;
    // SOCKS5响应：成功
    private static final byte SOCKS5_REPLY_SUCCEEDED = 0x00;
    // SOCKS5响应：通用失败
    private static final byte SOCKS5_REPLY_GENERAL_FAILURE = 0x01;
    // SOCKS4 响应：成功
    private static final byte SOCKS4_REPLY_SUCCEEDED = 0x5A;
    public static final String PROXY_CONNECTION = "Proxy-Connection";
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String ALL_HOST = "0.0.0.0";
    /**
     * 创建TCP服务器用于http代理、SOCKS代理穿透
     */
    private NetServer server;
    /**
     * 代理监听端口对应大端序字节
     */
    private final byte[] remotePortByte;

    // 在类中添加UDP端口分配方法
    private int allocateUdpPort() {
        // 简单实现：使用固定范围分配UDP端口30000~40000
        return 30000 + (int) (Math.random() * 10000);
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
//        options.setIdleTimeout(IDLE_TIMEOUT);
        options.setTcpKeepAlive(true);
        options.setTcpNoDelay(true);
        options.setTcpFastOpen(true);
        //options.setClientAuth(ClientAuth.REQUIRED);
        options.setReceiveBufferSize(BUFFER_SIZE);
        options.setSendBufferSize(BUFFER_SIZE);
        if (clientProxy.getType() == ServiceType.HTTPS_PROXY) {
            options.setSsl(true);
            options.setKeyCertOptions(securityService.getKeyCertOptions());
        }
        //TCP监听
        server = this.vertx.createNetServer(options);
        // 处理SOCKS5连接请求，和基于端口的反向代理转发处理方式不一样：多了握手处理、认证处理（可选），握手和认证处理完后，从SOCKS5请求数据中获取目标服务地址和端口
        server.connectHandler(netSocket -> {
            netSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            SocketAddress socketAddress = netSocket.remoteAddress();
            log.debug("收到代理连接，客户端地址[{}]，连接类型[{}]!", socketAddress.toString(), clientProxy.getType().name());
            // 请求唯一标识
            int requestId = socketAddress.hashCode();
            // 消息唯一标识 端口+请求ID，6字节
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                    .appendBytes(remotePortByte)
                    .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            // 连接关闭处理
            netSocket.closeHandler(voidHandler -> {
                log.debug("代理客户端[{}]连接关闭！", socketAddress);
                boolean cachedRequest = this.cachedRequest(requestId);
                this.removeCacheAndClose(requestId);
                if (cachedRequest) {
                    log.debug("代理客户端连接关闭，发送关闭连接消息到被代理端[{}]！", socketAddress);
                    serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length())
                            .appendByte(JRPMsgType.CLOSE.getCode())
                            .appendBuffer(msgId));
                }
            });

            netSocket.exceptionHandler(err -> {
                this.removeCacheAndClose(requestId);
                netSocket.close();
                log.error("代理客户端[{}]异常：{}！", socketAddress, err.getMessage(), err);
            });
            // 设置http认证和协议处理器
            netSocket.handler(httpAuthAndProtocolHandler(netSocket));
        });

        server.listen(remotePort, res -> {
            if (res.succeeded()) {
                log.info("内网穿透代理服务启动成功，代理端口：{}。", remotePort);
            } else {
                log.error("端口[{}]内网穿透代理服务启动失败：{}",
                        remotePort, res.cause().getMessage(), res.cause());
            }
        });
    }

    @Override
    protected void closeRequest(ProxyRequest request) {
        request.close();
    }

    /**
     * http认证和代理协议处理器
     * 当穿透类型为socks5用户名、密码认证时，socks5客户端按socks5协议进行用户名密码认证，然后才能通过socks协议进行UDP、TCP转发，否则直接代理关闭连接。
     * 当穿透类型为socks4和socks5（无用户名、密码认证的socks5）时，需要先浏览器访问"http://代理ip:代理端口"进行认证，服务端基于host标识认证信息，然后才能通过socks协议进行UDP、TCP转发，否则直接代理关闭连接。
     * http代理需要浏览器访问"http://代理ip:代理端口"进行认证。
     * <p>
     * 基于host标识认证信息存在问题：
     * host（ip）是运营商动态的情况时，不固定(家庭网络运营商动态分配IP)，共用同一个外网IP的局域网其他设备也能访问
     * 外网IP变化后，要重新认证，服务端不能感知到用户的外网IP变化，原来的认证信无法移除。
     * 解决办法：
     * 1.http请求可强制使用基于用户名、密码认证。
     * 2.socks4和socks5(无用户名、密码认证的socks5)通过http认证通过后，设置认证通过的host缓存有效期，超过有效期后，清除认证信息。
     *
     * @param socket 客户端socket
     * @return 处理器
     */
    private Handler<Buffer> httpAuthAndProtocolHandler(NetSocket socket) {
        SocketAddress remoteAddress = socket.remoteAddress();
        return buffer -> {
            //log.debug("收到[{}]客户端数据[{}]！", remoteAddress.toString(), buffer.toString());
            int requestId = socket.hashCode();
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                    .appendBytes(remotePortByte)
                    .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            //为提高安全性，增加http认证。
            //socks5无认证（用户名、密码认证）时，需要先通过浏览器访问"http://代理ip:代理端口"进行认证，然后才能通过socks5协议进行UDP、TCP转发，否则直接关闭连接。
            //Proxy-Authorization: Digest
            //是否为HTTP请求，报文以CONNECT或GET开头需要进行http认证
            log.debug("httpAuthData:{}", buffer.toString());
            boolean httpFlag = securityService.isHTTPRequest(buffer);
            if (httpFlag) {
                String bufferStr = buffer.toString();
                log.debug("收到[{}]客户端数据[{}]！", remoteAddress.toString(), bufferStr);
                //处理http认证
                String host = remoteAddress.host();
                int remotePort = remoteAddress.port();
                //substrate.office.com:443,http://192.168.1.13:1081/
                String[] methodAndUrl = bufferStr.split(" ", 3);
                HttpMethod method = HttpMethod.valueOf(methodAndUrl[0]);
                String url = methodAndUrl[1];
                //是否为http代理认证请求，当url包括“:port”或者“:port/”，port为穿透代理外网端口，则表示为http代理认证请求
                boolean httpProxyAuthRequest = url.contains(":") && (url.endsWith(":" + clientProxy.getRemote_port()) || url.contains(":" + clientProxy.getRemote_port() + "/"));
                if (httpProxyAuthRequest) {
                    if (clientProxy.getType() == ServiceType.HTTP_PROXY || clientProxy.getType() == ServiceType.HTTPS_PROXY || clientProxy.getType() == ServiceType.SMART_PROXY) {
                        if (securityService.authorizeHttpProxy(clientRegister, host, buffer)) {
                            //HTTP代理方式穿透，通过代理IP:端口认证，认证成功返回成功就行
                            log.debug("[{}]正向代理穿透认证成功！", host);
                            socket.end(Buffer.buffer(securityService.getOKResponse()));
                        } else {
                            //认证未通过，返回认证失败
                            log.warn("[{}]未授权访问正向代理穿透:{}，浏览器弹窗输入认证信息！", remoteAddress, remotePort);
                            //http代理认证
                            socket.end(Buffer.buffer(securityService.getHttpProxyAuthenticateResponse(host)));
                        }
                    } else {
                        //不支持http代理方式，直接关闭
                        socket.close();
                    }
                } else {
                    boolean proxyConnection = bufferStr.contains(PROXY_CONNECTION);
                    Pattern hostPattern = Pattern.compile("^Host:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
                    Matcher matcher = hostPattern.matcher(bufferStr);
                    String targetHost;
                    int targetPort = 80;
                    if (matcher.find()) {
                        String hostHeaderValue = matcher.group(1).trim();
                        int colonIndex = hostHeaderValue.lastIndexOf(':');
                        if (colonIndex != -1) {
                            try {
                                targetHost = hostHeaderValue.substring(0, colonIndex);
                                targetPort = Integer.parseInt(hostHeaderValue.substring(colonIndex + 1));
                            } catch (NumberFormatException e) {
                                log.warn("解析 Host 端口失败: {}", hostHeaderValue);
                                // 默认端口
                                return;
                            }
                        } else {
                            targetHost = hostHeaderValue;
                            // 默认 HTTP 端口
                        }
                    } else {
                        log.warn("未找到Host:{}", host);
                        return;
                    }
                    if (proxyConnection) {//HTTP代理请求
                        if (clientProxy.getType() == ServiceType.HTTP_PROXY || clientProxy.getType() == ServiceType.HTTPS_PROXY || clientProxy.getType() == ServiceType.SMART_PROXY) {
                            if (securityService.authorizeHttpProxy(clientRegister, host, buffer)) {//通过认证的代理请求，尝试和内网建立连接转发数据
                                Buffer httpData = removeHttpProxy(bufferStr);
                                //重新设置tcp数据接收处理器
                                socket.handler(httDataHandler(msgId));
                                //转发请求
                                log.debug("转发来自[{}]的正向代理穿透HTTP请求到内网客户端！", remoteAddress);

                                this.sendConnectInfo(socket, requestId, msgId, HttpMethod.CONNECT == method ? ProxyProto.HTTPS : ProxyProto.HTTP, targetHost, null, ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) targetPort).array(), httpData);
                            } else {
                                log.warn("[{}]未授权访问HTTP代理:{}，直接关闭！", remoteAddress, remotePort);
                                socket.end();
                            }
                        } else {
                            //不支持http代理方式，直接关闭
                            socket.close();
                        }
                    } else if (clientProxy.getType() == ServiceType.SOCKS4 || clientProxy.getType() == ServiceType.SOCKS5 || clientProxy.getType() == ServiceType.SMART_PROXY) {
                        //支持socks无认证代理，需要先做http认证
                        if (securityService.authorizeHttp(clientRegister, host, buffer)) {
                            //http认证成功，只返回ok，可以客户端配置socks方式访问内网
                            socket.end(Buffer.buffer(securityService.getOKResponse()));
                        } else {
                            log.warn("来自[{}]的非HTTP正向代理穿透方式请求，浏览器弹窗提示输入认证信息！", remoteAddress);
                            socket.end(Buffer.buffer(securityService.getAuthenticateResponse(host)));
                        }
                    } else {
                        //不支持socks代理方式，直接关闭
                        socket.close();
                    }
                }
            } else {
                //判断是否为sock4、sock4a、sock5代理协议
                if (buffer.length() < 2) {
                    log.warn("握手请求报文不正确：{}", remoteAddress);
                    socket.close();
                    return;
                }
                // VERSION：协议版本号，1个字节，socks5为0x05
                byte version = buffer.getByte(0);
                if (version == SOCKS4_VERSION && (clientProxy.getType() == ServiceType.SOCKS4 || clientProxy.getType() == ServiceType.SMART_PROXY)) {
                    //报文格式：VERSION(1) + CD(1) + DSTPORT(2) + DSTIP(4) + USERID(var) + NULL(1)
                    //获取CD 1字节 操作代码，连接请求值为1
                    //byte cmd = buffer.getByte(1);
                    //获取DSTPORT	2字节	目标服务器的端口号
                    byte[] targetPort = buffer.getBytes(2, 4);
                    //获取DSTIP	4字节	目标服务器的IP地址
                    StringBuilder ipBuilder = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) ipBuilder.append(".");
                        ipBuilder.append(buffer.getByte(4 + i) & 0xFF);
                    }

                    String dstIP = ipBuilder.toString();
                    byte[] dstIPByte = buffer.getBytes(4, 8);
                    //通过dstIP判断是否为socks4a协议
                    // socks4a协议格式：0x04（版本，1字节）+ 0x01（CONNECT命令，1字节）+ 目标端口（2字节）+ 目标IP（4字节，通常填0.0.0.1）+ 用户ID（以NULL结尾）+ 域名
                    //VERSION(1) + CD(1) + DSTPORT(2) + DSTIP(4) + USERID(NULL terminated) + DOMAIN(NULL terminated)
                    boolean socks4a = dstIP.equals("0.0.0.1");
                    StringBuilder domainBuilder = new StringBuilder();
                    //获取USERID
                    StringBuilder userIdBuilder = new StringBuilder();
                    for (int i = 8; i < buffer.length(); i++) {
                        if (buffer.getByte(i) == 0) {
                            break;
                        }
                        userIdBuilder.append((char) buffer.getByte(i));
                    }
                    String userId = userIdBuilder.toString();
                    if (socks4a) {
                        for (int i = 8; i < buffer.length(); i++) {
                            if (buffer.getByte(i) == 0) {
                                break;
                            }
                            domainBuilder.append((char) buffer.getByte(i));
                        }
                        if (8 + userId.length() + 1 < buffer.length()) {
                            for (int i = userId.length() + 1; i < buffer.length(); i++) {
                                if (buffer.getByte(i) == 0) {
                                    break;
                                }
                                domainBuilder.append((char) buffer.getByte(i));
                            }
                        }
                        //比如www.example.com
                        log.debug("来自[{}]的SOCKS4a代理穿透请求，目标域名为：{}", remoteAddress, domainBuilder);
                    }
                    //用户名和host验证
                    if ((StringUtils.hasText(userId) && clientRegister.getUsername().equals(userId)) || securityService.authorized(socket.remoteAddress().host())) {
                        log.debug("来自[{}]的SOCKS4代理穿透请求认证通过！", remoteAddress);
                        String targetHost = socks4a ? domainBuilder.toString() : dstIP;
                        sendConnectInfo(socket, requestId, msgId, ProxyProto.SOCK4A_TCP, targetHost, dstIPByte, targetPort, Buffer.buffer());
                    } else {
                        log.warn("来自[{}]的SOCKS4代理穿透请求认证失败！", remoteAddress);
                        socket.close();
                    }
                } else if (version == SOCKS5_VERSION && (clientProxy.getType() == ServiceType.SOCKS5 || clientProxy.getType() == ServiceType.SMART_PROXY)) {
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
                        log.info("切换到用户名密码认证处理阶段：{}", remoteAddress);
                        socket.handler(socks5AuthHandler(socket, msgId));
                    } else {
                        // 无需认证，尝试和内网建立连接
                        socket.handler(socks5RequestHandler(socket, msgId));
                    }
                } else {
                    log.warn("socks协议版本号不匹配，或者不支持，直接关闭：{}", remoteAddress);
                    socket.close();
                }
            }
        };
    }

    /**
     * 移除请求行和请求头里的代理信息
     *
     * @param bufferStr 请求数据
     * @return 移除代理信息后的数据
     */
    private static Buffer removeHttpProxy(String bufferStr) {
        int bodyIndex = bufferStr.indexOf("\r\n\r\n");
        if (bodyIndex == -1) {
            log.info("bodyIndex-1:{}", bufferStr);
            return Buffer.buffer(bufferStr);
        }
        String requestLineAndHeader = bufferStr.substring(0, bodyIndex);
        StringTokenizer requestLines = new StringTokenizer(requestLineAndHeader, "\r\n");
        boolean connection = bufferStr.contains("\r\nConnection: ");
        if (connection) {
            log.info("ConnectionBuffer:{}", bufferStr);
        }
        StringJoiner dataBuilder = new StringJoiner("\r\n"); // 使用 StringBuilder 替代 StringJoiner 并修正换行符
        while (requestLines.hasMoreTokens()) {
            String requestLine = requestLines.nextToken();
            if (dataBuilder.length() == 0) {
                // 替换第一行的 URL 为相对路径
                requestLine = requestLine.replaceFirst("(https|http)://[^/]+", "");
            }
            if (requestLine.startsWith(PROXY_AUTHORIZATION)) {
                continue;
            }
            if (requestLine.startsWith(PROXY_CONNECTION)) {
                if (!connection) {
                    requestLine = requestLine.replace(PROXY_CONNECTION, "Connection");
                } else {
                    continue;
                }
            }
            dataBuilder.add(requestLine); // 正确地追加换行符
        }
        return Buffer.buffer(dataBuilder + bufferStr.substring(requestLineAndHeader.length()));
    }

    /**
     * 3.SOCKS5请求处理处理器：尝试和内网建立连接
     *
     * @param clientSocket 代理客户端socket
     * @param msgId        消息ID
     * @return 处理器
     */
    private Handler<Buffer> socks5RequestHandler(NetSocket clientSocket, Buffer msgId) {
        return buffer -> {
            if (buffer.length() < 7) {
                log.warn("SOCKS5请求报文长度不支持：{}", clientSocket.remoteAddress());
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
                byte[] dstIP = null;
                String targetHost;
                //目标端口（大端序）
                byte[] targetPort;
                //int addrLength;
                if (addrType == SOCKS5_ADDR_TYPE_IPV4) {
                    // IPv4地址
                    if (buffer.length() < 10) {
                        log.warn("SOCKS5请求报文IPv4地址长度不支持：{}", clientAddress);
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                        return;
                    }
                    StringBuilder ipBuilder = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) ipBuilder.append(".");
                        ipBuilder.append(buffer.getByte(4 + i) & 0xFF);
                    }
                    dstIP = buffer.getBytes(4, 8);
                    targetHost = ipBuilder.toString();
                    //byte b = -1;  // 在二进制中表示为 11111111
                    //int result = b & 0xFF;  // 结果是 255 (00000000 00000000 00000000 11111111)
                    //targetPort = ((buffer.getByte(8) & 0xFF) << 8) | (buffer.getByte(9) & 0xFF);
                    targetPort = buffer.getBytes(8, 10);
                    //addrLength = 10;
                } else if (addrType == SOCKS5_ADDR_TYPE_DOMAIN) {
                    // 域名地址
                    int domainLength = buffer.getByte(4) & 0xFF;
                    if (buffer.length() < 7 + domainLength) {
                        sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                        log.warn("SOCKS5域名地址长度不支持：{}", clientAddress);
                        return;
                    }
                    targetHost = buffer.getString(5, 5 + domainLength);
                    targetPort = buffer.getBytes(5 + domainLength, 5 + domainLength + 2);
                    //addrLength = 7 + domainLength;
                } else if (addrType == SOCKS5_ADDR_TYPE_IPV6) {
                    // IPv6地址: 16字节
                    byte[] ipv6byte = buffer.getBytes(4, 20);
                    targetHost = bytesToIPv6Address(ipv6byte);
                    // 端口: 2字节大端序
                    targetPort = buffer.getBytes(20, 22);

                } else {
                    log.warn("请求报文协议不支持：{}", clientAddress);
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                    return;
                }
                //认证检查
                if (!securityService.authorized(clientAddress.host())) {
                    log.warn("SOCKS5请求报文认证失败：{}", clientAddress);
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE).onComplete(voidHandler -> clientSocket.close());
                } else {
                    ProxyProto socksProxyProto = cmd == SOCKS5_CMD_UDP_ASSOCIATE ? ProxyProto.SOCK5_UDP : ProxyProto.SOCK5_TCP;
                    if (socksProxyProto == ProxyProto.SOCK5_UDP) {
                        //客户端发送UDP关联请求（CMD=0x03），包含目标服务器的IP地址和端口。
                        //代理服务器分配一个本地UDP端口，并通过TCP连接将此端口信息（IP+端口）返回客户端
                        handleUdpAssociate(clientSocket);
                    } else {
                        sendConnectInfo(clientSocket, clientSocket.hashCode(), msgId, socksProxyProto, targetHost, dstIP, targetPort, Buffer.buffer());
                    }
                    //sendConnectInfo(clientSocket, socksProxyProto, targetHost, ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) targetPort).array());
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
     * @param clientSocket 代理客户端socket
     * @param requestId    请求ID
     * @param msgId        消息ID
     * @param proxyProto   代理协议
     * @param targetHost   目标地址
     * @param dstIP        目标IP
     * @param targetPort   目标端口
     * @param data         需要转发的http或者udp数据
     */
    private void sendConnectInfo(NetSocket clientSocket, Integer requestId, Buffer msgId, ProxyProto proxyProto, String targetHost, byte[] dstIP, byte[] targetPort, Buffer data) {
        // 通知内网代理建立连接
        Buffer target = Buffer.buffer(1 + targetHost.length() + 1 + targetPort.length)
                .appendByte(proxyProto.getProto())
                .appendString(targetHost)
                .appendByte(IP_PORT_SEPARATOR)
                .appendBytes(targetPort);
        ProxyRequest proxyRequest = ProxyRequest.createTcpRequest(clientSocket, proxyProto, targetHost, dstIP, targetPort);
        this.cacheRequest(requestId, proxyRequest);
        //转发连接信息给穿透客户端
        this.serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + target.length())
                .appendByte(JRPMsgType.RECEIVE.getCode())
                .appendBuffer(msgId)
                .appendBuffer(target).appendBuffer(data));
        //1秒后没收到创建连接成功消息，关闭连接
        vertx.setTimer(1000, id -> {
            if (!proxyRequest.isTunneled()) {
                this.removeCacheAndClose(requestId);
            }
        });
    }

    /**
     * 数据处理器：接收和转发用户端数据到内网代理程序
     */
    private Handler<Buffer> dataHandler(Buffer msgId) {
        return buffer -> {
            // 将代理客户端加入管理
            serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + buffer.length())
                    .appendByte(JRPMsgType.RECEIVE.getCode())
                    .appendBuffer(msgId)
                    .appendBuffer(buffer));
        };
    }

    /**
     * http数据处理器：接收和转发用户端数据到内网代理程序
     */
    private Handler<Buffer> httDataHandler(Buffer msgId) {
        return buffer -> {
            String bufferStr = buffer.toString();
            Buffer sendBuffer;
            if (securityService.isHTTPRequest(buffer)) {
                sendBuffer = removeHttpProxy(bufferStr);
            } else {
                //非首次请求数据
                sendBuffer = buffer;
            }
            serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + sendBuffer.length())
                    .appendByte(JRPMsgType.RECEIVE.getCode())
                    .appendBuffer(msgId)
                    .appendBuffer(sendBuffer));
        };
    }

    /**
     * 发送SOCKS4响应
     *
     * @param socket    SOCKS4客户端socket
     * @param replyCode 回复码 1字节
     * @param dstPort   目标端口 2字节
     * @param dstIP     目标IP 4字节
     * @return 返回结果
     */
    private Future<Void> sendSocks4Reply(NetSocket socket, byte replyCode, byte[] dstPort, byte[] dstIP) {
        Buffer reply = Buffer.buffer(8)
                .appendBytes(new byte[]{
                        0x00,      // 版本 1字节
                        replyCode // 状态码，0x5A表示请求成功‌，0x5B-0x5F（失败）
                }).appendBytes(dstPort)
                .appendBytes(dstIP);
        return socket.write(reply);
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
     *
     * @param clientSocket 代理客户端socket
     * @param msgId        认证消息ID
     * @return 处理器
     */
    private Handler<Buffer> socks5AuthHandler(NetSocket clientSocket, Buffer msgId) {
        return buffer -> {
            if (buffer.length() < 3) {
                log.warn("认证消息长度不够:{}", buffer.length());
                clientSocket.close();
                return;
            }

            byte authVersion = buffer.getByte(0);
            if (authVersion != SOCKS5_AUTH_VERSION) {
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
                log.warn("认证版本错误:{}", authVersion);
                clientSocket.close();
                return;
            }

            // 用户名长度
            int usernameLength = buffer.getByte(1) & 0xFF;
            if (buffer.length() < 2 + usernameLength + 1) {
                clientSocket.close();
                log.warn("用户名长度不够:{}", buffer.length());
                return;
            }
            // 用户名
            String username = buffer.getString(2, 2 + usernameLength);

            // 密码长度
            int passwordPos = 2 + usernameLength;
            int passwordLength = buffer.getByte(passwordPos) & 0xFF;
            if (buffer.length() < passwordPos + 1 + passwordLength) {
                log.warn("密码长度不够:{}", buffer.length());
                clientSocket.close();
                return;
            }
            // 密码
            String password = buffer.getString(passwordPos + 1, passwordPos + 1 + passwordLength);
            // 验证用户名密码
            if (validateCredentials(username, password)) {
                log.info("用户名密码验证成功:{}", username);
                securityService.addAuthorizedHost(clientSocket.remoteAddress().host());
                // 成功后请求处理
                clientSocket.handler(socks5RequestHandler(clientSocket, msgId));
                // 发送成功响应
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_SUCCESS);
            } else {
                log.warn("用户名密码验证失败:{}", username);
                // 发送失败响应
                sendSocks5AuthReply(clientSocket, SOCKS5_AUTH_FAILURE);
                clientSocket.close();
            }
        };
    }

    /**
     * UDP数据处理，一个UDP端口中继大量不同目的地的数据报。
     * 适用于DNS查询、音视频流、游戏信令等UDP协议场景。
     *
     * @param clientSocket 代理客户端socket
     *                     处理UDP数据
     */
    private void handleUdpAssociate(NetSocket clientSocket) {
        try {
            // 分配UDP端口
            int udpPort = allocateUdpPort();
            // 创建UDP服务器
            DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setSendBufferSize(BUFFER_SIZE).setReceiveBufferSize(BUFFER_SIZE));
            socket.exceptionHandler(err ->
                    log.error("UDP连接异常: {}", err.getMessage(), err));
            // 处理UDP数据
            socket.handler(udpSocket -> {
                // 处理来自客户端的UDP数据
                handleUdpData(clientSocket, socket, udpSocket.data());
            });
            //clientSocket里获取当前服务器地址
            // 启动UDP服务器
            socket.listen(udpPort, ALL_HOST, res -> {
                if (res.succeeded()) {
                    // 添加TCP连接活跃状态维护
                    clientSocket.exceptionHandler(err -> {
                        log.error("TCP连接异常: {}", err.getMessage(), err);
                        socket.close(); // 同时关闭UDP socket
                    });
                    clientSocket.closeHandler(handler -> {
                        log.debug("socks5代理客户端[{}]连接关闭！", clientSocket.remoteAddress());
                        this.closeUDPSocket(clientSocket.hashCode());
                        socket.close();
                    });
                    // 发送成功响应，包含分配的UDP端口
                    sendSocks5UdpReply(clientSocket, SOCKS5_REPLY_SUCCEEDED, udpPort);
                    log.debug("UDP关联成功，分配端口: {}", udpPort);
                } else {
                    log.error("UDP服务器启动失败: {}", res.cause().getMessage());
                    sendSocks5Reply(clientSocket, SOCKS5_REPLY_GENERAL_FAILURE);
                    socket.close();
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
     * 关闭关联的UDP Socket
     *
     * @param tcpHashCode TCP socket hashCode
     */
    public void closeUDPSocket(int tcpHashCode) {
        UdpRequest.closeByTcpHashCode(tcpHashCode).forEach(udpHashCode -> {
            UdpRequest cachedRequest = (UdpRequest) this.getRequest(udpHashCode);
            if (cachedRequest != null) {
                this.removeCacheAndClose(udpHashCode);
                log.debug("用户TCP代理连接[{}]关闭，发送SOCKS5 UDP关闭连接消息到内网代理服务！", cachedRequest.getSocket().remoteAddress());
                serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + cachedRequest.getMsgId().length())
                        .appendByte(JRPMsgType.CLOSE.getCode())
                        .appendBuffer(cachedRequest.getMsgId()));
            }
        });
    }

    /**
     * 处理UDP数据转发
     *
     * @param clientSocket 客户端套接字
     * @param socket       服务端UDP监听对象
     * @param buffer       数据缓冲区
     */
    private void handleUdpData(NetSocket clientSocket, DatagramSocket socket, Buffer buffer) {
        /*
        +----+------+------+----------+----------+----------+
        |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
        +----+------+------+----------+----------+----------+
        | 2  |  1   |  1   | Variable |    2     | Variable |
        +----+------+------+----------+----------+----------+
        RSV：保留字段（固定为0）
        FRAG：分片标识（0表示完整数据包）
        ATYP：地址类型（0x01-IPv4，0x03-域名，0x04-IPv6）
        DST.ADDR：目标服务器地址
            当 ATYP = 0x01：4字节的IPv4地址（例如，192.0.2.1 表示为 C0 00 02 01）。
            当 ATYP = 0x03：1字节长度n + n字节的域名（例如，"example.com" 表示为 0x0B 'e' 'x' 'a' 'm' 'p' 'l' 'e' '.' 'c' 'o' 'm'）。
            当 ATYP = 0x04：16字节的IPv6地址。
        DST.PORT：目标服务器端口
        DATA：实际数据
         */
        // 实现UDP数据转发:解析SOCKS5 UDP请求格式并转发到目标地址
        //获取SOCKS5 UDP数据里的目标地址、目标端口、数据
        log.debug("收到UDP数据，长度: {}", buffer.length());
        try {
            // 检查最小长度
            if (buffer.length() < 4) {
                log.warn("UDP数据包长度不足");
                return;
            }
            // 跳过RSV(2字节)和FRAG(1字节)
            int offset = 3;
            // 获取ATYP地址类型
            byte addrType = buffer.getByte(offset++);
            String targetHost;
            int targetPort;
            byte[] portBytes;
            Buffer data;
            if (addrType == SOCKS5_ADDR_TYPE_IPV4) {
                // IPv4地址: 4字节
                if (buffer.length() < offset + 4 + 2) {
                    log.warn("IPv4 UDP数据包长度不足");
                    return;
                }
                StringBuilder ipBuilder = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    if (i > 0) ipBuilder.append(".");
                    ipBuilder.append(buffer.getByte(offset + i) & 0xFF);
                }
                targetHost = ipBuilder.toString();
                offset += 4;
                // 端口: 2字节大端序
                targetPort = ((buffer.getByte(offset) & 0xFF) << 8) | (buffer.getByte(offset + 1) & 0xFF);
                portBytes = buffer.getBytes(offset, offset + 2);
                offset += 2;
                // 剩余数据
                data = buffer.getBuffer(offset, buffer.length());
            } else if (addrType == SOCKS5_ADDR_TYPE_DOMAIN) {
                // 域名地址
                if (buffer.length() < offset + 1) {
                    log.warn("域名UDP数据包长度不足");
                    return;
                }
                // 域名长度
                int domainLength = buffer.getByte(offset++) & 0xFF;
                if (buffer.length() < offset + domainLength + 2) {
                    log.warn("域名UDP数据包长度不足");
                    return;
                }
                // 域名
                targetHost = buffer.getString(offset, offset + domainLength);
                offset += domainLength;
                // 端口: 2字节大端序
                targetPort = ((buffer.getByte(offset) & 0xFF) << 8) | (buffer.getByte(offset + 1) & 0xFF);
                portBytes = buffer.getBytes(offset, offset + 2);
                offset += 2;
                // 剩余数据
                data = buffer.getBuffer(offset, buffer.length());
            } else if (addrType == SOCKS5_ADDR_TYPE_IPV6) {
                // IPv6地址: 16字节
                if (buffer.length() < offset + 16 + 2) {
                    log.warn("IPv6 UDP数据包长度不足");
                    return;
                }
                // 简化处理，实际应该转换为标准IPv6格式
                byte[] ipv6byte = buffer.getBytes(offset, offset + 16);
                targetHost = bytesToIPv6Address(ipv6byte); // 需要实际转换

                offset += 16;
                // 端口: 2字节大端序
                targetPort = ((buffer.getByte(offset) & 0xFF) << 8) | (buffer.getByte(offset + 1) & 0xFF);
                portBytes = buffer.getBytes(offset, offset + 2);
                offset += 2;
                // 剩余数据
                data = buffer.getBuffer(offset, buffer.length());
            } else {
                log.warn("不支持的地址类型: {}", addrType);
                return;
            }
            // 打印解析结果
            log.debug("解析UDP数据: 目标地址={}, 目标端口={}, 数据长度={}", targetHost, targetPort, data.length());
            int requestId = InetSocketAddress.createUnresolved(targetHost, targetPort).hashCode();
            Buffer msgId = Buffer.buffer(MSG_BYTE_SIZE)
                    .appendBytes(remotePortByte)
                    .appendBytes(ByteBuffer.allocate(4).putInt(requestId).array());
            // TODO: 实际转发数据到目标地址
            // 通知内网代理建立连接
            Buffer target = Buffer.buffer(1 + targetHost.length() + 1 + portBytes.length)
                    .appendByte(ProxyProto.SOCK5_UDP.getProto())
                    .appendString(targetHost)
                    .appendByte(IP_PORT_SEPARATOR)
                    .appendBytes(portBytes);
            ProxyRequest request = this.getRequest(requestId);
            if (request == null) {
                request = ProxyRequest.createUdpRequest(msgId, clientSocket, socket, targetHost, null, portBytes);
                this.cacheRequest(requestId, request);
            }
            //转发信息到穿透客户端
            this.serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length() + target.length())
                    .appendByte(JRPMsgType.RECEIVE.getCode())
                    .appendBuffer(msgId)
                    .appendBuffer(target).appendBuffer(data));
        } catch (Exception e) {
            log.error("处理UDP数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 将16字节IPv6地址数据转换为标准IPv6地址字符串
     *
     * @param ipv6Bytes 16字节的IPv6地址数据
     * @return 标准IPv6地址字符串
     */
    private String bytesToIPv6Address(byte[] ipv6Bytes) {
        if (ipv6Bytes.length != 16) {
            throw new IllegalArgumentException("IPv6地址必须是16字节");
        }

        try {
            // 使用Java内置的InetAddress类处理IPv6地址转换
            java.net.InetAddress inetAddress = java.net.InetAddress.getByAddress(ipv6Bytes);
            if (inetAddress instanceof java.net.Inet6Address) {
                // getHostAddress()会自动格式化为标准IPv6字符串格式
                return inetAddress.getHostAddress();
            }
        } catch (java.net.UnknownHostException e) {
            // 这种情况理论上不会发生，因为我们提供了正确的字节数组长度
            log.warn("无法解析IPv6地址: {}", e.getMessage());
        }

        // 如果上面的方法失败，回退到手动构建（不推荐）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) {
                sb.append(":");
            }
            // 将两个字节组合成一个16位整数并转换为十六进制
            int word = ((ipv6Bytes[i] & 0xFF) << 8) | (ipv6Bytes[i + 1] & 0xFF);
            sb.append(Integer.toHexString(word));
        }
        return sb.toString();
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
        // 确保在写入完成后不关闭连接
        socket.write(reply).onFailure(err -> {
            log.error("发送UDP关联响应失败: {}", err.getMessage(), err);
            socket.close();
        });
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
        return username != null && username.equals(this.clientRegister.getUsername()) &&
                password != null && password.equals(this.clientRegister.getPassword());
    }

    @Override
    public void backData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer data) {
        ProxyRequest proxyRequest = this.getRequest(requestId);
        if (proxyRequest != null) {
            NetSocket clientNetSocket = proxyRequest.getSocket();
            ProxyProto proxyProto = proxyRequest.getProxyProto();
            String clientAddress = clientNetSocket.remoteAddress().toString();
            if (JRPMsgType.CLOSE == msgType) {
                log.debug("收到内网代理服务返回的关闭信息[{}]，关闭SOCKS5连接。", clientAddress);
                this.removeCacheAndClose(requestId);
            } else if (JRPMsgType.RESPONSE == msgType) {
                log.debug("收到内网代理服务返回数据并返回给代理客户端[{}]。", clientAddress);
                //SOCK5_TCP或SOCK4_TCP穿透客户端创建TCP连接成功后会返回空消息(realData.length()==0)
                if (data.length() == 0) {
                    //接收和转发来自用户端的数据
                    clientNetSocket.handler(dataHandler(msgId));
                    switch (proxyProto) {
                        case SOCK5_TCP:
                            // 发送socks5穿透隧道创建成功响应
                            log.debug("穿透客户端创建TCP连接成功，发送socks5 TCP隧道创建成功响应。");
                            sendSocks5Reply(clientNetSocket, SOCKS5_REPLY_SUCCEEDED)
                                    .onSuccess(done -> proxyRequest.setTunneled(true));
                            break;
                        case SOCK4_TCP:
                        case SOCK4A_TCP:
                            // 发送socks4穿透隧道创建成功响应
                            log.debug("穿透客户端创建TCP连接成功，发送socks4 TCP隧道创建成功响应。");
                            //返回socks4连接成功响应，组装sock4响应消息
                            /*
                            VN (1字节): 协议版本，固定为0x00
                            CD (1字节): 状态码，0x5A表示请求成功
                            DSTPORT (2字节): 目标端口（大端序）
                            DSTIP (4字节): 目标IP地址
                           */
                            sendSocks4Reply(clientNetSocket, SOCKS4_REPLY_SUCCEEDED, proxyRequest.getPortBytes(), proxyRequest.getDstIP()).onSuccess(done -> proxyRequest.setTunneled(true));
                            break;
                        case HTTP:
                        case HTTPS:
                            proxyRequest.setTunneled(true);
                            break;
                    }
                } else {
                    proxyRequest.setTunneled(true);
                    if (proxyProto == ProxyProto.SOCK5_UDP && proxyRequest instanceof UdpRequest) {
                        DatagramSocket socket = ((UdpRequest) proxyRequest).getUdpSocket();
                        socket.send(data, proxyRequest.getTargetPort(), proxyRequest.getTargetHost(), done -> {
                            if (done.succeeded()) {
                                log.debug("返回UDP数据成功。");
                            } else {
                                log.error("返回UDP数据失败。", done.cause());
                            }
                        });
                    } else {
                        clientNetSocket.write(data);
                        if (clientNetSocket.writeQueueFull()) {
                            clientNetSocket.pause();
                            clientNetSocket.drainHandler(done -> clientNetSocket.resume());
                        }
                    }

                }
            } else {
                log.warn("收到内网代理服务返回数据[{}]，消息类型[{}]不匹配！", clientAddress, msgType);
            }
        } else if (JRPMsgType.CLOSE == msgType) {
            log.warn("收到内网代理服务返回的关闭消息，代理客户端[{}]连接已经失效，不做处理！", requestId);
        } else {
            log.warn("收到内网代理服务返回消息，但是代理客户端[{}]连接已经失效，发送关闭连接消息到内网代理服务！", requestId);
            serverSocket.write(Buffer.buffer(JRPMsgType.TYPE_LEN + msgId.length())
                    .appendByte(JRPMsgType.CLOSE.getCode())
                    .appendBuffer(msgId));
        }
    }

    @Override
    public void stop() throws Exception {
        log.info("关闭端口[{}]下正向代理服务并清理缓存！", clientProxy.getRemote_port());
        server.close();
        //clientSocketMap.clear();
        super.stop();
    }
}