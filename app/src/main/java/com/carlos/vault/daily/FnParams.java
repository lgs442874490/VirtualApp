package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import com.carlos.vault.VaultSync;

import org.json.JSONObject;

/**
 * 功能参数存储（按角色隔离）+ 本机配置模板。
 * - 参数：prefs daily_params 的 "p:fpid:sid:fnId" → JSON 对象（商品勾选/路线/FID清单等）
 * - 模板：把某角色的全部开关+参数快照存到 "template"，可一键套用到本机任意账号/角色
 */
public final class FnParams {

    private static final String PREFS = "daily_params";
    private static final String KEY_TEMPLATE = "template";

    /**
     * 当前选中角色 uid（R2 Phase 2）：同服双角色的开关/参数按 uid 隔离。
     * 返回 0 = 角色未知（旧数据/加载早期）→ 所有读写退回旧键，行为与 Phase 2 前一致。
     */
    private static long currentUid(Context ctx, String fpid) {
        try {
            return RoleStore.activeRoleUid(ctx, fpid);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static JSONObject params(Context ctx, String fpid, long sid, String fnId) {
        return params(ctx, fpid, sid, currentUid(ctx, fpid), fnId);
    }

    /**
     * 读取某角色 uid 的任务参数：优先 {@code p:fpid:sid:uid:fn}；miss 时回退旧键
     * {@code p:fpid:sid:fn} 并**惰性迁移**到 uid 键（旧键保留供回滚/旧版本读取）。
     */
    public static JSONObject params(Context ctx, String fpid, long sid, long uid, String fnId) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String raw = sp.getString(key(fpid, sid, uid, fnId), null);
            if (raw == null && uid > 0L) {
                String legacy = sp.getString(key(fpid, sid, 0L, fnId), null);
                if (legacy != null) {
                    sp.edit().putString(key(fpid, sid, uid, fnId), legacy).apply();
                    raw = legacy;
                }
            }
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveParams(Context ctx, String fpid, long sid, String fnId, JSONObject p) {
        saveParams(ctx, fpid, sid, currentUid(ctx, fpid), fnId, p);
    }

    public static void saveParams(Context ctx, String fpid, long sid, long uid, String fnId, JSONObject p) {
        boolean saved = false;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key(fpid, sid, uid, fnId), p.toString())
                    .apply();
            saved = true;
        } catch (Exception ignored) {
        }
        // 用户设置写盘后立即触发下发（saveSubOn/saveStrategy/saveSelSet/putTopLevel 内部都经
        // 本方法落盘，无需各处重复接线）；失败不影响本地保存。
        if (saved) notifySettingsChanged(ctx);
    }

