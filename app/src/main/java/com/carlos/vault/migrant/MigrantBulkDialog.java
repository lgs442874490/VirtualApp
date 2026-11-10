package com.carlos.vault.migrant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量创建弹窗（1:1 复刻网页版批量创建区块，模板 3823-4086 行）。
 *
 * <p>结构：`选择账号`（多选，来自 {@code accounts} action）→ `目标王国`（下拉，取自
 * overview.kingdoms）→ 4 个复选框（与主表单同级联禁用规则）→ 风险长文案 →
 * 结果区（「已创建 X / Y」+ 逐角色失败原因）→ 底部「关闭 / 开始批量创建」。
 *
 * <p>逐账号执行与单个创建一致的资格预检：离线、名额不足、不同赛区或仍被其它模块占用的
 * 账号会单独列出原因，不影响其它账号（服务端保证失败隔离，见契约 §3.6）。
 */
public final class MigrantBulkDialog {

    private final Activity act;
    private final MigrantApi.Identity who;
    private final String accountLabel;
    private final long presetTargetKid;
    private final MigrantModels.Overview overview;
    private final int advanceMillis;
    private final Runnable onChanged;

    private Dialog dialog;
    private LinearLayout accountBox;
    private TextView tvTarget;
    private TextView tvSubmit;
    private LinearLayout resultBox;

    private final List<MigrantModels.AccountItem> accounts = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private long targetKid;
    private boolean chkExitAlliance = true;
    private boolean chkRecall = true;
    private boolean chkSpeedup = true;
    private boolean chkDiamond = true;

    private boolean loadingAccounts;
    private boolean submitting;
    private MigrantModels.BulkResult result;

    private MigrantBulkDialog(Activity act, MigrantApi.Identity who, String accountLabel,
                              long presetTargetKid, MigrantModels.Overview overview,
                              int advanceMillis, Runnable onChanged) {
        this.act = act;
        this.who = who;
        this.accountLabel = accountLabel == null ? "当前角色" : accountLabel;
        this.presetTargetKid = presetTargetKid;
        this.overview = overview;
        this.advanceMillis = advanceMillis;
        this.onChanged = onChanged;
        this.targetKid = presetTargetKid;
    }

    /** 打开批量创建弹窗。 */
    public static void show(Activity act, MigrantApi.Identity who, String accountLabel,
                            long presetTargetKid, MigrantModels.Overview overview,
                            int advanceMillis, Runnable onChanged) {
        if (act == null) return;
        new MigrantBulkDialog(act, who, accountLabel, presetTargetKid, overview,
                advanceMillis, onChanged).open();
    }

    // ==================== 生命周期 ====================

