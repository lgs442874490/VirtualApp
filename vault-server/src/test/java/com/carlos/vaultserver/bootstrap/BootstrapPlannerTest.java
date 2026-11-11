package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Cost;
import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Prerequisite;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Action;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.ActionKind;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Catalog;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.HoldPolicy;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Plan;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Request;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.ResourceAvailability;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapPlanner} 单测（M4a 任务书 §2.5）：黄金场景（真实组合目录：
 * 空城→熔炉 2 级含前置、熔炉 2→3 级含家具补齐）、complete/busy 阻塞文案、
 * fake 目录注入的环形依赖与深度嵌套、maxActions 截断、资源不足文案。
 */
public class BootstrapPlannerTest {

    /** 51 hold 时长（与 BootstrapService.ACTION_HOLD_MILLIS 一致；此处固定 30 分钟文案 1800 秒）。 */
    private static final long ACTION_HOLD_MILLIS = 1_800_000L;

    private final BootstrapPlanner planner = new BootstrapPlanner();

    // ================= 黄金场景（真实目录） =================

    /** 空城 → 熔炉 2 级：[熔炉 0→1, 伐木场 0→1（前置）, 熔炉 1→2]。 */
    @Test
    public void golden_emptyTownToFurnace2() {
        Plan plan = planner.plan(new Request(Map.of(), Map.of(), Map.of(301L, 2L), Set.of()));
        assertEquals(3, plan.actions().size());

        Action a0 = plan.actions().get(0);
        assertEquals(ActionKind.UPGRADE_BUILDING, a0.kind());
        assertEquals(301L, a0.buildingId());
        assertEquals(0L, a0.fromLevel());
        assertEquals(1L, a0.toLevel());
        assertTrue(a0.costs().isEmpty());
        assertEquals("建造 大熔炉 到 1 级", a0.label());
        assertTrue(a0.usesQueue());

        Action a1 = plan.actions().get(1);
        assertEquals(302L, a1.buildingId());
        assertEquals("伐木场", a1.buildingName());
        assertEquals(List.of(new Cost(103L, 30L)), a1.costs());
        assertEquals("建造 伐木场 到 1 级", a1.label());

        Action a2 = plan.actions().get(2);
        assertEquals(301L, a2.buildingId());
        assertEquals(1L, a2.fromLevel());
        assertEquals(2L, a2.toLevel());
        assertEquals(List.of(new Cost(103L, 180L)), a2.costs());
        assertEquals("升级 大熔炉 到 2 级", a2.label());

        assertTrue(plan.complete());
        assertEquals("", plan.blockedReason());
    }

    /** 熔炉 2 → 3 级：前置民居1(308) 2 级 + 家具 101/103 各补到 2 级（家具补齐）。 */
    @Test
    public void golden_furnace2To3_withFurnitureTopUp() {
        Plan plan = planner.plan(new Request(Map.of(301L, 2L), Map.of(),
                Map.of(301L, 3L), Set.of()));
        assertEquals(7, plan.actions().size());

        assertEquals(308L, plan.actions().get(0).buildingId());
        assertEquals(0L, plan.actions().get(0).fromLevel());
        assertEquals(1L, plan.actions().get(0).toLevel());

        Action create101 = plan.actions().get(1);
        assertEquals(ActionKind.CREATE_FURNITURE, create101.kind());
        assertEquals(308L, create101.buildingId());
        assertEquals(101L, create101.furnitureId());
        assertEquals(0L, create101.fromLevel());
        assertEquals(1L, create101.toLevel());
        assertEquals(List.of(new Cost(103L, 10L)), create101.costs());
        assertEquals("民居1 家具 101 建造", create101.label());
        assertFalse(create101.usesQueue());

        Action upgrade101 = plan.actions().get(2);
        assertEquals(ActionKind.UPGRADE_FURNITURE, upgrade101.kind());
        assertEquals(1L, upgrade101.fromLevel());
        assertEquals(2L, upgrade101.toLevel());
        assertEquals(List.of(new Cost(103L, 15L)), upgrade101.costs());
        assertEquals("民居1 家具 101 升级到 2 级", upgrade101.label());

        Action create103 = plan.actions().get(3);
        assertEquals(ActionKind.CREATE_FURNITURE, create103.kind());
        assertEquals(103L, create103.furnitureId());

        Action upgrade103 = plan.actions().get(4);
        assertEquals(ActionKind.UPGRADE_FURNITURE, upgrade103.kind());
        assertEquals(103L, upgrade103.furnitureId());

        Action finish308 = plan.actions().get(5);
        assertEquals(308L, finish308.buildingId());
        assertEquals(1L, finish308.fromLevel());
        assertEquals(2L, finish308.toLevel());
        assertEquals(List.of(new Cost(103L, 35L)), finish308.costs());

        Action finish301 = plan.actions().get(6);
        assertEquals(301L, finish301.buildingId());
        assertEquals(2L, finish301.fromLevel());
        assertEquals(3L, finish301.toLevel());
        assertEquals(List.of(new Cost(103L, 805L)), finish301.costs());

        assertTrue(plan.complete());
    }

