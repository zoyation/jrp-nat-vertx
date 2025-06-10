package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.ServiceType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 服务器转发代理主控类
 */
@Slf4j
public class ClientReverseProxyVerticle extends AbstractVerticle {
    /**
     * ”ip:端口“地址总长度数值对应字符串长度。
     */
    public static final int CLIENT_IP_PORT_LEN = 2;
    public static final int IDLE_TIMEOUT = 10;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * vertx实例
     */
    private final Vertx vertx;
    /**
     * 安全认证控制类
     */
    private final SecurityService securityService;
    /**
     * 客户端注册信息
     */
    @Getter
    private final ClientRegister clientRegister;
    /**
     * 持有和内网代理服务器的连接，收到客户端请求消息后，通知内网代理服务器
     */
    private final ServerWebSocket serverSocket;

    /**
     * 用户tcp请求客户端socket
     */
    private final Map<String, NetSocket> clientTcpSocketMap = new ConcurrentHashMap<>();
    /**
     * 所有代理Verticle
     */
    private final Map<Integer, AbstractVerticle> proxyVerticleMap = new ConcurrentHashMap<>();

    public ClientReverseProxyVerticle(ClientRegister clientRegister, ServerWebSocket serverSocket, Vertx vertx, SecurityService securityService) {
        this.clientRegister = clientRegister;
        this.serverSocket = serverSocket;
        this.vertx = vertx;
        this.securityService = securityService;
    }

