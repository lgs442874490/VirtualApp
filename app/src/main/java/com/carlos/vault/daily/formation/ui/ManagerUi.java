package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiLayoutRules;
import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.FormationStore;

/**
 * 编队管理页/编队板共享的排版与视觉工具（T4 自有辅助类，非契约类型）。
 *
 * <p>视觉基准：{@code design/ui-ref-编队-20261025/README.md} §2 —— 463dp 定宽弹窗、
 * 主色 {@code #4F46E5}、卡片白底 + 1px {@code #CFD5E0} 描边 + 圆角 16、页底 {@code #F3F5F9}。
 * 所有界面代码构建（无 XML），基础控件复用 {@link UiStyle}。
 *
 * <p><b>【UI 规范例外 · 2026-11-05 裁定】</b>本包是线上网页版编队界面的 <b>1:1 像素复刻</b>
 * （对标稿见上，含 463dp 定宽、12dp 边距、13/15/16px 字号、8/10/12/16 圆角、职业色）。
 * 因此下列项<b>不适用</b> S1/S2/S4/S5 的全局 v2 token，不要"统一"到 {@code UiStyle.BRAND}、
 * 18dp 边距或 {@code UiComponents.TEXT_*}，否则会破坏复刻一致性：
 * 色板、DESIGN_WIDTH_DP、designWidth() 的 marginDp、圆角档位、字号档位、职业色。
 * 例外范围内仍必须遵守：颜色一律登记为本类具名常量（不得散落字面量）、
 * 可点元素挂 {@link UiStyle#press}、轻提示用 {@link UiStyle#toast}、不使用已废弃 ProgressDialog。
 * 详见 {@code qa/UI规范一致性核查清单-20261105.md}「例外适用范围」。
 */
public final class ManagerUi {

    private ManagerUi() {
    }

    // ===== 主题令牌（ui-ref README §2.0）=====
    public static final int PRIMARY = 0xFF4F46E5;         // el-color-primary
    public static final int PRIMARY_DEEP = 0xFF3730A3;    // tab 选中字
    public static final int PRIMARY_50 = 0xFFEEF2FF;      // tab 选中底
    public static final int PRIMARY_100 = 0xFFE0E7FF;
    public static final int PRIMARY_300 = 0xFFC7D2FE;
    public static final int PAGE = 0xFFF3F5F9;            // 弹窗内页底
    public static final int CARD = 0xFFFFFFFF;
    public static final int SOFT = 0xFFF8FAFC;
    public static final int LINE = 0xFFCFD5E0;            // 弹窗/卡片描边
    public static final int LINE_SOFT = 0xFFE5E9F2;
    public static final int TEXT = 0xFF0F172A;
    public static final int TEXT2 = 0xFF334155;
    public static final int TEXT3 = 0xFF64748B;
    public static final int TEXT4 = 0xFF94A3B8;
    public static final int WARN_BG = 0xFFFFF4E5;
    public static final int WARN_INK = 0xFFB45309;
    public static final int OK_BG = 0xFFE7F8F1;
    public static final int OK_INK = 0xFF047857;
    public static final int INFO_BG = 0xFFEEF2FF;
    public static final int INFO_INK = 0xFF3730A3;
    public static final int ERR_BG = 0xFFFEECEC;
    public static final int ERR_INK = 0xFFB91C1C;
    public static final int SLATE = 0xFF64748B;           // 职业角标底（规格：#64748b 底白字）

