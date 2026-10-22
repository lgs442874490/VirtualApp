package com.carlos.vault.daily.guardian;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 防守战报码表校准（遗留 #1/#4）纯逻辑面：默认表（进攻战报映射 4/5=胜 6/7/8=败，标注来源）、
 * 未收录码（second_type=1 实测样本）不判成败、用户覆盖（second 并集 / by_first 覆盖 / roles 扩展）。
 */
public class GuardReportCodesTest {

    // ---------------------------------------------------------------- 默认表

    @Test
    public void defaultTable_attackReportMapping() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(null);
        // 4/5=胜 6/7/8=败——来源：进攻战报映射（recon 模块分类，交接 20261017 §四.1）
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 4));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 5));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 6));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 7));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 8));
        assertEquals(5, m.secondSize());
    }

    @Test
    public void unknownCode_returnsNull_notVerdict() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse("");
        // 实测防守战报样本 second_type=1 落在码表外：必须返回 null（未收录），
        // 引擎侧据此不判成败、不触发撤防、写 GUARD_REPORT_UNKNOWN
        assertNull(m.verdictOf(30, 1));
        assertNull(m.verdictOf(30, 0));
        assertNull(m.verdictOf(30, 3));
        assertNull(m.verdictOf(30, 9));
    }

    @Test
    public void battleFirstDefaultsTo30() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(null);
        assertTrue(m.isBattleFirst(30));   // P1 实证：守城级联 + 驻防参战战报均为 first_type=30
        assertFalse("非战斗邮件不按战报处理", m.isBattleFirst(20));
        assertFalse(m.isBattleFirst(0));
        assertNotNull(m.roleOf(30));
    }

    // ---------------------------------------------------------------- 覆盖映射

    @Test
    public void overrideSecond_mergesOverDefaults() {
        // 校准产出：防守 second_type=1 实测为败 → 并集进默认表，其余默认码不动
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse("{\"second\":{\"1\":\"loss\"}}");
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 1));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 4));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 7));
    }

    @Test
    public void overrideSecond_canReclassifyKnownCode() {
        // 防守校准后若发现 4 在防守语义下不是胜 → 直接改写
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"second\":{\"4\":\"unknown\",\"6\":\"win\"}}");
        assertEquals(GuardReportCodes.UNKNOWN, m.verdictOf(30, 4));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 6));
    }

    @Test
    public void overrideByFirst_highestPriority() {
        // first_type 覆盖表优先于全局默认表，且按 first_type 隔离
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"roles\":{\"31\":\"联盟战报\"},\"by_first\":{\"31\":{\"1\":\"win\"}}}");
        assertTrue(m.isBattleFirst(31));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(31, 1));
        // first_type=30 的码表不受 31 的覆盖影响
        assertNull(m.verdictOf(30, 1));
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 4));
    }

    @Test
    public void overrideExtendsBattleFirstTypes() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"roles\":{\"30\":\"防守战报\",\"35\":\"驻防援护战报\"}}");
        assertTrue(m.isBattleFirst(30));
        assertTrue(m.isBattleFirst(35));
        assertFalse(m.isBattleFirst(31));
        assertEquals("驻防援护战报", m.roleOf(35));
    }

    // ---------------------------------------------------------------- 解析健壮性

    @Test
    public void parse_garbageKeepsDefaults() {
        // 非法覆盖绝不能把败判成胜（误撤防）——异常时整体回退内置默认表
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse("{{{not json");
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 4));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 8));
        assertEquals(5, m.secondSize());
    }

    @Test
    public void parse_normalizesVerdictValues() {
        // 大小写归一；未知取值（手滑写错）一律归为 unknown，绝不落成 win/loss
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"second\":{\"2\":\"WIN\",\"3\":\"Loss\",\"9\":\"banana\"}}");
        assertEquals(GuardReportCodes.WIN, m.verdictOf(30, 2));
        assertEquals(GuardReportCodes.LOSS, m.verdictOf(30, 3));
        assertEquals(GuardReportCodes.UNKNOWN, m.verdictOf(30, 9));
    }

    @Test
    public void parse_ignoresBadKeys() {
        GuardReportCodes.Mapping m = GuardReportCodes.Mapping.parse(
                "{\"second\":{\"abc\":\"win\"},\"roles\":{\"x\":\"y\"}}");
        assertEquals(5, m.secondSize());
        assertFalse(m.isBattleFirst(0));
    }

    // ---------------------------------------------------------------- 工具

    @Test
    public void clip_truncatesLongRaw() {
        assertEquals("", GuardReportCodes.clip(null, 10));
        assertEquals("ab", GuardReportCodes.clip("ab", 10));
        String raw = "{\"first_type\":30,\"second_type\":1,\"other\":\"xxxx\"}";
        String clipped = GuardReportCodes.clip(raw, 10);
        assertTrue(clipped.startsWith("{\"first_ty"));
        assertTrue(clipped.endsWith("…"));
        assertEquals(11, clipped.length());
    }
}
