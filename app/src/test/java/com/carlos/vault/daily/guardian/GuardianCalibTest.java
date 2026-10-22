package com.carlos.vault.daily.guardian;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 距离分档标定 D（遗留 #5）纯逻辑面：分档选择、EWMA 样本更新、缺档回退链
 * （对应档 → single 单值 → 默认 38s）、JSON 落盘往返。
 */
public class GuardianCalibTest {

    // ---------------------------------------------------------------- 分档选择

    @Test
    public void bandOf_defaultThresholds() {
        // 默认：near ≤ 20 格 < mid ≤ 60 格 < far
        assertEquals(GuardianCalib.BAND_NEAR, GuardianCalib.bandOf(0, 20, 60));
        assertEquals(GuardianCalib.BAND_NEAR, GuardianCalib.bandOf(20, 20, 60));   // 边界含
        assertEquals(GuardianCalib.BAND_MID, GuardianCalib.bandOf(20.1, 20, 60));
        assertEquals(GuardianCalib.BAND_MID, GuardianCalib.bandOf(60, 20, 60));    // 边界含
        assertEquals(GuardianCalib.BAND_FAR, GuardianCalib.bandOf(60.1, 20, 60));
        assertEquals(GuardianCalib.BAND_FAR, GuardianCalib.bandOf(500, 20, 60));
    }

    @Test
    public void bandOf_customThresholds_andInvalidFallback() {
        // 功能页可配（dist_near/dist_mid 策略项）
        assertEquals(GuardianCalib.BAND_NEAR, GuardianCalib.bandOf(10, 10, 40));
        assertEquals(GuardianCalib.BAND_MID, GuardianCalib.bandOf(40, 10, 40));
        assertEquals(GuardianCalib.BAND_FAR, GuardianCalib.bandOf(40.5, 10, 40));
        // 非法阈值回默认：nearTh≤0 → 20；midTh≤nearTh → 60
        assertEquals(GuardianCalib.BAND_NEAR, GuardianCalib.bandOf(15, 0, 60));
        assertEquals(GuardianCalib.BAND_NEAR, GuardianCalib.bandOf(15, 0, 0));  // 15 仍 ≤ 修正后的 20
        assertEquals(GuardianCalib.BAND_MID, GuardianCalib.bandOf(30, 0, 0));   // 30 > 20 且 ≤ 60
        assertEquals(GuardianCalib.BAND_FAR, GuardianCalib.bandOf(61, 0, 0));
    }

    @Test
    public void distance_euclideanGrids() {
        assertEquals(5.0, GuardianCalib.distance(0, 0, 3, 4), 1e-9);
        assertEquals(0.0, GuardianCalib.distance(674, 684, 674, 684), 1e-9);
        assertEquals(10.0, GuardianCalib.distance(687, 698, 687, 708), 1e-9);
    }

    // ---------------------------------------------------------------- 缺档回退链

    @Test
    public void estimate_emptyTable_fallsToDefault38() {
        // 再缺回退默认 38s（交接实测中距样本量级）
        assertEquals(GuardianCalib.DEFAULT_D_SEC, GuardianCalib.estimate(new GuardianCalib.Table(), GuardianCalib.BAND_NEAR));
        assertEquals(GuardianCalib.DEFAULT_D_SEC, GuardianCalib.estimate(new GuardianCalib.Table(), GuardianCalib.BAND_FAR));
        assertEquals(GuardianCalib.DEFAULT_D_SEC, GuardianCalib.estimate(null, GuardianCalib.BAND_MID));
    }

    @Test
    public void estimate_missingBand_fallsBackToSingleThenDefault() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        // 只有近距样本（实测近距 16-17s），远距威胁缺档 → single 空 → 默认 38
        assertTrue(GuardianCalib.updated(t, 10, 16, 20, 60));
        assertEquals(16L, GuardianCalib.estimate(t, GuardianCalib.BAND_NEAR));
        assertEquals(GuardianCalib.DEFAULT_D_SEC, GuardianCalib.estimate(t, GuardianCalib.BAND_FAR));

