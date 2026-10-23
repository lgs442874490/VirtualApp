package com.carlos.vault.wjdr.asset;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 资产库内核单测：READY 判定 / awaitReady / awaitChange / version 只在变化时增 /
 * invalidate / 监听 dirty 域 / 推荐协议诊断。
 *
 * <p>READY 规则（CoreAssetLibrary）：核心 4 协议齐 +（推荐协议齐 或 首帧后 {@link
 * CoreAssetLibrary#READY_GRACE_MS} 宽限）。
 */
public class CoreAssetLibraryTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : items) out.add(o);
        return out;
    }

    private static Map<String, Object> item(long id, long count) {
        return map("id", id, "count", count);
    }

    private static CoreAssetLibrary lib() {
        return new CoreAssetLibrary(new AssetKey("fpid", 1L, 1001L, 1L));
    }

    // ---------------- 四核心协议内容 ----------------

    private static void feedCore(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", map(
                "project_start_ts", 1610294400L, "servertime", 1791905936L,
                "utc_offset", 28800L, "open_time", 1725156900L, "zero_time", 1791820800L));
        lib.accept("notify_world_player_init", map(
                "city_point", map("x", 585L, "y", 694L),
                "march_info", list(march(19117L)),
                "world_extra_march", map("value", 0L)));
        lib.accept("notify_resource_init", map("data", list(item(101L, 1704304L))));
        lib.accept("notify_recover_resource_init", map("data", map(
                "201.0", map("id", 201L, "count", 180L, "max_count", 200L, "recover", 1L,
                        "next_calc_ts", 1791916148L, "interval", 300L))));
    }

    private static Map<String, Object> march(long id) {
        return map("id", id, "owner", 28181058L, "type", 4L, "state", 3L,
                "starttime", 1791896523L, "endtime", 1791922041L,
                "startpoint", map("x", 585L, "y", 694L),
                "endpoint", map("x", 609L, "y", 704L));
    }

    /** 其余推荐协议（{@link CoreAssetLibrary#REQUIRED_RECOMMENDED} 减去核心 4 项）。 */
    private static void feedRemainingRecommended(CoreAssetLibrary lib) {
        // 未接解析器的协议：只计入 seen（推荐协议诊断口径），不影响 READY 之外的行为
        lib.accept("notify_soldier_init", map("data", list()));
        lib.accept("notify_hero_init", map("data", list()));
        lib.accept("notify_shelter_info", map());
        lib.accept("notify_lords_equipment_info", map());
        lib.accept("notify_all_research", map("researched", map(
                "1020.0", map("tid", 1020L, "lev", 1L))));
        lib.accept("notify_vip_info", map("vip_lv", 4L, "vip_active_endtime", 1L));
        lib.accept("notify_buff_show", map("map", map()));
        lib.accept("notify_backpack_init", map("data", list(item(610002L, 274L))));
        lib.accept("notify_intelligence_info", map(
                "radar_level", 17L, "radar_exp", 8L, "power_level", 7L,
                "next_event_time", 1791932400L, "events", map()));
    }

    private static CoreAssetLibrary readyLib() {
        CoreAssetLibrary lib = lib();
        feedCore(lib);
        feedRemainingRecommended(lib);
        return lib;
    }

    // ---------------- 测试 ----------------

    @Test
    public void coreFourOnly_notReady_untilGraceElapsed() throws Exception {
        CoreAssetLibrary lib = lib();
        feedCore(lib);
        assertFalse("4 核心协议齐但推荐协议未齐且未到宽限 -> 不 READY", lib.ready());
        assertFalse(lib.snapshot().ready);
        assertTrue(lib.version() > 0);

        // 宽限期（5s）后任一已识别帧触发重新判定
        Thread.sleep(CoreAssetLibrary.READY_GRACE_MS + 200L);
        lib.accept("notify_server_time", map("time", 1791906100L));
        assertTrue("核心 4 协议 + 宽限到 -> READY", lib.ready());
        assertTrue(lib.snapshot().ready);
        assertTrue(lib.readyAtMs() > 0);
    }

    @Test
    public void recommendedProtocolsArrival_makesReadyImmediately() {
        CoreAssetLibrary lib = lib();
        lib.accept("notify_soldier_init", map("data", list()));
        lib.accept("notify_hero_init", map("data", list()));
        lib.accept("notify_shelter_info", map());
        // 只到部分推荐协议：未 READY
        assertFalse(lib.ready());
        feedCore(lib);
        assertFalse(lib.ready());
        lib.accept("notify_all_research", map("researched", map(
                "1020.0", map("tid", 1020L, "lev", 1L))));
        lib.accept("notify_vip_info", map("vip_lv", 4L, "vip_active_endtime", 1L));
        lib.accept("notify_lords_equipment_info", map());
        lib.accept("notify_buff_show", map("map", map()));
        lib.accept("notify_backpack_init", map("data", list(item(610002L, 274L))));
        assertFalse(lib.ready());
        lib.accept("notify_intelligence_info", map(
                "radar_level", 17L, "radar_exp", 8L, "power_level", 7L,
                "next_event_time", 1791932400L, "events", map()));
        assertTrue(lib.ready());
        assertTrue(lib.snapshot().ready);
        assertTrue(lib.recommendedComplete());
        assertTrue(lib.missingProtocols().isEmpty());
        assertNotNull(lib.snapshot().key);
    }

    @Test
    public void awaitReady_and_awaitChange() {
        CoreAssetLibrary lib = lib();
        assertFalse(lib.awaitReady(0L));
        feedCore(lib);
        // 核心齐但推荐未齐：awaitReady 超时返回 false（不阻塞到 5s 宽限之外）
        assertFalse(lib.awaitReady(50L));

        CoreAssetLibrary lib2 = readyLib();
        assertTrue(lib2.awaitReady(1000L));
        long v = lib2.version();
        lib2.accept("notify_server_time", map("time", 1791906500L));
        long v2 = lib2.awaitChange(v, 1000L);
        assertTrue("awaitChange 应返回新版本", v2 > v);
        assertEquals(v2, lib2.version());
        // 已是最新版本：超时立即返回当前版本
        assertEquals(v2, lib2.awaitChange(v2, 0L));
    }

    @Test
    public void versionOnlyIncrementsOnRealChange() {
        CoreAssetLibrary lib = lib();
        feedCore(lib);
        long v = lib.version();
        assertTrue(v > 0);

        // 同一 server_time 重复推送：无变化不增版本
        lib.accept("notify_server_time", map("time", 1791905936L));
        assertEquals(v, lib.version());

        // 未知协议：不关心，不增版本
        lib.accept("notify_not_a_real_protocol", map("foo", 1L));
        assertEquals(v, lib.version());

        // 资源同值重放：init 全量替换但内容相同 -> 不增版本
        lib.accept("notify_resource_init", map("data", list(item(101L, 1704304L))));
        assertEquals(v, lib.version());

        // 真正变化才增版本
        lib.accept("notify_server_time", map("time", 1791907000L));
        assertTrue(lib.version() > v);
        assertEquals(1791907000L, lib.clock().serverTimeSec);
    }

    @Test
    public void invalidate_resetsReadyAndMarksStale_thenInitRefresh() {
        CoreAssetLibrary lib = readyLib();
        assertTrue(lib.ready());
        assertTrue(lib.snapshot().hasDomain(AssetDomain.RESOURCE));

        lib.invalidate("takeover");
        assertFalse(lib.ready());
        assertFalse(lib.snapshot().ready);
        assertTrue(lib.snapshot().staleDomains.contains(AssetDomain.META));
        assertTrue(lib.snapshot().staleDomains.contains(AssetDomain.RESOURCE));
        assertTrue("快照 readyAtMs 保留上次 READY 时刻（框架口径）", lib.readyAtMs() > 0L);

        // invalidate 后新 init 帧：该域恢复 fresh，但 READY 需核心集齐
        lib.accept("notify_resource_init", map("data", list(item(101L, 42L))));
        assertFalse(lib.ready());
        assertTrue(lib.snapshot().hasDomain(AssetDomain.RESOURCE));
        assertEquals(42L, lib.snapshot().resources.diamond());
        assertTrue(lib.snapshot().staleDomains.contains(AssetDomain.META));

        // 重放登录 init：重新 READY
        feedCore(lib);
        feedRemainingRecommended(lib);
        assertTrue(lib.ready());
    }

    @Test
    public void listenerReceivesDirtyDomainsAndSnapshot() {
        final CoreAssetLibrary lib = lib();
        final AtomicInteger calls = new AtomicInteger();
        final Set<AssetDomain> lastDirty = new LinkedHashSet<AssetDomain>();
        lib.addListener(new CoreAssetLibrary.Listener() {
            @Override
            public void onChanged(AssetSnapshot snapshot, Set<AssetDomain> dirtyDomains) {
                calls.incrementAndGet();
                lastDirty.clear();
                lastDirty.addAll(dirtyDomains);
                assertNotNull(snapshot);
            }
        });
        // 监听器异常隔离：坏监听器不影响其它监听者
        lib.addListener(new CoreAssetLibrary.Listener() {
            @Override
            public void onChanged(AssetSnapshot snapshot, Set<AssetDomain> dirtyDomains) {
                throw new IllegalStateException("boom");
            }
        });

        lib.accept("notify_resource_init", map("data", list(item(101L, 100L))));
        assertTrue(calls.get() > 0);
        assertTrue(lastDirty.contains(AssetDomain.RESOURCE));
        assertEquals(100L, lib.snapshot().resources.diamond());

        // 无变化的帧不通知
        int before = calls.get();
        lib.accept("notify_resource_init", map("data", list(item(101L, 100L))));
        assertEquals(before, calls.get());
    }

    @Test
    public void missingProtocols_diagnostics() {
        CoreAssetLibrary lib = lib();
        assertEquals(CoreAssetLibrary.REQUIRED_RECOMMENDED.length, lib.missingProtocols().size());
        assertFalse(lib.recommendedComplete());
        feedCore(lib);
        List<String> missing = lib.missingProtocols();
        assertFalse(missing.contains("notify_game_init"));
        assertFalse(missing.contains("notify_resource_init"));
        assertTrue(missing.contains("notify_vip_info"));
        feedRemainingRecommended(lib);
        assertTrue(lib.recommendedComplete());
        assertTrue(lib.missingProtocols().isEmpty());
    }
}
