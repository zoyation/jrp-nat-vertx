package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 穿透服务基础类
 */
public abstract class AbstractProxyVerticle<T> extends AbstractVerticle {
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

    private final Map<Integer, T> clientSocketMap = new ConcurrentHashMap<>();


    /**
     * 请求ID池
     *
     * @param <T>
     */
    private class RequestIdPool<T> {
        private final Map<Integer, T> resourceMap = new ConcurrentHashMap<>();
        private final Map<T, Integer> idMap = new ConcurrentHashMap<>();
        private final Queue<Integer> availableIds = new ConcurrentLinkedQueue<>();
        private final int maxCapacity;

        public RequestIdPool(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            // 预先生成可用ID队列
            for (int i = 0; i < maxCapacity; i++) {
                availableIds.offer(i);
            }
        }

        public Integer acquire(T resource) {
            Integer id = availableIds.poll();
            if (id == null) {
                throw new RuntimeException("Resource pool exhausted");
            }
            resourceMap.put(id, resource);
            idMap.put(resource, id);
            return id;
        }

        public T release(Integer id) {
            T resource = resourceMap.remove(id);
            if (resource != null) {
                idMap.remove(resource);
                availableIds.offer(id);
            }
            return resource;
        }

        public T getResource(Integer id) {
            return resourceMap.get(id);
        }

        public Integer getId(T res) {
            return idMap.get(res);
        }
    }

    /**
     * 默认100万个并发的请求ID池
     */
    private final RequestIdPool<String> requestIdPool = new RequestIdPool<>(1000000);

    protected AbstractProxyVerticle(ServerWebSocket serverSocket, SecurityService securityService, ClientRegister clientRegister, ClientProxy clientProxy) {
        this.serverSocket = serverSocket;
        this.securityService = securityService;
        this.clientRegister = clientRegister;
        this.clientProxy = clientProxy;
    }


    /**
     * 释放请求ID
     *
     * @param id 请求ID
     */
    public String release(Integer id) {
        return requestIdPool.release(id);
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
     * 移除请求
     *
     * @param requestId 请求ID
     */
    protected T removeRequest(Integer requestId) {
        return clientSocketMap.remove(requestId);
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
     * 获取缓存的请求
     *
     * @param requestId 请求ID
     */
    protected boolean hasRequest(int requestId) {
        return clientSocketMap.containsKey(requestId);
    }

    /**
     * 关闭请求
     *
     * @param requestId 请求ID
     * @param request   请求信息
     */
    protected void closeRequest(Integer requestId, T request) {
        this.removeRequest(requestId);
        this.closeRequest(request);
    }

    /**
     * 关闭请求
     *
     * @param request 请求信息
     */
    protected abstract void closeRequest(T request);

    /**
     * 向内网代理服务器发送数据
     *
     * @param msgType   消息类型
     * @param msgId     消息ID
     * @param requestId 客户端地址
     * @param realData  实际数据
     */
    protected abstract void writeData(JRPMsgType msgType, Buffer msgId, Integer requestId, Buffer realData);
}
