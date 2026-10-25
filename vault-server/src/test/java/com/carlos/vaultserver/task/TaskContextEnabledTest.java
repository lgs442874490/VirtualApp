package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-25 P0 修复回归：TaskContext 的「本角色任务是否启用」判定。
 *
 * <p>背景（真机监控实证）：账号 roleConfig 只显式启用 gather，intel 目录默认 false 从未执行，
 * 但采集让位条件只数资产库情报待办、不判断 intel 是否启用 → 采集 58/58 次
 * 「情报待办优先，采集让位」、全天 0 队派出。修复后 gather 用本判定决定是否让位。
 */
public class TaskContextEnabledTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskContext ctx(Map<String, Boolean> roleConfig) {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "节奏",
                TaskContext.enabledPredicate(roleConfig));
    }

    @Test
    public void explicitConfigWinsAndMissingFallsBackToCatalogDefault() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("gather", true);
        TaskContext tc = ctx(cfg);
        assertTrue("显式启用 gather", tc.taskEnabled("gather"));
        assertFalse("intel 目录默认 false 且未显式启用 → 未启用（修复前采集被永久让位的根因）",
                tc.taskEnabled("intel"));
        assertTrue("supply 目录默认 true → 启用", tc.taskEnabled("supply"));
        assertFalse("未知任务 id 保守视为未启用", tc.taskEnabled("no_such_task"));
        assertFalse("空 id 未启用", tc.taskEnabled(""));
    }

    @Test
    public void explicitDisableOverridesDefaultTrue() {
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("supply", false);
        TaskContext tc = ctx(cfg);
        assertFalse("显式 false 覆盖目录默认 true", tc.taskEnabled("supply"));
        assertTrue("intel 未显式配置 → 仍按目录默认 false", !tc.taskEnabled("intel"));
    }

    @Test
    public void nullConfigMeansCatalogDefaults() {
        TaskContext tc = ctx(null);
        assertFalse(tc.taskEnabled("intel"));
        assertFalse(tc.taskEnabled("gather"));
        assertTrue(tc.taskEnabled("supply"));
    }

    @Test
    public void missingPredicateKeepsLegacyBehavior() {
        // 旧构造（反应式/测试路径）不传判定 → 保守返回 true，行为不变
        TaskContext legacy = new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "反应式");
        assertTrue(legacy.taskEnabled("intel"));
        assertTrue(legacy.taskEnabled("no_such_task"));
    }
}
