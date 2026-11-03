package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationRules;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 功能页「情报出征设置」卡（设计 §4.2；仅 {@code fn.id=intel}，位于通用「编队」卡下方）。
 *
 * <p>三块内容：
 * <ol>
 *   <li><b>英雄下拉</b>（盾/矛/弓）：首项按职业区分——盾=「自动（最强盾）」、矛=「自动（波根）」、
 *       弓=「自动（吉娜）」；其余 = 目录中该职业英雄（{@code 名字 · Lv · 星 · 战力}，
 *       按 power→level→star→id 倒序，与服务端 {@code HERO_STRENGTH} 同口径；power=0 不展示战力）。
 *       写入 {@code intel_hero_shield/lancer/marksman}（0=自动）。目录未同步
 *       （{@code heroOptions()} 为空）时整块置灰，只读提示
 *       「英雄目录未同步：先登录/等待同步」，比例仍可编辑（设计 §4.2）。</li>
 *   <li><b>兵种比例</b>：三行数字输入（盾/矛/弓，0..100，默认 50/20/30）+ 「5:2:3」快捷比例按钮
 *       + 合计/错误文案；保存写 {@code intel_ratio}（服务端按相对权重分配）。</li>
 *   <li><b>保存 / 恢复默认</b>：保存写 params + {@link VaultSync#onAccountsChanged}；
 *       恢复默认清空 5 个 {@code intel_*} 键 → 回落到"最强盾/波根/吉娜 · 50:20:30"默认自动。</li>
 * </ol>
 *
 * <p>若当前是指定编队（{@code formation_mode=ROLE} 且已绑定编队），顶部提示"本页出征设置仅在
 * 自动编队时生效"并给「切回自动」（只写 {@code formation_mode=AUTOMATIC} 并清 id，
 * <b>不</b>调用 {@code saveFnFormation(0)}——那会把模式又改回 ROLE，生产踩坑记录见
 * {@code FormationEntryCard}）。
 *
 * <p>下拉选择走 {@link AlertDialog#setSingleChoiceItems}（与 {@code GatherConfigActivity}
 * 既有写法一致），整个构建/点击链路 try/catch，绝不让异常冒到功能页。
 */
public final class IntelFormationCard {

    /** 情报功能 id（{@link FnRegistry} / 服务端 {@code IntelFormationPlan} 同值）。 */
    public static final String FN_INTEL = "intel";

    /** 职业：1 盾 / 2 矛 / 3 弓（与 {@link FormationRules#professionOf} 同口径）。 */
    private static final int PROF_SHIELD = 1;
    private static final int PROF_LANCER = 2;
    private static final int PROF_MARKSMAN = 3;

    private static final String[] HERO_LABELS = {"盾兵英雄", "矛兵英雄", "弓兵英雄"};
    private static final int[] HERO_PROFS = {PROF_SHIELD, PROF_LANCER, PROF_MARKSMAN};
    private static final String[] RATIO_LABELS = {"盾兵比例", "矛兵比例", "弓兵比例"};

    /** 「自动」项按职业区分（用户要求：盾=最强盾、矛=波根、弓=吉娜）。 */
    private static final String AUTO_SHIELD = "自动（最强盾）";
    private static final String AUTO_LANCER = "自动（波根）";
    private static final String AUTO_MARKSMAN = "自动（吉娜）";
    private static final String AUTO_FALLBACK = "自动（最强）";
    private static final String UNSYNCED_HINT = "英雄目录未同步：先登录/等待同步";

    private IntelFormationCard() {
    }

    /** 英雄选择回调（id=0 表示自动）。 */
    private interface OnHeroChosen {
        void onChosen(long heroId, String label);
    }

    /**
     * 构建「情报出征设置」卡。
     *
     * @param sid       角色区服 id（≤0 = 角色未同步 → 只显示等待文案）
     * @param uid       当前角色 uid（空串=旧键，等价于 uid 未知）
     * @param fnId      功能 id（非 {@value #FN_INTEL} 返回空卡，调用方可无条件 addView）
     * @param onChanged 保存/切换后的刷新回调（可空）
     */
    public static LinearLayout build(final Activity act, final String fpid, final long sid,
                                     final String uid, final String fnId, final Runnable onChanged) {
        final LinearLayout card = UiStyle.outlineCard(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 18), dp(act, 10), dp(act, 18), 0);
        card.setLayoutParams(lp);
        if (fnId == null || !FN_INTEL.equals(fnId)) return card;   // 仅情报页使用
        try {
            fill(act, fpid, sid, uid, fnId, card, onChanged);
        } catch (Throwable t) {
            card.removeAllViews();
            card.addView(text(act, "情报出征设置暂不可用：" + t.getMessage(), 11,
                    UiStyle.ERR_INK, false));
        }
        return card;
    }

    // ==================== 页面构建 ====================

    private static void fill(final Activity act, final String fpid, final long sid,
                             final String uid, final String fnId, final LinearLayout card,
                             final Runnable onChanged) {
        card.removeAllViews();
        final boolean ready = fpid != null && !fpid.trim().isEmpty() && sid > 0L;
        final JSONObject params = ready ? FnParams.params(act, fpid, sid, fnId) : new JSONObject();
        final IntelFormationConfig cfg = IntelFormationConfig.read(params);

        // ---------- 标题 + 默认规则说明 ----------
        card.addView(text(act, "情报出征设置", 15, UiStyle.INK, true));
        TextView desc = text(act,
                "默认出征：最强盾 / 波根(矛) / 吉娜(弓) · "
                        + IntelFormationConfig.quoteRatio(
                        IntelFormationConfig.DEFAULT_INFANTRY,
                        IntelFormationConfig.DEFAULT_LANCER,
                        IntelFormationConfig.DEFAULT_MARKSMAN)
                        + "（缺失时自动补对应职业最强）",
                11, UiStyle.INK2, false);
        desc.setPadding(0, dp(act, 3), 0, dp(act, 6));
        card.addView(desc);

        if (!ready) {
            TextView wait = text(act, "等待角色同步：请在账号页选择角色后返回本页", 11,
                    UiStyle.WARN_INK, false);
            wait.setPadding(0, dp(act, 2), 0, dp(act, 2));
            card.addView(wait);
            return;
        }

        // ---------- 指定编队（ROLE）提示 + 切回自动 ----------
        if (IntelFormationConfig.isRoleBound(params)) {
            LinearLayout roleBox = new LinearLayout(act);
            roleBox.setOrientation(LinearLayout.VERTICAL);
            roleBox.setBackground(UiStyle.roundRectDp(act, UiStyle.WARN_BG, 12));
            roleBox.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(act, 6);
            roleBox.setLayoutParams(rlp);
            roleBox.addView(text(act, "当前使用指定编队，本页出征设置仅在自动编队时生效", 11.5f,
                    UiStyle.WARN_INK, false));
            TextView back = text(act, "切回自动 ›", 12, UiStyle.BRAND_TEXT, true);
            back.setPadding(0, dp(act, 4), 0, 0);
            UiStyle.press(back);
            back.setOnClickListener(v -> {
                try {
                    // 只写 mode=AUTOMATIC（内部会清 formation_id/formation_ids）；
                    // 绝不能用 saveFnFormation(0)——它会把模式强制改回 ROLE。
                    FormationStore.saveFnMode(act, fpid, sid, fnId,
                            FormationStore.MODE_AUTOMATIC);
                    try {
                        VaultSync.onAccountsChanged(act);
                    } catch (Throwable ignored) {
                    }
                    fill(act, fpid, sid, uid, fnId, card, onChanged);
                    if (onChanged != null) onChanged.run();
                    UiStyle.toast(act, "已切换为自动编队，情报将按本页设置出征");
                } catch (Throwable t) {
                    UiStyle.toast(act, "切回自动失败：" + t.getMessage());
                }
            });
            roleBox.addView(back);
            card.addView(roleBox);
        }

        // ---------- 英雄下拉（盾/矛/弓） ----------
        final FormationCatalogStore cat = FormationStore.catalogOf(act, fpid, sid, uid);
        final List<FormationStore.Hero> heroes = cat == null
                ? new ArrayList<FormationStore.Hero>() : cat.heroOptions();
        final boolean synced = cat != null && cat.hasHeroes();
        if (!synced) {
            TextView hint = text(act, UNSYNCED_HINT, 11, UiStyle.INK3, false);
            hint.setPadding(0, dp(act, 2), 0, dp(act, 2));
            card.addView(hint);
        }
        final long[] current = {cfg.heroShield, cfg.heroLancer, cfg.heroMarksman};
        final Map<Long, Integer> fallbackLevels = heroLevelFallbacks(cat);
        for (int i = 0; i < HERO_LABELS.length; i++) {
            heroRow(act, card, HERO_LABELS[i], HERO_PROFS[i], current, i, heroes,
                    fallbackLevels, synced, fpid, sid, fnId, onChanged);
        }

        // ---------- 兵种比例（3 行数字输入） ----------
        card.addView(UiStyle.divider(act));
        TextView ratioTitle = text(act, "兵种比例（相对权重，合计需 >0）", 12, UiStyle.INK, true);
        ratioTitle.setPadding(0, dp(act, 8), 0, dp(act, 2));
        card.addView(ratioTitle);

        // 快捷比例 5:2:3（=50/20/30，点击填入三行输入框；默认值同源 IntelFormationConfig）
        LinearLayout quickRow = new LinearLayout(act);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        quickRow.setPadding(0, dp(act, 2), 0, dp(act, 2));
        TextView quickLabel = text(act, "快捷比例", 11, UiStyle.INK2, false);
        LinearLayout.LayoutParams qllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qllp.rightMargin = dp(act, 8);
        quickRow.addView(quickLabel, qllp);
        final TextView quick523 = UiStyle.chip(act, "5:2:3", false);
        quickRow.addView(quick523);
        card.addView(quickRow);

        final TextView sumView = text(act, "", 11, UiStyle.INK2, false);
        sumView.setPadding(0, dp(act, 4), 0, dp(act, 2));
        final EditText[] ratioInputs = new EditText[3];
        final Runnable updateSum = () -> refreshSum(sumView, ratioInputs);
        ratioInputs[0] = ratioRow(act, card, RATIO_LABELS[0], cfg.ratioInfantry, updateSum);
        ratioInputs[1] = ratioRow(act, card, RATIO_LABELS[1], cfg.ratioLancer, updateSum);
        ratioInputs[2] = ratioRow(act, card, RATIO_LABELS[2], cfg.ratioMarksman, updateSum);
        quick523.setOnClickListener(v -> {
            ratioInputs[0].setText(String.valueOf(IntelFormationConfig.DEFAULT_INFANTRY));
            ratioInputs[1].setText(String.valueOf(IntelFormationConfig.DEFAULT_LANCER));
            ratioInputs[2].setText(String.valueOf(IntelFormationConfig.DEFAULT_MARKSMAN));
            updateSum.run();
        });
        card.addView(sumView);
        refreshSum(sumView, ratioInputs);

        // 总兵力只读提示（intel_total 由服务端按容量/库存自动；此处保留既有值不被覆盖）
        TextView totalHint = text(act, cfg.total > 0L
                ? "总兵力：固定 " + FormationRules.fmt(cfg.total) + "（intel_total）"
                : "总兵力：按出征容量与库存自动", 10.5f, UiStyle.INK3, false);
        totalHint.setPadding(0, dp(act, 2), 0, dp(act, 2));
        card.addView(totalHint);

        // ---------- 保存 / 恢复默认 ----------
        TextView validateView = text(act, "", 11, UiStyle.ERR_INK, false);
        validateView.setPadding(0, dp(act, 4), 0, 0);
        card.addView(validateView);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, dp(act, 8), 0, 0);
        TextView save = UiStyle.primaryBtn(act, "保存", () -> {
            int r0 = IntelFormationConfig.parseRatio(
                    textOf(ratioInputs[0]), IntelFormationConfig.DEFAULT_INFANTRY);
            int r1 = IntelFormationConfig.parseRatio(
                    textOf(ratioInputs[1]), IntelFormationConfig.DEFAULT_LANCER);
            int r2 = IntelFormationConfig.parseRatio(
                    textOf(ratioInputs[2]), IntelFormationConfig.DEFAULT_MARKSMAN);
            String err = IntelFormationConfig.validateRatio(r0, r1, r2);
            if (err != null) {
                validateView.setText(err);
                refreshSum(sumView, ratioInputs);
                UiStyle.toast(act, err);
                return;
            }
            try {
                JSONObject p = FnParams.params(act, fpid, sid, fnId);
                IntelFormationConfig c = IntelFormationConfig.read(p);
                c.ratioInfantry = r0;
                c.ratioLancer = r1;
                c.ratioMarksman = r2;
                FnParams.saveParams(act, fpid, sid, fnId, c.write(p));
                try {
                    VaultSync.onAccountsChanged(act);
                } catch (Throwable ignored) {
                }
                validateView.setText("");
                fill(act, fpid, sid, uid, fnId, card, onChanged);
                if (onChanged != null) onChanged.run();
                UiStyle.toast(act, "情报出征设置已保存（比例 "
                        + IntelFormationConfig.quoteRatio(r0, r1, r2) + "）");
            } catch (Throwable t) {
                validateView.setText("保存失败：" + t.getMessage());
                UiStyle.toast(act, "保存失败：" + t.getMessage());
            }
        });
        save.setMinHeight(dp(act, 44));          // 与「恢复默认」同高（Design 主 48 / 次 40，同行取中）
        btns.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView reset = UiStyle.secondaryBtn(act, "恢复默认", () -> {
            try {
                JSONObject p = FnParams.params(act, fpid, sid, fnId);
                FnParams.saveParams(act, fpid, sid, fnId, IntelFormationConfig.clear(p));
                try {
                    VaultSync.onAccountsChanged(act);
                } catch (Throwable ignored) {
                }
                fill(act, fpid, sid, uid, fnId, card, onChanged);
                if (onChanged != null) onChanged.run();
                UiStyle.toast(act, "已恢复默认：最强盾 / 波根 / 吉娜 · "
                        + IntelFormationConfig.quoteRatio(IntelFormationConfig.DEFAULT_INFANTRY,
                        IntelFormationConfig.DEFAULT_LANCER,
                        IntelFormationConfig.DEFAULT_MARKSMAN));
            } catch (Throwable t) {
                UiStyle.toast(act, "恢复默认失败：" + t.getMessage());
            }
        });
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rlp2.leftMargin = dp(act, 8);
        reset.setMinHeight(dp(act, 44));
        btns.addView(reset, rlp2);
        card.addView(btns);
    }

    // ==================== 英雄下拉 ====================

    /**
     * 单个职业的英雄下拉行：首项按职业区分的「自动（最强盾/波根/吉娜）」+ 该职业英雄
     * （power→level→star→id 倒序，与服务端 HERO_STRENGTH 同口径）。
     * 选中即落库（写 {@code intel_hero_*}），比例输入框内容不受影响（不整页重建）。
     */
    private static void heroRow(final Activity act, final LinearLayout card, final String label,
                                final int profession, final long[] current, final int idx,
                                final List<FormationStore.Hero> heroes,
                                final Map<Long, Integer> fallbackLevels, final boolean synced,
                                final String fpid, final long sid, final String fnId,
                                final Runnable onChanged) {
        final TextView row = new TextView(act);
        row.setTextSize(12);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(act, 40));
        setDropdownText(row, label,
                heroValue(heroes, current[idx], profession, fallbackLevels), synced);
        card.addView(row);
        if (!synced) return;
        UiStyle.press(row);
        row.setOnClickListener(v -> {
            try {
                showHeroDialog(act, label, profession, current[idx], heroes,
                        fallbackLevels, (heroId, pickedLabel) -> {
                            try {
                                JSONObject p = FnParams.params(act, fpid, sid, fnId);
                                IntelFormationConfig c = IntelFormationConfig.read(p);
                                if (profession == PROF_SHIELD) c.heroShield = heroId;
                                else if (profession == PROF_LANCER) c.heroLancer = heroId;
                                else c.heroMarksman = heroId;
                                FnParams.saveParams(act, fpid, sid, fnId, c.write(p));
                                try {
                                    VaultSync.onAccountsChanged(act);
                                } catch (Throwable ignored) {
                                }
                                current[idx] = heroId;
                                setDropdownText(row, label, pickedLabel, true);
                                if (onChanged != null) onChanged.run();
                                UiStyle.toast(act, label + "：" + pickedLabel);
                            } catch (Throwable t) {
                                UiStyle.toast(act, "保存英雄失败：" + t.getMessage());
                            }
                        });
            } catch (Throwable t) {
                UiStyle.toast(act, "打开英雄选择失败：" + t.getMessage());
            }
        });
    }

    /** 弹出该职业英雄单选列表（setSingleChoiceItems；不使用自定义视图，保证不崩溃）。 */
    private static void showHeroDialog(final Activity act, final String title, final int profession,
                                       final long currentId,
                                       final List<FormationStore.Hero> heroes,
                                       final Map<Long, Integer> fallbackLevels,
                                       final OnHeroChosen cb) {
        final List<FormationStore.Hero> matched = new ArrayList<FormationStore.Hero>();
        if (heroes != null) {
            for (FormationStore.Hero h : heroes) {
                if (h == null) continue;
                if (FormationRules.professionOf(h) == profession) matched.add(h);
            }
        }
        // 强度排序（服务端 HERO_STRENGTH 同口径）：power > level > star > id，越大越强。
        // level 缺失时用目录原始 effectiveLevel/training_lev 回退（仅展示/排序用）。
        Collections.sort(matched, new Comparator<FormationStore.Hero>() {
            @Override
            public int compare(FormationStore.Hero a, FormationStore.Hero b) {
                int c = cmpDesc(a.power, b.power);
                if (c != 0) return c;
                c = cmpDesc(displayLevel(a, fallbackLevels), displayLevel(b, fallbackLevels));
                if (c != 0) return c;
                c = cmpDesc(a.star, b.star);
                if (c != 0) return c;
                return cmpDesc(a.id, b.id);
            }
        });
        final String autoLabel = autoLabel(profession);
        final String[] labels = new String[matched.size() + 1];
        labels[0] = autoLabel;
        int selected = 0;
        for (int i = 0; i < matched.size(); i++) {
            FormationStore.Hero h = matched.get(i);
            labels[i + 1] = heroLabel(h, fallbackLevels);
            if (h.id == currentId) selected = i + 1;
        }
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(title)
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    d.dismiss();
                    if (cb == null) return;
                    if (which <= 0) {
                        cb.onChosen(IntelFormationConfig.HERO_AUTO, autoLabel);
                    } else {
                        FormationStore.Hero h = matched.get(which - 1);
                        cb.onChosen(h.id, heroLabel(h, fallbackLevels));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「自动」项文案：盾=最强盾 / 矛=波根 / 弓=吉娜（未知职业回退「自动（最强）」）。 */
    private static String autoLabel(int profession) {
        if (profession == PROF_SHIELD) return AUTO_SHIELD;
        if (profession == PROF_LANCER) return AUTO_LANCER;
        if (profession == PROF_MARKSMAN) return AUTO_MARKSMAN;
        return AUTO_FALLBACK;
    }

    /** 下拉行当前值文案：自动（按职业）/ 英雄名 · Lv · 星 · 战力 / 目录缺失时保留 id 提示。 */
    private static String heroValue(List<FormationStore.Hero> heroes, long heroId,
                                    int profession, Map<Long, Integer> fallbackLevels) {
        if (heroId <= 0L) return autoLabel(profession);
        if (heroes != null) {
            for (FormationStore.Hero h : heroes) {
                if (h != null && h.id == heroId) return heroLabel(h, fallbackLevels);
            }
        }
        return "英雄#" + heroId + "（目录中未找到，出征时按最强兜底）";
    }

    /**
     * {@code 名字 · Lv31 · 4星 · 战力xxx}（未归队补标记）。
     *
     * <p>power=0 时不显示「战力0」；level=0 时不显示 Lv；星级按 starDisplay 优先，
     * 缺省用原始 star（30=5 星）折算。
     */
    private static String heroLabel(FormationStore.Hero h,
                                    Map<Long, Integer> fallbackLevels) {
        if (h == null) return AUTO_FALLBACK;
        StringBuilder sb = new StringBuilder();
        sb.append(h.name == null || h.name.trim().isEmpty() ? ("英雄" + h.id) : h.name.trim());
        int level = displayLevel(h, fallbackLevels);
        if (level > 0) sb.append(" · Lv").append(level);
        int stars = displayStars(h);
        if (stars > 0) sb.append(" · ").append(stars).append("星");
        if (h.power > 0L) sb.append(" · 战力").append(FormationRules.fmt(h.power));
        if (!h.available) sb.append(" · 未归队");
        return sb.toString();
    }

    // ==================== 英雄等级/星级回退 ====================

    /**
     * 目录原始等级回退（仅当 {@code Hero.level==0} 时用于展示/排序）：
     * 读 state 原始 JSON 的 effectiveLevel / training_lev / lev，不改 FormationStore。
     */
    private static Map<Long, Integer> heroLevelFallbacks(FormationCatalogStore cat) {
        Map<Long, Integer> out = new HashMap<Long, Integer>();
        if (cat == null) return out;
        try {
            JSONObject raw = cat.raw();
            JSONArray arr = raw.optJSONArray("heroes");
            if (arr == null) arr = raw.optJSONArray("heroOptions");
            if (arr == null) arr = raw.optJSONArray("hero_options");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id", 0L);
                if (id <= 0L) continue;
                int level = firstPositiveInt(o, "effectiveLevel", "effective_level",
                        "training_lev", "trainingLev", "lev");
                if (level > 0) out.put(id, level);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 展示等级：Hero.level 优先，缺失时用目录原始等级回退。 */
    private static int displayLevel(FormationStore.Hero h, Map<Long, Integer> fallbackLevels) {
        if (h == null) return 0;
        if (h.level > 0) return h.level;
        Integer v = fallbackLevels == null ? null : fallbackLevels.get(h.id);
        return v == null ? 0 : Math.max(0, v.intValue());
    }

    /** 展示星级（0..5）：starDisplay 优先，否则原始 star（30=5 星）折算。 */
    private static int displayStars(FormationStore.Hero h) {
        if (h == null) return 0;
        int s = h.starDisplay > 0 ? h.starDisplay : h.star;
        if (s > 5) s = (int) Math.round(s / 6.0);
        return Math.max(0, Math.min(5, s));
    }

    /** 降序比较（服务端 HERO_STRENGTH：越大越强）。 */
    private static int cmpDesc(long a, long b) {
        return a > b ? -1 : (a < b ? 1 : 0);
    }

    private static int firstPositiveInt(JSONObject o, String... keys) {
        if (o == null || keys == null) return 0;
        for (String k : keys) {
            int v = o.optInt(k, 0);
            if (v > 0) return v;
        }
        return 0;
    }

    // ==================== 比例输入 ====================

    private static EditText ratioRow(final Activity act, final LinearLayout card,
                                     final String label, final int value,
                                     final Runnable onChangedSum) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 4), 0, dp(act, 4));
        TextView l = text(act, label, 12, UiStyle.INK, false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        EditText et = UiStyle.searchInput(act, "");
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setTextSize(13);
        et.setGravity(Gravity.CENTER);
        et.setText(String.valueOf(value));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                dp(act, 64), ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = dp(act, 8);
        row.addView(et, elp);
        TextView pct = text(act, "%", 12, UiStyle.INK2, false);
        pct.setPadding(dp(act, 4), 0, 0, 0);
        row.addView(pct);
        card.addView(row);
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (onChangedSum != null) onChangedSum.run();
            }
        });
        return et;
    }

    /** 合计/校验提示（合法=灰字合计；非法=红字原因）。 */
    private static void refreshSum(TextView sumView, EditText[] inputs) {
        if (sumView == null || inputs == null || inputs.length < 3) return;
        int r0 = IntelFormationConfig.parseRatio(textOf(inputs[0]),
                IntelFormationConfig.DEFAULT_INFANTRY);
        int r1 = IntelFormationConfig.parseRatio(textOf(inputs[1]),
                IntelFormationConfig.DEFAULT_LANCER);
        int r2 = IntelFormationConfig.parseRatio(textOf(inputs[2]),
                IntelFormationConfig.DEFAULT_MARKSMAN);
        String err = IntelFormationConfig.validateRatio(r0, r1, r2);
        if (err != null) {
            sumView.setText("合计 " + (r0 + r1 + r2) + "% · " + err);
            sumView.setTextColor(UiStyle.ERR_INK);
        } else {
            sumView.setText("合计 " + (r0 + r1 + r2) + "%（服务端按相对权重分配兵量）");
            sumView.setTextColor(r0 + r1 + r2 == 100 ? UiStyle.OK_INK : UiStyle.INK2);
        }
    }

    // ==================== 小工具 ====================

    private static String textOf(EditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString();
    }

    /** 与 {@link UiStyle#dropdownRow} 同款着色（禁用时整行 INK3 且不带 ▾）。 */
    private static void setDropdownText(TextView v, String label, String value, boolean enabled) {
        String text = label + "  " + value + (enabled ? " ▾" : "");
        SpannableString ss = new SpannableString(text);
        int vs = label.length() + 2;
        if (enabled) {
            ss.setSpan(new ForegroundColorSpan(UiStyle.INK2), 0, label.length(), 0);
            ss.setSpan(new StyleSpan(Typeface.BOLD), vs, ss.length(), 0);
            ss.setSpan(new ForegroundColorSpan(UiStyle.BRAND_TEXT), vs, ss.length(), 0);
        } else {
            ss.setSpan(new ForegroundColorSpan(UiStyle.INK3), 0, label.length(), 0);
            ss.setSpan(new ForegroundColorSpan(UiStyle.INK3), vs, ss.length(), 0);
        }
        v.setText(ss);
    }

    private static TextView text(Activity act, String s, float size, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static int dp(Activity act, int v) {
        return UiStyle.dp(act, v);
    }
}
