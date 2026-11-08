package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商店/联盟/背包类任务（App 端 daily/ShopTasks + LeagueTasks 逐行移植，改动须两侧同步）。
 * 商品目录 towndev/*.json 随 jar classpath 打包（与 App assets 同源）。
 */
public final class ServerShopLeagueTasks {

    private ServerShopLeagueTasks() {
    }

    // ================= 目录加载（classpath 资源） =================

    public static final class Good {
        public long configId;
        public String name = "";
        public long itemId, currencyId, price, limit, count;
        public String currencyName = "";
    }

    private static Map<Long, List<Good>> shopsCache;

    public static synchronized Map<Long, List<Good>> shops() {
        if (shopsCache != null) return shopsCache;
        Map<Long, List<Good>> out = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(readResource("towndev/shops.json"));
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

    public static final class LeakGood {
        public long index, tableId, currencyId, price, itemId, itemCount, limit;
    }

    private static List<LeakGood> leakCache;

    public static synchronized List<LeakGood> leakGoods() {
        if (leakCache != null) return leakCache;
        List<LeakGood> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readResource("towndev/flamefangs.json"));
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

    public static final class Gift {
        public long giftId, itemId;
        public String name = "";
        public List<Long> amountOptions = new ArrayList<>();
        public long lockType, lockValue;
        public boolean everOwnedRequired;
    }

    private static List<Gift> giftCache;

    public static synchronized List<Gift> gifts() {
        if (giftCache != null) return giftCache;
        List<Gift> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readResource("towndev/socialgifts.json"));
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

    private static String readResource(String name) throws Exception {
        java.io.InputStream in = ServerShopLeagueTasks.class.getClassLoader().getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("classpath 缺资源 " + name);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ================= 商店购买 =================

    public static final long[] SHOP_IDS_TOWN = {801, 701, 1001, 901, 1201, 301, 2001, 601, 1101, 2401, 1701};
    public static final long[] SHOP_IDS_ALLIANCE = {401, 501};

    /** 网页版仅流动商人(801)/神秘商店(601)支持 req_shop_refresh；其余店铺为日/周重置。 */
    public static final long SHOP_ID_VENDOR = 801L;
    public static final long SHOP_ID_MYSTERIOUS = 601L;

    /** App 新 UI「按名字勾选」参数键：roleParams 里存在非空对象即视为名单模式。 */
    static final String PARAM_BUY_NAMES = "buy_names";
    /**
     * App「有序勾选清单」参数键（2026-11-08）：JSON 数组，元素 {shop_id, conf_id, count}。
     * 与 buy_names 的区别：保留用户勾选顺序，按顺序购买、货币不足即停止（直到没有货币）。
     */
    static final String PARAM_BUY_LIST = "buy_list";

    /**
     * 名单模式判定（S2 起正式支持 buy_names；此函数保留为解析入口与批次2单测契约）。
     * App SubCardUi 保存时会写 buy_names（名字→1），同时为兼容旧读取把该名字的**全部 variant
     * confId** 写进 buys（SubCardUi.java:601-614）；服务端以 buy_names 为准（名字模式优先），
     * 只在同名多 variant 中选「最便宜且可支付」的一档。
     */
    static boolean nameModeConfigured(JSONObject params) {
        if (params == null) return false;
        JSONObject names = params.optJSONObject(PARAM_BUY_NAMES);
        return names != null && names.length() > 0;
    }

    /**
     * 过滤出网页版支持刷新的店铺（801/601），其余店铺不发送 req_shop_refresh
     * （audit §6.4 P3：原实现会对 12 店铺全量刷新，且对不可刷新店发协议）。
     */
    static long[] refreshableShopIds(long[] shopIds) {
        if (shopIds == null) return new long[0];
        long[] tmp = new long[shopIds.length];
        int n = 0;
        for (long shopId : shopIds) {
            if (shopId == SHOP_ID_VENDOR || shopId == SHOP_ID_MYSTERIOUS) {
                tmp[n++] = shopId;
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    // ---------- S2：名单模式（名字 → 候选 variant，最便宜且可支付） ----------

    /** 钻石币种 id（网页版 DailyShopService.DIAMOND_RESOURCE_ID=101）。 */
    static final long SHOP_DIAMOND_CURRENCY_ID = 101L;
    /** pending 对账窗口：窗口内同商品不重复下单（网页版 pending 检查点的最小版）。 */
    static final long SHOP_PENDING_TTL_MS = 120_000L;

    /** 网页版流动商人刷新费用：前 3 次免费，其后 50/100/100/200/200/500/1000 钻。 */
    static final long[] VENDOR_REFRESH_COSTS = {0L, 0L, 0L, 50L, 100L, 100L, 200L, 200L, 500L, 1000L};
    /** 网页版神秘商店刷新费用：首次免费，其后 100…2000 钻。 */
    static final long[] MYSTERIOUS_REFRESH_COSTS = {0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L,
            800L, 900L, 1000L, 1100L, 1200L, 1300L, 1400L, 1500L, 1600L, 1700L, 1800L, 1900L, 2000L};

    /** 货架候选：某店铺当前在架的单个商品实例（含实时价/已购/限购）。 */
    static record ShopShelfItem(long shopId, long goodsId, long configId, long currencyId,
                                long price, long buyCount, long limit) {
        /** 剩余可购数；limit<=0 视为不限购（名单模式仍只买 1 件）。 */
        long remaining() {
            return limit <= 0L ? Long.MAX_VALUE : Math.max(0L, limit - buyCount);
        }
    }

    /** 购买预算（安全默认：钻石关、钻石预算 0、每轮最多 20 件）。 */
    static record ShopBudget(boolean allowDiamond, long maxDiamondSpend, int maxBuysPerRound) {
        static ShopBudget from(JSONObject refreshCfg) {
            if (refreshCfg == null) return new ShopBudget(false, 0L, 20);
            long maxDiamond = Math.max(0L, refreshCfg.optLong("max_diamond_spend", 0L));
            int maxBuys = Math.max(1, Math.min(200, refreshCfg.optInt("max_buys", 20)));
            return new ShopBudget(refreshCfg.optBoolean("allow_diamond", false), maxDiamond, maxBuys);
        }
    }

    /** 解析 App 新 UI 名单：兼容 {名字:数量} / ["名字"] / [{name,count}] / "名字"。 */
    static Map<String, Long> parseBuyNames(Object raw) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        try {
            if (raw instanceof JSONObject) {
                JSONObject names = (JSONObject) raw;
                for (Iterator<String> it = names.keys(); it.hasNext(); ) {
                    String rawName = it.next();
                    String name = rawName == null ? "" : rawName.trim();
                    if (name.isEmpty()) continue;
                    long count = Math.max(1L, names.optLong(rawName, 1L));
                    out.merge(name, count, Math::max);
                }
            } else if (raw instanceof JSONArray) {
                JSONArray names = (JSONArray) raw;
                for (int i = 0; i < names.length(); i++) {
                    Object value = names.opt(i);
                    if (value instanceof JSONObject) {
                        JSONObject item = (JSONObject) value;
                        String name = item.optString("name", "").trim();
                        if (!name.isEmpty()) {
                            out.merge(name, Math.max(1L, item.optLong("count", 1L)), Math::max);
                        }
                    } else if (value != null) {
                        String name = String.valueOf(value).trim();
                        if (!name.isEmpty()) out.merge(name, 1L, Math::max);
                    }
                }
            } else if (raw instanceof String) {
                String name = ((String) raw).trim();
                if (!name.isEmpty()) out.put(name, 1L);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 旧 configId 勾选兼容：解析成去重后的 configId→数量（同一 variant 只买一次）。 */
    static LinkedHashMap<Long, Long> parseLegacyBuys(JSONObject buys) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        if (buys == null) return out;
        for (Iterator<String> it = buys.keys(); it.hasNext(); ) {
            String key = it.next();
            long configId;
            try {
                configId = Long.parseLong(key == null ? "" : key.trim());
            } catch (Exception ignored) {
                continue;
            }
            if (configId <= 0L) continue;
            out.merge(configId, Math.max(1L, buys.optLong(key, 1L)), Math::max);
        }
        return out;
    }

    /** 实时价防御式提取（字段名未实证，与 App ShopTasks.livePriceOf 同款）。 */
    static long livePriceOf(Map<String, Object> goods, long fallback) {
        if (goods != null) {
            for (String key : new String[]{"price", "cur_price", "now_price", "discount_price"}) {
                long value = Exec.longOf(goods.get(key));
                if (value > 0L) return value;
            }
        }
        return fallback;
    }

    /**
     * 单件是否可支付（资金安全核心）：
     * <ul>
     *   <li>免费档(price=0) 永远可买（不消耗币种）；</li>
     *   <li>钻石(101) 必须显式 allow_diamond + 剩余钻石预算足够；</li>
     *   <li>其它币种必须余额已知且足够（余额未知不买）。</li>
     * </ul>
     */
    static boolean shopItemPayable(ShopShelfItem item, Map<Long, Long> balances,
                                   ShopBudget budget, long diamondSpent) {
        if (item == null || item.price() < 0L) return false;
        if (item.price() == 0L) return true;
        if (item.currencyId() == SHOP_DIAMOND_CURRENCY_ID) {
            if (budget == null || !budget.allowDiamond()) return false;
            long remaining = budget.maxDiamondSpend() - Math.max(0L, diamondSpent);
            if (remaining < item.price()) return false;
        }
        Long balance = balances == null ? null : balances.get(item.currencyId());
        return balance != null && balance >= item.price();
    }

    /**
     * 同名多 variant 选「最便宜且可支付」的实例：绝不买贵档，也绝不买余额未知/超预算的档。
     * 同价时按 goodsId 升序稳定选择（便于复现与测试）。
     */
    static ShopShelfItem chooseCheapestVariant(List<ShopShelfItem> shelf,
                                               Map<Long, String> nameByConfigId, String name,
                                               Map<Long, Long> balances, ShopBudget budget,
                                               long diamondSpent) {
        if (shelf == null || name == null) return null;
        ShopShelfItem best = null;
        for (ShopShelfItem item : shelf) {
            if (!name.equals(nameByConfigId.get(item.configId()))) continue;
            if (item.remaining() <= 0L) continue;
            if (!shopItemPayable(item, balances, budget, diamondSpent)) continue;
            if (best == null || item.price() < best.price()
                    || (item.price() == best.price() && item.goodsId() < best.goodsId())) {
                best = item;
            }
        }
        return best;
    }

    /** 货架扁平化：店号 + goods 实例 → 候选；catalog 缺失的未知商品不参与（避免误判价格）。 */
    static List<ShopShelfItem> flattenShelf(Map<String, Object> mapInfo,
                                            Map<Long, Good> byConfigId) {
        List<ShopShelfItem> out = new ArrayList<>();
        if (mapInfo == null) return out;
        for (Map.Entry<String, Object> e : mapInfo.entrySet()) {
            long shopId;
            try {
                shopId = Long.parseLong(e.getKey() == null ? "" : e.getKey().trim());
            } catch (Exception ignored) {
                continue;
            }
            Map<String, Object> shopInfo = Exec.mapOf(e.getValue());
            if (shopInfo == null) continue;
            for (Map<String, Object> goods : Exec.mapsOf(shopInfo.get("goods"))) {
                long goodsId = Exec.longOf(goods.get("id"));
                long configId = Exec.longOf(goods.get("conf_id"));
                if (goodsId <= 0L || configId <= 0L) continue;
                Good meta = byConfigId == null ? null : byConfigId.get(configId);
                if (meta == null) continue;
                out.add(new ShopShelfItem(shopId, goodsId, configId, meta.currencyId,
                        livePriceOf(goods, meta.price), Exec.longOf(goods.get("buy_count")), meta.limit));
            }
        }
        return out;
    }

    /** 从 shop_info 响应里读每店 refresh_times（用于免费次数/钻石预算判定）。 */
    static Map<Long, Integer> refreshTimesFrom(Map<String, Object> mapInfo) {
        Map<Long, Integer> out = new HashMap<>();
        if (mapInfo == null) return out;
        for (Map.Entry<String, Object> e : mapInfo.entrySet()) {
            long shopId;
            try {
                shopId = Long.parseLong(e.getKey() == null ? "" : e.getKey().trim());
            } catch (Exception ignored) {
                continue;
            }
            Map<String, Object> shopInfo = Exec.mapOf(e.getValue());
            if (shopInfo == null) continue;
            // 只收录响应里真实带 refresh_times 的店铺：缺失=刷新次数未同步，不刷新（防误花钻石）。
            if (!shopInfo.containsKey("refresh_times")) continue;
            out.put(shopId, Math.max(0, (int) Exec.longOf(shopInfo.get("refresh_times"))));
        }
        return out;
    }

    /** 当前刷新费用：0=免费，>0=钻石，-1=超出费用表（不可刷新）。 */
    static long shopRefreshCost(long shopId, int refreshTimes) {
        long[] costs = shopId == SHOP_ID_VENDOR ? VENDOR_REFRESH_COSTS
                : shopId == SHOP_ID_MYSTERIOUS ? MYSTERIOUS_REFRESH_COSTS : null;
        if (costs == null || refreshTimes < 0 || refreshTimes >= costs.length) return -1L;
        return costs[refreshTimes];
    }

    /** 付费刷新门控：必须 allow_diamond 且剩余钻石预算覆盖本次费用。 */
    static boolean diamondRefreshAllowed(ShopBudget budget, long shopId, int refreshTimes,
                                         long diamondSpent) {
        long cost = shopRefreshCost(shopId, refreshTimes);
        if (cost < 0L) return false;
        if (cost == 0L) return true;
        if (budget == null || !budget.allowDiamond()) return false;
        return budget.maxDiamondSpend() - Math.max(0L, diamondSpent) >= cost;
    }

    /** 币种余额：notify_resource_init/produced_resource_init 为绝对值，resource_update 为差值。 */
    static Map<Long, Long> currencyBalances(TaskContext tc) {
        Map<Long, Long> out = new HashMap<>();
        applyResourceList(out, tc.engine.notifyContent("notify_resource_init"));
        applyResourceList(out, tc.engine.notifyContent("notify_produced_resource_init"));
        applyResourceDelta(out, tc.engine.notifyContent("notify_resource_update"));
        applyResourceList(out, tc.engine.notifyContent("notify_produced_resource_update"));
        return out;
    }

    /** 绝对计数列表：content.data[] = [{id,count}]。 */
    static void applyResourceList(Map<Long, Long> out, Map<String, Object> content) {
        if (out == null || content == null) return;
        for (Map<String, Object> item : Exec.mapsOf(content.get("data"))) {
            long id = Exec.longOf(item.get("id"));
            long count = Exec.longOf(item.get("count"));
            if (id > 0L && count >= 0L) out.put(id, count);
        }
    }

    /** 差值更新：content.data[] = [{id,count}]；content.type=="del" 时为减少。 */
    static void applyResourceDelta(Map<Long, Long> out, Map<String, Object> content) {
        if (out == null || content == null) return;
        long sign = "del".equals(String.valueOf(content.get("type"))) ? -1L : 1L;
        for (Map<String, Object> item : Exec.mapsOf(content.get("data"))) {
            long id = Exec.longOf(item.get("id"));
            long delta = sign * Exec.longOf(item.get("count"));
            if (id > 0L) out.merge(id, delta, Long::sum);
        }
    }

    /** pending 对账记录（防超时/断线后重复下单）。 */
    static record ShopPending(long shopId, long goodsId, long configId, long price,
                              long currencyId, long buyCountBefore, long atMillis) {
        String toJson() {
            JSONObject o = new JSONObject();
            o.put("shop_id", shopId);
            o.put("goods_id", goodsId);
            o.put("config_id", configId);
            o.put("price", price);
            o.put("currency_id", currencyId);
            o.put("buy_count_before", buyCountBefore);
            o.put("at", atMillis);
            return o.toString();
        }

        static ShopPending fromJson(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                JSONObject o = new JSONObject(raw);
                long shopId = o.optLong("shop_id", 0L);
                long goodsId = o.optLong("goods_id", 0L);
                if (shopId <= 0L || goodsId <= 0L) return null;
                return new ShopPending(shopId, goodsId, o.optLong("config_id", 0L),
                        o.optLong("price", 0L), o.optLong("currency_id", 0L),
                        o.optLong("buy_count_before", 0L), o.optLong("at", 0L));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static String shopPendingKey(TaskContext tc, String fnId) {
        return "srv:shop:" + tc.fpid + ":" + tc.sid + ":" + fnId + ":pending";
    }

    static ShopPending loadShopPending(TaskContext tc, String fnId) {
        try {
            return ShopPending.fromJson(tc.store.getString(shopPendingKey(tc, fnId)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void saveShopPending(TaskContext tc, String fnId, ShopPending pending) {
        try {
            tc.store.putString(shopPendingKey(tc, fnId), pending == null ? "" : pending.toJson());
        } catch (Throwable ignored) {
        }
    }

    static void clearShopPending(TaskContext tc, String fnId) {
        try {
            tc.store.putString(shopPendingKey(tc, fnId), "");
        } catch (Throwable ignored) {
        }
    }

    /** pending 是否仍在防重窗口内（>= TTL 视为过期可重试）。 */
    static boolean shopPendingFresh(ShopPending pending, long nowMillis) {
        return pending != null && pending.atMillis() > 0L
                && nowMillis - pending.atMillis() >= 0L
                && nowMillis - pending.atMillis() < SHOP_PENDING_TTL_MS;
    }

    /** 货架已购数增加 → 上轮请求已被服务端处理（无论响应是否丢失）。 */
    static boolean shopPendingCompleted(ShopPending pending, long shelfBuyCount) {
        return pending != null && shelfBuyCount > pending.buyCountBefore();
    }

    /** 购买条件类错误码（网页版 DailyShopService.isPurchaseConditionBlocked 同款）。 */
    static boolean isPurchaseConditionBlocked(int code) {
        if (code >= 800 && code <= 830) return true;
        return code == 10521 || code == 40240;
    }

    public static TaskOutcome shopBuy(TaskContext tc) {
        return buyFromShops(tc, SHOP_IDS_TOWN, "shop", "商店购买");
    }

    public static TaskOutcome allianceShop(TaskContext tc) {
        return buyFromShops(tc, SHOP_IDS_ALLIANCE, "alliance_shop", "联盟商店");
    }

    /**
     * 有序勾选清单模式（2026-11-08）：按用户勾选顺序购买，直到货币不足/全部处理完。
     * 清单元素 {shop_id, conf_id, count}；count<=0 时按“限购剩余与余额”尽量买。
     * 售罄（buy_count>=limit）跳过；货币不足停止本轮。
     */
    static TaskOutcome buyListMode(TaskContext tc, long[] shopIds, String fnId, String fnName,
                                   JSONArray buyList) {
        Exec.Multi m = new Exec.Multi();
        try {
            Map<Long, Good> byConfigId = new HashMap<>();
            for (List<Good> gl : shops().values()) {
                for (Good g : gl) {
                    if (g != null && g.configId > 0L) byConfigId.put(g.configId, g);
                }
            }
            Map<Long, Long> balances = currencyBalances(tc);
            int bought = 0;
            for (int i = 0; i < buyList.length(); i++) {
                JSONObject it = buyList.optJSONObject(i);
                if (it == null) continue;
                long shopId = it.optLong("shop_id", 0L);
                long configId = it.optLong("conf_id", 0L);
                if (shopId <= 0L || configId <= 0L) continue;
                if (!containsShop(shopIds, shopId)) continue;
                Good meta = byConfigId.get(configId);
                if (meta == null) {
                    m.skip("商品配置缺失 conf=" + configId);
                    continue;
                }
                List<Object> arr = new ArrayList<>();
                for (long sid : shopIds) arr.add(sid);
                GameMessage info = tc.engine.request("req_shop_info_array", Exec.args("arr_id", arr));
                if (!Exec.ok(info)) {
                    m.skip("货架拉取失败(码" + Exec.codeOf(info) + ")");
                    break;
                }
                Map<String, Object> mapInfo = Exec.mapOf(info.getContent().get("map_info"));
                if (mapInfo == null) {
                    m.skip("货架响应缺商品表");
                    break;
                }
                List<ShopShelfItem> shelf = flattenShelf(mapInfo, byConfigId);
                ShopShelfItem target = null;
                for (ShopShelfItem s : shelf) {
                    if (s.shopId() == shopId && s.configId() == configId) {
                        target = s;
                        break;
                    }
                }
                if (target == null) {
                    m.skip(meta.name + " 当前货架无此商品");
                    continue;
                }
                long remaining = target.remaining();
                if (remaining <= 0L) {
                    m.skip(meta.name + " 已售罄");
                    continue;
                }
                long price = meta.price;
                long bal = balances.getOrDefault(meta.currencyId, 0L);
                long canBuy = price <= 0L ? remaining : Math.min(remaining, Math.max(0L, bal / price));
                if (canBuy <= 0L) {
                    // 用户口径：按勾选顺序购买，直到没有货币
                    m.skip(meta.name + " 余额不足，停止购买");
                    break;
                }
                long want = Math.max(0L, it.optLong("count", 0L));
                long count = want > 0L ? Math.min(want, canBuy) : canBuy;
                if (count <= 0L) continue;
                GameMessage buy = tc.engine.request("req_shop_buy", Exec.args(
                        "shop_id", shopId, "goods_id", target.goodsId(), "count", count));
                int code = Exec.codeOf(buy);
                if (Exec.ok(buy)) {
                    bought++;
                    m.claimed(meta.name + "×" + count);
                    if (meta.currencyId > 0L && price > 0L) {
                        balances.put(meta.currencyId, Math.max(0L, bal - price * count));
                    }
                } else if (isPurchaseConditionBlocked(code)) {
                    m.skip(meta.name + " 条件不满足");
                } else {
                    m.skip(meta.name + " 码" + code);
                }
            }
            if (bought == 0 && m.sb.length() == 0) m.skip("勾选清单无可购商品");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, fnName);
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, fnId, fnName);
    }

    private static boolean containsShop(long[] ids, long id) {
        if (ids == null) return false;
        for (long x : ids) if (x == id) return true;
        return false;
    }

    private static TaskOutcome buyFromShops(TaskContext tc, long[] shopIds,
                                            String fnId, String fnName) {
        Exec.Multi m = new Exec.Multi();
        try {
            JSONObject p = tc.params(fnId);
            // 2026-11-08：有序勾选清单优先（按用户勾选顺序购买，直到没有货币）
            JSONArray buyList = p.optJSONArray(PARAM_BUY_LIST);
            if (buyList != null && buyList.length() > 0) {
                return buyListMode(tc, shopIds, fnId, fnName, buyList);
            }
            Map<String, Long> wantNames = parseBuyNames(p.opt(PARAM_BUY_NAMES));
            LinkedHashMap<Long, Long> wantConfigIds = parseLegacyBuys(p.optJSONObject("buys"));
            boolean nameMode = !wantNames.isEmpty();
            if (!nameMode && wantConfigIds.isEmpty()) {
                m.skip("未勾选商品（设置页配置）");
                return Exec.result(m, fnId, fnName);
            }
            Map<Long, Good> byConfigId = new HashMap<>();
            Map<Long, String> nameByConfigId = new HashMap<>();
            for (List<Good> gl : shops().values()) {
                for (Good g : gl) {
                    if (g == null || g.configId <= 0L) continue;
                    byConfigId.put(g.configId, g);
                    String name = g.name == null ? "" : g.name.trim();
                    if (!name.isEmpty()) nameByConfigId.put(g.configId, name);
                }
            }
            List<Object> arr = new ArrayList<>();
            for (long sid : shopIds) arr.add(sid);
            JSONObject refreshCfg = p.optJSONObject("refresh");
            int refreshRounds = refreshCfg == null ? 0
                    : Math.max(0, Math.min(5, refreshCfg.optInt("rounds", 0)));
            int targetPurchases = refreshCfg == null ? 0
                    : Math.max(0, refreshCfg.optInt("target_purchases", 0));
            ShopBudget budget = ShopBudget.from(refreshCfg);
            // 币种余额：未知币种一律不买（资金安全）；钻石另需显式预算。
            Map<Long, Long> balances = currencyBalances(tc);
            long nowMillis = System.currentTimeMillis();
            ShopPending pending = loadShopPending(tc, fnId);
            if (pending != null && !shopPendingFresh(pending, nowMillis)) {
                clearShopPending(tc, fnId);
                pending = null;
            }
            long diamondSpent = 0L;
            int boughtTotal = 0;
            for (int round = 0; round <= refreshRounds; round++) {
                GameMessage info = tc.engine.request("req_shop_info_array",
                        Exec.args("arr_id", arr));
                if (!Exec.ok(info)) {
                    if (round == 0) m.skip("商店数据拉取失败(码" + Exec.codeOf(info) + ")");
                    break;
                }
                Map<String, Object> mapInfo = Exec.mapOf(info.getContent().get("map_info"));
                if (mapInfo == null) {
                    if (round == 0) m.skip("商店响应缺商品表");
                    break;
                }
                List<ShopShelfItem> shelf = flattenShelf(mapInfo, byConfigId);
                Map<Long, Integer> refreshTimes = refreshTimesFrom(mapInfo);
                int boughtThisRound = 0;
                if (nameMode) {
                    for (String name : wantNames.keySet()) {
                        if (boughtThisRound >= budget.maxBuysPerRound()) break;
                        ShopShelfItem chosen = chooseCheapestVariant(shelf, nameByConfigId, name,
                                balances, budget, diamondSpent);
                        if (chosen == null) continue;
                        boolean[] cleared = new boolean[1];
                        pending = reconcileShopPending(tc, fnId, pending, shelf, nowMillis, cleared);
                        if (pending != null && pending.shopId() == chosen.shopId()
                                && pending.goodsId() == chosen.goodsId()) {
                            m.skip(name + " 上轮购买待对账，跳过防重复");
                            continue;
                        }
                        saveShopPending(tc, fnId, new ShopPending(chosen.shopId(), chosen.goodsId(),
                                chosen.configId(), chosen.price(), chosen.currencyId(),
                                chosen.buyCount(), nowMillis));
                        GameMessage buy = tc.engine.request("req_shop_buy", Exec.args(
                                "shop_id", chosen.shopId(), "goods_id", chosen.goodsId(), "count", 1L));
                        int code = Exec.codeOf(buy);
                        if (Exec.ok(buy)) {
                            clearShopPending(tc, fnId);
                            if (chosen.currencyId() == SHOP_DIAMOND_CURRENCY_ID) {
                                diamondSpent += chosen.price();
                            }
                            boughtThisRound++;
                            m.claimed(name + "×1（" + chosen.price() + "）");
                        } else if (isPurchaseConditionBlocked(code)) {
                            clearShopPending(tc, fnId);
                            m.skip(name + " 条件不满足");
                        } else if (code >= 0) {
                            clearShopPending(tc, fnId);
                            m.skip(name + " 码" + code);
                        } else {
                            // 响应未知（超时/断线）：保留 pending，下一轮/下次运行先对账，不重复下单。
                            m.skip(name + " 响应未知，等待对账");
                        }
                    }
                } else {
                    Set<Long> seenConfigIds = new HashSet<>();
                    for (ShopShelfItem item : shelf) {
                        Long want = wantConfigIds.get(item.configId());
                        if (want == null || want <= 0L) continue;
                        if (!seenConfigIds.add(item.configId())) continue;   // 旧 buys 去重
                        long remaining = item.remaining();
                        if (remaining <= 0L) continue;
                        Good meta = byConfigId.get(item.configId());
                        String label = meta == null || meta.name == null || meta.name.isBlank()
                                ? String.valueOf(item.configId()) : meta.name;
                        if (boughtThisRound >= budget.maxBuysPerRound()) break;
                        if (!shopItemPayable(item, balances, budget, diamondSpent)) {
                            m.skip(label + " 余额/预算不足或余额未知，跳过");
                            continue;
                        }
                        pending = reconcileShopPending(tc, fnId, pending, shelf, nowMillis, new boolean[1]);
                        if (pending != null && pending.shopId() == item.shopId()
                                && pending.goodsId() == item.goodsId()) {
                            m.skip(label + " 上轮购买待对账，跳过防重复");
                            continue;
                        }
                        long count = Math.min(want, remaining);
                        saveShopPending(tc, fnId, new ShopPending(item.shopId(), item.goodsId(),
                                item.configId(), item.price(), item.currencyId(), item.buyCount(),
                                nowMillis));
                        GameMessage buy = tc.engine.request("req_shop_buy", Exec.args(
                                "shop_id", item.shopId(), "goods_id", item.goodsId(), "count", count));
                        int code = Exec.codeOf(buy);
                        if (Exec.ok(buy)) {
                            clearShopPending(tc, fnId);
                            if (item.currencyId() == SHOP_DIAMOND_CURRENCY_ID) {
                                diamondSpent += item.price() * count;
                            }
                            boughtThisRound++;
                            m.claimed(label + "×" + count);
                            m.rewards(label, buy);
                        } else if (isPurchaseConditionBlocked(code)) {
                            clearShopPending(tc, fnId);
                            m.skip(label + " 条件不满足");
                        } else if (code >= 0) {
                            clearShopPending(tc, fnId);
                            m.skip(label + " 码" + code);
                        } else {
                            m.skip(label + " 响应未知，等待对账");
                        }
                    }
                }
                boughtTotal += boughtThisRound;
                if (targetPurchases > 0 && boughtTotal >= targetPurchases) break;
                if (round == refreshRounds) break;
                // 只刷新 801/601；钻石刷新必须显式 allow_diamond + 预算覆盖当次费用。
                long[] refreshShops = refreshableShopIds(shopIds);
                if (refreshShops.length == 0) break;
                boolean refreshed = false;
                for (long shopId : refreshShops) {
                    Integer timesValue = refreshTimes.get(shopId);
                    if (timesValue == null) {
                        m.skip("刷新次数未同步，跳过刷新（店铺" + shopId + "）");
                        break;
                    }
                    int times = timesValue;
                    long cost = shopRefreshCost(shopId, times);
                    if (cost < 0L) break;
                    if (cost > 0L) {
                        if (!diamondRefreshAllowed(budget, shopId, times, diamondSpent)) {
                            m.skip("钻石刷新预算不足，已停止（店铺" + shopId + "）");
                            break;
                        }
                        diamondSpent += cost;   // 仅预算记账；实际扣费以服务端为准
                    }
                    GameMessage rr = tc.engine.request("req_shop_refresh",
                            Exec.args("shop_id", shopId));
                    int rc = Exec.codeOf(rr);
                    if (Exec.ok(rr)) {
                        refreshed = true;
                        refreshTimes.put(shopId, times + 1);
                    } else if (rc == 51) {
                        m.skip("刷新需钻石且预算未通过，已停止（店铺" + shopId + "）");
                        break;
                    }
                }
                if (!refreshed) break;
            }
            if (boughtTotal == 0 && m.sb.length() == 0) m.skip("货架无勾选商品");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, fnName);
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, fnId, fnName);
    }

    /**
     * pending 对账：若货架显示该商品已购数增加 → 上轮请求已被服务端处理，清 pending；
     * 过期 pending 直接清理（允许重试）；窗口内的 pending 原样返回（本轮不再对同商品下单）。
     */
    static ShopPending reconcileShopPending(TaskContext tc, String fnId, ShopPending pending,
                                            List<ShopShelfItem> shelf, long nowMillis,
                                            boolean[] clearedOut) {
        if (pending == null) return null;
        if (!shopPendingFresh(pending, nowMillis)) {
            clearShopPending(tc, fnId);
            return null;
        }
        if (shelf != null) {
            for (ShopShelfItem item : shelf) {
                if (item.shopId() == pending.shopId() && item.goodsId() == pending.goodsId()
                        && shopPendingCompleted(pending, item.buyCount())) {
                    clearShopPending(tc, fnId);
                    if (clearedOut != null && clearedOut.length > 0) clearedOut[0] = true;
                    return null;
                }
            }
        }
        return pending;
    }

    // ================= 烈焰与獠牙 =================

    public static TaskOutcome flamefangs(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            JSONObject p = tc.params("act_flamefangs");
            JSONObject buys = p.optJSONObject("buys");
            Map<String, Object> ni = tc.engine.notifyContent("notify_spar_leak_info");
            Map<String, Object> base = ni == null ? null : Exec.mapOf(ni.get("base"));
            long actId = base == null ? 0 : Exec.longOf(base.get("id"));
            long tableId = ni == null ? 0 : Exec.longOf(ni.get("table_id"));
            long stage = base == null ? 0 : Exec.longOf(base.get("cur_stage_idx"));
            if (ni == null || actId <= 0 || tableId <= 0 || (stage != 2 && stage != 3)) {
                m.skip("兑换未开放（活动进行/兑换期才可买）");
                return Exec.result(m, "act_flamefangs", "烈焰獠牙");
            }
            if (buys == null || buys.length() == 0) {
                m.skip("未勾选兑换项（设置页配置）");
                return Exec.result(m, "act_flamefangs", "烈焰獠牙");
            }
            Map<Long, Long> bought = new HashMap<>();
            Object bi = ni.get("buy_info");
            if (bi instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) bi).entrySet()) {
                    try {
                        bought.put(Long.parseLong(String.valueOf(e.getKey())),
                                Exec.longOf(e.getValue()));
                    } catch (Exception ignored) {
                    }
                }
            } else {
                for (Map<String, Object> x : Exec.mapsOf(bi)) {
                    bought.put(Exec.longOf(x.get("id")), Exec.longOf(x.get("count")));
                }
            }
            Map<Long, LeakGood> catalog = new HashMap<>();
            for (LeakGood l : leakGoods()) {
                if (l.tableId == tableId) catalog.put(l.index, l);
            }
            int ok = 0;
            for (Iterator<String> it = buys.keys(); it.hasNext(); ) {
                long index = Long.parseLong(it.next());
                LeakGood meta = catalog.get(index);
                if (meta == null) continue;
                long w = buys.optLong(String.valueOf(index));
                long remaining = Math.max(0, meta.limit - bought.getOrDefault(index, 0L));
                long count = Math.min(w, remaining);
                if (count <= 0) continue;
                GameMessage r = tc.engine.request("req_spar_leak_buy", Exec.args(
                        "activity_id", actId, "index", index, "count", count));
                if (Exec.ok(r)) {
                    ok++;
                    m.claimed("档" + index + "×" + count);
                } else {
                    m.skip("档" + index + "码" + Exec.codeOf(r));
                }
            }
            if (ok == 0 && m.sb.length() == 0) m.skip("本轮无可兑换");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "烈焰獠牙");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_flamefangs", "烈焰獠牙");
    }

    // ================= 社交礼物 =================

    public static TaskOutcome socialGift(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            JSONObject p = tc.params("social_gift");
            JSONArray targets = p.optJSONArray("targets");
            long giftId = p.optLong("gift_id", 0);
            long count = p.optLong("count", 1);
            if (targets == null || targets.length() == 0 || giftId <= 0) {
                m.skip("未配置收礼人或礼物（设置页配置）");
                return Exec.result(m, "social_gift", "社交礼物");
            }
            for (Gift g : gifts()) {
                if (g.giftId == giftId && !g.amountOptions.isEmpty()
                        && !g.amountOptions.contains(count)) {
                    m.skip("数量 " + count + " 不在可选档位 " + g.amountOptions + "（" + g.name + "）");
                    return Exec.result(m, "social_gift", "社交礼物");
                }
            }
            tc.engine.request("req_social_gift_set_disclaimer", null);
            int sent = 0;
            for (int i = 0; i < targets.length(); i++) {
                String fid = targets.optString(i, "").trim();
                if (fid.isEmpty()) continue;
                GameMessage lookup = tc.engine.request("req_player_info_by_fid",
                        Exec.args("fid", fid));
                Map<String, Object> r = Exec.ok(lookup)
                        ? Exec.mapOf(lookup.getContent().get("result")) : null;
                long uid = r == null ? 0 : Exec.longOf(r.get("uid"));
                if (uid <= 0) {
                    m.skip("FID " + fid + " 未找到");
                    continue;
                }
                GameMessage send = tc.engine.request("req_social_gift_send", Exec.args(
                        "gift_id", giftId, "target_uid", uid, "count", count,
                        "anonymous", false, "allow_buy", false));
                int code = Exec.codeOf(send);
                if (Exec.ok(send)) {
                    sent++;
                    m.claimed("→" + (r.get("nickName") != null ? r.get("nickName") : uid));
                } else if (code == 41566) {
                    tc.engine.request("req_social_gift_set_disclaimer", null);
                    m.skip("需同意玩家须知（已重试同意，下轮生效）");
                } else if (code == 41558) {
                    m.skip("礼物未解锁");
                } else {
                    m.skip("FID" + fid + "码" + code);
                }
            }
            if (sent == 0 && m.sb.length() == 0) m.skip("未送出");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "社交礼物");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "social_gift", "社交礼物");
    }

    // ================= 雪原贸易站（活动族 2702；与 App ShopTasks.snowTrade 2026-10-14 版一致） =================
    //
    // notify_value_exchange_init.ve_info = {id, exchange_data:{idx:已兑次数}, is_free_exchange}。
    // 购买：req_value_exchange_exchange{idx,count}；免费兑：req_value_exchange_free_exchange{idx}。
    // 参数：p:act_snowtrade.free_idx（免费档，默认 132）、buys={idx:目标数量}。
    // 注：seen_idx 缓存是 App 功能页勾选清单的适配数据，服务器参数为同步副本只读，不写。

    public static TaskOutcome snowTrade(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            boolean onFree = tc.subOn("act_snowtrade", "free", true);
            boolean onBuy = tc.subOn("act_snowtrade", "buy", false);
            if (!onFree && !onBuy) {
                m.skip("子开关已全部关闭");
                m.subSkip("free", "已关闭");
                m.subSkip("buy", "已关闭");
                return Exec.result(m, "act_snowtrade", "雪原贸易站");
            }
            tc.engine.collectNotifies(1500);
            JSONObject p = tc.params("act_snowtrade");
            Map<String, Object> vi = tc.engine.notifyData("notify_value_exchange_init");
            Map<String, Object> ve = Exec.mapOf(vi == null ? null : vi.get("ve_info"));
            long actId = ve == null ? 0 : Exec.longOf(ve.get("id"));
            if (ve == null || actId <= 0) {
                tc.saveModText("act_snowtrade", "free", "活动未开放（无 ve_info 推送）");
                tc.saveModText("act_snowtrade", "buy", "活动未开放（无 ve_info 推送）");
                m.skip("雪原贸易站未开放（ve_info 未同步）");
                m.subSkip("free", "活动未开放");
                m.subSkip("buy", "活动未开放");
                return Exec.result(m, "act_snowtrade", "雪原贸易站");
            }
            // 已兑次数表（外层键形如 "132.0"，值形如 {k:132,v:0}；兼容纯数字形状）
            java.util.Map<Long, Long> bought = new java.util.HashMap<>();
            Object ed = ve.get("exchange_data");
            if (ed instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) ed).entrySet()) {
                    try {
                        long idx = Long.parseLong(String.valueOf(e.getKey()).replace(".0", ""));
                        Object vObj = e.getValue();
                        long cnt;
                        if (vObj instanceof Map) {
                            Map<String, Object> vm = Exec.mapOf(vObj);
                            cnt = Exec.longOf(vm != null && vm.containsKey("v") ? vm.get("v") : vObj);
                        } else {
                            cnt = Exec.longOf(vObj);
                        }
                        bought.put(idx, cnt);
                    } catch (Exception ignored) {
                    }
                }
            }
            boolean freeAvail = "true".equals(String.valueOf(ve.get("is_free_exchange")));
            tc.saveModText("act_snowtrade", "free", "活动" + actId + " · 免费兑"
                    + (freeAvail ? "可用" : "不可用") + " · 已兑 " + bought);
            // ① 免费兑（默认档 132；兑错档由服务端报错跳过，无副作用）
            if (!onFree) {
                m.subSkip("free", "已关闭");
            } else if (!freeAvail) {
                m.skip("免费兑不可用或已用");
                m.subSkip("free", "不可用");
            } else {
                long freeIdx = p.optLong("free_idx", 132);
                if (bought.getOrDefault(freeIdx, 0L) > 0) {
                    m.skip("免费档" + freeIdx + "本角色已兑×" + bought.get(freeIdx));
                    m.subSkip("free", "本角色已兑");
                } else {
                    GameMessage fr = tc.engine.request("req_value_exchange_free_exchange",
                            Exec.args("idx", freeIdx));
                    if (Exec.ok(fr)) {
                        m.claimed("免费兑档" + freeIdx);
                        m.subOk("free", "已兑档" + freeIdx);
                    } else {
                        m.skip("免费兑码" + Exec.codeOf(fr));
                        m.subSkip("free", "码" + Exec.codeOf(fr));
                    }
                }
            }
            // ② 按清单兑换（buys: idx→目标数量；已兑部分自动扣减）
            if (!onBuy) {
                m.subSkip("buy", "已关闭");
            } else {
                JSONObject buys = p.optJSONObject("buys");
                if (buys == null || buys.length() == 0) {
                    tc.saveModText("act_snowtrade", "buy", "清单未配置（已知档位 " + bought.keySet() + "）");
                    m.skip("未配置 buys");
                    m.subSkip("buy", "未配置");
                } else {
                    int ok = 0;
                    StringBuilder buySt = new StringBuilder();
                    java.util.Iterator<String> it = buys.keys();
                    while (it.hasNext()) {
                        String idxS = it.next();
                        long idx;
                        try {
                            idx = Long.parseLong(idxS.replace(".0", ""));
                        } catch (NumberFormatException nfe) {
                            continue;
                        }
                        long want = Math.max(1, buys.optLong(idxS, 1));
                        long had = bought.getOrDefault(idx, 0L);
                        long count = Math.max(0, want - had);
                        if (buySt.length() > 0) buySt.append(" · ");
                        buySt.append("档").append(idx).append(" ").append(had).append("/").append(want);
                        if (count == 0) {
                            buySt.append(" 已足");
                            continue;
                        }
                        GameMessage r = tc.engine.request("req_value_exchange_exchange",
                                Exec.args("idx", idx, "count", count));
                        if (Exec.ok(r)) {
                            ok++;
                            m.claimed("档" + idx + "×" + count);
                            buySt.append("→补").append(count);
                        } else {
                            m.skip("档" + idx + "码" + Exec.codeOf(r));
                            buySt.append("→码").append(Exec.codeOf(r));
                        }
                    }
                    tc.saveModText("act_snowtrade", "buy",
                            buySt.length() == 0 ? "清单无可识别档位" : buySt.toString());
                    if (ok == 0 && m.sb.length() == 0) m.skip("本轮无可兑换");
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "雪原贸易站");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_snowtrade", "雪原贸易站");
    }

    // ================= 燃霜矿区 =================

    public static final String[] FROSTFIRE_SLOTS = {"12:30", "16:00", "19:00", "20:30", "22:00"};

    public static TaskOutcome frostfire(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            JSONObject p = tc.params("act_frostfire");
            JSONArray slots = p.optJSONArray("slots");
            if (slots == null || slots.length() == 0) {
                m.skip("未选择报名时段（设置页配置）");
                return Exec.result(m, "act_frostfire", "燃霜矿区");
            }
            long slot = slots.optLong(0);
            Map<String, Object> wi = tc.engine.notifyContent("notify_frostfire_war_info");
            Map<String, Object> actInfo = wi == null ? null : Exec.mapOf(wi.get("act_info"));
            long signUpIndex = actInfo == null ? 0 : Exec.longOf(actInfo.get("sign_up_index"));
            long signUpTs = actInfo == null ? 0 : Exec.longOf(actInfo.get("sign_up_ts"));
            boolean signedToday = signUpIndex > 0 && signUpTs > 0
                    && System.currentTimeMillis() / 86400000L == signUpTs / 86400L;
            if (signedToday) {
                m.skip("今日已报名(" + signUpIndex + ")");
            } else {
                GameMessage apply = tc.engine.request("req_frostfire_war_apply",
                        Exec.args("index", slot));
                int code = Exec.codeOf(apply);
                if (Exec.ok(apply)) {
                    m.claimed("已报名 " + FROSTFIRE_SLOTS[(int) Math.max(0, slot - 1)]);
                } else if (code == 10724) {
                    GameMessage exit = tc.engine.request("req_frostfire_war_exit_warzone", null);
                    int ec = Exec.codeOf(exit);
                    if (ec == 0 || ec == 10714) {
                        GameMessage retry = tc.engine.request("req_frostfire_war_apply",
                                Exec.args("index", slot));
                        if (Exec.ok(retry)) m.claimed("退旧场后已报名");
                        else m.skip("重报名码" + Exec.codeOf(retry));
                    } else {
                        m.skip("退场码" + ec);
                    }
                } else {
                    m.skip(code == 10717 ? "未到报名时间" : "报名码" + code);
                }
            }
            Map<String, Object> pi = tc.engine.notifyContent("notify_frostfire_war_player_init");
            long state = pi == null ? 0 : Exec.longOf(pi.get("state"));
            if (state == 2 || state == 3) {
                for (int idx = 1; idx <= 5; idx++) {
                    GameMessage r = tc.engine.request("req_frostfire_war_get_collect_reward",
                            Exec.args("idx", idx));
                    int code = Exec.codeOf(r);
                    if (Exec.ok(r)) m.claimed("里程碑" + idx);
                    else if (code != 10746 && code != 10748) break;
                }
                GameMessage rank = tc.engine.request("req_frostfire_war_self_score_rank", null);
                if (Exec.ok(rank)) {
                    Object self = rank.getContent().get("self_value");
                    if (self != null) m.claimed("当前积分" + self);
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "燃霜矿区");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_frostfire", "燃霜矿区");
    }

    // ================= 联盟总动员（L3 最小状态机） =================

    /**
     * 总动员「购买接取次数」决策结果。allowed=false 时 reason 会作为 SKIP 文案展示。
     */
    static record MobilizationCountBuy(boolean allowed, String reason) {
    }

    /**
     * 旧签名保留：批次2 单测锁定「默认不买」。免费首购判定见
     * {@link #mobilizationFreeCountBuyDecision(MobilizationState, long, JSONObject)}。
     */
    static MobilizationCountBuy mobilizationCountBuyDecision(JSONObject params) {
        if (params != null && params.optBoolean("allow_paid_count", false)) {
            return new MobilizationCountBuy(false,
                    "总动员购买次数已跳过：服务端未接价目/状态机，allow_paid_count 暂不生效（安全跳过）");
        }
        return new MobilizationCountBuy(false,
                "总动员购买次数已跳过：服务端未接价目/状态机，无法确认免费首购（安全跳过）");
    }

    static final int MOB_CODE_NOT_OPEN = 7401;
    static final int MOB_CODE_NO_QUEST = 8409;
    static final int MOB_CODE_QUEST_NOT_DONE = 8410;
    static final int MOB_CODE_NO_MILESTONE = 8413;
    /** 网页版 catalog/alliance-mobilization.json purchaseCosts[0]==0（首购免费，仅此一次允许购买）。 */
    static final long MOB_FIRST_PURCHASE_COST = 0L;
    static final long MOB_EMPTY_MEMORY_MS = 300_000L;
    static final long MOB_OPEN_INFO_7401_RETRY_MS = 3_600_000L;
    static final long MOB_OPEN_INFO_OTHER_RETRY_MS = 300_000L;
    static final int MOB_REFRESH_HOUR_LIMIT_DEFAULT = 12;
    static final int MOB_REFRESH_DAY_LIMIT_DEFAULT = 100;

    /** 推送来源（生产用 engine::notifyData；单测用 Map 注入）。 */
    interface MobilizationNotifySource {
        Map<String, Object> notify(String proto);
    }

    /** 总动员最小状态（从 notify_mobilization_* 推送增量维护，见网页版 DailyAllianceMobilizationState）。 */
    static final class MobilizationState {
        long activityId;
        final List<Long> stageTimestamps = new ArrayList<>();
        boolean boardInitialized;
        long allianceId;
        long participantAllianceId;
        boolean allianceJoined;
        long playerScore;
        int remainingAcceptCount;
        int dailyPurchaseCount;
        long questId;
        long questProgress;
        long questEndTime;
        int questPosition;
        final Map<Integer, Long> publicPositions = new LinkedHashMap<>();
        final Map<Integer, Long> personalPositions = new LinkedHashMap<>();

        /** stage：1=未开启 2=预告 3=开启中 4=结算；activity/阶段缺失按 1 处理。 */
        int stage(long nowSec) {
            if (activityId <= 0L || stageTimestamps.size() < 3) return 1;
            if (nowSec < stageTimestamps.get(0)) return 1;
            if (nowSec < stageTimestamps.get(1)) return 2;
            return nowSec < stageTimestamps.get(2) ? 3 : 4;
        }

        boolean active(long nowSec) {
            return stage(nowSec) == 3;
        }

        boolean boardAvailable(long nowSec) {
            int stage = stage(nowSec);
            return stage == 3 || stage == 4;
        }

        boolean inAlliance() {
            return allianceId > 0L;
        }

        boolean hasQuest() {
            return questId > 0L;
        }
    }

    static List<Object> valuesOf(Object raw) {
        if (raw instanceof Map) return new ArrayList<>(((Map<?, ?>) raw).values());
        if (raw instanceof java.util.Collection) return new ArrayList<>((java.util.Collection<?>) raw);
        return new ArrayList<>();
    }

    static boolean boolOf(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).longValue() != 0L;
        return "true".equalsIgnoreCase(String.valueOf(raw)) || "1".equals(String.valueOf(raw));
    }

    static int mobPositionOf(Object key) {
        long value = Exec.longOf(key);
        if (value <= 0L) {
            try {
                value = (long) Double.parseDouble(String.valueOf(key).trim());
            } catch (Exception ignored) {
            }
        }
        return value > 0L && value <= 200L ? (int) value : 0;
    }

    static void putMobPosition(Map<Integer, Long> target, int pos, Object raw, boolean keepEmpty) {
        if (target == null || pos <= 0) return;
        Map<String, Object> data = Exec.mapOf(raw);
        long taskId = data == null ? 0L : Exec.longOf(data.get("id"));
        if (taskId > 0L || keepEmpty) target.put(pos, taskId);
        else target.remove(pos);
    }

    /** 解析 positions：Map{pos:{id}} 或 List[{id}]（index=pos，从 1 起）。 */
    static Map<Integer, Long> parseMobPositions(Object raw, boolean keepEmpty) {
        LinkedHashMap<Integer, Long> out = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                int pos = mobPositionOf(entry.getKey());
                if (pos > 0) putMobPosition(out, pos, entry.getValue(), keepEmpty);
            }
        } else {
            int pos = 1;
            for (Object value : valuesOf(raw)) putMobPosition(out, pos++, value, keepEmpty);
        }
        return out;
    }

