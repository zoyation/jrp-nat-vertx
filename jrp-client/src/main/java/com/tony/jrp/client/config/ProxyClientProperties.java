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
    private Integer reconnectionTimes = 6*100;
}
