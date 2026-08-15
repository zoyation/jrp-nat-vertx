package com.tony.jrp.common.enums;

import lombok.Getter;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 消息类型
 * REGISTER： 注册
 * REGISTER_RESULT： 注册结果
 * RECEIVE： 接收
 * RESPONSE： 响应
 * CLOSE： 关闭
 * PROXIES_UPDATE： 更新代理
 * PROXIES_UPDATE_RESULT： 更新代理结果
 * UDP_TUNNEL_REQUEST: 打洞请求
 * UDP_TUNNEL_RESPONSE: 打洞响应
 * UDP_TUNNEL_KEEPALIVE: 打洞成功
 */
@Getter
public enum JRPMsgType implements Serializable {
    REGISTER((byte) 0X00),
    REGISTER_RESULT((byte) 0X01),
    RECEIVE((byte) 0X02),
    RESPONSE((byte) 0X03),
    CLOSE((byte) 0X04),
    PROXIES_UPDATE((byte) 0X05),
    PROXIES_UPDATE_RESULT((byte) 0X06),
    UDP_TUNNEL_REQUEST((byte) 0X07),
    UDP_TUNNEL_RESPONSE((byte) 0X08),
    UDP_TUNNEL_KEEPALIVE((byte) 0X09);
    private final byte code;
    private final byte[] codeArray;
    public static final int TYPE_LEN = 1;
    public static final int TYPE_PORT_LEN = JRPMsgType.TYPE_LEN + 2;

    /**
     * 构造函数
     */
    JRPMsgType(byte code) {
        this.code = code;
        this.codeArray = new byte[]{code};
    }

    /**
     * 转换成字节数组
     */
    public byte[] codeArray() {
        return codeArray;
    }

    /**
     * 根据code获取枚举
     */
    public static JRPMsgType getByCode(byte code) {
        return Arrays.stream(JRPMsgType.values()).filter(r -> r.code == code).findFirst().orElse(null);
    }
}