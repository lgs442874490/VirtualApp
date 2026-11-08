package com.carlos.vault.daily;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * TC-D01 / TC-D02 / TC-D03：静态数据目录与原版 Web 版 config 资源一致性。
 * （数据源=网页版 town-development-catalog / shops / flame-fangs / social-gifts 裁剪版）
 */
public class TowndevDataTest {

    private static JsonObject towndev;
    private static JsonObject shops;
    private static JsonArray flamefangs;
    private static JsonArray socialgifts;

    private static File asset(String rel) {
        File f = new File("src/main/assets/towndev/" + rel);
        if (f.exists()) return f;
        return new File("../app/src/main/assets/towndev/" + rel);
    }

    @BeforeClass
    public static void load() throws Exception {
        towndev = new JsonParser().parse(new FileReader(asset("towndev.json"))).getAsJsonObject();
        shops = new JsonParser().parse(new FileReader(asset("shops.json"))).getAsJsonObject();
        flamefangs = new JsonParser().parse(new FileReader(asset("flamefangs.json"))).getAsJsonArray();
        socialgifts = new JsonParser().parse(new FileReader(asset("socialgifts.json"))).getAsJsonArray();
    }

    @Test
    public void towndev_catalogShape() {
        // 191 科技 / 25 建筑 / 9 资源（resourceNames 为 id→名称映射对象）
        JsonArray techs = towndev.getAsJsonArray("technologies");
        JsonArray buildings = towndev.getAsJsonArray("buildings");
        JsonObject resources = towndev.getAsJsonObject("resourceNames");
        assertEquals(191, techs.size());
        assertEquals(25, buildings.size());
        assertEquals(9, resources.entrySet().size());
        assertEquals("钻石", resources.get("101").getAsString());
        assertEquals("火晶", resources.get("100081").getAsString());

        Set<Integer> techIds = new HashSet<>();
        for (JsonElement e : techs) {
            JsonObject t = e.getAsJsonObject();
            int id = t.get("id").getAsInt();
            assertTrue(id > 0);
            techIds.add(id);
            assertTrue("科技名非空", t.get("name").getAsString().length() > 0);
            assertTrue("maxLevel>=1", t.get("maxLevel").getAsInt() >= 1);
            JsonArray levels = t.getAsJsonArray("levels");
            assertEquals(levels.size(), t.get("maxLevel").getAsInt());
            for (JsonElement le : levels) {
                JsonObject lv = le.getAsJsonObject();
                assertTrue("每级必须有成本", lv.getAsJsonArray("costs").size() > 0);
                // 条件类型只允许 type5（建筑等级）/type14（前置科技）
                if (lv.has("conditions")) {
                    for (JsonElement ce : lv.getAsJsonArray("conditions")) {
                        int type = ce.getAsJsonObject().get("type").getAsInt();
                        assertTrue("条件类型必须是 5 或 14: " + type, type == 5 || type == 14);
                    }
                }
            }
        }
        assertEquals("科技 id 不得重复", 191, techIds.size());

        for (JsonElement e : buildings) {
            JsonObject b = e.getAsJsonObject();
            assertTrue(b.get("id").getAsInt() > 0);
            assertTrue(b.get("maxLevel").getAsInt() >= b.get("initialLevel").getAsInt());
        }
    }

    @Test
    public void researchRouteFamilies_presentInCatalog() {
        // 三族科技（与网页版 DailyResearchRouteCatalog 一致）：
        // GROWTH 7 族 / ECONOMY 8 族 / BATTLE 17 族
        String[] growth = {"工具改良", "病房扩建", "兵营扩建", "器材优化", "包扎术", "训练器材", "指挥艺术"};
        String[] economy = {"食物采集", "木材采集", "煤矿采集", "铁矿采集", "生肉产量", "木材产量", "煤矿产量", "铁矿产量"};
        String[] battle = {"编制扩展", "武器特训", "稳守反击", "精准射击", "迅猛突刺", "防御阵型", "警戒队形"};

        Map<String, Integer> families = new HashMap<>();
        for (JsonElement e : towndev.getAsJsonArray("technologies")) {
            String name = e.getAsJsonObject().get("name").getAsString();
            String family = stripRomanSuffix(name);
            families.merge(family, 1, Integer::sum);
        }
        for (String f : growth) assertTrue("GROWTH 族必须存在: " + f, families.containsKey(f));
        for (String f : economy) assertTrue("ECONOMY 族必须存在: " + f, families.containsKey(f));
        for (String f : battle) assertTrue("BATTLE 族必须存在: " + f, families.containsKey(f));
    }

