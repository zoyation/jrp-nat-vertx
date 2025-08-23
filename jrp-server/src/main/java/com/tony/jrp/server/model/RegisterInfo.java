package com.tony.jrp.server.model;

import com.tony.jrp.common.model.ClientProxy;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

/**
 * 连接注册信息
 */
@Data
public class RegisterInfo {
    private String id;
    /**
     * 注册客户端外网主机
     */
    private String host;
    /**
     * 注册客户端外网端口
     */
    private Integer port;
    /**
     * 穿透客户端唯一标识
     */
    private String client_id;
    /**
     * 穿透客户端名称
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
     * 客户端穿透配置列表
     */
    private List<ClientProxy> proxies;
    /**
     * 注册时间
     */
    private Timestamp register_time;
    /**
     * 下线时间
     */
    private Timestamp offline_time;
    /**
     * 状态 0-异常，1-在线，2-下线
     */
    private Integer status;
    /**
     * 备注
     */
    private String remark;
}
