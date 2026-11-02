package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 情报出征设置纯逻辑单测（设计 §4.2/§4.3；契约键由服务端 {@code IntelFormationPlan} 冻结）。
 *
 * <p>只测 {@link IntelFormationConfig} 的 JVM 纯函数：默认值、读写/清空、比例校验、ROLE 判定。
 * 不触碰 Activity/SharedPreferences（落库路径在 {@code IntelFormationCard}）。
 */
public class IntelFormationConfigTest {

    // ==================== 默认值 ====================

    @Test
    public void read_nullOrEmpty_isDefault() throws Exception {
        for (JSONObject p : new JSONObject[]{null, new JSONObject()}) {
            IntelFormationConfig c = IntelFormationConfig.read(p);
            assertEquals("盾英雄默认自动", 0L, c.heroShield);
            assertEquals("矛英雄默认自动", 0L, c.heroLancer);
            assertEquals("弓英雄默认自动", 0L, c.heroMarksman);
            assertEquals("盾比例默认 50", 50, c.ratioInfantry);
            assertEquals("矛比例默认 20", 20, c.ratioLancer);
            assertEquals("弓比例默认 30", 30, c.ratioMarksman);
            assertEquals("总兵力默认自动", 0L, c.total);
            assertTrue("全默认", c.isDefault());
            assertEquals("50:20:30", c.ratioText());
        }
    }

    @Test
    public void defaults_matchDesignContract() throws Exception {
        assertEquals(50, IntelFormationConfig.DEFAULT_INFANTRY);
        assertEquals(20, IntelFormationConfig.DEFAULT_LANCER);
        assertEquals(30, IntelFormationConfig.DEFAULT_MARKSMAN);
        assertEquals("intel_hero_shield", IntelFormationConfig.KEY_HERO_SHIELD);
        assertEquals("intel_hero_lancer", IntelFormationConfig.KEY_HERO_LANCER);
        assertEquals("intel_hero_marksman", IntelFormationConfig.KEY_HERO_MARKSMAN);
        assertEquals("intel_ratio", IntelFormationConfig.KEY_RATIO);
        assertEquals("intel_total", IntelFormationConfig.KEY_TOTAL);
    }

    // ==================== 读写 ====================

    @Test
    public void read_parsesServerKeys() throws Exception {
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_HERO_SHIELD, 50041L);
        p.put(IntelFormationConfig.KEY_HERO_LANCER, 50029L);
        p.put(IntelFormationConfig.KEY_HERO_MARKSMAN, 50008L);
        p.put(IntelFormationConfig.KEY_RATIO, ratio(20, 30, 50));
        p.put(IntelFormationConfig.KEY_TOTAL, 30000L);

