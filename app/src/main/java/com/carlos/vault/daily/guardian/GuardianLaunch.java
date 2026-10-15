package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.ExecUtil;
import com.carlos.vault.daily.FnParams;
import com.carlos.vault.daily.MarchCoordinator;
import com.carlos.vault.daily.RoleSessionKeeper;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 守护盟友 P3：卡秒驻防发射器。
 *
 * 流程（design §3.2/§5.2/§6.4）：
 *   威胁 QUALIFIED（攻向盟友城 + 阈值内）→
 *     未标定：立即发射（保证先于敌落地），用响应 endtime 标定本城行军时长 D；
 *     已标定：waitUntil(敌到达 − D − Δ) 后发射，验收落地裕量 ∈ [0.5s, 5s]。
 *
 * 约束：同一盟友目标同时只驻一队；全局并发 ≤2；发射经 MarchCoordinator（守门让位语义）。
 * Δ 默认 1500ms（策略 guardian/delta_ms 可调）。
 */
public final class GuardianLaunch {

    private static final int SPEEDUP_CAP = 0;   // P3 不做加速（P5 接入 SnapExecutor）
    private static final long ARRIVE_MARGIN_MIN_MS = 500L;
    private static final long ARRIVE_MARGIN_MAX_MS = 5000L;
    private static final int MAX_CONCURRENT = 2;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile int launchVariant = -1;
    private static volatile Context APP;
    /** targetUid → [我方驻防 marchId, 敌方行军 marchId]。 */
    private static final Map<Long, long[]> INFLIGHT = new ConcurrentHashMap<>();
    /** kid → 已标定行军时长（秒）。 */
    private static final Map<Long, Long> CALIB = new ConcurrentHashMap<>();

    private GuardianLaunch() {
    }

