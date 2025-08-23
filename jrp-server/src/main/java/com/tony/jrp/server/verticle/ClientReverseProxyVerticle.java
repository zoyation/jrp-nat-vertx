package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.service.impl.SecurityService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务器转发代理主控类
 */
@Slf4j
public class ClientReverseProxyVerticle extends AbstractVerticle {
    /**
     * ”ip:端口“地址总长度数值对应字符串长度。
     */
    public static final int CLIENT_IP_PORT_LEN = 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    /**
     * 持有和内网代理服务器的连接，收到客户端请求消息后，通知内网代理服务器
     */
    private final ServerWebSocket serverSocket;
    /**
     * 安全认证控制类
     */
    private final SecurityService securityService;
    /**
     * 客户端注册信息
     */
    @Getter
    private final ClientRegister clientRegister;
    /**
     * 所有代理Verticle
     */
    private final Map<Integer, AbstractProxyVerticle> proxyVerticleMap = new ConcurrentHashMap<>();

    public ClientReverseProxyVerticle(ClientRegister clientRegister, ServerWebSocket serverSocket, SecurityService securityService) {
        this.clientRegister = clientRegister;
        this.serverSocket = serverSocket;
        this.securityService = securityService;
    }

    @Override
    public void start() throws Exception {
        serverSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
        /* 重新设置socket的handler，处理返回消息 */
        serverSocket.handler(data -> {
            String msgId, clientAddress;
            Buffer realData;
            //消息前缀为：消息标志符，后面是消息id：即代理端口位数（一位整数1024到49151，4或者5）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
            //获取代理端口字符串长度（代理到外网的穿透访问端口，一位整数，比如1024则长度为4,49151则长度为5）
            int portLen = Integer.parseInt(data.getString(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + 1));
            //外网访问端口，整数，比如1024
            Integer remotePort = Integer.parseInt(data.getBuffer(JRPMsgType.TYPE_LEN + 1, JRPMsgType.TYPE_LEN + 1 + portLen).toString());
            //请求唯一标识字符串长度（两位整数，不会超过100，比如110.242.69.21:49151对应长度值为19个字符‌，‌IPv6字符串的标准长度为39个字符‌，包括冒号（:）和十六进制数字‌）
            int clientStrLen = Integer.parseInt(data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN).toString());
            clientAddress = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
            msgId = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
            realData = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen, data.length());
            AbstractProxyVerticle verticle = proxyVerticleMap.get(remotePort);
            if (verticle == null) {
                log.warn("收到内网代理服务返回消息，但是未找到端口对应代理，客户端[{}]连接已经失效，发送关闭连接消息到内网代理服务！", clientAddress);
                serverSocket.write(Buffer.buffer(msgId).appendBuffer(Buffer.buffer(JRPMsgType.CLOSE.getCode())));
            } else {
                verticle.writeData(msgId, clientAddress, realData);
            }
        });
        //代理服务里监听指定端口，用于接收转发用户请求到内网服务，并返回到请求端
        for (ClientProxy clientProxy : clientRegister.getProxies()) {
            Integer remotePort = clientProxy.getRemote_port();
            synchronized (ClientReverseProxyVerticle.this) {
                if (proxyVerticleMap.get(remotePort) != null) {
                    log.warn("已存在外网端口为[{}]的代理信息，不做处理！", remotePort);
                    continue;
                }
            }
            AbstractProxyVerticle verticle;
            switch (clientProxy.getType()) {
                case HTTPS:
                case HTTP:
                case TCP: {
                    verticle = new TCPVerticle(serverSocket, securityService, clientRegister, clientProxy);
                    break;
                }
                case UDP: {
                    verticle = new UDPVerticle(serverSocket, securityService, clientRegister, clientProxy);
                    break;
                }
                case SOCKS4:
                case SOCKS5:
                default:
                    throw new Exception("不支持代理类型：" + clientProxy.getType().name() + "！");
            }
            Future<String> tcpFuture = vertx.deployVerticle(verticle);
            tcpFuture.onSuccess(id -> proxyVerticleMap.put(remotePort, verticle)).onFailure(Throwable::printStackTrace);
        }
    }

    @Override
    public void stop() {
        String ports = proxyVerticleMap.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
        log.info("清理端口[{}]下所有代理缓存！", ports);
        proxyVerticleMap.values().forEach((v) -> vertx.undeploy(v.deploymentID()));
        proxyVerticleMap.clear();
    }
}
