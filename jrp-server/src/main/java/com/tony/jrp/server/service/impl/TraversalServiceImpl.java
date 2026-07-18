package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.ITraversalService;
import com.tony.jrp.server.util.TraversalUtil;
import com.tony.jrp.server.verticle.RegisterTraversalVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.tony.jrp.server.util.TraversalUtil.MAX_PORT;
import static com.tony.jrp.server.util.TraversalUtil.MIN_PORT;

/**
 * 穿透服务初始化控制类
 * 接收注册信息，启动穿透服务
 */
@Service
@Slf4j
public class TraversalServiceImpl implements ITraversalService {

    @Autowired
    protected Vertx vertx;
    /**
     * 安全服务
     */
    @Autowired
    protected SecurityService securityService;
    /**
     * 所有代理信息
     */
    private final Map<String, RegisterTraversalVerticle> verticleMap = new ConcurrentHashMap<>();

    @Override
    public Future<String> start(ClientRegister clientRegister, ServerWebSocket webSocket) {
        List<ClientProxy> proxies = clientRegister.getProxies().stream().filter(ClientProxy::isEnable).collect(Collectors.toList());
        //停止上次的代理
        Optional<Map.Entry<String, RegisterTraversalVerticle>> exist = verticleMap.entrySet().stream()
                .filter(entry -> entry.getValue().getClientRegister().getId().equals(clientRegister.getId()))
                .findFirst();
        if (exist.isPresent()) {
            RegisterTraversalVerticle value = exist.get().getValue();
            verticleMap.remove(exist.get().getKey());
            log.info("取消代理：{}", exist.get().getKey());
            return vertx.undeploy(value.deploymentID()).compose(v ->
                    deployVerticle(clientRegister, webSocket, proxies)
            );
        } else {
            return deployVerticle(clientRegister, webSocket, proxies);
        }
    }

    /**
     * 部署穿透服务
     *
     * @param clientRegister 客户端注册信息
     * @param webSocket      客户端WebSocket连接
     * @param proxies        代理信息
     * @return 部署穿透服务结果
     */
    private Future<String> deployVerticle(ClientRegister clientRegister, ServerWebSocket webSocket, List<ClientProxy> proxies) {
        List<String> invalidPorts = TraversalUtil.getInvalidPorts(proxies);
        if (!invalidPorts.isEmpty()) {
            throw new IllegalArgumentException("端口[" + String.join(",", invalidPorts) + "]已被使用，请使用" + MIN_PORT + "到" + MAX_PORT + "中其它端口，或让服务器自动分配！");
        } else {
            TraversalUtil.allocatePort(proxies);
            RegisterTraversalVerticle traversalVerticle = new RegisterTraversalVerticle(clientRegister, webSocket, securityService);
            verticleMap.put(webSocket.textHandlerID(), traversalVerticle);
            return vertx.deployVerticle(traversalVerticle);
        }
    }

    @Override
    public Future<Void> stop(List<ClientProxy> clientProxyList, ServerWebSocket webSocket) {
        if (clientProxyList != null) {
            RegisterTraversalVerticle clientReverseProxyVerticle = verticleMap.remove(webSocket.textHandlerID());
            if (clientReverseProxyVerticle != null && vertx.deploymentIDs().contains(clientReverseProxyVerticle.deploymentID())) {
                return vertx.undeploy(clientReverseProxyVerticle.deploymentID());
            }
        }
        return Future.succeededFuture();
    }
}
