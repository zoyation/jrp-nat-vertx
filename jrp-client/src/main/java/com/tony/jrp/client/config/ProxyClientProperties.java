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
     * redis 配置
     */
    private RedisConfig redis;
}
