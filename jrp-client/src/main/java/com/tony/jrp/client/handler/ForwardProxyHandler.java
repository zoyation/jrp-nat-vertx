package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.enums.SocksProxyProto;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * sockets消息处理器
 */
@Slf4j
public class ForwardProxyHandler extends AbstractProxyHandler {
    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();
    /**
     * 代理转发目标信息缓存
     */
    private final Map<Integer, TargetInfo> targetMap = new ConcurrentHashMap<>();

    /**
     * 代理转发目标信息缓存
     */
    @Data
    private static class TargetInfo {
        /**
         * 代理转发地址，格式：ip:port
         */
        private String proxyPass;
        /**
         * 原始请求host
         */
        private String host;
        /**
         * 原始请求host
         */
        private int port;

        /**
         * @param host 请求 host
         * @param port 请求端口
         * @return
         */
        public static TargetInfo of(String host, int port) {
            TargetInfo targetInfo = new TargetInfo();
            targetInfo.setProxyPass(host + ":" + port);
            targetInfo.setHost(host);
            targetInfo.setPort(port);
            return targetInfo;
        }
    }

    public ForwardProxyHandler(Vertx vertx) {
        super(vertx);
    }

    @Override
    public void closeSocket(Integer clientId) {
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            log.debug("收到断开连接请求，关闭SOCKS TCP连接[{}]。", clientId);
            netSocketMap.remove(clientId);
            targetMap.remove(clientId);
            netSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        NetSocket netSocket = netSocketMap.get(clientId);
        TargetInfo targetInfo = targetMap.get(clientId);
        if (netSocket != null && targetInfo != null) {
            sendTcpData(data, netSocket);
        } else {
            synchronized (netSocketMap) {
                netSocket = netSocketMap.get(clientId);
                targetInfo = targetMap.get(clientId);
                if (netSocket != null && targetInfo != null) {
                    sendTcpData(data, netSocket);
                } else {
                    //首次创建TCP连接，格式：协议类型SocksProxyProto里枚举值（1字节）HOST(域名或IP):PORT（2字节）
                    SocksProxyProto protocol = SocksProxyProto.getByProto(data.getByte(0));
                    StringBuilder targetHost = new StringBuilder();
                    for (int i = 1; i < data.length(); i++) {
                        if (data.getByte(i) == ':') {
                            break;
                        }
                        targetHost.append((char) data.getByte(i));
                    }
                    int targetPort = data.getBuffer(1 + targetHost.length() + 1, 1 + targetHost.length() + 1 + 2).getUnsignedShort(0);
                    Buffer sendData = (protocol == SocksProxyProto.HTTP || protocol == SocksProxyProto.HTTPS) ? data.getBuffer(1 + targetHost.length() + 1 + 2, data.length()) : Buffer.buffer();
                    final SocketAddress socketAddress = SocketAddress.inetSocketAddress(targetPort, targetHost.toString());
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, targetHost, targetPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                    NetClientOptions clientOptions = new NetClientOptions();
                    clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                    clientOptions.setSendBufferSize(BUFFER_SIZE);
                    clientOptions.setTrustAll(true);
                    clientOptions.setConnectTimeout(CONNECT_TIMEOUT);
                    NetClient netClient = vertx.createNetClient(clientOptions);
                    netClient.connect(socketAddress, asyncResult -> {
                        try {
                            if (asyncResult.succeeded()) {
                                NetSocket proxySocket = asyncResult.result();
                                proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                netSocketMap.put(clientId, proxySocket);
                                targetMap.put(clientId, TargetInfo.of(targetHost.toString(), targetPort));
                                proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                proxySocket.closeHandler(ch -> {
                                    if (webSocket != null && netSocketMap.remove(clientId) != null) {
                                        targetMap.remove(clientId);
                                        log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                        webSocket.write(closeBuffer(msgId));
                                    }
                                });
                                // 返回给服务端代表连接成功
                                if (webSocket != null) {
                                    proxySocket.handler(response -> {
                                        if (netSocketMap.get(clientId) != null) {
                                            log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                            webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                        } else {
                                            log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                        }
                                    });
                                    log.info("内网代理连接到{}:{}成功！", socketAddress.host(), socketAddress.port());
                                    if (sendData.length() > 0) {
                                        StringTokenizer tokenizer = new StringTokenizer(sendData.toString(), "\r\n");
                                        boolean https;
                                        if (tokenizer.hasMoreTokens()) {
                                            //第一行是请求行，正向代理转发过来的格式为：CONNECT http://192.168.1.11:88/index.html HTTP/1.1\r\n
                                            //或者 GET http://192.168.1.11:88/index.html HTTP/1.1\r\n
                                            String firstLine = tokenizer.nextToken();
                                            String[] request = firstLine.split(" ");
                                            if (request.length == 3) {
                                                //http://192.168.1.11:88/index.html
                                                String method = request[0];
                                                String url = request[1];
                                                https = method.equals("CONNECT");
                                                URL absoluteUrl;
                                                try {
                                                    absoluteUrl = new URL(https ? ("https://" + url) : url);
                                                } catch (MalformedURLException e) {
                                                    log.error("URL解析异常:{}！", firstLine);
                                                    throw new RuntimeException(e);
                                                }
                                                String uri;
                                                if (url.startsWith("connection: upgrade")) {
                                                    uri = url;
                                                } else {
                                                    uri = absoluteUrl.getFile();
                                                }
                                                //第一行替换“http://192.168.1.11:88/index.html”为“/index.html”
                                                Buffer requestFirestLine = Buffer.buffer(firstLine.replace(url, uri));
                                                Buffer otherBuffer = sendData.getBuffer(firstLine.length(), sendData.length());
                                                Buffer receiveData = Buffer.buffer(requestFirestLine.length() + otherBuffer.length()).appendBuffer(requestFirestLine).appendBuffer(otherBuffer);
                                                sendTcpData(receiveData, proxySocket);
                                            } else {
                                                log.warn("请求消息格式不正确：{}", firstLine);
                                            }
                                        } else {
                                            throw new RuntimeException("无法解析请求！");
                                        }
                                    } else {
                                        //非http请求，返回给代理服务端代表连接成功
                                        webSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId));
                                    }
                                }
                            } else {
                                log.error("内网代理连接到{}:{}失败：{}！", socketAddress.host(), socketAddress.port(), asyncResult.cause().getMessage(), asyncResult.cause());
                                webSocket.write(closeBuffer(msgId));
                            }
                        } catch (Exception e) {
                            log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                            webSocket.write(closeBuffer(msgId));
                        } finally {
                            downLatch.countDown();
                        }
                    });
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

    /**
     * 发送TCP数据
     *
     * @param data      数据
     * @param netSocket 数据发送对象
     */
    private static void sendTcpData(Buffer data, NetSocket netSocket) {
        netSocket.write(data);
        if (netSocket.writeQueueFull()) {
            netSocket.pause();
            netSocket.drainHandler((done) -> netSocket.resume());
        }
    }

    @Override
    public void close() throws IOException {
        if (!netSocketMap.isEmpty()) {
            log.info("停止TCP转发服务");
            netSocketMap.values().forEach(NetSocket::close);
            netSocketMap.clear();
            targetMap.clear();
        }
    }
}
