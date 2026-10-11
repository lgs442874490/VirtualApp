package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 野外类 + 城建类任务（App 端 daily/FieldTasks + TownDevTasks 逐行移植，改动须两侧同步）。
 */
public final class ServerFieldTasks {

    private ServerFieldTasks() {
    }

    // ---------- 野外采集 ----------

    public static TaskOutcome gather(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            JSONObject p = tc.params("gather");
            int minLevel = Math.max(1, Math.min(6, p.optInt("min_level", 5)));
            int maxQueues = Math.max(1, Math.min(5, p.optInt("max_queues", 3)));
            List<Integer> resList = new ArrayList<>();
            org.json.JSONArray resArr = p.optJSONArray("resources");
            if (resArr == null || resArr.length() == 0) {
                resList.add(1);
                resList.add(2);
            } else {
                for (int i = 0; i < resArr.length(); i++) {
                    resList.add(resArr.optInt(i) - 101);
                }
            }
            int active = countMarches(tc.engine, 4);
            int capacity = Math.min(maxQueues, marchCapacity(tc.engine));
            boolean fullOnly = "full".equals(tc.strategy("gather", "send", "any"));
            int slots = Math.max(0, capacity - active);
            if (slots == 0) {
                m.skip("采集队列已满(" + active + "/" + capacity + ")");
            } else {
                int sent = 0;
                for (int res : resList) {
                    if (sent >= slots) break;
                    for (int level = Math.min(6, minLevel); level >= 1 && sent < slots; level--) {
                        Map<String, Object> pt = searchResource(tc.engine, res, level, fullOnly);
                        if (pt == null) continue;
                        Map<String, Object> formation = Exec.formation(tc.engine, 1 + (sent % 3));
                        if (formation == null) {
                            m.skip("无英雄可派");
                            return Exec.result(m, "gather", "野外采集");
                        }
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", 4L, "endpoint", pt, "formation", formation,
                                "extra", new java.util.LinkedHashMap<>()));
                        if (Exec.marchId(march) > 0) {
                            sent++;
                        } else {
                            int code = Exec.codeOf(march);
                            if (code == 2878 || code == 401) {
                                m.skip("派出" + sent + "后受阻(码" + code + ")");
                                return Exec.result(m, "gather", "野外采集");
                            }
                        }
                    }
                }
                if (sent > 0) {
                    m.claimed("派出采集×" + sent);
                    tc.saveModText("gather", "send",
                            "上轮派出 " + sent + " 队（队列 " + active + "/" + capacity + "）");
                } else m.skip("附近未搜到资源点");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "野外采集");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "gather", "野外采集");
    }

    private static Map<String, Object> searchResource(WjdrEngine engine, int resId, int level,
                                                      boolean fullOnly) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", Exec.args(
                "mapobj_type", 2L, "level_min", level, "level_max", level,
                "res_id", resId, "is_full", fullOnly));
        if (!Exec.ok(rsp)) return null;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : Exec.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    private static int countMarches(WjdrEngine engine, int type) {
        Map<String, Object> mi = engine.notifyData("notify_player_march_init");
        int n = 0;
        if (mi == null) return 0;
        for (Map<String, Object> march : Exec.mapsOf(mi.get("marchs"))) {
            if (Exec.longOf(march.get("type")) == type) n++;
        }
        return n;
    }

    private static int marchCapacity(WjdrEngine engine) {
        Map<String, Object> wp = engine.notifyData("notify_world_player_init");
        if (wp == null) return 3;
        Map<String, Object> world = Exec.mapOf(wp.get("world"));
        long cap = world == null ? 0 : Exec.longOf(world.get("capacity"));
        return cap > 0 ? (int) cap : 3;
    }

    // ---------- 情报任务 ----------

    public static TaskOutcome intel(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            Map<String, Object> ev = tc.engine.notifyData("notify_intelligence_events");
            if (ev == null) {
                m.skip("暂无情报事件");
            } else {
                int handled = 0;
                for (Map<String, Object> e : Exec.mapsOf(ev.get("events"))) {
                    long id = Exec.longOf(e.get("id"));
                    long status = Exec.longOf(e.get("status"));
                    long type = Exec.longOf(e.get("type"));
                    if (id <= 0 || status != 1) continue;
                    boolean doneOne = false;
                    if (type == 2 || type == 3) {
                        Map<String, Object> formation = Exec.formation(tc.engine, 1);
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", type == 2 ? 301L : 302L,
                                "endpoint", Exec.point(Exec.longOf(e.get("x")), Exec.longOf(e.get("y"))),
                                "formation", formation,
                                "extra", Exec.args("event_id", id)));
                        doneOne = Exec.marchId(march) > 0;
                    } else {
                        List<Long> heroes = ServerTownTasks.squadHeroes(tc.engine);
                        List<Object> fightHeros = new ArrayList<>(heroes);
                        GameMessage st = tc.engine.request("req_intelligence_start_battle",
                                Exec.args("id", id, "fight_heros_data",
                                        Exec.args("source", 1, "fight_heros", fightHeros)));
                        if (Exec.ok(st)) {
                            Exec.sleep(3000);
                            GameMessage en = tc.engine.request("req_intelligence_end_battle",
                                    Exec.args("id", id));
                            doneOne = Exec.ok(en);
                        }
                    }
                    if (doneOne) {
                        handled++;
                        GameMessage rc = tc.engine.request("req_intelligence_receive",
                                Exec.args("id", id));
                        if (!Exec.ok(rc) && Exec.codeOf(rc) != 2501) {
                            m.skip("领奖码" + Exec.codeOf(rc));
                        }
                    }
                }
                if (handled > 0) m.claimed("处理情报×" + handled);
                else m.skip("无可派情报");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "情报任务");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "intel", "情报任务");
    }

    // ---------- 自动狩猎野怪 ----------

    public static TaskOutcome hunt(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            JSONObject hp = tc.params("hunt");
            int lvMin = Math.max(1, Math.min(10, hp.optInt("level_min", 4)));
            int lvMax = Math.max(lvMin, Math.min(10, hp.optInt("level_max", 6)));
            int maxTimes = Math.max(1, Math.min(20, hp.optInt("max_times", 5)));
            int hunted = 0;
            int noTarget = 0;
            for (int round = 0; round < maxTimes; round++) {
                Map<String, Object> target = null;
                for (int lv = lvMax; lv >= lvMin && target == null; lv--) {
                    target = searchMonster(tc.engine, lv);
                }
                if (target == null) {
                    noTarget++;
                    break;
                }
                Map<String, Object> formation = Exec.formation(tc.engine, 1 + (round % 3));
                if (formation == null) {
                    m.skip("无英雄可派");
                    break;
                }
                long monsterId = Exec.longOf(target.get("monsterid"));
                GameMessage march = tc.engine.request("req_world_march", Exec.args(
                        "type", 3L,
                        "endpoint", Exec.point(Exec.longOf(target.get("x")), Exec.longOf(target.get("y"))),
                        "formation", formation,
                        "extra", Exec.args("monsterid", monsterId)));
                if (Exec.marchId(march) > 0) {
                    hunted++;
                } else {
                    int code = Exec.codeOf(march);
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
                Exec.sleep(500);
            }
            if (hunted > 0) {
                m.claimed("狩猎×" + hunted);
                tc.saveModText("hunt", "hunt", "上轮狩猎 " + hunted + " 只（Lv" + lvMin + "-" + lvMax + "）");
            } else if (m.sb.length() == 0) m.skip("未找到可猎野怪");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "自动狩猎");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "hunt", "自动狩猎");
    }

    // ---------- 探险速刷（App FieldTasks.adventureSweep 逐行移植） ----------
    //
    // req_begin_adventure_pve{checkpoint, fight_heros_data, is_fast} →
    // req_end_adventure_pve{checkpoint, operatedata, enddata}。
    // 已通关(≤pass_checkpoint)的关卡走 is_fast 速刷无需战斗回放；未通关的新关
    // 需要客户端战斗模拟，enddata 形状未实证——遇到即停并提示手动解锁一次。

    public static TaskOutcome adventureSweep(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            JSONObject sp = tc.params("adventure_sweep");
            JSONObject strat = sp.optJSONObject("strategy");
            int maxSweep = strat != null && strat.has("sweep") ? strat.optInt("sweep", 5) : 5;
            maxSweep = Math.max(1, Math.min(50, maxSweep));

            Map<String, Object> pve = tc.engine.notifyData("notify_adventure_pve_info");
            long passed = numIn(pve, "pass_checkpoint");
            if (passed <= 0) {
                m.skip("探险进度未同步（先手动进一次探险）");
                m.subSkip("sweep", "进度未同步");
                return Exec.result(m, "adventure_sweep", "探险速刷");
            }
            List<Long> heroes = null;
            for (List<Long> v : Exec.clientSquads(tc.engine).values()) {
                if (v != null && !v.isEmpty()) {
                    heroes = v;
                    break;
                }
            }
            if (heroes == null) {
                heroes = new ArrayList<>();
                List<Long> all = Exec.allHeroes(tc.engine);
                for (int i = 0; i < all.size() && heroes.size() < 5; i++) heroes.add(all.get(i));
            }
            if (heroes.isEmpty()) {
                m.skip("无可用英雄编队");
                m.subSkip("sweep", "无编队");
                return Exec.result(m, "adventure_sweep", "探险速刷");
            }
            int swept = 0;
            for (int i = 0; i < maxSweep; i++) {
                long next = passed + 1;
                GameMessage begin = tc.engine.request("req_begin_adventure_pve", Exec.args(
                        "checkpoint", next, "fight_heros_data", heroes, "is_fast", true));
                if (!Exec.ok(begin)) {
                    int code = Exec.codeOf(begin);
                    m.skip("第" + next + "关无法速刷(码" + code + ")"
                            + (code != 0 ? "" : ""));
                    break;
                }
                GameMessage end = tc.engine.request("req_end_adventure_pve", Exec.args(
                        "checkpoint", next, "operatedata", new LinkedHashMap<String, Object>(),
                        "enddata", new ArrayList<>()));
                if (Exec.ok(end)) {
                    swept++;
                    passed = next;
                    m.claimed("速刷第" + next + "关");
                } else {
                    int code = Exec.codeOf(end);
                    m.skip("第" + next + "关未确认(码" + code + ")——新关卡需手动战斗解锁一次");
                    break;
                }
            }
            if (swept > 0) {
                m.subOk("sweep", "速刷×" + swept + "（推进到第" + passed + "关）");
                tc.saveModText("adventure_sweep", "sweep",
                        "上轮速刷 " + swept + " 关 → 第 " + passed + " 关");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "探险速刷");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "adventure_sweep", "探险速刷");
    }

    /** 逐层下钻取数值（App TownTasks.numIn 同款：顶层或一层容器内）。 */
    private static long numIn(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        if (v instanceof Number) return Exec.longOf(v);
        for (Object o : map.values()) {
            if (o instanceof Map) {
                Object inner = ((Map<?, ?>) o).get(key);
                if (inner instanceof Number) return Exec.longOf(inner);
            }
        }
        return 0;
    }

    private static Map<String, Object> searchMonster(WjdrEngine engine, int level) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", Exec.args(
                "mapobj_type", 3L, "level_min", level, "level_max", level));
        if (!Exec.ok(rsp)) return null;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : Exec.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    // ================= 城建：科技研究 + 建筑升级（App TownDevTasks 移植） =================

    public static final class DevState {
        public final Map<Long, Long> techLevels = new HashMap<>();
        public final Map<Long, Integer> buildingLevels = new HashMap<>();
        public final java.util.Set<Long> busyBuildings = new HashSet<>();
        public Long idleResearchQueue;
        public final List<Long> idleBuildQueues = new ArrayList<>();
        public boolean ready;
    }

    static DevState snapshot(WjdrEngine engine, TaskContext tc) {
        DevState s = new DevState();
        Map<String, Object> ar = engine.notifyData("notify_all_research");
        Map<String, Object> si = engine.notifyData("notify_shelter_info");
        Map<String, Object> qi = engine.notifyData("notify_queue_info");
        if (ar == null || si == null || qi == null) return s;
        s.ready = true;
        Object researched = ar.get("researched");
        Map<Long, Long> kv = Exec.kvPairs(researched instanceof Map
                ? ((Map<?, ?>) researched) : null);
        if (!kv.isEmpty()) {
            for (Map.Entry<Long, Long> e : kv.entrySet()) s.techLevels.put(e.getKey(), e.getValue());
        } else {
            for (Map<String, Object> m : Exec.mapsOf(researched)) {
                long tid = Exec.longOf(m.get("tid"));
                if (tid > 0) s.techLevels.put(tid, Exec.longOf(m.get("lev")));
            }
        }
        Object bi = si.get("building_info");
        if (bi instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) bi).entrySet()) {
                absorbBuilding(s, e.getValue(), Exec.longOf(e.getKey()));
            }
        } else {
            for (Object o : Exec.listOf(bi) != null ? Exec.listOf(bi) : new ArrayList<>()) {
                absorbBuilding(s, o, 0);
            }
        }
        Object qm = qi.get("queue_map");
        List<Map<String, Object>> queues = new ArrayList<>();
        if (qm instanceof Map) {
            for (Object v : ((Map<?, ?>) qm).values()) {
                Map<String, Object> mm = Exec.mapOf(v);
                if (mm != null) queues.add(mm);
            }
        } else {
            queues = Exec.mapsOf(qm);
        }
        for (Map<String, Object> q : queues) {
            long qtype = Exec.longOf(Exec.mapOf(q.get("base")) != null
                    ? Exec.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
            long status = Exec.longOf(q.get("status"));
            long end = Exec.longOf(q.get("end_time"));
            long qid = Exec.longOf(q.get("qid"));
            boolean busy = status != 1 && end > 0;
            if (busy) continue;
            if (qtype == 1 && qid > 0) s.idleBuildQueues.add(qid);
            if (qtype == 2 && qid > 0 && s.idleResearchQueue == null) s.idleResearchQueue = qid;
        }
        // 队列倒计时状态行（building.auto / research.auto）
        try {
            java.text.SimpleDateFormat hm = com.carlos.vaultserver.Beijing.fmt("HH:mm");
            Map<String, Object> qi2 = engine.notifyContent("notify_queue_info");
            Object qm2 = Exec.mapOf(qi2 == null ? null : qi2.get("data")) != null
                    ? Exec.mapOf(qi2.get("data")).get("queue_map") : qi2.get("queue_map");
            List<Map<String, Object>> qs = new ArrayList<>();
            if (qm2 instanceof Map) {
                for (Object v2 : ((Map<?, ?>) qm2).values()) {
                    Map<String, Object> mm = Exec.mapOf(v2);
                    if (mm != null) qs.add(mm);
                }
            } else {
                qs = Exec.mapsOf(qm2);
            }
            long bEnd = 0, rEnd = 0;
            for (Map<String, Object> q : qs) {
                long qtype = Exec.longOf(Exec.mapOf(q.get("base")) != null
                        ? Exec.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
                long end = Exec.longOf(q.get("end_time"));
                if (end <= System.currentTimeMillis() / 1000L) continue;
                if (qtype == 1 && (bEnd == 0 || end < bEnd)) bEnd = end;
                if (qtype == 2 && (rEnd == 0 || end < rEnd)) rEnd = end;
            }
            if (bEnd > 0) tc.saveModText("building", "auto",
                    "施工中 · " + hm.format(new java.util.Date(bEnd * 1000)) + "完成");
            if (rEnd > 0) tc.saveModText("research", "auto",
                    "研究进行中 · " + hm.format(new java.util.Date(rEnd * 1000)) + "完成");
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static void absorbBuilding(DevState s, Object raw, long fallbackBid) {
        Map<String, Object> entry = Exec.mapOf(raw);
        if (entry == null) return;
        long bid = Exec.longOf(entry.get("bid"));
        if (bid <= 0) bid = fallbackBid;
        if (bid <= 0) return;
        Map<String, Object> base = Exec.mapOf(entry.get("base"));
        int lv = base != null ? Exec.intOf(base.get("lv")) : Exec.intOf(entry.get("lv"));
        long status = base != null ? Exec.longOf(base.get("status")) : Exec.longOf(entry.get("status"));
        long qid = Exec.longOf(entry.get("qid"));
        if (lv > 0) s.buildingLevels.put(bid, lv);
        if (status != 0 || qid > 0) s.busyBuildings.add(bid);
    }

    // ---------- 科技研究 ----------

    public static TaskOutcome research(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            DevState s = snapshot(tc.engine, tc);
            if (!s.ready) {
                m.skip("科技/建筑/队列数据未同步");
                return Exec.result(m, "research", "科技研究");
            }
            if (s.idleResearchQueue == null) {
                m.skip("研究队列忙");
                return Exec.result(m, "research", "科技研究");
            }
            String profile = tc.params("research").optString("profile", "casual");
            List<TownDevCatalog.Goal> goals = TownDevCatalog.route(profile);
            TownDevCatalog cat = TownDevCatalog.get();
            long[] action = nextTech(cat, goals, s.techLevels, s.buildingLevels, new HashSet<>());
            if (action == null) {
                m.skip("路线内科技已全部达标或被前置阻塞");
                return Exec.result(m, "research", "科技研究");
            }
            long tid = action[0];
            int toLevel = (int) action[1];
            TownDevCatalog.Tech t = cat.techs.get(tid);
            long encoded = tid * 10 + toLevel;
            GameMessage rsp = tc.engine.request("req_begin_research", Exec.args(
                    "tid_lev", encoded, "qid", s.idleResearchQueue));
            int code = Exec.codeOf(rsp);
            if (Exec.ok(rsp)) {
                m.claimed("研究 " + (t == null ? tid : t.name) + " → Lv" + toLevel);
                GameMessage help = tc.engine.request("req_queue_ask_for_help",
                        Exec.args("qid", s.idleResearchQueue));
                if (!Exec.ok(help)) m.skip("联盟求助不可用");
            } else if (code == 51) {
                m.skip("研究资源不足");
            } else if (code == 4111) {
                m.skip("科研所正在升级");
            } else if (code == 806) {
                m.skip("前置科技/建筑等级不足");
            } else {
                m.fail("研究码" + code);
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "科技研究");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "research", "科技研究");
    }

    private static long[] nextTech(TownDevCatalog cat, List<TownDevCatalog.Goal> goals,
                                   Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                   java.util.Set<Long> path) {
        for (TownDevCatalog.Goal g : goals) {
            long[] r = ensureTech(cat, g.tid, g.targetLevel, levels, buildingLevels, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureTech(TownDevCatalog cat, long tid, int requiredLevel,
                                     Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                     java.util.Set<Long> path) {
        long cur = levels.getOrDefault(tid, 0L);
        if (cur >= requiredLevel) return null;
        if (!path.add(tid)) return null;
        TownDevCatalog.Tech t = cat.techs.get(tid);
        if (t == null) {
            path.remove(tid);
            return null;
        }
        int next = (int) cur + 1;
        if (next > t.maxLevel) {
            path.remove(tid);
            return null;
        }
        TownDevCatalog.TechLevel lv = t.level(next);
        if (lv == null) {
            path.remove(tid);
            return null;
        }
        for (long[] cond : lv.conditions) {
            if (cond[0] == 14) {
                long[] r = ensureTech(cat, cond[1], (int) cond[2], levels, buildingLevels, path);
                if (r != null) {
                    path.remove(tid);
                    return r;
                }
            } else if (cond[0] == 5) {
                int bl = buildingLevels.getOrDefault(cond[1], 0);
                if (bl < cond[2]) {
                    path.remove(tid);
                    return null;
                }
            }
        }
        path.remove(tid);
        return new long[]{tid, next};
    }

    // ---------- 建筑升级 ----------

    public static TaskOutcome building(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            DevState s = snapshot(tc.engine, tc);
            if (!s.ready) {
                m.skip("建筑/队列数据未同步");
                return Exec.result(m, "building", "建筑升级");
            }
            int maxActions = tc.params("building").optInt("max_actions", 2);
            TownDevCatalog cat = TownDevCatalog.get();
            int done = 0;
            for (int i = 0; i < maxActions; i++) {
                if (s.idleBuildQueues.isEmpty()) {
                    if (i == 0) m.skip("建造队列忙");
                    break;
                }
                long[] action = nextBuilding(cat, s, new HashSet<>());
                if (action == null) {
                    if (i == 0) m.skip("无可升建筑（前置/队列/资源限制）");
                    break;
                }
                long bid = action[0];
                int toLevel = (int) action[1];
                TownDevCatalog.Building b = cat.buildings.get(bid);
                long qid = s.idleBuildQueues.remove(0);
                GameMessage rsp = tc.engine.request("req_upgrade_building", Exec.args(
                        "bid", bid, "use_diamond", false, "qid", qid));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    done++;
                    s.buildingLevels.put(bid, toLevel);
                    s.busyBuildings.add(bid);
                    m.claimed((b == null ? bid : b.name) + "→Lv" + toLevel);
                    GameMessage help = tc.engine.request("req_queue_ask_for_help",
                            Exec.args("qid", qid));
                    if (!Exec.ok(help)) m.skip("求助不可用");
                } else if (code == 51) {
                    m.skip("资源不足，停止本轮");
                    break;
                } else if (code == 806) {
                    m.skip("前置建筑等级不足");
                    break;
                } else if (code == 119 || code == 120) {
                    m.skip("需先满足家具门槛（大熔炉类）");
                    break;
                } else {
                    m.skip("升级码" + code);
                    break;
                }
            }
            if (done == 0 && m.sb.length() == 0) m.skip("无可升建筑");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "建筑升级");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "building", "建筑升级");
    }

    private static long[] nextBuilding(TownDevCatalog cat, DevState s, java.util.Set<Long> path) {
        List<Long> ids = new ArrayList<>(cat.buildings.keySet());
        ids.sort(Long::compare);
        for (long bid : ids) {
            if (s.buildingLevels.getOrDefault(bid, 0) <= 0) continue;
            long[] r = ensureBuilding(cat, bid, s, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureBuilding(TownDevCatalog cat, long bid, DevState s, java.util.Set<Long> path) {
        TownDevCatalog.Building b = cat.buildings.get(bid);
        if (b == null) return null;
        if (!path.add(bid)) return null;
        try {
            int cur = s.buildingLevels.getOrDefault(bid, 0);
            if (cur >= b.maxLevel) return null;
            if (s.busyBuildings.contains(bid)) return null;
            int next = cur + 1;
            TownDevCatalog.BuildingLevel lv = b.level(next);
            if (lv == null) return null;
            for (long[] pre : lv.prerequisites) {
                int preLv = s.buildingLevels.getOrDefault(pre[0], 0);
                if (preLv < pre[1]) {
                    long[] r = ensureBuilding(cat, pre[0], s, path);
                    if (r != null) return r;
                }
            }
            return new long[]{bid, next};
        } finally {
            path.remove(bid);
        }
    }
}
