package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * FormationModel v2 单测：契约 §5 序列化 / 新旧/脏 JSON 解析 / 派生逻辑。
 *
 * <p>覆盖：toJson(compact=false|true)、roundtrip、旧版库条目、网页 collection 驼峰键、
 * 脏数据容忍、source 与 id 符号一致性（网页 R3）、内置派生、队长兜底、名称清洗/截断。
 */
public class FormationModelTest {

    // ==================== 序列化（契约 §5） ====================

    @Test
    public void toJson_full_usesContractKeys() throws Exception {
        FormationModel f = new FormationModel("采集木", Arrays.asList(50000L));
        f.id = 1;
        f.captainHeroId = 50000L;
        f.allocationMode = FormationModel.MODE_PERCENTAGE;
        f.infantry = 15077;
        f.lancer = 15077;
        f.marksman = 120616;
        f.infantryRatio = 10;
        f.lancerRatio = 10;
        f.marksmanRatio = 80;
        f.version = 3;
        f.updatedAt = 1792863932863L;
        JSONObject o = f.toJson(false);
        assertEquals(1L, o.getLong("id"));
        assertEquals("采集木", o.getString("name"));
        assertEquals("CUSTOM", o.getString("source"));
        assertTrue("template_key 显式 null（JSONObject.NULL）", o.isNull("template_key"));
        assertFalse(o.getBoolean("built_in"));
        assertEquals(1, o.getJSONArray("hero_ids").length());
        assertEquals(50000L, o.getLong("captain_hero_id"));
        assertEquals("PERCENTAGE", o.getString("allocation_mode"));
        assertEquals(15077L, o.getJSONObject("soldiers").getLong("infantry"));
        assertEquals(10, o.getJSONObject("ratio").getInt("infantry"));
        assertEquals(150770L, o.getLong("total"));
        assertEquals(3L, o.getLong("version"));
        assertEquals(1792863932863L, o.getLong("updated_at"));
        // total 由三兵种重算（忽略外部脏 totalSoldiers）
        f.totalSoldiers = 999;
        assertEquals(150770L, f.toJson(false).getLong("total"));
    }

    @Test
    public void toJson_compact_onlyLegacyFields() throws Exception {
        FormationModel f = new FormationModel("巨熊队", Arrays.asList(50041L, 50039L));
        f.id = 7;
        f.infantry = 1;
        JSONObject o = f.toJson(true);
        assertEquals(7L, o.getLong("id"));
        assertEquals("巨熊队", o.getString("name"));
        assertEquals(2, o.getJSONArray("hero_ids").length());
        assertNotNull(o.getJSONObject("soldiers"));
        assertNotNull(o.getJSONObject("ratio"));
        assertEquals(1L, o.getLong("total"));
        // compact 不写 v2 字段（旧服务端兼容）
        assertFalse(o.has("source"));
        assertFalse(o.has("allocation_mode"));
        assertFalse(o.has("captain_hero_id"));
        assertFalse(o.has("template_key"));
    }

    @Test
    public void roundTrip_fullFields() {
        FormationModel f = new FormationModel("跑快快", Arrays.asList(50008L, 50029L, 50034L));
        f.id = 10;
        f.captainHeroId = 50029L;
        f.allocationMode = FormationModel.MODE_PERCENTAGE;
        f.infantryRatio = 10;
        f.lancerRatio = 10;
        f.marksmanRatio = 80;
        f.infantry = 17771;
        f.lancer = 17771;
        f.marksman = 142168;
        f.version = 4;
        f.updatedAt = 1792863932863L;
        f.allHeroesAvailable = false;
        f.unavailableHeroIds.add(50008L);
        FormationModel back = FormationModel.fromJson(f.toJson(false));
        assertNotNull(back);
        assertEquals(f.id, back.id);
        assertEquals(f.name, back.name);
        assertEquals(f.heroIds, back.heroIds);
        assertEquals(Long.valueOf(50029L), back.captainHeroId);
        assertEquals(FormationModel.MODE_PERCENTAGE, back.allocationMode);
        assertEquals(10, back.infantryRatio);
        assertEquals(80, back.marksmanRatio);
        assertEquals(17771L, back.infantry);
        assertEquals(142168L, back.marksman);
        assertEquals(4L, back.version);
        assertEquals(1792863932863L, back.updatedAt);
        assertFalse(back.allHeroesAvailable);
        assertEquals(Arrays.asList(50008L), back.unavailableHeroIds);
        assertEquals("被契约要求显式写入的 source", "CUSTOM", back.source);
    }

    // ==================== 解析：旧版 / 网页 / 脏数据 ====================

