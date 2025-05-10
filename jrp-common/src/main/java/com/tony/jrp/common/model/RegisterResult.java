package com.tony.jrp.common.model;

import lombok.Data;

/**
 * 客户端注册结果
 */
@Data
public class RegisterResult {
    /**
     * 注册结果
     */
    private boolean success;
    /**
     * 结果描述
     */
    private String msg;

    public RegisterResult(){

    }

    private RegisterResult(boolean success, String msg) {
        this.success = success;
        this.msg = msg;
    }

    public static RegisterResult result(boolean success,String msg){
        return new RegisterResult(success,msg);
    }
    public static RegisterResult success(){
        return new RegisterResult(true,null);
    }

    public static RegisterResult success(String msg){
        return new RegisterResult(true,msg);
    }

    public static RegisterResult error(String msg){
        return new RegisterResult(false,msg);
    }
}
