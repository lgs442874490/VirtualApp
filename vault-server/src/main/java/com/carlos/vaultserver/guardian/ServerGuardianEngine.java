package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.HeroCatalog;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.ServerWarState;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.formation.FormationCatalog;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.HeroAvailability;
import com.carlos.vaultserver.formation.ResolvedFormation;
import com.carlos.vaultserver.task.Exec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 守护盟友 P0（服务端化，批次9 2026-10-23）：盟友主城被敌方进攻时，由服务器会话自动发起
 * type=5 驻防（设计依据 {@code design/守护盟友-服务端化设计与分期-20261023.md}）。
 *
 * <p><b>P1（2026-10-25）对等加速 + 钻石路径</b>：敌方在途加速（{@code situation_update} endtime 前移/压缩）
 * 且我方驻防已发射仍在途时，由 {@code guardExec} 单线程异步调用纯函数
 * {@link GuardianSpeedupPlanner} 选档，发 {@code req_world_march_speedup{id,itemid,owner}}：
 * <ul>
 *   <li>25% 道具（301900）优先，一次不够循环叠加，仍不够才升 50%（301901）；</li>
 *   <li>无 16s floor，只有末端 {@link GuardianSpeedupPlanner#MIN_REMAIN_MS} 安全余量；</li>
 *   <li>道具不设预算 cap（受库存 + {@code max_speedups_per_march} 约束），每场/每发记账；</li>
 *   <li>同一敌方压缩态只响应一次（幂等），单轮链上限 6 发；敌方再压缩 → 新一轮最小响应；</li>
 *   <li>{@code speedup_enabled=false}（默认）→ 零请求零行为变化；</li>
 *   <li>钻石路径（{@code diamond_speedup_enabled=true} 才启用）：道具耗尽时
 *       {@code req_shop_quick_buy{use:false,id:301901,count:1}} 真实购买 → 立即用该 50% 加速；
 *       受 {@code diamond_max_per_march}（默认 3）约束，失败/超限诚实止损。</li>
 * </ul>
 *
 * <p><b>P0 范围</b>：
 * <ul>
 *   <li>推送解析：{@code notify_world_alliance_situation_add/_update/_init/_del}（主检测源，
 *       生产实测 22/102/11/… 条/日到达服务器会话）+ {@code notify_alliance_member_pos_info/_update}
 *       （盟友坐标）+ {@code notify_world_player_init}（city_point 参考）；</li>
 *   <li>威胁表 + 单盟友规则（{@code ally_uid}/{@code target_uid}/{@code target_point} 过滤）；</li>
 *   <li>命中后：{@code dry_run=true} 只记录；否则 type=5 驻防发射（{@link ServerGuardianLauncher}），
 *       发射响应 endtime 为权威；敌召回（A-11: remain>2s）→ off_work/recall 撤防；</li>
 *   <li>P0 **立即发射**（不做 waitUntil/EWMA；加速链为 P1 增量）。</li>
 * </ul>
 *
 * <p><b>硬安全约束（宁可不发）</b>：
 * <ol>
 *   <li><b>默认关闭</b>：{@link Config#enabled}={@code false}、{@link Config#dryRun}={@code true}
 *       （roleParams 未配置时什么都不做，零状态写入、零请求）；</li>
 *   <li><b>默认零钻石</b>：{@code diamond_speedup_enabled=false}（默认）→ <b>零
 *       {@code req_shop_quick_buy} 请求</b>；只有开关显式打开才允许真实购买，且单发不重试、
 *       受 {@code diamond_max_per_march}（默认 3，0=不限）约束；库存未知（背包快照未同步）时绝不花钻石；</li>
 *   <li><b>不重试风暴</b>：每个威胁最多 1 次动作（A-2 三变体最多 3 个请求）；失败只记原始码；</li>
 *   <li><b>并发 ≤1</b>：同角色同时只允许 1 个在途驻防任务；同目标不重复；</li>
 *   <li><b>会话在线才动作</b>：推送只入账；请求只在 {@code STATE_ONLINE} 时发出；</li>
 *   <li><b>不改状态机</b>：本类由 SessionKeeper 在 online 分支挂载/tick，shutdown 时停止；
 *       战时闸只做展示标注（守护是战斗任务，不因日常战时让位而停）。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：消息钩子线程只做解析/威胁表更新（微秒级、不阻塞入站）；
 * 发射/撤防动作在 {@code guardian-{fpid}-{sid}} 单线程 daemon 执行器串行执行，
 * 经 {@code engine.requestLock} 与会话线程/心跳天然串行。
 *
 * <p><b>配置通道</b>：App FnParams → {@code /v1/vault/sync} params → {@code Account.roleParams}
 * （{@code VaultHttpApi.java:166,225}、{@code VaultStore.java:109}），键 {@code guardian}，
 * 形状 {@code {"sub":{...},"strategy":{...}}}（与 ServerWarState.configOf 同款；也容错扁平键）：
 * <pre>
 * enabled(默认false) dry_run(默认true) ally_uid target_uid target_point{x,y}
 * force_online(默认false) troops_min(默认0=不限，兼容别名 min_enemy_troops)
 * formation{hero_ids:[],squad_index:1} recall(默认true) recall_distance(预留)
 * d_sec(默认38)  // P0 仅用于日志/D 展示，不参与排程（立即发射）
 * speedup_enabled(默认false) diamond_speedup_enabled(默认false)
 * speedup_margin_s(默认2) max_speedups_per_march(默认0=不限) diamond_max_per_march(默认3，0=不限)
 * </pre>
 *
 * <p>威胁档位：复用批次11 的 {@code troops_min}（敌方兵力下限，已接线 {@link #decide}；App 的
 * {@code power_min} 是战力口径，服务器侧无战力阈值语义，本批不新增；单盟友过滤用
 * {@code ally_uid}/{@code target_uid}/{@code target_point}）。
 *
 * <p><b>观测键型（稳定契约，供 App/日报/看板消费）</b>：
 * <ul>
 *   <li>{@code mod:{fpid}:{sid}:guardian}：JSON，子项 {@code last}/{@code last_ts}/{@code last_trg="守护"}；</li>
 *   <li>{@code srv:guardian:{fpid}:{sid}:ledger}：JSON 数组（最新在前、上限 50），元素
 *       {ts, ev, marchId, owner, type, endSec, targetUid, x, y, ownMarchId, ownEndSec, code, variant, note}；
 *       P1 加速事件额外带 {itemid, beforeSec, stock25, stock50, reason, latencyMs}（ev=speedup/
 *       speedup_no_move/speedup_skip/speedup_failed/speedup_no_item）；
 *       P1 钻石事件额外带 {itemid, buyCount, diamondBought, stock25, stock50, code, reason, latencyMs}
 *       （ev=speedup_diamond_buy/speedup_diamond_buy_failed/speedup_diamond_skip）；</li>
 *   <li>{@code describe()}：诊断一行（配置/威胁/任务/盟友/最近推送）。</li>
 * </ul>
 */
public final class ServerGuardianEngine {

    /** 任务 id（与 App FnRegistry/FnDetail 一致）。 */
    public static final String FN_ID = "guardian";

    // ---------------- 协议常量（实证口径；服务器侧仅消费这些） ----------------

    static final String PROTO_ALLIANCE_ADD = "notify_world_alliance_situation_add";
    static final String PROTO_ALLIANCE_UPDATE = "notify_world_alliance_situation_update";
    static final String PROTO_ALLIANCE_INIT = "notify_world_alliance_situation_init";
    static final String PROTO_ALLIANCE_DEL = "notify_world_alliance_situation_del";
    static final String PROTO_ALLIANCE_POS_INFO = "notify_alliance_member_pos_info";
    static final String PROTO_ALLIANCE_POS_UPDATE = "notify_alliance_member_pos_update";
    static final String PROTO_WORLD_PLAYER_INIT = "notify_world_player_init";
    static final String PROTO_MARCH_DETAIL = "req_world_march_detail_4_player_situation";

    static final Set<String> ALLIANCE_MARCH_PROTOS = Set.of(
            PROTO_ALLIANCE_ADD, PROTO_ALLIANCE_UPDATE, PROTO_ALLIANCE_INIT);

    // ---------------- 行为常量 ----------------

    /** 进攻类行军（type=2 target_type=1 攻玩家城；type=1 为侦查，P0 不驻防）。 */
    static final long MARCH_TYPE_ATTACK = 2L;
    /** 默认行军时长 D（秒；设计 §3.5：P0 仅用于日志，立即发射；P1 再标定）。 */
    static final long DEFAULT_D_SEC = 38L;
    /** ETA 过近（<3s）不再追（物理上无解；P1 卡秒/加速才处理）。 */
    static final long MIN_ETA_SEC = 3L;
    /** 敌召回/到达判定阈值（A-11 实证：endtime−now >2s 判召回）。 */
    static final long RECALL_THRESHOLD_SEC = 2L;
    /** 配置热载间隔（同 ServerWarState 60s 热载纪律，这里 10s 更快响应开关）。 */
    static final long CONFIG_TTL_MILLIS = 10_000L;
    /** 威胁表保留：endtime 之后保留 90s（丢 del 推送时清理；同 App GuardianEngine.hasThreatOn 口径）。 */
    static final long THREAT_KEEP_AFTER_END_SEC = 90L;
    /** 无 endtime 威胁的年龄上界（2026-10-24 审查补强：del 丢失时防 threats 永久累积）。 */
    static final long STALE_NO_ETA_THREAT_MILLIS = 10 * 60_000L;
    /** 任务表保留：endtime 之后保留 10 分钟（等战报/敌情结束；P0 不主动撤已驻扎）。 */
    static final long MISSION_KEEP_AFTER_END_SEC = 600L;
    /** 同角色最多同时 1 个在途驻防任务（P0；App 上限 2，服务器先保守 1）。 */
    static final int MAX_CONCURRENT_MISSIONS = 1;
    /** 账本上限。 */
    static final int LEDGER_CAP = 50;
    /** 解析深度/候选上限（防异常 payload）。 */
    private static final int MAX_DEPTH = 5;
    private static final int MAX_CANDIDATES = 48;
    /** P1：道具库存本地记账的重读间隔（背包快照兜底；成功一次即本地递减，不信过期快照）。 */
    static final long STOCK_REFRESH_MILLIS = 10 * 60_000L;

    // ---------------- 配置 ----------------

    /** 生效配置（解析后；默认=关闭+dry_run）。 */
    static final class Config {
        boolean enabled = false;
        boolean dryRun = true;
        boolean recall = true;
        boolean speedupEnabled = false;      // P1：敌方加速 → 我方对等加速（默认关）
        // ---- P1 对等加速（2026-10-25；缺省=旧行为零变化） ----
        /** 道具不足时是否允许钻石加速（默认 false=硬约束零钻石；打开后走 req_shop_quick_buy 真实购买）。 */
        boolean diamondSpeedupEnabled = false;
        /** 本行军最多购买几颗钻石道具（默认 3；0=不限；超限止损，防钻石风暴）。 */
        int diamondMaxPerMarch = 3;
        /** 先至余量（秒；默认 2，对齐 P1 验收「我方 endtime ≤ 敌 endtime −1s」）。 */
        long speedupMarginS = 2L;
        /** 本行军加速发数上限（0=不限；道具不设预算 cap 的用户口径）。 */
        int maxSpeedupsPerMarch = 0;
        // ---- 批次11 触发策略（2026-10-24；缺省=旧行为零变化） ----
        /** 盟友受攻击风险时允许强制上线（默认 false；仅查询，不改变发送门控）。 */
        boolean forceOnline = false;
        /** 敌方来袭兵力下限（0=不限；兼容别名 min_enemy_troops；未知兵力→守护侧保守不发射）。 */
        long troopsMin = 0L;
        long allyUid = 0L;
        long targetUid = 0L;
        int targetX = 0;
        int targetY = 0;
        int squadIndex = 1;
        List<Long> heroIds = new ArrayList<>();
        double recallDistance = 0d;          // 预留
        long dSec = DEFAULT_D_SEC;
        // ---- 批次10 统一编队（task-32；缺省=旧行为零变化） ----
        /** 原始 roleParams（JSONObject→Map 深转换；FormationResolver 契约入参）。 */
        Map<String, Object> rawParams = new LinkedHashMap<>();
        /** formation_library JSON（先取 guardian 自身，其次 formation fn 参数）。 */
        String formationLibraryJson = "";

        static Config disabled() {
            return new Config();
        }

        boolean hasTargetPoint() {
            return targetX > 0 && targetY > 0;
        }

        String describe() {
            return "enabled=" + enabled + " dry_run=" + dryRun + " ally_uid=" + allyUid
                    + " target_uid=" + targetUid
                    + (hasTargetPoint() ? " target_point=" + targetX + "," + targetY : "")
                    + " squad=" + squadIndex
                    + (heroIds.isEmpty() ? "" : " heroes=" + heroIds)
                    + " recall=" + recall + " d=" + dSec + "s"
                    + " force_online=" + forceOnline + " troops_min=" + troopsMin
                    + " speedup=" + speedupEnabled
                    + " diamond_speedup=" + diamondSpeedupEnabled
                    + " diamond_max=" + (diamondMaxPerMarch <= 0 ? "unlimited" : String.valueOf(diamondMaxPerMarch))
                    + " speedup_margin=" + speedupMarginS + "s"
                    + " max_speedups=" + (maxSpeedupsPerMarch <= 0 ? "unlimited" : String.valueOf(maxSpeedupsPerMarch));
        }
    }

    // ---------------- 状态对象 ----------------

    /** 一个敌方威胁（已入账的行军；P0 只关心 id/owner/type/end/endpoint/targetUid）。 */
    static final class Threat {
        final long marchId;
        final long ownerUid;
        final long type;
        final long targetUid;
        final int x;
        final int y;
        final boolean hasXY;
        final long firstSeenMs;
        /** 敌方兵力（{@code squad_list.soldier} 求和；未知时 0，见 {@link #troopsKnown}）。 */
        final long troops;
        /** 兵力是否可从推送算出（false=未知：只有 power 无 squad，或推送缺字段）。 */
        final boolean troopsKnown;
        /** 敌方战力（march.power 或观察哨外层 slot.power；0=未知）。 */
        final long power;
        volatile long endEpochSec;

        Threat(long marchId, long ownerUid, long type, long targetUid,
               int x, int y, boolean hasXY, long endEpochSec, long firstSeenMs) {
            this(marchId, ownerUid, type, targetUid, x, y, hasXY, endEpochSec, firstSeenMs,
                    0L, false, 0L);
        }

        Threat(long marchId, long ownerUid, long type, long targetUid,
               int x, int y, boolean hasXY, long endEpochSec, long firstSeenMs,
               long troops, boolean troopsKnown, long power) {
            this.marchId = marchId;
            this.ownerUid = ownerUid;
            this.type = type;
            this.targetUid = targetUid;
            this.x = x;
            this.y = y;
            this.hasXY = hasXY;
            this.endEpochSec = endEpochSec;
            this.firstSeenMs = firstSeenMs;
            this.troops = troops;
            this.troopsKnown = troopsKnown;
            this.power = power;
        }

        /** 合并同一行军的新旧推送（endtime 前移=敌加速；坐标/target 以新值为准）。 */
        Threat merge(Threat newer) {
            if (newer == null) return this;
            long end = newer.endEpochSec > 0 ? newer.endEpochSec : endEpochSec;
            long target = newer.targetUid > 0 ? newer.targetUid : targetUid;
            long owner = newer.ownerUid > 0 ? newer.ownerUid : ownerUid;
            long typeNew = newer.type > 0 ? newer.type : type;
            boolean xy = newer.hasXY || hasXY;
            int nx = newer.hasXY ? newer.x : x;
            int ny = newer.hasXY ? newer.y : y;
            // 兵力/战力：新推送能算出则以新值为准，否则沿用旧值（防 update 推送缺 squad 时丢失已知兵力）
            long troopsNew = newer.troopsKnown ? newer.troops : troops;
            boolean knownNew = newer.troopsKnown || troopsKnown;
            long powerNew = newer.power > 0 ? newer.power : power;
            Threat t = new Threat(marchId, owner, typeNew, target,
                    nx, ny, xy, end, Math.min(firstSeenMs, newer.firstSeenMs),
                    troopsNew, knownNew, powerNew);
            return t;
        }

        String kind() {
            return type == MARCH_TYPE_ATTACK ? "进攻" : (type == 1L ? "侦查" : "type" + type);
        }

        /** 兵力展示（日志/账本）；未知=「兵力未知」。 */
        String troopsText() {
            return troopsKnown ? String.valueOf(troops) : "兵力未知";
        }
    }

    /** 一次已发射的驻防任务（targetKey → Mission）。 */
    static final class Mission {
        final long enemyMarchId;
        final long marchId;
        /** 我方到达时刻（服务器秒）；P1 加速成功后随响应 endtime 更新（volatile 供推送/执行线程读）。 */
        volatile long endtimeSec;
        final long launchedAtMs;

        Mission(long enemyMarchId, long marchId, long endtimeSec, long launchedAtMs) {
            this.enemyMarchId = enemyMarchId;
            this.marchId = marchId;
            this.endtimeSec = endtimeSec;
            this.launchedAtMs = launchedAtMs;
        }
    }

    /** 规则判定结果（纯函数返回）。 */
    static final class Decision {
        final boolean hit;
        final String reason;

        private Decision(boolean hit, String reason) {
            this.hit = hit;
            this.reason = reason;
        }

        static Decision hit() {
            return new Decision(true, "hit");
        }

        static Decision no(String reason) {
            return new Decision(false, reason);
        }
    }

    // ---------------- 实例状态 ----------------

    private final String fpid;
    private final long sid;
    private final StateStore store;
    private final VaultStore vault;
    private final ServerGuardianLauncher launcher = new ServerGuardianLauncher();

    /** 推送线程写、执行器读：threats/成员坐标表/UI 诊断。 */
    private final Map<Long, Threat> threats = new ConcurrentHashMap<>();
    private final Map<Long, long[]> allies = new ConcurrentHashMap<>();
    private final Map<String, Mission> missions = new ConcurrentHashMap<>();
    /** 已尝试动作的威胁 id（每威胁最多 1 次动作；防更新推送重复触发）。 */
    private final Set<Long> attempted = ConcurrentHashMap.newKeySet();
    /** 已入队待执行的威胁 id（防同 tick/连续推送重复提交）。 */
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    /** 兵力阈值拒绝留痕（威胁 id → 已记录的判定值；同一值只记一次账本）。 */
    private final Map<Long, String> thresholdNotes = new ConcurrentHashMap<>();
    /** 目标级进行中标记（"t:{uid}" / "p:{x},{y}"）。 */
    private final Set<String> activeTargets = ConcurrentHashMap.newKeySet();
    /**
     * 2026-10-28：发射失败（415 编队超限 / 406 士兵超限）后的目标级冷却
     * （targetKey → 截止毫秒）。冷却期内同目标新威胁只记账不发射，防 415/406 重试风暴。
     */
    static final long LAUNCH_FAIL_COOLDOWN_MS = 45_000L;
    /** 同一 target+code 的 WARN/writeMod 日志节流窗口（60s；不再每个威胁刷一条）。 */
    static final long FAIL_LOG_THROTTLE_MS = 60_000L;
    private final Map<String, Long> targetCooldowns = new ConcurrentHashMap<>();
    /** {@code targetKey|code → 上次失败日志时刻}（节流用）。 */
    private final Map<String, Long> failLogAt = new ConcurrentHashMap<>();
    /** 最近一次自动编队摘要（英雄/容量/兵力比；供 launched 账本文案，2026-10-28 需求）。 */
    private volatile String lastAutoFormationSummary = "";
    /**
     * P1：每个敌方行军已处理的「压缩态」endtime（敌方 marchId → 最近一次触发加速的敌 endSec）。
     * 同一压缩态重复推送只处理一次（幂等，防请求风暴）；敌方再次压缩（endSec 更小）→ 新一轮最小响应。
     */
    private final Map<Long, Long> speedupRounds = new ConcurrentHashMap<>();
    /** P1：每个我方驻防行军已用加速发数（我方 marchId → 发数；max_speedups_per_march 记账）。 */
    private final Map<Long, Integer> speedupUsedByMarch = new ConcurrentHashMap<>();
    /** P1 钻石路径：每个我方驻防行军已购买钻石道具数（我方 marchId → 次数；diamond_max_per_march 记账）。 */
    private final Map<Long, Integer> diamondBoughtByMarch = new ConcurrentHashMap<>();
    /** P1：道具库存本地记账（-1=未知 → 从背包快照初始化；成功后递减；2827 清零该档）。 */
    private volatile long stock25 = -1L;
    private volatile long stock50 = -1L;
    /** P1：库存记账最近一次从背包/资产库读取的时刻。 */
    private volatile long stockLoadedAtMs;

    private final ExecutorService guardExec;
    private final AtomicBoolean attached = new AtomicBoolean(false);
    private volatile Config config = Config.disabled();
    private volatile long lastConfigLoadAt;
    private volatile long lastPushAt;
    private volatile String lastProto = "";
    private volatile boolean stopped;
    /** R2 Phase 2B：角色 uid（>0 时守护账本/状态键按角色隔离；0=旧/兼容路径）。 */
    private final long roleUid;
    /** 当前会话是否为 active 角色写入方（supplier 由 SessionKeeper 提供；异常按 false）。 */
    private final Supplier<Boolean> activeRole;

    /** 旧构造器（P0/兼容路径）：roleUid=0、activeRole=null，键型与行为完全不变。 */
    public ServerGuardianEngine(String fpid, long sid, StateStore store, VaultStore vault) {
        this(fpid, sid, 0L, store, vault, null);
    }

    /**
     * R2 Phase 2B（2026-10-29）：按角色隔离守护账本/状态键。
     *
     * <p>键规则：roleUid&gt;0 → {@code srv:guardian:<fpid>:<sid>:<uid>:ledger} /
     * {@code mod:<fpid>:<sid>:<uid>:guardian}；activeRole.get()==true 时**双写旧键**
     * （App 今日页/FnDetail 展示不变），兄弟角色只写角色键；读优先角色键、缺失回退旧键。
     * roleUid&lt;=0 时等价旧构造器（只写旧键）。
     *
     * @param roleUid    真实会话角色 uid（测试/兼容可传 0）
     * @param activeRole 是否 active 角色写入方；null 视为 false，get() 抛异常按 false
     */
    public ServerGuardianEngine(String fpid, long sid, long roleUid, StateStore store,
                                VaultStore vault, Supplier<Boolean> activeRole) {
        this.fpid = fpid == null ? "" : fpid;
        this.sid = sid;
        this.roleUid = roleUid;
        this.store = store;
        this.vault = vault;
        this.activeRole = activeRole;
        this.guardExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "guardian-" + this.fpid + "-" + sid
                    + (roleUid > 0L ? "-" + roleUid : ""));
            t.setDaemon(true);
            return t;
        });
    }

    /** 当前会话是否 active 角色写入方（旧路径恒 true；supplier 异常按 false）。 */
    private boolean isActiveRole() {
        if (roleUid <= 0L) return true;
        if (activeRole == null) return false;
        try {
            return Boolean.TRUE.equals(activeRole.get());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读状态：角色键优先，缺失回退旧键（迁移/兼容；roleUid<=0 时两键相同）。 */
    private String readState(String roleKey, String legacyKey) {
        if (store == null) return null;
        String raw = store.getString(roleKey);
        if (raw == null && !roleKey.equals(legacyKey)) raw = store.getString(legacyKey);
        return raw;
    }

    /** 写状态：写角色键；active 角色额外双写旧键（App 展示链路不变），兄弟角色不覆盖旧键。 */
    private void writeState(String roleKey, String legacyKey, String value) {
        if (store == null) return;
        store.putString(roleKey, value);
        if (roleUid > 0L && !roleKey.equals(legacyKey) && isActiveRole()) {
            store.putString(legacyKey, value);
        }
    }

    // =====================================================================
    // 生命周期（SessionKeeper 三处接线：字段 / online 挂载+tick / shutdown）
    // =====================================================================

    /** 挂载推送钩子（幂等；钩子线程只解析入账，不发请求）。 */
    public void attach(WjdrEngine engine) {
        if (engine == null || !attached.compareAndSet(false, true)) return;
        try {
            engine.addMessageHook((proto, content) -> {
                try {
                    onPush(engine, proto, content);
                } catch (Throwable t) {
                    FileLog.warn("守护盟友钩子异常 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
                }
            });
            reloadConfig(true);
            FileLog.info("守护盟友 P0 钩子已挂载 {}:{}（{}）", fpid, String.valueOf(sid),
                    config.describe());
        } catch (Throwable t) {
            attached.set(false);
            FileLog.warn("守护盟友钩子挂载失败 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
        }
    }

    /**
     * 会话线程 tick（online 时由 SessionKeeper 调用）：配置热载 + 内存过期清理。
     * 不做任何网络请求；发射/撤防在 guardExec 线程。
     */
    public void tick(WjdrEngine engine) {
        if (stopped) return;
        reloadConfig(false);
        long nowS = nowSec(engine);
        long nowMs = System.currentTimeMillis();
        try {
            for (Map.Entry<Long, Threat> e : threats.entrySet()) {
                Threat t = e.getValue();
                boolean expiredByEnd = t.endEpochSec > 0
                        && nowS - t.endEpochSec > THREAT_KEEP_AFTER_END_SEC;
                // 无 endtime 的威胁无法按到达时刻过期：按首次入账年龄兜底清理（防 del 丢失累积）
                boolean expiredNoEta = t.endEpochSec <= 0
                        && t.firstSeenMs > 0
                        && nowMs - t.firstSeenMs > STALE_NO_ETA_THREAT_MILLIS;
                if (expiredByEnd || expiredNoEta) {
                    if (threats.remove(e.getKey(), t)) {
                        attempted.remove(t.marchId);
                        submitted.remove(t.marchId);
                    }
                }
            }
            missions.entrySet().removeIf(en -> {
                Mission m = en.getValue();
                return m.endtimeSec > 0 && nowS - m.endtimeSec > MISSION_KEEP_AFTER_END_SEC;
            });
            thresholdNotes.keySet().removeIf(id -> !threats.containsKey(id));
            // P1：清理已消失行军的加速幂等/记账（防长期累积）
            speedupRounds.keySet().removeIf(id -> !threats.containsKey(id));
            speedupUsedByMarch.keySet().removeIf(id -> {
                for (Mission m : missions.values()) {
                    if (m.marchId == id) return false;
                }
                return true;
            });
            diamondBoughtByMarch.keySet().removeIf(id -> {
                for (Mission m : missions.values()) {
                    if (m.marchId == id) return false;
                }
                return true;
            });
            // 2026-10-28：清理过期目标冷却/失败日志节流表（防长期累积）
            targetCooldowns.entrySet().removeIf(en -> en.getValue() == null || en.getValue() <= nowMs);
            failLogAt.entrySet().removeIf(en -> en.getValue() == null
                    || nowMs - en.getValue() > FAIL_LOG_THROTTLE_MS * 4L);
        } catch (Throwable ignored) {
        }
    }

    /** 停止执行器（会话 shutdown 时调用；不发请求）。 */
    public void shutdown() {
        stopped = true;
        try {
            guardExec.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 强制上线（force_online）：SessionKeeper 在 paused/takeover/recovering 时查询
    // =====================================================================

    /**
     * 是否存在「本进程已收到、仍新鲜、规则命中且会真实派兵」的盟友受攻击风险，供 SessionKeeper
     * 在 {@code paused/takeover/recovering}（非 online）时强制回连并派兵驻防。
     *
     * <p>纯查询：不发请求、不写状态、不影响「每威胁最多 1 次动作」约束。判定 =
     * 配置 {@code enabled ∧ force_online ∧ !dry_run} ∧ 威胁未过期（endtime 后
     * {@link #THREAT_KEEP_AFTER_END_SEC} 内）∧ {@link #decide} 命中（decide 本就不看会话
     * 在线状态，online 与否由调用方处理）。配置按热载纪律顺带刷新（10s 节流）。
     *
     * <p>{@code dry_run} 只记录不发射，为一个不会真实驻防的风险去抢占登录/顶掉其他设备不符合
     * 用户预期（用户需求是「允许强制上线并派兵驻防」，派兵=实发），故强制上线仅实发模式生效。
     */
    public boolean forceOnlineWanted() {
        if (stopped) return false;
        reloadConfig(false);
        Config cfg = config;
        if (cfg == null || !cfg.enabled || !cfg.forceOnline || cfg.dryRun) return false;
        long nowMs = System.currentTimeMillis();
        long nowS = nowMs / 1000L;
        for (Threat t : threats.values()) {
            if (t == null) continue;
            // 已过期的威胁不入账（避免拿旧情强制回连）；decide 内还有 ETA 窗口复核
            if (t.endEpochSec > 0 && nowS - t.endEpochSec > THREAT_KEEP_AFTER_END_SEC) continue;
            if (t.endEpochSec <= 0 && t.firstSeenMs > 0
                    && nowMs - t.firstSeenMs > STALE_NO_ETA_THREAT_MILLIS) continue;
            if (decide(t, cfg, nowMs).hit) return true;
        }
        return false;
    }

    // =====================================================================
    // 推送入口
    // =====================================================================

    /** 推送入口（钩子线程；包级可见便于单测直接驱动）。 */
    void onPush(WjdrEngine engine, String proto, Map<String, Object> content) {
        if (stopped || proto == null || content == null) return;
        reloadConfig(false);
        Config cfg = config;
        if (!cfg.enabled) return;                       // 默认关：零副作用（不解析、不写状态）
        lastPushAt = System.currentTimeMillis();
        lastProto = proto;

        // 盟友坐标表（P1 距离标定用；P0 仅存内存，供 target_point 回退）
        if (PROTO_ALLIANCE_POS_INFO.equals(proto) || PROTO_ALLIANCE_POS_UPDATE.equals(proto)) {
            Map<Long, long[]> parsed = parseMemberPos(content);
            if (!parsed.isEmpty()) allies.putAll(parsed);
            return;
        }

        // 敌情解除：召回 → 按配置撤防；到达 → 不撤（进战斗/落地）
        if (PROTO_ALLIANCE_DEL.equals(proto)) {
            for (long id : parseDeletedMarchIds(content)) handleThreatGone(engine, id);
            return;
        }

        if (!ALLIANCE_MARCH_PROTOS.contains(proto)) return;

        long nowMs = System.currentTimeMillis();
        for (Threat t : parseThreats(content)) {
            if (t.marchId <= 0 || t.ownerUid <= 0) continue;
            Threat prev = threats.get(t.marchId);
            Threat cur = prev == null ? t : prev.merge(t);
            threats.put(cur.marchId, cur);
            // P1：敌方加速（endtime 前移/压缩）→ 若我方驻防已发射且在途 → 异步对等加速
            if (prev != null) maybeSubmitSpeedup(engine, prev, cur, cfg, nowMs);
            Decision d = decide(cur, cfg, nowMs);
            if (!d.hit) {
                noteThresholdReject(cur, d, cfg);
                continue;
            }
            if (!submitted.add(cur.marchId)) continue;
            final long marchId = cur.marchId;
            try {
                guardExec.execute(() -> {
                    submitted.remove(marchId);
                    executeThreat(engine, marchId);
                });
            } catch (Throwable t2) {
                submitted.remove(marchId);
            }
        }
    }

    /**
     * 兵力阈值拒绝留痕（账本 {@code skip_troops}）：只对 {@code troops_min>0} 引起的兵力类拒绝
     * 记一次；同一威胁同一判定值不重复记账（防 update 推送刷屏，兵力后续变化时允许再记）。
     */
    void noteThresholdReject(Threat t, Decision d, Config cfg) {
        try {
            if (t == null || d == null || cfg == null || cfg.troopsMin <= 0) return;
            if (d.reason == null || !d.reason.startsWith("troops_")) return;
            String note = (t.troopsKnown ? "troops=" + t.troops : "troops=unknown") + "；" + d.reason;
            String prev = thresholdNotes.put(t.marchId, note);
            if (note.equals(prev)) return;
            ledger("skip_troops", t, null, "兵力阈值未过：" + note + "（troops_min=" + cfg.troopsMin + "）");
        } catch (Throwable ignored) {
        }
    }

    /** 威胁消失处理（召回/到达；钩子线程 → 只入队撤防）。 */
    private void handleThreatGone(WjdrEngine engine, long marchId) {
        Threat t = threats.remove(marchId);
        if (t == null) return;
        attempted.remove(marchId);
        submitted.remove(marchId);
        thresholdNotes.remove(marchId);
        long nowS = nowSec(engine);
        boolean recalled = t.endEpochSec - nowS > RECALL_THRESHOLD_SEC;
        if (!recalled) {
            ledger("threat_landed", t, null, "敌方已落地（进入战斗，不撤防）");
            return;
        }
        Mission m = missions.get(targetKey(t));
        if (m == null) {
            ledger("threat_recalled", t, null, "敌召回（无我方驻防任务）");
            return;
        }
        Config cfg = config;
        if (!cfg.recall) {
            ledger("threat_recalled_keep", t, null, "敌召回；配置 recall=false 保留驻防");
            return;
        }
        if (cfg.dryRun) {
            ledger("threat_recalled_dry", t, null, "[DRY] 敌召回 → 将撤防（dry_run 不发请求）");
            return;
        }
        final Mission mission = m;
        try {
            guardExec.execute(() -> executeRecall(engine, mission, "敌召回"));
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 执行（guardExec 单线程：发射 / 撤防；每威胁最多 1 次动作）
    // =====================================================================

    /** 执行一条威胁：重新校验规则 → 门控 → dry_run 记录 / 立即发射。 */
    void executeThreat(WjdrEngine engine, long marchId) {
        if (stopped) return;
        Threat t = threats.get(marchId);
        if (t == null) return;
        Config cfg = config;
        long nowMs = System.currentTimeMillis();
        Decision d = decide(t, cfg, nowMs);
        if (!d.hit) return;
        if (!attempted.add(marchId)) return;             // P0：每威胁最多 1 次动作
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) {
            ledger("skip_offline", t, null, "会话不在线（跳过，不补发旧情）");
            attempted.remove(marchId);
            return;
        }
        String targetKey = targetKey(t);
        // 2026-10-28：同目标 415/406 失败后的冷却期内，新威胁只记账不发射（防重试风暴/日志刷屏）
        Long coolUntil = targetCooldowns.get(targetKey);
        if (coolUntil != null) {
            if (inLaunchCooldown(coolUntil, nowMs)) {
                ledger("skip_cooldown", t, null, "同目标发射失败冷却中（剩 "
                        + Math.max(0L, (coolUntil - nowMs + 999L) / 1000L) + "s），本威胁只记账不发射");
                return;
            }
            targetCooldowns.remove(targetKey);
        }
        if (!activeTargets.add(targetKey) || missions.size() >= MAX_CONCURRENT_MISSIONS) {
            activeTargets.remove(targetKey);
            ledger("skip_busy", t, null,
                    "已有驻防任务在跑（missions=" + missions.size() + "）");
            return;
        }
        try {
            if (cfg.dryRun) {
                long eta = t.endEpochSec - nowSec(engine);
                String text = "命中威胁 " + t.kind() + " march=" + t.marchId + " owner=" + t.ownerUid
                        + " 敌方兵力=" + t.troopsText()
                        + (t.power > 0 ? " 战力=" + t.power : "")
                        + " → 目标" + (t.targetUid > 0 ? " uid" + t.targetUid : "")
                        + (t.hasXY ? "(" + t.x + "," + t.y + ")" : "")
                        + " ETA " + Math.max(0, eta) + "s，D=" + cfg.dSec + "s，warYield="
                        + warYieldNote() + "（dry_run 只记录不发射）";
                FileLog.info("守护盟友[DRY] {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("dry_run", t, null, text);
                writeMod("🛡[DRY] " + text);
                return;
            }
            int free = freeMarchSlots(engine);
            if (free <= 0) {
                ledger("skip_queue", t, null, free < 0 ? "队列容量/占用未知（宁可不发）" : "队列无空位 free=" + free);
                return;
            }
            int[] pt = resolveTargetPoint(t, cfg);
            if (pt == null || pt[0] <= 0 || pt[1] <= 0) {
                ledger("skip_no_point", t, null, "目标坐标未知（无 endpoint/盟友坐标/target_point）");
                return;
            }
            Map<String, Object> formation = formationOf(engine, cfg);
            if (formation == null || formation.isEmpty()) {
                ledger("skip_no_formation", t, null, "编队不可用（hero/兵种数据缺失）");
                return;
            }
            ServerGuardianLauncher.LaunchResult r = launcher.launch(engine, pt[0], pt[1], formation, t.targetUid);
            if (r.ok) {
                // P0-5 同款：成功发射复位 415 英雄上限/406 兵力缩放（自适应必须能恢复）
                HeroAvailability.markSuccess(engine);
                missions.put(targetKey, new Mission(t.marchId, r.marchId, r.endtimeSec, nowMs));
                long nowS = nowSec(engine);
                long ahead = t.endEpochSec > 0 && r.endtimeSec > 0 ? t.endEpochSec - r.endtimeSec : 0L;
                String autoNote = lastAutoFormationSummary;
                String text = "已发射 type=5 驻防 marchId=" + r.marchId + " endtime=" + r.endtimeSec
                        + "（敌 endtime=" + t.endEpochSec + (ahead != 0 ? "，我方" + (ahead >= 0 ? "先于敌 " : "晚于敌 ") + Math.abs(ahead) + "s" : "")
                        + "）目标" + targetText(t, pt)
                        + (autoNote == null || autoNote.isEmpty() ? "" : "｜" + autoNote);
                FileLog.info("守护盟友发射 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("launched", t, r, text);
                writeMod("🛡" + text);
            } else {
                // 2026-10-28：415（编队人数/配置超限）/406（士兵超限）自适应 + 目标冷却 + 日志节流。
                // 本次生产事故：无编队参数时 Exec.formation 取客户端 5 英雄 → 该号每队上限 3 → 每个威胁 415，
                // 一天 930 次失败且无降噪；现在失败后下调英雄上限/兵力缩放，下一威胁即生效。
                String adapt = onLaunchFailure(engine, r.code, formation, targetKey, nowMs);
                if (adapt != null) {
                    String text = "发射失败 " + adapt + "（本目标冷却 "
                            + (LAUNCH_FAIL_COOLDOWN_MS / 1000L) + "s，不重试本威胁）";
                    long nowFail = System.currentTimeMillis();
                    String logKey = targetKey + "|" + r.code;
                    Long last = failLogAt.get(logKey);
                    if (failureLogDue(last == null ? 0L : last, nowFail, FAIL_LOG_THROTTLE_MS)) {
                        failLogAt.put(logKey, nowFail);
                        FileLog.warn("守护盟友发射失败 {}:{} code={}（{}）",
                                fpid, String.valueOf(sid), String.valueOf(r.code), adapt);
                        writeMod("⚠ " + text);
                    }
                    ledger("launch_failed", t, r, text);
                } else {
                    String text = "发射失败 code=" + r.code + "（" + r.text + "），不重试";
                    FileLog.warn("守护盟友发射失败 {}:{} {}", fpid, String.valueOf(sid), text);
                    ledger("launch_failed", t, r, text);
                    writeMod("⚠ " + text);
                }
            }
        } finally {
            activeTargets.remove(targetKey);
        }
    }

    /** 执行一次撤防（在途→recall；已驻扎→off_work；dry_run 不发）。 */
    void executeRecall(WjdrEngine engine, Mission mission, String reason) {
        if (stopped || mission == null) return;
        Config cfg = config;
        if (!cfg.enabled || cfg.dryRun) return;
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return;
        try {
            long nowS = nowSec(engine);
            boolean inFlight = ServerGuardianLauncher.inFlightAt(mission.endtimeSec, nowS);
            String proto = ServerGuardianLauncher.recallProtoFor(inFlight);
            GameMessage rsp = engine.request(proto, ServerGuardianLauncher.buildMarchIdArgs(mission.marchId));
            int code = Exec.codeOf(rsp);
            String text = "撤防 " + proto.replace("req_world_march_", "") + " marchId=" + mission.marchId
                    + " code=" + code + "（" + reason + "）";
            if (code == 0) {
                missions.entrySet().removeIf(en -> en.getValue().marchId == mission.marchId);
                FileLog.info("守护盟友撤防 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("recalled", null, null, text);
                writeMod("🧹" + text);
            } else {
                FileLog.warn("守护盟友撤防失败 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("recall_failed", null, null, text);
            }
        } catch (Throwable t) {
            FileLog.warn("守护盟友撤防异常 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
            ledger("recall_error", null, null, "撤防异常：" + brief(t.getMessage()));
        }
    }

    // =====================================================================
    // P1：对等加速（敌方 endtime 前移/压缩 → 我方在途驻防同步加速）
    // 触发：situation 推送里同一敌方 marchId 的 endtime 前移（=敌方加速）；
    // 条件：该威胁对应我方驻防 Mission 已发射且仍在途；动作在 guardExec 单线程串行执行。
    // 关闭（speedup_enabled=false，默认）：本段零请求、零行为变化。
    // =====================================================================

    /**
     * 敌方加速事件入口（推送线程调用；只做判定 + 入队，不发请求）。
     *
     * <p>幂等：按（敌方 marchId, 压缩后 endtime）去重——同一压缩态重复推送只处理一次；
     * 敌方再次压缩（endtime 更小）才是新一轮最小响应（App 19:05「精准止损，不囤积领先」同口径）。
     *
     * @param prev 该行军上一条已知状态（压缩前）
     * @param cur  合并后的最新状态（压缩后）
     */
    void maybeSubmitSpeedup(WjdrEngine engine, Threat prev, Threat cur, Config cfg, long nowMs) {
        try {
            if (prev == null || cur == null || cfg == null) return;
            if (!cfg.speedupEnabled) return;                          // 默认关：零行为变化
            if (prev.endEpochSec <= 0 || cur.endEpochSec <= 0) return;
            if (cur.endEpochSec >= prev.endEpochSec) return;           // 只处理 endtime 前移（敌方加速）
            Mission m = missions.get(targetKey(cur));
            if (m == null || m.enemyMarchId != cur.marchId) return;    // 无我方在途驻防 → 不加速
            Long handled = speedupRounds.get(cur.marchId);
            if (handled != null && handled.longValue() == cur.endEpochSec) return;   // 同一压缩态幂等
            speedupRounds.put(cur.marchId, cur.endEpochSec);
            long delta = prev.endEpochSec - cur.endEpochSec;
            if (cfg.dryRun) {
                String note = "敌加速 " + delta + "s（endtime " + prev.endEpochSec + "→" + cur.endEpochSec
                        + "）→ [DRY] 将对在途驻防 marchId=" + m.marchId
                        + " 对等加速（dry_run 不发请求）";
                FileLog.info("守护盟友加速[DRY] {}:{} {}", fpid, String.valueOf(sid), note);
                ledger("speedup_dry", cur, null, note);
                return;
            }
            final long enemyMarchId = cur.marchId;
            final long enemyEndSec = cur.endEpochSec;
            final long compressAtMs = nowMs;
            try {
                guardExec.execute(() -> {
                    try {
                        executeSpeedupRound(engine, enemyMarchId, enemyEndSec, compressAtMs);
                    } catch (Throwable t) {
                        FileLog.warn("守护盟友加速异常 {}:{} {}", fpid, String.valueOf(sid),
                                brief(String.valueOf(t)));
                        ledger("speedup_error", null, null, "加速异常：" + brief(t.getMessage()));
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 加速链执行（guardExec 单线程；一串行请求，不重试风暴）。
     *
     * <p>每发：重估我方/敌方 ETA + 库存 → {@link GuardianSpeedupPlanner#next} 选档 →
     * {@link ServerGuardianLauncher#speedup} 发 {@code req_world_march_speedup} →
     * 用响应新 endtime 更新 Mission → 循环，直到 planner 返回 NONE（先到/不可为/库存不足）或响应无前移。
     *
     * <p>记账：成功发一次扣本地库存；2827（道具不足）清零该档并重选；非 0 码终止（不重试）。
     * 钻石路径（planner 返回 NEED_DIAMOND 且 {@code diamond_speedup_enabled=true}）：
     * 先 {@code req_shop_quick_buy{use:false,id:301901,count:1}} 购买（8s、单发不重试、受
     * {@code diamond_max_per_march} 约束），成功则库存 +1 并立即用 301901 加速（购买+使用算 1 次链动作），
     * 失败则记账终止（不冷却，下次压缩事件可再试）。
     * 所有分支都写 FileLog + 账本（ev=speedup/speedup_skip/speedup_failed/speedup_no_item/speedup_no_move/
     * speedup_diamond_buy/speedup_diamond_buy_failed/speedup_diamond_skip）。
     *
     * @param enemyMarchId 敌方行军 id（威胁表键）
     * @param enemyEndSec  触发本次加速的敌方 endtime（服务器秒；执行时以威胁表最新值为准）
     * @param compressAtMs 收到压缩推送的本机毫秒时刻（G2 p95 观测：推送→请求发出）
     */
    void executeSpeedupRound(WjdrEngine engine, long enemyMarchId, long enemyEndSec, long compressAtMs) {
        if (stopped) return;
        Config cfg = config;
        if (cfg == null || !cfg.speedupEnabled) return;
        Threat t = threats.get(enemyMarchId);
        if (t == null) return;                                        // 敌已消失（召回/落地）
        Mission m = missions.get(targetKey(t));
        if (m == null || m.enemyMarchId != enemyMarchId) return;
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) {
            ledger("speedup_skip", t, null, "会话不在线，跳过加速（不补发旧情）");
            return;
        }
        long marginMs = Math.max(0L, cfg.speedupMarginS) * 1000L;
        int usedRound = 0;
        int usedMarch = speedupUsedByMarch.getOrDefault(m.marchId, 0);
        for (int guard = 0; guard <= GuardianSpeedupPlanner.MAX_SHOTS_PER_ROUND; guard++) {
            if (stopped) return;
            Threat latest = threats.get(enemyMarchId);
            if (latest == null) return;
            long enemyEnd = latest.endEpochSec > 0 ? latest.endEpochSec : enemyEndSec;
            long nowMs = serverNowMs(engine);
            long ourRemain = m.endtimeSec * 1000L - nowMs;
            long enemyRemain = enemyEnd * 1000L - nowMs;
            refreshStock(engine);
            GuardianSpeedupPlanner.Step step = GuardianSpeedupPlanner.next(
                    new GuardianSpeedupPlanner.Input(ourRemain, enemyRemain, marginMs,
                            stock25, stock50, usedMarch, cfg.maxSpeedupsPerMarch, usedRound,
                            cfg.diamondSpeedupEnabled));
            long itemId;
            if (step.action == GuardianSpeedupPlanner.Action.NEED_DIAMOND) {
                // ---- P1 钻石路径（只有 diamond_speedup_enabled=true 才可能到这里）----
                // 安全闸 1：开关双保险（planner 已按开关返回，这里防配置热载竞态）
                if (!cfg.diamondSpeedupEnabled) {
                    FileLog.info("守护盟友加速决策 {}:{} R3 未出手 enemyMarchId={} ownMarchId={} "
                                    + "action={} reason=diamond_disabled（planner 意图但开关已关，不发）",
                            fpid, String.valueOf(sid), enemyMarchId, m.marchId, step.action);
                    ledgerSpeedup("speedup_skip", t, m, 0L, m.endtimeSec, m.endtimeSec,
                            stock25, stock50, "diamond_disabled", step.reason, -1, -1L);
                    return;
                }
                // 安全闸 2：库存未知（背包快照未同步）不花钻石，避免「以为 0 实际有道具」
                if (stock25 < 0L || stock50 < 0L) {
                    FileLog.warn("守护盟友钻石购买跳过 {}:{} ownMarchId={} 库存未知（背包快照未同步），不花钻石",
                            fpid, String.valueOf(sid), m.marchId);
                    ledgerDiamond("speedup_diamond_skip", t, m, 0L, 0L, 0,
                            stock25, stock50, "stock_unknown",
                            "背包快照未同步（库存未知），不花钻石，止损", -1, -1L);
                    return;
                }
                // 安全闸 3：本行军购买上限（diamond_max_per_march；0=不限）
                int bought = diamondBoughtByMarch.getOrDefault(m.marchId, 0);
                if (cfg.diamondMaxPerMarch > 0 && bought >= cfg.diamondMaxPerMarch) {
                    FileLog.warn("守护盟友钻石购买止损 {}:{} ownMarchId={} 已达 diamond_max_per_march={}（已购 {}），不超买",
                            fpid, String.valueOf(sid), m.marchId, cfg.diamondMaxPerMarch, bought);
                    ledgerDiamond("speedup_diamond_skip", t, m,
                            GuardianSpeedupPlanner.ITEM_SPEEDUP_50, 0L, bought,
                            stock25, stock50, "diamond_limit",
                            "已达 diamond_max_per_march=" + cfg.diamondMaxPerMarch
                                    + "（本行军已购 " + bought + "），止损不超买", -1, -1L);
                    return;
                }
                long buyAtMs = serverNowMs(engine);
                ServerGuardianLauncher.QuickBuyResult br = launcher.quickBuy(
                        engine, GuardianSpeedupPlanner.ITEM_SPEEDUP_50, 1L);
                long buyLatencyMs = buyAtMs - compressAtMs;
                if (!br.ok) {
                    // 单发不重试；不设冷却（供应链/余额问题，下次压缩事件可再尝试）
                    FileLog.warn("守护盟友钻石购买失败 {}:{} ownMarchId={} code={}（{}），链终止不重试",
                            fpid, String.valueOf(sid), m.marchId, br.code, br.text);
                    ledgerDiamond("speedup_diamond_buy_failed", t, m, br.itemId, 0L, bought,
                            stock25, stock50, "code_" + br.code, br.text, br.code, buyLatencyMs);
                    return;
                }
                diamondBoughtByMarch.put(m.marchId, bought + 1);
                stock50 = Math.max(0L, stock50) + 1L;   // 301901 已入背包 → 本地库存记账 +1
                FileLog.info("守护盟友钻石购买 {}:{} 成功 ownMarchId={} itemid={} count=1 "
                                + "diamondBought={} stock50={} latencyMs={}",
                        fpid, String.valueOf(sid), m.marchId, br.itemId,
                        bought + 1, stock50, buyLatencyMs);
                ledgerDiamond("speedup_diamond_buy", t, m, br.itemId, 1L, bought + 1,
                        stock25, stock50, "need_diamond", br.text, br.code, buyLatencyMs);
                // 与 App 同序（GuardianLaunch.java:996-1010）：买完立即在同一轮用 301901 发加速；
                // 「购买+使用」算本次链动作 1 次（与直接用道具同口径，不放宽单轮 6 发上限）。
                itemId = GuardianSpeedupPlanner.ITEM_SPEEDUP_50;
            } else if (!step.usesItem()) {
                FileLog.info("守护盟友加速决策 {}:{} R3 未出手 enemyMarchId={} ownMarchId={} "
                                + "ourEnd={} enemyEnd={} ourRemain={}ms enemyRemain={}ms ahead={}ms "
                                + "margin={}ms stock25={} stock50={} action={} reason={} detail={}",
                        fpid, String.valueOf(sid), enemyMarchId, m.marchId,
                        m.endtimeSec, enemyEnd, ourRemain, enemyRemain, step.aheadBeforeMs,
                        marginMs, stock25, stock50, step.action, step.reasonCode, step.reason);
                ledgerSpeedup("speedup_skip", t, m, 0L, m.endtimeSec, m.endtimeSec,
                        stock25, stock50, step.reasonCode, step.reason, -1, -1L);
                return;
            } else {
                itemId = step.itemId;
            }
            long beforeEnd = m.endtimeSec;
            ServerGuardianLauncher.SpeedupResult r = launcher.speedup(engine, m.marchId, itemId);
            usedRound++;
            usedMarch++;
            speedupUsedByMarch.put(m.marchId, usedMarch);
            long latencyMs = nowMs - compressAtMs;
            if (r.code == ServerGuardianLauncher.CODE_ITEM_SHORTAGE) {
                // 2827=道具不足：清零该档本地记账 → 下一轮按 planner 重选/止损（不冷却：供应链问题不是行军问题）
                if (itemId == GuardianSpeedupPlanner.ITEM_SPEEDUP_50) stock50 = 0L;
                else stock25 = 0L;
                stockLoadedAtMs = System.currentTimeMillis();
                FileLog.warn("守护盟友加速道具不足 {}:{} itemid={}（2827），清零该档库存记账后重选档位",
                        fpid, String.valueOf(sid), itemId);
                ledgerSpeedup("speedup_no_item", t, m, itemId, beforeEnd, m.endtimeSec,
                        stock25, stock50, "item_shortage_2827", r.text, r.code, latencyMs);
                continue;
            }
            if (!r.ok) {
                FileLog.warn("守护盟友加速失败 {}:{} ownMarchId={} itemid={} code={}（{}）",
                        fpid, String.valueOf(sid), m.marchId, itemId, r.code, r.text);
                ledgerSpeedup("speedup_failed", t, m, itemId, beforeEnd, m.endtimeSec,
                        stock25, stock50, "code_" + r.code, r.text, r.code, latencyMs);
                return;
            }
            // code=0：道具已消耗，扣本地库存（不信过期快照）
            if (itemId == GuardianSpeedupPlanner.ITEM_SPEEDUP_50) {
                stock50 = Math.max(0L, stock50 - 1L);
            } else {
                stock25 = Math.max(0L, stock25 - 1L);
            }
            long afterEnd = r.endtimeSec;
            boolean moved = afterEnd > 0 && afterEnd < beforeEnd;
            if (moved) m.endtimeSec = afterEnd;
            long now2 = serverNowMs(engine);
            long afterRemain = moved ? m.endtimeSec * 1000L - now2 : ourRemain;
            long aheadAfter = enemyRemain - afterRemain;
            String tier = itemId == GuardianSpeedupPlanner.ITEM_SPEEDUP_25 ? "一阶25%" : "二阶50%";
            FileLog.info("守护盟友加速 {}:{} R3 第{}发[{}]{} ownMarchId={} enemyMarchId={} "
                            + "ourEnd={}→{} enemyEnd={} ourRemain={}ms→{}ms ahead={}ms→{}ms "
                            + "itemid={} stock25={} stock50={} latencyMs={} reason={}",
                    fpid, String.valueOf(sid), usedRound, tier, moved ? "生效✅" : "无前移",
                    m.marchId, enemyMarchId, beforeEnd, moved ? String.valueOf(afterEnd) : "?",
                    enemyEnd, ourRemain, afterRemain, step.aheadBeforeMs, aheadAfter,
                    itemId, stock25, stock50, latencyMs, step.reasonCode);
            ledgerSpeedup(moved ? "speedup" : "speedup_no_move", t, m, itemId, beforeEnd,
                    m.endtimeSec, stock25, stock50, step.reasonCode, step.reason, r.code, latencyMs);
            if (!moved) return;   // 无前移=触底/单发上限实证，链终止（保留已扣道具记账）
        }
    }

    // ---------------- P1：道具库存记账 ----------------

    /**
     * 道具库存（本地记账，App 19:15/19:20 教训「不再信过期快照」同口径）：
     * <ul>
     *   <li>未知（-1）→ 先读核心资产库背包，回退 {@code notify_backpack_init/update} 推送；</li>
     *   <li>已知 → 每次成功加速本地递减；2827 清零该档；</li>
     *   <li>两档都为 0 且超过 {@link #STOCK_REFRESH_MILLIS} 才重新读快照（发现活动补给）。</li>
     * </ul>
     * 背包快照未同步时保持未知（不误判为 0 永久止损），本次不发请求。
     */
    void refreshStock(WjdrEngine engine) {
        try {
            long now = System.currentTimeMillis();
            boolean unknown = stock25 < 0L || stock50 < 0L;
            boolean emptyLately = stock25 == 0L && stock50 == 0L
                    && now - stockLoadedAtMs > STOCK_REFRESH_MILLIS;
            if (!unknown && !emptyLately) return;
            long n25 = -1L;
            long n50 = -1L;
            try {
                com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                        com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready()) {
                    n25 = lib.backpack().get(GuardianSpeedupPlanner.ITEM_SPEEDUP_25);
                    n50 = lib.backpack().get(GuardianSpeedupPlanner.ITEM_SPEEDUP_50);
                }
            } catch (Throwable ignored) {
            }
            if (n25 < 0L || n50 < 0L) {
                long[] counts = backpackSpeedupCounts(engine);
                if (counts != null) {
                    n25 = counts[0];
                    n50 = counts[1];
                }
            }
            if (n25 < 0L && n50 < 0L) return;   // 背包快照未到：保持未知，下次再试
            stock25 = Math.max(0L, n25 < 0L ? 0L : n25);
            stock50 = Math.max(0L, n50 < 0L ? 0L : n50);
            stockLoadedAtMs = now;
            FileLog.info("守护盟友加速库存 {}:{} 25%={} 50%={}（来源：资产库/背包推送）",
                    fpid, String.valueOf(sid), stock25, stock50);
        } catch (Throwable ignored) {
        }
    }

    /** 背包推送 → {25% 库存, 50% 库存}；未同步（无 notify_backpack_init/update）返回 null。 */
    static long[] backpackSpeedupCounts(WjdrEngine engine) {
        if (engine == null) return null;
        try {
            Map<String, Object> bp = engine.notifyData("notify_backpack_init");
            if (bp == null) bp = engine.notifyData("notify_backpack_update");
            return backpackCounts(bp);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 背包 map（{@code data} Map/List；元素 {@code {id,count}} 或 map 形 "301900.0"）→ {25%,50%}。
     * 背包已同步但无这两种道具 → 视为 0；未同步（null/空）→ null。
     */
    static long[] backpackCounts(Map<String, Object> backpack) {
        if (backpack == null) return null;
        long n25 = -1L;
        long n50 = -1L;
        Object data = backpack.get("data");
        if (data == null) data = backpack;
        try {
            if (data instanceof Map) {
                for (Map.Entry<?, ?> en : ((Map<?, ?>) data).entrySet()) {
                    long id = Exec.longOf(en.getKey());
                    Object v = en.getValue();
                    long cnt;
                    if (v instanceof Map) {
                        Map<String, Object> vm = Exec.mapOf(v);
                        if (id <= 0L) id = Exec.longOf(vm.get("id"));
                        cnt = Exec.longOf(vm.get("count"));
                    } else {
                        cnt = Exec.longOf(v);
                    }
                    if (id == GuardianSpeedupPlanner.ITEM_SPEEDUP_25) n25 = cnt;
                    else if (id == GuardianSpeedupPlanner.ITEM_SPEEDUP_50) n50 = cnt;
                }
            } else {
                for (Map<String, Object> m : Exec.mapsOf(data)) {
                    long id = Exec.longOf(m.get("id"));
                    if (id == GuardianSpeedupPlanner.ITEM_SPEEDUP_25) n25 = Exec.longOf(m.get("count"));
                    else if (id == GuardianSpeedupPlanner.ITEM_SPEEDUP_50) n50 = Exec.longOf(m.get("count"));
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        if (n25 < 0L && n50 < 0L) {
            // data 为空/无相关道具：背包已同步即视为 0；完全空 map 也当 0（保守不发）
            return new long[]{0L, 0L};
        }
        return new long[]{Math.max(0L, n25), Math.max(0L, n50)};
    }

    /** 服务器毫秒时钟（已校准时用 GameServerClock；否则本机时钟）。 */
    private long serverNowMs(WjdrEngine engine) {
        try {
            if (engine != null && engine.serverClock() != null && engine.serverClock().locked()) {
                return engine.serverClock().nowServerMs();
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis();
    }

    // =====================================================================
    // 配置读取（roleParams `guardian`，默认关）
    // =====================================================================

    /** 当前生效配置快照（包可见：单测/诊断用；返回只读引用）。 */
    Config configSnapshot() {
        return config;
    }

    /** 配置热载（10s 节流；force=启动时立即读）。任何异常 → 关闭（fail-closed）。 */
    void reloadConfig(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastConfigLoadAt < CONFIG_TTL_MILLIS) return;
        lastConfigLoadAt = now;
        try {
            VaultStore.Account acc = vault == null ? null : vault.find(fpid);
            Map<String, String> raw = acc == null ? null : acc.roleParams.get(String.valueOf(sid));
            String json = raw == null ? null : raw.get(FN_ID);
            // 无 App 参数时也必须走 parseConfig(null) 以应用 env 实盘/灰度覆盖（VAULT_GUARDIAN_*）；
            // 无 env 时 parseConfig(null) 等价 Config.disabled()（默认关，零行为变化）。
            Config parsed = json == null || json.isEmpty()
                    ? parseConfig(null) : parseConfig(new JSONObject(json));
            // 批次10：编队库可能由 App 同步在独立 formation fn 参数里（与 FormationResolver.libraryFor 同口径兜底）
            if (parsed.formationLibraryJson.isEmpty() && raw != null) {
                try {
                    String fj = raw.get("formation");
                    if (fj != null && !fj.isEmpty()) {
                        parsed.formationLibraryJson = new JSONObject(fj).optString("formation_library", "");
                    }
                } catch (Throwable ignored) {
                }
            }
            config = parsed;
        } catch (Throwable t) {
            config = Config.disabled();
        }
    }

    /**
     * 该角色是否处于「需要队列驻防盟友」的守护实盘状态（R2 Phase2B，2026-10-29 用户口径）：
     * {@code enabled && !dryRun}（parseConfig 已合并 env 覆盖）。
     *
     * <p>用途：战争态（WARTIME 让位）只对这类角色触发——只有守护盟友实盘需要占用行军队列
     * 去驻防盟友，才需要在战时让位自动化任务；未开守护或演练（dry_run）的账号/角色不应
     * 被联盟战场推送无谓地推入战争态。
     *
     * @param acc 账号（null → false）
     * @param sid 区服 id
     * @param uid 角色 uid（优先按 uid 取参数；缺失回退该 sid 参数=区服默认）
     */
    public static boolean queueDefenseActive(VaultStore.Account acc, long sid, long uid) {
        if (acc == null) return false;
        try {
            java.util.Map<String, String> raw = VaultStore.roleParamsOf(acc, sid, uid);
            String json = raw == null ? null : raw.get(FN_ID);
            // 无 App 参数时也必须走 parseConfig(null) 以应用 env 灰度覆盖（实盘开关）
            Config c = json == null || json.isEmpty()
                    ? parseConfig(null) : parseConfig(new JSONObject(json));
            return c.enabled && !c.dryRun;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析 roleParams `guardian` JSON（纯函数；缺省/异常一律关闭+dry_run；env 灰度可覆盖）。 */
    static Config parseConfig(JSONObject p) {
        Config c = Config.disabled();
        if (p == null) {
            applyEnvOverrides(c);
            return c;
        }
        try {
            JSONObject sub = p.optJSONObject("sub");
            JSONObject st = p.optJSONObject("strategy");
            // 总开关（2026-10-24）：显式 enabled 键为权威值（App 配置卡「总开关」）；仅在旧数据
            // 没有 enabled 键时才把 FnDetail 既有 intercept 开关当作启用标志（向后兼容）。
            // 否则「总开关关 + 旧 intercept=true」会把模块重新打开，违背总开关语义。
            c.enabled = has(sub, st, p, "enabled")
                    ? bool(sub, st, p, "enabled", false)
                    : bool(sub, st, p, "intercept", false);
            c.dryRun = bool(sub, st, p, "dry_run", true);
            c.recall = bool(sub, st, p, "recall", true);
            c.speedupEnabled = bool(sub, st, p, "speedup_enabled", false);   // P1 对等加速总开关
            // P1 对等加速参数（默认全关/安全值；缺键=旧行为）
            c.diamondSpeedupEnabled = bool(sub, st, p, "diamond_speedup_enabled", false);
            long diamondMax = num(sub, st, p, "diamond_max_per_march", 3L);
            c.diamondMaxPerMarch = diamondMax < 0L ? 3 : (int) Math.min(Integer.MAX_VALUE, diamondMax);
            long marginS = num(sub, st, p, "speedup_margin_s", 2L);
            if (marginS < 0L || marginS > 60L) marginS = 2L;   // 界外值忽略（防配置错误把 margin 当负/无限）
            c.speedupMarginS = marginS;
            long maxSpeedups = num(sub, st, p, "max_speedups_per_march", 0L);
            c.maxSpeedupsPerMarch = maxSpeedups <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, maxSpeedups);
            // 批次11：强制上线查询开关 + 敌方兵力下限（0=不限；兼容别名 min_enemy_troops）
            c.forceOnline = bool(sub, st, p, "force_online", false);
            long troopsMin = num(sub, st, p, "troops_min", -1L);
            if (troopsMin < 0) troopsMin = num(sub, st, p, "min_enemy_troops", 0L);
            c.troopsMin = Math.max(0L, troopsMin);
            c.allyUid = Math.max(0L, num(sub, st, p, "ally_uid", 0L));
            c.targetUid = Math.max(0L, num(sub, st, p, "target_uid", 0L));
            Object tp = first(sub, st, p, "target_point");
            if (tp instanceof JSONObject) {
                JSONObject o = (JSONObject) tp;
                c.targetX = o.optInt("x", 0);
                c.targetY = o.optInt("y", 0);
            } else if (tp instanceof JSONArray && ((JSONArray) tp).length() >= 2) {
                c.targetX = ((JSONArray) tp).optInt(0, 0);
                c.targetY = ((JSONArray) tp).optInt(1, 0);
            } else if (tp instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) tp;
                c.targetX = (int) Exec.longOf(m.get("x"));
                c.targetY = (int) Exec.longOf(m.get("y"));
            } else if (tp instanceof List && ((List<?>) tp).size() >= 2) {
                c.targetX = (int) Exec.longOf(((List<?>) tp).get(0));
                c.targetY = (int) Exec.longOf(((List<?>) tp).get(1));
            }
            Object fm = first(sub, st, p, "formation");
            if (fm instanceof JSONObject) {
                JSONObject o = (JSONObject) fm;
                int squad = o.optInt("squad_index", 0);
                if (squad <= 0) squad = o.optInt("squad", 0);
                if (squad > 0) c.squadIndex = squad;
                JSONArray heroes = o.optJSONArray("hero_ids");
                if (heroes != null) {
                    for (int i = 0; i < heroes.length(); i++) {
                        long id = heroes.optLong(i, 0L);
                        if (id > 0) c.heroIds.add(id);
                    }
                }
            } else if (fm instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) fm;
                int squad = (int) Exec.longOf(m.get("squad_index"));
                if (squad <= 0) squad = (int) Exec.longOf(m.get("squad"));
                if (squad > 0) c.squadIndex = squad;
                Object heroes = m.get("hero_ids");
                if (heroes instanceof List) {
                    for (Object h : (List<?>) heroes) {
                        long id = Exec.longOf(h);
                        if (id > 0) c.heroIds.add(id);
                    }
                }
            } else {
                int squad = (int) num(sub, st, p, "squad_index", 0L);
                if (squad > 0) c.squadIndex = squad;
            }
            long d = num(sub, st, p, "d_sec", DEFAULT_D_SEC);
            if (d >= 10L && d <= 600L) c.dSec = d;   // 界外值忽略（防配置错误把 D 当 0）
            Object rd = first(sub, st, p, "recall_distance");
            if (rd != null) c.recallDistance = Math.max(0d, Exec.longOf(rd));
        } catch (Throwable ignored) {
            Config bad = Config.disabled();
            applyEnvOverrides(bad);
            return bad;
        }
        // P1/灰度环境变量覆盖（可选）：App 侧尚无 guardian 新键入口时，可用服务端 env 灰度/实盘开启；
        // env 未设置/非法 → 完全以 roleParams 为准（默认零变化）。
        applyEnvOverrides(c);
        // 批次10：统一编队参数（无这些键 → isConfigured=false → 旧行为）
        c.rawParams = ServerGuardianLauncher.toParamMap(p);
        c.formationLibraryJson = p.optString("formation_library", "");
        return c;
    }

    private static Object first(JSONObject sub, JSONObject st, JSONObject top, String key) {
        if (sub != null && sub.has(key)) return sub.opt(key);
        if (st != null && st.has(key)) return st.opt(key);
        return top == null ? null : top.opt(key);
    }

    /**
     * 灰度环境变量布尔覆盖：true/1/yes/on → TRUE；false/0/no/off → FALSE；
     * 未设置/空/非法 → null（调用方保持 roleParams 原值，默认行为不变）。
     * System 属性优先（单测注入），其次环境变量。
     */
    static Boolean envBool(String key) {
        String v = setting(key);
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        if ("true".equalsIgnoreCase(s) || "1".equals(s)
                || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)
                || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    private static String setting(String key) {
        try {
            String p = System.getProperty(key);
            if (p != null) return p;
        } catch (Throwable ignored) {
        }
        try {
            return System.getenv(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 灰度/实盘 env 覆盖（未设置或不合法 → 保持 roleParams 值，默认行为零变化）：
     * <ul>
     *   <li>{@code VAULT_GUARDIAN_ENABLED=1|0}：守护总开关（实盘开启无需 App 参数入口）</li>
     *   <li>{@code VAULT_GUARDIAN_DRY_RUN=1|0}：演练开关（实盘需 0）</li>
     *   <li>{@code VAULT_GUARDIAN_TROOPS_MIN=N}：敌方兵力下限（0=不限）</li>
     *   <li>{@code VAULT_GUARDIAN_SPEEDUP=1|0}：P1 对等加速总开关</li>
     *   <li>{@code VAULT_GUARDIAN_DIAMOND=1|0}：钻石购买开关（真实花钻石，慎用）</li>
     * </ul>
     * 目标（盟友 uid / 坐标）无需配置：威胁目标由联盟/世界 push 自动解析
     * （{@code decide} 的 ally_uid/target_uid/target_point 均为 0 时不过滤、{@code resolveTargetPoint}
     * 优先用 push endpoint，其次盟友坐标表）。
     */
    static void applyEnvOverrides(Config c) {
        if (c == null) return;
        Boolean enabled = envBool("VAULT_GUARDIAN_ENABLED");
        if (enabled != null) c.enabled = enabled;
        Boolean dryRun = envBool("VAULT_GUARDIAN_DRY_RUN");
        if (dryRun != null) c.dryRun = dryRun;
        Boolean speedup = envBool("VAULT_GUARDIAN_SPEEDUP");
        if (speedup != null) c.speedupEnabled = speedup;
        Boolean diamond = envBool("VAULT_GUARDIAN_DIAMOND");
        if (diamond != null) c.diamondSpeedupEnabled = diamond;
        String troops = setting("VAULT_GUARDIAN_TROOPS_MIN");
        if (troops != null) {
            try {
                long v = Long.parseLong(troops.trim());
                if (v >= 0L) c.troopsMin = v;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 键是否显式存在（三处任一）——用于区分「显式 false」与「旧数据缺键」。 */
    private static boolean has(JSONObject sub, JSONObject st, JSONObject top, String key) {
        if (sub != null && sub.has(key)) return true;
        if (st != null && st.has(key)) return true;
        return top != null && top.has(key);
    }

    private static boolean bool(JSONObject sub, JSONObject st, JSONObject top, String key, boolean def) {
        Object v = first(sub, st, top, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)) return false;
        }
        return def;
    }

    private static long num(JSONObject sub, JSONObject st, JSONObject top, String key, long def) {
        Object v = first(sub, st, top, key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // =====================================================================
    // 推送解析（纯函数；容错递归，不猜协议）
    // =====================================================================

    /** 解析一条 situation 推送中的敌军行军 → Threat 列表（add/update/init；重复 id 合并）。 */
    static List<Threat> parseThreats(Map<String, Object> content) {
        List<Threat> out = new ArrayList<>();
        if (content == null) return out;
        long nowMs = System.currentTimeMillis();
        Map<Long, Threat> merged = new LinkedHashMap<>();
        for (Map<String, Object> m : marchesOf(content)) {
            long id = Exec.longOf(m.get("id"));
            long owner = Exec.longOf(m.get("owner"));
            if (id <= 0 || owner <= 0) continue;
            long type = Exec.longOf(m.get("type"));
            long end = firstLong(m, "endtime", "end_time", "endTime");
            long targetUid = targetUidOf(m);
            int[] xy = endpointOf(m);
            long[] troops = troopsOf(m);
            long power = firstLong(m, "power");
            Threat t = new Threat(id, owner, type, targetUid,
                    xy == null ? 0 : xy[0], xy == null ? 0 : xy[1], xy != null, end, nowMs,
                    troops[0], troops[1] > 0, power);
            Threat prev = merged.get(id);
            merged.put(id, prev == null ? t : prev.merge(t));
            if (merged.size() >= MAX_CANDIDATES) break;
        }
        out.addAll(merged.values());
        return out;
    }

    /**
     * 解析 del 推送中的行军 id（形态 `{to_marchs:[{id}]}` / `{marchs:[...]}` / `{ids:[..]}` / `{id}`）。
     * del 推送只带 `{id,type,kid}`（不含 owner），故不能复用 marchesOf 的 owner 过滤，这里按结构直取 id。
     */
    static List<Long> parseDeletedMarchIds(Map<String, Object> content) {
        Set<Long> ids = new LinkedHashSet<>();
        collectDeletedIds(content, ids, 0);
        return new ArrayList<>(ids);
    }

    private static void collectDeletedIds(Object node, Set<Long> ids, int depth) {
        if (node == null || depth > MAX_DEPTH || ids.size() >= MAX_CANDIDATES) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectDeletedIds(o, ids, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long direct = Exec.longOf(m.get("id"));
        if (direct > 0) ids.add(direct);
        for (String k : new String[]{"ids", "march_ids", "marchIds"}) {
            Object v = m.get(k);
            if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    long id = Exec.longOf(o);
                    if (id > 0) ids.add(id);
                }
            }
        }
        for (String k : new String[]{"to_marchs", "marchs", "data", "info", "content"}) {
            Object v = m.get(k);
            if (v instanceof Map || v instanceof List) collectDeletedIds(v, ids, depth + 1);
        }
    }

    /** 解析盟友坐标推送（`member_list` map/list 或 `member_pos` 单条）→ uid → (x,y)。 */
    static Map<Long, long[]> parseMemberPos(Map<String, Object> content) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        if (content == null) return out;
        Object single = content.get("member_pos");
        if (single instanceof Map) collectMemberPos((Map<?, ?>) single, out);
        Object list = content.get("member_list");
        if (list instanceof Map) {
            for (Object v : ((Map<?, ?>) list).values()) collectMemberPos(v, out);
        } else if (list instanceof List) {
            for (Object v : (List<?>) list) collectMemberPos(v, out);
        }
        if (out.isEmpty() && content.containsKey("uid")) collectMemberPos(content, out);
        return out;
    }

    private static void collectMemberPos(Object node, Map<Long, long[]> out) {
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long uid = Exec.longOf(m.get("uid"));
        long x = Exec.longOf(m.get("x"));
        long y = Exec.longOf(m.get("y"));
        if (uid > 0 && x > 0 && y > 0) out.put(uid, new long[]{x, y});
    }

    /**
     * 递归收集推送中的行军对象（三种实证形状）：
     * <pre>
     *   目标系列: {"to_marchs":[{...}]} / {"marchs":[{...}]}
     *   观察哨:   {"marches":{"9.0":{"march":{...},"power":0}}}
     *   包装层:   {"data":{...}} / {"info":{...}}
     * </pre>
     * 防御式：任意嵌套含 id+owner 的 map 也可能是 march 候选，不锁定单一形状。
     */
    static List<Map<String, Object>> marchesOf(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectMarches(content, out, 0);
        return out;
    }

    private static void collectMarches(Object node, List<Map<String, Object>> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_CANDIDATES) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectMarches(o, out, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        Object marches = m.get("marches");
        if (marches instanceof Map) {
            for (Object v : ((Map<?, ?>) marches).values()) {
                Map<String, Object> e = Exec.mapOf(v);
                if (e == null) continue;
                Map<String, Object> inner = Exec.mapOf(e.get("march"));
                Map<String, Object> cand = inner != null ? inner : e;
                if (inner != null && !inner.containsKey("power")) {
                    // 观察哨外层 power（slot.power）：挂到行军候选上，供兵力阈值/诊断消费
                    long outerPower = Exec.longOf(e.get("power"));
                    if (outerPower > 0) {
                        Map<String, Object> withPower = new LinkedHashMap<>(inner);
                        withPower.put("power", outerPower);
                        cand = withPower;
                    }
                }
                if (isMarchLike(cand)) out.add(cand);
            }
        } else if (marches instanceof List) {
            collectMarches(marches, out, depth + 1);
        }
        for (String key : new String[]{"to_marchs", "marchs"}) {
            Object arr = m.get(key);
            if (arr instanceof List) collectMarches(arr, out, depth + 1);
        }
        if (isMarchLike(m)) out.add(Exec.mapOf(m));
        for (String wrap : new String[]{"data", "info", "content", "detail"}) {
            Object w = m.get(wrap);
            if (w instanceof Map || w instanceof List) collectMarches(w, out, depth + 1);
        }
    }

    private static boolean isMarchLike(Map<?, ?> m) {
        return m != null && Exec.longOf(m.get("id")) > 0 && Exec.longOf(m.get("owner")) > 0
                && (m.containsKey("endtime") || m.containsKey("end_time") || m.containsKey("type"));
    }

    private static long firstLong(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                long n = Exec.longOf(v);
                if (n > 0) return n;
            }
        }
        return 0L;
    }

    /**
     * 敌方兵力汇总（{@code march.squad_list.soldier}，与 {@code WarNotify.troopsOf} 同口径）：
     * map 键=兵种id 值=数量 / 值={count:n} / list 元素={id,count}；士兵 id=兵种*10000+等级*100。
     *
     * @return {@code [合计兵力, 1=已知/0=未知]}；无可算兵力（如观察哨只有 power 无 squad）视为未知
     */
    static long[] troopsOf(Map<String, Object> m) {
        long total = 0L;
        boolean known = false;
        if (m != null) {
            Map<String, Object> squad = Exec.mapOf(m.get("squad_list"));
            Object soldier = squad == null ? null : squad.get("soldier");
            if (soldier instanceof Map) {
                for (Map.Entry<?, ?> en : ((Map<?, ?>) soldier).entrySet()) {
                    long id = Exec.longOf(en.getKey());
                    Object v = en.getValue();
                    long cnt = 0L;
                    if (v instanceof Number) {
                        cnt = ((Number) v).longValue();
                    } else {
                        Map<String, Object> vm = Exec.mapOf(v);
                        if (vm != null) {
                            cnt = Exec.longOf(vm.get("count"));
                            if (id <= 0) id = Exec.longOf(vm.get("id"));
                        }
                    }
                    if (id > 0 && cnt > 0) {
                        total += cnt;
                        known = true;
                    }
                }
            } else if (soldier instanceof List) {
                for (Object o : (List<?>) soldier) {
                    Map<String, Object> vm = Exec.mapOf(o);
                    if (vm == null) continue;
                    long id = Exec.longOf(vm.get("id"));
                    long cnt = Exec.longOf(vm.get("count"));
                    if (id > 0 && cnt > 0) {
                        total += cnt;
                        known = true;
                    }
                }
            }
        }
        return new long[]{total, known ? 1L : 0L};
    }

    /** 守方/目标 uid：atk_city.targetid 优先，退化 target.map_city.uid / target_uid（ServerShieldResponder 同口径）。 */
    static long targetUidOf(Map<String, Object> m) {
        if (m == null) return 0L;
        Map<String, Object> atk = Exec.mapOf(m.get("atk_city"));
        if (atk != null) {
            long t = Exec.longOf(atk.get("targetid"));
            if (t > 0) return t;
            t = Exec.longOf(atk.get("uid"));
            if (t > 0) return t;
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null) {
                long uid = Exec.longOf(city.get("uid"));
                if (uid > 0) return uid;
            }
            long uid = Exec.longOf(target.get("uid"));
            if (uid > 0) return uid;
        }
        return Exec.longOf(m.get("target_uid"));
    }

    /** 行军目标坐标：endpoint{x,y} → target.map_city{x,y}（防御式；取不到 null）。 */
    static int[] endpointOf(Map<String, Object> m) {
        if (m == null) return null;
        int[] p = pointOf(Exec.mapOf(m.get("endpoint")));
        if (p != null) return p;
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            p = pointOf(Exec.mapOf(target.get("map_city")));
            if (p != null) return p;
            p = pointOf(target);
            if (p != null) return p;
        }
        return pointOf(m);
    }

    private static int[] pointOf(Map<String, Object> m) {
        if (m == null) return null;
        long x = Exec.longOf(m.get("x"));
        long y = Exec.longOf(m.get("y"));
        return x > 0 && y > 0 ? new int[]{(int) x, (int) y} : null;
    }

    // =====================================================================
    // 规则判定 / 目标解析（纯函数）
    // =====================================================================

    /** P0 规则：type=2 进攻 + ETA 够 + 目标匹配（单盟友白名单）。 */
    static Decision decide(Threat t, Config cfg, long nowMs) {
        if (t == null) return Decision.no("no_threat");
        if (cfg == null || !cfg.enabled) return Decision.no("disabled");
        if (t.type != MARCH_TYPE_ATTACK) return Decision.no("not_attack（type=" + t.type + " 不驻防）");
        long nowS = nowMs / 1000L;
        if (t.endEpochSec <= 0) return Decision.no("no_eta");
        if (t.endEpochSec - nowS < MIN_ETA_SEC) return Decision.no("eta_too_close（剩余 " + (t.endEpochSec - nowS) + "s）");
        // 单盟友过滤：ally_uid 优先；target_uid/target_point 为可选精确过滤
        if (cfg.allyUid > 0 && t.targetUid > 0 && t.targetUid != cfg.allyUid) {
            return Decision.no("not_ally（targetUid=" + t.targetUid + " != ally_uid=" + cfg.allyUid + "）");
        }
        if (cfg.targetUid > 0) {
            if (t.targetUid > 0) {
                if (t.targetUid != cfg.targetUid) return Decision.no("target_uid 不符：" + t.targetUid);
            } else if (cfg.hasTargetPoint()) {
                if (!t.hasXY || t.x != cfg.targetX || t.y != cfg.targetY) {
                    return Decision.no("target_uid 未知且 endpoint 不匹配 target_point");
                }
            } else {
                return Decision.no("target_uid 未知（配置了 target_uid 但推送无目标 uid）");
            }
        }
        if (cfg.hasTargetPoint()) {
            if (!t.hasXY) return Decision.no("target_point 已配置但推送无坐标");
            if (t.x != cfg.targetX || t.y != cfg.targetY) {
                return Decision.no("endpoint 不匹配 target_point（" + t.x + "," + t.y + "）");
            }
        }
        // 兵力下限阈值（批次11；默认 0=不限，与旧行为一致）：
        // 已知且低于阈值 → 不派兵；未知 → 守护侧保守不发射（宁可少驻防，不把兵送入劣势战）
        if (cfg.troopsMin > 0) {
            if (!t.troopsKnown) {
                return Decision.no("troops_unknown（敌方兵力未知 < troops_min=" + cfg.troopsMin
                        + "，守护侧保守不发射）");
            }
            if (t.troops < cfg.troopsMin) {
                return Decision.no("troops_low（敌方兵力 " + t.troops + " < troops_min="
                        + cfg.troopsMin + "，不派兵）");
            }
        }
        return Decision.hit();
    }

    /** 目标坐标：威胁 endpoint → target_point 配置 → 盟友坐标表（targetUid）。 */
    int[] resolveTargetPoint(Threat t, Config cfg) {
        if (t == null) return null;
        if (t.hasXY && t.x > 0 && t.y > 0) return new int[]{t.x, t.y};
        if (cfg != null && cfg.hasTargetPoint()) return new int[]{cfg.targetX, cfg.targetY};
        if (t.targetUid > 0) {
            long[] xy = allies.get(t.targetUid);
            if (xy != null) return new int[]{(int) xy[0], (int) xy[1]};
        }
        return null;
    }

    private static String targetKey(Threat t) {
        if (t == null) return "unknown";
        return t.targetUid > 0 ? "t:" + t.targetUid
                : (t.hasXY ? "p:" + t.x + "," + t.y : "m:" + t.marchId);
    }

    private static String targetText(Threat t, int[] pt) {
        StringBuilder sb = new StringBuilder();
        if (t != null && t.targetUid > 0) sb.append("uid").append(t.targetUid);
        if (pt != null) sb.append(sb.length() > 0 ? " " : "").append('(').append(pt[0]).append(',').append(pt[1]).append(')');
        return sb.toString();
    }

    /**
     * 编队（批次10 统一编队接入）：
     * <ol>
     *   <li>显式编队参数 → {@link ServerGuardianLauncher#resolveFormation}（ROLE/AUTOMATIC；不可用→null=上层
     *       SKIP，不静默回退）；</li>
     *   <li>无编队参数 → 旧行为：hero_ids 优先，否则 Exec.formation(engine, squad_index)（缺省队 1）。</li>
     * </ol>
     * 三变体自发现在 {@link ServerGuardianLauncher#launch}（与本方法解耦，保留不变）。
     */
    Map<String, Object> formationOf(WjdrEngine engine, Config cfg) {
        try {
            ServerGuardianLauncher.FormationResolution resolved = ServerGuardianLauncher.resolveFormation(
                    engine, cfg == null ? null : cfg.rawParams,
                    cfg == null ? "" : cfg.formationLibraryJson);
            if (resolved.configured) {
                if (!resolved.ready()) {
                    FileLog.warn("守护盟友统一编队不可用 {}:{}：{}", fpid, String.valueOf(sid), resolved.reason);
                    return null;
                }
                FileLog.info("守护盟友统一编队 {}:{}：{}", fpid, String.valueOf(sid), resolved.label);
                return resolved.payload;
            }
            // 2026-10-28 415 修复：旧路径直接用客户端整队英雄（实证 5 个）→ 该号每队上限 3 → 永远 415。
            // 英雄数统一夹到 HeroAvailability.autoHeroLimit（默认 3；415 后 3→2→1），
            // 并按 soldierScalePercent 缩放兵力（406 自适应）。
            int heroLimit = HeroAvailability.autoHeroLimit(engine);
            int scale = HeroAvailability.soldierScalePercent(engine);
            if (cfg != null && cfg.heroIds != null && !cfg.heroIds.isEmpty()) {
                return scaleFormationSoldiers(
                        Exec.formationWithHeroes(engine, clampFormationHeroes(cfg.heroIds, heroLimit)),
                        scale);
            }
            // 2026-10-28 用户需求：无任何显式编队参数时，不再用客户端编队（可能 5 英雄 → 415），
            // 改为「最强空闲盾/矛/弓各一名 + 盾50%/矛20%/弓30% 按出征上限带兵」的 AUTO 编队。
            lastAutoFormationSummary = "";
            AutoFormation auto = guardianAutoFormation(engine);
            if (auto == null) return null;
            lastAutoFormationSummary = auto.summary;
            return auto.payload;
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // AUTO 最强空闲英雄编队（2026-10-28 用户需求）
    // 盾:矛:弓 = 50:20:30，按出征容量夹取；复用 FormationResolver.autoProfessionHeroes /
    // autoOutcome（已含在城/黑名单/415 限档/406 缩放/容量夹取），不重复实现选人/分配逻辑。
    // =====================================================================

    /** 自动编队结果：payload + 可读摘要（日志/账本/单测用）。 */
    static final class AutoFormation {
        final Map<String, Object> payload;
        final String summary;

        AutoFormation(Map<String, Object> payload, String summary) {
            this.payload = payload;
            this.summary = summary == null ? "" : summary;
        }
    }

    /** 守护盟友 AUTO 编队比例（用户需求）：盾 50% / 矛 20% / 弓 30%。 */
    static Map<String, Long> guardianAutoRatio() {
        Map<String, Long> r = new LinkedHashMap<>();
        r.put("infantry", 50L);
        r.put("lancer", 20L);
        r.put("marksman", 30L);
        return r;
    }

    /** 英雄职业数摘要（纯函数）：盾x/矛y/弓z（职业未知不计）。 */
    static String heroProfessionSummary(List<Long> heroIds) {
        int shield = 0, spear = 0, bow = 0;
        if (heroIds != null) {
            for (Long id : heroIds) {
                if (id == null || id <= 0L) continue;
                int p = HeroCatalog.profession(id);
                if (p == 1) shield++;
                else if (p == 2) spear++;
                else if (p == 3) bow++;
            }
        }
        return "盾" + shield + "/矛" + spear + "/弓" + bow;
    }

    /**
     * AUTO 最强空闲英雄编队：
     * <ol>
     *   <li>英雄：{@link FormationResolver#autoProfessionHeroes} —— 最强盾/矛/弓各一，
     *       已含在城/黑名单/前序占用过滤，并受 415 限档（3→2→1）；</li>
     *   <li>容量：{@link FormationCatalog#capacityForHeroes}（出征上限；0=未知 → 传 0 走库存/5万兜底）；</li>
     *   <li>配兵：{@link FormationResolver#autoOutcome} 按 {@link #guardianAutoRatio()} 50:20:30
     *       分配并按容量夹取（406 缩放/415 提示由 Resolver 内部消费）。</li>
     * </ol>
     *
     * @return payload + 摘要；英雄/库存未同步 → null（上层 skip_no_formation，可读原因见日志）
     */
    AutoFormation guardianAutoFormation(WjdrEngine engine) {
        if (engine == null) return null;
        List<Long> heroes = FormationResolver.autoProfessionHeroes(engine);
        if (heroes.isEmpty()) {
            FileLog.info("守护盟友自动编队不可用 {}:{}：英雄目录未同步/无可用英雄（notify_hero_init）",
                    fpid, String.valueOf(sid));
            return null;
        }
        long capacity = FormationCatalog.capacityForHeroes(engine, heroes);
        String capText = capacity > 0L ? String.valueOf(capacity) : "未知(兜底库存)";
        FormationResolver.ResolveOutcome out =
                FormationResolver.autoOutcome(engine, guardianAutoRatio(), Math.max(0L, capacity));
        if (!out.ready()) {
            FileLog.info("守护盟友自动编队不可用 {}:{}：{}（英雄{} 容量={}）",
                    fpid, String.valueOf(sid), out.reason, heroProfessionSummary(heroes), capText);
            return null;
        }
        ResolvedFormation rf = out.formation;
        String summary = "自动编队 英雄[" + heroProfessionSummary(rf.heroIds) + "]"
                + " 容量=" + capText
                + " 兵力=盾" + rf.infantry + "/矛" + rf.lancer + "/弓" + rf.marksman
                + " 比例50:20:30（" + out.reason + "）";
        FileLog.info("守护盟友 {}", summary);
        return new AutoFormation(rf.toFormationPayload(), summary);
    }

    // =====================================================================
    // 415/406 自适应（2026-10-28 生产事故：无编队参数取 5 英雄 → 该号上限 3 → 永远 415）
    // =====================================================================

    /** 英雄数夹取（纯函数）：最多 limit 个；limit&lt;=0 视为默认 3。 */
    static List<Long> clampFormationHeroes(List<Long> heroes, int limit) {
        List<Long> out = new ArrayList<>();
        if (heroes == null) return out;
        int cap = limit > 0 ? limit : HeroAvailability.DEFAULT_AUTO_HEROES;
        for (Long id : heroes) {
            if (out.size() >= cap) break;
            if (id != null && id > 0L) out.add(id);
        }
        return out;
    }

    /**
     * 兵力缩放（纯函数；406 自适应）：{@code formation.soldier=[{k,v}]} 每项 v 按 percent 缩放（≥1）。
     * percent&lt;100 且存在 soldier 时才生成新 payload；其余原样返回。
     */
    static Map<String, Object> scaleFormationSoldiers(Map<String, Object> formation, int percent) {
        if (formation == null || percent >= 100 || percent <= 0) return formation;
        Object raw = formation.get("soldier");
        if (!(raw instanceof List)) return formation;
        List<Map<String, Object>> scaled = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> pair = Exec.mapOf(o);
            if (pair == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(pair);
            long v = Exec.longOf(pair.get("v"));
            if (v > 0L) copy.put("v", Math.max(1L, v * percent / 100L));
            scaled.add(copy);
        }
        Map<String, Object> out = new LinkedHashMap<>(formation);
        out.put("soldier", scaled);
        return out;
    }

    /** 冷却期判定（纯函数）：until&gt;0 且 now&lt;until。 */
    static boolean inLaunchCooldown(long untilMs, long nowMs) {
        return untilMs > 0L && nowMs < untilMs;
    }

    /** 失败日志节流判定（纯函数）：距上次 ≥window 才再记（时钟回拨按可记）。 */
    static boolean failureLogDue(long lastAtMs, long nowMs, long windowMs) {
        if (lastAtMs <= 0L) return true;
        long delta = nowMs - lastAtMs;
        return delta < 0L || delta >= windowMs;
    }

    /**
     * 发射失败自适应（415/406）：下调英雄上限/兵力缩放 + 设置目标冷却，返回可读文案；其它码返回 null。
     * <ul>
     *   <li>415：{@link HeroAvailability#noteHeroCountRejected}(实际英雄数) → 下一威胁按更低上限重试；</li>
     *   <li>406：{@link HeroAvailability#noteSoldierLimitRejected} → 下一威胁按更低兵力缩放。</li>
     * </ul>
     */
    String onLaunchFailure(WjdrEngine engine, long code, Map<String, Object> formation,
                           String targetKey, long nowMs) {
        if (code != 415L && code != 406L) return null;
        String text;
        if (code == 415L) {
            int used = HeroAvailability.heroIdsOf(formation).size();
            int next = HeroAvailability.noteHeroCountRejected(engine, used);
            text = "编队人数超限(415，英雄" + used + ")，已下调至 " + next + " 英雄重试";
        } else {
            int scale = HeroAvailability.noteSoldierLimitRejected(engine);
            text = "士兵超限(406)，兵力已缩放至 " + scale + "%重试";
        }
        if (targetKey != null && !targetKey.isEmpty()) {
            targetCooldowns.put(targetKey, nowMs + LAUNCH_FAIL_COOLDOWN_MS);
        }
        return text;
    }

    /** 测试/诊断：某目标当前是否处于 415/406 失败冷却期。 */
    boolean targetInCooldown(String targetKey, long nowMs) {
        Long until = targetKey == null ? null : targetCooldowns.get(targetKey);
        return until != null && inLaunchCooldown(until, nowMs);
    }

    // =====================================================================
    // 队列校验（发射前；未知一律宁可不发）
    // =====================================================================

    /**
     * 剩余可出征队列数；容量/占用取不到 → -1（上层跳过发射）。
     *
     * <p>W2：优先核心资产库 {@code queue().capacity/occupied}（网页 MarchCapacityState 派生公式 +
     * 排除巨熊等特殊队列、含 type=99 上车）。seed 未 READY / 回滚开关 off / 队列域未知时，
     * 逐项回退旧读法（容量读 {@code notify_world_player_init} 的 capacity 字段 + 行军明细请求）。
     */
    int freeMarchSlots(WjdrEngine engine) {
        try {
            if (assetLibraryEnabled()) {
                try {
                    com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                            com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
                    if (lib != null && lib.ready()) {
                        com.carlos.vault.wjdr.asset.AssetSnapshot.Queue q = lib.queue();
                        if (q != null && q.capacity > 0) {
                            // idle 已含 pending 与 limitReached 语义（网页 BehemothAccountState.idle 同口径）
                            return Math.max(0, q.idle);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            int capacity = capacityOf(engine.notifyData(PROTO_WORLD_PLAYER_INIT));
            if (capacity <= 0) return -1;
            GameMessage rsp = engine.request(PROTO_MARCH_DETAIL,
                    Exec.args("owner", engine.lastUid(), "kid", engine.lastServerId()), 5);
            if (Exec.codeOf(rsp) != 0 || rsp.getContent() == null) return -1;
            return freeSlots(capacity, activeMarchesOf(rsp.getContent()));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 资产库读侧回滚开关（默认 on；{@code VAULT_ASSET_LIBRARY=off} 全部回退旧读取）。 */
    static boolean assetLibraryEnabled() {
        try {
            String v = System.getenv("VAULT_ASSET_LIBRARY");
            return v == null || !"off".equalsIgnoreCase(v.trim());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 世界出征容量：`notify_world_player_init.world.capacity`（同 ServerFieldTasks 口径），取不到 0。 */
    static int capacityOf(Map<String, Object> worldPlayerInit) {
        if (worldPlayerInit == null) return 0;
        Integer n = findCapacity(worldPlayerInit.get("world"), 0);
        if (n == null) n = findCapacity(worldPlayerInit, 0);
        int cap = n == null ? 0 : n;
        return cap > 0 && cap <= 12 ? cap : 0;
    }

    private static Integer findCapacity(Object node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object c = m.get("capacity");
            if (c instanceof Number) {
                int n = ((Number) c).intValue();
                if (n > 0) return n;
            }
            for (Object v : m.values()) {
                Integer n = findCapacity(v, depth + 1);
                if (n != null) return n;
            }
        }
        return null;
    }

    /**
     * 自身在途/驻扎行军数（用于"空队列"校验）：递归找含 id+state 的 march 对象，
     * state ∈ {2 在途, 3 驻扎, 4 回程} 计占用（同 App QueueLedger 口径；去重防嵌套重复）。
     */
    static int activeMarchesOf(Map<String, Object> detailRsp) {
        if (detailRsp == null) return 0;
        Map<Long, Long> states = new LinkedHashMap<>();
        collectMarchStates(detailRsp, states, 0);
        int n = 0;
        for (Long st : states.values()) {
            if (st == 2L || st == 3L || st == 4L) n++;
        }
        return n;
    }

    private static void collectMarchStates(Object node, Map<Long, Long> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 200) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectMarchStates(o, out, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long id = Exec.longOf(m.get("id"));
        long state = Exec.longOf(m.get("state"));
        boolean marchLike = id > 0 && state > 0
                && (m.containsKey("type") || m.containsKey("endtime") || m.containsKey("end_time"));
        if (marchLike) out.putIfAbsent(id, state);
        for (Object v : m.values()) {
            if (v instanceof Map || v instanceof List) collectMarchStates(v, out, depth + 1);
        }
    }

    /** 剩余队列数（纯函数；负值防御）。 */
    static int freeSlots(int capacity, int active) {
        return Math.max(0, capacity - active);
    }

    // =====================================================================
    // 状态输出（mod/srv 键型固定，供 App/日报消费）
    // =====================================================================

    /** 本次动作的账本记录（最新在前，上限 {@link #LEDGER_CAP}）。 */
    void ledger(String event, Threat t, ServerGuardianLauncher.LaunchResult r, String note) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ev", event == null ? "" : event);
            if (t != null) {
                e.put("marchId", t.marchId);
                e.put("owner", t.ownerUid);
                e.put("type", t.type);
                e.put("endSec", t.endEpochSec);
                e.put("targetUid", t.targetUid);
                if (t.hasXY) {
                    e.put("x", t.x);
                    e.put("y", t.y);
                }
            }
            if (r != null) {
                e.put("ownMarchId", r.marchId);
                e.put("ownEndSec", r.endtimeSec);
                e.put("code", r.code);
                e.put("variant", r.variant);
            }
            e.put("note", clip(note, 400));
            prependLedger(e);
        } catch (Throwable ignored) {
        }
    }

    /**
     * P1 加速账本（与 App {@code guardian_log} 同口径字段便于对拍）：
     * 敌方 marchId / 我方 ownMarchId / 道具 itemid / 加速前后 ownEndSec(beforeSec→ownEndSec) /
     * 敌方 endSec / 库存 stock25/stock50 / 原因 reason + note / 业务码 code / 推送→请求 latencyMs。
     */
    void ledgerSpeedup(String event, Threat t, Mission m, long itemId, long beforeEndSec,
                       long afterEndSec, long stock25, long stock50, String reasonCode,
                       String reason, int code, long latencyMs) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ev", event == null ? "" : event);
            if (t != null) {
                e.put("marchId", t.marchId);
                e.put("owner", t.ownerUid);
                e.put("type", t.type);
                e.put("endSec", t.endEpochSec);
                e.put("targetUid", t.targetUid);
            }
            if (m != null) {
                e.put("ownMarchId", m.marchId);
                e.put("ownEndSec", afterEndSec);
                e.put("beforeSec", beforeEndSec);
            }
            if (itemId > 0L) e.put("itemid", itemId);
            e.put("stock25", stock25);
            e.put("stock50", stock50);
            e.put("reason", reasonCode == null ? "" : reasonCode);
            if (code != -1) e.put("code", code);
            if (latencyMs >= 0L) e.put("latencyMs", latencyMs);
            e.put("note", clip(reason, 400));
            prependLedger(e);
        } catch (Throwable ignored) {
        }
    }

    /**
     * P1 钻石路径账本：{@code speedup_diamond_buy / speedup_diamond_buy_failed / speedup_diamond_skip}。
     *
     * <p>字段：itemid / buyCount（本次花费道具数）/ diamondBought（本行军累计购买数）/
     * stock25/stock50（购买后库存）/ reason + note / code / latencyMs；不含任何敏感信息（钻石余额等）。
     */
    void ledgerDiamond(String event, Threat t, Mission m, long itemId, long buyCount,
                       int diamondBought, long stock25, long stock50, String reasonCode,
                       String reason, int code, long latencyMs) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ev", event == null ? "" : event);
            if (t != null) {
                e.put("marchId", t.marchId);
                e.put("owner", t.ownerUid);
                e.put("type", t.type);
                e.put("endSec", t.endEpochSec);
                e.put("targetUid", t.targetUid);
            }
            if (m != null) {
                e.put("ownMarchId", m.marchId);
                e.put("ownEndSec", m.endtimeSec);
            }
            if (itemId > 0L) e.put("itemid", itemId);
            e.put("buyCount", buyCount);
            e.put("diamondBought", diamondBought);
            e.put("stock25", stock25);
            e.put("stock50", stock50);
            e.put("reason", reasonCode == null ? "" : reasonCode);
            if (code != -1) e.put("code", code);
            if (latencyMs >= 0L) e.put("latencyMs", latencyMs);
            e.put("note", clip(reason, 400));
            prependLedger(e);
        } catch (Throwable ignored) {
        }
    }

    /** 账本前置写入（最新在前、上限 {@link #LEDGER_CAP}；异常静默）。包内可见便于单测。 */
    void prependLedger(JSONObject e) {
        try {
            String legacy = ledgerKey(fpid, sid);
            String key = ledgerKey();
            JSONArray arr = new JSONArray();
            String raw = readState(key, legacy);
            if (raw != null) {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable ignored) {
                }
            }
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && i < LEDGER_CAP - 1; i++) out.put(arr.get(i));
            if (store != null) {
                writeState(key, legacy, out.toString());
                store.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 写 `mod:{fpid}:{sid}[:{uid}]:guardian`（App FnDetail/今日页读旧键，active 角色双写）。 */
    void writeMod(String text) {
        try {
            if (store == null) return;
            String legacy = modKey(fpid, sid);
            String key = modKey();
            JSONObject o = new JSONObject();
            String raw = readState(key, legacy);
            if (raw != null) {
                try {
                    o = new JSONObject(raw);
                } catch (Throwable ignored) {
                }
            }
            o.put("last", clip(text, 400));
            o.put("last_ts", System.currentTimeMillis());
            o.put("last_trg", "守护");
            writeState(key, legacy, o.toString());
            store.flush();
        } catch (Throwable ignored) {
        }
    }

    /** 本实例账本键（Phase 2B）：uid&gt;0 → 角色键 `srv:guardian:<fpid>:<sid>:<uid>:ledger`；否则旧键。 */
    String ledgerKey() {
        return roleUid > 0L
                ? "srv:guardian:" + fpid + ":" + sid + ":" + roleUid + ":ledger"
                : ledgerKey(fpid, sid);
    }

    /** 本实例状态键（Phase 2B）：uid&gt;0 → `mod:<fpid>:<sid>:<uid>:guardian`；否则旧键。 */
    String modKey() {
        return roleUid > 0L
                ? "mod:" + fpid + ":" + sid + ":" + roleUid + ":" + FN_ID
                : modKey(fpid, sid);
    }

    static String ledgerKey(String fpid, long sid) {
        return "srv:guardian:" + fpid + ":" + sid + ":ledger";
    }

    static String modKey(String fpid, long sid) {
        return "mod:" + fpid + ":" + sid + ":" + FN_ID;
    }

    private String warYieldNote() {
        try {
            return ServerWarState.wartimeYield(fpid) ? "战时" : "非战时";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private long nowSec(WjdrEngine engine) {
        try {
            if (engine != null && engine.serverClock() != null && engine.serverClock().locked()) {
                return engine.serverClock().nowServerSec();
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() / 1000L;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String brief(String s) {
        return clip(s, 120);
    }

    // =====================================================================
    // 诊断（日志/单测）
    // =====================================================================

    /** 诊断一行：配置 + 威胁/任务/盟友数 + 最近推送（日志/接口用，不泄露敏感信息）。 */
    public String describe() {
        long ago = lastPushAt > 0 ? (System.currentTimeMillis() - lastPushAt) : -1L;
        return "guardian{fpid=" + fpid + ", sid=" + sid + ", " + config.describe()
                + ", threats=" + threats.size() + ", missions=" + missions.size()
                + ", allies=" + allies.size() + ", lastProto=" + lastProto
                + ", lastPushAgoMs=" + ago + (stopped ? ", stopped" : "") + "}";
    }

    /** 配置快照（单测/诊断）。 */
    Config config() {
        return config;
    }

    /** 威胁表大小（单测/诊断）。 */
    int threatCount() {
        return threats.size();
    }

    /** 任务表大小（单测/诊断）。 */
    int missionCount() {
        return missions.size();
    }

    /** 盟友坐标表大小（单测/诊断）。 */
    int allyCount() {
        return allies.size();
    }

    /** 单测等待执行器空闲（提交的任务先于本次调用全部完成）。 */
    void awaitIdleForTest(long timeoutMs) {
        try {
            guardExec.submit(() -> {
            }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }
}
