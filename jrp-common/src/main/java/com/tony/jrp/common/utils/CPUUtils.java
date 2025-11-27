package com.tony.jrp.common.utils;

import java.io.InputStream;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Scanner;

public class CPUUtils {
    private static String generateFallbackUniqueId() throws SocketException {
        // 方案1: 使用MAC地址
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            byte[] mac = network.getHardwareAddress();
            //mac转为字符串
            if (network.isUp() && mac != null && mac.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (byte b1 : mac) {
                    sb.append(String.format("%02X", b1));
                }
                return sb.toString();
            }
        }
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 获取当前系统CPU序列，可区分linux系统和windows系统
     */
    public static String getCpuId() {
        try {
            String cpuId;
            // 获取当前操作系统名称
            String os = System.getProperty("os.name");
            os = os.toUpperCase();
            // linux系统用Runtime.getRuntime().exec()执行 dmidecode -t processor 查询cpu序列
            // windows系统用 wmic cpu get ProcessorId 查看cpu序列
            if ("LINUX".equals(os)) {
                cpuId = getLinuxCpuId("dmidecode -t processor | grep 'ID'", "ID", ":");
            } else {
                cpuId = getWindowsCpuId();
            }
            return cpuId.toUpperCase().replace(" ", "");
        } catch (Exception e) {
            return generateFingerprint();
        }
    }

    /**
     * 获取linux系统CPU序列
     */
    public static String getLinuxCpuId(String cmd, String record, String symbol) throws Exception {
        String execResult = executeLinuxCmd(cmd);
        String[] infos = execResult.split("\n");
        for (String info : infos) {
            info = info.trim();
            if (info.contains(record)) {
                info = info.replace(" ", "");
                String[] sn = info.split(symbol);
                return sn[1];
            }
        }
        return null;
    }

    public static String executeLinuxCmd(String cmd) throws Exception {
        Runtime run = Runtime.getRuntime();
        Process process;
        process = run.exec(cmd);
        InputStream in = process.getInputStream();
        StringBuilder out = new StringBuilder();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) != -1; ) {
            out.append(new String(b, 0, n));
        }
        in.close();
        process.destroy();
        return out.toString();
    }

    /**
     * 获取windows系统CPU序列
     */
    public static String getWindowsCpuId() throws Exception {
        Process process = Runtime.getRuntime().exec(
                new String[]{"wmic", "cpu", "get", "ProcessorId"});
        process.getOutputStream().close();
        Scanner sc = new Scanner(process.getInputStream());
        sc.next();
        return sc.next();
    }

    /**
     * 生成机器指纹
     */
    public static String generateFingerprint() {
        try {
            StringBuilder fingerprint = new StringBuilder();

            // 1. 系统信息
            fingerprint.append(System.getProperty("os.name"));
            fingerprint.append(System.getProperty("os.arch"));
            fingerprint.append(System.getProperty("user.name"));

            // 2. 网络接口信息
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && !ni.isVirtual() && ni.isUp()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null) {
                        for (byte b : mac) {
                            fingerprint.append(String.format("%02X", b));
                        }
                        break; // 只使用第一个有效的MAC
                    }
                }
            }
            // 3. 环境变量
            fingerprint.append(System.getenv("PROCESSOR_IDENTIFIER"));
            fingerprint.append(System.getenv("PROCESSOR_LEVEL"));
            System.out.println(fingerprint);
            // 4. 生成哈希
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(fingerprint.toString().getBytes());

            // 5. 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (Exception e) {
            // 备用方案：基于时间的随机标识
            return "fallback-" + System.currentTimeMillis() + "-" +
                    Integer.toHexString((int) (Math.random() * 1000000));
        }
    }

    public static void main(String[] args) throws SocketException {
        System.out.println(generateFallbackUniqueId());
        System.out.println(generateFingerprint());
    }
}

