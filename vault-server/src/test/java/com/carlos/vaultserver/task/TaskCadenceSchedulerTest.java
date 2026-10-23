package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.automation.ModuleRegistry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 任务节奏调度（网页版 TaskEngine 1:1 语义的服务端最小实现）：
 * - 只给 auto=true 且会话在线的账号派单；
 * - 每 tick 只派 1 个“最久未到点”的已启用任务，写入下次时间（网页版间隔）；
 * - 同账号最小提交间隔、会话忙/战时/时间窗/暂停均不派单；
 * - 未受理时 60 秒重试，不吞间隔。
 */
public class TaskCadenceSchedulerTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private FakeKeeper keeper;
    private TaskCadenceScheduler scheduler;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        keeper = new FakeKeeper();
        scheduler = new TaskCadenceScheduler(store, stateStore, keeper,
                registry(), false, new Random(42L));
    }

    private static ModuleRegistry registry() {
        ModuleRegistry r = Modules.registry();
        r.validate();
        return r;
    }

    private VaultStore.Account seed(boolean auto, Map<String, Boolean> roleConfig) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = auto;
        a.activeSid = String.valueOf(SID);
        if (roleConfig != null) a.roleConfig.put(String.valueOf(SID), roleConfig);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        keeper.online.add(FPID);
        keeper.sids.put(FPID, SID);
        return store.find(FPID);
    }

    private static long bj(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long nextAt(String fn) {
        Object v = stateStore.snapshot().get("srv:cad:" + FPID + ":" + SID + ":" + fn);
        assertNotNull("缺少节奏状态键 " + fn, v);
        return ((Number) v).longValue();
    }

    // ---------- 主路径 ----------

    @Test
    public void dispatchesMostOverdueEnabledTask_andSchedulesNext() {
        seed(true, null);
        long now = bj(2026, 10, 23, 6, 0);

        scheduler.tick(now);

        assertEquals("首轮应派第一个到点的默认任务", 1, keeper.submitted.size());
        String fn = keeper.submitted.get(0);
        assertTrue("应派已启用的奖励类任务，实际=" + fn, TaskCadence.contains(fn));
        // login_rewards 间隔 300s，抖动 ±10%
        long nxt = nextAt(fn);
        long interval = TaskCadence.intervalSeconds(fn) * 1000L;
        assertTrue("下次时间应在 [0.9,1.1] 间隔内：next-now=" + (nxt - now),
                nxt >= now + (long) (interval * 0.9d) && nxt <= now + (long) (interval * 1.1d));
        assertEquals(now, ((Number) stateStore.snapshot().get("srv:cadlast:" + FPID)).longValue());
    }

    @Test
    public void respectsMinimumDispatchGap() {
        seed(true, null);
        long now = bj(2026, 10, 23, 6, 0);

        scheduler.tick(now);
        assertEquals(1, keeper.submitted.size());
        scheduler.tick(now + 1_000L);        // 最小间隔 3s 内不派
        assertEquals(1, keeper.submitted.size());
        scheduler.tick(now + 4_000L);        // 过了间隔，派下一个到点任务
        assertEquals(2, keeper.submitted.size());
        assertFalse("同一任务不应连续两拍派发",
                keeper.submitted.get(0).equals(keeper.submitted.get(1)));
    }

    // ---------- 闸门 ----------

    @Test
    public void skipsWhenAutoOff() {
        seed(false, null);
        scheduler.tick(bj(2026, 10, 23, 6, 0));
        assertTrue(keeper.submitted.isEmpty());
    }

    @Test
    public void skipsWhenSessionOffline() {
        seed(true, null);
        keeper.online.clear();
        scheduler.tick(bj(2026, 10, 23, 6, 0));
        assertTrue(keeper.submitted.isEmpty());
    }

    @Test
    public void skipsWhenExecutorBusy() {
        seed(true, null);
        keeper.busy = true;
        scheduler.tick(bj(2026, 10, 23, 6, 0));
        assertTrue(keeper.submitted.isEmpty());
    }

    @Test
    public void skipsDuringWartimeYield() {
        seed(true, null);
        keeper.war = true;
        scheduler.tick(bj(2026, 10, 23, 6, 0));
        assertTrue(keeper.submitted.isEmpty());
    }

    @Test
    public void unacceptedRetriesAfterOneMinute() {
        seed(true, null);
        keeper.accept = false;
        long now = bj(2026, 10, 23, 6, 0);
        scheduler.tick(now);
        assertEquals(1, keeper.submitted.size());
        // 未受理：下次时间 = now + 60s
        long nxt = nextAt(keeper.submitted.get(0));
        assertEquals(now + TaskCadenceScheduler.RETRY_MILLIS, nxt);
    }

    @Test
    public void windowModeHonoursAccountWindow() {
        VaultStore.Account a = seed(true, null);
        a.windowStart = 5;
        a.windowEnd = 9;
        TaskCadenceScheduler windowed = new TaskCadenceScheduler(store, stateStore, keeper,
                registry(), true, new Random(7L));

        windowed.tick(bj(2026, 10, 23, 3, 0));   // 窗外（北京时间 03:00）
        assertTrue("时间窗外不应派单", keeper.submitted.isEmpty());
        windowed.tick(bj(2026, 10, 23, 6, 0));   // 窗内
        assertFalse("时间窗内应派单", keeper.submitted.isEmpty());
    }

    // ---------- 配置口径 ----------

    @Test
    public void roleConfigOverridesDefaultsForCadence() {
        Map<String, Boolean> rc = new HashMap<>();
        rc.put("mail", false);       // 默认 true → 关
        rc.put("gather", true);      // 默认 false → 开
        VaultStore.Account a = seed(true, rc);

        List<String> enabled = scheduler.enabledTaskIds(a, SID);
        assertFalse("roleConfig 关闭的邮件不应进入节奏", enabled.contains("mail"));
        assertTrue("roleConfig 开启的采集应进入节奏", enabled.contains("gather"));
        assertTrue("未配置的默认任务照旧启用", enabled.contains("supply"));
    }

    @Test
    public void newSessionSidStartsFreshCadence() {
        seed(true, null);
        scheduler.tick(bj(2026, 10, 23, 6, 0));
        String fn = keeper.submitted.get(0);
        long firstNext = nextAt(fn);

        keeper.sids.put(FPID, 889L);               // 切角色/重登到另一个 sid
        long now2 = firstNext + 1_000L;
        scheduler.tick(now2);                      // 新 sid 无状态 → 立即补一轮
        assertEquals(2, keeper.submitted.size());
    }

    @Test
    public void initGraceBlocksDispatchRightAfterLogin() {
        seed(true, null);
        long now = bj(2026, 10, 23, 6, 0);
        keeper.onlineSince = now - 5_000L;      // 刚上线 5 秒
        scheduler.tick(now);
        assertTrue("登录初始化保护期内不应派单", keeper.submitted.isEmpty());

        keeper.onlineSince = now - TaskCadenceScheduler.INIT_GRACE_MILLIS - 1_000L;
        scheduler.tick(now);
        assertFalse("保护期结束后应恢复派单", keeper.submitted.isEmpty());
    }

    // ---------- 假实现 ----------

    private static final class FakeKeeper implements RoundScheduler.KeeperFacade {
        final Set<String> online = new HashSet<>();
        final Map<String, Long> sids = new HashMap<>();
        final List<String> submitted = new ArrayList<>();
        boolean busy;
        boolean war;
        boolean accept = true;
        long onlineSince;

        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
            submitted.add(fn);
            return accept;
        }

        @Override
        public long sessionSid(String fpid) {
            return sids.getOrDefault(fpid, 0L);
        }

        @Override
        public boolean sessionOnline(String fpid) {
            return online.contains(fpid);
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }

        @Override
        public boolean executorBusy(String fpid) {
            return busy;
        }

        @Override
        public long sessionOnlineSince(String fpid) {
            return onlineSince;
        }

        @Override
        public boolean wartimeYield(String fpid) {
            return war;
        }
    }
}
