package com.carlos.vault.daily;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.AccountStore;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.gather.GatherConfigV2;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 功能设置页：单个功能的说明/开关（按角色独立）/立即执行/操作日志。
 */
public class FunctionActivity extends Activity {

    // 兜底：进程被回收后本页可能成为栈底，按返回应回主页而不是退出App
    @Override
    public void onBackPressed() {
        if (isTaskRoot()) {
            try {
                startActivity(new android.content.Intent(this, com.carlos.vault.HomeActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return;
            } catch (Throwable ignored) {
            }
        }
        super.onBackPressed();
    }


    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_FN_ID = "fn_id";
    public static final String EXTRA_SERVERID = "serverid";

    private AccountStore store;
    private DailyConfigStore configStore;
    private LogStore logStore;
    private AccountStore.Account account;
    private FnRegistry.Fn fn;
    private long serverid;
    private TextView logView;
    private TextView claimView;
    private UiStyle.Switch toggle;
    /** 采集卡副标题（配置摘要）：保存采集设置返回本页时原位刷新，无需整页重建。 */
    private TextView gatherSummaryView;
    /** 军医所设置卡：每批治疗数量输入框（失焦/退出页面自动保存并下发）。 */
    private EditText medicHealEt;
    private TextView medicSummaryView;
    /** 输入框是否有未保存改动（onPause 兜底保存用）。 */
    private boolean medicHealDirty;
    // 每日任务专属（原日常页模块迁移）
    private LinearLayout extraBox;
    private TextView actTitle, actSub;
    private android.widget.ProgressBar actBar;
    private LinearLayout boxRow, questBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AccountStore(this);
        configStore = new DailyConfigStore(this);
        logStore = new LogStore(this);

        String accId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        String fnId = getIntent().getStringExtra(EXTRA_FN_ID);
        serverid = getIntent().getLongExtra(EXTRA_SERVERID, 0);
        if (serverid <= 0) {
            // 未显式传区服：取该凭证当前启用角色
            long active = RoleStore.activeRole(this, getIntent().getStringExtra(EXTRA_ACCOUNT_ID) == null ? "" : findFpid());
            if (active > 0) serverid = active;
        }
        for (AccountStore.Account a : store.all()) {
            if (a != null && a.id != null && a.id.equals(accId)) {
                account = a;
                break;
            }
        }
        fn = FnRegistry.byId(fnId);
        if (account == null || fn == null) {
            finish();
            return;
        }
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        setContentView(buildUi());
        // 服务器模式：进页先拉一次服务器最新状态，回写后重渲染（否则看到的是上次快照）
        if (com.carlos.vault.VaultSync.isServerMode(this)
                && account.fpid != null && !account.fpid.isEmpty()) {
            com.carlos.vault.VaultSync.fetchState(this, (code, message, data) ->
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || code != 0) return;
                            // 用户正在输入医院批量数量时不重建（会把输入框内容冲掉）
                            if (medicHealEt != null && medicHealEt.hasFocus()) return;
                            setContentView(buildUi());
                        } catch (Throwable ignored) {
                        }
                    }));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 数字输入不随每次击键下发：仅用户改过时在失焦/退出页面保存（含非法回退 400）
        saveMedicHealIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「采集设置」页保存返回时原位刷新副标题摘要（整页重建会丢掉滚动位置/执行结果区）
        refreshGatherSummary();
    }

    /** 刷新采集卡副标题：GatherConfigV2.summary()（如「3队 · 肉8/木7/煤9」）。 */
    private void refreshGatherSummary() {
        if (gatherSummaryView == null || account == null) return;
        try {
            GatherConfigV2 cfg = GatherConfigV2.parse(
                    FnParams.params(this, account.fpid, serverid, "gather"));
            gatherSummaryView.setText(cfg.summary());
        } catch (Throwable ignored) {
        }
    }

    private String findFpid() {
        String accId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        for (AccountStore.Account a : store.all()) {
            if (a != null && a.id != null && a.id.equals(accId)) return a.fpid;
        }
        return "";
    }

    /**
     * 编队卡使用的角色 sid：优先本页 serverid；为 0 时回退当前激活角色
     * （账号页深链/旧入口不带 EXTRA_SERVERID），仍为 0 返回 0（卡片降级"等待角色同步"）。
     */
    private long formationSid() {
        if (serverid > 0) return serverid;
        try {
            long active = RoleStore.activeRole(this, account == null ? "" : account.fpid);
            if (active > 0) return active;
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /** 打开页面时的状态刷新节流（同功能 60 秒内不重复拉）。 */
    private static final java.util.Map<String, Long> LAST_FN_REFRESH = new java.util.concurrent.ConcurrentHashMap<>();

    private void refreshOnOpen(final LinearLayout subCard) {
        try {
            // 服务器模式：本地无常驻会话（执行与采集都在 vault-server），状态新鲜度由
            // onCreate 的 fetchState 重渲染负责，不走本地引擎刷新也不提示"会话不在线"
            if (com.carlos.vault.VaultSync.isServerMode(this)) return;
            final String fpid = account.fpid;
            final long sid = serverid;
            String key = fpid + ":" + sid + ":" + fn.id;
            long now = System.currentTimeMillis();
            Long last = LAST_FN_REFRESH.get(key);
            if (last != null && now - last < 60_000L) return;
            LAST_FN_REFRESH.put(key, now);
            new Thread(() -> {
                RoleSessionKeeper.LiveSession live = DailyRunner.liveOf(
                        FunctionActivity.this, fpid, sid);
                if (live == null) {
                    // 无常驻会话（未开启/被顶号暂停/重连中/需重导token）：保持现有快照并提示
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || subCard == null) return;
                            TextView tip = new TextView(FunctionActivity.this);
                            tip.setText("⏳ 常驻会话不在线，显示上次快照（开启 24h 常驻在线后自动实时刷新）");
                            tip.setTextSize(10.5f);
                            tip.setTextColor(UiStyle.WARN_INK);
                            tip.setPadding(dp(4), dp(6), dp(4), dp(2));
                            subCard.addView(tip, 0);
                        } catch (Throwable ignored) {
                        }
                    });
                    return;
                }
                boolean ok = DailyRunner.refreshFnState(
                        FunctionActivity.this, live.engine, fpid, sid, fn.id);
                if (!ok) return;
                runOnUiThread(() -> {
                    try {
                        if (isFinishing() || subCard == null) return;
                        subCard.removeAllViews();
                        subCard.addView(SubCardUi.build(FunctionActivity.this, fpid, sid, fn.id));
                    } catch (Throwable ignored) {
                    }
                });
            }, "fn-refresh").start();
        } catch (Throwable ignored) {
        }
    }

    private View buildUi() {        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(14), dp(6));
        android.widget.FrameLayout backWrap = new android.widget.FrameLayout(this);
        backWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.INK, 22),
                new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(backWrap);
        backWrap.setOnClickListener(v -> finish());
        bar.addView(backWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("功能详情");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        // 生效开关（口径统一，2026-10-28 功能型 bug A/C）：未配置时回落
        // DailyTasks.TaskDef.enabledByDefault（barracks/intel/arena 等默认关），与日常页
        // HomeActivity.fnOn 一致；读取走「有效配置」（稀疏 map 补齐全部任务键再回写）。
        Map<String, Boolean> rawCfg = configStore.roleConfig(account.fpid, serverid);
        Map<String, Boolean> cfg = configStore.effectiveRoleConfig(
                account.fpid, serverid, DailyTasks.all());
        final boolean autoOn = com.carlos.vault.daily.DailyConfigStore.effectiveOn(cfg, fn);
        final boolean autoExplicit = rawCfg != null && rawCfg.containsKey(fn.id);

        // 功能 Hero：图标 + 名称/描述 + 今日状态
        LinearLayout info = UiStyle.outlineCard(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.setMargins(dp(18), dp(6), dp(18), 0);
        info.setLayoutParams(infoLp);
        int fnIcon = "login_rewards".equals(fn.id) ? UiStyle.Icon.GIFT : UiStyle.Icon.DOC;
        info.addView(UiStyle.iconTile(this, fnIcon, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 54, 26, 14),
                new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(dp(14), 0, 0, 0);
        TextView fnName = new TextView(this);
        fnName.setText(fn.name);
        fnName.setTextSize(20);
        fnName.setTypeface(Typeface.DEFAULT_BOLD);
        fnName.setTextColor(UiStyle.INK);
        infoCol.addView(fnName);
        TextView desc = new TextView(this);
        desc.setText(fn.desc);
        desc.setTextSize(12);
        desc.setTextColor(UiStyle.INK2);
        desc.setPadding(0, dp(4), 0, 0);
        infoCol.addView(desc);

        int stNow = new com.carlos.vault.daily.LogStore(this)
                .status(account.fpid, serverid, fn.id);
        String stType = stNow == com.carlos.vault.daily.LogStore.ST_DONE ? "ok"
                : stNow == com.carlos.vault.daily.LogStore.ST_SKIPPED ? "ok"
                : stNow == com.carlos.vault.daily.LogStore.ST_RUNNING ? "run"
                : stNow == com.carlos.vault.daily.LogStore.ST_FAILED ? "err" : "idle";
        String stText = stNow == com.carlos.vault.daily.LogStore.ST_DONE ? "今日已领"
                : stNow == com.carlos.vault.daily.LogStore.ST_SKIPPED ? "已检查·无可领"
                : stNow == com.carlos.vault.daily.LogStore.ST_RUNNING ? "进行中"
                : stNow == com.carlos.vault.daily.LogStore.ST_FAILED ? "上次失败" : "今日未跑";
        LinearLayout stRow = new LinearLayout(this);
        stRow.setOrientation(LinearLayout.HORIZONTAL);
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        stRow.setPadding(0, dp(8), 0, 0);
        stRow.addView(UiStyle.badge(this, stType, "● " + stText));
        long nextAt = com.carlos.vault.daily.RoleStore.schedule(this, account.fpid).nextRunAt;
        TextView next = new TextView(this);
        // 服务器模式下明确区分“服务器自动执行 / 仅手动”：未加入自动化时不再笼统写“服务器调度”
        if (com.carlos.vault.VaultSync.isServerMode(this)) {
            next.setText(autoOn ? "执行：服务器调度" : "执行：仅手动（未加入自动化）");
        } else {
            next.setText(nextAt > 0 ? "下次自动："
                    + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(nextAt)) : "下次自动：未开启");
        }
        next.setTextSize(11);
        next.setTextColor(UiStyle.INK2);
        next.setPadding(dp(8), 0, 0, 0);
        stRow.addView(next);
        infoCol.addView(stRow);
        info.addView(infoCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(info);

        // 立即执行（规范主按钮：52dp · 品牌实心 · 按压反馈；未开放置灰）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(dp(18), dp(14), dp(18), 0);
        TextView run = actionButton(fn.implemented ? "立即执行一次" : "功能待开放", fn.implemented);
        btnRow.addView(run, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(btnRow);

        // 加入自动日常：显示口径 = 有效默认值（见上方 autoOn/autoExplicit 计算）
        LinearLayout enableRow = UiStyle.outlineCard(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        enableRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams erlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erlp.setMargins(dp(18), dp(14), dp(18), 0);
        enableRow.setLayoutParams(erlp);
        enableRow.addView(UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout enableCol = new LinearLayout(this);
        enableCol.setOrientation(LinearLayout.VERTICAL);
        enableCol.setPadding(dp(12), 0, dp(8), 0);
        TextView enableTitle = new TextView(this);
        enableTitle.setText("加入自动化任务");
        enableTitle.setTextSize(15);
        enableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enableTitle.setTextColor(UiStyle.INK);
        enableCol.addView(enableTitle);
        TextView enableSub = new TextView(this);
        enableSub.setText("按角色独立设置 · 关闭后仅手动执行");
        enableSub.setTextSize(11.5f);
        enableSub.setTextColor(UiStyle.INK2);
        enableSub.setPadding(0, dp(3), 0, 0);
        enableCol.addView(enableSub);
        // 默认值/生效口径灰字提示（避免“看起来开着但服务器没跑”的误解）
        TextView enableHint = new TextView(this);
        enableHint.setText(!autoOn
                ? (autoExplicit ? "已关闭 · 服务器不会自动执行（可随时手动执行）"
                : "默认关闭 · 打开后服务器按节奏自动执行")
                : (autoExplicit ? "已开启 · 服务器按节奏自动执行"
                : "默认开启 · 可在日常页随时关闭"));
        enableHint.setTextSize(10.5f);
        enableHint.setTextColor(autoOn ? UiStyle.BRAND_TEXT : UiStyle.INK3);
        enableHint.setPadding(0, dp(2), 0, 0);
        enableCol.addView(enableHint);
        enableRow.addView(enableCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final UiStyle.Switch[] swRef = new UiStyle.Switch[1];
        toggle = new UiStyle.Switch(this, autoOn,
                () -> {
                    boolean isChecked = swRef[0].isOn();
                    // Phase2C 修复（2026-10-30）：显式按当前角色 uid 读写，避免同服双角色时
                    // 旧 active 键只记 sid → 解析到另一个角色、把开关串写到别的角色。
                    long uidNow = RoleStore.currentRoleUid(FunctionActivity.this, account.fpid);
                    // 写入前先补齐为完整配置，避免稀疏 map 整包上传把其它开关冲掉
                    Map<String, Boolean> c = configStore.effectiveRoleConfig(
                            account.fpid, serverid, uidNow, DailyTasks.all());
                    c.put(fn.id, isChecked);
                    configStore.saveRoleConfig(account.fpid, serverid, uidNow, c);
                    com.carlos.vault.VaultSync.onAccountsChanged(FunctionActivity.this);
                    toast(isChecked ? "已加入自动化任务并同步服务器" : "已从自动化任务移除");
                });
        swRef[0] = toggle;
        enableRow.addView(toggle);
        root.addView(enableRow);

        // 批次12：采集改为独立「采集设置」页（6 队列 × 资源/等级/编队/兵量，GatherConfigV2 单一事实源），
        // 原弹窗降级为设置页底部「旧版参数（兼容）」，本卡片不再直接弹窗。
        if ("gather".equals(fn.id)) {
            LinearLayout pick = UiStyle.outlineCard(this);
            pick.setOrientation(LinearLayout.HORIZONTAL);
            pick.setGravity(Gravity.CENTER_VERTICAL);
            pick.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMargins(dp(18), dp(10), dp(18), 0);
            pick.setLayoutParams(plp);
            pick.addView(UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT,
                    UiStyle.BRAND_50, 40, 20, 12),
                    new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            TextView t = new TextView(this);
            t.setText("采集参数");
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(UiStyle.INK);
            mid.addView(t);
            TextView s = new TextView(this);
            // 动态摘要：v2 优先；无 v2 时按旧配置迁移后的等价摘要（如「3队 · 肉8/木7/煤9」）
            gatherSummaryView = s;
            s.setTextSize(11.5f);
            s.setTextColor(UiStyle.INK2);
            mid.addView(s);
            pick.addView(mid, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(18);
            arrow.setTextColor(UiStyle.INK2);
            pick.addView(arrow);
            UiStyle.press(pick);
            pick.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(this, GatherConfigActivity.class)
                            .putExtra(EXTRA_ACCOUNT_ID, account.id)
                            .putExtra(EXTRA_FN_ID, "gather")
                            .putExtra(EXTRA_SERVERID, serverid));
                } catch (Throwable e) {
                    toast("打开采集设置失败：" + e.getMessage());
                }
            });
            refreshGatherSummary();
            root.addView(pick);
        }

        // 用户侧不再提供任何 JSON 参数输入：参数一律由功能专属卡片的开关/下拉完成
        // （采集→采集设置页；狩猎→下方「狩猎设置」卡；其余功能用子项开关/策略选项）。

        // 整页滚动区：子项实时状态与配置 → 领取内容 → 运行记录
        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollBox = new LinearLayout(this);
        scrollBox.setOrientation(LinearLayout.VERTICAL);
        scrollBox.setPadding(0, 0, 0, dp(16));
        scroll.addView(scrollBox);

        // ⓪ 批次10（2026-10-23）：队列类功能统一编队（英雄/兵种/比例，随角色 params 同步服务器）。
        // 采集/情报/狩猎/巨兽/巨熊/守护盟友按此编队出征（服务器 FormationResolver 消费）。
        // T5（2026-10-25）：改为「编队摘要卡 + 编队管理入口」；sid≤0（账号页深链未传 serverid /
        // 角色未同步）先回退当前角色 sid，仍为 0 时卡片显示「等待角色同步」并禁用入口，
        // 避免用 sid=0 去读 srv:formation:catalog:{fpid}:0 导致目录恒空。
        if (fn.formation) {
            long fnSid = formationSid();
            if (fnSid > 0 && fnSid != serverid) {
                serverid = fnSid;   // 回填本页角色 sid：后续卡片/保存与编队卡同源
            }
            scrollBox.addView(FormationPicker.buildCard(this, account.fpid, serverid, fn.id, null));
            if ("bear".equals(fn.id)) {
                // 巨熊另需「发起队 + 上车编队 1-6」（每支可独立自动/指定）
                scrollBox.addView(FormationPicker.buildBearJoinCard(
                        this, account.fpid, serverid, null));
            }
            if ("intel".equals(fn.id)) {
                // 设计 §4.2（2026-10-31）：情报出征设置卡——三行英雄下拉（盾/矛/弓，
                // 默认最强盾/波根/吉娜）+ 兵种比例（intel_ratio 10:10:80），写 intel_* params
                // 由服务端 IntelFormationPlan 消费；位于通用「编队」摘要卡下方，其它功能页不受影响。
                long intelUid = RoleStore.currentRoleUid(this, account.fpid);
                scrollBox.addView(IntelFormationCard.build(this, account.fpid, serverid,
                        intelUid > 0L ? String.valueOf(intelUid) : "", fn.id, null));
            }
        }
        // 批次11：守护盟友配置页（用户确认放开 UI）——只读状态卡 + 配置卡
        if ("guardian".equals(fn.id)) {
            scrollBox.addView(buildGuardianStatusCard());
            scrollBox.addView(buildGuardianConfigCard());
        }

        // ⓪ 登录好礼专属：十四天签到网格（游戏同款元素）
        if ("login_rewards".equals(fn.id)) {
            buildLoginGift(scrollBox);
        }
        // ⓪ 其余奖励类专属区（邮件/补给/挂机/活动合集/统帅/无尽征程）
        if ("mail".equals(fn.id) || "supply".equals(fn.id) || "adventure".equals(fn.id)
                || "activity_rewards".equals(fn.id) || "commander_gift".equals(fn.id)
                || "act_endless".equals(fn.id)) {
            extraBox = new LinearLayout(this);
            extraBox.setOrientation(LinearLayout.VERTICAL);
            scrollBox.addView(extraBox);
            rebuildExtra();
        }
        // ⓪ 每日任务专属：活跃度 + 任务组进度/分档领取（原日常页每日任务模块迁移）
        if ("daily_mission".equals(fn.id)) {
            LinearLayout actCard = UiStyle.card(this);
            actCard.setOrientation(LinearLayout.VERTICAL);
            actCard.setPadding(dp(16), dp(12), dp(16), dp(12));
            actTitle = new TextView(this);
            actTitle.setTextSize(13);
            actTitle.setTypeface(Typeface.DEFAULT_BOLD);
            actTitle.setTextColor(UiStyle.INK);
            actCard.addView(actTitle);
            actBar = new android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            actBar.setMax(325);
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable)
                    actBar.getProgressDrawable();
            ld.getDrawable(1).setColorFilter(UiStyle.BRAND,
                    android.graphics.PorterDuff.Mode.SRC_IN);
            actCard.addView(actBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
            actSub = new TextView(this);
            actSub.setTextSize(11);
            actSub.setTextColor(UiStyle.INK2);
            actSub.setPadding(0, dp(6), 0, 0);
            actCard.addView(actSub);
            boxRow = new LinearLayout(this);
            boxRow.setOrientation(LinearLayout.HORIZONTAL);
            boxRow.setPadding(0, dp(8), 0, 0);
            actCard.addView(boxRow);
            scrollBox.addView(actCard);
            TextView qTitle = new TextView(this);
            qTitle.setText("任务进度 · 点「待领取」行可手动领");
            qTitle.setTextSize(14);
            qTitle.setTypeface(Typeface.DEFAULT_BOLD);
            qTitle.setTextColor(UiStyle.INK);
            qTitle.setPadding(dp(20), dp(14), dp(16), dp(6));
            scrollBox.addView(qTitle);
            questBox = new LinearLayout(this);
            questBox.setOrientation(LinearLayout.VERTICAL);
            scrollBox.addView(questBox);
        }

        // ① 子项实时状态与配置（含 每子项实时状态行/✓今日/上次动作/开关/策略）
        TextView subTitle = new TextView(this);
        subTitle.setText("执行参数");
        subTitle.setTextSize(14);
        subTitle.setTypeface(Typeface.DEFAULT_BOLD);
        subTitle.setTextColor(UiStyle.INK);
        subTitle.setPadding(dp(20), dp(14), dp(16), dp(6));
        scrollBox.addView(subTitle);
        LinearLayout subCard = UiStyle.card(this);
        subCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        subCard.addView(SubCardUi.build(this, account.fpid, serverid, fn.id));
        scrollBox.addView(subCard);
        // 打开页面即刷新：借常驻会话收取该功能最新状态（只读，不执行任务动作），
        // 完成后原位重建子项卡（旧快照常停留在上次成功执行的时刻）。
        refreshOnOpen(subCard);

        // 军医所专属设置卡（2026-10-28 bug3）：每批治疗数量（heal_count）+ 治疗后联盟求助
        // （request_help）——服务端 MedicService 循环治疗按这两个 params 顶层键读取，键名不可改。
        if ("medic".equals(fn.id)) {
            scrollBox.addView(buildMedicConfigCard());
        }

        // 狩猎专属参数卡（子项卡片之后）：等级区间 + 出击次数，纯下拉/选项，无 JSON 输入。
        // 键与执行侧 FieldTasks.hunt 及服务器狩猎引擎一致（level_min/level_max/max_times），
        // 保存即写 FnParams 并同步服务器。
        if (HuntConfig.FN_ID.equals(fn.id)) {
            scrollBox.addView(buildHuntConfigCard());
        }

        // ② 领取内容（从运行记录提取成功领取条目）
        TextView claimTitle = new TextView(this);
        claimTitle.setText("执行明细");
        claimTitle.setTextSize(14);
        claimTitle.setTypeface(Typeface.DEFAULT_BOLD);
        claimTitle.setTextColor(UiStyle.INK);
        claimTitle.setPadding(dp(20), dp(16), dp(16), dp(6));
        scrollBox.addView(claimTitle);
        LinearLayout claimCard = UiStyle.card(this);
        claimCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        claimView = new TextView(this);
        claimView.setTextSize(12);
        claimCard.addView(claimView);
        scrollBox.addView(claimCard);

        // ③ 运行记录（功能级，最近50条）
        TextView logTitle = new TextView(this);
        logTitle.setText("最近执行");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(UiStyle.INK);
        logTitle.setPadding(dp(20), dp(16), dp(16), dp(6));
        scrollBox.addView(logTitle);
        LinearLayout logCard = UiStyle.card(this);
        logCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        logView = new TextView(this);
        logView.setTextSize(12);
        logCard.addView(logView);
        scrollBox.addView(logCard);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        refreshRecords();
        refreshQuestSection();
        refreshLoginGift();
        return root;
    }

    // ---------- 每日任务：活跃度 + 任务组（原 DailyActivity 迁移） ----------

    private void refreshQuestSection() {
        if (questBox == null) return;
        questBox.removeAllViews();
        if (boxRow != null) boxRow.removeAllViews();
        long sid = serverid;
        QuestStore.State st = QuestStore.load(this, account.fpid, sid);
        if (boxRow != null) {
            if (st.integral < 0) {
                actTitle.setText("活跃度：尚未同步（打开今日页自动同步，或执行一次）");
                actBar.setProgress(0);
                actSub.setText("档位：40/80/120/160/215/270/325");
            } else {
                actTitle.setText("活跃度：" + st.integral + " / 325");
                actBar.setProgress((int) Math.min(st.integral, 325));
                StringBuilder sub = new StringBuilder();
                sub.append("宝箱已达成 ").append(Math.max(0, st.boxes)).append("/7 档");
                if (st.integral >= 325) sub.append(" · 已满");
                else sub.append(" · 下一档 ").append(nextThreshold((int) st.integral));
                if (st.ts > 0) sub.append(" · 更新于 ").append(new java.text.SimpleDateFormat(
                        "MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(st.ts)));
                actSub.setText(sub.toString());
            }
            for (int t : DailyRunner.BOX_THRESHOLDS) {
                TextView chip = new TextView(this);
                chip.setText(String.valueOf(t));
                chip.setTextSize(10);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(4), dp(2), dp(4), dp(2));
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setCornerRadius(dp(9));
                boolean reached = st.integral >= t;
                gd.setColor(reached ? UiStyle.BRAND : UiStyle.IDLE_BG);
                chip.setTextColor(reached ? android.graphics.Color.WHITE : UiStyle.INK2);
                chip.setBackground(gd);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                boxRow.addView(chip, lp);
            }
        }
        if (st.ts == 0) {
            TextView tip = new TextView(this);
            tip.setText("任务进度尚未同步 —— 打开「今日」页会自动同步一次");
            tip.setTextSize(12);
            tip.setTextColor(UiStyle.INK2);
            tip.setPadding(dp(4), dp(8), dp(4), dp(8));
            questBox.addView(tip);
            return;
        }
        for (final QuestCatalog.Group g : QuestCatalog.all()) {
            long groupProg = 0;
            boolean allDone = true;
            int maxTarget = 1;
            QuestCatalog.Tier claimTier = null;
            for (QuestCatalog.Tier t : g.tiers) {
                Integer p = st.progress.get(t.questId);
                if (p != null && p > groupProg) groupProg = p;
                if (t.target > maxTarget) maxTarget = t.target;
                if (!st.claimed.contains(t.questId)) {
                    allDone = false;
                    if (groupProg >= t.target && claimTier == null) claimTier = t;
                }
            }
            final boolean fAllDone = allDone;
            final QuestCatalog.Tier fClaim = claimTier;
            final long fProg = groupProg;

            LinearLayout card = UiStyle.card(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(11), dp(14), dp(11));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(8);
            card.setLayoutParams(clp);

            LinearLayout l1 = new LinearLayout(this);
            l1.setOrientation(LinearLayout.HORIZONTAL);
            l1.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(g.name);
            name.setTextSize(13.5f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(UiStyle.INK);
            l1.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView status = new TextView(this);
            status.setTextSize(11);
            status.setPadding(dp(9), dp(3), dp(9), dp(3));
            android.graphics.drawable.GradientDrawable sgd = new android.graphics.drawable.GradientDrawable();
            sgd.setCornerRadius(dp(10));
            if (allDone) {
                status.setText("已完成");
                status.setTextColor(UiStyle.OK_INK);
                sgd.setColor(UiStyle.OK_BG);
            } else if (claimTier != null) {
                status.setText("待领取");
                status.setTextColor(UiStyle.WARN_INK);
                sgd.setColor(UiStyle.WARN_BG);
            } else if (groupProg > 0) {
                status.setText("进行中");
                status.setTextColor(UiStyle.BRAND);
                sgd.setColor(UiStyle.WAIT_50);
            } else {
                status.setText("未开始");
                status.setTextColor(UiStyle.INK2);
                sgd.setColor(UiStyle.IDLE_BG);
            }
            status.setBackground(sgd);
            l1.addView(status);
            card.addView(l1);

            UiStyle.press(card);
            card.setOnClickListener(v -> {
                if (fClaim != null) claimQuestManually(fClaim);
                else if (!fAllDone) toast("先完成对应玩法（或点上方「立即执行」自动领）");
            });

            LinearLayout l2 = new LinearLayout(this);
            l2.setOrientation(LinearLayout.HORIZONTAL);
            l2.setGravity(Gravity.CENTER_VERTICAL);
            l2.setPadding(0, dp(8), 0, 0);
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(fClaim != null ? fClaim.target : maxTarget);
            pb.setProgress((int) Math.min(fProg, fClaim != null ? fClaim.target : maxTarget));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(10), 1);
            plp.rightMargin = dp(10);
            l2.addView(pb, plp);
            TextView pt = new TextView(this);
            pt.setText(fProg + "/" + (fClaim != null ? fClaim.target : maxTarget));
            pt.setTextSize(12);
            pt.setTextColor(UiStyle.INK2);
            l2.addView(pt);
            card.addView(l2);

            LinearLayout l3 = new LinearLayout(this);
            l3.setOrientation(LinearLayout.HORIZONTAL);
            l3.setGravity(Gravity.CENTER_VERTICAL);
            l3.setPadding(0, dp(8), 0, 0);
            for (QuestCatalog.Tier t : g.tiers) {
                boolean done = st.claimed.contains(t.questId);
                boolean reach = !done && fProg >= t.target;
                TextView chip = new TextView(this);
                chip.setText(done ? "✓" + t.target : String.valueOf(t.target));
                chip.setTextSize(11);
                chip.setPadding(dp(8), dp(3), dp(8), dp(3));
                android.graphics.drawable.GradientDrawable cgd = new android.graphics.drawable.GradientDrawable();
                cgd.setCornerRadius(dp(10));
                if (done) {
                    chip.setTextColor(android.graphics.Color.WHITE);
                    cgd.setColor(UiStyle.BRAND);
                } else if (reach) {
                    chip.setTextColor(UiStyle.WARN_INK);
                    cgd.setColor(UiStyle.WARN_BG);
                } else {
                    chip.setTextColor(UiStyle.INK2);
                    cgd.setColor(UiStyle.IDLE_BG);
                }
                chip.setBackground(cgd);
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                qlp.rightMargin = dp(6);
                l3.addView(chip, qlp);
            }
            TextView pts = new TextView(this);
            pts.setText("活跃度+" + g.totalPoints());
            pts.setTextSize(11);
            pts.setTextColor(UiStyle.WARN_INK);
            l3.addView(pts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.addView(l3);
            questBox.addView(card);
        }
    }

    private static int nextThreshold(int act) {
        for (int t : DailyRunner.BOX_THRESHOLDS) {
            if (act < t) return t;
        }
        return 325;
    }

    // ---------- 登录好礼：十四天签到网格（游戏截图同款元素） ----------

    private LinearLayout giftGrid;
    private TextView giftBanner, giftClaimBtn;

    private void buildLoginGift(LinearLayout scrollBox) {
        // 顶部横幅（游戏同款："开启你的十四天独家好礼"）
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{UiStyle.BRAND_PRESS, UiStyle.BRAND});
        bg.setCornerRadius(dp(14));
        banner.setBackground(bg);
        giftBanner = new TextView(this);
        giftBanner.setTextSize(15);
        giftBanner.setTypeface(Typeface.DEFAULT_BOLD);
        giftBanner.setTextColor(android.graphics.Color.WHITE);
        giftBanner.setGravity(Gravity.CENTER);
        banner.addView(giftBanner);
        scrollBox.addView(banner);

        giftGrid = new LinearLayout(this);
        giftGrid.setOrientation(LinearLayout.VERTICAL);
        giftGrid.setPadding(0, dp(10), 0, 0);
        scrollBox.addView(giftGrid);

        giftClaimBtn = new TextView(this);
        giftClaimBtn.setTextSize(14);
        giftClaimBtn.setTypeface(Typeface.DEFAULT_BOLD);
        giftClaimBtn.setGravity(Gravity.CENTER);
        giftClaimBtn.setPadding(0, dp(12), 0, dp(12));
        scrollBox.addView(giftClaimBtn);
    }

    private void refreshLoginGift() {
        if (giftGrid == null) return;
        giftGrid.removeAllViews();
        org.json.JSONObject mod = FnParams.modState(this, account.fpid, serverid, "login_rewards");
        if (mod.optLong("sign_ts", 0) == 0) {
            giftBanner.setText("登录好礼 · 7 天双轨签到");
            TextView tip = new TextView(this);
            tip.setText("签到进度尚未同步 —— 打开「今日」页会自动同步一次");
            tip.setTextSize(12);
            tip.setTextColor(UiStyle.INK2);
            tip.setPadding(dp(4), dp(8), dp(4), dp(8));
            giftGrid.addView(tip);
            giftClaimBtn.setText("今日状态未知（先同步）");
            giftClaimBtn.setTextColor(UiStyle.INK2);
            return;
        }
        int signDays = (int) Math.min(7, mod.optLong("sign_days", 0));
        boolean hasPay = mod.optBoolean("has_pay", false);
        int round = mod.optInt("server_index", 5004001);
        java.util.Set<Long> freeRec = daySet(mod.optJSONArray("free_rec"));
        java.util.Set<Long> buyRec = daySet(mod.optJSONArray("buy_rec"));
        giftBanner.setText("登录好礼 · 7 天双轨签到\n已连签 " + mod.optLong("sign_days", 0)
                + " 天 · " + (hasPay ? "史诗通行证已激活" : "史诗轨需通行证（同 7 天，独立领奖）"));
        giftBanner.setLineSpacing(dp(3), 1f);

        String[][] free = RewardText.loginRewardsFree(round);
        String[][] epic = RewardText.loginRewardsEpic(round);
        boolean anyClaimable = false;
        LinearLayout row = null;
        for (int i = 0; i < 14; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                giftGrid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final boolean isEpic = i >= 7;
            int day = i % 7 + 1;
            String[] rewards = isEpic ? epic[day - 1] : free[day - 1];
            boolean claimed = isEpic ? buyRec.contains((long) day) : freeRec.contains((long) day);
            boolean claimable = !claimed && day == signDays + 1 && (!isEpic || hasPay);
            if (claimable) anyClaimable = true;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
            cg.setCornerRadius(dp(10));
            if (claimed) {
                cg.setColor(UiStyle.IDLE_BG);
            } else if (claimable) {
                cg.setColor(UiStyle.BRAND_50);
                cg.setStroke(dp(2), UiStyle.BRAND);
            } else {
                cg.setColor(UiStyle.SURFACE_2);
                cg.setStroke(dp(1), UiStyle.LINE);
            }
            cell.setBackground(cg);
            cell.setAlpha(claimed ? 0.62f : 1f);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER);
            TextView dayTag = new TextView(this);
            dayTag.setText((claimed ? "✓" : "") + "DAY " + day);
            dayTag.setTextSize(9.5f);
            dayTag.setTypeface(Typeface.DEFAULT_BOLD);
            dayTag.setTextColor(claimable ? UiStyle.BRAND
                    : (claimed ? UiStyle.INK3 : UiStyle.INK2));
            head.addView(dayTag);
            if (isEpic) {
                TextView ep = new TextView(this);
                ep.setText(" 史诗");
                ep.setTextSize(8.5f);
                ep.setTypeface(Typeface.DEFAULT_BOLD);
                ep.setTextColor(UiStyle.WARN_INK);
                head.addView(ep);
            }
            cell.addView(head);
            for (String r : rewards) {
                TextView rt = new TextView(this);
                rt.setText(r);
                rt.setTextSize(10);
                rt.setGravity(Gravity.CENTER);
                rt.setTextColor(claimed ? UiStyle.INK3 : UiStyle.INK);
                rt.setPadding(0, dp(2), 0, 0);
                cell.addView(rt);
            }
            if (claimable) {
                TextView mark = new TextView(this);
                mark.setText("可领取");
                mark.setTextSize(9.5f);
                mark.setTypeface(Typeface.DEFAULT_BOLD);
                mark.setTextColor(UiStyle.WARN_INK);
                mark.setPadding(0, dp(3), 0, 0);
                cell.addView(mark);
            }
            if (claimable) cell.setOnClickListener(v -> runOne());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 4 != 3) clp.rightMargin = dp(7);
            ((LinearLayout.LayoutParams) clp).bottomMargin = dp(7);
            row.addView(cell, clp);
        }
        if (anyClaimable) {
            giftClaimBtn.setText("领取今日好礼");
            giftClaimBtn.setTextColor(android.graphics.Color.WHITE);
            android.graphics.drawable.GradientDrawable bbg = new android.graphics.drawable.GradientDrawable();
            bbg.setColor(UiStyle.BRAND);
            bbg.setCornerRadius(dp(12));
            giftClaimBtn.setBackground(bbg);
            UiStyle.press(giftClaimBtn);
            giftClaimBtn.setOnClickListener(v -> runOne());
        } else {
            giftClaimBtn.setText("今日已领 · 明日再来");
            giftClaimBtn.setTextColor(UiStyle.INK2);
            android.graphics.drawable.GradientDrawable bbg = new android.graphics.drawable.GradientDrawable();
            bbg.setColor(UiStyle.IDLE_BG);
            bbg.setCornerRadius(dp(12));
            giftClaimBtn.setBackground(bbg);
            UiStyle.press(giftClaimBtn);
            giftClaimBtn.setOnClickListener(null);
        }
    }

    private static java.util.Set<Long> daySet(org.json.JSONArray arr) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(arr.optLong(i, -1));
        return out;
    }

    // ---------- 各奖励功能专属区（统一标准：横幅 + 可视化数据 + 行动按钮） ----------

    private void rebuildExtra() {
        if (extraBox == null) return;
        extraBox.removeAllViews();
        switch (fn.id) {
            case "mail": buildMailBoard(); break;
            case "supply": buildSupplyCard(); break;
            case "adventure": buildAdventureCard(); break;
            case "activity_rewards": buildActivityRewards(); break;
            case "commander_gift": buildCommanderGift(); break;
            case "act_endless": buildEndlessCard(); break;
        }
    }

    /** 蓝渐变主题横幅，返回副题 TextView（便于刷新）。 */
    private TextView themeBanner(String title, String sub) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{UiStyle.BRAND_PRESS, UiStyle.BRAND});
        bg.setCornerRadius(dp(14));
        banner.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(android.graphics.Color.WHITE);
        t.setGravity(Gravity.CENTER);
        banner.addView(t);
        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(11.5f);
        st.setTextColor(UiStyle.BRAND_100);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, dp(4), 0, 0);
        banner.addView(st);
        extraBox.addView(banner);
        return st;
    }

    private TextView actionButton(String text, boolean enabled) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(12), 0, dp(12));
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setCornerRadius(dp(12));
        if (enabled) {
            bgd.setColor(UiStyle.BRAND);
            b.setTextColor(android.graphics.Color.WHITE);
            UiStyle.press(b);
            b.setOnClickListener(v -> runOne());
        } else {
            bgd.setColor(UiStyle.IDLE_BG);
            b.setTextColor(UiStyle.INK2);
        }
        b.setBackground(bgd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    /** 从状态文本里解析时间（yyyy-MM-dd HH:mm:ss / HH:mm:ss）。 */
    private long parseTimeIn(String text) {
        if (text == null) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.CHINA);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matcher(text);
            if (m.find()) return f.parse(m.group()).getTime();
            java.text.SimpleDateFormat f2 = new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.CHINA);
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("[0-9]{2}:[0-9]{2}:[0-9]{2}").matcher(text);
            if (m2.find()) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                java.util.Calendar t = java.util.Calendar.getInstance();
                t.setTime(f2.parse(m2.group()));
                c.set(java.util.Calendar.HOUR_OF_DAY, t.get(java.util.Calendar.HOUR_OF_DAY));
                c.set(java.util.Calendar.MINUTE, t.get(java.util.Calendar.MINUTE));
                c.set(java.util.Calendar.SECOND, t.get(java.util.Calendar.SECOND));
                if (c.getTimeInMillis() < System.currentTimeMillis() - 3600_000L) c.add(java.util.Calendar.DATE, 1);
                return c.getTimeInMillis();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private String remainText(long ts) {
        if (ts <= 0) return "";
        long sec = (ts - System.currentTimeMillis()) / 1000L;
        if (sec <= 0) return "已可领";
        if (sec < 3600) return (sec / 60 + 1) + " 分钟后";
        if (sec < 86400) return sec / 3600 + " 小时" + (sec % 3600) / 60 + "分后";
        return sec / 86400 + " 天后";
    }

    /** 邮件板块：只做状态汇总（领取组合在下方子项配置用预设 chips 切换，避免双编辑面）。 */
    private void buildMailBoard() {
        String[] names = {"系统", "联盟", "战报", "消息", "收藏"};
        int[][] boardTypes = {
                {10}, {20, 100, 120, 170}, {30, 110, 130, 140, 160, 210},
                {40, 70, 80, 90, 150, 180, 190, 200, 220}, {50}};
        java.util.Set<Integer> cur = new java.util.HashSet<>();
        String strat = FnParams.strategy(this, account.fpid, serverid, "mail", "read", "all");
        if (strat.contains(",")) {
            for (String p2 : strat.split(",")) {
                try { cur.add(Integer.parseInt(p2.trim())); } catch (Throwable ignored) { }
            }
        } else if ("sys".equals(strat)) {
            cur.add(10);
        } else if ("sys_ally".equals(strat)) {
            cur.add(10); cur.add(20); cur.add(100); cur.add(120); cur.add(170);
        } else if ("no_battle".equals(strat)) {
            for (int t : new int[]{10, 20, 40, 50}) cur.add(t);
        } else {
            for (int[] bt : boardTypes) for (int t : bt) cur.add(t);
        }
        int onCnt = 0;
        for (int i = 0; i < names.length; i++) if (containsAny(cur, boardTypes[i])) onCnt++;
        themeBanner("邮件奖励 · 批量读信领附件",
                "已选 " + onCnt + "/5 板块 · 组合在下方子项配置切换");
    }

    private static boolean containsAny(java.util.Set<Integer> set, int[] types) {
        for (int t : types) if (set.contains(t)) return true;
        return false;
    }

    /** 仓库补给：下次可领倒计时 + 限时体力窗口。 */
    private void buildSupplyCard() {
        org.json.JSONObject mod = FnParams.modState(this, account.fpid, serverid, "supply");
        String online = mod.optString("online", "");
        String spec = mod.optString("spec", "");
        long ts = parseTimeIn(online);
        themeBanner("仓库补给", online.isEmpty() ? "普通+限时体力（打开今日页自动同步）"
                : "普通补给 " + (ts > 0 ? remainText(ts) : online));
        LinearLayout card = UiStyle.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        addKV(card, "普通补给", ts > 0 ? online + "（" + remainText(ts) + "）"
                : (online.isEmpty() ? "未同步" : online));
        addKV(card, "限时体力", spec.isEmpty() ? "窗口外（午间/晚间开启）" : spec);
        addKV(card, "说明", "刷新即领/截止前N分钟的策略在下方子项配置里选");
        extraBox.addView(card);
    }

    private void addKV(LinearLayout card, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextSize(12);
        kt.setTextColor(UiStyle.INK2);
        row.addView(kt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextSize(12);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        vt.setTextColor(UiStyle.INK);
        vt.setGravity(Gravity.RIGHT);
        row.addView(vt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.addView(row, lp);
    }

    /** 探险挂机：产量线性累积，用户自选领取时机。 */
    private void buildAdventureCard() {
        org.json.JSONObject mod = FnParams.modState(this, account.fpid, serverid, "adventure");
        String hang = mod.optString("hang_up", "");
        long ts = parseTimeIn(hang);
        String strat = FnParams.strategy(this, account.fpid, serverid,
                "adventure", "hang_up", "0");
        String stratName = "总是领取";
        if ("300".equals(strat)) stratName = "攒够 5 分钟才领";
        else if ("600".equals(strat)) stratName = "攒够 10 分钟才领";
        else if ("1800".equals(strat)) stratName = "攒够 30 分钟才领";
        themeBanner("探险挂机 · 产量随时间线性累积",
                hang.isEmpty() ? "约 10 分钟一领" : (ts > 0 ? "满格倒计时 " + remainText(ts) : hang));
        LinearLayout card = UiStyle.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView big = new TextView(this);
        big.setText(hang.isEmpty() ? "--" : (ts > 0 ? remainText(ts) : "已可领"));
        big.setTextSize(26);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(UiStyle.BRAND);
        big.setGravity(Gravity.CENTER);
        card.addView(big);
        TextView cap = new TextView(this);
        cap.setText("距产量满格（可领）");
        cap.setTextSize(11);
        cap.setTextColor(UiStyle.INK2);
        cap.setGravity(Gravity.CENTER);
        card.addView(cap);
        addKV(card, "当前策略", stratName + "（在下方子项配置切换）");
        addKV(card, "说明", "不想攒的话，把子项策略切到「总是领取」，点上方「立即执行」马上领");
        extraBox.addView(card);
    }

    /** 活动奖励合集：状态汇总（要领哪些在下方子项配置勾选，避免与子项开关双编辑面）。 */
    private void buildActivityRewards() {
        java.util.List<FnDetail.Sub> subs = FnDetail.of(fn.id);
        int onCnt = 0;
        for (FnDetail.Sub s : subs) {
            if (FnParams.subOn(this, account.fpid, serverid, fn.id, s.id, s.defOn)) onCnt++;
        }
        themeBanner("活动奖励合集 · 免费奖励一键全领",
                "已启用 " + onCnt + "/" + subs.size() + " 项 · 在下方子项配置勾选");
    }

    /** 统帅礼包：每日一份。 */
    private void buildCommanderGift() {
        int st = logStore.status(account.fpid, serverid, fn.id);
        boolean done = st == LogStore.ST_DONE;
        themeBanner("统帅礼包 · 每日一份免费", done ? "今日已领取 ✓" : "今日尚未领取");
        LinearLayout card = UiStyle.card(this);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = new TextView(this);
        t.setText(done ? "✓ 今日已领取，明日刷新" : "今日的免费统帅礼包还没领，点上方「立即执行」领取");
        t.setTextSize(13);
        t.setTextColor(done ? UiStyle.OK_INK : UiStyle.WARN_INK);
        card.addView(t);
        extraBox.addView(card);
    }

    /** 无尽征程：积分里程碑。 */
    private void buildEndlessCard() {
        org.json.JSONObject mod = FnParams.modState(this, account.fpid, serverid, "act_endless");
        String ms = mod.optString("milestone", "");
        long integral = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]+").matcher(ms);
        if (m.find()) {
            try { integral = Long.parseLong(m.group()); } catch (Throwable ignored) { }
        }
        themeBanner("无尽征程 · 里程碑积分", ms.isEmpty() ? "积分未同步（打开今日页同步）"
                : "当前积分 " + integral);
        LinearLayout card = UiStyle.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView big = new TextView(this);
        big.setText(String.valueOf(integral > 0 ? integral : "--"));
        big.setTextSize(30);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(UiStyle.BRAND);
        big.setGravity(Gravity.CENTER);
        card.addView(big);
        TextView cap = new TextView(this);
        cap.setText("当前积分（活动期间任务/玩法累积）");
        cap.setTextSize(11);
        cap.setTextColor(UiStyle.INK2);
        cap.setGravity(Gravity.CENTER);
        card.addView(cap);
        extraBox.addView(card);
    }

    private void claimQuestManually(final QuestCatalog.Tier tier) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("领取任务奖励…");
        pd.setCancelable(false);
        pd.show();
        final AccountStore.Account acc = account;
        final long sid = serverid;
        new AsyncTask<Void, Void, DailyResult>() {
            @Override
            protected DailyResult doInBackground(Void... p) {
                return DailyRunner.claimQuest(FunctionActivity.this, acc.fpid,
                        acc.token, sid, tier.questId);
            }

            @Override
            protected void onPostExecute(DailyResult r) {
                try { pd.dismiss(); } catch (Throwable ignored) { }
                refreshQuestSection();
                refreshRecords();
                toast(r.symbol() + " " + r.message);
            }
        }.execute();
    }

    /** 刷新领取内容 + 运行记录两区。 */
    private void refreshRecords() {
        java.text.SimpleDateFormat f1 = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.CHINA);
        StringBuilder claims = new StringBuilder();
        StringBuilder logsSb = new StringBuilder();
        List<LogStore.Entry> es = logStore.logs(account.fpid, serverid, fn.id);
        int claimCnt = 0;
        for (LogStore.Entry e : es) {
            // 只收领取成功条目：新条目看 category，旧条目（无 category 字段）按 DailyResult 符号兜底
            boolean claimed = "CLAIMED".equals(e.category)
                    || (e.category.isEmpty() && e.reason.startsWith("✅"));
            if (!e.ok || !claimed) continue;
            // 展示"领到了什么"（物品明细优先），不再把时间点当内容罗列
            String line = RewardText.claimLine(e.reason, e.detail);
            if (line.isEmpty()) continue;
            claims.append("· ").append(line.replace("\n", "\n  ")).append('\n');
            if (++claimCnt >= 20) break;
        }
        claimView.setText(claimCnt == 0 ? "暂无领取记录" : claims.toString().trim());
        claimView.setTextColor(claimCnt == 0 ? UiStyle.INK2 : UiStyle.INK);
        for (LogStore.Entry e : es) {
            logsSb.append(f1.format(new java.util.Date(e.ts))).append("  ")
                    .append(e.ok ? "✅" : "❌").append(e.reason.isEmpty() ? "" : " " + e.reason);
            // 扩展字段（旧条目为零值，自然省略）：耗时 · 结果分级 · 触发来源
            if (e.durMs > 0) {
                logsSb.append(" · ").append(e.durMs >= 1000
                        ? String.format(java.util.Locale.CHINA, "%.1fs", e.durMs / 1000.0)
                        : e.durMs + "ms");
            }
            if (!e.category.isEmpty()) logsSb.append(" · ").append(e.category);
            if (!e.trigger.isEmpty()) logsSb.append(" · ").append(e.trigger);
            logsSb.append('\n');
        }
        logView.setText(logsSb.length() == 0
                ? "暂无运行记录\n· 点上方「立即执行」马上生成\n· 开了自动日常的话，定时执行后也会记录在这里"
                : logsSb.toString().trim());
        logView.setTextColor(UiStyle.INK);
    }

    private void runOne() {
        // 服务器模式：执行提交给 vault-server（本地不 fresh 登录，防互踢）；结果经 fetchState 回写后在记录区查看
        if (com.carlos.vault.VaultSync.isServerMode(this) && account != null
                && account.fpid != null && !account.fpid.isEmpty()) {
            com.carlos.vault.VaultSync.triggerAutomation(this, account.fpid, fn.id,
                    (code, message, data) -> runOnUiThread(() ->
                            toast(code == 0 && data != null && data.optBoolean("accepted", false)
                                    ? "已提交服务器执行「" + fn.name + "」\n稍后重进本页查看结果"
                                    : "服务器未受理：" + message)));
            // 已回传的状态先渲染一次（本轮结果要等服务器跑完，重进页面再取）
            com.carlos.vault.VaultSync.fetchState(this, (code, message, data) ->
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || code != 0) return;
                            setContentView(buildUi());
                        } catch (Throwable ignored) {
                        }
                    }));
            return;
        }
        DailyRunner.SOURCE = "手动";   // 与 HomeActivity 一键跑全部同款：本次执行产生的日志/状态行都标"手动"
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("执行「" + fn.name + "」…");
        pd.setCancelable(false);
        pd.show();
        final AccountStore.Account acc = account;
        final long sid = serverid;
        new AsyncTask<Void, Void, DailyResult>() {
            @Override
            protected DailyResult doInBackground(Void... p) {
                return DailyRunner.runSingle(FunctionActivity.this, acc.fpid, acc.token, sid, fn.id);
            }

            @Override
            protected void onPostExecute(DailyResult r) {
                try { pd.dismiss(); } catch (Throwable ignored) { }
                logStore.append(acc.fpid, sid, fn.id,
                        r.category == DailyResult.Category.CLAIMED, r.toString(),
                        r.rewards == null ? "" : r.rewards,
                        "手动", r.durationMs, r.category.name(), r.attempt);
                logStore.setStatus(acc.fpid, sid, fn.id,
                        r.category == DailyResult.Category.CLAIMED ? LogStore.ST_DONE
                                : r.category == DailyResult.Category.SKIPPED
                                ? LogStore.ST_SKIPPED : LogStore.ST_FAILED);
                logStore.applySubResults(acc.fpid, sid, fn.id, r.subResults);
                refreshRecords();
                refreshQuestSection();
                refreshLoginGift();
                if (extraBox != null) rebuildExtra();
                new AlertDialog.Builder(FunctionActivity.this)
                        .setTitle(r.taskName)
                        .setMessage(r.symbol() + " " + r.message)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        }.execute();
    }

    /**
     * 旧版采集参数弹窗（兼容/回滚）：从「采集设置」页底部「旧版参数（兼容）」进入。
     *
     * <p>只写旧版键（squad_index/resources_named/min_level/max_queues/strategy.send），
     * 不触碰 v2（新版 6 队列配置）。旧服务端 jar 忽略 v2、只读这些旧键，因此该入口
     * 可用于「新 App + 旧 server」兼容与线上回滚；新版配置仍以设置页为准。
     *
     * @param onSaved 保存成功回调（可空；设置页用它重新读取参数并刷新预览）
     */
    public static void showLegacyGatherDialog(final Activity act, final String fpid,
                                              final long sid, final Runnable onSaved) {
        if (act == null || fpid == null) return;
        JSONObject p = FnParams.params(act, fpid, sid, "gather");

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiStyle.dp(act, 18), UiStyle.dp(act, 8), UiStyle.dp(act, 18), UiStyle.dp(act, 8));

        TextView legacyTip = new TextView(act);
        legacyTip.setText("旧版参数（兼容旧服务端/回滚）：只改下方旧版键，不影响新版 6 队列配置");
        legacyTip.setTextSize(11);
        legacyTip.setTextColor(UiStyle.WARN_INK);
        legacyTip.setPadding(0, 0, 0, UiStyle.dp(act, 6));
        box.addView(legacyTip);

        TextView squadLabel = new TextView(act);
        squadLabel.setText("游戏内存编队（旧，兼容；新编队见页面顶部「编队」）");
        squadLabel.setTextSize(13);
        squadLabel.setTypeface(Typeface.DEFAULT_BOLD);
        squadLabel.setTextColor(UiStyle.INK);
        box.addView(squadLabel);

        final RadioGroup squadGroup = new RadioGroup(act);
        squadGroup.setOrientation(RadioGroup.VERTICAL);
        String[] squadNames = {"自动（沿用游戏当前编队）", "第1队", "第2队", "第3队", "第4队", "第5队"};
        for (int i = 0; i < squadNames.length; i++) {
            RadioButton rb = new RadioButton(act);
            // 0 是合法 id 但不是合法的选中态标记，统一用 100+i 再换算回队号。
            rb.setId(100 + i);
            rb.setText(squadNames[i]);
            rb.setTextSize(13);
            rb.setTextColor(UiStyle.INK);
            squadGroup.addView(rb);
        }
        int curSquad = Math.max(0, Math.min(5, p.optInt("squad_index", 0)));
        squadGroup.check(100 + curSquad);
        box.addView(squadGroup);

        TextView resLabel = new TextView(act);
        resLabel.setText("采集矿种");
        resLabel.setTextSize(13);
        resLabel.setTypeface(Typeface.DEFAULT_BOLD);
        resLabel.setTextColor(UiStyle.INK);
        resLabel.setPadding(0, UiStyle.dp(act, 10), 0, 0);
        box.addView(resLabel);

        String[] resNames = {"肉", "木", "煤", "铁"};
        String[] resKeys = {"MEAT", "WOOD", "COAL", "IRON"};
        org.json.JSONArray curRes = p.optJSONArray("resources_named");
        final CheckBox[] resBoxes = new CheckBox[4];
        for (int i = 0; i < 4; i++) {
            CheckBox cb = new CheckBox(act);
            cb.setText(resNames[i]);
            cb.setTextSize(13);
            cb.setTextColor(UiStyle.INK);
            boolean on = curRes == null || curRes.length() == 0;
            if (!on) {
                for (int j = 0; j < curRes.length(); j++) {
                    if (resKeys[i].equalsIgnoreCase(curRes.optString(j, ""))) { on = true; break; }
                }
            }
            cb.setChecked(on);
            resBoxes[i] = cb;
            box.addView(cb);
        }

        LinearLayout levelRow = new LinearLayout(act);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        levelRow.setGravity(Gravity.CENTER_VERTICAL);
        levelRow.setPadding(0, UiStyle.dp(act, 10), 0, 0);
        TextView levelLabel = new TextView(act);
        levelLabel.setText("矿点等级");
        levelLabel.setTextSize(13);
        levelLabel.setTypeface(Typeface.DEFAULT_BOLD);
        levelLabel.setTextColor(UiStyle.INK);
        levelRow.addView(levelLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final NumberPicker levelPick = new NumberPicker(act);
        levelPick.setMinValue(1);
        levelPick.setMaxValue(6);
        levelPick.setWrapSelectorWheel(false);
        levelPick.setValue(Math.max(1, Math.min(6, p.optInt("min_level", 5))));
        levelRow.addView(levelPick, new LinearLayout.LayoutParams(UiStyle.dp(act, 80), UiStyle.dp(act, 90)));
        box.addView(levelRow);

        LinearLayout queueRow = new LinearLayout(act);
        queueRow.setOrientation(LinearLayout.HORIZONTAL);
        queueRow.setGravity(Gravity.CENTER_VERTICAL);
        queueRow.setPadding(0, UiStyle.dp(act, 6), 0, 0);
        TextView queueLabel = new TextView(act);
        queueLabel.setText("队列数");
        queueLabel.setTextSize(13);
        queueLabel.setTypeface(Typeface.DEFAULT_BOLD);
        queueLabel.setTextColor(UiStyle.INK);
        queueRow.addView(queueLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final NumberPicker queuePick = new NumberPicker(act);
        queuePick.setMinValue(1);
        queuePick.setMaxValue(5);
        queuePick.setWrapSelectorWheel(false);
        queuePick.setValue(Math.max(1, Math.min(5, p.optInt("max_queues", 3))));
        queueRow.addView(queuePick, new LinearLayout.LayoutParams(UiStyle.dp(act, 80), UiStyle.dp(act, 90)));
        box.addView(queueRow);

        final CheckBox fullBox = new CheckBox(act);
        fullBox.setText("只采满矿");
        fullBox.setTextSize(13);
        fullBox.setTextColor(UiStyle.INK);
        fullBox.setChecked("full".equals(p.optJSONObject("strategy") == null
                ? "any" : p.optJSONObject("strategy").optString("send", "any")));
        box.addView(fullBox);

        new AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("采集参数（旧版）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        JSONObject next = FnParams.params(act, fpid, sid, "gather");
                        next.put("squad_index",
                                Math.max(0, squadGroup.getCheckedRadioButtonId() - 100));
                        org.json.JSONArray names = new org.json.JSONArray();
                        for (int i = 0; i < 4; i++) {
                            if (resBoxes[i].isChecked()) names.put(resKeys[i]);
                        }
                        next.put("resources_named", names);
                        next.put("min_level", levelPick.getValue());
                        next.put("max_queues", queuePick.getValue());
                        JSONObject strategy = next.optJSONObject("strategy");
                        if (strategy == null) strategy = new JSONObject();
                        strategy.put("send", fullBox.isChecked() ? "full" : "any");
                        next.put("strategy", strategy);
                        // 采集改为编队选择后，清掉旧的 JSON 高级参数，避免两套配置互相覆盖。
                        next.remove("hero_ids");
                        next.remove("troop_count");
                        next.remove("target_amount");
                        next.remove("carry_per_troop");
                        FnParams.saveParams(act, fpid, sid, "gather", next);
                        VaultSync.onAccountsChanged(act);
                        UiStyle.toast(act, "旧版采集参数已保存并同步服务器（兼容旧服务端）");
                        if (onSaved != null) onSaved.run();
                    } catch (Throwable t) {
                        UiStyle.toast(act, "保存失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 守护盟友（批次11：UI 放开 + 配置页） =================
    //
    // 与服务器对齐（ServerGuardianEngine.parseConfig）：roleParams `guardian` 键
    //   enabled(默认false) dry_run(默认true=演练) ally_uid target_uid target_point{x,y}
    //   d_sec(默认38) recall(默认true)；编队字段复用页顶「统一编队」卡
    //   （formation_mode/formation_id/soldiers/ratio/total）。
    // 状态只读自 state：mod:{fpid}:{sid}:guardian 与 srv:guardian:{fpid}:{sid}:ledger。

    /** 只读状态卡：生效配置 + mod 最近状态 + 服务端账本统计。 */
    private LinearLayout buildGuardianStatusCard() {
        LinearLayout card = UiStyle.card(this);
        TextView title = new TextView(this);
        title.setText("守护盟友 · 状态");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        card.addView(title);

        FnParams.GuardianCfg cfg = FnParams.loadGuardianCfg(this, account.fpid, serverid);
        org.json.JSONObject mod = FnParams.modState(this, account.fpid, serverid,
                FnParams.FN_GUARDIAN);
        FnParams.GuardianLedger stat = FnParams.guardianLedger(
                FnParams.loadGuardianLedger(this, account.fpid, serverid));

        addKV(card, "配置", (cfg.enabled ? "已启用" : "未启用")
                + " · " + (cfg.dryRun ? "演练模式（只记录不派兵）" : "实发模式"));
        addKV(card, "触发策略", "强制上线=" + (cfg.forceOnline ? "开" : "关")
                + " · 来袭兵力下限=" + (cfg.troopsMin > 0 ? String.valueOf(cfg.troopsMin) : "不限"));
        addKV(card, "集结威胁阈值", cfg.rallyTroopsMin > 0
                ? ("兵力 > " + cfg.rallyTroopsMin + " 视为威胁集结")
                : "不限（0=不按兵力判定）");
        addKV(card, "钻石加速", cfg.diamondSpeedup
                ? "允许（真实花钻；先用道具，无道具才买）" : "关闭（零钻石，默认）");
        if (!cfg.enabled) addKV(card, "提示", "未启用：服务器不处理敌情（等待配置）");
        String last = mod.optString("last", "");
        long lastTs = mod.optLong("last_ts", 0);
        if (!last.isEmpty()) {
            addKV(card, "最近状态", last + (lastTs > 0 ? " · " + new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(lastTs)) : ""));
        }
        addKV(card, "服务端账本", stat.total > 0 ? stat.text()
                : "暂无记录（未启用或尚未命中敌情）");
        TextView cap = new TextView(this);
        cap.setText("账本由服务器守护引擎写入 · 最近 50 条 · 诊断用");
        cap.setTextSize(10.5f);
        cap.setTextColor(UiStyle.INK3);
        cap.setPadding(0, dp(6), 0, 0);
        card.addView(cap);
        return card;
    }

    /** 配置卡：总开关/演练-实发/盟友与目标/提前量/撤退；保存写 params 并同步。 */
    private LinearLayout buildGuardianConfigCard() {
        final FnParams.GuardianCfg cfg = FnParams.loadGuardianCfg(this, account.fpid, serverid);
        LinearLayout card = UiStyle.card(this);
        TextView title = new TextView(this);
        title.setText("守护盟友 · 配置");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        card.addView(title);

        // ① 总开关（enabled，默认 false；即时写 params+同步）
        final UiStyle.Switch[] enRef = new UiStyle.Switch[1];
        enRef[0] = new UiStyle.Switch(this, cfg.enabled, () -> {
            FnParams.GuardianCfg c = FnParams.loadGuardianCfg(this, account.fpid, serverid);
            c.enabled = enRef[0].isOn();
            FnParams.saveGuardianCfg(this, account.fpid, serverid, c);
            com.carlos.vault.VaultSync.onAccountsChanged(this);
            toast(c.enabled ? "守护盟友已启用（服务器守护引擎接管）" : "守护盟友已关闭");
        });
        card.addView(switchRow("总开关", "关闭时服务器不处理敌情（默认关）", enRef[0]));

        // ② 演练/实发（dry_run 默认 true；切实发需二次确认）
        TextView modeLabel = new TextView(this);
        modeLabel.setText("模式");
        modeLabel.setTextSize(12.5f);
        modeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        modeLabel.setTextColor(UiStyle.INK);
        modeLabel.setPadding(0, dp(10), 0, dp(4));
        card.addView(modeLabel);
        card.addView(UiStyle.segmented(this, new String[]{"演练模式", "实发模式"},
                cfg.dryRun ? 0 : 1, idx -> {
                    if (idx == 0) {
                        setGuardianDryRun(true);
                        setContentView(buildUi());
                        toast("已切换演练模式（只记录不派兵）");
                    } else {
                        UiStyle.showConfirmDialog(this, "切换实发模式？",
                                "实发模式命中敌情后会真实派兵驻防：会占用行军队列/兵力，"
                                        + "可能触发战斗且撤回有延迟。\n\n确认切换为实发模式？",
                                "确认实发", UiStyle.ERR_INK, () -> {
                                    setGuardianDryRun(false);
                                    setContentView(buildUi());
                                    toast("已切换实发模式，请确认总开关与盟友/目标已配置");
                                });
                    }
                }));
        TextView modeTip = new TextView(this);
        modeTip.setText(cfg.dryRun
                ? "演练模式：只记录不发兵（默认；建议先看账本再切实发）"
                : "实发模式：命中后自动派兵驻防（谨慎）");
        modeTip.setTextSize(10.5f);
        modeTip.setTextColor(cfg.dryRun ? UiStyle.INK2 : UiStyle.WARN_INK);
        modeTip.setPadding(0, dp(4), 0, 0);
        card.addView(modeTip);

        // ③ 盟友 / 目标 uid / 目标坐标
        final EditText allyEt = numField("盟友 uid（必填才能命中）", cfg.allyUid);
        card.addView(fieldRow("盟友 uid", allyEt));
        final EditText targetEt = numField("目标 uid（可选，精确过滤）", cfg.targetUid);
        card.addView(fieldRow("目标 uid（可选）", targetEt));
        LinearLayout xy = new LinearLayout(this);
        xy.setOrientation(LinearLayout.HORIZONTAL);
        xy.setGravity(Gravity.CENTER_VERTICAL);
        final EditText xEt = numField("x", cfg.targetX);
        final EditText yEt = numField("y", cfg.targetY);
        xy.addView(fieldRow("目标坐标 x（可选）", xEt), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        xy.addView(fieldRow("y", yEt), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(xy);

        // ④ 提前量 / 撤退
        final EditText dEt = numField("提前量秒数（默认 38）", cfg.dSec);
        card.addView(fieldRow("提前量 d_sec", dEt));
        final UiStyle.Switch[] recallRef = new UiStyle.Switch[1];
        recallRef[0] = new UiStyle.Switch(this, cfg.recall, () -> { });
        card.addView(switchRow("敌人召回时撤防",
                "recall=true：敌撤回后撤走我方驻防（默认开）", recallRef[0]));

        // ④.5 触发策略（2026-10-24）：强制上线 + 来袭兵力预警阈值，均为独立可启用参数
        final UiStyle.Switch[] forceRef = new UiStyle.Switch[1];
        forceRef[0] = new UiStyle.Switch(this, cfg.forceOnline, () -> { });
        card.addView(switchRow("风险时强制上线",
                "盟友受攻击且兵力达阈值时，暂停/被顶号/掉线也允许抢登回连后派兵驻防（默认关；会顶掉其他设备）",
                forceRef[0]));
        final EditText troopsEt = numField("敌方来袭兵力下限（0=不限）", cfg.troopsMin);
        card.addView(fieldRow("来袭兵力预警阈值", troopsEt));
        TextView troopsTip = new TextView(this);
        troopsTip.setText("敌方行军兵力（squad_list.soldier 合计）低于该值不派兵；"
                + "推送不含兵力时按保守策略不发射。0=不限。");
        troopsTip.setTextSize(10.5f);
        troopsTip.setTextColor(UiStyle.INK2);
        troopsTip.setPadding(0, dp(2), 0, 0);
        card.addView(troopsTip);

        // ④.6 批次 K：集结兵力阈值（> 该兵力视为威胁集结并驻防）
        final EditText rallyEt = numField("集结兵力阈值（默认 300000）", cfg.rallyTroopsMin);
        card.addView(fieldRow("集结兵力阈值", rallyEt));
        TextView rallyTip = new TextView(this);
        rallyTip.setText("> 该兵力视为威胁集结并驻防（整数，0~10000000；默认 300000）。"
                + "仅集结类威胁按此阈值判定，普通来袭仍按上面的兵力下限。");
        rallyTip.setTextSize(10.5f);
        rallyTip.setTextColor(UiStyle.INK2);
        rallyTip.setPadding(0, dp(2), 0, 0);
        card.addView(rallyTip);

        // ④.7 批次 K：允许钻石加速（真实花钻；默认关，与 speedup_enabled 独立）
        final UiStyle.Switch[] diamondRef = new UiStyle.Switch[1];
        diamondRef[0] = new UiStyle.Switch(this, cfg.diamondSpeedup, () -> { });
        card.addView(switchRow("允许钻石加速（真实花钻）",
                "−25%≈400 钻/次 · −50%≈2000 钻/次；先用道具，没道具才买道具再加速"
                        + "（1×50%≈2×25%，优先 25%）；关闭时零钻石消耗（默认关）",
                diamondRef[0]));
        TextView diamondTip = new TextView(this);
        diamondTip.setText("道具库存/本场已花钻石见上方「守护盟友 · 状态」卡（服务器账本统计，最近 50 条）。"
                + "服务端 env 硬关（VAULT_GUARDIAN_DIAMOND=0）时本开关不生效。");
        diamondTip.setTextSize(10.5f);
        diamondTip.setTextColor(UiStyle.INK2);
        diamondTip.setPadding(0, dp(2), 0, 0);
        card.addView(diamondTip);

        // ⑤ 保存（写 params.guardian；保留统一编队字段）
        TextView save = UiStyle.primaryBtn(this, "保存守护配置", () -> {
            String rallyErr = FnParams.validateRallyTroopsMin(rallyEt.getText() == null
                    ? "" : rallyEt.getText().toString());
            if (rallyErr != null) {
                toast(rallyErr);
                return;
            }
            FnParams.GuardianCfg next = FnParams.loadGuardianCfg(this, account.fpid, serverid);
            next.enabled = enRef[0].isOn();
            next.diamondSpeedup = diamondRef[0].isOn();
            next.rallyTroopsMin = FnParams.rallyTroopsMinOf(rallyEt.getText() == null
                    ? "" : rallyEt.getText().toString());
            next.recall = recallRef[0].isOn();
            next.forceOnline = forceRef[0].isOn();
            next.troopsMin = numOf(troopsEt);
            next.allyUid = numOf(allyEt);
            next.targetUid = numOf(targetEt);
            next.targetX = (int) Math.min(Integer.MAX_VALUE, numOf(xEt));
            next.targetY = (int) Math.min(Integer.MAX_VALUE, numOf(yEt));
            long d = numOf(dEt);
            next.dSec = (d >= 10 && d <= 600) ? (int) d : FnParams.GUARDIAN_DEFAULT_D_SEC;
            FnParams.saveGuardianCfg(this, account.fpid, serverid, next);
            com.carlos.vault.VaultSync.onAccountsChanged(this);
            toast("守护配置已保存并同步服务器");
            setContentView(buildUi());
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(12);
        card.addView(save, slp);
        return card;
    }

    /** 立即持久化演练/实发（dry_run）。 */
    private void setGuardianDryRun(boolean dryRun) {
        FnParams.GuardianCfg c = FnParams.loadGuardianCfg(this, account.fpid, serverid);
        c.dryRun = dryRun;
        FnParams.saveGuardianCfg(this, account.fpid, serverid, c);
        com.carlos.vault.VaultSync.onAccountsChanged(this);
    }

    /** 行：标题/副题 + 右侧控件。 */
    private LinearLayout switchRow(String title, String sub, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(10.5f);
        s.setTextColor(UiStyle.INK2);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(right);
        return row;
    }

    /** 行：标题 + 数字输入框。 */
    private LinearLayout fieldRow(String title, EditText et) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), dp(6), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11.5f);
        t.setTextColor(UiStyle.INK2);
        row.addView(t);
        row.addView(et);
        return row;
    }

    /** 数字输入框（空=0）。 */
    private EditText numField(String hint, long value) {
        EditText et = UiStyle.searchInput(this, hint);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(value > 0 ? String.valueOf(value) : "");
        return et;
    }

    /** 数字输入框读数（空/非法=0）。 */
    private long numOf(EditText et) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) return 0;
            return Math.max(0, Long.parseLong(s));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ================= 军医所设置（medic，2026-10-28 bug3） =================
    //
    // 服务端 MedicService.heal(tc, maxCount, immediate, requestHelp) 从角色 params 的
    // medic 顶层键读取（键名契约锁定，不可改名/嵌套到 sub）：
    //   heal_count   每批最多治疗数量（默认 400；范围 1-10000；服务端 0=全部，App 始终下发正数）
    //   request_help 治疗成功后是否向联盟求助 req_queue_ask_for_help（默认 true）
    //   immediate    保持原状，本卡不改
    // 「自动治疗」(medic.sub.heal) 与「治疗后联盟求助」(medic 子项 help → 写顶层
    // request_help，SubCardUi 特殊处理) 两个子开关统一在执行参数卡里切换；本卡只做数字
    // 输入，避免同一设置出现两个编辑面导致状态互相覆盖。

    /** 医院设置卡：每批治疗数量（数字输入）+ 说明（两个子开关在执行参数卡里）。 */
    private LinearLayout buildMedicConfigCard() {
        final JSONObject p = FnParams.params(this, account.fpid, serverid, "medic");
        final int heal = FnParams.medicHealCount(p);
        // 只展示默认值：打开页面不修改任何参数（服务端对缺省 heal_count 的默认值由服务端
        // 侧统一为 400，App 不静默写配置；仅用户改动/点保存时才落库下发）。

        LinearLayout card = UiStyle.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("医院设置");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        card.addView(title);

        medicSummaryView = new TextView(this);
        medicSummaryView.setText(medicSummaryText(heal));
        medicSummaryView.setTextSize(12);
        medicSummaryView.setTypeface(Typeface.DEFAULT_BOLD);
        medicSummaryView.setTextColor(UiStyle.BRAND_TEXT);
        medicSummaryView.setPadding(0, dp(4), 0, dp(2));
        card.addView(medicSummaryView);

        TextView tip = new TextView(this);
        tip.setText("按每批数量治疗伤兵（1-10000，默认 400）；循环治疗直到伤兵清零或资源不足。"
                + "「治疗后联盟求助」在上方「执行参数」里切换（默认开）。");
        tip.setTextSize(10.5f);
        tip.setTextColor(UiStyle.INK2);
        tip.setPadding(0, 0, 0, dp(6));
        card.addView(tip);

        // ① 每批治疗数量：规范数字输入框（失焦/退出页面保存并下发；非法回退 400）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(2));
        TextView label = new TextView(this);
        label.setText("每批治疗数量");
        label.setTextSize(12.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(UiStyle.INK);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        medicHealEt = UiStyle.searchInput(this, "1-10000");
        medicHealEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        medicHealEt.setSingleLine(true);
        medicHealEt.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        medicHealEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        medicHealEt.setText(String.valueOf(heal));
        medicHealEt.setSelection(medicHealEt.getText().length());
        medicHealEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                medicHealDirty = true;
            }
        });
        // 保留 UiStyle.searchInput 自带的聚焦描边逻辑（自定义 listener 会覆盖它），再追加保存
        final View.OnFocusChangeListener baseFocus = medicHealEt.getOnFocusChangeListener();
        medicHealEt.setOnFocusChangeListener((v, hasFocus) -> {
            if (baseFocus != null) baseFocus.onFocusChange(v, hasFocus);
            if (!hasFocus) saveMedicHealIfNeeded();
        });
        medicHealEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveMedicHealCount(true);
                hideKeyboard(v);
                v.clearFocus();
                return true;
            }
            return false;
        });
        row.addView(medicHealEt, new LinearLayout.LayoutParams(dp(120), dp(44)));
        card.addView(row);

        // ② 保存按钮（失焦/退出页面会自动保存，这里给显式入口）
        TextView save = UiStyle.primaryBtn(this, "保存医院设置", () -> {
            saveMedicHealCount(true);
            hideKeyboard(medicHealEt);
            if (medicHealEt != null) medicHealEt.clearFocus();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(10);
        card.addView(save, slp);
        return card;
    }

    /** 失焦/退出页面时保存输入框：仅用户改过（medicHealDirty）才写盘，打开页面不写配置。 */
    private void saveMedicHealIfNeeded() {
        if (!medicHealDirty || medicHealEt == null || account == null) return;
        saveMedicHealCount(false);
    }

    /** 规范输入框值并写入 medic.heal_count（putTopLevel 内部触发 notifySettingsChanged 下发）。 */
    private void saveMedicHealCount(boolean showToast) {
        if (medicHealEt == null || account == null) return;
        String typed = medicHealEt.getText().toString().trim();
        int v = FnParams.parseMedicHealCount(typed);
        int typedNum;
        try {
            typedNum = Integer.parseInt(typed);
        } catch (Throwable t) {
            typedNum = Integer.MIN_VALUE;   // 非数字（空/字母）→ 视为非法
        }
        boolean fellBack = typedNum != v;
        if (fellBack) {
            // 非法/越界 → 回退默认 400 并回填输入框，保证“看到的=保存的”
            medicHealEt.setText(String.valueOf(v));
            medicHealEt.setSelection(medicHealEt.getText().length());
        } else if (!String.valueOf(v).equals(typed)) {
            // 合法但有前导零（如 0400）→ 静默规范显示，不提示错误
            medicHealEt.setText(String.valueOf(v));
            medicHealEt.setSelection(medicHealEt.getText().length());
        }
        FnParams.putTopLevel(this, account.fpid, serverid, "medic", "heal_count", v);
        medicHealDirty = false;
        refreshMedicSummary();
        if (showToast) {
            toast(fellBack ? "输入不合法，已回退 400 并保存"
                    : "医院设置已保存并同步服务器（每批 " + v + "）");
        }
    }

    /** 刷新医院设置摘要（当前每批数量）。 */
    private void refreshMedicSummary() {
        if (medicSummaryView == null) return;
        int heal = FnParams.parseMedicHealCount(
                medicHealEt == null ? null : medicHealEt.getText().toString());
        medicSummaryView.setText(medicSummaryText(heal));
    }

    private static String medicSummaryText(int heal) {
        return "每批 " + heal + " 个 · 循环治疗至伤兵清零/资源不足";
    }

    /** 收起软键盘（数字输入完成/保存后，避免键盘挡住下方卡片）。 */
    private void hideKeyboard(View v) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    // ================= 狩猎设置（hunt）：等级区间 + 出击次数 =================
    //
    // 历史遗留的「高级参数（JSON）」编辑入口已整体移除：参数一律由功能专属卡片的
    // 开关/下拉完成（采集→采集设置页；狩猎→本卡；其它功能→子项开关/策略选项）。
    // 狩猎是唯一被执行侧消费的数值参数（FieldTasks.hunt 读 level_min/level_max/max_times），
    // 解析/夹取/写回全部放到纯函数 HuntConfig（JVM 单测覆盖），本页只做 UI；
    // 保存仍走 FnParams + VaultSync.onAccountsChanged 的既有链路，存储格式不变。

    /** 狩猎设置卡：创建后由 {@link #fillHuntCard} 填充，选择变更时就地重绘。 */
    private LinearLayout buildHuntConfigCard() {
        LinearLayout card = UiStyle.card(this);
        fillHuntCard(card);
        return card;
    }

    /** 重绘狩猎设置卡：标题 + 当前值摘要 + 三条下拉（等级下限/上限、出击次数）+ 说明。 */
    private void fillHuntCard(LinearLayout card) {
        if (card == null || account == null) return;
        card.removeAllViews();
        final HuntConfig.Cfg cfg = HuntConfig.parse(
                FnParams.params(this, account.fpid, serverid, HuntConfig.FN_ID));

        TextView title = new TextView(this);
        title.setText("狩猎设置");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        card.addView(title);

        TextView cur = new TextView(this);
        cur.setText("当前：" + cfg.summary());
        cur.setTextSize(12);
        cur.setTypeface(Typeface.DEFAULT_BOLD);
        cur.setTextColor(UiStyle.BRAND_TEXT);
        cur.setPadding(0, dp(4), 0, dp(2));
        card.addView(cur);

        TextView tip = new TextView(this);
        tip.setText("只搜索该等级区间的野怪；下限高于上限时自动夹取（上限至少等于下限）。"
                + "出击次数用满、无目标或体力不足即停。");
        tip.setTextSize(10.5f);
        tip.setTextColor(UiStyle.INK2);
        tip.setPadding(0, 0, 0, dp(6));
        card.addView(tip);

        card.addView(UiStyle.dropdownRow(this, "等级下限", "Lv" + cfg.levelMin,
                () -> chooseHuntLevel(card, cfg.levelMin, true)));
        card.addView(UiStyle.dropdownRow(this, "等级上限", "Lv" + cfg.levelMax,
                () -> chooseHuntLevel(card, cfg.levelMax, false)));
        card.addView(UiStyle.dropdownRow(this, "出击次数", cfg.maxTimes + " 次",
                () -> chooseHuntTimes(card, cfg.maxTimes)));
    }

    /** 选等级（只列 1-10，无自由输入）；lower=true 改下限，false 改上限。 */
    private void chooseHuntLevel(LinearLayout card, int current, boolean lower) {
        final int[] values = HuntConfig.levelOptions();
        String[] labels = new String[values.length];
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = "Lv" + values[i];
            if (values[i] == current) selected = i;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(lower ? "等级下限（1-10）" : "等级上限（1-10）")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    HuntConfig.Cfg next = HuntConfig.parse(
                            FnParams.params(this, account.fpid, serverid, HuntConfig.FN_ID));
                    if (lower) next.setLevelMin(values[which]);
                    else next.setLevelMax(values[which]);
                    saveHuntConfig(card, next);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选出击次数（只列 1-20，无自由输入）。 */
    private void chooseHuntTimes(LinearLayout card, int current) {
        final int[] values = HuntConfig.timesOptions();
        String[] labels = new String[values.length];
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " 次";
            if (values[i] == current) selected = i;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("出击次数（1-20）")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    HuntConfig.Cfg next = HuntConfig.parse(
                            FnParams.params(this, account.fpid, serverid, HuntConfig.FN_ID));
                    next.setMaxTimes(values[which]);
                    saveHuntConfig(card, next);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 写回 hunt params（只覆盖三个参数键，其它键保留）→ 同步服务器 → 就地重绘。 */
    private void saveHuntConfig(LinearLayout card, HuntConfig.Cfg cfg) {
        try {
            JSONObject next = HuntConfig.apply(
                    FnParams.params(this, account.fpid, serverid, HuntConfig.FN_ID), cfg);
            FnParams.saveParams(this, account.fpid, serverid, HuntConfig.FN_ID, next);
            VaultSync.onAccountsChanged(this);
            fillHuntCard(card);
            toast("狩猎设置已保存并同步服务器（" + cfg.summary() + "）");
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        }
    }

    private void toast(String s) {
        UiStyle.toast(this, s);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