    @Test
    public void fromJson_legacyLibraryEntry_infersPercentage() throws Exception {
        // 批次10 旧库形状：无 allocation_mode，soldiers 全 0、ratio 和 100 → PERCENTAGE
        JSONObject o = new JSONObject("{\"id\":2,\"name\":\"巨熊队\",\"hero_ids\":[50041],"
                + "\"soldiers\":{\"infantry\":0,\"lancer\":0,\"marksman\":0},"
                + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":0}");
        FormationModel f = FormationModel.fromJson(o);
        assertNotNull(f);
        assertEquals(2L, f.id);
        assertEquals(FormationModel.MODE_PERCENTAGE, f.allocationMode);
        assertEquals(100, f.ratioSum());
        assertEquals(0L, f.soldiersTotal());
        // 旧库有兵量 → MANUAL
        JSONObject o2 = new JSONObject("{\"id\":3,\"name\":\"手填\",\"hero_ids\":[50041],"
                + "\"soldiers\":{\"infantry\":20000,\"lancer\":15000,\"marksman\":15000},"
                + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":50000}");
        FormationModel g = FormationModel.fromJson(o2);
        assertEquals(FormationModel.MODE_MANUAL, g.allocationMode);
        assertEquals(50000L, g.soldiersTotal());
    }

    @Test
    public void fromJson_webCollectionCamelCase() throws Exception {
        // 网页 collection FormationView（档案 §2.4 实测样例）
        JSONObject o = new JSONObject("{\"id\":-1,\"source\":\"GAME\",\"sourceId\":1,"
                + "\"name\":\"赫克托\",\"heroIds\":[50025,50019,50022],\"captainHeroId\":50025,"
                + "\"allocationMode\":\"MANUAL\","
                + "\"infantryCount\":88885,\"lancerCount\":3629,\"marksmanCount\":88886,"
                + "\"infantryRatio\":0,\"lancerRatio\":0,\"marksmanRatio\":0,"
                + "\"totalSoldiers\":181400,\"version\":0,"
                + "\"updatedAt\":\"1970-01-01T00:00:00Z\",\"builtIn\":false}");
        FormationModel f = FormationModel.fromJson(o);
        assertNotNull(f);
        assertTrue(f.isGame());
        assertEquals(-1L, f.id);
        assertEquals("赫克托", f.name);
        assertEquals(Arrays.asList(50025L, 50019L, 50022L), f.heroIds);
        assertEquals(Long.valueOf(50025L), f.captainHeroId);
        assertEquals(FormationModel.MODE_MANUAL, f.allocationMode);
        assertEquals(88885L, f.infantry);
        assertEquals(3629L, f.lancer);
        assertEquals(88886L, f.marksman);
        assertEquals(181400L, f.totalSoldiers);
        assertEquals(0L, f.version);
        assertEquals(0L, f.updatedAt);
    }

    @Test
    public void fromJson_dirtyTolerated() throws Exception {
        // 空对象：id 0、无名称/英雄
        FormationModel zero = FormationModel.fromJson(new JSONObject());
        assertNotNull(zero);
        assertEquals(0L, zero.id);
        assertEquals("编队 0", zero.name);
        assertTrue(zero.heroIds.isEmpty());
        // hero_ids 逗号串 + 负数/非法项
        FormationModel comma = FormationModel.fromJson(new JSONObject()
                .put("id", 5).put("name", "x").put("hero_ids", "50041, -1, abc, 50039"));
        assertEquals(Arrays.asList(50041L, 50039L), comma.heroIds);
        // 4 个英雄 → 截断到 3（网页客户端 slice(0,3)）
        FormationModel four = FormationModel.fromJson(new JSONObject()
                .put("id", 6).put("hero_ids", new JSONArray(
                        Arrays.asList(1L, 2L, 3L, 4L))));
        assertEquals(3, four.heroIds.size());
        // null 输入
        assertNull(FormationModel.fromJson(null));
        assertTrue(FormationModel.parseLibrary("{bad json").isEmpty());
        assertTrue(FormationModel.parseLibrary(null).isEmpty());
        assertTrue(FormationModel.parseLibrary("{\"x\":1}").isEmpty());
    }

    @Test
    public void fromJson_sourceMustMatchIdSign() throws Exception {
        // 网页 R3：source 与 id 符号不一致 → 整条丢弃
        assertNull(FormationModel.fromJson(new JSONObject()
                .put("id", 3).put("source", "GAME")));
        assertNull(FormationModel.fromJson(new JSONObject()
                .put("id", -3).put("source", "CUSTOM")));
        // 一致则保留
        FormationModel game = FormationModel.fromJson(new JSONObject()
                .put("id", -3).put("source", "GAME").put("name", "g"));
        assertNotNull(game);
        assertTrue(game.isGame());
        // 无 source → 按 id 符号推导
        assertEquals("GAME", FormationModel.fromJson(new JSONObject()
                .put("id", -9).put("name", "g")).source);
        assertEquals("CUSTOM", FormationModel.fromJson(new JSONObject()
                .put("id", 9).put("name", "c")).source);
    }

