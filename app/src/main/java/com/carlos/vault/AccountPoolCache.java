package com.carlos.vault;

import android.content.Context;
import android.util.Log;

import com.carlos.vault.daily.RoleRuntimeState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 账号池缓存（M2）：{@code /v1/vault/accounts} 响应落地 + stale/unsupported 标记。
 *
 * <p>契约（《账号池M2M3-App实现契约》§1）：请求成功（code==0）覆盖缓存并清两标记；
 * 失败分两类（M4 真机验证 §B-13）：
 * <ul>
 *   <li>网络失败/其它业务错 → {@link #markStale}，<b>保留上次数据</b> +「状态获取失败，
 *       点击重试」横幅（UI 不误判离线）；</li>
 *   <li>旧服务端没有该接口（code=3002/not found/HTTP 404，如生产 v4.1.4）→
 *       {@link #markUnsupported}：接口长期不存在而非暂时故障，UI 不显示失败横幅、
 *       直接走 legacy 渲染（server 升级 M1 后成功请求自愈）。</li>
 * </ul>
 * 轮询 5s 由页面调度。
 *
 * <p>解析辅助把 M1 §2.1 的 {@code roles[].state} 对象映射成
 * {@link RoleRuntimeState}（纯函数状态机，文案/动作矩阵单测在 JVM 侧）；
 * 角色缺 state（异常数据）返回 null，UI 回落旧降级展示。
 * 角色在线判定统一走 {@link #isRoleOnline}（online 在 {@code state.online}，
 * 顶层 {@code online} 仅作旧 shape 兼容——M4 §3.1 D-1 教训）。
 */
public final class AccountPoolCache {

    private static final String TAG = "AccountPoolCache";
    private static final String PREFS = "vault_server";   // 与 VaultSync 同 prefs
    private static final String KEY_DATA = "pool_cache";
    private static final String KEY_AT = "pool_at";
    private static final String KEY_STALE = "pool_stale";
    private static final String KEY_UNSUPPORTED = "pool_unsupported";

    /** 旧服务端「接口不存在」业务码（M4 真机实测 v4.1.4 返回 3002 not found）。 */
    public static final int CODE_NOT_FOUND = 3002;

    private AccountPoolCache() {
    }

    /** 成功响应落地（覆盖旧缓存并清 stale/unsupported）。 */
    public static void save(Context app, JSONObject data) {
        if (data == null) return;
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DATA, data.toString())
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putBoolean(KEY_STALE, false)
                    .putBoolean(KEY_UNSUPPORTED, false)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "pool 缓存写入失败: " + t);
        }
    }

    /**
     * 请求失败（网络/其它业务错）：标记 stale，保留上次数据（不误判离线）。
     * 与 unsupported 互斥——一次请求只有一种失败口径，后标记者生效。
     */
    public static void markStale(Context app) {
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_STALE, true)
                    .putBoolean(KEY_UNSUPPORTED, false)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 服务端没有池接口（code=3002/not found/404，生产 v4.1.4 常态）：
     * 标记 unsupported，UI 不显示失败横幅、直接 legacy 渲染。
     */
    public static void markUnsupported(Context app) {
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_UNSUPPORTED, true)
                    .putBoolean(KEY_STALE, false)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 失败回复是否属于「服务端没有该接口」（区分 unsupported 与 stale，纯函数）：
     * 业务码 3002 / message 含 not found（大小写不敏感）/ HTTP 404。
     * 网络异常（code=-1 + 超时等 message）不算——那是 stale。
     */
    public static boolean isUnsupportedReply(int code, String message) {
        if (code == CODE_NOT_FOUND) return true;
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("not found") || m.contains("http 404");
    }

    /** 上次成功的 accounts 响应 data（无缓存返回 null）。 */
    public static JSONObject load(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DATA, "");
            if (raw == null || raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 上次请求是否失败（保留旧数据展示 +「状态获取失败」横幅；unsupported 时不显示）。 */
    public static boolean isStale(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_STALE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务端是否没有池接口（v4.1.4 降级：UI 不显示失败横幅，直接 legacy 渲染）。 */
    public static boolean isUnsupported(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_UNSUPPORTED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 缓存落盘时刻（0=从未成功）。 */
    public static long updatedAt(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---------- 解析辅助（M1 §2.1 结构） ----------

    /** fpid → 账号对象（data.accounts[]；入参 null/异常返回空表）。 */
    public static Map<String, JSONObject> accountsByFpid(JSONObject data) {
        Map<String, JSONObject> out = new HashMap<>();
        if (data == null) return out;
        try {
            JSONArray arr = data.optJSONArray("accounts");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String fpid = o.optString("fpid", "");
                if (!fpid.isEmpty()) out.put(fpid, o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 服务端角色对象 → 纯函数状态机实例；缺 state 对象返回 null（UI 走旧降级）。 */
    public static RoleRuntimeState stateOfRole(JSONObject role) {
        if (role == null) return null;
        JSONObject st = role.optJSONObject("state");
        if (st == null) return null;
        boolean enabled = role.optBoolean("enabled", true);
        return new RoleRuntimeState(
                st.optBoolean("online", false),
                st.optString("runState", RoleRuntimeState.RUN_STOPPED),
                st.optString("recoveryState", RoleRuntimeState.REC_AVAILABLE),
                st.optString("statusMessage", ""),
                st.optBoolean("takeoverRecoveryActive", false),
                st.optLong("nextRecoveryAt", 0L),
                st.optBoolean("startupInProgress", false),
                st.optBoolean("loginInProgress", false),
                st.optString("runScheduleState", RoleRuntimeState.SCHED_ACTIVE),
                enabled);
    }

    /**
     * 服务端角色对象在线判定（M4 D-1 修复，纯函数）：M1 §2.1 在线在
     * {@code roles[].state.online}；缺 state 时回退旧顶层 {@code online} 字段
     * （兼容旧 shape/异常数据）。<b>读角色 online 必须走这里</b>——直接
     * {@code role.optBoolean("online")} 会恒 false，在线行被误判离线。
     * 同账号多角色并行在线（M2d）：逐角色独立判定，互不影响。
     */
    public static boolean isRoleOnline(JSONObject role) {
        if (role == null) return false;
        JSONObject st = role.optJSONObject("state");
        if (st != null) return st.optBoolean("online", false);
        return role.optBoolean("online", false);
    }

    /** 服务端 sid/uid 数字字符串安全解析（非法/≤0 → 0）。 */
    public static long parseNum(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
