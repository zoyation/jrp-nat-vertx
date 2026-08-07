package com.tony.jrp.common.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 端口转换工具类
 */
public class PortConverter {
    /**
     * 转换：将端口号转换为字节数组
     */
    public static byte[] portToBytes(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口号必须在 0-65535 范围内");
        }
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.order(ByteOrder.BIG_ENDIAN); // 大端序
        buffer.putShort((short) port);
        return buffer.array();
    }

    /**
     * 反向转换：从字节数组还原端口号
     */
    public static int bytesToPort(byte[] portBytes) {
        if (portBytes.length != 2) {
            throw new IllegalArgumentException("字节数组长度必须为2");
        }
        return ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
    }

    /**
     * 获取远程端口的字节数组
     *
     * @param remotePort 远程端口
     * @return 远程端口的字节数组
     */
    public static byte[] getRemotePortByte(int remotePort) {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) remotePort).array();
    }

    public static void main(String[] args) {
        // 测试常见端口
        int[] testPorts = {80, 443, 1080, 8080, 25565, 65535};

        for (int port : testPorts) {
            byte[] bytes = portToBytes(port);
            int restoredPort = bytesToPort(bytes);

            System.out.printf("端口 %5d -> 字节: [0x%02X, 0x%02X] -> 还原: %d %s\n",
                    port,
                    bytes[0] & 0xFF,
                    bytes[1] & 0xFF,
                    restoredPort,
                    port == restoredPort ? "✓" : "✗");
        }
    }
}
