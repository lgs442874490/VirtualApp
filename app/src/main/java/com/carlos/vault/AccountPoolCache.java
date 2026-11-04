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

    /**
     * 服务端角色对象 → 纯函数状态机实例；缺 state 对象返回 null（UI 走旧降级）。
     * P1：附解析 {@code pause_resume_at}（ISO-8601，可空）→ {@code pauseResumeAt}，
     * PAUSED 态倒计时数据源；旧服务端缺字段 → 0，展示口径回落 M2（无后缀）。
     */
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
                enabled,
                pauseResumeAtOf(role));
    }

    /**
     * 账号恢复策略对象（P1 §二：{@code account.recovery}，账号级作用于全部角色）。
     * 旧服务端缺字段返回 null（UI 回落默认值，见 {@link RecoverySettings#defaults()}）。
     */
    public static JSONObject recoveryOf(JSONObject account) {
        if (account == null) return null;
        try {
            return account.optJSONObject("recovery");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 角色暂停自动恢复时刻（P1 §二 {@code roles[].pause_resume_at}，ISO-8601 可空）。
     * 兼容口径：ISO-8601（{@code Z}/{@code ±HH[:MM]} 偏移/无偏移按 UTC）、纯数字按
     * epoch millis 直读；缺字段/解析失败一律 0（=无，不猜）。
     */
    public static long pauseResumeAtOf(JSONObject role) {
        if (role == null) return 0L;
        try {
            Object v = role.opt("pause_resume_at");
            if (v == null) return 0L;
            if (v instanceof Number) {
                long ms = ((Number) v).longValue();
                return ms > 0 ? ms : 0L;
            }
            return parseIsoMs(String.valueOf(v));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * ISO-8601 时刻 → epoch millis（纯 JDK，无 Android 依赖，JVM 可测）。
     * 支持 {@code yyyy-MM-dd'T'HH:mm:ss}（毫秒与小数秒可选）、尾缀 {@code Z} 或
     * {@code ±HH[:MM]} 偏移（无偏移按 UTC）；无法解析返回 0。
     */
    static long parseIsoMs(String s) {
        if (s == null) return 0L;
        s = s.trim();
        if (s.isEmpty()) return 0L;
        // 纯数字：服务端数值口径容错（epoch millis）
        if (s.matches("\\d+")) {
            try {
                long ms = Long.parseLong(s);
                return ms > 0 ? ms : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        }
        try {
            if (s.length() < 16 || s.charAt(10) != 'T') return 0L;
            int year = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(5, 7));
            int day = Integer.parseInt(s.substring(8, 10));
            String rest = s.substring(11);
            long offsetMs = 0L;
            if (rest.endsWith("Z") || rest.endsWith("z")) {
                rest = rest.substring(0, rest.length() - 1);
            } else {
                int plus = rest.lastIndexOf('+');
                int minus = rest.lastIndexOf('-');
                int cut = Math.max(plus, minus);
                if (cut > 0) {
                    offsetMs = parseOffsetMs(rest.substring(cut));
                    rest = rest.substring(0, cut);
                }
            }
            long fracMs = 0L;
            int dot = rest.indexOf('.');
            if (dot >= 0) {
                String frac = rest.substring(dot + 1);
                rest = rest.substring(0, dot);
                // 小数秒（.5 / .123 / .123456）截取前 3 位补齐为毫秒
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    sb.append(i < frac.length() ? frac.charAt(i) : '0');
                }
                fracMs = Integer.parseInt(sb.toString());
            }
            String[] hms = rest.split(":");
            if (hms.length < 2 || hms.length > 3) return 0L;
            int hh = Integer.parseInt(hms[0]);
            int mm = Integer.parseInt(hms[1]);
            int ss = hms.length == 3 ? Integer.parseInt(hms[2]) : 0;
            // 严格范围校验（Calendar 默认 lenient 会把 13 月/99 日滚动进位，必须先拦）
            if (month < 1 || month > 12 || day < 1 || day > 31
                    || hh > 23 || mm > 59 || ss > 59) return 0L;
            java.util.GregorianCalendar cal =
                    new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.setLenient(false);   // 2 月 30 日等真实历法非法日同样拒绝（getTimeInMillis 抛错→0）
            cal.set(year, month - 1, day, hh, mm, ss);
            // 偏移表示「本地 = UTC + offset」→ epoch = 无偏移解析值 - offset
            return cal.getTimeInMillis() + fracMs - offsetMs;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** {@code ±HH[:MM]} / {@code ±HHMM} 偏移 → millis（非法 → 抛给上层归零）。 */
    private static long parseOffsetMs(String off) {
        char sign = off.charAt(0);
        String digits = off.substring(1).replace(":", "");
        int hh = Integer.parseInt(digits.substring(0, 2));
        int mm = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
        long v = hh * 3_600_000L + mm * 60_000L;
        return sign == '-' ? -v : v;
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

    /**
     * 按 (sid, uid) 在账号池账号对象里找服务端角色行（P1：恢复设置读取与
     * HomeActivity 行匹配共用）。uid 任一侧未知时按 sid 兜底（同服同 sid 多角色
     * uid 未同步的行可能串状态——已知限制，随角色元数据同步自愈）。
     */
    public static JSONObject findRole(JSONObject poolAcc, long sid, long uid) {
        if (poolAcc == null) return null;
        try {
            JSONArray roles = poolAcc.optJSONArray("roles");
            if (roles == null) return null;
            JSONObject hit = null;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject ro = roles.optJSONObject(i);
                if (ro == null) continue;
                if (parseNum(ro.optString("sid", "")) != sid) continue;
                long ruid = parseNum(ro.optString("uid", "0"));
                if (uid > 0 && ruid > 0 && ruid != uid) continue;
                hit = ro;
                if (uid > 0 && ruid == uid) break;   // 精确命中优先
            }
            return hit;
        } catch (Throwable t) {
            return null;
        }
    }
}
