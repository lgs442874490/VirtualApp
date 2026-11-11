package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.FileLog;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 城镇商店商品目录（{@code bootstrap/daily-town-shop-catalog.json}，与原版 jar 内
 * {@code config/daily-town-shop-catalog.json} 同源）。
 *
 * <p>仅提供起号补资源所需的最小子集：商品 itemId → 钻石价格。数据项形状：
 * {@code {shopId:701, itemId:100123, name:"1万木材（安全）", currencyId:101, price:2, limit:200}}。
 * 懒加载单例；加载失败降级为空表（补资源功能自动跳过，不影响起号主线）。
 */
public final class BootstrapShopCatalog {

    private static final String RESOURCE = "/bootstrap/daily-town-shop-catalog.json";
    private static volatile Map<Long, Long> priceByItemId;
    /** configId → itemId（商店响应里给的是 conf_id，需要反查 itemId）。 */
    private static volatile Map<Long, Long> itemIdByConfigId;

    private BootstrapShopCatalog() {
    }

    /** configId → itemId（对齐新版 BootstrapShopService.itemIdByConfigId）。未知返回 0。 */
    public static long itemIdByConfigId(long configId) {
        Map<Long, Long> m = configToItem();
        Long v = m.get(configId);
        return v == null ? 0L : v;
    }

    private static Map<Long, Long> configToItem() {
        Map<Long, Long> local = itemIdByConfigId;
        if (local != null) return local;
        synchronized (BootstrapShopCatalog.class) {
            if (itemIdByConfigId != null) return itemIdByConfigId;
            LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
            try (InputStream in = BootstrapShopCatalog.class.getResourceAsStream(RESOURCE)) {
                if (in != null) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    org.json.JSONArray arr = new org.json.JSONArray(text);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        long configId = item.optLong("configId", 0L);
                        long itemId = item.optLong("itemId", 0L);
                        if (configId > 0L && itemId > 0L) out.put(configId, itemId);
                    }
                }
            } catch (Throwable t) {
                FileLog.warn("商店目录 configId 映射解析失败：{}", String.valueOf(t.getMessage()));
            }
            itemIdByConfigId = out;
            return itemIdByConfigId;
        }
    }

    /** 商品钻石价格；未知 itemId 返回 0（调用方据此跳过购买）。 */
    public static long priceOf(long itemId) {
        Long price = prices().get(itemId);
        return price == null ? 0L : price;
    }

    private static Map<Long, Long> prices() {
        Map<Long, Long> local = priceByItemId;
        if (local != null) return local;
        synchronized (BootstrapShopCatalog.class) {
            if (priceByItemId != null) return priceByItemId;
            priceByItemId = load();
            return priceByItemId;
        }
    }

    private static Map<Long, Long> load() {
        HashMap<Long, Long> out = new HashMap<>();
        try (InputStream in = BootstrapShopCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                FileLog.warn("商店目录缺失：{}", RESOURCE);
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                long itemId = item.optLong("itemId", 0L);
                long price = item.optLong("price", 0L);
                if (itemId > 0L && price > 0L) out.put(itemId, price);
            }
        } catch (Throwable t) {
            FileLog.warn("商店目录解析失败：{}", String.valueOf(t.getMessage()));
        }
        return out;
    }
}
