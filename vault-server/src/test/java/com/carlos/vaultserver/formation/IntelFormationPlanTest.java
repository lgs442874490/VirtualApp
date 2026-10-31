package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 情报出征编队单测（设计契约 §4；{@code design/情报-每日三轮与出征编队-20261031.md}）：
 * <ul>
 *   <li>默认最强盾 + 波根（即便别的矛更强）+ 吉娜（即便别的弓更强）；</li>
 *   <li>目录无波根/吉娜 → 最强矛/最强弓；</li>
 *   <li>指定英雄在途（available=false）/301 拉黑/不存在/职业不符 → 同职业最强可用回退；</li>
 *   <li>默认比例 10:10:80；显式 intel_ratio 覆盖；旧 ratio 次之；</li>
 *   <li>ROLE 指定编队优先（formation_library + formation_mode=ROLE + formation_id）。</li>
 * </ul>
 */
public class IntelFormationPlanTest {

    /** 英雄（id, 等级, 星级, 战力）：战力刻意制造“更强但不是默认英雄”的矛/弓。 */
    private static final long SHIELD_TOP = 50001L;    // 盾 9000
    private static final long SHIELD_2 = 50004L;      // 盾 5000
    private static final long LANCER_TOP = 50039L;    // 最强矛 9000
    private static final long LANCER_2 = 50035L;      // 次强矛 6000
    private static final long LANCER_BORGAN = 50029L; // 波根（默认矛位；比 50039/50035 弱）
    private static final long MARK_TOP = 50033L;      // 最强弓 9000
    private static final long MARK_2 = 50031L;        // 次强弓 6000
    private static final long MARK_GINA = 50008L;     // 吉娜（默认弓位；比 50033/50031 弱）