    static void applyMobActivity(MobilizationState st, Object raw) {
        Map<String, Object> activity = Exec.mapOf(raw);
        if (activity == null) return;
        long id = Exec.longOf(activity.get("id"));
        if (id > 0L) st.activityId = id;
        st.stageTimestamps.clear();
        for (Object value : valuesOf(activity.get("stage_ts"))) {
            long ts = Exec.longOf(value);
            if (ts > 0L) st.stageTimestamps.add(ts);
        }
    }

    static void applyMobQuest(MobilizationState st, Object raw) {
        Map<String, Object> quest = Exec.mapOf(raw);
        long id = quest == null ? 0L : Exec.longOf(quest.get("id"));
        st.questId = Math.max(0L, id);
        st.questProgress = id > 0L ? Math.max(0L, Exec.longOf(quest.get("progress"))) : 0L;
        st.questEndTime = id > 0L ? Math.max(0L, Exec.longOf(quest.get("endtime"))) : 0L;
        st.questPosition = id > 0L ? Math.max(0, (int) Exec.longOf(quest.get("pos"))) : 0;
    }

    static void applyMobAlliance(MobilizationState st, Object raw) {
        Map<String, Object> alliance = Exec.mapOf(raw);
        if (alliance == null) return;
        long aid = Exec.longOf(alliance.get("aid"));
        if (aid > 0L) st.allianceId = aid;
        st.participantAllianceId = Math.max(st.participantAllianceId, st.allianceId);
        st.allianceJoined = st.allianceJoined || boolOf(alliance.get("is_join")) || aid > 0L;
    }

