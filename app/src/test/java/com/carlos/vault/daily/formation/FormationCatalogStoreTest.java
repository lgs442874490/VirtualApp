package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.FormationStore;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FormationCatalogStore 单测（契约 §2.3 / §3.2）：双形状解析（服务端 state v2 + 网页 collection）、
 * 容量公式、游戏编队只读镜像、目录缺失降级。
 *
 * <p>线上真实样本：{@code app/src/test/resources/formation/formations_collection.json}
 * （GET /api/daily/roles/5/formations 实测响应，43 英雄 / 12 游戏编队 / limits 181400）。
 */
public class FormationCatalogStoreTest {

    /** 读 test resources 文本。 */
    static String resource(String path) throws Exception {
        InputStream in = FormationCatalogStoreTest.class.getClassLoader()
                .getResourceAsStream(path);
        assertNotNull("测试资源缺失: " + path, in);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    // ==================== 线上 collection 形状（浏览器目录兼容） ====================

    @Test
    public void liveCollection_parsesAllSections() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse(
                resource("formation/formations_collection.json"));
        assertTrue("configured（marchCapacity.initialized）", cat.configured());
        assertTrue("automaticAvailable", cat.automaticAvailable());
        assertEquals(0L, cat.capturedAt());
        assertEquals("43 个英雄选项", 43, cat.heroOptions().size());
        assertArrayEquals("limits [3H,2H,1H,0H]", new long[]{181400, 167930, 154460, 140990},
                cat.limits());
        assertEquals(140990L, cat.fixedCapacity());
        assertEquals(0, cat.percentageBasisPoints());
        assertArrayEquals("服务端兜底自动比例", new int[]{50, 20, 30}, cat.automaticSettings());
        assertEquals(181400L, cat.limits()[0]);
        assertEquals("演出容量（全部英雄 SYNC_PENDING 不在城 → 0 英雄档）",
                140990L, cat.capacity());
        assertEquals("网页我的编队首屏 12 条", 12, cat.formations().size());
        assertEquals(9000000001L, cat.formations().get(0).id);
        assertEquals("雪猿宠物容量（avalancheCapacity）",
                13500L, cat.avalanche().getLong("configuredTroopBonus"));
        assertEquals("collection 无 memory/bear → 空对象", 0, cat.memoryStatus().length());
    }

