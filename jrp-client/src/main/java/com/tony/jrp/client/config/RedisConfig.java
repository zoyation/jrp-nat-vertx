package com.tony.jrp.client.config;

import io.vertx.redis.client.RedisClientType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * redis配置信息
 */
@Data
public class RedisConfig {
    /**
     * 客户端类型
     */
    private RedisClientType clientType;
    /**
     * redis服务数据库
     */
    private Integer database = 0;
    /**
     * redis服务地址，替换host、port、username、password
     * 格式redis://[:password@]host[:port][/db-number]
     */
    private String url;
    /**
     * redis服务地址
     */
    private String host = "localhost";
    /**
     * redis服务端口
     */
    private Integer port = 6379;
    /**
     * redis服务用户名
     */
    private String username;
    /**
     * redis服务用户密码
     */
    private String password;
    /**
     * redis服务集群主节点
     */
    private String master;

    /**
     * redis服务集群节点，格式：host:port
     */
    private List<String> nodes = new ArrayList<>(0);
}
