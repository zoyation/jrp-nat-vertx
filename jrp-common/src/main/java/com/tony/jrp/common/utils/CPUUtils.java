package com.tony.jrp.common.utils;
import java.io.InputStream;
import java.util.Scanner;
public class CPUUtils {
    /**
     * 获取当前系统CPU序列，可区分linux系统和windows系统
     */
    public static String getCpuId() throws Exception {
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
        assert cpuId != null;
        return cpuId.toUpperCase().replace(" ", "");
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

}

