package com.carlos.vaultserver.guardian;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 守护盟友 P1 对等加速——纯函数规划器单测（2026-10-25）。
 *
 * <p>覆盖交付口径（用户拍板）：
 * <ul>
 *   <li>25% 优先：25% 够用只用 25%；25% 不够升 50%；多次 25% 循环叠加；</li>
 *   <li>库存不足 → 诚实止损；钻石开关默认关闭时不产生钻石动作（NEED_DIAMOND 只在显式开启时出现）；</li>
 *   <li>已先到不动 / margin 语义 / margin 不可达退化严格先到 / 双方 ETA 相同继续算；</li>
 *   <li>无 16s floor：末端只有 {@link GuardianSpeedupPlanner#MIN_REMAIN_MS} = 2s 安全余量；</li>
 *   <li>时钟异常 / 敌方已到达 / 我方已到达 / 次数上限，全部确定返回、不抛异常。</li>
 * </ul>
 */
public class GuardianSpeedupPlannerTest {

    private static final long S = 1000L;   // 1 秒

    // ---------------- 常量/数学工具 ----------------

    @Test
    public void constants_matchEmpiricalItemIdsAndFactors() {
        assertEquals("一阶 25% 道具 id（A-10 实证）", 301900L, GuardianSpeedupPlanner.ITEM_SPEEDUP_25);
        assertEquals("二阶 50% 道具 id（A-10 实证）", 301901L, GuardianSpeedupPlanner.ITEM_SPEEDUP_50);
        assertEquals(0.75d, GuardianSpeedupPlanner.FACTOR_25, 0.0d);
        assertEquals(0.50d, GuardianSpeedupPlanner.FACTOR_50, 0.0d);
        assertEquals(2_000L, GuardianSpeedupPlanner.DEFAULT_MARGIN_MS);
        assertEquals(2_000L, GuardianSpeedupPlanner.MIN_REMAIN_MS);
        assertTrue("单轮链有安全上限（防请求风暴）", GuardianSpeedupPlanner.MAX_SHOTS_PER_ROUND >= 2);
    }

    @Test
    public void projection_isStrictMultiplication() {
        assertEquals("25% → ×0.75", 7_500L, GuardianSpeedupPlanner.projectRemainMs(10_000L, 0.75d));
        assertEquals("50% → ×0.50", 5_000L, GuardianSpeedupPlanner.projectRemainMs(10_000L, 0.50d));
        assertEquals(7_500L,
                GuardianSpeedupPlanner.projectRemainMs(10_000L, GuardianSpeedupPlanner.Action.USE_25));
        assertEquals(5_000L,
                GuardianSpeedupPlanner.projectRemainMs(10_000L, GuardianSpeedupPlanner.Action.USE_50));
        assertEquals("非道具动作不改变 ETA", 10_000L,
                GuardianSpeedupPlanner.projectRemainMs(10_000L, GuardianSpeedupPlanner.Action.NONE));
        assertEquals("负剩余防御", 0L, GuardianSpeedupPlanner.projectRemainMs(-5L, 0.75d));
    }

    // ---------------- 已先到 / 双方相同 ETA ----------------

    @Test
    public void alreadyAhead_noAction() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(50 * S, 120 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("already_ahead", st.reasonCode);
        assertFalse(st.usesItem());
        assertEquals(0L, st.itemId);
        assertEquals("NONE 不改变 ETA", st.ourRemainMs, st.projectedRemainMs);
        assertTrue("原因写明已先到：" + st.reason, st.reason.contains("已先到"));
    }

    @Test
    public void equalEta_isNotAhead_soAct() {
        // 双方 ETA 相同（领先 0 < margin 2s）→ 视为未先到，需要加速
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(60 * S, 60 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, st.action);
        assertEquals(0L, st.aheadBeforeMs);
    }

    // ---------------- 25% 优先 / 循环叠加 ----------------

    @Test
    public void use25_when25AloneSuffices() {
        // 我方 100s、敌 90s、margin 2s：25% → 75s，领先 15s ≥ 2s → 只用一阶
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(100 * S, 90 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, st.action);
        assertEquals(GuardianSpeedupPlanner.ITEM_SPEEDUP_25, st.itemId);
        assertEquals(75 * S, st.projectedRemainMs);
        assertEquals("25% 够用不能动二阶", 0, st.projectedAheadMs - (90 * S - 75 * S));
        assertEquals("use_25_sufficient", st.reasonCode);
        assertTrue("原因写明一发足够：" + st.reason, st.reason.contains("一发足够"));
        assertTrue("25% 库存快照回显", st.stock25 >= 0);
    }

    @Test
    public void use25Stacked_multipleShotsUntilAhead() {
        // 我方 120s、敌 80s、margin 2s：25% 一发 90s 不够（需 ≤78s），两发 67.5s 才够
        GuardianSpeedupPlanner.Step first = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(120 * S, 80 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, first.action);
        assertEquals(90 * S, first.projectedRemainMs);
        assertEquals("use_25_stack", first.reasonCode);
        assertTrue("原因写明叠加发数：" + first.reason, first.reason.contains("叠加 2 发"));

        // 模拟宿主循环：用响应 ETA 继续推进（每次只给下一步）
        GuardianSpeedupPlanner.Step second = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(first.projectedRemainMs, 80 * S, 2 * S, 9, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, second.action);
        assertEquals(67_500L, second.projectedRemainMs);

        GuardianSpeedupPlanner.Step third = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(second.projectedRemainMs, 80 * S, 2 * S, 8, 10));
        assertEquals("两发后已先到 12.5s", GuardianSpeedupPlanner.Action.NONE, third.action);
        assertEquals("already_ahead", third.reasonCode);
    }

    // ---------------- 25% 不够 → 升 50% ----------------

    @Test
    public void use50_whenNo25Stock() {
        // 我方 120s、敌 80s、margin 2s：25% 库存 0 → 50% 一发 60s，领先 20s → 升二阶
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(120 * S, 80 * S, 2 * S, 0, 5));
        assertEquals(GuardianSpeedupPlanner.Action.USE_50, st.action);
        assertEquals(GuardianSpeedupPlanner.ITEM_SPEEDUP_50, st.itemId);
        assertEquals(60 * S, st.projectedRemainMs);
        assertEquals("use_50_required", st.reasonCode);
        assertTrue("原因写明 25% 库存为 0：" + st.reason, st.reason.contains("25% 库存为 0"));
    }

    @Test
    public void use50_when25BlockedByEndFloor() {
        // 我方 2.6s、敌 3.5s、margin 2s（需 ≤1.5s）：25% 一次到 1.95s 后已到末端 floor，
        // 无法再叠第二发；50% 一发 1.3s ≤1.5s → 必须升二阶（floor 感知选档）。
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(2_600L, 3_500L, 2_000L, 5, 5));
        assertEquals(GuardianSpeedupPlanner.Action.USE_50, st.action);
        assertEquals(GuardianSpeedupPlanner.ITEM_SPEEDUP_50, st.itemId);
        assertEquals(1_300L, st.projectedRemainMs);
    }

    @Test
    public void use25First_then50IfStillNeeded() {
        // 我方 4s、敌 3.3s、margin 2s（需 ≤1.3s）：25% 不够单发/两发，需 2×25%+1×50%；
        // 按「25% 优先」先给 25%（后续再按新 ETA 重选）。
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(4 * S, 3_300L, 2_000L, 10, 5));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, st.action);
    }

    // ---------------- 库存不足 / 钻石开关（默认关） ----------------

    @Test
    public void stockInsufficient_diamondOff_isHonestStop() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(120 * S, 80 * S, 2 * S, 0, 0));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("stock_insufficient", st.reasonCode);
        assertFalse("默认零钻石：不得产生钻石动作", st.action == GuardianSpeedupPlanner.Action.NEED_DIAMOND);
        assertTrue("原因写明库存为 0：" + st.reason, st.reason.contains("道具库存为 0"));
    }

    @Test
    public void stockInsufficient_diamondAllowed_onlyThenNeedDiamond() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(120 * S, 80 * S, 2 * S, 0, 0, 0, 0, 0, true));
        assertEquals("开关打开才允许返回钻石意图", GuardianSpeedupPlanner.Action.NEED_DIAMOND, st.action);
        assertEquals("need_diamond", st.reasonCode);
        assertFalse("NEED_DIAMOND 本身不携带道具动作（宿主 P1 只记日志不发 quick_buy）", st.usesItem());
    }

    @Test
    public void stockInsufficient_partialStock_stopWithoutDiamond() {
        // 我方 120s、敌 80s、margin 2s：纯 25% 需 2 发，但 25% 库存只有 1 且无 50% → 无法保证先到
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(120 * S, 80 * S, 2 * S, 1, 0));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("stock_insufficient", st.reasonCode);
    }

    // ---------------- margin 语义 ----------------

    @Test
    public void marginBoundary_aheadExactlyMarginIsSatisfied() {
        // 领先刚好 2s = margin → 已先到（先至 ≥ margin）
        GuardianSpeedupPlanner.Step exact = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(88 * S, 90 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, exact.action);
        assertEquals("already_ahead", exact.reasonCode);

        // 领先 1s < margin 2s → 需继续加速
        GuardianSpeedupPlanner.Step shortByOne = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(89 * S, 90 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, shortByOne.action);
    }

    @Test
    public void marginReachable_reducesToMinimumAhead() {
        assertEquals(2_000L, GuardianSpeedupPlanner.requiredAheadMs(90 * S, 2 * S));
        // margin 大于敌剩余 → 物理不可达，退化为严格先到（1ms）
        assertEquals(1L, GuardianSpeedupPlanner.requiredAheadMs(1_500L, 2_000L));
        // margin ≤0 → 至少严格先到
        assertEquals(1L, GuardianSpeedupPlanner.requiredAheadMs(90 * S, 0L));
        assertEquals(1L, GuardianSpeedupPlanner.requiredAheadMs(90 * S, -5L));
    }

    @Test
    public void marginUnreachable_alreadyStrictlyAhead_stopsWithoutBurningItems() {
        // 敌只剩 1.5s，margin 2s 不可达；我方 1s 已严格先到 → 不烧道具
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(1 * S, 1_500L, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("margin_unreachable_ahead", st.reasonCode);
        assertTrue("原因写明 margin 不可达：" + st.reason, st.reason.contains("margin 2"));
    }

    // ---------------- 末端 2s 安全余量（无旧 16s floor） ----------------

    @Test
    public void tailGuard_onlyLastTwoSecondsBlocked() {
        // 剩余 2.0s → 末端保护不加速（敌方 3s，领先 1s < margin 2s，否则会先命中「已先到」）
        GuardianSpeedupPlanner.Step tail = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(2 * S, 3 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, tail.action);
        assertEquals("tail_guard", tail.reasonCode);
        assertTrue("原因写明末端：" + tail.reason, tail.reason.contains("末端"));

        // 剩余 2.001s → 仍在可加速窗口（证明没有旧 16s floor）
        GuardianSpeedupPlanner.Step justAbove = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(2_001L, 3_005L, 2 * S, 10, 10));
        assertTrue("无 16s floor：2s 之后即可加速", justAbove.usesItem());

        // 旧 floor 16s 附近（剩余 15s）当然允许
        GuardianSpeedupPlanner.Step fifteen = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(15 * S, 16 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, fifteen.action);
    }

    // ---------------- 边界：敌方已到达 / 我方已到达 / 时钟异常 ----------------

    @Test
    public void enemyLanded_noAction() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(30 * S, 0L, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("enemy_landed", st.reasonCode);
        assertEquals(0L, st.itemId);
    }

    @Test
    public void ourArrivedOrEnded_noAction() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(0L, 30 * S, 2 * S, 10, 10));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("our_arrived", st.reasonCode);
    }

    @Test
    public void clockAnomaly_noAction() {
        // 负 margin
        assertEquals("clock_anomaly", GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(60 * S, 90 * S, -1L, 10, 10)).reasonCode);
        // 异常大的剩余（>24h）
        assertEquals("clock_anomaly", GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(GuardianSpeedupPlanner.MAX_SANE_REMAIN_MS + 1L,
                        90 * S, 2 * S, 10, 10)).reasonCode);
        // null 入参不抛异常
        GuardianSpeedupPlanner.Step nullIn = GuardianSpeedupPlanner.next(null);
        assertNotNull(nullIn);
        assertEquals(GuardianSpeedupPlanner.Action.NONE, nullIn.action);
        assertEquals("clock_anomaly", nullIn.reasonCode);
    }

    // ---------------- 次数上限（max_speedups_per_march / 单轮链） ----------------

    @Test
    public void maxSpeedupsPerMarch_stopsAtLimit() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(100 * S, 90 * S, 2 * S, 10, 10,
                        2, 2, 0, false));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("march_limit", st.reasonCode);
    }

    @Test
    public void roundChainLimit_stopsAtSafetyCap() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(100 * S, 90 * S, 2 * S, 100, 100,
                        0, 0, GuardianSpeedupPlanner.MAX_SHOTS_PER_ROUND, false));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("round_limit", st.reasonCode);
    }

    @Test
    public void negativeStockTreatedAsZero_neverThrows() {
        GuardianSpeedupPlanner.Step st = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(100 * S, 90 * S, 2 * S, -5L, -1L));
        assertEquals(GuardianSpeedupPlanner.Action.NONE, st.action);
        assertEquals("stock_insufficient", st.reasonCode);
        assertEquals(0L, st.stock25);
        assertEquals(0L, st.stock50);
    }
}
