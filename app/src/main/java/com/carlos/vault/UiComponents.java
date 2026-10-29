package com.carlos.vault;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设计系统 v2 的组合组件。业务页只组装这些组件，不再复制顶栏、正文宽度和卡片样式。
 *
 * <p>职责边界：{@link UiStyle} 负责颜色、圆角、原生控件与交互反馈；本类负责页面骨架、
 * 文字层级、通用卡片和按钮状态。组件不包含账号、任务、编队等业务规则，也不发起接口请求。</p>
 *
 * <p>命名约定：{@code page/root/topBar} 为结构组件，{@code title/body/caption} 为文字组件，
 * {@code card/iconCard} 为容器组件，{@code compactPrimaryButton} 为 44dp 紧凑主按钮。
 * 业务特有布局应留在业务页；只有跨两个以上页面复用时才下沉到本类。</p>
 */
public final class UiComponents {
    public static final float TEXT_DISPLAY = 30f;
    public static final float TEXT_PAGE_TITLE = 19f;
    public static final float TEXT_HEADLINE = 16f;
    public static final float TEXT_BODY = 14f;
    public static final float TEXT_LABEL = 13f;
    public static final float TEXT_CAPTION = 11.5f;
    public static final float TEXT_MICRO = 10.5f;

    private UiComponents() {
    }