    private void open() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiStyle.roundRectDp(act, UiStyle.CARD, 20));
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));

        panel.addView(UiComponents.text(act, "批量创建移民任务",
                UiComponents.TEXT_HEADLINE, UiStyle.INK, true));

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(8);
        panel.addView(scroll, slp);

        // 选择账号
        body.addView(label("选择账号"));
        accountBox = new LinearLayout(act);
        accountBox.setOrientation(LinearLayout.VERTICAL);
        accountBox.setBackground(UiStyle.roundRectStroke(act, UiStyle.SURFACE_2, 10,
                UiStyle.LINE));
        accountBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        body.addView(accountBox);

        // 目标王国
        body.addView(label("目标王国"));
        tvTarget = UiStyle.dropdownRow(act, "目标王国",
                targetKid > 0 ? MigrantModels.kingdomLabel(overview, targetKid)
                        : "选择目标王国", this::chooseKingdom);
        body.addView(tvTarget);

        // 复选框
        LinearLayout options = new LinearLayout(act);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(6), 0, 0);
        options.addView(check("开放前自动退出联盟", chkExitAlliance, checked -> {
            chkExitAlliance = checked;
        }));
        options.addView(check("开放前自动收回外出部队", chkRecall, checked -> {
            chkRecall = checked;
            applyCascade(options);
        }));
        options.addView(check("回城赶不上时用行军加速器（二阶 → 一阶）", chkSpeedup,
                checked -> chkSpeedup = checked));
        options.addView(check("背包不足时允许钻石购买（召回道具 800 钻／加速器 400 钻，每任务上限 10 个）",
                chkDiamond, checked -> chkDiamond = checked));
        body.addView(options);
        applyCascade(options);

        // 风险长文案（逐字照抄网页版）
        TextView warning = UiComponents.caption(act,
                "逐个账号执行与单个创建一致的资格预检：离线、名额不足、不同赛区或仍被其它模块占用的账号会单独列出原因，不影响其它账号；可在开放前数天创建。任务会在开放前 15 分钟上号准备：行军途中的部队用「出征召回」道具、采集等工作状态免费撤回，回城预计晚于开放前 2 分钟时用行军加速器压缩时间；退盟、召回、加速都在开放前 15 分钟的准备窗口内执行，创建任务时不会立刻动作；请确认账号可以退盟。",
                UiStyle.WARN_INK);
        warning.setBackground(UiStyle.roundRectDp(act, UiStyle.WARN_BG, 10));
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        body.addView(warning, wlp);

        // 结果区
        resultBox = new LinearLayout(act);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(resultBox);

        // 底部
        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);
        footer.addView(UiComponents.compactSecondaryButton(act, "关闭", () -> {
            if (dialog != null) dialog.dismiss();
        }));
        tvSubmit = UiComponents.compactPrimaryButton(act, "开始批量创建", this::submit);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp2.leftMargin = dp(8);
        footer.addView(tvSubmit, slp2);
        panel.addView(footer);

        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(panel)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog = dlg;
        int width = Math.min(dp(560), act.getResources().getDisplayMetrics().widthPixels - dp(16));
        width = Math.max(dp(280), width);
        panel.setLayoutParams(new ViewGroup.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(width, (int) (act.getResources().getDisplayMetrics().heightPixels * 0.86));
        }
        loadAccounts();
        refreshSubmitState();
    }

    // ==================== 数据 ====================

    private void loadAccounts() {
        if (loadingAccounts) return;
        loadingAccounts = true;
        MigrantApi.accounts(act, who, (code, message, data) -> act.runOnUiThread(() -> {
            loadingAccounts = false;
            accounts.clear();
            if (code == 0) {
                accounts.addAll(MigrantModels.accountsOf(data));
            } else {
                UiStyle.toast(act, "读取账号列表失败："
                        + (message == null || message.isEmpty() ? "未知错误" : message));
            }
            renderAccounts();
            refreshSubmitState();
        }));
    }

    private void renderAccounts() {
        if (accountBox == null) return;
        accountBox.removeAllViews();
        if (accounts.isEmpty()) {
            accountBox.addView(UiComponents.caption(act,
                    loadingAccounts ? "正在读取账号列表…" : "账号列表为空（服务端账号池无可用角色）",
                    UiStyle.INK3));
            return;
        }
        for (final MigrantModels.AccountItem a : accounts) {
            CheckBox cb = new CheckBox(act);
            cb.setText(a.label());
            cb.setTextSize(UiComponents.TEXT_LABEL);
            cb.setTextColor(UiStyle.INK2);
            cb.setChecked(selected.contains(a.accountRoleId));
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selected.add(a.accountRoleId);
                else selected.remove(a.accountRoleId);
                refreshSubmitState();
            });
            accountBox.addView(cb);
        }
    }

    private void chooseKingdom() {
        if (overview == null || overview.kingdoms.isEmpty()) {
            UiStyle.toast(act, "暂无可选王国（等待活动配置同步）");
            return;
        }
        final List<MigrantModels.Kingdom> list = overview.kingdoms;
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = MigrantModels.kingdomOptionLabel(list.get(i));
        }
        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择目标王国")
                .setItems(items, (d, which) -> {
                    targetKid = list.get(which).kid;
                    tvTarget.setText(MigrantModels.kingdomLabel(overview, targetKid));
                    refreshSubmitState();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSubmitState() {
        if (tvSubmit == null) return;
        boolean ok = !submitting && !selected.isEmpty() && targetKid > 0;
        tvSubmit.setEnabled(ok);
        tvSubmit.setText(submitting ? "创建中…" : "开始批量创建");
    }

    // ==================== 提交 ====================

    /**
     * 提交前的危险操作二次确认（与单角色「创建边界任务」同等级）。
     *
     * <p>正文必须让用户看清三件事，否则「批量」会放大误操作：将创建多少个角色、
     * 迁往哪个目标王国、服务端到点会自动做哪些动作（按复选框实际状态拼）。
     */
    private void submit() {
        if (submitting) return;
        if (selected.isEmpty()) {
            UiStyle.toast(act, "请先选择需要移民的账号");
            return;
        }
        if (targetKid <= 0) {
            UiStyle.toast(act, "请先选择目标王国");
            return;
        }
        final List<Long> ids = new ArrayList<>(selected);
        String message = "将为 " + ids.size() + " 个角色创建前往 "
                + MigrantModels.kingdomLabel(overview, targetKid) + " 的移民任务。"
                + "逐个账号执行资格预检，预检不通过的账号会单独列出原因、不影响其它账号。"
                + MigrantModels.prepareSummary(chkExitAlliance, chkRecall,
                chkSpeedup, chkDiamond);
        UiStyle.showConfirmDialog(act, "确认批量创建移民任务", message,
                "开始批量创建", UiStyle.BRAND_TEXT, () -> doSubmit(ids));
    }

    /** 确认后真正发起 {@code bulk}（逐角色独立，失败隔离）。 */
    private void doSubmit(final List<Long> ids) {
        if (submitting) return;
        submitting = true;
        refreshSubmitState();
        MigrantApi.bulk(act, who, targetKid, ids, chkExitAlliance, chkRecall,
                chkSpeedup, chkDiamond, advanceMillis, (code, message, data) ->
                        act.runOnUiThread(() -> {
                            if (act.isFinishing() || (android.os.Build.VERSION.SDK_INT
                                    >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1
                                    && act.isDestroyed())) {
                                return;
                            }
                            submitting = false;
                            if (code != 0) {
                                refreshSubmitState();
                                UiStyle.toast(act, "批量创建失败："
                                        + (message == null || message.isEmpty()
                                        ? "未知错误" : message));
                                return;
                            }
                            result = MigrantModels.bulkOf(data);
                            if (result == null) {
                                result = new MigrantModels.BulkResult();
                                result.requested = ids.size();
                            }
                            int okCount = result.created.size();
                            int failCount = result.failed.size();
                            UiStyle.toast(act, failCount > 0
                                    ? "批量创建完成：成功 " + okCount + "，失败 " + failCount
                                    : "批量创建完成：成功 " + okCount);
                            renderResult();
                            refreshSubmitState();
                            if (onChanged != null) onChanged.run();
                        }));
    }

    private void renderResult() {
        if (resultBox == null) return;
        resultBox.removeAllViews();
        if (result == null) return;
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(UiStyle.roundRectStroke(act, UiStyle.SURFACE_2, 12, UiStyle.LINE));
        block.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        block.setLayoutParams(lp);

        TextView head = UiComponents.text(act,
                "已创建 " + result.created.size() + " / " + result.requested,
                UiComponents.TEXT_LABEL, UiStyle.INK, true);
        block.addView(head);
        for (MigrantModels.Failure f : result.failed) {
            String name = labelOf(f.accountRoleId);
            TextView row = UiComponents.caption(act, name + "：" + f.message, UiStyle.ERR_INK);
            row.setPadding(0, dp(4), 0, 0);
            block.addView(row);
        }
        resultBox.addView(block);
    }

    private String labelOf(long accountRoleId) {
        for (MigrantModels.AccountItem a : accounts) {
            if (a.accountRoleId == accountRoleId) return a.label();
        }
        return "角色 " + accountRoleId;
    }

    // ==================== 组件 ====================

    private interface OnCheck {
        void onCheck(boolean checked);
    }

    private CheckBox check(String text, boolean checked, final OnCheck cb) {
        CheckBox box = new CheckBox(act);
        box.setText(text);
        box.setTextSize(UiComponents.TEXT_LABEL);
        box.setTextColor(UiStyle.INK2);
        box.setChecked(checked);
        box.setPadding(0, dp(6), 0, dp(6));
        box.setOnCheckedChangeListener((v, isChecked) -> cb.onCheck(isChecked));
        return box;
    }

    /** 复选框 3/4 依赖「开放前自动收回外出部队」（与主表单同规则）。 */
    private void applyCascade(LinearLayout options) {
        for (int i = 0; i < options.getChildCount(); i++) {
            View v = options.getChildAt(i);
            if (!(v instanceof CheckBox)) continue;
            if (i < 2) continue;
            CheckBox cb = (CheckBox) v;
            cb.setEnabled(chkRecall);
            cb.setAlpha(chkRecall ? 1f : 0.45f);
        }
    }

    private TextView label(String text) {
        TextView v = UiComponents.caption(act, text, UiStyle.INK2);
        v.setPadding(dp(2), dp(12), 0, dp(2));
        return v;
    }

    private int dp(int v) {
        return UiStyle.dp(act, v);
    }
}
