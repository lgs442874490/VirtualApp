package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.formation.FormationIcons;
import com.carlos.vault.daily.formation.FormationRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 英雄选择弹窗（T5，1:1 网页版 §1.5「英雄选择」，源码 `DailyRoleFormation*.js :593-969`）。
 *
 * <p>结构：
 * <ol>
 *   <li>头部 `英雄选择` + 关闭（aria 关闭英雄选择）；</li>
 *   <li>3 张职业卡（盾/矛/弓）：已选 → 头像 + 职业 + 代数(`S{n}`/`基础`) + 星级 + `{level}级` + 右上 × 移除；
 *       未选 → 职业字 + `+` + `选择{盾兵英雄}`（点击切到该职业页签）；</li>
 *   <li>排序行：`排序` + `实力`（默认）/`等级`/`稀有度`；</li>
 *   <li>英雄网格（当前职业，3 列，{@link HeroTileView}）：职业角标/等级/五星/`已指派`/`未归队`，
 *       点击=选入该职业（同职业唯一，最多 {@link FormationStore#HERO_MAX}）；</li>
 *   <li>空态 `暂无{盾兵英雄}`；</li>
 *   <li>底部 `查看` + 主按钮 `保存`（有改动）/`完成`（无改动）。</li>
 * </ol>
 *
 * <p>确认回调：`heroIds` 顺序=选择顺序（最多 3）；若原队长不在新列表 → 回落 `heroIds[0]`（无则 null）。
 * 本类只选人，不写编队库/不裁剪兵量（裁剪由调用方按 {@link FormationRules#clampCounts} 处理）。
 */
public final class HeroPickerDialog {

    /** 选择结果回调（主线程）。 */
    public interface OnPicked {
        void onPicked(List<Long> heroIds, Long captainHeroId);
    }

    /** 排序方式（与网页版一致）。 */
    private static final int SORT_POWER = 0;
    private static final int SORT_LEVEL = 1;
    private static final int SORT_GENERATION = 2;
    private static final String[] SORT_LABELS = {"实力", "等级", "稀有度"};
    private static final String[] PROF_LABELS = {"盾兵", "矛兵", "弓兵"};
    private static final String[] PROF_SHORT = {"盾", "矛", "弓"};

    private HeroPickerDialog() {
    }

    /**
     * 自读目录的便捷入口（调用方只需传角色与初始选择）。
     *
     * <p>目录/编队库均从 {@link FormationStore} 读取：英雄选项 = `catalogOf(...).heroOptions()`，
     * 「已指派」集合 = 其它编队已用英雄（不含本编队英雄）。
     */
    public static void show(Activity act, String fpid, long sid, List<Long> initialHeroIds,
                            Long captainHeroId, int initialProfession, OnPicked cb) {
        // 无 uid 信息 → 旧键（uid 空串等价旧行为）
        show(act, fpid, sid, "", initialHeroIds, captainHeroId, initialProfession, cb);
    }

    /**
     * 自读目录的便捷入口（v2，uid 感知；T7 2026-10-25）。
     *
     * <p>uid 非空时优先读 {@code srv:formation:catalog:{fpid}:{sid}:{uid}}，缺失回退旧键；
     * 同 fpid:sid 多角色时避免英雄列表串角色。
     */
    public static void show(Activity act, String fpid, long sid, String uid,
                            List<Long> initialHeroIds, Long captainHeroId, int initialProfession,
                            OnPicked cb) {
        List<FormationStore.Hero> options = new ArrayList<>();
        Set<Long> assigned = new LinkedHashSet<>();
        try {
            options.addAll(FormationStore.catalogOf(act, fpid, sid, uid).heroOptions());
        } catch (Throwable ignored) {
        }
        try {
            List<Long> initial = initialHeroIds == null ? new ArrayList<>() : initialHeroIds;
            for (com.carlos.vault.daily.formation.FormationModel f
                    : FormationStore.formations(act, fpid, sid)) {
                if (f == null || f.heroIds == null) continue;
                boolean same = f.heroIds.size() == initial.size()
                        && f.heroIds.containsAll(initial);
                if (same) continue;   // 本编队自身不算"已指派"
                for (Long h : f.heroIds) {
                    if (h != null && h > 0) assigned.add(h);
                }
            }
        } catch (Throwable ignored) {
        }
        show(act, fpid, sid, initialHeroIds, captainHeroId, initialProfession, options, assigned, cb);
    }

    /**
     * 主入口：给定英雄选项与占用集合，弹出选择器。
     *
     * @param initialHeroIds   初始已选（顺序=上阵顺序，可空）
     * @param captainHeroId    初始队长（可空）
     * @param initialProfession 初始页签职业（1 盾 / 2 矛 / 3 弓；≤0 取第一个未选职业）
     * @param options          目录英雄全量（含不在城）
     * @param assignedElsewhere 其它编队已占用英雄 id（用于"已指派"标签，可空）
     */
    public static void show(final Activity act, final String fpid, final long sid,
                            final List<Long> initialHeroIds, final Long captainHeroId,
                            final int initialProfession, final List<FormationStore.Hero> options,
                            final Set<Long> assignedElsewhere, final OnPicked cb) {
        if (act == null) return;
        final List<Long> initial = new ArrayList<>();
        if (initialHeroIds != null) {
            for (Long h : initialHeroIds) {
                if (h != null && h > 0 && !initial.contains(h)) initial.add(h);
            }
        }
        final List<FormationStore.Hero> heroes = options == null
                ? new ArrayList<>() : new ArrayList<>(options);
        final Set<Long> assigned = assignedElsewhere == null
                ? Collections.<Long>emptySet() : assignedElsewhere;

        final LinkedHashSet<Long> selected = new LinkedHashSet<>(initial);
        final Long[] captain = {captainHeroId};
        final int startProf = initialProfession >= 1 && initialProfession <= 3
                ? initialProfession : firstEmptyProfession(selected, heroes);
        final int[] currentProf = {startProf};
        final int[] sortMode = {SORT_POWER};

        ScrollView scroll = new ScrollView(act);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 18), dp(act, 10), dp(act, 18), dp(act, 6));
        scroll.addView(root);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        final Runnable[] renderRef = new Runnable[1];
        final Runnable render = () -> {
            root.removeAllViews();
            root.addView(buildHeader(act, heroes.isEmpty()));
            root.addView(buildProfessionCards(act, heroes, selected, captain, currentProf, renderRef));
            root.addView(buildSortRow(act, sortMode, renderRef));
            root.addView(buildGrid(act, heroes, selected, assigned, captain, currentProf,
                    sortMode, renderRef));
            root.addView(buildFooter(act, fpid, sid, selected, captain, initial, heroes,
                    currentProf, dlgRef, cb, renderRef));
        };
        renderRef[0] = render;
        render.run();

        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(scroll)
                .setNegativeButton("取消", null)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    // ==================== 区块 ====================

    private static View buildHeader(Activity act, boolean catalogEmpty) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(act);
        t.setText("英雄选择");
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        t.setPadding(0, 0, 0, dp(act, 8));
        box.addView(t);
        if (catalogEmpty) {
            // 缺陷 ①：目录未同步时也要有可见说明（绝不空白弹窗）
            TextView tip = new TextView(act);
            tip.setText("英雄目录未同步：请先在编队管理页点「刷新」，或等待容量同步完成后重试");
            tip.setTextSize(10.5f);
            tip.setTextColor(UiStyle.WARN_INK);
            tip.setPadding(0, 0, 0, dp(act, 6));
            box.addView(tip);
        }
        return box;
    }

    /** 3 张职业卡（已选=摘要 + × 移除；未选=选择入口）。 */
    private static View buildProfessionCards(final Activity act, final List<FormationStore.Hero> heroes,
                                             final LinkedHashSet<Long> selected, final Long[] captain,
                                             final int[] currentProf, final Runnable[] renderRef) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int p = 1; p <= 3; p++) {
            final int prof = p;
            FormationStore.Hero h = heroOf(heroes, selected, prof);
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(act, 6), dp(act, 8), dp(act, 6), dp(act, 8));
            card.setBackground(UiStyle.roundRectStroke(act,
                    currentProf[0] == prof ? UiStyle.BRAND_50 : UiStyle.SURFACE_2,
                    12, currentProf[0] == prof ? UiStyle.BRAND : UiStyle.LINE));
            card.setOnClickListener(v -> {
                currentProf[0] = prof;
                renderRef[0].run();
            });

            if (h != null) {
                FrameLayout box = new FrameLayout(act);
                ImageView iv = new ImageView(act);
                android.graphics.drawable.Drawable d = FormationIcons.avatar(act, h.id);
                if (d == null) d = FormationIcons.placeholder(act, HeroTileView.professionLabel(prof));
                iv.setImageDrawable(d);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(iv, new FrameLayout.LayoutParams(dp(act, 44), dp(act, 44)));
                TextView remove = new TextView(act);
                remove.setText("×");
                remove.setTextSize(11);
                remove.setTextColor(UiStyle.WHITE);
                remove.setGravity(Gravity.CENTER);
                remove.setBackground(UiStyle.roundRectDp(act, UiStyle.ERR_SOLID, 999));
                FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(act, 16), dp(act, 16));
                rlp.gravity = Gravity.RIGHT | Gravity.TOP;
                box.addView(remove, rlp);
                remove.setOnClickListener(v -> {
                    selected.remove(h.id);
                    if (captain[0] != null && captain[0] == h.id) captain[0] = null;
                    renderRef[0].run();
                });
                card.addView(box, new LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)));
                TextView nm = new TextView(act);
                nm.setText((h.name == null || h.name.isEmpty() ? "英雄" + h.id : h.name)
                        + " · " + PROF_SHORT[prof - 1]);
                nm.setTextSize(10.5f);
                nm.setTypeface(Typeface.DEFAULT_BOLD);
                nm.setTextColor(UiStyle.INK);
                nm.setSingleLine(true);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                nm.setPadding(0, dp(act, 3), 0, 0);
                card.addView(nm);
                TextView meta = new TextView(act);
                meta.setText((h.generation > 0 ? "S" + h.generation : "基础")
                        + " · " + stars(h.starDisplay) + " · " + h.level + "级");
                meta.setTextSize(9.5f);
                meta.setTextColor(UiStyle.INK2);
                meta.setSingleLine(true);
                card.addView(meta);
            } else {
                TextView plus = new TextView(act);
                plus.setText("+");
                plus.setTextSize(18);
                plus.setTypeface(Typeface.DEFAULT_BOLD);
                plus.setTextColor(UiStyle.BRAND_TEXT);
                plus.setGravity(Gravity.CENTER);
                plus.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 999, UiStyle.LINE_STRONG));
                card.addView(plus, new LinearLayout.LayoutParams(dp(act, 40), dp(act, 40)));
                TextView add = new TextView(act);
                add.setText("选择" + PROF_LABELS[prof - 1] + "英雄");
                add.setTextSize(10.5f);
                add.setTypeface(Typeface.DEFAULT_BOLD);
                add.setTextColor(UiStyle.BRAND_TEXT);
                add.setGravity(Gravity.CENTER);
                add.setPadding(0, dp(act, 4), 0, 0);
                card.addView(add);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = dp(act, p < 3 ? 8 : 0);
            row.addView(card, lp);
        }
        return row;
    }

    /** 排序行：`排序` + 实力/等级/稀有度。 */
    private static View buildSortRow(Activity act, final int[] sortMode, final Runnable[] renderRef) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 10), 0, dp(act, 6));
        TextView label = new TextView(act);
        label.setText("排序");
        label.setTextSize(11.5f);
        label.setTextColor(UiStyle.INK2);
        row.addView(label, new LinearLayout.LayoutParams(dp(act, 34),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(UiStyle.segmented(act, SORT_LABELS, sortMode[0], idx -> {
            sortMode[0] = idx;
            renderRef[0].run();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** 当前职业的英雄网格（3 列），空态 `暂无{XX英雄}`。 */
    private static View buildGrid(Activity act, List<FormationStore.Hero> heroes,
                                  LinkedHashSet<Long> selected, Set<Long> assigned,
                                  Long[] captain, int[] currentProf, int[] sortMode,
                                  Runnable[] renderRef) {
        int prof = currentProf[0];
        List<FormationStore.Hero> list = new ArrayList<>();
        for (FormationStore.Hero h : heroes) {
            if (h == null) continue;
            if (FormationRules.professionOf(h) != prof) continue;
            list.add(h);
        }
        sortHeroes(list, sortMode[0]);

        ScrollView inner = new ScrollView(act);
        inner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 250)));
        if (list.isEmpty()) {
            inner.addView(UiStyle.emptyState(act, "🧩",
                    "暂无" + PROF_LABELS[prof - 1] + "英雄",
                    "英雄目录同步后再试，或先手填编队英雄 ID", null, null));
            return inner;
        }
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rowView = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 3 == 0) {
                rowView = new LinearLayout(act);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(rowView);
            }
            final FormationStore.Hero h = list.get(i);
            HeroTileView tile = new HeroTileView(act);
            boolean isSel = selected.contains(h.id);
            boolean isCap = captain[0] != null && captain[0] == h.id;
            tile.bind(h, isSel, assigned.contains(h.id), isCap, true, hero -> {
                // 同职业唯一：点已选的 = 移除；点未选的 = 替换该职业原有英雄（保持顺序位置）
                if (selected.contains(hero.id)) {
                    selected.remove(hero.id);
                } else {
                    selectReplace(selected, hero.id, FormationRules.professionOf(hero),
                            professionMap(heroes));
                }
                if (captain[0] == null || !selected.contains(captain[0])) {
                    captain[0] = selected.isEmpty() ? null : selected.iterator().next();
                }
                renderRef[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = dp(act, (i % 3 == 2) ? 0 : 6);
            lp.bottomMargin = dp(act, 6);
            rowView.addView(tile, lp);
        }
        inner.addView(grid);
        return inner;
    }

    /** 底部：`查看`（当前职业已选英雄详情）+ 主按钮 `保存`/`完成`。 */
    private static View buildFooter(final Activity act, final String fpid, final long sid,
                                    final LinkedHashSet<Long> selected, final Long[] captain,
                                    final List<Long> initial, final List<FormationStore.Hero> heroes,
                                    final int[] currentProf, final AlertDialog[] dlgRef,
                                    final OnPicked cb, final Runnable[] renderRef) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(act, 8), 0, 0);

        final boolean changed = !sameSelection(initial, selected);
        FormationStore.Hero current = heroOf(heroes, selected, currentProf[0]);
        final boolean hasCurrent = current != null;

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView view = new TextView(act);
        view.setText("查看");
        view.setTextSize(12.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(hasCurrent ? UiStyle.BRAND_TEXT : UiStyle.INK3);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        view.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 12,
                hasCurrent ? UiStyle.LINE_STRONG : UiStyle.LINE));
        view.setOnClickListener(v -> {
            if (!hasCurrent) return;
            showHeroInfo(act, heroOf(heroes, selected, currentProf[0]));
        });
        row.addView(view);

        TextView primary = new TextView(act);
        primary.setText(changed ? "保存" : "完成");
        primary.setTextSize(13);
        primary.setTypeface(Typeface.DEFAULT_BOLD);
        primary.setTextColor(UiStyle.WHITE);
        primary.setGravity(Gravity.CENTER);
        primary.setPadding(dp(act, 18), dp(act, 11), dp(act, 18), dp(act, 11));
        boolean enabled = changed || hasCurrent;
        primary.setBackground(UiStyle.roundRectDp(act,
                enabled ? UiStyle.BRAND : UiStyle.LINE_STRONG, 12));
        primary.setOnClickListener(v -> {
            if (!changed && !hasCurrent) return;
            List<Long> out = new ArrayList<>(selected);
            Long cap = captain[0];
            if (cap == null || !out.contains(cap)) cap = out.isEmpty() ? null : out.get(0);
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            if (cb != null) cb.onPicked(out, cap);
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        plp.leftMargin = dp(act, 10);
        row.addView(primary, plp);
        box.addView(row);

        TextView hint = new TextView(act);
        hint.setText("已选 " + selected.size() + "/" + FormationStore.HERO_MAX
                + " · 每职业限选 1 名英雄");
        hint.setTextSize(10);
        hint.setTextColor(UiStyle.INK3);
        hint.setPadding(0, dp(act, 5), 0, 0);
        box.addView(hint);
        return box;
    }

    /** 只读英雄信息（无养成接口时的本地面板，替代网页版 §1.14「查看」）。 */
    private static void showHeroInfo(final Activity act, final FormationStore.Hero h) {
        if (h == null) return;
        int prof = FormationRules.professionOf(h);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 6));
        TextView name = new TextView(act);
        name.setText(h.name == null || h.name.isEmpty() ? "英雄" + h.id : h.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        box.addView(name);
        TextView meta = new TextView(act);
        meta.setText(HeroTileView.professionLabel(prof)
                + (h.generation > 0 ? " · S" + h.generation : " · 基础")
                + " · " + h.level + "级"
                + " · " + stars(h.starDisplay)
                + (h.available ? " · 英雄在家" : " · 未归队"));
        meta.setTextSize(12);
        meta.setTextColor(UiStyle.INK2);
        meta.setPadding(0, dp(act, 4), 0, dp(act, 8));
        box.addView(meta);
        TextView extra = new TextView(act);
        extra.setText("战力 " + FormationRules.fmt(h.power)
                + " · 出征容量 " + FormationRules.fmt(h.troopCapacity));
        extra.setTextSize(12);
        extra.setTextColor(UiStyle.INK2);
        box.addView(extra);
        TextView tip = new TextView(act);
        tip.setText("英雄养成详情需在线同步，App 内暂不提供");
        tip.setTextSize(10.5f);
        tip.setTextColor(UiStyle.INK3);
        tip.setPadding(0, dp(act, 8), 0, 0);
        box.addView(tip);
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("英雄信息")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ==================== 纯逻辑 ====================

    /** 选入/移除（同职业唯一：先移除同职业其它英雄，再把新英雄追加到末尾）。 */
    static void toggle(LinkedHashSet<Long> selected, long heroId) {
        if (selected.contains(heroId)) {
            selected.remove(heroId);
            return;
        }
        if (selected.size() >= FormationStore.HERO_MAX) return;   // 上限保护（≥3 不再加入）
        selected.add(heroId);
    }

    /** 选入时按职业替换：保留原位置（供编队板 3 格顺序使用）。 */
    static void selectReplace(LinkedHashSet<Long> selected, long heroId, int profession,
                              java.util.Map<Long, Integer> profOf) {
        if (selected.contains(heroId)) return;
        List<Long> copy = new ArrayList<>(selected);
        for (Long id : copy) {
            Integer p = profOf.get(id);
            if (p != null && p == profession) selected.remove(id);
        }
        if (selected.size() < FormationStore.HERO_MAX) selected.add(heroId);
    }

    /** 初始页签：第一个没有已选英雄的职业。 */
    static int firstEmptyProfession(Set<Long> selected, List<FormationStore.Hero> heroes) {
        boolean[] used = new boolean[4];
        for (FormationStore.Hero h : heroes) {
            if (h == null || !selected.contains(h.id)) continue;
            int p = FormationRules.professionOf(h);
            if (p >= 1 && p <= 3) used[p] = true;
        }
        for (int p = 1; p <= 3; p++) {
            if (!used[p]) return p;
        }
        return 1;
    }

    /** 英雄 → 职业映射（同职业替换用）。 */
    static java.util.Map<Long, Integer> professionMap(List<FormationStore.Hero> heroes) {
        java.util.Map<Long, Integer> m = new java.util.HashMap<Long, Integer>();
        if (heroes == null) return m;
        for (FormationStore.Hero h : heroes) {
            if (h == null) continue;
            m.put(h.id, FormationRules.professionOf(h));
        }
        return m;
    }

    private static FormationStore.Hero heroOf(List<FormationStore.Hero> heroes,
                                              Set<Long> selected, int profession) {
        for (FormationStore.Hero h : heroes) {
            if (h == null || !selected.contains(h.id)) continue;
            if (FormationRules.professionOf(h) == profession) return h;
        }
        return null;
    }

    private static void sortHeroes(List<FormationStore.Hero> list, int mode) {
        Comparator<FormationStore.Hero> cmp;
        if (mode == SORT_LEVEL) {
            cmp = (a, b) -> {
                int c = Long.compare(b.level, a.level);
                if (c != 0) return c;
                c = Long.compare(b.power, a.power);
                return c != 0 ? c : Long.compare(a.id, b.id);
            };
        } else if (mode == SORT_GENERATION) {
            cmp = (a, b) -> {
                int c = Integer.compare(b.generation, a.generation);
                if (c != 0) return c;
                c = Long.compare(b.power, a.power);
                return c != 0 ? c : Long.compare(a.id, b.id);
            };
        } else {
            cmp = (a, b) -> {
                int c = Long.compare(b.power, a.power);
                if (c != 0) return c;
                c = Long.compare(b.level, a.level);
                return c != 0 ? c : Long.compare(a.id, b.id);
            };
        }
        Collections.sort(list, cmp);
    }

    static boolean sameSelection(List<Long> a, Set<Long> b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        if (a.size() != b.size()) return false;
        return b.containsAll(a);
    }

    static String stars(int display) {
        int n = Math.max(0, Math.min(5, display));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < n ? '★' : '☆');
        return sb.toString();
    }

    private static int dp(Activity act, int v) {
        return UiStyle.dp(act, v);
    }
}
