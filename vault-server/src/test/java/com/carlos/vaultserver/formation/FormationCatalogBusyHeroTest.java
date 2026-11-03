package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-04 真机：资产库 queue.marches 为空（occupied=6 但 marches=0）时，
 * busyHeroIds 恒空 → 自动编队选到正在驻防的英雄 → 403 英雄被占用。
 * 兜底改为直接读 notify_world_player_init.march_info 的 squad_list.hero_id。
 */
public class FormationCatalogBusyHeroTest {

    private static final class TestEngine extends WjdrEngine {
        final Map<String, Object> notifies = new LinkedHashMap<>();

        TestEngine() {
            super(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            return (Map<String, Object>) notifies.get(proto);
        }
    }

    private static Map<String, Object> pair(long k, long v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("v", v);
        return m;
    }

    @Test
    public void busyHeroIds_fromMarchInfo_whenAssetQueueEmpty() {
        TestEngine e = new TestEngine();
        Map<String, Object> squad = new LinkedHashMap<>();
        squad.put("hero_id", List.of(pair(1L, 50024L), pair(2L, 50029L), pair(3L, 50028L)));
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 13834L);
        march.put("type", 5L);
        march.put("state", 3L);   // 驻扎中：仍然占用英雄
        march.put("squad_list", squad);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("march_info", List.of(march));
        e.notifies.put("notify_world_player_init", wpi);

        Set<Long> busy = FormationCatalog.busyHeroIds(e);
        assertTrue("驻扎行军英雄应算占用：" + busy, busy.contains(50024L));
        assertTrue(busy.contains(50029L));
        assertTrue(busy.contains(50028L));
        assertFalse(busy.contains(50001L));
    }

    @Test
    public void busyHeroIds_emptyWhenNoMarchInfo() {
        TestEngine e = new TestEngine();
        assertTrue(FormationCatalog.busyHeroIds(e).isEmpty());
    }
}