        IntelFormationConfig c = IntelFormationConfig.read(p);
        assertEquals(50041L, c.heroShield);
        assertEquals(50029L, c.heroLancer);
        assertEquals(50008L, c.heroMarksman);
        assertEquals(20, c.ratioInfantry);
        assertEquals(30, c.ratioLancer);
        assertEquals(50, c.ratioMarksman);
        assertEquals(30000L, c.total);
        assertFalse("非全默认", c.isDefault());
        assertEquals("20:30:50", c.ratioText());
    }

    @Test
    public void write_roundTripsNonDefaultValues() throws Exception {
        IntelFormationConfig c = new IntelFormationConfig();
        c.heroShield = 50041L;
        c.heroMarksman = 50008L;
        c.ratioInfantry = 5;
        c.ratioLancer = 15;
        c.ratioMarksman = 80;
        c.total = 12000L;

        JSONObject p = c.write(new JSONObject());
        assertEquals(50041L, p.optLong(IntelFormationConfig.KEY_HERO_SHIELD, -1));
        assertEquals(50008L, p.optLong(IntelFormationConfig.KEY_HERO_MARKSMAN, -1));
        assertFalse("自动（0）不落键", p.has(IntelFormationConfig.KEY_HERO_LANCER));
        JSONObject ratio = p.optJSONObject(IntelFormationConfig.KEY_RATIO);
        assertNotNull(ratio);
        assertEquals(5, ratio.optInt("infantry"));
        assertEquals(15, ratio.optInt("lancer"));
        assertEquals(80, ratio.optInt("marksman"));
        assertEquals(12000L, p.optLong(IntelFormationConfig.KEY_TOTAL, -1));

        IntelFormationConfig back = IntelFormationConfig.read(p);
        assertEquals(c.heroShield, back.heroShield);
        assertEquals(c.heroMarksman, back.heroMarksman);
        assertEquals(c.ratioInfantry, back.ratioInfantry);
        assertEquals(c.ratioLancer, back.ratioLancer);
        assertEquals(c.ratioMarksman, back.ratioMarksman);
        assertEquals(c.total, back.total);
    }

    @Test
    public void write_defaults_removesKeys() throws Exception {
        // 写默认值 == 清空（服务端回落默认自动编队，设计 §6 回滚口径）
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_HERO_SHIELD, 50041L);
        p.put(IntelFormationConfig.KEY_RATIO, ratio(20, 30, 50));
        p.put(IntelFormationConfig.KEY_TOTAL, 999L);

        JSONObject out = new IntelFormationConfig().write(p);
        assertEquals(0, out.length());
        assertFalse(out.has(IntelFormationConfig.KEY_HERO_SHIELD));
        assertFalse(out.has(IntelFormationConfig.KEY_RATIO));
        assertFalse(out.has(IntelFormationConfig.KEY_TOTAL));
    }

    @Test
    public void write_preservesOtherParamsKeys() throws Exception {
        // 情报页与统一编队共用同一 params：写 intel_* 不得冲掉 formation_mode 等键
        JSONObject p = new JSONObject();
        p.put("formation_mode", "AUTOMATIC");
        p.put("sub", new JSONObject().put("events", true));

        IntelFormationConfig c = new IntelFormationConfig();
        c.ratioInfantry = 20;
        c.ratioLancer = 30;
        c.ratioMarksman = 50;
        JSONObject out = c.write(p);
        assertEquals("AUTOMATIC", out.optString("formation_mode"));
        assertNotNull(out.optJSONObject("sub"));
        assertTrue(out.optJSONObject("sub").optBoolean("events"));
        JSONObject ratio = out.optJSONObject(IntelFormationConfig.KEY_RATIO);
        assertNotNull("非默认比例应落 intel_ratio", ratio);
        assertEquals(20, ratio.optInt("infantry"));
        assertEquals(30, ratio.optInt("lancer"));
        assertEquals(50, ratio.optInt("marksman"));
    }

    @Test
    public void clear_removesAllIntelKeysOnly() throws Exception {
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_HERO_SHIELD, 50041L);
        p.put(IntelFormationConfig.KEY_HERO_LANCER, 50029L);
        p.put(IntelFormationConfig.KEY_HERO_MARKSMAN, 50008L);
        p.put(IntelFormationConfig.KEY_RATIO, ratio(20, 30, 50));
        p.put(IntelFormationConfig.KEY_TOTAL, 123L);
        p.put("formation_mode", "AUTOMATIC");

        JSONObject out = IntelFormationConfig.clear(p);
        for (String key : new String[]{IntelFormationConfig.KEY_HERO_SHIELD,
                IntelFormationConfig.KEY_HERO_LANCER, IntelFormationConfig.KEY_HERO_MARKSMAN,
                IntelFormationConfig.KEY_RATIO, IntelFormationConfig.KEY_TOTAL}) {
            assertFalse("应移除 " + key, out.has(key));
        }
        assertEquals("统一编队字段保留", "AUTOMATIC", out.optString("formation_mode"));
        assertTrue(IntelFormationConfig.read(out).isDefault());
    }

    @Test
    public void clear_nullSafe() throws Exception {
        JSONObject out = IntelFormationConfig.clear(null);
        assertNotNull(out);
        assertEquals(0, out.length());
    }

    @Test
    public void read_clampsIllegalValues() throws Exception {
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_HERO_SHIELD, -5L);
        p.put(IntelFormationConfig.KEY_TOTAL, -100L);
        p.put(IntelFormationConfig.KEY_RATIO, ratio(200, -10, 80));

        IntelFormationConfig c = IntelFormationConfig.read(p);
        assertEquals("负英雄 id → 自动", 0L, c.heroShield);
        assertEquals("负总兵力 → 0", 0L, c.total);
        assertEquals("超 100 夹取", 100, c.ratioInfantry);
        assertEquals("负数夹取", 0, c.ratioLancer);
        assertEquals(80, c.ratioMarksman);
    }

    @Test
    public void read_ratioAllZeroFallsBackToDefault() throws Exception {
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_RATIO, ratio(0, 0, 0));

        IntelFormationConfig c = IntelFormationConfig.read(p);
        assertEquals(50, c.ratioInfantry);
        assertEquals(20, c.ratioLancer);
        assertEquals(30, c.ratioMarksman);
    }

    @Test
    public void read_brokenRatioTypeFallsBackToDefault() throws Exception {
        JSONObject p = new JSONObject();
        p.put(IntelFormationConfig.KEY_RATIO, "50:20:30");   // 类型错误（字符串）

        IntelFormationConfig c = IntelFormationConfig.read(p);
        assertEquals(50, c.ratioInfantry);
        assertEquals(30, c.ratioMarksman);
    }

    // ==================== 比例校验 ====================

    @Test
    public void validateRatio_acceptsDefaults() throws Exception {
        assertNull(IntelFormationConfig.validateRatio(50, 20, 30));
        assertNull("合计 >0 即可（服务端按相对权重）",
                IntelFormationConfig.validateRatio(5, 2, 3));
        assertNull("允许单项为 0", IntelFormationConfig.validateRatio(0, 0, 100));
    }

    @Test
    public void validateRatio_rejectsSumZero() throws Exception {
        assertNotNull("0/0/0 非法", IntelFormationConfig.validateRatio(0, 0, 0));
        assertTrue(IntelFormationConfig.validateRatio(0, 0, 0).contains("合计"));
    }

    @Test
    public void validateRatio_rejectsNegative() throws Exception {
        assertNotNull(IntelFormationConfig.validateRatio(-1, 10, 80));
        assertNotNull(IntelFormationConfig.validateRatio(10, -1, 80));
        assertNotNull(IntelFormationConfig.validateRatio(10, 10, -1));
        assertTrue(IntelFormationConfig.validateRatio(-1, 0, 0).contains("盾兵"));
    }

    @Test
    public void validateRatio_rejectsOver100() throws Exception {
        assertNotNull(IntelFormationConfig.validateRatio(101, 0, 0));
        assertNotNull(IntelFormationConfig.validateRatio(10, 101, 0));
        assertNotNull(IntelFormationConfig.validateRatio(10, 10, 101));
        assertTrue(IntelFormationConfig.validateRatio(0, 0, 101).contains("弓兵"));
    }

    @Test
    public void parseRatio_handlesEmptyAndGarbage() throws Exception {
        assertEquals(IntelFormationConfig.DEFAULT_MARKSMAN,
                IntelFormationConfig.parseRatio("", IntelFormationConfig.DEFAULT_MARKSMAN));
        assertEquals(IntelFormationConfig.DEFAULT_INFANTRY,
                IntelFormationConfig.parseRatio(null, IntelFormationConfig.DEFAULT_INFANTRY));
        assertEquals(IntelFormationConfig.DEFAULT_LANCER,
                IntelFormationConfig.parseRatio("abc", IntelFormationConfig.DEFAULT_LANCER));
        assertEquals(42, IntelFormationConfig.parseRatio(" 42 ", 0));
    }

    @Test
    public void clampRatio_bounds() throws Exception {
        assertEquals(0, IntelFormationConfig.clampRatio(-9));
        assertEquals(100, IntelFormationConfig.clampRatio(101));
        assertEquals(80, IntelFormationConfig.clampRatio(80));
    }

    // ==================== ROLE 判定 ====================

    @Test
    public void isRoleBound_falseByDefault() throws Exception {
        assertFalse(IntelFormationConfig.isRoleBound(null));
        assertFalse(IntelFormationConfig.isRoleBound(new JSONObject()));
        JSONObject p = new JSONObject();
        p.put("formation_mode", "AUTOMATIC");
        p.put("formation_id", 3L);
        assertFalse("AUTOMATIC 即使残留 id 也不算指定编队",
                IntelFormationConfig.isRoleBound(p));
    }

    @Test
    public void isRoleBound_trueWithRoleAndId() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 7L);
        assertTrue(IntelFormationConfig.isRoleBound(p));
    }

    @Test
    public void isRoleBound_trueWithRoleAndIdArray() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "ROLE");
        p.put("formation_ids", new JSONArray().put(3L));
        assertTrue(IntelFormationConfig.isRoleBound(p));
    }

    @Test
    public void isRoleBound_falseWhenRoleWithoutBinding() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "ROLE");
        assertFalse("ROLE 但未绑定编队（空 id）→ 仍按本页设置生效",
                IntelFormationConfig.isRoleBound(p));
        p.put("formation_ids", new JSONArray());
        assertFalse(IntelFormationConfig.isRoleBound(p));
        p.put("formation_ids", new JSONArray().put(0L));
        assertFalse(IntelFormationConfig.isRoleBound(p));
    }

    @Test
    public void normalizeMode_aliases() throws Exception {
        assertEquals(IntelFormationConfig.MODE_ROLE,
                IntelFormationConfig.normalizeMode("normal"));
        assertEquals(IntelFormationConfig.MODE_ROLE,
                IntelFormationConfig.normalizeMode("CUSTOM"));
        assertEquals(IntelFormationConfig.MODE_ROLE,
                IntelFormationConfig.normalizeMode("role"));
        assertEquals(IntelFormationConfig.MODE_AUTOMATIC,
                IntelFormationConfig.normalizeMode("AUTOMATIC"));
        assertEquals(IntelFormationConfig.MODE_AUTOMATIC,
                IntelFormationConfig.normalizeMode(""));
        assertEquals(IntelFormationConfig.MODE_AUTOMATIC,
                IntelFormationConfig.normalizeMode(null));
    }

    @Test
    public void isRoleBound_acceptsLegacyModeAliases() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "NORMAL");
        p.put("formation_id", 4L);
        assertTrue("旧版 NORMAL 视为 ROLE", IntelFormationConfig.isRoleBound(p));
    }

    // ==================== 工具 ====================

    private static JSONObject ratio(int inf, int lancer, int marksman) throws Exception {
        JSONObject o = new JSONObject();
        o.put("infantry", inf);
        o.put("lancer", lancer);
        o.put("marksman", marksman);
        return o;
    }
}
