package com.tony.jrp.server.verticle;

import com.tony.jrp.server.manager.P2PSessionManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * P2P打洞服务器Verticle
 * 负责处理P2P注册、打洞协助和心跳维护
 */
@Slf4j
public class P2PServerVerticle extends AbstractVerticle {

    /**
     * P2P消息类型
     */
    public enum P2PMsgType {
        REGISTER((byte) 0x01, "P2P注册"),
        HOLE_PUNCH((byte) 0x02, "打洞请求"),
        HOLE_PUNCH_RESPONSE((byte) 0x03, "打洞响应"),
        HEARTBEAT((byte) 0x04, "心跳"),
        DATA((byte) 0x05, "数据传输"),
        ERROR((byte) 0x06, "错误");

        private final byte code;
        private final String desc;

        P2PMsgType(byte code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public byte getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }

        public static P2PMsgType getByCode(byte code) {
            for (P2PMsgType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 远程穿透端口（复用为P2P打洞端口）
     * 此端口同时支持TCP转发访问和UDP打洞辅助
     */
    private final int remotePort;

    /**
     * P2P会话管理器（全局共享）
     */
    private final P2PSessionManager sessionManager;

    /**
     * UDP Socket
     */
    private DatagramSocket datagramSocket;

    /**
     * 服务器公网IP
     */
    private String publicIp;

    public P2PServerVerticle(int remotePort, P2PSessionManager sessionManager) {
        this.remotePort = remotePort;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() throws Exception {
        // 创建UDP服务器，监听remotePort（与TCP转发共用同一端口号）
        DatagramSocketOptions options = new DatagramSocketOptions()
                .setIpV6(false)
                .setReusePort(true)
                .setReceiveBufferSize(65536)
                .setSendBufferSize(65536);

        datagramSocket = vertx.createDatagramSocket(options);

        datagramSocket.listen(remotePort, "0.0.0.0", asyncResult -> {
            if (asyncResult.succeeded()) {
                log.info("P2P打洞UDP服务启动成功，监听端口: {} (同时支持TCP转发)", remotePort);

                // 获取服务器公网IP
                detectPublicIp();

                // 处理接收到的数据包
                datagramSocket.handler(this::handlePacket);
            } else {
                log.error("P2P打洞UDP服务启动失败，端口: {}", remotePort, asyncResult.cause());
                throw new RuntimeException("P2P打洞UDP服务启动失败", asyncResult.cause());
            }
        });
    }

    /**
     * 处理接收到的UDP数据包
     */
    private void handlePacket(DatagramPacket packet) {
        try {
            Buffer buffer = packet.data();
            if (buffer.length() < 1) {
                log.warn("收到空数据包，来源: {}", packet.sender());
                return;
            }

            byte msgTypeCode = buffer.getByte(0);
            P2PMsgType msgType = P2PMsgType.getByCode(msgTypeCode);

            if (msgType == null) {
                log.warn("未知的P2P消息类型: {}, 来源: {}", msgTypeCode, packet.sender());
                return;
            }

            String senderAddress = packet.sender().host();
            int senderPort = packet.sender().port();

            log.debug("收到P2P消息: type={}, from={}:{}", msgType.getDesc(), senderAddress, senderPort);

            switch (msgType) {
                case REGISTER:
                    handleRegister(packet, buffer);
                    break;
                case HOLE_PUNCH:
                    handleHolePunch(packet, buffer);
                    break;
                case HEARTBEAT:
                    handleHeartbeat(packet, buffer);
                    break;
                case DATA:
                    handleData(packet, buffer);
                    break;
                default:
                    log.warn("未处理的P2P消息类型: {}", msgType.getDesc());
            }
        } catch (Exception e) {
            log.error("处理P2P数据包异常", e);
        }
    }

    /**
     * 处理P2P注册请求
     */
    private void handleRegister(DatagramPacket packet, Buffer buffer) {
        try {
            byte[] dataBytes = buffer.getBytes();
            byte[] jsonBytes = new byte[dataBytes.length - 1];
            System.arraycopy(dataBytes, 1, jsonBytes, 0, jsonBytes.length);

            JsonObject json = new JsonObject(new String(jsonBytes));
            String clientId = json.getString("client_id");
            String proxyId = json.getString("proxy_id");
            String localIp = json.getString("local_ip");
            Integer localPort = json.getInteger("local_port");

            if (clientId == null || proxyId == null || localIp == null || localPort == null) {
                log.warn("P2P注册请求参数不完整: {}", json.encode());
                sendErrorResponse(packet, "参数不完整");
                return;
            }

            // 创建或更新会话
            P2PSessionManager.P2PSession session = sessionManager.createOrUpdateSession(
                    clientId, proxyId, localIp, localPort);

            // 设置公网地址和socket
            session.setPublicIp(packet.sender().host());
            session.setPublicPort(packet.sender().port());
            session.setSocket(datagramSocket);

            // 返回注册成功响应
            JsonObject response = new JsonObject()
                    .put("type", "p2p_register_response")
                    .put("success", true)
                    .put("public_ip", packet.sender().host())
                    .put("public_port", packet.sender().port())
                    .put("server_public_ip", publicIp)
                    .put("remote_port", remotePort);

            sendResponse(packet, response);

            log.info("P2P注册成功: clientId={}, proxyId={}, local={}:{}, public={}:{}",
                    clientId, proxyId, localIp, localPort,
                    packet.sender().host(), packet.sender().port());

        } catch (Exception e) {
            log.error("处理P2P注册请求失败", e);
            sendErrorResponse(packet, "注册失败: " + e.getMessage());
        }
    }

    /**
     * 处理打洞请求
     */
    private void handleHolePunch(DatagramPacket packet, Buffer buffer) {
        try {
            byte[] dataBytes = buffer.getBytes();
            byte[] jsonBytes = new byte[dataBytes.length - 1];
            System.arraycopy(dataBytes, 1, jsonBytes, 0, jsonBytes.length);

            JsonObject json = new JsonObject(new String(jsonBytes));
            String clientId = json.getString("client_id");
            String proxyId = json.getString("proxy_id");
            String targetClientId = json.getString("target_client_id");

            if (clientId == null || proxyId == null || targetClientId == null) {
                log.warn("打洞请求参数不完整: {}", json.encode());
                sendErrorResponse(packet, "参数不完整");
                return;
            }

            // 查找目标会话
            P2PSessionManager.P2PSession targetSession = sessionManager.getSession(targetClientId, proxyId);
            if (targetSession == null) {
                log.warn("目标P2P会话不存在: targetClientId={}, proxyId={}", targetClientId, proxyId);
                sendErrorResponse(packet, "目标会话不存在");
                return;
            }

            // 返回目标公网地址
            JsonObject response = new JsonObject()
                    .put("type", "p2p_hole_punch_response")
                    .put("success", true)
                    .put("target_public_ip", targetSession.getPublicIp())
                    .put("target_public_port", targetSession.getPublicPort())
                    .put("target_local_ip", targetSession.getLocalIp())
                    .put("target_local_port", targetSession.getLocalPort());

            sendResponse(packet, response);

            log.info("返回打洞目标信息: clientId={}, proxyId={}, target={}:{}",
                    clientId, proxyId, targetSession.getPublicIp(), targetSession.getPublicPort());

            // 通知目标客户端有新的打洞请求
            JsonObject notify = new JsonObject()
                    .put("type", "p2p_hole_punch_notify")
                    .put("client_id", clientId)
                    .put("public_ip", packet.sender().host())
                    .put("public_port", packet.sender().port());

            Buffer notifyBuffer = Buffer.buffer()
                    .appendByte(P2PMsgType.HOLE_PUNCH.getCode())
                    .appendBytes(notify.encode().getBytes());

            datagramSocket.send(notifyBuffer,
                    targetSession.getPublicPort(),
                    targetSession.getPublicIp(),
                    asyncResult -> {
                        if (asyncResult.succeeded()) {
                            log.debug("已通知目标客户端打洞请求: target={}:{}",
                                    targetSession.getPublicIp(), targetSession.getPublicPort());
                        } else {
                            log.warn("通知目标客户端打洞请求失败", asyncResult.cause());
                        }
                    });

        } catch (Exception e) {
            log.error("处理打洞请求失败", e);
            sendErrorResponse(packet, "打洞请求失败: " + e.getMessage());
        }
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(DatagramPacket packet, Buffer buffer) {
        try {
            byte[] dataBytes = buffer.getBytes();
            byte[] jsonBytes = new byte[dataBytes.length - 1];
            System.arraycopy(dataBytes, 1, jsonBytes, 0, jsonBytes.length);

            JsonObject json = new JsonObject(new String(jsonBytes));
            String clientId = json.getString("client_id");
            String proxyId = json.getString("proxy_id");

            if (clientId != null && proxyId != null) {
                sessionManager.updateHeartbeat(clientId, proxyId);

                // 返回心跳响应
                JsonObject response = new JsonObject()
                        .put("type", "p2p_heartbeat_response")
                        .put("success", true)
                        .put("timestamp", System.currentTimeMillis());

                sendResponse(packet, response);
            }
        } catch (Exception e) {
            log.error("处理心跳消息失败", e);
        }
    }

    /**
     * 处理数据传输（中转模式，可选）
     */
    private void handleData(DatagramPacket packet, Buffer buffer) {
        // P2P模式下，数据应该直接在客户端之间传输
        // 这里可以实现中转模式，当P2P打洞失败时使用
        log.debug("收到P2P数据传输请求（暂未实现中转模式）");
    }

    /**
     * 发送响应
     */
    private void sendResponse(DatagramPacket packet, JsonObject json) {
        Buffer buffer = Buffer.buffer()
                .appendByte(P2PMsgType.HOLE_PUNCH_RESPONSE.getCode())
                .appendBytes(json.encode().getBytes());

        datagramSocket.send(buffer, packet.sender().port(), packet.sender().host(),
                asyncResult -> {
                    if (asyncResult.failed()) {
                        log.warn("发送P2P响应失败: {}", asyncResult.cause().getMessage());
                    }
                });
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(DatagramPacket packet, String errorMsg) {
        JsonObject errorJson = new JsonObject()
                .put("type", "p2p_error")
                .put("success", false)
                .put("error", errorMsg);

        Buffer buffer = Buffer.buffer()
                .appendByte(P2PMsgType.ERROR.getCode())
                .appendBytes(errorJson.encode().getBytes());

        datagramSocket.send(buffer, packet.sender().port(), packet.sender().host(),
                asyncResult -> {
                    if (asyncResult.failed()) {
                        log.warn("发送P2P错误响应失败: {}", asyncResult.cause().getMessage());
                    }
                });
    }

    /**
     * 检测服务器公网IP
     */
    private void detectPublicIp() {
        vertx.executeBlocking(promise -> {
            try {
                // 简单获取：使用第一个监听的本地地址
                // 实际部署时可能需要通过外部API获取公网IP
                java.net.InetAddress localHost = java.net.InetAddress.getLocalHost();
                this.publicIp = localHost.getHostAddress();
                log.info("检测到服务器IP地址: {}", publicIp);
                promise.complete();
            } catch (Exception e) {
                log.warn("获取服务器公网IP失败，使用默认值", e);
                this.publicIp = "0.0.0.0";
                promise.complete();
            }
        });
    }

    @Override
    public void stop() throws Exception {
        if (datagramSocket != null) {
            datagramSocket.close();
            log.info("P2P打洞服务器已停止");
        }
    }

    /**
     * 获取服务器公网IP
     */
    public String getPublicIp() {
        return publicIp;
    }
}