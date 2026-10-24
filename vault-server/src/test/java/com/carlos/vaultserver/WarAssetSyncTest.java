package com.carlos.vaultserver;

import com.carlos.vault.wjdr.asset.AssetKey;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * W9 战争事实迁移：资产库联盟域行军 → {@link ServerWarState} 判定机（与原始推送幂等）。
 */
public class WarAssetSyncTest {

    @After
    public void cleanup() {
        ServerWarState.unregister("t", 888L);
    }

    @Test
    public void allianceMarchFromAssets_drivesThreatAndSettlesOnRemoval() {
        long ownUid = 28181058L;
        long enemy = 99900001L;
        long nowSec = System.currentTimeMillis() / 1000L;
        CoreAssetLibrary lib = new CoreAssetLibrary(new AssetKey("t", 888L, ownUid, 1L));
        ready(lib);
        Map<String, Object> march = mapOf(
                "id", 7001L,
                "owner", enemy,
                "type", 2L,
                "state", 2L,
                "endtime", nowSec + 600L,
                "target_type", 1L,
                "targetid", 123456L,
                "target", mapOf("uid", 123456L));
        lib.accept("notify_world_alliance_situation_init",
                mapOf("marchs", new ArrayList<Object>(Arrays.asList(march))));
        assertTrue(lib.war().allianceMarches.size() == 1);

        ServerWarState w = ServerWarState.forRole("t", 888L);
        assertNotNull(w);
        w.syncFromAssets(lib, ownUid);
        JSONObject sum = ServerWarState.summaryJson("t", 888L);
        assertNotNull(sum);
        assertTrue("敌情板应有敌人", sum.optInt("enemies") >= 1);
        assertTrue("在途进攻>=1", sum.optInt("inFlightAttacks") >= 1);

        // 行军消失（推送 del/结束）→ 资产库替换为空 → 对账结算
        lib.accept("notify_world_alliance_situation_init", mapOf("marchs", new ArrayList<Object>()));
        w.syncFromAssets(lib, ownUid);
        JSONObject after = ServerWarState.summaryJson("t", 888L);
        assertEquals("在途进攻应结算为 0", 0, after.optInt("inFlightAttacks"));
    }

    // ---------- fixtures ----------

    private static void ready(CoreAssetLibrary lib) {
        lib.accept("notify_game_init", mapOf("project_start_ts", 1_610_294_400L,
                "open_time", 1_725_182_701L, "servertime", 1_791_905_936L));
        lib.accept("notify_world_player_init", mapOf("march_info", new ArrayList<Object>(),
                "world_extra_march", mapOf("value", 0L)));
        lib.accept("notify_resource_init", mapOf("data", new ArrayList<Object>()));
        lib.accept("notify_recover_resource_init", mapOf("data", new LinkedHashMap<String, Object>()));
        lib.accept("notify_all_research", mapOf("researched", new LinkedHashMap<String, Object>()));
        lib.accept("notify_vip_info", mapOf("vip_lv", 6L, "vip_active_endtime", 9_999_999_999L));
        lib.accept("notify_soldier_init", mapOf("data", new ArrayList<Object>()));
        lib.accept("notify_hero_init", mapOf("data", new LinkedHashMap<String, Object>()));
        lib.accept("notify_shelter_info", mapOf("building_info", new LinkedHashMap<String, Object>()));
        lib.accept("notify_lords_equipment_info", mapOf("data", new ArrayList<Object>()));
        lib.accept("notify_backpack_init", mapOf("data", new ArrayList<Object>()));
        lib.accept("notify_intelligence_info", mapOf("events", new ArrayList<Object>()));
        lib.accept("notify_buff_show", mapOf("map", new ArrayList<Object>()));
        assertTrue(lib.ready());
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
