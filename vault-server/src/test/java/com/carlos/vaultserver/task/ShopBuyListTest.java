package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店「有序勾选清单」购买（2026-11-08）：按勾选顺序购买、直到没有货币。
 * 覆盖：顺序、余额不足停止、售罄跳过。
 */
public class ShopBuyListTest {

    private static final long SID = 888L;
    /** 301 专精能源石（专家折扣格）：1250 竞技币。 */
    private static final long CONF_301 = 30112902L;
    private static final long CURRENCY_301 = 109L;
    /** 901 通用专家印记（专家格）：1500 争霸赛徽章。 */
    private static final long CONF_901 = 90111101L;
    private static final long CURRENCY_901 = 111L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
    }

    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<Map<String, Object>> bought = new ArrayList<>();

        TestEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            if ("req_shop_info_array".equals(proto)) return responses.get("shop_info");
            if ("req_shop_buy".equals(proto)) {
                bought.add(new LinkedHashMap<>(args));
                return msg(0);
            }
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }

        @Override
        public long lastUid() {
            return 1L;
        }

        @Override
        public long lastServerId() {
            return SID;
        }
    }

    private static GameMessage msg(long code) {
        GameMessage m = new GameMessage();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        m.setContent(c);
        return m;
    }

    /** 货架响应：{code:0, map_info:{<shopId>.0:{id,goods:{<grid>:{id,conf_id,buy_count,grid_id}}}}}。 */
    private static GameMessage shelves(Map<String, Object> mapInfo) {
        GameMessage m = new GameMessage();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        c.put("map_info", mapInfo);
        m.setContent(c);
        return m;
    }

    private static Map<String, Object> shelf(long shopId, long confId, long goodsId, long buyCount) {
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put("id", goodsId);
        goods.put("conf_id", confId);
        goods.put("buy_count", buyCount);
        goods.put("grid_id", goodsId);
        Map<String, Object> shop = new LinkedHashMap<>();
        shop.put("id", shopId);
        shop.put("goods", Map.of(String.valueOf(goodsId), goods));
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.put(String.valueOf(shopId), shop);
        return mi;
    }

    private static Map<String, Object> currency(long id, long count) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("data", List.of(Map.of("id", id, "count", count)));
        return c;
    }

    private static JSONObject item(long shopId, long confId, long count) {
        return new JSONObject().put("shop_id", shopId).put("conf_id", confId).put("count", count);
    }

    private static JSONArray list(JSONObject... items) {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        return a;
    }

    private TaskContext tc(TestEngine e, JSONArray buyList) {
        JSONObject p = new JSONObject();
        p.put("buy_list", buyList);
        Map<String, JSONObject> params = new HashMap<>();
        params.put("shop", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    @Test
    public void buyList_ordersByUserSelection() {
        TestEngine e = new TestEngine();
        e.notifies.put("notify_resource_init", currency(CURRENCY_301, 5000L));
        e.notifies.put("notify_produced_resource_init", currency(CURRENCY_901, 5000L));
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.putAll(shelf(301L, CONF_301, 20L, 0L));
        mi.putAll(shelf(901L, CONF_901, 1L, 0L));
        e.responses.put("shop_info", shelves(mi));

        TaskOutcome out = ServerShopLeagueTasks.shopBuy(tc(e,
                list(item(301L, CONF_301, 1L), item(901L, CONF_901, 1L))));

        assertEquals(2, e.bought.size());
        assertEquals(301L, ((Number) e.bought.get(0).get("shop_id")).longValue());
        assertEquals(20L, ((Number) e.bought.get(0).get("goods_id")).longValue());
        assertEquals(901L, ((Number) e.bought.get(1).get("shop_id")).longValue());
        assertTrue(out.message.contains("专精能源石"));
    }

    @Test
    public void buyList_stopsWhenCurrencyInsufficient() {
        TestEngine e = new TestEngine();
        // 301 余额 3000：够买 1250 的专精能源石；901 余额 1000：不够 1500 的专家印记 → 停止
        e.notifies.put("notify_resource_init", currency(CURRENCY_301, 3000L));
        e.notifies.put("notify_produced_resource_init", currency(CURRENCY_901, 1000L));
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.putAll(shelf(301L, CONF_301, 20L, 0L));
        mi.putAll(shelf(901L, CONF_901, 1L, 0L));
        e.responses.put("shop_info", shelves(mi));

        TaskOutcome out = ServerShopLeagueTasks.shopBuy(tc(e,
                list(item(301L, CONF_301, 1L), item(901L, CONF_901, 1L))));

        assertEquals("只应买到第一个", 1, e.bought.size());
        assertEquals(301L, ((Number) e.bought.get(0).get("shop_id")).longValue());
        assertTrue(out.message.contains("余额不足"));
    }

    @Test
    public void buyList_skipsSoldOut() {
        TestEngine e = new TestEngine();
        e.notifies.put("notify_resource_init", currency(CURRENCY_301, 5000L));
        e.notifies.put("notify_produced_resource_init", currency(CURRENCY_901, 5000L));
        Map<String, Object> mi = new LinkedHashMap<>();
        // 301 专精能源石 limit=1 且已买 1 → 售罄
        mi.putAll(shelf(301L, CONF_301, 20L, 1L));
        mi.putAll(shelf(901L, CONF_901, 1L, 0L));
        e.responses.put("shop_info", shelves(mi));

        TaskOutcome out = ServerShopLeagueTasks.shopBuy(tc(e,
                list(item(301L, CONF_301, 1L), item(901L, CONF_901, 1L))));

        assertEquals("售罄应跳过", 1, e.bought.size());
        assertEquals(901L, ((Number) e.bought.get(0).get("shop_id")).longValue());
    }
}