    static void applyMobAllianceIdentity(MobilizationState st, Map<String, Object> data) {
        if (data == null) return;
        Map<String, Object> player = Exec.mapOf(data.get("player"));
        long aid = player == null ? Exec.longOf(data.get("aid")) : Exec.longOf(player.get("aid"));
        if (aid > 0L) {
            st.allianceId = aid;
            st.participantAllianceId = aid;
            st.allianceJoined = true;
        }
    }

    static void applyMobPosition(MobilizationState st, Map<String, Object> data, boolean personal) {
        if (data == null) return;
        int pos = Math.max(0, (int) Exec.longOf(data.get("pos")));
        if (pos <= 0) return;
        Map<String, Object> position = Exec.mapOf(data.get("position"));
        long taskId = position == null ? 0L : Exec.longOf(position.get("id"));
        if (taskId <= 0L) taskId = Exec.longOf(data.get("id"));
        (personal ? st.personalPositions : st.publicPositions).put(pos, taskId);
    }

    /** 公共任务板静态数据（网页版 req_mobilization_open_info 的 fallback 路径）。 */
    static void applyMobOpenInfo(MobilizationState st, Map<String, Object> content) {
        if (content == null) return;
        applyMobActivity(st, content.get("activity"));
        st.publicPositions.clear();
        st.publicPositions.putAll(parseMobPositions(content.get("positions"), false));
        st.boardInitialized = true;
    }