    @Override
    public void start() throws Exception {
        serverSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
        /* 重新设置socket的handler，处理返回消息 */
        serverSocket.handler(data -> {
            String msgId, clientAddress;
            Buffer realData;
            //消息前缀为：消息标志符，后面是消息id：即代理端口位数（一位整数1024到49151，4或者5）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
            //获取代理端口字符串长度（代理到外网的穿透访问端口，一位整数，比如1024则长度为4,49151则长度为5）
            int portLen = Integer.parseInt(data.getString(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + 1));
            //请求唯一标识字符串长度（两位整数，不会超过100，比如110.242.69.21:49151对应长度值为19个字符‌，‌IPv6字符串的标准长度为39个字符‌，包括冒号（:）和十六进制数字‌）
            int clientStrLen = Integer.parseInt(data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN).toString());
            clientAddress = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
            msgId = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
            realData = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen, data.length());
            NetSocket clientNetSocket = clientTcpSocketMap.get(clientAddress);
            boolean closeMsg = realData.toString().endsWith(JRPMsgType.CLOSE.getCode());
            if (clientNetSocket != null) {
                if (closeMsg) {
                    log.debug("收到内网代理服务返回的关闭信息[{}]，关闭TCP连接。", clientAddress);
                    clientTcpSocketMap.remove(clientAddress);
                    clientNetSocket.close();
                } else {
                    log.debug("收到内网代理服务返回的TCP信息并返回给客户端[{}]。", clientAddress);
                    clientNetSocket.write(realData);
                    if (clientNetSocket.writeQueueFull()) {
                        clientNetSocket.pause();
                        clientNetSocket.drainHandler(done -> {
                            clientNetSocket.resume();
                        });
                    }
                }
            } else if (closeMsg) {
                log.warn("收到内网代理服务返回的关闭消息，客户端[{}]连接已经失效，不做处理！", clientAddress);
            } else {
                log.warn("收到内网代理服务返回消息，但是客户端[{}]连接已经失效，发送关闭连接消息到内网代理服务！", clientAddress);
                serverSocket.write(Buffer.buffer(msgId).appendBuffer(Buffer.buffer(JRPMsgType.CLOSE.getCode())));
            }
        });
        //代理服务里监听指定端口，用于接收转发用户请求到内网服务，并返回到请求端
        for (ClientProxy clientProxy : clientRegister.getProxies()) {
            Integer remotePort = clientProxy.getRemote_port();
            synchronized (ClientReverseProxyVerticle.this) {
                if (proxyVerticleMap.get(remotePort) != null) {
                    log.warn("已存在外网端口为[{}]的代理信息，不做处理！", remotePort);
                    continue;
                }
            }
            switch (clientProxy.getType()) {
                case HTTPS:
                case HTTP:
                case TCP: {
                    AbstractVerticle tcpVerticle = new AbstractVerticle() {
                        NetServer server;

                        @Override
                        public void start() {
                            server = initTcpProxy(clientProxy);
                        }

                        @Override
                        public void stop() {
                            server.close();
                        }
                    };
                    Future<String> tcpFuture = vertx.deployVerticle(tcpVerticle);
                    tcpFuture.onSuccess(id -> proxyVerticleMap.put(remotePort, tcpVerticle)).onFailure(Throwable::printStackTrace);
                    break;
                }
                case UDP:
                case SOCKS4:
                case SOCKS5:
                default:
                    throw new Exception("不支持代理类型：" + clientProxy.getType().name() + "！");
            }
        }
    }

    private NetServer initTcpProxy(ClientProxy clientProxy) {
        Integer remotePort = clientProxy.getRemote_port();
        // 创建TCP服务器
        NetServerOptions options = new NetServerOptions();
        options.setIdleTimeout(IDLE_TIMEOUT);
//        options.setReceiveBufferSize(RECEIVE_BUFFER_SIZE);
//        options.setSendBufferSize(BUFFER_SIZE);
        NetServer server = vertx.createNetServer(options);
        // 处理连接请求
        server.connectHandler(clientSocket -> {
            clientSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            SocketAddress socketAddress = clientSocket.remoteAddress();
            log.debug("[{}] 创建连接!", socketAddress.toString());
            String clientAddress = socketAddress.toString();
            //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
            String msgId = remotePort.toString().length() + remotePort.toString() + clientAddress.length() + clientAddress;
            //log.info("客户端[{}]连接:{}", clientAddress, remotePort);
            String host = socketAddress.host();
            boolean httpFlag = clientProxy.getType() == ServiceType.HTTP;
            //延迟获取是否为http请求，http类型请求创建连接后会马上收到数据，‌SSH协议请求不会收到数据，需要通知被代理客户端连接后返回数据。
            AtomicBoolean receiveDataFlag = new AtomicBoolean(false);
            Handler<Buffer> dataHandler = data -> {
                receiveDataFlag.set(true);
                boolean authorized = securityService.authorized(host);
                //未授权非HTTP请求都屏蔽
                if (!authorized && !securityService.isHTTPRequest(data)) {
                    log.warn("关闭非HTTP类型未授权请求[{}]！", clientAddress);
                    clientSocket.close();
                } else if (authorized) {
                    if (!httpFlag && securityService.isHTTPRequest(data)) {
                        log.warn("[{}]-[{}]类型服务，授权通过，不支持HTTP访问:{}！", clientAddress, clientProxy.getType().name(), remotePort);
                        String warnResponse = securityService.getHttpWarnResponse();
                        clientTcpSocketMap.remove(clientAddress);
                        clientSocket.end(Buffer.buffer(warnResponse));
                    } else {
                        log.debug("客户端[{}-[{}]类型服务访问权限验证通过，转发消息!", clientAddress, clientProxy.getType().name());
                        clientTcpSocketMap.put(clientAddress, clientSocket);
                        serverSocket.write(Buffer.buffer(msgId).appendBuffer(data));
                        if (serverSocket.writeQueueFull()) {
                            serverSocket.pause();
                            clientSocket.pause();
                            serverSocket.drainHandler(done -> {
                                serverSocket.resume();
                                clientSocket.resume();
                            });
                        }
                    }
                } else {
                    //首次访问或者首次验证都需要走HTTP接口
                    if (securityService.isHTTPRequest(data)) {
                        //尝试HTTP用户名密码信息验证
                        if (securityService.authorizeHttp(host, data)) {
                            if (httpFlag) {
                                log.debug("HTTP客户端[{}]请求验证通过，开始转发消息!", clientAddress);
                                clientTcpSocketMap.put(clientAddress, clientSocket);
                                serverSocket.write(Buffer.buffer(msgId).appendBuffer(data));
                            } else {
                                log.debug("非HTTP客户端[{}]请求验证通过，返回成功提示信息!", clientAddress);
                                clientSocket.end(Buffer.buffer(securityService.getNotHttpSuccessResponse()));
                            }
                        } else if (securityService.canToNetSocket(data.toString())) {
                            log.warn("[{}]websocket或CONNECT未授权访问:{}，直接关闭！", clientAddress, remotePort);
                            clientTcpSocketMap.remove(clientAddress);
                            clientSocket.close();
                        } else {
                            // 假设我们在处理HTTP请求
                            log.warn("[{}]HTTP未授权访问:{}，浏览器弹窗输入认证信息！", clientAddress, remotePort);
                            // 将重定向响应写入socket
                            clientSocket.end(Buffer.buffer(securityService.getAuthenticateResponse(host)));
                        }
                    } else {
                        clientTcpSocketMap.remove(clientAddress);
                        clientSocket.close();
                        log.warn("[{}]非法访问:{}，直接关闭！", host, remotePort);
                        //return false;
                    }
                }
            };
            Handler<Void> closeHandler = voidHandler -> {
                log.debug("客户端[{}]连接关闭！", clientAddress);
                if (clientTcpSocketMap.containsKey(clientAddress)) {
                    clientTcpSocketMap.remove(clientAddress);
                    //log.warn("客户端连接关闭，丢弃收到的内网代理服务器返回信息，并通知内网服务器断开连接[{}]！", clientAddress);
                    //代理端口位数（一位整数）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
                    log.debug("客户端连接关闭，发送关闭连接消息到被代理端[{}]！", clientAddress);
                    serverSocket.write(Buffer.buffer(msgId).appendBuffer(Buffer.buffer(JRPMsgType.CLOSE.getCode())));
                }
            };
            clientSocket.handler(dataHandler);
            clientSocket.closeHandler(closeHandler);
            clientSocket.exceptionHandler(err -> log.error("客户端[{}]异常：{}！", clientAddress, err.getMessage(), err));
            boolean authorized = securityService.authorized(host);
            //授权通过，如果是非HTTP、SSH类TCP代理（这儿不能通过NetSocket判断创建连接是不是HTTP请求），才通知客户端初始化。
            //http类型请求创建连接后会马上收到数据；SSH协议请求不会收到数据，需要通知被代理客户端连接后返回数据。延迟判断httpRequestFlag如果为false，判断是ssh等协议连接，通知被代理端初始化。
            vertx.setTimer(200, (id) -> {
                if (authorized && !httpFlag && !receiveDataFlag.get()) {
                    //关闭历史未移除连接
                    if (clientTcpSocketMap.containsKey(clientAddress)) {
                        clientTcpSocketMap.remove(clientAddress).close();
                    }
                    log.debug("发送来自客户端[{}]的非HTTP初始化请求!", clientAddress);
                    clientTcpSocketMap.put(clientAddress, clientSocket);
                    serverSocket.write(Buffer.buffer(msgId));
                }
                //未授权不是http请求，是非法请求
                if (!authorized && !httpFlag && !receiveDataFlag.get()) {
                    log.warn("来自客户端[{}]的非HTTP初始化请求，未通过认证，直接关闭!", clientAddress);
                    clientSocket.close();
                }
            });
        }).exceptionHandler(err -> log.error("端口[{}]TCP内网穿透代理服务异常：{}", remotePort, err.getMessage(), err));
        return server.listen(remotePort, res -> {
            // 监听端口
            if (res.succeeded()) {
                log.info("[{}]内网穿透代理服务启动成功，代理端口：{}。", clientProxy.getType().name(), remotePort);
            } else {
                log.error("端口[{}]-[{}]内网穿透代理服务启动失败：{}", remotePort, clientProxy.getType().name(), res.cause().getMessage(), res.cause());
            }
        });
    }

    @Override
    public void stop() {
        String ports = proxyVerticleMap.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
        log.info("清理端口[{}]下所有代理缓存！", ports);
        proxyVerticleMap.values().forEach((v) -> vertx.undeploy(v.deploymentID()));
        clientTcpSocketMap.values().forEach(NetSocket::close);
        clientTcpSocketMap.clear();
        proxyVerticleMap.clear();
    }
}
