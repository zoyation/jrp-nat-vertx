package com.tony.jrp.client.model;

import com.tony.jrp.common.enums.ProxyProto;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetSocket;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author tony
 * @date 2025/12/18
 * 正向代理UDP请求信息
 */
public class UdpRequest extends ProxyRequest {
    /**
     * 当前 UDP Socket
     */
    @Getter
    private final Buffer msgId;
    @Getter
    private final DatagramSocket udpSocket;
    /**
     * TCP关联的UDP Socket，key为TCP socket hashCode，value为UDP socket map
     */
    private static final Map<Integer, Map<Integer, DatagramSocket>> ASSOCIATE_UDP_MAP = new ConcurrentHashMap<>();

    /**
     * 创建正向代理请求
     *
     * @param msgId      消息ID
     * @param socket     用户客户端socket
     * @param udpSocket  当前 UDP Socket
     * @param targetHost 目标主机
     * @param dstIP      目标IP
     * @param portBytes  目标端口
     * @return 向代理请求信息
     */
    public UdpRequest(Buffer msgId, NetSocket socket, DatagramSocket udpSocket, String targetHost, byte[] dstIP, byte[] portBytes) {
        super(socket, ProxyProto.SOCK5_UDP, targetHost, dstIP, portBytes);
        this.msgId = msgId;
        this.udpSocket = udpSocket;
        ASSOCIATE_UDP_MAP.computeIfAbsent(socket.hashCode(), k -> new ConcurrentHashMap<>())
                .put(udpSocket.hashCode(), udpSocket);
    }

    /**
     * 关闭关联的UDP Socket
     *
     * @param tcpHashCode TCP socket hashCode
     */
    public static Set<Integer> closeByTcpHashCode(int tcpHashCode) {
        if (!ASSOCIATE_UDP_MAP.isEmpty()) {
            //关闭关联的UDP Socket
            Map<Integer, DatagramSocket> datagramSocketMap = ASSOCIATE_UDP_MAP.get(tcpHashCode);
            datagramSocketMap.forEach((k, v) -> v.close());
            ASSOCIATE_UDP_MAP.remove(tcpHashCode);
            return datagramSocketMap.keySet();
        }
        return Collections.emptySet();
    }

    public Future<Void> close() {
        if (!ASSOCIATE_UDP_MAP.isEmpty()) {
            //关闭关联的UDP Socket
            int hashCode = this.getSocket().hashCode();
            ASSOCIATE_UDP_MAP.get(hashCode).forEach((k, v) -> v.close());
            ASSOCIATE_UDP_MAP.remove(hashCode);
        }
        return Future.succeededFuture();
        //tcp socket关闭时会触发close方法，所以不调用super.close()关闭tcp socket，否则会触发tcp socket的closeHandler
        //super.close();
    }
}
