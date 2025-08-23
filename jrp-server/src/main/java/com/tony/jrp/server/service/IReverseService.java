package com.tony.jrp.server.service;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import io.vertx.core.Future;
import io.vertx.core.http.ServerWebSocket;

import java.util.List;

/**
 * 客户端请求转发服务接口，启动和停止控制
 */
public interface IReverseService {
    /**
     * 启动代理转发服务
     *
     * @param clientRegister 被代理客户端信息
     * @param webSocket      客户端请求
     * @return 启动结果
     */
    Future<Boolean> startReverseProxy(ClientRegister clientRegister, ServerWebSocket webSocket);
    /**
     * 停止代理转发服务
     *
     * @param clientProxyList   被代理客户端信息
     * @return 停止结果
     */
    Future<String> stopReverseProxy(List<ClientProxy> clientProxyList, ServerWebSocket webSocket);
}
