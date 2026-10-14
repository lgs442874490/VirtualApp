package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1 协议探针（守护盟友 M1 阻塞项实弹工具）。
 * 设计：design/守护盟友-自动驻防系统设计-20261015.md §13；
 * 全部引擎收发自动落 ProtoLogger（proto_log.txt），本类只负责发起与现场记录。
 *
 * 覆盖项：
 *  A-13 使馆容量        req_arsenal_league_embassy_help_simple_info / _march_detail_list（无消耗）
 *  A-10 道具库存        engine 登录快照 notify_backpack_init 读数 + 加速前后 diff 锁 item id（无消耗）
 *  A-3  加速 req 参数    req_world_march_speedup 候选参数逐试，成功即实证
 *  A-17 blind-burst     首次成功后背靠背连发 2 发，观察是否被接受
 *  A-15/A-16 floor      每发后读自身行军 endtime，连续 2 发无前移即触底
 *  召回参数             req_world_march_recall 候选参数逐试
 *  A-2  驻防发起        req_world_march type=5 打盟友城坐标（需界面单独触发）
 *  A-23 迁城 c2s        req_arsenal_league_move_city（烧道具，需确认）
 *
 * 消耗声明：加速链会烧真实加速道具（上限 8 发）+ 一条采集行军；迁城按钮烧高迁。
 */
public final class GuardProbe {

    private static final int SPEEDUP_CAP = 8;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private GuardProbe() {
    }

