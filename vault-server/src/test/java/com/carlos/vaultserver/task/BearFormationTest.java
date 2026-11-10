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

    // ======================= 七、批次11：功能级 soldiers 覆盖（参数 > 库值） =======================

    @Test
    public void mergeSoldiers_paramOverridesLibrary_fieldWise() {
        FormationLibrary lib = libWithSoldiers(1L, 100L, 200L, 300L);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("formation_mode", "ROLE");
        params.put("formation_id", 1L);
        params.put("soldiers", soldiersMap(1000L, 0L, 3000L));
        Map<String, Object> merged = ServerBearTasks.mergeSoldiersForResolve(params, lib);
        Map<String, Object> got = Exec.mapOf(merged.get("soldiers"));
        assertNotNull(got);
        assertEquals("参数覆盖盾兵", 1000L, Exec.longOf(got.get("infantry")));
        assertEquals("参数 0 → 回退库值（矛）", 200L, Exec.longOf(got.get("lancer")));
        assertEquals("参数覆盖弓兵", 3000L, Exec.longOf(got.get("marksman")));
    }

    @Test
    public void mergeSoldiers_noOverride_usesLibraryValue() {
        FormationLibrary lib = libWithSoldiers(1L, 100L, 200L, 300L);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("formation_mode", "ROLE");
        params.put("formation_id", 1L);
        Map<String, Object> got = Exec.mapOf(
                ServerBearTasks.mergeSoldiersForResolve(params, lib).get("soldiers"));
        assertNotNull("无功能级覆盖 → 注入库值", got);
        assertEquals(100L, Exec.longOf(got.get("infantry")));
        assertEquals(200L, Exec.longOf(got.get("lancer")));
        assertEquals(300L, Exec.longOf(got.get("marksman")));
    }

    @Test
    public void mergeSoldiers_autoModeOrNoLib_untouched() {
        FormationLibrary lib = libWithSoldiers(1L, 100L, 200L, 300L);
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("formation_mode", "AUTOMATIC");
        auto.put("formation_id", 1L);
        assertNull("AUTOMATIC 不注入库兵量（走 ratio/total 自动）",
                ServerBearTasks.mergeSoldiersForResolve(auto, lib).get("soldiers"));
        Map<String, Object> noLib = new LinkedHashMap<>();
        noLib.put("formation_mode", "ROLE");
        noLib.put("formation_id", 1L);
        assertNull("无库 → 不注入", ServerBearTasks.mergeSoldiersForResolve(
                noLib, FormationLibrary.empty()).get("soldiers"));
        FormationLibrary ratioOnly = FormationLibrary.parse(
                "{\"formations\":[{\"id\":1,\"hero_ids\":[50041],\"ratio\":{\"infantry\":40}}]}");
        assertNull("库编队无显式兵量 → 不注入", ServerBearTasks.mergeSoldiersForResolve(
                noLib, ratioOnly).get("soldiers"));
        Map<String, Object> paramOnly = new LinkedHashMap<>();
        paramOnly.put("formation_mode", "ROLE");
        paramOnly.put("formation_id", 2L);
        paramOnly.put("soldiers", soldiersMap(5L, 6L, 7L));
        Map<String, Object> out = ServerBearTasks.mergeSoldiersForResolve(paramOnly, lib);
        assertEquals("库里没有该编队 id：参数原样保留交给 resolver 校验", 5L,
                Exec.longOf(Exec.mapOf(out.get("soldiers")).get("infantry")));
    }

    // ======================= 八、批次11：上车坏槽跳过继续 / disabled 槽 =======================

    @Test
    public void selectJoinSlots_badSlotSkippedGoodSlotsContinue() {
        List<ServerBearTasks.JoinFormationConfig> slots = Arrays.asList(
                joinSlot(1, true, "ROLE", 2L),
                joinSlot(2, true, "ROLE", 9L),
                joinSlot(3, false, "ROLE", 2L),
                joinSlot(4, true, "AUTOMATIC", 0L));
        ServerBearTasks.JoinSlotSelection sel = ServerBearTasks.selectJoinSlots(slots, slot -> {
            if (slot.id == 2) {
                return new ServerBearTasks.JoinSlotPlan(slot, null, "", "编队 #9 不存在或没有英雄");
            }
            return new ServerBearTasks.JoinSlotPlan(slot, formation(), "来源=" + slot.mode, "");
        });
        assertEquals("坏槽跳过继续：可用槽=#1/#4 保序", Arrays.asList(1, 4), usableSlotIds(sel));
        assertEquals("disabled 槽 #3 直接跳过", Arrays.asList(3), sel.disabledIds);
        assertEquals(1, sel.skippedNotes.size());
        assertTrue("跳过原因可读", sel.skippedNotes.get(0)
                .contains("上车编队#2 跳过：编队 #9 不存在或没有英雄"));
        assertFalse(sel.allSkipped());
    }

    @Test
    public void selectJoinSlots_allBad_endsWithReadableNotes() {
        List<ServerBearTasks.JoinFormationConfig> slots = Arrays.asList(
                joinSlot(1, true, "ROLE", 8L), joinSlot(2, true, "ROLE", 9L));
        ServerBearTasks.JoinSlotSelection sel = ServerBearTasks.selectJoinSlots(slots,
                slot -> new ServerBearTasks.JoinSlotPlan(slot, null, "", "兵量超出库存"));
        assertTrue("全槽失败才结束本场上车", sel.allSkipped());
        assertEquals(2, sel.skippedNotes.size());
        String notes = sel.notesText();
        assertTrue(notes.contains("上车编队#1 跳过：兵量超出库存"));
        assertTrue(notes.contains("上车编队#2 跳过：兵量超出库存"));
        assertTrue(sel.disabledIds.isEmpty());
    }

    @Test
    public void selectJoinSlots_disabledOnly_noRequestNoFailure() {
        List<ServerBearTasks.JoinFormationConfig> slots = Arrays.asList(
                joinSlot(1, false, "ROLE", 2L), joinSlot(2, false, "AUTOMATIC", 0L));
        ServerBearTasks.JoinSlotSelection sel = ServerBearTasks.selectJoinSlots(slots, slot -> {
            throw new AssertionError("disabled 槽不应调用解析器（不发请求/不计失败）");
        });
        assertTrue(sel.usable.isEmpty());
        assertEquals(Arrays.asList(1, 2), sel.disabledIds);
        assertTrue("disabled 槽不产生跳过失败记录", sel.skippedNotes.isEmpty());
    }

    @Test
    public void selectJoinSlots_resolverThrows_treatedAsSkipAndContinue() {
        List<ServerBearTasks.JoinFormationConfig> slots = Arrays.asList(
                joinSlot(1, true, "ROLE", 2L), joinSlot(2, true, "ROLE", 3L));
        ServerBearTasks.JoinSlotSelection sel = ServerBearTasks.selectJoinSlots(slots, slot -> {
            if (slot.id == 1) throw new IllegalStateException("boom");
            return new ServerBearTasks.JoinSlotPlan(slot, formation(), "ok", "");
        });
        assertEquals("异常槽按跳过处理，继续下一槽", Arrays.asList(2), usableSlotIds(sel));
        assertTrue(sel.skippedNotes.get(0).contains("上车编队#1 跳过"));
    }

    @Test
    public void planJoinAttempts_pairsSlotsAndSkipsConflicts() {
        ServerBearTasks.JoinSlotSelection sel = new ServerBearTasks.JoinSlotSelection();
        sel.usable.add(joinPlan(1, Arrays.asList(11L, 12L)));
        sel.usable.add(joinPlan(2, Arrays.asList(21L, 22L)));
        sel.usable.add(joinPlan(3, Arrays.asList(31L, 32L)));
        List<ServerBearTasks.RallyCandidate> candidates = Arrays.asList(
                candidate(100L), candidate(200L), candidate(300L));
        Set<Long> used = new LinkedHashSet<>(Arrays.asList(11L));   // 槽1英雄已被占用
        List<String> conflictNotes = new ArrayList<>();
        List<ServerBearTasks.JoinAttempt> attempts = ServerBearTasks.planJoinAttempts(
                sel, candidates, used, 3, conflictNotes);
        assertEquals("坏槽后继续：槽2/槽3 各 1 次", 2, attempts.size());
        assertEquals("槽1冲突跳过", 1, conflictNotes.size());
        assertTrue(conflictNotes.get(0).contains("上车编队#1 跳过：英雄已被发起队/前序上车占用"));
        assertEquals("冲突不消耗车头：槽2 用第 1 个车头", 100L, attempts.get(0).candidate.masterMarchId);
        assertEquals("槽3 用第 2 个车头", 200L, attempts.get(1).candidate.masterMarchId);
    }

    @Test
    public void planJoinAttempts_respectsRemainingAndCandidateCount() {
        ServerBearTasks.JoinSlotSelection sel = new ServerBearTasks.JoinSlotSelection();
        sel.usable.add(joinPlan(1, Arrays.asList(11L)));
        sel.usable.add(joinPlan(2, Arrays.asList(21L)));
        sel.usable.add(joinPlan(3, Arrays.asList(31L)));
        List<ServerBearTasks.RallyCandidate> candidates = Arrays.asList(
                candidate(100L), candidate(200L));
        assertEquals("候选车头数上限", 2, ServerBearTasks.planJoinAttempts(
                sel, candidates, Collections.emptySet(), 5, new ArrayList<>()).size());
        assertEquals("剩余配额上限", 1, ServerBearTasks.planJoinAttempts(
                sel, candidates, Collections.emptySet(), 1, new ArrayList<>()).size());
        assertTrue(ServerBearTasks.planJoinAttempts(sel, null,
                Collections.emptySet(), 5, null).isEmpty());
    }

    @Test
    public void recordJoinSkipLogOnce_perSlotPerWindow() {
        ServerBearTasks.BearState state = new ServerBearTasks.BearState();
        assertTrue("首次记录", ServerBearTasks.recordJoinSkipLogOnce(state, 1000L, 2));
        assertFalse("同场次同槽只记一次", ServerBearTasks.recordJoinSkipLogOnce(state, 1000L, 2));
        assertTrue("同场次其它槽可记", ServerBearTasks.recordJoinSkipLogOnce(state, 1000L, 3));
        assertTrue("新场次重新记录", ServerBearTasks.recordJoinSkipLogOnce(state, 2000L, 2));
        assertEquals("新场次只保留当前槽", "2", state.joinSkipSlots);
        ServerBearTasks.BearState back = ServerBearTasks.parseState(ServerBearTasks.stateJson(state));
        assertEquals("跳过节流状态可持久化", 2000L, back.joinSkipWindowStart);
        assertEquals("2", back.joinSkipSlots);
    }

    @Test
    public void joinSlot_disabledParsedButExcludedFromEnabledIds() {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("id", 1).put("mode", "ROLE").put("formation_id", 2));
        arr.put(new JSONObject().put("id", 2).put("mode", "ROLE").put("formation_id", 2)
                .put("enabled", false));
        JSONObject p = new JSONObject();
        p.put("join_formations", arr);
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        assertEquals(2, cfg.joinFormations.size());
        assertFalse(cfg.joinFormations.get(1).enabled);
        assertEquals("disabled 槽不进启用列表", Arrays.asList(1),
                ServerBearTasks.enabledJoinFormationIds(cfg));
    }

    // ======================= 九、批次11：launch_enabled / 编队 enabled 语义 =======================

    @Test
    public void launchEnabledSemantics_webAligned() {
        JSONObject off = new JSONObject();
        off.put("launch_enabled", false);
        assertFalse("launch_enabled=false → 本场不发车（网页版 launchEnabled）",
                ServerBearTasks.BearConfig.of(off).launchEnabled);
        JSONObject formationDisabled = new JSONObject();
        formationDisabled.put("enabled", false);   // 网页版编队级 enabled
        assertTrue("编队级 enabled 不单独拦截发起（网页版校验/保护英雄用）",
                ServerBearTasks.BearConfig.of(formationDisabled).launchEnabled);
        assertTrue("默认发起开（部署后旧行为不变）",
                ServerBearTasks.BearConfig.of(new JSONObject()).launchEnabled);
    }

    // ======================= 辅助构造（批次11） =======================

    private static FormationLibrary libWithSoldiers(long id, long infantry, long lancer, long marksman) {
        String json = "{\"formations\":[{\"id\":" + id + ",\"name\":\"t\","
                + "\"hero_ids\":[50041,50039,50037],"
                + "\"soldiers\":{\"infantry\":" + infantry + ",\"lancer\":" + lancer
                + ",\"marksman\":" + marksman + "}}]}";
        return FormationLibrary.parse(json);
    }

    private static Map<String, Object> soldiersMap(long infantry, long lancer, long marksman) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("infantry", infantry);
        m.put("lancer", lancer);
        m.put("marksman", marksman);
        return m;
    }

    private static Map<String, Object> formation() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", Arrays.asList(Exec.kv(11L, 1L)));
        f.put("soldier", Arrays.asList(Exec.kv(11000L, 1000L)));
        return f;
    }

    private static ServerBearTasks.JoinFormationConfig joinSlot(int id, boolean enabled,
                                                                String mode, long formationId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", (long) id);
        params.put("enabled", enabled);
        params.put("formation_mode", mode);
        if (formationId > 0) params.put("formation_id", formationId);
        return new ServerBearTasks.JoinFormationConfig(id, enabled, mode, formationId,
                0L, 0L, 0L, params);
    }

    private static ServerBearTasks.JoinSlotPlan joinPlan(int slotId, List<Long> heroes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hero_id", Exec.heroPairs(heroes));
        payload.put("soldier", Arrays.asList(Exec.kv(11000L, 1000L)));
        ServerBearTasks.JoinFormationConfig slot = joinSlot(slotId, true, "ROLE", slotId);
        return new ServerBearTasks.JoinSlotPlan(slot, payload, "来源=ROLE#" + slotId, "");
    }

    private static List<Integer> usableSlotIds(ServerBearTasks.JoinSlotSelection selection) {
        List<Integer> ids = new ArrayList<>();
        for (ServerBearTasks.JoinSlotPlan plan : selection.usable) ids.add(plan.slot.id);
        return ids;
    }

    private static ServerBearTasks.RallyCandidate candidate(long masterMarchId) {
        ServerBearTasks.RallyCandidate c = new ServerBearTasks.RallyCandidate();
        c.masterMarchId = masterMarchId;
        c.ownerUid = 5L;
        c.startX = 10L;
        c.startY = 20L;
        return c;
    }

    // ======================= 2026-11-10：默认模板/默认上车槽 =======================

    @Test
    public void defaultJoinSlots_usesExistingRidePresetsOnly() {
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":9100000010,\"name\":\"熊上车1\",\"hero_ids\":[50036]},"
                + "{\"id\":9100000011,\"name\":\"熊上车2\",\"hero_ids\":[50026]}]}");
        List<ServerBearTasks.JoinFormationConfig> slots = ServerBearTasks.defaultJoinSlots(
                lib, ServerBearTasks.BearConfig.defaults());
        assertEquals("库内只存在 2 套熊上车预设", 2, slots.size());
        assertEquals(1, slots.get(0).id);
        assertEquals("ROLE", slots.get(0).mode);
        assertEquals(9100000010L, slots.get(0).formationId);
        assertEquals(2, slots.get(1).id);
        assertEquals(9100000011L, slots.get(1).formationId);
        assertTrue(slots.get(0).enabled);

        assertTrue("空库不生成默认槽",
                ServerBearTasks.defaultJoinSlots(null, ServerBearTasks.BearConfig.defaults()).isEmpty());
    }
}
