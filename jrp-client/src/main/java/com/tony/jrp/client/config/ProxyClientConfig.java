package com.tony.jrp.client.config;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.UserProxy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Setter
@Getter
@Slf4j
public class ProxyClientConfig implements Serializable {
    /**
     * 路径
     */
    private String path = "jrp-client";
    /**
     * 端口
     */
    private Integer port = 8000;
    /**
     * 内网穿透，需要进行服务器中转代理服务配置
     */
    List<ClientProxy> remote_proxies= Collections.emptyList();
    /**
     * 用户端p2p访问信息，用于直连穿透。
     */
    List<UserProxy> user_proxies = Collections.emptyList();

    @Override
    public String toString() {
        return "ProxyConfig{" +
                "path='" + path + '\'' +
                ", port=" + port +
                ", remoteProxies=" + remote_proxies +
                ", userProxies=" + user_proxies +
                '}';
    }
}
