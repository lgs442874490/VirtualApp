package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.ModularRoundRunner;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T1/T2 集成回归：稀疏 roleConfig（真实账号 k888 只配置 5 项）回放模块化整轮。
 *
 * <p>回归背景（G1）：目录默认 false 的域动作在 roleConfig 未出现时不得执行；
 * 稀疏配置必须回退到任务目录默认值（旧 RoundRunner 语义）。
 * 同时覆盖 T2：单任务路径只跑目标动作、不写 srv:lastrun。
 */
public class ModularRoundRunnerSparseConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private VaultStore.Account acc;
    private CaptureTasksTest.FakeEngine engine;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        engine = new CaptureTasksTest.FakeEngine();
        acc = new VaultStore.Account("111");
        acc.token = "tk";
        // 与真机 48460403/k888 的稀疏配置一致：只勾选 5 项
        Map<String, Boolean> sparse = new LinkedHashMap<>();
        sparse.put("barracks", true);
        sparse.put("adventure_sweep", true);
        sparse.put("arena", true);
        sparse.put("gather", true);
        sparse.put("intel", true);
        // 2026-11-11 任务级默认全开：本用例改为验证「显式关闭」仍被门控
        sparse.put("bank", false);
        sparse.put("shop", false);
        sparse.put("alliance_shop", false);
        sparse.put("hunt", false);
        acc.roleConfig.put("913", sparse);
    }

    private String firstReason(String fnId) {
        String raw = store.getString("log:111:913:" + fnId);
        assertNotNull("缺少日志键: " + fnId, raw);
        JSONArray arr = new JSONArray(raw);
        assertTrue("日志为空: " + fnId, arr.length() > 0);
        return arr.getJSONObject(0).getString("reason");
    }

    @Test
    public void sparseConfigDoesNotRunCatalogDefaultOffActions() {
        ModularRoundRunner.runRound(engine, acc, 913L, store, null, null, "定时");

        // 目录默认 false 且 roleConfig 未出现的动作：必须“配置未启用”，不得执行
        for (String id : new String[]{"bank", "shop", "alliance_shop", "hunt"}) {
            assertEquals("目录默认关闭动作不得被模块默认打开: " + id,
                    "配置未启用", firstReason(id));
        }
        // 显式开启的动作必须进入执行（reason 不应是“配置未启用”）
        assertNotEquals("显式开启的 gather 应执行", "配置未启用", firstReason("gather"));
        assertNotEquals("显式开启的 intel 应执行", "配置未启用", firstReason("intel"));

        String summary = store.getString("summary:111");
        assertNotNull(summary);
        assertTrue("整轮 summary 应包含被门控动作", summary.contains("配置未启用"));
        Object lastRun = store.snapshot().get("srv:lastrun:111");
        assertTrue("正常整轮应写 srv:lastrun", lastRun instanceof Number);
    }

    @Test
    public void singleTaskOnlyRunsRequestedActionAndDoesNotConsumeRound() {
        ModularRoundRunner.runRound(engine, acc, 913L, store, null, "bank", "手动");

        assertEquals("单任务应只跑目标动作（配置未启用）", "配置未启用", firstReason("bank"));
        assertNull("单任务不得触发其它动作", store.getString("log:111:913:gather"));
        assertNull("单任务不得消耗当天整轮", store.snapshot().get("srv:lastrun:111"));
    }

    @Test
    public void unregisteredLegacyActionRunsInsteadOfPendingMigration() {
        // 2026-11-11：homeland 未迁入模块化目录；配置开启时必须回退 legacy 执行器，
        // 不得只记“待迁移到模块化目录”（线上曾导致晨曦岛修复不生效）。
        acc.roleConfig.get("913").put("homeland", true);
        ModularRoundRunner.runRound(engine, acc, 913L, store, null, null, "定时");

        String raw = store.getString("log:111:913:homeland");
        assertNotNull("未收录动作应回退执行并写日志", raw);
        JSONArray arr = new JSONArray(raw);
        assertTrue("日志非空", arr.length() > 0);
        org.junit.Assert.assertFalse("不得只记待迁移",
                arr.getJSONObject(0).getString("reason").contains("待迁移到模块化目录"));
    }
}
