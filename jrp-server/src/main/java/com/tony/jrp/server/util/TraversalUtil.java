package com.tony.jrp.server.util;

import com.tony.jrp.common.model.ClientProxy;
import com.tony.jrp.common.utils.PortChecker;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 穿透工具类
 */
@Slf4j
public class TraversalUtil {
    /**
     * 允许穿透的最小端口
     */
    public static final int MIN_PORT = 1024;
    /**
     * 允许穿透的最大端口49151
     */
    public static final int MAX_PORT = 49151;

    private TraversalUtil() {

    }

    /**
     * 检查代理的远程端口是否可用
     *
     * @param proxies 代理列表
     * @return 不可用的端口列表
     */
    public static List<String> getInvalidPorts(List<ClientProxy> proxies) {
        //进行端口检查，如果端口被占用了，提示不能使用
        List<String> invalidPorts = new ArrayList<>();
        // 收集当前已配置的远程端口
        for (ClientProxy clientProxy : proxies) {
            Integer remotePort = clientProxy.getRemote_port();
            // 如果端口不为空且在有效范围内，但被占用，则记录为无效端口
            if (remotePort != null && (remotePort < MIN_PORT || remotePort > MAX_PORT || !PortChecker.isUsable(remotePort))) {
                invalidPorts.add(String.valueOf(remotePort));
            }
        }
        return invalidPorts;
    }

    /**
     * 为代理分配端口
     *
     * @param proxies 代理列表
     */
    public static void allocatePort(List<ClientProxy> proxies) {
        Set<Integer> usedPorts = new HashSet<>();
        for (ClientProxy clientProxy : proxies) {
            Integer remotePort = clientProxy.getRemote_port();
            // 如果端口为空、超出范围或被占用，则分配最小可用端口
            if (remotePort == null) {
                int allocatedPort = findMinAvailablePort(usedPorts);
                usedPorts.add(allocatedPort);
                clientProxy.setRemote_port(allocatedPort);
                log.info("为代理 [{}] 自动分配端口: {}", clientProxy.getName(), allocatedPort);
            }
        }
    }

    /**
     * 查找限制范围内最小的未被占用端口
     *
     * @param configuredPorts 当前已配置的远程端口
     * @return 最小可用端口
     */
    private static int findMinAvailablePort(Set<Integer> configuredPorts) {
        // 从 MIN_PORT 开始查找第一个可用端口
        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            // 检查端口是否在当前配置中被使用
            if (configuredPorts.contains(port)) {
                continue;
            }
            // 检查端口是否在系统中可用
            if (PortChecker.isUsable(port)) {
                return port;
            }
        }
        throw new IllegalStateException("在端口范围 " + MIN_PORT + "-" + MAX_PORT + " 内没有可用端口");
    }
}
