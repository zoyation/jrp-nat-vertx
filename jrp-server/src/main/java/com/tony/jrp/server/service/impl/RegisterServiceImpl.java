package com.tony.jrp.server.service.impl;

import com.tony.jrp.server.model.RegisterConfig;
import com.tony.jrp.server.model.RegisterInfo;
import com.tony.jrp.server.service.IRegisterService;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * 注册信息管理
 */
@Slf4j
@Service
public class RegisterServiceImpl implements IRegisterService, InitializingBean {
    /**
     * 配置文件默认路径
     */
    public static final String CONFIG_PATH = "config.json";
    @Autowired
    protected Vertx vertx;
    RegisterConfig registerConfig;

    @Override
    public void add(RegisterInfo registerInfo) {
        synchronized (CONFIG_PATH){
            registerConfig.getReal_list().add(registerInfo);
            registerConfig.getRegister_list().add(registerInfo);
            try {
                saveToFile(getConfigFilePath(),Json.encode(registerConfig));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void update(RegisterInfo registerInfo) {
        synchronized (CONFIG_PATH){
            try {
                List<RegisterInfo> realList = registerConfig.getReal_list();
                realList.removeIf(r->r.getId().equals(registerInfo.getId()));
                saveToFile(getConfigFilePath(),Json.encode(registerConfig));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 初始化配置
     *
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
                    saveToFile(configFilePath, "{\"max_client\":100,\"max_port_num\":100,\"register_list\":[],\"real_list\":[]}");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ConfigStoreOptions storeOptions = new ConfigStoreOptions()
                .setType("file")
                .setOptional(false)
                .setConfig(new JsonObject().put("path", configFilePath));
        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(storeOptions);
        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
        retriever.getConfig().onComplete(json -> {
            JsonObject result = json.result();
            registerConfig = Json.decodeValue(result.toString(), RegisterConfig.class);
        });
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

    /**
     * 获取配置文件路径
     *
     * @return 配置文件路径
     */
    private String getConfigFilePath() {
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
            try {
                configFilePath = new ClassPathResource(CONFIG_PATH).getFile().getAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return configFilePath;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        initConfig();
    }
}
