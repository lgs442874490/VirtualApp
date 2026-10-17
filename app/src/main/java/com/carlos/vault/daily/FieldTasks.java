package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 野外类任务（协议流移植自网页版 Gather/Intelligence/Behemoth/BearAction/Hunt 服务，
 * 按一次性执行语义：每次运行填满空闲采集队列 / 处理当前情报 / 开启原生自动上车 / 狩猎若干野怪）。
 */
final class FieldTasks {

    private FieldTasks() {
    }

    // ---------- 野外采集：搜资源 → 派队（填空闲队列，默认肉） ----------

    static DailyResult gather(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 子开关门控：send=自动采集（本功能唯一动作；策略只管"任意存量/只采满矿"）
            boolean onSend = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "gather", "send", true);
            if (!onSend) {
                m.skip("子开关「自动采集」已关闭");
                m.subSkip("send", "子开关已关闭");
                return done(m);
            }
            engine.collectNotifies(2500);
            org.json.JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "gather");
            int minLevel = Math.max(1, Math.min(6, p.optInt("min_level", 5)));
            int maxQueues = Math.max(1, Math.min(5, p.optInt("max_queues", 3)));
            java.util.List<Integer> resList = new java.util.ArrayList<>();
            org.json.JSONArray resArr = p.optJSONArray("resources");
            if (resArr == null || resArr.length() == 0) {
                resList.add(1);
                resList.add(2);
            } else {
                for (int i = 0; i < resArr.length(); i++) {
                    // 资源id 102肉/103木/104煤/105铁 → 采集 res_id 1/2/3/4
                    resList.add(resArr.optInt(i) - 101);
                }
            }
            int active = countMarches(engine, 4);
            int capacity = Math.min(maxQueues, marchCapacity(engine));
            // 守护盟友队列预留（设计 §6.10 双池）：守护开启时采集最多占 capacity-2，
            // 给卡秒驻防留 2 个机动队列（GuardianLaunch 发 type=5 需要空队列）
            try {
                if (com.carlos.vault.daily.guardian.GuardianEngine.reserveQueues(engine.appContext(),
                        engine.lastFpid(), engine.lastServerId()) > 0) {
                    capacity = Math.max(active, capacity - com.carlos.vault.daily.guardian.GuardianEngine.reserveQueues(
                            engine.appContext(), engine.lastFpid(), engine.lastServerId()));
                }
            } catch (Throwable ignored) {
            }
            // 策略：只采满矿（req_world_map_search 的 is_full 过滤）
            boolean fullOnly = "full".equals(FnParams.strategy(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "gather", "send", "any"));
            int slots = Math.max(0, capacity - active);
            if (slots == 0) {
                m.skip("采集队列已满(" + active + "/" + capacity + ")");
            } else {
                int sent = 0;
                for (int res : resList) {
                    if (sent >= slots) break;
                    for (int level = Math.min(6, minLevel); level >= 1 && sent < slots; level--) {
                        Map<String, Object> pt = searchResource(engine, res, level, fullOnly);
                        if (pt == null) continue;
                        Map<String, Object> formation = ExecUtil.formation(engine, 1 + (sent % 3));
                        if (formation == null) {
                            m.skip("无英雄可派");
                            return done(m);
                        }
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", 4L, "endpoint", pt, "formation", formation,
                                "extra", new java.util.LinkedHashMap<>()));
                        if (ExecUtil.marchId(march) > 0) {
                            sent++;
                        } else {
                            int code = ExecUtil.codeOf(march);
                            if (code == 2878 || code == 401) {   // 队列满/兵不足
                                m.skip("派出" + sent + "后受阻(码" + code + ")");
                                return done(m);
                            }
                        }
                    }
                }
                if (sent > 0) {
                    m.claimed("派出采集×" + sent);
                    mod(engine, "gather", "send", "上轮派出 " + sent + " 队（队列 " + active + "/" + capacity + "）");
                    // 容量估算 + 采集英雄推荐（对标原版 MarchCapacityState；兵量=节点余量÷容量）
                    try {
                        GatherCapacity.Estimate est = GatherCapacity.estimateFull(engine);
                        mod(engine, "gather", "capacity", "编队容量≈" + est.capacity + "/队"
                                + (est.note.isEmpty() ? "" : "（" + est.note + "）")
                                + " · 满载兵量=节点余量÷容量 · " + GatherCapacity.GATHER_HERO_HINT);
                    } catch (Throwable ignored) {
                    }
                } else m.skip("附近未搜到资源点");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "野外采集");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return done(m);
    }

    static void mod(WjdrEngine engine, String fnId, String subId, String text) {
        try {
            FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(), fnId, subId, text);
        } catch (Throwable ignored) {
        }
    }

    private static DailyResult done(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("gather", "野外采集", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** req_world_map_search 找一个资源点坐标（找不到返回 null）。fullOnly=只搜满存量矿。 */
    private static Map<String, Object> searchResource(WjdrEngine engine, int resId, int level,
                                                      boolean fullOnly) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                "mapobj_type", 2L, "level_min", level, "level_max", level,
                "res_id", resId, "is_full", fullOnly));
        if (!ExecUtil.ok(rsp)) return null;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : ExecUtil.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    /** 当前 type=N 的行军数。协议盘点 2026-10-12 两级实锤：
     * ① notify_player_march_init 不在 s2c schema（旧实现恒返回 0 → 已派出队列被重复计入空位）；
     * ② schema 解析得 req_world_march_detail_4_player_situation.request{owner,kid}，
     *    空参被服务端拒（code 2 实证）——owner=玩家uid、kid=区号，行军列表在响应 detail 字段。
     *  查询失败按 0 处理（回退旧行为，宁可让服务端拒多发也不漏发）。 */
    private static int countMarches(WjdrEngine engine, int type) {
        try {
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return 0;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return 0;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            int n = 0;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail == null ? null : detail.get(k))) {
                    if (ExecUtil.longOf(march.get("type")) == type) n++;
                }
                if (n > 0) break;   // 取第一个命中的列表形状
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int marchCapacity(WjdrEngine engine) {
        Map<String, Object> wp = engine.notifyData("notify_world_player_init");
        if (wp == null) return 3;
        Map<String, Object> world = ExecUtil.mapOf(wp.get("world"));
        long cap = world == null ? 0 : ExecUtil.longOf(world.get("capacity"));
        return cap > 0 ? (int) cap : 3;
    }

    // ---------- 情报任务：处理当前可派事件 ----------

    static DailyResult intel(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位：情报派队同样占行军队列，威胁窗口内让给卡秒驻防
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：events=处理情报（本功能唯一动作）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "intel", "events", true)) {
                m.skip("子开关「处理情报」已关闭");
                m.subSkip("events", "子开关已关闭");
                DailyResult off = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(2500);
            Map<String, Object> ev = engine.notifyData("notify_intelligence_events");
            if (ev == null) {
                m.skip("暂无情报事件");
            } else {
                int handled = 0;
                for (Map<String, Object> e : ExecUtil.mapsOf(ev.get("events"))) {
                    long id = ExecUtil.longOf(e.get("id"));
                    long status = ExecUtil.longOf(e.get("status"));
                    long type = ExecUtil.longOf(e.get("type"));
                    if (id <= 0 || status != 1) continue;   // 1=待处理
                    boolean doneOne = false;
                    if (type == 2 || type == 3) {
                        Map<String, Object> formation = ExecUtil.formation(engine, 1);
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", type == 2 ? 301L : 302L,
                                "endpoint", ExecUtil.point(ExecUtil.longOf(e.get("x")), ExecUtil.longOf(e.get("y"))),
                                "formation", formation,
                                "extra", ExecUtil.args("event_id", id)));
                        doneOne = ExecUtil.marchId(march) > 0;
                    } else {
                        List<Long> heroes = TownTasks.squadHeroes(engine);
                        List<Object> fightHeros = new ArrayList<>(heroes);
                        GameMessage st = engine.request("req_intelligence_start_battle",
                                ExecUtil.args("id", id, "fight_heros_data",
                                        ExecUtil.args("source", 1, "fight_heros", fightHeros)));
                        if (ExecUtil.ok(st)) {
                            ExecUtil.sleep(3000);
                            GameMessage en = engine.request("req_intelligence_end_battle",
                                    ExecUtil.args("id", id));
                            doneOne = ExecUtil.ok(en);
                        }
                    }
                    if (doneOne) {
                        handled++;
                        GameMessage rc = engine.request("req_intelligence_receive",
                                ExecUtil.args("id", id));
                        if (!ExecUtil.ok(rc) && ExecUtil.codeOf(rc) != 2501) {
                            m.skip("领奖码" + ExecUtil.codeOf(rc));
                        }
                    }
                }
                if (handled > 0) m.claimed("处理情报×" + handled);
                else m.skip("无可派情报");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "情报任务");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 巨兽狩猎：开启客户端原生自动上车（巨兽/死神/巨熊） ----------

    static DailyResult behemoth(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "behemoth", "auto_join", true)) {
                m.skip("自动上车已关闭（设置页可开）");
                DailyResult rr = new DailyResult("behemoth", "巨兽狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
            }
            // 同步一次当前开关状态
            engine.request("req_alliance_activity_auto_join_info",
                    ExecUtil.args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L)));
            GameMessage open = engine.request("req_alliance_activity_auto_join_open",
                    ExecUtil.args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L),
                            "march_limit", 3L));
            if (ExecUtil.ok(open)) {
                m.claimed("自动上车已开启(巨兽/死神/巨熊)");
                mod(engine, "behemoth", "auto_join", "原生自动上车：已开启（巨兽/死神/巨熊）");
            }
            else m.skip("开启码" + ExecUtil.codeOf(open));
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "巨兽狩猎");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("behemoth", "巨兽狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 巨熊行动：同步陷阱预约（发车/上车由自动上车承接） ----------

    static DailyResult bear(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位（交接 §6.2：防请求队列饿死卡秒驻防 + 保留队列不被日常吃掉）
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：trap=巨熊陷阱同步（本功能唯一动作）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "bear", "trap", true)) {
                m.skip("子开关「巨熊陷阱同步」已关闭");
                m.subSkip("trap", "子开关已关闭");
                DailyResult off = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(1500);
            GameMessage trap = engine.request("req_alliance_trap_info", null);
            try {
                DailyRunner.debugLog(engine.appContext(), "trap rsp=" + String.valueOf(trap.getContent())
                        .substring(0, Math.min(800, String.valueOf(trap.getContent()).length())));
            } catch (Throwable ignored) {
            }
            if (ExecUtil.ok(trap)) {
                // 原版字段：reserve_time/mass_start_time/mass_end_time（内容可能在 data/trap/trap_info 嵌套里）
                long reserve = deepLong(trap.getContent(), "reserve_time", "reserveTime", 0);
                long massStart = deepLong(trap.getContent(), "mass_start_time", "massStartTime", 0);
                long massEnd = deepLong(trap.getContent(), "mass_end_time", "massEndTime", 0);
                long coolEnd = deepLong(trap.getContent(), "cool_end_time", "coolEndTime", 0);
                java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm",
                        java.util.Locale.CHINA);
                java.text.SimpleDateFormat dh = new java.text.SimpleDateFormat("M月d日 HH:mm",
                        java.util.Locale.CHINA);
                boolean rv = reserve > 1_700_000_000L && reserve < 2_000_000_000L;
                String bearText = rv
                        ? "已预约 " + dh.format(new java.util.Date(reserve * 1000))
                        : (reserve > 0 ? "已预约" : "未预约 · 建议提前预约");
                try {
                    org.json.JSONObject mod = new org.json.JSONObject();
                    mod.put("trap", bearText);
                    if (rv) mod.put("reserve", reserve);
                    if (massStart > 1_700_000_000L && massStart < 2_000_000_000L) {
                        mod.put("start", massStart);
                    }
                    if (massEnd > 1_700_000_000L && massEnd < 2_000_000_000L) {
                        mod.put("end", massEnd);
                    }
                    if (coolEnd > 1_700_000_000L && coolEnd < 2_000_000_000L) {
                        mod.put("cool", coolEnd);
                    }
                    FnParams.saveModState(engine.appContext(), engine.lastFpid(),
                            engine.lastServerId(), "bear", mod);
                } catch (Throwable ignored) {
                }
                m.claimed(reserve > 0 ? bearText : "陷阱状态已同步");
            } else {
                int code = ExecUtil.codeOf(trap);
                m.skip(code == 2 || code == 7401 ? "无巨熊陷阱/未到活动" : "陷阱码" + code);
            }
        } catch (Exception e) {
            try {
                DailyRunner.debugLog(engine.appContext(), "bear fail: " + e);
            } catch (Throwable ignored) {
            }
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "巨熊行动");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** 深扫找数字字段（嵌套 Map/List，depth<=4）。 */
    private static long deepLong(Object v, String k1, String k2, long def) {
        Long r = deepFind(v, k1, k2, 0);
        return r == null ? def : r;
    }

    private static Long deepFind(Object v, String k1, String k2, int depth) {
        if (depth > 4 || v == null) return null;
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            for (String k : new String[]{k1, k2}) {
                Object x = m.get(k);
                if (x instanceof Number) return ((Number) x).longValue();
            }
            for (Object x : m.values()) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        } else if (v instanceof java.util.List) {
            for (Object x : (java.util.List<?>) v) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------- 自动狩猎野怪 ----------

    static DailyResult hunt(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位：狩猎占行军队列，威胁窗口内不与卡秒驻防抢队
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：hunt=自动狩猎（本功能唯一动作；参数只管等级区间/次数）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "hunt", "hunt", true)) {
                m.skip("子开关「自动狩猎」已关闭");
                m.subSkip("hunt", "子开关已关闭");
                DailyResult off = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(2500);
            org.json.JSONObject hp = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "hunt");
            int lvMin = Math.max(1, Math.min(10, hp.optInt("level_min", 4)));
            int lvMax = Math.max(lvMin, Math.min(10, hp.optInt("level_max", 6)));
            int maxTimes = Math.max(1, Math.min(20, hp.optInt("max_times", 5)));
            int hunted = 0;
            int noTarget = 0;
            for (int round = 0; round < maxTimes; round++) {
                Map<String, Object> target = null;
                long level = 0;
                for (int lv = lvMax; lv >= lvMin && target == null; lv--) {
                    target = searchMonster(engine, lv);
                    level = lv;
                }
                if (target == null) {
                    noTarget++;
                    break;
                }
                Map<String, Object> formation = ExecUtil.formation(engine, 1 + (round % 3));
                if (formation == null) {
                    m.skip("无英雄可派");
                    break;
                }
                long monsterId = ExecUtil.longOf(target.get("monsterid"));
                GameMessage march = engine.request("req_world_march", ExecUtil.args(
                        "type", 3L,
                        "endpoint", ExecUtil.point(ExecUtil.longOf(target.get("x")), ExecUtil.longOf(target.get("y"))),
                        "formation", formation,
                        "extra", ExecUtil.args("monsterid", monsterId)));
                if (ExecUtil.marchId(march) > 0) {
                    hunted++;
                } else {
                    int code = ExecUtil.codeOf(march);
                    if (code == 51) {
                        m.skip("体力不足");
                        break;
                    }
                    if (code == 2878) {
                        m.skip("行军队列已满");
                        break;
                    }
                    noTarget++;
                    if (noTarget >= 3) break;
                }
                ExecUtil.sleep(500);
            }
            if (hunted > 0) {
                m.claimed("狩猎×" + hunted);
                mod(engine, "hunt", "hunt", "上轮狩猎 " + hunted + " 只（Lv" + lvMin + "-" + lvMax + "）");
            }
            else if (m.sb.length() == 0) m.skip("未找到可猎野怪");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "自动狩猎");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    private static Map<String, Object> searchMonster(WjdrEngine engine, int level) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                "mapobj_type", 3L, "level_min", level, "level_max", level));
        if (!ExecUtil.ok(rsp)) return null;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : ExecUtil.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    // ---------- 探险速刷（is_fast 快速战斗通道，2026-10-11 协议实证） ----------
    //
    // req_begin_adventure_pve{checkpoint, fight_heros_data, is_fast} →
    // req_end_adventure_pve{checkpoint, operatedata, enddata}。
    // 已通关(≤pass_checkpoint)的关卡走 is_fast 速刷无需战斗回放；未通关的新关
    // 需要客户端战斗模拟，enddata 形状未实证——遇到即停并提示手动解锁一次。

    static DailyResult adventureSweep(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            android.content.Context sctx = engine.appContext();
            String sfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long ssidx = engine.lastServerId();
            org.json.JSONObject sp = FnParams.params(sctx, sfpid, ssidx, "adventure_sweep");
            org.json.JSONObject strat = sp.optJSONObject("strategy");
            int maxSweep = strat != null && strat.has("sweep") ? strat.optInt("sweep", 5) : 5;
            maxSweep = Math.max(1, Math.min(50, maxSweep));

            Map<String, Object> pve = engine.notifyData("notify_adventure_pve_info");
            long passed = TownTasks.numIn(pve, "pass_checkpoint");
            if (passed <= 0) {
                m.skip("探险进度未同步（先手动进一次探险）");
                m.subSkip("sweep", "进度未同步");
                return sweepResult(m);
            }
            List<Long> heroes = ExecUtil.clientSquads(engine).values().stream().findFirst()
                    .orElseGet(() -> ExecUtil.allHeroes(engine).stream().limit(5)
                            .collect(java.util.stream.Collectors.toList()));
            if (heroes.isEmpty()) {
                m.skip("无可用英雄编队");
                m.subSkip("sweep", "无编队");
                return sweepResult(m);
            }
            int swept = 0;
            for (int i = 0; i < maxSweep; i++) {
                long next = passed + 1;
                GameMessage begin = engine.request("req_begin_adventure_pve", ExecUtil.args(
                        "checkpoint", next, "fight_heros_data", heroes, "is_fast", true));
                if (!ExecUtil.ok(begin)) {
                    int code = ExecUtil.codeOf(begin);
                    m.skip("第" + next + "关无法速刷(码" + code + ")"
                            + (code != 0 ? "" : ""));
                    break;
                }
                GameMessage end = engine.request("req_end_adventure_pve", ExecUtil.args(
                        "checkpoint", next, "operatedata", new java.util.LinkedHashMap<>(),
                        "enddata", new ArrayList<>()));
                if (ExecUtil.ok(end)) {
                    swept++;
                    passed = next;
                    m.claimed("速刷第" + next + "关");
                } else {
                    int code = ExecUtil.codeOf(end);
                    m.skip("第" + next + "关未确认(码" + code + ")——新关卡需手动战斗解锁一次");
                    break;
                }
            }
            if (swept > 0) {
                m.subOk("sweep", "速刷×" + swept + "（推进到第" + passed + "关）");
                mod(engine, "adventure_sweep", "sweep", "上轮速刷 " + swept + " 关 → 第 " + passed + " 关");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "探险速刷");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return sweepResult(m);
    }

    private static DailyResult sweepResult(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("adventure_sweep", "探险速刷", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }
}
