package com.carlos.vault.migrant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
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

/**
 * 请求审计弹窗（1:1 复刻网页版 {@code MigrantRequestDetailsDialog}，模板 700-1030 行）。
 *
 * <p>结构：头部（kicker + 角色 + 路由 + 请求数 + 状态徽标 + 最终响应码）→
 * 6 项摘要（角色 / 目标王国 / 任务状态 / 请求记录 / 实际迁入目标 / 最终响应码）→
 * 错误提示 → <b>执行时间轴（10 项）</b>（计划开放 / 任务创建 / 进入就绪 / 提交窗启动 /
 * 首次请求发送 / 首次游戏服时刻 / 最后请求发送 / 首次响应完成 / 最后响应完成 / 任务完成）→
 * <b>对时指标</b>（时钟来源 / 边界游戏服时刻 / 边界主机偏差 / 心跳 RTT / 时钟同步次数 /
 * 最近校时 / 请求级对时覆盖 / 首次请求对时状态）→ 逐次请求卡片（含 pretty JSON 请求体/响应）→ 空态。
 *
 * <p>「游戏服时刻」一律取 {@code server_now_millis}（角色 GameServerClock），
 * 未对时显示「未同步」，绝不用本机墙钟冒充（与网页版一致）。
 */
public final class MigrantAttemptsDialog {

    private final Activity act;
    private final MigrantApi.Identity who;
    private final MigrantModels.Job job;

    private Dialog dialog;
    private LinearLayout body;
    private MigrantModels.Attempts data;
    private boolean loading;

    private MigrantAttemptsDialog(Activity act, MigrantApi.Identity who, MigrantModels.Job job) {
        this.act = act;
        this.who = who;
        this.job = job;
    }

    /** 打开请求审计弹窗。 */
    public static void show(Activity act, MigrantApi.Identity who, MigrantModels.Job job) {
        if (act == null || job == null || job.jobId == null || job.jobId.isEmpty()) {
            if (act != null) UiStyle.toast(act, "任务缺少 job_id，无法查看请求详情");
            return;
        }
        new MigrantAttemptsDialog(act, who, job).open();
    }

    // ==================== 生命周期 ====================

