package com.tony.jrp.client.service.impl;

import com.tony.jrp.client.config.ProxyClientConfig;
import com.tony.jrp.client.config.ProxyClientProperties;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.query.IQueryExp;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "vertx.jrp", name = "config-store-type", havingValue = "file")
@Slf4j
public class FileConfigServiceImpl implements IConfigService, InitializingBean {
    /**
     * 配置文件默认路径
     */
    public static final String CONFIG_PATH = "config.json";
    public static String configFilePath;
    private ConfigStoreOptions storeOptions;
    /**
     * 默认代理配置信息
     */
    public static final String DEFAULT_JSON_CONFIG = "{\n" +
            "  \"path\": \"/jrp-client\",\n" +
            "  \"port\": 80,\n" +
            "  \"remote_proxies\": [\n" +
            "    {\n" +
            "      \"type\": \"HTTP\",\n" +
            "      \"remote_port\": 86,\n" +
            "      \"proxy_pass\": \"http://192.168.80.78:86\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";
    private ProxyClientConfig proxyConfig;
    @Autowired
    private ProxyClientProperties properties;

    public ConfigStoreOptions getConfigStore() {
        return storeOptions;
    }
    @Override
    public String add(ClientProxy data) {
        try {
            proxyConfig.getRemote_proxies().add(data);
            saveToFile(Json.encode(proxyConfig));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return data.toString();
    }

    @Override
    public int update(ClientProxy data) {
        try {
            List<ClientProxy> ClientProxyList = proxyConfig.getRemote_proxies();
            for (int i = 0; i < ClientProxyList.size(); i++) {
                ClientProxy ClientProxy = ClientProxyList.get(i);
                if (ClientProxy.toString().equals(data.toString())) {
                    ClientProxyList.set(i, data);
                    break;
                }
            }
            saveToFile(Json.encode(proxyConfig));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 1;
    }

    @Override
    public int delete(Set<String> ids) {
        try {
            proxyConfig.setRemote_proxies(proxyConfig.getRemote_proxies().stream().filter(r -> !ids.contains(r.toString())).collect(Collectors.toList()));
            saveToFile(Json.encode(proxyConfig));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ids.size();
    }


    @Override
    public ClientProxy detail(String id) {
        return proxyConfig.getRemote_proxies().stream().filter(r -> r.toString().equals(id)).findFirst().orElse(null);
    }

    @Override
    public List<ClientProxy> list(IQueryExp queryExp) {
        return proxyConfig.getRemote_proxies();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ApplicationHome home = new ApplicationHome(getClass());
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
        File configFile = new File(configFilePath);
        if (log.isInfoEnabled()) {
            log.info("configFilePath:" + configFilePath);
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
                    proxyConfig = Json.decodeValue(DEFAULT_JSON_CONFIG, ProxyClientConfig.class);
                    saveToFile(DEFAULT_JSON_CONFIG);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }else{
            BufferedReader configReader = new BufferedReader(new FileReader(configFile));
            String configStr = configReader.lines().collect(Collectors.joining(""));
            configReader.close();
            proxyConfig = Json.decodeValue(configStr, ProxyClientConfig.class);
        }
        storeOptions = new ConfigStoreOptions()
                .setType("file")
                .setOptional(false)
                .setConfig(new JsonObject().put("path", configFilePath));
    }

    /**
     * 配置保存到文件
     *
     * @param jsonConfig 配置信息
     * @throws IOException 异常
     */
    private static void saveToFile(String jsonConfig) throws IOException {
        synchronized (CONFIG_PATH) {
            FileWriter out = new FileWriter(configFilePath);
            BufferedWriter writer = new BufferedWriter(out);
            writer.write(jsonConfig);
            writer.close();
        }
    }
}
