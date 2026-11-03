package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FormationQuickSetup 单测（网页 §1.12 / 契约 §0）：一键编队 5 步规划、单队列条件、未同步降级、
 * 结果文案（已设置/已跳过/失败）与模块中文映射。
 */
public class FormationQuickSetupTest {

    private static List<String> resources(int n) {
        String[] all = {"MEAT", "WOOD", "COAL", "IRON", "MEAT", "WOOD"};
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n && i < all.length; i++) out.add(all[i]);
        return out;
    }

    private static FormationQuickSetup.Step step(List<FormationQuickSetup.Step> steps, String module) {
        for (FormationQuickSetup.Step s : steps) {
            if (module.equals(s.module)) return s;
        }
        return null;
    }

    @Test
    public void plan_readyMultiQueue() {
        List<FormationQuickSetup.Step> steps = FormationQuickSetup.plan(3, resources(3), true);
        assertEquals("固定 5 步", 5, steps.size());
        assertEquals("applied", step(steps, "gather").status);
        assertTrue(step(steps, "gather").message.contains("3 个采集队列"));
        assertEquals("巨兽只设预设队，不再受队列数限制", "applied",
                step(steps, "behemoth").status);
        assertTrue(step(steps, "behemoth").message.contains("巨兽预设"));
        assertEquals("applied", step(steps, "intel").status);
        assertEquals("多队列时狩猎跳过", "skipped", step(steps, "hunt").status);
        // App 当前无「英雄的使命」模块（FnRegistry 无该功能）→ 明确 skipped，不谎报已设置
        assertEquals("skipped", step(steps, "hero_mission").status);
        assertTrue(step(steps, "hero_mission").message.contains("暂无"));
    }

    @Test
    public void plan_readySingleQueue() {
        List<FormationQuickSetup.Step> steps = FormationQuickSetup.plan(1, resources(6), true);
        assertEquals("采集 min(4,队伍数=1) = 1", "applied", step(steps, "gather").status);
        assertTrue(step(steps, "gather").message.contains("1 个采集队列"));
        assertEquals("单队列巨兽应用", "applied", step(steps, "behemoth").status);
        assertEquals("单队列狩猎应用", "applied", step(steps, "hunt").status);
        // gather 上限 4
        assertTrue(FormationQuickSetup.plan(5, resources(6), true).get(0).message
                .contains("4 个采集队列"));
    }

    @Test
    public void plan_notSyncedAllFailed() {
        List<FormationQuickSetup.Step> steps = FormationQuickSetup.plan(3, resources(3), false);
        assertEquals(5, steps.size());
        for (FormationQuickSetup.Step s : steps) {
            assertEquals("未同步 → 全 failed", "failed", s.status);
            assertEquals(FormationQuickSetup.MSG_NOT_SYNCED, s.message);
        }
    }

    @Test
    public void plan_noGatherQueuesSkipped() {
        List<FormationQuickSetup.Step> steps = FormationQuickSetup.plan(3,
                new ArrayList<String>(), true);
        assertEquals("skipped", step(steps, "gather").status);
        assertEquals(FormationQuickSetup.MSG_NO_GATHER_QUEUE,
                step(steps, "gather").message);
        assertEquals("其它步骤不受影响", "applied", step(steps, "intel").status);
        // null 资源列表同样安全
        assertEquals("skipped", FormationQuickSetup.plan(3, null, true).get(0).status);
    }

    @Test
    public void stepLine_webCopy() {
        assertEquals("已设置 · 采集：ok", new FormationQuickSetup.Step(
                FormationQuickSetup.MODULE_GATHER,
                FormationQuickSetup.STATUS_APPLIED, "ok").line());
        assertEquals("已跳过 · 每日巨兽：x", new FormationQuickSetup.Step(
                FormationQuickSetup.MODULE_BEHEMOTH,
                FormationQuickSetup.STATUS_SKIPPED, "x").line());
        assertEquals("失败 · 情报：boom", new FormationQuickSetup.Step(
                FormationQuickSetup.MODULE_INTEL,
                FormationQuickSetup.STATUS_FAILED, "boom").line());
        assertEquals("每日巨兽", new FormationQuickSetup.Step(
                FormationQuickSetup.MODULE_BEHEMOTH, "x", "").label());
    }

    @Test
    public void moduleLabels() {
        assertEquals("采集", FormationQuickSetup.moduleLabel("gather"));
        assertEquals("每日巨兽", FormationQuickSetup.moduleLabel("behemoth"));
        assertEquals("情报", FormationQuickSetup.moduleLabel("intel"));
        assertEquals("自动狩猎", FormationQuickSetup.moduleLabel("hunt"));
        assertEquals("英雄的使命", FormationQuickSetup.moduleLabel("hero_mission"));
        assertEquals("未知模块", FormationQuickSetup.moduleLabel("nope"));
        assertEquals("未知模块", FormationQuickSetup.moduleLabel(null));
        assertEquals("applied", FormationQuickSetup.STATUS_APPLIED);
        assertEquals("skipped", FormationQuickSetup.STATUS_SKIPPED);
        assertEquals("failed", FormationQuickSetup.STATUS_FAILED);
    }

    @Test
    public void plan_queueLimitClamped() {
        // 0/负数 clamp 到 1 → 单队列步骤应用
        assertTrue(FormationQuickSetup.plan(0, resources(2), true).get(0).message
                .contains("1 个采集队列"));
        assertTrue(FormationQuickSetup.plan(-5, resources(2), true).get(0).message
                .contains("1 个采集队列"));
        // 超过 5 clamp 到 5，但仍受「采集最多 4」限制
        List<FormationQuickSetup.Step> steps = FormationQuickSetup.plan(99, resources(6), true);
        assertTrue(steps.get(0).message.contains("4 个采集队列"));
        assertEquals("巨兽只设预设队，不受队列数限制", "applied",
                step(steps, "behemoth").status);
        assertEquals("狩猎多队列仍跳过", "skipped", step(steps, "hunt").status);
    }

    @Test
    public void plan_isPureAndRepeatable() {
        List<FormationQuickSetup.Step> a = FormationQuickSetup.plan(3, resources(3), true);
        List<FormationQuickSetup.Step> b = FormationQuickSetup.plan(3, resources(3), true);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).module, b.get(i).module);
            assertEquals(a.get(i).status, b.get(i).status);
            assertEquals(a.get(i).message, b.get(i).message);
        }
        assertFalse("plan 不依赖时间/随机", a.get(0).message.isEmpty());
        // 资源列表不被修改
        List<String> res = Arrays.asList("MEAT", "WOOD");
        FormationQuickSetup.plan(2, res, true);
        assertEquals(2, res.size());
    }
}
