package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Cost;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link BuildingGateCatalog} 单测（M4a 任务书 §2.5）：直接读真实
 * {@code bootstrap/building-gate-catalog.json} 锚点值——301 大熔炉（maxLevel 80，
 * 无家具门槛）、家具 101:1（exp=10，cost=103:10）等。
 */
public class BuildingGateCatalogTest {

    private final BuildingGateCatalog catalog = BuildingGateCatalog.get();

    // ================= needExp =================

    @Test
    public void needExp_gateValues() {
        assertEquals(20L, catalog.needExp(302L, 2L));
        assertEquals(40L, catalog.needExp(308L, 2L));
        assertEquals(0L, catalog.needExp(302L, 1L));
    }

    @Test
    public void needExp_furnaceHasNoExpGate() {
        // 301 全 80 级均无经验门槛（数据实证）
        for (long lv = 1L; lv <= 80L; ++lv) {
            assertEquals("301 L" + lv + " 应无 needExp", 0L, catalog.needExp(301L, lv));
        }
    }

    @Test
    public void needExp_unknownBuildingOrLevel_returnsZero() {
        assertEquals(0L, catalog.needExp(999L, 1L));
        assertEquals(0L, catalog.needExp(302L, 999L));
    }

    // ================= furnitureRequirement =================

    @Test
    public void furnitureRequirement_gateValues() {
        assertEquals(List.of(new Cost(401L, 2L)), catalog.furnitureRequirement(302L, 1L));
        assertEquals(List.of(new Cost(401L, 4L), new Cost(402L, 4L)),
                catalog.furnitureRequirement(302L, 2L));
        // 308 L1 门槛：家具 101:2 + 103:2
        assertEquals(List.of(new Cost(101L, 2L), new Cost(103L, 2L)),
                catalog.furnitureRequirement(308L, 1L));
    }

    @Test
    public void furnitureRequirement_furnaceIsEmpty() {
        assertTrue(catalog.furnitureRequirement(301L, 2L).isEmpty());
        assertTrue(catalog.furnitureRequirement(301L, 40L).isEmpty());
    }

    @Test
    public void furnitureRequirement_unknownIsEmpty() {
        assertTrue(catalog.furnitureRequirement(999L, 1L).isEmpty());
    }

    // ================= furnitureCosts / Exp / MaxLevel =================

    @Test
    public void furnitureCosts_anchorValues() {
        assertEquals(List.of(new Cost(103L, 10L)), catalog.furnitureCosts(101L, 1L));
        assertEquals(List.of(new Cost(103L, 15L)), catalog.furnitureCosts(101L, 2L));
        // 家具 401 用 102（木材系不同道具）
        assertEquals(List.of(new Cost(102L, 15L)), catalog.furnitureCosts(401L, 1L));
        assertTrue(catalog.furnitureCosts(999L, 1L).isEmpty());
    }

    @Test
    public void furnitureExp_anchorValues() {
        assertEquals(10L, catalog.furnitureExp(101L, 1L));
        assertEquals(20L, catalog.furnitureExp(101L, 2L));
        assertEquals(0L, catalog.furnitureExp(999L, 1L));
    }

    @Test
    public void furnitureMaxLevel_anchorValues() {
        assertEquals(20L, catalog.furnitureMaxLevel(101L));
        assertEquals(40L, catalog.furnitureMaxLevel(401L));
        assertEquals(0L, catalog.furnitureMaxLevel(999L));
    }

    @Test
    public void furnitureContributesExp_oneLevelExpPositive() {
        assertTrue(catalog.furnitureContributesExp(101L));
        assertTrue(catalog.furnitureContributesExp(401L));
        assertFalse(catalog.furnitureContributesExp(999L));
    }

    // ================= supports / maxLevel =================

    @Test
    public void supports_gateBuildings() {
        assertTrue(catalog.supports(301L));
        assertTrue(catalog.supports(302L));
        assertTrue(catalog.supports(340L));
        assertFalse(catalog.supports(999L));
        // 318 英雄大厅不在 gate json（组合目录 BootstrapCatalog 内置特殊定义）
        assertFalse(catalog.supports(318L));
    }

    @Test
    public void gateMaxLevel_furnace80() {
        assertEquals(80L, catalog.gateMaxLevel(301L));
        assertEquals(30L, catalog.gateMaxLevel(302L));
        assertEquals(0L, catalog.gateMaxLevel(999L));
    }

    @Test
    public void catalogSize_realData() {
        // 真实数据 29 个门槛建筑、家具表非空（防数据文件被误清）
        assertEquals(29, catalog.buildings.size());
        assertFalse(catalog.furniture.isEmpty());
        assertFalse(catalog.furnitureMaxLevels.isEmpty());
    }
}
