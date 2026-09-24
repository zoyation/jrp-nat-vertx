package com.tony.jrp.client.handler;

import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetSocket;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隧道（注册到外网穿透服务的websocket）流量控制协调器。
 * <p>
 * 内网客户端只有一条隧道websocket，所有穿透端口的数据都从这条隧道收发，而 Vert.x 的
 * {@code drainHandler} 是单槽的：如果每个连接各自注册，后注册的会覆盖先注册的，先被暂停的
 * 内网连接将永远等不到排水回调，传输永久卡死。因此必须由同一个协调器统一登记、统一恢复。
 * <p>
 * 两个方向的背压：
 * <ol>
 *   <li>下行（内网服务响应 → 隧道）：隧道写队列满 → 暂停读取内网socket，隧道排水后统一恢复；</li>
 *   <li>上行（隧道请求 → 内网服务）：内网socket写队列满 → 暂停读取隧道，让反压沿TCP传导到
 *       服务端（不能只pause内网socket，那不产生任何反压，只会在客户端堆里无限堆积）。</li>
 * </ol>
 * P2P（UDP打洞）场景的DatagramSocket没有写队列概念，无法做流控，此时tunnel传null，所有方法为空操作。
 */
public class TunnelFlow {

    /**
     * 共享的隧道websocket，P2P场景为null
     */
    private final WebSocket tunnel;
    /**
     * 因隧道写队列满而暂停读取的内网连接
     */
    private final Set<NetSocket> pausedUpstream = ConcurrentHashMap.newKeySet();
    /**
     * 因内网连接写队列满而请求暂停隧道读取的连接标识
     */
    private final Set<Object> congestedDownstream = ConcurrentHashMap.newKeySet();
    /**
     * 当前待转发的数据是否来自隧道websocket。
     * P2P（UDP打洞）场景下数据来自UDP，隧道websocket承载的是其它端口的中转流量，
     * 此时不能因为P2P连接拥塞就去暂停隧道，否则会连累其它端口的转发。
     */
    private volatile boolean tunnelSource = true;

    /**
     * 设置当前数据来源是否为隧道websocket
     *
     * @param tunnelSource true-数据来自隧道websocket
     */
    public void setTunnelSource(boolean tunnelSource) {
        this.tunnelSource = tunnelSource;
    }

    public TunnelFlow(WebSocket tunnel) {
        this.tunnel = tunnel;
        if (tunnel != null) {
            tunnel.drainHandler(v -> resumeUpstreamAll());
        }
    }

    /**
     * 隧道拥塞时暂停读取内网服务连接，排水后统一恢复
     *
     * @param socket 连接内网服务的socket
     */
    public void pauseUpstream(NetSocket socket) {
        if (tunnel == null || !tunnelSource) {
            return;
        }
        if (tunnel.writeQueueFull() && pausedUpstream.add(socket)) {
            socket.pause();
        }
    }

    /**
     * 连接关闭时移除登记，避免对已关闭连接调用resume
     *
     * @param socket 连接内网服务的socket
     */
    public void removeUpstream(NetSocket socket) {
        pausedUpstream.remove(socket);
    }

    /**
     * 内网服务侧拥塞：暂停读取隧道，让反压沿TCP传导到服务端
     *
     * @param key 连接标识
     */
    public void pauseTunnel(Object key) {
        if (tunnel == null || !tunnelSource) {
            return;
        }
        if (congestedDownstream.add(key)) {
            tunnel.pause();
        }
    }

    /**
     * 内网服务排水：所有连接都排空后才恢复读取隧道
     *
     * @param key 连接标识
     */
    public void resumeTunnel(Object key) {
        if (tunnel == null) {
            return;
        }
        if (congestedDownstream.remove(key) && congestedDownstream.isEmpty()) {
            tunnel.resume();
        }
    }

    private void resumeUpstreamAll() {
        Iterator<NetSocket> iterator = pausedUpstream.iterator();
        while (iterator.hasNext()) {
            iterator.next().resume();
            iterator.remove();
        }
    }
}
