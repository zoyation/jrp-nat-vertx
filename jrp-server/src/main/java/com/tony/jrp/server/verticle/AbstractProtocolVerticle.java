package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 穿透协议服务基础类
 */
public abstract class AbstractProtocolVerticle<T> extends AbstractVerticle {
    /**
     * 读写超时时间，单位秒，5分钟
     */
    public static final int IDLE_TIMEOUT = 300;
    /**
     * 数据大小，默认256KB。
     * 该值只用于UDP收发缓冲区（UDP没有内核自动调优机制，必须显式设置）和写队列上限的推导基数。
     * TCP不再设置SO_RCVBUF/SO_SNDBUF：显式设置会关闭内核接收缓冲自动调优（tcp_moderate_rcvbuf），
     * 且Linux上会被net.core.rmem_max（默认208KB）静默截断，高BDP链路上反而比自动调优更慢。
     */
    public static final int BUFFER_SIZE = 256 * 1024;
    /**
     * 写队列最大字节数，默认1MB（BUFFER_SIZE的4倍）。
     * Vert.x 4.5 的 writeQueueMaxSize 按字节计（内部映射为netty高低水位size/2、size），不是消息条数。
     * 取值必须大于单次转发的数据块，否则几乎每次写入都会触发pause/drain，吞吐退化成停等。
     */
    public static final int WRITE_QUEUE_MAX_SIZE = BUFFER_SIZE * 4;
    public static final int MSG_BYTE_SIZE = 6;
    /**
     * 代理服务器对应的外网IPV4地址
     */
    protected final String ipv4;
    /**
     * 持有和内网代理服务器的连接，收到客户端请求消息后，通知内网代理服务器
     */
    protected final ServerWebSocket serverSocket;
    /**
     * 安全认证控制类
     */
    protected final SecurityService securityService;
    /**
     * 客户端注册信息
     */
    protected ClientRegister clientRegister;
    /**
     * 内网代理服务注册信息
     */
    protected final ClientProxy clientProxy;
    /**
     * 隧道流量控制协调器，同一条隧道下的所有代理共享
     */
    protected final TunnelFlow flow;

    /**
     * 请求ID池，缓存请求信息
     */
    private final Map<Integer, T> clientSocketMap = new ConcurrentHashMap<>();
    /**
     * 请求唯一ID生成器。
     * 不能用 SocketAddress#hashCode：哈希碰撞会让两条连接串数据；也不能每个Verticle各自从0自增，
     * 因为内网客户端的连接缓存（TcpReverseProxyHandler#netSocketMap）是全局的，不同端口的ID会撞号。
     * 因此全局统一分配。
     */
    private static final AtomicInteger REQUEST_ID_SEQ = new AtomicInteger();

    protected AbstractProtocolVerticle(String ipv4, ServerWebSocket serverSocket, SecurityService securityService, ClientRegister clientRegister, ClientProxy clientProxy, TunnelFlow flow) {
        this.ipv4 = ipv4;
        this.serverSocket = serverSocket;
        this.securityService = securityService;
        this.clientRegister = clientRegister;
        this.clientProxy = clientProxy;
        this.flow = flow;
        serverSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
    }

    /**
     * 生成全局唯一的请求ID
     *
     * @return 请求ID
     */
    protected static int nextRequestId() {
        return REQUEST_ID_SEQ.incrementAndGet();
    }

    @Override
    public void start() {
        init();
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }

    /**
     * 初始化穿透服务
     */
    protected abstract void init();

    /**
     * 缓存请求
     *
     * @param requestId 请求ID
     * @param request   请求信息
     */
    protected void cacheRequest(int requestId, T request) {
        clientSocketMap.put(requestId, request);
    }

    /**
     * 获取缓存的请求
     *
     * @param requestId 请求ID
     */
    protected T getRequest(int requestId) {
        return clientSocketMap.get(requestId);
    }

    /**
     * 获取缓存的请求，如果进行了转发就会缓存请求信息
     *
     * @param requestId 请求ID
     */
    protected boolean cachedRequest(int requestId) {
        return clientSocketMap.containsKey(requestId);
    }

    protected void removeCacheAndClose(Integer requestId) {
        T remove = clientSocketMap.remove(requestId);
        if (remove != null) {
            this.closeRequest(remove);
        }
    }

    /**
     * 关闭请求
     *
     * @param request 请求信息
     */
    protected abstract void closeRequest(T request);

    /**
     * 转发内网代理服务器返回数据给用户端
     *
     * @param msgType   消息类型
     * @param msgId     消息ID
     * @param requestId 客户端地址
     * @param data      实际数据
     */
    protected abstract void backData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer data);

    /**
     * 获取客户端代理配置
     *
     * @return 客户端代理配置
     */
    public ClientProxy getClientProxy() {
        return clientProxy;
    }

    /**
     * 更新客户端注册信息
     *
     * @param clientRegister 新的客户端注册信息
     */
    public void setClientRegister(ClientRegister clientRegister) {
        this.clientRegister = clientRegister;
    }
}
