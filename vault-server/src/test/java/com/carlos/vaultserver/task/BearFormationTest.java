package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.guardian.ServerGuardianLauncher;
import com.carlos.vaultserver.formation.FormationLibrary;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.FormationSpec;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次10（task-32）：统一编队接入的纯函数单测（巨熊发起/上车 1-6 / 巨兽 / 守护盟友 + 301 降级）。
 *
 * <p>基准：
 * <ul>
 *   <li>网页版 {@code DailyBearFormationConfig}（id 0=发起、1-6=上车；enabled/formationMode/
 *       formationId/infantry/lancer/marksman）与 {@code DailyBearMarchExecutor.resolveFormation:562-570}；</li>
 *   <li>网页版 {@code DailyMarchFormationResolver}（AUTOMATIC/ROLE；英雄不在城→换英雄组合）——301 语义按
 *       task-32 冻结口径：拉黑 301 英雄后换其余英雄组合，最多 2 轮；</li>
 *   <li>formation 包冻结契约（FormationLibrary/FormationResolver/ResolvedFormation）。</li>
 * </ul>
 *
 * <p>全部不连真协议；引擎相关路径（resolve 需要 WjdrEngine）只测 null-engine 的失败分支与纯函数部分。
 */
public class BearFormationTest {

    // ======================= 一、巨熊：参数转换 / 配置检测 =======================

