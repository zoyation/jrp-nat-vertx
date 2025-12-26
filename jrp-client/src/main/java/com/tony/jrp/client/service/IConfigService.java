package com.tony.jrp.client.service;

import io.vertx.config.ConfigStoreOptions;
import io.vertx.ext.web.RoutingContext;

import java.util.function.Supplier;

public interface IConfigService {
    /**
     * 默认代理配置信息
     */
    String DEFAULT_JSON_CONFIG = "{\n" +
            "  \"path\": \"jrp-client\",\n" +
            "  \"port\": 8000,\n" +
            "  \"remote_proxies\": [\n" +
            "    {\n" +
            "      \"id\": \"1\",\n" +
            "      \"name\": \"HTTP配置页面映射\",\n" +
            "      \"proxy_pass\": \"http://127.0.0.1:8000\",\n" +
            "      \"type\": \"HTTP\",\n" +
            "      \"remote_port\": 8001\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": \"2\",\n" +
            "      \"name\": \"windows远程mstsc映射\",\n" +
            "      \"proxy_pass\": \"127.0.0.1:3389\",\n" +
            "      \"type\": \"TCP\",\n" +
            "      \"remote_port\": 13389\n" +
            "    },\n" +
            "    {\n" +
            "      \"id\": \"3\",\n" +
            "      \"name\": \"智能代理（同时支持http、https、socks代理穿透）\",\n" +
            "      \"type\": \"SMART_PROXY\",\n" +
            "      \"remote_port\": 1080\n" +
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
