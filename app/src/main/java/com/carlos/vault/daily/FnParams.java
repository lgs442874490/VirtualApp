package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 功能参数存储（按角色隔离）+ 本机配置模板。
 * - 参数：prefs daily_params 的 "p:fpid:sid:fnId" → JSON 对象（商品勾选/路线/FID清单等）
 * - 模板：把某角色的全部开关+参数快照存到 "template"，可一键套用到本机任意账号/角色
 */
public final class FnParams {

    private static final String PREFS = "daily_params";
    private static final String KEY_TEMPLATE = "template";

    public static JSONObject params(Context ctx, String fpid, long sid, String fnId) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String raw = sp.getString(key(fpid, sid, fnId), null);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveParams(Context ctx, String fpid, long sid, String fnId, JSONObject p) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key(fpid, sid, fnId), p.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    // ================= 子项开关 / 策略 =================

    /** 子项开关（存于 fnId 参数的 sub:{subId:bool}，缺省用 def）。 */
    public static boolean subOn(Context ctx, String fpid, long sid, String fnId, String subId, boolean def) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            JSONObject sub = p.optJSONObject("sub");
            if (sub != null && sub.has(subId)) return sub.getBoolean(subId);
        } catch (Exception ignored) {
        }
        return def;
    }

    public static void saveSubOn(Context ctx, String fpid, long sid, String fnId, String subId, boolean on) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            JSONObject sub = p.optJSONObject("sub");
            if (sub == null) sub = new JSONObject();
            sub.put(subId, on);
            p.put("sub", sub);
            saveParams(ctx, fpid, sid, fnId, p);
        } catch (Exception ignored) {
        }
    }

    /** 子项策略（存于 strategy:{subId:value}）。 */
    public static String strategy(Context ctx, String fpid, long sid, String fnId, String subId, String def) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            JSONObject st = p.optJSONObject("strategy");
            if (st != null && st.has(subId)) return st.getString(subId);
        } catch (Exception ignored) {
        }
        return def;
    }

    public static void saveStrategy(Context ctx, String fpid, long sid, String fnId, String subId, String val) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            JSONObject st = p.optJSONObject("strategy");
            if (st == null) st = new JSONObject();
            st.put(subId, val);
            p.put("strategy", st);
            saveParams(ctx, fpid, sid, fnId, p);
        } catch (Exception ignored) {
        }
    }

    // ================= 子项多选集（清单勾选，如商店商品/报名时段） =================

    /** 子项多选集（存于 sel:{subId:{val:true}}）。 */
    public static java.util.Set<String> selSet(Context ctx, String fpid, long sid, String fnId, String subId) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            JSONObject sel = params(ctx, fpid, sid, fnId).optJSONObject("sel");
            JSONObject o = sel == null ? null : sel.optJSONObject(subId);
            if (o != null) {
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) out.add(it.next());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 整体替换某子项的多选集。 */
    public static void saveSelSet(Context ctx, String fpid, long sid, String fnId, String subId,
                                  java.util.Collection<String> vals) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            JSONObject sel = p.optJSONObject("sel");
            if (sel == null) sel = new JSONObject();
            JSONObject o = new JSONObject();
            for (String v : vals) o.put(v, true);
            sel.put(subId, o);
            p.put("sel", sel);
            saveParams(ctx, fpid, sid, fnId, p);
        } catch (Exception ignored) {
        }
    }

    /** 写参数顶层键（如 act_frostfire 的 slots 数组）。 */
    public static void putTopLevel(Context ctx, String fpid, long sid, String fnId, String key, Object val) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            p.put(key, val);
            saveParams(ctx, fpid, sid, fnId, p);
        } catch (Exception ignored) {
        }
    }

    /** 自定义策略分钟数。 */
    public static int strategyMinutes(Context ctx, String fpid, long sid, String fnId, String subId, int def) {
        try {
            JSONObject p = params(ctx, fpid, sid, fnId);
            return p.optInt("strategy_min_" + subId, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    /** 模块状态行（daily_state mod:fpid:sid:fnId JSON：subId→文案）。 */
    public static JSONObject modState(Context ctx, String fpid, long sid, String fnId) {
        try {
            String raw = ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE)
                    .getString("mod:" + fpid + ":" + sid + ":" + fnId, null);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 合并写入单个子项状态文案（自动记录更新时间与触发来源：手动/定时/通知）。 */
    public static void saveModText(Context ctx, String fpid, long sid, String fnId, String subId, String text) {
        try {
            JSONObject o = modState(ctx, fpid, sid, fnId);
            o.put(subId, text);
            o.put(subId + "_ts", System.currentTimeMillis());
            o.put(subId + "_trg", DailyRunner.SOURCE);
            saveModState(ctx, fpid, sid, fnId, o);
        } catch (Exception ignored) {
        }
    }

    public static void saveModState(Context ctx, String fpid, long sid, String fnId, JSONObject o) {
        try {
            ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE).edit()
                    .putString("mod:" + fpid + ":" + sid + ":" + fnId, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String key(String fpid, long sid, String fnId) {
        return "p:" + fpid + ":" + sid + ":" + fnId;
    }

    // ================= 模板 =================

    /** 当前模板（无则返回 null）。结构：{enabled:{fnId:bool}, params:{fnId:{...}}} */
    public static JSONObject template(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_TEMPLATE, null);
            return raw == null ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把指定角色的全部开关+参数保存为模板。 */
    public static boolean saveTemplate(Context ctx, String fpid, long sid) {
        try {
            JSONObject tpl = new JSONObject();
            JSONObject enabled = new JSONObject();
            JSONObject params = new JSONObject();
            java.util.Map<String, Boolean> cfg = new DailyConfigStore(ctx).roleConfig(fpid, sid);
            for (FnRegistry.Fn fn : FnRegistry.all()) {
                Boolean on = cfg == null ? null : cfg.get(fn.id);
                enabled.put(fn.id, on == null ? fn.implemented && defaultOn(fn.id) : on);
                JSONObject p = params(ctx, fpid, sid, fn.id);
                if (p.length() > 0) params.put(fn.id, p);
            }
            tpl.put("enabled", enabled);
            tpl.put("params", params);
            tpl.put("savedAt", System.currentTimeMillis());
            tpl.put("savedFrom", fpid + ":" + sid);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TEMPLATE, tpl.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把模板一键套用到指定角色（开关+参数全覆盖）。 */
    public static boolean applyTemplate(Context ctx, String fpid, long sid) {
        JSONObject tpl = template(ctx);
        if (tpl == null) return false;
        try {
            JSONObject enabled = tpl.optJSONObject("enabled");
            JSONObject params = tpl.optJSONObject("params");
            if (enabled != null) {
                java.util.Map<String, Boolean> cfg = new java.util.LinkedHashMap<>();
                for (FnRegistry.Fn fn : FnRegistry.all()) {
                    if (enabled.has(fn.id)) cfg.put(fn.id, enabled.getBoolean(fn.id));
                }
                new DailyConfigStore(ctx).saveRoleConfig(fpid, sid, cfg);
            }
            if (params != null) {
                SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                for (FnRegistry.Fn fn : FnRegistry.all()) {
                    JSONObject p = params.optJSONObject(fn.id);
                    ed.putString(key(fpid, sid, fn.id), p == null ? "" : p.toString());
                }
                ed.apply();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean defaultOn(String fnId) {
        for (DailyTasks.TaskDef def : DailyTasks.all()) {
            if (def.id.equals(fnId)) return def.enabledByDefault;
        }
        return false;
    }

    private FnParams() {
    }
}
