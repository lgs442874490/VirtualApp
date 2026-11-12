package com.carlos.vaultserver.passport;

/**
 * FunSDK passport 调用异常：网络失败、响应非法 JSON、上游错误等。
 *
 * <p>消息可包含截断后的上游原文（≤300 字符），但<b>绝不包含</b> Authorization 头、
 * gameKey、session_key、验证码、身份证号等敏感值（构造方负责）。
 */
public class PassportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PassportException(String message) {
        super(message);
    }

    public PassportException(String message, Throwable cause) {
        super(message, cause);
    }
}
