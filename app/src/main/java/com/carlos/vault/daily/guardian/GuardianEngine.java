package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;
import com.carlos.vault.daily.RoleSessionKeeper;
import com.carlos.vault.wjdr.WjdrEngine;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护盟友引擎（P2：检测层 dry_run）。
 *
 * 职责（design/守护盟友-自动驻防系统设计-20261015.md §3/§4，P2 里程碑）：
 *   - 挂全部在线会话，解析世界态势推送（P1 实证：敌对盟友的行军走
 *     notify_world_alliance_situation_*（to_marchs），一般世界行军走 notify_world_situation_info_*）
 *   - 识别"指向本盟盟友城的侦查/进攻行军" → Threat 登记（owner/type/坐标/兵力/ETA/state）
 *   - 规则评估（R1）：攻击力阈值 + ETA 上限 + 目标须为盟友城
 *   - dry_run：只更新状态行 + 落 files/guardian_log.txt，不发出任何游戏指令
 *
 * state 语义（P1 实证）：2=在途出征 3=已到达（战斗/采集）4=回程；endtime>now 且 state∈{2,4} 才可加速/在途。
 * 行军 type：1=侦查 2=进攻 3=狩猎 4=采集 5=驻防(增援)。
 *
 * P3 接入点：LaunchScheduler（intercept 子开关开启后，QUALIFIED 威胁触发卡秒驻防）。
 */
public final class GuardianEngine {

    public static final String FN_ID = "guardian";

    private static final ConcurrentHashMap<WjdrEngine, Boolean> ATTACHED = new ConcurrentHashMap<>();
    /** 在途敌情：kid:marchId → Track。 */
    private static final ConcurrentHashMap<String, Track> THREATS = new ConcurrentHashMap<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    /** 最近一次战斗邮件到达时刻（ms）——无战报归因用（用户 20261017：后落地/敌撤离开战即无战报）。 */
    private static final java.util.concurrent.atomic.AtomicLong LAST_BATTLE_MAIL_AT =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** EXTREME 锁存（设计 §5.1）：owner → 锁存到期时刻。压缩≥3 次即锁 24h——其后续行军
     *  一律"检测即发射+立刻烧加速"（用户 20261017：3min 压缩到 2s 的必须防住）。 */
    private static final ConcurrentHashMap<Long, Long> EXTREME_LOCK = new ConcurrentHashMap<>();
    /** owner → 最近一次信号时刻（P4"惯犯近期活跃"判定：30 分钟窗口）。 */
    private static final ConcurrentHashMap<Long, Long> OWNER_SEEN = new ConcurrentHashMap<>();
    /** P4 预驻防合成威胁 id 序列（负数与真实 march id 永不冲突）。 */
    private static final java.util.concurrent.atomic.AtomicLong PRE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** P7 战争状态机（§6.11）：fpid:kid → 实例（按角色实例独立计分）。 */
    private static final ConcurrentHashMap<String, WarState> WAR = new ConcurrentHashMap<>();
    /** 人工强制开关（war.force_lock 子项）上次读取值：检测翻转触发 forceWartime。 */
    private static final ConcurrentHashMap<String, Boolean> WAR_FORCE_PREV = new ConcurrentHashMap<>();
    /** ALERT 预警级即让位的可选出征类任务（§6.11.2：停 P4 可选出征、采集限近郊）。 */
    private static final java.util.Set<String> ALERT_FIELD_TASKS = new java.util.LinkedHashSet<>(
            java.util.Arrays.asList("gather", "hunt", "intel", "bear", "adventure_sweep",
                    "act_mercenary", "act_truck", "act_feast", "act_icehunter"));

