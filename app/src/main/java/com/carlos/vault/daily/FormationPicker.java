package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.formation.FormationBinding;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.FormationRules;
import com.carlos.vault.daily.formation.ui.FormationBoardView;
import com.carlos.vault.daily.formation.ui.FormationEntryCard;
import com.carlos.vault.daily.formation.ui.FormationManagerDialogs;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 编队选择/入口 UI 门面（T5，1:1 网页版；契约 §4.4）。
 *
 * <p><b>四个旧 public static 方法签名保持不变</b>（外部调用方零改动）：
 * <ol>
 *   <li>{@link #buildCard}：功能页「编队」摘要卡 → 委托 {@link FormationEntryCard}
 *       （摘要 + 「编队管理」入口 + 状态行 + 出征容量入口）；</li>
 *   <li>{@link #buildBearJoinCard}：巨熊「发起队 + 上车编队 1-6」（v2 目录 + 旧 params 双读）；</li>
 *   <li>{@link #pickFormationFor}：队列编队选择器（来源 tab 自动/内置/巨熊/游戏 + 只读
 *       {@link FormationBoardView} + `确认使用`）——旧回调签名不变，另加 v2 重载返回
 *       mode/formationIds/troops；</li>
 *   <li>{@link #showLibrary}：兼容旧调用方 → 打开 {@code FormationManagerActivity}（T4）。</li>
 * </ol>
 *
 * <p>选择器不写编队库（选择结果交回调落库）；巨熊槽位沿用既有 params API。兵量统一走
 * {@link FormationRules#allocateByRatio}。任何状态（角色未同步/目录未就绪/无编队/离线）
 * 都有可见文案与可点入口，不出现空白弹窗（用户实测缺陷 ①）。
 */
public final class FormationPicker {

    /** 数据变更回调（功能页刷新摘要用；可空）。 */
    public interface OnChanged {
        void onChanged();
    }

    /** 旧选择回调（保持签名；formationId<=0 表示"自动编队"）。 */
    public interface OnFormationPicked {
        void onPicked(long formationId, FormationStore.Formation formation);
    }

    /** v2 选择回调：mode + 多编队 id + 三项兵量（ROLE=编队兵量；AUTOMATIC=比例分摊）。 */
    public interface OnFormationPickedDetail {
        void onPicked(String mode, long[] formationIds, long infantry, long lancer,
                      long marksman, int[] ratios);
    }

    /** 来源 tab（与网页版 §1.13 一致）。 */
    private static final String SRC_AUTO = "AUTO";
    private static final String SRC_BUILTIN = "BUILTIN";
    private static final String SRC_BEAR = "BEAR";
    private static final String SRC_GAME = "GAME";
    private static final String[] SRC_LABELS = {"自动编队", "内置编队", "巨熊编队", "游戏编队"};

    private FormationPicker() {
    }

    // ==================== ① 功能页编队摘要卡 ====================

    /**
     * 队列功能页顶部「编队」卡（gather/intel/hunt/behemoth/bear/guardian）。
     * 摘要卡 + 「编队管理」按钮；sid≤0 时自动回退当前角色 sid，仍为 0 则卡片显示「等待角色同步」。
     */
    public static LinearLayout buildCard(final Activity act, final String fpid, final long sid,
                                         final String fnId, final OnChanged onChanged) {
        long useSid = sid > 0 ? sid : activeRoleSid(act, fpid);
        String uid = activeRoleUid(act, fpid);
        final OnChanged cb = onChanged;
        return FormationEntryCard.build(act, fpid, useSid, uid, fnId,
                cb == null ? null : cb::onChanged);
    }

    // ==================== ② 巨熊：发起队 + 上车编队 1-6 ====================

    /** 巨熊页「发起队 + 上车编队 1-6」卡片（每支独立：自动/指定 + 启停；模板入口进管理页）。 */
    public static LinearLayout buildBearJoinCard(final Activity act, final String fpid,
                                                 final long sid, final OnChanged onChanged) {
        final long useSid = sid > 0 ? sid : activeRoleSid(act, fpid);
        final String uid = activeRoleUid(act, fpid);
        final LinearLayout card = UiStyle.outlineCard(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 18), dp(act, 10), dp(act, 18), 0);
        card.setLayoutParams(lp);
        fillBearJoinCard(act, fpid, useSid, uid, card, onChanged);
        return card;
    }

    private static void fillBearJoinCard(final Activity act, final String fpid, final long sid,
                                         final String uid, final LinearLayout card,
                                         final OnChanged onChanged) {
        card.removeAllViews();
        final boolean ready = fpid != null && !fpid.trim().isEmpty() && sid > 0;
        final FormationCatalogStore cat = ready
                ? FormationStore.catalogOf(act, fpid, sid, uid) : null;   // T7：uid 感知目录
        final Runnable refresh = () -> fillBearJoinCard(act, fpid, sid, uid, card, onChanged);

        card.addView(text(act, "巨熊编队", 15, UiStyle.INK, true));
        TextView sub = text(act, "发起队 + 上车 1-6：每支可独立设为自动或指定编队", 11,
                UiStyle.INK2, false);
        sub.setPadding(0, dp(act, 3), 0, dp(act, 6));
        card.addView(sub);

        if (!ready) {
            TextView wait = text(act, "等待角色同步：请在账号页选择角色后返回本页", 11,
                    UiStyle.WARN_INK, false);
            card.addView(wait);
            return;
        }

        // 模板状态 + 模板入口（模板应用/一键编队由 T1/T4 提供，这里只做入口）
        boolean applied = false;
        String bearText;
        try {
            JSONObject bear = cat.bearBinding();
            applied = bear != null && bear.optBoolean("applied", false);
            if (applied) {
                String title = bear.optString("title", "");
                int gen = bear.optInt("generation", 0);
                String tier = bear.optString("tier", "");
                bearText = "已应用：" + title + "（" + gen + " 代"
                        + (tier.isEmpty() ? "" : " · " + tier) + "）";
            } else {
                bearText = "尚未选择模板；应用后自动生成 1 支开车队与 6 支上车队（盾 5% / 矛 5% / 弓 90%）";
            }
        } catch (Throwable t) {
            bearText = "尚未选择模板；应用后自动生成 1 支开车队与 6 支上车队（盾 5% / 矛 5% / 弓 90%）";
        }
        TextView bearStatus = text(act, bearText, 11, applied ? UiStyle.OK_INK : UiStyle.INK2, false);
        bearStatus.setPadding(0, 0, 0, dp(act, 6));
        card.addView(bearStatus);
        card.addView(UiStyle.dropdownRow(act, "巨熊编队模板", applied ? "更换模板" : "选择模板",
                () -> openManager(act, fpid, sid, uid, "bear")));

        // 发起队（沿用 bear 功能 params 的 formation_mode/formation_id）
        String bearMode = FormationStore.fnMode(act, fpid, sid, "bear");
        long bearId = FormationStore.fnFormationId(act, fpid, sid, "bear");
        String bearValue = FormationStore.MODE_ROLE.equals(bearMode) && bearId > 0
                ? "#" + bearId + " " + nameOf(act, fpid, sid, bearId) : "自动";
        card.addView(UiStyle.dropdownRow(act, "发起队", bearValue,
                () -> pickFormationFor(act, fpid, sid, uid, null,
                        (mode, ids, inf, lanc, mark, ratios) -> {
                            if (FormationStore.MODE_ROLE.equals(mode) && ids.length > 0) {
                                FormationStore.saveFnMode(act, fpid, sid, "bear", FormationStore.MODE_ROLE);
                                FormationStore.saveFnFormation(act, fpid, sid, "bear", ids[0]);
                                FormationStore.saveFnSoldiersOverride(act, fpid, sid, "bear", inf, lanc, mark);
                            } else {
                                FormationStore.saveFnMode(act, fpid, sid, "bear", FormationStore.MODE_AUTOMATIC);
                                FormationStore.saveFnAutoRatio(act, fpid, sid, "bear",
                                        ratios[0], ratios[1], ratios[2], 0);
                            }
                            sync(act);
                            refresh.run();
                            if (onChanged != null) onChanged.onChanged();
                        })));

        // 上车 1-6
        for (int j = 1; j <= 6; j++) {
            final int joinId = j;
            JSONObject cfg = FormationStore.joinFormation(act, fpid, sid, joinId);
            String mode = FormationStore.normalizeMode(cfg.optString("mode",
                    FormationStore.MODE_AUTOMATIC));
            long fid = cfg.optLong("formation_id", 0);
            final boolean enabled = FormationStore.joinEnabled(cfg);
            String value;
            if (!FormationStore.MODE_ROLE.equals(mode) || fid <= 0) {
                value = "自动";
            } else {
                value = "#" + fid + " " + nameOf(act, fpid, sid, fid);
            }
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dd = UiStyle.dropdownRow(act, "上车编队 " + joinId,
                    value + (enabled ? "" : "（已停用）"),
                    () -> pickFormationFor(act, fpid, sid, uid, null,
                            (mode2, ids, inf, lanc, mark, ratios) -> {
                                boolean cur = FormationStore.joinEnabled(
                                        FormationStore.joinFormation(act, fpid, sid, joinId));
                                if (FormationStore.MODE_ROLE.equals(mode2) && ids.length > 0) {
                                    FormationStore.saveJoinFormation(act, fpid, sid, joinId,
                                            FormationStore.MODE_ROLE, ids[0], cur);
                                } else {
                                    FormationStore.saveJoinFormation(act, fpid, sid, joinId,
                                            FormationStore.MODE_AUTOMATIC, 0, cur);
                                }
                                sync(act);
                                refresh.run();
                                if (onChanged != null) onChanged.onChanged();
                            }));
            if (!enabled) dd.setAlpha(.45f);
            row.addView(dd, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(new UiStyle.Switch(act, enabled, () -> {
                FormationStore.saveJoinEnabled(act, fpid, sid, joinId, !enabled);
                sync(act);
                refresh.run();
                if (onChanged != null) onChanged.onChanged();
            }));
            card.addView(row);
        }
    }

    // ==================== ③ 队列编队选择器 ====================

    /** 旧签名（保持）：确认后回调所选编队；AUTOMATIC 回调 formationId=0、formation=null。 */
    public static void pickFormationFor(final Activity act, final String fpid, final long sid,
                                        final OnFormationPicked cb) {
        final long useSid = sid > 0 ? sid : activeRoleSid(act, fpid);
        final String uid = activeRoleUid(act, fpid);
        pickFormationFor(act, fpid, useSid, uid, null,
                (mode, ids, inf, lanc, mark, ratios) -> {
                    if (cb == null) return;
                    if (FormationStore.MODE_ROLE.equals(mode) && ids.length > 0) {
                        cb.onPicked(ids[0], FormationStore.byId(act, fpid, sid, ids[0]));
                    } else {
                        cb.onPicked(0L, null);
                    }
                });
    }

    /**
     * v2 选择器（1:1 §1.13）：来源 tab（自动编队/内置编队/巨熊编队/游戏编队）+ 只读
     * {@link FormationBoardView} + `确认使用`。
     *
     * @param disabledIds 不可选编队 id（多队列互斥用，可空）
     */
    public static void pickFormationFor(final Activity act, final String fpid, final long sid,
                                        final String uid, final List<Long> disabledIds,
                                        final OnFormationPickedDetail cb) {
        if (act == null) return;
        if (fpid == null || fpid.trim().isEmpty() || sid <= 0) {
            UiStyle.toast(act, "等待角色同步，请先选择角色");
            return;
        }
        final FormationCatalogStore cat = FormationStore.catalogOf(act, fpid, sid, uid);   // T7：uid 优先
        // T4 便利 API：把目录装进编队板（英雄等级/星级/职业/队长展示用；不装则回落静态表）
        try {
            FormationBoardView.installCatalog(cat);
        } catch (Throwable ignored) {
        }
        // 内置编队懒创建（幂等；容量+目录就绪才建 8 个，否则仅 2 个基础内置）
        try {
            FormationStore.ensureBuiltIns(act, fpid, sid, uid);   // T7：按角色目录懒填内置
        } catch (Throwable ignored) {
        }
        final List<FormationModel> lib = FormationStore.formations(act, fpid, sid);
        final List<FormationModel> games = cat.gameFormations();
        final List<Long> disabled = disabledIds == null
                ? new ArrayList<Long>() : disabledIds;

        final String[] source = {SRC_AUTO};
        final long[] selectedId = {0L};
        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));

        final AlertDialog[] dlgRef = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];
        final PickerHost host = new PickerHost(act, fpid, sid, uid, cat, lib, games, source,
                selectedId, disabled, rebuild, dlgRef, cb);
        rebuild[0] = () -> {
            body.removeAllViews();
            header(act, body, source, host, rebuild);
            boolean boardOk = false;
            try {
                FormationBoardView board = new FormationBoardView(act, host);
                body.addView(board.view());
                board.render();
                boardOk = true;
            } catch (Throwable t) {
                // Board 未就绪/渲染异常时降级为简易列表，保证弹窗不为空（缺陷 ①）
                body.addView(fallbackList(act, host, rebuild));
            }
            if (!boardOk) {
                // 降级态才自绘 banner + 确认按钮（Board 正常时自带 banner/主按钮，避免双按钮）
                body.addView(confirmBar(act, host, dlgRef));
            }
        };
        resetSelection(host, source, selectedId);
        rebuild[0].run();

        ScrollView scroll = new ScrollView(act);
        scroll.addView(body);
        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(scroll)
                .setNegativeButton("取消", null)
                .create();
        dlgRef[0] = dlg;
        dlg.show();
    }

    /** 头部：标题 + 来源 tab。 */
    private static void header(final Activity act, LinearLayout body, final String[] source,
                               final PickerHost host, final Runnable[] rebuild) {
        // 标题行：右侧给「＋ 新建」「编队管理」入口（用户反馈：选择器里只有选择，没有新增入口）
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(act, "选择编队", 15, UiStyle.INK, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView addBtn = text(act, "＋ 新建", 12, UiStyle.BRAND_TEXT, true);
        UiStyle.press(addBtn);
        titleRow.addView(addBtn);
        TextView manageBtn = text(act, "编队管理 ›", 12, UiStyle.BRAND_TEXT, true);
        UiStyle.press(manageBtn);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(act, 10);
        titleRow.addView(manageBtn, mlp);
        titleRow.setPadding(0, 0, 0, dp(act, 6));
        body.addView(titleRow);
        addBtn.setOnClickListener(v -> {
            if (host.dlgRef[0] != null) host.dlgRef[0].dismiss();
            openManagerNew(act, host.fpid, host.sid, host.uid);
        });
        manageBtn.setOnClickListener(v -> {
            if (host.dlgRef[0] != null) host.dlgRef[0].dismiss();
            openManager(act, host.fpid, host.sid, host.uid, "mine");
        });
        body.addView(UiStyle.segmented(act, SRC_LABELS, sourceIndex(source[0]), idx -> {
            source[0] = indexSource(idx);
            host.tabKey = SRC_AUTO.equals(source[0]) ? SRC_AUTO : "";
            host.selectedId[0] = 0L;
            if (!SRC_AUTO.equals(source[0])) {
                List<FormationModel> list = host.listOf(source[0]);
                if (!list.isEmpty()) {
                    host.tabKey = host.keyOf(list.get(0));
                    host.selectedId[0] = list.get(0).id;
                }
            }
            rebuild[0].run();
        }));
        // 游戏编队只读镜像：编辑 = 生成自定义副本（T1 copyGameFormation）
        if (SRC_GAME.equals(source[0]) && host.selectedId[0] < 0) {
            body.addView(UiStyle.dropdownRow(act, "游戏编队", "编辑副本 →", () -> {
                FormationModel copy = FormationStore.copyGameFormation(act, host.fpid, host.sid,
                        host.uid, host.selectedId[0]);   // T7：uid 感知游戏编队镜像
                if (copy == null) {
                    UiStyle.toast(act, "复制失败：编队不存在");
                    return;
                }
                UiStyle.toast(act, FormationRules.MSG_GAME_READONLY);
                if (host.dlgRef[0] != null) host.dlgRef[0].dismiss();
                openManager(act, host.fpid, host.sid, host.uid, "mine");
            }));
        }
    }

    /** 初始选中：自动 tab；否则该来源第一支编队。 */
    private static void resetSelection(PickerHost host, String[] source, long[] selectedId) {
        if (SRC_AUTO.equals(source[0])) {
            host.tabKey = SRC_AUTO;
            selectedId[0] = 0L;
            return;
        }
        List<FormationModel> list = host.listOf(source[0]);
        host.tabKey = list.isEmpty() ? "" : host.keyOf(list.get(0));
        selectedId[0] = list.isEmpty() ? 0L : list.get(0).id;
    }

    /** 只读 Board 不可用时的降级列表（保证选择器永远可操作）。 */
    private static View fallbackList(final Activity act, final PickerHost host,
                                     final Runnable[] rebuild) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (SRC_AUTO.equals(host.source[0])) {
            String sub = host.capacity() > 0
                    ? "出征时按实时最大容量自动分配盾、矛、弓（上限 " +
                    FormationRules.fmt(host.capacity()) + "）"
                    : "出征时按实时最大容量自动分配盾、矛、弓";
            box.addView(UiStyle.emptyState(act, "⚡", "自动编队", sub, null, null));
            return box;
        }
        List<FormationModel> list = host.listOf(host.source[0]);
        if (list.isEmpty()) {
            String empty = SRC_GAME.equals(host.source[0])
                    ? "暂未同步到游戏内编队；请先登录游戏并保存编队"
                    : "还没有编队；可到「编队管理」创建自定义编队";
            box.addView(UiStyle.emptyState(act, "🧩", "暂无编队", empty, "＋ 新建编队",
                    () -> openManagerNew(act, host.fpid, host.sid, host.uid)));
            return box;
        }
        for (final FormationModel f : list) {
            box.addView(UiStyle.dropdownRow(act, host.labelOf(f), host.badgeOf(f), () -> {
                host.selectedId[0] = f.id;
                host.tabKey = host.keyOf(f);
                rebuild[0].run();
            }));
        }
        return box;
    }

    /** 底部：banner + 确认使用。 */
    private static View confirmBar(final Activity act, final PickerHost host,
                                   final AlertDialog[] dlgRef) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(act, 6), 0, dp(act, 2));
        TextView banner = text(act, host.bannerText(), 11,
                host.bannerTone() == 2 ? UiStyle.WARN_INK : UiStyle.INK2, false);
        banner.setPadding(0, dp(act, 2), 0, dp(act, 6));
        box.addView(banner);
        TextView primary = text(act, host.primaryEnabled() ? "确认使用" : "确认使用", 13,
                UiStyle.WHITE, true);
        primary.setGravity(Gravity.CENTER);
        primary.setPadding(0, dp(act, 11), 0, dp(act, 11));
        primary.setBackground(UiStyle.roundRectDp(act,
                host.primaryEnabled() ? UiStyle.BRAND : UiStyle.LINE_STRONG, 12));
        primary.setOnClickListener(v -> host.doConfirm());
        box.addView(primary);
        return box;
    }

    // ==================== ③-b 触发器（网页版 §1.13 role-picker-trigger） ====================

    /**
     * 来源描述（网页版 `sa`）：AUTOMATIC→`自动编队`；所选全为内置→`内置编队`；否则`角色编队`。
     */
    public static String triggerSource(String mode, List<FormationModel> lib, long[] ids) {
        if (!FormationStore.MODE_ROLE.equals(FormationStore.normalizeMode(mode))) {
            return "自动编队";
        }
        if (ids == null || ids.length == 0) return "角色编队";
        boolean allBuiltIn = true;
        for (long id : ids) {
            FormationModel f = find(lib, id);
            if (f == null || !f.builtIn) {
                allBuiltIn = false;
                break;
            }
        }
        return allBuiltIn ? "内置编队" : "角色编队";
    }

    /**
     * 摘要（网页版 `K`）：AUTOMATIC→`自动盾矛弓 · 全局比例 · 实时最大容量`；ROLE→编队名
     * （`、` 连接）；未选→`尚未选择编队`；库为空→`尚未同步或创建编队`。
     */
    public static String triggerSummary(String mode, List<FormationModel> lib, long[] ids) {
        if (!FormationStore.MODE_ROLE.equals(FormationStore.normalizeMode(mode))) {
            return "自动盾矛弓 · 全局比例 · 实时最大容量";
        }
        if (ids == null || ids.length == 0) {
            return lib == null || lib.isEmpty() ? "尚未同步或创建编队" : "尚未选择编队";
        }
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            FormationModel f = find(lib, id);
            String name = f == null ? ("#" + id + "（已删除）")
                    : (f.name == null || f.name.isEmpty() ? ("编队 " + f.id) : f.name);
            if (sb.length() > 0) sb.append('、');
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * 触发器行：`{来源描述小字} {编队名粗体} 选择编队 ›`（点击打开来源选择器）。
     *
     * @param automaticRatios 自动编队比例（仅摘要展示用，可空）
     */
    public static LinearLayout buildTrigger(Activity act, String mode, List<FormationModel> lib,
                                            long[] ids, int[] automaticRatios, Runnable onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
        row.setBackground(UiStyle.roundRectStroke(act, UiStyle.SURFACE_2, 12, UiStyle.LINE));

        boolean auto = !FormationStore.MODE_ROLE.equals(FormationStore.normalizeMode(mode));
        LinearLayout textBox = new LinearLayout(act);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView small = text(act, triggerSource(mode, lib, ids), 10.5f, UiStyle.INK2, false);
        String summary = triggerSummary(mode, lib, ids);
        if (auto && automaticRatios != null && automaticRatios.length >= 3) {
            summary = "自动 " + automaticRatios[0] + "/" + automaticRatios[1] + "/"
                    + automaticRatios[2] + " · 实时最大容量";
        }
        TextView strong = text(act, summary, 12.5f, UiStyle.INK, true);
        strong.setSingleLine(true);
        strong.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(small);
        textBox.addView(strong);
        row.addView(textBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView action = text(act, "选择编队 ›", 11.5f, UiStyle.BRAND_TEXT, true);
        row.addView(action);
        // aria：网页版 `选择出征编队，当前为{...}`
        row.setContentDescription("选择出征编队，当前为" + summary);
        UiStyle.press(row);
        row.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return row;
    }

    private static FormationModel find(List<FormationModel> lib, long id) {
        if (lib == null) return null;
        for (FormationModel f : lib) {
            if (f != null && f.id == id) return f;
        }
        return null;
    }

    // ==================== ④ showLibrary 兼容 ====================

    /** 兼容旧调用方：直接打开编队管理页（T4）。 */
    public static void showLibrary(final Activity act, final String fpid, final long sid,
                                   final OnChanged onChanged) {
        long useSid = sid > 0 ? sid : activeRoleSid(act, fpid);
        openManager(act, fpid, useSid, activeRoleUid(act, fpid), null);
        if (onChanged != null) onChanged.onChanged();
    }

    /** 打开编队管理并直达「新建编队」编辑器。 */
    private static void openManagerNew(Activity act, String fpid, long sid, String uid) {
        if (sid <= 0 || fpid == null || fpid.trim().isEmpty()) {
            UiStyle.toast(act, "等待角色同步，请先选择角色");
            return;
        }
        try {
            FormationManagerActivity.launchNew(act, fpid, sid, uid, null);
        } catch (Throwable t) {
            UiStyle.toast(act, "打开编队管理失败：" + t.getMessage());
        }
    }

    private static void openManager(Activity act, String fpid, long sid, String uid, String focusFnId) {
        if (sid <= 0 || fpid == null || fpid.trim().isEmpty()) {
            UiStyle.toast(act, "等待角色同步，请先选择角色");
            return;
        }
        try {
            FormationManagerActivity.launch(act, fpid, sid, uid, focusFnId);
        } catch (Throwable t) {
            UiStyle.toast(act, "打开编队管理失败：" + t.getMessage());
        }
    }

    // ==================== 选择器 Host（只读 Board） ====================

    /** 只读 Board 的 Host 实现（契约 §4.2 冻结接口）。 */
    private static final class PickerHost implements FormationBoardView.Host {
        final Activity act;
        final String fpid;
        final long sid;
        final String uid;
        final FormationCatalogStore cat;
        final List<FormationModel> lib;
        final List<FormationModel> games;
        final String[] source;
        final long[] selectedId;
        final List<Long> disabled;
        final Runnable[] rebuild;
        final AlertDialog[] dlgRef;
        final OnFormationPickedDetail cb;
        String tabKey = SRC_AUTO;

        PickerHost(Activity act, String fpid, long sid, String uid, FormationCatalogStore cat,
                   List<FormationModel> lib, List<FormationModel> games, String[] source,
                   long[] selectedId, List<Long> disabled, Runnable[] rebuild,
                   AlertDialog[] dlgRef, OnFormationPickedDetail cb) {
            this.act = act;
            this.fpid = fpid;
            this.sid = sid;
            this.uid = uid == null ? "" : uid;
            this.cat = cat;
            this.lib = lib == null ? new ArrayList<FormationModel>() : lib;
            this.games = games == null ? new ArrayList<FormationModel>() : games;
            this.source = source;
            this.selectedId = selectedId;
            this.disabled = disabled;
            this.rebuild = rebuild;
            this.dlgRef = dlgRef;
            this.cb = cb;
        }

        // ---------- 数据 ----------

        List<FormationModel> listOf(String src) {
            List<FormationModel> out = new ArrayList<>();
            if (SRC_GAME.equals(src)) {
                out.addAll(games);
            } else if (SRC_BEAR.equals(src)) {
                for (FormationModel f : lib) if (isBear(f)) out.add(f);
            } else if (SRC_BUILTIN.equals(src)) {
                for (FormationModel f : lib) if (!isBear(f) && !f.isGame()) out.add(f);
            }
            return out;
        }

        FormationModel byId(long id) {
            for (FormationModel f : lib) if (f.id == id) return f;
            for (FormationModel f : games) if (f.id == id) return f;
            return null;
        }

        String keyOf(FormationModel f) {
            return f.id < 0 ? ("G" + Math.abs(f.id)) : ("C" + f.id);
        }

        String labelOf(FormationModel f) {
            if (f.id < 0) {
                return "G" + (Math.abs(f.id) < 10 ? "0" : "") + Math.abs(f.id);
            }
            return f.name == null || f.name.isEmpty() ? ("编队 " + f.id) : f.name;
        }

        String badgeOf(FormationModel f) {
            if (f.isGame()) return f.name == null || f.name.isEmpty() ? "游戏" : f.name;
            if (f.builtIn) return "内置";
            return "自定义";
        }

        String mode() {
            return SRC_AUTO.equals(source[0])
                    ? FormationStore.MODE_AUTOMATIC : FormationStore.MODE_ROLE;
        }

        // ---------- FormationBoardView.Host ----------

        @Override
        public String boardKey() {
            return "picker:" + source[0] + ":" + tabKey;
        }

        @Override
        public List<FormationBoardView.BoardTab> tabs() {
            List<FormationBoardView.BoardTab> out = new ArrayList<>();
            if (SRC_AUTO.equals(source[0])) {
                int[] r = automaticRatios();
                out.add(new FormationBoardView.BoardTab(SRC_AUTO, "自动盾矛弓",
                        FormationRules.ratioCode(r[0], r[1], r[2]), 0L));
                return out;
            }
            for (FormationModel f : listOf(source[0])) {
                out.add(new FormationBoardView.BoardTab(keyOf(f), labelOf(f), badgeOf(f), 0L));
            }
            return out;
        }

        @Override
        public String selectedTab() {
            return tabKey;
        }

        @Override
        public FormationModel formation() {
            if (SRC_AUTO.equals(source[0])) return null;
            return byId(selectedId[0]);
        }

        @Override
        public boolean automatic() {
            return SRC_AUTO.equals(source[0]);
        }

        @Override
        public long capacity() {
            if (automatic()) {
                return cat == null ? 0L : cat.capacity();
            }
            FormationModel f = formation();
            if (cat == null) return 0L;
            return f == null ? cat.capacity() : cat.capacityFor(f.heroIds);
        }

        @Override
        public long[] capacityLimits() {
            return cat == null ? new long[]{0, 0, 0, 0} : cat.limits();
        }

        @Override
        public String bannerText() {
            if (automatic()) {
                if (cat == null || !cat.automaticAvailable()) return "容量未同步，暂不可用自动编队";
                return FormationRules.MSG_AUTO_CAPACITY;
            }
            FormationModel f = formation();
            if (f == null) return FormationBinding.selectionBanner(0, 1);
            if (disabled.contains(f.id)) return "该编队已被其它队列占用，请重新选择";
            String away = FormationRules.availabilityWarning(f, cat);
            if (away != null && !away.isEmpty()) return away;
            return FormationRules.MSG_ALL_GOOD;
        }

        @Override
        public int bannerTone() {
            if (automatic()) return cat != null && cat.automaticAvailable() ? 1 : 2;
            FormationModel f = formation();
            if (f == null || disabled.contains(f.id)) return 2;
            return f.allHeroesAvailable ? 0 : 2;
        }

        @Override
        public boolean editable() {
            return false;
        }

        @Override
        public boolean heroesInteractive() {
            return false;
        }

        @Override
        public boolean secondaryActions() {
            return false;
        }

        @Override
        public String primaryLabel() {
            return "确认使用";
        }

        @Override
        public String primaryHint() {
            if (automatic()) {
                int[] r = automaticRatios();
                return FormationRules.ratioCode(r[0], r[1], r[2]);
            }
            return (selectedId[0] > 0 ? "1" : "0") + "/1 支";
        }

        @Override
        public boolean primaryEnabled() {
            if (automatic()) return cat != null && cat.automaticAvailable();
            return selectedId[0] > 0 && !disabled.contains(selectedId[0]);
        }

        @Override
        public int[] automaticRatios() {
            try {
                int[] r = cat == null ? null : cat.automaticSettings();
                if (r != null && r.length >= 3 && r[0] + r[1] + r[2] == 100) return r;
            } catch (Throwable ignored) {
            }
            try {
                int[] r = FormationStore.automaticSettings(act, fpid, sid, uid);   // T7：uid 兜底目录
                if (r != null && r.length >= 3 && r[0] + r[1] + r[2] == 100) return r;
            } catch (Throwable ignored) {
            }
            return new int[]{50, 20, 30};
        }

        @Override
        public void onTabSelected(String key) {
            tabKey = key;
            if (SRC_AUTO.equals(source[0])) {
                selectedId[0] = 0L;
            } else {
                for (FormationModel f : listOf(source[0])) {
                    if (keyOf(f).equals(key)) {
                        selectedId[0] = f.id;
                        break;
                    }
                }
            }
            rebuild[0].run();
        }

        @Override
        public void onHeroSlot(int professionType) {
        }

        @Override
        public void onHeroLongPress(long heroId) {
        }

        @Override
        public void onCaptainToggle(long heroId) {
        }

        @Override
        public void onTroopChanged(String key, long value) {
        }

        @Override
        public void onRatioChanged(String key, int pct) {
        }

        @Override
        public void onSecondaryAction(String action) {
        }

        @Override
        public void onPrimary() {
            doConfirm();
        }

        /** 确认使用：ROLE 回传编队兵量；AUTOMATIC 回传比例分摊到实时容量。 */
        void doConfirm() {
            if (!primaryEnabled()) {
                UiStyle.toast(act, bannerText());
                return;
            }
            if (FormationStore.MODE_ROLE.equals(mode())) {
                FormationModel f = byId(selectedId[0]);
                if (f == null) {
                    UiStyle.toast(act, "请选择一支编队");
                    return;
                }
                long[] ids = new long[]{f.id};
                int[] ratios = new int[]{f.infantryRatio, f.lancerRatio, f.marksmanRatio};
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                if (cb != null) {
                    cb.onPicked(FormationStore.MODE_ROLE, ids,
                            f.infantry, f.lancer, f.marksman, ratios);
                }
            } else {
                int[] ratios = automaticRatios();
                long[] troops = FormationRules.allocateByRatio(
                        Math.max(0, capacity()), ratios[0], ratios[1], ratios[2]);
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                if (cb != null) {
                    cb.onPicked(FormationStore.MODE_AUTOMATIC, new long[0],
                            troops[0], troops[1], troops[2], ratios);
                }
            }
        }

        @Override
        public void onCapacityDetail() {
            try {
                FormationManagerDialogs.showCapacityDialog(act, cat);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onPetCapacity() {
            try {
                FormationManagerDialogs.showPetCapacityDialog(act, cat);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onOpenSettings() {
            openManager(act, fpid, sid, uid, null);
        }
    }

    // ==================== 小工具 ====================

    private static int sourceIndex(String src) {
        for (int i = 0; i < SRC_LABELS.length; i++) {
            if (indexSource(i).equals(src)) return i;
        }
        return 0;
    }

    private static String indexSource(int idx) {
        switch (idx) {
            case 1:
                return SRC_BUILTIN;
            case 2:
                return SRC_BEAR;
            case 3:
                return SRC_GAME;
            default:
                return SRC_AUTO;
        }
    }

    private static boolean isBear(FormationModel f) {
        if (f == null) return false;
        try {
            return f.isBear();
        } catch (Throwable t) {
            return f.name != null && f.name.startsWith("巨熊·");
        }
    }

    private static String nameOf(Activity act, String fpid, long sid, long id) {
        try {
            FormationModel f = FormationStore.formation(act, fpid, sid, id);
            return f == null || f.name == null || f.name.isEmpty() ? "" : f.name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static long activeRoleSid(Activity act, String fpid) {
        try {
            return RoleStore.activeRole(act, fpid == null ? "" : fpid);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String activeRoleUid(Activity act, String fpid) {
        try {
            long uid = RoleStore.activeRoleUid(act, fpid == null ? "" : fpid);
            return uid > 0 ? String.valueOf(uid) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static TextView text(Activity act, String s, float size, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static void sync(Activity act) {
        try {
            VaultSync.onAccountsChanged(act);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Activity act, int v) {
        return UiStyle.dp(act, v);
    }
}
