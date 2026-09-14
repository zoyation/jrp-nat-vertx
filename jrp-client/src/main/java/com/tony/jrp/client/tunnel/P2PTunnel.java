package com.tony.jrp.client.tunnel;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2P隧道封装
 * 负责P2P数据的封装、解析和可靠传输
 */
@Slf4j
public class P2PTunnel {

    /**
     * P2P数据包类型
     */
    public enum P2PPacketType {
        DATA((byte) 0x01, "数据传输"),
        ACK((byte) 0x02, "确认收到"),
        SYN((byte) 0x03, "同步请求"),
        FIN((byte) 0x04, "关闭连接"),
        KEEPALIVE((byte) 0x05, "心跳保活");

        private final byte code;
        private final String desc;

        P2PPacketType(byte code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public byte getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }

        public static P2PPacketType getByCode(byte code) {
            for (P2PPacketType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * P2P数据包头
     */
    public static class P2PPacketHeader {
        private P2PPacketType type;
        private int sequence;
        private int ack;
        private long timestamp;

        public P2PPacketHeader(P2PPacketType type, int sequence, int ack) {
            this.type = type;
            this.sequence = sequence;
            this.ack = ack;
            this.timestamp = System.currentTimeMillis();
        }

        public P2PPacketHeader(P2PPacketType type, int sequence, int ack, long timestamp) {
            this.type = type;
            this.sequence = sequence;
            this.ack = ack;
            this.timestamp = timestamp;
        }

        // Getters and Setters
        public P2PPacketType getType() { return type; }
        public int getSequence() { return sequence; }
        public int getAck() { return ack; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * P2P数据包
     */
    public static class P2PPacket {
        private P2PPacketHeader header;
        private byte[] data;
        private String proxyId;

        public P2PPacket(P2PPacketHeader header, byte[] data, String proxyId) {
            this.header = header;
            this.data = data;
            this.proxyId = proxyId;
        }

        // Getters and Setters
        public P2PPacketHeader getHeader() { return header; }
        public byte[] getData() { return data; }
        public String getProxyId() { return proxyId; }
    }

    /**
     * P2P隧道状态
     */
    public enum TunnelState {
        CLOSED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    /**
     * 隧道配置
     */
    private final String proxyId;
    private final String targetIp;
    private final int targetPort;
    private final int mtu; // 最大传输单元

    /**
     * UDP Socket
     */
    private DatagramSocket datagramSocket;

    /**
     * 隧道状态
     */
    private volatile TunnelState state = TunnelState.CLOSED;

    /**
     * 序列号生成器
     */
    private final AtomicInteger sequenceGenerator = new AtomicInteger(0);

    /**
     * 接收序列号
     */
    private final AtomicInteger receiveSequence = new AtomicInteger(0);

    /**
     * 确认序列号
     */
    private final AtomicInteger ackSequence = new AtomicInteger(0);

    /**
     * 重传队列
     */
    private final ConcurrentHashMap<Integer, P2PPacket> retransmitQueue = new ConcurrentHashMap<>();

    /**
     * 最后活动时间
     */
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    /**
     * 最大重传次数
     */
    private static final int MAX_RETRANSMIT_TIMES = 3;

    /**
     * 重传超时时间（毫秒）
     */
    private static final long RETRANSMIT_TIMEOUT = 5000;

    /**
     * 心跳间隔（毫秒）
     */
    private static final long HEARTBEAT_INTERVAL = 30000;

    /**
     * 隧道超时时间（毫秒）
     */
    private static final long TUNNEL_TIMEOUT = 60000;

    public P2PTunnel(String proxyId, String targetIp, int targetPort) {
        this.proxyId = proxyId;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.mtu = 1400; // 默认MTU 1400字节
    }

    /**
     * 设置UDP Socket
     */
    public void setDatagramSocket(DatagramSocket socket) {
        this.datagramSocket = socket;
    }

    /**
     * 启动隧道
     */
    public void start() {
        if (state != TunnelState.CLOSED) {
            log.warn("P2P隧道已在运行或正在连接: proxyId={}, state={}", proxyId, state);
            return;
        }

        state = TunnelState.CONNECTING;
        log.info("启动P2P隧道: proxyId={}, target={}:{}", proxyId, targetIp, targetPort);

        // 发送SYN握手包
        sendSyn();

        state = TunnelState.CONNECTED;
        log.info("P2P隧道已建立: proxyId={}", proxyId);
    }

    /**
     * 关闭隧道
     */
    public void close() {
        if (state == TunnelState.CLOSED) {
            return;
        }

        state = TunnelState.DISCONNECTING;
        log.info("关闭P2P隧道: proxyId={}", proxyId);

        // 发送FIN关闭包
        sendFin();

        // 清理资源
        retransmitQueue.clear();

        state = TunnelState.CLOSED;
        log.info("P2P隧道已关闭: proxyId={}", proxyId);
    }

    /**
     * 发送数据
     */
    public boolean sendData(byte[] data) {
        if (state != TunnelState.CONNECTED) {
            log.warn("P2P隧道未连接，无法发送数据: proxyId={}, state={}", proxyId, state);
            return false;
        }

        if (data == null || data.length == 0) {
            log.warn("数据为空，无法发送: proxyId={}", proxyId);
            return false;
        }

        // 分片发送大数据
        if (data.length > mtu) {
            return sendFragmentedData(data);
        }

        // 发送单包数据
        return sendSinglePacket(data);
    }

    /**
     * 发送单包数据
     */
    private boolean sendSinglePacket(byte[] data) {
        int sequence = sequenceGenerator.getAndIncrement();
        P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.DATA, sequence, receiveSequence.get());
        P2PPacket packet = new P2PPacket(header, data, proxyId);

        return sendPacket(packet);
    }

    /**
     * 分片发送大数据
     */
    private boolean sendFragmentedData(byte[] data) {
        int offset = 0;
        int totalFragments = (data.length + mtu - 1) / mtu;

        for (int i = 0; i < totalFragments; i++) {
            int fragmentSize = Math.min(mtu, data.length - offset);
            byte[] fragmentData = new byte[fragmentSize + 1]; // 1 byte for fragment index
            fragmentData[0] = (byte) i;
            System.arraycopy(data, offset, fragmentData, 1, fragmentSize);

            int sequence = sequenceGenerator.getAndIncrement();
            P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.DATA, sequence, receiveSequence.get());
            P2PPacket packet = new P2PPacket(header, fragmentData, proxyId);

            if (!sendPacket(packet)) {
                log.error("分片发送失败: proxyId={}, fragment={}/{}", proxyId, i + 1, totalFragments);
                return false;
            }

            offset += fragmentSize;
        }

        return true;
    }

    /**
     * 发送数据包
     */
    private boolean sendPacket(P2PPacket packet) {
        try {
            Buffer buffer = encodePacket(packet);

            datagramSocket.send(buffer, targetPort, targetIp, asyncResult -> {
                if (asyncResult.succeeded()) {
                    // 加入重传队列
                    if (packet.getHeader().getType() == P2PPacketType.DATA) {
                        retransmitQueue.put(packet.getHeader().getSequence(), packet);
                    }
                    updateActivityTime();
                } else {
                    log.error("P2P数据包发送失败: proxyId={}, seq={}, error={}",
                            proxyId, packet.getHeader().getSequence(), asyncResult.cause().getMessage());
                }
            });

            return true;
        } catch (Exception e) {
            log.error("编码P2P数据包失败: proxyId={}", proxyId, e);
            return false;
        }
    }

    /**
     * 接收数据包
     */
    public void receivePacket(Buffer buffer) {
        try {
            P2PPacket packet = decodePacket(buffer);
            if (packet == null) {
                log.warn("解析P2P数据包失败: proxyId={}", proxyId);
                return;
            }

            updateActivityTime();

            P2PPacketHeader header = packet.getHeader();
            P2PPacketType type = header.getType();

            log.debug("收到P2P数据包: proxyId={}, type={}, seq={}, ack={}",
                    proxyId, type.getDesc(), header.getSequence(), header.getAck());

            switch (type) {
                case DATA:
                    handleDataPacket(packet);
                    break;
                case ACK:
                    handleAckPacket(packet);
                    break;
                case SYN:
                    handleSynPacket(packet);
                    break;
                case FIN:
                    handleFinPacket(packet);
                    break;
                case KEEPALIVE:
                    handleKeepalivePacket(packet);
                    break;
                default:
                    log.warn("未知的P2P数据包类型: type={}", type.getDesc());
            }
        } catch (Exception e) {
            log.error("处理P2P数据包异常: proxyId={}", proxyId, e);
        }
    }

    /**
     * 处理数据包
     */
    private void handleDataPacket(P2PPacket packet) {
        P2PPacketHeader header = packet.getHeader();
        int sequence = header.getSequence();

        // 更新接收序列号
        receiveSequence.set(sequence + 1);

        // 发送ACK确认
        sendAck(sequence);

        // 通知上层应用处理数据（这里需要回调接口）
        onDataReceived(packet.getData());
    }

    /**
     * 处理ACK包
     */
    private void handleAckPacket(P2PPacket packet) {
        int ack = packet.getHeader().getAck();

        // 从重传队列中移除已确认的数据包
        retransmitQueue.remove(ack);
        log.debug("收到ACK确认: proxyId={}, ack={}", proxyId, ack);
    }

    /**
     * 处理SYN包
     */
    private void handleSynPacket(P2PPacket packet) {
        log.info("收到SYN握手包: proxyId={}", proxyId);
        sendAck(packet.getHeader().getSequence());
    }

    /**
     * 处理FIN包
     */
    private void handleFinPacket(P2PPacket packet) {
        log.info("收到FIN关闭包: proxyId={}", proxyId);
        sendAck(packet.getHeader().getSequence());
        close();
    }

    /**
     * 处理心跳包
     */
    private void handleKeepalivePacket(P2PPacket packet) {
        log.debug("收到心跳包: proxyId={}", proxyId);
        sendAck(packet.getHeader().getSequence());
    }

    /**
     * 发送SYN握手包
     */
    private void sendSyn() {
        int sequence = sequenceGenerator.getAndIncrement();
        P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.SYN, sequence, 0);
        P2PPacket packet = new P2PPacket(header, new byte[0], proxyId);
        sendPacket(packet);
    }

    /**
     * 发送ACK确认包
     */
    private void sendAck(int ackSequence) {
        P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.ACK, sequenceGenerator.getAndIncrement(), ackSequence);
        P2PPacket packet = new P2PPacket(header, new byte[0], proxyId);
        sendPacket(packet);
    }

    /**
     * 发送FIN关闭包
     */
    private void sendFin() {
        int sequence = sequenceGenerator.getAndIncrement();
        P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.FIN, sequence, 0);
        P2PPacket packet = new P2PPacket(header, new byte[0], proxyId);
        sendPacket(packet);
    }

