package com.carlos.vault;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 统一样式工具 —— 设计方案 v2 token 表 + 组件库（全项目唯一取色处）。
 *
 * 规范要点：
 *  · 一套 token：任何页面/组件不得另起色值，改色只发生在本文件；
 *  · 描边分层：卡片白底 + 1px LINE 描边 + r-3 16dp 圆角，不靠重阴影；
 *  · 触感反馈：所有可点元素挂 {@link #press}（按压 120ms 缩放 0.985），
 *    实心按钮再用 {@link #pressRound} 叠加按压加深底色；
 *  · 状态 = 色 + 圆点 + 文字三重编码（badge）；
 *  · 轻提示用 {@link #toast}（墨底反色），不再直接用系统 Toast 样式。
 */
public class UiStyle {

    // ===== 设计方案 v2 tokens（浅色）=====
    public static final int BRAND        = 0xFF2E5BFF;   // brand-500 主按钮底/强调（白字 5.1:1）
    public static final int BRAND_PRESS  = 0xFF1E43D9;   // brand-600 按压/页面底蓝字
    public static final int BRAND_DEEP   = 0xFF1733A8;   // brand-700 渐变终点
    public static final int BRAND_TEXT   = 0xFF1E43D9;   // 页面底上的蓝字
    public static final int BRAND_50     = 0xFFEEF3FF;   // surface tint
    public static final int BRAND_100    = 0xFFDCE5FF;   // 浅底悬浮态
    public static final int BRAND_200    = 0xFFBFCCFF;   // 描边（按钮/输入）
    public static final int BRAND_300    = 0xFFC7D4FF;   // 弱描边（白底上的强调框）
    public static final int ICE          = 0xFF0EA5A5;   // ice-500 仅"进行中"
    public static final int BG_V2        = 0xFFF2F5FA;   // 画布
    public static final int CARD         = 0xFFFFFFFF;   // 卡片面
    public static final int INK          = 0xFF0F1729;   // 主文本（16.4:1）
    public static final int INK2         = 0xFF61708B;   // 次文本（4.8:1）
    public static final int INK3         = 0xFF9AA6BC;   // 三级文本/表头（2.6:1，仅限非正文）
    public static final int LINE         = 0xFFE5E9F2;   // 描边/分割线
    public static final int SURFACE_2    = 0xFFF8FAFD;   // 次级面（行底/降饱和）
    public static final int SURFACE_3    = 0xFFEEF2F9;   // 三级面（控件容器底）
    public static final int LINE_STRONG  = 0xFFD2D9E6;   // 强描边（输入框/未选中勾选框）
    public static final int SWITCH_OFF   = 0xFFC9D2E0;   // 开关关闭态底色
    public static final int WAIT_50      = 0xFFE8EEFF;   // wait 浅底（进行中底）
    public static final int WECHAT_GREEN = 0xFF07C160;   // 微信品牌绿（仅微信支付按钮）
    public static final int ERR_BORDER   = 0xFFF3B4B4;   // 危险操作区浅红描边
    public static final int INVERSE      = 0xFFF5F7FB;   // 深底上的浅字（轻提示/批量条）
    public static final int WHITE        = 0xFFFFFFFF;   // 纯白（文字/底）
    // 深底（品牌渐变/墨色批量条）上的浅色系，按语义对应状态色
    public static final int ON_BRAND_OK   = 0xFF7EE2B8;  // 深底 · 正常/在线
    public static final int ON_BRAND_WARN = 0xFFFFD59E;  // 深底 · 警示/重连
    public static final int ON_BRAND_ERR  = 0xFFFF9E9E;  // 深底 · 错误/需处理
    public static final int ON_BRAND_IDLE = 0xFFDCE5FF;  // 深底 · 中性
    public static final int WHITE_14     = 0x24FFFFFF;   // 深底上的次级按钮底
    public static final int WHITE_20     = 0x33FFFFFF;   // 深底上的强调条底
    public static final int WHITE_12     = 0x1FFFFFFF;   // 深底 ghost 按钮底
    public static final int WHITE_45     = 0x73FFFFFF;   // 深底 ghost 按钮描边
    public static final int BLACK_40     = 0x66000000;   // 弹层遮罩
    public static final int SCRIM        = 0x59080C17;   // 底部弹层遮罩（规范：压暗 35%）
    // 状态徽章 bg/ink/solid 三层。type: ok/run/wait/warn/err/idle
    public static final int OK_BG = 0xFFE7F8F1,  OK_INK = 0xFF047857,  OK_SOLID = 0xFF10B981;
    public static final int RUN_BG = 0xFFE6F6F6,  RUN_INK = 0xFF0F766E, RUN_SOLID = 0xFF0EA5A5;
    public static final int WAIT_BG = 0xFFEEF0FE, WAIT_INK = 0xFF4338CA, WAIT_SOLID = 0xFF6366F1;
    public static final int WARN_BG = 0xFFFFF4E5, WARN_INK = 0xFFB45309, WARN_SOLID = 0xFFF59E0B;
    public static final int ERR_BG = 0xFFFEECEC,  ERR_INK = 0xFFB91C1C, ERR_SOLID = 0xFFEF4444;
    public static final int IDLE_BG = 0xFFF1F3F7, IDLE_INK = 0xFF6B7280;

    /** dp → px（四舍五入）。 */
    public static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    public static GradientDrawable roundRect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(color);
        return d;
    }

    /** dp 圆角圆角矩形（工厂内部统一走这里，避免 px/dp 混用）。 */
    public static GradientDrawable roundRectDp(Context ctx, int color, int radiusDp) {
        return roundRect(color, dp(ctx, radiusDp));
    }

    /** 描边圆角矩形。 */
    public static GradientDrawable roundRectStroke(Context ctx, int color, int radiusDp,
                                                   int strokeColor) {
        GradientDrawable d = roundRectDp(ctx, color, radiusDp);
        d.setStroke(dp(ctx, 1), strokeColor);
        return d;
    }

    // ==================== 交互反馈（规范：按压 120ms · 缩放 0.985）====================

    /**
     * 给可点元素挂触感反馈：按下缩至 0.985，抬起/取消 120ms 回弹。
     * 不消费事件，点击行为照常；对全屏遮罩类视图不要挂。
     */
    public static void press(final View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(100L).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
                        break;
                }
                return false;
            }
        });
    }

    /** 实心底色按压加深（状态两态：normal / pressed）。 */
    public static StateListDrawable pressRound(Context ctx, int normal, int pressed, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, roundRectDp(ctx, pressed, radiusDp));
        s.addState(new int[]{}, roundRectDp(ctx, normal, radiusDp));
        return s;
    }

    // ==================== 容器 ====================

    /** 分组卡片：白底 + 1px LINE 描边 + r-3 16dp（描边分层，不靠重阴影）。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRectStroke(ctx, CARD, 16, LINE));
        c.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), 0);
        c.setLayoutParams(lp);
        return c;
    }

    /** 描边卡片（无阴影分层：白底 + 1px LINE 描边 + r-3 16dp），边距由调用方控制。 */
    public static LinearLayout outlineCard(android.content.Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRectStroke(ctx, CARD, 16, LINE));
        return c;
    }

    /** 1px 分割线（分组列表行间）。 */
    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        return v;
    }

    // ==================== 文字 ====================

    /** 分组标题（Label 13sp/600，区块间 22dp 节奏）。 */
    public static android.widget.TextView sectionTitle(android.content.Context ctx, String text) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(INK);
        v.setPadding(dp(ctx, 2), dp(ctx, 18), dp(ctx, 2), dp(ctx, 8));
        return v;
    }

    /** 状态徽章（22dp 高、全圆角胶囊、bg+ink 双层，文字≤8字）。type: ok/run/wait/warn/err/idle */
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
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(ink);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        v.setBackground(roundRectDp(ctx, bg, 999));   // 全圆胶囊
        return v;
    }

    /** 横向 chip（选中=BRAND 底白字；未选=BRAND_50 底 BRAND_TEXT 字），全圆胶囊。 */
    public static android.widget.TextView chip(android.content.Context ctx, String text, boolean selected) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setTextColor(selected ? WHITE : BRAND_TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        v.setBackground(roundRectDp(ctx, selected ? BRAND : BRAND_50, 999));
        press(v);
        return v;
    }

    /** 灰chip（计数筛选器用），全圆胶囊。 */
    public static android.widget.TextView chipGray(android.content.Context ctx, String text, boolean selected) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setTextColor(selected ? WHITE : INK2);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        v.setBackground(roundRectDp(ctx, selected ? BRAND : WHITE, 999));
        press(v);
        return v;
    }

    // ==================== 按钮 ====================

    /** 主按钮（48dp · r-2 12dp · BRAND 底白字 · 按压加深+缩放）。 */
    public static android.widget.TextView primaryBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(WHITE);
        v.setGravity(android.view.Gravity.CENTER);
        v.setBackground(pressRound(ctx, BRAND, BRAND_PRESS, 12));
        v.setMinHeight(dp(ctx, 48));
        press(v);
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 次级按钮（40dp · r-2 12dp · 白底 BRAND_300 描边 · 按压 BRAND_50）。 */
    public static android.widget.TextView secondaryBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(BRAND_TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        StateListDrawable g = new StateListDrawable();
        g.addState(new int[]{android.R.attr.state_pressed}, roundRectDp(ctx, BRAND_50, 12));
        g.addState(new int[]{}, roundRectStroke(ctx, WHITE, 12, BRAND_300));
        v.setBackground(g);
        v.setMinHeight(dp(ctx, 40));
        press(v);
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 危险按钮（40dp · ERR_SOLID 底白字 · 按压加深）。 */
    public static android.widget.TextView dangerBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(WHITE);
        v.setGravity(android.view.Gravity.CENTER);
        v.setBackground(pressRound(ctx, ERR_SOLID, 0xFFDC2626, 12));
        v.setMinHeight(dp(ctx, 40));
        press(v);
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** ghost 按钮（透明底 BRAND_TEXT 字 · 按压 BRAND_50）。 */
    public static android.widget.TextView ghostBtn(android.content.Context ctx, String text, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(BRAND_TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        v.setBackground(pressRound(ctx, Color.TRANSPARENT, BRAND_50, 12));
        v.setMinHeight(dp(ctx, 40));
        v.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        press(v);
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 卡片内行按钮（图标 + 文字），按压整体加深。 */
    public static LinearLayout makeRowBtn(Context ctx, String icon, String text, int textColor, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 14));
        row.setBackground(pressRound(ctx, SURFACE_2, SURFACE_3, 10));

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

    /** 分段控件（如 矩阵|列表）：一体圆角容器 + 选中段 brand 底白字，各段带按压。 */
    public static android.widget.LinearLayout segmented(android.content.Context ctx,
                                                        String[] options, int selected,
                                                        final java.util.function.IntConsumer onSelect) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setBackground(roundRectDp(ctx, SURFACE_3, 14));
        wrap.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            android.widget.TextView seg = new android.widget.TextView(ctx);
            seg.setText(options[i]);
            seg.setTextSize(12);
            seg.setTypeface(i == selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            seg.setTextColor(i == selected ? WHITE : INK2);
            seg.setGravity(android.view.Gravity.CENTER);
            seg.setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6));
            if (i == selected) seg.setBackground(roundRectDp(ctx, BRAND, 11));
            seg.setOnClickListener(v -> {
                if (onSelect != null) onSelect.accept(idx);
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            wrap.addView(seg, lp);
        }
        return wrap;
    }

    /** 下拉选择行："标签  当前值 ▾"（label 灰 + 值 brand 粗），带按压。 */
    public static android.widget.TextView dropdownRow(android.content.Context ctx,
                                                      String label, String value, Runnable onClick) {
        android.widget.TextView v = new android.widget.TextView(ctx);
        v.setTextSize(12);
        android.text.SpannableString ss = new android.text.SpannableString(label + "  " + value + " ▾");
        ss.setSpan(new android.text.style.ForegroundColorSpan(INK2), 0, label.length(), 0);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                label.length() + 2, ss.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(BRAND_TEXT),
                label.length() + 2, ss.length(), 0);
        v.setText(ss);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);
        v.setMinHeight(dp(ctx, 40));
        press(v);
        if (onClick != null) v.setOnClickListener(x -> onClick.run());
        return v;
    }

    /** 搜索输入框（44dp 高 · 白底 · LINE_STRONG 描边 · 聚焦转 BRAND）。 */
    public static android.widget.EditText searchInput(android.content.Context ctx, String hint) {
        return inputBox(ctx, hint, false);
    }

    /** 规范输入框：r-2 12dp · LINE_STRONG 描边 · 聚焦 BRAND 描边。 */
    private static android.widget.EditText inputBox(final android.content.Context ctx,
                                                    String hint, boolean multiline) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint(hint);
        et.setTextSize(13);
        et.setTextColor(INK);
        et.setHintTextColor(INK3);
        et.setBackground(roundRectStroke(ctx, WHITE, 12, LINE_STRONG));
        et.setMinHeight(dp(ctx, 44));
        et.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ((EditText) v).setBackground(roundRectStroke(ctx, WHITE, 12,
                        hasFocus ? BRAND : LINE_STRONG));
            }
        });
        if (multiline) { et.setMinLines(5); et.setGravity(Gravity.TOP); }
        return et;
    }

    // ==================== 轻提示 / 空状态 ====================

    /** 规范轻提示：墨底 + 反色字 + r-2 圆角，短时自动消失。 */
    public static void toast(Context ctx, String msg) {
        TextView v = new TextView(ctx);
        v.setText(msg);
        v.setTextSize(13);
        v.setTextColor(INVERSE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(roundRectDp(ctx, INK, 12));
        v.setPadding(dp(ctx, 16), dp(ctx, 11), dp(ctx, 16), dp(ctx, 11));
        Toast t = new Toast(ctx);
        t.setView(v);
        t.setDuration(Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, dp(ctx, 80));
        t.show();
    }

    /**
     * 空状态（规范：空状态必须能直接完成任务）。
     * 图标 + 标题 + 副文案 + 可选动作按钮；返回整块视图，调用方 addView 即可。
     */
    public static LinearLayout emptyState(Context ctx, String icon, String title, String sub,
                                          String actionText, Runnable action) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(ctx, 24), dp(ctx, 36), dp(ctx, 24), dp(ctx, 28));

        if (icon != null && !icon.isEmpty()) {
            TextView ic = new TextView(ctx);
            ic.setText(icon);
            ic.setTextSize(40);
            ic.setGravity(Gravity.CENTER);
            box.addView(ic);
        }
        TextView tt = new TextView(ctx);
        tt.setText(title);
        tt.setTextSize(15);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        tt.setTextColor(INK);
        tt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(ctx, 12);
        box.addView(tt, tlp);

        if (sub != null && !sub.isEmpty()) {
            TextView ss = new TextView(ctx);
            ss.setText(sub);
            ss.setTextSize(13);
            ss.setTextColor(INK2);
            ss.setGravity(Gravity.CENTER);
            ss.setLineSpacing(dp(ctx, 3), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(ctx, 6);
            box.addView(ss, slp);
        }
        if (actionText != null && !actionText.isEmpty()) {
            TextView btn = secondaryBtn(ctx, actionText, action);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    dp(ctx, 180), dp(ctx, 40));
            blp.topMargin = dp(ctx, 18);
            box.addView(btn, blp);
        }
        return box;
    }

    // ==================== 弹窗 ====================

    /** 弹窗动作按钮：主操作=BRAND 实心胶囊；危险=ERR_SOLID 实心；取消=SURFACE_3 胶囊。 */
    private static TextView dialogBtn(Context ctx, String text, int accent, boolean filled) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(ctx, 40));
        v.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        if (filled) {
            int bg = accent == ERR_INK ? ERR_SOLID : BRAND;
            int bgPress = accent == ERR_INK ? 0xFFDC2626 : BRAND_PRESS;
            v.setTextColor(WHITE);
            v.setBackground(pressRound(ctx, bg, bgPress, 12));
        } else {
            v.setTextColor(accent == ERR_INK ? ERR_INK : INK2);
            v.setBackground(pressRound(ctx, SURFACE_3, LINE, 12));
        }
        press(v);
        return v;
    }

    /** 输入弹窗（r-5 20dp 白卡 · 规范输入框 · 胶囊按钮）。 */
    public static void showInputDialog(Context ctx, String title, String hint, String prefill,
                                       boolean multiline, OnInputResult callback) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRectDp(ctx, CARD, 20));
        box.setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 20));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(INK);
        box.addView(tv);

        EditText et = inputBox(ctx, hint, multiline);
        et.setTextSize(14);
        if (prefill != null && !prefill.isEmpty()) et.setText(prefill);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(ctx, 14);
        box.addView(et, etLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(ctx, 16), 0, 0);

        TextView cancel = dialogBtn(ctx, "取消", INK2, false);
        btnRow.addView(cancel);

        TextView ok = dialogBtn(ctx, "确定", BRAND_TEXT, true);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = dp(ctx, 10);
        btnRow.addView(ok, okLp);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        cancel.setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> { callback.onResult(et.getText().toString()); dlg.dismiss(); });
        dlg.show();
    }

    /** 确认弹窗。posColor 传 ERR_INK 时确认按钮为红色实心（危险操作），其余为 BRAND 实心。 */
    public static void showConfirmDialog(Context ctx, String title, String message,
                                         String posText, int posColor, Runnable onConfirm) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRectDp(ctx, CARD, 20));
        box.setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 20));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(INK);
        box.addView(tv);

        TextView msg = new TextView(ctx);
        msg.setText(message);
        msg.setTextSize(14);
        msg.setTextColor(INK2);
        msg.setLineSpacing(dp(ctx, 3), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(ctx, 10);
        box.addView(msg, msgLp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(ctx, 16), 0, 0);

        TextView cancel = dialogBtn(ctx, "取消", INK2, false);
        btnRow.addView(cancel);

        TextView ok = dialogBtn(ctx, posText, posColor, true);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = dp(ctx, 10);
        btnRow.addView(ok, okLp);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(ctx, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        cancel.setOnClickListener(v -> dlg.dismiss());
        ok.setOnClickListener(v -> { onConfirm.run(); dlg.dismiss(); });
        dlg.show();
    }

    // ==================== 自绘控件 ====================

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
            p.setColor(WHITE);
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

    /** 圆环进度（自绘 drawArc，stroke 7dp，起点 -90°，白系：置于品牌渐变 Hero 上）。 */
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
            p.setColor(WHITE);
            c.drawArc(r, -90, 360 * fraction, false, p);
            if (big != null && !big.isEmpty()) {
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setAntiAlias(true);
                p.setColor(WHITE);
                float cx = w / 2;
                if (suffix != null && !suffix.isEmpty()) {
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    float w1 = p.measureText(big);
                    p.setTypeface(Typeface.DEFAULT);
                    p.setTextSize(dp(getContext(), 12));
                    float w2 = p.measureText(suffix);
                    float x0 = w / 2 - (w1 + w2) / 2;
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    c.drawText(big, x0 + w1 / 2, h / 2 + dp(getContext(), 1), p);
                    p.setTypeface(Typeface.DEFAULT);
                    p.setTextSize(dp(getContext(), 12));
                    p.setColor(BRAND_100);
                    c.drawText(suffix, x0 + w1 + w2 / 2, h / 2 + dp(getContext(), 1), p);
                } else {
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(dp(getContext(), 17));
                    c.drawText(big, cx, h / 2 + dp(getContext(), 1), p);
                }
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(dp(getContext(), 8));
                p.setColor(BRAND_100);
                c.drawText(caption, cx, h / 2 + dp(getContext(), 13), p);
            }
        }
    }

    public interface OnInputResult { void onResult(String value); }
}
