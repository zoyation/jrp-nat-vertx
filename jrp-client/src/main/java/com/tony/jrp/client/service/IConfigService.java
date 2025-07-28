package com.tony.jrp.client.service;

import io.vertx.config.ConfigStoreOptions;
import io.vertx.ext.web.RoutingContext;

import java.util.function.Supplier;

public interface IConfigService{
    ConfigStoreOptions getConfigStore();

    void list(RoutingContext ctx);

    void save(RoutingContext ctx);
     void end(Supplier<String> action, RoutingContext ctx);
}
