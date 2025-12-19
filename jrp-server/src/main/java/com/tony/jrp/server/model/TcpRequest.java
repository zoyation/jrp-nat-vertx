package com.tony.jrp.server.model;

import com.tony.jrp.common.enums.ProxyProto;
import io.vertx.core.net.NetSocket;

/**
 * @author tony
 * @date 2025/12/03
 * 正向代理请求信息
 */
public class TcpRequest extends ProxyRequest {
    public TcpRequest(NetSocket socket, ProxyProto proxyProto, String targetHost, byte[] dstIP, byte[] portBytes) {
        super(socket, proxyProto, targetHost, dstIP, portBytes);
    }
}
