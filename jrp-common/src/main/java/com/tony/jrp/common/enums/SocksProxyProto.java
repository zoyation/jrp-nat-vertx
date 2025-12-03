package com.tony.jrp.common.enums;

import lombok.Getter;

import java.io.Serializable;

/**
 * 正向代理服务协议枚举
 * HTTP: HTTP或HTTPS代理协议
 * SOCK4_TCP: socks4 TCP代理协议
 * SOCK5_TCP: socks5 TCP代理协议
 * SOCK5_UDP: socks5 UDP代理协议
 * UDP: socks5 UDP代理
 */
@Getter
public enum SocksProxyProto implements Serializable {
    HTTP((byte) 0X01),
    HTTPS((byte) 0X02),
    SOCK4_TCP((byte) 0X03),
    SOCK4A_TCP((byte) 0X04),
    SOCK5_TCP((byte) 0X05),
    SOCK5_UDP((byte) 0X06);
    /**
     * 穿透协议
     */
    private final byte proto;

    /**
     * 构造函数
     *
     * @param proto 穿透协议
     */
    SocksProxyProto(byte proto) {
        this.proto = proto;
    }

    /**
     * 根据穿透协议获取枚举
     *
     * @param proto 穿透协议
     * @return SocksProxyProto
     */
    public static SocksProxyProto getByProto(byte proto) {
        for (SocksProxyProto value : values()) {
            if (value.proto == proto) {
                return value;
            }
        }
        return null;
    }
}
