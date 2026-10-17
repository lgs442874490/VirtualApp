package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.daily.DailyResult;
import com.carlos.vault.daily.DailyRunner;
import com.carlos.vault.daily.DailySchedulerService;
import com.carlos.vault.daily.FnRegistry;
import com.carlos.vault.daily.LogStore;
import com.carlos.vault.daily.ProtoLogger;
import com.carlos.vault.daily.RoleStore;
import com.carlos.vault.license.LicenseManager;
import com.carlos.vault.license.LicenseTicket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 新版主界面（设计方案 v1）：底部四 Tab —— 今日 / 账号 / 任务 / 我的。
 * - 今日：Hero（完成度+切换账号/切换角色）+ 号库矩阵（默认，可切列表）+ 限时窗口 + 快捷入口
 * - 账号：搜索 + 筛选chips + 列表（单击=启动，⋯=日常/管理/删除）
 * - 任务：自动日常 + 分类chips + 折叠任务行（tap→配置页）+ 进阶工具
 * - 我的：授权卡 + 分组列表（运行/数据/初始化/危险区）
 * Hero 主按钮=切换账号 / 切换角色（数据全自动，切换即刷新全部页面）。
 */
public class HomeActivity extends AccountVaultActivity {

    private FrameLayout content;
    private final LinearLayout[] pages = new LinearLayout[4];
    private final TextView[] navItems = new TextView[4];
    private final View[] navBars = new View[4];

    // 今日页
    private TextView heroDone, heroSub, statRun, statErr, statWait, topCaption;
    private UiStyle.Ring heroRing;
    private TextView autoStateBtn;   // 自动执行状态卡（点=开关自动日常）
    private TextView onlineDot;      // 当前角色在线状态（容器登录态口径）
    private TextView actVal, actTier;      // 活跃度档位进度条（进度表达规范）
    private View actFill;
    private boolean syncing;
    private LinearLayout limitBox, rallyBox;
    private String selectedAccountId;
    /** 全局当前角色 "fpid:sid" —— 今日/任务/详情页所有数据跟随它（不再按账号activeRole漂移） */
    private String currentRoleKey;

    // 账号页
    private LinearLayout accountList;
    private EditText searchInput;
    private TextView accountCap;
    private int accountFilter = 0;   // 0全部 1工作区 2异常 3未跑
    private final java.util.Set<String> selectedAccounts = new java.util.HashSet<>();
    private LinearLayout batchBar;
    private final List<TextView> filterChips = new ArrayList<>();
    private View accMask;          // 账号页加载遮罩（状态计算后台化期间的兜底）
    private int accGen;            // 防止快速刷新时旧结果覆盖新结果
    private int accView;           // 0=列表视图 1=卡片墙（旧版角色大方块卡）
    private android.widget.LinearLayout accSeg;   // 列表|卡片 分段（选中态跟随刷新）
    private final java.util.Set<String> expandedAccs = new java.util.HashSet<>();

    // 任务页
    private LinearLayout taskRows;
    private TextView taskCap, bannerTitle, bannerSub, winBtn;
    private UiStyle.Switch autoSwitch;
    private UiStyle.Switch keepSwitch;   // 24h 常驻在线开关（心跳+自动互助，与自动日常正交）
    private TextView keepSub;            // 常驻在线状态行（在线/重连中/被顶号/需重导token）
    private TextView helpStatusText;     // 今日页互助卡状态行
    private TextView helpActionBtn;      // 今日页「一键帮助全盟」按钮
    private final List<TextView> catChips = new ArrayList<>();
    private int taskCat = 0;
    private AccountStore.Account taskAccount;
    private String expandedFn;
    private UiStyle.Switch fnSwRef(UiStyle.Switch s) { return s; }
    private boolean fnOn(FnRegistry.Fn fn) {
        if (taskAccount == null) return false;
        // 口径统一：日常页一切读写跟随「全局当前角色」（与 refreshTasks 状态徽章同源），
        // 不走 activeSid——否则切换角色后开关仍读写旧角色配置（配置残留/串写）。
        long sid = currentRoleSid();
        Map<String, Boolean> cfg = new com.carlos.vault.daily.DailyConfigStore(this)
                .roleConfig(taskAccount.fpid, sid);
        Boolean on = cfg == null ? null : cfg.get(fn.id);
        return on == null ? (fn.implemented && defOn(fn.id)) : on;
    }
    private void setFnOn(FnRegistry.Fn fn, boolean on) {
        if (taskAccount == null) return;
        long sid = currentRoleSid();
        com.carlos.vault.daily.DailyConfigStore cs = new com.carlos.vault.daily.DailyConfigStore(this);
        Map<String, Boolean> cfg = cs.roleConfig(taskAccount.fpid, sid);
        if (cfg == null) cfg = new java.util.LinkedHashMap<>();
        cfg.put(fn.id, on);
        cs.saveRoleConfig(taskAccount.fpid, sid, cfg);
    }
    private boolean defOn(String fnId) {
        for (com.carlos.vault.daily.DailyTasks.TaskDef d : com.carlos.vault.daily.DailyTasks.all()) {
            if (d.id.equals(fnId)) return d.enabledByDefault;
        }
        return true;
    }

    // 我的页
    private TextView licenseDays;

    // 执行引擎（可离开：静态态 + 附着的执行页回调）
    static final RunState RUN = new RunState();

    static class RunState {
        volatile boolean running;
        volatile int done, total;
        volatile String current = "";
        final List<String> logs = new ArrayList<>();
        Runnable attacher;   // 执行页附着回调

        void log(String line) {
            synchronized (logs) {
                logs.add(line);
                if (logs.size() > 200) logs.remove(0);
            }
            Runnable a = attacher;
            if (a != null) a.run();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /** 由父类 onCreate 在核心初始化后调用（替代旧版 buildUi）。 */
    void buildHomeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildBottomNav());

        for (int i = 0; i < 4; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            content.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        currentRoleKey = getSharedPreferences("home", MODE_PRIVATE).getString("current_role", null);
        alignActiveRoleWithCurrent();
        buildTodayPage();
        buildAccountPage();
        buildTaskPage();
        buildMinePage();
        setContentView(root);
        switchTab(0);
    }

    void onEngineReady() {
        refreshAllPages();
        // 打开即全量核对：所有token账号后台逐个登录核对资料；开了自动且今天没跑的号直接补跑
        new Thread(this::kickFullSync, "fullsync-kick").start();
    }

    /**
     * 账号库数据变化钩子（父类 refresh() 在无经典九宫格时的回调路径）。
     * 触发源：批量导入完成、启动分身成功标记 lastLogin、向导步骤落盘等。
     * 只重刷账号页（列表/角色表/批量条随数据重算）；今日页 Hero 卡在切页时自刷。
     */
    void onAccountsChanged() {
        refreshAccounts();
    }

    /**
     * 从功能详情页 / 容器游戏 / 桌面返回时四页全部重渲染。
     * <p>历史缺陷：父类 onResume 的守卫 {@code status == null} 对本页恒真（不走 buildUi），
     * 返回主界面时三页停留在离开前的旧状态（在线标记、今日进度、账号表全不更新）。
     * 各 refresh 方法自带视图空守卫，门禁页期（buildHomeUi 未跑）调用是安全的空操作。
     * 账号表重载由父类 refresh() 完成（onResume 内先于本方法执行）。
     */
    @Override
    protected void onResume() {
        super.onResume();
        refreshAllPages();
    }

    private boolean fullSyncing;

    /** 打开App后台全量自动化：①逐账号核对资料(syncOnly) ②auto开启且今日未跑的账号补跑日常。 */
    private void kickFullSync() {
        if (fullSyncing) return;
        long lastFull = getSharedPreferences("home", MODE_PRIVATE).getLong("last_fullsync", 0);
        if (System.currentTimeMillis() - lastFull < 10 * 60_000L) return;   // 10分钟防抖
        // 服务器模式：资料核对由服务器常驻会话负责，本地全量上传一次 + 拉回传状态与采集数据；②补跑日常仍走本地
        final boolean serverMode = VaultSync.isServerMode(this);
        if (serverMode) {
            VaultSync.syncAll(this);
            VaultSync.fetchState(this);
        }
        fullSyncing = true;
        try {
            List<AccountStore.Account> all = new ArrayList<>(accounts == null
                    ? new ArrayList<>() : accounts);
            int idx = 0;
            for (AccountStore.Account a : all) {
                if (a == null || a.token == null || a.token.isEmpty()
                        || a.fpid == null || a.fpid.isEmpty()) continue;
                idx++;
                final int prog = idx;
                runOnUiThread(() -> {
                    if (accountCap != null) {
                        accountCap.setText("核对中 " + prog + "/" + all.size()
                                + " · 工作区 " + workspaceAccounts().size());
                    }
                });
                if (!serverMode) {
                    try {
                        DailyRunner.syncOnly(this, a.fpid, a.token, mainRoleSid(a));
                    } catch (Throwable ignored) {
                    }
                }
                runOnUiThread(this::refreshAccounts);
            }
            // ②补跑：开了自动且今天还没跑过的账号
            java.text.SimpleDateFormat dayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.CHINA);
            String today = dayFmt.format(new java.util.Date());
            for (AccountStore.Account a : all) {
                if (a == null || a.token == null || a.token.isEmpty()
                        || a.fpid == null || a.fpid.isEmpty()) continue;
                RoleStore.Schedule sc = RoleStore.schedule(this, a.fpid);
                if (!sc.autoEnabled) continue;
                long lastRun = sc.lastRunAt;
                boolean ranToday = lastRun > 0 && dayFmt.format(new java.util.Date(lastRun)).equals(today);
                if (ranToday) continue;
                runOnUiThread(() -> toast("自动补跑：" + a.label + "（今天还没跑）"));
                try {
                    String r = DailyRunner.runAccountRoles(this, a);
                    if (r != null) {
                        sc.lastRunAt = System.currentTimeMillis();
                        RoleStore.saveSchedule(this, a.fpid, sc);
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(this::refreshAllPages);
            }
            getSharedPreferences("home", MODE_PRIVATE).edit()
                    .putLong("last_fullsync", System.currentTimeMillis()).apply();
            runOnUiThread(() -> {
                if (accountCap != null) refreshAccounts();
                refreshAllPages();
            });
        } finally {
            fullSyncing = false;
        }
    }

    // ================= 底部导航 =================

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundColor(UiStyle.WHITE);
        // 顶部 1px 分割线（独立垂直层，不挤占横向空间）
        View line = new View(this);
        line.setBackgroundColor(UiStyle.LINE);
        nav.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        String[] names = {"今日", "账号", "日常", "我的"};
        String[] icons = {"◉", "☰", "⚙", "▣"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(6), 0, 0);
            // 选中指示：顶部 16dp 短横条
            View bar = new View(this);
            bar.setBackgroundColor(UiStyle.BRAND);
            item.addView(bar, new LinearLayout.LayoutParams(dp(16), dp(3)));
            bar.setTag("bar" + i);
            // 图标：定高 22dp + 关闭字体内边距。◉/☰/⚙/▣ 是象形字形，回退字体的
            // ascent/descent 远超字号，wrap_content 下字形 ink 会溢出视图盒压到
            // 下方文字上（选中态蓝粗体下尤其明显）。定高盒 + CENTER 重力把字形
            // 收进盒内，与文字之间再留 2dp 间距，保证永不相叠。
            TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextSize(16);
            icon.setGravity(Gravity.CENTER);
            icon.setIncludeFontPadding(false);
            icon.setTag("icon" + i);
            item.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
            // 文字：与图标固定 2dp 间距；同样关字体 padding，10.5sp 粗体也不再侵占图标盒
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextSize(10.5f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setTag("label" + i);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(2);
            item.addView(label, llp);
            UiStyle.press(item);
            item.setOnClickListener(v -> switchTab(idx));
            navBars[i] = bar;
            navItems[i] = label;
            row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nav.setLayoutParams(nlp);
        return nav;
    }

    private void switchTab(int which) {
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == which ? View.VISIBLE : View.GONE);
            navItems[i].setTextColor(i == which ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            navItems[i].setTypeface(i == which ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (navBars[i] != null) {
                navBars[i].setVisibility(i == which ? View.VISIBLE : View.INVISIBLE);
            }
        }
        if (which == 0) refreshToday();
        if (which == 1) refreshAccounts(true);
        if (which == 2) {
            refreshTasks();
            taskAccount = currentSelected();
            if (taskAccount != null) autoSyncAccount(taskAccount, true);
        }
        if (which == 3) refreshMine();
    }

    // ================= Tab1 · 今日 =================

    private void buildTodayPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(20));
        sc.addView(box);
        pages[0].addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // —— 顶栏：标题 + 日期·自动日常 + ☰ ——
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("无尽号库");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        topCaption = new TextView(this);
        topCaption.setTextSize(11.5f);
        topCaption.setTextColor(UiStyle.BRAND_TEXT);
        topCaption.setTypeface(Typeface.DEFAULT_BOLD);
        tb.addView(topCaption);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView menu = new TextView(this);
        menu.setText("☰");
        menu.setTextSize(20);
        menu.setTextColor(UiStyle.INK);
        menu.setPadding(dp(10), dp(6), dp(4), dp(6));
        UiStyle.press(menu);
        menu.setOnClickListener(v -> showMainMenu());
        top.addView(menu);
        box.addView(top);