    /**
     * 发送心跳包
     */
    public void sendHeartbeat() {
        if (state != TunnelState.CONNECTED) {
            return;
        }

        P2PPacketHeader header = new P2PPacketHeader(P2PPacketType.KEEPALIVE, sequenceGenerator.getAndIncrement(), 0);
        P2PPacket packet = new P2PPacket(header, new byte[0], proxyId);
        sendPacket(packet);
    }

    /**
     * 编码数据包
     */
    private Buffer encodePacket(P2PPacket packet) {
        P2PPacketHeader header = packet.getHeader();
        byte[] data = packet.getData();

        Buffer buffer = Buffer.buffer();

        // 数据包类型 (1 byte)
        buffer.appendByte(header.getType().getCode());

        // 序列号 (4 bytes)
        buffer.appendInt(header.getSequence());

        // 确认序列号 (4 bytes)
        buffer.appendInt(header.getAck());

        // 时间戳 (8 bytes)
        buffer.appendLong(header.getTimestamp());

        // 数据长度 (4 bytes)
        buffer.appendInt(data.length);

        // 数据
        if (data.length > 0) {
            buffer.appendBytes(data);
        }

        return buffer;
    }

    /**
     * 解码数据包
     */
    private P2PPacket decodePacket(Buffer buffer) {
        try {
            if (buffer.length() < 17) { // 最小包头大小: 1 + 4 + 4 + 8 = 17 bytes
                return null;
            }

            // 读取包头
            byte typeCode = buffer.getByte(0);
            P2PPacketType type = P2PPacketType.getByCode(typeCode);
            if (type == null) {
                return null;
            }

            int sequence = buffer.getInt(1);
            int ack = buffer.getInt(5);
            long timestamp = buffer.getLong(9);

            // 读取数据长度
            int dataLength = buffer.getInt(17);

            // 读取数据
            byte[] data = new byte[dataLength];
            if (dataLength > 0 && buffer.length() >= 21 + dataLength) {
                buffer.getBytes(21, 21 + dataLength, data);
            }

            P2PPacketHeader header = new P2PPacketHeader(type, sequence, ack, timestamp);
            return new P2PPacket(header, data, proxyId);
        } catch (Exception e) {
            log.error("解码P2P数据包失败", e);
            return null;
        }
    }

