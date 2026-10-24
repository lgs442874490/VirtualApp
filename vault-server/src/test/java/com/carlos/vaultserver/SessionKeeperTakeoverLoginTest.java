package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-24 缺陷回归：脚本被顶号后点「上线」必须真正回连（旧逻辑对已存在会话直接返回 ok，
 * App toast「上线成功」但引擎仍停在 takeover，用户无法实际登录）。
 *
 * <p>覆盖：takeover → 手动 login 触发 {@code beginRecoveryNow} 并回连；recovering → 立即重连；
 * need_token → 明确失败原因；offline 启动前 → 幂等成功且不新建重复会话。
 */
public class SessionKeeperTakeoverLoginTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID = "35371154";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private final List<TakeoverTestEngine> engines = new ArrayList<>();
    private SessionKeeper keeper;

    /** 测试引擎：状态可外部切换；记录强制回连调用与消息钩子（不发任何网络请求）。 */
    static final class TakeoverTestEngine extends WjdrEngine {
        volatile String state = STATE_OFFLINE;
        volatile boolean online;
        volatile int recoveryRequests;
        volatile String lastRecoveryReason = "";
        /** true=每次 tryReconnect 都被对方顶回（模拟持续人玩互踢，用于强制回连预算测试）。 */
        volatile boolean kickAfterReconnect;
        /** 引擎消息钩子（SessionKeeper 挂载的 shield/guardian 等），测试直接投递推送。 */
        final List<java.util.function.BiConsumer<String, Map<String, Object>>> hooks = new ArrayList<>();

        TakeoverTestEngine() {
            super(null);
        }

        @Override
        public void addMessageHook(java.util.function.BiConsumer<String, Map<String, Object>> hook) {
            if (hook != null) hooks.add(hook);
        }

        void firePush(String proto, Map<String, Object> content) {
            for (java.util.function.BiConsumer<String, Map<String, Object>> h : hooks) {
                try {
                    h.accept(proto, content);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public long lastUid() {
            return Long.parseLong(UID);
        }

        @Override
        public void initialize() {
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) {
            online = true;
            state = STATE_ONLINE;
            return result();
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) {
            online = true;
            state = STATE_ONLINE;
            return result();
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return Long.parseLong(UID);
        }

        @Override
        public List<RoleInfo> discoverRoles() {
            RoleInfo ri = new RoleInfo();
            ri.serverid = 888L;
            ri.uid = Long.parseLong(UID);
            ri.name = "隆科多";
            return Arrays.asList(ri);
        }

        @Override
        public String sessionState() {
            return state;
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            return null;
        }

        @Override
        public String identityJson() {
            return "";
        }

        @Override
        public void applyIdentity(String settingsJson) {
        }

        @Override
        public String lastFailure() {
            return state;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }

        @Override
        public void close() {
            online = false;
            state = STATE_OFFLINE;
        }

        @Override
        public void beginRecoveryNow(String reason) {
            recoveryRequests++;
            lastRecoveryReason = reason == null ? "" : reason;
            state = STATE_RECOVERING;
        }

        @Override
        public boolean tryReconnect() {
            if (!STATE_RECOVERING.equals(state)) return false;
            if (kickAfterReconnect) {
                online = false;
                state = STATE_TAKEOVER;   // 抢回后立刻又被对方顶回
                return false;
            }
            online = true;
            state = STATE_ONLINE;
            return true;
        }

        private static LoginResult result() {
            LoginResult r = new LoginResult();
            r.serverId = 888L;
            r.uid = Long.parseLong(UID);
            r.gameName = "隆科多";
            r.level = 70;
            return r;
        }
    }

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(8), null, stateStore, () -> {
            TakeoverTestEngine e = new TakeoverTestEngine();
            engines.add(e);
            return e;
        }, true);
        syncAccount();
    }

    private VaultConfig config(int maxSessions) {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null, "human_first", maxSessions);
    }

    private void syncAccount() {
        syncAccount(null);
    }

    /** @param battleShieldJson 角色 battle_shield 参数（null=无）；用于 force_online 接线测试。 */
    private void syncAccount(String battleShieldJson) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk";
        a.activeSid = SID;
        a.activeUid = UID;
        if (battleShieldJson != null) {
            java.util.LinkedHashMap<String, String> rp = new java.util.LinkedHashMap<>();
            rp.put("battle_shield", battleShieldJson);
            a.roleParams.put(SID, rp);
        }
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(FPID, SID, UID);
    }

    /** 登录并把引擎驱动到 ONLINE（第一拍 startLogin → ONLINE，第二拍 run online 分支）。 */
    private TakeoverTestEngine loginOnline() {
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID, "login");
        assertTrue(r.accepted);
        keeper.tickForTest();   // offline → startLogin → ONLINE
        keeper.tickForTest();   // online 分支：挂载 help/shield/guardian
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
        assertFalse(engines.isEmpty());
        TakeoverTestEngine e = engines.get(0);
        assertTrue(e.online);
        return e;
    }

    @Test
    public void manualLogin_afterTakeover_forcesRecoveryAndComesBackOnline() {
        TakeoverTestEngine e = loginOnline();
        // 模拟被顶号（code=5 → engine takeover，会话仍在 sessions 里）
        e.online = false;
        e.state = WjdrEngine.STATE_TAKEOVER;
        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID);
        assertFalse("被顶号不是在线态，App 应显示可上线", rt.online);

        // 缺陷场景：点「上线」
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID, "login");
        assertEquals(0, r.code);
        assertTrue(r.accepted);
        assertEquals("必须触发一次强制回连", 1, e.recoveryRequests);
        assertTrue("回连原因应可读", e.lastRecoveryReason.contains("手动上线"));
        assertNotNull("响应 message 供 App toast 展示", r.message);
        assertTrue(r.message.contains("强制回连"));

        // 下一拍完成重连
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
    }

    @Test
    public void manualLogin_whileRecovering_doesNotWaitForBackoff() {
        TakeoverTestEngine e = loginOnline();
        e.online = false;
        e.state = WjdrEngine.STATE_RECOVERING;
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID, "login");
        assertEquals(0, r.code);
        assertTrue(r.accepted);
        assertEquals(1, e.recoveryRequests);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
    }

    @Test
    public void manualLogin_needToken_returnsActionableError() {
        TakeoverTestEngine e = loginOnline();
        e.state = WjdrEngine.STATE_NEED_TOKEN;
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID, "login");
        assertEquals(SessionControl.RoleOpResult.CODE_TOKEN_MISSING, r.code);
        assertTrue(r.message.contains("token"));
        assertEquals("token 失效不应乱触发回连", 0, e.recoveryRequests);
    }

    @Test
    public void manualLogin_beforeFirstTick_isIdempotentAndThenLogsIn() {
        SessionControl.RoleOpResult first = keeper.roleAction(FPID, SID, UID, "login");
        assertTrue(first.accepted);
        // 会话已建、引擎还没跑第一拍（offline）：再次上线必须幂等成功且不重复建会话
        SessionControl.RoleOpResult second = keeper.roleAction(FPID, SID, UID, "login");
        assertEquals(0, second.code);
        assertTrue(second.accepted);
        assertEquals("同一角色只能有一个会话", 1, engines.size());
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
    }

    @Test
    public void manualLogin_alreadyOnline_staysIdempotent() {
        TakeoverTestEngine e = loginOnline();
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID, "login");
        assertEquals(0, r.code);
        assertTrue(r.accepted);
        assertEquals("在线重复上线不应触发回连", 0, e.recoveryRequests);
    }

    // ---------- force_online（风险强制上线）与 SessionKeeper 接线 ----------

    /** 自己城被进攻的联盟态势推送（服务端 ServerShieldResponder 口径）。 */
    private static Map<String, Object> warPush(long ownUid) {
        Map<String, Object> atk = new java.util.LinkedHashMap<>();
        atk.put("targetid", ownUid);
        Map<String, Object> march = new java.util.LinkedHashMap<>();
        march.put("id", 13080L);
        march.put("owner", 49422287L);
        march.put("type", 2L);
        march.put("target_type", 1L);
        march.put("atk_city", atk);
        march.put("endtime", System.currentTimeMillis() / 1000L + 300L);
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("to_marchs", Arrays.asList(march));
        return content;
    }

    @Test
    public void forceOnline_riskAfterTakeover_reconnectsAutomatically() {
        syncAccount("{\"sub\":{\"force_online\":true}}");
        TakeoverTestEngine e = loginOnline();
        // 在线时收到攻击风险（推送 → 内存 pending）
        assertTrue("消息钩子应已挂载", e.hooks.size() > 0);
        e.firePush("notify_world_alliance_situation_add", warPush(Long.parseLong(UID)));
        // 随后被顶号：旧行为只会 30 分钟后探测；配置 force_online 应立刻抢回
        e.online = false;
        e.state = WjdrEngine.STATE_TAKEOVER;
        keeper.tickForTest();
        assertTrue("配置 force_online 后应自动强制回连", e.recoveryRequests >= 1);
        assertTrue(e.lastRecoveryReason.contains("风险强制上线"));
        // 下一拍完成重连
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
    }

    @Test
    public void forceOnline_defaultOff_keepsYieldBehavior() {
        TakeoverTestEngine e = loginOnline();
        e.firePush("notify_world_alliance_situation_add", warPush(Long.parseLong(UID)));
        e.online = false;
        e.state = WjdrEngine.STATE_TAKEOVER;
        keeper.tickForTest();
        assertEquals("默认关：不得替用户抢登（人玩优先）", 0, e.recoveryRequests);
        assertEquals(WjdrEngine.STATE_TAKEOVER, e.state);
    }

    /** 反射把强制回连节流清零（预算测试需要连续尝试，不等待真实 60s）。 */
    private void resetForceThrottle() {
        try {
            java.lang.reflect.Field sf = SessionKeeper.class.getDeclaredField("sessions");
            sf.setAccessible(true);
            Object ls = ((java.util.Map<?, ?>) sf.get(keeper)).get(FPID + "|" + SID + "|" + UID);
            java.lang.reflect.Field f = ls.getClass().getDeclaredField("lastForceLoginAt");
            f.setAccessible(true);
            f.setLong(ls, 0L);
        } catch (Throwable t) {
            throw new AssertionError("反射重置节流失败：" + t, t);
        }
    }

    @Test
    public void forceOnline_budgetExhausted_fallsBackToYieldToBreakKickLoop() {
        syncAccount("{\"sub\":{\"force_online\":true}}");
        TakeoverTestEngine e = loginOnline();
        e.firePush("notify_world_alliance_situation_add", warPush(Long.parseLong(UID)));
        e.kickAfterReconnect = true;   // 对方持续在玩：每次抢回立刻再被顶
        for (int i = 0; i < 6; i++) {
            e.online = false;
            e.state = WjdrEngine.STATE_TAKEOVER;
            resetForceThrottle();
            keeper.tickForTest();   // 强制回连（或预算耗尽后回退让位）
            keeper.tickForTest();   // RECOVERING → tryReconnect → 又被顶回
        }
        assertEquals("同一在线周期最多抢回 " + SessionKeeper.MAX_FORCE_LOGIN_ATTEMPTS + " 次",
                SessionKeeper.MAX_FORCE_LOGIN_ATTEMPTS, e.recoveryRequests);
        assertEquals("预算耗尽后停在让位态（不再互踢）", WjdrEngine.STATE_TAKEOVER, e.state);
    }

    @Test
    public void forceOnline_paused_resumesAndLogsIn() {
        syncAccount("{\"sub\":{\"force_online\":true}}");
        TakeoverTestEngine e = loginOnline();
        e.firePush("notify_world_alliance_situation_add", warPush(Long.parseLong(UID)));
        SessionControl.RoleOpResult paused = keeper.roleAction(FPID, SID, UID, "pause");
        assertEquals(VaultStore.RUN_PAUSED, paused.runState);
        assertFalse(keeper.roleRuntime(FPID, SID, UID).online);
        // 暂停期间：风险强制恢复 RUNNING（同一拍不登录，下一拍 OFFLINE → startLogin）
        keeper.tickForTest();
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID).runState);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID).online);
    }
}
