package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.asset.HeroCatalog;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 英雄目录数据修复（契约 §3.2 / App 审计 P0-1 / 服务端审计 P2-1）：
 * 名称去引号（根因=NNAMES 字面量自带转义引号）、补 50062-50064、代际表。
 */
public class HeroCatalogFixTest {

    @Test
    public void names_haveNoQuotes_anyHero() {
        // 根因回归：生产目录曾出现 "\"巴希提\""（HeroCatalog.NAMES 字面量带转义引号）
        assertEquals("巴希提", HeroCatalog.name(50006L));
        assertEquals("诺拉", HeroCatalog.name(50025L));
        for (Map.Entry<Long, String> e : HeroCatalog.names().entrySet()) {
            String name = e.getValue();
            assertFalse("英雄 " + e.getKey() + " 名称不得含引号：" + name, name.contains("\""));
            assertFalse("英雄 " + e.getKey() + " 名称不得含反斜杠：" + name, name.contains("\\"));
            assertEquals("英雄 " + e.getKey() + " 名称不得有首尾空白", name.trim(), name);
            assertFalse("英雄名称不得为空", name.isEmpty());
        }
    }

    @Test
    public void cleanName_stripsLiteralAndEscapedQuotes() {
        assertEquals("巴希提", HeroCatalog.cleanName("\"巴希提\""));
        assertEquals("巴希提", HeroCatalog.cleanName("\\\"巴希提\\\""));
        assertEquals("巴希提", HeroCatalog.cleanName("  \"巴希提\"  "));
        assertEquals("卢姆·波根", HeroCatalog.cleanName("\"卢姆·波根\""));
        assertEquals("", HeroCatalog.cleanName(null));
        assertEquals("", HeroCatalog.cleanName("null"));
    }

    @Test
    public void coversWebCatalog65Heroes() {
        // 网页版 frontend/hero/heroes.json 共 65 条（50000-50064）
        assertEquals(65, HeroCatalog.names().size());
        assertEquals(65, HeroCatalog.types().size());
        assertEquals("艾丹", HeroCatalog.name(50062L));
        assertEquals("贝尔莎", HeroCatalog.name(50063L));
        assertEquals("埃莉诺", HeroCatalog.name(50064L));
        assertEquals(HeroCatalog.INFANTRY, HeroCatalog.profession(50062L));
        assertEquals(HeroCatalog.LANCER, HeroCatalog.profession(50063L));
        assertEquals(HeroCatalog.MARKSMAN, HeroCatalog.profession(50064L));
        assertEquals(17, HeroCatalog.generation(50062L));
    }

    @Test
    public void generation_tableMatchesWebCatalog() {
        assertEquals(0, HeroCatalog.generation(50000L));
        assertEquals("基础", HeroCatalog.generationLabel(50000L));
        assertEquals(6, HeroCatalog.generation(50019L));
        assertEquals(10, HeroCatalog.generation(50043L));
        assertEquals(16, HeroCatalog.generation(50061L));
        assertEquals("6代", HeroCatalog.generationLabel(50019L));
        assertEquals(0, HeroCatalog.generation(999999L));
    }

    @Test
    public void professionName_isChinese() {
        assertEquals("盾兵", HeroCatalog.professionName(50019L));
        assertEquals("矛兵", HeroCatalog.professionName(50025L));
        assertEquals("射手", HeroCatalog.professionName(50006L));
        assertEquals("未知职业", HeroCatalog.professionName(999999L));
        assertNotNull(HeroCatalog.generations());
        assertTrue(HeroCatalog.names().containsKey(50064L));
    }
}
