package com.tony.jrp.client.verticle;

import com.tony.jrp.client.service.impl.SecurityService;
import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.UserProxy;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.SocketAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 穿透协议服务基础类
 */
public abstract class AbstractProtocolVerticle<T> extends AbstractVerticle {
    /**
     * 读写超时时间，单位秒
     */
    public static final int IDLE_TIMEOUT = 60;
    /**
     * 数据大小，默认256KB
     */
    public static final int BUFFER_SIZE = 256 * 1024;
    /**
     * 写队列最大长度4 * 1024 * 256 * 1024=1G
     */
    public static final int WRITE_QUEUE_MAX_SIZE = 4 * 1024;
    public static final int MSG_BYTE_SIZE = 6;
    /**
     * 代理服务器对应的外网IPV4地址
     */
    protected final String ipv4;
    /**
     * 持有和内网代理服务器的连接，收到客户端请求消息后，通知内网代理服务器
     */
    protected final DatagramSocket datagramSocket;
    /**
     * 安全认证控制类
     */
    protected final SecurityService securityService;
    /**
     * 内网代理服务注册信息
     */
    protected final UserProxy clientProxy;

    protected final SocketAddress p2pSocketAddress;
    /**
     * 请求ID池，缓存请求信息
     */
    private final Map<Integer, T> clientSocketMap = new ConcurrentHashMap<>();
    /**
     * 定义请求ID生成器，可复用
     */
    protected final AtomicInteger requestIdGenerator = new AtomicInteger(0);

    /**
     * 构造函数
     *
     * @param ipv4            内网IPV4地址
     * @param serverSocket    UDP服务器
     * @param socketAddress   P2P服务器地址
     * @param securityService 安全服务
     * @param clientProxy     客户端代理信息
     */
    protected AbstractProtocolVerticle(String ipv4, DatagramSocket serverSocket, SocketAddress socketAddress, SecurityService securityService, UserProxy clientProxy) {
        this.ipv4 = ipv4;
        this.datagramSocket = serverSocket;
        this.p2pSocketAddress = socketAddress;
        this.securityService = securityService;
        this.clientProxy = clientProxy;
    }

    @Override
    public void start() {
        init();
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        //关闭P2P打洞DatagramSocket
        if (datagramSocket != null) {
            datagramSocket.close();
        }
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
    public abstract void backData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer data);

    /**
     * 获取客户端代理配置
     *
     * @return 客户端代理配置
     */
    public UserProxy getClientProxy() {
        return clientProxy;
    }
}