    /** 从推送增量装配最小状态（生产入口）。 */
    static MobilizationState readMobilizationState(MobilizationNotifySource src) {
        MobilizationState st = new MobilizationState();
        if (src == null) return st;
        Map<String, Object> init = src.notify("notify_mobilization_init");
        if (init != null) applyMobInit(st, init);
        applyMobAllianceIdentity(st, src.notify("notify_alliance_init"));
        applyMobAllianceIdentity(st, src.notify("notify_alliance_player_update"));
        Map<String, Object> activityUpdate = src.notify("notify_mobilization_activity_update");
        if (activityUpdate != null) applyMobActivity(st, activityUpdate.get("activity"));
        Map<String, Object> questUpdate = src.notify("notify_mobilization_quest_update");
        if (questUpdate != null) applyMobQuest(st, questUpdate.get("quest"));
        Map<String, Object> personalPos = src.notify("notify_mobilization_personal_position_update");
        if (personalPos != null) applyMobPosition(st, personalPos, true);
        Map<String, Object> publicPos = src.notify("notify_mobilization_position_update");
        if (publicPos != null) applyMobPosition(st, publicPos, false);
        Map<String, Object> mobAlliance = src.notify("notify_mobilization_alliance_init");
        if (mobAlliance != null) applyMobAlliance(st, mobAlliance.get("alliance"));
        Map<String, Object> scoreUpdate = src.notify("notify_mobilization_score_update");
        if (scoreUpdate != null) st.playerScore = Math.max(0L, Exec.longOf(scoreUpdate.get("score")));
        return st;
    }

