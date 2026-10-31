package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 K：守护页钻石开关 + 集结兵力阈值（App params 形状/校验/账本统计）。
 *
 * <p>参数落点与其它 guardian 参数一致（sub/strategy/顶层均可解析，保存写顶层）；
 * 钻石关闭时不得下发 {@code diamond_speedup_enabled}；阈值默认 300000，0~10,000,000。
 */
public class GuardianDiamondCfgTest {

    // ---------------- 默认值 ----------------

    @Test
    public void defaults_diamondOff_andRallyThreshold300k() {
        FnParams.GuardianCfg c = FnParams.guardianCfg(null);
        assertFalse("钻石加速默认关（真实花钻）", c.diamondSpeedup);
        assertEquals("集结兵力阈值默认 300000",
                FnParams.GUARDIAN_DEFAULT_RALLY_TROOPS_MIN, c.rallyTroopsMin);
        assertEquals(300_000L, c.rallyTroopsMin);
    }

    // ---------------- 解析形状（sub/strategy/顶层） ----------------

    @Test
    public void parse_diamondAndRallyFromAnyLocation() throws Exception {
        FnParams.GuardianCfg top = FnParams.guardianCfg(new JSONObject(
                "{\"diamond_speedup_enabled\":true,\"rally_troops_min\":500000}"));
        assertTrue(top.diamondSpeedup);
        assertEquals(500_000L, top.rallyTroopsMin);

        FnParams.GuardianCfg sub = FnParams.guardianCfg(new JSONObject(
                "{\"sub\":{\"diamond_speedup_enabled\":\"1\",\"rally_troops_min\":\"800000\"}}"));
        assertTrue("字符串 1 也认", sub.diamondSpeedup);
        assertEquals(800_000L, sub.rallyTroopsMin);

        FnParams.GuardianCfg st = FnParams.guardianCfg(new JSONObject(
                "{\"strategy\":{\"diamond_speedup_enabled\":true,\"rally_troops_min\":100}}"));
        assertTrue(st.diamondSpeedup);
        assertEquals(100L, st.rallyTroopsMin);

        FnParams.GuardianCfg clampHigh = FnParams.guardianCfg(new JSONObject(
                "{\"rally_troops_min\":99999999}"));
        assertEquals("超上限夹到 10000000", FnParams.GUARDIAN_MAX_RALLY_TROOPS_MIN,
                clampHigh.rallyTroopsMin);
        FnParams.GuardianCfg clampLow = FnParams.guardianCfg(new JSONObject(
                "{\"rally_troops_min\":-5}"));
        assertEquals("负数夹到 0", 0L, clampLow.rallyTroopsMin);
    }

    // ---------------- 保存形状 ----------------

    @Test
    public void apply_diamondOff_removesAllDiamondKeys() throws Exception {
        JSONObject prev = new JSONObject(
                "{\"enabled\":true,\"diamond_speedup_enabled\":true,"
                        + "\"sub\":{\"diamond_speedup_enabled\":true,\"watch\":true},"
                        + "\"strategy\":{\"diamond_speedup_enabled\":true,\"send\":\"any\"},"
                        + "\"formation_mode\":\"role\",\"formation_id\":7,"
                        + "\"soldiers\":{\"infantry\":1},\"rally_troops_min\":900000}");
        FnParams.GuardianCfg cfg = FnParams.guardianCfg(prev);
        assertTrue(cfg.diamondSpeedup);
        cfg.diamondSpeedup = false;
        JSONObject out = FnParams.applyGuardianCfg(prev, cfg);
        assertFalse("顶层钻石键必须移除", out.has("diamond_speedup_enabled"));
        assertFalse(out.getJSONObject("sub").has("diamond_speedup_enabled"));
        assertFalse(out.getJSONObject("strategy").has("diamond_speedup_enabled"));
        // 集结点力阈值仍落库（默认/显式值）
        assertEquals(900_000L, out.getLong("rally_troops_min"));
        // 其它字段（统一编队/策略）不得被清掉
        assertEquals("role", out.getString("formation_mode"));
        assertEquals(7L, out.getLong("formation_id"));
        assertEquals(1, out.getJSONObject("soldiers").getInt("infantry"));
        assertEquals("any", out.getJSONObject("strategy").getString("send"));
    }

    @Test
    public void apply_diamondOn_writesTopLevelKey_andRally() throws Exception {
        FnParams.GuardianCfg cfg = FnParams.guardianCfg(null);
        cfg.enabled = true;
        cfg.dryRun = false;
        cfg.diamondSpeedup = true;
        cfg.rallyTroopsMin = 450_000L;
        JSONObject out = FnParams.applyGuardianCfg(new JSONObject(), cfg);
        assertTrue(out.getBoolean("diamond_speedup_enabled"));
        assertEquals(450_000L, out.getLong("rally_troops_min"));
        assertTrue("与 speedup_enabled 独立：本页不写该键", !out.has("speedup_enabled"));
        // 重新解析一致
        FnParams.GuardianCfg back = FnParams.guardianCfg(out);
        assertTrue(back.diamondSpeedup);
        assertEquals(450_000L, back.rallyTroopsMin);
    }

    // ---------------- 阈值输入校验（Activity 保存前调用） ----------------

    @Test
    public void validateRallyTroopsMin_acceptsEmptyAndBounds_rejectsBad() {
        assertNull("空=使用默认", FnParams.validateRallyTroopsMin(""));
        assertNull(FnParams.validateRallyTroopsMin(null));
        assertNull(FnParams.validateRallyTroopsMin("0"));
        assertNull(FnParams.validateRallyTroopsMin("300000"));
        assertNull(FnParams.validateRallyTroopsMin("10000000"));

        assertNotNull("负数拒绝", FnParams.validateRallyTroopsMin("-1"));
        assertNotNull("超上限拒绝", FnParams.validateRallyTroopsMin("10000001"));
        assertNotNull("非整数拒绝", FnParams.validateRallyTroopsMin("abc"));
        assertNotNull("小数拒绝", FnParams.validateRallyTroopsMin("1.5"));

        assertEquals("空→默认 300000", 300_000L, FnParams.rallyTroopsMinOf(""));
        assertEquals("非法→默认 300000", 300_000L, FnParams.rallyTroopsMinOf("oops"));
        assertEquals(888_888L, FnParams.rallyTroopsMinOf(" 888888 "));
    }

    // ---------------- 账本统计（本场已花钻石） ----------------

    @Test
    public void guardianLedger_countsDiamondBuys() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("ev", "speedup_diamond_buy").put("itemid", 301900L));
        arr.put(new JSONObject().put("ev", "speedup_diamond_buy").put("itemid", 301900L));
        arr.put(new JSONObject().put("ev", "speedup_diamond_buy").put("itemid", 301901L));
        arr.put(new JSONObject().put("ev", "launched"));
        FnParams.GuardianLedger l = FnParams.guardianLedger(arr);
        assertEquals(2, l.diamond25);
        assertEquals(1, l.diamond50);
        String text = l.text();
        assertTrue(text.contains("钻25%×2"));
        assertTrue(text.contains("50%×1"));
        assertTrue("估算 2*400+2000=2800 钻", text.contains("2800"));
    }
}
