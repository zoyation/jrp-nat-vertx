package com.tony.jrp.common.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public class PortChecker {
    /**
     * 根据输入端口号，递增递归查询可使用端口
     *
     * @param port 端口号
     * @return 如果被占用，递归；否则返回可使用port
     */
    public static int getUsablePort(int port) throws IOException {
        boolean flag = false;
        Socket socket = null;
        InetAddress theAddress = InetAddress.getByName("127.0.0.1");
        try {
            socket = new Socket(theAddress, port);
            flag = true;
        } catch (IOException e) {
            //如果测试端口号没有被占用，那么会抛出异常，通过下文flag来返回可用端口
        } finally {
            if (socket != null) {
                //new了socket最好释放
                socket.close();
            }
        }

        if (flag) {
            //端口被占用，port + 1递归
            port = port + 1;
            return getUsablePort(port);
        } else {
            //可用端口
            return port;
        }
    }

    /**
     * 根据输入端口号，判断是否可使用端口
     *
     * @param port 端口号
     * @return 是否可使用，true-是，false-不可用
     */
    public static boolean isUsable(int port) {
        boolean usable;
        Socket socket = null;
        try {
            InetAddress theAddress = InetAddress.getByName("127.0.0.1");
            socket = new Socket(theAddress, port);
            usable = false;
        } catch (IOException e) {
            //如果测试端口号没有被占用，那么会抛出异常，通过下文flag来返回可用端口
            usable = true;
        } finally {
            if (socket != null) {
                //new了socket最好释放
                try {
                    socket.close();
                } catch (IOException ignored) {

                }
            }
        }
        return usable;
    }
}

