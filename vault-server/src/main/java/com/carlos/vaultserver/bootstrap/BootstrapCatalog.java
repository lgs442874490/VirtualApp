package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Cost;
import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Prerequisite;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Catalog;
import com.carlos.vaultserver.task.TownDevCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划器组合目录（M4a 任务书 §1 数据源分工）：把 {@link TownDevCatalog}（建筑名称/成本/前置，
 * 数据 towndev/towndev.json）与 {@link BuildingGateCatalog}（needExp/家具门槛/家具成本经验，
 * 数据 bootstrap/building-gate-catalog.json）合并为 {@link Catalog}，另内置 318 英雄大厅
 * 特殊建筑定义（对齐原版 SpecialBuildingCatalog，gate/towndev 数据均未收录 318）。
 *
 * <p>fallback 链对齐原版 DailyTownDevelopmentCatalog：名称与上限 towndev 优先、gate 兜底、
 * 特殊建筑兜底；前置 towndev 优先（任务书：建筑成本与前置以 TownDevCatalog 为准）；
 * buildingCosts 只取 towndev；门槛族全部取 gate。
 */
public final class BootstrapCatalog implements Catalog {

    /** 特殊建筑：318 英雄大厅（对齐原版 SpecialBuildingCatalog.DEFINITIONS）。 */
    private static final long HERO_HALL_ID = 318L;
    private static final long HERO_HALL_MAX_LEVEL = 1L;
    private static final String HERO_HALL_NAME = "英雄大厅";
    private static final List<Prerequisite> HERO_HALL_PREREQUISITES =
            List.of(new Prerequisite(301L, 4L));

    private final TownDevCatalog towndev;
    private final BuildingGateCatalog gates;

    private BootstrapCatalog(TownDevCatalog towndev, BuildingGateCatalog gates) {
        this.towndev = towndev;
        this.gates = gates;
    }

    /** 生产装配：TownDevCatalog 单例 + BuildingGateCatalog 单例。 */
    public static BootstrapCatalog combined() {
        return new BootstrapCatalog(TownDevCatalog.get(), BuildingGateCatalog.get());
    }

    /** 城镇建筑全量清单（towndev 数据序，status buildings 视图遍历用）。 */
    @Override
    public List<Catalog.TownBuilding> townBuildings() {
        List<Catalog.TownBuilding> out = new ArrayList<>();
        for (TownDevCatalog.Building b : towndev.buildings.values()) {
            out.add(new Catalog.TownBuilding(b.id, b.name == null || b.name.isBlank()
                    ? buildingName(b.id) : b.name));
        }
        return out;
    }

    // ================= Catalog 实现 =================

    @Override
    public boolean supports(long buildingId) {
        return towndev.buildings.containsKey(buildingId)
                || gates.supports(buildingId)
                || buildingId == HERO_HALL_ID;
    }

    @Override
    public long maxLevel(long buildingId) {
        TownDevCatalog.Building building = towndev.buildings.get(buildingId);
        if (building != null) return building.maxLevel;
        if (gates.supports(buildingId)) return gates.gateMaxLevel(buildingId);
        return buildingId == HERO_HALL_ID ? HERO_HALL_MAX_LEVEL : 0L;
    }

    @Override
    public String buildingName(long buildingId) {
        TownDevCatalog.Building building = towndev.buildings.get(buildingId);
        if (building != null && building.name != null && !building.name.isBlank()) {
            return building.name;
        }
        if (buildingId == HERO_HALL_ID) return HERO_HALL_NAME;
        return "建筑 " + buildingId;
    }

    @Override
    public List<Prerequisite> prerequisites(long buildingId, long level) {
        // towndev 优先（任务书：前置以 TownDevCatalog 为准）；gate 兜底；特殊建筑最后。
        TownDevCatalog.Building building = towndev.buildings.get(buildingId);
        if (building != null) {
            TownDevCatalog.BuildingLevel bl = buildingLevel(building, level);
            if (bl != null) {
                ArrayList<Prerequisite> out = new ArrayList<>();
                for (long[] p : bl.prerequisites) out.add(new Prerequisite(p[0], p[1]));
                return List.copyOf(out);
            }
            return List.of();
        }
        BuildingGateCatalog.GateLevel gl = gateLevel(buildingId, level);
        if (gl != null) return gl.prerequisites();
        return buildingId == HERO_HALL_ID ? HERO_HALL_PREREQUISITES : List.of();
    }

    /** 按等级查 TownDevCatalog 建筑级定义（其 level(int) 为 task 包私有，这里自行遍历）。 */
    private static TownDevCatalog.BuildingLevel buildingLevel(TownDevCatalog.Building building, long level) {
        for (TownDevCatalog.BuildingLevel bl : building.levels) {
            if (bl.level == level) return bl;
        }
        return null;
    }

    @Override
    public long needExp(long buildingId, long level) {
        BuildingGateCatalog.GateLevel gl = gateLevel(buildingId, level);
        return gl == null ? 0L : gl.needExp();
    }

    @Override
    public List<Cost> furnitureRequirement(long buildingId, long level) {
        BuildingGateCatalog.GateLevel gl = gateLevel(buildingId, level);
        return gl == null ? List.of() : gl.furniture();
    }

    @Override
    public long furnitureExp(long furnitureId, long level) {
        return gates.furnitureExp(furnitureId, level);
    }

    @Override
    public List<Cost> furnitureCosts(long furnitureId, long level) {
        return gates.furnitureCosts(furnitureId, level);
    }

    @Override
    public long furnitureMaxLevel(long furnitureId) {
        return gates.furnitureMaxLevel(furnitureId);
    }

    @Override
    public boolean furnitureContributesExp(long furnitureId) {
        return gates.furnitureContributesExp(furnitureId);
    }

    @Override
    public List<Cost> buildingCosts(long buildingId, long level) {
        TownDevCatalog.Building building = towndev.buildings.get(buildingId);
        if (building == null) return List.of();
        TownDevCatalog.BuildingLevel bl = buildingLevel(building, level);
        if (bl == null) return List.of();
        ArrayList<Cost> out = new ArrayList<>();
        for (TownDevCatalog.Cost c : bl.costs) out.add(new Cost(c.id, c.amount));
        return List.copyOf(out);
    }

    private BuildingGateCatalog.GateLevel gateLevel(long buildingId, long level) {
        BuildingGateCatalog.GateBuilding b = gates.buildings.get(buildingId);
        return b == null ? null : b.level(level);
    }
}
