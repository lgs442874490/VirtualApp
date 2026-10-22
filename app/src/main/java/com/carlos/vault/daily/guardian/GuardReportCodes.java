package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 防守战报码表校准（开发计划遗留 #1/#4，交接 20261017 §四）。
 *
 * 背景：驻防成败以战报邮件为准（用户 20261017 裁决）。战斗邮件 first_type=30（P1 实证
 * 守城级联），但 second_type 码表只从进攻战报摸清：4/5=胜 6/7/8=败（来源：recon 模块分类，
 * 交接 20261017 §四.1）——**防守战报语义未校准**，实测防守样本 second_type=1 落在码表外。
 *
 * 规则（本类固化）：
 *  - 已收录码 → win/loss 判定文案；
 *  - 未收录码（映射缺项）→ 不判成败、不触发撤防（撤防仍由敌情解除事件驱动，邮件从不触发），
 *    写日志 GUARD_REPORT_UNKNOWN first=.. second=.. raw=..，并记入 FnParams 待校准列表
 *    （顶层 "report_unknown"："first:second" → {count,lastAt,raw}，封顶 20 条）；
 *  - 覆盖通道：FnParams 策略项 report_codes（JSON 字符串，机制同 power_min 等策略项），形状：
 *      {"roles":{"30":"战斗/防守战报"},                 // first_type → 角色标注（决定哪些邮件按战报处理）
 *       "second":{"1":"loss"},                          // second_type → win/loss/unknown（并集进默认表）
 *       "by_first":{"30":{"5":"unknown"}}}              // 按 first_type 覆盖（最高优先级）
 *    显式映射为 "unknown" = 已校准为未知（不判成败，也不再进待校准列表）。
 */
public final class GuardReportCodes {

    public static final String WIN = "win";
    public static final String LOSS = "loss";
    public static final String UNKNOWN = "unknown";

    /** 默认战斗邮件 first_type（P1 实证：守城级联 + 驻防参战战报均为 30）。 */
    public static final long DEF_FIRST_BATTLE = 30L;

    /** 待校准列表封顶（防被垃圾邮件刷爆 FnParams）。 */
    static final int MAX_PENDING = 20;
    private static final int RAW_CLIP = 160;

    private GuardReportCodes() {
    }

    /** 战报映射表：内置默认 + 用户覆盖合并。纯逻辑，可单测。 */
    public static final class Mapping {
        /** first_type → 角色标注（键集=按战报处理的邮件类型）。 */
        private final Map<Long, String> roles = new LinkedHashMap<>();
        /** second_type → verdict 默认表（来源：进攻战报 4/5=胜 6/7/8=败，防守语义待校准）。 */
        private final Map<Long, String> second = new LinkedHashMap<>();
        /** first_type → (second_type → verdict) 覆盖表（最高优先级）。 */
        private final Map<Long, Map<Long, String>> byFirst = new LinkedHashMap<>();

        Mapping() {
            roles.put(DEF_FIRST_BATTLE, "战斗/防守战报");
            // 默认表（来源标注：进攻战报映射，recon 模块分类，交接 20261017；防守样本未校准）
            second.put(4L, WIN);
            second.put(5L, WIN);
            second.put(6L, LOSS);
            second.put(7L, LOSS);
            second.put(8L, LOSS);
        }

        public boolean isBattleFirst(long firstType) {
            return roles.containsKey(firstType);
        }

        public String roleOf(long firstType) {
            String r = roles.get(firstType);
            return r == null ? "战报" : r;
        }

        /**
         * verdict 判定：by_first[first][second] → second[second] → null（未收录）。
         * 返回值语义：win=胜 loss=败 unknown=已校准为未知 null=未收录（调用方须走 GUARD_REPORT_UNKNOWN）。
         */
        public String verdictOf(long firstType, long secondType) {
            Map<Long, String> over = byFirst.get(firstType);
            if (over != null) {
                String v = over.get(secondType);
                if (v != null) return v;
            }
            return second.get(secondType);
        }

        public int roleCount() {
            return roles.size();
        }

        public int secondSize() {
            return second.size();
        }

