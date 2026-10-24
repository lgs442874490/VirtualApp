package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 统一编队解析单测（批次10）：模式/参数解析、比例分配、ROLE/AUTOMATIC/回退三态、
 * 英雄/库存校验、SKIP 语义、payload 形状。不连真协议（引擎替身只回放 notify）。
 *
 * <p>参考网页版：{@code DailyFormationMode}（AUTOMATIC|ROLE；NORMAL/CUSTOM→ROLE）、
 * {@code DailyFormationConfigSupport}（模式回落）、{@code DailyMarchFormationResolver}（不可用返回 SKIP）。
 */
public class FormationResolverTest {

    private static final String LIB_JSON = "{\"formations\":["
            + "{\"id\":1,\"name\":\"采集队\",\"hero_ids\":[50041,50039],"
            + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451},"
            + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":50000}]}";

    /** 最小引擎替身：只回放 notifyData（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();

        TestEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    return m;
                }
            }
            return c;
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TestEngine engine;
    private StateStore state;

    @Before
    public void setUp() {
        engine = new TestEngine();
        state = new StateStore(tmp.getRoot().toPath());
        FormationCatalog.reset(engine);
    }

    // ---------------- 夹具 ----------------

    private void presetHeroes() {
        Map<String, Object> hi = new LinkedHashMap<>();
        hi.put("50041.0", hero(50041L, 80, 8, 9000));
        hi.put("50039.0", hero(50039L, 75, 7, 5000));
        hi.put("50022.0", hero(50022L, 70, 6, 1000));
        engine.notifies.put("notify_hero_init", hi);
    }

    private static Map<String, Object> hero(long id, long lv, long star, long power) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("lv", lv);
        h.put("star", star);
        h.put("power", power);
        return h;
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

    private void presetAll() {
        presetHeroes();
        presetSoldiers();
        FormationCatalog.capture(engine);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------- 参数解析 ----------------

    @Test
    public void isConfigured_onlyNewFormationKeysTriggerNewPath() {
        assertFalse(FormationResolver.isConfigured((Map<String, Object>) null));
        assertFalse(FormationResolver.isConfigured(new LinkedHashMap<>()));
        assertFalse("旧参数 hero_ids 不算新编队系统（保持旧行为）",
                FormationResolver.isConfigured(params("hero_ids", "1,2,3")));
        assertFalse("旧参数 squad_index 不算新编队系统",
                FormationResolver.isConfigured(params("squad_index", "2")));
        assertFalse("空字符串忽略", FormationResolver.isConfigured(params("formation_mode", "")));
        assertTrue(FormationResolver.isConfigured(params("formation_mode", "ROLE")));
        assertTrue(FormationResolver.isConfigured(params("formation_id", "1")));
        assertTrue(FormationResolver.isConfigured(params("soldiers", Map.of("infantry", 1))));
        assertTrue(FormationResolver.isConfigured(params("ratio", Map.of("infantry", 40))));
        assertTrue(FormationResolver.isConfigured(new JSONObject("{\"formationMode\":\"AUTOMATIC\"}")));
    }

    @Test
    public void modeAndParamParsing_webParity() {
        assertEquals(FormationResolver.MODE_ROLE, FormationResolver.modeOf(params("formation_mode", "NORMAL")));
        assertEquals("CUSTOM→ROLE（网页版 DailyFormationMode.from）",
                FormationResolver.MODE_ROLE, FormationResolver.modeOf(params("formation_mode", "CUSTOM")));
        assertEquals(FormationResolver.MODE_ROLE, FormationResolver.modeOf(params("formation_mode", "role")));
        assertEquals(FormationResolver.MODE_AUTOMATIC,
                FormationResolver.modeOf(params("formation_mode", "AUTOMATIC")));
        assertEquals("有 formation_id 且未写 mode → ROLE",
                FormationResolver.MODE_ROLE, FormationResolver.modeOf(params("formation_id", "3")));
        assertEquals("无参数 → AUTOMATIC（但 isConfigured=false 时不会走新路径）",
                FormationResolver.MODE_AUTOMATIC, FormationResolver.modeOf(new LinkedHashMap<>()));
        assertEquals("未知值按 formation_id 回退", FormationResolver.MODE_ROLE,
                FormationResolver.modeOf(params("formation_mode", "BOGUS", "formation_id", "2")));

        assertEquals(2L, FormationResolver.formationIdOf(params("formation_id", "2")));
        assertEquals(2L, FormationResolver.formationIdOf(params("formationId", 2)));
        assertEquals(5000L, FormationResolver.totalOf(params("total", "5000")));

        long[] sol = FormationResolver.soldiersOf(params("soldiers",
                Map.of("infantry", 100, "lancer", 50, "marksman", 25)));
        assertNotNull(sol);
        assertEquals(100L, sol[0]);
        assertEquals(50L, sol[1]);
        assertEquals(25L, sol[2]);
        long[] alias = FormationResolver.soldiersOf(params("soldiers",
                Map.of("shield", 7, "spear", 8, "bow", 9)));
        assertEquals(7L, alias[0]);
        assertEquals(8L, alias[1]);
        assertEquals(9L, alias[2]);
        assertNull(FormationResolver.soldiersOf(new LinkedHashMap<>()));

        Map<String, Long> ratio = FormationResolver.ratioOf(params("ratio",
                Map.of("infantry", 10, "lancer", 20, "marksman", 70)), null);
        assertEquals(10L, (long) ratio.get("infantry"));
        long[] arr = FormationResolver.ratioArray(ratio, FormationResolver.DEFAULT_RATIO);
        assertEquals(10L, arr[0]);
        assertEquals(20L, arr[1]);
        assertEquals(70L, arr[2]);

        assertEquals(2, FormationResolver.squadIndexOf(params("squad_index", "2"), 1));
        assertEquals("非法 squad 回落默认", 1, FormationResolver.squadIndexOf(params("squad_index", "9"), 1));
    }

    // ---------------- 比例分配（纯函数） ----------------

    @Test
    public void allocate_ratioAndStockCaps() {
        long[] stock = {100000, 100000, 100000};
        long[] a = FormationResolver.allocate(1000, new long[]{40, 30, 30}, stock);
        assertEquals(400L, a[0]);
        assertEquals(300L, a[1]);
        assertEquals(300L, a[2]);

        // 盾上限 100：其余两线补足，总量仍=1000
        long[] capped = FormationResolver.allocate(1000, new long[]{40, 30, 30},
                new long[]{100, 1000, 1000});
        assertEquals("不得超过库存", 100L, capped[0]);
        assertEquals("总量守恒", 1000L, capped[0] + capped[1] + capped[2]);
        assertTrue(capped[1] <= 1000 && capped[2] <= 1000);

        // 库存合计 < total：夹到库存合计
        long[] small = FormationResolver.allocate(500, new long[]{40, 30, 30},
                new long[]{100, 100, 100});
        assertEquals(300L, small[0] + small[1] + small[2]);

        // 权重全 0 → 默认 40:30:30
        long[] def = FormationResolver.allocate(1000, new long[]{0, 0, 0}, stock);
        assertEquals(400L, def[0]);
        assertEquals(300L, def[1]);
        assertEquals(300L, def[2]);

        // 边界
        assertEquals(0L, FormationResolver.allocate(0, new long[]{1, 1, 1}, stock)[0]);
        assertEquals(0L, FormationResolver.allocate(1000, new long[]{1, 1, 1},
                new long[]{0, 0, 0})[0]);
        assertEquals(0L, FormationResolver.allocate(1000, new long[]{1, 1, 1}, null)[0]);
    }

    // ---------------- 三态：FALLBACK / AUTO / ROLE ----------------

    @Test
    public void fallback_noParamsKeepsLegacyBehavior() {
        // 无参数 = 旧行为：source=FALLBACK；无英雄数据时 payload 为 null（与旧 Exec.formation 一致）
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(
                engine, FormationLibrary.empty(), new LinkedHashMap<>());
        assertTrue(ro.ready());
        assertEquals(ResolvedFormation.SRC_FALLBACK, ro.formation.source);
        assertNull("无英雄 → 旧行为同样无编队", ro.formation.toFormationPayload());

        // 有英雄/兵种数据时：FALLBACK payload 必须与旧 Exec.formation(engine, squad) 完全一致
        presetSoldiers();
        presetHeroes();
        Map<String, Object> p = params("squad_index", "2");
        FormationResolver.ResolveOutcome legacy = FormationResolver.resolveOutcome(
                engine, FormationLibrary.empty(), p);
        assertTrue(legacy.ready());
        assertEquals(ResolvedFormation.SRC_FALLBACK, legacy.formation.source);
        Map<String, Object> expected = Exec.formation(engine, 2);
        assertEquals(expected, legacy.formation.toFormationPayload());
    }

    @Test
    public void auto_selectsStrongestHeroesAndAllocatesByRatio() {
        presetAll();
        ResolvedFormation f = FormationResolver.auto(engine, FormationSpec.defaultRatio(), 1000);
        assertNotNull(f);
        assertEquals(ResolvedFormation.SRC_AUTO, f.source);
        assertEquals("英雄按战力降序", List.of(50041L, 50039L, 50022L), f.heroIds);
        assertEquals(400L, f.infantry);
        assertEquals(300L, f.lancer);
        assertEquals(300L, f.marksman);

        Map<String, Object> payload = f.toFormationPayload();
        assertNotNull(payload);
        assertTrue(payload.containsKey("hero_id"));
        assertTrue(payload.containsKey("soldier"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heroes = (List<Map<String, Object>>) payload.get("hero_id");
        assertEquals(3, heroes.size());
        assertEquals("k=上阵位次(1)", 1L, ((Number) heroes.get(0).get("k")).longValue());
        assertEquals("v=英雄id", 50041L, ((Number) heroes.get(0).get("v")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> soldiers = (List<Map<String, Object>>) payload.get("soldier");
        assertEquals("三兵种各一条（最高档 id）", 3, soldiers.size());
        assertEquals(11000L, ((Number) soldiers.get(0).get("k")).longValue());
    }

    @Test
    public void auto_missingHeroOrStock_returnsNullWithReason() {
        // 无英雄
        FormationResolver.ResolveOutcome noHero = FormationResolver.autoOutcome(
                engine, FormationSpec.defaultRatio(), 1000);
        assertFalse(noHero.ready());
        assertTrue(noHero.reason.contains("英雄"));
        // 有英雄无库存
        presetHeroes();
        FormationCatalog.capture(engine);
        FormationResolver.ResolveOutcome noStock = FormationResolver.autoOutcome(
                engine, FormationSpec.defaultRatio(), 1000);
        assertFalse(noStock.ready());
        assertTrue(noStock.reason.contains("库存"));
        assertNull(FormationResolver.auto(engine, FormationSpec.defaultRatio(), 1000));
    }

    @Test
    public void role_resolvesSavedFormationToPayload() {
        presetAll();
        FormationLibrary lib = FormationLibrary.parse(LIB_JSON);
        Map<String, Object> p = params("formation_mode", "ROLE", "formation_id", "1");
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib, p);
        assertTrue(ro.reason, ro.ready());
        ResolvedFormation f = ro.formation;
        assertEquals(ResolvedFormation.SRC_ROLE, f.source);
        assertEquals(1L, f.formationId);
        assertEquals("采集队", f.label);
        assertEquals(List.of(50041L, 50039L), f.heroIds);
        assertEquals(20000L, f.infantry);
        assertEquals(1549L, f.lancer);
        assertEquals(28451L, f.marksman);
        Map<String, Object> payload = f.toFormationPayload();
        assertNotNull(payload);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heroes = (List<Map<String, Object>>) payload.get("hero_id");
        assertEquals(2, heroes.size());
        assertTrue("日志文案含来源/英雄/兵种", f.describe().contains("来源=ROLE#1"));
    }

    @Test
    public void role_skipSemantics_neverSilentlyFallback() {
        presetAll();
        FormationLibrary lib = FormationLibrary.parse(LIB_JSON);

        assertFalse("库缺失 → SKIP",
                FormationResolver.resolveOutcome(engine, FormationLibrary.empty(),
                        params("formation_mode", "ROLE", "formation_id", "1")).ready());
        assertFalse("编队 id 不存在 → SKIP",
                FormationResolver.resolveOutcome(engine, lib,
                        params("formation_mode", "ROLE", "formation_id", "99")).ready());
        assertFalse("ROLE 未给 id → SKIP",
                FormationResolver.resolveOutcome(engine, lib,
                        params("formation_mode", "ROLE")).ready());

        // 英雄不在角色英雄列表（编队库里有 99999）
        FormationLibrary badHero = FormationLibrary.parse("{\"formations\":[{\"id\":9,"
                + "\"name\":\"坏队\",\"hero_ids\":[99999],\"soldiers\":{\"infantry\":100}}]}");
        FormationResolver.ResolveOutcome heroSkip = FormationResolver.resolveOutcome(engine, badHero,
                params("formation_mode", "ROLE", "formation_id", "9"));
        assertFalse(heroSkip.ready());
        assertTrue(heroSkip.reason.contains("英雄"));

        // 显式兵量超出库存 → SKIP（不静默削减）
        Map<String, Object> over = params("formation_mode", "ROLE", "formation_id", "1",
                "soldiers", Map.of("infantry", 999999));
        FormationResolver.ResolveOutcome overSkip = FormationResolver.resolveOutcome(engine, lib, over);
        assertFalse(overSkip.ready());
        assertTrue(overSkip.reason.contains("超出库存"));
    }

    @Test
    public void role_totalZero_allocatesByRatioWithinStock() {
        presetAll();
        // total=0 且编队无 soldiers → 按比例/库存自动（网页版 total=0=按容量）
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":5,"
                + "\"hero_ids\":[50041],\"ratio\":{\"infantry\":1,\"lancer\":1,\"marksman\":1},"
                + "\"total\":0}]}");
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "5", "total", "900"));
        assertTrue(ro.reason, ro.ready());
        assertEquals(900L, ro.formation.totalSoldiers());
        assertEquals("1:1:1 比例", 300L, ro.formation.infantry);
        assertEquals(300L, ro.formation.lancer);
        assertEquals(300L, ro.formation.marksman);
    }

    @Test
    public void configuredButInvalid_returnsNullNotFallback() {
        presetAll();
        assertNull("已配置新编队但库缺失：返回 null（调用方 SKIP），不是旧行为回退",
                FormationResolver.resolve(engine, FormationLibrary.empty(),
                        params("formation_mode", "ROLE", "formation_id", "1")));
    }

    // ---------------- 库来源（TaskContext） ----------------

    @Test
    public void libraryFor_readsTaskParamsThenGlobalFormationScope() {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        JSONObject gather = new JSONObject();
        gather.put("formation_library", LIB_JSON);
        params.put("gather", gather);
        TaskContext tc = new TaskContext(engine, "111", 913L, state, params, "test");
        FormationLibrary lib = FormationResolver.libraryFor(tc, "gather");
        assertEquals(1, lib.size());

        // 任务自身没有 → 回落全局 formation 参数作用域
        Map<String, JSONObject> onlyGlobal = new LinkedHashMap<>();
        JSONObject formation = new JSONObject();
        formation.put("formation_library", LIB_JSON);
        onlyGlobal.put("formation", formation);
        TaskContext tc2 = new TaskContext(engine, "111", 913L, state, onlyGlobal, "test");
        assertEquals(1, FormationResolver.libraryFor(tc2, "hunt").size());

        // 都没有 → 空库
        TaskContext empty = new TaskContext(engine, "111", 913L, state, new LinkedHashMap<>(), "test");
        assertTrue(FormationResolver.libraryFor(empty, "intel").isEmpty());
    }

    @Test
    public void strongestHeroes_cappedAtFive() {
        Map<String, Object> hi = new LinkedHashMap<>();
        for (long id = 1; id <= 8; id++) hi.put(id + ".0", hero(60000L + id, 60 + id, 5, id * 100));
        engine.notifies.put("notify_hero_init", hi);
        FormationCatalog.capture(engine);
        List<Long> picks = FormationResolver.strongestHeroes(engine, 5);
        assertEquals(5, picks.size());
        assertEquals("战力最高者第一", 60008L, (long) picks.get(0));
        List<Long> one = FormationResolver.strongestHeroes(engine, 1);
        assertEquals(1, one.size());
    }

    // ---------------- 批次11：ROLE 必须使用编队库兵量（task-37） ----------------

    private static FormationLibrary libOne(long id, String name, String heroIdsJson,
                                           String soldiersJson, String ratioJson, long total) {
        StringBuilder sb = new StringBuilder("{\"formations\":[{\"id\":").append(id)
                .append(",\"name\":\"").append(name).append("\",\"hero_ids\":").append(heroIdsJson);
        if (soldiersJson != null) sb.append(",\"soldiers\":").append(soldiersJson);
        if (ratioJson != null) sb.append(",\"ratio\":").append(ratioJson);
        sb.append(",\"total\":").append(total).append("}]}");
        return FormationLibrary.parse(sb.toString());
    }

    @Test
    public void role_usesLibrarySoldiers_whenNoFunctionOverride() {
        presetAll();
        // 库兵量 1000/500/700（≠库存/自动分配结果）；修复前会落回 ratio+库存自动分配（20000/1549/28451）
        FormationLibrary lib = libOne(7, "库兵队", "[50041]",
                "{\"infantry\":1000,\"lancer\":500,\"marksman\":700}", null, 50000);
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "7"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("库兵量必须生效（不被自动分配覆盖）", 1000L, ro.formation.infantry);
        assertEquals(500L, ro.formation.lancer);
        assertEquals(700L, ro.formation.marksman);
        assertEquals(2200L, ro.formation.totalSoldiers());
        assertTrue("reason 标注来源", ro.reason.contains("编队库兵量"));

        // payload 必须反映最终兵量（k=最高档兵种 id，v=库兵量）
        Map<String, Object> payload = ro.formation.toFormationPayload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> soldiers = (List<Map<String, Object>>) payload.get("soldier");
        assertEquals(3, soldiers.size());
        assertEquals(11000L, ((Number) soldiers.get(0).get("k")).longValue());
        assertEquals(1000L, ((Number) soldiers.get(0).get("v")).longValue());
        assertEquals(21000L, ((Number) soldiers.get(1).get("k")).longValue());
        assertEquals(500L, ((Number) soldiers.get(1).get("v")).longValue());
        assertEquals(700L, ((Number) soldiers.get(2).get("v")).longValue());
    }

    @Test
    public void role_functionSoldiersOverridePerLine() {
        presetAll();
        FormationLibrary lib = libOne(8, "库兵队", "[50041]",
                "{\"infantry\":1000,\"lancer\":500,\"marksman\":700}", null, 0);
        // 功能级只覆盖 infantry/marksman（>0 生效），lancer 沿用库兵量
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "8",
                        "soldiers", Map.of("infantry", 3000, "marksman", 900)));
        assertTrue(ro.reason, ro.ready());
        assertEquals(3000L, ro.formation.infantry);
        assertEquals("未覆盖的兵种沿用编队库", 500L, ro.formation.lancer);
        assertEquals(900L, ro.formation.marksman);
        assertTrue(ro.reason.contains("功能级 soldiers 覆盖"));

        // 全量覆盖
        FormationResolver.ResolveOutcome full = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "8",
                        "soldiers", Map.of("infantry", 2000, "lancer", 1500, "marksman", 1000)));
        assertTrue(full.ready());
        assertEquals(2000L, full.formation.infantry);
        assertEquals(1500L, full.formation.lancer);
        assertEquals(1000L, full.formation.marksman);
    }

    @Test
    public void role_librarySoldiersOverStock_skipsWithReason() {
        presetAll();
        FormationLibrary lib = libOne(9, "超量队", "[50041]",
                "{\"infantry\":50000,\"lancer\":50000,\"marksman\":50000}", null, 0);
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "9"));
        assertFalse("库兵量超库存 → SKIP（不静默削减）", ro.ready());
        assertTrue(ro.reason.contains("超出库存"));
        assertTrue("原因含具体缺口：" + ro.reason, ro.reason.contains("50000"));
    }

    @Test
    public void role_noSoldiers_totalAllocatesByRatio() {
        presetAll();
        FormationLibrary lib = libOne(10, "比例队", "[50041]", null,
                "{\"infantry\":1,\"lancer\":1,\"marksman\":1}", 900);
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "10"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("spec.total>0 → 按 total/ratio 分配（保留 AUTOMATIC 语义）", 900L,
                ro.formation.totalSoldiers());
        assertEquals(300L, ro.formation.infantry);
        assertEquals(300L, ro.formation.lancer);
        assertEquals(300L, ro.formation.marksman);
        assertTrue(ro.reason.contains("按比例分配"));
    }

    @Test
    public void role_noSoldiersNoTotal_skipsWithReadableReason() {
        presetAll();
        FormationLibrary lib = libOne(11, "空兵队", "[50041]", null, null, 0);
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "11"));
        assertFalse("库未配兵量且无 total/ratio → SKIP（不静默自动分配）", ro.ready());
        assertTrue(ro.reason.contains("未配置兵量"));
        assertTrue(ro.reason.contains("total"));
    }

    @Test
    public void role_explicitParamsRatio_allocatesWithoutTotal() {
        presetAll();
        FormationLibrary lib = libOne(12, "比例队", "[50041]", null, null, 0);
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "12",
                        "ratio", Map.of("infantry", 100, "lancer", 0, "marksman", 0)));
        assertTrue("显式 ratio 也算明确要求按比例分配：" + ro.reason, ro.ready());
        assertTrue(ro.formation.totalSoldiers() > 0);
        assertEquals("容量上限内盾优先", 20000L, ro.formation.infantry);
    }
}
