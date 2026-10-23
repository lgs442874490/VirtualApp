package com.carlos.vault;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次7（task-25）：初始化引导状态机纯函数单测（对齐
 * design/全流程行为与分级门控-20261023.md §5.2 五步：①兑换码→②克隆/初始化→③添加账号→④启动分身→⑤自动化(T2+)）。
 *
 * <p>只覆盖 JVM 可测的静态逻辑（编解码 / 推进 / 自动检测 / 完成判定 / 首启跳转判定），
 * 不触碰 SharedPreferences/Activity（实例读写属于 Android 运行时，由真机冒烟覆盖）。
 */
public class OnboardingStateTest {

    // ---------- 编解码 ----------

    @Test
    public void decode_emptyOrNull_allPending() {
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, OnboardingState.decode(null));
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, OnboardingState.decode(""));
    }

    @Test
    public void decode_statusCodes() {
        // 1=完成 2=跳过 0=未开始
        assertArrayEquals(new int[]{OnboardingState.DONE, OnboardingState.SKIPPED,
                        OnboardingState.PENDING, OnboardingState.DONE, OnboardingState.SKIPPED},
                OnboardingState.decode("12012"));
        // 非法字符按未开始；短串缺位补 0
        assertArrayEquals(new int[]{OnboardingState.PENDING, OnboardingState.PENDING,
                        OnboardingState.PENDING, OnboardingState.PENDING, OnboardingState.PENDING},
                OnboardingState.decode("9x"));
    }

    @Test
    public void encode_roundTripAndLength() {
        assertEquals("12012", OnboardingState.encode(new int[]{1, 2, 0, 1, 2}));
        assertEquals("00000", OnboardingState.encode(null));
        assertEquals("10000", OnboardingState.encode(new int[]{1}));   // 短数组补 0
        int[] src = {OnboardingState.DONE, OnboardingState.PENDING, OnboardingState.SKIPPED,
                OnboardingState.DONE, OnboardingState.PENDING};
        assertArrayEquals(src, OnboardingState.decode(OnboardingState.encode(src)));
    }

    // ---------- 推进 ----------

    @Test
    public void mark_stepAndStatus() {
        int[] base = {0, 0, 0, 0, 0};
        int[] done1 = OnboardingState.mark(base, OnboardingState.STEP_LICENSE, OnboardingState.DONE);
        assertEquals(OnboardingState.DONE, done1[0]);
        assertArrayEquals("原数组不被修改", new int[]{0, 0, 0, 0, 0}, base);
        assertNotSame(base, done1);

        int[] skipped = OnboardingState.mark(done1, OnboardingState.STEP_CLONE,
                OnboardingState.SKIPPED);
        assertEquals(OnboardingState.SKIPPED, skipped[1]);
        // 已完成可被显式改回（跳过/重做场景）
        assertEquals(OnboardingState.PENDING, OnboardingState.mark(skipped,
                OnboardingState.STEP_CLONE, OnboardingState.PENDING)[1]);
    }

    @Test
    public void mark_illegalStepOrStatus_noopCopy() {
        int[] base = {1, 0, 0, 2, 0};
        assertArrayEquals("步骤号越界原样返回", base, OnboardingState.mark(base, 0, 1));
        assertArrayEquals("步骤号越界原样返回", base, OnboardingState.mark(base, 6, 1));
        assertArrayEquals("状态值非法原样返回", base, OnboardingState.mark(base, 1, 7));
    }

    @Test
    public void statusOf_and_settled() {
        int[] steps = {OnboardingState.DONE, OnboardingState.SKIPPED,
                OnboardingState.PENDING, OnboardingState.DONE, OnboardingState.PENDING};
        assertEquals(OnboardingState.DONE, OnboardingState.statusOf(steps, 1));
        assertEquals(OnboardingState.SKIPPED, OnboardingState.statusOf(steps, 2));
        assertEquals(OnboardingState.PENDING, OnboardingState.statusOf(steps, 3));
        assertEquals("越界按未开始", OnboardingState.PENDING, OnboardingState.statusOf(steps, 9));
        assertEquals("null 安全", OnboardingState.PENDING, OnboardingState.statusOf(null, 1));
        assertTrue(OnboardingState.settled(steps, 2));
        assertFalse(OnboardingState.settled(steps, 3));
    }

    // ---------- 自动检测 ----------

    @Test
    public void autoDetect_marksDetectedSteps() {
        int[] steps = OnboardingState.autoDetect(new int[]{0, 0, 0, 0, 0},
                true, true, true, true);
        assertEquals("授权可用 → ①完成", OnboardingState.DONE, steps[0]);
        assertEquals("已克隆+有基线 → ②完成", OnboardingState.DONE, steps[1]);
        assertEquals("已有账号 → ③完成", OnboardingState.DONE, steps[2]);
        assertEquals("游戏分身存活 → ④完成", OnboardingState.DONE, steps[3]);
        assertEquals("⑤自动化只能用户引导确认，不自动判定", OnboardingState.PENDING, steps[4]);
    }

    @Test
    public void autoDetect_doesNotDowngradeAndUpgradesSkipped() {
        // ③已跳过但已有账号 → 升级为完成；①已完成 + 授权异常 → 保持完成；⑤用户已跳过不覆盖
        int[] steps = OnboardingState.autoDetect(new int[]{
                OnboardingState.DONE, OnboardingState.PENDING, OnboardingState.SKIPPED,
                OnboardingState.PENDING, OnboardingState.SKIPPED}, false, false, true, false);
        assertEquals(OnboardingState.DONE, steps[0]);
        assertEquals(OnboardingState.PENDING, steps[1]);
        assertEquals(OnboardingState.DONE, steps[2]);
        assertEquals(OnboardingState.PENDING, steps[3]);
        assertEquals("⑤用户已跳过不被自动覆盖", OnboardingState.SKIPPED, steps[4]);
    }

    // ---------- 完成判定（T1 止于第④步 / T2+ 含第⑤步） ----------

    @Test
    public void allSettled_doneOrSkippedCounts() {
        assertFalse(OnboardingState.allSettled(new int[]{1, 1, 1, 1, 0}));
        assertTrue(OnboardingState.allSettled(new int[]{1, 1, 1, 1, 2}));
        assertTrue("全跳过也算有结论（可进入主页）",
                OnboardingState.allSettled(new int[]{2, 2, 2, 2, 2}));
        assertEquals(2, OnboardingState.settledCount(new int[]{1, 2, 0, 0, 0}));
        assertEquals(2, OnboardingState.doneCount(new int[]{1, 2, 1, 0, 0}));
        assertEquals("第一个未结论步骤=3", 3,
                OnboardingState.firstPendingStep(new int[]{1, 1, 0, 2, 2}));
        assertEquals("全有结论=0", 0, OnboardingState.firstPendingStep(new int[]{1, 1, 1, 1, 1}));
    }

    @Test
    public void requiredSteps_byTier() {
        assertEquals("T1 普通版引导止于第④步", 4, OnboardingState.requiredStepCount(1));
        assertEquals("T2 会员需完成第⑤步", 5, OnboardingState.requiredStepCount(2));
        assertEquals("T3 高级会员同样含第⑤步", 5, OnboardingState.requiredStepCount(3));
        assertEquals("未知/0 等级按 T1 处理", 4, OnboardingState.requiredStepCount(0));

        int[] t1Done = {1, 1, 1, 1, 0};
        assertTrue("T1：前 4 步完成即可进入主页",
                OnboardingState.allSettled(t1Done, OnboardingState.requiredStepCount(1)));
        assertFalse("T2：第⑤步未处理不能算完成",
                OnboardingState.allSettled(t1Done, OnboardingState.requiredStepCount(2)));
    }

    @Test
    public void stepLabelsAndBadges() {
        assertEquals("已完成", OnboardingState.statusLabel(OnboardingState.DONE));
        assertEquals("已跳过", OnboardingState.statusLabel(OnboardingState.SKIPPED));
        assertEquals("未开始", OnboardingState.statusLabel(OnboardingState.PENDING));
        assertEquals("ok", OnboardingState.statusBadgeType(OnboardingState.DONE));
        assertEquals("idle", OnboardingState.statusBadgeType(OnboardingState.SKIPPED));
        assertEquals("run", OnboardingState.statusBadgeType(OnboardingState.PENDING));
    }

    // ---------- 首启跳转判定 ----------

    @Test
    public void shouldRedirect_matrix() {
        assertTrue("未完成 + 已授权 + 本进程未拉过 → 跳引导",
                OnboardingState.shouldRedirect(false, true, false));
        assertFalse("已完成 → 不跳",
                OnboardingState.shouldRedirect(true, true, false));
        assertFalse("未授权 → 先走授权门禁，不跳",
                OnboardingState.shouldRedirect(false, false, false));
        assertFalse("本进程已拉过 → 防重入不跳",
                OnboardingState.shouldRedirect(false, true, true));
    }

    @Test
    public void constantsAreStable() {
        assertEquals("五步流程（§5.2）", 5, OnboardingState.STEP_COUNT);
        assertEquals(1, OnboardingState.STEP_LICENSE);
        assertEquals(2, OnboardingState.STEP_CLONE);
        assertEquals(3, OnboardingState.STEP_ACCOUNT);
        assertEquals(4, OnboardingState.STEP_LAUNCH);
        assertEquals(5, OnboardingState.STEP_AUTOMATION);
        assertEquals(0, OnboardingState.PENDING);
        assertEquals(1, OnboardingState.DONE);
        assertEquals(2, OnboardingState.SKIPPED);
    }
}
