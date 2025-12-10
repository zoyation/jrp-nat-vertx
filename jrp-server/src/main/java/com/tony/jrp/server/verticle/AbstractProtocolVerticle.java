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

/**
 * 穿透协议服务基础类
 */
public abstract class AbstractProtocolVerticle<T> extends AbstractVerticle {
    /**
     * 读写超时时间，单位秒
     */
    public static final int IDLE_TIMEOUT = 10;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
    public static final int MSG_BYTE_SIZE = 6;

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
    protected final ClientRegister clientRegister;
    /**
     * 内网代理服务注册信息
     */
    protected final ClientProxy clientProxy;

    /**
     * 请求ID池，缓存请求信息
     */
    private final Map<Integer, T> clientSocketMap = new ConcurrentHashMap<>();

    protected AbstractProtocolVerticle(ServerWebSocket serverSocket, SecurityService securityService, ClientRegister clientRegister, ClientProxy clientProxy) {
        this.serverSocket = serverSocket;
        this.securityService = securityService;
        this.clientRegister = clientRegister;
        this.clientProxy = clientProxy;
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
     * 移除请求缓存
     *
     * @param requestId 请求ID
     */
    protected void removeRequest(Integer requestId) {
        clientSocketMap.remove(requestId);
    }

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
    protected boolean cacheRequest(int requestId) {
        return clientSocketMap.containsKey(requestId);
    }

    /**
     * 关闭请求
     *
     * @param requestId 请求ID
     */
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
     * 转发向内网代理服务器返回数据给用户端
     *
     * @param msgType   消息类型
     * @param msgId     消息ID
     * @param requestId 客户端地址
     * @param realData  实际数据
     */
    protected abstract void writeData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer realData);
}