    @Test
    public void liveCollection_heroOptionsFields() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse(
                resource("formation/formations_collection.json"));
        FormationStore.Hero h = cat.hero(50043L);
        assertNotNull(h);
        assertEquals("布兰琪", h.name);
        assertEquals(3, h.professionType);
        assertEquals("射手", h.profession);
        assertEquals(10, h.generation);
        assertEquals(13977857L, h.power);
        assertEquals(26, h.star);
        assertEquals(4, h.starDisplay);
        assertEquals(80, h.level);
        assertEquals(13470L, h.troopCapacity);
        assertFalse(h.available);
        assertEquals("SYNC_PENDING", h.availabilityState);
        assertEquals("等待同步", h.availabilityReason);
        assertEquals("等待同步", h.availabilityText());
        // 职业分布：盾/矛/弓都有
        boolean shield = false, spear = false, bow = false;
        for (FormationStore.Hero x : cat.heroOptions()) {
            if (x.professionType == 1) shield = true;
            if (x.professionType == 2) spear = true;
            if (x.professionType == 3) bow = true;
        }
        assertTrue(shield && spear && bow);
    }

    @Test
    public void liveCollection_gameFormationsReadonly() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse(
                resource("formation/formations_collection.json"));
        List<FormationModel> games = cat.gameFormations();
        assertEquals(12, games.size());
        for (FormationModel g : games) {
            assertTrue("游戏编队 id<0（=-slotId）", g.id < 0);
            assertTrue("source=GAME", g.isGame());
            assertEquals("GAME", g.source);
            assertTrue("sourceId 还原 = |id|", -g.id >= 1 && -g.id <= 12);
            assertFalse("游戏编队非内置", g.builtIn);
        }
        FormationModel first = games.get(0);
        assertEquals(-1L, first.id);
        assertEquals("赫克托", first.name);
        assertEquals(Arrays.asList(50025L, 50019L, 50022L), first.heroIds);
        assertEquals(Long.valueOf(50025L), first.captainHeroId);
        assertEquals(181400L, first.totalSoldiers);
        // 百分比游戏编队
        FormationModel pct = null;
        for (FormationModel g : games) {
            if (g.isPercentage()) {
                pct = g;
                break;
            }
        }
        assertNotNull("存在 PERCENTAGE 游戏编队（-9 主队）", pct);
        assertEquals("PERCENTAGE", pct.allocationMode);
        assertEquals(-9L, pct.id);
    }

    // ==================== 服务端 state v2 形状（契约 §3.2） ====================

    @Test
    public void stateV2_numericProfessionAndCapacity() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse("{\"version\":2,"
                + "\"capturedAt\":1792863932863,\"configured\":true,"
                + "\"heroes\":["
                + "{\"id\":50006,\"name\":\"巴希提\",\"profession\":3,\"professionType\":3,"
                + "\"professionName\":\"射手\",\"level\":6,\"star\":0,\"starDisplay\":0,"
                + "\"power\":0,\"generation\":0,\"troopCapacity\":0,\"available\":true,"
                + "\"availabilityState\":\"AVAILABLE\",\"availabilityReason\":\"英雄在家\"},"
                + "{\"id\":50008,\"name\":\"加托\",\"profession\":1,\"professionType\":1,"
                + "\"troopCapacity\":13470,\"available\":true},"
                + "{\"id\":50016,\"name\":\"米娅\",\"profession\":2,\"professionType\":2,"
                + "\"troopCapacity\":11860,\"available\":true}],"
                + "\"marchCapacity\":{\"initialized\":true,\"message\":\"登录容量状态已同步\","
                + "\"fixedCapacity\":140990,\"percentageBasisPoints\":0,"
                + "\"zeroHeroCapacity\":140990,\"oneHeroCapacity\":154460,"
                + "\"twoHeroCapacity\":167930,\"threeHeroCapacity\":181400,"
                + "\"limits\":{\"threeHeroLimit\":181400,\"twoHeroLimit\":167930,"
                + "\"oneHeroLimit\":154460,\"zeroHeroLimit\":140990}},"
                + "\"automaticSettings\":{\"infantryRatio\":50,\"lancerRatio\":20,"
                + "\"marksmanRatio\":30},"
                + "\"avalanche\":{\"initialized\":true,\"owned\":true,\"troopBonus\":0},"
                + "\"memory\":{\"daily\":{\"exists\":false,\"savedAt\":0,\"slotCount\":0,"
                + "\"version\":0},\"battle\":{\"exists\":false,\"savedAt\":0,"
                + "\"slotCount\":0,\"version\":0}},"
                + "\"bear\":{\"applied\":false,\"templateId\":\"\",\"title\":\"\","
                + "\"generation\":0,\"tier\":\"\",\"launchFormationId\":0,"
                + "\"joinFormationIds\":[]}}");
        assertTrue(cat.configured());
        assertEquals(1792863932863L, cat.capturedAt());
        FormationStore.Hero h = cat.hero(50006L);
        assertEquals("射手", h.profession);
        assertEquals(3, h.professionType);
        assertTrue(h.available);
        assertEquals("英雄在家", h.availabilityReason);
        assertEquals("140990+13470+11860+0", 166320L,
                cat.capacityFor(Arrays.asList(50008L, 50016L, 50006L)));
        assertEquals("容量提示", "登录容量状态已同步",
                cat.marchCapacityMessage().getString("message"));
        assertTrue(cat.avalanche().getBoolean("initialized"));
        assertFalse(cat.memoryStatus().getJSONObject("daily").getBoolean("exists"));
        assertEquals("自动编队容量=在家各职业最强 3 人（50006 战力 0 但为弓唯一）",
                cat.capacity(), cat.capacityFor(cat.bestAvailableHeroIds()));
    }

    @Test
    public void stateV2_withLimitObject() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse("{\"marchCapacity\":{"
                + "\"initialized\":true,\"fixedCapacity\":100,"
                + "\"limits\":{\"threeHeroLimit\":300,\"twoHeroLimit\":200,"
                + "\"oneHeroLimit\":150,\"zeroHeroLimit\":100}}}");
        assertArrayEquals(new long[]{300, 200, 150, 100}, cat.limits());
        assertEquals(100L, cat.fixedCapacity());
        assertEquals(100L, cat.capacity());
    }

    // ==================== 目录缺失降级（契约 §2.3） ====================

    @Test
    public void emptyAndInvalid_degradeWithoutCrash() throws Exception {
        for (FormationCatalogStore cat : Arrays.asList(
                FormationCatalogStore.empty(),
                FormationCatalogStore.parse(null),
                FormationCatalogStore.parse(""),
                FormationCatalogStore.parse("{bad json"))) {
            assertFalse(cat.configured());
            assertFalse(cat.automaticAvailable());
            assertEquals(0L, cat.capturedAt());
            assertEquals(0L, cat.capacity());
            assertEquals(0L, cat.fixedCapacity());
            assertArrayEquals(new long[]{0, 0, 0, 0}, cat.limits());
            assertArrayEquals(new int[]{50, 20, 30}, cat.automaticSettings());
            assertTrue(cat.heroOptions().isEmpty());
            assertTrue(cat.gameFormations().isEmpty());
            assertTrue(cat.formations().isEmpty());
            assertNull(cat.hero(50000L));
            assertNotNull("非空对象", cat.raw());
            assertNotNull(cat.marchCapacityMessage());
            assertNotNull(cat.avalanche());
            assertNotNull(cat.memoryStatus());
        }
        // 空目录构造也安全
        FormationCatalogStore raw = new FormationCatalogStore(null);
        assertFalse(raw.configured());
    }

    @Test
    public void catalogWithoutCapacity_notConfiguredButHeroesParsed() throws Exception {
        // v1 旧目录：heroes+soldiers+capacity，无 marchCapacity → configured=false，容量 0
        FormationCatalogStore cat = FormationCatalogStore.parse(
                "{\"capturedAt\":1792700000000,\"capacity\":5,"
                        + "\"heroes\":[{\"id\":50041,\"name\":\"艾玛\",\"level\":60,\"star\":5}],"
                        + "\"soldiers\":{\"infantry\":80000}}");
        assertFalse(cat.configured());
        assertEquals(0L, cat.capacity());
        assertEquals(1, cat.heroOptions().size());
        assertEquals("艾玛", cat.heroOptions().get(0).name);
    }

    // ==================== automaticSettings 容错（网页 A3） ====================

    @Test
    public void automaticSettings_invalidSumFallsBack() throws Exception {
        assertEquals(Arrays.toString(new int[]{50, 20, 30}), Arrays.toString(
                FormationCatalogStore.parse("{\"automaticSettings\":{\"infantryRatio\":10,"
                        + "\"lancerRatio\":10,\"marksmanRatio\":10}}").automaticSettings()));
        assertEquals(Arrays.toString(new int[]{40, 30, 30}), Arrays.toString(
                FormationCatalogStore.parse("{\"automaticSettings\":{\"infantry_ratio\":40,"
                        + "\"lancer_ratio\":30,\"marksman_ratio\":30}}").automaticSettings()));
        // 负值 clamp 0 后和≠100 → 默认
        assertArrayEquals(new int[]{50, 20, 30}, FormationCatalogStore.parse(
                "{\"automaticSettings\":{\"infantryRatio\":-10,\"lancerRatio\":60,"
                        + "\"marksmanRatio\":50}}").automaticSettings());
    }

    @Test
    public void percentageBasisPointsParsed() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse("{\"marchCapacity\":{"
                + "\"initialized\":true,\"fixedCapacity\":100000,"
                + "\"percentageBasisPoints\":2500}}");
        assertEquals(2500, cat.percentageBasisPoints());
        // (100000)*(10000+2500)/10000 = 125000
        assertEquals(125000L, cat.capacityFor(new ArrayList<Long>()));
    }

    @Test
    public void memoryJsonResource_parsesStatus() throws Exception {
        // 线上记忆接口响应 {daily,battle}，服务端在 state v2 中以 memory 键承载
        JSONObject memory = new JSONObject(resource("formation/memory.json"));
        JSONObject state = new JSONObject();
        state.put("memory", memory);
        state.put("marchCapacity", new JSONObject().put("initialized", true)
                .put("fixedCapacity", 10));
        FormationCatalogStore cat = new FormationCatalogStore(state);
        assertTrue(cat.configured());
        assertFalse(cat.memoryStatus().getJSONObject("daily").getBoolean("exists"));
        assertFalse(cat.memoryStatus().getJSONObject("battle").getBoolean("exists"));
        assertEquals(0, cat.memoryStatus().getJSONObject("daily").getInt("slotCount"));
    }
}
