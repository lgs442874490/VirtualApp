package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批次10：统一编队库纯函数单测（序列化/校验/隔离键/编解码/目录解析）。
 *
 * <p>契约依据（vault-server 冻结）：{@code params["formation_library"]} 为字符串 JSON
 * {@code {"formations":[{"id","name","hero_ids","soldiers","ratio","total"}]}}；
 * 每功能 params 用 formation_mode/formation_id/soldiers/ratio/total；巨熊加 join_formations。
 * 本测试只跑 JVM 可测静态逻辑，不触碰 SharedPreferences/Activity。
 */
public class FormationStoreTest {

    private static FormationStore.Formation formation(int id, String name) {
        FormationStore.Formation f = new FormationStore.Formation(name,
                new ArrayList<>(Arrays.asList(50041L, 50039L)));
        f.id = id;
        return f;
    }

    // ==================== 隔离键 ====================

    @Test
    public void prefKey_isPerFpidAndSid() {
        // 同一账号不同区服 / 同区服不同账号必须互相隔离
        assertEquals("f:48460403:888", FormationStore.prefKey("48460403", 888));
        assertEquals("f:48460403:724", FormationStore.prefKey("48460403", 724));
        assertEquals("f:57259770:888", FormationStore.prefKey("57259770", 888));
        assertTrue("fpid/sid 不同 → key 不同",
                !FormationStore.prefKey("48460403", 888).equals(
                        FormationStore.prefKey("48460403", 724)));
        assertEquals("fpid 空值兜底", "f::888", FormationStore.prefKey(null, 888));
    }

    @Test
    public void catalogStateKey_matchesServerFrozenKey() {
        assertEquals("srv:formation:catalog:48460403:888",
                FormationStore.catalogStateKey("48460403", 888));
        assertEquals("srv:formation:catalog::888",
                FormationStore.catalogStateKey(null, 888));
    }

    // ==================== 序列化 / 反序列化 ====================

    @Test
    public void libraryJson_usesFrozenContractKeys() throws Exception {
        FormationStore.Formation f = formation(1, "采集队");
        f.infantry = 20000;
        f.lancer = 1549;
        f.marksman = 28451;
        f.ratioInfantry = 40;
        f.ratioLancer = 3;
        f.ratioMarksman = 57;
        f.total = 50000;
        String json = FormationStore.libraryJson(List.of(f));
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("formations");
        assertEquals(1, arr.length());
        JSONObject o = arr.getJSONObject(0);
        assertEquals(1, o.getInt("id"));
        assertEquals("采集队", o.getString("name"));
        assertEquals(2, o.getJSONArray("hero_ids").length());
        assertEquals(50041L, o.getJSONArray("hero_ids").getLong(0));
        JSONObject soldiers = o.getJSONObject("soldiers");
        assertEquals(20000L, soldiers.getLong("infantry"));
        assertEquals(1549L, soldiers.getLong("lancer"));
        assertEquals(28451L, soldiers.getLong("marksman"));
        JSONObject ratio = o.getJSONObject("ratio");
        assertEquals(40, ratio.getInt("infantry"));
        assertEquals(3, ratio.getInt("lancer"));
        assertEquals(57, ratio.getInt("marksman"));
        assertEquals(50000L, o.getLong("total"));
    }

    @Test
    public void libraryJson_parseRoundTrip() {
        FormationStore.Formation a = formation(1, "采集队");
        a.infantry = 20000;
        a.lancer = 3000;
        a.marksman = 27000;
        a.total = 50000;
        FormationStore.Formation b = formation(2, "巨熊队");
        b.ratioMode = true;
        b.ratioInfantry = 40;
        b.ratioLancer = 30;
        b.ratioMarksman = 30;
        b.total = 0;
        String json = FormationStore.libraryJson(Arrays.asList(a, b));
        List<FormationStore.Formation> back = FormationStore.parseLibrary(json);
        assertEquals(2, back.size());
        assertEquals("采集队", back.get(0).name);
        assertEquals(Arrays.asList(50041L, 50039L), back.get(0).heroIds);
        assertEquals(20000, back.get(0).infantry);
        assertEquals(50000, back.get(0).total);
        assertFalse("数量模式不被误判为比例模式", back.get(0).ratioMode);
        assertEquals("巨熊队", back.get(1).name);
        assertTrue("比例模式还原", back.get(1).ratioMode);
        assertEquals(40, back.get(1).ratioInfantry);
        assertEquals(30, back.get(1).ratioLancer);
        assertEquals(30, back.get(1).ratioMarksman);
        assertEquals(0, back.get(1).total);
    }

