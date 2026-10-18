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
 * 常驻会话管理器：每账号一条会话（active 角色，v3.9.1 单会话语义）。
 *
 * 每会话独立单线程执行器跑 1 秒节拍（scheduleWithFixedDelay 天然防重入）：
 * offline → 发起登录；online → 心跳；recovering → 到点重连；takeover → 到点探测。
 * 引擎内部的退避/被踢冷却/顶号策略（WjdrEngine）原样生效，与 App 端行为一致。
 */
public final class SessionKeeper implements SessionControl,
        com.carlos.vaultserver.task.RoundScheduler.KeeperFacade {

    private final VaultStore store;
    private final VaultConfig config;
    private final AssetSource assets;
    private final StateStore stateStore;
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public SessionKeeper(VaultStore store, VaultConfig config, AssetSource assets, StateStore stateStore) {
        this.store = store;
        this.config = config;
        this.assets = assets;
        this.stateStore = stateStore;
        // 引擎静态读策略 → 账号库当前值（policy API 可在线改）
        WjdrEngine.setTakeoverPolicySource(store::policy);
    }

    private final class LiveSession {
        final String fpid;
        volatile String token;
        volatile String sid;
        volatile boolean wantOnline;
        final WjdrEngine engine = new WjdrEngine(assets);
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
            this.fpid = acc.fpid;
            this.token = acc.token;
            this.sid = acc.activeSid;
            this.wantOnline = true;
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-" + fpid);
                t.setDaemon(true);
                return t;
            });
            engine.setStateListener(s -> {
                state = s;
                stateSince = System.currentTimeMillis();
            });
        }

        void start() {
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
            switch (st) {
                case WjdrEngine.STATE_OFFLINE:
                    startLogin();
                    break;
                case WjdrEngine.STATE_ONLINE:
                    engine.heartbeatTick();
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
         */
        WjdrEngine.LoginResult loginBoundRole(VaultStore.Account acc) throws Exception {
            long targetKid = resolveSid();
            if (targetKid <= 0) {
                return engine.login(fpid, acc.token, null);
            }
            String cacheKey = "srv:roleuid:" + fpid + ":" + targetKid;
            String cached = stateStore.getString(cacheKey);
            long roleUid = 0;
            try {
                roleUid = cached == null || cached.isEmpty() ? 0 : Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
            }
            if (roleUid <= 0) {
                engine.login(fpid, acc.token, null);   // 默认角色上线（仅为拉角色清单）
                Long found = engine.discoverRoleUid(targetKid);
                if (found == null || found <= 0) {
                    FileLog.warn("目标区服 k{} 无角色，按默认角色上线 fpid={}", targetKid, fpid);
                    return engine.login(fpid, acc.token, null);
                }
                roleUid = found;
                stateStore.putString(cacheKey, String.valueOf(roleUid));
                stateStore.flush();
                FileLog.info("发现角色映射 fpid={} k{}→uid={}", fpid, targetKid, roleUid);
                // 二次 loginauth 必须走全新连接（旧已认证连接上再发 loginauth 会超时）
                engine.initialize();
            }
            WjdrEngine.LoginResult r = engine.loginByRole(fpid, acc.token, roleUid);
            if (r.uid != roleUid) {
                // 绑定未生效（角色被删/清单过期）：清缓存按实际角色上线
                FileLog.warn("角色绑定失败 fpid={} 期望uid={} 实际uid={}，清缓存",
                        fpid, String.valueOf(roleUid), String.valueOf(r.uid));
                stateStore.putString(cacheKey, "");
                stateStore.flush();
            }
            return r;
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
                String idKey = "srv:uid:" + fpid + ":" + resolveSid();
                String idnKey = "srv:identity:" + fpid + ":" + resolveSid();
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

        /** 首次登录：初始化 → 注入指纹 → 登录（按 activeSid 绑定目标角色）→ 固化"学到"的设备身份。 */
        void startLogin() {
            VaultStore.Account acc = store.find(fpid);
            if (acc == null || acc.token == null || acc.token.isEmpty()) return;
            try {
                engine.setProxy(resolveProxy(acc));
                engine.initialize();
                String seed = identitySeed(acc);
                if (seed != null && !seed.isEmpty()) engine.applyIdentity(seed);
                WjdrEngine.LoginResult r = loginBoundRole(acc);
                FileLog.info("登录成功 fpid={} sid={} uid={} 角色={}",
                        fpid, String.valueOf(sid), String.valueOf(r.uid),
                        r.gameName == null ? "" : r.gameName);
                roleName = r.gameName == null ? "" : r.gameName;
                roleLevel = r.level;
                rolePower = r.power;
                roleAlliance = r.alliance == null ? "" : r.alliance;
                verifyIdentity(r.uid);
                String learned = engine.identityJson();
                if (!learned.isEmpty()) store.learnIdentity(fpid, sid, learned);
                // 互助挂载（按真实 sid；默认角色登录时固化实际区服）+ 排首次采集
                if (sid == null && r.serverId > 0) sid = String.valueOf(r.serverId);
                if (help == null) {
                    help = new ServerHelpResponder(fpid, resolveSid(), stateStore);
                    help.attach(engine);
                }
                if (shield == null) {
                    shield = new ServerShieldResponder(fpid, resolveSid(), stateStore, store);
                    shield.attach(engine);
                }
                nextCaptureAt = System.currentTimeMillis() + 3000;
            } catch (Throwable t) {
                // 引擎状态机已分类（退避重试 / need_token），这里只留痕
                FileLog.warn("登录失败 fpid={}：{}", fpid,
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
        if (stopped) return;
        List<VaultStore.Account> accs = store.snapshot();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (VaultStore.Account a : accs) {
            if (a.keep && a.token != null && !a.token.isEmpty()) wanted.add(a.fpid);
        }
        // 停掉不再常驻/已删除的账号会话（已删除的顺带清理采集状态）
        java.util.Set<String> known = new java.util.HashSet<>();
        for (VaultStore.Account a : accs) known.add(a.fpid);
        for (Map.Entry<String, LiveSession> e : sessions.entrySet()) {
            if (!wanted.contains(e.getKey())) {
                FileLog.info("停止会话 fpid={}（未开常驻或已删除）", e.getKey());
                e.getValue().shutdown();
                sessions.remove(e.getKey());
                if (!known.contains(e.getKey())) {
                    stateStore.removeAccount(e.getKey());
                    stateStore.flush();
                }
            }
        }
        // 确保常驻账号有会话；token/角色变化 → 重启会话
        for (VaultStore.Account a : accs) {
            if (!wanted.contains(a.fpid)) continue;
            LiveSession ls = sessions.get(a.fpid);
            if (ls == null) {
                ls = new LiveSession(a);
                sessions.put(a.fpid, ls);
                FileLog.info("建会话 fpid={} activeSid={} 代理={}", a.fpid,
                        String.valueOf(a.activeSid), String.valueOf(resolveProxy(a)));
                ls.start();
            } else if (!ls.wantOnline) {
                ls.wantOnline = true;
            } else if (!Objects.equals(ls.token, a.token) || !Objects.equals(ls.sid, a.activeSid)) {
                FileLog.info("token/角色变化，重启会话 fpid={}", a.fpid);
                ls.shutdown();
                sessions.remove(a.fpid);
                LiveSession fresh = new LiveSession(a);
                sessions.put(a.fpid, fresh);
                fresh.start();
            }
        }
    }

    /** token 更新入口（token API 调用）：有会话则交给 reconcile 比对重启。 */
    public void onTokenUpdated(String fpid) {
        reconcile();
    }

    // ---------- 整轮调度面（RoundScheduler / policy run 使用） ----------

    /**
     * 按需建会话并开始登录（账号页“登录并运行自动化”/手动触发用）。
     * 与 reconcile 的区别：不要求账号 keep=true——手动登录是一次显式请求，
     * 服务器临时保活该会话，跑完仍由统一开关决定是否常驻。
     */
    public boolean ensureSession(String fpid) {
        if (stopped) return false;
        VaultStore.Account acc = store.find(fpid);
        if (acc == null || acc.token == null || acc.token.isEmpty()) return false;
        // 显式登录即开启该账号的统一自动化，避免下一次 reconcile 把会话停掉。
        store.setAutomation(fpid, true);
        acc = store.find(fpid);
        LiveSession ls = sessions.get(fpid);
        if (ls == null) {
            ls = new LiveSession(acc);
            sessions.put(fpid, ls);
            FileLog.info("按需建会话 fpid={} activeSid={}", fpid, String.valueOf(acc.activeSid));
            ls.start();
        }
        ls.wantOnline = true;
        return true;
    }

    /** 提交整轮（fn=null）或单任务到该账号会话线程；返回是否受理。 */
    public boolean submitRound(String fpid, String fn, String trigger) {
        LiveSession ls = sessions.get(fpid);
        if (ls == null || !ls.wantOnline) return false;
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

    /** 账号当前会话 sid（无会话 0）。 */
    public long sessionSid(String fpid) {
        LiveSession ls = sessions.get(fpid);
        return ls == null ? 0 : ls.resolveSid();
    }

    /** 账号会话是否在线。 */
    public boolean sessionOnline(String fpid) {
        LiveSession ls = sessions.get(fpid);
        return ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState());
    }

    @Override
    public boolean yield(String fpid, long probeMillis) {
        LiveSession ls = sessions.get(fpid);
        if (ls == null) return store.find(fpid) != null;
        ls.engine.beginYield(probeMillis, "App 端进游戏，服务器让位（" +
                (probeMillis / 60000L) + " 分钟后探测回连）");
        FileLog.info("让位指令 fpid={}，{}ms 后回连", fpid, String.valueOf(probeMillis));
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
            LiveSession ls = sessions.get(a.fpid);
            if (ls != null) {
                s.state = ls.engine.sessionState();
                s.lastFailure = ls.engine.lastFailure();
                s.sid = ls.sid;
                s.roleName = ls.roleName;
                s.roleLevel = ls.roleLevel;
                s.rolePower = ls.rolePower;
                s.roleAlliance = ls.roleAlliance;
                s.sinceTs = ls.stateSince;
            } else {
                s.state = a.keep ? WjdrEngine.STATE_OFFLINE : "offline";
                s.lastFailure = a.keep && !s.hasToken ? "缺少 token" : null;
                s.sid = a.activeSid;
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

    /** 指纹种子：active 角色的指纹优先，缺则账号级（对齐 App 端 roleIdentitySeed 语义）。 */
    private String identitySeed(VaultStore.Account a) {
        if (a.activeSid != null) {
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
