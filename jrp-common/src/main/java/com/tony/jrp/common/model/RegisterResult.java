package com.tony.jrp.common.model;

import lombok.Data;

import java.util.List;

/**
 * 客户端注册结果
 */
@Data
public class RegisterResult {
    /**
     * 注册结果
     */
    private boolean success;
    /**
     * 结果描述
     */
    private String msg;
    /**
     * 客户端穿透配置列表
     */
    private List<ClientProxy> proxies;
    /**
     * 用户端p2p访问信息，用于直连穿透。
     */
    private List<UserProxy> userProxies;
    public RegisterResult() {

    }

    private RegisterResult(boolean success, String msg) {
        this.success = success;
        this.msg = msg;
    }

    public static RegisterResult result(boolean success, String msg) {
        return new RegisterResult(success, msg);
    }

    public static RegisterResult success() {
        return new RegisterResult(true, null);
    }

    public static RegisterResult success(String msg) {
        return new RegisterResult(true, msg);
    }

    public static RegisterResult error(String msg) {
        return new RegisterResult(false, msg);
    }
}
