package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationIcons;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.FormationRules;
import com.carlos.vault.wjdr.asset.HeroCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队板（1:1 网页版 {@code DailyFormationBoard}，契约 §4.2 —— Host 签名冻结，T4 提供、T5 复用）。
 *
 * <p>结构自上而下（尺寸取自 {@code design/ui-ref-编队-20261025/README.md} §2.1）：
 * <ol>
 *   <li>编队 tab 卡条（80×56 圆角 12，图标/名称/badge）；</li>
 *   <li>统计行（当前兵力/上限 + 编队名）；</li>
 *   <li>战争增益行（容量 32×32 / 宠物 32×32 / 编队设置 30×30）；</li>
 *   <li>3 英雄格（三卡等宽铺满，约 140×156 圆角 16：头像/职业角标/代数 S/等级/五星/队长/×，
 *       空位=职业占位）；</li>
 *   <li>banner（tone 0 不显示 / 1 info / 2 warn）；</li>
 *   <li>3 兵种行（数量或比例 + 滑杆 ±，只读时无控件）；</li>
 *   <li>士兵比例 chips；</li>
 *   <li>动作区（全部撤回/平均配置/比例分配 + 主按钮 hint/处理中…）。</li>
 * </ol>
 *
 * <p>本类只负责渲染与转发交互，不写数据；所有状态由 {@link Host} 持有并在 {@code render()}
 * 前更新。英雄等级/星级等展示数据优先来自 {@link #installCatalog(FormationCatalogStore)}
 * 装入的目录，缺目录时回落到 {@link HeroCatalog} 静态英雄表（仅名称/代际/职业）。
 */
public final class FormationBoardView {

    // ==================== 契约 §4.2 冻结接口 ====================

    /** 编队板宿主（T4 管理器/编辑器与 T5 选择器共用）。 */
    public interface Host {
        /** 唯一标识（滚动位置/状态用）。 */
        String boardKey();

        /** tab 卡条数据。 */
        List<BoardTab> tabs();

        /** 当前选中 tab key。 */
        String selectedTab();

        /** 当前编队；null=AUTOMATIC。 */
        FormationModel formation();

        /** true=自动编队板。 */
        boolean automatic();

        /** 当前容量（上限）。 */
        long capacity();

        /** [3H,2H,1H,0H]。 */
        long[] capacityLimits();

        /** banner 文案（tone=0 时不显示）。 */
        String bannerText();

        /** banner 语气：0 ok / 1 info / 2 warn。 */
        int bannerTone();

        /** 在线 + configured。 */
        boolean editable();

        /** 编辑器：英雄格可交互。 */
        boolean heroesInteractive();

        /** 自动/编辑器：显示次动作。 */
        boolean secondaryActions();

        String primaryLabel();

        String primaryHint();

        boolean primaryEnabled();

        /** 自动比例（编辑/只读展示）。 */
        int[] automaticRatios();

        void onTabSelected(String key);

        void onHeroSlot(int professionType);

        void onHeroLongPress(long heroId);

        void onCaptainToggle(long heroId);

        /** 手动数量模式。 */
        void onTroopChanged(String key, long value);

        /** 比例模式。 */
        void onRatioChanged(String key, int pct);

        /** clear|average|ratio。 */
        void onSecondaryAction(String action);

        void onPrimary();

        void onCapacityDetail();

        void onPetCapacity();

        void onOpenSettings();
    }

    /** tab 卡片数据（T5 按 4 参构造）。 */
    public static final class BoardTab {
        public final String key;
        public final String label;
        public final String badge;
        public final long iconHeroId;

        public BoardTab(String key, String label, String badge, long iconHeroId) {
            this.key = key == null ? "" : key;
            this.label = label == null ? "" : label;
            this.badge = badge == null ? "" : badge;
            this.iconHeroId = iconHeroId;
        }

        public BoardTab(String key, String label, String badge) {
            this(key, label, badge, 0L);
        }
    }

    /** 兵种 key（与 Host.onTroopChanged/onRatioChanged 约定一致）。 */
    public static final String KEY_INFANTRY = "infantry";
    public static final String KEY_LANCER = "lancer";
    public static final String KEY_MARKSMAN = "marksman";

    // ==================== 目录（T4/T5 可装，缺省回落静态英雄表） ====================

    private static volatile FormationCatalogStore heroCatalog = null;

    /** 装入当前角色英雄目录（编队板展示等级/星级/归队态用；可空）。 */
    public static void installCatalog(FormationCatalogStore catalog) {
        heroCatalog = catalog;
    }

    /** tab 卡条滚动位置（boardKey → scrollX）。 */
    private static final Map<String, Integer> TAB_SCROLL = new HashMap<String, Integer>();

    // ==================== 实例 ====================

    private final Activity act;
    private final Host host;
    private final LinearLayout root;

    public FormationBoardView(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.root = new LinearLayout(act);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setBackgroundColor(ManagerUi.PAGE);
        this.root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        render();
    }

    /** 编队板根视图（直接 addView 到页面/弹窗）。 */
    public View view() {
        return root;
    }

    /** 依 host 当前状态重建（主线程调用）。 */
    public void render() {
        root.removeAllViews();
        if (host == null) return;
        addTabs();
        addStats();
        addBuffs();
        addHeroes();
        addBanner();
        addTroops();
        addRatios();
        addActions();
    }

    // ==================== 数据读取（全部容错） ====================

    private boolean automatic() {
        try {
            return host.automatic();
        } catch (Throwable t) {
            return false;
        }
    }

    private FormationModel formation() {
        try {
            return host.formation();
        } catch (Throwable t) {
            return null;
        }
    }

    private long capacity() {
        try {
            return Math.max(0, host.capacity());
        } catch (Throwable t) {
            return 0;
        }
    }

    private long[] limits() {
        try {
            long[] l = host.capacityLimits();
            return l == null || l.length < 4 ? new long[]{0, 0, 0, 0} : l;
        } catch (Throwable t) {
            return new long[]{0, 0, 0, 0};
        }
    }

    private int[] automaticRatios() {
        try {
            int[] r = host.automaticRatios();
            if (r == null || r.length < 3) return new int[]{50, 20, 30};
            return new int[]{Math.max(0, r[0]), Math.max(0, r[1]), Math.max(0, r[2])};
        } catch (Throwable t) {
            return new int[]{50, 20, 30};
        }
    }

    private boolean editable() {
        try {
            return host.editable();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean heroesInteractive() {
        try {
            return host.heroesInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean secondaryActions() {
        try {
            return host.secondaryActions();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前展示的三兵种数量（AUTO=按容量×比例；比例模式=按比例重算；否则模型数量）。 */
    private long[] troopCounts() {
        long cap = capacity();
        if (automatic()) {
            int[] r = automaticRatios();
            return FormationRules.allocateByRatio(cap, r[0], r[1], r[2]);
        }
        FormationModel f = formation();
        if (f == null) return new long[]{0, 0, 0};
        if (f.isPercentage() && f.ratioSum() > 0) {
            long base = cap > 0 ? cap : f.soldiersTotal();
            return FormationRules.allocateByRatio(base, f.infantryRatio,
                    f.lancerRatio, f.marksmanRatio);
        }
        return new long[]{Math.max(0, f.infantry), Math.max(0, f.lancer),
                Math.max(0, f.marksman)};
    }

    /** 当前展示的三兵种比例（AUTO=自动比例；比例模式=模型比例；数量模式=按数量反算）。 */
    private int[] troopRatios() {
        if (automatic()) return automaticRatios();
        FormationModel f = formation();
        if (f == null) return new int[]{0, 0, 0};
        if (f.isPercentage() && f.ratioSum() > 0) {
            return new int[]{Math.max(0, f.infantryRatio), Math.max(0, f.lancerRatio),
                    Math.max(0, f.marksmanRatio)};
        }
        long[] c = troopCounts();
        long total = c[0] + c[1] + c[2];
        if (total <= 0) {
            return new int[]{Math.max(0, f.infantryRatio), Math.max(0, f.lancerRatio),
                    Math.max(0, f.marksmanRatio)};
        }
        int inf = (int) Math.round(c[0] * 100.0 / total);
        int lanc = (int) Math.round(c[1] * 100.0 / total);
        int mark = Math.max(0, 100 - inf - lanc);
        inf = Math.min(100, Math.max(0, inf));
        lanc = Math.min(100, Math.max(0, lanc));
        return new int[]{inf, lanc, mark};
    }

    // ==================== 1. tab 卡条 ====================

    private void addTabs() {
        List<BoardTab> tabs = null;
        try {
            tabs = host.tabs();
        } catch (Throwable ignored) {
        }
        if (tabs == null) tabs = new ArrayList<BoardTab>();
        String selected = null;
        try {
            selected = host.selectedTab();
        } catch (Throwable ignored) {
        }
        final String selectedKey = selected;

        HorizontalScrollView sc = new HorizontalScrollView(act);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(ManagerUi.dp(act, 6), ManagerUi.dp(act, 5),
                ManagerUi.dp(act, 6), ManagerUi.dp(act, 7));
        for (final BoardTab tab : tabs) {
            if (tab == null) continue;
            boolean active = selectedKey != null && selectedKey.equals(tab.key);
            FrameLayout card = new FrameLayout(act);
            card.setBackground(active
                    ? ManagerUi.strokeWidth(act, ManagerUi.CARD, 12, 2, ManagerUi.PRIMARY)
                    : ManagerUi.stroke(act, ManagerUi.CARD, 12, ManagerUi.LINE_SOFT));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ManagerUi.dp(act, 80), ManagerUi.dp(act, 56));
            clp.rightMargin = ManagerUi.dp(act, 8);
            card.setLayoutParams(clp);

            // badge（左上）
            if (!tab.badge.isEmpty()) {
                TextView badge = ManagerUi.text(act, tab.badge, 10.5f,
                        active ? Color.WHITE : ManagerUi.TEXT3, true);
                badge.setGravity(Gravity.CENTER);
                badge.setSingleLine(true);
                badge.setEllipsize(android.text.TextUtils.TruncateAt.END);
                badge.setPadding(ManagerUi.dp(act, 5), 0, ManagerUi.dp(act, 5), 0);
                badge.setBackground(ManagerUi.round(act,
                        active ? ManagerUi.PRIMARY : 0xFFEEF0F4, 8));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ManagerUi.dp(act, 36), ManagerUi.dp(act, 20), Gravity.TOP | Gravity.START);
                blp.setMargins(ManagerUi.dp(act, 6), ManagerUi.dp(act, 5), 0, 0);
                card.addView(badge, blp);
            }
            // 图标（右上）：游戏编队英雄头像 / 其它类型徽标
            FrameLayout icon = new FrameLayout(act);
            icon.setLayoutParams(new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 25), ManagerUi.dp(act, 23), Gravity.TOP | Gravity.END));
            ((FrameLayout.LayoutParams) icon.getLayoutParams()).setMargins(0,
                    ManagerUi.dp(act, 5), ManagerUi.dp(act, 6), 0);
            icon.setClipToOutline(true);
            icon.setBackground(ManagerUi.round(act,
                    active ? ManagerUi.PRIMARY_50 : 0xFFF1F5F9, 7));
            if (tab.iconHeroId > 0) {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Drawable d = heroAvatar(tab.iconHeroId);
                if (d != null) iv.setImageDrawable(d);
                icon.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                String glyph = tab.label.isEmpty() ? "编" : tab.label.substring(0, 1);
                TextView gl = ManagerUi.text(act, glyph, 10, active
                        ? ManagerUi.PRIMARY_DEEP : ManagerUi.TEXT3, true);
                gl.setGravity(Gravity.CENTER);
                icon.addView(gl, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            card.addView(icon);
            // 名称（左下）
            TextView name = ManagerUi.text(act, tab.label, 11.5f,
                    active ? ManagerUi.PRIMARY_DEEP : ManagerUi.TEXT2, active);
            ManagerUi.ellipsize(name);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 68), ManagerUi.dp(act, 16),
                    Gravity.BOTTOM | Gravity.START);
            nlp.setMargins(ManagerUi.dp(act, 6), 0, 0, ManagerUi.dp(act, 6));
            card.addView(name, nlp);

            card.setOnClickListener(v -> {
                if (selectedKey != null && selectedKey.equals(tab.key)) return;
                try {
                    host.onTabSelected(tab.key);
                } catch (Throwable ignored) {
                }
            });
            UiStyle.press(card);
            strip.addView(card);
        }
        sc.addView(strip);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 68)));
        // 恢复横向滚动位置
        String key = boardKey();
        if (key != null) {
            final Integer x = TAB_SCROLL.get(key);
            if (x != null) {
                sc.post(() -> sc.scrollTo(x, 0));
            }
            try {
                sc.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
                    TAB_SCROLL.put(key, scrollX);
                });
            } catch (Throwable ignored) {
                // API<23 无此监听：仅失去滚动位置记忆，不影响功能
            }
        }
    }

    private String boardKey() {
        try {
            return host.boardKey();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 2. 统计行 ====================

    private void addStats() {
        long cap = capacity();
        long current;
        String name;
        if (automatic()) {
            current = cap;
            name = "全局自动编队";
        } else {
            FormationModel f = formation();
            long[] c = troopCounts();
            current = c[0] + c[1] + c[2];
            name = f == null ? "" : (f.name == null || f.name.isEmpty() ? "未命名编队" : f.name);
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ManagerUi.dp(act, 12), ManagerUi.dp(act, 5),
                ManagerUi.dp(act, 12), ManagerUi.dp(act, 6));

        LinearLayout l1 = ManagerUi.row(act);
        // 盾徽标 + 当前/上限
        TextView shield = ManagerUi.text(act, "盾", 10, Color.WHITE, true);
        shield.setGravity(Gravity.CENTER);
        shield.setBackground(ManagerUi.round(act, ManagerUi.SLATE, 6));
        l1.addView(shield, ManagerUi.lp(ManagerUi.dp(act, 18), ManagerUi.dp(act, 18)));
        boolean over = cap > 0 && current > cap;
        TextView troop = ManagerUi.text(act, FormationRules.fmt(current), 13,
                over ? ManagerUi.WARN_INK : ManagerUi.TEXT, true);
        troop.setPadding(ManagerUi.dp(act, 6), 0, 0, 0);
        l1.addView(troop);
        if (cap > 0) {
            TextView capT = ManagerUi.text(act, " / " + FormationRules.fmt(cap), 12,
                    ManagerUi.TEXT4, false);
            l1.addView(capT);
        }
        if (over) {
            TextView warn = ManagerUi.text(act, " ⚠", 12, ManagerUi.WARN_INK, false);
            l1.addView(warn);
        }
        box.addView(l1);

        TextView nameT = ManagerUi.text(act, name, 15, ManagerUi.TEXT, true);
        ManagerUi.ellipsize(nameT);
        nameT.setPadding(0, ManagerUi.dp(act, 4), 0, 0);
        box.addView(nameT);

        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 68)));
    }

    // ==================== 3. 战争增益行 ====================

    private void addBuffs() {
        LinearLayout row = ManagerUi.row(act);
        row.setPadding(ManagerUi.dp(act, 12), 0, ManagerUi.dp(act, 12), 0);
        TextView label = ManagerUi.text(act, "战争增益", 12.5f, ManagerUi.TEXT3, true);
        row.addView(label, ManagerUi.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 2, 0, act));

        // 出征容量详情（32×32 · #6366F1 底白图标）
        FrameLayout capIcon = ManagerUi.iconButton(act, 0xFF6366F1, null,
                32, () -> callHost(() -> host.onCapacityDetail()));
        TextView capGlyph = ManagerUi.text(act, "兵", 11, Color.WHITE, true);
        capGlyph.setGravity(Gravity.CENTER);
        capIcon.addView(capGlyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        capIcon.setContentDescription("出征容量详情");
        LinearLayout.LayoutParams capLp = ManagerUi.lp(ManagerUi.dp(act, 32), ManagerUi.dp(act, 32));
        capLp.leftMargin = ManagerUi.dp(act, 8);
        row.addView(capIcon, capLp);

        // 宠物技能增益（32×32）
        FrameLayout petIcon = ManagerUi.iconButton(act, 0xFFF59E0B, null,
                32, () -> callHost(() -> host.onPetCapacity()));
        TextView petGlyph = ManagerUi.text(act, "宠", 11, Color.WHITE, true);
        petGlyph.setGravity(Gravity.CENTER);
        petIcon.addView(petGlyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        petIcon.setContentDescription("宠物技能增益");
        LinearLayout.LayoutParams petLp = ManagerUi.lp(ManagerUi.dp(act, 32), ManagerUi.dp(act, 32));
        petLp.leftMargin = ManagerUi.dp(act, 8);
        row.addView(petIcon, petLp);

        row.addView(new View(act), ManagerUi.lp(0, 1, 1));

        // 编队设置（自动/我的 非游戏编队显示）
        FormationModel f = formation();
        boolean showSettings = automatic() || (f != null && !f.isGame());
        if (showSettings) {
            FrameLayout set = ManagerUi.iconButton(act, 0xFFF1F5F9, null, 30,
                    () -> callHost(() -> host.onOpenSettings()));
            TextView plus = ManagerUi.text(act, "＋", 14, ManagerUi.TEXT3, true);
            plus.setGravity(Gravity.CENTER);
            set.addView(plus, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            set.setContentDescription("打开编队设置");
            row.addView(set);
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 41)));
    }

    // ==================== 4. 英雄格 ====================

    private void addHeroes() {
        boolean auto = automatic();
        FormationModel f = formation();
        List<Long> ordered = new ArrayList<Long>();
        Long captain = null;
        if (!auto && f != null) {
            captain = f.effectiveCaptainId();
            if (captain != null && f.hasHero(captain)) ordered.add(captain);
            for (Long h : f.distinctHeroIds()) {
                if (h == null || h <= 0) continue;
                if (captain != null && h.longValue() == captain.longValue()) continue;
                ordered.add(h);
            }
            // 超过 3 人只显示前 3（数据异常兜底）
            while (ordered.size() > FormationModel.HERO_MAX) {
                ordered.remove(ordered.size() - 1);
            }
        }
        // 职业槽：已选英雄用其职业；空位按 盾→矛→弓 补齐未占用职业
        int[] slotProf = new int[]{0, 0, 0};
        boolean[] used = new boolean[4];
        for (int i = 0; i < ordered.size() && i < 3; i++) {
            int prof = professionOfHero(ordered.get(i));
            if (prof == 0 || used[prof]) {
                prof = firstFree(used);
            }
            slotProf[i] = prof;
            if (prof > 0) used[prof] = true;
        }
        for (int i = ordered.size(); i < 3; i++) {
            int prof = firstFree(used);
            slotProf[i] = prof;
            if (prof > 0) used[prof] = true;
        }

        LinearLayout row = ManagerUi.row(act);
        row.setGravity(Gravity.TOP);
        // 三卡等宽铺满：左右各 10dp、卡间距 10dp；高度 156dp（原 54dp 边距 + 18dp 间距 + 180dp
        // 导致每张仅约 105dp 宽，头像过窄）。463dp 卡宽下每张约 (463-20-20)/3 ≈ 141dp。
        int pad = ManagerUi.dp(act, 10);
        row.setPadding(pad, 0, pad, 0);
        final int cardH = ManagerUi.dp(act, 156);
        for (int i = 0; i < 3; i++) {
            long heroId = i < ordered.size() ? ordered.get(i) : 0L;
            boolean isCaptain = heroId > 0 && captain != null
                    && heroId == captain.longValue();
            View card = heroCard(heroId, slotProf[i], isCaptain);
            LinearLayout.LayoutParams clp = ManagerUi.lp(0, cardH, 1f);
            if (i < 2) clp.rightMargin = ManagerUi.dp(act, 10);
            row.addView(card, clp);
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH));
    }

    private static int firstFree(boolean[] used) {
        for (int p = 1; p <= 3; p++) {
            if (p < used.length && !used[p]) return p;
        }
        return 0;
    }

    private View heroCard(final long heroId, final int profession, final boolean isCaptain) {
        final boolean interactive = heroesInteractive();
        FrameLayout card = new FrameLayout(act);
        // 底：白底 + 1px 描边（圆角 16）
        card.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 16, ManagerUi.LINE_SOFT));
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        ManagerUi.dp(act, 16));
            }
        });

        if (heroId <= 0) {
            // ---- 空位 ----
            TextView prof = ManagerUi.text(act, ManagerUi.professionChar(profession), 10.5f,
                    Color.WHITE, true);
            prof.setGravity(Gravity.CENTER);
            prof.setBackground(ManagerUi.round(act, ManagerUi.SLATE, 8));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 26), ManagerUi.dp(act, 26), Gravity.TOP | Gravity.START);
            plp.setMargins(ManagerUi.dp(act, 5), ManagerUi.dp(act, 5), 0, 0);
            card.addView(prof, plp);

            LinearLayout center = new LinearLayout(act);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            clp.topMargin = ManagerUi.dp(act, 12);
            // 圆形占位
            FrameLayout circle = new FrameLayout(act);
            circle.setBackground(ManagerUi.circle(act, ManagerUi.professionTint(profession)));
            ImageView ic = new ImageView(act);
            Drawable ph = null;
            try {
                ph = FormationIcons.placeholder(act, ManagerUi.professionLabel(profession));
            } catch (Throwable ignored) {
            }
            if (ph != null) ic.setImageDrawable(ph);
            circle.addView(ic, new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 30), ManagerUi.dp(act, 30), Gravity.CENTER));
            center.addView(circle, ManagerUi.lp(ManagerUi.dp(act, 48), ManagerUi.dp(act, 48)));
            TextView add = ManagerUi.text(act, "＋", 12, ManagerUi.professionAccent(profession), true);
            add.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams addLp = ManagerUi.lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ManagerUi.dp(act, 14));
            addLp.topMargin = ManagerUi.dp(act, 6);
            center.addView(add, addLp);
            TextView strong = ManagerUi.text(act,
                    ManagerUi.professionHeroLabel(profession), 12, ManagerUi.TEXT2, true);
            strong.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = ManagerUi.lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = ManagerUi.dp(act, 3);
            center.addView(strong, slp);
            TextView small = ManagerUi.text(act, "未上阵", 10.5f, ManagerUi.TEXT4, false);
            small.setGravity(Gravity.CENTER);
            center.addView(small);
            card.addView(center, clp);

            card.setContentDescription("添加" + ManagerUi.professionHeroLabel(profession));
            if (interactive) {
                card.setOnClickListener(v -> callHost(() -> host.onHeroSlot(profession)));
                UiStyle.press(card);
            }
            return card;
        }

        // ---- 有英雄：头像 + 底部压暗 + 角标/代数/等级/星级/队长/× ----
        FormationStore.Hero hero = heroOf(heroId);
        int prof = profession > 0 ? profession : professionOfHero(heroId);

        ImageView avatar = new ImageView(act);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable d = heroAvatar(heroId);
        if (d != null) {
            avatar.setImageDrawable(d);
        } else {
            avatar.setBackground(ManagerUi.round(act, ManagerUi.professionTint(prof), 16));
        }
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        card.addView(avatar, alp);

        // 底部压暗层（下 75dp）
        View scrim = new View(act);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE60B1220, 0x590B1220, 0x000B1220});
        scrim.setBackground(g);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 95),
                Gravity.BOTTOM);
        card.addView(scrim, slp);

        // 职业角标（左上 · #64748b 底白字）
        TextView profT = ManagerUi.text(act, ManagerUi.professionChar(prof), 10.5f,
                Color.WHITE, true);
        profT.setGravity(Gravity.CENTER);
        profT.setBackground(ManagerUi.round(act, ManagerUi.SLATE, 8));
        FrameLayout.LayoutParams ptlp = new FrameLayout.LayoutParams(
                ManagerUi.dp(act, 24), ManagerUi.dp(act, 24), Gravity.TOP | Gravity.START);
        ptlp.setMargins(ManagerUi.dp(act, 5), ManagerUi.dp(act, 5), 0, 0);
        card.addView(profT, ptlp);

        // 代数 S{n}/基础（顶部，职业角标右侧）
        int generation = hero == null ? HeroCatalog.generation(heroId)
                : (hero.generation > 0 ? hero.generation : HeroCatalog.generation(heroId));
        String tierText = generation > 0 ? "S" + generation : "基础";
        TextView tier = ManagerUi.text(act, tierText, 10.5f, 0xFFFDE68A, true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ManagerUi.dp(act, 20),
                Gravity.TOP | Gravity.START);
        tlp.setMargins(ManagerUi.dp(act, 33), ManagerUi.dp(act, 7), 0, 0);
        card.addView(tier, tlp);

        // × 移除（右上，仅英雄可交互时）
        if (interactive) {
            TextView remove = ManagerUi.text(act, "×", 13, Color.WHITE, true);
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(ManagerUi.circle(act, 0x66000000));
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 22), ManagerUi.dp(act, 22), Gravity.TOP | Gravity.END);
            rlp.setMargins(0, ManagerUi.dp(act, 5), ManagerUi.dp(act, 5), 0);
            card.addView(remove, rlp);
            remove.setOnClickListener(v -> callHost(() -> {
                FormationModel fm = formation();
                if (fm != null) {
                    fm.heroIds.remove(Long.valueOf(heroId));
                    if (fm.captainHeroId != null
                            && fm.captainHeroId.longValue() == heroId) {
                        fm.captainHeroId = null;
                    }
                    host.onHeroSlot(0);   // 通知宿主刷新/裁剪
                }
            }));
        }

        // 队长标记（左下，24×24 圆形星）
        if (isCaptain) {
            FrameLayout cap = new FrameLayout(act);
            cap.setBackground(ManagerUi.circle(act, 0xFFFBBF24));
            TextView star = ManagerUi.text(act, "★", 11, Color.WHITE, true);
            star.setGravity(Gravity.CENTER);
            cap.addView(star, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(act, 24), ManagerUi.dp(act, 24), Gravity.BOTTOM | Gravity.START);
            clp.setMargins(ManagerUi.dp(act, 6), 0, 0, ManagerUi.dp(act, 44));
            card.addView(cap, clp);
            if (interactive) {
                cap.setContentDescription("取消" + heroName(heroId) + "队长");
                cap.setOnClickListener(v -> callHost(() -> host.onCaptainToggle(heroId)));
            }
        }

        // 等级（左下）
        int level = hero == null ? 0 : hero.level;
        TextView lv = ManagerUi.text(act, level > 0 ? level + "级" : "",
                12, Color.WHITE, true);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ManagerUi.dp(act, 20),
                Gravity.BOTTOM | Gravity.START);
        llp.setMargins(ManagerUi.dp(act, 6), 0, 0, ManagerUi.dp(act, 22));
        card.addView(lv, llp);

        // 五星（底部 · 亮/暗）
        int stars = hero == null ? 0 : Math.min(5, Math.max(0,
                hero.starDisplay > 0 ? hero.starDisplay : hero.star));
        LinearLayout starRow = ManagerUi.row(act);
        starRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 5; i++) {
            TextView s = ManagerUi.text(act, "★", 10.5f,
                    i < stars ? 0xFFFBBF24 : 0x66FFFFFF, false);
            s.setPadding(0, 0, ManagerUi.dp(act, 1), 0);
            starRow.addView(s);
        }
        FrameLayout.LayoutParams stlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ManagerUi.dp(act, 14),
                Gravity.BOTTOM | Gravity.START);
        stlp.setMargins(ManagerUi.dp(act, 5), 0, 0, ManagerUi.dp(act, 5));
        card.addView(starRow, stlp);

        // 圆角描边覆盖层（头像覆盖了背景描边，这里补回 1px 边框；不拦截点击）
        View border = new View(act);
        border.setBackground(ManagerUi.stroke(act, Color.TRANSPARENT, 16, ManagerUi.LINE_SOFT));
        border.setClickable(false);
        border.setFocusable(false);
        card.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.setContentDescription(heroName(heroId) + "，" + level + "级");
        if (interactive) {
            card.setOnClickListener(v -> callHost(() -> host.onHeroSlot(prof)));
            card.setOnLongClickListener(v -> {
                callHost(() -> host.onHeroLongPress(heroId));
                return true;
            });
            UiStyle.press(card);
        }
        return card;
    }

    // ==================== 5. banner ====================

    private void addBanner() {
        int tone;
        String text;
        try {
            tone = host.bannerTone();
            text = host.bannerText();
        } catch (Throwable t) {
            return;
        }
        if (tone == 0 || text == null || text.trim().isEmpty()) return;
        int bg = tone == 2 ? ManagerUi.WARN_BG : ManagerUi.INFO_BG;
        int ink = tone == 2 ? ManagerUi.WARN_INK : ManagerUi.INFO_INK;
        TextView v = ManagerUi.text(act, text, 11.5f, ink, false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(ManagerUi.dp(act, 8), ManagerUi.dp(act, 5),
                ManagerUi.dp(act, 8), ManagerUi.dp(act, 5));
        v.setBackground(ManagerUi.round(act, bg, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ManagerUi.dp(act, 12);
        lp.rightMargin = ManagerUi.dp(act, 12);
        lp.bottomMargin = ManagerUi.dp(act, 4);
        root.addView(v, lp);
    }

    // ==================== 6. 兵种行 ====================

    private static final String[] TROOP_NAMES = {"盾兵", "矛兵", "弓兵"};
    private static final String[] TROOP_KEYS = {KEY_INFANTRY, KEY_LANCER, KEY_MARKSMAN};
    private static final int[] TROOP_PROFS = {ManagerUi.PROF_SHIELD, ManagerUi.PROF_LANCER,
            ManagerUi.PROF_BOW};

    private void addTroops() {
        long[] counts = troopCounts();
        int[] ratios = troopRatios();
        long cap = capacity();
        boolean showControls = editable();
        boolean ratioMode = automatic()
                || (formation() != null && formation().isPercentage());

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ManagerUi.dp(act, 2), 0, ManagerUi.dp(act, 2), 0);
        for (int i = 0; i < 3; i++) {
            box.addView(troopRow(i, counts[i], ratios[i], cap, showControls, ratioMode));
        }
        int h = showControls ? 272 : 200;
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, h)));
    }

    private View troopRow(final int index, long count, int ratio, long capacity,
                          boolean showControls, final boolean ratioMode) {
        final int prof = TROOP_PROFS[index];
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 16, ManagerUi.LINE_SOFT));
        card.setPadding(ManagerUi.dp(act, 8), ManagerUi.dp(act, 7),
                ManagerUi.dp(act, 10), ManagerUi.dp(act, 7));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ManagerUi.dp(act, showControls ? 82 : 58));
        cardLp.leftMargin = ManagerUi.dp(act, 8);
        cardLp.rightMargin = ManagerUi.dp(act, 8);
        cardLp.bottomMargin = ManagerUi.dp(act, 8);
        card.setLayoutParams(cardLp);

        LinearLayout top = ManagerUi.row(act);
        // 兵种徽章 52×52
        FrameLayout badge = new FrameLayout(act);
        badge.setBackground(ManagerUi.round(act, ManagerUi.professionTint(prof), 12));
        TextView glyph = ManagerUi.text(act, ManagerUi.professionChar(prof), 16,
                ManagerUi.professionAccent(prof), true);
        glyph.setGravity(Gravity.CENTER);
        badge.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        top.addView(badge, ManagerUi.lp(ManagerUi.dp(act, 52), ManagerUi.dp(act, 52)));
        TextView name = ManagerUi.text(act, TROOP_NAMES[index], 14, ManagerUi.TEXT2, true);
        LinearLayout.LayoutParams nlp = ManagerUi.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = ManagerUi.dp(act, 10);
        top.addView(name, nlp);

        TextView cnt = ManagerUi.text(act, FormationRules.fmt(count), 13.5f,
                ManagerUi.TEXT2, true);
        top.addView(cnt);
        if (showControls || automatic()) {
            TextView capT = ManagerUi.text(act, " / " + (capacity > 0
                    ? FormationRules.fmt(capacity) : "-"), 12, ManagerUi.TEXT4, false);
            top.addView(capT);
        }
        card.addView(top);

        if (showControls) {
            LinearLayout controls = ManagerUi.row(act);
            controls.setPadding(ManagerUi.dp(act, 60), ManagerUi.dp(act, 2), 0, 0);
            final int step = ratioMode ? 5 : (int) Math.max(1, capacity / 20);
            final int max = ratioMode ? 100
                    : (int) Math.min(Integer.MAX_VALUE, Math.max(1, capacity));
            final int progress = ratioMode ? Math.max(0, Math.min(100, ratio)) : (int) Math.min(max, count);

            TextView minus = stepButton("－", true);
            controls.addView(minus, ManagerUi.lp(ManagerUi.dp(act, 40), ManagerUi.dp(act, 30)));
            SeekBar sb = ManagerUi.slider(act, max, progress, null);
            LinearLayout.LayoutParams slp = ManagerUi.lp(0, ManagerUi.dp(act, 30), 1f);
            slp.leftMargin = ManagerUi.dp(act, 4);
            slp.rightMargin = ManagerUi.dp(act, 4);
            controls.addView(sb, slp);
            TextView plus = stepButton("＋", true);
            controls.addView(plus, ManagerUi.lp(ManagerUi.dp(act, 40), ManagerUi.dp(act, 30)));
            card.addView(controls);

            final int cur = progress;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    if (!fromUser) return;
                    dispatch(index, value, ratioMode);
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            minus.setOnClickListener(v -> dispatch(index, Math.max(0, cur - step), ratioMode));
            plus.setOnClickListener(v -> dispatch(index, Math.min(max, cur + step), ratioMode));
        }
        return card;
    }

    private TextView stepButton(String glyph, boolean enabled) {
        TextView t = ManagerUi.text(act, glyph, 14,
                enabled ? ManagerUi.PRIMARY_DEEP : ManagerUi.TEXT4, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 8, ManagerUi.LINE_SOFT));
        return t;
    }

    private void dispatch(int index, int value, boolean ratioMode) {
        final String key = TROOP_KEYS[index];
        if (ratioMode) {
            callHost(() -> host.onRatioChanged(key, Math.max(0, Math.min(100, value))));
        } else {
            callHost(() -> host.onTroopChanged(key, Math.max(0, value)));
        }
    }

    // ==================== 7. 士兵比例 ====================

    private void addRatios() {
        int[] ratios = troopRatios();
        LinearLayout row = ManagerUi.row(act);
        row.setPadding(ManagerUi.dp(act, 14), 0, ManagerUi.dp(act, 14), 0);
        TextView label = ManagerUi.text(act, "士兵比例：", 11.5f, ManagerUi.TEXT3, false);
        row.addView(label, ManagerUi.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, act));
        for (int i = 0; i < 3; i++) {
            TextView chip = ManagerUi.text(act, Math.max(0, ratios[i]) + "%", 11,
                    ManagerUi.professionAccent(TROOP_PROFS[i]), true);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 999, ManagerUi.LINE_SOFT));
            chip.setPadding(ManagerUi.dp(act, 9), ManagerUi.dp(act, 3),
                    ManagerUi.dp(act, 9), ManagerUi.dp(act, 3));
            LinearLayout.LayoutParams lp = ManagerUi.lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ManagerUi.dp(act, 6);
            row.addView(chip, lp);
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 30)));
    }

    // ==================== 8. 动作区 ====================

    private void addActions() {
        LinearLayout row = ManagerUi.row(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ManagerUi.dp(act, 4), ManagerUi.dp(act, 4),
                ManagerUi.dp(act, 4), ManagerUi.dp(act, 4));

        if (secondaryActions()) {
            LinearLayout group = ManagerUi.row(act);
            group.setGravity(Gravity.CENTER_VERTICAL);
            group.setLayoutParams(ManagerUi.lp(ManagerUi.dp(act, 210),
                    ManagerUi.dp(act, 82)));
            group.addView(actionButton("↩", "全部撤回", "clear"));
            group.addView(actionButton("✓", "平均配置", "average"));
            group.addView(actionButton("≡", "比例分配", "ratio"));
            row.addView(group, ManagerUi.lp(ManagerUi.dp(act, 210), ManagerUi.dp(act, 82)));
        }

        String label = "保存编队";
        String hint = "";
        boolean enabled = false;
        try {
            label = host.primaryLabel();
            hint = host.primaryHint();
            enabled = host.primaryEnabled();
        } catch (Throwable ignored) {
        }
        if (label == null || label.isEmpty()) label = "保存编队";
        if (hint == null) hint = "";

        FrameLayout primary = new FrameLayout(act);
        primary.setBackground(enabled
                ? ManagerUi.pressable(act, ManagerUi.PRIMARY, 0xFF4338CA, 16)
                : ManagerUi.round(act, ManagerUi.PRIMARY_300, 16));
        TextView hintT = ManagerUi.text(act, hint, 10.5f,
                enabled ? 0xCCFFFFFF : Color.WHITE, false);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        hlp.setMargins(0, ManagerUi.dp(act, 8), ManagerUi.dp(act, 10), 0);
        primary.addView(hintT, hlp);
        TextView labelT = ManagerUi.text(act, label, 15, Color.WHITE, true);
        labelT.setGravity(Gravity.CENTER);
        primary.addView(labelT, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (enabled) {
            primary.setOnClickListener(v -> callHost(() -> host.onPrimary()));
            UiStyle.press(primary);
        }
        LinearLayout.LayoutParams plp;
        if (secondaryActions()) {
            plp = ManagerUi.lp(ManagerUi.dp(act, 237), ManagerUi.dp(act, 82));
            plp.leftMargin = ManagerUi.dp(act, 4);
        } else {
            // 无次动作（游戏/我的只读）：主按钮占满整行（网页 grid 210px 1fr 的 1fr）
            plp = ManagerUi.lp(0, ManagerUi.dp(act, 82), 1f);
            plp.leftMargin = ManagerUi.dp(act, 8);
            plp.rightMargin = ManagerUi.dp(act, 8);
        }
        row.addView(primary, plp);
        row.addView(new View(act), ManagerUi.lp(0, 1, 1));

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(act, 90)));
    }

    private View actionButton(String glyph, String label, final String action) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 12, ManagerUi.LINE_SOFT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ManagerUi.dp(act, 67), ManagerUi.dp(act, 82));
        lp.rightMargin = ManagerUi.dp(act, 4);
        box.setLayoutParams(lp);

        TextView ic = ManagerUi.text(act, glyph, 17, ManagerUi.PRIMARY_DEEP, true);
        ic.setGravity(Gravity.CENTER);
        box.addView(ic, ManagerUi.lp(ManagerUi.dp(act, 44), ManagerUi.dp(act, 44)));
        TextView tv = ManagerUi.text(act, label, 10.5f, ManagerUi.TEXT3, false);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        box.addView(tv);
        box.setOnClickListener(v -> callHost(() -> host.onSecondaryAction(action)));
        UiStyle.press(box);
        return box;
    }

    // ==================== 英雄数据 ====================

    private Drawable heroAvatar(long heroId) {
        try {
            return FormationIcons.avatar(act, heroId);
        } catch (Throwable t) {
            return null;
        }
    }

    private FormationStore.Hero heroOf(long heroId) {
        FormationCatalogStore cat = heroCatalog;
        if (cat != null) {
            try {
                FormationStore.Hero h = cat.hero(heroId);
                if (h != null) return h;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private int professionOfHero(long heroId) {
        FormationStore.Hero h = heroOf(heroId);
        if (h != null) {
            int p = FormationRules.professionOf(h);
            if (p > 0) return p;
        }
        return HeroCatalog.profession(heroId);
    }

    private String heroName(long heroId) {
        FormationStore.Hero h = heroOf(heroId);
        if (h != null && h.name != null && !h.name.isEmpty()) return h.name;
        try {
            String n = HeroCatalog.name(heroId);
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {
        }
        return "英雄" + heroId;
    }

    private void callHost(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }
}
