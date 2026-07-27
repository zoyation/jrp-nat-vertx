package com.tony.jrp.server.manager;

import io.vertx.core.datagram.DatagramSocket;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P2P会话管理器
 * 管理P2P打洞会话，维护客户端连接信息
 */
@Slf4j
public class P2PSessionManager {

    /**
     * P2P会话信息
     */
    public static class P2PSession {
        private String clientId;
        private String proxyId;
        private String localIp;
        private Integer localPort;
        private String publicIp;
        private Integer publicPort;
        private DatagramSocket socket;
        private Long lastHeartbeat;
        private Long createTime;

        public P2PSession(String clientId, String proxyId, String localIp, Integer localPort) {
            this.clientId = clientId;
            this.proxyId = proxyId;
            this.localIp = localIp;
            this.localPort = localPort;
            this.createTime = System.currentTimeMillis();
            this.lastHeartbeat = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getClientId() { return clientId; }
        public String getProxyId() { return proxyId; }
        public String getLocalIp() { return localIp; }
        public void setLocalIp(String localIp) { this.localIp = localIp; }
        public Integer getLocalPort() { return localPort; }
        public void setLocalPort(Integer localPort) { this.localPort = localPort; }
        public String getPublicIp() { return publicIp; }
        public void setPublicIp(String publicIp) { this.publicIp = publicIp; }
        public Integer getPublicPort() { return publicPort; }
        public void setPublicPort(Integer publicPort) { this.publicPort = publicPort; }
        public DatagramSocket getSocket() { return socket; }
        public void setSocket(DatagramSocket socket) { this.socket = socket; }
        public Long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(Long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public Long getCreateTime() { return createTime; }
    }

    /**
     * 会话存储：clientId_proxyId -> P2PSession
     */
    private final ConcurrentHashMap<String, P2PSession> sessions = new ConcurrentHashMap<>();

    /**
     * 会话超时时间（毫秒）
     */
    private final long sessionTimeout;

    /**
     * 定时清理任务
     */
    private final ScheduledExecutorService cleanupExecutor;

    public P2PSessionManager(long timeoutSeconds) {
        this.sessionTimeout = timeoutSeconds * 1000;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        startCleanupTask();
    }

    /**
     * 启动会话清理任务
     */
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredSessions();
            } catch (Exception e) {
                log.error("清理过期P2P会话失败", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
        log.info("P2P会话清理任务已启动，间隔60秒");
    }

    /**
     * 清理过期会话
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int cleanedCount = 0;

        for (String key : sessions.keySet()) {
            P2PSession session = sessions.get(key);
            if (session != null && (now - session.getLastHeartbeat()) > sessionTimeout) {
                sessions.remove(key);
                if (session.getSocket() != null) {
                    try {
                        session.getSocket().close();
                    } catch (Exception e) {
                        log.warn("关闭P2P socket失败: {}", e.getMessage());
                    }
                }
                cleanedCount++;
                log.info("清理过期P2P会话: clientId={}, proxyId={}", session.getClientId(), session.getProxyId());
            }
        }

        if (cleanedCount > 0) {
            log.info("已清理 {} 个过期P2P会话", cleanedCount);
        }
    }

    /**
     * 创建或更新会话
     */
    public P2PSession createOrUpdateSession(String clientId, String proxyId, String localIp, Integer localPort) {
        String key = buildSessionKey(clientId, proxyId);
        P2PSession session = sessions.get(key);

        if (session == null) {
            session = new P2PSession(clientId, proxyId, localIp, localPort);
            sessions.put(key, session);
            log.info("创建P2P会话: clientId={}, proxyId={}, localIp={}, localPort={}",
                    clientId, proxyId, localIp, localPort);
        } else {
            session.setLocalIp(localIp);
            session.setLocalPort(localPort);
            session.setLastHeartbeat(System.currentTimeMillis());
            log.debug("更新P2P会话: clientId={}, proxyId={}", clientId, proxyId);
        }

        return session;
    }

    /**
     * 获取会话
     */
    public P2PSession getSession(String clientId, String proxyId) {
        return sessions.get(buildSessionKey(clientId, proxyId));
    }

    /**
     * 更新会话心跳
     */
    public void updateHeartbeat(String clientId, String proxyId) {
        P2PSession session = getSession(clientId, proxyId);
        if (session != null) {
            session.setLastHeartbeat(System.currentTimeMillis());
        }
    }

    /**
     * 设置会话公网地址
     */
    public void setPublicAddress(String clientId, String proxyId, String publicIp, Integer publicPort) {
        P2PSession session = getSession(clientId, proxyId);
        if (session != null) {
            session.setPublicIp(publicIp);
            session.setPublicPort(publicPort);
            log.info("设置P2P会话公网地址: clientId={}, proxyId={}, publicIp={}, publicPort={}",
                    clientId, proxyId, publicIp, publicPort);
        }
    }

    /**
     * 删除会话
     */
    public void removeSession(String clientId, String proxyId) {
        String key = buildSessionKey(clientId, proxyId);
        P2PSession session = sessions.remove(key);
        if (session != null && session.getSocket() != null) {
            try {
                session.getSocket().close();
            } catch (Exception e) {
                log.warn("关闭P2P socket失败: {}", e.getMessage());
            }
        }
        log.info("删除P2P会话: clientId={}, proxyId={}", clientId, proxyId);
    }

    /**
     * 获取所有活跃会话
     */
    public ConcurrentHashMap<String, P2PSession> getAllSessions() {
        return sessions;
    }

    /**
     * 获取会话数量
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * 构建会话键
     */
    private String buildSessionKey(String clientId, String proxyId) {
        return clientId + "_" + proxyId;
    }

    /**
     * 销毁管理器
     */
    public void destroy() {
        cleanupExecutor.shutdown();
        for (P2PSession session : sessions.values()) {
            if (session.getSocket() != null) {
                try {
                    session.getSocket().close();
                } catch (Exception e) {
                    log.warn("关闭P2P socket失败: {}", e.getMessage());
                }
            }
        }
        sessions.clear();
        log.info("P2P会话管理器已销毁");
    }
}