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
            JSONObject root = new JSONObject(readAsset(ctx, "towndev/shops.json"));
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
            JSONArray arr = new JSONArray(readAsset(ctx, "towndev/flamefangs.json"));
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

    private static DailyResult buyFromShops(WjdrEngine engine, long[] shopIds,
                                            String fnId, String fnName) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), fnId);
            JSONObject buys = p.optJSONObject("buys");
            if (buys == null || buys.length() == 0) {
                m.skip("未勾选商品（设置页配置）");
                return result(m, fnId, fnName);
            }
            Map<Long, Long> want = new HashMap<>();   // configId → 数量
            for (Iterator<String> it = buys.keys(); it.hasNext(); ) {
                String k = it.next();
                want.put(Long.parseLong(k), buys.optLong(k));
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
            engine.collectNotifies(2000);
            JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "act_frostfire");
            JSONArray slots = p.optJSONArray("slots");
            if (slots == null || slots.length() == 0) {
                m.skip("未选择报名时段（设置页配置）");
                return result(m, "act_frostfire", "燃霜矿区");
            }
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
            // 已在战区：领里程碑 + 报分数
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

    private static DailyResult result(ExecUtil.Multi m, String id, String name) {
        return new DailyResult(id, name, m.cat(), m.note() + " " + m.sb);
    }
}