    /** 伐木场 1 → 2 级：家具 401 补到 2 级（创建成本 102:15 + 升级成本 102:25）。 */
    @Test
    public void sawmill2_needsFurniture401() {
        Plan plan = planner.plan(new Request(Map.of(301L, 2L, 302L, 1L), Map.of(),
                Map.of(302L, 2L), Set.of()));
        assertEquals(3, plan.actions().size());
        assertEquals(ActionKind.CREATE_FURNITURE, plan.actions().get(0).kind());
        assertEquals(401L, plan.actions().get(0).furnitureId());
        assertEquals(List.of(new Cost(102L, 15L)), plan.actions().get(0).costs());
        assertEquals(ActionKind.UPGRADE_FURNITURE, plan.actions().get(1).kind());
        assertEquals(401L, plan.actions().get(1).furnitureId());
        assertEquals(List.of(new Cost(102L, 25L)), plan.actions().get(1).costs());
        assertEquals(ActionKind.UPGRADE_BUILDING, plan.actions().get(2).kind());
        assertEquals(302L, plan.actions().get(2).buildingId());
        assertEquals("升级 伐木场 到 2 级", plan.actions().get(2).label());
        assertTrue(plan.complete());
    }

    /** 已有家具 401:1：只补升级 1→2（创建动作省略）。 */
    @Test
    public void sawmill2_existingFurniture1_skipsCreate() {
        Plan plan = planner.plan(new Request(Map.of(301L, 2L, 302L, 1L),
                Map.of(302L, Map.of(401L, 1L)), Map.of(302L, 2L), Set.of()));
        assertEquals(2, plan.actions().size());
        assertEquals(ActionKind.UPGRADE_FURNITURE, plan.actions().get(0).kind());
        assertEquals(1L, plan.actions().get(0).fromLevel());
        assertEquals(2L, plan.actions().get(0).toLevel());
    }

    // ================= complete / busy =================

    @Test
    public void furnaceAlreadyAtTarget_completeWithoutActions() {
        Plan plan = planner.plan(new Request(Map.of(301L, 9L), Map.of(),
                Map.of(301L, 9L), Set.of()));
        assertTrue(plan.actions().isEmpty());
        assertTrue(plan.complete());
        assertEquals("", plan.blockedReason());
    }

    @Test
    public void busyBuilding_blocksWithMessage() {
        Plan plan = planner.plan(new Request(Map.of(301L, 1L), Map.of(),
                Map.of(301L, 2L), Set.of(301L)));
        assertTrue(plan.actions().isEmpty());
        assertFalse(plan.complete());
        assertEquals("大熔炉 正在建造或升级，等待完成后再继续", plan.blockedReason());
    }

    // ================= fake 目录：环形依赖 / 深度嵌套 / 经验门槛文案 =================

    /** 可编程 fake 目录（只承载环形/深链/经验场景所需的最小数据）。 */
    private static final class FakeCatalog implements Catalog {
        final Map<Long, List<Prerequisite>> prereqs = new LinkedHashMap<>();
        final Map<Long, Long> needExp = new LinkedHashMap<>();
        final Map<Long, String> names = new LinkedHashMap<>();
        /** 参与 经验贡献 的家具 id。 */
        final java.util.Set<Long> contributes = new java.util.LinkedHashSet<>();
        /** 每级家具经验值。 */
        long expPerLevel;

        @Override
        public boolean supports(long buildingId) {
            return prereqs.containsKey(buildingId) || needExp.containsKey(buildingId);
        }

        @Override
        public long maxLevel(long buildingId) {
            return 80L;
        }

        @Override
        public String buildingName(long buildingId) {
            return names.getOrDefault(buildingId, "建筑 " + buildingId);
        }

        @Override
        public List<Prerequisite> prerequisites(long buildingId, long level) {
            return prereqs.getOrDefault(buildingId, List.of());
        }

        @Override
        public long needExp(long buildingId, long level) {
            return needExp.getOrDefault(buildingId, 0L);
        }

