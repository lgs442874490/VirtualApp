package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T3（v2 契约 §3.4）Resolver 单测：ROLE 英雄合法性（≤3/重复/职业唯一/目录/黑名单）、
 * formation_ids 候选回退、容量同比缩放（旧版 fitFormation 口径）、库存缩放、capacity=0 只 WARN、
 * PERCENTAGE 按容量分配、AUTO 的 415/406 自适应与 usedHeroes 预留、GAME 只读、队长/元数据。
 *
 * <p>不连真协议：引擎替身只回放 notify 帧；容量用 {@code MarchCapacityCalculator} 公式在
 * 英雄 lev=1 时精确可得 565（500 固定 + 65 英雄容量）。
 */
public class FormationV2ResolverTest {

    private static final long H_SHIELD = 50041L;   // 盾(1)
    private static final long H_SHIELD2 = 50038L;  // 盾(1)
    private static final long H_LANCER = 50039L;   // 矛(2)
    private static final long H_ARCHER = 50022L;   // 弓(3)
    private static final long H_UNKNOWN = 50999L;  // 职业未知(0)

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

    private TestEngine engine;

    @After
    public void cleanup() {
        if (engine != null) {
            HeroAvailability.clear(engine);
            FormationCatalog.reset(engine);
        }
    }

    // ---------------- fixtures ----------------

    private void newEngine() {
        engine = new TestEngine();
    }

    private void presetHeroesNoLevel(long... ids) {
        Map<String, Object> hi = new LinkedHashMap<>();
        for (long id : ids) hi.put(id + ".0", hero(id, 0L));
        engine.notifies.put("notify_hero_init", hi);
    }

    private void presetHeroesLevel1(long... ids) {
        Map<String, Object> hi = new LinkedHashMap<>();
        for (long id : ids) hi.put(id + ".0", hero(id, 1L));
        engine.notifies.put("notify_hero_init", hi);
    }

