package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.RouteRule;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tcp消息处理器
 */
@Slf4j
public class TcpReverseProxyHandler extends AbstractProxyHandler {

    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();

    /**
     * TCP客户端单例（HTTP），用于复用连接
     */
    private final NetClient tcpClient;

    /**
     * TCP客户端单例（HTTPS），用于复用连接
     */
    private final NetClient httpsClient;

    public TcpReverseProxyHandler(Vertx vertx) {
        super(vertx);
        // 初始化TCP客户端单例
        NetClientOptions httpOptions = new NetClientOptions();
        httpOptions.setReceiveBufferSize(BUFFER_SIZE);
        httpOptions.setSendBufferSize(BUFFER_SIZE);
        httpOptions.setConnectTimeout(CONNECT_TIMEOUT);
        this.tcpClient = vertx.createNetClient(httpOptions);

        // 初始化HTTPS TCP客户端单例
        NetClientOptions httpsOptions = new NetClientOptions();
        httpsOptions.setReceiveBufferSize(BUFFER_SIZE);
        httpsOptions.setSendBufferSize(BUFFER_SIZE);
        httpsOptions.setConnectTimeout(CONNECT_TIMEOUT);
        httpsOptions.setSsl(true);
        httpsOptions.setTrustAll(true);
        this.httpsClient = vertx.createNetClient(httpsOptions);
    }

