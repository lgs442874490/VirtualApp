package com.carlos.vault.wjdr.asset;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** 队列行军解析携带英雄 id（供自动编队排除在途英雄，修 403 重复选人）。 */
public class QueueMarchHeroIdsTest {

    @Test
    public void worldPlayerInit_parsesSquadHeroIds() {
        CoreAssetLibrary lib = new CoreAssetLibrary(new AssetKey("test", 888L, 1L, 1L));
        ready(lib);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 19117L);
        march.put("type", 4L);
        march.put("state", 3L);
        march.put("endtime", 1_900_000_000L);
        march.put("squad_list", mapOf(
                "id", 501533L,
                "hero_id", mapOf(
                        "1.0", mapOf("k", 1L, "v", 50041L),
                        "2.0", mapOf("k", 2L, "v", 50042L))));
        lib.accept("notify_world_player_init", mapOf(
                "march_info", Arrays.asList(march),
                "world_extra_march", mapOf("value", 0L)));
        assertNotNull(lib.queue());
        assertEquals(1, lib.queue().marches.size());
        List<Long> heroes = lib.queue().marches.get(0).heroIds;
        assertEquals(Arrays.asList(50041L, 50042L), heroes);
        assertEquals(1, lib.queue().occupied);
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
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
