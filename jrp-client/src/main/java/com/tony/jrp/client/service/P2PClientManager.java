package com.tony.jrp.client.service;

import com.tony.jrp.client.server.LocalProxyServer;
import com.tony.jrp.client.tunnel.P2PTunnel;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2P客户端管理器
 * 负责P2P打洞、隧道建立和数据传输
 */
@Slf4j
public class P2PClientManager {

    /**
     * P2P配置
     */
    private final String clientId;
    private final String proxyId;
    private final String serverP2pAddress;
    private final Vertx vertx;
    private final ClientProxy clientProxy;

    /**
     * 本地服务信息（原始服务地址）
     */
    private final String localIp;
    private final int localPort;

    /**
     * 本地监听端口（P2P连接成功后，在此端口监听用户请求）
     */
    private final int localAccessPort;

    /**
     * P2P隧道
     */
    private P2PTunnel p2pTunnel;

    /**
     * UDP Socket
     */
    private DatagramSocket datagramSocket;

    /**
     * 本地代理服务器
     */
    private LocalProxyServer localProxyServer;

    /**
     * P2P状态
     */
    public enum P2PState {
        IDLE,
        REGISTERING,
        REGISTERED,
        HOLE_PUNCHING,
        CONNECTED,
        FAILED,
        DISCONNECTED
    }

    private volatile P2PState state = P2PState.IDLE;

    /**
     * 重连计数器
     */
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    /**
     * 最大重连次数
     */
    private final int maxReconnectTimes;

    /**
     * 打洞超时时间（毫秒）
     */
    private static final long HOLE_PUNCH_TIMEOUT = 30000;

    /**
     * 打洞开始时间
     */
    private final AtomicLong holePunchStartTime = new AtomicLong(0);

    /**
     * 目标客户端信息（打洞成功后获取）
     */
    private String targetPublicIp;
    private int targetPublicPort;
    private String targetLocalIp;
    private int targetLocalPort;

    /**
     * 心跳定时器ID
     */
    private long heartbeatTimerId = -1;

    /**
     * 回调接口
     */
    public interface P2PCallback {
        void onConnected();
        void onFailed(String reason);
        void onDataReceived(byte[] data);
    }

    private final P2PCallback callback;

    public P2PClientManager(String clientId, ClientProxy clientProxy, String serverP2pAddress,
                            Vertx vertx, int maxReconnectTimes, int localAccessPort, P2PCallback callback) {
        this.clientId = clientId;
        this.proxyId = clientProxy.getId();
        this.serverP2pAddress = serverP2pAddress;
        this.vertx = vertx;
        this.clientProxy = clientProxy;
        this.maxReconnectTimes = maxReconnectTimes;
        this.localAccessPort = localAccessPort;
        this.callback = callback;

        // 解析本地服务地址（原始服务地址，打洞成功后数据将转发到此地址）
        String proxyPass = clientProxy.getProxy_pass();
        if (proxyPass == null || !proxyPass.contains(":")) {
            throw new IllegalArgumentException("proxy_pass格式错误，应为 host:port: " + proxyPass);
        }
        // 去掉协议前缀（如 http://）
        String cleanPass = proxyPass.replaceAll("^https?://", "");
        String[] localParts = cleanPass.split(":");
        this.localIp = localParts[0];
        this.localPort = Integer.parseInt(localParts[1]);
    }

