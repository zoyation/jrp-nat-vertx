package com.tony.jrp.server.verticle;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

/**
 * 穿透服务基础类
 */
public abstract class AbstractProxyVerticle extends AbstractVerticle {
    /**
     * 读写超时时间，单位秒
     */
    public static final int IDLE_TIMEOUT = 10;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
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

    protected AbstractProxyVerticle(ServerWebSocket serverSocket, SecurityService securityService, ClientRegister clientRegister, ClientProxy clientProxy) {
        this.serverSocket = serverSocket;
        this.securityService = securityService;
        this.clientRegister = clientRegister;
        this.clientProxy = clientProxy;
    }

    @Override
    public void start() {
        init();
    }

    /**
     * 初始化穿透服务
     */
    public abstract void init();

    /**
     * 写数据
     */
    public abstract void writeData(String msgId, String clientAddress, Buffer realData);
}
