package com.carlos.vault.migrant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撤退详情弹窗（1:1 复刻网页版 {@code MigrantMarchRecallDialog}，模板 59-260 行）。
 *
 * <p>结构：标题 {@code 角色名 · 撤退详情} → 4 项事实带（自由移民开放 / 自动召回窗口 /
 * 准备截止线 / 账号状态）→ 提示条 → 行军列表（部队 #id、类型/状态徽标、目标坐标、
 * 预计回城、召回方式、逐支操作与提示）→ 非队列行军队列提示 → 结果消息 →
 * 底部 {@code 刷新 / 全部召回（N 支） / 关闭}。
 *
 * <p>召回流程与网页版一致：先 {@code mode=item}；服务端回 {@code requires_diamond=true}
 * 时弹二次确认「背包中的「X」不足。是否用钻石购买 1 个（800 钻）并立即召回？」，
 * 确认后改发 {@code mode=diamond}。所有危险动作都经 {@link UiStyle#showConfirmDialog}。
 */
public final class MigrantRecallDialog {

    private final Activity act;
    private final MigrantApi.Identity who;
    private final MigrantModels.Job job;
    private final Runnable onChanged;

    private Dialog dialog;
    private LinearLayout body;
    private TextView tvAllRecall;

    private MigrantModels.MarchInfo info;
    private boolean loading;
    private long busyMarchId = -1L;
    private final Map<Long, String> rowErrors = new LinkedHashMap<>();

    private MigrantRecallDialog(Activity act, MigrantApi.Identity who,
                                MigrantModels.Job job, Runnable onChanged) {
        this.act = act;
        this.who = who;
        this.job = job;
        this.onChanged = onChanged;
    }

    /** 打开撤退详情弹窗。 */
    public static void show(Activity act, MigrantApi.Identity who,
                            MigrantModels.Job job, Runnable onChanged) {
        if (act == null || job == null || job.jobId == null || job.jobId.isEmpty()) {
            if (act != null) UiStyle.toast(act, "任务缺少 job_id，无法查看撤退详情");
            return;
        }
        new MigrantRecallDialog(act, who, job, onChanged).open();
    }

    // ==================== 生命周期 ====================

    private void open() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiStyle.roundRectDp(act, UiStyle.CARD, 20));
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));

        TextView title = UiComponents.text(act,
                (job.accountName == null || job.accountName.isEmpty()
                        ? "移民任务" : job.accountName) + " · 撤退详情",
                UiComponents.TEXT_HEADLINE, UiStyle.INK, true);
        title.setSingleLine(true);
        panel.addView(title);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(8);
        panel.addView(scroll, slp);

        // 底部操作
        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);

        TextView refresh = UiComponents.compactSecondaryButton(act, "刷新", this::load);
        footer.addView(refresh);

        tvAllRecall = UiComponents.compactSecondaryButton(act, "全部召回", () -> recall(0L));
        tvAllRecall.setTextColor(UiStyle.enabledTextColors(UiStyle.ERR_INK));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        footer.addView(tvAllRecall, alp);

        TextView close = UiComponents.compactPrimaryButton(act, "关闭", () -> {
            if (dialog != null) dialog.dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        footer.addView(close, clp);
        panel.addView(footer);

        AlertDialog dlg = new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(panel)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog = dlg;
        int width = Math.min(dp(880), act.getResources().getDisplayMetrics().widthPixels - dp(16));
        panel.setLayoutParams(new ViewGroup.LayoutParams(
                Math.max(dp(280), width), ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(Math.max(dp(280), width),
                    (int) (act.getResources().getDisplayMetrics().heightPixels * 0.82));
        }
        render();
        load();
    }

    // ==================== 数据 ====================

    private void load() {
        if (loading) return;
        loading = true;
        render();
        MigrantApi.marches(act, who, job.jobId, (code, message, data) ->
                act.runOnUiThread(() -> {
                    if (gone()) return;
                    loading = false;
                    if (code != 0) {
                        info = null;
                        UiStyle.toast(act, "读取部队失败："
                                + (message == null || message.isEmpty() ? "未知错误" : message));
                        render();
                        return;
                    }
                    info = MigrantModels.marchesOf(data);
                    rowErrors.clear();
                    render();
                }));
    }

    /** 召回（marchId=0 表示全部）。 */
    private void recall(final long marchId) {
        if (info == null || loading) return;
        if (!info.online) {
            UiStyle.toast(act, "账号未上线，无法召回部队");
            return;
        }
        if (!info.manualRecallAllowed) {
            UiStyle.toast(act, info.manualRecallBlockReason == null
                    || info.manualRecallBlockReason.isEmpty()
                    ? "当前阶段不能手动召回" : info.manualRecallBlockReason);
            return;
        }
        MigrantModels.March target = findMarch(marchId);
        if (marchId > 0 && target == null) return;
        if (marchId > 0 && busyMarchId >= 0) return;
        if (marchId == 0 && (busyMarchId == 0 || info.pendingCount() <= 0)) return;

        String text = MigrantModels.recallConfirmText(target, info.pendingCount());
        UiStyle.showConfirmDialog(act, marchId > 0 ? "确认召回部队" : "确认全部召回",
                text, "发送召回", UiStyle.ERR_INK,
                () -> sendRecall(marchId, MigrantApi.RECALL_MODE_ITEM));
    }

    private void sendRecall(final long marchId, final String mode) {
        busyMarchId = marchId;
        render();
        MigrantApi.recall(act, who, job.jobId, marchId, mode, (code, message, data) ->
                act.runOnUiThread(() -> {
                    if (gone()) return;
                    if (code != 0) {
                        busyMarchId = -1L;
                        render();
                        UiStyle.toast(act, "召回失败："
                                + (message == null || message.isEmpty() ? "未知错误" : message));
                        return;
                    }
                    MigrantModels.RecallResult result = MigrantModels.recallOf(data);
                    if (result == null) {
                        busyMarchId = -1L;
                        render();
                        return;
                    }
                    if (result.requiresDiamond) {
                        busyMarchId = -1L;
                        render();
                        String item = result.recallItemName();
                        UiStyle.showConfirmDialog(act, "召回道具不足",
                                "背包中的「" + item + "」不足。是否用钻石购买 1 个（800 钻）并立即召回？",
                                "钻石召回", UiStyle.BRAND_TEXT,
                                () -> sendRecall(marchId, MigrantApi.RECALL_MODE_DIAMOND));
                        return;
                    }
                    busyMarchId = -1L;
                    applyResult(result);
                }));
    }

    private void applyResult(MigrantModels.RecallResult result) {
        rowErrors.clear();
        for (MigrantModels.RecallItem it : result.results) {
            if (!it.success) {
                rowErrors.put(it.marchId,
                        it.message == null || it.message.isEmpty() ? "召回未发出" : it.message);
            }
        }
        if (result.recalled > 0) {
            UiStyle.toast(act, result.message == null || result.message.isEmpty()
                    ? "召回指令已发送" : result.message);
        } else if (result.requiresDiamond) {
            UiStyle.toast(act, "召回道具不足，未发送新的召回指令");
        } else {
            UiStyle.toast(act, result.message == null || result.message.isEmpty()
                    ? "没有需要召回的部队" : result.message);
        }
        if (onChanged != null) onChanged.run();
        load();
    }

    private MigrantModels.March findMarch(long marchId) {
        if (info == null || marchId <= 0) return null;
        for (MigrantModels.March m : info.marches) {
            if (m.marchId == marchId) return m;
        }
        return null;
    }

    // ==================== 渲染 ====================

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        body.addView(factBand());
        body.addView(noteLine());

        if (loading && info == null) {
            body.addView(UiComponents.caption(act, "正在读取任务与账号状态…", UiStyle.INK3));
        } else if (info != null && !info.marches.isEmpty()) {
            for (MigrantModels.March m : info.marches) {
                body.addView(marchRow(m));
            }
        } else {
            body.addView(emptyBlock());
        }

        if (info != null && info.nonQueueMarchCount > 0) {
            TextView t = UiComponents.caption(act, "另有 " + info.nonQueueMarchCount
                    + " 支行军不占用出征队列（如护送货车），不计入移民条件", UiStyle.INK3);
            t.setPadding(0, dp(8), 0, 0);
            body.addView(t);
        }
        if (info != null && info.recallMessage != null && !info.recallMessage.isEmpty()) {
            TextView t = UiComponents.caption(act, info.recallMessage, UiStyle.WARN_INK);
            t.setPadding(0, dp(8), 0, 0);
            body.addView(t);
        }

        if (tvAllRecall != null) {
            boolean canAll = info != null && info.online && info.manualRecallAllowed
                    && info.pendingCount() > 0 && busyMarchId < 0;
            tvAllRecall.setText("全部召回（" + (info == null ? 0 : info.pendingCount()) + " 支）");
            tvAllRecall.setEnabled(canAll);
        }
    }

    private View factBand() {
        LinearLayout band = new LinearLayout(act);
        band.setOrientation(LinearLayout.VERTICAL);
        band.setBackground(UiStyle.roundRectDp(act, UiStyle.SURFACE_2, 12));
        band.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout r1 = new LinearLayout(act);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        // 服务端当前只回 preparation_deadline_millis；窗口起点与开放时刻由 job 兜底
        long freedomStart = info != null && info.freedomStartMillis > 0
                ? info.freedomStartMillis : job.freedomStartMillis;
        long windowStart = info != null && info.preparationStartMillis > 0
                ? info.preparationStartMillis : job.preparationStartMillis;
        // 准备截止线 = 自由移民开放前 2 分钟（契约 §4「就绪目标」）
        long deadline = info != null && info.preparationDeadlineMillis > 0
                ? info.preparationDeadlineMillis
                : (freedomStart > 0
                ? freedomStart - MigrantModels.READY_DEADLINE_BEFORE_MILLIS : 0L);
        r1.addView(factCell("自由移民开放", MigrantModels.fmtTime(freedomStart)), weight(1f));
        r1.addView(factCell("自动召回窗口", MigrantModels.fmtTime(windowStart)), weight(1f));
        band.addView(r1);

        LinearLayout r2 = new LinearLayout(act);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, dp(8), 0, 0);
        r2.addView(factCell("准备截止线", MigrantModels.fmtTime(deadline)), weight(1f));
        boolean online = info != null && info.online;
        r2.addView(factCell("账号状态", online ? "已上线（可读取部队）" : "未上线",
                online ? UiStyle.OK_INK : UiStyle.WARN_INK), weight(1f));
        band.addView(r2);
        return band;
    }

    private View factCell(String label, String value) {
        return factCell(label, value, UiStyle.INK);
    }

    private View factCell(String label, String value, int ink) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.caption(act, label, UiStyle.INK3));
        TextView v = UiComponents.text(act, value, UiComponents.TEXT_LABEL, ink, true);
        v.setSingleLine(true);
        col.addView(v);
        return col;
    }

    private View noteLine() {
        TextView t = UiComponents.caption(act, MigrantModels.recallNote(info), UiStyle.INK2);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private View marchRow(final MigrantModels.March m) {
        boolean recalled = m.recalled;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(UiStyle.roundRectStroke(act,
                recalled ? UiStyle.SURFACE_2 : UiStyle.CARD, 12,
                recalled ? UiStyle.LINE : UiStyle.LINE_STRONG));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiComponents.text(act, "部队 #" + m.marchId,
                UiComponents.TEXT_LABEL, UiStyle.INK, true));
        head.addView(tag(m.type, "idle", dp(6)));
        head.addView(tag(m.state == null || m.state.isEmpty() ? "-" : m.state,
                MigrantModels.marchStateTone(m), dp(6)));
        if (m.rally) head.addView(tag("集结行军", "idle", dp(6)));
        if (recalled) head.addView(tag("已发起召回", "warn", dp(6)));
        row.addView(head);

        LinearLayout facts = new LinearLayout(act);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(0, dp(6), 0, 0);
        LinearLayout f1 = new LinearLayout(act);
        f1.setOrientation(LinearLayout.HORIZONTAL);
        f1.addView(dtDd("目标坐标", MigrantModels.marchCoord(m)), weight(1f));
        f1.addView(dtDd("预计回城", MigrantModels.marchEta(m)), weight(1f));
        facts.addView(f1);
        LinearLayout f2 = new LinearLayout(act);
        f2.setOrientation(LinearLayout.HORIZONTAL);
        f2.setPadding(0, dp(4), 0, 0);
        f2.addView(dtDd("召回方式", MigrantModels.marchRecallMode(m)), weight(1f));
        f2.addView(new View(act), weight(1f));
        facts.addView(f2);
        row.addView(facts);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, 0);
        String hint = rowErrors.containsKey(m.marchId)
                ? rowErrors.get(m.marchId) : MigrantModels.marchHint(m, info);
        TextView hintView = UiComponents.caption(act, hint,
                rowErrors.containsKey(m.marchId) ? UiStyle.ERR_INK : UiStyle.INK2);
        hintView.setSingleLine(false);
        actions.addView(hintView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!recalled) {
            boolean canRecall = info != null && info.online && info.manualRecallAllowed
                    && m.recallable && (busyMarchId < 0 || busyMarchId == m.marchId);
            TextView btn = UiComponents.compactSecondaryButton(act,
                    busyMarchId == m.marchId ? "发送中…"
                            : (m.recallAction == null || m.recallAction.isEmpty()
                            ? "召回" : m.recallAction),
                    () -> recall(m.marchId));
            btn.setTextColor(UiStyle.enabledTextColors(UiStyle.ERR_INK));
            btn.setEnabled(canRecall);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            blp.leftMargin = dp(6);
            actions.addView(btn, blp);
        }
        row.addView(actions);
        return row;
    }

    private View dtDd(String dt, String dd) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.caption(act, dt, UiStyle.INK3));
        TextView v = UiComponents.text(act, dd, UiComponents.TEXT_LABEL, UiStyle.INK2, false);
        v.setSingleLine(false);
        col.addView(v);
        return col;
    }

    private TextView tag(String text, String tone, int leftMarginDp) {
        TextView v = UiStyle.badge(act, tone, text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(leftMarginDp);
        v.setLayoutParams(lp);
        return v;
    }

    private View emptyBlock() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(12), dp(24), dp(12), dp(12));
        boolean online = info != null && info.online;
        TextView title = UiComponents.text(act, online ? "当前没有在外部队" : "账号未上线",
                UiComponents.TEXT_BODY, UiStyle.INK, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView desc = UiComponents.caption(act, online
                ? "服务端快照显示没有部队出征在外；准备窗口内任务还会再自动复核一次"
                : "上线后才能读取部队回城时间并手动召回", UiStyle.INK2);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(4), 0, 0);
        box.addView(desc);

        if (!online) {
            TextView login = UiComponents.compactPrimaryButton(act, "上线并同步部队",
                    this::doLogin);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(180), dp(44));
            lp.topMargin = dp(12);
            box.addView(login, lp);
        }
        return box;
    }

    /** 上线并轮询等待角色会话就绪（网页版 15 次 × 1s）。 */
    private void doLogin() {
        if (job.accountRoleId <= 0) {
            UiStyle.toast(act, "该任务缺少稳定角色主键，请在移民模块账号列表里上线该角色");
            return;
        }
        UiStyle.toast(act, "正在上线角色…");
        MigrantApi.login(act, who, (code, message, data) -> act.runOnUiThread(() -> {
            if (gone()) return;
            if (code != 0) {
                UiStyle.toast(act, "上线失败："
                        + (message == null || message.isEmpty() ? "未知错误" : message));
                return;
            }
            pollOnline(0);
        }));
    }

    private void pollOnline(final int attempt) {
        if (attempt >= 15 || dialog == null || !dialog.isShowing()) {
            if (info == null || !info.online) {
                UiStyle.toast(act, "账号上线较慢，请稍后点击刷新");
            }
            return;
        }
        load();
        act.getWindow().getDecorView().postDelayed(() -> {
            if (info != null && info.online) {
                UiStyle.toast(act, "账号已上线，部队状态已同步");
                return;
            }
            pollOnline(attempt + 1);
        }, 1000L);
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** 宿主 Activity 已结束/销毁：异步回调不再碰 UI（toast 会抛 BadTokenException）。 */
    private boolean gone() {
        if (act == null || act.isFinishing()) return true;
        return android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && act.isDestroyed();
    }

    private int dp(int v) {
        return UiStyle.dp(act, v);
    }
}
