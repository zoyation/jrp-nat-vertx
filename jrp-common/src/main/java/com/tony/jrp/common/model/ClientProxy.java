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
     * 服务名称
     */
    private String name;
    /**
     * 服务地址
     */
    private String proxy_pass;
    /**
     * 穿透类型
     */
    private ServiceType type;
    /**
     * 穿透外网访问端口
     */
    private Integer remote_port;
}