    public static synchronized boolean running() {
        return RUNNING.get();
    }

    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void log(String line) {
        synchronized (RECENT) {
            RECENT.addFirst(stamp() + " " + line);
            while (RECENT.size() > 60) RECENT.removeLast();
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    /** 在线会话摘要（供界面选择探针执行会话）。 */
    public static List<String> onlineSessions(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                if (f.engine != null && "ONLINE".equals(f.engine.sessionState())) {
                    out.add(f.fpid + ":" + f.sid + " " + f.label);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static RoleSessionKeeper.LiveSession pick(Context ctx, String key) {
        try {
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                String k = f.fpid + ":" + f.sid;
                if (k.equals(key) && "ONLINE".equals(f.engine.sessionState())) return f;
            }
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                if ("ONLINE".equals(f.engine.sessionState())) return f;   // 兜底取第一个在线
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ A-13 使馆查询（无消耗）

    public static void embassyQuery(Context ctx, String sessionKey) {
        runAsync(() -> {
            RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
            if (f == null) { log("使馆查询：无在线会话"); return; }
            log("使馆查询 @" + f.fpid + ":" + f.sid);
            for (String proto : new String[]{
                    "req_arsenal_league_embassy_help_simple_info",
                    "req_arsenal_league_embassy_help_march_detail_list"}) {
                try {
                    GameMessage rsp = f.engine.request(proto, new LinkedHashMap<>());
                    log(proto + " → " + abbrev(String.valueOf(rsp == null ? "null" : rsp.getContent())));
                } catch (Throwable t) {
                    log(proto + " 异常: " + brief(t));
                }
            }
        });
    }

    // ------------------------------------------------------------------ A-10 道具快照（无消耗）

    public static void itemSnapshot(Context ctx, String sessionKey) {
        runAsync(() -> {
            RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
            if (f == null) { log("道具快照：无在线会话"); return; }
            try {
                Map<String, Object> bp = f.engine.notifyContent("notify_backpack_init");
                StringBuilder sb = new StringBuilder();
                int total = 0;
                for (Map<String, Object> it : ExecUtil.mapsOf(bp == null ? null : bp.get("data"))) {
                    long id = ExecUtil.longOf(it.get("id"));
                    long cnt = ExecUtil.longOf(it.get("count"));
                    if ((id >= 510000L && id <= 599999L) || (id >= 600000L && id <= 699999L)) {
                        sb.append(id).append('x').append(cnt).append(' ');
                        total++;
                    }
                }
                log("道具快照(" + total + "种5xx/6xx类): " + (sb.length() == 0 ? "无" : abbrev(sb.toString())));
                if (total == 0) log("注意：backpack_init 快照为空——看 proto_log 的 SNAPSHOT-LOGIN 段核对形状");
            } catch (Throwable t) {
                log("道具快照异常: " + brief(t));
            }
        });
    }

    // ------------------------------------------------------------------ 加速链实测（烧道具，上限 8 发）

    public static void speedupChain(Context ctx, String sessionKey) {
        runAsync(() -> {
            if (!RUNNING.compareAndSet(false, true)) { log("已有探针在跑"); return; }
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("加速链：无在线会话"); return; }
                WjdrEngine engine = f.engine;
                log("加速链开始 @" + f.fpid + ":" + f.sid + "（上限 " + SPEEDUP_CAP + " 发）");

                // 1. 找一个远资源点 + 编队，发采集行军
                Map<String, Object> pt = searchFar(engine);
                if (pt == null) { log("未搜到资源点，链终止"); return; }
                Map<String, Object> formation = ExecUtil.formation(engine, 1);
                if (formation == null) { log("无可用编队，链终止"); return; }
                GameMessage launch = engine.request("req_world_march", ExecUtil.args(
                        "type", 4L, "endpoint", pt, "formation", formation,
                        "extra", new LinkedHashMap<>()));
                long marchId = marchIdOf(launch);
                if (marchId <= 0) { log("行军发射失败 code=" + codeOf(launch) + "，链终止"); return; }
                log("行军已发射 id=" + marchId + " → (" + ExecUtil.longOf(pt.get("x")) + "," + ExecUtil.longOf(pt.get("y")) + ")");

                Map<String, Object> bp0 = backpackMap(engine);

                // 2. 加速链：首发试参数 → burst 2 发 → 单发直至 floor
                long end = selfMarchEnd(engine, marchId);
                log("初始 endtime=" + end + "（剩 " + (end - nowS()) + "s）");
                String spelling = null;
                int used = 0;
                int noMove = 0;
                boolean burstTested = false;
                while (used < SPEEDUP_CAP) {
                    int round = (spelling == null) ? 1 : (!burstTested ? 2 : 1);   // 首发试参，第二轮 blind-burst×2
                    boolean anyOk = false;
                    for (int i = 0; i < round && used < SPEEDUP_CAP; i++) {
                        GameMessage rsp = trySpeedup(engine, marchId, spelling);
                        if (rsp == null) break;
                        spelling = currentSpelling;
                        int code = codeOf(rsp);
                        used++;
                        if (code == 0) {
                            anyOk = true;
                            if (!burstTested) log("加速 ok（参数拼法【" + spelling + "】）——A-3 实证");
                        } else {
                            log("加速拒绝 code=" + code + "（拼法 " + currentSpelling + "）");
                        }
                    }
                    if (round == 2 && anyOk && !burstTested) {
                        burstTested = true;
                        log("A-17：已背靠背连发 2 发未等响应，下面读 endtime 验证是否都被处理");
                    }
                    if (!anyOk && spelling != null) { log("加速连续失败，链终止"); break; }
                    if (!anyOk && spelling == null) {
                        log("全部候选参数被拒——A-3 需要换思路（抓游戏客户端 c2s）"); break;
                    }
                    sleep(600);
                    long end2 = selfMarchEnd(engine, marchId);
                    log("第 " + used + " 发后 endtime=" + end2 + "（前移 " + (end - end2) + "s，剩 " + (end2 - nowS()) + "s）");
                    if (end2 >= end - 1) {
                        noMove++;
                        if (noMove >= 2) { log("触底（floor）：连续 2 发无前移——A-16 实证，floor≈" + (end2 - nowS()) + "s"); break; }
                    } else noMove = 0;
                    end = end2;
                }

                // 3. 道具 diff → 锁 item id（A-10）
                Map<String, Object> bp1 = backpackMap(engine);
                diffBackpack(bp0, bp1);

                // 4. 召回（释放队列 + 召回参数实证）
                if (marchId > 0) {
                    GameMessage rc = tryRecall(engine, marchId);
                    log("召回 code=" + codeOf(rc) + "（成功即召回参数实证）");
                }
                log("加速链结束，共耗 " + used + " 发");
            } catch (Throwable t) {
                log("加速链异常: " + brief(t));
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static String currentSpelling = "?";

    private static GameMessage trySpeedup(WjdrEngine engine, long marchId, String known) throws Exception {
        // 候选参数拼法：tier 1=25% 档、2=50% 档（用户实证 1 阶 25% / 2 阶 50% 的命名）
        String[][] candidates = {
                {"march_id", "tier"}, {"march_id", "type"}, {"id", "tier"},
        };
        for (String[] c : candidates) {
            String sp = c[0] + "+" + c[1];
            if (known != null && !"?" .equals(known) && !sp.equals(known)) continue;   // 已锁定拼法后只发该拼法
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(c[0], marchId);
            args.put(c[1], 1L);
            GameMessage rsp = engine.request("req_world_march_speedup", args);
            currentSpelling = sp;
            if (codeOf(rsp) == 0) return rsp;
        }
        return null;
    }

    private static GameMessage tryRecall(WjdrEngine engine, long marchId) throws Exception {
        String[][] candidates = {{"march_id"}, {"id"}};
        GameMessage last = null;
        for (String[] c : candidates) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put(c[0], marchId);
            last = engine.request("req_world_march_recall", args);
            if (codeOf(last) == 0) { log("召回拼法【" + c[0] + "】"); return last; }
        }
        return last;
    }

    // ------------------------------------------------------------------ 驻防发起（A-2，烧一条行军）

    /** allyX/allyY = 盟友城坐标；type=5 驻防。发完不召回（真实驻防），由用户后续处理。 */
    public static void garrisonLaunch(Context ctx, String sessionKey, long allyX, long allyY) {
        runAsync(() -> {
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("驻防探针：无在线会话"); return; }
                Map<String, Object> formation = ExecUtil.formation(f.engine, 1);
                if (formation == null) { log("驻防探针：无可用编队"); return; }
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("x", allyX);
                pt.put("y", allyY);
                GameMessage rsp = f.engine.request("req_world_march", ExecUtil.args(
                        "type", 5L, "endpoint", pt, "formation", formation,
                        "extra", new LinkedHashMap<>()));
                log("驻防发起(type=5 → " + allyX + "," + allyY + ") code=" + codeOf(rsp)
                        + " marchId=" + marchIdOf(rsp) + " ——code=0 即 A-2 实证（响应全量看 proto_log）");
            } catch (Throwable t) {
                log("驻防探针异常: " + brief(t));
            }
        });
    }

    // ------------------------------------------------------------------ 迁城（A-23 c2s，烧高迁）

    public static void moveCity(Context ctx, String sessionKey) {
        runAsync(() -> {
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("迁城探针：无在线会话"); return; }
                Map<String, Object> init = f.engine.notifyContent("notify_world_player_init");
                Map<String, Object> cp = ExecUtil.mapOf(init == null ? null : init.get("city_point"));
                long x = ExecUtil.longOf(cp.get("x")), y = ExecUtil.longOf(cp.get("y"));
                if (x <= 0) { log("未取得当前城坐标（city_point 空），链终止"); return; }
                log("当前城 (" + x + "," + y + ")，尝试迁城到附近 (+1,0)");
                String[][] candidates = {{"x", "y"}, {"pos_x", "pos_y"}};
                GameMessage last = null;
                for (String[] c : candidates) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put(c[0], x + 1);
                    args.put(c[1], y);
                    last = f.engine.request("req_arsenal_league_move_city", args);
                    log("req_arsenal_league_move_city [" + c[0] + "," + c[1] + "] code=" + codeOf(last));
                    if (codeOf(last) == 0) break;
                }
            } catch (Throwable t) {
                log("迁城探针异常: " + brief(t));
            }
        });
    }

    // ------------------------------------------------------------------ 内部工具

    private static void runAsync(Runnable r) {
        Thread t = new Thread(r, "guard-probe");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Object> searchFar(WjdrEngine engine) throws Exception {
        Map<String, Object> best = null;
        for (int resId : new int[]{104, 102, 103}) {
            GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                    "mapobj_type", 2L, "level_min", 1L, "level_max", 1L,
                    "res_id", (long) resId, "is_full", false));
            if (!ExecUtil.ok(rsp)) continue;
            for (String k : new String[]{"points", "data", "list"}) {
                for (Map<String, Object> p : ExecUtil.mapsOf(rsp.getContent().get(k))) {
                    if (p.containsKey("x") && p.containsKey("y")) {
                        best = p;   // 取第一个（搜索结果按距离排序，够远即可——加速链不在乎距离）
                        break;
                    }
                }
                if (best != null) break;
            }
            if (best != null) break;
        }
        return best;
    }

