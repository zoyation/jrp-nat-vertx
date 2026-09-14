package com.tony.jrp.common.model;

import com.tony.jrp.common.enums.ServiceType;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户端p2p访问信息
 */
@Data
public class UserProxy implements Serializable {
    /**
     * 代理标识
     */
    private String id;
    /**
     * 服务名称
     */
    private String name;

    /**
     * 穿透类型 默认HTTP
     */
    private ServiceType type = ServiceType.HTTP;
    /**
     * 复用端口，穿透外网访问端口，服务端初始化的p2p打洞端口
     */
    private Integer remote_port;
    /**
     * 本地端口，打洞成功后本地代理该端口，通过打洞隧道直连转发数据
     */
    private Integer local_port;
    /**
     * 是否启用，默认开启。
     */
    private boolean enable = true;

}
