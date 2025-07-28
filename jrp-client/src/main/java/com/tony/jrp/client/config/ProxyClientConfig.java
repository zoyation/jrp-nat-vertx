package com.tony.jrp.client.config;

import com.tony.jrp.common.model.ClientProxy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
@Setter
@Getter
@Slf4j
public class ProxyClientConfig implements Serializable {
    /**
     * 路径
     */
    private String path = "/";
    /**
     * 端口
     */
    private Integer port = 80;
    /**
     * 内网穿透，需要进行服务器中转代理服务配置
     */
    List<ClientProxy> remote_proxies;

    @Override
    public String toString() {
        return "ProxyConfig{" +
                "path='" + path + '\'' +
                ", port=" + port +
                ", remoteProxies=" + remote_proxies +
                '}';
    }
}
