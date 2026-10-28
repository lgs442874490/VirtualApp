package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R2 Phase 2：整轮调度器按角色化（RoundScheduler）。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>{@code VAULT_PER_ROLE} off（默认）只调度 active 角色，行为与现网一致；</li>
 *   <li>on 时账号下全部角色各自排期/派单，内存键与状态键按角色 uid 隔离；</li>
 *   <li>{@code requestRun(RoleKey)} 精确到角色；旧 {@code requestRun(fpid)} = active 角色；</li>
 *   <li>{@code srv:supplyfire} 写角色键，旧账号键只由 active 角色镜像写；</li>
 *   <li>旧 KeeperFacade 实现（v2 默认方法）回退旧账号键路径，行为不变。</li>
 * </ul>
 */
public class RoundSchedulerPerRoleTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long UID_ACTIVE = 1001L;
    private static final long UID_SIBLING = 2002L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore vault;
    private StateStore state;
    private FakeKeeper keeper;

    /** 假会话层：记录按角色提交；rolesOf/activeRole 按 Phase 2 接口实现。 */
    static final class FakeKeeper implements RoundScheduler.KeeperFacade {
        final List<RoleKey> roles = new ArrayList<>();
        final Set<String> online = new HashSet<>();
        final List<RoleKey> submitted = new ArrayList<>();
        final List<String> fns = new ArrayList<>();
        final List<String> legacySubmitted = new ArrayList<>();
        RoleKey active;

        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
            legacySubmitted.add(fpid + "|" + fn);
            return true;
        }

        @Override
        public boolean submitRound(RoleKey role, String fn, String trigger) {
            submitted.add(role);
            fns.add(fn);
            return true;
        }

        @Override
        public long sessionSid(String fpid) {
            for (RoleKey r : roles) {
                if (r.fpid.equals(fpid)) return r.sid;
            }
            return 0;
        }

        @Override
        public boolean sessionOnline(String fpid) {
            for (RoleKey r : roles) {
                if (r.fpid.equals(fpid) && online.contains(r.key())) return true;
            }
            return false;
        }

        @Override
        public boolean sessionOnline(RoleKey role) {
            return role != null && online.contains(role.key());
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }

        @Override
        public List<RoleKey> rolesOf(String fpid) {
            List<RoleKey> out = new ArrayList<>();
            for (RoleKey r : roles) {
                if (r.fpid.equals(fpid)) out.add(r);
            }
            return out;
        }

        @Override
        public RoleKey activeRole(String fpid) {
            return active != null && active.fpid.equals(fpid) ? active : null;
        }

        @Override
        public boolean wartimeYield(RoleKey role) {
            return false;
        }
    }

    /** 旧 KeeperFacade 实现（只实现 v1 抽象方法），用于回退路径回归。 */
    static final class LegacyKeeper implements RoundScheduler.KeeperFacade {
        final List<String> submitted = new ArrayList<>();

        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
            submitted.add(fpid + "|" + fn);
            return true;
        }

        @Override
        public long sessionSid(String fpid) {
            return SID;
        }

        @Override
        public boolean sessionOnline(String fpid) {
            return true;
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }
    }

    @Before
    public void setUp() {
        vault = new VaultStore(tmp.getRoot().toPath());
        vault.load();
        state = new StateStore(tmp.getRoot().toPath());
        keeper = new FakeKeeper();
        keeper.roles.add(new RoleKey(FPID, SID, UID_ACTIVE));
        keeper.roles.add(new RoleKey(FPID, SID, UID_SIBLING));
        keeper.active = keeper.roles.get(0);
        keeper.online.add(keeper.roles.get(0).key());
        keeper.online.add(keeper.roles.get(1).key());
    }

    private void seed(boolean auto) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = auto;
        a.windowStart = 0;
        a.windowEnd = 0;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    private long num(String key) {
        Object v = state.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1L;
    }

    @Test
    public void perRoleOff_onlyActiveRoleDispatched() {
        seed(true);
        RoundScheduler s = new RoundScheduler(vault, state, keeper, false);

        s.tick();   // 排期（窗口结束时刻已过 → 立即补跑）
        s.tick();   // 派单

        assertEquals(1, keeper.submitted.size());
        assertEquals(UID_ACTIVE, keeper.submitted.get(0).uid);
        assertNull("整轮提交 fn=null", keeper.fns.get(0));
    }

    @Test
    public void perRoleOn_allRolesDispatchedIndependently() {
        seed(true);
        RoundScheduler s = new RoundScheduler(vault, state, keeper, true);

        s.tick();
        s.tick();

        assertEquals(2, keeper.submitted.size());
        Set<Long> uids = new HashSet<>(Arrays.asList(
                keeper.submitted.get(0).uid, keeper.submitted.get(1).uid));
        assertEquals(new HashSet<>(Arrays.asList(UID_ACTIVE, UID_SIBLING)), uids);
    }

    @Test
    public void requestRunRole_targetsExactRole() {
        seed(false);
        RoundScheduler s = new RoundScheduler(vault, state, keeper, true);
        RoleKey sibling = new RoleKey(FPID, SID, UID_SIBLING);

        assertTrue(s.requestRun(sibling, "supply"));
        s.tick();

        assertEquals(1, keeper.submitted.size());
        assertEquals(UID_SIBLING, keeper.submitted.get(0).uid);
        assertEquals("supply", keeper.fns.get(0));
        assertEquals(0, keeper.legacySubmitted.size());
    }

    @Test
    public void requestRunCompat_targetsActiveRole() {
        seed(false);
        RoundScheduler s = new RoundScheduler(vault, state, keeper, true);

        assertTrue(s.requestRun(FPID, null));
        s.tick();

        assertEquals(1, keeper.submitted.size());
        assertEquals(UID_ACTIVE, keeper.submitted.get(0).uid);
        assertNull(keeper.fns.get(0));
    }

    @Test
    public void supplyFired_perRoleAndActiveMirror() {
        seed(false);
        long nowSec = System.currentTimeMillis() / 1000L;
        long siblingOnlineTs = nowSec - 60L;
        long siblingWake = siblingOnlineTs * 1000L;
        // 兄弟角色：角色级 mod 状态 → 只写角色级 supplyfire 键
        state.putString("mod:111:913:2002:supply", "{\"online_ts\":" + siblingOnlineTs + "}");
        RoundScheduler s = new RoundScheduler(vault, state, keeper, true);

        s.tick();

        assertEquals(1, keeper.submitted.size());
        assertEquals(UID_SIBLING, keeper.submitted.get(0).uid);
        assertEquals("supply", keeper.fns.get(0));
        assertEquals(siblingWake, num("srv:supplyfire:111:913:2002"));
        assertNull("非 active 角色不得镜像旧键", state.snapshot().get("srv:supplyfire:111"));

        // active 角色：角色键缺失 → 回退旧 mod 键（迁移兼容），并镜像旧 supplyfire 键
        long activeOnlineTs = nowSec - 30L;
        long activeWake = activeOnlineTs * 1000L;
        state.putString("mod:111:913:supply", "{\"online_ts\":" + activeOnlineTs + "}");
        s.tick();

        assertEquals(2, keeper.submitted.size());
        assertEquals(UID_ACTIVE, keeper.submitted.get(1).uid);
        assertEquals(activeWake, num("srv:supplyfire:111:913:1001"));
        assertEquals(activeWake, num("srv:supplyfire:111"));
    }

    @Test
    public void legacyKeeper_fallsBackToAccountKeys() {
        seed(true);
        LegacyKeeper legacy = new LegacyKeeper();
        RoundScheduler s = new RoundScheduler(vault, state, legacy, false);

        s.tick();
        s.tick();

        assertEquals(1, legacy.submitted.size());
        assertEquals(FPID + "|null", legacy.submitted.get(0));
        // 旧实现没有角色键能力：状态仍走旧账号键，不产生角色键
        assertNull(state.snapshot().get("srv:lastrun:111:913"));
        assertFalse(keeper.submitted.size() > 0);
    }

    @Test
    public void perRoleEnabled_envDefaultIsOff() {
        // 未设置 VAULT_PER_ROLE 时默认 off（现网行为）；on/1/true 才切换。
        if (System.getenv(RoundScheduler.ENV_PER_ROLE) == null) {
            assertFalse(RoundScheduler.perRoleEnabled());
        }
    }

    @Test
    public void nextRunAt_isNotSharedAcrossRoles() {
        seed(true);
        RoundScheduler s = new RoundScheduler(vault, state, keeper, true);
        s.tick();   // 双角色各自排期

        // 让 active 角色“今天已跑” → 只有兄弟角色派单，证明排期键按角色隔离
        state.putLong("srv:lastrun:111:913:1001", System.currentTimeMillis());
        s.tick();

        assertEquals(1, keeper.submitted.size());
        assertEquals(UID_SIBLING, keeper.submitted.get(0).uid);
        assertNotNull(state.snapshot().get("srv:lastrun:111:913:1001"));
    }
}
