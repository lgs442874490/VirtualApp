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
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.AccountStore;
import com.carlos.vault.UiStyle;

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
        setContentView(buildUi());
        // 服务器模式：进页先拉一次服务器最新状态，回写后重渲染（否则看到的是上次快照）
        if (com.carlos.vault.VaultSync.isServerMode(this)
                && account.fpid != null && !account.fpid.isEmpty()) {
            com.carlos.vault.VaultSync.fetchState(this, (code, message, data) ->
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || code != 0) return;
                            setContentView(buildUi());
                        } catch (Throwable ignored) {
                        }
                    }));
        }
    }

    private String findFpid() {
        String accId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        for (AccountStore.Account a : store.all()) {
            if (a != null && a.id != null && a.id.equals(accId)) return a.fpid;
        }
        return "";
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
        bar.setPadding(dp(16), dp(18), dp(16), dp(4));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(UiStyle.BRAND);
        back.setPadding(dp(6), 0, dp(12), 0);
        UiStyle.press(back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(fn.name);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.BRAND_TEXT);
        bar.addView(title);
        root.addView(bar);

        // 信息卡
        LinearLayout info = UiStyle.card(this);
        info.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView desc = new TextView(this);
        desc.setText(fn.desc);
        desc.setTextSize(13);
        desc.setTextColor(UiStyle.INK);
        info.addView(desc);
        TextView role = new TextView(this);
        String roleName = "";
        try {
            for (RoleStore.Role r : RoleStore.roles(this, account.fpid)) {
                if (r.serverid == serverid && r.name != null && !r.name.isEmpty()) {
                    roleName = r.name;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        role.setText("角色：" + (roleName.isEmpty()
                ? account.label + (serverid > 0 ? " · K" + serverid : "")
                : roleName + " · K" + serverid + "（" + account.label + "）")
                + "（配置只作用于本角色）");
        role.setTextSize(12);
        role.setTextColor(UiStyle.INK2);
        role.setPadding(0, dp(6), 0, 0);
        info.addView(role);

        // 总开关行（与子项开关同语言的 iOS Switch）：关闭后自动日常/一键执行跳过本功能。
        // 底层配置键不变（cfg[fn.id]），执行链路照旧读取。
        Map<String, Boolean> cfg = configStore.roleConfig(account.fpid, serverid);
        // roleConfig 在无角色配置/无旧号级配置/无 meta 模板时返回 null（全新状态）——
        // 判空兜底（与 FnParams 读取侧的 null 回退同款），否则功能详情页一开就 NPE。
        Boolean on = cfg == null ? null : cfg.get(fn.id);
        LinearLayout enableRow = new LinearLayout(this);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        enableRow.setPadding(0, dp(10), 0, 0);
        LinearLayout enableCol = new LinearLayout(this);
        enableCol.setOrientation(LinearLayout.VERTICAL);
        TextView enableTitle = new TextView(this);
        enableTitle.setText("启用此功能");
        enableTitle.setTextSize(13);
        enableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enableTitle.setTextColor(UiStyle.INK);
        enableCol.addView(enableTitle);
        TextView enableSub = new TextView(this);
        enableSub.setText("关闭后，自动日常与一键执行都会跳过本功能");
        enableSub.setTextSize(11);
        enableSub.setTextColor(UiStyle.INK2);
        enableSub.setPadding(0, dp(2), 0, 0);
        enableCol.addView(enableSub);
        enableRow.addView(enableCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final UiStyle.Switch[] swRef = new UiStyle.Switch[1];
        toggle = new UiStyle.Switch(this, on == null || on,
                () -> {
                    boolean isChecked = swRef[0].isOn();
                    Map<String, Boolean> c = configStore.roleConfig(account.fpid, serverid);
                    if (c == null) c = configStore.metaConfig(DailyTasks.all());
                    c.put(fn.id, isChecked);
                    configStore.saveRoleConfig(account.fpid, serverid, c);
                    toast(isChecked ? "已启用" : "已停用");
                });
        swRef[0] = toggle;
        enableRow.addView(toggle);
        info.addView(enableRow);
        root.addView(info);

        // 立即执行（规范主按钮：48dp · 品牌实心 · 按压反馈；未开放置灰）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(dp(16), dp(12), dp(16), 0);
        TextView run = actionButton(fn.implemented ? "立即执行" : "功能待开放", fn.implemented);
        btnRow.addView(run, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(btnRow);

        // 整页滚动区：子项实时状态与配置 → 领取内容 → 运行记录
        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollBox = new LinearLayout(this);
        scrollBox.setOrientation(LinearLayout.VERTICAL);
        scrollBox.setPadding(0, 0, 0, dp(16));
        scroll.addView(scrollBox);

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
        subTitle.setText("子项状态 · 配置");
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

        // ② 领取内容（从运行记录提取成功领取条目）
        TextView claimTitle = new TextView(this);
        claimTitle.setText("领取内容");
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
        logTitle.setText("运行记录");
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
            com.carlos.vault.VaultSync.runTask(this, account.fpid, fn.id);
            toast("已提交服务器执行「" + fn.name + "」\n稍后下拉/重进本页查看结果");
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
                        r.category == DailyResult.Category.CLAIMED || r.category == DailyResult.Category.SKIPPED
                                ? LogStore.ST_DONE : LogStore.ST_FAILED);
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

    private void toast(String s) {
        UiStyle.toast(this, s);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
