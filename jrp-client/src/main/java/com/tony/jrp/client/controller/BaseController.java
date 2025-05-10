package com.tony.jrp.client.controller;

import com.tony.jrp.client.service.IBaseService;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;

import javax.management.Query;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class BaseController<T> {
    protected abstract Class<T> typeClass();

    protected abstract IBaseService<T> service();

    protected Supplier<Query> query() {
        return null;
    }

    public void add(RoutingContext ctx) {
        T data = Json.decodeValue(ctx.body().buffer(), typeClass());
        this.execute(routingContext -> routingContext.response().write(this.service().add(data)), ctx);
    }

    @SuppressWarnings("unchecked")
    public void delete(RoutingContext ctx) {
        Set<String> ids = (Set<String>) Json.decodeValue(ctx.body().buffer(), Set.class);
        this.execute(routingContext -> routingContext.response().write(String.valueOf(this.service().delete(ids))), ctx);
    }

    public void update(RoutingContext ctx) {
        T data = Json.decodeValue(ctx.body().buffer(), typeClass());
        this.execute(routingContext -> routingContext.response().write(String.valueOf(this.service().update(data))), ctx);
    }

    public void detail(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        this.execute(routingContext -> routingContext.response().write(Json.encode(this.service().detail(id))), ctx);
    }


    public void list(RoutingContext ctx) {
        this.execute((routingContext -> routingContext.response().write(Json.encode(this.service().list(null)))), ctx);
    }

    public void execute(Consumer<RoutingContext> action, RoutingContext ctx) {
        HttpServerResponse response = ctx.response();
        response.setChunked(true);
        action.accept(ctx);
        ctx.response().end();
    }
}
