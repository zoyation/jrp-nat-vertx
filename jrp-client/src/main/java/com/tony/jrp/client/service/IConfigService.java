package com.tony.jrp.client.service;

import com.tony.jrp.common.model.ClientProxy;
import io.vertx.config.ConfigStoreOptions;

public interface IConfigService extends IBaseService<ClientProxy>{
    ConfigStoreOptions getConfigStore();
}
