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
    /** ④→⑤ 接力：最近一次驻防发射 {marchId, endtime秒, 发射时刻ms}。world detail 不含 type=5（实测 code=2），只能靠它。 */
    private static volatile long[] LAST_GARRISON;

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

    private static volatile Context APP;

    /** UI 入口注入 Application 上下文（探针日志落盘用）。 */
    public static void init(Context ctx) {
        if (APP == null && ctx != null) APP = ctx.getApplicationContext();
    }

    private static void log(String line) {
        synchronized (RECENT) {
            RECENT.addFirst(stamp() + " " + line);
            while (RECENT.size() > 60) RECENT.removeLast();
        }
        // 落盘（files/probe_log.txt，adb run-as 可读）——现场诊断不依赖截图
        try {
            Context c = APP;
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "probe_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(stamp() + " " + line + "\n");
            }
        } catch (Throwable ignored) {
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
                if (f.engine != null && WjdrEngine.STATE_ONLINE.equals(f.engine.sessionState())) {
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
                if (k.equals(key) && WjdrEngine.STATE_ONLINE.equals(f.engine.sessionState())) return f;
            }
            for (RoleSessionKeeper.LiveSession f : RoleSessionKeeper.get(ctx).sessionsSnapshot()) {
                if (WjdrEngine.STATE_ONLINE.equals(f.engine.sessionState())) return f;   // 兜底取第一个在线
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ A-13 使馆查询（无消耗）

    public static void embassyQuery(Context ctx, String sessionKey) {
        init(ctx);
        runAsync(() -> {
            RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
            if (f == null) { log("使馆查询：无在线会话"); return; }
            log("使馆查询 @" + f.fpid + ":" + f.sid);
            // 协议族考证（20261015 schema dump）：普通联盟=req_embassy_help_*(10101-10103)，
            // req_arsenal_league_embassy_*(17222-17224)=兵工厂联盟模式专属。
            // 双族对照各带 {uid=自己}：A-13 容量字段 + 9001 是否"模式不符"一次实证
            Map<String, Object> args = new LinkedHashMap<>();
            try { args.put("uid", f.engine.lastUid()); } catch (Throwable ignored) { }
            for (String proto : new String[]{
                    "req_embassy_help_simple_info",
                    "req_embassy_help_info",
                    "req_arsenal_league_embassy_help_simple_info",
                    "req_arsenal_league_embassy_help_march_detail_list"}) {
                try {
                    GameMessage rsp = f.engine.request(proto, new LinkedHashMap<>(args));
                    log(proto + " → " + abbrev(String.valueOf(rsp == null ? "null" : rsp.getContent())));
                } catch (Throwable t) {
                    log(proto + " 异常: " + brief(t));
                }
            }
        });
    }

    // ------------------------------------------------------------------ A-10 道具快照（无消耗）

    public static void itemSnapshot(Context ctx, String sessionKey) {
        init(ctx);
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
                // 全量导出（files/backpack_dump.txt，每行 id count）——用道具前后各导一次，diff 锁 id
                try {
                    java.io.File dump = new java.io.File(APP.getFilesDir(), "backpack_dump.txt");
                    try (java.io.FileWriter w = new java.io.FileWriter(dump, false)) {
                        for (Map<String, Object> it : ExecUtil.mapsOf(bp == null ? null : bp.get("data"))) {
                            w.write(ExecUtil.longOf(it.get("id")) + " " + ExecUtil.longOf(it.get("count")) + "\n");
                        }
                    }
                    log("全量背包已导出 backpack_dump.txt（" + ExecUtil.mapsOf(bp.get("data")).size() + " 种）");
                } catch (Throwable t2) {
                    log("背包导出失败: " + brief(t2));
                }
                // P0 服务器时钟读数（本机钟 vs 服务器钟的偏差一目了然）
                com.carlos.vault.wjdr.GameServerClock ck = f.engine.serverClock();
                log("服务器时钟：" + (ck.locked()
                        ? "已锁相 本机-服务器偏差=" + (System.currentTimeMillis() - ck.nowServerMs())
                          + "ms errBound=" + ck.errBoundMs() + "ms 服务器秒=" + ck.nowServerSec()
                          + " 距上次校准=" + ck.sinceCalibMs() + "ms"
                        : "未锁相（登录样本中未发现 server_time）本机秒=" + System.currentTimeMillis() / 1000L));
            } catch (Throwable t) {
                log("道具快照异常: " + brief(t));
            }
        });
    }

    // ------------------------------------------------------------------ 加速链实测（烧道具，上限 8 发）

    public static void speedupChain(Context ctx, String sessionKey) {
        init(ctx);
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
                log("搜到野怪 (" + ExecUtil.longOf(pt.get("x")) + "," + ExecUtil.longOf(pt.get("y"))
                        + ") monsterid=" + ExecUtil.longOf(pt.get("monsterid")));
                Map<String, Object> formation = buildFormationProbe(engine);
                if (formation == null) { log("编队构建失败（各层数据见诊断行），链终止"); return; }
                log("编队就绪，发射行军…");
                GameMessage launch = engine.request("req_world_march", ExecUtil.args(
                        "type", 3L, "endpoint", ExecUtil.point(ExecUtil.longOf(pt.get("x")), ExecUtil.longOf(pt.get("y"))),
                        "formation", formation,
                        "extra", ExecUtil.args("monsterid", ExecUtil.longOf(pt.get("monsterid")))));
                long marchId = marchIdOf(launch);
                if (marchId <= 0) {
                    log("行军发射失败 code=" + codeOf(launch) + (codeOf(launch) == 51 ? "（体力不足）" : "") + "，重搜一次");
                    sleep(1200);
                    Map<String, Object> pt2 = searchFar(engine);
                    if (pt2 == null) { log("重搜仍无资源点，链终止"); return; }
                    GameMessage launch2 = engine.request("req_world_march", ExecUtil.args(
                            "type", 3L, "endpoint", ExecUtil.point(ExecUtil.longOf(pt2.get("x")), ExecUtil.longOf(pt2.get("y"))),
                            "formation", formation,
                            "extra", ExecUtil.args("monsterid", ExecUtil.longOf(pt2.get("monsterid")))));
                    marchId = marchIdOf(launch2);
                    if (marchId <= 0) { log("重试发射仍失败 code=" + codeOf(launch2) + "，链终止"); return; }
                    log("重试发射成功 id=" + marchId);
                }
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

    // ------------------------------------------------------------------ 加速链·复用在途行军（不搜图不发射）

    /**
     * 复用自动化日常已发出的在途行军作为加速目标（绕开地图搜索限频/形状问题）。
     * 数据源：notify_player_march_add（登录后最近一条己方出征推送）。
     */
    public static void speedupExisting(Context ctx, String sessionKey) {
        init(ctx);
        runAsync(() -> {
            if (!RUNNING.compareAndSet(false, true)) { log("已有探针在跑"); return; }
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("复用加速链：无在线会话"); return; }
                WjdrEngine engine = f.engine;
                long ownerUid = engine.lastUid();
                log("复用加速链开始 @" + f.fpid + ":" + f.sid + "（等待在途行军，最多 3 分钟）");

                // 等一条在途行军（notify 缓冲 → 登录快照 → 自查 detail 轮询）。
                // 选行军策略：优先 type=5（驻防矩阵实验对象）；普通行军只记兜底，
                // 自查到兜底后再等最多 45s，仍无 type=5 才用兜底（防把道具烧在采集行军上）
                long marchId = -1, end = -1, mtype = -1;
                long fbId = -1, fbEnd = -1;
                long type5Deadline = 0;
                long deadline = System.currentTimeMillis() + 180_000L;
                while (System.currentTimeMillis() < deadline && marchId <= 0) {
                    // 来源0：④发射接力（world detail 不含 type=5，实测 code=2——这是唯一可靠来源）
                    long[] lg = LAST_GARRISON;
                    if (lg != null && System.currentTimeMillis() - lg[2] < 60_000L && lg[1] > nowS()) {
                        marchId = lg[0]; end = lg[1]; mtype = 5L;
                    }
                    // 来源1：notify 缓冲
                    Map<String, Object> add = engine.notifyContent("notify_player_march_add");
                    Map<String, Object> march = ExecUtil.mapOf(add == null ? null : add.get("march"));
                    if (march != null) {
                        long st = ExecUtil.longOf(march.get("state"));
                        if (st == 2 || st == 4) {   // 仅在途（出征/回程）；已到达(3)不可加速
                            long mid = ExecUtil.longOf(march.get("id"));
                            long mend = ExecUtil.longOf(march.get("endtime"));
                            long mty = ExecUtil.longOf(march.get("type"));
                            if (mty == 5L && mid > 0 && mend > nowS()) { marchId = mid; end = mend; mtype = mty; }
                            else if (mid > 0 && mend > nowS() && fbId <= 0) { fbId = mid; fbEnd = mend; }
                        }
                    }
                    // 来源2：登录快照 march_info
                    if (marchId <= 0) {
                        Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
                        for (Map<String, Object> mi : ExecUtil.mapsOf(wpi == null ? null : wpi.get("march_info"))) {
                            long eid = ExecUtil.longOf(mi.get("id"));
                            long eend = ExecUtil.longOf(mi.get("endtime"));
                            long st = ExecUtil.longOf(mi.get("state"));
                            long mty = ExecUtil.longOf(mi.get("type"));
                            if (eid > 0 && eend > nowS() && (st == 2 || st == 4)) {
                                if (mty == 5L) { marchId = eid; end = eend; mtype = mty; break; }
                                if (fbId <= 0) { fbId = eid; fbEnd = eend; }
                            }
                        }
                    }
                    // 来源3：自查 detail（实时，兜底记录也在这里刷新）
                    if (marchId <= 0) {
                        Map<Long, long[]> m = selfMarchesTyped(engine);
                        for (Map.Entry<Long, long[]> e : m.entrySet()) {
                            long[] v = e.getValue();
                            if (v[1] == 5L) { marchId = e.getKey(); end = v[0]; mtype = 5L; break; }
                            if (fbId <= 0) { fbId = e.getKey(); fbEnd = v[0]; }
                        }
                    }
                    if (marchId <= 0 && fbId > 0 && type5Deadline == 0) {
                        type5Deadline = System.currentTimeMillis() + 45_000L;
                        log("先看到普通行军（兜底 id=" + fbId + "），等 45s 看有没有 type=5 驻防……");
                    }
                    if (marchId <= 0 && fbId > 0 && type5Deadline > 0 && System.currentTimeMillis() >= type5Deadline) {
                        marchId = fbId; end = fbEnd; mtype = -1;   // 用兜底
                    }
                    if (marchId <= 0) sleep(5_000L);
                }
                if (marchId <= 0) { log("3 分钟内无在途行军（自动化未出征？）——稍后再点本按钮"); return; }
                if (mtype != 5L) { log("目标是普通行军（非 type=5）——不跑矩阵（保道具）。请先点④发射驻防再点本按钮"); return; }
                log("目标行军 id=" + marchId + " type=5 剩余 " + (end - nowS()) + "s");

                // type=5 驻防行军 → 加速形状实验矩阵（4s 短超时，失败不断连风暴）
                if (mtype == 5L) {
                    log("⚠️type=5 驻防行军：先跑形状实验矩阵（每变体 4s 超时）");
                    // 选道具（同主链：二阶 301901 优先）
                    Map<String, Object> bpM = backpackMap(engine);
                    long itemIdM = 0;
                    for (Map<String, Object> it : ExecUtil.mapsOf(bpM.get("data"))) {
                        long iid = ExecUtil.longOf(it.get("id"));
                        long cnt = ExecUtil.longOf(it.get("count"));
                        if (iid == 301901L && cnt > 0) { itemIdM = iid; break; }
                        if (iid == 301900L && cnt > 0 && itemIdM == 0) itemIdM = iid;
                    }
                    if (itemIdM <= 0) {
                        // 钻石兜底：快捷购买二阶加速道具再用（用户 20261016 授权测试）
                        Map<String, Object> qb = new LinkedHashMap<>();
                        qb.put("use", false);
                        qb.put("id", 301901L);
                        qb.put("count", 1L);
                        GameMessage brsp = f.engine.request("req_shop_quick_buy", qb, 8);
                        int bc = codeOf(brsp);
                        log("背包无加速道具 → 钻石快捷购买 301901 code=" + bc);
                        if (bc == 0) itemIdM = 301901L;
                        else { log("钻石购买失败（码 " + bc + "），矩阵无法执行"); return; }
                    }
                    String[][] matrix = {
                            {"req_world_march_speedup", "owner=string"},
                            {"DIAMOND_BUY", "world+owner=string"},
                            {"req_world_march_speedup", "owner=number"},
                            {"req_arsenal_league_march_speedup", "owner=string"},
                    };
                    String hit = null;
                    for (String[] mv : matrix) {
                        if (end - nowS() <= 3) { log("敌/我剩余不足 3s，矩阵中止"); break; }
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("id", marchId);
                        a.put("itemid", itemIdM);
                        if ("owner=string".equals(mv[1])) a.put("owner", String.valueOf(ownerUid));
                        else if ("owner=number".equals(mv[1])) a.put("owner", ownerUid);
                        String proto = mv[0];
                        if ("DIAMOND_BUY".equals(mv[0])) {
                            // 钻石路径：快捷购买二阶加速道具再用（原版 FastMarchSpeedupService 同款）
                            Map<String, Object> qb = new LinkedHashMap<>();
                            qb.put("use", false);
                            qb.put("id", 301901L);
                            qb.put("count", 1L);
                            try {
                                GameMessage brsp = f.engine.request("req_shop_quick_buy", qb, 8);
                                int bc = codeOf(brsp);
                                log("矩阵 钻石快捷购买 301901 code=" + bc);
                                if (bc != 0) continue;
                            } catch (Throwable t) {
                                log("矩阵 钻石购买异常: " + brief(t));
                                waitOnline(f.engine, 8_000L);
                                continue;
                            }
                            proto = "req_world_march_speedup";
                        }
                        try {
                            GameMessage r = f.engine.request(proto, a, 3);
                            int c = codeOf(r);
                            long ne = ExecUtil.longOf(r.getContent() == null ? null : r.getContent().get("endtime"));
                            log("矩阵 " + proto + "(" + mv[1] + ") code=" + c + (ne > 0 ? " newEnd前移" + (end - ne) + "s" : ""));
                            if (c == 0 && ne > 0 && ne < end) { hit = proto + "|" + mv[1]; end = ne; break; }
                        } catch (Throwable t) {
                            log("矩阵 " + proto + "(" + mv[1] + ") 异常: " + brief(t));
                            waitOnline(f.engine, 8_000L);
                        }
                    }
                    if (hit != null) {
                        log("✅type=5 加速形状锁定：" + hit + "（R3 据此跟进）——新 endtime=" + end);
                    } else {
                        log("❌type=5 加速矩阵全败：服务器对该行军类型不响应加速（R3 需换路径）");
                    }
                    diffBackpack(bpM, backpackMap(engine));
                    Map<String, Object> rargs = new LinkedHashMap<>();
                    rargs.put("id", marchId);
                    try {
                        GameMessage rc = f.engine.request("req_world_march_recall", rargs, 6);
                        log("召回 {id} code=" + codeOf(rc) + "（type=5 在途召回参数实证）");
                    } catch (Throwable t) {
                        log("召回异常: " + brief(t));
                    }
                    return;
                }

                // 选道具：301901=二阶加速(50%)、301900=一阶加速(25%)（2026-10-15 受控 diff 实证，A-10）
                Map<String, Object> bp0 = backpackMap(engine);
                long itemId = 0;
                String tier = "";
                for (Map<String, Object> it : ExecUtil.mapsOf(bp0.get("data"))) {
                    long iid = ExecUtil.longOf(it.get("id"));
                    long cnt = ExecUtil.longOf(it.get("count"));
                    if (iid == 301901L && cnt > 0) { itemId = iid; tier = "二阶50%"; break; }
                    if (iid == 301900L && cnt > 0 && itemId == 0) { itemId = iid; tier = "一阶25%"; }
                }
                if (itemId <= 0) { log("背包无一/二阶加速道具（301900/301901）——只测召回"); }

                // 加速链：schema 实证 {id, itemid, owner}，响应含新 endtime
                log("开始加速：itemid=" + itemId + "（" + tier + "）owner=" + ownerUid + "（上限 " + SPEEDUP_CAP + " 发）");
                int used = 0, noMove = 0;
                boolean burstTested = false;
                long prevEnd = end;
                while (used < SPEEDUP_CAP && itemId > 0) {
                    int round = (!burstTested && used >= 1) ? 2 : 1;   // 首发验证后第二轮 blind-burst×2
                    boolean anyOk = false;
                    long lastEnd = 0;
                    for (int i = 0; i < round && used < SPEEDUP_CAP; i++) {
                        Map<String, Object> args = new LinkedHashMap<>();
                        args.put("id", marchId);
                        args.put("itemid", itemId);
                        args.put("owner", String.valueOf(ownerUid));   // sproto schema: owner 是 string 字段(type 06)
                        GameMessage rsp = engine.request("req_world_march_speedup", args);
                        int code = codeOf(rsp);
                        used++;
                        if (code == 0) {
                            anyOk = true;
                            long newEnd = ExecUtil.longOf(rsp.getContent().get("endtime"));
                            if (newEnd > 0) lastEnd = newEnd;
                            if (!burstTested) log("加速 ok（{id,itemid,owner}）——A-3 实证，newEnd=" + newEnd);
                        } else {
                            log("加速拒绝 code=" + code + "（第 " + used + " 发）");
                        }
                    }
                    if (round == 2 && anyOk && !burstTested) {
                        burstTested = true;
                        log("A-17：已背靠背连发 2 发未等响应");
                    }
                    if (!anyOk) { log("加速连续拒绝，链终止"); break; }
                    long end2 = lastEnd > 0 ? lastEnd : selfMarchEnd(engine, marchId);
                    if (end2 <= 0) { log("行军已消失（落地），链提前结束"); break; }
                    log("第 " + used + " 发后 endtime=" + end2 + "（前移 " + (prevEnd - end2) + "s，剩 " + (end2 - nowS()) + "s）");
                    if (end2 >= prevEnd - 1) {
                        if (++noMove >= 2) { log("触底（floor）：连续 2 发无前移——A-16 实证，floor≈" + (end2 - nowS()) + "s"); break; }
                    } else noMove = 0;
                    prevEnd = end2;
                    sleep(400);
                }

                diffBackpack(bp0, backpackMap(engine));
                if (marchId > 0) {
                    Map<String, Object> rargs = new LinkedHashMap<>();
                    rargs.put("id", marchId);
                    GameMessage rc = engine.request("req_world_march_recall", rargs);
                    log("召回 {id} code=" + codeOf(rc) + "（成功即召回参数实证）链结束，共耗 " + used + " 发");
                }
            } catch (Throwable t) {
                log("复用加速链异常: " + brief(t));
            } finally {
                RUNNING.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ 驻防发起（A-2，烧一条行军）

    /** allyX/allyY = 盟友城坐标；type=5 驻防。发完不召回（真实驻防），由用户后续处理。 */
    public static void garrisonLaunch(Context ctx, String sessionKey, long allyX, long allyY) {
        init(ctx);
        runAsync(() -> {
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("驻防探针：无在线会话"); return; }
                log("驻防探针 @" + f.fpid + ":" + f.sid);
                Map<String, Object> formation = ExecUtil.formation(f.engine, 1);
                if (formation == null) {
                    // 兜底：A-2 实证变体4（1兵无英雄，21:19 code=0 样本）——不依赖英雄快照
                    long bid = 0, bc = 0;
                    Map<String, Object> si = f.engine.notifyContent("notify_soldier_init");
                    // 一路：soldier_init.soldier = 城内兵力（旧形状）
                    for (Map<String, Object> it : ExecUtil.mapsOf(si == null ? null : si.get("soldier"))) {
                        long id = ExecUtil.longOf(it.get("id"));
                        long cnt = ExecUtil.longOf(it.get("count"));
                        if (id > 0 && cnt > bc) { bc = cnt; bid = id; }
                    }
                    // 二路：soldier_init.formation.*.soldier = 编队预设兵力（新形状，22:28 快照实证）
                    if (bid <= 0 && si != null) {
                        for (Map<String, Object> fm : ExecUtil.mapsOf(si.get("formation"))) {
                            for (Map<String, Object> it : ExecUtil.mapsOf(fm.get("soldier"))) {
                                long id = ExecUtil.longOf(it.get("id"));
                                long cnt = ExecUtil.longOf(it.get("count"));
                                if (id > 0 && cnt > bc) { bc = cnt; bid = id; }
                            }
                        }
                    }
                    // 三路：recruitment_init.recruit_soldier（顶层键，登录快照）
                    if (bid <= 0) {
                        Map<String, Object> ri = f.engine.notifyContent("notify_recruitment_init");
                        Map<String, Object> rs = ri == null ? null : ExecUtil.mapOf(ri.get("recruit_soldier"));
                        if (rs != null) {
                            for (Object v : rs.values()) {
                                Map<String, Object> m = ExecUtil.mapOf(v);
                                long id = ExecUtil.longOf(m.get("k") != null ? m.get("k") : m.get("id"));
                                long cnt = ExecUtil.longOf(m.get("v") != null ? m.get("v") : m.get("count"));
                                if (id > 0 && cnt > bc) { bc = cnt; bid = id; }
                            }
                        }
                    }
                    if (bid <= 0) {
                        log("驻防探针：无可用编队且三路士兵快照均空（" + f.fpid + ":" + f.sid + "）");
                        return;
                    }
                    formation = ExecUtil.args("hero_id", new java.util.ArrayList<>(), "soldier",
                            java.util.Arrays.asList(ExecUtil.args("k", bid, "v", 1L)));
                    log("驻防探针：编队快照缺失，用变体4（兵" + bid + "×1）");
                }
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("x", allyX);
                pt.put("y", allyY);
                GameMessage rsp = f.engine.request("req_world_march", ExecUtil.args(
                        "type", 5L, "endpoint", pt, "formation", formation,
                        "extra", new LinkedHashMap<>()));
                long mid0 = marchIdOf(rsp);
                long end0 = 0;
                try {
                    Map<String, Object> minfo = ExecUtil.mapOf(rsp.getContent().get("march_info"));
                    end0 = ExecUtil.longOf(minfo.get("endtime"));
                } catch (Throwable ignored) {
                }
                if (codeOf(rsp) == 0 && mid0 > 0) {
                    LAST_GARRISON = new long[]{mid0, end0, System.currentTimeMillis()};
                    log("驻防发起(type=5 → " + allyX + "," + allyY + ") code=0 marchId=" + mid0
                            + " ——已交接给⑤矩阵（code=0 即 A-2 实证）");
                } else {
                    log("驻防发起(type=5 → " + allyX + "," + allyY + ") code=" + codeOf(rsp)
                            + " marchId=" + mid0 + " ——code=0 即 A-2 实证（响应全量看 proto_log）");
                }
            } catch (Throwable t) {
                log("驻防探针异常: " + brief(t));
            }
        });
    }

    // ------------------------------------------------------------------ 迁城（A-23 c2s，烧高迁）

    /**
     * 撤回驻防探针（2026-11-04：入参改为游戏内可见的 FID，内部再换 uid）。
     *
     * <p>用户无法得知服务器内部 uid，工具只让填 FID；这里用 {@code req_player_info_by_fid}
     * 换成 uid 再按 help_city.target 匹配驻扎行军。
     */
    public static void repatriateProbe(Context ctx, String sessionKey, String targetFidStr) {
        init(ctx);
        runAsync(() -> {
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("撤防探针：无在线会话"); return; }
                WjdrEngine engine = f.engine;
                long targetUid = 0;
                String fid = targetFidStr == null ? "" : targetFidStr.trim();
                if (!fid.isEmpty()) {
                    Map<String, Object> r = null;
                    try {
                        GameMessage lookup = engine.request("req_player_info_by_fid",
                                ExecUtil.args("fid", fid));
                        if (ExecUtil.ok(lookup)) r = ExecUtil.mapOf(lookup.getContent().get("result"));
                    } catch (Throwable ignored) {
                    }
                    targetUid = r == null ? 0L : ExecUtil.longOf(r.get("uid"));
                    if (targetUid <= 0L) log("撤防：FID " + fid + " 未查到玩家（按全部驻扎处理）");
                }
                log("撤防探针 @" + f.fpid + ":" + f.sid
                        + (targetUid > 0 ? " fid=" + fid + "→uid=" + targetUid : "（全部驻扎）"));
                // 协议考证（原版工具 recallDecision）：驻扎(state=3)撤离 = req_world_march_off_work {id}；
                // req_embassy_repatriation {target_uid} 非此用途（实测 code=0 空操作 / 2894 不存在）。
                // 数据源：world detail 查询不含驻扎行军（实测 code=2），驻扎的在 notify_world_player_init 快照里
                java.util.List<Map<String, Object>> garrisons = new java.util.ArrayList<>();
                Map<String, Object> wpi = engine.notifyContent("notify_world_player_init");
                for (Map<String, Object> mi : ExecUtil.mapsOf(wpi == null ? null : wpi.get("march_info"))) {
                    long mid = ExecUtil.longOf(mi.get("id"));
                    long ty = ExecUtil.longOf(mi.get("type"));
                    long st = ExecUtil.longOf(mi.get("state"));
                    Map<String, Object> help = ExecUtil.mapOf(mi.get("help_city"));
                    long helpTarget = help == null ? 0L : ExecUtil.longOf(help.get("target"));
                    if (mid > 0 && ty == 5L && st == 3L) garrisons.add(mi);
                }
                int done = 0, fail = 0;
                for (Map<String, Object> march : garrisons) {
                    long mid = ExecUtil.longOf(march.get("id"));
                    Map<String, Object> help = ExecUtil.mapOf(march.get("help_city"));
                    long helpTarget = ExecUtil.longOf(help.get("target"));
                    if (targetUid > 0 && helpTarget != targetUid) continue;
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", mid);
                    GameMessage r = engine.request("req_world_march_off_work", a, 8);
                    int c = codeOf(r);
                    log("off_work 行军" + mid + "（援助 uid" + helpTarget + "）code=" + c);
                    if (c == 0) done++; else fail++;
                }
                log("撤防探针完成：成功 " + done + "，失败 " + fail);
            } catch (Throwable t) {
                log("撤防探针异常: " + brief(t));
            }
        });
    }

    public static void moveCity(Context ctx, String sessionKey) {
        init(ctx);
        runAsync(() -> {
            try {
                RoleSessionKeeper.LiveSession f = pick(ctx, sessionKey);
                if (f == null) { log("迁城探针：无在线会话"); return; }
                Map<String, Object> init = f.engine.notifyContent("notify_world_player_init");
                Map<String, Object> cp = ExecUtil.mapOf(init == null ? null : init.get("city_point"));
                long x = ExecUtil.longOf(cp.get("x")), y = ExecUtil.longOf(cp.get("y"));
                if (x <= 0) { log("未取得当前城坐标（city_point 空），链终止"); return; }
                log("当前城 (" + x + "," + y + ")，尝试迁城到附近 (+1,0)");
                // 协议族考证（20261017 schema dump）：普通世界=req_world_move_city(5604)；
                // req_arsenal_league_move_city 为兵工厂联盟模式专属
                String[][] candidates = {{"x", "y"}, {"pos_x", "pos_y"}};
                GameMessage last = null;
                for (String[] c : candidates) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("point", ExecUtil.args(c[0], x + 1, c[1], y));
                    last = f.engine.request("req_world_move_city", args);
                    log("req_world_move_city [" + c[0] + "," + c[1] + "] code=" + codeOf(last));
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

    /** 探针本地编队构建（带逐层诊断）：英雄取 hero_arranges/hero_init，士兵取库存前三 Tier1。 */
    private static Map<String, Object> buildFormationProbe(WjdrEngine engine) {
        List<Long> heroes = new ArrayList<>();
        // 双形状自适应：sproto map 字段解码可能是 Map{"1.0":{..}} 也可能是 List[{k,v}]，两种都吃
        Map<String, Object> arrange = engine.notifyContent("notify_battle_hero_arrange_data");
        for (Map<String, Object> m : collectMaps(arrange, "hero_arranges")) {
            for (Object h : collectAny(m.get("fight_heros"))) {
                long id = ExecUtil.longOf(h);
                if (id > 0 && !heroes.contains(id)) heroes.add(id);
            }
        }
        log("诊断①hero_arranges 英雄数=" + heroes.size());
        if (heroes.isEmpty()) {
            Map<String, Object> hiC = engine.notifyContent("notify_hero_init");
            for (Map<String, Object> m : collectMaps(hiC, "data")) {
                long id = ExecUtil.longOf(m.get("id"));
                if (id > 0) heroes.add(id);
            }
            heroes.sort((a, b) -> Long.compare(b, a));
            log("诊断②hero_init 兜底英雄数=" + heroes.size());
        }
        if (heroes.isEmpty()) return null;
        while (heroes.size() > 3) heroes.remove(heroes.size() - 1);

        // 兵力：Tier1（10001/20001/30001）库存按 40/30/30 分配（FieldTasks 同款）
        Map<Long, Long> soldier = new LinkedHashMap<>();
        Map<String, Object> si = engine.notifyContent("notify_soldier_init");
        for (Map<String, Object> m : collectMaps(si, "soldier")) {
            soldier.put(ExecUtil.longOf(m.get("id")), ExecUtil.longOf(m.get("count")));
        }
        // 最轻量合法编队：英雄 ×N + 1 个兵（用户实证：不带兵不能出征，最少 1 兵）
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        Long bestId = null;
        long bestCnt = 0;
        for (Map.Entry<Long, Long> e : soldier.entrySet()) {
            if (e.getValue() > bestCnt) { bestCnt = e.getValue(); bestId = e.getKey(); }
        }
        log("诊断③兵力池=" + soldier.size() + " 种，最大堆 id=" + bestId + " x" + bestCnt);
        if (bestId != null && bestCnt > 0) {
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("k", bestId);
            kv.put("v", 1L);
            soldierPairs.add(kv);
        }
        List<Map<String, Object>> heroPairs = new ArrayList<>();
        for (long h : heroes) {
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("k", h);
            kv.put("v", 1L);
            heroPairs.add(kv);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", heroPairs);
        f.put("soldier", soldierPairs);
        return f;
    }


    /** 双形状收集：v 可能是 List[{..}] 或 Map{key:{..}}（sproto map 字段两种解码形态都吃）。 */
    private static List<Map<String, Object>> collectMaps(Object v, String preferredKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) if (o instanceof Map) out.add((Map<String, Object>) o);
            return out;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            Object pref = m.get(preferredKey);
            if (pref instanceof Map) {
                for (Object o : ((Map<?, ?>) pref).values()) if (o instanceof Map) out.add((Map<String, Object>) o);
                return out;
            }
            if (pref instanceof List) {
                for (Object o : (List<?>) pref) if (o instanceof Map) out.add((Map<String, Object>) o);
                return out;
            }
            for (Object o : m.values()) if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    /** 双形状展开：List 直返；Map 取 values。 */
    private static List<Object> collectAny(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List) {
            out.addAll((List<?>) v);
        } else if (v instanceof Map) {
            out.addAll(((Map<?, ?>) v).values());
        }
        return out;
    }

    private static Map<String, Object> searchFar(WjdrEngine engine) throws Exception {
        // 野怪点（mapobj_type=3，狩猎行军 type=3）：本区资源矿已被自动化扫空（2872=无空矿，14:23 实证），
        // 野怪常在；搜索/发射形状与 FieldTasks.hunt 完全一致
        for (int level = 6; level >= 1; level--) {
            GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                    "mapobj_type", 3L, "level_min", level, "level_max", level));
            if (!ExecUtil.ok(rsp)) continue;
            Map<String, Object> content = rsp.getContent();
            Object point = content.get("point");
            if (point instanceof Map) {
                Map<?, ?> p = (Map<?, ?>) point;
                if (p.containsKey("x") && p.containsKey("y")) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> en : p.entrySet()) out.put(String.valueOf(en.getKey()), en.getValue());
                    return out;
                }
            }
            for (String k : new String[]{"points", "data", "list"}) {
                for (Map<String, Object> p : ExecUtil.mapsOf(content.get(k))) {
                    if (p.containsKey("x") && p.containsKey("y")) return p;
                }
            }
        }
        return null;
    }
    /** 自身在途行军：marchId → endtime（秒）。 */
    private static Map<Long, Long> selfMarches(WjdrEngine engine) {
        Map<Long, Long> out = new LinkedHashMap<>();
        Map<Long, long[]> full = selfMarchesTyped(engine);
        for (Map.Entry<Long, long[]> e : full.entrySet()) out.put(e.getKey(), e.getValue()[0]);
        return out;
    }

    /** 自查在途行军：marchId → [endtime秒, type]（state 2/4 才收）。 */
    private static Map<Long, long[]> selfMarchesTyped(WjdrEngine engine) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return out;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return out;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            long now = System.currentTimeMillis() / 1000L;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail.get(k))) {
                    long id = ExecUtil.longOf(march.get("id"));
                    long end2 = ExecUtil.longOf(march.get("endtime"));
                    long st = ExecUtil.longOf(march.get("state"));
                    long ty = ExecUtil.longOf(march.get("type"));
                    if (id > 0 && end2 > now && (st == 2 || st == 4)) out.put(id, new long[]{end2, ty});
                }
                if (!out.isEmpty()) break;
            }
        } catch (Throwable ignored) {
        }
        return out;
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
            return ExecUtil.marchId(msg);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int codeOf(GameMessage msg) {
        try {
            return (int) ExecUtil.codeOf(msg);
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

    /** 等会话回到 ONLINE（超时断连后的指数退避重连 ≥8s）。 */
    private static boolean waitOnline(WjdrEngine engine, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) {
                sleep(500);
                return true;
            }
            sleep(300);
        }
        return false;
    }

    private static String brief(Throwable t) {
        String s = String.valueOf(t);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }
}
