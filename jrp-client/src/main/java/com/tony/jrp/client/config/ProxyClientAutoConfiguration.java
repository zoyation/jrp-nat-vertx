package com.tony.jrp.client.config;

import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(
        proxyBeanMethods = false
)
@Slf4j
public class ProxyClientAutoConfiguration {

    public ProxyClientAutoConfiguration() {
    }

    @Bean
    public Vertx vertx() {
        return Vertx.currentContext().owner();
    }
}
