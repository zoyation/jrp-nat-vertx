package com.tony.jrp.common.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * 端口检查工具类
 */
public class PortChecker {
    public static final String LOCAL_HOST = "127.0.0.1";

    /**
     * 根据输入端口号，判断是否可使用端口
     *
     * @param port 端口号
     * @return 是否可使用，true-是，false-不可用
     */
    public static boolean isUsable(int port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            // 设置绑定超时
            serverSocket.setSoTimeout(1000);
            // 尝试绑定本地地址和指定端口
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(LOCAL_HOST), port));
            // 绑定成功说明端口可用
            return true;
        } catch (IOException e) {
            // 绑定失败说明端口被占用或其他问题
            return false;
        }
    }
}

