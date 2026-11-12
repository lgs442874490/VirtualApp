/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Qualifier
 *  org.springframework.core.io.ClassPathResource
 *  org.springframework.stereotype.Service
 *  tools.jackson.core.type.TypeReference
 *  tools.jackson.databind.ObjectMapper
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.account.GameAccountSessionService;
import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.modules.bootstrap.service.BootstrapProtocolSupport;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public final class BootstrapShopService {
    @Generated
    private static final Logger log = LoggerFactory.getLogger(BootstrapShopService.class);
    static final long COMMANDER_SHOP_ID = 701L;
    static final long MEAT_ITEM_ID = 100113L;
    static final long WOOD_ITEM_ID = 100123L;
    static final long COAL_ITEM_ID = 100133L;
    static final long IRON_ITEM_ID = 100143L;
    static final long PACK_SIZE = 10000L;
    static final int SHOP_NOT_OPEN = 1180;
    private static final String CATALOG_PATH = "config/daily-town-shop-catalog.json";
    private static final String SHOP_INFO = "req_shop_info";
    private static final String SHOP_BUY = "req_shop_buy";
    private static final String USE_ITEM = "req_use_item";
    private static final Map<Long, Long> PACK_RESOURCE_IDS = Map.of(100113L, 102L, 100123L, 103L, 100133L, 104L, 100143L, 105L);
    private final GameAccountSessionService sessions;
    private final Map<Long, CatalogItem> catalogByItemId;
    private final Map<Long, Long> itemIdByConfigId;

    public BootstrapShopService(@Qualifier(value="bootstrapGameAccountSessionService") GameAccountSessionService sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        Catalog catalog = this.loadCatalog(objectMapper);
        this.catalogByItemId = catalog.byItemId();
        this.itemIdByConfigId = catalog.itemIdByConfigId();
    }

    public List<Map<String, Object>> resourcePacks(long accountRoleId) {
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        Map<Long, Map<String, Object>> goodsByItem = this.currentGoods(session).goods();
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (long itemId : List.of(Long.valueOf(100113L), Long.valueOf(100123L), Long.valueOf(100133L), Long.valueOf(100143L))) {
            Map<String, Object> goods = goodsByItem.get(itemId);
            CatalogItem catalog = this.catalogByItemId.get(itemId);
            if (goods == null || catalog == null) continue;
            LinkedHashMap<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("itemId", itemId);
            view.put("goodsId", goods.get("goodsId"));
            view.put("name", catalog.name());
            view.put("price", catalog.price());
            view.put("currencyId", catalog.currencyId());
            view.put("count", catalog.count());
            view.put("limit", catalog.limit());
            view.put("buyCount", goods.get("buyCount"));
            view.put("remaining", BootstrapShopService.remainingPurchases(catalog, goods));
            result.add(view);
        }
        return result;
    }

    public Map<String, Object> buy(long accountRoleId, long goodsId, int count) {
        if (goodsId <= 0L) {
            throw new IllegalArgumentException("\u5546\u54c1\u5b9e\u4f8b\u7f16\u53f7\u65e0\u6548\uff0c\u8bf7\u5237\u65b0\u5546\u5e97\u540e\u91cd\u8bd5");
        }
        if (count <= 0 || count > 200) {
            throw new IllegalArgumentException("\u8d2d\u4e70\u4efd\u6570\u5fc5\u987b\u5728 1 \u5230 200 \u4e4b\u95f4");
        }
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        Map<Long, Map<String, Object>> goodsByItem = this.currentGoods(session).goods();
        Map goods = goodsByItem.values().stream().filter(value -> BootstrapProtocolSupport.number(value.get("goodsId")) == goodsId).findFirst().orElseThrow(() -> new IllegalArgumentException("\u5546\u54c1\u5df2\u7ecf\u5237\u65b0\u6216\u4e0d\u5728\u5f53\u524d\u5546\u5e97\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5"));
        long itemId = BootstrapProtocolSupport.number(goods.get("itemId"));
        CatalogItem catalog = this.catalogByItemId.get(itemId);
        if (catalog == null) {
            throw new IllegalArgumentException("\u5f53\u524d\u5546\u54c1\u4e0d\u5728\u8d77\u53f7\u8865\u8d44\u6e90\u767d\u540d\u5355\u5185");
        }
        GameMessage response = session.request(SHOP_BUY, Map.of("shop_id", 701L, "goods_id", goodsId, "count", count));
        long code = BootstrapProtocolSupport.responseCode(response);
        if (code != 0L) {
            throw new IllegalStateException("\u8d2d\u4e70\u88ab\u62d2\u7edd\uff0c\u9519\u8bef\u7801 " + code);
        }
        BootstrapShopService.useResourcePack(session, itemId, count);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("itemId", itemId);
        result.put("goodsId", goodsId);
        result.put("count", count);
        result.put("resourceId", PACK_RESOURCE_IDS.get(itemId));
        result.put("resourceAmount", 10000L * (long)count);
        result.put("diamondCost", catalog.price() * (long)count);
        result.put("message", "\u5df2\u8d2d\u4e70\u5e76\u4f7f\u7528 " + catalog.name() + " \u00d7" + count);
        return result;
    }

    private static void useResourcePack(GameAccountSessionService.AccountSession session, long itemId, int count) {
        try {
            session.request(USE_ITEM, Map.of("id", itemId, "count", count, "extra", Map.of()));
            log.info("[\u8d77\u53f7] \u7edf\u5e05\u5546\u5e97\u8d2d\u4e70\u540e\u5df2\u81ea\u52a8\u6253\u5f00\u5b89\u5168\u8d44\u6e90\u5305 itemId={} count={}", (Object)itemId, (Object)count);
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u81ea\u52a8\u6253\u5f00\u5b89\u5168\u8d44\u6e90\u5305\u5931\u8d25 itemId={} message={}", (Object)itemId, (Object)exception.getMessage());
        }
    }

    public Map<String, Object> buyMissingResources(long accountRoleId, long missingMeat, long missingWood, long missingCoal, long missingIron, long diamondBalance) {
        GameAccountSessionService.AccountSession session = this.requireOnline(accountRoleId);
        ShopGoods shopGoods = this.currentGoods(session);
        Map<Long, Map<String, Object>> goodsByItem = shopGoods.goods();
        ArrayList<Map<String, Object>> purchases = new ArrayList<Map<String, Object>>();
        this.appendPurchases(accountRoleId, goodsByItem, 100113L, missingMeat, diamondBalance, purchases);
        this.appendPurchases(accountRoleId, goodsByItem, 100123L, missingWood, diamondBalance, purchases);
        long remainMeat = Math.max(0L, missingMeat - BootstrapShopService.coveredAmount(purchases, 102L));
        long remainWood = Math.max(0L, missingWood - BootstrapShopService.coveredAmount(purchases, 103L));
        ArrayList<Map<String, Object>> unsupplied = new ArrayList<Map<String, Object>>();
        BootstrapShopService.appendUnsupplied(unsupplied, 100113L, 102L, remainMeat, "\u751f\u8089");
        BootstrapShopService.appendUnsupplied(unsupplied, 100123L, 103L, remainWood, "\u6728\u6750");
        BootstrapShopService.appendUnsupplied(unsupplied, 100133L, 104L, missingCoal, "\u7164\u77ff\uff08\u7edf\u5e05\u5546\u5e97\u4e0d\u552e\u5356\uff09");
        BootstrapShopService.appendUnsupplied(unsupplied, 100143L, 105L, missingIron, "\u94c1\u77ff\uff08\u7edf\u5e05\u5546\u5e97\u4e0d\u552e\u5356\uff09");
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("purchases", List.copyOf(purchases));
        result.put("diamondSpent", BootstrapShopService.spent(purchases));
        result.put("opened", shopGoods.opened());
        result.put("unsupplied", List.copyOf(unsupplied));
        result.put("coverable", unsupplied.isEmpty());
        if (!shopGoods.opened()) {
            result.put("message", "\u7edf\u5e05\u5546\u5e97\u672a\u5f00\u653e\uff08\u5927\u7194\u7089 5 \u7ea7\u5f00\u653e\uff09\uff0c\u672c\u6b21\u65e0\u6cd5\u7528\u94bb\u77f3\u8865\u8d44\u6e90");
        } else if (!unsupplied.isEmpty()) {
            result.put("message", "\u4ecd\u6709\u7f3a\u53e3\u65e0\u6cd5\u8865\u8db3\uff1a" + BootstrapShopService.unsuppliedSummary(unsupplied));
        } else if (purchases.isEmpty()) {
            result.put("message", "\u5f53\u524d\u6ca1\u6709\u9700\u8981\u8865\u5145\u7684\u8d44\u6e90");
        } else {
            result.put("message", "\u5df2\u8865\u5145 " + purchases.size() + " \u7c7b\u8d44\u6e90");
        }
        return result;
    }

    private static long coveredAmount(List<Map<String, Object>> purchases, long resourceId) {
        long total = 0L;
        for (Map<String, Object> purchase : purchases) {
            if (BootstrapProtocolSupport.number(purchase.get("resourceId")) != resourceId) continue;
            total += BootstrapProtocolSupport.number(purchase.get("resourceAmount"));
        }
        return total;
    }

    private static void appendUnsupplied(List<Map<String, Object>> unsupplied, long itemId, long resourceId, long missing, String name) {
        if (missing <= 0L) {
            return;
        }
        LinkedHashMap<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("itemId", itemId);
        entry.put("resourceId", resourceId);
        entry.put("missing", missing);
        entry.put("name", name);
        unsupplied.add(entry);
    }

    private static String unsuppliedSummary(List<Map<String, Object>> unsupplied) {
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> entry : unsupplied) {
            if (!summary.isEmpty()) {
                summary.append('\uff0c');
            }
            summary.append(BootstrapProtocolSupport.text(entry.get("name"))).append(' ').append(BootstrapProtocolSupport.number(entry.get("missing")));
        }
        return summary.toString();
    }

    public Map<String, Object> buyMissingResources(long accountRoleId, long missingWood, long missingCoal, long missingIron, long diamondBalance) {
        return this.buyMissingResources(accountRoleId, 0L, missingWood, missingCoal, missingIron, diamondBalance);
    }

    private void appendPurchases(long accountRoleId, Map<Long, Map<String, Object>> goodsByItem, long itemId, long missing, long diamondBalance, List<Map<String, Object>> purchases) {
        long alreadySpent = BootstrapShopService.spent(purchases);
        long remaining = Math.max(0L, diamondBalance - alreadySpent);
        purchases.addAll(this.buyForMissing(accountRoleId, goodsByItem, itemId, missing, remaining));
    }

    private List<Map<String, Object>> buyForMissing(long accountRoleId, Map<Long, Map<String, Object>> goodsByItem, long itemId, long missing, long remainingDiamonds) {
        long affordable;
        if (missing <= 0L) {
            return List.of();
        }
        Map<String, Object> goods = goodsByItem.get(itemId);
        CatalogItem catalog = this.catalogByItemId.get(itemId);
        if (goods == null || catalog == null) {
            log.info("[\u8d77\u53f7] \u7edf\u5e05\u5546\u5e97\u5f53\u524d\u65e0\u5546\u54c1 itemId={}\uff08\u53ef\u80fd\u672a\u5f00\u653e\u6216\u5df2\u552e\u5b8c\uff09", (Object)itemId);
            return List.of();
        }
        long packs = (missing + 10000L - 1L) / 10000L;
        long l = affordable = catalog.price() <= 0L ? packs : Math.min(packs, remainingDiamonds / catalog.price());
        if (affordable <= 0L) {
            log.info("[\u8d77\u53f7] \u7edf\u5e05\u5546\u5e97\u94bb\u77f3\u4e0d\u8db3\uff0c\u8df3\u8fc7 itemId={} \u7f3a\u53e3={} \u94bb\u77f3\u4f59\u989d={} \u5355\u4ef7={}", new Object[]{itemId, missing, remainingDiamonds, catalog.price()});
            return List.of();
        }
        try {
            Map<String, Object> purchase = this.buy(accountRoleId, BootstrapProtocolSupport.number(goods.get("goodsId")), (int)Math.min(affordable, 200L));
            log.info("[\u8d77\u53f7] \u7edf\u5e05\u5546\u5e97\u5df2\u8d2d\u4e70 {}\uff08\u7f3a\u53e3 {}\uff0c\u82b1\u8d39 {} \u94bb\u77f3\uff09", new Object[]{catalog.name(), missing, catalog.price() * Math.min(affordable, 200L)});
            return List.of(purchase);
        }
        catch (RuntimeException exception) {
            log.info("[\u8d77\u53f7] \u7edf\u5e05\u5546\u5e97\u8d2d\u4e70 itemId={} \u5931\u8d25\uff08\u7f3a\u53e3 {}\uff09\uff1a{}", new Object[]{itemId, missing, exception.getMessage()});
            return List.of();
        }
    }

    private static long remainingPurchases(CatalogItem catalog, Map<String, Object> goods) {
        if (catalog.limit() <= 0L) {
            return Long.MAX_VALUE;
        }
        long bought = BootstrapProtocolSupport.number(goods.get("buyCount"));
        return Math.max(0L, catalog.limit() - bought);
    }

    private static long spent(List<Map<String, Object>> purchases) {
        return purchases.stream().mapToLong(value -> BootstrapProtocolSupport.number(value.get("diamondCost"))).sum();
    }

    private ShopGoods currentGoods(GameAccountSessionService.AccountSession session) {
        GameMessage response = session.request(SHOP_INFO, Map.of("shop_id", 701L));
        Map<String, Object> content = BootstrapProtocolSupport.content(response);
        long code = BootstrapProtocolSupport.number(content.get("code"));
        if (code != 0L) {
            return new ShopGoods(code != 1180L, Map.of());
        }
        Map<String, Object> info = BootstrapProtocolSupport.map(content.get("info"));
        LinkedHashMap<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Object raw : BootstrapProtocolSupport.iterable(info.get("goods"))) {
            Map<String, Object> goods = BootstrapProtocolSupport.map(raw);
            long goodsId = BootstrapProtocolSupport.number(goods.get("id"));
            long confId = BootstrapProtocolSupport.number(goods.get("conf_id"));
            long gridId = BootstrapProtocolSupport.number(goods.get("grid_id"));
            long itemId = this.resolveItemId(confId, goodsId, gridId);
            if (goodsId <= 0L || itemId <= 0L) continue;
            LinkedHashMap<String, Long> view = new LinkedHashMap<String, Long>();
            view.put("itemId", itemId);
            view.put("goodsId", goodsId);
            view.put("buyCount", BootstrapProtocolSupport.number(goods.get("buy_count")));
            result.put(itemId, view);
        }
        return new ShopGoods(true, result);
    }

    private long resolveItemId(long confId, long goodsId, long gridId) {
        Long byConfigId = this.itemIdByConfigId.get(confId);
        if (byConfigId != null) {
            return byConfigId;
        }
        for (long candidate : new long[]{confId, goodsId, gridId}) {
            if (!this.catalogByItemId.containsKey(candidate)) continue;
            return candidate;
        }
        return 0L;
    }

    private Catalog loadCatalog(ObjectMapper objectMapper) {
        Catalog catalog;
        block9: {
            ClassPathResource resource = new ClassPathResource(CATALOG_PATH);
            InputStream input = resource.getInputStream();
            try {
                List rows = (List)objectMapper.readValue(input, (TypeReference)new TypeReference<List<Map<String, Object>>>(this){
                    {
                        Objects.requireNonNull(this$0);
                    }
                });
                LinkedHashMap<Long, CatalogItem> byItemId = new LinkedHashMap<Long, CatalogItem>();
                LinkedHashMap<Long, Long> itemIdByConfigId = new LinkedHashMap<Long, Long>();
                for (Map row : rows) {
                    long itemId;
                    if (BootstrapProtocolSupport.number(row.get("shopId")) != 701L || !PACK_RESOURCE_IDS.containsKey(itemId = BootstrapProtocolSupport.number(row.get("itemId")))) continue;
                    byItemId.put(itemId, new CatalogItem(itemId, BootstrapProtocolSupport.text(row.get("name")), Math.max(0L, BootstrapProtocolSupport.number(row.get("price"))), BootstrapProtocolSupport.number(row.get("currencyId")), Math.max(1L, BootstrapProtocolSupport.number(row.get("count"))), Math.max(0L, BootstrapProtocolSupport.number(row.get("limit")))));
                    long configId = BootstrapProtocolSupport.number(row.get("configId"));
                    if (configId <= 0L) continue;
                    itemIdByConfigId.put(configId, itemId);
                }
                catalog = new Catalog(Map.copyOf(byItemId), Map.copyOf(itemIdByConfigId));
                if (input == null) break block9;
            }
            catch (Throwable throwable) {
                try {
                    if (input != null) {
                        try {
                            input.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (IOException exception) {
                    throw new IllegalStateException("\u65e0\u6cd5\u52a0\u8f7d\u5546\u5e97\u76ee\u5f55", exception);
                }
            }
            input.close();
        }
        return catalog;
    }

    private GameAccountSessionService.AccountSession requireOnline(long accountRoleId) {
        if (!this.sessions.hasSession(accountRoleId)) {
            throw new IllegalStateException("\u8d77\u53f7\u8d26\u53f7\u672a\u4e0a\u7ebf");
        }
        return this.sessions.onlineSession(accountRoleId);
    }

    private record Catalog(Map<Long, CatalogItem> byItemId, Map<Long, Long> itemIdByConfigId) {
    }

    private record ShopGoods(boolean opened, Map<Long, Map<String, Object>> goods) {
    }

    private record CatalogItem(long itemId, String name, long price, long currencyId, long count, long limit) {
    }
}

