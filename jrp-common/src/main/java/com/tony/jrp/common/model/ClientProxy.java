package com.tony.jrp.common.model;
import com.tony.jrp.common.enums.ServiceType;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;

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
     * 代理名称
     */
    private String name;
    /**
     * 代理类型
     */
    private ServiceType type;
    /**
     * 内网服务端口
     */
    private Integer port;
    /**
     * 外网转发服务器代理端口
     */
    private Integer remote_port;

    private String proxy_pass;
}
