package com.carlos.vault.daily;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * TC-C01 / TC-D05：功能注册表与默认开关矩阵（35 默认开 / 21 默认关）。
 * 开关缺省回落语义（runTasks: null→enabledByDefault）依赖此矩阵，UI 三态显示同样依赖。
 * 计数变更史：50 → 53（+成就奖励/雪原贸易站/守护盟友）→ 56 → 57（+战争状态，P7），
 * 新增功能必须同步本类断言。
 */
public class RegistryAndDefaultsTest {

    @Test
    public void fnRegistry_allItems_uniqueAndImplemented() {
        List<FnRegistry.Fn> all = FnRegistry.all();
        assertEquals(61, all.size());
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (FnRegistry.Fn f : all) {
            assertTrue("id 非空", f.id != null && f.id.length() > 0);
            assertTrue("v3.x 全量开放，不应再有 implemented=false: " + f.id, f.implemented);
            assertTrue("desc 非空: " + f.id, f.desc != null && f.desc.length() > 0);
            ids.add(f.id);
            names.add(f.name);
        }
        assertEquals("fn id 不得重复", 61, ids.size());
        assertEquals("fn 名称不得重复", 61, names.size());
    }

    @Test
    public void fnRegistry_categoryCounts() {
        int[] expected = {10, 11, 19, 7, 8, 2, 4}; // 奖励/城镇/活动/野外/联盟/商店/其他
        assertEquals(7, FnRegistry.CAT_NAMES.length);
        for (int cat = 0; cat < 7; cat++) {
            assertEquals("分类 " + FnRegistry.CAT_NAMES[cat] + " 数量",
                    expected[cat], FnRegistry.byCategory(cat).size());
        }
    }

    @Test
    public void fnRegistry_byId() {
        assertNotNull(FnRegistry.byId("login_rewards"));
        assertEquals("登录好礼", FnRegistry.byId("login_rewards").name);
        assertNull(FnRegistry.byId("no_such_fn"));
    }

    @Test
    public void dailyTasks_registryMatchesFnRegistry() {
        List<DailyTasks.TaskDef> defs = DailyTasks.all();
        assertEquals(61, defs.size());
        Set<String> fnIds = new HashSet<>();
        for (FnRegistry.Fn f : FnRegistry.all()) fnIds.add(f.id);
        Set<String> defIds = new HashSet<>();
        for (DailyTasks.TaskDef d : defs) {
            assertTrue("TaskDef id 必须在 FnRegistry 中: " + d.id, fnIds.contains(d.id));
            defIds.add(d.id);
            // 2026-11-06 决策5：本地执行器已删除，TaskDef 仅作为任务目录（id/名称/默认开关）
        }
        assertEquals("两侧集合全等", fnIds, defIds);
    }

    /**
     * 整改 A2 回归：TaskDef 名称必须与 FnRegistry（用户可见名）逐项一致。
     * 历史缺陷：act_joy/battle_shield/alliance_donate/alliance_showdown 四项两处注册名不同，
     * 导致执行结果弹窗/日志的功能名与日常页卡片名对不上。
     */
    @Test
    public void taskNames_matchFnRegistry() {
        java.util.Map<String, String> fnNames = new java.util.HashMap<>();
        for (FnRegistry.Fn f : FnRegistry.all()) fnNames.put(f.id, f.name);
        for (DailyTasks.TaskDef d : DailyTasks.all()) {
            assertEquals("TaskDef 名称必须与 FnRegistry 一致: " + d.id,
                    fnNames.get(d.id), d.name);
        }
    }

    @Test
    public void defaultOffMatrix_exactly21() {
        // 2026-11-11 默认开启策略：任务级全部默认开，默认关集合为空。
        // 需要关闭的能力改由「消耗钻石/道具的子项默认关」与 FeatureGate 隐藏过滤承接。
        Set<String> expectedOff = new HashSet<>();

        Set<String> actualOff = new HashSet<>();
        for (DailyTasks.TaskDef d : DailyTasks.all()) {
            if (!d.enabledByDefault) actualOff.add(d.id);
        }
        assertEquals("默认关清单必须为空（2026-11-11 起任务级全开）", expectedOff, actualOff);
    }

    /**
     * 防漂移：FnRegistry 中每个功能都必须有 FnDetail 子项目录（≥1 个子项）。
     * 历史上新增功能（守护盟友/成就奖励/雪原贸易站）只改了 FnRegistry+DailyTasks，
     * 测试计数与 FnDetail 容易漏同步——本用例把三处锁死。
     */
    @Test
    public void fnDetail_everyRegistryFnHasSubs() {
        // 2026-11-04：守护盟友的配置已全部收进「守护盟友 · 配置」卡（params.guardian），
        // 旧版 FnDetail 执行参数（watch/intercept/hold/pre_garrison/标定档）废弃，
        // 因此它是唯一允许没有子项目录的功能。
        for (FnRegistry.Fn f : FnRegistry.all()) {
            if ("guardian".equals(f.id)) continue;
            assertFalse("FnDetail 缺少功能 " + f.id + " 的子项目录",
                    FnDetail.of(f.id).isEmpty());
        }
    }

    @Test
    public void dailyQuestIds_matchOriginalCatalog() throws Exception {
        // 原版 DailyMissionCatalog 全量任务 ID：1320000 + 1320010..1320083 段（21 个）
        Field f = DailyTasks.class.getDeclaredField("DAILY_QUEST_IDS");
        f.setAccessible(true);
        int[] ids = (int[]) f.get(null);
        assertEquals(21, ids.length);
        assertEquals(1320000, ids[0]);
        for (int id : ids) {
            assertTrue(id >= 1320000 && id <= 1320083);
        }
        Set<Integer> set = new HashSet<>();
        for (int id : ids) set.add(id);
        assertEquals(21, set.size());
    }

    @Test
    public void boxThresholds_matchOriginalActivityTiers() {
        // 原版活跃度宝箱档位：40/80/120/160/215/270/325
        int[] ts = DailyConst.BOX_THRESHOLDS;
        assertEquals(7, ts.length);
        assertEquals(40, ts[0]);
        assertEquals(80, ts[1]);
        assertEquals(120, ts[2]);
        assertEquals(160, ts[3]);
        assertEquals(215, ts[4]);
        assertEquals(270, ts[5]);
        assertEquals(325, ts[6]);
    }
}
