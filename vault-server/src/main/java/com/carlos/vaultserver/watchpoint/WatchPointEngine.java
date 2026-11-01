package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionKeeper;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地图观察点（服务器侧，2026-10-27 MVP）。
 *
 * <p>协议事实（K888 实扫 2026-10-27 验证，见 design/观察点实测-20261027/README.md）：
 * <ul>
 *   <li>c2s {@code req_world_map_info}：发 55×55 矩形（≤55），session 约定 1e8~4e8，只发不等；</li>
 *   <li>s2c {@code notify_world_mapobjs}：{@code session/datalist[world_mapobj]/end_flag/kid/lod}，城池 type=1；</li>
 *   <li>s2c {@code notify_world_marchs}：{@code session/datalist[world_march]/end_flag}；行军 type 1 侦查/2 进攻/
 *       5 驻防/99 集结随从/102 集结主体 等，带 startpoint/endpoint/endtime；</li>
 *   <li>实测 96.3% 行军同时出现在起点格与终点格（平均 2.5 格）→ 盯敌方城格可在出兵后秒级发现并拿到目标坐标。</li>
 * </ul>
 *
 * <p>职责：
 * <ol>
 *   <li><b>监视</b>：对每个被监视敌方城（默认 Hey 的 5 个 80 级炉子）每 3–5s 查其 55×55 格，
 *       看到从该格出发、owner 命中监视 uid 的 PvP 行军 → 企业微信告警（谁 → 目标坐标/城、行为、ETA、兵力）；</li>
 *   <li><b>保护</b>：对 7 个友盟聚集地（熊坑）每 20–30s 查保护矩形，终点落在矩形内且非友军的 PvP 行军 → 来袭告警；</li>
 *   <li><b>迁城跟踪</b>：监视目标连续 2 轮完整查询消失 → 触发整图扫描（484 格，实测约 16s）按 uid 定位新城，
 *       更新监视矩形并告警。</li>
 * </ol>
 *
 * <p><b>只发告警，不发日报/汇总</b>（用户 2026-10-27 要求）。企业微信通道复用 {@code vault.json} 的 watch 配置
 * （webhookUrl / wecomEnabled / 静默时段）；无 webhook 时只落日志。默认关闭，需 {@code VAULT_WATCHPOINT=on}。
 */
public final class WatchPointEngine {

    /** 开关环境变量：on/1/true 启用（与文件 enabled 同时满足）。 */
    public static final String ENV_ENABLED = WatchPointStore.ENV_ENABLED;
    private static final String LOG = "观察点";
    private static final int MAP_MAX = 1199;
    private static final int TILE = 55;
    private static final int GRID = 22;
    private static final int SCAN_TILES = GRID * GRID;
    private static final int SESSION_MONITOR_BASE = 100_000_000;
    private static final int SESSION_PROTECT_BASE = 100_000_100;
    private static final int SESSION_SCAN_BASE = 100_500_000;
    private static final long RESPONSE_TIMEOUT_MS = 6000L;
    /** 批次 Q1：扫描完整性阈值（>=95% 才做告警判定）。 */
    private static final double SCAN_COMPLETE_RATIO = 0.95d;
    /** 批次 Q2：多角色协同 env 开关（off/0 强制单角色旧行为）。 */
    public static final String ENV_MULTI = "VAULT_WATCHPOINT_MULTI";
    /** 批次 Q3：攻击者跟踪 TTL/上限/未定位去重。 */
    private static final long ATTACKER_TRACK_TTL_MS = 2L * 3_600_000L;
    private static final int ATTACKER_TRACK_MAX = 200;
    private static final long ATTACKER_MISSING_DEDUPE_MS = 6L * 3_600_000L;
    /** 批次 B：跨区探测阈值（前 8 个 tile 无城 → 游戏不允许跨区查图）。 */
    static final int CROSS_KID_PROBE_TILES = 8;
    // 说明（C）：本引擎只有一个 store.kid；同一时间只能监听一个目标区服。
    // 跨区角色（crossKidRoleUids）只是借用其它区服会话去查 store.kid 的地图；
    // 不支持多个区服同时监听（需多实例/多配置，另立需求）。
    private static final long ALERT_DEDUPE_MS = 15L * 60_000L;
    private static final int SCAN_SEND_PER_TICK = 6;

    private enum Kind {MONITOR, PROTECT, SCAN, CROSS_PROBE}

    private static final class Ctx {
        final int session;
        long probeUid;
        final Kind kind;
        final WatchPointStore.Rect rect;
        final WjdrEngine engine;
        final long sentAt;
        volatile boolean ended;
        final Set<Long> cityUids = ConcurrentHashMap.newKeySet();

        Ctx(int session, Kind kind, WatchPointStore.Rect rect, WjdrEngine engine) {
            this.session = session;
            this.kind = kind;
            this.rect = rect;
            this.engine = engine;
            this.sentAt = System.currentTimeMillis();
        }
    }

    /** 供其它模块（守护盟友）按坐标查城：uid/aid/lv/nick/坐标。 */
    public static final class CityInfo {
        public final long uid;
        public final long aid;
        public final long lv;
        public final String nick;
        public final int x;
        public final int y;

        CityInfo(long uid, long aid, long lv, String nick, int x, int y) {
            this.uid = uid;
            this.aid = aid;
            this.lv = lv;
            this.nick = nick == null ? "" : nick;
            this.x = x;
            this.y = y;
        }
    }

    /** 共享实例（Main 安装）：供守护盟友按坐标反查城 uid/联盟。 */
    private static volatile WatchPointEngine SHARED;

    public static void install(WatchPointEngine engine) {
        SHARED = engine;
    }

    public static WatchPointEngine shared() {
        return SHARED;
    }

    /** 坐标 → 城（整图扫描缓存；未扫描到/未启用返回 null）。任意线程可调。 */
    public static CityInfo cityAt(int x, int y) {
        WatchPointEngine e = SHARED;
        if (e == null || x <= 0 || y <= 0) return null;
        City c = e.cityByPoint.get(x + "," + y);
        return c == null ? null : new CityInfo(c.uid, c.aid, c.lv, c.nick, c.x, c.y);
    }

