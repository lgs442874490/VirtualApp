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
    public void validate_heroCountOneToThree() {
        FormationStore.Formation none = withTroops();
        none.heroIds.clear();
        assertTrue("0 英雄非法", FormationStore.validate(none).contains("英雄"));
        FormationStore.Formation four = withTroops();
        four.heroIds.clear();
        for (long i = 1; i <= 4; i++) four.heroIds.add(50000 + i);
        assertTrue("4 英雄非法（游戏每队列最多 3）", FormationStore.validate(four).contains("最多"));
        FormationStore.Formation three = withTroops();
        three.heroIds.clear();
        for (long i = 1; i <= 3; i++) three.heroIds.add(50000 + i);
        assertNull("3 英雄合法", FormationStore.validate(three));
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

    // ==================== 批次11：功能级 soldiers 覆盖 ====================

    @Test
    public void soldiersOverride_parseSerializeDefaults() throws Exception {
        long[] none = FormationStore.parseSoldiers(null);
        assertEquals(0, none[0]);
        assertEquals(0, none[1]);
        assertEquals(0, none[2]);
        JSONObject o = FormationStore.soldiersJson(20000, -5, 30000);
        assertEquals(20000L, o.getLong("infantry"));
        assertEquals("负数归 0", 0L, o.getLong("lancer"));
        assertEquals(30000L, o.getLong("marksman"));
        long[] parsed = FormationStore.parseSoldiers(o);
        assertEquals(20000L, parsed[0]);
        assertEquals(0L, parsed[1]);
        assertEquals(30000L, parsed[2]);
        // 缺字段=0（兼容旧数据）
        long[] partial = FormationStore.parseSoldiers(new JSONObject().put("infantry", 500));
        assertEquals(500L, partial[0]);
        assertEquals(0L, partial[1]);
        assertEquals(0L, partial[2]);
    }

    @Test
    public void soldiersOverride_text() {
        assertEquals("未覆盖", FormationStore.soldiersOverrideText(null));
        assertEquals("未覆盖", FormationStore.soldiersOverrideText(new long[]{0, 0, 0}));
        assertEquals("覆盖：盾100/矛0/弓200",
                FormationStore.soldiersOverrideText(new long[]{100, 0, 200}));
    }

    // ==================== 批次11：巨熊上车槽 enabled ====================

    @Test
    public void joinElement_enabledField() throws Exception {
        assertTrue("默认启用", FormationStore.joinEnabled(FormationStore.joinElement(1, "AUTO", 0)));
        assertTrue(FormationStore.joinElement(1, "AUTOMATIC", 0).getBoolean("enabled"));
        assertFalse("显式停用", FormationStore.joinElement(2, "ROLE", 3, false)
                .getBoolean("enabled"));
        // 老数据无 enabled 键 → 默认启用（向后兼容）
        assertTrue("老数据默认启用", FormationStore.joinEnabled(
                new JSONObject().put("id", 3).put("mode", "AUTOMATIC")));
        assertFalse(FormationStore.joinEnabled(new JSONObject().put("enabled", false)));
        assertFalse("字符串 false 兼容", FormationStore.joinEnabled(
                new JSONObject().put("enabled", "false")));
        assertTrue(FormationStore.joinEnabled(new JSONObject().put("enabled", 1)));
    }

    // ==================== 批次11：守护盟友 params（FnParams 纯函数） ====================

    @Test
    public void guardianCfg_defaultsSafe() {
        FnParams.GuardianCfg c = FnParams.guardianCfg(new JSONObject());
        assertFalse("默认关闭", c.enabled);
        assertTrue("默认演练模式（只记录不派兵）", c.dryRun);
        assertTrue("默认 enemy 召回撤防", c.recall);
        assertEquals(0L, c.allyUid);
        assertEquals(0L, c.targetUid);
        assertEquals(0, c.targetX);
        assertEquals(0, c.targetY);
        assertEquals("默认提前量 38 秒", 38, c.dSec);
        assertFalse(c.hasTargetPoint());
        assertTrue(c.modeText().contains("演练"));
        // null 安全
        FnParams.GuardianCfg n = FnParams.guardianCfg(null);
        assertTrue(n.dryRun);
        assertEquals(38, n.dSec);
    }

    @Test
    public void guardianCfg_parseTopLevel() throws Exception {
        JSONObject p = new JSONObject()
                .put("enabled", true)
                .put("dry_run", false)
                .put("recall", false)
                .put("ally_uid", 123861751L)
                .put("target_uid", 98765432L)
                .put("target_point", new JSONObject().put("x", 687).put("y", 698))
                .put("d_sec", 45);
        FnParams.GuardianCfg c = FnParams.guardianCfg(p);
        assertTrue(c.enabled);
        assertFalse("显式实发", c.dryRun);
        assertFalse(c.recall);
        assertEquals(123861751L, c.allyUid);
        assertEquals(98765432L, c.targetUid);
        assertEquals(687, c.targetX);
        assertEquals(698, c.targetY);
        assertEquals(45, c.dSec);
        assertTrue(c.modeText().contains("实发"));
    }

    @Test
    public void guardianCfg_legacySubStrategyCompat() throws Exception {
        // 旧 FnDetail 开关写在 sub/strategy；服务器 first(sub,st,top) 口径 → App 同口径
        JSONObject p = new JSONObject()
                .put("sub", new JSONObject().put("intercept", true))
                .put("strategy", new JSONObject().put("d_sec", "60"));
        FnParams.GuardianCfg c = FnParams.guardianCfg(p);
        assertTrue("旧 intercept 兼容为 enabled", c.enabled);
        assertEquals("strategy 里的 d_sec 生效", 60, c.dSec);
        assertTrue("dry_run 缺省演练", c.dryRun);
    }

    @Test
    public void guardianCfg_dSecClamp() throws Exception {
        assertEquals("越界回落 38", 38,
                FnParams.guardianCfg(new JSONObject().put("d_sec", 5)).dSec);
        assertEquals(38, FnParams.guardianCfg(new JSONObject().put("d_sec", 9999)).dSec);
        assertEquals(10, FnParams.guardianCfg(new JSONObject().put("d_sec", 10)).dSec);
        assertEquals(600, FnParams.guardianCfg(new JSONObject().put("d_sec", 600)).dSec);
    }

    @Test
    public void applyGuardianCfg_preservesFormationKeys() throws Exception {
        JSONObject prev = new JSONObject()
                .put("formation_mode", "ROLE")
                .put("formation_id", 1)
                .put("ratio", new JSONObject().put("infantry", 40).put("lancer", 30)
                        .put("marksman", 30))
                .put("intercept", true)
                .put("target_point", new JSONObject().put("x", 1).put("y", 2));
        FnParams.GuardianCfg c = new FnParams.GuardianCfg();
        c.enabled = true;
        c.dryRun = true;
        c.allyUid = 100;
        c.dSec = 38;
        JSONObject out = FnParams.applyGuardianCfg(prev, c);
        assertEquals("编队字段保留", "ROLE", out.getString("formation_mode"));
        assertEquals(1, out.getInt("formation_id"));
        assertEquals(40, out.getJSONObject("ratio").getInt("infantry"));
        assertTrue(out.getBoolean("enabled"));
        assertTrue(out.getBoolean("dry_run"));
        assertEquals(100L, out.getLong("ally_uid"));
        assertEquals(38, out.getInt("d_sec"));
        assertFalse("顶层旧 intercept 清掉（避免 OR 残留）", out.has("intercept"));
        assertFalse("坐标 0=不指定 → 移除 target_point", out.has("target_point"));
        // 批次11：本地模式镜像开关（演练=dry_run → 只监测不驻防）
        JSONObject sub = out.getJSONObject("sub");
        assertTrue("监测开", sub.getBoolean("watch"));
        assertFalse("演练模式不自动驻防", sub.getBoolean("intercept"));
    }

    @Test
    public void applyGuardianCfg_realModeEnablesLocalIntercept() throws Exception {
        FnParams.GuardianCfg c = new FnParams.GuardianCfg();
        c.enabled = true;
        c.dryRun = false;   // 实发
        JSONObject out = FnParams.applyGuardianCfg(new JSONObject(), c);
        assertTrue(out.getBoolean("enabled"));
        assertFalse(out.getBoolean("dry_run"));
        JSONObject sub = out.getJSONObject("sub");
        assertTrue("实发：本地自动驻防开", sub.getBoolean("intercept"));
        assertTrue(sub.getBoolean("watch"));
    }

    @Test
    public void applyGuardianCfg_writesTargetPoint() throws Exception {
        FnParams.GuardianCfg c = new FnParams.GuardianCfg();
        c.targetX = 687;
        c.targetY = 698;
        JSONObject out = FnParams.applyGuardianCfg(new JSONObject(), c);
        assertEquals(687, out.getJSONObject("target_point").getInt("x"));
        assertEquals(698, out.getJSONObject("target_point").getInt("y"));
    }

    @Test
    public void guardianLedger_stats() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("ts", 1792700000000L).put("ev", "threat_landed")
                .put("note", "敌方已落地"));
        arr.put(new JSONObject().put("ts", 1792699000000L).put("ev", "launched").put("note", "已发射"));
        arr.put(new JSONObject().put("ts", 1792698000000L).put("ev", "dry_run").put("note", "[DRY] 命中"));
        arr.put(new JSONObject().put("ts", 1792697000000L).put("ev", "launch_failed").put("note", "401"));
        arr.put(new JSONObject().put("ts", 1792696000000L).put("ev", "recall_error").put("note", "撤防异常"));
        arr.put(new JSONObject().put("ts", 1792695000000L).put("ev", "recalled").put("note", "已撤防"));
        arr.put(new JSONObject().put("ts", 1792694000000L).put("ev", "skip_offline").put("note", "离线"));
        FnParams.GuardianLedger l = FnParams.guardianLedger(arr);
        assertEquals(7, l.total);
        assertEquals(1, l.dryRun);
        assertEquals(1, l.launched);
        assertEquals("launch_failed + recall_error", 2, l.failed);
        assertEquals(1, l.recalled);
        assertEquals(1, l.skipped);
        assertEquals(1, l.threats);
        assertEquals("最新在前", "threat_landed", l.lastEv);
        assertEquals(1792700000000L, l.lastTs);
        assertEquals("敌方已落地", l.lastNote);
        String text = l.text();
        assertTrue(text.contains("演练1"));
        assertTrue(text.contains("实发1"));
        assertTrue(text.contains("失败2"));
        // 空账本
        assertTrue(FnParams.guardianLedger(null).text().isEmpty());
        assertTrue(FnParams.guardianLedger(new JSONArray()).text().isEmpty());
    }

    @Test
    public void guardianLedgerText_parseArrayAndWrapped() throws Exception {
        // 服务器 state 值是「数组 JSON 字符串」（最新在前）
        String arrText = "[{\"ts\":1,\"ev\":\"launched\",\"note\":\"ok\"}]";
        JSONArray arr = FnParams.parseGuardianLedgerText(arrText);
        assertEquals(1, arr.length());
        assertEquals("launched", arr.getJSONObject(0).getString("ev"));
        // 兼容 {"ledger":[...]} 包装
        JSONArray wrapped = FnParams.parseGuardianLedgerText(
                "{\"ledger\":[" + arrText.substring(1, arrText.length() - 1) + "]}");
        assertEquals(1, wrapped.length());
        // 空/非法 → 空数组（UI 不崩）
        assertEquals(0, FnParams.parseGuardianLedgerText(null).length());
        assertEquals(0, FnParams.parseGuardianLedgerText("  ").length());
        assertEquals(0, FnParams.parseGuardianLedgerText("{bad").length());
        assertEquals(0, FnParams.parseGuardianLedgerText("{\"x\":1}").length());
        // 解析后可进统计
        FnParams.GuardianLedger l = FnParams.guardianLedger(arr);
        assertEquals(1, l.launched);
        assertEquals("ok", l.lastNote);
    }

    @Test
    public void guardianStateKeys_matchServer() {
        assertEquals("mod:48460403:888:guardian",
                FnParams.guardianModKey("48460403", 888));
        assertEquals("srv:guardian:48460403:888:ledger",
                FnParams.guardianLedgerKey("48460403", 888));
        assertEquals(FnParams.FN_GUARDIAN, "guardian");
        assertEquals(38, FnParams.GUARDIAN_DEFAULT_D_SEC);
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
