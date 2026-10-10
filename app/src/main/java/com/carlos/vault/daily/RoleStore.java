package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色与调度存储。模型与原版一致：
 * 凭证（号库账号 fpid+token） → 多个角色（serverid+uid+name）。
 * 登录带 serverid 即选择对应区服的角色；登录成功自动登记角色。
 * 调度：每凭证一份 {autoEnabled, serverid, nextRunAt, lastRunAt, windowStart/End}。
 */
public class RoleStore {

    public static class Role {
        public long serverid;
        public long uid;
        public String name = "";
        public boolean enabled;   // 工作区开关（默认关）
        /** 角色元数据（登录摘要/玩家信息补全；老数据无这些字段，optLong/optString 兜底为零值）。 */
        public long level;            // 熔炉等级（0=未知）
        public long power;            // 战力（0=未知）
        public String alliance = "";  // 联盟名（""=未知/无盟）
    }

    /** 某凭证下已登记的角色（按发现顺序）。 */
    public static List<Role> roles(Context ctx, String fpid) {
        List<Role> out = new ArrayList<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("roles:" + fpid, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Role r = new Role();
                r.serverid = o.optLong("serverid", 0);
                r.uid = o.optLong("uid", 0);
                r.name = o.optString("name", "");
                r.enabled = o.optBoolean("enabled", false);
                r.level = o.optLong("level", 0);
                r.power = o.optLong("power", 0);
                r.alliance = o.optString("alliance", "");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 登录成功后登记/刷新角色（按 serverid 去重；无 serverid 也可登记 uid=0 槽）。 */
    public static void registerRole(Context ctx, String fpid, long serverid, long uid, String name) {
        registerRole(ctx, fpid, serverid, uid, name, 0, 0, "");
    }

    /** 带元数据版：等级/战力/联盟名仅在新值有效时覆盖（0/"" 不冲掉旧值，与 uid/name 同策略）。 */
    public static void registerRole(Context ctx, String fpid, long serverid, long uid, String name,
                                    long level, long power, String alliance) {
        try {
            List<Role> list = roles(ctx, fpid);
            Role hit = null;
            for (Role r : list) {
                if (r.serverid == serverid) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) {
                hit = new Role();
                hit.serverid = serverid;
                hit.enabled = false;   // 新角色默认不进工作区
                list.add(hit);
            }
            if (uid > 0) hit.uid = uid;
            if (name != null && !name.isEmpty()) hit.name = name;
            if (level > 0) hit.level = level;
            if (power > 0) hit.power = power;
            if (alliance != null && !alliance.isEmpty()) hit.alliance = alliance;
            saveRoles(ctx, fpid, list);
        } catch (Throwable ignored) {
        }
    }

    /** 更新角色的工作区开关。 */
    public static void setEnabled(Context ctx, String fpid, long serverid, boolean enabled) {
        try {
            List<Role> list = roles(ctx, fpid);
            for (Role r : list) {
                if (r.serverid == serverid) {
                    r.enabled = enabled;
                    break;
                }
            }
            saveRoles(ctx, fpid, list);
        } catch (Throwable ignored) {
        }
    }

    /** 按 (fpid, serverid) 取已登记角色；未登记返回 null。 */
    public static Role roleOf(Context ctx, String fpid, long serverid) {
        if (fpid == null || serverid <= 0) return null;
        for (Role r : roles(ctx, fpid)) {
            if (r.serverid == serverid) return r;
        }
        return null;
    }

    /** 直接改写某角色的 uid（含清 0；钉扎自愈用：串区时期登记的错 uid 需要纠正）。 */
    public static void updateUid(Context ctx, String fpid, long serverid, long uid) {
        if (fpid == null || serverid <= 0) return;
        try {
            List<Role> list = roles(ctx, fpid);
            for (Role r : list) {
                if (r.serverid == serverid) {
                    r.uid = uid;
                    break;
                }
            }
            saveRoles(ctx, fpid, list);
        } catch (Throwable ignored) {
        }
    }

    private static void saveRoles(Context ctx, String fpid, List<Role> list) throws org.json.JSONException {
        JSONArray arr = new JSONArray();
        for (Role r : list) {
            JSONObject o = new JSONObject();
            o.put("serverid", r.serverid);
            o.put("uid", r.uid);
            o.put("name", r.name);
            o.put("enabled", r.enabled);
            o.put("level", r.level);
            o.put("power", r.power);
            o.put("alliance", r.alliance == null ? "" : r.alliance);
            arr.put(o);
        }
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .edit().putString("roles:" + fpid, arr.toString()).apply();
    }

    /** 当前选中的角色（工作区内切换）。 */
    public static long activeRole(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .getLong("active:" + fpid, -1);
    }

    public static void setActiveRole(Context ctx, String fpid, long serverid) {
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .edit().putLong("active:" + fpid, serverid)
                .putBoolean("picked:" + fpid, true).apply();
    }

    /** 是否已明确选过角色（决定自动同步要不要自动挑主角色）。 */
    public static boolean rolePicked(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .getBoolean("picked:" + fpid, false);
    }

    /**
     * 自动挑主角色：优先名字与账号标签匹配的角色（如 913铁柱 → 铁柱、），
     * 否则用登录默认。仅在从未手动选择过时调用。
     */
    public static long pickMainRole(Context ctx, String fpid, String accountLabel, long loginDefaultSid) {
        try {
            String label = accountLabel == null ? "" : accountLabel.replaceAll("[^\\u4e00-\\u9fa5]", "");
            if (label.length() >= 2) {
                for (Role r : roles(ctx, fpid)) {
                    if (r.name != null && r.name.contains(label)) {
                        setActiveRole(ctx, fpid, r.serverid);
                        return r.serverid;
                    }
                }
                // 前缀匹配（标签前2字）
                String head = label.substring(0, Math.min(2, label.length()));
                for (Role r : roles(ctx, fpid)) {
                    if (r.name != null && r.name.contains(head)) {
                        setActiveRole(ctx, fpid, r.serverid);
                        return r.serverid;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        setActiveRole(ctx, fpid, loginDefaultSid);
        return loginDefaultSid;
    }

    // ---------- 自动调度（每凭证） ----------

    public static class Schedule {
        public boolean autoEnabled;
        public long serverid;        // 自动执行用哪个角色（0=默认）
        public long nextRunAt;       // epoch ms
        public long lastRunAt;
    }

    public static Schedule schedule(Context ctx, String fpid) {
        Schedule s = new Schedule();
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE);
            s.autoEnabled = sp.getBoolean("auto:" + fpid, false);
            s.serverid = sp.getLong("server:" + fpid, 0);
            s.nextRunAt = sp.getLong("next:" + fpid, 0);
            s.lastRunAt = sp.getLong("last:" + fpid, 0);
        } catch (Exception ignored) {
        }
        return s;
    }

    public static void saveSchedule(Context ctx, String fpid, Schedule s) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putBoolean("auto:" + fpid, s.autoEnabled)
                .putLong("server:" + fpid, s.serverid)
                .putLong("next:" + fpid, s.nextRunAt)
                .putLong("last:" + fpid, s.lastRunAt)
                .apply();
    }

    // ---------- 任务级 wake（比 nextRunAt 更细的"到点可动"时刻） ----------

    /**
     * 账号下次"到点可动"时刻（epoch ms；0=无）。
     *
     * <p>next:<fpid> 是账号级整轮日常的下次执行（时间窗内每天一次）；
     * wake:<fpid> 是任务级的更早时刻——当前由补给状态驱动（普通补给"下一次可领"
     * online_ts / 限时补给按策略提前量），到点由调度器只补领 supply 单功能，不重跑整轮。
     * 由 DailyRunner.scheduleNextSupplyWake 依据最新补给状态推算覆写。
     */
    public static long wakeAt(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).getLong("wake:" + fpid, 0);
    }

    public static void saveWakeAt(Context ctx, String fpid, long ms) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putLong("wake:" + fpid, ms).apply();
    }

    // ---------- 常驻在线（24h 会话开关，独立于每日自动日常；灰度期仅主力号开启） ----------

    /** 该账号是否 24h 常驻在线（RoleSessionKeeper.syncOnline 依据；默认 false）。 */
    public static boolean keepOnline(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE)
                .getBoolean("keep:" + fpid, false);
    }

    public static void saveKeepOnline(Context ctx, String fpid, boolean on) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putBoolean("keep:" + fpid, on).apply();
    }

    // ---------- 全局时间窗 ----------

    /** 自动执行时间窗（小时，含端点）。默认 5:00-9:00。 */
    public static int[] window(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE);
        return new int[]{sp.getInt("window_start", 5), sp.getInt("window_end", 9)};
    }

    public static void saveWindow(Context ctx, int startHour, int endHour) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putInt("window_start", startHour)
                .putInt("window_end", endHour)
                .apply();
    }
}
