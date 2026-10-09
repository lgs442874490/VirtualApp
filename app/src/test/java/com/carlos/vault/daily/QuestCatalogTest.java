package com.carlos.vault.daily;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * TC-D04：每日任务目录（QuestCatalog，纯内存目录）。
 * 与原版 Web 版 daily-mission 的活跃度档位/任务分组目录对齐。
 */
public class QuestCatalogTest {

    @Test
    public void catalog_shape() {
        List<QuestCatalog.Group> all = QuestCatalog.all();
        assertEquals(22, all.size());
        int totalTiers = 0;
        Set<Long> ids = new HashSet<>();
        for (QuestCatalog.Group g : all) {
            assertNotNull(g.key);
            assertNotNull(g.name);
            assertNotNull(g.tag);
            assertTrue("分组名非空", g.name.length() > 0);
            assertTrue(g.tiers.length > 0);
            int lastTarget = -1;
            for (QuestCatalog.Tier t : g.tiers) {
                assertTrue("questId 在目录范围内: " + t.questId,
                        t.questId >= 1320000L && t.questId <= 1320211L);
                assertTrue(t.target > 0);
                assertTrue("档位积分必须为正", t.points > 0);
                assertTrue("组内档位按目标升序", t.target > lastTarget);
                lastTarget = t.target;
                ids.add(t.questId);
                totalTiers++;
            }
        }
        assertEquals(44, totalTiers);
        assertEquals("questId 不得重复", totalTiers, ids.size());
    }

    @Test
    public void allianceHelpGroup_matchesOriginal() {
        // 原版对齐样本：联盟帮助组 3 档（1/5/20 次 → 5/10/15 活跃度）
        QuestCatalog.Group g = null;
        for (QuestCatalog.Group cand : QuestCatalog.all()) {
            if (cand.key.contains("help") || cand.name.contains("帮助")) { g = cand; break; }
        }
        assertNotNull(g);
        assertEquals(3, g.tiers.length);
        assertEquals(1, g.tiers[0].target);
        assertEquals(5, g.tiers[0].points);
        assertEquals(5, g.tiers[1].target);
        assertEquals(10, g.tiers[1].points);
        assertEquals(20, g.tiers[2].target);
        assertEquals(15, g.tiers[2].points);
    }

    @Test
    public void totalPoints_positive() {
        int total = 0;
        for (QuestCatalog.Group g : QuestCatalog.all()) total += g.totalPoints();
        assertTrue(total > 0);
    }
}
