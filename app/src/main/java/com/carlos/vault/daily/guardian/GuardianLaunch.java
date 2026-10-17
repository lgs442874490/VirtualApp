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

    /** 守护并发上限（设计 §5.2：最多 2 个驻防任务同时执行；替代旧全局单飞锁——
     *  04:22 双攻击实测：精卡等待最长 120s 期间第二个威胁被"已有驻防任务在跑"整单丢弃）。 */
    private static final java.util.concurrent.Semaphore LAUNCH_SLOTS = new java.util.concurrent.Semaphore(2);
    /** 已确认不在城里的英雄（301 实证/随驻防出征）——编队构建时排除，防反复烧 301。 */
    private static final java.util.Set<Long> HEROES_AWAY = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** marchId → 该驻防带出去的英雄（行军结束归还后从 HEROES_AWAY 释放）。 */
    private static final Map<Long, java.util.List<Long>> MARCH_HEROES = new ConcurrentHashMap<>();
    private static volatile int launchVariant = -1;
    private static volatile Context APP;
    /** targetUid → [marchId, 敌方行军 marchId, 我方到达秒, 行军状态(2在途/3驻扎/0已结束), 已加速标记]。
     *  20:51/21:08 教训：行军回家后记录不清会拿死 marchId 发加速（服务器静默超时），状态必须随推送维护。 */
    private static final Map<Long, long[]> INFLIGHT = new ConcurrentHashMap<>();
    /** kid → 已标定行军时长（秒）。 */
    private static final Map<Long, Long> CALIB = new ConcurrentHashMap<>();

    private GuardianLaunch() {
    }

    /** GuardianEngine 检测到 QUALIFIED 威胁时调用（异步执行）。 */
    public static void onThreatQualified(Context app, String fpid, long kid, GuardianEngine.Track t) {
        if (APP == null && app != null) APP = app.getApplicationContext();
        if (!LAUNCH_SLOTS.tryAcquire()) {
            glog(app, "驻防并发已达上限(2)，跳过 uid" + t.owner + " 的威胁（敌若再加速会重新触发）");
            return;
        }
        final Context c = app.getApplicationContext();
        Thread th = new Thread(() -> {
            try {
                execute(c, fpid, kid, t);
            } catch (Throwable ex) {
                glog(app, "驻防执行异常: " + stack(ex));
            } finally {
                LAUNCH_SLOTS.release();
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

    /** 启动时恢复已标定行军时长（saveCalib 写 guardian_calib.txt，此前只有写没有读）。 */
    private static void loadCalib(Context app) {
        if (!CALIB.isEmpty()) return;
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), "guardian_calib.txt");
            if (!f.exists()) return;
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                String[] p2 = line.trim().split(",");
                if (p2.length == 2) CALIB.put(Long.parseLong(p2[0]), Long.parseLong(p2[1]));
            }
            if (!CALIB.isEmpty()) glog(app, "已恢复行军标定表 " + CALIB.size() + " 条");
        } catch (Throwable ignored) {
        }
    }

    private static void runLaunch(Context app, String fpid, long kid, GuardianEngine.Track t) {
        try {
            loadCalib(app);
            RoleSessionKeeper.LiveSession f = sessionFor(app, fpid, kid);
            if (f == null) {
                // 04:32 教训：上一次 R3 超时断连的退避窗口（8~16s）会把引擎晾在 RECOVERING——
                // 攻击还有 100s+ 余量，等恢复而不是放弃
                glog(app, "无在线会话（可能在退避重连中），等待最多 60s");
                long deadline = System.currentTimeMillis() + 60_000L;
                while (System.currentTimeMillis() < deadline) {
                    GuardianEngine.Track live0 = GuardianEngine.trackOf(kid, t.marchId);
                    if (live0 == null) { glog(app, "等待会话期间威胁已解除，取消驻防"); return; }
                    sleep(2000);
                    f = sessionFor(app, fpid, kid);
                    if (f != null) break;
                }
                if (f == null) { glog(app, "60s 内会话未恢复，放弃本次驻防"); return; }
                glog(app, "会话已恢复，继续驻防流程");
            }
            WjdrEngine engine = f.engine;

            int gx = t.x, gy = t.y;
            if (gx <= 0) { glog(app, "威胁缺目标坐标，跳过"); return; }

            long[] existing = t.targetUid > 0 ? INFLIGHT.get(t.targetUid) : null;
            if (existing != null && existing[3] != 0) {   // 3=驻扎也拦（2814 已实证同目标重复援助被拒）
                glog(app, "该盟友城已有我方驻防（uid" + t.targetUid + " 状态" + existing[3] + "），不重复驻防");
                return;
            }
            if (existing != null) INFLIGHT.remove(t.targetUid);   // 已结束的过期记录，清掉再上
            if (INFLIGHT.size() >= MAX_CONCURRENT) { glog(app, "驻防并发已达上限，跳过"); return; }

            Object lock = null;
            try { lock = MarchCoordinator.forKey(fpid, kid).tryAcquire("guardian", 3000); } catch (Throwable ignored) { }
            glog(app, "资源闸 " + (lock != null ? "已获取" : "未获取（继续执行，守护优先）"));

            // 使馆查询①：目标城使馆状态（A-13）
            // 协议族考证（20261015 schema dump）：普通联盟=req_embassy_help_simple_info(10101)，
            // req_arsenal_league_embassy_*（17223）是"兵工厂联盟"模式专属——9001 之谜即模式不符
            try {
                Map<String, Object> q = new LinkedHashMap<>();
                if (t.targetUid > 0) q.put("uid", t.targetUid);
                GameMessage r = null;
                int code = -999;
                for (String proto : new String[]{"req_embassy_help_simple_info",
                        "req_arsenal_league_embassy_help_simple_info"}) {
                    try {
                        r = engine.request(proto, q, 5);
                        code = ExecUtil.codeOf(r);
                        glog(app, proto + " code=" + code);
                        if (code == 0) break;
                    } catch (Throwable ex) {
                        glog(app, proto + " 异常: " + stack(ex));
                    }
                }
                Map<String, Object> rc = r == null ? null : r.getContent();
                glog(app, "目标使馆查询 code=" + (code == -999 ? "null" : String.valueOf(code))
                        + " 内容=" + abbrev(String.valueOf(rc)));
                if (code == 1510 || code == 2815) {
                    // 1510=not_in_same_alliance / 2815=help_must_same_alliance：该目标已退盟（23:01 KK 实测）
                    AllyMap.evict(fpid, kid, t.targetUid);
                    glog(app, "目标 uid" + t.targetUid + " 已不在联盟（" + code + "），移出盟友坐标表，放弃驻防");
                    return;
                }
            } catch (Throwable ex) {
                glog(app, "目标使馆查询异常: " + stack(ex));
            }

            long enemyEnd = t.endServerSec;
            long nowS = System.currentTimeMillis() / 1000L;
            Long calibD = CALIB.get(kid);

            // 精卡轮：可中断重估等待——每 300ms 重读敌 ETA，敌加速时即时调整。
            // 21:49 一一矿工败因（差 2s）：连环加速下等"剩<D+Δ"每拖 1s 输 1s → 敌第一次压缩
            // （forceQualified）立即发射（设计 v1.1 PRE_LANDED：宁提前暴露，不可晚到）。
            // EXTREME 锁存者（全压缩惯犯，用户裁决"3min 压到 2s 必须防住"）：检测即发射，
            // 一秒都不等——发射后立即烧加速（见 launch 后 forced boost）。
            boolean extreme = GuardianEngine.extremeLocked(t.owner);
            if (extreme) glog(app, "EXTREME 锁存者 uid" + t.owner + "：检测即发射+立刻加速，跳过精卡等待");
            if (!extreme && calibD != null && enemyEnd - nowS > calibD + 3) {
                long deltaMs = deltaMs(app, fpid, kid);
                long deadlineMs = System.currentTimeMillis() + 120_000L;
                glog(app, "精卡等待：敌 ETA " + (enemyEnd - nowS) + "s − D " + calibD + "s − Δ "
                        + deltaMs / 1000L + "s（每 300ms 重估；敌一压缩立即发射）");
                while (System.currentTimeMillis() < deadlineMs) {
                    GuardianEngine.Track live = GuardianEngine.trackOf(kid, t.marchId);
                    if (live == null) { glog(app, "威胁行军已消失（召回/落地），取消本次驻防发射"); return; }
                    if (live.forceQualified) {
                        glog(app, "敌已开始压缩（PRE_LANDED）：立即发射，抢在后续压缩之前");
                        break;
                    }
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

            // 编队变体轮试：哪个形状能发就用哪个（code=0 即锁定）。
            // 04:22 双攻击实测：301=英雄不在城（随集结/驻防出征）→ 即刻拉黑防重复烧；
            // 2878=队列满 → 召回一条最旧采集让位后重试同变体（手册 §6.10.5 仲裁 P2）
            // 战时召回（设计 E2-lite，03:04 二城实测教训）：确认要发射 → 立即召回全部在途采集，
            // 腾出队列给驻防和可能的下一波攻击（召回免费；off_work 的驻防要飞回家，队列释放有
            // 回程时延，靠提前撤来不及——采集现在撤，下一波攻击时队列就空了）
            recallOwnGathers(app, engine);

            List<Map<String, Object>> variants = formationVariants(engine);
            if (variants.isEmpty()) {
                glog(app, "编队构建失败（无可用英雄/士兵），推送手动增援告警并放弃");
                alert(app, gx, gy, t, -1);
                return;
            }
            GameMessage ok = null;
            long ourEnd = -1;
            int lastCode = -999;
            boolean queueFreed = false;
            int queueRetry = 0;
            boolean teleported = false;
            for (int vi = 0; vi < variants.size(); vi++) {
                // 301 拉黑后的变体跳过：变体列表是发射前构建的，英雄拉黑后同英雄变体直接跳（22:40 实测磨 3s）
                java.util.List<Long> vhs = variantHeroes(variants.get(vi));
                boolean allAway = !vhs.isEmpty();
                for (long h : vhs) if (!HEROES_AWAY.contains(h)) { allAway = false; break; }
                if (allAway) { glog(app, "变体" + (vi + 1) + "英雄全被拉黑，跳过"); continue; }
                GameMessage rsp = safeLaunch(engine, gx, gy, variants.get(vi), t.targetUid);
                lastCode = codeOf(rsp);
                glog(app, "编队变体" + (vi + 1) + " 发射 code=" + lastCode);
                ourEnd = endtimeOf(rsp);
                if (lastCode == 301) {
                    if (!vhs.isEmpty()) { HEROES_AWAY.addAll(vhs); glog(app, "英雄不在城，拉黑重试：" + vhs); }
                }
                if (lastCode == 0 && ourEnd > 0) {
                    ok = rsp;
                    if (!vhs.isEmpty()) {
                        HEROES_AWAY.addAll(vhs);
                        MARCH_HEROES.put(marchIdOf(ok), vhs);
                    }
                    glog(app, "✅驻防编队形态锁定：变体" + (vi + 1));
                    break;
                }
                // 队列满 2878：持续重试到敌落地（03:04 教训：off_work 撤防后部队要飞回家，队列
                // 释放有回程时延，一次性 1.5s 重试必失败；敌方压缩窗内每 2s 再试，回程到家即成）
                if (lastCode == 2815) {
                    // 2815=help_must_same_alliance：目标已退盟（使馆查询未拦到的兜底路径）
                    AllyMap.evict(fpid, kid, t.targetUid);
                    glog(app, "目标 uid" + t.targetUid + " 已不在联盟（2815），移出盟友坐标表，放弃驻防");
                    return;
                }
                if (lastCode == 2878) {
                    if (!queueFreed) {
                        queueFreed = true;
                        Long freeId = weakOwnGarrison(engine, t.targetUid);
                        if (freeId == null) freeId = anyWeakOwnGarrison(engine, t.targetUid);
                        if (freeId != null) {
                            try {
                                Map<String, Object> a = new LinkedHashMap<>();
                                a.put("id", freeId);
                                GameMessage r = engine.request("req_world_march_off_work", a, 8);
                                glog(app, "队列满(2878)：撤自家弱驻防行军" + freeId + " 腾位（等回程释放）code=" + ExecUtil.codeOf(r));
                            } catch (Throwable ex) {
                                glog(app, "弱驻防腾位异常: " + stack(ex));
                            }
                        } else {
                            Long gid = oldestOwnGather(engine);
                            if (gid != null) {
                                try {
                                    Map<String, Object> ra = new LinkedHashMap<>();
                                    ra.put("id", gid);
                                    GameMessage rr = engine.request("req_world_march_recall", ra, 8);
                                    glog(app, "队列满(2878)：召回最旧采集行军" + gid + " 让位 code=" + ExecUtil.codeOf(rr));
                                } catch (Throwable ex) {
                                    glog(app, "队列满让位召回异常: " + stack(ex));
                                }
                            }
                        }
                    }
                    // L2 高级迁城一键释队（手册 §6/设计 §6.10.5 P3，用户 20261017 重申）：
                    // 撤防释放有回程时延等不及 且 无守护在途（迁城会中断在途驻防）→ 原地(+1,0)
                    // 迁城，全 6 队瞬间回城（F4）。每次发射只迁一次。
                    if (queueRetry >= 1 && !teleported) {
                        boolean guardInFlight = false;
                        for (long[] g : INFLIGHT.values()) {
                            if (g.length > 3 && g[3] == 2L) { guardInFlight = true; break; }
                        }
                        Map<String, Object> wpiT = engine.notifyContent("notify_world_player_init");
                        for (Map<String, Object> mi : mapsDeep(wpiT == null ? null : wpiT.get("march_info"))) {
                            long ty = ExecUtil.longOf(mi.get("type"));
                            long st = ExecUtil.longOf(mi.get("state"));
                            if (ty == 5L && st == 2L) { guardInFlight = true; break; }
                        }
                        if (!guardInFlight) {
                            teleported = true;
                            try {
                                Map<String, Object> cp = ExecUtil.mapOf(wpiT == null ? null : wpiT.get("city_point"));
                                long cx = ExecUtil.longOf(cp.get("x")), cy = ExecUtil.longOf(cp.get("y"));
                                if (cx > 0) {
                                    // 19:05 实测 +1 格 2836（err_world_can_not_move_city，格被占/不可迁）→
                                    // 四方位轮试，首个 code=0 即成
                                    int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                                    boolean moved = false;
                                    for (int[] off : offs) {
                                        Map<String, Object> ma = new LinkedHashMap<>();
                                        ma.put("point", ExecUtil.point((int) (cx + off[0]), (int) (cy + off[1])));
                                        GameMessage mr = engine.request("req_world_move_city", ma, 8);
                                        int mc = ExecUtil.codeOf(mr);
                                        glog(app, "L2 迁城释队：迁至(" + (cx + off[0]) + "," + (cy + off[1])
                                                + ") code=" + mc);
                                        if (mc == 0) { moved = true; break; }
                                    }
                                    if (moved) sleep(3000);   // 等服务器处理 6 队回收
                                }
                            } catch (Throwable ex) {
                                glog(app, "迁城释队异常: " + stack(ex));
                            }
                        } else {
                            glog(app, "L2 迁城释队跳过：有守护驻防在途（迁城会中断，先等其落地）");
                        }
                    }
                    // 等队列释放再重试（敌还在途就继续等；威胁消失/超 30 次则终止）
                    sleep(2000);
                    GuardianEngine.Track liveT = GuardianEngine.trackOf(kid, t.marchId);
                    if (liveT == null) { glog(app, "威胁已解除，停止队列等待"); return; }
                    if (++queueRetry >= 30) { glog(app, "队列等待超 60s 未腾出，推送手动增援告警"); alert(app, gx, gy, t, lastCode); return; }
                    vi--;   // 重试同变体
                    continue;
                }
                if (lastCode == 2814 && !queueFreed) {
                    // §7.6 replace_own：城里的我方旧驻防若是测试级弱兵（<5000），撤掉腾位换新
                    Long weak = weakOwnGarrison(engine, t.targetUid);
                    if (weak != null) {
                        queueFreed = true;
                        try {
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("id", weak);
                            GameMessage r = engine.request("req_world_march_off_work", a, 8);
                            glog(app, "2814：我方旧驻防行军" + weak + " 兵力过弱，撤掉换新 code=" + ExecUtil.codeOf(r));
                        } catch (Throwable ex) {
                            glog(app, "弱驻防撤回异常: " + stack(ex));
                        }
                        sleep(1500);
                        vi--;   // 重试同一变体
                        continue;
                    }
                }
            }
            if (ourEnd <= 0) {
                if (lastCode == 2814) {
                    // 该城已有我方驻防覆盖（服务器拒绝重复援助）——非失败，不告警
                    glog(app, "该盟友城已有我方驻防覆盖（2814），无需新增；存量不足请手动增援");
                    return;
                }
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
            // 验收窗仅参考：成败以战报邮件为准（用户 20261017 裁决）
            glog(app, "驻防完成：我方到达 " + hhmmss(ourEnd) + "，敌到达 " + hhmmss(realEnemyEnd)
                    + "，先于敌 " + marginMs / 1000L + "s 落地——仅代表先至，**成败以战报邮件为准**（"
                    + (marginMs > 0 ? "已先至，等待🚩/❌邮件" : "晚于敌，预计❌") + "）");
            if (t.targetUid > 0) {
                INFLIGHT.put(t.targetUid, new long[]{marchIdOf(ok), t.marchId, ourEnd, 2L, 0L});
                // EXTREME 锁存者：发射即烧加速（等敌动手再加速对全压缩者结构性太晚——
                // 05:16 复盘：检测即发射+立刻 50% → 到达反超敌 4s）
                if (extreme) {
                    glog(app, "EXTREME：发射即烧加速（不等敌压缩）");
                    raceAccelerate(app, fpid, kid, t.targetUid, t.marchId,
                            GuardianEngine.trackOf(kid, t.marchId) != null
                                    ? GuardianEngine.trackOf(kid, t.marchId).endServerSec : enemyEnd,
                            GuardianEngine.trackOf(kid, t.marchId), true);
                }
            }
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
                .filter(e -> !busy.contains(e.getKey()) && !HEROES_AWAY.contains(e.getKey()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> idle.add(e.getKey()));
        List<Long> top = new ArrayList<>();
        roster.entrySet().stream()
                .filter(e -> !HEROES_AWAY.contains(e.getKey()))
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


    /** 敌情解除后自动撤回驻防。撤回条件（用户 20261016 裁决）：敌方召回→立即；敌方落地→等战报后由
     *  repatriateDelayed 调用。协议=req_world_march_off_work {id}（embassy_repatriation 非此用途）。
     *  数据源双路：INFLIGHT 在册（本会话发射）+ 登录快照扫描（跨重启仍有效）。 */
    public static void repatriate(Context app, String fpid, long kid, long targetUid) {
        try {
            RoleSessionKeeper.LiveSession f = sessionFor(app, fpid, kid);
            if (f == null) return;
            WjdrEngine engine = f.engine;
            java.util.List<Long> ids = new ArrayList<>();
            long[] g = INFLIGHT.get(targetUid);
            if (g != null && (g[3] == 3L || g[3] == 2L)) ids.add(g[0]);   // 在册：驻扎或在途都要收
            Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
            for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
                long mid = ExecUtil.longOf(mi.get("id"));
                long ty = ExecUtil.longOf(mi.get("type"));
                long st = ExecUtil.longOf(mi.get("state"));
                Map<String, Object> help = asMap(mi.get("help_city"));
                long ht = help == null ? 0L : ExecUtil.longOf(help.get("target"));
                if (mid > 0 && ty == 5L && (st == 3L || st == 2L) && ht == targetUid && !ids.contains(mid)) ids.add(mid);
            }
            if (ids.isEmpty()) {
                glog(app, "撤防：uid" + targetUid + " 城未发现我方驻扎行军（可能已撤/在途）");
                INFLIGHT.remove(targetUid);
                return;
            }
            for (long mid : ids) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", mid);
                // 在途(2)=召回；驻扎(3)=下工。23:01 孤儿驻防教训：敌召回时我方还在路上，只撤驻扎会漏
                boolean inFlight = g != null && g[0] == mid && g[3] == 2L;
                if (!inFlight) {
                    for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
                        if (ExecUtil.longOf(mi.get("id")) == mid) {
                            inFlight = ExecUtil.longOf(mi.get("state")) == 2L;
                            break;
                        }
                    }
                }
                String proto = inFlight ? "req_world_march_recall" : "req_world_march_off_work";
                GameMessage r = engine.request(proto, a, 8);
                glog(app, "撤防 " + proto.replace("req_world_march_", "") + " 行军" + mid
                        + "（uid" + targetUid + (inFlight ? " 在途召回" : "") + "）code=" + ExecUtil.codeOf(r));
            }
            INFLIGHT.remove(targetUid);
        } catch (Throwable t) {
            glog(app, "撤回驻防异常: " + stack(t));
        }
    }

    /** 敌方落地后的延迟撤防：战报一出即撤（用户裁决）。无战报归因（20261017）：
     *  落地后再等 15s 仍无战斗邮件 → ❌未参战（后落地/敌已打完）；敌召回路径走 repatriate
     *  直调，无战报属正常——威胁未成立，不算防御失败。 */
    public static void repatriateDelayed(Context app, String fpid, long kid, long targetUid, long delayMs) {
        glog(app, "敌方落地：战报出即撤防（" + delayMs / 1000L + "s 后，uid" + targetUid + "）");
        final long landMs = System.currentTimeMillis();
        Thread th = new Thread(() -> {
            sleep(delayMs);
            try {
                repatriate(app, fpid, kid, targetUid);
            } catch (Throwable t) {
                glog(app, "延迟撤防异常: " + stack(t));
            }
            sleep(15_000L);
            long lastMail = com.carlos.vault.daily.guardian.GuardianEngine.lastBattleMailAt();
            if (lastMail < landMs) {
                glog(app, "❌无战报：我方未参战（后落地/敌已打完）→ 本次驻防判定失败（uid" + targetUid + "）");
            }
        }, "guardian-repatriate");
        th.setDaemon(true);
        th.start();
    }

    /**
     * 孤儿驻防清扫（兜底，20261017 用户实测"驻防完不召回队列"）：事件驱动撤防漏掉时由此兜底。
     * 扫描本账号驻扎中(state=3)的 type=5 行军，目标已无活跃威胁 → off_work 撤回释放队列。
     */
    private static final java.util.Set<Long> SWEEP_DEAD =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    public static void sweepOrphans(Context app, String fpid, long kid) {
        try {
            RoleSessionKeeper.LiveSession f = sessionFor(app, fpid, kid);
            if (f == null) return;
            WjdrEngine engine = f.engine;
            Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
            java.util.List<long[]> victims = new ArrayList<>();   // [marchId, targetUid]
            for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
                long mid = ExecUtil.longOf(mi.get("id"));
                long ty = ExecUtil.longOf(mi.get("type"));
                long st = ExecUtil.longOf(mi.get("state"));
                Map<String, Object> help = asMap(mi.get("help_city"));
                long ht = help == null ? 0L : ExecUtil.longOf(help.get("target"));
                if (mid > 0 && ty == 5L && st == 3L) victims.add(new long[]{mid, ht});
            }
            // INFLIGHT 在册但快照没有的（本会话发射、登录快照未刷新）也纳入
            for (Map.Entry<Long, long[]> e : INFLIGHT.entrySet()) {
                long[] g = e.getValue();
                boolean known = false;
                for (long[] v : victims) if (v[0] == g[0]) { known = true; break; }
                if (!known && g.length > 0 && g[0] > 0 && g[3] == 3L) victims.add(new long[]{g[0], e.getKey()});
            }
            int freed = 0;
            for (long[] v : victims) {
                if (GuardianEngine.hasThreatOn(kid, v[1])) continue;   // 目标仍有威胁：继续驻
                if (SWEEP_DEAD.contains(v[0])) continue;   // 2806 已证实行军不存在（过期快照残影）
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", v[0]);
                try {
                    GameMessage r = engine.request("req_world_march_off_work", a, 8);
                    int sc = ExecUtil.codeOf(r);
                    glog(app, "清扫：撤回无威胁驻防 行军" + v[0] + "（uid" + v[1] + "）code=" + sc);
                    if (sc == 0) freed++;
                    if (sc == 2806) SWEEP_DEAD.add(v[0]);   // 行军不存在：记下不再空转
                } catch (Throwable t) {
                    glog(app, "清扫撤回异常 行军" + v[0] + ": " + stack(t));
                }
                INFLIGHT.remove(v[1]);
            }
            if (!victims.isEmpty()) glog(app, "清扫完成：发现 " + victims.size() + " 处驻扎，撤回 " + freed);
        } catch (Throwable t) {
            glog(app, "清扫异常: " + stack(t));
        }
    }

    /** R3 动态同步：敌加速 → 我方在途驻防行军用道具跟进（每条行军限 1 发，二阶 50% 优先）。
     *
     * 实测教训链（20261015）：
     * ① 20:51/21:08 两次"超时"实为对已回家的过期 marchId 发请求（INFLIGHT 未随推送清理）——
     *    现由 onOwnMarchUpdate/onOwnMarchEnded 维护 g[3] 状态，非在途(2)不发起；
     * ② 21:19 联盟版对在途行军静默、世界版因断连未送达——世界版对在途 type=5 仍待实弹定论
     *    （用户实证 21:00 前手动加速驻防第一发成功）；
     * ③ 用户观察"第一发生效、第二发无效"→ 每条行军只发 1 发（g[4] 标记），多发无益；
     * ④ 短超时（4s）失败快，协议间等会话回在线，全败冷却 3min 防断连风暴。 */
    private static final ConcurrentHashMap<String, Long> R3_COOLDOWN = new ConcurrentHashMap<>();
    /** 加速道具库存记账（19:15 教训：登录快照过期，照旧发 301901 → 2827 道具不足）。
     *  首次从快照初始化（高估），每发生效递减，2827 直接清零，钻石购买成功回补。 */
    private static final java.util.concurrent.atomic.AtomicLong T2_STOCK =
            new java.util.concurrent.atomic.AtomicLong(-1L);   // 301901 二阶
    private static final java.util.concurrent.atomic.AtomicLong T1_STOCK =
            new java.util.concurrent.atomic.AtomicLong(-1L);   // 301900 一阶

    private static void refreshStockIfUnknown(WjdrEngine engine) {
        if (T2_STOCK.get() >= 0 && T1_STOCK.get() >= 0) return;
        Map<String, Object> bp = engine.notifyContent("notify_backpack_init");
        for (Map<String, Object> it : ExecUtil.mapsOf(bp == null ? null : bp.get("data"))) {
            long iid = ExecUtil.longOf(it.get("id"));
            long cnt = ExecUtil.longOf(it.get("count"));
            if (iid == 301901L && T2_STOCK.get() < 0) T2_STOCK.set(cnt);
            if (iid == 301900L && T1_STOCK.get() < 0) T1_STOCK.set(cnt);
        }
        if (T2_STOCK.get() < 0) T2_STOCK.set(0);
        if (T1_STOCK.get() < 0) T1_STOCK.set(0);
    }

    public static void raceAccelerate(Context app, String fpid, long kid, long targetUid,
                                      long enemyMarchId, long enemyEndSec,
                                      GuardianEngine.Track track) {
        raceAccelerate(app, fpid, kid, targetUid, enemyMarchId, enemyEndSec, track, false);
    }

    public static void raceAccelerate(Context app, String fpid, long kid, long targetUid,
                                      long enemyMarchId, long enemyEndSec,
                                      GuardianEngine.Track track, boolean force) {
        try {
            long[] g = INFLIGHT.get(targetUid);
            if (g == null) return;                       // 该目标暂无我方驻防
            // 时间判定取代 state 判定（18:20 教训：state=3 推送比实际到达早 17s，把 R3 全拦死）
            long ourEnd0 = g.length >= 3 ? g[2] : -1;
            if (ourEnd0 > 0 && ourEnd0 - nowS() < 4) return;   // 已到达/即将到达
            String ck = kid + ":" + g[0];
            Long coolUntil = R3_COOLDOWN.get(ck);
            long nowMs = System.currentTimeMillis();
            if (coolUntil != null && nowMs < coolUntil) return;   // 冷却期（硬错误才设）
            RoleSessionKeeper.LiveSession f = sessionFor(app, fpid, kid);
            if (f == null) return;
            WjdrEngine engine = f.engine;
            long ourEnd = ourEnd0;
            if (ourEnd <= 0) {
                ourEnd = selfMarchEnd(engine, g[0]);      // 兜底查询
                if (ourEnd <= 0) { INFLIGHT.remove(targetUid); return; }   // 行军已不在，清过期记录
            }
            // ---- 触发判定（用户 20261017 裁决：第一次加速即对等；连压即响应）----
            long aheadBy = enemyEndSec - ourEnd;         // >0 = 我方先至秒数
            boolean burst = track != null && track.compressCount >= 2
                    && System.currentTimeMillis() - track.lastCompressAt < 1500L
                    && aheadBy < 60;                      // 领先过大不响应（防骗道具；敌再压会再触发）
            boolean mirrorFirst = track != null && track.compressCount >= 1 && g[4] == 0;
            boolean behind = aheadBy < 15;
            if (!force && !burst && !behind && !(mirrorFirst && aheadBy < 60)) {
                return;   // 领先充足且无敌压缩迹象：不烧道具（公理②）
            }
            if (ourEnd - nowS() < 3) {
                return;   // 已到达/贴地，无法加速
            }

            // ---- 加速链（19:05 教训：分级选档 + 精准止损；19:15 教训：库存记账 + 2827 换档）----
            int shots = 0;
            long curEnd = ourEnd;
            while (shots < 6) {
                GuardianEngine.Track tk = GuardianEngine.trackOf(kid, enemyMarchId);
                long eEnd = tk != null ? tk.endServerSec : enemyEndSec;
                boolean enemyActiveNow = tk != null
                        && System.currentTimeMillis() - tk.lastCompressAt < 2000L;
                long stopMargin = enemyActiveNow ? 6 : 3;   // 敌压缩中留 6s 缓冲（防下一刀瞬间反超）
                if (curEnd <= eEnd - stopMargin) {
                    if (shots == 0) glog(app, "R3 已先至 " + (eEnd - curEnd) + "s，无需出手");
                    break;   // 精准止损：敌再压缩→新事件→新一轮最小响应，不囤积领先
                }
                if (curEnd - nowS() < 2.5f) break;   // 贴地缓冲
                // 分级选档（用户裁决：能一阶就一阶）+ 库存记账（不信过期快照）
                refreshStockIfUnknown(engine);
                GuardianEngine.Track tkS = GuardianEngine.trackOf(kid, enemyMarchId);
                boolean enemyActive = tkS != null
                        && System.currentTimeMillis() - tkS.lastCompressAt < 2000L;
                // 止损余量自适应：敌还在压缩 → 目标先至 6s（防下一刀瞬间反超）；安静 → 3s
                long margin = enemyActive ? 6 : 3;
                long remainSec = curEnd - nowS();
                boolean tier1Ok = (long) (remainSec * 0.75f) + margin <= (eEnd - nowS());
                long itemId = 0;
                String tierName = "";
                if (tier1Ok && T1_STOCK.get() > 0) { itemId = 301900L; tierName = "一阶25%"; }
                else if (T2_STOCK.get() > 0) { itemId = 301901L; tierName = "二阶50%"; }
                else if (T1_STOCK.get() > 0) { itemId = 301900L; tierName = "一阶25%(仅剩)"; }
                if (itemId <= 0) {
                    Map<String, Object> qb = new LinkedHashMap<>();
                    qb.put("use", false);
                    qb.put("id", 301901L);
                    qb.put("count", 1L);
                    GameMessage brsp = engine.request("req_shop_quick_buy", qb, 8);
                    int bcode = ExecUtil.codeOf(brsp);
                    glog(app, "R3 钻石快捷购买 301901 code=" + bcode);
                    if (bcode == 0) { itemId = 301901L; tierName = "二阶50%(购)"; T2_STOCK.incrementAndGet(); }
                    else { glog(app, "R3：无道具且钻石购买失败（码 " + bcode + "），停止"); return; }
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", g[0]);
                args.put("itemid", itemId);
                args.put("owner", String.valueOf(engine.lastUid()));
                GameMessage rsp = null;
                int code = -999;
                // 世界版为主（联盟版实证永远 9001，删）。18:20 教训：偶发超时（剩余 38s 也超——
                // 非剩余时间问题）→ 等重连后原版重试一次，不再烧 10s 走联盟兜底
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        rsp = engine.request("req_world_march_speedup", args, 4);
                        code = ExecUtil.codeOf(rsp);
                        if (code == 0) break;
                    } catch (Throwable t) {
                        boolean timeout = String.valueOf(t).contains("超时");
                        glog(app, "R3 world 尝试" + (attempt + 1) + " 异常: " + stack(t));
                        code = -999;
                        rsp = null;
                        if (timeout && enemyEndSec - nowS() < 20) break;
                        if (!waitOnline(engine, 12_000L)) break;
                    }
                    if (code > 0) break;
                    sleep(300);
                }
                shots++;
                g[4] = shots;
                if (code == 2827) {
                    // 道具不足（库存记账与服务器不符）：清零该档，换档/购买后下一轮重选
                    if (itemId == 301901L) T2_STOCK.set(0); else T1_STOCK.set(0);
                    glog(app, "R3 " + tierName + "库存不足(2827)，清零记账→换档/购买重试");
                    continue;   // 不冷却——供应链问题不是行军问题
                }
                if (code != 0) {
                    if (code > 0) {
                        glog(app, "R3 第" + shots + "发被拒（码 " + code + "），冷却 60s");
                        R3_COOLDOWN.put(ck, System.currentTimeMillis() + 60_000L);   // 硬错误才冷却
                    } else {
                        glog(app, "R3 第" + shots + "发超时未达（重连后仍无响应），链终止");
                    }
                    break;
                }
                if (itemId == 301901L) T2_STOCK.updateAndGet(v -> Math.max(0, v - 1));
                else T1_STOCK.updateAndGet(v -> Math.max(0, v - 1));
                long ne = ExecUtil.longOf(rsp.getContent().get("endtime"));
                if (ne <= 0 || ne >= curEnd) {
                    glog(app, "R3 第" + shots + "发无前移（触底/上限实证），链终止——到达 "
                            + hhmmss(curEnd) + "（剩 " + (curEnd - nowS()) + "s）");
                    break;
                }
                curEnd = ne;
                g[2] = ne;
                glog(app, "R3 第" + shots + "发[" + tierName + "]生效 ✅ 到达 → " + hhmmss(ne)
                        + "（剩 " + (ne - nowS()) + "s vs 敌剩 " + (eEnd - nowS()) + "s）");
                sleep(200);   // 收手判断在循环顶部统一做（先至 ≥2s 即停）
            }
        } catch (Throwable t) {
            glog(app, "R3 异常: " + stack(t));
        }
    }

    /** 引擎推送喂：我方行军状态变化（march_update.change.state）——维护 INFLIGHT 行军状态。
     *  state 3=落地驻扎（不可再加速，保留记录防重复驻防）；4=回程；2=在途。 */
    public static void onOwnMarchState(long kid, long marchId, long state) {
        for (Map.Entry<Long, long[]> e : INFLIGHT.entrySet()) {
            long[] g = e.getValue();
            if (g.length > 0 && g[0] == marchId) {
                g[3] = state == 3L ? 3L : (state == 4L ? 4L : g[3]);
                return;
            }
        }
    }

    /** 引擎推送喂：我方行军消失（march_del）——清理过期驻防记录与出征英雄（20:51 死 marchId 发加速的根因）。 */
    public static void onOwnMarchEnded(long kid, long marchId) {
        java.util.List<Long> hs = MARCH_HEROES.remove(marchId);
        if (hs != null) HEROES_AWAY.removeAll(hs);
        for (Map.Entry<Long, long[]> e : INFLIGHT.entrySet()) {
            long[] g = e.getValue();
            if (g.length > 0 && g[0] == marchId) {
                INFLIGHT.remove(e.getKey());
                glog(APP, "驻防记录已清理（行军" + marchId + " 结束，uid" + e.getKey() + "）");
                return;
            }
        }
    }

    /** 从登录快照找我方驻扎在 targetUid 城的行军，兵力 <5000 视为测试级弱驻防，返回其 marchId（无则 null）。 */
    private static Long weakOwnGarrison(WjdrEngine engine, long targetUid) {
        try {
            Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
            for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
                long mid = ExecUtil.longOf(mi.get("id"));
                long ty = ExecUtil.longOf(mi.get("type"));
                long st = ExecUtil.longOf(mi.get("state"));
                Map<String, Object> help = asMap(mi.get("help_city"));
                long ht = help == null ? 0L : ExecUtil.longOf(help.get("target"));
                if (mid <= 0 || ty != 5L || st != 3L || ht != targetUid) continue;
                long troops = 0;
                Map<String, Object> squad = asMap(mi.get("squad_list"));
                Object soldiers = squad == null ? null : squad.get("soldier");
                if (soldiers instanceof Map) {
                    for (Object o : ((Map<?, ?>) soldiers).values()) {
                        if (o instanceof Map) troops += ExecUtil.longOf(((Map<?, ?>) o).get("count"));
                    }
                } else if (soldiers instanceof List) {
                    for (Object o : (List<?>) soldiers) {
                        if (o instanceof Map) troops += ExecUtil.longOf(((Map<?, ?>) o).get("count"));
                    }
                }
                glog(app_ctx(), "2814 检查：我方驻防行军" + mid + " 兵力 " + troops);
                if (troops < 5000L) return mid;   // 测试级弱驻防 → 腾位换新
                return null;                       // 有正经驻防 → 不动
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context app_ctx() {
        return APP;
    }

    /** 任意我方弱驻防（<5000 兵，排除 excludeTarget 城），用于队列满时腾位。 */
    private static Long anyWeakOwnGarrison(WjdrEngine engine, long excludeTarget) {
        try {
            Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
            for (Map<String, Object> mi : mapsDeep(wpi == null ? null : wpi.get("march_info"))) {
                long mid = ExecUtil.longOf(mi.get("id"));
                long ty = ExecUtil.longOf(mi.get("type"));
                long st = ExecUtil.longOf(mi.get("state"));
                Map<String, Object> help = asMap(mi.get("help_city"));
                long ht = help == null ? 0L : ExecUtil.longOf(help.get("target"));
                if (mid <= 0 || ty != 5L || st != 3L || ht == excludeTarget || ht <= 0) continue;
                long troops = 0;
                Map<String, Object> squad = asMap(mi.get("squad_list"));
                Object soldiers = squad == null ? null : squad.get("soldier");
                if (soldiers instanceof Map) {
                    for (Object o : ((Map<?, ?>) soldiers).values()) {
                        if (o instanceof Map) troops += ExecUtil.longOf(((Map<?, ?>) o).get("count"));
                    }
                }
                if (troops < 5000L) return mid;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从编队形状里取英雄 id 列表（hero_id[{k,v}]）。 */
    private static java.util.List<Long> variantHeroes(Map<String, Object> formation) {
        java.util.List<Long> out = new ArrayList<>();
        try {
            Object heroes = formation.get("hero_id");
            if (heroes instanceof List) {
                for (Object o : (List<?>) heroes) {
                    if (o instanceof Map) {
                        long id = ExecUtil.longOf(((Map<?, ?>) o).get("k"));
                        if (id > 0) out.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 等会话回到 ONLINE（超时断连后的指数退避重连 ≥8s）。 */
    private static boolean waitOnline(WjdrEngine engine, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) {
                sleep(500);   // 缓冲：刚 ONLINE 的连接先让心跳稳一拍
                return true;
            }
            sleep(300);
        }
        return false;
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

    /** 战时召回（设计 E2，用户 20261017 重申：战争态放弃一切占队日常）：检测到威胁即召回全部
     *  在途/采集中行军（免费、先于开战——开战后召回要烧道具）。每账号 5 分钟去重防抖。 */
    private static final ConcurrentHashMap<String, Long> WAR_RECALL_AT = new ConcurrentHashMap<>();

    public static void wartimeRecall(Context app, String fpid, long kid) {
        try {
            String k = fpid + ":" + kid;
            Long last = WAR_RECALL_AT.get(k);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 300_000L) return;
            WAR_RECALL_AT.put(k, now);
            // 异步执行：召回链有阻塞请求，绝不能卡推送处理线程（handleMarch 调用方）
            final Context c = app.getApplicationContext();
            Thread th = new Thread(() -> {
                try {
                    RoleSessionKeeper.LiveSession f = sessionFor(c, fpid, kid);
                    if (f == null) return;
                    recallOwnGathers(c, f.engine);
                } catch (Throwable t) {
                    glog(c, "战时召回异常: " + stack(t));
                }
            }, "guardian-war-recall");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            glog(app, "战时召回调度异常: " + stack(t));
        }
    }

    /** 战时召回（设计 E2-lite）：确认发射驻防时召回全部在途/采集中行军（免费），
     *  立刻启动回程——队列在回程到家后释放，为驻防与下一波攻击腾位。 */
    private static void recallOwnGathers(Context app, WjdrEngine engine) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args, 5);
            if (rsp == null || rsp.getContent() == null) return;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            java.util.List<Long> ids = new ArrayList<>();
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail.get(k))) {
                    long ty = ExecUtil.longOf(march.get("type"));
                    long st = ExecUtil.longOf(march.get("state"));
                    long id = ExecUtil.longOf(march.get("id"));
                    if (id > 0 && ty == 4L && (st == 2L || st == 3L)) ids.add(id);
                }
                if (!ids.isEmpty()) break;
            }
            for (long id : ids) {
                try {
                    Map<String, Object> ra = new LinkedHashMap<>();
                    ra.put("id", id);
                    GameMessage rr = engine.request("req_world_march_recall", ra, 8);
                    glog(app, "战时召回采集行军" + id + " code=" + ExecUtil.codeOf(rr));
                } catch (Throwable t) {
                    glog(app, "战时召回异常 行军" + id + ": " + stack(t));
                }
            }
            if (!ids.isEmpty()) glog(app, "战时召回完成：" + ids.size() + " 队采集回程中（队列将于到家后释放）");
        } catch (Throwable t) {
            glog(app, "战时召回查询异常: " + stack(t));
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

    /** 找守护账号自己的在线会话：fpid+kid 双匹配（20261016 多账号教训：同区服可开多个不同盟的号，
     *  只按 kid 抓会话会拿到别盟账号——使馆 1510/驻防 2815/英雄 301 全是错号所致）。 */
    private static RoleSessionKeeper.LiveSession sessionFor(Context ctx, String fpid, long kid) {
        try {
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                if (f.sid == kid && f.fpid != null && f.fpid.equals(fpid) && f.engine != null
                        && WjdrEngine.STATE_ONLINE.equals(f.engine.sessionState())) return f;
            }
            // 兜底：该账号不在线时退回任意同区在线（好过完全不动作）
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
