package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.AssetSource;
import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 301 英雄拉黑轮换（HeroAvailability）单元测试。 */
public class HeroAvailabilityTest {

    @After
    public void cleanup() {
        HeroAvailability.clear(engine());
    }

    @Test
    public void blockAndCheck() {
        WjdrEngine e = engine();
        HeroAvailability.block(e, Arrays.asList(50001L, 50002L, 0L), 60_000L);
        assertTrue(HeroAvailability.isBlocked(e, 50001L));
        assertTrue(HeroAvailability.isBlocked(e, 50002L));
        assertFalse(HeroAvailability.isBlocked(e, 50003L));
        assertEquals(2, HeroAvailability.blockedIds(e).size());
        HeroAvailability.clear(e);
        assertFalse(HeroAvailability.isBlocked(e, 50001L));
    }

    @Test
    public void expiredBlockAutoClears() {
        WjdrEngine e = engine();
        HeroAvailability.block(e, Arrays.asList(50001L), 1L);
        try {
            Thread.sleep(5L);
        } catch (InterruptedException ignored) {
        }
        assertFalse(HeroAvailability.isBlocked(e, 50001L));
    }

    @Test
    public void heroIdsFromPayload() {
        Map<String, Object> payload = Map.of("hero_id", List.of(
                Map.of("k", 50001L, "v", 1L), Map.of("k", 50002L, "v", 1L)));
        List<Long> ids = HeroAvailability.heroIdsOf(payload);
        assertEquals(Arrays.asList(50001L, 50002L), ids);
        assertTrue(HeroAvailability.heroIdsOf(null).isEmpty());
    }

    private static WjdrEngine engine() {
        return new WjdrEngine(new AssetSource() {
            @Override
            public byte[] read(String name) {
                return new byte[0];
            }
        });
    }
}
