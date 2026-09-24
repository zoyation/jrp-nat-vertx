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
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tcp消息处理器
 */
@Slf4j
public class TcpReverseProxyHandler extends AbstractProxyHandler {

    /**
     * HTTP方法最大长度，判断是否为HTTP请求时只需要看开头这几个字节
     */
    private static final int HTTP_METHOD_MAX_LEN = 8;

    /**
     * 代理请求对象缓存
     */
    private final Map<Integer, NetSocket> netSocketMap = new ConcurrentHashMap<>();
    /**
     * 内网连接建立完成前暂存的请求数据，key：clientId。
     * 连接建立是异步的，这期间到达的数据必须排队，否则会丢数据或者同一个clientId建立多条连接。
     */
    private final Map<Integer, Deque<Buffer>> pendingDataMap = new ConcurrentHashMap<>();
    /**
     * 正在建立内网连接的clientId，避免首包之后的报文重复发起连接
     */
    private final Set<Integer> connectingSet = ConcurrentHashMap.newKeySet();
    /**
     * 连接建立期间收到关闭消息的clientId，连接成功后需要立即关闭，避免泄漏内网连接
     */
    private final Set<Integer> canceledSet = ConcurrentHashMap.newKeySet();

    /**
     * TCP客户端单例（HTTP），用于复用连接
     */
    private final NetClient tcpClient;

    /**
     * TCP客户端单例（HTTPS），用于复用连接
     */
    private final NetClient httpsClient;

    public TcpReverseProxyHandler(Vertx vertx, TunnelFlow flow) {
        super(vertx, flow);
        // 初始化TCP客户端单例
        NetClientOptions httpOptions = new NetClientOptions();
//        httpOptions.setReceiveBufferSize(BUFFER_SIZE);
//        httpOptions.setSendBufferSize(BUFFER_SIZE);
        httpOptions.setConnectTimeout(CONNECT_TIMEOUT);
        this.tcpClient = vertx.createNetClient(httpOptions);

        // 初始化HTTPS TCP客户端单例
        NetClientOptions httpsOptions = new NetClientOptions();
//        httpsOptions.setReceiveBufferSize(BUFFER_SIZE);
//        httpsOptions.setSendBufferSize(BUFFER_SIZE);
        httpsOptions.setConnectTimeout(CONNECT_TIMEOUT);
        httpsOptions.setSsl(true);
        httpsOptions.setTrustAll(true);
        this.httpsClient = vertx.createNetClient(httpsOptions);
    }

    @Override
    protected boolean blocking() {
        //本处理器已全异步，直接在event loop执行，避免executeBlocking的有序队列成为吞吐瓶颈
        return false;
    }

    @Override
    public void closeSocket(Integer clientId) {
        NetSocket netSocket = netSocketMap.remove(clientId);
        pendingDataMap.remove(clientId);
        if (connectingSet.remove(clientId)) {
            //连接还没建立就要求关闭，标记后由连接回调负责关闭，避免泄漏内网连接
            canceledSet.add(clientId);
        }
        if (netSocket != null) {
            log.info("收到断开连接请求，关闭TCP连接[{}]。", clientId);
            this.flow.removeUpstream(netSocket);
            this.flow.resumeTunnel(clientId);
            netSocket.close();
        } else {
            log.warn("收到断开连接请求，未找到连接[{}]对应netSocket。", clientId);
        }
    }

