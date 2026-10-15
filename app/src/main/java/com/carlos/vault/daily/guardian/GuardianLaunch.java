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
    /** targetUid → 在途驻防 marchId。 */
    private static final ConcurrentHashMap<Long, Long> INFLIGHT = new ConcurrentHashMap<>();
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

    private static void execute(Context app, String fpid, long kid, GuardianEngine.Track t) {
        try {
            RoleSessionKeeper.LiveSession f = sessionFor(app, kid);
            if (f == null) { glog(app, "无 " + kid + " 在线会话，无法驻防"); return; }
            WjdrEngine engine = f.engine;

            // 目标城坐标：威胁行军的 endpoint 即盟友城
            int gx = t.x, gy = t.y;
            if (gx <= 0) { glog(app, "威胁缺目标坐标，跳过"); return; }

            // 并发闸：同目标一队 + 全局上限
            if (t.targetUid > 0 && INFLIGHT.containsKey(t.targetUid)) {
                glog(app, "该盟友城已有我方驻防在途（uid" + t.targetUid + "），不重复驻防");
                return;
            }
            if (INFLIGHT.size() >= MAX_CONCURRENT) { glog(app, "驻防并发已达上限，跳过"); return; }

            // 资源闸：MarchCoordinator（采集/狩猎等日常任务让位语义）
            Object lock = null;
            try {
                lock = MarchCoordinator.forKey(fpid, kid).tryAcquire("guardian", 3000);
            } catch (Throwable ignored) {
            }
            glog(app, "资源闸 " + (lock != null ? "已获取" : "未获取（继续执行，守护优先）"));

            Map<String, Object> formation = buildFormation(engine);
            if (formation == null) { glog(app, "编队构建失败，放弃驻防"); return; }

            // 目标使馆状态查询（A-13/A-2 邻证）：驻防位满/无使馆可能是 301 的根因
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

            if (calibD == null || enemyEnd - nowS < calibD + 3) {
                // 标定轮：立即发射（必然先于敌落地），记录真实行军时长
                glog(app, "标定轮：立即发射驻防（敌 ETA " + (enemyEnd - nowS) + "s）");
                GameMessage rsp = safeLaunch(engine, gx, gy, formation, t.targetUid);
                long ourEnd = endtimeOf(rsp);
                if (ourEnd <= 0) { glog(app, "驻防发射失败 code=" + codeOf(rsp)); return; }
                long d = ourEnd - nowS;
                CALIB.put(kid, d);
                saveCalib(app, kid, d);
                long margin = (enemyEnd > ourEnd ? enemyEnd - ourEnd : 0);
                glog(app, "标定完成 D=" + d + "s，我方到达先于敌 " + margin + "s"
                        + (margin >= 1 ? "（标定轮即卡秒成功）" : "（未卡在窗口，后续威胁将精卡）"));
                INFLIGHT.put(t.targetUid > 0 ? t.targetUid : gx * 100000L + gy, ourEnd > 0 ? ourEnd : 0L);
                return;
            }

            // 精卡轮：waitUntil(敌到达 − D − Δ) 后发射
            long deltaMs = deltaMs(app, fpid, kid);
            long fireServerSec = enemyEnd - calibD - deltaMs / 1000L;
            long waitMs = fireServerSec * 1000L - engine.serverClock().nowServerMs();
            if (waitMs <= 0) { glog(app, "已过发射窗（差 " + (-waitMs) + "ms），立即发射兜底"); }
            else {
                glog(app, "卡秒：等 " + waitMs + "ms 后发射（敌 ETA " + (enemyEnd - nowS)
                        + "s − D " + calibD + "s − Δ " + deltaMs / 1000L + "s）");
            }
            try {
                engine.serverClock().waitUntilSec(fireServerSec);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            GameMessage rsp = safeLaunch(engine, gx, gy, formation, t.targetUid);
            long ourEnd = endtimeOf(rsp);
            if (ourEnd <= 0) {
                glog(app, "精卡发射失败 code=" + codeOf(rsp) + "，推送手动增援告警");
                com.carlos.vault.daily.EnemyWatch.alert(app,
                        "🚨盟友城被攻，请手动增援（精卡发射被拒 code=" + codeOf(rsp) + "）", true);
                return;
            }
            long marginMs = (enemyEnd - ourEnd) * 1000L;
            boolean ok = marginMs >= ARRIVE_MARGIN_MIN_MS && marginMs <= ARRIVE_MARGIN_MAX_MS;
            glog(app, "精卡发射完成：我方到达先于敌 " + marginMs + "ms → "
                    + (ok ? "✅卡秒验收通过（0.5~5s 窗）" : "⚠️窗口外（Δ 需调整）"));
        } finally {
            // 占位：INFLIGHT 的清除由后续 march_del 观察（P3 简化为 TTL 由进程生命周期覆盖）
        }
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
            Object march = c == null ? null : c.get("march");
            if (march instanceof Map) return ExecUtil.longOf(((Map<?, ?>) march).get("endtime"));
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    // ------------------------------------------------------------------ 编队（英雄 3 + 主力兵 40/30/30）

    private static Map<String, Object> buildFormation(WjdrEngine engine) {
        List<Long> heroes = new ArrayList<>();
        Map<String, Object> arrange = engine.notifyData("notify_battle_hero_arrange_data");
        if (arrange != null) {
            for (Map<String, Object> m : mapsDeep(arrange.get("hero_arranges"))) {
                Object fh = m.get("fight_heros");
                if (fh instanceof List) {
                    for (Object h : (List<?>) fh) {
                        long id = ExecUtil.longOf(h);
                        if (id > 0 && !heroes.contains(id)) heroes.add(id);
                    }
                }
            }
        }
        if (heroes.isEmpty()) {
            Map<String, Object> hi = engine.notifyContent("notify_hero_init");
            Object data = hi == null ? null : hi.get("data");
            if (data instanceof Map) {
                for (Object o : ((Map<?, ?>) data).values()) {
                    long id = ExecUtil.longOf(asMap(o).get("id"));
                    if (id > 0) heroes.add(id);
                }
                heroes.sort((a, b) -> Long.compare(b, a));
            }
        }
        if (heroes.size() > 3) heroes.subList(3, heroes.size()).clear();
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

    private static String stack(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
