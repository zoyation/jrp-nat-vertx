package com.tony.jrp.common.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * ip转为长整型数
 */
public class IPUtils {
    public static long ipToLong(String host) {
        InetAddress address = null;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        long ipLong;
        if (address instanceof Inet4Address) { // 确保是IPv6地址
            byte[] bytes = address.getAddress();
            ipLong = Integer.valueOf((bytes[3] & 0xFF)).longValue() << 24
                    | Integer.valueOf((bytes[2] & 0xFF)).longValue() << 16
                    | Integer.valueOf((bytes[1] & 0xFF)).longValue() << 8
                    | Integer.valueOf((bytes[0] & 0xFF)).longValue();

        } else {
            byte[] bytes = address.getAddress();
            ipLong = ((long) bytes[15] & 0xFF) << 112
                    | ((long) bytes[14] & 0xFF) << 104
                    | ((long) bytes[13] & 0xFF) << 96
                    | ((long) bytes[12] & 0xFF) << 88
                    | ((long) bytes[11] & 0xFF) << 80
                    | ((long) bytes[10] & 0xFF) << 72
                    | ((long) bytes[9] & 0xFF) << 64
                    | ((long) bytes[8] & 0xFF) << 56
                    | ((long) bytes[7] & 0xFF) << 48
                    | ((long) bytes[6] & 0xFF) << 40
                    | ((long) bytes[5] & 0xFF) << 32
                    | ((long) bytes[4] & 0xFF) << 24
                    | ((long) bytes[3] & 0xFF) << 16
                    | ((long) bytes[2] & 0xFF) << 8
                    | ((long) bytes[1] & 0xFF);
        }
        return ipLong;
    }

    public static void main(String[] args) {
        /*
        公网IP地址最大值取决于IP地址类型：
        公网IP地址类型
        A类‌：最大值为‌126.255.255.255‌（126.255.255.254为最后一个可用地址）
        B类‌：最大值为‌191.255.255.255‌（191.255.255.254为最后一个可用地址）
        C类‌：最大值为‌223.255.255.255‌（223.255.255.254为最后一个可用地址）
         */
        System.out.println(ipToLong("223.255.255.254"));
        System.out.println(ipToLong("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }
}
