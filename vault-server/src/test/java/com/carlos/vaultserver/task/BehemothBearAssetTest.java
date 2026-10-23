package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B2-T3：巨兽 auto_join / 巨熊 trap 读核心资产库的回归（纯 JVM，无网络）。
 *
 * <p>覆盖：①资产库 auto_join 状态 + {@code ServerTasks.autoJoinActive} 判定；
 * ②资产库 trap 域 + {@code ServerBearTasks.trapTimesFromAssets} 映射与回退。
 */
public class BehemothBearAssetTest {

    @After
    public void cleanup() {
        CoreAssetRegistry.global().clearAll();
    }

    // ---------- 1. auto_join 资产库判定 ----------

    @Test
    public void autoJoinActive_asset() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib);
        long future = 1_900_000_000L;
        lib.accept("req_alliance_activity_auto_join_info", mapOf(
                "end_time", future,
                "limit", 3L,
                "just_soldier", false,
                "offline", false,
                "auto_mass_list", Arrays.asList(
                        mapOf("id", 1L, "selected", true),
                        mapOf("id", 3L, "selected", false))));

        // selected 非空且未过期：期望 [1,4,3] 与选中 {1} 有交集 → true
        assertTrue(ServerTasks.autoJoinActive(lib.autoJoin(), Arrays.asList(1L, 4L, 3L), 1_700_000_000L));
        // 期望与选中无交集 → false
        assertFalse(ServerTasks.autoJoinActive(lib.autoJoin(), Arrays.asList(2L, 4L), 1_700_000_000L));
        // end_time 已过期 → false
        assertFalse(ServerTasks.autoJoinActive(lib.autoJoin(), Arrays.asList(1L, 4L), 1_950_000_000L));
        // 期望列表为空 → 只看是否有选中且未过期 → true
        assertTrue(ServerTasks.autoJoinActive(lib.autoJoin(), null, 1_700_000_000L));
    }

    @Test
    public void autoJoinActive_emptyOrExpired() {
        CoreAssetLibrary lib = new CoreAssetLibrary(new com.carlos.vault.wjdr.asset.AssetKey("test", 888L, 1L, 1L));
        long now = 1_700_000_000L;
        // 无数据 → false
        assertFalse(ServerTasks.autoJoinActive(lib.autoJoin(), Arrays.asList(1L), now));
        // selected 全空 → false
        lib.accept("req_alliance_activity_auto_join_info", mapOf(
                "end_time", 1_900_000_000L,
                "auto_mass_list", new ArrayList<>()));
        assertFalse(ServerTasks.autoJoinActive(lib.autoJoin(), Arrays.asList(1L), now));
    }

    // ---------- 2. trap 资产库映射 ----------

    @Test
    public void trapTimesFromAssets_asset() {
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib);
        lib.accept("req_alliance_trap_info", mapOf(
                "reserve_time", 2000L,
                "mass_start_time", 2100L,
                "mass_end_time", 3000L,
                "cool_end_time", 4000L));

        ServerBearTasks.TrapTimes t = ServerBearTasks.trapTimesFromAssets(engine);
        assertNotNull(t);
        assertEquals(2000L, t.reserve);
        assertEquals(2100L, t.start);
        assertEquals(3000L, t.end);
        assertEquals(4000L, t.cool);
    }

    @Test
    public void trapTimesFromAssets_fallbacks() {
        // 未注册 → null
        assertNull(ServerBearTasks.trapTimesFromAssets(new FakeEngine()));
        // 已 ready 但无 trap 数据 → null
        WjdrEngine engine = new FakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib);
        assertNull(ServerBearTasks.trapTimesFromAssets(engine));
        // 响应只有 mass_start_time（无 reserve）→ 资产库仍可给出场次时间（reserveKnown=true, any=true）
        lib.accept("req_alliance_trap_info", mapOf("mass_start_time", 2100L));
        ServerBearTasks.TrapTimes onlyStart = ServerBearTasks.trapTimesFromAssets(engine);
        assertNotNull(onlyStart);
        assertEquals(2100L, onlyStart.start);
    }

    // ---------- fixtures ----------

    /** 4 个核心协议 → READY。 */
    private static void ready(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", mapOf("project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", mapOf("march_info", new ArrayList<>(),
                "world_extra_march", mapOf("value", 0L)));
        lib.accept("notify_resource_init", mapOf("data", new ArrayList<>()));
        lib.accept("notify_recover_resource_init", mapOf("data", new LinkedHashMap<>()));
        lib.accept("notify_all_research", mapOf("researched", new LinkedHashMap<>()));
        lib.accept("notify_vip_info", mapOf("vip_lv", 6L, "vip_active_endtime", 9_999_999_999L));
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
