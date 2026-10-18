package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 科技研究 + 建筑升级执行器（规划算法移植自网页版 ResearchAutomationPlanner / BuildingUpgradePlanner）。
 * - 科技：按路线（休闲/战狂）ensure 递归补前置，每次发一级
 * - 建筑：沿前置链找最低层可升对象（前置最低优先），队列空闲才升
 */
final class TownDevTasks {

    private TownDevTasks() {
    }

    // ================= 状态解析 =================

    static class DevState {
        final Map<Long, Long> techLevels = new HashMap<>();      // tid → lev
        final Map<Long, Integer> buildingLevels = new HashMap<>();// bid → lv
        final Set<Long> busyBuildings = new HashSet<>();
        Long idleResearchQueue;
        final List<Long> idleBuildQueues = new ArrayList<>();

        boolean ready;
    }

    static DevState snapshot(WjdrEngine engine) {
        DevState s = new DevState();
        Map<String, Object> ar = engine.notifyData("notify_all_research");
        Map<String, Object> si = engine.notifyData("notify_shelter_info");
        Map<String, Object> qi = engine.notifyData("notify_queue_info");
        if (ar == null || si == null || qi == null) return s;
        s.ready = true;
        // 科技已完成
        Object researched = ar.get("researched");
        Map<Long, Long> kv = ExecUtil.kvPairs(researched instanceof Map
                ? ((Map<?, ?>) researched) : null);
        if (!kv.isEmpty()) {
            for (Map.Entry<Long, Long> e : kv.entrySet()) s.techLevels.put(e.getKey(), e.getValue());
        } else {
            for (Map<String, Object> m : ExecUtil.mapsOf(researched)) {
                long tid = ExecUtil.longOf(m.get("tid"));
                if (tid > 0) s.techLevels.put(tid, ExecUtil.longOf(m.get("lev")));
            }
        }
        // 建筑
        Object bi = si.get("building_info");
        if (bi instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) bi).entrySet()) {
                absorbBuilding(s, e.getValue(), ExecUtil.longOf(e.getKey()));
            }
        } else {
            for (Object o : ExecUtil.listOf(bi) != null ? ExecUtil.listOf(bi) : new ArrayList<>()) {
                absorbBuilding(s, o, 0);
            }
        }
        // 队列
        Object qm = qi.get("queue_map");
        List<Map<String, Object>> queues = new ArrayList<>();
        if (qm instanceof Map) {
            for (Object v : ((Map<?, ?>) qm).values()) {
                Map<String, Object> mm = ExecUtil.mapOf(v);
                if (mm != null) queues.add(mm);
            }
        } else {
            queues = ExecUtil.mapsOf(qm);
        }
        for (Map<String, Object> q : queues) {
            long qtype = ExecUtil.longOf(ExecUtil.mapOf(q.get("base")) != null
                    ? ExecUtil.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
            long status = ExecUtil.longOf(q.get("status"));
            long end = ExecUtil.longOf(q.get("end_time"));
            long qid = ExecUtil.longOf(q.get("qid"));
            boolean busy = status != 1 && end > 0;
            if (busy) continue;
            if (qtype == 1 && qid > 0) s.idleBuildQueues.add(qid);
            if (qtype == 2 && qid > 0 && s.idleResearchQueue == null) s.idleResearchQueue = qid;
        }
        // 队列倒计时状态行（building.auto / research.auto）
        try {
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            if (s.ready) {
                Map<String, Object> qi2 = engine.notifyContent("notify_queue_info");
                Object qm2 = ExecUtil.mapOf(qi2.get("data")) != null
                        ? ExecUtil.mapOf(qi2.get("data")).get("queue_map") : qi2.get("queue_map");
                java.util.List<Map<String, Object>> qs = new java.util.ArrayList<>();
                if (qm2 instanceof Map) {
                    for (Object v2 : ((Map<?, ?>) qm2).values()) {
                        Map<String, Object> mm = ExecUtil.mapOf(v2);
                        if (mm != null) qs.add(mm);
                    }
                } else {
                    qs = ExecUtil.mapsOf(qm2);
                }
                long bEnd = 0, rEnd = 0;
                for (Map<String, Object> q : qs) {
                    long qtype = ExecUtil.longOf(ExecUtil.mapOf(q.get("base")) != null
                            ? ExecUtil.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
                    long end = ExecUtil.longOf(q.get("end_time"));
                    if (end <= System.currentTimeMillis() / 1000L) continue;
                    if (qtype == 1 && (bEnd == 0 || end < bEnd)) bEnd = end;
                    if (qtype == 2 && (rEnd == 0 || end < rEnd)) rEnd = end;
                }
                if (bEnd > 0) FnParams.saveModText(engine.appContext(), engine.lastFpid(),
                        engine.lastServerId(), "building", "auto",
                        "施工中 · " + hm.format(new java.util.Date(bEnd * 1000)) + "完成");
                if (rEnd > 0) FnParams.saveModText(engine.appContext(), engine.lastFpid(),
                        engine.lastServerId(), "research", "auto",
                        "研究进行中 · " + hm.format(new java.util.Date(rEnd * 1000)) + "完成");
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static void absorbBuilding(DevState s, Object raw, long fallbackBid) {
        Map<String, Object> entry = ExecUtil.mapOf(raw);
        if (entry == null) return;
        long bid = ExecUtil.longOf(entry.get("bid"));
        if (bid <= 0) bid = fallbackBid;
        if (bid <= 0) return;
        Map<String, Object> base = ExecUtil.mapOf(entry.get("base"));
        int lv = base != null ? ExecUtil.intOf(base.get("lv")) : ExecUtil.intOf(entry.get("lv"));
        long status = base != null ? ExecUtil.longOf(base.get("status")) : ExecUtil.longOf(entry.get("status"));
        long qid = ExecUtil.longOf(entry.get("qid"));
        if (lv > 0) s.buildingLevels.put(bid, lv);
        if (status != 0 || qid > 0) s.busyBuildings.add(bid);
    }

    // ================= 科技研究 =================

    static final String RESEARCH_ID = "research", RESEARCH_NAME = "科技研究";

    static DailyResult research(WjdrEngine engine) {
        Context ctx = engine.appContext();
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 子开关门控：auto=路线自动研究（help=研究联盟求助，下面单独判）
            if (!FnParams.subOn(ctx, currentFpid(engine), currentSid(engine), "research", "auto", true)) {
                m.skip("子开关「路线自动研究」已关闭");
                return result(m, RESEARCH_ID, RESEARCH_NAME);
            }
            engine.collectNotifies(2500);
            DevState s = snapshot(engine);
            if (!s.ready) {
                m.skip("科技/建筑/队列数据未同步");
                return result(m, RESEARCH_ID, RESEARCH_NAME);
            }
            if (s.idleResearchQueue == null) {
                m.skip("研究队列忙");
                return result(m, RESEARCH_ID, RESEARCH_NAME);
            }
            String profile = FnParams.params(ctx, currentFpid(engine), currentSid(engine), "research")
                    .optString("profile", "casual");
            List<TownDevCatalog.Goal> goals = TownDevCatalog.route(ctx, profile);
            TownDevCatalog cat = TownDevCatalog.get(ctx);
            long[] action = nextTech(cat, goals, s.techLevels, s.buildingLevels, new HashSet<>());
            if (action == null) {
                m.skip("路线内科技已全部达标或被前置阻塞");
                return result(m, RESEARCH_ID, RESEARCH_NAME);
            }
            long tid = action[0];
            int toLevel = (int) action[1];
            TownDevCatalog.Tech t = cat.techs.get(tid);
            long encoded = tid * 10 + toLevel;
            GameMessage rsp = engine.request("req_begin_research", ExecUtil.args(
                    "tid_lev", encoded, "qid", s.idleResearchQueue));
            int code = ExecUtil.codeOf(rsp);
            if (ExecUtil.ok(rsp)) {
                m.claimed("研究 " + (t == null ? tid : t.name) + " → Lv" + toLevel);
                if (FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "research", "help", true)) {
                    GameMessage help = engine.request("req_queue_ask_for_help",
                            ExecUtil.args("qid", s.idleResearchQueue));
                    if (!ExecUtil.ok(help)) m.skip("联盟求助不可用");
                }
            } else if (code == 51) {
                m.skip("研究资源不足");
            } else if (code == 4111) {
                m.skip("科研所正在升级");
            } else if (code == 806) {
                m.skip("前置科技/建筑等级不足");
            } else if (code == 4104) {
                m.skip("科技目标已满级或当前不可研究（码4104）");
            } else {
                m.fail("研究码" + code);
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "科技研究");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, RESEARCH_ID, RESEARCH_NAME);
    }

    /** ensure 递归：返回 {tid, toLevel}，被建筑/满级阻塞返回 null。 */
    private static long[] nextTech(TownDevCatalog cat, List<TownDevCatalog.Goal> goals,
                                   Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                   Set<Long> path) {
        for (TownDevCatalog.Goal g : goals) {
            long[] r = ensureTech(cat, g.tid, g.targetLevel, levels, buildingLevels, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureTech(TownDevCatalog cat, long tid, int requiredLevel,
                                     Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                     Set<Long> path) {
        long cur = levels.getOrDefault(tid, 0L);
        if (cur >= requiredLevel) return null;
        if (!path.add(tid)) return null;   // 循环引用
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
                    return null;   // 被建筑等级阻塞（不自动升建筑，由建筑任务管）
                }
            }
        }
        path.remove(tid);
        return new long[]{tid, next};
    }

    // ================= 建筑升级（前置最低优先） =================

    static final String BUILDING_ID = "building", BUILDING_NAME = "建筑升级";

    static DailyResult building(WjdrEngine engine) {
        Context ctx = engine.appContext();
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 子开关门控：auto=前置最低自动升级（help=建造联盟求助，下面单独判）
            if (!FnParams.subOn(ctx, currentFpid(engine), currentSid(engine), "building", "auto", true)) {
                m.skip("子开关「前置最低自动升级」已关闭");
                return result(m, BUILDING_ID, BUILDING_NAME);
            }
            engine.collectNotifies(2500);
            DevState s = snapshot(engine);
            if (!s.ready) {
                m.skip("建筑/队列数据未同步");
                return result(m, BUILDING_ID, BUILDING_NAME);
            }
            int maxActions = FnParams.params(ctx, currentFpid(engine), currentSid(engine), "building")
                    .optInt("max_actions", 2);
            TownDevCatalog cat = TownDevCatalog.get(ctx);
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
                GameMessage rsp = engine.request("req_upgrade_building", ExecUtil.args(
                        "bid", bid, "use_diamond", false, "qid", qid));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) {
                    done++;
                    s.buildingLevels.put(bid, toLevel);
                    s.busyBuildings.add(bid);
                    m.claimed((b == null ? bid : b.name) + "→Lv" + toLevel);
                    if (FnParams.subOn(engine.appContext(),
                            engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                            "building", "help", true)) {
                        GameMessage help = engine.request("req_queue_ask_for_help",
                                ExecUtil.args("qid", qid));
                        if (!ExecUtil.ok(help)) m.skip("求助不可用");
                    }
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
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "建筑升级");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, BUILDING_ID, BUILDING_NAME);
    }

    /** 前置链递归：优先升"最底层被依赖"的建筑。返回 {bid, toLevel}。 */
    private static long[] nextBuilding(TownDevCatalog cat, DevState s, Set<Long> path) {
        List<Long> ids = new ArrayList<>(cat.buildings.keySet());
        ids.sort(Long::compare);
        for (long bid : ids) {
            if (s.buildingLevels.getOrDefault(bid, 0) <= 0) continue;   // 未建成不自动新建
            long[] r = ensureBuilding(cat, bid, s, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureBuilding(TownDevCatalog cat, long bid, DevState s, Set<Long> path) {
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
                    if (r != null) return r;   // 先升最低层前置
                }
            }
            return new long[]{bid, next};
        } finally {
            path.remove(bid);
        }
    }

    // ================= 杂项 =================

    private static DailyResult result(ExecUtil.Multi m, String id, String name) {
        DailyResult r = new DailyResult(id, name, m.cat(), m.note() + " " + m.sb);
        r.subResults.putAll(m.subResults);
        return r;
    }

    private static String currentFpid(WjdrEngine engine) {
        return engine.lastFpid() == null ? "" : engine.lastFpid();
    }

    private static long currentSid(WjdrEngine engine) {
        return engine.lastServerId();
    }
}
