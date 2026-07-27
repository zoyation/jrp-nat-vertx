package com.tony.jrp.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(
        proxyBeanMethods = false
)
@ConfigurationProperties("vertx.jrp")
@Data
public class ProxyClientProperties {
    /**
     * 配置文件来源
     */
    private String configStoreType = "file";
    /**
     * 注册服务地址
     */
    private String registerAddress;
    /**
     * 穿透中转websocket是否启用ssl
     */
    private Boolean ssl = Boolean.FALSE;
    /**
     * 注册认证信息
     */
    private String token;
    /**
     * 断线重连次数
     */
    private Integer reconnectionTimes = 6 * 100;
    /**
     * 穿透成功后，访问认证用户名，如果没配置会使用服务端里面配置的认证信息
     */
    private String username;
    /**
     * 穿透成功后，访问认证密码，如果没配置会使用服务端里面配置的认证信息
     */
    private String password;
    /**
     * P2P打洞端口，用于NAT打洞
     */
    private Integer p2pPort = 3000;
    /**
     * 用户模式启动，启用后用户访问127.0.0.1:本地端口通过P2P隧道直连
     */
    private Boolean userMode = Boolean.FALSE;
    /**
     * 用户模式本地端口起始范围
     */
    private Integer userModePortStart = 5000;
    /**
     * 用户模式本地端口结束范围
     */
    private Integer userModePortEnd = 6000;
    /**
     * P2P连接重试次数
     */
    private Integer p2pReconnectTimes = 3;

    /**
     * redis 配置
     */
    private RedisConfig redis;
}
