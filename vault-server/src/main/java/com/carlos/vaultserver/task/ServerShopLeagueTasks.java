package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static final long[] SHOP_IDS_TOWN = {801, 701, 1001, 901, 1201, 301, 2001, 601, 1101, 2401};
    public static final long[] SHOP_IDS_ALLIANCE = {401, 501};

    public static TaskOutcome shopBuy(TaskContext tc) {
        return buyFromShops(tc, SHOP_IDS_TOWN, "shop", "商店购买");
    }

    public static TaskOutcome allianceShop(TaskContext tc) {
        return buyFromShops(tc, SHOP_IDS_ALLIANCE, "alliance_shop", "联盟商店");
    }

    private static TaskOutcome buyFromShops(TaskContext tc, long[] shopIds,
                                            String fnId, String fnName) {
        Exec.Multi m = new Exec.Multi();
        try {
            JSONObject p = tc.params(fnId);
            JSONObject buys = p.optJSONObject("buys");
            if (buys == null || buys.length() == 0) {
                m.skip("未勾选商品（设置页配置）");
                return Exec.result(m, fnId, fnName);
            }
            Map<Long, Long> want = new HashMap<>();
            for (Iterator<String> it = buys.keys(); it.hasNext(); ) {
                String k = it.next();
                want.put(Long.parseLong(k), buys.optLong(k));
            }
            List<Object> arr = new ArrayList<>();
            for (long sid : shopIds) arr.add(sid);
            GameMessage info = tc.engine.request("req_shop_info_array", Exec.args("arr_id", arr));
            if (!Exec.ok(info)) {
                m.skip("商店数据拉取失败(码" + Exec.codeOf(info) + ")");
                return Exec.result(m, fnId, fnName);
            }
            Map<String, Object> mapInfo = Exec.mapOf(info.getContent().get("map_info"));
            if (mapInfo == null) {
                m.skip("商店响应缺商品表");
                return Exec.result(m, fnId, fnName);
            }
            Map<Long, Good> catalog = new HashMap<>();
            for (List<Good> gl : shops().values()) {
                for (Good g : gl) catalog.put(g.configId, g);
            }
            int bought = 0;
            for (Map.Entry<String, Object> e : mapInfo.entrySet()) {
                long shopId = Long.parseLong(e.getKey());
                Map<String, Object> shopInfo = Exec.mapOf(e.getValue());
                if (shopInfo == null) continue;
                for (Map<String, Object> g : Exec.mapsOf(shopInfo.get("goods"))) {
                    long confId = Exec.longOf(g.get("conf_id"));
                    Long w = want.get(confId);
                    if (w == null || w <= 0) continue;
                    long goodsId = Exec.longOf(g.get("id"));
                    long boughtCount = Exec.longOf(g.get("buy_count"));
                    Good meta = catalog.get(confId);
                    long limit = meta == null ? 0 : meta.limit;
                    long remaining = limit > 0 ? Math.max(0, limit - boughtCount) : w;
                    if (remaining <= 0) continue;
                    long count = Math.min(w, remaining);
                    GameMessage buy = tc.engine.request("req_shop_buy", Exec.args(
                            "shop_id", shopId, "goods_id", goodsId, "count", count));
                    int code = Exec.codeOf(buy);
                    if (Exec.ok(buy)) {
                        bought++;
                        m.claimed((meta == null ? confId : meta.name) + "×" + count);
                        m.rewards(meta == null ? "商品" + confId : meta.name, buy);
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
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, fnName);
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, fnId, fnName);
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

    // ================= 联盟类（App LeagueTasks 移植） =================

    public static TaskOutcome mobilization(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            GameMessage info = tc.engine.request("req_mobilization_open_info", null);
            if (!Exec.ok(info)) {
                m.skip("总动员未开启(码" + Exec.codeOf(info) + ")");
            } else {
                Map<String, Object> ni = tc.engine.notifyContent("notify_mobilization_init");
                int claimed = 0;
                if (ni != null) {
                    for (String key : new String[]{"quests", "quest_list", "data"}) {
                        for (Map<String, Object> q : Exec.mapsOf(ni.get(key))) {
                            long pos = Exec.longOf(q.get("pos"));
                            long progress = Exec.longOf(q.get("progress"));
                            long target = Exec.longOf(q.get("target"));
                            if (pos > 0 && target > 0 && progress >= target) {
                                GameMessage r = tc.engine.request("req_mobilization_get_quest_reward",
                                        Exec.args("pos", pos));
                                if (Exec.ok(r)) {
                                    claimed++;
                                    m.rewards("任务奖励", r);
                                }
                            }
                        }
                    }
                }
                if (claimed > 0) m.claimed("任务奖励×" + claimed);
                GameMessage buy = tc.engine.request("req_mobilization_buy_count", Exec.args("count", 1));
                if (Exec.ok(buy)) m.claimed("接取次数+1");
                GameMessage ms = tc.engine.request("req_mobilization_batch_get_milestone", null);
                if (Exec.ok(ms)) {
                    m.claimed("里程碑");
                    m.rewards("里程碑", ms);
                }
                else if (Exec.codeOf(ms) != 8413) m.skip("里程碑码" + Exec.codeOf(ms));
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "联盟总动员");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "alliance_mobilization", "联盟总动员");
    }

    public static TaskOutcome showdown(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            GameMessage info = tc.engine.request("req_alliance_showdown_activity_info", null);
            if (!Exec.ok(info)) {
                m.skip("争霸赛未开启(码" + Exec.codeOf(info) + ")");
            } else {
                Map<String, Object> all = info.getContent();
                boolean signed = Exec.longOf(all == null ? null : all.get("apply")) == 1
                        || "true".equals(String.valueOf(all == null ? null : all.get("is_apply")));
                if (signed) {
                    m.skip("已报名");
                } else {
                    Map<String, Object> formation = Exec.formation(tc.engine, 1);
                    GameMessage apply = tc.engine.request("req_alliance_showdown_apply", Exec.args(
                            "type", 1L, "formation", formation));
                    if (Exec.ok(apply)) m.claimed("已报名赛道1");
                    else m.skip("报名码" + Exec.codeOf(apply));
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "联盟争霸赛");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "alliance_showdown", "争霸赛");
    }

    public static TaskOutcome allianceHelp(TaskContext tc) {
        // 常驻互助（ServerHelpResponder）接管中：它 30s 轮询持续刷新 help_all_ts，
        // 3 分钟内有过响应/巡检即视为已覆盖，跳过本轮重复请求（服务器上整轮仅在常驻会话跑，此跳过常态生效）
        try {
            long ts = tc.modState("alliance_help").optLong("help_all_ts", 0);
            if (ts > 0 && System.currentTimeMillis() - ts < 180_000L) {
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
                        "常驻互助接管中，跳过本轮");
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
            if (code == 75 || code == 2 || code == 1536) {
                return new TaskOutcome("alliance_help", "联盟互助", TaskOutcome.Category.SKIPPED,
                        code == 1536 ? "今日帮助次数已用完或无请求" : "当前无互助请求或不可用");
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

    public static TaskOutcome backpack(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> bi = tc.engine.notifyContent("notify_backpack_init");
            Map<Long, Long> items = Exec.kvPairs(bi == null ? null : bi.get("data"));
            if (items == null || items.isEmpty()) {
                items = Exec.kvPairs(bi == null ? null : bi.get("items"));
            }
            if (items.isEmpty()) {
                m.skip("背包数据未同步");
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
                    m.skip("无资源包可开");
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
}
