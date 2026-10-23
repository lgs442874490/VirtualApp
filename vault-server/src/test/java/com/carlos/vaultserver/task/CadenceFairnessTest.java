package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.automation.ModuleRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次6 · 节奏调度公平性 / 全局预算 / 派单统计单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code VAULT_CADENCE_GLOBAL_MAX} 解析与默认值（默认 20；off/0/负数=不限）；</li>
 *   <li>60s 滑动窗口全局预算：超预算本 tick 不派且不写任何 cad 键，窗口滑过后恢复；</li>
 *   <li>账号 round-robin 公平（{@link TaskCadenceScheduler#pickNextAccount} 纯函数 + 双账号交替派单）；</li>
 *   <li>同 tick 全局最多派 1 单（不会因遍历顺序让某账号饿死）；</li>
 *   <li>统计键 {@code srv:cad:stats:{yyyy-MM-dd}} 的键型/派单计数/按账号分布/跳过原因/跨天/重启继承；</li>
 *   <li>既有闸门（auto/离线/战时/初始化保护/会话忙/最小间隔）与结果退避的回归断言
 *       ——完整回归见既有 {@code TaskCadenceSchedulerTest}。</li>
 * </ul>
 */
public class CadenceFairnessTest {

    private static final String A = "48460403";
    private static final String B = "57259770";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private FakeKeeper keeper;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        keeper = new FakeKeeper();
    }

    private static ModuleRegistry registry() {
        ModuleRegistry r = Modules.registry();
        r.validate();
        return r;
    }

    private TaskCadenceScheduler scheduler(int globalMaxPerMinute) {
        return new TaskCadenceScheduler(store, stateStore, keeper, registry(),
                false, new Random(42L), globalMaxPerMinute);
    }

    private VaultStore.Account account(String fpid) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        keeper.online.add(fpid);
        keeper.sids.put(fpid, SID);
        return a;
    }

    private void seed(String... fpids) {
        List<VaultStore.Account> in = new ArrayList<>();
        for (String fpid : fpids) in.add(account(fpid));
        store.replaceAll(in);
    }

    private static long bj(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String dayKey(long epochMillis) {
        return Beijing.fmt("yyyy-MM-dd").format(new Date(epochMillis));
    }

    private String statsKey(long epochMillis) {
        return TaskCadenceScheduler.STATS_KEY_PREFIX + dayKey(epochMillis);
    }

    private JSONObject stats(long epochMillis) {
        String raw = stateStore.getString(statsKey(epochMillis));
        assertNotNull("缺少统计键 " + statsKey(epochMillis), raw);
        return new JSONObject(raw);
    }

    /** 当前全部节奏相关状态（排除统计键本身）：用于断言“超预算不写 cad 键”。 */
    private Map<String, Object> snapshotWithoutStats() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : stateStore.snapshot().entrySet()) {
            if (e.getKey().startsWith(TaskCadenceScheduler.STATS_KEY_PREFIX)) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ======================= 1. 全局预算配置解析（纯函数） =======================

    @Test
    public void parseGlobalMax_defaultsAndOffMeansUnlimited() {
        assertEquals("默认 20 次/分钟", 20, TaskCadenceScheduler.DEFAULT_GLOBAL_MAX_PER_MINUTE);
        assertEquals(20, TaskCadenceScheduler.parseGlobalMax(null));
        assertEquals(20, TaskCadenceScheduler.parseGlobalMax(""));
        assertEquals(20, TaskCadenceScheduler.parseGlobalMax("   "));
        assertEquals("非法值回落默认", 20, TaskCadenceScheduler.parseGlobalMax("abc"));
        assertEquals("off=不限", 0, TaskCadenceScheduler.parseGlobalMax("off"));
        assertEquals("OFF 大小写不敏感", 0, TaskCadenceScheduler.parseGlobalMax("OFF"));
        assertEquals("false=不限", 0, TaskCadenceScheduler.parseGlobalMax("false"));
        assertEquals("0=不限", 0, TaskCadenceScheduler.parseGlobalMax("0"));
        assertEquals("负数=不限", 0, TaskCadenceScheduler.parseGlobalMax("-3"));
        assertEquals(5, TaskCadenceScheduler.parseGlobalMax("5"));
        assertEquals(100, TaskCadenceScheduler.parseGlobalMax(" 100 "));
    }

    // ======================= 2. round-robin 公平（纯函数） =======================

    @Test
    public void pickNextAccount_roundRobinPureFunction() {
        assertNull("无候选", TaskCadenceScheduler.pickNextAccount(null, "x"));
        assertNull("空候选", TaskCadenceScheduler.pickNextAccount(new ArrayList<>(), "x"));

        assertEquals("首次选字典序最小", "a",
                TaskCadenceScheduler.pickNextAccount(Arrays.asList("b", "a"), null));
        // 单账号：永远选自己（不因游标跳过）
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(Arrays.asList("a"), null));
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(Arrays.asList("a"), "a"));

        // 两账号严格交替
        List<String> two = Arrays.asList("b", "a");
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(two, null));
        assertEquals("b", TaskCadenceScheduler.pickNextAccount(two, "a"));
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(two, "b"));

        // 三账号环形（候选乱序也按 fpid 稳定排序）
        List<String> three = Arrays.asList("c", "a", "b");
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(three, null));
        assertEquals("b", TaskCadenceScheduler.pickNextAccount(three, "a"));
        assertEquals("c", TaskCadenceScheduler.pickNextAccount(three, "b"));
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(three, "c"));

        // 游标账号离开候选（掉线）：从字典序最小重新开始，不跳过任何人
        assertEquals("a", TaskCadenceScheduler.pickNextAccount(Arrays.asList("c", "a"), "b"));
    }

    // ======================= 3. 全局预算滑动窗口 =======================

    @Test
    public void globalBudget_capsDispatchAndDoesNotWriteCadKeysWhenExhausted() {
        seed(A);
        TaskCadenceScheduler s = scheduler(2);
        long now = bj(2026, 10, 23, 6, 0);

        s.tick(now);
        assertEquals("窗口内 1 单", 1, keeper.submitted.size());
        s.tick(now + 7_000L);
        assertEquals("窗口内 2 单（达到预算）", 2, keeper.submitted.size());
        assertEquals(2, s.minuteDispatchCount());

        Map<String, Object> before = snapshotWithoutStats();
        s.tick(now + 14_000L);
        assertEquals("超预算本 tick 不派", 2, keeper.submitted.size());
        assertEquals("超预算不写任何 cad 状态键", before, snapshotWithoutStats());

        // 60s 滑动窗口滑过第一条派单记录（now）后恢复
        s.tick(now + 61_000L);
        assertEquals("窗口滑过后恢复派单", 3, keeper.submitted.size());
        assertTrue("统计记录 global_budget 跳过",
                stats(now).getJSONObject("skip").optLong("global_budget", 0L) >= 1L);
    }

    @Test
    public void globalBudgetOffMeansUnlimited() {
        seed(A);
        TaskCadenceScheduler unlimited = scheduler(0);
        long now = bj(2026, 10, 23, 6, 0);
        for (int i = 0; i < 12; i++) unlimited.tick(now + i * 7_000L);
        assertEquals("0=不限，12 次节奏全部派单", 12, keeper.submitted.size());

        // 同一序列 + 预算 2：窗口内最多 2 单；60s 后旧记录滑出才有新额度（预期 0/7/63/70s 四单）。
        setUpTmpForBudgetControl();
        seed(A);
        TaskCadenceScheduler capped = scheduler(2);
        for (int i = 0; i < 12; i++) capped.tick(now + i * 7_000L);
        assertEquals("预算 2 时 60s 窗口最多 2 单，滑窗后新增（0/7/63/70s）",
                4, keeper.submitted.size());
        assertTrue("预算受限应记录 global_budget",
                stats(now).getJSONObject("skip").optLong("global_budget", 0L) >= 1L);
    }

    /** 同一测试内做第二组对照时重建临时 store/stateStore/keeper（独立目录）。 */
    private void setUpTmpForBudgetControl() {
        java.nio.file.Path dir = tmp.getRoot().toPath().resolve("case2");
        store = new VaultStore(dir);
        store.load();
        stateStore = new StateStore(dir);
        keeper = new FakeKeeper();
    }

    // ======================= 4. 账号 round-robin 公平（行为） =======================

    @Test
    public void roundRobin_twoOnlineAccountsAlternate() {
        seed(A, B);
        TaskCadenceScheduler s = scheduler(0);   // 预算不限，单测公平性
        long now = bj(2026, 10, 23, 6, 0);

        for (int i = 0; i < 6; i++) {
            s.tick(now + i * 7_000L);
            assertEquals("每 tick 全局最多派 1 单", i + 1, keeper.submitted.size());
        }
        assertEquals("两账号严格交替（字典序 + 游标环回）",
                Arrays.asList(A, B, A, B, A, B), keeper.submittedFpids);
    }

    @Test
    public void roundRobin_doesNotStarveEitherAccount() {
        seed(A, B);
        TaskCadenceScheduler s = scheduler(0);
        long now = bj(2026, 10, 23, 6, 0);
        for (int i = 0; i < 20; i++) s.tick(now + i * 7_000L);

        int a = 0, b = 0;
        for (String fpid : keeper.submittedFpids) {
            if (A.equals(fpid)) a++;
            if (B.equals(fpid)) b++;
        }
        assertTrue("两账号都应有派单（无饿死）：A=" + a + " B=" + b, a >= 8 && b >= 8);
        assertTrue("数量应接近（公平）：A=" + a + " B=" + b, Math.abs(a - b) <= 2);
    }

    // ======================= 5. 统计键 =======================

    @Test
    public void stats_writtenAfterDispatchWithStableSchema() {
        seed(A);
        TaskCadenceScheduler s = scheduler(20);
        long now = bj(2026, 10, 23, 6, 0);
        s.tick(now);

        String key = statsKey(now);
        assertEquals("键型契约：srv:cad:stats:{yyyy-MM-dd}",
                "srv:cad:stats:2026-10-23", key);
        JSONObject o = stats(now);
        assertEquals("date", "2026-10-23", o.getString("date"));
        assertEquals("dispatch", 1L, o.getLong("dispatch"));
        assertEquals("按账号分布", 1L, o.getJSONObject("byAccount").getLong(A));
        assertEquals("全局预算", 20, o.getInt("globalMax"));
        assertEquals("当前窗口派单数", 1, o.getInt("minuteDispatch"));
        assertTrue("firstAt > 0", o.getLong("firstAt") > 0L);
        assertEquals("updatedAt=本次 tick", now, o.getLong("updatedAt"));
        assertNotNull("skip 结构稳定（即使为空）", o.getJSONObject("skip"));
    }

    @Test
    public void stats_recordsSkipReasons() {
        seed(A);
        store.find(A).auto = false;              // auto 关 → auto_off
        TaskCadenceScheduler s = scheduler(20);
        long now = bj(2026, 10, 23, 6, 0);
        s.tick(now);
        assertTrue("未派单", keeper.submitted.isEmpty());
        assertTrue("auto_off 计数", stats(now).getJSONObject("skip").optLong("auto_off", 0L) >= 1L);
        assertEquals("dispatch 应为 0", 0L, stats(now).getLong("dispatch"));

        // 恢复 auto 但离线 → offline
        store.find(A).auto = true;
        keeper.online.clear();
        s.tick(now + 2_000L);
        s.publishStats(now + 2_000L);            // 直接落盘（统计落盘有 10s 合并窗口）
        assertTrue("offline 计数", stats(now).getJSONObject("skip").optLong("offline", 0L) >= 1L);

        // 在线 → 派单 1 次；随后 1s 内再 tick → min_gap 跳过（最小间隔 6s）
        keeper.online.add(A);
        s.tick(now + 3_000L);
        assertEquals("闸门恢复后派单", 1, keeper.submitted.size());
        s.tick(now + 4_000L);
        assertEquals("最小间隔内不派单", 1, keeper.submitted.size());
        s.publishStats(now + 4_000L);
        JSONObject skip = stats(now).getJSONObject("skip");
        assertTrue("min_gap 计数", skip.optLong("min_gap", 0L) >= 1L);
        assertTrue("至少覆盖 auto_off/offline/min_gap 三种原因", skip.length() >= 3);
    }

    @Test
    public void stats_skipReasonsCoverWarBusyAndInitGrace() {
        seed(A);
        TaskCadenceScheduler s = scheduler(20);
        long now = bj(2026, 10, 23, 6, 0);

        keeper.war = true;
        s.tick(now);
        keeper.war = false;
        keeper.busy = true;
        s.tick(now + 2_000L);
        keeper.busy = false;
        keeper.onlineSince = now + 3_000L;       // 刚上线 0s → 初始化保护
        s.tick(now + 3_000L);
        s.publishStats(now + 3_000L);

        JSONObject skip = stats(now).getJSONObject("skip");
        assertTrue("战时让位", skip.optLong("wartime", 0L) >= 1L);
        assertTrue("会话忙", skip.optLong("busy", 0L) >= 1L);
        assertTrue("登录初始化保护", skip.optLong("init_grace", 0L) >= 1L);
        assertTrue("全程无派单", keeper.submitted.isEmpty());
    }

    @Test
    public void stats_dayRolloverKeepsOldDayKey() {
        seed(A);
        TaskCadenceScheduler s = scheduler(20);
        long d1 = bj(2026, 10, 23, 23, 59);
        long d2 = d1 + 2 * 60_000L;              // 2026-10-24 00:01

        s.tick(d1);
        assertEquals("day1 派单 1", 1L, stats(d1).getLong("dispatch"));
        s.tick(d2);
        assertEquals("day2 独立统计", 1L, stats(d2).getLong("dispatch"));
        assertEquals("day1 历史保留", 1L, stats(d1).getLong("dispatch"));
        assertEquals("day2 键日期", "2026-10-24", stats(d2).getString("date"));
    }

    @Test
    public void stats_surviveSameDayRestart() {
        seed(A);
        long now = bj(2026, 10, 23, 6, 0);
        TaskCadenceScheduler first = scheduler(20);
        first.tick(now);
        assertEquals(1L, stats(now).getLong("dispatch"));

        // 模拟同日重启：新调度器从已落盘统计继承计数（日报不丢数）
        TaskCadenceScheduler second = scheduler(20);
        second.tick(now + 7_000L);
        assertEquals("同日重启后累加（1+1）", 2L, stats(now).getLong("dispatch"));
        assertEquals("按账号分布累加", 2L, stats(now).getJSONObject("byAccount").getLong(A));
    }

    // ======================= 6. 既有行为回归（关键闸门在统计侧的表现） =======================

    @Test
    public void existingSafetyGatesStillPreventDispatch() {
        seed(A);
        TaskCadenceScheduler s = scheduler(0);
        long now = bj(2026, 10, 23, 6, 0);

        // auto 关
        store.find(A).auto = false;
        s.tick(now);
        assertTrue(keeper.submitted.isEmpty());
        store.find(A).auto = true;

        // 离线
        keeper.online.clear();
        s.tick(now + 2_000L);
        assertTrue(keeper.submitted.isEmpty());
        keeper.online.add(A);

        // 登录初始化保护（刚上线 5s）
        keeper.onlineSince = now + 2_000L - 5_000L;
        s.tick(now + 2_000L);
        assertTrue(keeper.submitted.isEmpty());
        keeper.onlineSince = 0L;

        // 会话忙
        keeper.busy = true;
        s.tick(now + 4_000L);
        assertTrue(keeper.submitted.isEmpty());
        keeper.busy = false;

        // 战时让位
        keeper.war = true;
        s.tick(now + 6_000L);
        assertTrue(keeper.submitted.isEmpty());
        keeper.war = false;

        // 闸门全开 → 恢复派单
        s.tick(now + 8_000L);
        assertEquals("闸门恢复后应派单", 1, keeper.submitted.size());
        // 最小间隔（6s，含刚写入的 srv:cadlast 内存/状态双口径）
        s.tick(now + 9_000L);
        assertEquals("最小间隔内不派单", 1, keeper.submitted.size());
    }

    @Test
    public void unacceptedDispatchStillRetriesAfter60sAndNotCountedInBudget() {
        seed(A);
        TaskCadenceScheduler s = scheduler(1);
        long now = bj(2026, 10, 23, 6, 0);

        keeper.accept = false;
        s.tick(now);
        assertEquals("未受理也记录一次提交尝试", 1, keeper.submitted.size());
        Object retry = stateStore.snapshot().get("srv:cad:" + A + ":" + SID + ":" + keeper.submitted.get(0));
        assertEquals("未受理 → 60s 后重试", now + TaskCadenceScheduler.RETRY_MILLIS,
                ((Number) retry).longValue());
        assertEquals("未受理不计入预算窗口", 0, s.minuteDispatchCount());
        assertEquals("统计 dispatch 不计未受理", 0L, stats(now).getLong("dispatch"));

        keeper.accept = true;
        s.minuteDispatchCount();                  // no-op，保持可读
        s.tick(now + 61_000L);                   // 预算 1 且窗口为空 → 可派
        assertEquals(2, keeper.submitted.size());
        assertEquals("受理后才计入窗口", 1, s.minuteDispatchCount());
    }

    // ======================= 假实现 =======================

    private static final class FakeKeeper implements RoundScheduler.KeeperFacade {
        final Set<String> online = new HashSet<>();
        final Map<String, Long> sids = new HashMap<>();
        final List<String> submitted = new ArrayList<>();
        final List<String> submittedFpids = new ArrayList<>();
        boolean busy;
        boolean war;
        boolean accept = true;
        long onlineSince;

        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
            submitted.add(fn);
            submittedFpids.add(fpid);
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
