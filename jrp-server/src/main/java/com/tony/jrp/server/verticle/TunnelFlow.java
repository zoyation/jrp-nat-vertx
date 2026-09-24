package com.tony.jrp.server.verticle;

import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隧道（内网客户端注册的websocket）流量控制协调器。
 * <p>
 * 一个内网客户端注册的所有穿透端口共用同一条隧道websocket（见RegisterTraversalVerticle），
 * 而 Vert.x 的 {@code drainHandler} 是单槽的：如果每个代理Verticle各自注册，后注册的会覆盖
 * 先注册的，先被暂停的用户连接将永远等不到排水回调，传输永久卡死。因此必须由同一个协调器
 * 统一登记、统一恢复。
 * <p>
 * 两个方向的背压：
 * <ol>
 *   <li>上行（用户请求 → 隧道）：隧道写队列满 → 暂停读取用户socket，隧道排水后统一恢复；</li>
 *   <li>下行（内网响应 → 用户）：用户socket写队列满 → 暂停读取隧道，让反压沿TCP传导到内网
 *       客户端（不能去 pause 用户socket，那不产生任何反压，只会让数据在堆里无限堆积）。</li>
 * </ol>
 */
public class TunnelFlow {

    /**
     * 共享的隧道websocket
     */
    private final ServerWebSocket tunnel;
    /**
     * 因隧道写队列满而暂停读取的用户连接
     */
    private final Set<NetSocket> pausedUpstream = ConcurrentHashMap.newKeySet();
    /**
     * 因用户连接写队列满而请求暂停隧道读取的连接标识
     */
    private final Set<Object> congestedDownstream = ConcurrentHashMap.newKeySet();

    public TunnelFlow(ServerWebSocket tunnel) {
        this.tunnel = tunnel;
        tunnel.drainHandler(v -> resumeUpstreamAll());
    }

    /**
     * 隧道拥塞时暂停读取用户连接，排水后统一恢复
     *
     * @param socket 用户连接
     */
    public void pauseUpstream(NetSocket socket) {
        if (tunnel.writeQueueFull() && pausedUpstream.add(socket)) {
            socket.pause();
        }
    }

    /**
     * 连接关闭时移除登记，避免对已关闭连接调用resume
     *
     * @param socket 用户连接
     */
    public void removeUpstream(NetSocket socket) {
        pausedUpstream.remove(socket);
    }

    /**
     * 用户连接拥塞：暂停读取隧道，使反压沿TCP传导到内网客户端
     *
     * @param key 连接标识
     */
    public void pauseTunnel(Object key) {
        if (congestedDownstream.add(key)) {
            tunnel.pause();
        }
    }

    /**
     * 用户连接排水：所有连接都排空后才恢复读取隧道
     *
     * @param key 连接标识
     */
    public void resumeTunnel(Object key) {
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
