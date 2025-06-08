package com.tony.jrp.client.controller;

import com.tony.jrp.client.service.IBaseService;
import com.tony.jrp.client.service.IConfigService;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.query.IQueryExp;
import com.tony.jrp.common.query.Query;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProxyClientController extends BaseController<ClientProxy> {
    public static final String TYPE = "type";
    @Autowired
    IConfigService configService;

    @Override
    public Class<ClientProxy> typeClass() {
        return ClientProxy.class;
    }

    @Override
    public IBaseService<ClientProxy> service() {
        return configService;
    }

    @Override
    public void list(RoutingContext ctx) {
        String type = ctx.request().getParam(TYPE);
        IQueryExp queryExp = type == null ? null : Query.eq(Query.attr(TYPE), Query.value(type));
        this.execute((routingContext -> routingContext.response().write(Json.encode(this.service().list(queryExp)))), ctx);
    }
}