        // single 有值（旧 kid 单值标定迁移/未知距离样本）：缺档回退单值
        t.single.dSec = 38;
        t.single.samples = 1;
        assertEquals(38L, GuardianCalib.estimate(t, GuardianCalib.BAND_FAR));
        assertEquals(38L, GuardianCalib.estimate(t, GuardianCalib.BAND_MID));
        // 对应档有值时档值优先于 single
        assertEquals(16L, GuardianCalib.estimate(t, GuardianCalib.BAND_NEAR));
    }

    @Test
    public void estimate_unknownBand_usesSingleBucket() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        assertTrue(GuardianCalib.updated(t, -1, 42, 20, 60));   // 缺坐标 → single
        assertEquals(0, t.near.samples);
        assertEquals(42L, GuardianCalib.estimate(t, GuardianCalib.BAND_UNKNOWN));
    }

    // ---------------------------------------------------------------- 样本更新（EWMA）

    @Test
    public void updated_firstSampleSeeds_secondEwmA() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        assertTrue(GuardianCalib.updated(t, 15, 16, 20, 60));
        assertEquals(16L, t.near.dSec);
        assertEquals(1L, t.near.samples);
        // EWMA α=0.3：round(16×0.7 + 38×0.3) = round(22.6) = 23
        assertTrue(GuardianCalib.updated(t, 12, 38, 20, 60));
        assertEquals(23L, t.near.dSec);
        assertEquals(2L, t.near.samples);
        assertTrue(t.anySample());
    }

    @Test
    public void updated_samplesLandInOwnBand() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        assertTrue(GuardianCalib.updated(t, 10, 16, 20, 60));    // near
        assertTrue(GuardianCalib.updated(t, 45, 38, 20, 60));    // mid
        assertTrue(GuardianCalib.updated(t, 120, 75, 20, 60));   // far
        assertEquals(16L, t.near.dSec);
        assertEquals(38L, t.mid.dSec);
        assertEquals(75L, t.far.dSec);
        assertEquals(1L, t.near.samples);
        assertEquals(1L, t.mid.samples);
        assertEquals(1L, t.far.samples);
    }

    @Test
    public void updated_rejectsOutOfRangeSamples() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        assertFalse("D≤0 必是解析错", GuardianCalib.updated(t, 10, 0, 20, 60));
        assertFalse(">1h 必是等待时长混进来", GuardianCalib.updated(t, 10, 3601, 20, 60));
        assertFalse(t.anySample());
    }

    // ---------------------------------------------------------------- JSON 落盘往返

    @Test
    public void jsonRoundtrip() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        assertTrue(GuardianCalib.updated(t, 10, 16, 20, 60));
        assertTrue(GuardianCalib.updated(t, 12, 17, 20, 60));
        assertTrue(GuardianCalib.updated(t, 45, 38, 20, 60));
        String json = GuardianCalib.toJson(t).optJSONObject("calib").toString();
        GuardianCalib.Table back = GuardianCalib.fromJson(json);
        assertEquals(16L, back.near.dSec);
        assertEquals(2L, back.near.samples);
        assertEquals(38L, back.mid.dSec);
        assertEquals(0L, back.far.samples);
        assertEquals(0L, back.single.samples);
        assertTrue(back.anySample());
    }

    @Test
    public void fromJson_garbageYieldsEmptyTable() {
        GuardianCalib.Table t = GuardianCalib.fromJson("这不是JSON");
        assertFalse(t.anySample());
        assertEquals(GuardianCalib.DEFAULT_D_SEC, GuardianCalib.estimate(t, GuardianCalib.BAND_NEAR));
        // 空串同样安全
        assertFalse(GuardianCalib.fromJson("").anySample());
    }

    // ---------------------------------------------------------------- 描述（日志面）

    @Test
    public void describe_showsBandSingleAndDefault() {
        GuardianCalib.Table t = new GuardianCalib.Table();
        String empty = GuardianCalib.describe(t, 10, 20, 60);
        assertTrue(empty.contains("near"));
        assertTrue(empty.contains("38s"));
        assertTrue(GuardianCalib.updated(t, 10, 16, 20, 60));
        String filled = GuardianCalib.describe(t, 10, 20, 60);
        assertTrue(filled.contains("D=16s(1样)"));
        assertTrue(GuardianCalib.describe(t, -1, 20, 60).contains("unknown"));
    }
}
