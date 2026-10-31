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
 * <p>批次7（2026-10-23）按 {@link FeatureGate} 分级门控重排：
 * <ul>
 *   <li><b>敌情监控</b>（T3 高级会员）：推送渠道 + 开关 + 配置；无权限不渲染；</li>
 *   <li><b>真机监控</b>（T3 高级会员）：服务端任务级协议监控页（daily/MonitorActivity）；</li>
 *   <li><b>协议监听</b>（调试包专属）：正式包不渲染；</li>
 *   <li><b>P1 协议探针</b>（调试包专属）：长按「开放策略」卡片进入，正式包无入口；</li>
 *   <li><b>历史「规划中 · 14」整段下架</b>：三消/爆兵/宠物洗练/移民抢位/侦察战报/迁城/
 *       坐标查询/充值记录/巨兽批量等均无实现，未开发/未立项功能一律不显示；
 *       同一条目在 {@code design/全流程行为与分级门控-20261023.md} 中登记。</li>
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
        UiStyle.toast(this, s);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 批次7 防旁路：本页只有 T3（敌情监控/真机监控）与调试工具；无任何可用工具时（含直接 Intent
        // 启动绕过 Home 入口门控）直接返回，不放空壳页也不泄露功能清单。
        if (!FeatureGate.visible(this, FeatureGate.Feature.ENEMY_WATCH)
                && !FeatureGate.visible(this, FeatureGate.Feature.DEV_MONITOR)
                && !FeatureGate.visible(this, FeatureGate.Feature.PROTOCOL_LOGGER)) {
            toast(FeatureGate.reasonText(FeatureGate.Feature.TOOLBOX));
            finish();
            return;
        }
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
        UiComponents.Page page = UiComponents.scrollPage(
                this, "工具箱", null, this::finish, 24);
        LinearLayout box = page.body;

        // ===== 批次7 功能门控（单一事实源 FeatureGate，2026-10-23） =====
        // 原则：① 无权限功能不显示；② 未开发/未立项功能一律隐藏（历史“规划中 · 14”整段下架，
        // 三消/爆兵/宠物洗练/移民抢位/侦察战报/迁城/坐标查询/充值记录/巨兽批量等均无实现）；
        // ③ 工具箱内调试/实测项仅调试包可见。
        final boolean debugBuild = FeatureGate.isDebugBuild(this);
        final int tier = FeatureGate.currentTier(this);
        final boolean canWatch = FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH, tier, debugBuild);      // T3 高阶
        final boolean canMonitor = FeatureGate.visible(FeatureGate.Feature.DEV_MONITOR, tier, debugBuild);    // T3 高阶（正式包可见）
        final boolean canWatchpoint = FeatureGate.visible(FeatureGate.Feature.WATCHPOINT, tier, debugBuild);   // T3 高阶
        final boolean canProto = FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER, tier, debugBuild); // 仅调试包
        final int toolCount = FeatureGate.visibleToolCount(tier, debugBuild);
        if (toolCount > 0) {
            box.addView(sectionRow("可用工具 · " + toolCount, "诊断与监控"));
            // 协议监听（调试包专属：正式包不渲染）
            if (canProto) {
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
            }
            // 敌情监控（T3 高级会员）
            if (canWatch) {
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
            }
            // 观察点（批次 B 2026-10-30）：App 内可视化编辑服务器 data/watchpoint.json（get/set/enable）。
            if (canWatchpoint) {
                LinearLayout wpCard = toolCard(UiStyle.Icon.EYE, "观察点",
                        "监视敌方城/保护友盟聚集地（告警配置，保存即热载）", UiStyle.BRAND_50);
                wpCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                wpCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.WatchpointActivity.class)));
                box.addView(wpCard);
            }
            // 真机监控（T3 高级会员，2026-10-25）：服务端任务级协议监控只读页，点击进入。
            // 服务端未部署该接口时页内提示「服务端未升级」，入口照常（先装 App 等部署）。
            if (canMonitor) {
                LinearLayout monitorCard = toolCard(UiStyle.Icon.TARGET, "真机监控",
                        "服务端任务/协议实时监控（领取·任务）", UiStyle.BRAND_50);
                monitorCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                monitorCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.daily.MonitorActivity.class)));
                box.addView(monitorCard);
            }
        } else {
            // 批次8（P0-1）：无任何可见工具时**整组/占位都不渲染**（不放空卡片、不放“暂无”占位）。
            // 正常路径下 onCreate 已 finish（防直开旁路），此处仅防御异常路径。
        }

        // 开发调试（P1 协议探针）保留为隐藏入口：长按「开放策略」卡片进入；仅调试包渲染。
        if (debugBuild) {
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
            n2.setText("调试包专属：长按本卡片进入协议探针");
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
        }

        return page.root;
    }

    /** 分区标题行：左主标题 + 右提示。 */
    private LinearLayout sectionRow(String title, String hint) {
        return UiComponents.sectionHeader(this, title, hint);
    }

    /** 工具行卡：统一图标卡；开关、箭头及点击回调仍由本页绑定。 */
    private LinearLayout toolCard(int iconType, String title, String sub, int iconBg) {
        return UiComponents.iconCard(this, iconType, title, sub, iconBg);
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
