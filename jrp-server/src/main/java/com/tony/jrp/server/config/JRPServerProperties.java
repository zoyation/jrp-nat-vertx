package com.tony.jrp.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties("vertx.jrp")
@Data
public class JRPServerProperties {
    /**
     * 中转服务端的websocket端口。
     * 作用1：内网服务通过连接该服务端口注册需要穿透的服务，成功后中转服务端缓存websocket连接信息。
     * 作用2：当服务端收到客户端请求后，通过对应websocket连接转发到对应被代理内网服务，内网代理服务根据收到的通知信息调用实际服务，
     */
    private Integer registerPort = 1024;
    /**
     * 管理页面http访问端口
     */
    private Integer pagePort = 10086;
    /**
     * 管理页面http访问路径
     */
    private String pagePath;
    /**
     * 配置管理页面用户名
     */
    private String username;
    /**
     * 配置管理页面密码
     */
    private String password;
    /**
     * 认证信息
     */
    private String token;
    /**
     * 访问认证类型：SHA256，MD5
     */
    private String algorithm="MD5";
    /**
     * 路由白名单
     */
    private String whiteUrl;
    /**
     * 每秒运行请求数
     */
    private Integer permitsPerSecond = Integer.MAX_VALUE;
    /**
     * 允许缓存的最大请求数
     */
    private Integer maxRequest = Integer.MAX_VALUE;
    /**
     * 请求缓存的最大时间
     */
    private Integer requestCacheSeconds = 60;
}
