package com.carlos.vault.daily;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 自动召回 / 自动开盾：输入校验纯函数（2026-11-05）。
 *
 * <p>需求 VL-01～VL-08：秒数空/非数字/负数/&lt;2 拒绝；阈值空/非数字/负数拒绝，0=不限。
 */
public class AutoRecallShieldConfigTest {

    @Test
    public void etaSec_rejectsEmptyNonNumericNegativeAndBelowMin() {
        assertEquals("请输入秒数", FnParams.validateEtaSec(null));
        assertEquals("请输入秒数", FnParams.validateEtaSec("   "));
        assertEquals("秒数必须是数字", FnParams.validateEtaSec("abc"));
        assertEquals("秒数不能为负数", FnParams.validateEtaSec("-1"));
        assertEquals("秒数不得低于 2 秒", FnParams.validateEtaSec("1"));
        assertEquals("秒数不得低于 2 秒", FnParams.validateEtaSec("0"));
        assertEquals("秒数不能超过 600 秒", FnParams.validateEtaSec("601"));
    }

    @Test
    public void etaSec_acceptsDefaultAndBoundaries() {
        assertNull(FnParams.validateEtaSec("2"));
        assertNull(FnParams.validateEtaSec("60"));
        assertNull(FnParams.validateEtaSec("600"));
    }

    @Test
    public void troopsMin_rejectsEmptyNonNumericNegative() {
        assertEquals("请输入兵力阈值（0=不限）", FnParams.validateTroopsMin(null, "召回兵力阈值"));
        assertEquals("请输入兵力阈值（0=不限）", FnParams.validateTroopsMin("  ", "召回兵力阈值"));
        assertEquals("召回兵力阈值必须是数字", FnParams.validateTroopsMin("x", "召回兵力阈值"));
        assertEquals("召回兵力阈值不能为负数", FnParams.validateTroopsMin("-5", "召回兵力阈值"));
    }

    @Test
    public void troopsMin_acceptsZeroAndPositive() {
        assertNull(FnParams.validateTroopsMin("0", "召回兵力阈值"));
        assertNull(FnParams.validateTroopsMin("5000", "召回兵力阈值"));
        assertNull(FnParams.validateTroopsMin("10000000", "召回兵力阈值"));
    }

    @Test
    public void diamondBudget_rejectsEmptyNonNumericNegativeAndOverMax() {
        assertEquals("请输入每日钻石预算（0=不购买）", FnParams.validateDiamondBudget(null));
        assertEquals("请输入每日钻石预算（0=不购买）", FnParams.validateDiamondBudget("  "));
        assertEquals("钻石预算必须是数字", FnParams.validateDiamondBudget("x"));
        assertEquals("钻石预算不能为负数", FnParams.validateDiamondBudget("-1"));
        assertEquals("钻石预算不能超过 100000 钻", FnParams.validateDiamondBudget("100001"));
    }

    @Test
    public void diamondBudget_acceptsZeroDefaultAndBoundary() {
        assertNull(FnParams.validateDiamondBudget("0"));
        assertNull(FnParams.validateDiamondBudget("1200"));
        assertNull(FnParams.validateDiamondBudget("100000"));
    }
}
