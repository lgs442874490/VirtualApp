package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionKeeper;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;
import com.carlos.vaultserver.alert.AlertRules;
import com.carlos.vaultserver.alert.AlertText;

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

        public CityInfo(long uid, long aid, long lv, String nick, int x, int y) {
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

    /** 友盟 aid 集合（只读快照；供采集召回等同口径排除友军）。 */
    public static Set<Long> friendAids() {
        WatchPointEngine e = SHARED;
        if (e == null || e.store == null || e.store.friendAids == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(e.store.friendAids);
    }

    /**
     * 城缓存查询（2026-11-02 小工具）：按 uid / 昵称子串 / 联盟简称（昵称前缀 [xxx]）/ aid / 最低炉子等级过滤。
     * 供 API {@code /v1/vault/watchpoint action=lookup} 使用；无共享实例返回空列表。
     */
    public static java.util.List<CityInfo> lookup(String uid, String nick, String abbr,
                                                  long aid, long minLv, int limit) {
        java.util.List<CityInfo> out = new java.util.ArrayList<>();
        WatchPointEngine e = SHARED;
        if (e == null) return out;
        String u = uid == null ? "" : uid.trim();
        String n = nick == null ? "" : nick.trim();
        String ab = abbr == null ? "" : abbr.trim();
        if (limit <= 0 || limit > 1000) limit = 1000;   // 别让 limit=10^6 把整图缓存全吐出来
        // 简称优先当「联盟 aid」用：玩家目录 aabbr→aid 或官方联盟搜索；解析不到才退回昵称前缀 [818]
        long abbrAid = 0L;
        if (!ab.isEmpty()) {
            PlayerDirectory dir = PlayerDirectory.shared();
            Long known = dir == null ? null : dir.aidOfAbbr(ab);
            if (known != null && known > 0L) {
                abbrAid = known;
            } else if (e.keeper != null) {
                try {
                    abbrAid = e.keeper.resolveAllianceAid(e.store.fpid, 0L, ab);
                } catch (Throwable ignore) {
                    abbrAid = 0L;
                }
                if (abbrAid > 0L && dir != null) dir.rememberAbbr(ab, abbrAid);
            }
            if (abbrAid <= 0L && ab.matches("\\d{7,}")) {
                // 直接塞 aid（≥7 位）也认，便于脚本/老数据
                try { abbrAid = Long.parseLong(ab); } catch (NumberFormatException ignore) { }
            }
        }
        for (City c : e.cityByUid.values()) {
            if (c == null) continue;
            if (!u.isEmpty() && !String.valueOf(c.uid).equals(u)) continue;
            if (aid > 0L && c.aid != aid) continue;
            if (minLv > 0L && c.lv < minLv) continue;
            String nn = c.nick == null ? "" : c.nick;   // 城缓存可能没有昵称，别 NPE
            if (abbrAid > 0L) {
                if (c.aid != abbrAid) continue;
            } else if (!ab.isEmpty() && !ab.equalsIgnoreCase(abbrFromNick(nn))) {
                continue;
            }
            if (!n.isEmpty() && !nn.contains(n)) continue;
            out.add(new CityInfo(c.uid, c.aid, c.lv, nn, c.x, c.y));
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    /** 坐标 → 城（整图扫描缓存；未扫描到/未启用返回 null）。任意线程可调。 */
    public static CityInfo cityAt(int x, int y) {
        WatchPointEngine e = SHARED;
        if (e == null || x <= 0 || y <= 0) return null;
        City c = e.cityByPoint.get(x + "," + y);
        return c == null ? null : new CityInfo(c.uid, c.aid, c.lv, c.nick, c.x, c.y);
    }

    /**
     * 整图城缓存是否已有数据（2026-11-04）：守护用「集结终点是否城」过滤巨兽/野外集结，
     * 但缓存未完成首扫时 cityAt 恒 null；此时不得据此拒绝真实威胁，应退回旧判定。
     */
    public static boolean cityCacheReady() {
        WatchPointEngine e = SHARED;
        return e != null && !e.cityByPoint.isEmpty();
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

    /**
     * 注入一个城市坐标（单测 + 守护模块 observeCity 生产路径：守护坐标查询看到的城）。
     * 2026-11-04：昵称同步进实时昵称缓存，推送优先用它。
     */
    public void seedCityForTest(long uid, long aid, int x, int y, String nick) {
        City c = new City();
        c.uid = uid;
        c.aid = aid;
        c.x = x;
        c.y = y;
        c.nick = nick == null ? "" : nick;
        cityByPoint.put(x + "," + y, c);
        if (uid > 0L) {
            cityByUid.put(uid, c);
            if (!c.nick.isEmpty()) liveNickByUid.put(uid, c.nick);
        }
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
        /** 扫描顺序（2026-11-02）：丢失目标时按「离最后坐标由近到远」排，先扫周边再扫全图。 */
        int[] order;
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
    /** 采集点缓存（2026-11-02）：坐标 → 正在该点采集的人（uid/aid/marchId）。 */
    static final class ResPoint {
        long uid;
        long aid;
        long marchId;
        long atMs;
    }

    private final Map<String, ResPoint> resByPoint = new java.util.concurrent.ConcurrentHashMap<>();
    /** 采集点被攻击告警配额：每个攻击者 5 分钟最多 3 条（与侦查同口径，独立计数）。 */
    private final Map<Long, java.util.ArrayDeque<Long>> gatherAlertTimes =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 2026-11-04：A1 情报行为（侦查/驻防/发起集结）统一限流：每人 5 分钟 3 条。 */
    private final Map<Long, java.util.ArrayDeque<Long>> intelAlertTimes =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 2026-11-04：资源类行为（采集/建设/破坏）限流：每人 5 分钟 3 条。 */
    private final Map<Long, java.util.ArrayDeque<Long>> resAlertTimes =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 联盟 aid → 联盟全名（保护更新文案用；来自地图王城/要塞对象的 aname）。 */
    private final Map<Long, String> allianceNames = new java.util.concurrent.ConcurrentHashMap<>();
    /** 联盟建筑缓存（2026-11-04）：坐标 → 总部/旗子/王城（归属 aid）。用于区分建设 vs 破坏。 */
    static final class Building {
        final String kind;
        final long aid;
        Building(String kind, long aid) {
            this.kind = kind;
            this.aid = aid;
        }
    }
    private final Map<String, Building> buildingByPoint = new java.util.concurrent.ConcurrentHashMap<>();

    /** 坐标 → 联盟建筑（总部/旗子/王城）；无返回 null。任意线程可调。 */
    public static Building buildingAt(int x, int y) {
        WatchPointEngine e = SHARED;
        if (e == null || x <= 0 || y <= 0) return null;
        return e.buildingByPoint.get(x + "," + y);
    }
    private final Map<String, Long> gatherCounted = new java.util.concurrent.ConcurrentHashMap<>();
    /** 采集点缓存有效期（2026-11-04）：超过该时长未刷新视为「人已走」，不再据此告警。 */
    static final long GATHER_POINT_TTL_MS = 20L * 60_000L;
    /** 采集点告警跳过诊断去重。 */
    private final Set<String> gatherSkipLogged = ConcurrentHashMap.newKeySet();

    /** 监视目标跟踪（2026-11-02）：最后出现时刻 / 丢失时刻 / 上次搜索扫描时刻 / 最近被进攻时刻。 */
    private final Map<Long, Long> targetSeenAt = new ConcurrentHashMap<>();
    private final Map<Long, Long> targetLostAt = new ConcurrentHashMap<>();
    private final Map<Long, Long> targetSearchAt = new ConcurrentHashMap<>();
    private final Map<Long, Long> targetAttackedAt = new ConcurrentHashMap<>();
    /**
     * 坐标更新聚合窗口（2026-11-04 用户口径）：**1 分钟内高迁多次只推最后一次**。
     * 第一次坐标变化开启窗口，窗口内只累计最新坐标；窗口到期（默认 60s）才推一条，
     * 前面的中间坐标一律不推（用户：前面几次都是无效推送）。
     */
    private volatile long moveAlertWindowMs = 60_000L;
    /** 聚合窗口内待推的最新坐标（uid → pending；windowStart=窗口起点）。 */
    private final Map<Long, PendingMove> pendingMoveAlert = new java.util.concurrent.ConcurrentHashMap<>();
    /** 最近一次地图城对象（type=1）给出的实时昵称：uid → nick（2026-11-04：推送只用实时昵称）。 */
    private final Map<Long, String> liveNickByUid = new java.util.concurrent.ConcurrentHashMap<>();
    /** 非城对象（含 map_city 的行军/队列对象）诊断去重：已记过日志的 uid。 */
    private final Set<Long> nonCityLogged = ConcurrentHashMap.newKeySet();
    /**
     * 监视目标的在途行军终点（2026-11-04）：uid → "x,y" → 过期时刻。
     * 地图对象里可能混入「城快照(map_city)+行军终点(point)」的伪城对象，按终点坐标排除，避免坐标乱跳。
     */
    private final Map<Long, Map<String, Long>> marchEndpointsByUid =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 行军终点记忆 TTL（ms）：覆盖一次在途行军的最长时长。 */
    static final long MARCH_ENDPOINT_TTL_MS = 10L * 60_000L;

    /** 聚合窗口内待推的坐标快照：ox/oy=窗口起点坐标（上次已推坐标），x/y=窗口内最新坐标。 */
    static final class PendingMove {
        final int ox;
        final int oy;
        final int x;
        final int y;
        final String nick;
        final long windowStart;

        PendingMove(int ox, int oy, int x, int y, String nick, long windowStart) {
            this.ox = ox;
            this.oy = oy;
            this.x = x;
            this.y = y;
            this.nick = nick;
            this.windowStart = windowStart;
        }
    }
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
    /** 2026-11-02 侦查限流：每个攻击者 5 分钟内最多推送 3 条侦查类告警（出征类不受限）。 */
    static final long SCOUT_WINDOW_MS = 5L * 60L * 1000L;
    static final int SCOUT_MAX_PER_WINDOW = 3;
    private final Map<Long, java.util.ArrayDeque<Long>> scoutAlertTimes =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** uid:marchId → 最近一次计侦查配额的时刻（防轮询重放重复扣配额）。 */
    private final Map<String, Long> scoutCounted = new java.util.concurrent.ConcurrentHashMap<>();

    /** 侦查告警配额（纯函数，便于单测）：窗口内未满 max 则放行并记账，否则拒绝。 */
    static boolean scoutQuotaAllow(Map<Long, java.util.ArrayDeque<Long>> map, long uid,
                                   long now, long windowMs, int max) {
        if (max <= 0 || map == null || uid <= 0L) return true;
        java.util.ArrayDeque<Long> q = map.computeIfAbsent(uid, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
        }
        // 打完就跑的散人很多，过期队列要清掉，否则 map 只增不减
        if (map.size() > 256) {
            map.entrySet().removeIf(en -> {
                java.util.ArrayDeque<Long> dq = en.getValue();
                synchronized (dq) {
                    while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) dq.pollFirst();
                    return dq.isEmpty();
                }
            });
        }
        return true;
    }

    /**
     * 同一条行军只记一次侦查配额（2026-11-02 修）：监视轮询每 4s 会把同一条在途行军重放一遍，
     * 若每次重放都扣配额，一条侦查 12 秒就能把「5 分钟 3 条」刷光，真实侦查反被静默。
     * 返回 true=这条行军第一次计配额（需要继续做配额判定）。
     */
    boolean scoutCountOnce(long uid, long marchId, long now) {
        if (uid <= 0L || marchId <= 0L) return true;
        String k = uid + ":" + marchId;
        Long prev = scoutCounted.get(k);
        if (prev != null && now - prev < SCOUT_WINDOW_MS) return false;
        scoutCounted.put(k, now);
        if (scoutCounted.size() > 4096) {
            scoutCounted.entrySet().removeIf(e -> now - e.getValue() > SCOUT_WINDOW_MS);
        }
        return true;
    }

    /** 该告警 key 是否已在去重窗口内（同一条行军的重复轮询，不产生新通知也不该占配额）。 */
    private boolean recentlyAlerted(String key) {
        Long prev = alerted.get(key);
        return prev != null && System.currentTimeMillis() - prev < ALERT_DEDUPE_MS;
    }

    /** 兵力分级文案（纯函数）：低于阈值→⚪ 佯攻；≥阈值→🔴 威胁进攻；阈值 0→普通兵力展示。 */
    static String troopsTag(long troops, long feintBelow) {
        if (troops <= 0L) return "";
        if (feintBelow <= 0L) return "｜兵力约 " + troops;
        return troops < feintBelow
                ? "｜⚪ 佯攻（兵力约 " + troops + " < " + feintBelow + "）"
                : "｜🔴 威胁进攻（兵力约 " + troops + " ≥ " + feintBelow + "）";
    }

    /** 2026-10-28 误报修复计数：自循环行军 / 目标联盟未知的来袭候选 / 随从告警静默。 */
    private final java.util.concurrent.atomic.AtomicLong statSelfIgnored = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statUnknownTargetIgnored = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statRallyFollowerIgnored = new java.util.concurrent.atomic.AtomicLong();
    /** 2026-11-09：保护矩形内 PvE 行军（巨兽/野外集结等）被 A2 敌袭静默的计数。 */
    private final java.util.concurrent.atomic.AtomicLong statPveIgnored = new java.util.concurrent.atomic.AtomicLong();
    /** 未知目标候选 INFO 去重（同 type/owner/坐标只记一次，防日志刷屏）。 */
    private final Set<String> unknownTargetLogged = ConcurrentHashMap.newKeySet();
    /** PvE 来袭候选静默 INFO 去重（同 type/owner/坐标只记一次）。 */
    private final Set<String> pveIncomingLogged = ConcurrentHashMap.newKeySet();
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
                flushPendingMoveAlerts();
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
                        + " 自循环忽略={} 未知目标忽略={} 随从静默={} PvE静默={} 角色数={} 账号数={}",
                LOG, String.valueOf(lastTickHadEngine),
                String.join(",", lastEngineDescs),
                String.valueOf(statSentMonitor.get()), String.valueOf(statOkMonitor.get()),
                String.valueOf(statTimeouts.get()), String.valueOf(statSentProtect.get()),
                String.valueOf(statOkProtect.get()), String.valueOf(statAlerts.get()),
                scan.active ? "进行中" : "空闲", String.valueOf(cityByUid.size()),
                String.valueOf(statSelfIgnored.get()), String.valueOf(statUnknownTargetIgnored.get()),
                String.valueOf(statRallyFollowerIgnored.get()),
                String.valueOf(statPveIgnored.get()),
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
        // 2026-11-01 关键修复：先给「所有候选引擎」挂消息钩子，再做跨区探测过滤。
        // 之前探测用的跨区引擎不在最终列表 → 没挂钩子 → 服务端回包被引擎丢弃，误判“跨区不可用”。
        for (WjdrEngine e : picked) attach(e);
        // 跨区角色先「探测」再参与分发——未验证前不占用扫描分片，避免整图完整性被拉低；
        // 探测用已知有城的矩形（观察点缓存/monitorTargets）。
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
    /** 跨区探测 content.session 序号（官方用小序号回显）。 */
    private static final java.util.concurrent.atomic.AtomicLong crossProbeSeq =
            new java.util.concurrent.atomic.AtomicLong();

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
            int cx = (rect.x1 + rect.x2) / 2, cy = (rect.y1 + rect.y2) / 2;
            // 2026-11-01 官方抓包口径：fog{kid} 之后 map_info{args:[{startpoint,endpoint,kid}],
            //   session=小序号, fulldata=false, lod=0}，矩形 21×21。
            long contentSession = 500_000L + Math.abs(uid % 1000L) * 100L
                    + (crossProbeSeq.incrementAndGet() % 100L);
            Ctx ctx = new Ctx(session, Kind.CROSS_PROBE, rect, engine);
            ctx.probeUid = uid;
            ctxBySession.put(session, ctx);
            ctxBySession.put((int) contentSession, ctx);   // 推送按 content.session 回显
            lastSent.put(session, System.currentTimeMillis());
            FileLog.info("[{}] 跨区探测发起 uid={} 区服={} 中心({},{}) 目标kid={} "
                            + "fulldata=false lod=0 header={} content={}", LOG,
                    String.valueOf(uid), String.valueOf(engine.lastServerId()),
                    String.valueOf(cx), String.valueOf(cy), String.valueOf(store.kid),
                    String.valueOf(session), String.valueOf(contentSession));
            sendRawOfficial(engine, session, contentSession, cx, cy);
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
            // 名单里已删除的目标，跟踪状态一并清掉（防 map 无限增长）
            if (targetSeenAt.size() > 32) {
                targetSeenAt.keySet().removeIf(uid -> !isMonitoredUid(uid));
                targetLostAt.keySet().removeIf(uid -> !isMonitoredUid(uid));
                targetSearchAt.keySet().removeIf(uid -> !isMonitoredUid(uid));
                targetAttackedAt.keySet().removeIf(uid -> !isMonitoredUid(uid));
            }
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
            // 2026-11-06：联盟建筑增量推送（add/del/update，session<=0 的主动推送）——
            // 建旗子/拆旗子/总部建成/易主的直接事件源；此前被当「未匹配回包」丢弃（生产 11-06 日志
            // 单日 add 213 / del 218 / update 306 条全部丢失）。
            if (sess <= 0L && isBuildingPush(proto)) {
                onMapObjPush(proto, content);
                return;
            }
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
                    if (o instanceof Map) {
                        parseCity((Map<?, ?>) o, ctx.cityUids);
                        parseResource((Map<?, ?>) o);
                        parseAllianceMeta((Map<?, ?>) o);
                        parseAllianceBuilding((Map<?, ?>) o);
                    }
                }
            }
            Map<?, ?> obj = asMap(content.get("obj"));
            if (!obj.isEmpty()) {
                parseCity(obj, ctx.cityUids);
                parseResource(obj);
                parseAllianceMeta(obj);
                parseAllianceBuilding(obj);
            }
            Map<?, ?> change = asMap(content.get("change"));
            if (!change.isEmpty()) {
                parseCity(change, ctx.cityUids);
                parseResource(change);
                parseAllianceMeta(change);
                parseAllianceBuilding(change);
            }
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

    /** 资源矿对象（world_mapobj.map_resource：id/uid/aid/march_id）：记录「谁在这个点采集」。 */
    private void parseResource(Map<?, ?> item) {
        Map<?, ?> res = asMap(item.get("map_resource"));
        if (res.isEmpty()) return;
        Map<?, ?> point = asMap(item.get("point"));
        int x = (int) asLong(point.get("x"), 0L);
        int y = (int) asLong(point.get("y"), 0L);
        if (x <= 0 || y <= 0) return;
        long uid = asLong(res.get("uid"), 0L);
        long marchId = asLong(res.get("march_id"), 0L);
        if (uid <= 0L && marchId <= 0L) {
            // 2026-11-04：空矿点必须清掉旧采集者，否则过期的「谁在采」会让后续任何经过该点的
            // 行军都被误报成「采集点被攻击」（生产大量盟友互打误报的来源之一）。
            resByPoint.remove(x + "," + y);
            return;
        }
        recordResource(x, y, uid, asLong(res.get("aid"), 0L), marchId);
    }

    /** 联盟元数据（2026-11-04）：从王城/要塞对象取 aid→联盟全名，供保护更新文案用。 */
    private void parseAllianceMeta(Map<?, ?> item) {
        for (String k : new String[]{"map_capital", "map_major_fort", "map_middle_fort",
                "map_minor_fort", "map_alliance_hall"}) {
            Map<?, ?> o = asMap(item.get(k));
            if (o.isEmpty()) continue;
            long aid = asLong(o.get("aid"), 0L);
            String aname = str(o.get("aname"));
            if (aid > 0L && !aname.isEmpty()) allianceNames.put(aid, aname);
        }
    }

    /** 联盟建筑（总部/旗子/王城）→ 坐标缓存，用于「建设 vs 破坏」按目标归属判定。 */
    private void parseAllianceBuilding(Map<?, ?> item) {
        int[] pt = pointOf(item);
        if (pt == null) return;
        Building b = buildingOf(item);
        if (b == null) return;
        buildingByPoint.put(pt[0] + "," + pt[1], b);
        if (buildingByPoint.size() > 20000) {
            // 简单防膨胀：扫描会周期性重建，这里只做上限保护
            buildingByPoint.clear();
        }
    }

    // ------------------------------------------------------------------ 建筑变化推送（2026-11-06）

    /** 联盟建筑增量推送：add/del/update（session<=0 主动推送）。 */
    private static boolean isBuildingPush(String proto) {
        return "notify_world_mapobj_add".equals(proto)
                || "notify_world_mapobj_del".equals(proto)
                || "notify_world_mapobj_update".equals(proto);
    }

    /**
     * 处理建筑增量推送：add=新建、del=拆除、update=归属/类型变化。
     *
     * <p>只处理坐标落在监视目标矩形（±27 格）或保护矩形内的事件，其余（全服推送）直接丢弃，
     * 避免刷屏与缓存膨胀。
     */
    private void onMapObjPush(String proto, Map<String, Object> content) {
        if ("notify_world_mapobj_del".equals(proto)) {
            int[] pt = pointOf(content);
            if (pt == null || !inWatchArea(pt[0], pt[1])) return;
            Building old = buildingByPoint.remove(pt[0] + "," + pt[1]);
            if (old == null) return;   // 非联盟建筑或从未缓存，不告警
            onBuildingEvent(pt[0], pt[1], old, "拆除");
            return;
        }
        Map<?, ?> item = asMap(content.get("obj"));
        if (item.isEmpty()) item = asMap(content.get("change"));
        if (item.isEmpty()) return;
        Building nb = buildingOf(item);
        if (nb == null) return;
        int[] pt = pointOf(item);
        if (pt == null) pt = pointOf(content);
        if (pt == null || !inWatchArea(pt[0], pt[1])) return;
        Building old = buildingByPoint.get(pt[0] + "," + pt[1]);
        if ("notify_world_mapobj_add".equals(proto)) {
            buildingByPoint.put(pt[0] + "," + pt[1], nb);
            onBuildingEvent(pt[0], pt[1], nb, "新建");
        } else if (old == null) {
            buildingByPoint.put(pt[0] + "," + pt[1], nb);
            onBuildingEvent(pt[0], pt[1], nb, "新建");
        } else if (old.aid != nb.aid || !old.kind.equals(nb.kind)) {
            buildingByPoint.put(pt[0] + "," + pt[1], nb);
            onBuildingEvent(pt[0], pt[1], nb, "易主");
        }
    }

    /** 建筑变化告警（区域过滤 + 15 分钟去重由 {@link #alert} 承担）。 */
    private void onBuildingEvent(int x, int y, Building b, String action) {
        if (!inWatchArea(x, y)) return;
        String label = b.aid > 0L ? store.aidLabels.get(b.aid) : null;
        if (label == null || label.isEmpty()) label = b.aid > 0L ? allianceNames.get(b.aid) : null;
        alert("bld:" + action + ":" + x + "," + y + ":" + b.aid,
                buildingAlertText(action, b.kind, label, x, y, nowText()));
    }

    /** 坐标是否落在监视目标矩形（与扫描同源 {@link #rectAround}）或保护矩形内。 */
    boolean inWatchArea(int x, int y) {
        for (WatchPointStore.Target t : store.monitorTargets) {
            if (rectAround(t.x, t.y, TILE / 2).contains(x, y)) return true;
        }
        for (WatchPointStore.Rect r : store.protectRects) {
            if (r.contains(x, y)) return true;
        }
        return false;
    }

    /** 从世界地图对象解析联盟建筑（总部/旗子/王城）；非联盟建筑返回 null。 */
    static Building buildingOf(Map<?, ?> item) {
        if (item == null) return null;
        Map<?, ?> o = asMap(item.get("map_alliance_hall"));
        String kind = null;
        if (!o.isEmpty()) {
            kind = "总部";
        } else {
            o = asMap(item.get("map_alliance_flag"));
            if (!o.isEmpty()) kind = "旗子";
        }
        if (kind == null) {
            o = asMap(item.get("map_capital"));
            if (!o.isEmpty()) kind = "王城";
        }
        if (kind == null) return null;
        return new Building(kind, asLong(o.get("aid"), 0L));
    }

    /** 解析对象坐标（point）；缺失/非法返回 null。 */
    private static int[] pointOf(Map<?, ?> item) {
        if (item == null) return null;
        Map<?, ?> point = asMap(item.get("point"));
        int x = (int) asLong(point.get("x"), 0L);
        int y = (int) asLong(point.get("y"), 0L);
        return (x <= 0 || y <= 0) ? null : new int[]{x, y};
    }

    /** 建筑变化文案（2026-11-06）：📍 建筑·新建/拆除/易主。 */
    static String buildingAlertText(String action, String kind, String allianceLabel,
                                    int x, int y, String nowTime) {
        StringBuilder sb = new StringBuilder("📍 建筑·").append(action).append('\n');
        sb.append("类型：").append(kind).append('\n');
        if (allianceLabel != null && !allianceLabel.isEmpty()) {
            sb.append("联盟：").append(allianceLabel).append('\n');
        }
        sb.append("坐标：(").append(x).append(',').append(y).append(")\n");
        sb.append("时间：").append(nowTime);
        return sb.toString();
    }

    /** 单测：坐标缓存查询（不依赖 SHARED 安装）。 */
    Building buildingAtForTest(int x, int y) {
        return buildingByPoint.get(x + "," + y);
    }

    /** 写入采集点缓存（空矿点会被后续「无人采集」覆盖掉）。 */
    void recordResource(int x, int y, long uid, long aid, long marchId) {
        recordResourceAt(x, y, uid, aid, marchId, System.currentTimeMillis());
    }

    /** 带时间戳的采集点写入（单测可造过期数据）。 */
    void recordResourceAt(int x, int y, long uid, long aid, long marchId, long atMs) {
        if (x <= 0 || y <= 0) return;
        ResPoint rp = new ResPoint();
        rp.uid = uid;
        rp.aid = aid;
        rp.marchId = marchId;
        rp.atMs = atMs;
        resByPoint.put(x + "," + y, rp);
        if (resByPoint.size() > 20000) {
            long now = System.currentTimeMillis();
            resByPoint.entrySet().removeIf(e -> now - e.getValue().atMs > 30L * 60_000L);
        }
    }

    /**
     * 世界地图对象 → 城缓存。
     *
     * <p>2026-11-04（生产 04:27 坐标乱跳根因）：world_mapobj 的 {@code type} 与行军 {@code type}
     * 同名字段。侦查等行军单位也会带 {@code map_city}（归属城快照）+ 行军 {@code point} 下发，
     * 只判 {@code type==1} 拦不住（侦查行军 type 也是 1），会把「行军当前位置」当城坐标：
     * 同一监视目标实测 30 秒内在 (573,428)/(739,380)/(179,250) 间跳，用户按坐标找不到人。
     * 城对象只有 {@code map_city/point/type}，没有 owner/startpoint/endpoint/move_xxx/squad_list 等
     * 行军字段 → 命中任一即判定「非城」丢弃（不再把行军位置当城坐标）。
     */
    private void parseCity(Map<?, ?> item, Set<Long> seenUids) {
        // 2026-11-02：world_mapobj type: 1=城 2=资源矿 3=怪物…；只有 type=1 才可能是城。
        if (item.containsKey("type")) {
            long objType = asLong(item.get("type"), 0L);
            if (objType > 0L && objType != 1L) return;
        }
        Map<?, ?> city = asMap(item.get("map_city"));
        if (city.isEmpty()) {
            city = asMap(item.get("city"));
        }
        if (city.isEmpty()) return;
        long uid = asLong(city.get("uid"), 0L);
        if (uid <= 0) return;
        // 2026-11-04：行军/队列对象（含 map_city 归属城快照）不是城——按对象 point 记会得到行军位置。
        if (looksLikeMarch(item)) {
            logNonCityItem(uid, item);
            return;
        }
        // 2026-11-04：world_mapobj 是 union，城只会有 map_city；带 map_camp/map_resource 等的不是城。
        if (hasNonCitySubtype(item)) {
            logNonCityItem(uid, item);
            return;
        }
        City c = new City();
        c.uid = uid;
        c.aid = asLong(city.get("aid"), 0L);
        c.lv = asLong(city.get("lv"), 0L);
        c.nick = str(city.get("nickName"));
        Map<?, ?> point = asMap(item.get("point"));
        c.x = (int) asLong(point.get("x"), 0L);
        c.y = (int) asLong(point.get("y"), 0L);
        if (c.x <= 0 && c.y <= 0) return;
        // 2026-11-04：命中监视目标自己的在途行军终点 → 伪城对象（城快照+行军终点），不是他的城。
        // 只在「坐标要变化」时判定：观测点与当前城坐标一致时不能再否决（否则真城会被判成伪城）。
        WatchPointStore.Target mt0 = findTarget(uid);
        if (mt0 != null && (mt0.x != c.x || mt0.y != c.y) && isMarchEndpointOf(uid, c.x, c.y)) {
            logNonCityItem(uid, item);
            return;
        }
        cityByUid.put(uid, c);
        cityByPoint.put(c.x + "," + c.y, c);
        seenUids.add(uid);
        if (scan.active) scan.cityCount++;
        // 2026-11-04：实时昵称缓存（推送优先用它，不用配置/目录里的旧名）
        if (c.nick != null && !c.nick.isEmpty()) liveNickByUid.put(uid, c.nick);
        // 2026-11-02：任何一次回包看到监视目标的新坐标 → 立刻更新 + 通知（不用等整图扫完）
        if (isMonitoredUid(uid)) {
            WatchPointStore.Target mt = findTarget(uid);
            if (mt != null && (mt.x != c.x || mt.y != c.y)) {
                // 诊断留痕：坐标变化来自哪个对象（线上核对「行军对象混入」用）
                FileLog.info("[{}] 监视目标城对象：uid={} ({},{}) nick={} scan={} type={} keys={}",
                        LOG, String.valueOf(uid), String.valueOf(c.x), String.valueOf(c.y),
                        c.nick, String.valueOf(scan.active), String.valueOf(item.get("type")),
                        item.keySet());
            }
            noteTargetCity(uid, c.x, c.y, c.nick);
        }
    }

    /** 行军/队列对象特征字段：城对象不会有这些字段。 */
    private static final String[] MARCH_SHAPE_KEYS = {
            "owner", "startpoint", "endpoint", "move_startpoint", "move_endpoint",
            "squad_list", "march_id", "mass_id", "target_type", "endtime", "target"
    };

    /** 是否行军/队列形状（2026-11-04：type=1 的侦查行军也会带 map_city，必须靠字段形状排除）。 */
    static boolean looksLikeMarch(Map<?, ?> item) {
        if (item == null) return true;
        for (String k : MARCH_SHAPE_KEYS) {
            if (item.containsKey(k)) return true;
        }
        return false;
    }

    /**
     * 2026-11-04：是否带了「非城」的地图子对象（world_mapobj 是 union：城只会有 map_city）。
     * 行军营地等伪城对象常同时带 map_camp/map_resource 等字段——命中即不是城。
     * {@code map_expression} 是地图表情叠层，允许与城共存（不据此判非城）。
     */
    static boolean hasNonCitySubtype(Map<?, ?> item) {
        if (item == null) return true;
        for (Object k : item.keySet()) {
            String s = String.valueOf(k);
            if (s.startsWith("map_") && !"map_city".equals(s) && !"map_expression".equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** 记录监视目标在途行军的终点（含 move_endpoint 当前目标点）；自循环/回自己城的不记。 */
    void rememberMarchEndpoint(long uid, int sx, int sy, int ex, int ey, Map<?, ?> moveEnd) {
        if (uid <= 0L || ex <= 0 || ey <= 0) return;
        if (sx > 0 && sy > 0 && sx == ex && sy == ey) return;   // 自循环（起点=终点）
        WatchPointStore.Target t = findTarget(uid);
        if (t != null && t.x == ex && t.y == ey) return;        // 终点就是当前城坐标：别把真城当伪城
        long now = System.currentTimeMillis();
        long exp = now + MARCH_ENDPOINT_TTL_MS;
        Map<String, Long> pts = marchEndpointsByUid.computeIfAbsent(
                uid, k -> new java.util.concurrent.ConcurrentHashMap<>());
        // e: 精确终点（与伪城对象 point 同源，必须精确匹配；容差过大会把城边的行军终点当成城）
        pts.put("e:" + ex + "," + ey, exp);
        if (moveEnd != null && !moveEnd.isEmpty()) {
            int mx = (int) Math.round(asLong(moveEnd.get("x"), 0L));
            int my = (int) Math.round(asLong(moveEnd.get("y"), 0L));
            if (mx > 0 && my > 0) pts.put("m:" + mx + "," + my, exp);   // m: 行军当前目标点（double 取整，±1）
        }
        if (pts.size() > 256) pts.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** 该坐标是否命中监视目标的在途行军终点：终点精确匹配；move_endpoint 允许 ±1 格取整误差。 */
    boolean isMarchEndpointOf(long uid, int x, int y) {
        Map<String, Long> pts = marchEndpointsByUid.get(uid);
        if (pts == null || pts.isEmpty()) return false;
        long now = System.currentTimeMillis();
        Long exact = pts.get("e:" + x + "," + y);
        if (exact != null && exact >= now) return true;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                Long exp = pts.get("m:" + (x + dx) + "," + (y + dy));
                if (exp != null && exp >= now) return true;
            }
        }
        return false;
    }

    /** 非城对象诊断（只对监视目标记一次，避免刷日志）。 */
    private void logNonCityItem(long uid, Map<?, ?> item) {
        if (uid <= 0L || !isMonitoredUid(uid)) return;
        if (!nonCityLogged.add(uid)) return;
        FileLog.info("[{}] 忽略非城地图对象（含 map_city，疑行军/队列，不作为城坐标）：uid={} "
                        + "type={} keys={} point={}",
                LOG, String.valueOf(uid), String.valueOf(item == null ? null : item.get("type")),
                item == null ? "null" : item.keySet(),
                item == null ? "null" : String.valueOf(item.get("point")));
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
            FileLog.info("[{}] 监视目标 {} 本轮未出现（连续 {} 轮，最后坐标 {},{})", LOG,
                    t.display(), String.valueOf(n), String.valueOf(t.x), String.valueOf(t.y));
            if (n >= 2) handleTargetLost(t);
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
        Map<?, ?> sp = asMap(m.get("startpoint"));
        Map<?, ?> ep = asMap(m.get("endpoint"));
        int sx = (int) asLong(sp.get("x"), -1L);
        int sy = (int) asLong(sp.get("y"), -1L);
        int ex = (int) asLong(ep.get("x"), -1L);
        int ey = (int) asLong(ep.get("y"), -1L);
        // 2026-11-02：不用行军 startpoint 校正坐标——行军对象的“当前位置”会随部队移动，
        // 拿它当城坐标会让监视窗口来回跳（实测同一目标 4 秒内在多个坐标间抖动）。
        // 坐标只认地图城对象（type=1）与整图扫描结果。
        // 2026-11-02：行军状态过滤——2=在途 / 3=到达 / 4=回程；只对在途告警，
        // 否则「进攻后返回」会被当成「打自己」（终点=自己城）重复通知。
        long endtime = asLong(m.get("endtime"), 0L);
        long marchState = asLong(m.get("state"), 0L);
        // 2026-11-04：记录监视目标的在途行军终点（伪城对象识别用，见 isMarchEndpointOf）
        if (marchState == 2L && isMonitoredUid(owner)) {
            rememberMarchEndpoint(owner, sx, sy, ex, ey, asMap(m.get("move_endpoint")));
        }
        // 2026-11-02：采集行军（type=4）本身就是「这个点在采」的证据 → 记录采集人，供采集点被攻击告警用
        // 2026-11-04 用户口径：监视目标资源类行为（采集/建设/破坏），每人 5 分钟前 3 条。
        if (type == 4L && marchState == 2L) {
            recordResource(ex, ey, owner,
                    asLong(asMap(m.get("collect")).get("target_aid"), asLong(m.get("aid"), 0L)), id);
            if (ctx.kind == Kind.MONITOR && isMonitoredUid(owner) && ctx.rect.contains(sx, sy)
                    && !(sx == ex && sy == ey)) {
                maybeResourceAlert(owner, id, "采集", ex, ey, endtime, troopsOf(m));
            }
            return;
        }
        // 攻击采集点（A3 资源·受袭）：只认出征(type=2)，且攻击者/采集者不能同联盟/同友盟。
        ResPoint rp = resByPoint.get(ex + "," + ey);
        if (rp != null && rp.uid > 0L && rp.uid != owner && type == 2L) {
            if (marchState == 2L && gatherRelevant(owner, rp)
                    && gatherAttackValid(m, owner, rp, ex, ey)) {
                gatherAttackAlert(owner, id, type, sx, sy, ex, ey, endtime, troopsOf(m), rp);
            }
            return;   // 采集点攻击走专门文案，不再重复发「目标城未知」的攻城告警
        }
        if (marchState >= 3L) return;
        // 只告警「打城」：地图行军的 atk_city/recon_city 在 map_info 数据里为空，用终点坐标是否命中已知城坐标判定
        City targetCity = cityNear(ex, ey);
        long targetUid = targetCity == null ? 0L : targetCity.uid;
        long targetAid = targetCity == null ? 0L : targetCity.aid;
        City ownerCity = cityByUid.get(owner);
        long ownerAid = ownerCity == null ? 0L : ownerCity.aid;
        // 被打飞判定：敌军（非自己）进攻监视目标的城时记一笔，用于「迁城 vs 破城随机飞走」文案
        if (targetCity != null && isMonitoredUid(targetCity.uid) && owner != targetCity.uid
                && (type == 2L || type == 100L || type == 102L)) {
            targetAttackedAt.put(targetCity.uid, System.currentTimeMillis());
        }
        boolean selfMarch = isSelfMarch(sx, sy, ex, ey, targetUid, owner);
        if (ctx.kind == Kind.MONITOR) {
            if (!ctx.rect.contains(sx, sy)) return;
            if (!isMonitoredUid(owner)) return;
            if (selfMarch) {
                statSelfIgnored.incrementAndGet();
                return;
            }
            // 资源类：按目标归属区分「建设(自己/同阵营)」与「破坏(敌对/中立)」；采集已在上方处理
            String resBeh = resourceBehavior(m, type, ownerAid, ex, ey);
            if (resBeh != null) {
                maybeResourceAlert(owner, id, resBeh, ex, ey, endtime, troopsOf(m));
                return;
            }
            // A1 情报：侦查 / 驻防·增援(含援助 type6) / 发起集结（统一 5 分钟前 3 条）
            // 注意：驻防/援助的目标本来就是同联盟城，不能套用「同联盟不推」的进攻过滤。
            if (type == 1L || type == 5L || type == 6L || type == 100L) {
                String marchKey = "m:" + owner + ":" + id;
                if (recentlyAlerted(marchKey)) return;
                long now = System.currentTimeMillis();
                if (!scoutQuotaAllow(intelAlertTimes, owner, now, SCOUT_WINDOW_MS, SCOUT_MAX_PER_WINDOW)) {
                    FileLog.info("[{}] 情报告警限流（5 分钟内已发 {} 条）：uid{}", LOG,
                            String.valueOf(SCOUT_MAX_PER_WINDOW), String.valueOf(owner));
                    return;
                }
                alertOutgoing(owner, id, type, ex, ey, endtime, troopsOf(m), marchKey);
                return;
            }
            // 进攻/集结出征由战争警报统一推送（A2），观察点不重复推
            return;
        } else if (ctx.kind == Kind.PROTECT) {
            if (!ctx.rect.contains(ex, ey)) return;
            if (selfMarch) {
                statSelfIgnored.incrementAndGet();
                return;
            }
            // A2 敌袭：只报「进攻(2)/集结出征(102)」
            if (type != 2L && type != 102L) return;
            // 2026-11-09 误报修复（用户实测）：巨兽模板的集结出征实为 PvE 行军，不能当敌袭推送。
            // 原来这里只判 type/友盟城/友军，缺 PvE 过滤（采集点分支有，A2 分支漏了）。
            String pve = pveMarchReason(m);
            if (!pve.isEmpty()) {
                statPveIgnored.incrementAndGet();
                logPveIncomingSkip(type, owner, ex, ey, pve);
                return;
            }
            // 来袭只对「已知友盟城」告警；联盟未知目标仅 INFO 记录候选，不推送。
            if (targetCity == null || targetAid <= 0L) {
                statUnknownTargetIgnored.incrementAndGet();
                logUnknownIncomingCandidate(type, owner, ex, ey);
                return;
            }
            boolean alert = shouldAlertIncoming(selfMarch, ownerAid, targetAid,
                    store.friendAids, type, store.incomingRallyFollowerAlerts);
            // 监视目标发起的行军由战争警报统一推（避免同一条行军重复通知）
            if (alert && isMonitoredUid(owner)) alert = false;
            if (!alert) return;
            String marchKey = "m:" + owner + ":" + id;
            if (recentlyAlerted(marchKey)) return;   // 敌情通道已推过同一条行军
            alertIncoming(owner, ownerCity, id, type, ex, ey, endtime, troopsOf(m), marchKey);
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

    /** PvE 来袭候选静默（同 type/owner/坐标只记一次；reason 便于部署后核对命中字段）。 */
    private void logPveIncomingSkip(long type, long owner, int ex, int ey, String reason) {
        String key = type + ":" + owner + ":" + ex + ":" + ey;
        if (pveIncomingLogged.size() > 2000) pveIncomingLogged.clear();
        if (!pveIncomingLogged.add(key)) return;
        FileLog.info("[{}] 来袭候选被过滤（PvE 巨兽/野外集结，不推送）：type={} 攻击者=uid{} "
                        + "目标=({},{}) 特征={}",
                LOG, String.valueOf(type), String.valueOf(owner),
                String.valueOf(ex), String.valueOf(ey), reason);
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
        return AlertRules.isSelfLoop(sx, sy, ex, ey, targetUid, owner);
    }

    /**
     * 来袭告警判定（PROTECT）：2026-10-28 收紧为「已知友盟城才告警」。
     * 注意：目标联盟未知（aid&lt;=0）由调用方先计数过滤（{@code statUnknownTargetIgnored}），
     * 这里再防御一次；友军发起的增援/自家部队不告警；99/100 随从类可被配置静默。
     */
    static boolean shouldAlertIncoming(boolean selfMarch, long ownerAid, long targetAid,
                                       Set<Long> friendAids, long type, boolean rallyFollowerAlerts) {
        return AlertRules.incomingAllowed(selfMarch, ownerAid, targetAid, friendAids, type,
                rallyFollowerAlerts);
    }

    /** 敌情告警判定（MONITOR）：自循环已单独过滤；同联盟/自家城之间的行军不算"打谁"（原有口径）。 */
    static boolean shouldAlertMonitor(long ownerAid, long targetAid) {
        return AlertRules.monitorAllowed(ownerAid, targetAid);
    }

    /** 联盟标注（纯函数）：aid&gt;0 → " [标签]"；联盟未知(aid&lt;=0) → "（联盟未知）"，不再留空白。 */
    static String allySuffix(long aid, Map<Long, String> aidLabels) {
        return allySuffix(aid, aidLabels, null);
    }

    /**
     * 联盟标注（2026-11-02）：地图城堡昵称形如 {@code [818]小淑女i}，简称就在昵称前缀里。
     * 取标签优先级：保护目录 aidLabels → 昵称 [简称] → {@code 盟<aid>} → 联盟未知。
     */
    static String allySuffix(long aid, Map<Long, String> aidLabels, String nick) {
        String label = aid > 0L && aidLabels != null ? aidLabels.get(aid) : null;
        if (label == null || label.isEmpty()) label = abbrFromNick(nick);
        if (label != null && !label.isEmpty()) return " [" + label + "]";
        // 2026-11-02 用户要求：通知里不带 aid/uid，联盟未知时只给文字，不给数字
        return aid > 0L ? "" : "（联盟未知）";
    }

    /**
     * 联盟简称前缀（2026-11-04 用户口径：所有联盟简称放在游戏昵称前面）：
     * {@code [SYD]昵称}；无简称返回空串（只显示昵称）。
     */
    static String allyPrefix(long aid, Map<Long, String> aidLabels, String nick) {
        String label = aid > 0L && aidLabels != null ? aidLabels.get(aid) : null;
        if (label == null || label.isEmpty()) label = abbrFromNick(nick);
        return label == null || label.isEmpty() ? "" : "[" + label + "]";
    }

    /** 带联盟简称前缀的显示名：[SYD]昵称（无简称则原样）。 */
    static String prefixed(long aid, Map<Long, String> aidLabels, String nick) {
        String n = nick == null ? "" : nick.trim();
        String pre = allyPrefix(aid, aidLabels, n);
        if (pre.isEmpty()) return n;
        return n.startsWith("[") ? n : pre + n;
    }

    /** 城堡昵称里的联盟简称：{@code "[818]小淑女i"} → {@code "818"}；无前缀/异常返回空串。 */
    static String abbrFromNick(String nick) {
        if (nick == null) return "";
        String s = nick.trim();
        if (!s.startsWith("[")) return "";
        int end = s.indexOf(']');
        if (end <= 1) return "";
        String a = s.substring(1, end).trim();
        return a.isEmpty() || a.length() > 12 ? "" : a;
    }

    /**
     * 城昵称（2026-11-04 用户口径）：优先「实时地图昵称」（最近一次城对象 nickName）→ 城缓存 →
     * 玩家目录兜底；都取不到返回 null。绝不再优先用配置/目录里两天前的旧昵称。
     */
    private String nickOf(City c) {
        if (c == null) return null;
        String live = liveNickByUid.get(c.uid);
        if (live != null && !live.isEmpty()) return live;
        if (c.nick != null && !c.nick.isEmpty()) return c.nick;
        PlayerDirectory dir = PlayerDirectory.shared();
        if (dir != null) {
            PlayerDirectory.Entry e = dir.byUid(c.uid);
            if (e != null && e.nick != null && !e.nick.isEmpty()) return e.nick;
        }
        return c.nick;
    }

    /** 实时昵称（uid → 最近一次地图城对象昵称）；没有返回空串。 */
    String liveNick(long uid) {
        String n = liveNickByUid.get(uid);
        return n == null ? "" : n;
    }

    /** 监视目标条目（无则 null）。 */
    private WatchPointStore.Target findTarget(long uid) {
        for (WatchPointStore.Target t : store.monitorTargets) {
            if (t != null && t.uid == uid) return t;
        }
        return null;
    }

    /**
     * 记录监视目标的最新坐标（2026-11-02）：任一地图城对象都能喂进来。
     * 坐标变了 → 更新配置 + 企微通知；2026-11-04：昵称实时回写、丢失/恢复不再单独推送。
     */
    void noteTargetCity(long uid, int x, int y, String gameNick) {
        noteTargetCity(uid, x, y, gameNick, false);
    }

    /** force 参数保留兼容（丢失后重新找到也走同一聚合窗口：1 分钟内只推最终坐标）。 */
    void noteTargetCity(long uid, int x, int y, String gameNick, boolean force) {
        if (uid <= 0L || x <= 0 || y <= 0) return;
        WatchPointStore.Target t = findTarget(uid);
        if (t == null) return;
        long now = System.currentTimeMillis();
        targetSeenAt.put(uid, now);
        boolean wasLost = targetLostAt.remove(uid) != null;
        targetSearchAt.remove(uid);
        targetMisses.remove(uid);
        // 2026-11-04：实时游戏昵称回写（推送/日志/App 配置页统一用实时名，不再用旧缓存名）
        if (gameNick != null && !gameNick.isEmpty()) {
            liveNickByUid.put(uid, gameNick);
            if (!gameNick.equals(t.nick)) {
                t.nick = gameNick;
                store.save();
            }
        }
        if (t.x == x && t.y == y) return;   // 位置未变：静默（丢失后恢复也不单独推送）
        int ox = t.x;
        int oy = t.y;
        int jump = Math.max(Math.abs(x - ox), Math.abs(y - oy));
        // 坐标随时更新（监视窗口要跟着他走）；推送走「1 分钟聚合窗口」，窗口内只累计最新坐标
        t.x = x;
        t.y = y;
        store.save();
        if (jump <= 2 && !wasLost) return;   // 城点相邻格口径误差：静默校正
        // 2026-11-04 用户口径：1 分钟内高迁多次只推最后一次（前面几次都是无效推送）。
        // 第一次变化开启窗口（记录窗口起点坐标 ox/oy），窗口内后续变化只覆盖最新坐标，窗口到期才推。
        PendingMove old = pendingMoveAlert.get(uid);
        int baseX = old == null ? ox : old.ox;
        int baseY = old == null ? oy : old.oy;
        long windowStart = old == null ? now : old.windowStart;
        pendingMoveAlert.put(uid, new PendingMove(baseX, baseY, x, y, gameNick, windowStart));
        FileLog.info("[{}] 坐标已更新（{} 秒聚合窗口内只推最后一次）：uid={} 窗口起点 ({},{}) → 最新 ({},{})",
                LOG, String.valueOf(moveAlertWindowMs / 1000L), String.valueOf(uid),
                String.valueOf(baseX), String.valueOf(baseY),
                String.valueOf(x), String.valueOf(y));
    }

    /** 聚合窗口到期后推「窗口内最后一次坐标」（主循环每 80ms 调用；保证只推最终坐标）。 */
    private void flushPendingMoveAlerts() {
        if (pendingMoveAlert.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, PendingMove> en : pendingMoveAlert.entrySet()) {
            long uid = en.getKey();
            PendingMove p = en.getValue();
            if (p == null || now - p.windowStart < moveAlertWindowMs) continue;
            if (!pendingMoveAlert.remove(uid, p)) continue;
            WatchPointStore.Target t = findTarget(uid);
            if (t == null) continue;
            if (p.x == p.ox && p.y == p.oy) continue;   // 窗口内绕回原坐标：无净变化，不推
            fireMoveAlert(uid, t, p.ox, p.oy, p.x, p.y, p.nick);
        }
    }

    /**
     * 推送坐标更新（2026-11-04 用户口径）：
     * <pre>
     * 📍 观察点·坐标更新
     * 游戏昵称：时装小尓
     * 原坐标 (739,380) → 新坐标 (179,250)
     * 时间：04:27:21
     * </pre>
     * 不带原因、不带丢失/重新找到说明、不带 uid/区服等编号。
     */
    private void fireMoveAlert(long uid, WatchPointStore.Target t, int ox, int oy,
                               int x, int y, String gameNick) {
        String nick = gameNick == null ? "" : gameNick.trim();
        if (nick.isEmpty()) nick = liveNick(uid);
        if (nick.isEmpty() && t != null && t.nick != null) nick = t.nick.trim();
        if (nick.isEmpty()) nick = "未知名玩家";
        StringBuilder sb = new StringBuilder("📍 观察点·坐标更新\n");
        sb.append("游戏昵称：").append(nick).append('\n');
        if (ox > 0 && oy > 0) {
            sb.append("原坐标 (").append(ox).append(',').append(oy).append(") → 新坐标 (")
                    .append(x).append(',').append(y).append(")\n");
        } else {
            sb.append("新坐标 (").append(x).append(',').append(y).append(")\n");
        }
        sb.append("时间：").append(nowText());
        alert("move:" + uid + ":" + x + ":" + y, sb.toString());
        // 原因只留运维日志，不再进推送
        FileLog.info("[{}] 监视目标坐标更新：uid={} ({},{}) → ({},{}) 游戏昵称={} 原因={}", LOG,
                String.valueOf(uid), String.valueOf(ox), String.valueOf(oy),
                String.valueOf(x), String.valueOf(y), nick, relocationReason(uid));
    }

    /** 迁城原因文案：近 30 分钟内有敌军进攻其城 → 疑似被破城随机飞走。 */
    private String relocationReason(long uid) {
        Long atk = targetAttackedAt.get(uid);
        boolean flew = atk != null && System.currentTimeMillis() - atk < 30L * 60_000L;
        return flew ? "原因：近 30 分钟内有敌军进攻其城 → 疑似被破城随机飞走"
                : "原因：主动迁城 / 被盟友传送 / 未知";
    }

    /** 保护名 → 联盟 aid（支持 THY-1/THY-2 这类同盟拆分矩形）。 */
    private long aidForProtectName(String name) {
        if (name == null) return 0L;
        String n = name.trim();
        for (Map.Entry<Long, String> e : store.aidLabels.entrySet()) {
            if (n.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        int dash = n.indexOf('-');
        if (dash > 0) {
            String base = n.substring(0, dash);
            for (Map.Entry<Long, String> e : store.aidLabels.entrySet()) {
                if (base.equalsIgnoreCase(e.getValue())) return e.getKey();
            }
        }
        return 0L;
    }

    /**
     * 保护矩形为空（连续 2 次完整扫描）→ 按该联盟当前城群重算聚集地中心：
     * 自动把矩形移到新中心并推送「📍 观察点·保护更新」（2026-11-04 用户口径）。
     */
    private void relocateProtectRect(WatchPointStore.Rect r, int streak) {
        long aid = aidForProtectName(r.name);
        if (aid <= 0L) {
            FileLog.info("[{}] 保护矩形 {} 连续 {} 次为空，但无法解析联盟 aid，跳过迁移",
                    LOG, r.name, String.valueOf(streak));
            return;
        }
        java.util.List<City> cs = new java.util.ArrayList<>();
        for (City c : cityByUid.values()) {
            if (c != null && c.aid == aid && c.x > 0 && c.y > 0) cs.add(c);
        }
        if (cs.size() < 5) {
            FileLog.info("[{}] 保护矩形 {} 连续 {} 次为空，联盟 aid={} 城数不足 {}，跳过迁移",
                    LOG, r.name, String.valueOf(streak), String.valueOf(aid), String.valueOf(cs.size()));
            return;
        }
        int nx = medianCity(cs, true);
        int ny = medianCity(cs, false);
        int ox = (r.x1 + r.x2) / 2;
        int oy = (r.y1 + r.y2) / 2;
        if (Math.max(Math.abs(nx - ox), Math.abs(ny - oy)) < 5) {
            return;   // 基本没搬，不刷屏
        }
        int w = Math.max(10, r.x2 - r.x1);
        int h = Math.max(10, r.y2 - r.y1);
        int x1 = clampInt(nx - w / 2, 0, MAP_MAX - w);
        int y1 = clampInt(ny - h / 2, 0, MAP_MAX - h);
        String aname = allianceNames.getOrDefault(aid, "");
        String abbr = store.aidLabels.getOrDefault(aid, "");
        if (abbr == null || abbr.isEmpty()) abbr = r.name == null ? "" : r.name.trim();
        String label = (abbr.isEmpty() ? "" : "[" + abbr + "]") + aname;
        if (label.isEmpty()) label = "联盟" + aid;
        alert("rect-move:" + r.name + ":" + (scan.startedAt / 3_600_000L),
                "📍 观察点·保护更新\n联盟：" + label
                        + "\n原坐标 (" + ox + "," + oy + ") → 新坐标 (" + nx + "," + ny + ")"
                        + "\n时间：" + nowText());
        r.x1 = x1;
        r.y1 = y1;
        r.x2 = x1 + w;
        r.y2 = y1 + h;
        store.save();
        rectEmptyStreak.remove(r.name);
        FileLog.info("[{}] 保护矩形 {} 已迁移：({},{}) → ({},{})，新矩形 ({},{})-({},{})",
                LOG, r.name, String.valueOf(ox), String.valueOf(oy), String.valueOf(nx),
                String.valueOf(ny), String.valueOf(r.x1), String.valueOf(r.y1),
                String.valueOf(r.x2), String.valueOf(r.y2));
    }

    /** 城群坐标中位数（抗离群点）。 */
    private static int medianCity(java.util.List<City> cs, boolean xAxis) {
        int[] a = new int[cs.size()];
        for (int i = 0; i < cs.size(); i++) a[i] = xAxis ? cs.get(i).x : cs.get(i).y;
        java.util.Arrays.sort(a);
        return a[a.length / 2];
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 监视目标在监视窗口内连续未出现（丢失视野）：
     * ① 先看已知视野（城缓存里是否已有他的新坐标）→ 有就直接按新坐标推送；
     * ② 没有就发起「以最后坐标为中心」的搜索扫描，先扫周边再扫全图未知区域，扫到即推送新坐标。
     *
     * <p>2026-11-04 用户口径：**丢失/搜索过程不推送**（不再发「📡 目标暂时丢失」），只推最终新坐标。
     */
    void handleTargetLost(WatchPointStore.Target t) {
        long now = System.currentTimeMillis();
        City known = cityByUid.get(t.uid);
        if (known != null && (known.x != t.x || known.y != t.y)) {
            // 已知视野里就有 → 直接推送新坐标（不发起扫描）
            noteTargetCity(t.uid, known.x, known.y, known.nick, true);
            return;
        }
        if (targetLostAt.putIfAbsent(t.uid, now) == null) {
            // 只记日志，不推送（用户：不需要推送他失踪了你在找他）
            FileLog.info("[{}] 监视目标丢失视野：{} 最后坐标 ({},{})，静默搜索", LOG,
                    t.display(), String.valueOf(t.x), String.valueOf(t.y));
        }
        Long lastSearch = targetSearchAt.get(t.uid);
        if (lastSearch == null || now - lastSearch > 90_000L) {
            targetSearchAt.put(t.uid, now);
            startScan("目标丢失搜索：" + t.display(), t.x, t.y);
        }
    }

    private boolean isMonitoredUid(long uid) {
        for (WatchPointStore.Target t : store.monitorTargets) {
            if (t.uid == uid) return true;
        }
        return false;
    }

    private void alertOutgoing(long owner, long marchId, long type, int ex, int ey, long endtime,
                               long troops, String marchKey) {
        String key = "out:" + owner + ":" + marchId;
        if (recentlyAlerted(key)) return;   // 同一条行军的轮询重放：不推送也不再扣配额
        long nowMs = System.currentTimeMillis();
        if (marchKey != null) alerted.put(marchKey, nowMs);   // 标记：另一通道不再重复推
        City ownerCity = cityByUid.get(owner);
        City target = cityNear(ex, ey);
        // 2026-11-04：攻击者名优先实时地图昵称（不用配置/目录旧名）
        String nick = nickOf(ownerCity);
        if (nick == null || nick.isEmpty()) nick = liveNick(owner);
        if (nick == null || nick.isEmpty()) nick = "未知名玩家";
        String text = outgoingAlertText(nick, ownerCity == null ? 0L : ownerCity.aid, store.aidLabels,
                type, nickOf(target), target == null ? 0L : target.aid,
                ex, ey, endtime - System.currentTimeMillis() / 1000L, troops, nowText(),
                store.feintTroopsBelow);
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
        return outgoingAlertText(nick, ownerAid, aidLabels, type, targetNick, targetAid,
                ex, ey, etaSeconds, troops, nowTime, 0L);
    }

    static String outgoingAlertText(String nick, long ownerAid, Map<Long, String> aidLabels,
                                    long type, String targetNick, long targetAid,
                                    int ex, int ey, long etaSeconds, long troops, String nowTime,
                                    long feintBelow) {
        // 2026-11-04 用户口径：🚨 敌情·{行为}；简称放昵称前；目标/坐标未知显示（未知）。
        StringBuilder sb = new StringBuilder("🚨 敌情·").append(a1Behavior(type)).append('\n');
        sb.append("发起人：").append(prefixed(ownerAid, aidLabels, nick)).append('\n');
        boolean hasTarget = targetNick != null && !targetNick.isEmpty();
        sb.append("目标：").append(hasTarget ? prefixed(targetAid, aidLabels, targetNick) : "（未知）").append('\n');
        if (ex > 0 && ey > 0) {
            sb.append("坐标：(").append(ex).append(',').append(ey).append(")\n");
        } else {
            sb.append("坐标：（未知）\n");
        }
        sb.append("时间：").append(nowTime);
        return sb.toString();
    }

    /** A1 情报行为：1 侦查 / 5 驻防·增援 / 100 发起集结。 */
    static String a1Behavior(long type) {
        if (type == 1L) return "侦查";
        if (type == 5L || type == 6L) return "驻防/增援";
        if (type == 100L) return "发起集结";
        return typeLabel(type);
    }

    /** A2 敌袭行为：2 进攻 / 102 集结出征。 */
    static String a2Behavior(long type) {
        return AlertText.a2Behavior(type);
    }

    private void alertIncoming(long owner, City ownerCity, long marchId, long type,
                               int ex, int ey, long endtime, long troops, String marchKey) {
        // 集结随从/集结类按「目标点」聚合去重：一次集结几十个随从，避免刷屏与触发企微 20 条/分钟限速
        String key = (type == 99L || type == 100L || type == 102L || type == 10L || type == 12L)
                ? "in-rally:" + ex + ":" + ey
                : "in:" + owner + ":" + marchId;
        if (recentlyAlerted(key)) return;
        long nowMs = System.currentTimeMillis();
        if (marchKey != null) alerted.put(marchKey, nowMs);
        City target = cityNear(ex, ey);
        // 2026-11-04：攻击者名优先实时地图昵称
        String who = nickOf(ownerCity);
        if (who == null || who.isEmpty()) who = liveNick(owner);
        if (who == null || who.isEmpty()) who = "未知名玩家";
        String text = incomingAlertText(who, ownerCity == null ? 0L : ownerCity.aid, store.aidLabels,
                type, nickOf(target), target == null ? 0L : target.aid,
                ex, ey, endtime - System.currentTimeMillis() / 1000L, troops, nowText(),
                store.feintTroopsBelow);
        alert(key, text);
        trackAttacker(owner, ownerCity);
    }

    /** 采集点缓存（只读，单测用）。 */
    Map<String, ResPoint> resByPointForTest() {
        return resByPoint;
    }

    /** 终点是不是「有人在采的矿点」且来的是出征（2026-11-04：只有 type=2 出征，集结类不算）。 */
    boolean isGatherAttack(long owner, int ex, int ey, long type) {
        ResPoint rp = resByPoint.get(ex + "," + ey);
        return rp != null && rp.uid > 0L && rp.uid != owner && type == 2L;
    }

    /**
     * 采集点「被攻击」是否可信（2026-11-04 生产误报修复）：
     * <ul>
     *   <li>盟友之间无法互相攻击 → 攻击者/采集者同联盟（或昵称同 [abbr]）一律不推；</li>
     *   <li>只有「出征」(type=2) 才可能打采集点；集结类已在调用方排除；</li>
     *   <li>巨兽/野外/采集/打野等 PvE 行军终点也可能落在矿点上 → 不算打采集点；</li>
     *   <li>采集点缓存超过 TTL 未刷新（人早走了）→ 不可信。</li>
     * </ul>
     */
    boolean gatherAttackValid(Map<?, ?> m, long owner, ResPoint rp, int ex, int ey) {
        if (rp == null) return false;
        if (isPveMarch(m)) {
            logGatherSkip(owner, rp, ex, ey, "PvE 行军（巨兽/野外集结/采集/打野）");
            return false;
        }
        long atkAid = aidOf(owner);
        long vicAid = rp.aid > 0L ? rp.aid : aidOf(rp.uid);
        if (atkAid > 0L && vicAid > 0L) {
            if (atkAid == vicAid) {
                logGatherSkip(owner, rp, ex, ey, "同联盟（盟友无法互相攻击）");
                return false;
            }
            // 2026-11-04：同阵营友盟之间也不会互相攻击（生产 SHE→KFC 等 1 兵「集结主体」误报）
            if (store.friendAids.contains(atkAid) && store.friendAids.contains(vicAid)) {
                logGatherSkip(owner, rp, ex, ey, "同阵营友盟（友军之间不互相攻击）");
                return false;
            }
        }
        if (atkAid <= 0L || vicAid <= 0L) {
            // 联盟未知时用昵称里的 [abbr] 兜底（游戏昵称通常带联盟简称）
            String atkAbbr = abbrFromNick(displayNick(owner));
            String vicAbbr = abbrFromNick(displayNick(rp.uid));
            if (!atkAbbr.isEmpty() && atkAbbr.equalsIgnoreCase(vicAbbr)) {
                logGatherSkip(owner, rp, ex, ey, "同联盟简称（盟友无法互相攻击）");
                return false;
            }
        }
        if (System.currentTimeMillis() - rp.atMs > GATHER_POINT_TTL_MS) {
            logGatherSkip(owner, rp, ex, ey, "采集点缓存过期（>"
                    + (GATHER_POINT_TTL_MS / 60_000L) + " 分钟未刷新）");
            return false;
        }
        return true;
    }

    /** 巨兽/野外集结/采集/打野等 PvE 行军的特征字段（命中任一即不是「打采集点」）。 */
    static final String[] PVE_MARCH_KEYS = {
            "mass_boss", "atk_monster", "mass_icefield_hunter_boss", "atk_monster_auto",
            "transaction_slg", "collect", "collect_resource", "recon_resource", "recon_monster",
            "mass_alliance_trap", "atk_world_boss", "mass_world_boss", "pet_catch", "truck_escort",
            "alliance_collect_resource", "build_trap", "frostfire_war_atk_monster",
            "frostfire_war_collect", "frostfire_war_burst_collect", "frostfire_war_atk_center"
    };

    /** 是否 PvE 行军（巨兽/野外集结/采集/打野等）。 */
    static boolean isPveMarch(Map<?, ?> m) {
        if (m == null) return true;
        for (String k : PVE_MARCH_KEYS) {
            if (m.containsKey(k)) return true;
        }
        return false;
    }

    /** 巨兽/世界 boss 目标子对象（target 内命中任一即 PvE；打城时 target.map_city，不会命中）。 */
    static final String[] PVE_TARGET_KEYS = {
            "map_monster", "map_boss", "map_world_boss", "monster", "world_boss"
    };

    /**
     * PvE 行军判定（deep 口径，返回命中特征；空串=非 PvE）。2026-11-09 用户实测：
     * {@code type=102} 的「巨兽模板」集结被观察点 A2 误报为「集结出征」——顶层 PvE 动作键之外，
     * 巨兽标记还可能落在 target 子对象或 mass 的 master_type 里，故补齐三层判定。
     *
     * <p>只认已知 PvE 的 master_type（501 冰原猎手 / 411 巨熊 / 5004 巨兽类）；
     * 102 不在此列（保留「集结出征」能力，避免误杀真实打城集结）。
     */
    public static String pveMarchReason(Map<?, ?> m) {
        if (m == null) return "null";
        for (String k : PVE_MARCH_KEYS) {
            if (m.containsKey(k)) return "key:" + k;
        }
        Map<?, ?> target = asMap(m.get("target"));
        for (String k : PVE_TARGET_KEYS) {
            if (target.containsKey(k)) return "target:" + k;
        }
        for (String mk : new String[]{"mass_master", "mass_slave", "mass", "mass_info"}) {
            Map<?, ?> mass = asMap(m.get(mk));
            long mt = asLong(mass.get("master_type"), 0L);
            if (mt == 501L || mt == 411L || mt == 5004L) return mk + ".master_type:" + mt;
        }
        return "";
    }

    /** 是否 PvE 行军（deep 口径；A2 两条通道共用，避免口径漂移）。 */
    public static boolean isPveMarchDeep(Map<?, ?> m) {
        return !pveMarchReason(m).isEmpty();
    }

    /** uid → 联盟 aid：优先实时城缓存，其次玩家目录；未知 0。 */
    private long aidOf(long uid) {
        City c = cityByUid.get(uid);
        if (c != null && c.aid > 0L) return c.aid;
        PlayerDirectory dir = PlayerDirectory.shared();
        PlayerDirectory.Entry e = dir == null ? null : dir.byUid(uid);
        return e == null ? 0L : e.aid;
    }

    /** 展示昵称：实时地图昵称优先，其次玩家目录；未知空串。 */
    private String displayNick(long uid) {
        String n = liveNick(uid);
        if (!n.isEmpty()) return n;
        PlayerDirectory dir = PlayerDirectory.shared();
        PlayerDirectory.Entry e = dir == null ? null : dir.byUid(uid);
        return e == null || e.nick == null ? "" : e.nick;
    }

    /** 采集点告警跳过留痕（去重，便于线上核对误报来源）。 */
    private void logGatherSkip(long owner, ResPoint rp, int ex, int ey, String why) {
        String key = owner + ":" + rp.uid + ":" + why;
        if (gatherSkipLogged.size() > 2000) gatherSkipLogged.clear();
        if (!gatherSkipLogged.add(key)) return;
        FileLog.info("[{}] 采集点告警跳过（{}）：攻击者 uid={} aid={} 采集者 uid={} aid={} 资源点=({},{})",
                LOG, why, String.valueOf(owner), String.valueOf(aidOf(owner)),
                String.valueOf(rp.uid), String.valueOf(rp.aid),
                String.valueOf(ex), String.valueOf(ey));
    }

    /** 这条采集点告警是否与本人相关：攻击者是监视目标，或被打的是友盟的人。 */
    boolean gatherRelevant(long owner, ResPoint rp) {
        if (isMonitoredUid(owner)) return true;
        if (rp == null) return false;
        if (rp.aid > 0L && store.friendAids.contains(rp.aid)) return true;
        PlayerDirectory dir = PlayerDirectory.shared();
        PlayerDirectory.Entry de = dir == null ? null : dir.byUid(rp.uid);
        return de != null && de.aid > 0L && store.friendAids.contains(de.aid);
    }

    /** 同一条行军只记一次采集点告警配额（轮询重放不重复扣）。 */
    boolean gatherCountOnce(long uid, long marchId, long now) {
        if (uid <= 0L || marchId <= 0L) return true;
        String k = uid + ":" + marchId;
        Long prev = gatherCounted.get(k);
        if (prev != null && now - prev < SCOUT_WINDOW_MS) return false;
        gatherCounted.put(k, now);
        if (gatherCounted.size() > 4096) {
            gatherCounted.entrySet().removeIf(e -> now - e.getValue() > SCOUT_WINDOW_MS);
        }
        return true;
    }

    /** 采集点被攻击告警（2026-11-02）：攻击人坐标/出征信息 + 被攻击人 + 资源点坐标。 */
    private void gatherAttackAlert(long owner, long marchId, long type, int sx, int sy,
                                   int ex, int ey, long endtime, long troops, ResPoint rp) {
        String key = "gather:" + owner + ":" + marchId;
        if (recentlyAlerted(key)) return;
        long now = System.currentTimeMillis();
        if (gatherCountOnce(owner, marchId, now)
                && !scoutQuotaAllow(gatherAlertTimes, owner, now,
                        SCOUT_WINDOW_MS, SCOUT_MAX_PER_WINDOW)) {
            FileLog.info("[{}] 采集点告警限流（5 分钟内已发 {} 条）：uid{}", LOG,
                    String.valueOf(SCOUT_MAX_PER_WINDOW), String.valueOf(owner));
            return;
        }
        City atk = cityByUid.get(owner);
        // 2026-11-04：攻击人/被攻击人名优先实时地图昵称（不用配置/目录旧名）
        String atkNick = nickOf(atk);
        if (atkNick == null || atkNick.isEmpty()) atkNick = liveNick(owner);
        if (atkNick == null || atkNick.isEmpty()) atkNick = "未知名玩家";
        long atkAid = atk == null ? 0L : atk.aid;
        PlayerDirectory dir = PlayerDirectory.shared();
        PlayerDirectory.Entry de = dir == null ? null : dir.byUid(rp.uid);
        String victimNick = liveNick(rp.uid);
        if (victimNick == null || victimNick.isEmpty()) {
            victimNick = de != null && de.nick != null && !de.nick.isEmpty()
                    ? de.nick : "未知名玩家";
        }
        long victimAid = rp.aid > 0L ? rp.aid : (de == null ? 0L : de.aid);
        alert(key, gatherAlertText(atkNick, atkAid, sx, sy, type, ex, ey,
                endtime - now / 1000L, troops, victimNick, victimAid,
                nowText(), store.aidLabels, store.feintTroopsBelow));
        trackAttacker(owner, atk);
    }

    /**
     * 资源类行为（静态兜底，无目标归属信息）：采集=type4；建设=type8；破坏=联盟旗/总部攻击字段。
     * 巨兽/打野/援助(type6)返回 null。
     */
    static String resourceBehavior(Map<?, ?> m, long type) {
        if (m == null) return null;
        if (isAllianceBuildingAttack(m)) return "破坏";
        if (type == 4L) return "采集";
        if (type == 8L) return "建设";
        return null;
    }

    /**
     * 资源类行为（2026-11-04 用户口径，按目标归属区分）：
     * <ul>
     *   <li>目标是<b>自己家/同阵营</b>的联盟总部/旗子 → 建设（type6/8）；</li>
     *   <li>目标是<b>敌对/中立</b>总部/旗子 → 破坏（type2/6/8）；</li>
     *   <li>采集=type4；打野/巨兽不推；type6 援助归 A1 驻防/增援。</li>
     * </ul>
     */
    String resourceBehavior(Map<?, ?> m, long type, long ownerAid, int ex, int ey) {
        Building b = buildingByPoint.get(ex + "," + ey);
        if (b != null) {
            if (type == 4L) return "采集";
            boolean own = ownerAid > 0L && b.aid > 0L
                    && (b.aid == ownerAid
                        || (store.friendAids.contains(ownerAid) && store.friendAids.contains(b.aid)));
            if (own) {
                return (type == 6L || type == 8L) ? "建设" : null;
            }
            // 敌对/中立联盟建筑：打掉就是破坏（进攻/集结类仍走 A1/A2）
            return (type == 2L || type == 6L || type == 8L) ? "破坏" : null;
        }
        return resourceBehavior(m, type);
    }

    /** 破坏联盟旗/总部（普通/集结）字段。 */
    static boolean isAllianceBuildingAttack(Map<?, ?> m) {
        if (m == null) return false;
        for (String k : new String[]{"atk_alliance_flag", "atk_alliance_hall",
                "mass_alliance_flag", "mass_alliance_hall"}) {
            if (m.containsKey(k)) return true;
        }
        return false;
    }

    /** 资源类推送（📍 资源·{采集|建设|破坏}），每人 5 分钟前 3 条。 */
    private void maybeResourceAlert(long owner, long marchId, String beh,
                                    int ex, int ey, long endtime, long troops) {
        String key = "res:" + owner + ":" + marchId;
        if (recentlyAlerted(key)) return;
        long now = System.currentTimeMillis();
        if (!scoutQuotaAllow(resAlertTimes, owner, now, SCOUT_WINDOW_MS, SCOUT_MAX_PER_WINDOW)) {
            FileLog.info("[{}] 资源告警限流（5 分钟内已发 {} 条）：uid{}", LOG,
                    String.valueOf(SCOUT_MAX_PER_WINDOW), String.valueOf(owner));
            return;
        }
        City c = cityByUid.get(owner);
        String nick = nickOf(c);
        if (nick == null || nick.isEmpty()) nick = liveNick(owner);
        if (nick == null || nick.isEmpty()) nick = "未知名玩家";
        alert(key, resourceAlertText(nick, c == null ? 0L : c.aid, beh, ex, ey,
                endtime - now / 1000L, troops, nowText(), store.aidLabels));
    }

    /** 资源类文案（2026-11-04 用户口径）。 */
    static String resourceAlertText(String nick, long aid, String beh, int ex, int ey,
                                    long etaSeconds, long troops, String nowTime,
                                    Map<Long, String> aidLabels) {
        StringBuilder sb = new StringBuilder("📍 资源·").append(beh).append('\n');
        sb.append("发起人：").append(prefixed(aid, aidLabels, nick)).append('\n');
        sb.append("目标点：(").append(ex).append(',').append(ey).append(")\n");
        sb.append("兵力：").append(troops > 0L ? String.valueOf(troops) : "未知").append('\n');
        sb.append("ETA：").append(hms(etaSeconds)).append('\n');
        sb.append("时间：").append(nowTime);
        return sb.toString();
    }

    /** 采集点被攻击文案（纯函数，便于单测）。 */
    static String gatherAlertText(String atkNick, long atkAid, int sx, int sy, long type,
                                  int ex, int ey, long etaSeconds, long troops,
                                  String victimNick, long victimAid, String nowTime,
                                  Map<Long, String> aidLabels, long feintBelow) {
        // 2026-11-04 用户口径：🚨 资源·受袭（每人 5 分钟只推前 3 次）。
        StringBuilder sb = new StringBuilder("🚨 资源·受袭\n");
        sb.append("攻击人：").append(prefixed(atkAid, aidLabels, atkNick))
                .append("  坐标 (").append(sx).append(',').append(sy).append(")\n");
        sb.append("出征信息：").append(typeLabel(type)).append('\n');
        sb.append("兵力：").append(troops > 0L ? String.valueOf(troops) : "未知").append('\n');
        sb.append("ETA：").append(hms(etaSeconds)).append('\n');
        sb.append("被攻击人：").append(prefixed(victimAid, aidLabels, victimNick)).append('\n');
        sb.append("资源点：(").append(ex).append(',').append(ey).append(")\n");
        sb.append("时间：").append(nowTime);
        return sb.toString();
    }

    /**
     * 来袭告警文案（纯函数）：攻击者联盟未知（aid&lt;=0）时显式标注「（联盟未知）」，
     * 降低"攻守同名/没 [盟] 标签"歧义；目标一定是已知友盟城（带 [abbr]）。
     */
    static String incomingAlertText(String attackerNick, long attackerAid, Map<Long, String> aidLabels,
                                    long type, String targetNick, long targetAid,
                                    int ex, int ey, long etaSeconds, long troops, String nowTime) {
        return incomingAlertText(attackerNick, attackerAid, aidLabels, type, targetNick, targetAid,
                ex, ey, etaSeconds, troops, nowTime, 0L);
    }

    static String incomingAlertText(String attackerNick, long attackerAid, Map<Long, String> aidLabels,
                                    long type, String targetNick, long targetAid,
                                    int ex, int ey, long etaSeconds, long troops, String nowTime,
                                    long feintBelow) {
        // P1：A2 文案统一在 AlertText；输出与旧实现逐字一致。
        String target = targetNick == null || targetNick.isEmpty()
                ? "" : prefixed(targetAid, aidLabels, targetNick);
        return AlertText.incoming(AlertText.a2Behavior(type), AlertText.level(troops, feintBelow),
                prefixed(attackerAid, aidLabels, attackerNick), troops, target, ex, ey,
                etaSeconds, nowTime);
    }

    /** 级别：⚪ 佯攻 / 🔴 威胁进攻（兵力未知=未知）。 */
    static String levelTag(long troops, long feintBelow) {
        return AlertText.level(troops, feintBelow);
    }

    /** 2026-11-02：整图扫描完成后，若玩家目录过期则后台重建（uid/fid/简称/aid 互查）。 */
    private void refreshPlayerDirectory() {
        try {
            PlayerDirectory dir = PlayerDirectory.shared();
            if (dir == null) return;
            java.util.List<CityInfo> cities = new java.util.ArrayList<>(cityByUid.size());
            for (City c : cityByUid.values()) {
                cities.add(new CityInfo(c.uid, c.aid, c.lv, c.nick, c.x, c.y));
            }
            String fpid = store.fpid;
            if (fpid == null || fpid.isEmpty()) {
                java.util.List<VaultStore.Account> accs = vaultStore == null
                        ? java.util.Collections.emptyList() : vaultStore.snapshot();
                if (!accs.isEmpty()) fpid = accs.get(0).fpid;
            }
            // sid 传 0 = 同账号任意在线角色（store.kid 是区服 ID，不是 sid，别混用）
            dir.refreshAsync(keeper, fpid, 0L, cities);
        } catch (Throwable t) {
            FileLog.info("[{}] 玩家目录刷新失败：{}", LOG, String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ 整图扫描（迁城定位/基线）

    private void startScan(String reason) {
        startScan(reason, 0, 0);
    }

    /**
     * 整图扫描（2026-11-02 增强）：{@code centerX/centerY > 0} 时按「离该点由近到远」排队，
     * 用于监视目标丢失后的搜索——先扫他最后出现的周边，几秒内就能发现新坐标，再覆盖全图未知区域。
     */
    private void startScan(String reason, int centerX, int centerY) {
        if (scan.active) return;
        scan.active = true;
        scan.reason = reason == null ? "" : reason;
        scan.startedAt = System.currentTimeMillis();
        scan.nextTile = 0;
        scan.ended = 0;
        scan.cityCount = 0;
        scan.order = scanOrder(centerX, centerY);
        cityByUid.clear();
        cityByPoint.clear();
        buildingByPoint.clear();
        FileLog.info("[{}] 触发整图扫描：{}（484 格，预计 15–20s{}）", LOG, scan.reason,
                centerX > 0 && centerY > 0 ? "，优先周边 (" + centerX + "," + centerY + ")" : "");
    }

    /** 扫描顺序：默认 0..n；给了中心点则按到中心点的距离升序（周边优先）。 */
    static int[] scanOrder(int centerX, int centerY) {
        int[] idx = new int[SCAN_TILES];
        for (int i = 0; i < SCAN_TILES; i++) idx[i] = i;
        if (centerX <= 0 || centerY <= 0) return idx;
        final int cx = centerX;
        final int cy = centerY;
        Integer[] boxed = new Integer[SCAN_TILES];
        for (int i = 0; i < SCAN_TILES; i++) boxed[i] = i;
        java.util.Arrays.sort(boxed, (a, b) -> {
            long da = tileDist2(a, cx, cy);
            long db = tileDist2(b, cx, cy);
            return da == db ? Integer.compare(a, b) : Long.compare(da, db);
        });
        for (int i = 0; i < SCAN_TILES; i++) idx[i] = boxed[i];
        return idx;
    }

    /** 第 i 格中心到点 (x,y) 的距离平方（仅用于排序）。 */
    private static long tileDist2(int i, int x, int y) {
        int sx = (i / GRID) * TILE;
        int sy = (i % GRID) * TILE;
        long dx = sx + TILE / 2L - x;
        long dy = sy + TILE / 2L - y;
        return dx * dx + dy * dy;
    }

    private void scanTick(java.util.List<WjdrEngine> engines) {
        if (engines == null || engines.isEmpty()) return;
        int n = engines.size();
        long now = System.currentTimeMillis();
        int budget = 4;   // 每 80ms 最多 4 块（N 个角色并行时总速率为 4N）
        while (scan.nextTile < SCAN_TILES && budget-- > 0) {
            int slot = scan.nextTile++;
            int i = scan.order != null && slot < scan.order.length ? scan.order[slot] : slot;
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

    /** 官方口径（2026-11-01 真机抓包）：跨区查询 fulldata=false、lod=0、21×21 小块、content.session 小序号。 */
    private void sendRawOfficial(WjdrEngine engine, int headerSession, long contentSession,
                                 int cx, int cy) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("startpoint", xy(Math.max(0, cx - 10), Math.max(0, cy - 10)));
        args.put("endpoint", xy(cx + 10, cy + 10));
        args.put("kid", store.kid);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Collections.singletonList(args));
        payload.put("session", contentSession);
        payload.put("fulldata", Boolean.FALSE);
        payload.put("lod", 0L);
        engine.sendFireAndForget("req_world_map_info", payload, headerSession);
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
            noteTargetCity(t.uid, c.x, c.y, c.nick);
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
                    // 2026-11-04 用户口径：保护矩形为空时自动解析该联盟新聚集地并推送「保护更新」。
                    relocateProtectRect(r, streak);
                } else {
                    FileLog.info("[{}] 保护矩形 {} 首次完整扫描为空（连续 {} 次），暂不处理",
                            LOG, r.name, String.valueOf(streak));
                }
            } else {
                rectEmptyStreak.remove(r.name);
            }
        }
        // 诊断（2026-11-04）：输出监视目标周边的联盟建筑（建设/破坏判定核对用，低频，每次完整扫描一条/目标）
        for (WatchPointStore.Target t : store.monitorTargets) {
            StringBuilder bs = new StringBuilder();
            for (Map.Entry<String, Building> en : buildingByPoint.entrySet()) {
                String[] xy = en.getKey().split(",");
                try {
                    int bx = Integer.parseInt(xy[0]);
                    int by = Integer.parseInt(xy[1]);
                    if (Math.abs(bx - t.x) <= 40 && Math.abs(by - t.y) <= 40) {
                        bs.append('(').append(bx).append(',').append(by).append(')')
                                .append(en.getValue().kind).append(" aid=").append(en.getValue().aid).append(' ');
                    }
                } catch (Throwable ignored) {
                }
            }
            if (bs.length() > 0) {
                FileLog.info("[{}] 目标 {} 周边联盟建筑：{}", LOG, t.display(), bs.toString().trim());
            }
        }
        FileLog.info("[{}] 保护矩形友盟城数（完整度 {}）：{}", LOG, completeness, health.toString().trim());
        if (found > 0) store.save();
        checkTrackedAttackers(completeness);
        refreshPlayerDirectory();
        FileLog.info("[{}] 整图扫描完成：{}/{} 格/{} 城，采集点缓存 {} 个，完整度={}，耗时 {}ms，监视目标命中 {}/{}（缺失 {}）",
                LOG, String.valueOf(scan.ended), String.valueOf(SCAN_TILES),
                String.valueOf(cityByUid.size()), String.valueOf(resByPoint.size()), completeness,
                String.valueOf(cost), String.valueOf(found),
                String.valueOf(store.monitorTargets.size()), String.valueOf(missing));
        if (missing > 0) {
            // 2026-11-04 用户口径：丢失/搜索过程不推送（「直接找，找到推新坐标就行」），只记日志。
            FileLog.info("[{}] 本轮整图未找到 {} 个监视目标（继续静默搜索，不推送）", LOG,
                    String.valueOf(missing));
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
                        "⚠️ 观察点·攻击者未定位（可能被击飞/跨服）\n"
                                + (ta.nick.isEmpty() ? "未知名玩家" : ta.nick)
                                + "\n最后坐标 (" + ta.x + "," + ta.y + ")"
                                + "\n时间：" + nowText());
                continue;
            }
            attackerMissingAt.remove(uid);
            if (c.x != ta.x || c.y != ta.y) {
                int ox = ta.x;
                int oy = ta.y;
                alert("attacker-move:" + uid + ":" + c.x + ":" + c.y,
                        "📍 观察点·攻击者迁城\n"
                                + (ta.nick.isEmpty() ? "未知名玩家" : ta.nick)
                                + "\n原坐标 (" + ox + "," + oy + ") → 新坐标 (" + c.x + "," + c.y + ")"
                                + "\n时间：" + nowText());
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

    /** 集结相关类型：10/12 集结中、99 随从、100 集结、102 集结主体。 */
    static boolean isRallyType(long type) {
        return type == 10L || type == 12L || type == 99L || type == 100L || type == 102L;
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
        m.put("pveIgnored", statPveIgnored.get());
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

    /** 节流窗口内排队的迁城通知数（2026-11-04：窗口内最新坐标必须排队待发）。 */
    int pendingMoveCountForTest() {
        return pendingMoveAlert.size();
    }

    /** 排队的迁城通知目标坐标（无则 -1）。 */
    int pendingMoveXForTest(long uid) {
        PendingMove p = pendingMoveAlert.get(uid);
        return p == null ? -1 : p.x;
    }

    /** 单测：注入联盟建筑（x,y → 总部/旗子，归属 aid）。 */
    void seedBuildingForTest(int x, int y, String kind, long aid) {
        buildingByPoint.put(x + "," + y, new Building(kind, aid));
    }

    /** 单测：改聚合窗口（默认 60s；0=立即到期）。 */
    void setMoveAlertWindowMsForTest(long ms) {
        this.moveAlertWindowMs = Math.max(0L, ms);
    }

    /** 单测：触发一次聚合窗口到期检查。 */
    void flushPendingMoveAlertsForTest() {
        flushPendingMoveAlerts();
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