    @Override
    public void receiveMsgAndProxy(Consumer<Buffer> bufferConsumer, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data) {
        String originHost = clientProxy.getHost();
        int originPort = clientProxy.getPort();
        boolean https = clientProxy.isHttps();
        NetSocket netSocket = netSocketMap.get(clientId);
        if (netSocket != null) {
            sendTcpData(clientProxy, data, netSocket, clientId);
            return;
        }
        // 连接建立中：先排队，等连接成功后按序发送，不能重复发起连接
        if (connectingSet.contains(clientId)) {
            if (data.length() > 0) {
                pendingDataMap.computeIfAbsent(clientId, k -> new ConcurrentLinkedDeque<>()).add(data);
            }
            return;
        }
        connectingSet.add(clientId);
        if (data.length() > 0) {
            pendingDataMap.computeIfAbsent(clientId, k -> new ConcurrentLinkedDeque<>()).add(data);
        }
        log.info("收到连接请求[{}]，准备连接到[{}:{}]！", clientId, originHost, originPort);
        // 根据是否HTTPS选择对应的TCP客户端
        NetClient selectedClient = https ? httpsClient : tcpClient;
        selectedClient.connect(originPort, originHost).onComplete(asyncResult -> {
            connectingSet.remove(clientId);
            Deque<Buffer> pending = pendingDataMap.remove(clientId);
            if (asyncResult.failed()) {
                canceledSet.remove(clientId);
                log.error("内网代理连接到{}:{}失败：{}！", originHost, originPort, asyncResult.cause().getMessage(), asyncResult.cause());
                bufferConsumer.accept(closeBuffer(msgId));
                return;
            }
            NetSocket proxySocket = asyncResult.result();
            if (canceledSet.remove(clientId)) {
                log.info("连接建立期间客户端[{}]已请求关闭，直接关闭内网连接！", clientId);
                proxySocket.close();
                return;
            }
            proxySocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
            netSocketMap.put(clientId, proxySocket);
            proxySocket.exceptionHandler(e -> log.debug("代理转发服务异常：{}", e.getMessage(), e));
            proxySocket.closeHandler(ch -> {
                if (netSocketMap.remove(clientId) != null) {
                    log.info("客户端[{}]对应的内容请求关闭！", clientId);
                    this.flow.removeUpstream(proxySocket);
                    this.flow.resumeTunnel(clientId);
                    bufferConsumer.accept(closeBuffer(msgId));
                }
            });
            proxySocket.handler(response -> {
                if (netSocketMap.get(clientId) == null) {
                    log.warn("和服务器断开连接，不返回请求给客户端[{}]！", clientId);
                    return;
                }
                log.debug("已返回消息，通过转发消息到外网穿透服务器，返回给请求客户端[{}]！", clientId);
                bufferConsumer.accept(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE + response.length()).appendByte(JRPMsgType.RESPONSE.getCode()).appendBuffer(msgId).appendBuffer(response));
                //隧道拥塞时暂停读取内网服务，让反压传导到内网服务；隧道排水后由TunnelFlow统一恢复
                this.flow.pauseUpstream(proxySocket);
            });
            log.info("内网代理连接到{}:{}成功！", originHost, originPort);
            //转发排队期间暂存的请求数据到内网真实服务器
            if (pending != null) {
                Buffer buffered;
                while ((buffered = pending.poll()) != null) {
                    sendTcpData(clientProxy, buffered, proxySocket, clientId);
                }
            }
        });
    }

    /**
     * 发送TCP数据
     *
     * @param clientProxy 代理配置信息
     * @param data        数据
     * @param netSocket   数据发送对象
     * @param clientId    请求唯一标识
     */
    private void sendTcpData(ClientProxy clientProxy, Buffer data, NetSocket netSocket, Integer clientId) {
        Buffer writeBuffer = data;
        if (isHttpRequest(data)) {
            //只有确认是HTTP报文才按字符串改写请求头；scp/ssh等二进制数据绝不能做UTF-8解码再编码，否则数据被破坏
            String dataStr = data.toString();
            String host = clientProxy.getHost();
            Integer port = clientProxy.getPort();
            // 剥离location路径前缀（仅当传入的是RouteRule时）
            String location = null;
            if (clientProxy instanceof RouteRule) {
                location = ((RouteRule) clientProxy).getLocation();
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
            writeBuffer = Buffer.buffer(dataStr);
        }
        netSocket.write(writeBuffer);
        if (netSocket.writeQueueFull()) {
            //内网服务侧拥塞：暂停读取隧道，让反压沿TCP传导到服务端。
            //不能只pause内网socket：那不产生反压，只会在客户端堆里无限堆积（大文件scp上传时OOM）。
            this.flow.pauseTunnel(clientId);
            netSocket.drainHandler(done -> this.flow.resumeTunnel(clientId));
        }
    }

    /**
     * 判断数据是否为HTTP请求（以HTTP方法开头）。
     * 只解码开头若干字节，且用ISO_8859_1按字节解码：
     * 1.避免对二进制数据做整块UTF-8解码（scp/ssh流量下既浪费CPU，又会产生替换字符）；
     * 2.ISO_8859_1是字节到字符的一一映射，不会改变数据。
     *
     * @param data 数据
     * @return true-是HTTP请求
     */
    private static boolean isHttpRequest(Buffer data) {
        if (data == null || data.length() == 0) {
            return false;
        }
        int len = Math.min(data.length(), HTTP_METHOD_MAX_LEN);
        String head = data.getString(0, len, StandardCharsets.ISO_8859_1.name());
        return head.startsWith("GET ") || head.startsWith("POST ")
                || head.startsWith("PUT ") || head.startsWith("DELETE ")
                || head.startsWith("HEAD ") || head.startsWith("OPTIONS ")
                || head.startsWith("PATCH ") || head.startsWith("TRACE ")
                || head.startsWith("CONNECT ");
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
        pendingDataMap.clear();
        connectingSet.clear();
        canceledSet.clear();
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
