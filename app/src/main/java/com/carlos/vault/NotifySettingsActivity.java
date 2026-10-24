package com.carlos.vault;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import com.carlos.vault.daily.WarNotifyGate;
import com.carlos.vault.daily.WarNotifyPoller;
import com.carlos.vault.daily.WarNotifyStore;

/**
 * 战争通知设置页（2026-10-22）：监视名单管理（服务器 /v1/vault/watch）、企业微信机器人
 * webhook 配置 + 测试、短信通知（App 本地：开关/手机号/权限引导）、最近事件列表
 * （服务器 /v1/vault/events）。UI 全程序化（UiStyle 设计语言），与工具箱页同骨架。
 *
 * <p>监视名单是<b>账号级</b>（fpid 寻址）：顶部账号选择器默认取上次使用或第一个工作区账号；
 * 名单/企微配置存服务器侧，短信开关/手机号/事件游标存 App 本地 prefs。
 */
public class NotifySettingsActivity extends Activity {

    private static final int REQ_SMS = 1001;

    private LinearLayout targetsBox;      // 监视名单容器
    private LinearLayout eventsBox;       // 最近事件容器
    private TextView accountValue;
    private EditText addUidEt, addNameEt, addKidEt, addNoteEt;
    private TextView addTargetBtn;      // 添加监视按钮（请求中禁用，防重复提交）
    private boolean addTargetInFlight;  // 添加监视请求进行中（防重入）
    private EditText webhookEt, cooldownEt, quietStartEt, quietEndEt, phoneEt;
    private UiStyle.Switch watchSw, wecomSw, smsSw;
    private TextView smsPermText;
    /** 企业微信卡片状态行：保存/测试结果落页面（不只 toast）。 */
    private TextView wecomStatus;
    /** 当前编辑的名单条目 uid（enabled 开关回传用）。 */
    private final List<String> targetUidOrder = new ArrayList<>();
    private String fpid = "";

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private void toast(String msg) {
        UiStyle.toast(this, msg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        setContentView(buildUi());
        WarNotifyPoller.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSmsPerm();
        loadAll();
    }

    // ================================= UI 构建 =================================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);
        root.addView(topBar());

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(30));
        sc.addView(box);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // —— 监视账号 ——
        box.addView(UiStyle.sectionTitle(this, "监视账号"));
        LinearLayout accCard = UiStyle.outlineCard(this);
        accCard.setOrientation(LinearLayout.HORIZONTAL);
        accCard.setGravity(Gravity.CENTER_VERTICAL);
        accCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView accLabel = new TextView(this);
        accLabel.setText("名单生效账号");
        accLabel.setTextSize(13.5f);
        accLabel.setTextColor(UiStyle.INK);
        accCard.addView(accLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        accountValue = new TextView(this);
        accountValue.setTextSize(13);
        accountValue.setTypeface(Typeface.DEFAULT_BOLD);
        accountValue.setTextColor(UiStyle.BRAND_TEXT);
        accCard.addView(accountValue);
        TextView arrow = new TextView(this);
        arrow.setText(" ▾");
        arrow.setTextSize(13);
        arrow.setTextColor(UiStyle.INK2);
        accCard.addView(arrow);
        UiStyle.press(accCard);
        accCard.setOnClickListener(v -> pickAccount());
        box.addView(accCard);
        box.addView(hint("监视名单按账号区分（该账号名下所有角色会话共同监视）；名单存服务器，多端共享。"));
        box.addView(hint("⚠️ 只能监控进入本角色「联盟/世界视野」的敌方行军（自己城或盟友被攻击、联盟态势推送）；"
                + "目标攻击视野外的其他联盟时游戏客户端不会收到该数据，无法产生通知。"));

        // —— 监视总开关 ——
        LinearLayout masterCard = UiStyle.outlineCard(this);
        masterCard.setOrientation(LinearLayout.HORIZONTAL);
        masterCard.setGravity(Gravity.CENTER_VERTICAL);
        masterCard.setPadding(dp(12), dp(8), dp(4), dp(8));
        masterCard.addView(colText("监视总开关", "关闭后服务器不再解析推送、不产生事件"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        watchSw = new UiStyle.Switch(this, false, () ->
                VaultSync.watchConfig(this, watchSw.isOn(), null, null, null, null, null,
                        cb("监视已" + (watchSw.isOn() ? "开启" : "关闭"), false)));
        masterCard.addView(watchSw);
        box.addView(masterCard);

        // —— 监视名单 ——
        box.addView(UiStyle.sectionTitle(this, "监视名单"));
        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(targetsBox);
        box.addView(addCard());

        // —— 企业微信机器人 ——
        box.addView(UiStyle.sectionTitle(this, "企业微信机器人"));
        LinearLayout wc = UiStyle.outlineCard(this);
        wc.setOrientation(LinearLayout.VERTICAL);
        wc.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout wcHead = new LinearLayout(this);
        wcHead.setOrientation(LinearLayout.HORIZONTAL);
        wcHead.setGravity(Gravity.CENTER_VERTICAL);
        wcHead.addView(colText("推送开关", "命中监视敌人动作时推送群机器人"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        wecomSw = new UiStyle.Switch(this, true, null);
        wcHead.addView(wecomSw);
        wc.addView(wcHead);
        webhookEt = field(wc, "机器人 Webhook 地址", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…");
        // URI 变体：避免中文输入法对 URL 全角化/拼音化（空格、全角冒号等会破坏 webhook）
        webhookEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout wcBtns = new LinearLayout(this);
        wcBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b1.topMargin = dp(10);
        wcBtns.addView(UiStyle.secondaryBtn(this, "保存", this::saveWecom), b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b2.leftMargin = dp(10);
        b2.topMargin = dp(10);
        wcBtns.addView(UiStyle.primaryBtn(this, "发送测试", this::testWecom), b2);
        wc.addView(wcBtns);
        wecomStatus = new TextView(this);
        wecomStatus.setTextSize(11.5f);
        wecomStatus.setPadding(dp(2), dp(8), 0, 0);
        wc.addView(wecomStatus);
        wc.addView(hint("⚠️ 保存后需在上方「监视名单」添加敌人 uid 才会触发推送——webhook 只决定推送通道，名单决定监视谁。"));
        wc.addView(hint("推送频率受「冷却」约束；夜间静默时段只停企微推送，事件照常记录。"));
        box.addView(wc);

        // —— 短信通知 ——
        box.addView(UiStyle.sectionTitle(this, "短信通知"));
        LinearLayout sms = UiStyle.outlineCard(this);
        sms.setOrientation(LinearLayout.VERTICAL);
        sms.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout smsHead = new LinearLayout(this);
        smsHead.setOrientation(LinearLayout.HORIZONTAL);
        smsHead.setGravity(Gravity.CENTER_VERTICAL);
        smsHead.addView(colText("短信开关", "服务器事件经本机转发短信"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        smsSw = new UiStyle.Switch(this, WarNotifyStore.smsEnabled(this), () -> {
            boolean on = smsSw.isOn();
            WarNotifyStore.setSmsEnabled(this, on);
            if (on) {
                WarNotifyPoller.start(this);
                if (!smsPermissionGranted()) requestSmsPermission();
            }
            toast("短信通知已" + (on ? "开启" : "关闭"));
        });
        smsHead.addView(smsSw);
        sms.addView(smsHead);
        phoneEt = field(sms, "接收手机号", "5~20 位数字，支持国际号段");
        phoneEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        phoneEt.setText(WarNotifyStore.smsPhone(this));
        LinearLayout smsBtns = new LinearLayout(this);
        smsBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        s1.topMargin = dp(10);
        smsBtns.addView(UiStyle.secondaryBtn(this, "保存", this::saveSms), s1);
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        s2.leftMargin = dp(10);
        s2.topMargin = dp(10);
        smsBtns.addView(UiStyle.secondaryBtn(this, "短信权限", this::ensureSmsPermission), s2);
        sms.addView(smsBtns);
        smsPermText = new TextView(this);
        smsPermText.setTextSize(11.5f);
        smsPermText.setPadding(dp(2), dp(8), 0, 0);
        sms.addView(smsPermText);
        sms.addView(hint("App 每 30 秒向服务器拉一次新事件；同一事件只发一条，且最多每 5 分钟一条，防短信轰炸。权限被拒时降级为仅记录日志。"));
        box.addView(sms);

        // —— 高级 ——
        box.addView(UiStyle.sectionTitle(this, "节流与静默"));
        LinearLayout adv = UiStyle.outlineCard(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setPadding(dp(12), dp(12), dp(12), dp(12));
        cooldownEt = field(adv, "同目标同动作冷却（秒，60~3600）", "默认 300");
        quietStartEt = field(adv, "静默开始小时（0~23，留空关闭）", "如 23");
        quietEndEt = field(adv, "静默结束小时（0~23）", "如 8");
        LinearLayout.LayoutParams s3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s3.topMargin = dp(10);
        adv.addView(UiStyle.primaryBtn(this, "保存节流与静默", this::saveAdvanced), s3);
        box.addView(adv);

        // —— 最近事件 ——
        box.addView(UiStyle.sectionTitle(this, "最近事件"));
        eventsBox = new LinearLayout(this);
        eventsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(eventsBox);
        box.addView(hint("展示服务器最近记录（环形 200 条，含行为/发起人/兵力/结算）；点条目查看详情。下拉刷新重新拉取。"));

        return root;
    }

    private View topBar() {
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
        title.setText("战争通知");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    /** 标题/副题竖排（卡片头）。 */
    private View colText(String title, String sub) {
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
        return mid;
    }

    private EditText field(LinearLayout parent, String label, String hint) {
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
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        parent.addView(et, elp);
        return et;
    }

    private TextView hint(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(11.5f);
        h.setTextColor(UiStyle.INK3);
        h.setPadding(dp(4), dp(6), dp(4), 0);
        return h;
    }

    /** 添加监视行卡片。 */
    private View addCard() {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        addUidEt = field(card, "敌人 uid（必填）", "游戏内玩家 uid，纯数字");
        addUidEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        addNameEt = field(card, "名称（可选）", "游戏昵称");
        addKidEt = field(card, "区服 kid（可选）", "如 888");
        addKidEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        addNoteEt = field(card, "备注（可选）", "通知里优先显示");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        addTargetBtn = UiStyle.primaryBtn(this, "添加监视", this::addTarget);
        card.addView(addTargetBtn, lp);
        return card;
    }

    // ================================= 数据加载 =================================

    private void loadAll() {
        fpid = WarNotifyStore.lastFpid(this);
        if (fpid.isEmpty()) {
            List<AccountStore.Account> all = new AccountStore(this).all();
            if (!all.isEmpty()) fpid = all.get(0).fpid;
        }
        renderAccountLabel();
        if (fpid.isEmpty()) {
            toast("请先导入账号（我的 → 批量导入导出）");
            return;
        }
        VaultSync.watchList(this, fpid, (code, message, data) -> {
            if (code != 0 || data == null) {
                runOnUiThread(() -> toast("名单加载失败：" + message));
                return;
            }
            renderWatchData(data);
        });
        refreshEvents();
    }

    private void renderAccountLabel() {
        String label = fpid;
        for (AccountStore.Account a : new AccountStore(this).all()) {
            if (a.fpid.equals(fpid) && a.label != null && !a.label.isEmpty()) label = a.label;
        }
        accountValue.setText(label);
    }

    /** 名单 + 配置渲染（回调线程 → UI 线程）。 */
    private void renderWatchData(JSONObject data) {
        JSONArray targets = data.optJSONArray("targets");
        JSONObject config = data.optJSONObject("config");
        runOnUiThread(() -> {
            targetUidOrder.clear();
            targetsBox.removeAllViews();
            int n = targets == null ? 0 : targets.length();
            if (n == 0) {
                targetsBox.addView(hint("暂无监视目标 —— 用下方卡片添加（uid 见游戏好友/战报页）。"));
            }
            for (int i = 0; i < n; i++) {
                JSONObject t = targets.optJSONObject(i);
                if (t != null) targetsBox.addView(targetRow(t));
            }
            if (config != null) {
                watchSw.setOn(config.optBoolean("enabled", false));
                wecomSw.setOn(config.optBoolean("wecomEnabled", true));
                if (webhookEt.getText() == null || webhookEt.getText().length() == 0) {
                    webhookEt.setText(config.optString("webhookUrl", ""));
                }
                if (cooldownEt.getText() == null || cooldownEt.getText().length() == 0) {
                    cooldownEt.setText(String.valueOf(config.optInt("cooldownSeconds", 300)));
                }
                int qs = config.optInt("quietStart", -1);
                if (quietStartEt.getText() == null || quietStartEt.getText().length() == 0) {
                    quietStartEt.setText(qs < 0 ? "" : String.valueOf(qs));
                    quietEndEt.setText(String.valueOf(config.optInt("quietEnd", 8)));
                }
            }
        });
    }

    /** 单个监视目标行：显示名 + uid@kid + 开关 + 删除。 */
    private View targetRow(JSONObject t) {
        final String uid = t.optString("uid", "");
        targetUidOrder.add(uid);
        String d = t.optString("note", "");
        if (d.isEmpty()) d = t.optString("name", "");
        if (d.isEmpty()) d = "uid " + uid;
        final String display = d;

        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(display);
        name.setTextSize(13.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(t.optBoolean("enabled", true) ? UiStyle.INK : UiStyle.INK3);
        mid.addView(name);
        TextView sub = new TextView(this);
        String kid = t.optString("kid", "");
        sub.setText("uid " + uid + (kid.isEmpty() ? "" : " @k" + kid));
        sub.setTextSize(11.5f);
        sub.setTextColor(UiStyle.INK2);
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final UiStyle.Switch[] swBox = new UiStyle.Switch[1];
        swBox[0] = new UiStyle.Switch(this, t.optBoolean("enabled", true), () -> {
            boolean on = swBox[0].isOn();
            VaultSync.watchUpdate(this, fpid, uid, null, null, null, on,
                    cb("已" + (on ? "启用" : "停用") + "监视", true));
        });
        row.addView(swBox[0]);
        TextView del = new TextView(this);
        del.setText("删除");
        del.setTextSize(12);
        del.setTextColor(UiStyle.ERR_INK);
        del.setPadding(dp(12), dp(8), dp(8), dp(8));
        UiStyle.press(del);
        del.setOnClickListener(v -> new AlertDialog.Builder(this,
                        AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("删除监视")
                .setMessage("不再监视 " + display + "（uid " + uid + "）？")
                .setPositiveButton("删除", (dg, w) -> VaultSync.watchRemove(this, fpid, uid,
                        cb("已删除", true)))
                .setNegativeButton("取消", null).show());
        row.addView(del);
        return row;
    }

    /** 最近事件列表（取最近 20 条倒序展示）。 */
    private void refreshEvents() {
        VaultSync.fetchEvents(this, 0, (code, message, data) -> {
            if (code != 0 || data == null) return;
            JSONArray events = data.optJSONArray("events");
            runOnUiThread(() -> {
                eventsBox.removeAllViews();
                if (events == null || events.length() == 0) {
                    eventsBox.addView(hint("暂无事件记录 —— 命中监视敌人动作后这里会出现时间线。"));
                    return;
                }
                int from = Math.max(0, events.length() - 20);
                for (int i = events.length() - 1; i >= from; i--) {
                    JSONObject e = events.optJSONObject(i);
                    if (e == null) continue;
                    JSONObject ep = e.optJSONObject("endpoint");
                    boolean hasXY = ep != null;
                    LinearLayout row = UiStyle.outlineCard(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(12), dp(8), dp(12), dp(8));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = dp(6);
                    row.setLayoutParams(rlp);
                    TextView line = new TextView(this);
                    line.setText(WarNotifyGate.formatEventRow(e.optLong("detectedAt", 0),
                            e.optString("action", ""), e.optString("targetName", ""),
                            hasXY, hasXY ? ep.optInt("x") : 0, hasXY ? ep.optInt("y") : 0,
                            e.optLong("sid", 0)));
                    line.setTextSize(12.5f);
                    line.setTextColor(UiStyle.INK);
                    row.addView(line);
                    // v4.1.8：第二行展示新字段（行为/兵力/ETA 或 结算/破城）
                    String sub = WarNotifyGate.formatEventSub(e);
                    if (!sub.isEmpty()) {
                        TextView subV = new TextView(this);
                        subV.setText(sub);
                        subV.setTextSize(11f);
                        subV.setPadding(0, dp(2), 0, 0);
                        subV.setTextColor(UiStyle.INK2);
                        row.addView(subV);
                    }
                    // 条目可点开详情（纯文本弹窗）
                    UiStyle.press(row);
                    row.setOnClickListener(v -> new AlertDialog.Builder(this,
                                    AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                            .setTitle("事件详情")
                            .setMessage(WarNotifyGate.formatEventDetail(e))
                            .setPositiveButton("关闭", null).show());
                    eventsBox.addView(row);
                }
            });
        });
    }

    // ================================= 动作 =================================

    /** 通用回调：toast 结果；reload=true 时成功后重拉名单。 */
    private VaultSync.Callback cb(String okMsg, boolean reload) {
        return (code, message, data) -> runOnUiThread(() -> {
            if (code == 0) {
                if (okMsg != null && !okMsg.isEmpty()) toast(okMsg);
                if (reload) loadAll();
            } else {
                toast("失败：" + (message == null ? String.valueOf(code) : message));
            }
        });
    }

    private void pickAccount() {
        List<AccountStore.Account> all = new AccountStore(this).all();
        if (all.isEmpty()) {
            toast("暂无账号，请先导入");
            return;
        }
        String[] names = new String[all.size()];
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            AccountStore.Account a = all.get(i);
            names[i] = (a.label == null || a.label.isEmpty() ? a.fpid : a.label)
                    + (a.fpid.equals(fpid) ? "（当前）" : "");
            if (a.fpid.equals(fpid)) checked = i;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择名单生效账号")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    fpid = all.get(which).fpid;
                    WarNotifyStore.setLastFpid(this, fpid);
                    d.dismiss();
                    renderAccountLabel();
                    loadAll();
                })
                .setNegativeButton("取消", null).show();
    }

    private void addTarget() {
        if (addTargetInFlight) return;
        String uid = text(addUidEt);
        if (!uid.matches("\\d+") || Long.parseLong(uid) <= 0) {
            toast("uid 必须为大于 0 的数字");
            return;
        }
        addTargetInFlight = true;
        addTargetBtn.setEnabled(false);
        addTargetBtn.setAlpha(0.5f);
        VaultSync.watchAdd(this, fpid, uid, text(addNameEt), text(addKidEt), text(addNoteEt),
                (code, message, data) -> runOnUiThread(() -> {
                    addTargetInFlight = false;
                    addTargetBtn.setEnabled(true);
                    addTargetBtn.setAlpha(1f);
                    if (code == 0) {
                        toast("已添加监视");
                        addUidEt.setText("");
                        addNameEt.setText("");
                        addKidEt.setText("");
                        addNoteEt.setText("");
                        loadAll();
                    } else {
                        toast("失败：" + (message == null ? String.valueOf(code) : message));
                    }
                }));
    }

    private void saveWecom() {
        VaultSync.watchConfig(this, null, wecomSw.isOn(), text(webhookEt),
                null, null, null, (code, message, data) -> runOnUiThread(() -> {
                    if (code == 0) {
                        setWecomStatus("✅ 已保存（企微推送已" + (wecomSw.isOn() ? "开启" : "关闭") + "）",
                                UiStyle.OK_INK);
                        toast("企业微信配置已保存");
                    } else {
                        setWecomStatus("❌ 保存失败：" + (message == null ? String.valueOf(code) : message),
                                UiStyle.ERR_INK);
                        toast("失败：" + (message == null ? String.valueOf(code) : message));
                    }
                }));
    }

    private void testWecom() {
        setWecomStatus("… 测试发送中", UiStyle.INK2);
        VaultSync.watchTestNotify(this, (code, message, data) -> runOnUiThread(() -> {
            if (code != 0) {
                setWecomStatus("❌ 发送失败：" + message, UiStyle.ERR_INK);
                return;
            }
            boolean ok = data != null && data.optBoolean("ok", false);
            setWecomStatus(ok ? "✅ 测试消息已发送，请在企业微信群确认"
                            : "❌ 发送失败：请检查 webhook 地址与网络",
                    ok ? UiStyle.OK_INK : UiStyle.ERR_INK);
        }));
    }

    /** 企微卡片状态行（保存/测试结果；页面内可见，不只 toast）。 */
    private void setWecomStatus(String text, int color) {
        if (wecomStatus == null) return;
        wecomStatus.setText(text);
        wecomStatus.setTextColor(color);
    }

    private void saveSms() {
        String phone = text(phoneEt);
        if (!phone.isEmpty() && !WarNotifyGate.phoneValid(phone)) {
            toast("手机号须为 5~20 位数字");
            return;
        }
        WarNotifyStore.setSmsPhone(this, phone);
        WarNotifyStore.setSmsEnabled(this, smsSw.isOn());
        if (smsSw.isOn()) WarNotifyPoller.start(this);
        toast("短信设置已保存");
    }

    private void saveAdvanced() {
        Integer cooldown = intOrNull(cooldownEt);
        if (cooldown == null) cooldown = 300;
        String qs = text(quietStartEt);
        String qe = text(quietEndEt);
        Integer quietStart = qs.isEmpty() ? -1 : intOrNull(quietStartEt);
        Integer quietEnd = qe.isEmpty() ? 8 : intOrNull(quietEndEt);
        if ((quietStart != null && quietStart != -1 && (quietStart < 0 || quietStart > 23))
                || (quietEnd != null && (quietEnd < 0 || quietEnd > 23))) {
            toast("静默小时须为 0~23");
            return;
        }
        VaultSync.watchConfig(this, watchSw == null ? null : watchSw.isOn(), null, null,
                cooldown, quietStart, quietEnd, cb("节流与静默已保存", false));
    }

    // ================================= 短信权限 =================================

    private boolean smsPermissionGranted() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshSmsPerm() {
        boolean ok = smsPermissionGranted();
        smsPermText.setText(ok ? "● 短信权限已授予"
                : "● 未授予 SEND_SMS 权限 —— 点「短信权限」申请；拒绝则只记录日志不发短信");
        smsPermText.setTextColor(ok ? UiStyle.OK_INK : UiStyle.WARN_INK);
    }

    private void ensureSmsPermission() {
        if (smsPermissionGranted()) {
            toast("短信权限已授予");
            return;
        }
        requestSmsPermission();
    }

    private void requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SMS) {
            refreshSmsPerm();
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "短信权限已授予" : "已拒绝：短信通知降级为仅记录日志");
        }
    }

    // ================================= 工具 =================================

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static Integer intOrNull(EditText et) {
        try {
            String s = text(et);
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
