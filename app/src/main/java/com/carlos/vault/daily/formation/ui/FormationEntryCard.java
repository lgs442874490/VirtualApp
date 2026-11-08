package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.FormationRules;
import com.carlos.vault.daily.formation.ui.FormationManagerDialogs;

import java.util.List;

/**
 * 功能页「编队」摘要卡（T5，契约 §4.1；网页版入口卡 §1.1 的功能页等价形态）。
 *
 * <p>与网页版入口卡同源的 4 条状态行 + 容量徽章 + 支编队计数；点击「编队管理」进入
 * {@code FormationManagerActivity}（T4），点「出征容量」复用 T4 的容量弹窗。
 *
 * <p><b>永不为空</b>（用户实测缺陷 ①）：任何状态（角色未同步 / 目录未就绪 / 无编队 / 离线）
 * 都渲染可见文案与可点按钮；仅"角色未同步"时入口置灰并提示「等待角色同步」。
 */
public final class FormationEntryCard {

    private FormationEntryCard() {
    }

    /**
     * 构建摘要卡。
     *
     * @param sid       角色区服 id（≤0 表示角色未同步 → 卡片降级为"等待角色同步"并禁用入口）
     * @param fnId      功能 id（gather/intel/behemoth/bear/hunt/guardian）
     * @param onChanged 编辑返回后的刷新回调（可空）
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
        fill(act, fpid, sid, uid, fnId, card, onChanged);
        return card;
    }

    private static void fill(final Activity act, final String fpid, final long sid,
                             final String uid, final String fnId, final LinearLayout card,
                             final Runnable onChanged) {
        card.removeAllViews();
        final boolean ready = sid > 0 && fpid != null && !fpid.trim().isEmpty();
        final FormationCatalogStore cat = ready
                ? FormationStore.catalogOf(act, fpid, sid, uid) : null;   // T7：uid 感知目录，多角色不串
        final List<FormationModel> lib = ready
                ? FormationStore.formations(act, fpid, sid) : java.util.Collections.emptyList();
        final List<FormationModel> games = cat == null
                ? java.util.Collections.emptyList() : cat.gameFormations();
        final int custom = countCustom(lib);
        final int total = custom + (games == null ? 0 : games.size());

        // ---------- 标题行：编队 + 容量徽章 + 编队管理入口 ----------
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(act, "编队", 15, UiStyle.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (ready && cat != null) {
            TextView badge = UiStyle.badge(act, cat.configured() ? "ok" : "warn",
                    cat.configured() ? "容量已同步" : "同步中");
            head.addView(badge);
        }
        // 显式「＋ 新建」入口：用户反馈功能页编队卡只看到“选择/管理”，找不到新增编队
        TextView add = text(act, "＋ 新建", 12,
                ready ? UiStyle.BRAND_TEXT : UiStyle.INK3, true);
        UiStyle.press(add);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(act, 8);
        head.addView(add, alp);
        add.setOnClickListener(v -> {
            if (!ready) {
                UiStyle.toast(act, "等待角色同步，请先选择角色");
                return;
            }
            try {
                com.carlos.vault.daily.FormationManagerActivity.launchNew(act, fpid, sid, uid, fnId);
            } catch (Throwable t) {
                UiStyle.toast(act, "打开编队管理失败：" + t.getMessage());
            }
            if (onChanged != null) onChanged.run();
        });

        TextView manage = text(act, "编队管理 ›", 12, ready ? UiStyle.BRAND_TEXT : UiStyle.INK3, true);
        UiStyle.press(manage);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(act, 10);
        head.addView(manage, mlp);
        manage.setOnClickListener(v -> {
            if (!ready) {
                UiStyle.toast(act, "等待角色同步，请先选择角色");
                return;
            }
            try {
                com.carlos.vault.daily.FormationManagerActivity.launch(act, fpid, sid, uid, fnId);
            } catch (Throwable t) {
                UiStyle.toast(act, "打开编队管理失败：" + t.getMessage());
            }
            if (onChanged != null) onChanged.run();
        });
        card.addView(head);

        // ---------- 状态行（网页版 §1.1 四文案）----------
        TextView status = text(act, statusText(ready, cat, total, custom,
                games == null ? 0 : games.size()), 11, UiStyle.INK2, false);
        status.setPadding(0, dp(act, 3), 0, dp(act, 6));
        card.addView(status);

        if (!ready) {
            // 角色未同步：给可操作指引，而不是空白/异常弹窗（缺陷 ①）
            TextView wait = text(act, "等待角色同步：请在账号页选择角色后返回本页", 11,
                    UiStyle.WARN_INK, false);
            wait.setPadding(0, dp(act, 2), 0, dp(act, 2));
            card.addView(wait);
            return;
        }

        // ---------- 当前绑定摘要（非采集：点击可切换 自动/指定编队，1:1 网页版队列选择器） ----------
        final View bindRow = bindingSummary(act, fpid, sid, uid, fnId, lib);
        if (!"gather".equals(fnId)) {
            bindRow.setContentDescription("选择出征编队");
            UiStyle.press(bindRow);
            bindRow.setOnClickListener(v -> {
                try {
                    com.carlos.vault.daily.FormationPicker.pickFormationFor(act, fpid, sid, uid,
                            null,
                            (mode, ids, inf, lanc, mark, ratios) -> {
                                if (FormationStore.MODE_ROLE.equals(mode)) {
                                    FormationStore.saveFnMode(act, fpid, sid, fnId, mode);
                                    long first = ids != null && ids.length > 0 ? ids[0] : 0L;
                                    FormationStore.saveFnFormation(act, fpid, sid, fnId, first);
                                    if (ids != null && ids.length > 1) {
                                        FormationStore.saveFnFormationIds(act, fpid, sid, fnId, ids);
                                    }
                                    FormationStore.saveFnSoldiersOverride(act, fpid, sid, fnId,
                                            inf, lanc, mark);
                                } else {
                                    // 自动编队：只写 mode+比例权重；不要调用 saveFnFormation(0)
                                    // （该方法会把模式强制改回 ROLE，生产实测踩坑）
                                    int r0 = ratios != null && ratios.length >= 3 ? ratios[0] : 50;
                                    int r1 = ratios != null && ratios.length >= 3 ? ratios[1] : 20;
                                    int r2 = ratios != null && ratios.length >= 3 ? ratios[2] : 30;
                                    FormationStore.saveFnAutoRatio(act, fpid, sid, fnId, r0, r1, r2, 0);
                                    FormationStore.saveFnMode(act, fpid, sid, fnId,
                                            FormationStore.MODE_AUTOMATIC);
                                    FormationStore.saveFnSoldiersOverride(act, fpid, sid, fnId,
                                            0, 0, 0);
                                }
                                try {
                                    VaultSync.onAccountsChanged(act);
                                } catch (Throwable ignored) {
                                }
                                fill(act, fpid, sid, uid, fnId, card, onChanged);
                                if (onChanged != null) onChanged.run();
                                UiStyle.toast(act, FormationStore.MODE_ROLE.equals(mode)
                                        ? "已指定出征编队" : "已切换为自动编队");
                            });
                } catch (Throwable t) {
                    UiStyle.toast(act, "打开编队选择失败：" + t.getMessage());
                }
            });
        }
        card.addView(bindRow);

        // ---------- 出征容量入口（复用 T4 容量弹窗）----------
        if (cat != null) {
            long cap = cat.capacity();
            String capText = cap > 0
                    ? FormationRules.fmt(cap) + (cat.percentageBasisPoints() != 0
                    ? "（Buff " + (cat.percentageBasisPoints() > 0 ? "+" : "") +
                    (cat.percentageBasisPoints() / 100) + "%）" : "")
                    : "等待容量同步";
            TextView capacityRow = UiStyle.dropdownRow(act, "出征容量", capText, () -> {
                try {
                    FormationManagerDialogs.showCapacityDialog(act, cat);
                } catch (Throwable t) {
                    UiStyle.toast(act, "容量信息不可用：" + t.getMessage());
                }
            });
            card.addView(capacityRow);
        }

        // ---------- 自动编队比例入口（网页版 §1.7「比例分配」，合计必须 100%）----------
        int[] auto = FormationStore.automaticSettings(act, fpid, sid, uid);   // T7：uid 兜底目录
        final int[] safeAuto = auto == null || auto.length < 3 ? new int[]{50, 20, 30} : auto;
        card.addView(UiStyle.dropdownRow(act, "自动编队比例",
                safeAuto[0] + ":" + safeAuto[1] + ":" + safeAuto[2]
                        + " · 出征时按实时最大容量分配",
                () -> FormationRatioDialog.show(act, safeAuto,
                        cat != null && cat.automaticAvailable(),
                        (inf, lanc, mark) -> {
                            FormationStore.saveAutomaticSettings(act, fpid, sid, inf, lanc, mark);
                            try {
                                VaultSync.onAccountsChanged(act);
                            } catch (Throwable ignored) {
                            }
                            fill(act, fpid, sid, uid, fnId, card, onChanged);
                            if (onChanged != null) onChanged.run();
                            UiStyle.toast(act, "自动编队比例已保存为 "
                                    + FormationRatioDialog.ratioCode(inf, lanc, mark));
                        })));

        // ---------- 无编队引导（仍可点，直接进管理页新建）----------
        if (lib.isEmpty() && (games == null || games.isEmpty())) {
            TextView create = text(act, "＋ 新建第一支编队", 12.5f, UiStyle.BRAND_TEXT, true);
            create.setPadding(0, dp(act, 8), 0, 0);
            UiStyle.press(create);
            create.setOnClickListener(v -> {
                try {
                    com.carlos.vault.daily.FormationManagerActivity.launchNew(act, fpid, sid, uid, fnId);
                } catch (Throwable t) {
                    UiStyle.toast(act, "打开编队管理失败：" + t.getMessage());
                }
            });
            card.addView(create);
        }
    }

    /** 当前功能绑定摘要：`使用编队 #1 采集队` / `自动编队 · 50/20/30 · 总量按容量`。 */
    private static View bindingSummary(Activity act, String fpid, long sid, String uid,
                                       String fnId, List<FormationModel> lib) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        String mode = FormationStore.fnMode(act, fpid, sid, fnId);
        boolean role = FormationStore.MODE_ROLE.equals(mode);
        TextView line = text(act, "", 11.5f, UiStyle.INK2, false);
        if (role) {
            long id = FormationStore.fnFormationId(act, fpid, sid, fnId);
            FormationModel f = id > 0 ? FormationStore.formation(act, fpid, sid, id) : null;
            String value;
            if (id <= 0) {
                value = "尚未选择编队";
            } else if (f == null) {
                value = "编队已删除，请重选";
            } else {
                // 2026-11-08（用户口径）：只展示编队名字，不展示 #9100000003 之类内部编号。
                String fname = f.name == null ? "" : f.name.trim();
                if (fname.isEmpty()) fname = "自定义编队";
                value = "英雄 " + f.heroIds.size() + " 名 · " + soldiersOf(f)
                        + " · 来源=" + fname;
            }
            line.setText(value);
        } else {
            int[] ratios = FormationStore.automaticSettings(act, fpid, sid, uid);   // T7：uid 兜底目录
            int[] safe = ratios == null || ratios.length < 3 ? new int[]{50, 20, 30} : ratios;
            line.setText("自动编队 · " + safe[0] + "/" + safe[1] + "/" + safe[2]
                    + " · 出征时按实时最大容量自动分配盾、矛、弓");
        }
        box.addView(line);
        return box;
    }

    /** 网页版 §1.1 状态行四文案 + 支编队计数。 */
    static String statusText(boolean ready, FormationCatalogStore cat, int total,
                             int custom, int games) {
        if (!ready) return "等待角色同步";
        if (cat == null || !cat.configured()) {
            String msg = "";
            try {
                msg = cat == null ? "" : cat.marchCapacityMessage().optString("message", "");
            } catch (Throwable ignored) {
            }
            if (msg == null || msg.isEmpty()) msg = "等待登录容量状态同步";
            return msg;
        }
        if (total == 0) return "容量已就绪，可同步游戏编队或创建自定义编队";
        return games + " 支游戏编队 · " + custom + " 支自定义编队";
    }

    /** 自定义编队数（排除游戏编队与巨熊编队）。 */
    private static int countCustom(List<FormationModel> lib) {
        int n = 0;
        if (lib == null) return 0;
        for (FormationModel f : lib) {
            if (f == null || f.isGame()) continue;
            if (f.builtIn || f.templateKey != null) continue;   // 内置模板不算“自定义”
            n++;
        }
        return n;
    }

    private static String soldiersOf(FormationModel f) {
        if (f == null) return "—";
        if ("PERCENTAGE".equals(f.allocationMode)) {
            return "比例" + f.infantryRatio + ":" + f.lancerRatio + ":" + f.marksmanRatio
                    + (f.totalSoldiers > 0 ? " 总量" + FormationRules.fmt(f.totalSoldiers) : " 总量按容量");
        }
        return "盾" + FormationRules.fmt(f.infantry) + " 矛" + FormationRules.fmt(f.lancer)
                + " 弓" + FormationRules.fmt(f.marksman);
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
