package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.utils.PortChecker;
import com.tony.jrp.server.service.ITraversalService;
import com.tony.jrp.server.verticle.RegisterTraversalVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 穿透服务初始化控制类
 * 接收注册信息，启动穿透服务
 */
@Service
@Slf4j
public class TraversalServiceImpl implements ITraversalService {
    /**
     * 允许穿透的最小端口
     */
    public static final int MIN_PORT = 1024;
    /**
     * 允许穿透的最大端口
     */
    public static final int MAX_PORT = 49151;
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
    public synchronized Future<Boolean> start(ClientRegister clientRegister, ServerWebSocket webSocket) {
        List<ClientProxy> proxies = clientRegister.getProxies();
        return vertx.executeBlocking(() -> {
            //停止上次的代理
            verticleMap.forEach((key, traversalVerticle) -> {
                if (traversalVerticle.getClientRegister().getId().equals(clientRegister.getId())) {
                    vertx.undeploy(traversalVerticle.deploymentID());
                }
            });
            verticleMap.entrySet().removeIf(entry -> {
                boolean remove = entry.getValue().getClientRegister().getId().equals(clientRegister.getId());
                vertx.undeploy(entry.getValue().deploymentID());
                return remove;
            });
            //进行端口检查，如果端口被占用了，提示不能使用
            List<String> usedPort = new ArrayList<>();
            for (ClientProxy clientProxy : proxies) {
                if (clientProxy.getRemote_port() < MIN_PORT || clientProxy.getRemote_port() > MAX_PORT || !PortChecker.isUsable(clientProxy.getRemote_port())) {
                    usedPort.add(String.valueOf(clientProxy.getRemote_port()));
                }
            }
            if (!usedPort.isEmpty()) {
                throw new IllegalArgumentException("端口[" + String.join(",", usedPort) + "]已被使用，请使用" + MIN_PORT + "到" + MAX_PORT + "中其它端口！");
            } else {
                CountDownLatch countDownLatch = new CountDownLatch(1);
                AtomicBoolean result = new AtomicBoolean();
                try {
                    final RegisterTraversalVerticle verticle = new RegisterTraversalVerticle(clientRegister, webSocket, securityService);
                    verticleMap.put(webSocket.textHandlerID(), verticle);
                    vertx.deployVerticle(verticle).onSuccess(id -> {
                        result.set(true);
                        countDownLatch.countDown();
                    }).onFailure(e -> {
                        log.error("内网穿透代理异常：{}", e.getMessage(), e);
                        result.set(false);
                        countDownLatch.countDown();
                    });
                } catch (Exception e) {
                    log.error("初始化内网穿透代理异常：{}", e.getMessage(), e);
                    result.set(false);
                    countDownLatch.countDown();
                }
                try {
                    boolean countDown = countDownLatch.await(10, TimeUnit.SECONDS);
                    if (!countDown) {
                        log.error("初始化内网穿透代理超时！");
                    }
                    result.set(countDown);
                } catch (Exception e) {
                    result.set(false);
                }
                return result.get();
            }
        });
    }

    @Override
    public Future<String> stop(List<ClientProxy> clientProxyList, ServerWebSocket webSocket) {
        Promise<String> promise = Promise.promise();
        if (clientProxyList != null) {
            RegisterTraversalVerticle clientReverseProxyVerticle = verticleMap.remove(webSocket.textHandlerID());
            if (clientReverseProxyVerticle != null) {
                try {
                    vertx.undeploy(clientReverseProxyVerticle.deploymentID())
                            .onSuccess(s -> promise.complete("停止代理成功。"))
                            .onFailure(t -> {
                                promise.fail("停止代理失败：" + t.getMessage());
                                log.error("停止代理失败。", t);
                            });
                } catch (Exception e) {
                    log.error("停止代理异常：{}", e.getMessage(), e);
                    promise.fail("停止代理异常：" + e.getMessage());
                }
            } else {
                promise.complete("未找到代理信息。");
            }
        } else {
            promise.complete("clientRegister参数为空。");
        }
        return promise.future();
    }
}
