package com.tony.jrp.client.utils;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.SocketAddress;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2P隧道分片传输工具类
 * <p>
 * 实现方式参考P2PTunnel，提供UDP可靠传输能力：
 * 1. 所有经隧道传输的数据统一封装为P2P协议包（DATA/ACK/SYN/FIN/KEEPALIVE），
 * 数据包格式：类型(1字节) + 序列号(4字节) + 确认号(4字节) + 时间戳(8字节) + 数据长度(4字节) + 数据
 * 2. 发送方为每个数据包分配递增序列号，并缓存到重传队列，等待对端ACK确认后才移除
 * 3. 接收方收到数据包后立即回复ACK；收到ACK后发送方从重传队列移除对应数据包
 * 4. 超过重传超时时间仍未确认的数据包自动重传，超过最大重传次数则丢弃
 * 5. 超过UDP阈值的数据报自动分片传输（DATA包数据区：会话ID(4) + 总片数(2) + 片序号(2) + 分片数据），
 * 接收端收集齐所有分片后重组还原为原始数据
 * <p>
 * 协议类型码取值0x0B~0x0F，避开{@code JRPMsgType}(0x00~0x0A)的所有类型码，
 * 因此未经隧道封装的普通JRP消息（注册结果JSON、UDP_TUNNEL_KEEPALIVE心跳等）
 * 不会被误判为隧道协议包。
 */
@Slf4j
public final class UdpFragmentUtil {

    /**
     * P2P数据包类型
     */
    @Getter
    public enum P2PPacketType {
        DATA((byte) 0x0B, "数据传输"),
        ACK((byte) 0x0C, "确认收到"),
        SYN((byte) 0x0D, "同步请求"),
        FIN((byte) 0x0E, "关闭连接"),
        KEEPALIVE((byte) 0x0F, "心跳保活");

        private final byte code;
        private final String desc;

