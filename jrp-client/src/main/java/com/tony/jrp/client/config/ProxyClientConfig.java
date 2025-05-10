package com.tony.jrp.client.config;

import com.tony.jrp.common.model.ClientProxy;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
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
     * 和公网代理服务通信用的固定端口
     */
    private Integer remote_port = 800;

    /**
     * 内网穿透，需要进行服务器中转代理服务配置
     */
    List<ClientProxy> remote_proxies;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getRemote_port() {
        return remote_port;
    }

    public void setRemote_port(Integer remote_port) {
        this.remote_port = remote_port;
    }

    public List<ClientProxy> getRemote_proxies() {
        return remote_proxies;
    }

    public void setRemote_proxies(List<ClientProxy> remote_proxies) {
        this.remote_proxies = remote_proxies;
    }

    @Override
    public String toString() {
        return "ProxyConfig{" +
                "path='" + path + '\'' +
                ", port=" + port +
                ", remote_port=" + remote_port +
                ", remoteProxies=" + remote_proxies +
                '}';
    }
}