    /**
     * 用户设置写盘后的轻量下发钩子（2026-10-28 功能型 bug）：训练等级、医院每批治疗数量
     * 这类「设置」此前只写本地，最长要等 10 分钟全量同步才到服务器，用户感知「设置了也
     * 无效」。
     *
     * <p>服务器模式下调用 {@link VaultSync#onAccountsChanged(Context)} 把上行窗口压到约 3 秒
     * （该方法内部自带 3s 去抖，连续调用不会放大请求量）；非服务器模式或无 Context 时
     * no-op；任何异常一律吞掉，绝不因同步失败影响本地保存/页面交互。
     */
    public static void notifySettingsChanged(Context ctx) {
        try {
            if (ctx != null && VaultSync.isServerMode(ctx)) VaultSync.onAccountsChanged(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** 角色发现前写入 sid=0 的参数迁移到真实 sid；目标已有参数时以目标为准。 */
    public static void migrateRole(Context ctx, String fpid, long fromSid, long toSid) {
        if (fpid == null || fpid.isEmpty() || fromSid == toSid) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String fromPrefix = "p:" + fpid + ":" + fromSid + ":";
            String toPrefix = "p:" + fpid + ":" + toSid + ":";
            java.util.Map<String, ?> all = sp.getAll();
            SharedPreferences.Editor ed = sp.edit();
            boolean changed = false;
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(fromPrefix)) continue;
                String target = toPrefix + k.substring(fromPrefix.length());
                if (!all.containsKey(target) && e.getValue() instanceof String) {
                    ed.putString(target, (String) e.getValue());
                }
                ed.remove(k);
                changed = true;
            }
            if (changed) ed.apply();
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

    // ================= 军医所批量治疗参数（2026-10-28 功能型 bug3） =================
    //
    // 服务端 MedicService.heal(tc, maxCount, immediate, requestHelp) 从角色 params 的
    // medic 顶层键读取：heal_count（>0=每批最多治疗该数量，0=全部）与 request_help
    // （治疗后是否 req_queue_ask_for_help 联盟求助，缺省 true）。键名由服务端契约锁定，
    // App 侧不得改名/嵌套到 sub。

    /** 每批治疗数量默认值（用户可改 1-10000）。 */
    public static final int MEDIC_HEAL_COUNT_DEFAULT = 400;
    /** 每批治疗数量下限。 */
    public static final int MEDIC_HEAL_COUNT_MIN = 1;
    /** 每批治疗数量上限。 */
    public static final int MEDIC_HEAL_COUNT_MAX = 10000;
    /** 治疗后联盟求助默认值（与服务端缺省 true 同口径）。 */
    public static final boolean MEDIC_REQUEST_HELP_DEFAULT = true;

    /** 规范化每批治疗数量（纯函数）：范围 1-10000，非法/越界回退默认 400。 */
    public static int normalizeMedicHealCount(int value) {
        return value >= MEDIC_HEAL_COUNT_MIN && value <= MEDIC_HEAL_COUNT_MAX
                ? value : MEDIC_HEAL_COUNT_DEFAULT;
    }

    /** 解析输入框文本（纯函数）：空/非数字/越界回退默认 400。 */
    public static int parseMedicHealCount(String text) {
        if (text == null) return MEDIC_HEAL_COUNT_DEFAULT;
        try {
            return normalizeMedicHealCount(Integer.parseInt(text.trim()));
        } catch (Throwable ignored) {
            return MEDIC_HEAL_COUNT_DEFAULT;
        }
    }

    /** 读 medic 参数里的每批治疗数量（缺失/非法/越界 → 默认 400）。 */
    public static int medicHealCount(JSONObject p) {
        if (p == null) return MEDIC_HEAL_COUNT_DEFAULT;
        return normalizeMedicHealCount(p.optInt("heal_count", MEDIC_HEAL_COUNT_DEFAULT));
    }

    /** 读 medic 参数里的「治疗后联盟求助」(request_help，缺省 true)。 */
    public static boolean medicRequestHelp(JSONObject p) {
        return p == null ? MEDIC_REQUEST_HELP_DEFAULT
                : p.optBoolean("request_help", MEDIC_REQUEST_HELP_DEFAULT);
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

    /** 模块状态行（daily_state mod:fpid:sid[:uid]:fnId JSON：subId→文案）。 */
    public static JSONObject modState(Context ctx, String fpid, long sid, String fnId) {
        return modState(ctx, fpid, sid, currentUid(ctx, fpid), fnId);
    }

    /**
     * 按角色 uid 读模块状态行：优先 {@code mod:fpid:sid:uid:fn}，miss 回退旧键
     * {@code mod:fpid:sid:fn}（服务端 /state 会同时投影旧键给 active 角色，双读兼容）。
     */
    public static JSONObject modState(Context ctx, String fpid, long sid, long uid, String fnId) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE);
            String raw = sp.getString(modKey(fpid, sid, uid, fnId), null);
            if (raw == null && uid > 0L) raw = sp.getString(modKey(fpid, sid, 0L, fnId), null);
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
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
        saveModState(ctx, fpid, sid, currentUid(ctx, fpid), fnId, o);
    }

    public static void saveModState(Context ctx, String fpid, long sid, long uid, String fnId,
                                    JSONObject o) {
        try {
            ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE).edit()
                    .putString(modKey(fpid, sid, uid, fnId), o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 参数键（R2 Phase 2）：{@code p:<fpid>:<sid>:<uid>:<fn>}（uid&gt;0）；
     * uid&lt;=0 退化为旧键 {@code p:<fpid>:<sid>:<fn>}（旧数据/回滚兼容）。
     */
    static String key(String fpid, long sid, long uid, String fnId) {
        return uid > 0L
                ? "p:" + fpid + ":" + sid + ":" + uid + ":" + fnId
                : "p:" + fpid + ":" + sid + ":" + fnId;
    }

    /** 模块状态键（R2 Phase 2）：{@code mod:<fpid>:<sid>:<uid>:<fn>}；uid&lt;=0 → 旧键。 */
    static String modKey(String fpid, long sid, long uid, String fnId) {
        return uid > 0L
                ? "mod:" + fpid + ":" + sid + ":" + uid + ":" + fnId
                : "mod:" + fpid + ":" + sid + ":" + fnId;
    }

    // ================= 守护盟友配置（批次11；1:1 服务器 ServerGuardianEngine.parseConfig） =================

    /** 守护盟友功能 id（与服务器 ServerGuardianEngine.FN_ID 一致）。 */
    public static final String FN_GUARDIAN = "guardian";

    /** 提前量默认 38 秒（1:1 服务器 DEFAULT_D_SEC）。 */
    public static final int GUARDIAN_DEFAULT_D_SEC = 38;

    /** 守护盟友配置（默认=关闭 + 演练；实发必须 App 侧二次确认后才写入 dry_run=false）。 */
    public static final class GuardianCfg {
        public boolean enabled = false;
        /** 演练模式：只记录不派兵（默认 true，服务器同口径）。 */
        public boolean dryRun = true;
        public boolean recall = true;
        /**
         * 强制上线（2026-10-24）：盟友存在被攻击风险且来袭兵力≥预警阈值时，
         * 允许服务端在暂停/被顶号/掉线状态抢登回连后派兵驻防（默认关）。
         */
        public boolean forceOnline = false;
        /** 来袭兵力预警下限（敌方 squad_list.soldier 合计；0=不限；兵力未知时守护侧保守不派）。 */
        public long troopsMin = 0L;
        public long allyUid = 0L;
        public long targetUid = 0L;
        public int targetX = 0;
        public int targetY = 0;
        public int dSec = GUARDIAN_DEFAULT_D_SEC;

        public boolean hasTargetPoint() {
            return targetX > 0 && targetY > 0;
        }

        public String modeText() {
            return dryRun ? "演练模式（只记录不派兵）" : "实发模式（命中后自动派兵驻防）";
        }
    }

    /**
     * 解析守护盟友 params JSON（纯函数）。
     * 落点兼容 top-level（App 配置页新写法）/ sub / strategy（旧 FnDetail 开关），
     * 与服务器 {@code first(sub,st,top)} 同口径；缺省：enabled=false、dry_run=true、
     * recall=true、d_sec=38、target_point 空。
     */
    public static GuardianCfg guardianCfg(JSONObject p) {
        GuardianCfg c = new GuardianCfg();
        if (p == null) return c;
        try {
            JSONObject sub = p.optJSONObject("sub");
            JSONObject st = p.optJSONObject("strategy");
            // 总开关语义（2026-10-24，与服务器 ServerGuardianEngine.parseConfig 同口径）：
            // 显式 enabled 键为权威值；仅旧数据缺 enabled 键时才用 intercept 兼容启用。
            c.enabled = gHas(sub, st, p, "enabled")
                    ? gBool(sub, st, p, "enabled", false)
                    : gBool(sub, st, p, "intercept", false);
            c.dryRun = gBool(sub, st, p, "dry_run", true);
            c.recall = gBool(sub, st, p, "recall", true);
            c.forceOnline = gBool(sub, st, p, "force_online", false);
            c.troopsMin = Math.max(0L, gNum(sub, st, p, "troops_min", 0L));
            c.allyUid = Math.max(0L, gNum(sub, st, p, "ally_uid", 0L));
            c.targetUid = Math.max(0L, gNum(sub, st, p, "target_uid", 0L));
            Object tp = gFirst(sub, st, p, "target_point");
            long tx = 0, ty = 0;
            if (tp instanceof JSONObject) {
                tx = ((JSONObject) tp).optLong("x", 0);
                ty = ((JSONObject) tp).optLong("y", 0);
            } else if (tp instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) tp;
                if (arr.length() >= 2) {
                    tx = arr.optLong(0, 0);
                    ty = arr.optLong(1, 0);
                }
            }
            c.targetX = (int) Math.max(0, Math.min(Integer.MAX_VALUE, tx));
            c.targetY = (int) Math.max(0, Math.min(Integer.MAX_VALUE, ty));
            long d = gNum(sub, st, p, "d_sec", GUARDIAN_DEFAULT_D_SEC);
            c.dSec = (d >= 10L && d <= 600L) ? (int) d : GUARDIAN_DEFAULT_D_SEC;
        } catch (Throwable ignored) {
        }
        return c;
    }

    /**
     * 写回守护盟友配置（纯函数）：只覆盖守护 key，保留 prev 里其它键（尤其是统一编队
     * formation_mode/formation_id/soldiers/ratio/total，不能被配置页保存清掉）。
     * target_point 坐标为 0 时移除该键（可空=未指定）。
     */
    public static JSONObject applyGuardianCfg(JSONObject prev, GuardianCfg cfg) {
        JSONObject out;
        try {
            out = prev == null ? new JSONObject() : new JSONObject(prev.toString());
        } catch (Throwable t) {
            out = new JSONObject();
        }
        if (cfg == null) return out;
        try {
            out.put("enabled", cfg.enabled);
            out.put("dry_run", cfg.dryRun);
            out.put("recall", cfg.recall);
            out.put("force_online", cfg.forceOnline);
            out.put("troops_min", Math.max(0, cfg.troopsMin));
            out.put("ally_uid", Math.max(0, cfg.allyUid));
            out.put("target_uid", Math.max(0, cfg.targetUid));
            out.put("d_sec", Math.max(10, Math.min(600, cfg.dSec)));
            if (cfg.hasTargetPoint()) {
                JSONObject pt = new JSONObject();
                pt.put("x", cfg.targetX);
                pt.put("y", cfg.targetY);
                out.put("target_point", pt);
            } else {
                out.remove("target_point");
            }
            // 旧 intercept 开关由 enabled/dry_run 接管（服务器 enabled||intercept，故顶层移除）。
            // 同时镜像到 App 本地模式读取的 sub 开关（GuardianEngine：watch=监测、intercept=自动驻防）：
            //   演练模式 → 只监测不驻防（watch=true, intercept=false）；
            //   实发模式 → 监测+自动驻防（watch=true, intercept=true）。
            out.remove("intercept");
            JSONObject sub = out.optJSONObject("sub");
            if (sub == null) sub = new JSONObject();
            sub.put("watch", cfg.enabled);
            sub.put("intercept", cfg.enabled && !cfg.dryRun);
            out.put("sub", sub);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 守护盟友账本统计（服务端 srv:guardian:{fpid}:{sid}:ledger：JSON 数组、最新在前）。 */
    public static final class GuardianLedger {
        public int total;
        /** 演练命中次数（dry_run）。 */
        public int dryRun;
        /** 实发（launched）。 */
        public int launched;
        /** 发射/撤防失败（launch_failed/recall_failed/recall_error）。 */
        public int failed;
        /** 撤防（recalled）。 */
        public int recalled;
        /** 跳过（skip_*：离线/队列/编队缺失等）。 */
        public int skipped;
        /** 威胁事件（threat_*）。 */
        public int threats;
        public long lastTs;
        public String lastEv = "";
        public String lastNote = "";

        public String text() {
            if (total == 0) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("演练").append(dryRun).append(" · 实发").append(launched)
                    .append(" · 失败").append(failed).append(" · 撤防").append(recalled)
                    .append(" · 跳过").append(skipped);
            if (lastEv != null && !lastEv.isEmpty()) {
                sb.append("\n最近：").append(lastEv);
                if (lastTs > 0) {
                    sb.append(' ').append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                            java.util.Locale.CHINA).format(new java.util.Date(lastTs)));
                }
                if (lastNote != null && !lastNote.isEmpty()) sb.append(" · ").append(lastNote);
            }
            return sb.toString();
        }
    }

    /** 解析守护账本 JSON 数组（纯函数；坏条目跳过）。 */
    public static GuardianLedger guardianLedger(org.json.JSONArray arr) {
        GuardianLedger l = new GuardianLedger();
        if (arr == null) return l;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            l.total++;
            String ev = e.optString("ev", "");
            if ("dry_run".equals(ev)) l.dryRun++;
            else if ("launched".equals(ev)) l.launched++;
            else if ("launch_failed".equals(ev) || "recall_failed".equals(ev)
                    || "recall_error".equals(ev)) l.failed++;
            else if ("recalled".equals(ev)) l.recalled++;
            else if (ev.startsWith("skip_")) l.skipped++;
            else if (ev.startsWith("threat_")) l.threats++;
            long ts = e.optLong("ts", 0);
            if (i == 0) {   // 服务端数组最新在前
                l.lastEv = ev;
                l.lastTs = ts;
                l.lastNote = e.optString("note", "");
            }
        }
        return l;
    }

    /** 守护盟友 state 键：账本（服务器写 → VaultSync 回写 daily_state 同名键）。 */
    public static String guardianLedgerKey(String fpid, long sid) {
        return "srv:guardian:" + (fpid == null ? "" : fpid.trim()) + ":" + sid + ":ledger";
    }

    /** 守护盟友 state 键：状态行 mod:{fpid}:{sid}:guardian。 */
    public static String guardianModKey(String fpid, long sid) {
        return "mod:" + (fpid == null ? "" : fpid.trim()) + ":" + sid + ":guardian";
    }

    /**
     * 纯函数：账本文本 → JSON 数组（服务器 state 值是「数组 JSON 字符串」；
     * 兼容被包成 {"ledger":[...]} 的形状；非法/空 → 空数组，绝不抛异常）。
     */
    public static org.json.JSONArray parseGuardianLedgerText(String raw) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (raw == null || raw.trim().isEmpty()) return out;
        String t = raw.trim();
        try {
            if (t.startsWith("[")) {
                org.json.JSONArray arr = new org.json.JSONArray(t);
                return arr == null ? out : arr;
            }
            org.json.JSONObject root = new org.json.JSONObject(t);
            org.json.JSONArray arr = root.optJSONArray("ledger");
            return arr == null ? out : arr;
        } catch (Throwable ignored) {
            return out;
        }
    }

