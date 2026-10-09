package com.carlos.vault.wjdr.protocol;

import java.util.Map;

/** 游戏协议层异常（简化自反编译版，去掉错误描述符依赖） */
public class GameProtocolException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String proto;
    private final int code;

    public GameProtocolException(String proto, int code, Map<String, Object> content) {
        super(proto + " 响应异常(code=" + code + "): " + String.valueOf(content));
        this.proto = proto;
        this.code = code;
    }

    public String proto() {
        return this.proto;
    }

    public int code() {
        return this.code;
    }
}