    /**
     * 更新活动时间
     */
    private void updateActivityTime() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    /**
     * 检查隧道是否超时
     */
    public boolean isTimeout() {
        long idleTime = System.currentTimeMillis() - lastActivityTime.get();
        return idleTime > TUNNEL_TIMEOUT;
    }

    /**
     * 重传超时数据包
     */
    public void retransmitTimeoutPackets() {
        long now = System.currentTimeMillis();

        for (P2PPacket packet : retransmitQueue.values()) {
            long packetAge = now - packet.getHeader().getTimestamp();
            if (packetAge > RETRANSMIT_TIMEOUT) {
                log.warn("重传超时数据包: proxyId={}, seq={}, age={}ms",
                        proxyId, packet.getHeader().getSequence(), packetAge);

                // 重传
                sendPacket(packet);

                // 如果超过最大重传次数，关闭隧道
                if (packetAge > RETRANSMIT_TIMEOUT * MAX_RETRANSMIT_TIMES) {
                    log.error("数据包重传次数超过限制，关闭隧道: proxyId={}", proxyId);
                    close();
                    break;
                }
            }
        }
    }

    /**
     * 数据接收回调（需要由上层实现）
     */
    protected void onDataReceived(byte[] data) {
        // 子类实现具体的处理逻辑
    }

    // Getters
    public TunnelState getState() {
        return state;
    }

    public String getProxyId() {
        return proxyId;
    }

    public String getTargetIp() {
        return targetIp;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public long getLastActivityTime() {
        return lastActivityTime.get();
    }
}