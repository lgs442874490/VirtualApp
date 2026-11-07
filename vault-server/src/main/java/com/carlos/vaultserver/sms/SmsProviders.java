package com.carlos.vaultserver.sms;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 接码平台工厂：type → {@link SmsProvider} 实例。
 *
 * <ul>
 *   <li>{@code d1} → {@link D1SmsProvider}（config: token 必填，baseUrl 可选）</li>
 *   <li>{@code haozhuma} → {@link HaozhuSmsProvider}（config: user/password/sid 必填，server 可选）</li>
 * </ul>
 *
 * <p>未知 type 抛 IllegalArgumentException；必填参数缺失抛明确中文错误。
 */
public final class SmsProviders {

    private SmsProviders() {
    }

    /**
     * 按类型创建接码平台实例。type 大小写不敏感（trim 后小写比较）。
     *
     * @param type      平台标识：d1 / haozhuma
     * @param config    平台配置项（可空键值；必填项缺失抛 IllegalArgumentException）
     * @param transport GET 传输（生产用 {@link D1SmsProvider.HttpUrlConnectionTransport}，单测注入假实现）
     */
    public static SmsProvider create(String type, Map<String, String> config,
            D1SmsProvider.Transport transport) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("接码平台类型 type 不能为空");
        }
        Map<String, String> cfg = config == null ? Collections.emptyMap() : config;
        String t = type.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "d1":
                requireConfig(cfg, "token", "D1 token");
                return new D1SmsProvider(cfg.get("token"), cfg.get("baseUrl"), transport);
            case "haozhuma":
                requireConfig(cfg, "user", "豪猪账号 user");
                requireConfig(cfg, "password", "豪猪密码 password");
                requireConfig(cfg, "sid", "豪猪项目 sid");
                return new HaozhuSmsProvider(cfg.get("server"), cfg.get("user"),
                        cfg.get("password"), cfg.get("sid"), transport);
            default:
                throw new IllegalArgumentException("未知接码平台类型: " + type + "（支持 d1 / haozhuma）");
        }
    }

    /** 必填配置校验：缺失/空白抛「缺少接码平台配置 &lt;label&gt;」。 */
    private static void requireConfig(Map<String, String> cfg, String key, String label) {
        String v = cfg.get(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少接码平台配置 " + label);
        }
    }
}
