package com.carlos.vaultserver;

import com.carlos.vault.wjdr.GameServerClock;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.RoleKey;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.StateKeys;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;
import com.carlos.vaultserver.watchpoint.WatchPointEngine;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 反应式开盾（P1-2 服务器补位，承接 App DailySchedulerService.onGameEvent 改 no-op 后的缺口）：
 * 服务器模式"被攻击/侦查自动开免费盾"的常驻实现，行为对齐原版 WAR/RECON 分支。
 *
 * <ul>
 *   <li>事件源 = 世界行军推送（协议实证见 design/守护盟友-协议实证-20261015.md）：
 *     notify_world_situation_info_add/_up（观察哨系列）与 notify_world_alliance_situation_add/_update
 *     （目标系列）。type=1=侦查(RECON)、type=2=进攻(WAR)（type=5 驻防不触发），target_type=1=攻玩家城；
 *     目标 uid（atk_city.targetid 或 target.map_city.uid）必须等于本角色 uid——只防自己城，
 *     不为邻城 attack 浪费每日免费盾；</li>
 *   <li>推送线程只解析+置脏（同 ServerHelpResponder 模式），开盾动作由会话线程 tick 驱动，
 *     与心跳/采集天然串行（engine.requestLock 串行，无并发互踢）；</li>
 *   <li>门控（2026-10-24 扩展）：总开关 battle_shield.enabled（缺省 true，关=定时免费盾与反应式都停）
 *     + 账号自动化开启（auto/keep 统一开关）+ 子开关 battle_shield.reactive != false（默认 true，
 *     App FnDetail 同款语义）；</li>
 *   <li>触发策略（strategy 子项，可独立配置；2026-11-06 决策：只读新键，旧键
 *     eta_sec/troops_min 不再兼容）：
 *     <ul>
 *       <li>shield_troops_min：来袭兵力下限（march.squad_list.soldier 求和，与 WarNotify.troopsOf 同口径）；
 *         已知且低于阈值→跳过留痕；兵力未知→按保护优先照常触发并留痕；仅在防骚扰
 *         shield_anti_harass 开启时生效；</li>
 *       <li>shield_eta_sec：卡秒——&gt;0 时记录敌方到达服务器秒（endtime），剩余 &gt; N 秒则挂起等待
 *         （保持 pending，不用 10 分钟 EVENT_TTL 判死）；剩余 ≤ N 秒才开盾；</li>
 *       <li>shield_mode：immediate=收到即开 / before_arrival=抵达前 N 秒（缺省）；</li>
 *     </ul></li>
 *   <li>force_online：{@link #forceOnlineWanted()} 供 SessionKeeper 在非 online 分支查询——
 *     存在新鲜风险（未过期/未到达）且总开关+reactive+force_online 均开时返回 true，由会话管理器
 *     强制回连；本类只回答"要不要回连"，不自己登录；</li>
 *   <li>幂等：开盾成功（8 小时护盾）后 8 小时内不重复开；其余结果（今日已用/协议异常/错误码）
 *     5 分钟冷却，防推送风暴；shield_mode=immediate 时事件超过 10 分钟才被处理则丢弃——human_first 让位/掉线期
 *     积压的事件自然过期，回连后不为旧攻击补盾（before_arrival 的挂起风险不受此限）；</li>
 *   <li>让位保护：tick 只在会话 online 时执行（LiveSession 亦只在 online 分支调用），人玩期间
 *     服务器不发任何请求，不互踢；</li>
 *   <li>留痕：FileLog + mod:&lt;fpid&gt;:&lt;sid&gt;:&lt;uid&gt;:battle_shield（Phase 2B 按角色；
 *     active 角色额外镜像写旧键 mod:fpid:sid:battle_shield，App FnDetail/今日页同键展示不变，
 *     文案区分 总开关关闭/兵力不足/等待卡秒/已触发）。</li>
 * </ul>
 */
public final class ServerShieldResponder {

    // ---------------- 协议常量（实证口径，勿凭猜测改） ----------------

    /** 行军类型：1=侦查 2=进攻 10/12/99/100/102=集结类（5=驻防/增援不触发护盾）。 */
    private static final long MARCH_RECON = 1L;
    private static final long MARCH_WAR = 2L;
    /** 集结进攻发起（100）与集结主体（102）：均按 WAR 触发护盾（对齐网页版 RALLY_CITY_ATTACK
     *  与 ServerGuardianEngine.DEFAULT_ATTACK_TYPES={2,100,102}；实证 A-1：99=随从、102=主体）。 */
    private static final long MARCH_RALLY = 100L;
    private static final long MARCH_RALLY_MAIN = 102L;

    /** 目标类型：1=玩家城（打野/情报类 target_type 非 1，不触发）。 */
    private static final long TARGET_CITY = 1L;
    /** 目标类型：2=地图资源点（采集点遭袭识别用，对齐网页版 targetsOwnResource）。 */
    private static final long TARGET_RESOURCE = 2L;
    /** 目标采集行军类型：4=个人采集；110=联盟矿采集（仅识别，不自动撤联盟矿）。 */
    private static final long MARCH_GATHER = 4L;
    private static final long MARCH_ALLIANCE_GATHER = 110L;

    /** 是否集结类行军（100=发起集结 / 102=集结主体）；99 随从与集结同 ETA，不单独触发。 */
    static boolean isRallyType(long type) {
        return type == MARCH_RALLY || type == MARCH_RALLY_MAIN;
    }

    /** 采集极限场景：召回失败重试间隔 / 最大尝试次数 / watch 空闲回收。 */
    static final long GATHER_WATCH_RETRY_MS = 60_000L;
    static final int GATHER_WATCH_MAX_ATTEMPTS = 2;
    static final long GATHER_WATCH_IDLE_MS = 5 * 60_000L;
    /** 采集点兜底轮询默认间隔。 */
    static final long GATHER_POLL_DEFAULT_SEC = 60L;

    /** 护盾道具 id（2026-11-05 真机抓包/商店目录实证）。 */
    static final long SHIELD_ITEM_2H = 302301L;
    static final long SHIELD_ITEM_8H = 302302L;
    static final long SHIELD_ITEM_12H = 302305L;
    /** 2 小时护盾实测价格（钻石；以实际扣钻推送为准）。 */
    static final long DIAMOND_SHIELD_PRICE = 400L;
    /** 钻石购盾日预算（默认 3 个/日）。 */
    static final int DIAMOND_BUY_DAILY_MAX = 3;
    /** 护盾账本状态回写节流。 */
    static final long SHIELD_LEDGER_WRITE_MS = 5_000L;

    /**
     * WAR/RECON 行军推送源（含 del，用于敌方行军撤回/消失）。
     * <p>{@code notify_world_situation_info}（无后缀）是登录/重连后的全量快照：不监听它，
     * 断线恢复后已在途的进攻将无法被识别，只能等下一次 add/up（可能没有）——表现为开盾过晚。
     */
    static final Set<String> SITUATION_PROTOS = Set.of(
            "notify_world_situation_info",
            "notify_world_situation_info_add",
            "notify_world_situation_info_up",
            "notify_world_situation_info_del",
            "notify_world_alliance_situation_add",
            "notify_world_alliance_situation_update",
            "notify_world_alliance_situation_del");

    /** 护盾/背包/资源账本推送源。 */
    static final Set<String> LEDGER_PROTOS = Set.of(
            "notify_backpack_init",
            "notify_backpack_update",
            "notify_resource_init",
            "notify_resource_update",
            "notify_city_item_init",
            "notify_city_item_update");

    /** 开盾成功后的幂等窗：8 小时免费护盾时长内不重复开。 */
    static final long SHIELD_ACTIVE_MILLIS = 8 * 3600_000L;
    /** 非成功结果的重试冷却：防推送风暴把协议打爆。 */
    static final long RETRY_COOLDOWN_MILLIS = 5 * 60_000L;
    /** 事件保鲜期：推送到达超过该时长才处理则丢弃（仅 shield_mode=immediate 路径）。 */
    static final long EVENT_TTL_MILLIS = 10 * 60_000L;
    /** 事件保鲜期（秒，卡秒路径判断"威胁早已到达"用）。 */
    static final long EVENT_TTL_SEC = EVENT_TTL_MILLIS / 1000L;

    // ---------------- 风险状态 ----------------

    /** 一条推送命中"自己城被侦查/进攻"后的决策输入（兵力/到达时刻用于阈值与卡秒）。 */
    private static final class Risk {
        /** WAR / RECON（严重度优先）。 */
        final String kind;
        /** 敌方到达服务器秒（多条命中取最早）；0=未知。 */
        final long endtimeSec;
        /** squad_list.soldier 求和；-1=未知。 */
        final long troops;

        Risk(String kind, long endtimeSec, long troops) {
            this.kind = kind;
            this.endtimeSec = endtimeSec;
            this.troops = troops;
        }
    }

    /** 自己正在采集的行军（来源：核心资产库 QUEUE；type=4，state=2 在途 / state=3 采集中）。 */
    static final class GatherMarch {
        final long marchId;
        final int x;
        final int y;
        /** 2=在途，3=采集中。 */
        final long state;
        /** state=2：抵达时刻；state=3：采集结束时刻。 */
        final long endtimeSec;
        final long aid;

        GatherMarch(long marchId, int x, int y) {
            this(marchId, x, y, 3L, 0L, 0L);
        }

        GatherMarch(long marchId, int x, int y, long state, long endtimeSec, long aid) {
            this.marchId = marchId;
            this.x = x;
            this.y = y;
            this.state = state;
            this.endtimeSec = endtimeSec;
            this.aid = aid;
        }
    }

    /** 敌方同点行军缓存（采集队/出征；用于极限场景判定）。 */
    static final class EnemyMarch {
        final long marchId;
        long owner;
        long aid;
        long type;
        int x;
        int y;
        long endtimeSec;
        long state;
        /** squad_list.soldier 合计；-1=未知。 */
        long troops = -1L;
        boolean landed;
        boolean accelerated;
        long lastSeenMs;

        EnemyMarch(long marchId) {
            this.marchId = marchId;
        }
    }

    /** 单条我方采集行军的极限场景 watch。 */
    static final class GatherWatch {
        final long marchId;
        int x;
        int y;
        long state;
        long aid;
        long arrivalSec;
        long gatherEndSec;
        long scheduledAtSec;
        long scheduledEnemyId;
        long lastSeenMs;
        long lastAttemptMs;
        int attempts;
        boolean recalled;
        boolean failed;
        long lastPollMs;

        GatherWatch(long marchId, int x, int y, long state, long endtimeSec) {
            this.marchId = marchId;
            this.x = x;
            this.y = y;
            this.state = state;
            if (state == 2L) this.arrivalSec = endtimeSec;
            if (state == 3L) this.gatherEndSec = endtimeSec;
        }
    }

    /** 自己采集行军来源（生产读核心资产库；单测可注入，避免依赖资产库 READY）。 */
    interface MarchSource {
        List<GatherMarch> ownGatheringMarches(WjdrEngine engine);
    }

    /** 一次采集点遭袭告警（钩子线程只入账，撤矿动作交给 tick 线程）。 */
    private static final class GatherAlert {
        final long enemyMarchId;
        final long endtimeSec;
        final int x;
        final int y;
        /** 命中的自己采集行军（可能为空：只有资源点归属能判、资产库未 READY）。 */
        final List<GatherMarch> ownMarches;
        /** 是否由 target.map_resource.uid==本人 直接判定（而非仅坐标匹配）。 */
        final boolean ownResource;

        GatherAlert(long enemyMarchId, long endtimeSec, int x, int y,
                    List<GatherMarch> ownMarches, boolean ownResource) {
            this.enemyMarchId = enemyMarchId;
            this.endtimeSec = endtimeSec;
            this.x = x;
            this.y = y;
            this.ownMarches = ownMarches == null ? Collections.<GatherMarch>emptyList() : ownMarches;
            this.ownResource = ownResource;
        }
    }

    // ---------------- 状态 ----------------

    private final String fpid;
    private final long sid;
    /** Phase 2B：角色 uid（0=旧/兼容路径，状态只写旧键）。 */
    private final long roleUid;
    /** Phase 2B：本角色是否 App 当前选中角色（true 时状态双写旧键；null/false=不镜像）。 */
    private final Supplier<Boolean> activeSupplier;
    private final StateStore store;
    private final VaultStore vault;

    /** 本机毫秒时间源（生产 System.currentTimeMillis；单测注入以便驱动卡秒）。 */
    private final LongSupplier nowMs;
    /** 服务器秒时间源（单测注入；null=生产走 engine.serverClock()，未校准退化本机秒）。 */
    private final LongSupplier serverNowSecSource;
    /** attach 时记录的引擎：forceOnlineWanted() 无引擎入参，取服务器时钟用。 */
    private volatile WjdrEngine engineRef;
    /** 自己采集行军来源（默认读核心资产库；测试可注入）。 */
    private volatile MarchSource marchSource = this::ownGatheringFromAssetLibrary;
    /** 最近一次命中的新鲜风险（未处理/挂起中）；null=无风险。 */
    private volatile Risk risk;
    /** 最近一次采集点遭袭告警（钩子线程写入，tick 消费）。 */
    private volatile GatherAlert gatherAlert;
    /** 采集告警脏标记（与 city risk 的 dirty 分离：采集撤矿与开盾是两条独立反应链）。 */
    private final AtomicBoolean gatherDirty = new AtomicBoolean(false);
    /** 已撤矿 marchId → 最近一次撤矿时刻（防同威胁重复请求；10min 冷却）。 */
    private final Map<Long, Long> gatherRecalledAt = new ConcurrentHashMap<>();
    /** 敌方同点行军缓存：marchId → 最近状态（推送线程写，tick 线程读）。 */
    private final Map<Long, EnemyMarch> enemyMarches = new ConcurrentHashMap<>();
    /**
     * 观察哨槽位 id → marchId（包可见：单测断言 del 清理）。
     * <p>{@code notify_world_situation_info_del {"id_arr":[9.0]}} 携带的是 player_situation 槽位，
     * 不是 marchId；没有这张映射就无法按 del 清理 {@link #enemyMarches}，敌方撤回后采集召回会误判。
     */
    final Map<Long, Long> situationSlotMarch = new ConcurrentHashMap<>();
    /** 我方采集 watch：marchId → 状态机（tick 线程独占写）。 */
    private final Map<Long, GatherWatch> gatherWatches = new ConcurrentHashMap<>();
    /** 背包护盾账本：itemId → count（init + update 增量）。 */
    private final Map<Long, Long> shieldBackpack = new ConcurrentHashMap<>();
    /** 护盾账本是否已从 init 初始化。 */
    private volatile boolean shieldLedgerReady;
    /** 当前生效护盾：itemId / endtime（秒）；endtime<=0 表示无。 */
    private volatile long shieldActiveItem;
    private volatile long shieldActiveEnd;
    /** 账本状态上次回写时刻。 */
    private volatile long shieldLedgerWriteMs;
    /** 钻石购盾日预算：yyyyMMdd / 当日已买次数（内存 + 状态键兜底）。 */
    private volatile String diamondBuyDay = "";
    private volatile int diamondBuysToday;
    /** 风险写入/清除互斥（推送线程 vs 会话线程），保证"处理旧风险时新推送不丢"。 */
    private final Object riskLock = new Object();
    /** 卡秒挂起下一次判定时刻（本机毫秒；0=无挂起）。 */
    volatile long etaCheckAtMs;
    /** 服务器时钟未校准告警只打一次（每 responder 实例）。 */
    private final AtomicBoolean clockWarned = new AtomicBoolean(false);

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 冷却截止时刻（包可见：单测断言幂等窗/重试窗）。 */
    final AtomicLong nextAttemptAt = new AtomicLong(0);
    /** 最近一次 WAR/RECON 推送时刻（包可见：单测模拟陈旧事件）。 */
    volatile long lastEventAt;
    /** 最近一次事件类型 WAR/RECON（包可见：单测断言）。 */
    volatile String lastEventKind = "";

    /** 旧构造（Phase 2B 前）：roleUid=0，状态只写旧键，行为完全不变。 */
    ServerShieldResponder(String fpid, long sid, StateStore store, VaultStore vault) {
        this(fpid, sid, 0L, store, vault, null, System::currentTimeMillis, null);
    }

    /**
     * 单测构造重载：注入本机毫秒与服务器秒时间源（roleUid=0，旧行为）。
     */
    ServerShieldResponder(String fpid, long sid, StateStore store, VaultStore vault,
                          LongSupplier nowMs, LongSupplier serverNowSec) {
        this(fpid, sid, 0L, store, vault, null, nowMs, serverNowSec);
    }

    /**
     * Phase 2B 公开构造（SessionKeeper 按角色挂载时使用）：
     * roleUid&gt;0 时状态键按角色隔离 {@code mod:<fpid>:<sid>:<uid>:battle_shield}；
     * {@code activeSupplier} 返回 true（App 当前选中角色）时额外镜像写旧键，App 显示链路不变。
     * roleUid&lt;=0 等价旧构造（只写旧键）。
     */
    public ServerShieldResponder(String fpid, long sid, long roleUid,
                                 StateStore store, VaultStore vault,
                                 Supplier<Boolean> activeSupplier) {
        this(fpid, sid, roleUid, store, vault, activeSupplier,
                System::currentTimeMillis, null);
    }

    /** 完整注入构造（单测：roleUid + activeSupplier + 时间源）。 */
    ServerShieldResponder(String fpid, long sid, long roleUid,
                          StateStore store, VaultStore vault,
                          Supplier<Boolean> activeSupplier,
                          LongSupplier nowMs, LongSupplier serverNowSec) {
        this.fpid = fpid;
        this.sid = sid;
        this.roleUid = roleUid;
        this.activeSupplier = activeSupplier;
        this.store = store;
        this.vault = vault;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
        this.serverNowSecSource = serverNowSec;
    }

    // ---------------- 挂载与 tick ----------------

    /** 挂到引擎消息钩子（推送线程调用：只解析+置脏，不发请求）。 */
    void attach(WjdrEngine engine) {
        engineRef = engine;
        engine.addMessageHook((proto, content) -> {
            try {
                if (SITUATION_PROTOS.contains(proto)) {
                    if (proto.endsWith("_del")) onSituationDel(content);
                    else onPush(content, engine.lastUid(), engine);
                } else if (LEDGER_PROTOS.contains(proto)) {
                    onLedgerPush(proto, content);
                }
            } catch (Throwable ignored) {
            }
        });
        initLedgerFromEngine(engine);
    }

    /**
     * 推送入口（钩子线程）：命中"自己城被侦查/进攻"记入开盾风险；
     * 命中"自己的采集点遭袭"记入采集告警（两条反应链独立）。
     * 包可见供单测直接驱动（不经过引擎钩子分发）。
     */
    void onPush(Map<String, Object> content, long ownUid) {
        onPush(content, ownUid, engineRef);
    }

    /** 推送入口（带引擎：采集行军来源需要读核心资产库；engine 可 null=测试无资产库）。 */
    void onPush(Map<String, Object> content, long ownUid, WjdrEngine engine) {
        recordSituationSlots(content);
        trackEnemyMarches(content, ownUid);
        Risk r = riskOf(content, ownUid);
        if (r != null) {
            synchronized (riskLock) {
                // 合并而非覆盖：后到的推送（可能是更晚的波次/另一支部队）不得推迟
                // 已记录的更早到达时间，否则第一波到达时无盾（"被打了才开盾"）。
                Risk base = risk;
                // 旧风险已到达超过保鲜期：不再参与合并，否则会把过期 endtime 带进新风险
                // 并在下一拍被 decide 当作陈旧事件整体丢弃（连新推送一起吞掉）。
                if (base != null && base.endtimeSec > 0L
                        && base.endtimeSec < serverNowSec(engine) - EVENT_TTL_SEC) {
                    base = null;
                }
                Risk merged = mergeRisk(base, r);
                risk = merged;
                lastEventKind = merged.kind;
                lastEventAt = nowMs.getAsLong();
                etaCheckAtMs = 0;
                dirty.set(true);
            }
        }
        GatherAlert g = gatherAlertOf(content, ownUid, engine);
        if (g != null) {
            gatherAlert = g;
            gatherDirty.set(true);
        }
    }

    /**
     * 合并新旧风险：WAR 优先；endtime 取更早的非 0 值（0=未知不参与比较）；
     * 兵力任一未知（-1）则整体未知，否则求和。
     */
    private static Risk mergeRisk(Risk old, Risk fresh) {
        if (old == null) return fresh;
        if (fresh == null) return old;
        String kind = ("WAR".equals(old.kind) || "WAR".equals(fresh.kind)) ? "WAR" : "RECON";
        long end;
        if (old.endtimeSec <= 0L) end = fresh.endtimeSec;
        else if (fresh.endtimeSec <= 0L) end = old.endtimeSec;
        else end = Math.min(old.endtimeSec, fresh.endtimeSec);
        long troops;
        if (old.troops < 0L || fresh.troops < 0L) troops = -1L;
        else troops = old.troops + fresh.troops;
        return new Risk(kind, end, troops);
    }

    /**
     * 记录观察哨槽位 → marchId：
     * ① {@code {"marches":{"9.0":{"march":{"id":...}}}}}（add/up）；
     * ② {@code {"situation_info":{...}}}（登录全量快照；Map 键或元素 id 是槽位）。
     * 仅用于 {@code notify_world_situation_info_del.id_arr} 按槽位清理敌方行军缓存。
     */
    private void recordSituationSlots(Map<String, Object> content) {
        if (content == null) return;
        recordSlots(content.get("marches"), true);
        recordSlots(content.get("situation_info"), false);
    }

    private void recordSlots(Object raw, boolean keyIsSlot) {
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                Map<String, Object> entry = Exec.mapOf(e.getValue());
                long slot = keyIsSlot ? slotIdOf(e.getKey())
                        : (entry == null ? 0L : Exec.longOf(entry.get("id")));
                putSlotMarch(slot, entry);
            }
        } else if (raw instanceof List) {
            for (Object v : (List<?>) raw) {
                Map<String, Object> entry = Exec.mapOf(v);
                putSlotMarch(entry == null ? 0L : Exec.longOf(entry.get("id")), entry);
            }
        }
    }

    private void putSlotMarch(long slot, Map<String, Object> entry) {
        if (slot <= 0L || entry == null) return;
        Map<String, Object> march = Exec.mapOf(entry.get("march"));
        long id = march == null ? Exec.longOf(entry.get("id")) : Exec.longOf(march.get("id"));
        if (id > 0L) situationSlotMarch.put(slot, id);
    }

    /** 槽位 id 宽容解析：数字/数字字符串/"9.0" 这类 double 字符串（Exec.longOf 不认 9.0）。 */
    static long slotIdOf(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
        }
        try {
            return (long) Double.parseDouble(s);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * 敌方 situation_del：兼容三种实证形状——
     * ① {@code to_marchs/marchs:[{id,...}]}（联盟态势）；
     * ② 顶层 {@code {"id":11261}}（alliance_situation_del，设计契约）；
     * ③ {@code {"id_arr":[9.0]}}（info_del，槽位 id，经 {@link #situationSlotMarch} 换 marchId）。
     */
    void onSituationDel(Map<String, Object> content) {
        if (content == null) return;
        for (Map<String, Object> m : marchesOf(content)) {
            long id = Exec.longOf(m.get("id"));
            if (id > 0) enemyMarches.remove(id);
        }
        long directId = Exec.longOf(content.get("id"));
        if (directId > 0L) enemyMarches.remove(directId);
        Object idArr = content.get("id_arr");
        if (idArr instanceof List) {
            for (Object o : (List<?>) idArr) {
                long slot = slotIdOf(o);
                if (slot <= 0L) continue;
                Long mid = situationSlotMarch.remove(slot);
                if (mid != null && mid > 0L) enemyMarches.remove(mid);
            }
        }
    }

    /** 单测：当前跟踪的敌方行军数（验证 del 各形状清理）。 */
    int trackedEnemyMarchCount() {
        return enemyMarches.size();
    }

    /** 把 situation 推送中的敌方同点行军写入缓存（供采集极限场景判定）。 */
    void trackEnemyMarches(Map<String, Object> content, long ownUid) {
        if (content == null || ownUid <= 0) return;
        long now = nowMs.getAsLong();
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            if (type != MARCH_WAR && type != MARCH_GATHER && type != MARCH_ALLIANCE_GATHER) continue;
            if (isPveMarch(m)) continue;
            long id = Exec.longOf(m.get("id"));
            long owner = Exec.longOf(m.get("owner"));
            if (id <= 0 || owner <= 0 || owner == ownUid) continue;
            int[] pt = enemyPoint(m);
            int x = pt[0], y = pt[1];
            long end = Exec.longOf(m.get("endtime"));
            long aid = Exec.longOf(m.get("aid"));
            long state = Exec.longOf(m.get("state"));
            EnemyMarch e = enemyMarches.get(id);
            if (e == null) {
                e = new EnemyMarch(id);
                enemyMarches.put(id, e);
            }
            if (end > 0 && e.endtimeSec > 0 && end < e.endtimeSec - 1) e.accelerated = true;
            e.owner = owner;
            if (aid > 0) e.aid = aid;
            e.type = type;
            if (x > 0 || y > 0) {
                e.x = x;
                e.y = y;
            }
            if (end > 0) e.endtimeSec = end;
            if (state > 0) e.state = state;
            long troops = troopsOf(m);
            if (troops >= 0L) e.troops = troops;
            e.landed = (end > 0 && end <= serverNowSec(engineRef)) || state == 3L;
            e.lastSeenMs = now;
        }
        long ttl = Math.max(120_000L, EVENT_TTL_MILLIS);
        enemyMarches.entrySet().removeIf(en -> now - en.getValue().lastSeenMs > ttl);
        // 槽位映射不随 enemyMarches 同步删除：行军可能因缺 owner/类型被 track 过滤而未入缓存，
        // 但 del 仍需按槽位精确清理；槽位数有限，仅用上限防异常 payload 无限增长。
        if (situationSlotMarch.size() > 512) situationSlotMarch.clear();
    }

    /** 敌方行军终点坐标（endpoint 优先，退化 target.map_resource）。 */
    private static int[] enemyPoint(Map<String, Object> m) {
        Map<String, Object> p = firstPoint(m);
        if (p != null) {
            long x = Exec.longOf(p.get("x")), y = Exec.longOf(p.get("y"));
            if (x > 0 || y > 0) return new int[]{(int) x, (int) y};
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        Map<String, Object> res = target == null ? null : Exec.mapOf(target.get("map_resource"));
        if (res != null) {
            return new int[]{(int) Exec.longOf(res.get("x")), (int) Exec.longOf(res.get("y"))};
        }
        return new int[]{0, 0};
    }

    /** PvE 行军过滤（巨兽/野怪等），避免采集召回误判。 */
    private static boolean isPveMarch(Map<String, Object> m) {
        if (m == null) return false;
        if (hasNonEmptyMap(m, "map_boss") || hasNonEmptyMap(m, "map_monster")
                || hasNonEmptyMap(m, "map_world_item")) return true;
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        return target != null && (hasNonEmptyMap(target, "map_boss")
                || hasNonEmptyMap(target, "map_monster")
                || hasNonEmptyMap(target, "map_world_item"));
    }

    private static boolean hasNonEmptyMap(Map<String, Object> m, String key) {
        Map<String, Object> v = m == null ? null : Exec.mapOf(m.get(key));
        return v != null && !v.isEmpty();
    }

    // ---------------- 护盾账本（init + update） ----------------

    /** 从引擎已缓存的 notify 初始化账本（attach 时调用）。 */
    void initLedgerFromEngine(WjdrEngine engine) {
        if (engine == null) return;
        try {
            boolean any = false;
            Map<String, Object> bp = engine.notifyContent("notify_backpack_init");
            if (bp != null) { applyBackpackInit(bp); any = true; }
            Map<String, Object> rp = engine.notifyContent("notify_resource_init");
            if (rp != null) { applyResourceInit(rp); any = true; }
            Map<String, Object> ci = engine.notifyContent("notify_city_item_init");
            if (ci != null) { applyCityItemInit(ci); any = true; }
            shieldLedgerReady = any;
        } catch (Throwable ignored) {
        }
    }

    /** 护盾/背包/资源推送入口（钩子线程）。 */
    void onLedgerPush(String proto, Map<String, Object> content) {
        try {
            if ("notify_backpack_init".equals(proto)) applyBackpackInit(content);
            else if ("notify_backpack_update".equals(proto)) applyBackpackUpdate(content);
            else if ("notify_resource_init".equals(proto)) applyResourceInit(content);
            else if ("notify_resource_update".equals(proto)) applyResourceUpdate(content);
            else if ("notify_city_item_init".equals(proto)) applyCityItemInit(content);
            else if ("notify_city_item_update".equals(proto)) applyCityItemUpdate(content);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Map<String, Object> content) {
        if (content == null) return Collections.emptyList();
        Object data = content.get("data");
        if (data == null) data = content.get("items");
        if (data instanceof List) return Exec.mapsOf(data);
        if (data instanceof Map) return new ArrayList<>(Exec.mapsOf(((Map<?, ?>) data).values()));
        return Collections.emptyList();
    }

    void applyBackpackInit(Map<String, Object> content) {
        if (content == null) return;
        shieldBackpack.clear();
        for (Map<String, Object> it : itemList(content)) {
            long id = Exec.longOf(it.get("id"));
            long cnt = Exec.longOf(it.get("count"));
            if (id > 0) shieldBackpack.put(id, Math.max(0L, cnt));
        }
    }

    void applyBackpackUpdate(Map<String, Object> content) {
        if (content == null) return;
        String type = String.valueOf(content.get("type"));
        boolean del = "del".equals(type) || "delete".equals(type);
        for (Map<String, Object> it : itemList(content)) {
            long id = Exec.longOf(it.get("id"));
            long cnt = Math.max(0L, Exec.longOf(it.get("count")));
            if (id <= 0) continue;
            long old = shieldBackpack.getOrDefault(id, 0L);
            shieldBackpack.put(id, del ? Math.max(0L, old - cnt) : old + cnt);
        }
    }

    void applyResourceInit(Map<String, Object> content) {
        // 钻石余额不用于决策，保留接口给后续展示/预算；当前只处理 101 增量。
        applyResourceUpdate(content);
    }

    void applyResourceUpdate(Map<String, Object> content) {
        // 当前仅钻石购盾需要计数；余额展示可由 App 实时读取，不在此持久化。
    }

    void applyCityItemInit(Map<String, Object> content) {
        if (content == null) return;
        for (Map<String, Object> it : itemList(content)) {
            if (Exec.longOf(it.get("id")) == 101L) {
                shieldActiveItem = Exec.longOf(it.get("itemid"));
                shieldActiveEnd = Exec.longOf(it.get("endtime"));
                return;
            }
        }
        shieldActiveItem = 0L;
        shieldActiveEnd = 0L;
    }

    void applyCityItemUpdate(Map<String, Object> content) {
        if (content == null) return;
        Map<String, Object> info = Exec.mapOf(content.get("info"));
        if (info == null) info = content;
        if (Exec.longOf(info.get("id")) != 101L) return;
        shieldActiveItem = Exec.longOf(info.get("itemid"));
        shieldActiveEnd = Exec.longOf(info.get("endtime"));
    }

    long shieldCount(long itemId) {
        return Math.max(0L, shieldBackpack.getOrDefault(itemId, 0L));
    }

    boolean shieldActive(WjdrEngine engine) {
        return shieldActiveEnd > 0L && shieldActiveEnd > serverNowSec(engine);
    }

    /** 把护盾账本写入 App 状态键（节流）。 */
    void writeShieldLedgerState(TaskContext tc, boolean force) {
        // 没有账本数据（旧测试/未登录）时不污染状态键。
        if (!shieldLedgerReady && shieldBackpack.isEmpty() && shieldActiveEnd <= 0L) return;
        long now = nowMs.getAsLong();
        if (!force && now - shieldLedgerWriteMs < SHIELD_LEDGER_WRITE_MS) return;
        shieldLedgerWriteMs = now;
        try {
            JSONObject mod = readShieldState();
            mod.put("shield_active_item", shieldActiveItem);
            mod.put("shield_active_end", shieldActiveEnd);
            mod.put("shield_count_302301", shieldCount(SHIELD_ITEM_2H));
            mod.put("shield_count_302302", shieldCount(SHIELD_ITEM_8H));
            mod.put("shield_count_302305", shieldCount(SHIELD_ITEM_12H));
            mod.put("shield_state_ts", now);
            writeShieldState(mod);
        } catch (Throwable ignored) {
        }
    }

    private void writeShieldState(JSONObject mod) {
        String json = mod.toString();
        if (roleUid > 0L) {
            store.putString(shieldStateKey(), json);
            if (mirrorLegacy()) store.putString(shieldStateLegacyKey(), json);
        } else {
            store.putString(shieldStateLegacyKey(), json);
        }
        store.flush();
    }

    /**
     * 会话线程 tick 驱动：先处理采集点遭袭提醒（撤矿统一由 gatherWatchTick 执行），
     * 再处理开盾风险。
     * 返回 true 表示本轮有动作（发请求/跳过留痕），false=无动作/挂起/冷却/陈旧。
     */
    boolean tick(WjdrEngine engine) {
        // 让位/恢复/登录中：不发请求（人玩优先，防互踢）；风险保留，回连如仍新鲜才补
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return false;
        long now = nowMs.getAsLong();
        boolean acted = false;
        GatherAlert g = gatherAlert;
        if (g != null && gatherDirty.compareAndSet(true, false)) {
            acted = handleGather(engine, g, now);
        }
        VaultStore.Account acc = vault.find(fpid);
        TaskContext tc = acc == null ? null : taskContext(engine, acc);
        if (tc != null) {
            try {
                if (gatherWatchTick(engine, tc, now)) acted = true;
            } catch (Throwable t) {
                FileLog.warn("采集极限场景 watch 异常 fpid={}：{}", fpid, brief(String.valueOf(t)));
            }
            writeShieldLedgerState(tc, false);
        }
        boolean fresh = dirty.compareAndSet(true, false);   // 先消费脏标记再读风险：避免与推送线程竞态丢事件
        Risk r = risk;
        if (r == null) return acted;
        if (!fresh) {
            // 无新推送：仅"卡秒挂起"到点才继续推进（等待期间保持 pending，不受 10min 判死）
            if (etaCheckAtMs <= 0 || now < etaCheckAtMs) return acted;
        }
        return decide(engine, r, now) || acted;
    }

    // ---------------- 决策（会话线程内执行） ----------------

    private boolean decide(WjdrEngine engine, Risk r, long now) {
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：账号自动化未开启", fpid);
            saveState(null, "自动化未开启，未反应式开盾");
            return true;
        }
        TaskContext tc = taskContext(engine, acc);
        // 门控链（设计 §2.2）：总开关 → reactive → 账号自动化
        if (!tc.subOn("battle_shield", "enabled", true)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：总开关 battle_shield.enabled 已关闭", fpid);
            saveState(tc, "总开关已关闭（App 设置页可开）");
            return true;
        }
        if (!tc.subOn("battle_shield", "reactive", true)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：子开关 battle_shield.reactive 已关闭", fpid);
            saveState(tc, "反应式开盾已关闭（App 设置页可开）");
            return true;
        }
        if (!(acc.auto || acc.keep)) {
            clearRisk(r);
            FileLog.info("反应式开盾跳过 fpid={}：账号自动化未开启", fpid);
            saveState(tc, "自动化未开启，未反应式开盾");
            return true;
        }
        String prefix = "WAR".equals(r.kind) ? "被进攻" : "被侦查";
        // 两种模式：immediate（收到即开）/ before_arrival（到达前 N 秒，默认 2、最小 2）。
        boolean immediate = "immediate".equals(shieldMode(tc));
        long etaSec = shieldEtaSec(tc);
        if (!immediate) {
            if (r.endtimeSec > 0) {
                long remain = r.endtimeSec - serverNowSec(engine);
                if (remain > etaSec) {
                    // 挂起：保持 pending，本拍不发请求；到点由 tick 的 etaCheckAtMs 继续推进
                    // （与 onPush/clearRisk 的写入同一把锁，避免旧值覆盖新推送的清零）
                    synchronized (riskLock) {
                        etaCheckAtMs = now + Math.max(200L, (remain - etaSec) * 1000L);
                    }
                    saveState(tc, prefix + " → 等待卡秒：约" + remain + "秒后到达，到达前"
                            + etaSec + "秒开盾");
                    FileLog.info("反应式开盾挂起 fpid={} 事件={}：约{}秒后到达（卡秒{}秒）",
                            fpid, r.kind, String.valueOf(remain), String.valueOf(etaSec));
                    return false;
                }
                if (remain < -EVENT_TTL_SEC) {
                    // 到达已超过保鲜期：为旧攻击补盾无意义（卡秒路径唯一的时间上界）
                    clearRisk(r);
                    FileLog.info("反应式开盾丢弃 fpid={}：威胁已到达超过{}秒",
                            fpid, String.valueOf(EVENT_TTL_SEC));
                    return false;
                }
            } else {
                // 推送未带 endtime：无法卡秒，按保护优先立即开盾并留痕
                FileLog.info("反应式开盾 fpid={} 命中{}风险但缺 endtime，按保护优先立即开盾",
                        fpid, r.kind);
            }
        } else if (now - lastEventAt > EVENT_TTL_MILLIS) {
            // 陈旧事件（让位期积压）：静默丢弃，不为旧攻击补盾（与 master 行为一致）
            clearRisk(r);
            return false;
        }
        // 防骚扰：shield_anti_harass 显式关闭时不设阈值；缺省（未写该键）视为开启，
        // 阈值只读新键 shield_troops_min（2026-11-06 决策：不再读旧 troops_min）。
        boolean antiHarass = tc.subOn("battle_shield", "shield_anti_harass", false)
                || !tc.hasSub("battle_shield", "shield_anti_harass");
        long troopsMin = antiHarass ? shieldTroopsMin(tc) : 0L;
        if (troopsMin > 0) {
            if (r.troops < 0) {
                FileLog.info("反应式开盾 fpid={} 兵力未知，按保护优先照常触发（阈值{}）",
                        fpid, String.valueOf(troopsMin));
            } else if (r.troops < troopsMin) {
                clearRisk(r);
                saveState(tc, prefix + " → 来袭兵力" + r.troops + "低于阈值" + troopsMin + "，未开盾");
                FileLog.info("反应式开盾跳过 fpid={}：来袭兵力{}低于阈值{}",
                        fpid, String.valueOf(r.troops), String.valueOf(troopsMin));
                return true;
            }
        }
        if (now < nextAttemptAt.get()) {   // 幂等（8h 护盾窗）/防风暴冷却（5min）
            clearRisk(r);
            return false;
        }
        if (!busy.compareAndSet(false, true)) return false;
        try {
            openShield(tc, now);
        } finally {
            busy.set(false);
        }
        clearRisk(r);
        return true;
    }

    /**
     * 真正下发开盾请求：已有护盾 → 免费盾 → 背包盾 → 钻石购盾（默认关）。
     * 顺序遵循需求：免费优先、其次背包、最后才花钻石。
     */
    private void openShield(TaskContext tc, long now) {
        if (!shieldLedgerReady) initLedgerFromEngine(tc.engine);
        long nowSec = serverNowSec(tc.engine);
        String prefix = ("WAR".equals(lastEventKind) ? "被进攻" : "被侦查");
        if (shieldActive(tc.engine)) {
            nextAttemptAt.set(now + Math.max(60_000L,
                    (shieldActiveEnd - nowSec) * 1000L));
            saveState(tc, prefix + " → 已有护盾生效中（剩余" + Math.max(0L, shieldActiveEnd - nowSec)
                    + "秒），不重复开");
            writeShieldLedgerState(tc, true);
            return;
        }
        // 1) 免费盾（每日免费 8h；不可用则继续）
        TaskOutcome free;
        try {
            free = ServerTasks.freeShield(tc);
        } catch (Throwable t) {
            free = ServerTasks.classifyException(t, "战斗护盾");
        }
        if (free.category == TaskOutcome.Category.CLAIMED) {
            shieldActiveItem = SHIELD_ITEM_8H;
            shieldActiveEnd = nowSec + 8 * 3600L;
            nextAttemptAt.set(now + 8 * 3600_000L);
            saveState(tc, prefix + " → " + free.message);
            writeShieldLedgerState(tc, true);
            FileLog.info("反应式开盾 fpid={} 事件={} 结果=CLAIMED(免费)：{}",
                    fpid, lastEventKind, free.message);
            return;
        }
        // 2) 背包盾（2h → 8h → 12h，优先最短可用，避免浪费长盾）
        long item = 0L;
        long hours = 0L;
        if (shieldCount(SHIELD_ITEM_2H) > 0L) { item = SHIELD_ITEM_2H; hours = 2L; }
        else if (shieldCount(SHIELD_ITEM_8H) > 0L) { item = SHIELD_ITEM_8H; hours = 8L; }
        else if (shieldCount(SHIELD_ITEM_12H) > 0L) { item = SHIELD_ITEM_12H; hours = 12L; }
        if (item > 0L) {
            int code = useItem(tc, item);
            if (code == 0) {
                shieldBackpack.put(item, Math.max(0L, shieldCount(item) - 1L));
                shieldActiveItem = item;
                shieldActiveEnd = nowSec + hours * 3600L;
                nextAttemptAt.set(now + hours * 3600_000L);
                saveState(tc, prefix + " → 已使用背包 " + shieldName(item) + "（code=0）");
                writeShieldLedgerState(tc, true);
                FileLog.info("反应式开盾 fpid={} 事件={} 来源=背包 item={} code=0",
                        fpid, lastEventKind, String.valueOf(item));
                return;
            }
            FileLog.warn("反应式开盾 fpid={} 背包盾 item={} 使用失败 code={}",
                    fpid, String.valueOf(item), String.valueOf(code));
        }
        // 3) 钻石购盾（默认关；日预算/单次 1 个）
        boolean buyDiamond = tc.subOn("battle_shield", "shield_buy_diamond", false);
        if (buyDiamond) {
            int code = buyAndUseShield(tc, nowSec);
            if (code == 0) {
                shieldActiveItem = SHIELD_ITEM_2H;
                shieldActiveEnd = nowSec + 2 * 3600L;
                nextAttemptAt.set(now + 2 * 3600_000L);
                saveState(tc, prefix + " → 背包无盾，已用 " + DIAMOND_SHIELD_PRICE
                        + " 钻石购买并开启 2 小时护盾（code=0）");
                writeShieldLedgerState(tc, true);
                FileLog.info("反应式开盾 fpid={} 事件={} 来源=钻石 item={} price={} code=0",
                        fpid, lastEventKind, String.valueOf(SHIELD_ITEM_2H),
                        String.valueOf(DIAMOND_SHIELD_PRICE));
                return;
            }
            saveState(tc, prefix + " → 背包无盾，钻石购盾失败 code=" + code
                    + "（不再重试，5 分钟后可再试）");
            nextAttemptAt.set(now + RETRY_COOLDOWN_MILLIS);
            FileLog.warn("反应式开盾 fpid={} 钻石购盾失败 code={}", fpid, String.valueOf(code));
            writeShieldLedgerState(tc, true);
            return;
        }
        // 4) 无可用护盾（保留免费盾失败原因，App 状态可读）
        nextAttemptAt.set(now + RETRY_COOLDOWN_MILLIS);
        saveState(tc, prefix + " → 免费盾不可用（" + free.message + "）、背包无盾，"
                + "且钻石购盾关闭；未开盾");
        writeShieldLedgerState(tc, true);
        FileLog.info("反应式开盾 fpid={} 事件={} 无可用护盾", fpid, lastEventKind);
    }

    /** 使用背包护盾道具（req_use_item{id,count:1,extra:{}}）；返回业务码。 */
    int useItem(TaskContext tc, long itemId) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", itemId);
            args.put("count", 1);
            args.put("extra", new LinkedHashMap<String, Object>());
            GameMessage rsp = tc.engine.request("req_use_item", args, 8);
            return Exec.codeOf(rsp);
        } catch (Throwable t) {
            FileLog.warn("使用护盾道具异常 fpid={} item={}：{}", fpid, String.valueOf(itemId),
                    brief(String.valueOf(t)));
            return -1;
        }
    }

    /** 钻石购盾：先买 2 小时盾入背包，再显式使用；返回最终业务码。 */
    int buyAndUseShield(TaskContext tc, long nowSec) {
        if (!allowDiamondBuy(nowSec)) {
            FileLog.warn("钻石购盾跳过 fpid={}：今日已达上限 {}", fpid, String.valueOf(DIAMOND_BUY_DAILY_MAX));
            return -2;
        }
        int buyCode;
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", SHIELD_ITEM_2H);
            args.put("count", 1);
            args.put("use", false);
            args.put("extra", new LinkedHashMap<String, Object>());
            GameMessage rsp = tc.engine.request("req_shop_quick_buy", args, 10);
            buyCode = Exec.codeOf(rsp);
        } catch (Throwable t) {
            FileLog.warn("钻石购盾请求异常 fpid={}：{}", fpid, brief(String.valueOf(t)));
            return -1;
        }
        if (buyCode != 0) return buyCode;
        shieldBackpack.put(SHIELD_ITEM_2H, shieldCount(SHIELD_ITEM_2H) + 1L);
        recordDiamondBuy(nowSec);
        int useCode = useItem(tc, SHIELD_ITEM_2H);
        if (useCode == 0) {
            shieldBackpack.put(SHIELD_ITEM_2H, Math.max(0L, shieldCount(SHIELD_ITEM_2H) - 1L));
        }
        return useCode;
    }

    boolean allowDiamondBuy(long nowSec) {
        String day = dayKey(nowSec);
        if (!day.equals(diamondBuyDay)) {
            diamondBuyDay = day;
            diamondBuysToday = loadDiamondBuys(day);
        }
        return diamondBuysToday < DIAMOND_BUY_DAILY_MAX;
    }

    void recordDiamondBuy(long nowSec) {
        String day = dayKey(nowSec);
        if (!day.equals(diamondBuyDay)) {
            diamondBuyDay = day;
            diamondBuysToday = loadDiamondBuys(day);
        }
        diamondBuysToday++;
        saveDiamondBuys(day, diamondBuysToday);
    }

    /** 日预算持久化：跨进程/重登录仍生效。 */
    int loadDiamondBuys(String day) {
        try {
            String raw = store.getString(diamondBuyKey(day));
            if (raw == null || raw.isEmpty()) return 0;
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    void saveDiamondBuys(String day, int count) {
        try {
            store.putString(diamondBuyKey(day), String.valueOf(Math.max(0, count)));
            store.flush();
        } catch (Throwable ignored) {
        }
    }

    String diamondBuyKey(String day) {
        return roleUid > 0L
                ? "srv:shield_diamond_buy:" + fpid + ":" + sid + ":" + roleUid + ":" + day
                : "srv:shield_diamond_buy:" + fpid + ":" + sid + ":" + day;
    }

    static String dayKey(long nowSec) {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                .format(new java.util.Date(nowSec * 1000L));
    }

    static String shieldName(long itemId) {
        if (itemId == SHIELD_ITEM_2H) return "2小时护盾";
        if (itemId == SHIELD_ITEM_8H) return "8小时护盾";
        if (itemId == SHIELD_ITEM_12H) return "12小时护盾";
        return "护盾" + itemId;
    }

    // ---------------- 采集极限场景（2026-11-05：抢先/加速/跟随/落位/兜底） ----------------

    /**
     * 采集极限场景主循环：同步我方 state=2/3 行军 → 敌方同点判定 → 到点召回 → 兜底轮询。
     * 返回 true 表示本轮有动作（召回/状态写入）。
     */
    boolean gatherWatchTick(WjdrEngine engine, TaskContext tc, long nowMs) {
        if (tc == null || engine == null) return false;
        if (!tc.subOn("battle_shield", "enabled", true)) return false;
        if (!tc.subOn("battle_shield", "gather_recall", false)) return false;
        // 2026-11-06 决策：不再以"未写 gather_mode"区分新旧路径，统一按新配置
        //（gather_mode 缺省 before_arrival、gather_eta_sec 缺省 2 秒）监控召回。
        long nowSec = serverNowSec(engine);
        syncGatherWatches(engine, nowSec, nowMs);
        boolean acted = false;
        for (GatherWatch w : new ArrayList<>(gatherWatches.values())) {
            if (w == null || w.recalled || w.failed) continue;
            if (w.scheduledAtSec > 0 && nowSec < w.scheduledAtSec) continue;
            if (evaluateGatherWatch(engine, tc, w, nowSec, nowMs)) acted = true;
        }
        long pollSec = gatherPollSec(tc);
        for (GatherWatch w : new ArrayList<>(gatherWatches.values())) {
            if (w == null || w.recalled || w.failed) continue;
            if (pollSec <= 0 || nowMs - w.lastPollMs < pollSec * 1000L) continue;
            w.lastPollMs = nowMs;
            if (pollGatherTile(engine, tc, w, nowSec, nowMs)) acted = true;
            // 每拍最多查一个采集点，避免 6 队同时同步请求阻塞会话线程。
            break;
        }
        return acted;
    }

    /** 从核心资产库同步我方采集行军（state=2 在途 / state=3 采集中）。 */
    void syncGatherWatches(WjdrEngine engine, long nowSec, long nowMs) {
        List<GatherMarch> own = marchSource == null
                ? Collections.<GatherMarch>emptyList() : marchSource.ownGatheringMarches(engine);
        Set<Long> seen = new java.util.HashSet<>();
        for (GatherMarch m : own) {
            if (m == null || m.marchId <= 0 || (m.x <= 0 && m.y <= 0)) continue;
            seen.add(m.marchId);
            GatherWatch w = gatherWatches.get(m.marchId);
            if (w == null) {
                w = new GatherWatch(m.marchId, m.x, m.y, m.state, m.endtimeSec);
                if (m.state == 3L && w.arrivalSec <= 0) w.arrivalSec = nowSec;
                w.aid = m.aid;
                w.lastPollMs = nowMs;   // 新建 watch 不立刻查地图，等一个轮询周期
                gatherWatches.put(m.marchId, w);
            } else {
                if (m.state == 3L && w.state != 3L) {
                    w.arrivalSec = nowSec;
                    w.gatherEndSec = m.endtimeSec;
                }
                if (m.endtimeSec > 0) {
                    if (m.state == 2L) w.arrivalSec = m.endtimeSec;
                    if (m.state == 3L) w.gatherEndSec = m.endtimeSec;
                }
                w.x = m.x;
                w.y = m.y;
                w.state = m.state;
                w.aid = m.aid;
            }
            w.lastSeenMs = nowMs;
        }
        gatherWatches.entrySet().removeIf(en -> nowMs - en.getValue().lastSeenMs > GATHER_WATCH_IDLE_MS);
    }

    /** 单条 watch 判定：立即 / E-N 触发 / 采集结束后不触发。 */
    boolean evaluateGatherWatch(WjdrEngine engine, TaskContext tc, GatherWatch w,
                                long nowSec, long nowMs) {
        EnemyMarch e = findEarliestEnemy(tc, w, nowSec);
        if (e == null) {
            w.scheduledAtSec = 0L;
            w.scheduledEnemyId = 0L;
            return false;
        }
        long E = e.endtimeSec;
        // 采集中且敌方在采集结束后才到：不召回
        if (w.state == 3L && w.gatherEndSec > 0L && E > 0L && E >= w.gatherEndSec) {
            w.scheduledAtSec = 0L;
            w.scheduledEnemyId = 0L;
            return false;
        }
        boolean immediate = "immediate".equals(gatherMode(tc));
        if (immediate || E <= 0L || E <= nowSec) {
            return triggerGatherWatch(engine, tc, w, e,
                    "采集点遭袭：敌方#"+e.marchId+" 已落地/立即模式", nowSec, nowMs);
        }
        long n = gatherEtaSec(tc);
        long at = E - n;
        if (at <= nowSec) {
            return triggerGatherWatch(engine, tc, w, e,
                    "采集点遭袭：敌方#"+e.marchId+" 到达前"+n+"秒", nowSec, nowMs);
        }
        if (w.scheduledAtSec != at || w.scheduledEnemyId != e.marchId) {
            w.scheduledAtSec = at;
            w.scheduledEnemyId = e.marchId;
            String scene = (w.state == 2L && at < w.arrivalSec) ? "场景一（在途召回）" : "场景二（落位后撤矿）";
            saveStateText(tc, "gather", scene + "：敌方#" + e.marchId + " 预计" + (E - nowSec)
                    + "秒后到达 @(" + w.x + "," + w.y + ")，计划到达前" + n + "秒召回");
            FileLog.info("采集极限场景挂起 fpid={} marchId={} enemy={} E={} N={} at={} state={}",
                    fpid, String.valueOf(w.marchId), String.valueOf(e.marchId),
                    String.valueOf(E), String.valueOf(n), String.valueOf(at), String.valueOf(w.state));
        }
        return false;
    }

    /** 找同点、最早到达、未被阈值过滤的敌方行军。 */
    EnemyMarch findEarliestEnemy(TaskContext tc, GatherWatch w, long nowSec) {
        EnemyMarch best = null;
        boolean anti = tc.subOn("battle_shield", "gather_anti_harass", false);
        long threshold = anti ? gatherTroopsMin(tc) : 0L;
        Set<Long> friends = WatchPointEngine.friendAids();
        for (EnemyMarch e : enemyMarches.values()) {
            if (e == null || e.owner <= 0L) continue;
            if (e.x != w.x || e.y != w.y) continue;
            if (e.type != MARCH_WAR && e.type != MARCH_GATHER && e.type != MARCH_ALLIANCE_GATHER) continue;
            if (w.aid > 0L && e.aid > 0L && w.aid == e.aid) continue;   // 同联盟不召回
            if (w.aid > 0L && e.aid > 0L && friends.contains(w.aid)
                    && friends.contains(e.aid)) continue;               // 同阵营友盟不召回
            if (threshold > 0L && e.troops >= 0L && e.troops < threshold) {
                continue;   // 低于防骚扰阈值
            }
            if (e.endtimeSec <= 0L) return e;   // 未知到达：保护优先
            if (best == null || e.endtimeSec < best.endtimeSec) best = e;
        }
        return best;
    }

    /** 执行召回：state=2 在途 recall，state=3 撤矿 off_work；失败最多重试 2 次。 */
    boolean triggerGatherWatch(WjdrEngine engine, TaskContext tc, GatherWatch w, EnemyMarch e,
                               String reason, long nowSec, long nowMs) {
        if (w.recalled) return false;
        if (w.attempts > 0 && nowMs - w.lastAttemptMs < GATHER_WATCH_RETRY_MS) return false;
        w.attempts++;
        w.lastAttemptMs = nowMs;
        String proto = w.state == 2L ? "req_world_march_recall" : "req_world_march_off_work";
        int code;
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", w.marchId);
            GameMessage rsp = engine.request(proto, args, 8);
            code = Exec.codeOf(rsp);
        } catch (Throwable t) {
            code = -1;
            FileLog.warn("采集极限场景召回异常 fpid={} marchId={}：{}",
                    fpid, String.valueOf(w.marchId), brief(String.valueOf(t)));
        }
        if (code == 0) {
            w.recalled = true;
            w.scheduledAtSec = 0L;
            gatherRecalledAt.put(w.marchId, nowMs);
            saveStateText(tc, "gather", reason + " → " + (w.state == 2L ? "已在途召回" : "已撤矿")
                    + " marchId=" + w.marchId + (e == null ? "" : " enemy=" + e.marchId));
            FileLog.info("采集极限场景召回成功 fpid={} marchId={} proto={} enemy={}",
                    fpid, String.valueOf(w.marchId), proto, e == null ? "-" : String.valueOf(e.marchId));
        } else if (w.attempts >= GATHER_WATCH_MAX_ATTEMPTS) {
            w.failed = true;
            saveStateText(tc, "gather", reason + " → 召回失败 code=" + code
                    + "（已重试 " + w.attempts + " 次，停止）marchId=" + w.marchId);
            FileLog.warn("采集极限场景召回失败 fpid={} marchId={} code={} attempts={}",
                    fpid, String.valueOf(w.marchId), String.valueOf(code), String.valueOf(w.attempts));
        } else {
            w.scheduledAtSec = nowSec + GATHER_WATCH_RETRY_MS / 1000L;
            saveStateText(tc, "gather", reason + " → 召回失败 code=" + code
                    + "，60秒后重试 marchId=" + w.marchId);
            FileLog.warn("采集极限场景召回失败待重试 fpid={} marchId={} code={}",
                    fpid, String.valueOf(w.marchId), String.valueOf(code));
        }
        return true;
    }

    /** 兜底：req_world_mapobj 查采集点，march_id 非己方 → 点位已被占据。 */
    boolean pollGatherTile(WjdrEngine engine, TaskContext tc, GatherWatch w,
                           long nowSec, long nowMs) {
        if (w == null || w.x <= 0 || w.y <= 0) return false;
        long kid = engine.lastServerId();
        if (kid <= 0L) return false;
        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", w.x);
            point.put("y", w.y);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("kid", kid);
            args.put("point", point);
            GameMessage rsp = engine.request("req_world_mapobj", args, 8);
            if (rsp == null || Exec.codeOf(rsp) != 0) return false;
            Map<String, Object> obj = Exec.mapOf(rsp.getContent().get("mapobj"));
            if (obj == null) return false;
            if (Exec.longOf(obj.get("type")) != TARGET_RESOURCE) return false;
            Map<String, Object> res = Exec.mapOf(obj.get("map_resource"));
            long mid = res == null ? 0L : Exec.longOf(res.get("march_id"));
            if (mid > 0L && mid != w.marchId) {
                return triggerGatherWatch(engine, tc, w, null,
                        "地图复核：@" + w.x + "," + w.y + " 已被其他行军占据", nowSec, nowMs);
            }
        } catch (Throwable t) {
            FileLog.warn("采集点兜底查询异常 fpid={} @{}:{}：{}",
                    fpid, String.valueOf(w.x), String.valueOf(w.y), brief(String.valueOf(t)));
        }
        return false;
    }

    /** 采集召回模式：immediate / before_arrival（默认 before_arrival）。 */
    String gatherMode(TaskContext tc) {
        String v = tc.strategy("battle_shield", "gather_mode", "");
        return "immediate".equals(v) ? "immediate" : "before_arrival";
    }

    /** 采集召回提前量：gather_eta_sec 优先；默认/非法/小于 2 → 2。 */
    long gatherEtaSec(TaskContext tc) {
        String v = tc.strategy("battle_shield", "gather_eta_sec", "");
        return parseEta(v, "gather_eta_sec");
    }

    long gatherTroopsMin(TaskContext tc) {
        return parseThreshold(tc.strategy("battle_shield", "gather_troops_min", "0"),
                "gather_troops_min");
    }

    long gatherPollSec(TaskContext tc) {
        long v = parseEta(tc.strategy("battle_shield", "gather_watch_poll_sec", "60"),
                "gather_watch_poll_sec");
        return Math.max(10L, v);
    }

    // ---------------- 采集点遭袭（2026-10-24：对齐网页版 GATHERING_ATTACK） ----------------

    /**
     * 处理一次采集点遭袭：写 App 状态提醒；`sub.gather_recall=true` 时对命中的自己采集行军
     * 发 {@code req_world_march_off_work} 撤矿（同 marchId 10 分钟冷却，防重复请求）。
     *
     * <p>语义来源（网页版 wjdr-review，1:1 对齐）：
     * {@code DailyBattleState.appendThreats} 对 type=4/110 且 {@code target.map_resource.uid==ownUid}
     * 的来袭行军，用 endpoint 匹配自己 type=4、state=3 的采集行军；命中后
     * {@code DailyBattleService.handleGatheringThreat} 发 {@code req_world_march_off_work {id}}。
     */
    private boolean handleGather(WjdrEngine engine, GatherAlert g, long now) {
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null || !(acc.auto || acc.keep)) {
            saveStateText(null, "gather", "自动化未开启，未处理采集告警");
            return true;
        }
        TaskContext tc = taskContext(engine, acc);
        if (!tc.subOn("battle_shield", "enabled", true)) {
            saveStateText(tc, "gather", "总开关已关闭（App 设置页可开）");
            return true;
        }
        if (!tc.subOn("battle_shield", "gather_attack", true)) {
            FileLog.info("采集点遭袭跳过 fpid={}：子开关 battle_shield.gather_attack 已关闭", fpid);
            saveStateText(tc, "gather", "采集点遭袭提醒已关闭");
            return true;
        }
        StringBuilder text = new StringBuilder("采集点遭袭");
        if (g.ownResource) text.append("（资源点归属本人）");
        if (g.x > 0 || g.y > 0) text.append(" @(").append(g.x).append(",").append(g.y).append(")");
        if (g.enemyMarchId > 0) text.append(" 敌行军#").append(g.enemyMarchId);
        if (g.ownMarches.isEmpty()) {
            text.append(" · 未定位到在采队伍");
        } else {
            text.append(" · 在采队伍");
            for (GatherMarch m : g.ownMarches) text.append(" #").append(m.marchId);
        }
        // 2026-11-06 决策：采集召回统一走 gatherWatchTick（gather_mode 缺省 before_arrival，
        // 提前量 gather_eta_sec 默认 2 秒）；不再保留"未写新键时收到即撤矿"的旧路径。
        String line = text.toString();
        saveStateText(tc, "gather", line);
        FileLog.info("采集点遭袭 fpid={}：{}", fpid, line);
        return true;
    }

    /** 自己采集行军（type=4、state=2 在途 / state=3 采集中）：生产读核心资产库 QUEUE。 */
    private List<GatherMarch> ownGatheringFromAssetLibrary(WjdrEngine engine) {
        try {
            if (engine == null) return Collections.emptyList();
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready() || lib.queue() == null) return Collections.emptyList();
            List<GatherMarch> out = new ArrayList<>();
            for (AssetSnapshot.QueueMarch m : lib.queue().marches) {
                if (m == null) continue;
                if (m.type != MARCH_GATHER) continue;                    // 只处理个人采集；联盟矿不自动召回
                if (m.state != 2L && m.state != 3L) continue;            // 在途 / 采集中
                if (m.endX <= 0 || m.endY <= 0) continue;
                out.add(new GatherMarch(m.id, (int) m.endX, (int) m.endY,
                        m.state, m.endTime, m.aid));
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** 单测注入采集行军来源（避免依赖资产库 READY）。 */
    void setMarchSourceForTest(MarchSource source) {
        this.marchSource = source;
    }

    /**
     * 从一条 situation 推送中识别「自己的采集点遭袭」。
     * 判定（网页版 appendThreats 同口径）：来袭 type=4/110、目标 {@code target.type=2} 且
     * {@code target.map_resource.uid==本人}；同时用 endpoint 匹配自己正在采集的 type=4/state=3 行军。
     * 仅坐标匹配也接受（资源点 uid 推送缺失时的兜底）。
     */
    GatherAlert gatherAlertOf(Map<String, Object> content, long ownUid, WjdrEngine engine) {
        if (content == null || ownUid <= 0) return null;
        List<GatherMarch> own = marchSource == null
                ? Collections.<GatherMarch>emptyList() : marchSource.ownGatheringMarches(engine);
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            if (type != MARCH_GATHER && type != MARCH_ALLIANCE_GATHER) continue;
            Map<String, Object> target = Exec.mapOf(m.get("target"));
            boolean ownResource = false;
            if (target != null) {
                long targetType = Exec.longOf(target.get("type"));
                Map<String, Object> resource = Exec.mapOf(target.get("map_resource"));
                if (resource != null) {
                    long uid = Exec.longOf(resource.get("uid"));
                    if (uid == ownUid) ownResource = true;
                    if (targetType == 0L && uid > 0) targetType = TARGET_RESOURCE;
                }
                if (targetType == TARGET_RESOURCE && !ownResource) {
                    // 目标类型是资源点但归属未知：仅靠坐标匹配兜底（不因此直接判定本人）
                }
            }
            Map<String, Object> endpoint = firstPoint(m);
            int x = endpoint == null ? 0 : (int) Exec.longOf(endpoint.get("x"));
            int y = endpoint == null ? 0 : (int) Exec.longOf(endpoint.get("y"));
            List<GatherMarch> hit = new ArrayList<>();
            for (GatherMarch gm : own) {
                if (gm.x == x && gm.y == y) hit.add(gm);
            }
            if (!ownResource && hit.isEmpty()) continue;   // 既非本人资源点、也不匹配在采队伍：不是我的采集点
            long endSec = Exec.longOf(m.get("endtime"));
            return new GatherAlert(Exec.longOf(m.get("id")), endSec, x, y, hit, ownResource);
        }
        return null;
    }

    /** 行军 endpoint{x,y} → target.map_city{x,y}（网页版 endpointOf 同口径）。 */
    private static Map<String, Object> firstPoint(Map<String, Object> m) {
        Map<String, Object> p = Exec.mapOf(m.get("endpoint"));
        if (p != null && (Exec.longOf(p.get("x")) != 0 || Exec.longOf(p.get("y")) != 0)) return p;
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null && (Exec.longOf(city.get("x")) != 0 || Exec.longOf(city.get("y")) != 0)) {
                return city;
            }
        }
        return null;
    }

    // ---------------- 强制上线查询（SessionKeeper 非 online 分支用） ----------------

    /**
     * 是否请求 SessionKeeper 强制上线（battle_shield.sub.force_online）：
     * 存在"仍新鲜"的风险（未过期/未到达）且总开关、reactive、force_online 三者均开时 true。
     * 本方法不要求会话 online——它正是给"非 online"分支用的；无风险时恒 false。
     */
    public boolean forceOnlineWanted() {
        Risk r = risk;
        if (r == null) return false;
        VaultStore.Account acc = vault.find(fpid);
        if (acc == null) return false;
        // 只读配置：engine 传 null 安全（TaskContext 的开关/策略读取不触碰 engine）
        TaskContext tc = taskContext(null, acc);
        if (!tc.subOn("battle_shield", "enabled", true)) return false;
        if (!tc.subOn("battle_shield", "reactive", true)) return false;
        if (!tc.subOn("battle_shield", "force_online", false)) return false;
        boolean immediate = "immediate".equals(shieldMode(tc));
        if (r.endtimeSec > 0) {
            long remain = r.endtimeSec - serverNowSec(engineRef);
            // 卡秒模式只对"尚未到达"的风险强制上线；立即模式到达后保鲜窗内仍算新鲜
            return immediate ? remain > -EVENT_TTL_SEC : remain > 0;
        }
        return nowMs.getAsLong() - lastEventAt <= EVENT_TTL_MILLIS;
    }

    /** 清除当前风险（仅当仍是 snapshot 时，避免覆盖同时到达的新推送）。 */
    private void clearRisk(Risk expected) {
        synchronized (riskLock) {
            if (risk == expected) {
                risk = null;
                etaCheckAtMs = 0;
            }
        }
    }

    // ---------------- 时间与配置读取 ----------------

    /** 当前服务器秒：单测注入优先；生产 engine.serverClock() 已校准则用，未校准退化本机秒。 */
    private long serverNowSec(WjdrEngine engine) {
        if (serverNowSecSource != null) return serverNowSecSource.getAsLong();
        WjdrEngine e = engine != null ? engine : engineRef;
        if (e != null) {
            try {
                GameServerClock ck = e.serverClock();
                if (ck != null && ck.locked()) return ck.nowServerSec();
            } catch (Throwable ignored) {
            }
        }
        if (clockWarned.compareAndSet(false, true)) {
            // 未校准：ETA/卡秒按本机秒推算，本机与游戏服务器时钟偏差会直接变成卡秒偏差
            FileLog.warn("反应式开盾 fpid={}：服务器时钟未校准，ETA 卡秒退化为本机秒（偏差≈本机时钟偏差）", fpid);
        }
        return nowMs.getAsLong() / 1000L;
    }

    /** 开盾模式：只读新键 shield_mode；缺省/非法 before_arrival（2026-11-06 决策：不再读旧 eta_sec）。 */
    String shieldMode(TaskContext tc) {
        String v = tc.strategy("battle_shield", "shield_mode", "");
        if ("immediate".equals(v) || "before_arrival".equals(v)) return v;
        return "before_arrival";
    }

    /** 开盾提前量：只读新键 shield_eta_sec；默认/非法/小于 2 → 2（2026-11-06 决策：不再读旧 eta_sec）。 */
    long shieldEtaSec(TaskContext tc) {
        String v = tc.strategy("battle_shield", "shield_eta_sec", "");
        if (!v.isEmpty()) return parseEta(v, "shield_eta_sec");
        return 2L;
    }

    /** 开盾兵力阈值：只读新键 shield_troops_min；默认 0（2026-11-06 决策：不再读旧 troops_min）。 */
    long shieldTroopsMin(TaskContext tc) {
        String v = tc.strategy("battle_shield", "shield_troops_min", "");
        if (!v.isEmpty()) return parseThreshold(v, "shield_troops_min");
        return 0L;
    }

    /** 秒数校验：空/非数字/负数/&lt;2 → 2；&gt;600 → 600；并告警。 */
    static long parseEta(String s, String key) {
        if (s == null) return 2L;
        String t = s.trim();
        if (t.isEmpty()) return 2L;
        long v;
        try {
            v = Long.parseLong(t);
        } catch (Exception e) {
            FileLog.warn("battle_shield.{} 非数字，回退 2：{}", key, t);
            return 2L;
        }
        if (v < 2L) {
            FileLog.warn("battle_shield.{} 小于 2 秒，回退 2：{}", key, String.valueOf(v));
            return 2L;
        }
        if (v > 600L) {
            FileLog.warn("battle_shield.{} 超过 600 秒，夹取 600：{}", key, String.valueOf(v));
            return 600L;
        }
        return v;
    }

    /** 兵力阈值校验：空/非数字/负数 → 0（=不限）；并告警。 */
    static long parseThreshold(String s, String key) {
        if (s == null || s.trim().isEmpty()) return 0L;
        String t = s.trim();
        try {
            long v = Long.parseLong(t);
            if (v < 0L) {
                FileLog.warn("battle_shield.{} 为负数，回退 0：{}", key, t);
                return 0L;
            }
            return v;
        } catch (Exception e) {
            FileLog.warn("battle_shield.{} 非数字，回退 0：{}", key, t);
            return 0L;
        }
    }