    @Test
    public void parseLibrary_tolerant() {
        assertTrue(FormationStore.parseLibrary(null).isEmpty());
        assertTrue(FormationStore.parseLibrary("").isEmpty());
        assertTrue("非法 JSON 不抛异常", FormationStore.parseLibrary("{bad json").isEmpty());
        assertTrue("缺 formations 键", FormationStore.parseLibrary("{\"x\":1}").isEmpty());
        // 坏条目（id<=0）跳过，好条目保留
        String json = "{\"formations\":[{\"id\":0,\"name\":\"坏\"},"
                + "{\"id\":3,\"name\":\"好\",\"hero_ids\":[1]}]}";
        List<FormationStore.Formation> list = FormationStore.parseLibrary(json);
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).id);
    }

    @Test
    public void fromJson_acceptsCommaSeparatedHeroIds() throws Exception {
        FormationStore.Formation f = FormationStore.fromJson(
                new JSONObject().put("id", 1).put("name", "x").put("hero_ids", "50041, 50039,abc"));
        assertNotNull(f);
        assertEquals(2, f.heroIds.size());
        assertEquals(50041L, (long) f.heroIds.get(0));
    }

    // ==================== 校验 ====================

    @Test
    public void validate_nameNotEmpty() {
        FormationStore.Formation f = formation(1, "  ");
        f.infantry = 100;
        f.total = 100;
        assertTrue(FormationStore.validate(f).contains("名称"));
        assertNull(FormationStore.validate(formation(1, "采集队") == null ? null : withTroops()));
    }

    private FormationStore.Formation withTroops() {
        FormationStore.Formation f = formation(1, "采集队");
        f.infantry = 20000;
        f.lancer = 15000;
        f.marksman = 15000;
        f.total = 50000;
        return f;
    }

    @Test
    public void validate_heroCountOneToFive() {
        FormationStore.Formation none = withTroops();
        none.heroIds.clear();
        assertTrue("0 英雄非法", FormationStore.validate(none).contains("英雄"));
        FormationStore.Formation six = withTroops();
        six.heroIds.clear();
        for (long i = 1; i <= 6; i++) six.heroIds.add(50000 + i);
        assertTrue("6 英雄非法", FormationStore.validate(six).contains("最多"));
        FormationStore.Formation five = withTroops();
        five.heroIds.clear();
        for (long i = 1; i <= 5; i++) five.heroIds.add(50000 + i);
        assertNull("5 英雄合法", FormationStore.validate(five));
        FormationStore.Formation dup = withTroops();
        dup.heroIds.clear();
        dup.heroIds.add(50041L);
        dup.heroIds.add(50041L);
        assertNull("重复英雄按去重后 1 个合法", FormationStore.validate(dup));
    }

    @Test
    public void validate_soldierCounts() {
        FormationStore.Formation zero = new FormationStore.Formation("空队",
                List.of(50041L));
        assertTrue("数量模式兵力全 0 非法", FormationStore.validate(zero).contains("兵力"));
        FormationStore.Formation neg = withTroops();
        neg.infantry = -1;
        assertTrue(FormationStore.validate(neg).contains("负数"));
    }

    @Test
    public void validate_ratioModeSumMustBe100() {
        FormationStore.Formation f = new FormationStore.Formation("比例队", List.of(50041L));
        f.ratioMode = true;
        f.ratioInfantry = 40;
        f.ratioLancer = 30;
        f.ratioMarksman = 20;   // 90
        assertTrue(FormationStore.validate(f).contains("100"));
        f.ratioMarksman = 30;
        assertNull("比例和 100 合法（总量 0=按容量也能保存）", FormationStore.validate(f));
    }

    @Test
    public void validate_nameLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 21; i++) sb.append('队');
        FormationStore.Formation f = withTroops();
        f.name = sb.toString();
        assertTrue(FormationStore.validate(f).contains("20"));
    }

    // ==================== id 分配 / 摘要 / 模式 ====================

    @Test
    public void nextId_sequence() {
        assertEquals("空库从 1 开始", 1, FormationStore.nextId(new ArrayList<>()));
        List<FormationStore.Formation> list = new ArrayList<>();
        list.add(formation(1, "a"));
        assertEquals(2, FormationStore.nextId(list));
        list.add(formation(7, "b"));
        assertEquals(8, FormationStore.nextId(list));
        list.add(formation(3, "c"));
        assertEquals("取最大 +1", 8, FormationStore.nextId(list));
    }

    @Test
    public void summary_countAndRatioModes() {
        FormationStore.Formation count = withTroops();
        String s1 = FormationStore.summary(count);
        assertTrue(s1.contains("英雄2名"));
        assertTrue(s1.contains("盾20000"));
        assertTrue(s1.contains("矛15000"));
        assertTrue(s1.contains("弓15000"));
        FormationStore.Formation ratio = new FormationStore.Formation("比例队", List.of(50041L));
        ratio.ratioMode = true;
        ratio.ratioInfantry = 40;
        ratio.ratioLancer = 30;
        ratio.ratioMarksman = 30;
        String s2 = FormationStore.summary(ratio);
        assertTrue(s2.contains("比例40:30:30"));
        assertTrue(s2.contains("按容量"));
        assertNotNull(FormationStore.summary(null));
        assertTrue(FormationStore.summary(null).contains("未选择"));
    }

    @Test
    public void normalizeMode_webParity() {
        assertEquals(FormationStore.MODE_AUTOMATIC, FormationStore.normalizeMode(null));
        assertEquals(FormationStore.MODE_AUTOMATIC, FormationStore.normalizeMode(""));
        assertEquals(FormationStore.MODE_ROLE, FormationStore.normalizeMode("ROLE"));
        assertEquals(FormationStore.MODE_ROLE, FormationStore.normalizeMode("role"));
        assertEquals("网页版 NORMAL→ROLE", FormationStore.MODE_ROLE,
                FormationStore.normalizeMode("NORMAL"));
        assertEquals("网页版 CUSTOM→ROLE", FormationStore.MODE_ROLE,
                FormationStore.normalizeMode("CUSTOM"));
        assertEquals("未知值回落到自动", FormationStore.MODE_AUTOMATIC,
                FormationStore.normalizeMode("WHATEVER"));
        assertEquals("指定编队", FormationStore.modeLabel("ROLE"));
        assertEquals("自动编队", FormationStore.modeLabel("AUTOMATIC"));
    }

    // ==================== 编队目录解析 ====================

    @Test
    public void catalogShape_capacityBestIdsStockFresh() throws Exception {
        // 服务端 FormationCatalog 冻结形状（batch10）：
        // {"capturedAt":ms,"capacity":5,"heroes":[...],"soldiers":{...,"best_ids":[...]}}
        JSONObject catalog = new JSONObject(
                "{\"capturedAt\":1792700000000,\"capacity\":5,"
                        + "\"heroes\":[{\"id\":50041,\"name\":\"艾玛\"}],"
                        + "\"soldiers\":{\"infantry\":80000,\"lancer\":40000,"
                        + "\"marksman\":30000,\"best_ids\":[11000,21000,31000]}}");
        assertEquals(5L, FormationStore.capacity(catalog));
        assertEquals(-1L, FormationStore.capacity(null));
        assertEquals(-1L, FormationStore.capacity(new JSONObject()));
        assertEquals(Arrays.asList(11000L, 21000L, 31000L),
                FormationStore.bestSoldierIds(catalog));
        assertTrue(FormationStore.bestSoldierIds(new JSONObject()).isEmpty());
        assertEquals("盾80000/矛40000/弓30000", FormationStore.soldierStockText(catalog));
        assertEquals("", FormationStore.soldierStockText(null));
        assertTrue("capturedAt 新鲜", FormationStore.catalogFresh(catalog,
                1792700000000L + 60_000L, 10 * 60_000L));
        assertFalse("capturedAt 过期", FormationStore.catalogFresh(catalog,
                1792700000000L + 11 * 60_000L, 10 * 60_000L));
        assertTrue("无 capturedAt 按新鲜（不拦 UI）", FormationStore.catalogFresh(
                new JSONObject(), 1L, 1000L));
    }

    @Test
    public void parseHeroes_andSoldierStock() throws Exception {
        JSONObject catalog = new JSONObject(
                "{\"heroes\":[{\"id\":50041,\"name\":\"艾玛\",\"level\":60,\"star\":5,"
                        + "\"power\":1200000,\"status\":\"idle\"},"
                        + "{\"id\":0,\"name\":\"坏条目\"}],"
                        + "\"soldiers\":{\"infantry\":80000,\"lancer\":40000,\"marksman\":30000},"
                        + "\"capacity\":6}");
        List<FormationStore.Hero> heroes = FormationStore.parseHeroes(catalog);
        assertEquals("坏条目跳过", 1, heroes.size());
        assertEquals(50041L, heroes.get(0).id);
        assertEquals("艾玛", heroes.get(0).name);
        assertEquals(60, heroes.get(0).level);
        assertEquals(5, heroes.get(0).star);
        assertTrue(heroes.get(0).label().contains("Lv60"));
        assertEquals(80000L, FormationStore.soldierStock(catalog, "infantry"));
        assertEquals(-1, FormationStore.soldierStock(catalog, "unknown"));
        assertEquals(-1, FormationStore.soldierStock(null, "infantry"));
        assertTrue(FormationStore.parseHeroes(null).isEmpty());
    }

    @Test
    public void heroesText_usesCatalogNames() {
        FormationStore.Formation f = formation(1, "采集队");
        FormationStore.Hero h = new FormationStore.Hero();
        h.id = 50041;
        h.name = "艾玛";
        assertEquals("艾玛、50039", FormationStore.heroesText(f, List.of(h)));
        assertEquals("50041、50039", FormationStore.heroesText(f, null));
    }

    // ==================== 巨熊 join_formations 元素 ====================

    @Test
    public void joinElement_autoHasNoFormationId() throws Exception {
        JSONObject auto = FormationStore.joinElement(3, "AUTOMATIC", 0);
        assertEquals(3, auto.getInt("id"));
        assertEquals("AUTOMATIC", auto.getString("mode"));
        assertFalse("自动上车不写 formation_id", auto.has("formation_id"));
        JSONObject role = FormationStore.joinElement(2, "ROLE", 5);
        assertEquals(2, role.getInt("id"));
        assertEquals("ROLE", role.getString("mode"));
        assertEquals(5L, role.getLong("formation_id"));
        // 指定但没给 id：不写非法 0
        JSONObject bad = FormationStore.joinElement(2, "ROLE", 0);
        assertFalse(bad.has("formation_id"));
    }

    // ==================== 库 JSON 空值行为（同步兼容） ====================

    @Test
    public void emptyLibraryJson_isValidButEmpty() throws Exception {
        String json = FormationStore.libraryJson(new ArrayList<>());
        JSONObject o = new JSONObject(json);
        assertEquals(0, o.getJSONArray("formations").length());
        assertTrue(FormationStore.parseLibrary(json).isEmpty());
    }
}
