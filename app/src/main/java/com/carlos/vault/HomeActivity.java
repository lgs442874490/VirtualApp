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
import com.carlos.vault.daily.RoleDisplay;
import com.carlos.vault.daily.RoleStore;
import com.carlos.vault.license.DeviceId;
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
    private final LinearLayout[] navPills = new LinearLayout[4];
    private final UiStyle.Icon[] navIcons = new UiStyle.Icon[4];

    // 今日页
    private TextView heroDone, heroSub, statRun, statErr, statWait, topCaption;
    private UiStyle.Ring heroRing;
    private UiStyle.Switch todayAutoSwitch;
    private TextView todayAutoSub;
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
    private TextView accViewList, accViewCard;    // 设计稿 S3 的「列表 / 卡片」页签
    private TextView accBatchBtn;                 // 批量管理按钮（长按行进入批量态时同步文案）
    private boolean batchMode;                    // 批量管理态：行首显示勾选框
    private final java.util.Set<String> expandedAccs = new java.util.HashSet<>();

    // 账号页 · 账号池（M2：UI + 状态机 + 轮询，契约《账号池M2M3-App实现契约》§3）
    private int currentTab = 0;                   // 当前可见 tab（轮询只在账号页跑）
    private TextView poolBarLoginAll, poolBarStopAll, poolBarRefresh;
    private TextView poolBarProgress;             // 批量条进度「上线中/停止中 x/y」
    private TextView poolBanner;                  // stale 横幅「状态获取失败，点击重试」
    private boolean poolPolling;                  // 轮询排程开关（tab 可见 + resumed）
    private boolean poolBarBusy;                  // 全局批量执行中（批量条按钮禁点）
    private final java.util.Set<String> poolBusy  // 角色动作进行中 "fpid:sid:uid"（loading/禁点）
            = new java.util.HashSet<>();
    private final java.util.Set<String> poolAccBusy = new java.util.HashSet<>();  // 账号级动作进行中 fpid
    private final android.os.Handler poolHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean poolInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);   // 请求串行防重入
    private String lastPoolRender;                // 上次渲染的池数据快照（未变则跳过重绘）
    private static final long POOL_POLL_INTERVAL_MS = 5000L;
    /** 轮询节拍：仅在账号 tab 可见时请求；串行防重入（上一发未回不发下一发）。 */
    private final Runnable poolTick = new Runnable() {
        @Override public void run() {
            if (!poolPolling) return;
            if (currentTab != 1 || isFinishing()) return;   // 切走/离开后不再排程
            pollPoolOnce();
            poolHandler.postDelayed(poolTick, POOL_POLL_INTERVAL_MS);
        }
    };

    // 任务页
    private LinearLayout taskRows;
    private ScrollView taskScroll;
    private TextView taskCap, bannerTitle, bannerSub, winBtn;
    private UiStyle.Switch autoSwitch;
    private UiStyle.Switch keepSwitch;   // 24h 常驻在线开关（心跳+自动互助，与自动日常正交）
    private TextView keepSub;            // 常驻在线状态行（在线/重连中/被顶号/需重导token）
    private TextView helpStatusText;     // 今日页互助卡状态行
    private TextView helpActionBtn;      // 今日页「一键帮助全盟」按钮
    private final List<TextView> catChips = new ArrayList<>();
    private int taskCat = 0;
    private String taskQuery = "";      // 日常中心搜索词（空=按分类浏览；非空=跨分类搜索）
    private final java.util.Set<String> favFns = new java.util.LinkedHashSet<>();  // 常用功能 id（持久化，保持展示顺序）
    private EditText taskSearch;        // 功能搜索框（buildTaskPage 建一次，TextWatcher 驱动过滤）
    private TextView searchClear;       // 搜索框右侧「清除」（有词时常显）
    private TextView searchCap;         // 搜索结果计数 / 空态提示行
    private AccountStore.Account taskAccount;
    private UiStyle.Switch fnSwRef(UiStyle.Switch s) { return s; }
    /**
     * 配置写入的 sid：优先当前角色；角色尚未发现时回退 activeRole/已发现角色。
     * 不能直接用 currentRoleSid()=0：新账号首次打开日常页时角色可能还没同步，
     * 写进 acc:fpid:0 的配置永远不会被 syncFields 上传（服务端只收 roles[] 的 sid）。
     */
    private long configSid(AccountStore.Account a) {
        long sid = currentRoleSid();
        if (sid > 0) return sid;
        if (a == null) return 0;
        sid = activeSid(a);
        if (sid > 0) return sid;
        try {
            for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                if (r.enabled && r.serverid > 0) return r.serverid;
            }
            for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                if (r.serverid > 0) return r.serverid;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private boolean fnOn(FnRegistry.Fn fn) {
        if (taskAccount == null) return false;
        // 口径统一：日常页一切读写跟随「全局当前角色」（与 refreshTasks 状态徽章同源），
        // 不走 activeSid——否则切换角色后开关仍读写旧角色配置（配置残留/串写）。
        long sid = configSid(taskAccount);
        Map<String, Boolean> cfg = new com.carlos.vault.daily.DailyConfigStore(this)
                .roleConfig(taskAccount.fpid, sid);
        Boolean on = cfg == null ? null : cfg.get(fn.id);
        return on == null ? (fn.implemented && defOn(fn.id)) : on;
    }
    private void setFnOn(FnRegistry.Fn fn, boolean on) {
        if (taskAccount == null) return;
        long sid = configSid(taskAccount);
        com.carlos.vault.daily.DailyConfigStore cs = new com.carlos.vault.daily.DailyConfigStore(this);
        Map<String, Boolean> cfg = cs.roleConfig(taskAccount.fpid, sid);
        if (cfg == null) cfg = new java.util.LinkedHashMap<>();
        cfg.put(fn.id, on);
        cs.saveRoleConfig(taskAccount.fpid, sid, cfg);
        // 关键：服务端执行依据是上传后的 roleConfig；不调用会等下一次全量同步，
        // 表现为“App 里开了，服务器整轮仍按旧配置跑”。
        VaultSync.onAccountsChanged(this);
    }
    private boolean defOn(String fnId) {
        for (com.carlos.vault.daily.DailyTasks.TaskDef d : com.carlos.vault.daily.DailyTasks.all()) {
            if (d.id.equals(fnId)) return d.enabledByDefault;
        }
        return true;
    }

    // 我的页
    private TextView licenseDays;
    private TextView licenseMeta;   // 授权卡次行：到期日 + 绑定规则
    private TextView licenseBind;   // 授权卡底行：已绑定设备数
    private TextView licenseStatus; // 生效中/未激活徽标
    private TextView licenseExpire; // 授权卡底行：到期日

    // 执行引擎（可离开：静态态 + 附着的执行页回调）
    static final RunState RUN = new RunState();

    /** 执行页展示态（不参与引擎）：本批账号名单（逐号结果行）+ 开局时间戳（已运行时长）。 */
    private List<String> runNames = new ArrayList<>();
    private long runStartedAt;
    private long runEndedAt;      // 本轮结束时刻（0=未结束），用于冻结执行页时长

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
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiStyle.BG_V2);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 4; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            content.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View nav = buildBottomNav();
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        nlp.leftMargin = dp(12);
        nlp.rightMargin = dp(12);
        nlp.bottomMargin = dp(12);
        root.addView(nav, nlp);

        currentRoleKey = getSharedPreferences("home", MODE_PRIVATE).getString("current_role", null);
        alignActiveRoleWithCurrent();
        loadFavs();   // 常用功能（S7 日常中心）先于任何 refreshTasks 加载
        buildTodayPage();
        buildAccountPage();
        buildTaskPage();
        buildMinePage();
        setContentView(root);
        switchTab(0);
    }

    @Override
    protected void addMainMenuExtras(LinearLayout box, LinearLayout.LayoutParams rowLp) {
        box.addView(UiStyle.makeRowBtn(this, "🤝", "一键帮助全盟", UiStyle.BRAND,
                this::oneKeyHelpAll), rowLp);
    }

    void onEngineReady() {
        // 统一自动化：客户端不再本地执行任务，启动即锁定服务器模式。
        VaultSync.ensureServerMode(this);
        refreshAllPages();
        // 打开即上传身份库并拉回服务器状态；本地不再补跑日常。
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
        // 账号详情页也允许切角色（同写 activeRole + home:current_role）；本页的
        // currentRoleKey 是进页时读的快照，返回时必须重读，否则 Hero/配置仍按旧角色。
        currentRoleKey = getSharedPreferences("home", MODE_PRIVATE)
                .getString("current_role", null);
        refreshAllPages();
        // 账号池轮询（M2）：仅 resumed 且账号 tab 可见时跑（切 tab/onPause 停）
        if (currentTab == 1) startPoolPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPoolPolling();
    }

    private boolean fullSyncing;

    /**
     * App 前台同步：只做“上传身份库 + 拉回服务器状态”。
     *
     * <p>2026-10 起自动化任务全部由 vault-server 执行，客户端不再在打开 App 时本地补跑；
     * 服务器自己的 30 秒调度器负责整轮/补给，App 只负责展示与手动触发。
     */
    private void kickFullSync() {
        if (fullSyncing) return;
        long lastFull = getSharedPreferences("home", MODE_PRIVATE).getLong("last_fullsync", 0);
        if (System.currentTimeMillis() - lastFull < 10 * 60_000L) return;   // 10分钟防抖
        fullSyncing = true;
        try {
            VaultSync.ensureServerMode(this);
            VaultSync.syncAll(this);
            VaultSync.fetchState(this, (code, message, data) -> {
                getSharedPreferences("home", MODE_PRIVATE).edit()
                        .putLong("last_fullsync", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (accountCap != null) refreshAccounts();
                    refreshAllPages();
                });
            });
        } finally {
            fullSyncing = false;
        }
    }

    // ================= 底部导航 =================

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable nbg = new GradientDrawable();
        nbg.setColor(UiStyle.WHITE);
        nbg.setCornerRadius(dp(32));
        nav.setBackground(nbg);
        nav.setElevation(dp(10));
        String[] names = {"今日", "账号", "日常", "我的"};
        int[] icons = {UiStyle.Icon.HOME, UiStyle.Icon.USERS,
                UiStyle.Icon.CHECKLIST, UiStyle.Icon.USER};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(30), dp(4), dp(30), dp(4));
            UiStyle.Icon icon = new UiStyle.Icon(this, icons[i], UiStyle.INK2, 22);
            pill.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextSize(10.5f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(UiStyle.INK2);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(3);
            pill.addView(label, llp);
            UiStyle.press(pill);
            pill.setOnClickListener(v -> switchTab(idx));
            item.addView(pill, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            item.setOnClickListener(v -> switchTab(idx));
            navIcons[i] = icon;
            navItems[i] = label;
            navPills[i] = pill;
            nav.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return nav;
    }

    private void switchTab(int which) {
        currentTab = which;
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == which ? View.VISIBLE : View.GONE);
            boolean sel = i == which;
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(26));
            pill.setColor(sel ? UiStyle.BRAND : android.graphics.Color.TRANSPARENT);
            navPills[i].setBackground(pill);
            navItems[i].setTextColor(sel ? UiStyle.WHITE : UiStyle.INK2);
            if (navIcons[i] != null) navIcons[i].setColor(sel ? UiStyle.WHITE : UiStyle.INK2);
        }
        // 账号池轮询只挂账号 tab（M2 契约 §3.2）：切入即启动（首拍立即请求），切走即停
        if (which == 1) startPoolPolling();
        else stopPoolPolling();
        if (which == 0) refreshToday();
        if (which == 1) refreshAccounts(true);
        if (which == 2) {
            refreshTasks();
            taskAccount = currentSelected();
            if (taskAccount != null) autoSyncAccount(taskAccount, true);
        }
        if (which == 3) refreshMine();
    }

    // ================= 账号池轮询（M2 契约 §3.2） =================

    /**
     * 启动 5s 轮询：仅服务器模式；首拍立即请求（进入账号页/操作后状态 ≤2s 可见）。
     * 重复调用幂等（onResume 与 switchTab 都会触发）。
     */
    private void startPoolPolling() {
        if (!VaultSync.isServerMode(this)) return;
        if (poolPolling) return;
        poolPolling = true;
        poolHandler.removeCallbacks(poolTick);
        poolHandler.post(poolTick);
    }

    private void stopPoolPolling() {
        poolPolling = false;
        poolHandler.removeCallbacks(poolTick);
    }

    /** 单次账号池请求：串行防重入（上一发未回不发下一发）；数据有变化才重渲染。 */
    private void pollPoolOnce() {
        if (!poolInFlight.compareAndSet(false, true)) return;
        VaultSync.fetchAccountPool(this, (code, message, data) -> {
            poolInFlight.set(false);
            runOnUiThread(() -> {
                // 失败（stale 标记已在 VaultSync 落地）必须重渲染出横幅；成功则数据变了才重绘
                String snap = data == null ? null : data.toString();
                boolean changed = code != 0 || snap == null || !snap.equals(lastPoolRender);
                if (code == 0) lastPoolRender = snap;
                if (changed && accountList != null) refreshAccounts();
            });
        });
    }

    // ================= Tab1 · 今日 =================

    private void buildTodayPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(110));
        sc.addView(box);
        pages[0].addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // —— 顶栏：标题 + 日期·自动日常 + 通知铃 ——
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("无尽号库");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        topCaption = new TextView(this);
        topCaption.setTextSize(12);
        topCaption.setTextColor(UiStyle.INK2);
        topCaption.setTypeface(Typeface.DEFAULT_BOLD);
        topCaption.setPadding(0, dp(3), 0, 0);
        tb.addView(topCaption);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout bellWrap = new FrameLayout(this);
        GradientDrawable bellBg = new GradientDrawable();
        bellBg.setColor(UiStyle.WHITE);
        bellBg.setCornerRadius(dp(22));
        bellWrap.setBackground(bellBg);
        bellWrap.setElevation(dp(2));
        bellWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.BELL, UiStyle.INK, 20),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        UiStyle.press(bellWrap);
        bellWrap.setOnClickListener(v -> showMainMenu());
        top.addView(bellWrap, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(top);

        // —— Hero 卡：环形完成度 + 当前角色/活跃度 + 双跑按钮 ——
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(UiStyle.BRAND);
        hg.setCornerRadius(dp(20));
        hero.setBackground(hg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        box.addView(hero, hlp);

        // 左：环（数字在环内）；右：信息行 + 活跃度档位条（规范第一张截图：环与活跃度并排）
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        heroRing = new UiStyle.Ring(this);
        heroTop.addView(heroRing);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(18), 0, 0, 0);
        LinearLayout heroSubRow = new LinearLayout(this);
        heroSubRow.setOrientation(LinearLayout.HORIZONTAL);
        heroSubRow.setGravity(Gravity.CENTER_VERTICAL);
        heroSub = new TextView(this);
        heroSub.setTextSize(16);
        heroSub.setTypeface(Typeface.DEFAULT_BOLD);
        heroSub.setTextColor(UiStyle.WHITE);
        heroSub.setMaxLines(1);
        heroSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroSubRow.addView(heroSub, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heroSubRow.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON_DOWN,
                UiStyle.BRAND_100, 16), new LinearLayout.LayoutParams(dp(16), dp(16)));
        onlineDot = new TextView(this);
        onlineDot.setText("· 检测中");
        onlineDot.setTextSize(11.5f);
        onlineDot.setTypeface(Typeface.DEFAULT_BOLD);
        onlineDot.setTextColor(UiStyle.ON_BRAND_OK);
        heroSubRow.addView(onlineDot);
        UiStyle.press(heroSubRow);
        heroSubRow.setOnClickListener(v -> showRolePicker());
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
        arlp.topMargin = dp(10);
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

        // 双按钮：切换账号（主）/ 切换角色（次）——数据全自动，这里只做切换
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(16), 0, 0);

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

        // —— 统一自动化任务卡（2026-10 起替代“自动日常 + 24h 常驻在线”双卡）——
        LinearLayout autoCard = new LinearLayout(this);
        autoCard.setOrientation(LinearLayout.HORIZONTAL);
        autoCard.setGravity(Gravity.CENTER_VERTICAL);
        autoCard.setPadding(dp(14), dp(14), dp(12), dp(14));
        autoCard.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 16, UiStyle.LINE));
        LinearLayout.LayoutParams duoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        duoLp.topMargin = dp(12);
        autoCard.setLayoutParams(duoLp);
        autoCard.addView(UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING,
                UiStyle.BRAND_TEXT, UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout aMid = new LinearLayout(this);
        aMid.setOrientation(LinearLayout.VERTICAL);
        aMid.setPadding(dp(12), 0, dp(8), 0);
        TextView autoTitle = new TextView(this);
        autoTitle.setText("自动化任务");
        autoTitle.setTextSize(15);
        autoTitle.setTypeface(Typeface.DEFAULT_BOLD);
        autoTitle.setTextColor(UiStyle.INK);
        aMid.addView(autoTitle);
        todayAutoSub = new TextView(this);
        todayAutoSub.setTextSize(11f);
        todayAutoSub.setTextColor(UiStyle.INK2);
        todayAutoSub.setPadding(0, dp(3), 0, 0);
        aMid.addView(todayAutoSub);
        autoCard.addView(aMid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        todayAutoSwitch = new UiStyle.Switch(this, false, () -> {
            AccountStore.Account cra = currentRoleAccount();
            if (cra == null) {
                toast("没有可用账号");
                todayAutoSwitch.setOn(false);
                return;
            }
            boolean on = todayAutoSwitch.isOn();
            RoleStore.saveAutomation(this, cra.fpid, on);
            VaultSync.ensureServerMode(this);
            VaultSync.onAccountsChanged(this);
            com.carlos.vault.daily.RoleSessionKeeper.get(this).start();
            com.carlos.vault.daily.RoleSessionKeeper.get(this).syncOnline();
            toast(on ? "自动化任务已开启 · 服务器执行" : "自动化任务已关闭");
            refreshToday();
            refreshTasks();
        });
        autoCard.addView(todayAutoSwitch);
        box.addView(autoCard);



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
        todoTitle.setText("待办事项");
        todoTitle.setTextSize(16);
        todoTitle.setTypeface(Typeface.DEFAULT_BOLD);
        todoTitle.setTextColor(UiStyle.INK);
        todoHead.addView(todoTitle);
        todoHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView todoHint = new TextView(this);
        todoHint.setText("错过要等下一轮");
        todoHint.setTextSize(11.5f);
        todoHint.setTextColor(UiStyle.INK2);
        todoHead.addView(todoHint);
        box.addView(todoHead);
        limitBox = new LinearLayout(this);
        limitBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(limitBox);

        // 联盟大厅入口放在顶栏通知菜单里（一键帮助全盟），保持今日页与设计稿一致的卡片骨架。

        // —— 联盟大厅（正在集结的队列）——
        LinearLayout rallyHead = new LinearLayout(this);
        rallyHead.setOrientation(LinearLayout.HORIZONTAL);
        rallyHead.setGravity(Gravity.CENTER_VERTICAL);
        rallyHead.setPadding(dp(2), dp(18), dp(2), dp(8));
        TextView rallyTitle = new TextView(this);
        rallyTitle.setText("联盟大厅 · 正在集结");
        rallyTitle.setTextSize(16);
        rallyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        rallyTitle.setTextColor(UiStyle.INK);
        rallyHead.addView(rallyTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView rallyAll = new TextView(this);
        rallyAll.setText("全部");
        rallyAll.setTextSize(12);
        rallyAll.setTextColor(UiStyle.BRAND_TEXT);
        UiStyle.press(rallyAll);
        rallyAll.setOnClickListener(v -> toast("联盟大厅随游戏同步更新"));
        rallyHead.addView(rallyAll);
        box.addView(rallyHead);
        rallyBox = new LinearLayout(this);
        rallyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(rallyBox);

        // —— 快捷入口 ——
        LinearLayout quickHead = new LinearLayout(this);
        quickHead.setOrientation(LinearLayout.HORIZONTAL);
        quickHead.setGravity(Gravity.CENTER_VERTICAL);
        quickHead.setPadding(dp(2), dp(18), dp(2), dp(8));
        TextView quickTitle = new TextView(this);
        quickTitle.setText("快捷入口");
        quickTitle.setTextSize(16);
        quickTitle.setTypeface(Typeface.DEFAULT_BOLD);
        quickTitle.setTextColor(UiStyle.INK);
        quickHead.addView(quickTitle);
        box.addView(quickHead);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        int[] qIcons = {UiStyle.Icon.GIFT, UiStyle.Icon.USERS,
                UiStyle.Icon.DOC, UiStyle.Icon.GRID};
        String[] qLabels = {"补领", "切号", "日志", "工具箱"};
        String[] qActs = {"daily", "switch", "log", "tools"};
        for (int qi = 0; qi < qLabels.length; qi++) {
            final String act = qActs[qi];
            LinearLayout cell = UiStyle.outlineCard(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(8), dp(12), dp(8), dp(12));
            cell.addView(UiStyle.iconTile(this, qIcons[qi], UiStyle.BRAND_TEXT,
                    UiStyle.BRAND_50, 38, 19, 11),
                    new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView label = new TextView(this);
            label.setText(qLabels[qi]);
            label.setTextSize(11.5f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(UiStyle.INK);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(7), 0, 0);
            cell.addView(label);
            UiStyle.press(cell);
            cell.setOnClickListener(v -> {
                if ("switch".equals(act)) showAccountSwitcher();
                else quickAction(act);
            });
            LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (qi < qLabels.length - 1) q2.rightMargin = dp(8);
            quick.addView(cell, q2);
        }
        box.addView(quick);
    }

    /** 账号切换（主按钮）：任务页/限时/联盟大厅联动当前账号。 */
    /** 账号主角色：工作区第一个，无则 activeRole，再无则第一角色。返回 {sid, uid}（sid=0=无）。 */
    private long[] mainRole(AccountStore.Account a) {
        try {
            List<RoleStore.Role> rs = RoleStore.roles(this, a.fpid);
            for (RoleStore.Role r : rs) {
                if (r.enabled) return new long[]{r.serverid, r.uid};
            }
            RoleStore.ActiveRole act = RoleStore.activeRoleEx(this, a.fpid);
            if (act.sid > 0) return new long[]{act.sid, act.uid};
            if (!rs.isEmpty()) return new long[]{rs.get(0).serverid, rs.get(0).uid};
        } catch (Throwable ignored) {
        }
        return new long[]{0, 0};
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
        final long[][] mainRoles = new long[cand.size()][];
        String[] names = new String[cand.size()];
        AccountStore.Account cur = currentRoleAccount();
        int checked = 0;
        for (int i = 0; i < cand.size(); i++) {
            AccountStore.Account a = cand.get(i);
            mainRoles[i] = mainRole(a);
            String mainName = "";
            try {
                for (RoleStore.Role r : RoleStore.roles(this, a.fpid)) {
                    if (r.serverid == mainRoles[i][0] && r.name != null && !r.name.isEmpty()) {
                        mainName = r.name;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            names[i] = a.label + "（主角色：" + (mainName.isEmpty() ? "K" + mainRoles[i][0] : mainName) + "）";
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
                    long[] m = mainRoles[cand.indexOf(target)];
                    if (m[0] > 0) {
                        setCurrentRole(target.fpid, m[0], m[1]);
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
            case "tools": openToolBox(); break;
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
        int claimed = st[0], failed = st[1], running = st[2], total = st[3], skipped = st[4];
        if (failed > 0) return new String[]{"err", "有异常"};
        if (running > 0) return new String[]{"run", "进行中"};
        if (total > 0 && claimed + skipped >= total) {
            // 只有真实领取（CLAIMED）才显示“已完成”；全跳过显示“已检查”，
            // 避免“显示已完成但游戏内没领到奖励”的误判。
            return claimed > 0 ? new String[]{"ok", "已完成"} : new String[]{"ok", "已检查"};
        }
        if (claimed > 0) return new String[]{"wait", "待领取"};
        if (skipped > 0) return new String[]{"wait", "已检查"};
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

    /**
     * 当前角色 sid（无记录则回退 activeSid）。
     *
     * <p>current_role 键格式：{@code "fpid:sid"}（旧）或 {@code "fpid:sid:uid"}（同服多角色，
     * 2026-10-20 起）。uid 段缺失按 0 处理——调用方需要 uid 时走 {@link #currentRoleUid()}。
     */
    private long currentRoleSid() {
        if (currentRoleKey != null && currentRoleKey.contains(":")) {
            try {
                String rest = currentRoleKey.substring(currentRoleKey.indexOf(':') + 1);
                int c = rest.indexOf(':');
                return Long.parseLong(c < 0 ? rest : rest.substring(0, c));
            } catch (Throwable ignored) {
            }
        }
        AccountStore.Account a = currentRoleAccount();
        return a == null ? 0 : activeSid(a);
    }

    /** 当前角色 uid（0=未知/旧格式未带 uid）。同服多角色靠它区分同一 sid 的两个角色。 */
    private long currentRoleUid() {
        if (currentRoleKey != null && currentRoleKey.contains(":")) {
            try {
                int i1 = currentRoleKey.indexOf(':');
                int i2 = currentRoleKey.indexOf(':', i1 + 1);
                if (i2 > 0 && i2 < currentRoleKey.length() - 1) {
                    return Long.parseLong(currentRoleKey.substring(i2 + 1));
                }
            } catch (Throwable ignored) {
            }
        }
        AccountStore.Account a = currentRoleAccount();
        return a == null ? 0 : RoleStore.activeRoleUid(this, a.fpid);
    }

    /** 某角色行是否就是当前角色：sid 相等，且当前 uid 未知或与该行 uid 相等（同服多角色精确命中）。 */
    private boolean isCurrentRole(RoleStore.Role r) {
        if (r == null) return false;
        long sid = currentRoleSid();
        if (r.serverid != sid) return false;
        long uid = currentRoleUid();
        return uid <= 0 || r.uid == uid;
    }

    /**
     * 启动对齐：把账号激活角色（activeRole）校准到全局当前角色。
     *
     * <p>历史版本「切换角色」只写 home:current_role 不写 activeRole，存量设备上两者可能
     * 不一致（功能开关/详情页兜底/常驻会话选角仍在旧角色）。启动时以 currentRoleKey 为准
     * 做一次对齐，保证所有消费方同源。仅对当前角色所属账号生效，不动其它账号。
     *
     * <p>同服多角色：current_role 带 uid 时按复合身份对齐（旧格式只有 sid 时退化为
     * sid 口径，由 setActiveRole 内部解析 uid）。
     */
    private void alignActiveRoleWithCurrent() {
        if (currentRoleKey == null || !currentRoleKey.contains(":")) return;
        try {
            int idx = currentRoleKey.indexOf(':');
            String fpid = currentRoleKey.substring(0, idx);
            String rest = currentRoleKey.substring(idx + 1);
            int c = rest.indexOf(':');
            long sid = Long.parseLong(c < 0 ? rest : rest.substring(0, c));
            long uid = c < 0 ? 0 : Long.parseLong(rest.substring(c + 1));
            if (fpid.isEmpty() || sid <= 0) return;
            RoleStore.ActiveRole act = RoleStore.activeRoleEx(this, fpid);
            long activeAt = RoleStore.activeRoleAt(this, fpid);
            long currentAt = getSharedPreferences("home", MODE_PRIVATE)
                    .getLong("current_role_at", 0);
            // 双源对齐（2026-10-20）：按写入时间取较新者。旧实现无条件用 current_role
            // 覆盖 active2，重启时会把用户刚切的角色回滚（真机已复现）。
            int source = com.carlos.vault.daily.RoleIdentity.pickNewerRoleSource(
                    act.sid, act.uid, activeAt, sid, uid, currentAt);
            if (source == com.carlos.vault.daily.RoleIdentity.SOURCE_ACTIVE) {
                if (act.sid > 0) {
                    currentRoleKey = fpid + ":" + act.sid
                            + (act.uid > 0 ? ":" + act.uid : "");
                    getSharedPreferences("home", MODE_PRIVATE).edit()
                            .putString("current_role", currentRoleKey)
                            .putLong("current_role_at", activeAt).apply();
                }
            } else if (act.sid != sid || (uid > 0 && act.uid != uid)) {
                RoleStore.setActiveRole(this, fpid, sid, uid);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 记住当前角色并立即刷新全部页面（旧签名：uid 按角色表解析）。 */
    private void setCurrentRole(String fpid, long sid) {
        long uid = 0;
        try {
            RoleStore.Role r = RoleStore.roleOf(this, fpid, sid);
            uid = r == null ? 0 : r.uid;
        } catch (Throwable ignored) {
        }
        setCurrentRole(fpid, sid, uid);
    }

    /**
     * 记住当前角色（复合身份）并立即刷新全部页面。
     *
     * <p>current_role 键：uid>0 时写 {@code "fpid:sid:uid"}（同服多角色区分依据）；
     * uid 未知时保持旧格式 {@code "fpid:sid"}，兼容旧版本读取。
     */
    private void setCurrentRole(String fpid, long sid, long uid) {
        currentRoleKey = fpid + ":" + sid + (uid > 0 ? ":" + uid : "");
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("current_role", currentRoleKey)
                // 双源对齐时间戳（2026-10-20）：启动时按较新者对齐，防旧 current_role
                // 覆盖用户刚切的 active 角色（真机：切隆科多后重启回滚成小鹿）
                .putLong("current_role_at", System.currentTimeMillis()).apply();
        // 关键：同步账号激活角色（RoleStore.activeRole）。activeRole 是功能开关读写
        // （fnOn/setFnOn 经 activeSid）、功能详情页 sid 兜底、多角色竞速会话的主角色指纹
        // 源（RoleSessionKeeper 竞速登录时 roleIdentitySeed 以 activeRole 判主角色）的
        // 事实源——若只改 currentRoleKey，切换后会出现"页面徽章跟新角色、配置/指纹跟旧角色"
        // 的分裂（真机事故：k888→k913 后兵营训练档位仍显示 k888 的 t3）。
        try {
            RoleStore.setActiveRole(this, fpid, sid, uid);
        } catch (Throwable ignored) {
        }
        // 同服多角色防回滚（P2）：标记待上传 + 触发去抖同步。上传完成前服务端回传仍是
        // 旧角色，若无此标记 learnServerRole 会把刚切的角色回滚（见 VaultSync 双层门控）。
        try {
            VaultSync.notifyRoleSwitched(this, fpid);
        } catch (Throwable ignored) {
        }
        refreshAllPages();
        toast("当前角色已切换");
    }

    /** 分段控件选中态跟随 accView（修复切换后高亮不变化）。 */
    private void updateAccSeg() {
        updateAccViewToggle();
    }

    /** 列表/卡片页签视觉：选中项白底浮起，未选中透明。 */
    private void updateAccViewToggle() {
        if (accViewList == null || accViewCard == null) return;
        for (int i = 0; i < 2; i++) {
            TextView t = i == 0 ? accViewList : accViewCard;
            boolean sel = i == accView;
            t.setTextColor(sel ? UiStyle.INK : UiStyle.INK2);
            t.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (sel) {
                t.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 12, UiStyle.LINE));
                t.setElevation(dp(2));
            } else {
                t.setBackground(null);
                t.setElevation(0);
            }
        }
    }

    /** 角色选择器：仅当前账号下的角色，当前项打勾（换账号走"切换账号"）。 */
    /** 设计稿 S5：当前账号的角色管理底部弹层（切换/跑日常/下线）。 */
    private void showRoleManagerForCurrent() {
        AccountStore.Account curAcc = currentRoleAccount();
        if (curAcc == null || curAcc.fpid == null) {
            toast("没有可用账号");
            return;
        }
        List<RoleStore.Role> rs;
        try {
            rs = RoleStore.roles(this, curAcc.fpid);
        } catch (Throwable t) {
            rs = new ArrayList<>();
        }
        if (rs.isEmpty()) {
            toast(curAcc.label + " 暂无角色数据 —— 登录一次游戏后自动发现");
            return;
        }
        showRoleManager(curAcc, rs, accountOnline(curAcc));
    }

    private void showRolePicker() {
        AccountStore.Account curAcc = currentRoleAccount();
        if (curAcc == null || curAcc.fpid == null) {
            toast("没有可用账号");
            return;
        }
        List<String> items = new ArrayList<>();
        final List<String[]> pairs = new ArrayList<>();   // {fpid, sid, uid, 显示名}
        long curSid = currentRoleSid();
        long curUid = currentRoleUid();
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
            if (!r.enabled) continue;   // 切换角色只展示已加入工作区的角色
            // 标准展示（昵称 + UID/城堡/战力，缺失段「未同步」）——不再用「同服第N角色」
            String rn = RoleDisplay.roleName(r.name);
            boolean isCur = r.serverid == curSid && (curUid <= 0 || r.uid == curUid);
            String tag = isCur ? " ✓当前" : "";
            pairs.add(new String[]{curAcc.fpid, String.valueOf(r.serverid),
                    String.valueOf(r.uid), rn});
            items.add(rn + " · "
                    + RoleDisplay.roleMetaLine(r.serverid, r.uid, r.level, r.power) + tag);
        }
        if (items.isEmpty()) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("没有可切换的角色")
                    .setMessage("当前账号还没有角色加入工作区。\n\n请到「账号」→ 该账号右侧「⋯」→「角色管理」，打开角色开关。")
                    .setPositiveButton("去角色管理",
                            (d, w) -> showRoleManagerForCurrent())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("切换当前角色")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String[] p2 = pairs.get(w);
                    try {
                        long uid = p2.length > 2 ? Long.parseLong(p2[2]) : 0;
                        setCurrentRole(p2[0], Long.parseLong(p2[1]), uid);
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
        int pct = qTotal == 0 ? 0
                : (qDone >= qTotal ? 100 : Math.min(99, (int) (100L * qDone / qTotal)));
        heroRing.setFraction(pct / 100f);
        heroRing.setPercent(pct, "今日完成");
        actVal.setText(integral < 0 ? "-- / 325" : integral + " / 325");
        android.widget.LinearLayout.LayoutParams aflp =
                (android.widget.LinearLayout.LayoutParams) actFill.getLayoutParams();
        aflp.weight = integral <= 0 ? 0f : Math.min(1f, integral / 325f);
        actFill.setLayoutParams(aflp);
        if (integral < 0) actTier.setText("打开账号同步后显示档位");
        else if (integral >= 325) actTier.setText("已达最高档位 · 宝箱全解锁");
        else {
            int[] tiers = {40, 80, 120, 160, 215, 270, 325};
            int next = 325, tier = 1;
            for (int t : tiers) {
                if (integral >= t) tier++;
                else { next = t; break; }
            }
            actTier.setText("第 " + tier + " 档 · 再 " + (next - integral) + " 分领宝箱");
        }
        // Hero当前角色行（信息上移到 heroSub：本卡只显示 当前角色：角色名 + 在线状态）
        String crName = "";
        try {
            AccountStore.Account cra = currentRoleAccount();
            if (cra != null) {
                for (RoleStore.Role r : RoleStore.roles(this, cra.fpid)) {
                    if (isCurrentRole(r) && r.name != null && !r.name.isEmpty()) {
                        crName = r.name;
                        break;
                    }
                }
                if (crName.isEmpty()) crName = cra.label;
            }
        } catch (Throwable ignored) {
        }
        heroSub.setText((sel == null || sel.label == null || sel.label.isEmpty()
                ? "未选择账号" : sel.label)
                + (crName.isEmpty() ? "" : " · " + crName));
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
                } else if (accountOnline(onlineAcc) && roleUidOf(onlineAcc.fpid, curSidF, currentRoleUid()) != 0
                        && liveOnlineUid() == roleUidOf(onlineAcc.fpid, curSidF, currentRoleUid())) {
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
                if (isCurrentRole(r) && r.name != null && !r.name.isEmpty()) {
                    crName2 = r.name;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        // 统一自动化任务状态（服务器执行；不再区分自动日常/常驻在线）
        boolean roleAuto = sel != null && RoleStore.automationEnabled(this, sel.fpid);
        if (todayAutoSwitch != null) todayAutoSwitch.setOn(roleAuto);
        if (todayAutoSub != null) {
            int[] win = RoleStore.window(this);
            String winText = (win[0] == 0 && win[1] >= 24) ? "全天"
                    : String.format(java.util.Locale.CHINA, "%02d:00-%02d:00", win[0], win[1]);
            todayAutoSub.setText("服务器执行 · " + ws.size() + " 个号 · "
                    + (roleAuto ? winText : "未开启"));
        }
        // 顶栏 caption：日期 + 统一自动化状态
        boolean anyAuto = false;
        for (AccountStore.Account a : ws) {
            if (RoleStore.automationEnabled(this, a.fpid)) {
                anyAuto = true;
                break;
            }
        }
        topCaption.setText(new java.text.SimpleDateFormat("M月d日 EEE", java.util.Locale.CHINA)
                .format(new java.util.Date()) + " · 自动化任务 已" + (anyAuto ? "开启" : "关闭"));
        renderLimitWindows(sel);
        renderRallies(sel);
        autoSyncAccount(sel, false);
    }

    /** 下次自动执行的人话时间（今天 / 明早 / MM-dd）。 */
    private String nextRunText(long nxt) {
        if (nxt <= 0) return "即将 ";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.setTimeInMillis(nxt);
        String hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(nxt));
        boolean sameDay = now.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR);
        now.add(java.util.Calendar.DAY_OF_YEAR, 1);
        boolean tomorrow = now.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return "今天 " + hm + " ";
        if (tomorrow) return "明早 " + hm + " ";
        return new java.text.SimpleDateFormat("MM-dd ", java.util.Locale.CHINA)
                .format(new java.util.Date(nxt)) + hm + " ";
    }

    /** 角色区服对应的游戏 uid（找不到返回 0）。uid<=0 = 该 sid 第一行（旧口径）。 */
    private long roleUidOf(String fpid, long sid) {
        return roleUidOf(fpid, sid, 0);
    }

    /**
     * 角色区服对应的游戏 uid（同服多角色精确版）：uid>0 按 (sid,uid) 命中，
     * 找不到再退回该 sid 第一行——容器 live 反查正在玩的角色时不会串到同服另一个角色。
     */
    private long roleUidOf(String fpid, long sid, long uid) {
        try {
            List<RoleStore.Role> rs = RoleStore.roles(this, fpid);
            if (uid > 0) {
                for (RoleStore.Role r : rs) {
                    if (r.serverid == sid && r.uid == uid) return r.uid;
                }
            }
            for (RoleStore.Role r : rs) {
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
        // 每条待办独立白卡（设计稿 S2：卡片之间 8dp 间距）
        LinearLayout card = new LinearLayout(this);
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
            todoRow(card, "🐻", "巨熊陷阱预约",
                    bh.format(new java.util.Date(bearReserve * 1000)) + " 开启 · 全工作区自动预约",
                    "即将开始", UiStyle.WARN_INK, "",
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
        long sid = configSid(cur);
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

    /** 待办行：独立白卡 + 线框图标 + 标题/副题 + 右侧状态徽章（设计稿 S2）。 */
    private void todoRow(LinearLayout card, String emoji, String title, String sub,
                         String r1, int r1Color, String r2, Runnable onClick) {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        int iconType = UiStyle.Icon.TARGET;
        int iconColor = UiStyle.BRAND_TEXT;
        int iconBg = UiStyle.BRAND_50;
        if (title.contains("巨熊")) {
            iconType = UiStyle.Icon.CLOCK;
            iconColor = UiStyle.WARN_INK;
            iconBg = UiStyle.WARN_BG;
        } else if (title.contains("答题") || title.contains("礼")) {
            iconType = UiStyle.Icon.GIFT;
        } else if (title.contains("补给")) {
            iconType = UiStyle.Icon.LIGHTNING;
        } else if (title.contains("兵工厂")) {
            iconType = UiStyle.Icon.SHIELD;
        } else if (title.contains("燃霜")) {
            iconType = UiStyle.Icon.COMPASS;
        }
        row.addView(UiStyle.iconTile(this, iconType, iconColor, iconBg, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView n = new TextView(this);
        n.setText(title);
        n.setTextSize(15);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(UiStyle.INK);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(n);
        String detail = sub == null ? "" : sub;
        if (detail.isEmpty() && r2 != null) detail = r2;
        if (!detail.isEmpty()) {
            TextView s2 = new TextView(this);
            s2.setText(detail);
            s2.setTextSize(11.5f);
            s2.setTextColor(UiStyle.INK2);
            s2.setPadding(0, dp(3), 0, 0);
            s2.setMaxLines(1);
            s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(s2);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String type = "idle";
        if (r1Color == UiStyle.WARN_INK) type = "warn";
        else if (r1Color == UiStyle.WAIT_INK) type = "wait";
        else if (r1Color == UiStyle.RUN_INK) type = "run";
        else if (r1Color == UiStyle.ERR_INK) type = "err";
        else if (r1Color == UiStyle.OK_INK) type = "ok";
        if (r1 != null && !r1.isEmpty()) row.addView(UiStyle.badge(this, type, r1));
        if (onClick != null) {
            UiStyle.press(row);
            row.setOnClickListener(v -> onClick.run());
        }
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
        card.setPadding(dp(14), dp(13), dp(14), dp(12));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(UiStyle.iconTile(this, UiStyle.Icon.TARGET, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView nm = new TextView(this);
        nm.setText(title);
        nm.setTextSize(15);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(UiStyle.INK);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(nm);
        TextView sum = new TextView(this);
        StringBuilder sbt = new StringBuilder();
        if (memberMax > 0) sbt.append(members).append("/").append(memberMax).append(" 人");
        if (soldiers > 0) sbt.append(sbt.length() > 0 ? " · " : "").append(fmtWan(soldiers)).append("兵");
        if (sbt.length() == 0) sbt.append(marching ? "行军" : "集结中");
        if (anchor > 0) sbt.append(" · 剩余 ").append(fmtRemain(anchor - now));
        sum.setText(sbt.toString());
        sum.setTextSize(11.5f);
        sum.setTextColor(UiStyle.INK2);
        sum.setPadding(0, dp(3), 0, 0);
        mid.addView(sum);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView join = new TextView(this);
        join.setText("加入");
        join.setTextSize(12.5f);
        join.setTypeface(Typeface.DEFAULT_BOLD);
        join.setTextColor(UiStyle.WHITE);
        join.setGravity(Gravity.CENTER);
        join.setPadding(dp(16), dp(8), dp(16), dp(8));
        join.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 10));
        UiStyle.press(join);
        join.setOnClickListener(v -> {
            AccountStore.Account cur = currentRoleAccount();
            if (cur != null) openDaily(cur, "behemoth");
        });
        row.addView(join);
        card.addView(row);

        if (memberMax > 0) {
            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND_50, 2));
            View fill = new View(this);
            fill.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 2));
            float frac = Math.max(0.04f, Math.min(1f, members / (float) memberMax));
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, frac));
            track.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f - frac));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
            tlp.topMargin = dp(10);
            card.addView(track, tlp);
        }

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
        android.widget.FrameLayout pageRoot = new android.widget.FrameLayout(this);
        pages[1].addView(pageRoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(16));
        pageRoot.addView(box, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：账号 + 账号/工作区/在线统计 + 批量管理
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("账号");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        accountCap = new TextView(this);
        accountCap.setTextSize(12);
        accountCap.setTextColor(UiStyle.INK2);
        accountCap.setPadding(0, dp(3), 0, 0);
        tb.addView(accountCap);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView batchBtn = new TextView(this);
        batchBtn.setText(batchMode ? "完成" : "批量管理");
        batchBtn.setTextSize(13);
        batchBtn.setTypeface(Typeface.DEFAULT_BOLD);
        batchBtn.setTextColor(UiStyle.BRAND_TEXT);
        batchBtn.setGravity(Gravity.CENTER);
        batchBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        batchBtn.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 16, UiStyle.LINE));
        UiStyle.press(batchBtn);
        batchBtn.setOnClickListener(v -> {
            batchMode = !batchMode;
            if (!batchMode) selectedAccounts.clear();
            batchBtn.setText(batchMode ? "完成" : "批量管理");
            refreshAccounts();
        });
        batchBtn.setTag("batchBtn");
        accBatchBtn = batchBtn;
        top.addView(batchBtn);
        box.addView(top);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(12);
        top.setLayoutParams(tlp);

        // 批量条（M2 契约 §3.1）：[全部上线/全部停止] + 进度文案 + [刷新]
        LinearLayout poolBar = new LinearLayout(this);
        poolBar.setOrientation(LinearLayout.HORIZONTAL);
        poolBar.setGravity(Gravity.CENTER_VERTICAL);
        poolBar.setPadding(0, 0, 0, dp(8));
        poolBarLoginAll = poolMiniBtn("全部上线",
                UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
        poolBarLoginAll.setOnClickListener(v -> runGlobalBatch(VaultSync.ACTION_LOGIN_ALL));
        poolBar.addView(poolBarLoginAll);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spl.leftMargin = dp(8);
        poolBarStopAll = poolMiniBtn("全部停止",
                UiStyle.SURFACE_3, UiStyle.INK2, UiStyle.LINE_STRONG);
        poolBarStopAll.setOnClickListener(v -> runGlobalBatch(VaultSync.ACTION_STOP_ALL));
        poolBar.addView(poolBarStopAll, spl);
        poolBarProgress = new TextView(this);
        poolBarProgress.setTextSize(11);
        poolBarProgress.setTextColor(UiStyle.INK2);
        poolBarProgress.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        poolBarProgress.setMaxLines(1);
        poolBar.addView(poolBarProgress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        poolBarRefresh = poolMiniBtn("刷新", UiStyle.WHITE, UiStyle.INK2, UiStyle.LINE_STRONG);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.leftMargin = dp(8);
        poolBarRefresh.setOnClickListener(v -> {
            pollPoolOnce();
            refreshAccounts();
        });
        poolBar.addView(poolBarRefresh, rlp2);
        box.addView(poolBar);

        // stale 横幅：上次状态获取失败 → 保留上次状态展示 +「点击重试」（不误判离线）
        poolBanner = new TextView(this);
        poolBanner.setText("状态获取失败，点击重试");
        poolBanner.setTextSize(11.5f);
        poolBanner.setTypeface(Typeface.DEFAULT_BOLD);
        poolBanner.setTextColor(UiStyle.WARN_INK);
        poolBanner.setPadding(dp(10), dp(7), dp(10), dp(7));
        poolBanner.setBackground(UiStyle.roundRectDp(this, UiStyle.WARN_BG, 8));
        poolBanner.setVisibility(View.GONE);
        poolBanner.setOnClickListener(v -> pollPoolOnce());   // 重试；成功后 stale 清除自动隐藏
        box.addView(poolBanner);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.bottomMargin = dp(8);
        poolBanner.setLayoutParams(blp2);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(14), dp(4), dp(14), dp(4));
        searchBox.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 14, UiStyle.LINE));
        searchBox.addView(new UiStyle.Icon(this, UiStyle.Icon.SEARCH, UiStyle.INK3, 19),
                new LinearLayout.LayoutParams(dp(19), dp(19)));
        searchInput = new EditText(this);
        searchInput.setHint("搜索备注名 / 号 ID / 角色名 / 区服");
        searchInput.setTextSize(13.5f);
        searchInput.setTextColor(UiStyle.INK);
        searchInput.setHintTextColor(UiStyle.INK3);
        searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(10), dp(12), dp(4), dp(12));
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(searchBox);
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

        // 列表 / 卡片 视图切换（设计稿 S3：选中项白底浮起）
        LinearLayout viewTabs = new LinearLayout(this);
        viewTabs.setOrientation(LinearLayout.HORIZONTAL);
        viewTabs.setPadding(0, dp(6), 0, dp(4));
        accViewList = new TextView(this);
        accViewList.setText("列表");
        accViewList.setTextSize(13);
        accViewList.setTypeface(Typeface.DEFAULT_BOLD);
        accViewList.setGravity(Gravity.CENTER);
        accViewList.setPadding(dp(16), dp(7), dp(16), dp(7));
        accViewCard = new TextView(this);
        accViewCard.setText("卡片");
        accViewCard.setTextSize(13);
        accViewCard.setGravity(Gravity.CENTER);
        accViewCard.setPadding(dp(16), dp(7), dp(16), dp(7));
        viewTabs.addView(accViewList);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(10);
        viewTabs.addView(accViewCard, vlp);
        UiStyle.press(accViewList);
        UiStyle.press(accViewCard);
        accViewList.setOnClickListener(v -> {
            accView = 0;
            refreshAccounts();
        });
        accViewCard.setOnClickListener(v -> {
            accView = 1;
            refreshAccounts();
        });
        box.addView(viewTabs);
        updateAccViewToggle();

        ScrollView sc = new ScrollView(this);
        accountList = new LinearLayout(this);
        accountList.setOrientation(LinearLayout.VERTICAL);
        accountList.setPadding(0, 0, 0, dp(100));
        sc.addView(accountList);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // FAB ＋（新增；长按=批量导入）。设计 token：56dp 正圆 + 品牌底 + 浮起阴影
        FrameLayout fabWrap = new FrameLayout(this);
        TextView fab = new TextView(this);
        fab.setText("+");
        fab.setTextSize(30);
        fab.setTextColor(UiStyle.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setContentDescription("新增账号（长按批量导入）");
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(UiStyle.BRAND);
        fg.setCornerRadius(dp(28));
        fab.setBackground(fg);
        fab.setElevation(dp(4));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.RIGHT | Gravity.BOTTOM);
        flp.rightMargin = dp(18);
        flp.bottomMargin = dp(92);
        fabWrap.addView(fab, flp);
        UiStyle.press(fab);
        fab.setOnClickListener(v -> showCreateEntry());
        fab.setOnLongClickListener(v -> {
            showBatchImport();
            return true;
        });
        pageRoot.addView(fabWrap, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 角色行（账号卡展开后）：游戏角色名 · K区服 · 今日状态 · 上次 · ▶跑日常。 */
    /** 跑单个角色：把激活角色对齐到它（数据写读同源）再走执行引擎。 */
    private void runRole(final AccountStore.Account a, final long sid, final long uid) {
        if (RUN.running) {
            showRunPanel();
            return;
        }
        RoleStore.setActiveRole(this, a.fpid, sid, uid);
        // 跑日常即把激活角色对齐到它：同样标记待上传 + 触发同步（服务器按 activeUid 绑定角色）
        try {
            VaultSync.notifyRoleSwitched(this, a.fpid);
        } catch (Throwable ignored) {
        }
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

    /** 卡片视图：3 列账号图标墙（游戏图标 + 备注名 + 登录方式/上次登录）。 */
    private void renderRoleMatrix(List<AccountStore.Account> shown,
                                  java.util.Map<String, List<RoleStore.Role>> rolesMap,
                                  java.util.Map<String, int[]> roleStats,
                                  java.util.Map<String, Long> roleLast) {
        // 卡片视图 = 旧版 3 列入口墙：游戏图标 + 名称 + token/原生/无 + 最近登录。
        int per = 3;
        LinearLayout row = null;
        for (int i2 = 0; i2 < shown.size(); i2++) {
            if (i2 % per == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(16);
                accountList.addView(row, rlp);
            }
            final AccountStore.Account a = shown.get(i2);

            android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
            tile.setBackground(UiStyle.roundRectDp(this, UiStyle.CARD, 14));
            tile.setElevation(dp(2));

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(dp(6), dp(14), dp(6), dp(14));
            content.addView(gameIconSquare(60, a.label, a.id),
                    new LinearLayout.LayoutParams(dp(60), dp(60)));

            TextView name = new TextView(this);
            name.setText(a.label == null || a.label.isEmpty() ? "?" : a.label);
            name.setTextSize(13);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(UiStyle.INK);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(8);
            content.addView(name, nlp);

            LinearLayout subRow = new LinearLayout(this);
            subRow.setOrientation(LinearLayout.HORIZONTAL);
            subRow.setGravity(Gravity.CENTER_VERTICAL);
            boolean hasToken = a.token != null && !a.token.isEmpty();
            boolean hasSnap = false;
            try {
                hasSnap = engine.hasEntrySnapshot(a.id);
            } catch (Throwable ignored) {
            }
            TextView subStatus = new TextView(this);
            subStatus.setText(hasToken ? "token" : (hasSnap ? "原生" : "无"));
            subStatus.setTextSize(9.5f);
            subStatus.setMaxLines(1);
            subStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subStatus.setTextColor(UiStyle.INK2);
            subStatus.setGravity(Gravity.START);
            subRow.addView(subStatus, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView subTime = new TextView(this);
            subTime.setText(a.lastLogin > 0
                    ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(a.lastLogin)) : "未用");
            subTime.setTextSize(9.5f);
            subTime.setMaxLines(1);
            subTime.setTextColor(UiStyle.INK2);
            subTime.setGravity(Gravity.END);
            subTime.setPadding(dp(4), 0, 0, 0);
            subRow.addView(subTime);
            content.addView(subRow);

            tile.addView(content, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (batchMode) {
                android.widget.FrameLayout.LayoutParams cklp =
                        new android.widget.FrameLayout.LayoutParams(dp(22), dp(22),
                                Gravity.TOP | Gravity.START);
                cklp.leftMargin = dp(6);
                cklp.topMargin = dp(6);
                tile.addView(batchCheckbox(a), cklp);
            }

            UiStyle.press(tile);
            tile.setOnClickListener(v -> {
                if (batchMode) toggleBatch(a);
                else showAccountDetail(a);
            });
            tile.setOnLongClickListener(v -> {
                accountMenu(a);
                return true;
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = dp(4);
            tlp.rightMargin = dp(4);
            row.addView(tile, tlp);
        }
        if (row != null && shown.size() % per != 0) {
            for (int i = shown.size() % per; i < per; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        if (shown.isEmpty()) {
            accountList.addView(emptyTip("没有匹配的账号"));
        }
    }

    /** 角色操作菜单（矩阵格子/角色行用）。 */
    private void roleMenu(final AccountStore.Account a, final RoleStore.Role r) {
        // 标题统一标准展示（昵称 + UID/城堡/战力，缺失段「未同步」）——不再用「同服第N角色」
        String title = RoleDisplay.roleName(r.name)
                + " · " + RoleDisplay.roleMetaLine(r.serverid, r.uid, r.level, r.power);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(title)
                .setItems(new String[]{"▶ 跑日常（该角色）", "📋 日常详情",
                        "🖥 启动游戏（该角色）", "🖥 启动游戏（整个账号）",
                        r.enabled ? "移出工作区" : "纳入工作区"}, (d, w) -> {
                    switch (w) {
                        case 0: runRole(a, r.serverid, r.uid); break;
                        case 1: openFnDetail(a, "daily_mission", r.serverid); break;
                        case 2: launchRole(a, r); break;
                        case 3: doSwitch(a); break;
                        case 4:
                            RoleStore.setEnabled(this, a.fpid, r.serverid, r.uid, !r.enabled);
                            VaultSync.onAccountsChanged(this);
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
                .setItems(new String[]{"⏻ 登录并运行自动化", "🎁 每日任务（进度/领取）",
                        "▶ 启动游戏", "👥 角色管理", "⏱ 恢复设置", "⇅ 排序（旧视图）"}, (d, w) -> {
                    switch (w) {
                        case 0: loginAndRun(a, null); break;
                        case 1: openDaily(a, null); break;
                        case 2: doSwitch(a); break;
                        case 3:
                            showRoleManager(a, RoleStore.roles(this, a.fpid), accountOnline(a));
                            break;
                        case 4: showRecoverySettingsDialog(a); break;
                        case 5: openOld(); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 账号级“登录”按钮（账号菜单/卡片头）：切到该账号<b>主角色</b> → 以该角色
     * 模拟登录在线。语义与角色行「登录」一致（{@link #loginRole}），只是角色
     * 取主角色而非钉扎角色。
     */
    private void loginAndRun(final AccountStore.Account a, final Runnable done) {
        if (a == null || a.fpid == null || a.fpid.isEmpty()
                || a.token == null || a.token.isEmpty()) {
            toast("该账号没有可用 token");
            if (done != null) done.run();
            return;
        }
        long[] main = mainRole(a);
        long sid = main[0];
        if (sid <= 0) sid = activeSid(a);
        // setCurrentRole 由 loginRole 统一执行（钉扎 sid+uid，含防回滚标记），避免双写
        loginRole(a, sid, main[1], done);
    }

    /**
     * 角色级“登录”（第二轮 UI 语义）：以指定角色 (sid,uid) <b>模拟登录在线</b> ——
     * 切到该角色（复合身份钉扎）→ 上行 activeUid → 服务器建会话并排队整轮。
     * <b>不是启动游戏客户端</b>（启动客户端走 {@link #launchRole}/{@link #doSwitch}）。
     *
     * <p>保持既有整轮触发行为：与账号级登录同一后半程
     * （{@link VaultSync#loginRoleOnline}）。回调在主线程收尾，按钮由调用方恢复。
     */
    private void loginRole(final AccountStore.Account a, final long sid, final long uid,
                           final Runnable done) {
        if (a == null || a.fpid == null || a.fpid.isEmpty()
                || a.token == null || a.token.isEmpty()) {
            toast("该账号没有可用 token");
            if (done != null) done.run();
            return;
        }
        if (sid > 0) setCurrentRole(a.fpid, sid, uid);
        toast("正在上传配置并登录服务器…");
        VaultSync.loginRoleOnline(this, a.fpid, (code, message, data) -> {
            if (code != 0) {
                runOnUiThread(() -> {
                    toast("配置同步失败：" + message);
                    if (done != null) done.run();
                });
                return;
            }
            boolean ok = data != null && data.optBoolean("accepted", false);
            String state = data == null ? "" : data.optString("state", "");
            String sidText = data == null ? "" : data.optString("sid", "");
            int cfgCount = 0;
            try {
                org.json.JSONObject cfg = data == null ? null : data.optJSONObject("config");
                if (cfg != null) cfgCount = cfg.length();
            } catch (Throwable ignored) {
            }
            final int fCfgCount = cfgCount;
            runOnUiThread(() -> {
                if (ok) {
                    toast("已登录并触发服务器自动化"
                            + (sidText.isEmpty() ? "" : " · sid=" + sidText)
                            + (state.isEmpty() ? "" : " · 会话=" + state)
                            + (fCfgCount > 0 ? " · 配置 " + fCfgCount + " 项" : ""));
                    refreshAllPages();
                    VaultSync.fetchState(this);
                } else {
                    toast("服务器未受理：" + message);
                }
                if (done != null) done.run();
            });
        });
    }

    /** 账号详情（设计稿 S4）：复用入口管理页，展示角色/登录数据/入口管理。 */
    private void showAccountDetail(AccountStore.Account a) {
        if (a == null) return;
        startActivity(new android.content.Intent(this, EntryDetailActivity.class)
                .putExtra(EntryDetailActivity.EXTRA_ID, a.id));
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
            // 账号池（M2）：服务器唯一事实源；无缓存/请求失败保留旧值（stale 由 VaultSync 标记）。
            // 旧服务端没有池接口（unsupported，生产 v4.1.4 常态）→ 直接 legacy 渲染，
            // 不吃旧缓存（M4 §3.3：横幅也不显示）
            final boolean poolUnsupported = VaultSync.isPoolUnsupported(HomeActivity.this);
            org.json.JSONObject pool = poolUnsupported
                    ? null : VaultSync.cachedAccountPool(HomeActivity.this);
            java.util.Map<String, org.json.JSONObject> poolAcc =
                    com.carlos.vault.AccountPoolCache.accountsByFpid(pool);
            final int serverOnline = pool == null ? -1 : pool.optInt("onlineCount", -1);
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
                // 搜索（M2 扩展网页版口径）：备注名 / 号ID(fpid) / 角色名 / 区服(sid)
                if (!q.isEmpty()) {
                    boolean accHit = (a.label != null && a.label.contains(q))
                            || (a.fpid != null && a.fpid.contains(q));
                    boolean roleHit = false;
                    List<RoleStore.Role> qrs = accRoles.get(a.id);
                    if (qrs != null) {
                        for (RoleStore.Role qr : qrs) {
                            if ((qr.name != null && qr.name.contains(q))
                                    || String.valueOf(qr.serverid).contains(q)) {
                                roleHit = true;
                                break;
                            }
                        }
                    }
                    if (!accHit && !roleHit) continue;
                }
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
            final java.util.Map<String, org.json.JSONObject> fPoolAcc = poolAcc;
            final int fServerOnline = serverOnline;
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
                // stale 横幅（M2 契约 §1 降级：保留上次状态 +「状态获取失败」提示；
                // 横幅本体在 buildAccountPage 的静态布局里，这里只切可见性。
                // unsupported=服务端没有池接口（v4.1.4）→ 不算故障，不显示横幅）
                if (poolBanner != null) {
                    poolBanner.setVisibility(!poolUnsupported
                            && VaultSync.isPoolStale(HomeActivity.this)
                            ? View.VISIBLE : View.GONE);
                }
                updateAccSeg();
                if (accView == 1) {
                    renderRoleMatrix(fShown, fRoles, fRoleStats, fRoleLast);
                } else {
                    // 列表视图 = 账号分组框（账号头 + 工作区角色表格，每行可单独登录）。
                    // 第二轮 UI：弃用只有账号级登录按钮的简单卡片——同服多角色的
                    // 每个工作区角色都必须可达（简单卡片只剩长按菜单一条路）。
                    for (AccountStore.Account a : fShown) {
                        accountList.addView(accountGroupCard(a, fStats.get(a.id),
                                fRoles.get(a.id), fRoleStats, fRoleLast, fOnline,
                                fLiveUid, fLiveEntry, fPoolAcc.get(a.fpid)));
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
        // 顶栏计数「N 个账号 · 工作区 M · K 个在线」（M4 §3.3 口径统一）：
        // M = 全部账号的启用角色总数（与组头「工作区 N」同源，本地 RoleStore 数据，
        // 不额外请求；原账号数口径与组头不一致）；K 优先服务器聚合 onlineCount
        // （服务端唯一事实源，M2 契约 §5.1），无池数据回退本地快照判定计数
        int wsRoleCnt = 0;
        for (java.util.List<RoleStore.Role> rs : fRoles.values()) {
            if (rs == null) continue;
            for (RoleStore.Role r0 : rs) if (r0 != null && r0.enabled) wsRoleCnt++;
        }
        int onCnt = 0;
        for (Boolean b : fOnline.values()) if (Boolean.TRUE.equals(b)) onCnt++;
        if (fServerOnline >= 0) onCnt = fServerOnline;
        if (accountCap != null)
            accountCap.setText(accounts.size() + " 个账号 · 工作区 " + wsRoleCnt
                    + " · " + onCnt + " 个在线");
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

    /** 角色表各列宽度（dp）。昵称列吃剩余宽度，其余定宽以保证逐行对齐（状态列容纳「● 已暂停·待恢复」）。
     *  P1：COL_AB=防封保护开关列（UiStyle.Switch 原生 46dp 宽）。 */
    private static final int COL_ONLINE = 74, COL_ZONE = 24, COL_DONE = 38, COL_LAST = 38,
            COL_GO = 74, COL_AB = 46;

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
                               long actUid, boolean isLiveAcc, long liveUid) {
        if (!accOn) return false;
        if (isLiveAcc && liveUid > 0) return r.uid > 0 && r.uid == liveUid;
        // 激活角色 uid 已知时按 uid 精确命中：同服两个角色不会再被同一个 actSid 同时判成"在线"
        if (actUid > 0 && r.uid > 0) return r.uid == actUid;
        return r.serverid == actSid;
    }

    /**
     * 角色行在线<b>文案与口径</b>（返回 [文案, "on"/"off"]）：
     * <ul>
     *   <li><b>服务器模式</b>（第二轮 UI 统一的主口径）：账号态取
     *       {@link VaultSync#cachedStatusOf}，按 sid+activeUid 匹配本行
     *       （{@link RoleDisplay#roleMatchesServerStatus}）；文案走
     *       {@link RoleDisplay#serverStateLabel}（在线/重连中/被顶号暂停/需重导token/
     *       离线）——与今日页常驻状态、角色管理行同一状态源；</li>
     *   <li><b>本地模式</b>（ legacy 兜底）：
     *       <b>日常在线</b>（RoleSessionKeeper 协议常驻会话 online，fpid:sid 粒度）/
     *       <b>游戏在线</b>（容器 live playerprefs uid 反查）/
     *       <b>在线</b>（快照有效登录态 + 激活角色推断）/<b>离线</b>。</li>
     * </ul>
     */
    private String[] roleOnlineLabel(AccountStore.Account a, RoleStore.Role r, boolean accOn,
                                     long actSid, long actUid, boolean isLiveAcc, long liveUid) {
        try {
            // 服务器模式：统一服务器状态源（无缓存=刚打开还没同步，显示"同步中"）
            if (VaultSync.isServerMode(this)) {
                org.json.JSONObject o = a.fpid == null ? null : VaultSync.cachedStatusOf(this, a.fpid);
                if (o == null) return new String[]{"● 同步中", "off"};
                String state = o.optString("state", "");
                long statusSid = parseLongSafe(o.optString("sid", ""));
                long statusUid = parseLongSafe(o.optString("activeUid", ""));
                boolean match = RoleDisplay.roleMatchesServerStatus(
                        r.serverid, r.uid, statusSid, statusUid, actSid, actUid);
                if (!match) return new String[]{"● 离线", "off"};
                boolean on = RoleDisplay.isServerOnline(state);
                return new String[]{"● " + RoleDisplay.serverStateLabel(state), on ? "on" : "off"};
            }
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
        if (roleOnline(a, r, accOn, actSid, actUid, isLiveAcc, liveUid)) {
            return new String[]{"● 在线", "on"};
        }
        return new String[]{"● 离线", "off"};
    }

    /** 服务器回传的 sid/uid 字符串安全解析（<=0/非法 → 0）。 */
    private static long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
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

    /** 卡片视图的旧版图标：直接使用游戏 APK 图标（不裁切）。 */
    private android.view.View gameIconSquare(int sizeDp, String label, String seed) {
        android.graphics.drawable.Drawable ic = gameIcon();
        if (ic != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(ic);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            return iv;
        }
        return avatarView(sizeDp, label, seed);
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
        r.addView(colHead("防封", COL_AB, Gravity.CENTER));
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

    /** 批量态勾选框（列表行与卡片格共用）。 */
    private TextView batchCheckbox(final AccountStore.Account a) {
        boolean sel = selectedAccounts.contains(a.id);
        TextView check = new TextView(this);
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
        check.setOnClickListener(v -> toggleBatch(a));
        return check;
    }

    /** 翻转批量选中并重绘（勾选框与整行/整格点击同源）。 */
    private void toggleBatch(AccountStore.Account a) {
        if (selectedAccounts.contains(a.id)) selectedAccounts.remove(a.id);
        else selectedAccounts.add(a.id);
        refreshAccounts();
    }

    /** 账号分组框：头行=备注名+登录方式(原生/token)+⚙；框内=已入工作区的角色独立入口卡。 */
    private android.view.View accountGroupCard(final AccountStore.Account a, final String[] st,
                                               final List<RoleStore.Role> roles,
                                               final java.util.Map<String, int[]> roleStats,
                                               final java.util.Map<String, Long> roleLast,
                                               final java.util.Map<String, Boolean> online,
                                               final long liveUid, final String liveEntryId,
                                               final org.json.JSONObject poolAcc) {
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
        final long actUid = RoleStore.activeRoleUid(this, a.fpid);
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

        // ⏱ 恢复设置（P1 契约 §三.3）：顶号自动恢复策略（账号级，作用于全部角色）
        LinearLayout recPill = headerPill("恢复设置", UiStyle.Icon.CLOCK,
                UiStyle.INK2, UiStyle.SURFACE_3, UiStyle.LINE_STRONG);
        recPill.setOnClickListener(v -> showRecoverySettingsDialog(a));
        LinearLayout.LayoutParams rplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rplp.rightMargin = dp(6);
        head.addView(recPill, rplp);

        // ⚙ 角色管理：唯一的管理入口之一（另一个 = 点角色行）。卡片其它区域不响应，避免误触弹层。
        // 图标为程序化矢量 Icon（文本字符会随机型字体异形）。
        LinearLayout gear = headerPill("角色管理", UiStyle.Icon.GEAR,
                UiStyle.BRAND_TEXT, UiStyle.BRAND_50, UiStyle.BRAND_200);
        gear.setOnClickListener(v -> showRoleManager(a, roles, accOn));
        head.addView(gear);
        box.addView(head);

        // 框内：已启用角色 → 表格（状态/角色/区号/今日完成/上次登录/动作），全部可见可滚动
        // （第二轮 UI：移除"最多 4 行 + 还有 N 个"折叠——同服多角色账号必须全部可达）
        int enN = 0;
        if (roles != null) for (RoleStore.Role r0 : roles) if (r0.enabled) enN++;
        if (enN > 0) {
            LinearLayout secH = new LinearLayout(this);
            secH.setOrientation(LinearLayout.HORIZONTAL);
            secH.setGravity(Gravity.CENTER_VERTICAL);
            secH.setPadding(0, dp(6), 0, 0);
            // 组头「工作区 N · 在线 N」（M2 网页版口径）：在线数优先服务器聚合，回退本地判定
            TextView l = new TextView(this);
            int poolOn = poolAcc == null ? -1 : poolAcc.optInt("onlineCount", -1);
            boolean headOn = poolOn > 0 || accOn;
            if (poolOn < 0 && !headOn) {
                try {
                    for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                            : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                        if (a.fpid != null && a.fpid.equals(ls.fpid)
                                && com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                            headOn = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            l.setText("工作区 " + enN + " · 在线 " + (poolOn >= 0 ? poolOn : (headOn ? 1 : 0)));
            l.setTextSize(10);
            l.setTypeface(Typeface.DEFAULT_BOLD);
            l.setTextColor(headOn ? UiStyle.OK_INK : UiStyle.INK3);
            secH.addView(l, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // 账号级批量（M2 契约 §3.1 组头「全部上线/全部停止」= 工作区角色批量）
            boolean accBusy = poolAccBusy.contains(a.fpid);
            TextView accLoginAll = poolMiniBtn(accBusy ? "执行中" : "全部上线",
                    UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
            accLoginAll.setOnClickListener(v -> poolAccountAction(a, VaultSync.ACTION_LOGIN_ALL));
            secH.addView(accLoginAll);
            TextView accStopAll = poolMiniBtn(accBusy ? "执行中" : "全部停止",
                    UiStyle.SURFACE_3, UiStyle.INK2, UiStyle.LINE_STRONG);
            LinearLayout.LayoutParams aslp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aslp.leftMargin = dp(6);
            secH.addView(accStopAll, aslp);
            accStopAll.setOnClickListener(v -> poolAccountAction(a, VaultSync.ACTION_STOP_ALL));
            if (accBusy) {
                accLoginAll.setEnabled(false);
                accStopAll.setEnabled(false);
                accLoginAll.setAlpha(.5f);
                accStopAll.setAlpha(.5f);
            }
            box.addView(secH);
            box.addView(roleTableHead());
            long nowMs = System.currentTimeMillis();
            for (final RoleStore.Role r : roles) {
                if (!r.enabled) continue;
                org.json.JSONObject poolRole = matchPoolRole(poolAcc, r);
                box.addView(roleEntryCard(a, r,
                        roleStats.get(a.fpid + ":" + r.serverid),
                        roleLast.containsKey(a.fpid + ":" + r.serverid)
                                ? roleLast.get(a.fpid + ":" + r.serverid) : 0L,
                        roleOnlineLabel(a, r, accOn, actSid, actUid, isLiveAcc, liveUid), accOn, roles,
                        com.carlos.vault.AccountPoolCache.stateOfRole(poolRole), poolRole,
                        poolBusy.contains(a.fpid + ":" + r.serverid + ":" + r.uid), nowMs));
            }
        } else {
            // 空态（M2 契约 §3.1）：无角色=引导登录发现；有角色未入工作区=「尚未选择工作区角色」+选择角色
            LinearLayout tipBox = new LinearLayout(this);
            tipBox.setOrientation(LinearLayout.HORIZONTAL);
            tipBox.setGravity(Gravity.CENTER_VERTICAL);
            tipBox.setPadding(dp(4), dp(10), dp(4), dp(4));
            TextView tip = new TextView(this);
            tip.setText(roles == null || roles.isEmpty()
                    ? "暂无角色数据（登录一次自动发现）"
                    : "尚未选择工作区角色");
            tip.setTextSize(11.5f);
            tip.setTextColor(UiStyle.INK2);
            tipBox.addView(tip, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (roles != null && !roles.isEmpty()) {
                TextView pick = poolMiniBtn("选择角色",
                        UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
                pick.setOnClickListener(v -> showRoleManager(a, roles, accOn));
                tipBox.addView(pick);
            }
            box.addView(tipBox);
        }
        // 长按账号框 = 账号菜单（批量/登录并运行/启动游戏/角色管理/排序，与简单卡片一致）
        UiStyle.press(box);
        box.setOnLongClickListener(v -> {
            if (!batchMode) accountMenu(a);
            return true;
        });
        return box;
    }

    /**
     * 角色行（账号框内嵌 · 表格化，M2 账号池版）：
     * {@code 状态（● + 11 态文案） | 昵称 | 区号 | 今日完成 | 上次登录 | [上线/暂停/停止/登录]}。
     *
     * <p>状态与动作渲染（M2 契约 §2/§3.1 + M4 §3.2 修复）：
     * <ul>
     *   <li>池状态可用（{@code poolState != null}）：文案/配色走 {@link RoleRuntimeState}
     *       纯函数（与网页版 §2.3 逐字一致）；动作按矩阵——在线→[暂停][停止]，
     *       暂停→[上线][停止]（服务端 login 等价 resume），其它非在线（含已停止/
     *       需登录/待恢复/计划休眠）→[上线] role-action；行内不再出现
     *       「登录（服务器自动化）」（保留在账号详情页与降级/无池路径）；</li>
     *   <li>池状态不可用（旧服务端/请求失败）：沿用 {@link #roleOnlineLabel} 旧文案
     *       与「登录」按钮（保留现有行为，stale 横幅提示）；</li>
     *   <li>被顶号/待恢复 → 行下 banner「账号被顶号 · 待恢复 mm:ss」（倒计时随轮询刷新）。</li>
     * </ul>
     * P1 扩展（契约 §三）：「防封」列 = UiStyle.Switch（状态来自 pool 数据
     * {@code roles[].antibanEnabled}，开启前确认弹窗）；「已暂停·待恢复」态附
     * pause_resume_at 倒计时；长按行 = 角色动作菜单（重命名/移除/启用收纳）。
     * 点<b>行</b> = 打开角色管理；动作按钮走 role-action（进行中禁点，失败 toast 服务端
     * message，成功后立即刷新）。点「上线」直接 role-action login（M2d：同账号
     * 多角色并行在线，服务端不再互斥，无前置确认弹窗）。
     */
    private android.view.View roleEntryCard(final AccountStore.Account a, final RoleStore.Role r,
                                            int[] dayStats, long lastTs, String[] onlineKind, boolean accOn,
                                            final List<RoleStore.Role> allRoles,
                                            final com.carlos.vault.daily.RoleRuntimeState poolState,
                                            final org.json.JSONObject poolRole,
                                            final boolean roleBusy, final long nowMs) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int done = dayStats == null ? 0 : dayStats[0];
        int total = dayStats == null ? 0 : dayStats[3];

        // ① 状态：文字 + 颜色双重编码（不靠颜色单独分辨）。池状态=11 态文案，否则旧口径
        TextView stv = new TextView(this);
        int stColor = UiStyle.INK3;
        if (poolState != null) {
            String tone = poolState.tone();
            stColor = "ok".equals(tone) ? UiStyle.OK_INK
                    : "warn".equals(tone) ? UiStyle.WARN_INK
                    : "err".equals(tone) ? UiStyle.ERR_INK
                    : "busy".equals(tone) ? UiStyle.RUN_INK : UiStyle.INK3;
            stv.setText("● " + poolState.label(nowMs));
        } else {
            boolean online = onlineKind != null && "on".equals(onlineKind[1]);
            stv.setText(onlineKind == null ? "● 离线" : onlineKind[0]);
            stColor = online ? UiStyle.OK_INK : UiStyle.INK3;
        }
        stv.setTextSize(9.5f);
        stv.setTypeface(Typeface.DEFAULT_BOLD);
        stv.setTextColor(stColor);
        stv.setMaxLines(1);
        stv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stv.setWidth(dp(COL_ONLINE));
        row.addView(stv);

        // ② 昵称（空=未同步；同服多角色靠角色管理弹层的 UID 段区分）
        TextView nm = new TextView(this);
        nm.setText(RoleDisplay.roleName(r.name));
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

        // ⑤.5 防封保护开关（P1 契约 §三.1）：在线/离线均可切换；状态来自池数据
        // （roles[].antibanEnabled），刷新后同步；无池数据（旧服务端）占位对齐不渲染。
        LinearLayout abBox = new LinearLayout(this);
        abBox.setGravity(Gravity.CENTER);
        abBox.setLayoutParams(new LinearLayout.LayoutParams(dp(COL_AB),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(abBox);
        if (poolRole != null && poolState != null && !poolState.degraded) {
            final boolean abOn = com.carlos.vault.AccountPoolCache.antibanOf(poolRole);
            if (!poolState.enabled) {
                // 已收纳：开关随行置灰禁点（契约 §三.4）
                UiStyle.Switch sw = new UiStyle.Switch(this, abOn, null);
                sw.setEnabled(false);
                sw.setAlpha(.4f);
                abBox.addView(sw);
            } else {
                final UiStyle.Switch[] abRef = new UiStyle.Switch[1];
                abRef[0] = new UiStyle.Switch(this, abOn, () -> {
                    // Switch 点击即翻转：先回弹到池数据状态（成功后轮询刷新驱动真实值），
                    // 开启走确认弹窗（逐字契约），关闭直接调用
                    final boolean want = abRef[0].isOn();
                    abRef[0].setOn(abOn);
                    PoolRoleActions.toggleAntiban(HomeActivity.this, a, r, want,
                            () -> runOnUiThread(() -> {
                                pollPoolOnce();
                                refreshAccounts();
                            }));
                });
                abBox.addView(abRef[0]);
            }
        }

        // ⑥ 动作列（M2）：按状态矩阵渲染；进行中整列「执行中」禁点
        LinearLayout goBox = new LinearLayout(this);
        goBox.setOrientation(LinearLayout.HORIZONTAL);
        goBox.setGravity(Gravity.END);
        LinearLayout.LayoutParams goblp = new LinearLayout.LayoutParams(dp(COL_GO),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(goBox, goblp);
        if (poolState != null && !poolState.degraded && poolState.enabled && !roleBusy) {
            if (poolState.online) {
                // 在线 → [暂停][停止]
                TextView pauseBtn = poolMiniBtn("暂停",
                        UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
                pauseBtn.setOnClickListener(v -> poolRoleAction(a, r, VaultSync.ACTION_PAUSE));
                goBox.addView(pauseBtn);
                TextView stopBtn = poolMiniBtn("停止",
                        UiStyle.SURFACE_3, UiStyle.INK2, UiStyle.LINE_STRONG);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.leftMargin = dp(4);
                goBox.addView(stopBtn, slp);
                stopBtn.setOnClickListener(v -> poolRoleAction(a, r, VaultSync.ACTION_STOP));
            } else if (poolState.canResume()) {
                // 暂停 → [上线][停止]（服务端 login 对暂停会话等价 resume）
                TextView upBtn = poolMiniBtn("上线",
                        UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
                upBtn.setOnClickListener(v -> poolRoleAction(a, r, VaultSync.ACTION_LOGIN));
                goBox.addView(upBtn);
                TextView stopBtn = poolMiniBtn("停止",
                        UiStyle.SURFACE_3, UiStyle.INK2, UiStyle.LINE_STRONG);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.leftMargin = dp(4);
                goBox.addView(stopBtn, slp);
                stopBtn.setOnClickListener(v -> poolRoleAction(a, r, VaultSync.ACTION_STOP));
            } else {
                // 非在线一律 role-action「上线」（M4 §3.2 修复：含已停止/需登录/待恢复/
                // 计划休眠——池状态可用时「登录（服务器自动化）」不再出现在行内，
                // 仅保留在降级/无池路径与账号详情页；M2d：上线直连 login，无互斥确认）
                TextView upBtn = poolMiniBtn("上线",
                        UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
                upBtn.setOnClickListener(v -> poolRoleAction(a, r, VaultSync.ACTION_LOGIN));
                goBox.addView(upBtn);
            }
        } else if (poolState != null && !poolState.degraded && !poolState.enabled) {
            // 已收纳：动作矩阵全部禁用（契约 §2），去「角色管理」启用后再操作
            TextView dis = poolMiniBtn("已收纳", UiStyle.SURFACE_3, UiStyle.INK3, UiStyle.LINE);
            dis.setEnabled(false);
            dis.setAlpha(.5f);
            goBox.addView(dis);
        } else if (roleBusy) {
            TextView busy = poolMiniBtn("执行中", UiStyle.SURFACE_3, UiStyle.INK3, UiStyle.LINE);
            busy.setEnabled(false);
            busy.setAlpha(.6f);
            goBox.addView(busy);
        } else {
            // 降级（旧服务端/已收纳/无池数据）：保留现有「登录」行为
            goBox.addView(legacyLoginBtn(a, r));
        }

        // ⑦ 被顶号/待恢复 banner（行下，M2 契约 §3.1）
        if (poolState != null) {
            String banner = poolState.bannerText(nowMs);
            if (banner != null) {
                TextView bv = new TextView(this);
                bv.setText(banner);
                bv.setTextSize(10);
                bv.setTypeface(Typeface.DEFAULT_BOLD);
                bv.setTextColor(UiStyle.WARN_INK);
                bv.setPadding(dp(8), dp(5), dp(8), dp(5));
                bv.setBackground(UiStyle.roundRectDp(this, UiStyle.WARN_BG, 8));
                LinearLayout.LayoutParams bvlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bvlp.topMargin = dp(2);
                wrap.addView(bv, bvlp);
            }
        }

        // 点角色行 = 角色管理（与齿轮同一入口）；长按 = P1 角色动作菜单
        // （重命名/移除/启用收纳——池数据可用才挂，旧服务端走角色管理本地开关）
        UiStyle.press(row);
        row.setOnClickListener(v -> showRoleManager(a, allRoles, accOn));
        if (poolRole != null && poolState != null && !poolState.degraded) {
            row.setOnLongClickListener(v -> {
                PoolRoleActions.showMenu(HomeActivity.this, a, r,
                        poolRole.optBoolean("enabled", true), () -> runOnUiThread(() -> {
                            pollPoolOnce();
                            refreshAccounts();
                        }));
                return true;
            });
        }
        return wrap;
    }

    /** 保留的行内「登录」按钮（= 登录并触发服务器自动化，非启动游戏客户端）。 */
    private TextView legacyLoginBtn(final AccountStore.Account a, final RoleStore.Role r) {
        TextView go = poolMiniBtn("登录", UiStyle.BRAND_50, UiStyle.BRAND_TEXT, UiStyle.BRAND_200);
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            go.setText("登录中");
            loginRole(a, r.serverid, r.uid, () -> refreshAccounts());
        });
        return go;
    }

    // ==================== 账号池动作与批量（M2 契约 §3.1） ====================

    /** 小动作按钮（角色行动作列/批量条/组头共用）：11sp 粗体圆角药丸。 */
    private TextView poolMiniBtn(String text, int bg, int ink, int stroke) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(ink);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(bg);
        bg2.setCornerRadius(dp(8));
        bg2.setStroke(dp(1), stroke);
        b.setBackground(bg2);
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        UiStyle.press(b);
        return b;
    }

    /** 账号组头右侧胶囊按钮（恢复设置/角色管理共用）：圆角浅底 + 矢量图标 + 粗体字。 */
    private LinearLayout headerPill(String text, int icon, int ink, int bg, int stroke) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(bg);
        pbg.setCornerRadius(dp(10));
        pbg.setStroke(dp(1), stroke);
        pill.setBackground(pbg);
        pill.setPadding(dp(9), dp(7), dp(9), dp(7));
        pill.addView(new UiStyle.Icon(this, icon, ink));
        TextView tx = new TextView(this);
        tx.setText(text);
        tx.setTextSize(11.5f);
        tx.setTypeface(Typeface.DEFAULT_BOLD);
        tx.setTextColor(ink);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        pill.addView(tx, lp);
        UiStyle.press(pill);
        return pill;
    }

    // ==================== 恢复设置（P1 契约 §三.3） ====================

    /**
     * 顶号恢复设置对话框（账号级，作用于全部角色）：switch + 分钟/次/秒输入 + 冷却下拉
     * （15 分钟/30 分钟/1 小时/2 小时/24 小时）。预填服务端 {@code account.recovery}
     * （旧服务端缺字段回落契约默认值，{@link RecoverySettings#fromAccount}）；
     * 保存前 {@link RecoverySettings#validate} 预校验（文案逐字对齐服务端 3002），
     * 不过不关弹窗；成功 toast「顶号恢复设置已保存并立即生效」。
     */
    private void showRecoverySettingsDialog(final AccountStore.Account a) {
        if (a == null || a.fpid == null || a.fpid.isEmpty()) return;
        org.json.JSONObject poolAcc = com.carlos.vault.AccountPoolCache.accountsByFpid(
                VaultSync.cachedAccountPool(this)).get(a.fpid);
        final RecoverySettings cur = RecoverySettings.fromAccount(poolAcc);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), dp(2));

        TextView intro = new TextView(this);
        intro.setText("意外被顶下线后按以下策略自动顶回，作用于该账号全部角色，保存立即生效");
        intro.setTextSize(11);
        intro.setTextColor(UiStyle.INK2);
        box.addView(intro);

        // 允许意外下线后自动上线（switch）
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.topMargin = dp(12);
        TextView swLabel = new TextView(this);
        swLabel.setText("允许意外下线后自动上线");
        swLabel.setTextSize(13);
        swLabel.setTypeface(Typeface.DEFAULT_BOLD);
        swLabel.setTextColor(UiStyle.INK);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final UiStyle.Switch enSw = new UiStyle.Switch(this, cur.enabled, null);
        swRow.addView(enSw);
        box.addView(swRow, swlp);

        // 顶号后等待（分钟，1~1440，默认 30）
        final EditText delayEt = recoveryNumField(box, "顶号后等待（分钟，1~1440）",
                "默认 30", String.valueOf(cur.initialDelayMinutes));
        // 连续尝试（次，0~10，默认 3；0=不自动顶号，账号保持离线）
        final EditText attemptsEt = recoveryNumField(box, "连续尝试（次，0~10）",
                "默认 3；0=不自动顶号，账号保持离线", String.valueOf(cur.maxAttempts));
        // 每次间隔（秒，1~300，默认 5）
        final EditText retryEt = recoveryNumField(box, "每次间隔（秒，1~300）",
                "默认 5", String.valueOf(cur.retryIntervalSeconds));

        // 让号冷却（下拉：15 分钟/30 分钟/1 小时/2 小时/24 小时，默认 15 分钟）
        TextView cdLabel = new TextView(this);
        cdLabel.setText("让号冷却");
        cdLabel.setTextSize(12.5f);
        cdLabel.setTypeface(Typeface.DEFAULT_BOLD);
        cdLabel.setTextColor(UiStyle.INK);
        LinearLayout.LayoutParams cllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cllp.topMargin = dp(12);
        box.addView(cdLabel, cllp);
        final int[] cd = {cur.cooldownMinutes};
        final TextView cdRow = new TextView(this);
        cdRow.setTextSize(12);
        cdRow.setGravity(Gravity.CENTER_VERTICAL);
        cdRow.setMinHeight(dp(44));
        cdRow.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 12, UiStyle.LINE_STRONG));
        cdRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        final Runnable[] renderCd = new Runnable[1];
        renderCd[0] = () -> renderDropdownText(cdRow, "让号冷却",
                RecoverySettings.cooldownLabel(cd[0]));
        renderCd[0].run();
        final String[] cdLabels = new String[RecoverySettings.COOLDOWNS.length];
        for (int i = 0; i < cdLabels.length; i++) {
            cdLabels[i] = RecoverySettings.cooldownLabel(RecoverySettings.COOLDOWNS[i]);
        }
        cdRow.setOnClickListener(v -> new AlertDialog.Builder(this,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("让号冷却")
                .setItems(cdLabels, (d2, w2) -> {
                    cd[0] = RecoverySettings.COOLDOWNS[w2];
                    renderCd[0].run();
                })
                .setNegativeButton("取消", null)
                .show());
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(8);
        box.addView(cdRow, crlp);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("恢复设置 · " + a.label)
                .setView(sc)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> {
            final TextView pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                Integer delay = parseEtInt(delayEt);
                Integer attempts = parseEtInt(attemptsEt);
                Integer retry = parseEtInt(retryEt);
                if (delay == null || attempts == null || retry == null) {
                    toast("请输入有效的数字");
                    return;
                }
                String err = RecoverySettings.validate(delay, retry, attempts, cd[0]);
                if (err != null) {
                    toast(err);   // 文案逐字对齐服务端 3002（RecoverySettings 常量）
                    return;
                }
                pos.setEnabled(false);
                pos.setText("保存中…");
                VaultSync.updateRecoverySettings(this, a.fpid, new RecoverySettings(
                                enSw.isOn(), delay, retry, attempts, cd[0]),
                        (code, message, data) -> runOnUiThread(() -> {
                            pos.setEnabled(true);
                            pos.setText("保存");
                            boolean ok = code == 0 && (data == null
                                    || data.optBoolean("accepted", true));
                            if (ok) {
                                dialog.dismiss();
                                toast(RecoverySettings.MSG_SAVED);
                                pollPoolOnce();
                                refreshAccounts();
                            } else {
                                toast("保存失败：" + (message == null || message.isEmpty()
                                        ? "服务器未受理" : message));
                            }
                        }));
            });
        });
        dialog.show();
    }

    /** 恢复设置对话框的数字输入行（label + 44dp 标准输入框；返回 EditText 供读取）。 */
    private EditText recoveryNumField(LinearLayout parent, String label, String hint,
                                      String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12.5f);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setTextColor(UiStyle.INK);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(12);
        parent.addView(l, llp);
        EditText et = UiStyle.searchInput(this, hint);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        parent.addView(et, elp);
        return et;
    }

    /** 下拉行文案（label 灰 + 值 brand 粗，同 {@link UiStyle#dropdownRow} 的样式口径）。 */
    private void renderDropdownText(TextView v, String label, String value) {
        android.text.SpannableString ss = new android.text.SpannableString(label + "  " + value + " ▾");
        ss.setSpan(new android.text.style.ForegroundColorSpan(UiStyle.INK2), 0, label.length(), 0);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), label.length() + 2, ss.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(UiStyle.BRAND_TEXT),
                label.length() + 2, ss.length(), 0);
        v.setText(ss);
    }

    /** 输入框整数安全读取（空/非数字 → null）。 */
    private static Integer parseEtInt(EditText et) {
        try {
            String s = et.getText() == null ? "" : et.getText().toString().trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 池角色匹配（后台/渲染线程均可）：按 (sid, uid) 找服务端角色对象。
     * 实现已下沉 {@link AccountPoolCache#findRole}（P1：角色管理弹层长按菜单共用）。
     */
    private static org.json.JSONObject matchPoolRole(org.json.JSONObject poolAcc,
                                                     RoleStore.Role r) {
        return r == null ? null
                : com.carlos.vault.AccountPoolCache.findRole(poolAcc, r.serverid, r.uid);
    }

    /** 动作的中文名（toast 用）。 */
    private static String actionLabel(String action) {
        if (VaultSync.ACTION_LOGIN.equals(action)) return "上线";
        if (VaultSync.ACTION_PAUSE.equals(action)) return "暂停";
        if (VaultSync.ACTION_RESUME.equals(action)) return "恢复";
        if (VaultSync.ACTION_STOP.equals(action)) return "停止";
        if (VaultSync.ACTION_LOGOUT.equals(action)) return "下线";
        if (VaultSync.ACTION_LOGIN_ALL.equals(action)) return "全部上线";
        if (VaultSync.ACTION_STOP_ALL.equals(action)) return "全部停止";
        return action;
    }

    /**
     * 单角色 role-action：进行中标记（渲染为「执行中」禁点）→ 请求 → 成功 toast +
     * 立即刷新（pollPoolOnce ≤2s 语义），失败 toast 服务端 message。
     * M2d 语义变更：同账号多角色并行在线，login 不再互斥停他人；旧服务端若仍在
     * data.stoppedRoles 返回被停角色，仅记日志不提示（「已下线 XX」toast 已移除）。
     */
    private void poolRoleAction(final AccountStore.Account a, final RoleStore.Role r,
                                final String action) {
        final String key = a.fpid + ":" + r.serverid + ":" + r.uid;
        if (!poolBusy.add(key)) return;
        refreshAccounts();   // 立即渲染「执行中」禁点
        VaultSync.roleAction(this, a.fpid, String.valueOf(r.serverid),
                String.valueOf(r.uid), action, (code, message, data) -> runOnUiThread(() -> {
                    poolBusy.remove(key);
                    boolean ok = code == 0 && data != null
                            && data.optBoolean("accepted", false);
                    String label = actionLabel(action);
                    if (ok) {
                        if (VaultSync.ACTION_LOGIN.equals(action) && data != null) {
                            org.json.JSONArray stopped = data.optJSONArray("stoppedRoles");
                            if (stopped != null && stopped.length() > 0) {
                                // 旧服务端互斥残留：M2d 已移除「已下线」提示，仅日志忽略
                                android.util.Log.d("HomeActivity",
                                        "login returned stoppedRoles (legacy mutual-exclusion, ignored): "
                                                + stopped);
                            }
                        }
                        String msg = data == null ? "" : data.optString("message", "");
                        // P1 §三.2：暂停成功 toast 固定文案（服务端 message「将在 N 分钟后自动上线」
                        // 含具体分钟数，刷新后由「已暂停·待恢复」倒计时承接，不再双报）
                        if (VaultSync.ACTION_PAUSE.equals(action)) {
                            toast(RoleActionRules.PAUSE_OK_TOAST);
                        } else {
                            toast(label + "成功" + (msg.isEmpty() ? "" : " · " + msg));
                        }
                    } else {
                        toast(label + "失败："
                                + (message == null || message.isEmpty() ? "服务器未受理" : message));
                    }
                    pollPoolOnce();   // 操作后立即刷新（契约 §3.2：≤2s）
                    refreshAccounts();
                }));
    }

    /** 账号级批量（组头「全部上线/全部停止」）：语义同 {@link #poolRoleAction}。 */
    private void poolAccountAction(final AccountStore.Account a, final String action) {
        if (a == null || a.fpid == null || a.fpid.isEmpty()) return;
        if (!poolAccBusy.add(a.fpid)) return;
        refreshAccounts();
        VaultSync.accountAction(this, a.fpid, action, (code, message, data) ->
                runOnUiThread(() -> {
                    poolAccBusy.remove(a.fpid);
                    boolean ok = code == 0 && data != null
                            && data.optBoolean("accepted", false);
                    String label = actionLabel(action);
                    if (ok) {
                        toast(label + "完成（" + a.label + "）");
                    } else {
                        toast(label + "失败："
                                + (message == null || message.isEmpty() ? "服务器未受理" : message));
                    }
                    pollPoolOnce();
                    refreshAccounts();
                }));
    }

    /**
     * 全局批量（批量条「全部上线/全部停止」）：契约 §3.1 P0 方案——本地逐账号串行调
     * account-action，进度 x/y 为真实逐账号结果（全局接口是原子批量无进度；globalAction
     * 保留在 VaultSync 供后续直接对接）。执行期间批量条按钮禁点、进度文案「上线中/停止中 x/y」。
     */
    private void runGlobalBatch(final String action) {
        if (poolBarBusy) return;
        if (accounts == null || accounts.isEmpty()) {
            toast("没有账号");
            return;
        }
        final List<AccountStore.Account> list = new ArrayList<>();
        for (AccountStore.Account a : accounts) {
            if (a != null && a.fpid != null && !a.fpid.isEmpty()) list.add(a);
        }
        if (list.isEmpty()) {
            toast("没有账号");
            return;
        }
        poolBarBusy = true;
        setPoolBarEnabled(false);
        final String doing = VaultSync.ACTION_LOGIN_ALL.equals(action) ? "上线中 " : "停止中 ";
        new Thread(() -> {
            int done = 0, okCnt = 0;
            for (AccountStore.Account a : list) {
                final int d = done;
                runOnUiThread(() -> {
                    if (poolBarProgress != null)
                        poolBarProgress.setText(doing + d + "/" + list.size());
                });
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                final boolean[] okBox = {false};
                VaultSync.accountAction(HomeActivity.this, a.fpid, action,
                        (code, message, data) -> {
                            okBox[0] = code == 0 && data != null
                                    && data.optBoolean("accepted", false);
                            latch.countDown();
                        });
                try {
                    latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (okBox[0]) okCnt++;
                done++;
            }
            final int fOk = okCnt, fDone = done;
            runOnUiThread(() -> {
                poolBarBusy = false;
                setPoolBarEnabled(true);
                if (poolBarProgress != null)
                    poolBarProgress.setText("完成 " + fOk + "/" + fDone);
                toast(actionLabel(action) + "完成 " + fOk + "/" + fDone);
                pollPoolOnce();
                refreshAccounts();
            });
        }, "pool-global-batch").start();
    }

    /** 批量条执行期间禁用 [全部上线][全部停止][刷新]。 */
    private void setPoolBarEnabled(boolean enabled) {
        TextView[] btns = {poolBarLoginAll, poolBarStopAll, poolBarRefresh};
        for (TextView b : btns) {
            if (b == null) continue;
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1f : .5f);
        }
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
            // 复合身份写激活角色：同服多角色只写 sid 会解析回第一行，钉扎到另一个角色
            RoleStore.setActiveRole(this, a.fpid, r.serverid, r.uid);
            // 预登录启动同样改变了当前角色：标记待上传 + 触发去抖同步（防回滚，P2）
            VaultSync.notifyRoleSwitched(this, a.fpid);
        } catch (Throwable ignored) {
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在切换到 K" + r.serverid
                + (r.uid > 0 ? " uid=" + r.uid : "") + " «" + r.name + "»（预登录对齐角色…）");
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
                            acc.fpid, acc.token, role.serverid, role.uid);
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
                // 启动阶段提示角色名（含区服/uid），不再显示账号备注名
                String roleName = (role.name == null || role.name.isEmpty() ? acc.label : role.name)
                        + "·K" + role.serverid + (role.uid > 0 ? "·" + role.uid : "");
                doSwitch(acc, roleName);
            });
        }, "prelaunch-role").start();
    }

    /**
     * ⚙ 角色管理底部弹层（实现已抽到 {@link RoleManagerSheet}，账号页/账号详情页共用）：
     * 列出该账号全部角色 —— 启用与未启用都在。
     *
     * <p>打开开关 = 加入工作区并启用；关闭 = 移出。改动后账号卡的角色区会随
     * refreshAccounts 同步。为什么用弹层而不是跳页：角色是账号的属性，管理完就关掉，
     * 不该让用户离开账号上下文。
     */
    private void showRoleManager(final AccountStore.Account a, final List<RoleStore.Role> roles,
                                 boolean accOn) {
        RoleManagerSheet.show(this, a, roles, this::refreshAccounts);
    }

    /** 账号页加载遮罩：半透明覆盖 + 居中"加载中…"卡。 */
    private void showAccMask() {
        if (accMask == null) {
            android.widget.FrameLayout fl = new android.widget.FrameLayout(this);
            fl.setBackgroundColor(UiStyle.BLACK_40);
            fl.setClickable(true);
            fl.setElevation(dp(24));
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
            ((ViewGroup) content.getParent()).addView(accMask,
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
        taskScroll = new ScrollView(this);
        ScrollView sc = taskScroll;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(110));
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
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        tb.addView(title);
        taskCap = new TextView(this);
        taskCap.setTextSize(12);
        taskCap.setTextColor(UiStyle.INK2);
        taskCap.setPadding(0, dp(3), 0, 0);
        tb.addView(taskCap);
        top.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView switchRole = new TextView(this);
        switchRole.setText("切换角色");
        switchRole.setTextSize(13);
        switchRole.setTypeface(Typeface.DEFAULT_BOLD);
        switchRole.setTextColor(UiStyle.BRAND_TEXT);
        switchRole.setGravity(Gravity.CENTER);
        switchRole.setPadding(dp(14), dp(8), dp(14), dp(8));
        switchRole.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 16, UiStyle.LINE));
        UiStyle.press(switchRole);
        switchRole.setOnClickListener(v -> showRolePicker());
        top.addView(switchRole);
        box.addView(top);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);
        top.setLayoutParams(tlp);

        // 功能搜索（S7 日常中心）：名称/id 实时过滤，跨分类；有搜索词时结果列表替换分类列表
        LinearLayout search = UiStyle.outlineCard(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        search.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        search.addView(new UiStyle.Icon(this, UiStyle.Icon.SEARCH, UiStyle.INK3, 19),
                new LinearLayout.LayoutParams(dp(19), dp(19)));
        taskSearch = new EditText(this);
        taskSearch.setHint("搜索功能，如：邮件 / 护盾 / 采集");
        taskSearch.setTextSize(13);
        taskSearch.setTextColor(UiStyle.INK);
        taskSearch.setHintTextColor(UiStyle.INK3);
        taskSearch.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        taskSearch.setSingleLine(true);
        taskSearch.setPadding(dp(10), 0, dp(4), 0);
        taskSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim();
                if (q.equals(taskQuery)) return;
                taskQuery = q;
                refreshTasks();
            }
        });
        search.addView(taskSearch, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchClear = new TextView(this);
        searchClear.setText("✕");
        searchClear.setTextSize(12);
        searchClear.setTextColor(UiStyle.INK3);
        searchClear.setGravity(Gravity.CENTER);
        searchClear.setPadding(dp(8), dp(2), dp(6), dp(2));
        searchClear.setVisibility(View.GONE);
        UiStyle.press(searchClear);
        searchClear.setOnClickListener(v -> taskSearch.setText(""));
        search.addView(searchClear);
        box.addView(search);

        searchCap = new TextView(this);
        searchCap.setTextSize(11);
        searchCap.setTextColor(UiStyle.INK2);
        searchCap.setPadding(dp(4), dp(6), dp(4), dp(2));
        searchCap.setVisibility(View.GONE);
        box.addView(searchCap);

        // 自动日常横幅卡（规范：图标 + 标题/副题 + 管理工作区小按钮）
        LinearLayout auto = UiStyle.outlineCard(this);
        auto.setOrientation(LinearLayout.HORIZONTAL);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        auto.setPadding(dp(14), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.topMargin = dp(10);
        auto.setLayoutParams(autoLp);
        auto.addView(UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout aMid = new LinearLayout(this);
        aMid.setOrientation(LinearLayout.VERTICAL);
        aMid.setPadding(dp(12), 0, dp(6), 0);
        bannerTitle = new TextView(this);
        bannerTitle.setText("自动化任务");
        bannerTitle.setTextSize(15);
        bannerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTitle.setTextColor(UiStyle.INK);
        aMid.addView(bannerTitle);
        bannerSub = new TextView(this);
        bannerSub.setTextSize(11.5f);
        bannerSub.setTextColor(UiStyle.INK2);
        bannerSub.setPadding(0, dp(3), 0, 0);
        bannerSub.setMaxLines(1);
        bannerSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        // 统一自动化开关（服务器执行；原“自动日常 + 常驻在线”合并）
        autoSwitch = new UiStyle.Switch(this, false, () -> {
            AccountStore.Account cur = taskAccount == null ? firstWorkspace() : taskAccount;
            if (cur == null) {
                toast("没有可用账号");
                autoSwitch.setOn(false);
                return;
            }
            boolean on = autoSwitch.isOn();
            RoleStore.saveAutomation(this, cur.fpid, on);
            VaultSync.ensureServerMode(this);
            VaultSync.onAccountsChanged(this);
            com.carlos.vault.daily.RoleSessionKeeper.get(this).start();
            com.carlos.vault.daily.RoleSessionKeeper.get(this).syncOnline();
            toast(on ? "自动化任务已开启 · 服务器执行" : "自动化任务已关闭");
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

        // 24h 常驻在线开关已移到今日页双卡（设计稿 S2），日常页保持「搜索 → 自动日常 → 分类」节奏。

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
                if (taskSearch != null) taskSearch.setText("");   // 切分类即退出搜索态
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
        if (helpActionBtn != null) helpActionBtn.setEnabled(false);
        final AccountStore.Account facc = cur;
        // 统一自动化：互助也提交服务器执行（服务器常驻会话已有联盟互助推送响应）。
        VaultSync.ensureServerMode(this);
        VaultSync.triggerAutomation(this, facc.fpid, "alliance_help", (code, message, data) -> {
            boolean ok = code == 0 && data != null && data.optBoolean("accepted", false);
            runOnUiThread(() -> {
                if (helpActionBtn != null) helpActionBtn.setEnabled(true);
                toast(ok ? "已提交服务器：一键帮助全盟" : "服务器未受理：" + message);
                refreshToday();
            });
        });
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
     * 自动化执行位置（固定服务器）：2026-10 起客户端不再本地跑任务，
     * 本页只提供状态说明与“立即同步”，不提供关闭服务器模式的入口。
     */
    private void showServerModePicker() {
        VaultSync.ensureServerMode(this);
        new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("自动化执行位置")
                .setMessage("统一自动化任务固定由 vault-server 执行。\n\n"
                        + "本机只负责：账号/token/配置上传、服务器状态展示、分身游玩。\n\n"
                        + "如需立即对齐配置，点“立即同步”。")
                .setPositiveButton("立即同步", (d, w) -> {
                    VaultSync.syncAll(this);
                    VaultSync.refreshStatus(this);
                    toast("已触发同步（见运行日志/状态行）");
                })
                .setNegativeButton("关闭", null)
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
                // 文案映射统一走 RoleDisplay.serverStateLabel（账号页角色行/角色管理行同源）
                return head + RoleDisplay.serverStateLabel(st) + " · 服务器";
            }
            StringBuilder sb = new StringBuilder();
            for (com.carlos.vault.daily.RoleSessionKeeper.LiveSession ls
                    : com.carlos.vault.daily.RoleSessionKeeper.get(this).sessionsSnapshot()) {
                if (!cur.fpid.equals(ls.fpid)) continue;
                // 状态字面量与 WjdrEngine.STATE_* 常量一致（online/recovering/takeover/need_token）；
                // 文案同样走 RoleDisplay.serverStateLabel（本地口径去掉「· 服务器」后缀）
                if (sb.length() > 0) sb.append("  ");
                sb.append(ls.sid).append("区·")
                        .append(RoleDisplay.serverStateLabel(ls.engine.sessionState()));
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
        long sid = configSid(taskAccount);
        LogStore ls = new LogStore(this);
        com.carlos.vault.daily.DailyConfigStore cfgStore = new com.carlos.vault.daily.DailyConfigStore(this);
        Map<String, Boolean> cfg = cfgStore.roleConfig(taskAccount.fpid, sid);

        // 顶栏 caption：N 项功能 · 已启用 M；横幅：工作区角色数 + 自动日常开关态
        int total = FnRegistry.all().size(), enabled = 0;
        for (FnRegistry.Fn fn : FnRegistry.all()) {
            Boolean on = cfg == null ? null : cfg.get(fn.id);
            if (on == null ? (fn.implemented && defOn(fn.id)) : on) enabled++;
        }
        String roleName = "";
        try {
            for (RoleStore.Role r : RoleStore.roles(this, taskAccount.fpid)) {
                if (r.serverid == sid && r.name != null && !r.name.isEmpty()) {
                    roleName = r.name;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (roleName.isEmpty()) roleName = taskAccount.label;
        if (taskCap != null) {
            taskCap.setText(total + " 项功能 · 已启用 " + enabled + " · 当前 " + roleName);
        }
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
        if (bannerTitle != null) {
            bannerTitle.setText("自动化任务");
            int[] win = RoleStore.window(this);
            String winText = (win[0] == 0 && win[1] >= 24) ? "全天"
                    : String.format(java.util.Locale.CHINA, "%02d:00-%02d:00", win[0], win[1]);
            bannerSub.setText("服务器执行 · " + winText + " · 已启用 " + enabled + " 项");
            winBtn.setText("时段");
            if (autoSwitch != null) {
                autoSwitch.setOn(RoleStore.automationEnabled(this, taskAccount.fpid));
            }
        }

        // 列表来源：搜索态=跨分类过滤结果；否则=当前分类；常用区仅在浏览态置顶渲染
        List<FnRegistry.Fn> list;
        if (taskQuery.isEmpty()) {
            list = FnRegistry.byCategory(taskCat);
            if (searchCap != null) searchCap.setVisibility(View.GONE);
            if (searchClear != null) searchClear.setVisibility(View.GONE);
            if (!favFns.isEmpty()) {
                taskRows.addView(favSectionHeader());
                int fi = 0;
                for (String fid : new ArrayList<>(favFns)) {
                    FnRegistry.Fn f = FnRegistry.byId(fid);
                    if (f == null) continue;   // 已下线功能：静默跳过
                    taskRows.addView(favRow(f, ls, sid));
                    fi++;
                    if (fi == 4) taskRows.addView(toolsFavRow());
                }
                if (fi < 4) taskRows.addView(toolsFavRow());
            }
        } else {
            String q = taskQuery.toLowerCase();
            list = new ArrayList<>();
            for (FnRegistry.Fn f : FnRegistry.all()) {
                if (f.name.toLowerCase().contains(q) || f.id.toLowerCase().contains(q)) list.add(f);
            }
            if (searchCap != null) {
                searchCap.setVisibility(View.VISIBLE);
                searchCap.setText("「" + taskQuery + "」匹配 " + list.size() + " 项"
                        + (list.isEmpty() ? " · 换个关键词试试" : ""));
            }
            if (searchClear != null) searchClear.setVisibility(View.VISIBLE);
            if (list.isEmpty()) {
                taskRows.addView(emptyTip("没有匹配「" + taskQuery + "」的功能"));
                return;
            }
        }
        for (final FnRegistry.Fn fn : list) {
            taskRows.addView(taskRow(fn, sid));
        }
    }

    /** 分类列表行（设计稿 S7）：与常用区一致的图标/标题/开关 + 点行进详情。 */
    private View taskRow(final FnRegistry.Fn fn, long sid) {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        boolean enabled = fnOn(fn);
        card.addView(UiStyle.iconTile(this, featureIcon(fn),
                enabled ? UiStyle.BRAND_TEXT : UiStyle.INK3,
                enabled ? UiStyle.BRAND_50 : UiStyle.SURFACE_3, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(fn.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(enabled ? UiStyle.INK : UiStyle.INK2);
        mid.addView(name);
        TextView sub = new TextView(this);
        sub.setText(fnSub(fn));
        sub.setTextSize(11.5f);
        sub.setTextColor(UiStyle.INK2);
        sub.setPadding(0, dp(3), 0, 0);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sub);
        card.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (fn.implemented) {
            final UiStyle.Switch[] sw = new UiStyle.Switch[1];
            sw[0] = new UiStyle.Switch(this, enabled, () -> {
                setFnOn(fn, sw[0].isOn());
                refreshTasks();
            });
            card.addView(sw[0]);
        } else {
            card.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
        }
        UiStyle.press(card);
        card.setOnClickListener(v -> openFnDetail(taskAccount, fn.id));
        card.setOnLongClickListener(v -> {
            toggleFav(fn.id);
            toast(isFav(fn.id) ? "已加入常用" : "已移出常用");
            refreshTasks();
            return true;
        });
        return card;
    }

    // ================= 常用功能（S7 日常中心：高频置顶 + 持久化） =================

    /** 读取常用集；首次用高频默认集播种（覆盖奖励/联盟/野外/城镇四类每天都碰的功能）。 */
    private void loadFavs() {
        favFns.clear();
        android.content.SharedPreferences sp = getSharedPreferences("daily_fav", MODE_PRIVATE);
        // 逗号串保存以保序；旧版 StringSet 无序，读取时做一次迁移。
        String raw = sp.getString("fav_list", null);
        if (raw != null) {
            for (String id : raw.split(",")) {
                if (!id.isEmpty()) favFns.add(id);
            }
            return;
        }
        java.util.Set<String> def = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                "login_rewards", "mail", "supply", "daily_mission",
                "gather", "town_affairs", "alliance_help", "alliance_chest"));
        java.util.Set<String> saved = sp.getStringSet("favs", def);
        if (saved != null) favFns.addAll(saved);
    }

    private void saveFavs() {
        StringBuilder sb = new StringBuilder();
        for (String id : favFns) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        getSharedPreferences("daily_fav", MODE_PRIVATE).edit()
                .putString("fav_list", sb.toString()).apply();
    }

    private boolean isFav(String fnId) {
        return favFns.contains(fnId);
    }

    private void toggleFav(String fnId) {
        if (!favFns.remove(fnId)) favFns.add(fnId);
        saveFavs();
    }

    /** 常用区标题行：左标题 + 右提示。 */
    private View favSectionHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(14), dp(2), dp(6));
        TextView t = new TextView(this);
        t.setText("常用");
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        row.addView(t);
        TextView h = new TextView(this);
        h.setText("长按可移出");
        h.setTextSize(11);
        h.setTextColor(UiStyle.INK3);
        h.setPadding(dp(6), dp(2), 0, 0);
        row.addView(h);
        return row;
    }

    /** 常用区行卡（设计稿 S7）：图标 + 名称/摘要 + 开关（未开放为箭头）。长按移出常用。 */
    private View favRow(FnRegistry.Fn fn, LogStore ls, long sid) {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        card.addView(UiStyle.iconTile(this, featureIcon(fn), UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(fn.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        mid.addView(name);
        TextView sub = new TextView(this);
        sub.setText(fnSub(fn));
        sub.setTextSize(11.5f);
        sub.setTextColor(UiStyle.INK2);
        sub.setPadding(0, dp(3), 0, 0);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sub);
        card.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (fn.implemented) {
            final UiStyle.Switch[] sw = new UiStyle.Switch[1];
            sw[0] = new UiStyle.Switch(this, fnOn(fn), () -> {
                setFnOn(fn, sw[0].isOn());
                refreshTasks();
            });
            card.addView(sw[0]);
        } else {
            card.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
        }

        UiStyle.press(card);
        card.setOnClickListener(v -> openFnDetail(taskAccount, fn.id));
        card.setOnLongClickListener(v -> {
            toggleFav(fn.id);
            toast("已移出常用");
            refreshTasks();
            return true;
        });
        return card;
    }

    /** 常用区里的「进阶工具」行（设计稿 S7）。 */
    private View toolsFavRow() {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.addView(UiStyle.iconTile(this, UiStyle.Icon.GRID, UiStyle.INK2,
                UiStyle.SURFACE_3, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText("进阶工具");
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        mid.addView(name);
        TextView sub = new TextView(this);
        sub.setText("排行榜 · 迁城 · 三消等 14 项");
        sub.setTextSize(11.5f);
        sub.setTextColor(UiStyle.INK2);
        sub.setPadding(0, dp(3), 0, 0);
        mid.addView(sub);
        card.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                new LinearLayout.LayoutParams(dp(16), dp(16)));
        UiStyle.press(card);
        card.setOnClickListener(v -> openToolBox());
        return card;
    }

    private int featureIcon(FnRegistry.Fn fn) {
        switch (fn.id) {
            case "login_rewards": return UiStyle.Icon.GIFT;
            case "mail": return UiStyle.Icon.MAIL;
            case "supply": return UiStyle.Icon.BOX;
            case "daily_mission": return UiStyle.Icon.CHECKLIST;
            case "activity_rewards": case "commander_gift": return UiStyle.Icon.GIFT;
            case "alliance_help": return UiStyle.Icon.USERS;
            case "research": return UiStyle.Icon.DOC;
            case "building": return UiStyle.Icon.GRID;
            case "bear": return UiStyle.Icon.TARGET;
            case "gather": case "adventure": return UiStyle.Icon.COMPASS;
            default:
                switch (fn.cat) {
                    case FnRegistry.CAT_REWARD: return UiStyle.Icon.GIFT;
                    case FnRegistry.CAT_TOWN: return UiStyle.Icon.GRID;
                    case FnRegistry.CAT_ACTIVITY: return UiStyle.Icon.TARGET;
                    case FnRegistry.CAT_FIELD: return UiStyle.Icon.COMPASS;
                    case FnRegistry.CAT_ALLIANCE: return UiStyle.Icon.USERS;
                    case FnRegistry.CAT_SHOP: return UiStyle.Icon.BOX;
                    default: return UiStyle.Icon.DOC;
                }
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

    /** 常用/分类行副题：使用功能说明（设计稿 S7 的一行摘要），去掉括号补充并截断。 */
    private String fnSub(FnRegistry.Fn fn) {
        String d = fn.desc == null ? "" : fn.desc;
        int cut = d.indexOf('（');
        if (cut < 0) cut = d.indexOf('(');
        if (cut > 0) d = d.substring(0, cut);
        return d.length() > 22 ? d.substring(0, 22) + "…" : d;
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

    /**
     * 工具箱独立页（设计稿 S9）：协议监听 / 敌情监控 / P1 探针收在一页，
     * 替代旧版「文字列表对话框」。实现已整体迁入 {@link ToolBoxActivity}，此处只做跳转。
     */
    private void openToolBox() {
        try {
            startActivity(new android.content.Intent(this, ToolBoxActivity.class));
        } catch (Throwable t) {
            toast("工具箱打开失败");
        }
    }

    // ================= Tab4 · 我的 =================

    private void buildMinePage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(110));
        sc.addView(box);
        pages[3].addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：我的 + 通知铃
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("我的");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout bellWrap = new FrameLayout(this);
        GradientDrawable bellBg = new GradientDrawable();
        bellBg.setColor(UiStyle.WHITE);
        bellBg.setCornerRadius(dp(22));
        bellWrap.setBackground(bellBg);
        bellWrap.setElevation(dp(2));
        bellWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.BELL, UiStyle.INK, 20),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        UiStyle.press(bellWrap);
        bellWrap.setOnClickListener(v -> showMainMenu());
        top.addView(bellWrap, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(top);

        // 授权卡（品牌蓝 + 大数字 + 管理授权）
        LinearLayout lic = new LinearLayout(this);
        lic.setOrientation(LinearLayout.VERTICAL);
        lic.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable lg = new GradientDrawable();
        lg.setColor(UiStyle.BRAND);
        lg.setCornerRadius(dp(20));
        lic.setBackground(lg);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(14);
        box.addView(lic, llp);

        LinearLayout licTop = new LinearLayout(this);
        licTop.setOrientation(LinearLayout.HORIZONTAL);
        licTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView plan = new TextView(this);
        plan.setText("专业版授权");
        plan.setTextSize(16);
        plan.setTypeface(Typeface.DEFAULT_BOLD);
        plan.setTextColor(UiStyle.WHITE);
        licTop.addView(plan, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE_20, 999));
        statusPill.setPadding(dp(9), dp(4), dp(9), dp(4));
        View dot = new View(this);
        dot.setBackground(UiStyle.roundRect(UiStyle.WHITE, dp(3)));
        statusPill.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        licenseStatus = new TextView(this);
        licenseStatus.setText("生效中");
        licenseStatus.setTextSize(11);
        licenseStatus.setTextColor(UiStyle.WHITE);
        licenseStatus.setPadding(dp(5), 0, 0, 0);
        statusPill.addView(licenseStatus);
        licTop.addView(statusPill);
        lic.addView(licTop);

        LinearLayout licMid = new LinearLayout(this);
        licMid.setOrientation(LinearLayout.HORIZONTAL);
        licMid.setGravity(Gravity.BOTTOM);
        licMid.setPadding(0, dp(14), 0, 0);
        licenseDays = new TextView(this);
        licenseDays.setTextSize(40);
        licenseDays.setTypeface(Typeface.DEFAULT_BOLD);
        licenseDays.setTextColor(UiStyle.WHITE);
        licMid.addView(licenseDays);
        TextView lu = new TextView(this);
        lu.setText("天");
        lu.setTextSize(16);
        lu.setTextColor(UiStyle.BRAND_100);
        lu.setPadding(dp(5), 0, 0, dp(6));
        licMid.addView(lu);
        LinearLayout licInfo = new LinearLayout(this);
        licInfo.setOrientation(LinearLayout.VERTICAL);
        licInfo.setPadding(dp(14), 0, 0, dp(4));
        TextView info1 = new TextView(this);
        info1.setText("剩余有效期");
        info1.setTextSize(12);
        info1.setTextColor(UiStyle.WHITE);
        licInfo.addView(info1);
        licenseMeta = new TextView(this);
        licenseMeta.setTextSize(11);
        licenseMeta.setTextColor(UiStyle.BRAND_100);
        licenseMeta.setPadding(0, dp(3), 0, 0);
        licInfo.addView(licenseMeta);
        licMid.addView(licInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        lic.addView(licMid);

        LinearLayout licBottom = new LinearLayout(this);
        licBottom.setOrientation(LinearLayout.HORIZONTAL);
        licBottom.setPadding(0, dp(10), 0, 0);
        licenseExpire = new TextView(this);
        licenseExpire.setTextSize(12);
        licenseExpire.setTextColor(UiStyle.BRAND_100);
        licBottom.addView(licenseExpire, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        licenseBind = new TextView(this);
        licenseBind.setTextSize(12);
        licenseBind.setTextColor(UiStyle.BRAND_100);
        licBottom.addView(licenseBind);
        lic.addView(licBottom);

        LinearLayout manage = new LinearLayout(this);
        manage.setOrientation(LinearLayout.HORIZONTAL);
        manage.setGravity(Gravity.CENTER);
        manage.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE, 16));
        manage.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(14);
        manage.setLayoutParams(mlp);
        manage.addView(new UiStyle.Icon(this, UiStyle.Icon.SHIELD, UiStyle.BRAND_TEXT, 18),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView manageTx = new TextView(this);
        manageTx.setText("管理授权");
        manageTx.setTextSize(15);
        manageTx.setTypeface(Typeface.DEFAULT_BOLD);
        manageTx.setTextColor(UiStyle.BRAND_TEXT);
        manageTx.setPadding(dp(8), 0, 0, 0);
        manage.addView(manageTx);
        UiStyle.press(manage);
        manage.setOnClickListener(v -> showLicenseMenu());
        lic.addView(manage);

        box.addView(UiStyle.sectionTitle(this, "运行"));
        box.addView(mineRowIcon(UiStyle.Icon.PLAY, "执行进度",
                "当前/最近一轮的账号与逐号结果", this::showRunPanel));
        box.addView(mineRowIcon(UiStyle.Icon.DOC, "执行日志", "保留最近 200 条",
                this::showRunLog));
        box.addView(mineRowIcon(UiStyle.Icon.DOC, "崩溃日志",
                "最近崩溃现场 · 可复制", this::showCrashLog));

        box.addView(UiStyle.sectionTitle(this, "数据"));
        box.addView(mineRowIcon(UiStyle.Icon.CLOUD, "账号快照", "每号一份 · 切号不丢",
                this::openOld));
        box.addView(mineRowIcon(UiStyle.Icon.UPLOAD, "批量导入导出", "token · 分组",
                this::showBatchImport));
        box.addView(mineRowIcon(UiStyle.Icon.TRASH, "清理缓存", "可释放调试缓存",
                () -> toast("缓存清理将在下一版提供")));

        box.addView(UiStyle.sectionTitle(this, "容器与初始化"));
        box.addView(mineRowIcon(UiStyle.Icon.COMPASS, "初始化向导",
                "克隆 → 首启 → 重置 → 存基线", this::showWizard));
        box.addView(mineRowIcon(UiStyle.Icon.BOX, "克隆软件", "把无尽冬日装进号库",
                () -> onWizardStep(0)));
        box.addView(mineRowIcon(UiStyle.Icon.CLOUD, "微信支付", "容器微信登录 / 充值",
                this::doWechatPay));
        box.addView(mineRowIcon(UiStyle.Icon.SHIELD, "顶号策略",
                "协议会话被游戏客户端顶下线时怎么办", this::showTakeoverPolicyPicker));
        box.addView(mineRowIcon(UiStyle.Icon.CLOUD, "服务器模式",
                (VaultSync.isServerMode(this) ? "已开启 · " : "未开启 · ")
                        + "自动化跑在服务器", this::showServerModePicker));
        box.addView(buildHotUpdateRow());
        box.addView(mineRowIcon(UiStyle.Icon.CLOCK, "定时与保活",
                "前台服务 · 精确闹钟 · 通知唤醒", () -> {
                    AccountStore.Account cur = firstWorkspace();
                    if (cur != null) openDaily(cur, null);
                    toast("在弹出页顶部设置自动日常");
                }));

        box.addView(UiStyle.sectionTitle(this, "账号管理"));
        box.addView(mineRowIcon(UiStyle.Icon.PLUS, "新增账号", "手动添加一个账号",
                this::showCreateEntry));
        box.addView(mineRowIcon(UiStyle.Icon.GRID, "经典视图",
                "旧版账号卡片墙（排序/分组）", this::openOld));

        box.addView(UiStyle.sectionTitle(this, "关于"));
        box.addView(mineRowIcon(UiStyle.Icon.USER, "关于无尽号库",
                "版本 " + appVersion() + " · 仅支持 Android（容器版）",
                () -> toast("无尽号库 · " + appVersion() + "\n容器引擎 VirtualApp · 数据保存在本机")));

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

    /** 我的页行（设计稿 S11）：图标盒 + 标题/副题 + 右箭头。 */
    private View mineRowIcon(int iconType, String title, String sub, Runnable onClick) {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);
        row.addView(UiStyle.iconTile(this, iconType, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        mid.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(UiStyle.INK2);
            s.setPadding(0, dp(3), 0, 0);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(s);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                new LinearLayout.LayoutParams(dp(16), dp(16)));
        UiStyle.press(row);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** 热更新检查行：显示本地热更版本，点击对比服务器版本，不一致自动拉取应用。 */
    private TextView hotSub;

    private View buildHotUpdateRow() {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);
        row.addView(UiStyle.iconTile(this, UiStyle.Icon.CLOUD, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView t = new TextView(this);
        t.setText("检查更新");
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        mid.addView(t);
        hotSub = new TextView(this);
        hotSub.setText(hotSubText());
        hotSub.setTextSize(11.5f);
        hotSub.setTextColor(UiStyle.INK2);
        hotSub.setPadding(0, dp(3), 0, 0);
        mid.addView(hotSub);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                new LinearLayout.LayoutParams(dp(16), dp(16)));
        UiStyle.press(row);
        row.setOnClickListener(v -> runHotUpdateCheck());
        return row;
    }

    private String hotSubText() {
        long v = com.carlos.vault.daily.HotConfig.version();
        String url = getSharedPreferences("hotconfig", MODE_PRIVATE).getString("hot_url", "");
        boolean hasUrl = url != null && !url.isEmpty();
        String local = v > 0 ? ("热更 v" + v) : "无热更包";
        return "本机 v" + appVersionName() + " · " + local
                + (hasUrl ? " · 点击检查更新" : " · 未配置更新源");
    }

    /** 本机 versionName（读取失败兜底「—」）。 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "—";
        }
    }

    /** 本机 versionCode（读取失败返回 -1，此时跳过整包更新判断）。 */
    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 版本串（关于区用）：versionName (versionCode)，读取失败兜底「—」。 */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
            return "—";
        }
    }

    private void runHotUpdateCheck() {
        final android.app.ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("检查更新…");
        pd.setCancelable(false);
        pd.show();
        final int localCode = appVersionCode();
        new Thread(() -> {
            com.carlos.vault.daily.HotConfig.CheckResult r =
                    com.carlos.vault.daily.HotConfig.checkServer(this);
            runOnUiThread(() -> {
                pd.dismiss();
                refreshAllPages();   // 重建我的页，刷新「本机/热更」副题
                // 大版本优先：热更覆盖不了的整包更新，引导到浏览器下载。
                boolean apkNewer = localCode > 0 && r.apkVersionCode > localCode
                        && !r.apkUrl.isEmpty();
                if (apkNewer) {
                    showApkUpdateDialog(r, localCode);
                    return;
                }
                String msg;
                switch (r.code) {
                    case 0:
                        msg = "热更已更新：v" + r.localVersion + " → v" + r.serverVersion;
                        break;
                    case 1:
                        msg = r.serverVersion > 0 ? "已是最新 v" + r.serverVersion : "已是最新";
                        break;
                    case 2:
                        msg = "未配置更新源（prefs hot_url / adb 广播 --es url 均可设置）";
                        break;
                    default:
                        msg = "拉取失败，请检查网络后重试";
                }
                toast(msg);
            });
        }, "hot-check").start();
    }

    /** 大版本更新：热更覆盖不了，引导到浏览器下载整包（不做静默安装）。 */
    private void showApkUpdateDialog(com.carlos.vault.daily.HotConfig.CheckResult r,
                                     int localCode) {
        String name = r.apkVersionName.isEmpty()
                ? String.valueOf(r.apkVersionCode) : r.apkVersionName;
        StringBuilder msg = new StringBuilder();
        msg.append("本机版本：v").append(appVersionName())
                .append(" (").append(localCode).append(")\n");
        msg.append("最新版本：v").append(name)
                .append(" (").append(r.apkVersionCode).append(")\n\n");
        msg.append("本次为大版本更新，无法通过热更完成，请到浏览器下载最新安装包覆盖安装。");
        msg.append("\n请直接覆盖安装，不要先卸载（卸载会清空账号库与快照）。");
        if (!r.apkNotes.isEmpty()) msg.append("\n\n更新内容：\n").append(r.apkNotes);
        if (r.code == 0) msg.append("\n\n（热更配置已同步应用）");
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("浏览器下载", (d, w) -> openInBrowser(r.apkUrl))
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 用系统浏览器打开下载地址；没有可用浏览器时退化为复制链接。 */
    private void openInBrowser(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            toast("下载地址无效：" + u);
            return;
        }
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(u)));
        } catch (Throwable t) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("update_url", u));
                toast("没有可用浏览器，下载链接已复制");
            } catch (Throwable ignored) {
                toast("无法打开浏览器：" + u);
            }
        }
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
            if (licenseExpire != null) {
                licenseExpire.setText(t.expireAt <= 0 ? "永久有效" : "到期 "
                        + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                        .format(new java.util.Date(t.expireAt)));
            }
            if (licenseMeta != null) {
                // P2-2：明确展示绑定设备与掩码码，避免用户把"一码一设备"误解为 bug；
                // 双开/手机分身是独立实例，需另发一码（见 showLicenseMenu 说明）。
                licenseMeta.setText("本机 " + DeviceId.label()
                        + (maskCode(t.code).isEmpty() ? "" : " · " + maskCode(t.code))
                        + " · 一码一设备");
            }
            if (licenseBind != null) licenseBind.setText("已绑定本机");
            if (licenseStatus != null) licenseStatus.setText("生效中");
        } else {
            licenseDays.setText("--");
            if (licenseExpire != null) licenseExpire.setText("未激活");
            if (licenseMeta != null) {
                licenseMeta.setText("本机 " + DeviceId.label() + " · 一码一设备");
            }
            if (licenseBind != null) licenseBind.setText("未绑定设备");
            if (licenseStatus != null) licenseStatus.setText("未激活");
        }
    }

    /** 兑换码掩码：只露前 4 与后 4，中间固定星号（不用于校验，仅展示）。 */
    private static String maskCode(String code) {
        if (code == null) return "";
        String c = code.trim();
        if (c.length() < 10) return c;
        return c.substring(0, 4) + "-****-" + c.substring(c.length() - 4);
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

    /** P2-1：查看/复制/清空最近崩溃现场（CrashGuard 环形缓冲）。 */
    private void showCrashLog() {
        String log = CrashGuard.readLog(this);
        if (log == null || log.trim().isEmpty()) {
            toast("暂无崩溃记录");
            return;
        }
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        tv.setTextSize(10.5f);
        tv.setTextIsSelectable(true);
        tv.setText(log);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        sc.addView(tv);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("崩溃日志（最近 " + CrashGuard.MAX_ENTRIES + " 条）")
                .setView(sc)
                .setPositiveButton("复制", (d, w) -> {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log));
                        toast("已复制崩溃日志");
                    } catch (Throwable t) {
                        toast("复制失败");
                    }
                })
                .setNeutralButton("清空", (d, w) -> {
                    CrashGuard.clear(this);
                    toast("已清空崩溃日志");
                })
                .setNegativeButton("关闭", null)
                .show();
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
            bp.bottomMargin = dp(92);
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
                    VaultSync.onAccountsChanged(this);
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
        // 统一自动化：批量“跑日常”只提交服务器，不再本地执行。
        if (VaultSync.isServerMode(this)) {
            runListServer(ws);
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
        runStartedAt = System.currentTimeMillis();   // 执行页「已运行时长」计时（仅展示）
        runEndedAt = 0;
        showRunPanel(ws);
        DailyRunner.SOURCE = "手动";
        new Thread(() -> {
            for (AccountStore.Account a : ws) {
                if (!RUN.running) break;
                RUN.current = a.label;
                RUN.log("→ " + a.label + " 开始");
                long sid = activeSid(a);
                // 同服多角色：带上激活角色 uid，本地跑日常钉扎到具体角色（不落同服另一个）
                long uid = RoleStore.activeRoleUid(HomeActivity.this, a.fpid);
                try {
                    final com.carlos.vault.daily.LogStore ls = new com.carlos.vault.daily.LogStore(HomeActivity.this);
                    DailyRunner.Summary s2 = DailyRunner.run(HomeActivity.this, a.fpid, a.token,
                            sid, uid, null, new DailyRunner.ProgressListener() {
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
                                        ls.setStatus(a.fpid, sid, r.taskId,
                                                r.category == DailyResult.Category.CLAIMED
                                                        ? com.carlos.vault.daily.LogStore.ST_DONE
                                                        : r.category == DailyResult.Category.SKIPPED
                                                        ? com.carlos.vault.daily.LogStore.ST_SKIPPED
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
            runEndedAt = System.currentTimeMillis();
            RUN.running = false;
            RUN.log("→ 全部完成 " + RUN.done + "/" + RUN.total);
            runOnUiThread(() -> {
                toast("日常已完成 · " + RUN.done + "/" + RUN.total + " 个号");
                refreshAllPages();
            });
        }, "run-all").start();
    }

    /**
     * 服务器批量执行：逐账号同步配置后提交整轮，执行页只展示“提交/受理”结果。
     * 真正的任务进度与领取结果由服务器写 state，App 通过 fetchState 回写后展示。
     */
    private void runListServer(final List<AccountStore.Account> ws) {
        RUN.running = true;
        RUN.done = 0;
        RUN.total = ws.size();
        RUN.current = "";
        synchronized (RUN.logs) {
            RUN.logs.clear();
        }
        RUN.log("→ 提交服务器执行 " + ws.size() + " 个号");
        runStartedAt = System.currentTimeMillis();
        runEndedAt = 0;
        showRunPanel(ws);
        VaultSync.ensureServerMode(this);
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(ws.size());
        for (final AccountStore.Account a : ws) {
            RUN.current = a.label;
            RUN.log("→ " + a.label + " 上传配置并登录服务器…");
            // 先全量上报（配置/参数/角色）再由服务器建会话触发，保证“配置读取→任务触发”同源。
            VaultSync.syncAll(this, (sc, sm, sd) -> {
                if (sc != 0) {
                    RUN.log("✗ " + a.label + " · 配置同步失败：" + sm);
                    if (left.decrementAndGet() == 0) finishRunListServer();
                    return;
                }
                VaultSync.triggerAutomation(this, a.fpid, null, (code, message, data) -> {
                    boolean ok = code == 0 && data != null && data.optBoolean("accepted", false);
                    String state = data == null ? "" : data.optString("state", "");
                    String sid = data == null ? "" : data.optString("sid", "");
                    if (ok) {
                        RUN.done++;
                        RUN.log("✓ " + a.label + " · 服务器已受理"
                                + (sid.isEmpty() ? "" : " sid=" + sid)
                                + (state.isEmpty() ? "" : " 会话=" + state));
                    } else {
                        RUN.log("✗ " + a.label + " · 服务器未受理：" + message);
                    }
                    if (left.decrementAndGet() == 0) finishRunListServer();
                });
            });
        }
    }

    private void finishRunListServer() {
        runEndedAt = System.currentTimeMillis();
        RUN.running = false;
        RUN.log("→ 服务器已受理 " + RUN.done + "/" + RUN.total + " 个号；结果稍后自动回写");
        VaultSync.fetchState(this);
        runOnUiThread(() -> {
            toast("已提交服务器 · " + RUN.done + "/" + RUN.total + " 个号");
            refreshAllPages();
        });
    }

    /**
     * 执行页（设计稿 S10：品牌渐变 Hero 进度 / 当前账号与步骤 / 逐号结果 / mono 日志 / 底部操作栏）。
     *
     * <p>可离开：线程继续，随时回来看。执行引擎（RunState/线程）不改动，仅视图层：
     * <ul>
     *   <li>进度/百分比/时长来自 RunState 只读字段（时长为展示态，开局时记时间戳）</li>
     *   <li>逐号结果与当前步骤从日志事件反推，不新增引擎状态</li>
     *   <li>attacher 回主线程执行：引擎在后台线程回调 log()，视图更新必须 runOnUiThread</li>
     * </ul>
     */
    private void showRunPanel() {
        renderRunPanel(runNames);
    }

    /** 开批入口：带入本批账号名单（逐号结果行）；执行中再打开沿用上次名单。 */
    private void showRunPanel(List<AccountStore.Account> wsIn) {
        List<String> names = new ArrayList<>();
        for (AccountStore.Account a : wsIn) {
            if (a != null && a.label != null) names.add(a.label);
        }
        runNames = names;
        renderRunPanel(names);
    }

    /** 执行页（设计稿 S10）：全屏进度 + 当前账号 + 逐号结果 + 实时日志 + 底部操作。 */
    private void renderRunPanel(List<String> names) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(14), dp(6));
        FrameLayout back = new FrameLayout(this);
        back.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.INK, 22),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(back);
        back.setOnClickListener(v -> dlg.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("自动日常运行");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout more = new FrameLayout(this);
        more.addView(new UiStyle.Icon(this, UiStyle.Icon.MORE, UiStyle.INK2, 20),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        UiStyle.press(more);
        more.setOnClickListener(v -> new AlertDialog.Builder(this,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setItems(new String[]{"后台运行", "停止本轮"}, (dd, w) -> {
                    if (w == 0) {
                        RUN.attacher = null;
                        dlg.dismiss();
                    } else if (RUN.running) {
                        if (runEndedAt == 0) runEndedAt = System.currentTimeMillis();
                        RUN.running = false;
                        RUN.log("→ 用户停止");
                    }
                }).show());
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(20));
        sc.addView(box);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Hero
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 20));
        box.addView(hero);

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView heroCap = new TextView(this);
        heroCap.setText("正在执行");
        heroCap.setTextSize(14);
        heroCap.setTypeface(Typeface.DEFAULT_BOLD);
        heroCap.setTextColor(UiStyle.WHITE);
        heroTop.addView(heroCap, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout runPill = new LinearLayout(this);
        runPill.setOrientation(LinearLayout.HORIZONTAL);
        runPill.setGravity(Gravity.CENTER_VERTICAL);
        runPill.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE_20, 999));
        runPill.setPadding(dp(9), dp(4), dp(9), dp(4));
        View runDot = new View(this);
        runDot.setBackground(UiStyle.roundRect(UiStyle.WHITE, dp(3)));
        runPill.addView(runDot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        TextView runTx = new TextView(this);
        runTx.setText("运行中");
        runTx.setTextSize(11);
        runTx.setTextColor(UiStyle.WHITE);
        runTx.setPadding(dp(5), 0, 0, 0);
        runPill.addView(runTx);
        heroTop.addView(runPill);
        hero.addView(heroTop);

        LinearLayout heroMid = new LinearLayout(this);
        heroMid.setOrientation(LinearLayout.HORIZONTAL);
        heroMid.setGravity(Gravity.BOTTOM);
        heroMid.setPadding(0, dp(8), 0, 0);
        final TextView pct = new TextView(this);
        pct.setTextSize(42);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        pct.setTextColor(UiStyle.WHITE);
        heroMid.addView(pct);
        LinearLayout heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);
        heroInfo.setPadding(dp(14), 0, 0, dp(6));
        final TextView cnt = new TextView(this);
        cnt.setTextSize(13);
        cnt.setTypeface(Typeface.DEFAULT_BOLD);
        cnt.setTextColor(UiStyle.WHITE);
        heroInfo.addView(cnt);
        final TextView elapsed = new TextView(this);
        elapsed.setTextSize(11);
        elapsed.setTextColor(UiStyle.BRAND_100);
        elapsed.setPadding(0, dp(4), 0, 0);
        heroInfo.addView(elapsed);
        heroMid.addView(heroInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(heroMid);

        final LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE_20, 4));
        final View fill = new View(this);
        fill.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE, 4));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        tlp.topMargin = dp(12);
        hero.addView(track, tlp);

        // 当前账号
        LinearLayout curHead = new LinearLayout(this);
        curHead.setOrientation(LinearLayout.HORIZONTAL);
        curHead.setGravity(Gravity.CENTER_VERTICAL);
        curHead.setPadding(dp(2), dp(18), dp(2), dp(8));
        TextView curTitle = new TextView(this);
        curTitle.setText("当前账号");
        curTitle.setTextSize(16);
        curTitle.setTypeface(Typeface.DEFAULT_BOLD);
        curTitle.setTextColor(UiStyle.INK);
        curHead.addView(curTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView curHint = new TextView(this);
        curHint.setText("实时");
        curHint.setTextSize(11);
        curHint.setTextColor(UiStyle.INK2);
        curHead.addView(curHint);
        box.addView(curHead);

        LinearLayout cur = UiStyle.outlineCard(this);
        cur.setOrientation(LinearLayout.VERTICAL);
        cur.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout curRow = new LinearLayout(this);
        curRow.setOrientation(LinearLayout.HORIZONTAL);
        curRow.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.FrameLayout curAvatar = UiStyle.snowAvatar(this, 56, "current");
        curRow.addView(curAvatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout curMid = new LinearLayout(this);
        curMid.setOrientation(LinearLayout.VERTICAL);
        curMid.setPadding(dp(12), 0, dp(8), 0);
        final TextView cName = new TextView(this);
        cName.setTextSize(15);
        cName.setTypeface(Typeface.DEFAULT_BOLD);
        cName.setTextColor(UiStyle.INK);
        curMid.addView(cName);
        final TextView cSub = new TextView(this);
        cSub.setTextSize(11.5f);
        cSub.setTextColor(UiStyle.INK2);
        cSub.setPadding(0, dp(4), 0, 0);
        curMid.addView(cSub);
        curRow.addView(curMid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView cBadge = UiStyle.badge(this, "run", "执行中");
        curRow.addView(cBadge);
        cur.addView(curRow);
        final TextView step = new TextView(this);
        step.setTextSize(12);
        step.setTextColor(UiStyle.INK2);
        step.setPadding(0, dp(10), 0, 0);
        cur.addView(step);
        final LinearLayout curTrack = new LinearLayout(this);
        curTrack.setOrientation(LinearLayout.HORIZONTAL);
        curTrack.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND_50, 3));
        final View curFill = new View(this);
        curFill.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 3));
        curTrack.addView(curFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        ctlp.topMargin = dp(10);
        cur.addView(curTrack, ctlp);
        box.addView(cur);

        // 全部账号
        final List<TextView> badges = new ArrayList<>();
        final List<TextView> dots = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        if (!names.isEmpty()) {
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(2), dp(18), dp(2), dp(8));
            TextView ht = new TextView(this);
            ht.setText("全部账号 · " + names.size());
            ht.setTextSize(16);
            ht.setTypeface(Typeface.DEFAULT_BOLD);
            ht.setTextColor(UiStyle.INK);
            head.addView(ht, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView hh = new TextView(this);
            hh.setText("按完成度排序");
            hh.setTextSize(11);
            hh.setTextColor(UiStyle.INK2);
            head.addView(hh);
            box.addView(head);
            int idx = 1;
            for (String label : names) {
                LinearLayout row = UiStyle.outlineCard(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(12), dp(12), dp(12));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(8);
                row.setLayoutParams(rlp);
                TextView dot = new TextView(this);
                dot.setText(String.valueOf(idx++));
                dot.setTextSize(14);
                dot.setTypeface(Typeface.DEFAULT_BOLD);
                dot.setTextColor(UiStyle.WHITE);
                dot.setGravity(Gravity.CENTER);
                dot.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 999));
                row.addView(dot, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout mid = new LinearLayout(this);
                mid.setOrientation(LinearLayout.VERTICAL);
                mid.setPadding(dp(12), 0, dp(8), 0);
                TextView nm = new TextView(this);
                nm.setText(label);
                nm.setTextSize(15);
                nm.setTypeface(Typeface.DEFAULT_BOLD);
                nm.setTextColor(UiStyle.INK);
                mid.addView(nm);
                TextView sub = new TextView(this);
                sub.setText("等待执行");
                sub.setTextSize(11.5f);
                sub.setTextColor(UiStyle.INK2);
                sub.setPadding(0, dp(3), 0, 0);
                mid.addView(sub);
                row.addView(mid, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView b = UiStyle.badge(this, "idle", "等待中");
                row.addView(b);
                box.addView(row);
                badges.add(b);
                dots.add(dot);
                labels.add(label);
            }
        }

        // 实时日志
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.setPadding(dp(2), dp(18), dp(2), dp(8));
        TextView lt = new TextView(this);
        lt.setText("实时日志");
        lt.setTextSize(16);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        lt.setTextColor(UiStyle.INK);
        logHead.addView(lt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView lh = new TextView(this);
        lh.setText("保留最近 200 条");
        lh.setTextSize(11);
        lh.setTextColor(UiStyle.INK2);
        logHead.addView(lh);
        box.addView(logHead);

        LinearLayout logCard = UiStyle.outlineCard(this);
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        ScrollView logSc = new ScrollView(this);
        final TextView log = new TextView(this);
        log.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        log.setTextSize(10.5f);
        log.setTextColor(UiStyle.INK2);
        logSc.addView(log);
        logCard.addView(logSc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        box.addView(logCard);

        // 底部操作栏
        View line = new View(this);
        line.setBackgroundColor(UiStyle.LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(18), dp(12), dp(18), dp(16));
        foot.setBackgroundColor(UiStyle.WHITE);
        TextView bgBtn = new TextView(this);
        bgBtn.setText("后台运行");
        bgBtn.setTextSize(14);
        bgBtn.setTypeface(Typeface.DEFAULT_BOLD);
        bgBtn.setTextColor(UiStyle.INK);
        bgBtn.setGravity(Gravity.CENTER);
        bgBtn.setPadding(0, dp(13), 0, dp(13));
        bgBtn.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 14, UiStyle.LINE));
        foot.addView(bgBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView stopBtn = new TextView(this);
        stopBtn.setText(RUN.running ? "停止" : "关闭");
        stopBtn.setTextSize(14);
        stopBtn.setTypeface(Typeface.DEFAULT_BOLD);
        stopBtn.setTextColor(UiStyle.WHITE);
        stopBtn.setGravity(Gravity.CENTER);
        stopBtn.setPadding(0, dp(13), 0, dp(13));
        stopBtn.setBackground(UiStyle.roundRectDp(this,
                RUN.running ? UiStyle.ERR_SOLID : UiStyle.INK, 14));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.leftMargin = dp(10);
        foot.addView(stopBtn, slp);
        root.addView(foot);

        final Runnable update = () -> {
            float p = RUN.total <= 0 ? 0f : RUN.done / (float) RUN.total;
            int pctVal = RUN.total <= 0 ? 0
                    : (RUN.done >= RUN.total ? 100 : Math.min(99, (int) (100L * RUN.done / RUN.total)));
            pct.setText(pctVal + "%");
            cnt.setText(RUN.done + " / " + RUN.total + " 项已完成");
            int w = track.getWidth();
            if (w > 0) {
                LinearLayout.LayoutParams fl = (LinearLayout.LayoutParams) fill.getLayoutParams();
                fl.width = Math.max(dp(8), (int) (w * p));
                fill.setLayoutParams(fl);
            }
            boolean finished = !RUN.running && RUN.total > 0 && RUN.done >= RUN.total;
            boolean ended = !RUN.running && RUN.total > 0;   // 含中途停止
            if (ended && runEndedAt == 0) runEndedAt = System.currentTimeMillis();
            heroCap.setText(RUN.running ? "正在执行"
                    : finished ? "执行结束" : ended ? "已停止" : "未开始");
            runTx.setText(RUN.running ? "运行中"
                    : finished ? "已结束" : ended ? "已停止" : "空闲");
            curHint.setText(RUN.running ? "实时" : (RUN.total > 0 ? "最近一轮" : "暂无"));
            elapsed.setText(runStartedAt == 0 ? "准备中"
                    : "已运行 " + elapsedText()
                    + (RUN.running ? " · 实时更新" : finished ? " · 已结束" : " · 已停止"));
            String stopLabel = RUN.running ? "停止" : "关闭";
            if (!stopLabel.contentEquals(stopBtn.getText())) {
                stopBtn.setText(stopLabel);
                stopBtn.setBackground(UiStyle.roundRectDp(this,
                        RUN.running ? UiStyle.ERR_SOLID : UiStyle.INK, 14));
            }
            cName.setText(RUN.current.isEmpty() ? "—" : RUN.current);
            cSub.setText(RUN.done + " / " + RUN.total + " 项"
                    + (RUN.running ? " · 实时进度"
                    : finished ? " · 已结束" : ended ? " · 已停止" : " · 未开始"));
            styleRunBadge(cBadge, RUN.running ? "run" : finished ? "ok" : "idle");
            cBadge.setText(RUN.running ? "执行中" : finished ? "已完成"
                    : ended ? "已停止" : "未开始");
            step.setText("当前步骤：" + currentStepText());
            // 单一子项用 weight 会恒占满整行，必须像 Hero 进度条一样回写显式宽度。
            int cw = curTrack.getWidth();
            if (cw > 0) {
                LinearLayout.LayoutParams cfl =
                        (LinearLayout.LayoutParams) curFill.getLayoutParams();
                cfl.width = Math.max(dp(6), (int) (cw * p));
                curFill.setLayoutParams(cfl);
            }
            for (int i = 0; i < badges.size(); i++) {
                String st = runStateOf(labels.get(i));
                styleRunBadge(badges.get(i), st);
                badges.get(i).setText("ok".equals(st) ? "已完成"
                        : "run".equals(st) ? "执行中"
                        : "err".equals(st) ? "异常" : "等待中");
                int color = "ok".equals(st) ? UiStyle.OK_SOLID
                        : "run".equals(st) ? UiStyle.BRAND
                        : "err".equals(st) ? UiStyle.WARN_SOLID : UiStyle.INK3;
                dots.get(i).setBackground(UiStyle.roundRectDp(this, color, 999));
            }
            StringBuilder sb = new StringBuilder();
            synchronized (RUN.logs) {
                for (String l : RUN.logs) {
                    sb.append(l).append('\n');
                }
            }
            log.setText(sb.toString());
            logSc.post(() -> logSc.fullScroll(ScrollView.FOCUS_DOWN));
        };
        update.run();
        track.post(update);   // 首帧布局完成后再按实际宽度回写进度
        RUN.attacher = () -> runOnUiThread(update);

        bgBtn.setOnClickListener(v -> {
            RUN.attacher = null;
            dlg.dismiss();
        });
        stopBtn.setOnClickListener(v -> {
            if (RUN.running) {
                if (runEndedAt == 0) runEndedAt = System.currentTimeMillis();
                RUN.running = false;
                RUN.log("→ 用户停止");
            }
            RUN.attacher = null;
            dlg.dismiss();
        });

        dlg.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.setOnDismissListener(dd -> RUN.attacher = null);
        dlg.show();
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            try {
                win.setStatusBarColor(UiStyle.BG_V2);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 执行页徽章原地换色（与 UiStyle.badge 同色板，避免每轮重建视图）。 */
    private void styleRunBadge(TextView v, String type) {
        int bg, ink;
        switch (type) {
            case "ok":   bg = UiStyle.OK_BG;   ink = UiStyle.OK_INK;   break;
            case "run":  bg = UiStyle.RUN_BG;  ink = UiStyle.RUN_INK;  break;
            case "err":  bg = UiStyle.ERR_BG;  ink = UiStyle.ERR_INK;  break;
            default:     bg = UiStyle.IDLE_BG; ink = UiStyle.IDLE_INK; break;
        }
        v.setTextColor(ink);
        v.setBackground(UiStyle.roundRectDp(this, bg, 999));
    }

    /** 逐号状态（从日志事件反推，只读不写引擎）：✓=完成 ✗=异常 →开始=执行中 其余=等待。 */
    private String runStateOf(String label) {
        synchronized (RUN.logs) {
            for (int i = RUN.logs.size() - 1; i >= 0; i--) {
                String l = RUN.logs.get(i);
                if (l.startsWith("✓ " + label)) return "ok";
                if (l.startsWith("✗ " + label)) return "err";
                if (l.startsWith("→ " + label + " 开始")) return "run";
            }
        }
        return "idle";
    }

    /** 当前步骤：最近一条 ▸ 日志（形如「账号 · 任务名」）；无则准备中/排队中。 */
    private String currentStepText() {
        synchronized (RUN.logs) {
            for (int i = RUN.logs.size() - 1; i >= 0; i--) {
                String l = RUN.logs.get(i);
                if (l.startsWith("▸ ")) return l.substring(2);
            }
        }
        return RUN.current.isEmpty()
                ? (RUN.running ? "排队中…" : "暂无记录")
                : RUN.current + " · 准备中";
    }

    /** 已运行时长文案（展示态计时，开局时记时间戳）。 */
    private String elapsedText() {
        long end = runEndedAt > 0 ? runEndedAt : System.currentTimeMillis();
        long sec = Math.max(0, end - runStartedAt) / 1000;
        return sec < 60 ? (sec + " 秒") : (sec / 60 + " 分钟");
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
