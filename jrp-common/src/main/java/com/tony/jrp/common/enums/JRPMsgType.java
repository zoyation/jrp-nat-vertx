package com.tony.jrp.common.enums;

import lombok.Getter;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 消息类型
 */
@Getter
public enum JRPMsgType implements Serializable {
    REGISTER("JRP0"),
    REGISTER_RESULT("JRP1"),
    GET("JRP2"),
    RESPONSE("JRP3"),
    CLOSE("JRP4"),
    WEBSOCKET_GET("JRP5");
    private final String code;
    public static final int TYPE_LEN = 4;

    JRPMsgType(String code) {
        this.code = code;
    }

    public static JRPMsgType getByCode(String code) {
        return Arrays.stream(JRPMsgType.values()).filter(r -> r.code.equals(code)).findFirst().orElse(null);
    }
}