    /**
     * 启动P2P客户端
     */
    public void start() {
        if (state != P2PState.IDLE && state != P2PState.FAILED && state != P2PState.DISCONNECTED) {
            log.warn("P2P客户端已在运行或正在连接: proxyId={}, state={}", proxyId, state);
            return;
        }

        log.info("启动P2P客户端: proxyId={}, server={}", proxyId, serverP2pAddress);

        // 创建UDP Socket
        DatagramSocketOptions options = new DatagramSocketOptions()
                .setIpV6(false)
                .setReusePort(true);

        datagramSocket = vertx.createDatagramSocket(options);

        // 处理接收到的数据包
        datagramSocket.handler(this::handlePacket);

        // 解析服务器地址
        String[] serverParts = serverP2pAddress.split(":");
        String serverIp = serverParts[0];
        int serverPort = Integer.parseInt(serverParts[1]);

        // 创建P2P隧道
        p2pTunnel = new P2PTunnel(proxyId, serverIp, serverPort) {
            @Override
            protected void onDataReceived(byte[] data) {
                handleTunnelData(data);
            }
        };

        p2pTunnel.setDatagramSocket(datagramSocket);

        // 开始注册流程
        registerToServer();
    }

    /**
     * 停止P2P客户端
     */
    public void stop() {
        log.info("停止P2P客户端: proxyId={}", proxyId);

        state = P2PState.DISCONNECTED;

        // 停止心跳
        if (heartbeatTimerId != -1) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }

        // 关闭P2P隧道
        if (p2pTunnel != null) {
            p2pTunnel.close();
        }

        // 关闭本地代理服务器
        if (localProxyServer != null) {
            localProxyServer.stop();
        }

        // 关闭UDP Socket
        if (datagramSocket != null) {
            datagramSocket.close();
        }

