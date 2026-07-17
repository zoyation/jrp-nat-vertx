package com.tony.jrp.common.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 路由规则，仅用于HTTP/HTTPS穿透类型。
 * 每条规则定义一个路径前缀(location)和对应的本地原始服务(proxy_pass)。
 * 继承ClientProxy以复用proxy_pass、host、port、https等字段，便于穿透处理器直接使用。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class RouteRule extends ClientProxy {
    /**
     * 路由路径前缀。
     * 例如：/api 匹配所有以/api开头的请求。
     * 为空或"/"时匹配所有请求（默认路由），优先级最低。
     */
    private String location;
}
