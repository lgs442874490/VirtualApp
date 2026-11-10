package com.carlos.vault.daily;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 功能型 bug 回归（App 侧纯函数）：配置“稀疏 map + 默认补齐”与医院批量治疗参数契约。
 *
 * <p>背景（用户反馈 1/2/6 “设置了也无效/从来不执行”）：服务器模式是否执行功能完全由
 * App 全量同步的 roles[].config 决定；旧实现把稀疏/旧配置原样上传（例如只有
 * {@code {"gather":true}}），服务端读不到 barracks/intel/arena 键 → 判“配置未启用”。
 * 修复后 App 显示与上传统一走 {@link DailyConfigStore#fillMissing}/{@link DailyConfigStore#effectiveOn}
 * 与 {@link DailyConfigStore#effectiveRoleConfig}，缺省值 = {@code DailyTasks.TaskDef.enabledByDefault}。
 *
 * <p>本测试不依赖 Android Context（只测纯函数），可在普通 JVM 单测里跑。
 */
public class DailyConfigStoreEffectiveConfigTest {

    private static final List<DailyTasks.TaskDef> DEFS = DailyTasks.all();

    // ==================== 稀疏配置 + 默认补齐 ====================

    /** null/空配置 → 补齐成与任务目录等长的完整 map，逐项等于默认值。 */
    @Test
    public void fillMissing_nullBecomesFullDefaults() {
        Map<String, Boolean> filled = DailyConfigStore.fillMissing(null, DEFS);
        assertEquals("必须补齐全部任务", DEFS.size(), filled.size());
        for (DailyTasks.TaskDef d : DEFS) {
            assertEquals("任务 " + d.id + " 缺省值应为 enabledByDefault",
                    Boolean.valueOf(d.enabledByDefault), filled.get(d.id));
        }

        Map<String, Boolean> empty = DailyConfigStore.fillMissing(
                new LinkedHashMap<String, Boolean>(), DEFS);
        assertEquals(DEFS.size(), empty.size());
    }

    /**
     * 生产实证的稀疏配置 {@code {"gather":true}}：补齐后 gather 保持显式 true，
     * barracks/intel/arena 回落默认 false（默认关闭项不得被默认打开）。
     */
    @Test
    public void fillMissing_sparseKeepsExplicitAndFillsRest() {
        Map<String, Boolean> sparse = new LinkedHashMap<>();
        sparse.put("gather", true);
        Map<String, Boolean> filled = DailyConfigStore.fillMissing(sparse, DEFS);

        assertEquals("补齐后必须与服务端契约一致的全量项数", DEFS.size(), filled.size());
        assertEquals("显式配置保留", Boolean.TRUE, filled.get("gather"));
        assertEquals("练兵 2026-11-11 起默认开", Boolean.TRUE, filled.get("barracks"));
        assertEquals("情报 2026-11-11 起默认开", Boolean.TRUE, filled.get("intel"));
        assertEquals("竞技场 2026-11-11 起默认开", Boolean.TRUE, filled.get("arena"));
        assertEquals("军医所默认开启", Boolean.TRUE, filled.get("medic"));
        assertEquals("登录好礼默认开启", Boolean.TRUE, filled.get("login_rewards"));

        assertFalse("不能修改入参", sparse.containsKey("barracks"));
        assertEquals(1, sparse.size());
    }

    /** 显式 false 必须保留（不能因为默认 true 被“补齐”成 true）。 */
    @Test
    public void fillMissing_explicitFalseWinsOverDefaultTrue() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("login_rewards", false);
        Map<String, Boolean> filled = DailyConfigStore.fillMissing(cfg, DEFS);
        assertEquals(Boolean.FALSE, filled.get("login_rewards"));
    }

    /** 幂等：对已完整的配置再补齐不改变任何值（VaultSync 每次同步都调用，不能抖动）。 */
    @Test
    public void fillMissing_isIdempotent() {
        Map<String, Boolean> once = DailyConfigStore.fillMissing(null, DEFS);
        Map<String, Boolean> twice = DailyConfigStore.fillMissing(once, DEFS);
        assertEquals(once, twice);
        assertEquals(DEFS.size(), twice.size());
    }

    /** 历史/未知键非破坏性保留（不清理，避免误删未来版本或用户自定义项）。 */
    @Test
    public void fillMissing_keepsUnknownKeys() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("legacy_unknown_task", true);
        Map<String, Boolean> filled = DailyConfigStore.fillMissing(cfg, DEFS);
        assertEquals(Boolean.TRUE, filled.get("legacy_unknown_task"));
        assertEquals(DEFS.size() + 1, filled.size());
    }

    // ==================== 开关显示口径（与 HomeActivity.fnOn 一致） ====================

    /** 未配置时：显示口径 = implemented && enabledByDefault。 */
    @Test
    public void effectiveOn_unconfiguredFallsBackToDefault() {
        FnRegistry.Fn barracks = fn("barracks");
        FnRegistry.Fn intel = fn("intel");
        FnRegistry.Fn arena = fn("arena");
        FnRegistry.Fn login = fn("login_rewards");
        Map<String, Boolean> empty = new LinkedHashMap<>();

        assertTrue("练兵 2026-11-11 起默认开",
                DailyConfigStore.effectiveOn(empty, barracks));
        assertTrue("情报 2026-11-11 起默认开", DailyConfigStore.effectiveOn(empty, intel));
        assertTrue("竞技场 2026-11-11 起默认开", DailyConfigStore.effectiveOn(empty, arena));
        assertTrue("登录好礼默认开启", DailyConfigStore.effectiveOn(empty, login));
        // 稀疏配置缺键时同样回落默认，而不是“缺键=开”
        Map<String, Boolean> sparse = new LinkedHashMap<>();
        sparse.put("gather", true);
        assertTrue("稀疏配置缺键同样回落新默认开", DailyConfigStore.effectiveOn(sparse, barracks));
    }

    /** 显式配置优先于默认；用户打开后必须显示开并上传 true。 */
    @Test
    public void effectiveOn_explicitValueWins() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("barracks", true);
        cfg.put("login_rewards", false);
        assertTrue(DailyConfigStore.effectiveOn(cfg, fn("barracks")));
        assertFalse(DailyConfigStore.effectiveOn(cfg, fn("login_rewards")));
    }

    /** defaultOn 必须与 DailyTasks 目录同源（未知 id → false）。 */
    @Test
    public void defaultOn_matchesTaskCatalog() {
        for (DailyTasks.TaskDef d : DEFS) {
            assertEquals("默认值不一致: " + d.id, d.enabledByDefault,
                    DailyConfigStore.defaultOn(d.id));
        }
        assertFalse(DailyConfigStore.defaultOn("no_such_task"));
        assertFalse(DailyConfigStore.defaultOn(null));
    }

    /** 服务器模式上传口径：补齐后的完整配置包含全部任务键（VaultSync.syncFields 依赖）。 */
    @Test
    public void filledConfig_coversEveryUploadedKey() {
        Map<String, Boolean> filled = DailyConfigStore.fillMissing(null, DEFS);
        for (DailyTasks.TaskDef d : DEFS) {
            assertTrue("上传配置缺键会导致服务端判未启用: " + d.id,
                    filled.containsKey(d.id));
        }
    }

    // ==================== bug 3：医院批量治疗参数契约 ====================

    /** 每批治疗数量：默认 400、范围 1-10000、非法回退 400（与服务端 MedicService 同契约）。 */
    @Test
    public void medicHealCount_defaultRangeAndFallback() {
        assertEquals(400, FnParams.MEDIC_HEAL_COUNT_DEFAULT);
        assertEquals(1, FnParams.MEDIC_HEAL_COUNT_MIN);
        assertEquals(10000, FnParams.MEDIC_HEAL_COUNT_MAX);

        assertEquals(400, FnParams.normalizeMedicHealCount(0));
        assertEquals(400, FnParams.normalizeMedicHealCount(-5));
        assertEquals(400, FnParams.normalizeMedicHealCount(10001));
        assertEquals(1, FnParams.normalizeMedicHealCount(1));
        assertEquals(500, FnParams.normalizeMedicHealCount(500));
        assertEquals(10000, FnParams.normalizeMedicHealCount(10000));

        assertEquals("空输入回退默认", 400, FnParams.parseMedicHealCount(null));
        assertEquals(400, FnParams.parseMedicHealCount(""));
        assertEquals(400, FnParams.parseMedicHealCount("  "));
        assertEquals(400, FnParams.parseMedicHealCount("abc"));
        assertEquals(400, FnParams.parseMedicHealCount("20000"));
        assertEquals(500, FnParams.parseMedicHealCount("500"));
        assertEquals(500, FnParams.parseMedicHealCount(" 500 "));
    }

    /** 读 medic 参数顶层键 heal_count / request_help（服务端 MedicService 按同一键读取，勿改名）。 */
    @Test
    public void medicParams_readTopLevelContract() throws Exception {
        JSONObject p = new JSONObject();
        p.put("heal_count", 600);
        assertEquals(600, FnParams.medicHealCount(p));
        assertTrue("request_help 缺省 true（治疗后自动联盟求助）", FnParams.medicRequestHelp(p));

        p.put("request_help", false);
        assertFalse(FnParams.medicRequestHelp(p));

        p.put("heal_count", 0);
        assertEquals("0 非法（服务端 0=全部，但 UI 必须显式 1-10000）",
                400, FnParams.medicHealCount(p));
        assertEquals("缺省 params → 默认 400/求助开",
                400, FnParams.medicHealCount((JSONObject) null));
        assertTrue(FnParams.medicRequestHelp(null));
    }

    // ==================== helpers ====================

    private static FnRegistry.Fn fn(String id) {
        for (FnRegistry.Fn f : FnRegistry.all()) {
            if (f.id.equals(id)) return f;
        }
        throw new AssertionError("FnRegistry 缺少功能: " + id);
    }
}
