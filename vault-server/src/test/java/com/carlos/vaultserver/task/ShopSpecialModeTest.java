package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

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
 * 特殊自动购买（2026-11-08）：
 * - 601 免费商品全买 + 免费刷新全用；
 * - 801 资源货币商品全买（同格替换靠重新拉货架）+ 免费刷新（前 3 次）。
 */
public class ShopSpecialModeTest {

    private static final long SID = 888L;
    private static final long CONF_601_FREE = 60110328L;   // 英雄组件自选箱（免费）
    private static final long CONF_801_RES = 801103323L;   // 5分钟建造加速（煤矿 104，100000）
    private static final long CURRENCY_COAL = 104L;

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
        final Map<String, Object> mapInfo = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final List<Map<String, Object>> bought = new ArrayList<>();
        int refreshes = 0;

        TestEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public GameMessage request(String proto, Map<String, Object> args) {
            if ("req_shop_info_array".equals(proto)) return info();
            if ("req_shop_buy".equals(proto)) {
                bought.add(new LinkedHashMap<>(args));
                long shopId = ((Number) args.get("shop_id")).longValue();
                long goodsId = ((Number) args.get("goods_id")).longValue();
                long count = ((Number) args.get("count")).longValue();
                Map<String, Object> shop = (Map<String, Object>) mapInfo.get(String.valueOf(shopId));
                if (shop != null) {
                    Map<String, Object> goodsMap = (Map<String, Object>) shop.get("goods");
                    Map<String, Object> goods = (Map<String, Object>) goodsMap.get(String.valueOf(goodsId));
                    if (goods != null) {
                        long before = ((Number) goods.getOrDefault("buy_count", 0L)).longValue();
                        goods.put("buy_count", before + count);
                    }
                }
                return msg(0);
            }
            if ("req_shop_refresh".equals(proto)) {
                refreshes++;
                long shopId = ((Number) args.get("shop_id")).longValue();
                Map<String, Object> shop = (Map<String, Object>) mapInfo.get(String.valueOf(shopId));
                if (shop != null) {
                    long before = ((Number) shop.getOrDefault("refresh_times", 0L)).longValue();
                    shop.put("refresh_times", before + 1L);
                }
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

        GameMessage info() {
            GameMessage m = new GameMessage();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", 0);
            c.put("map_info", mapInfo);
            m.setContent(c);
            return m;
        }
    }

    private static GameMessage msg(long code) {
        GameMessage m = new GameMessage();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        m.setContent(c);
        return m;
    }

    private static Map<String, Object> shop(long shopId, long confId, long goodsId, long buyCount,
                                            long refreshTimes) {
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put("id", goodsId);
        goods.put("conf_id", confId);
        goods.put("buy_count", buyCount);
        goods.put("grid_id", goodsId);
        Map<String, Object> shop = new LinkedHashMap<>();
        shop.put("id", shopId);
        shop.put("goods", Map.of(String.valueOf(goodsId), goods));
        shop.put("refresh_times", refreshTimes);
        return shop;
    }

    private static Map<String, Object> currency(long id, long count) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("data", List.of(Map.of("id", id, "count", count)));
        return c;
    }

    private TaskContext tc(TestEngine e, JSONObject p) {
        Map<String, JSONObject> params = new HashMap<>();
        params.put("shop", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    @Test
    public void free601_buysFreeItemsAndUsesFreeRefresh() {
        TestEngine e = new TestEngine();
        e.mapInfo.put("601", shop(601L, CONF_601_FREE, 1L, 0L, 0L));
        JSONObject p = new JSONObject();
        p.put("free_601", true);

        TaskOutcome out = ServerShopLeagueTasks.shopBuy(tc(e, p));

        assertEquals("免费商品应买 1 件", 1, e.bought.size());
        assertEquals("首次免费刷新应使用 1 次", 1, e.refreshes);
        assertTrue(out.message.contains("免费"));
    }

    @Test
    public void resource801_buysResourceItemsAndUsesFreeRefreshes() {
        TestEngine e = new TestEngine();
        e.mapInfo.put("801", shop(801L, CONF_801_RES, 1L, 0L, 0L));
        e.notifies.put("notify_resource_init", currency(CURRENCY_COAL, 1_000_000L));
        JSONObject p = new JSONObject();
        p.put("auto_resource_801", true);

        TaskOutcome out = ServerShopLeagueTasks.shopBuy(tc(e, p));

        assertEquals("资源商品应买 1 件", 1, e.bought.size());
        assertEquals("801 前 3 次免费刷新应全部用掉", 3, e.refreshes);
        assertTrue(out.message.contains("5分钟建造加速"));
    }
}
