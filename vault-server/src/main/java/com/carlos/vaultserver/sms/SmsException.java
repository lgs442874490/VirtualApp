package com.carlos.vaultserver.sms;

/**
 * 接码平台调用异常：网络失败、响应非法、上游错误、号码解析失败等。
 *
 * <p>消息含平台名 + 操作 + 上游错误文本（截断 ≤300 字符），但<b>绝不包含</b>
 * token / 账号 / 密码等敏感值（构造方负责）。
 */
public class SmsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SmsException(String message) {
        super(message);
    }

    public SmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