    static void applyMobInit(MobilizationState st, Map<String, Object> init) {
        applyMobActivity(st, init.get("activity"));
        applyMobAlliance(st, init.get("alliance"));
        Map<String, Object> player = Exec.mapOf(init.get("player"));
        if (player == null) return;
        st.remainingAcceptCount = Math.max(0, (int) Exec.longOf(player.get("count")));
        st.playerScore = Math.max(0L, Exec.longOf(player.get("score")));
        Map<String, Object> dayData = Exec.mapOf(player.get("day_data"));
        if (dayData != null) st.dailyPurchaseCount = Math.max(0, (int) Exec.longOf(dayData.get("count")));
        long aid = Exec.longOf(player.get("aid"));
        if (aid > 0L) st.participantAllianceId = aid;
        applyMobQuest(st, player.get("quest"));
        st.personalPositions.clear();
        st.personalPositions.putAll(parseMobPositions(player.get("positions"), true));
    }

    static String mobilizationStateSkipReason(MobilizationState st, long nowSec) {
        if (st == null || st.activityId <= 0L) return "总动员未开放或未同步";
        if (!st.inAlliance()) return "当前未加入联盟，总动员不执行";
        if (st.stage(nowSec) <= 2) return "总动员尚未进入开启阶段";
        return null;
    }

    /**
     * 购买接取次数：只允许「cost=0 的免费首购」（网页版 purchaseCosts[0]==0 且 dailyPurchaseCount==0）。
     * 付费购买一律拒绝（零新增钻石消耗路径）；已有接取次数时保留首购，先接任务。
     */
    static MobilizationCountBuy mobilizationFreeCountBuyDecision(MobilizationState st, long nowSec,
                                                                  JSONObject params) {
        if (st == null) return new MobilizationCountBuy(false, "总动员状态未同步，跳过购买次数");
        if (params != null && params.optBoolean("allow_paid_count", false)) {
            return new MobilizationCountBuy(false,
                    "付费购买接取次数未开放：仅免费首购(cost=0)允许（allow_paid_count 不生效）");
        }
        if (!st.active(nowSec)) return new MobilizationCountBuy(false, "非总动员开启阶段，跳过购买次数");
        if (st.hasQuest()) return new MobilizationCountBuy(false, "已有进行中任务，跳过购买次数");
        if (st.remainingAcceptCount > 0) {
            return new MobilizationCountBuy(false, "当前已有接取次数，保留免费首购稍后使用");
        }
        if (st.dailyPurchaseCount != 0) {
            return new MobilizationCountBuy(false, "今日已购买过接取次数，仅首购免费，跳过");
        }
        if (MOB_FIRST_PURCHASE_COST != 0L) {
            return new MobilizationCountBuy(false, "首购价目非免费，跳过");
        }
        return new MobilizationCountBuy(true, "免费首购(cost=0)");
    }

    /** 总动员检查点：空奖记忆 + open_info 探测节流（避免 7401 每轮打）。 */
    static record MobilizationCheckpoint(long emptyQuestUntil, String emptyQuestFp,
                                         long emptyMilestoneUntil, String emptyMilestoneFp,
                                         long lastOpenInfoAt, int lastOpenInfoCode) {
        static final MobilizationCheckpoint EMPTY =
                new MobilizationCheckpoint(0L, "", 0L, "", 0L, 0);

        MobilizationCheckpoint rememberQuestEmpty(String fp, long nowMillis) {
            return new MobilizationCheckpoint(nowMillis + MOB_EMPTY_MEMORY_MS,
                    fp == null ? "" : fp, emptyMilestoneUntil, emptyMilestoneFp,
                    lastOpenInfoAt, lastOpenInfoCode);
        }

        MobilizationCheckpoint clearQuestEmpty() {
            return new MobilizationCheckpoint(0L, "", emptyMilestoneUntil, emptyMilestoneFp,
                    lastOpenInfoAt, lastOpenInfoCode);
        }

        MobilizationCheckpoint rememberMilestoneEmpty(String fp, long nowMillis) {
            return new MobilizationCheckpoint(emptyQuestUntil, emptyQuestFp,
                    nowMillis + MOB_EMPTY_MEMORY_MS, fp == null ? "" : fp,
                    lastOpenInfoAt, lastOpenInfoCode);
        }

        MobilizationCheckpoint clearMilestoneEmpty() {
            return new MobilizationCheckpoint(emptyQuestUntil, emptyQuestFp, 0L, "",
                    lastOpenInfoAt, lastOpenInfoCode);
        }

        MobilizationCheckpoint rememberOpenInfo(long atMillis, int code) {
            return new MobilizationCheckpoint(emptyQuestUntil, emptyQuestFp,
                    emptyMilestoneUntil, emptyMilestoneFp, atMillis, code);
        }

        String toJson() {
            JSONObject o = new JSONObject();
            o.put("empty_quest_until", emptyQuestUntil);
            o.put("empty_quest_fp", emptyQuestFp == null ? "" : emptyQuestFp);
            o.put("empty_milestone_until", emptyMilestoneUntil);
            o.put("empty_milestone_fp", emptyMilestoneFp == null ? "" : emptyMilestoneFp);
            o.put("last_open_info_at", lastOpenInfoAt);
            o.put("last_open_info_code", lastOpenInfoCode);
            return o.toString();
        }

        static MobilizationCheckpoint fromJson(String raw) {
            if (raw == null || raw.isBlank()) return EMPTY;
            try {
                JSONObject o = new JSONObject(raw);
                return new MobilizationCheckpoint(o.optLong("empty_quest_until", 0L),
                        o.optString("empty_quest_fp", ""),
                        o.optLong("empty_milestone_until", 0L),
                        o.optString("empty_milestone_fp", ""),
                        o.optLong("last_open_info_at", 0L),
                        o.optInt("last_open_info_code", 0));
            } catch (Exception ignored) {
                return EMPTY;
            }
        }
    }

    static String mobCheckpointKey(TaskContext tc) {
        return "srv:mobilization:" + tc.fpid + ":" + tc.sid;
    }

    static MobilizationCheckpoint loadMobCheckpoint(TaskContext tc) {
        try {
            return MobilizationCheckpoint.fromJson(tc.store.getString(mobCheckpointKey(tc)));
        } catch (Throwable ignored) {
            return MobilizationCheckpoint.EMPTY;
        }
    }

    static void saveMobCheckpoint(TaskContext tc, MobilizationCheckpoint cp) {
        try {
            tc.store.putString(mobCheckpointKey(tc), cp == null ? "" : cp.toJson());
        } catch (Throwable ignored) {
        }
    }

    static boolean mobQuestMemoryActive(MobilizationCheckpoint cp, String fingerprint,
                                        long nowMillis) {
        return cp != null && cp.emptyQuestUntil() > nowMillis
                && fingerprint != null && !fingerprint.isEmpty()
                && fingerprint.equals(cp.emptyQuestFp());
    }

    static boolean mobMilestoneMemoryActive(MobilizationCheckpoint cp, String fingerprint,
                                            long nowMillis) {
        return cp != null && cp.emptyMilestoneUntil() > nowMillis
                && fingerprint != null && !fingerprint.isEmpty()
                && fingerprint.equals(cp.emptyMilestoneFp());
    }

    /** 7401 上一次探测在 1 小时内 → 不再探测；其它失败码 5 分钟内不重复探测。 */
    static boolean shouldProbeOpenInfo(MobilizationCheckpoint cp, long nowMillis) {
        if (cp == null || cp.lastOpenInfoAt() <= 0L) return true;
        long elapsed = Math.max(0L, nowMillis - cp.lastOpenInfoAt());
        long wait = cp.lastOpenInfoCode() == MOB_CODE_NOT_OPEN
                ? MOB_OPEN_INFO_7401_RETRY_MS : MOB_OPEN_INFO_OTHER_RETRY_MS;
        return elapsed >= wait;
    }

    static String mobQuestFingerprint(MobilizationState st) {
        return st == null ? "" : st.activityId + "#" + st.questId + "#" + st.questProgress;
    }

    static String mobMilestoneFingerprint(MobilizationState st, long nowSec) {
        if (st == null) return "";
        int stage = st.stage(nowSec);
        return st.activityId + "#" + stage + "#" + st.allianceId + "#" + st.playerScore;
    }

    static int firstPositionWithTask(Map<Integer, Long> positions) {
        int best = 0;
        if (positions == null) return 0;
        for (Map.Entry<Integer, Long> entry : positions.entrySet()) {
            Integer pos = entry.getKey();
            Long taskId = entry.getValue();
            if (pos == null || pos <= 0 || taskId == null || taskId <= 0L) continue;
            if (best == 0 || pos < best) best = pos;
        }
        return best;
    }

    /** 刷新限频预算（每小时/每日；默认关闭时不会写入）。 */
    static record MobilizationRefreshBudget(long hourKey, int hourCount,
                                            long dayKey, int dayCount) {
        static final MobilizationRefreshBudget EMPTY =
                new MobilizationRefreshBudget(0L, 0, 0L, 0);

        String toJson() {
            JSONObject o = new JSONObject();
            o.put("hour_key", hourKey);
            o.put("hour_count", hourCount);
            o.put("day_key", dayKey);
            o.put("day_count", dayCount);
            return o.toString();
        }

        static MobilizationRefreshBudget fromJson(String raw) {
            if (raw == null || raw.isBlank()) return EMPTY;
            try {
                JSONObject o = new JSONObject(raw);
                return new MobilizationRefreshBudget(o.optLong("hour_key", 0L),
                        o.optInt("hour_count", 0), o.optLong("day_key", 0L),
                        o.optInt("day_count", 0));
            } catch (Exception ignored) {
                return EMPTY;
            }
        }
    }

    static long mobHourKey(long nowMillis) {
        return nowMillis / 3_600_000L;
    }

    static long mobDayKey(long nowMillis) {
        return (nowMillis + 8L * 3_600_000L) / 86_400_000L;   // 北京时间自然日
    }

    static MobilizationRefreshBudget mobRefreshNormalized(MobilizationRefreshBudget budget,
                                                          long nowMillis) {
        if (budget == null) budget = MobilizationRefreshBudget.EMPTY;
        long hourKey = mobHourKey(nowMillis);
        long dayKey = mobDayKey(nowMillis);
        int hourCount = budget.hourKey() == hourKey ? Math.max(0, budget.hourCount()) : 0;
        int dayCount = budget.dayKey() == dayKey ? Math.max(0, budget.dayCount()) : 0;
        return new MobilizationRefreshBudget(hourKey, hourCount, dayKey, dayCount);
    }

    static boolean mobRefreshAllowed(MobilizationRefreshBudget budget, long nowMillis,
                                     int hourLimit, int dayLimit) {
        MobilizationRefreshBudget normalized = mobRefreshNormalized(budget, nowMillis);
        return normalized.hourCount() < Math.max(1, hourLimit)
                && normalized.dayCount() < Math.max(1, dayLimit);
    }

    static MobilizationRefreshBudget mobRefreshRecorded(MobilizationRefreshBudget budget,
                                                        long nowMillis) {
        MobilizationRefreshBudget normalized = mobRefreshNormalized(budget, nowMillis);
        return new MobilizationRefreshBudget(normalized.hourKey(), normalized.hourCount() + 1,
                normalized.dayKey(), normalized.dayCount() + 1);
    }

    static String mobRefreshKey(TaskContext tc) {
        return "srv:mobilization:" + tc.fpid + ":" + tc.sid + ":refresh";
    }

    /** 刷新（默认关；分个人/公共，独立每小时/每日限频）。 */
    static void handleMobilizationRefresh(TaskContext tc, Exec.Multi m, MobilizationState st,
                                          JSONObject p, long nowSec) throws Exception {
        boolean personal = tc.subOn("alliance_mobilization", "refresh_personal", false);
        boolean pub = tc.subOn("alliance_mobilization", "refresh_public", false);
        if (!personal && !pub) return;
        if (st == null || !st.active(nowSec)) return;
        MobilizationRefreshBudget budget;
        try {
            budget = MobilizationRefreshBudget.fromJson(
                    tc.store.getString(mobRefreshKey(tc)));
        } catch (Throwable ignored) {
            budget = MobilizationRefreshBudget.EMPTY;
        }
        long nowMillis = System.currentTimeMillis();
        int hourLimit = p == null ? MOB_REFRESH_HOUR_LIMIT_DEFAULT
                : Math.max(1, p.optInt("refresh_hour_limit", MOB_REFRESH_HOUR_LIMIT_DEFAULT));
        int dayLimit = p == null ? MOB_REFRESH_DAY_LIMIT_DEFAULT
                : Math.max(1, p.optInt("refresh_day_limit", MOB_REFRESH_DAY_LIMIT_DEFAULT));
        if (personal) {
            int pos = firstPositionWithTask(st.personalPositions);
            if (pos > 0 && mobRefreshAllowed(budget, nowMillis, hourLimit, dayLimit)) {
                GameMessage r = tc.engine.request("req_mobilization_refresh_personal_quest",
                        Exec.args("pos", (long) pos));
                if (Exec.ok(r)) {
                    m.claimed("专属任务刷新(pos=" + pos + ")");
                    budget = mobRefreshRecorded(budget, nowMillis);
                } else {
                    m.skip("专属刷新码" + Exec.codeOf(r));
                }
            }
        }
        if (pub) {
            int pos = firstPositionWithTask(st.publicPositions);
            if (pos > 0 && mobRefreshAllowed(budget, nowMillis, hourLimit, dayLimit)) {
                GameMessage r = tc.engine.request("req_mobilization_refresh_quest",
                        Exec.args("pos", (long) pos));
                if (Exec.ok(r)) {
                    m.claimed("公共任务刷新(pos=" + pos + ")");
                    budget = mobRefreshRecorded(budget, nowMillis);
                } else {
                    m.skip("公共刷新码" + Exec.codeOf(r));
                }
            }
        }
        try {
            tc.store.putString(mobRefreshKey(tc), budget.toJson());
        } catch (Throwable ignored) {
        }
    }

