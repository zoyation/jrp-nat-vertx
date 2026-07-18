package com.tony.jrp.common.model;

import com.tony.jrp.common.enums.ServiceType;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

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
     * proxy_pass中的路径前缀（如 http://host:8080/app 中的 /app）。
     * 类似nginx的proxy_pass URI，转发时会将请求路径加上该前缀。
     */
    private String path;
    /**
     * 穿透类型 默认HTTP
     */
    private ServiceType type = ServiceType.HTTP;
    /**
     * 穿透外网访问端口
     */
    private Integer remote_port;
    /**
     * 是否启用路由规则，仅HTTP/HTTPS类型有效。
     * 启用后可通过routes配置多条路由规则，按路径前缀转发到不同本地服务。
     * 启用路由规则时，不能设置proxy_pass（本地服务地址）。
     */
    private boolean enable_route_rules = false;
    /**
     * 路由规则列表，仅HTTP/HTTPS类型生效。
     * 每条规则定义一个路径前缀和对应的本地原始服务。
     * 请求通过最长前缀匹配选择对应路由规则转发到不同原始服务。
     * 为空时所有请求转发到proxy_pass配置的默认服务。
     */
    private List<RouteRule> routes;
    /**
     * 是否启用，默认开启。
     */
    private boolean enable = true;

}