    /** 坐标是否落在保护矩形（友盟聚集地）内；未启用/无矩形返回 false。 */
    public static boolean inProtectRect(int x, int y) {
        WatchPointEngine e = SHARED;
        if (e == null || x <= 0 || y <= 0) return false;
        try {
            for (WatchPointStore.Rect r : e.store.protectRects) {
                if (r != null && r.contains(x, y)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 保护矩形列表（只读快照；供诊断/守护决策）。 */
    public static java.util.List<WatchPointStore.Rect> protectRects() {
        WatchPointEngine e = SHARED;
        if (e == null) return java.util.Collections.emptyList();
        try {
            return new java.util.ArrayList<>(e.store.protectRects);
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /** 其它模块（守护坐标查询）观测到的城 → 写入共享缓存（aid 用于同联盟判定）。 */
    public static void observeCity(long uid, long aid, int x, int y, String nick) {
        WatchPointEngine e = SHARED;
        if (e == null || uid <= 0L || x <= 0 || y <= 0) return;
        e.seedCityForTest(uid, aid, x, y, nick);
    }

    /** 终点坐标 → [兵力合计, 行军数, 最近时刻]（观察点扫到的在途行军；供守护判断集结威胁）。 */
    private final Map<String, long[]> marchDest = new ConcurrentHashMap<>();
    static final long MARCH_DEST_TTL_MS = 180_000L;

    /** 终点(x,y) 的在途行军兵力合计：[troops, count, ageMs]；无数据返回 null。 */
    public static long[] marchTroopsTo(int x, int y) {
        WatchPointEngine e = SHARED;
        if (e == null || x <= 0 || y <= 0) return null;
        long[] v = e.marchDest.get(x + "," + y);
        if (v == null) return null;
        long age = System.currentTimeMillis() - v[2];
        if (age > MARCH_DEST_TTL_MS) return null;
        return new long[]{v[0], v[1], age};
    }

    /** 记录一条在途行军（终点兵力索引；供守护集结兵力判定）。 */
    private void indexMarchDestination(int ex, int ey, long troops, long endtime) {
        if (ex <= 0 || ey <= 0 || troops <= 0L) return;
        String key = ex + "," + ey;
        long now = System.currentTimeMillis();
        marchDest.compute(key, (k, old) -> {
            long sum = (old == null ? 0L : old[0]) + troops;
            long cnt = (old == null ? 0L : old[1]) + 1L;
            return new long[]{sum, cnt, now};
        });
        if (marchDest.size() > 4000) {
            marchDest.entrySet().removeIf(en -> now - en.getValue()[2] > MARCH_DEST_TTL_MS);
        }
    }

    /** 测试钩子：注入一个城市坐标（仅单测使用；生产由整图扫描填充）。 */
    public void seedCityForTest(long uid, long aid, int x, int y, String nick) {
        City c = new City();
        c.uid = uid;
        c.aid = aid;
        c.x = x;
        c.y = y;
        c.nick = nick == null ? "" : nick;
        cityByPoint.put(x + "," + y, c);
        if (uid > 0L) cityByUid.put(uid, c);
    }

    /** 测试钩子：注入一个保护矩形（仅单测使用）。 */
    public void seedProtectRectForTest(String name, int x1, int y1, int x2, int y2) {
        try {
            store.protectRects.add(new WatchPointStore.Rect(name, x1, y1, x2, y2));
        } catch (Throwable ignored) {
        }
    }

    /** 批次 Q3：被跟踪的攻击者。 */
    static final class TrackedAttacker {
        final long uid;
        final String nick;
        int x;
        int y;
        long atMs;

        TrackedAttacker(long uid, String nick, int x, int y, long atMs) {
            this.uid = uid;
            this.nick = nick == null ? "" : nick;
            this.x = x;
            this.y = y;
            this.atMs = atMs;
        }
    }

    private static final class City {
        long uid;
        long aid;
        long lv;
        String nick = "";
        int x;
        int y;
    }

    private static final class ScanState {
        boolean active;
        String reason = "";
        long startedAt;
        int nextTile;
        int ended;
        int cityCount;
    }

    private final SessionKeeper keeper;
    private final WatchPointStore store;
    private final VaultStore vaultStore;
    private final WeComNotifier notifier;
    private final Set<WjdrEngine> attached =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, Ctx> ctxBySession = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<Long, City> cityByUid = new ConcurrentHashMap<>();
    private final Map<String, City> cityByPoint = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> targetMisses = new ConcurrentHashMap<>();
    private final Map<String, Long> alerted = new ConcurrentHashMap<>();
    private final ExecutorService alertExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "watchpoint-alert");
        t.setDaemon(true);
        return t;
    });
    private final ScanState scan = new ScanState();

    private volatile boolean running;
    private Thread thread;
    /** 令牌桶：必须用 double 累积，否则 80ms tick 下 floor 恒为 0 → 首轮后永远发不出请求。 */
    private double tokens = 5.0d;
    private long lastRefillAt = System.currentTimeMillis();
    private long nextScanAt;
    private long storeMtime = -1L;
    private long lastStoreCheck;
    private long lastPruneAt;
    private long lastWecomAt;
    private final java.util.concurrent.atomic.AtomicLong statSentMonitor = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statSentProtect = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statOkMonitor = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statOkProtect = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statTimeouts = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statAlerts = new java.util.concurrent.atomic.AtomicLong();
    /** 2026-10-28 误报修复计数：自循环行军 / 目标联盟未知的来袭候选 / 随从告警静默。 */
    private final java.util.concurrent.atomic.AtomicLong statSelfIgnored = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statUnknownTargetIgnored = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statRallyFollowerIgnored = new java.util.concurrent.atomic.AtomicLong();
    /** 未知目标候选 INFO 去重（同 type/owner/坐标只记一次，防日志刷屏）。 */
    private final Set<String> unknownTargetLogged = ConcurrentHashMap.newKeySet();
    private long lastStatsAt;
    private volatile boolean lastTickHadEngine;
    private volatile java.util.List<String> lastEngineDescs = java.util.Collections.emptyList();
    private volatile int lastAccountCount;
    /** 批次 Q3：攻击者 uid → 最后已知城坐标（来袭/出兵告警时记录）。 */
    private final Map<Long, TrackedAttacker> trackedAttackers = new ConcurrentHashMap<>();
    /** 批次 Q3：未定位告警 6h 去重。 */
    private final Map<Long, Long> attackerMissingAt = new ConcurrentHashMap<>();
    /** 批次 Q1：每个保护矩形连续「完整扫描为空」次数。 */
    private final Map<String, Integer> rectEmptyStreak = new ConcurrentHashMap<>();
    /** 最近一条告警文本（单测断言用）。 */
    private volatile String lastAlertText = "";
    /** 批次 B：跨区角色探测状态（uid → probe；不支持的角色自动排除）。 */
    private final Map<Long, CrossProbe> crossProbes = new ConcurrentHashMap<>();
    private final Set<Long> crossUnavailable = ConcurrentHashMap.newKeySet();

    /** 跨区角色探测记录。 */
    static final class CrossProbe {
        final long uid;
        int tiles;
        int cities;
        Boolean supported;   // null=探测中；TRUE/FALSE=已判定
        boolean logged;

        CrossProbe(long uid) {
            this.uid = uid;
        }
    }
    private volatile boolean baselineDone;
    private volatile long startedAt;

    public WatchPointEngine(SessionKeeper keeper, WatchPointStore store,
                            VaultStore vaultStore, WeComNotifier notifier) {
        this.keeper = keeper;
        this.store = store;
        this.vaultStore = vaultStore;
        this.notifier = notifier;
    }

    public void start() {
        if (!WatchPointStore.envEnabled()) {
            FileLog.info("[{}] 未启用（{} 未设置 on/1）", LOG, ENV_ENABLED);
            return;
        }
        store.load();
        if (!store.enabled) {
            FileLog.info("[{}] 未启用（{} enabled=false）", LOG, store.file());
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        nextScanAt = store.fullScanIntervalMs > 0 ? startedAt + store.fullScanIntervalMs : Long.MAX_VALUE;
        thread = new Thread(this::loop, "watchpoint-engine");
        thread.setDaemon(true);
        thread.start();
        FileLog.info("[{}] 已启用：kid={} 监视目标={} 保护矩形={} 监视间隔={}ms 保护间隔={}ms 限速={}req/s"
                        + " 多角色={} 扫描超时={}ms",
                LOG, String.valueOf(store.kid), String.valueOf(store.monitorTargets.size()),
                String.valueOf(store.protectRects.size()), String.valueOf(store.monitorIntervalMs),
                String.valueOf(store.protectIntervalMs), String.valueOf(store.maxReqPerSec),
                String.valueOf(multiRoleEnabled()), String.valueOf(store.scanTimeoutMs));
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        alertExec.shutdownNow();
    }

    // ------------------------------------------------------------------ 主循环

    private void loop() {
        while (running) {
            try {
                maybeReloadStore();
                if (!WatchPointStore.envEnabled() || !store.enabled) {
                    Thread.sleep(1500L);
                    continue;
                }
                java.util.List<WjdrEngine> engines = pickEngines();
                lastTickHadEngine = !engines.isEmpty();
                updateEngineDesc(engines);
                if (engines.isEmpty()) {
                    // 扫描中会话掉线：超时后按已收到的数据收尾，避免卡在扫描态
                    if (scan.active && System.currentTimeMillis() - scan.startedAt
                            > Math.max(15_000L, store.scanTimeoutMs) + 15_000L) {
                        finishScan();
                    }
                    Thread.sleep(1000L);
                    continue;
                }
                if (!baselineDone && !scan.active) {
                    baselineDone = true;
                    startScan("启动基线");
                } else if (scan.active) {
                    scanTick(engines);
                } else {
                    pollTick(engines);
                }
                maybeLogStats();
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FileLog.warn("[{}] 循环异常：{}", LOG, String.valueOf(t));
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** 每 60s 打一条运维统计（证明轮询在跑：发送/回包/超时/告警）。 */
    private void maybeLogStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatsAt < 60_000L) return;
        lastStatsAt = now;
        FileLog.info("[{}] 运行统计：有会话={} 会话={} 监视发送={}(回包{} 超时{}) 保护发送={}(回包{}) 告警={} 扫描={} 城缓存={}"
                        + " 自循环忽略={} 未知目标忽略={} 随从静默={} 角色数={} 账号数={}",
                LOG, String.valueOf(lastTickHadEngine),
                String.join(",", lastEngineDescs),
                String.valueOf(statSentMonitor.get()), String.valueOf(statOkMonitor.get()),
                String.valueOf(statTimeouts.get()), String.valueOf(statSentProtect.get()),
                String.valueOf(statOkProtect.get()), String.valueOf(statAlerts.get()),
                scan.active ? "进行中" : "空闲", String.valueOf(cityByUid.size()),
                String.valueOf(statSelfIgnored.get()), String.valueOf(statUnknownTargetIgnored.get()),
                String.valueOf(statRallyFollowerIgnored.get()),
                String.valueOf(lastEngineDescs.size()), String.valueOf(lastAccountCount));
    }

    /** 批次 A/B：返回所有满足 kid/账号白名单/跨区探测且在线的引擎（multi off 只返回 1 个）。 */
    java.util.List<WjdrEngine> pickEngines() {
        java.util.List<WjdrEngine> online = new java.util.ArrayList<>();
        try {
            for (WjdrEngine e : keeper.onlineEngines()) online.add(e);
        } catch (Throwable ignored) {
        }
        java.util.Set<Long> crossUids = new java.util.LinkedHashSet<>(store.crossKidRoleUids);
        java.util.List<WjdrEngine> picked = selectEngines(online, store.fpid, store.fpids,
                store.allAccounts, store.kid, store.crossKid, crossUids, multiRoleEnabled(),
                crossUnavailable);
        // 2026-11-01：跨区角色先「探测」再参与分发——未验证前不占用扫描分片，避免整图完整性被拉低；
        // 探测用已知有城的矩形（观察点缓存/monitorTargets），比用地图左上角 8 格可靠。
        java.util.List<WjdrEngine> out = new java.util.ArrayList<>();
        for (WjdrEngine e : picked) {
            try {
                long sid = e.lastServerId();
                long uid = e.lastUid();
                if (sid == store.kid) {
                    out.add(e);
                    continue;
                }
                if (crossProbeSupported.contains(uid)) {
                    out.add(e);
                    continue;
                }
                if (!crossUnavailable.contains(uid)) startCrossProbe(e);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 跨区角色探测结果判定（纯函数）：有城=可用；连续 tries 次 0 城=不可用；否则待定。 */
    static Boolean crossProbeVerdict(int cities, int tries) {
        if (cities > 0) return Boolean.TRUE;
        return tries >= CROSS_PROBE_TRIES ? Boolean.FALSE : null;
    }

    private final Set<Long> crossProbeSupported = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> crossProbeTries = new ConcurrentHashMap<>();
    private final Set<Integer> crossProbeSessions = ConcurrentHashMap.newKeySet();
    static final int CROSS_PROBE_TRIES = 3;
    /** 跨区探测等待上限（ms）：无 end_flag 也按 0 城计一次（跨区图较大，给足 20s）。 */
    static final long CROSS_PROBE_TIMEOUT_MS = 20_000L;
    static final int SESSION_CROSS_PROBE_BASE = 100_900_000;

    /** 对跨区角色发起一次地图查询（已知有城的矩形），结果异步判定。 */
    private void startCrossProbe(WjdrEngine engine) {
        try {
            long uid = engine.lastUid();
            int session = SESSION_CROSS_PROBE_BASE + (int) Math.abs(uid % 1000L);
            if (!crossProbeSessions.add(session)) return;   // 已有在途探测
            WatchPointStore.Rect rect = probeRect();
            if (rect == null) {
                crossProbeSessions.remove(session);
                return;
            }
            Ctx ctx = new Ctx(session, Kind.CROSS_PROBE, rect, engine);
            ctx.probeUid = uid;
            ctxBySession.put(session, ctx);
            lastSent.put(session, System.currentTimeMillis());
            FileLog.info("[{}] 跨区探测发起 uid={} 区服={} 矩形({},{})-({},{})", LOG,
                    String.valueOf(uid), String.valueOf(engine.lastServerId()),
                    String.valueOf(rect.x1), String.valueOf(rect.y1),
                    String.valueOf(rect.x2), String.valueOf(rect.y2));
            sendRaw(engine, session, rect);
        } catch (Throwable t) {
            FileLog.warn("[{}] 跨区探测发起失败：{}", LOG, String.valueOf(t));
        }
    }

    /** 探测用矩形：优先取缓存里任一座城周边（必有城），否则 monitorTargets[0]。 */
    private WatchPointStore.Rect probeRect() {
        for (City c : cityByPoint.values()) {
            if (c != null && c.x > 0 && c.y > 0) return rectAround(c.x, c.y, 27);
        }
        if (!store.monitorTargets.isEmpty()) {
            WatchPointStore.Target t = store.monitorTargets.get(0);
            return rectAround(t.x, t.y, 27);
        }
        return null;
    }

    /**
     * 纯选择（单测可注入 online 列表）：
     * 同 kid 角色按账号规则过滤（fpid 兼容优先 → fpids 白名单 → allAccounts；allAccounts=false 时锁定首个账号）；
     * 跨区角色仅当 crossKid=true、uid ∈ crossUids 且未被标记不可用时纳入；按 fpid|sid|uid 去重。
     */
    static java.util.List<WjdrEngine> selectEngines(java.util.List<WjdrEngine> online,
                                                    String fpid, java.util.List<String> fpids,
                                                    boolean allAccounts, long kid,
                                                    boolean crossKid, java.util.Set<Long> crossUids,
                                                    boolean multi, java.util.Set<Long> excluded) {
        java.util.List<WjdrEngine> out = new java.util.ArrayList<>();
        if (online == null) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String want = fpid == null ? "" : fpid.trim();
        java.util.Set<String> white = new java.util.LinkedHashSet<>();
        if (fpids != null) {
            for (String f : fpids) if (f != null && !f.trim().isEmpty()) white.add(f.trim());
        }
        String lockedFpid = null;   // allAccounts=false 时锁首个账号
        for (WjdrEngine e : online) {
            if (e == null) continue;
            try {
                if (e.sessionState() == null || !WjdrEngine.STATE_ONLINE.equals(e.sessionState())) continue;
                long uid = e.lastUid();
                String efpid = e.lastFpid() == null ? "" : e.lastFpid();
                boolean sameKid = e.lastServerId() == kid;
                if (!sameKid) {
                    if (!crossKid) continue;
                    if (crossUids == null || !crossUids.contains(uid)) continue;
                    if (excluded != null && excluded.contains(uid)) continue;
                } else {
                    if (!want.isEmpty()) {
                        if (!want.equals(efpid)) continue;
                    } else if (!white.isEmpty()) {
                        if (!white.contains(efpid)) continue;
                    } else if (!allAccounts) {
                        if (lockedFpid == null) lockedFpid = efpid;
                        else if (!lockedFpid.equals(efpid)) continue;
                    }
                }
                String key = efpid + "|" + e.lastServerId() + "|" + uid;
                if (!seen.add(key)) continue;
                out.add(e);
                if (!multi) break;
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 跨区探测纯函数：有城→TRUE；≥8 tile 且 0 城→FALSE；未达阈值→null。 */
    static Boolean crossKidSupported(int cityCount, int tiles) {
        if (cityCount > 0) return Boolean.TRUE;
        if (tiles >= CROSS_KID_PROBE_TILES) return Boolean.FALSE;
        return null;
    }

    /** 每次扫描 tile 回包记录跨区角色探测；不支持则加入排除集合（自动降级）。 */
    private void noteCrossProbe(Ctx ctx) {
        if (ctx == null || ctx.engine == null) return;
        try {
            long uid = ctx.engine.lastUid();
            if (uid <= 0L || ctx.engine.lastServerId() == store.kid) return;
            int cities = ctx.cityUids.size();
            CrossProbe p = crossProbes.computeIfAbsent(uid, k -> new CrossProbe(uid));
            p.tiles++;
            p.cities += cities;
            Boolean s = crossKidSupported(p.cities, p.tiles);
            if (s == null) return;
            if (p.supported == null) {
                p.supported = s;
                if (Boolean.TRUE.equals(s)) {
                    FileLog.info("[{}] 跨区角色 uid={} kid={} 探测可用（已获 {} 城/{} tile）",
                            LOG, String.valueOf(uid), String.valueOf(ctx.engine.lastServerId()),
                            String.valueOf(p.cities), String.valueOf(p.tiles));
                } else {
                    crossUnavailable.add(uid);
                    FileLog.warn("[{}] 跨区角色 uid={} kid={} 探测不可用（前 {} tile 0 城）→ 自动排除，查询仍带 kid={}",
                            LOG, String.valueOf(uid), String.valueOf(ctx.engine.lastServerId()),
                            String.valueOf(p.tiles), String.valueOf(store.kid));
                }
            }
        } catch (Throwable ignored) {
        }
    }
    /** env VAULT_WATCHPOINT_MULTI=off/0 强制单角色；否则按 store.multiRole（默认 true）。 */
    static boolean multiRoleEnabled(boolean storeMulti) {
        try {
            String v = System.getenv(ENV_MULTI);
            if (v != null) {
                String s = v.trim();
                if ("off".equalsIgnoreCase(s) || "0".equals(s)
                        || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) return false;
            }
        } catch (Throwable ignored) {
        }
        return storeMulti;
    }

    private boolean multiRoleEnabled() {
        return multiRoleEnabled(store.multiRole);
    }

    private void updateEngineDesc(java.util.List<WjdrEngine> engines) {
        java.util.List<String> descs = new java.util.ArrayList<>();
        for (WjdrEngine e : engines) {
            try {
                descs.add(e.lastFpid() + ":" + e.lastServerId() + ":" + e.lastUid());
            } catch (Throwable ignored) {
            }
        }
        if (!descs.equals(lastEngineDescs)) {
            lastEngineDescs = descs;
            java.util.Set<String> accounts = new java.util.HashSet<>();
            for (WjdrEngine e : engines) {
                try { accounts.add(e.lastFpid()); } catch (Throwable ignored) { }
            }
            lastAccountCount = accounts.size();
            FileLog.info("[{}] 使用会话 {}（观察点扫描，角色数={}，账号数={}）",
                    LOG, String.join(",", descs), String.valueOf(descs.size()),
                    String.valueOf(lastAccountCount));
        }
        for (WjdrEngine e : engines) attach(e);
    }

    private void attach(WjdrEngine engine) {
        if (engine == null) return;
        synchronized (attached) {
            if (!attached.add(engine)) return;
        }
        engine.addMessageHook((proto, content) -> {
            try {
                onMessage(engine, proto, content);
            } catch (Throwable t) {
                FileLog.warn("[{}] 解析异常 {}：{}", LOG, proto, String.valueOf(t));
            }
        });
        FileLog.info("[{}] 已挂载会话钩子（{}）", LOG, engine.lastFpid() + ":" + engine.lastUid());
    }

    private void maybeReloadStore() {
        long now = System.currentTimeMillis();
        if (now - lastStoreCheck < 5000L) return;
        lastStoreCheck = now;
        try {
            Path f = store.file();
            long m = Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : -1L;
            if (m > 0 && m != storeMtime) {
                storeMtime = m;
                long prevInterval = store.fullScanIntervalMs;
                store.load();
                // 2026-10-31：在线改 fullScanIntervalMs（0→15min 等）时必须重排下次扫描，
                // 否则 nextScanAt 仍是 Long.MAX_VALUE → 周期扫描永不触发（迁城/攻击者跟踪失效）。
                if (store.fullScanIntervalMs != prevInterval) {
                    nextScanAt = store.fullScanIntervalMs > 0
                            ? System.currentTimeMillis() + store.fullScanIntervalMs
                            : Long.MAX_VALUE;
                    FileLog.info("[{}] 扫描间隔已更新：{}ms（下次扫描 {}）", LOG,
                            String.valueOf(store.fullScanIntervalMs),
                            store.fullScanIntervalMs > 0
                                    ? new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(nextScanAt))
                                    : "禁用");
                }
                FileLog.info("[{}] 配置已重载：监视={} 保护={} enabled={}",
                        LOG, String.valueOf(store.monitorTargets.size()),
                        String.valueOf(store.protectRects.size()), String.valueOf(store.enabled));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 常态轮询

    private void pollTick(java.util.List<WjdrEngine> engines) {
        if (engines == null || engines.isEmpty()) return;
        int n = engines.size();
        long now = System.currentTimeMillis();
        refillTokens(now);
        if (store.fullScanIntervalMs > 0 && now >= nextScanAt) {
            startScan("周期基线刷新");
            return;
        }
        // 监视目标（3–5s）
        for (int i = 0; i < store.monitorTargets.size(); i++) {
            WatchPointStore.Target t = store.monitorTargets.get(i);
            int session = SESSION_MONITOR_BASE + i;
            Long sent = lastSent.get(session);
            Ctx old = ctxBySession.get(session);
            boolean due = sent == null || now - sent >= store.monitorIntervalMs;
            if (!due || (old != null && !old.ended && now - old.sentAt < RESPONSE_TIMEOUT_MS)) continue;
            if (!takeToken()) return;
            WatchPointStore.Rect rect = rectAround(t.x, t.y, 27);
            send(engines.get(i % n), session, rect, Kind.MONITOR);
        }
        // 保护矩形（20–30s）
        for (int i = 0; i < store.protectRects.size(); i++) {
            WatchPointStore.Rect rect = store.protectRects.get(i);
            int session = SESSION_PROTECT_BASE + i;
            Long sent = lastSent.get(session);
            Ctx old = ctxBySession.get(session);
            boolean due = sent == null || now - sent >= store.protectIntervalMs;
            if (!due || (old != null && !old.ended && now - old.sentAt < RESPONSE_TIMEOUT_MS)) continue;
            if (!takeToken()) return;
            int mi = store.monitorTargets.size();
            send(engines.get((i + mi) % n), session, rect, Kind.PROTECT);
        }
        // 超时清理 + 告警去重表清理
        if (now - lastPruneAt > 60_000L) {
            lastPruneAt = now;
            for (Map.Entry<Integer, Ctx> e : ctxBySession.entrySet()) {
                Ctx c = e.getValue();
                if (!c.ended && now - c.sentAt > RESPONSE_TIMEOUT_MS * 2) {
                    ctxBySession.remove(e.getKey(), c);
                    statTimeouts.incrementAndGet();
                }
                // 2026-11-01：跨区探测无回包（常见于游戏拒绝跨区查图）→ 按 0 城计一次；
                // 连续 2 次 0 城即判“跨区不可用”，自动从分发中排除。
                if (c.kind == Kind.CROSS_PROBE && !c.ended
                        && now - c.sentAt > CROSS_PROBE_TIMEOUT_MS) {
                    ctxBySession.remove(e.getKey(), c);
                    crossProbeSessions.remove(c.session);
                    long uid = c.probeUid;
                    int tries = crossProbeTries.merge(uid, 1, Integer::sum);
                    Boolean verdict = crossProbeVerdict(c.cityUids.size(), tries);
                    if (Boolean.TRUE.equals(verdict)) {
                        crossProbeSupported.add(uid);
                        FileLog.info("[{}] 跨区角色 uid={} 探测可用（{} 城）", LOG,
                                String.valueOf(uid), String.valueOf(c.cityUids.size()));
                    } else if (Boolean.FALSE.equals(verdict)) {
                        crossUnavailable.add(uid);
                        FileLog.info("[{}] 跨区角色 uid={} 探测不可用（{} 次无回包/0 城）→ 排除", LOG,
                                String.valueOf(uid), String.valueOf(tries));
                    } else {
                        FileLog.info("[{}] 跨区角色 uid={} 探测超时（第 {} 次，将重试）", LOG,
                                String.valueOf(uid), String.valueOf(tries));
                    }
                }
            }
            alerted.entrySet().removeIf(en -> now - en.getValue() > ALERT_DEDUPE_MS * 2);
        }
    }

    private void refillTokens(long now) {
        long elapsed = now - lastRefillAt;
        if (elapsed <= 0) return;
        lastRefillAt = now;
        tokens = Math.min(10.0d, tokens + store.maxReqPerSec * elapsed / 1000.0d);
    }

    private boolean takeToken() {
        if (tokens < 1.0d) return false;
        tokens -= 1.0d;
        return true;
    }

    private void send(WjdrEngine engine, int session, WatchPointStore.Rect rect, Kind kind) {
        long now = System.currentTimeMillis();
        try {
            doSend(engine, session, rect, kind, now);
        } catch (Throwable t) {
            FileLog.warn("[{}] 发送失败 session={}：{}", LOG, String.valueOf(session), String.valueOf(t));
        }
    }

    private void doSend(WjdrEngine engine, int session, WatchPointStore.Rect rect, Kind kind, long now) {
        Ctx ctx = new Ctx(session, kind, rect, engine);
        ctxBySession.put(session, ctx);
        lastSent.put(session, now);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("startpoint", xy(rect.x1, rect.y1));
        args.put("endpoint", xy(rect.x2, rect.y2));
        args.put("kid", store.kid);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Collections.singletonList(args));
        payload.put("session", (long) session);
        payload.put("fulldata", Boolean.TRUE);
        payload.put("lod", 1L);
        engine.sendFireAndForget("req_world_map_info", payload, session);
        if (kind == Kind.MONITOR) statSentMonitor.incrementAndGet();
        else if (kind == Kind.PROTECT) statSentProtect.incrementAndGet();
    }

    private static Map<String, Object> xy(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    private static WatchPointStore.Rect rectAround(int x, int y, int radius) {
        int x1 = Math.max(0, Math.min(MAP_MAX - TILE + 1, x - radius));
        int y1 = Math.max(0, Math.min(MAP_MAX - TILE + 1, y - radius));
        return new WatchPointStore.Rect("monitor", x1, y1, x1 + (TILE - 1), y1 + (TILE - 1));
    }

    // ------------------------------------------------------------------ 收包解析

    private void onMessage(WjdrEngine engine, String proto, Map<String, Object> content) {
        if (proto == null || content == null) return;
        long sess = asLong(content.get("session"), -1L);
        Ctx ctx = sess > 0 ? ctxBySession.get((int) sess) : null;
        if (ctx == null) {
            // 2026-11-01 诊断：跨区探测无回包时，确认回包是否「session 不同/缺失」。
            if (("notify_world_mapobjs".equals(proto) || proto.startsWith("notify_world_mapobj"))
                    && System.currentTimeMillis() - unmatchedLogAt > 30_000L) {
                unmatchedLogAt = System.currentTimeMillis();
                FileLog.info("[{}] 未匹配地图回包：proto={} session={} kid={} keys={}", LOG, proto,
                        String.valueOf(sess), String.valueOf(content.get("kid")),
                        content.keySet());
            }
            return;
        }
        boolean mapobjs = "notify_world_mapobjs".equals(proto) || proto.startsWith("notify_world_mapobj_");
        boolean marchs = "notify_world_marchs".equals(proto) || proto.startsWith("notify_world_march_")
                || proto.startsWith("notify_mass_march");
        if (mapobjs) {
            Object dl = content.get("datalist");
            if (dl instanceof List) {
                for (Object o : (List<?>) dl) {
                    if (o instanceof Map) parseCity((Map<?, ?>) o, ctx.cityUids);
                }
            }
            Map<?, ?> obj = asMap(content.get("obj"));
            if (!obj.isEmpty()) parseCity(obj, ctx.cityUids);
            Map<?, ?> change = asMap(content.get("change"));
            if (!change.isEmpty()) parseCity(change, ctx.cityUids);
            if (truthy(content.get("end_flag"))) {
                ctx.ended = true;
                onComplete(ctx);
            }
        } else if (marchs) {
            Object dl = content.get("datalist");
            if (dl instanceof List) {
                for (Object o : (List<?>) dl) {
                    if (o instanceof Map) onMarch(ctx, (Map<?, ?>) o);
                }
            }
            Map<?, ?> single = asMap(content.get("march"));
            if (!single.isEmpty()) onMarch(ctx, single);
            if (truthy(content.get("end_flag")) && ctx.kind == Kind.SCAN) {
                ctx.ended = true;
            }
        }
    }

    private void parseCity(Map<?, ?> item, Set<Long> seenUids) {
        Map<?, ?> city = asMap(item.get("map_city"));
        if (city.isEmpty()) {
            city = asMap(item.get("city"));
        }
        if (city.isEmpty()) return;
        long uid = asLong(city.get("uid"), 0L);
        if (uid <= 0) return;
        City c = new City();
        c.uid = uid;
        c.aid = asLong(city.get("aid"), 0L);
        c.lv = asLong(city.get("lv"), 0L);
        c.nick = str(city.get("nickName"));
        Map<?, ?> point = asMap(item.get("point"));
        c.x = (int) asLong(point.get("x"), 0L);
        c.y = (int) asLong(point.get("y"), 0L);
        if (c.x <= 0 && c.y <= 0) return;
        cityByUid.put(uid, c);
        cityByPoint.put(c.x + "," + c.y, c);
        seenUids.add(uid);
        if (scan.active) scan.cityCount++;
    }

    /**
     * 2026-10-31：按坐标找城（容忍 ±2 格）——生产大量「目标城联盟未知」候选的 endpoint 与
     * 城 point 差 1 格（城中心/格子口径不同），精确查找会把真来袭全部过滤掉。
     * 命中记录偏差日志（节流），便于后续统一坐标口径。
     */
    private City cityNear(int x, int y) {
        City c = cityByPoint.get(x + "," + y);
        if (c != null) return c;
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    City n = cityByPoint.get((x + dx) + "," + (y + dy));
                    if (n == null) continue;
                    long now = System.currentTimeMillis();
                    if (now - nearCityLogAt > 60_000L) {
                        nearCityLogAt = now;
                        FileLog.info("[{}] 坐标容差命中：march endpoint=({},{}) → 城 {} ({},{}) 偏差 {},{}",
                                LOG, String.valueOf(x), String.valueOf(y),
                                String.valueOf(n.uid), String.valueOf(n.x), String.valueOf(n.y),
                                String.valueOf(dx), String.valueOf(dy));
                    }
                    return n;
                }
            }
        }
        return null;
    }

    private volatile long nearCityLogAt;
    /** 未匹配地图回包诊断节流（30s）。 */
    private volatile long unmatchedLogAt;

    private void onComplete(Ctx ctx) {
        if (ctx.kind == Kind.CROSS_PROBE) {
            crossProbeSessions.remove(ctx.session);
            long uid = ctx.probeUid;
            int cities = ctx.cityUids.size();
            int tries = crossProbeTries.merge(uid, 1, Integer::sum);
            Boolean verdict = crossProbeVerdict(cities, tries);
            if (Boolean.TRUE.equals(verdict)) {
                crossProbeSupported.add(uid);
                FileLog.info("[{}] 跨区角色 uid={} 探测可用（{} 城，{} 格）→ 参与观察点分发", LOG,
                        String.valueOf(uid), String.valueOf(cities), String.valueOf(1));
            } else if (Boolean.FALSE.equals(verdict)) {
                crossUnavailable.add(uid);
                FileLog.info("[{}] 跨区角色 uid={} 探测不可用（{} 次均 0 城）→ 自动排除", LOG,
                        String.valueOf(uid), String.valueOf(tries));
            } else {
                FileLog.info("[{}] 跨区角色 uid={} 探测未定（0 城，第 {} 次，将重试）", LOG,
                        String.valueOf(uid), String.valueOf(tries));
            }
            return;
        }
        if (ctx.kind == Kind.MONITOR) statOkMonitor.incrementAndGet();
        else if (ctx.kind == Kind.PROTECT) statOkProtect.incrementAndGet();
        if (ctx.kind == Kind.SCAN) {
            noteCrossProbe(ctx);
            scan.ended++;
            if (scan.ended >= SCAN_TILES) finishScan();
            return;
        }
        if (ctx.kind != Kind.MONITOR) return;
        long now = System.currentTimeMillis();
        for (WatchPointStore.Target t : store.monitorTargets) {
            if (!ctx.rect.contains(t.x, t.y)) continue;
            if (ctx.cityUids.contains(t.uid)) {
                targetMisses.remove(t.uid);
                continue;
            }
            AtomicInteger miss = targetMisses.computeIfAbsent(t.uid, k -> new AtomicInteger());
            int n = miss.incrementAndGet();
            FileLog.info("[{}] 监视目标 {} 本轮未出现（连续 {} 轮）", LOG, t.display(), String.valueOf(n));
            if (n >= 2 && !scan.active) {
                startScan("监视目标消失：" + t.display());
                return;
            }
        }
        if (now - lastPruneAt > 60_000L) {
            lastPruneAt = now;
            alerted.entrySet().removeIf(e -> now - e.getValue() > ALERT_DEDUPE_MS * 2);
        }
    }

    private void onMarch(Ctx ctx, Map<?, ?> m) {
        long id = asLong(m.get("id"), 0L);
        long owner = asLong(m.get("owner"), 0L);
        long type = asLong(m.get("type"), 0L);
        if (id <= 0 || owner <= 0) return;
        // 2026-10-31：先索引终点兵力（不受告警类型过滤；守护判断集结威胁用）
        try {
            Map<?, ?> epIndex = asMap(m.get("endpoint"));
            indexMarchDestination((int) asLong(epIndex.get("x"), -1L),
                    (int) asLong(epIndex.get("y"), -1L), troopsOf(m), asLong(m.get("endtime"), 0L));
        } catch (Throwable ignored) {
        }
        if (!store.alertTypes.contains(type)) return;
        Map<?, ?> sp = asMap(m.get("startpoint"));
        Map<?, ?> ep = asMap(m.get("endpoint"));
        int sx = (int) asLong(sp.get("x"), -1L);
        int sy = (int) asLong(sp.get("y"), -1L);
        int ex = (int) asLong(ep.get("x"), -1L);
        int ey = (int) asLong(ep.get("y"), -1L);
        long endtime = asLong(m.get("endtime"), 0L);
        // 只告警「打城」：地图行军的 atk_city/recon_city 在 map_info 数据里为空，用终点坐标是否命中已知城坐标判定
        City targetCity = cityNear(ex, ey);
        long targetUid = targetCity == null ? 0L : targetCity.uid;
        long targetAid = targetCity == null ? 0L : targetCity.aid;
        City ownerCity = cityByUid.get(owner);
        long ownerAid = ownerCity == null ? 0L : ownerCity.aid;
        // 2026-10-28 误报修复①：自循环/自家城行军（起点=终点、目标是发起者自己的城）
        // 典型场景：集结随从/部队往返自己的城或集结点 → 不是"打谁"，MONITOR/PROTECT 都不告警。
        boolean selfMarch = isSelfMarch(sx, sy, ex, ey, targetUid, owner);
        if (ctx.kind == Kind.MONITOR) {
            if (!ctx.rect.contains(sx, sy)) return;
            if (!isMonitoredUid(owner)) return;
            if (selfMarch) {
                statSelfIgnored.incrementAndGet();
                return;
            }
            if (store.cityTargetOnly && targetCity == null) return;
            // 同联盟目标（增援/自家城/内部动作）不算“打谁”，避免噪音
            if (!shouldAlertMonitor(ownerAid, targetAid)) return;
            alertOutgoing(owner, id, type, ex, ey, endtime, troopsOf(m));
        } else if (ctx.kind == Kind.PROTECT) {
            if (!ctx.rect.contains(ex, ey)) return;
            if (selfMarch) {
                statSelfIgnored.incrementAndGet();
                return;
            }
            // 2026-10-28 误报修复②：来袭只对「已知友盟城」告警。
            // targetCity 缺失或 aid<=0（联盟未知，如本次 12:18 的自循环误报）→ 仅 INFO 记录候选，不推送。
            if (targetCity == null || targetAid <= 0L) {
                statUnknownTargetIgnored.incrementAndGet();
                logUnknownIncomingCandidate(type, owner, ex, ey);
                return;
            }
            boolean alert = shouldAlertIncoming(selfMarch, ownerAid, targetAid,
                    store.friendAids, type, store.incomingRallyFollowerAlerts);
            if (!alert) {
                if (!store.incomingRallyFollowerAlerts && (type == 99L || type == 100L)
                        && store.friendAids.contains(targetAid)) {
                    statRallyFollowerIgnored.incrementAndGet();
                }
                return;
            }
            alertIncoming(owner, ownerCity, id, type, ex, ey, endtime, troopsOf(m));
        }
    }

    /** 未知目标（城未缓存/联盟未知）候选只记 INFO，不推送；同 type/owner/坐标去重防刷屏。 */
    private void logUnknownIncomingCandidate(long type, long owner, int ex, int ey) {
        String key = type + ":" + owner + ":" + ex + ":" + ey;
        if (unknownTargetLogged.size() > 2000) unknownTargetLogged.clear();
        if (!unknownTargetLogged.add(key)) return;
        FileLog.info("[{}] 来袭候选被过滤（目标城联盟未知，不推送）：type={} 攻击者=uid{} 目标=({},{})",
                LOG, String.valueOf(type), String.valueOf(owner),
                String.valueOf(ex), String.valueOf(ey));
    }

    // ---------- 告警判定纯函数（2026-10-28 误报修复；单测见 WatchPointAlertFilterTest） ----------

    /**
     * 自循环/自身行军判定：满足任一条即不是"打别人"。
     * <ul>
     *   <li>起点 == 终点（部队往返自己的城/集结点、集结随从自循环；生产 12:18 误报即此形态）；</li>
     *   <li>目标城就是发起者自己的城（{@code target.uid == owner}）。</li>
     * </ul>
     * 坐标未知（-1）时不参与起点=终点判定（避免脏数据误杀）。
     */
    static boolean isSelfMarch(int sx, int sy, int ex, int ey, long targetUid, long owner) {
        if (sx >= 0 && sy >= 0 && sx == ex && sy == ey) return true;
        return targetUid > 0L && targetUid == owner;
    }

    /**
     * 来袭告警判定（PROTECT）：2026-10-28 收紧为「已知友盟城才告警」。
     * 注意：目标联盟未知（aid&lt;=0）由调用方先计数过滤（{@code statUnknownTargetIgnored}），
     * 这里再防御一次；友军发起的增援/自家部队不告警；99/100 随从类可被配置静默。
     */
    static boolean shouldAlertIncoming(boolean selfMarch, long ownerAid, long targetAid,
                                       Set<Long> friendAids, long type, boolean rallyFollowerAlerts) {
        if (selfMarch) return false;
        if (targetAid <= 0L) return false;                                     // 目标联盟未知 → 不告警
        if (friendAids == null || !friendAids.contains(targetAid)) return false; // 非友盟城
        if (ownerAid > 0L && friendAids.contains(ownerAid)) return false;      // 友军自己人
        if (!rallyFollowerAlerts && (type == 99L || type == 100L)) return false; // 随从告警可关
        return true;
    }

    /** 敌情告警判定（MONITOR）：自循环已单独过滤；同联盟/自家城之间的行军不算"打谁"（原有口径）。 */
    static boolean shouldAlertMonitor(long ownerAid, long targetAid) {
        return !(ownerAid > 0L && targetAid > 0L && ownerAid == targetAid);
    }

    /** 联盟标注（纯函数）：aid&gt;0 → " [标签]"；联盟未知(aid&lt;=0) → "（联盟未知）"，不再留空白。 */
    static String allySuffix(long aid, Map<Long, String> aidLabels) {
        if (aid > 0L) {
            String label = aidLabels == null ? null : aidLabels.get(aid);
            return " [" + (label == null || label.isEmpty() ? "盟" + aid : label) + "]";
        }
        return "（联盟未知）";
    }

    private boolean isMonitoredUid(long uid) {
        for (WatchPointStore.Target t : store.monitorTargets) {
            if (t.uid == uid) return true;
        }
        return false;
    }

    private void alertOutgoing(long owner, long marchId, long type, int ex, int ey, long endtime, long troops) {
        String key = "out:" + owner + ":" + marchId;
        City ownerCity = cityByUid.get(owner);
        City target = cityNear(ex, ey);
        String nick = ownerCity != null && !ownerCity.nick.isEmpty() ? ownerCity.nick : "uid" + owner;
        String text = outgoingAlertText(nick, ownerCity == null ? 0L : ownerCity.aid, store.aidLabels,
                type, target == null ? null : target.nick, target == null ? 0L : target.aid,
                ex, ey, endtime - System.currentTimeMillis() / 1000L, troops, nowText());
        alert(key, text);
        trackAttacker(owner, ownerCity);
    }

    /**
     * 敌情告警文案（纯函数，便于单测「联盟未知」标注）。
     * 攻击者行：aid&gt;0 显示 [标签]，联盟未知显示（联盟未知）；目标行同理，不再留空白。
     */
    static String outgoingAlertText(String nick, long ownerAid, Map<Long, String> aidLabels,
                                    long type, String targetNick, long targetAid,
                                    int ex, int ey, long etaSeconds, long troops, String nowTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚨 观察点·敌情\n");
        sb.append("监视目标出兵：").append(nick).append(allySuffix(ownerAid, aidLabels)).append("\n");
        sb.append("行为：").append(typeLabel(type)).append("\n");
        sb.append("目标：(").append(ex).append(",").append(ey).append(")");
        if (targetNick != null) {
            sb.append(" ").append(targetNick.isEmpty() ? "?" : targetNick)
                    .append(allySuffix(targetAid, aidLabels));
        }
        sb.append("\nETA：").append(hms(etaSeconds));
        if (troops > 0) sb.append("｜兵力约 ").append(troops);
        sb.append("\n时间：").append(nowTime);
        return sb.toString();
    }

    private void alertIncoming(long owner, City ownerCity, long marchId, long type,
                               int ex, int ey, long endtime, long troops) {
        // 集结随从/集结类按「目标点」聚合去重：一次集结几十个随从，避免刷屏与触发企微 20 条/分钟限速
        String key = (type == 99L || type == 100L || type == 102L || type == 10L || type == 12L)
                ? "in-rally:" + ex + ":" + ey
                : "in:" + owner + ":" + marchId;
        City target = cityNear(ex, ey);
        String who = ownerCity != null && !ownerCity.nick.isEmpty() ? ownerCity.nick : "uid" + owner;
        String text = incomingAlertText(who, ownerCity == null ? 0L : ownerCity.aid, store.aidLabels,
                type, target == null ? null : target.nick, target == null ? 0L : target.aid,
                ex, ey, endtime - System.currentTimeMillis() / 1000L, troops, nowText());
        alert(key, text);
        trackAttacker(owner, ownerCity);
    }

    /**
     * 来袭告警文案（纯函数）：攻击者联盟未知（aid&lt;=0）时显式标注「（联盟未知）」，
     * 降低"攻守同名/没 [盟] 标签"歧义；目标一定是已知友盟城（带 [abbr]）。
     */
    static String incomingAlertText(String attackerNick, long attackerAid, Map<Long, String> aidLabels,
                                    long type, String targetNick, long targetAid,
                                    int ex, int ey, long etaSeconds, long troops, String nowTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚨 观察点·来袭\n");
        sb.append("攻击者：").append(attackerNick).append(allySuffix(attackerAid, aidLabels));
        sb.append("\n行为：").append(typeLabel(type)).append("\n");
        sb.append("目标：");
        if (targetNick != null) {
            sb.append(targetNick.isEmpty() ? "?" : targetNick)
                    .append(allySuffix(targetAid, aidLabels)).append(" ");
        }
        sb.append("(").append(ex).append(",").append(ey).append(")");
        sb.append("\nETA：").append(hms(etaSeconds));
        if (troops > 0) sb.append("｜兵力约 ").append(troops);
        sb.append("\n时间：").append(nowTime);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 整图扫描（迁城定位/基线）

    private void startScan(String reason) {
        if (scan.active) return;
        scan.active = true;
        scan.reason = reason == null ? "" : reason;
        scan.startedAt = System.currentTimeMillis();
        scan.nextTile = 0;
        scan.ended = 0;
        scan.cityCount = 0;
        cityByUid.clear();
        cityByPoint.clear();
        FileLog.info("[{}] 触发整图扫描：{}（484 格，预计 15–20s）", LOG, scan.reason);
    }

    private void scanTick(java.util.List<WjdrEngine> engines) {
        if (engines == null || engines.isEmpty()) return;
        int n = engines.size();
        long now = System.currentTimeMillis();
        int budget = 4;   // 每 80ms 最多 4 块（N 个角色并行时总速率为 4N）
        while (scan.nextTile < SCAN_TILES && budget-- > 0) {
            int i = scan.nextTile++;
            int sx = (i / GRID) * TILE;
            int sy = (i % GRID) * TILE;
            if (sx > MAP_MAX || sy > MAP_MAX) continue;
            int ex = Math.min(MAP_MAX, sx + TILE - 1);
            int ey = Math.min(MAP_MAX, sy + TILE - 1);
            WatchPointStore.Rect rect = new WatchPointStore.Rect("scan", sx, sy, ex, ey);
            int session = SESSION_SCAN_BASE + i;
            Ctx ctx = new Ctx(session, Kind.SCAN, rect, engines.get(i % n));
            ctxBySession.put(session, ctx);
            lastSent.put(session, now);
            try {
                sendRaw(engines.get(i % n), session, rect);
            } catch (Throwable t) {
                // 单引擎异常不影响其它引擎/后续 tile
                FileLog.warn("[{}] 扫描发送失败 engine={} session={}：{}",
                        LOG, String.valueOf(engines.get(i % n).lastUid()),
                        String.valueOf(session), String.valueOf(t));
            }
        }
        if (scan.nextTile >= SCAN_TILES && (scan.ended >= SCAN_TILES
                || now - scan.startedAt > store.scanTimeoutMs)) {
            finishScan();
        }
    }

    private void sendRaw(WjdrEngine engine, int session, WatchPointStore.Rect rect) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("startpoint", xy(rect.x1, rect.y1));
        args.put("endpoint", xy(rect.x2, rect.y2));
        args.put("kid", store.kid);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Collections.singletonList(args));
        payload.put("session", (long) session);
        payload.put("fulldata", Boolean.TRUE);
        payload.put("lod", 1L);
        engine.sendFireAndForget("req_world_map_info", payload, session);
    }

    private void finishScan() {
        scan.active = false;
        long cost = System.currentTimeMillis() - scan.startedAt;
        String completeness = scan.ended + "/" + SCAN_TILES;
        boolean complete = scan.ended >= (int) Math.ceil(SCAN_TILES * SCAN_COMPLETE_RATIO);
        if (!complete) {
            FileLog.warn("[{}] 整图扫描不完整：{}/{} 格，耗时 {}ms，城缓存 {}（<95%，跳过矩形健康度/迁城/攻击者判定，不告警）",
                    LOG, String.valueOf(scan.ended), String.valueOf(SCAN_TILES),
                    String.valueOf(cost), String.valueOf(cityByUid.size()));
            nextScanAt = store.fullScanIntervalMs > 0
                    ? System.currentTimeMillis() + store.fullScanIntervalMs : Long.MAX_VALUE;
            return;
        }
        int found = 0;
        int missing = 0;
        for (WatchPointStore.Target t : store.monitorTargets) {
            City c = cityByUid.get(t.uid);
            if (c == null) {
                missing++;
                continue;
            }
            found++;
            int ox = t.x;
            int oy = t.y;
            if (ox != c.x || oy != c.y) {
                t.x = c.x;
                t.y = c.y;
                targetMisses.remove(t.uid);
                alert("move:" + t.uid + ":" + c.x + ":" + c.y,
                        "📍 观察点·迁城\n监视目标 " + t.display() + "\n原坐标 (" + ox + "," + oy
                                + ") → 新坐标 (" + c.x + "," + c.y + ")\n扫描完整度=" + completeness
                                + "\n时间：" + nowText());
            }
        }
        // 保护矩形健康度：连续 2 次完整扫描为 0 才告警（防部分扫描误报）
        StringBuilder health = new StringBuilder();
        for (WatchPointStore.Rect r : store.protectRects) {
            int n = 0;
            for (City c : cityByUid.values()) {
                if (c.aid > 0 && store.friendAids.contains(c.aid) && r.contains(c.x, c.y)) n++;
            }
            health.append(r.name).append('=').append(n).append(' ');
            if (n == 0) {
                int streak = rectEmptyStreak.merge(r.name, 1, Integer::sum);
                if (streak >= 2) {
                    alert("rect-empty:" + r.name + ":" + (scan.startedAt / 3_600_000L),
                            "⚠️ 观察点·保护矩形为空\n" + r.name + " " + r.display()
                                    + " 内没有友盟城（连续 " + streak + " 次完整扫描；聚集地可能已迁移，建议重扫）"
                                    + "\n扫描完整度=" + completeness + "\n时间：" + nowText());
                } else {
                    FileLog.info("[{}] 保护矩形 {} 首次完整扫描为空（连续 {} 次），暂不告警",
                            LOG, r.name, String.valueOf(streak));
                }
            } else {
                rectEmptyStreak.remove(r.name);
            }
        }
        FileLog.info("[{}] 保护矩形友盟城数（完整度 {}）：{}", LOG, completeness, health.toString().trim());
        if (found > 0) store.save();
        checkTrackedAttackers(completeness);
        FileLog.info("[{}] 整图扫描完成：{}/{} 格/{} 城，完整度={}，耗时 {}ms，监视目标命中 {}/{}（缺失 {}）",
                LOG, String.valueOf(scan.ended), String.valueOf(SCAN_TILES),
                String.valueOf(cityByUid.size()), completeness, String.valueOf(cost),
                String.valueOf(found), String.valueOf(store.monitorTargets.size()),
                String.valueOf(missing));
        if (missing > 0) {
            alert("scan-missing:" + scan.startedAt,
                    "⚠️ 观察点·扫描未找到\n" + missing + " 个监视目标本轮整图未找到（可能跨服/数据延迟）"
                            + "\n扫描完整度=" + completeness + "\n时间：" + nowText());
        }
        nextScanAt = store.fullScanIntervalMs > 0
                ? System.currentTimeMillis() + store.fullScanIntervalMs : Long.MAX_VALUE;
    }

    // ------------------------------------------------------------------ 攻击者击飞/迁城跟踪（Q3）

    /** 告警时记录攻击者 uid + 当前城坐标（monitorTargets 内的 uid 跳过）。 */
    private void trackAttacker(long uid, City city) {
        if (uid <= 0L || city == null || isMonitoredUid(uid)) return;
        long now = System.currentTimeMillis();
        TrackedAttacker old = trackedAttackers.get(uid);
        if (old != null) {
            old.x = city.x;
            old.y = city.y;
            old.atMs = now;
            return;
        }
        if (trackedAttackers.size() >= ATTACKER_TRACK_MAX) {
            long oldestUid = 0L;
            long oldest = Long.MAX_VALUE;
            for (TrackedAttacker a : trackedAttackers.values()) {
                if (a.atMs < oldest) {
                    oldest = a.atMs;
                    oldestUid = a.uid;
                }
            }
            if (oldestUid > 0L) trackedAttackers.remove(oldestUid);
        }
        trackedAttackers.put(uid, new TrackedAttacker(uid, city.nick, city.x, city.y, now));
    }

    /** 完整扫描后对比攻击者坐标：迁城→告警新坐标；未找到→未定位（同 uid 6h 去重）。 */
    private void checkTrackedAttackers(String completeness) {
        long now = System.currentTimeMillis();
        for (Long uidObj : new java.util.ArrayList<>(trackedAttackers.keySet())) {
            long uid = uidObj == null ? 0L : uidObj;
            if (uid <= 0L) continue;
            TrackedAttacker ta = trackedAttackers.get(uid);
            if (ta == null) continue;
            if (now - ta.atMs > ATTACKER_TRACK_TTL_MS) {
                trackedAttackers.remove(uid);
                attackerMissingAt.remove(uid);
                continue;
            }
            if (isMonitoredUid(uid)) continue;   // 监视目标自身逻辑已覆盖
            City c = cityByUid.get(uid);
            if (c == null) {
                Long last = attackerMissingAt.get(uid);
                if (last != null && now - last < ATTACKER_MISSING_DEDUPE_MS) continue;
                attackerMissingAt.put(uid, now);
                alert("attacker-missing:" + uid,
                        "⚠️ 观察点·攻击者未定位（可能被击飞/跨服）\nuid=" + uid
                                + (ta.nick.isEmpty() ? "" : " " + ta.nick)
                                + "\n最后坐标 (" + ta.x + "," + ta.y + ")"
                                + "\n扫描完整度=" + completeness + "\n时间：" + nowText());
                continue;
            }
            attackerMissingAt.remove(uid);
            if (c.x != ta.x || c.y != ta.y) {
                int ox = ta.x;
                int oy = ta.y;
                alert("attacker-move:" + uid + ":" + c.x + ":" + c.y,
                        "📍 观察点·攻击者迁城\nuid=" + uid
                                + (ta.nick.isEmpty() ? "" : " " + ta.nick)
                                + "\n原坐标 (" + ox + "," + oy + ") → 新坐标 (" + c.x + "," + c.y + ")"
                                + "\n扫描完整度=" + completeness + "\n时间：" + nowText());
                ta.x = c.x;
                ta.y = c.y;
                ta.atMs = now;
            }
        }
    }

    // ------------------------------------------------------------------ 告警出口

    private void alert(String key, String text) {
        long now = System.currentTimeMillis();
        Long prev = alerted.get(key);
        if (prev != null && now - prev < ALERT_DEDUPE_MS) return;
        alerted.put(key, now);
        statAlerts.incrementAndGet();
        lastAlertText = text;
        FileLog.info("[{}] {}", LOG, text.replace('\n', ' ').replace('\r', ' '));
        if (!store.wecomEnabled) return;
        VaultStore.WatchConfig cfg = vaultStore.watchConfig();
        if (!cfg.wecomEnabled || cfg.webhookUrl == null || cfg.webhookUrl.trim().isEmpty()) return;
        if (inQuietHours(cfg, now)) {
            FileLog.info("[{}] 静默时段，仅记录日志：{}", LOG, text.split("\n")[0]);
            return;
        }
        final String body = text.length() > 1500 ? text.substring(0, 1500) : text;
        final String url = cfg.webhookUrl;
        try {
            alertExec.execute(() -> {
                try {
                    waitWecomSlot();
                    boolean ok = notifier.send(url, body);
                    if (!ok) FileLog.warn("[{}] 企业微信推送失败", LOG);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    FileLog.warn("[{}] 企业微信推送异常：{}", LOG, String.valueOf(t));
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 企业微信机器人限速 20 条/分钟：单线程队列保证 ≥3s 一条。 */
    private synchronized void waitWecomSlot() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = 3000L - (now - lastWecomAt);
        if (wait > 0) Thread.sleep(wait);
        lastWecomAt = System.currentTimeMillis();
    }

    private static boolean inQuietHours(VaultStore.WatchConfig cfg, long nowMillis) {
        if (cfg.quietStart < 0) return false;
        ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        int h = z.getHour();
        int s = cfg.quietStart;
        int e = cfg.quietEnd;
        if (s == e) return false;
        return s < e ? (h >= s && h < e) : (h >= s || h < e);
    }

    // ------------------------------------------------------------------ 工具

    private static int countOf(Object v) {
        if (v instanceof Number) return (int) ((Number) v).longValue();
        Map<?, ?> m = asMap(v);
        long n = asLong(m.get("count"), 0L);
        return (int) n;
    }

    private static long troopsOf(Map<?, ?> march) {
        try {
            Map<?, ?> squad = asMap(march.get("squad_list"));
            Map<?, ?> soldiers = asMap(squad.get("soldier"));
            if (soldiers.isEmpty()) soldiers = asMap(march.get("soldier_map"));
            long total = 0;
            for (Object v : soldiers.values()) total += Math.max(0, countOf(v));
            return total;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String typeLabel(long type) {
        if (type == 1) return "侦查";
        if (type == 2) return "进攻";
        if (type == 3) return "狩猎";
        if (type == 4) return "采集";
        if (type == 5) return "驻防/增援";
        if (type == 10) return "集结/集结中";
        if (type == 12) return "集结/集结中";
        if (type == 99) return "集结随从";
        if (type == 100) return "集结";
        if (type == 102) return "集结主体";
        if (type == 302) return "打野";
        return "type" + type;
    }

    private static String hms(long seconds) {
        long s = Math.max(0L, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "时" + m + "分";
        return m + "分" + sec + "秒";
    }

    private static String nowText() {
        ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        return String.format("%02d:%02d:%02d", z.getHour(), z.getMinute(), z.getSecond());
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static long asLong(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Throwable t) {
            return def;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Map<?, ?> asMap(Object v) {
        return v instanceof Map ? (Map<?, ?>) v : Collections.emptyMap();
    }

    /** 只读统计（测试/运维用）。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("kid", store.kid);
        m.put("monitorTargets", store.monitorTargets.size());
        m.put("protectRects", store.protectRects.size());
        m.put("scanning", scan.active);
        m.put("citiesCached", cityByUid.size());
        m.put("selfIgnored", statSelfIgnored.get());
        m.put("unknownTargetIgnored", statUnknownTargetIgnored.get());
        m.put("rallyFollowerIgnored", statRallyFollowerIgnored.get());
        m.put("uptimeMs", running ? System.currentTimeMillis() - startedAt : 0L);
        return m;
    }

    /** 供单测/运维：当前缓存的城数量。 */
    int cityCacheSize() {
        return cityByUid.size();
    }

    List<WatchPointStore.Rect> protectRectsSnapshot() {
        return new ArrayList<>(store.protectRects);
    }

    // ------------------------------------------------------------------ 单测钩子（包可见）

    void startScanForTest(String reason) {
        startScan(reason);
    }

    void scanTickForTest(java.util.List<WjdrEngine> engines) {
        scanTick(engines);
    }

    void finishScanForTest() {
        finishScan();
    }

    void feedResponseForTest(int session, String proto, Map<String, Object> content) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (content != null) c.putAll(content);
        c.put("session", (long) session);
        onMessage(null, proto, c);
    }

    boolean scanActiveForTest() {
        return scan.active;
    }

    int scanEndedForTest() {
        return scan.ended;
    }

    int cityCacheForTest() {
        return cityByUid.size();
    }

    int alertCountForTest() {
        return (int) statAlerts.get();
    }

    String lastAlertTextForTest() {
        return lastAlertText;
    }

    java.util.List<WjdrEngine> pickEnginesForTest(java.util.List<WjdrEngine> online) {
        java.util.Set<Long> crossUids = new java.util.LinkedHashSet<>(store.crossKidRoleUids);
        return selectEngines(online, store.fpid, store.fpids, store.allAccounts,
                store.kid, store.crossKid, crossUids, multiRoleEnabled(), crossUnavailable);
    }

    int crossProbeTilesForTest(long uid) {
        CrossProbe p = crossProbes.get(uid);
        return p == null ? 0 : p.tiles;
    }

    Boolean crossSupportedForTest(long uid) {
        CrossProbe p = crossProbes.get(uid);
        return p == null ? null : p.supported;
    }

    boolean crossUnavailableForTest(long uid) {
        return crossUnavailable.contains(uid);
    }

    void pollTickForTest(java.util.List<WjdrEngine> engines) {
        pollTick(engines);
    }

    void trackAttackerForTest(long uid, long aid, int x, int y, String nick) {
        City c = new City();
        c.uid = uid;
        c.aid = aid;
        c.x = x;
        c.y = y;
        c.nick = nick == null ? "" : nick;
        cityByUid.put(uid, c);
        cityByPoint.put(x + "," + y, c);
        trackAttacker(uid, c);
    }

    void setMultiRoleForTest(boolean multi) {
        store.multiRole = multi;
    }

}