    private static final String LIB_JSON = "{\"formations\":["
            + "{\"id\":1,\"name\":\"情报队\",\"hero_ids\":[50001,50029,50008],"
            + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451}}]}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TestEngines.FakeEngine engine;
    private StateStore state;

    @Before
    public void setUp() {
        engine = new TestEngines.FakeEngine();
        state = new StateStore(tmp.getRoot().toPath());
        FormationCatalog.reset(engine);
    }

    @After
    public void tearDown() {
        HeroAvailability.clear(engine);
        FormationCatalog.reset(engine);
        CoreAssetRegistry.global().clearAll();
    }

    // ------------------------------------------------------------------
    // 纯函数：参数解析
    // ------------------------------------------------------------------

    @Test
    public void preferredHeroesOf_defaultsBorganAndGina_zeroMeansAuto() {
        long[] def = IntelFormationPlan.preferredHeroesOf(new JSONObject());
        assertEquals("盾 0=最强可用", 0L, def[1]);
        assertEquals("矛 0=波根", IntelFormationPlan.HERO_BORGAN, def[2]);
        assertEquals("弓 0=吉娜", IntelFormationPlan.HERO_GINA, def[3]);
        assertEquals(50029L, IntelFormationPlan.HERO_BORGAN);
        assertEquals(50008L, IntelFormationPlan.HERO_GINA);

        JSONObject p = new JSONObject();
        p.put("intel_hero_shield", 50004L);
        p.put("intel_hero_lancer", LANCER_2);
        p.put("intel_hero_marksman", MARK_2);
        long[] explicit = IntelFormationPlan.preferredHeroesOf(p);
        assertEquals(50004L, explicit[1]);
        assertEquals(LANCER_2, explicit[2]);
        assertEquals(MARK_2, explicit[3]);
    }

    @Test
    public void ratioOf_defaultTenTenEighty_intelRatioThenLegacyRatio() {
        assertEquals(10L, (long) IntelFormationPlan.ratioOf(new JSONObject()).get("infantry"));
        assertEquals(10L, (long) IntelFormationPlan.ratioOf(new JSONObject()).get("lancer"));
        assertEquals(80L, (long) IntelFormationPlan.ratioOf(new JSONObject()).get("marksman"));

        JSONObject explicit = new JSONObject();
        explicit.put("intel_ratio", new JSONObject(
                "{\"infantry\":20,\"lancer\":30,\"marksman\":50}"));
        // 显式 intel_ratio 压过旧 ratio
        explicit.put("ratio", new JSONObject("{\"infantry\":1,\"lancer\":1,\"marksman\":1}"));
        Map<String, Long> r = IntelFormationPlan.ratioOf(explicit);
        assertEquals(20L, (long) r.get("infantry"));
        assertEquals(30L, (long) r.get("lancer"));
        assertEquals(50L, (long) r.get("marksman"));

        // 无 intel_ratio 时用旧 ratio（缺失项按情报默认补齐，不是 40:30:30）
        JSONObject legacy = new JSONObject();
        legacy.put("ratio", new JSONObject("{\"infantry\":1,\"lancer\":1,\"marksman\":1}"));
        Map<String, Long> lr = IntelFormationPlan.ratioOf(legacy);
        assertEquals(1L, (long) lr.get("infantry"));
        assertEquals(1L, (long) lr.get("lancer"));
        assertEquals(1L, (long) lr.get("marksman"));

        JSONObject partial = new JSONObject();
        partial.put("intel_ratio", new JSONObject("{\"infantry\":50}"));
        Map<String, Long> pr = IntelFormationPlan.ratioOf(partial);
        assertEquals(50L, (long) pr.get("infantry"));
        assertEquals("缺失项按默认补", 10L, (long) pr.get("lancer"));
        assertEquals(80L, (long) pr.get("marksman"));

        // total：intel_total 优先，其次旧 total
        JSONObject t = new JSONObject();
        assertEquals(0L, IntelFormationPlan.totalOf(t));
        t.put("total", 900L);
        assertEquals(900L, IntelFormationPlan.totalOf(t));
        t.put("intel_total", 1200L);
        assertEquals(1200L, IntelFormationPlan.totalOf(t));
    }

    // ------------------------------------------------------------------
    // 默认编队：最强盾 + 波根 + 吉娜（即使其他矛/弓更强）
    // ------------------------------------------------------------------

    @Test
    public void resolve_defaultStrongestShieldBorganGina_evenIfOthersStronger() {
        presetAll(true);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(new JSONObject()));
        assertTrue(ro.reason, ro.ready());
        assertEquals(ResolvedFormation.SRC_AUTO, ro.formation.source);
        assertEquals("最强盾 + 波根 + 吉娜（即便 50039/50033 更强）",
                List.of(SHIELD_TOP, LANCER_BORGAN, MARK_GINA), ro.formation.heroIds);
        assertTrue("reason 必须注明情报编队与盾/矛/弓：" + ro.reason,
                ro.reason.contains("情报编队（盾/矛/弓=50001/50029/50008）"));
        assertTrue(ro.reason.contains("比例 10:10:80"));
    }

    @Test
    public void resolve_catalogWithoutBorganGina_fallsBackToStrongestLancerAndMarksman() {
        presetAll(false);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(new JSONObject()));
        assertTrue(ro.reason, ro.ready());
        assertEquals("无波根/吉娜 → 最强矛/最强弓",
                List.of(SHIELD_TOP, LANCER_TOP, MARK_TOP), ro.formation.heroIds);
    }

    // ------------------------------------------------------------------
    // 指定英雄不可用 → 同职业最强可用回退
    // ------------------------------------------------------------------

    @Test
    public void resolve_explicitHeroes_usedWhenAvailable() {
        presetAll(true);
        JSONObject p = new JSONObject();
        p.put("intel_hero_lancer", LANCER_2);
        p.put("intel_hero_marksman", MARK_2);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals(List.of(SHIELD_TOP, LANCER_2, MARK_2), ro.formation.heroIds);
    }

    @Test
    public void resolve_explicitHero301Blocked_fallsBackToStrongestAvailable() {
        presetAll(true);
        HeroAvailability.block(engine, List.of(LANCER_2), 60_000L);
        JSONObject p = new JSONObject();
        p.put("intel_hero_lancer", LANCER_2);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals("301 拉黑 → 同职业最强可用（50039）", LANCER_TOP,
                (long) ro.formation.heroIds.get(1));
        assertFalse(ro.formation.heroIds.contains(LANCER_2));
    }

    @Test
    public void resolve_explicitHeroMissingOrWrongProfession_fallsBackToStrongest() {
        presetAll(true);
        JSONObject missing = new JSONObject();
        missing.put("intel_hero_lancer", 999999L);          // 目录不存在
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(missing));
        assertTrue(ro.reason, ro.ready());
        assertEquals("不存在 → 同职业最强可用", LANCER_TOP, (long) ro.formation.heroIds.get(1));

        // 职业不符（矛位填了弓英雄 50033）→ 视为不可用，回退最强矛
        JSONObject wrong = new JSONObject();
        wrong.put("intel_hero_lancer", MARK_TOP);
        FormationResolver.ResolveOutcome ro2 = IntelFormationPlan.resolve(context(wrong));
        assertTrue(ro2.reason, ro2.ready());
        assertEquals(LANCER_TOP, (long) ro2.formation.heroIds.get(1));
    }

    @Test
    public void resolve_explicitHeroBusyInMarch_fallsBackToStrongestAvailable() {
        presetAll(true);
        // 预置一条在途行军，英雄 50035 不在城（目录 available=false + busyHeroIds 命中）
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        readyAssetLibrary(lib);
        Map<String, Object> march = TestEngines.FakeEngine.mapOf(
                "id", 1L, "type", 4L, "state", 3L, "endtime", 1_900_000_000L,
                "squad_list", TestEngines.FakeEngine.mapOf("hero_id",
                        TestEngines.FakeEngine.mapOf("1.0",
                                TestEngines.FakeEngine.pair(1L, LANCER_2))));
        lib.accept("notify_world_player_init", TestEngines.FakeEngine.mapOf(
                "march_info", Arrays.asList(march),
                "world_extra_march", TestEngines.FakeEngine.mapOf("value", 0L)));
        assertTrue(FormationCatalog.busyHeroIds(engine).contains(LANCER_2));
        FormationCatalog.capture(engine);
        assertFalse("在途英雄目录 available=false",
                heroAvailable(engine, LANCER_2));

        JSONObject p = new JSONObject();
        p.put("intel_hero_lancer", LANCER_2);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals("在途占用 → 同职业最强可用", LANCER_TOP,
                (long) ro.formation.heroIds.get(1));
    }

    @Test
    public void preferredProfessionHeroes_excludesUsedHeroes_andIsProfessionUnique() {
        presetAll(true);
        List<Long> picks = FormationResolver.preferredProfessionHeroes(
                engine, new long[]{0L, 0L, 0L, 0L}, List.of(LANCER_TOP, MARK_TOP));
        assertEquals("前序队列已用英雄必须排除", List.of(SHIELD_TOP, LANCER_2, MARK_2), picks);
        assertEquals("职业唯一/顺序 盾→矛→弓", 3, picks.size());
    }

    // ------------------------------------------------------------------
    // 比例与总兵力
    // ------------------------------------------------------------------

    @Test
    public void resolve_defaultRatio_isTenTenEighty() {
        presetAll(true);
        JSONObject p = new JSONObject();
        p.put("intel_total", 1000L);
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals(100L, ro.formation.infantry);
        assertEquals(100L, ro.formation.lancer);
        assertEquals(800L, ro.formation.marksman);
        // payload 里的 soldier 数量即 10:10:80
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> soldiers =
                (List<Map<String, Object>>) ro.formation.toFormationPayload().get("soldier");
        assertEquals(3, soldiers.size());
        assertEquals(100L, ((Number) soldiers.get(0).get("v")).longValue());
        assertEquals(100L, ((Number) soldiers.get(1).get("v")).longValue());
        assertEquals(800L, ((Number) soldiers.get(2).get("v")).longValue());
    }

    @Test
    public void resolve_explicitIntelRatio_overridesDefault_andLegacyRatioComesSecond() {
        presetAll(true);
        JSONObject p = new JSONObject();
        p.put("intel_total", 1000L);
        p.put("intel_ratio", new JSONObject(
                "{\"infantry\":20,\"lancer\":30,\"marksman\":50}"));
        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals(200L, ro.formation.infantry);
        assertEquals(300L, ro.formation.lancer);
        assertEquals(500L, ro.formation.marksman);
        assertTrue(ro.reason.contains("比例 20:30:50"));

        JSONObject legacy = new JSONObject();
        legacy.put("intel_total", 900L);
        legacy.put("ratio", new JSONObject("{\"infantry\":1,\"lancer\":1,\"marksman\":1}"));
        FormationResolver.ResolveOutcome ro2 = IntelFormationPlan.resolve(context(legacy));
        assertTrue(ro2.reason, ro2.ready());
        assertEquals(300L, ro2.formation.infantry);
        assertEquals(300L, ro2.formation.lancer);
        assertEquals(300L, ro2.formation.marksman);
    }

    // ------------------------------------------------------------------
    // ROLE 指定编队优先
    // ------------------------------------------------------------------

    @Test
    public void resolve_roleFormationTakesPriorityOverIntelHeroes() {
        presetAll(true);
        JSONObject p = new JSONObject();
        p.put("formation_library", LIB_JSON);
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 1L);
        // 情报自定义英雄即使更“强”，ROLE 也必须优先（玩家显式选择优先）
        p.put("intel_hero_shield", SHIELD_2);
        p.put("intel_hero_lancer", LANCER_TOP);
        p.put("intel_ratio", new JSONObject(
                "{\"infantry\":1,\"lancer\":1,\"marksman\":1}"));

        FormationResolver.ResolveOutcome ro = IntelFormationPlan.resolve(context(p));
        assertTrue(ro.reason, ro.ready());
        assertEquals("ROLE 指定编队优先（不改既有语义）", ResolvedFormation.SRC_ROLE,
                ro.formation.source);
        assertEquals(1L, ro.formation.formationId);
        assertEquals("情报队", ro.formation.label);
        assertEquals(List.of(SHIELD_TOP, LANCER_BORGAN, MARK_GINA), ro.formation.heroIds);
        assertEquals(20000L, ro.formation.infantry);
        assertEquals(1549L, ro.formation.lancer);
        assertEquals(28451L, ro.formation.marksman);
    }

    // ------------------------------------------------------------------
    // SKIP 原因可读
    // ------------------------------------------------------------------

    @Test
    public void resolve_skipReasonsAreReadable_neverFallbackToGameSquadOne() {
        assertFalse("null 上下文 → SKIP", IntelFormationPlan.resolve(null).ready());
        TaskContext noEngine = new TaskContext(null, "111", 913L, state,
                new LinkedHashMap<>(), "test");
        FormationResolver.ResolveOutcome noEngineOutcome = IntelFormationPlan.resolve(noEngine);
        assertFalse(noEngineOutcome.ready());
        assertTrue(noEngineOutcome.reason.contains("engine"));

        // 无英雄目录（只有兵种库存）→ SKIP 且原因可读，不静默回退游戏第 1 队
        presetSoldiers();
        FormationCatalog.capture(engine);
        FormationResolver.ResolveOutcome noHero = IntelFormationPlan.resolve(context(new JSONObject()));
        assertFalse(noHero.ready());
        assertTrue("原因可读：" + noHero.reason, noHero.reason.contains("情报编队"));
        assertTrue(noHero.reason.contains("英雄"));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private TaskContext context(JSONObject intel) {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        if (intel != null) params.put("intel", intel);
        return new TaskContext(engine, "111", 913L, state, params, "test");
    }

    private void presetAll(boolean withBorganAndGina) {
        presetHeroes(withBorganAndGina);
        presetSoldiers();
        FormationCatalog.capture(engine);
    }

    private void presetHeroes(boolean withBorganAndGina) {
        Map<String, Object> hi = new LinkedHashMap<>();
        putHero(hi, SHIELD_TOP, 80, 9000);
        putHero(hi, SHIELD_2, 70, 5000);
        putHero(hi, LANCER_TOP, 80, 9000);
        putHero(hi, LANCER_2, 70, 6000);
        putHero(hi, MARK_TOP, 80, 9000);
        putHero(hi, MARK_2, 70, 6000);
        if (withBorganAndGina) {
            putHero(hi, LANCER_BORGAN, 60, 3000);   // 波根：比 LANCER_TOP/LANCER_2 弱
            putHero(hi, MARK_GINA, 60, 3000);       // 吉娜：比 MARK_TOP/MARK_2 弱
        }
        engine.notifies.put("notify_hero_init", hi);
    }

    private static void putHero(Map<String, Object> hi, long id, long lv, long power) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("lv", lv);
        h.put("star", 8L);
        h.put("power", power);
        hi.put(id + ".0", h);
    }

    private void presetSoldiers() {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", Map.of("id", 11000L, "count", 20000L));
        soldier.put("21000.0", Map.of("id", 21000L, "count", 1549L));
        soldier.put("31000.0", Map.of("id", 31000L, "count", 28451L));
        Map<String, Object> si = new LinkedHashMap<>();
        si.put("soldier", soldier);
        engine.notifies.put("notify_soldier_init", si);
    }

    private static boolean heroAvailable(com.carlos.vault.wjdr.WjdrEngine engine, long heroId) {
        for (FormationCatalog.Hero h : FormationCatalog.heroes(engine)) {
            if (h.id == heroId) return h.available;
        }
        return false;
    }

    /** 资产库 READY 夹具（与 FormationCapacityBusyTest 同款；在途行军英雄 → busyHeroIds）。 */
    private static void readyAssetLibrary(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", TestEngines.FakeEngine.mapOf(
                "project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", TestEngines.FakeEngine.mapOf(
                "march_info", Arrays.asList(),
                "world_extra_march", TestEngines.FakeEngine.mapOf("value", 0L)));
        lib.accept("notify_resource_init", TestEngines.FakeEngine.mapOf("data", Arrays.asList()));
        lib.accept("notify_recover_resource_init", TestEngines.FakeEngine.mapOf(
                "data", new LinkedHashMap<>()));
        lib.accept("notify_all_research", TestEngines.FakeEngine.mapOf(
                "researched", new LinkedHashMap<>()));
        lib.accept("notify_vip_info", TestEngines.FakeEngine.mapOf(
                "vip_lv", 6L, "vip_active_endtime", 9_999_999_999L));
        lib.accept("notify_soldier_init", TestEngines.FakeEngine.mapOf("data", Arrays.asList()));
        lib.accept("notify_hero_init", TestEngines.FakeEngine.mapOf(
                "data", new LinkedHashMap<>()));
        lib.accept("notify_shelter_info", TestEngines.FakeEngine.mapOf(
                "building_info", new LinkedHashMap<>()));
        lib.accept("notify_lords_equipment_info", TestEngines.FakeEngine.mapOf(
                "data", Arrays.asList()));
        lib.accept("notify_backpack_init", TestEngines.FakeEngine.mapOf("data", Arrays.asList()));
        lib.accept("notify_intelligence_info", TestEngines.FakeEngine.mapOf(
                "events", Arrays.asList()));
        lib.accept("notify_buff_show", TestEngines.FakeEngine.mapOf("map", Arrays.asList()));
        assertNotNull(lib);
        assertTrue(lib.ready());
    }
}
