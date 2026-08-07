package com.tony.jrp.client.service;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.UserProxy;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
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
            "      \"remote_port\": 8001,\n" +
            "      \"enable_p2p\": true\n" +
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
            "  \"user_proxies\":[]\n" +
            "}";

    ConfigStoreOptions getConfigStore();

    /**
     * 获取代理列表
     *
     * @param ctx 上下文
     */
    void listRemoteProxies(RoutingContext ctx);

    /**
     * 保存代理列表
     *
     * @param ctx 上下文
     */
    void saveRemoteProxies(RoutingContext ctx);

    /**
     * 保存配置信息
     *
     * @param list 配置信息列表
     * @return 配置信息
     */
    public int saveRemoteProxies(List<ClientProxy> list);

    /**
     * 获取p2p配置列表
     *
     * @param ctx 上下文
     */
    void listUserProxies(RoutingContext ctx);

    /**
     * 保存p2p配置列表
     *
     * @param ctx 上下文
     */
    void saveUserProxies(RoutingContext ctx);

    /**
     * 保存p2p配置信息
     *
     * @param list 配置信息列表
     * @return 配置信息
     */
    public int saveUserProxies(List<UserProxy> list);

    /**
     * 结束
     *
     * @param action 操作
     * @param ctx    上下文
     */
    void end(Supplier<String> action, RoutingContext ctx);
}
