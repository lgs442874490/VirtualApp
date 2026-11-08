package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置驱动任务：商店购买 / 联盟商店 / 烈焰与獠牙 / 社交礼物 / 燃霜矿区。
 * 商品目录来自 assets/towndev/*.json（数据源=网页版 config 资源）。
 */
final class ShopTasks {

    private ShopTasks() {
    }

    // ================= 目录加载 =================

    static class Good {
        long configId;
        String name = "";
        long itemId, currencyId, price, limit, count;
        String currencyName = "";
    }

    /** shops.json: {shopId: [Good...]}，进程内缓存。 */
    private static Map<Long, List<Good>> shopsCache;

    static synchronized Map<Long, List<Good>> shops(Context ctx) {
        if (shopsCache != null) return shopsCache;
        Map<Long, List<Good>> out = new LinkedHashMap<>();
        try {
            // 热更目录优先（HotConfig.catalogs.shops），缺失回落 APK 内置 assets
            JSONObject root = HotConfig.catalogObj("shops");
            if (root == null) root = new JSONObject(readAsset(ctx, "towndev/shops.json"));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String shopId = it.next();
                JSONArray arr = root.getJSONArray(shopId);
                List<Good> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject g = arr.getJSONObject(i);
                    Good good = new Good();
                    good.configId = g.optLong("configId");
                    good.name = g.optString("name");
                    good.itemId = g.optLong("itemId");
                    good.currencyId = g.optLong("currencyId");
                    good.currencyName = g.optString("currencyName");
                    good.price = g.optLong("price");
                    good.limit = g.optLong("limit");
                    good.count = g.optLong("count", 1);
                    list.add(good);
                }
                out.put(Long.parseLong(shopId), list);
            }
        } catch (Exception ignored) {
        }
        shopsCache = out;
        return out;
    }

    static class LeakGood {
        long index, tableId, currencyId, price, itemId, itemCount, limit;
    }

    private static List<LeakGood> leakCache;

    static synchronized List<LeakGood> leakGoods(Context ctx) {
        if (leakCache != null) return leakCache;
        List<LeakGood> out = new ArrayList<>();
        try {
            // 热更目录优先（HotConfig.catalogs.flamefangs），缺失回落 APK 内置 assets
            JSONArray arr = HotConfig.catalog("flamefangs");
            if (arr == null) arr = new JSONArray(readAsset(ctx, "towndev/flamefangs.json"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                LeakGood l = new LeakGood();
                l.index = g.optLong("index");
                l.tableId = g.optLong("tableId");
                l.currencyId = g.optLong("currencyId");
                l.price = g.optLong("price");
                l.itemId = g.optLong("itemId");
                l.itemCount = g.optLong("itemCount");
                l.limit = g.optLong("limit");
                out.add(l);
            }
        } catch (Exception ignored) {
        }
        leakCache = out;
        return out;
    }

    static class Gift {
        long giftId, itemId;
        String name = "";
        List<Long> amountOptions = new ArrayList<>();
        long lockType, lockValue;
        boolean everOwnedRequired;
    }

    private static List<Gift> giftCache;

    static synchronized List<Gift> gifts(Context ctx) {
        if (giftCache != null) return giftCache;
        List<Gift> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readAsset(ctx, "towndev/socialgifts.json"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                Gift gift = new Gift();
                gift.giftId = g.optLong("giftId");
                gift.itemId = g.optLong("itemId");
                gift.name = g.optString("name");
                gift.lockType = g.optLong("lockConditionType");
                gift.lockValue = g.optLong("lockConditionValue");
                gift.everOwnedRequired = g.optBoolean("everOwnedRequired");
                JSONArray ao = g.optJSONArray("amountOptions");
                if (ao != null) {
                    for (int j = 0; j < ao.length(); j++) gift.amountOptions.add(ao.optLong(j));
                }
                out.add(gift);
            }
        } catch (Exception ignored) {
        }
        giftCache = out;
        return out;
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        byte[] buf = new byte[in.available()];
        int read = in.read(buf);
        in.close();
        return new String(buf, 0, read, StandardCharsets.UTF_8);
    }

    // ================= 商店购买（参数 buys: {configId: 数量}） =================

    static final long[] SHOP_IDS_TOWN = {801, 701, 1001, 901, 1201, 301, 2001, 601, 1101, 2401, 1701};
    static final long[] SHOP_IDS_ALLIANCE = {401, 501};
/** 实时价防御式提取（字段名未实证：price/cur_price/now_price/discount_price 之一），缺省回退目录价。 */
    private static long livePriceOf(Map<String, Object> g, long fallback) {
        for (String k : new String[]{"price", "cur_price", "now_price", "discount_price"}) {
            long v = ExecUtil.longOf(g.get(k));
            if (v > 0) return v;
        }
        return fallback;
    }

    private static boolean containsShop(long[] ids, long want) {
        for (long id : ids) if (id == want) return true;
        return false;
    }
/** 流动商人钻石刷新当日用量（进程内；按自然日重置）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> DIAMOND_REFRESH_DAILY =
            new java.util.concurrent.ConcurrentHashMap<>();
// ================= 燃霜矿区（报名+进场+里程碑+分数；占矿循环下一版） =================

    static final String[] FROSTFIRE_SLOTS = {"12:30", "16:00", "19:00", "20:30", "22:00"};
}