    @Test
    public void paramsToMap_deepConversion() {
        JSONObject soldiers = new JSONObject();
        soldiers.put("infantry", 20000);
        soldiers.put("lancer", 1549);
        JSONObject ratio = new JSONObject();
        ratio.put("infantry", 1);
        JSONObject p = new JSONObject();
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 3);
        p.put("soldiers", soldiers);
        p.put("ratio", ratio);
        p.put("join_formations", new JSONArray(Arrays.asList(
                new JSONObject().put("id", 1).put("mode", "ROLE").put("formation_id", 2))));
        Map<String, Object> m = ServerBearTasks.paramsToMap(p);
        assertEquals("ROLE", m.get("formation_mode"));
        assertEquals(3L, Exec.longOf(m.get("formation_id")));
        Map<String, Object> soldiersMap = Exec.mapOf(m.get("soldiers"));
        assertNotNull("soldiers 深转换为 Map", soldiersMap);
        assertEquals(20000L, Exec.longOf(soldiersMap.get("infantry")));
        Map<String, Object> ratioMap = Exec.mapOf(m.get("ratio"));
        assertNotNull(ratioMap);
        assertEquals(1L, Exec.longOf(ratioMap.get("infantry")));
        List<?> joins = (List<?>) m.get("join_formations");
        assertNotNull("join_formations 深转换为 List", joins);
        assertEquals(1, joins.size());
        Map<String, Object> slot = Exec.mapOf(joins.get(0));
        assertNotNull(slot);
        assertEquals(1L, Exec.longOf(slot.get("id")));
        assertEquals("null 安全", 0, ServerBearTasks.paramsToMap(null).size());
    }

    @Test
    public void hasGenericFormationConfig_detection() {
        assertFalse("空参数 → 旧行为", ServerBearTasks.hasGenericFormationConfig(
                new LinkedHashMap<>()));
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("hero_ids", "1,2,3");
        legacy.put("squad_index", 2);
        assertFalse("仅旧 hero_ids/squad_index → 旧行为", ServerBearTasks.hasGenericFormationConfig(legacy));
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("formation_mode", "ROLE");
        assertTrue(ServerBearTasks.hasGenericFormationConfig(mode));
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("formation_id", 2L);
        assertTrue(ServerBearTasks.hasGenericFormationConfig(id));
        Map<String, Object> soldiers = new LinkedHashMap<>();
        soldiers.put("soldiers", new LinkedHashMap<>());
        assertTrue(ServerBearTasks.hasGenericFormationConfig(soldiers));
        Map<String, Object> joins = new LinkedHashMap<>();
        joins.put("join_formations", new ArrayList<>());
        assertTrue(ServerBearTasks.hasGenericFormationConfig(joins));
        Map<String, Object> singleMode = new LinkedHashMap<>();
        singleMode.put("mode", "ROLE");
        assertTrue("App join 槽用 mode 也识别", ServerBearTasks.hasGenericFormationConfig(singleMode));
        assertFalse(ServerBearTasks.hasGenericFormationConfig(null));
    }

    @Test
    public void bearConfig_parsesFormationFields() {
        JSONObject p = new JSONObject();
        p.put("launch_enabled", true);
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 2);
        p.put("soldiers", new JSONObject().put("infantry", 20000).put("lancer", 1549).put("marksman", 28451));
        p.put("formation_library", "{\"formations\":[{\"id\":2,\"hero_ids\":[50041]}]}");
        JSONArray joins = new JSONArray();
        joins.put(new JSONObject().put("id", 1).put("enabled", true).put("mode", "ROLE")
                .put("formation_id", 2).put("soldiers",
                        new JSONObject().put("infantry", 1000).put("lancer", 500).put("marksman", 500)));
        joins.put(new JSONObject().put("id", 3).put("enabled", false)
                .put("mode", "AUTOMATIC").put("ratio", new JSONObject().put("infantry", 1)));
        p.put("join_formations", joins);
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        assertTrue("显式编队参数 → 走统一编队", cfg.formationConfigured);
        assertEquals("库 JSON 原样保存", p.optString("formation_library"), cfg.formationLibraryJson);
        assertEquals("ROLE", cfg.paramsMap.get("formation_mode"));
        assertEquals(2, cfg.joinFormations.size());
        ServerBearTasks.JoinFormationConfig slot1 = cfg.joinFormations.get(0);
        assertEquals(1, slot1.id);
        assertTrue(slot1.enabled);
        assertEquals("ROLE", slot1.mode);
        assertEquals(2L, slot1.formationId);
        assertEquals(1000L, slot1.infantry);
        assertEquals("槽参数统一键名 formation_mode", "ROLE", slot1.params.get("formation_mode"));
        assertFalse("disabled 槽", cfg.joinFormations.get(1).enabled);
    }

    @Test
    public void bearConfig_legacyOnlyUnchanged() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", "50041,50042");
        p.put("squad_index", 2);
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        assertFalse("仅旧参数 → formationConfigured=false（部署后零行为变化）", cfg.formationConfigured);
        assertEquals(Arrays.asList(50041L, 50042L), cfg.heroIds);
        assertEquals(2, cfg.squadIndex);
        assertTrue("无 join_formations", cfg.joinFormations.isEmpty());
        assertTrue("默认参数同样旧行为", ServerBearTasks.BearConfig.of(new JSONObject()).joinFormations.isEmpty());
        assertNull("旧行为下无上车编队槽", ServerBearTasks.joinFormationAt(cfg, 0));
        assertTrue(ServerBearTasks.enabledJoinFormationIds(cfg).isEmpty());
    }

    // ======================= 二、巨熊：join_formations 1-6 =======================

    @Test
    public void parseJoinFormations_idsAndOrder() {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("id", 5).put("mode", "AUTO"));
        arr.put(new JSONObject().put("id", 2).put("mode", "ROLE").put("formation_id", 4));
        arr.put(new JSONObject().put("id", 2).put("mode", "ROLE"));              // 重复 id → 丢弃
        arr.put(new JSONObject().put("id", 9).put("mode", "ROLE"));              // 越界 → 丢弃
        arr.put(new JSONObject().put("mode", "ROLE"));                            // 缺 id → 下标+1=5? 但 5 已存在 → 丢弃
        arr.put(new JSONObject());                                                // 空对象 → 丢弃
        JSONObject p = new JSONObject();
        p.put("join_formations", arr);
        List<ServerBearTasks.JoinFormationConfig> slots = ServerBearTasks.parseJoinFormations(p);
        assertEquals("有效槽数量", 2, slots.size());
        assertEquals(5, slots.get(0).id);
        assertEquals(2, slots.get(1).id);
        assertEquals(Arrays.asList(2, 5), ServerBearTasks.enabledJoinFormationIds(
                ServerBearTasks.BearConfig.of(p)));
        assertEquals("第 0 个槽=最小 id", 2,
                ServerBearTasks.joinFormationAt(ServerBearTasks.BearConfig.of(p), 0).id);
        assertEquals("第 1 个槽=次小 id", 5,
                ServerBearTasks.joinFormationAt(ServerBearTasks.BearConfig.of(p), 1).id);
        assertNull("越界 → null", ServerBearTasks.joinFormationAt(
                ServerBearTasks.BearConfig.of(p), 2));
    }

    @Test
    public void parseJoinFormations_missingIdUsesIndex() {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("mode", "ROLE").put("formation_id", 1));
        arr.put(new JSONObject().put("mode", "ROLE").put("formation_id", 2));
        JSONObject p = new JSONObject();
        p.put("join_formations", arr);
        List<ServerBearTasks.JoinFormationConfig> slots = ServerBearTasks.parseJoinFormations(p);
        assertEquals(2, slots.size());
        assertEquals(1, slots.get(0).id);
        assertEquals(2, slots.get(1).id);
    }

    @Test
    public void parseJoinFormations_invalidInputs() {
        assertTrue(ServerBearTasks.parseJoinFormations(null).isEmpty());
        JSONObject p = new JSONObject();
        assertTrue(ServerBearTasks.parseJoinFormations(p).isEmpty());
        p.put("join_formations", "not-an-array");
        assertTrue("非法形状 → 空（走旧逻辑）", ServerBearTasks.parseJoinFormations(p).isEmpty());
    }

    // ======================= 三、301 降级重试序列 =======================

    @Test
    public void blacklist301_accumulatesAndDedupes() {
        Set<Long> b = ServerBearTasks.blacklistHeroes(null, Arrays.asList(11L, 12L));
        assertEquals(new LinkedHashSet<>(Arrays.asList(11L, 12L)), b);
        Set<Long> b2 = ServerBearTasks.blacklistHeroes(b, Arrays.asList(12L, 13L, 0L, -1L));
        assertEquals("累加且去重/忽略非法", new LinkedHashSet<>(Arrays.asList(11L, 12L, 13L)), b2);
        assertEquals("原集合不被修改", 2, b.size());
        assertTrue("null 安全", ServerBearTasks.blacklistHeroes(null, null).isEmpty());
        assertTrue(ServerBearTasks.blacklistHeroes(Collections.emptySet(), null).isEmpty());
    }

    @Test
    public void canRetry301_maxTwoRounds() {
        assertEquals(2, ServerBearTasks.MAX_BEAR_301_ROUNDS);
        assertEquals(301, ServerBearTasks.CODE_HERO_NOT_IN_CITY);
        assertTrue(ServerBearTasks.canRetry301(0));
        assertTrue(ServerBearTasks.canRetry301(1));
        assertFalse("第 2 轮后不再重试（防风暴）", ServerBearTasks.canRetry301(2));
        assertFalse(ServerBearTasks.canRetry301(-1));
    }

    @Test
    public void bear301_downgradeSequence() {
        // 初始编队英雄 11/12/13 触发 301 → 拉黑后换 14/15/16；再 301 → 拉黑 6 个后无可用英雄
        List<Long> all = Arrays.asList(11L, 12L, 13L, 14L, 15L, 16L);
        Set<Long> blacklist = new LinkedHashSet<>();
        blacklist = ServerBearTasks.blacklistHeroes(blacklist, Arrays.asList(11L, 12L, 13L));
        assertTrue(ServerBearTasks.canRetry301(0));
        List<Long> round1 = ServerBearTasks.pickHeroesExcluding(all, blacklist, 3);
        assertEquals(Arrays.asList(14L, 15L, 16L), round1);
        blacklist = ServerBearTasks.blacklistHeroes(blacklist, round1);
        assertTrue(ServerBearTasks.canRetry301(1));
        List<Long> round2 = ServerBearTasks.pickHeroesExcluding(all, blacklist, 3);
        assertTrue("两轮后无可用英雄组合", round2.isEmpty());
        assertFalse("预算耗尽", ServerBearTasks.canRetry301(2));
        String text = ServerBearTasks.bear301Text(1, blacklist);
        assertTrue(text.contains("301"));
        assertTrue(text.contains("第1轮"));
        assertTrue(text.contains("11"));
        assertTrue(text.contains("16"));
    }

    @Test
    public void pickHeroesExcluding_filtersAndCaps() {
        List<Long> all = Arrays.asList(0L, 11L, 11L, 12L, -3L, 13L, 14L, 15L);
        assertEquals("去重/过滤非法/跳过黑名单/保序/上限",
                Arrays.asList(12L, 13L, 14L),
                ServerBearTasks.pickHeroesExcluding(all,
                        new LinkedHashSet<>(Arrays.asList(11L, 15L)), 3));
        assertTrue(ServerBearTasks.pickHeroesExcluding(null, null, 3).isEmpty());
        assertTrue(ServerBearTasks.pickHeroesExcluding(all, null, 0).isEmpty());
        assertTrue("全部被拉黑 → 空", ServerBearTasks.pickHeroesExcluding(
                Arrays.asList(1L, 2L), new LinkedHashSet<>(Arrays.asList(1L, 2L)), 3).isEmpty());
    }

    // ======================= 四、巨兽：统一编队参数（兼容旧 hero_ids/squad_index） =======================

    @Test
    public void behemothConfig_formationFieldsAndLegacyCompat() {
        JSONObject p = new JSONObject();
        p.put("launch_enabled", true);
        p.put("formation_mode", "AUTOMATIC");
        p.put("ratio", new JSONObject().put("infantry", 5).put("lancer", 2).put("marksman", 3));
        p.put("total", 50000);
        p.put("formation_library", "{\"formations\":[]}");
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertTrue("显式编队参数", cfg.formationConfigured);
        assertTrue(FormationResolver.isConfigured(cfg.paramsMap));
        assertNotNull("ratio 深转换", Exec.mapOf(cfg.paramsMap.get("ratio")));
        assertTrue("库 JSON 保存", cfg.formationLibraryJson.contains("formations"));

        JSONObject legacy = new JSONObject();
        legacy.put("hero_ids", "101,102");
        legacy.put("squad_index", 2);
        ServerTasks.BehemothConfig old = ServerTasks.BehemothConfig.of(legacy);
        assertFalse("仅旧 hero_ids/squad_index → 旧行为", old.formationConfigured);
        assertEquals(Arrays.asList(101L, 102L), old.heroIds);
        assertEquals(2, old.squadIndex);
        assertFalse(ServerTasks.BehemothConfig.of(new JSONObject()).formationConfigured);
    }

    @Test
    public void formationResolver_isConfigured_matchesOurDetection() {
        // 冻结契约 isConfigured 语义与我们 hasGenericFormationConfig 对齐（含 mode）
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("hero_ids", "1,2");
        legacy.put("squad_index", 2);
        assertFalse(FormationResolver.isConfigured(legacy));
        assertFalse(ServerBearTasks.hasGenericFormationConfig(legacy));
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("mode", "ROLE");
        assertTrue(FormationResolver.isConfigured(mode));
        assertTrue(ServerBearTasks.hasGenericFormationConfig(mode));
        Map<String, Object> joinsOnly = new LinkedHashMap<>();
        joinsOnly.put("join_formations", new ArrayList<>());
        assertFalse("isConfigured 不看 join_formations（发起侧不得误判）",
                FormationResolver.isConfigured(joinsOnly));
        assertTrue("我方检测含 join_formations（上车侧）",
                ServerBearTasks.hasGenericFormationConfig(joinsOnly));
    }

    // ======================= 五、守护盟友：驻防编队解析包装 =======================

    @Test
    public void guardian_noFormationConfig_legacyUnchanged() {
        ServerGuardianLauncher.FormationResolution r =
                ServerGuardianLauncher.resolveFormation(null, new LinkedHashMap<>(), "");
        assertFalse("无编队参数 → 调用方走旧 hero_ids/squad_index/队1", r.configured);
        assertFalse(r.ready());
    }

    @Test
    public void guardian_explicitConfigWithoutEngine_skipWithReason() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("formation_mode", "ROLE");
        params.put("formation_id", 2L);
        ServerGuardianLauncher.FormationResolution r =
                ServerGuardianLauncher.resolveFormation(null, params, "");
        assertTrue("显式配置 → configured=true（调用方 SKIP，不静默回退）", r.configured);
        assertFalse("引擎为空 → 不可用", r.ready());
        assertFalse("原因可读", r.reason.isEmpty());
    }

    @Test
    public void guardian_toParamMap_deepConversion() {
        JSONObject p = new JSONObject();
        p.put("enabled", true);
        p.put("sub", new JSONObject().put("dry_run", false));
        p.put("target_point", new JSONObject().put("x", 100).put("y", 200));
        Map<String, Object> m = ServerGuardianLauncher.toParamMap(p);
        Map<String, Object> sub = Exec.mapOf(m.get("sub"));
        assertNotNull(sub);
        assertEquals(Boolean.FALSE, sub.get("dry_run"));
        Map<String, Object> tp = Exec.mapOf(m.get("target_point"));
        assertNotNull(tp);
        assertEquals(100L, Exec.longOf(tp.get("x")));
        assertEquals(0, ServerGuardianLauncher.toParamMap(null).size());
    }

    // ======================= 六、formation 包契约自检（只读依赖） =======================

    @Test
    public void formationLibrary_parsesBearJoinSlots() {
        String json = "{\"formations\":[{\"id\":2,\"name\":\"熊队\",\"hero_ids\":[50041,50039],"
                + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451},"
                + "\"ratio\":{\"infantry\":1,\"lancer\":1,\"marksman\":1},\"total\":50000}]}";
        FormationLibrary lib = FormationLibrary.parse(json);
        assertEquals(1, lib.size());
        FormationSpec spec = lib.byId(2);
        assertNotNull(spec);
        assertEquals(Arrays.asList(50041L, 50039L), spec.heroIds);
        assertEquals(20000L, spec.infantry);
        assertNull("不存在的编队 → null（SKIP）", lib.byId(99));
        assertFalse(lib.isEmpty());
        assertNotNull(FormationSpec.bearRatio());
        assertEquals(3, FormationSpec.bearRatio().size());
    }

    @Test
    public void formationResolver_bearRatioConstant() {
        assertEquals(3, FormationResolver.BEAR_RATIO.length);
        assertEquals(1L, FormationResolver.BEAR_RATIO[0]);
        assertEquals(1L, FormationResolver.BEAR_RATIO[1]);
        assertEquals(1L, FormationResolver.BEAR_RATIO[2]);
        assertEquals("ROLE", FormationResolver.MODE_ROLE);
        assertEquals("AUTOMATIC", FormationResolver.MODE_AUTOMATIC);
    }
}