    // ===== 补充语义色（2026-11-05 统一登记，原散落在 FormationBoardView / HeroTileView 等）=====
    // 例外范围内「颜色一律登记为具名常量」的落地；仍属对标稿色系，不并入 UiStyle。
    public static final int BADGE_SLATE_BG  = 0xFFEEF0F4;  // 兵种徽章底
    public static final int SOFT_BLUE_BG    = 0xFFF1F5F9;  // 次级浅蓝底
    public static final int INFO_SOLID      = 0xFF6366F1;  // 容量 / 宠物信息图标底
    public static final int WARN_SOLID      = 0xFFF59E0B;  // 战争增益图标底
    public static final int PRIMARY_PRESS   = 0xFF4338CA;  // 主按钮按压态
    public static final int HERO_GRAD_TOP   = 0xE60B1220;  // 英雄卡渐变（上/中/下）
    public static final int HERO_GRAD_MID   = 0x590B1220;
    public static final int HERO_GRAD_END   = 0x000B1220;
    public static final int TIER_INK        = 0xFFFDE68A;  // tier 徽章字
    public static final int REMOVE_SCRIM    = 0x66000000;  // 英雄卡移除按钮遮罩
    public static final int CAPTAIN_BG      = 0xFFFBBF24;  // 队长标记 / 亮星
    public static final int STAR_OFF        = 0x66FFFFFF;  // 暗星
    public static final int WHITE_80        = 0xCCFFFFFF;  // 深底上的主按钮字（禁用态）
    public static final int ERR_SOLID       = 0xFFE5484D;
    public static final int ERR_PRESS       = 0xFFDC2626;
    public static final int PROF_COLOR_INFANTRY   = 0xFF64748B;  // 盾
    public static final int PROF_COLOR_LANCER     = 0xFF0EA5E9;  // 矛
    public static final int PROF_COLOR_MARKSMAN   = 0xFFF59E0B;  // 弓
    public static final int PROF_COLOR_UNKNOWN    = 0xFF94A3B8;
    public static final int STATUS_BAR      = 0xFF000000;  // 编队页状态栏
    public static final int PAGE_SCRIM      = 0x99080C17;  // 编队页背景遮罩

    /** 设计基准宽（463dp）。 */
    public static final int DESIGN_WIDTH_DP = 463;

    // ===== 尺寸 =====
    public static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 编队弹窗保留 463dp 设计上限，与全局共用窗口宽度/边距算法。 */
    public static int designWidth(Context ctx) {
        return UiLayoutRules.contentWidthPx(
                ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().density,
                DESIGN_WIDTH_DP, 12);
    }

    // ===== 背景 =====

    public static GradientDrawable round(Context ctx, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setColor(color);
        return d;
    }

    public static GradientDrawable stroke(Context ctx, int color, int radiusDp, int strokeColor) {
        GradientDrawable d = round(ctx, color, radiusDp);
        d.setStroke(dp(ctx, 1), strokeColor);
        return d;
    }

    public static GradientDrawable strokeWidth(Context ctx, int color, int radiusDp,
                                               int strokeDp, int strokeColor) {
        GradientDrawable d = round(ctx, color, radiusDp);
        d.setStroke(Math.max(1, dp(ctx, strokeDp)), strokeColor);
        return d;
    }

    public static GradientDrawable circle(Context ctx, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static StateListDrawable pressable(Context ctx, int normal, int pressed, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{-android.R.attr.state_enabled}, round(ctx, SOFT, radiusDp));
        s.addState(new int[]{android.R.attr.state_pressed}, round(ctx, pressed, radiusDp));
        s.addState(new int[]{}, round(ctx, normal, radiusDp));
        return s;
    }

    // ===== 文本 =====