    /** GuardianEngine 检测到 QUALIFIED 威胁时调用（异步执行）。 */
    public static void onThreatQualified(Context app, String fpid, long kid, GuardianEngine.Track t) {
        if (APP == null && app != null) APP = app.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            glog(app, "已有驻防任务在跑，跳过 uid" + t.owner + " 的威胁");
            return;
        }
        final Context c = app.getApplicationContext();
        Thread th = new Thread(() -> {
            try {
                execute(c, fpid, kid, t);
            } catch (Throwable ex) {
                glog(app, "驻防执行异常: " + stack(ex));
            } finally {
                RUNNING.set(false);
            }
        }, "guardian-launch");
        th.setDaemon(true);
        th.start();
    }

    /** 按目标的活动标记：同一目标同时只跑一个驻防任务；不同目标可并行。 */
    private static final ConcurrentHashMap<Long, Boolean> ACTIVE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ACTIVE_ROLES = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static void execute(Context app, String fpid, long kid, GuardianEngine.Track t) {
        Long activeKey = t.targetUid > 0 ? t.targetUid : (long) t.marchId;
        String roleKey = fpid + ":" + kid;
        if (ACTIVE.putIfAbsent(activeKey, Boolean.TRUE) != null) {
            glog(app, "该目标已有驻防任务在跑，跳过（uid" + activeKey + "）");
            return;
        }
        ACTIVE_ROLES.add(roleKey);
        try {
            runLaunch(app, fpid, kid, t);
        } finally {
            ACTIVE.remove(activeKey);
            ACTIVE_ROLES.remove(roleKey);
        }
    }

    /** 该角色（fpid:kid）是否有驻防任务执行中——日常任务让位判定。 */
    public static boolean isActiveForRole(String fpid, long sid) {
        return ACTIVE_ROLES.contains(fpid + ":" + sid);
    }

    private static void runLaunch(Context app, String fpid, long kid, GuardianEngine.Track t) {
        try {
            RoleSessionKeeper.LiveSession f = sessionFor(app, kid);
            if (f == null) { glog(app, "无 " + kid + " 在线会话，无法驻防"); return; }
            WjdrEngine engine = f.engine;

            int gx = t.x, gy = t.y;
            if (gx <= 0) { glog(app, "威胁缺目标坐标，跳过"); return; }

            if (t.targetUid > 0 && INFLIGHT.containsKey(t.targetUid)) {
                glog(app, "该盟友城已有我方驻防在途（uid" + t.targetUid + "），不重复驻防");
                return;
            }
            if (INFLIGHT.size() >= MAX_CONCURRENT) { glog(app, "驻防并发已达上限，跳过"); return; }

            Object lock = null;
            try { lock = MarchCoordinator.forKey(fpid, kid).tryAcquire("guardian", 3000); } catch (Throwable ignored) { }
            glog(app, "资源闸 " + (lock != null ? "已获取" : "未获取（继续执行，守护优先）"));

            // 使馆查询①：目标城使馆状态（A-13）
            try {
                Map<String, Object> q = new LinkedHashMap<>();
                if (t.targetUid > 0) q.put("uid", t.targetUid);
                GameMessage r = engine.request("req_arsenal_league_embassy_help_simple_info", q);
                Map<String, Object> rc = r == null ? null : r.getContent();
                glog(app, "目标使馆查询 code=" + (rc == null ? "null" : String.valueOf(rc.get("code")))
                        + " 内容=" + abbrev(String.valueOf(rc)));
            } catch (Throwable ex) {
                glog(app, "目标使馆查询异常: " + stack(ex));
            }

            long enemyEnd = t.endServerSec;
            long nowS = System.currentTimeMillis() / 1000L;
            Long calibD = CALIB.get(kid);

            // 精卡轮：可中断重估等待——每 300ms 重读敌 ETA，敌加速时即时调整
            if (calibD != null && enemyEnd - nowS > calibD + 3) {
                long deltaMs = deltaMs(app, fpid, kid);
                long deadlineMs = System.currentTimeMillis() + 120_000L;
                glog(app, "精卡等待：敌 ETA " + (enemyEnd - nowS) + "s − D " + calibD + "s − Δ "
                        + deltaMs / 1000L + "s（每 300ms 重估）");
                while (System.currentTimeMillis() < deadlineMs) {
                    GuardianEngine.Track live = GuardianEngine.trackOf(kid, t.marchId);
                    if (live == null) { glog(app, "威胁行军已消失（召回/落地），取消本次驻防发射"); return; }
                    long eta = live.endServerSec - nowS();
                    long fireAt = live.endServerSec - calibD - deltaMs / 1000L;
                    long waitMs = fireAt * 1000L - engine.serverClock().nowServerMs();
                    if (eta <= calibD + deltaMs / 1000L) {
                        glog(app, "敌加速过快（剩 " + eta + "s < D+Δ），立即发射兜底（迟到的驻防仍有价值）");
                        break;
                    }
                    if (waitMs <= 300) break;   // 进入发射窗
                    sleep(300);
                }
            }

            // 编队变体轮试：哪个形状能发就用哪个（code=0 即锁定）
            List<Map<String, Object>> variants = formationVariants(engine);
            if (variants.isEmpty()) {
                glog(app, "编队构建失败（无可用英雄/士兵），推送手动增援告警并放弃");
                alert(app, gx, gy, t, -1);
                return;
            }
            GameMessage ok = null;
            long ourEnd = -1;
            int lastCode = -999;
            for (int vi = 0; vi < variants.size(); vi++) {
                GameMessage rsp = safeLaunch(engine, gx, gy, variants.get(vi), t.targetUid);
                lastCode = codeOf(rsp);
                glog(app, "编队变体" + (vi + 1) + " 发射 code=" + lastCode);
                ourEnd = endtimeOf(rsp);
                if (lastCode == 0 && ourEnd > 0) { ok = rsp; glog(app, "✅驻防编队形态锁定：变体" + (vi + 1)); break; }
            }
            if (ourEnd <= 0) {
                glog(app, "全部编队变体被拒（末码 " + lastCode + "），推送手动增援告警");
                alert(app, gx, gy, t, lastCode);
                return;
            }

            long d = ourEnd - nowS;
            if (calibD == null) {
                CALIB.put(kid, d);
                saveCalib(app, kid, d);
                glog(app, "标定完成 D=" + d + "s");
            }
            // 裕量按"当前真实敌到达"计算（发射过程中敌可能加速，用威胁表最新值）
            GuardianEngine.Track live = GuardianEngine.trackOf(kid, t.marchId);
            long realEnemyEnd = live != null ? live.endServerSec : (enemyEnd > 0 ? enemyEnd : ourEnd);
            long marginMs = (realEnemyEnd - ourEnd) * 1000L;
            boolean inWin = marginMs >= ARRIVE_MARGIN_MIN_MS && marginMs <= ARRIVE_MARGIN_MAX_MS;
            glog(app, "驻防完成：我方到达 " + hhmmss(ourEnd) + "，敌到达 " + hhmmss(realEnemyEnd)
                    + "，先于敌 " + marginMs + "ms → " + (inWin ? "✅卡秒验收通过（0.5~5s 窗）"
                    : marginMs > 0 ? "早于敌 " + marginMs / 1000L + "s（标定轮/提前落位，安全）"
                    : "⚠️晚于敌 " + (-marginMs) + "ms（敌加速过快，已尽力）"));
            if (t.targetUid > 0) INFLIGHT.put(t.targetUid, new long[]{marchIdOf(ok), t.marchId, ourEnd});
        } catch (Throwable ex) {
            glog(app, "驻防执行异常: " + stack(ex));
        }
    }

    /** 编队变体：V1 空闲英雄×3+主力兵 / V2 高等级英雄×3+主力兵 / V3 单英雄+1兵 / V4 1兵（镜像用户成功样本）。 */
    private static List<Map<String, Object>> formationVariants(WjdrEngine engine) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Long, Long> roster = new LinkedHashMap<>();      // heroId → lev
        Map<String, Object> hi = engine.notifyContent("notify_hero_init");
        Object hdata = hi == null ? null : hi.get("data");
        if (hdata instanceof Map) {
            for (Object o : ((Map<?, ?>) hdata).values()) {
                Map<String, Object> m = asMap(o);
                long id = ExecUtil.longOf(m.get("id"));
                long lev = ExecUtil.longOf(m.get("lev"));
                if (id > 0) roster.put(id, lev);
            }
        } else if (hdata instanceof List) {
            for (Object o : (List<?>) hdata) {
                Map<String, Object> m = asMap(o);
                long id = ExecUtil.longOf(m.get("id"));
                long lev = ExecUtil.longOf(m.get("lev"));
                if (id > 0) roster.put(id, lev);
            }
        }
        Map<Long, Long> soldier = new LinkedHashMap<>();
        Map<String, Object> si = engine.notifyContent("notify_soldier_init");
        Object sdata = si == null ? null : si.get("soldier");
        if (sdata instanceof Map) {
            for (Object o : ((Map<?, ?>) sdata).values()) {
                Map<String, Object> m = asMap(o);
                soldier.put(ExecUtil.longOf(m.get("id")), ExecUtil.longOf(m.get("count")));
            }
        } else if (sdata instanceof List) {
            for (Object o : (List<?>) sdata) {
                Map<String, Object> m = asMap(o);
                soldier.put(ExecUtil.longOf(m.get("id")), ExecUtil.longOf(m.get("count")));
            }
        }

        // 忙碌英雄：在途行军编队 + 防守编队
        java.util.Set<Long> busy = new java.util.HashSet<>();
        Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
        for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
            Map<String, Object> squad = asMap(mi.get("squad_list"));
            Object heroesObj = squad == null ? null : squad.get("hero");
            for (Map<String, Object> h : mapsDeep(heroesObj)) busy.add(ExecUtil.longOf(h.get("id")));
        }
        Map<String, Object> arrange = engine.notifyData("notify_battle_hero_arrange_data");
        if (arrange != null) {
            for (Map<String, Object> m : mapsDeep(arrange.get("hero_arranges"))) {
                Object fh = m.get("fight_heros");
                if (fh instanceof List) for (Object h : (List<?>) fh) busy.add(ExecUtil.longOf(h));
            }
        }

        List<Long> idle = new ArrayList<>();
        roster.entrySet().stream()
                .filter(e -> !busy.contains(e.getKey()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> idle.add(e.getKey()));
        List<Long> top = new ArrayList<>();
        roster.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> top.add(e.getKey()));
        List<Long> big = new ArrayList<>();
        soldier.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(1)
                .forEach(e -> big.add(e.getKey()));

        long total = 0;
        for (Long id : big) total += soldier.getOrDefault(id, 0L);
        final long CAP = 100_000L;
        if (total > CAP) {
            double k = (double) CAP / total;
            for (Long id : big) soldier.put(id, (long) (soldier.getOrDefault(id, 0L) * k));
        }

        if (!idle.isEmpty() && !big.isEmpty()) {
            List<Map<String, Object>> heroPairs = new ArrayList<>();
            for (long h : idle) heroPairs.add(ExecUtil.args("k", h, "v", 1L));
            List<Map<String, Object>> soldierPairs = new ArrayList<>();
            for (long id : big) soldierPairs.add(ExecUtil.args("k", id, "v", soldier.getOrDefault(id, 0L)));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("hero_id", heroPairs);
            f.put("soldier", soldierPairs);
            out.add(f);
            glog(APP_F(), "变体1：空闲英雄" + idle + " + 兵" + big);
        }
        if (!top.isEmpty() && !big.isEmpty()) {
            List<Map<String, Object>> heroPairs = new ArrayList<>();
            for (long h : top) heroPairs.add(ExecUtil.args("k", h, "v", 1L));
            List<Map<String, Object>> soldierPairs = new ArrayList<>();
            for (long id : big) soldierPairs.add(ExecUtil.args("k", id, "v", soldier.getOrDefault(id, 0L)));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("hero_id", heroPairs);
            f.put("soldier", soldierPairs);
            out.add(f);
            glog(APP_F(), "变体2：高等级英雄" + top + " + 兵" + big);
        }
        if (!idle.isEmpty() && !big.isEmpty()) {
            List<Map<String, Object>> heroPairs = new ArrayList<>();
            heroPairs.add(ExecUtil.args("k", idle.get(0), "v", 1L));
            List<Map<String, Object>> soldierPairs = new ArrayList<>();
            soldierPairs.add(ExecUtil.args("k", big.get(0), "v", 1L));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("hero_id", heroPairs);
            f.put("soldier", soldierPairs);
            out.add(f);
            glog(APP_F(), "变体3：单英雄+1兵");
        }
        if (!big.isEmpty()) {
            List<Map<String, Object>> soldierPairs = new ArrayList<>();
            soldierPairs.add(ExecUtil.args("k", big.get(0), "v", 1L));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("hero_id", new ArrayList<>());
            f.put("soldier", soldierPairs);
            out.add(f);
            glog(APP_F(), "变体4：1兵纯兵（镜像用户手动成功样本）");
        }
        return out;
    }

    private static Context APP_F() {
        return APP;
    }

    /** 从使馆驻防详情里收集驻扎在外城的部队信息（英雄/兵种 id），用于编队排除。 */
    private static void collectStationedHeroes(Map<String, Object> rc, java.util.Set<Long> out) {
        try {
            if (rc == null) return;
            for (Object v : rc.values()) collectStationedDeep(v, out, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void collectStationedDeep(Object node, java.util.Set<Long> out, int depth) {
        if (node instanceof Map && depth <= 4) {
            for (Object o : ((Map<?, ?>) node).values()) collectStationedDeep(o, out, depth + 1);
        } else if (node instanceof List && depth <= 4) {
            for (Object o : (List<?>) node) collectStationedDeep(o, out, depth + 1);
        }
    }

    /** 自身某行军的当前 endtime（秒）。 */
    private static long selfMarchEnd(WjdrEngine engine, long marchId) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return -1;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return -1;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail.get(k))) {
                    if (ExecUtil.longOf(march.get("id")) == marchId) return ExecUtil.longOf(march.get("endtime"));
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static GameMessage launch(WjdrEngine engine, int x, int y, Map<String, Object> formation, long targetUid) throws Exception {
        // A-2 变体自发现：extra 形状逐试（空 / target_uid / param1），code=0 即锁定
        String[] variants = {"empty", "target_uid", "param1"};
        GameMessage last = null;
        for (int vi = 0; vi < variants.length; vi++) {
            if (launchVariant >= 0 && launchVariant != vi) continue;   // 已锁定拼法
            Map<String, Object> extra = new LinkedHashMap<>();
            if (vi == 1 && targetUid > 0) extra.put("target_uid", targetUid);
            if (vi == 2 && targetUid > 0) extra.put("param1", targetUid);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("type", 5L);
            args.put("endpoint", ExecUtil.point(x, y));
            args.put("formation", formation);
            args.put("extra", extra);
            GameMessage rsp = engine.request("req_world_march", args);
            last = rsp;
            glog(APP, "驻防发射变体" + (vi + 1) + "(" + variants[vi] + ") code=" + codeOf(rsp));
            if (codeOf(rsp) == 0) {
                if (launchVariant != vi) { launchVariant = vi; glog(APP, "✅驻防发射拼法锁定：变体" + (vi + 1) + " ——A-2 实证"); }
                return rsp;
            }
        }
        return last;
    }

    private static int codeOf(GameMessage rsp) {
        try {
            return ExecUtil.codeOf(rsp);
        } catch (Throwable t) {
            return -999;
        }
    }

    private static void alert(Context app, int gx, int gy, GuardianEngine.Track t, int code) {
        try {
            com.carlos.vault.daily.EnemyWatch.alert(app,
                    "🚨盟友城被攻，请手动增援！\n目标：uid" + (t.targetUid > 0 ? t.targetUid : "")
                            + " 城(" + gx + "," + gy + ")\n敌到：约 " + Math.max(0, t.endServerSec - System.currentTimeMillis() / 1000L)
                            + "s 后（自动驻防被拒 code=" + code + "）", true);
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long marchIdOf(GameMessage rsp) {
        try {
            Map<String, Object> c = rsp.getContent();
            for (String k : new String[]{"march_info", "march"}) {
                Object m = c == null ? null : c.get(k);
                if (m instanceof Map) {
                    long id = ExecUtil.longOf(((Map<?, ?>) m).get("id"));
                    if (id > 0) return id;
                }
            }
            return ExecUtil.marchId(rsp);
        } catch (Throwable t) {
            return -1L;
        }
    }


    /** 敌情解除后自动撤回驻防（req_arsenal_league_embassy_repatriation {target_uid}，schema 实证）。 */
    public static void repatriate(Context app, String fpid, long kid, long targetUid) {
        try {
            long[] g = INFLIGHT.remove(targetUid);
            if (g == null) return;
            RoleSessionKeeper.LiveSession f = sessionFor(app, kid);
            if (f == null) return;
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("target_uid", targetUid);
            GameMessage rsp = f.engine.request("req_arsenal_league_embassy_repatriation", args);
            glog(app, "撤回驻防（uid" + targetUid + "）code=" + codeOf(rsp));
        } catch (Throwable t) {
            glog(app, "撤回驻防异常: " + stack(t));
        }
    }

    /** R3 动态同步：敌加速 → 我方在途驻防行军用道具跟进（≤3 发/次，二阶 50%）。 */
    public static void raceAccelerate(Context app, String fpid, long kid, long targetUid, long enemyMarchId, long enemyEndSec) {
        try {
            long[] g = INFLIGHT.get(targetUid);
            if (g == null) return;                       // 该目标暂无我方驻防在途
            RoleSessionKeeper.LiveSession f = sessionFor(app, kid);
            if (f == null) return;
            WjdrEngine engine = f.engine;
            long ourEnd = g.length >= 3 ? g[2] : -1;     // 我方到达（发射响应实证值）
            if (ourEnd <= 0) {
                ourEnd = selfMarchEnd(engine, g[0]);      // 兜底查询
                if (ourEnd <= 0) return;
            }
            if (ourEnd <= enemyEndSec) return;           // 已先于敌，无需加速

            // 选道具：二阶 301901 优先，一阶 301900 兜底
            long itemId = 0;
            Map<String, Object> bp = engine.notifyContent("notify_backpack_init");
            for (Map<String, Object> it : ExecUtil.mapsOf(bp == null ? null : bp.get("data"))) {
                long iid = ExecUtil.longOf(it.get("id"));
                long cnt = ExecUtil.longOf(it.get("count"));
                if (iid == 301901L && cnt > 0) { itemId = iid; break; }
                if (iid == 301900L && cnt > 0 && itemId == 0) itemId = iid;
            }
            if (itemId <= 0) { glog(app, "R3：无加速道具，无法跟进敌加速"); return; }

            int sent = 0;
            long ourEnd2 = ourEnd;
            while (sent < 3 && ourEnd2 > enemyEndSec) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", g[0]);
                args.put("itemid", itemId);
                args.put("owner", String.valueOf(engine.lastUid()));
                GameMessage rsp = engine.request("req_world_march_speedup", args);
                if (ExecUtil.codeOf(rsp) != 0) { glog(app, "R3 加速拒绝 code=" + ExecUtil.codeOf(rsp)); break; }
                long ne = ExecUtil.longOf(rsp.getContent().get("endtime"));
                if (ne <= 0 || ne >= ourEnd2) break;     // 无前移即停
                ourEnd2 = ne;
                sent++;
                g[2] = ne;                                // 回写记录
            }
            if (sent > 0) glog(app, "R3 跟进加速 " + sent + " 发，我方剩余 " + (ourEnd2 - System.currentTimeMillis() / 1000L) + "s vs 敌 " + (enemyEndSec - System.currentTimeMillis() / 1000L) + "s");
        } catch (Throwable t) {
            glog(app, "R3 异常: " + stack(t));
        }
    }

    /** 队列满时召回一条自己的采集行军（最旧优先），返回 marchId。 */
    private static Long oldestOwnGather(WjdrEngine engine) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return null;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return null;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            Long best = null; long bestStart = Long.MAX_VALUE;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail.get(k))) {
                    if (ExecUtil.longOf(march.get("type")) != 4L) continue;
                    long st = ExecUtil.longOf(march.get("state"));
                    if (st != 2L && st != 3L) continue;
                    long id = ExecUtil.longOf(march.get("id"));
                    long startTime = ExecUtil.longOf(march.get("starttime"));
                    if (id > 0 && startTime < bestStart) { bestStart = startTime; best = id; }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static GameMessage safeLaunch(WjdrEngine engine, int x, int y, Map<String, Object> formation, long targetUid) {
        try {
            return launch(engine, x, y, formation, targetUid);
        } catch (Throwable t) {
            glog(APP, "发射异常: " + stack(t));
            return null;
        }
    }

    private static long endtimeOf(GameMessage rsp) {
        try {
            Map<String, Object> c = rsp.getContent();
            // 响应形状实证：{"code":0,"march_info":{...,"endtime":...,"state":2,"type":5,...}}
            for (String k : new String[]{"march_info", "march"}) {
                Object march = c == null ? null : c.get(k);
                if (march instanceof Map) return ExecUtil.longOf(((Map<?, ?>) march).get("endtime"));
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    // ------------------------------------------------------------------ 编队（英雄 3 + 主力兵 40/30/30）

    private static Map<String, Object> buildFormation(WjdrEngine engine) {
        // 英雄必须选"城里空闲"的：err_hero_not_found(301) = 编队里的英雄不在城（随采集队在外）
        // 全量花名册（hero_init） − 在途行军编队里的英雄 → 按等级取 3
        Map<Long, Long> roster = new LinkedHashMap<>();   // heroId → lev
        Map<String, Object> hi = engine.notifyContent("notify_hero_init");
        Object data = hi == null ? null : hi.get("data");
        if (data instanceof Map) {
            for (Object o : ((Map<?, ?>) data).values()) {
                Map<String, Object> m = asMap(o);
                long id = ExecUtil.longOf(m.get("id"));
                long lev = ExecUtil.longOf(m.get("lev"));
                if (id > 0) roster.put(id, lev);
            }
        } else if (data instanceof List) {
            for (Object o : (List<?>) data) {
                Map<String, Object> m = asMap(o);
                long id = ExecUtil.longOf(m.get("id"));
                long lev = ExecUtil.longOf(m.get("lev"));
                if (id > 0) roster.put(id, lev);
            }
        }
        Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
        java.util.Set<Long> busy = new java.util.HashSet<>();
        for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
            Map<String, Object> squad = asMap(mi.get("squad_list"));
            Object heroesObj = squad == null ? null : squad.get("hero");
            for (Map<String, Object> h : mapsDeep(heroesObj)) {
                long id = ExecUtil.longOf(h.get("id"));
                if (id > 0) busy.add(id);
            }
        }
        Map<String, Object> arrange = engine.notifyData("notify_battle_hero_arrange_data");
        if (arrange != null) {
            for (Map<String, Object> m : mapsDeep(arrange.get("hero_arranges"))) {
                Object fh = m.get("fight_heros");
                if (fh instanceof List) {
                    for (Object h : (List<?>) fh) {
                        long id = ExecUtil.longOf(h);
                        if (id > 0) busy.add(id);   // 防守编队英雄保守起见也视为占用
                    }
                }
            }
        }
        List<Long> heroes = new ArrayList<>();
        roster.entrySet().stream()
                .filter(e -> !busy.contains(e.getKey()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> heroes.add(e.getKey()));
        glog(APP, "英雄诊断：花名册 " + roster.size() + "，在途占用 " + busy.size()
                + "，可用空闲 " + heroes.size() + "（选中 " + heroes + "）");
        if (heroes.isEmpty()) {
            // 兜底：花名册按等级取 3（忙闲未知时先试）
            roster.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .forEach(e -> heroes.add(e.getKey()));
            glog(APP, "空闲英雄为空，兜底用高等级英雄 " + heroes);
        }
        if (heroes.isEmpty()) return null;

        Map<Long, Long> soldier = new LinkedHashMap<>();
        Map<String, Object> si = engine.notifyContent("notify_soldier_init");
        Object sdata = si == null ? null : si.get("soldier");
        if (sdata instanceof Map) {
            for (Object o : ((Map<?, ?>) sdata).values()) {
                Map<String, Object> m = asMap(o);
                soldier.put(ExecUtil.longOf(m.get("id")), ExecUtil.longOf(m.get("count")));
            }
        } else if (sdata instanceof List) {
            for (Object o : (List<?>) sdata) {
                Map<String, Object> m = asMap(o);
                soldier.put(ExecUtil.longOf(m.get("id")), ExecUtil.longOf(m.get("count")));
            }
        }
        List<Long> top = new ArrayList<>();
        soldier.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> top.add(e.getKey()));
        long total = 0;
        for (Long id : top) total += soldier.getOrDefault(id, 0L);
        // 援军负载上限：单次驻防总量封顶 10 万（实际成功样本 1 兵~40 万级；过量可能被拒）
        final long CAP = 100_000L;
        if (total > CAP) {
            double k = (double) CAP / total;
            for (Long id : top) soldier.put(id, (long) (soldier.getOrDefault(id, 0L) * k));
            total = CAP;
        }
        if (total == 0) { // 保底：任意最大堆 1 兵（用户实证：不带兵不能出征）
            Long bid = null; long bc = 0;
            for (Map.Entry<Long, Long> e : soldier.entrySet()) {
                if (e.getValue() > bc) { bc = e.getValue(); bid = e.getKey(); }
            }
            if (bid != null) top.add(bid);
            total = bc;
        }

        List<Map<String, Object>> heroPairs = new ArrayList<>();
        for (long h : heroes) heroPairs.add(ExecUtil.args("k", h, "v", 1L));
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        if (total > 0 && top.size() >= 3) {
            long[] want = {total * 4 / 10, total * 3 / 10, total - total * 4 / 10 - total * 3 / 10};
            for (int i = 0; i < 3; i++) {
                if (want[i] > 0) soldierPairs.add(ExecUtil.args("k", top.get(i), "v", want[i]));
            }
        } else if (total > 0 && !top.isEmpty()) {
            soldierPairs.add(ExecUtil.args("k", top.get(0), "v", soldier.getOrDefault(top.get(0), 1L)));
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", heroPairs);
        f.put("soldier", soldierPairs);
        return f;
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapsDeep(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) if (o instanceof Map) out.add((Map<String, Object>) o);
        } else if (v instanceof Map) {
            for (Object o : ((Map<?, ?>) v).values()) if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static RoleSessionKeeper.LiveSession sessionFor(Context ctx, long kid) {
        try {
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                if (f.sid == kid && f.engine != null
                        && WjdrEngine.STATE_ONLINE.equals(f.engine.sessionState())) return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long deltaMs(Context app, String fpid, long kid) {
        try {
            return Long.parseLong(FnParams.strategy(app, fpid, kid, GuardianEngine.FN_ID, "delta_ms", "1500"));
        } catch (Throwable t) {
            return 1500L;
        }
    }

    private static void saveCalib(Context app, long kid, long dSec) {
        try {
            Context c = app.getApplicationContext();
            java.io.File f = new java.io.File(c.getFilesDir(), "guardian_calib.txt");
            java.util.LinkedHashMap<Long, Long> m = new java.util.LinkedHashMap<>();
            if (f.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String[] p2 = line.trim().split(",");
                    if (p2.length == 2) m.put(Long.parseLong(p2[0]), Long.parseLong(p2[1]));
                }
            }
            m.put(kid, dSec);
            try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
                for (Map.Entry<Long, Long> e : m.entrySet()) w.write(e.getKey() + "," + e.getValue() + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    static void glog(Context c, String line) {
        try {
            java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(), "guardian_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(new java.util.Date()) + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String hhmmss(long serverSec) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(serverSec * 1000L));
    }

    private static String stack(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