        P2PPacketType(byte code, String desc) {
            this.code = code;
            this.desc = desc;
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
     * P2P数据包头大小：类型(1) + 序列号(4) + 确认号(4) + 时间戳(8) + 数据长度(4)
     */
    public static final int P2P_HEADER_SIZE = 21;
    /**
     * 分片触发阈值（字节），超过该大小的数据报将被分片传输。
     * 默认取MTU友好值1400，避免IP分片导致公网丢包。
     */
    public static final int UDP_FRAGMENT_THRESHOLD = 1400;
    /**
     * 分片头大小（DATA包数据区）：会话ID(4) + 总片数(2) + 片序号(2)
     */
    public static final int FRAGMENT_HEADER_SIZE = 8;
    /**
     * 每个分片的最大数据载荷
     */
    public static final int FRAGMENT_CHUNK_SIZE = UDP_FRAGMENT_THRESHOLD - P2P_HEADER_SIZE - FRAGMENT_HEADER_SIZE;
    /**
     * 分片重组超时时间（毫秒），超时后清理未完成的分片缓存
     */
    private static final long FRAGMENT_TIMEOUT = 30_000L;
    /**
     * 已重组完成的会话缓存保留时间（毫秒），用于去重，避免ACK丢失后重传导致上层收到重复数据
     */
    private static final long COMPLETED_SESSION_KEEP_TIME = 60_000L;
    /**
     * 重传超时时间（毫秒），超过该时间未收到ACK则重传
     */
    private static final long RETRANSMIT_TIMEOUT = 2000L;
    /**
     * 最大重传次数，超过后丢弃数据包
     */
    private static final int MAX_RETRANSMIT_TIMES = 5;

    /**
     * 序列号生成器
     */
    private static final AtomicInteger SEQUENCE_GENERATOR = new AtomicInteger();
    /**
     * 会话ID生成器
     */
    private static final AtomicInteger SESSION_ID_COUNTER = new AtomicInteger();
    /**
     * 重传队列：序列号 -> 待确认的数据包
     */
    private static final Map<Integer, PendingPacket> RETRANSMIT_QUEUE = new ConcurrentHashMap<>();
    /**
     * 分片重组缓存：会话ID -> 分片组装器
     */
    private static final Map<Integer, FragmentAssembler> FRAGMENT_MAP = new ConcurrentHashMap<>();
    /**
     * 已完成重组的会话缓存：会话ID -> 完成时间，用于去重
     */
    private static final Map<Integer, Long> COMPLETED_SESSIONS = new ConcurrentHashMap<>();

    private UdpFragmentUtil() {
    }

    /**
     * 判断数据包是否为P2P隧道协议包
     */
    public static boolean isFragment(Buffer data) {
        return data != null && data.length() >= P2P_HEADER_SIZE && P2PPacketType.getByCode(data.getByte(0)) != null;
    }

    /**
     * 分片发送数据：数据超过阈值时自动拆分为多个数据报发送，
     * 所有数据包统一封装为DATA包并缓存到重传队列，等待对端ACK确认，超时自动重传
     *
     * @param datagramSocket 用于发送的UDP数据报套接字
     * @param requestId      请求id
     * @param data           要发送的数据
     * @param port           目标端口
     * @param host           目标主机
     */
    public static void sendWithFragment(DatagramSocket datagramSocket, int requestId, Buffer data, int port, String host) {
        List<Buffer> packets = encodeDataPackets(data);
        for (Buffer packet : packets) {
            sendDataPacket(datagramSocket, port, host, packet);
        }
    }

    /**
     * 发送心跳保活包（可选），供上层周期性调用，对端收到后回复ACK
     *
     * @param datagramSocket 用于发送的UDP数据报套接字
     * @param port           目标端口
     * @param host           目标主机
     */
    public static void sendHeartbeat(DatagramSocket datagramSocket, int port, String host) {
        Buffer packet = encodePacket(P2PPacketType.KEEPALIVE, SEQUENCE_GENERATOR.getAndIncrement(), 0, System.currentTimeMillis(), new byte[0]);
        datagramSocket.send(packet, port, host);
    }

    /**
     * 发送FIN关闭包，通知对端本端将结束隧道会话。
     * 对端收到后回复ACK确认；若本端后续不再发送数据，上层可自行调用{@link #cleanupExpired()}清理会话资源。
     *
     * @param datagramSocket 用于发送的UDP数据报套接字
     * @param port           目标端口
     * @param host           目标主机
     */
    public static void sendFin(DatagramSocket datagramSocket, int port, String host) {
        if (datagramSocket == null) {
            return;
        }
        Buffer packet = encodePacket(P2PPacketType.FIN, SEQUENCE_GENERATOR.getAndIncrement(), 0, System.currentTimeMillis(), new byte[0]);
        datagramSocket.send(packet, port, host);
        log.info("发送FIN关闭包：target={}:{}", host, port);
    }

    /**
     * 分片数据重组
     *
     * @param datagramSocket 收到数据包所使用的UDP数据报套接字（用于回复ACK）
     * @param sender         数据包发送方地址（用于回复ACK）
     * @param data           收到的数据包
     * @return 重组后的完整数据；若为普通JRP消息则原样返回；若为隧道控制包或分片尚未接收完整则返回null
     */
    public static Buffer assemble(DatagramSocket datagramSocket, SocketAddress sender, Buffer data) {
        if (data == null || data.length() < P2P_HEADER_SIZE) {
            return data;
        }
        byte typeCode = data.getByte(0);
        P2PPacketType type = P2PPacketType.getByCode(typeCode);
        if (type == null) {
            //非隧道协议包（普通JRP消息、注册结果JSON、UDP_TUNNEL_KEEPALIVE心跳等），原样返回
            return data;
        }
        int sequence = data.getInt(1);
        int ack = data.getInt(5);
        int dataLength = data.getInt(17);
        if (dataLength < 0 || P2P_HEADER_SIZE + dataLength > data.length()) {
            log.warn("非法的隧道数据包长度：type={}, dataLength={}, bufferLength={}", type.getDesc(), dataLength, data.length());
            return data;
        }
        Buffer payload = data.getBuffer(P2P_HEADER_SIZE, P2P_HEADER_SIZE + dataLength);
        switch (type) {
            case DATA:
                return handleDataPacket(datagramSocket, sender, sequence, payload);
            case ACK:
                handleAckPacket(ack);
                return null;
            case SYN:
                log.info("收到SYN握手包，回复ACK");
                sendAck(datagramSocket, sender, sequence);
                return null;
            case FIN:
                log.info("收到FIN关闭包，回复ACK");
                sendAck(datagramSocket, sender, sequence);
                return null;
            case KEEPALIVE:
                log.debug("收到心跳保活包，回复ACK");
                sendAck(datagramSocket, sender, sequence);
                return null;
            default:
                return data;
        }
    }

    /**
     * 清理超时资源：重传超时未确认的数据包、清理超时的分片缓存和已完成会话缓存
     */
    public static void cleanupExpired() {
        long now = System.currentTimeMillis();
        //清理超时未完成的分片缓存
        FRAGMENT_MAP.entrySet().removeIf(entry -> now - entry.getValue().getCreateTime() > FRAGMENT_TIMEOUT);
        //清理超时的已完成会话缓存
        COMPLETED_SESSIONS.entrySet().removeIf(entry -> now - entry.getValue() > COMPLETED_SESSION_KEEP_TIME);
        //重传超时未确认的数据包
        for (Map.Entry<Integer, PendingPacket> entry : RETRANSMIT_QUEUE.entrySet()) {
            PendingPacket pending = entry.getValue();
            if (now - pending.getSendTime() > RETRANSMIT_TIMEOUT) {
                if (pending.getRetransmitCount() >= MAX_RETRANSMIT_TIMES) {
                    log.error("数据包重传超过最大次数[{}]，丢弃：seq={}", MAX_RETRANSMIT_TIMES, entry.getKey());
                    RETRANSMIT_QUEUE.remove(entry.getKey());
                } else {
                    log.warn("重传超时数据包：seq={}, retransmitCount={}", entry.getKey(), pending.getRetransmitCount());
                    pending.resend();
                }
            }
        }
    }

    /**
     * 将待发送数据编码为P2P DATA包列表，超过阈值时自动分片
     */
    private static List<Buffer> encodeDataPackets(Buffer data) {
        int sessionId = SESSION_ID_COUNTER.incrementAndGet();
        List<Buffer> packets = new ArrayList<>();
        int total = data.length() > FRAGMENT_CHUNK_SIZE ? (data.length() + FRAGMENT_CHUNK_SIZE - 1) / FRAGMENT_CHUNK_SIZE : 1;
        for (int i = 0; i < total; i++) {
            int start = i * FRAGMENT_CHUNK_SIZE;
            int end = Math.min(data.length(), start + FRAGMENT_CHUNK_SIZE);
            //分片头：会话ID + 总片数 + 片序号 + 分片数据
            Buffer fragmentData = Buffer.buffer(FRAGMENT_HEADER_SIZE + (end - start))
                    .appendInt(sessionId)
                    .appendShort((short) total)
                    .appendShort((short) i)
                    .appendBuffer(data.getBuffer(start, end));
            packets.add(encodePacket(P2PPacketType.DATA, SEQUENCE_GENERATOR.getAndIncrement(), 0, System.currentTimeMillis(), fragmentData.getBytes()));
        }
        return packets;
    }

    /**
     * 编码P2P数据包
     */
    private static Buffer encodePacket(P2PPacketType type, int sequence, int ack, long timestamp, byte[] data) {
        Buffer buffer = Buffer.buffer(P2P_HEADER_SIZE + data.length);
        buffer.appendByte(type.getCode());
        buffer.appendInt(sequence);
        buffer.appendInt(ack);
        buffer.appendLong(timestamp);
        buffer.appendInt(data.length);
        buffer.appendBytes(data);
        return buffer;
    }

    /**
     * 发送DATA数据包并加入重传队列
     */
    private static void sendDataPacket(DatagramSocket datagramSocket, int port, String host, Buffer packet) {
        int sequence = packet.getInt(1);
        RETRANSMIT_QUEUE.put(sequence, new PendingPacket(datagramSocket, host, port, packet));
        datagramSocket.send(packet, port, host).onSuccess(v -> {
            log.debug("发送DATA数据包成功：seq={}, target={}:{}", sequence, host, port);
        }).onFailure(t -> {
            log.debug("发送DATA数据包失败：seq={}, target={}:{}", sequence, host, port);
        });
    }

    /**
     * 发送ACK确认包
     */
    private static void sendAck(DatagramSocket datagramSocket, SocketAddress sender, int ackSequence) {
        if (datagramSocket == null || sender == null) {
            return;
        }
        Buffer ackPacket = encodePacket(P2PPacketType.ACK, SEQUENCE_GENERATOR.getAndIncrement(), ackSequence, System.currentTimeMillis(), new byte[0]);
        datagramSocket.send(ackPacket, sender.port(), sender.host());
    }

    /**
     * 处理DATA数据包：回复ACK、收集分片并重组
     *
     * @param sequence 收到的数据包序列号，用于回复ACK确认
     */
    private static Buffer handleDataPacket(DatagramSocket datagramSocket, SocketAddress sender, int sequence, Buffer payload) {
        //立即回复ACK确认
        sendAck(datagramSocket, sender, sequence);
        if (payload.length() < FRAGMENT_HEADER_SIZE) {
            log.warn("DATA数据包载荷过短，丢弃");
            return null;
        }
        int sessionId = payload.getInt(0);
        int total = payload.getUnsignedShort(4);
        int index = payload.getUnsignedShort(6);
        if (total < 1 || index < 0 || index >= total) {
            log.warn("非法的分片头：sessionId={}, total={}, index={}", sessionId, total, index);
            return null;
        }
        Buffer chunk = payload.getBuffer(FRAGMENT_HEADER_SIZE, payload.length());
        if (chunk.length() > FRAGMENT_CHUNK_SIZE) {
            //分片载荷超过单个分片上限，非法数据，丢弃
            log.warn("分片载荷超过上限：sessionId={}, index={}, length={}", sessionId, index, chunk.length());
            return null;
        }
        //已完成会话去重，避免ACK丢失重传导致上层收到重复数据
        if (COMPLETED_SESSIONS.containsKey(sessionId)) {
            return null;
        }
        FragmentAssembler assembler = FRAGMENT_MAP.computeIfAbsent(sessionId, k -> new FragmentAssembler(total));
        if (assembler.getTotal() != total) {
            //会话冲突，理论上不会发生，丢弃
            log.error("会话冲突：sessionId={}, total={}", sessionId, total);
            return null;
        }
        boolean added = assembler.put(index, chunk);
        if (added && assembler.isComplete()) {
            FRAGMENT_MAP.remove(sessionId);
            COMPLETED_SESSIONS.put(sessionId, System.currentTimeMillis());
            return assembler.toBuffer();
        }
        return null;
    }

    /**
     * 处理ACK包：从重传队列移除已确认的数据包
     */
    private static void handleAckPacket(int ack) {
        PendingPacket pending = RETRANSMIT_QUEUE.remove(ack);
        if (pending != null) {
            log.debug("收到ACK确认，移除重传队列：seq={}", ack);
        }
    }

    /**
     * 待确认的数据包
     */
    private static class PendingPacket {
        private final DatagramSocket datagramSocket;
        private final String host;
        private final int port;
        private final Buffer packet;
        private final long sendTime;
        private int retransmitCount;

        PendingPacket(DatagramSocket datagramSocket, String host, int port, Buffer packet) {
            this.datagramSocket = datagramSocket;
            this.host = host;
            this.port = port;
            this.packet = packet;
            this.sendTime = System.currentTimeMillis();
        }

        long getSendTime() {
            return sendTime;
        }

        int getRetransmitCount() {
            return retransmitCount;
        }

        void resend() {
            retransmitCount++;
            datagramSocket.send(packet, port, host);
            log.debug("重发数据包：seq={}, target={}:{}", packet.getInt(1), host, port);
        }
    }

    /**
     * 分片组装器
     */
    private static class FragmentAssembler {
        private final int total;
        private final Buffer[] chunks;
        private final long createTime = System.currentTimeMillis();
        private int receivedCount;

        FragmentAssembler(int total) {
            this.total = total;
            this.chunks = new Buffer[total];
        }

        int getTotal() {
            return total;
        }

        long getCreateTime() {
            return createTime;
        }

        /**
         * 存放分片，返回是否新增分片
         */
        boolean put(int index, Buffer chunk) {
            synchronized (this) {
                if (chunks[index] == null) {
                    chunks[index] = chunk;
                    receivedCount++;
                    return true;
                }
            }
            return false;
        }

        boolean isComplete() {
            return receivedCount == total;
        }

        Buffer toBuffer() {
            int length = 0;
            for (Buffer chunk : chunks) {
                length += chunk.length();
            }
            Buffer result = Buffer.buffer(length);
            for (Buffer chunk : chunks) {
                result.appendBuffer(chunk);
            }
            return result;
        }
    }
}