        log.info("P2P客户端已停止: proxyId={}", proxyId);
    }

    /**
     * 注册到P2P服务器
     */
    private void registerToServer() {
        state = P2PState.REGISTERING;

        try {
            JsonObject registerJson = new JsonObject()
                    .put("type", "p2p_register")
                    .put("client_id", clientId)
                    .put("proxy_id", proxyId)
                    .put("local_ip", localIp)
                    .put("local_port", localPort);

            Buffer buffer = Buffer.buffer()
                    .appendByte((byte) 0x01) // REGISTER消息类型
                    .appendBytes(registerJson.encode().getBytes());

            String[] serverParts = serverP2pAddress.split(":");
            String serverIp = serverParts[0];
            int serverPort = Integer.parseInt(serverParts[1]);

            datagramSocket.send(buffer, serverPort, serverIp, asyncResult -> {
                if (asyncResult.succeeded()) {
                    log.info("P2P注册请求已发送: proxyId={}", proxyId);
                } else {
                    log.error("P2P注册请求发送失败: proxyId={}", proxyId, asyncResult.cause());
                    handleRegistrationFailed("注册请求发送失败");
                }
            });

        } catch (Exception e) {
            log.error("P2P注册异常: proxyId={}", proxyId, e);
            handleRegistrationFailed("注册异常: " + e.getMessage());
        }
    }

    /**
     * 处理注册失败
     */
    private void handleRegistrationFailed(String reason) {
        log.error("P2P注册失败: proxyId={}, reason={}", proxyId, reason);

        // 尝试重连
        if (reconnectCount.incrementAndGet() < maxReconnectTimes) {
            log.info("尝试重新注册: proxyId={}, reconnectCount={}", proxyId, reconnectCount.get());
            vertx.setTimer(5000, id -> registerToServer());
        } else {
            state = P2PState.FAILED;
            log.error("P2P注册重连次数超限: proxyId={}, maxReconnectTimes={}",
                    proxyId, maxReconnectTimes);
            if (callback != null) {
                callback.onFailed("注册失败: " + reason);
            }
        }
    }

    /**
     * 处理UDP数据包
     */
    private void handlePacket(DatagramPacket packet) {
        try {
            Buffer buffer = packet.data();
            if (buffer.length() < 1) {
                return;
            }

            byte msgTypeCode = buffer.getByte(0);

            // 处理P2P服务器响应
            byte[] jsonBytes = new byte[buffer.length() - 1];
            System.arraycopy(buffer.getBytes(), 1, jsonBytes, 0, jsonBytes.length);

            JsonObject json = new JsonObject(new String(jsonBytes));
            String type = json.getString("type");

            log.debug("收到P2P数据包: type={}, from={}:{}", type, packet.sender().host(), packet.sender().port());

            switch (type) {
                case "p2p_register_response":
                    handleRegisterResponse(packet, json);
                    break;
                case "p2p_hole_punch_response":
                    handleHolePunchResponse(json);
                    break;
                case "p2p_hole_punch_notify":
                    handleHolePunchNotify(json);
                    break;
                case "p2p_heartbeat_response":
                    handleHeartbeatResponse();
                    break;
                default:
                    log.warn("未知的P2P数据包类型: {}", type);
            }

        } catch (Exception e) {
            log.error("处理P2P数据包异常", e);
        }
    }

    /**
     * 处理注册响应
     */
    private void handleRegisterResponse(DatagramPacket packet, JsonObject json) {
        if (!json.getBoolean("success", false)) {
            handleRegistrationFailed(json.getString("error", "注册失败"));
            return;
        }

        state = P2PState.REGISTERED;

        // 保存自己的公网地址
        String publicIp = json.getString("public_ip");
        int publicPort = json.getInteger("public_port");

        log.info("P2P注册成功: proxyId={}, public={}:{}", proxyId, publicIp, publicPort);

        // 开始打洞
        startHolePunch();
    }

    /**
     * 开始NAT打洞
     */
    private void startHolePunch() {
        state = P2PState.HOLE_PUNCHING;
        holePunchStartTime.set(System.currentTimeMillis());

        log.info("开始NAT打洞: proxyId={}", proxyId);

        // 发送打洞请求（寻找对等端）
        // 注意：这里简化了逻辑，实际应用中需要知道对等端的client_id
        // 暂时假设对等端会通过服务器找到我们

        // 设置打洞超时检测
        vertx.setTimer(HOLE_PUNCH_TIMEOUT, id -> {
            if (state == P2PState.HOLE_PUNCHING) {
                log.warn("P2P打洞超时: proxyId={}", proxyId);
                handleHolePunchFailed("打洞超时");
            }
        });
    }

    /**
     * 处理打洞响应
     */
    private void handleHolePunchResponse(JsonObject json) {
        if (!json.getBoolean("success", false)) {
            handleHolePunchFailed(json.getString("error", "打洞失败"));
            return;
        }

        // 获取目标对等端信息
        targetPublicIp = json.getString("target_public_ip");
        targetPublicPort = json.getInteger("target_public_port");
        targetLocalIp = json.getString("target_local_ip");
        targetLocalPort = json.getInteger("target_local_port");

        log.info("获取到打洞目标信息: proxyId={}, targetPublic={}:{}, targetLocal={}:{}",
                proxyId, targetPublicIp, targetPublicPort, targetLocalIp, targetLocalPort);

        // 更新P2P隧道的目标地址
        p2pTunnel = new P2PTunnel(proxyId, targetPublicIp, targetPublicPort) {
            @Override
            protected void onDataReceived(byte[] data) {
                handleTunnelData(data);
            }
        };
        p2pTunnel.setDatagramSocket(datagramSocket);

        // 启动P2P隧道
        p2pTunnel.start();

        // 向目标对等端发送打洞包
        sendHolePunchPacket();
    }

    /**
     * 发送打洞包
     */
    private void sendHolePunchPacket() {
        try {
            JsonObject holePunchJson = new JsonObject()
                    .put("type", "p2p_hole_punch")
                    .put("client_id", clientId)
                    .put("proxy_id", proxyId);

            Buffer buffer = Buffer.buffer()
                    .appendByte((byte) 0x02) // HOLE_PUNCH消息类型
                    .appendBytes(holePunchJson.encode().getBytes());

            datagramSocket.send(buffer, targetPublicPort, targetPublicIp, asyncResult -> {
                if (asyncResult.succeeded()) {
                    log.info("P2P打洞包已发送: proxyId={}, target={}:{}",
                            proxyId, targetPublicIp, targetPublicPort);
                } else {
                    log.error("P2P打洞包发送失败", asyncResult.cause());
                }
            });

        } catch (Exception e) {
            log.error("发送P2P打洞包异常", e);
        }
    }

    /**
     * 处理打洞通知
     */
    private void handleHolePunchNotify(JsonObject json) {
        String notifyClientId = json.getString("client_id");
        String notifyIp = json.getString("public_ip");
        int notifyPort = json.getInteger("public_port");

        log.info("收到打洞通知: proxyId={}, from={}:{}", proxyId, notifyIp, notifyPort);

        // 保存对等端信息
        targetPublicIp = notifyIp;
        targetPublicPort = notifyPort;

        // 更新P2P隧道
        p2pTunnel = new P2PTunnel(proxyId, targetPublicIp, targetPublicPort) {
            @Override
            protected void onDataReceived(byte[] data) {
                handleTunnelData(data);
            }
        };
        p2pTunnel.setDatagramSocket(datagramSocket);

        // 启动P2P隧道
        p2pTunnel.start();

        // 状态更新为已连接
        handleP2PConnected();
    }

    /**
     * 处理P2P连接成功
     */
    private void handleP2PConnected() {
        state = P2PState.CONNECTED;

        long punchTime = System.currentTimeMillis() - holePunchStartTime.get();
        log.info("P2P连接成功: proxyId={}, punchTime={}ms", proxyId, punchTime);

        // 启动本地代理服务器
        startLocalProxyServer();

        // 启动心跳
        startHeartbeat();

        // 通知回调
        if (callback != null) {
            callback.onConnected();
        }
    }

    /**
     * 处理打洞失败
     */
    private void handleHolePunchFailed(String reason) {
        log.error("P2P打洞失败: proxyId={}, reason={}", proxyId, reason);

        // 尝试回退到中转模式
        state = P2PState.FAILED;

        if (callback != null) {
            callback.onFailed("打洞失败: " + reason + "，请使用中转模式");
        }
    }

    /**
     * 处理心跳响应
     */
    private void handleHeartbeatResponse() {
        log.debug("收到心跳响应: proxyId={}", proxyId);
        // 心跳响应处理
    }

    /**
     * 启动本地代理服务器
     * 打洞成功后，在本地端口上监听 TCP 连接，将请求通过 P2P 隧道直连原始服务
     */
    private void startLocalProxyServer() {
        try {
            localProxyServer = new LocalProxyServer(localAccessPort, proxyId, vertx, p2pTunnel);
            localProxyServer.start();

            log.info("本地代理服务器已启动: proxyId={}, localPort={}, targetService={}:{}",
                    proxyId, localAccessPort, localIp, localPort);

        } catch (Exception e) {
            log.error("启动本地代理服务器失败: proxyId={}", proxyId, e);
        }
    }

    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        heartbeatTimerId = vertx.setPeriodic(30000, id -> {
            if (p2pTunnel != null && p2pTunnel.getState() == P2PTunnel.TunnelState.CONNECTED) {
                p2pTunnel.sendHeartbeat();
                log.debug("发送P2P心跳: proxyId={}", proxyId);
            }
        });
    }

    /**
     * 处理隧道数据
     */
    private void handleTunnelData(byte[] data) {
        if (callback != null) {
            callback.onDataReceived(data);
        }
    }

    /**
     * 发送数据到对等端
     */
    public boolean sendData(byte[] data) {
        if (state != P2PState.CONNECTED) {
            log.warn("P2P未连接，无法发送数据: proxyId={}, state={}", proxyId, state);
            return false;
        }

        if (p2pTunnel == null) {
            log.warn("P2P隧道不存在: proxyId={}", proxyId);
            return false;
        }

        return p2pTunnel.sendData(data);
    }

    // Getters
    public P2PState getState() {
        return state;
    }

    public String getProxyId() {
        return proxyId;
    }

    public LocalProxyServer getLocalProxyServer() {
        return localProxyServer;
    }
}