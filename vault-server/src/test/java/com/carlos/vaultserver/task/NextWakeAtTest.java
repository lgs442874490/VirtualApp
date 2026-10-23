package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 批次4 · C3 业务态 wakeAt 通用化单测（网页版 {@code TaskDecision.SleepUntil} 语义）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link TaskOutcome} 新增字段与链式 setter 的向后兼容（旧构造签名零改动）；</li>
 *   <li>{@link RoundRunner#acceptNextWakeAt} 落盘边界（now &lt;= x &lt;= now+24h）；</li>
 *   <li>{@link RoundRunner#applyResult} 只在单任务模式写 {@code srv:cad:{fpid}:{sid}:{fn}}，
 *       整轮模式不写、越界丢弃；</li>
 *   <li>{@link ServerFieldTasks#intelNextWakeAt}、{@link ServerBearTasks#bearNextWakeAtMillis}
 *       两个纯函数的 nextWake 计算（含 24h 边界的联动）。</li>
 * </ul>
 */
public class NextWakeAtTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    /** 生产实测：2026-10-23 21:00 北京时间（周五）巨熊预约场次开始（与 ServerBearTasksTest 一致）。 */
    private static final long BEAR_START = 1792760400L;
    /** 20:50 = 开赛前 10 分钟（准备窗开始）。 */
    private static final long BEAR_PREP_START = 1792759800L;
    /** 20:00（准备窗之前 = WAITING）。 */
    private static final long BEAR_T_2000 = 1792756800L;
    /** 21:10（活动进行中 = ACTIVE）。 */
    private static final long BEAR_T_MID = 1792761000L;
    /** 21:30（活动结束）。 */
    private static final long BEAR_END = 1792762200L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore stateStore;

    @Before
    public void setUp() {
        stateStore = new StateStore(tmp.getRoot().toPath());
    }

    private static ServerBearTasks.BearConfig bearDefaults() {
        return ServerBearTasks.BearConfig.defaults();
    }

    private static String cadKey(String fn) {
        return "srv:cad:" + FPID + ":" + SID + ":" + fn;
    }

    // ======================= 1. TaskOutcome 兼容 =======================

    @Test
    public void taskOutcome_oldConstructorStillWorksAndNextWakeDefaultsToZero() {
        TaskOutcome r = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.CLAIMED, "处理情报×1");
        assertEquals("intel", r.taskId);
        assertEquals("情报任务", r.taskName);
        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertEquals("处理情报×1", r.message);
        assertEquals("旧调用不设置 nextWake，必须默认 0（不覆盖节奏间隔）", 0L, r.nextWakeAtMillis);
        assertEquals("✅", r.symbol());
        assertTrue(r.successLike());
    }

    @Test
    public void taskOutcome_wakeAtIsFluentAndOverridable() {
        TaskOutcome r = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.SKIPPED, "待重试");
        TaskOutcome same = r.wakeAt(1_700_000_123_000L);
        assertSame("wakeAt 必须返回自身便于 Exec.result(...).wakeAt(...) 链式", r, same);
        assertEquals(1_700_000_123_000L, r.nextWakeAtMillis);
        r.wakeAt(0L);
        assertEquals("0 = 撤销覆盖", 0L, r.nextWakeAtMillis);
    }

    @Test
    public void taskOutcome_wakeInIsNowPlusDelay() {
        long before = System.currentTimeMillis();
        TaskOutcome r = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.SKIPPED, "x")
                .wakeIn(ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS);
        long after = System.currentTimeMillis();
        assertTrue("wakeIn 至少是 before+delay", r.nextWakeAtMillis >= before + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS);
        assertTrue("wakeIn 不超过 after+delay", r.nextWakeAtMillis <= after + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS);
        TaskOutcome neg = new TaskOutcome("x", "x", TaskOutcome.Category.SKIPPED, "x").wakeIn(-1L);
        assertEquals("负数 delay = 不覆盖", 0L, neg.nextWakeAtMillis);
    }

    // ======================= 2. 落盘边界（纯函数） =======================

    @Test
    public void acceptNextWakeAt_boundaries() {
        long now = 1_700_000_000_000L;
        long day = RoundRunner.NEXT_WAKE_MAX_AHEAD_MILLIS;
        assertEquals("24h 常量", 24L * 60L * 60L * 1000L, day);

        assertFalse("0=不覆盖", RoundRunner.acceptNextWakeAt(now, 0L));
        assertFalse("负数非法", RoundRunner.acceptNextWakeAt(now, -1L));
        assertFalse("过去时刻丢弃", RoundRunner.acceptNextWakeAt(now, now - 1L));
        assertTrue("now 本身合法（立即到期）", RoundRunner.acceptNextWakeAt(now, now));
        assertTrue("now+1 合法", RoundRunner.acceptNextWakeAt(now, now + 1L));
        assertTrue("刚好 24h 合法", RoundRunner.acceptNextWakeAt(now, now + day));
        assertFalse("超 24h 丢弃", RoundRunner.acceptNextWakeAt(now, now + day + 1L));
    }

    // ======================= 3. Runner 落盘（单任务 vs 整轮） =======================

    @Test
    public void applyResult_singleTaskModeWritesCadenceKey() {
        long at = System.currentTimeMillis() + 15L * 60L * 1000L;
        TaskOutcome r = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.CLAIMED, "处理情报×1")
                .wakeAt(at);
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", r, "节奏", true);

        Map<String, Object> snap = stateStore.snapshot();
        Object v = snap.get(cadKey("intel"));
        assertNotNull("单任务模式必须写 cadence 键", v);
        assertTrue(v instanceof Number);
        assertEquals("必须写入任务自报的时刻", at, ((Number) v).longValue());
        assertNotNull("落账主流程不受影响：log 键照写", snap.get("log:" + FPID + ":" + SID + ":intel"));
        assertNotNull("落账主流程不受影响：st 键照写",
                snap.get("st:" + com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd")
                        .format(new java.util.Date()) + ":" + FPID + ":" + SID + ":intel"));
    }

    @Test
    public void applyResult_roundModeDoesNotWriteCadenceKey() {
        long at = System.currentTimeMillis() + 15L * 60L * 1000L;
        TaskOutcome r = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.CLAIMED, "处理情报×1")
                .wakeAt(at);
        // 旧 6 参重载 = 整轮口径（singleTaskMode=false），兼容旧调用且绝不覆盖节奏键
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", r, "整轮");
        assertFalse("整轮模式不得写 cadence 键", stateStore.snapshot().containsKey(cadKey("intel")));

        TaskOutcome r2 = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.CLAIMED, "x").wakeAt(at);
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", r2, "整轮", false);
        assertFalse("显式 false 也不写", stateStore.snapshot().containsKey(cadKey("intel")));
    }

    @Test
    public void applyResult_dropsOutOfBoundaryValues() {
        // 过去时刻
        TaskOutcome past = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.SKIPPED, "x")
                .wakeAt(System.currentTimeMillis() - 1_000L);
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", past, "节奏", true);
        assertFalse("过去时刻不得写（= 已到期由节奏/唤醒路由负责）",
                stateStore.snapshot().containsKey(cadKey("intel")));

        // 超过 24h
        TaskOutcome far = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.SKIPPED, "x")
                .wakeAt(System.currentTimeMillis() + RoundRunner.NEXT_WAKE_MAX_AHEAD_MILLIS + 60_000L);
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", far, "节奏", true);
        assertFalse("超 24h 必须丢弃，避免把节奏冻住", stateStore.snapshot().containsKey(cadKey("intel")));

        // 0 / 未设置
        TaskOutcome none = new TaskOutcome("intel", "情报任务", TaskOutcome.Category.SKIPPED, "x");
        RoundRunner.applyResult(stateStore, FPID, SID, "intel", none, "节奏", true);
        assertFalse("0=不覆盖", stateStore.snapshot().containsKey(cadKey("intel")));
    }

    // ======================= 4. 情报 nextWake 纯函数 =======================

    @Test
    public void intelNextWake_dispatchedMeansWait15Minutes() {
        long now = 1_700_000_000_000L;
        assertEquals("派队后兜底 15 分钟（结果推送会提前唤醒）",
                now + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS,
                ServerFieldTasks.intelNextWakeAt(now, 1));
        assertEquals("多个派队也是 15 分钟", now + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS,
                ServerFieldTasks.intelNextWakeAt(now, 3));
        assertEquals("只领奖/无事件轮不设，交给固定 15s 节奏",
                0L, ServerFieldTasks.intelNextWakeAt(now, 0));
        assertEquals("负数防御", 0L, ServerFieldTasks.intelNextWakeAt(now, -2));
        assertEquals("15 分钟口径", 15L * 60_000L, ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS);
    }

    // ======================= 5. 巨熊 nextWake 纯函数 =======================

    @Test
    public void bearNextWake_reservedWaiting_wakes60sBeforeStart() {
        ServerBearTasks.Window w = ServerBearTasks.windowOf(
                BEAR_T_2000, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.WAITING, w.phase);
        long next = ServerBearTasks.bearNextWakeAtMillis(BEAR_T_2000 * 1000L, w, true, false);
        assertEquals("已预约且窗口未开始 → 开赛前 60 秒",
                (BEAR_START - 60L) * 1000L, next);
    }

    @Test
    public void bearNextWake_preparingFarFromStart_wakes60sBeforeStart() {
        ServerBearTasks.Window w = ServerBearTasks.windowOf(
                BEAR_PREP_START, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.PREPARING, w.phase);
        assertEquals("准备窗早期仍先睡到开赛前 60 秒",
                (BEAR_START - 60L) * 1000L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_PREP_START * 1000L, w, true, false));
    }

    @Test
    public void bearNextWake_last60sWakesExactlyAtStart() {
        long now = BEAR_START - 30L;   // 已进入最后 60 秒
        ServerBearTasks.Window w = ServerBearTasks.windowOf(now, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.PREPARING, w.phase);
        assertEquals("最后 60 秒 → 直接睡到开赛时刻（发车点）",
                BEAR_START * 1000L,
                ServerBearTasks.bearNextWakeAtMillis(now * 1000L, w, true, false));
    }

    @Test
    public void bearNextWake_activeLaunched_wakes30sLater() {
        ServerBearTasks.Window w = ServerBearTasks.windowOf(
                BEAR_T_MID, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.ACTIVE, w.phase);
        assertEquals("窗口内已发车 → 30 秒后上车/对账",
                (BEAR_T_MID + 30L) * 1000L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_T_MID * 1000L, w, true, true));
    }

    @Test
    public void bearNextWake_activeLaunchedNearEnd_neverExceedsWindowEnd() {
        long now = BEAR_END - 10L;   // 距结束 10 秒 < 30 秒
        ServerBearTasks.Window w = ServerBearTasks.windowOf(now, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.ACTIVE, w.phase);
        assertEquals("不晚于窗口结束", BEAR_END * 1000L,
                ServerBearTasks.bearNextWakeAtMillis(now * 1000L, w, true, true));
    }

    @Test
    public void bearNextWake_notLaunchedOrNotReservedOrEnded_noOverride() {
        ServerBearTasks.Window active = ServerBearTasks.windowOf(
                BEAR_T_MID, BEAR_START, 0L, bearDefaults());
        assertEquals("窗口内未发车：交给 15s 节奏重试", 0L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_T_MID * 1000L, active, true, false));
        assertEquals("未预约：绝不臆造唤醒/发车", 0L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_T_MID * 1000L, active, false, true));

        ServerBearTasks.Window ended = ServerBearTasks.windowOf(BEAR_END, BEAR_START, 0L, bearDefaults());
        assertEquals(ServerBearTasks.Window.ENDED, ended.phase);
        assertEquals("已结束：交给节奏", 0L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_END * 1000L, ended, true, true));

        assertEquals("空窗口防御", 0L,
                ServerBearTasks.bearNextWakeAtMillis(BEAR_T_MID * 1000L, null, true, true));
    }

    @Test
    public void bearNextWake_beyond24hIsDroppedByRunnerBoundary() {
        // 提前 48 小时看下一场（已预约）：纯函数给出开赛前 60s，但距 now > 24h → runner 丢弃，
        // 节奏继续按 15s 探测（预约同步有 5min/6h 节流），等进入 24h 后再落盘。
        long nowSec = BEAR_START - 48L * 3600L;
        ServerBearTasks.Window w = ServerBearTasks.windowOf(nowSec, BEAR_START, 0L, bearDefaults());
        long next = ServerBearTasks.bearNextWakeAtMillis(nowSec * 1000L, w, true, false);
        assertEquals((BEAR_START - 60L) * 1000L, next);
        assertFalse("48h 之外不得落盘",
                RoundRunner.acceptNextWakeAt(nowSec * 1000L, next));

        // 进入 24h 内则可落盘
        long nearSec = BEAR_START - 23L * 3600L;
        ServerBearTasks.Window near = ServerBearTasks.windowOf(nearSec, BEAR_START, 0L, bearDefaults());
        long nearNext = ServerBearTasks.bearNextWakeAtMillis(nearSec * 1000L, near, true, false);
        assertTrue("23h 之内可落盘",
                RoundRunner.acceptNextWakeAt(nearSec * 1000L, nearNext));
        assertNull("未写键时 snapshot 无该键", stateStore.snapshot().get(cadKey("bear")));
    }
}
