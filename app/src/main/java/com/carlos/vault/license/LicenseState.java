package com.carlos.vault.license;

/**
 * 授权状态机。
 *
 * 状态由 {@link LicenseManager#recheckLocalState()} 依据本地票据推导，
 * 服务端返回的 status 是权威输入；离线时退化到本地时钟 + 宽限期判定。
 *
 * 多状态叠加时的优先级（决策 D4）：REVOKED &gt; DEVICE_MISMATCH &gt; EXPIRED &gt; OFFLINE_EXPIRED。
 */
public enum LicenseState {

    /** 从未激活，或票据缺失。 */
    UNACTIVATED,

    /** 票据 HMAC 校验失败（被改过 / 换签名重打包）。 */
    TAMPERED,

    /** 离线超过宽限期，需联网校验（可恢复态）。 */
    OFFLINE_EXPIRED,

    /** 已到期（终态，需续费换新码）。 */
    EXPIRED,

    /** 设备不匹配（终态，需客服换绑）。 */
    DEVICE_MISMATCH,

    /** 已被服务端停用（终态）。 */
    REVOKED,

    /** 离线宽限中，仍可正常使用。 */
    ACTIVE_OFFLINE,

    /** 正常在线有效。 */
    ACTIVE;

    /** 该状态是否允许使用主功能（也是 launchGame 硬闸门的放行依据）。 */
    public boolean usable() {
        return this == ACTIVE || this == ACTIVE_OFFLINE;
    }

    /**
     * 失效状态的严重度排序，数值越大越优先展示（D4）。
     * 非失效态返回 0。
     */
    public int failureRank() {
        switch (this) {
            case REVOKED:
                return 4;
            case DEVICE_MISMATCH:
                return 3;
            case EXPIRED:
                return 2;
            case OFFLINE_EXPIRED:
                return 1;
            case TAMPERED:
            case UNACTIVATED:
            default:
                return 0;
        }
    }

    /**
     * 取两者中更严重的一个（D4）：可用态一律让位于任一失效态。
     * <ul>
     *   <li>两者都可用 → 返回 {@code a}；</li>
     *   <li>仅一方不可用 → 返回该不可用方；</li>
     *   <li>两者都不可用 → 按 {@link #failureRank()} 取大。</li>
     * </ul>
     */
    public static LicenseState worse(LicenseState a, LicenseState b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.usable()) return b.usable() ? a : b;
        if (b.usable()) return a;
        return a.failureRank() >= b.failureRank() ? a : b;
    }

    /** 把服务端返回的 status 字符串映射为本地状态。未知值按 UNACTIVATED 处理。 */
    public static LicenseState fromServerStatus(String status) {
        if (status == null) return UNACTIVATED;
        switch (status) {
            case LicenseConfig.STATUS_ACTIVE:
                return ACTIVE;
            case LicenseConfig.STATUS_EXPIRED:
                return EXPIRED;
            case LicenseConfig.STATUS_REVOKED:
                return REVOKED;
            case LicenseConfig.STATUS_DEVICE_MISMATCH:
                return DEVICE_MISMATCH;
            default:
                return UNACTIVATED;
        }
    }

    /** 锁屏/激活页的标题（文案表见架构设计 §8.7）。 */
    public String title() {
        switch (this) {
            case REVOKED:
                return "授权已被停用";
            case DEVICE_MISMATCH:
                return "设备不匹配";
            case EXPIRED:
                return "授权已到期";
            case OFFLINE_EXPIRED:
                return "需要联网校验";
            case TAMPERED:
            case UNACTIVATED:
            default:
                return "使用受限";
        }
    }
}
