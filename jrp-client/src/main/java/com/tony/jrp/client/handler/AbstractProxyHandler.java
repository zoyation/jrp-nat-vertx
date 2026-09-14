package com.tony.jrp.client.handler;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.function.Consumer;

/**
 * tcp消息处理器
 */
@Slf4j
public abstract class AbstractProxyHandler implements Closeable {
    public static final int BUFFER_SIZE = 1024 * 1024 * 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * 连接超时时间：5秒
     * NAT穿透场景需要更长的超时时间，考虑：
     * 1. 多层NAT转发延迟
     * 2. 跨网络环境（移动网络、跨国等）
     * 3. 内网服务响应慢的情况
     */
    public static final int CONNECT_TIMEOUT = 5000;
    /**
     * TCP空闲超时：300秒（5分钟）
     * 适用于HTTP Keep-Alive、HTTPS隧道、SOCKS代理等长连接场景
     * 避免频繁重建连接，提高性能
     */
    public static final int IDLE_TIMEOUT = 300;
    public static final int TYPE_AND_MSG_ID_BYTE_SIZE = 9;
    protected Vertx vertx;

    public AbstractProxyHandler(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * 处理消息
     *
     * @param bufferConsumer 数据处理器
     * @param msgType        消息类型
     * @param msgId          消息id
     * @param clientId       唯一标识
     * @param proxyPass      代理信息
     * @param data           数据
     */
    public void handle(Consumer<Buffer> bufferConsumer, byte msgType, Buffer msgId, Integer clientId, ClientProxy proxyPass, Buffer data) {
        log.debug("收到外网穿透服务器转发的客户端请求消息[{}]！", clientId);
        try {
            if (msgType == JRPMsgType.CLOSE.getCode()) {
                closeSocket(clientId);
            } else {
                vertx.executeBlocking(() -> {
                    try {
                        receiveMsgAndProxy(bufferConsumer, msgId, clientId, proxyPass, data);
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
     * @param msgId 唯一标识
     * @return 关闭连接消息
     */
    public Buffer closeBuffer(Buffer msgId) {
        return Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.CLOSE.getCode()).appendBuffer(msgId);
    }

    /**
     * 关闭连接
     *
     * @param clientId 唯一标识
     */
    public abstract void closeSocket(Integer clientId);

    /**
     * 接受消息，发请求到内网服务并返回结果
     *
     * @param bufferConsumer 数据处理器
     * @param msgId          消息id
     * @param clientId       请求唯一标识（IP+端口）
     * @param clientProxy    代理配置信息
     * @param data           数据
     */
    protected abstract void receiveMsgAndProxy(Consumer<Buffer> bufferConsumer, Buffer msgId, Integer clientId, ClientProxy clientProxy, Buffer data);
}
