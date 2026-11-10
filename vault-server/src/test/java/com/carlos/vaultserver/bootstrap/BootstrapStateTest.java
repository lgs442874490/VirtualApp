package com.carlos.vaultserver.bootstrap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapState} 单测（M4a 任务书 §2.5）：手造 loaddata 样本（3 建筑 + 家具 +
 * 1 忙队列）防御式深找解析、乐观更新（markedUpgraded/markedBusy/markedFurniture）、
 * idleQueueId 推进、未同步态。
 */
public class BootstrapStateTest {

    // ---------------- 样本构造 ----------------

    /** 3 建筑（dict/base/level 键三种形状混排）+ 家具（list/base 混排）+ 1 忙队列 + 资源。 */
    private static Map<String, Object> sampleLoadData() {
        Map<String, Object> b301 = new LinkedHashMap<>();
        b301.put("bid", 301L);
        b301.put("lv", 2L);
        b301.put("status", 0L);

        Map<String, Object> base302 = new LinkedHashMap<>();
        base302.put("lv", 1L);
        base302.put("status", 1L);
        Map<String, Object> b302 = new LinkedHashMap<>();
        b302.put("bid", 302L);
        b302.put("base", base302);
        b302.put("qid", 5L);

        Map<String, Object> b308 = new LinkedHashMap<>();
        b308.put("bid", 308L);
        b308.put("level", 1L);   // 兼容 level 键（任务书：lv/level）

        Map<String, Object> buildings = new LinkedHashMap<>();
        buildings.put("301", b301);
        buildings.put("302", b302);
        buildings.put("308", b308);

        Map<String, Object> f1 = new LinkedHashMap<>();
        f1.put("bid", 302L);
        f1.put("conf_fid", 401L);
        f1.put("lv", 2L);
        Map<String, Object> f308base = new LinkedHashMap<>();
        f308base.put("conf_fid", 101L);
        f308base.put("lv", 3L);
        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("bid", 308L);
        f2.put("base", f308base);
        List<Object> furnitures = new ArrayList<>();
        furnitures.add(f1);
        furnitures.add(f2);

        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("bid", 302L);
        queue.put("qid", 5L);
        queue.put("status", 0L);   // 非 1 → 忙碌
        List<Object> queues = new ArrayList<>();
        queues.add(queue);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("103", 500);
        res.put("104", 60);
        res.put("105", 7);
        res.put("101", 88);

        Map<String, Object> load = new LinkedHashMap<>();
        load.put("uid", 12345L);
        load.put("name", "旅人");
        load.put("buildings", buildings);
        load.put("furnitures", furnitures);
        load.put("queues", queues);
        load.put("res", res);
        return load;
    }

    // ---------------- 解析 ----------------

    @Test
    public void fromLoadData_parsesBuildingsFurnitureQueuesResources() {
        BootstrapState s = BootstrapState.fromLoadData(sampleLoadData());
        assertEquals(2L, s.level(301L));
        assertEquals(1L, s.level(302L));
        assertEquals(1L, s.level(308L));
        assertEquals(2L, s.furnaceLevel());
        assertEquals(Map.of(401L, 2L), s.furnitureLevels().get(302L));
        assertEquals(Map.of(101L, 3L), s.furnitureLevels().get(308L));
        // 302 升级中（base.status=1）+ 占用队列 5 → busy
        assertEquals(Set.of(302L), s.busyBuildings());
        assertTrue(s.busy(302L));
        assertFalse(s.busy(301L));
        assertTrue(s.buildingsReady());
        assertTrue(s.queuesReady());
        assertTrue(s.stateReady());
        assertEquals(12345L, s.roleUid());
        assertEquals("旅人", s.roleName());
        assertEquals(500L, s.resourceCount(103L));
        assertEquals(60L, s.resourceCount(104L));
        assertEquals(7L, s.resourceCount(105L));
        assertEquals(88L, s.resourceCount(101L));
        assertEquals(0L, s.resourceCount(999L));
    }

    @Test
    public void fromLoadData_nullOrEmpty_notReady() {
        BootstrapState empty = BootstrapState.fromLoadData(null);
        assertFalse(empty.buildingsReady());
        assertFalse(empty.queuesReady());
        assertFalse(empty.stateReady());
        assertEquals(0L, empty.furnaceLevel());
        assertEquals("", empty.roleName());
        assertEquals(0L, empty.roleUid());
        assertFalse(BootstrapState.fromLoadData(Map.of()).buildingsReady());
    }

    @Test
    public void fromLoadData_buildingInfoKeyCandidate() {
        // 键候选 building_info（与 buildings 等价）
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("bid", 301L);
        entry.put("lv", 7L);
        List<Object> list = new ArrayList<>();
        list.add(entry);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("building_info", list);
        BootstrapState s = BootstrapState.fromLoadData(load);
        assertTrue(s.buildingsReady());
        assertFalse(s.queuesReady());
        assertEquals(7L, s.level(301L));
    }

    // ---------------- idleQueueId ----------------

