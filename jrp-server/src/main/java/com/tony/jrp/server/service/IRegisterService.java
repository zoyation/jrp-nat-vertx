package com.tony.jrp.server.service;

import com.tony.jrp.server.model.RegisterInfo;

/**
 * 注册信息管理
 */
public interface IRegisterService {
    /**
     * 保存注册信息
     * @param registerInfo 注册信息
     */
    void add(RegisterInfo registerInfo);
    /**
     * 更新注册信息
     * @param registerInfo 注册信息
     */
    void update(RegisterInfo registerInfo);
}