        /** 解析覆盖 JSON（并集合并进默认表；空/非法 → 纯默认表）。 */
        public static Mapping parse(String json) {
            Mapping m = new Mapping();
            if (json == null) return m;
            String s = json.trim();
            if (s.isEmpty()) return m;
            try {
                JSONObject o = new JSONObject(s);
                JSONObject roles = o.optJSONObject("roles");
                if (roles != null) {
                    Iterator<String> it = roles.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long ft = parseLong(k);
                        String v = roles.optString(k, "").trim();
                        if (ft > 0 && !v.isEmpty()) m.roles.put(ft, v);
                    }
                }
                JSONObject second = o.optJSONObject("second");
                if (second != null) {
                    Iterator<String> it = second.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long st = parseLong(k);
                        String v = normalize(second.optString(k, ""));
                        if (st >= 0 && v != null) m.second.put(st, v);
                    }
                }
                JSONObject byFirst = o.optJSONObject("by_first");
                if (byFirst != null) {
                    Iterator<String> it = byFirst.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long ft = parseLong(k);
                        JSONObject inner = byFirst.optJSONObject(k);
                        if (ft <= 0 || inner == null) continue;
                        Map<Long, String> map = m.byFirst.get(ft);
                        if (map == null) {
                            map = new LinkedHashMap<>();
                            m.byFirst.put(ft, map);
                        }
                        Iterator<String> it2 = inner.keys();
                        while (it2.hasNext()) {
                            String k2 = it2.next();
                            long st = parseLong(k2);
                            String v = normalize(inner.optString(k2, ""));
                            if (st >= 0 && v != null) map.put(st, v);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非法 JSON：保留内置默认表（宁缺毋滥——错误配置不能把败判成胜触发误撤防）
            }
            return m;
        }

        private static long parseLong(String s) {
            try {
                return (long) Double.parseDouble(s);
            } catch (Throwable t) {
                return -1L;
            }
        }

        /** verdict 值归一：win/loss/unknown 之外的取值一律视为 unknown（防手滑写错触发误判）。 */
        private static String normalize(String v) {
            String s = v == null ? "" : v.trim().toLowerCase(java.util.Locale.CHINA);
            if (s.isEmpty()) return null;
            return WIN.equals(s) || LOSS.equals(s) ? s : UNKNOWN;
        }
    }

    // ------------------------------------------------------------------ Android 胶水

    /** 读映射：FnParams 策略项 report_codes（JSON）并集合并进内置默认表。 */
    public static Mapping load(Context ctx, String fpid, long sid) {
        return Mapping.parse(FnParams.strategy(ctx, fpid, sid, GuardianEngine.FN_ID, "report_codes", ""));
    }

    /** 参数覆盖入口（UI/参数通道）：保存 report_codes 策略值（JSON 字符串）。 */
    public static void saveOverride(Context ctx, String fpid, long sid, String json) {
        FnParams.saveStrategy(ctx, fpid, sid, GuardianEngine.FN_ID, "report_codes",
                json == null ? "" : json);
    }

    /**
     * 未收录码入待校准列表（FnParams 顶层 "report_unknown"：
     * "first:second" → {first,second,count,lastAt,raw}，去重计数、封顶 20 条）。
     */
    public static void recordUnknown(Context ctx, String fpid, long sid, long firstType,
                                     long secondType, String raw) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, GuardianEngine.FN_ID);
            JSONObject list = p.optJSONObject("report_unknown");
            if (list == null) list = new JSONObject();
            String key = firstType + ":" + secondType;
            JSONObject e = list.optJSONObject(key);
            if (e == null) {
                if (list.length() >= MAX_PENDING) {
                    return;   // 已满：不挤占已收样本（校准靠针对性实测，不靠堆量）
                }
                e = new JSONObject();
                e.put("first", firstType);
                e.put("second", secondType);
                e.put("count", 0L);
                list.put(key, e);
            }
            e.put("count", e.optLong("count", 0L) + 1L);
            e.put("lastAt", System.currentTimeMillis());
            e.put("raw", clip(raw, RAW_CLIP));
            p.put("report_unknown", list);
            FnParams.saveParams(ctx, fpid, sid, GuardianEngine.FN_ID, p);
        } catch (Throwable ignored) {
        }
    }

    /** 待校准列表（只读投影，状态行/排查用）。 */
    public static JSONObject unknownList(Context ctx, String fpid, long sid) {
        try {
            JSONObject list = FnParams.params(ctx, fpid, sid, GuardianEngine.FN_ID)
                    .optJSONObject("report_unknown");
            return list == null ? new JSONObject() : list;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 截断（日志/FnParams 里 raw 只留判读所需首段）。 */
    public static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
