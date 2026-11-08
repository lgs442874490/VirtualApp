package com.carlos.vault.daily;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.PresetFormations;

/**
 * 巨兽狩猎「巨兽队（预设）」卡（2026-11-03 用户口径）。
 *
 * <p>三种活动互不相同：
 * <ul>
 *   <li><b>巨熊</b>：两天一次、30 分钟，只挨打不还手，按伤害发奖（纯福利）→ 需要打熊队 + 上车队；</li>
 *   <li><b>巨兽</b>：每日冰原巨兽，按王国进度开放等级（当前地图 4-8 级），通过<b>集结</b>处理
 *       → 这里只设一支「预设·巨兽」队（{@link PresetFormations#ID_FIRST}）；</li>
 *   <li><b>狩猎</b>：每日野外 1-30 级野兽，只能出征、不能集结 → 用通用编队卡。</li>
 * </ul>
 *
 * <p>本卡只写该功能 params 的 {@code formation_mode/formation_id}（服务器
 * {@code FormationResolver} / {@code chooseBehemothFormation} 消费），不涉及 join_formations。
 */
public final class BehemothTeamCard {

    /** 预设·巨兽 id。 */
    public static final long PRESET_BEHEMOTH_ID = PresetFormations.ID_FIRST;

    private BehemothTeamCard() {
    }

    /** 构建「巨兽队（预设）」卡；sid≤0 显示等待角色同步。 */
    public static LinearLayout build(final Activity act, final String fpid, final long sid,
                                     final String fnId, final Runnable onChanged) {
        final String useFn = fnId == null || fnId.trim().isEmpty() ? "behemoth" : fnId.trim();
        final LinearLayout card = UiStyle.outlineCard(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 18), dp(act, 10), dp(act, 18), 0);
        card.setLayoutParams(lp);
        fill(act, fpid, sid, useFn, card, onChanged);
        return card;
    }

    private static void fill(final Activity act, final String fpid, final long sid,
                             final String fnId, final LinearLayout card, final Runnable onChanged) {
        card.removeAllViews();
        card.addView(text(act, "巨兽队（预设）", 15, UiStyle.INK, true));
        TextView sub = text(act, "每日冰原巨兽按王国进度开放等级（当前地图 4-8 级），通过集结处理；"
                + "这里只设一支预设巨兽队，建议直接用「预设·巨兽」", 11, UiStyle.INK2, false);
        sub.setPadding(0, dp(act, 3), 0, dp(act, 6));
        card.addView(sub);

        if (fpid == null || fpid.trim().isEmpty() || sid <= 0) {
            card.addView(text(act, "等待角色同步：请在账号页选择角色后返回本页", 11,
                    UiStyle.WARN_INK, false));
            return;
        }

        final String uid = roleUid(act, fpid);
        try {
            FormationStore.ensurePresets(act, fpid, sid, uid);
        } catch (Throwable ignored) {
        }

        String mode = FormationStore.fnMode(act, fpid, sid, fnId);
        long formationId = FormationStore.fnFormationId(act, fpid, sid, fnId);
        String value;
        if (FormationStore.MODE_ROLE.equals(mode) && formationId > 0) {
            value = nameOf(act, fpid, sid, formationId);   // 只显示编队名字（去掉 #编号）
        } else {
            value = "自动（最强盾 / 波根 / 吉娜）";
        }
        card.addView(UiStyle.dropdownRow(act, "巨兽队", value,
                () -> FormationPicker.pickFormationFor(act, fpid, sid, uid, null, mode,
                        new long[]{formationId},
                        (pickedMode, ids, inf, lancer, marksman, ratios) -> {
                            if (FormationStore.MODE_ROLE.equals(pickedMode) && ids.length > 0) {
                                FormationStore.saveFnMode(act, fpid, sid, fnId, FormationStore.MODE_ROLE);
                                FormationStore.saveFnFormation(act, fpid, sid, fnId, ids[0]);
                                FormationStore.saveFnSoldiersOverride(act, fpid, sid, fnId,
                                        inf, lancer, marksman);
                            } else {
                                FormationStore.saveFnMode(act, fpid, sid, fnId,
                                        FormationStore.MODE_AUTOMATIC);
                                FormationStore.saveFnAutoRatio(act, fpid, sid, fnId,
                                        ratios[0], ratios[1], ratios[2], 0);
                            }
                            sync(act);
                            fill(act, fpid, sid, fnId, card, onChanged);
                            if (onChanged != null) onChanged.run();
                        })));

        // 一键使用「预设·巨兽」
        FormationModel preset = null;
        try {
            preset = FormationStore.formation(act, fpid, sid, PRESET_BEHEMOTH_ID);
        } catch (Throwable ignored) {
        }
        if (preset != null) {
            final FormationModel usePreset = preset;
            card.addView(UiStyle.dropdownRow(act, "快速设置",
                    "使用「" + usePreset.name + "」预设",
                    () -> {
                        FormationStore.saveFnMode(act, fpid, sid, fnId, FormationStore.MODE_ROLE);
                        FormationStore.saveFnFormation(act, fpid, sid, fnId, PRESET_BEHEMOTH_ID);
                        sync(act);
                        fill(act, fpid, sid, fnId, card, onChanged);
                        if (onChanged != null) onChanged.run();
                    }));
        } else {
            TextView hint = text(act, "预设「巨兽」尚未生成：登录并同步角色英雄后自动创建",
                    10.5f, UiStyle.INK3, false);
            hint.setPadding(0, dp(act, 2), 0, 0);
            card.addView(hint);
        }
    }

    private static String roleUid(Activity act, String fpid) {
        try {
            long uid = RoleStore.currentRoleUid(act, fpid);
            return uid > 0 ? String.valueOf(uid) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String nameOf(Activity act, String fpid, long sid, long id) {
        try {
            FormationModel f = FormationStore.formation(act, fpid, sid, id);
            if (f != null && f.name != null && !f.name.trim().isEmpty()) return f.name;
        } catch (Throwable ignored) {
        }
        return "编队已删除";
    }

    private static void sync(Activity act) {
        try {
            com.carlos.vault.VaultSync.onAccountsChanged(act);
        } catch (Throwable ignored) {
        }
    }

    private static TextView text(Activity act, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + .5f);
    }
}
