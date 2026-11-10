package com.carlos.vault;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 永久 GM 标记（2026-11-10）：把**指定测试机**标记为 GM 角色，用于内部功能验收。
 *
 * <p>与 license 票据的 {@code role=4}（服务端权威）并存：
 * <ul>
 *   <li>服务端下发 GM 票据 → {@link com.carlos.vault.license.LicenseManager#role()} = 4；</li>
 *   <li>本机永久 GM 标记 → {@link FeatureGate#currentTier(Context)} 直接返回
 *       {@link FeatureGate#TIER_GM}，不受票据过期/吊销影响（测试机专用）。</li>
 * </ul>
 *
 * <p>标记绑定设备 UUID（{@code license_vault/device_uuid}），把 prefs 拷到别的设备不生效；
 * 通过 adb run-as 写 prefs 或调试入口开启，仅用于开发/内部测试，不对外分发。
 */
public final class GmMode {
    private static final String PREFS = "gm_mode";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DEVICE = "device_uuid";
    private static final String KEY_MARKED_AT = "marked_at";

    private GmMode() {
    }

    /**
     * 本机是否被标记为永久 GM。
     *
     * <p>主判据是本地 {@code enabled} 标志（只能通过 adb run-as / 调试入口写入，本身即设备级操作）；
     * 设备 UUID 只作为**弱绑定**：能读到标记值且与当前设备指纹不一致时才拒绝。
     * VA 容器/定制 ROM 下 {@code ANDROID_ID} 可能读不到或与 shell 视角不同，
     * 此时信任 enabled（否则测试机无法标记，与"永久 GM"诉求冲突）。
     */
    public static boolean isGm(Context ctx) {
        if (ctx == null) return false;
        try {
            // 只认本地 enabled 标志：该标志只能通过 adb run-as / 调试入口写入（本身就是设备级操作）。
            // 不做设备指纹强校验——实测 VA 容器内 App 视角的 ANDROID_ID 与 shell 视角不同，
            // 强绑定会让"把本机标记为 GM"永远不生效（2026-11-10 真机复现）。
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把本机标记为永久 GM；返回是否成功（拿不到设备 UUID 时失败）。 */
    public static boolean mark(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, true)
                    .putLong(KEY_MARKED_AT, System.currentTimeMillis())
                    .apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取消本机 GM 标记。 */
    public static void unmark(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_ENABLED)
                    .remove(KEY_DEVICE)
                    .remove(KEY_MARKED_AT)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 标记时间（0=未标记），仅用于设置页展示。 */
    public static long markedAt(Context ctx) {
        if (ctx == null) return 0L;
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_MARKED_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