    public static TextView text(Context ctx, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s == null ? "" : s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 主文本 13sp。 */
    public static TextView text(Context ctx, String s) {
        return text(ctx, s, 13, TEXT, false);
    }

    /** 区块标签：13sp 粗体 + 上下留白。 */
    public static TextView label(Context ctx, String s, int topDp, int bottomDp) {
        TextView t = text(ctx, s, 13, TEXT, true);
        t.setPadding(0, dp(ctx, topDp), 0, dp(ctx, bottomDp));
        return t;
    }

    /** 次要说明文字：11.5sp TEXT3。 */
    public static TextView sub(Context ctx, String s) {
        return text(ctx, s, 11.5f, TEXT3, false);
    }

    /** 单行省略。 */
    public static TextView ellipsize(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    // ===== 布局 =====

    public static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpMargin(int w, int h, int l, int t, int r, int b,
                                                     Context ctx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b));
        return p;
    }

    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(LINE_SOFT);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        return v;
    }

    public static View spacer(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, hDp)));
        return v;
    }

    // ===== 小按钮 / chip =====

    /** 描边小按钮（36dp 高，圆角 10，白底 LINE 描边，主色粗体字）。 */
    public static TextView smallButton(Context ctx, String text, boolean primary, Runnable onClick) {
        TextView t = text(ctx, text, 12, primary ? Color.WHITE : PRIMARY_DEEP, true);
        t.setTextColor(UiComponents.enabledTextColors(primary ? Color.WHITE : PRIMARY_DEEP));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(ctx, 44));
        t.setMinWidth(dp(ctx, 64));
        t.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        if (primary) {
            t.setBackground(pressable(ctx, PRIMARY, 0xFF4338CA, 10));
        } else {
            StateListDrawable g = new StateListDrawable();
            g.addState(new int[]{-android.R.attr.state_enabled}, round(ctx, SOFT, 10));
            g.addState(new int[]{android.R.attr.state_pressed}, round(ctx, PRIMARY_50, 10));
            g.addState(new int[]{}, stroke(ctx, CARD, 10, LINE));
            t.setBackground(g);
        }
        t.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return t;
    }

    /** 全圆 chip（选中=PRIMARY_50 底 + PRIMARY_DEEP 字 + PRIMARY 描边）。 */
    public static TextView chip(Context ctx, String text, boolean selected, Runnable onClick) {
        TextView t = text(ctx, text, 12, selected ? PRIMARY_DEEP : TEXT3,
                selected);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        if (selected) {
            t.setBackground(stroke(ctx, PRIMARY_50, 999, PRIMARY_300));
        } else {
            t.setBackground(stroke(ctx, CARD, 999, LINE_SOFT));
        }
        t.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return t;
    }

    /** 计数型子 tab（144×32，active=PRIMARY_50 底 + PRIMARY_DEEP 字 + PRIMARY_300 描边）。 */
    public static TextView subTab(Context ctx, String text, boolean active, Runnable onClick) {
        TextView t = text(ctx, text, 12, active ? PRIMARY_DEEP : TEXT2, active);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(ctx, 32));
        t.setBackground(active ? stroke(ctx, PRIMARY_50, 10, PRIMARY_300)
                : stroke(ctx, CARD, 10, LINE_SOFT));
        t.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return t;
    }

    /** 方形图标按钮（约 30–32dp，圆角 10）。 */
    public static FrameLayout iconButton(Context ctx, int bg, Drawable icon, int sizeDp,
                                         Runnable onClick) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(round(ctx, bg, 10));
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int pad = dp(ctx, sizeDp <= 30 ? 7 : 8);
        ilp.setMargins(pad, pad, pad, pad);
        box.addView(iv, ilp);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        if (onClick != null) {
            box.setOnClickListener(v -> onClick.run());
            UiStyle.press(box);
        }
        return box;
    }

    // ===== 职业 =====

    public static final int PROF_SHIELD = 1;
    public static final int PROF_LANCER = 2;
    public static final int PROF_BOW = 3;

    public static String professionLabel(int type) {
        switch (type) {
            case PROF_SHIELD: return "盾兵";
            case PROF_LANCER: return "矛兵";
            case PROF_BOW: return "弓兵";
            default: return "英雄";
        }
    }

    public static String professionChar(int type) {
        switch (type) {
            case PROF_SHIELD: return "盾";
            case PROF_LANCER: return "矛";
            case PROF_BOW: return "弓";
            default: return "英";
        }
    }

    public static String professionHeroLabel(int type) {
        switch (type) {
            case PROF_SHIELD: return "盾兵英雄";
            case PROF_LANCER: return "矛兵英雄";
            case PROF_BOW: return "弓兵英雄";
            default: return "英雄";
        }
    }

    /** 职业强调色（空位图标 / 兵种徽章）。 */
    public static int professionAccent(int type) {
        switch (type) {
            case PROF_SHIELD: return 0xFF3B82F6;
            case PROF_LANCER: return 0xFF8B5CF6;
            case PROF_BOW: return 0xFFF59E0B;
            default: return SLATE;
        }
    }

    /** 职业浅底（空位圆圈 / 兵种徽章底）。 */
    public static int professionTint(int type) {
        switch (type) {
            case PROF_SHIELD: return 0xFFEFF6FF;
            case PROF_LANCER: return 0xFFF5F3FF;
            case PROF_BOW: return 0xFFFFF7ED;
            default: return 0xFFF1F5F9;
        }
    }

    /** 职业中文（FormationStore.Hero，缺失时按 0 处理）。 */
    public static int professionOf(FormationStore.Hero hero) {
        if (hero == null) return 0;
        if (hero.professionType >= 1 && hero.professionType <= 3) return hero.professionType;
        // 服务端 profession 数值/中文兜底
        if ("盾兵".equals(hero.profession)) return PROF_SHIELD;
        if ("矛兵".equals(hero.profession)) return PROF_LANCER;
        if ("射手".equals(hero.profession) || "弓兵".equals(hero.profession)) return PROF_BOW;
        return 0;
    }

    // ===== 头像 =====

    /**
     * 英雄头像（{@code FormationIcons.avatar} 内置 WebP；缺失时职业色块占位）。
     * 返回等边 ImageView（CENTER_CROP，圆角由调用方裁剪或自行叠底）。
     */
    public static ImageView avatar(Activity act, long heroId, int sizeDp, int radiusDp) {
        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable d = null;
        try {
            d = com.carlos.vault.daily.formation.FormationIcons.avatar(act, heroId);
        } catch (Throwable ignored) {
        }
        if (d != null) {
            iv.setImageDrawable(d);
        } else {
            placeHeroPlaceholder(act, iv, professionOfId(heroId), sizeDp, radiusDp);
        }
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return iv;
    }

    /** 头像兜底：职业色块 + 首字（无目录时用 "英"）。 */
    public static void placeHeroPlaceholder(Activity act, ImageView iv, int professionType,
                                            int sizeDp, int radiusDp) {
        iv.setImageDrawable(null);
        iv.setBackground(round(act, professionTint(professionType), radiusDp));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        int iconSize = Math.max(16, Math.round(sizeDp * 0.42f));
        iv.setImageDrawable(new LetterDrawable(act, professionChar(professionType),
                professionAccent(professionType), iconSize));
    }

    /** 未知职业：1/2/3 轮换以保持视觉区分（仅在无目录时兜底）。 */
    private static int professionOfId(long heroId) {
        return 0;
    }

    /** 首字 Drawable（占位头像用）。 */
    private static final class LetterDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String letter;

        LetterDrawable(Context ctx, String letter, int color, int sizePx) {
            this.letter = letter;
            p.setColor(color);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(sizePx);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(letter, b.centerX(), y, p);
        }

        @Override public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 圆形英雄头像（按职业着色描边的 44dp 圆）。 */
    public static FrameLayout circleAvatar(Activity act, long heroId, int sizeDp) {
        FrameLayout box = new FrameLayout(act);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(act, sizeDp), dp(act, sizeDp)));
        View bg = new View(act);
        bg.setBackground(circle(act, professionTint(0)));
        box.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            Drawable d = com.carlos.vault.daily.formation.FormationIcons.avatar(act, heroId);
            if (d != null) iv.setImageDrawable(d);
        } catch (Throwable ignored) {
        }
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ilp.setMargins(dp(act, 2), dp(act, 2), dp(act, 2), dp(act, 2));
        box.addView(iv, ilp);
        return box;
    }

    // ===== 滑杆 =====

    /** 主色滑杆（进度/滑块着色，去掉左右 split track 视觉杂质）。 */
    public static SeekBar slider(Context ctx, int max, int progress,
                                 SeekBar.OnSeekBarChangeListener listener) {
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(Math.max(1, max));
        sb.setProgress(Math.max(0, Math.min(max, progress)));
        try {
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
            sb.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFE2E8F0));
        } catch (Throwable ignored) {
        }
        sb.setOnSeekBarChangeListener(listener);
        return sb;
    }

    // ===== 空状态（弹窗内嵌） =====

    public static LinearLayout emptyBlock(Context ctx, String iconText, String title, String subText) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(ctx, 20), dp(ctx, 34), dp(ctx, 20), dp(ctx, 30));
        TextView ic = text(ctx, iconText, 30, TEXT4, false);
        ic.setGravity(Gravity.CENTER);
        box.addView(ic);
        TextView tt = text(ctx, title, 14, TEXT2, true);
        tt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(ctx, 10);
        box.addView(tt, tlp);
        if (subText != null && !subText.isEmpty()) {
            TextView ss = text(ctx, subText, 12, TEXT3, false);
            ss.setGravity(Gravity.CENTER);
            ss.setLineSpacing(dp(ctx, 2), 1f);
            LinearLayout.LayoutParams slp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(ctx, 6);
            box.addView(ss, slp);
        }
        return box;
    }

    /** 状态胶囊（如「容量已同步」）。 */
    public static TextView tag(Context ctx, String text, int bg, int ink) {
        TextView t = ManagerUi.text(ctx, text, 10.5f, ink, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        t.setBackground(round(ctx, bg, 999));
        return t;
    }

    /** 设宽高（dp）。 */
    public static void size(View v, Context ctx, int wDp, int hDp) {
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, wDp), dp(ctx, hDp)));
    }
}
