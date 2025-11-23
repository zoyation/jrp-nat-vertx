package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;

/**
 * tcp消息处理器
 */
@Slf4j
public abstract class AbstractProxyHandler implements Closeable {
    protected Vertx vertx;

    public AbstractProxyHandler(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * 处理消息
     *
     * @param registerWebSocket 注册连接
     * @param msgType           消息类型
     * @param clientId          唯一标识
     * @param msgId             消息id
     * @param proxyPass         代理信息
     * @param data              数据
     */
    public void handle(WebSocket registerWebSocket, byte msgType, String clientId, String msgId, String proxyPass, Buffer data) {
        log.debug("收到外网穿透服务器转发的客户端请求消息[{}]！", clientId);
        try {
            if (msgType == JRPMsgType.CLOSE.getCode()) {
                closeSocket(clientId);
            } else {
                vertx.executeBlocking(() -> {
                    try {
                        receiveMsgAndProxy(registerWebSocket, msgId, clientId, proxyPass, data);
                        return true;
                    } catch (Exception e) {
                        log.error("接受消息失败：{}", e.getMessage(), e);
                        return false;
                    }
                });
            }
        } catch (Exception e) {
            log.error("接受消息失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 关闭连接
     *
     * @param clientId 唯一标识
     */
    public abstract void closeSocket(String clientId);

    /**
     * 接受消息，发请求到内网服务并返回结果
     *
     * @param webSocket 中转连接
     * @param msgId     消息id
     * @param clientId  请求唯一标识（IP+端口）
     * @param proxyPass 代理配置信息
     * @param data      数据
     */
    protected abstract void receiveMsgAndProxy(WebSocket webSocket, String msgId, String clientId, String proxyPass, Buffer data);
}