        @Override
        public List<Cost> furnitureRequirement(long buildingId, long level) {
            return List.of();
        }

        @Override
        public long furnitureExp(long furnitureId, long level) {
            return contributes.contains(furnitureId) ? expPerLevel * Math.max(0L, level) : 0L;
        }

        @Override
        public List<Cost> furnitureCosts(long furnitureId, long level) {
            return List.of();
        }

        @Override
        public long furnitureMaxLevel(long furnitureId) {
            return 40L;
        }

        @Override
        public boolean furnitureContributesExp(long furnitureId) {
            return contributes.contains(furnitureId);
        }

        @Override
        public List<Cost> buildingCosts(long buildingId, long level) {
            return List.of();
        }
    }

    /** 互为前置 → 环形依赖文案（报环上被重入的建筑，即目标主体）。 */
    @Test
    public void circularPrerequisites_blocksWithMessage() {
        FakeCatalog fake = new FakeCatalog();
        fake.prereqs.put(11L, List.of(new Prerequisite(12L, 1L)));
        fake.prereqs.put(12L, List.of(new Prerequisite(11L, 1L)));
        fake.names.put(11L, "甲楼");
        fake.names.put(12L, "乙楼");
        Plan plan = new BootstrapPlanner(fake)
                .plan(new Request(Map.of(), Map.of(), Map.of(11L, 1L), Set.of()));
        assertTrue(plan.actions().isEmpty());
        assertFalse(plan.complete());
        assertEquals("建筑 甲楼 的前置条件互相依赖，已停止自动升级", plan.blockedReason());
    }

    /** 前置链 45 层 → 深度截断文案（MAX_DEPTH=40）。 */
    @Test
    public void deepPrerequisiteChain_blocksWithDepthMessage() {
        FakeCatalog fake = new FakeCatalog();
        for (long id = 1L; id <= 45L; ++id) {
            fake.prereqs.put(id, List.of(new Prerequisite(id + 1L, 1L)));
            fake.names.put(id, "楼" + id);
        }
        Plan plan = new BootstrapPlanner(fake)
                .plan(new Request(Map.of(), Map.of(), Map.of(1L, 1L), Set.of()));
        assertTrue(plan.actions().isEmpty());
        assertEquals("建筑前置条件嵌套过深，请检查门槛配置", plan.blockedReason());
    }

    /** 家具经验不足文案（fake：needExp=40，家具 55 每级贡献 10）。 */
    @Test
    public void experienceBlocked_message_fromFakeCatalog() {
        FakeCatalog fake = new FakeCatalog();
        fake.needExp.put(21L, 40L);
        fake.names.put(21L, "靶场");
        fake.contributes.add(55L);
        fake.expPerLevel = 10L;
        Plan plan = new BootstrapPlanner(fake)
                .plan(new Request(Map.of(21L, 1L),
                        Map.of(21L, Map.of(55L, 1L)), Map.of(21L, 2L), Set.of()));
        assertTrue(plan.actions().isEmpty());
        assertEquals("靶场 的家具经验不足（10/40），等待家具升级数据同步", plan.blockedReason());
    }

    // ================= maxActions / 资源不足 =================

    @Test
    public void maxActions_truncatesPlan() {
        Plan plan = planner.plan(new Request(Map.of(), Map.of(), Map.of(301L, 2L), Set.of(),
                ResourceAvailability.ALWAYS, 2));
        assertEquals(2, plan.actions().size());
        assertFalse(plan.complete());
    }

    @Test
    public void maxActions_default8_zeroFallsBack() {
        // maxActions<=0 → 默认 8；≤300 语义在 Request 紧凑构造器
        Plan plan = planner.plan(new Request(Map.of(), Map.of(), Map.of(301L, 2L), Set.of(),
                ResourceAvailability.ALWAYS, 0));
        assertEquals(3, plan.actions().size());
        assertTrue(plan.complete());
    }

    @Test
    public void resourcesMissing_blocksWithMessage() {
        Plan plan = planner.plan(new Request(Map.of(301L, 1L), Map.of(), Map.of(301L, 2L), Set.of(),
                (itemId, amount) -> false, 8));
        assertTrue(plan.actions().isEmpty());
        assertFalse(plan.complete());
        assertEquals("伐木场 升级到 1 级所需资源不足，等待资源补充", plan.blockedReason());
    }

