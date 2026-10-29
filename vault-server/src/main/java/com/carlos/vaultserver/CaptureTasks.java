package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.task.RoleKey;
import com.carlos.vaultserver.task.ServerNotifyHistory;
import com.carlos.vaultserver.task.StateKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全量状态采集（移植自 App 端 DailyRunner.captureAll / FieldTasks.bear / DailyTasks.captureSupplyState）：
 * 等关键 notify → 任务进度/日历/资源/集结/巨熊/补给/燃霜，写入 StateStore（键型与 App daily_state 一致）。
 * 解析逻辑与 App 端逐行对齐，改动须两侧同步。
 *
 * <p>R2 Phase 2（2026-10-29）：同账号同服多角色各自采集，状态键按角色 uid 隔离
 * （{@link StateKeys} 角色键）；App 仍读的旧键（{@code res:<fpid>:<sid>} 等）只由
 * <b>active 角色镜像写</b>（{@code activeWriter}）。旧 4 参签名等价 uid=0/active=true，
 * 行为与 Phase 2 之前 100% 一致。
 */
public final class CaptureTasks {

    public static final int[] BOX_THRESHOLDS = {40, 80, 120, 160, 215, 270, 325};

    /** 采集周期：登录后首采 + 每 10 分钟刷新（App 端为按页面触发，服务器端为常驻周期）。 */
    public static final long CAPTURE_INTERVAL_MILLIS = 10 * 60_000L;

    private CaptureTasks() {
    }

    /** 全量采集（须在会话线程调用，引擎在线）。返回错误描述或 null。旧签名（uid=0 兼容路径）。 */
    public static String captureAll(WjdrEngine engine, StateStore store, String fpid, long sid) {
        return captureAll(engine, store, fpid, sid, 0L, true);
    }

