package com.carlos.vault.daily;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;

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
        ref[0] = new UiStyle.Switch(ctx,
                FnParams.subOn(ctx, fpid, sid, fnId, sub.id, sub.defOn),
                () -> FnParams.saveSubOn(ctx, fpid, sid, fnId, sub.id, ref[0].isOn()));
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
            st.setTextColor(stale ? 0xFFB45309 : UiStyle.BRAND_TEXT);   // WARN_INK
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
        } else if (sub.strategies != null && sub.strategies.length > 0) {
            // 策略 chips（>5 个换行）
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(ctx, 6), 0, 0);
            String cur = FnParams.strategy(ctx, fpid, sid, fnId, sub.id, sub.strategies[0][0]);
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
        v.setTextColor(selected ? 0xFFFFFFFF : UiStyle.INK2);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setBackground(UiStyle.roundRect(selected ? UiStyle.BRAND : UiStyle.SURFACE_3, dp(v.getContext(), 10)));
    }

    private static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private SubCardUi() {
    }
}