/** 写 battle_shield 的 reactive 子项（App FnDetail/今日页同键展示）。 */
    private void saveState(TaskContext tc, String text) {
        saveStateText(tc, "reactive", text);
    }

    /**
     * 写 battle_shield 的指定子项（reactive=开盾 / gather=采集点遭袭）。
     *
     * <p>Phase 2B 键型：roleUid&gt;0 → {@code mod:<fpid>:<sid>:<uid>:battle_shield}；active 角色
     * 额外镜像写旧键 {@code mod:<fpid>:<sid>:battle_shield}（App 显示链路不变）；roleUid&lt;=0
     * 只写旧键（旧构造路径行为完全不变）。读取优先角色键、缺失回退旧键。
     */
    private void saveStateText(TaskContext tc, String subId, String text) {
        try {
            JSONObject mod = readShieldState();
            mod.put(subId, text);
            mod.put(subId + "_ts", System.currentTimeMillis());
            mod.put(subId + "_trg", "反应式");
            writeShieldState(mod);
        } catch (Throwable ignored) {
        }
    }

    /** battle_shield 角色状态键（roleUid<=0 时即旧键）。 */
    String shieldStateKey() {
        return StateKeys.role("mod", new RoleKey(fpid, sid, roleUid), "battle_shield");
    }

    /** battle_shield 旧状态键（App 今日页/FnDetail 读取）。 */
    String shieldStateLegacyKey() {
        return StateKeys.legacy("mod", new RoleKey(fpid, sid, roleUid), "battle_shield");
    }

    /** 读当前状态：角色键优先，缺失回退旧键（惰性迁移读）。 */
    private JSONObject readShieldState() {
        String raw = null;
        if (roleUid > 0L) raw = store.getString(shieldStateKey());
        if (raw == null) raw = store.getString(shieldStateLegacyKey());
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 是否镜像写旧键：仅 roleUid&gt;0 且本角色是 App 当前选中角色。 */
    private boolean mirrorLegacy() {
        if (roleUid <= 0L || activeSupplier == null) return false;
        try {
            return Boolean.TRUE.equals(activeSupplier.get());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 本角色 TaskContext：Phase 2 配置按 roleUid 生效（roleParamsByUid/roleConfigByUid 优先，
     * 回退旧 sid 配置）；roleUid 同时用于任务内的 mod 键型。
     */
    private TaskContext taskContext(WjdrEngine engine, VaultStore.Account acc) {
        return new TaskContext(engine, fpid, sid, store, paramsOf(acc, sid, roleUid), "反应式",
                TaskContext.enabledPredicate(VaultStore.roleConfigOf(acc, sid, roleUid)), roleUid);
    }

    /** 角色任务参数（Phase 2：roleParamsByUid 优先 → 旧 sid 参数）→ TaskContext 形状。 */
    private static Map<String, JSONObject> paramsOf(VaultStore.Account acc, long sid, long roleUid) {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        Map<String, String> raw = VaultStore.roleParamsOf(acc, sid, roleUid);
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                try {
                    params.put(e.getKey(), new JSONObject(e.getValue()));
                } catch (Throwable ignored) {
                }
            }
        }
        return params;
    }

    // ---------------- 推送解析（与 App EnemyWatch.handleMarch 同口径） ----------------

    /**
     * 判定推送是否含"自己城被侦查/进攻"。
     * 返回 "WAR"（有进攻）/ "RECON"（仅有侦查）/ null（不触发）。
     */
    static String classify(Map<String, Object> content, long ownUid) {
        Risk r = riskOf(content, ownUid);
        return r == null ? null : r.kind;
    }

    /**
     * 汇总推送中命中"自己城被侦查/进攻"的行军：kind 严重度优先 WAR；
     * endtime 取多条命中的最早到达（服务器秒，0=未知）；troops 求和（任一未知则整体未知）。
     */
    private static Risk riskOf(Map<String, Object> content, long ownUid) {
        if (content == null || ownUid <= 0) return null;
        boolean hit = false, war = false, recon = false, unknownTroops = false;
        long endSec = 0, troops = 0;
        for (Map<String, Object> m : marchesOf(content)) {
            long type = Exec.longOf(m.get("type"));
            // 1=侦查、2=进攻、100/102=集结进攻（网页版 RALLY_CITY_ATTACK 同按护盾处理）
            if (type != MARCH_RECON && type != MARCH_WAR && !isRallyType(type)) continue;
            Object targetType = m.get("target_type");
            if (targetType != null && Exec.longOf(targetType) != TARGET_CITY) continue;
            if (targetUidOf(m) != ownUid) continue;
            hit = true;
            if (type == MARCH_WAR || isRallyType(type)) war = true;
            else recon = true;
            long e = Exec.longOf(m.get("endtime"));
            if (e > 0 && (endSec == 0 || e < endSec)) endSec = e;
            long t = troopsOf(m);
            if (t < 0) unknownTroops = true;
            else troops += t;
        }
        if (!hit) return null;
        return new Risk(war ? "WAR" : "RECON", endSec, unknownTroops ? -1L : troops);
    }

    /**
     * 单条行军兵力汇总（squad_list.soldier，形状宽容，与 WarNotify.troopsOf 同口径）：
     * map 键=id 值=数量（或 {count:n}）、list 元素={id,count}；未知（无 squad/全 0）返回 -1。
     * 包可见：单测断言阈值口径。
     */
    static long troopsOf(Map<String, Object> m) {
        Map<String, Object> squad = Exec.mapOf(m.get("squad_list"));
        Object soldier = squad == null ? null : squad.get("soldier");
        long total = 0;
        boolean known = false;
        if (soldier instanceof Map) {
            for (Map.Entry<?, ?> en : ((Map<?, ?>) soldier).entrySet()) {
                long id = Exec.longOf(en.getKey());
                Object v = en.getValue();
                long cnt = 0;
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
        return known ? total : -1L;
    }

    /**
     * 收集推送中的行军对象，兼容三种实证形状：
     * 观察哨 {"marches":{"9.0":{"march":{...},"power":0}}} / 列表直挂；
     * 目标系列 {"to_marchs":[{...}]} / {"marchs":[{...}]}；以及 {data:{...}} 包装。
     * 包可见：P7 ServerWarState 复用同一解析（单一事实源）。
     */
    static List<Map<String, Object>> marchesOf(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectMarches(content, out);
        for (String wrap : new String[]{"data", "info"}) {
            Object w = content.get(wrap);
            if (w instanceof Map) collectMarches((Map<String, Object>) w, out);
        }
        return out;
    }

    private static void collectMarches(Map<String, Object> c, List<Map<String, Object>> out) {
        Object marches = c.get("marches");
        if (marches instanceof Map) {
            for (Object v : ((Map<?, ?>) marches).values()) addMarchEntry(v, out);
        } else if (marches instanceof List) {
            for (Object v : (List<?>) marches) addMarchEntry(v, out);
        }
        for (String key : new String[]{"to_marchs", "marchs"}) {
            Object arr = c.get(key);
            if (arr instanceof List) {
                for (Object v : (List<?>) arr) addMarchEntry(v, out);
            }
        }
        // 单数形状 {"march":{...}}（实证 2026-10-15 §2.2 敌进攻发射帧；与 WarNotify 同口径）。
        addMarchEntry(c.get("march"), out);
        // 登录/重连全量快照 notify_world_situation_info：situation_info（Map 键=槽位 或 List）。
        Object info = c.get("situation_info");
        if (info instanceof Map) {
            for (Object v : ((Map<?, ?>) info).values()) addMarchEntry(v, out);
        } else if (info instanceof List) {
            for (Object v : (List<?>) info) addMarchEntry(v, out);
        }
    }

    /** 加入一条行军（兼容 {march:{...}} 包裹），按 id 去重；无 id 的保留（riskOf 仍可能命中）。 */
    private static void addMarchEntry(Object raw, List<Map<String, Object>> out) {
        Map<String, Object> e = Exec.mapOf(raw);
        if (e == null) return;
        Map<String, Object> wrapped = Exec.mapOf(e.get("march"));
        Map<String, Object> march = wrapped != null ? wrapped : e;
        long id = Exec.longOf(march.get("id"));
        if (id <= 0L) {
            out.add(march);
            return;
        }
        for (Map<String, Object> exist : out) {
            if (Exec.longOf(exist.get("id")) == id) return;
        }
        out.add(march);
    }

    /** 守方 uid：atk_city.targetid 优先，退化 target.map_city.uid；取不到返回 0（不触发）。
     *  包可见：P7 ServerWarState 复用同一解析。 */
    static long targetUidOf(Map<String, Object> m) {
        Map<String, Object> atk = Exec.mapOf(m.get("atk_city"));
        if (atk != null) {
            long t = Exec.longOf(atk.get("targetid"));
            if (t > 0) return t;
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null) return Exec.longOf(city.get("uid"));
        }
        return 0;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
