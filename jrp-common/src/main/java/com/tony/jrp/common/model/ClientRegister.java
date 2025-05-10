package com.tony.jrp.common.model;

import lombok.Data;

import java.util.List;

/**
 * 客户端代理信息
 */
@Data
public class ClientRegister {
    /**
     * 被代理客户端唯一标识
     */
    private String id;
    /**
     * 认证信息
     */
    private String token;

    private List<ClientProxy> proxies;
}