    /** 前置建筑不在目录（fake 只收录主体）→ 无法自动建造文案。 */
    @Test
    public void unsupportedPrerequisite_blocksWithMessage() {
        FakeCatalog fake = new FakeCatalog();
        fake.prereqs.put(31L, List.of(new Prerequisite(99L, 1L)));
        fake.names.put(31L, "主楼");
        Plan plan = new BootstrapPlanner(fake)
                .plan(new Request(Map.of(), Map.of(), Map.of(31L, 1L), Set.of()));
        assertTrue(plan.actions().isEmpty());
        assertEquals("前置建筑 99 不在门槛目录中，无法自动建造", plan.blockedReason());
    }

    /** 防御性：非法键（≤0）被忽略；null 目标集合兜底为空（Map.copyOf 拒绝 null 值，故不塞 null 值条目）。 */
    @Test
    public void request_normalization() {
        Map<Long, Long> dirty = new LinkedHashMap<>();
        dirty.put(301L, 1L);
        dirty.put(-5L, 3L);
        dirty.put(0L, 2L);
        Plan plan = planner.plan(new Request(dirty, null, null, null));
        assertTrue(plan.actions().isEmpty());
        assertTrue(plan.complete());
    }

    /** 便捷构造复用验证（4 参构造 → ALWAYS + 默认上限）。 */
    @Test
    public void actionLabel_kinds() {
        Action build = new Action(ActionKind.UPGRADE_BUILDING, 301L, "大熔炉", 0L, 0L, 1L, new ArrayList<>());
        assertEquals("建造 大熔炉 到 1 级", build.label());
        Action upgrade = new Action(ActionKind.UPGRADE_BUILDING, 301L, "大熔炉", 0L, 5L, 6L, List.of());
        assertEquals("升级 大熔炉 到 6 级", upgrade.label());
        Action furniture = new Action(ActionKind.UPGRADE_FURNITURE, 302L, "伐木场", 401L, 1L, 2L, List.of());
        assertEquals("伐木场 家具 401 升级到 2 级", furniture.label());
        assertFalse(furniture.usesQueue());
    }

    // ================= 卡点 A 方案 B：hold 参与规划（跳过依赖链，保留独立动作） =================

    @Test
    public void holdPolicy_skipsHeldFurnitureChain_keepsIndependentFurniture() {
        // 家具 101 建造被 hold：101 的 create+upgrade 都不可执行；家具 103 与 101 独立，仍可执行；
        // 301 2→3 依赖 308 补齐，不能出现在可执行动作里。
        HoldPolicy hold101 = action -> action.kind() == ActionKind.CREATE_FURNITURE
                && action.furnitureId() == 101L ? ACTION_HOLD_MILLIS : 0L;
        Plan plan = planner.plan(new Request(Map.of(301L, 2L, 308L, 1L), Map.of(),
                Map.of(301L, 3L), Set.of(), ResourceAvailability.ALWAYS,
                BootstrapPlanner.DEFAULT_MAX_ACTIONS, hold101));

        assertTrue(plan.holdBlocked());
        assertEquals(ACTION_HOLD_MILLIS, plan.holdRemainingMillis());
        assertTrue(plan.blockedReason().contains("动作退避中，剩余 1800 秒"));
        assertEquals(ActionKind.CREATE_FURNITURE, plan.actions().get(0).kind());
        assertEquals("被 hold 的 101 整链被跳过，独立可执行的 103 仍在",
                103L, plan.actions().get(0).furnitureId());
        assertFalse("101 的依赖动作不得出现",
                plan.actions().stream().anyMatch(a -> a.furnitureId() == 101L));
        assertFalse("依赖 308 的熔炉升级不得出现",
                plan.actions().stream().anyMatch(a -> a.buildingId() == 301L));
        assertFalse(plan.complete());
    }

    @Test
    public void holdPolicy_heldBuildingActionStopsDependentLevels() {
        // 308 0→1 被 hold：依赖它的 308 后续等级与 301 2→3 都不能作为可执行动作。
        HoldPolicy hold308 = action -> action.kind() == ActionKind.UPGRADE_BUILDING
                && action.buildingId() == 308L ? 900_000L : 0L;
        Plan plan = planner.plan(new Request(Map.of(301L, 2L), Map.of(), Map.of(301L, 3L), Set.of(),
                ResourceAvailability.ALWAYS, BootstrapPlanner.DEFAULT_MAX_ACTIONS, hold308));

        assertTrue(plan.holdBlocked());
        assertEquals(900_000L, plan.holdRemainingMillis());
        assertTrue("被 hold 的链首不得让后继依赖动作变为可执行", plan.actions().isEmpty());
        assertFalse(plan.complete());
        assertTrue(plan.blockedReason().contains("动作退避中"));
    }
}
