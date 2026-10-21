package com.carlos.vault;

import org.json.JSONObject;

/**
 * 账号顶号恢复策略（P1 契约 §一/§三.3，<b>纯 Java，无 Android 依赖</b>——JVM 单测直跑）。
 *
 * <p>账号级字段（作用于该账号全部角色）：{@code enabled / initialDelayMinutes /
 * retryIntervalSeconds / maxAttempts / cooldownMinutes}。范围与默认值逐字对齐契约 §一；
 * 校验错误文案逐字对齐线上服务端 3002 返回（App 端预校验用同一份字符串，M4d 验收
 * 「范围错误 toast 中文提示，尽量对齐服务端文案」）。
 *
 * <p>冷却取值只允许 15/30/60/120/1440 分钟（下拉选择，展示「15 分钟/30 分钟/1 小时/
 * 2 小时/24 小时」）；UI 下拉天然合法，校验函数仍全覆盖（防绕过 + 服务端同口径）。
 */
public final class RecoverySettings {

    // ---------- 契约 §一 范围与默认 ----------
    public static final int DELAY_MIN = 1, DELAY_MAX = 1440;         // 顶号后等待（分钟）
    public static final int RETRY_MIN = 1, RETRY_MAX = 300;          // 每次间隔（秒）
    public static final int ATTEMPTS_MIN = 0, ATTEMPTS_MAX = 10;     // 连续尝试（0=不自动顶号）
    /** 让号冷却允许值（分钟）。 */
    public static final int[] COOLDOWNS = {15, 30, 60, 120, 1440};

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_DELAY_MINUTES = 30;              // §三.3：默认 30 分钟
    public static final int DEFAULT_RETRY_SECONDS = 5;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_COOLDOWN_MINUTES = 15;

    // ---------- 服务端 3002 校验文案（逐字，勿改字） ----------
    public static final String ERR_DELAY = "顶号后等待必须在 1 到 1440 分钟之间";
    public static final String ERR_RETRY = "重试间隔秒数必须在 1 到 300 之间";
    public static final String ERR_ATTEMPTS = "连续尝试次数必须在 0 到 10 之间";
    public static final String ERR_COOLDOWN = "冷却时长只支持 15 分钟、30 分钟、1 小时、2 小时或 24 小时";

    /** 保存成功 toast（契约 §三.3 逐字）。 */
    public static final String MSG_SAVED = "顶号恢复设置已保存并立即生效";

    public final boolean enabled;
    public final int initialDelayMinutes;
    public final int retryIntervalSeconds;
    public final int maxAttempts;
    public final int cooldownMinutes;

    public RecoverySettings(boolean enabled, int initialDelayMinutes, int retryIntervalSeconds,
                            int maxAttempts, int cooldownMinutes) {
        this.enabled = enabled;
        this.initialDelayMinutes = initialDelayMinutes;
        this.retryIntervalSeconds = retryIntervalSeconds;
        this.maxAttempts = maxAttempts;
        this.cooldownMinutes = cooldownMinutes;
    }

    /** 契约默认值（旧服务端缺 recovery 字段时的 UI 预填）。 */
    public static RecoverySettings defaults() {
        return new RecoverySettings(DEFAULT_ENABLED, DEFAULT_DELAY_MINUTES,
                DEFAULT_RETRY_SECONDS, DEFAULT_MAX_ATTEMPTS, DEFAULT_COOLDOWN_MINUTES);
    }

    /**
     * 从 {@code /accounts} 的账号对象解析（{@code account.recovery}）；
     * 缺对象/缺字段逐项回落默认值（旧服务端容错）。
     */
    public static RecoverySettings fromAccount(org.json.JSONObject account) {
        JSONObject r = AccountPoolCache.recoveryOf(account);
        if (r == null) return defaults();
        return new RecoverySettings(
                r.optBoolean("enabled", DEFAULT_ENABLED),
                clampOr(r, "initialDelayMinutes", DEFAULT_DELAY_MINUTES),
                clampOr(r, "retryIntervalSeconds", DEFAULT_RETRY_SECONDS),
                clampOr(r, "maxAttempts", DEFAULT_MAX_ATTEMPTS),
                clampOr(r, "cooldownMinutes", DEFAULT_COOLDOWN_MINUTES));
    }

    /**
     * 字段读取容错：缺键/类型异常回落默认值；值不在契约范围时也回落默认
     * （服务端只回合法值，异常值视为脏数据，不给输入框填非法初值）。
     */
    private static int clampOr(JSONObject r, String key, int def) {
        if (!r.has(key)) return def;
        int v = r.optInt(key, def);
        switch (key) {
            case "initialDelayMinutes":
                return v >= DELAY_MIN && v <= DELAY_MAX ? v : def;
            case "retryIntervalSeconds":
                return v >= RETRY_MIN && v <= RETRY_MAX ? v : def;
            case "maxAttempts":
                return v >= ATTEMPTS_MIN && v <= ATTEMPTS_MAX ? v : def;
            case "cooldownMinutes":
                return isAllowedCooldown(v) ? v : def;
            default:
                return v;
        }
    }

    /** 冷却值是否在允许列表（15/30/60/120/1440）。 */
    public static boolean isAllowedCooldown(int minutes) {
        for (int c : COOLDOWNS) {
            if (c == minutes) return true;
        }
        return false;
    }

    /** 冷却下拉展示文案（契约 §三.3：15 分钟/30 分钟/1 小时/2 小时/24 小时）。 */
    public static String cooldownLabel(int minutes) {
        switch (minutes) {
            case 15: return "15 分钟";
            case 30: return "30 分钟";
            case 60: return "1 小时";
            case 120: return "2 小时";
            case 1440: return "24 小时";
            default: return minutes + " 分钟";
        }
    }

    /** 展示文案 → 冷却值（下拉选中项；未知文案 → -1）。 */
    public static int cooldownValue(String label) {
        if (label == null) return -1;
        switch (label) {
            case "15 分钟": return 15;
            case "30 分钟": return 30;
            case "1 小时": return 60;
            case "2 小时": return 120;
            case "24 小时": return 1440;
            default: return -1;
        }
    }

    /**
     * 保存前校验（与服务端同规则同文案）：返回 null=合法，否则返回第一条错误
     * 中文文案（toast 直接展示）。校验顺序：等待 → 间隔 → 次数 → 冷却。
     */
    public static String validate(int initialDelayMinutes, int retryIntervalSeconds,
                                  int maxAttempts, int cooldownMinutes) {
        if (initialDelayMinutes < DELAY_MIN || initialDelayMinutes > DELAY_MAX) return ERR_DELAY;
        if (retryIntervalSeconds < RETRY_MIN || retryIntervalSeconds > RETRY_MAX) return ERR_RETRY;
        if (maxAttempts < ATTEMPTS_MIN || maxAttempts > ATTEMPTS_MAX) return ERR_ATTEMPTS;
        if (!isAllowedCooldown(cooldownMinutes)) return ERR_COOLDOWN;
        return null;
    }

    /** 本实例是否全部合法（fromAccount 后可用性自检）。 */
    public boolean valid() {
        return validate(initialDelayMinutes, retryIntervalSeconds, maxAttempts,
                cooldownMinutes) == null;
    }
}
