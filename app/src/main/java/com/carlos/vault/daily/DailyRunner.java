/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  org.json.JSONArray
 *  org.json.JSONObject
 */
package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class DailyRunner {
    public static final int[] BOX_THRESHOLDS;
    static final long[][] CAL_FAMILIES;
    public static final String[] FROSTFIRE_SLOTS;
    public static volatile String SOURCE;

    static {
        BOX_THRESHOLDS = new int[]{40, 80, 120, 160, 215, 270, 325};
        CAL_FAMILIES = new long[][]{
                {2965, 0}, {2436, 0}, {2450, 0}, {2415, 0}, {2937, 0}, {2705, 0}, {2438, 0},
                {2426, 0}, {2413, 0}, {2403, 0}, {2418, 0}, {2904, 0}, {2417, 0}, {2934, 0},
                {2412, 0}, {2404, 0}};
        FROSTFIRE_SLOTS = new String[]{"12:30", "16:00", "19:00", "20:30", "22:00"};
    }

    /*
     * Exception decompiling
     */
    public static void captureCalendar(Context context, WjdrEngine engine, String fpid, long sid) {
        try {
            Map content = null;
            try {
                GameMessage rsp = engine.request("req_activity_calendar_get_data", null);
                if (rsp != null && rsp.getContent() != null) content = rsp.getContent();
            } catch (Throwable ignored) {
            }
            if (content == null || content.isEmpty()) {
                content = engine.notifyContent("notify_activity_calendar_data");
            }
            if (content == null) return;
            debugLog(context, "calendar raw=" + String.valueOf(content));
            Object dataObj = content.get("data");
            if (dataObj == null) dataObj = content;
            if (!(dataObj instanceof Map)) return;
            JSONArray todos = new JSONArray();
            SharedPreferences sp = context.getSharedPreferences("daily_state", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis() / 1000L;
            SimpleDateFormat md = new SimpleDateFormat("MM-dd", Locale.CHINA);
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            long todayStart = cal.getTimeInMillis() / 1000L;
            for (Object v : ((Map) dataObj).values()) {
                if (!(v instanceof Map)) continue;
                Object idObj = ((Map) v).get("id");
                long id = idObj instanceof Number ? ((Number) idObj).longValue() : 0;
                if (id <= 0) {
                    boolean parsed = false;
                    for (Map.Entry e : ((Map<?, ?>) dataObj).entrySet()) {
                        if (e.getValue() == v) {
                            try {
                                id = (long) Double.parseDouble(String.valueOf(e.getKey()).trim());
                                parsed = true;
                            } catch (Throwable ignored2) {
                            }
                            break;
                        }
                    }
                    if (!parsed) continue;
                }
                long family = id / 1000;
                Object schedObj = ((Map) v).get("schedule");
                if (!(schedObj instanceof List)) continue;
                for (Object so : (List) schedObj) {
                    if (!(so instanceof Map)) continue;
                    long begin = num(((Map) so).get("begin_ts"));
                    long end = num(((Map) so).get("end_ts"));
                    if (begin <= 0 || end <= begin) continue;
                    String text;
                    if (begin <= now && end > now) {
                        if (end - todayStart < 86400L) {
                            text = (end - todayStart) < 7200L ? hm.format(new Date(end * 1000)) + " 结束" : "今日结束";
                        } else if (end - now > 48 * 3600L) {
                            text = md.format(new Date(end * 1000)) + " 结束";
                        } else {
                            text = hm.format(new Date(end * 1000)) + " 结束";
                        }
                    } else if (begin > now) {
                        if (begin - todayStart < 86400L) {
                            text = "今日开启 " + hm.format(new Date(begin * 1000));
                        } else if (begin - todayStart < 2 * 86400L) {
                            text = "明日 " + hm.format(new Date(begin * 1000)) + " 开始";
                        } else {
                            text = md.format(new Date(begin * 1000)) + " " + hm.format(new Date(begin * 1000)) + " 开始";
                        }
                    } else {
                        continue;
                    }
                    JSONObject t = new JSONObject();
                    t.put("id", id);
                    t.put("family", family);
                    t.put("name", familyName(family));
                    String fn = familyFn(family);
                    if (fn != null) t.put("fn", fn);
                    t.put("begin", begin);
                    t.put("end", end);
                    t.put("text", text);
                    todos.put(t);
                    if (fn != null) {
                        try {
                            JSONObject mod = FnParams.modState(context, fpid, sid, fn);
                            mod.put("open", text);
                            mod.put("open_ts", System.currentTimeMillis());
                            FnParams.saveModState(context, fpid, sid, fn, mod);
                        } catch (Throwable ignored3) {
                        }
                    }
                }
            }
            sp.edit().putString("todo:" + fpid + ":" + sid, todos.toString()).apply();
        } catch (Throwable t) {
            debugLog(context, "calendar fail: " + t);
        }
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }


    private static void captureFrostfire(WjdrEngine engine, String fpid, long sid) {
        java.util.Map<String, Object> c = engine.notifyContent("notify_frostfire_war_info");
        if (c == null) return;
        Object ai = c.get("act_info");
        if (!(ai instanceof java.util.Map)) return;
        java.util.Map<String, Object> info = (java.util.Map<String, Object>) ai;
        Object idxObj = info.get("sign_up_index");
        Object tsObj = info.get("sign_up_ts");
        long idx = idxObj instanceof Number ? ((Number) idxObj).longValue() : 0;
        long ts = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0;
        try {
            org.json.JSONObject mod = new org.json.JSONObject();
            mod.put("index", idx);
            mod.put("ts", ts);
            FnParams.saveModState(engine.appContext(), fpid, sid, "frostfire", mod);
        } catch (Throwable ignored) {
        }
    }
    /*
     * Exception decompiling
     */

    private static void capturePushStates(WjdrEngine engine, String fpid, long sid) {
        android.content.Context ctx = engine.appContext();
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.CHINA);
        long now = System.currentTimeMillis() / 1000L;
        // 建造(qtype1)/科研(qtype2,8)队列：notify_queue_info/update → queue_map{qid:{base:{qtype},end_time}}
        for (String proto : new String[]{"notify_queue_info", "notify_queue_update"}) {
            try {
                java.util.Map<String, Object> c = engine.notifyContent(proto);
                if (c == null) continue;
                Object qm = c.get("queue_map");
                if (!(qm instanceof java.util.Map)) {
                    java.util.Map<String, Object> d = engine.notifyData(proto);
                    if (d != null && d.get("queue_map") instanceof java.util.Map) qm = d.get("queue_map");
                }
                if (!(qm instanceof java.util.Map)) continue;
                long buildEnd = 0, researchEnd = 0;
                for (Object qv : ((java.util.Map<String, Object>) qm).values()) {
                    if (!(qv instanceof java.util.Map)) continue;
                    java.util.Map<String, Object> q = (java.util.Map<String, Object>) qv;
                    Object baseObj = q.get("base");
                    java.util.Map<String, Object> base = baseObj instanceof java.util.Map
                            ? (java.util.Map<String, Object>) baseObj : q;
                    long qtype = pushNum(base.get("qtype"));
                    long end = pushNum(q.get("end_time"));
                    if (end <= now) continue;
                    if (qtype == 1 && end > buildEnd) buildEnd = end;
                    if ((qtype == 2 || qtype == 8) && end > researchEnd) researchEnd = end;
                }
                if (buildEnd > 0) writeModState(ctx, fpid, sid, "building", "auto",
                        "施工中 · " + tf.format(new java.util.Date(buildEnd * 1000)) + " 完成");
                if (researchEnd > 0) writeModState(ctx, fpid, sid, "research", "auto",
                        "研究中 · " + tf.format(new java.util.Date(researchEnd * 1000)) + " 完成");
                break;
            } catch (Throwable ignored) {
            }
        }
        // 兵营每营队列（notify_barrack_init.data{bid→{soldier_id,finish_ts}} → 收兵倒计时）
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_barrack_init");
            if (c != null) {
                Object dObj = c.get("data");
                if (!(dObj instanceof java.util.Map)) dObj = unwrapPush(c).get("data");
                if (dObj instanceof java.util.Map) {
                    long[] campIds = {321, 322, 323};
                    String[] campKeys = {"collect_shield", "collect_spear", "collect_bow"};
                    String[] campNames = {"盾兵营", "矛兵营", "弓兵营"};
                    long[] finish = {0, 0, 0};
                    for (java.util.Map.Entry<String, Object> e
                            : ((java.util.Map<String, Object>) dObj).entrySet()) {
                        if (!(e.getValue() instanceof java.util.Map)) continue;
                        java.util.Map<String, Object> q = (java.util.Map<String, Object>) e.getValue();
                        long bid = pushNum(q.get("bid"));
                        if (bid <= 0) {
                            try {
                                bid = parseNumKey(e.getKey());
                            } catch (Throwable ignored2) {
                            }
                        }
                        long soldierId = pushNum(q.get("soldier_id"));
                        for (int i = 0; i < 3; i++) {
                            if (bid == campIds[i]) finish[i] = soldierId > 0 ? pushNum(q.get("finish_ts")) : 0;
                        }
                    }
                    java.text.SimpleDateFormat hm2 = new java.text.SimpleDateFormat("HH:mm",
                            java.util.Locale.CHINA);
                    for (int i = 0; i < 3; i++) {
                        String txt;
                        if (finish[i] > now) {
                            java.util.Date d = new java.util.Date(finish[i] * 1000);
                            boolean today = hm2.format(d).compareTo(
                                    hm2.format(new java.util.Date())) >= 0
                                    && finish[i] - now < 86400L;
                            txt = "训练中 · " + (today ? hm2.format(d) : tf.format(d)) + " 可收";
                        } else {
                            txt = "空闲";
                        }
                        writeModState(ctx, fpid, sid, "barracks", campKeys[i], campNames[i] + " " + txt);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 无尽征程积分（notify_general_bp_extra_info → info.info.integral）
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_general_bp_extra_info");
            if (c == null) c = engine.notifyContent("notify_general_bp_normal_info");
            if (c != null) {
                long itg = pushNum(unwrapPush(unwrapPush(c)).get("integral"));
                if (itg <= 0) itg = pushNum(unwrapPush(c).get("integral"));
                if (itg > 0) writeModState(ctx, fpid, sid, "act_endless", "milestone",
                        "当前积分 " + itg);
            }
        } catch (Throwable ignored) {
        }
        // 情报待办（notify_intelligence_info.events{}.status==1 计数）
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_intelligence_info");
            if (c != null) {
                Object evObj = unwrapPush(c).get("events");
                if (evObj instanceof java.util.Map) {
                    int pending = 0;
                    for (Object v : ((java.util.Map<String, Object>) evObj).values()) {
                        if (v instanceof java.util.Map
                                && pushNum(((java.util.Map<String, Object>) v).get("status")) == 1) {
                            pending++;
                        }
                    }
                    writeModState(ctx, fpid, sid, "intel", "events",
                            pending > 0 ? "待办情报 " + pending + " 条" : "情报已清空");
                }
            }
        } catch (Throwable ignored) {
        }
        // 火晶提炼计数
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_spar_info");
            if (c != null) {
                java.util.Map<String, Object> d = unwrapPush(c);
                writeModState(ctx, fpid, sid, "fire_crystal", "purify",
                        "今日提炼 " + pushNum(d.get("purify_count")) + " · 超炼 "
                                + pushNum(d.get("super_purify_count")));
            }
        } catch (Throwable ignored) {
        }
        // 争霸赛报名窗口
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_alliance_showdown_activity");
            if (c != null) {
                Object actObj = unwrapPush(c).get("activity");
                if (actObj instanceof java.util.Map) {
                    long end = pushNum(((java.util.Map<String, Object>) actObj).get("endtime"));
                    if (end > now) {
                        writeModState(ctx, fpid, sid, "alliance_showdown", "apply",
                                "报名窗口 · " + tf.format(new java.util.Date(end * 1000)) + " 结束");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 联盟动员
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_mobilization_alliance_init");
            if (c != null) {
                Object alObj = unwrapPush(c).get("alliance");
                if (alObj instanceof java.util.Map) {
                    java.util.Map<String, Object> al = (java.util.Map<String, Object>) alObj;
                    writeModState(ctx, fpid, sid, "alliance_mobilization", "milestone",
                            "联盟档位 " + pushNum(al.get("grade")) + " · 积分 " + pushNum(al.get("score")));
                }
            }
        } catch (Throwable ignored) {
        }
        // 社交礼物
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_social_gift_info");
            if (c != null) {
                java.util.Map<String, Object> d = unwrapPush(c);
                writeModState(ctx, fpid, sid, "social_gift", "send",
                        "凭证 " + pushNum(d.get("voucher_num")) + " · 积分 "
                                + pushNum(d.get("milestone_point")));
            }
        } catch (Throwable ignored) {
        }
        // 背包资源包
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_backpack_init");
            if (c != null) {
                Object dataObj = unwrapPush(c).get("data");
                if (dataObj == null) dataObj = c.get("items");
                java.util.Map<Long, Long> items = new java.util.TreeMap<>();
                if (dataObj instanceof java.util.List) {
                    for (Object o : (java.util.List<Object>) dataObj) {
                        if (o instanceof java.util.Map) {
                            long id = pushNum(((java.util.Map<String, Object>) o).get("id"));
                            long cnt = pushNum(((java.util.Map<String, Object>) o).get("count"));
                            if (id > 0) items.put(id, cnt);
                        }
                    }
                } else if (dataObj instanceof java.util.Map) {
                    for (Object o : ((java.util.Map<String, Object>) dataObj).values()) {
                        if (o instanceof java.util.Map) {
                            long id = pushNum(((java.util.Map<String, Object>) o).get("id"));
                            long cnt = pushNum(((java.util.Map<String, Object>) o).get("count"));
                            if (id > 0) items.put(id, cnt);
                        }
                    }
                }
                if (!items.isEmpty()) {
                    long fixed = 0, random = 0;
                    for (long id = 100111; id <= 100115; id++) {
                        Long cnt = items.get(id);
                        if (cnt != null) fixed += cnt;
                    }
                    for (long id = 100091; id <= 100093; id++) {
                        Long cnt = items.get(id);
                        if (cnt != null) random += cnt;
                    }
                    writeModState(ctx, fpid, sid, "backpack", "fixed",
                            "固定资源包 ×" + fixed + " · 随机箱 ×" + random);
                }
            }
        } catch (Throwable ignored) {
        }
        // 晨曦岛
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_homeland_info");
            if (c != null) {
                Object hlObj = c.get("homeland_info");
                if (!(hlObj instanceof java.util.Map)) hlObj = unwrapPush(c).get("homeland_info");
                if (hlObj instanceof java.util.Map) {
                    java.util.Map<String, Object> hl = (java.util.Map<String, Object>) hlObj;
                    long giftTs = pushNum(hl.get("free_production_ts"));
                    if (giftTs > 0) {
                        long gifts = Math.min(3, Math.max(0, now - giftTs) / 28800L);
                        writeModState(ctx, fpid, sid, "homeland", "free",
                                gifts > 0 ? "回礼可领 " + gifts + " 个" : "回礼暂无可领（8小时产1个）");
                    }
                    Object mapObj = hl.get("map_info");
                    if (mapObj instanceof java.util.Map) {
                        java.util.Map<String, Object> mapInfo = (java.util.Map<String, Object>) mapObj;
                        Object treeObj = mapInfo.get("life_tree_info");
                        if (treeObj instanceof java.util.Map) {
                            long treeVal = pushNum(((java.util.Map<String, Object>) treeObj).get("cur_value"));
                            writeModState(ctx, fpid, sid, "homeland", "tree",
                                    "生命之树 " + treeVal + " 币可领");
                        }
                        long woodTotal = -1;
                        for (Object b : valuesOf(mapInfo.get("building_infos"))) {
                            if (!(b instanceof java.util.Map)) continue;
                            java.util.Map<String, Object> bd = (java.util.Map<String, Object>) b;
                            long bid = pushNum(bd.get("bid"));
                            Object infoObj = bid == 7001 ? bd.get("logging_cabin_info")
                                    : (bid == 7082 ? bd.get("logging_site_info") : null);
                            if (infoObj instanceof java.util.Map) {
                                long v = pushNum(((java.util.Map<String, Object>) infoObj).get("cur_value"));
                                if (woodTotal < 0) woodTotal = 0;
                                woodTotal += v;
                            }
                        }
                        if (woodTotal >= 0) writeModState(ctx, fpid, sid, "homeland", "logging",
                                "伐木产物累计 " + woodTotal);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 兵营已解锁兵等（notify_soldier_init.soldier → max_shield/spear/bow）
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_soldier_init");
            if (c != null) {
                Object so = unwrapPush(c).get("soldier");
                long[] maxT = {0, 0, 0};
                if (so instanceof java.util.Map) {
                    for (Object k : ((java.util.Map<?, ?>) so).keySet()) {
                        try {
                            long id = k instanceof Number ? ((Number) k).longValue()
                                    : (long) Double.parseDouble(String.valueOf(k).trim());
                            long line = id / 10000, idx = (id % 10000) / 100;
                            if (line >= 1 && line <= 3 && idx > maxT[(int) (line - 1)]) {
                                maxT[(int) (line - 1)] = idx;
                            }
                        } catch (Throwable ignored2) {
                        }
                    }
                }
                debugLog(ctx, "soldier tiers: so=" + (so == null ? "null" : so.getClass().getSimpleName())
                        + " max=" + maxT[0] + "/" + maxT[1] + "/" + maxT[2]);
                if (maxT[0] > 0 || maxT[1] > 0 || maxT[2] > 0) {
                    org.json.JSONObject mod = FnParams.modState(ctx, fpid, sid, "barracks");
                    mod.put("max_shield", maxT[0]);
                    mod.put("max_spear", maxT[1]);
                    mod.put("max_bow", maxT[2]);
                    FnParams.saveModState(ctx, fpid, sid, "barracks", mod);
                }
            }
        } catch (Throwable ignored) {
        }
        // 十四天登录好礼
        try {
            java.util.Map<String, Object> c = engine.notifyContent("notify_fourteen_sign_info");
            if (c != null) {
                java.util.Map<String, Object> d = unwrapPush(c);
                org.json.JSONObject mod = FnParams.modState(ctx, fpid, sid, "login_rewards");
                mod.put("sign_days", pushNum(d.get("sign_days")));
                mod.put("has_pay", Boolean.TRUE.equals(d.get("has_pay")));
                mod.put("server_index", pushNum(d.get("server_index")));
                mod.put("free_rec", dayArrayOf(d.get("free_award_record")));
                mod.put("buy_rec", dayArrayOf(d.get("buy_award_record")));
                mod.put("sign_ts", now);
                FnParams.saveModState(ctx, fpid, sid, "login_rewards", mod);
            }
        } catch (Throwable ignored) {
        }
        // 尚未解析的推送：原始报文落 daily_log.txt
        for (String proto : new String[]{"notify_daily_claim_info", "notify_soldier_init"}) {
            try {
                java.util.Map<String, Object> c = engine.notifyContent(proto);
                if (c == null) continue;
                String raw = String.valueOf(c);
                debugLog(ctx, proto + " raw: "
                        + (raw.length() > 700 ? raw.substring(0, 700) + "…" : raw));
            } catch (Throwable ignored) {
            }
        }
    }
    /*
     * Exception decompiling
     */

    public static void captureQuestState(Context context, WjdrEngine engine, String fpid,
                                         long sid, Summary sum) {
        try {
            java.util.Map<String, Object> q = engine.notifyData("notify_dailyquests");
            if (q == null) return;
            debugLog(context, "dailyquests keys=" + q.keySet());
            Object questsObj = q.get("quests");
            debugLog(context, "quests type=" + (questsObj == null ? "null"
                    : questsObj.getClass().getSimpleName()) + " content=" + String.valueOf(questsObj));
            java.util.Map<Long, Integer> prog = new java.util.LinkedHashMap<>();
            if (questsObj instanceof Map) {
                for (Object e : ((Map) questsObj).entrySet()) {
                    Map.Entry en = (Map.Entry) e;
                    long qid;
                    try {
                        qid = (long) Double.parseDouble(String.valueOf(en.getKey()).trim());
                    } catch (Throwable t) {
                        continue;
                    }
                    Object v = en.getValue();
                    long p2 = 0;
                    if (v instanceof Map) {
                        Object pv = ((Map) v).get("progress");
                        p2 = pv instanceof Number ? ((Number) pv).longValue() : 0;
                    } else if (v instanceof Number) {
                        p2 = ((Number) v).longValue();
                    }
                    prog.put(qid, (int) p2);
                }
            }
            java.util.Set<Long> claimed = new java.util.LinkedHashSet<>();
            for (Object o : valuesOf(q.get("finishs"))) {
                if (o instanceof Number) claimed.add(((Number) o).longValue());
                else if (o instanceof Map) {
                    Object id = ((Map) o).get("id");
                    if (id instanceof Number) claimed.add(((Number) id).longValue());
                }
            }
            long integral = num(q.get("integral"));
            int boxes = 0;
            for (int t : BOX_THRESHOLDS) {
                if (integral >= t) boxes++;
            }
            QuestStore.State st = new QuestStore.State();
            st.progress.putAll(prog);
            st.claimed.addAll(claimed);
            st.integral = (int) integral;
            st.boxes = boxes;
            st.ts = System.currentTimeMillis();
            QuestStore.save(context, fpid, sid, st);
            if (sum != null) {
                sum.activeness = (int) integral;
                sum.boxesReached = boxes;
            }
        } catch (Throwable t) {
            debugLog(context, "queststate fail: " + t);
        }
    }
    /*
     * WARNING - Removed back jump from a try to a catch block - possible behaviour change.
     * Loose catch block
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static DailyResult claimQuest(Context context, String fpid, String sdkToken,
                                         long serverid, long questId) {
        // 在线会话复用（防自顶：常驻会话在线时不再 fresh 登录）
        RoleSessionKeeper.LiveSession live = liveOf(context, fpid, serverid);
        if (live != null) {
            try {
                GameMessage rsp = live.engine.request("req_get_all_dailyquest_award",
                        ExecUtil.args("quest_ids", Collections.singletonList(questId)));
                captureQuestState(context, live.engine, fpid, live.sid, null);
                if (ExecUtil.ok(rsp)) {
                    return new DailyResult("quest", "任务领取", DailyResult.Category.CLAIMED,
                            "任务 " + questId + " 奖励已领取");
                }
                return DailyTasks.classifyServerCode(ExecUtil.codeOf(rsp), "任务领取");
            } catch (Exception e) {
                return DailyTasks.classifyException(e, "任务领取");
            }
        }
        WjdrEngine engine = WjdrAndroid.newEngine(context);
        try {
            HotConfig.init(context);
            engine.initialize();
            applyRoleIdentity(context, engine, fpid, serverid);
            RoleLogin.loginPinned(context, engine, fpid, sdkToken, serverid);
            persistRoleIdentity(context, engine, fpid, serverid);
            GameMessage rsp = engine.request("req_get_all_dailyquest_award",
                    ExecUtil.args("quest_ids", Collections.singletonList(questId)));
            engine.collectNotifies(1200);
            captureQuestState(context, engine, fpid, serverid, null);
            if (ExecUtil.ok(rsp)) {
                return new DailyResult("quest", "任务领取", DailyResult.Category.CLAIMED,
                        "任务 " + questId + " 奖励已领取");
            }
            return DailyTasks.classifyServerCode(ExecUtil.codeOf(rsp), "任务领取");
        } catch (Exception e) {
            return DailyTasks.classifyException(e, "任务领取");
        } finally {
            engine.close();
            // fresh 登录会踢掉该账号的多角色竞速会话，收尾让 keeper 整号重赛建回
            try {
                RoleSessionKeeper.get(context).syncOnline();
            } catch (Throwable ignored) {
            }
        }
    }


    private static void collectRallyLines(Object v, org.json.JSONArray out, int depth) {
        if (depth > 5 || v == null || out.length() >= 10) return;
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            boolean rallyLike = m.get("type") instanceof Number
                    || m.containsKey("master_marchid")
                    || m.containsKey("prepare_time")
                    || m.containsKey("monsterid");
            if (rallyLike) {
                org.json.JSONObject o = toJ(m);
                if (o.length() > 0) out.put(o);
                return;
            }
            for (Object x : m.values()) collectRallyLines(x, out, depth + 1);
        } else if (v instanceof List) {
            for (Object x : (List) v) collectRallyLines(x, out, depth + 1);
        }
    }

    private static org.json.JSONArray dayArrayOf(Object v) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (v instanceof List) {
            for (Object o : (List) v) {
                if (o instanceof Number) arr.put(((Number) o).longValue());
            }
        } else if (v instanceof Map) {
            for (Object k : ((Map<?, ?>) v).keySet()) {
                try {
                    arr.put((long) Double.parseDouble(String.valueOf(k).trim()));
                } catch (Throwable ignored) {
                }
            }
        }
        return arr;
    }

    static void debugLog(Context context, String msg) {
        try {
            java.io.File dir = context.getExternalFilesDir(null);
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "daily_log.txt"), true);
            w.write(System.currentTimeMillis() + " [dbg] " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 角色固定设备指纹：登录前注入（每角色一台固定"设备"，身份稳定/风控分散；主角色沿用
     * 账号级旧指纹）。注意这与"同账号多角色并行在线"无关——服务器按账号唯一会话
     * （终判实验见 RoleSessionKeeper 类注释）。{@code sid<=0}（默认角色登录）由
     * roleIdentitySeed 回落账号级指纹，与旧行为一致。
     */
    static void applyRoleIdentity(Context ctx, WjdrEngine engine, String fpid, long sid) {
        try {
            long main = RoleStore.activeRole(ctx, fpid);
            engine.applyIdentity(new com.carlos.vault.AccountStore(ctx).roleIdentitySeed(fpid, sid, main));
        } catch (Throwable ignored) {
        }
    }

    /** 借该角色的常驻在线会话（无则 null；sid<=0 时先试当前选中角色，再试任意在线角色）。 */
    public static RoleSessionKeeper.LiveSession liveOf(Context ctx, String fpid, long sid) {
        try {
            RoleSessionKeeper keeper = RoleSessionKeeper.get(ctx);
            if (sid <= 0) {
                long active = RoleStore.activeRole(ctx, fpid);
                RoleSessionKeeper.LiveSession ls = active > 0 ? keeper.borrowOnline(fpid, active) : null;
                return ls != null ? ls : keeper.borrowAnyOnline(fpid);
            }
            return keeper.borrowOnline(fpid, sid);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 登录成功后固化本次随机生成的角色指纹（已有固定指纹时为幂等回写）。 */
    static void persistRoleIdentity(Context ctx, WjdrEngine engine, String fpid, long sid) {
        try {
            String json = engine.identityJson();
            if (json.isEmpty()) return;
            com.carlos.vault.AccountStore store = new com.carlos.vault.AccountStore(ctx);
            if (sid > 0) store.updateRoleSettings(fpid, sid, json);
            else store.updateSettings(fpid, json);   // 默认角色登录：维持账号级旧口径
        } catch (Throwable ignored) {
        }
    }

    /**
     * 依据最新补给状态推算账号下次"到点可领"时刻并落盘（wake:<fpid>，0=无）。
     *
     * <p>候选：普通补给 online_ts+60s（子项开启时）；限时补给按策略提前量（截止前N分钟+60s，子项开启时）。
     * 角色取调度将补领的那个（schedule.serverid，缺省 activeRole），多角色不放大范围。
     * 调度器到点只跑 supply 单功能（runSupplyTopup），补领后由本方法顺延下一轮。
     */
    public static void scheduleNextSupplyWake(Context ctx, String fpid) {
        try {
            long now = System.currentTimeMillis();
            long sid = RoleStore.schedule(ctx, fpid).serverid;
            if (sid <= 0) sid = RoleStore.activeRole(ctx, fpid);
            java.util.List<Long> sids = new java.util.ArrayList<>();
            if (sid > 0) {
                sids.add(sid);
            } else {
                for (RoleStore.Role r : RoleStore.roles(ctx, fpid)) {
                    if (r.enabled) sids.add(r.serverid);
                }
            }
            long wake = 0;
            for (Long s : sids) {
                org.json.JSONObject mod = FnParams.modState(ctx, fpid, s, "supply");
                if (FnParams.subOn(ctx, fpid, s, "supply", "online", true)) {
                    long t = mod.optLong("online_ts", 0) * 1000L + 60_000L;
                    if (t > now && (wake == 0 || t < wake)) wake = t;
                }
                if (FnParams.subOn(ctx, fpid, s, "supply", "spec", true)) {
                    String strat = FnParams.strategy(ctx, fpid, s, "supply", "spec", "now");
                    long leadMin = "end30".equals(strat) ? 30 : "end60".equals(strat) ? 60
                            : "now".equals(strat) ? 0
                            : FnParams.strategyMinutes(ctx, fpid, s, "supply", "spec", 30);
                    long endMs = mod.optLong("spec_end", 0) * 1000L;
                    long t = endMs - leadMin * 60_000L + 60_000L;
                    if (endMs > now && t > now && (wake == 0 || t < wake)) wake = t;
                }
            }
            RoleStore.saveWakeAt(ctx, fpid, wake);
        } catch (Throwable ignored) {
        }
    }
    static String familyFn(long family) {
        switch ((int) family) {
            case 2965: return "act_balloon";
            case 2436: return "act_pitchpot";
            case 2450: return "act_snowclean";
            case 2415: return "act_icebound";
            case 2937: return "act_polar";
            case 2705: return "act_flamefangs";
            case 2438: return "act_brawl";
            case 2426: return "act_truck";
            case 2413:
            case 2403: return "act_icehunter";
            case 2418: return "act_fishing";
            case 2904: return "act_endless";
            case 2417: return "act_frostfire";
            case 2934: return "act_feast";
            case 2412: return "act_mercenary";
            case 2404: return "act_joy";
            default: return null;
        }
    }

    static String familyName(long family) {
        switch ((int) family) {
            case 2965: return "热气球派对手册";
            case 2436: return "投壶高手";
            case 2450: return "除雪装备计划";
            case 2415: return "冰封的许愿池";
            case 2937: return "极地营救";
            case 2705: return "烈焰与獠牙";
            case 2438: return "乱斗战场";
            case 2426: return "运宝劫掠";
            case 2413:
            case 2403: return "猎冰行动";
            case 2418: return "冰湖垂钓";
            case 2904: return "无尽寒冬";
            case 2417: return "燃霜矿区";
            case 2934: return "极寒盛宴";
            case 2412: return "佣兵悬赏";
            case 2404: return "欢乐庆典";
            default: return "活动 " + family;
        }
    }
    private static Integer intField(Map map, String ... stringArray) {
        for (String string2 : stringArray) {
            Object v2 = map.get(string2);
            boolean bl2 = v2 instanceof Number;
            if (!bl2) continue;
            return ((Number)v2).intValue();
        }
        return null;
    }


    private static List<Long> knownSids(Context context, String fpid, long sid) {
        ArrayList<Long> out = new ArrayList<>();
        for (RoleStore.Role r : RoleStore.roles(context, fpid)) {
            out.add(r.serverid);
        }
        if (out.isEmpty() && sid > 0) out.add(sid);
        return out;
    }
    private static Long numField(Map map, String ... stringArray) {
        for (String string2 : stringArray) {
            Object v2 = map.get(string2);
            boolean bl2 = v2 instanceof Number;
            if (!bl2) continue;
            return ((Number)v2).longValue();
        }
        return null;
    }


    private static long parseNumKey(String s) {
        return (long) Double.parseDouble(s.trim());
    }
    private static long pushNum(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private static java.util.Map<String, Object> unwrapPush(java.util.Map<String, Object> c) {
        for (String k : new String[]{"data", "info", "content"}) {
            Object v = c.get(k);
            if (v instanceof java.util.Map) return (java.util.Map<String, Object>) v;
        }
        return c;
    }

    private static java.util.List<Object> valuesOf(Object v) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (v instanceof java.util.Map) out.addAll(((java.util.Map<String, Object>) v).values());
        else if (v instanceof java.util.List) out.addAll((java.util.List<Object>) v);
        return out;
    }
    public static Summary run(Context context, String fpid, String sdkToken, long serverid,
                              Map<String, Boolean> cfg, ProgressListener listener) {
        Summary sum = new Summary();
        WjdrEngine engine = WjdrAndroid.newEngine(context);
        try {
            engine.initialize();
        } catch (Throwable t) {
            sum.loginError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (listener != null) listener.onFinish(sum);
            return sum;
        }
        WjdrEngine.LoginResult lr;
        applyRoleIdentity(context, engine, fpid, serverid);
        try {
            lr = RoleLogin.loginPinned(context, engine, fpid, sdkToken, serverid);
        } catch (Throwable t) {
            sum.loginError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (listener != null) listener.onFinish(sum);
            engine.close();
            return sum;
        }
        persistRoleIdentity(context, engine, fpid, serverid);
        sum.uid = lr.uid;
        sum.serverId = lr.serverId;
        sum.gameName = lr.gameName == null ? "" : lr.gameName;
        sum.level = lr.level;
        sum.power = lr.power;
        sum.alliance = lr.alliance == null ? "" : lr.alliance;
        RoleStore.registerRole(context, fpid, lr.serverId, lr.uid, sum.gameName,
                lr.level, lr.power, sum.alliance);
        engine.collectNotifies(2000);
        captureQuestState(context, engine, fpid, serverid, sum);
        try {
            captureCalendar(context, engine, fpid, serverid);
        } catch (Throwable t) {
            sum.loginError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            if (listener != null) listener.onFinish(sum);
            engine.close();
            return sum;
        }
        return runTasks(context, engine, fpid, serverid, cfg, listener, sum, true);
    }

    /** 任务循环 + 收尾捕获（fresh 登录与常驻在线会话复用共用；closeEngine=false 时不关会话供续用）。 */
    static Summary runTasks(Context context, WjdrEngine engine, String fpid, long serverid,
                            Map<String, Boolean> cfg, ProgressListener listener, Summary sum,
                            boolean closeEngine) {
        HotConfig.init(context);
        for (DailyTasks.TaskDef d : DailyTasks.all()) {
            Boolean on = cfg == null ? null : cfg.get(d.id);
            boolean enabled = on != null ? on : d.enabledByDefault;
            if (HotConfig.isDisabled(d.id)) {
                // 热更止血：远端一键禁用某任务（优先级高于本地开关）
                DailyResult r = new DailyResult(d.id, d.name, DailyResult.Category.SKIPPED, "热更禁用");
                sum.results.add(r);
                if (listener != null) listener.onTaskDone(r);
                continue;
            }
            if (!enabled) {
                DailyResult r = new DailyResult(d.id, d.name, DailyResult.Category.SKIPPED, "配置未启用");
                sum.results.add(r);
                if (listener != null) listener.onTaskDone(r);
                continue;
            }
            if (listener != null) listener.onTaskStart(d.name);
            // 行军域锁（M1）：与推送唤醒/互助/手动执行互斥；30s 拿不到=让出本轮（SKIPPED，下轮再试）
            final AutoCloseable marchGuard;
            if (MarchCoordinator.isGuarded(d.id)) {
                marchGuard = MarchCoordinator.forKey(fpid, serverid).tryAcquire(d.id, 30_000L);
                if (marchGuard == null) {
                    DailyResult r = new DailyResult(d.id, d.name, DailyResult.Category.SKIPPED,
                            "行军资源占用中，本轮让出");
                    sum.results.add(r);
                    if (listener != null) listener.onTaskDone(r);
                    continue;
                }
            } else {
                marchGuard = null;
            }
            long t0 = System.currentTimeMillis();
            DailyResult r;
            try {
                r = d.executor.run(engine);
            } catch (Throwable t) {
                DailyResult x = DailyTasks.classifyException(t, d.name);
                r = new DailyResult(d.id, d.name, x.category, x.message);
            } finally {
                if (marchGuard != null) {
                    try {
                        marchGuard.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            r.durationMs = System.currentTimeMillis() - t0;
            r.attempt = 1;
            sum.results.add(r);
            if (listener != null) listener.onTaskDone(r);
            if (r.category == DailyResult.Category.SESSION_CLOSED) {
                sum.sessionLost = true;
                break;
            }
        }
        engine.collectNotifies(1200);
        captureQuestState(context, engine, fpid, serverid, sum);
        // 热更声明式领取任务（Tier 2）收尾执行：单条隔离，失败不影响整轮
        if (!sum.sessionLost) {
            HotConfig.runClaims(engine, listener);
        }
        if (closeEngine) engine.close();
        if (listener != null) listener.onFinish(sum);
        return sum;
    }

    public static DailyResult runSingle(Context context, String fpid, String sdkToken,
                                        long serverid, String fnId) {
        for (DailyTasks.TaskDef d : DailyTasks.all()) {
            if (!d.id.equals(fnId)) continue;
            // 在线会话复用：该角色已有常驻会话 → 直接执行（切角色寻址/免登录秒回/防自顶）
            RoleSessionKeeper.LiveSession live = liveOf(context, fpid, serverid);
            if (live != null) {
                long lt0 = System.currentTimeMillis();
                DailyResult r;
                try {
                    r = d.executor.run(live.engine);
                } catch (Throwable t) {
                    return DailyTasks.classifyException(t, d.name);
                }
                r.durationMs = System.currentTimeMillis() - lt0;
                r.attempt = 1;
                try {
                    captureQuestState(context, live.engine, fpid, live.sid, null);
                } catch (Throwable ignored) {
                }
                return r;
            }
            long singleT0 = System.currentTimeMillis();
            // 同账号单会话约束：fresh 登录前关掉该账号已有的常驻会话——服务器按 fpid 唯一会话，
            // 不关也会被服务端踢掉（code 46/5，终判实验见 RoleSessionKeeper 类注释），先关更干净。
            // 临时会话结束后，keeper 下次 syncOnline 会把常驻角色拉回。
            try {
                RoleSessionKeeper.get(context).closeAccountSessions(fpid);
            } catch (Throwable ignored) {
            }
            WjdrEngine engine = WjdrAndroid.newEngine(context);
            try {
                try {
                    engine.initialize();
                } catch (Throwable t) {
                    DailyResult x = DailyTasks.classifyException(t, d.name);
                    return new DailyResult(d.id, d.name, x.category, x.message);
                }
                try {
                    HotConfig.init(context);
                    applyRoleIdentity(context, engine, fpid, serverid);
                    RoleLogin.loginPinned(context, engine, fpid, sdkToken, serverid);
                    persistRoleIdentity(context, engine, fpid, serverid);
                } catch (Throwable t) {
                    return DailyTasks.classifyException(t, d.name);
                }
                DailyResult r;
                try {
                    r = d.executor.run(engine);
                } catch (Throwable t) {
                    return DailyTasks.classifyException(t, d.name);
                }
                r.durationMs = System.currentTimeMillis() - singleT0;
                r.attempt = 1;
                engine.collectNotifies(1200);
                captureQuestState(context, engine, fpid, serverid, null);
                captureCalendar(context, engine, fpid, serverid);
                return r;
            } finally {
                engine.close();
                // fresh 登录已把该账号的多角色竞速会话踢掉（服务器登录即踢旧），
                // 收尾立即让 keeper 整号重赛建回
                try {
                    RoleSessionKeeper.get(context).syncOnline();
                } catch (Throwable ignored) {
                }
            }
        }
        return new DailyResult(fnId, fnId, DailyResult.Category.ERROR, "未知功能");
    }

    static void saveRallies(Context context, WjdrEngine engine, String fpid, long sid) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            java.util.Map<String, Object> init = engine.notifyData("notify_world_alliance_situation_init");
            if (init != null) collectRallyLines(init, arr, 0);
            java.util.Map<String, Object> act = engine.notifyContent("notify_world_alliance_situation_activity");
            if (act != null) collectRallyLines(act, arr, 0);
            debugLog(context, "rallies raw=" + String.valueOf(init));
            context.getSharedPreferences("daily_state", Context.MODE_PRIVATE).edit()
                    .putString("rallies:" + fpid + ":" + sid, arr.toString()).apply();
        } catch (Throwable t) {
            debugLog(context, "rallies fail: " + t);
        }
    }

    static void saveResources(Context context, WjdrEngine engine, String fpid, long sid) {
        try {
            java.util.Map<String, Object> ri = engine.notifyData("notify_recover_resource_init");
            if (ri == null) return;
            org.json.JSONObject out = new org.json.JSONObject();
            collectCounts(ri, out, 0);
            context.getSharedPreferences("daily_state", Context.MODE_PRIVATE).edit()
                    .putString("res:" + fpid + ":" + sid, out.toString()).apply();
        } catch (Throwable t) {
            debugLog(context, "res fail: " + t);
        }
    }

    /** 深扫资源计数：键为资源id（Double/String），值为数量或 {count}。 */
    private static void collectCounts(Object v, org.json.JSONObject out, int depth) {
        if (depth > 4 || v == null) return;
        if (v instanceof Map) {
            for (Object e : ((Map) v).entrySet()) {
                Map.Entry en = (Map.Entry) e;
                Object val = en.getValue();
                if (val instanceof Number) {
                    try {
                        out.put(String.valueOf((long) Double.parseDouble(String.valueOf(en.getKey()).trim())),
                                ((Number) val).longValue());
                    } catch (Throwable ignored) {
                    }
                } else if (val instanceof Map && ((Map) val).get("count") instanceof Number) {
                    try {
                        out.put(String.valueOf((long) Double.parseDouble(String.valueOf(en.getKey()).trim())),
                                ((Number) ((Map) val).get("count")).longValue());
                    } catch (Throwable ignored) {
                    }
                } else if (val instanceof Map || val instanceof List) {
                    collectCounts(val, out, depth + 1);
                }
            }
        } else if (v instanceof List) {
            for (Object x : (List) v) collectCounts(x, out, depth + 1);
        }
    }
    private static String str(Object object) {
        String string2 = object == null ? "" : String.valueOf(object);
        return string2;
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */

        /** 单账号全角色执行（工作区角色逐个跑日常：各自cfg/日志/子项/lastr；多角色汇总文本）。
     *  顶号保护：游戏在线跳过。返回 null=没跑（无角色/游戏在线/无token），否则多角色汇总或单角色文本。 */
    public static String runAccountRoles(Context context, com.carlos.vault.AccountStore.Account acc) {
        if (acc == null || acc.token == null || acc.token.isEmpty()
                || acc.fpid == null || acc.fpid.isEmpty()) return null;
        // 顶号保护不在函数开头一刀切：有常驻会话的角色照常在会话上执行（不会顶掉
        // 正在玩的游戏客户端）；只有需要 fresh 登录的角色才在循环内逐个跳过。
        java.util.List<RoleStore.Role> roleList = new java.util.ArrayList<>();
        try {
            for (RoleStore.Role r : RoleStore.roles(context, acc.fpid)) {
                if (r.enabled) roleList.add(r);
            }
        } catch (Throwable ignored) {
        }
        if (roleList.isEmpty()) {
            long fb = RoleStore.schedule(context, acc.fpid).serverid;
            if (fb > 0) {
                RoleStore.Role r = new RoleStore.Role();
                r.serverid = fb;
                r.name = "";
                r.enabled = true;
                roleList.add(r);
            }
        }
        if (roleList.isEmpty()) return null;
        DailyConfigStore cfgStore = new DailyConfigStore(context);
        LogStore ls = new LogStore(context);
        StringBuilder multi = new StringBuilder();
        DailyRunner.Summary last = null;
        int ranRoles = 0;   // 真实跑过任务的角色数（0=本轮全被顶号/登录失败，不烧当天）
        for (RoleStore.Role r : roleList) {
            final long sidF = r.serverid;
            Map<String, Boolean> cfg = cfgStore.roleConfig(acc.fpid, sidF);
            if (cfg == null) cfg = cfgStore.metaConfig(DailyTasks.all());
            DailyRunner.ProgressListener pl = new DailyRunner.ProgressListener() {
                @Override
                public void onTaskStart(String name) {
                }

                @Override
                public void onTaskDone(DailyResult res) {
                    try {
                        boolean ok = res.category == DailyResult.Category.CLAIMED
                                || res.category == DailyResult.Category.SKIPPED;
                        ls.append(acc.fpid, sidF, res.taskId, ok, res.toString(),
                                res.rewards == null ? "" : res.rewards,
                                DailyRunner.SOURCE, res.durationMs, res.category.name(), res.attempt);
                        ls.setStatus(acc.fpid, sidF, res.taskId,
                                ok ? LogStore.ST_DONE : LogStore.ST_FAILED);
                        ls.applySubResults(acc.fpid, sidF, res.taskId, res.subResults);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onFinish(DailyRunner.Summary s3) {
                }
            };
            // 该角色已有常驻在线会话 → 直接在会话上执行（防自顶：fresh 登录会互踢；同时免登录）
            RoleSessionKeeper.LiveSession live = liveOf(context, acc.fpid, sidF);
            DailyRunner.Summary sum;
            if (live != null) {
                Summary pre = new Summary();
                pre.uid = live.engine.lastUid();
                pre.serverId = live.engine.lastServerId();
                sum = runTasks(context, live.engine, acc.fpid, sidF, cfg, pl, pre, false);
            } else {
                // fresh 登录前的顶号保护（此时才检查：游戏客户端正挂该账号则跳过本角色）
                boolean gameLive = false;
                try {
                    String liveFpid = new com.carlos.vault.VaultEngine(context).readLiveSessionFpid();
                    gameLive = liveFpid != null && !liveFpid.isEmpty() && liveFpid.equals(acc.fpid);
                } catch (Throwable ignored) {
                }
                if (gameLive) {
                    sum = new Summary();
                    sum.loginError = "游戏在线 · 跳过登录（防顶号）";
                } else {
                    sum = run(context, acc.fpid, acc.token, sidF, cfg, pl);
                }
            }
            // 轮次真实跑过才盖该角色的 lastr；顶号跳过/登录失败不盖（否则 UI 显示"今日已跑"
            // 且当日补跑被烧掉，用户玩一天游戏当天日常全废——2026-10-11 真机实证）
            if (sum.loginError == null) {
                try {
                    context.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                            .putLong("lastr:" + acc.fpid + ":" + sidF,
                                    System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                }
                ranRoles++;
            }
            last = sum;
            multi.append(r.name == null || r.name.isEmpty() ? "K" + r.serverid : r.name)
                    .append("：").append(sum.loginError != null
                            ? "登录失败 " + sum.loginError : sum.toText().split("\n")[0])
                    .append('\n');
        }
        if (ranRoles > 0) {
            try {
                context.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                        .putLong("last:" + acc.fpid, System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {
            }
        }
        if (last != null) {
            RoleStore.registerRole(context, acc.fpid, last.serverId, last.uid,
                    last.gameName == null ? "" : last.gameName,
                    last.level, last.power, last.alliance);
            context.getSharedPreferences("daily_state", Context.MODE_PRIVATE).edit()
                    .putString("summary:" + acc.fpid, last.toText()).apply();
        }
        try {
            scheduleNextSupplyWake(context, acc.fpid);   // 依据最新补给状态顺延任务级 wake
        } catch (Throwable ignored) {
        }
        // 整轮里的 fresh 登录会打断多角色竞速会话（服务器登录即踢旧），收尾立即整号重赛建回
        try {
            RoleSessionKeeper.get(context).syncOnline();
        } catch (Throwable ignored) {
        }
        // 全部角色被顶号跳过/登录失败：按"没跑"上报（null），让调度/补跑路径保持当日待跑
        if (ranRoles == 0) return null;
        return roleList.size() > 1 ? multi.toString().trim()
                : (last == null ? null : last.toText());
    }

    public static String syncOnly(Context context, String fpid, String sdkToken, long serverid) {
        // 常驻会话复用优先：该账号已有在线会话 → 直接在会话上同步（防自顶 + 免登录）。
        // 注意顺序：防顶号检查只应拦"fresh 登录"（登录会顶掉正在玩的游戏客户端），
        // 借常驻会话同步不会顶号——真机事故：游戏客户端长挂时旧逻辑在函数开头就跳过，
        // 连常驻会话同步也不做，导致今日页/详情页全部快照停留数天前（互助却因走
        // 常驻会话链路而是新鲜的）。
        RoleSessionKeeper.LiveSession liveSes = liveOf(context, fpid, serverid);
        if (liveSes != null) {
            return captureAll(context, liveSes.engine, fpid, liveSes.sid);
        }
        // 无常驻会话需 fresh 登录：此时才做顶号保护
        try {
            String live = new com.carlos.vault.VaultEngine(context).readLiveSessionFpid();
            if (live != null && !live.isEmpty() && live.equals(fpid)) {
                return "游戏在线 · 跳过登录同步（防顶号）";
            }
        } catch (Throwable ignored) {
        }
        WjdrEngine engine = WjdrAndroid.newEngine(context);
        try {
            HotConfig.init(context);
            engine.initialize();
            applyRoleIdentity(context, engine, fpid, serverid);
            WjdrEngine.LoginResult lr = RoleLogin.loginPinned(context, engine, fpid, sdkToken, serverid);
            persistRoleIdentity(context, engine, fpid, serverid);
            long sid = lr.serverId > 0 ? lr.serverId : serverid;
            RoleStore.registerRole(context, fpid, lr.serverId, lr.uid, lr.gameName,
                    lr.level, lr.power, lr.alliance == null ? "" : lr.alliance);
            return captureAll(context, engine, fpid, sid);
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            engine.close();
            // fresh 登录会踢掉该账号的多角色竞速会话，收尾让 keeper 整号重赛建回
            try {
                RoleSessionKeeper.get(context).syncOnline();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 打开功能详情页/展开卡片时的单功能状态刷新：借常驻会话收取最新 notify/只读协议，
     * 只写状态快照（modState），不执行任何任务动作（不领取/不训练/不出征）。
     * 会话不在线时返回 false（调用方保持显示现有快照，⏳ 标记已由显示层负责）。
     */
    public static boolean refreshFnState(Context context, WjdrEngine engine,
                                         String fpid, long sid, String fnId) {
        try {
            android.util.Log.i("FnRefresh", "start fn=" + fnId + " fpid=" + fpid + " sid=" + sid
                    + " state=" + engine.sessionState()
                    + " protos=" + (engine.notifyProtos().length() > 300
                            ? engine.notifyProtos().substring(0, 300) : engine.notifyProtos()));
            engine.collectNotifies(1200);
            switch (fnId == null ? "" : fnId) {
                case "barracks": {
                    TownTasks.captureBarrackState(engine);
                    // 同步可训练档位（与执行器同口径：建筑等级+科技；回退 owned-soldiers）
                    int[] tiers = TownTasks.barrackUnlockTiers(engine);
                    String src = "building";
                    if (tiers == null) {
                        src = "soldiers";
                        tiers = new int[4];
                        for (Long id : ExecUtil.soldiers(engine).keySet()) {
                            int line = (int) (id / 10000);
                            int idx = (int) ((id % 10000) / 100);
                            if (line >= 1 && line <= 3 && idx > tiers[line]) tiers[line] = idx;
                        }
                    }
                    org.json.JSONObject ms = FnParams.modState(context, fpid, sid, "barracks");
                    ms.put("max_shield", tiers[1]);
                    ms.put("max_spear", tiers[2]);
                    ms.put("max_bow", tiers[3]);
                    ms.put("tier_src", src);
                    FnParams.saveModState(context, fpid, sid, "barracks", ms);
                    break;
                }
                case "town_affairs":
                    TownTasks.captureHeroSearch(engine);
                    break;
                case "supply":
                    DailyTasks.captureSupplyState(engine, context, fpid, sid);
                    break;
                case "daily_mission":
                    captureQuestState(context, engine, fpid, sid, null);
                    break;
                case "bear":
                    FieldTasks.bear(engine);   // 仅 req_alliance_trap_info 只读请求 + 写状态
                    break;
                case "medic": {
                    // 只读解析 notify_hospital_init 伤兵总量（与执行器同形状兼容）
                    Map<String, Object> hi = engine.notifyData("notify_hospital_init");
                    if (hi != null) {
                        long wounds = 0;
                        Object ws = hi.get("wound_soldiers");
                        java.util.List<Object> l = ExecUtil.listOf(ws);
                        if (l != null) {
                            for (Object o : l) {
                                Map<String, Object> kv = ExecUtil.mapOf(o);
                                if (kv != null) wounds += ExecUtil.longOf(kv.get("v"));
                            }
                        } else {
                            Map<String, Object> m = ExecUtil.mapOf(ws);
                            if (m != null) {
                                Object inner = m.get("soldiers");
                                for (Object o : ExecUtil.listOf(inner)) {
                                    Map<String, Object> kv = ExecUtil.mapOf(o);
                                    if (kv != null) wounds += ExecUtil.longOf(kv.get("v"));
                                }
                            }
                        }
                        FnParams.saveModText(context, fpid, sid, "medic", "heal",
                                wounds <= 0 ? "当前无伤兵" : "伤兵 " + wounds + " 待治疗");
                    }
                    break;
                }
                case "expert": {
                    Map<String, Object> ti = engine.notifyData("notify_travel_info");
                    if (ti != null) {
                        long energy = ExecUtil.longOf(ti.get("energy"));
                        if (energy <= 0) energy = ExecUtil.longOf(ti.get("stamina"));
                        if (energy <= 0) energy = ExecUtil.longOf(ti.get("power"));
                        if (energy > 0) {
                            FnParams.saveModText(context, fpid, sid, "expert", "energy",
                                    "当前行动力 " + energy);
                        }
                    }
                    break;
                }
                default:
                    // 其余功能状态依赖执行动作写入，这里只刷新引擎 notify 缓存（无害）
                    break;
            }
            android.util.Log.i("FnRefresh", "done fn=" + fnId);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("FnRefresh", "failed fn=" + fnId + ": " + t, t);
            return false;
        }
    }

    /** 全量状态捕获（fresh 登录与在线会话共用）：等关键 notify → 任务进度/日历/资源/集结/巨熊/补给/燃霜/推送态。 */
    private static String captureAll(Context context, WjdrEngine engine, String fpid, long sid) {
        try {
            for (int i = 0; i < 10; i++) {
                if (engine.notifyContent("notify_dailyquests") != null
                        && engine.notifyContent("notify_recover_resource_init") != null) break;
                engine.collectNotifies(500);
            }
            captureQuestState(context, engine, fpid, sid, null);
            captureCalendar(context, engine, fpid, sid);
            saveResources(context, engine, fpid, sid);
            saveRallies(context, engine, fpid, sid);
            try {
                FieldTasks.bear(engine);
            } catch (Throwable ignored) {
            }
            try {
                DailyTasks.captureSupplyState(engine, context, fpid, sid);
            } catch (Throwable ignored) {
            }
            try {
                captureFrostfire(engine, fpid, sid);
            } catch (Throwable ignored) {
            }
            try {
                capturePushStates(engine, fpid, sid);
            } catch (Throwable ignored) {
            }
            try {
                scheduleNextSupplyWake(context, fpid);   // 依据最新补给状态顺延任务级 wake
            } catch (Throwable ignored) {
            }
            // 采集不盖 lastr：lastr 语义是"该角色上一次任务轮次"，采集盖章会让 UI 误显示"今日已跑"
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }
    private static org.json.JSONObject toJ(Map<String, Object> map) {
        return (org.json.JSONObject) toJavaJson(map);
    }
    static Object toJavaJson(Object v) {
        if (v instanceof Map) {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Object e : ((Map) v).entrySet()) {
                Map.Entry en = (Map.Entry) e;
                try {
                    o.put(String.valueOf(en.getKey()), toJavaJson(en.getValue()));
                } catch (Throwable ignored) {
                }
            }
            return o;
        }
        if (v instanceof List) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Object x : (List) v) a.put(toJavaJson(x));
            return a;
        }
        if (v instanceof Number) return ((Number) v).longValue();
        return v == null ? org.json.JSONObject.NULL : String.valueOf(v);
    }
    private static void writeModState(android.content.Context ctx, String fpid, long sid,
                                      String fn, String key, String text) {
        try {
            org.json.JSONObject o = FnParams.modState(ctx, fpid, sid, fn);
            o.put(key, text);
            // 与 FnParams.saveModText 一致：统一写毫秒（此处曾写秒，导致 UI 按 ms 格式化显示 1970 年）
            o.put(key + "_ts", System.currentTimeMillis());
            FnParams.saveModState(ctx, fpid, sid, fn, o);
        } catch (Throwable ignored) {
        }
    }
    public interface ProgressListener {
        void onTaskStart(String name);

        void onTaskDone(DailyResult r);

        void onFinish(Summary s);
    }

    public static class Summary {
        public final List<DailyResult> results = new ArrayList();
        public String loginError;
        public String gameName = "";
        public long serverId;
        public long uid;
        public int activeness = -1;
        public int boxesReached = -1;
        public boolean sessionLost;
        /** 角色元数据透传（登记 RoleStore 用；来自本次登录的 LoginResult）。 */
        public long level;
        public long power;
        public String alliance = "";

        public int count(DailyResult.Category category) {
            int n = 0;
            for (DailyResult r : results) {
                if (r.category == category) n++;
            }
            return n;
        }

        public String toText() {
            if (loginError != null) {
                return "❌ 登录失败：" + loginError;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("✅成功 ").append(count(DailyResult.Category.CLAIMED))
                    .append(" · ⏭跳过 ").append(count(DailyResult.Category.SKIPPED))
                    .append(" · ⚠️降级 ").append(count(DailyResult.Category.DEGRADED))
                    .append(" · ❌异常 ").append(count(DailyResult.Category.ERROR)).append('\n');
            for (DailyResult r : results) {
                sb.append('\n').append(r.toString());
            }
            return sb.toString();
        }
    }
}

