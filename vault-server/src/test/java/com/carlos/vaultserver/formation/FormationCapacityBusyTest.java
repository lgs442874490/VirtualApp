package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetKey;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.asset.MarchCapacityCalculator;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 406/403 修复回归：
 * <ul>
 *   <li>出征容量计算（英雄等级→容量）>0，供编队夹取兵力；</li>
 *   <li>在途行军英雄从自动编队候选中排除（防 403 重复选人）。</li>
 * </ul>
 */
public class FormationCapacityBusyTest {

    @After
    public void cleanup() {
        CoreAssetRegistry.global().clearAll();
    }

    @Test
    public void capacityForHeroes() {
        WjdrEngine engine = fakeEngine();
        // 英雄 lv80 + 指挥中心 lv30 + 研究 1020 级1 + 家园生命树 8 + 容量建筑 5
        long capacity = MarchCapacityCalculator.capacityFor(engine, Arrays.asList(50001L));
        assertTrue("容量必须为正（公式数据齐）", capacity > 0L);
        // 单个 lv80 英雄容量 13470 + 固定容量(500+67000+320+4000+1000)*? 远大于 0
        assertTrue("容量应包含固定容量部分", capacity > 500L);
    }

    @Test
    public void busyHeroIds_excludedFromAutoSelection() {
        WjdrEngine engine = fakeEngine();
        CoreAssetLibrary lib = CoreAssetRegistry.global().attach(engine, "test", 888L, 1L, 1L);
        ready(lib);
        // 登录帧带 1 条在途采集行军，英雄 50001
        Map<String, Object> march = mapOf("id", 1L, "type", 4L, "state", 3L,
                "endtime", 1_900_000_000L,
                "squad_list", mapOf("hero_id", mapOf("1.0", mapOf("k", 1L, "v", 50001L))));
        lib.accept("notify_world_player_init", mapOf("march_info", Arrays.asList(march),
                "world_extra_march", mapOf("value", 0L)));
        assertTrue(FormationCatalog.busyHeroIds(engine).contains(50001L));
        List<Long> strongest = FormationResolver.strongestHeroes(engine, 5);
        assertFalse("在途英雄必须从自动编队候选中排除", strongest.contains(50001L));
        assertTrue("其余英雄可用", strongest.contains(50002L));
        assertTrue(FormationCatalog.busyHeroIds(engine).size() == 1);
    }

    // ---------- fixtures ----------

    /** 假引擎：覆盖 notifyData 返回构造的登录帧（编队目录/容量数据源）。 */
    private static WjdrEngine fakeEngine() {
        AssetSource assets = name -> new byte[0];
        return new WjdrEngine(assets) {
            @Override
            public Map<String, Object> notifyData(String proto) {
                Map<String, Object> heroInit = new LinkedHashMap<>();
                heroInit.put("data", mapOf(
                        "50001.0", mapOf("id", 50001L, "lev", 80L, "star", 10L),
                        "50002.0", mapOf("id", 50002L, "lev", 60L, "star", 8L)));
                switch (proto) {
                    case "notify_hero_init": return heroInit;
                    case "notify_shelter_info":
                        return mapOf("building_info", mapOf("331", mapOf("base", mapOf("lv", 30L))));
                    case "notify_all_research":
                        return mapOf("researched", mapOf("1020.0", mapOf("tid", 1020L, "lev", 1L)));
                    case "notify_homeland_info":
                        return mapOf("buildings", Arrays.asList(
                                mapOf("bid", 7000L, "level", 8L),
                                mapOf("bid", 7036L, "level", 5L)));
                    case "notify_buff_show":
                        return mapOf("buffs", Arrays.asList());
                    case "notify_soldier_init":
                        return mapOf("data", Arrays.asList(
                                mapOf("id", 11100L, "count", 50000L),
                                mapOf("id", 21100L, "count", 50000L),
                                mapOf("id", 31100L, "count", 50000L)));
                    default:
                        return null;
                }
            }
        };
    }

    private static void ready(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", mapOf("project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", mapOf("march_info", Arrays.asList(),
                "world_extra_march", mapOf("value", 0L)));
        lib.accept("notify_resource_init", mapOf("data", Arrays.asList()));
        lib.accept("notify_recover_resource_init", mapOf("data", new LinkedHashMap<>()));
        lib.accept("notify_all_research", mapOf("researched", new LinkedHashMap<>()));
        lib.accept("notify_vip_info", mapOf("vip_lv", 6L, "vip_active_endtime", 9_999_999_999L));
        lib.accept("notify_soldier_init", mapOf("data", Arrays.asList()));
        lib.accept("notify_hero_init", mapOf("data", new LinkedHashMap<>()));
        lib.accept("notify_shelter_info", mapOf("building_info", new LinkedHashMap<>()));
        lib.accept("notify_lords_equipment_info", mapOf("data", Arrays.asList()));
        lib.accept("notify_backpack_init", mapOf("data", Arrays.asList()));
        lib.accept("notify_intelligence_info", mapOf("events", Arrays.asList()));
        lib.accept("notify_buff_show", mapOf("map", Arrays.asList()));
        assertTrue(lib.ready());
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
