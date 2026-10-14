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

    static final long[] SHOP_IDS_TOWN = {801, 701, 1001, 901, 1201, 301, 2001, 601, 1101, 2401};
    static final long[] SHOP_IDS_ALLIANCE = {401, 501};

    static DailyResult shopBuy(WjdrEngine engine) {
        return buyFromShops(engine, SHOP_IDS_TOWN, "shop", "商店购买");
    }

    static DailyResult allianceShop(WjdrEngine engine) {
        return buyFromShops(engine, SHOP_IDS_ALLIANCE, "alliance_shop", "联盟商店");
    }

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

    /** 一轮货架采购：对每个勾选名字买货架上最便宜的一档×1；错误码视为该名字本轮跳过。 */
    private static void buyWantedPass(WjdrEngine engine, ExecUtil.Multi m, Map<String, Object> mapInfo,
                                      Map<Long, Good> catalog, java.util.Set<String> wanted,
                                      java.util.Set<String> bought) throws Exception {
        Map<String, long[]> best = new HashMap<>();   // name → {price, shopId, goodsId}
        for (Map.Entry<String, Object> e : mapInfo.entrySet()) {
            long shopId = Long.parseLong(e.getKey());
            Map<String, Object> shopInfo = ExecUtil.mapOf(e.getValue());
            if (shopInfo == null) continue;
            for (Map<String, Object> g : ExecUtil.mapsOf(shopInfo.get("goods"))) {
                long confId = ExecUtil.longOf(g.get("conf_id"));
                Good meta = catalog.get(confId);
                if (meta == null) continue;
                String name = meta.name == null || meta.name.isEmpty()
                        ? String.valueOf(confId) : meta.name;
                if (!wanted.contains(name) || bought.contains(name)) continue;
                long boughtCount = ExecUtil.longOf(g.get("buy_count"));
                if (meta.limit > 0 && boughtCount >= meta.limit) continue;
                long price = livePriceOf(g, meta.price);
                long[] cur = best.get(name);
                if (cur == null || price < cur[0]) {
                    best.put(name, new long[]{price, shopId, ExecUtil.longOf(g.get("id"))});
                }
            }
        }
        for (String name : wanted) {
            if (bought.contains(name)) continue;
            long[] b = best.get(name);
            if (b == null) continue;
            GameMessage buy = engine.request("req_shop_buy", ExecUtil.args(
                    "shop_id", b[1], "goods_id", b[2], "count", 1));
            int code = ExecUtil.codeOf(buy);
            bought.add(name);   // 无论成败，本轮不再重试该名字
            if (ExecUtil.ok(buy)) {
                m.claimed(name + "×1（" + b[0] + "）");
            } else if ((code >= 800 && code <= 830) || code == 10521 || code == 40240) {
                m.skip(name + " 条件不满足");
            } else {
                m.skip(name + " 码" + code);
            }
        }
    }

    /** 流动商人钻石刷新当日用量（进程内；按自然日重置）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> DIAMOND_REFRESH_DAILY =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int diamondRefreshUsedToday(String fpid, long sid) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                .format(new java.util.Date());
        long[] v = DIAMOND_REFRESH_DAILY.get(fpid + ":" + sid);
        if (v == null || String.valueOf(v[1]).equals(day) != true) return 0;
        return (int) v[0];
    }

    private static void diamondRefreshSetToday(String fpid, long sid, int set) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                .format(new java.util.Date());
        DIAMOND_REFRESH_DAILY.put(fpid + ":" + sid, new long[]{set, Long.parseLong(day)});
    }

    private static DailyResult buyFromShops(WjdrEngine engine, long[] shopIds,
                                            String fnId, String fnName) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), fnId);
            JSONObject buys = p.optJSONObject("buys");
            JSONObject buyNames = p.optJSONObject("buy_names");
            if (buys == null || buys.length() == 0) {
                m.skip("未勾选商品（设置页配置）");
                return result(m, fnId, fnName);
            }
            List<Object> arr = new ArrayList<>();
            for (long sid : shopIds) arr.add(sid);
            GameMessage info = engine.request("req_shop_info_array", ExecUtil.args("arr_id", arr));
            if (!ExecUtil.ok(info)) {
                m.skip("商店数据拉取失败(码" + ExecUtil.codeOf(info) + ")");
                return result(m, fnId, fnName);
            }
            Map<String, Object> mapInfo = ExecUtil.mapOf(info.getContent().get("map_info"));
            if (mapInfo == null) {
                m.skip("商店响应缺商品表");
                return result(m, fnId, fnName);
            }
            Map<Long, Good> catalog = new HashMap<>();
            for (List<Good> gl : shops(engine.appContext()).values()) {
                for (Good g : gl) catalog.put(g.configId, g);
            }
            // 名字模式（新选择器）：同名商品多档折扣位合并勾选，运行时买货架上最便宜的一档。
            // 流动商人(801)支持刷新循环：先免费刷新3次，之后按子项开关/策略用钻石刷新，直到刷齐勾选商品
            if (buyNames != null && buyNames.length() > 0) {
                android.content.Context sctx = engine.appContext();
                String sfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
                long ssid = engine.lastServerId();
                boolean diamondRefresh = FnParams.subOn(sctx, sfpid, ssid, fnId, "refresh_diamond", false);
                int diamondMax = 0;
                try {
                    diamondMax = Integer.parseInt(FnParams.strategy(sctx, sfpid, ssid,
                            fnId, "refresh_diamond", "0").trim());
                } catch (Exception ignored) {
                }
                boolean refreshShop = containsShop(shopIds, 801L);
                java.util.Set<String> wanted = new java.util.HashSet<>();
                for (Iterator<String> it = buyNames.keys(); it.hasNext(); ) wanted.add(it.next());
                java.util.Set<String> bought = new java.util.HashSet<>();
                int freeUsed = 0;
                int diamondUsed = diamondRefreshUsedToday(sfpid, ssid);
                int maxPass = refreshShop ? 3 + Math.max(0, diamondMax) : 0;
                for (int pass = 0; pass <= maxPass; pass++) {
                    buyWantedPass(engine, m, mapInfo, catalog, wanted, bought);
                    if (bought.containsAll(wanted) || !refreshShop) break;
                    boolean canRefresh;
                    if (freeUsed < 3) {
                        canRefresh = true;
                    } else {
                        canRefresh = diamondRefresh && diamondUsed < diamondMax;
                    }
                    if (!canRefresh) break;
                    GameMessage rf = engine.request("req_shop_refresh",
                            ExecUtil.args("shop_id", 801L));
                    if (!ExecUtil.ok(rf)) {
                        m.skip("刷新货架失败(码" + ExecUtil.codeOf(rf) + ")");
                        break;
                    }
                    if (freeUsed < 3) {
                        freeUsed++;
                    } else {
                        diamondUsed++;
                        diamondRefreshSetToday(sfpid, ssid, diamondUsed);
                    }
                    m.claimed("刷新货架（" + (freeUsed < 3 ? "免费" : "钻石") + "第" + (freeUsed < 3 ? freeUsed : diamondUsed) + "次）");
                    GameMessage info2 = engine.request("req_shop_info_array", ExecUtil.args("arr_id", arr));
                    if (ExecUtil.ok(info2)) {
                        Map<String, Object> mi2 = ExecUtil.mapOf(info2.getContent().get("map_info"));
                        if (mi2 != null) mapInfo = mi2;
                    }
                }
                if (bought.isEmpty() && m.sb.length() == 0) m.skip("货架无勾选商品");
                if (!bought.containsAll(wanted)) {
                    java.util.List<String> miss = new ArrayList<>();
                    for (String w : wanted) if (!bought.contains(w)) miss.add(w);
                    m.skip("未刷到: " + String.join("、", miss));
                }
                return result(m, fnId, fnName);
            }
            Map<Long, Long> want = new HashMap<>();   // configId → 数量
            for (Iterator<String> it = buys.keys(); it.hasNext(); ) {
                String k = it.next();
                want.put(Long.parseLong(k), buys.optLong(k));
            }
            int bought = 0;
            for (Map.Entry<String, Object> e : mapInfo.entrySet()) {
                long shopId = Long.parseLong(e.getKey());
                Map<String, Object> shopInfo = ExecUtil.mapOf(e.getValue());
                if (shopInfo == null) continue;
                for (Map<String, Object> g : ExecUtil.mapsOf(shopInfo.get("goods"))) {
                    long confId = ExecUtil.longOf(g.get("conf_id"));
                    Long w = want.get(confId);
                    if (w == null || w <= 0) continue;
                    long goodsId = ExecUtil.longOf(g.get("id"));
                    long boughtCount = ExecUtil.longOf(g.get("buy_count"));
                    Good meta = catalog.get(confId);
                    long limit = meta == null ? 0 : meta.limit;
                    long remaining = limit > 0 ? Math.max(0, limit - boughtCount) : w;
                    if (remaining <= 0) continue;
                    long count = Math.min(w, remaining);
                    GameMessage buy = engine.request("req_shop_buy", ExecUtil.args(
                            "shop_id", shopId, "goods_id", goodsId, "count", count));
                    int code = ExecUtil.codeOf(buy);
                    if (ExecUtil.ok(buy)) {
                        bought++;
                        m.claimed((meta == null ? confId : meta.name) + "×" + count);
                    } else if (code >= 800 && code <= 830) {
                        m.skip((meta == null ? confId : meta.name) + "条件不满足");
                    } else if (code == 10521 || code == 40240) {
                        m.skip((meta == null ? confId : meta.name) + "条件不满足");
                    } else {
                        m.skip((meta == null ? confId : meta.name) + "码" + code);
                    }
                }
            }
            if (bought == 0 && m.sb.length() == 0) m.skip("货架无勾选商品");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, fnName);
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, fnId, fnName);
    }

    // ================= 烈焰与獠牙（参数 buys: {index: 数量}） =================

    static DailyResult flamefangs(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "act_flamefangs");
            JSONObject buys = p.optJSONObject("buys");
            Map<String, Object> ni = engine.notifyContent("notify_spar_leak_info");
            Map<String, Object> base = ni == null ? null : ExecUtil.mapOf(ni.get("base"));
            long actId = base == null ? 0 : ExecUtil.longOf(base.get("id"));
            long tableId = ni == null ? 0 : ExecUtil.longOf(ni.get("table_id"));
            long stage = base == null ? 0 : ExecUtil.longOf(base.get("cur_stage_idx"));
            if (ni == null || actId <= 0 || tableId <= 0 || (stage != 2 && stage != 3)) {
                m.skip("兑换未开放（活动进行/兑换期才可买）");
                return result(m, "act_flamefangs", "烈焰獠牙");
            }
            if (buys == null || buys.length() == 0) {
                m.skip("未勾选兑换项（设置页配置）");
                return result(m, "act_flamefangs", "烈焰獠牙");
            }
            Map<Long, Long> bought = new HashMap<>();
            Object bi = ni.get("buy_info");
            if (bi instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) bi).entrySet()) {
                    try {
                        bought.put(Long.parseLong(String.valueOf(e.getKey())),
                                ExecUtil.longOf(e.getValue()));
                    } catch (Exception ignored) {
                    }
                }
            } else {
                for (Map<String, Object> x : ExecUtil.mapsOf(bi)) {
                    bought.put(ExecUtil.longOf(x.get("id")), ExecUtil.longOf(x.get("count")));
                }
            }
            Map<Long, LeakGood> catalog = new HashMap<>();
            for (LeakGood l : leakGoods(engine.appContext())) {
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
                GameMessage r = engine.request("req_spar_leak_buy", ExecUtil.args(
                        "activity_id", actId, "index", index, "count", count));
                if (ExecUtil.ok(r)) {
                    ok++;
                    m.claimed("档" + index + "×" + count);
                } else {
                    m.skip("档" + index + "码" + ExecUtil.codeOf(r));
                }
            }
            if (ok == 0 && m.sb.length() == 0) m.skip("本轮无可兑换");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "烈焰獠牙");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, "act_flamefangs", "烈焰獠牙");
    }

    // ================= 社交礼物（参数 targets:[fid], gift_id, count） =================

    static DailyResult socialGift(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 子开关门控：send=按FID赠送（清单/礼物仍需在参数里配置，未配置照旧跳过）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "social_gift", "send", true)) {
                m.skip("子开关「按FID赠送」已关闭");
                m.subSkip("send", "子开关已关闭");
                return result(m, "social_gift", "社交礼物");
            }
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "social_gift");
            JSONArray targets = p.optJSONArray("targets");
            long giftId = p.optLong("gift_id", 0);
            long count = p.optLong("count", 1);
            if (targets == null || targets.length() == 0 || giftId <= 0) {
                m.skip("未配置收礼人或礼物（设置页配置）");
                return result(m, "social_gift", "社交礼物");
            }
            // 数量档位校验（对标原版"按目录校验"）：目录给出可选档位（基础礼物 1/10/20/50/100）
            // 时不允许任意值；高级礼物目录无档位（amountOptions 为空）则交给服务端判定。
            for (Gift g : gifts(engine.appContext())) {
                if (g.giftId == giftId && !g.amountOptions.isEmpty()
                        && !g.amountOptions.contains(count)) {
                    m.skip("数量 " + count + " 不在可选档位 " + g.amountOptions
                            + "（" + g.name + "）");
                    return result(m, "social_gift", "社交礼物");
                }
            }
            // 同意须知（幂等）
            engine.request("req_social_gift_set_disclaimer", null);
            int sent = 0;
            for (int i = 0; i < targets.length(); i++) {
                String fid = targets.optString(i, "").trim();
                if (fid.isEmpty()) continue;
                GameMessage lookup = engine.request("req_player_info_by_fid",
                        ExecUtil.args("fid", fid));
                Map<String, Object> r = ExecUtil.ok(lookup)
                        ? ExecUtil.mapOf(lookup.getContent().get("result")) : null;
                long uid = r == null ? 0 : ExecUtil.longOf(r.get("uid"));
                if (uid <= 0) {
                    m.skip("FID " + fid + " 未找到");
                    continue;
                }
                GameMessage send = engine.request("req_social_gift_send", ExecUtil.args(
                        "gift_id", giftId, "target_uid", uid, "count", count,
                        "anonymous", false, "allow_buy", false));
                int code = ExecUtil.codeOf(send);
                if (ExecUtil.ok(send)) {
                    sent++;
                    m.claimed("→" + (r.get("nickName") != null ? r.get("nickName") : uid));
                } else if (code == 41566) {
                    engine.request("req_social_gift_set_disclaimer", null);
                    m.skip("需同意玩家须知（已重试同意，下轮生效）");
                } else if (code == 41558) {
                    m.skip("礼物未解锁");
                } else {
                    m.skip("FID" + fid + "码" + code);
                }
            }
            if (sent == 0 && m.sb.length() == 0) m.skip("未送出");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "社交礼物");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, "social_gift", "社交礼物");
    }

    // ================= 燃霜矿区（报名+进场+里程碑+分数；占矿循环下一版） =================

    static final String[] FROSTFIRE_SLOTS = {"12:30", "16:00", "19:00", "20:30", "22:00"};

    static DailyResult frostfire(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 子开关门控：apply=按时段报名 / milestone=里程碑领奖+积分上报
            // （报名同时要求已勾选时段，两个条件都满足才发报名）
            boolean onApply = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "act_frostfire", "apply", true);
            boolean onMilestone = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "act_frostfire", "milestone", true);
            if (!onApply && !onMilestone) {
                m.skip("子开关已全部关闭");
                m.subSkip("apply", "已关闭"); m.subSkip("milestone", "已关闭");
                return result(m, "act_frostfire", "燃霜矿区");
            }
            engine.collectNotifies(2000);
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "act_frostfire");
            JSONArray slots = p.optJSONArray("slots");
            if (!onApply) {
                m.subSkip("apply", "已关闭");
            } else if (slots == null || slots.length() == 0) {
                m.skip("未选择报名时段（设置页配置）");
                m.subSkip("apply", "未选时段");
                return result(m, "act_frostfire", "燃霜矿区");
            } else {
            long slot = slots.optLong(0);
            Map<String, Object> wi = engine.notifyContent("notify_frostfire_war_info");
            Map<String, Object> actInfo = wi == null ? null : ExecUtil.mapOf(wi.get("act_info"));
            long signUpIndex = actInfo == null ? 0 : ExecUtil.longOf(actInfo.get("sign_up_index"));
            long signUpTs = actInfo == null ? 0 : ExecUtil.longOf(actInfo.get("sign_up_ts"));
            boolean signedToday = signUpIndex > 0 && signUpTs > 0
                    && System.currentTimeMillis() / 86400000L == signUpTs / 86400L;
            if (signedToday) {
                m.skip("今日已报名(" + signUpIndex + ")");
            } else {
                GameMessage apply = engine.request("req_frostfire_war_apply",
                        ExecUtil.args("index", slot));
                int code = ExecUtil.codeOf(apply);
                if (ExecUtil.ok(apply)) {
                    m.claimed("已报名 " + FROSTFIRE_SLOTS[(int) Math.max(0, slot - 1)]);
                } else if (code == 10724) {
                    GameMessage exit = engine.request("req_frostfire_war_exit_warzone", null);
                    int ec = ExecUtil.codeOf(exit);
                    if (ec == 0 || ec == 10714) {
                        GameMessage retry = engine.request("req_frostfire_war_apply",
                                ExecUtil.args("index", slot));
                        if (ExecUtil.ok(retry)) m.claimed("退旧场后已报名");
                        else m.skip("重报名码" + ExecUtil.codeOf(retry));
                    } else {
                        m.skip("退场码" + ec);
                    }
                } else {
                    m.skip(code == 10717 ? "未到报名时间" : "报名码" + code);
                }
            }
            }
            // 已在战区：领里程碑 + 报分数
            if (!onMilestone) {
                m.subSkip("milestone", "已关闭");
                return result(m, "act_frostfire", "燃霜矿区");
            }
            Map<String, Object> pi = engine.notifyContent("notify_frostfire_war_player_init");
            long state = pi == null ? 0 : ExecUtil.longOf(pi.get("state"));
            if (state == 2 || state == 3) {
                for (int idx = 1; idx <= 5; idx++) {
                    GameMessage r = engine.request("req_frostfire_war_get_collect_reward",
                            ExecUtil.args("idx", idx));
                    int code = ExecUtil.codeOf(r);
                    if (ExecUtil.ok(r)) m.claimed("里程碑" + idx);
                    else if (code != 10746 && code != 10748) break;
                }
                GameMessage rank = engine.request("req_frostfire_war_self_score_rank", null);
                if (ExecUtil.ok(rank)) {
                    Object self = rank.getContent().get("self_value");
                    if (self != null) m.claimed("当前积分" + self);
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "燃霜矿区");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, "act_frostfire", "燃霜矿区");
    }

    // ================= 雪原贸易站（活动族 2702；协议盘点 2026-10-12 真机快照实证） =================
    //
    // notify_value_exchange_init.ve_info = {id:2702001, exchange_data:{idx:已兑次数},
    //   is_free_exchange}（快照实证：进游戏 132:0 → 出游戏 132:1，兑换进度按角色隔离）。
    // 购买：req_value_exchange_exchange{idx,count}；免费兑：req_value_exchange_free_exchange{idx}。
    // 商品目录在游戏客户端本地资源、服务器不推送——用户按游戏内档位号配置：
    //   p:act_snowtrade.free_idx = 132（免费档，默认 132：K913 手动兑的档，K888 未兑）
    //   p:act_snowtrade.buys = {"155": 3, ...}（idx→目标数量，超出限购由服务端拒绝）

    static DailyResult snowTrade(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            boolean onFree = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "act_snowtrade", "free", true);
            boolean onBuy = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "act_snowtrade", "buy", false);
            if (!onFree && !onBuy) {
                m.skip("子开关已全部关闭");
                m.subSkip("free", "已关闭");
                m.subSkip("buy", "已关闭");
                return result(m, "act_snowtrade", "雪原贸易站");
            }
            engine.collectNotifies(1500);
            Map<String, Object> vi = engine.notifyData("notify_value_exchange_init");
            Map<String, Object> ve = ExecUtil.mapOf(vi == null ? null : vi.get("ve_info"));
            long actId = ve == null ? 0 : ExecUtil.longOf(ve.get("id"));
            if (ve == null || actId <= 0) {
                mod(engine, "act_snowtrade", "free", "活动未开放（无 ve_info 推送）");
                mod(engine, "act_snowtrade", "buy", "活动未开放（无 ve_info 推送）");
                m.skip("雪原贸易站未开放（ve_info 未同步）");
                m.subSkip("free", "活动未开放");
                m.subSkip("buy", "活动未开放");
                return result(m, "act_snowtrade", "雪原贸易站");
            }
            // 已兑次数表（外层键形如 "132.0"，值形如 {k:132,v:0}；兼容纯数字形状）
            Map<Long, Long> bought = new HashMap<>();
            Object ed = ve.get("exchange_data");
            if (ed instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) ed).entrySet()) {
                    try {
                        long idx = Long.parseLong(String.valueOf(e.getKey()).replace(".0", ""));
                        Object vObj = e.getValue();
                        long cnt;
                        if (vObj instanceof Map) {
                            Map<String, Object> vm = ExecUtil.mapOf(vObj);
                            cnt = ExecUtil.longOf(vm != null && vm.containsKey("v") ? vm.get("v") : vObj);
                        } else {
                            cnt = ExecUtil.longOf(vObj);
                        }
                        bought.put(idx, cnt);
                    } catch (Exception ignored) {
                    }
                }
            }
            boolean freeAvail = "true".equals(String.valueOf(ve.get("is_free_exchange")));
            // 缓存本活动出现过的档位号——功能页勾选清单据此自适应（服务器不推商品目录）
            try {
                JSONObject seen = new JSONObject();
                for (Long k : bought.keySet()) seen.put(String.valueOf(k), 1);
                FnParams.putTopLevel(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "act_snowtrade", "seen_idx", seen);
            } catch (Exception ignored) {
            }
            mod(engine, "act_snowtrade", "free", "活动" + actId + " · 免费兑"
                    + (freeAvail ? "可用" : "不可用") + " · 已兑 " + bought);
            // ① 免费兑（默认档 132；兑错档由服务端报错跳过，无副作用）
            if (!onFree) {
                m.subSkip("free", "已关闭");
            } else if (!freeAvail) {
                m.skip("免费兑不可用或已用");
                m.subSkip("free", "不可用");
            } else {
                long freeIdx = FnParams.params(engine.appContext(),
                        engine.lastFpid(), engine.lastServerId(), "act_snowtrade").optLong("free_idx", 132);
                if (bought.getOrDefault(freeIdx, 0L) > 0) {
                    m.skip("免费档" + freeIdx + "本角色已兑×" + bought.get(freeIdx));
                    m.subSkip("free", "本角色已兑");
                } else {
                    GameMessage fr = engine.request("req_value_exchange_free_exchange",
                            ExecUtil.args("idx", freeIdx));
                    if (ExecUtil.ok(fr)) {
                        m.claimed("免费兑档" + freeIdx);
                        m.subOk("free", "已兑档" + freeIdx);
                    } else {
                        m.skip("免费兑码" + ExecUtil.codeOf(fr));
                        m.subSkip("free", "码" + ExecUtil.codeOf(fr));
                    }
                }
            }
            // ② 按清单兑换（buys: idx→目标数量；已兑部分自动扣减）
            if (!onBuy) {
                m.subSkip("buy", "已关闭");
            } else {
                JSONObject buys = FnParams.params(engine.appContext(),
                        engine.lastFpid(), engine.lastServerId(), "act_snowtrade").optJSONObject("buys");
                if (buys == null || buys.length() == 0) {
                    mod(engine, "act_snowtrade", "buy", "清单未配置（已知档位 " + bought.keySet() + "，进页勾选）");
                    m.skip("未配置 buys（功能页 buy 子项勾选档位）");
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
                        GameMessage r = engine.request("req_value_exchange_exchange",
                                ExecUtil.args("idx", idx, "count", count));
                        if (ExecUtil.ok(r)) {
                            ok++;
                            m.claimed("档" + idx + "×" + count);
                            buySt.append("→补").append(count);
                        } else {
                            m.skip("档" + idx + "码" + ExecUtil.codeOf(r));
                            buySt.append("→码").append(ExecUtil.codeOf(r));
                        }
                    }
                    mod(engine, "act_snowtrade", "buy",
                            buySt.length() == 0 ? "清单无可识别档位" : buySt.toString());
                    if (ok == 0 && m.sb.length() == 0) m.skip("本轮无可兑换");
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "雪原贸易站");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return result(m, "act_snowtrade", "雪原贸易站");
    }

    /** 子项状态行写入助手。 */
    static void mod(WjdrEngine engine, String fnId, String subId, String text) {
        try {
            FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(), fnId, subId, text);
        } catch (Throwable ignored) {
        }
    }

    private static DailyResult result(ExecUtil.Multi m, String id, String name) {
        DailyResult r = new DailyResult(id, name, m.cat(), m.note() + " " + m.sb);
        r.rewards = m.rewardsText();
        r.subResults.putAll(m.subResults);
        return r;
    }
}
