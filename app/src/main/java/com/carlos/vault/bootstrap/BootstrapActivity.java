package com.carlos.vault.bootstrap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.FeatureGate;
import com.carlos.vault.HomeActivity;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起号（M5，2026-11-07）：注册 / 起号 / 批次 三区页面，1:1 对齐原版
 * RegistrationWorkspace / BootstrapWorkspace 的文案与状态语义（任务书 §2）。
 *
 * <p>交互与工程约束：
 * <ul>
 *   <li>所有请求走 {@link VaultSync#registrationCall} / {@link VaultSync#bootstrapCall}
 *       （异步，回调在 OkHttp 线程后 {@code runOnUiThread} 回 UI）；</li>
 *   <li>批次视图前台 5s 轮询 batch-status，批次终态/页面不可见即停（对齐 MonitorActivity 模式）；</li>
 *   <li>接码凭据 / token / 身份证输入只在内存（password 型输入框），不落盘、不打日志，
 *       onDestroy 清空；</li>
 *   <li>回流导入走 {@link BootstrapReferralSync#mergeAndMark}：入号库 + referral-pull 标记，
 *       <b>不自动触发全量 sync</b>，UI 提示「已并入号库，稍后请在账号页手动同步」。</li>
 * </ul>
 */
public class BootstrapActivity extends Activity {

    /** 批次轮询间隔（任务书 §3.2：5s）。 */
    private static final long POLL_MS = 5000L;

    private static final int TAB_REGISTER = 0;
    private static final int TAB_BOOTSTRAP = 1;
    private static final int TAB_BATCHES = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private LinearLayout body;
    private LinearLayout regPanel;
    private LinearLayout bootPanel;
    private LinearLayout batchesPanel;

    // ---- 注册区状态 ----
    private EditText d1TokenEt;
    private EditText hzServerEt, hzUserEt, hzPwdEt, hzSidEt;
    private LinearLayout hzBox, d1Box;
    private int smsProvider;   // 0=D1，1=豪猪
    private EditText keywordEt, goalEt, maxEt, gamePwdEt;
    private EditText smsRealNameEt, smsRealIdCardEt;
    private TextView providerTestResult;
    private LinearLayout guestResultBox;
    private TextView guestEncView, guestRealNameView;
    private String guestFpidValue, guestEncryptedValue;
    private LinearLayout regBatchBox;
    private String regBatchId;
    private boolean regBatchTerminal;
    private boolean regPolling;

    // ---- 起号区状态 ----
    private EditText accountsInputEt;
    private EditText serverIdEt, furnaceEt, allianceEt, roleNameEt;
    private UiStyle.Switch instantDiamondSw;
    /** 领任务奖励补资源（真实轮：新号资源为 0 时必须靠领奖推进）。 */
    private UiStyle.Switch claimRewardsSw;
    /** 统帅商店钻石补缺资源（面向有钻石的号）。 */
    private UiStyle.Switch buyShopResourcesSw;
    private TextView importResultView;
    private TextView startBootBtn;
    private LinearLayout pickedListBox;
    private final List<BootstrapReferralSync.ReferralEntry> pickedReferrals = new ArrayList<>();
    private LinearLayout bootBatchBox;
    private String bootBatchId;
    private boolean bootBatchTerminal;
    private boolean bootPolling;

    // ---- 批次区状态 ----
    private LinearLayout regListHost, bootListHost;

    /** 轮询心跳：前台且有未终态批次时每 5s 拉一次（在途防重）。 */
    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            boolean any = false;
            if (regBatchId != null && !regBatchTerminal && !regPolling) {
                refreshRegBatch();
                any = true;
            }
            if (bootBatchId != null && !bootBatchTerminal && !bootPolling) {
                refreshBootBatch();
                any = true;
            }
            if (any) ui.postDelayed(this, POLL_MS);
        }
    };

    // ================= 生命周期 =================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 防旁路（对齐 ToolBoxActivity）：无起号权限（含直接 Intent 直开）直接返回，不放空壳页。
        if (!FeatureGate.visible(this, FeatureGate.Feature.ACCOUNT_FARM)) {
            UiStyle.toast(this, FeatureGate.reasonText(FeatureGate.Feature.ACCOUNT_FARM));
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

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        kickPoll();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(pollTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        ui.removeCallbacks(pollTick);
        // 凭据纪律：页面销毁即弃（输入框内容只在内存，这里显式清空持有的文本）。
        clearSensitive(d1TokenEt, hzServerEt, hzUserEt, hzPwdEt, hzSidEt,
                guestRealNameInput, guestRealIdCardInput,
                smsRealNameEt, smsRealIdCardEt);
        super.onDestroy();
    }

    private static void clearSensitive(EditText... ets) {
        for (EditText et : ets) {
            if (et != null) et.setText("");
        }
    }

    // ================= UI 骨架 =================

    private View buildUi() {
        UiComponents.Page page = UiComponents.scrollPage(
                this, "起号", "注册接码 · 批量起号 · 账号回流", this::finish, 24);
        body = page.body;

        LinearLayout segmented = UiStyle.segmented(this,
                new String[]{"注册", "起号", "批次"}, TAB_REGISTER, idx -> switchTab(idx));
        LinearLayout segWrap = new LinearLayout(this);
        segWrap.setOrientation(LinearLayout.VERTICAL);
        segWrap.addView(segmented);
        body.addView(segWrap);

        regPanel = buildRegisterPanel();
        bootPanel = buildBootstrapPanel();
        batchesPanel = buildBatchesPanel();
        body.addView(regPanel);
        body.addView(bootPanel);
        body.addView(batchesPanel);
        switchTab(TAB_REGISTER);
        return page.root;
    }

    private void switchTab(int idx) {
        regPanel.setVisibility(idx == TAB_REGISTER ? View.VISIBLE : View.GONE);
        bootPanel.setVisibility(idx == TAB_BOOTSTRAP ? View.VISIBLE : View.GONE);
        batchesPanel.setVisibility(idx == TAB_BATCHES ? View.VISIBLE : View.GONE);
        if (idx == TAB_BATCHES) loadBatchLists();
    }

    // ================= 注册区 =================

    private LinearLayout buildRegisterPanel() {
        LinearLayout panel = column();
        panel.addView(UiComponents.sectionHeader(this, "游客快速注册", "服务端代注册，token 仅返回一次"));
        panel.addView(guestCard());

        panel.addView(UiComponents.sectionHeader(this, "手机号接码注册", "接码平台取号自动完成注册/实名"));
        panel.addView(smsCard());
        panel.addView(regBatchCard());
        return panel;
    }

    /** 游客子区：express → 展示 fpid/encrypted（掩码）→ 可选实名 → 转入起号。 */
    private View guestCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(UiComponents.body(this, "一键生成游客账号；生成后可直接转入起号，"
                + "或先补实名（实名后账号长期可用）。"));

        TextView genBtn = UiStyle.primaryBtn(this, "生成游客账号", this::startExpress);
        card.addView(genBtn, rowParams(0, 8));

        guestResultBox = new LinearLayout(this);
        guestResultBox.setOrientation(LinearLayout.VERTICAL);
        guestResultBox.setVisibility(View.GONE);
        card.addView(guestResultBox, rowParams(0, 10));
        return card;
    }

    private void startExpress() {
        genGuestBtnDisable();
        VaultSync.registrationCall(this, VaultSync.registrationExpressFields(),
                (code, message, data) -> runOnUiThread(() -> {
                    genGuestBtnEnable();
                    if (code != 0) {
                        toast("注册失败：" + safeMsg(message, code));
                        return;
                    }
                    showGuestResult(data);
                }));
    }

    private void genGuestBtnDisable() {
        setEnabledDeep(guestResultBox == null ? null : (ViewGroup) guestResultBox.getParent(), false);
    }

    private void genGuestBtnEnable() {
        setEnabledDeep(guestResultBox == null ? null : (ViewGroup) guestResultBox.getParent(), true);
    }

    /** 游客注册成功：fpid + encrypted 掩码 + 实名入口 + 转入起号确认。 */
    private void showGuestResult(JSONObject data) {
        guestResultBox.removeAllViews();
        guestFpidValue = data == null ? "" : data.optString("fpid", "");
        guestEncryptedValue = data == null ? "" : data.optString("encrypted", "");
        guestResultBox.addView(UiComponents.text(this, "已生成游客账号", 14, UiStyle.OK_INK, true));
        guestEncView = UiComponents.caption(this,
                "号ID " + guestFpidValue + " · 加密串 "
                        + BootstrapForms.maskCredential(guestEncryptedValue), UiStyle.INK2);
        guestResultBox.addView(guestEncView, rowParams(0, 4));

        // 实名入口（可选）：姓名 + 身份证（password 型）→ real-name
        LinearLayout rnBox = new LinearLayout(this);
        rnBox.setOrientation(LinearLayout.VERTICAL);
        guestRealNameInput = input("实名姓名（可选）", false);
        guestRealIdCardInput = input("身份证号（可选，18 位）", true);
        rnBox.addView(guestRealNameInput, rowParams(0, 8));
        rnBox.addView(guestRealIdCardInput, rowParams(0, 8));
        rnBox.addView(UiStyle.secondaryBtn(this, "提交实名", this::startGuestRealName), rowParams(0, 8));
        guestRealNameView = UiComponents.caption(this, "", UiStyle.OK_INK);
        rnBox.addView(guestRealNameView);
        guestResultBox.addView(rnBox, rowParams(0, 8));

        // 转入起号确认（文案 §2：「已完成注册。转入起号？」）
        guestResultBox.addView(UiComponents.caption(this, "已完成注册。转入起号？", UiStyle.INK), rowParams(0, 10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(UiStyle.primaryBtn(this, "立即转入起号",
                        () -> transferToBootstrap(guestEncryptedValue)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(UiStyle.ghostBtn(this, "稍后处理", () -> row.setVisibility(View.GONE)),
                rowParams(10, 0));
        guestResultBox.addView(row, rowParams(0, 6));
        guestResultBox.setVisibility(View.VISIBLE);
        toast("已生成游客账号");
    }

    private EditText guestRealNameInput, guestRealIdCardInput;

    /** 游客实名（real-name）：sdkToken 由服务端内存凭据表按 fpid 取。 */
    private void startGuestRealName() {
        if (guestFpidValue == null || guestFpidValue.isEmpty()) return;
        String name = text(guestRealNameInput);
        String idCard = text(guestRealIdCardInput);
        if (name.isEmpty()) {
            toast("请输入姓名");
            return;
        }
        if (idCard.length() != 18) {
            toast("身份证号必须为 18 位");
            return;
        }
        Map<String, Object> f = VaultSync.registrationRealNameFields(guestFpidValue, name, idCard);
        VaultSync.registrationCall(this, f, (code, message, data) -> runOnUiThread(() -> {
            if (code != 0) {
                toast("实名失败：" + safeMsg(message, code));
                return;
            }
            boolean ok = data != null && data.optBoolean("realNameVerified", false);
            if (guestRealNameView != null) {
                guestRealNameView.setText(ok ? "实名认证已完成"
                        : "实名未完成：" + (data == null ? "" : data.optString("realNameMessage", "")));
            }
            if (ok) toast("实名认证已完成");
            clearSensitive(guestRealIdCardInput);   // 身份证即用即弃
        }));
    }

    /** 带 encrypted 切到起号区并填入账号清单（任务书 §3.2 游客子区）。 */
    private void transferToBootstrap(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            toast("该账号没有加密串，无法转入");
            return;
        }
        appendAccountLine(encrypted);
        switchTab(TAB_BOOTSTRAP);
        toast("已带入账号加密串，请在起号区继续");
    }

    // ---- 接码子区 ----

    /** 当前选中的接码平台（0=D1，1=豪猪；字段在顶部状态区声明）。 */
    private View smsCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout seg = UiStyle.segmented(this, new String[]{
                BootstrapForms.providerDisplay(BootstrapForms.PROVIDER_D1),
                BootstrapForms.providerDisplay(BootstrapForms.PROVIDER_HAOZHUMA)},
                smsProvider, idx -> {
                    smsProvider = idx;
                    d1Box.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
                    hzBox.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
                });
        card.addView(seg);

        // D1 凭据：token（password 型）
        d1Box = new LinearLayout(this);
        d1Box.setOrientation(LinearLayout.VERTICAL);
        d1TokenEt = input("D1 token", true);
        d1Box.addView(d1TokenEt, rowParams(0, 8));
        card.addView(d1Box, rowParams(0, 4));

        // 豪猪凭据：服务器地址 / 账号 / 密码 / 项目 sid（密码 password 型）
        hzBox = new LinearLayout(this);
        hzBox.setOrientation(LinearLayout.VERTICAL);
        hzBox.setVisibility(View.GONE);
        hzServerEt = input("豪猪服务器地址（可空=官方默认）", false);
        hzUserEt = input("豪猪账号 user", false);
        hzPwdEt = input("豪猪密码 password", true);
        hzSidEt = input("豪猪项目 sid", false);
        hzBox.addView(hzServerEt, rowParams(0, 8));
        hzBox.addView(hzUserEt, rowParams(0, 8));
        hzBox.addView(hzPwdEt, rowParams(0, 8));
        hzBox.addView(hzSidEt, rowParams(0, 8));
        card.addView(hzBox, rowParams(0, 4));

        keywordEt = input("短信关键词（默认：点点互动）", false);
        keywordEt.setText(BootstrapForms.DEFAULT_KEYWORD);
        card.addView(keywordEt, rowParams(0, 8));

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        goalEt = inputNum("目标成功数", "1");
        maxEt = inputNum("最多尝试号码数", "3");
        nums.addView(goalEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nums.addView(maxEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(nums, rowParams(0, 8));

        // 可选实名（留空跳过；身份证 password 型，即用即弃）
        smsRealNameEt = input("实名姓名（可选，留空跳过实名）", false);
        smsRealIdCardEt = input("实名身份证号（可选，18 位）", true);
        card.addView(smsRealNameEt, rowParams(0, 8));
        card.addView(smsRealIdCardEt, rowParams(0, 8));

        TextView pwdTitle = UiComponents.caption(this, "游戏账号密码（至少 6 位）", UiStyle.INK2);
        card.addView(pwdTitle, rowParams(0, 10));
        LinearLayout pwdRow = new LinearLayout(this);
        pwdRow.setOrientation(LinearLayout.HORIZONTAL);
        pwdRow.setGravity(Gravity.CENTER_VERTICAL);
        gamePwdEt = input("", false);
        gamePwdEt.setText(BootstrapForms.generateGamePassword());
        pwdRow.addView(gamePwdEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pwdRow.addView(UiStyle.ghostBtn(this, "重新生成", () ->
                        gamePwdEt.setText(BootstrapForms.generateGamePassword())),
                rowParams(8, 0));
        card.addView(pwdRow, rowParams(0, 4));

        providerTestResult = UiComponents.caption(this, "", UiStyle.INK2);
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.addView(UiStyle.secondaryBtn(this, "测试连接", this::startProviderTest),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionRow.addView(UiStyle.primaryBtn(this, "开始接码注册", this::startSmsBatch),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) actionRow.getChildAt(1).getLayoutParams()).leftMargin = dp(8);
        card.addView(actionRow, rowParams(0, 10));
        card.addView(providerTestResult, rowParams(0, 6));
        return card;
    }

    /** provider-test：结果按任务书 §2 文案。 */
    private void startProviderTest() {
        String cfg = currentProviderConfig();
        if (cfg == null) {
            providerTestResult.setText("请先填写接码平台凭据");
            providerTestResult.setTextColor(UiStyle.ERR_INK);
            return;
        }
        providerTestResult.setText("正在测试连接…");
        providerTestResult.setTextColor(UiStyle.INK2);
        Map<String, Object> f = VaultSync.registrationProviderTestFields(currentProviderType(), cfg);
        VaultSync.registrationCall(this, f, (code, message, data) -> runOnUiThread(() -> {
            if (code != 0) {
                providerTestResult.setText("接码平台连接测试未通过："
                        + (data != null && !data.optString("error", "").isEmpty()
                        ? data.optString("error", "") : safeMsg(message, code)));
                providerTestResult.setTextColor(UiStyle.ERR_INK);
                return;
            }
            boolean ok = data != null && data.optBoolean("ok", false);
            if (ok) {
                providerTestResult.setText("连接成功：余额 " + data.optString("balanceText", "")
                        + "，最大取号数 " + data.optInt("maxNumbers", 0)
                        + "，凭据 " + data.optString("credentialMask", ""));
                providerTestResult.setTextColor(UiStyle.OK_INK);
            } else {
                providerTestResult.setText("接码平台连接测试未通过"
                        + (data != null && !data.optString("error", "").isEmpty()
                        ? "：" + data.optString("error", "") : ""));
                providerTestResult.setTextColor(UiStyle.ERR_INK);
            }
        }));
    }

    /** 当前平台的 providerType（服务端口径 d1 / haozhuma）。 */
    private String currentProviderType() {
        return smsProvider == 1 ? BootstrapForms.PROVIDER_HAOZHUMA : BootstrapForms.PROVIDER_D1;
    }

    /** 当前平台 providerConfig JSON；必填缺失返回 null（本方法只在内存拼装）。 */
    private String currentProviderConfig() {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (smsProvider == 1) {
            inputs.put("server", text(hzServerEt));
            inputs.put("user", text(hzUserEt));
            inputs.put("password", text(hzPwdEt));
            inputs.put("sid", text(hzSidEt));
        } else {
            inputs.put("token", text(d1TokenEt));
        }
        return BootstrapForms.providerConfigJson(currentProviderType(), inputs);
    }

    /** 开始接码注册：本地校验 → batch-start → 批次视图 + 轮询。 */
    private void startSmsBatch() {
        String cfg = currentProviderConfig();
        if (cfg == null) {
            toast("请先填写接码平台凭据");
            return;
        }
        int goal = parseInt(goalEt, 1);
        int max = parseInt(maxEt, goal);
        String err = BootstrapForms.validatePhoneBatch(goal, max, text(gamePwdEt),
                text(smsRealIdCardEt));
        if (err != null) {
            toast(err);
            return;
        }
        String keyword = text(keywordEt);
        if (keyword.isEmpty()) keyword = BootstrapForms.DEFAULT_KEYWORD;
        // 可选实名（姓名+身份证都非空才带上）
        String realNameJson = "";
        if (!text(smsRealNameEt).isEmpty() && !text(smsRealIdCardEt).isEmpty()) {
            try {
                JSONObject rn = new JSONObject();
                rn.put("name", text(smsRealNameEt).trim());
                rn.put("idCard", text(smsRealIdCardEt).trim());
                realNameJson = rn.toString();
            } catch (Throwable ignored) {
            }
            clearSensitive(smsRealIdCardEt);   // 身份证提交后即弃（批次明细只回 masked）
        }
        Map<String, Object> f = VaultSync.registrationBatchStartFields(currentProviderType(),
                cfg, keyword, goal, max, null, realNameJson, text(gamePwdEt));
        VaultSync.registrationCall(this, f, (code, message, data) -> runOnUiThread(() -> {
            if (code != 0) {
                toast("批次启动失败：" + safeMsg(message, code));
                return;
            }
            toast("接码注册批次已开始");
            regBatchId = data == null ? "" : data.optString("id", "");
            regBatchTerminal = false;
            regBatchBox.setVisibility(View.VISIBLE);
            refreshRegBatch();
            kickPoll();
        }));
    }

    // ---- 注册批次视图 ----

    private View regBatchCard() {
        LinearLayout card = UiComponents.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        regBatchBox = new LinearLayout(this);
        regBatchBox.setOrientation(LinearLayout.VERTICAL);
        regBatchBox.setVisibility(View.GONE);
        card.addView(UiComponents.title(this, "接码注册批次"));
        card.addView(regBatchBox, rowParams(0, 8));
        return card;
    }

    /** 拉注册批次视图（batch-status）；终态后停止轮询。 */
    private void refreshRegBatch() {
        if (regBatchId == null || regBatchId.isEmpty() || regPolling) return;
        regPolling = true;
        VaultSync.registrationCall(this, VaultSync.registrationBatchStatusFields(regBatchId),
                (code, message, data) -> runOnUiThread(() -> {
                    regPolling = false;
                    if (code != 0) {
                        regBatchTerminal = true;   // 批次不存在/服务端未升级：停止轮询
                        if (regBatchBox != null) {
                            regBatchBox.addView(UiComponents.caption(this,
                                    "批次状态获取失败：" + safeMsg(message, code), UiStyle.ERR_INK));
                        }
                        return;
                    }
                    renderRegBatch(data);
                    if (BootstrapForms.isTerminalBatch(data == null ? "" : data.optString("state", ""))) {
                        regBatchTerminal = true;
                    }
                }));
    }

    private void renderRegBatch(JSONObject v) {
        if (regBatchBox == null || v == null) return;
        regBatchBox.removeAllViews();
        String state = v.optString("state", "");
        regBatchBox.addView(statusLine("批次 " + BootstrapForms.batchShortId(v.optString("id", "")),
                state, "成功 " + v.optInt("successCount", 0) + " / 失败 "
                + v.optInt("failCount", 0) + " / 尝试 " + v.optInt("attemptCount", 0)));
        JSONArray phones = v.optJSONArray("phones");
        if (phones != null) {
            for (int i = 0; i < phones.length(); i++) {
                JSONObject p = phones.optJSONObject(i);
                if (p == null) continue;
                regBatchBox.addView(phoneRow(p));
            }
        }
        if (!BootstrapForms.isTerminalBatch(state)) {
            TextView stop = UiStyle.dangerBtn(this, "停止批次", this::cancelRegBatch);
            stop.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            regBatchBox.addView(stop, rowParams(0, 10));
        }
    }

    /** 单号行：手机号 / 状态 / 步骤轨迹（最近 1 步）/ 错误 / 成功号「转入起号」。 */
    private View phoneRow(JSONObject p) {
        String state = p.optString("state", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(UiStyle.roundRectDp(this, UiStyle.SURFACE_2, 10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView phone = UiComponents.text(this,
                p.optString("phone", "").isEmpty() ? "（待取号）" : p.optString("phone", ""),
                13, UiStyle.INK, true);
        head.addView(phone, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(statusBadge(state));
        row.addView(head);

        String lastStep = lastStepText(p.optJSONArray("steps"));
        if (!lastStep.isEmpty()) {
            row.addView(UiComponents.caption(this, lastStep, UiStyle.INK2), rowParams(0, 3));
        }
        String error = p.optString("error", "");
        if (!error.isEmpty()) {
            TextView err = UiComponents.caption(this, "接码注册失败：" + error, UiStyle.ERR_INK);
            row.addView(err, rowParams(0, 3));
        }
        String encrypted = p.optString("encrypted", "");
        if ("SUCCESS".equals(state) && !encrypted.isEmpty() && !"null".equals(encrypted)) {
            LinearLayout ok = new LinearLayout(this);
            ok.setOrientation(LinearLayout.HORIZONTAL);
            ok.setGravity(Gravity.CENTER_VERTICAL);
            ok.addView(UiComponents.caption(this, "注册与实名已完成", UiStyle.OK_INK),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ok.addView(UiComponents.compactSecondaryButton(this, "转入起号",
                    () -> transferToBootstrap(encrypted)));
            row.addView(ok, rowParams(0, 6));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void cancelRegBatch() {
        if (regBatchId == null || regBatchId.isEmpty()) return;
        VaultSync.registrationCall(this, VaultSync.registrationBatchCancelFields(regBatchId),
                cbToastThenRefresh("已请求取消，当前号码注册完成后停止", this::refreshRegBatch));
    }

    /** 把加密串追加进起号账号清单（游客/接码成功号「转入起号」入口）。 */
    private void appendAccountLine(String line) {
        if (line == null || line.isEmpty()) return;
        String cur = accountsInputEt.getText().toString();
        accountsInputEt.setText(cur.isEmpty() ? line : cur + "\n" + line);
    }

    // ================= 起号区 =================

    private LinearLayout buildBootstrapPanel() {
        LinearLayout panel = column();

        // ① 账号来源
        panel.addView(UiComponents.sectionHeader(this, "① 账号来源", "批量加密串 / TXT"));
        LinearLayout srcCard = UiComponents.card(this);
        srcCard.setOrientation(LinearLayout.VERTICAL);
        srcCard.addView(UiComponents.body(this,
                "只复制账号凭证到起号模块；批量起号会为每个账号在指定区服新建角色。"));
        accountsInputEt = multiInput("每行一个：加密串（注册区转入/原版 TXT）", 5);
        srcCard.addView(accountsInputEt, rowParams(0, 8));
        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.addView(UiStyle.secondaryBtn(this, "解析预检", this::startImportCheck),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        srcRow.addView(UiStyle.secondaryBtn(this, "拉取服务器待回流号", this::showReferralPicker),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) srcRow.getChildAt(1).getLayoutParams()).leftMargin = dp(8);
        srcCard.addView(srcRow, rowParams(0, 8));
        importResultView = UiComponents.caption(this, "", UiStyle.INK2);
        srcCard.addView(importResultView, rowParams(0, 4));
        pickedListBox = new LinearLayout(this);
        pickedListBox.setOrientation(LinearLayout.VERTICAL);
        srcCard.addView(pickedListBox, rowParams(0, 4));
        panel.addView(srcCard);

        // ② 配置
        panel.addView(UiComponents.sectionHeader(this, "② 配置", "区服 / 目标大熔炉 / 角色名"));
        LinearLayout cfgCard = UiComponents.card(this);
        cfgCard.setOrientation(LinearLayout.VERTICAL);
        serverIdEt = inputNum("区服号（正整数）", "");
        furnaceEt = inputNum("目标大熔炉（1–9 级）", "9");
        cfgCard.addView(serverIdEt, rowParams(0, 0));
        cfgCard.addView(furnaceEt, rowParams(0, 8));
        allianceEt = input("联盟名称（可空）", false);
        cfgCard.addView(allianceEt, rowParams(0, 8));
        roleNameEt = input("角色名（可空，≤16 字）", false);
        cfgCard.addView(roleNameEt, rowParams(0, 8));
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.addView(UiComponents.body(this, "钻石即时升级（默认开）"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        instantDiamondSw = new UiStyle.Switch(this, true, null);
        swRow.addView(instantDiamondSw);
        cfgCard.addView(swRow, rowParams(0, 8));

        // 资源补充开关（2026-11-10 真实轮新增：新号资源为 0 且背包无资源包）
        LinearLayout claimRow = new LinearLayout(this);
        claimRow.setOrientation(LinearLayout.HORIZONTAL);
        claimRow.setGravity(Gravity.CENTER_VERTICAL);
        claimRow.addView(UiComponents.body(this, "领任务奖励补资源"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        claimRewardsSw = new UiStyle.Switch(this, true, null);
        claimRow.addView(claimRewardsSw);
        cfgCard.addView(claimRow, rowParams(0, 8));

        LinearLayout shopRow = new LinearLayout(this);
        shopRow.setOrientation(LinearLayout.HORIZONTAL);
        shopRow.setGravity(Gravity.CENTER_VERTICAL);
        shopRow.addView(UiComponents.body(this, "商店钻石补缺资源（需有钻石）"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buyShopResourcesSw = new UiStyle.Switch(this, false, null);
        shopRow.addView(buyShopResourcesSw);
        cfgCard.addView(shopRow, rowParams(0, 8));
        panel.addView(cfgCard);

        // ③ 执行
        panel.addView(UiComponents.sectionHeader(this, "③ 执行", "5 秒自动刷新"));
        LinearLayout runCard = UiComponents.card(this);
        runCard.setOrientation(LinearLayout.VERTICAL);
        startBootBtn = UiStyle.primaryBtn(this, "开始批量起号", this::startBootstrapBatch);
        runCard.addView(startBootBtn);
        bootBatchBox = new LinearLayout(this);
        bootBatchBox.setOrientation(LinearLayout.VERTICAL);
        bootBatchBox.setVisibility(View.GONE);
        runCard.addView(bootBatchBox, rowParams(0, 10));
        panel.addView(runCard);
        return panel;
    }

    /** import：服务端解析回显（成功清单 + 错误行）。 */
    private void startImportCheck() {
        List<String> lines = BootstrapForms.parseAccountLines(accountsInputEt.getText().toString());
        if (lines.isEmpty()) {
            importResultView.setText("请先粘贴账号清单（每行一个加密串）");
            importResultView.setTextColor(UiStyle.ERR_INK);
            return;
        }
        importResultView.setText("解析中…");
        importResultView.setTextColor(UiStyle.INK2);
        VaultSync.bootstrapCall(this, VaultSync.bootstrapImportFields(lines),
                (code, message, data) -> runOnUiThread(() -> {
                    if (code != 0) {
                        importResultView.setText("解析失败：" + safeMsg(message, code));
                        importResultView.setTextColor(UiStyle.ERR_INK);
                        return;
                    }
                    int count = data.optInt("count", 0);
                    int errCount = data.optInt("errorCount", 0);
                    importResultView.setText("解析成功 " + count + " 个账号"
                            + (errCount > 0 ? "，错误 " + errCount + " 行" : ""));
                    importResultView.setTextColor(errCount > 0 ? UiStyle.WARN_INK : UiStyle.OK_INK);
                    if (errCount > 0) {
                        JSONArray errors = data.optJSONArray("errors");
                        if (errors != null && errors.length() > 0) {
                            importResultView.setText(importResultView.getText()
                                    + "：" + errors.optString(0, ""));
                        }
                    }
                }));
    }

    /** referral-list → 多选弹窗 → 勾选导入（入号库 + 标记 + 带入起号清单）。 */
    private void showReferralPicker() {
        toast("正在拉取待回流账号…");
        VaultSync.bootstrapCall(this, VaultSync.bootstrapReferralListFields(),
                (code, message, data) -> runOnUiThread(() -> {
                    if (code != 0) {
                        toast("拉取失败：" + safeMsg(message, code));
                        return;
                    }
                    List<BootstrapReferralSync.ReferralEntry> pending =
                            BootstrapReferralSync.parseReferralList(data);
                    if (pending.isEmpty()) {
                        toast("暂无待回流账号");
                        return;
                    }
                    pickReferrals(pending);
                }));
    }

    private void pickReferrals(List<BootstrapReferralSync.ReferralEntry> pending) {
        final int n = pending.size();
        String[] labels = new String[n];
        final boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            BootstrapReferralSync.ReferralEntry e = pending.get(i);
            labels[i] = BootstrapReferralSync.displayLabel(e)
                    + (e.furnaceLevel > 0 ? "（大熔炉 " + e.furnaceLevel + " 级）" : "")
                    + (e.serverId > 0 ? " · 区服 " + e.serverId : "");
            checked[i] = true;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择要导入的待回流账号（" + n + "）")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("导入", (d, w) -> {
                    List<BootstrapReferralSync.ReferralEntry> picked = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        if (checked[i]) picked.add(pending.get(i));
                    }
                    if (picked.isEmpty()) return;
                    // 入号库 + referral-pull 标记（不自动全量 sync）
                    BootstrapReferralSync.mergeAndMark(this, picked, null, (c, m, dt) ->
                            runOnUiThread(() -> {
                                if (c != 0) {
                                    toast("回流导入失败：" + safeMsg(m, c));
                                    return;
                                }
                                int added = dt == null ? 0 : dt.optInt("added", 0);
                                toast(added > 0
                                        ? "已并入号库 " + added + " 个账号，稍后请在账号页手动同步"
                                        : "所选账号已在号库，已标记完成回流");
                            }));
                    // 同时带入起号清单（batch-start 走 fpid+sdkToken 对象）
                    for (BootstrapReferralSync.ReferralEntry e : picked) {
                        if (!containsReferral(e)) pickedReferrals.add(e);
                    }
                    renderPickedList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean containsReferral(BootstrapReferralSync.ReferralEntry e) {
        for (BootstrapReferralSync.ReferralEntry x : pickedReferrals) {
            if (x.fpid.equals(e.fpid)) return true;
        }
        return false;
    }

    private void renderPickedList() {
        pickedListBox.removeAllViews();
        if (pickedReferrals.isEmpty()) return;
        pickedListBox.addView(UiComponents.caption(this,
                "已带入起号清单 " + pickedReferrals.size() + " 个（来自服务器回流）", UiStyle.BRAND_TEXT));
    }

    /** 开始批量起号：校验 → batch-start → 批次视图 + 轮询。 */
    private void startBootstrapBatch() {
        List<String> lines = BootstrapForms.parseAccountLines(accountsInputEt.getText().toString());
        int target = (int) BootstrapForms.parsePositiveLong(text(furnaceEt));
        if (target <= 0) target = 9;
        String err = BootstrapForms.validateBootstrapBatch(text(serverIdEt), target, text(roleNameEt));
        if (err != null) {
            toast(err);
            return;
        }
        String accountsJson = BootstrapForms.accountsJson(lines, pickedReferrals);
        if (accountsJson.isEmpty()) {
            toast("请先填入账号清单或勾选回流账号");
            return;
        }
        Map<String, Object> f = VaultSync.bootstrapBatchStartFields(
                BootstrapForms.parsePositiveLong(text(serverIdEt)), target,
                text(allianceEt), text(roleNameEt), instantDiamondSw.isOn(),
                claimRewardsSw.isOn(), buyShopResourcesSw.isOn(), accountsJson);
        final long sid = BootstrapForms.parsePositiveLong(text(serverIdEt));
        final int targetLevel = target;
        startBootBtn.setEnabled(false);
        VaultSync.bootstrapCall(this, f, (code, message, data) -> runOnUiThread(() -> {
            startBootBtn.setEnabled(true);
            if (code != 0) {
                toast("批量起号失败：" + safeMsg(message, code));
                return;
            }
            bootBatchId = data == null ? "" : data.optString("id", "");
            bootBatchTerminal = false;
            bootBatchBox.setVisibility(View.VISIBLE);
            toast("开始批量起号：区服 " + sid + "，目标大熔炉 " + targetLevel + " 级");
            refreshBootBatch();
            kickPoll();
        }));
    }

    // ---- 起号批次视图 ----

    private void refreshBootBatch() {
        if (bootBatchId == null || bootBatchId.isEmpty() || bootPolling) return;
        bootPolling = true;
        VaultSync.bootstrapCall(this, VaultSync.bootstrapBatchStatusFields(bootBatchId),
                (code, message, data) -> runOnUiThread(() -> {
                    bootPolling = false;
                    if (code != 0) {
                        bootBatchTerminal = true;
                        if (bootBatchBox != null) {
                            bootBatchBox.addView(UiComponents.caption(this,
                                    "批次状态获取失败：" + safeMsg(message, code), UiStyle.ERR_INK));
                        }
                        return;
                    }
                    renderBootBatch(data);
                    if (BootstrapForms.isTerminalBatch(data == null ? "" : data.optString("state", ""))) {
                        bootBatchTerminal = true;
                    }
                }));
    }

    private void renderBootBatch(JSONObject v) {
        if (bootBatchBox == null || v == null) return;
        bootBatchBox.removeAllViews();
        String state = v.optString("state", "");
        String msg = v.optString("message", "");
        regBatchStyleSummary(bootBatchBox, "起号批次 " + BootstrapForms.batchShortId(v.optString("id", "")),
                state, "成功 " + v.optInt("successCount", 0) + " / 失败 " + v.optInt("failCount", 0)
                        + " / 区服 " + v.optLong("serverId", 0) + " · 目标大熔炉 "
                        + v.optInt("targetFurnaceLevel", 0) + " 级", msg);
        JSONArray accounts = v.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.optJSONObject(i);
                if (a == null) continue;
                bootBatchBox.addView(accountRow(a));
            }
        }
        if (!BootstrapForms.isTerminalBatch(state)) {
            TextView stop = UiStyle.dangerBtn(this, "停止批量起号", this::cancelBootBatch);
            stop.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            bootBatchBox.addView(stop, rowParams(0, 10));
        }
    }

    /** 起号单号行：标签 / 状态 / 错误摘要 / 熔炉等级；SUCCESS 显示「已转入日常（待回流）」。 */
    private View accountRow(JSONObject a) {
        String state = a.optString("state", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(UiStyle.roundRectDp(this, UiStyle.SURFACE_2, 10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiComponents.text(this, a.optString("label", ""), 13, UiStyle.INK, true);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(statusBadge(state));
        row.addView(head);

        StringBuilder sub = new StringBuilder();
        if (a.optLong("furnaceLevel", 0) > 0) {
            sub.append("大熔炉 ").append(a.optLong("furnaceLevel", 0)).append(" 级");
        }
        if (a.optLong("uid", 0) > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append("角色 ").append(a.optLong("uid", 0));
        }
        if (sub.length() > 0) {
            row.addView(UiComponents.caption(this, sub.toString(), UiStyle.INK2), rowParams(0, 3));
        }
        String steps = a.optString("steps", "");
        if (!steps.isEmpty()) {
            row.addView(UiComponents.caption(this, steps, UiStyle.INK3), rowParams(0, 3));
        }
        String error = a.optString("error", "");
        if (!error.isEmpty()) {
            row.addView(UiComponents.caption(this, error, UiStyle.ERR_INK), rowParams(0, 3));
        }
        if ("SUCCESS".equals(state)) {
            row.addView(UiComponents.caption(this, "已转入日常（待回流）", UiStyle.OK_INK), rowParams(0, 3));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void cancelBootBatch() {
        if (bootBatchId == null || bootBatchId.isEmpty()) return;
        VaultSync.bootstrapCall(this, VaultSync.bootstrapBatchCancelFields(bootBatchId),
                cbToastThenRefresh("已请求取消，当前账号起号自然结束后停止", this::refreshBootBatch));
    }

    // ================= 批次区（batch-list + 详情） =================

    private LinearLayout buildBatchesPanel() {
        LinearLayout panel = column();
        panel.addView(UiComponents.sectionHeader(this, "注册批次", "含历史，点击查看详情"));
        regListHost = new LinearLayout(this);
        regListHost.setOrientation(LinearLayout.VERTICAL);
        panel.addView(regListHost);

        panel.addView(UiComponents.sectionHeader(this, "起号批次", "含历史，点击查看详情"));
        bootListHost = new LinearLayout(this);
        bootListHost.setOrientation(LinearLayout.VERTICAL);
        panel.addView(bootListHost);
        return panel;
    }

    private void loadBatchLists() {
        VaultSync.registrationCall(this, VaultSync.batchListFields(),
                (code, message, data) -> runOnUiThread(() -> {
                    if (regListHost == null) return;
                    regListHost.removeAllViews();
                    if (code != 0) {
                        regListHost.addView(listErrorView(message, code));
                        return;
                    }
                    renderBatchSummaries(regListHost, data, true);
                }));
        VaultSync.bootstrapCall(this, VaultSync.batchListFields(),
                (code, message, data) -> runOnUiThread(() -> {
                    if (bootListHost == null) return;
                    bootListHost.removeAllViews();
                    if (code != 0) {
                        bootListHost.addView(listErrorView(message, code));
                        return;
                    }
                    renderBatchSummaries(bootListHost, data, false);
                }));
    }

    /** batch-list 摘要行（注册/起号两套字段共用一个渲染，reg=true 用注册字段）。 */
    private void renderBatchSummaries(LinearLayout host, JSONObject data, boolean reg) {
        JSONArray batches = data == null ? null : data.optJSONArray("batches");
        if (batches == null || batches.length() == 0) {
            host.addView(UiComponents.caption(this, "暂无批次记录", UiStyle.INK3));
            return;
        }
        for (int i = 0; i < batches.length(); i++) {
            JSONObject b = batches.optJSONObject(i);
            if (b == null) continue;
            String id = b.optString("id", "");
            String state = b.optString("state", "");
            String sub = reg
                    ? "成功 " + b.optInt("successCount", 0) + " / 失败 " + b.optInt("failCount", 0)
                    + " / 目标 " + b.optInt("goalCount", 0) + " 号"
                    : "成功 " + b.optInt("successCount", 0) + " / 失败 " + b.optInt("failCount", 0)
                    + " / 区服 " + b.optLong("serverId", 0);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE, 12));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(UiComponents.text(this, "批次 " + BootstrapForms.batchShortId(id), 13,
                    UiStyle.INK, true));
            col.addView(UiComponents.caption(this, sub, UiStyle.INK2), rowParams(0, 2));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(statusBadge(state));
            row.setOnClickListener(v -> openBatchDetail(id, reg));
            UiStyle.press(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            host.addView(row);
        }
    }

    /** 点击批次摘要 → batch-status → 详情弹窗（每号一行文本）。 */
    private void openBatchDetail(String batchId, boolean reg) {
        toast("正在获取批次详情…");
        VaultSync.Callback cb = (code, message, data) -> runOnUiThread(() -> {
            if (code != 0 || data == null) {
                toast("获取详情失败：" + safeMsg(message, code));
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("状态：").append(BootstrapForms.batchStateText(data.optString("state", "")))
                    .append("\n成功 ").append(data.optInt("successCount", 0))
                    .append(" / 失败 ").append(data.optInt("failCount", 0));
            String msg = data.optString("message", "");
            if (!msg.isEmpty()) sb.append("\n").append(msg);
            if (reg) {
                JSONArray phones = data.optJSONArray("phones");
                if (phones != null) {
                    for (int i = 0; i < phones.length(); i++) {
                        JSONObject p = phones.optJSONObject(i);
                        if (p == null) continue;
                        sb.append("\n").append(p.optString("phone", "").isEmpty()
                                ? "（待取号）" : p.optString("phone", ""))
                                .append(" · ").append(BootstrapForms.phoneStateText(p.optString("state", "")));
                        String err = p.optString("error", "");
                        if (!err.isEmpty()) sb.append(" · ").append(err);
                    }
                }
            } else {
                JSONArray accounts = data.optJSONArray("accounts");
                if (accounts != null) {
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject a = accounts.optJSONObject(i);
                        if (a == null) continue;
                        sb.append("\n").append(a.optString("label", ""))
                                .append(" · ").append(BootstrapForms.accountStateText(a.optString("state", "")));
                        if (a.optLong("furnaceLevel", 0) > 0) {
                            sb.append(" · 大熔炉 ").append(a.optLong("furnaceLevel", 0));
                        }
                        String err = a.optString("error", "");
                        if (!err.isEmpty()) sb.append(" · ").append(err);
                    }
                }
            }
            ScrollView sc = new ScrollView(this);
            TextView tv = UiComponents.body(this, sb.toString());
            tv.setPadding(dp(18), dp(12), dp(18), dp(6));
            sc.addView(tv);
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setTitle("批次 " + BootstrapForms.batchShortId(batchId))
                    .setView(sc)
                    .setPositiveButton("关闭", null)
                    .show();
        });
        if (reg) {
            VaultSync.registrationCall(this, VaultSync.registrationBatchStatusFields(batchId), cb);
        } else {
            VaultSync.bootstrapCall(this, VaultSync.bootstrapBatchStatusFields(batchId), cb);
        }
    }

    // ================= 公共小件 =================

    /** 统一结果回调：toast + 成功后刷新。 */
    private VaultSync.Callback cbToastThenRefresh(String okMsg, Runnable refresh) {
        return (code, message, data) -> runOnUiThread(() -> {
            if (code == 0) {
                toast(okMsg.isEmpty() ? (data == null ? "" : data.optString("message", "")) : okMsg);
            } else {
                toast("失败：" + safeMsg(message, code));
            }
            if (refresh != null) refresh.run();
        });
    }

    /** 状态行：左标题 + 状态 chip + 副题统计。 */
    private void regBatchStyleSummary(LinearLayout host, String title, String state,
                                      String sub, String message) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiComponents.text(this, title, 14, UiStyle.INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(statusBadge(state));
        host.addView(head);
        host.addView(UiComponents.caption(this, sub, UiStyle.INK2), rowParams(0, 3));
        if (message != null && !message.isEmpty()) {
            // 汇总文案按批次状态着色：失败红 / 完成绿 / 进行中与已取消中性
            int ink = "FAILED".equals(state) ? UiStyle.ERR_INK
                    : ("COMPLETED".equals(state) ? UiStyle.OK_INK : UiStyle.INK2);
            host.addView(UiComponents.caption(this, message, ink), rowParams(0, 3));
        }
    }

    private View statusLine(String title, String state, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        regBatchStyleSummary(box, title, state, sub, "");
        return box;
    }

    /** 状态 → 胶囊 chip（文案/色系映射统一走 {@link BootstrapForms}，纯逻辑单测锁定）。 */
    private View statusBadge(String state) {
        return UiStyle.badge(this, BootstrapForms.badgeType(state),
                BootstrapForms.stateText(state));
    }

    /** steps 数组 → 最近一步文本（「步骤 ok/失败：message」）。 */
    private String lastStepText(JSONArray steps) {
        if (steps == null || steps.length() == 0) return "";
        JSONObject s = steps.optJSONObject(steps.length() - 1);
        if (s == null) return "";
        String step = s.optString("step", "");
        boolean ok = s.optBoolean("ok", false);
        String message = s.optString("message", "");
        return (ok ? "✓ " : "✗ ") + step + (message.isEmpty() ? "" : "：" + message);
    }

    private View listErrorView(String message, int code) {
        TextView v = UiComponents.caption(this, "批次列表获取失败：" + safeMsg(message, code)
                + "，点击重试", UiStyle.ERR_INK);
        v.setOnClickListener(x -> loadBatchLists());
        return v;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /** 规范输入框（白底 + LINE_STRONG 描边；password=true 密文）。 */
    private EditText input(String hint, boolean password) {
        EditText et = UiStyle.searchInput(this, hint);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return et;
    }

    /** 数字输入框。 */
    private EditText inputNum(String hint, String prefill) {
        EditText et = UiStyle.searchInput(this, hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (prefill != null && !prefill.isEmpty()) et.setText(prefill);
        return et;
    }

    /** 多行输入框（批量加密串 / TXT）。 */
    private EditText multiInput(String hint, int minLines) {
        EditText et = UiStyle.searchInput(this, hint);
        et.setMinLines(minLines);
        et.setGravity(Gravity.TOP);
        et.setScrollContainer(true);
        return et;
    }

    private LinearLayout.LayoutParams rowParams(int leftMargin, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(leftMargin);
        lp.topMargin = dp(topMargin);
        return lp;
    }

    /** 深度启用/禁用（express 请求期间防重复点击）。 */
    private static void setEnabledDeep(ViewGroup root, boolean enabled) {
        if (root == null) return;
        root.setEnabled(enabled);
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            c.setEnabled(enabled);
            if (c instanceof ViewGroup) setEnabledDeep((ViewGroup) c, enabled);
        }
    }

    private String text(EditText et) {
        return et == null ? "" : et.getText().toString().trim();
    }

    private int parseInt(EditText et, int def) {
        try {
            return Integer.parseInt(text(et));
        } catch (Throwable t) {
            return def;
        }
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void toast(String s) {
        UiStyle.toast(this, s);
    }

    private String safeMsg(String message, int code) {
        return message == null || message.isEmpty() ? String.valueOf(code) : message;
    }

    /** 轮询挂起（有未终态批次时）。 */
    private void kickPoll() {
        ui.removeCallbacks(pollTick);
        boolean need = (regBatchId != null && !regBatchTerminal)
                || (bootBatchId != null && !bootBatchTerminal);
        if (resumed && need) ui.postDelayed(pollTick, POLL_MS);
    }
}
