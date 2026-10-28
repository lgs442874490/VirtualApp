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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 城镇域 2026-10-28 功能型 bug 服务端单测（bug 1 练兵自动续 / bug 6 竞技场可执行）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>竞技场判定边界：结算窗 23:10/23:09/00:00（北京时间）、每日场次上限、领奖重试/门控、
 *       新增的"无英雄数据/拉对手失败"可读文案（旧实现空 Multi → SKIPPED 无文案 =
 *       用户看到"从来没执行过"）；</li>
 *   <li>兵营：训练档位判定 {@link ServerTownTasks#barrackTrainTier}（maxTier=0 时必须返回 0
 *       由任务写可读 SKIP，而不是静默/盲发）；</li>
 *   <li>节奏链路：barracks/arena 在 {@link TaskCadence} 表内，roleConfig=true 后
 *       {@link TaskCadenceScheduler#enabledTaskIds} 会纳入周期执行；</li>
 *   <li>推送唤醒：notify_barrack_* → barracks（训练完成即续训）、notify_hospital_* → medic。</li>
 * </ul>
 * 不连真协议。既有 {@code IntelArenaFixTest} 的用例保持不动（这里只补边界/新增判定）。
 */
public class TownBarracksArenaFixTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private TaskCadenceScheduler scheduler;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        scheduler = new TaskCadenceScheduler(store, stateStore, new StubKeeper(),
                registry(), false, new Random(42L));
    }

    private static ModuleRegistry registry() {
        ModuleRegistry r = Modules.registry();
        r.validate();
        return r;
    }

    // ==================== 竞技场：结算窗边界 ====================

    @Test
    public void arenaWindowBoundaries_beijing() {
        assertFalse("23:09 未进结算窗", ServerTownTasks.arenaInDailyRewardWindow(bj(23, 9)));
        assertTrue("23:10 进结算窗", ServerTownTasks.arenaInDailyRewardWindow(bj(23, 10)));
        assertTrue("23:59 仍在结算窗", ServerTownTasks.arenaInDailyRewardWindow(bj(23, 59)));
        assertFalse("次日 00:00 结算窗结束（跨天重置）",
                ServerTownTasks.arenaInDailyRewardWindow(bj(0, 0)));
        assertFalse("白天（12:00）不是结算窗，可正常打免费次数",
                ServerTownTasks.arenaInDailyRewardWindow(bj(12, 0)));
    }

    @Test
    public void arenaDailyFightCap_includesBoughtTimes() {
        assertEquals(5, ServerTownTasks.arenaDailyFightCap(0));
        assertEquals(8, ServerTownTasks.arenaDailyFightCap(3));
        assertEquals("负数防御", 5, ServerTownTasks.arenaDailyFightCap(-2));
    }

    @Test
    public void arenaRewardGating_retryAndAllowed() {
        long now = System.currentTimeMillis();
        assertTrue("从未尝试 → 立即可领", ServerTownTasks.arenaRewardRetryDue(0L, now));
        assertFalse("4 分 59 秒不重试",
                ServerTownTasks.arenaRewardRetryDue(now - 299_000L, now));
        assertTrue("满 5 分钟重试",
                ServerTownTasks.arenaRewardRetryDue(now - 300_000L, now));
        // 服务端计数未知/达标 → 允许；明确不足且无兜底证据 → 不请求
        assertTrue(ServerTownTasks.arenaRewardAttemptAllowed(-1, 0));
        assertTrue(ServerTownTasks.arenaRewardAttemptAllowed(5, 6));
        assertFalse(ServerTownTasks.arenaRewardAttemptAllowed(2, 2));
        assertTrue("本地已发场次多于服务端计数 → 兜底允许一次",
                ServerTownTasks.arenaRewardAttemptAllowed(3, 5));
    }

    @Test
    public void arenaFightableHeroes_andOpponentNotes() {
        assertFalse("缺英雄推送时不能静默跳过", ServerTownTasks.arenaHasFightableHeroes(null));
        assertFalse(ServerTownTasks.arenaHasFightableHeroes(new ArrayList<Long>()));
        List<Long> heroes = new ArrayList<>();
        heroes.add(50002L);
        assertTrue(ServerTownTasks.arenaHasFightableHeroes(heroes));

        assertTrue(ServerTownTasks.arenaOpponentSkipNote(2901).contains("次数已用完"));
        assertTrue(ServerTownTasks.arenaOpponentSkipNote(2902).contains("次数已用完"));
        assertTrue(ServerTownTasks.arenaOpponentSkipNote(2920).contains("失效"));
        assertTrue(ServerTownTasks.arenaOpponentSkipNote(2903).contains("频繁"));
        assertTrue("其它码要带码便于排查",
                ServerTownTasks.arenaOpponentSkipNote(9999).contains("9999"));
    }

    // ==================== 兵营：训练档位判定 ====================

    @Test
    public void barrackTrainTier_autoUsesHighestUnlocked() {
        assertEquals("auto → 已解锁最高档", 5, ServerTownTasks.barrackTrainTier(0, 5, 12));
        assertEquals("无解锁数据时 auto 不能训练（0 → 任务写可读 SKIP）",
                0, ServerTownTasks.barrackTrainTier(0, 0, 12));
        assertEquals("档位上限 12", 12, ServerTownTasks.barrackTrainTier(0, 99, 12));
    }

    @Test
    public void barrackTrainTier_explicitStrategyClampedToUnlocked() {
        assertEquals("显式 T7 超过已解锁 T5 → 夹到 T5", 5,
                ServerTownTasks.barrackTrainTier(7, 5, 12));
        assertEquals("显式 T3 低于已解锁 → 用显式值", 3,
                ServerTownTasks.barrackTrainTier(3, 5, 12));
        assertEquals("解锁数据缺失时显式档位按 fallback 保守放行（App 端同款）", 7,
                ServerTownTasks.barrackTrainTier(7, 0, 12));
        assertEquals("fallback 也被夹到 12", 12,
                ServerTownTasks.barrackTrainTier(99, 0, 12));
        assertEquals("非法策略回退 auto", 4, ServerTownTasks.barrackTrainTier(-1, 4, 12));
    }

    // ==================== 节奏链路：barracks/arena 可周期执行 ====================

    @Test
    public void cadenceCoversBarracksAndArena() {
        assertTrue(TaskCadence.contains("barracks"));
        assertTrue(TaskCadence.contains("arena"));
        assertTrue(TaskCadence.contains("medic"));
        // 网页版 town 30s → 60s 安全下限；barracks 同为 60s
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("barracks"));
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("arena"));
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("medic"));
    }

    @Test
    public void roleConfigEnablesBarracksAndArenaInCadence() {
        Map<String, Boolean> rc = new HashMap<>();
        rc.put("barracks", true);
        rc.put("arena", true);
        VaultStore.Account a = seed(rc);
        List<String> enabled = scheduler.enabledTaskIds(a, SID);
        assertTrue("roleConfig=true 后 barracks 必须进入节奏调度（练兵的周期执行前提）",
                enabled.contains("barracks"));
        assertTrue("roleConfig=true 后 arena 必须进入节奏调度（竞技场没执行过的服务端排查点）",
                enabled.contains("arena"));
    }

    @Test
    public void defaultCatalogKeepsBarracksAndArenaOff() {
        // 网页版默认关（ServerTasks 目录 enabledByDefault=false）：不勾选不得偷跑
        VaultStore.Account a = seed(null);
        List<String> enabled = scheduler.enabledTaskIds(a, SID);
        assertFalse("barracks 默认关", enabled.contains("barracks"));
        assertFalse("arena 默认关", enabled.contains("arena"));
        assertTrue("medic 默认开", enabled.contains("medic"));
    }

    // ==================== 推送唤醒：训练完成即续训 / 治疗完成即续疗 ====================

    @Test
    public void wakeRulesForBarracksAndHospital() {
        for (String proto : new String[]{"notify_barrack_init", "notify_barrack_update",
                "notify_barrack_train_finish"}) {
            assertTrue(proto + " → barracks（训练完成即续训，不等 60s/退避 180s）",
                    TaskWakeRouter.tasksForProtocol(proto).contains("barracks"));
        }
        for (String proto : new String[]{"notify_hospital_init",
                "notify_hospital_wound_soldiers", "notify_hospital_heal_finish"}) {
            assertTrue(proto + " → medic（治疗完成立即重评）",
                    TaskWakeRouter.tasksForProtocol(proto).contains("medic"));
        }
        for (String fn : TaskWakeRouter.mappedTaskIds()) {
            assertTrue("唤醒任务必须存在于节奏表：" + fn, TaskCadence.contains(fn));
        }
    }

    // ==================== task-7：训练响应体队列 → 状态行 ====================

    @Test
    public void trainResponseQueue_parsesFinishTsAndBid() {
        long nowSec = 1_791_890_000L;
        // 生产实证响应：{"code":0,"barrack":{"bid":321,...,"finish_ts":1791896497,"total_time":10319}}
        Map<String, Object> content = map("code", 0, "barrack", map(
                "bid", 321, "soldier_id", 10800, "soldier_num", 200,
                "finish_ts", nowSec + 10319, "total_time", 10319, "qid", 30001));
        assertEquals("finish_ts（秒）→ 毫秒",
                (nowSec + 10319) * 1000L,
                ServerTownTasks.barrackQueueEndMillis(content, nowSec * 1000L + 6_000L));
        assertEquals("bid=321 → 盾营", 1, ServerTownTasks.barrackQueueLine(content, 3));
    }

    @Test
    public void trainResponseQueue_totalTimeFallbackAndMillisCompat() {
        long nowMs = 1_791_890_000_000L;
        long nowSec = nowMs / 1000L;
        // finish_ts 缺失 → now + total_time 兜底
        Map<String, Object> noFinish = map("barrack", map("bid", 322, "total_time", 600));
        assertEquals((nowSec + 600L) * 1000L,
                ServerTownTasks.barrackQueueEndMillis(noFinish, nowMs));
        // 毫秒时间戳兼容
        Map<String, Object> millis = map("barrack", map(
                "bid", 323, "finish_ts", (nowSec + 900L) * 1000L));
        assertEquals((nowSec + 900L) * 1000L,
                ServerTownTasks.barrackQueueEndMillis(millis, nowMs));
        // 两者都无 / 结构不对 → 0（不误写状态行）
        assertEquals(0L, ServerTownTasks.barrackQueueEndMillis(map("code", 0), nowMs));
        assertEquals(0L, ServerTownTasks.barrackQueueEndMillis(null, nowMs));
    }

    @Test
    public void trainResponseQueue_shapeCompatibility() {
        // {barrack:{...}} 包裹
        Map<String, Object> wrapped = map("barrack", map("bid", 321, "total_time", 60));
        // {barrack:[{...}]} 多营 List（取首条）
        Map<String, Object> listShape = map("barrack", List.of(
                map("bid", 322, "total_time", 60), map("bid", 323, "total_time", 60)));
        // 已解包成队列本体
        Map<String, Object> unwrapped = map("bid", 323, "finish_ts", Long.MAX_VALUE / 2000L);
        assertNotNull(ServerTownTasks.barrackQueueOf(wrapped));
        assertEquals(2, ServerTownTasks.barrackQueueLine(listShape, 1));
        assertEquals(3, ServerTownTasks.barrackQueueLine(unwrapped, 1));
        // {data:{barrack:{...}}} / 已解包 data 内层
        assertNotNull(ServerTownTasks.barrackQueueOf(
                map("data", map("barrack", map("bid", 321, "total_time", 60)))));
        assertNotNull(ServerTownTasks.barrackQueueOf(
                map("data", map("bid", 322, "finish_ts", 0, "total_time", 60))));
        assertNull(ServerTownTasks.barrackQueueOf(map("code", 0)));
        assertEquals("bid 识别不到 → 用请求行兜底", 2,
                ServerTownTasks.barrackQueueLine(map("barrack", map("total_time", 60)), 2));
    }

    @Test
    public void trainResponseQueue_statusTextFormat() {
        long endMs = bj(21, 5);
        String row = ServerTownTasks.barrackQueueStatusText(endMs);
        assertNotNull(row);
        assertTrue("格式应为 训练中 · HH:mm可收，实际=" + row, row.startsWith("训练中 · "));
        assertTrue(row.endsWith("可收"));
        assertTrue("应含北京时区 HH:mm，实际=" + row,
                row.matches("训练中 · \\d{2}:\\d{2}可收"));
        assertNull(ServerTownTasks.barrackQueueStatusText(0L));
    }

    @Test
    public void trainResponseQueue_winsOverStaleNotify() {
        long nowSec = 1_791_890_000L;
        Long fresh = (nowSec + 7200L) * 1000L;
        // 旧 notify 是"无队列"快照：fresh 必须优先，不能被覆盖回空闲
        String freshRow = ServerTownTasks.barrackStateRow(fresh, 0L, nowSec);
        assertNotNull(freshRow);
        assertTrue("训练刚成功不能被旧 notify 覆盖回空闲，实际=" + freshRow,
                freshRow.startsWith("训练中 · "));
        // 无 fresh、notify 无数据 → 空闲
        assertEquals("空闲（无训练队列数据）", ServerTownTasks.barrackStateRow(null, 0L, nowSec));
        // 无 fresh、notify 队列在未来 → 训练中
        assertTrue(ServerTownTasks.barrackStateRow(null, nowSec + 600L, nowSec)
                .startsWith("训练中 · "));
        // 无 fresh、notify 队列已过 → 有完工批次可收
        assertEquals("有完工批次可收", ServerTownTasks.barrackStateRow(null, nowSec - 10L, nowSec));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================== 测试基础设施 ====================


    private VaultStore.Account seed(Map<String, Boolean> roleConfig) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        if (roleConfig != null) a.roleConfig.put(String.valueOf(SID), roleConfig);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        VaultStore.Account found = store.find(FPID);
        assertNotNull(found);
        return found;
    }

    /** 北京时间当日 时:分 的毫秒时间戳。 */
    private static long bj(int hour, int minute) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.set(2026, Calendar.OCTOBER, 28, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 只满足 enabledTaskIds/闸门读取的 KeeperFacade 替身。 */
    private static final class StubKeeper implements RoundScheduler.KeeperFacade {
        @Override
        public boolean submitRound(String fpid, String fn, String trigger) {
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
}
