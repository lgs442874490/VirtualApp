package com.carlos.vault.daily;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 任务行"展开态"的子项卡片（就地配置，与 DailyActivity.subCard 同逻辑的静态版，
 * 供新界面的任务页行内展开使用）。
 */
public final class SubCardUi {

    /** 构建某功能的全部子项卡片（含状态行/开关/策略/兵营等级下拉）。 */
    public static View build(final Context ctx, final String fpid, final long sid, String fnId) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        JSONObject modState = FnParams.modState(ctx, fpid, sid, fnId);
        for (final FnDetail.Sub sub : FnDetail.of(fnId)) {
            box.addView(subCard(ctx, fpid, sid, fnId, sub, modState));
        }
        if (FnDetail.of(fnId).isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("该功能暂无子项配置");
            empty.setTextSize(12);
            empty.setTextColor(UiStyle.INK2);
            empty.setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 8));
            box.addView(empty);
        }
        return box;
    }

    private static View subCard(final Context ctx, final String fpid, final long sid,
                                final String fnId, final FnDetail.Sub sub, JSONObject modState) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiStyle.roundRect(UiStyle.SURFACE_2, dp(ctx, 12)));
        card.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(ctx, 6);
        card.setLayoutParams(clp);

        LinearLayout l1 = new LinearLayout(ctx);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(sub.name);
        name.setTextSize(13);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        l1.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // B表子项级：当日结果点（✓绿/✗红），有记录才显示
        try {
            int sst = new LogStore(ctx).subStatus(fpid, sid, fnId, sub.id);
            if (sst == LogStore.ST_DONE || sst == LogStore.ST_FAILED) {
                TextView mark = new TextView(ctx);
                mark.setText(sst == LogStore.ST_DONE ? "✓ 今日" : "✗ 今日");
                mark.setTextSize(10.5f);
                mark.setTypeface(Typeface.DEFAULT_BOLD);
                mark.setTextColor(sst == LogStore.ST_DONE ? UiStyle.OK_INK : UiStyle.ERR_INK);
                l1.addView(mark);
            }
        } catch (Throwable ignored) {
        }
        final UiStyle.Switch[] ref = new UiStyle.Switch[1];
        // 军医所「治疗后联盟求助」：服务端 MedicService 从角色 params 的**顶层** request_help
        // 读取（键名契约锁定），故该子项开关写顶层键而非 sub:{...}，避免写成 sub.request_help
        // 导致服务端读不到（2026-10-28 bug3）。
        final boolean topLevelRequestHelp = "medic".equals(fnId) && "help".equals(sub.id);
        ref[0] = new UiStyle.Switch(ctx,
                topLevelRequestHelp
                        ? FnParams.medicRequestHelp(FnParams.params(ctx, fpid, sid, fnId))
                        : FnParams.subOn(ctx, fpid, sid, fnId, sub.id, sub.defOn),
                () -> {
                    if (topLevelRequestHelp) {
                        FnParams.putTopLevel(ctx, fpid, sid, fnId, "request_help", ref[0].isOn());
                    } else {
                        FnParams.saveSubOn(ctx, fpid, sid, fnId, sub.id, ref[0].isOn());
                    }
                });
        UiStyle.Switch sw = ref[0];
        l1.addView(sw);
        card.addView(l1);

        TextView desc = new TextView(ctx);
        desc.setText(sub.desc);
        desc.setTextSize(11);
        desc.setTextColor(UiStyle.INK2);
        desc.setPadding(0, dp(ctx, 3), 0, 0);
        card.addView(desc);

        String status = modState.optString(sub.id, "");
        if (!status.isEmpty()) {
            long ts = modState.optLong(sub.id + "_ts", 0);
            if (ts > 0 && ts < 100_000_000_000L) ts *= 1000L;   // 兼容旧版秒级 _ts（writeModState 曾写秒）
            String trg = modState.optString(sub.id + "_trg", "");
            // 时效防御：快照超过 26 小时视为过期（如"今日 x/5""下次还 N 小时"跨天失真、
            // 补给/兵营的旧时刻），前缀 ⏳ 提示数据非当日，避免误导。
            boolean stale = ts > 0 && System.currentTimeMillis() - ts > 26 * 3600_000L;
            String suffix = ts > 0 ? "（" + new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.CHINA).format(new java.util.Date(ts))
                    + (stale ? " 快照" : "·" + (trg.isEmpty() ? "更新" : trg)) + "）" : "";
            TextView st = new TextView(ctx);
            st.setText((stale ? "⏳ " : "") + status + suffix);
            st.setTextSize(11);
            st.setTextColor(stale ? UiStyle.WARN_INK : UiStyle.BRAND_TEXT);   // WARN_INK
            st.setPadding(0, dp(ctx, 3), 0, 0);
            card.addView(st);
        } else {
            // 无实时状态时退回"上次动作"（B表子项级日志）
            try {
                LogStore.Entry le = new LogStore(ctx).subLast(fpid, sid, fnId, sub.id);
                if (le != null) {
                    TextView st = new TextView(ctx);
                    st.setText("上次 " + (le.ok ? "✓ " : "✗ ") + le.reason + " · "
                            + new java.text.SimpleDateFormat("MM-dd HH:mm",
                                    java.util.Locale.CHINA).format(new java.util.Date(le.ts)));
                    st.setTextSize(11);
                    st.setTextColor(UiStyle.INK2);
                    st.setPadding(0, dp(ctx, 3), 0, 0);
                    card.addView(st);
                }
            } catch (Throwable ignored) {
            }
        }

        // 兵营等级动态下拉（只列已解锁档；历史误选的超档值按解锁档收敛显示）
        if ("barracks".equals(fnId) && sub.id.startsWith("train_")) {
            String maxKey = sub.id.replace("train_", "max_");
            int maxTier = modState.optInt(maxKey, 0);
            String cur = FnParams.strategy(ctx, fpid, sid, fnId, sub.id, "auto");
            int curNum = 0;
            try {
                if (!"auto".equals(cur)) curNum = Integer.parseInt(cur.trim());
            } catch (Throwable ignored3) {
            }
            int show = maxTier > 0 && curNum > maxTier ? maxTier : curNum;
            if (show > 0 && show != curNum) {
                // 超出已解锁档：直接收敛存储，显示与执行一致
                FnParams.saveStrategy(ctx, fpid, sid, fnId, sub.id, String.valueOf(show));
                cur = String.valueOf(show);
            }
            final String curF = cur;
            String curText = show == 0 ? "自动最高" : "T" + show;
            final int fMaxTier = maxTier;
            card.addView(UiStyle.dropdownRow(ctx, "训练等级", curText, () -> {
                java.util.List<String> vals = new ArrayList<>();
                java.util.List<String> labels = new ArrayList<>();
                vals.add("auto");
                labels.add(maxTier > 0 ? "自动最高（该营已解锁档）" : "自动最高（等级未同步——打开App稍候自动同步）");
                // 等级未同步（maxTier=0）时不列具体档位，避免选到未解锁的高档
                for (int t = maxTier; t >= 1; t--) {
                    vals.add(String.valueOf(t));
                    labels.add("T" + t + (t == fMaxTier ? "（当前最高）" : ""));
                }
                int checked = vals.indexOf(curF);
                new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                        .setTitle(sub.name + " · 选择等级（只显示已解锁）")
                        .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, checked),
                                (dlg, w) -> {
                                    FnParams.saveStrategy(ctx, fpid, sid, fnId, sub.id, vals.get(w));
                                    dlg.dismiss();
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }));
        } else if ("act_frostfire".equals(fnId) && "apply".equals(sub.id)) {
            card.addView(frostfireSlotsRow(ctx, fpid, sid));
        } else if (("shop".equals(fnId) || "alliance_shop".equals(fnId)) && "buy".equals(sub.id)) {
            card.addView(shopPickRow(ctx, fpid, sid, fnId));
        } else if ("ally_offline_guard".equals(fnId) && "watch".equals(sub.id)) {
            card.addView(watchUidsRow(ctx, fpid, sid, fnId));
        } else if ("act_flamefangs".equals(fnId) && "buy".equals(sub.id)) {
            card.addView(flamefangsPickRow(ctx, fpid, sid));
        } else if ("act_snowtrade".equals(fnId) && "buy".equals(sub.id)) {
            card.addView(snowtradePickRow(ctx, fpid, sid));
        } else if (sub.strategies != null && sub.strategies.length > 0) {
            // 策略 chips（>5 个换行）
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(ctx, 6), 0, 0);
            String cur = FnParams.strategy(ctx, fpid, sid, fnId, sub.id, defaultStrategy(sub));
            final TextView[] chips = new TextView[sub.strategies.length];
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            wrap.addView(row);
            for (int i = 0; i < sub.strategies.length; i++) {
                if (i > 0 && i % 4 == 0) {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    wrap.addView(row);
                }
                final String val = sub.strategies[i][0];
                TextView chip = new TextView(ctx);
                chip.setText(sub.strategies[i][1]);
                chip.setTextSize(11);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5));
                applyChip(chip, val.equals(cur));
                chips[i] = chip;
                UiStyle.press(chip);
                chip.setOnClickListener(v -> {
                    FnParams.saveStrategy(ctx, fpid, sid, fnId, sub.id, val);
                    for (TextView c : chips) applyChip(c, c == v);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(ctx, 6);
                row.addView(chip, lp);
            }
            card.addView(wrap);
        }
        return card;
    }

    private static void applyChip(TextView v, boolean selected) {
        v.setTextColor(selected ? UiStyle.WHITE : UiStyle.INK2);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setBackground(UiStyle.roundRect(selected ? UiStyle.BRAND : UiStyle.SURFACE_3, dp(v.getContext(), 10)));
    }

    /**
     * chips 无已存值时的默认档：优先选 label 标「（默认）」的档（与 FnDetail 执行侧默认一致，
     * 兼容半角括号写法），无标注则维持首档。只影响 UI 高亮，不改变执行侧取值。
     */
    private static String defaultStrategy(FnDetail.Sub sub) {
        for (String[] s : sub.strategies) {
            if (s.length > 1 && s[1] != null
                    && (s[1].contains("（默认）") || s[1].contains("(默认)"))) return s[0];
        }
        return sub.strategies[0][0];
    }

    // ================= 清单选择（商店勾选 / 燃霜时段 / 烈焰獠牙档位） =================

    /** 店铺 id → 展示名（与 ShopTasks.SHOP_IDS_* 对应）。 */
    private static final java.util.Map<Long, String> SHOP_NAMES = new java.util.LinkedHashMap<>();

    static {
        SHOP_NAMES.put(801L, "流动商人");
        SHOP_NAMES.put(701L, "统帅商店");
        SHOP_NAMES.put(1001L, "兵工厂");
        SHOP_NAMES.put(901L, "争霸商店");
        SHOP_NAMES.put(1201L, "最强王国");
        SHOP_NAMES.put(301L, "竞技场商店");
        SHOP_NAMES.put(2001L, "地心商店");
        SHOP_NAMES.put(601L, "神秘商店");
        SHOP_NAMES.put(1101L, "火晶商店");
        SHOP_NAMES.put(2401L, "乱斗商店");
        SHOP_NAMES.put(401L, "联盟日店");
        SHOP_NAMES.put(501L, "联盟周店");
    }

    private static View pickRow(Context ctx, String label, String value, Runnable onClick) {
        return UiStyle.dropdownRow(ctx, label, value, onClick);
    }

    /** 燃霜矿区报名时段多选：写顶层 slots 数组（执行器取首个未到时段）。 */
    private static View frostfireSlotsRow(final Context ctx, final String fpid, final long sid) {
        final String[] slotNames = ShopTasks.FROSTFIRE_SLOTS;
        java.util.Set<String> cur = FnParams.selSet(ctx, fpid, sid, "act_frostfire", "apply");
        return pickRow(ctx, "报名时段", cur.isEmpty() ? "未选择（不报名）" : "已选 " + cur + "", () -> {
            final String[] labels = new String[slotNames.length];
            final boolean[] state = new boolean[slotNames.length];
            for (int i = 0; i < slotNames.length; i++) {
                labels[i] = "时段" + (i + 1) + " · " + slotNames[i];
                state[i] = cur.contains(String.valueOf(i + 1));
            }
            new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("燃霜矿区 · 勾选报名时段")
                    .setMultiChoiceItems(labels, state, (dlg, w, isChecked) -> state[w] = isChecked)
                    .setPositiveButton("保存", (dlg, w) -> {
                        java.util.List<String> vals = new ArrayList<>();
                        org.json.JSONArray arr = new org.json.JSONArray();
                        for (int i = 0; i < state.length; i++) {
                            if (state[i]) {
                                vals.add(String.valueOf(i + 1));
                                arr.put(i + 1);
                            }
                        }
                        FnParams.saveSelSet(ctx, fpid, sid, "act_frostfire", "apply", vals);
                        FnParams.putTopLevel(ctx, fpid, sid, "act_frostfire", "slots", arr);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    /** 商店/联盟商店勾选：按店铺分组浏览（在线拉实时货架：已购/折扣；缓存取币种余额），存 buys {configId:1}。 */
    /** 离线联动：关注角色 uid 输入（逗号分隔，保存为 watch_uids JSON 数组）。 */
    private static View watchUidsRow(final Context ctx, final String fpid, final long sid, final String fnId) {
        final JSONArray cur = FnParams.params(ctx, fpid, sid, fnId).optJSONArray("watch_uids");
        StringBuilder sb = new StringBuilder();
        if (cur != null) {
            for (int i = 0; i < cur.length(); i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(cur.optLong(i));
            }
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 4));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(ctx);
        label.setText("关注角色");
        label.setTextSize(12);
        label.setTextColor(UiStyle.INK2);
        row.addView(label);
        final EditText et = UiStyle.searchInput(ctx, "uid，逗号分隔，例：111,222");
        et.setText(sb.toString());
        row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = new TextView(ctx);
        save.setText("保存");
        save.setTextSize(12);
        save.setTextColor(0xFF2F6BFF);
        save.setPadding(dp(ctx, 8), 0, 0, 0);
        save.setOnClickListener(v -> {
            JSONArray arr = new JSONArray();
            for (String part : et.getText().toString().split("[,，\\s]+")) {
                if (part.isEmpty()) continue;
                try {
                    long uid = Long.parseLong(part.trim());
                    if (uid > 0L) arr.put(uid);
                } catch (Exception ignored) {
                }
            }
            FnParams.putTopLevel(ctx, fpid, sid, fnId, "watch_uids", arr);
            UiStyle.toast(ctx, "已保存 " + arr.length() + " 个关注角色");
        });
        row.addView(save);
        return row;
    }

    private static View shopPickRow(final Context ctx, final String fpid, final long sid, final String fnId) {
        long[] shopIds = "shop".equals(fnId) ? ShopTasks.SHOP_IDS_TOWN : ShopTasks.SHOP_IDS_ALLIANCE;
        final long[] shopIdsF = shopIds;
        final int preCount = buysCount(FnParams.params(ctx, fpid, sid, fnId).optJSONObject("buys"));
        return pickRow(ctx, "勾选商品",
                preCount == 0 ? "按店铺浏览并勾选（点开加载货架）" : "已勾选 " + preCount + " 件（点开修改）",
                () -> {
                    UiStyle.toast(ctx, "正在拉取货架…");
                    final java.util.List<Long> idsF = new ArrayList<>();
                    for (long id : shopIdsF) idsF.add(id);
                    new Thread(() -> {
                        // 后台线程：只认【精确 fpid:sid】的在线会话拉实时货架。
                        // 严禁跨角色兜底：同账号另一区的会话数据会串区显示（913/888 事故），宁可用目录价。
                        java.util.Map<Long, java.util.Map<String, Object>> live = new java.util.LinkedHashMap<>();
                        java.util.Map<Long, Long> bag = new java.util.LinkedHashMap<>();
                        java.util.Map<Long, Long> res = new java.util.LinkedHashMap<>();
                        boolean online = false;
                        String sessionTag = "";
                        try {
                            RoleSessionKeeper.LiveSession ls =
                                    RoleSessionKeeper.get(ctx).borrowOnline(fpid, sid);
                            if (ls != null && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                                online = true;
                                sessionTag = "本角色";
                                java.util.List<Object> arr = new ArrayList<>();
                                for (long id : idsF) arr.add(id);
                                GameMessage info = ls.engine.request("req_shop_info_array",
                                        ExecUtil.args("arr_id", arr));
                                if (ExecUtil.ok(info)) {
                                    java.util.Map<String, Object> mapInfo =
                                            ExecUtil.mapOf(info.getContent().get("map_info"));
                                    for (java.util.Map.Entry<String, Object> e : mapInfo.entrySet()) {
                                        try {
                                            live.put(Long.parseLong(e.getKey()),
                                                    ExecUtil.mapOf(e.getValue()));
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                java.util.Map<String, Object> bi = ls.engine.notifyContent("notify_backpack_init");
                                java.util.Map<Long, Long> kv = ExecUtil.kvPairs(bi == null ? null : bi.get("data"));
                                if (kv == null || kv.isEmpty()) kv = ExecUtil.kvPairs(bi == null ? null : bi.get("items"));
                                if (kv != null) bag = kv;
                                // 资源余额（肉木煤铁/钻石等 101-105 在 notify_resource_init，不在背包里）
                                java.util.Map<String, Object> ri = ls.engine.notifyContent("notify_resource_init");
                                java.util.Map<Long, Long> rk = ExecUtil.kvPairs(ri == null ? null
                                        : (ri.get("data") != null ? ri.get("data") : ri.get("items")));
                                if ((rk == null || rk.isEmpty()) && ri != null) rk = ExecUtil.kvPairs(ri);
                                if (rk != null) res = rk;
                            } else if (ls != null) {
                                // 会话存在但非在线（重连中/被顶号暂停）
                                sessionTag = "本角色会话重连中";
                            }
                        } catch (Throwable ignored) {
                        }
                        final java.util.Map<Long, java.util.Map<String, Object>> liveF = live;
                        final java.util.Map<Long, Long> bagF = bag;
                        final java.util.Map<Long, Long> resF = res;
                        final boolean onlineF = online;
                        final String tagF = online ? ("实时货架 · " + sessionTag) : "目录价（无在线会话）";
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                showShopDialog(ctx, fpid, sid, fnId, idsF, liveF, bagF, resF, onlineF, tagF));
                    }, "shop-pick-load").start();
                });
    }

    private static int buysCount(JSONObject buys) {
        return buys == null ? 0 : buys.length();
    }

    /** 店铺分页对话框（对标游戏内商店分类）：顶部店铺 tab 一次看一家；店头含币种+余额；
     *  在线时先展示"当前货架"（可立即购买），下方为自动采购清单；勾选状态跨页保持。 */
    private static void showShopDialog(final Context ctx, final String fpid, final long sid, final String fnId,
                                       java.util.List<Long> shopIds,
                                       java.util.Map<Long, java.util.Map<String, Object>> live,
                                       java.util.Map<Long, Long> bag,
                                       java.util.Map<Long, Long> res,
                                       boolean online, String sessionTag) {
        java.util.Map<Long, java.util.List<ShopTasks.Good>> catalog = ShopTasks.shops(ctx);
        JSONObject curBuys = FnParams.params(ctx, fpid, sid, fnId).optJSONObject("buys");

        // 预合并：shopId → (合并键 name|currency → variants)
        final java.util.LinkedHashMap<Long, java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>>> perShop =
                new java.util.LinkedHashMap<>();
        for (long shopId : shopIds) {
            java.util.List<ShopTasks.Good> goods = catalog.get(shopId);
            if (goods == null || goods.isEmpty()) continue;
            java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>> merged =
                    new java.util.LinkedHashMap<>();
            for (ShopTasks.Good g : goods) {
                String key = (g.name == null || g.name.isEmpty() ? String.valueOf(g.configId) : g.name)
                        + "|" + g.currencyId;
                java.util.List<ShopTasks.Good> l = merged.get(key);
                if (l == null) {
                    l = new ArrayList<>();
                    merged.put(key, l);
                }
                l.add(g);
            }
            perShop.put(shopId, merged);
        }
        // 勾选状态（plainName → bool），跨分页保持；初始来自已保存的 buys。
        // 2026-11-08：改用 LinkedHashMap 保留勾选顺序，保存为 buy_list（按顺序购买）。
        final java.util.LinkedHashMap<String, Boolean> sel = new java.util.LinkedHashMap<>();
        for (java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>> m : perShop.values()) {
            for (java.util.List<ShopTasks.Good> v : m.values()) {
                if (curBuys != null && checkName(curBuys, v)) {
                    sel.put(plainNameOf(v.get(0)), true);
                }
            }
        }
        // confId → Good 索引（实时货架行反查名字/币种/限购用）
        final java.util.Map<Long, ShopTasks.Good> byConf = new java.util.HashMap<>();
        for (java.util.List<ShopTasks.Good> gl : catalog.values()) {
            for (ShopTasks.Good g : gl) byConf.put(g.configId, g);
        }

        final android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        final android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        final android.widget.LinearLayout chipRow = new android.widget.LinearLayout(ctx);
        chipRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 2));
        chipScroll.addView(chipRow);
        final android.widget.ScrollView scroller = new android.widget.ScrollView(ctx);
        final android.widget.LinearLayout content = new android.widget.LinearLayout(ctx);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 8));
        scroller.addView(content);
        root.addView(chipScroll);
        root.addView(scroller, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int[] curShopIdx = {0};
        final int shopCount = perShop.size();
        final TextView[] chips = new TextView[shopCount];
        final Runnable[] render = new Runnable[1];

        int ci = 0;
        for (final long shopId : perShop.keySet()) {
            final int idx = ci;
            String shopName = SHOP_NAMES.containsKey(shopId) ? SHOP_NAMES.get(shopId) : ("商店" + shopId);
            TextView chip = new TextView(ctx);
            chip.setText(shopName + "(" + perShop.get(shopId).size() + ")");
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(ctx, 6);
            chip.setLayoutParams(lp);
            UiStyle.press(chip);
            chip.setOnClickListener(v -> {
                curShopIdx[0] = idx;
                restyleShopChips(chips, perShop, curShopIdx[0]);
                render[0].run();
                scroller.smoothScrollTo(0, 0);
            });
            chipRow.addView(chip);
            chips[ci] = chip;
            ci++;
        }

        render[0] = () -> {
            int k = 0;
            for (long shopId : perShop.keySet()) {
                applyChip(chips[k], k == curShopIdx[0]);
                k++;
            }
            content.removeAllViews();
            long shopId = (long) perShop.keySet().toArray()[curShopIdx[0]];
            java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>> merged = perShop.get(shopId);
            java.util.List<ShopTasks.Good> allGoods = catalog.get(shopId);
            String shopName = SHOP_NAMES.containsKey(shopId) ? SHOP_NAMES.get(shopId) : ("商店" + shopId);

            java.util.LinkedHashMap<Long, String> curs = new java.util.LinkedHashMap<>();
            if (allGoods != null) {
                for (ShopTasks.Good g : allGoods) {
                    if (g.currencyId > 0 && !curs.containsKey(g.currencyId)) {
                        curs.put(g.currencyId, g.currencyName == null ? "" : g.currencyName);
                    }
                }
            }
            StringBuilder curText = new StringBuilder();
            for (java.util.Map.Entry<Long, String> c : curs.entrySet()) {
                Long bal = res.get(c.getKey());
                if (bal == null) bal = bag.get(c.getKey());
                if (curText.length() > 0) curText.append(" / ");
                curText.append(c.getValue().isEmpty() ? ("币" + c.getKey()) : c.getValue())
                        .append(" ").append(bal == null ? "?" : humanize(bal));
            }
            java.util.Map<String, Object> shopLive = live.get(shopId);
            String liveTag = shopLive != null ? "·实时货架" : (online ? "·本店无货架" : "·目录价（未在线）");
            TextView head = new TextView(ctx);
            head.setText(shopName + liveTag + " · " + merged.size() + " 种"
                    + (curText.length() > 0 ? "\n" + curText : ""));
            head.setTextSize(12.5f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(UiStyle.BRAND_TEXT);
            head.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));
            content.addView(head);

            // 当前货架（在线时）：现在就能买的商品与价格/已购
            if (shopLive != null) {
                TextView shelfCap = new TextView(ctx);
                shelfCap.setText("当前货架（可立即购买）");
                shelfCap.setTextSize(11.5f);
                shelfCap.setTypeface(Typeface.DEFAULT_BOLD);
                shelfCap.setTextColor(UiStyle.INK);
                shelfCap.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
                content.addView(shelfCap);
                boolean any = false;
                for (java.util.Map<String, Object> g : ExecUtil.mapsOf(shopLive.get("goods"))) {
                    long confId = ExecUtil.longOf(g.get("conf_id"));
                    ShopTasks.Good meta = byConf.get(confId);
                    long price = livePriceOfUi(g, meta == null ? 0 : meta.price);
                    if (price <= 0) continue;
                    long boughtN = ExecUtil.longOf(g.get("buy_count"));
                    StringBuilder sbl = new StringBuilder("· ");
                    sbl.append(meta == null || meta.name == null || meta.name.isEmpty()
                            ? String.valueOf(confId) : meta.name);
                    if (meta != null && meta.count > 1) sbl.append("×").append(meta.count);
                    sbl.append("  ").append(price)
                            .append(meta == null || meta.currencyName == null ? "" : meta.currencyName);
                    if (meta != null && meta.limit > 0) {
                        sbl.append(" · 限").append(meta.limit);
                        if (boughtN > 0) sbl.append("（已购").append(boughtN).append("）");
                    }
                    TextView row = new TextView(ctx);
                    row.setText(sbl.toString());
                    row.setTextSize(11.5f);
                    row.setTextColor(UiStyle.INK2);
                    row.setPadding(dp(ctx, 8), dp(ctx, 1), 0, dp(ctx, 1));
                    content.addView(row);
                    any = true;
                }
                if (!any) {
                    TextView empty = new TextView(ctx);
                    empty.setText("· 货架数据为空");
                    empty.setTextSize(11.5f);
                    empty.setTextColor(UiStyle.INK2);
                    content.addView(empty);
                }
                TextView wantCap = new TextView(ctx);
                wantCap.setText("自动采购清单（勾选想要的名字，运行时自动刷新+购买）");
                wantCap.setTextSize(11.5f);
                wantCap.setTypeface(Typeface.DEFAULT_BOLD);
                wantCap.setTextColor(UiStyle.INK);
                wantCap.setPadding(0, dp(ctx, 8), 0, dp(ctx, 2));
                content.addView(wantCap);
            }

            for (java.util.Map.Entry<String, java.util.List<ShopTasks.Good>> me : merged.entrySet()) {
                java.util.List<ShopTasks.Good> variants = me.getValue();
                ShopTasks.Good first = variants.get(0);
                long minP = Long.MAX_VALUE, maxP = 0, limit = 0, count = 0;
                for (ShopTasks.Good g : variants) {
                    minP = Math.min(minP, g.price);
                    maxP = Math.max(maxP, g.price);
                    limit = Math.max(limit, g.limit);
                    count = Math.max(count, g.count);
                }
                long buyCount = 0;
                java.util.Map<String, Object> lg = shopLive == null ? null : findLiveGood(shopLive, first.configId);
                if (lg != null) buyCount = ExecUtil.longOf(lg.get("buy_count"));
                final String plainName = plainNameOf(first);
                String priceText = (minP == maxP ? String.valueOf(minP)
                        : minP + "~" + maxP + (maxP > 0 && minP * 2 >= maxP
                                ? "（约" + Math.round(minP * 100.0 / maxP) + "%价）" : ""))
                        + (first.currencyName == null ? "" : first.currencyName);
                StringBuilder sb = new StringBuilder();
                sb.append(plainName);
                if (count > 1) sb.append("×").append(count);
                sb.append("  ").append(priceText);
                if (limit > 0) sb.append(" · 限").append(limit)
                        .append(buyCount > 0 ? "（已购" + buyCount + "）" : "");
                android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
                cb.setText(sb.toString());
                cb.setTextSize(12);
                cb.setChecked(Boolean.TRUE.equals(sel.get(plainName)));
                cb.setOnCheckedChangeListener((b, isChecked) -> sel.put(plainName, isChecked));
                content.addView(cb, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        };
        restyleShopChips(chips, perShop, 0);
        render[0].run();

        TextView tip = new TextView(ctx);
        tip.setText("数据：" + sessionTag + "；勾选顺序=购买顺序（每样买最便宜一档×1，限购自动跳过）");
        tip.setTextSize(10.5f);
        tip.setTextColor(UiStyle.INK2);
        tip.setPadding(dp(ctx, 14), 0, dp(ctx, 14), dp(ctx, 4));
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.addView(tip);
        final android.widget.CheckBox free601 = new android.widget.CheckBox(ctx);
        free601.setText("神秘商店：免费商品全买 + 免费刷新全用");
        free601.setTextSize(10.5f);
        free601.setChecked(FnParams.params(ctx, fpid, sid, fnId).optBoolean("free_601", true));
        final android.widget.CheckBox res801 = new android.widget.CheckBox(ctx);
        res801.setText("游荡商人：资源货币商品全买（不保留）");
        res801.setTextSize(10.5f);
        res801.setChecked(FnParams.params(ctx, fpid, sid, fnId).optBoolean("auto_resource_801", true));
        wrap.addView(free601);
        wrap.addView(res801);
        wrap.addView(root);

        new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle((fnId.equals("shop") ? "商店购买" : "联盟商店") + " · 按店铺勾选")
                .setView(wrap)
                .setPositiveButton("保存", (dlg, w) -> {
                    JSONObject names = new JSONObject();
                    JSONObject next = new JSONObject();
                    // 2026-11-08：有序勾选清单（按用户勾选顺序购买，直到没有货币）
                    JSONArray buyList = new JSONArray();
                    java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>> flat = new java.util.LinkedHashMap<>();
                    for (java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>> m : perShop.values()) {
                        for (java.util.Map.Entry<String, java.util.List<ShopTasks.Good>> e : m.entrySet()) {
                            flat.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                    for (java.util.Map.Entry<String, Boolean> e : sel.entrySet()) {
                        if (!Boolean.TRUE.equals(e.getValue())) continue;
                        String key = e.getKey();
                        try {
                            names.put(key, 1);
                        } catch (Exception ignored) {
                        }
                        java.util.List<ShopTasks.Good> v = flat.get(key);
                        if (v == null) continue;
                        for (ShopTasks.Good g : v) {
                            try {
                                long targetShopId = shopIdOf(g, perShop);
                                if (targetShopId <= 0L) continue;
                                next.put(String.valueOf(g.configId), 1);
                                JSONObject it = new JSONObject();
                                it.put("shop_id", targetShopId);
                                it.put("conf_id", g.configId);
                                it.put("count", 1);
                                buyList.put(it);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    FnParams.putTopLevel(ctx, fpid, sid, fnId, "buys", next);
                    FnParams.putTopLevel(ctx, fpid, sid, fnId, "buy_names", names);
                    FnParams.putTopLevel(ctx, fpid, sid, fnId, "buy_list", buyList);
                    FnParams.putTopLevel(ctx, fpid, sid, fnId, "free_601", free601.isChecked());
                    FnParams.putTopLevel(ctx, fpid, sid, fnId, "auto_resource_801", res801.isChecked());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String plainNameOf(ShopTasks.Good g) {
        return g.name == null || g.name.isEmpty() ? String.valueOf(g.configId) : g.name;
    }

    /** 2026-11-08：按 configId 反查所属店铺（写 buy_list 用）。 */
    private static long shopIdOf(ShopTasks.Good g,
                                 java.util.LinkedHashMap<Long, java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>>> perShop) {
        for (java.util.Map.Entry<Long, java.util.LinkedHashMap<String, java.util.List<ShopTasks.Good>>> e : perShop.entrySet()) {
            for (java.util.List<ShopTasks.Good> v : e.getValue().values()) {
                for (ShopTasks.Good x : v) {
                    if (x.configId == g.configId) return e.getKey();
                }
            }
        }
        return 0L;
    }

    private static void restyleShopChips(TextView[] chips,
                                         java.util.LinkedHashMap<Long, ?> perShop, int selected) {
        int k = 0;
        for (Long ignored : perShop.keySet()) {
            applyChip(chips[k], k == selected);
            k++;
        }
    }

    /** 该名字的任一 variant 已在勾选清单里。 */
    private static boolean checkName(JSONObject curBuys, java.util.List<ShopTasks.Good> variants) {
        if (curBuys == null) return false;
        for (ShopTasks.Good g : variants) {
            if (curBuys.has(String.valueOf(g.configId))) return true;
        }
        return false;
    }

    /** 在实时货架 map_info[shopId].goods 里按 conf_id 找条目。 */
    private static java.util.Map<String, Object> findLiveGood(java.util.Map<String, Object> shopLive,
                                                              long confId) {
        for (java.util.Map<String, Object> g : ExecUtil.mapsOf(shopLive.get("goods"))) {
            if (ExecUtil.longOf(g.get("conf_id")) == confId) return g;
        }
        return null;
    }

    private static String humanize(long v) {
        if (v >= 100_000_000L) return String.format(java.util.Locale.CHINA, "%.1f亿", v / 1e8);
        if (v >= 10_000L) return String.format(java.util.Locale.CHINA, "%.1f万", v / 1e4);
        return String.valueOf(v);
    }

    /** 实时货架价防御式提取（与 ShopTasks.livePriceOf 同款字段候选）。 */
    private static long livePriceOfUi(java.util.Map<String, Object> g, long fallback) {
        for (String k : new String[]{"price", "cur_price", "now_price", "discount_price"}) {
            long v = ExecUtil.longOf(g.get(k));
            if (v > 0) return v;
        }
        return fallback;
    }

    /** 烈焰与獠牙兑换档位勾选：按表分组，存 buys {index:1}。 */
    private static View flamefangsPickRow(final Context ctx, final String fpid, final long sid) {
        java.util.List<ShopTasks.LeakGood> goods = ShopTasks.leakGoods(ctx);
        final java.util.List<String> labels = new ArrayList<>();
        final java.util.List<String> vals = new ArrayList<>();
        for (ShopTasks.LeakGood g : goods) {
            labels.add("表" + g.tableId + "·档" + g.index
                    + "（" + g.price + "×" + g.itemCount + "件" + (g.limit > 0 ? "·限" + g.limit : "") + "）");
            vals.add(String.valueOf(g.index));
        }
        JSONObject buys = FnParams.params(ctx, fpid, sid, "act_flamefangs").optJSONObject("buys");
        int n = buys == null ? 0 : buys.length();
        return pickRow(ctx, "勾选兑换档位", n == 0 ? "未勾选（目录 " + labels.size() + " 档）" : "已勾选 " + n + " 档", () -> {
            JSONObject curBuys = FnParams.params(ctx, fpid, sid, "act_flamefangs").optJSONObject("buys");
            final boolean[] state = new boolean[vals.size()];
            for (int i = 0; i < vals.size(); i++) {
                state[i] = curBuys != null && curBuys.has(vals.get(i));
            }
            new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("烈焰与獠牙 · 勾选兑换期要抢的档位")
                    .setMultiChoiceItems(labels.toArray(new String[0]), state,
                            (dlg, w, isChecked) -> state[w] = isChecked)
                    .setPositiveButton("保存", (dlg, w) -> {
                        JSONObject next = new JSONObject();
                        for (int i = 0; i < state.length; i++) {
                            if (state[i]) {
                                try {
                                    next.put(vals.get(i), 1);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        FnParams.putTopLevel(ctx, fpid, sid, "act_flamefangs", "buys", next);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    /**
     * 雪原贸易站兑换档位勾选：服务器不推商品目录，清单 = 内置已知档 ∪ 最近
     * exchange_data 快照出现过的档（snowTrade 缓存于 seen_idx），存 buys {idx:1}。
     */
    private static View snowtradePickRow(final Context ctx, final String fpid, final long sid) {
        java.util.TreeSet<Long> idxSet = new java.util.TreeSet<>();
        idxSet.add(132L);   // 免费档（free 子项默认档）
        idxSet.add(155L);   // 协议快照实证档（活动 2702001）
        JSONObject seen = FnParams.params(ctx, fpid, sid, "act_snowtrade").optJSONObject("seen_idx");
        if (seen != null) {
            java.util.Iterator<String> sit = seen.keys();
            while (sit.hasNext()) {
                try {
                    idxSet.add(Long.parseLong(sit.next().replace(".0", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        final java.util.List<String> labels = new ArrayList<>();
        final java.util.List<String> vals = new ArrayList<>();
        for (long idx : idxSet) {
            labels.add("档" + idx + (idx == 132L ? "（默认）" : ""));
            vals.add(String.valueOf(idx));
        }
        // 免费档单选行：换期保护会把 free_idx 重置为 0（待配置），在此重选
        final long curFree = FnParams.params(ctx, fpid, sid, "act_snowtrade").optLong("free_idx", 132);
        View freeRow = pickRow(ctx, "免费兑换档",
                curFree <= 0 ? "待配置（换期已重置，必选）" : "档" + curFree, () -> {
            new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("雪原贸易站 · 选择免费兑换档")
                    .setSingleChoiceItems(labels.toArray(new String[0]),
                            Math.max(0, vals.indexOf(String.valueOf(curFree))),
                            (dlg, w) -> FnParams.putTopLevel(ctx, fpid, sid,
                                    "act_snowtrade", "free_idx", Long.parseLong(vals.get(w))))
                    .setPositiveButton("保存", null)
                    .setNegativeButton("取消", null)
                    .show();
        });
        View buyRow = snowtradeBuysPickRow(ctx, fpid, sid, labels, vals);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(freeRow);
        wrap.addView(buyRow);
        return wrap;
    }

    private static View snowtradeBuysPickRow(final Context ctx, final String fpid, final long sid,
                                             final java.util.List<String> labels,
                                             final java.util.List<String> vals) {
        JSONObject buys = FnParams.params(ctx, fpid, sid, "act_snowtrade").optJSONObject("buys");
        int n = buys == null ? 0 : buys.length();
        return pickRow(ctx, "勾选兑换档位", n == 0 ? "未勾选（目录 " + labels.size() + " 档）" : "已勾选 " + n + " 档", () -> {
            JSONObject curBuys = FnParams.params(ctx, fpid, sid, "act_snowtrade").optJSONObject("buys");
            final boolean[] state = new boolean[vals.size()];
            for (int i = 0; i < vals.size(); i++) {
                state[i] = curBuys != null && curBuys.has(vals.get(i));
            }
            new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("雪原贸易站 · 勾选要兑换的档位（存为数量1，多兑改 buys 参数）")
                    .setMultiChoiceItems(labels.toArray(new String[0]), state,
                            (dlg, w, isChecked) -> state[w] = isChecked)
                    .setPositiveButton("保存", (dlg, w) -> {
                        JSONObject next = new JSONObject();
                        for (int i = 0; i < state.length; i++) {
                            if (state[i]) {
                                try {
                                    next.put(vals.get(i), 1);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        FnParams.putTopLevel(ctx, fpid, sid, "act_snowtrade", "buys", next);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private SubCardUi() {
    }
}
