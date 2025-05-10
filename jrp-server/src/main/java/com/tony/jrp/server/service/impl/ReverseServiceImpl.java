package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.utils.PortChecker;
import com.tony.jrp.server.service.IReverseService;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.impl.ConcurrentHashSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
@Service
@Slf4j
public class ReverseServiceImpl implements IReverseService {
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 49151;
    @Autowired
    protected Vertx vertx;
    @Autowired
    protected SecurityService securityService;
    /**
     * 所有代理信息
     */
    private final Map<String, ClientReverseProxyVerticle> reverseProxyMap = new ConcurrentHashMap<>();
    /**
     * 所有已使用端口
     */
    private final Set<Integer> allPorts = new ConcurrentHashSet<>();

    @Override
    public synchronized Future<Boolean> startReverseProxy(ClientRegister clientRegister, ServerWebSocket webSocket) {
        List<ClientProxy> proxies = clientRegister.getProxies();
        //进行端口检查，如果端口被占用了，提示不能使用
        return vertx.executeBlocking(() -> {
            List<String> usedPort = new ArrayList<>();
            for (ClientProxy clientProxy : proxies) {
                if (allPorts.contains(clientProxy.getRemote_port()) || clientProxy.getRemote_port() < MIN_PORT || clientProxy.getRemote_port() > MAX_PORT || !PortChecker.isUsable(clientProxy.getRemote_port())) {
                    usedPort.add(String.valueOf(clientProxy.getRemote_port()));
                }
            }
            if (!usedPort.isEmpty()) {
                throw new IllegalArgumentException("端口[" + String.join(",", usedPort) + "]已被使用，请使用1024到49151中其它端口！");
            } else {
                CountDownLatch countDownLatch = new CountDownLatch(1);
                AtomicBoolean result = new AtomicBoolean();
                try {
                    final ClientReverseProxyVerticle newClientReverseProxyVerticle = new ClientReverseProxyVerticle(clientRegister, webSocket, vertx, securityService);
                    reverseProxyMap.put(webSocket.textHandlerID(), newClientReverseProxyVerticle);
                    proxies.forEach(r -> allPorts.add(r.getRemote_port()));
                    vertx.deployVerticle(newClientReverseProxyVerticle).onSuccess(id -> {
                        result.set(true);
                        countDownLatch.countDown();
                    }).onFailure(e -> {
                        log.error("内网穿透代理异常：{}",e.getMessage(),e);
                        result.set(false);
                        countDownLatch.countDown();
                    });
                } catch (Exception e) {
                    log.error("初始化内网穿透代理异常：{}",e.getMessage(),e);
                    result.set(false);
                    countDownLatch.countDown();
                }
                try{
                    boolean countDown = countDownLatch.await(10, TimeUnit.SECONDS);
                    if(!countDown){
                        log.error("初始化内网穿透代理超时！");
                    }
                    result.set(countDown);
                }catch (Exception e){
                    result.set(false);
                }
                return result.get();
            }
        });
    }

    @Override
    public Future<String> stopReverseProxy(ClientRegister clientRegister, ServerWebSocket webSocket) {
        Promise<String> promise = Promise.promise();
        if (clientRegister != null) {
            clientRegister.getProxies().forEach(r -> allPorts.remove(r.getRemote_port()));
            ClientReverseProxyVerticle clientReverseProxyVerticle = reverseProxyMap.remove(webSocket.textHandlerID());
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