    private static Map<String, Object> hero(long id, long lev) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        if (lev > 0L) h.put("lev", lev);
        h.put("star", 0L);
        h.put("power", 0L);
        return h;
    }

    private void presetSoldiers(long inf, long lanc, long mark) {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", Map.of("id", 11000L, "count", inf));
        soldier.put("21000.0", Map.of("id", 21000L, "count", lanc));
        soldier.put("31000.0", Map.of("id", 31000L, "count", mark));
        Map<String, Object> si = new LinkedHashMap<>();
        si.put("soldier", soldier);
        engine.notifies.put("notify_soldier_init", si);
    }

    private void presetSoldiers() {
        presetSoldiers(20000L, 1549L, 28451L);
    }

    /** 指挥中心 lv1 → 固定容量 500+400=900（让 T2 MarchCapacity 判定 initialized=true）。 */
    private void presetShelter() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("lv", 1L);
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("base", base);
        Map<String, Object> building = new LinkedHashMap<>();
        building.put("331", cc);
        Map<String, Object> shelter = new LinkedHashMap<>();
        shelter.put("building_info", building);
        engine.notifies.put("notify_shelter_info", shelter);
    }

    private void presetBuffBp(long bp) {
        Map<String, Object> buff = new LinkedHashMap<>();
        buff.put("buff_map", Map.of("10508", bp));
        engine.notifies.put("notify_buff_show", Map.of("buffs", List.of(buff)));
    }

    private void capture() {
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
    }

    private void presetNoLevelAll() {
        newEngine();
        presetHeroesNoLevel(H_SHIELD, H_LANCER, H_ARCHER, H_SHIELD2);
        presetSoldiers();
        capture();
    }

    private void presetLevel1() {
        newEngine();
        presetHeroesLevel1(H_SHIELD, H_LANCER, H_ARCHER);
        presetSoldiers();
        presetShelter();   // capacity = (900+65) = 965（T2 MarchCapacity initialized）
        capture();
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static FormationLibrary lib(String json) {
        return FormationLibrary.parse(json);
    }

    private static FormationLibrary libOne(long id, String name, String heroesJson,
                                           String soldiersJson) {
        StringBuilder sb = new StringBuilder("{\"formations\":[{\"id\":").append(id)
                .append(",\"name\":\"").append(name).append("\",\"hero_ids\":")
                .append(heroesJson);
        if (soldiersJson != null) sb.append(",\"soldiers\":").append(soldiersJson);
        sb.append("}]}");
        return FormationLibrary.parse(sb.toString());
    }

    private FormationResolver.ResolveOutcome resolveRole(FormationLibrary lib,
                                                         Map<String, Object> p) {
        return FormationResolver.resolveOutcome(engine, lib, p);
    }

    // ==================================================================
    // ① ROLE 英雄合法性校验（P0-1：不再把非法编队下发 → 415）
    // ==================================================================

    @Test
    public void role_rejectsTooManyHeroes_neverDispatches() {
        presetNoLevelAll();
        FormationLibrary lib = libOne(1, "超员队",
                "[" + H_SHIELD + "," + H_LANCER + "," + H_ARCHER + "," + H_SHIELD2 + "]",
                "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertFalse("英雄数>3 必须 SKIP（生产 415 根因）", ro.ready());
        assertTrue(ro.reason, ro.reason.contains("超过上限"));
        assertTrue(ro.reason.contains("3"));
        assertNull("非法编队不得产出 payload", FormationResolver.resolve(engine, lib,
                params("formation_mode", "ROLE", "formation_id", "1")));
    }

    @Test
    public void role_rejectsDuplicateHeroIds() {
        presetNoLevelAll();
        FormationLibrary lib = libOne(1, "重复队",
                "[" + H_SHIELD + "," + H_SHIELD + "]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertFalse(ro.ready());
        assertTrue(ro.reason, ro.reason.contains("英雄重复"));
    }

    @Test
    public void role_rejectsDuplicateProfession() {
        presetNoLevelAll();
        // 两个盾兵英雄（50041/50038 都是职业 1）
        FormationLibrary lib = libOne(1, "双盾队",
                "[" + H_SHIELD + "," + H_SHIELD2 + "]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertFalse(ro.ready());
        assertTrue(ro.reason, ro.reason.contains("职业重复"));
    }

    @Test
    public void role_rejectsHeroNotInCatalog() {
        presetNoLevelAll();
        FormationLibrary lib = libOne(1, "幽灵队", "[99999]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertFalse(ro.ready());
        assertTrue("目录缺失必须 SKIP：" + ro.reason,
                ro.reason.contains("英雄不在角色英雄库"));
    }

    @Test
    public void role_unknownProfessionSkipped_notFalsePositive() {
        newEngine();
        presetHeroesNoLevel(H_SHIELD, H_UNKNOWN);
        presetSoldiers();
        capture();
        FormationLibrary lib = libOne(1, "未知职业队",
                "[" + H_SHIELD + "," + H_UNKNOWN + "]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue("职业未知=0 时跳过职业校验（不误杀）：" + ro.reason, ro.ready());
        assertEquals(2, ro.formation.heroIds.size());
    }

    @Test
    public void role_blacklist_hitsHeroAway_triesNextCandidate() {
        presetNoLevelAll();
        HeroAvailability.block(engine, List.of(H_SHIELD), 60_000L);
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":1,\"name\":\"被拉黑\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"soldiers\":{\"infantry\":100}},"
                + "{\"id\":2,\"name\":\"可用\",\"hero_ids\":[" + H_LANCER + "],"
                + "\"soldiers\":{\"infantry\":100}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_ids", List.of(1L, 2L)));
        assertTrue(ro.reason, ro.ready());
        assertEquals("候选 #1 黑名单 → 回退候选 #2", 2L, ro.formation.formationId);
    }

    @Test
    public void role_blacklist_defaultSkipWithReadableReason() {
        presetNoLevelAll();
        HeroAvailability.block(engine, List.of(H_SHIELD), 60_000L);
        FormationLibrary lib = libOne(1, "外出队", "[" + H_SHIELD + "]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertFalse("黑名单命中 + 默认 SKIP", ro.ready());
        assertTrue(ro.reason, ro.reason.contains("HERO_AWAY"));
        assertTrue(ro.reason, ro.reason.contains("不在城"));
    }

    @Test
    public void role_blacklist_withFormationFallbackAuto() {
        presetNoLevelAll();
        HeroAvailability.block(engine, List.of(H_SHIELD), 60_000L);
        FormationLibrary lib = libOne(1, "外出队", "[" + H_SHIELD + "]", "{\"infantry\":100}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib, params(
                "formation_mode", "ROLE", "formation_id", "1", "formation_fallback", "AUTO"));
        assertTrue(ro.reason, ro.ready());
        assertEquals(ResolvedFormation.SRC_AUTO, ro.formation.source);
        assertTrue("回退原因可读：" + ro.reason, ro.reason.contains("HERO_AWAY"));
        assertTrue(ro.reason.contains("回退自动编队"));
    }

    @Test
    public void role_gameFormationReadOnly() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":-5,"
                + "\"name\":\"游戏队\",\"source\":\"GAME\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"soldiers\":{\"infantry\":100}}]}");
        assertNotNull("id<0 GAME 镜像可解析", lib.byId(-5L));
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "-5"));
        assertFalse("ROLE 绑定不允许选 GAME", ro.ready());
        assertTrue(ro.reason, ro.reason.contains("只读"));
        assertTrue("提示用副本：" + ro.reason, ro.reason.contains("副本"));
    }

    // ==================================================================
    // ② formation_ids 候选回退（P1-2）
    // ==================================================================

    @Test
    public void role_formationIdsCandidateFallback_inOrder() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":1,\"name\":\"坏队\",\"hero_ids\":[" + H_SHIELD + "," + H_LANCER
                + "," + H_ARCHER + "," + H_SHIELD2 + "],\"soldiers\":{\"infantry\":100}},"
                + "{\"id\":2,\"name\":\"好队\",\"hero_ids\":[" + H_LANCER + "],"
                + "\"soldiers\":{\"infantry\":120,\"lancer\":30}}]}");
        // 只给 formation_ids（无 formation_mode/formation_id）也应识别为 ROLE
        Map<String, Object> p = params("formation_ids", List.of(1L, 2L));
        assertTrue(FormationResolver.isConfigured(p));
        assertEquals(FormationResolver.MODE_ROLE, FormationResolver.modeOf(p));
        FormationResolver.ResolveOutcome ro = resolveRole(lib, p);
        assertTrue(ro.reason, ro.ready());
        assertEquals("按顺序回退到 #2", 2L, ro.formation.formationId);
        assertEquals(List.of(H_LANCER), ro.formation.heroIds);
        assertEquals(120L, ro.formation.infantry);
    }

    @Test
    public void role_allCandidatesInvalid_skipWithPerCandidateSummary() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":1,\"name\":\"坏队A\",\"hero_ids\":[" + H_SHIELD + "," + H_SHIELD
                + "],\"soldiers\":{\"infantry\":1}},"
                + "{\"id\":2,\"name\":\"坏队B\",\"hero_ids\":[99999],"
                + "\"soldiers\":{\"infantry\":1}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_ids", List.of(1L, 2L)));
        assertFalse(ro.ready());
        assertTrue(ro.reason, ro.reason.contains("候选均不可用"));
        assertTrue("候选 #1 原因：" + ro.reason, ro.reason.contains("#1"));
        assertTrue("候选 #2 原因：" + ro.reason, ro.reason.contains("#2"));
        assertTrue("原因裁剪 ≤200 字 + 省略号：" + ro.reason,
                ro.reason.length() <= 215);
    }

    // ==================================================================
    // ③ 容量/库存缩放（P0-3：不再 SKIP）
    // ==================================================================

    @Test
    public void role_explicitSoldiersOverCapacity_scaledByFitFormation() {
        presetLevel1();   // 固定 900（500 基础+指挥中心 lv1 400）+ 英雄 lv1 65 = 965
        assertEquals(965L, FormationResolver.capacityForHeroes(engine, List.of(H_SHIELD)));
        FormationLibrary lib = libOne(1, "超容量队", "[" + H_SHIELD + "]",
                "{\"infantry\":1000,\"lancer\":0,\"marksman\":0}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("按容量同比缩放（旧版 expandedFixedTarget）", 965L, ro.formation.infantry);
        assertEquals(965L, ro.formation.totalSoldiers());
        assertTrue("原因含容量缩放：" + ro.reason,
                ro.reason.contains("出征容量") && ro.reason.contains("缩放"));
    }

    @Test
    public void role_manualCountsOverCapacity_scaledProportionally() {
        presetLevel1();
        FormationLibrary lib = libOne(1, "比例缩放队", "[" + H_SHIELD + "]",
                "{\"infantry\":400,\"lancer\":300,\"marksman\":300}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        // 目标 965 按 400:300:300 最大余数 → 386/290/289
        assertEquals(386L, ro.formation.infantry);
        assertEquals(290L, ro.formation.lancer);
        assertEquals(289L, ro.formation.marksman);
        assertEquals(965L, ro.formation.totalSoldiers());
    }

    @Test
    public void role_capacityBuffBp_expandsConfiguredTotal() {
        presetLevel1();
        presetBuffBp(2000L);   // +20% 容量 → capacity = floor(965*12000/10000)=1158
        FormationLibrary lib = libOne(1, "容量Buff队", "[" + H_SHIELD + "]",
                "{\"infantry\":1000,\"lancer\":0,\"marksman\":0}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        // expandedFixedTarget(1000, 2000, 0, 1158)=min(1200,1158)=1158
        assertEquals(1158L, ro.formation.infantry);
    }

    @Test
    public void role_capacityUnknown_noClampOnlyWarn() {
        presetNoLevelAll();   // 无 lev → capacity=0（未知）
        assertEquals(0L, FormationResolver.capacityForHeroes(engine, List.of(H_SHIELD)));
        FormationLibrary lib = libOne(1, "容量未知队", "[" + H_SHIELD + "]",
                "{\"infantry\":1000,\"lancer\":500,\"marksman\":700}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("capacity<=0 不夹取（保持旧行为）", 1000L, ro.formation.infantry);
        assertEquals(500L, ro.formation.lancer);
        assertEquals(700L, ro.formation.marksman);
        assertTrue("只 WARN：" + ro.reason, ro.reason.contains("容量未知"));
    }

    @Test
    public void role_stockShortage_scalesToStock() {
        presetNoLevelAll();
        FormationLibrary lib = libOne(1, "超库存队", "[" + H_SHIELD + "]",
                "{\"infantry\":50000,\"lancer\":50000,\"marksman\":50000}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals(20000L, ro.formation.infantry);
        assertEquals(1549L, ro.formation.lancer);
        assertEquals(28451L, ro.formation.marksman);
        assertTrue("原因含库存缩放：" + ro.reason, ro.reason.contains("库存"));
    }

    @Test
    public void role_percentageMode_allocatesByCapacity() {
        presetLevel1();   // capacity 965
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":1,"
                + "\"name\":\"比例队\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"allocation_mode\":\"PERCENTAGE\","
                + "\"ratio\":{\"infantry\":50,\"lancer\":20,\"marksman\":30}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        // allocatePercentages(965, 50/20/30) → 483/193/289（余数并列盾优先）
        assertEquals(483L, ro.formation.infantry);
        assertEquals(193L, ro.formation.lancer);
        assertEquals(289L, ro.formation.marksman);
        assertEquals(965L, ro.formation.totalSoldiers());
        assertTrue(ro.reason, ro.reason.contains("按比例分配"));
    }

    @Test
    public void role_totalParamLegacyCompat_keepsOldSemantics() {
        presetNoLevelAll();   // capacity=0
        FormationLibrary lib = libOne(1, "旧total队", "[" + H_SHIELD + "]", null);
        // 旧 App 语义：total>0 + ratio 权重 → 总量按比例权重分配（不按容量百分比）
        FormationResolver.ResolveOutcome ro = resolveRole(lib, params(
                "formation_mode", "ROLE", "formation_id", "1", "total", "900",
                "ratio", Map.of("infantry", 1, "lancer", 1, "marksman", 1)));
        assertTrue(ro.reason, ro.ready());
        assertEquals(300L, ro.formation.infantry);
        assertEquals(300L, ro.formation.lancer);
        assertEquals(300L, ro.formation.marksman);
        assertTrue(ro.reason, ro.reason.contains("按比例分配"));
    }

    @Test
    public void role_406Scale_appliedToSoldiers() {
        // 非「按比例」编队保留 406 自适应（2026-11-07 仅按比例编队取消缩放）
        presetNoLevelAll();
        HeroAvailability.noteSoldierLimitRejected(engine);   // 75%
        FormationLibrary lib = libOne(1, "406队", "[" + H_SHIELD + "]",
                "{\"infantry\":1000,\"lancer\":0,\"marksman\":0}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("406 后按 75% 缩放：round_half_up(1000*75/100)=750",
                750L, ro.formation.infantry);
        assertTrue("原因含 406：" + ro.reason, ro.reason.contains("406"));
    }

    @Test
    public void role_percentageNo406Scaling() {
        // 2026-11-07：按比例编队以「容量×比例」为准，不因 406 向下缩放
        presetLevel1();   // capacity 965
        HeroAvailability.noteSoldierLimitRejected(engine);   // 旧逻辑会缩到 75%
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":1,"
                + "\"name\":\"比例队\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"allocation_mode\":\"PERCENTAGE\","
                + "\"ratio\":{\"infantry\":50,\"lancer\":20,\"marksman\":30}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals(483L, ro.formation.infantry);
        assertEquals(193L, ro.formation.lancer);
        assertEquals(289L, ro.formation.marksman);
        assertFalse("按比例编队不应出现 406 缩放：" + ro.reason, ro.reason.contains("406 缩放"));
    }

    @Test
    public void role_percentageWithStoredSoldiers_usesCapacity() {
        // 2026-11-07：任意 allocation_mode=PERCENTAGE 的编队都按容量×比例，忽略库内 soldiers
        presetLevel1();   // capacity 965
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":1,"
                + "\"name\":\"比例队\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"allocation_mode\":\"PERCENTAGE\","
                + "\"ratio\":{\"infantry\":50,\"lancer\":20,\"marksman\":30},"
                + "\"soldiers\":{\"infantry\":999999,\"lancer\":0,\"marksman\":0}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals(483L, ro.formation.infantry);
        assertEquals(193L, ro.formation.lancer);
        assertEquals(289L, ro.formation.marksman);
        assertEquals(965L, ro.formation.totalSoldiers());
    }

    // ==================================================================
    // ④ 队长 / 元数据（v2）
    // ==================================================================

    @Test
    public void role_captainFirstInPayload() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":1,"
                + "\"name\":\"队长队\",\"hero_ids\":[" + H_SHIELD + "," + H_LANCER + "],"
                + "\"captain_hero_id\":" + H_LANCER + ",\"soldiers\":{\"infantry\":100}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "1"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("队长排到上阵位第 1", List.of(H_LANCER, H_SHIELD), ro.formation.heroIds);
        assertEquals(Long.valueOf(H_LANCER), ro.formation.captainHeroId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heroes = (List<Map<String, Object>>)
                ro.formation.toFormationPayload().get("hero_id");
        assertEquals(1L, ((Number) heroes.get(0).get("k")).longValue());
        assertEquals(H_LANCER, ((Number) heroes.get(0).get("v")).longValue());
        assertTrue(ro.formation.describe().contains("队长=" + H_LANCER));
    }

    @Test
    public void role_v2MetaExposed_templateBuiltInAllocation() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":57,"
                + "\"name\":\"基础编队·巨兽\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"captain_hero_id\":" + H_SHIELD + ",\"allocation_mode\":\"MANUAL\","
                + "\"template_key\":\"BEHEMOTH\",\"built_in\":true,"
                + "\"soldiers\":{\"infantry\":1}}]}");
        FormationResolver.ResolveOutcome ro = resolveRole(lib,
                params("formation_mode", "ROLE", "formation_id", "57"));
        assertTrue(ro.reason, ro.ready());
        assertEquals("BEHEMOTH", ro.formation.templateKey);
        assertTrue(ro.formation.builtIn);
        assertEquals(FormationSpec.MODE_MANUAL, ro.formation.allocationMode);
        assertEquals(Long.valueOf(H_SHIELD), ro.formation.captainHeroId);
        assertEquals(1L, ro.formation.totalSoldiers());
    }

    // ==================================================================
    // ⑤ usedHeroes 同轮预留（P1-7）
    // ==================================================================

    @Test
    public void role_usedHeroesReservation_triesNextCandidateAndSkipsIfAllReserved() {
        presetNoLevelAll();
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":1,\"name\":\"队1\",\"hero_ids\":[" + H_SHIELD + "],"
                + "\"soldiers\":{\"infantry\":10}},"
                + "{\"id\":2,\"name\":\"队2\",\"hero_ids\":[" + H_LANCER + "],"
                + "\"soldiers\":{\"infantry\":10}}]}");
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_ids", List.of(1L, 2L)),
                Set.of(H_SHIELD));
        assertTrue(ro.reason, ro.ready());
        assertEquals("被前序队列占用的 #1 跳过 → #2", 2L, ro.formation.formationId);

        // 全部候选都被占用 → SKIP，原因带 HERO_RESERVED
        FormationResolver.ResolveOutcome skip = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "ROLE", "formation_ids", List.of(1L, 2L)),
                Set.of(H_SHIELD, H_LANCER));
        assertFalse(skip.ready());
        assertTrue(skip.reason, skip.reason.contains("HERO_RESERVED"));
    }

    @Test
    public void auto_usedHeroesReservation_excludesReserved() {
        presetNoLevelAll();
        List<Long> all = FormationResolver.autoProfessionHeroes(engine);
        assertEquals(3, all.size());
        List<Long> reservedPicks = FormationResolver.autoProfessionHeroes(engine,
                Set.of(H_SHIELD, H_LANCER));
        // 盾位被占用 → 换同职业 50038；矛位无替补 → 缺；弓位 50022
        assertEquals(List.of(H_SHIELD2, H_ARCHER), reservedPicks);
        assertFalse(reservedPicks.contains(H_SHIELD));
        assertFalse(reservedPicks.contains(H_LANCER));
    }

    // ==================================================================
    // ⑥ AUTO：415/406 自适应（P0-5，原先 dead code）+ 职业 0 排除 + automaticSettings
    // ==================================================================

    @Test
    public void auto415_decrementsThenSuccessRestores() {
        presetNoLevelAll();
        assertEquals("默认 3 英雄", 3, FormationResolver.autoProfessionHeroes(engine).size());
        assertEquals(2, HeroAvailability.noteHeroCountRejected(engine, 3));
        assertEquals("415 后下一轮实际少上 1 人", 2,
                FormationResolver.autoProfessionHeroes(engine).size());
        assertEquals(1, HeroAvailability.noteHeroCountRejected(engine, 2));
        assertEquals(1, FormationResolver.autoProfessionHeroes(engine).size());
        // 成功出征 → 复位（否则同进程永久缩水）
        HeroAvailability.markSuccess(engine);
        assertEquals("成功后复位完整上限", 3, FormationResolver.autoProfessionHeroes(engine).size());
        assertFalse(HeroAvailability.hasHeroLimitHint(engine));
    }

    @Test
    public void auto406_scalesTargetThenSuccessRestores() {
        presetNoLevelAll();
        HeroAvailability.noteSoldierLimitRejected(engine);   // 75%
        FormationResolver.ResolveOutcome ro = FormationResolver.autoOutcome(engine,
                FormationSpec.defaultRatio(), 1000);
        assertTrue(ro.reason, ro.ready());
        // target=min(1000, 50000)=1000 → 406 75% = 750 → 40/30/30
        assertEquals(750L, ro.formation.totalSoldiers());
        assertEquals(300L, ro.formation.infantry);
        assertEquals(225L, ro.formation.lancer);
        assertTrue(ro.reason, ro.reason.contains("406"));
        HeroAvailability.markSuccess(engine);
        assertEquals(100, HeroAvailability.soldierScalePercent(engine));
        FormationResolver.ResolveOutcome full = FormationResolver.autoOutcome(engine,
                FormationSpec.defaultRatio(), 1000);
        assertEquals(1000L, full.formation.totalSoldiers());
    }

    @Test
    public void auto_unknownProfessionExcluded() {
        newEngine();
        Map<String, Object> hi = new LinkedHashMap<>();
        Map<String, Object> unknown = hero(H_UNKNOWN, 0L);
        unknown.put("power", 9999L);   // 战力最高但职业未知
        hi.put(H_UNKNOWN + ".0", unknown);
        hi.put(H_SHIELD + ".0", hero(H_SHIELD, 0L));
        hi.put(H_LANCER + ".0", hero(H_LANCER, 0L));
        hi.put(H_ARCHER + ".0", hero(H_ARCHER, 0L));
        engine.notifies.put("notify_hero_init", hi);
        presetSoldiers();
        capture();
        List<Long> picked = FormationResolver.autoProfessionHeroes(engine);
        assertEquals(3, picked.size());
        assertFalse("profession=0 不参与 AUTO", picked.contains(H_UNKNOWN));
        assertEquals(List.of(H_SHIELD, H_LANCER, H_ARCHER), picked);
    }

    @Test
    public void auto_usesLibraryAutomaticSettingsWhenPresent() {
        newEngine();
        presetHeroesNoLevel(H_SHIELD, H_LANCER, H_ARCHER);
        presetSoldiers(100000L, 100000L, 100000L);
        capture();
        FormationLibrary lib = FormationLibrary.parse("{\"automatic_settings\":"
                + "{\"infantry_ratio\":50,\"lancer_ratio\":20,\"marksman_ratio\":30},"
                + "\"formations\":[]}");
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine, lib,
                params("formation_mode", "AUTOMATIC"));
        assertTrue(ro.reason, ro.ready());
        // 50000 × 50/20/30
        assertEquals(25000L, ro.formation.infantry);
        assertEquals(10000L, ro.formation.lancer);
        assertEquals(15000L, ro.formation.marksman);
    }

    @Test
    public void auto_defaultRatioUnchanged_whenNoAutomaticSettings() {
        newEngine();
        presetHeroesNoLevel(H_SHIELD, H_LANCER, H_ARCHER);
        presetSoldiers(100000L, 100000L, 100000L);
        capture();
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine,
                FormationLibrary.parse("{\"formations\":[]}"),
                params("formation_mode", "AUTOMATIC"));
        assertTrue(ro.reason, ro.ready());
        // 旧默认 40:30:30 不因 v2 改变
        assertEquals(20000L, ro.formation.infantry);
        assertEquals(15000L, ro.formation.lancer);
        assertEquals(15000L, ro.formation.marksman);
    }

    // ==================================================================
    // ⑦ 旧行为/回归
    // ==================================================================

    @Test
    public void fallback_noParamsStillLegacy() {
        presetNoLevelAll();
        FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(engine,
                FormationLibrary.empty(), new LinkedHashMap<>());
        assertTrue(ro.ready());
        assertEquals(ResolvedFormation.SRC_FALLBACK, ro.formation.source);
        assertEquals("无参数仍走旧行为（payload 与 Exec.formation 一致）",
                com.carlos.vaultserver.task.Exec.formation(engine, 1),
                ro.formation.toFormationPayload());
    }
}
