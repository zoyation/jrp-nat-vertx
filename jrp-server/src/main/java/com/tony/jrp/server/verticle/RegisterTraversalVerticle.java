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
 * 服务器转发穿透主控类
 * 一个客户端一个代理服务对应一个verticle
 */
@Slf4j
public class RegisterTraversalVerticle extends AbstractVerticle {
    /**
     * 远程端口byte数组长度。
     */
    public static final int REMOTE_PORT_LEN = 2;
    /**
     * 请求唯一ID（int类型）对应byte数组长度，4字节。
     */
    public static final int REQUEST_ID_LEN = 4;
    /**
     * ”ip:端口“地址总长度数值对应字符串长度。
     */
    public static final int CLIENT_IP_PORT_LEN = 2;
    public static final int WRITE_QUEUE_MAX_SIZE = 100;
    public static final int TYPE_AND_MSG_ID_BYTE_SIZE = 9;
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
    private final Map<Integer, AbstractProtocolVerticle> verticleMap = new ConcurrentHashMap<>();


    /**
     * 构造函数
     *
     * @param clientRegister  客户端注册信息
     * @param serverSocket    服务器socket
     * @param securityService 安全认证服务
     */
    public RegisterTraversalVerticle(ClientRegister clientRegister, ServerWebSocket serverSocket, SecurityService securityService) {
        this.clientRegister = clientRegister;
        this.serverSocket = serverSocket;
        this.securityService = securityService;
    }

    @Override
    public void start() throws Exception {
        serverSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
        /* 重新设置socket的handler，处理返回消息 */
        serverSocket.handler(data -> {
            JRPMsgType msgType = data.length() > 0 ? JRPMsgType.getByCode(data.getByte(0)) : null;
            //消息前缀为：消息标志符，后面是消息id：即代理端口位数（一位整数1024到49151，4或者5）+代理端口（字符串）+请求唯一标识长度（两位整数）+请求唯一标识（IP+端口）
            //获取代理端口字符串长度（代理到外网的穿透访问端口，一位整数，比如1024则长度为4,49151则长度为5）
            //外网访问端口，整数，比如1024
            Integer remotePort = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN).getUnsignedShort(0);
            //int clientStrLen = Integer.parseInt(data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN).toString());
            //clientAddress = data.getBuffer(JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN, JRPMsgType.TYPE_LEN + 1 + portLen + CLIENT_IP_PORT_LEN + clientStrLen).toString();
            Integer requestId = data.getBuffer(JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN).getInt(0);
            //获取消息标识：代理端口+请求id
            Buffer msgId = data.getBuffer(JRPMsgType.TYPE_LEN, JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN);
            Buffer realData = data.getBuffer(JRPMsgType.TYPE_LEN + REMOTE_PORT_LEN + REQUEST_ID_LEN, data.length());
            AbstractProtocolVerticle verticle = verticleMap.get(remotePort);
            if (verticle == null) {
                log.warn("端口[{}]收到内网代理服务返回消息，但是未找到端口对应代理，客户端标识id[{}]对应连接已经失效，发送关闭连接消息到内网代理服务！", remotePort, requestId);
                serverSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.CLOSE.getCode()).appendBuffer(msgId));
            } else {
                verticle.writeData(msgType, msgId, requestId, realData);
            }
        });
        //代理服务里监听指定端口，用于接收转发用户请求到内网服务，并返回到请求端
        for (ClientProxy clientProxy : clientRegister.getProxies()) {
            Integer remotePort = clientProxy.getRemote_port();
            synchronized (RegisterTraversalVerticle.this) {
                if (verticleMap.get(remotePort) != null) {
                    log.warn("已存在外网端口为[{}]的代理信息，不做处理！", remotePort);
                    continue;
                }
            }
            AbstractProtocolVerticle verticle;
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
                    verticle = new ForwardProxyVerticle(serverSocket, securityService, clientRegister, clientProxy);
                    break;
                default:
                    throw new Exception("不支持代理类型：" + clientProxy.getType().name() + "！");
            }
            Future<String> tcpFuture = vertx.deployVerticle(verticle);
            tcpFuture.onSuccess(id -> verticleMap.put(remotePort, verticle)).onFailure(Throwable::printStackTrace);
        }
    }

    @Override
    public void stop() {
        String ports = verticleMap.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
        log.info("清理端口[{}]下所有代理缓存！", ports);
        verticleMap.values().forEach((v) -> vertx.undeploy(v.deploymentID()));
        verticleMap.clear();
    }
}
