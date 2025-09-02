package com.tony.jrp.client.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
import com.tony.jrp.client.config.RedisConfig;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.JacksonCodec;
import io.vertx.ext.web.RoutingContext;
import io.vertx.redis.client.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class RedisConfigServiceImpl implements IConfigService, InitializingBean {
    public static final String JRP_CLIENT_CONFIG = "jrp-client-config";
    public static final String CONFIGURATION = "configuration";
    @Autowired
    protected Vertx vertx;
    /**
     * 固定参数配置信息
     */
    @Autowired
    protected ProxyClientProperties properties;
    private RedisAPI redisAPI;
    private ConfigStoreOptions storeOptions;
    /**
     * 默认代理配置信息
     */
    public static final String DEFAULT_JSON_CONFIG = "{\n" +
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

    public ConfigStoreOptions getConfigStore() {
        return storeOptions;
    }

    @Override
    public void end(Supplier<String> action, RoutingContext ctx) {
        HttpServerResponse response = ctx.response();
        response.setChunked(true);
        response.putHeader("content-type", "application/json;charset=utf-8;");
        response.write(action.get(), "utf-8");

        ctx.response().end();
    }

    @Override
    public void list(RoutingContext ctx) {
        redisAPI.hgetall(CONFIGURATION).onSuccess(response -> {
            Response config = response.get(JRP_CLIENT_CONFIG);
            if (config == null) {
                this.end(() -> "{}", ctx);
                return;
            }
            List<ClientProxy> remoteProxies = Json.decodeValue(config.toString(), ProxyClientConfig.class).getRemote_proxies();
            this.end(() -> Json.encode(remoteProxies), ctx);
        });
    }

    @Override
    public void save(RoutingContext ctx) {
        ctx.request().body().onSuccess(buffer -> {
            List<ClientProxy> remote_proxies = JacksonCodec.decodeValue(buffer, new TypeReference<List<ClientProxy>>() {
            });
            redisAPI.hgetall(CONFIGURATION).onSuccess(response -> {
                ProxyClientConfig proxyClientConfig = Json.decodeValue(response.get(JRP_CLIENT_CONFIG).toString(), ProxyClientConfig.class);
                proxyClientConfig.setRemote_proxies(remote_proxies);
                List<String> dataList = new ArrayList<>(2);
                dataList.add(CONFIGURATION);
                dataList.add(JRP_CLIENT_CONFIG);
                dataList.add(Json.encode(proxyClientConfig));
                redisAPI.hset(dataList).onSuccess(setResponse -> {
                    this.end(() -> String.valueOf(remote_proxies.size()), ctx);
                }).onFailure(throwable -> {
                    ctx.response().setStatusCode(500);
                    this.end(() -> "保存异常：" + throwable.getMessage(), ctx);
                });
            }).onFailure(throwable -> {
                ctx.response().setStatusCode(500);
                this.end(() -> "获取原始配置异常：" + throwable.getMessage(), ctx);
            })
            ;

        });
    }

    /**
     * 初始化配置
     *
     * @throws IOException 异常
     */
    private void initConfig() throws IOException {
        RedisConfig redis = properties.getRedis();
        // 配置 Redis 连接
        //redis://[:password@]host[:port][/db-number]
        RedisOptions options = new RedisOptions();
        options.setType(redis.getClientType());
        options.setPassword(redis.getPassword());
        options.setMasterName(redis.getMaster());
        String url = getUrl(redis);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        switch (redis.getClientType()) {
            case STANDALONE:
                options.setConnectionString(url);
                break;
            case SENTINEL:
            case CLUSTER:
            case REPLICATION:
                List<String> nodes = redis.getNodes();
                if (nodes == null || nodes.isEmpty()) {
                    nodes = new ArrayList<>(1);
                    nodes.add(url);
                }
                options.setEndpoints(nodes);
                options.setRole(RedisRole.MASTER);
                break;
        }
        Redis client = Redis.createClient(
                vertx, options);
        redisAPI = RedisAPI.api(client);
        redisAPI.hgetall(CONFIGURATION).onSuccess(response -> {
            if (response == null || response.toString().equals("{}")) {
                List<String> dataList = new ArrayList<>(2);
                dataList.add(CONFIGURATION);
                dataList.add(JRP_CLIENT_CONFIG);
                dataList.add(DEFAULT_JSON_CONFIG);
                redisAPI.hset(dataList).onSuccess(setResponse -> {
                    log.info("初始化默认配置到redis成功！");
                    countDownLatch.countDown();
                }).onFailure(throwable -> {
                    log.error("初始化默认配置到redis失败：{}！", throwable.getMessage(), throwable);
                    countDownLatch.countDown();
                });
            } else {
                countDownLatch.countDown();
                log.info("已有配置不需要初始化！");
            }
        }).onFailure(throwable -> {
            log.error("获取配置异常：{}！", throwable.getMessage(), throwable);
            countDownLatch.countDown();
        });
        try {
            countDownLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getUrl(RedisConfig redis) {
        String url = redis.getUrl();
        if (url == null) {
            url = redis.getHost() + ":" + redis.getPort() + "/" + redis.getDatabase();
            if (redis.getPassword() != null && redis.getUsername() != null) {
                url = "redis://" + redis.getUsername() + ":" + redis.getPassword() + "@" + url;
            } else if (redis.getPassword() != null) {
                url = "redis://" + ":" + redis.getPassword() + "@" + url;
            } else {
                url = "redis://" + url;
            }
        }
        return url;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.initConfig();
        RedisConfig redis = properties.getRedis();
        storeOptions = new ConfigStoreOptions()
                .setType("redis");
        JsonObject config = new JsonObject();
        config.put("type", redis.getClientType());
        config.put("Password", redis.getPassword());
        config.put("masterName", redis.getMaster());
        String url = getUrl(redis);
        switch (redis.getClientType()) {
            case STANDALONE:
                config.put("connectionString", url);
                break;
            case SENTINEL:
            case CLUSTER:
            case REPLICATION:
                List<String> nodes = redis.getNodes();
                if (nodes == null || nodes.isEmpty()) {
                    nodes = new ArrayList<>(1);
                    nodes.add(url);
                }
                config.put("endpoints", nodes);
                config.put("role", RedisRole.MASTER);
                break;
        }
        config.put("key", CONFIGURATION);
        storeOptions.setConfig(config);
    }
}
