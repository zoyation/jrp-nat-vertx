package com.tony.jrp.common.enums;

import lombok.Getter;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 消息类型
 */
@Getter
public enum JRPMsgType implements Serializable {
    REGISTER((byte) 0X00),
    REGISTER_RESULT((byte) 0X01),
    RECEIVE((byte) 0X02),
    RESPONSE((byte) 0X03),
    CLOSE((byte) 0X04),
    WEBSOCKET_GET((byte) 0X05);
    private final byte code;
    public static final int TYPE_LEN = 1;

    JRPMsgType(byte code) {
        this.code = code;
    }

    public static JRPMsgType getByCode(byte code) {
        return Arrays.stream(JRPMsgType.values()).filter(r -> r.code == code).findFirst().orElse(null);
    }
}