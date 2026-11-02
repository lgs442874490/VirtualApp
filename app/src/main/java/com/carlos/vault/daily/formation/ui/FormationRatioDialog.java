package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.carlos.vault.UiStyle;

/**
 * 自动编队比例分配弹窗（T5，1:1 网页版 §1.7「比例分配」，源码
 * `DailyFormationRatioDialog.pretty.js:696-900`）。
 *
 * <p>结构：头部 `比例分配` → 说明 `设置自动编队时盾、矛、弓的出兵比例，合计必须为 100%。`
 * → 3 行（兵种名 + 滑杆 0-100/步进 5 + 数字输入 + `%`）→ 快捷比例 `523/514/505/640/433`
 * → 合计行 `当前合计 X%`（≠100 时红字 `请调整为 100% 后再应用`）
 * →（可选）`开启出征界面默认使用当前比例分配` 复选框 → `取消` / `应用比例`。
 *
 * <p>纯 Android 代码构建（无 XML），颜色/圆角沿用 {@link UiStyle}；步进与上限与网页版一致
 * （滑杆 step=5，输入框 step=5，0..100）。打开时用传入比例初始化；应用回调整数且合计=100。
 */
public final class FormationRatioDialog {

    /** 应用回调（主线程；保证三项均为 5 的倍数且合计 100）。 */
    public interface OnApplied {
        void onApplied(int infantry, int lancer, int marksman);
    }

    /**
     * 应用回调（带「开启出征界面默认使用当前比例分配」勾选状态）。
     *
     * <p>勾选时调用方应把该比例写入全局自动编队设置（契约 §4.3）。
     */
    public interface OnAppliedWithDefault {
        void onApplied(int infantry, int lancer, int marksman, boolean useAsDefault);
    }

    /** 快捷比例（码 → 三项）：523=50/20/30、514=50/10/40、505=50/0/50、640=60/40/0、433=40/30/30。 */
    private static final String[] PRESET_CODES = {"523", "514", "505", "640", "433"};
    private static final int[][] PRESET_VALUES = {
            {50, 20, 30}, {50, 10, 40}, {50, 0, 50}, {60, 40, 0}, {40, 30, 30}};
    private static final String[] PROF_LABELS = {"盾兵", "矛兵", "弓兵"};

    private FormationRatioDialog() {
    }

    /**
     * @param current   当前比例 {盾,矛,弓}（非法/合计≠100 按 50/20/30 兜底）
     * @param available 自动编队是否可用（false 时主按钮禁用并提示）
     * @param cb        应用回调（可空）
     */
    public static void show(final Activity act, int[] current, final boolean available,
                            final OnApplied cb) {
        // 旧签名保持：不显示「默认使用当前比例分配」开关（FormationEntryCard 等调用方零改动）。
        show(act, current, available, false, false,
                cb == null ? null : (inf, lancer, marksman, useAsDefault) ->
                        cb.onApplied(inf, lancer, marksman));
    }

