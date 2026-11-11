package com.carlos.vaultserver.watchpoint;

import com.carlos.vaultserver.watchpoint.WatchPointEngine.MarchActivity;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 行军行为统一分类（2026-11-11 用户口径）：
 *
 * <ul>
 *   <li>己方联盟旗帜/总部（荒原·雪原）/王城：已建成 → 驻防，未建成 → 建设；</li>
 *   <li>其他联盟旗帜/总部/王城：一律破坏；</li>
 *   <li>熊坑陷阱：只能建设（不论建成与否），不能驻防；其他联盟陷阱 → 破坏；</li>
 *   <li>矿点：已有采集者、或有更早出发且仍在途的同点行军 → 撞矿；否则 → 采集；</li>
 *   <li>侦查（type=1）不参与上述分类。</li>
 * </ul>
 */
public class WatchPointMarchActivityClassifyTest {

    private static final long OWN_AID = 89700731L;
    private static final long OTHER_AID = 89700055L;
    private static final long TYPE_DEFEND = 5L;   // 驻防/增援
    private static final long TYPE_GATHER = 4L;   // 采集
    private static final long TYPE_SCOUT = 1L;    // 侦查

    private static WatchPointEngine.Building bld(String kind, long aid, boolean built) {
        return new WatchPointEngine.Building(kind, aid, built);
    }

    private static MarchActivity classify(long type, long ownerAid, WatchPointEngine.Building b,
                                          boolean resourcePoint, boolean occupied, boolean earlier) {
        return WatchPointEngine.classifyMarchActivity(type, ownerAid, b, resourcePoint, occupied, earlier);
    }

    // ---------------- 旗帜 ----------------

    @Test
    public void ownBuiltFlag_isDefend() {
        assertEquals(MarchActivity.DEFEND, classify(TYPE_DEFEND, OWN_AID, bld("旗子", OWN_AID, true),
                false, false, false));
    }

    @Test
    public void ownUnbuiltFlag_isBuild() {
        assertEquals(MarchActivity.BUILD, classify(TYPE_DEFEND, OWN_AID, bld("旗子", OWN_AID, false),
                false, false, false));
    }

    @Test
    public void otherFlag_isDestroy_evenIfBuilt() {
        assertEquals(MarchActivity.DESTROY, classify(TYPE_DEFEND, OWN_AID, bld("旗子", OTHER_AID, true),
                false, false, false));
        assertEquals(MarchActivity.DESTROY, classify(TYPE_DEFEND, OWN_AID, bld("旗子", OTHER_AID, false),
                false, false, false));
    }

    // ---------------- 总部（荒原/雪原）与王城 ----------------

    @Test
    public void ownBuiltHall_isDefend_ownUnbuiltHall_isBuild() {
        assertEquals(MarchActivity.DEFEND, classify(TYPE_DEFEND, OWN_AID, bld("总部", OWN_AID, true),
                false, false, false));
        assertEquals(MarchActivity.BUILD, classify(TYPE_DEFEND, OWN_AID, bld("总部", OWN_AID, false),
                false, false, false));
    }

    @Test
    public void otherHall_isDestroy() {
        assertEquals(MarchActivity.DESTROY, classify(TYPE_DEFEND, OWN_AID, bld("总部", OTHER_AID, true),
                false, false, false));
    }

    @Test
    public void ownCapital_isDefend() {
        assertEquals(MarchActivity.DEFEND, classify(TYPE_DEFEND, OWN_AID, bld("王城", OWN_AID, true),
                false, false, false));
    }

    @Test
    public void otherCapital_isDestroy() {
        assertEquals(MarchActivity.DESTROY, classify(TYPE_DEFEND, OWN_AID, bld("王城", OTHER_AID, true),
                false, false, false));
    }

    // ---------------- 熊坑陷阱：只能建设，不能驻防 ----------------

    @Test
    public void ownTrap_builtOrNot_isBuild_neverDefend() {
        assertEquals(MarchActivity.BUILD, classify(TYPE_DEFEND, OWN_AID, bld("陷阱", OWN_AID, true),
                false, false, false));
        assertEquals(MarchActivity.BUILD, classify(TYPE_DEFEND, OWN_AID, bld("陷阱", OWN_AID, false),
                false, false, false));
    }

    @Test
    public void otherTrap_isDestroy() {
        assertEquals(MarchActivity.DESTROY, classify(TYPE_DEFEND, OWN_AID, bld("陷阱", OTHER_AID, true),
                false, false, false));
    }

    // ---------------- 矿点：采集 vs 撞矿 ----------------

    @Test
    public void resourceOccupied_isClash() {
        assertEquals(MarchActivity.CLASH, classify(TYPE_GATHER, OWN_AID, null, true, true, false));
    }

    @Test
    public void resourceEarlierInbound_isClash() {
        assertEquals(MarchActivity.CLASH, classify(TYPE_GATHER, OWN_AID, null, true, false, true));
    }

    @Test
    public void resourceIdle_isGather() {
        assertEquals(MarchActivity.GATHER, classify(TYPE_GATHER, OWN_AID, null, true, false, false));
    }

    // ---------------- 不影响其他行为 ----------------

    @Test
    public void scoutNeverClassified() {
        assertNull(classify(TYPE_SCOUT, OWN_AID, bld("旗子", OWN_AID, true), false, false, false));
        assertNull(classify(TYPE_SCOUT, OWN_AID, null, true, true, true));
    }

    @Test
    public void plainTarget_returnsNull() {
        assertNull(classify(TYPE_DEFEND, OWN_AID, null, false, false, false));
        assertNull(classify(TYPE_GATHER, OWN_AID, null, false, false, false));
    }

    @Test
    public void nonGatherTypeOnResource_returnsNull() {
        // 打矿点（type=2）不走采集/撞矿分类，由「采集点受袭」通道处理。
        assertNull(classify(2L, OWN_AID, null, true, true, true));
    }

    // ---------------- buildingOf 解析（2026-11-11 审查修复：sproto bool = Boolean） ----------------

    private static Map<String, Object> buildingItem(String field, long aid, Object buildFinish) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("aid", aid);
        if (buildFinish != null) obj.put("build_finish", buildFinish);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(field, obj);
        return item;
    }

    @Test
    public void buildingOf_parsesBooleanBuildFinish() {
        WatchPointEngine.Building built = WatchPointEngine.buildingOf(
                buildingItem("map_alliance_flag", OWN_AID, Boolean.TRUE));
        assertNotNull(built);
        assertTrue(built.buildFinish);

        WatchPointEngine.Building unbuilt = WatchPointEngine.buildingOf(
                buildingItem("map_alliance_flag", OWN_AID, Boolean.FALSE));
        assertNotNull(unbuilt);
        assertFalse("Boolean.FALSE 必须判为未建成（未建成 → 建设，不能误判驻防）",
                unbuilt.buildFinish);
    }

    @Test
    public void buildingOf_resolvesKinds() {
        assertEquals("总部", WatchPointEngine.buildingOf(
                buildingItem("map_alliance_hall", OWN_AID, null)).kind);
        assertEquals("旗子", WatchPointEngine.buildingOf(
                buildingItem("map_alliance_flag", OWN_AID, null)).kind);
        assertEquals("陷阱", WatchPointEngine.buildingOf(
                buildingItem("map_alliance_trap", OWN_AID, null)).kind);
        assertEquals("王城", WatchPointEngine.buildingOf(
                buildingItem("map_capital", OWN_AID, null)).kind);
    }
}
