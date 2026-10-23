package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 批次10（2026-10-23）：统一编队选择/编辑 UI（代码构建，无 XML，复用 {@link UiStyle}）。
 *
 * <p>交互（1:1 参考网页版 DailyRoleFormation* / DailyFormationConfigSupport）：
 * <ul>
 *   <li>{@link #buildCard}：功能页顶部「编队」卡片 —— 自动/指定编队、编队下拉、
 *       摘要行（英雄 N 名 · 盾x 矛y 弓z · 来源=指定#1/自动）、自动比例、管理编队入口；</li>
 *   <li>{@link #buildBearJoinCard}：巨熊「上车编队 1-6」（每支可独立自动/指定）；</li>
 *   <li>{@link #showLibrary}：编队库列表（新增/编辑/复制/删除）；</li>
 *   <li>{@link #showEditor}：编队编辑（名称、英雄≤5、兵种数量或比例+总量）。</li>
 * </ul>
 *
 * <p>英雄列表来自服务器 state 键 {@code srv:formation:catalog:{fpid}:{sid}}（由
 * {@link FormationStore#catalog} 从 VaultSync 缓存的 daily_state 读）；目录缺失时
 * 降级为手动输入英雄 ID 并提示「目录同步中」。保存后调用 {@link VaultSync#onAccountsChanged}
 * 触发既有同步链路（编队库作为 params.formation_library 上传），不新增任何游戏协议。
 */
public final class FormationPicker {

    /** 数据变更回调（功能页刷新摘要用；可空）。 */
    public interface OnChanged {
        void onChanged();
    }

    private FormationPicker() {
    }

    // ==================== 功能页「编队」卡片 ====================

    /**
     * 队列功能页顶部「编队」卡片（gather/intel/hunt/behemoth/bear/guardian）。
     * 返回的卡片自管理内容（修改后原位重建）。
     */
    public static LinearLayout buildCard(final Activity act, final String fpid, final long sid,
                                         final String fnId, final OnChanged onChanged) {
        final LinearLayout card = UiStyle.outlineCard(act);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 18), dp(act, 10), dp(act, 18), 0);
        card.setLayoutParams(lp);
        final Runnable refresh = () -> fillFormationCard(act, fpid, sid, fnId, card, onChanged);
        refresh.run();
        return card;
    }

    private static void fillFormationCard(final Activity act, final String fpid, final long sid,
                                          final String fnId, final LinearLayout card,
                                          final OnChanged onChanged) {
        card.removeAllViews();
        // 标题行：编队 + 来源徽章 + 「管理编队」
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(act, "编队", 15, UiStyle.INK, true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView manage = text(act, "管理编队 ›", 12, UiStyle.BRAND_TEXT, true);
        UiStyle.press(manage);
        manage.setOnClickListener(v -> showLibrary(act, fpid, sid, () -> {
            if (onChanged != null) onChanged.onChanged();
            fillFormationCard(act, fpid, sid, fnId, card, onChanged);
        }));
        head.addView(manage);
        card.addView(head);

        final String mode = FormationStore.fnMode(act, fpid, sid, fnId);
        TextView modeSub = text(act, FormationStore.MODE_ROLE.equals(mode)
                ? "按指定编队的英雄/兵种出征"
                : "服务器按空闲英雄 + 兵力库存自动分配", 11, UiStyle.INK2, false);
        modeSub.setPadding(0, dp(act, 3), 0, dp(act, 6));
        card.addView(modeSub);

        // 模式分段：自动 / 指定
        final Runnable refresh = () -> fillFormationCard(act, fpid, sid, fnId, card, onChanged);
        card.addView(UiStyle.segmented(act, new String[]{"自动编队", "指定编队"},
                FormationStore.MODE_ROLE.equals(mode) ? 1 : 0, idx -> {
                    String next = idx == 1 ? FormationStore.MODE_ROLE : FormationStore.MODE_AUTOMATIC;
                    FormationStore.saveFnMode(act, fpid, sid, fnId, next);
                    if (idx == 1 && FormationStore.fnFormationId(act, fpid, sid, fnId) <= 0) {
                        // 切到指定但还没选：直接弹编队选择
                        pickFormation(act, fpid, sid, fnId, () -> {
                            sync(act);
                            refresh.run();
                        });
                    }
                    sync(act);
                    refresh.run();
                }));

        if (FormationStore.MODE_ROLE.equals(mode)) {
            long fid = FormationStore.fnFormationId(act, fpid, sid, fnId);
            FormationStore.Formation f = FormationStore.byId(act, fpid, sid, fid);
            String value;
            if (fid <= 0) value = "未选择";
            else if (f == null) value = "#" + fid + "（编队已删除，请重选）";
            else value = "#" + f.id + " " + f.name;
            card.addView(UiStyle.dropdownRow(act, "使用编队", value,
                    () -> pickFormation(act, fpid, sid, fnId, () -> {
                        sync(act);
                        refresh.run();
                    })));
            // 摘要行（批次11：带功能级兵量覆盖）
            long[] override = FormationStore.fnSoldiersOverride(act, fpid, sid, fnId);
            String overrideText = FormationStore.soldiersOverrideText(override);
            TextView sum = text(act, (f == null
                    ? "英雄 -- · 请选择/新建编队"
                    : "英雄 " + f.heroIds.size() + " 名 · " + soldiersOf(f)
                    + " · 来源=指定#" + f.id)
                    + " · " + overrideText, 11.5f, UiStyle.INK2, false);
            sum.setPadding(0, dp(act, 6), 0, 0);
            card.addView(sum);
            // 兵量覆盖（可选）：0/空=用编队库兵量；写入 params soldiers（服务器 resolver 覆盖）
            card.addView(UiStyle.dropdownRow(act, "兵量覆盖（可选）",
                    "未覆盖（用编队库兵量）".equals(overrideText) ? overrideText
                            : overrideText + "（0=用库值）",
                    () -> showSoldiersOverrideDialog(act, fpid, sid, fnId, () -> refresh.run())));
        } else {
            long[] ratio = FormationStore.fnAutoRatio(act, fpid, sid, fnId);
            card.addView(UiStyle.dropdownRow(act, "自动比例",
                    ratio[0] + ":" + ratio[1] + ":" + ratio[2]
                            + " · 总量" + (ratio[3] <= 0 ? "按容量" : String.valueOf(ratio[3])),
                    () -> showAutoRatioDialog(act, fpid, sid, fnId, () -> refresh.run())));
            TextView sum = text(act, "来源=自动 · 服务器按空闲英雄+库存分配（英雄≤5）",
                    11.5f, UiStyle.INK2, false);
            sum.setPadding(0, dp(act, 6), 0, 0);
            card.addView(sum);
        }

        // 目录状态提示（缺失时说明降级策略）
        if (!FormationStore.hasCatalog(act, fpid, sid)) {
            TextView tip = text(act, "英雄目录同步中：可先「管理编队」手动填英雄 ID；同步后自动显示名字/状态",
                    10.5f, UiStyle.WARN_INK, false);
            tip.setPadding(0, dp(act, 6), 0, 0);
            card.addView(tip);
        }

        // 选择/新建入口（库为空时的引导）
        if (FormationStore.list(act, fpid, sid).isEmpty()) {
            TextView create = text(act, "＋ 新建第一支编队", 12.5f, UiStyle.BRAND_TEXT, true);
            create.setPadding(0, dp(act, 8), 0, 0);
            UiStyle.press(create);
            create.setOnClickListener(v -> showEditor(act, fpid, sid, null, () -> {
                sync(act);
                refresh.run();
                if (onChanged != null) onChanged.onChanged();
            }));
            card.addView(create);
        }
    }

    /** 巨熊页「上车编队 1-6」卡片（每支独立：自动/指定；缺省自动）。 */
    public static LinearLayout buildBearJoinCard(final Activity act, final String fpid, final long sid,
                                                 final OnChanged onChanged) {
        final LinearLayout card = UiStyle.outlineCard(act);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 18), dp(act, 10), dp(act, 18), 0);
        card.setLayoutParams(lp);
        final Runnable refresh = () -> fillBearJoinCard(act, fpid, sid, card, onChanged);
        refresh.run();
        return card;
    }

    private static void fillBearJoinCard(final Activity act, final String fpid, final long sid,
                                         final LinearLayout card, final OnChanged onChanged) {
        card.removeAllViews();
        TextView title = text(act, "上车编队 1-6", 15, UiStyle.INK, true);
        card.addView(title);
        TextView sub = text(act, "巨熊集结开始后按序上 1-6 辆车；缺省=自动（沿用默认编队）",
                11, UiStyle.INK2, false);
        sub.setPadding(0, dp(act, 3), 0, dp(act, 4));
        card.addView(sub);
        final Runnable refresh = () -> fillBearJoinCard(act, fpid, sid, card, onChanged);
        for (int j = 1; j <= 6; j++) {
            final int joinId = j;   // lambda 捕获需 effectively final
            JSONObject cfg = FormationStore.joinFormation(act, fpid, sid, joinId);
            String mode = FormationStore.normalizeMode(cfg.optString("mode",
                    FormationStore.MODE_AUTOMATIC));
            long fid = cfg.optLong("formation_id", 0);
            FormationStore.Formation f = fid > 0 ? FormationStore.byId(act, fpid, sid, fid) : null;
            String value;
            if (!FormationStore.MODE_ROLE.equals(mode) || fid <= 0) {
                value = "自动";
            } else if (f == null) {
                value = "#" + fid + "（已删除）";
            } else {
                value = "#" + f.id + " " + f.name;
            }
            final boolean enabled = FormationStore.joinEnabled(cfg);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dd = UiStyle.dropdownRow(act, "上车编队 " + joinId,
                    value + (enabled ? "" : "（已停用）"), () -> showJoinChooser(act, fpid, sid,
                            joinId, () -> {
                                sync(act);
                                refresh.run();
                                if (onChanged != null) onChanged.onChanged();
                            }));
            if (!enabled) dd.setAlpha(.45f);   // 停用行置灰，但仍可点开改配置
            row.addView(dd, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            UiStyle.Switch sw = new UiStyle.Switch(act, enabled, () -> {
                FormationStore.saveJoinEnabled(act, fpid, sid, joinId,
                        !FormationStore.joinEnabled(FormationStore.joinFormation(
                                act, fpid, sid, joinId)));
                sync(act);
                refresh.run();
                if (onChanged != null) onChanged.onChanged();
            });
            row.addView(sw);
            card.addView(row);
        }
    }

    // ==================== 编队选择（功能页用） ====================

    private static void pickFormation(final Activity act, final String fpid, final long sid,
                                      final String fnId, final Runnable onPicked) {
        final List<FormationStore.Formation> list = FormationStore.list(act, fpid, sid);
        final List<String> names = new ArrayList<>();
        for (FormationStore.Formation f : list) {
            names.add("#" + f.id + " " + f.name + " · " + FormationStore.summary(f));
        }
        names.add("＋ 新建编队");
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择编队")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which >= list.size()) {
                        showEditor(act, fpid, sid, null, () -> {
                            if (onPicked != null) onPicked.run();
                        });
                        return;
                    }
                    FormationStore.Formation f = list.get(which);
                    FormationStore.saveFnFormation(act, fpid, sid, fnId, f.id);
                    if (onPicked != null) onPicked.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showAutoRatioDialog(final Activity act, final String fpid, final long sid,
                                            final String fnId, final Runnable onSaved) {
        long[] cur = FormationStore.fnAutoRatio(act, fpid, sid, fnId);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 6));
        LinearLayout ratioRow = new LinearLayout(act);
        ratioRow.setOrientation(LinearLayout.HORIZONTAL);
        ratioRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText inf = numInput(act, "盾%", String.valueOf(cur[0]));
        final EditText lan = numInput(act, "矛%", String.valueOf(cur[1]));
        final EditText mar = numInput(act, "弓%", String.valueOf(cur[2]));
        ratioRow.addView(labeled(act, "盾", inf), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ratioRow.addView(labeled(act, "矛", lan), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ratioRow.addView(labeled(act, "弓", mar), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(ratioRow);
        final JSONObject catalog = FormationStore.catalog(act, fpid, sid);
        final EditText total = numInput(act, "总兵力（0=按容量自动）", String.valueOf(cur[3]));
        box.addView(labeled(act, "总量", total));
        String stock = FormationStore.soldierStockText(catalog);
        long capacity = FormationStore.capacity(catalog);
        StringBuilder tipSb = new StringBuilder("比例之和必须=100；总量 0 表示按服务器容量自动");
        if (capacity > 0) tipSb.append("\n服务器容量 ").append(capacity).append("（总量上限提示）");
        if (!stock.isEmpty()) tipSb.append("\n当前兵种库存：").append(stock);
        List<Long> best = FormationStore.bestSoldierIds(catalog);
        if (!best.isEmpty()) tipSb.append("\n最高兵种：").append(best.toString());
        TextView tip = text(act, tipSb.toString(), 11, UiStyle.INK2, false);
        tip.setPadding(0, dp(act, 8), 0, 0);
        box.addView(tip);
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("自动编队比例")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    int i0 = (int) parseLong(inf, 40), i1 = (int) parseLong(lan, 30),
                            i2 = (int) parseLong(mar, 30);
                    if (i0 < 0 || i1 < 0 || i2 < 0) {
                        UiStyle.toast(act, "比例不能为负数");
                        return;
                    }
                    if (i0 + i1 + i2 != 100) {
                        UiStyle.toast(act, "比例之和必须等于 100（当前 " + (i0 + i1 + i2) + "）");
                        return;
                    }
                    FormationStore.saveFnAutoRatio(act, fpid, sid, fnId, i0, i1, i2,
                            Math.max(0, parseLong(total, 0)));
                    sync(act);
                    if (onSaved != null) onSaved.run();
                    UiStyle.toast(act, "自动编队已保存并同步");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showJoinChooser(final Activity act, final String fpid, final long sid,
                                        final int joinId, final Runnable onSaved) {
        // 批次11：切换编队/自动时保留该槽位的 enabled 状态（停用的槽位不因改配置被重新启用）
        final boolean enabled = FormationStore.joinEnabled(
                FormationStore.joinFormation(act, fpid, sid, joinId));
        final List<FormationStore.Formation> list = FormationStore.list(act, fpid, sid);
        final List<String> names = new ArrayList<>();
        names.add("自动（默认）");
        for (FormationStore.Formation f : list) {
            names.add("#" + f.id + " " + f.name + " · " + FormationStore.summary(f));
        }
        names.add("＋ 新建编队");
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("上车编队 " + joinId)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        FormationStore.saveJoinFormation(act, fpid, sid, joinId,
                                FormationStore.MODE_AUTOMATIC, 0, enabled);
                        if (onSaved != null) onSaved.run();
                        return;
                    }
                    if (which > list.size()) {
                        showEditor(act, fpid, sid, null, () -> {
                            if (onSaved != null) onSaved.run();
                        });
                        return;
                    }
                    FormationStore.Formation f = list.get(which - 1);
                    FormationStore.saveJoinFormation(act, fpid, sid, joinId,
                            FormationStore.MODE_ROLE, f.id, enabled);
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 功能级兵量覆盖弹窗（盾/矛/弓，0/空=用编队库值；写 params soldiers）。 */
    private static void showSoldiersOverrideDialog(final Activity act, final String fpid,
                                                   final long sid, final String fnId,
                                                   final Runnable onSaved) {
        long[] cur = FormationStore.fnSoldiersOverride(act, fpid, sid, fnId);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 6));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText inf = numInput(act, "盾兵（0=库值）", String.valueOf(cur[0]));
        final EditText lan = numInput(act, "矛兵（0=库值）", String.valueOf(cur[1]));
        final EditText mar = numInput(act, "弓兵（0=库值）", String.valueOf(cur[2]));
        row.addView(labeled(act, "盾", inf), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labeled(act, "矛", lan), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labeled(act, "弓", mar), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        TextView tip = text(act, "留空或 0 = 使用编队库兵量；非 0 = 覆盖该功能出征兵量（写入 params.soldiers）",
                11, UiStyle.INK2, false);
        tip.setPadding(0, dp(act, 8), 0, 0);
        box.addView(tip);
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("兵量覆盖（可选）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    long i0 = Math.max(0, parseLong(inf, 0));
                    long i1 = Math.max(0, parseLong(lan, 0));
                    long i2 = Math.max(0, parseLong(mar, 0));
                    FormationStore.saveFnSoldiersOverride(act, fpid, sid, fnId, i0, i1, i2);
                    sync(act);
                    if (onSaved != null) onSaved.run();
                    UiStyle.toast(act, i0 + i1 + i2 <= 0
                            ? "已清除兵量覆盖（使用编队库兵量）" : "兵量覆盖已保存并同步");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 编队库列表（新增/编辑/复制/删除） ====================

    /** 编队库列表弹窗。 */
    public static void showLibrary(final Activity act, final String fpid, final long sid,
                                   final OnChanged onChanged) {
        final List<FormationStore.Formation> list = FormationStore.list(act, fpid, sid);
        ScrollView sc = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        final AlertDialog[] dlgRef = new AlertDialog[1];
        final OnChanged reopen = () -> {
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            if (onChanged != null) onChanged.onChanged();
            showLibrary(act, fpid, sid, onChanged);
        };

        if (list.isEmpty()) {
            box.addView(UiStyle.emptyState(act, "🧩", "还没有编队",
                    "新建一支编队：选英雄、填兵种数量或比例，采集/情报/巨兽/巨熊都会按它出征",
                    "＋ 新建编队", () -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        showEditor(act, fpid, sid, null, reopen);
                    }));
        } else {
            for (final FormationStore.Formation f : list) {
                LinearLayout row = UiStyle.outlineCard(act);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(act, 8);
                row.setLayoutParams(rlp);
                LinearLayout l1 = new LinearLayout(act);
                l1.setOrientation(LinearLayout.HORIZONTAL);
                l1.setGravity(Gravity.CENTER_VERTICAL);
                l1.addView(text(act, "#" + f.id + " " + f.name, 14, UiStyle.INK, true),
                        new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                l1.addView(text(act, f.heroIds.size() + " 英雄", 11, UiStyle.INK2, false));
                row.addView(l1);
                TextView sum = text(act, FormationStore.summary(f)
                        + (FormationStore.validate(f) == null ? "" : " · ⚠配置不完整"), 11,
                        UiStyle.INK2, false);
                sum.setPadding(0, dp(act, 4), 0, dp(act, 6));
                row.addView(sum);
                LinearLayout acts = new LinearLayout(act);
                acts.setOrientation(LinearLayout.HORIZONTAL);
                acts.setGravity(Gravity.END);
                acts.addView(miniBtn(act, "编辑", () -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showEditor(act, fpid, sid, f, reopen);
                }));
                acts.addView(miniBtn(act, "复制", () -> {
                    FormationStore.Formation copy = FormationStore.duplicate(act, fpid, sid, f.id);
                    if (copy == null) {
                        UiStyle.toast(act, "复制失败：源编队不存在");
                        return;
                    }
                    sync(act);
                    reopen.onChanged();
                    UiStyle.toast(act, "已复制为 #" + copy.id + " " + copy.name);
                }));
                acts.addView(miniBtn(act, "删除", () -> UiStyle.showConfirmDialog(act, "删除编队",
                        "#" + f.id + " " + f.name + "\n删除后引用它的功能会提示重新选择。",
                        "删除", UiStyle.ERR_INK, () -> {
                            FormationStore.delete(act, fpid, sid, f.id);
                            sync(act);
                            reopen.onChanged();
                        })));
                row.addView(acts);
                box.addView(row);
            }
            TextView add = text(act, "＋ 新建编队", 13, UiStyle.BRAND_TEXT, true);
            add.setGravity(Gravity.CENTER);
            add.setPadding(0, dp(act, 10), 0, dp(act, 6));
            UiStyle.press(add);
            add.setOnClickListener(v -> {
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                showEditor(act, fpid, sid, null, reopen);
            });
            box.addView(add);
        }
        sc.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("编队库（按角色隔离）")
                .setView(sc)
                .setNegativeButton("关闭", null)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    // ==================== 编队编辑（名称/英雄≤5/数量或比例+总量） ====================

    /** 新建（existing=null）或编辑编队。 */
    public static void showEditor(final Activity act, final String fpid, final long sid,
                                  final FormationStore.Formation existing, final OnChanged onSaved) {
        final FormationStore.Formation base = existing == null
                ? new FormationStore.Formation() : existing.copy();
        ScrollView sc = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));

        // ① 名称
        box.addView(label(act, "编队名称"));
        final EditText nameEt = UiStyle.searchInput(act, "如：采集队 / 巨熊主队");
        nameEt.setText(base.name);
        box.addView(nameEt);

        // ② 英雄（目录可用 → 多选；缺失 → 手动 ID 输入）
        box.addView(label(act, "英雄（1-5 个）"));
        final List<FormationStore.Hero> catalog = FormationStore.parseHeroes(
                FormationStore.catalog(act, fpid, sid));
        final List<Long> picked = new ArrayList<>(base.heroIds);
        final TextView pickedText = text(act, "已选 " + picked.size() + "/" + FormationStore.HERO_MAX,
                11.5f, UiStyle.INK2, false);
        box.addView(pickedText);
        final EditText heroIdsEt;
        if (catalog.isEmpty()) {
            // 降级：手动输入英雄 ID
            heroIdsEt = UiStyle.searchInput(act, "英雄ID，逗号分隔（如 50041,50039）");
            StringBuilder pre = new StringBuilder();
            for (int i = 0; i < base.heroIds.size(); i++) {
                if (i > 0) pre.append(',');
                pre.append(base.heroIds.get(i));
            }
            heroIdsEt.setText(pre.toString());
            box.addView(heroIdsEt);
            TextView tip = text(act, "英雄目录同步中：可先手填英雄 ID 保存；服务器同步目录后自动显示名字/状态",
                    10.5f, UiStyle.WARN_INK, false);
            tip.setPadding(0, dp(act, 4), 0, 0);
            box.addView(tip);
        } else {
            heroIdsEt = null;
            for (final FormationStore.Hero h : catalog) {
                final CheckBox cb = new CheckBox(act);
                cb.setText(h.label());
                cb.setTextSize(12.5f);
                cb.setTextColor(UiStyle.INK);
                cb.setChecked(picked.contains(h.id));
                cb.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) {
                        if (picked.contains(h.id)) return;
                        if (picked.size() >= FormationStore.HERO_MAX) {
                            btn.setChecked(false);
                            UiStyle.toast(act, "英雄最多 " + FormationStore.HERO_MAX + " 个");
                            return;
                        }
                        picked.add(h.id);
                    } else {
                        picked.remove(h.id);
                    }
                    pickedText.setText("已选 " + picked.size() + "/" + FormationStore.HERO_MAX);
                });
                box.addView(cb);
            }
            // 目录里没有的历史英雄（编队来自旧数据）保留可编辑提示
            for (Long id : base.heroIds) {
                boolean known = false;
                for (FormationStore.Hero h : catalog) {
                    if (h.id == id) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    TextView legacy = text(act, "另有历史英雄 ID " + id + "（目录未含，保存时保留）",
                            10.5f, UiStyle.INK2, false);
                    box.addView(legacy);
                }
            }
        }

        // ③ 分配方式：固定数量 / 比例+总量
        box.addView(label(act, "兵力分配"));
        final boolean[] ratioMode = {base.ratioMode || base.totalSoldiers() == 0};
        final LinearLayout countPanel = new LinearLayout(act);
        countPanel.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout ratioPanel = new LinearLayout(act);
        ratioPanel.setOrientation(LinearLayout.VERTICAL);
        final Runnable syncPanels = () -> {
            countPanel.setVisibility(ratioMode[0] ? View.GONE : View.VISIBLE);
            ratioPanel.setVisibility(ratioMode[0] ? View.VISIBLE : View.GONE);
        };
        final JSONObject catalogNow = FormationStore.catalog(act, fpid, sid);
        final long stockInf = FormationStore.soldierStock(catalogNow, "infantry");
        final long stockLan = FormationStore.soldierStock(catalogNow, "lancer");
        final long stockMar = FormationStore.soldierStock(catalogNow, "marksman");
        final EditText infEt = numInput(act, stockHint(stockInf, "盾兵数量"), String.valueOf(base.infantry));
        final EditText lanEt = numInput(act, stockHint(stockLan, "矛兵数量"), String.valueOf(base.lancer));
        final EditText marEt = numInput(act, stockHint(stockMar, "弓兵数量"), String.valueOf(base.marksman));
        countPanel.addView(labeled(act, stockHint(stockInf, "盾兵"), infEt));
        countPanel.addView(labeled(act, stockHint(stockLan, "矛兵"), lanEt));
        countPanel.addView(labeled(act, stockHint(stockMar, "弓兵"), marEt));
        final EditText rInf = numInput(act, "盾%", String.valueOf(base.ratioMode ? base.ratioInfantry : 40));
        final EditText rLan = numInput(act, "矛%", String.valueOf(base.ratioMode ? base.ratioLancer : 30));
        final EditText rMar = numInput(act, "弓%", String.valueOf(base.ratioMode ? base.ratioMarksman : 30));
        final EditText totalEt = numInput(act, "总量（0=按容量自动）", String.valueOf(base.total));
        LinearLayout ratioRow = new LinearLayout(act);
        ratioRow.setOrientation(LinearLayout.HORIZONTAL);
        ratioRow.setGravity(Gravity.CENTER_VERTICAL);
        ratioRow.addView(labeled(act, "盾", rInf), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ratioRow.addView(labeled(act, "矛", rLan), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ratioRow.addView(labeled(act, "弓", rMar), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ratioPanel.addView(ratioRow);
        ratioPanel.addView(labeled(act, "总量", totalEt));
        long capacityNow = FormationStore.capacity(catalogNow);
        String ratioTipText = "比例之和必须=100；总量 0=按服务器容量自动"
                + (capacityNow > 0 ? "\n服务器容量 " + capacityNow + "（总量上限提示）" : "");
        String stockText = FormationStore.soldierStockText(catalogNow);
        if (!stockText.isEmpty()) ratioTipText += "\n当前兵种库存：" + stockText;
        TextView ratioTip = text(act, ratioTipText, 10.5f, UiStyle.INK2, false);
        ratioPanel.addView(ratioTip);
        box.addView(UiStyle.segmented(act, new String[]{"固定数量", "比例+总量"},
                ratioMode[0] ? 1 : 0, idx -> {
                    ratioMode[0] = idx == 1;
                    syncPanels.run();
                }));
        box.addView(countPanel);
        box.addView(ratioPanel);
        syncPanels.run();

        // ④ 保存/取消
        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(existing == null ? "新建编队" : "编辑编队 #" + existing.id)
                .setView(sc)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    FormationStore.Formation f = new FormationStore.Formation();
                    f.id = base.id;
                    f.name = nameEt.getText().toString().trim();
                    if (heroIdsEt != null) {
                        for (String part : heroIdsEt.getText().toString().split("[,，\\s]+")) {
                            try {
                                long id = Long.parseLong(part.trim());
                                if (id > 0 && !f.heroIds.contains(id)) f.heroIds.add(id);
                            } catch (Throwable ignored) {
                            }
                        }
                        // 手动 ID 模式保留历史英雄（目录缺失时不丢旧数据）
                        for (Long id : base.heroIds) {
                            if (!f.heroIds.contains(id)) f.heroIds.add(id);
                        }
                    } else {
                        f.heroIds.addAll(picked);
                        for (Long id : base.heroIds) {
                            if (!f.heroIds.contains(id)) f.heroIds.add(id);
                        }
                    }
                    f.ratioMode = ratioMode[0];
                    if (ratioMode[0]) {
                        f.ratioInfantry = (int) parseLong(rInf, 0);
                        f.ratioLancer = (int) parseLong(rLan, 0);
                        f.ratioMarksman = (int) parseLong(rMar, 0);
                        f.total = Math.max(0, parseLong(totalEt, 0));
                        f.infantry = 0;
                        f.lancer = 0;
                        f.marksman = 0;
                    } else {
                        f.infantry = Math.max(0, parseLong(infEt, 0));
                        f.lancer = Math.max(0, parseLong(lanEt, 0));
                        f.marksman = Math.max(0, parseLong(marEt, 0));
                        f.total = f.totalSoldiers();
                        // 数量模式同步一份比例（服务器展示/参考；按数量出征）
                        long sum = Math.max(1, f.total);
                        f.ratioInfantry = (int) Math.round(f.infantry * 100.0 / sum);
                        f.ratioLancer = (int) Math.round(f.lancer * 100.0 / sum);
                        f.ratioMarksman = 100 - f.ratioInfantry - f.ratioLancer;
                    }
                    String err = FormationStore.validate(f);
                    if (err != null) {
                        UiStyle.toast(act, err);
                        return;
                    }
                    // 批次10：超库存仅提示（不阻止保存）——服务器 FormationResolver 会按库存
                    // 削减或 SKIP；提前告知避免“保存了但不出征”的困惑。
                    if (!ratioMode[0]) {
                        boolean over = (stockInf >= 0 && f.infantry > stockInf)
                                || (stockLan >= 0 && f.lancer > stockLan)
                                || (stockMar >= 0 && f.marksman > stockMar);
                        if (over) {
                            UiStyle.toast(act, "兵力超过当前库存（" + stockText
                                    + "），服务器可能按库存削减/跳过");
                        }
                    }
                    FormationStore.Formation saved = FormationStore.upsert(act, fpid, sid, f);
                    if (saved == null) {
                        UiStyle.toast(act, "保存失败：编队非法");
                        return;
                    }
                    sync(act);
                    if (onSaved != null) onSaved.onChanged();
                    UiStyle.toast(act, "编队 #" + saved.id + " 已保存并同步");
                    dlg.dismiss();
                }));
        dlg.show();
    }

    // ==================== 小工具（排版） ====================

    private static TextView label(Activity act, String s) {
        TextView t = text(act, s, 13, UiStyle.INK, true);
        t.setPadding(0, dp(act, 12), 0, dp(act, 4));
        return t;
    }

    private static TextView text(Activity act, String s, float size, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static EditText numInput(Activity act, String hint, String prefill) {
        EditText et = UiStyle.searchInput(act, hint);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(prefill == null ? "" : prefill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 4);
        et.setLayoutParams(lp);
        return et;
    }

    private static LinearLayout labeled(Activity act, String name, EditText et) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(act, name, 11.5f, UiStyle.INK2, false);
        col.addView(t);
        col.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(act, 6);
        col.setLayoutParams(lp);
        return col;
    }

    private static TextView miniBtn(Activity act, String s, Runnable r) {
        TextView t = text(act, s, 11.5f, UiStyle.BRAND_TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(act, 10), dp(act, 4), dp(act, 10), dp(act, 4));
        t.setBackground(UiStyle.roundRectDp(act, UiStyle.BRAND_50, 999));
        UiStyle.press(t);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(act, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static String soldiersOf(FormationStore.Formation f) {
        if (f.ratioMode) {
            return "比例" + f.ratioInfantry + ":" + f.ratioLancer + ":" + f.ratioMarksman
                    + " 总量" + (f.total <= 0 ? "按容量" : String.valueOf(f.total));
        }
        return "盾" + f.infantry + " 矛" + f.lancer + " 弓" + f.marksman;
    }

    /** 库存提示后缀（库存未知返回空）。 */
    private static String stockHint(long stock, String base) {
        return stock >= 0 ? base + "（库存" + stock + "）" : base;
    }

    private static long parseLong(EditText et, long def) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) return def;
            return Long.parseLong(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Activity act, int v) {
        return (int) (act.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 保存后触发既有同步链路（VaultSync 会把 formation_library 写进该角色 params）。 */
    private static void sync(Activity act) {
        try {
            VaultSync.onAccountsChanged(act);
        } catch (Throwable ignored) {
        }
    }
}
