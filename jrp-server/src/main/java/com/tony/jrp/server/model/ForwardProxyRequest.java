package com.tony.jrp.server.model;

import com.tony.jrp.common.enums.SocksProxyProto;
import io.vertx.core.Future;
import io.vertx.core.net.NetSocket;
import lombok.Data;

/**
 * @author tony
 * @date 2025/12/03
 * 正向代理请求信息
 */
@Data
public class ForwardProxyRequest {
    /**
     * 用户客户端socket
     */
    private NetSocket socket;
    /**
     * 代理协议
     */
    private SocksProxyProto proxyProto;
    /**
     * 目标主机
     */
    private String targetHost;
    /**
     * 目标端口
     */
    private int targetPort;

    /**
     * 是否内网代理客户端创建连接是否成功
     */
    private boolean tunnelStatus;

    private ForwardProxyRequest(NetSocket socket, SocksProxyProto proxyProto, String targetHost, int targetPort) {
        this.socket = socket;
        this.proxyProto = proxyProto;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public static ForwardProxyRequest create(NetSocket socket, SocksProxyProto socksProxyProto, String targetHost, int targetPort) {
        return new ForwardProxyRequest(socket, socksProxyProto, targetHost, targetPort);
    }

    public Future<Void> close() {
        return socket.close();
    }
}
