package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 工具箱（设计稿 S9）：诊断/监控能力的独立页。
 *
 * <p>从旧版「文字对话框」升级为状态透明的独立页：
 * <ul>
 *   <li>已开放 · 2：协议监听（日志体积 + 开关）、敌情监控（推送渠道 + 开关 + 配置）</li>
 *   <li>开发调试：P1 协议探针（原 HomeActivity.showProbe 原样迁入，功能不变）</li>
 *   <li>规划中 · 14：置灰网格，开放后自动进入「常用」</li>
 * </ul>
 * 敌情监控配置（原 HomeActivity.showWatchConfig）与协议探针（showProbe）整体迁入本页，
 * HomeActivity 不再保留副本，避免两处逻辑漂移。
 */
public class ToolBoxActivity extends Activity {

    /** 兜底：进程被回收后本页可能成为栈底，按返回应回主页而不是退出 App。 */
    @Override
    public void onBackPressed() {
        if (isTaskRoot()) {
            try {
                startActivity(new Intent(this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return;
            } catch (Throwable ignored) {
            }
        }
        super.onBackPressed();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
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
        setContentView(buildUi());
    }

    // ================= UI =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        // 顶栏：‹ + 标题
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(6));
        FrameLayout backWrap = new FrameLayout(this);
        backWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.BRAND_TEXT, 22),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(backWrap);
        backWrap.setOnClickListener(v -> finish());
        bar.addView(backWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("工具箱");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(24));
        sc.addView(box);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // —— 已开放 · 2 ——
        box.addView(sectionRow("已开放 · 2", "诊断与监控"));

        // 协议监听
        LinearLayout protoCard = toolCard(UiStyle.Icon.TARGET, "协议监听",
                protoSub(), UiStyle.BRAND_50);
        final UiStyle.Switch[] protoSw = new UiStyle.Switch[1];
        protoSw[0] = new UiStyle.Switch(this,
                com.carlos.vault.daily.ProtoLogger.on(this), () -> {
                    boolean turnOn = protoSw[0].isOn();
                    com.carlos.vault.daily.ProtoLogger.setOn(this, turnOn);
                    if (turnOn) com.carlos.vault.daily.ProtoLogger.reattachAll(this);
                    toast(turnOn ? "协议监听已开启\n（原生游戏里玩一轮后退出，稍后我来拉日志分析）"
                            : "协议监听已停止");
                    refreshProtoCard(protoCard, protoSw[0]);
                });
        protoCard.addView(protoSw[0]);
        attachToggle(protoCard, protoSw[0]);
        refreshProtoCard(protoCard, protoSw[0]);
        box.addView(protoCard);

        // 敌情监控
        LinearLayout watchCard = toolCard(UiStyle.Icon.EYE, "敌情监控",
                watchSub(), UiStyle.BRAND_50);
        final UiStyle.Switch[] watchSw = new UiStyle.Switch[1];
        watchSw[0] = new UiStyle.Switch(this,
                com.carlos.vault.daily.EnemyWatchStore.enabled(this), () -> {
                    boolean on = watchSw[0].isOn();
                    saveWatchEnabled(on);
                    if (on) com.carlos.vault.daily.EnemyWatch.attachAll(this);
                    toast("敌情监控已" + (on ? "开启" : "关闭"));
                    setCardSub(watchCard, watchSub());
                });
        watchCard.addView(watchSw[0]);
        // 点卡片主体 = 打开配置（开关只承担开关，避免误触）
        watchCard.setOnClickListener(v -> showWatchConfig());
        box.addView(watchCard);

        // 开发调试（P1 协议探针）保留为隐藏入口：长按「开放策略」卡片进入。

