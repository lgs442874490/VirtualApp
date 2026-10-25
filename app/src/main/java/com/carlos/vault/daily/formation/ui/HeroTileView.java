package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.formation.FormationIcons;
import com.carlos.vault.daily.formation.FormationRules;

/**
 * 英雄头像块（T5，1:1 网页版 `.hero-selection-tile` / 编队板 hero 卡）。
 *
 * <p>结构（自上而下）：
 * <ul>
 *   <li>头像 64dp（{@link FormationIcons#avatar}，缺失走职业色+首字占位）+ 左上职业角标（盾/矛/弓）；</li>
 *   <li>名称（12sp/700，单行省略）；</li>
 *   <li>`{level}级` + 五星（★ 实心数 = starDisplay，上限 5）；</li>
 *   <li>状态行：`已指派`（其它编队已用且未选中）/`未归队`（available=false）；</li>
 *   <li>队长角标（★，可选）。</li>
 * </ul>
 *
 * <p>选中态 = 2dp 主色描边 + 主色浅底；普通态 = 1dp LINE 描边。点击回调原样交给调用方，
 * 由 {@link HeroPickerDialog} 做"同职业唯一 / ≤3"业务判定（本类只画不判）。
 *
 * <p>纯 Android View（无 XML）；颜色/圆角统一走 {@link UiStyle}。
 */
public final class HeroTileView extends FrameLayout {

    /** 点击回调（每块自带，避免调用方按下标反查）。 */
    public interface OnTileClick {
        void onClick(FormationStore.Hero hero);
    }

    private final Activity act;
    private final LinearLayout card;

    public HeroTileView(Activity act) {
        super(act);
        this.act = act;
        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(6), dp(8), dp(6), dp(8));
        addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * 绑定数据并重建（每次全量重画，避免复用残留）。
     *
     * @param hero           目录英雄
     * @param selected       是否在当前编队的已选列表
     * @param assignedElse   是否已被其它编队占用（未选中时才显示"已指派"）
     * @param captain        是否当前队长
     * @param clickable      false=只读（容量未就绪/离线），点击无响应
     * @param onClick        点击回调（可空）
     */
    public void bind(FormationStore.Hero hero, boolean selected, boolean assignedElse,
                     boolean captain, boolean clickable, final OnTileClick onClick) {
        card.removeAllViews();
        if (hero == null) return;
        int prof = FormationRules.professionOf(hero);
        boolean available = hero.available;
        boolean warn = !available;

        setBackground(selected
                ? UiStyle.roundRectStroke(act, UiStyle.BRAND_50, 14, UiStyle.BRAND)
                : UiStyle.roundRectStroke(act, UiStyle.CARD, 14, UiStyle.LINE));
        setPadding(dp(2), dp(2), dp(2), dp(2));

        // 头像 + 职业角标
        FrameLayout avatarBox = new FrameLayout(act);
        ImageView avatar = new ImageView(act);
        android.graphics.drawable.Drawable d = FormationIcons.avatar(act, hero.id);
        if (d == null) d = FormationIcons.placeholder(act, professionLabel(prof));
        avatar.setImageDrawable(d);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarBox.addView(avatar, new FrameLayout.LayoutParams(dp(64), dp(64)));
        TextView profTag = new TextView(act);
        profTag.setText(cornerLabel(prof));
        profTag.setTextSize(9);
        profTag.setTypeface(Typeface.DEFAULT_BOLD);
        profTag.setTextColor(UiStyle.WHITE);
        profTag.setGravity(Gravity.CENTER);
        profTag.setBackground(UiStyle.roundRectDp(act, professionColor(prof), 6));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(18), dp(16));
        plp.gravity = Gravity.LEFT | Gravity.TOP;
        plp.leftMargin = dp(2);
        plp.topMargin = dp(2);
        avatarBox.addView(profTag, plp);
        if (captain) {
            TextView cap = new TextView(act);
            cap.setText("★");
            cap.setTextSize(11);
            cap.setTextColor(UiStyle.WARN_INK);
            cap.setBackground(UiStyle.roundRectDp(act, UiStyle.WARN_BG, 8));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(18), dp(16));
            clp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
            clp.rightMargin = dp(2);
            clp.bottomMargin = dp(2);
            avatarBox.addView(cap, clp);
        }
        card.addView(avatarBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        // 名称
        TextView name = new TextView(act);
        String n = hero.name == null || hero.name.isEmpty() ? ("英雄" + hero.id) : hero.name;
        name.setText(n);
        name.setTextSize(11.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        card.addView(name, nlp);

        // 等级 + 星
        TextView lv = new TextView(act);
        lv.setText((hero.level > 0 ? hero.level + "级" : "—")
                + (hero.generation > 0 ? "  S" + hero.generation : ""));
        lv.setTextSize(10);
        lv.setTextColor(UiStyle.INK2);
        lv.setGravity(Gravity.CENTER);
        card.addView(lv);

        TextView star = new TextView(act);
        int stars = Math.max(0, Math.min(5, hero.starDisplay));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < stars ? '★' : '☆');
        star.setText(sb.toString());
        star.setTextSize(9.5f);
        star.setTextColor(stars > 0 ? UiStyle.WARN_SOLID : UiStyle.INK3);
        star.setGravity(Gravity.CENTER);
        card.addView(star);

        // 状态标签
        String tag = null;
        int tagType = 0;
        if (warn) {
            tag = "未归队";
            tagType = 1;
        } else if (!selected && assignedElse) {
            tag = "已指派";
            tagType = 2;
        }
        if (tag != null) {
            TextView tagView = new TextView(act);
            tagView.setText(tag);
            tagView.setTextSize(9);
            tagView.setTypeface(Typeface.DEFAULT_BOLD);
            tagView.setTextColor(tagType == 1 ? UiStyle.WARN_INK : UiStyle.BRAND_TEXT);
            tagView.setBackground(UiStyle.roundRectDp(act,
                    tagType == 1 ? UiStyle.WARN_BG : UiStyle.BRAND_50, 999));
            tagView.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(3);
            card.addView(tagView, tlp);
        }

        final boolean canClick = clickable;
        setOnClickListener(clickable && onClick != null
                ? v -> onClick.onClick(hero) : null);
        setAlpha(canClick || selected || !warn ? 1f : 0.72f);
    }

    /** 职业角标：盾/矛/弓（未知显示 英）。 */
    public static String cornerLabel(int profession) {
        switch (profession) {
            case 1:
                return "盾";
            case 2:
                return "矛";
            case 3:
                return "弓";
            default:
                return "英";
        }
    }

    /** 职业全称：盾兵/矛兵/弓兵（未知 英雄）。 */
    public static String professionLabel(int profession) {
        switch (profession) {
            case 1:
                return "盾兵";
            case 2:
                return "矛兵";
            case 3:
                return "弓兵";
            default:
                return "英雄";
        }
    }

    /** 职业色：盾 #64748B / 矛 #0EA5E9 / 弓 #F59E0B / 未知灰。 */
    public static int professionColor(int profession) {
        switch (profession) {
            case 1:
                return 0xFF64748B;
            case 2:
                return 0xFF0EA5E9;
            case 3:
                return 0xFFF59E0B;
            default:
                return UiStyle.INK3;
        }
    }

    private int dp(int v) {
        return UiStyle.dp(act, v);
    }
}