    private static String stripRomanSuffix(String name) {
        // familyOf 同款：剥掉尾部罗马数字（工具改良I → 工具改良）
        return name.replaceFirst("(?i)(I{1,3}|IV|V|VI{1,3}|IX|X)$", "").trim();
    }

    @Test
    public void shops_catalogShape() {
        // 12 家店 942 商品；ShopTasks 引用的店 id 全部收录
        Set<String> expectedShopIds = new HashSet<>();
        for (int id : new int[]{401, 501, 801, 701, 1001, 901, 1201, 301, 2001, 601, 1101, 2401, 1701}) {
            expectedShopIds.add(String.valueOf(id));
        }
        assertEquals(13, shops.entrySet().size());
        assertEquals(expectedShopIds, shops.keySet());

        int goods = 0;
        for (Map.Entry<String, JsonElement> shop : shops.entrySet()) {
            for (JsonElement g : shop.getValue().getAsJsonArray()) {
                JsonObject good = g.getAsJsonObject();
                assertTrue("configId 必须存在", good.has("configId"));
                assertTrue("itemId 必须存在", good.has("itemId"));
                assertTrue("limit 必须存在: " + shop.getKey(), good.has("limit"));
                assertTrue("limit>=0", good.get("limit").getAsInt() >= 0);
                goods++;
            }
        }
        assertEquals(958, goods);

        // 联盟币商品限购样例（401 店存在 limit=5 的商品）
        boolean hasLimit5 = false;
        for (JsonElement g : shops.getAsJsonArray("401")) {
            if (g.getAsJsonObject().get("limit").getAsInt() == 5) hasLimit5 = true;
        }
        assertTrue(hasLimit5);
    }

    @Test
    public void flamefangs_catalogShape() {
        assertEquals(85, flamefangs.size());
        Set<String> keys = new HashSet<>();
        for (JsonElement e : flamefangs) {
            JsonObject o = e.getAsJsonObject();
            assertTrue(o.has("index"));
            assertTrue(o.has("tableId"));
            assertTrue("limit 必须存在", o.has("limit"));
            assertTrue(o.get("limit").getAsInt() > 0);
            keys.add(o.get("tableId").getAsString());
        }
        assertTrue("兑换表按 tableId 区分期次", keys.size() >= 1);
    }

    @Test
    public void socialGifts_catalogShape() {
        assertEquals(12, socialgifts.size());
        int withAmountOptions = 0;
        for (JsonElement e : socialgifts) {
            JsonObject g = e.getAsJsonObject();
            assertTrue(g.has("giftId"));
            assertTrue(g.has("name"));
            assertTrue(g.has("isVisible"));
            JsonArray opts = g.getAsJsonArray("amountOptions");
            // 基础礼物（1xxx 段）固定 5 档数量；高级礼物（2xxx/3xxx 段）无可选档位
            assertTrue("档位数只能是 0 或 5: " + g.get("giftId"), opts.size() == 0 || opts.size() == 5);
            if (opts.size() > 0) {
                withAmountOptions++;
                assertEquals(java.util.Arrays.asList(1, 10, 20, 50, 100).toString().replace(" ", ""),
                        opts.toString().replace(" ", ""));
            }
        }
        assertEquals("前 4 个基础礼物有 5 档数量", 4, withAmountOptions);
    }
}
