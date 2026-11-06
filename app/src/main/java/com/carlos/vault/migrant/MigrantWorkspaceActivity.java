package com.carlos.vault.migrant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;
import com.carlos.vault.daily.CurrentRoleKey;
import com.carlos.vault.daily.RoleStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 藏宝阁·自由移民工作区（1:1 复刻网页版 {@code MigrantWorkspace.vue}）。
 *
 * <p>信息结构与行为对齐网页版（{@code MigrantWorkspace-C_ZVt8cF.js} 主模板 2511-4089 行），
 * 视觉按 {@code design/UI规范.md} 落地（{@code UiStyle} 取色/圆角、{@code UiComponents} 字号与骨架）。
 *
 * <p>页面区块（与网页版一致）：
 * <ol>
 *   <li>头部：kicker「移民」+ 资格数据完整/活动未开放标签 + 账号名 + 身份 + 账号开关</li>
 *   <li>概览条：服务器时间（每秒 tick）+ 三阶段时间轴（展望 3 天/邀请 2 天/自由移民 2 天）+ 阶段标签</li>
 *   <li>数据行：当前王国 / 活动期号 / 目标王国 / 授权书 / 剩余名额</li>
 *   <li>任务表单：目标王国下拉 + 王国详情 + 4 动作 + 提前量 + 4 复选框（级联禁用）</li>
 *   <li>资格结果：checks + warnings（未预检为空态）</li>
 *   <li>任务大盘：范围/筛选切换 + 就绪 chip + 逐任务 7 列信息 + 空态双文案</li>
 *   <li>最近历史结果</li>
 * </ol>
 *
 * <p>刷新策略（与网页版 {@code Kl({minIntervalMs:2000, fallbackMs:30000})} 一致）：
 * 前台最小 2s 间隔轮询 overview+jobs；连续失败退避到 30s；本地每秒 tick 外推服务器时间
 * 并只用文本更新（不重建视图，避免丢滚动位置与闪烁）。
 *
 * <p>身份来源：Intent extras（{@link #EXTRA_FPID}/{@link #EXTRA_SID}/{@link #EXTRA_UID}）
 * 优先，其次前台 {@code home:current_role}（与 {@code AssetCenterActivity} 同口径）。
 */
public class MigrantWorkspaceActivity extends Activity {

    public static final String EXTRA_FPID = "migrant_fpid";
    public static final String EXTRA_SID = "migrant_sid";
    public static final String EXTRA_UID = "migrant_uid";

    /** 前台最小轮询间隔（网页版 minIntervalMs）。 */
    private static final long MIN_INTERVAL_MS = 2_000L;
    /** 连续失败后的退避间隔（网页版 fallbackMs）。 */
    private static final long FALLBACK_MS = 30_000L;
    /** 失败达到该次数切退避。 */
    private static final int FALLBACK_AFTER_FAILURES = 2;
    /** 历史列表条数（全部账号 20 / 当前角色 8，网页版 {@code ze}）。 */
    private static final int HISTORY_ALL = 20;
    private static final int HISTORY_CURRENT = 8;

    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_CURRENT = "current";
    private static final String FILTER_ALL = "all";
    private static final String FILTER_ISSUE = "issue";

    // ==================== 身份 ====================

    private String fpid = "";
    private long sid;
    private long uid;
    private MigrantApi.Identity who;
    private String accountLabel = "";
    private String accountIdentityText = "";

    // ==================== 数据 ====================

    private MigrantModels.Overview overview;
    private MigrantModels.Jobs jobs = new MigrantModels.Jobs();
    private MigrantModels.Precheck precheck;
    private List<MigrantModels.AccountItem> accountItems = new ArrayList<>();

    /** 服务器时间基准（{@code overview.server_now_millis}）与本地锚点。 */
    private long serverNowBase;
    private long serverNowAnchorLocal = System.currentTimeMillis();

    private boolean online;
    private boolean destroyed;
    private boolean refreshing;
    private boolean prechecking;
    private boolean testing;
    private boolean creating;
    private int consecutiveFailures;
    private long lastRefreshAt;
    /** 上一次渲染的数据签名：仅内容实质变化才重建视图（2s 轮询不抖屏）。 */
    private String lastSignature = "";

    // ==================== 表单状态 ====================

    private long targetKid;
    private int advanceMillis;
    private boolean chkExitAlliance = true;
    private boolean chkRecall = true;
    private boolean chkSpeedup = true;
    private boolean chkDiamond = true;
    private String scope = SCOPE_ALL;
    private String filter = FILTER_ALL;

    // ==================== 视图 ====================

    private UiComponents.Page page;
    private LinearLayout box;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvServerClock;
    private TextView tvServerHint;
    private TextView tvStageBadge;
    private StageTimeline stageTimeline;

    /** 任务行随 tick 变化的文本：jobId → [倒计时, 行军文案]。 */
    private final Map<String, TextView[]> liveJobViews = new LinkedHashMap<>();
    /** 复选框级联禁用需要直接引用。 */
    private CheckBox cbSpeedup;
    private CheckBox cbDiamond;
    private EditText etAdvance;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            ui.postDelayed(this, 1_000L);
        }
    };

    // ==================== 入口 ====================

    /** 从功能页/工具页进入（账号 + 角色明确）。 */
    public static void launch(Activity from, String fpid, long sid, long uid) {
        if (from == null) return;
        Intent i = new Intent(from, MigrantWorkspaceActivity.class);
        i.putExtra(EXTRA_FPID, fpid == null ? "" : fpid);
        i.putExtra(EXTRA_SID, sid);
        i.putExtra(EXTRA_UID, uid);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }

        if (!resolveIdentity()) {
            finish();
            return;
        }
        setContentView(buildUi());
        refreshData(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        // 回到前台立即补一次（轮询期间可能已落后很久）
        if (who != null && who.valid() && !refreshing) refreshData(false);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 身份解析：Intent extras → 前台 current_role；都不可用则提示并退出。 */
    private boolean resolveIdentity() {
        Intent in = getIntent();
        if (in != null) {
            String fp = in.getStringExtra(EXTRA_FPID);
            if (fp != null && !fp.trim().isEmpty()) fpid = fp.trim();
            sid = in.getLongExtra(EXTRA_SID, 0L);
            uid = in.getLongExtra(EXTRA_UID, 0L);
        }
        if (fpid.isEmpty() || sid <= 0L) {
            try {
                String raw = getSharedPreferences("home", MODE_PRIVATE)
                        .getString("current_role", null);
                CurrentRoleKey.Parts p = CurrentRoleKey.parse(raw);
                if (p != null) {
                    if (fpid.isEmpty()) fpid = p.fpid;
                    if (sid <= 0L) sid = p.sid;
                    if (uid <= 0L) uid = p.uid;
                }
            } catch (Throwable ignored) {
            }
        }
        if (fpid.isEmpty() || sid <= 0L) {
            UiStyle.toast(this, "请先在账号页选择角色，再打开移民");
            return false;
        }
        if (uid <= 0L) uid = RoleStore.activeRoleUid(this, fpid);
        who = new MigrantApi.Identity(fpid, sid, uid);
        accountLabel = resolveAccountLabel();
        accountIdentityText = "K" + sid + (uid > 0L ? " · fid " + uid : "");
        return true;
    }

    private String resolveAccountLabel() {
        try {
            RoleStore.Role r = RoleStore.roleOf(this, fpid, sid, uid);
            if (r != null && r.name != null && !r.name.trim().isEmpty()) return r.name.trim();
        } catch (Throwable ignored) {
        }
        return uid > 0L ? "角色 " + uid : "K" + sid + " 角色";
    }

    // ==================== 页面骨架 ====================

    private View buildUi() {
        TextView refresh = UiComponents.compactSecondaryButton(this, "刷新",
                () -> refreshData(true));
        page = UiComponents.scrollPage(this, "移民", "藏宝阁 · 自由移民", this::finish, 40, refresh);
        box = page.body;
        renderAll();
        return page.root;
    }

    /** 全量重建（保留滚动位置）。 */
    private void rerender() {
        if (box == null) return;
        final int scrollY = page != null && page.scroll != null ? page.scroll.getScrollY() : 0;
        liveJobViews.clear();
        box.removeAllViews();
        box.addView(headerCard());
        box.addView(overviewCard());
        box.addView(metricsCard());
        box.addView(commandCard());
        box.addView(precheckCard());
        box.addView(jobsCard());
        View history = historyCard();
        if (history != null) box.addView(history);
        if (page != null && page.scroll != null) {
            page.scroll.post(() -> page.scroll.scrollTo(0, scrollY));
        }
        updateLiveViews();
    }

    private void renderAll() {
        rerender();
    }

    // ==================== 头部 ====================

    private View headerCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout flags = new LinearLayout(this);
        flags.setOrientation(LinearLayout.VERTICAL);

        LinearLayout flagRow = new LinearLayout(this);
        flagRow.setOrientation(LinearLayout.HORIZONTAL);
        flagRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView kicker = UiComponents.text(this, "移民", UiComponents.TEXT_MICRO,
                UiStyle.INK3, true);
        kicker.setLetterSpacing(0.12f);
        flagRow.addView(kicker);

        TextView stateBadge = UiStyle.badge(this,
                overview != null && overview.stateComplete ? "ok" : "warn",
                overview != null && overview.stateComplete ? "资格数据完整" : "等待状态同步");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        flagRow.addView(stateBadge, blp);
        flags.addView(flagRow);

        if (overview != null && !overview.activityOpen) {
            TextView closed = UiStyle.badge(this, "idle",
                    overview.message == null || overview.message.isEmpty()
                            ? "移民活动当前未开放" : overview.message);
            closed.setMaxLines(1);
            closed.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(6);
            flags.addView(closed, clp);
        }

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(0, dp(10), 0, 0);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiComponents.text(this, accountLabel, UiComponents.TEXT_HEADLINE,
                UiStyle.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);
        TextView identity = UiComponents.caption(this, accountIdentityText, UiStyle.INK2);
        identity.setPadding(0, dp(2), 0, 0);
        copy.addView(identity);
        headRow.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 账号开关：offline → 点击上线（走 login action）；online → 提示在账号页操作。
        LinearLayout toggleBox = new LinearLayout(this);
        toggleBox.setOrientation(LinearLayout.HORIZONTAL);
        toggleBox.setGravity(Gravity.CENTER_VERTICAL);
        TextView toggleLabel = UiComponents.caption(this, online ? "在线" : "离线",
                online ? UiStyle.OK_INK : UiStyle.INK3);
        toggleBox.addView(toggleLabel);
        final UiStyle.Switch[] swRef = new UiStyle.Switch[1];
        UiStyle.Switch sw = new UiStyle.Switch(this, online,
                () -> doToggleAccount(swRef[0]));
        swRef[0] = sw;
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.leftMargin = dp(8);
        toggleBox.addView(sw, swLp);
        headRow.addView(toggleBox);

        card.addView(flags);
        card.addView(headRow);
        return card;
    }

    // ==================== 概览条 ====================

    private View overviewCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockCopy = new LinearLayout(this);
        clockCopy.setOrientation(LinearLayout.VERTICAL);
        clockCopy.addView(UiComponents.caption(this, "服务器时间", UiStyle.INK3));
        tvServerClock = UiComponents.numeric(this, fmtServerClock(),
                UiComponents.TEXT_PAGE_TITLE, UiStyle.INK, true);
        clockCopy.addView(tvServerClock);
        tvServerHint = UiComponents.caption(this, serverHintText(), UiStyle.INK2);
        tvServerHint.setPadding(0, dp(2), 0, 0);
        clockCopy.addView(tvServerHint);
        clockRow.addView(clockCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvStageBadge = UiStyle.badge(this, MigrantModels.stageTone(stage()),
                MigrantModels.stageLabel(stage()));
        clockRow.addView(tvStageBadge);
        card.addView(clockRow);

        // 三阶段时间轴（展望 3 天 / 邀请 2 天 / 自由移民 2 天）
        stageTimeline = new StageTimeline(this);
        stageTimeline.setPercent(MigrantModels.timelinePercent(activityOf(), serverNow()));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        tlp.topMargin = dp(14);
        card.addView(stageTimeline, tlp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setPadding(0, dp(8), 0, 0);
        labels.addView(stageLabelCell("展望", "3 天", 3f), weight(3f));
        labels.addView(stageLabelCell("邀请", "2 天", 2f), weight(2f));
        labels.addView(stageLabelCell("自由移民", "2 天", 2f), weight(2f));
        card.addView(labels);
        return card;
    }

    private View stageLabelCell(String name, String days, float weight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(weight > 2.5f ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        TextView b = UiComponents.text(this, name, UiComponents.TEXT_LABEL, UiStyle.INK, true);
        col.addView(b);
        TextView s = UiComponents.caption(this, days, UiStyle.INK3);
        col.addView(s);
        return col;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** 三阶段进度条 + 当前位置指示器（自绘，颜色全部取自 UiStyle）。 */
    private static final class StageTimeline extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segProspect = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segInvitation = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segFreedom = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int percent;

        StageTimeline(Context ctx) {
            super(ctx);
            track.setColor(UiStyle.SURFACE_3);
            // 阶段用状态色（SOLID）低透明度表达：展望=wait、邀请=warn、自由=ok
            segProspect.setColor(UiStyle.WAIT_SOLID);
            segProspect.setAlpha(70);
            segInvitation.setColor(UiStyle.WARN_SOLID);
            segInvitation.setAlpha(70);
            segFreedom.setColor(UiStyle.OK_SOLID);
            segFreedom.setAlpha(70);
            marker.setColor(UiStyle.INK);
        }

        void setPercent(int p) {
            int next = Math.max(0, Math.min(100, p));
            if (next != percent) {
                percent = next;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            // 3:2:2 分段
            float[] ratios = {3f, 2f, 2f};
            Paint[] paints = {segProspect, segInvitation, segFreedom};
            float x = 0f;
            for (int i = 0; i < ratios.length; i++) {
                float segW = w * ratios[i] / 7f;
                c.drawRect(x, 0, x + segW, h, paints[i]);
                x += segW;
            }
            float mx = w * percent / 100f;
            float mr = Math.max(2f, h * 0.9f);
            c.drawCircle(Math.min(w - mr, Math.max(mr, mx)), h / 2f, mr, marker);
        }
    }

    // ==================== 数据行 ====================

    private View metricsCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(metricCell("当前王国",
                MigrantModels.kingdomLabel(overview, overview == null ? 0 : overview.currentKid)), weight(1f));
        row1.addView(metricCell("活动期号", overview == null
                || overview.activity.serialNo.isEmpty() ? "-" : overview.activity.serialNo), weight(1f));
        row1.addView(metricCell("目标王国",
                targetKid > 0 ? MigrantModels.kingdomLabel(overview, targetKid) : "-"), weight(1f));
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(12), 0, 0);
        row2.addView(metricCell("授权书", precheck == null ? "-" : precheck.permitText()), weight(1f));
        row2.addView(metricCell("剩余名额", precheck == null
                ? "-" : MigrantModels.fmtInt(precheck.remainingQuota)), weight(1f));
        row2.addView(new View(this), weight(1f));
        card.addView(row2);
        return card;
    }

    private View metricCell(String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.caption(this, label, UiStyle.INK3));
        TextView v = UiComponents.text(this, value, UiComponents.TEXT_BODY, UiStyle.INK, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, dp(2), dp(6), 0);
        col.addView(v);
        return col;
    }

    // ==================== 任务表单 ====================

    private View commandCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiComponents.title(this, "移民任务"));
        String cfg = overview == null || overview.activity.configVersion.isEmpty()
                ? "等待活动配置" : overview.activity.configVersion;
        copy.addView(UiComponents.caption(this, cfg, UiStyle.INK3));
        head.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        head.addView(UiComponents.compactSecondaryButton(this, "刷新移民状态",
                () -> refreshData(true)));
        card.addView(head);

        // ---------- 目标王国 ----------
        String targetText = targetKid > 0
                ? MigrantModels.kingdomLabel(overview, targetKid) : "选择同赛区王国";
        TextView dropdown = UiStyle.dropdownRow(this, "目标王国", targetText,
                () -> chooseKingdom(!online || refreshing));
        dropdown.setEnabled(online && !refreshing);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        card.addView(dropdown, dlp);

        MigrantModels.Kingdom k = overview == null ? null : overview.kingdom(targetKid);
        if (k != null) {
            LinearLayout facts = new LinearLayout(this);
            facts.setOrientation(LinearLayout.VERTICAL);
            facts.setPadding(0, dp(8), 0, 0);
            LinearLayout fr1 = new LinearLayout(this);
            fr1.setOrientation(LinearLayout.HORIZONTAL);
            fr1.addView(factCell("王国状态", k.strong() ? "强盛" : "普通"), weight(1f));
            fr1.addView(factCell("已用名额", MigrantModels.fmtInt(k.migrantNum)), weight(1f));
            facts.addView(fr1);
            LinearLayout fr2 = new LinearLayout(this);
            fr2.setOrientation(LinearLayout.HORIZONTAL);
            fr2.setPadding(0, dp(8), 0, 0);
            fr2.addView(factCell("普通移民", MigrantModels.fmtInt(k.normalMigrantNum)), weight(1f));
            fr2.addView(factCell("实力限制", k.powerLimitValue > 0
                    ? "≤" + MigrantModels.fmtInt(k.powerLimitValue)
                    : "未知（档位 " + (k.powerLimit > 0 ? String.valueOf(k.powerLimit) : "-") + "）"),
                    weight(1f));
            facts.addView(fr2);
            card.addView(facts);
        }

        // ---------- 4 个动作（2×2）----------
        final boolean ready = precheck != null && precheck.ready;
        final boolean activeExists = hasActiveJobForCurrent();
        final boolean canPrecheck = online && targetKid > 0 && !refreshing && !prechecking;

        LinearLayout actRow1 = new LinearLayout(this);
        actRow1.setOrientation(LinearLayout.HORIZONTAL);
        actRow1.setPadding(0, dp(12), 0, 0);

        TextView btnPrecheck = UiComponents.compactSecondaryButton(this,
                prechecking ? "预检中…" : "资格预检", this::doPrecheck);
        btnPrecheck.setEnabled(canPrecheck);
        actRow1.addView(btnPrecheck, weight(1f));

        TextView btnTest = UiComponents.compactSecondaryButton(this,
                testing ? "发送中…" : "提前测试", this::doTestEarly);
        btnTest.setEnabled(ready && !activeExists);
        btnTest.setTextColor(UiStyle.enabledTextColors(UiStyle.WARN_INK));
        LinearLayout.LayoutParams tlp = weight(1f);
        tlp.leftMargin = dp(8);
        actRow1.addView(btnTest, tlp);
        card.addView(actRow1);

        LinearLayout actRow2 = new LinearLayout(this);
        actRow2.setOrientation(LinearLayout.HORIZONTAL);
        actRow2.setPadding(0, dp(8), 0, 0);

        TextView btnCreate = UiComponents.compactPrimaryButton(this,
                creating ? "创建中…" : "创建边界任务", this::doCreate);
        btnCreate.setEnabled(ready && !activeExists);
        actRow2.addView(btnCreate, weight(1f));

        TextView btnBulk = UiComponents.compactSecondaryButton(this, "批量创建", this::openBulk);
        btnBulk.setEnabled(ready);
        LinearLayout.LayoutParams blp = weight(1f);
        blp.leftMargin = dp(8);
        actRow2.addView(btnBulk, blp);
        card.addView(actRow2);

        // ---------- 提前量 ----------
        LinearLayout advRow = new LinearLayout(this);
        advRow.setOrientation(LinearLayout.HORIZONTAL);
        advRow.setGravity(Gravity.CENTER_VERTICAL);
        advRow.setPadding(0, dp(14), 0, 0);
        advRow.addView(UiComponents.caption(this, "提前量", UiStyle.INK2));

        etAdvance = new EditText(this);
        etAdvance.setInputType(InputType.TYPE_CLASS_NUMBER);
        etAdvance.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        etAdvance.setTextSize(UiComponents.TEXT_LABEL);
        etAdvance.setTextColor(UiStyle.INK);
        etAdvance.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 10,
                UiStyle.LINE_STRONG));
        etAdvance.setPadding(dp(8), dp(6), dp(8), dp(6));
        etAdvance.setMinWidth(dp(64));
        etAdvance.setText(String.valueOf(advanceMillis));
        etAdvance.setGravity(Gravity.CENTER);
        etAdvance.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitAdvance();
        });
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.leftMargin = dp(8);
        advRow.addView(etAdvance, etLp);

        TextView advHint = UiComponents.caption(this,
                "毫秒，默认 0；填 20~30 可补偿网络单程时延", UiStyle.INK3);
        advHint.setPadding(dp(8), 0, 0, 0);
        advHint.setSingleLine(false);
        advRow.addView(advHint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(advRow);

        // ---------- 4 个复选框 ----------
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(0, dp(8), 0, 0);

        opts.addView(checkRow("开放前自动退出联盟", chkExitAlliance, (v, checked) -> {
            chkExitAlliance = checked;
        }, null));

        opts.addView(checkRow("开放前自动收回外出部队", chkRecall, (v, checked) -> {
            chkRecall = checked;
            applyRecallCascade();
        }, null));

        cbSpeedup = checkRow("回城赶不上时用行军加速器（二阶 → 一阶）", chkSpeedup,
                (v, checked) -> chkSpeedup = checked, null);
        opts.addView(cbSpeedup);

        cbDiamond = checkRow("背包不足时允许钻石购买（召回道具 800 钻／加速器 400 钻，每任务上限 10 个）",
                chkDiamond, (v, checked) -> chkDiamond = checked, null);
        opts.addView(cbDiamond);

        TextView note = UiComponents.caption(this,
                "任务在开放前 15 分钟上号准备：退出联盟、召回部队并把回城时间压到开放前 2 分钟以内，全程保持账号在线",
                UiStyle.INK3);
        note.setPadding(dp(2), dp(8), 0, 0);
        opts.addView(note);
        card.addView(opts);

        applyRecallCascade();
        return card;
    }

    private View factCell(String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.caption(this, label, UiStyle.INK3));
        TextView v = UiComponents.text(this, value, UiComponents.TEXT_LABEL, UiStyle.INK, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(v);
        return col;
    }

    private interface CheckListener {
        void onChecked(View v, boolean checked);
    }

    private CheckBox checkRow(String text, boolean checked, final CheckListener listener,
                              final Runnable extra) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(UiComponents.TEXT_LABEL);
        cb.setTextColor(UiStyle.INK2);
        cb.setChecked(checked);
        cb.setPadding(0, dp(6), 0, dp(6));
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) listener.onChecked(buttonView, isChecked);
            if (extra != null) extra.run();
        });
        return cb;
    }

    /** 复选框 3/4 依赖「开放前自动收回外出部队」：关闭时禁用并降低层级（网页版 {@code disabled: !A}）。 */
    private void applyRecallCascade() {
        if (cbSpeedup != null) {
            cbSpeedup.setEnabled(chkRecall);
            cbSpeedup.setAlpha(chkRecall ? 1f : 0.45f);
        }
        if (cbDiamond != null) {
            cbDiamond.setEnabled(chkRecall);
            cbDiamond.setAlpha(chkRecall ? 1f : 0.45f);
        }
    }

    private void commitAdvance() {
        if (etAdvance == null) return;
        advanceMillis = MigrantModels.clampAdvance(parseInt(etAdvance.getText().toString()));
        String txt = String.valueOf(advanceMillis);
        if (!txt.contentEquals(etAdvance.getText())) etAdvance.setText(txt);
    }

    /** 目标王国选择（同赛区王国列表，取自 overview.kingdoms）。 */
    private void chooseKingdom(boolean disabled) {
        if (disabled) {
            UiStyle.toast(this, online ? "正在刷新，请稍候" : "账号未上线，无法选择目标王国");
            return;
        }
        if (overview == null || overview.kingdoms.isEmpty()) {
            UiStyle.toast(this, "暂无可选王国（等待活动配置同步）");
            return;
        }
        final List<MigrantModels.Kingdom> list = overview.kingdoms;
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = MigrantModels.kingdomOptionLabel(list.get(i));
        }
        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择同赛区王国")
                .setItems(items, (d, which) -> {
                    targetKid = list.get(which).kid;
                    precheck = null;   // 网页版 wl()：换目标即清空预检
                    rerender();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    // ==================== 资格结果 ====================

    private View precheckCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiComponents.text(this, "资格结果", UiComponents.TEXT_BODY,
                UiStyle.INK, true), weight(1f));
        if (precheck != null) {
            head.addView(UiStyle.badge(this, precheck.ready ? "ok" : "err",
                    precheck.ready ? "可以创建" : "存在阻断项"));
        }
        card.addView(head);

        if (precheck == null) {
            card.addView(UiStyle.emptyState(this, "☑", "尚未执行资格预检",
                    "选择王国后执行资格预检", null, null));
            return card;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, 0);
        for (MigrantModels.Check c : precheck.checks) {
            list.addView(checkLine(c));
        }
        for (String w : precheck.warnings) {
            list.addView(warningLine(w));
        }
        card.addView(list);
        return card;
    }

    private View checkLine(MigrantModels.Check c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView mark = UiComponents.text(this, c.passed ? "✓" : "✕",
                UiComponents.TEXT_BODY, c.passed ? UiStyle.OK_INK : UiStyle.ERR_INK, true);
        mark.setWidth(dp(22));
        row.addView(mark);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiComponents.text(this, c.label, UiComponents.TEXT_LABEL,
                c.passed ? UiStyle.INK : UiStyle.ERR_INK, true));
        if (c.detail != null && !c.detail.isEmpty()) {
            TextView d = UiComponents.caption(this, c.detail, UiStyle.INK2);
            d.setPadding(0, dp(1), 0, 0);
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View warningLine(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView mark = UiComponents.text(this, "!", UiComponents.TEXT_BODY,
                UiStyle.WARN_INK, true);
        mark.setWidth(dp(22));
        row.addView(mark);
        TextView t = UiComponents.caption(this, text, UiStyle.WARN_INK);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ==================== 任务大盘 ====================

    private View jobsCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        // ---- 标题 ----
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout icon = UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 36, 18, 10);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.addView(UiComponents.text(this, "任务大盘", UiComponents.TEXT_BODY,
                UiStyle.INK, true));
        int activeCount = jobs.active == null ? 0 : jobs.active.size();
        if (activeCount > 0) {
            TextView badge = UiStyle.badge(this, "run", activeCount + " 运行中");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(6);
            titleLine.addView(badge, blp);
        }
        titles.addView(titleLine);
        titles.addView(UiComponents.caption(this,
                "多角色自由移民自动抢位与前置状态监控", UiStyle.INK3));
        titleRow.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(titleRow);

        // ---- 范围切换：全部账号 / 当前角色 ----
        List<MigrantModels.Job> currentOnly = currentRoleJobs();
        final String[] scopeOptions = {
                "全部账号 (" + activeCount + ")",
                "当前角色 (" + currentOnly.size() + ")"};
        LinearLayout scopeSeg = UiStyle.segmented(this, scopeOptions,
                SCOPE_CURRENT.equals(scope) ? 1 : 0, idx -> {
                    scope = idx == 1 ? SCOPE_CURRENT : SCOPE_ALL;
                    rerender();
                });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        card.addView(scopeSeg, slp);

        // ---- 筛选：全部 / 待排查异常 + 就绪 chip ----
        List<MigrantModels.Job> scoped = scopedJobs();
        int issueCount = 0;
        for (MigrantModels.Job j : scoped) {
            if (MigrantModels.hasIssue(j, serverNow())) issueCount++;
        }
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        final String[] filterOptions = {
                "全部 (" + scoped.size() + ")",
                "待排查异常" + (issueCount > 0 ? " (" + issueCount + ")" : " (0)")};
        LinearLayout filterSeg = UiStyle.segmented(this, filterOptions,
                FILTER_ISSUE.equals(filter) ? 1 : 0, idx -> {
                    filter = idx == 1 ? FILTER_ISSUE : FILTER_ALL;
                    rerender();
                });
        filterRow.addView(filterSeg, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = UiStyle.chip(this, MigrantModels.readinessText(scoped, serverNow()),
                false);
        String tone = MigrantModels.readinessTone(scoped, serverNow());
        if ("warn".equals(tone)) {
            chip.setTextColor(UiStyle.WARN_INK);
            chip.setBackground(UiStyle.roundRectDp(this, UiStyle.WARN_BG, 999));
        } else if ("ok".equals(tone)) {
            chip.setTextColor(UiStyle.OK_INK);
            chip.setBackground(UiStyle.roundRectDp(this, UiStyle.OK_BG, 999));
        } else {
            chip.setTextColor(UiStyle.INK2);
            chip.setBackground(UiStyle.roundRectDp(this, UiStyle.IDLE_BG, 999));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        filterRow.addView(chip, clp);
        card.addView(filterRow, flp);

        // ---- 批量发车（预检通过时）----
        if (precheck != null && precheck.ready) {
            TextView bulk = UiComponents.compactSecondaryButton(this, "批量发车", this::openBulk);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(8);
            card.addView(bulk, blp);
        }

        // ---- 列表 / 空态 ----
        List<MigrantModels.Job> visible = visibleJobs();
        if (visible.isEmpty()) {
            card.addView(jobsEmptyState(scoped));
        } else {
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, dp(6), 0, 0);
            for (MigrantModels.Job j : visible) {
                list.addView(jobRow(j));
            }
            card.addView(list);
        }
        return card;
    }

    private View jobsEmptyState(List<MigrantModels.Job> scoped) {
        boolean issueMode = FILTER_ISSUE.equals(filter);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(26), dp(8), dp(12));

        TextView title = UiComponents.title(this, issueMode
                ? "所有任务状态正常" : "当前暂无运行中的移民任务");
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView desc = UiComponents.caption(this, issueMode
                ? "当前筛选范围内的进行中任务均已完成时钟对齐、退盟与部队状态检查，无待排查异常。"
                : "配置好目标王国并预检通过后，可创建单角色发车任务，或使用上方“批量创建”下发多账号发车计划。",
                UiStyle.INK2);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(6), 0, 0);
        box.addView(desc);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(14), 0, 0);
        if (issueMode) {
            TextView back = UiComponents.compactSecondaryButton(this,
                    "查看全部任务 (" + scoped.size() + ")", () -> {
                        filter = FILTER_ALL;
                        rerender();
                    });
            actions.addView(back);
        } else {
            final boolean ready = precheck != null && precheck.ready;
            if (ready) {
                TextView bulk = UiComponents.compactSecondaryButton(this,
                        "批量创建移民任务", this::openBulk);
                actions.addView(bulk);
            }
            if (ready && !hasActiveJobForCurrent()) {
                TextView create = UiComponents.compactSecondaryButton(this,
                        "为当前角色创建任务", this::doCreate);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(8);
                actions.addView(create, lp);
            }
        }
        box.addView(actions);
        return box;
    }

    /**
     * 单个任务行：逐区块落网页版 7 列语义
     * （状态 / 角色与目标 / 提交进度 / 时钟·行军 / 就绪态与加速 / 抢位倒计时 / 操作）。
     * 窄屏改为纵向堆叠，但每个区块保留自己的微标签，信息不丢。
     */
    private View jobRow(final MigrantModels.Job j) {
        long now = serverNow();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(UiStyle.roundRectStroke(this, UiStyle.CARD, 12,
                MigrantModels.hasIssue(j, now) ? UiStyle.ERR_BORDER : UiStyle.LINE));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        // 行 1：状态 + 角色与目标
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiStyle.badge(this, MigrantModels.statusTone(j),
                j.statusLabel == null || j.statusLabel.isEmpty() ? "-" : j.statusLabel));

        LinearLayout roleCol = new LinearLayout(this);
        roleCol.setOrientation(LinearLayout.VERTICAL);
        roleCol.setPadding(dp(8), 0, 0, 0);
        TextView name = UiComponents.text(this, j.displayName(), UiComponents.TEXT_LABEL,
                UiStyle.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        roleCol.addView(name);
        TextView meta = UiComponents.caption(this,
                MigrantModels.jobRoute(j) + " · " + MigrantModels.fmtTime(j.freedomStartMillis),
                UiStyle.INK3);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        roleCol.addView(meta);
        head.addView(roleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(head);

        // 行 2：提交进度 + 请求详情 | 时钟 + 行军 + 撤退详情
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setPadding(0, dp(8), 0, 0);

        LinearLayout attemptsCol = new LinearLayout(this);
        attemptsCol.setOrientation(LinearLayout.VERTICAL);
        attemptsCol.addView(UiComponents.caption(this, "提交进度", UiStyle.INK3));
        LinearLayout attLine = new LinearLayout(this);
        attLine.setOrientation(LinearLayout.HORIZONTAL);
        attLine.setGravity(Gravity.CENTER_VERTICAL);
        attLine.addView(UiComponents.numeric(this, String.valueOf(j.attemptCount),
                UiComponents.TEXT_BODY, UiStyle.INK, true));
        attLine.addView(UiComponents.caption(this, " / " + j.maxAttempts, UiStyle.INK3));
        attemptsCol.addView(attLine);
        TextView reqDetail = UiComponents.compactSecondaryButton(this, "请求详情",
                () -> openAttempts(j));
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        rlp2.topMargin = dp(4);
        attemptsCol.addView(reqDetail, rlp2);
        mid.addView(attemptsCol, weight(1f));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setPadding(dp(6), 0, 0, 0);
        clockCol.addView(UiComponents.caption(this, "时钟 / 行军", UiStyle.INK3));
        TextView clockText = UiComponents.text(this, MigrantModels.clockText(j),
                UiComponents.TEXT_LABEL, MigrantModels.isRoleClock(j) ? UiStyle.INK : UiStyle.WARN_INK,
                true);
        clockText.setSingleLine(true);
        clockCol.addView(clockText);
        TextView marchText = UiComponents.caption(this,
                MigrantModels.marchText(j, now), UiStyle.INK2);
        marchText.setSingleLine(false);
        clockCol.addView(marchText);
        if (MigrantModels.hasMarches(j) || j.recallBeforeOpen) {
            TextView marchDetail = UiComponents.compactSecondaryButton(this, "撤退详情",
                    () -> openMarches(j));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            mlp.topMargin = dp(4);
            clockCol.addView(marchDetail, mlp);
        }
        mid.addView(clockCol, weight(1f));
        row.addView(mid);

        // 行 3：就绪态与加速（三重编码：色 + 文案）
        List<View> healthTags = new ArrayList<>();
        if (MigrantModels.hasMarches(j)) {
            healthTags.add(UiStyle.badge(this, MigrantModels.recallTagTone(j, now),
                    MigrantModels.recallTagText(j, now)));
        }
        int speedups = j.speedupTier2Used + j.speedupTier1Used + j.speedupDiamondUsed;
        if (speedups > 0) {
            healthTags.add(UiStyle.badge(this, "idle",
                    "加速器 " + j.speedupTier2Used + " 二阶 / " + j.speedupTier1Used
                            + " 一阶 / " + j.speedupDiamondUsed + " 钻石"));
        }
        MigrantModels.ExitBadge exit = MigrantModels.exitAllianceBadge(j);
        if (exit.show) healthTags.add(UiStyle.badge(this, exit.tone, exit.text));
        if (MigrantModels.allReady(j, now)) {
            healthTags.add(UiStyle.badge(this, "ok", "全部就绪"));
        }
        if (!healthTags.isEmpty()) {
            LinearLayout health = new LinearLayout(this);
            health.setOrientation(LinearLayout.HORIZONTAL);
            health.setGravity(Gravity.CENTER_VERTICAL);
            health.setPadding(0, dp(8), 0, 0);
            health.addView(UiComponents.text(this, "就绪态与加速", UiComponents.TEXT_MICRO,
                    UiStyle.INK3, true));
            for (int i = 0; i < healthTags.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(6);
                health.addView(healthTags.get(i), lp);
            }
            row.addView(health);
        }

        // 行 4：抢位倒计时 + 操作
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(8), 0, 0);
        LinearLayout countdownCol = new LinearLayout(this);
        countdownCol.setOrientation(LinearLayout.VERTICAL);
        countdownCol.addView(UiComponents.text(this, "抢位倒计时", UiComponents.TEXT_MICRO,
                UiStyle.INK3, true));
        TextView countdown = UiComponents.numeric(this,
                MigrantModels.jobCountdown(j, now), UiComponents.TEXT_LABEL, UiStyle.INK2, false);
        countdownCol.addView(countdown);
        foot.addView(countdownCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (MigrantModels.canRecheck(j)) {
            TextView recheck = UiComponents.compactSecondaryButton(this, "重新核验",
                    () -> doRecheck(j));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.leftMargin = dp(6);
            foot.addView(recheck, lp);
        }
        if (j.cancelable) {
            TextView cancel = UiComponents.compactSecondaryButton(this, "取消",
                    () -> doCancel(j));
            cancel.setTextColor(UiStyle.enabledTextColors(UiStyle.ERR_INK));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.leftMargin = dp(6);
            foot.addView(cancel, lp);
        }
        row.addView(foot);

        if (j.jobId != null && !j.jobId.isEmpty()) {
            liveJobViews.put(j.jobId, new TextView[]{countdown, marchText});
        }
        return row;
    }

    // ==================== 最近历史 ====================

    private View historyCard() {
        List<MigrantModels.Job> history = historyJobs();
        if (history.isEmpty()) return null;
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiComponents.text(this, "最近历史结果 (" + history.size() + ")",
                UiComponents.TEXT_BODY, UiStyle.INK, true), weight(1f));
        head.addView(UiComponents.caption(this, "展示最近完成或取消的移民任务", UiStyle.INK3));
        card.addView(head);

        for (MigrantModels.Job j : history) {
            card.addView(historyRow(j));
        }
        return card;
    }

    private View historyRow(final MigrantModels.Job j) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setBackground(null);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiStyle.badge(this, MigrantModels.statusTone(j),
                j.statusLabel == null || j.statusLabel.isEmpty() ? "-" : j.statusLabel));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, 0, 0);
        TextView name = UiComponents.text(this,
                j.displayName() + " · " + MigrantModels.jobRoute(j),
                UiComponents.TEXT_LABEL, UiStyle.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        col.addView(UiComponents.caption(this, MigrantModels.fmtTime(j.updatedAtMillis),
                UiStyle.INK3));
        head.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout stat = new LinearLayout(this);
        stat.setOrientation(LinearLayout.VERTICAL);
        stat.setGravity(Gravity.END);
        stat.addView(UiComponents.numeric(this, j.attemptCount + " 次",
                UiComponents.TEXT_LABEL, UiStyle.INK, true));
        stat.addView(UiComponents.caption(this, "最终提交", UiStyle.INK3));
        head.addView(stat);
        row.addView(head);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(6), 0, 0);
        String result = j.error != null && !j.error.isEmpty()
                ? j.error : MigrantModels.finalResultText(j);
        TextView res = UiComponents.caption(this, result, UiStyle.INK2);
        res.setSingleLine(false);
        foot.addView(res, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView detail = UiComponents.compactSecondaryButton(this, "请求详情",
                () -> openAttempts(j));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        dlp.leftMargin = dp(6);
        foot.addView(detail, dlp);
        row.addView(foot);
        return row;
    }

    // ==================== 列表计算 ====================

    private List<MigrantModels.Job> currentRoleJobs() {
        List<MigrantModels.Job> out = new ArrayList<>();
        if (jobs == null || jobs.active == null) return out;
        for (MigrantModels.Job j : jobs.active) {
            if (j.accountRoleId == uid) out.add(j);
        }
        return out;
    }

    private List<MigrantModels.Job> scopedJobs() {
        return SCOPE_CURRENT.equals(scope) ? currentRoleJobs()
                : (jobs == null || jobs.active == null
                ? new ArrayList<MigrantModels.Job>() : jobs.active);
    }

    private List<MigrantModels.Job> visibleJobs() {
        List<MigrantModels.Job> scoped = scopedJobs();
        if (!FILTER_ISSUE.equals(filter)) return scoped;
        List<MigrantModels.Job> out = new ArrayList<>();
        long now = serverNow();
        for (MigrantModels.Job j : scoped) {
            if (MigrantModels.hasIssue(j, now)) out.add(j);
        }
        return out;
    }

    private List<MigrantModels.Job> historyJobs() {
        if (jobs == null || jobs.finished == null) return new ArrayList<>();
        List<MigrantModels.Job> out = new ArrayList<>();
        if (SCOPE_ALL.equals(scope)) {
            for (MigrantModels.Job j : jobs.finished) {
                out.add(j);
                if (out.size() >= HISTORY_ALL) break;
            }
        } else {
            for (MigrantModels.Job j : jobs.finished) {
                if (j.accountRoleId != uid) continue;
                out.add(j);
                if (out.size() >= HISTORY_CURRENT) break;
            }
        }
        return out;
    }

    private boolean hasActiveJobForCurrent() {
        return !currentRoleJobs().isEmpty();
    }

    // ==================== 时间 ====================

    private MigrantModels.Activity activityOf() {
        return overview == null ? null : overview.activity;
    }

    /** 服务器时间外推：{@code base + (本机 now - 锚点)}（网页版 {@code i}）。 */
    private long serverNow() {
        long local = System.currentTimeMillis();
        if (serverNowBase <= 0) return 0L;
        return serverNowBase + Math.max(0L, local - serverNowAnchorLocal);
    }

    private String stage() {
        return MigrantModels.stageOf(activityOf(), serverNow());
    }

    private String fmtServerClock() {
        long now = serverNow();
        if (now <= 0) return "--:--:--";
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss",
                    Locale.CHINA);
            f.setTimeZone(MigrantModels.DISPLAY_TZ);
            return f.format(new java.util.Date(now));
        } catch (Throwable t) {
            return "--:--:--";
        }
    }

    private String serverHintText() {
        return MigrantModels.serverClockHint(activityOf(), serverNow());
    }

    // ==================== 轮询 ====================

    private void onTick() {
        updateLiveViews();
        long interval = consecutiveFailures >= FALLBACK_AFTER_FAILURES
                ? FALLBACK_MS : MIN_INTERVAL_MS;
        long since = System.currentTimeMillis() - lastRefreshAt;
        if (!refreshing && since >= interval) refreshData(false);
    }

    /** 只更新随时间推进的文本（不重建视图）。 */
    private void updateLiveViews() {
        long now = serverNow();
        if (tvServerClock != null) tvServerClock.setText(fmtServerClock());
        if (tvServerHint != null) tvServerHint.setText(serverHintText());
        if (tvStageBadge != null) {
            String stage = stage();
            tvStageBadge.setText(MigrantModels.stageLabel(stage));
            tvStageBadge.setBackground(UiStyle.roundRectDp(this,
                    toneBackground(MigrantModels.stageTone(stage)), 999));
            tvStageBadge.setTextColor(toneInk(MigrantModels.stageTone(stage)));
        }
        if (stageTimeline != null) {
            stageTimeline.setPercent(MigrantModels.timelinePercent(activityOf(), now));
        }
        if (jobs == null) return;
        for (MigrantModels.Job j : jobs.active) {
            TextView[] refs = liveJobViews.get(j.jobId);
            if (refs == null) continue;
            if (refs[0] != null) refs[0].setText(MigrantModels.jobCountdown(j, now));
            if (refs[1] != null) refs[1].setText(MigrantModels.marchText(j, now));
        }
    }

    // ==================== 网络动作 ====================

    /** 刷新 overview + jobs（manual=true 时给出成功/失败提示）。 */
    private void refreshData(final boolean manual) {
        if (who == null || !who.valid() || refreshing) return;
        refreshing = true;
        lastRefreshAt = System.currentTimeMillis();
        MigrantApi.refresh(this, who, (code, message, data) -> runOnUiThread(() -> {
            if (uiGone()) return;
            refreshing = false;
            if (code != 0) {
                consecutiveFailures++;
                if (manual) {
                    UiStyle.toast(MigrantWorkspaceActivity.this,
                            "刷新失败：" + (message == null || message.isEmpty()
                                    ? "未知错误" : message));
                }
                updateLiveViews();
                return;
            }
            consecutiveFailures = 0;
            JSONObject ovj = data == null ? null : data.optJSONObject("overview");
            JSONObject jj = data == null ? null : data.optJSONObject("jobs");
            overview = MigrantModels.overviewOf(ovj);
            serverNowBase = overview == null ? 0L : overview.serverNowMillis;
            serverNowAnchorLocal = System.currentTimeMillis();
            jobs = MigrantModels.jobsOf(jj);
            online = overview != null;
            if (overview != null && targetKid > 0 && overview.kingdom(targetKid) == null) {
                targetKid = 0L;
                precheck = null;
            }
            // 服务器时间每秒漂移，不构成「内容变化」：从签名里剔除后再比较，
            // 避免 2s 轮询每次都整页重建（丢滚动位置、列表闪烁）。
            String sig = signatureOf(ovj, jj);
            if (!sig.equals(lastSignature)) {
                lastSignature = sig;
                rerender();
            } else {
                updateLiveViews();
            }
            if (manual) UiStyle.toast(MigrantWorkspaceActivity.this, "移民状态已刷新");
        }));
    }

    /** 数据签名：剔除每分钟都在变的 {@code server_now_millis}，其余字段原样参与比较。 */
    private static String signatureOf(JSONObject ovj, JSONObject jj) {
        String ov = ovj == null ? "" : ovj.toString();
        // 服务端每次响应都会带新的 server_now_millis；同值替换后比较才能识别「无变化」
        ov = ov.replaceAll("\"server_now_millis\":\\d+", "\"server_now_millis\":0");
        return ov + "||" + (jj == null ? "" : jj.toString());
    }

    /** 账号开关：离线 → 上线（login action）；在线 → 下线（logout action）。 */
    private void doToggleAccount(UiStyle.Switch sw) {
        if (online) {
            // 在线 → 下线：与网页版 WorkspaceAccountToggle 一致（只切会话，不改任务定义）
            UiStyle.toast(this, "正在下线角色…");
            MigrantApi.logout(this, who, (code, message, data) -> runOnUiThread(() -> {
                if (uiGone()) return;
                if (code != 0) {
                    sw.setOn(true);
                    UiStyle.toast(MigrantWorkspaceActivity.this,
                            "下线失败：" + (message == null || message.isEmpty()
                                    ? "未知错误" : message));
                    return;
                }
                UiStyle.toast(MigrantWorkspaceActivity.this, "角色已下线");
                refreshData(false);
            }));
            return;
        }
        sw.setOn(true);
        UiStyle.toast(this, "正在上线角色…");
        MigrantApi.login(this, who, (code, message, data) -> runOnUiThread(() -> {
            if (uiGone()) return;
            if (code != 0) {
                sw.setOn(false);
                UiStyle.toast(MigrantWorkspaceActivity.this,
                        "上线失败：" + (message == null || message.isEmpty()
                                ? "未知错误" : message));
                return;
            }
            UiStyle.toast(MigrantWorkspaceActivity.this, "角色已上线，正在同步移民状态");
            refreshData(false);
        }));
    }

    /** 资格预检。 */
    private void doPrecheck() {
        commitAdvance();
        if (targetKid <= 0 || prechecking) return;
        prechecking = true;
        MigrantApi.precheck(this, who, targetKid, chkExitAlliance, chkRecall,
                chkSpeedup, chkDiamond, advanceMillis, (code, message, data) ->
                        runOnUiThread(() -> {
                            if (uiGone()) return;
                            prechecking = false;
                            if (code != 0) {
                                precheck = null;
                                UiStyle.toast(MigrantWorkspaceActivity.this,
                                        "资格预检失败：" + (message == null || message.isEmpty()
                                                ? "未知错误" : message));
                                rerender();
                                return;
                            }
                            precheck = MigrantModels.precheckOf(data);
                            if (precheck == null) precheck = new MigrantModels.Precheck();
                            UiStyle.toast(MigrantWorkspaceActivity.this,
                                    precheck.ready ? "资格预检通过" : "资格预检存在阻断项");
                            rerender();
                        }));
    }

    /** 提前测试（真实发送一次迁入请求，必须二次确认）。 */
    private void doTestEarly() {
        if (targetKid <= 0 || testing) return;
        UiStyle.showConfirmDialog(this, "确认提前测试",
                "将向 K" + targetKid + " 发送一次真实移民请求；若服务端提前接受，角色会立即迁入。",
                "发送真实请求", UiStyle.ERR_INK, () -> {
                    testing = true;
                    rerender();
                    MigrantApi.testEarly(this, who, targetKid, (code, message, data) ->
                            runOnUiThread(() -> {
                                if (uiGone()) return;
                                testing = false;
                                if (code != 0) {
                                    UiStyle.toast(MigrantWorkspaceActivity.this,
                                            "提前测试失败：" + (message == null || message.isEmpty()
                                                    ? "未知错误" : message));
                                    rerender();
                                    return;
                                }
                                boolean accepted = data != null && data.optBoolean("accepted", false);
                                String msg = data == null ? "" : data.optString("message", "");
                                UiStyle.toast(MigrantWorkspaceActivity.this,
                                        msg.isEmpty() ? (accepted ? "服务端已接受" : "服务端未接受")
                                                : msg);
                                refreshData(false);
                            }));
                });
    }

    /** 创建边界任务（含准备摘要二次确认）。 */
    private void doCreate() {
        commitAdvance();
        if (targetKid <= 0 || creating) return;
        final String message = "确认为" + accountLabel + "创建前往 "
                + MigrantModels.kingdomLabel(overview, targetKid)
                + " 的移民边界任务？到点后会抢号登录该角色执行。"
                + MigrantModels.prepareSummary(chkExitAlliance, chkRecall, chkSpeedup, chkDiamond);
        UiStyle.showConfirmDialog(this, "确认移民定时任务", message, "确认创建",
                UiStyle.BRAND_TEXT, () -> {
                    creating = true;
                    rerender();
                    MigrantApi.create(this, who, targetKid, advanceMillis, chkExitAlliance,
                            chkRecall, chkSpeedup, chkDiamond, (code, msg, data) ->
                                    runOnUiThread(() -> {
                                        if (uiGone()) return;
                                        creating = false;
                                        if (code != 0) {
                                            UiStyle.toast(MigrantWorkspaceActivity.this,
                                                    "创建失败：" + (msg == null || msg.isEmpty()
                                                            ? "未知错误" : msg));
                                            rerender();
                                            return;
                                        }
                                        UiStyle.toast(MigrantWorkspaceActivity.this,
                                                "移民边界任务已创建");
                                        refreshData(false);
                                    }));
                });
    }

    /** 取消任务（危险操作，二次确认）。 */
    private void doCancel(final MigrantModels.Job j) {
        UiStyle.showConfirmDialog(this, "取消任务",
                "取消前往 K" + j.targetKid + " 的移民任务？", "取消任务", UiStyle.ERR_INK, () -> {
                    MigrantApi.cancel(this, who, j.jobId, (code, message, data) ->
                            runOnUiThread(() -> {
                                if (uiGone()) return;
                                if (code != 0) {
                                    UiStyle.toast(MigrantWorkspaceActivity.this,
                                            "取消失败：" + (message == null || message.isEmpty()
                                                    ? "未知错误" : message));
                                    return;
                                }
                                UiStyle.toast(MigrantWorkspaceActivity.this, "移民任务已取消");
                                refreshData(false);
                            }));
                });
    }

    /** 重新核验（重登读当前王国对账）。 */
    private void doRecheck(final MigrantModels.Job j) {
        MigrantApi.recheck(this, who, j.jobId, (code, message, data) ->
                runOnUiThread(() -> {
                    if (uiGone()) return;
                    if (code != 0) {
                        UiStyle.toast(MigrantWorkspaceActivity.this,
                                "核验失败：" + (message == null || message.isEmpty()
                                        ? "未知错误" : message));
                        return;
                    }
                    UiStyle.toast(MigrantWorkspaceActivity.this, "已重新核验任务状态");
                    refreshData(false);
                }));
    }

    /** 撤退详情弹窗。 */
    private void openMarches(MigrantModels.Job j) {
        MigrantRecallDialog.show(this, who, j, () -> refreshData(false));
    }

    /** 请求审计弹窗。 */
    private void openAttempts(MigrantModels.Job j) {
        MigrantAttemptsDialog.show(this, who, j);
    }

    /** 批量创建弹窗。 */
    private void openBulk() {
        MigrantBulkDialog.show(this, who, accountLabel, targetKid, overview,
                advanceMillis, () -> refreshData(false));
    }

    // ==================== 工具 ====================

    private int toneBackground(String tone) {
        if ("ok".equals(tone)) return UiStyle.OK_BG;
        if ("err".equals(tone)) return UiStyle.ERR_BG;
        if ("warn".equals(tone)) return UiStyle.WARN_BG;
        if ("wait".equals(tone)) return UiStyle.WAIT_BG;
        if ("run".equals(tone)) return UiStyle.RUN_BG;
        return UiStyle.IDLE_BG;
    }

    private int toneInk(String tone) {
        if ("ok".equals(tone)) return UiStyle.OK_INK;
        if ("err".equals(tone)) return UiStyle.ERR_INK;
        if ("warn".equals(tone)) return UiStyle.WARN_INK;
        if ("wait".equals(tone)) return UiStyle.WAIT_INK;
        if ("run".equals(tone)) return UiStyle.RUN_INK;
        return UiStyle.IDLE_INK;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Activity 已不可用（正在结束/已销毁）：网络回调可能晚于 onDestroy，
     * 此时任何 toast/rerender 都可能抛 WindowManager$BadTokenException 或泄漏。
     * 所有异步回调进入 UI 线程后先过这一关。
     */
    private boolean uiGone() {
        if (destroyed || isFinishing()) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed();
    }

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }
}
