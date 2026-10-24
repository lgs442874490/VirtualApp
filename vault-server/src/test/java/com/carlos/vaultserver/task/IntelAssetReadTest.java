package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetKey;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B2-T2：情报任务/队列容量改读核心资产库的回归（纯 JVM，无网络）。
 *
 * <p>覆盖：①资产库情报事件 → 旧循环 Map 形状；②容量公式纯函数阶梯；
 * ③marchCapacity/intelStamina 资产库优先分支（fake engine + 注册表 attach）。
 */
public class IntelAssetReadTest {

    @After
    public void cleanup() {
        CoreAssetRegistry.global().clearAll();
    }

    // ---------- 纯函数：容量公式 ----------

    @Test
    public void capacityFormula_techVipBuffExtra() {
        Map<String, Object> wpi = wpi(0);
        Map<String, Object> research = research(1020L, 1L);
        Map<String, Object> vip2 = vip(2, 0);
        // 1 + 1 科技 = 2
        assertEquals(2, ServerFieldTasks.marchCapacityFromNotifies(wpi, research, vip2, null, 1000L));
        // + VIP6 → 3
        assertEquals(3, ServerFieldTasks.marchCapacityFromNotifies(wpi, research, vip(6, 2000), null, 1000L));
        // + buff 10505 → 4
        assertEquals(4, ServerFieldTasks.marchCapacityFromNotifies(wpi, research, vip(6, 2000),
                buffShow(10505L, 2000L), 1000L));
        // + extra 1 → 5
        assertEquals(5, ServerFieldTasks.marchCapacityFromNotifies(wpi(1), research, vip(6, 2000),
                buffShow(10505L, 2000L), 1000L));
        // 三个科技 + VIP + buff + extra2 = 7 → clamp 6
        Map<String, Object> research3 = new LinkedHashMap<>();
        research3.put("researched", mapOf(
                "1007.0", row(1007L, 1L),
                "1020.0", row(1020L, 1L),
                "1027.0", row(1027L, 1L)));
        assertEquals(6, ServerFieldTasks.marchCapacityFromNotifies(wpi(2), research3, vip(6, 2000),
                buffShow(10505L, 2000L), 1000L));
        // 过期 buff / 过期 VIP 不加
        assertEquals(2, ServerFieldTasks.marchCapacityFromNotifies(wpi, research, vip(6, 500),
                buffShow(10505L, 500L), 1000L));
        // 科技/世界 init 缺失 → 0（未知）
        assertEquals(0, ServerFieldTasks.marchCapacityFromNotifies(null, research, vip2, null, 1000L));
        assertEquals(0, ServerFieldTasks.marchCapacityFromNotifies(wpi, null, vip2, null, 1000L));
    }

    // ---------- 资产库分支：marchCapacity / intelStamina ----------

