package com.carlos.vault;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一样式工具 */
public class UiStyle {

    public static final int BLUE      = 0xFF2962FF;
    public static final int BLUE_DARK = 0xFF1A237E;
    public static final int TEAL      = 0xFF00695C;
    public static final int RED       = 0xFFD32F2F;
    public static final int BG        = 0xFFF5F7FB;
    public static final int CARD      = 0xFFFFFFFF;
    public static final int TEXT_PRI  = 0xFF1A1A2E;
    public static final int TEXT_SEC  = 0xFF6B7280;
    public static final int DIVIDER   = 0xFFE5E7EB;

    private static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    public static GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusDp);
        d.setColor(color);
        return d;
    }

    /** 分组卡片 */
    public static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(CARD, 14));
        c.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), 0);
        c.setLayoutParams(lp);
        return c;
    }

    /** 卡片内行按钮（图标 + 文字） */
    public static LinearLayout makeRowBtn(Context ctx, String icon, String text, int textColor, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 14));
        row.setBackground(roundRect(0xFFFAFBFD, 10));

        TextView ic = new TextView(ctx);
        ic.setText(icon);
        ic.setTextSize(17);
        row.addView(ic, new LinearLayout.LayoutParams(dp(ctx, 36), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(textColor);
        tv.setPadding(dp(ctx, 8), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** 输入弹窗（圆角卡片式） */
    public static void showInputDialog(Context ctx, String title, String hint, String prefill,
                                       boolean multiline, OnInputResult callback) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(CARD, 16));
        box.setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 8));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_PRI);
        box.addView(tv);

        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextSize(14);
        et.setTextColor(TEXT_PRI);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setShape(GradientDrawable.RECTANGLE);
        etBg.setCornerRadius(10);
        etBg.setStroke(1, DIVIDER);
        etBg.setColor(0xFFF8F9FC);
        et.setBackground(etBg);
        et.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        if (multiline) { et.setMinLines(5); et.setGravity(Gravity.TOP); }
        if (prefill != null && !prefill.isEmpty()) et.setText(prefill);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(ctx, 14);
        box.addView(et, etLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(ctx, 16), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(TEXT_SEC);
        cancel.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        btnRow.addView(cancel);

        TextView ok = new TextView(ctx);
        ok.setText("确定");
        ok.setTextSize(14);
        ok.setTextColor(BLUE);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        btnRow.addView(ok);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        cancel.setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> { callback.onResult(et.getText().toString()); dlg.dismiss(); });
        dlg.show();
    }

    /** 确认弹窗 */
    public static void showConfirmDialog(Context ctx, String title, String message,
                                         String posText, int posColor, Runnable onConfirm) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(CARD, 16));
        box.setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 8));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_PRI);
        box.addView(tv);

        TextView msg = new TextView(ctx);
        msg.setText(message);
        msg.setTextSize(14);
        msg.setTextColor(TEXT_SEC);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(ctx, 10);
        box.addView(msg, msgLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(ctx, 16), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(TEXT_SEC);
        cancel.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        btnRow.addView(cancel);

        TextView ok = new TextView(ctx);
        ok.setText(posText);
        ok.setTextSize(14);
        ok.setTextColor(posColor);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        btnRow.addView(ok);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        cancel.setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> { onConfirm.run(); dlg.dismiss(); });
        dlg.show();
    }

    public interface OnInputResult { void onResult(String value); }
}
