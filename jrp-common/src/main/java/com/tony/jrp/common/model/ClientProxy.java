package com.tony.jrp.common.model;

import com.tony.jrp.common.enums.ServiceType;
import lombok.Data;

import java.io.Serializable;

/**
 * 内网客户端代理信息
 */
@Data
public class ClientProxy implements Serializable {
    /**
     * 代理标识
     */
    private String id;
    /**
     * 服务名称
     */
    private String name;
    /**
     * 服务地址
     */
    private String proxy_pass;
    /**
     * 是否https
     */
    private boolean https;
    /**
     * 服务ip/domain
     */
    private String host;
    /**
     * 服务端口
     */
    private Integer port;
    /**
     * 穿透类型 默认HTTP
     */
    private ServiceType type = ServiceType.HTTP;
    /**
     * 穿透外网访问端口
     */
    private Integer remote_port;


}
