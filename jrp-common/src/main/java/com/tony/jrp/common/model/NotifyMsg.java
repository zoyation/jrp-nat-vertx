package com.tony.jrp.common.model;

import lombok.Data;

/**
 * 通知消息实现功能：
 * 1.外网穿透服务端通过websocket连接(内网代理服务端注册代理时创建的)发送消息通知内网代理服务端有客户端请求（通过外网穿透服务端代理端口访问的请求）；
 * 2.内网代理服务端收到通知消息后，确定外网服务端口，用客户端请求的相同端口地址从外网穿透服务端去拉取客户端请求信息。
 * 3.内网服务端获取到客户端请求信息后，调用真实服务获取返回信息，请求同样代理端口将结果发送到服务端。
 */
@Data
public class NotifyMsg {
    private Integer port;
}
