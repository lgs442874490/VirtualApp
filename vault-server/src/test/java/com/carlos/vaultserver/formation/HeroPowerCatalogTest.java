package com.carlos.vaultserver.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 英雄实力计算（2026-11-09，对齐网页版 HeroPowerCatalog）：
 * 生产抓包样本——赫克托 50024（Lv80/5星/专武5/技能满 + 4 件装备）= 6,084,302，
 * 与游戏内英雄实力排行榜 type7 完全一致；无装备基础值 2,304,648。
 */
public class HeroPowerCatalogTest {

    private static Map<String, Object> hector() {
        Map<String, Object> hero = new LinkedHashMap<>();
        hero.put("id", 50024L);
        hero.put("lev", 1L);
        hero.put("training_lev", 80L);
        hero.put("star", 30L);
        hero.put("elv", 5L);
        Map<String, Object> skills = new LinkedHashMap<>();
        for (int i = 0; i <= 6; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", 500240L + i);
            s.put("lv", i == 0 ? 1L : 5L);
            skills.put(String.valueOf(500240 + i), s);
        }
        hero.put("skills", skills);
        return hero;
    }

    private static List<Map<String, Object>> hectorEquipment() {
        List<Map<String, Object>> list = new ArrayList<>();
        long[][] rows = {
                {1011501L, 56L, 4L, 1L},
                {1021501L, 171L, 13L, 2L},
                {1031501L, 120L, 11L, 3L},
                {1041501L, 55L, 3L, 4L},
        };
        for (long[] r : rows) {
            Map<String, Object> d = new HashMap<>();
            d.put("eid", r[0]);
            d.put("slv", r[1]);
            d.put("rlv", r[2]);
            d.put("sid", r[3]);
            d.put("hid", 50024L);
            list.add(d);
        }
        return list;
    }

    @Test
    public void hectorBasePower_matchesGameValue() {
        // 等级+星级+技能（不含专武/装备）：186600*2.22 + 691800*2.22 + 101520 = 2,051,568
        assertEquals("赫克托基础实力（不含专武/装备）", 2_051_568L,
                HeroPowerCatalog.basePowerForTest(hector()));
        // 含专武（elv=5 战力 253,080）：2,051,568 + 253,080 = 2,304,648
        assertEquals("赫克托（无装备，含专武）", 2_304_648L,
                HeroPowerCatalog.power(hector(), null));
    }

    @Test
    public void hectorWithEquipment_matchesGameRankingValue() {
        assertEquals("赫克托（含 4 件装备）实力必须等于游戏内英雄实力榜 6,084,302",
                6_084_302L, HeroPowerCatalog.power(hector(), hectorEquipment()));
    }

    @Test
    public void explicitProtocolPowerWins() {
        Map<String, Object> hero = hector();
        hero.put("power", 12345678L);
        assertEquals(12345678L, HeroPowerCatalog.power(hero, hectorEquipment()));
    }
}