    private static long selfMarchEnd(WjdrEngine engine, long marchId) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return -1;
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

    private static Map<String, Object> backpackMap(WjdrEngine engine) {
        Map<String, Object> bp = engine.notifyContent("notify_backpack_init");
        return bp == null ? new LinkedHashMap<>() : bp;
    }

    private static void diffBackpack(Map<String, Object> before, Map<String, Object> after) {
        try {
            Map<Long, Long> b = itemCounts(before), a = itemCounts(after);
            StringBuilder sb = new StringBuilder();
            for (Long k : a.keySet()) {
                long v0 = b.containsKey(k) ? b.get(k) : 0;
                if (a.get(k) != v0) sb.append("id").append(k).append(":").append(v0).append("→").append(a.get(k)).append(' ');
            }
            log(sb.length() == 0 ? "道具无变化（加速可能走钻石或未生效？）——A-10 item id 未锁定"
                    : "道具变化：" + sb + "——A-10 锁定");
        } catch (Throwable t) {
            log("道具 diff 异常: " + brief(t));
        }
    }

    private static Map<Long, Long> itemCounts(Map<String, Object> bp) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (bp == null) return out;
        for (Map<String, Object> it : ExecUtil.mapsOf(bp.get("data"))) {
            out.put(ExecUtil.longOf(it.get("id")), ExecUtil.longOf(it.get("count")));
        }
        return out;
    }

    private static long marchIdOf(GameMessage msg) {
        try {
            Map<String, Object> c = msg.getContent();
            Object m = c == null ? null : c.get("march");
            if (m instanceof Map) return ExecUtil.longOf(((Map<?, ?>) m).get("id"));
            return ExecUtil.longOf(c == null ? null : c.get("march_id"));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int codeOf(GameMessage msg) {
        try {
            return (int) ExecUtil.longOf(msg.getContent().get("code"));
        } catch (Throwable t) {
            return -999;
        }
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String brief(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }
}