    /** 读守护账本（daily_state；缺失返回空数组，不抛异常）。 */
    public static org.json.JSONArray loadGuardianLedger(Context ctx, String fpid, long sid) {
        if (ctx == null) return new org.json.JSONArray();
        try {
            String raw = ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE)
                    .getString(guardianLedgerKey(fpid, sid), null);
            return parseGuardianLedgerText(raw);
        } catch (Throwable ignored) {
            return new org.json.JSONArray();
        }
    }

    /** 读守护配置（FnParams guardian params）。 */
    public static GuardianCfg loadGuardianCfg(Context ctx, String fpid, long sid) {
        return guardianCfg(params(ctx, fpid, sid, FN_GUARDIAN));
    }

    /** 保存守护配置（保留同 params 里的统一编队字段）。 */
    public static void saveGuardianCfg(Context ctx, String fpid, long sid, GuardianCfg cfg) {
        try {
            JSONObject prev = params(ctx, fpid, sid, FN_GUARDIAN);
            saveParams(ctx, fpid, sid, FN_GUARDIAN, applyGuardianCfg(prev, cfg));
        } catch (Throwable ignored) {
        }
    }

    private static Object gFirst(JSONObject sub, JSONObject st, JSONObject top, String key) {
        if (sub != null && sub.has(key)) return sub.opt(key);
        if (st != null && st.has(key)) return st.opt(key);
        return top == null ? null : top.opt(key);
    }

    /** 键是否显式存在（三处任一）——区分「显式 false」与「旧数据缺键」。 */
    private static boolean gHas(JSONObject sub, JSONObject st, JSONObject top, String key) {
        if (sub != null && sub.has(key)) return true;
        if (st != null && st.has(key)) return true;
        return top != null && top.has(key);
    }

    private static long gNum(JSONObject sub, JSONObject st, JSONObject top, String key, long def) {
        Object v = gFirst(sub, st, top, key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean gBool(JSONObject sub, JSONObject st, JSONObject top, String key,
                                 boolean def) {
        Object v = gFirst(sub, st, top, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)) return false;
        }
        return def;
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
                    ed.putString(key(fpid, sid, currentUid(ctx, fpid), fn.id),
                            p == null ? "" : p.toString());
                }
                ed.apply();
            }
            // 模板套用同时改开关与参数，直接触发一次下发（与 saveParams 同口径）
            notifySettingsChanged(ctx);
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