    public static boolean extremeLocked(long ownerUid) {
        Long until = EXTREME_LOCK.get(ownerUid);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 记录 owner 信号时刻（每次 handleMarch 都刷新，与预驻防开关无关）。 */
    static void noteOwnerSeen(long ownerUid, long nowMs) {
        if (ownerUid > 0) OWNER_SEEN.put(ownerUid, nowMs);
    }

    /** EXTREME 惯犯是否近期活跃（30 分钟内有信号）。 */
    static boolean ownerRecentlyActive(long ownerUid, long nowMs) {
        Long seen = OWNER_SEEN.get(ownerUid);
        return seen != null && nowMs - seen < GuardianPreGarrison.RECENT_ACTIVE_MS;
    }

    /** 一条威胁行军的跟踪记录。 */
    public static final class Track {
        public final long marchId;
        public final long kid;
        public final String fpid;       // 检测该威胁的账号（威胁期间该账号日常让位判定用）
        public final long owner;
        public final long type;          // 1=侦查 2=进攻
        public final long targetUid;     // 被打/被侦查的盟友 uid（0=未知）
        public final int x, y;
        public final long power;         // 攻方战力
        public final long firstSeenMs;
        public volatile long endServerSec;   // 当前预计到达（服务器秒）
        public volatile String stateText = "在途";
        public volatile long landedAtSec = 0;
        public volatile boolean forceQualified = false;   // 加速来袭已触发过驻防（防重复）
        public volatile int compressCount = 0;            // 敌方压缩事件数（R3 预判触发用）
        public volatile long lastCompressAt = 0;          // 最近一次压缩时刻（ms）
        public volatile boolean preGarrison = false;      // P4 预驻防合成威胁（非真实敌情）

        Track(long marchId, long kid, String fpid, long owner, long type, long targetUid, int x, int y,
              long power, long endServerSec) {
            this.marchId = marchId;
            this.kid = kid;
            this.fpid = fpid == null ? "" : fpid;
            this.owner = owner;
            this.type = type;
            this.targetUid = targetUid;
            this.x = x;
            this.y = y;
            this.power = power;
            this.endServerSec = endServerSec;
            this.firstSeenMs = System.currentTimeMillis();
        }

        public String kind() {
            return type == 1 ? "侦查" : type == 2 ? "进攻" : "行军#" + type;
        }
    }

    private GuardianEngine() {
    }

    // ------------------------------------------------------------------ 配置（FnParams p:guardian.* 子开关/策略）

    public static boolean watchOn(Context ctx, String fpid, long sid) {
        return FnParams.subOn(ctx, fpid, sid, FN_ID, "watch", false);
    }

    public static boolean interceptOn(Context ctx, String fpid, long sid) {
        return FnParams.subOn(ctx, fpid, sid, FN_ID, "intercept", false);
    }

    /** 攻方战力阈值（策略值即数字字符串；"0"=不限）。 */
    public static long powerMin(Context ctx, String fpid, long sid) {
        try {
            return Long.parseLong(FnParams.strategy(ctx, fpid, sid, FN_ID, "power_min", "0"));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 该角色是否有活跃威胁（驻防任务执行中）——日常任务应让位（设计 §6.10.5/E1-lite）。 */
    public static boolean threatActiveForRole(String fpid, long sid) {
        return com.carlos.vault.daily.guardian.GuardianLaunch.isActiveForRole(fpid, sid);
    }

    /**
     * 威胁期间让位判定（交接 §6.2）：该账号名下有在途敌情（从检测到解除）或驻防任务执行中。
     * 巨熊陷阱/狩猎/情报等派队类日常在窗口内跳过——既防请求队列饿死卡秒驻防，
     * 也防它们吃掉保留队列（手册 §4.1 保留池原则；采集已由 reserveQueues 单独限容）。
     */
    public static boolean threatBusyForRole(String fpid, long sid) {
        if (fpid != null) {
            for (Track t : THREATS.values()) {
                if (t.kid == sid && fpid.equals(t.fpid)) return true;
            }
        }
        return com.carlos.vault.daily.guardian.GuardianLaunch.isActiveForRole(fpid, sid);
    }

    /** 守护开启时为卡秒驻防预留的队列数（采集等日常不得占用）。 */
    public static int reserveQueues(Context ctx, String fpid, long sid) {
        if (interceptOn(ctx, fpid, sid) || watchOn(ctx, fpid, sid)) {
            // E3 战时池改组：6 队全部为驻防/支援预留（返回大数 → 采集可用容量清零）
            WarState ws = warStatePeek(fpid, sid);
            if (ws != null && ws.wartime()) return 99;
            return 2;
        }
        return 0;
    }

    // ------------------------------------------------------------------ P7 WarState（设计 §6.11）

    /** FN_ID war：战争状态参数（p:war.*，§11 参数表）。 */
    public static final String WAR_FN = "war";

    private static String warKey(String fpid, long sid) {
        return (fpid == null ? "" : fpid) + ":" + sid;
    }

    /** 取已有实例（不创建、不读配置；闸/状态行热路径用）。 */
    static WarState warStatePeek(String fpid, long sid) {
        return WAR.get(warKey(fpid, sid));
    }

    /** 战争状态实例（get-or-create + 热载 p:war.* 参数 + 进/出回调接线）。 */
    public static WarState warState(Context ctx, String fpid, long kid) {
        if (APP == null && ctx != null) init(ctx);
        String key = warKey(fpid, kid);
        WarState ws = WAR.get(key);
        if (ws == null) {
            synchronized (WAR) {
                ws = WAR.get(key);
                if (ws == null) {
                    ws = new WarState();
                    ws.setListener(new WarListener(fpid, kid));
                    WAR.put(key, ws);
                }
            }
        }
        ws.configure(warConfig(ctx, fpid, kid));
        return ws;
    }

    /** p:war.* 参数装配（§11 war.* 表；FnDetail war 条目同款语义）。 */
    static WarState.Config warConfig(Context ctx, String fpid, long sid) {
        WarState.Config c = new WarState.Config();
        c.auto = FnParams.subOn(ctx, fpid, sid, WAR_FN, "auto", true);
        c.scoutHard = FnParams.subOn(ctx, fpid, sid, WAR_FN, "scout_hard", true);
        c.shieldExit = FnParams.subOn(ctx, fpid, sid, WAR_FN, "shield_exit", true);
        c.quietExitMs = minutes(FnParams.strategy(ctx, fpid, sid, WAR_FN, "quiet_exit", "30"), 30);
        c.alertTh = (int) minutes(FnParams.strategy(ctx, fpid, sid, WAR_FN, "alert_th", "25"), 25);
        c.feintBurstCount = (int) minutes(FnParams.strategy(ctx, fpid, sid, WAR_FN, "feint_burst", "5"), 5);
        c.durationAlertMs = minutes(FnParams.strategy(ctx, fpid, sid, WAR_FN, "duration_alert", "120"), 120)
                * 60_000L;
        c.allowlist = parseList(FnParams.strategy(ctx, fpid, sid, WAR_FN, "allowlist", "battle_shield,medic"));
        c.friendlyUids = parseUids(FnParams.strategy(ctx, fpid, sid, WAR_FN, "friendly_uids", ""));
        return c;
    }

    /** 60s 节拍（挂载清扫器顺带驱动）：热载参数 + 人工开关翻转 + 退出判定 + 状态行刷新。 */
    public static void warTick(Context ctx, String fpid, long kid) {
        try {
            if (fpid == null) return;
            WarState ws = warState(ctx, fpid, kid);
            boolean force = FnParams.subOn(ctx, fpid, kid, WAR_FN, "force_lock", false);
            String key = warKey(fpid, kid);
            Boolean prev = WAR_FORCE_PREV.get(key);
            long now = System.currentTimeMillis();
            if (force && !Boolean.TRUE.equals(prev)) {
                ws.forceWartime(true, "功能页强制", now);
                event(ctx, fpid, kid, "⚔️人工强制战争态（p:war.force_lock 开）");
            } else if (!force && Boolean.TRUE.equals(prev) && ws.manualLocked()) {
                ws.forceWartime(false, "功能页解除", now);
                event(ctx, fpid, kid, "☠️人工解除战争态（p:war.force_lock 关）");
            }
            WAR_FORCE_PREV.put(key, force);
            ws.tick(now);
            FnParams.saveModText(ctx, fpid, kid, WAR_FN, "watch", ws.explain(now));
        } catch (Throwable ignored) {
        }
    }

    /** E1 战时任务闸（完整态）：null=放行；否则 SKIP 理由。WARTIME 看 allowlist，ALERT 停可选出征。 */
    public static String warGateNote(Context ctx, String fpid, long sid, String taskId) {
        if (fpid == null || WAR_FN.equals(taskId)) return null;   // war 自身=状态行任务，永不被自己的闸拦
        WarState ws = WAR.get(warKey(fpid, sid));
        if (ws == null) return null;
        if (ws.wartime()) {
            return ws.dailyTaskGated(taskId)
                    ? "战时暂停（" + reasonText(ws) + "；war.allowlist 外任务）" : null;
        }
        if (ws.alert() && taskId != null && ALERT_FIELD_TASKS.contains(taskId)) {
            return "战争预警：可选出征暂停（采集限近郊）";
        }
        return null;
    }

    /** 威胁/预警期间让位（FieldTasks bear/hunt/intel 等占队列任务用，无 Context 热路径）。 */
    public static boolean warBusyForRole(String fpid, long sid) {
        WarState ws = warStatePeek(fpid, sid);
        return ws != null && (ws.wartime() || ws.alert());
    }

    /** 供测试/上层探针：直接喂一条信号。 */
    public static void warFeed(String fpid, long sid, WarState.Signal s) {
        WarState ws = warStatePeek(fpid, sid);
        if (ws != null) ws.onSignal(s);
    }

    /** 日常轮 war 任务的输出：驱动一次 warTick（热载/退出判定）并返回当前态势文本。 */
    public static String warSummaryText(Context ctx, String fpid, long kid) {
        if (ctx == null) return "战争状态监测未就绪";
        try {
            warTick(ctx, fpid, kid);
            WarState ws = warState(ctx, fpid, kid);
            return ws.explain(System.currentTimeMillis());
        } catch (Throwable t) {
            return "战争状态不可用：" + t;
        }
    }

    private static String reasonText(WarState ws) {
        return ws.manualLocked() ? "人工锁定" : ws.reason() == null ? "H 触发" : ws.reason().text;
    }

    private static long minutes(String v, long def) {
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static java.util.Set<String> parseList(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (csv != null) {
            for (String p : csv.split("[,，]")) {
                if (!p.trim().isEmpty()) out.add(p.trim());
            }
        }
        return out.isEmpty() ? WarState.Config.defaultAllowlist() : out;
    }

    private static java.util.Set<Long> parseUids(String csv) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (csv != null) {
            for (String p : csv.split("[,，]")) {
                try {
                    long u = Long.parseLong(p.trim());
                    if (u > 0) out.add(u);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 战争态进/出/提醒回调：E2 召回 + E5 状态行 + E6 guardian 日志留痕。 */
    private static final class WarListener extends WarState.ListenerAdapter {
        private final String fpid;
        private final long kid;

        WarListener(String fpid, long kid) {
            this.fpid = fpid == null ? "" : fpid;
            this.kid = kid;
        }

        @Override public void onWarEnter(WarState.Trigger t, String detail) {
            Context c = app();
            if (c == null) return;
            event(c, fpid, kid, "⚔️进入战争态（" + t.text + "）" + detail);
            // E2：在途非战斗行军全部召回（免费召回先于开战；与既有威胁召回幂等叠加）
            try {
                com.carlos.vault.daily.guardian.GuardianLaunch.wartimeRecall(c, fpid, kid);
            } catch (Throwable ignored) {
            }
        }

        @Override public void onWarExit(String reason, String detail) {
            Context c = app();
            if (c == null) return;
            // X1/X4：退出原因 + 敌情板归档；任务闸重开后当日任务由 45min 补跑机制自动补齐（X2）
            event(c, fpid, kid, "☠️退出战争态（" + reason + "）" + detail);
            try {
                FnParams.saveModText(c, fpid, kid, WAR_FN, "watch", "平时（" + reason + "）");
            } catch (Throwable ignored) {
            }
        }

        @Override public void onNotice(String text) {
            Context c = app();
            if (c == null) return;
            event(c, fpid, kid, text);
        }
    }

    /** ETA 上限（秒；超过不构成即时威胁）。 */
    public static long etaMaxSec(Context ctx, String fpid, long sid) {
        try {
            return Long.parseLong(FnParams.strategy(ctx, fpid, sid, FN_ID, "eta_max", "1800"));
        } catch (Throwable t) {
            return 1800L;
        }
    }

    // ------------------------------------------------------------------ 挂载

    /** 会话登录后调用（RoleSessionKeeper，模式同 ProtoLogger/EnemyWatch）。幂等。 */
    public static void attach(Context ctx, RoleSessionKeeper.LiveSession fls) {
        try {
            if (fls.engine == null) return;
            if (ATTACHED.putIfAbsent(fls.engine, Boolean.TRUE) != null) return;
            final Context app = ctx.getApplicationContext();
            final long kid = fls.sid;
            final String fpid = fls.fpid;
            fls.engine.addMessageHook((proto, content) -> {
                try {
                    if (proto != null && (proto.startsWith("notify_world_alliance_situation")
                            || proto.startsWith("notify_world_situation"))) {
                        glog("hook收到 " + fpid + ":" + kid + " " + proto);
                    }
                    handle(app, fpid, kid, proto, content);
                } catch (Throwable t) {
                    glog("handle异常 " + proto + ": " + t);
                }
            });
            glog("已挂载 " + fls.fpid + ":" + fls.sid);
            // 孤儿驻防清扫（20261017"驻防完不召回队列"兜底）：挂载后 45s 起每 60s 扫一次
            final java.util.concurrent.ScheduledExecutorService sweeper =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "guardian-sweep-" + fpid + ":" + kid);
                        t.setDaemon(true);
                        return t;
                    });
            sweeper.scheduleWithFixedDelay(() -> {
                try {
                    warTick(app, fpid, kid);   // P7：war 参数热载/人工开关/静默-开盾退出判定
                    if (!interceptOn(app, fpid, kid) && !watchOn(app, fpid, kid)) return;
                    com.carlos.vault.daily.guardian.GuardianLaunch.sweepOrphans(app, fpid, kid);
                } catch (Throwable ignored) {
                }
            }, 45, 60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 推送处理

    /** 供发射器实时读取敌 ETA（kid:marchId 定位）。 */
    public static Track trackOf(long kid, long marchId) {
        return THREATS.get(kid + ":" + marchId);
    }

    /** 威胁表快照（P4 H2 计数/候选目标用）。 */
    static List<Track> tracksSnapshot() {
        return new ArrayList<>(THREATS.values());
    }

    /**
     * P4 预驻防：合成一条"常驻戒备威胁"（负 id、ETA=now+滞留窗）注册进威胁表，
     * 让发射链的 2878 重试/清扫器按既有威胁语义工作；到期由 hasThreatOn 的
     * EXPIRED 清理摘除，随后孤儿清扫器撤回驻防释放队列。
     */
    static Track newPreTrack(String fpid, long kid, Track from) {
        try {
            if (from == null || from.x <= 0) return null;
            Track pre = new Track(PRE_SEQ.decrementAndGet(), kid, fpid, from.owner, from.type,
                    from.targetUid, from.x, from.y, from.power,
                    System.currentTimeMillis() / 1000L + GuardianPreGarrison.DWELL_SEC);
            pre.preGarrison = true;
            THREATS.put(kid + ":" + pre.marchId, pre);
            return pre;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 该区服某盟友城当前是否有活跃威胁（清扫器判断驻防是否还需保留）。
     *  过期条目（endtime 已过 90s 仍没收到 del——断连丢推送等）先剔除，防死威胁堵住清扫。 */
    public static boolean hasThreatOn(long kid, long targetUid) {
        if (targetUid <= 0) return false;
        long nowS = System.currentTimeMillis() / 1000L;
        for (Map.Entry<String, Track> e : THREATS.entrySet()) {
            Track t = e.getValue();
            if (t.endServerSec + 90 < nowS) {
                THREATS.remove(e.getKey());   // EXPIRED：落地已久且 del 丢失
                continue;
            }
            if (t.kid == kid && t.targetUid == targetUid) return true;
        }
        return false;
    }

    /** 最近战斗邮件到达时刻（0=从未）——repatriateDelayed 无战报归因用。 */
    public static long lastBattleMailAt() {
        return LAST_BATTLE_MAIL_AT.get();
    }

    /** 供发射器感知威胁消失（敌召回/落地）。 */
    public static void dropTrack(long kid, long marchId) {
        THREATS.remove(kid + ":" + marchId);
    }

    private static void handle(Context app, String fpid, long kid, String proto, Map<String, Object> content) {
        if (content == null) return;
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;

        // 盟友坐标表喂食（本会话账号的联盟成员位置推送——按 fpid 隔离，多账号不同盟不互相污染）
        if ("notify_alliance_member_pos_info".equals(proto)) {
            Map<String, Object> ml = asMap(content.get("member_list"));
            if (ml != null) {
                for (Object v : ml.values()) {
                    Map<String, Object> p = asMap(v);
                    AllyMap.put(fpid, kid, num(p.get("uid")), num(p.get("x")), num(p.get("y")));
                }
            }
            return;
        }
        if ("notify_alliance_member_pos_update".equals(proto)) {
            Map<String, Object> p = asMap(content.get("member_pos"));
            if (p != null) AllyMap.put(fpid, kid, num(p.get("uid")), num(p.get("x")), num(p.get("y")));
            return;
        }

        boolean watch = watchOn(app, fpid, kid);
        switch (proto) {
            case "notify_world_alliance_situation_add":
            case "notify_world_alliance_situation_update":
            case "notify_world_alliance_situation_init":
                // init=登录/回连快照：不处理会漏掉重连窗口内在途的攻击（04:06 顶号 7 分钟实测教训）
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleMarch(app, fpid, kid, asMap(v), true, num(((Map<?, ?>) v).get("power")));
                        }
                    }
                }
                break;
            case "notify_world_situation_info_add":
            case "notify_world_situation_info_up": {
                Object marches = content.get("marches");
                if (marches instanceof Map) {
                    for (Object v : ((Map<?, ?>) marches).values()) {
                        if (!(v instanceof Map)) continue;
                        Map<String, Object> entry = asMap(v);
                        Object m = entry.get("march");
                        if (m instanceof Map) handleMarch(app, fpid, kid, asMap(m), false, num(entry.get("power")));
                    }
                } else if (marches instanceof List) {
                    for (Object v : (List<?>) marches) {
                        if (v instanceof Map) handleMarch(app, fpid, kid, asMap(v), false, num(((Map<?, ?>) v).get("power")));
                    }
                }
                break;
            }
            case "notify_world_alliance_situation_del": {
                for (String key : new String[]{"to_marchs", "marchs"}) {
                    Object arr = content.get(key);
                    if (arr instanceof List) {
                        for (Object v : (List<?>) arr) {
                            if (v instanceof Map) handleDel(app, fpid, kid, asMap(v));
                        }
                    }
                }
                break;
            }
            case "notify_player_march_update": {
                // 我方行军状态变化 → 维护驻防记录（20:51 死 marchId 发加速的根因防线）
                long mid = num(marchIdOfPlayerPush(content));
                if (mid > 0) {
                    Map<String, Object> ch = asMap(content.get("change"));
                    long st = ch == null ? 0 : num(ch.get("state"));
                    if (st > 0) com.carlos.vault.daily.guardian.GuardianLaunch.onOwnMarchState(kid, mid, st);
                }
                break;
            }
            case "notify_new_mail_syn": {
                // 驻防成败判定（用户 20261017 裁决：拿到驻防成功邮件才算成功，其余都算失败）。
                // 码表校准（遗留 #1/#4）：first_type→角色 + second_type→win/loss/unknown 映射，
                // 默认表 4/5=胜 6/7/8=败 来源=进攻战报（recon 模块分类，交接 20261017）——防守语义未校准。
                // 未收录码（如实测防守样本 second_type=1）不判成败、不触发撤防（撤防仅由敌情解除驱动，
                // 邮件从不触发撤防），写 GUARD_REPORT_UNKNOWN 日志 + 落 FnParams 待校准列表；
                // 覆盖通道=策略项 report_codes（JSON，GuardReportCodes 类注释有形状说明）。
                Map<String, Object> cover = asMap(content.get("mail_cover_info"));
                if (cover != null) {
                    GuardReportCodes.Mapping mapping = GuardReportCodes.load(app, fpid, kid);
                    long ft = num(cover.get("first_type"));
                    if (mapping.isBattleFirst(ft)) {
                        LAST_BATTLE_MAIL_AT.set(System.currentTimeMillis());
                        // P7 WarState：战斗结算确认 → H1 硬触发 + 软 +40（owner 未知=0，敌情板条目由行军信号建立）
                        WarState ws = warStatePeek(fpid, kid);
                        if (ws != null) {
                            ws.onBattleConfirmed(0, 0, "b" + (System.currentTimeMillis() / 60_000L),
                                    System.currentTimeMillis());
                        }
                        long st = num(cover.get("second_type"));
                        String v = mapping.verdictOf(ft, st);
                        if (GuardReportCodes.WIN.equals(v)) {
                            event(app, fpid, kid, "✅驻防成功（" + mapping.roleOf(ft) + " second_type=" + st + "）");
                        } else if (GuardReportCodes.LOSS.equals(v)) {
                            event(app, fpid, kid, "❌驻防失败（" + mapping.roleOf(ft) + " second_type=" + st + "）");
                        } else {
                            String raw = GuardReportCodes.clip(String.valueOf(cover), 160);
                            glog("GUARD_REPORT_UNKNOWN first=" + ft + " second=" + st + " raw=" + raw);
                            if (v == null) {
                                GuardReportCodes.recordUnknown(app, fpid, kid, ft, st, raw);
                            }
                            event(app, fpid, kid, "📮战斗邮件未收录码（first=" + ft + " second=" + st
                                    + (v == null ? "，已入待校准" : "，已映射 unknown")
                                    + "）——不判成败/不触发撤防");
                        }
                    }
                }
                break;
            }
            case "notify_player_march_del": {
                long mid = num(marchIdOfPlayerPush(content));
                if (mid > 0) com.carlos.vault.daily.guardian.GuardianLaunch.onOwnMarchEnded(kid, mid);
                break;
            }
            default:
                break;
        }
    }

    /** player march 推送的 march id 位置：update={id,...}，del={id,...}（顶层即有）。 */
    private static Object marchIdOfPlayerPush(Map<String, Object> content) {
        return content.get("id");
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    /** 新敌情入战争状态机（§7.31：盟友名单内 owner 与自家小号白名单不算敌情）。 */
    private static void feedWarNewTrack(Context app, String fpid, long kid, Track t,
                                        boolean qualified, long atMs) {
        try {
            if (t.owner <= 0) return;
            if (AllyMap.isAlly(fpid, kid, t.owner)) return;
            WarState ws = warState(app, fpid, kid);
            if (ws.isFriendlyUid(t.owner)) return;
            if (t.type == 1) {
                ws.onScout(t.marchId, t.owner, atMs);   // H0（scout_hard=false 自动降级软 +15）
            } else if (t.type == 2) {
                ws.onAttackMarch(t.marchId, t.owner, t.endServerSec, qualified, atMs);
                if (extremeLocked(t.owner)) {
                    ws.onExtremeMarch(t.marchId, t.owner, atMs);   // H3 极限压缩者在途
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleMarch(Context app, String fpid, long kid, Map<?, ?> m,
                                    boolean allyTargeted, long power) {
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;
        long id = num(m.get("id"));
        long owner = num(m.get("owner"));
        long type = num(m.get("type"));
        if (id <= 0 || owner <= 0) return;
        if (type != 1 && type != 2) return;   // P2 只关注侦查/进攻

        String tkey = kid + ":" + id;
        long end = num(m.get("endtime"));
        long nowS = System.currentTimeMillis() / 1000L;
        int[] xy = endpoint(m);
        long state = num(m.get("state"));
        long targetUid = targetUid(m);

        // 盟友目标判定：优先 atk_city.targetid/point 命中盟友表；world_situation 通用源用坐标匹配
        boolean allyHit = allyTargeted || (targetUid > 0 && AllyMap.isAlly(fpid, kid, targetUid))
                || (targetUid <= 0 && xy != null && AllyMap.match(fpid, kid, xy[0], xy[1]) > 0);
        if (!allyHit) return;

        Track t = THREATS.get(tkey);
        if (t == null) {
            if (end <= nowS) return;   // 已成历史
            t = new Track(id, kid, fpid, owner, type, targetUid, xy == null ? 0 : xy[0], xy == null ? 0 : xy[1],
                    power, end);
            THREATS.put(tkey, t);
            noteOwnerSeen(owner, System.currentTimeMillis());
            // E2 战时召回（用户 20261017 重申）：威胁一出现就召回全部在途采集（免费、先于开战）
            com.carlos.vault.daily.guardian.GuardianLaunch.wartimeRecall(app, fpid, kid);
            boolean intercept = interceptOn(app, fpid, kid);
            long pMin = powerMin(app, fpid, kid);
            long etaMax = etaMaxSec(app, fpid, kid);
            long etaSec = end - nowS;
            boolean qualified = intercept && etaSec <= etaMax && (pMin <= 0 || power >= pMin);
            // P7 WarState 喂食：H0 侦查即入 / 新进攻软 +30 / H2 并发 / H3 极限压缩者（§6.11.1）
            feedWarNewTrack(app, fpid, kid, t, qualified, nowMs());
            String verdict = evaluate(app, fpid, kid, power, end - nowS);
            event(app, fpid, kid, "🚨检测[" + t.kind() + "] fid" + owner + " → 盟友城(" + t.x + "," + t.y
                    + (targetUid > 0 ? " fid" + targetUid : "") + ") ETA " + (end - nowS) + "s（"
                    + hhmmss(end) + "）power≈" + power + " → " + verdict);
            if (qualified) {
                com.carlos.vault.daily.guardian.GuardianLaunch.onThreatQualified(app, fpid, kid, t);
            }
            // P4 预驻防：高威胁信号（H3 极限惯犯/H2 多路并发）出现 → 提前向关键盟友城派驻（默认关）
            com.carlos.vault.daily.guardian.GuardianPreGarrison.onThreatSignal(app, fpid, kid, t);
        } else {
            long prev = t.endServerSec;
            t.endServerSec = end;
            noteOwnerSeen(owner, System.currentTimeMillis());
            if (end < prev) {
                t.compressCount++;
                t.lastCompressAt = System.currentTimeMillis();
                if (t.compressCount >= 3 && !extremeLocked(owner)) {
                    EXTREME_LOCK.put(owner, System.currentTimeMillis() + 24 * 3600_000L);
                    event(app, fpid, kid, "🚨EXTREME 锁存 fid" + owner + "（压缩" + t.compressCount
                            + "次，24h 内其行军一律检测即发射+立刻加速）");
                }
                // P7 WarState：敌加减速 = 敌活跃（刷新敌情板静默计时）；EXTREME 锁存者压缩 → H3
                WarState ws = warStatePeek(fpid, kid);
                if (ws != null) {
                    ws.onAttackUpdated(id, end);
                    ws.onSignal(WarState.Signal.soft("U:m" + id + ":" + end, 0, owner,
                            t.type == 1 ? ThreatBoard.Kind.SCOUT : ThreatBoard.Kind.ATTACK, nowMs()));
                    if (extremeLocked(owner)) ws.onExtremeMarch(id, owner, nowMs());
                }
                event(app, fpid, kid, "⚡加速来袭[" + t.kind() + "] fid" + owner + " 行军" + id
                        + " 剩余 " + (prev - nowS) + "s → " + (end - nowS) + "s（烧道具了）");
                // 加速来袭 = 敌方真实投入，绕过战力阈值直接触发驻防（公理：防御先于一切）
                if (interceptOn(app, fpid, kid) && !t.forceQualified) {
                    t.forceQualified = true;
                    com.carlos.vault.daily.guardian.GuardianLaunch.onThreatQualified(app, fpid, kid, t);
                }
                // R3 动态同步：我方驻防已在途时，敌方再加速 → 加速我方驻防保持先至（带 Track 预判触发）
                if (interceptOn(app, fpid, kid)) {
                    final GuardianEngine.Track ft = t;
                    final long fend = end;
                    final Context fapp = app;
                    final String ffpid = fpid;
                    final long fkid = kid;
                    Thread r3 = new Thread(() ->
                            com.carlos.vault.daily.guardian.GuardianLaunch.raceAccelerate(
                                    fapp, ffpid, fkid, ft.targetUid, id, fend, ft, false),
                            "guardian-r3");
                    r3.setDaemon(true);
                    r3.start();   // 异步：加速链有阻塞请求，绝不能卡推送处理线程
                }
            }
        }
    }

    private static void handleDel(Context app, String fpid, long kid, Map<?, ?> m) {
        if (!watchOn(app, fpid, kid) && !interceptOn(app, fpid, kid)) return;
        long id = num(m.get("id"));
        String tkey = kid + ":" + id;
        Track t = THREATS.remove(tkey);
        if (t == null) return;
        long nowS = System.currentTimeMillis() / 1000L;
        long remain = t.endServerSec - nowS;
        // P6 常驻戒备（hold_garrison_min>0）：EXTREME 惯犯/本次遭遇压缩≥3 的威胁结束后武装，
        // 下面两条撤防路径（含 sweepOrphans 兜底）统一经 GuardianHold.shouldHold 让位
        long armedUntil = com.carlos.vault.daily.guardian.GuardianHold.armIfNeeded(
                app, fpid, kid, t.targetUid, t.owner, t.compressCount);
        if (armedUntil > 0) {
            event(app, fpid, kid, "🛡常驻戒备开始：fid" + t.owner + " 为 EXTREME 惯犯（压缩 "
                    + t.compressCount + " 次），fid" + t.targetUid + " 城驻防保留 "
                    + com.carlos.vault.daily.guardian.GuardianHold.holdGarrisonMin(app, fpid, kid)
                    + " 分钟（敌撤/战报出不撤防，期间新威胁由在位驻防承接）");
        }
        // P7 WarState：佯攻（召回解除，7.2 口径 remain>2s）→ H4 计数+软 +10；落地 → H1 硬触发
        WarState ws = warStatePeek(fpid, kid);
        if (ws != null && !AllyMap.isAlly(fpid, kid, t.owner) && !ws.isFriendlyUid(t.owner)) {
            if (t.type == 2) {
                ws.onAttackEnded(t.marchId, t.owner, t.targetUid, remain > 2, nowMs());
            } else {
                ws.onScoutEnded(t.marchId);
            }
        }
        // 撤回条件（用户 20261016 裁决）：①敌方召回 → 立即撤；②敌方落地 → 等战斗结算拿到
        // 驻防成败战报（约 75s）再撤——绝不在战斗进行中撤防（驻防无条件公理）
        if (interceptOn(app, fpid, kid) && t.targetUid > 0) {
            try {
                if (remain > 2) {
                    com.carlos.vault.daily.guardian.GuardianLaunch.repatriate(app, fpid, kid, t.targetUid);
                } else {
                    com.carlos.vault.daily.guardian.GuardianLaunch.repatriateDelayed(
                            app, fpid, kid, t.targetUid, 10_000L);
                }
            } catch (Throwable ignored) {
            }
        }
        String how = remain > 2 ? "召回解除（距落地 " + remain + "s）" : "已落地";
        event(app, fpid, kid, "✔敌情解除[" + t.kind() + "] fid" + t.owner + " 行军" + id + " " + how);
    }

    // ------------------------------------------------------------------ 规则评估（R1）

    private static String evaluate(Context app, String fpid, long kid, long power, long etaSec) {
        boolean intercept = interceptOn(app, fpid, kid);
        long pMin = powerMin(app, fpid, kid);
        long etaMax = etaMaxSec(app, fpid, kid);
        if (etaSec > etaMax) return "观察（ETA 超窗口）";
        if (pMin > 0 && power < pMin) return "观察（战力低于阈值）";
        return intercept ? " QUALIFIED（P3 接线后自动卡秒驻防）" : "dry_run（拦截未开启，仅告警）";
    }

    // ------------------------------------------------------------------ 状态与日志

    private static void event(Context app, String fpid, long kid, String line) {
        glog(fpid + ":" + kid + " " + line);
        try {
            // 状态行（功能卡片展示）；同时保留最近事件在文本里
            FnParams.saveModText(app, fpid, kid, FN_ID, "watch", line);
        } catch (Throwable ignored) {
        }
        synchronized (RECENT) {
            RECENT.addFirst(stamp() + " " + line);
            while (RECENT.size() > 30) RECENT.removeLast();
        }
    }

    /** 功能卡片状态行（含汇总 + P7 战争状态）。 */
    public static String statusLine(Context ctx, String fpid, long kid) {
        int n = 0;
        for (Track t : THREATS.values()) if (t.kid == kid) n++;
        String hold = com.carlos.vault.daily.guardian.GuardianHold.holdStatusText(
                fpid, kid, System.currentTimeMillis());
        String last = null;
        synchronized (RECENT) {
            for (String s : RECENT) {
                if (s.contains(":" + kid + " ")) { last = s; break; }
            }
        }
        String line = "监测中 · 当前威胁 " + n + " · 盟友坐标 " + AllyMap.size(fpid, kid) + " 位"
                + (hold.isEmpty() ? "" : " · " + hold);
        WarState ws = warStatePeek(fpid, kid);
        if (ws != null && ws.state() != WarState.State.NORMAL) {
            line += "\n" + ws.explain(System.currentTimeMillis());
        }
        return line + (last == null ? "" : "\n" + last);
    }

    /** 日常轮的状态刷新任务输出（无游戏动作）。 */
    public static String summary(Context ctx, String fpid, long kid) {
        return statusLine(ctx, fpid, kid);
    }

    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void glog(String line) {
        try {
            Context c = APP;
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "guardian_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(stamp() + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile Context APP;

    private static Context app() {
        return APP;
    }

    /** App 上下文注入（RoleSessionKeeper 附挂时）。 */
    public static void init(Context ctx) {
        if (APP == null && ctx != null) APP = ctx.getApplicationContext();
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private static String hhmmss(long serverSec) {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(serverSec * 1000L));
    }

    // ------------------------------------------------------------------ 解析工具（双形状自适应，P1 实证）

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (Throwable t) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static int[] endpoint(Map<?, ?> m) {
        try {
            Object p = m.get("endpoint");
            if (p instanceof Map) {
                return new int[]{(int) num(((Map<?, ?>) p).get("x")), (int) num(((Map<?, ?>) p).get("y"))};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 行军目标 uid：to_marchs 形状优先 atk_city.targetid，其次 target.map_city.uid。 */
    private static long targetUid(Map<?, ?> m) {
        try {
            Object atk = m.get("atk_city");
            if (atk instanceof Map) return num(((Map<?, ?>) atk).get("targetid"));
            Object target = m.get("target");
            if (target instanceof Map) {
                Object city = ((Map<?, ?>) target).get("map_city");
                if (city instanceof Map) return num(((Map<?, ?>) city).get("uid"));
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
