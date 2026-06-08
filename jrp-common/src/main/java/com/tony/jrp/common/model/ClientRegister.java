package com.tony.jrp.common.model;

import lombok.Data;

import java.util.List;

/**
 * 客户端穿透配置信息
 */
@Data
public class ClientRegister {
    /**
     * 被代理客户端唯一标识
     */
    private String id;
    /**
     * 被代理客户端名称
     */
    private String name;
    /**
     * 认证信息
     */
    private String token;
    /**
     * 穿透成功后，访问认证用户名，如果没配置会使用服务端里面配置的认证信息
     */
    private String username;
    /**
     * 穿透成功后，访问认证密码，如果没配置会使用服务端里面配置的认证信息
     */
    private String password;
    /**
     * 是否已经完成更新（端口动态生成时，完成更新保存配置，不需要再发送更新消息），true表示已经更新，false表示未更新
     */
    private boolean updated = false;
    /**
     * 客户端穿透配置列表
     */
    private List<ClientProxy> proxies;
}
