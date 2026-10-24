package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.AccountStore;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻在线会话管理器（对标原版 GameAccountSessionService 的多角色会话层）。
 *
 * <p>模型与原版一致：<b>每角色（fpid:sid）一条独立 TCP 会话</b>，各自心跳/恢复/顶号处理。
 * 引擎侧（WjdrEngine）已内置心跳判定、指数退避重连与 notify_logout 码表；本类只做三件事：
 * <ul>
 *   <li>1 秒扫描（原版 heartbeatExecutor 同款节拍）：在线会话发心跳、恢复态到点重登、顶号态探测回连；</li>
 *   <li>登录/重连/互助动作统一走单线程 actor（= 原版"全局登录并发 1"的保守版），避免扫描线程被阻塞；</li>
 *   <li>会话生命周期与账号库联动：token 更新即重建，账号退出自动日常即下线。</li>
 * </ul>
 *
 * <p>哪些角色常驻在线由 {@link #syncOnline()} 决定：开启「常驻在线」的账号 → 工作区全部启用角色。
 *
 * <p><b>同账号多角色并存（对齐原版 2026-10-18 复核）</b>：服务器在登录完成时踢掉该账号的
 * 既有会话（串行登录必互踢，code=46 同设备 / code=5 异设备）；但多条 loginauth<b>同时在途</b>
 * （都未完成登记）时可钻过踢人检查，全部登记成功后只要不掉线即长期共存。原版
 * `GameAccountSessionService` 用 `newVirtualThreadPerTaskExecutor()` 并发提交所有选中角色，
 * 没有错峰；并发上界由 `GameAccountLoginBudget(32)` 控制，失败按恢复/退避处理，不降级单会话。
 * 本地按同一语义：
 * <ul>
 *   <li>多角色账号登录 = 各角色线程 CountDownLatch <b>同刻发令</b>，并发 loginauth；</li>
 *   <li>全局公平信号量 {@link #LOGIN_BUDGET}（32）限流；</li>
 *   <li>缺员时热补缺的角色；全员掉线才整号冷启动；</li>
 *   <li>失败按 8s×2ⁿ 封顶 300s 退避，<b>不降级单会话</b>。</li>
 * </ul>
 * 设备指纹按角色独立（{@link AccountStore#roleIdentitySeed}）仅为了身份稳定/风控分散。
 */
public final class RoleSessionKeeper {

    /** 互助兜底轮询间隔（原版 alliance-donation 任务间隔 30s）。 */
    static final long HELP_POLL_MILLIS = 30_000L;

    /**
     * 多角色登录策略（对齐原版 startSelectedSessions / submitStartupPipelines）：
     * 同账号各角色<b>同刻并发</b>提交 loginauth（原版用 VirtualThreadPerTaskExecutor，
     * 没有错峰延迟），并发上界由 {@link #LOGIN_BUDGET} 控制。服务器若在登录完成时踢旧会话，
     * 由各会话的恢复/退避处理；不做“错峰钻窗口”，也不做“连续失败降级单会话”。
     */

    /** 一条常驻会话（fpid:sid → 引擎 + 互助响应状态）。 */
    public static final class LiveSession {
        public final WjdrEngine engine;
        public final String fpid;
        public final long sid;
        public final String label;

        // ---- 互助响应状态（HelpResponder 维护；对标原版 DailyAllianceDonationState 的 helpRequests） ----
        final Map<String, HelpResponder.HelpReq> helpRequests = new ConcurrentHashMap<>();
        final AtomicBoolean helpAttached = new AtomicBoolean();
        final AtomicBoolean helpBusy = new AtomicBoolean();
        volatile long helpBlockedUntil;
        volatile long lastHelpScanAt;
        volatile int helpNumToday;
        volatile long dayGlory;
        volatile long lastMarchPollAt;   // 行军类轮询兜底节流（PushWakeDriver.MARCH_POLL_MILLIS）

        // ---- 推送唤醒状态（PushWakeDriver；每会话一次） ----
        final java.util.concurrent.atomic.AtomicBoolean wakeAttached = new AtomicBoolean();

        // ---- 协议监听（ProtoLogger 诊断；每会话一次） ----
        final AtomicBoolean protoLogAttached = new AtomicBoolean();

        // ---- 核心资产库（W1 App 原生模式；engine 创建后、login 前 attach） ----
        volatile CoreAssetLibrary assetLib;

        LiveSession(WjdrEngine engine, String fpid, long sid, String label) {
            this.engine = engine;
            this.fpid = fpid;
            this.sid = sid;
            this.label = label == null ? fpid : label;
        }

        /** 供扫描线程异步重登（重登耗时数十秒，不能占住扫描线程）。 */
        void reconnectOnce() {
            try {
                engine.tryReconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile RoleSessionKeeper instance;

    public static RoleSessionKeeper get(Context ctx) {
        RoleSessionKeeper k = instance;
        if (k == null) {
            synchronized (RoleSessionKeeper.class) {
                if (instance == null) instance = new RoleSessionKeeper(ctx.getApplicationContext());
                k = instance;
            }
        }
        return k;
    }

    private final Context ctx;
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService actor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vault-session-actor");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledExecutorService scanner;

    // ---------- 账号级竞速登录状态（多角色并存的编排层） ----------
    // desired：<fpid → 想保持在线的(账号, 角色列表)>，syncOnline 每次全量重建；scan 按它判断
    // "账号是否完整"，不完整（任一会话掉线）就整号重赛——绝不单独重连某一条（会踢掉兄弟）。
    private static final class Desired {
        final AccountStore.Account acc;
        final List<Long> sids;
        Desired(AccountStore.Account acc, List<Long> sids) {
            this.acc = acc;
            this.sids = sids;
        }
    }

    private final Map<String, Desired> desired = new ConcurrentHashMap<>();
    /** 竞速退避：<fpid → 下次允许起赛的时刻>（失败 8s×2ⁿ 封顶 300s；人玩避让固定 60s）。 */
    private final Map<String, Long> nextRaceAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> raceShift = new ConcurrentHashMap<>();
    /** 正在竞速的账号（防重复起赛）。 */
    private final Set<String> raceInFlight = ConcurrentHashMap.newKeySet();
    /**
     * 全局登录并发预算（对齐原版 GameAccountLoginBudget.DEFAULT_MAX_CONCURRENT_LOGINS=32）。
     * 多角色同刻并发 + 几十个账号时，避免把网关/登录接口打爆；公平信号量保证先到先得。
     */
    static final int LOGIN_BUDGET_PERMITS = 32;
    private static final java.util.concurrent.Semaphore LOGIN_BUDGET =
            new java.util.concurrent.Semaphore(LOGIN_BUDGET_PERMITS, true);
    /** 竞速连续失败计数：仅用于退避与日志（原版语义：不降级，持续按退避恢复）。 */
    private final Map<String, Integer> raceFails = new ConcurrentHashMap<>();

    private RoleSessionKeeper(Context ctx) {
        this.ctx = ctx;
    }

    /** 启动 1 秒扫描（幂等；服务重建后可再次调用）。 */
    public synchronized void start() {
        if (scanner != null && !scanner.isShutdown()) return;
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vault-session-keeper");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleWithFixedDelay(this::scan, 1L, 1L, TimeUnit.SECONDS);
    }

    private void scan() {
        long now = System.currentTimeMillis();
        // 服务器模式自愈：模式开启前已建立的期望/会话（如启动时的本地竞速）在 1 秒内全部
        // 收敛关闭——否则本地会与 vault-server 互踢（会话创建入口在 syncOnline，这里兜底）。
        if (com.carlos.vault.VaultSync.isServerMode(ctx)) {
            if (!desired.isEmpty() || !sessions.isEmpty()) {
                closeSessionsNotIn(new HashSet<String>());
                desired.clear();
            }
            return;
        }
        // 按账号分组处理：多角色账号的恢复是"整号重赛"，不能逐会话单独重连
        Map<String, List<LiveSession>> byAccount = new java.util.HashMap<>();
        for (LiveSession ls : sessions.values()) {
            byAccount.computeIfAbsent(ls.fpid, k -> new ArrayList<>()).add(ls);
        }
        for (Map.Entry<String, List<LiveSession>> e : byAccount.entrySet()) {
            String fpid = e.getKey();
            Desired d = desired.get(fpid);
            boolean multi = d != null && d.sids.size() > 1;
            boolean needRace = false;
            String raceReason = "";
            for (LiveSession ls : e.getValue()) {
                try {
                    String st = ls.engine.sessionState();
                    if (WjdrEngine.STATE_ONLINE.equals(st)) {
                        ls.engine.heartbeatTick();
                        if (now - ls.lastHelpScanAt >= HELP_POLL_MILLIS) {
                            ls.lastHelpScanAt = now;
                            HelpResponder.poll(ctx, ls);
                        }
                        if (now - ls.lastMarchPollAt >= PushWakeDriver.MARCH_POLL_MILLIS) {
                            ls.lastMarchPollAt = now;
                            PushWakeDriver.pollMarch(ctx, ls);
                        }
                    } else if (WjdrEngine.STATE_RECOVERING.equals(st)) {
                        if (multi) {
                            // 多角色账号：单独重连会踢掉兄弟会话，标记整号重赛
                            needRace = true;
                            raceReason = "会话断线";
                        } else {
                            actor.execute(ls::reconnectOnce);
                        }
                    } else if (WjdrEngine.STATE_TAKEOVER.equals(st) && ls.engine.takeoverProbeDue(now)) {
                        if (multi) {
                            needRace = true;
                            raceReason = "顶号探测到期";
                        } else {
                            ls.engine.beginRecoveryNow("顶号探测回连");
                        }
                    }
                    // need_token：停机待人工重导 token，不自动重试
                } catch (Throwable ignored) {
                }
            }
            // 账号不完整（应有 N 条在线、实际缺员）也整号重赛
            if (!needRace && multi && d != null) {
                for (long sid : d.sids) {
                    LiveSession ls = sessions.get(key(fpid, sid));
                    if (ls == null || !WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                        needRace = true;
                        raceReason = "在线缺员";
                        break;
                    }
                }
            }
            if (needRace && d != null) {
                ensureAccountOnline(d);
            }
        }
    }

    /**
     * 确保某角色的常驻会话存在（不存在/凭证变更则创建并异步登录；返回当前会话，可能仍在登录中）。
     *
     * <p><b>单角色账号的常规路径</b>；多角色账号由 {@link #startRace} 竞速编排（同刻起跑钻
     * 登记窗口），不走本方法。身份按角色独立注入（roleIdentitySeed：主角色沿用账号级旧指纹，
     * 其它角色各自随机并固化）。
     */
    public LiveSession ensureOnline(AccountStore.Account acc, long sid) {
        if (acc == null || acc.fpid == null || acc.fpid.isEmpty()
                || acc.token == null || acc.token.isEmpty() || sid <= 0) {
            return null;
        }
        String k = key(acc.fpid, sid);
        LiveSession ls = sessions.get(k);
        if (ls != null) {
            // token 已更新（用户重导）：旧凭证会话永远登不回去，直接重建
            if (acc.token.equals(ls.engine.savedToken())) return ls;
            closeSession(k);
        }
        ls = new LiveSession(WjdrAndroid.newEngine(ctx), acc.fpid, sid, acc.label);
        // 状态变化日志（灰度观察用：logcat 过滤 SessionKeeper）
        final LiveSession flsLog = ls;
        ls.engine.setStateListener(s -> {
            android.util.Log.i("SessionKeeper",
                    flsLog.label + " " + flsLog.fpid + ":" + flsLog.sid + " → " + s
                            + (flsLog.engine.lastFailure() == null ? "" : " · " + flsLog.engine.lastFailure()));
            // W1：离开 ONLINE → 资产置 stale（重连后 init 重放会重新 READY）
            if (!WjdrEngine.STATE_ONLINE.equals(s)) invalidateAssetLibrary(flsLog, "state=" + s);
        });
        sessions.put(k, ls);
        // W1 资产库：engine 创建后、login 前 attach（登录过程中就会推送 init 帧）
        attachAssetLibrary(ls);
        start();
        final LiveSession fls = ls;
        final AccountStore.Account facc = acc;
        actor.execute(() -> {
            try {
                fls.engine.initialize();
                try {
                    AccountStore store = new AccountStore(ctx);
                    fls.engine.applyIdentity(store.roleIdentitySeed(
                            fls.fpid, fls.sid, RoleStore.activeRole(ctx, fls.fpid)));
                } catch (Throwable ignored) {
                }
                // 角色钉扎登录（uid 选角 + 落地校验）：serverid 会被服务器忽略恒落最近角色，
                // 不钉扎会出现"913 会话实际跑 888"，配置/状态跨区污染
                RoleLogin.loginPinned(ctx, fls.engine, facc.fpid, facc.token, fls.sid);
                try {
                    RoleStore.syncRoleMeta(ctx, fls.engine, fls.fpid);   // 登录推送补全昵称/等级/战力
                } catch (Throwable ignored) {
                }
                HelpResponder.attach(ctx, fls);
                PushWakeDriver.attach(ctx, fls);
                ProtoLogger.attach(ctx, fls);
                EnemyWatch.attach(ctx, fls);
                com.carlos.vault.daily.guardian.GuardianEngine.init(ctx);
                com.carlos.vault.daily.guardian.GuardianEngine.attach(ctx, fls);
                try {
                    new AccountStore(ctx).updateRoleSettings(fls.fpid, fls.sid, fls.engine.identityJson());
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
                // 登录失败：引擎状态机已就位（recovering 退避重试 / need_token 停机），扫描线程接管
            }
        });
        return ls;
    }

    /** 借一条"当前在线"的会话执行操作（无则返回 null，调用方回落 fresh 登录）。
     *  借用即防自顶：已有常驻会话的号绝不能再 fresh 登录（同号同角色会互踢）。 */
    public LiveSession borrowOnline(String fpid, long sid) {
        if (fpid == null || fpid.isEmpty()) return null;
        LiveSession ls = sessions.get(key(fpid, sid));
        return (ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) ? ls : null;
    }

    /** 该账号任意一条在线会话（未指定角色时的兜底）。 */
    public LiveSession borrowAnyOnline(String fpid) {
        if (fpid == null || fpid.isEmpty()) return null;
        for (LiveSession ls : sessions.values()) {
            if (fpid.equals(ls.fpid) && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                return ls;
            }
        }
        return null;
    }

    /** 动作线程（登录/重连/互助等阻塞操作统一串行，防扫描线程被卡）。 */
    public void execute(Runnable r) {
        try {
            actor.execute(() -> {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 常驻在线同步（DailySchedulerService 巡检调用，10 分钟一次）：
     * <b>开启「常驻在线」开关（keep:&lt;fpid&gt;）的账号</b>，其<b>工作区全部启用角色</b>保持在线
     * （无启用角色时回落调度角色）；不再符合条件的会话关闭。与「自动日常」（每日时间窗整轮）正交：
     * 常驻是 24h 心跳+自动互助，自动日常是每天窗口跑任务。
     *
     * <p>多角色账号经 {@link #startRace} 竞速登录同时起跑（见类注释的机制说明）；单角色账号走
     * {@link #ensureOnline} 常规登录。
     */
    public void syncOnline() {
        try {
            // 服务器模式：本地不建常驻会话（防与 vault-server 互踢），状态改由服务器回传。
            // syncOnline 的触发点（keep 开关切换/巡检/登录收尾）同时是账号数据可能变化的时点，
            // 顺手触发一次去抖全量同步，让服务器的 keep/active 角色跟随本机。
            if (com.carlos.vault.VaultSync.isServerMode(ctx)) {
                closeSessionsNotIn(new HashSet<String>());
                desired.clear();
                com.carlos.vault.VaultSync.onAccountsChanged(ctx);
                com.carlos.vault.VaultSync.fetchState(ctx);
                return;
            }
            Set<String> want = new HashSet<>();
            Map<String, Desired> wantDesired = new java.util.HashMap<>();
            for (AccountStore.Account acc : new AccountStore(ctx).all()) {
                if (acc == null || acc.token == null || acc.token.isEmpty()
                        || acc.fpid == null || acc.fpid.isEmpty()) continue;
                if (!RoleStore.keepOnline(ctx, acc.fpid)) continue;
                RoleStore.Schedule s = RoleStore.schedule(ctx, acc.fpid);
                List<Long> sids = new ArrayList<>();
                for (RoleStore.Role r : RoleStore.roles(ctx, acc.fpid)) {
                    if (r.enabled && r.serverid > 0) sids.add(r.serverid);
                }
                if (sids.isEmpty() && s.serverid > 0) sids.add(s.serverid);
                if (sids.isEmpty()) continue;
                wantDesired.put(acc.fpid, new Desired(acc, sids));
                for (long sid : sids) want.add(key(acc.fpid, sid));
            }
            // 全量重建期望表（keep 关掉的账号随之移出，scan 不再为其重赛）
            desired.clear();
            desired.putAll(wantDesired);
            for (Map.Entry<String, Desired> e : wantDesired.entrySet()) {
                ensureAccountOnline(e.getValue());
            }
            closeSessionsNotIn(want);
        } catch (Throwable ignored) {
        }
    }

    /** 降级单会话时的选角：在线者优先（不打断已建立的会话），其次 activeRole，再次第一个启用角色。 */
    private long pickSingle(String fpid, List<Long> sids) {
        for (long sid : sids) {
            LiveSession ls = sessions.get(key(fpid, sid));
            if (ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) return sid;
        }
        long act = 0;
        try {
            act = RoleStore.activeRole(ctx, fpid);
        } catch (Throwable ignored) {
        }
        for (long sid : sids) if (sid == act) return sid;
        return sids.get(0);
    }

    /**
     * 账号会话编排：全员在线则无事；单角色账号常规登录；多角色缺员时——
     * <b>已有在线会话则热补</b>（只补缺的角色，不动在线的——复刻实测成功形态：02:08 的
     * 成功对正是在第三方会话占用"已注册槽位"时建立的）；全员缺员才整号冷启动竞速。
     */
    private void ensureAccountOnline(Desired d) {
        String fpid = d.acc.fpid;
        if (d.sids.size() == 1) {
            ensureOnline(d.acc, d.sids.get(0));
            return;
        }
        java.util.List<Long> missing = new ArrayList<>();
        boolean anyOnline = false;
        for (long sid : d.sids) {
            LiveSession ls = sessions.get(key(fpid, sid));
            if (ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) anyOnline = true;
            else missing.add(sid);
        }
        if (missing.isEmpty()) return;
        startRace(d.acc, missing, d.sids, anyOnline ? "热补缺员" : "整号竞速", anyOnline);
    }

    /**
     * 账号级<b>竞速登录</b>：同账号的全部角色线程同刻起跑，多条 loginauth 同时在途，
     * 钻过服务器"登录完成时踢旧会话"的检查（游戏已知的卡多角色在线机制），全部登记成功后
     * 只要不掉线即长期共存。
     *
     * <p>护栏：①容器游戏客户端正挂本账号时不抢（人玩优先，60s 后再看）；②任一会话掉线后由
     * scan 触发<b>整号重赛</b>（关旧再赛，绝不单独重连——单独重连必踢兄弟）；③竞速失败按
     * 8s×2ⁿ 封顶 300s 退避。
     */
    void startRace(final AccountStore.Account acc, final List<Long> sids, final List<Long> allSids,
                   final String reason, final boolean warm) {
        final String fpid = acc.fpid;
        if (fpid == null || fpid.isEmpty() || sids == null || sids.isEmpty()) return;
        if (!raceInFlight.add(fpid)) return;   // 该账号已有竞速在跑
        final long now = System.currentTimeMillis();
        Long until = nextRaceAt.get(fpid);
        if (until != null && now < until) {
            raceInFlight.remove(fpid);
            return;
        }
        // 人玩优先：容器游戏客户端正挂本账号时不抢（竞速登录会把人玩顶下线）
        try {
            String live = new com.carlos.vault.VaultEngine(ctx).readLiveSessionFpid();
            if (live != null && !live.isEmpty() && live.equals(fpid)) {
                raceInFlight.remove(fpid);
                nextRaceAt.put(fpid, now + 60_000L);
                android.util.Log.i("SessionKeeper", fpid + " 游戏客户端在线，竞速避让 60s（" + reason + "）");
                return;
            }
        } catch (Throwable ignored) {
        }
        // 冷启动才关全账号旧会话；热补不动在线会话（只清要重登的角色的半死会话）
        if (!warm) closeAccountSessions(fpid);
        for (long sid : sids) {
            LiveSession old = sessions.get(key(fpid, sid));
            if (old != null && !WjdrEngine.STATE_ONLINE.equals(old.engine.sessionState())) {
                closeSession(key(fpid, sid));
            }
        }
        final int n = sids.size();
        // 对齐原版 startSelectedSessions：所有角色同刻并发提交 loginauth，
        // 不做错峰；并发上界由全局 LOGIN_BUDGET（32）控制。
        final java.util.concurrent.CountDownLatch startGate =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(n);
        android.util.Log.i("SessionKeeper",
                fpid + " 并发登录起跑：" + n + " 角色（全局预算 " + LOGIN_BUDGET_PERMITS
                        + "，" + reason + "）");
        for (int i = 0; i < n; i++) {
            final long sid = sids.get(i);
            final LiveSession ls = new LiveSession(WjdrAndroid.newEngine(ctx), fpid, sid, acc.label);
            final LiveSession fls = ls;
            ls.engine.setStateListener(s -> {
                android.util.Log.i("SessionKeeper",
                        fls.label + " " + fls.fpid + ":" + fls.sid + " → " + s
                                + (fls.engine.lastFailure() == null ? "" : " · " + fls.engine.lastFailure()));
                // W1：离开 ONLINE → 资产置 stale（重连后 init 重放会重新 READY）
                if (!WjdrEngine.STATE_ONLINE.equals(s)) invalidateAssetLibrary(fls, "state=" + s);
            });
            sessions.put(key(fpid, sid), ls);
            // W1 资产库：engine 创建后、login 前 attach（竞速线程稍后才 initialize/login）
            attachAssetLibrary(ls);
            Thread t = new Thread(() -> {
                try {
                    fls.engine.initialize();
                    try {
                        AccountStore store = new AccountStore(ctx);
                        fls.engine.applyIdentity(store.roleIdentitySeed(
                                fls.fpid, fls.sid, RoleStore.activeRole(ctx, fls.fpid)));
                    } catch (Throwable ignored) {
                    }
                    startGate.await();   // 同刻发令：所有角色并发提交 loginauth
                    try {
                        LOGIN_BUDGET.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        RoleLogin.loginPinned(ctx, fls.engine, acc.fpid, acc.token, fls.sid);
                    } finally {
                        LOGIN_BUDGET.release();
                    }
                    try {
                        RoleStore.syncRoleMeta(ctx, fls.engine, fls.fpid);   // 登录推送补全昵称/等级/战力
                    } catch (Throwable ignored) {
                    }
                    HelpResponder.attach(ctx, fls);
                    PushWakeDriver.attach(ctx, fls);
                    ProtoLogger.attach(ctx, fls);
                    EnemyWatch.attach(ctx, fls);
                    com.carlos.vault.daily.guardian.GuardianEngine.init(ctx);
                    com.carlos.vault.daily.guardian.GuardianEngine.attach(ctx, fls);
                    try {
                        new AccountStore(ctx).updateRoleSettings(fls.fpid, fls.sid, fls.engine.identityJson());
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                    // 失败会话留在状态机（recovering/need_token），settle 后按退避整号重赛
                } finally {
                    done.countDown();
                }
            }, "vault-race-" + fpid + ":" + sid);
            t.setDaemon(true);
            t.start();
        }
        startGate.countDown();   // 发令
        Thread settle = new Thread(() -> {
            try {
                done.await();
                // 宽限：顶号推送可比 login 返回晚若干毫秒才被入站线程处理，
                // 立即结算会把"登录后 4ms 被踢"虚报成成功。等一小会儿再以实际会话态计分。
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
            raceInFlight.remove(fpid);
            // 结算口径：结算时刻的实际会话态（含热补时未参赛的在线角色，不用线程内计数——
            // 顶号推送可比 login 返回晚若干毫秒，线程内计数会虚报成功）
            int ok = 0;
            for (long sid : allSids) {
                LiveSession ls = sessions.get(key(fpid, sid));
                if (ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) ok++;
            }
            if (ok == allSids.size()) {
                raceShift.put(fpid, 0);
                raceFails.remove(fpid);
                android.util.Log.i("SessionKeeper",
                        fpid + " 并发登录成功：" + ok + " 角色同时在线（" + reason + "）");
            } else {
                // 原版语义：不降级单会话，只按指数退避继续恢复（8s×2ⁿ 封顶 300s）。
                int fails = raceFails.merge(fpid, 1, Integer::sum);
                int shift = Math.min(raceShift.merge(fpid, 1, Integer::sum), 6);
                long backoff = Math.min(8000L << shift, 300000L);
                nextRaceAt.put(fpid, System.currentTimeMillis() + backoff);
                android.util.Log.i("SessionKeeper", fpid + " 并发登录未全员在线（"
                        + ok + "/" + n + "，第 " + fails + " 次），" + (backoff / 1000) + "s 后重赛");
            }
        }, "vault-race-settle-" + fpid);
        settle.setDaemon(true);
        settle.start();
    }

    private String pickSingleLog(String fpid, Desired d) {
        try {
            return "K" + pickSingle(fpid, d.sids);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 关闭该账号的全部常驻会话（fresh 登录其任意角色前调用：服务器按账号唯一会话，防互踢）。 */
    public void closeAccountSessions(String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        for (LiveSession ls : new ArrayList<>(sessions.values())) {
            if (fpid.equals(ls.fpid)) closeSession(key(ls.fpid, ls.sid));
        }
    }

    /** 全部会话快照（UI 展示在线状态用）。 */
    public List<LiveSession> sessionsSnapshot() {
        return new ArrayList<>(sessions.values());
    }

    // ---------- W1 核心资产库（App 原生模式接线） ----------

    /**
     * 资产库挂载（App 原生模式）：engine 创建后、login 前 attach 并挂 inbound 回灌钩子。
     *
     * <p>服务器模式下 App 不建本地会话（{@link #syncOnline()} 早退），资产摘要改由
     * {@code /v1/vault/state} 的 {@code srv:asset:*} 投影读取（服务端 SessionKeeper 写入）。
     */
    private void attachAssetLibrary(LiveSession ls) {
        if (ls == null || ls.engine == null || ls.assetLib != null) return;
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().attach(ls.engine, ls.fpid, ls.sid,
                    ls.engine.lastUid(), System.currentTimeMillis());
            if (lib == null) return;
            ls.assetLib = lib;
            ls.engine.addMessageHook((proto, content) -> {
                CoreAssetLibrary mine = ls.assetLib;
                if (mine != null) mine.accept(proto, content);   // 第一行：先入资产库
            });
            android.util.Log.i("SessionKeeper", "资产库已挂载 " + ls.fpid + ":" + ls.sid);
        } catch (Throwable ignored) {
        }
    }

    /** 离开 ONLINE：数据置 stale（保留最近数据供诊断；重连后 init 重放会重新 READY）。 */
    private static void invalidateAssetLibrary(LiveSession ls, String reason) {
        try {
            CoreAssetLibrary lib = ls == null ? null : ls.assetLib;
            if (lib != null) lib.invalidate(reason);
        } catch (Throwable ignored) {
        }
    }

    /** 会话关闭：invalidate + 从注册表 detach（先于 engine.close，避免 close 后还有迟到的钩子回调）。 */
    private static void detachAssetLibrary(LiveSession ls) {
        try {
            CoreAssetLibrary lib = ls == null ? null : ls.assetLib;
            ls.assetLib = null;
            if (lib == null) return;
            lib.invalidate("session-closed");
            CoreAssetRegistry.global().detach(lib);
        } catch (Throwable ignored) {
        }
    }

    public void closeSession(String k) {
        LiveSession ls = sessions.remove(k);
        if (ls != null) {
            detachAssetLibrary(ls);
            try {
                ls.engine.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只关闭指定角色的常驻会话（对齐原版 startOne 的 detachSessions(accountRoleId)）：
     *  fresh 登录某角色时不要连坐关闭同账号的兄弟角色会话。sid≤0 时 no-op。 */
    public void closeRoleSession(String fpid, long sid) {
        if (fpid == null || fpid.isEmpty() || sid <= 0) return;
        closeSession(key(fpid, sid));
    }

    public void closeSessionsNotIn(Set<String> keep) {
        for (String k : new ArrayList<>(sessions.keySet())) {
            if (keep == null || !keep.contains(k)) closeSession(k);
        }
    }

    static String key(String fpid, long sid) {
        return fpid + ":" + sid;
    }
}
