package com.carlos.vaultserver;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private final VaultStore store;
    private final VaultConfig config;
    private final AssetSource assets;
    private final StateStore stateStore;
    /** 角色会话表：key = {@link #sessionKey}(fpid,sid,uid)（M1d 多角色并行容器）。 */
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final EngineFactory engineFactory;
    /** true = 会话节拍由测试手动驱动（tickForTest），不起后台线程。 */
    private final boolean manualTick;
    private volatile boolean stopped;
    /** 角色动作串行化锁（P0 规模下全局锁粒度足够，杜绝多角色并发动作竞态）。 */
    private final Object actionLock = new Object();

    /** 角色会话键：fpid|sid|uid（uid 数字规范化，缺省空串——旧数据无 uid 的会话槽）。 */
    static String sessionKey(String fpid, String sid, String uid) {
        long u = parseUid(uid);
        return fpid + "|" + (sid == null ? "" : sid) + "|" + (u > 0 ? String.valueOf(u) : "");
    }

    private static String norm(String s) {
        return s == null ? "" : s;
    }

    public SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore) {
        this(store, config, assets, stateStore, null, false);
    }

    SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore,
                  EngineFactory engineFactory, boolean manualTick) {
        this.store = store;
        this.config = config;
        this.assets = assets;
        this.stateStore = stateStore;
        this.engineFactory = engineFactory;
        this.manualTick = manualTick;
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
        /** 会话级运行态：RUNNING（默认）/ PAUSED（任务暂停、连接保留）。销毁即 STOPPED，不存于此。 */
        volatile String runState = VaultStore.RUN_RUNNING;
        /** 让位/顶号探测到期时刻（takeover 倒计时回传 App；0=未知）。 */
        volatile long yieldProbeAt;
        final WjdrEngine engine;
        final ScheduledExecutorService exec;
        /** 互助自动响应（登录成功后按真实 sid 惰性挂载）。 */
        volatile ServerHelpResponder help;
        /** 反应式开盾（WAR/RECON 推送驱动；登录成功后按真实 sid 惰性挂载）。 */
        volatile ServerShieldResponder shield;
        /** 下次全量采集时刻（登录后 3s 首采，之后每 10 分钟）。 */
        volatile long nextCaptureAt;
        volatile long stateSince = System.currentTimeMillis();
        volatile String state = WjdrEngine.STATE_OFFLINE;
        /** 登录学到的角色元数据（/status 回传 App 角色卡）。 */
        volatile String roleName = "";
        volatile long roleLevel;
        volatile long rolePower;
        volatile String roleAlliance = "";

        LiveSession(VaultStore.Account acc) {
            this(acc, null, null, VaultStore.RUN_RUNNING);
        }

        /** 显式角色绑定（账号池 M1）：sid/uid 非 null 时覆盖账号库 active 值。 */
        LiveSession(VaultStore.Account acc, String roleSid, String roleUid, String initialRunState) {
            this.fpid = acc.fpid;
            this.token = acc.token;
            this.sid = norm(roleSid != null ? roleSid : acc.activeSid);
            this.uidArg = roleUid != null ? roleUid : acc.activeUid;
            this.wantUid = parseUid(this.uidArg);
            this.key = sessionKey(fpid, sid, this.uidArg);
            this.wantOnline = true;
            this.runState = VaultStore.RUN_PAUSED.equals(initialRunState)
                    ? VaultStore.RUN_PAUSED : VaultStore.RUN_RUNNING;
            this.engine = engineFactory != null ? engineFactory.create() : new WjdrEngine(assets);
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-" + fpid
                        + (wantUid > 0 ? "-" + wantUid : "-" + sid));
                t.setDaemon(true);
                return t;
            });
            engine.setStateListener(s -> {
                state = s;
                stateSince = System.currentTimeMillis();
                // 引擎自发顶号（code=5）无外部指令时刻可读：按默认探测间隔给 App 倒计时
                if (WjdrEngine.STATE_TAKEOVER.equals(s) && yieldProbeAt == 0) {
                    yieldProbeAt = System.currentTimeMillis() + TAKEOVER_PROBE_DEFAULT_MILLIS;
                }
                if (WjdrEngine.STATE_ONLINE.equals(s)) yieldProbeAt = 0;
            });
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
            String st = engine.sessionState();
            boolean paused = VaultStore.RUN_PAUSED.equals(runState);
            switch (st) {
                case WjdrEngine.STATE_OFFLINE:
                    startLogin();
                    break;
                case WjdrEngine.STATE_ONLINE:
                    engine.heartbeatTick();
                    // 暂停：保持连接/心跳（P0 简化），停采集/互助/开盾等任务执行
                    if (paused) break;
                    // 同 fpid 多会话（M1d）：App 消费的 state 键（res:/todo:/mod:… 均为
                    // fpid:sid 键型，/state 契约不变）只由 active 角色会话写入——
                    // 兄弟角色会话保持「占位在线」（连接/心跳/重连），不执行任务，
                    // 避免同区服多角色互相覆盖采集状态。
                    if (!isActiveRoleWriter()) break;
                    // 互助/开盾惰性挂载（成为写入方才挂；按真实 sid，默认角色登录时固化实际区服）
                    if (help == null) {
                        help = new ServerHelpResponder(fpid, resolveSid(), stateStore);
                        help.attach(engine);
                    }
                    if (shield == null) {
                        shield = new ServerShieldResponder(fpid, resolveSid(), stateStore, store);
                        shield.attach(engine);
                    }
                    // 反应式开盾优先于采集/互助：攻击行军按分钟计，开盾早一秒是一秒
                    ServerShieldResponder s = shield;
                    if (s != null) s.tick(engine);
                    ServerHelpResponder h = help;
                    long now = System.currentTimeMillis();
                    if (now >= nextCaptureAt) {
                        long captureSid = resolveSid();
                        String err = CaptureTasks.captureAll(engine, stateStore, fpid, captureSid);
                        if (err != null) FileLog.warn("采集失败 fpid={}：{}", fpid, err);
                        nextCaptureAt = System.currentTimeMillis() + CaptureTasks.CAPTURE_INTERVAL_MILLIS;
                        stateStore.flush();
                    }
                    if (h != null) h.tick(engine);
                    break;
                case WjdrEngine.STATE_RECOVERING:
                    engine.tryReconnect();
                    break;
                case WjdrEngine.STATE_TAKEOVER:
                    if (engine.takeoverProbeDue(System.currentTimeMillis())) {
                        engine.beginRecoveryNow("让位探测到期，回连");
                    }
                    break;
                default:
                    // logging：登录进行中；need_token：等 token 补给（token API 触发重启）
            }
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
            } catch (Throwable t) {
                // 引擎状态机已分类（退避重试 / need_token），这里只留痕
                FileLog.warn("登录失败 {}：{}", key,
                        t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            }
        }

        void shutdown() {
            wantOnline = false;
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
                    ls = new LiveSession(a, d.sid, d.uid, d.runState);
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
                    LiveSession fresh = new LiveSession(a, d.sid, d.uid, d.runState);
                    sessions.put(key, fresh);
                    fresh.start();
                }
            }
        }
    }

    /**
     * 账号的期望角色集合（快照上推导，不改库）：
     * desiredOnline=true 的全部角色（同 fpid 可多个，M1d 多角色并行恢复）；
     * 无任何 desired 条目且 keep=true、activeUid 缺省（旧数据/旧客户端，无 uid 概念）
     * 回落 active 角色单会话 —— uid 明确的账号一律以 desiredOnline 为准
     * （显式动作优先于 keep，logout/stop 不被 keep 复活）。
     */
    private static List<VaultStore.RoleEntry> desiredRolesOf(VaultStore.Account a) {
        List<VaultStore.RoleEntry> out = new ArrayList<>();
        for (VaultStore.RoleEntry r : a.roles.values()) {
            if (r.desiredOnline) out.add(r);
        }
        if (out.isEmpty() && a.keep && parseUid(a.activeUid) == 0) {
            out.add(new VaultStore.RoleEntry(a.activeSid == null ? "" : a.activeSid, null));
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
                default:
                    return RoleOpResult.fail(3002, "未知 action: " + action);
            }
        }
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
                store.applyRoleRunState(fpid, role.sid, role.uid, VaultStore.RUN_RUNNING);
            }
            return RoleOpResult.ok(VaultStore.RUN_RUNNING, stopped);
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

    private RoleOpResult pauseRoleLocked(String fpid, String sid, String uid) {
        if (fpid == null || fpid.isEmpty()) return RoleOpResult.fail(3002, "缺少 fpid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) return RoleOpResult.fail(1004, "账号不存在: " + fpid);
        LiveSession ls = sessions.get(sessionKey(fpid, sid, uid));
        if (ls == null || !ls.wantOnline || !bindsRole(ls, sid, uid)) {
            return RoleOpResult.fail(RoleOpResult.CODE_ROLE_NOT_ONLINE, "角色未在线，无法暂停");
        }
        if (VaultStore.RUN_PAUSED.equals(ls.runState)) {
            return RoleOpResult.ok(VaultStore.RUN_PAUSED);   // 幂等
        }
        ls.runState = VaultStore.RUN_PAUSED;
        store.applyRoleRunState(fpid, sid, uid, VaultStore.RUN_PAUSED);
        FileLog.info("角色暂停 {}（连接保留，任务停止）", ls.key);
        return RoleOpResult.ok(VaultStore.RUN_PAUSED);
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
        store.applyRoleRunState(fpid, sid, uid, VaultStore.RUN_RUNNING);
        FileLog.info("角色恢复 {}", ls.key);
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

    /** 提交整轮（fn=null）或单任务到该账号 active 角色会话线程；返回是否受理。 */
    public boolean submitRound(String fpid, String fn, String trigger) {
        LiveSession ls = sessionForAccount(fpid);
        if (ls == null || !ls.wantOnline) return false;
        if (!VaultStore.RUN_RUNNING.equals(ls.runState)) return false;   // 暂停：不执行任务
        ls.exec.execute(() -> {
            try {
                if (!WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                    FileLog.warn("整轮跳过 fpid={}（会话不在线）", fpid);
                    return;
                }
                VaultStore.Account acc = store.find(fpid);
                if (acc == null) return;
                long sid = ls.resolveSid();
                java.util.Map<String, org.json.JSONObject> params = new java.util.LinkedHashMap<>();
                java.util.Map<String, String> raw = acc.roleParams.get(String.valueOf(sid));
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
                if ("1".equals(System.getenv("VAULT_LEGACY_ROUND"))) {
                    com.carlos.vaultserver.task.RoundRunner.runRound(
                            ls.engine, acc, sid, stateStore, params, fn, trigger);
                } else {
                    com.carlos.vaultserver.task.ModularRoundRunner.runRound(
                            ls.engine, acc, sid, stateStore, params, fn, trigger);
                }
            } catch (Throwable t) {
                FileLog.warn("整轮异常 fpid={}：{}", fpid, String.valueOf(t));
            }
        });
        return true;
    }

    /** 账号当前 active 角色会话 sid（无会话 0）。 */
    public long sessionSid(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls == null ? 0 : ls.resolveSid();
    }

    /** 账号 active 角色会话是否在线。 */
    public boolean sessionOnline(String fpid) {
        LiveSession ls = sessionForAccount(fpid);
        return ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState());
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
}
