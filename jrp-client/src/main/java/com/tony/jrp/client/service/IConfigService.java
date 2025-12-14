package com.tony.jrp.client.service;

import io.vertx.config.ConfigStoreOptions;
import io.vertx.ext.web.RoutingContext;

import java.util.function.Supplier;

public interface IConfigService {
    /**
     * 默认代理配置信息
     */
    String DEFAULT_JSON_CONFIG = "{\n" +
            "  \"path\": \"/jrp-client\",\n" +
            "  \"port\": 8000,\n" +
            "  \"remote_proxies\": [\n" +
            "    {\n" +
            "      \"name\": \"测试\",\n" +
            "      \"proxy_pass\": \"http://127.0.0.1:8000\",\n" +
            "      \"type\": \"HTTP\",\n" +
            "      \"remote_port\": 8001\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    ConfigStoreOptions getConfigStore();

    /**
     * 获取代理列表
     *
     * @param ctx 上下文
     */
    void list(RoutingContext ctx);

    /**
     * 保存代理列表
     *
     * @param ctx 上下文
     */
    void save(RoutingContext ctx);

    /**
     * 结束
     *
     * @param action 操作
     * @param ctx    上下文
     */
    void end(Supplier<String> action, RoutingContext ctx);
}
