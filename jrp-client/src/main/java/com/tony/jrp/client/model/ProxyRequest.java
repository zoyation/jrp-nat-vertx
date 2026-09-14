package com.tony.jrp.client.model;

import com.tony.jrp.common.enums.ProxyProto;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetSocket;
import lombok.Getter;
import lombok.Setter;

/**
 * @author tony
 * @date 2025/12/18
 * 正向代理UDP请求信息
 */
@Getter
public class ProxyRequest {
    /**
     * 用户客户端socket
     */
    private final NetSocket socket;
    /**
     * 代理协议
     */
    private final ProxyProto proxyProto;
    /**
     * 目标IP
     */
    private final byte[] dstIP;
    /**
     * 目标主机
     */
    private final String targetHost;
    /**
     * 目标端口
     */
    private final byte[] portBytes;
    /**
     * 目标端口
     */
    private final int targetPort;

    /**
     * 是否内网代理客户端创建连接是否成功
     */
    @Setter
    private boolean tunneled;

    public ProxyRequest(NetSocket socket, ProxyProto proxyProto, String targetHost, byte[] dstIP, byte[] portBytes) {
        this.socket = socket;
        this.proxyProto = proxyProto;
        this.targetHost = targetHost;
        this.dstIP = dstIP;
        this.portBytes = portBytes;
        this.targetPort = Byte.toUnsignedInt(portBytes[0]) * 256 + Byte.toUnsignedInt(portBytes[1]);
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
    public static ProxyRequest createTcpRequest(NetSocket socket, ProxyProto socksProxyProto, String targetHost, byte[] dstIP, byte[] targetPort) {
        return new TcpRequest(socket, socksProxyProto, targetHost, dstIP, targetPort);
    }

    /**
     * 创建正向代理请求
     *
     * @param msgId      消息ID
     * @param socket     用户客户端tcp socket
     * @param udpSocket  服务端UDPSocket
     * @param targetHost 目标主机
     * @param dstIP      目标IP
     * @param targetPort 目标端口
     * @return 向代理请求信息
     */
    public static ProxyRequest createUdpRequest(Buffer msgId, NetSocket socket, DatagramSocket udpSocket, String targetHost, byte[] dstIP, byte[] targetPort) {
        return new UdpRequest(msgId, socket, udpSocket, targetHost, dstIP, targetPort);
    }

    public Future<Void> close() {
        return socket.close();
    }
}
