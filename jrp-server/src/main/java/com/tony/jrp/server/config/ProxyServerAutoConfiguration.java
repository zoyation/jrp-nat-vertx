package com.tony.jrp.server.config;

import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(
        proxyBeanMethods = false
)
@Slf4j
public class ProxyServerAutoConfiguration {
    @Bean
    public Vertx vertx() {
        return Vertx.currentContext().owner();
    }
}