    /**
     * 带「开启出征界面默认使用当前比例分配」复选框的重载（契约 §4.3）。
     *
     * @param showDefaultSwitch 是否显示该复选框（false 时行为与旧签名一致）
     * @param defaultOn         复选框初始勾选状态
     * @param cb                应用回调（第三参 = 复选框勾选状态）
     */
    public static void show(final Activity act, int[] current, final boolean available,
                            final boolean showDefaultSwitch, final boolean defaultOn,
                            final OnAppliedWithDefault cb) {
        if (act == null) return;
        final int[] init = normalize(current);
        final int[] value = {init[0], init[1], init[2]};
        final boolean[] useDefault = {showDefaultSwitch && defaultOn};

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 6));

        TextView title = new TextView(act);
        title.setText("比例分配");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        box.addView(title);

        TextView desc = new TextView(act);
        desc.setText("设置自动编队时盾、矛、弓的出兵比例，合计必须为 100%。");
        desc.setTextSize(11.5f);
        desc.setTextColor(UiStyle.INK2);
        desc.setPadding(0, dp(act, 4), 0, dp(act, 8));
        box.addView(desc);

        final SeekBar[] bars = new SeekBar[3];
        final EditText[] inputs = new EditText[3];
        final TextView[] pcts = new TextView[3];
        final TextView totalText = new TextView(act);
        final TextView totalHint = new TextView(act);
        final TextView applyBtn = new TextView(act);
        final TextView[] presetChips = new TextView[PRESET_CODES.length];
        final Runnable[] sync = new Runnable[1];
        final Runnable doSync = () -> {
            if (sync[0] != null) sync[0].run();
        };

        // ---------- 3 行：兵种 + 滑杆 + 数字 + % ----------
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(act, 5), 0, dp(act, 5));

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(act);
            label.setText(PROF_LABELS[i]);
            label.setTextSize(13);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(UiStyle.INK);
            head.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pcts[i] = new TextView(act);
            pcts[i].setText(value[i] + "%");
            pcts[i].setTextSize(13);
            pcts[i].setTypeface(Typeface.DEFAULT_BOLD);
            pcts[i].setTextColor(UiStyle.BRAND_TEXT);
            head.addView(pcts[i]);
            row.addView(head);

            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            SeekBar bar = new SeekBar(act);
            bar.setMax(100);
            bar.setProgress(value[i]);
            bars[i] = bar;
            line.addView(bar, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            EditText et = new EditText(act);
            et.setText(String.valueOf(value[i]));
            et.setTextSize(13);
            et.setGravity(Gravity.CENTER);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setBackground(UiStyle.roundRectStroke(act, UiStyle.WHITE, 10, UiStyle.LINE_STRONG));
            et.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));
            inputs[i] = et;
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(act, 58),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.leftMargin = dp(act, 8);
            line.addView(et, elp);
            TextView pct = new TextView(act);
            pct.setText("%");
            pct.setTextSize(12);
            pct.setTextColor(UiStyle.INK2);
            pct.setPadding(dp(act, 4), 0, 0, 0);
            line.addView(pct);
            row.addView(line);
            box.addView(row);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int stepped = step(progress);
                    if (stepped != progress) seekBar.setProgress(stepped);
                    value[idx] = stepped;
                    doSync.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                int parsed = clamp100(step(parse(et.getText().toString(), value[idx])));
                value[idx] = parsed;
                doSync.run();
            });
        }

        // ---------- 快捷比例 ----------
        LinearLayout presets = new LinearLayout(act);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        presets.setPadding(0, dp(act, 4), 0, dp(act, 6));
        TextView plabel = new TextView(act);
        plabel.setText("快捷比例");
        plabel.setTextSize(11.5f);
        plabel.setTextColor(UiStyle.INK2);
        LinearLayout.LayoutParams pllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pllp.rightMargin = dp(act, 6);
        presets.addView(plabel, pllp);
        for (int i = 0; i < PRESET_CODES.length; i++) {
            final int[] pv = PRESET_VALUES[i];
            TextView chip = new TextView(act);
            chip.setText(PRESET_CODES[i]);
            chip.setTextSize(11.5f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(UiStyle.INK2);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 8, UiStyle.LINE));
            chip.setPadding(dp(act, 8), dp(act, 4), dp(act, 8), dp(act, 4));
            chip.setOnClickListener(v -> {
                value[0] = pv[0];
                value[1] = pv[1];
                value[2] = pv[2];
                doSync.run();
            });
            presetChips[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(act, 6);
            presets.addView(chip, clp);
        }
        box.addView(presets);

        // ---------- 合计行 ----------
        totalText.setTextSize(13);
        totalText.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(totalText);
        totalHint.setTextSize(10.5f);
        totalHint.setTextColor(UiStyle.ERR_INK);
        totalHint.setPadding(0, dp(act, 2), 0, 0);
        box.addView(totalHint);

        // ---------- 开启出征界面默认使用当前比例分配 ----------
        if (showDefaultSwitch) {
            CheckBox defaultSwitch = new CheckBox(act);
            defaultSwitch.setText("开启出征界面默认使用当前比例分配");
            defaultSwitch.setTextSize(12);
            defaultSwitch.setTextColor(UiStyle.INK2);
            defaultSwitch.setChecked(useDefault[0]);
            defaultSwitch.setPadding(0, dp(act, 8), 0, dp(act, 2));
            defaultSwitch.setOnCheckedChangeListener((v, checked) -> useDefault[0] = checked);
            box.addView(defaultSwitch);
        }

        // ---------- 底部按钮 ----------
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(act, 10), 0, 0);
        final AlertDialog[] dlgRef = new AlertDialog[1];

        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextSize(12.5f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(UiStyle.INK2);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(act, 12), dp(act, 11), dp(act, 12), dp(act, 11));
        cancel.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 12, UiStyle.LINE_STRONG));
        cancel.setOnClickListener(v -> {
            if (dlgRef[0] != null) dlgRef[0].dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        applyBtn.setText("应用比例");
        applyBtn.setTextSize(13);
        applyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        applyBtn.setTextColor(UiStyle.WHITE);
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setPadding(dp(act, 14), dp(act, 11), dp(act, 14), dp(act, 11));
        applyBtn.setOnClickListener(v -> {
            if (!available) {
                UiStyle.toast(act, "自动编队当前不可用（容量未同步）");
                return;
            }
            if (value[0] + value[1] + value[2] != 100) {
                UiStyle.toast(act, "请调整为 100% 后再应用");
                return;
            }
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            if (cb != null) cb.onApplied(value[0], value[1], value[2], useDefault[0]);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        alp.leftMargin = dp(act, 10);
        actions.addView(applyBtn, alp);
        box.addView(actions);

        // ---------- 统一刷新闭包 ----------
        sync[0] = () -> {
            for (int i = 0; i < 3; i++) {
                if (pcts[i] != null) pcts[i].setText(value[i] + "%");
                if (bars[i] != null && bars[i].getProgress() != value[i]) {
                    bars[i].setProgress(value[i]);
                }
                String txt = String.valueOf(value[i]);
                if (inputs[i] != null && !txt.contentEquals(inputs[i].getText())) {
                    inputs[i].setText(txt);
                }
            }
            int sum = value[0] + value[1] + value[2];
            totalText.setText("当前合计 " + sum + "%");
            totalText.setTextColor(sum == 100 ? UiStyle.OK_INK : UiStyle.ERR_INK);
            boolean ok = sum == 100 && available;
            if (sum != 100) {
                totalHint.setText("请调整为 100% 后再应用");
                totalHint.setVisibility(View.VISIBLE);
            } else if (!available) {
                totalHint.setText("自动编队当前不可用（容量未同步）");
                totalHint.setVisibility(View.VISIBLE);
            } else {
                totalHint.setVisibility(View.GONE);
            }
            applyBtn.setBackground(UiStyle.roundRectDp(act,
                    ok ? UiStyle.BRAND : UiStyle.LINE_STRONG, 12));
            applyBtn.setAlpha(ok ? 1f : 0.75f);
            for (int i = 0; i < presetChips.length; i++) {
                if (presetChips[i] == null) continue;
                int[] pv = PRESET_VALUES[i];
                boolean on = value[0] == pv[0] && value[1] == pv[1] && value[2] == pv[2];
                presetChips[i].setTextColor(on ? UiStyle.BRAND_TEXT : UiStyle.INK2);
                presetChips[i].setBackground(UiStyle.roundRectStroke(act,
                        on ? UiStyle.BRAND_50 : UiStyle.CARD, 8,
                        on ? UiStyle.BRAND : UiStyle.LINE));
            }
        };
        sync[0].run();

        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    // ==================== 纯函数（JVM 侧可核对） ====================

    /** 归一：负数归 0、>100 归 100、非 5 倍数向下取 5；合计≠100 时回默认 50/20/30。 */
    public static int[] normalize(int[] current) {
        if (current == null || current.length < 3) return new int[]{50, 20, 30};
        int a = clamp100(step(current[0]));
        int b = clamp100(step(current[1]));
        int c = clamp100(step(current[2]));
        if (a + b + c != 100) return new int[]{50, 20, 30};
        return new int[]{a, b, c};
    }

    /** 比例码：50/20/30 → `523`；非 10 倍数时返回 `a:b:c`。 */
    public static String ratioCode(int inf, int lanc, int mark) {
        if (inf % 10 == 0 && lanc % 10 == 0 && mark % 10 == 0) {
            return "" + (inf / 10) + (lanc / 10) + (mark / 10);
        }
        return inf + ":" + lanc + ":" + mark;
    }

    /** 快捷码 → 三项；未知码返回 null。 */
    public static int[] preset(String code) {
        if (code == null) return null;
        for (int i = 0; i < PRESET_CODES.length; i++) {
            if (PRESET_CODES[i].equals(code)) return PRESET_VALUES[i].clone();
        }
        return null;
    }

    private static int step(int v) {
        return (clamp100(v) / 5) * 5;
    }

    private static int clamp100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Activity act, int v) {
        return UiStyle.dp(act, v);
    }
}
