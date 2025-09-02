package com.tony.jrp.client.config;

import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.client.service.impl.FileConfigServiceImpl;
import com.tony.jrp.client.service.impl.RedisConfigServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfigServiceConfig {
    @ConditionalOnProperty(prefix = "vertx.jrp", name = "config-store-type", havingValue = "file", matchIfMissing = true)
    @Bean
    public IConfigService fileConfigService() {
        return new FileConfigServiceImpl();
    }

    @ConditionalOnProperty(prefix = "vertx.jrp", name = "config-store-type", havingValue = "redis", matchIfMissing = false)
    @Bean
    public IConfigService redisConfigService() {
        return new RedisConfigServiceImpl();
    }
}
