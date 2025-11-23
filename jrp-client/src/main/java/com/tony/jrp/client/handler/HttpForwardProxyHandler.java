package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * http正向代理消息处理器
 */
@Slf4j
public class HttpForwardProxyHandler extends AbstractProxyHandler {
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * 代理请求对象缓存
     */
    private final Map<String, NetSocket> netSocketMap = new ConcurrentHashMap<>();

    public HttpForwardProxyHandler(Vertx vertx) {
        super(vertx);
    }

    @Override
    public void closeSocket(String clientId) {
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            log.debug("收到断开连接请求，关闭TCP连接[{}]。", clientId);
            netSocketMap.remove(clientId);
            netSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(WebSocket webSocket, String msgId, String clientId, String proxyPass, Buffer data) {
        /*https:
        CONNECT s.url.cn:443 HTTP/1.1\r\n
        Host: s.url.cn:443\r\n
        Proxy-Connection: keep-alive\r\n
        User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) TDAppDesktop/3.8.10 Chrome/114.0.5735.289 Electron/25.8.1 Safari/537.36 TDAppDesktop/3.8.11 TDAppDesktopChannel/30001 _tdocFlag/2\r\n\r\n
        */
        /*
        http:
        GET functional.events.data.microsoft.com:443 HTTP/1.1
        Host: functional.events.data.microsoft.com:443
        Proxy-Connection: keep-alive
        */
        if (data.toString().contains("connection: upgrade")) {
            log.debug("connection: upgrade:{}", data);
        }
        StringTokenizer tokenizer = new StringTokenizer(data.toString(), "\r\n");
        SocketAddress socketAddress;
        int originPort;
        String originHost;
        boolean https;
        if (tokenizer.hasMoreTokens()) {
            //第一行是请求行，正向代理转发过来的格式为：CONNECT http://192.168.1.11:88/index.html HTTP/1.1\r\n
            String firstLine = tokenizer.nextToken();
            String[] request = firstLine.split(" ");
            if (request.length == 3) {
                //http://192.168.1.11:88/index.html
                String method = request[0];
                String url = request[1];
                https = method.equals("CONNECT");
                URL absoluteUrl = null;
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
                Buffer receiveData = Buffer.buffer(firstLine.replace(url, uri)).appendBuffer(data.getBuffer(firstLine.length(), data.length()));
                originPort = absoluteUrl.getPort();
                if (originPort == -1) {
                    originPort = https ? 443 : 80;
                }
                originHost = absoluteUrl.getHost();
                socketAddress = SocketAddress.inetSocketAddress(originPort, absoluteUrl.getHost());
                NetSocket netSocket = netSocketMap.get(clientId);
                if (netSocket != null) {
                    //buffer第一个字符为消息标志符，后面是客户端远程ID(ip+端口)长度2位+远程ID
                    sendTcpData(socketAddress.host(), proxyPass, receiveData, netSocket);
                } else {
                    synchronized (netSocketMap) {
                        netSocket = netSocketMap.get(clientId);
                        if (netSocket != null) {
                            sendTcpData(socketAddress.host(), proxyPass, receiveData, netSocket);
                        } else {
                            log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, originHost, originPort);
                            CountDownLatch downLatch = new CountDownLatch(1);
                            // 创建一个TCP客户端，代理转发请求消息到内网并原路返回
                            NetClientOptions clientOptions = new NetClientOptions();
                            clientOptions.setReceiveBufferSize(BUFFER_SIZE);
                            clientOptions.setSendBufferSize(BUFFER_SIZE);
                            if (https) {
                                clientOptions.setSsl(true);
                                clientOptions.setTrustAll(true);
                            }
                            NetClient netClient = vertx.createNetClient(clientOptions);
                            netClient.connect(socketAddress, asyncResult -> {
                                try {
                                    if (asyncResult.succeeded()) {
                                        NetSocket proxySocket = asyncResult.result();
                                        proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                        netSocketMap.put(clientId, proxySocket);
                                        proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                        proxySocket.closeHandler(ch -> {
                                            if (webSocket != null && netSocketMap.remove(clientId) != null) {
                                                log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                                webSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendByte(JRPMsgType.CLOSE.getCode()));
                                            }
                                        });
                                        proxySocket.handler(response -> {
                                            if (webSocket != null && netSocketMap.get(clientId) != null) {
                                                log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                                //消息标志符+客户端远程ID(ip+端口)长度2位+远程ID
                                                //Integer remotePort = proxy.getRemote_port();
                                                webSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendBuffer(response));
                                            } else {
                                                log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                            }
                                        });
                                        //转发返回消息到内网真实服务器
                                        if (receiveData.length() > 0) {
                                            sendTcpData(originHost, proxyPass, receiveData, proxySocket);
                                        }
                                        log.info("内网代理连接到{}:{}成功！", originHost, socketAddress.port());
                                    } else {
                                        log.error("内网代理连接到{}:{}失败：{}！", originHost, socketAddress.port(), asyncResult.cause().getMessage(), asyncResult.cause());
                                    }
                                } catch (Exception e) {
                                    log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                    webSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendByte(JRPMsgType.CLOSE.getCode()));
                                } finally {
                                    downLatch.countDown();
                                }
                            });
                            try {
                                downLatch.await();
                            } catch (InterruptedException e) {
                                log.error("转发服务连接处理异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                                webSocket.write(Buffer.buffer(JRPMsgType.RESPONSE.getCode() + msgId).appendByte(JRPMsgType.CLOSE.getCode()));
                            }
                        }
                    }
                }
            }
        } else {
            throw new RuntimeException("无法解析请求！");
        }
    }


    /**
     * 发送TCP数据
     *
     * @param originHost 原始服务主机
     * @param proxyPass  代理服务地址
     * @param data       数据
     * @param netSocket  数据发送对象
     */
    private static void sendTcpData(String originHost, String proxyPass, Buffer data, NetSocket netSocket) {
        if (data.toString().contains("Host:")) {
            //替换Host和Referer值，避免被内网服务器拦截
            netSocket.write(Buffer.buffer(data.toString().replaceAll("Host: .*", "Host: " + originHost).replaceAll("Referer:.*", "referer: " + proxyPass)));
        } else {
            netSocket.write(data);
        }
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
        }
    }
}