    private void open() {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiStyle.roundRectDp(act, UiStyle.CARD, 20));
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));

        TextView title = UiComponents.text(act, "自由移民 · 迁入请求详情",
                UiComponents.TEXT_HEADLINE, UiStyle.INK, true);
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

        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(10), 0, 0);
        footer.addView(UiComponents.compactSecondaryButton(act, "刷新", this::load));
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
        int width = Math.min(dp(1320), act.getResources().getDisplayMetrics().widthPixels - dp(16));
        width = Math.max(dp(300), width);
        panel.setLayoutParams(new ViewGroup.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(width, (int) (act.getResources().getDisplayMetrics().heightPixels * 0.88));
        }
        render();
        load();
    }

    // ==================== 数据 ====================

    private void load() {
        if (loading) return;
        loading = true;
        render();
        MigrantApi.attempts(act, who, job.jobId, (code, message, json) ->
                act.runOnUiThread(() -> {
                    if (gone()) return;
                    loading = false;
                    if (code != 0) {
                        data = null;
                        UiStyle.toast(act, "读取请求记录失败："
                                + (message == null || message.isEmpty() ? "未知错误" : message));
                        render();
                        return;
                    }
                    data = MigrantModels.attemptsOf(json);
                    render();
                }));
    }

    // ==================== 渲染 ====================

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        body.addView(heroBlock());
        body.addView(summaryBlock());

        String error = data != null ? data.error : (job.error == null ? "" : job.error);
        if (error != null && !error.isEmpty()) {
            TextView err = UiComponents.caption(act, error, UiStyle.WARN_INK);
            err.setBackground(UiStyle.roundRectDp(act, UiStyle.WARN_BG, 10));
            err.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            body.addView(err, lp);
        }

        body.addView(timelineBlock());
        body.addView(clockBlock());

        if (data != null && !data.attempts.isEmpty()) {
            for (MigrantModels.Attempt at : data.attempts) {
                body.addView(attemptCard(at));
            }
        } else if (!loading) {
            body.addView(emptyBlock());
        }
    }

    private View heroBlock() {
        LinearLayout hero = new LinearLayout(act);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(0, dp(10), 0, 0);

        LinearLayout copy = new LinearLayout(act);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = UiComponents.text(act, "自由移民 · 请求审计",
                UiComponents.TEXT_MICRO, UiStyle.INK3, true);
        kicker.setLetterSpacing(0.1f);
        copy.addView(kicker);
        copy.addView(UiComponents.text(act, accountName(), UiComponents.TEXT_HEADLINE,
                UiStyle.INK, true));
        copy.addView(UiComponents.caption(act,
                route() + " · " + attemptCount() + " 次迁入请求", UiStyle.INK2));
        hero.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout state = new LinearLayout(act);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setGravity(Gravity.END);
        state.addView(UiStyle.badge(act, MigrantModels.auditStatusTone(status()), statusLabel()));
        TextView code = UiComponents.caption(act, "最终响应码 " + responseCodeText(),
                UiStyle.INK3);
        code.setPadding(0, dp(4), 0, 0);
        state.addView(code);
        hero.addView(state);
        return hero;
    }

    private View summaryBlock() {
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(UiStyle.roundRectDp(act, UiStyle.SURFACE_2, 12));
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        block.setLayoutParams(lp);

        addPairRow(block, "角色", accountName(), "目标王国", route());
        addPairRow(block, "任务状态", statusLabel(), "请求记录",
                recordCount() + " / " + declaredAttemptCount() + " 条");
        addPairRow(block, "实际迁入目标", actualTarget(), "最终响应码", responseCodeText());
        return block;
    }

    private void addPairRow(LinearLayout parent, String l1, String v1, String l2, String v2) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        row.addView(cell(l1, v1), weight(1f));
        row.addView(cell(l2, v2), weight(1f));
        parent.addView(row);
    }

    private View cell(String label, String value) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.caption(act, label, UiStyle.INK3));
        TextView v = UiComponents.text(act, value, UiComponents.TEXT_LABEL, UiStyle.INK, true);
        v.setSingleLine(false);
        col.addView(v);
        return col;
    }

    /** 执行时间轴（10 项，文案逐字照抄网页版）。 */
    private View timelineBlock() {
        LinearLayout block = section("执行时间轴", "本机时间与游戏服对时证据分开显示",
                recordCount() + " 次请求");

        long freedomStart = pick(data == null ? 0 : data.freedomStartMillis, job.freedomStartMillis);
        long created = pick(data == null ? 0 : data.createdAtMillis, job.createdAtMillis);
        long armed = pick(data == null ? 0 : data.armedAtMillis, job.armedAtMillis);
        long sent = pick(data == null ? 0 : data.sentAtMillis, job.sentAtMillis);
        long firstSent = data == null ? 0 : pick(data.firstAttemptAtMillis, minSent());
        long firstServerNow = data == null ? 0 : (data.attempts.isEmpty()
                ? 0 : data.attempts.get(0).serverNowMillis);
        long lastSent = data == null ? 0 : pick(data.lastAttemptAtMillis, maxSent());
        long firstResp = data == null ? 0 : pick(data.firstResponseAtMillis, minResponse());
        long lastResp = data == null ? 0 : pick(data.lastResponseAtMillis, maxResponse());
        long finished = data == null ? 0 : data.finishedAtMillis;

        addTimeline(block, "计划开放", MigrantModels.fmtTime(freedomStart), "活动自由移民边界");
        addTimeline(block, "任务创建", MigrantModels.fmtTime(created), "本机时间");
        addTimeline(block, "进入就绪", MigrantModels.fmtTime(armed),
                MigrantModels.boundaryDelta(armed, freedomStart));
        addTimeline(block, "提交窗启动", MigrantModels.fmtTime(sent),
                MigrantModels.boundaryDelta(sent, freedomStart));
        addTimeline(block, "首次请求发送", MigrantModels.fmtTime(firstSent),
                MigrantModels.boundaryDelta(firstSent, freedomStart));
        addTimeline(block, "首次游戏服时刻", MigrantModels.fmtTime(firstServerNow),
                MigrantModels.boundaryDelta(firstServerNow, freedomStart));
        addTimeline(block, "最后请求发送", MigrantModels.fmtTime(lastSent),
                MigrantModels.sinceFirst(lastSent, firstSent));
        addTimeline(block, "首次响应完成", MigrantModels.fmtTime(firstResp), "本机时间");
        addTimeline(block, "最后响应完成", MigrantModels.fmtTime(lastResp),
                MigrantModels.sinceFirst(lastResp, firstSent));
        addTimeline(block, "任务完成", MigrantModels.fmtTime(finished),
                finished > 0 ? MigrantModels.sinceFirst(finished, firstSent) : "任务仍在执行");
        return block;
    }

    private void addTimeline(LinearLayout parent, String label, String value, String hint) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView l = UiComponents.caption(act, label, UiStyle.INK3);
        row.addView(l);
        TextView v = UiComponents.numeric(act, value, UiComponents.TEXT_LABEL, UiStyle.INK, true);
        row.addView(v);
        TextView h = UiComponents.caption(act, hint, UiStyle.INK2);
        row.addView(h);
        parent.addView(row);
    }

    /** 对时指标（8 项 + 覆盖 badge）。 */
    private View clockBlock() {
        int synced = data == null ? 0 : data.syncedCount();
        int total = data == null ? 0 : data.attempts.size();
        String tone = MigrantModels.syncSummaryTone(data);
        LinearLayout block = section("对时指标",
                "游戏服时刻来自角色 GameServerClock，不使用浏览器墙钟冒充",
                MigrantModels.syncSummary(data));

        addPairRow(block, "时钟来源", MigrantModels.clockSourceLabel(
                        data != null ? data.clockSource : job.clockSource),
                "边界游戏服时刻", MigrantModels.fmtTimeOrMissing(
                        data != null ? data.clockEpochMillis : job.clockEpochMillis));
        addPairRow(block, "边界主机偏差", MigrantModels.fmtClockOffset(
                        data == null ? 0 : data.hostClockOffsetMillis,
                        data != null && MigrantModels.epochReady(
                                data.clockEpochMillis, data.clockSource)),
                "心跳 RTT", MigrantModels.fmtRtt(data == null
                        ? job.heartbeatRttMillis : data.heartbeatRttMillis));
        addPairRow(block, "时钟同步次数", MigrantModels.fmtCount(data == null
                        ? 0 : data.clockSyncCount),
                "最近校时", MigrantModels.fmtTimeOrMissing(data == null
                        ? 0 : data.clockSyncedAtMillis));
        addPairRow(block, "请求级对时覆盖", MigrantModels.syncCoverage(data),
                "首次请求对时状态", data == null || data.attempts.isEmpty()
                        ? "未记录" : MigrantModels.attemptSyncLabel(data.attempts.get(0)));
        if (total > 0) {
            TextView cov = UiComponents.caption(act, MigrantModels.syncSummary(data),
                    MigrantModels.syncSummaryTone(data).equals("ok") ? UiStyle.OK_INK
                            : MigrantModels.syncSummaryTone(data).equals("err")
                            ? UiStyle.ERR_INK : UiStyle.WARN_INK);
            cov.setPadding(0, dp(6), 0, 0);
            block.addView(cov);
        }
        return block;
    }

    private LinearLayout section(String title, String desc, String hint) {
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 16, UiStyle.LINE));
        block.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        block.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(act);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiComponents.text(act, title, UiComponents.TEXT_BODY, UiStyle.INK, true));
        copy.addView(UiComponents.caption(act, desc, UiStyle.INK3));
        head.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(UiComponents.caption(act, hint, UiStyle.INK2));
        block.addView(head);
        return block;
    }

    /** 单次请求卡片（{#} + 阶段 + 结果 + 明细 + pretty JSON）。 */
    private View attemptCard(final MigrantModels.Attempt at) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiStyle.roundRectStroke(act, UiStyle.SURFACE_2, 12, UiStyle.LINE));
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout no = new LinearLayout(act);
        no.setOrientation(LinearLayout.HORIZONTAL);
        no.setGravity(Gravity.CENTER_VERTICAL);
        no.addView(UiComponents.numeric(act, "#" + at.attemptNo,
                UiComponents.TEXT_LABEL, UiStyle.INK3, true));
        TextView phase = UiStyle.badge(act, MigrantModels.phaseTone(at.phase),
                MigrantModels.phaseLabelOf(at.phase, at.phaseLabel));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(6);
        no.addView(phase, plp);
        head.addView(no, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(UiStyle.badge(act, MigrantModels.outcomeTone(at.outcome),
                at.outcomeLabel == null || at.outcomeLabel.isEmpty() ? "-" : at.outcomeLabel));
        card.addView(head);

        addPairRow(card, "本机发送", MigrantModels.fmtTime(at.sentAtMillis),
                "游戏服时刻", MigrantModels.attemptServerNow(at));
        addPairRow(card, "本机响应完成", MigrantModels.fmtTime(at.responseAtMillis),
                "请求 RTT", MigrantModels.fmtRtt(at.latencyMillis));
        addPairRow(card, "主机时钟偏差",
                MigrantModels.fmtClockOffset(at.hostClockOffsetMillis, at.serverClockReady),
                "心跳 RTT", MigrantModels.fmtRtt(at.heartbeatRttMillis));
        addPairRow(card, "对时状态", MigrantModels.attemptSyncLabel(at),
                "响应码", at.responseCode == null ? "—" : String.valueOf(at.responseCode));

        String note = at.responseText != null && !at.responseText.isEmpty()
                ? at.responseText : at.error;
        if (note != null && !note.isEmpty()) {
            TextView nv = UiComponents.caption(act, note, UiStyle.INK2);
            nv.setPadding(0, dp(6), 0, 0);
            card.addView(nv);
        }

        // 折叠：协议 session + 请求体 + 原始响应
        final LinearLayout detail = new LinearLayout(act);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(View.GONE);
        String session = at.requestSession == null || at.requestSession.isEmpty()
                ? "—" : at.requestSession;
        detail.addView(UiComponents.caption(act,
                "session " + session + (at.probe ? " · 不等回包探针" : ""), UiStyle.INK3));
        detail.addView(payloadTitle("请求体"));
        detail.addView(payloadText(MigrantModels.prettyJson(at.requestJson)));
        detail.addView(payloadTitle("原始响应"));
        detail.addView(payloadText(MigrantModels.prettyJson(at.responseJson)));

        TextView toggle = UiComponents.caption(act, "展开协议 session、请求体与原始响应", UiStyle.BRAND_TEXT);
        toggle.setPadding(0, dp(6), 0, 0);
        toggle.setOnClickListener(v -> {
            boolean show = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "收起协议明细" : "展开协议 session、请求体与原始响应");
        });
        card.addView(toggle);
        card.addView(detail);
        return card;
    }

    private TextView payloadTitle(String text) {
        TextView v = UiComponents.caption(act, text, UiStyle.INK2);
        v.setPadding(0, dp(6), 0, dp(2));
        return v;
    }

    private TextView payloadText(String text) {
        TextView v = UiComponents.text(act, text, UiComponents.TEXT_MICRO, UiStyle.INK2, false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setBackground(UiStyle.roundRectDp(act, UiStyle.SURFACE_3, 8));
        v.setPadding(dp(8), dp(6), dp(8), dp(6));
        v.setTextIsSelectable(true);
        return v;
    }

    private View emptyBlock() {
        return UiStyle.emptyState(act, "◷", "还没有请求记录",
                "这个任务还没有请求记录；请求发出后会按次显示在这里", null, null);
    }

    // ==================== 取值辅助 ====================

    private static long pick(long primary, long fallback) {
        return primary > 0 ? primary : fallback;
    }

    private long minSent() {
        long v = 0;
        if (data == null) return 0;
        for (MigrantModels.Attempt a : data.attempts) {
            if (a.sentAtMillis > 0 && (v == 0 || a.sentAtMillis < v)) v = a.sentAtMillis;
        }
        return v;
    }

    private long maxSent() {
        long v = 0;
        if (data == null) return 0;
        for (MigrantModels.Attempt a : data.attempts) {
            if (a.sentAtMillis > v) v = a.sentAtMillis;
        }
        return v;
    }

    private long minResponse() {
        long v = 0;
        if (data == null) return 0;
        for (MigrantModels.Attempt a : data.attempts) {
            if (a.responseAtMillis > 0 && (v == 0 || a.responseAtMillis < v)) {
                v = a.responseAtMillis;
            }
        }
        return v;
    }

    private long maxResponse() {
        long v = 0;
        if (data == null) return 0;
        for (MigrantModels.Attempt a : data.attempts) {
            if (a.responseAtMillis > v) v = a.responseAtMillis;
        }
        return v;
    }

    private String accountName() {
        if (data != null && data.accountName != null && !data.accountName.isEmpty()) {
            return data.accountName;
        }
        if (job.accountName != null && !job.accountName.isEmpty()) return job.accountName;
        return "移民任务";
    }

    private String route() {
        long o = job.originalKid;
        long t = job.targetKid;
        if (data != null) {
            if (data.targetKid > 0) t = data.targetKid;
            if (o <= 0) o = job.originalKid;
        }
        return o > 0 && t > 0 ? "#" + o + " → #" + t : "—";
    }

    private String actualTarget() {
        if (data == null) return "—";
        long kid = data.targetKid > 0 ? data.targetKid : job.targetKid;
        String name = data.targetName == null ? "" : data.targetName;
        if (kid <= 0) return "—";
        return "#" + kid + (name.isEmpty() ? "" : " · " + name);
    }

    private String status() {
        if (data != null && data.status != null && !data.status.isEmpty()) return data.status;
        return job.status;
    }

    private String statusLabel() {
        if (data != null && data.statusLabel != null && !data.statusLabel.isEmpty()) {
            return data.statusLabel;
        }
        if (job.statusLabel != null && !job.statusLabel.isEmpty()) return job.statusLabel;
        return "—";
    }

    private String responseCodeText() {
        Integer code = data != null && data.responseCode != null
                ? data.responseCode : job.responseCode;
        return code == null ? "—" : String.valueOf(code);
    }

    private int recordCount() {
        return data == null ? 0 : data.attempts.size();
    }

    private int declaredAttemptCount() {
        if (data != null && data.attemptCount > 0) return data.attemptCount;
        return job.attemptCount;
    }

    private int attemptCount() {
        return recordCount() > 0 ? recordCount() : declaredAttemptCount();
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