    @Test
    public void marchCapacity_assetLibraryFirst() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib, 1L);
        assertEquals("1+1020科技+VIP6+extra1=4", 4, ServerFieldTasks.marchCapacity(engine));
    }

    @Test
    public void intelStamina_assetLibraryFirst() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib, 0L);
        lib.accept("notify_recover_resource_init", mapOf("data", mapOf("201.0", mapOf(
                "id", 201L, "count", 120L, "max_count", 200L, "recover", 1L,
                "next_calc_ts", 9_999_999_999L, "interval", 300L))));
        Long st = ServerFieldTasks.intelStamina(engine);
        assertNotNull(st);
        assertEquals(120L, st.longValue());
    }

    @Test
    public void intelEventsFromAssets_mapsFields() {
        CoreAssetLibrary lib = new CoreAssetLibrary(new AssetKey("test", 888L, 1L, 1L));
        lib.accept("notify_intelligence_info", mapOf(
                "radar_level", 5L,
                "next_event_time", 123L,
                "events", Arrays.asList(mapOf(
                        "id", 11L, "event_id", 1001L, "status", 1L,
                        "x", 100L, "y", 200L, "expire_ts", 9_999_999_999L, "played", false))));
        assertTrue("READY 前 intel 域仍应可读", lib.intel().events.size() == 1);
        Map<Long, Map<String, Object>> mapped = ServerFieldTasks.intelEventsFromAssets(lib.intel());
        assertEquals(1, mapped.size());
        Map<String, Object> e = mapped.get(11L);
        assertNotNull(e);
        assertEquals(1001L, e.get("event_id"));
        assertEquals(1L, e.get("status"));
        assertEquals(100L, e.get("x"));
        assertEquals(200L, e.get("y"));
    }

    @Test
    public void intelEventsFromAssets_emptySafe() {
        assertTrue(ServerFieldTasks.intelEventsFromAssets(null).isEmpty());
        assertTrue(ServerFieldTasks.intelEventsFromAssets(AssetSnapshot.Intel.empty()).isEmpty());
    }

    @Test
    public void legacyMarchState_assetFirst() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib, 0L);
        lib.accept("notify_world_player_init", mapOf("march_info", Arrays.asList(
                        mapOf("id", 1L, "type", 4L, "state", 3L, "endtime", 1234567890L),
                        mapOf("id", 2L, "type", 4L, "state", 3L, "endtime", 1234567900L),
                        mapOf("id", 3L, "type", 411L, "state", 3L, "endtime", 1234567910L)),
                "world_extra_march", mapOf("value", 0L)));
        ServerFieldTasks.MarchSnapshot snap = ServerFieldTasks.snapshotMarches(engine);
        assertTrue("资产库就绪后行军快照应 known", snap.known());
        assertEquals(2, snap.count(4L));
        assertEquals(1, snap.count(411L));
    }

    @Test
    public void bearWindowPauseReason_asset() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib, 0L);
        long now = lib.clock().serverNowSec();
        lib.accept("req_alliance_trap_info", mapOf("reserve_time", now + 100L));
        assertNotNull(ServerFieldTasks.bearWindowPauseReason(lib, now));
        assertNull(ServerFieldTasks.bearWindowPauseReason(null, now));
    }

    // ---------- fixtures ----------

    /** 4 个核心协议 → READY；extraMarch 控制额外队列。 */
    private static void ready(CoreAssetLibrary lib, long extraMarch) {
        lib.accept("notify_game_init", mapOf("project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", mapOf("march_info", new ArrayList<>(),
                "world_extra_march", mapOf("value", extraMarch)));
        lib.accept("notify_resource_init", mapOf("data", new ArrayList<>()));
        lib.accept("notify_recover_resource_init", mapOf("data", new LinkedHashMap<>()));
        lib.accept("notify_all_research", research(1020L, 1L));
        lib.accept("notify_vip_info", vip(6, 9_999_999_999L));
        // 推荐协议集齐 → READY 不等 5s 宽限
        lib.accept("notify_soldier_init", mapOf("data", new ArrayList<>()));
        lib.accept("notify_hero_init", mapOf("data", new ArrayList<>()));
        lib.accept("notify_shelter_info", mapOf("building_info", new LinkedHashMap<>()));
        lib.accept("notify_lords_equipment_info", mapOf("data", new ArrayList<>()));
        lib.accept("notify_backpack_init", mapOf("data", new ArrayList<>()));
        lib.accept("notify_intelligence_info", mapOf("events", new ArrayList<>()));
        lib.accept("notify_buff_show", mapOf("map", new ArrayList<>()));
        assertTrue("核心+推荐协议齐后应 READY", lib.ready());
    }

    private static Map<String, Object> wpi(long extra) {
        return mapOf("march_info", new ArrayList<>(), "world_extra_march", mapOf("value", extra));
    }

    private static Map<String, Object> research(long tid, long lev) {
        return mapOf("researched", mapOf(tid + ".0", row(tid, lev)));
    }

    private static Map<String, Object> row(long tid, long lev) {
        return mapOf("tid", tid, "lev", lev);
    }

    private static Map<String, Object> vip(long lv, long activeEnd) {
        return mapOf("vip_lv", lv, "vip_active_endtime", activeEnd);
    }

    private static Map<String, Object> buffShow(long attr, long expire) {
        Map<String, Object> buff = mapOf("show_id", 1L, "expire_ts", expire,
                "buff_map", mapOf(attr + ".0", mapOf("k", attr, "v", 1L)));
        List<Object> list = new ArrayList<>();
        list.add(buff);
        return mapOf("map", list);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 测试用引擎：不需要真实连接，只用于注册表关联。 */
    private static final class FakeEngine extends WjdrEngine {
        FakeEngine() {
            super(new AssetSource() {
                @Override
                public byte[] read(String name) {
                    return new byte[0];
                }
            });
        }
    }
}