    /**
     * 全量采集（R2 Phase 2 按角色）。
     *
     * @param uid          角色 uid（&gt;0 时状态键按角色隔离；0=旧/兼容路径）
     * @param activeWriter 是否 App 当前选中角色（仅它镜像写旧键，App 显示链路不变）
     * @return 错误描述或 null
     */
    public static String captureAll(WjdrEngine engine, StateStore store, String fpid, long sid,
                                    long uid, boolean activeWriter) {
        RoleKey role = new RoleKey(fpid, sid, uid);
        try {
            for (int i = 0; i < 10; i++) {
                if (engine.notifyContent("notify_dailyquests") != null
                        && engine.notifyContent("notify_recover_resource_init") != null) break;
                engine.collectNotifies(500);
            }
            captureQuestState(engine, store, role, activeWriter);
            captureCalendar(engine, store, role, activeWriter);
            saveResources(engine, store, role, activeWriter);
            saveRallies(engine, store, role, activeWriter);
            try {
                bear(engine, store, role, activeWriter);
            } catch (Throwable ignored) {
            }
            try {
                captureSupplyState(engine, store, role, activeWriter);
            } catch (Throwable ignored) {
            }
            try {
                captureFrostfire(engine, store, role, activeWriter);
            } catch (Throwable ignored) {
            }
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    // ---------------- 键型辅助（R2 Phase 2） ----------------

    /** 写角色键；active 角色额外镜像旧键（App 显示链路）。uid&lt;=0 时两者同键，只写一次。 */
    private static void putRoleString(StateStore store, RoleKey role, String family, String rest,
                                      String value, boolean activeWriter) {
        store.putString(StateKeys.role(family, role, rest), value);
        if (activeWriter && role.roleScoped()) {
            store.putString(StateKeys.legacy(family, role, rest), value);
        }
    }

    private static void putRoleLong(StateStore store, RoleKey role, String family, String rest,
                                    long value, boolean activeWriter) {
        store.putLong(StateKeys.role(family, role, rest), value);
        if (activeWriter && role.roleScoped()) {
            store.putLong(StateKeys.legacy(family, role, rest), value);
        }
    }

    /** 读角色 mod 状态；active 角色 miss 时回退旧键（迁移兼容）。 */
    private static JSONObject readRoleMod(StateStore store, RoleKey role, String sub,
                                          boolean activeWriter) {
        String raw = store.getString(StateKeys.role("mod", role, sub));
        if (raw == null && activeWriter && role.roleScoped()) {
            raw = store.getString(StateKeys.legacy("mod", role, sub));
        }
        try {
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    // ---------------- 每日任务（notify_dailyquests → questprog/questdone/act/boxes/questts） ----------------

    static void captureQuestState(WjdrEngine engine, StateStore store, String fpid, long sid) {
        captureQuestState(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    static void captureQuestState(WjdrEngine engine, StateStore store, RoleKey role,
                                  boolean activeWriter) {
        try {
            Map<String, Object> q = engine.notifyData("notify_dailyquests");
            if (q == null) return;
            Object questsObj = q.get("quests");
            Map<Long, Integer> prog = new LinkedHashMap<>();
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
            Set<Long> claimed = new LinkedHashSet<>();
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
            JSONObject p = new JSONObject();
            for (Map.Entry<Long, Integer> e : prog.entrySet()) {
                p.put(String.valueOf(e.getKey()), e.getValue().intValue());
            }
            JSONArray done = new JSONArray();
            for (Long qid : claimed) done.put(qid.longValue());
            putRoleString(store, role, "questprog", null, p.toString(), activeWriter);
            putRoleString(store, role, "questdone", null, done.toString(), activeWriter);
            putRoleLong(store, role, "questts", null, System.currentTimeMillis(), activeWriter);
            putRoleLong(store, role, "act", null, integral, activeWriter);
            putRoleLong(store, role, "boxes", null, boxes, activeWriter);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 活动日历（req_activity_calendar_get_data → todo: + 各活动 mod.open） ----------------

    static void captureCalendar(WjdrEngine engine, StateStore store, String fpid, long sid) {
        captureCalendar(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    static void captureCalendar(WjdrEngine engine, StateStore store, RoleKey role,
                                boolean activeWriter) {
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
            // 批次4（2026-10-23）：周期采集的日历响应喂给通知历史（复用批次3 allowlist/每 proto 8 条/
            // 总量 2000 条/4MB 预算/不可变深拷贝），让 C5 活动日历门控在长在线无 notify 推送时仍有
            // 新鲜数据；不新增网络请求（复用上面的 req_activity_calendar_get_data）。失败不影响主流程。
            try {
                ServerNotifyHistory.syncActivityCalendar(role.fpid, content);
            } catch (Throwable ignored) {
            }
            Object dataObj = content.get("data");
            if (dataObj == null) dataObj = content;
            if (!(dataObj instanceof Map)) return;
            JSONArray todos = new JSONArray();
            long now = System.currentTimeMillis() / 1000L;
            SimpleDateFormat md = Beijing.fmt("MM-dd");
            SimpleDateFormat hm = Beijing.fmt("HH:mm");
            Calendar cal = Calendar.getInstance(Beijing.TZ);
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
                            JSONObject mod = readRoleMod(store, role, fn, activeWriter);
                            mod.put("open", text);
                            mod.put("open_ts", System.currentTimeMillis());
                            putRoleString(store, role, "mod", fn, mod.toString(), activeWriter);
                        } catch (Throwable ignored3) {
                        }
                    }
                }
            }
            putRoleString(store, role, "todo", null, todos.toString(), activeWriter);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 资源（notify_recover_resource_init → res:） ----------------

    static void saveResources(WjdrEngine engine, StateStore store, String fpid, long sid) {
        saveResources(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    static void saveResources(WjdrEngine engine, StateStore store, RoleKey role, boolean activeWriter) {
        try {
            Map<String, Object> ri = engine.notifyData("notify_recover_resource_init");
            if (ri == null) return;
            JSONObject out = new JSONObject();
            collectCounts(ri, out, 0);
            putRoleString(store, role, "res", null, out.toString(), activeWriter);
        } catch (Throwable ignored) {
        }
    }

    /** 深扫资源计数：键为资源id（Double/String），值为数量或 {count}。 */
    private static void collectCounts(Object v, JSONObject out, int depth) {
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

    // ---------------- 集结（notify_world_alliance_situation_* → rallies:） ----------------

    static void saveRallies(WjdrEngine engine, StateStore store, String fpid, long sid) {
        saveRallies(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    static void saveRallies(WjdrEngine engine, StateStore store, RoleKey role, boolean activeWriter) {
        try {
            JSONArray arr = new JSONArray();
            Map<String, Object> init = engine.notifyData("notify_world_alliance_situation_init");
            if (init != null) collectRallyLines(init, arr, 0);
            Map<String, Object> act = engine.notifyContent("notify_world_alliance_situation_activity");
            if (act != null) collectRallyLines(act, arr, 0);
            putRoleString(store, role, "rallies", null, arr.toString(), activeWriter);
        } catch (Throwable ignored) {
        }
    }

    private static void collectRallyLines(Object v, JSONArray out, int depth) {
        if (depth > 5 || v == null || out.length() >= 10) return;
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            boolean rallyLike = m.get("type") instanceof Number
                    || m.containsKey("master_marchid")
                    || m.containsKey("prepare_time")
                    || m.containsKey("monsterid");
            if (rallyLike) {
                JSONObject o = (JSONObject) toJavaJson(m);
                if (o.length() > 0) out.put(o);
                return;
            }
            for (Object x : m.values()) collectRallyLines(x, out, depth + 1);
        } else if (v instanceof List) {
            for (Object x : (List) v) collectRallyLines(x, out, depth + 1);
        }
    }

    // ---------------- 巨熊（req_alliance_trap_info → mod:bear） ----------------

    public static void bear(WjdrEngine engine, StateStore store, String fpid, long sid) {
        bear(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    /**
     * 巨熊采集（R2 Phase 2B 按角色）：状态键 {@code mod:<fpid>:<sid>:<uid>:bear}；
     * activeWriter 时额外镜像旧键 {@code mod:<fpid>:<sid>:bear}（App 显示链路）。
     *
     * @param uid          角色 uid（&gt;0 时按角色隔离；0=旧/兼容路径）
     * @param activeWriter 是否 App 当前选中角色（仅它镜像写旧键）
     */
    public static void bear(WjdrEngine engine, StateStore store, String fpid, long sid,
                            long uid, boolean activeWriter) {
        bear(engine, store, new RoleKey(fpid, sid, uid), activeWriter);
    }

    static void bear(WjdrEngine engine, StateStore store, RoleKey role, boolean activeWriter) {
        try {
            GameMessage trap = engine.request("req_alliance_trap_info", null);
            if (trap == null || trap.getContent() == null) return;
            Object code = trap.getContent().get("code");
            if (code instanceof Number && ((Number) code).intValue() != 0) return;
            long reserve = deepLong(trap.getContent(), "reserve_time", "reserveTime");
            long massStart = deepLong(trap.getContent(), "mass_start_time", "massStartTime");
            long massEnd = deepLong(trap.getContent(), "mass_end_time", "massEndTime");
            long coolEnd = deepLong(trap.getContent(), "cool_end_time", "coolEndTime");
            SimpleDateFormat dh = Beijing.fmt("M月d日 HH:mm");
            boolean rv = reserve > 1_700_000_000L && reserve < 2_000_000_000L;
            String bearText = rv
                    ? "已预约 " + dh.format(new Date(reserve * 1000))
                    : (reserve > 0 ? "已预约" : "未预约 · 建议提前预约");
            JSONObject mod = new JSONObject();
            mod.put("trap", bearText);
            if (rv) mod.put("reserve", reserve);
            if (massStart > 1_700_000_000L && massStart < 2_000_000_000L) mod.put("start", massStart);
            if (massEnd > 1_700_000_000L && massEnd < 2_000_000_000L) mod.put("end", massEnd);
            if (coolEnd > 1_700_000_000L && coolEnd < 2_000_000_000L) mod.put("cool", coolEnd);
            putRoleString(store, role, "mod", "bear", mod.toString(), activeWriter);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 仓库补给（notify_recover_resource_init 时间字段 → mod:supply） ----------------

    public static void captureSupplyState(WjdrEngine engine, StateStore store, String fpid, long sid) {
        captureSupplyState(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    /**
     * 仓库补给采集（R2 Phase 2B 按角色）：状态键 {@code mod:<fpid>:<sid>:<uid>:supply}；
     * activeWriter 时额外镜像旧键 {@code mod:<fpid>:<sid>:supply}（App 显示链路）。
     *
     * @param uid          角色 uid（&gt;0 时按角色隔离；0=旧/兼容路径）
     * @param activeWriter 是否 App 当前选中角色（仅它镜像写旧键）
     */
    public static void captureSupplyState(WjdrEngine engine, StateStore store, String fpid, long sid,
                                          long uid, boolean activeWriter) {
        captureSupplyState(engine, store, new RoleKey(fpid, sid, uid), activeWriter);
    }

    static void captureSupplyState(WjdrEngine engine, StateStore store, RoleKey role,
                                   boolean activeWriter) {
        try {
            Map<String, Object> ri = engine.notifyData("notify_recover_resource_init");
            if (ri == null) return;
            JSONObject out = new JSONObject();
            List<long[]> times = new java.util.ArrayList<>();
            collectTimes(ri, times, 0);
            long now = System.currentTimeMillis() / 1000L;
            long onlineNext = 0, specEnd = 0;
            for (long[] t : times) {
                if (t[0] < now) continue;
                if (t[1] == 1 && (onlineNext == 0 || t[0] < onlineNext)) onlineNext = t[0];
                if (t[1] == 2 && (specEnd == 0 || t[0] > specEnd)) specEnd = t[0];
            }
            SimpleDateFormat f = Beijing.fmt("yyyy-MM-dd HH:mm:ss");
            if (onlineNext > 0) {
                out.put("online", "下一次可领 " + f.format(new Date(onlineNext * 1000)));
                out.put("online_ts", onlineNext);
            }
            if (specEnd > 0) {
                out.put("spec", (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 15 ? "午间补给" : "晚间补给")
                        + " · 截止 " + f.format(new Date(specEnd * 1000)));
                out.put("spec_end", specEnd);
            }
            if (out.length() > 0) {
                putRoleString(store, role, "mod", "supply", out.toString(), activeWriter);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 深扫收集时间字段：kind 1=名字含online/time 2=名字含spec/end。 */
    private static void collectTimes(Object v, List<long[]> out, int depth) {
        if (depth > 4 || v == null) return;
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase();
                Object val = e.getValue();
                if (val instanceof Number) {
                    long n = ((Number) val).longValue();
                    boolean epochish = n > 1_700_000_000L && n < 2_000_000_000L;
                    if (epochish && (k.contains("end") || k.contains("time") || k.contains("ts"))) {
                        int kind = k.contains("spec") || k.contains("end") ? 2 : 1;
                        out.add(new long[]{n, kind});
                    }
                } else {
                    collectTimes(val, out, depth + 1);
                }
            }
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) collectTimes(o, out, depth + 1);
        }
    }

    // ---------------- 燃霜矿区（notify_frostfire_war_info → mod:frostfire） ----------------

    static void captureFrostfire(WjdrEngine engine, StateStore store, String fpid, long sid) {
        captureFrostfire(engine, store, new RoleKey(fpid, sid, 0L), true);
    }

    static void captureFrostfire(WjdrEngine engine, StateStore store, RoleKey role,
                                 boolean activeWriter) {
        try {
            Map<String, Object> c = engine.notifyContent("notify_frostfire_war_info");
            if (c == null) return;
            Object ai = c.get("act_info");
            if (!(ai instanceof Map)) return;
            Map<String, Object> info = (Map<String, Object>) ai;
            long idx = num(info.get("sign_up_index"));
            long ts = num(info.get("sign_up_ts"));
            JSONObject mod = new JSONObject();
            mod.put("index", idx);
            mod.put("ts", ts);
            putRoleString(store, role, "mod", "frostfire", mod.toString(), activeWriter);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 活动族名/功能映射（与 App DailyRunner.familyName/familyFn 一致） ----------------

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

    // ---------------- 取值辅助（与 App 端同风格：形状防御式） ----------------

    static Object toJavaJson(Object v) {
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
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
            JSONArray a = new JSONArray();
            for (Object x : (List) v) a.put(toJavaJson(x));
            return a;
        }
        if (v instanceof Number) return ((Number) v).longValue();
        return v == null ? JSONObject.NULL : String.valueOf(v);
    }

    private static List<Object> valuesOf(Object v) {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        if (v instanceof Map) {
            out.addAll(((Map) v).values());
        } else if (v instanceof List) {
            out.addAll((List<?>) v);
        }
        return out;
    }

    static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** 深找数值字段（FieldTasks.deepLong 同款：Map/List 递归，嵌套 data/trap/trap_info 兼容）。 */
    static long deepLong(Map<String, Object> m, String... keys) {
        Long v = keys.length > 1
                ? deepFind(m, keys[0], keys[1], 0)
                : deepFind(m, keys[0], null, 0);
        return v == null ? 0 : v;
    }

    private static Long deepFind(Object v, String k1, String k2, int depth) {
        if (depth > 4 || v == null) return null;
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            for (String k : new String[]{k1, k2}) {
                if (k == null) continue;
                Object x = m.get(k);
                if (x instanceof Number) return ((Number) x).longValue();
            }
            for (Object x : m.values()) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        } else if (v instanceof List) {
            for (Object x : (List<?>) v) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}
