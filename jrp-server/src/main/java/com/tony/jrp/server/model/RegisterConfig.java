package com.tony.jrp.server.model;

import com.tony.jrp.common.model.ClientProxy;
import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

/**
 * 注册配置文件
 */
@Data
public class RegisterConfig {
    /**
     * 支持注册的客户端最大值
     */
    private Integer max_client = 100;
    /**
     * 支持穿透的端口数量最大值
     */
    private Integer max_port_num = 100;
    /**
     * 所有实时注册列表
     */
    private List<RegisterInfo> real_list;
    /**
     * 所有注册列表
     */
    private List<RegisterInfo> register_list;
}
