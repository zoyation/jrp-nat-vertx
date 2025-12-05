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
     * 目标IP
     */
    private byte[] dstIP;
    /**
     * 目标主机
     */
    private String targetHost;
    /**
     * 目标端口
     */
    private byte[] targetPort;

    /**
     * 是否内网代理客户端创建连接是否成功
     */
    private boolean tunneled;

    private ForwardProxyRequest(NetSocket socket, SocksProxyProto proxyProto, String targetHost, byte[] dstIP, byte[] targetPort) {
        this.socket = socket;
        this.proxyProto = proxyProto;
        this.targetHost = targetHost;
        this.dstIP = dstIP;
        this.targetPort = targetPort;
    }

    /**
     * 创建正向代理请求
     *
     * @param socket          用户客户端socket
     * @param socksProxyProto 代理协议
     * @param targetHost      目标主机
     * @param dstIP           目标IP
     * @param targetPort      目标端口
     * @return 向代理请求信息
     */
    public static ForwardProxyRequest create(NetSocket socket, SocksProxyProto socksProxyProto, String targetHost, byte[] dstIP, byte[] targetPort) {
        return new ForwardProxyRequest(socket, socksProxyProto, targetHost, dstIP, targetPort);
    }

    public Future<Void> close() {
        return socket.close();
    }
}