    @Override
    public void closeSocket(Integer clientId) {
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
    public void receiveMsgAndProxy(Consumer<Buffer> bufferConsumer, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        String proxyPass = clientProxy.getProxy_pass();
        int originPort = clientProxy.getPort();
        String originHost = clientProxy.getHost();
        boolean https = clientProxy.isHttps();
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            sendTcpData(clientProxy, data, netSocket);
        } else {
            synchronized (netSocketMap) {
                netSocket = netSocketMap.get(clientId);
                if (netSocket != null) {
                    sendTcpData(clientProxy, data, netSocket);
                } else {
                    log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, originHost, originPort);
                    CountDownLatch downLatch = new CountDownLatch(1);
                    // 根据是否HTTPS选择对应的TCP客户端
                    NetClient selectedClient = https ? httpsClient : tcpClient;
                    selectedClient.connect(originPort, originHost, asyncResult -> {
                        try {
                            if (asyncResult.succeeded()) {
                                NetSocket proxySocket = asyncResult.result();
                                proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
                                netSocketMap.put(clientId, proxySocket);
                                proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
                                proxySocket.closeHandler(ch -> {
                                    if (bufferConsumer != null && netSocketMap.remove(clientId) != null) {
                                        log.debug("客户端[{}]对应的内容请求关闭！", clientId);
                                        bufferConsumer.accept(closeBuffer(msgId));
                                    }
                                });
                                proxySocket.handler(response -> {
                                    if (bufferConsumer != null && netSocketMap.get(clientId) != null) {
                                        log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                                        bufferConsumer.accept(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                                    } else {
                                        log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                                    }
                                });
                                //转发返回消息到内网真实服务器
                                if (data.length() > 0) {
                                    sendTcpData(clientProxy, data, proxySocket);
                                }
                                log.info("内网代理连接到{}:{}成功！", originHost, originPort);
                            } else {
                                log.error("内网代理连接到{}:{}失败：{}！", originHost, originPort, asyncResult.cause().getMessage(), asyncResult.cause());
                            }
                        } catch (Exception e) {
                            log.error("初始化转发服务异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                            bufferConsumer.accept(closeBuffer(msgId));
                        } finally {
                            downLatch.countDown();
                        }
                    });
                    try {
                        downLatch.await();
                    } catch (InterruptedException e) {
                        log.error("转发服务连接处理异常：{}，发送关闭消息给服务端", e.getMessage(), e);
                        bufferConsumer.accept(closeBuffer(msgId));
                    }
                }
            }
        }
    }

    /**
     * 发送TCP数据
     *
     * @param clientProxy 代理配置信息
     * @param data        数据
     * @param netSocket   数据发送对象
     */
    private static void sendTcpData(ClientProxy clientProxy, Buffer data, NetSocket netSocket) {
        String dataStr = data.toString();
        if (isHttpRequest(dataStr)) {
            String host = clientProxy.getHost();
            Integer port = clientProxy.getPort();
            // 剥离location路径前缀（仅当传入的是RouteRule时）
            String location = null;
            if (clientProxy instanceof RouteRule) {
                RouteRule routeRule = (RouteRule) clientProxy;
                location = routeRule.getLocation();
            }
            String proxyPass = clientProxy.isHttps() ? "https://" + host + ":" + port : "http://" + host + ":" + port;
            // 获取proxy_pass中的路径前缀
            String proxyPath = clientProxy.getPath();
            if (proxyPath == null) {
                proxyPath = "";
            }
            if (location != null && !location.isEmpty() && !"/".equals(location)) {
                dataStr = rewriteRequestPath(dataStr, location, proxyPath);
            } else if (!proxyPath.isEmpty()) {
                // 无location但有proxy_pass路径，直接加上路径前缀
                dataStr = prependPath(dataStr, proxyPath);
            }
            //替换Host和Referer值，避免被内网服务器拦截
            //替换Host值
            dataStr = dataStr.replaceFirst("(?m)^Host: .*", "Host: " + host + ":" + port);
            //替换Origin值
            dataStr = dataStr.replaceFirst("(?m)^Origin: .*", "Origin: " + proxyPass);
            //替换Referer值
            dataStr = dataStr.replaceFirst("(?m)^Referer:\\s*https?://[^\\s/]+(.*)", "Referer: " + proxyPass + "$1");
            // 替换 Referer 值，保持协议一致性
            if (dataStr.contains("Referer:")) {
                //获取Referer值
                int index = dataStr.indexOf("Referer: ");
                int lineEnd = dataStr.indexOf("\r\n", index);
                if (lineEnd != -1) {
                    String referer = dataStr.substring(index + "Referer: ".length(), lineEnd);
                    int uriIndex = referer.indexOf("/", 7);
                    if (uriIndex != -1) {
                        dataStr = dataStr.replace(referer, clientProxy.getProxy_pass() + referer.substring(uriIndex));
                    } else {
                        dataStr = dataStr.replace(referer, clientProxy.getProxy_pass());
                    }
                }
            }
            netSocket.write(Buffer.buffer(dataStr));
        } else {
            netSocket.write(data);
        }
        if (netSocket.writeQueueFull()) {
            netSocket.pause();
            netSocket.drainHandler((done) -> netSocket.resume());
        }
    }

    /**
     * 判断数据是否为HTTP请求（以HTTP方法开头）
     */
    private static boolean isHttpRequest(String data) {
        return data.startsWith("GET ") || data.startsWith("POST ")
                || data.startsWith("PUT ") || data.startsWith("DELETE ")
                || data.startsWith("HEAD ") || data.startsWith("OPTIONS ")
                || data.startsWith("PATCH ") || data.startsWith("TRACE ")
                || data.startsWith("CONNECT ");
    }

    /**
     * 重写请求路径：剥离location前缀并加上proxy_pass路径前缀。
     * 类似nginx的proxy_pass行为：
     * location=/api, proxy_pass路径=/backend: /api/users -> /backend/users
     *
     * @param dataStr   请求数据
     * @param location  location路径前缀
     * @param proxyPath proxy_pass中的路径前缀
     * @return 重写后的请求数据
     */
    private static String rewriteRequestPath(String dataStr, String location, String proxyPath) {
        // 确保location以/开头
        if (!location.startsWith("/")) {
            location = "/" + location;
        }
        // 移除location末尾的斜杠（避免//users的情况）
        if (location.endsWith("/") && location.length() > 1) {
            location = location.substring(0, location.length() - 1);
        }
        int lineEnd = dataStr.indexOf("\r\n");
        if (lineEnd == -1) {
            return dataStr;
        }
        String requestLine = dataStr.substring(0, lineEnd);
        // 匹配请求行中的路径：METHOD /path HTTP/x.x
        Matcher matcher = Pattern.compile("^(\\S+\\s+)(" + Pattern.quote(location) + ")(/.*)?(\\s+.*)$").matcher(requestLine);
        if (matcher.matches()) {
            String method = matcher.group(1);
            String remaining = matcher.group(3);
            String httpVersion = matcher.group(4);
            // 拼接proxy_pass路径和剩余路径
            String newPath;
            if (remaining != null && !remaining.isEmpty()) {
                newPath = proxyPath + remaining;
            } else {
                newPath = proxyPath.isEmpty() ? "/" : proxyPath;
            }
            String newRequestLine = method + newPath + httpVersion;
            dataStr = newRequestLine + dataStr.substring(lineEnd);
        }
        return dataStr;
    }

    /**
     * 在请求路径前加上proxy_pass路径前缀。
     * 例如：proxyPath=/app, GET /users -> GET /app/users
     *
     * @param dataStr   请求数据
     * @param proxyPath proxy_pass中的路径前缀
     * @return 重写后的请求数据
     */
    private static String prependPath(String dataStr, String proxyPath) {
        int lineEnd = dataStr.indexOf("\r\n");
        if (lineEnd == -1) {
            return dataStr;
        }
        String requestLine = dataStr.substring(0, lineEnd);
        Matcher matcher = Pattern.compile("^(\\S+\\s+)(/.*)(\\s+.*)$").matcher(requestLine);
        if (matcher.matches()) {
            String method = matcher.group(1);
            String path = matcher.group(2);
            String httpVersion = matcher.group(3);
            String newPath = proxyPath + path;
            String newRequestLine = method + newPath + httpVersion;
            dataStr = newRequestLine + dataStr.substring(lineEnd);
        }
        return dataStr;
    }

    @Override
    public void close() throws IOException {
        if (!netSocketMap.isEmpty()) {
            log.info("停止TCP转发服务");
            netSocketMap.values().forEach(NetSocket::close);
            netSocketMap.clear();
        }
        // 关闭TCP客户端
        if (tcpClient != null) {
            tcpClient.close();
        }
        if (httpsClient != null) {
            httpsClient.close();
        }
    }
}