    public static TaskOutcome mobilization(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            JSONObject p = tc.params("alliance_mobilization");
            MobilizationState st = readMobilizationState(tc.engine::notifyData);
            MobilizationCheckpoint cp = loadMobCheckpoint(tc);
            long nowMillis = System.currentTimeMillis();
            long nowSec = nowMillis / 1000L;
            // ① 静态数据缺失：open_info 仅按节流探测（7401 不再每轮打）。
            if (st.activityId <= 0L) {
                if (shouldProbeOpenInfo(cp, nowMillis)) {
                    // 该协议没有请求字段；传空对象最稳。
                    GameMessage info = tc.engine.request("req_mobilization_open_info",
                            new LinkedHashMap<String, Object>());
                    int code = Exec.codeOf(info);
                    cp = cp.rememberOpenInfo(nowMillis, code);
                    saveMobCheckpoint(tc, cp);
                    if (code == MOB_CODE_NOT_OPEN) {
                        m.skip("总动员未开放(码7401)，1小时内不再探测");
                    } else if (!Exec.ok(info)) {
                        m.skip("总动员未开启(码" + code + ")");
                    } else {
                        st = readMobilizationState(tc.engine::notifyData);
                        applyMobOpenInfo(st, info.getContent());
                    }
                } else {
                    m.skip("总动员未开放/未同步（open_info 探测节流中）");
                }
                if (st.activityId <= 0L) return Exec.result(m, "alliance_mobilization", "联盟总动员");
            }
            String stateSkip = mobilizationStateSkipReason(st, nowSec);
            if (stateSkip != null) {
                m.skip(stateSkip);
                return Exec.result(m, "alliance_mobilization", "联盟总动员");
            }
            // ② 已完成任务奖励：quest 存在且 progress>0 才尝试（无 catalog 目标值，8409/8410 兜底）。
            if (st.hasQuest() && st.questProgress > 0L) {
                String fingerprint = mobQuestFingerprint(st);
                if (mobQuestMemoryActive(cp, fingerprint, nowMillis)) {
                    m.skip("任务奖励 8409/8410 记忆期内，跳过");
                } else {
                    GameMessage r = tc.engine.request("req_mobilization_get_quest_reward",
                            new LinkedHashMap<String, Object>());
                    int code = Exec.codeOf(r);
                    if (Exec.ok(r)) {
                        m.claimed("任务奖励");
                        m.rewards("任务奖励", r);
                    } else if (code == MOB_CODE_NO_QUEST || code == MOB_CODE_QUEST_NOT_DONE) {
                        cp = cp.rememberQuestEmpty(fingerprint, nowMillis);
                        saveMobCheckpoint(tc, cp);
                        m.skip(code == MOB_CODE_QUEST_NOT_DONE
                                ? "任务未完成，300秒内不重试" : "暂无已完成任务奖励，300秒内不重试");
                    } else {
                        m.skip("任务奖励码" + code);
                    }
                }
            }
            // ③ 里程碑（8413 空奖 300s 记忆，避免每轮空打）。
            String milestoneFp = mobMilestoneFingerprint(st, nowSec);
            if (mobMilestoneMemoryActive(cp, milestoneFp, nowMillis)) {
                m.skip("暂无里程碑可领（300秒内不重试）");
            } else {
                GameMessage r = tc.engine.request("req_mobilization_batch_get_milestone",
                        new LinkedHashMap<String, Object>());
                int code = Exec.codeOf(r);
                if (Exec.ok(r)) {
                    m.claimed("里程碑");
                    m.rewards("里程碑", r);
                    cp = cp.clearMilestoneEmpty();
                    saveMobCheckpoint(tc, cp);
                } else if (code == MOB_CODE_NO_MILESTONE) {
                    cp = cp.rememberMilestoneEmpty(milestoneFp, nowMillis);
                    saveMobCheckpoint(tc, cp);
                    m.skip("暂无里程碑可领（300秒内不重试）");
                } else if (code != 0) {
                    m.skip("里程碑码" + code);
                }
            }
            // ④ 购买接取次数：仅免费首购(cost=0)，付费一律不做。
            MobilizationCountBuy freeBuy = mobilizationFreeCountBuyDecision(st, nowSec, p);
            if (freeBuy.allowed()) {
                GameMessage r = tc.engine.request("req_mobilization_buy_count",
                        Exec.args("count", 1));
                if (Exec.ok(r)) m.claimed("接取次数+1（免费首购）");
                else m.skip("免费首购码" + Exec.codeOf(r));
            } else if (!st.hasQuest()) {
                m.skip(freeBuy.reason());
            }
            // ⑤ 自动接取（默认关）：从公共/专属板挑一个已有任务的槽位接取。
            if (tc.subOn("alliance_mobilization", "auto_accept", false)
                    && !st.hasQuest() && st.remainingAcceptCount > 0 && st.active(nowSec)) {
                int pos = firstPositionWithTask(st.publicPositions);
                boolean publicTask = pos > 0;
                if (!publicTask) pos = firstPositionWithTask(st.personalPositions);
                if (pos > 0) {
                    String proto = publicTask ? "req_mobilization_get_quest"
                            : "req_mobilization_get_personal_quest";
                    GameMessage r = tc.engine.request(proto, Exec.args("pos", (long) pos));
                    if (Exec.ok(r)) m.claimed("自动接取总动员任务(pos=" + pos + ")");
                    else m.skip("自动接取码" + Exec.codeOf(r));
                } else {
                    m.skip("无已上板任务可接取");
                }
            }
            // ⑥ 刷新（默认关 + 每小时/每日限频）。
            handleMobilizationRefresh(tc, m, st, p, nowSec);
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "联盟总动员");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "alliance_mobilization", "联盟总动员");
    }

    // ================= 联盟争霸赛（L4 赛季/报名窗口） =================

    static final ZoneId SHOWDOWN_ZONE = ZoneId.of("Asia/Shanghai");
    static final int SHOWDOWN_DEFAULT_SIGNUP_DAY = 1;        // 周一
    static final String SHOWDOWN_DEFAULT_SIGNUP_TIME = "00:00";
    static final int SHOWDOWN_DEFAULT_PATH = 2;              // 中路
    static final int SHOWDOWN_CODE_TIMEOUT = 7705;
    static final int SHOWDOWN_CODE_NO_SOLDIER = 401;
    static final int SHOWDOWN_WINDOW_END_HOUR = 23;          // 周二 23:00 截止
    static final DateTimeFormatter SHOWDOWN_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 报名日：默认周一；只接受周一/周二（网页版同校验），其它值回落周一。 */
    static int showdownSignupDay(JSONObject p) {
        int day = p == null ? SHOWDOWN_DEFAULT_SIGNUP_DAY : p.optInt("signup_day",
                SHOWDOWN_DEFAULT_SIGNUP_DAY);
        return day == 2 ? 2 : 1;
    }

    static LocalTime showdownSignupTime(JSONObject p) {
        String raw = p == null ? "" : p.optString("signup_time", SHOWDOWN_DEFAULT_SIGNUP_TIME).trim();
        if (raw.isEmpty()) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(raw, SHOWDOWN_TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** 报名分路：默认中路(2)，限制 1..3。 */
    static int showdownPath(JSONObject p) {
        int path = p == null ? SHOWDOWN_DEFAULT_PATH : p.optInt("path", SHOWDOWN_DEFAULT_PATH);
        return Math.max(1, Math.min(3, path));
    }

    /** 下一次报名窗口起点（epoch 秒）：本周报名日 signupTime；若已过周二 23:00 截止则顺延下周。 */
    static long showdownNextDueEpoch(long nowEpochSeconds, int signupDayValue, LocalTime time) {
        LocalTime signupTime = time == null ? LocalTime.MIDNIGHT : time;
        ZonedDateTime now = Instant.ofEpochSecond(nowEpochSeconds).atZone(SHOWDOWN_ZONE);
        LocalDate monday = now.toLocalDate().minusDays(now.getDayOfWeek().getValue() - 1L);
        int dayOffset = signupDayValue == 2 ? 1 : 0;
        ZonedDateTime windowEnd = LocalDateTime.of(monday.plusDays(1),
                LocalTime.of(SHOWDOWN_WINDOW_END_HOUR, 0)).atZone(SHOWDOWN_ZONE);
        LocalDate dueDate = now.isAfter(windowEnd) ? monday.plusWeeks(1L) : monday;
        return LocalDateTime.of(dueDate.plusDays(dayOffset), signupTime)
                .atZone(SHOWDOWN_ZONE).toEpochSecond();
    }

    /** 报名窗口是否已到（窗口内=已到；窗口结束后自动滚动到下周）。 */
    static boolean showdownWindowOpen(long nowEpochSeconds, int signupDayValue, LocalTime time) {
        return nowEpochSeconds >= showdownNextDueEpoch(nowEpochSeconds, signupDayValue, time);
    }

    static String showdownWindowSkipReason(long nowEpochSeconds, int signupDayValue, LocalTime time) {
        if (showdownWindowOpen(nowEpochSeconds, signupDayValue, time)) return null;
        String day = signupDayValue == 2 ? "周二" : "周一";
        String hhmm = time == null ? SHOWDOWN_DEFAULT_SIGNUP_TIME : time.format(SHOWDOWN_TIME_FORMAT);
        return "争霸赛报名窗口未到（" + day + " " + hhmm + " 开放）";
    }

    /** 报名失败文案：7705 超时 / 401 兵不足 单独分类，不再一律“未开启”。 */
    static String showdownApplyFailureNote(int code) {
        if (code == SHOWDOWN_CODE_TIMEOUT) {
            return "争霸赛请求超时，稍后重试（码7705）";
        }
        if (code == SHOWDOWN_CODE_NO_SOLDIER) {
            return "可用士兵不足，等待部队回城后重试（码401）";
        }
        return "报名码" + code;
    }

    /**
     * 争霸赛最小前置状态判定：返回非空字符串=明确 SKIP 原因；返回 null=状态未知/可尝试报名
     * （保持原行为，最终由服务端判定）。只做“能读到就明确”的保守判断，不臆测字段。
     */
    static String showdownSkipReason(Map<String, Object> activityInfo) {
        if (activityInfo == null || activityInfo.isEmpty()) {
            return "争霸赛无活动数据（未开启或未同步）";
        }
        boolean signed = Exec.longOf(activityInfo.get("apply")) == 1
                || "true".equalsIgnoreCase(String.valueOf(activityInfo.get("is_apply")));
        if (signed) {
            return "争霸赛已报名（本季）";
        }
        if (activityInfo.containsKey("season") && Exec.longOf(activityInfo.get("season")) <= 0) {
            return "争霸赛当前无赛季（未开启）";
        }
        return null;
    }

    /** 活动信息拉取失败的 SKIP 文案：超时(7705)不再伪装成“未开启”。 */
    static String showdownInfoFailureNote(int code) {
        if (code == 7705) {
            return "争霸赛请求超时，稍后重试（码7705）";
        }
        return "争霸赛未开启(码" + code + ")";
    }

    // ---- L4 尾项：完整编队 / 赛季状态 / 战报只读（批次6） ----

    /** 单队出征容量上限（与 Exec.formationWithHeroes 同口径，防服务端 code=2）。 */
    static final long SHOWDOWN_SOLDIER_CAP = 50_000L;
    /** 默认出征比例 5:2:3（盾:矛:弓 = 50%:20%:30%，网页版 DailyAllianceShowdownConfig 默认值）。 */
    static final int SHOWDOWN_INFANTRY_RATIO = 5;
    static final int SHOWDOWN_LANCER_RATIO = 2;
    static final int SHOWDOWN_MARKSMAN_RATIO = 3;
    /** 默认 5 英雄。 */
    static final int SHOWDOWN_HERO_LIMIT = 5;
    /** 战报最多读取条数（只读、低频）。 */
    static final int SHOWDOWN_REPORT_LIMIT = 3;

    /** 编队解析结果：error 非空时调用方 SKIP 并展示原因。 */
    static record ShowdownFormation(List<Long> heroIds, long[] soldierCounts,
                                    String source, String error) {
        boolean ready() {
            return error == null && heroIds != null && !heroIds.isEmpty()
                    && totalSoldiers() > 0L;
        }

        long totalSoldiers() {
            long total = 0L;
            if (soldierCounts != null) {
                for (long count : soldierCounts) total += Math.max(0L, count);
            }
            return total;
        }
    }

    /** 争霸赛活动首页状态（网页版 DailyAllianceShowdownState.updateHome 同字段）。 */
    static record ShowdownActivity(Map<String, Object> activity, Map<String, Object> player,
                                   Map<String, Object> group, Map<String, Object> target,
                                   long count, long grade) {
        long season() {
            return activity == null ? 0L : Math.max(0L, Exec.longOf(activity.get("season")));
        }

        int stage() {
            return activity == null ? 0 : (int) Math.max(0L, Exec.longOf(activity.get("stage")));
        }

        boolean signedUp() {
            return player != null && !player.isEmpty();
        }

        boolean initialized() {
            return activity != null && !activity.isEmpty();
        }

        /** 分组 aid：group.aids 首个 >0，否则 target.aid（网页版 groupAid 同款）。 */
        long groupAid() {
            if (group != null) {
                for (Object value : valuesOf(group.get("aids"))) {
                    long aid = Exec.longOf(value);
                    if (aid > 0L) return aid;
                }
            }
            return target == null ? 0L : Math.max(0L, Exec.longOf(target.get("aid")));
        }
    }

    static ShowdownActivity showdownActivity(Map<String, Object> content) {
        if (content == null) {
            return new ShowdownActivity(Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L);
        }
        Map<String, Object> activity = Exec.mapOf(content.get("activity"));
        Map<String, Object> player = Exec.mapOf(content.get("player"));
        Map<String, Object> group = Exec.mapOf(content.get("group"));
        Map<String, Object> target = Exec.mapOf(content.get("target"));
        return new ShowdownActivity(
                activity == null ? Map.of() : activity,
                player == null ? Map.of() : player,
                group == null ? Map.of() : group,
                target == null ? Map.of() : target,
                Math.max(0L, Exec.longOf(content.get("count"))),
                Math.max(0L, Exec.longOf(content.get("grade"))));
    }

    /** hero_ids 覆盖：JSONArray / "11,12,13" 逗号串；去重、过滤非法、上限 5。 */
    static List<Long> showdownHeroIdsOverride(JSONObject p) {
        List<Long> out = new ArrayList<>();
        if (p == null) return out;
        Object raw = p.opt("hero_ids");
        List<Object> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) values.add(array.opt(i));
        } else if (raw instanceof String) {
            for (String part : ((String) raw).split("[,，\\s]+")) values.add(part);
        }
        for (Object value : values) {
            long id = Exec.longOf(value);
            if (id > 0L && !out.contains(id) && out.size() < SHOWDOWN_HERO_LIMIT) out.add(id);
        }
        return out;
    }

    /** 5:2:3 兵力分配：按各兵种库存夹取，总兵力 ≤ cap；不足时宁可少带不超发。 */
    static long[] showdownSoldierCounts(long[] stock, long cap) {
        long[] counts = new long[3];
        if (stock == null || stock.length < 3) return counts;
        long total = Math.max(0L, Math.min(cap <= 0L ? SHOWDOWN_SOLDIER_CAP : cap,
                Math.max(0L, stock[0]) + Math.max(0L, stock[1]) + Math.max(0L, stock[2])));
        long ratioTotal = (long) SHOWDOWN_INFANTRY_RATIO + SHOWDOWN_LANCER_RATIO + SHOWDOWN_MARKSMAN_RATIO;
        long wantInfantry = Math.min(Math.max(0L, stock[0]),
                total * SHOWDOWN_INFANTRY_RATIO / ratioTotal);
        long wantLancer = Math.min(Math.max(0L, stock[1]),
                total * SHOWDOWN_LANCER_RATIO / ratioTotal);
        long wantMarksman = Math.min(Math.max(0L, stock[2]),
                Math.max(0L, total - wantInfantry - wantLancer));
        counts[0] = wantInfantry;
        counts[1] = wantLancer;
        counts[2] = wantMarksman;
        return counts;
    }

    /**
     * 编队解析（纯函数）：hero_ids 覆盖 > formation_index 指定客户端编队 > AUTOMATIC 5:2:3。
     * 英雄/兵力/兵种任一缺失 → error（调用方 SKIP，不发报名请求）。
     */
    static ShowdownFormation resolveShowdownFormation(JSONObject p, List<Long> squadHeroes,
                                                      List<Long> strongestHeroes,
                                                      long[] soldierStock, long[] soldierIds) {
        List<Long> heroes = new ArrayList<>();
        String source;
        List<Long> override = showdownHeroIdsOverride(p);
        if (!override.isEmpty()) {
            heroes.addAll(override);
            source = "hero_ids覆盖(" + override.size() + "英雄)";
        } else {
            int formationIndex = p == null ? 0 : p.optInt("formation_index", 0);
            if (formationIndex > 0 && squadHeroes != null && !squadHeroes.isEmpty()) {
                for (Long hero : squadHeroes) {
                    if (hero != null && hero > 0L && heroes.size() < SHOWDOWN_HERO_LIMIT) {
                        heroes.add(hero);
                    }
                }
                source = "客户端编队" + formationIndex;
            } else {
                if (strongestHeroes != null) {
                    for (Long hero : strongestHeroes) {
                        if (hero != null && hero > 0L && heroes.size() < SHOWDOWN_HERO_LIMIT) {
                            heroes.add(hero);
                        }
                    }
                }
                source = "AUTOMATIC 5:2:3";
            }
        }
        if (heroes.isEmpty()) {
            return new ShowdownFormation(List.of(), new long[3], source,
                    "当前角色没有可用英雄（英雄数据未同步）");
        }
        long[] counts = showdownSoldierCounts(soldierStock, SHOWDOWN_SOLDIER_CAP);
        long total = counts[0] + counts[1] + counts[2];
        if (total <= 0L) {
            return new ShowdownFormation(heroes, counts, source,
                    "当前角色出征容量/兵力未同步");
        }
        if (soldierIds == null || soldierIds.length < 3) {
            return new ShowdownFormation(heroes, counts, source, "士兵兵种数据未同步");
        }
        return new ShowdownFormation(heroes, counts, source, null);
    }

    /** 构造报名 formation 载荷（网页版 DailyResolvedFormation → {hero_id, soldier} 同形状）。 */
    static Map<String, Object> showdownFormationPayload(ShowdownFormation formation,
                                                        long[] soldierIds) {
        List<Map<String, Object>> heroPairs = Exec.heroPairs(
                formation == null ? null : formation.heroIds());
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        long[] counts = formation == null ? null : formation.soldierCounts();
        if (counts != null && soldierIds != null) {
            for (int i = 0; i < Math.min(3, Math.min(counts.length, soldierIds.length)); i++) {
                if (counts[i] > 0L && soldierIds[i] > 0L) {
                    soldierPairs.add(Exec.soldierPair(soldierIds[i], counts[i]));
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hero_id", heroPairs);
        payload.put("soldier", soldierPairs);
        return payload;
    }

    /** 首页状态 SKIP 原因（新状态机；与旧 showdownSkipReason 同文案口径）。 */
    static String showdownActivitySkipReason(ShowdownActivity activity) {
        if (activity == null || !activity.initialized()) {
            return "争霸赛无活动数据（未开启或未同步）";
        }
        if (activity.season() <= 0L) {
            return "争霸赛当前无赛季（未开启）";
        }
        if (activity.signedUp()) {
            return "争霸赛已报名（本季）";
        }
        return null;
    }

    /** 幂等：本季已报名（服务端 player 非空或 mod 记录同赛季）→ 不再请求报名。 */
    static boolean showdownSignedIdempotent(ShowdownActivity activity, long modSignedSeason) {
        if (activity == null) return false;
        if (activity.signedUp()) return true;
        return activity.season() > 0L && modSignedSeason == activity.season();
    }

    /** 从 group.内容提取战报 id：rounds[].results[].report_id（兼容 Map/List 形状）。 */
    static List<String> showdownReportIds(Map<String, Object> group) {
        List<String> out = new ArrayList<>();
        if (group == null) return out;
        for (Object rawRound : valuesOf(group.get("rounds"))) {
            Map<String, Object> round = Exec.mapOf(rawRound);
            if (round == null) continue;
            for (Object rawResult : valuesOf(round.get("results"))) {
                Map<String, Object> result = Exec.mapOf(rawResult);
                if (result == null) continue;
                Object rawId = result.get("report_id");
                String reportId = rawId == null ? "" : String.valueOf(rawId).trim();
                if (!reportId.isEmpty() && !"null".equals(reportId) && !out.contains(reportId)) {
                    out.add(reportId);
                }
                if (out.size() >= SHOWDOWN_REPORT_LIMIT) return out;
            }
        }
        return out;
    }

    /** 战报结果文案：只读文本字段（二进制 report 不解码，避免误报）。 */
    static String showdownReportResultText(Map<String, Object> reportContent) {
        if (reportContent == null) return "";
        for (String key : new String[]{"result", "win", "is_win", "score", "grade", "rank"}) {
            Object value = reportContent.get(key);
            if (value == null || value instanceof byte[]) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty() && !"null".equals(text)) return key + "=" + text;
        }
        return "";
    }

    /** mod:alliance_showdown 摘要文案（赛季/报名/战报数/赛道/结果）。 */
    static String showdownModSummary(ShowdownActivity activity, int reports, int path,
                                     String action) {
        StringBuilder sb = new StringBuilder();
        sb.append("赛季").append(activity == null ? 0L : activity.season());
        if (activity != null && activity.initialized()) sb.append(" · 阶段").append(activity.stage());
        sb.append(activity != null && activity.signedUp() ? " · 已报名" : " · 未报名");
        sb.append(" · 战报").append(Math.max(0, reports)).append("条");
        if (path > 0) sb.append(" · 赛道").append(path);
        if (action != null && !action.isEmpty()) sb.append(" · ").append(action);
        return sb.toString();
    }

    static String showdownModKey(TaskContext tc) {
        return "mod:" + tc.fpid + ":" + tc.sid + ":alliance_showdown";
    }

    /** 写单键 mod（保留 App 今日页 mod 文案契约：key + key_ts）。 */
    static void saveShowdownMod(TaskContext tc, String key, Object value) {
        try {
            JSONObject mod = tc.modState("alliance_showdown");
            mod.put(key, value);
            mod.put(key + "_ts", System.currentTimeMillis());
            tc.store.putString(showdownModKey(tc), mod.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 报名后只读 group_info / battle_report；返回读到的战报条数。 */
    private static int readShowdownReports(TaskContext tc, ShowdownActivity activity,
                                           Exec.Multi m) {
        if (activity == null || activity.season() <= 0L || activity.groupAid() <= 0L) return 0;
        int read = 0;
        try {
            GameMessage group = tc.engine.request("req_alliance_showdown_group_info",
                    Exec.args("season", activity.season(), "aid", activity.groupAid()));
            int groupCode = Exec.codeOf(group);
            if (!Exec.ok(group)) {
                m.skip(groupCode == 7705 ? "战报组信息超时，稍后重试"
                        : "战报组信息码" + groupCode);
                return 0;
            }
            Map<String, Object> content = group.getContent();
            Map<String, Object> groupData = Exec.mapOf(content == null ? null : content.get("group"));
            if (groupData == null) groupData = content == null ? Map.of() : content;
            String firstResult = "";
            for (String reportId : showdownReportIds(groupData)) {
                try {
                    GameMessage report = tc.engine.request("req_alliance_showdown_battle_report",
                            Exec.args("reportid", reportId));
                    if (!Exec.ok(report)) {
                        m.skip("战报" + reportId + "码" + Exec.codeOf(report));
                        continue;
                    }
                    read++;
                    if (firstResult.isEmpty()) {
                        firstResult = showdownReportResultText(report.getContent());
                    }
                } catch (Exception reportError) {
                    if (ServerTownTasks.sessionLost(reportError)) throw reportError;
                    m.skip("战报" + reportId + "读取中断");
                }
            }
            if (!firstResult.isEmpty()) saveShowdownMod(tc, "battle_result", firstResult);
            if (read > 0) m.claimed("战报只读×" + read);
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) throw new RuntimeException(e);
            m.skip("战报只读失败，稍后重试");
        }
        return read;
    }

    public static TaskOutcome showdown(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            JSONObject p = tc.params("alliance_showdown");
            GameMessage info = tc.engine.request("req_alliance_showdown_activity_info", null);
            if (!Exec.ok(info)) {
                m.skip(showdownInfoFailureNote(Exec.codeOf(info)));
                saveShowdownMod(tc, "last_error", showdownInfoFailureNote(Exec.codeOf(info)));
                return Exec.result(m, "alliance_showdown", "争霸赛");
            }
            ShowdownActivity activity = showdownActivity(info.getContent());
            int path = showdownPath(p);
            long modSignedSeason = tc.modState("alliance_showdown").optLong("signed_season", 0L);
            saveShowdownMod(tc, "season", activity.season());
            saveShowdownMod(tc, "stage", activity.stage());
            saveShowdownMod(tc, "count", activity.count);
            saveShowdownMod(tc, "signed", activity.signedUp());

            String skipReason = showdownActivitySkipReason(activity);
            if (skipReason != null) {
                m.skip(skipReason);
            } else if (showdownSignedIdempotent(activity, modSignedSeason)) {
                // 幂等：本季已报名（服务端状态或 mod 记录）→ 不再发报名请求。
                m.skip("本季已报名（幂等，跳过重复报名）");
                saveShowdownMod(tc, "signed", true);
                if (activity.season() > 0L) saveShowdownMod(tc, "signed_season", activity.season());
            } else {
                String windowSkip = showdownWindowSkipReason(System.currentTimeMillis() / 1000L,
                        showdownSignupDay(p), showdownSignupTime(p));
                if (windowSkip != null) {
                    m.skip(windowSkip);
                } else {
                    // 编队：hero_ids 覆盖 > formation_index 客户端编队 > AUTOMATIC 5:2:3。
                    Map<Long, List<Long>> squads = Exec.clientSquads(tc.engine);
                    int formationIndex = p.optInt("formation_index", 0);
                    List<Long> squadHeroes = formationIndex > 0 ? squads.get((long) formationIndex) : null;
                    List<Long> strongestHeroes = Exec.allHeroes(tc.engine);
                    long[] stock = Exec.baseSoldiers(tc.engine);
                    long[] soldierIds = Exec.bestSoldierIds(Exec.soldiers(tc.engine));
                    ShowdownFormation formation = resolveShowdownFormation(p, squadHeroes,
                            strongestHeroes, stock, soldierIds);
                    if (!formation.ready()) {
                        m.skip("编队不可用：" + formation.error());
                        saveShowdownMod(tc, "last_error", "编队不可用：" + formation.error());
                    } else {
                        Map<String, Object> payload = showdownFormationPayload(formation, soldierIds);
                        saveShowdownMod(tc, "formation", formation.source()
                                + " · 英雄" + formation.heroIds().size()
                                + " · 兵" + formation.totalSoldiers());
                        GameMessage apply = tc.engine.request("req_alliance_showdown_apply",
                                Exec.args("type", (long) path, "formation", payload));
                        int code = Exec.codeOf(apply);
                        if (Exec.ok(apply)) {
                            m.claimed("已报名赛道" + path + "（" + formation.source() + "）");
                            saveShowdownMod(tc, "signed", true);
                            saveShowdownMod(tc, "path", path);
                            if (activity.season() > 0L) {
                                saveShowdownMod(tc, "signed_season", activity.season());
                            }
                        } else {
                            String note = showdownApplyFailureNote(code);
                            m.skip(note);
                            saveShowdownMod(tc, "last_error", note);
                            if (code != SHOWDOWN_CODE_NO_SOLDIER && code != SHOWDOWN_CODE_TIMEOUT) {
                                saveShowdownMod(tc, "last_error_code", code);
                            }
                        }
                    }
                }
            }
            // 赛季/战报只读：已报名且有赛季+aid 时读 group_info / battle_report（无付费路径）。
            int reports = readShowdownReports(tc, activity, m);
            if (reports > 0) saveShowdownMod(tc, "reports", reports);
            String action = m.sb.length() == 0 ? "" : "已处理";
            saveShowdownMod(tc, "state",
                    showdownModSummary(activity, reports, path, action));
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "联盟争霸赛");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "alliance_showdown", "争霸赛");
    }

    public static TaskOutcome allianceHelp(TaskContext tc) {
        // 常驻互助（ServerHelpResponder）接管中：它 30s 轮询持续刷新 help_all_ts，
        // 3 分钟内有过响应/巡检即视为已覆盖，跳过本轮重复请求（服务器上整轮仅在常驻会话跑，此跳过常态生效）。
        // P0 2026-10-28：SKIP 文案带上常驻响应器的真实状态，避免用户把“跳过本轮”误读成“不执行互助”。
        try {
            long ts = tc.modState("alliance_help").optLong("help_all_ts", 0);
            if (ts > 0 && System.currentTimeMillis() - ts < 180_000L) {
                String state = tc.modState("alliance_help").optString("help_all", "").trim();
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
                        "常驻响应器已接管自动互助，本轮跳过"
                                + (state.isEmpty() ? "" : "（" + state + "）"));
            }
        } catch (Throwable ignored) {
        }
        try {
            GameMessage rsp = tc.engine.request("req_alliance_help_all", null);
            int code = Exec.codeOf(rsp);
            if (Exec.ok(rsp)) {
                tc.saveModText("alliance_help", "help_all",
                        "上轮互助 " + com.carlos.vaultserver.Beijing.fmt("HH:mm")
                                .format(new java.util.Date()));
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.CLAIMED,
                        "已一键帮助全盟（服务器常驻秒级响应主力）");
            }
            if (code == 1536) {
                // 1536 = 服务端此刻没有可响应的互助请求（瞬时），不是每日上限：
                // 不落盘、不跨天；下一轮/下一次推送继续尝试。
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
                        "当前没有可互助请求（码1536），下一轮继续尝试");
            }
            if (code == 75 || code == 2) {
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
                        "当前没有可互助请求（码" + code + "），下一轮继续尝试");
            }
            if (code == 16873) {
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED, "无联盟");
            }
            return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.ERROR, "错误码 " + code);
        } catch (Exception e) {
            return ServerTasks.classifyException(e, "联盟互助");
        }
    }

    // ---------- 晨曦岛拜访协助（App LeagueTasks.homelandVisit 逐行移植；原版 homeland-visit 同款） ----------

    public static TaskOutcome homelandVisit(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            long myUid = tc.engine.lastUid();
            long kid = tc.sid;
            // 1. 联盟活跃排行（type=10003 = 晨曦岛活跃榜），rank_list[].key = 成员 uid
            GameMessage rank = tc.engine.request("req_alliance_rank_data",
                    Exec.args("type", 10003L));
            if (!Exec.ok(rank)) {
                m.skip("联盟排行未开放(码" + Exec.codeOf(rank) + ")");
                return Exec.result(m, "homeland_visit", "晨曦岛拜访");
            }
            List<Long> candidates = new ArrayList<>();
            for (Map<String, Object> row : Exec.mapsOf(rank.getContent().get("rank_list"))) {
                long uid = Exec.longOf(row.get("key"));
                if (uid > 0 && uid != myUid && candidates.size() < 20) candidates.add(uid);
            }
            if (candidates.isEmpty()) {
                m.skip("联盟排行无成员");
                return Exec.result(m, "homeland_visit", "晨曦岛拜访");
            }
            // 2. 批量查协助状态：今日已协助≥3 或 6 小时内协助过 → 跳过（原版同款阈值）
            GameMessage infos = tc.engine.request("req_homeland_player_homeland_info",
                    Exec.args("uids", candidates, "type", 1L));
            Map<String, Object> pi = Exec.mapOf(infos.getContent().get("player_homeland_infos"));
            int visited = 0, assisted = 0;
            for (Long uid : candidates) {
                if (visited >= 10) break;   // 单轮上限（原版 300s 任务节律的保守版）
                Map<String, Object> info = Exec.mapOf(pi == null ? null : pi.get(String.valueOf(uid)));
                Map<String, Object> assist = Exec.mapOf(info == null ? null : info.get("homeland_assist_info"));
                long assistedCount = Exec.longOf(assist == null ? null : assist.get("assisted_count"));
                long lastTime = Exec.longOf(assist == null ? null : assist.get("last_assisted_time"));
                if (assistedCount >= 3 || (lastTime > 0
                        && System.currentTimeMillis() / 1000L - lastTime < 21600L)) {
                    continue;
                }
                GameMessage visit = tc.engine.request("req_homeland_visit_other_homeland",
                        Exec.args("uid", uid, "kid", kid));
                if (!Exec.ok(visit)) continue;
                GameMessage as = tc.engine.request("req_homeland_assist", Exec.args("uid", uid));
                int code = Exec.codeOf(as);
                if (Exec.ok(as)) {
                    assisted++;
                    m.claimed("协助 uid=" + uid);
                } else if (code == 15026) {
                    m.skip("今日协助次数已用完(3/3)");
                    break;
                } else if (code == 15027 || code == 15028) {
                    m.skip("uid=" + uid + " 冷却/对方已满");
                } else {
                    m.skip("uid=" + uid + " 码" + code);
                }
                visited++;
            }
            if (assisted > 0) {
                m.claimed("拜访协助×" + assisted);
                m.subOk("assist", "协助×" + assisted);
            } else if (m.sb.length() == 0) {
                m.skip("无可协助对象");
                m.subSkip("assist", "无可协助");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "晨曦岛拜访");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "homeland_visit", "晨曦岛拜访");
    }

    /**
     * 背包道具（R8 解析修复，批次4 2026-10-23）。
     *
     * <p>生产“背包数据未同步”根因（audit §5.3）：抓包 tools/bundle_re/proto_log.txt:36
     * {@code notify_backpack_init{"data":[{"id":510000,"count":14},...]}} 是 {@code List<{id,count}>}，
     * 旧实现用 {@code Exec.kvPairs}（只认 {k,v} 对，Exec.java:76-83）→ 恒空。
     * 修复：{@link #parseBackpackItems} 用 {@code Exec.mapsOf} 形状兼容（List/Map/Collection），
     * 字段兼容 id/item_id + count/num/amount/cnt/quantity，并叠加最近一条
     * {@code notify_backpack_update}（type=add 累加，抓包 10-14 02:24 add 实证）。
     *
     * <p><b>不扩大自动使用范围</b>：网页版无背包自动开箱模块（service/DailyBackpackResourceService.java
     * 是手动 UI：req_batch_use_item/req_use_item）→ 是否自动开箱产品待确认；本修复仅解析，
     * 保持既有固定资源包 100111-100115（random_boxes 开关保留 100091-100093）行为不变，不新增消耗类型。
     */
    public static TaskOutcome backpack(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> init = tc.engine.notifyContent("notify_backpack_init");
            Map<Long, Long> items = parseBackpackItems(init);
            applyBackpackUpdate(items, tc.engine.notifyContent("notify_backpack_update"));
            if (items.isEmpty()) {
                m.skip(init == null
                        ? "背包数据未同步（未收到 notify_backpack_init）"
                        : "背包数据为空");
            } else {
                List<Map<String, Object>> params = new ArrayList<>();
                int used = 0;
                JSONObject bpk = tc.params("backpack");
                for (long id = 100111; id <= 100111 + 4; id++) {
                    Long cnt = items.get(id);
                    if (cnt != null && cnt > 0) {
                        params.add(Exec.args("id", id, "count", cnt, "extra", new LinkedHashMap<>()));
                        used += cnt;
                    }
                }
                if (bpk.optBoolean("random_boxes", false)) {
                    for (long id = 100091; id <= 100093; id++) {
                        Long cnt = items.get(id);
                        if (cnt != null && cnt > 0) {
                            params.add(Exec.args("id", id, "count", cnt, "extra", new LinkedHashMap<>()));
                            used += cnt;
                        }
                    }
                }
                if (used == 0) {
                    // 解析成功但无可使用项：明确“无可使用道具”，不再误报“未同步”
                    m.skip("无可使用道具（背包 " + items.size() + " 种，未配置自动使用类型）");
                } else {
                    GameMessage rsp = tc.engine.request("req_batch_use_item", Exec.args("params", params));
                    if (Exec.ok(rsp)) {
                        m.claimed("开启资源包×" + used);
                        m.rewards("资源包", rsp);
                    }
                    else m.skip("使用码" + Exec.codeOf(rsp));
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "背包道具");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "backpack", "背包道具");
    }

    // ---- R8 背包解析纯函数（package-private，便于 RewardStateFixTest 单测；形状见背包方法注释） ----

    /** 背包快照解析：{@code {"data":[{id,count}...]}} / {@code {"items":[...]}}，兼容 Map 与旧 {k,v}。 */
    static Map<Long, Long> parseBackpackItems(Map<String, Object> content) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (content == null) return out;
        Object raw = content.containsKey("data") ? content.get("data") : content.get("items");
        mergeBackpackEntries(out, raw);
        if (out.isEmpty()) {
            out.putAll(Exec.kvPairs(raw));   // 旧形状兜底（防御：网页版抓包无此形状）
        }
        return out;
    }

    /** 合并背包条目（List/Map；Exec.mapsOf 兼容形状）；id 兼容 id/item_id，数量兼容 count/num/amount。 */
    static void mergeBackpackEntries(Map<Long, Long> out, Object raw) {
        if (raw == null || out == null) return;
        for (Map<String, Object> item : Exec.mapsOf(raw)) {
            long id = Exec.longOf(item.get("id"));
            // Exec.mapsOf 对 Map 形态回填的 id 可能是 "100111.0" 浮点键 → 再走 numericKey
            if (id <= 0L) id = ServerTasks.numericKey(item.get("id"));
            if (id <= 0L) id = ServerTasks.numericKey(item.get("item_id"));
            if (id <= 0L) continue;
            long count = backpackCount(item);
            if (count <= 0L) continue;
            out.merge(id, count, Long::sum);
        }
    }

    /** 数量字段兼容：count/num/amount/cnt/quantity/value（网页版 integerLongMap 兼容 num/amount 口径）。 */
    static long backpackCount(Map<String, Object> item) {
        if (item == null) return 0L;
        for (String k : new String[]{"count", "num", "amount", "cnt", "quantity", "value"}) {
            Object v = item.get(k);
            if (v != null) {
                long c = Exec.longOf(v);
                if (c > 0L) return c;
            }
        }
        return 0L;
    }

    /**
     * 叠加最近一条 notify_backpack_update{type,data:[{id,count}]}（抓包 10-14 02:24 type=add 实证）：
     * add=累加；del/delete/remove/reduce/sub=扣减（下限 0）；其它/缺失 type 不猜测、保持快照，
     * 避免把增量事件误当全量快照覆盖（宁漏不花）。
     */
    static void applyBackpackUpdate(Map<Long, Long> items, Map<String, Object> update) {
        if (items == null || update == null) return;
        Object raw = update.containsKey("data") ? update.get("data") : update.get("items");
        if (raw == null) return;
        String type = String.valueOf(update.get("type")).trim().toLowerCase(java.util.Locale.ROOT);
        Map<Long, Long> delta = new LinkedHashMap<>();
        mergeBackpackEntries(delta, raw);
        for (Map.Entry<Long, Long> e : delta.entrySet()) {
            long id = e.getKey();
            long count = e.getValue();
            switch (type) {
                case "add":
                    items.merge(id, count, Long::sum);
                    break;
                case "del":
                case "delete":
                case "remove":
                case "reduce":
                case "sub":
                    items.put(id, Math.max(0L, items.getOrDefault(id, 0L) - count));
                    break;
                default:
                    break;   // 未知增量类型：不猜，保持快照
            }
        }
    }
}
