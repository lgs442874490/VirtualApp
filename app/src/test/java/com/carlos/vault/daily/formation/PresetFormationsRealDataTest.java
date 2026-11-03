package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.FormationStore;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 线上真实目录（{@code formations_collection.json}，43 名英雄）下的 19 套预设冒烟测试：
 * 真实字段组合不应出现空编队、跨队重复、职业冲突或比例异常；熊上车最多 6 队。
 */
public class PresetFormationsRealDataTest {

    private static FormationModel find(List<FormationModel> lib, long id) {
        for (FormationModel f : lib) {
            if (f.id == id) return f;
        }
        return null;
    }

    private static String resource(String path) throws Exception {
        InputStream in = PresetFormationsRealDataTest.class.getClassLoader()
                .getResourceAsStream(path);
        assertNotNull("测试资源缺失: " + path, in);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    @Test
    public void liveCatalog_resolvesAllPresetsWithoutConflict() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse(
                resource("formation/formations_collection.json"));
        assertTrue(cat.configured());
        List<FormationModel> all = PresetFormations.all(cat);
        assertEquals(19, all.size());

        for (FormationModel f : all) {
            // 熊上车：队长与打熊队冲突时该队跳过（备选参考，不默认保留）
            if (f.id >= 9100000010L && f.id <= 9100000019L && f.heroIds.isEmpty()) continue;
            // 采集：单队长容量 >10 万时只带队长（契约 §2），其余编队 3 人
            boolean gather = f.id >= 9100000006L && f.id <= 9100000009L;
            int expectHeroes = gather && f.heroIds.size() == 1 ? 1 : 3;
            assertEquals(f.name + " 英雄数", expectHeroes, f.heroIds.size());
            assertEquals(f.name + " 队内英雄不重复", f.heroIds.size(),
                    new HashSet<Long>(f.heroIds).size());
            assertNotNull(f.name + " 队长", f.captainHeroId);
            assertTrue(f.name + " 队长在阵", f.hasHero(f.captainHeroId.longValue()));
            if (f.isPercentage()) {
                assertEquals(f.name + " 比例合计", 100,
                        f.infantryRatio + f.lancerRatio + f.marksmanRatio);
            }
            // 同队职业不重复（游戏每队列盾/矛/弓各一）
            Set<Integer> professions = new HashSet<Integer>();
            for (Long id : f.heroIds) {
                FormationStore.Hero h = cat.hero(id.longValue());
                assertNotNull(f.name + " 英雄 " + id + " 在目录", h);
                int p = FormationRules.professionOf(h);
                assertTrue(f.name + " 同队职业不重复", p <= 0
                        || professions.add(Integer.valueOf(p)));
            }
        }

        // 采集 4 队之间英雄不重复（单队长容量 >10 万时每队仅 1 人）
        Set<Long> gatherHeroes = new LinkedHashSet<Long>();
        int gatherTotal = 0;
        for (int i = 5; i <= 8; i++) {
            gatherHeroes.addAll(all.get(i).heroIds);
            gatherTotal += all.get(i).heroIds.size();
        }
        assertEquals("采集队列之间英雄不重复", gatherTotal, gatherHeroes.size());

        // ensure 落库：5 基础 + 4 采集 + 最多 6 熊上车（其余仅备选参考）
        List<FormationModel> lib = new ArrayList<FormationModel>();
        int created = PresetFormations.ensure(lib, cat);
        assertEquals(created, lib.size());
        for (FormationModel f : lib) {
            assertTrue(PresetFormations.isPreset(f.id));
            assertTrue(f.builtIn);
        }
        List<FormationModel> ride = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (PresetFormations.isPreset(f.id)
                    && f.id >= 9100000010L && f.id <= 9100000019L) ride.add(f);
        }
        assertEquals("熊上车最多 6 队", PresetFormations.BEAR_RIDE_MAX_TEAMS, ride.size());
        Set<Long> used = new LinkedHashSet<Long>(PresetFormations.byId(9100000003L) == null
                ? new ArrayList<Long>() : find(lib, 9100000003L).heroIds);
        for (FormationModel f : ride) {
            for (Long id : f.heroIds) {
                assertTrue("熊上车不与打熊队/彼此重复: " + f.name, used.add(id));
            }
        }
        // 5 基础 + 4 采集 + 6 熊上车
        assertEquals(5 + 4 + ride.size(), created);
    }
}