    @Test
    public void idleQueueId_fallbackCandidates() {
        // 未同步队列：候选 1/2
        assertEquals(1L, BootstrapState.empty().idleQueueId());
        // 队列 1 忙 → 候选 2
        BootstrapState s = BootstrapState.empty();
        s.markedBusy(301L);
        assertEquals(2L, s.idleQueueId());
        // 队列 1、2 都忙 → 0
        s.markedBusy(302L);
        assertEquals(0L, s.idleQueueId());
    }

    @Test
    public void idleQueueId_fromSyncedQueues() {
        BootstrapState s = BootstrapState.fromLoadData(sampleLoadData());
        // 已知队列 {5}，5 忙 → 无空闲 → 0（knownQueues 非空不再回退候选 1/2）
        assertEquals(0L, s.idleQueueId());
    }

    // ---------------- 乐观更新 ----------------

    @Test
    public void markedUpgraded_advancesLevelAndReleasesQueue() {
        BootstrapState s = BootstrapState.fromLoadData(sampleLoadData());
        s.markedUpgraded(301L, 3L);
        assertEquals(3L, s.level(301L));
        assertFalse(s.busy(301L));
        // 对齐 markBuildingInstantUpgrade：不碰其他建筑
        assertEquals(1L, s.level(302L));
    }

    @Test
    public void markedBusy_marksBuildingAndConsumesQueue() {
        BootstrapState s = BootstrapState.empty();
        s.markedBusy(301L);
        assertEquals(Set.of(301L), s.busyBuildings());
        assertTrue(s.busy(301L));
        // 队列 1 被占用 → 空闲队列推进到 2
        assertEquals(2L, s.idleQueueId());
    }

    @Test
    public void markedFurniture_advancesFurnitureLevel() {
        BootstrapState s = BootstrapState.fromLoadData(sampleLoadData());
        s.markedFurniture(302L, 401L, 3L);
        assertEquals(Map.of(401L, 3L), s.furnitureLevels().get(302L));
        s.markedFurniture(301L, 101L, 1L);
        assertEquals(Map.of(101L, 1L), s.furnitureLevels().get(301L));
    }

    @Test
    public void markedUpdates_defensive() {
        BootstrapState s = BootstrapState.empty();
        s.markedUpgraded(-1L, 5L);      // 不抛
        s.markedBusy(0L);
        s.markedFurniture(0L, 0L, 3L);
        assertEquals(0L, s.furnaceLevel());
    }

    // ---------------- buildingLevels 快照 ----------------

    @Test
    public void buildingLevels_snapshot() {
        BootstrapState s = BootstrapState.fromLoadData(sampleLoadData());
        assertEquals(Map.of(301L, 2L, 302L, 1L, 308L, 1L), s.buildingLevels());
    }

    @Test
    public void queueMapShape_fromNotifyQueueInfo() {
        // 兼容原版 notify_queue_info 形状：queue_map {qid → {qid, base.qtype, status}}
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("qtype", 1L);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("qid", 3L);
        q.put("base", base);
        q.put("status", 2L);    // 非 1 → 忙
        Map<String, Object> queueMap = new LinkedHashMap<>();
        queueMap.put("3", q);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("queue_map", queueMap);
        BootstrapState s = BootstrapState.fromLoadData(load);
        assertTrue(s.queuesReady());
        assertEquals(0L, s.idleQueueId());
    }

    @Test
    public void nonBuildingQueueType_skipped() {
        // qtype=2（非建筑队列）不计入
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("qtype", 2L);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("qid", 9L);
        q.put("base", base);
        q.put("status", 0L);
        Map<String, Object> queueMap = new LinkedHashMap<>();
        queueMap.put("9", q);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("queue_map", queueMap);
        BootstrapState s = BootstrapState.fromLoadData(load);
        assertTrue(s.queuesReady());
        assertEquals(1L, s.idleQueueId());   // 队列 9 被跳过 → 回退候选 1
    }

    @Test
    public void resourceListShape_parsed() {
        // 资源 list 形状 [{id, count}]
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 103L);
        r.put("count", 42);
        List<Object> list = new ArrayList<>(Arrays.asList(r));
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("resources", list);
        BootstrapState s = BootstrapState.fromLoadData(load);
        assertEquals(42L, s.resourceCount(103L));
    }

    /** 真实轮回归（2026-11-11）：幸存者列表解析与「闲置」判定（委任上岗的前提）。 */
    @Test
    public void survivors_parsedAndIdleDetected() {
        java.util.Map<String, Object> load = new java.util.LinkedHashMap<>();
        load.put("survivor_list", java.util.List.of(
                java.util.Map.of("sid", 1001L, "fid_work", 0L, "is_sick", false),
                java.util.Map.of("sid", 1002L, "fid_work", 5001L, "is_sick", false),
                java.util.Map.of("sid", 1003L, "fid_work", 0L, "is_sick", true)));
        BootstrapState st = BootstrapState.fromLoadData(load);
        org.junit.Assert.assertEquals(3, st.survivors().size());
        java.util.List<BootstrapState.Survivor> idle = st.idleSurvivors();
        org.junit.Assert.assertEquals("只有 1001 可委任（1002 已上岗、1003 生病）", 1, idle.size());
        org.junit.Assert.assertEquals(1001L, idle.get(0).sid());
    }
}
