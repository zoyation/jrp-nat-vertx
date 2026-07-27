package com.tony.jrp.client.server;

import com.tony.jrp.client.tunnel.P2PTunnel;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地反向代理服务器
 * 监听本地端口，将本地请求通过P2P隧道转发到内网服务
 */
@Slf4j
public class LocalProxyServer {

    /**
     * 服务器配置
     */
    private final int localPort;
    private final String proxyId;
    private final Vertx vertx;
    private final P2PTunnel p2pTunnel;

    /**
     * NetServer实例
     */
    private NetServer netServer;

    /**
     * 客户端连接映射
     */
    private final Map<String, NetSocket> clientConnections = new ConcurrentHashMap<>();

    /**
     * 是否正在运行
     */
    private volatile boolean running = false;

    public LocalProxyServer(int localPort, String proxyId, Vertx vertx, P2PTunnel p2pTunnel) {
        this.localPort = localPort;
        this.proxyId = proxyId;
        this.vertx = vertx;
        this.p2pTunnel = p2pTunnel;
    }

    /**
     * 启动本地代理服务器
     */
    public void start() {
        if (running) {
            log.warn("本地代理服务器已在运行: port={}", localPort);
            return;
        }

        try {
            NetServerOptions options = new NetServerOptions()
                    .setPort(localPort)
                    .setHost("127.0.0.1")
                    .setTcpKeepAlive(true)
                    .setIdleTimeout(300); // 5分钟空闲超时

            netServer = vertx.createNetServer(options);

            netServer.connectHandler(socket -> {
                String connectionId = socket.writeHandlerID();
                log.info("收到本地连接: port={}, connectionId={}", localPort, connectionId);

                // 存储客户端连接
                clientConnections.put(connectionId, socket);

                // 处理来自客户端的数据
                socket.handler(data -> {
                    log.debug("收到本地客户端数据: port={}, connectionId={}, dataLength={}",
                            localPort, connectionId, data.length());
                    handleClientData(connectionId, data);
                });

                // 处理连接关闭
                socket.closeHandler(v -> {
                    log.info("本地连接关闭: port={}, connectionId={}", localPort, connectionId);
                    clientConnections.remove(connectionId);
                });

                // 处理异常
                socket.exceptionHandler(e -> {
                    log.error("本地连接异常: port={}, connectionId={}", localPort, connectionId, e);
                    socket.close();
                });
            });

            netServer.listen(result -> {
                if (result.succeeded()) {
                    running = true;
                    log.info("本地代理服务器启动成功: port={}, proxyId={}", localPort, proxyId);
                } else {
                    log.error("本地代理服务器启动失败: port={}", localPort, result.cause());
                    running = false;
                }
            });

        } catch (Exception e) {
            log.error("启动本地代理服务器异常: port={}", localPort, e);
            running = false;
        }
    }

    /**
     * 停止本地代理服务器
     */
    public void stop() {
        if (!running) {
            return;
        }

        log.info("停止本地代理服务器: port={}", localPort);
        running = false;

        // 关闭所有客户端连接
        for (NetSocket socket : clientConnections.values()) {
            try {
                socket.close();
            } catch (Exception e) {
                log.warn("关闭客户端连接失败", e);
            }
        }
        clientConnections.clear();

        // 关闭服务器
        if (netServer != null) {
            netServer.close(result -> {
                if (result.succeeded()) {
                    log.info("本地代理服务器已停止: port={}", localPort);
                } else {
                    log.error("停止本地代理服务器失败: port={}", localPort, result.cause());
                }
            });
        }
    }

    /**
     * 处理客户端数据
     */
    private void handleClientData(String connectionId, Buffer data) {
        if (p2pTunnel == null || p2pTunnel.getState() != P2PTunnel.TunnelState.CONNECTED) {
            log.warn("P2P隧道未连接，无法转发数据: connectionId={}, state={}",
                    connectionId,
                    p2pTunnel != null ? p2pTunnel.getState() : "null");

            // 发送错误响应给客户端
            sendErrorResponse(connectionId, "P2P隧道未连接");
            return;
        }

        try {
            // 封装数据，包含连接ID
            byte[] connectionIdBytes = connectionId.getBytes();
            byte[] dataBytes = data.getBytes();

            byte[] combinedData = new byte[connectionIdBytes.length + dataBytes.length];
            System.arraycopy(connectionIdBytes, 0, combinedData, 0, connectionIdBytes.length);
            System.arraycopy(dataBytes, 0, combinedData, connectionIdBytes.length, dataBytes.length);

            // 通过P2P隧道发送数据
            boolean success = p2pTunnel.sendData(combinedData);

            if (!success) {
                log.error("通过P2P隧道发送数据失败: connectionId={}", connectionId);
                sendErrorResponse(connectionId, "发送失败");
            }

        } catch (Exception e) {
            log.error("处理客户端数据异常: connectionId={}", connectionId, e);
            sendErrorResponse(connectionId, "处理异常: " + e.getMessage());
        }
    }

    /**
     * 发送数据给客户端
     */
    public void sendDataToClient(String connectionId, Buffer data) {
        NetSocket socket = clientConnections.get(connectionId);
        if (socket == null) {
            log.warn("客户端连接不存在: connectionId={}", connectionId);
            return;
        }

        try {
            socket.write(data);
            log.debug("发送数据给客户端: connectionId={}, dataLength={}", connectionId, data.length());
        } catch (Exception e) {
            log.error("发送数据给客户端失败: connectionId={}", connectionId, e);
            socket.close();
        }
    }

    /**
     * 发送错误响应给客户端
     */
    private void sendErrorResponse(String connectionId, String message) {
        NetSocket socket = clientConnections.get(connectionId);
        if (socket != null) {
            try {
                Buffer errorBuffer = Buffer.buffer("HTTP/1.1 503 Service Unavailable\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + message.length() + "\r\n" +
                        "\r\n" +
                        message);
                socket.write(errorBuffer);
            } catch (Exception e) {
                log.error("发送错误响应失败: connectionId={}", connectionId, e);
            }
        }
    }

    /**
     * 获取连接数量
     */
    public int getConnectionCount() {
        return clientConnections.size();
    }

    /**
     * 获取本地端口
     */
    public int getLocalPort() {
        return localPort;
    }

    /**
     * 获取代理ID
     */
    public String getProxyId() {
        return proxyId;
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 关闭指定连接
     */
    public void closeConnection(String connectionId) {
        NetSocket socket = clientConnections.remove(connectionId);
        if (socket != null) {
            try {
                socket.close();
                log.info("关闭客户端连接: connectionId={}", connectionId);
            } catch (Exception e) {
                log.warn("关闭客户端连接失败: connectionId={}", connectionId, e);
            }
        }
    }
}