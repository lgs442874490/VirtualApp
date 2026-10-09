package com.carlos.vault;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
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

    // ===== 设计方案 v1 tokens（浅色）=====
    public static final int BRAND        = 0xFF2E5BFF;   // brand-500 主按钮底/强调
    public static final int BRAND_PRESS  = 0xFF1E43D9;   // brand-600
    public static final int BRAND_DEEP   = 0xFF1733A8;   // brand-700 渐变终点
    public static final int BRAND_TEXT   = 0xFF1E43D9;   // 页面底上的蓝字
    public static final int BRAND_50     = 0xFFEEF3FF;   // surface tint
    public static final int ICE          = 0xFF0EA5A5;   // ice-500 仅"进行中"
    public static final int BG_V2        = 0xFFF2F5FA;   // 画布
    public static final int INK          = 0xFF0F1729;   // 主文本
    public static final int INK2         = 0xFF61708B;   // 次文本
    public static final int LINE         = 0xFFE5E9F2;   // 描边/分割线
    // 状态徽章 bg/ink 双层
    public static final int OK_BG = 0xFFE7F8F1,  OK_INK = 0xFF047857,  OK_SOLID = 0xFF10B981;
    public static final int INK3 = 0xFF9AA6BC;                            // 三级文本/表头（2.6:1，仅限非正文）
    public static final int BRAND_100 = 0xFFDCE5FF;                       // 浅底悬浮态
    public static final int BRAND_200 = 0xFFBFCCFF;                       // 描边（按钮/输入）
    public static final int SURFACE_2 = 0xFFF8FAFD;                       // 次级面（行底/降饱和）
    public static final int SURFACE_3 = 0xFFEEF2F9;                       // 三级面（控件容器底）
    public static final int LINE_STRONG = 0xFFD2D9E6;                    // 强描边（输入框/未选中勾选框）
    public static final int SWITCH_OFF  = 0xFFC9D2E0;                    // 开关关闭态底色
    public static final int BRAND_300   = 0xFFC7D4FF;                    // 弱描边（白底上的强调框）
    public static final int WAIT_50     = 0xFFE8EEFF;                    // wait 浅底（进行中底）
    public static final int WECHAT_GREEN= 0xFF07C160;                    // 微信品牌绿（仅微信支付按钮）
    public static final int ERR_BORDER  = 0xFFF3B4B4;                    // 危险操作区浅红描边
    public static final int INVERSE     = 0xFFF5F7FB;                    // 深底上的浅字（轻提示/批量条）
    public static final int RUN_BG = 0xFFE6F6F6,  RUN_INK = 0xFF0F766E, RUN_SOLID = 0xFF0EA5A5;
    public static final int WAIT_BG = 0xFFEEF0FE, WAIT_INK = 0xFF4338CA, WAIT_SOLID = 0xFF6366F1;
    public static final int WARN_BG = 0xFFFFF4E5, WARN_INK = 0xFFB45309, WARN_SOLID = 0xFFF59E0B;
    public static final int ERR_BG = 0xFFFEECEC,  ERR_INK = 0xFFB91C1C, ERR_SOLID = 0xFFEF4444;
    public static final int IDLE_BG = 0xFFF1F3F7, IDLE_INK = 0xFF6B7280;

    // ==================== v1 兼容别名（已重定向到 v2 token 语义）====================
    // 旧页面（AccountVaultActivity / FunctionActivity / EntryDetailActivity / LicenseUi）仍在
    // 引用这些名字。规范要求「全项目一套 token」，所以这里不再是独立色值，而是逐个映射到
    // v2 语义等价物 —— 改色只发生在这一处，4 个旧页面 136 处引用全部随之对齐。
    // 迁移完成后可整段删除（先删引用，再删别名）。
    // 映射关系（v1 → v2）：
    //   BLUE 2962FF → BRAND 2E5BFF          （主色微调至白字 5.1:1）
    //   BLUE_DARK 1A237E → BRAND_TEXT 1E43D9（页面底上的蓝字；不再当深底渐变用）
    //   TEAL 00695C → RUN_INK 0F766E        （语义收窄为"进行中"）
    //   RED D32F2F → ERR_INK B91C1C         （原色对白底仅 4.2:1，不达标）
    //   BG F5F7FB → BG_V2 F2F5FA            （画布压深，卡片改描边分层）
    //   TEXT_PRI 1A1A2E → INK 0F1729        （16.4:1）
    //   TEXT_SEC 6B7280 → INK2 61708B       （4.8:1）
    //   DIVIDER E5E7EB → LINE E5E9F2        （偏冷配合冰蓝）
    /** @deprecated 用 {@link #BRAND} */
    public static final int BLUE      = BRAND;
    /** @deprecated 用 {@link #BRAND_TEXT} */
    public static final int BLUE_DARK = BRAND_TEXT;
    /** @deprecated 用 {@link #RUN_INK} */
    public static final int TEAL      = RUN_INK;
    /** @deprecated 用 {@link #RED_INK} */
    public static final int RED       = ERR_INK;
    /** @deprecated 用 {@link #BG_V2} */
    public static final int BG        = BG_V2;
    /** @deprecated 用 {@link #CARD} */
    public static final int CARD      = 0xFFFFFFFF;
    /** @deprecated 用 {@link #INK} */
    public static final int TEXT_PRI  = INK;
    /** @deprecated 用 {@link #INK2} */
    public static final int TEXT_SEC  = INK2;
    /** @deprecated 用 {@link #LINE} */
    public static final int DIVIDER   = LINE;

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
        row.setBackground(roundRect(SURFACE_2, 10));

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
        etBg.setColor(SURFACE_2);
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

    /** 状态徽章（22dp 高、全圆角、bg+ink 双层，文字≤8字）。type: ok/run/wait/warn/err/idle */
    public static android.widget.TextView badge(android.content.Context ctx, String type, String text) {
        int bg, ink;
        switch (type) {
            case "ok":   bg = OK_BG;   ink = OK_INK;   break;
            case "run":  bg = RUN_BG;  ink = RUN_INK;  break;
            case "wait": bg = WAIT_BG; ink = WAIT_INK; break;
            case "warn": bg = WARN_BG; ink = WARN_INK; break;
            case "err":  bg = ERR_BG;  ink = ERR_INK;  break;
            default:     bg = IDLE_BG; ink = IDLE_INK; break;
        }
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(10.5f);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(ink);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        v.setBackground(roundRect(bg, 999));   // 全圆胶囊（spec：徽章 22dp 高）
        return v;
    }

    /** 描边卡片（无阴影分层：白底 + 1px LINE 描边 + r-3 16dp）。 */
    public static LinearLayout outlineCard(android.content.Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(ctx, 16));
        g.setStroke(dp(ctx, 1), LINE);
        c.setBackground(g);
        return c;
    }

    /** 横向 chip（选中=BRAND 底白字；未选=BRAND_50 底 BRAND_TEXT 字）。 */
    public static android.widget.TextView chip(android.content.Context ctx, String text, boolean selected) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        v.setTextColor(selected ? 0xFFFFFFFF : BRAND_TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        v.setBackground(roundRect(selected ? BRAND : BRAND_50, 14));
        return v;
    }

    /** 灰chip（计数筛选器用）。 */
    public static android.widget.TextView chipGray(android.content.Context ctx, String text, boolean selected) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        v.setTextColor(selected ? 0xFFFFFFFF : INK2);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        v.setBackground(roundRect(selected ? BRAND : 0xFFFFFFFF, 14));
        return v;
    }

    /** 分组标题（P5 去卡片化：细分割线 + 标题）。 */
    public static android.widget.TextView sectionTitle(android.content.Context ctx, String text) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(INK);
        v.setPadding(dp(ctx, 2), dp(ctx, 18), dp(ctx, 2), dp(ctx, 8));
        return v;
    }

    /** 主按钮（48dp，全局唯一：一键跑全部）。 */
    public static android.widget.TextView primaryBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(15);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFFFFFFF);
        v.setGravity(android.view.Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BRAND);
        g.setCornerRadius(dp(ctx, 14));
        v.setBackground(g);
        v.setMinHeight(dp(ctx, 48));
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 次级按钮（40dp 描边）。 */
    public static android.widget.TextView secondaryBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(BRAND_TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(ctx, 12));
        g.setStroke(dp(ctx, 1), BRAND_300);
        v.setBackground(g);
        v.setMinHeight(dp(ctx, 40));
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    // ===== 规范组件（设计方案 v1 · 组件库） =====

    /** iOS 风格开关（视觉46×28，brand 色，白滑块，热区≥44dp）。 */
    public static class Switch extends android.view.View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable cb;

        public Switch(android.content.Context ctx, boolean initial, Runnable onChanged) {
            super(ctx);
            on = initial;
            cb = onChanged;
            setContentDescription(on ? "开" : "关");
            setOnClickListener(v -> {
                on = !on;
                setContentDescription(on ? "开" : "关");
                invalidate();
                if (cb != null) cb.run();
            });
        }

        public boolean isOn() {
            return on;
        }

        public void setOn(boolean b) {
            on = b;
            setContentDescription(on ? "开" : "关");
            invalidate();
        }

        /**
         * 触摸目标 ≥44dp（规范 a11y）：测量尺寸给 46×44（宽不变），视觉轨道只画中段 28dp 高。
         * 上下各 8dp 是透明垫肩 —— 只扩热区、不改布局观感。
         */
        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(getContext(), 46), dp(getContext(), 44));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float trackH = dp(getContext(), 28);
            float top = (getHeight() - trackH) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? BRAND : SWITCH_OFF);
            c.drawRoundRect(0, top, getWidth(), top + trackH, trackH / 2, trackH / 2, p);
            float d = trackH - 6;
            p.setColor(0xFFFFFFFF);
            float cy = top + trackH / 2;
            c.drawCircle(on ? getWidth() - d / 2 - 3 : d / 2 + 3, cy, d / 2, p);
        }
    }

    // ==================== 单色矢量图标（替代 ⚙ ✕ 等文本字符）====================
    // 文本字符随机型字体异形（Roboto/MIUI/EMUI 各画各的），且无法控笔宽与对齐。
    // 这里用 Path 手绘 24dp 网格图标，stroke 颜色由调用方给（通常 INK2 / BRAND_TEXT）。
    public static class Icon extends android.view.View {
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int color;

        public static final int GEAR = 0;      // 齿轮（角色管理）
        public static final int CLOSE = 1;     // 关闭 ✕
        public static final int CHEVRON = 2;   // 右箭头 ›

        public Icon(android.content.Context ctx, int which, int colorDp) {
            super(ctx);
            color = colorDp;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            switch (which) {
                case GEAR:
                    // 外圈 8 齿 + 中心圆
                    path.addCircle(12, 12, 3.2f, android.graphics.Path.Direction.CW);
                    float r1 = 8.6f, r2 = 10.6f;
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * 2 * k / 8;
                        path.moveTo((float) (12 + r1 * Math.cos(a)), (float) (12 + r1 * Math.sin(a)));
                        path.lineTo((float) (12 + r2 * Math.cos(a)), (float) (12 + r2 * Math.sin(a)));
                    }
                    break;
                case CLOSE:
                    path.moveTo(7, 7); path.lineTo(17, 17);
                    path.moveTo(17, 7); path.lineTo(7, 17);
                    break;
                default: // CHEVRON
                    path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18);
                    break;
            }
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(getContext(), 18), dp(getContext(), 18));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            p.setStrokeWidth(dp(getContext(), 2));
            p.setColor(color);
            float scale = getWidth() / 24f;
            c.save();
            c.scale(scale, scale);
            c.drawPath(path, p);
            c.restore();
        }
    }

    /** 分段控件（如 矩阵|列表）：一体圆角容器 + 选中段 brand 底白字。 */
    public static android.widget.LinearLayout segmented(android.content.Context ctx,
                                                        String[] options, int selected,
                                                        final java.util.function.IntConsumer onSelect) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setBackground(roundRect(SURFACE_3, dp(ctx, 14)));
        wrap.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            android.widget.TextView seg = new android.widget.TextView(ctx);
            seg.setText(options[i]);
            seg.setTextSize(12);
            seg.setTypeface(i == selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            seg.setTextColor(i == selected ? 0xFFFFFFFF : INK2);
            seg.setGravity(android.view.Gravity.CENTER);
            seg.setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6));
            if (i == selected) seg.setBackground(roundRect(BRAND, dp(ctx, 11)));
            seg.setOnClickListener(v -> {
                if (onSelect != null) onSelect.accept(idx);
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            wrap.addView(seg, lp);
        }
        return wrap;
    }

    /** 下拉选择行："标签  当前值 ▾"（label 灰 + 值 brand 粗）。 */
    public static android.widget.TextView dropdownRow(android.content.Context ctx,
                                                      String label, String value, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setTextSize(12);
        android.text.SpannableString ss = new android.text.SpannableString(label + "  " + value + " ▾");
        ss.setSpan(new android.text.style.ForegroundColorSpan(INK2), 0, label.length(), 0);
        ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                label.length() + 2, ss.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(BRAND_TEXT),
                label.length() + 2, ss.length(), 0);
        v.setText(ss);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);
        v.setMinHeight(dp(ctx, 40));
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 搜索输入框（44dp 高，描边分层）。 */
    public static android.widget.EditText searchInput(android.content.Context ctx, String hint) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint(hint);
        et.setTextSize(13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(ctx, 12));
        g.setStroke(dp(ctx, 1), LINE);
        et.setBackground(g);
        et.setMinHeight(dp(ctx, 44));
        et.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        return et;
    }

    /** 圆环进度（自绘 drawArc，stroke 8dp，起点 -90°，brand 色）。 */
    public static class Ring extends android.view.View {
        private float fraction;   // 0..1
        private String big = "", caption = "", suffix = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Ring(android.content.Context ctx) {
            super(ctx);
        }

        public void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        /** 环心文字：大数字 + 下方说明。 */
        public void setCenter(String big, String caption) {
            this.big = big;
            this.suffix = "";
            this.caption = caption;
            invalidate();
        }

        /** 环心文字：done 大号 + /total 小号（进度表达规范 34/50 样式）。 */
        public void setProgress(int done, int total, String cap) {
            this.big = String.valueOf(done);
            this.suffix = "/" + total;
            this.caption = cap;
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(getContext(), 86), dp(getContext(), 86));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            float stroke = dp(getContext(), 7);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(0x33FFFFFF);
            android.graphics.RectF r = new android.graphics.RectF(stroke / 2, stroke / 2,
                    w - stroke / 2, h - stroke / 2);
            c.drawArc(r, 0, 360, false, p);
            p.setColor(0xFFFFFFFF);
            c.drawArc(r, -90, 360 * fraction, false, p);
            if (big != null && !big.isEmpty()) {
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setAntiAlias(true);
                p.setColor(0xFFFFFFFF);
                float cx = w / 2;
                if (suffix != null && !suffix.isEmpty()) {
                    p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    float w1 = p.measureText(big);
                    p.setTypeface(android.graphics.Typeface.DEFAULT);
                    p.setTextSize(dp(getContext(), 12));
                    float w2 = p.measureText(suffix);
                    float x0 = w / 2 - (w1 + w2) / 2;
                    p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    c.drawText(big, x0 + w1 / 2, h / 2 + dp(getContext(), 1), p);
                    p.setTypeface(android.graphics.Typeface.DEFAULT);
                    p.setTextSize(dp(getContext(), 12));
                    p.setColor(BRAND_100);
                    c.drawText(suffix, x0 + w1 + w2 / 2, h / 2 + dp(getContext(), 1), p);
                } else {
                    p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    c.drawText(big, cx, h / 2 + dp(getContext(), 1), p);
                }
                p.setTypeface(android.graphics.Typeface.DEFAULT);
                p.setTextSize(dp(getContext(), 8));
                p.setColor(BRAND_100);
                c.drawText(caption, cx, h / 2 + dp(getContext(), 13), p);
            }
        }
    }

    public interface OnInputResult { void onResult(String value); }
}
