package com.tony.jrp.server.verticle;

import com.tony.jrp.common.enums.JRPMsgType;
import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.common.model.RegisterResult;
import com.tony.jrp.server.manager.P2PSessionManager;
import com.tony.jrp.server.service.impl.SecurityService;
import com.tony.jrp.server.util.TraversalUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.tony.jrp.server.util.TraversalUtil.MAX_PORT;
import static com.tony.jrp.server.util.TraversalUtil.MIN_PORT;

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
    public static final int WRITE_QUEUE_MAX_SIZE = 16384;
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
     * P2P会话管理器（全局共享，用于创建P2P打洞监听）
     */
    private final P2PSessionManager p2pSessionManager;
    /**
     * 客户端注册信息
     */
    @Getter
    private ClientRegister clientRegister;
    /**
     * 知名端口（0-1023）‌：这些端口通常被系统服务或标准应用协议占用
     * 动态/私有端口（49152-65535）：这些端口由操作系统临时分配给客户端进程，用于短期通信，例如浏览器发起的UDP请求。
     * 注册端口（1024-49151）‌：这些端口可由用户进程或应用程序动态分配，常见于自定义服务或特定软件。
     * 所有代理Verticle,key：注册端口（1024-49151），用户可注册用于特定服务。
     */
    private final Map<Integer, AbstractProtocolVerticle<?>> verticleMap = new ConcurrentHashMap<>();
    /**
     * P2P打洞Verticle部署ID映射：remotePort -> deploymentId
     */
    private final Map<Integer, String> p2pVerticleMap = new ConcurrentHashMap<>();
    /**
     * 外网IPv4地址
     */
    private String ipv4;


    /**
     * 构造函数
     *
     * @param clientRegister  客户端注册信息
     * @param serverSocket    服务器socket
     * @param securityService 安全认证服务
     * @param p2pSessionManager P2P会话管理器
     */
    public RegisterTraversalVerticle(ClientRegister clientRegister, ServerWebSocket serverSocket,
                                     SecurityService securityService, P2PSessionManager p2pSessionManager) {
        this.clientRegister = clientRegister;
        this.serverSocket = serverSocket;
        this.securityService = securityService;
        this.p2pSessionManager = p2pSessionManager;
    }

    @Override
    public void start() throws Exception {
        //vertx调用第三方接口获取云服务器外网IPV4地址和IPV6地址
        // 获取IPv4地址
        String host = serverSocket.headers().get("host");
        this.ipv4 = host.substring(0, host.lastIndexOf(":"));
        log.info("获取到外网IPV4地址：{}", ipv4);
        serverSocket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
        /* 重新设置socket的handler，处理返回消息 */
        serverSocket.handler(data -> {
            JRPMsgType msgType = data.length() > 0 ? JRPMsgType.getByCode(data.getByte(0)) : null;
            if (JRPMsgType.PROXIES_UPDATE == msgType) {
                String registerJsonStr = data.getString(1, data.length());
                String prettily;
                try {
                    prettily = new JsonObject(registerJsonStr).encodePrettily();
                    log.info("收到来自[{}]的服务代理更新信息:\n{}", serverSocket.remoteAddress(), prettily);
                } catch (Exception e) {
                    log.error("收到来自[{}]的无效代理更新信息信息:\n{}", serverSocket.remoteAddress(), registerJsonStr, e);
                    // 发送错误结果
                    RegisterResult errorResult = RegisterResult.error("无效的JSON格式：" + e.getMessage());
                    serverSocket.write(Buffer.buffer(JRPMsgType.PROXIES_UPDATE_RESULT.codeArray()).appendBuffer(Buffer.buffer(Json.encode(errorResult))));
                    return;
                }
                // 将JSON转换为ClientRegister对象
                ClientRegister newClientRegister = Json.decodeValue(registerJsonStr, ClientRegister.class);
                // 调用更新方法并返回结果
                try {
                    updateClientRegister(newClientRegister);
                    // 发送成功结果
                    RegisterResult successResult = RegisterResult.success("代理配置更新成功");
                    successResult.setProxies(newClientRegister.getProxies());
                    serverSocket.write(Buffer.buffer(JRPMsgType.PROXIES_UPDATE_RESULT.codeArray()).appendBuffer(Buffer.buffer(Json.encode(successResult))));
                    log.info("代理配置更新成功，已返回结果到客户端");
                } catch (Exception e) {
                    log.error("代理配置更新失败：{}", e.getMessage(), e);
                    // 发送错误结果
                    RegisterResult errorResult = RegisterResult.error("更新失败：" + e.getMessage());
                    serverSocket.write(Buffer.buffer(JRPMsgType.PROXIES_UPDATE_RESULT.codeArray()).appendBuffer(Buffer.buffer(Json.encode(errorResult))));
                }
            } else {
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
                AbstractProtocolVerticle<?> verticle = verticleMap.get(remotePort);
                if (verticle == null) {
                    log.warn("端口[{}]收到内网代理服务返回消息，但是未找到端口对应代理，客户端标识id[{}]对应连接已经失效，发送关闭连接消息到内网代理服务！", remotePort, requestId);
                    serverSocket.write(Buffer.buffer(TYPE_AND_MSG_ID_BYTE_SIZE).appendByte(JRPMsgType.CLOSE.getCode()).appendBuffer(msgId));
                } else {
                    verticle.backData(msgType, msgId, requestId, realData);
                }
            }
        });
        //代理服务里监听指定端口，用于接收转发用户请求到内网服务，并返回到请求端
        for (ClientProxy clientProxy : clientRegister.getProxies()) {
            if (!clientProxy.isEnable()) {
                continue;
            }
            Integer remotePort = clientProxy.getRemote_port();
            synchronized (RegisterTraversalVerticle.this) {
                if (verticleMap.get(remotePort) != null) {
                    log.warn("已存在外网端口为[{}]的代理信息，不做处理！", remotePort);
                    continue;
                }
            }
            AbstractProtocolVerticle<?> verticle;
            switch (clientProxy.getType()) {
                case HTTPS:
                case HTTP:
                case TCP: {
                    verticle = new TCPVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                    break;
                }
                case UDP: {
                    verticle = new UDPVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                    break;
                }
                case HTTP_PROXY:
                case HTTPS_PROXY:
                case SOCKS4:
                case SOCKS5:
                case SMART_PROXY:
                    verticle = new ForwardProxyVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                    break;
                default:
                    throw new IllegalStateException("不支持代理类型：" + clientProxy.getType().name() + "！");
            }
            vertx.deployVerticle(verticle)
                    .onSuccess(id -> verticleMap.put(remotePort, verticle))
                    .onFailure(Throwable::printStackTrace);

            // 如果代理启用了P2P，同时在该remote_port上部署P2P打洞UDP监听
            // remote_port同时支持TCP转发和UDP打洞（协议不同，可共存同一端口号）
            if (clientProxy.isEnable_p2p()) {
                deployP2PVerticle(clientProxy);
            }
        }
    }

    /**
     * 更新客户端注册信息，处理代理的增删改
     *
     * @param newClientRegister 新的客户端注册信息
     */
    public void updateClientRegister(ClientRegister newClientRegister) {
        if (newClientRegister == null || newClientRegister.getProxies() == null) {
            log.warn("新的客户端注册信息为空，不做处理！");
            return;
        }
        List<ClientProxy> proxies = newClientRegister.getProxies().stream().filter(ClientProxy::isEnable).collect(Collectors.toList());
        // 1. 处理新增的指定了远程端口的穿透配置，校验端口
        List<ClientProxy> newProxyList = proxies.stream().filter(proxy -> proxy.getRemote_port() != null && !verticleMap.containsKey(proxy.getRemote_port())).collect(Collectors.toList());
        List<String> invalidPorts = TraversalUtil.getInvalidPorts(newProxyList);
        if (!invalidPorts.isEmpty()) {
            log.warn("存在已被占用端口[{}]，不做处理！", invalidPorts);
            throw new IllegalArgumentException("端口[" + String.join(",", invalidPorts) + "]已被使用，请使用" + MIN_PORT + "到" + MAX_PORT + "中其它端口，或让服务器自动分配！");
        }
        // 自动分配端口，如果有未指定远程端口的穿透配置
        TraversalUtil.allocatePort(proxies);
        // 移除不存在和禁用的代理（在新配置中不存在的remote_port或者禁用的）
        verticleMap.keySet().stream()
                .filter(remotePort -> proxies.stream()
                        .noneMatch(proxy -> remotePort.equals(proxy.getRemote_port())))
                .forEach(remotePort -> {
                    AbstractProtocolVerticle<?> verticle = verticleMap.remove(remotePort);
                    if (verticle != null) {
                        vertx.undeploy(verticle.deploymentID())
                                .onSuccess(v -> log.info("已移除端口[{}]的代理verticle", remotePort))
                                .onFailure(t -> log.error("移除端口[{}]的代理verticle失败", remotePort, t));
                    }
                    // 同时移除对应端口的P2P打洞Verticle
                    undeployP2PVerticle(remotePort);
                });
        // 2. 处理新增或更新的代理
        for (ClientProxy newProxy : proxies) {
            Integer remotePort = newProxy.getRemote_port();
            AbstractProtocolVerticle<?> existingVerticle = verticleMap.get(remotePort);

            if (existingVerticle == null) {
                // 新代理，创建verticle
                deployNewVerticle(newProxy);
            } else {
                // 检查是否需要更新（比较关键参数）
                ClientProxy oldProxy = existingVerticle.getClientProxy();
                if (needUpdate(oldProxy, newProxy)) {
                    log.info("端口[{}]的代理配置发生变化，重新部署verticle", remotePort);
                    // 移除旧的verticle
                    verticleMap.remove(remotePort);
                    vertx.undeploy(existingVerticle.deploymentID())
                            .onSuccess(v -> {
                                log.info("已卸载端口[{}]的旧verticle", remotePort);
                                // 部署新的verticle
                                deployNewVerticle(newProxy);
                            })
                            .onFailure(t -> log.error("卸载端口[{}]的旧verticle失败", remotePort, t));
                } else {
                    // 配置未变化，但需要更新clientRegister引用
                    existingVerticle.setClientRegister(newClientRegister);
                    log.debug("端口[{}]的代理配置未变化，仅更新clientRegister引用", remotePort);

                    // 处理P2P启用状态变更
                    boolean wasP2P = oldProxy.isEnable_p2p();
                    boolean nowP2P = newProxy.isEnable_p2p();
                    if (!wasP2P && nowP2P) {
                        log.info("端口[{}]的代理启用P2P，部署P2P打洞Verticle", remotePort);
                        deployP2PVerticle(newProxy);
                    } else if (wasP2P && !nowP2P) {
                        log.info("端口[{}]的代理禁用P2P，移除P2P打洞Verticle", remotePort);
                        undeployP2PVerticle(remotePort);
                    }
                }
            }
        }

        // 更新当前verticle的clientRegister引用为新的对象
        this.clientRegister = newClientRegister;
    }

    /**
     * 判断两个代理配置是否需要更新
     *
     * @param oldProxy 旧的代理配置
     * @param newProxy 新的代理配置
     * @return true表示需要更新，false表示不需要更新
     */
    private boolean needUpdate(ClientProxy oldProxy, ClientProxy newProxy) {
        if (oldProxy == null || newProxy == null) {
            return true;
        }

        // 比较proxy_pass
        if (!Objects.equals(oldProxy.getProxy_pass(), newProxy.getProxy_pass())) {
            return true;
        }

        // 比较https
        if (oldProxy.isHttps() != newProxy.isHttps()) {
            return true;
        }

        // 比较host
        if (!Objects.equals(oldProxy.getHost(), newProxy.getHost())) {
            return true;
        }

        // 比较port
        if (!Objects.equals(oldProxy.getPort(), newProxy.getPort())) {
            return true;
        }

        // 比较type
        if (oldProxy.getType() != newProxy.getType()) {
            return true;
        }

        // 比较P2P启用状态
        if (oldProxy.isEnable_p2p() != newProxy.isEnable_p2p()) {
            return true;
        }

        return false;
    }

    /**
     * 部署新的代理verticle
     *
     * @param clientProxy 客户端代理配置
     */
    private void deployNewVerticle(ClientProxy clientProxy) {
        Integer remotePort = clientProxy.getRemote_port();

        if (ipv4 == null) {
            log.error("IPv4地址未获取，无法部署端口[{}]的代理verticle", remotePort);
            return;
        }

        AbstractProtocolVerticle<?> verticle;
        switch (clientProxy.getType()) {
            case HTTPS:
            case HTTP:
            case TCP: {
                verticle = new TCPVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                break;
            }
            case UDP: {
                verticle = new UDPVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                break;
            }
            case HTTP_PROXY:
            case HTTPS_PROXY:
            case SOCKS4:
            case SOCKS5:
            case SMART_PROXY:
                verticle = new ForwardProxyVerticle(ipv4, serverSocket, securityService, clientRegister, clientProxy);
                break;
            default:
                throw new IllegalStateException("不支持代理类型：" + clientProxy.getType().name() + "！");
        }

        vertx.deployVerticle(verticle)
                .onSuccess(id -> {
                    verticleMap.put(remotePort, verticle);
                    log.info("成功部署端口[{}]的代理verticle，类型：{}", remotePort, clientProxy.getType());
                })
                .onFailure(t -> log.error("部署端口[{}]的代理verticle失败", remotePort, t));

        // 如果代理启用了P2P，同时在该remote_port上部署P2P打洞UDP监听
        if (clientProxy.isEnable_p2p()) {
            deployP2PVerticle(clientProxy);
        }
    }

    /**
     * 部署P2P打洞Verticle到指定remote_port
     * remote_port同时承载TCP转发和UDP打洞（协议不同可共存）
     */
    private void deployP2PVerticle(ClientProxy clientProxy) {
        Integer remotePort = clientProxy.getRemote_port();
        // 避免重复部署
        if (p2pVerticleMap.containsKey(remotePort)) {
            log.debug("端口[{}]的P2P打洞Verticle已存在，跳过部署", remotePort);
            return;
        }
        P2PServerVerticle p2pVerticle = new P2PServerVerticle(remotePort, p2pSessionManager);
        vertx.deployVerticle(p2pVerticle)
                .onSuccess(id -> {
                    p2pVerticleMap.put(remotePort, id);
                    log.info("成功部署端口[{}]的P2P打洞Verticle (UDP)", remotePort);
                })
                .onFailure(t -> log.error("部署端口[{}]的P2P打洞Verticle失败", remotePort, t));
    }

    /**
     * 移除指定端口的P2P打洞Verticle
     */
    private void undeployP2PVerticle(Integer remotePort) {
        String deploymentId = p2pVerticleMap.remove(remotePort);
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                    .onSuccess(v -> log.info("已移除端口[{}]的P2P打洞Verticle", remotePort))
                    .onFailure(t -> log.warn("移除端口[{}]的P2P打洞Verticle失败", remotePort, t));
        }
    }

    @Override
    public void stop() {
        String ports = verticleMap.keySet().stream().map(Object::toString).collect(Collectors.joining(","));
        log.info("清理端口[{}]下所有代理缓存！", ports);
        verticleMap.clear();

        // 清理P2P打洞Verticle
        for (Map.Entry<Integer, String> entry : p2pVerticleMap.entrySet()) {
            vertx.undeploy(entry.getValue(), result -> {
                if (result.succeeded()) {
                    log.info("已移除端口[{}]的P2P打洞Verticle", entry.getKey());
                } else {
                    log.warn("移除端口[{}]的P2P打洞Verticle失败", entry.getKey(), result.cause());
                }
            });
        }
        p2pVerticleMap.clear();
    }
}