    /** 标准页面根节点：竖向、统一画布色。 */
    public static LinearLayout pageRoot(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);
        return root;
    }

    /**
     * 标准详情页顶栏：44dp 返回热区 + 19sp 标题 + 可选副标题 + 可选右侧动作。
     * 所有动作由调用方提供并保留自己的业务回调；本组件只负责布局。
     */
    public static LinearLayout topBar(Context ctx, String title, String subtitle,
                                      Runnable onBack, View... actions) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int side = UiStyle.dp(ctx, UiLayoutRules.SCREEN_MARGIN_DP);
        bar.setPadding(side, UiStyle.dp(ctx, 10), side, UiStyle.dp(ctx, 8));
        if (onBack != null) {
            bar.addView(iconButton(ctx, UiStyle.Icon.ARROW_LEFT, "返回", onBack),
                    new LinearLayout.LayoutParams(UiStyle.dp(ctx, 44), UiStyle.dp(ctx, 44)));
        }

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiStyle.dp(ctx, onBack == null ? 0 : 4), 0, UiStyle.dp(ctx, 8), 0);
        TextView titleView = text(ctx, title, TEXT_PAGE_TITLE, UiStyle.INK, true);
        titleView.setTag("title");
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = caption(ctx, subtitle, UiStyle.INK2);
            sub.setTag("subtitle");
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, UiStyle.dp(ctx, 2), 0, 0);
            titles.addView(sub);
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (actions != null) {
            for (View action : actions) {
                if (action == null) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = UiStyle.dp(ctx, 6);
                bar.addView(action, lp);
            }
        }
        return bar;
    }

    /** 44dp 图标按钮；图标只是视觉，contentDescription 必须表达实际动作。 */
    public static FrameLayout iconButton(Context ctx, int icon, String description, Runnable onClick) {
        FrameLayout button = new FrameLayout(ctx);
        button.setBackground(UiStyle.pressRound(ctx, android.graphics.Color.TRANSPARENT,
                UiStyle.SURFACE_3, 12));
        button.setContentDescription(description);
        button.setFocusable(true);
        int iconDp = 22;
        button.addView(UiStyle.icon(ctx, icon, UiStyle.INK2, iconDp),
                new FrameLayout.LayoutParams(UiStyle.dp(ctx, iconDp),
                        UiStyle.dp(ctx, iconDp), Gravity.CENTER));
        UiStyle.press(button);
        if (onClick != null) button.setOnClickListener(v -> onClick.run());
        return button;
    }

    /**
     * 标准滚动页。body 是调用方唯一内容容器；宽屏下内容限宽居中，手机保持 18dp 边距。
     * Activity 重建时会按新的窗口宽度重新计算，支持分屏和窗口尺寸变化。
     */
    public static Page scrollPage(Context ctx, String title, String subtitle,
                                  Runnable onBack, int bottomPaddingDp, View... actions) {
        Page page = new Page();
        page.root = pageRoot(ctx);
        page.root.addView(topBar(ctx, title, subtitle, onBack, actions));
        page.scroll = new ScrollView(ctx);
        page.scroll.setFillViewport(false);
        page.scroll.setClipToPadding(false);
        page.body = new LinearLayout(ctx);
        page.body.setOrientation(LinearLayout.VERTICAL);
        page.body.setPadding(0, UiStyle.dp(ctx, 4), 0,
                UiStyle.dp(ctx, Math.max(0, bottomPaddingDp)));
        FrameLayout host = contentHost(ctx, page.body);
        page.scroll.addView(host, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.root.addView(page.scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    /** 将任意正文按当前窗口宽度加上 18dp 边距，并在宽屏限宽居中。 */
    public static FrameLayout contentHost(Context ctx, View content) {
        FrameLayout host = new FrameLayout(ctx);
        int width = UiLayoutRules.contentWidthPx(
                ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        ViewGroup.LayoutParams old = content.getLayoutParams();
        if (old instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) old;
            lp.topMargin = margins.topMargin;
            lp.bottomMargin = margins.bottomMargin;
        }
        host.addView(content, lp);
        return host;
    }

    /** 滚动页结构句柄；只暴露 View，不暴露任何业务状态。 */
    public static final class Page {
        public LinearLayout root;
        public ScrollView scroll;
        public LinearLayout body;
    }

    /** 正文文字；bold 仅表达层级，不使用伪斜体或新增字体。 */
    public static TextView text(Context ctx, String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(ctx);
        view.setText(value == null ? "" : value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(UiStyle.dp(ctx, 2), 1f);
        return view;
    }

    public static TextView title(Context ctx, String value) {
        return text(ctx, value, TEXT_HEADLINE, UiStyle.INK, true);
    }

    public static TextView body(Context ctx, String value) {
        return text(ctx, value, TEXT_BODY, UiStyle.INK2, false);
    }

    public static TextView caption(Context ctx, String value, int color) {
        return text(ctx, value, TEXT_CAPTION, color, false);
    }

    /** 数值文本：等宽数字避免倒计时、UID 和进度刷新时横向抖动。 */
    public static TextView numeric(Context ctx, String value, float sizeSp, int color, boolean bold) {
        TextView view = text(ctx, value, sizeSp, color, bold);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    /** 左标题 + 右辅助说明的分区行；窄屏辅助说明最多两行，不挤压主标题。 */
    public static LinearLayout sectionHeader(Context ctx, String title, String hint) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiStyle.dp(ctx, 2), UiStyle.dp(ctx, 18),
                UiStyle.dp(ctx, 2), UiStyle.dp(ctx, 8));
        row.addView(text(ctx, title, TEXT_BODY, UiStyle.INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (hint != null && !hint.isEmpty()) {
            TextView hintView = caption(ctx, hint, UiStyle.INK3);
            hintView.setMaxLines(2);
            hintView.setGravity(Gravity.END);
            row.addView(hintView);
        }
        return row;
    }

    /** 标准内容卡：16dp 圆角、1px 描边、14/16dp 内边距；默认仅上间距。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = UiStyle.outlineCard(ctx);
        card.setPadding(UiStyle.dp(ctx, 16), UiStyle.dp(ctx, 14),
                UiStyle.dp(ctx, 16), UiStyle.dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiStyle.dp(ctx, 10);
        card.setLayoutParams(lp);
        return card;
    }

    /** 图标 + 标题/副题卡。后续开关或箭头由调用方追加，业务回调仍由调用方绑定。 */
    public static LinearLayout iconCard(Context ctx, int icon, String title, String subtitle) {
        return iconCard(ctx, icon, title, subtitle, UiStyle.BRAND_50);
    }

    /** 允许沿用页面既有语义色图标底，不新增视觉体系。 */
    public static LinearLayout iconCard(Context ctx, int icon, String title, String subtitle,
                                        int iconBackground) {
        LinearLayout card = card(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(UiStyle.iconTile(ctx, icon, UiStyle.BRAND_TEXT, iconBackground,
                        40, 20, 12),
                new LinearLayout.LayoutParams(UiStyle.dp(ctx, 40), UiStyle.dp(ctx, 40)));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiStyle.dp(ctx, 12), 0, UiStyle.dp(ctx, 8), 0);
        col.addView(text(ctx, title, 15f, UiStyle.INK, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = caption(ctx, subtitle, UiStyle.INK2);
            sub.setPadding(0, UiStyle.dp(ctx, 3), 0, 0);
            sub.setTag("sub");
            col.addView(sub);
        }
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    /** 顶栏/卡片内紧凑主按钮：44dp 热区，禁用态不可点击且降低层级。 */
    public static TextView compactPrimaryButton(Context ctx, String label, Runnable onClick) {
        TextView button = UiStyle.primaryBtn(ctx, label, onClick);
        button.setTextSize(TEXT_LABEL);
        button.setMinHeight(UiStyle.dp(ctx, 44));
        button.setPadding(UiStyle.dp(ctx, 16), 0, UiStyle.dp(ctx, 16), 0);
        return button;
    }

    /** 小号次按钮：视觉紧凑但高度不低于 44dp，避免窄屏误触。 */
    public static TextView compactSecondaryButton(Context ctx, String label, Runnable onClick) {
        TextView button = UiStyle.secondaryBtn(ctx, label, onClick);
        button.setTextSize(12.5f);
        button.setMinHeight(UiStyle.dp(ctx, 44));
        button.setPadding(UiStyle.dp(ctx, 10), 0, UiStyle.dp(ctx, 10), 0);
        return button;
    }

    /** 供自定义按钮复用的禁用文字色；正常色不变。 */
    public static ColorStateList enabledTextColors(int normal) {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{UiStyle.INK3, normal});
    }
}
