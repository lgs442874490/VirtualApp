package com.carlos.vault.daily.formation.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.PresetFormations;
import com.carlos.vault.daily.formation.FormationQuickSetup;
import com.carlos.vault.daily.formation.FormationRules;
import com.carlos.vault.wjdr.asset.HeroCatalog;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编队管理页/编队板统一弹窗（T4；契约 §4.3）。全部代码构建（无 XML），
 * 尺寸/文案按 {@code design/ui-ref-编队-20261025/README.md} §2 与
 * {@code design/编队功能-网页版1比1规格-20261025.md} §1.6/§1.8–§1.12。
 *
 * <p>覆盖：出征容量、宠物技能增益、编队记忆、一键编队结果、
 * 删除确认、切换未保存确认、保存目标编队（编队阵容）、功能保存目标、编队设置。
 * T5 复用 {@link #showCapacityDialog}/{@link #showPetCapacityDialog}。
 */
public final class FormationManagerDialogs {

    private FormationManagerDialogs() {
    }

    // ==================== 对外回调接口 ====================

    /** 编队记忆动作（kind=DAILY|BATTLE）。 */
    public interface MemoryAction {
        void onSave(String kind, boolean overwrite);

        void onRestore(String kind);
    }

    /** 保存目标编队回调（targetId<=0 且 name 非空 = 新建）。 */
    public interface SaveTarget {
        void onTarget(long targetId, String name);
    }

    /** 编队设置回调。 */
    public interface EditorSettings {
        void onConfirm(String name, String allocationMode);

        void onDelete();
    }

    /** 功能保存目标回调。 */
    public interface OnFunctions {
        void onConfirm(List<String> fnIds);
    }

    // ==================== 编队容量（规格 §1.8 / ui-ref §2.3） ====================

    public static void showCapacityDialog(Activity act, FormationCatalogStore cat) {
        if (act == null) return;
        LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];

        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headText = ManagerUi.column(act);
        headText.addView(ManagerUi.text(act, "出征容量参考", 11.5f, ManagerUi.TEXT3, false));
        headText.addView(ManagerUi.text(act, "基础出征上限", 21, ManagerUi.TEXT, true));
        String status = "登录容量状态已同步";
        if (cat != null) {
            try {
                JSONObject msg = cat.marchCapacityMessage();
                if (msg != null && msg.optString("message", "").length() > 0) {
                    status = msg.optString("message", status);
                }
            } catch (Throwable ignored) {
            }
        }
        headText.addView(ManagerUi.text(act, status, 11.5f, ManagerUi.TEXT3, false));
        head.addView(headText, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);

        card.addView(ManagerUi.spacer(act, 8));
        card.addView(ManagerUi.divider(act));
        card.addView(ManagerUi.spacer(act, 10));

        int bp = 0;
        long fixed = 0;
        long[] limits = new long[]{0, 0, 0, 0};
        long capacity = 0;
        if (cat != null) {
            try {
                bp = cat.percentageBasisPoints();
                fixed = cat.fixedCapacity();
                limits = cat.limits();
                capacity = cat.capacity();
            } catch (Throwable ignored) {
            }
        }
        boolean hasBuff = bp != 0;
        TextView buff = ManagerUi.text(act, hasBuff
                ? "当前容量百分比 Buff +" + (bp / 100.0f) + "%"
                : "当前无容量百分比 Buff", 12,
                hasBuff ? ManagerUi.PRIMARY_DEEP : ManagerUi.TEXT3, hasBuff);
        buff.setPadding(ManagerUi.dp(act, 12), ManagerUi.dp(act, 8),
                ManagerUi.dp(act, 12), ManagerUi.dp(act, 8));
        buff.setBackground(ManagerUi.round(act, hasBuff ? ManagerUi.PRIMARY_50 : ManagerUi.SOFT, 10));
        card.addView(buff, ManagerUi.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(ManagerUi.spacer(act, 10));
        String[] codes = {"3H", "2H", "1H", "0H"};
        String[] hints = {"三个英雄参考", "两个英雄参考", "一个英雄参考", "无英雄"};
        LinearLayout grid = ManagerUi.row(act);
        for (int i = 0; i < 4; i++) {
            boolean current = i == 3 ? capacity <= limits[3] : isCurrentTier(limits, i, capacity);
            LinearLayout cell = ManagerUi.column(act);
            cell.setPadding(ManagerUi.dp(act, 10), ManagerUi.dp(act, 8),
                    ManagerUi.dp(act, 8), ManagerUi.dp(act, 8));
            cell.setBackground(current
                    ? ManagerUi.stroke(act, ManagerUi.PRIMARY_50, 12, ManagerUi.PRIMARY_300)
                    : ManagerUi.round(act, ManagerUi.SOFT, 12));
            LinearLayout l1 = ManagerUi.row(act);
            TextView code = ManagerUi.text(act, codes[i], 10.5f, ManagerUi.PRIMARY_DEEP, true);
            code.setGravity(Gravity.CENTER);
            code.setBackground(ManagerUi.round(act, ManagerUi.PRIMARY_100, 6));
            code.setPadding(ManagerUi.dp(act, 6), ManagerUi.dp(act, 2),
                    ManagerUi.dp(act, 6), ManagerUi.dp(act, 2));
            l1.addView(code);
            l1.addView(ManagerUi.spacer(act, 1), ManagerUi.lp(0, 1, 1f));
            TextView num = ManagerUi.text(act, FormationRules.fmt(limits[i]), 14,
                    ManagerUi.TEXT, true);
            l1.addView(num);
            cell.addView(l1);
            TextView hint = ManagerUi.text(act, hints[i], 10, ManagerUi.TEXT4, false);
            hint.setPadding(0, ManagerUi.dp(act, 3), 0, 0);
            cell.addView(hint);
            LinearLayout.LayoutParams clp = ManagerUi.lp(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 3) clp.rightMargin = ManagerUi.dp(act, 6);
            grid.addView(cell, clp);
        }
        card.addView(grid);

        TextView fixedT = ManagerUi.text(act, "系统与账号固定容量 " + FormationRules.fmt(fixed),
                11, ManagerUi.TEXT3, false);
        fixedT.setPadding(0, ManagerUi.dp(act, 10), 0, 0);
        card.addView(fixedT);

        TextView foot = ManagerUi.text(act,
                "实际出征时以登录后实时同步的容量为准；宠物技能容量在真正出征前实时叠加。",
                10.5f, ManagerUi.TEXT4, false);
        foot.setPadding(0, ManagerUi.dp(act, 10), 0, 0);
        foot.setLineSpacing(ManagerUi.dp(act, 2), 1f);
        card.addView(foot);

        ref[0] = showDialog(act, card);
    }

    private static boolean isCurrentTier(long[] limits, int index, long capacity) {
        if (capacity <= 0) return false;
        return limits[index] == capacity;
    }

    // ==================== 宠物技能增益（规格 §1.9） ====================

    public static void showPetCapacityDialog(Activity act, FormationCatalogStore cat) {
        if (act == null) return;
        LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];

        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headText = ManagerUi.column(act);
        headText.addView(ManagerUi.text(act, "宠物技能增益", 11.5f, ManagerUi.TEXT3, false));
        headText.addView(ManagerUi.text(act, "雪猿 · 势如雪崩", 20, ManagerUi.TEXT, true));
        head.addView(headText, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);

        JSONObject av = null;
        if (cat != null) {
            try {
                av = cat.avalanche();
            } catch (Throwable ignored) {
            }
        }
        boolean initialized = av != null && av.optBoolean("initialized", false);
        boolean owned = av != null && av.optBoolean("owned", false);
        boolean active = av != null && av.optBoolean("active", false);
        int petBreakLevel = av == null ? 0 : av.optInt("petBreakLevel", 0);
        int skillLevel = av == null ? 0 : av.optInt("skillLevel", 0);
        long configured = av == null ? 0 : av.optLong("configuredTroopBonus", 0);
        long current = av == null ? 0 : av.optLong("troopBonus", 0);

        String sentence;
        if (!initialized) {
            sentence = "正在同步宠物拥有状态和技能使用状态";
        } else if (!owned) {
            sentence = "当前角色未拥有雪猿，不增加出征容量";
        } else if (active) {
            sentence = "技能正在生效，当前实际增加 " + FormationRules.fmt(current) + " 出征容量";
        } else {
            sentence = "当前未生效；激活后可增加 " + FormationRules.fmt(configured) + " 出征容量";
        }
        TextView desc = ManagerUi.text(act, sentence, 12,
                active ? ManagerUi.OK_INK : ManagerUi.TEXT3, false);
        desc.setPadding(0, ManagerUi.dp(act, 6), 0, ManagerUi.dp(act, 10));
        desc.setLineSpacing(ManagerUi.dp(act, 2), 1f);
        card.addView(desc);

        LinearLayout grid = ManagerUi.row(act);
        grid.addView(petCell(act, "宠物突破", petBreakLevel > 0 ? petBreakLevel + " 级" : "-"));
        grid.addView(petCell(act, "技能等级", skillLevel > 0 ? skillLevel + " 级" : "-"));
        grid.addView(petCell(act, "激活后增加", "+" + FormationRules.fmt(configured)));
        grid.addView(petCell(act, "当前增加", "+" + FormationRules.fmt(current)));
        card.addView(grid);

        if (!initialized) {
            TextView tip = ManagerUi.text(act, "等待角色宠物状态同步完成",
                    11, ManagerUi.WARN_INK, false);
            tip.setPadding(0, ManagerUi.dp(act, 10), 0, 0);
            card.addView(tip);
        }
        TextView foot = ManagerUi.text(act,
                "技能开启后，出征会按实时容量重新计算；未开启时仅展示基础容量。",
                10.5f, ManagerUi.TEXT4, false);
        foot.setPadding(0, ManagerUi.dp(act, 10), 0, 0);
        card.addView(foot);

        ref[0] = showDialog(act, card);
    }

    private static LinearLayout petCell(Activity act, String label, String value) {
        LinearLayout cell = ManagerUi.column(act);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(ManagerUi.dp(act, 4), ManagerUi.dp(act, 10),
                ManagerUi.dp(act, 4), ManagerUi.dp(act, 10));
        cell.setBackground(ManagerUi.round(act, ManagerUi.SOFT, 12));
        cell.addView(ManagerUi.text(act, label, 10.5f, ManagerUi.TEXT3, false));
        TextView v = ManagerUi.text(act, value, 15, ManagerUi.TEXT, true);
        v.setPadding(0, ManagerUi.dp(act, 3), 0, 0);
        cell.addView(v);
        LinearLayout.LayoutParams lp = ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = ManagerUi.dp(act, 6);
        cell.setLayoutParams(lp);
        return cell;
    }

    // ==================== 编队记忆（规格 §1.10） ====================

    public static void showMemoryDialog(final Activity act, final FormationCatalogStore cat,
                                        final boolean restore, final MemoryAction cb) {
        if (act == null) return;
        final LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];

        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headText = ManagerUi.column(act);
        headText.addView(ManagerUi.text(act, "编队记忆", 11.5f, ManagerUi.PRIMARY, true));
        headText.addView(ManagerUi.text(act, restore ? "恢复编队记忆" : "记忆当前编队",
                19, ManagerUi.TEXT, true));
        head.addView(headText, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);

        TextView desc = ManagerUi.text(act, restore
                        ? "选择要恢复到游戏的记忆类型；没有保存过的类型无法恢复。"
                        : "选择要把当前游戏编队保存到的记忆类型；已有记忆会先确认覆盖。",
                11.5f, ManagerUi.TEXT3, false);
        desc.setPadding(0, ManagerUi.dp(act, 8), 0, ManagerUi.dp(act, 10));
        desc.setLineSpacing(ManagerUi.dp(act, 2), 1f);
        card.addView(desc);

        JSONObject mem = null;
        if (cat != null) {
            try {
                mem = cat.memoryStatus();
            } catch (Throwable ignored) {
            }
        }
        card.addView(memoryOption(act, ref, cat, mem, "DAILY", "日常", restore, cb));
        card.addView(ManagerUi.spacer(act, 8));
        card.addView(memoryOption(act, ref, cat, mem, "BATTLE", "战斗", restore, cb));

        ref[0] = showDialog(act, card);
    }

    private static View memoryOption(final Activity act, final AlertDialog[] ref,
                                     final FormationCatalogStore cat, JSONObject mem,
                                     final String kind, final String label, final boolean restore,
                                     final MemoryAction cb) {
        JSONObject st = mem == null ? null : mem.optJSONObject(
                "DAILY".equals(kind) ? "daily" : "battle");
        boolean exists = st != null && st.optBoolean("exists", false);
        int slotCount = st == null ? 0 : st.optInt("slotCount", 0);
        long savedAt = st == null ? 0 : st.optLong("savedAt", 0);

        LinearLayout row = ManagerUi.column(act);
        row.setPadding(ManagerUi.dp(act, 14), ManagerUi.dp(act, 11),
                ManagerUi.dp(act, 14), ManagerUi.dp(act, 11));
        row.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 12, ManagerUi.LINE_SOFT));
        row.addView(ManagerUi.text(act, label, 14, ManagerUi.TEXT, true));
        String status;
        if (!exists) {
            status = restore ? "还没有保存过，无法恢复" : "还没有保存过";
        } else {
            status = "已保存 " + slotCount + " 支 · " + timeText(savedAt);
            if (!restore) status += " · 点击后覆盖";
        }
        TextView stT = ManagerUi.text(act, status, 11.5f,
                exists ? ManagerUi.TEXT3 : ManagerUi.TEXT4, false);
        stT.setPadding(0, ManagerUi.dp(act, 3), 0, 0);
        row.addView(stT);

        final boolean disabled = restore && !exists;
        row.setAlpha(disabled ? 0.45f : 1f);
        if (!disabled) {
            row.setOnClickListener(v -> {
                if (ref[0] != null) ref[0].dismiss();
                if (restore) {
                    confirm(act, "恢复" + label + "编队记忆",
                            "恢复会用" + label + "记忆内容覆盖当前游戏编队（" + slotCount
                                    + " 支），是否继续？", "恢复编队", false, () -> {
                                if (cb != null) cb.onRestore(kind);
                            });
                } else if (exists) {
                    confirm(act, "覆盖" + label + "编队记忆",
                            label + "记忆已有保存内容，重新记忆会覆盖旧记忆且无法找回，是否继续？",
                            "覆盖记忆", false, () -> {
                                if (cb != null) cb.onSave(kind, true);
                            });
                } else if (cb != null) {
                    cb.onSave(kind, false);
                }
            });
            UiStyle.press(row);
        }
        return row;
    }

    // ==================== 一键编队结果（规格 §1.12） ====================

    public static void showQuickFormationResult(Activity act, List<FormationQuickSetup.Step> steps) {
        if (act == null) return;
        LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ManagerUi.text(act, "一键编队结果", 17, ManagerUi.TEXT, true),
                ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);

        if (steps == null || steps.isEmpty()) {
            TextView ok = ManagerUi.text(act, "一键编队已完成", 13, ManagerUi.TEXT2, false);
            ok.setPadding(0, ManagerUi.dp(act, 14), 0, ManagerUi.dp(act, 6));
            card.addView(ok);
        } else {
            ScrollView sc = new ScrollView(act);
            LinearLayout list = ManagerUi.column(act);
            for (FormationQuickSetup.Step step : steps) {
                if (step == null) continue;
                int ink = FormationQuickSetup.STATUS_APPLIED.equals(step.status)
                        ? ManagerUi.OK_INK
                        : (FormationQuickSetup.STATUS_SKIPPED.equals(step.status)
                        ? ManagerUi.WARN_INK : ManagerUi.ERR_INK);
                TextView row = ManagerUi.text(act, step.line(), 12, ink, false);
                row.setPadding(ManagerUi.dp(act, 12), ManagerUi.dp(act, 8),
                        ManagerUi.dp(act, 12), ManagerUi.dp(act, 8));
                row.setBackground(ManagerUi.round(act, ManagerUi.SOFT, 10));
                LinearLayout.LayoutParams rlp = ManagerUi.lp(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = ManagerUi.dp(act, 6);
                list.addView(row, rlp);
            }
            sc.addView(list);
            LinearLayout.LayoutParams slp = ManagerUi.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (act.getResources().getDisplayMetrics().heightPixels * 0.4f));
            slp.topMargin = ManagerUi.dp(act, 10);
            card.addView(sc, slp);
        }

        TextView okBtn = ManagerUi.smallButton(act, "知道了", true, () -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout btns = ManagerUi.row(act);
        btns.setPadding(0, ManagerUi.dp(act, 12), 0, 0);
        btns.addView(new View(act), ManagerUi.lp(0, 1, 1f));
        btns.addView(okBtn);
        card.addView(btns);
        ref[0] = showDialog(act, card);
    }

    // ==================== 删除 / 切换确认 ====================

    public static void showDeleteConfirm(Activity act, FormationModel f, final Runnable onConfirm) {
        String name = f == null ? "" : (f.name == null ? "" : f.name);
        confirm(act, "删除编队",
                String.format(Locale.ROOT, FormationRules.MSG_DELETE_HINT_FMT, name),
                "确认删除", true, onConfirm);
    }

    public static void showSwitchConfirm(Activity act, final Runnable onContinue) {
        confirm(act, "切换编队", "切换编队会丢弃当前未保存的修改，是否继续？",
                "继续切换", false, onContinue, "留在当前编队");
    }

    // ==================== 保存目标编队（编队阵容，规格 §1.6） ====================

    /**
     * @param lib    现有编队（内置/自定义）
     * @param draft  当前编辑中的编队
     * @param cb     确认回调（targetId<=0=新建，name=新名称）
     */
    public static void showSaveTargetDialog(final Activity act, final List<FormationModel> lib,
                                            final FormationModel draft,
                                            final long currentId, final SaveTarget cb) {
        if (act == null) return;
        final LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];
        final long[] targetId = {currentId > 0 ? currentId : 0L};
        final boolean[] isNew = {currentId <= 0};

        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ManagerUi.text(act, "编队阵容", 17, ManagerUi.TEXT, true),
                ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);
        TextView hint = ManagerUi.text(act, "请选择编队", 11.5f, ManagerUi.TEXT3, false);
        hint.setPadding(0, ManagerUi.dp(act, 6), 0, ManagerUi.dp(act, 6));
        card.addView(hint);

        final LinearLayout tabsRow = ManagerUi.column(act);
        card.addView(tabsRow);

        // 名称输入（仅“新建编队”显示）
        LinearLayout nameBox = ManagerUi.column(act);
        TextView nameLabel = ManagerUi.text(act, "编队名称", 11.5f, ManagerUi.TEXT3, false);
        nameLabel.setPadding(0, ManagerUi.dp(act, 10), 0, ManagerUi.dp(act, 4));
        nameBox.addView(nameLabel);
        final EditText nameEt = UiStyle.searchInput(act, "例如：采集主队、巨兽高伤队");
        if (draft != null && draft.name != null) nameEt.setText(draft.name);
        try {
            nameEt.setFilters(new InputFilter[]{
                    new InputFilter.LengthFilter(FormationModel.NAME_MAX)});
        } catch (Throwable ignored) {
        }
        nameBox.addView(nameEt);
        TextView warn = ManagerUi.text(act, FormationRules.MSG_OVERWRITE_HINT, 10.5f,
                ManagerUi.WARN_INK, false);
        warn.setPadding(0, ManagerUi.dp(act, 8), 0, 0);
        nameBox.addView(warn);
        card.addView(nameBox);

        // ---------- 储存方案（游戏原文：按士兵数量储存 / 按士兵比例储存） ----------
        // 选中即写回 draft.allocationMode；切到比例且比例和为 0 时补 50/20/30（契约 §4.2）。
        final String[] schemeMode = {FormationModel.normalizeAllocationMode(
                draft == null ? FormationModel.MODE_MANUAL : draft.allocationMode)};
        TextView schemeLabel = ManagerUi.text(act, "储存方案", 11.5f, ManagerUi.TEXT3, false);
        schemeLabel.setPadding(0, ManagerUi.dp(act, 10), 0, ManagerUi.dp(act, 4));
        card.addView(schemeLabel);
        final String[] schemeModes = {FormationModel.MODE_MANUAL,
                FormationModel.MODE_PERCENTAGE};
        final String[] schemeTexts = {"按士兵数量储存", "按士兵比例储存"};
        final TextView[] schemeBtns = new TextView[2];
        final Runnable syncScheme = () -> {
            for (int i = 0; i < schemeBtns.length; i++) {
                if (schemeBtns[i] == null) continue;
                boolean on = schemeModes[i].equals(schemeMode[0]);
                schemeBtns[i].setText((on ? "◉ " : "○ ") + schemeTexts[i]);
                schemeBtns[i].setTextColor(on ? Color.WHITE : ManagerUi.TEXT2);
                schemeBtns[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                schemeBtns[i].setBackground(on
                        ? ManagerUi.round(act, ManagerUi.PRIMARY, 10)
                        : ManagerUi.stroke(act, ManagerUi.CARD, 10, ManagerUi.LINE_SOFT));
            }
        };
        LinearLayout schemeRow = ManagerUi.row(act);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView btn = ManagerUi.text(act, "", 12, ManagerUi.TEXT2, false);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, ManagerUi.dp(act, 10), 0, ManagerUi.dp(act, 10));
            btn.setOnClickListener(v -> {
                String mode = schemeModes[idx];
                if (mode.equals(schemeMode[0])) return;
                schemeMode[0] = mode;
                if (draft != null) {
                    draft.allocationMode = mode;
                    if (FormationModel.MODE_PERCENTAGE.equals(mode) && draft.ratioSum() <= 0) {
                        draft.infantryRatio = 50;
                        draft.lancerRatio = 20;
                        draft.marksmanRatio = 30;
                    }
                }
                syncScheme.run();
            });
            schemeBtns[i] = btn;
            LinearLayout.LayoutParams blp = ManagerUi.lp(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i == 0) blp.rightMargin = ManagerUi.dp(act, 6);
            schemeRow.addView(btn, blp);
        }
        card.addView(schemeRow);
        syncScheme.run();
        TextView schemeHint = ManagerUi.text(act,
                "储存编队阵容时，可在【按士兵数量储存】和【按士兵比例储存】两种方案间选择其一。",
                10.5f, ManagerUi.TEXT4, false);
        schemeHint.setPadding(0, ManagerUi.dp(act, 6), 0, 0);
        schemeHint.setLineSpacing(ManagerUi.dp(act, 1), 1f);
        card.addView(schemeHint);

        final TextView ok = ManagerUi.smallButton(act, "确定", true, () -> {
            if (isNew[0]) {
                String name = nameEt.getText().toString().trim();
                if (name.isEmpty()) {
                    UiStyle.toast(act, FormationRules.MSG_NAME_REQUIRED);
                    return;
                }
                if (ref[0] != null) ref[0].dismiss();
                if (cb != null) cb.onTarget(0L, name);
            } else {
                if (ref[0] != null) ref[0].dismiss();
                if (cb != null) cb.onTarget(targetId[0], nameEt.getText().toString().trim());
            }
        });
        TextView cancel = ManagerUi.smallButton(act, "取消", false, () -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout btns = ManagerUi.row(act);
        btns.setPadding(0, ManagerUi.dp(act, 14), 0, 0);
        btns.addView(cancel);
        btns.addView(new View(act), ManagerUi.lp(0, 1, 1f));
        btns.addView(ok);
        card.addView(btns);

        final Runnable syncNameBox = () -> {
            nameBox.setVisibility(isNew[0] ? View.VISIBLE : View.GONE);
            ok.setEnabled(!isNew[0] || nameEt.getText().toString().trim().length() > 0);
            ok.setAlpha(ok.isEnabled() ? 1f : 0.5f);
        };
        nameEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable s) {
                syncNameBox.run();
            }
        });
        final Runnable[] rebuildRef = new Runnable[1];
        final Runnable rebuildTabs = new Runnable() {
            @Override
            public void run() {
                tabsRow.removeAllViews();
                int col = 0;
                List<FormationModel> items = new ArrayList<FormationModel>();
                if (lib != null) {
                    for (FormationModel f : lib) {
                        if (f == null || f.isGame()) continue;
                        items.add(f);
                    }
                }
                if (items.isEmpty()) {
                    tabsRow.addView(ManagerUi.text(act, "暂无可覆盖的编队",
                            11.5f, ManagerUi.TEXT4, false));
                } else {
                    LinearLayout inner = new LinearLayout(act);
                    inner.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout line = ManagerUi.row(act);
                    for (final FormationModel f : items) {
                        final boolean selected = !isNew[0] && f.id == targetId[0];
                        TextView chip = ManagerUi.text(act,
                                f.name + "（" + (PresetFormations.isPreset(f.id) ? "预设" : "自定义") + "）",
                                11.5f, selected ? Color.WHITE : ManagerUi.TEXT2, selected);
                        chip.setSingleLine(true);
                        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        chip.setPadding(ManagerUi.dp(act, 10), ManagerUi.dp(act, 6),
                                ManagerUi.dp(act, 10), ManagerUi.dp(act, 6));
                        chip.setBackground(selected
                                ? ManagerUi.round(act, ManagerUi.PRIMARY, 999)
                                : ManagerUi.stroke(act, ManagerUi.CARD, 999, ManagerUi.LINE_SOFT));
                        chip.setOnClickListener(v -> {
                            targetId[0] = f.id;
                            isNew[0] = false;
                            if (rebuildRef[0] != null) rebuildRef[0].run();
                            syncNameBox.run();
                        });
                        LinearLayout.LayoutParams clp = ManagerUi.lp(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        clp.rightMargin = ManagerUi.dp(act, 6);
                        line.addView(chip, clp);
                        if (++col % 2 == 0) {
                            inner.addView(line);
                            line = ManagerUi.row(act);
                        }
                    }
                    if (col % 2 != 0) inner.addView(line);
                    ScrollView sc = new ScrollView(act);
                    sc.setHorizontalScrollBarEnabled(false);
                    sc.addView(inner);
                    tabsRow.addView(sc);
                    // 库内编队较多（含 19 套预设）时限制列表高度，避免「储存方案/确定」被挤出屏幕
                    final int maxListH = ManagerUi.dp(act, 168);
                    sc.post(() -> {
                        if (sc.getChildCount() == 0) return;
                        int content = sc.getChildAt(0).getHeight();
                        if (content <= maxListH) return;
                        ViewGroup.LayoutParams lp = sc.getLayoutParams();
                        if (lp != null && lp.height == maxListH) return;
                        if (lp == null) {
                            lp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, maxListH);
                        } else {
                            lp.height = maxListH;
                        }
                        sc.setLayoutParams(lp);
                    });
                }
                // 新建编队 chip
                LinearLayout newRow = ManagerUi.row(act);
                final boolean newSel = isNew[0];
                TextView newChip = ManagerUi.text(act, "＋ 新建编队", 11.5f,
                        newSel ? Color.WHITE : ManagerUi.PRIMARY_DEEP, newSel);
                newChip.setPadding(ManagerUi.dp(act, 10), ManagerUi.dp(act, 6),
                        ManagerUi.dp(act, 10), ManagerUi.dp(act, 6));
                newChip.setBackground(newSel ? ManagerUi.round(act, ManagerUi.PRIMARY, 999)
                        : ManagerUi.stroke(act, ManagerUi.CARD, 999, ManagerUi.PRIMARY_300));
                newChip.setOnClickListener(v -> {
                    isNew[0] = true;
                    targetId[0] = 0;
                    if (nameEt.getText().toString().trim().isEmpty() && draft != null) {
                        nameEt.setText(draft.name);
                    }
                    if (rebuildRef[0] != null) rebuildRef[0].run();
                    syncNameBox.run();
                });
                newRow.setPadding(0, ManagerUi.dp(act, 8), 0, 0);
                newRow.addView(newChip);
                tabsRow.addView(newRow);
            }
        };
        rebuildRef[0] = rebuildTabs;
        rebuildTabs.run();
        syncNameBox.run();
        ref[0] = showDialog(act, card);
    }

    private static long pickDefaultTarget(List<FormationModel> lib) {
        if (lib != null) {
            for (FormationModel f : lib) {
                if (f != null && !f.isGame() && !f.builtIn) return f.id;
            }
            for (FormationModel f : lib) {
                if (f != null && !f.isGame()) return f.id;
            }
        }
        return 0;
    }

    // ==================== 功能保存目标（功能 tab） ====================

    /**
     * 保存目标编队（功能 tab）：把当前自动比例/编队应用到所选功能队列。
     *
     * @param fnIds    功能 id（gather/intel/behemoth/bear/hunt/guardian）
     * @param labels   功能中文名（与 fnIds 对齐）
     * @param selected 默认勾选
     */
    public static void showFunctionTargetDialog(final Activity act, final String[] fnIds,
                                                final String[] labels, final String[] selected,
                                                final OnFunctions cb) {
        if (act == null || fnIds == null) return;
        final LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ManagerUi.text(act, "保存目标编队", 17, ManagerUi.TEXT, true),
                ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);
        TextView hint = ManagerUi.text(act, "选择要应用当前编队配置的功能队列", 11.5f,
                ManagerUi.TEXT3, false);
        hint.setPadding(0, ManagerUi.dp(act, 6), 0, ManagerUi.dp(act, 8));
        card.addView(hint);

        final Set<String> chosen = new LinkedHashSet<String>();
        if (selected != null) {
            for (String s : selected) {
                if (s != null && !s.isEmpty()) chosen.add(s);
            }
        }
        for (int i = 0; i < fnIds.length; i++) {
            final String id = fnIds[i];
            final String label = labels != null && i < labels.length ? labels[i] : id;
            final LinearLayout row = ManagerUi.row(act);
            row.setPadding(ManagerUi.dp(act, 12), ManagerUi.dp(act, 10),
                    ManagerUi.dp(act, 12), ManagerUi.dp(act, 10));
            row.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 12, ManagerUi.LINE_SOFT));
            LinearLayout.LayoutParams rlp = ManagerUi.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = ManagerUi.dp(act, 6);
            card.addView(row, rlp);
            final TextView check = ManagerUi.text(act, chosen.contains(id) ? "☑" : "☐",
                    15, ManagerUi.PRIMARY, true);
            row.addView(check, ManagerUi.lp(ManagerUi.dp(act, 24),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(ManagerUi.text(act, label, 13, ManagerUi.TEXT2, false));
            row.setOnClickListener(v -> {
                if (chosen.contains(id)) chosen.remove(id);
                else chosen.add(id);
                check.setText(chosen.contains(id) ? "☑" : "☐");
            });
        }

        LinearLayout btns = ManagerUi.row(act);
        btns.setPadding(0, ManagerUi.dp(act, 12), 0, 0);
        btns.addView(ManagerUi.smallButton(act, "取消", false, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        btns.addView(new View(act), ManagerUi.lp(0, 1, 1f));
        btns.addView(ManagerUi.smallButton(act, "确认使用", true, () -> {
            if (chosen.isEmpty()) {
                UiStyle.toast(act, FormationRules.MSG_SELECT_FORMATION);
                return;
            }
            if (ref[0] != null) ref[0].dismiss();
            if (cb != null) cb.onConfirm(new ArrayList<String>(chosen));
        }));
        card.addView(btns);
        ref[0] = showDialog(act, card);
    }

    // ==================== 编队设置（编辑器） ====================

    public static void showEditorSettingsDialog(final Activity act, final FormationModel draft,
                                                final boolean canDelete,
                                                final EditorSettings cb) {
        if (act == null || draft == null) return;
        final LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout head = ManagerUi.row(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ManagerUi.text(act, "编队设置", 17, ManagerUi.TEXT, true),
                ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton(act, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        card.addView(head);

        TextView nameLabel = ManagerUi.text(act, "编队名称", 11.5f, ManagerUi.TEXT3, false);
        nameLabel.setPadding(0, ManagerUi.dp(act, 10), 0, ManagerUi.dp(act, 4));
        card.addView(nameLabel);
        final EditText nameEt = UiStyle.searchInput(act, "例如：采集主队、巨兽高伤队");
        nameEt.setText(draft.name == null ? "" : draft.name);
        try {
            nameEt.setFilters(new InputFilter[]{
                    new InputFilter.LengthFilter(FormationModel.NAME_MAX)});
        } catch (Throwable ignored) {
        }
        card.addView(nameEt);

        TextView modeLabel = ManagerUi.text(act, "兵量分配", 11.5f, ManagerUi.TEXT3, false);
        modeLabel.setPadding(0, ManagerUi.dp(act, 12), 0, ManagerUi.dp(act, 4));
        card.addView(modeLabel);
        final String[] mode = {FormationModel.normalizeAllocationMode(draft.allocationMode)};
        final LinearLayout segmented = ManagerUi.row(act);
        final TextView fixed = modeButton(act, "固定数量",
                FormationModel.MODE_MANUAL.equals(mode[0]));
        final TextView ratio = modeButton(act, "比例分配",
                FormationModel.MODE_PERCENTAGE.equals(mode[0]));
        segmented.addView(fixed, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        segmented.addView(ratio, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(segmented);
        final Runnable syncMode = () -> {
            boolean m = FormationModel.MODE_MANUAL.equals(mode[0]);
            fixed.setBackground(m ? ManagerUi.round(act, ManagerUi.PRIMARY, 10)
                    : ManagerUi.stroke(act, ManagerUi.CARD, 10, ManagerUi.LINE_SOFT));
            fixed.setTextColor(m ? Color.WHITE : ManagerUi.TEXT2);
            ratio.setBackground(!m ? ManagerUi.round(act, ManagerUi.PRIMARY, 10)
                    : ManagerUi.stroke(act, ManagerUi.CARD, 10, ManagerUi.LINE_SOFT));
            ratio.setTextColor(!m ? Color.WHITE : ManagerUi.TEXT2);
        };
        fixed.setOnClickListener(v -> {
            mode[0] = FormationModel.MODE_MANUAL;
            syncMode.run();
        });
        ratio.setOnClickListener(v -> {
            mode[0] = FormationModel.MODE_PERCENTAGE;
            syncMode.run();
        });
        syncMode.run();

        LinearLayout btns = ManagerUi.row(act);
        btns.setPadding(0, ManagerUi.dp(act, 16), 0, 0);
        if (canDelete) {
            btns.addView(ManagerUi.smallButton(act, "删除编队", false, () -> {
                if (ref[0] != null) ref[0].dismiss();
                if (cb != null) cb.onDelete();
            }));
        }
        btns.addView(new View(act), ManagerUi.lp(0, 1, 1f));
        btns.addView(ManagerUi.smallButton(act, "取消", false, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        TextView ok = ManagerUi.smallButton(act, "确定", true, () -> {
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) {
                UiStyle.toast(act, FormationRules.MSG_NAME_REQUIRED);
                return;
            }
            if (ref[0] != null) ref[0].dismiss();
            if (cb != null) cb.onConfirm(name, mode[0]);
        });
        LinearLayout.LayoutParams okLp = ManagerUi.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = ManagerUi.dp(act, 8);
        btns.addView(ok, okLp);
        card.addView(btns);
        ref[0] = showDialog(act, card);
    }

    private static TextView modeButton(Activity act, String text, boolean selected) {
        TextView t = ManagerUi.text(act, text, 12.5f,
                selected ? Color.WHITE : ManagerUi.TEXT2, selected);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ManagerUi.dp(act, 9), 0, ManagerUi.dp(act, 9));
        t.setBackground(selected ? ManagerUi.round(act, ManagerUi.PRIMARY, 10)
                : ManagerUi.stroke(act, ManagerUi.CARD, 10, ManagerUi.LINE_SOFT));
        return t;
    }

    // ==================== 通用确认 ====================

    public static void confirm(final Activity act, String title, String message,
                               String posText, boolean danger, final Runnable onConfirm) {
        confirm(act, title, message, posText, danger, onConfirm, "取消");
    }

    public static void confirm(final Activity act, String title, String message,
                               String posText, boolean danger, final Runnable onConfirm,
                               String negText) {
        if (act == null) return;
        final LinearLayout card = baseCard(act);
        final AlertDialog[] ref = new AlertDialog[1];
        card.addView(ManagerUi.text(act, title, 16, ManagerUi.TEXT, true));
        TextView msg = ManagerUi.text(act, message, 12.5f, ManagerUi.TEXT3, false);
        msg.setPadding(0, ManagerUi.dp(act, 8), 0, 0);
        msg.setLineSpacing(ManagerUi.dp(act, 2), 1f);
        card.addView(msg);

        LinearLayout btns = ManagerUi.row(act);
        btns.setPadding(0, ManagerUi.dp(act, 16), 0, 0);
        btns.addView(ManagerUi.smallButton(act, negText, false, () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        btns.addView(new View(act), ManagerUi.lp(0, 1, 1f));
        TextView okBtn = ManagerUi.smallButton(act, posText, true, () -> {
            if (ref[0] != null) ref[0].dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        if (danger) {
            okBtn.setBackground(ManagerUi.pressable(act, ManagerUi.ERR_SOLID, ManagerUi.ERR_PRESS, 10));
        }
        btns.addView(okBtn);
        card.addView(btns);
        ref[0] = showDialog(act, card);
    }

    // ==================== 通用构件 ====================

    private static LinearLayout baseCard(Activity act) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ManagerUi.stroke(act, ManagerUi.CARD, 16, ManagerUi.LINE));
        card.setPadding(ManagerUi.dp(act, 18), ManagerUi.dp(act, 16),
                ManagerUi.dp(act, 18), ManagerUi.dp(act, 16));
        card.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private static TextView closeButton(Activity act, final Runnable onClick) {
        TextView t = ManagerUi.text(act, "×", 18, ManagerUi.TEXT3, false);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription("关闭");
        t.setBackground(ManagerUi.round(act, ManagerUi.SOFT, 999));
        t.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        UiStyle.press(t);
        t.setLayoutParams(new LinearLayout.LayoutParams(ManagerUi.dp(act, 30),
                ManagerUi.dp(act, 30)));
        return t;
    }

    private static TextView chip(Activity act, String text, boolean selected, Runnable onClick) {
        TextView t = ManagerUi.chip(act, text, selected, onClick);
        LinearLayout.LayoutParams lp = ManagerUi.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ManagerUi.dp(act, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 统一弹窗展示：透明窗口 + 卡片 463dp 上限宽 + 垂直居中。 */
    private static AlertDialog showDialog(final Activity act, final View card) {
        AlertDialog dlg = new AlertDialog.Builder(act,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT).setView(card).create();
        final Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setDimAmount(0.45f);
        }
        dlg.setCanceledOnTouchOutside(true);
        dlg.show();
        if (w != null) {
            int width = ManagerUi.designWidth(act);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        return dlg;
    }

    private static String timeText(long ms) {
        if (ms <= 0) return "时间未知";
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
        } catch (Throwable t) {
            return "时间未知";
        }
    }
}
