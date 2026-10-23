package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.license.LicenseManager;
import com.carlos.vault.license.LicenseState;
import com.carlos.vault.license.LicenseUi;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 批次7（task-25）：新用户初始化四步引导（首次使用引导页）。
 *
 * <p>把现有分散入口串成一条可完成、可跳过、可再次进入的流程（对齐
 * {@code design/全流程行为与分级门控-20261023.md} §5.2），不重写底层能力：
 * <ol>
 *   <li><b>① 绑定兑换码</b>：复用 {@link LicenseUi#showActivateDialog} + {@link LicenseManager#activateAsync}
 *       现有激活能力（授权协议零改动）；已激活自动打勾并显示剩余天数。</li>
 *   <li><b>② 克隆/初始化</b>：复用现有初始化向导（克隆→首启→重置→存基线，AccountVaultActivity#showWizard）；
 *       用 {@link VaultEngine#isGameInstalled()} + {@link VaultEngine#hasBaseline()} 自动检测完成态。</li>
 *   <li><b>③ 添加分身账户</b>：引导到主页「账号」页（+ 导入/克隆）；用 {@link AccountStore#all()} 自动检测；
 *       可跳过（稍后再加）。</li>
 *   <li><b>④ 打开游戏分身</b>：复用 {@link EntryDetailActivity} 的「启动分身」链路
 *       （其内部含 IME 预热/前置检查/失败诊断）；用 {@link VaultEngine#isContainerGameAlive()} 自动检测。</li>
 *   <li><b>⑤ 打开自动化（T2+）</b>：引导到主页「日常」页配置任务；按授权等级显示/提示 T2 会员权限
 *       （T1 用户引导止于第④步，第⑤步只显示升级说明、可跳过、不阻塞完成）。</li>
 * </ol>
 *
 * <p>状态持久化见 {@link OnboardingState}（SharedPreferences {@code onboarding_done} + 每步完成/跳过标记）。
 * 首次启动由 {@link AccountVaultActivity}（HomeActivity 走 super.onCreate）在授权门禁之后拉起本页；
 * 完成/跳过后不再打扰；再次进入入口在 ☰ 功能菜单「新手引导」。
 *
 * <p>本页**不新增任何游戏协议/网络请求**，不改变既有授权与克隆行为；UI 全部代码构建（复用 UiStyle）。
 */
public final class OnboardingActivity extends Activity {

    /** 是否「再次进入」（从功能菜单）：再次进入时不自动结束引导、不标记整页完成（除非用户点完成）。 */
    public static final String EXTRA_REVISIT = "onboarding_revisit";

    private static final int COLOR_DONE_BG = UiStyle.OK_BG;
    private static final int COLOR_DONE = UiStyle.OK_SOLID;
    private static final int COLOR_TODO_BG = UiStyle.BRAND_50;
    private static final int COLOR_TODO = UiStyle.BRAND;

    private OnboardingState state;
    private LicenseManager licenseManager;
    private VaultEngine engine;
    private AccountStore store;
    private boolean revisit;
    /** 容器引擎是否已完成就绪探测（冷启动 isGameInstalled 可能瞬时为空）。 */
    private boolean engineReady;
    /** 防重复 finish/自动完成。 */
    private boolean finishing;
    private boolean autoFinishScheduled;

    private LinearLayout stepsBox;
    private TextView progressText;
    private LinearLayout progressSegs;
    private TextView subtitleText;
    private TextView primaryBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        revisit = getIntent() != null && getIntent().getBooleanExtra(EXTRA_REVISIT, false);
        state = new OnboardingState(this);
        licenseManager = LicenseManager.get(this);
        try {
            licenseManager.ensureLoaded();
        } catch (Throwable ignored) {
        }
        engine = new VaultEngine(this);
        store = new AccountStore(this);
        setContentView(buildRoot());
        refresh();
        // 引擎启动是异步的：冷启动时立刻判定"已克隆"会误判为未克隆（与 AccountVaultActivity 同一经验）。
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    engine.ensureEngineReady();
                } catch (Throwable ignored) {
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                engineReady = true;
                refresh();
            }
        }.execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从各步骤返回（账号页/游戏/日常页）后重新检测：已完成的步骤自动打勾。
        if (stepsBox != null) refresh();
    }

    @Override
    protected void onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ==================================================================================
    // 一、UI 骨架（代码构建，复用 UiStyle）
    // ==================================================================================

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        // 顶部栏：返回 + 标题 + 说明
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(14), dp(16), dp(4));

        FrameLayout back = new FrameLayout(this);
        back.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.INK, 22),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(back);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(4), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("新手引导");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        titles.addView(title);
        subtitleText = new TextView(this);
        subtitleText.setTextSize(11.5f);
        subtitleText.setTextColor(UiStyle.INK2);
        subtitleText.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitleText);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        // 进度卡
        LinearLayout progressCard = UiStyle.card(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressText = new TextView(this);
        progressText.setTextSize(13);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setTextColor(UiStyle.INK);
        progressCard.addView(progressText);
        progressSegs = new LinearLayout(this);
        progressSegs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        segLp.topMargin = dp(10);
        progressCard.addView(progressSegs, segLp);
        root.addView(progressCard);

        // 步骤区（可滚动）
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        stepsBox.setPadding(dp(0), dp(2), dp(0), dp(18));
        sc.addView(stepsBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底部操作条
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(18), dp(10), dp(18), dp(18));

        // 批次8（P0-3）：点「稍后再说」记录 onboarding_deferred，后续冷启动不再自动弹
        // （☰ 功能菜单「新手引导」入口保留，可随时补做）。
        TextView later = UiStyle.ghostBtn(this, "稍后再说", this::deferAndFinish);
        bottom.addView(later, new LinearLayout.LayoutParams(0, dp(48), 1));
        primaryBtn = UiStyle.primaryBtn(this, "进入主页", this::finishFlow);
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        primaryLp.leftMargin = dp(10);
        bottom.addView(primaryBtn, primaryLp);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    // ==================================================================================
    // 二、状态刷新（自动检测 + 重建卡片 + 进度/按钮）
    // ==================================================================================

    private void refresh() {
        if (isFinishing() || isDestroyed() || stepsBox == null) return;

        LicenseState licenseState = null;
        boolean licensed = false;
        try {
            licenseState = licenseManager.recheckLocalState();
            licensed = licenseState != null && licenseState.usable();
        } catch (Throwable ignored) {
        }
        boolean hasAccount;
        List<AccountStore.Account> accounts;
        try {
            accounts = store.all();
            hasAccount = accounts != null && !accounts.isEmpty();
        } catch (Throwable t) {
            accounts = null;
            hasAccount = false;
        }
        boolean gameAlive = false;
        try {
            gameAlive = engine.isContainerGameAlive();
        } catch (Throwable ignored) {
        }
        boolean cloned = gameInstalled();
        boolean initialized = false;
        try {
            initialized = engineReady && cloned && engine.hasBaseline();
        } catch (Throwable ignored) {
        }

        int[] steps = OnboardingState.autoDetect(state.steps(), licensed, initialized, hasAccount, gameAlive);
        state.setSteps(steps);

        int tier = licenseTier();
        int requiredSteps = OnboardingState.requiredStepCount(tier);

        subtitleText.setText(revisit
                ? "再次查看：已完成步骤自动打勾，可跳过或补做"
                : "五步完成初始化（T1 普通版止于第④步）· 随时可从 ☰ 功能菜单再次进入");

        // 进度：已完成/已跳过都算"已处理"；T1 只需前 4 步
        progressText.setText("初始化进度 " + Math.min(OnboardingState.settledCount(steps), requiredSteps)
                + "/" + requiredSteps
                + "（完成 " + OnboardingState.doneCount(steps) + "）");
        rebuildProgressSegs(steps, requiredSteps);

        stepsBox.removeAllViews();

        // ① 兑换码
        addStepCard(OnboardingState.STEP_LICENSE, UiStyle.Icon.GIFT,
                licensed ? COLOR_DONE_BG : COLOR_TODO_BG,
                licensed ? COLOR_DONE : COLOR_TODO,
                "① 绑定兑换码",
                licenseStepDesc(licensed, licenseState),
                steps,
                licensed ? "更换兑换码" : "输入兑换码激活",
                () -> openActivateDialog(),
                null, null, null);

        // ② 克隆/初始化容器（复用既有初始化向导：克隆→首启→重置→存基线）
        String cloneDesc;
        String cloneAction;
        Runnable cloneRun;
        boolean cloneEnabled = true;
        if (!engineReady) {
            cloneDesc = "正在检测容器内游戏（克隆状态/基线），请稍候…";
            cloneAction = "检测中…";
            cloneRun = () -> UiStyle.toast(this, "正在检测容器内游戏，请稍候");
            cloneEnabled = false;
        } else if (initialized) {
            cloneDesc = "容器内游戏已克隆并已保存干净基线，可继续添加/启动账号。如需重新初始化可再次进入。";
            cloneAction = "重新打开初始化向导";
            cloneRun = () -> openMainPage("请在主页右上角 ☰ →「初始化向导」按需重新克隆/首启/存基线");
        } else if (!cloned) {
            cloneDesc = "尚未克隆游戏。请到主页右上角 ☰ →「初始化向导」，按 克隆→首启→重置→存基线 完成初始化。";
            cloneAction = "去完成克隆/初始化";
            cloneRun = () -> openMainPage("请在主页右上角 ☰ →「初始化向导」完成克隆/首启/存基线");
        } else {
            cloneDesc = "游戏已克隆，但尚未保存基线。请到主页右上角 ☰ →「初始化向导」完成 首启→重置→存基线。";
            cloneAction = "去保存基线（☰→初始化向导）";
            cloneRun = () -> openMainPage("请在主页右上角 ☰ →「初始化向导」完成首启→重置→存基线");
        }
        final String cloneActionText = cloneAction;
        final Runnable cloneRunFinal = cloneRun;
        final boolean cloneEnabledFinal = cloneEnabled;
        addStepCard(OnboardingState.STEP_CLONE, UiStyle.Icon.BOX,
                initialized ? COLOR_DONE_BG : COLOR_TODO_BG,
                initialized ? COLOR_DONE : COLOR_TODO,
                "② 克隆 / 初始化游戏", cloneDesc, steps,
                cloneActionText, cloneRunFinal, cloneEnabledFinal,
                "已完成初始化，刷新检测", this::refresh);

        // ③ 添加分身账户
        addStepCard(OnboardingState.STEP_ACCOUNT, UiStyle.Icon.USERS,
                hasAccount ? COLOR_DONE_BG : COLOR_TODO_BG,
                hasAccount ? COLOR_DONE : COLOR_TODO,
                "③ 添加分身账户",
                hasAccount
                        ? "已添加 " + (accounts == null ? 0 : accounts.size()) + " 个分身账户。可继续在「账号」页导入/克隆更多。"
                        : "在主页底部「账号」页点➕ 导入账号（可跳过本步，稍后再加）。",
                steps,
                hasAccount ? "管理账号" : "去添加分身账户",
                () -> openMainPage("在主页底部「账号」页点➕ 导入/克隆你的游戏账号，完成后返回本页"),
                null, null, null);

        // ④ 打开游戏分身
        String launchDesc;
        String launchAction;
        boolean launchActionEnabled = true;
        Runnable launchRun = this::openEntryFlow;
        if (!initialized) {
            launchDesc = "请先完成第②步：克隆/初始化容器后即可启动游戏分身。";
            launchAction = "先完成第②步初始化";
            launchActionEnabled = false;
            launchRun = () -> UiStyle.toast(this, "请先完成第②步：克隆/初始化游戏");
        } else if (accounts == null || accounts.isEmpty()) {
            launchDesc = "请先完成第③步：添加分身账户后即可打开游戏分身。";
            launchAction = "先添加分身账户";
            launchActionEnabled = false;
            launchRun = () -> UiStyle.toast(this, "请先完成第③步：添加分身账户");
        } else if (gameAlive) {
            launchDesc = "容器内游戏已在运行。返回游戏或继续下一步即可。";
            launchAction = "再次打开分身详情";
        } else {
            launchDesc = "容器内游戏已就绪。点击后在分身详情页点「启动分身」，启动成功返回本页会自动打勾。";
            launchAction = "选择账户并打开启动页";
        }
        final Runnable launchActionFinal = launchRun;
        final String launchActionText = launchAction;
        final boolean launchEnabledFinal = launchActionEnabled;
        addStepCard(OnboardingState.STEP_LAUNCH, UiStyle.Icon.PLAY,
                gameAlive ? COLOR_DONE_BG : COLOR_TODO_BG,
                gameAlive ? COLOR_DONE : COLOR_TODO,
                "④ 打开游戏分身", launchDesc, steps,
                launchActionText, launchActionFinal, launchEnabledFinal,
                "我已启动，标记完成", () -> {
                    state.markDone(OnboardingState.STEP_LAUNCH);
                    UiStyle.toast(this, "已标记第④步完成");
                    refresh();
                });

        // ⑤ 打开自动化（T2+；T1 显示升级说明、不强制阻塞）
        String automationDesc;
        String automationAction;
        Runnable automationRun;
        boolean automationEnabled = true;
        boolean member = tier >= 2;
        if (member) {
            automationDesc = "在主页底部「日常」页设置各项自动任务（统一生命周期执行）。"
                    + "当前授权等级已支持日常自动化（T2 及以上）。";
            automationAction = "去「日常」页设置任务";
            automationRun = () -> {
                state.markDone(OnboardingState.STEP_AUTOMATION);
                openMainPage("请切到底部「日常」页设置自动任务");
                refresh();
            };
        } else {
            automationDesc = "会员功能（T2 起）：日常自动化任务需 T2 会员权限。当前为普通版（T1），"
                    + "可先跳过本步；升级后在「日常」页即可使用（本引导不强制阻塞）。";
            automationAction = "查看升级说明";
            automationRun = () -> showUpgradeDialog();
        }
        final String automationActionText = automationAction;
        final Runnable automationRunFinal = automationRun;
        final boolean automationEnabledFinal = automationEnabled;
        addStepCard(OnboardingState.STEP_AUTOMATION, UiStyle.Icon.CHECKLIST,
                member && OnboardingState.statusOf(steps, OnboardingState.STEP_AUTOMATION)
                        == OnboardingState.DONE ? COLOR_DONE_BG : COLOR_TODO_BG,
                member && OnboardingState.statusOf(steps, OnboardingState.STEP_AUTOMATION)
                        == OnboardingState.DONE ? COLOR_DONE : COLOR_TODO,
                "⑤ 打开自动化任务（" + (member ? "T2 会员" : "会员功能") + "）", automationDesc, steps,
                automationActionText, automationRunFinal, automationEnabledFinal,
                null, null);

        boolean settled = OnboardingState.allSettled(steps, requiredSteps);
        primaryBtn.setText(settled ? "完成，进入主页" : "还有步骤未完成");
        primaryBtn.setAlpha(settled ? 1f : 0.55f);
        primaryBtn.setOnClickListener(v -> {
            if (!OnboardingState.allSettled(state.steps(),
                    OnboardingState.requiredStepCount(licenseTier()))) {
                UiStyle.toast(this, "还有步骤未完成：可逐项完成，或点每步「跳过」");
                return;
            }
            finishFlow();
        });
        // T2+：完成第⑤步后自动收尾进入主页；T1：止于第④步，保留按钮让用户看完第⑤步升级说明再手动进入。
        maybeAutoFinish(settled && member);
    }

    /** 升级说明弹窗（T1 用户第⑤步：只提示，不阻塞、不发起支付）。 */
    private void showUpgradeDialog() {
        try {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("会员功能说明")
                    .setMessage("日常自动化任务（今日/账号/日常页的自动领取与配置）需要 T2 会员权限。\n\n"
                            + "升级方式：购买会员兑换码后，在本页第①步或主页 ☰→授权管理 中更换兑换码即可。\n\n"
                            + "本引导不会因该步未完成而阻塞；可先跳过，升级后再从 ☰→新手引导 回来设置。")
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            UiStyle.toast(this, "当前为普通版（T1）：日常自动化需 T2 会员权限，升级后可在此设置");
        }
    }

    /** 授权步骤说明：已激活显示剩余，未激活提示输入兑换码（不重写激活协议）。 */
    private String licenseStepDesc(boolean licensed, LicenseState licenseState) {
        if (licensed) {
            String remain = "";
            try {
                remain = licenseManager.describeRemain();
            } catch (Throwable ignored) {
            }
            return "授权已激活" + (remain == null || remain.isEmpty() ? "" : "：" + remain)
                    + "。授权码一码一设备；如需续费/换绑可在此重新输入。";
        }
        String title = licenseState == null ? "未激活" : licenseState.title();
        return title + "。输入购买的兑换码激活授权（授权是使用号库与自动化功能的前提，"
                + "不会发起任何游戏内操作）。";
    }

    private void rebuildProgressSegs(int[] steps, int requiredSteps) {
        progressSegs.removeAllViews();
        for (int step = 1; step <= requiredSteps; step++) {
            int status = OnboardingState.statusOf(steps, step);
            View seg = new View(this);
            int color = status == OnboardingState.DONE ? UiStyle.BRAND
                    : status == OnboardingState.SKIPPED ? UiStyle.INK3
                    : UiStyle.LINE;
            seg.setBackground(UiStyle.roundRectDp(this, color, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            if (step > 1) lp.leftMargin = dp(6);
            progressSegs.addView(seg, lp);
        }
    }

    // ==================================================================================
    // 三、步骤卡片（统一构件）
    // ==================================================================================

    /**
     * 单步卡片：图标 + 标题 + 状态角标 + 说明 + 主按钮 + 附加链接（跳过/辅助动作）。
     */
    private void addStepCard(int step, int icon, int iconBg, int iconColor,
                             String title, String desc, int[] steps,
                             String primaryText, Runnable primaryAction, Boolean primaryEnabled,
                             String secondaryText, Runnable secondaryAction) {
        int status = OnboardingState.statusOf(steps, step);
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(18), dp(12), dp(18), 0);
        card.setLayoutParams(cardLp);

        // 头部：图标 + 标题 + 状态角标
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiStyle.iconTile(this, icon, iconColor, iconBg, 36, 18, 10),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiStyle.INK);
        tv.setPadding(dp(10), 0, dp(8), 0);
        head.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(UiStyle.badge(this, OnboardingState.statusBadgeType(status),
                OnboardingState.statusLabel(status)));
        card.addView(head);

        // 说明
        TextView descView = new TextView(this);
        descView.setText(desc);
        descView.setTextSize(12);
        descView.setTextColor(UiStyle.INK2);
        descView.setLineSpacing(dp(2), 1f);
        descView.setPadding(0, dp(8), 0, 0);
        card.addView(descView);

        // 主按钮（已完成时降为次级：可再次执行该步动作）
        boolean enabled = primaryEnabled == null || primaryEnabled;
        TextView actionBtn = status == OnboardingState.DONE
                ? UiStyle.secondaryBtn(this, primaryText, primaryAction)
                : UiStyle.primaryBtn(this, primaryText, primaryAction);
        if (!enabled) {
            actionBtn.setAlpha(0.5f);
            actionBtn.setOnClickListener(v -> {
                if (primaryAction != null) primaryAction.run();
            });
        }
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        actionLp.topMargin = dp(12);
        card.addView(actionBtn, actionLp);

        // 附加链接行：跳过本步 / 步骤特有辅助动作
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setGravity(Gravity.CENTER_VERTICAL);
        links.setPadding(0, dp(4), 0, 0);
        if (status == OnboardingState.PENDING) {
            TextView skip = UiStyle.ghostBtn(this, "跳过本步", () -> {
                state.markSkipped(step);
                UiStyle.toast(this, "已跳过第 " + step + " 步，可稍后在 ☰ 功能菜单重新进入");
                refresh();
            });
            links.addView(skip, new LinearLayout.LayoutParams(dp(108), dp(36)));
        }
        if (secondaryText != null && secondaryAction != null) {
            TextView extra = UiStyle.ghostBtn(this, secondaryText, secondaryAction);
            links.addView(extra, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        }
        if (links.getChildCount() > 0) card.addView(links);

        // 已完成/已跳过水印行
        if (status != OnboardingState.PENDING) {
            TextView note = new TextView(this);
            note.setText(status == OnboardingState.DONE
                    ? "✓ 该步已完成"
                    : "— 该步已跳过（可再次执行）");
            note.setTextSize(11);
            note.setTextColor(status == OnboardingState.DONE ? UiStyle.OK_INK : UiStyle.INK3);
            note.setPadding(0, dp(2), 0, 0);
            card.addView(note);
        }
        stepsBox.addView(card);
    }

    // ==================================================================================
    // 四、各步动作（全部复用现有能力，无新协议/网络请求）
    // ==================================================================================

    /** 第①步：复用 LicenseUi 的激活弹窗（内部走 LicenseManager.activateAsync 既有授权协议）。 */
    private void openActivateDialog() {
        try {
            LicenseUi.showActivateDialog(this, licenseManager, new LicenseUi.OnChanged() {
                @Override
                public void onChanged() {
                    UiStyle.toast(OnboardingActivity.this, "兑换码激活成功");
                    refresh();
                }
            });
        } catch (Throwable t) {
            UiStyle.toast(this, "打开激活弹窗失败：" + t);
        }
    }

    /**
     * 第②/④步：把主页（HomeActivity，四 Tab：今日/账号/日常/我的）带到前台并提示操作路径。
     * 不修改 HomeActivity：REORDER_TO_FRONT 优先复用已存在实例，返回键即回到本引导页。
     */
    private void openMainPage(String toastHint) {
        try {
            Intent it = new Intent(this, HomeActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(it);
            if (toastHint != null && !toastHint.isEmpty()) {
                UiStyle.toast(this, toastHint);
            }
        } catch (Throwable t) {
            UiStyle.toast(this, "打开主页失败：" + t);
        }
    }

    /** 第③步：选择账户 → 打开 EntryDetailActivity 既有「启动分身」页（IME 预热/诊断链路不变）。 */
    private void openEntryFlow() {
        List<AccountStore.Account> accounts;
        try {
            accounts = store.all();
        } catch (Throwable t) {
            accounts = null;
        }
        if (accounts == null || accounts.isEmpty()) {
            UiStyle.toast(this, "请先完成第②步：添加分身账户");
            return;
        }
        if (accounts.size() == 1) {
            openEntryDetail(accounts.get(0));
            return;
        }
        final List<AccountStore.Account> list = accounts;
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            AccountStore.Account a = list.get(i);
            labels[i] = a.label != null && !a.label.isEmpty() ? a.label : a.id;
        }
        try {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("选择要打开的分身账户")
                    .setItems(labels, (dialog, which) -> openEntryDetail(list.get(which)))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            UiStyle.toast(this, "选择账户失败：" + t);
        }
    }

    private void openEntryDetail(AccountStore.Account account) {
        try {
            Intent it = new Intent(this, EntryDetailActivity.class);
            it.putExtra(EntryDetailActivity.EXTRA_ID, account.id);
            startActivity(it);
            UiStyle.toast(this, "在详情页点「启动分身」；启动成功返回本页会自动打勾");
        } catch (Throwable t) {
            UiStyle.toast(this, "打不开分身详情（" + reasonOf(t) + "）");
        }
    }

    // ==================================================================================
    // 五、完成/权限提示
    // ==================================================================================

    /** 全部步骤有结论后：首次引导自动收尾（再次进入模式只提示，不自动关）。 */
    private void maybeAutoFinish(boolean settled) {
        if (!settled || finishing) return;
        if (revisit) {
            primaryBtn.setText("完成");
            return;
        }
        if (autoFinishScheduled) return;
        autoFinishScheduled = true;
        handler.postDelayed(() -> {
            autoFinishScheduled = false;
            if (isFinishing() || isDestroyed()) return;
            finishFlow();
        }, 700L);
    }

    /**
     * 批次8（P0-3）：「稍后再说」= 记 onboarding_deferred 后关闭当前页。
     * 之后冷启动不再自动弹出（老用户不被反复打扰），入口保留在 ☰ 功能菜单里。
     */
    private void deferAndFinish() {
        try {
            if (state != null) state.setDeferred(true);
        } catch (Throwable ignored) {
        }
        finish();
    }

    /** 完成引导：持久化 onboarding_done 并回到主页（主页实例在引导页下面，finish 即可见）。 */
    private void finishFlow() {
        if (finishing) return;
        finishing = true;
        try {
            state.setDone(true);
        } catch (Throwable ignored) {
        }
        UiStyle.toast(this, revisit ? "新手引导已关闭" : "初始化完成，进入主页");
        try {
            if (isTaskRoot()) {
                Intent it = new Intent(this, HomeActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
            }
        } catch (Throwable ignored) {
        }
        finish();
    }

    /** 容器内游戏是否已克隆（异常按未克隆处理，给可读引导）。 */
    private boolean gameInstalled() {
        try {
            return engine.isGameInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 授权等级：1 普通 / 2 会员 / 3 高级；读不到时按存量码默认 3。 */
    private int licenseTier() {
        try {
            Method m = licenseManager == null ? null : licenseManager.getClass().getMethod("tier");
            if (m != null) {
                Object v = m.invoke(licenseManager);
                if (v instanceof Integer) return (Integer) v;
            }
        } catch (Throwable ignored) {
        }
        return 3;
    }

    private static String reasonOf(Throwable t) {
        if (t == null) return "未知原因";
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }
}