        // —— Hero 卡（原型同款：环+完成度+当前角色+切换账号/切换角色双按钮）——
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(14));
        GradientDrawable hg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{UiStyle.BRAND_PRESS, UiStyle.BRAND});
        hg.setCornerRadius(dp(20));
        hero.setBackground(hg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(12);
        box.addView(hero, hlp);

        // 左：环（数字在环内）；右：信息行 + 活跃度档位条（规范第一张截图：环与活跃度并排）
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        heroRing = new UiStyle.Ring(this);
        heroTop.addView(heroRing);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(16), 0, 0, 0);
        LinearLayout heroSubRow = new LinearLayout(this);
        heroSubRow.setOrientation(LinearLayout.HORIZONTAL);
        heroSubRow.setGravity(Gravity.CENTER_VERTICAL);
        heroSub = new TextView(this);
        heroSub.setTextSize(11.5f);
        heroSub.setTextColor(UiStyle.BRAND_100);
        heroSubRow.addView(heroSub, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        onlineDot = new TextView(this);
        onlineDot.setText("· 检测中");
        onlineDot.setTextSize(11.5f);
        onlineDot.setTypeface(Typeface.DEFAULT_BOLD);
        onlineDot.setTextColor(UiStyle.BRAND_100);
        onlineDot.setPadding(dp(6), 0, 0, 0);
        heroSubRow.addView(onlineDot);
        // （keepDot 已移除：M3 起 onlineDot 即显示当前角色的常驻会话状态，
        //  旧「◉ 常驻在线」徽标与之重复）
        right.addView(heroSubRow);

        // 活跃度档位进度条（进度表达规范：当前值/满档 + 下一档还差多少）
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView actLbl = new TextView(this);
        actLbl.setText("活跃度");
        actLbl.setTextSize(12);
        actLbl.setTextColor(UiStyle.BRAND_100);
        actRow.addView(actLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actVal = new TextView(this);
        actVal.setTextSize(12);
        actVal.setTypeface(Typeface.DEFAULT_BOLD);
        actVal.setTextColor(UiStyle.WHITE);
        actRow.addView(actVal);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = dp(8);
        right.addView(actRow, arlp);
        LinearLayout actBar = new LinearLayout(this);
        actBar.setOrientation(LinearLayout.HORIZONTAL);
        actBar.setBackground(UiStyle.roundRect(UiStyle.WHITE_20, dp(3)));
        actFill = new View(this);
        actFill.setBackground(UiStyle.roundRect(UiStyle.WHITE, dp(3)));
        actBar.addView(actFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        ablp.topMargin = dp(6);
        right.addView(actBar, ablp);
        actTier = new TextView(this);
        actTier.setTextSize(11);
        actTier.setTextColor(UiStyle.BRAND_100);
        actTier.setPadding(0, dp(6), 0, 0);
        right.addView(actTier);
        heroTop.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(heroTop);

        // 双按钮并行一行（规范第一张截图：切换账号 + 切换角色在下方并排）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(14), 0, 0);
        // 双按钮：切换账号（主）/ 切换角色（次）——数据全自动，这里只做切换
        TextView accBtn = new TextView(this);
        accBtn.setText("切换账号");
        accBtn.setTextSize(14.5f);
        accBtn.setTypeface(Typeface.DEFAULT_BOLD);
        accBtn.setTextColor(UiStyle.BRAND_TEXT);
        accBtn.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(UiStyle.WHITE);
        sg.setCornerRadius(dp(16));
        accBtn.setBackground(sg);
        accBtn.setElevation(dp(5));
        UiStyle.press(accBtn);
        accBtn.setOnClickListener(v -> showAccountSwitcher());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, dp(48), 1.35f);
        btnRow.addView(accBtn, mlp);
        TextView roleBtn = new TextView(this);
        roleBtn.setText("切换角色");
        roleBtn.setTextSize(13);
        roleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        roleBtn.setTextColor(UiStyle.WHITE);
        roleBtn.setGravity(Gravity.CENTER);
        GradientDrawable og = new GradientDrawable();
        og.setColor(UiStyle.WHITE_12);
        og.setCornerRadius(dp(16));
        og.setStroke(dp(1), UiStyle.WHITE_45);
        roleBtn.setBackground(og);
        UiStyle.press(roleBtn);
        roleBtn.setOnClickListener(v -> showRolePicker());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        olp.leftMargin = dp(8);
        btnRow.addView(roleBtn, olp);
        hero.addView(btnRow);

        // 自动执行状态行（点击=开关）
        autoStateBtn = new TextView(this);
        autoStateBtn.setTextSize(11.5f);
        autoStateBtn.setTypeface(Typeface.DEFAULT_BOLD);
        autoStateBtn.setTextColor(UiStyle.BRAND_100);
        autoStateBtn.setGravity(Gravity.CENTER);
        autoStateBtn.setPadding(dp(8), dp(10), dp(8), dp(2));
        UiStyle.press(autoStateBtn);
        autoStateBtn.setOnClickListener(v -> {
            AccountStore.Account cra = currentRoleAccount();
            if (cra == null) {
                toast("没有可用账号");
                return;
            }
            RoleStore.Schedule sch2 = RoleStore.schedule(this, cra.fpid);
            sch2.autoEnabled = !sch2.autoEnabled;
            RoleStore.saveSchedule(this, cra.fpid, sch2);
            // 开关变化必须同步服务器（RoundScheduler 按 auto 排整轮；漏同步=服务器永远不知道）
            VaultSync.onAccountsChanged(this);
            toast("自动日常已" + (sch2.autoEnabled ? "开启" : "关闭"));
            refreshToday();
        });
        LinearLayout.LayoutParams aslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aslp.topMargin = dp(8);
        autoStateBtn.setLayoutParams(aslp);
        hero.addView(autoStateBtn);



        // —— 限时窗口（巨熊预约 + 活动日历，可点击进设置）——
        LinearLayout todoHead = new LinearLayout(this);
        todoHead.setOrientation(LinearLayout.HORIZONTAL);
        todoHead.setGravity(Gravity.CENTER_VERTICAL);
        todoHead.setPadding(dp(2), dp(16), dp(2), dp(10));
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thlp.topMargin = dp(6);
        todoHead.setLayoutParams(thlp);
        TextView todoTitle = new TextView(this);
        todoTitle.setText("代办事项");
        todoTitle.setTextSize(15);
        todoTitle.setTypeface(Typeface.DEFAULT_BOLD);
        todoTitle.setTextColor(UiStyle.INK);
        todoHead.addView(todoTitle);
        todoHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView todoHint = new TextView(this);
        todoHint.setText("错过要等下一轮");
        todoHint.setTextSize(11);
        todoHint.setTextColor(UiStyle.INK2);
        todoHead.addView(todoHint);
        box.addView(todoHead);
        limitBox = new LinearLayout(this);
        limitBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(limitBox);

        // —— 联盟互助卡（常驻自动响应 + 一键帮助全盟）——
        LinearLayout helpCard = UiStyle.outlineCard(this);
        helpCard.setOrientation(LinearLayout.VERTICAL);
        helpCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hclp.topMargin = dp(12);
        helpCard.setLayoutParams(hclp);
        LinearLayout helpHead = new LinearLayout(this);
        helpHead.setOrientation(LinearLayout.HORIZONTAL);
        helpHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView helpTitle = new TextView(this);
        helpTitle.setText("🤝 联盟互助");
        helpTitle.setTextSize(13.5f);
        helpTitle.setTypeface(Typeface.DEFAULT_BOLD);
        helpTitle.setTextColor(UiStyle.INK);
        helpHead.addView(helpTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        helpActionBtn = new TextView(this);
        helpActionBtn.setText("一键帮助全盟");
        helpActionBtn.setTextSize(12);
        helpActionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        helpActionBtn.setTextColor(UiStyle.WHITE);
        helpActionBtn.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(UiStyle.BRAND);
        hbg.setCornerRadius(dp(10));
        helpActionBtn.setBackground(hbg);
        helpActionBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        UiStyle.press(helpActionBtn);
        helpActionBtn.setOnClickListener(v -> oneKeyHelpAll());
        helpHead.addView(helpActionBtn);
        helpCard.addView(helpHead);
        helpStatusText = new TextView(this);
        helpStatusText.setTextSize(11.5f);
        helpStatusText.setTextColor(UiStyle.INK2);
        helpStatusText.setPadding(0, dp(6), 0, 0);
        helpStatusText.setText("加载中…");
        helpCard.addView(helpStatusText);
        box.addView(helpCard);

        // —— 联盟大厅（正在集结的队列）——
        box.addView(UiStyle.sectionTitle(this, "联盟大厅 · 正在集结"));
        rallyBox = new LinearLayout(this);
        rallyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(rallyBox);

        // —— 快捷入口 ——
        box.addView(UiStyle.sectionTitle(this, "快捷入口"));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        String[][] qs = {{"🎁", "补领", "daily"}, {"⇄", "切号", "switch"}, {"🗒", "日志", "log"}, {"🧰", "工具箱", "tools"}};
        for (String[] q : qs) {
            LinearLayout cell = UiStyle.outlineCard(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(8), dp(10), dp(8), dp(10));
            TextView icon = new TextView(this);
            icon.setText(q[0]);
            icon.setTextSize(20);
            icon.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(q[1]);
            label.setTextSize(11.5f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(UiStyle.INK);
            label.setGravity(Gravity.CENTER);
            cell.addView(icon);
            cell.addView(label);
            UiStyle.press(cell);
            cell.setOnClickListener(v -> {
                if ("switch".equals(q[2])) showAccountSwitcher();
                else quickAction(q[2]);
            });
            LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            q2.rightMargin = dp(8);
            quick.addView(cell, q2);
        }
        box.addView(quick);
    }

    /** 账号切换（主按钮）：任务页/限时/联盟大厅联动当前账号。 */
    /** 账号主角色：工作区第一个，无则 activeRole，再无则第一角色。 */
    private long mainRoleSid(AccountStore.Account a) {
        try {
            List<RoleStore.Role> rs = RoleStore.roles(this, a.fpid);
            for (RoleStore.Role r : rs) {
                if (r.enabled) return r.serverid;
            }
            long act = RoleStore.activeRole(this, a.fpid);
            if (act > 0) return act;
            if (!rs.isEmpty()) return rs.get(0).serverid;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void showAccountSwitcher() {
        List<AccountStore.Account> cand = new ArrayList<>();
        if (accounts != null) {
            for (AccountStore.Account a : accounts) {
                if (a != null && a.token != null && !a.token.isEmpty()
                        && a.fpid != null && !a.fpid.isEmpty()) cand.add(a);
            }
        }
        if (cand.isEmpty()) {
            toast("没有可用账号");
            return;
        }
        // 每个账号显示：备注名 + 主角色名
        final long[] mainSids = new long[cand.size()];
        String[] names = new String[cand.size()];
        AccountStore.Account cur = currentRoleAccount();
        int checked = 0;
        for (int i = 0; i < cand.size(); i++) {
            AccountStore.Account a = cand.get(i);
            mainSids[i] = mainRoleSid(a);
            String mainName = "";
            try {
                for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                    if (r.serverid == mainSids[i] && r.name != null && !r.name.isEmpty()) {
                        mainName = r.name;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            names[i] = a.label + "（主角色：" + (mainName.isEmpty() ? "K" + mainSids[i] : mainName) + "）";
            if (cur != null && a.fpid != null && a.fpid.equals(cur.fpid)) checked = i;
        }
        final AccountStore.Account[] pick = {cand.get(checked)};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("切换账号（选中即看其主角色）")
                .setSingleChoiceItems(names, checked, (d, w) -> pick[0] = cand.get(w))
                .setPositiveButton("切换", (d, w) -> {
                    AccountStore.Account target = pick[0];
                    selectedAccountId = target.id;
                    getSharedPreferences("home", MODE_PRIVATE).edit()
                            .putString("current_account", selectedAccountId).apply();
                    long msid = mainSids[cand.indexOf(target)];
                    if (msid > 0) {
                        setCurrentRole(target.fpid, msid);
                    } else {
                        toast("已切换：" + target.label);
                        refreshAllPages();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 当前选中账号（默认=工作区第一个）。 */
    private AccountStore.Account currentSelected() {
        if (selectedAccountId != null && accounts != null) {
            for (AccountStore.Account a : accounts) {
                if (selectedAccountId.equals(a.id) && a.token != null && !a.token.isEmpty()) return a;
            }
        }
        return firstWorkspace();
    }

    private void quickAction(String which) {
        switch (which) {
            case "daily": openDaily(firstWorkspace(), null); break;
            case "log": showRunLog(); break;
            case "tools": showTools(); break;
            case "wizard": showWizard(); break;
        }
    }

    private static int solidOf(String type) {
        switch (type) {
            case "ok": return UiStyle.OK_SOLID;
            case "run": return UiStyle.RUN_SOLID;
            case "wait": return UiStyle.WAIT_SOLID;
            case "err": return UiStyle.ERR_SOLID;
            default: return UiStyle.SWITCH_OFF;
        }
    }

    /** 账号今日状态（badge 类型 + 文案）。 */
    private String[] statusOf(AccountStore.Account a) {
        if (a == null || a.token == null || a.token.isEmpty()
                || a.fpid == null || a.fpid.isEmpty()) return new String[]{"err", "失败·token"};
        long sid = activeSid(a);
        int[] st = new LogStore(this).dayStats(a.fpid, sid);
        if (st[1] > 0) return new String[]{"err", "失败"};
        if (st[2] > 0) return new String[]{"run", "进行中"};
        if (st[3] > 0 && st[0] >= st[3]) return new String[]{"ok", "已完成"};
        if (st[0] > 0) return new String[]{"wait", "待领取"};
        return new String[]{"idle", "未开始"};
    }

    /** 当前角色对应的账号（无则回退当前选中账号）。 */
    private AccountStore.Account currentRoleAccount() {
        if (currentRoleKey != null && currentRoleKey.contains(":")) {
            String fpid = currentRoleKey.substring(0, currentRoleKey.indexOf(':'));
            if (accounts != null) {
                for (AccountStore.Account a : accounts) {
                    if (a != null && fpid.equals(a.fpid)) return a;
                }
            }
        }
        return currentSelected();
    }

    /** 当前角色 sid（无记录则回退 activeSid）。 */
    private long currentRoleSid() {
        if (currentRoleKey != null && currentRoleKey.contains(":")) {
            try {
                return Long.parseLong(currentRoleKey.substring(currentRoleKey.indexOf(':') + 1));
            } catch (Throwable ignored) {
            }
        }
        AccountStore.Account a = currentRoleAccount();
        return a == null ? 0 : activeSid(a);
    }

    /**
     * 启动对齐：把账号激活角色（activeRole）校准到全局当前角色。
     *
     * <p>历史版本「切换角色」只写 home:current_role 不写 activeRole，存量设备上两者可能
     * 不一致（功能开关/详情页兜底/常驻会话选角仍在旧角色）。启动时以 currentRoleKey 为准
     * 做一次对齐，保证所有消费方同源。仅对当前角色所属账号生效，不动其它账号。
     */
    private void alignActiveRoleWithCurrent() {
        if (currentRoleKey == null || !currentRoleKey.contains(":")) return;
        try {
            int idx = currentRoleKey.indexOf(':');
            String fpid = currentRoleKey.substring(0, idx);
            long sid = Long.parseLong(currentRoleKey.substring(idx + 1));
            if (fpid.isEmpty() || sid <= 0) return;
            if (RoleStore.activeRole(this, fpid) != sid) {
                RoleStore.setActiveRole(this, fpid, sid);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 记住当前角色并立即刷新全部页面。 */
    private void setCurrentRole(String fpid, long sid) {
        currentRoleKey = fpid + ":" + sid;
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("current_role", currentRoleKey).apply();
        // 关键：同步账号激活角色（RoleStore.activeRole）。activeRole 是功能开关读写
        // （fnOn/setFnOn 经 activeSid）、功能详情页 sid 兜底、多角色竞速会话的主角色指纹
        // 源（RoleSessionKeeper 竞速登录时 roleIdentitySeed 以 activeRole 判主角色）的
        // 事实源——若只改 currentRoleKey，切换后会出现"页面徽章跟新角色、配置/指纹跟旧角色"
        // 的分裂（真机事故：k888→k913 后兵营训练档位仍显示 k888 的 t3）。
        try {
            RoleStore.setActiveRole(this, fpid, sid);
        } catch (Throwable ignored) {
        }
        refreshAllPages();
        toast("当前角色已切换");
    }

    /** 分段控件选中态跟随 accView（修复切换后高亮不变化）。 */
    private void updateAccSeg() {
        if (accSeg == null) return;
        for (int i = 0; i < accSeg.getChildCount(); i++) {
            android.view.View c = accSeg.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            TextView t = (TextView) c;
            boolean sel = i == accView;
            t.setTextColor(sel ? UiStyle.WHITE : UiStyle.INK2);
            t.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setBackground(sel ? UiStyle.roundRect(UiStyle.BRAND, dp(11)) : null);
        }
    }

    /** 角色选择器：仅当前账号下的角色，当前项打勾（换账号走"切换账号"）。 */
    private void showRolePicker() {
        AccountStore.Account curAcc = currentRoleAccount();
        if (curAcc == null || curAcc.fpid == null) {
            toast("没有可用账号");
            return;
        }
        List<String> items = new ArrayList<>();
        final List<String[]> pairs = new ArrayList<>();   // {fpid, sid, 显示名}
        long curSid = currentRoleSid();
        List<RoleStore.Role> rs;
        try {
            rs = RoleStore.roles(this, curAcc.fpid);
        } catch (Throwable t) {
            rs = new ArrayList<>();
        }
        if (rs.isEmpty()) {
            toast(curAcc.label + " 暂无角色数据");
            return;
        }
        for (RoleStore.Role r : rs) {
            String rn = r.name == null || r.name.isEmpty()
                    ? "K" + r.serverid + "（未同步）" : r.name;
            String tag = r.serverid == curSid ? " ✓当前" : "";
            pairs.add(new String[]{curAcc.fpid, String.valueOf(r.serverid), rn});
            items.add(rn + " · K" + r.serverid + (r.enabled ? "" : " · 未入区") + tag);
        }
        if (items.isEmpty()) {
            toast("没有可用账号 —— 先导入");
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("切换当前角色")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String[] p2 = pairs.get(w);
                    try {
                        setCurrentRole(p2[0], Long.parseLong(p2[1]));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 游戏容器当前登录角色的 uid（live playerprefs 的 USER_SETTING_{uid} 键；读不到返回0）。 */
    private long liveOnlineUid() {
        try {
            java.io.File prefsDir = new java.io.File(
                    new java.io.File(engine.gameDataDir(), "shared_prefs"),
                    "com.gof.china.v2.playerprefs.xml");
            if (!prefsDir.isFile()) return 0;
            long len = prefsDir.length();
            if (len <= 0 || len > 1024L * 1024L) return 0;
            byte[] buf = new byte[(int) len];
            java.io.FileInputStream in = new java.io.FileInputStream(prefsDir);
            try {
                int n = in.read(buf);
                if (n <= 0) return 0;
                if (n < buf.length) buf = java.util.Arrays.copyOf(buf, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            String xml = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            // 键形如 name="USER_SETTING_295534397"（其余 USER_SETTING_xxx 均为角色设置键）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("USER_SETTING_([0-9]{6,})").matcher(xml);
            long uid = 0;
            while (m.find()) {
                try {
                    uid = Long.parseLong(m.group(1));
                } catch (Throwable ignored) {
                }
            }
            return uid;   // 取最后一个匹配（最新键）
        } catch (Throwable t) {
            return 0;
        }
    }

    private long activeSid(AccountStore.Account a) {
        long active = RoleStore.activeRole(this, a.fpid);
        if (active >= 0) return active;
        for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
            if (r.enabled) return r.serverid;
        }
        return 0;
    }

    private List<AccountStore.Account> workspaceAccounts() {
        List<AccountStore.Account> out = new ArrayList<>();
        if (accounts == null) return out;
        for (AccountStore.Account a : accounts) {
            if (a == null || a.token == null || a.token.isEmpty()
                    || a.fpid == null || a.fpid.isEmpty()) continue;
            boolean any = RoleStore.schedule(this, a.fpid).autoEnabled;
            if (!any) {
                for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                    if (r.enabled) {
                        any = true;
                        break;
                    }
                }
            }
            if (any) out.add(a);
        }
        return out;
    }

    private AccountStore.Account firstWorkspace() {
        List<AccountStore.Account> ws = workspaceAccounts();
        if (!ws.isEmpty()) return ws.get(0);
        if (accounts != null) {
            for (AccountStore.Account a : accounts) {
                if (a != null && a.token != null && !a.token.isEmpty()) return a;
            }
        }
        return null;
    }

    private void refreshToday() {
        if (accounts == null) return;
        AccountStore.Account sel = currentRoleAccount();
        selectedAccountId = sel == null ? null : sel.id;
        List<AccountStore.Account> ws = workspaceAccounts();
        int done = 0, err = 0, wait = 0, run = 0;
        for (AccountStore.Account a : ws) {
            String[] st = statusOf(a);
            switch (st[0]) {
                case "ok": done++; break;
                case "err": err++; break;
                case "wait": wait++; break;
                case "run": run++; break;
            }
        }
        // 环=当前账号游戏每日任务实时进度（同步即有，无需执行日常）
        int qTotal = 0, qDone = 0;
        com.carlos.vault.daily.QuestStore.State qs = sel == null ? null
                : com.carlos.vault.daily.QuestStore.load(this, sel.fpid, currentRoleSid());
        if (qs != null) {
            for (com.carlos.vault.daily.QuestCatalog.Group g : com.carlos.vault.daily.QuestCatalog.all()) {
                qTotal++;
                long groupProg = 0;
                boolean allDone = true;
                int maxTarget = 1;
                for (com.carlos.vault.daily.QuestCatalog.Tier t : g.tiers) {
                    Integer p = qs.progress.get(t.questId);
                    if (p != null && p > groupProg) groupProg = p;
                    if (t.target > maxTarget) maxTarget = t.target;
                    if (!qs.claimed.contains(t.questId)) allDone = false;
                }
                if (allDone || groupProg >= maxTarget) qDone++;
            }
        }
        // 进度表达规范：环=今日任务完成 q/q；活跃度=档位进度条（40/80/120/160/215/270/325，末端标下一档差多少）
        int integral = qs == null ? -1 : qs.integral;
        heroRing.setFraction(qTotal == 0 ? 0 : Math.min(1f, (float) qDone / qTotal));
        heroRing.setProgress(qDone, qTotal, "今日完成");
        actVal.setText(integral < 0 ? "-- / 325" : integral + " / 325");
        android.widget.LinearLayout.LayoutParams aflp =
                (android.widget.LinearLayout.LayoutParams) actFill.getLayoutParams();
        aflp.weight = integral <= 0 ? 0f : Math.min(1f, integral / 325f);
        actFill.setLayoutParams(aflp);
        if (integral < 0) actTier.setText("打开账号同步后显示档位");
        else if (integral >= 325) actTier.setText("已达最高档位 325");
        else {
            int next = 325;
            for (int t : new int[]{40, 80, 120, 160, 215, 270, 325}) {
                if (t > integral) { next = t; break; }
            }
            actTier.setText("下一档位 " + next + "（还差 " + (next - integral) + "）");
        }
        // Hero当前角色行（信息上移到 heroSub：本卡只显示 当前角色：角色名 + 在线状态）
        String crName = "";
        try {
            AccountStore.Account cra = currentRoleAccount();
            if (cra != null) {
                for (RoleStore.Role r : RoleStore.roles(this, cra.fpid)) {
                    if (r.serverid == currentRoleSid() && r.name != null && !r.name.isEmpty()) {
                        crName = r.name;
                        break;
                    }
                }
                if (crName.isEmpty()) crName = cra.label;
            }
        } catch (Throwable ignored) {
        }
        heroSub.setText("当前角色：" + (crName.isEmpty() ? "未选择" : crName)
                + " · K" + currentRoleSid());
        // 当前角色在线状态（区分口径）：协议常驻会话 online → 「日常在线」；容器游戏客户端
        // 正挂当前角色 → 「游戏在线」；其余按引擎会话态（重连中/被顶号暂停/需重导token/离线）。
        // 不再展示"其它角色在线"（旧版显示容器里正在玩的别的角色，与当前角色无关造成误导）。
        final AccountStore.Account onlineAcc = sel;
        final long curSidF = currentRoleSid();
        if (onlineAcc != null) {
            new Thread(() -> {
                String text;
                int color;
                com.carlos.vault.daily.RoleSessionKeeper.LiveSession cur = null;
                try {
                    for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                            : com.carlos.vault.daily.RoleSessionKeeper.get(HomeActivity.this)
                            .sessionsSnapshot()) {
                        if (onlineAcc.fpid != null && onlineAcc.fpid.equals(ls.fpid)
                                && ls.sid == curSidF) {
                            cur = ls;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 服务器模式：状态来自 vault-server 回传缓存（本地无会话；sid 对得上才显示在线）
                org.json.JSONObject srv = VaultSync.isServerMode(HomeActivity.this)
                        && onlineAcc.fpid != null
                        ? VaultSync.cachedStatusOf(HomeActivity.this, onlineAcc.fpid) : null;
                String srvSid = srv == null ? "" : srv.optString("sid", "");
                boolean srvRoleMatch = srv != null
                        && (srvSid.isEmpty() || parseSidSafe(srvSid) == curSidF);
                if (srv != null && srvRoleMatch) {
                    String st = srv.optString("state", "offline");
                    if (com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(st)) {
                        text = "● 日常在线";
                        color = UiStyle.ON_BRAND_OK;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_RECOVERING.equals(st)) {
                        text = "◌ 重连中";
                        color = UiStyle.ON_BRAND_WARN;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_TAKEOVER.equals(st)) {
                        text = "△ 被顶号暂停";
                        color = UiStyle.ON_BRAND_WARN;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_NEED_TOKEN.equals(st)) {
                        text = "⚠ 需重导token";
                        color = UiStyle.ON_BRAND_ERR;
                    } else {
                        text = "○ 离线";
                        color = UiStyle.ON_BRAND_IDLE;
                    }
                } else if (cur != null) {
                    String st = cur.engine.sessionState();
                    if (com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(st)) {
                        text = "● 日常在线";
                        color = UiStyle.ON_BRAND_OK;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_RECOVERING.equals(st)) {
                        text = "◌ 重连中";
                        color = UiStyle.ON_BRAND_WARN;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_TAKEOVER.equals(st)) {
                        text = "△ 被顶号暂停";
                        color = UiStyle.ON_BRAND_WARN;
                    } else if (com.carlos.vault.wjdr.WjdrEngine.STATE_NEED_TOKEN.equals(st)) {
                        text = "⚠ 需重导token";
                        color = UiStyle.ON_BRAND_ERR;
                    } else {
                        text = "○ 离线";
                        color = UiStyle.ON_BRAND_IDLE;
                    }
                } else if (accountOnline(onlineAcc) && roleUidOf(onlineAcc.fpid, curSidF) != 0
                        && liveOnlineUid() == roleUidOf(onlineAcc.fpid, curSidF)) {
                    // 容器游戏客户端正挂着当前角色
                    text = "● 游戏在线";
                    color = UiStyle.ON_BRAND_OK;
                } else {
                    text = "○ 离线";
                    color = UiStyle.ON_BRAND_IDLE;
                }
                final String fText = text;
                final int fColor = color;
                runOnUiThread(() -> {
                    onlineDot.setText(fText);
                    onlineDot.setTextColor(fColor);
                });
                refreshKeepIndicators(onlineAcc);
            }, "online-probe").start();
        }
        String nm = sel == null || sel.label == null ? "" : sel.label;
        String crName2 = "";
        try {
            for (RoleStore.Role r : RoleStore.roles(this, sel.fpid)) {
                if (r.serverid == currentRoleSid() && r.name != null && !r.name.isEmpty()) {
                    crName2 = r.name;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        // 自动执行状态：开=显示下次定时时刻；关=提示点击开启
        AccountStore.Account cra2 = sel;
        boolean roleAuto = cra2 != null
                && RoleStore.schedule(this, cra2.fpid).autoEnabled;
        if (roleAuto) {
            long nxt = cra2 == null ? 0 : RoleStore.schedule(this, cra2.fpid).nextRunAt;
            String nxtTxt = nxt <= 0 ? "即将" : new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.CHINA).format(new java.util.Date(nxt));
            autoStateBtn.setText("⚡ 自动执行已开启 · 下次 " + nxtTxt + " ▾");
        } else {
            autoStateBtn.setText("自动执行已关闭 · 点此开启");
        }
        // 顶栏 caption：日期 + 自动日常状态
        boolean anyAuto = false;
        for (AccountStore.Account a : ws) {
            if (RoleStore.schedule(this, a.fpid).autoEnabled) {
                anyAuto = true;
                break;
            }
        }
        topCaption.setText(new java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.CHINA)
                .format(new java.util.Date()) + " · 自动日常已" + (anyAuto ? "开启" : "关闭"));
        renderLimitWindows(sel);
        renderRallies(sel);
        autoSyncAccount(sel, false);
    }

    /** 展开功能卡时的状态刷新节流（同功能 60 秒内不重复拉；与详情页共用节流表）。 */
    private static final java.util.Map<String, Long> FN_EXPAND_REFRESH = new java.util.concurrent.ConcurrentHashMap<>();

    private void refreshFnOnExpand(final String fpid, final long sid, final String fnId) {
        try {
            String key = fpid + ":" + sid + ":" + fnId;
            long now = System.currentTimeMillis();
            Long last = FN_EXPAND_REFRESH.get(key);
            if (last != null && now - last < 60_000L) return;
            FN_EXPAND_REFRESH.put(key, now);
            new Thread(() -> {
                com.carlos.vault.daily.RoleSessionKeeper.LiveSession live =
                        com.carlos.vault.daily.DailyRunner.liveOf(this, fpid, sid);
                android.util.Log.i("FnRefresh", "expand live=" + (live == null
                        ? "null" : live.fpid + ":" + live.sid + " " + live.engine.sessionState()));
                if (live == null) return;
                boolean ok = com.carlos.vault.daily.DailyRunner.refreshFnState(
                        this, live.engine, fpid, sid, fnId);
                if (!ok) return;
                runOnUiThread(() -> {
                    // 仍展开该功能才重渲染（用户可能已切走）
                    if (fnId.equals(expandedFn) && fpid.equals(taskAccount == null ? "" : taskAccount.fpid)) {
                        refreshTasks();
                    }
                });
            }, "fn-expand-refresh").start();
        } catch (Throwable ignored) {
        }
    }

    /** 角色区服对应的游戏 uid（找不到返回 0）。 */
    private long roleUidOf(String fpid, long sid) {
        try {
            for (RoleStore.Role r : RoleStore.roles(this, fpid)) {
                if (r.serverid == sid) return r.uid;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 打开页面自动后台同步账号（只收数据不跑任务），同一账号5分钟内不重复。silent=不在Hero显示提示。 */
    private void autoSyncAccount(AccountStore.Account sel, boolean silent) {
        // 服务器模式：数据采集在服务器侧完成，拉回传状态+采集数据并回写本地
        // （放在 syncing/防抖之前——本地 syncOnly 卡住不影响服务器数据刷新）
        if (sel != null && VaultSync.isServerMode(this)) {
            VaultSync.fetchState(this);
            return;
        }
        if (sel == null || sel.token == null || sel.token.isEmpty() || syncing) return;
        long now = System.currentTimeMillis();
        String key = "last_sync:" + sel.fpid;
        if (now - getSharedPreferences("home", MODE_PRIVATE).getLong(key, 0) < 5 * 60 * 1000L)
            return;
        syncing = true;
        if (!silent) heroSub.setText("正在同步 " + sel.label + " 最新状态…");
        final AccountStore.Account acc = sel;
        new Thread(() -> {
            String err = DailyRunner.syncOnly(this, acc.fpid, acc.token, currentRoleSid());
            runOnUiThread(() -> {
                syncing = false;
                getSharedPreferences("home", MODE_PRIVATE).edit()
                        .putLong(key, now).apply();
                if (err != null && !silent) {
                    heroSub.setText(err.startsWith("游戏在线") ? err : "同步失败：" + err);
                }
                refreshAllPages();
            });
        }, "vault-sync").start();
    }

    /** 代办事项：巨熊行动预约（首行）+ 仓库补给 + 游戏活动日历全量清单。 */
    private void renderLimitWindows(AccountStore.Account cur) {
        limitBox.removeAllViews();
        // 游戏同款：一张分组卡 + 行间细分隔线，左圆图标，右侧时间/状态两行
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        // 巨熊行动：纯服务器数据；显示预约的时间（reserve），只显示两天之内，过期即清
        String bearStatus = "";
        boolean bearReserved = false;
        long bearReserve = 0, massStart = 0, massEnd = 0, coolEnd = 0;
        if (cur != null) {
            try {
                String raw = getSharedPreferences("daily_state", MODE_PRIVATE)
                        .getString("mod:" + cur.fpid + ":" + currentRoleSid() + ":bear", null);
                if (raw != null) {
                    org.json.JSONObject o = new org.json.JSONObject(raw);
                    String t = o.optString("trap", "");
                    if (!t.isEmpty()) bearStatus = t;
                    bearReserved = t.contains("已预约");
                    bearReserve = o.optLong("reserve", 0);
                    massStart = o.optLong("start", 0);
                    massEnd = o.optLong("end", 0);
                    coolEnd = o.optLong("cool", 0);
                }
            } catch (Exception ignored) {
            }
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        java.text.SimpleDateFormat bh = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        java.text.SimpleDateFormat bd = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        boolean bearRunning = massEnd > nowSec && (massStart == 0 || massStart <= nowSec);
        boolean reserveValid = bearReserve > 1_700_000_000L && bearReserve < 2_000_000_000L;
        boolean reserveSoon = reserveValid && bearReserve > nowSec
                && bearReserve - nowSec <= 2 * 86400L;   // 两天之内的预约才显示
        if (bearRunning) {
            todoRow(card, "🐻", "巨熊行动", "", "进行中", UiStyle.RUN_INK,
                    bh.format(new java.util.Date(massEnd * 1000)) + " 结束",
                    cur == null ? null : () -> openDaily(cur, "bear"));
        } else if (reserveSoon) {
            todoRow(card, "🐻", "巨熊行动", "", "已预约", UiStyle.WARN_INK,
                    bd.format(new java.util.Date(bearReserve * 1000)) + " 开打",
                    cur == null ? null : () -> openDaily(cur, "bear"));
        } else if (coolEnd > nowSec && coolEnd - nowSec <= 2 * 86400L) {
            todoRow(card, "🐻", "巨熊行动", "", "冷却中", UiStyle.INK2,
                    "冷却至 " + bh.format(new java.util.Date(coolEnd * 1000)),
                    cur == null ? null : () -> openDaily(cur, "bear"));
        } else if (!bearReserved && coolEnd <= nowSec) {
            // 未预约提醒（可操作项保留）；已过期/超两天的预约不显示
            todoRow(card, "🐻", "巨熊行动", "", "未预约", UiStyle.IDLE_INK,
                    "建议提前预约", cur == null ? null : () -> openDaily(cur, "bear"));
        }

        // 兵工厂争夺战：预约型活动（场次时间每盟不同）；档期进行中才显示，档期结束时间来自日历
        if (cur != null) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(
                        getSharedPreferences("daily_state", MODE_PRIVATE)
                                .getString("todo:" + cur.fpid + ":" + currentRoleSid(), "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject t = arr.optJSONObject(i);
                    if (t == null || t.optLong("family", 0) != 2401) continue;
                    long b = t.optLong("begin", 0), e = t.optLong("end", 0);
                    if (b <= nowSec && e > nowSec && e - nowSec <= 2 * 86400L) {
                        String until = e - nowSec > 48 * 3600L
                                ? new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
                                        .format(new java.util.Date(e * 1000)) + " 结束"
                                : bh.format(new java.util.Date(e * 1000)) + " 结束";
                        todoRow(card, "⚔", "兵工厂争夺战", "", "进行中", UiStyle.RUN_INK, until, null);
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (cur == null) {
            limitBox.addView(card);
            return;
        }
        long sid = currentRoleSid();
        android.content.SharedPreferences sp = getSharedPreferences("daily_state", MODE_PRIVATE);
        // 仓库补给（真实下次可领时间）
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    sp.getString("mod:" + cur.fpid + ":" + sid + ":supply", "{}"));
            String online = o.optString("online", "");
            String spec = o.optString("spec", "");
            // 时效防御：快照里的"下一次可领"若已是过去时刻，按"到期待领"显示，
            // 不再原样展示过期时间（旧版会把两天前的时刻当"未来"显示）。
            if (online.startsWith("下一次可领")) {
                try {
                    java.text.SimpleDateFormat sf = new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
                    java.util.Date t = sf.parse(online.substring("下一次可领".length()).trim());
                    if (t != null && t.getTime() < System.currentTimeMillis()) {
                        online = "到期待领（打开页面同步确认）";
                    }
                } catch (Throwable ignored) {
                }
            }
            String supStatus = !online.isEmpty() ? online
                    : (!spec.isEmpty() ? spec : "晚间 18:00 开启");
            todoRow(card, "⚡", "仓库补给", supStatus, "待领取", UiStyle.WAIT_INK, "",
                    () -> openDaily(cur, "supply"));
        } catch (Exception ignored) {
        }
        // 燃霜矿区：仅"已预约今日场次"才进代办（游戏同款），显示预约的场次时间
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    sp.getString("mod:" + cur.fpid + ":" + sid + ":frostfire", "{}"));
            long idx = o.optLong("index", 0);
            long sts = o.optLong("ts", 0);
            java.text.SimpleDateFormat dayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.CHINA);
            boolean reservedToday = idx >= 1 && idx <= com.carlos.vault.daily.DailyRunner.FROSTFIRE_SLOTS.length
                    && sts > 0 && dayFmt.format(new java.util.Date(sts * 1000))
                            .equals(dayFmt.format(new java.util.Date()));
            if (reservedToday) {
                String slot = com.carlos.vault.daily.DailyRunner.FROSTFIRE_SLOTS[(int) idx - 1];
                String[] hm2 = slot.split(":");
                java.util.Calendar slotStart = java.util.Calendar.getInstance();
                slotStart.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(hm2[0]));
                slotStart.set(java.util.Calendar.MINUTE, Integer.parseInt(hm2[1]));
                slotStart.set(java.util.Calendar.SECOND, 0);
                long sMs = slotStart.getTimeInMillis();
                long nowMs = System.currentTimeMillis();
                if (nowMs < sMs) {
                    todoRow(card, "⛏", "燃霜矿区", "", "已预约", UiStyle.WARN_INK,
                            "今日 " + slot, () -> openDaily(cur, "act_frostfire"));
                } else if (nowMs < sMs + 30 * 60000L) {
                    todoRow(card, "⛏", "燃霜矿区", "", "进行中", UiStyle.RUN_INK,
                            slot + " 场", () -> openDaily(cur, "act_frostfire"));
                }   // 今日场次已结束：不显示
            }
        } catch (Exception ignored) {
        }
        // 游戏代办清单（活动日历全量）：未来按开始时间排序；不确定的活动名（活动 xxxx）不显示
        java.util.List<org.json.JSONObject> items = new java.util.ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    sp.getString("todo:" + cur.fpid + ":" + sid, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject t = arr.optJSONObject(i);
                if (t != null) items.add(t);
            }
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis() / 1000L;
        items.sort((x, y) -> Long.compare(x.optLong("begin", 0), y.optLong("begin", 0)));
        int shown = 0;
        for (org.json.JSONObject t : items) {
            if (shown >= 6) break;
            long bg = t.optLong("begin", 0);
            if (bg <= now) continue;   // 只显示即将开始的（游戏代办同款）
            String fn = t.optString("fn", "");
            String name = t.optString("name", "");
            String text = t.optString("text", "");
            if (name.isEmpty() || text.isEmpty()) continue;
            if (name.startsWith("活动 ")) continue;          // 名字没对上号的不确定活动：不显示
            if (t.optLong("family", 0) == 2417) continue;    // 燃霜矿区走预约行，不走日历行
            if (bg - now > 2 * 86400L) continue;             // 只显示两天之内的事项
            todoRow(card, "🎯", name, "", text, UiStyle.INK, "未开始",
                    fn.isEmpty() ? null : () -> openDaily(cur, fn));
            shown++;
        }
        if (card.getChildCount() == 0) {
            TextView t = new TextView(this);
            t.setText("暂无代办（打开页面自动同步）");
            t.setTextSize(11.5f);
            t.setTextColor(UiStyle.INK2);
            t.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.addView(t);
        }
        limitBox.addView(card);
    }

    /** 游戏代办同款行：圆形图标底 + 名称(+灰副题) + 右侧两行(状态色/灰副行)，行间细分隔线。 */
    private void todoRow(LinearLayout card, String emoji, String title, String sub,
                         String r1, int r1Color, String r2, Runnable onClick) {
        if (card.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(UiStyle.LINE);
            card.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));

        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(16);
        icon.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ig = new android.graphics.drawable.GradientDrawable();
        ig.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ig.setColor(UiStyle.BRAND_50);
        icon.setBackground(ig);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
        ilp.rightMargin = dp(12);
        row.addView(icon, ilp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(title);
        n.setTextSize(13.5f);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(UiStyle.INK);
        mid.addView(n);
        if (!sub.isEmpty()) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(11);
            s2.setTextColor(UiStyle.INK2);
            s2.setPadding(0, dp(3), 0, 0);
            mid.addView(s2);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT);
        TextView ra = new TextView(this);
        ra.setText(r1);
        ra.setTextSize(12);
        ra.setTypeface(Typeface.DEFAULT_BOLD);
        ra.setTextColor(r1Color);
        right.addView(ra);
        if (!r2.isEmpty()) {
            TextView rb = new TextView(this);
            rb.setText(r2);
            rb.setTextSize(10.5f);
            rb.setTextColor(UiStyle.INK2);
            rb.setPadding(0, dp(2), 0, 0);
            right.addView(rb);
        }
        row.addView(right);
        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        card.addView(row);
    }

    private View limitRow(String title, String status, String right, Runnable onClick) {
        LinearLayout line = UiStyle.outlineCard(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(title);
        n.setTextSize(13);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(UiStyle.INK);
        mid.addView(n);
        if (!status.isEmpty()) {
            TextView s2 = new TextView(this);
            s2.setText(status);
            s2.setTextSize(11.5f);
            s2.setTextColor(UiStyle.INK2);
            mid.addView(s2);
        }
        line.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!right.isEmpty()) line.addView(UiStyle.badge(this, "warn", right));
        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        return line;
    }

    /** 联盟大厅：正在集结的队列（运行时采集存 daily_state rallies:fpid:sid）。 */
    private void renderRallies(AccountStore.Account cur) {
        rallyBox.removeAllViews();
        if (cur == null) return;
        String raw = getSharedPreferences("daily_state", MODE_PRIVATE)
                .getString("rallies:" + cur.fpid + ":" + currentRoleSid(), null);
        int shown = 0;
        if (raw != null) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                long nowSec = System.currentTimeMillis() / 1000L;
                int nowMin = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) * 60
                        + java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);
                for (int i = 0; i < arr.length() && shown < 6; i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        long e = jLong(o, "endtime", "end_time", "arrivetime", "arrive_time",
                                "starttime", "start_time");
                        boolean eOk = e > 1_700_000_000L && e < 2_000_000_000L;
                        if (eOk && nowSec > e + 300) continue;   // 已结束的集结不显示
                        rallyBox.addView(rallyCard(o));
                        shown++;
                    } else {
                        String line = arr.optString(i, "");
                        if (line.isEmpty()) continue;
                        java.util.regex.Matcher mh = java.util.regex.Pattern
                                .compile("([0-9]{1,2}):([0-9]{2})").matcher(line);
                        if (mh.find()) {
                            int lm = Integer.parseInt(mh.group(1)) * 60
                                    + Integer.parseInt(mh.group(2));
                            if (lm < nowMin - 10) continue;   // 过期残留
                        }
                        rallyBox.addView(limitRow("集结 · " + line, "", "进行中",
                                () -> openDaily(cur, "behemoth")));
                        shown++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText("当前没有进行中的集结（打开页面自动更新）");
            t.setTextSize(11.5f);
            t.setTextColor(UiStyle.INK2);
            t.setPadding(dp(2), dp(4), 0, 0);
            rallyBox.addView(t);
        }
    }

    /** 集结任务态卡片：折叠=发起人+状态徽章+剩余；展开=成员/兵力/目标/时间。 */
    private View rallyCard(org.json.JSONObject o) {
        long now = System.currentTimeMillis() / 1000L;
        long start = jLong(o, "starttime", "start_time", "departtime", "depart_time");
        long end = jLong(o, "endtime", "end_time", "arrivetime", "arrive_time");
        boolean startOk = start > 1_700_000_000L && start < 2_000_000_000L;
        boolean endOk = end > 1_700_000_000L && end < 2_000_000_000L;
        long anchor = 0;
        if (startOk && now < start) anchor = start;
        else if (endOk && now < end) anchor = end;
        boolean marching = startOk && now >= start;
        long lv = o.optLong("lv", 0);
        long power = o.optLong("power", 0);
        long state = o.optLong("state", 0);
        int members = 0, memberMax = 0;
        long soldiers = 0, soldierMax = 0;
        org.json.JSONObject mb = o.optJSONObject("mass_boss");
        if (mb == null) mb = o.optJSONObject("mass_mercenary_player_monster");
        if (mb == null) mb = o.optJSONObject("mass_alliance_boss");
        if (mb != null) {
            org.json.JSONArray us = mb.optJSONArray("uids");
            if (us != null) members = us.length();
            memberMax = (int) mb.optLong("max", 0);
            soldiers = mb.optLong("soldier_count", 0);
            soldierMax = mb.optLong("soldier_max", 0);
        }
        String owner = jStr(o, "name", "nickname", "ownername", "owner_name");
        String title = (owner.isEmpty() ? "盟友集结" : owner) + (lv > 0 ? " · Lv" + lv : "");

        LinearLayout card = UiStyle.outlineCard(this);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = new TextView(this);
        ic.setText("⚔");
        ic.setTextSize(14);
        row.addView(ic);
        TextView nm = new TextView(this);
        nm.setText(title);
        nm.setTextSize(13);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(UiStyle.INK);
        nm.setPadding(dp(8), 0, dp(6), 0);
        row.addView(nm);
        TextView sum = new TextView(this);
        StringBuilder sbt = new StringBuilder();
        if (memberMax > 0) sbt.append(members).append("/").append(memberMax).append("人");
        if (soldiers > 0) sbt.append(sbt.length() > 0 ? " · " : "").append(fmtWan(soldiers)).append("兵");
        if (sbt.length() == 0) sbt.append(marching ? "行军" : "集结中");
        sum.setText(sbt.toString());
        sum.setTextSize(11);
        sum.setTextColor(UiStyle.INK2);
        row.addView(sum);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(UiStyle.badge(this, marching ? "wait" : "run", marching ? "行军中" : "集结中"));
        if (anchor > 0) {
            TextView tm = new TextView(this);
            tm.setText("剩 " + fmtRemain(anchor - now));
            tm.setTextSize(11);
            tm.setTextColor(UiStyle.INK2);
            tm.setPadding(dp(8), 0, 0, 0);
            row.addView(tm);
        }
        card.addView(row);

        // 展开区
        LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        det.setPadding(dp(4), dp(10), dp(4), dp(2));
        addDetLine(det, "成员", memberMax > 0 ? members + "/" + memberMax + " 人" : "—");
        addDetLine(det, "兵力", soldiers > 0
                ? fmtWan(soldiers) + (soldierMax > 0 ? " / " + fmtWan(soldierMax) : "")
                + (soldierMax > 0 && soldiers >= soldierMax ? "（已满员）" : "")
                : "—");
        addDetLine(det, "目标", lv > 0 ? "Lv" + lv + " 巨兽" + (power > 0 ? " · 战力 " + fmtWan(power) : "") : "集结目标");
        java.text.SimpleDateFormat hf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        StringBuilder st = new StringBuilder(marching ? "行军中" : "集结中");
        if (state > 0) st.append("（状态").append(state).append("）");
        if (startOk && !marching) st.append(" · ").append(hf.format(new java.util.Date(start * 1000))).append(" 发车");
        if (endOk) st.append(" · ").append(hf.format(new java.util.Date(end * 1000))).append(marching ? " 到达" : " 截止");
        addDetLine(det, "状态", st.toString());
        addDetLine(det, "英雄/兵种", "游戏推送未含明细，随协议补全");
        det.setVisibility(View.GONE);
        final LinearLayout fdet = det;
        UiStyle.press(row);
        row.setOnClickListener(v -> fdet.setVisibility(
                fdet.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        card.addView(det);
        return card;
    }

    private void addDetLine(LinearLayout det, String k, String v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextSize(11);
        kt.setTextColor(UiStyle.INK2);
        kt.setPadding(0, 0, dp(10), 0);
        l.addView(kt, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextSize(11.5f);
        vt.setTextColor(UiStyle.INK);
        l.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        det.addView(l);
    }

    private static String fmtWan(long n) {
        if (n >= 10000) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000f);
        return String.valueOf(n);
    }

    /** 兵力三段比例条（盾蓝/矛青/弓橙）。 */
    private View troopBar(long[] t) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bp.topMargin = dp(6);
        bar.setLayoutParams(bp);
        int[] colors = {UiStyle.BRAND, UiStyle.RUN_SOLID, UiStyle.WARN_SOLID};
        for (int i = 0; i < 3; i++) {
            View seg = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(colors[i]);
            g.setCornerRadius(dp(2));
            seg.setBackground(g);
            long w = Math.max(1, t[i]);
            bar.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w));
        }
        return bar;
    }

    private static String pct(long v, long sum) {
        return sum <= 0 ? "0%" : Math.round(v * 100d / sum) + "%";
    }

    private static String fmtRemain(long sec) {
        if (sec <= 0) return "即将";
        if (sec < 3600) return (sec / 60) + "分";
        if (sec < 86400) return String.format(java.util.Locale.CHINA, "%.1f时", sec / 3600f);
        return (sec / 86400) + "天";
    }

    private static long jLong(org.json.JSONObject o, String... keys) {
        for (String k : keys) {
            long v = o.optLong(k, -1);
            if (v > 0) return v;
        }
        return 0;
    }

    private static String jStr(org.json.JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    /** 递归扫描兵种数量：key 含 shield/spear/bow|archer。 */
    private static long[] scanTroops(Object v, int depth) {
        long[] out = new long[3];
        scanTroops(v, depth, out);
        return out;
    }

    private static void scanTroops(Object v, int depth, long[] out) {
        if (depth > 4 || v == null) return;
        if (v instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) v;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next().toLowerCase();
                long n = o.optLong(k, -1);
                if (n >= 0 && k.length() < 20) {
                    if (k.contains("shield")) out[0] += n;
                    else if (k.contains("spear")) out[1] += n;
                    else if (k.contains("bow") || k.contains("archer")) out[2] += n;
                }
            }
            it = o.keys();
            while (it.hasNext()) scanTroops(o.opt(it.next()), depth + 1, out);
        } else if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            for (int i = 0; i < a.length(); i++) scanTroops(a.opt(i), depth + 1, out);
        }
    }

    /** 递归找英雄名：key 含 hero 的对象/字符串。 */
    private static String scanHero(Object v, int depth) {
        if (depth > 3 || v == null) return "";
        if (v instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) v;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String kl = k.toLowerCase();
                if (kl.contains("hero")) {
                    Object hv = o.opt(k);
                    if (hv instanceof String && !((String) hv).isEmpty()) {
                        String s = (String) hv;
                        if (s.length() < 24 && !s.matches("-?\\d+")) return s;
                    }
                    String nested = scanHero(hv, depth + 1);
                    if (!nested.isEmpty()) return nested;
                }
            }
            it = o.keys();
            while (it.hasNext()) {
                String nested = scanHero(o.opt(it.next()), depth + 1);
                if (!nested.isEmpty()) return nested;
            }
        } else if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                String nested = scanHero(a.opt(i), depth + 1);
                if (!nested.isEmpty()) return nested;
            }
        }
        return "";
    }

    // ================= Tab2 · 账号 =================

    private void buildAccountPage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(16));
        pages[1].addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：账号 + N 个账号 · 工作区 M
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("账号");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        accountCap = new TextView(this);
        accountCap.setTextSize(11.5f);
        accountCap.setTextColor(UiStyle.INK2);
        tb.addView(accountCap);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        accSeg = UiStyle.segmented(this,
                new String[]{"列表", "卡片"}, Math.min(accView, 1),
                idx -> {
                    accView = idx;
                    refreshAccounts(true);
                });
        top.addView(accSeg);
        TextView sort = new TextView(this);
        sort.setText("⇅");
        sort.setTextSize(18);
        sort.setTextColor(UiStyle.INK2);
        sort.setGravity(Gravity.CENTER);
        GradientDrawable sobg = new GradientDrawable();
        sobg.setColor(UiStyle.WHITE);
        sobg.setCornerRadius(dp(12));
        sobg.setStroke(dp(1), UiStyle.LINE);
        sort.setBackground(sobg);
        sort.setPadding(dp(10), dp(9), dp(10), dp(9));
        UiStyle.press(sort);
        sort.setOnClickListener(v -> openOld());
        top.addView(sort);
        box.addView(top);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);
        top.setLayoutParams(tlp);

        searchInput = new EditText(this);
        searchInput.setHint("搜索账号名 / UID / 备注");
        searchInput.setTextSize(13.5f);
        searchInput.setTextColor(UiStyle.INK);
        searchInput.setHintTextColor(UiStyle.INK2);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(UiStyle.WHITE);
        sg.setCornerRadius(dp(12));
        sg.setStroke(dp(1), UiStyle.LINE);
        searchInput.setBackground(sg);
        searchInput.setPadding(dp(12), dp(11), dp(12), dp(11));
        searchInput.setMinHeight(dp(44));
        box.addView(searchInput);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(android.text.Editable s) {
                refreshAccounts();
            }
        });

        HorizontalScrollView fs = new HorizontalScrollView(this);
        fs.setHorizontalScrollBarEnabled(false);
        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setPadding(0, dp(8), 0, dp(4));
        fs.addView(frow);
        box.addView(fs);
        String[] labels = {"全部", "工作区", "异常", "未跑"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView c = UiStyle.chipGray(this, labels[i], i == 0);
            UiStyle.press(c);
            c.setOnClickListener(v -> {
                accountFilter = idx;
                refreshAccounts();
            });
            filterChips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            frow.addView(c, lp);
        }

        ScrollView sc = new ScrollView(this);
        accountList = new LinearLayout(this);
        accountList.setOrientation(LinearLayout.VERTICAL);
        sc.addView(accountList);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // FAB ＋（新增；长按=批量导入）
        FrameLayout fabWrap = new FrameLayout(this);
        TextView fab = new TextView(this);
        fab.setText("＋");
        fab.setTextSize(24);
        fab.setTextColor(UiStyle.WHITE);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(UiStyle.BRAND);
        fg.setCornerRadius(dp(28));
        fab.setBackground(fg);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.RIGHT | Gravity.BOTTOM);
        flp.rightMargin = dp(18);
        flp.bottomMargin = dp(18);
        fabWrap.addView(fab, flp);
        UiStyle.press(fab);
        fab.setOnClickListener(v -> showCreateEntry());
        fab.setOnLongClickListener(v -> {
            showBatchImport();
            return true;
        });
        pages[1].addView(fabWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 角色行（账号卡展开后）：游戏角色名 · K区服 · 今日状态 · 上次 · ▶跑日常。 */
    /** 跑单个角色：把激活角色对齐到它（数据写读同源）再走执行引擎。 */
    private void runRole(final AccountStore.Account a, final long sid) {
        if (RUN.running) {
            showRunPanel();
            return;
        }
        RoleStore.setActiveRole(this, a.fpid, sid);
        List<AccountStore.Account> one = new ArrayList<>();
        one.add(a);
        runList(one);
    }

    /** 工作区角色独立入口卡（原版"纳入工作区后可独立上角色"）。 */
    /** 矩阵卡片墙：每格一个角色，颜色=今日状态，点格出角色菜单。 */

    /**
     * 卡片视图：3 列<b>账号图标墙</b>（原型里的「卡片」形式）—— 认号快。
     * 头像 = 无尽冬日图标，下方名字，再下方「登录方式 · 上次登录」。单击 = 启动游戏。
     */
    private void renderAccountWall(List<AccountStore.Account> shown,
                                   java.util.Map<String, Boolean> online) {
        LinearLayout row = null;
        int per = 3;
        for (int i = 0; i < shown.size(); i++) {
            if (i % per == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(4), 0, 0);
                accountList.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final AccountStore.Account a = shown.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(3), dp(6), dp(3), dp(12));

            cell.addView(avatarView(84, a.label, a.id));

            TextView nm = new TextView(this);
            nm.setText(a.label);
            nm.setTextSize(12);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(UiStyle.INK);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(7), 0, 0);
            cell.addView(nm);

            TextView sub = new TextView(this);
            boolean isToken = a.token != null && !a.token.isEmpty();
            sub.setText((isToken ? "token" : "原生") + " · " + shortTime(a.lastLogin));
            sub.setTextSize(9.5f);
            sub.setTextColor(UiStyle.INK2);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(1);
            cell.addView(sub);

            UiStyle.press(cell);
            cell.setOnClickListener(v -> doSwitch(a));
            row.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** 卡片墙（旧版真实样式·账号级）：2列边框卡，游戏图标+备注名；下行左=登录方式(原生/token)、右=上次登录时间。 */
    private void renderRoleMatrix(List<AccountStore.Account> shown,
                                  java.util.Map<String, List<RoleStore.Role>> rolesMap,
                                  java.util.Map<String, int[]> roleStats,
                                  java.util.Map<String, Long> roleLast) {
        LinearLayout row = null;
        int per = 2;
        for (int i2 = 0; i2 < shown.size(); i2++) {
            if (i2 % per == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                accountList.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final AccountStore.Account a = shown.get(i2);
            long lastTs = getSharedPreferences("daily_sched", MODE_PRIVATE)
                    .getLong("last:" + a.fpid, 0);
            List<RoleStore.Role> rs = rolesMap.get(a.id);
            if (rs != null) {
                for (RoleStore.Role r : rs) {
                    Long lt = roleLast.get(a.fpid + ":" + r.serverid);
                    if (lt != null && lt > lastTs) lastTs = lt;
                }
            }
            boolean isToken = a.token != null && !a.token.isEmpty();

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(UiStyle.WHITE);
            cg.setCornerRadius(dp(14));
            cg.setStroke(dp(1), UiStyle.LINE);
            card.setBackground(cg);
            card.setElevation(dp(1));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = new TextView(this);
            icon.setText("🏔");
            icon.setTextSize(20);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable ig = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{UiStyle.BRAND, UiStyle.BRAND_DEEP});
            ig.setCornerRadius(dp(10));
            icon.setBackground(ig);
            top.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nlp.leftMargin = dp(10);
            TextView name = new TextView(this);
            String nm = a.label == null || a.label.isEmpty() ? "?" : a.label;
            name.setText(nm);
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(UiStyle.INK);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, nlp);
            card.addView(top);

            LinearLayout bottom = new LinearLayout(this);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(8), 0, 0);
            TextView login = new TextView(this);
            login.setText(isToken ? "token" : "原生");
            login.setTextSize(11);
            login.setTypeface(Typeface.DEFAULT_BOLD);
            login.setTextColor(isToken ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            login.setBackground(UiStyle.roundRect(isToken ? UiStyle.BRAND_50 : UiStyle.IDLE_BG, dp(8)));
            login.setPadding(dp(8), dp(3), dp(8), dp(3));
            bottom.addView(login);
            bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView last = new TextView(this);
            last.setText(lastTs <= 0 ? "从未登录"
                    : "上次 " + new java.text.SimpleDateFormat("MM-dd HH:mm",
                            java.util.Locale.CHINA).format(new java.util.Date(lastTs)));
            last.setTextSize(11);
            last.setTextColor(UiStyle.INK2);
            bottom.addView(last);
            card.addView(bottom);

            UiStyle.press(card);
            card.setOnClickListener(v -> accountMenu(a));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i2 % per != per - 1) clp.rightMargin = dp(10);
            ((LinearLayout.LayoutParams) clp).bottomMargin = dp(10);
            row.addView(card, clp);
        }
        if (shown.isEmpty()) {
            accountList.addView(emptyTip("没有匹配的账号"));
        }
    }

    /** 角色操作菜单（矩阵格子/角色行用）。 */
    private void roleMenu(final AccountStore.Account a, final RoleStore.Role r) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle((r.name == null || r.name.isEmpty() ? "K" + r.serverid + "（未同步）" : r.name) + " · K" + r.serverid)
                .setItems(new String[]{"▶ 跑日常（该角色）", "📋 日常详情", "🖥 启动游戏（整个账号）",
                        r.enabled ? "移出工作区" : "纳入工作区"}, (d, w) -> {
                    switch (w) {
                        case 0: runRole(a, r.serverid); break;
                        case 1: openFnDetail(a, "daily_mission", r.serverid); break;
                        case 2: doSwitch(a); break;
                        case 3:
                            RoleStore.setEnabled(this, a.fpid, r.serverid, !r.enabled);
                            refreshAccounts();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void accountMenu(final AccountStore.Account a) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(a.label)
                .setItems(new String[]{"🎁 每日任务（进度/领取）", "▶ 启动游戏", "👥 角色管理（旧号库）", "⇅ 排序（旧视图）"}, (d, w) -> {
                    switch (w) {
                        case 0: openDaily(a, null); break;
                        case 1: doSwitch(a); break;
                        case 2: openOld(); break;
                        case 3: openOld(); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void accountDetail(AccountStore.Account a) {
        String[] s = statusOf(a);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(a.label)
                .setMessage("今日状态：" + s[1] + "\n点击「日常」查看该号的任务进度与配置")
                .setPositiveButton("日常", (d, w) -> openDaily(a, null))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void refreshAccounts() {
        refreshAccounts(false);
    }

    /** 账号页刷新：状态计算（LogStore/角色表读取）放后台线程，主线程只建视图；
     *  showMask=切页时先盖半透明遮罩+加载中，算完即撤（引擎同步抢CPU时切页不再卡数秒）。 */
    private void refreshAccounts(final boolean showMask) {
        if (accountList == null || accounts == null) return;
        final String q = searchInput == null ? "" : searchInput.getText().toString().trim();
        final List<AccountStore.Account> ws = workspaceAccounts();
        final int gen = ++accGen;
        if (showMask) showAccMask();
        new Thread(() -> {
          try {
            List<AccountStore.Account> shown = new ArrayList<>();
            java.util.Map<String, String[]> stats = new java.util.HashMap<>();
            java.util.Map<String, List<RoleStore.Role>> accRoles = new java.util.HashMap<>();
            java.util.Map<String, int[]> roleStats = new java.util.HashMap<>();   // fpid:sid -> [done,failed,run,total]
            java.util.Map<String, Long> roleLast = new java.util.HashMap<>();
            java.util.Map<String, Boolean> onlineMap = new java.util.HashMap<>();
            android.content.SharedPreferences sched =
                    getSharedPreferences("daily_sched", MODE_PRIVATE);
            com.carlos.vault.daily.LogStore rl = new com.carlos.vault.daily.LogStore(HomeActivity.this);
            int errCnt = 0, idleCnt = 0;
            for (AccountStore.Account a : accounts) {
                if (a == null) continue;
                String[] s2 = statusOf(a);
                stats.put(a.id, s2);
                if ("err".equals(s2[0])) errCnt++;
                if ("idle".equals(s2[0])) idleCnt++;
                try {
                    List<RoleStore.Role> rs = RoleStore.roles(HomeActivity.this, a.fpid);
                    accRoles.put(a.id, rs);
                    for (RoleStore.Role r : rs) {
                        roleStats.put(a.fpid + ":" + r.serverid, rl.dayStats(a.fpid, r.serverid));
                        roleLast.put(a.fpid + ":" + r.serverid,
                                sched.getLong("lastr:" + a.fpid + ":" + r.serverid, 0));
                    }
                } catch (Throwable ignored2) {
                }
                // 在线判定：开快照 zip 读一个条目，必须放在后台线程（按快照 mtime 缓存，见 accountOnline）
                onlineMap.put(a.id, accountOnline(a));
                if (accountFilter == 1 && !ws.contains(a)) continue;
                if (accountFilter == 2 && !"err".equals(s2[0])) continue;
                if (accountFilter == 3 && !"idle".equals(s2[0])) continue;
                if (!q.isEmpty() && (a.label == null || !a.label.contains(q))
                        && (a.fpid == null || !a.fpid.contains(q))) continue;
                shown.add(a);
            }
            // live 挂机角色反查：容器正挂着的号，其 playerprefs USER_SETTING_<uid> 可精确到"正在玩的那个角色"
            // （修复：激活角色是913铁柱但实际挂机888领主时，账号页误标铁柱在线）
            final long fLiveUid = liveOnlineUid();
            final String fLiveEntry = new AccountStore(HomeActivity.this).getLiveEntryId();
            final List<AccountStore.Account> fShown = shown;
            final java.util.Map<String, String[]> fStats = stats;
            final java.util.Map<String, List<RoleStore.Role>> fRoles = accRoles;
            final java.util.Map<String, int[]> fRoleStats = roleStats;
            final java.util.Map<String, Long> fRoleLast = roleLast;
            final java.util.Map<String, Boolean> fOnline = onlineMap;
            final int fErr = errCnt, fIdle = idleCnt;
            runOnUiThread(() -> {
                if (gen != accGen) {
                    // 已有更新的刷新请求接管渲染。但遮罩是自己 show 的，若不撤会永久
                    // 挡屏（真机：核对轮每完成一号触发一次无遮罩刷新，把带遮罩刷新的
                    // gen 顶掉，遮罩永远等不到 showMask 的那次收尾）——撤掉，由最新
                    // 刷新负责后续渲染。
                    if (showMask) hideAccMask();
                    return;
                }
                accountList.removeAllViews();
                updateAccSeg();
                if (accView == 1) {
                    renderRoleMatrix(fShown, fRoles, fRoleStats, fRoleLast);
                } else {
                    for (AccountStore.Account a : fShown) {
                        accountList.addView(accountGroupCard(a, fStats.get(a.id), fRoles.get(a.id),
                                fRoleStats, fRoleLast, fOnline, fLiveUid, fLiveEntry));
                    }
                }
                if (fShown.isEmpty()) accountList.addView(emptyTip("没有匹配的账号"));
                refreshBatchBar();
        // 筛选chips：药丸带计数（规范 .segscroll）+ 顶栏 caption
        String[] labels = {"全部 " + accounts.size(), "工作区 " + ws.size(),
                "异常 " + fErr, "未跑 " + fIdle};
        for (int i = 0; i < filterChips.size() && i < labels.length; i++) {
            filterChips.get(i).setText(labels[i]);
            boolean sel = i == accountFilter;
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(999));
            if (sel) {
                pill.setColor(UiStyle.BRAND);
                pill.setStroke(dp(1), UiStyle.BRAND);
            } else {
                pill.setColor(UiStyle.WHITE);
                pill.setStroke(dp(1), UiStyle.LINE);
            }
            filterChips.get(i).setBackground(pill);
            filterChips.get(i).setTextColor(sel ? UiStyle.WHITE : UiStyle.INK2);
            filterChips.get(i).setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (accountCap != null)
            accountCap.setText(accounts.size() + " 个账号 · 工作区 " + ws.size());
        hideAccMask();   // 最新一轮已渲染，无论它是否 show 过遮罩都撤（防残留挡屏）
            });
          } catch (final Throwable t) {
            runOnUiThread(() -> {
                if (gen != accGen) {
                    if (showMask) hideAccMask();   // 同上：过期刷新自己 show 的遮罩自己撤
                    return;
                }
                accountList.removeAllViews();
                accountList.addView(emptyTip("账号数据加载失败：" + t));
                refreshBatchBar();
                hideAccMask();
            });
          }
        }, "acc-refresh").start();
    }

    // ==================== 账号页（v2 原型）：在线判定 / 游戏头像 / 表格化角色行 ====================

    /** 角色表各列宽度（dp）。昵称列吃剩余宽度，其余定宽以保证逐行对齐（状态列容纳"● 日常在线"）。 */
    private static final int COL_ONLINE = 58, COL_ZONE = 34, COL_DONE = 52, COL_LAST = 52, COL_GO = 44;

    /**
     * 「在线」判定：该账号当前是否持有<b>有效登录态</b>。
     *
     * <p>口径与引擎侧完全一致（{@code session_key} 非空 + fpid 匹配，见 {@link SessionProbe}）：
     * 先看容器里正挂着的 live 会话，再读该入口快照。所以<b>多个号可以同时在线</b> ——
     * 这也是"在线"必须按账号/角色分别显示、而不能做成全局一个点的原因。
     *
     * <p>代价：每个号要开一次快照 zip 读一个条目。按快照 {@code lastModified} 缓存，
     * 只有快照真的变过才重新探测；本方法只在 {@code refreshAccounts} 的<b>后台线程</b>里调用。
     *
     * <p><b>⚠️ 全项目只有这一处定义"在线"</b>，将来要换口径（例如改成"当前容器里挂着的号"）
     * 只改这个方法即可。
     */
    private final java.util.HashMap<String, Boolean> onlineCache = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> onlineStamp = new java.util.HashMap<>();

    /** 角色级在线判定：live 挂载账号用 uid 反查真实挂机角色；纯快照账号维持激活角色推断。 */
    private boolean roleOnline(AccountStore.Account a, RoleStore.Role r, boolean accOn, long actSid,
                               boolean isLiveAcc, long liveUid) {
        if (!accOn) return false;
        if (isLiveAcc && liveUid > 0) return r.uid > 0 && r.uid == liveUid;
        return r.serverid == actSid;
    }

    /**
     * 角色行在线<b>文案与口径</b>（返回 [文案, "on"/"off"]）：
     * <ul>
     *   <li><b>日常在线</b>：RoleSessionKeeper 协议常驻会话 online（24h 心跳/自动互助），
     *       角色粒度精确（fpid:sid）——多角色并行在线的主口径；</li>
     *   <li><b>游戏在线</b>：容器里正挂着该角色（live playerprefs uid 反查，用户在真机客户端玩）；</li>
     *   <li><b>在线</b>：老口径兜底——快照有效登录态 + 激活角色推断（无法区分角色，仅主角色可能命中）；</li>
     *   <li><b>离线</b>：以上皆无。</li>
     * </ul>
     * 协议优先于容器：同一角色两条路不会同时成立（游戏客户端登录会顶掉协议会话 code=5）。
     */
    private String[] roleOnlineLabel(AccountStore.Account a, RoleStore.Role r, boolean accOn,
                                     long actSid, boolean isLiveAcc, long liveUid) {
        try {
            for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                    : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                if (a.fpid != null && a.fpid.equals(ls.fpid) && ls.sid == r.serverid
                        && com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                    return new String[]{"● 日常在线", "on"};
                }
            }
        } catch (Throwable ignored) {
        }
        if (isLiveAcc && liveUid > 0 && r.uid > 0 && r.uid == liveUid) {
            return new String[]{"● 游戏在线", "on"};
        }
        if (roleOnline(a, r, accOn, actSid, isLiveAcc, liveUid)) {
            return new String[]{"● 在线", "on"};
        }
        return new String[]{"● 离线", "off"};
    }

    private boolean accountOnline(AccountStore.Account a) {
        if (a == null || a.id == null) return false;
        try {
            // ① 容器里正挂着的号：直接问引擎的 live 会话，避免快照还没落盘时误判离线
            String owner = new AccountStore(this).getLiveEntryId();
            if (a.id.equals(owner)) {
                String live = engine.readLiveSessionFpid();
                if (!live.isEmpty()
                        && (a.fpid == null || a.fpid.isEmpty() || a.fpid.equals(live))) return true;
            }
            // ② 入口快照里的登录态（失败即判无效，与引擎同款 fail-closed）
            java.io.File snap = engine.entrySnapshot(a.id);
            if (snap == null || !snap.exists()) return false;
            long stamp = snap.lastModified();
            Long hit = onlineStamp.get(a.id);
            if (hit != null && hit == stamp) return Boolean.TRUE.equals(onlineCache.get(a.id));
            boolean ok = SessionProbe.snapshotHasValidSession(snap,
                    "shared_prefs/" + VaultEngine.PREF_NAME + ".xml", VaultEngine.SESSION_KEY, a.fpid);
            onlineStamp.put(a.id, stamp);
            onlineCache.put(a.id, ok);
            return ok;
        } catch (Throwable t) {
            return false;   // 任何异常都当"离线"，方向安全
        }
    }

    /**
     * 无尽冬日头像：取系统已装游戏包（{@code com.gof.china}）的真实图标 ——
     * 容器引用的就是这份 APK，所以拿到的就是游戏本尊的图标。
     * 取不到（未装 / 图标异常）时回退成"渐变首字块"。结果进程内只试一次。
     */
    private android.graphics.drawable.Drawable gameIcon;
    private boolean gameIconTried;

    private android.graphics.drawable.Drawable gameIcon() {
        if (!gameIconTried) {
            gameIconTried = true;
            try {
                gameIcon = getPackageManager().getApplicationIcon("com.gof.china");
            } catch (Throwable t) {
                gameIcon = null;
            }
        }
        return gameIcon;
    }

    /** 头像方块：优先游戏图标（圆角裁切），否则渐变首字。 */
    private android.view.View avatarView(int sizeDp, String label, String seed) {
        int px = dp(sizeDp);
        android.graphics.drawable.Drawable ic = gameIcon();
        if (ic != null) {
            try {
                // 自己画到圆角 Bitmap：不依赖 androidx 的 RoundedBitmapDrawableFactory，
                // 也就不会给这个已经 830MB 的项目再添一个依赖。
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        px, px, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(0, 0, px, px),
                        dp(13), dp(13), android.graphics.Path.Direction.CW);
                cv.clipPath(clip);
                ic.setBounds(0, 0, px, px);
                ic.draw(cv);
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                return iv;
            } catch (Throwable ignored) {
                // 落到下面的一字块
            }
        }
        TextView tv = new TextView(this);
        tv.setText(label == null || label.isEmpty() ? "?" : label.substring(0, 1));
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiStyle.WHITE);
        tv.setGravity(Gravity.CENTER);
        int h = seed == null ? 0 : Math.abs(seed.hashCode());
        int[][] grads = {{UiStyle.BRAND, UiStyle.BRAND_DEEP}, {UiStyle.RUN_SOLID, UiStyle.RUN_INK},
                {UiStyle.WAIT_SOLID, UiStyle.WAIT_INK}, {UiStyle.WARN_SOLID, UiStyle.WARN_INK}};
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, grads[h % grads.length]);
        g.setCornerRadius(dp(13));
        tv.setBackground(g);
        return tv;
    }

    /** 登录方式徽标：token=靛（待领取色）/ 原生=绿，与原型 vbadge 配色一致。 */
    private TextView loginBadge(AccountStore.Account a) {
        boolean isToken = a.token != null && !a.token.isEmpty();
        return UiStyle.badge(this, isToken ? "wait" : "ok", isToken ? "token" : "原生");
    }

    /** UID 尾号（保留后 6 位，前缀省略号）。 */
    private String uidTail(AccountStore.Account a) {
        if (a == null || a.fpid == null || a.fpid.isEmpty()) return "—";
        return a.fpid.length() <= 6 ? a.fpid : "…" + a.fpid.substring(a.fpid.length() - 6);
    }

    /** 角色区表头行：状态 / 角色 / 区号 / 今日完成 / 上次登录 /（操作列无标题）。 */
    private android.view.View roleTableHead() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(5));
        r.addView(colHead("状态", COL_ONLINE, Gravity.START));
        r.addView(colHead("角色", 0, Gravity.START),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(colHead("区号", COL_ZONE, Gravity.END));
        r.addView(colHead("今日完成", COL_DONE, Gravity.END));
        r.addView(colHead("上次登录", COL_LAST, Gravity.END));
        r.addView(colHead("", COL_GO, Gravity.END));
        return r;
    }

    private TextView colHead(String text, int wDp, int gravity) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(9.5f);
        t.setTextColor(UiStyle.INK3);
        t.setGravity(gravity);
        t.setMaxLines(1);
        if (wDp > 0) t.setWidth(dp(wDp));
        return t;
    }

    /** 上次登录的紧凑时间：今天→HH:mm，今年→MM-dd，更早→yy-MM-dd（列宽只有 52dp）。 */
    private String shortTime(long ts) {
        if (ts <= 0) return "—";
        java.util.Calendar c = java.util.Calendar.getInstance();
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.setTimeInMillis(ts);
        if (c.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                && c.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR))
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(new java.util.Date(ts));
        if (c.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR))
            return new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(new java.util.Date(ts));
        return new java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.CHINA).format(new java.util.Date(ts));
    }

    /** 账号分组框：头行=备注名+登录方式(原生/token)+⚙；框内=已入工作区的角色独立入口卡。 */
    private android.view.View accountGroupCard(final AccountStore.Account a, final String[] st,
                                               final List<RoleStore.Role> roles,
                                               final java.util.Map<String, int[]> roleStats,
                                               final java.util.Map<String, Long> roleLast,
                                               final java.util.Map<String, Boolean> online,
                                               final long liveUid, final String liveEntryId) {
        LinearLayout box = UiStyle.outlineCard(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(10);
        box.setLayoutParams(blp);

        // 账号在线（快照会话口径）：头行徽标计数、角色行、角色管理弹层共用。
        // 角色级：容器正挂着本账号（liveEntryId 匹配）时，用 live playerprefs 的 uid 精确反查
        // "正在玩的那个角色"（会话 XML 只有 fpid，但 playerprefs 的 USER_SETTING_<uid> 有角色 uid）；
        // 纯快照账号（未挂载）无法区分角色，维持激活角色（actSid）推断。
        final boolean accOn = online != null && Boolean.TRUE.equals(online.get(a.id));
        final long actSid = RoleStore.activeRole(this, a.fpid);
        final boolean isLiveAcc = liveEntryId != null && liveEntryId.equals(a.id);

        // 头行：☐勾选 | 无尽冬日头像 | 名字 + [登录方式] UID | ⚙ 角色管理
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 勾选（批量）
        final TextView check = new TextView(this);
        boolean sel = selectedAccounts.contains(a.id);
        check.setText(sel ? "✓" : "");
        check.setTextSize(12);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setTextColor(UiStyle.WHITE);
        check.setGravity(Gravity.CENTER);
        GradientDrawable ckg = new GradientDrawable();
        ckg.setCornerRadius(dp(7));
        if (sel) {
            ckg.setColor(UiStyle.BRAND);
        } else {
            ckg.setColor(UiStyle.WHITE);
            ckg.setStroke(dp(1), UiStyle.LINE_STRONG);
        }
        check.setBackground(ckg);
        UiStyle.press(check);
        check.setOnClickListener(v -> {
            if (selectedAccounts.contains(a.id)) selectedAccounts.remove(a.id);
            else selectedAccounts.add(a.id);
            refreshAccounts();
        });
        head.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));

        // 无尽冬日头像（游戏真实图标；取不到回退渐变首字）
        android.view.View av = avatarView(40, a.label, a.id);
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avlp.leftMargin = dp(9);
        head.addView(av, avlp);

        // 名字（取自备注）+ [登录方式] UID
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(9), 0, dp(6), 0);
        TextView name = new TextView(this);
        name.setText(a.label);
        name.setTextSize(14.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(name);
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, dp(3), 0, 0);
        metaRow.addView(loginBadge(a));
        TextView uid = new TextView(this);
        uid.setText("UID " + uidTail(a));
        uid.setTextSize(10.5f);
        uid.setTextColor(UiStyle.INK2);
        uid.setPadding(dp(6), 0, 0, 0);
        uid.setMaxLines(1);
        metaRow.addView(uid);
        titleBox.addView(metaRow);
        head.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // ⚙ 角色管理：唯一的管理入口之一（另一个 = 点角色行）。卡片其它区域不响应，避免误触弹层。
        // 图标为程序化矢量 Icon（文本字符会随机型字体异形）。
        LinearLayout gear = new LinearLayout(this);
        gear.setOrientation(LinearLayout.HORIZONTAL);
        gear.setGravity(Gravity.CENTER);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(UiStyle.BRAND_50);
        gbg.setCornerRadius(dp(10));
        gbg.setStroke(dp(1), UiStyle.BRAND_200);
        gear.setBackground(gbg);
        gear.setPadding(dp(9), dp(7), dp(9), dp(7));
        gear.addView(new UiStyle.Icon(this, UiStyle.Icon.GEAR, UiStyle.BRAND_TEXT));
        TextView gearTx = new TextView(this);
        gearTx.setText("角色管理");
        gearTx.setTextSize(11.5f);
        gearTx.setTypeface(Typeface.DEFAULT_BOLD);
        gearTx.setTextColor(UiStyle.BRAND_TEXT);
        LinearLayout.LayoutParams glp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp2.leftMargin = dp(5);
        gear.addView(gearTx, glp2);
        UiStyle.press(gear);
        gear.setOnClickListener(v -> showRoleManager(a, roles, accOn));
        head.addView(gear);
        box.addView(head);

        // 框内：已启用角色 → 表格（状态/角色/区号/今日完成/上次登录/登录），最多 4 行
        int enN = 0;
        if (roles != null) for (RoleStore.Role r0 : roles) if (r0.enabled) enN++;
        if (enN > 0) {
            LinearLayout secH = new LinearLayout(this);
            secH.setOrientation(LinearLayout.HORIZONTAL);
            secH.setPadding(0, dp(6), 0, 0);
            TextView l = new TextView(this);
            l.setText("已启用角色 · " + enN);
            l.setTextSize(10);
            l.setTextColor(UiStyle.INK3);
            secH.addView(l, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView rgt = new TextView(this);
            // 头行口径：快照登录态 或 该账号任一角色协议常驻在线
            boolean anyKeep = false;
            try {
                for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                        : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                    if (a.fpid != null && a.fpid.equals(ls.fpid)
                            && com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                        anyKeep = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            boolean headOn = accOn || anyKeep;
            rgt.setText(headOn ? "在线" : "离线");
            rgt.setTextSize(10);
            rgt.setTypeface(Typeface.DEFAULT_BOLD);
            rgt.setTextColor(headOn ? UiStyle.OK_INK : UiStyle.INK3);
            secH.addView(rgt);
            box.addView(secH);
            box.addView(roleTableHead());
            int shownN = 0;
            for (final RoleStore.Role r : roles) {
                if (!r.enabled) continue;
                if (shownN >= 4) break;
                shownN++;
                box.addView(roleEntryCard(a, r,
                        roleStats.get(a.fpid + ":" + r.serverid),
                        roleLast.containsKey(a.fpid + ":" + r.serverid)
                                ? roleLast.get(a.fpid + ":" + r.serverid) : 0L,
                        roleOnlineLabel(a, r, accOn, actSid, isLiveAcc, liveUid), accOn, roles));
            }
            if (enN > 4) {
                TextView more = new TextView(this);
                more.setText("还有 " + (enN - 4) + " 个已启用角色，点「角色管理」查看全部 ›");
                more.setTextSize(11);
                more.setTypeface(Typeface.DEFAULT_BOLD);
                more.setTextColor(UiStyle.BRAND_TEXT);
                more.setPadding(0, dp(8), 0, 0);
                UiStyle.press(more);
                more.setOnClickListener(v -> showRoleManager(a, roles, accOn));
                box.addView(more);
            }
        } else {
            TextView tip = new TextView(this);
            tip.setText(roles == null || roles.isEmpty()
                    ? "暂无角色数据（登录一次自动发现）"
                    : "点右上「⚙ 角色管理」把角色加入工作区");
            tip.setTextSize(11.5f);
            tip.setTextColor(UiStyle.INK2);
            tip.setPadding(dp(4), dp(10), dp(4), dp(4));
            box.addView(tip);
        }
        return box;
    }

    /**
     * 角色行（账号框内嵌 · 表格化，与原型一致）：
     * {@code 状态（● 日常在线/游戏在线/在线/离线） | 昵称 | 区号 | 今日完成 | 上次登录 | 登录}。
     *
     * <p>点<b>行</b> = 打开角色管理；点<b>「登录」</b> = 以该角色启动游戏。
     * 卡片其它区域不响应 —— 避免"想看一个角色却总弹出整张表"。
     */
    private android.view.View roleEntryCard(final AccountStore.Account a, final RoleStore.Role r,
                                            int[] dayStats, long lastTs, String[] onlineKind, boolean accOn,
                                            final List<RoleStore.Role> allRoles) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        int done = dayStats == null ? 0 : dayStats[0];
        int total = dayStats == null ? 0 : dayStats[3];

        // ① 状态：文字 + 颜色双重编码（不靠颜色单独分辨）；文案由 roleOnlineLabel 给出
        TextView stv = new TextView(this);
        boolean online = onlineKind != null && "on".equals(onlineKind[1]);
        stv.setText(onlineKind == null ? "● 离线" : onlineKind[0]);
        stv.setTextSize(10);
        stv.setTypeface(Typeface.DEFAULT_BOLD);
        stv.setTextColor(online ? UiStyle.OK_INK : UiStyle.INK3);
        stv.setMaxLines(1);
        stv.setWidth(dp(COL_ONLINE));
        row.addView(stv);

        // ② 昵称
        TextView nm = new TextView(this);
        nm.setText(r.name == null || r.name.isEmpty() ? "K" + r.serverid + " · 未同步" : r.name);
        nm.setTextSize(12.5f);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(UiStyle.INK);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(nm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ③ 区号 ④ 今日完成 ⑤ 上次登录
        row.addView(cellText(String.valueOf(r.serverid), COL_ZONE, 10.5f, UiStyle.INK2, Gravity.END, false));
        int dcol = total > 0 && done >= total ? UiStyle.OK_INK
                : (done == 0 ? UiStyle.INK3 : UiStyle.WARN_INK);
        row.addView(cellText(done + "/" + total, COL_DONE, 11.5f, dcol, Gravity.END, true));
        row.addView(cellText(shortTime(lastTs), COL_LAST, 10.5f, UiStyle.INK2, Gravity.END, false));

        // ⑥ 登录（以该角色启动游戏）
        TextView go = new TextView(this);
        go.setText("登录");
        go.setTextSize(11);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setTextColor(UiStyle.BRAND_TEXT);
        go.setGravity(Gravity.CENTER);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(UiStyle.BRAND_50);
        gbg.setCornerRadius(dp(8));
        gbg.setStroke(dp(1), UiStyle.BRAND_200);
        go.setBackground(gbg);
        go.setPadding(0, dp(5), 0, dp(5));
        go.setWidth(dp(COL_GO));
        UiStyle.press(go);
        go.setOnClickListener(v -> launchRole(a, r));
        row.addView(go);

        // 点角色行 = 角色管理（与齿轮同一入口）
        UiStyle.press(row);
        row.setOnClickListener(v -> showRoleManager(a, allRoles, accOn));
        return row;
    }

    /** 定宽单元格。 */
    private TextView cellText(String text, int wDp, float size, int color, int gravity, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(gravity);
        t.setMaxLines(1);
        t.setWidth(dp(wDp));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /**
     * 以指定角色登录：先把激活角色对齐到它（数据写读同源），再走原有启动流程。
     *
     * <p>登录的对象是<b>角色</b>而不是账号 —— 同一账号下不同角色是不同游戏进度，
     * 所以这里必须先 {@code setActiveRole}，否则引擎会按上次的激活角色启动。
     *
     * <p><b>翻转最近角色</b>：游戏客户端的 loginauth 写死在 il2cpp 里无法定制选角参数，
     * 落哪个角色由服务器端"最近角色"决定。启动客户端前先用引擎按目标角色登录一次
     * （RoleLogin 钉扎，uid 选角实证有效）随即断开——服务器"最近角色"翻转为目标，
     * 客户端起来自然落对。预登录失败不阻断启动（客户端按旧状态落，弹提示）。
     */
    private void launchRole(AccountStore.Account a, RoleStore.Role r) {
        try {
            RoleStore.setActiveRole(this, a.fpid, r.serverid);
        } catch (Throwable ignored) {
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在切换到 K" + r.serverid + " «" + r.name + "»（预登录对齐角色…）");
        pd.setCancelable(false);
        pd.show();
        final AccountStore.Account acc = a;
        final RoleStore.Role role = r;
        new Thread(() -> {
            String warn = null;
            try {
                com.carlos.vault.wjdr.WjdrEngine pre =
                        com.carlos.vault.daily.WjdrAndroid.newEngine(this);
                try {
                    pre.initialize();
                    com.carlos.vault.daily.RoleLogin.loginPinned(this, pre,
                            acc.fpid, acc.token, role.serverid);
                } finally {
                    pre.close();   // 立即断开：只为翻转服务器端"最近角色"记录
                }
            } catch (Throwable t) {
                warn = "预登录失败(" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                        + ")，客户端可能落在上次角色";
            }
            final String warnF = warn;
            runOnUiThread(() -> {
                pd.dismiss();
                if (warnF != null) android.widget.Toast.makeText(this, warnF,
                        android.widget.Toast.LENGTH_LONG).show();
                // 启动阶段提示角色名（含区服），不再显示账号备注名
                String roleName = (role.name == null || role.name.isEmpty() ? acc.label : role.name)
                        + "·K" + role.serverid;
                doSwitch(acc, roleName);
            });
        }, "prelaunch-role").start();
    }

    /**
     * ⚙ 角色管理 <b>底部弹层</b>（与原型一致）：列出该账号全部角色 —— 启用与未启用都在。
     *
     * <p>结构：拖拽条 → 头（账号名 + 副题「共 N 个角色，已启用 M · 开启即加入工作区」+ 关闭）
     * → 角色列表（未启用行降饱和 + 「未启用」标签 + 开关；每行带 在线/离线 · 区号 · 今日完成 · 上次登录）
     * → 底部「全部加入工作区 / 完成」。
     *
     * <p>打开开关 = 加入工作区并启用；关闭 = 移出。改动后账号卡的角色区会随 refreshAccounts 同步。
     * 为什么用弹层而不是跳页：角色是账号的属性，管理完就关掉，不该让用户离开账号上下文。
     *
     * <p>实现说明：不用 BottomSheetDialog（那要 material 依赖），直接往页面根 FrameLayout
     * 盖一层半透明遮罩 + 白色圆角面板，带 320ms 上滑入场动画 —— 与原型 spec 的动效一致。
     */
    private void showRoleManager(final AccountStore.Account a, final List<RoleStore.Role> roles,
                                 boolean accOn) {
        if (roles == null || roles.isEmpty()) {
            toast("暂无角色数据 —— 登录一次游戏后自动发现");
            return;
        }
        final long actSid = RoleStore.activeRole(this, a.fpid);
        // 角色行在线口径所需的 live 信息（一次读全；liveOnlineUid 读 live playerprefs，开弹层时算一次可接受）
        final boolean isLiveAcc;
        {
            String liveEntry = null;
            try {
                liveEntry = new AccountStore(this).getLiveEntryId();
            } catch (Throwable ignored) {
            }
            isLiveAcc = liveEntry != null && liveEntry.equals(a.id);
        }
        final long liveUid = isLiveAcc ? liveOnlineUid() : 0;

        final android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(UiStyle.SCRIM);   // rgba(8,12,23,35%)，压暗背景
        // 点遮罩空白处关闭
        overlay.setOnClickListener(v -> dismissSheet(overlay));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(UiStyle.WHITE);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(UiStyle.WHITE);
        pbg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        panel.setBackground(pbg);
        panel.setElevation(dp(16));
        android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        panel.setLayoutParams(plp);

        // —— 拖拽条 ——
        android.view.View grab = new android.view.View(this);
        grab.setBackgroundColor(UiStyle.LINE_STRONG);
        android.widget.FrameLayout.LayoutParams glp = new android.widget.FrameLayout.LayoutParams(
                dp(36), dp(4), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        glp.topMargin = dp(9);
        android.widget.FrameLayout grabWrap = new android.widget.FrameLayout(this);
        grabWrap.addView(grab, glp);
        panel.addView(grabWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(13)));

        // —— 头部：账号名 + 副题 + 关闭 ——
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(4), dp(10), dp(12));
        LinearLayout hb = new LinearLayout(this);
        hb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(a.label + " · 角色管理");
        title.setTextSize(14.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        hb.addView(title);
        final TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setTextColor(UiStyle.INK2);
        hb.addView(sub);
        head.addView(hb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 「发现角色」：向游戏服务器查询该凭证名下全部角色并入列表（本地表只含登录过的角色）
        final TextView discover = new TextView(this);
        discover.setText("🔍 发现角色");
        discover.setTextSize(11);
        discover.setTypeface(Typeface.DEFAULT_BOLD);
        discover.setTextColor(UiStyle.BRAND_TEXT);
        discover.setGravity(Gravity.CENTER);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(UiStyle.SURFACE_3);
        dbg.setCornerRadius(dp(9));
        discover.setBackground(dbg);
        discover.setPadding(dp(9), dp(6), dp(9), dp(6));
        UiStyle.press(discover);
        head.addView(discover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.FrameLayout close = new android.widget.FrameLayout(this);
        close.setBackground(UiStyle.roundRect(UiStyle.SURFACE_3, dp(10)));
        android.widget.FrameLayout.LayoutParams clp3 = new android.widget.FrameLayout.LayoutParams(
                dp(18), dp(18), Gravity.CENTER);
        close.addView(new UiStyle.Icon(this, UiStyle.Icon.CLOSE, UiStyle.INK2), clp3);
        close.setPadding(dp(10), dp(7), dp(10), dp(7));
        UiStyle.press(close);
        close.setOnClickListener(v -> dismissSheet(overlay));
        head.addView(close);
        panel.addView(head);

        final java.util.List<Runnable> syncSubs = new ArrayList<>();
        syncSubs.add(() -> {
            // 统计行实时读盘：行开关/发现角色落盘后重跑 syncSubs 才能反映最新数
            // （旧实现数的是打开弹层时捕获的 roles 快照，开关切了计数不动）。
            int n = roles.size(), en = 0;
            try {
                java.util.List<RoleStore.Role> cur = RoleStore.roles(this, a.fpid);
                n = cur.size();
                for (RoleStore.Role r : cur) if (r.enabled) en++;
            } catch (Throwable ignored) {
                for (RoleStore.Role r : roles) if (r.enabled) en++;
            }
            sub.setText("共 " + n + " 个角色，已启用 " + en + " · 开启即加入工作区");
        });
        syncSubs.get(0).run();

        // —— 角色列表（可重建：发现角色后重拉全量表） ——
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final android.content.SharedPreferences sched = getSharedPreferences("daily_sched", MODE_PRIVATE);
        final java.util.concurrent.atomic.AtomicReference<List<RoleStore.Role>> rolesRef =
                new java.util.concurrent.atomic.AtomicReference<>(roles);
        final Runnable rebuildList = () -> {
            list.removeAllViews();
            for (final RoleStore.Role r : rolesRef.get()) {
                list.addView(sheetRoleRow(a, r, roleOnlineLabel(a, r, accOn, actSid, isLiveAcc, liveUid),
                        sched.getLong("lastr:" + a.fpid + ":" + r.serverid, 0), syncSubs));
            }
        };
        rebuildList.run();
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        panel.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // —— 底部按钮：全部加入工作区 / 完成 ——
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(16), dp(11), dp(16), dp(14));
        TextView all = new TextView(this);
        all.setText("全部加入工作区");
        all.setTextSize(13f);
        all.setTypeface(Typeface.DEFAULT_BOLD);
        all.setTextColor(UiStyle.INK);
        all.setGravity(Gravity.CENTER);
        all.setBackground(UiStyle.roundRect(UiStyle.SURFACE_3, dp(12)));
        all.setPadding(0, dp(11), 0, dp(11));
        UiStyle.press(all);
        all.setOnClickListener(v -> {
            for (RoleStore.Role r : rolesRef.get()) RoleStore.setEnabled(this, a.fpid, r.serverid, true);
            for (Runnable rr : syncSubs) rr.run();
            // 刷新列表行视觉（在线标记按角色行新口径）
            rebuildList.run();
            refreshAccounts();
            toast("已把 " + rolesRef.get().size() + " 个角色全部加入工作区");
        });
        foot.addView(all, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(13f);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setTextColor(UiStyle.WHITE);
        done.setGravity(Gravity.CENTER);
        done.setBackground(UiStyle.roundRect(UiStyle.BRAND, dp(12)));
        done.setPadding(0, dp(11), 0, dp(11));
        UiStyle.press(done);
        done.setOnClickListener(v -> dismissSheet(overlay));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
        dlp.leftMargin = dp(9);
        foot.addView(done, dlp);
        panel.addView(foot);

        // 「发现角色」：req_server_list_simple + req_role_list 全量查询（本地表只含登录过的角色，
        // 玩家在游戏里新开的区服角色不会自动出现——2026-10-12 用户反馈角色管理列表不全）。
        discover.setOnClickListener(v -> {
            discover.setEnabled(false);
            discover.setAlpha(0.5f);
            sub.setText("发现角色中…（登录游戏服务器查询全角色清单）");
            new Thread(() -> {
                String err = null;
                int found = 0, added = 0;
                try {
                    java.util.List<com.carlos.vault.wjdr.WjdrEngine.RoleInfo> roles0 = null;
                    // 优先借常驻会话（免登录不顶号）；无会话且容器游戏没挂本账号时才临时登录
                    com.carlos.vault.daily.RoleSessionKeeper.LiveSession live =
                            DailyRunner.liveOf(this, a.fpid, 0);
                    if (live != null) {
                        roles0 = live.engine.discoverRoles();
                    } else {
                        String liveFpid = new com.carlos.vault.VaultEngine(this).readLiveSessionFpid();
                        if (a.fpid != null && a.fpid.equals(liveFpid)) {
                            err = "该账号正在容器游戏里运行，退出游戏后再发现";
                        } else {
                            com.carlos.vault.wjdr.WjdrEngine eng =
                                    com.carlos.vault.daily.WjdrAndroid.newEngine(this);
                            try {
                                com.carlos.vault.daily.HotConfig.init(this);
                                eng.initialize();
                                com.carlos.vault.daily.RoleLogin.loginPinned(this, eng,
                                        a.fpid, a.token, 0);
                                roles0 = eng.discoverRoles();
                            } finally {
                                eng.close();
                            }
                        }
                    }
                    if (roles0 != null) {
                        found = roles0.size();
                        for (com.carlos.vault.wjdr.WjdrEngine.RoleInfo ri : roles0) {
                            int before = RoleStore.roles(this, a.fpid).size();
                            // 已存在角色不改变 enabled；新角色默认未启用
                            RoleStore.registerRole(this, a.fpid, ri.serverid, ri.uid, ri.name);
                            if (RoleStore.roles(this, a.fpid).size() > before) added++;
                        }
                    }
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final String fErr = err;
                final int fFound = found, fAdded = added;
                runOnUiThread(() -> {
                    discover.setEnabled(true);
                    discover.setAlpha(1f);
                    if (fErr != null) {
                        sub.setText("发现失败：" + fErr);
                        return;
                    }
                    rolesRef.set(RoleStore.roles(this, a.fpid));
                    for (Runnable rr : syncSubs) rr.run();
                    rebuildList.run();
                    refreshAccounts();
                    sub.setText("发现完成：服务器共 " + fFound
                            + " 个角色，新增 " + fAdded + "（新角色默认未启用）");
                });
            }, "role-discover").start();
        });

        overlay.addView(panel, plp);

        // 高度约束：内容自适应，但不超过屏高 80%（原型 spec）
        overlay.addOnLayoutChangeListener((v2, l, t2, r2, b2, ol, ot, or2, ob) -> {
            android.view.ViewGroup.LayoutParams lp = panel.getLayoutParams();
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
            if (panel.getHeight() > max) {
                lp.height = max;
                panel.setLayoutParams(lp);
            }
        });

        // 盖到内容根上（pages 容器的父 FrameLayout）
        ViewGroup root = (ViewGroup) pages[1].getParent();
        root.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 上滑入场（320ms ease-out，近似原型 ease-out-expo）
        panel.setTranslationY(dp(48));
        panel.setAlpha(0.5f);
        panel.animate().translationY(0).alpha(1f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f))
                .start();
        overlay.setAlpha(0);
        overlay.animate().alpha(1f).setDuration(200).start();
    }

    /** 关闭底部弹层：面板下滑淡出后移除整层。 */
    private void dismissSheet(final android.widget.FrameLayout overlay) {
        View panel = null;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            android.view.View c = overlay.getChildAt(i);
            if (c instanceof LinearLayout) { panel = c; break; }
        }
        final View p2 = panel;
        Runnable remove = () -> {
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        };
        if (p2 != null) {
            p2.animate().translationY(dp(48)).alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(remove).start();
        } else {
            remove.run();
        }
        overlay.animate().alpha(0f).setDuration(200).start();
    }

    /** 弹层内单个角色行：在线标记(日常在线/游戏在线/在线/离线) · 名称(+未启用标签) · 区号 · 今日完成 · 上次登录 · 开关。 */
    private android.view.View sheetRoleRow(final AccountStore.Account a, final RoleStore.Role r,
                                           String[] onlineKind, long lastTs,
                                           final java.util.List<Runnable> syncSubs) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        if (!r.enabled) row.setBackgroundColor(UiStyle.SURFACE_2);   // 未启用行降饱和

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        TextView stv = new TextView(this);
        boolean online = onlineKind != null && "on".equals(onlineKind[1]);
        stv.setText(onlineKind == null ? "● 离线" : onlineKind[0]);
        stv.setTextSize(11);
        stv.setTypeface(Typeface.DEFAULT_BOLD);
        stv.setTextColor(online ? UiStyle.OK_INK : UiStyle.INK3);
        tb.addView(stv);
        TextView nm = new TextView(this);
        String rn = r.name == null || r.name.isEmpty() ? "K" + r.serverid + " · 未同步" : r.name;
        nm.setText(rn);
        nm.setTextSize(13.5f);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(r.enabled ? UiStyle.INK : UiStyle.INK2);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nm.setPadding(dp(8), 0, 0, 0);
        tb.addView(nm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!r.enabled) {
            TextView off = new TextView(this);
            off.setText("未启用");
            off.setTextSize(10);
            off.setTypeface(Typeface.DEFAULT_BOLD);
            off.setTextColor(UiStyle.WAIT_INK);
            off.setBackground(UiStyle.roundRect(UiStyle.WAIT_BG, dp(6)));
            off.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = dp(7);
            tb.addView(off, olp);
        }
        top.addView(tb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 开关：项目自带的自绘 Switch（46x28，无 material 依赖）
        UiStyle.Switch sw = new UiStyle.Switch(this, r.enabled, () -> {
            boolean now = !r.enabled;
            r.enabled = now;
            RoleStore.setEnabled(HomeActivity.this, a.fpid, r.serverid, now);
            row.setBackgroundColor(now ? UiStyle.WHITE : UiStyle.SURFACE_2);
            nm.setTextColor(now ? UiStyle.INK : UiStyle.INK2);
            for (Runnable rr : syncSubs) rr.run();
            refreshAccounts();
        });
        top.addView(sw);

        row.addView(top);

        // 副行：区 · 今日 · 上次
        int[] ds = new com.carlos.vault.daily.LogStore(this).dayStats(a.fpid, r.serverid);
        TextView meta = new TextView(this);
        meta.setText("区 " + r.serverid + " · 今日 " + (ds == null ? 0 : ds[0]) + "/"
                + (ds == null ? 0 : ds[3]) + " · 上次登录 " + shortTime(lastTs));
        meta.setTextSize(10.5f);
        meta.setTextColor(UiStyle.INK2);
        meta.setPadding(0, dp(4), 0, 0);
        row.addView(meta);

        // 分隔线（最后一行不加，由调用方控制不完美也无妨）
        android.view.View div = new android.view.View(this);
        div.setBackgroundColor(UiStyle.LINE);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp2.topMargin = dp(11);
        row.addView(div, dlp2);
        return row;
    }

    /** 账号页加载遮罩：半透明覆盖 + 居中"加载中…"卡。 */
    private void showAccMask() {
        if (accMask == null) {
            android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
            fl.setBackgroundColor(UiStyle.BLACK_40);
            fl.setClickable(true);
            LinearLayout box = UiStyle.outlineCard(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(30), dp(22), dp(30), dp(22));
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
            box.addView(pb, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView t = new TextView(this);
            t.setText("加载中…");
            t.setTextSize(13);
            t.setTextColor(UiStyle.INK2);
            t.setPadding(0, dp(10), 0, 0);
            box.addView(t);
            fl.addView(box, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            accMask = fl;
        }
        if (accMask.getParent() instanceof ViewGroup) return;
        try {
            ((ViewGroup) pages[1].getParent()).addView(accMask,
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {
        }
    }

    private void hideAccMask() {
        if (accMask != null && accMask.getParent() instanceof ViewGroup) {
            ((ViewGroup) accMask.getParent()).removeView(accMask);
        }
    }

    // ================= Tab3 · 任务 =================

    private void buildTaskPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(16));
        sc.addView(box);
        pages[2].addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：任务 + N 项功能 · 已启用 M
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("日常");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        taskCap = new TextView(this);
        taskCap.setTextSize(11.5f);
        taskCap.setTextColor(UiStyle.INK2);
        tb.addView(taskCap);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(top);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);
        top.setLayoutParams(tlp);

        // 自动日常横幅卡（规范：图标 + 标题/副题 + 管理工作区小按钮）
        LinearLayout auto = UiStyle.outlineCard(this);
        auto.setOrientation(LinearLayout.HORIZONTAL);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        auto.setPadding(dp(14), dp(12), dp(12), dp(12));
        TextView aIcon = new TextView(this);
        aIcon.setText("✓");
        aIcon.setTextSize(16);
        aIcon.setTypeface(Typeface.DEFAULT_BOLD);
        aIcon.setTextColor(UiStyle.BRAND_TEXT);
        aIcon.setGravity(Gravity.CENTER);
        aIcon.setBackground(UiStyle.roundRect(UiStyle.BRAND_50, dp(10)));
        auto.addView(aIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout aMid = new LinearLayout(this);
        aMid.setOrientation(LinearLayout.VERTICAL);
        aMid.setPadding(dp(11), 0, dp(6), 0);
        bannerTitle = new TextView(this);
        bannerTitle.setText("自动日常");
        bannerTitle.setTextSize(12.5f);
        bannerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTitle.setTextColor(UiStyle.INK);
        aMid.addView(bannerTitle);
        bannerSub = new TextView(this);
        bannerSub.setTextSize(11);
        bannerSub.setTextColor(UiStyle.INK3);
        aMid.addView(bannerSub);
        auto.addView(aMid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 时段按钮（原日常页的定时窗口设置迁移至此）
        winBtn = new TextView(this);
        winBtn.setTextSize(12);
        winBtn.setTypeface(Typeface.DEFAULT_BOLD);
        winBtn.setTextColor(UiStyle.BRAND_TEXT);
        winBtn.setGravity(Gravity.CENTER);
        GradientDrawable wbg = new GradientDrawable();
        wbg.setColor(UiStyle.WHITE);
        wbg.setCornerRadius(dp(9));
        wbg.setStroke(dp(1), UiStyle.BRAND_300);
        winBtn.setBackground(wbg);
        winBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        UiStyle.press(winBtn);
        winBtn.setOnClickListener(v -> showWindowPicker());
        auto.addView(winBtn);
        // 自动日常开关（原日常页顶部开关迁移至此）
        autoSwitch = new UiStyle.Switch(this, false, () -> {
            AccountStore.Account cur = taskAccount == null ? firstWorkspace() : taskAccount;
            if (cur == null) {
                toast("没有可用账号");
                autoSwitch.setOn(false);
                return;
            }
            RoleStore.Schedule sch = RoleStore.schedule(this, cur.fpid);
            sch.autoEnabled = autoSwitch.isOn();
            RoleStore.saveSchedule(this, cur.fpid, sch);
            refreshTasks();
        });
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.leftMargin = dp(8);
        auto.addView(autoSwitch, swlp);
        box.addView(auto);
        // 点横幅主体 → 每日任务详情页（进度/领取）
        UiStyle.press(auto);
        auto.setOnClickListener(v -> openDaily(
                taskAccount == null ? firstWorkspace() : taskAccount, null));

        // 常驻在线行卡（24h 会话：心跳/自动互助；灰度期仅对主力号开启，与自动日常正交）
        LinearLayout keep = UiStyle.outlineCard(this);
        keep.setOrientation(LinearLayout.HORIZONTAL);
        keep.setGravity(Gravity.CENTER_VERTICAL);
        keep.setPadding(dp(14), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(8);
        keep.setLayoutParams(klp);
        TextView kIcon = new TextView(this);
        kIcon.setText("◉");
        kIcon.setTextSize(15);
        kIcon.setTypeface(Typeface.DEFAULT_BOLD);
        kIcon.setTextColor(UiStyle.BRAND_TEXT);
        kIcon.setGravity(Gravity.CENTER);
        kIcon.setBackground(UiStyle.roundRect(UiStyle.BRAND_50, dp(10)));
        keep.addView(kIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout kMid = new LinearLayout(this);
        kMid.setOrientation(LinearLayout.VERTICAL);
        kMid.setPadding(dp(11), 0, dp(6), 0);
        TextView kTitle = new TextView(this);
        kTitle.setText("24h 常驻在线");
        kTitle.setTextSize(12.5f);
        kTitle.setTypeface(Typeface.DEFAULT_BOLD);
        kTitle.setTextColor(UiStyle.INK);
        kMid.addView(kTitle);
        keepSub = new TextView(this);
        keepSub.setTextSize(11);
        keepSub.setTextColor(UiStyle.INK3);
        keepSub.setText("未开启");
        kMid.addView(keepSub);
        keep.addView(kMid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        keepSwitch = new UiStyle.Switch(this, false, () -> {
            AccountStore.Account cur = taskAccount == null ? firstWorkspace() : taskAccount;
            if (cur == null) {
                toast("没有可用账号");
                keepSwitch.setOn(false);
                return;
            }
            RoleStore.saveKeepOnline(this, cur.fpid, keepSwitch.isOn());
            // 立即生效：开→拉起会话；关→下次同步自动下线
            com.carlos.vault.daily.RoleSessionKeeper.get(this).start();
            com.carlos.vault.daily.RoleSessionKeeper.get(this).syncOnline();
            if (keepSwitch.isOn()) {
                promptBatteryWhitelist();
                promptNotificationAccess();
            }
            toast("常驻在线已" + (keepSwitch.isOn() ? "开启" : "关闭"));
            refreshTasks();
        });
        LinearLayout.LayoutParams kslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kslp.leftMargin = dp(8);
        keep.addView(keepSwitch, kslp);
        box.addView(keep);

        // 分类chips（带数量）
        HorizontalScrollView cs = new HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        LinearLayout crow = new LinearLayout(this);
        crow.setOrientation(LinearLayout.HORIZONTAL);
        crow.setPadding(0, dp(12), 0, dp(4));
        cs.addView(crow);
        box.addView(cs);
        for (int i = 0; i < FnRegistry.CAT_NAMES.length; i++) {
            final int cat = i;
            int cnt = FnRegistry.byCategory(i).size();
            TextView c = UiStyle.chipGray(this, FnRegistry.CAT_NAMES[i] + " " + cnt, i == 0);
            UiStyle.press(c);
            c.setOnClickListener(v -> {
                taskCat = cat;
                refreshTasks();
            });
            catChips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(7);
            crow.addView(c, lp);
        }

        taskRows = new LinearLayout(this);
        taskRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(taskRows);

        // 进阶工具
        box.addView(UiStyle.sectionTitle(this, "进阶工具"));
        LinearLayout tools = UiStyle.outlineCard(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView t1 = new TextView(this);
        t1.setText("排行榜 · 迁城 · 三消 · 练号 · 侦查 · 移民 等 14 项");
        t1.setTextSize(12);
        t1.setTextColor(UiStyle.INK2);
        tools.addView(t1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t2 = new TextView(this);
        t2.setText("查看 ›");
        t2.setTextSize(12);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(UiStyle.BRAND_TEXT);
        tools.addView(t2);
        UiStyle.press(tools);
        tools.setOnClickListener(v -> showTools());
        box.addView(tools);
    }

    /** 今日页常驻徽标 + 互助卡状态（数据源：RoleSessionKeeper 快照 + alliance_help modState；后台线程调用）。 */
    private void refreshKeepIndicators(AccountStore.Account a) {
        try {
            if (a == null || a.fpid == null) return;
            // 服务器模式：互助响应在 vault-server 上执行，状态经 fetchState 回写 mod:alliance_help，
            // 这里按本地同款格式渲染（文案 + 今日已助 + 荣耀）
            if (VaultSync.isServerMode(this)) {
                org.json.JSONObject o = VaultSync.cachedStatusOf(this, a.fpid);
                String st = o == null ? "" : o.optString("state", "");
                org.json.JSONObject mod = com.carlos.vault.daily.FnParams
                        .modState(this, a.fpid, currentRoleSid(), "alliance_help");
                String helpText = mod.optString("help_all", "");
                final String help;
                if (!com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(st)) {
                    help = RoleStore.keepOnline(this, a.fpid)
                            ? "服务器会话建立中…" : "开启「24h 常驻在线」后由服务器自动响应盟友互助";
                } else if (!helpText.isEmpty()) {
                    int helpNum = mod.optInt("help_num", 0);
                    long glory = mod.optLong("day_glory", 0);
                    help = helpText
                            + (helpNum > 0 ? " · 今日已助 " + helpNum : "")
                            + (glory > 0 ? " · 荣耀 " + glory : "");
                } else {
                    help = "服务器常驻在线 · 互助自动响应中";
                }
                runOnUiThread(() -> {
                    if (helpStatusText != null) helpStatusText.setText(help);
                });
                return;
            }
            final String help;
            long curSid = currentRoleSid();
            com.carlos.vault.daily.RoleSessionKeeper.LiveSession hit = null;
            for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession x
                    : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                if (!a.fpid.equals(x.fpid)) continue;
                if (!com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(x.engine.sessionState())) continue;
                if (hit == null || x.sid == curSid) hit = x;
                if (x.sid == curSid) break;
            }
            if (hit != null) {
                org.json.JSONObject mod = com.carlos.vault.daily.FnParams
                        .modState(this, a.fpid, hit.sid, "alliance_help");
                String st = mod.optString("help_all", "");
                int helpNum = mod.optInt("help_num", 0);
                long glory = mod.optLong("day_glory", 0);
                help = (st.isEmpty() ? "等待互助请求…" : st)
                        + (helpNum > 0 ? " · 今日已助 " + helpNum : "")
                        + (glory > 0 ? " · 荣耀 " + glory : "");
            } else {
                help = RoleStore.keepOnline(this, a.fpid)
                        ? "常驻连接建立中…" : "开启「24h 常驻在线」后自动响应盟友互助";
            }
            runOnUiThread(() -> {
                if (helpStatusText != null) helpStatusText.setText(help);
            });
        } catch (Throwable ignored) {
        }
    }

    /** 一键帮助全盟（原版手动 claim 同款：req_alliance_help_all；有在线会话直用，无则临时登录）。 */
    private void oneKeyHelpAll() {
        AccountStore.Account cur = currentRoleAccount();
        if (cur == null || cur.fpid == null || cur.fpid.isEmpty()
                || cur.token == null || cur.token.isEmpty()) {
            toast("当前账号没有 token");
            return;
        }
        final long sid = currentRoleSid();
        if (helpActionBtn != null) helpActionBtn.setEnabled(false);
        final AccountStore.Account facc = cur;
        new Thread(() -> {
            DailyResult r = null;
            try {
                r = DailyRunner.runSingle(HomeActivity.this, facc.fpid, facc.token, sid, "alliance_help");
            } catch (Throwable ignored) {
            }
            final DailyResult fr = r;
            runOnUiThread(() -> {
                if (helpActionBtn != null) helpActionBtn.setEnabled(true);
                if (fr == null) {
                    toast("互助执行异常");
                    return;
                }
                toast(fr.symbol() + " " + fr.message);
                refreshToday();
            });
        }, "one-key-help").start();
    }

    /** 顶号策略选择：人玩优先(30分钟探测) / 立即抢回 / 只让位。存 daily_sched:takeover_policy。 */
    private void showTakeoverPolicyPicker() {
        String cur = getSharedPreferences("daily_sched", MODE_PRIVATE)
                .getString("takeover_policy", "human_first");
        final String[] vals = {"human_first", "grab_back", "yield_only"};
        final String[] names = {"人玩优先（默认）——让位 30 分钟后探测回连",
                "立即抢回——协议优先，会踢掉刚登录的游戏客户端",
                "只让位——不再自动回连，手动重开"};
        int idx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) idx = i;
        new android.app.AlertDialog.Builder(this)
                .setTitle("顶号策略")
                .setSingleChoiceItems(names, idx, (d, w) -> {
                })
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        android.widget.ListView lv = ((android.app.AlertDialog) d).getListView();
                        int sel = lv.getCheckedItemPosition();
                        if (sel >= 0 && sel < vals.length) {
                            getSharedPreferences("daily_sched", MODE_PRIVATE).edit()
                                    .putString("takeover_policy", vals[sel]).apply();
                            toast("顶号策略已设为：" + names[sel].split("——")[0]);
                        }
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 服务器模式开关：自动化（常驻在线/互助）跑在 vault-server，本机只做号库 + token 补给 + 分身游玩。
     * 开启时全量上传身份库并停用本地常驻会话（防互踢）；关闭则回本地常驻。
     */
    private void showServerModePicker() {
        boolean on = VaultSync.isServerMode(this);
        final String[] names = {"开启——常驻在线跑在服务器（本机号库+分身游玩）",
                "关闭——回到本地常驻在线"};
        int idx = on ? 0 : 1;
        new android.app.AlertDialog.Builder(this)
                .setTitle("服务器模式")
                .setSingleChoiceItems(names, idx, (d, w) -> {
                })
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        android.widget.ListView lv = ((android.app.AlertDialog) d).getListView();
                        int sel = lv.getCheckedItemPosition();
                        boolean turnOn = sel == 0;
                        if (turnOn != VaultSync.isServerMode(this)) {
                            VaultSync.setServerMode(this, turnOn);
                            toast(turnOn ? "服务器模式已开启，正在上传身份库…"
                                    : "已回到本地常驻在线");
                        }
                        // 两个方向都要重对齐会话（开=停本地建会话，关=恢复本地会话）
                        new Thread(() -> {
                            try {
                                com.carlos.vault.daily.RoleSessionKeeper.get(HomeActivity.this).syncOnline();
                            } catch (Throwable ignored) {
                            }
                        }, "keeper-sync").start();
                        refreshAllPages();
                    } catch (Throwable ignored) {
                    }
                })
                .setNeutralButton("立即同步", (d, w) -> {
                    VaultSync.syncAll(this);
                    VaultSync.refreshStatus(this);
                    toast("已触发同步（见运行日志/状态行）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 游戏通知监听授权引导：反应式开盾（WAR/RECON→自动开盾）等事件链路依赖通知使用权。 */
    private void promptNotificationAccess() {
        try {
            if (com.carlos.vault.daily.GameNotifyListenerService.enabled(this)) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("建议开启游戏通知监听")
                    .setMessage("开启后号库能感知游戏原生通知：被打/被侦查自动开盾、部队返回补采集、治疗完成续批。\n\n下一步在系统「通知使用权」里勾选「无尽号库」。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            com.carlos.vault.daily.GameNotifyListenerService.openSettings(this);
                        } catch (Throwable ignored) {
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 24h 常驻在线的电池白名单引导：未加白名单时弹一次系统申请（Doze 会掐后台心跳连接）。 */
    private void promptBatteryWhitelist() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("建议关闭电池优化")
                    .setMessage("24h 常驻在线需要保持后台连接。系统默认会优化（限制）号库的后台网络，"
                            + "导致心跳断续、互助响应延迟。\n\n下一步的系统弹窗里选「允许」即可。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Throwable ignored) {
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 常驻在线状态文案：当前账号各角色会话态（在线/重连中/被顶号暂停/需重导token）。 */
    private String keepStatusText() {
        try {
            AccountStore.Account cur = taskAccount == null ? firstWorkspace() : taskAccount;
            if (cur == null || !RoleStore.keepOnline(this, cur.fpid)) return "未开启";
            // 服务器模式：渲染 vault-server 回传的会话态
            if (VaultSync.isServerMode(this)) {
                org.json.JSONObject o = cur.fpid == null ? null : VaultSync.cachedStatusOf(this, cur.fpid);
                if (o == null) return "服务器状态同步中…";
                String st = o.optString("state", "offline");
                String sid = o.optString("sid", "");
                String head = sid.isEmpty() ? "" : sid + "区·";
                switch (st) {
                    case "online": return head + "在线 · 服务器";
                    case "recovering": return head + "重连中 · 服务器";
                    case "takeover": return head + "被顶号暂停 · 服务器";
                    case "need_token": return head + "需重导token · 服务器";
                    default: return head + st + " · 服务器";
                }
            }
            StringBuilder sb = new StringBuilder();
            for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                    : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                if (!cur.fpid.equals(ls.fpid)) continue;
                // 状态字面量与 WjdrEngine.STATE_* 常量一致（online/recovering/takeover/need_token）
                String st = ls.engine.sessionState();
                String s;
                switch (st) {
                    case "online": s = ls.sid + "区·在线"; break;
                    case "recovering": s = ls.sid + "区·重连中"; break;
                    case "takeover": s = ls.sid + "区·被顶号暂停"; break;
                    case "need_token": s = ls.sid + "区·需重导token"; break;
                    default: s = ls.sid + "区·" + st; break;
                }
                if (sb.length() > 0) sb.append("  ");
                sb.append(s);
            }
            return sb.length() == 0 ? "连接建立中…" : sb.toString();
        } catch (Throwable t) {
            return "—";
        }
    }

    /** 服务器回传 sid 容错解析：非数字（脏缓存/旧版字段）返回 -1，绝不令在线探针线程死亡。 */
    private static long parseSidSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    private void refreshTasks() {
        if (taskRows == null) return;
        taskAccount = currentRoleAccount();
        taskRows.removeAllViews();
        if (taskAccount == null) {
            taskRows.addView(emptyTip("没有可用账号 —— 先在「我的」导入 token"));
            return;
        }
        long sid = currentRoleSid();
        LogStore ls = new LogStore(this);
        com.carlos.vault.daily.DailyConfigStore cfgStore = new com.carlos.vault.daily.DailyConfigStore(this);
        Map<String, Boolean> cfg = cfgStore.roleConfig(taskAccount.fpid, sid);

        // 顶栏 caption：N 项功能 · 已启用 M；横幅：工作区角色数 + 自动日常开关态
        int total = FnRegistry.all().size(), enabled = 0;
        for (FnRegistry.Fn fn : FnRegistry.all()) {
            Boolean on = cfg == null ? null : cfg.get(fn.id);
            if (on == null ? (fn.implemented && defOn(fn.id)) : on) enabled++;
        }
        if (taskCap != null) taskCap.setText(total + " 项功能 · 已启用 " + enabled);
        // 分类chips：选中态跟随 taskCat（修复选中效果不切换）；药丸样式与账号页统一
        for (int i = 0; i < catChips.size(); i++) {
            TextView chip = catChips.get(i);
            boolean sel = i == taskCat;
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(999));
            if (sel) {
                pill.setColor(UiStyle.BRAND);
                pill.setStroke(dp(1), UiStyle.BRAND);
            } else {
                pill.setColor(UiStyle.WHITE);
                pill.setStroke(dp(1), UiStyle.LINE);
            }
            chip.setBackground(pill);
            chip.setTextColor(sel ? UiStyle.WHITE : UiStyle.INK2);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        }
        int wsCnt = workspaceAccounts().size();
        // 工作区口径 = 启用角色数（与执行端 runAccountRoles / RoleSessionKeeper 一致）。
        // 旧文案把账号数冒充角色数（2 账号 4 启用角色时显示"仅对工作区的 2 个角色执行"）。
        int wsRoleCnt = 0;
        for (AccountStore.Account a : workspaceAccounts()) {
            try {
                for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                    if (r.enabled) wsRoleCnt++;
                }
            } catch (Throwable ignored) {
            }
        }
        boolean anyAuto = false;
        for (AccountStore.Account a : workspaceAccounts()) {
            if (RoleStore.schedule(this, a.fpid).autoEnabled) { anyAuto = true; break; }
        }
        if (bannerTitle != null) {
            bannerTitle.setText("自动日常 · 已" + (anyAuto ? "开启" : "关闭"));
            if (wsRoleCnt > 0) {
                bannerSub.setText("仅对工作区的 " + wsRoleCnt + " 个启用角色执行");
            } else if (wsCnt > 0) {
                // 账号在工作区但没启用任何角色：执行端会回落到每号主角色单跑
                bannerSub.setText("仅对工作区 " + wsCnt + " 个账号的主角色执行");
            } else {
                bannerSub.setText("先在账号页把角色加入工作区");
            }
            int[] win = RoleStore.window(this);
            winBtn.setText("时段 " + win[0] + "-" + win[1] + "点");
            if (taskAccount != null && autoSwitch != null) {
                autoSwitch.setOn(RoleStore.schedule(this, taskAccount.fpid).autoEnabled);
            }
        }
        if (keepSub != null) keepSub.setText(keepStatusText());
        if (keepSwitch != null && taskAccount != null) {
            keepSwitch.setOn(RoleStore.keepOnline(this, taskAccount.fpid));
        }

        for (final FnRegistry.Fn fn : FnRegistry.byCategory(taskCat)) {
            final boolean fnEnabled = fnOn(fn);
            // 单卡：头部（图标+名称徽章+摘要+开关+箭头）+ 展开区（同卡 border-top，规范 .task）
            LinearLayout card = UiStyle.outlineCard(this);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(8);
            card.setLayoutParams(clp);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(14), dp(13), dp(14), dp(13));

            TextView icon = new TextView(this);
            icon.setText(iconOf(fn));
            icon.setTextSize(15);
            icon.setTextColor(fnEnabled ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(UiStyle.roundRect(fnEnabled ? UiStyle.BRAND_50 : UiStyle.IDLE_BG, dp(10)));
            head.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(12), 0, dp(6), 0);
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(fn.name);
            name.setTextSize(13.5f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(fnEnabled ? UiStyle.INK : UiStyle.INK3);
            nameRow.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            nameRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            int stat = ls.status(taskAccount.fpid, sid, fn.id);
            String badgeType = stat == LogStore.ST_DONE ? "ok"
                    : stat == LogStore.ST_RUNNING ? "run"
                    : stat == LogStore.ST_FAILED ? "err" : "idle";
            String badgeText = stat == LogStore.ST_DONE ? "已完成"
                    : stat == LogStore.ST_RUNNING ? "进行中"
                    : stat == LogStore.ST_FAILED ? "失败" : (fn.implemented ? "未开始" : "规划中");
            nameRow.addView(UiStyle.badge(this, badgeType, badgeText));
            body.addView(nameRow);
            TextView sum = new TextView(this);
            sum.setText(sumOf(fn, sid));
            sum.setTextSize(11.5f);
            sum.setTextColor(fnEnabled ? UiStyle.INK2 : UiStyle.INK3);
            sum.setMaxLines(1);
            sum.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.addView(sum);
            head.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            final UiStyle.Switch[] fnRef = new UiStyle.Switch[1];
            fnRef[0] = new UiStyle.Switch(this, fnEnabled,
                    () -> setFnOn(fn, fnRef[0].isOn()));
            head.addView(fnRef[0]);
            TextView chev = new TextView(this);
            chev.setText("▾");
            chev.setTextSize(15);
            chev.setTextColor(UiStyle.INK2);
            chev.setGravity(Gravity.CENTER);
            chev.setPadding(dp(10), 0, 0, 0);
            boolean open = fn.id.equals(expandedFn);
            chev.setRotation(open ? 180f : 0f);
            head.addView(chev);
            UiStyle.press(head);
            head.setOnClickListener(v -> {
                expandedFn = open ? null : fn.id;
                refreshTasks();
            });
            card.addView(head);

            if (open) {
                View div = new View(this);
                div.setBackgroundColor(UiStyle.LINE);
                card.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                LinearLayout detail = new LinearLayout(this);
                detail.setOrientation(LinearLayout.VERTICAL);
                detail.setPadding(dp(14), dp(4), dp(14), dp(12));
                detail.addView(com.carlos.vault.daily.SubCardUi.build(this, taskAccount.fpid, sid, fn.id));
                // 展开即刷新：借常驻会话收取该功能最新状态（只读），完成后整页重渲染
                refreshFnOnExpand(taskAccount.fpid, sid, fn.id);
                TextView deep = new TextView(this);
                deep.setText("更多（执行/日志/时间窗）›");
                deep.setTextSize(12);
                deep.setTextColor(UiStyle.BRAND_TEXT);
                deep.setGravity(Gravity.RIGHT);
                deep.setPadding(0, dp(8), 0, dp(2));
                deep.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                UiStyle.press(deep);
                deep.setOnClickListener(v -> openFnDetail(taskAccount, fn.id));
                detail.addView(deep);
                card.addView(detail);
            }
            taskRows.addView(card);
        }
    }

    /** 任务行图标：常见功能用语义 emoji，未命中按分类兜底。 */
    private String iconOf(FnRegistry.Fn fn) {
        switch (fn.id) {
            case "login_rewards": return "🎁";
            case "mail": return "✉️";
            case "supply": return "📦";
            case "daily_mission": return "✅";
            case "adventure": return "💤";
            case "activity_rewards": return "🎉";
            case "commander_gift": return "🎖";
            case "act_endless": return "🏁";
            case "town_affairs": return "🏛";
            case "medic": return "⛑";
            case "barracks": return "⚔";
            case "research": return "🔬";
            case "building": return "🏗";
            case "pet": return "🐾";
            case "expert": return "🧭";
            case "homeland": return "🏝";
            case "fire_crystal": return "🔥";
            case "climbtower": return "🗼";
            case "arena": return "🏟";
            case "fishing": case "act_fishing": return "🎣";
            case "act_frostfire": return "⛏";
            case "bear": return "🐻";
        }
        switch (fn.cat) {
            case FnRegistry.CAT_REWARD: return "🎁";
            case FnRegistry.CAT_TOWN: return "🏛";
            case FnRegistry.CAT_ACTIVITY: return "🎯";
            case FnRegistry.CAT_FIELD: return "🌲";
            case FnRegistry.CAT_ALLIANCE: return "🤝";
            case FnRegistry.CAT_SHOP: return "🛒";
            default: return "▤";
        }
    }

    /** 任务行一行摘要：优先实时状态行的第一句，否则功能描述。 */
    private String sumOf(FnRegistry.Fn fn, long sid) {
        try {
            String raw = getSharedPreferences("daily_state", MODE_PRIVATE)
                    .getString("mod:" + taskAccount.fpid + ":" + sid + ":" + fn.id, null);
            if (raw != null) {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.endsWith("_ts") || k.endsWith("_trg") || k.startsWith("max_")) continue;
                    String v = o.optString(k, "");
                    if (!v.isEmpty() && v.length() <= 30) return v;
                }
            }
        } catch (Exception ignored) {
        }
        return fn.desc.length() > 18 ? fn.desc.substring(0, 18) + "…" : fn.desc;
    }

    private void showTools() {
        boolean logOn = ProtoLogger.on(this);
        String[] items = {
                "协议监听：" + (logOn ? "已开启（点此停止）" : "已关闭（点此开启）"),
                "敌情监控：标记敌人出征 → 企业微信+短信",
                "P1 协议探针：使馆/道具/加速链/驻防/迁城（开发用）",
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("工具箱")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        boolean turnOn = !logOn;
                        ProtoLogger.setOn(this, turnOn);
                        if (turnOn) ProtoLogger.reattachAll(this);
                        toast(turnOn ? "协议监听已开启\n（原生游戏里玩一轮后退出，稍后我来拉日志分析）"
                                : "协议监听已停止");
                        showTools();
                    } else if (w == 1) {
                        showWatchConfig();
                    } else {
                        showProbe();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ================= 敌情监控（EnemyWatch：标记敌人行军 → 企业微信 + 短信） =================

    private void showWatchConfig() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(6));

        TextView tip = new TextView(this);
        tip.setText("标记敌人（按服分号观察）。名单每行一条：服:uid 备注\n例如 888:57347368 仇人A（备注可空，服号缺省按888）");
        tip.setTextSize(12);
        box.addView(tip);

        android.widget.CheckBox cbOn = new android.widget.CheckBox(this);
        cbOn.setText("敌情监控开启（挂在全部在线会话上）");
        cbOn.setChecked(com.carlos.vault.daily.EnemyWatchStore.enabled(this));
        box.addView(cbOn);

        EditText etList = new EditText(this);
        etList.setHint("888:57347368 仇人A\n888:123861751");
        etList.setTextSize(13);
        StringBuilder pre = new StringBuilder();
        for (com.carlos.vault.daily.EnemyWatchStore.Entry e : com.carlos.vault.daily.EnemyWatchStore.entries(this)) {
            pre.append(e.kid).append(':').append(e.uid);
            if (!e.note.isEmpty()) pre.append(' ').append(e.note);
            pre.append('\n');
        }
        etList.setText(pre.toString());
        box.addView(etList);

        EditText etWebhook = new EditText(this);
        etWebhook.setHint("企业微信群机器人 Webhook URL");
        etWebhook.setTextSize(13);
        etWebhook.setText(com.carlos.vault.daily.EnemyWatchStore.webhook(this));
        box.addView(etWebhook);

        EditText etPhone = new EditText(this);
        etPhone.setHint("短信接收手机号");
        etPhone.setTextSize(13);
        etPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        etPhone.setText(com.carlos.vault.daily.EnemyWatchStore.smsPhone(this));
        box.addView(etPhone);

        android.widget.CheckBox cbWecom = new android.widget.CheckBox(this);
        cbWecom.setText("企业微信通知");
        cbWecom.setChecked(com.carlos.vault.daily.EnemyWatchStore.wecomOn(this));
        box.addView(cbWecom);
        android.widget.CheckBox cbSms = new android.widget.CheckBox(this);
        cbSms.setText("短信通知");
        cbSms.setChecked(com.carlos.vault.daily.EnemyWatchStore.smsOn(this));
        box.addView(cbSms);
        android.widget.CheckBox cbEsc = new android.widget.CheckBox(this);
        cbEsc.setText("短信仅升级事件（敌人加速时）；不勾 = 所有敌情都发短信");
        cbEsc.setChecked(com.carlos.vault.daily.EnemyWatchStore.smsEscalateOnly(this));
        box.addView(cbEsc);

        TextView status = new TextView(this);
        status.setText("最近事件：\n" + joinRecent());
        status.setTextSize(11);
        box.addView(status);

        sc.addView(box);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("敌情监控 · 标记敌人出征即推送（企业微信+短信）")
                .setView(sc)
                .setPositiveButton("保存", (d, w) -> {
                    int bad = com.carlos.vault.daily.EnemyWatchStore.save(this,
                            cbOn.isChecked(), etWebhook.getText().toString(), etPhone.getText().toString(),
                            cbWecom.isChecked(), cbSms.isChecked(), cbEsc.isChecked(),
                            etList.getText().toString());
                    com.carlos.vault.daily.EnemyWatch.attachAll(this);
                    toast(bad > 0 ? "已保存，" + bad + " 行格式非法被忽略"
                            : "已保存，已对在线会话即时挂载");
                })
                .setNeutralButton("测试推送", (d, w) -> {
                    int bad = com.carlos.vault.daily.EnemyWatchStore.save(this,
                            cbOn.isChecked(), etWebhook.getText().toString(), etPhone.getText().toString(),
                            cbWecom.isChecked(), cbSms.isChecked(), cbEsc.isChecked(),
                            etList.getText().toString());
                    com.carlos.vault.daily.EnemyWatch.attachAll(this);
                    com.carlos.vault.daily.EnemyWatch.sendTest(this);
                    toast(bad > 0 ? "已保存并发送测试（" + bad + " 行非法被忽略）" : "已保存并发送测试推送");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private String joinRecent() {
        List<String> list = com.carlos.vault.daily.EnemyWatchStore.recent(this);
        if (list.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(list.size(), 12); i++) sb.append(list.get(i)).append('\n');
        return sb.toString();
    }

    // ================= P1 协议探针（GuardProbe：开发用，烧真实道具前有确认） =================

    private void showProbe() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(6));

        TextView sess = new TextView(this);
        List<String> online = com.carlos.vault.daily.GuardProbe.onlineSessions(this);
        sess.setText("在线会话（探针取第一个在线）：\n" + (online.isEmpty() ? "（无）" : joinLines(online)));
        sess.setTextSize(11);
        box.addView(sess);

        box.addView(btn("① 使馆查询（无消耗 · A-13 容量字段）", v ->
                com.carlos.vault.daily.GuardProbe.embassyQuery(this, null)));
        box.addView(btn("② 道具快照（无消耗 · A-10 库存读数）", v ->
                com.carlos.vault.daily.GuardProbe.itemSnapshot(this, null)));
        box.addView(btn("③ 加速链实测（烧≤8个加速道具 · A-3/15/16/17+召回）", v ->
                confirmThen("将发射一条采集行军并连用最多 8 个加速道具直至触底，随后召回。\n确认执行？",
                        () -> com.carlos.vault.daily.GuardProbe.speedupChain(this, null))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText px = new EditText(this);
        px.setHint("盟友城x");
        px.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        px.setText("687");
        EditText py = new EditText(this);
        py.setHint("盟友城y");
        py.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        py.setText("698");
        row.addView(px, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(py, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        box.addView(btn("④ 驻防发起 type=5（A-2 · 打盟友城，发后不召回）", v -> {
            long gx = 687, gy = 698;
            try { gx = Long.parseLong(px.getText().toString().trim()); } catch (Throwable ignored) { }
            try { gy = Long.parseLong(py.getText().toString().trim()); } catch (Throwable ignored) { }
            final long fx = gx, fy = gy;
            confirmThen("将向盟友城 (" + fx + "," + fy + ") 发起一次真实驻防（type=5）。\n确认执行？",
                    () -> com.carlos.vault.daily.GuardProbe.garrisonLaunch(this, null, fx, fy));
        }));
        box.addView(btn("⑤ 加速链·复用在途行军（烧≤8个加速道具 · 推荐）", v ->
                confirmThen("将复用自动化最近一条在途行军：连用最多 8 个加速道具直至触底，随后召回。\n"
                        + "若提示无在途行军，等日常采集自动出征后再点。\n确认执行？",
                        () -> com.carlos.vault.daily.GuardProbe.speedupExisting(this, null))));
        box.addView(btn("⑥ 迁城实测（烧高级迁城 · A-23 c2s）", v ->
                confirmThen("将消耗高级迁城道具迁到附近 (+1,0)。\n确认执行？",
                        () -> com.carlos.vault.daily.GuardProbe.moveCity(this, null))));
        EditText pu = new EditText(this);
        pu.setHint("撤防目标uid");
        pu.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pu.setText("20718040");
        box.addView(pu);
        box.addView(btn("⑦ 撤回驻防（embassy_repatriation · 无消耗）", v ->
                confirmThen("撤回我方在该盟友城的驻防（req_embassy_repatriation {target_uid}）。\n"
                        + "确认执行？", () -> com.carlos.vault.daily.GuardProbe.repatriateProbe(this, null, pu.getText().toString().trim()))));

        TextView result = new TextView(this);
        result.setText("探针输出：\n" + joinProbe());
        result.setTextSize(11);
        box.addView(result);

        sc.addView(box);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("P1 协议探针（结果同步落 proto_log）")
                .setView(sc)
                .setPositiveButton("刷新结果", (d, w) -> showProbe())
                .setNegativeButton("关闭", null)
                .show();
    }

    private android.widget.Button btn(String text, View.OnClickListener onClick) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setOnClickListener(onClick);
        return b;
    }

    private void confirmThen(String msg, Runnable action) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("消耗确认")
                .setMessage(msg)
                .setPositiveButton("执行", (d, w) -> action.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private String joinLines(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(list.size(), 8); i++) sb.append(list.get(i)).append('\n');
        return sb.toString();
    }

    private String joinProbe() {
        List<String> list = com.carlos.vault.daily.GuardProbe.recent();
        if (list.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(list.size(), 20); i++) sb.append(list.get(i)).append('\n');
        return sb.toString();
    }

    // ================= Tab4 · 我的 =================

    private void buildMinePage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(24));
        sc.addView(box);
        pages[3].addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 授权卡（品牌渐变 + 大数字）
        LinearLayout lic = new LinearLayout(this);
        lic.setOrientation(LinearLayout.VERTICAL);
        lic.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable lg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{UiStyle.BRAND_PRESS, UiStyle.BRAND});
        lg.setCornerRadius(dp(20));
        lic.setBackground(lg);
        TextView l1 = new TextView(this);
        l1.setText("无尽号库 · 授权");
        l1.setTextSize(12);
        l1.setTextColor(UiStyle.BRAND_100);
        lic.addView(l1);
        LinearLayout ln = new LinearLayout(this);
        ln.setOrientation(LinearLayout.HORIZONTAL);
        ln.setGravity(Gravity.BOTTOM);
        licenseDays = new TextView(this);
        licenseDays.setTextSize(30);
        licenseDays.setTypeface(Typeface.DEFAULT_BOLD);
        licenseDays.setTextColor(UiStyle.WHITE);
        ln.addView(licenseDays);
        TextView lu = new TextView(this);
        lu.setText(" 天");
        lu.setTextSize(13);
        lu.setTextColor(UiStyle.BRAND_100);
        ln.addView(lu);
        lic.addView(ln);
        TextView renew = new TextView(this);
        renew.setText("续期 / 兑换码 ›");
        renew.setTextSize(12.5f);
        renew.setTypeface(Typeface.DEFAULT_BOLD);
        renew.setTextColor(UiStyle.WHITE);
        renew.setPadding(0, dp(8), 0, 0);
        UiStyle.press(renew);
        renew.setOnClickListener(v -> showLicenseMenu());
        lic.addView(renew);
        box.addView(lic);

        box.addView(UiStyle.sectionTitle(this, "运行"));
        box.addView(mineRow("🗒 运行日志", "最近一次执行与逐号结果", this::showRunLog));
        box.addView(mineRow("⚖️ 顶号策略", "协议会话被游戏客户端顶下线时怎么办", this::showTakeoverPolicyPicker));
        box.addView(mineRow("☁️ 服务器模式",
                (VaultSync.isServerMode(this) ? "已开启 · " : "未开启 · ")
                        + "自动化跑在服务器，本机做号库与分身", this::showServerModePicker));
        box.addView(buildHotUpdateRow());
        box.addView(mineRow("⏰ 定时与保活", "前台服务 · 精确闹钟 · 通知唤醒", () -> {
            AccountStore.Account cur = firstWorkspace();
            if (cur != null) openDaily(cur, null);
            toast("在弹出页顶部设置自动日常");
        }));
        box.addView(UiStyle.sectionTitle(this, "数据"));
        box.addView(mineRow("＋ 新增账号", "手动添加一个账号", this::showCreateEntry));
        box.addView(mineRow("⇪ 批量导入 token", "格式：备注|号ID----token", this::showBatchImport));
        box.addView(mineRow("◫ 经典视图", "旧版账号卡片墙（排序/分组）", this::openOld));
        box.addView(UiStyle.sectionTitle(this, "初始化"));
        box.addView(mineRow("🧭 初始化向导", "克隆 → 首启 → 重置 → 存基线", this::showWizard));
        box.addView(mineRow("📥 克隆软件", "把无尽冬日装进号库", () -> onWizardStep(0)));
        box.addView(mineRow("💚 微信支付", "容器微信登录 / 充值", this::doWechatPay));
        box.addView(UiStyle.sectionTitle(this, "危险区"));
        LinearLayout danger = UiStyle.outlineCard(this);
        danger.setOrientation(LinearLayout.HORIZONTAL);
        danger.setGravity(Gravity.CENTER_VERTICAL);
        danger.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(UiStyle.WHITE);
        dg.setCornerRadius(dp(16));
        dg.setStroke(dp(1), UiStyle.ERR_BORDER);
        danger.setBackground(dg);
        TextView d1 = new TextView(this);
        d1.setText("清除容器数据");
        d1.setTextSize(13);
        d1.setTypeface(Typeface.DEFAULT_BOLD);
        d1.setTextColor(UiStyle.ERR_INK);
        danger.addView(d1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView d2 = new TextView(this);
        d2.setText("会作废全部账号，不可恢复");
        d2.setTextSize(11.5f);
        d2.setTextColor(UiStyle.INK2);
        danger.addView(d2);
        UiStyle.press(danger);
        danger.setOnClickListener(v -> new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("清除容器数据")
                .setMessage("该操作会作废容器内全部账号（快照仍保留），且不可恢复。\n\n如需彻底重置，请走「初始化向导」第 3 步。")
                .setPositiveButton("打开向导", (dd, w) -> showWizard())
                .setNegativeButton("取消", null)
                .show());
        box.addView(danger);
    }

    /** 热更新检查行：显示本地热更版本，点击对比服务器版本，不一致自动拉取应用。 */
    private TextView hotSub;

    private View buildHotUpdateRow() {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("🔄 检查热更");
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        mid.addView(t);
        hotSub = new TextView(this);
        hotSub.setText(hotSubText());
        hotSub.setTextSize(11.5f);
        hotSub.setTextColor(UiStyle.INK2);
        mid.addView(hotSub);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(UiStyle.INK2);
        row.addView(arrow);
        UiStyle.press(row);
        row.setOnClickListener(v -> runHotUpdateCheck());
        return row;
    }

    private String hotSubText() {
        long v = com.carlos.vault.daily.HotConfig.version();
        String url = getSharedPreferences("hotconfig", MODE_PRIVATE).getString("hot_url", "");
        boolean hasUrl = url != null && !url.isEmpty();
        String local = v > 0 ? ("本地热更 v" + v) : "本地无热更包";
        return hasUrl ? (local + " · 点击对比服务器") : (local + " · 未配置热更源");
    }

    private void runHotUpdateCheck() {
        final android.app.ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("检查热更…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            com.carlos.vault.daily.HotConfig.CheckResult r =
                    com.carlos.vault.daily.HotConfig.checkServer(this);
            runOnUiThread(() -> {
                pd.dismiss();
                String msg;
                switch (r.code) {
                    case 0:
                        msg = "热更已更新：v" + r.localVersion + " → v" + r.serverVersion;
                        break;
                    case 1:
                        msg = r.serverVersion > 0 ? "已是最新 v" + r.serverVersion : "已是最新";
                        break;
                    case 2:
                        msg = "未配置热更源（prefs hot_url / adb 广播 --es url 均可设置）";
                        break;
                    default:
                        msg = "拉取失败，请检查网络后重试";
                }
                toast(msg);
                refreshAllPages();   // 重建我的页，刷新版本号显示
            });
        }, "hot-check").start();
    }

    private View mineRow(String title, String sub, Runnable onClick) {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        mid.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11.5f);
        s.setTextColor(UiStyle.INK2);
        mid.addView(s);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(UiStyle.INK2);
        row.addView(arrow);
        UiStyle.press(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void refreshMine() {
        if (licenseManager == null || licenseDays == null) return;
        LicenseTicket t = licenseManager.currentTicket();
        if (t != null && t.expireAt >= 0 && licenseManager.localUsable()) {
            long days = Math.max(0, (t.expireAt - System.currentTimeMillis()) / 86400000L);
            licenseDays.setText(t.expireAt <= 0 ? "∞" : String.valueOf(days));
        } else {
            licenseDays.setText("--");
        }
    }

    private void showRunLog() {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "daily_log.txt");
            if (f.exists()) {
                java.util.List<String> lines = new ArrayList<>();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                String l;
                while ((l = br.readLine()) != null) {
                    lines.add(l);
                    if (lines.size() > 200) lines.remove(0);
                }
                br.close();
                for (String s2 : lines) sb.append(s2, 0, Math.min(120, s2.length())).append('\n');
            }
        } catch (Exception ignored) {
        }
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        tv.setTextSize(11);
        tv.setText(sb.length() == 0 ? "暂无日志" : sb.toString());
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        sc.addView(tv);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("运行日志（最近 200 行）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openOld() {
        startActivity(new android.content.Intent(this, AccountVaultActivity.class));
    }

    /** 全部"日常"入口统一去功能详情页；moduleId 空 = 每日任务页（旧日常页已废弃）。 */
    private void openDaily(AccountStore.Account a, String moduleId) {
        if (a == null) {
            toast("没有可用账号");
            return;
        }
        openFnDetail(a, moduleId == null ? "daily_mission" : moduleId);
    }

    /** 功能详情页（新）：实时状态 + 子项配置 + 运行记录 + 领取内容。 */
    private void openFnDetail(AccountStore.Account a, String fnId) {
        openFnDetail(a, fnId, a == null ? 0
                : (a.fpid != null && a.fpid.equals(currentRoleAccount() == null ? "" : currentRoleAccount().fpid)
                        ? currentRoleSid() : activeSid(a)));
    }

    /** 指定角色的功能详情页（角色级入口）。 */
    private void openFnDetail(AccountStore.Account a, String fnId, long sid) {
        if (a == null) {
            toast("没有可用账号");
            return;
        }
        android.content.Intent i = new android.content.Intent(this,
                com.carlos.vault.daily.FunctionActivity.class);
        i.putExtra(com.carlos.vault.daily.FunctionActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(com.carlos.vault.daily.FunctionActivity.EXTRA_FN_ID, fnId);
        i.putExtra(com.carlos.vault.daily.FunctionActivity.EXTRA_SERVERID, sid);
        startActivity(i);
    }

    private View emptyTip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(UiStyle.INK2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(24), 0, dp(24));
        return t;
    }

    /** 批量操作条（选中>0 时从底部显示：跑日常 / 取消）。 */
    private void refreshBatchBar() {
        if (batchBar == null) {
            batchBar = new LinearLayout(this);
            batchBar.setOrientation(LinearLayout.HORIZONTAL);
            batchBar.setGravity(Gravity.CENTER_VERTICAL);
            batchBar.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable g = new GradientDrawable();
            g.setColor(UiStyle.INK);   // 规范 .batchbar：ink 深底
            g.setCornerRadius(dp(16));
            batchBar.setBackground(g);
            batchBar.setElevation(dp(8));
            batchBar.setTag("batchText");
        }
        if (selectedAccounts.isEmpty()) {
            if (batchBar.getParent() instanceof ViewGroup) ((ViewGroup) batchBar.getParent()).removeView(batchBar);
            return;
        }
        batchBar.removeAllViews();
        TextView t = new TextView(this);
        t.setText(selectedAccounts.size() + " 个已选");
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.WHITE);
        batchBar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 跑日常
        TextView run = batchBtn("跑日常", UiStyle.WHITE_14, UiStyle.WHITE);
        UiStyle.press(run);
        run.setOnClickListener(v -> {
            List<AccountStore.Account> sel = new ArrayList<>();
            for (AccountStore.Account a : accounts) {
                if (selectedAccounts.contains(a.id)) sel.add(a);
            }
            selectedAccounts.clear();
            runList(sel);
        });
        batchBar.addView(run);
        // 导出：账号名 + fpid 文本复制到剪贴板
        TextView exp = batchBtn("导出", UiStyle.WHITE_14, UiStyle.WHITE);
        UiStyle.press(exp);
        exp.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (AccountStore.Account a : accounts) {
                if (selectedAccounts.contains(a.id))
                    sb.append(a.label).append('\t').append(a.fpid).append('\n');
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("accounts", sb.toString().trim()));
            toast("已复制 " + selectedAccounts.size() + " 个账号（名称+UID）");
        });
        batchBar.addView(exp);
        // 删除（危险：确认后从号库移除）
        TextView del = batchBtn("删除", UiStyle.ERR_SOLID, UiStyle.WHITE);
        UiStyle.press(del);
        del.setOnClickListener(v -> {
            final int n = selectedAccounts.size();
            new AlertDialog.Builder(HomeActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("删除 " + n + " 个账号？")
                    .setMessage("仅从号库移除账号记录，不影响游戏与分身数据。")
                    .setPositiveButton("删除", (d, w) -> {
                        for (String id : new ArrayList<>(selectedAccounts)) store.remove(id);
                        selectedAccounts.clear();
                        accounts = store.all();
                        refreshAllPages();
                        toast("已删除 " + n + " 个账号");
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        batchBar.addView(del);
        if (!(batchBar.getParent() instanceof ViewGroup)) {
            android.widget.FrameLayout.LayoutParams bp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            bp.leftMargin = dp(18);
            bp.rightMargin = dp(18);
            bp.bottomMargin = dp(18);
            try {
                ((ViewGroup) pages[1].getParent()).addView(batchBar, bp);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 自动日常执行时段（原日常页设置迁移）：起止小时选择器。 */
    private void showWindowPicker() {
        int[] w = RoleStore.window(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        final android.widget.NumberPicker sPick = new android.widget.NumberPicker(this);
        sPick.setMinValue(0);
        sPick.setMaxValue(23);
        sPick.setValue(w[0]);
        final android.widget.NumberPicker ePick = new android.widget.NumberPicker(this);
        ePick.setMinValue(1);
        ePick.setMaxValue(24);
        ePick.setValue(w[1]);
        TextView l1 = new TextView(this);
        l1.setText("每天 ");
        l1.setTextSize(13);
        TextView l2 = new TextView(this);
        l2.setText(" 点 到 ");
        l2.setTextSize(13);
        TextView l3 = new TextView(this);
        l3.setText(" 点");
        l3.setTextSize(13);
        box.addView(l1);
        box.addView(sPick);
        box.addView(l2);
        box.addView(ePick);
        box.addView(l3);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("自动日常执行时段")
                .setView(box)
                .setPositiveButton("保存", (d, x) -> {
                    int st = sPick.getValue(), en = ePick.getValue();
                    if (en <= st) {
                        toast("结束小时要晚于开始小时");
                        return;
                    }
                    RoleStore.saveWindow(this, st, en);
                    // 窗口变更即时生效：开自动的账号清掉旧 nextRunAt，调度器下轮 tick 按新窗口重算
                    for (AccountStore.Account a : accounts) {
                        if (a == null || a.fpid == null) continue;
                        RoleStore.Schedule sc = RoleStore.schedule(this, a.fpid);
                        if (sc.autoEnabled) {
                            sc.nextRunAt = 0;
                            RoleStore.saveSchedule(this, a.fpid, sc);
                        }
                    }
                    com.carlos.vault.daily.DailySchedulerService.ensureStarted(this);
                    refreshTasks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 批量条按钮（规范：半透明白底 / 红底删除，9dp 圆角）。 */
    private TextView batchBtn(String text, int bg, int fg) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(fg);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(11), dp(7), dp(11), dp(7));
        v.setBackground(UiStyle.roundRect(bg, dp(9)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    // ================= 批量执行引擎（顺序执行 · 可离开） =================
    // 入口：账号页角色行「▶ 跑日常」与批量操作条「跑日常」；
    // 今日页主入口已收敛为切换账号/切换角色（跑全部的旧入口随 v4.1.0 移除）。

    private void runList(final List<AccountStore.Account> wsIn) {
        if (RUN.running) {
            showRunPanel();
            return;
        }
        final List<AccountStore.Account> ws = new ArrayList<>();
        for (AccountStore.Account a : wsIn) {
            if (a != null && a.token != null && !a.token.isEmpty()) ws.add(a);
        }
        if (ws.isEmpty()) {
            toast("没有可跑的账号（需有效令牌）");
            return;
        }
        RUN.running = true;
        RUN.done = 0;
        RUN.total = ws.size();
        RUN.current = "";
        synchronized (RUN.logs) {
            RUN.logs.clear();
        }
        RUN.log("→ 开始执行工作区 " + ws.size() + " 个号");
        showRunPanel();
        DailyRunner.SOURCE = "手动";
        new Thread(() -> {
            for (AccountStore.Account a : ws) {
                if (!RUN.running) break;
                RUN.current = a.label;
                RUN.log("→ " + a.label + " 开始");
                long sid = activeSid(a);
                try {
                    final com.carlos.vault.daily.LogStore ls = new com.carlos.vault.daily.LogStore(HomeActivity.this);
                    DailyRunner.Summary s2 = DailyRunner.run(HomeActivity.this, a.fpid, a.token,
                            sid, null, new DailyRunner.ProgressListener() {
                                @Override
                                public void onTaskStart(String name) {
                                    RUN.log("▸ " + a.label + " · " + name);
                                }

                                @Override
                                public void onTaskDone(DailyResult r) {
                                    try {
                                        boolean ok = r.category == DailyResult.Category.CLAIMED
                                                || r.category == DailyResult.Category.SKIPPED;
                                        ls.append(a.fpid, sid, r.taskId, ok, r.toString(), "");
                                        ls.setStatus(a.fpid, sid, r.taskId, ok
                                                ? com.carlos.vault.daily.LogStore.ST_DONE
                                                : com.carlos.vault.daily.LogStore.ST_FAILED);
                                        ls.applySubResults(a.fpid, sid, r.taskId, r.subResults);
                                    } catch (Throwable ignored) {
                                    }
                                }

                                @Override
                                public void onFinish(DailyRunner.Summary s3) {
                                }
                            });
                    String brief = s2.toText().replace('\n', ' ');
                    RUN.log((s2.loginError != null ? "✗" : "✓") + " " + a.label + " · "
                            + brief.substring(0, Math.min(90, brief.length())));
                } catch (Throwable t) {
                    RUN.log("✗ " + a.label + " · " + t.getClass().getSimpleName());
                }
                RUN.done++;
                try {
                    long nowMs = System.currentTimeMillis();
                    getSharedPreferences("daily_sched", MODE_PRIVATE).edit()
                            .putLong("last:" + a.fpid, nowMs)
                            .putLong("lastr:" + a.fpid + ":" + sid, nowMs).apply();
                } catch (Throwable ignored) {
                }
            }
            RUN.running = false;
            RUN.log("→ 全部完成 " + RUN.done + "/" + RUN.total);
            runOnUiThread(() -> {
                toast("日常已完成 · " + RUN.done + "/" + RUN.total + " 个号");
                refreshAllPages();
            });
        }, "run-all").start();
    }

    /** 执行页（可离开：线程继续，随时回来看）。 */
    private void showRunPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));

        TextView title = new TextView(this);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        box.addView(title);
        TextView sub = new TextView(this);
        sub.setTextSize(12);
        sub.setTextColor(UiStyle.INK2);
        box.addView(sub);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this,
                null, android.R.attr.progressBarStyleHorizontal);
        box.addView(pb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        ScrollView sc = new ScrollView(this);
        TextView log = new TextView(this);
        log.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        log.setTextSize(11);
        sc.addView(log);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Runnable update = () -> {
            title.setText(RUN.running ? "正在执行 · 工作区 " + RUN.total + " 个号"
                    : "执行结束 · " + RUN.done + "/" + RUN.total);
            sub.setText("已完成 " + RUN.done + " / " + RUN.total
                    + (RUN.current.isEmpty() ? "" : " · 当前：" + RUN.current));
            pb.setMax(Math.max(1, RUN.total));
            pb.setProgress(RUN.done);
            StringBuilder sb = new StringBuilder();
            synchronized (RUN.logs) {
                for (String l : RUN.logs) {
                    sb.append(l.startsWith("✗") ? "⛔" : l.startsWith("✓") ? "✅" : "▸")
                            .append(l.substring(l.startsWith("✗") || l.startsWith("✓") ? 2 : 2)).append('\n');
                }
            }
            log.setText(sb.toString());
        };
        update.run();
        RUN.attacher = update;

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box)
                .setPositiveButton("离开（后台继续）", (d, w) -> RUN.attacher = null)
                .setNeutralButton(RUN.running ? "停止" : "关闭", (d, w) -> {
                    if (RUN.running) {
                        RUN.running = false;
                        RUN.log("→ 用户停止");
                    }
                    RUN.attacher = null;
                })
                .setOnCancelListener(d -> RUN.attacher = null)
                .show();
    }

    private void refreshAllPages() {
        runOnUiThread(() -> {
            refreshToday();
            refreshAccounts();
            refreshTasks();
            refreshMine();
        });
    }

    /**
     * 轻提示（规范 spec：深底 INK 圆角胶囊，底部浮出 1.5s 自消失）。
     *
     * <p>为什么不用系统 {@code Toast}：系统样式随厂商 ROM 变化（灰底/白底不定），
     * 与深色画布 + 描边卡片的视觉语言割裂；且无法控制出现位置（会盖住底部导航）。
     * 这里往内容根盖一个自绘 TextView，200ms 淡入、1.6s 后淡出移除。
     */
    private void toast(String s) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(UiStyle.INVERSE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UiStyle.roundRect(UiStyle.INK, dp(14)));
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setMaxLines(2);
        final ViewGroup root = (ViewGroup) pages[0].getParent();
        root.addView(t, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((android.widget.FrameLayout.LayoutParams) t.getLayoutParams()).bottomMargin = dp(92);
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(200).start();
        t.postDelayed(() -> t.animate().alpha(0f).setDuration(240)
                .withEndAction(() -> root.removeView(t)).start(), 1600);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
