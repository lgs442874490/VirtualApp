package com.carlos.vaultserver;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetDomain;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vaultserver.monitor.TaskTrace;
import com.carlos.vaultserver.task.AssetStateProjector;
import com.carlos.vaultserver.task.RoleKey;
import com.carlos.vaultserver.task.ServerNotifyHistory;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskWakeRouter;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻会话管理器（账号池 M1d 2026-10-21 多角色并行）：每角色一条会话。
 *
 * 每会话独立单线程执行器跑 1 秒节拍（scheduleWithFixedDelay 天然防重入）：
 * offline → 发起登录；online → 心跳；recovering → 到点重连；takeover → 到点探测。
 * 引擎内部的退避/被踢冷却/顶号策略（WjdrEngine）原样生效，与 App 端行为一致。
 *
 * <p><b>会话键 = (fpid, sid, uid)</b>（{@link #sessionKey}）：同一账号（fpid）多角色
 * 可并行在线（隆科多 + 小鹿各持独立引擎/状态/定时器），取消「同 fpid 单角色」互斥。
 * 角色显式控制面 {@link #roleAction}（login/logout/pause/resume/stop）按角色键寻址；
 * 期望态持久化在 {@link VaultStore.RoleEntry}，重启后 {@link #reconcile()} 恢复
 * <b>所有</b> desiredOnline 角色。显式动作优先于账号级 keep。
 *
 * <p>兼容与收敛规则（v4.1.4 不受影响）：
 * <ul>
 *   <li>旧数据/旧 App（无 desiredOnline 条目且 keep=true）：回落 active 角色单会话；</li>
 *   <li>账号级入口（automation 登录 {@link #ensureSession}、sync 切角色）保持
 *       「active 单会话」收敛语义；只有角色级 role-action login 是多角色安全的；</li>
 *   <li>App 消费的采集状态键（StateStore 的 res:/todo:/mod:… 均为 fpid:sid 键型，
 *       /state 契约不变）：同账号多会话时只由 active 角色会话写入（{@link
 *       LiveSession#isActiveRoleWriter}），兄弟角色会话为「占位在线」（连接/心跳/重连
 *       保持，任务执行留给 active 角色），避免互相覆盖。</li>
 * </ul>
 *
 * <p>并发上限 {@link VaultConfig#maxConcurrentSessions} 按<b>角色会话数</b>计数
 * （同账号多角色也各占一个），超限拒绝/跳过并记日志。
 */
public final class SessionKeeper implements SessionControl,
        com.carlos.vaultserver.task.RoundScheduler.KeeperFacade {

    /** 引擎替身工厂（单测注入；生产 null = new WjdrEngine(assets)）。 */
    interface EngineFactory {
        WjdrEngine create();
    }

    private static final long TAKEOVER_PROBE_DEFAULT_MILLIS = 30 * 60_000L; // 对齐 WjdrEngine.TAKEOVER_PROBE_MILLIS
    /** 风险强制上线节流：同一会话两次强制回连至少间隔 60s（防登录风暴与互踢环）。 */
    static final long FORCE_LOGIN_THROTTLE_MS = 60_000L;
    /**
     * 同一在线周期内的风险强制回连次数上限（2026-10-24 审查补强）：若连续抢回仍被顶回，
     * 说明对方设备在持续玩；达到上限后回到「让位/探测」路径，避免与对方形成每 60s 一轮的互踢环。
     * 重新登录在线（STATE_ONLINE）后计数清零。
     */
    static final int MAX_FORCE_LOGIN_ATTEMPTS = 3;

    /** 资产库 state 投影最小写间隔（≤1 次/秒；节流器只写最后一帧）。 */
    private static final long ASSET_PROJECTION_MIN_INTERVAL_MS = 1000L;

    /** Phase 1（2026-10-29）：兄弟角色（非主控）联盟科技捐献节拍，与主控节奏 alliance_donate=30s 对齐。 */
    static final long ROLE_DONATE_INTERVAL_MILLIS = 30_000L;
    /** 与 TaskCadenceScheduler.INIT_GRACE_MILLIS 对齐：登录后 20s 内不派任务（等 init 推送）。 */
    static long ROLE_INIT_GRACE_MILLIS = 20_000L;

    /**
     * 资产库 state 投影调度器（全进程单线程 daemon）：监听回调在 engine inbound 线程上，
     * 不阻塞也不做 IO；节流到期后由本线程把「最新快照」写入 StateStore。
     */
    private static final ScheduledExecutorService ASSET_PROJECTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "asset-projector");
                t.setDaemon(true);
                return t;
            });

    private final VaultStore store;
    private final VaultConfig config;
    private final AssetSource assets;
    private final StateStore stateStore;
    /** notify 推送唤醒路由器（C2）：会话 inbound 引擎钩子 → 命中任务 cadence 置 0。 */
    private final TaskWakeRouter wakeRouter;
    /** 战争通知中枢（可 null：测试/未启用；每个在线角色会话挂引擎钩子）。 */
    private final WarNotify war;
    /** 角色会话表：key = {@link #sessionKey}(fpid,sid,uid)（M1d 多角色并行容器）。 */
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final EngineFactory engineFactory;
    /** true = 会话节拍由测试手动驱动（tickForTest），不起后台线程。 */
    private final boolean manualTick;
    private volatile boolean stopped;
    /** 角色动作串行化锁（P0 规模下全局锁粒度足够，杜绝多角色并发动作竞态）。 */
    private final Object actionLock = new Object();
    /**
     * 编队动作串行化锁（独立于 actionLock）：记忆恢复要逐槽 500ms 发协议（最长数秒），
     * 不能占住角色控制面的全局锁，否则同进程其它角色的 login/stop 会被拖住。
     */
    private final Object formationActionLock = new Object();

    /** 角色会话键：fpid|sid|uid（uid 数字规范化，缺省空串——旧数据无 uid 的会话槽）。 */
    static String sessionKey(String fpid, String sid, String uid) {
        long u = parseUid(uid);
        return fpid + "|" + (sid == null ? "" : sid) + "|" + (u > 0 ? String.valueOf(u) : "");
    }

    /**
     * 角色级任务开关（Phase 1 2026-10-29；与 {@code TaskCadenceScheduler.enabledTaskIds} 同口径的轻量版）：
     * roleConfig 显式值优先，缺省回退任务目录默认。用于兄弟角色按角色跑联盟科技捐献前的门控。
     */
    static boolean roleTaskEnabled(VaultStore.Account acc, long sid, long uid, String fn) {
        if (acc == null || fn == null || fn.isEmpty()) return false;
        // R2 Phase2C：uid 优先（角色独立开关）→ 旧 sid 默认 → 任务目录默认
        Map<String, Boolean> cfg = VaultStore.roleConfigOf(acc, sid, uid);
        Boolean explicit = cfg == null ? null : cfg.get(fn);
        if (explicit != null) return explicit;
        ServerTasks.TaskDef def = ServerTasks.byId(fn);
        return def != null && def.enabledByDefault;
    }

    private static String norm(String s) {
        return s == null ? "" : s;
    }

    public SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore) {
        this(store, config, assets, stateStore, null, false, null);
    }

    /** 战争通知接线构造（Main 用）：war 非 null 时每个在线会话挂敌人监视钩子。 */
    public SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore,
                         WarNotify war) {
        this(store, config, assets, stateStore, null, false, war);
    }

    SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore,
                  EngineFactory engineFactory, boolean manualTick) {
        this(store, config, assets, stateStore, engineFactory, manualTick, null);
    }

    SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore,
                  EngineFactory engineFactory, boolean manualTick, WarNotify war) {
        this.store = store;
        this.config = config;
        this.assets = assets;
        this.stateStore = stateStore;
        this.wakeRouter = new TaskWakeRouter(stateStore);
        this.engineFactory = engineFactory;
        this.manualTick = manualTick;
        this.war = war;
        // 引擎静态读策略 → 账号库当前值（policy API 可在线改）
        WjdrEngine.setTakeoverPolicySource(store::policy);
    }

    // ---------- 登录目标角色绑定（静态：引擎/状态存储经参数注入，JVM 单测可替身直跑） ----------

    /**
     * 登录目标角色绑定（2026-10-20 同服多角色）。
     *
     * <p>两条路径：
     * <ul>
     *   <li><b>activeUid&gt;0</b>（同服多角色，App 明确指定角色）：直接按该 uid 绑定。首次需校验
     *       角色清单（默认角色上线 → req_role_list 确认 (kid,uid) 属于本账号），通过后写缓存
     *       {@code srv:roleuid:<fpid>:<kid>:<uid>}（键含 uid，不与旧 sid 键串味），之后缓存命中
     *       免校验直绑；绑定失败（落地 uid≠期望）清缓存按实际角色上线，绝不静默。校验不过
     *       （uid 过期/角色已删）→ 回退旧发现路径，不硬绑；</li>
     *   <li><b>activeUid 缺省</b>（0/空/非法，旧客户端/旧数据）：{@link #loginBoundRoleLegacy}
     *       原逻辑——sid 缓存单 uid，未命中则默认角色上线 discoverRoleUid 再二次登录。</li>
     * </ul>
     */
    static WjdrEngine.LoginResult loginBoundRole(WjdrEngine engine, String fpid, String token,
                                                 long targetKid, String activeUid,
                                                 StateStore stateStore) throws Exception {
        if (targetKid <= 0) {
            return engine.login(fpid, token, null);
        }
        long wantUid = parseUid(activeUid);
        if (wantUid <= 0) {
            return loginBoundRoleLegacy(engine, fpid, token, targetKid, stateStore);
        }
        String cacheKey = "srv:roleuid:" + fpid + ":" + targetKid + ":" + wantUid;
        if (parseUid(stateStore.getString(cacheKey)) != wantUid) {
            // 首次/缓存被清：默认角色上线拉角色清单，确认该 uid 确实落在目标区服
            engine.login(fpid, token, null);
            if (!roleListHas(engine, targetKid, wantUid)) {
                FileLog.warn("目标角色 uid={} 不在 k{} 角色清单，回退发现路径 fpid={}",
                        String.valueOf(wantUid), String.valueOf(targetKid), fpid);
                engine.initialize();   // 旧连接已认证，回退路径的登录要走全新连接
                return loginBoundRoleLegacy(engine, fpid, token, targetKid, stateStore);
            }
            stateStore.putString(cacheKey, String.valueOf(wantUid));
            stateStore.flush();
            FileLog.info("角色 uid 校验通过 fpid={} k{} uid={}",
                    fpid, String.valueOf(targetKid), String.valueOf(wantUid));
            engine.initialize();   // 二次 loginauth 必须走全新连接（旧已认证连接上再发会超时）
        }
        WjdrEngine.LoginResult r = engine.loginByRole(fpid, token, wantUid);
        if (r.uid != wantUid) {
            // 绑定未生效（角色被删/清单过期）：清缓存按实际角色上线
            FileLog.warn("角色绑定失败 fpid={} 期望uid={} 实际uid={}，清缓存",
                    fpid, String.valueOf(wantUid), String.valueOf(r.uid));
            stateStore.putString(cacheKey, "");
            stateStore.flush();
        }
        return r;
    }

    /**
     * 旧路径（activeUid 缺省）：缓存 srv:roleuid:&lt;fpid&gt;:&lt;sid&gt; 单 uid；
     * 未命中则默认角色上线发现目标角色 uid（同服多角色时只能发现其中一个——
     * 这正是 activeUid 直绑路径存在的原因），再二次登录绑定。
     */
    static WjdrEngine.LoginResult loginBoundRoleLegacy(WjdrEngine engine, String fpid, String token,
                                                       long targetKid, StateStore stateStore) throws Exception {
        String cacheKey = "srv:roleuid:" + fpid + ":" + targetKid;
        String cached = stateStore.getString(cacheKey);
        long roleUid = 0;
        try {
            roleUid = cached == null || cached.isEmpty() ? 0 : Long.parseLong(cached);
        } catch (NumberFormatException ignored) {
        }
        if (roleUid <= 0) {
            engine.login(fpid, token, null);   // 默认角色上线（仅为拉角色清单）
            Long found = engine.discoverRoleUid(targetKid);
            if (found == null || found <= 0) {
                FileLog.warn("目标区服 k{} 无角色，按默认角色上线 fpid={}", targetKid, fpid);
                return engine.login(fpid, token, null);
            }
            roleUid = found;
            stateStore.putString(cacheKey, String.valueOf(roleUid));
            stateStore.flush();
            FileLog.info("发现角色映射 fpid={} k{}→uid={}", fpid, targetKid, roleUid);
            // 二次 loginauth 必须走全新连接（旧已认证连接上再发 loginauth 会超时）
            engine.initialize();
        }
        WjdrEngine.LoginResult r = engine.loginByRole(fpid, token, roleUid);
        if (r.uid != roleUid) {
            // 绑定未生效（角色被删/清单过期）：清缓存按实际角色上线
            FileLog.warn("角色绑定失败 fpid={} 期望uid={} 实际uid={}，清缓存",
                    fpid, String.valueOf(roleUid), String.valueOf(r.uid));
            stateStore.putString(cacheKey, "");
            stateStore.flush();
        }
        return r;
    }

    /** 角色清单里是否存在 (kid, uid) 组合（在线态调用 req_role_list）。 */
    private static boolean roleListHas(WjdrEngine engine, long kid, long uid) {
        try {
            for (WjdrEngine.RoleInfo ri : engine.discoverRoles()) {
                if (ri.serverid == kid && ri.uid == uid) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 解析 uid 字符串；非法/空/≤0 → 0（旧行为）。 */
    static long parseUid(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private final class LiveSession {
        final String fpid;
        /** 会话在 {@link SessionKeeper#sessions} 中的键（构造时定死，= 绑定的角色）。 */
        final String key;
        volatile String token;
        /** 绑定角色 sid（规范化非 null；空串 = 服务器默认角色/旧数据）。 */
        volatile String sid;
        /** 账号库下发的目标角色 uid（0=未提供=旧行为，按 sid 发现）。 */
        volatile long wantUid;
        /** loginBoundRole 的 uid 入参原串（null = 账号库 activeUid，保持旧语义）。 */
        final String uidArg;
        /** 本次登录实际落地的角色 uid（/status 回传 App；App 据此学习 sid→uid 映射）。 */
        volatile long roleUid;
        volatile boolean wantOnline;
        /** 会话级运行态：RUNNING（默认）/ PAUSED（暂停、连接关闭、按恢复策略自动上线）。销毁即 STOPPED，不存于此。 */
        volatile String runState = VaultStore.RUN_RUNNING;
        /** 暂停自动恢复时刻（epoch millis；0=无计划）。PAUSED 态到期触发重新登录（P1 契约 §二.7）。 */
        volatile long pauseResumeAt;
        /** 暂停恢复连续失败次数（冷却结束重置；仅内存，重启后从 0 重数）。 */
        volatile int recoveryAttempts;
        /** 面向 App 的暂停/恢复中文状态（roleRuntime.statusMessage）。 */
        volatile String statusMessage = "";
        /** 让位/顶号探测到期时刻（takeover 倒计时回传 App；0=未知）。 */
        volatile long yieldProbeAt;
        /**
         * 风险强制上线（force_online）最近一次动工时刻：60s 节流，防登录风暴/互踢环。
         * 0=本进程尚未强制过（手动 role-action login 不受此字段限制）。
         */
        volatile long lastForceLoginAt;
        /** 本在线周期内风险强制回连已尝试次数（达到 MAX_FORCE_LOGIN_ATTEMPTS 后回退让位/探测）。 */
        volatile int forceLoginAttempts;
        /** 强制回连预算耗尽日志节流（5 分钟一条，防 tick 刷屏）。 */
        volatile long forceBudgetLogAt;
        final WjdrEngine engine;
        final ScheduledExecutorService exec;
        /** 互助自动响应（登录成功后按真实 sid 惰性挂载）。 */
        volatile ServerHelpResponder help;
        /** 反应式开盾（WAR/RECON 推送驱动；登录成功后按真实 sid 惰性挂载）。 */
        volatile ServerShieldResponder shield;
        /** P7 战争状态对等（世界/联盟行军推送 → WarState 判定；惰性挂载同上）。 */
        volatile ServerWarState war;
        /**
         * 守护盟友 P0（批次9）：推送 → 威胁表 → type=5 驻防/撤防。
         * 默认关（roleParams `guardian` 未配置时 enabled=false/dry_run=true，零请求零状态写入）。
         */
        volatile com.carlos.vaultserver.guardian.ServerGuardianEngine guardian;
        /**
         * 核心资产库（W1）：engine 创建后、login 前 attach，inbound 钩子第一行回灌；
         * 会话断开/关闭时 invalidate + detach。任务侧经 {@link CoreAssetRegistry#get(WjdrEngine)} 读。
         */
        volatile CoreAssetLibrary assetLib;
        /** 资产库变更 → srv:asset 投影守听器（节流 ≤1 次/秒）。 */
        private volatile AssetProjectionPublisher assetPublisher;
        /** 下次热载 p:war.* 参数时刻（60s 一次；配置来自 App 同步的 roleParams）。 */
        volatile long nextWarCfgAt;
        /** 下次全量采集时刻（登录后 3s 首采，之后每 10 分钟）。 */
        volatile long nextCaptureAt;
        /** Phase 1（2026-10-29）：兄弟角色（非主控）下次联盟科技捐献时刻。 */
        volatile long nextRoleDonateAt;
        volatile long stateSince = System.currentTimeMillis();
        volatile String state = WjdrEngine.STATE_OFFLINE;
        /**
         * 真机监控（2026-10-25）：最近一次已上报的引擎状态——同状态重复回调去重，
         * 避免顶号/need_token 的重复 setState 把 session 事件刷屏。
         */
        private volatile String monitorState = "";
        /** 真机监控：本次顶号周期是否已写过 takeover 明细事件（离开 TAKEOVER 后重置）。 */
        private volatile boolean monitorTakeoverLogged;
        /**
         * 本次在线周期开始时刻（C4）：引擎状态进入 {@link WjdrEngine#STATE_ONLINE} 时置 now，
         * 离开 ONLINE（含 recovering/offline/takeover）时清 0。节奏调度据此做登录初始化保护
         * ——刚上线的 20 秒内不派任务，等服务端 init 推送（活动/宝箱/补给/联盟科技等）到达，
         * 避免把「尚未推送」误判成「未同步/未开放」（生产实测推送比建会话晚约 20 秒）。
         */
        volatile long onlineSince;
        /** 正在执行的任务数（整轮/单任务；节奏调度据此避免把任务排队堆积）。 */
        final java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        /** 登录学到的角色元数据（/status 回传 App 角色卡）。 */
        volatile String roleName = "";
        volatile long roleLevel;
        volatile long rolePower;
        volatile String roleAlliance = "";

        LiveSession(VaultStore.Account acc) {
            this(acc, null, null, VaultStore.RUN_RUNNING, 0);
        }

        /** 显式角色绑定（账号池 M1）：sid/uid 非 null 时覆盖账号库 active 值。 */
        LiveSession(VaultStore.Account acc, String roleSid, String roleUid, String initialRunState) {
            this(acc, roleSid, roleUid, initialRunState, 0);
        }

        /** 带暂停恢复计划（P1：reconcile 恢复 PAUSED 条目时带回 pauseResumeAt）。 */
        LiveSession(VaultStore.Account acc, String roleSid, String roleUid, String initialRunState,
                    long initialPauseResumeAt) {
            this.fpid = acc.fpid;
            this.token = acc.token;
            this.sid = norm(roleSid != null ? roleSid : acc.activeSid);
            this.uidArg = roleUid != null ? roleUid : acc.activeUid;
            this.wantUid = parseUid(this.uidArg);
            this.key = sessionKey(fpid, sid, this.uidArg);
            this.wantOnline = true;
            this.runState = VaultStore.RUN_PAUSED.equals(initialRunState)
                    ? VaultStore.RUN_PAUSED : VaultStore.RUN_RUNNING;
            this.pauseResumeAt = Math.max(0, initialPauseResumeAt);
            this.engine = engineFactory != null ? engineFactory.create() : new WjdrEngine(assets);
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-" + fpid
                        + (wantUid > 0 ? "-" + wantUid : "-" + sid));
                t.setDaemon(true);
                return t;
            });
            // W1 资产库：engine 创建后、login 前 attach（登录过程中就会推送 init 帧）
            attachAssetLibrary();
            engine.setStateListener(s -> {
                long nowMs = System.currentTimeMillis();
                state = s;
                stateSince = nowMs;
                // C4：进入 ONLINE 记录本次在线起点；离开清 0（重复收到 ONLINE 不重置起点）
                if (WjdrEngine.STATE_ONLINE.equals(s)) {
                    if (onlineSince <= 0L) onlineSince = nowMs;
                    forceLoginAttempts = 0;   // 重新在线：重置风险强制回连预算
                } else {
                    onlineSince = 0L;
                    // W1：离开 ONLINE（登录中/断线/被顶号/停机）→ 资产置 stale 并投影 ready=false；
                    // 重连后 init 重放会重新 READY（hook 常驻，无需重新 attach）。
                    invalidateAssetLibrary("state=" + s);
                }
                // 引擎自发顶号（code=5）无外部指令时刻可读：按默认探测间隔给 App 倒计时
                if (WjdrEngine.STATE_TAKEOVER.equals(s) && yieldProbeAt == 0) {
                    yieldProbeAt = nowMs + TAKEOVER_PROBE_DEFAULT_MILLIS;
                }
                if (WjdrEngine.STATE_ONLINE.equals(s)) yieldProbeAt = 0;
                // 真机监控（2026-10-25）：状态变化落 session 事件（含 lastFailure/让位策略；
                // 同状态重复回调由 reportStateEvent 去重，不会按 tick 刷屏）。
                reportStateEvent(s);
            });
            // C2 推送唤醒：会话创建即挂载（登录期早到的 init 推送也不丢）
            attachWakeHook();
            // R2 通知历史（批次3 2026-10-23）：与唤醒钩子并列挂同一 inbound 线程，
            // 登录期早到的多实例活动 init 推送也不丢（引擎 per-proto 只留最后一帧）。
            attachNotifyHistoryHook();
            // 编队目录 v2（契约 §3.1/§3.2）：hero/soldier/编队/pet 推送到达即增量刷新并落 state
            attachFormationHook();
            // 真机监控（2026-10-25）：领取/任务域 notify 快照（用户手动登录原生游戏→顶号→重连时对照用）
            attachMonitorNotifyHook();
        }

        // ---------- C2 推送唤醒（notify → 任务 cadence 立即到期） ----------

        /** 唤醒钩子幂等标记（每会话至多一个；引擎 close 后连接不再产生消息，无需解绑）。 */
        private final java.util.concurrent.atomic.AtomicBoolean wakeHookAttached =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * 挂载 inbound 消息钩子 → {@link TaskWakeRouter}：命中 notify 协议时把对应任务的
         * {@code srv:cad:{fpid}:{sid}:{fn}} 写成 0，{@code TaskCadenceScheduler} 下一拍（2s）派单。
         *
         * <p>守卫：会话必须在线（离线/暂停/关闭不写状态）、角色 sid 已落地（登录未完成 skip）、
         * 未知协议零副作用（router 内保证）；钩子异常由引擎捕获，不影响其它监听者。
         */
        private void attachWakeHook() {
            if (!wakeHookAttached.compareAndSet(false, true)) return;
            final WjdrEngine eng = engine;
            final String fp = fpid;
            eng.addMessageHook((proto, content) -> {
                if (!wantOnline || !WjdrEngine.STATE_ONLINE.equals(eng.sessionState())) return;
                long sd = resolveSid();
                if (sd <= 0L) return;   // 登录未落地：sid 未知，跳过（不写任何状态）
                // R2 Phase 2：唤醒键按角色 uid 隔离（同服双角色各自唤醒，不互相覆盖）。
                long uid = roleUid > 0L ? roleUid : wantUid;
                wakeRouter.onNotify(fp, sd, uid, proto);
            });
            FileLog.info("推送唤醒钩子已挂载 {}", key);
        }

        // ---------- R2 通知历史（多实例活动取数；批次3 2026-10-23） ----------

        /** 历史钩子幂等标记（每会话至多一个；引擎 close 后不再产生消息，无需解绑）。 */
        private final java.util.concurrent.atomic.AtomicBoolean historyHookAttached =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * 挂载 inbound 消息钩子 → {@link com.carlos.vaultserver.task.ServerNotifyHistory}。
         *
         * <p>对齐网页版 {@code DailyActivityCalendarService.register()} 的
         * {@code session.observeInbound(...)}（service/DailyActivityCalendarService.java:50-61）与
         * {@code TaskEngine} 逐条 notify 派发（engine/TaskEngine.java:446-483）：每条 inbound 都进
         * 环形历史，任务线程再从历史里取“同协议多实例”（曙光市集 7 档/自选箱多组/卡 BP 多卡）。
         *
         * <p>守卫：会话必须在线（离线/暂停/关闭不写）、账号指纹非空；<b>只按 fpid 存</b>
         * （同账号多角色共用一份历史，sid 不作键，符合需求口径）；allowlist/容量/拷贝上限由
         * {@link com.carlos.vaultserver.task.ServerNotifyHistory} 内部保证（生产 -Xmx256m）。
         * 钩子异常由引擎捕获，不影响其它监听者（wake/战争/互助钩子）。
         */
        private void attachNotifyHistoryHook() {
            if (!historyHookAttached.compareAndSet(false, true)) return;
            final WjdrEngine eng = engine;
            final String fp = fpid;
            eng.addMessageHook((proto, content) -> {
                if (!wantOnline || !WjdrEngine.STATE_ONLINE.equals(eng.sessionState())) return;
                if (fp == null || fp.trim().isEmpty()) return;
                if (!ServerNotifyHistory.isAllowlisted(proto)) return;
                ServerNotifyHistory.record(fp, proto, content);
            });
            FileLog.info("通知历史钩子已挂载 {}", key);
        }

        // ---------- 编队目录 v2（契约 §3.1/§3.2；批次 T2 2026-10-25） ----------

        /** 编队目录钩子幂等标记（每会话至多一个）。 */
        private final java.util.concurrent.atomic.AtomicBoolean formationHookAttached =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 目录 state 落盘节流（推送可能连续到达；≤1 次/5s，实际数据仍即时进内存缓存）。 */
        volatile long lastFormationPersistAt;

        /**
         * 挂载 inbound 钩子 → {@link com.carlos.vaultserver.formation.FormationCatalog#onNotify}。
         *
         * <p>资产库钩子已对每条 inbound 调 onNotify（兵力增量等）；这里只补「init/编队/pet 到达后
         * 立刻把目录快照落 state」，避免登录期目录要等 10 分钟周期采集 App 才看到。
         * 只在 active 写入会话、在线且 sid 已落地时落盘；异常由引擎兜住。
         */
        private void attachFormationHook() {
            if (!formationHookAttached.compareAndSet(false, true)) return;
            final WjdrEngine eng = engine;
            eng.addMessageHook((proto, content) -> {
                if (!wantOnline || !WjdrEngine.STATE_ONLINE.equals(eng.sessionState())) return;
                if (!isFormationCatalogProto(proto)) return;
                long now = System.currentTimeMillis();
                // 节流：≥1 次/5s。但登录期「容量来源」推送（shelter/all_research/家园/buff）常落在
                // hero_init 之后 5s 内被丢掉，导致 App 一直「等待登录容量同步」到下一个 10 分钟周期
                // （生产实测 2026-10-25：capturedAt 停在登录+3s，marchCapacity.initialized=false）。
                // 因此容量未就绪时不节流，保证登录后首次拿到指挥中心/科技数据即刷新目录。
                boolean capacityReady;
                try {
                    capacityReady = com.carlos.vaultserver.formation.MarchCapacity
                            .of(eng).initialized;
                } catch (Throwable t) {
                    capacityReady = false;
                }
                if (capacityReady && now - lastFormationPersistAt < 5_000L) return;
                if (resolveSid() <= 0L) return;
                lastFormationPersistAt = now;
                try {
                    persistFormationCatalog(this);
                } catch (Throwable ignored) {
                }
            });
            FileLog.info("编队目录钩子已挂载 {}", key);
        }

        /** 触发目录落盘的推送集合（兵力增量已有资产库钩子做内存刷新，这里不需要）。 */
        private static boolean isFormationCatalogProto(String proto) {
            return "notify_hero_init".equals(proto) || "notify_soldier_init".equals(proto)
                    || "notify_soldier_formation_update".equals(proto)
                    || "notify_pet_info".equals(proto) || "notify_pet_ex_info".equals(proto)
                    // 容量来源（MarchCapacityCalculator）：到达即刷新，App 不必等 10 分钟周期采集
                    || "notify_shelter_info".equals(proto) || "notify_all_research".equals(proto)
                    || "notify_homeland_info".equals(proto) || "notify_buff_show".equals(proto);
        }

        // ---------- 真机监控（2026-10-25）：会话事件 / 推送快照 ----------

        /**
         * 挂载真机监控的推送快照钩子（TaskTrace 内部按「领取/任务域」白名单过滤、异常自吞、
         * 未启用时零开销）。用户手动登录原生游戏顶号后重连，登录期 init 与领取/任务域
         * notify 都会落到 App 监控页，便于对照「有在跑但游戏里没效果」。
         */
        private void attachMonitorNotifyHook() {
            try {
                TaskTrace.attachNotify(engine, fpid, this::resolveSid);
                FileLog.info("监控推送钩子已挂载 {}", key);
            } catch (Throwable t) {
                FileLog.warn("监控推送钩子挂载失败 {}：{}", key, String.valueOf(t));
            }
        }

        /**
         * 会话事件埋点（真机监控）：sid 用登录落地的真实区服（未落地时回落绑定 sid）。
         *
         * <p>所有监控异常一律吞掉——会话线程（登录/心跳/重连/让位）不能因监控失败受影响。
         *
         * @param event login / login_fail / state / takeover / pause / resume / resume_fail / closed
         */
        private void monitorSession(String event, String msg, JSONObject data) {
            try {
                long sd = resolveSid();
                String sidText = sd > 0L ? String.valueOf(sd) : norm(sid);
                TaskTrace.session(fpid, sidText, event, msg, data);
            } catch (Throwable ignored) {
                // 监控失败不影响会话线程
            }
        }

        /** 让位策略文本（取账号库全局策略，异常回落空串）。 */
        private String safePolicy() {
            try {
                String p = store.policy();
                return p == null ? "" : p;
            } catch (Throwable t) {
                return "";
            }
        }

        /** 已收通知历史条数（登录快照统计；异常按 0）。 */
        private int notifyEntryCount() {
            try {
                return ServerNotifyHistory.entryCount(fpid);
            } catch (Throwable t) {
                return 0;
            }
        }

        /**
         * 引擎状态变化 → session(event="state") 事件：state / engine.lastFailure() / 让位策略 /
         * 探测倒计时。同状态重复回调去重（引擎顶号、need_token 会重复 set 同一状态）。
         */
        private void reportStateEvent(String s) {
            try {
                if (s == null || s.isEmpty() || s.equals(monitorState)) return;
                monitorState = s;
                if (!WjdrEngine.STATE_TAKEOVER.equals(s)) monitorTakeoverLogged = false;
                String failure = orEmpty(engine.lastFailure());
                String policy = safePolicy();
                StringBuilder msg = new StringBuilder("状态→").append(s);
                if (!failure.isEmpty()) msg.append("，原因：").append(failure);
                if (!policy.isEmpty()) msg.append("，让位策略：").append(policy);
                JSONObject data = new JSONObject();
                data.put("state", s);
                data.put("lastFailure", failure);
                data.put("policy", policy);
                data.put("yieldProbeAt", yieldProbeAt);
                data.put("roleUid", roleUid);
                monitorSession("state", msg.toString(), data);
            } catch (Throwable ignored) {
                // 监控失败不影响状态机
            }
        }

        /** 顶号态明细（每次顶号周期只写一条）：让位策略、探测回连倒计时、风险强制回连预算。 */
        private void reportTakeoverOnce() {
            try {
                if (monitorTakeoverLogged) return;
                monitorTakeoverLogged = true;
                String policy = safePolicy();
                JSONObject data = new JSONObject();
                data.put("policy", policy);
                data.put("yieldProbeAt", yieldProbeAt);
                data.put("forceLoginAttempts", forceLoginAttempts);
                data.put("lastFailure", orEmpty(engine.lastFailure()));
                long remainMin = yieldProbeAt > 0L
                        ? Math.max(1L, (yieldProbeAt - System.currentTimeMillis()) / 60_000L) : 0L;
                StringBuilder msg = new StringBuilder("检测到顶号/人工登录（code=5）：按 ")
                        .append(policy.isEmpty() ? "human_first" : policy).append(" 让位");
                msg.append(remainMin > 0L ? "，约 " + remainMin + " 分钟后探测回连" : "，让位探测待定");
                if (forceLoginAttempts > 0) {
                    msg.append("（风险强制回连已 ").append(forceLoginAttempts).append(" 次）");
                }
                monitorSession("takeover", msg.toString(), data);
            } catch (Throwable ignored) {
                // 监控失败不影响让位逻辑
            }
        }

        /** 登录成功快照：昵称/uid/sid/resolveSid/让位策略 + 已收 notify 统计。 */
        private void reportLoginSuccess(WjdrEngine.LoginResult r) {
            try {
                long uid = r == null ? 0L : r.uid;
                long actualSid = r != null && r.serverId > 0 ? r.serverId : resolveSid();
                int notifyCount = notifyEntryCount();
                String policy = safePolicy();
                String msg = "登录成功 昵称=" + orEmpty(roleName)
                        + " uid=" + uid
                        + " sid=" + actualSid
                        + " resolveSid=" + resolveSid()
                        + " 已收通知=" + notifyCount
                        + (policy.isEmpty() ? "" : " 让位策略=" + policy);
                JSONObject data = new JSONObject();
                data.put("uid", uid);
                data.put("sid", actualSid);
                data.put("resolveSid", resolveSid());
                data.put("name", orEmpty(roleName));
                data.put("level", roleLevel);
                data.put("power", rolePower);
                data.put("alliance", orEmpty(roleAlliance));
                data.put("policy", policy);
                data.put("notifyEntries", notifyCount);
                monitorSession("login", msg, data);
            } catch (Throwable ignored) {
                // 监控失败不影响登录
            }
        }

        /** 登录失败快照：异常摘要 + 引擎分类状态/lastFailure（退避中/need_token 等）。 */
        private void reportLoginFailure(Throwable t) {
            try {
                String err = t == null ? "未知异常"
                        : (t.getMessage() == null ? String.valueOf(t) : t.getMessage());
                String stateNow = orEmpty(engine.sessionState());
                String failure = orEmpty(engine.lastFailure());
                JSONObject data = new JSONObject();
                data.put("state", stateNow);
                data.put("lastFailure", failure);
                data.put("policy", safePolicy());
                monitorSession("login_fail",
                        "登录失败：" + err + "（state=" + stateNow
                                + (failure.isEmpty() ? "" : "，" + failure) + "）", data);
            } catch (Throwable ignored) {
                // 监控失败不影响登录失败处理
            }
        }

        // ---------- W1 核心资产库（attach / inbound 回灌 / state 投影 / detach） ----------

        /**
         * 资产库挂载：engine 创建后、login 前 attach 并挂 inbound 钩子。
         *
         * <p>attach 早于 login 的原因：服务器在登录过程中就推送
         * {@code notify_game_init/world_player_init/resource_init/recover_resource_init} 等
         * init 帧（见 {@link WjdrEngine#initialize()} 的 hookNotifies 注释）；晚 attach 会
         * 丢掉 READY 所需的登录批推送。sid 未定时（账号库无 activeSid，默认角色）先用 0
         * attach，投影键用 {@link #resolveSid()} 的实时值（登录落地后即真实 sid）。
         */
        private void attachAssetLibrary() {
            try {
                final CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, fpid,
                        resolveSid(), roleUid > 0 ? roleUid : wantUid, System.currentTimeMillis());
                if (lib == null) return;
                final AssetProjectionPublisher pub = new AssetProjectionPublisher(this, lib);
                engine.addMessageHook((proto, content) -> {
                    lib.accept(proto, content);   // 第一行：先入资产库（异常由库/引擎各自兜住）
                    // 编队目录增量：兵力带走/回城（notify_soldier_update），避免编队库存用登录旧值（401）
                    try {
                        com.carlos.vaultserver.formation.FormationCatalog
                                .onNotify(engine, proto, content);
                    } catch (Throwable ignored) {
                    }
                    // W9：战争/驻防事实迁移——资产库联盟域行军作为第二事实源（与原始推送幂等去重）
                    // Phase2B：按角色实例同步（同服双角色各自一份战争态，互不覆盖）
                    try {
                        long warUid = roleUid > 0L ? roleUid : wantUid;
                        com.carlos.vaultserver.ServerWarState.syncAssets(fpid, resolveSid(), warUid,
                                lib, engine.lastUid());
                    } catch (Throwable ignored) {
                    }
                });
                lib.addListener(pub);
                assetLib = lib;
                assetPublisher = pub;
                FileLog.info("资产库已挂载 {} 库={}", key, lib.key());
            } catch (Throwable t) {
                FileLog.warn("资产库挂载失败 {}：{}", key, String.valueOf(t));
            }
        }

        /** 离开 ONLINE：数据置 stale（保留最近数据供诊断），投影立即反映 ready=false。 */
        private void invalidateAssetLibrary(String reason) {
            CoreAssetLibrary lib = assetLib;
            if (lib == null) return;
            try {
                lib.invalidate(reason);
                AssetProjectionPublisher pub = assetPublisher;
                if (pub != null) pub.onInvalidated(lib.snapshot(), reason);
            } catch (Throwable ignored) {
            }
        }

        /** 会话关闭/销毁：移除监听 → invalidate → 投影末帧 → 从注册表 detach。 */
        private void detachAssetLibrary(String reason) {
            CoreAssetLibrary lib = assetLib;
            AssetProjectionPublisher pub = assetPublisher;
            assetLib = null;
            assetPublisher = null;
            if (lib == null) return;
            try {
                if (pub != null) {
                    lib.removeListener(pub);
                    pub.stop();
                }
                lib.invalidate(reason);
                if (pub != null) pub.publish(lib.snapshot());
                CoreAssetRegistry.global().detach(engine);
                FileLog.info("资产库已卸载 {}：{}", key, reason);
            } catch (Throwable ignored) {
            }
        }

        /**
         * 资产库变更 → StateStore 投影（{@code srv:asset:{fpid}:{sid}}，W1/W4）。
         *
         * <p>线程模型：{@link #onChanged} 在 engine inbound 线程触发；这里只记录最新快照
         * 与 ready 日志，真正写 StateStore 交给静态单线程 {@link #ASSET_PROJECTOR}，
         * 节流 ≤1 次/秒且只写最后一帧（inbound 线程不做 IO、不阻塞协议处理）。
         */
        private final class AssetProjectionPublisher implements CoreAssetLibrary.Listener {
            private final LiveSession session;
            private final CoreAssetLibrary lib;
            private final AtomicBoolean scheduled = new AtomicBoolean(false);
            private volatile AssetSnapshot latest;
            private volatile long lastWriteAtMs;
            private volatile boolean lastReady;
            private volatile boolean stopped;

            AssetProjectionPublisher(LiveSession session, CoreAssetLibrary lib) {
                this.session = session;
                this.lib = lib;
            }

            @Override
            public void onChanged(AssetSnapshot snapshot, Set<AssetDomain> dirtyDomains) {
                if (stopped || snapshot == null) return;
                latest = snapshot;
                if (snapshot.ready && !lastReady) {
                    lastReady = true;
                    FileLog.info("asset_ready fpid={} sid={} version={} missing={}",
                            session.fpid, String.valueOf(session.resolveSid()),
                            String.valueOf(snapshot.version),
                            String.valueOf(snapshot.missingProtocols));
                } else if (!snapshot.ready) {
                    lastReady = false;
                }
                publishThrottled(false);
            }

            /** 离开 ONLINE：立即用 invalidate 后快照覆盖投影（ready=false）。 */
            void onInvalidated(AssetSnapshot snapshot, String reason) {
                if (stopped) return;
                lastReady = false;
                if (snapshot != null) latest = snapshot;
                publishThrottled(true);
            }

            void publishThrottled(boolean force) {
                long now = System.currentTimeMillis();
                if (force || now - lastWriteAtMs >= ASSET_PROJECTION_MIN_INTERVAL_MS) {
                    publish(latest);
                    return;
                }
                if (!scheduled.compareAndSet(false, true)) return;
                long delay = Math.max(1L, ASSET_PROJECTION_MIN_INTERVAL_MS - (now - lastWriteAtMs));
                try {
                    ASSET_PROJECTOR.schedule(() -> {
                        scheduled.set(false);
                        if (!stopped) publishThrottled(true);
                    }, delay, TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    scheduled.set(false);
                }
            }

            /** 立即写一帧（detach 路径的末帧也走这里；StateStore 写入为轻量内存操作）。 */
            void publish(AssetSnapshot snapshot) {
                AssetSnapshot s = snapshot != null ? snapshot : latest;
                if (s == null) return;
                try {
                    long sid = session.resolveSid();
                    if (sid <= 0L) return;   // 登录未落地（默认角色会话）：不写 srv:asset:fpid:0 垃圾键
                    long uid = s.key == null ? 0L : s.key.uid;
                    // 新契约：按 uid 分键，同服多角色互不覆盖（App 资产中心按当前角色 uid 读取）
                    if (uid > 0L) {
                        stateStore.putString(AssetStateProjector.stateKey(session.fpid, sid, uid),
                                AssetStateProjector.toJson(s, session.fpid, sid));
                    }
                    // 旧契约 srv:asset:{fpid}:{sid}：仅活跃角色写入，供旧 App/采集设置页兼容
                    if (session.isActiveRoleWriter()) {
                        stateStore.putString(AssetStateProjector.stateKey(session.fpid, sid),
                                AssetStateProjector.toJson(s, session.fpid, sid));
                    }
                    lastWriteAtMs = System.currentTimeMillis();
                } catch (Throwable ignored) {
                }
            }

            void stop() {
                stopped = true;
            }
        }

        void start() {
            if (manualTick) return;   // 测试模式：tickForTest 手动驱动
            exec.scheduleWithFixedDelay(() -> {
                try {
                    tick();
                } catch (Throwable t) {
                    FileLog.warn("session tick 异常 fpid={}：{}", fpid, String.valueOf(t));
                }
            }, 200, 1000, TimeUnit.MILLISECONDS);
        }

        void tick() {
            if (!wantOnline) return;
            // 暂停（P1 升级）：连接已关闭，仅按恢复策略到点自动上线；不做心跳/任务。
            // 风险强制上线（force_online）：暂停期间收到并挂起的新鲜风险（攻击开盾/盟友驻防）
            // 可越过暂停直接恢复——这是用户显式配置的例外，仍受 60s 节流保护。
            if (VaultStore.RUN_PAUSED.equals(runState)) {
                String risk = riskForceOnlineReason();
                if (risk != null) {
                    resumeForRisk(risk);
                    return;
                }
                pauseRecoveryTick();
                return;
            }
            String st = engine.sessionState();
            switch (st) {
                case WjdrEngine.STATE_OFFLINE:
                    startLogin();
                    break;
                case WjdrEngine.STATE_ONLINE:
                    engine.heartbeatTick();
                    // 战争监视钩子（所有角色会话无条件挂载，不受 active 写入方限制）：
                    // 联盟态势推送只会到达"在该联盟视野内"的角色会话，兄弟角色会话也必须监听，
                    // 否则会出现「目标一直在打人但没有任何通知」。attachWarHook 幂等（每会话至多一个）。
                    attachWarHook();
                    // 互助响应器：所有在线角色会话各自挂载（Phase 1 起状态键按角色 uid 隔离）。
                    ensureHelp(engine);
                    // R2 Phase 2（2026-10-29）：VAULT_PER_ROLE=on 时所有在线角色各自跑整轮/节奏/采集；
                    // off（默认）保持现网“只 active 角色执行、兄弟角色占位在线 + 联盟互助/捐献”行为。
                    boolean active = isActiveRoleWriter();
                    boolean perRole = com.carlos.vaultserver.task.RoundScheduler.perRoleEnabled();
                    if (!perRole && !active) {
                        ServerHelpResponder siblingHelp = help;
                        if (siblingHelp != null) siblingHelp.tick(engine);
                        roleScopedDonateTick(engine);
                        break;
                    }
                    long uidForRole = roleUid > 0L ? roleUid : wantUid;
                    // R2 Phase 2B：开盾按角色独立（每个角色自己城市受攻击各自开盾；键带 uid，
                    // 主控镜像 App 旧键）；perRole=off 时仍只主控角色，保持现网行为。
                    if (perRole || active) {
                        // 开盾惰性挂载（按真实 sid + 角色 uid；主控角色双写 App 旧键）
                        if (shield == null) {
                            shield = new ServerShieldResponder(fpid, resolveSid(), uidForRole,
                                    stateStore, store, this::isActiveRoleWriter);
                            shield.attach(engine);
                        }
                        // 反应式开盾优先于采集/互助：攻击行军按分钟计，开盾早一秒是一秒
                        ServerShieldResponder s = shield;
                        if (s != null) s.tick(engine);
                    }
                    // 战争态（WARTIME 让位）：用户口径（2026-10-29）——**只有开启守护盟友且实盘
                    // （enabled && !dry_run，需要用行军队列去驻防盟友）的账号才触发**，且只在守护宿主
                    // （主控角色）上实例化；未开守护/演练/兄弟角色一律不进入战争态，避免被联盟战场
                    // 推送无谓地推入战时让位、把日常任务停掉。
                    boolean warWanted = active
                            && com.carlos.vaultserver.guardian.ServerGuardianEngine.queueDefenseActive(
                                    store.find(fpid), resolveSid(), uidForRole);
                    if (warWanted) {
                        if (war == null) {
                            war = ServerWarState.forRole(fpid, resolveSid(), uidForRole);
                            war.attach(engine);
                            FileLog.info("战争态实例挂载 role={}（守护盟友实盘，需要队列驻防）", key);
                        }
                        long nowMs = System.currentTimeMillis();
                        ServerWarState w = war;
                        if (w != null) {
                            if (nowMs >= nextWarCfgAt) {
                                VaultStore.Account warAcc = store.find(fpid);
                                if (warAcc != null) {
                                    ServerWarState.configure(warAcc, resolveSid(), uidForRole);
                                }
                                nextWarCfgAt = nowMs + 60_000L;
                            }
                            w.tick(nowMs);
                        }
                    } else if (war != null) {
                        // 守护未开启/演练/非宿主：注销战争态实例，任务不再被战时让位（可随时再开）
                        ServerWarState w = war;
                        war = null;
                        try {
                            ServerWarState.unregister(fpid, resolveSid(), uidForRole);
                        } catch (Throwable ignored) {
                        }
                        FileLog.info("战争态实例注销 role={}（守护盟友未实盘开启/非宿主，不触发战争态）",
                                key);
                    }
                    // 守护盟友 P0（批次9）：账号级单实例（同一联盟战场只驻防一次），仅主控角色运行；
                    // 状态键按角色 uid（主控镜像旧键，App 展示不变）。
                    if (active) {
                        if (guardian == null) {
                            guardian = new com.carlos.vaultserver.guardian.ServerGuardianEngine(
                                    fpid, resolveSid(), uidForRole, stateStore, store,
                                    this::isActiveRoleWriter);
                            guardian.attach(engine);
                        }
                        com.carlos.vaultserver.guardian.ServerGuardianEngine g = guardian;
                        if (g != null) g.tick(engine);
                    }
                    long now = System.currentTimeMillis();
                    if (now >= nextCaptureAt) {
                        long captureSid = resolveSid();
                        // Phase 2：按角色 uid 采集（active 角色镜像旧键，App 契约不变）
                        String err = CaptureTasks.captureAll(engine, stateStore, fpid, captureSid,
                                uidForRole, active);
                        if (err != null) FileLog.warn("采集失败 fpid={} role={}：{}", fpid,
                                String.valueOf(uidForRole), err);
                        // 统一编队目录 v2（契约 §3.1/§3.2）：与周期采集一起刷新（只读、幂等），
                        // 带 uid 新键 + 旧键双写；空快照不覆盖好目录（审计 P0-6）。
                        persistFormationCatalog(this);
                        // 角色错峰：两个角色采集周期错开（按 uid 取 0-59s 偏移），避免同秒请求尖峰
                        long jitter = uidForRole > 0L ? (uidForRole % 60L) * 1000L : 0L;
                        nextCaptureAt = System.currentTimeMillis()
                                + CaptureTasks.CAPTURE_INTERVAL_MILLIS + jitter;
                        stateStore.flush();
                    }
                    // 互助响应（状态键按角色 uid 隔离；主控角色双写 App 旧键；开盾后执行保持原优先级）
                    ServerHelpResponder h = help;
                    if (h != null) h.tick(engine);
                    break;
                case WjdrEngine.STATE_RECOVERING:
                    // 风险强制上线：跳过退避立即重连（节流 60s；无风险时维持原退避）
                    forceReconnectForRisk("断线恢复中");
                    engine.tryReconnect();
                    break;
                case WjdrEngine.STATE_TAKEOVER: {
                    // 真机监控：顶号让位/探测说明（每次顶号周期只落一条，避免每秒刷事件）
                    reportTakeoverOnce();
                    // 风险强制上线：被顶号后若仍有新鲜风险，允许按配置抢回连接（会顶掉其他设备）
                    if (forceReconnectForRisk("被顶号让位")) break;
                    if (engine.takeoverProbeDue(System.currentTimeMillis())) {
                        engine.beginRecoveryNow("让位探测到期，回连");
                    }
                    break;
                }
                default:
                    // logging：登录进行中；need_token：等 token 补给（token API 触发重启）
            }
        }

        /**
         * 互助响应器惰性挂载（Phase 1 2026-10-29）：所有在线角色会话各自挂载，
         * 状态键按角色 uid 隔离（{@link ServerHelpResponder} 内部处理），
         * 主控角色额外双写 App 今日页读取的旧键。挂载失败只记日志，不影响会话主流程。
         */
        private void ensureHelp(WjdrEngine engine) {
            if (help != null) return;
            try {
                help = new ServerHelpResponder(fpid, resolveSid(), roleUid, stateStore,
                        this::isActiveRoleWriter);
                help.attach(engine);
            } catch (Throwable t) {
                FileLog.warn("互助响应器挂载失败 {}：{}", key, String.valueOf(t));
            }
        }

        /**
         * 非主控角色（兄弟会话）的联盟科技捐献节拍（Phase 1 2026-10-29）：
         * 主控角色仍由 {@link com.carlos.vaultserver.task.TaskCadenceScheduler} 以旧键路径派单；
         * 兄弟角色按角色 uid 键独立捐献，使同账号多角色的免费科技捐献次数都能被消耗
         * （修复「非主控角色免费次数只涨不花，游戏内长期 24/25」）。
         *
         * <p>与节奏调度同款安全闸：登录初始化保护、账号 auto 开关、角色任务开关、战时让位；
         * 异常吞掉只记日志，绝不影响兄弟会话的心跳/重连。
         */
        private void roleScopedDonateTick(WjdrEngine engine) {
            // Phase 2（VAULT_PER_ROLE=on）：联盟科技捐献由节奏调度按角色派单（每人一份 cad 键），
            // 这里不再重复执行，避免同一角色一轮内被捐两次。
            if (com.carlos.vaultserver.task.RoundScheduler.perRoleEnabled()) return;
            if (roleUid <= 0L) return;                       // 角色身份未落地：下一拍再试
            long now = System.currentTimeMillis();
            if (now < nextRoleDonateAt) return;
            nextRoleDonateAt = now + ROLE_DONATE_INTERVAL_MILLIS;
            // C4 登录初始化保护：刚上线的 20s 内服务端关键 init 推送（含 recover_resource）可能还没到
            if (onlineSince <= 0L || now - onlineSince < ROLE_INIT_GRACE_MILLIS) return;
            VaultStore.Account acc = store.find(fpid);
            if (acc == null || !acc.auto) return;            // 未开启自动：保持手动语义
            long sidNow = resolveSid();
            if (sidNow <= 0L) return;
            if (!roleTaskEnabled(acc, sidNow, roleUid, "alliance_donate")) return;
            if (SessionKeeper.this.wartimeYield(fpid)) return;   // P7 战时让位
            try {
                TaskContext tc = new TaskContext(engine, fpid, sidNow, stateStore,
                        java.util.Collections.emptyMap(), "角色", null, roleUid);
                ServerTasks.runAllianceDonate(tc);
            } catch (Throwable t) {
                FileLog.warn("兄弟角色科技捐献异常 {}：{}", key, String.valueOf(t));
            }
        }

        /**
         * 风险强制上线原因：本会话已收到并仍新鲜的风险，且对应模块配置允许 force_online 时
         * 返回中文原因（"攻击风险"/"盟友受袭风险"）；否则 null。
         *
         * <p>只有此前在线时才可能收到推送，因此 shield/guardian 非空即代表本进程知悉风险；
         * 完全离线（无会话）没有任何游戏推送通道，不在此能力范围（见可行性文档）。
         */
        private String riskForceOnlineReason() {
            try {
                ServerShieldResponder s = shield;
                if (s != null && s.forceOnlineWanted()) return "自身城受攻击风险";
            } catch (Throwable ignored) {
            }
            try {
                com.carlos.vaultserver.guardian.ServerGuardianEngine g = guardian;
                if (g != null && g.forceOnlineWanted()) return "盟友受攻击风险";
            } catch (Throwable ignored) {
            }
            return null;
        }

        /**
         * 非 online 分支的风险强制回连（takeover/recovering）：命中配置且有新鲜风险时调用
         * {@link WjdrEngine#beginRecoveryNow(String)} 跳过退避/让位探测；60s 节流防互踢环。
         *
         * @return true=本次已发起强制回连（调用方不要再走原探测逻辑）
         */
        private boolean forceReconnectForRisk(String scene) {
            String risk = riskForceOnlineReason();
            if (risk == null) return false;
            if (forceLoginBudgetExhausted()) {
                // 连续抢回仍被顶回：回退「让位/探测」，不再参与互踢；探测到期后照常回连
                if (yieldProbeAt <= 0) yieldProbeAt = System.currentTimeMillis() + TAKEOVER_PROBE_DEFAULT_MILLIS;
                return false;
            }
            long now = System.currentTimeMillis();
            long last = lastForceLoginAt;
            if (last > 0 && now - last < FORCE_LOGIN_THROTTLE_MS) return true;  // 节流窗内：视为已处理
            lastForceLoginAt = now;
            forceLoginAttempts++;
            yieldProbeAt = 0;
            statusMessage = "风险强制上线（" + risk + "）";
            engine.beginRecoveryNow("风险强制上线：" + risk + "（" + scene + "）");
            FileLog.info("风险强制上线 {}：{}（{}，第{}/{}次，节流 {}s）", key, risk, scene,
                    String.valueOf(forceLoginAttempts), String.valueOf(MAX_FORCE_LOGIN_ATTEMPTS),
                    String.valueOf(FORCE_LOGIN_THROTTLE_MS / 1000L));
            return true;
        }

        /** 暂停态风险强制恢复：清恢复计划、转 RUNNING；下一拍 OFFLINE → startLogin。 */
        private void resumeForRisk(String risk) {
            if (!desiredRoleInStore()) return;   // 已被显式停止/停用：不违背用户意图
            if (forceLoginBudgetExhausted()) return;
            long now = System.currentTimeMillis();
            long last = lastForceLoginAt;
            if (last > 0 && now - last < FORCE_LOGIN_THROTTLE_MS) return;
            lastForceLoginAt = now;
            forceLoginAttempts++;
            runState = VaultStore.RUN_RUNNING;
            pauseResumeAt = 0;
            recoveryAttempts = 0;
            statusMessage = "风险强制上线（" + risk + "）";
            store.clearRolePause(fpid, norm(sid), uidArg == null ? "" : uidArg);
            FileLog.info("风险强制上线：恢复暂停会话 {}（{}，第{}/{}次）", key, risk,
                    String.valueOf(forceLoginAttempts), String.valueOf(MAX_FORCE_LOGIN_ATTEMPTS));
            // 真机监控：恢复事件（风险强制上线越过暂停）
            JSONObject data = new JSONObject();
            data.put("runState", VaultStore.RUN_RUNNING);
            data.put("reason", risk);
            monitorSession("resume", "风险强制上线恢复暂停会话（" + risk + "）", data);
        }

        /**
         * 风险强制回连预算是否耗尽（同一在线周期最多 {@link #MAX_FORCE_LOGIN_ATTEMPTS} 次）。
         * 耗尽后回退让位/探测路径；日志 5 分钟节流防 tick 刷屏。
         */
        private boolean forceLoginBudgetExhausted() {
            if (forceLoginAttempts < MAX_FORCE_LOGIN_ATTEMPTS) return false;
            long now = System.currentTimeMillis();
            if (now - forceBudgetLogAt > 5 * 60_000L) {
                forceBudgetLogAt = now;
                FileLog.warn("风险强制上线 {}：本在线周期已连续抢回 {} 次仍被顶回，"
                                + "暂停强制回连（改走让位/探测，避免互踢环）",
                        key, String.valueOf(forceLoginAttempts));
            }
            return true;
        }

        /** 战争监视钩子（幂等，每会话至多一个）；推送线程异常由 WarNotify.onPush 自兜。 */
        private final java.util.concurrent.atomic.AtomicBoolean warHookAttached =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private void attachWarHook() {
            if (!warHookAttached.compareAndSet(false, true)) return;
            WarNotify w = SessionKeeper.this.war;   // 外部 WarNotify（LiveSession.war 是 gwar 的 ServerWarState，不可混用）
            if (w == null) return;
            String fp = fpid;
            long sd = resolveSid();
            engine.addMessageHook((proto, content) -> w.onPush(fp, sd, proto, content));
            FileLog.info("战争监视钩子已挂载 {}", key);
        }

        /**
         * 本会话是否为该账号 state 键的写入方：App 消费键均为 fpid:sid 键型（无 uid），
         * 同账号多会话时只有「== 账号 active 角色」的会话写采集/互助/开盾，
         * 其余角色会话跳过（M1d 占位在线；后续如按 uid 拆 state 键可放开全角色任务）。
         */
        boolean isActiveRoleWriter() {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null) return false;
            String aSid = norm(acc.activeSid);
            if (!aSid.isEmpty() && !aSid.equals(norm(sid))) return false;
            long aUid = parseUid(acc.activeUid);
            if (aUid == 0) return true;   // 旧数据无 uid 概念：该 fpid 只此一条会话槽
            long mine = roleUid > 0 ? roleUid : wantUid;
            return mine == 0 || mine == aUid;
        }

        /** 采集/互助用的角色 sid：activeSid 缺省时取登录返回的实际区服。 */
        long resolveSid() {
            String s = sid;
            if (s == null || s.isEmpty()) {
                long actual = engine.lastServerId();
                return actual > 0 ? actual : 0L;
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        /**
         * 按角色登录（2026-10-09 实证选角参数=角色 uid）：
         * 有 activeSid 时——缓存命中直接绑定；未命中则先默认角色上线发现目标角色 uid
         * （缓存 srv:roleuid:fpid:sid）再二次登录绑定。绑定失败按实际角色上线，
         * 由 verifyIdentity 公示真实身份，绝不静默。
         *
         * <p><b>同服多角色</b>（2026-10-20）：账号库带 activeUid>0 时说明 App 已明确
         * 当前角色（sid 相同 uid 不同），此时旧「sid→单 uid」缓存无法表达第二个角色，
         * 改走 {@link SessionKeeper#loginBoundRole} 的 uid 直绑路径；activeUid 缺省时
         * 行为与旧版完全一致。
         */
        WjdrEngine.LoginResult loginBoundRole(VaultStore.Account acc) throws Exception {
            return SessionKeeper.loginBoundRole(engine, fpid, acc.token, resolveSid(), uidArg, stateStore);
        }

        /**
         * 登录身份核验：拉真实昵称/kid/联盟/熔炉存档；uid 与上次不一致即告警串号
         * （2026-10-09 事故：fpid 名下挂了别的号的 token，数据采集错账号多日无人发现）。
         */
        void verifyIdentity(long uid) {
            try {
                com.carlos.vault.wjdr.protocol.GameMessage pbi = engine.request(
                        "req_players_base_info",
                        com.carlos.vaultserver.task.Exec.args("uids",
                                java.util.Collections.singletonList(uid)));
                Object result = pbi == null || pbi.getContent() == null
                        ? null : pbi.getContent().get("result");
                String nick = "", alliance = "", kid = "", stove = "";
                if (result instanceof Map) {
                    for (Object v : ((Map<?, ?>) result).values()) {
                        Map<String, Object> p = com.carlos.vaultserver.task.Exec.mapOf(v);
                        if (p == null) continue;
                        nick = String.valueOf(p.getOrDefault("nickName", ""));
                        kid = String.valueOf(p.getOrDefault("kid", ""));
                        stove = String.valueOf(p.getOrDefault("stove_lv", ""));
                        Map<String, Object> all = com.carlos.vaultserver.task.Exec.mapOf(p.get("alliance"));
                        alliance = all == null ? "" : String.valueOf(all.getOrDefault("abbr", ""));
                        break;
                    }
                }
                String identity = nick + "@k" + kid
                        + (alliance.isEmpty() ? "" : "(" + alliance + ")") + " 熔炉" + stove;
                // 回传 App 角色卡用（名称/等级/联盟；战力 base_info 未必有，取到就带）
                if (!nick.isEmpty()) roleName = nick;
                if (!alliance.isEmpty()) roleAlliance = alliance;
                try {
                    roleLevel = (long) Double.parseDouble(stove);
                } catch (Throwable ignored) {
                }
                try {
                    Object pw = result instanceof Map
                            ? ((Map<?, ?>) result).values().stream().findFirst().orElse(null) : null;
                    Map<String, Object> pm = com.carlos.vaultserver.task.Exec.mapOf(pw);
                    if (pm != null && pm.get("power") != null) {
                        rolePower = (long) Double.parseDouble(String.valueOf(pm.get("power")));
                    }
                } catch (Throwable ignored) {
                }
                // 身份键带 uid（M1d 同 fpid 多角色并行）：同区服多角色各有独立身份记录，
                // 避免共享 sid 键导致兄弟角色登录被误报「token 串号」
                String idKey = "srv:uid:" + fpid + ":" + resolveSid() + ":" + uid;
                String idnKey = "srv:identity:" + fpid + ":" + resolveSid() + ":" + uid;
                String prevUid = stateStore.getString(idKey);
                String prevIdentity = stateStore.getString(idnKey);
                if (prevUid != null && !prevUid.isEmpty() && !prevUid.equals(String.valueOf(uid))) {
                    FileLog.warn("⚠️token串号嫌疑 fpid={} uid变化 {}→{} 现身份[{}] 前身份[{}]——请核对token与号ID映射",
                            fpid, prevUid, String.valueOf(uid), identity, String.valueOf(prevIdentity));
                }
                stateStore.putString(idKey, String.valueOf(uid));
                stateStore.putString(idnKey, identity);
                stateStore.flush();
                FileLog.info("登录身份 fpid={} uid={} {}", fpid, String.valueOf(uid), identity);
                // 角色清单：一个 token 多角色是常态，登录落错角色曾导致整库数据采错账号
                try {
                    com.carlos.vault.wjdr.protocol.GameMessage rl = engine.request("req_role_list", null);
                    String raw = rl == null || rl.getContent() == null ? "空"
                            : String.valueOf(rl.getContent());
                    FileLog.info("角色清单 fpid={} {}", fpid,
                            raw.substring(0, Math.min(raw.length(), 900)));
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                FileLog.info("身份核验未完成 fpid={}：{}", fpid, String.valueOf(t));
            }
        }

        /**
         * 暂停自动恢复节拍（P1 契约 §二.7）：PAUSED 且 pauseResumeAt 到期且 desiredOnline
         * → 重新登录；成功转 RUNNING 并清计划；失败按恢复策略排下次尝试
         * （重试间隔 → 连续尝试达上限进冷却，冷却结束重置计数再试）。
         */
        void pauseRecoveryTick() {
            if (pauseResumeAt <= 0) return;                     // 无计划（如恢复关闭/0 次尝试）：保持离线
            long now = System.currentTimeMillis();
            if (now < pauseResumeAt) return;
            VaultStore.Account acc = store.find(fpid);
            if (acc == null || !desiredRoleInStore()) return;   // 期望被撤（sync/stop）：不自动恢复
            if (!resumeInFlight.compareAndSet(false, true)) return;
            try {
                attemptResumeLogin(acc);
            } finally {
                resumeInFlight.set(false);
            }
        }

        /** 库内该角色仍 desiredOnline（暂停期间 sync 收敛/显式停止会撤销期望）。 */
        private boolean desiredRoleInStore() {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null) return false;
            VaultStore.RoleEntry e = acc.roles.get(VaultStore.roleKey(norm(sid), uidArg == null ? "" : uidArg));
            return e != null && e.desiredOnline;
        }

        /** 到点重登一次并按恢复策略处理结果（会话节拍线程上同步执行，与 startLogin 同栈）。 */
        private void attemptResumeLogin(VaultStore.Account acc) {
            FileLog.info("暂停到期，尝试自动上线 {}", key);
            startLogin();
            String st = engine.sessionState();
            if (WjdrEngine.STATE_ONLINE.equals(st)) {
                runState = VaultStore.RUN_RUNNING;
                pauseResumeAt = 0;
                recoveryAttempts = 0;
                statusMessage = "";
                store.clearRolePause(fpid, norm(sid), uidArg == null ? "" : uidArg);
                FileLog.info("暂停恢复成功 {}（RUNNING，清除恢复计划）", key);
                // 真机监控：自动恢复成功
                JSONObject data = new JSONObject();
                data.put("runState", VaultStore.RUN_RUNNING);
                data.put("pauseResumeAt", 0L);
                monitorSession("resume", "暂停到期自动上线成功（RUNNING）", data);
                return;
            }
            // 登录失败：凭证失效不再自动重试；其余按恢复策略排下次尝试
            long next;
            if (WjdrEngine.STATE_NEED_TOKEN.equals(st)) {
                next = 0;
                statusMessage = orEmpty(engine.lastFailure());
                FileLog.info("暂停恢复中止 {}：token 失效，待人工补给", key);
            } else {
                int maxAttempts = acc.takeoverMaxAttempts;
                recoveryAttempts++;
                if (maxAttempts <= 0) {
                    // 0=不自动顶号：账号保持离线，等手动上线
                    next = 0;
                    statusMessage = "顶号恢复连续尝试为 0，账号保持离线，可手动上线";
                } else if (recoveryAttempts < maxAttempts) {
                    next = System.currentTimeMillis()
                            + acc.takeoverRetryIntervalSeconds * 1000L;
                    statusMessage = "登录失败，等待重试（" + recoveryAttempts + "/" + maxAttempts + "）";
                } else {
                    next = System.currentTimeMillis()
                            + acc.takeoverCooldownMinutes * 60_000L;
                    statusMessage = "连续 " + maxAttempts + " 次登录失败，冷却 "
                            + acc.takeoverCooldownMinutes + " 分钟后再试";
                    recoveryAttempts = 0;   // 冷却结束后重置尝试计数（契约 §二.7）
                }
                FileLog.info("暂停恢复失败 {}：{}，下次尝试={}",
                        key, statusMessage, next == 0 ? "无" : String.valueOf(next));
                // 真机监控：自动恢复失败（token 失效不再重试 / 按恢复策略排下次）
                JSONObject data = new JSONObject();
                data.put("state", orEmpty(st));
                data.put("statusMessage", orEmpty(statusMessage));
                data.put("nextResumeAt", next);
                data.put("recoveryAttempts", recoveryAttempts);
                monitorSession("resume_fail", "暂停恢复失败：" + orEmpty(statusMessage), data);
            }
            pauseResumeAt = next;
            store.applyRolePauseSchedule(fpid, norm(sid), uidArg == null ? "" : uidArg, next);
        }

        private final java.util.concurrent.atomic.AtomicBoolean resumeInFlight =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 首次登录：初始化 → 注入指纹 → 登录（按绑定角色）→ 固化"学到"的设备身份。 */
        void startLogin() {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null || acc.token == null || acc.token.isEmpty()) return;
            try {
                engine.setProxy(resolveProxy(acc));
                engine.initialize();
                String seed = identitySeed(acc, sid);
                if (seed != null && !seed.isEmpty()) engine.applyIdentity(seed);
                WjdrEngine.LoginResult r = loginBoundRole(acc);
                FileLog.info("登录成功 {} 角色={}", key,
                        r.gameName == null ? "" : r.gameName);
                roleUid = r.uid;   // /status 回传 App：据此学习 sid→uid（同服多角色映射）
                roleName = r.gameName == null ? "" : r.gameName;
                roleLevel = r.level;
                rolePower = r.power;
                roleAlliance = r.alliance == null ? "" : r.alliance;
                // 登录学到的角色落账号池（pool 视图渐进丰富；名称已有则不覆盖）
                if (r.uid > 0) {
                    long actualSid = r.serverId > 0 ? r.serverId : resolveSid();
                    if (actualSid > 0) {
                        store.learnRoleMeta(fpid, String.valueOf(actualSid), String.valueOf(r.uid),
                                roleName, roleLevel, rolePower);
                    }
                }
                verifyIdentity(r.uid);
                String learned = engine.identityJson();
                if (!learned.isEmpty()) store.learnIdentity(fpid, sid, learned);
                // 互助/开盾惰性挂载 + 排首次采集：只在会话成为 state 写入方后驱动（tick）
                if (sid == null || sid.isEmpty()) {
                    if (r.serverId > 0) sid = String.valueOf(r.serverId);
                }
                nextCaptureAt = System.currentTimeMillis() + 3000;
                // 真机监控（2026-10-25）：登录成功快照（昵称/uid/sid/让位策略 + 已收 notify 统计）
                reportLoginSuccess(r);
            } catch (Throwable t) {
                // 引擎状态机已分类（退避重试 / need_token），这里只留痕
                FileLog.warn("登录失败 {}：{}", key,
                        t.getMessage() == null ? String.valueOf(t) : t.getMessage());
                // 真机监控：登录失败（含 token 失效 / 顶号 / 网络异常）
                reportLoginFailure(t);
            }
        }

        void shutdown() {
            wantOnline = false;
            // 真机监控（2026-10-25）：会话关闭事件（CLOSED 语义；原因/最后状态留档）
            try {
                JSONObject data = new JSONObject();
                data.put("state", orEmpty(engine.sessionState()));
                data.put("lastFailure", orEmpty(engine.lastFailure()));
                data.put("runState", orEmpty(runState));
                monitorSession("closed", "会话关闭（runState=" + orEmpty(runState) + "）", data);
            } catch (Throwable ignored) {
                // 监控失败不影响会话销毁
            }
            // 守护盟友 P0：停掉 guardian 单线程执行器（不挂起线程，不发请求）
            com.carlos.vaultserver.guardian.ServerGuardianEngine g = guardian;
            if (g != null) {
                try {
                    g.shutdown();
                } catch (Throwable ignored) {
                }
            }
            // W1：会话关闭 → 资产库 invalidate + 投影末帧 + 从注册表 detach（先于 engine.close）
            detachAssetLibrary("session-closed");
            // P7：会话关闭 → 注销该角色战争态实例。否则旧角色的 WARTIME 会残留（实例从不回收），
            // 经账号级 wartimeYield 继续拦截其它角色/整账号的节奏派单（2026-10-24 事故根因之一）。
            ServerWarState w = war;
            war = null;
            if (w != null) {
                // Phase2B：按角色注销（不再误删兄弟角色实例）
                ServerWarState.unregister(fpid, w.sid, roleUid > 0L ? roleUid : wantUid);
            }
            // 批次B：会话关闭 → 释放竞技场独占门（否则该角色其它任务最多被挡 30min 由 TTL 兜底）
            try {
                long gateSid = w != null ? w.sid : resolveSid();
                long gateUid = roleUid > 0L ? roleUid : wantUid;
                com.carlos.vaultserver.task.ArenaGate.clear(fpid, gateSid, gateUid);
            } catch (Throwable ignored) {
            }
            try {
                engine.close();
            } catch (Throwable ignored) {
            }
            exec.shutdownNow();
        }
    }

    @Override
    public void reconcile() {
        synchronized (actionLock) {
            reconcileLocked();
        }
    }

    private void reconcileLocked() {
        if (stopped) return;
        List<VaultStore.Account> accs = store.snapshot();
        // 期望会话集合：desiredOnline 的全部角色（同 fpid 可多个，M1d 多角色并行恢复）；
        // 无任何 desired 条目时回落旧语义（keep=true → active 角色单会话，v4.1.4 旧数据兼容）。
        java.util.Map<String, VaultStore.RoleEntry> desired = new java.util.LinkedHashMap<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (VaultStore.Account a : accs) {
            known.add(a.fpid);
            if (a.token == null || a.token.isEmpty()) continue;
            for (VaultStore.RoleEntry d : desiredRolesOf(a)) {
                desired.put(sessionKey(a.fpid, d.sid, d.uid), d);
            }
        }
        // 停掉不再期望/已删除账号的会话（已删除的顺带清理采集状态）
        for (Map.Entry<String, LiveSession> e : sessions.entrySet()) {
            if (!desired.containsKey(e.getKey())) {
                LiveSession ls = e.getValue();
                FileLog.info("停止会话 {}（角色不再期望在线或账号已删除）", ls.key);
                ls.shutdown();
                sessions.remove(e.getKey());
                if (!known.contains(ls.fpid)) {
                    stateStore.removeAccount(ls.fpid);
                    stateStore.flush();
                }
            }
        }
        // 确保每个期望角色有会话（并发上限按角色会话数计）；token 变化 → 重启会话
        for (VaultStore.Account a : accs) {
            if (a.token == null || a.token.isEmpty()) continue;
            for (VaultStore.RoleEntry d : desiredRolesOf(a)) {
                String key = sessionKey(a.fpid, d.sid, d.uid);
                LiveSession ls = sessions.get(key);
                if (ls == null) {
                    if (sessions.size() >= config.maxConcurrentSessions) {
                        FileLog.warn("并发角色会话已达上限 {}，暂不恢复 {}",
                                String.valueOf(config.maxConcurrentSessions), key);
                        continue;
                    }
                    ls = new LiveSession(a, d.sid, d.uid, d.runState, d.pauseResumeAt);
                    sessions.put(key, ls);
                    FileLog.info("建会话 {} runState={} 代理={}", key, d.runState,
                            String.valueOf(resolveProxy(a)));
                    ls.start();
                } else if (!Objects.equals(ls.token, a.token)) {
                    // token 更新 → 重启会话（绑定角色由键定死，无需比对 sid/uid）
                    FileLog.info("token 变化，重启会话 {}", key);
                    ls.shutdown();
                    sessions.remove(key);
                    if (sessions.size() >= config.maxConcurrentSessions) {
                        FileLog.warn("并发角色会话已达上限 {}，暂不重启 {}",
                                String.valueOf(config.maxConcurrentSessions), key);
                        continue;
                    }
                    LiveSession fresh = new LiveSession(a, d.sid, d.uid, d.runState, d.pauseResumeAt);
                    sessions.put(key, fresh);
                    fresh.start();
                }
            }
        }
    }

    /**
     * 账号的期望角色集合（快照上推导，不改库）：
     * desiredOnline=true 的全部角色（同 fpid 可多个，M1d 多角色并行恢复）；
     * 无任何 desired 条目且 keep=true 时回落 active 角色单会话（显式动作优先于
     * keep，有 desired 条目时本回落一律不生效）——activeUid 缺省（旧数据/旧客户端，
     * 无 uid 概念）按 activeSid 兜底建无 uid 槽；activeUid 明确则按
     * (activeSid, activeUid) 建 uid 绑定槽。
     *
     * <p>冷启动旧格式兼容（M1e，2026-10-21）：生产旧 vault.json 角色条目无
     * desiredOnline 字段但带 activeUid，此前回落限定 activeUid==0 导致冷启动
     * 不建会话、自动化停摆到 App 下次 sync；现 keep=true + activeSid/activeUid
     * 齐备即回落 active 角色。sync 侧 mergeRolesLocked 在 keep=true 时本就会把
     * active 角色重算为 desired，回落与此收敛语义一致。
     */
    private static List<VaultStore.RoleEntry> desiredRolesOf(VaultStore.Account a) {
        List<VaultStore.RoleEntry> out = new ArrayList<>();
        for (VaultStore.RoleEntry r : a.roles.values()) {
            if (r.desiredOnline) out.add(r);
        }
        if (out.isEmpty() && a.keep) {
            if (parseUid(a.activeUid) > 0) {
                if (a.activeSid != null && !a.activeSid.isEmpty()) {
                    out.add(new VaultStore.RoleEntry(a.activeSid, a.activeUid));
                }
            } else {
                out.add(new VaultStore.RoleEntry(a.activeSid == null ? "" : a.activeSid, null));
            }
        }
        return out;
    }

    /** token 更新入口（token API 调用）：有会话则交给 reconcile 比对重启。 */
    public void onTokenUpdated(String fpid) {
        reconcile();
    }

    // ---------- 角色显式控制面（账号池 M1，契约 §3.2/§4） ----------

    /** 角色动作分发（login/logout/pause/resume/stop；幂等、串行化）。 */
    @Override
    public RoleOpResult roleAction(String fpid, String sid, String uid, String action) {
        return roleAction(fpid, sid, uid, action, null);
    }

    /** 角色动作分发（P1 2026-10-21 带参）：新增 antiban/pause(升级)/rename/remove/enabled。 */
    @Override
    public RoleOpResult roleAction(String fpid, String sid, String uid, String action,
                                   Map<String, Object> payload) {
        synchronized (actionLock) {
            if (action == null || action.isEmpty()) {
                return RoleOpResult.fail(3002, "缺少 action");
            }
            switch (action) {
                case "login":
                case "start":
                    return loginRoleLocked(fpid, sid, uid);
                case "pause":
                    return pauseRoleLocked(fpid, sid, uid);
                case "resume":
                    return resumeRoleLocked(fpid, sid, uid);
                case "logout":
                    return stopRoleLocked(fpid, sid, uid, true);
                case "stop":
                    return stopRoleLocked(fpid, sid, uid, false);
                case "antiban":
                    return antibanRoleLocked(fpid, sid, uid, payload);
                case "rename":
                    return renameRoleLocked(fpid, sid, uid, payload);
                case "remove":
                    return removeRoleLocked(fpid, sid, uid);
                case "enabled":
                    return enabledRoleLocked(fpid, sid, uid, payload);
                default:
                    return RoleOpResult.fail(3002, "未知 action: " + action);
            }
        }
    }

    private static boolean payloadEnabled(Map<String, Object> payload, boolean def) {
        if (payload == null) return def;
        Object v = payload.get("enabled");
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private static String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return "null".equals(s) ? null : s;
    }

    /** 角色解析（快照上找，动作落库走 store.apply*）。 */
    private static VaultStore.RoleEntry resolveOnSnapshot(VaultStore.Account a, String sid, String uid) {
        if (sid == null || sid.isEmpty()) return null;
        String wantUidStr = uid == null ? "" : uid;
        for (VaultStore.RoleEntry r : a.roles.values()) {
            if (sid.equals(r.sid) && wantUidStr.equals(r.uid)) return r;
        }
        // 回落 active 角色（sync 必建条目；手工库兜底）
        if (a.activeSid != null && a.activeSid.equals(sid)
                && a.activeUid != null && a.activeUid.equals(wantUidStr)) {
            return new VaultStore.RoleEntry(sid, wantUidStr);
        }
        return null;
    }

    /** 会话是否绑定给定角色（uid 缺省的旧会话按 sid 匹配）。 */
    private static boolean bindsRole(LiveSession ls, String sid, String uid) {
        if (ls.sid == null || !ls.sid.equals(sid)) return false;
        if (ls.wantUid > 0) return parseUid(uid) == ls.wantUid;
        return uid == null || uid.isEmpty() || parseUid(uid) == 0
                || String.valueOf(ls.wantUid).equals(uid);
    }

    private RoleOpResult loginRoleLocked(String fpid, String sid, String uid) {
        if (fpid == null || fpid.isEmpty()) return RoleOpResult.fail(3002, "缺少 fpid");
        if (sid == null || sid.isEmpty()) return RoleOpResult.fail(3002, "缺少 sid");
        if (uid == null || uid.isEmpty()) return RoleOpResult.fail(3002, "缺少 uid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        if (acc.token == null || acc.token.isEmpty()) {
            return RoleOpResult.fail(RoleOpResult.CODE_TOKEN_MISSING, "账号缺少 token，请先同步/补 token");
        }
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        if (!role.enabled) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_DISABLED, "角色已停用（已收纳），请先在角色管理中启用");
        }
        List<RoleRef> stopped = new ArrayList<>();   // 多角色并行（M1d）：login 不再顶兄弟角色，恒空
        String key = sessionKey(fpid, role.sid, role.uid);
        LiveSession cur = sessions.get(key);
        if (cur != null) {
            // 幂等：已是该角色会话 —— 暂停中则视为恢复，RUNNING 直接成功（ALREADY_ONLINE 非错误）
            if (VaultStore.RUN_PAUSED.equals(cur.runState)) {
                cur.runState = VaultStore.RUN_RUNNING;
                cur.pauseResumeAt = 0;
                cur.recoveryAttempts = 0;
                cur.statusMessage = "";
                store.clearRolePause(fpid, role.sid, role.uid);
                return RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
            }
            // 2026-10-24 缺陷修复：会话存在但引擎不在线（典型=被顶号后 takeover、断线退避中、
            // 登录前的 offline）时，旧逻辑直接返回 ok → App toast「上线成功」但实际不回连。
            // 手动「上线」是用户的显式意图，必须真正推进引擎状态机：
            String engState = cur.engine.sessionState();
            if (WjdrEngine.STATE_ONLINE.equals(engState)) {
                return RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
            }
            if (WjdrEngine.STATE_NEED_TOKEN.equals(engState)) {
                return RoleOpResult.fail(RoleOpResult.CODE_TOKEN_MISSING,
                        "token 已失效，需重新导出/同步 token 后再上线");
            }
            if (WjdrEngine.STATE_TAKEOVER.equals(engState)
                    || WjdrEngine.STATE_RECOVERING.equals(engState)) {
                // 被顶号/退避中：清让位探测与节流，立即强制回连（会顶掉其他设备；App 有顶号策略提示）
                cur.lastForceLoginAt = 0;
                cur.yieldProbeAt = 0;
                cur.statusMessage = "手动上线：正在强制回连";
                cur.engine.beginRecoveryNow("手动上线：强制回连");
                FileLog.info("手动上线强制回连 {}（原状态 {}）", key, engState);
                RoleOpResult r = RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
                r.message = "已强制回连（可能顶掉其他设备）";
                return r;
            }
            // offline：会话已就绪，节拍下一拍会 startLogin；logging：登录进行中
            RoleOpResult r = RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
            r.message = WjdrEngine.STATE_OFFLINE.equals(engState) ? "会话已就绪，正在登录" : "正在登录中";
            return r;
        }
        // 并发上限按角色会话数计（M1d，超限 code=1429）
        if (sessions.size() >= config.maxConcurrentSessions) {
            return RoleOpResult.fail(RoleOpResult.CODE_CONCURRENCY_LIMIT,
                    "并发会话已达上限 " + config.maxConcurrentSessions);
        }
        // 先落期望（崩溃一致），再动会话；同 fpid 其它角色的期望态与 session 保留（多角色并行）
        store.applyRoleLogin(fpid, role.sid, role.uid);
        // 旧数据无 uid 的「active 槽」会话被显式角色管理取代时停掉（避免同号双会话互踢）；
        // 键含明确 uid 的兄弟角色会话一律保留（M1d：login 不停兄弟）
        List<LiveSession> legacySlots = new ArrayList<>();
        for (LiveSession ls : sessions.values()) {
            if (ls.fpid.equals(fpid) && ls.wantUid == 0) legacySlots.add(ls);
        }
        for (LiveSession ls : legacySlots) {
            FileLog.info("角色上线接管旧 active 槽会话：停止 {}", ls.key);
            ls.shutdown();
            sessions.remove(ls.key);
        }
        VaultStore.Account fresh = store.find(fpid);
        LiveSession ls = new LiveSession(fresh, role.sid, role.uid, VaultStore.RUN_RUNNING);
        sessions.put(key, ls);
        ls.start();
        FileLog.info("角色上线 {}（同号其它角色会话保留 {} 个）",
                key, String.valueOf(sessions.size() - 1));
        return RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
    }

    /**
     * 暂停（P1 2026-10-21 升级）：前置「恢复已开启 + 角色在线」；置 PAUSED 与
     * pauseResumeAt=now+顶号后等待，关闭会话连接（desiredOnline 保留，到期自动上线）。
     */
    private RoleOpResult pauseRoleLocked(String fpid, String sid, String uid) {
        if (fpid == null || fpid.isEmpty()) return RoleOpResult.fail(3002, "缺少 fpid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        LiveSession ls = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (ls != null && VaultStore.RUN_PAUSED.equals(ls.runState)) {
            RoleOpResult paused = RoleOpResult.ok(VaultStore.RUN_PAUSED);   // 幂等
            paused.pauseResumeAt = ls.pauseResumeAt;
            return paused;
        }
        if (!acc.takeoverRecoveryEnabled) {
            return RoleOpResult.fail(3002, "顶号恢复未开启，无法暂停");
        }
        if (ls == null || !ls.wantOnline || !bindsRole(ls, role.sid, role.uid)
                || !WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_ONLINE, "账号当前离线，无法暂停");
        }
        long resumeAt = System.currentTimeMillis()
                + acc.takeoverInitialDelaySeconds * 1000L;
        ls.runState = VaultStore.RUN_PAUSED;
        ls.pauseResumeAt = resumeAt;
        ls.recoveryAttempts = 0;
        long minutes = Math.max(1, (acc.takeoverInitialDelaySeconds + 59) / 60);
        ls.statusMessage = "已暂停，将在 " + minutes + " 分钟后自动上线";
        try {
            ls.engine.close();   // 停止会话连接；会话槽保留（驱动到期自动恢复）
        } catch (Throwable ignored) {
        }
        store.applyRolePauseSchedule(fpid, role.sid, role.uid, resumeAt);
        FileLog.info("角色暂停 {}（{} 分钟后自动上线，恢复计划已落库）",
                ls.key, minutes);
        // 真机监控：暂停事件（含自动恢复时刻）
        try {
            JSONObject data = new JSONObject();
            data.put("runState", VaultStore.RUN_PAUSED);
            data.put("pauseResumeAt", resumeAt);
            ls.monitorSession("pause", "已暂停，将在 " + minutes + " 分钟后自动上线", data);
        } catch (Throwable ignored) {
            // 监控失败不影响暂停
        }
        RoleOpResult ok = RoleOpResult.ok(VaultStore.RUN_PAUSED);
        ok.message = "账号已暂停，将在 " + minutes + " 分钟后自动上线";
        ok.pauseResumeAt = resumeAt;
        return ok;
    }

    private RoleOpResult resumeRoleLocked(String fpid, String sid, String uid) {
        if (fpid == null || fpid.isEmpty()) return RoleOpResult.fail(3002, "缺少 fpid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        LiveSession ls = sessions.get(sessionKey(fpid, sid, uid));
        if (ls == null || !ls.wantOnline) {
            // 契约 §3.2：无会话则等价 login
            return loginRoleLocked(fpid, sid, uid);
        }
        if (!bindsRole(ls, sid, uid)) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "会话角色不匹配: sid=" + sid + " uid=" + String.valueOf(uid));
        }
        if (VaultStore.RUN_RUNNING.equals(ls.runState)) {
            return RoleOpResult.ok(VaultStore.RUN_RUNNING);   // 幂等
        }
        ls.runState = VaultStore.RUN_RUNNING;
        ls.pauseResumeAt = 0;   // resume 立即清除暂停恢复计划（契约 §二.7）
        ls.recoveryAttempts = 0;
        ls.statusMessage = "";
        store.clearRolePause(fpid, sid, uid);
        FileLog.info("角色恢复 {}", ls.key);
        // 真机监控：手动恢复事件
        try {
            JSONObject data = new JSONObject();
            data.put("runState", VaultStore.RUN_RUNNING);
            data.put("pauseResumeAt", 0L);
            ls.monitorSession("resume", "手动恢复上线（RUNNING）", data);
        } catch (Throwable ignored) {
            // 监控失败不影响恢复
        }
        return RoleOpResult.ok(VaultStore.RUN_RUNNING);
    }

    /** stop（preserveKeep=false）/logout（保留 keep/自动化配置）。 */
    private RoleOpResult stopRoleLocked(String fpid, String sid, String uid, boolean preserveKeep) {
        if (fpid == null || fpid.isEmpty()) return RoleOpResult.fail(3002, "缺少 fpid");
        if (sid == null || sid.isEmpty()) return RoleOpResult.fail(3002, "缺少 sid");
        if (uid == null || uid.isEmpty()) return RoleOpResult.fail(3002, "缺少 uid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        LiveSession cur = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (cur != null) {
            cur.shutdown();
            sessions.remove(cur.key);
        }
        store.applyRoleStop(fpid, role.sid, role.uid, preserveKeep);
        FileLog.info("{} {}（剩余在线兄弟角色 {} 个）", preserveKeep ? "角色下线" : "角色停止",
                sessionKey(fpid, role.sid, role.uid), String.valueOf(sessions.size()));
        return RoleOpResult.ok(VaultStore.RUN_STOPPED);
    }

    // ---------- P1 角色管理增强（2026-10-21 契约 §二.1-§二.6） ----------

    /** op antiban：持久化防封开关；在线角色开启时若本次在线未做风控检测则立即补做。 */
    private RoleOpResult antibanRoleLocked(String fpid, String sid, String uid,
                                           Map<String, Object> payload) {
        boolean enabled = payloadEnabled(payload, false);
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        store.setRoleAntiban(fpid, role.sid, role.uid, enabled);
        LiveSession ls = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (ls != null) {
            ls.engine.setAntibanEnabled(enabled);
            // 在线切换开启：本次在线尚未做风控检测 → 立即补做（引擎内一次性闸门防重）
            if (enabled && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                ls.engine.performAntibanRiskCheckIfPending();
            }
        }
        FileLog.info("防封保护{} fpid={} 角色={}", enabled ? "开启" : "关闭",
                fpid, sessionKey(fpid, role.sid, role.uid));
        RoleOpResult r = RoleOpResult.ok(currentRunStateOf(ls));
        r.message = enabled ? "已开启防封风控安全保护" : "已关闭防封风控安全保护";
        return r;
    }

    private static String currentRunStateOf(LiveSession ls) {
        if (ls == null || !ls.wantOnline) return VaultStore.RUN_STOPPED;
        return ls.runState;
    }

    /** op rename：在线协议改名；道具不足（code=51/err_item_not_enough）自动 use_diamond 重试。 */
    private RoleOpResult renameRoleLocked(String fpid, String sid, String uid,
                                          Map<String, Object> payload) {
        String name = payloadString(payload, "name");
        String invalid = validateRenameName(name);
        if (invalid != null) return RoleOpResult.fail(3002, invalid);
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        LiveSession ls = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (ls == null || !ls.wantOnline || !bindsRole(ls, role.sid, role.uid)
                || !WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_ONLINE, "角色不在线，无法修改角色名");
        }
        boolean usedDiamond = false;
        String applied;
        try {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("name", name);
            args.put("use_diamond", false);
            com.carlos.vault.wjdr.protocol.GameMessage rsp =
                    ls.engine.request("req_player_modify_name", args);
            if (isItemShortage(rsp)) {
                // 改名道具不足：按线上语义改用钻石（400）重试
                usedDiamond = true;
                args.put("use_diamond", true);
                rsp = ls.engine.request("req_player_modify_name", args);
            }
            long code = rsp == null || rsp.getContent() == null
                    ? -1 : toLongValue(rsp.getContent().get("code"));
            if (code != 0) {
                return RoleOpResult.fail(3002, "改名失败（code=" + code + "），请稍后重试");
            }
            applied = appliedName(rsp, name);
        } catch (Exception e) {
            return RoleOpResult.fail(3002, "改名失败："
                    + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
        store.setRoleName(fpid, role.sid, role.uid, applied);
        ls.roleName = applied;
        FileLog.info("角色改名 {} {}→{} usedDiamond={}",
                ls.key, role.name, applied, usedDiamond);
        RoleOpResult r = RoleOpResult.ok(currentRunStateOf(ls));
        r.message = "角色名已更新";
        r.name = applied;
        r.usedDiamond = usedDiamond;
        r.diamondCost = usedDiamond ? 400L : 0L;
        r.itemId = 610001L;
        return r;
    }

    /**
     * 玩家昵称解析（v4.1.8 战争通知：WarNotify 异步线程回调）。
     *
     * <p><b>线程纪律</b>：实现走 {@code engine.request}（阻塞等响应，响应经推送线程投递）——
     * 只准从 WarNotify 的 name/wecom 线程调用；推送线程调用=自等自=死锁。
     * 找到该账号（优先匹配 sid）的在线会话借引擎发 {@code req_players_base_info}，
     * 返回 result.*.nickName；任何失败返回空串（调用方回退监视备注/uid）。
     */
    public String resolvePlayerName(String fpid, long sid, long uid) {
        if (fpid == null || fpid.isEmpty() || uid <= 0) return "";
        for (LiveSession ls : sessions.values()) {
            if (!fpid.equals(ls.fpid)) continue;
            if (sid > 0 && ls.resolveSid() != sid) continue;
            if (!WjdrEngine.STATE_ONLINE.equals(ls.state)) continue;
            try {
                com.carlos.vault.wjdr.protocol.GameMessage pbi = ls.engine.request(
                        "req_players_base_info",
                        com.carlos.vaultserver.task.Exec.args("uids",
                                java.util.Collections.singletonList(uid)));
                Object result = pbi == null || pbi.getContent() == null
                        ? null : pbi.getContent().get("result");
                if (result instanceof Map) {
                    for (Object v : ((Map<?, ?>) result).values()) {
                        Map<String, Object> p =
                                com.carlos.vaultserver.task.Exec.mapOf(v);
                        if (p == null) continue;
                        Object nick = p.get("nickName");
                        String name = nick == null ? "" : String.valueOf(nick).trim();
                        if (!name.isEmpty() && !"null".equals(name)) return name;
                    }
                }
            } catch (Throwable t) {
                FileLog.info("战争通知昵称解析失败 fpid={} uid={}：{}", fpid, uid, String.valueOf(t));
            }
            return "";
        }
        return "";
    }

    /** 名称规则（对齐线上 RoleRenameSupport）：3~12 字、首尾无空格、无控制字符。 */
    static String validateRenameName(String raw) {
        String name = raw == null ? "" : raw;
        if (name.isEmpty()) return "角色名不能为空";
        if (!name.equals(name.trim())) return "角色名前后不能有空格";
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return "角色名不能包含换行或控制字符";
        }
        int len = name.codePointCount(0, name.length());
        if (len < 3 || len > 12) return "角色名长度需为 3~12 个字";
        return null;
    }

    /** 道具不足判定：错误码 51 或错误描述 err_item_not_enough（对齐线上 isItemShortage）。 */
    private static boolean isItemShortage(com.carlos.vault.wjdr.protocol.GameMessage rsp) {
        if (rsp == null || rsp.getContent() == null) return false;
        Map<String, Object> content = rsp.getContent();
        if (toLongValue(content.get("code")) == 51L) return true;
        return String.valueOf(content).contains("err_item_not_enough");
    }

    /** 应用响应 result.nickName（缺省用入参）。 */
    private static String appliedName(com.carlos.vault.wjdr.protocol.GameMessage rsp, String fallback) {
        if (rsp == null || rsp.getContent() == null) return fallback;
        Object result = rsp.getContent().get("result");
        if (result instanceof Map) {
            Object nick = ((Map<?, ?>) result).get("nickName");
            if (nick != null && !String.valueOf(nick).isEmpty()) return String.valueOf(nick).trim();
        }
        return fallback;
    }

    private static long toLongValue(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** op remove：从账号工作区移除角色（不删游戏内角色）；先停会话；active 指针随之清空。 */
    private RoleOpResult removeRoleLocked(String fpid, String sid, String uid) {
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        LiveSession cur = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (cur != null) {
            cur.shutdown();
            sessions.remove(cur.key);
        }
        store.removeRole(fpid, role.sid, role.uid);
        // R2 Phase 2B：角色被移除时清理其角色级状态键（防孤儿键堆积/串读）；旧键/兄弟角色键不动。
        try {
            long sidNum = Long.parseLong(role.sid == null ? "" : role.sid.trim());
            long uidNum = parseUid(role.uid);
            int removed = stateStore.removeRoleKeys(fpid, sidNum, uidNum);
            if (removed > 0) {
                FileLog.info("角色状态键已清理 {}：{} 个键", sessionKey(fpid, role.sid, role.uid),
                        String.valueOf(removed));
            }
        } catch (Throwable ignored) {
        }
        FileLog.info("角色移除 {}（仅工具内移除，游戏内角色未删除）",
                sessionKey(fpid, role.sid, role.uid));
        RoleOpResult r = RoleOpResult.ok(VaultStore.RUN_STOPPED);
        r.message = "角色已移除（游戏内角色未删除）";
        return r;
    }

    /** op enabled：启用/收纳角色；收纳时停止会话并清期望（keep 保留）。 */
    private RoleOpResult enabledRoleLocked(String fpid, String sid, String uid,
                                           Map<String, Object> payload) {
        boolean enabled = payloadEnabled(payload, true);
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        store.setRoleEnabled(fpid, role.sid, role.uid, enabled);
        if (!enabled) {
            LiveSession cur = sessions.get(sessionKey(fpid, role.sid, role.uid));
            if (cur != null) {
                cur.shutdown();
                sessions.remove(cur.key);
            }
            // 收纳 = 停会话 + 撤期望；preserveKeep=true 不动账号自动化配置
            store.applyRoleStop(fpid, role.sid, role.uid, true);
        }
        FileLog.info("角色{} {}", enabled ? "启用" : "收纳",
                sessionKey(fpid, role.sid, role.uid));
        RoleOpResult r = RoleOpResult.ok(VaultStore.RUN_STOPPED);
        r.message = enabled ? "已启用" : "已收纳";
        return r;
    }

    /** 恢复设置变更（P1 op recovery-settings 持久化后回调）：关闭时取消暂停角色的待恢复计划。 */
    @Override
    public void onRecoverySettingsChanged(String fpid) {
        synchronized (actionLock) {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null) return;
            for (LiveSession ls : sessions.values()) {
                if (!ls.fpid.equals(fpid)) continue;
                if (!VaultStore.RUN_PAUSED.equals(ls.runState)) continue;
                if (!acc.takeoverRecoveryEnabled) {
                    ls.pauseResumeAt = 0;
                    ls.recoveryAttempts = 0;
                    ls.statusMessage = "顶号恢复已关闭，账号保持离线，可手动上线";
                    store.applyRolePauseSchedule(fpid, norm(ls.sid),
                            ls.uidArg == null ? "" : ls.uidArg, 0);
                    FileLog.info("恢复设置关闭：取消待恢复计划 {}", ls.key);
                }
            }
        }
    }

    // ---------- 账号池状态视图（/accounts 与 /status 扩展共用） ----------

    @Override
    public RoleRuntime roleRuntime(String fpid, String sid, String uid) {
        LiveSession ls = sessions.get(sessionKey(fpid, sid, uid));
        if (ls == null || !ls.wantOnline || !bindsRole(ls, sid, uid)) {
            return RoleRuntime.offline();   // 无会话 = 离线（M1 契约 §2.1）
        }
        RoleRuntime rt = new RoleRuntime();
        boolean paused = VaultStore.RUN_PAUSED.equals(ls.runState);
        rt.runState = paused ? VaultStore.RUN_PAUSED : VaultStore.RUN_RUNNING;
        // 暂停态：恢复计划与中文说明透出给 App（倒计时用 nextRecoveryAt/pause_resume_at）
        rt.nextRecoveryAt = paused ? ls.pauseResumeAt : rt.nextRecoveryAt;
        rt.statusMessage = orEmpty(ls.statusMessage);
        String st = ls.engine.sessionState();
        switch (st == null ? "" : st) {
            case WjdrEngine.STATE_ONLINE:
                rt.online = true;
                break;
            case WjdrEngine.STATE_LOGGING:
                rt.loginInProgress = true;
                break;
            case WjdrEngine.STATE_OFFLINE:
                rt.startupInProgress = true;   // 会话已建，等节拍发起登录
                break;
            case WjdrEngine.STATE_RECOVERING:
                rt.recoveryState = "WAITING_RETRY";
                rt.statusMessage = orEmpty(ls.engine.lastFailure());
                break;
            case WjdrEngine.STATE_TAKEOVER:
                rt.takeoverRecoveryActive = true;
                rt.recoveryState = "WAITING_RETRY";
                rt.nextRecoveryAt = ls.yieldProbeAt;
                rt.statusMessage = orEmpty(ls.engine.lastFailure());
                break;
            case WjdrEngine.STATE_NEED_TOKEN:
                rt.recoveryState = "ACTION_REQUIRED";
                rt.statusMessage = orEmpty(ls.engine.lastFailure());
                break;
            default:
                break;
        }
        return rt;
    }

    @Override
    public int onlineRoleCount() {
        int n = 0;
        for (LiveSession ls : sessions.values()) {
            if (ls.wantOnline && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) n++;
        }
        return n;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 测试用：同步驱动所有会话各一拍（manualTick 模式下代替后台线程）。 */
    void tickForTest() {
        for (LiveSession ls : sessions.values()) {
            try {
                ls.tick();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 整轮调度面（RoundScheduler / policy run 使用） ----------

    /**
     * 按需建会话并开始登录（账号页“登录并运行自动化”/手动触发用）。
     * 与 reconcile 的区别：不要求账号 keep=true——手动登录是一次显式请求，
     * 服务器临时保活该会话，跑完仍由统一开关决定是否常驻。
     *
     * <p>账号级入口保持 v4.1.4 单会话语义（M1d）：uid 明确时 active 角色上线并
     * {@link VaultStore#applyRoleLoginExclusive 收敛}同号其它角色会话；多角色并行上线
     * 请走角色级 role-action login。
     */
    public boolean ensureSession(String fpid) {
        synchronized (actionLock) {
            if (stopped) return false;
            VaultStore.Account acc = store.find(fpid);
            if (acc == null || acc.token == null || acc.token.isEmpty()) return false;
            // 显式登录即开启该账号的统一自动化，避免下一次 reconcile 把会话停掉。
            store.setAutomation(fpid, true);
            acc = store.find(fpid);
            // uid 明确的账号把期望态一并落库（active 角色在线），角色模型与会话保持一致
            boolean uidBound = acc.activeSid != null && !acc.activeSid.isEmpty()
                    && parseUid(acc.activeUid) > 0;
            String key;
            if (uidBound) {
                store.applyRoleLoginExclusive(fpid, acc.activeSid, acc.activeUid);
                key = sessionKey(fpid, acc.activeSid, acc.activeUid);
                // 会话级收敛：停掉该 fpid 其它键的会话（旧 active 槽/兄弟角色），
                // 保证账号级登录后单会话（与 sync 切角色收敛规则一致）
                List<LiveSession> others = new ArrayList<>();
                for (LiveSession ls : sessions.values()) {
                    if (ls.fpid.equals(fpid) && !ls.key.equals(key)) others.add(ls);
                }
                for (LiveSession ls : others) {
                    FileLog.info("账号级登录收敛：停止兄弟会话 {}", ls.key);
                    ls.shutdown();
                    sessions.remove(ls.key);
                }
            } else {
                key = sessionKey(fpid, acc.activeSid, null);
            }
            LiveSession ls = sessions.get(key);
            if (ls == null) {
                if (sessions.size() >= config.maxConcurrentSessions) {
                    FileLog.warn("并发角色会话已达上限 {}，拒绝按需建会话 {}",
                            String.valueOf(config.maxConcurrentSessions), key);
                    return false;
                }
                ls = new LiveSession(acc, uidBound ? acc.activeSid : null,
                        uidBound ? acc.activeUid : null, VaultStore.RUN_RUNNING);
                sessions.put(key, ls);
                FileLog.info("按需建会话 {} activeSid={}", key, String.valueOf(acc.activeSid));
                ls.start();
            }
            ls.wantOnline = true;
            if (VaultStore.RUN_PAUSED.equals(ls.runState)) {
                ls.runState = VaultStore.RUN_RUNNING;   // 手动触发自动化 = 显式恢复
                ls.pauseResumeAt = 0;
                ls.recoveryAttempts = 0;
                ls.statusMessage = "";
                if (uidBound) store.applyRoleRunState(fpid, acc.activeSid, acc.activeUid,
                        VaultStore.RUN_RUNNING);
            }
            return true;
        }
    }

    /**
     * 账号级入口（整轮调度/让位/状态行）寻址的会话：active 角色会话优先，
     * 回落该 fpid 任一会话（旧数据/uid 缺省）。
     */
    private LiveSession sessionForAccount(String fpid) {
        VaultStore.Account acc = store.find(fpid);
        if (acc != null && acc.activeSid != null && !acc.activeSid.isEmpty()) {
            LiveSession ls = sessions.get(sessionKey(fpid, acc.activeSid, acc.activeUid));
            if (ls != null) return ls;
        }
        for (LiveSession ls : sessions.values()) {
            if (ls.fpid.equals(fpid)) return ls;
        }
        return null;
    }

    /**
     * 提交整轮（fn=null）或单任务到该账号**当前 active 角色**会话线程（旧 API 兼容）；
     * Phase 2 起内部转成按角色寻址（{@link #submitRound(RoleKey, String, String)}）。
     */
    public boolean submitRound(String fpid, String fn, String trigger) {
        RoleKey role = activeRole(fpid);
        if (role == null) {
            // 无 activeUid 的旧数据/未登录落地：保持旧语义（任意会话）
            LiveSession ls = sessionForAccount(fpid);
            if (ls == null) return false;
            return submitRound(new RoleKey(fpid, ls.resolveSid(), ls.roleUid), fn, trigger);
        }
        return submitRound(role, fn, trigger);
    }

    /**
     * 提交整轮（fn=null）或单任务到**指定角色**会话线程（R2 Phase 2）。
     * 角色会话不存在/未期望在线/非 RUNNING → false，**不再 fallback 兄弟角色**（避免任务派错人）。
     */
    @Override
    public boolean submitRound(RoleKey role, String fn, String trigger) {
        if (role == null || !role.valid()) return false;
        LiveSession ls = findRoleSession(role);
        if (ls == null || !ls.wantOnline) return false;
        if (!VaultStore.RUN_RUNNING.equals(ls.runState)) return false;   // 暂停：不执行任务
        final RoleKey target = role;
        ls.exec.execute(() -> {
            ls.inFlight.incrementAndGet();
            try {
                if (!WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                    FileLog.warn("整轮跳过 role={}（会话不在线）", target.key());
                    return;
                }
                VaultStore.Account acc = store.find(role.fpid);
                if (acc == null) return;
                long sid = ls.resolveSid();
                java.util.Map<String, org.json.JSONObject> params = new java.util.LinkedHashMap<>();
                // Phase 2：优先按角色 uid 取参数；缺省回退该 sid 旧配置（迁移/旧 App 兼容）。
                java.util.Map<String, String> raw = acc.roleParamsByUid != null
                        ? acc.roleParamsByUid.get(sid + ":" + target.uid) : null;
                if (raw == null || raw.isEmpty()) raw = acc.roleParams.get(String.valueOf(sid));
                if (raw != null) {
                    for (java.util.Map.Entry<String, String> e : raw.entrySet()) {
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            try {
                                params.put(e.getKey(), new org.json.JSONObject(e.getValue()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                // Phase 2：按角色执行（uid/active 传入 runner；旧签名保留兜底）
                boolean active = ls.isActiveRoleWriter();
                if ("1".equals(System.getenv("VAULT_LEGACY_ROUND"))) {
                    com.carlos.vaultserver.task.RoundRunner.runRound(
                            ls.engine, acc, sid, stateStore, params, fn, trigger, target.uid, active);
                } else {
                    com.carlos.vaultserver.task.ModularRoundRunner.runRound(
                            ls.engine, acc, sid, stateStore, params, fn, trigger, target.uid, active);
                }
            } catch (Throwable t) {
                FileLog.warn("整轮异常 role={}：{}", target.key(), String.valueOf(t));
            } finally {
                ls.inFlight.decrementAndGet();
            }
        });
        return true;
    }

    /** 按角色键找会话（fpid|sid|uid）；uid<=0 退回旧 active 语义。 */
    private LiveSession findRoleSession(RoleKey role) {
        if (role == null) return null;
        if (role.uid > 0L) {
            LiveSession ls = sessions.get(sessionKey(role.fpid, String.valueOf(role.sid),
                    String.valueOf(role.uid)));
            if (ls != null) return ls;
        }
        return sessionForAccount(role.fpid);
    }

    /** 该账号会话线程是否正在执行任务（节奏调度防堆积用）。 */
    @Override
    public boolean executorBusy(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls != null && ls.inFlight.get() > 0;
    }

    // ---------------- R2 Phase 2：按角色寻址（RoundScheduler.KeeperFacade v2） ----------------

    /** 该账号所有已注册会话对应的角色（含离线/登录中；调度器再逐角色判在线）。 */
    @Override
    public java.util.List<RoleKey> rolesOf(String fpid) {
        java.util.List<RoleKey> out = new java.util.ArrayList<>();
        if (fpid == null || fpid.isEmpty()) return out;
        for (LiveSession ls : sessions.values()) {
            try {
                if (!fpid.equals(ls.fpid)) continue;
                long sid = ls.resolveSid();
                long uid = ls.roleUid > 0L ? ls.roleUid : ls.wantUid;
                if (sid <= 0L) continue;
                out.add(new RoleKey(fpid, sid, uid));
            } catch (Throwable ignored) {
                // 单会话异常不影响其它角色
            }
        }
        return out;
    }

    /** App 当前选中角色（activeSid/activeUid 优先；无会话返回 null）。 */
    @Override
    public RoleKey activeRole(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        if (ls == null) return null;
        long sid = ls.resolveSid();
        if (sid <= 0L) return null;
        return new RoleKey(fpid, sid, ls.roleUid > 0L ? ls.roleUid : ls.wantUid);
    }

    /** 指定角色会话是否在线。 */
    @Override
    public boolean sessionOnline(RoleKey role) {
        LiveSession ls = findRoleSession(role);
        return ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState());
    }

    /** 指定角色登录初始化就绪时刻（0=未知/离线）。 */
    @Override
    public long sessionOnlineSince(RoleKey role) {
        LiveSession ls = findRoleSession(role);
        return ls == null ? 0L : ls.onlineSince;
    }

    /** 指定角色会话线程是否忙。 */
    @Override
    public boolean executorBusy(RoleKey role) {
        LiveSession ls = findRoleSession(role);
        return ls != null && ls.inFlight.get() > 0;
    }

    /**
     * 指定角色是否处于战争让位态（Phase 2B 起按角色实例 fpid|sid|uid；兄弟角色互不影响）。
     */
    @Override
    public boolean wartimeYield(RoleKey role) {
        if (role == null) return true;
        return ServerWarState.wartimeYield(role.fpid, role.sid, role.uid);
    }

    /** G7：按任务粒度让位（守护战争态只暂停占队任务）。 */
    public boolean wartimeYield(RoleKey role, String fnId) {
        if (role == null) return true;
        return ServerWarState.wartimeYield(role.fpid, role.sid, role.uid, fnId);
    }

    /** 账号当前 active 角色会话 sid（无会话 0）。 */
    public long sessionSid(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls == null ? 0 : ls.resolveSid();
    }

    /** P7 战时让位（RoundScheduler.KeeperFacade）：该账号任一角色处于战争态 → 自动整轮延后。 */
    @Override
    public boolean wartimeYield(String fpid) {
        return ServerWarState.wartimeYield(fpid);
    }

    /** 账号 active 角色会话是否在线。 */
    public boolean sessionOnline(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState());
    }

    /**
     * C4 登录初始化就绪：本次进入 ONLINE 状态的时刻（epoch millis）。
     * 未知/离线/登录未落地返回 0（节奏调度按「无保护信息」处理，不做 20s 延迟派单）。
     *
     * @see RoundScheduler.KeeperFacade#sessionOnlineSince(String)
     */
    @Override
    public long sessionOnlineSince(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls == null ? 0L : ls.onlineSince;
    }

    @Override
    public boolean yield(String fpid, long probeMillis) {
        LiveSession ls = sessionForAccount(fpid);
        if (ls == null) return store.find(fpid) != null;
        ls.engine.beginYield(probeMillis, "App 端进游戏，服务器让位（" +
                (probeMillis / 60000L) + " 分钟后探测回连）");
        ls.yieldProbeAt = System.currentTimeMillis() + Math.max(0, probeMillis);
        FileLog.info("让位指令 {}，{}ms 后回连", ls.key, String.valueOf(probeMillis));
        return true;
    }

    /**
     * 观察点/地图扫描用：所有处于 {@link WjdrEngine#STATE_ONLINE} 的角色会话引擎快照（只读）。
     *
     * <p>返回的是引擎引用，调用方只应做两件事：{@code addMessageHook} 收推送、
     * {@code sendFireAndForget} 发地图查询；不得调用 request/close 等会改变会话语义的方法。
     * 会话掉线/重连时引擎实例保持不变，钩子仍有效。
     */
    public java.util.List<WjdrEngine> onlineEngines() {
        java.util.List<WjdrEngine> out = new java.util.ArrayList<>();
        for (LiveSession ls : sessions.values()) {
            try {
                if (ls.engine != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                    out.add(ls.engine);
                }
            } catch (Throwable ignored) {
                // 单个会话异常不影响其它会话
            }
        }
        return out;
    }

    @Override
    public List<SessionStatus> status() {
        List<SessionStatus> out = new ArrayList<>();
        for (VaultStore.Account a : store.snapshot()) {
            SessionStatus s = new SessionStatus();
            s.fpid = a.fpid;
            s.label = a.label;
            s.group = a.group;
            s.hasToken = a.token != null && !a.token.isEmpty();
            // 账号级行（App v4.1.4 渲染契约不变）：展示 active 角色会话；多角色并行态走 /accounts
            LiveSession ls = sessionForAccount(a.fpid);
            if (ls != null) {
                s.state = ls.engine.sessionState();
                s.lastFailure = ls.engine.lastFailure();
                s.sid = ls.sid;
                // 会话在线时以实际落地 uid 为准（App 据此把服务器角色学回本地角色表）；
                // 未登录回落到账号库下发的 activeUid
                s.activeUid = ls.roleUid > 0 ? String.valueOf(ls.roleUid) : a.activeUid;
                s.roleName = ls.roleName;
                s.roleLevel = ls.roleLevel;
                s.rolePower = ls.rolePower;
                s.roleAlliance = ls.roleAlliance;
                s.sinceTs = ls.stateSince;
            } else {
                s.state = a.keep ? WjdrEngine.STATE_OFFLINE : "offline";
                s.lastFailure = a.keep && !s.hasToken ? "缺少 token" : null;
                s.sid = a.activeSid;
                s.activeUid = a.activeUid;
                s.sinceTs = 0;
            }
            out.add(s);
        }
        return out;
    }

    public void shutdownAll() {
        stopped = true;
        for (LiveSession ls : sessions.values()) ls.shutdown();
        sessions.clear();
    }

    /** 指纹种子：指定角色（可空）的指纹优先，缺则账号级（对齐 App 端 roleIdentitySeed 语义）。 */
    private String identitySeed(VaultStore.Account a, String roleSid) {
        if (roleSid != null) {
            String role = a.roleIdentity.get(roleSid);
            if (role != null && !role.isEmpty()) return role;
        } else if (a.activeSid != null) {
            String role = a.roleIdentity.get(a.activeSid);
            if (role != null && !role.isEmpty()) return role;
        }
        return a.identity;
    }

    /** 出口代理：单账号覆盖 &gt; 全局配置；都没有 = 直连。 */
    private Proxy resolveProxy(VaultStore.Account a) {
        InetSocketAddress addr = null;
        if (a.proxy != null && a.proxy.contains(":")) {
            String[] hp = a.proxy.split(":");
            try {
                addr = new InetSocketAddress(hp[0], Integer.parseInt(hp[1]));
            } catch (NumberFormatException ignored) {
            }
        } else if (config.socksProxy != null) {
            addr = config.socksProxy;
        }
        return addr != null ? new Proxy(Proxy.Type.SOCKS, addr) : null;
    }

    // ------------------------------------------------------------------
    // 编队目录 v2 / 编队动作（契约 §3.1/§3.2/§3.5；T2 2026-10-25）
    // ------------------------------------------------------------------

    /** 编队动作结果（code=0 成功；由 HTTP 层转 {code,message,data} 信封/BizError）。 */
    public static final class FormationActionResult {
        public final int code;
        public final String message;
        public final org.json.JSONObject data;

        private FormationActionResult(int code, String message, org.json.JSONObject data) {
            this.code = code;
            this.message = message == null ? "" : message;
            this.data = data;
        }

        public static FormationActionResult ok(org.json.JSONObject data) {
            return new FormationActionResult(0, "", data == null ? new org.json.JSONObject() : data);
        }

        public static FormationActionResult fail(int code, String message) {
            return new FormationActionResult(code, message, null);
        }
    }

    /**
     * 编队动作分发（{@code /v1/vault/role-action} 的 {@code formation.*} 分支，契约 §3.5）。
     *
     * <p>动作：{@code formation.catalog-refresh}（刷新目录）、{@code formation.memory-save}
     * （记忆当前游戏编队，overwrite 语义）、{@code formation.memory-restore}（逐槽恢复）、
     * {@code formation.bear-binding}（读巨熊绑定）。
     */
    public FormationActionResult formationAction(String fpid, String sid, String uid, String action,
                                                 Map<String, Object> payload) {
        if (fpid == null || fpid.isEmpty()) return FormationActionResult.fail(3002, "缺少 fpid");
        if (sid == null || sid.isEmpty()) return FormationActionResult.fail(3002, "缺少 sid");
        if (uid == null || uid.isEmpty()) return FormationActionResult.fail(3002, "缺少 uid");
        if (action == null || action.isEmpty()) return FormationActionResult.fail(3002, "缺少 action");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return FormationActionResult.fail(1004, "账号不存在: " + fpid);
        VaultStore.RoleEntry role = resolveOnSnapshot(acc, sid, uid);
        if (role == null) {
            return FormationActionResult.fail(RoleOpResult.CODE_ROLE_NOT_FOUND,
                    "角色不存在: sid=" + sid + " uid=" + uid);
        }
        LiveSession ls = sessions.get(sessionKey(fpid, role.sid, role.uid));
        if (ls == null || !bindsRole(ls, role.sid, role.uid)) {
            return FormationActionResult.fail(3002, "角色会话不存在，请先上线");
        }
        String sidStr = role.sid;
        String uidStr = role.uid;
        WjdrEngine engine = ls.engine;
        synchronized (formationActionLock) {
            boolean online = ls.wantOnline && WjdrEngine.STATE_ONLINE.equals(engine.sessionState());
            switch (action) {
                case "formation.catalog-refresh": {
                    if (!online) {
                        return FormationActionResult.fail(3002, "账号离线，无法刷新编队目录，请先上线");
                    }
                    persistFormationCatalog(ls);
                    org.json.JSONObject data = new org.json.JSONObject();
                    data.put("ok", true);
                    data.put("capturedAt",
                            com.carlos.vaultserver.formation.FormationCatalog.catalog(engine).capturedAt);
                    return FormationActionResult.ok(data);
                }
                case "formation.memory-save": {
                    if (!online) {
                        return FormationActionResult.fail(3002, "账号离线，无法记忆编队，请先上线");
                    }
                    String kind = kindOf(payload);
                    if (kind == null) {
                        return FormationActionResult.fail(3002, "kind 必须是 DAILY 或 BATTLE");
                    }
                    boolean overwrite = payloadBool(payload, "overwrite", false);
                    try {
                        org.json.JSONObject data = com.carlos.vaultserver.formation.FormationMemory
                                .save(stateStore, engine, fpid, sidStr, uidStr, kind, overwrite);
                        persistFormationCatalog(ls);
                        return FormationActionResult.ok(data);
                    } catch (com.carlos.vaultserver.formation.FormationMemory.MemoryError e) {
                        return FormationActionResult.fail(e.code, e.getMessage());
                    }
                }
                case "formation.memory-restore": {
                    if (!online) {
                        return FormationActionResult.fail(3002, "账号离线，无法恢复编队，请先上线");
                    }
                    String kind = kindOf(payload);
                    if (kind == null) {
                        return FormationActionResult.fail(3002, "kind 必须是 DAILY 或 BATTLE");
                    }
                    try {
                        org.json.JSONObject data = com.carlos.vaultserver.formation.FormationMemory
                                .restore(stateStore, engine, fpid, sidStr, uidStr, kind);
                        persistFormationCatalog(ls);
                        return FormationActionResult.ok(data);
                    } catch (com.carlos.vaultserver.formation.FormationMemory.MemoryError e) {
                        return FormationActionResult.fail(e.code, e.getMessage());
                    }
                }
                case "formation.bear-binding": {
                    return FormationActionResult.ok(com.carlos.vaultserver.formation.FormationMemory
                            .bearBinding(stateStore, fpid, sidStr, uidStr));
                }
                default:
                    return FormationActionResult.fail(3002, "未知 action: " + action);
            }
        }
    }

    /** payload.kind（DAILY/BATTLE；大小写容忍；非法 null）。 */
    private static String kindOf(Map<String, Object> payload) {
        Object v = payload == null ? null : payload.get("kind");
        if (v == null) return null;
        String k = String.valueOf(v).trim().toUpperCase();
        if (com.carlos.vaultserver.formation.FormationMemory.KIND_DAILY.equals(k)
                || com.carlos.vaultserver.formation.FormationMemory.KIND_BATTLE.equals(k)) {
            return k;
        }
        return null;
    }

    /** 布尔参数（Boolean/字符串 "true"/"1"）。 */
    private static boolean payloadBool(Map<String, Object> payload, String key, boolean def) {
        Object v = payload == null ? null : payload.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    /**
     * 编队目录 v2 落 state（新键带 uid + 旧键双写；空快照不覆盖好目录）。
     *
     * <p>顺序：会话元数据（角色标识/自动比例/记忆/巨熊绑定）→ capture → 空快照保护 → 双写。
     * 异常只记日志（目录是旁路数据，不能影响会话节拍）。
     */
    void persistFormationCatalog(LiveSession ls) {
        if (ls == null || ls.engine == null) return;
        long sid = ls.resolveSid();
        if (sid <= 0L) return;
        long uidNum = ls.wantUid > 0L ? ls.wantUid : ls.engine.lastUid();
        String fpid = ls.fpid;
        String sidStr = String.valueOf(sid);
        String uidStr = uidNum > 0L ? String.valueOf(uidNum) : "";
        try {
            com.carlos.vaultserver.formation.FormationCatalog.identify(ls.engine, fpid, sid, uidStr);
            int[] auto = automaticSettingsFor(fpid, sidStr);
            com.carlos.vaultserver.formation.FormationCatalog
                    .setAutomaticSettings(ls.engine, auto[0], auto[1], auto[2]);
            // 自动比例 state 键（契约 §3.2；App 兜底读取，同键型带 uid）
            org.json.JSONObject autoJson = new org.json.JSONObject();
            autoJson.put("infantryRatio", auto[0]);
            autoJson.put("lancerRatio", auto[1]);
            autoJson.put("marksmanRatio", auto[2]);
            stateStore.putString(com.carlos.vaultserver.formation.FormationCatalog
                    .autosettingsKey(fpid, sidStr, uidStr), autoJson.toString());
            com.carlos.vaultserver.formation.FormationCatalog.setMemoryStatus(ls.engine,
                    com.carlos.vaultserver.formation.FormationMemory
                            .statusMap(stateStore, fpid, sidStr, uidStr));
            com.carlos.vaultserver.formation.FormationCatalog.setBearBinding(ls.engine,
                    com.carlos.vaultserver.formation.FormationMemory
                            .bearBindingMap(stateStore, fpid, sidStr, uidStr));
            com.carlos.vaultserver.formation.FormationCatalog.capture(ls.engine);
            Map<String, Object> snap = com.carlos.vaultserver.formation.FormationCatalog
                    .snapshot(ls.engine);
            if (catalogSnapshotEmpty(snap) && hasGoodFormationCatalog(fpid, sidStr, uidStr)) {
                FileLog.info("编队目录为空快照，保留既有目录 fpid={} sid={} uid={}", fpid, sidStr, uidStr);
                return;
            }
            String json = new org.json.JSONObject(snap).toString();
            stateStore.putString(com.carlos.vaultserver.formation.FormationCatalog
                    .stateKey(fpid, sidStr, uidStr), json);
            // 旧键（无 uid）只由 active 角色写入，避免同 fpid+sid 多角色互踩（一版过渡）
            if (ls.isActiveRoleWriter()) {
                stateStore.putString(com.carlos.vaultserver.formation.FormationCatalog
                        .legacyStateKey(fpid, sidStr), json);
            }
        } catch (Throwable t) {
            FileLog.warn("编队目录写入失败 fpid={} sid={}：{}", fpid, sidStr, String.valueOf(t));
        }
    }

    /** 自动编队比例：角色参数 formation_library（或伪功能 formation 内嵌）→ 默认 50/20/30。 */
    int[] automaticSettingsFor(String fpid, String sid) {
        int[] def = com.carlos.vaultserver.formation.FormationCatalog.DEFAULT_AUTOMATIC_SETTINGS.clone();
        try {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null) return def;
            Map<String, String> params = acc.roleParams.get(sid);
            if (params == null || params.isEmpty()) return def;
            for (String key : new String[]{"formation_library", "formation"}) {
                String raw = params.get(key);
                int[] s = com.carlos.vaultserver.formation.FormationCatalog
                        .parseAutomaticSettings(raw);
                if (s != null) return s;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** 目录 Map 是否属于「空快照」（无英雄且三兵种库存全 0）。 */
    static boolean catalogSnapshotEmpty(Map<String, Object> snap) {
        if (snap == null) return true;
        Object heroes = snap.get("heroes");
        if (heroes instanceof java.util.List && !((java.util.List<?>) heroes).isEmpty()) return false;
        Object soldiers = snap.get("soldiers");
        if (soldiers instanceof Map) {
            Map<?, ?> sol = (Map<?, ?>) soldiers;
            for (String k : new String[]{"infantry", "lancer", "marksman"}) {
                Object v = sol.get(k);
                if (v instanceof Number && ((Number) v).longValue() > 0L) return false;
            }
        }
        Object formations = snap.get("gameFormations");
        if (formations instanceof java.util.List && !((java.util.List<?>) formations).isEmpty()) {
            return false;
        }
        return true;
    }

    /** 已存在的目录是否「好目录」（有英雄或有兵力；新旧键任一命中）。 */
    boolean hasGoodFormationCatalog(String fpid, String sid, String uid) {
        for (String key : new String[]{
                com.carlos.vaultserver.formation.FormationCatalog.stateKey(fpid, sid, uid),
                com.carlos.vaultserver.formation.FormationCatalog.legacyStateKey(fpid, sid)}) {
            String raw = stateStore.getString(key);
            if (raw == null || raw.trim().isEmpty()) continue;
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw.trim());
                org.json.JSONArray heroes = o.optJSONArray("heroes");
                if (heroes != null && heroes.length() > 0) return true;
                org.json.JSONObject soldiers = o.optJSONObject("soldiers");
                if (soldiers != null) {
                    if (soldiers.optLong("infantry", 0) > 0 || soldiers.optLong("lancer", 0) > 0
                            || soldiers.optLong("marksman", 0) > 0) return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

}
