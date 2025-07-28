package com.tony.jrp.client.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.model.ClientProxy;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.JacksonCodec;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public class FileConfigServiceImpl implements IConfigService, InitializingBean {
    /**
     * 配置文件默认路径
     */
    public static final String CONFIG_PATH = "config.json";
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
        this.end(() -> {
            try {
                return Json.encode(getConfig().getRemote_proxies());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, ctx);
    }

    @Override
    public void save(RoutingContext ctx) {
        ctx.request().body().onSuccess(buffer -> {
            List<ClientProxy> remote_proxies = JacksonCodec.decodeValue(buffer, new TypeReference<List<ClientProxy>>() {
            });
            this.end(() -> String.valueOf(this.save(remote_proxies)), ctx);
        });
    }

    /**
     * 保存配置信息
     * @param list 配置信息列表
     * @return 配置信息
     */
    public int save(List<ClientProxy> list) {
        ProxyClientConfig proxyConfig;
        try {
            proxyConfig = getConfig();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        proxyConfig.setRemote_proxies(list);
        try {
            saveToFile(getConfigFilePath(), Json.encode(proxyConfig));
            return list.size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 初始化配置
     * @throws IOException 异常
     */
    private void initConfig() throws IOException {
        String configFilePath = getConfigFilePath();
        File configFile = new File(configFilePath);
        if (log.isInfoEnabled()) {
            log.info("configFilePath:{}", configFilePath);
        }
        File parent = new File(configFile.getParent());
        boolean mkdir = true;
        if (!parent.isDirectory()) {
            mkdir = parent.mkdir();
        }
        if (mkdir && !configFile.isFile()) {
            try {
                boolean newFile = configFile.createNewFile();
                if (newFile) {
                    saveToFile(configFilePath, DEFAULT_JSON_CONFIG);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 获取配置文件路径
     * @return 配置文件路径
     * @throws IOException 异常
     */
    private String getConfigFilePath() throws IOException {
        ApplicationHome home = new ApplicationHome(getClass());
        String configFilePath;
        if (home.getSource() != null) {
            if (home.getSource().isDirectory()) {
                configFilePath = home.getSource().getAbsolutePath();
            } else {
                configFilePath = home.getSource().getParent();
            }
            configFilePath = configFilePath + File.separator + CONFIG_PATH;
        } else {
            configFilePath = new ClassPathResource(CONFIG_PATH).getFile().getAbsolutePath();
        }
        return configFilePath;
    }

    /**
     * 获取配置信息
     * @return 配置信息
     * @throws IOException 异常
     */
    private ProxyClientConfig getConfig() throws IOException {
        String configStr = getConfigStr();
        return Json.decodeValue(configStr, ProxyClientConfig.class);
    }

    /**
     * 获取配置文件路径
     * @return 配置文件路径
     * @throws IOException 异常
     */
    private String getConfigStr() throws IOException {
        String configFilePath = getConfigFilePath();
        File configFile = new File(configFilePath);
        BufferedReader configReader = new BufferedReader(new FileReader(configFile));
        String configStr = configReader.lines().collect(Collectors.joining(""));
        configReader.close();
        return configStr;
    }

    /**
     * 配置保存到文件
     *
     * @param configFilePath 配置信息路径
     * @param jsonConfig     配置信息
     * @throws IOException 异常
     */
    private static void saveToFile(String configFilePath, String jsonConfig) throws IOException {
        synchronized (CONFIG_PATH) {
            try (FileWriter out = new FileWriter(configFilePath); BufferedWriter writer = new BufferedWriter(out)) {
                writer.write(jsonConfig);
            }
        }
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        this.initConfig();
        String configFilePath = getConfigFilePath();
        storeOptions = new ConfigStoreOptions()
                .setType("file")
                .setOptional(false)
                .setConfig(new JsonObject().put("path", configFilePath));
    }
}