        // —— 规划中 · 14 ——
        box.addView(sectionRow("规划中 · 14", "后续版本陆续开放"));
        String[] planned = {"排行榜", "进攻 / 撞矿", "起号", "活动榜单",
                "迁城", "三消", "爆兵", "宠物洗练",
                "巨兽批量", "坐标查询", "侦察战报", "移民抢位",
                "充值记录", "编队管理"};
        int[] plannedIcons = {UiStyle.Icon.TARGET, UiStyle.Icon.SHIELD, UiStyle.Icon.PLUS,
                UiStyle.Icon.GIFT, UiStyle.Icon.COMPASS, UiStyle.Icon.GRID,
                UiStyle.Icon.USERS, UiStyle.Icon.COMPASS, UiStyle.Icon.SHIELD,
                UiStyle.Icon.SEARCH, UiStyle.Icon.DOC, UiStyle.Icon.TARGET,
                UiStyle.Icon.DOC, UiStyle.Icon.CHECKLIST};
        for (int r = 0; r < planned.length; r += 3) {
            LinearLayout grow = new LinearLayout(this);
            grow.setOrientation(LinearLayout.HORIZONTAL);
            grow.setPadding(0, dp(6), 0, 0);
            for (int c = r; c < Math.min(r + 3, planned.length); c++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(10), dp(4), dp(10));
                cell.addView(new UiStyle.Icon(this, plannedIcons[c], UiStyle.INK2, 26),
                        new LinearLayout.LayoutParams(dp(26), dp(26)));
                TextView label = new TextView(this);
                label.setText(planned[c]);
                label.setTextSize(12);
                label.setTextColor(UiStyle.INK3);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(8), 0, 0);
                label.setMaxLines(1);
                cell.addView(label);
                grow.addView(cell, new LinearLayout.LayoutParams(0, dp(84), 1));
            }
            // 末行不足 3 个时补空位，保持列宽一致
            for (int c = Math.min(r + 3, planned.length); c < r + 3; c++) {
                grow.addView(new View(this), new LinearLayout.LayoutParams(0, dp(84), 1));
            }
            box.addView(grow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 开放策略说明（长按进入开发调试 · P1 协议探针）
        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.HORIZONTAL);
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND_50, 16));
        note.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(18);
        note.setLayoutParams(nlp);
        note.addView(UiStyle.iconTile(this, UiStyle.Icon.CLOCK, UiStyle.BRAND_TEXT,
                UiStyle.WHITE, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout nCol = new LinearLayout(this);
        nCol.setOrientation(LinearLayout.VERTICAL);
        nCol.setPadding(dp(12), 0, 0, 0);
        TextView n1 = new TextView(this);
        n1.setText("开放策略");
        n1.setTextSize(15);
        n1.setTypeface(Typeface.DEFAULT_BOLD);
        n1.setTextColor(UiStyle.INK);
        nCol.addView(n1);
        TextView n2 = new TextView(this);
        n2.setText("随日常模块稳定性逐个解锁，开放后自动进入「常用」");
        n2.setTextSize(11.5f);
        n2.setTextColor(UiStyle.INK2);
        n2.setPadding(0, dp(3), 0, 0);
        nCol.addView(n2);
        note.addView(nCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        note.setOnLongClickListener(v -> {
            showProbe();
            return true;
        });
        box.addView(note);

        return root;
    }

    /** 分区标题行：左主标题 + 右提示。 */
    private LinearLayout sectionRow(String title, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(16), dp(2), dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(11);
        h.setTextColor(UiStyle.INK3);
        row.addView(h);
        return row;
    }

    /** 工具行卡：图标盒 + 标题/副题 + （调用方追加开关）。点卡片=回调由调用方定。 */
    private LinearLayout toolCard(int iconType, String title, String sub, int iconBg) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 16, UiStyle.LINE));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        card.addView(UiStyle.iconTile(this, iconType, UiStyle.BRAND_TEXT, iconBg, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11.5f);
        s.setTextColor(UiStyle.INK2);
        s.setPadding(0, dp(3), 0, 0);
        s.setTag("sub");
        col.addView(s);
        return card;
    }

    /** 卡片副题文案刷新（tag=sub 的 TextView）。 */
    private void setCardSub(LinearLayout card, String sub) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View c = card.getChildAt(i);
            if (!(c instanceof LinearLayout)) continue;
            LinearLayout col = (LinearLayout) c;
            for (int j = 0; j < col.getChildCount(); j++) {
                View v = col.getChildAt(j);
                if (v instanceof TextView && "sub".equals(v.getTag())) {
                    ((TextView) v).setText(sub);
                    return;
                }
            }
        }
    }

    /** 点卡片 = 拨动开关（视觉行为一致）。 */
    private void attachToggle(LinearLayout card, UiStyle.Switch sw) {
        card.setOnClickListener(v -> sw.performClick());
    }

    private String protoSub() {
        long sz = com.carlos.vault.daily.ProtoLogger.logSize(this);
        String size = sz <= 0 ? "暂无记录" : (sz < 1024 * 1024
                ? String.format("%.1fKB", sz / 1024f)
                : String.format("%.1fMB", sz / 1048576f));
        return "全账号协议抓包 · 已记录 " + size;
    }

    private void refreshProtoCard(LinearLayout card, UiStyle.Switch sw) {
        sw.setOn(com.carlos.vault.daily.ProtoLogger.on(this));
        setCardSub(card, protoSub());
    }

    private String watchSub() {
        int n = com.carlos.vault.daily.EnemyWatchStore.entries(this).size();
        return "标记敌人出征 · 企业微信 + 短信 · 名单 " + n + " 个";
    }

    /** 只翻转总开关，其余配置原样写回（EnemyWatchStore.save 是全量保存）。 */
    private void saveWatchEnabled(boolean on) {
        StringBuilder listText = new StringBuilder();
        for (com.carlos.vault.daily.EnemyWatchStore.Entry e
                : com.carlos.vault.daily.EnemyWatchStore.entries(this)) {
            listText.append(e.kid).append(':').append(e.uid);
            if (e.note != null && !e.note.isEmpty()) listText.append(' ').append(e.note);
            listText.append('\n');
        }
        com.carlos.vault.daily.EnemyWatchStore.save(this, on,
                com.carlos.vault.daily.EnemyWatchStore.webhook(this),
                com.carlos.vault.daily.EnemyWatchStore.smsPhone(this),
                com.carlos.vault.daily.EnemyWatchStore.wecomOn(this),
                com.carlos.vault.daily.EnemyWatchStore.smsOn(this),
                com.carlos.vault.daily.EnemyWatchStore.smsEscalateOnly(this),
                listText.toString());
    }

    // ================= 敌情监控配置（自 HomeActivity 迁入，逻辑不变） =================

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
        for (com.carlos.vault.daily.EnemyWatchStore.Entry e
                : com.carlos.vault.daily.EnemyWatchStore.entries(this)) {
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
                            cbOn.isChecked(), etWebhook.getText().toString(),
                            etPhone.getText().toString(),
                            cbWecom.isChecked(), cbSms.isChecked(), cbEsc.isChecked(),
                            etList.getText().toString());
                    com.carlos.vault.daily.EnemyWatch.attachAll(this);
                    toast(bad > 0 ? "已保存，" + bad + " 行格式非法被忽略"
                            : "已保存，已对在线会话即时挂载");
                    refreshUi();
                })
                .setNeutralButton("测试推送", (d, w) -> {
                    int bad = com.carlos.vault.daily.EnemyWatchStore.save(this,
                            cbOn.isChecked(), etWebhook.getText().toString(),
                            etPhone.getText().toString(),
                            cbWecom.isChecked(), cbSms.isChecked(), cbEsc.isChecked(),
                            etList.getText().toString());
                    com.carlos.vault.daily.EnemyWatch.attachAll(this);
                    com.carlos.vault.daily.EnemyWatch.sendTest(this);
                    toast(bad > 0 ? "已保存并发送测试（" + bad + " 行非法）"
                            : "已保存并发送测试推送");
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

    /** 配置保存后整页重Render，开关态/副题即时刷新。 */
    private void refreshUi() {
        setContentView(buildUi());
    }

    // ================= P1 协议探针（自 HomeActivity 迁入，逻辑不变） =================

    private void showProbe() {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(6));

        TextView sess = new TextView(this);
        List<String> online = com.carlos.vault.daily.GuardProbe.onlineSessions(this);
        sess.setText("在线会话（探针取第一个在线）：\n"
                + (online.isEmpty() ? "（无）" : joinLines(online)));
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
        row.addView(px, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(py, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        box.addView(btn("④ 驻防发起 type=5（A-2 · 打盟友城，发后不召回）", v -> {
            long gx = 687, gy = 698;
            try {
                gx = Long.parseLong(px.getText().toString().trim());
            } catch (Throwable ignored) {
            }
            try {
                gy = Long.parseLong(py.getText().toString().trim());
            } catch (Throwable ignored) {
            }
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
                                + "确认执行？",
                        () -> com.carlos.vault.daily.GuardProbe.repatriateProbe(this, null,
                                pu.getText().toString().trim()))));

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

    private Button btn(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(onClick);
        return b;
    }

    private void confirmThen(String msg, Runnable action) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("消耗确认")
                .setMessage(msg)
                .setPositiveButton("确认执行", (d, w) -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        toast("执行异常：" + t.getClass().getSimpleName());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 探针结果读 GuardProbe 的内存队列（最新在前）；与旧 HomeActivity 实现同源。 */
    private String joinProbe() {
        List<String> list = com.carlos.vault.daily.GuardProbe.recent();
        if (list.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(list.size(), 20); i++) sb.append(list.get(i)).append('\n');
        return sb.toString();
    }
}
