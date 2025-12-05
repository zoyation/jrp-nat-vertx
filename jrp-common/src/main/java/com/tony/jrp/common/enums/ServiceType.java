package com.tony.jrp.common.enums;

import java.io.Serializable;

/**
 * 服务类型枚举
 * HTTP，HTTPS、TCP、UDP：反向代理方式转发访问，用户输入代理后的公网IP端口访问，一个客户端配置多个服务，一个服务配置一个端口，实现转发访问多个服务。
 * HTTP_PROXY、HTTPS_PROXY、SOCKS4、SOCKS5：正向代理方式转发访问，用户设备配置一个代理地址，用户直接输入内网服务地址访问，一个客户端只需要配置一个端口，实现转发访问多个服务。
 */
public enum ServiceType implements Serializable {
    HTTP,
    HTTPS,
    TCP,
    UDP,
    HTTP_PROXY,
    HTTPS_PROXY,
    SOCKS4,
    SOCKS5,
    SMART_PROXY
}