    @Test
    public void fromJson_builtInDerivedFromTemplateKey() throws Exception {
        FormationModel f = FormationModel.fromJson(new JSONObject()
                .put("id", 57).put("name", "基础编队·巨兽").put("template_key", "BEHEMOTH"));
        assertTrue("templateKey 非空 → builtIn（网页 D1）", f.builtIn);
        assertEquals("BEHEMOTH", f.templateKey);
        FormationModel plain = FormationModel.fromJson(new JSONObject()
                .put("id", 8).put("name", "自定义"));
        assertFalse(plain.builtIn);
        assertNull(plain.templateKey);
    }

    @Test
    public void parseArray_dropsZeroKeepsGameAndDefaults() throws Exception {
        JSONArray arr = new JSONArray("[" +
                "{\"id\":0,\"name\":\"未保存\"}," +
                "{\"id\":1,\"name\":\"好\"}," +
                "{\"id\":-1,\"source\":\"GAME\",\"name\":\"游戏\"}]");
        List<FormationModel> list = FormationModel.parseArray(arr);
        assertEquals("id=0 未保存项不落库", 2, list.size());
        assertEquals(1L, list.get(0).id);
        assertEquals(-1L, list.get(1).id);
        assertTrue(list.get(1).isGame());
        // 裸数组库 JSON 也兼容
        assertEquals(1, FormationModel.parseLibrary("[{\"id\":2,\"name\":\"x\"}]").size());
    }

    // ==================== 派生逻辑 ====================

    @Test
    public void effectiveCaptain_fallbackAndContractString() {
        FormationModel f = new FormationModel("x", Arrays.asList(50041L, 50039L));
        assertEquals(Long.valueOf(50041L), f.effectiveCaptainId());
        assertEquals("50041", f.effectiveCaptain());
        f.captainHeroId = 50039L;
        assertEquals(Long.valueOf(50039L), f.effectiveCaptainId());
        assertEquals("50039", f.effectiveCaptain());
        // 非法队长（不在上阵列表）→ 回落首位
        f.captainHeroId = 99999L;
        assertEquals(Long.valueOf(50041L), f.effectiveCaptainId());
        // 无英雄 → null
        FormationModel empty = new FormationModel();
        assertNull(empty.effectiveCaptainId());
        assertNull(empty.effectiveCaptain());
    }

    @Test
    public void copy_isDeep() {
        FormationModel f = new FormationModel("x", Arrays.asList(1L, 2L));
        f.captainHeroId = 1L;
        f.unavailableHeroIds.add(2L);
        FormationModel c = f.copy();
        c.heroIds.add(3L);
        c.unavailableHeroIds.clear();
        c.captainHeroId = 3L;
        assertEquals(2, f.heroIds.size());
        assertEquals(1, f.unavailableHeroIds.size());
        assertEquals(Long.valueOf(1L), f.captainHeroId);
    }

    @Test
    public void isGame_isBear_isPercentage_andTotals() {
        FormationModel bear = new FormationModel("巨熊·开车队（一代·大氪）", Arrays.asList(1L));
        assertTrue(bear.isBear());
        bear.allocationMode = FormationModel.MODE_PERCENTAGE;
        assertTrue(bear.isPercentage());
        bear.infantry = 5;
        bear.lancer = 5;
        bear.marksman = 90;
        assertEquals(100L, bear.soldiersTotal());
        bear.totalSoldiers = 123456;   // 脏值不影响 soldiersTotal()
        assertEquals(100L, bear.soldiersTotal());
        assertFalse(bear.isGame());
        FormationModel game = new FormationModel();
        game.id = -2;
        assertTrue(game.isGame());
    }

    @Test
    public void cleanName_andTruncate() {
        assertEquals("巴希提", FormationModel.cleanName("\"巴希提\""));
        assertEquals("巴希提", FormationModel.cleanName("  \"巴希提\"  "));
        assertEquals("", FormationModel.cleanName(null));
        assertEquals("正常", FormationModel.cleanName("正常"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append('队');
        assertEquals(FormationModel.NAME_MAX, FormationModel.truncateName(sb.toString()).length());
        assertEquals("短名", FormationModel.truncateName(" 短名 "));
    }

    @Test
    public void normalizeHelpers() {
        assertEquals("GAME", FormationModel.normalizeSource("game"));
        assertEquals("CUSTOM", FormationModel.normalizeSource(" Custom "));
        assertEquals("", FormationModel.normalizeSource("x"));
        assertEquals(FormationModel.MODE_PERCENTAGE,
                FormationModel.normalizeAllocationMode("percentage"));
        assertEquals("非法按 MANUAL（网页 R2）", FormationModel.MODE_MANUAL,
                FormationModel.normalizeAllocationMode("WHATEVER"));
        assertEquals(FormationModel.MODE_MANUAL, FormationModel.normalizeAllocationMode(null));
        assertEquals(3, FormationModel.HERO_MAX);
        assertEquals(30, FormationModel.NAME_MAX);
    }
}
