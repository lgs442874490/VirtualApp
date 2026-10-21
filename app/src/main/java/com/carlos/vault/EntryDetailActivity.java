package com.carlos.vault;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.carlos.vault.daily.RoleDisplay;
import com.carlos.vault.daily.RoleStore;
import com.carlos.vault.gameupdate.GameApkDownloader;
import com.carlos.vault.gameupdate.GameApkVerifier;
import com.carlos.vault.gameupdate.GameClientUpdateConfig;
import com.carlos.vault.gameupdate.GameRecloneFlow;
import com.carlos.vault.gameupdate.GameVersionCheck;
import com.carlos.vault.gameupdate.LocalApkScanner;
import com.carlos.vault.license.LicenseManager;
import com.carlos.vault.license.LicenseUi;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 入口管理页：卡片式布局 */
public class EntryDetailActivity extends Activity {

    public static final String EXTRA_ID = "entry_id";

    private VaultEngine engine;
    private AccountStore store;
    private AccountStore.Account acc;
    private TextView modeView;
    private TextView infoView;
    private TextView profileMeta;
    private TextView profileBadge;
    private TextView roleTitle;
    private LinearLayout roleBox;
    /** 「启动分身」按钮下方的分身游戏版本状态行（M3） */
    private TextView launchStatusView;
    /** 最新版本配置（assets game_client.json → 内置缺省；apkUrl 可被 Intent extra 覆盖） */
    private GameClientUpdateConfig updateCfg;
    /** 本地 APK 更新的 READ_EXTERNAL_STORAGE 运行时请求码（M3b） */
    private static final int REQ_READ_STORAGE_LOCAL_APK = 4101;
    /** 「去设置」授权返回后自动继续本地更新流程 */
    private boolean resumeLocalApkUpdateAfterSettings;
    /** onResume 重新克隆提示的「去重」标记：同一原生版本只提示一次（原生再升级才重新提示） */
    private long reclonePromptShownForNativeVc;
    private final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new VaultEngine(this);
        store = new AccountStore(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        for (AccountStore.Account a : store.all()) {
            if (a.id.equals(id)) { acc = a; break; }
        }
        if (acc == null) { finish(); return; }
        // 测试可传 game_update_apk_url 覆盖下载源（指向本地/自建源，不必真下 888MB）
        updateCfg = GameClientUpdateConfig.load(this,
                getIntent().getStringExtra(GameClientUpdateConfig.EXTRA_OVERRIDE_APK_URL));
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 「去设置」授权后返回：权限已给 → 自动接着扫本地包；仍未给 → 下次点击再走引导
        if (resumeLocalApkUpdateAfterSettings) {
            resumeLocalApkUpdateAfterSettings = false;
            boolean granted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) continueLocalApkUpdate();
        }
        refreshInfo();
        // M3c：从商店/桌面回到本页时，若原生游戏已比分身新 → 提示重新克隆
        maybePromptReclone();
    }

    private void buildUi() {
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiStyle.BG_V2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(24));

        // 顶部标题栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(14), dp(14), dp(8));
        android.widget.FrameLayout backWrap = new android.widget.FrameLayout(this);
        backWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.BRAND_TEXT, 22),
                new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(backWrap);
        backWrap.setOnClickListener(v -> finish());
        topBar.addView(backWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText("账号详情");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        android.widget.FrameLayout moreWrap = new android.widget.FrameLayout(this);
        moreWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.MORE, UiStyle.INK2, 20),
                new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        UiStyle.press(moreWrap);
        moreWrap.setOnClickListener(v -> new AlertDialog.Builder(this,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setItems(new String[]{"移动到分组", "删除本入口"}, (d, w) -> {
                    if (w == 0) showMoveToGroupDialog();
                    else confirmDelete();
                }).show());
        topBar.addView(moreWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(topBar);

        // 资料卡
        LinearLayout headCard = new LinearLayout(this);
        headCard.setOrientation(LinearLayout.HORIZONTAL);
        headCard.setGravity(Gravity.CENTER_VERTICAL);
        headCard.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 16, UiStyle.LINE));
        headCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hclp.setMargins(dp(18), dp(6), dp(18), 0);
        headCard.setLayoutParams(hclp);
        headCard.addView(UiStyle.snowAvatar(this, 64, acc.id),
                new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.setPadding(dp(14), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(acc.label);
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        headText.addView(name);
        profileMeta = new TextView(this);
        profileMeta.setTextSize(12);
        profileMeta.setTextColor(UiStyle.INK2);
        profileMeta.setPadding(0, dp(5), 0, 0);
        headText.addView(profileMeta);
        profileBadge = UiStyle.badge(this, "ok", "已保存登录 · 备有 token");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        headText.addView(profileBadge, blp);
        headCard.addView(headText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(headCard);

        // 启动分身
        LinearLayout launchBtn = new LinearLayout(this);
        launchBtn.setOrientation(LinearLayout.HORIZONTAL);
        launchBtn.setGravity(Gravity.CENTER);
        launchBtn.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 16));
        launchBtn.setPadding(0, dp(15), 0, dp(15));
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblp.setMargins(dp(18), dp(14), dp(18), 0);
        launchBtn.setLayoutParams(lblp);
        launchBtn.addView(new UiStyle.Icon(this, UiStyle.Icon.PLAY, UiStyle.WHITE, 18),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView launchTx = new TextView(this);
        launchTx.setText("启动分身");
        launchTx.setTextSize(16);
        launchTx.setTypeface(Typeface.DEFAULT_BOLD);
        launchTx.setTextColor(Color.WHITE);
        launchTx.setPadding(dp(8), 0, 0, 0);
        launchBtn.addView(launchTx);
        UiStyle.press(launchBtn);
        launchBtn.setOnClickListener(v -> doLaunch());
        root.addView(launchBtn);

        // 启动分身状态行：分身游戏当前版本 / 是否需要更新（M3 契约：按钮下方状态文案）
        launchStatusView = new TextView(this);
        launchStatusView.setTextSize(11.5f);
        launchStatusView.setTextColor(UiStyle.INK2);
        launchStatusView.setPadding(dp(20), dp(7), dp(18), 0);
        root.addView(launchStatusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 登录并运行自动化（服务器执行）
        LinearLayout autoBtn = new LinearLayout(this);
        autoBtn.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn.setGravity(Gravity.CENTER);
        autoBtn.setBackground(UiStyle.roundRectStroke(this, Color.WHITE, 16, UiStyle.BRAND_300));
        autoBtn.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ablp.setMargins(dp(18), dp(10), dp(18), 0);
        autoBtn.setLayoutParams(ablp);
        autoBtn.addView(new UiStyle.Icon(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT, 18),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView autoTx = new TextView(this);
        autoTx.setText("登录并运行自动化");
        autoTx.setTextSize(15);
        autoTx.setTypeface(Typeface.DEFAULT_BOLD);
        autoTx.setTextColor(UiStyle.BRAND_TEXT);
        autoTx.setPadding(dp(8), 0, 0, 0);
        autoBtn.addView(autoTx);
        UiStyle.press(autoBtn);
        autoBtn.setOnClickListener(v -> loginAndRunAutomation(autoBtn, autoTx));
        root.addView(autoBtn);

        // 角色卡
        LinearLayout roleCard = UiStyle.outlineCard(this);
        roleCard.setOrientation(LinearLayout.VERTICAL);
        roleCard.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rclp.setMargins(dp(18), dp(14), dp(18), 0);
        roleCard.setLayoutParams(rclp);
        LinearLayout roleHead = new LinearLayout(this);
        roleHead.setOrientation(LinearLayout.HORIZONTAL);
        roleHead.setGravity(Gravity.CENTER_VERTICAL);
        roleTitle = new TextView(this);
        roleTitle.setTextSize(16);
        roleTitle.setTypeface(Typeface.DEFAULT_BOLD);
        roleTitle.setTextColor(UiStyle.INK);
        roleHead.addView(roleTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView roleManage = new TextView(this);
        roleManage.setText("管理");
        roleManage.setTextSize(12.5f);
        roleManage.setTypeface(Typeface.DEFAULT_BOLD);
        roleManage.setTextColor(UiStyle.BRAND_TEXT);
        UiStyle.press(roleManage);
        // 第二轮 UI：复用账号页同一个角色管理弹层（RoleManagerSheet）——
        // 未加入工作区的角色在这里打开开关，详情页角色盒只渲染工作区角色
        roleManage.setOnClickListener(v -> RoleManagerSheet.show(this, acc,
                RoleStore.roles(this, acc.fpid), this::refreshInfo));
        roleHead.addView(roleManage);
        roleCard.addView(roleHead);
        roleBox = new LinearLayout(this);
        roleBox.setOrientation(LinearLayout.VERTICAL);
        roleCard.addView(roleBox);
        root.addView(roleCard);

        // 登录数据
        LinearLayout loginCard = UiStyle.outlineCard(this);
        loginCard.setOrientation(LinearLayout.VERTICAL);
        loginCard.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lclp.setMargins(dp(18), dp(14), dp(18), 0);
        loginCard.setLayoutParams(lclp);
        loginCard.addView(sectionTitle("登录数据"));
        loginCard.addView(actionRow(UiStyle.Icon.USER, "设置 token",
                "粘贴：号 ID----token", this::showTokenDialog));
        loginCard.addView(actionRow(UiStyle.Icon.DOC, "提取并复制当前 token",
                "读取游戏当前登录，复制为 号ID----token", this::confirmExtractToken));
        loginCard.addView(actionRow(UiStyle.Icon.CLOUD, "保存当前登录现场",
                "把登录态存进本入口快照，切号不丢", this::captureSnapshot));
        root.addView(loginCard);

        // 入口管理
        LinearLayout mgmtCard = UiStyle.outlineCard(this);
        mgmtCard.setOrientation(LinearLayout.VERTICAL);
        mgmtCard.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mclp.setMargins(dp(18), dp(14), dp(18), 0);
        mgmtCard.setLayoutParams(mclp);
        mgmtCard.addView(sectionTitle("入口管理"));
        mgmtCard.addView(actionRow(UiStyle.Icon.DOC, "修改备注名", "", this::showRenameDialog));
        mgmtCard.addView(actionRow(UiStyle.Icon.GRID, "移动到分组", groupDisplay(),
                this::showMoveToGroupDialog));
        mgmtCard.addView(actionRow(UiStyle.Icon.TRASH, "删除本入口", "", UiStyle.ERR_INK,
                this::confirmDelete));
        root.addView(mgmtCard);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refreshInfo();
    }

    /** 详情页行：图标盒 + 标题/副题 + 右箭头。 */
    private LinearLayout actionRow(int iconType, String title, String sub, Runnable onClick) {
        return actionRow(iconType, title, sub, UiStyle.INK, onClick);
    }

    private LinearLayout actionRow(int iconType, String title, String sub, int titleColor,
                                   Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        row.addView(UiStyle.iconTile(this, iconType,
                titleColor == UiStyle.ERR_INK ? UiStyle.ERR_INK : UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 36, 18, 10),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(titleColor);
        mid.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(UiStyle.INK2);
            s.setPadding(0, dp(3), 0, 0);
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

    /**
     * 角色盒（第二轮 UI）：只渲染<b>已加入工作区</b>（enabled）的角色——
     * 每行 = 头像 + 昵称 + 标准元数据行（UID/城堡/战力，缺失「未同步」）+
     * 统一状态行 + 徽章（当前角色/工作区）+「登录」（以该角色模拟登录在线）。
     *
     * <p>未加入工作区的角色不在此出现（去右上「管理」里开开关）；空态给可点提示。
     */
    private void rebuildRoles() {
        if (roleBox == null) return;
        roleBox.removeAllViews();
        List<RoleStore.Role> all = RoleStore.roles(this, acc.fpid);
        List<RoleStore.Role> roles = RoleDisplay.workspaceRoles(all,
                r -> ((RoleStore.Role) r).enabled);
        RoleStore.ActiveRole active = RoleStore.activeRoleEx(this, acc.fpid);
        if (roleTitle != null) roleTitle.setText("工作区角色（" + roles.size() + "）");
        if (roles.isEmpty()) {
            TextView tip = new TextView(this);
            tip.setText(all.isEmpty()
                    ? "暂无角色数据（登录一次自动发现）"
                    : "还没有角色加入工作区 —— 点右上「管理」添加");
            tip.setTextSize(12);
            tip.setTextColor(UiStyle.INK2);
            tip.setPadding(dp(2), dp(10), 0, dp(10));
            if (!all.isEmpty()) {
                UiStyle.press(tip);
                tip.setOnClickListener(v -> RoleManagerSheet.show(this, acc, all, this::refreshInfo));
            }
            roleBox.addView(tip);
            return;
        }
        for (final RoleStore.Role r : roles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            // 头像种子带 uid：同服两个角色头像不再相同
            row.addView(UiStyle.snowAvatar(this, 44, acc.id + ":" + r.serverid + ":" + r.uid),
                    new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            TextView nm = new TextView(this);
            nm.setText(RoleDisplay.roleName(r.name));
            nm.setTextSize(15);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(UiStyle.INK);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(nm);
            TextView sub = new TextView(this);
            // 标准展示：K888 · UID 88280792 · 城堡 70 级 · 战力 20784万（缺失段「未同步」）
            sub.setText(RoleDisplay.roleMetaLine(r.serverid, r.uid, r.level, r.power));
            sub.setTextSize(11.5f);
            sub.setTextColor(UiStyle.INK2);
            sub.setPadding(0, dp(3), 0, 0);
            mid.addView(sub);
            // 统一状态行（服务器模式 cachedStatusOf / 本地模式会话快照；与账号页同源）
            TextView stv = new TextView(this);
            String[] st = RoleManagerSheet.roleStatusLabel(this, acc, r);
            stv.setText(st[0]);
            stv.setTextSize(10.5f);
            stv.setTypeface(Typeface.DEFAULT_BOLD);
            stv.setTextColor("on".equals(st[1]) ? UiStyle.OK_INK : UiStyle.INK3);
            stv.setPadding(0, dp(3), 0, 0);
            mid.addView(stv);
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // 当前角色判定按复合身份 (sid,uid)：同服两个角色不会同时被打勾
            boolean isActive = r.serverid == active.sid
                    && (active.uid <= 0 || r.uid == active.uid);
            row.addView(UiStyle.badge(this, isActive ? "wait" : "idle",
                    isActive ? "当前角色" : "工作区"));
            // 「登录」= 以该角色模拟登录在线（切角色 → 上行 activeUid → 服务器建会话并整轮），
            // 不是启动游戏客户端（启动客户端走页面顶部「启动分身」）
            final TextView loginBtn = new TextView(this);
            loginBtn.setText("登录");
            loginBtn.setTextSize(11.5f);
            loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
            loginBtn.setTextColor(UiStyle.BRAND_TEXT);
            loginBtn.setGravity(Gravity.CENTER);
            loginBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
            loginBtn.setBackground(UiStyle.roundRectStroke(this, UiStyle.BRAND_50, 8, UiStyle.BRAND_200));
            LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lblp.leftMargin = dp(8);
            row.addView(loginBtn, lblp);
            UiStyle.press(loginBtn);
            loginBtn.setOnClickListener(v -> loginRoleOnline(r, loginBtn));
            UiStyle.press(row);
            row.setOnClickListener(v -> {
                setCurrentRole(r.serverid, r.uid);
                toast("已设为当前角色");
                rebuildRoles();
            });
            roleBox.addView(row);
        }
    }

    /**
     * 以指定角色<b>模拟登录在线</b>（与账号页角色行「登录」同一后半程
     * {@link VaultSync#loginRoleOnline}）：先钉扎当前角色，再开自动化 →
     * 上传 → 服务器建会话并排队整轮。按钮在请求期间禁用以防重复触发。
     */
    private void loginRoleOnline(final RoleStore.Role r, final android.view.View btn) {
        if (acc == null || acc.fpid == null || acc.fpid.isEmpty()
                || acc.token == null || acc.token.isEmpty()) {
            toast("该账号没有可用 token");
            return;
        }
        setCurrentRole(r.serverid, r.uid);
        final TextView label = btn instanceof TextView ? (TextView) btn : null;
        btn.setEnabled(false);
        if (label != null) label.setText("登录中");
        VaultSync.loginRoleOnline(this, acc.fpid, (code, message, data) -> {
            runOnUiThread(() -> {
                btn.setEnabled(true);
                if (label != null) label.setText("登录");
                if (code != 0) {
                    toast("配置同步失败：" + message);
                    return;
                }
                boolean ok = data != null && data.optBoolean("accepted", false);
                String state = data == null ? "" : data.optString("state", "");
                String sidText = data == null ? "" : data.optString("sid", "");
                if (ok) {
                    toast("已登录并触发服务器自动化"
                            + (sidText.isEmpty() ? "" : " · sid=" + sidText)
                            + (state.isEmpty() ? "" : " · 会话=" + state));
                    refreshInfo();
                    VaultSync.fetchState(this);
                } else {
                    toast("服务器未受理：" + message);
                }
            });
        });
    }

    /**
     * 切角色：activeRole 与主页 current_role 必须同写。HomeActivity.setCurrentRole 是
     * 同一口径——只写 activeRole 会让主页徽章/配置按旧角色，重启时还会被启动对齐改回去。
     *
     * <p>复合身份（2026-10-20）：uid>0 时 current_role 写 {@code "fpid:sid:uid"}，
     * 同服多角色靠第三段区分；uid 未知保持旧格式 {@code "fpid:sid"}。
     */
    private void setCurrentRole(long sid, long uid) {
        RoleStore.setActiveRole(this, acc.fpid, sid, uid);
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putString("current_role", acc.fpid + ":" + sid + (uid > 0 ? ":" + uid : ""))
                // 双源对齐时间戳（2026-10-20）：启动对齐按时间取较新者
                .putLong("current_role_at", System.currentTimeMillis()).apply();
        // 同服多角色防回滚（P2）：标记待上传 + 触发去抖同步，避免上传完成前服务端旧
        // activeUid 把刚切的角色回滚（见 VaultSync.learnServerRole 双层门控）。
        try {
            VaultSync.notifyRoleSwitched(this, acc.fpid);
        } catch (Throwable ignored) {
        }
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    private void refreshInfo() {
        boolean hasToken = acc.token != null && !acc.token.isEmpty();
        boolean hasSnap = engine.hasEntrySnapshot(acc.id);
        long sid = RoleStore.activeRole(this, acc.fpid);
        if (sid <= 0) {
            List<RoleStore.Role> rs = RoleStore.roles(this, acc.fpid);
            if (!rs.isEmpty()) sid = rs.get(0).serverid;
        }
        if (profileMeta != null) {
            StringBuilder sb = new StringBuilder();
            if (hasToken && acc.fpid != null && !acc.fpid.isEmpty()) {
                sb.append("号ID ").append(acc.fpid);
            } else {
                sb.append("未绑定 token");
            }
            if (sid > 0) sb.append(" · ").append(sid).append("服");
            sb.append(" · 上次 ").append(acc.lastLogin > 0
                    ? FMT.format(new Date(acc.lastLogin)) : "未使用");
            profileMeta.setText(sb.toString());
        }
        if (profileBadge != null) {
            if (hasSnap) {
                profileBadge.setText("已保存登录 · 备有 token");
                profileBadge.setTextColor(UiStyle.OK_INK);
                profileBadge.setBackground(UiStyle.roundRectDp(this, UiStyle.OK_BG, 999));
            } else if (hasToken) {
                profileBadge.setText("token 登录 · 未保存现场");
                profileBadge.setTextColor(UiStyle.WAIT_INK);
                profileBadge.setBackground(UiStyle.roundRectDp(this, UiStyle.WAIT_BG, 999));
            } else {
                profileBadge.setText("原生登录 · 未绑定 token");
                profileBadge.setTextColor(UiStyle.INK2);
                profileBadge.setBackground(UiStyle.roundRectDp(this, UiStyle.IDLE_BG, 999));
            }
        }
        refreshLaunchStatus();
        rebuildRoles();
    }

    /**
     * 启动按钮下方状态行：容器内游戏版本 + 更新可用性（读不到容器时降级提示）。
     * M3c：判定为可更新（OUTDATED/UNSUPPORTED）时状态行可点，
     * 打开版本过旧弹窗（更新原生游戏/重新克隆分身/本地 APK/稍后）。
     */
    private void refreshLaunchStatus() {
        if (launchStatusView == null) return;
        if (!engine.isGameInstalled()) {
            launchStatusView.setText("分身游戏未克隆 · 启动时会引导克隆");
            launchStatusView.setTextColor(UiStyle.INK3);
            launchStatusView.setOnClickListener(null);
            launchStatusView.setClickable(false);
            return;
        }
        GameVersionCheck.Status st = engine.gameVersionStatus(updateCfg);
        launchStatusView.setText(GameVersionCheck.statusLine(st,
                engine.installedGameVersionName(), engine.installedGameVersionCode(),
                updateCfg.latestVersionName, updateCfg.latestVersionCode));
        launchStatusView.setTextColor(st == GameVersionCheck.Status.UP_TO_DATE
                ? UiStyle.OK_INK : UiStyle.WAIT_INK);
        if (st == GameVersionCheck.Status.OUTDATED
                || st == GameVersionCheck.Status.UNSUPPORTED) {
            UiStyle.press(launchStatusView);
            launchStatusView.setOnClickListener(v -> showUpdateDialog());
        } else {
            launchStatusView.setOnClickListener(null);
            launchStatusView.setClickable(false);
        }
    }

    /** 当前分组的展示名（"" 显示为"未分组"）。 */
    private String groupDisplay() {
        return (acc == null || acc.group == null || acc.group.isEmpty())
                ? EntryLayout.UNGROUPED_TITLE : acc.group;
    }

    // ---------- 移动到分组（③） ----------

    /**
     * 分组选择弹窗：已有分组 + "未分组" + "＋ 新建分组…"。
     *
     * 校验（空名 / 重名 / 保留名）全部交给 {@link EntryLayout#validateGroupName}，
     * 这里只做 UI 编排。**移入空组是允许的**；**最后一个成员被移走后分组不自动删除**
     * （变空组，仍显示，方便改名/删除）。
     */
    private void showMoveToGroupDialog() {
        List<EntryLayout.Group> meta = store.getGroupMeta();
        final List<String> names = new ArrayList<>();
        names.add(EntryLayout.UNGROUPED_TITLE);
        for (EntryLayout.Group g : meta) {
            if (g != null && g.name != null && !g.name.isEmpty()) names.add(g.name);
        }
        names.add("＋ 新建分组…");

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("移动到分组（当前：" + groupDisplay() + "）")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    String picked = names.get(which);
                    if ("＋ 新建分组…".equals(picked)) {
                        promptNewGroup();
                        return;
                    }
                    String target = EntryLayout.UNGROUPED_TITLE.equals(picked) ? "" : picked;
                    if (!store.setGroup(acc.id, target)) {
                        toast("移动失败，请重试");
                        return;
                    }
                    acc.group = target;
                    toast("已移动到「" + picked + "」");
                    refreshInfo();
                })
                .show();
    }

    /** 新建分组并把当前入口移进去。 */
    private void promptNewGroup() {
        UiStyle.showInputDialog(this, "新建分组", "输入分组名", "", false, text -> {
            String err = EntryLayout.validateGroupName(store.getGroupMeta(), text);
            if (err != null) {
                toast(err);
                return;
            }
            String name = text.trim();
            if (!store.createGroup(name)) {
                toast("新建分组失败，请重试");
                return;
            }
            store.setGroup(acc.id, name);
            acc.group = name;
            toast("已新建分组「" + name + "」并移入");
            refreshInfo();
        });
    }

    // ---------- 启动（M3：前置检查闸门 → 启动；失败弹窗给分类+原文+修复动作） ----------

    /**
     * 「启动分身」入口：先做完整前置检查（授权 → 引擎 → 克隆 → 版本），
     * 全过才真正拉起；任何失败都以弹窗展示「分类 + 原文 + 修复动作」，
     * 不再是「请从任务栏划掉游戏后重试」的泛化 toast。
     */
    private void doLaunch() {
        if (VaultEngine.launchBusy()) {
            toast("正在切换/启动账号，请稍候再试");
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("前置检查中…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, VaultEngine.Diagnostic>() {
            @Override protected VaultEngine.Diagnostic doInBackground(Void... p) {
                return precheck();
            }
            @Override protected void onPostExecute(VaultEngine.Diagnostic d) {
                pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (d == null) startLaunch();
                else handlePrecheckFailure(d);
            }
        }.execute();
    }

    /**
     * 逐闸门检查（后台线程）：授权 → 引擎就绪 → 游戏已克隆 → 版本不过旧。
     * 返回 null = 全部通过；否则为首个失败的诊断（分类 + 原文）。
     */
    private VaultEngine.Diagnostic precheck() {
        try {
            String lic = LicenseManager.gateForLaunch(this);
            if (lic != null) {
                return new VaultEngine.Diagnostic(VaultEngine.FailureCategory.LICENSE, lic);
            }
            String eng = engine.ensureEngineReady();
            if (eng != null) {
                return new VaultEngine.Diagnostic(VaultEngine.FailureCategory.ENGINE, eng);
            }
            if (!engine.isGameInstalled()) {
                return new VaultEngine.Diagnostic(VaultEngine.FailureCategory.CLONE,
                        "容器里没有该游戏，请先克隆（需手机上已安装官方原版）");
            }
            GameVersionCheck.Status st = engine.gameVersionStatus(updateCfg);
            if (st == GameVersionCheck.Status.OUTDATED
                    || st == GameVersionCheck.Status.UNSUPPORTED) {
                return new VaultEngine.Diagnostic(VaultEngine.FailureCategory.VERSION,
                        GameVersionCheck.outdatedText(engine.installedGameVersionName(),
                                updateCfg.latestVersionName)
                                + "，versionCode " + engine.installedGameVersionCode()
                                + " → " + updateCfg.latestVersionCode);
            }
            return null;
        } catch (Throwable t) {
            return new VaultEngine.Diagnostic(VaultEngine.FailureCategory.OTHER,
                    "前置检查异常:" + t.getMessage());
        }
    }

    /** 按失败分类给修复动作弹窗（契约 §4.1：必须展示分类+原文）。 */
    private void handlePrecheckFailure(VaultEngine.Diagnostic d) {
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("无法启动（" + VaultEngine.categoryLabel(d.category) + "）")
                .setMessage(d.display());
        switch (d.category) {
            case LICENSE:
                b.setPositiveButton("去激活", (d2, w) -> LicenseUi.showActivateDialog(
                        EntryDetailActivity.this, LicenseManager.get(EntryDetailActivity.this),
                        null));
                break;
            case ENGINE:
                b.setPositiveButton("重新初始化", (d2, w) -> doLaunch());
                break;
            case CLONE:
                b.setPositiveButton("去克隆", (d2, w) -> cloneThenLaunch());
                break;
            case VERSION:
                // UNSUPPORTED（低于可升级基线）不保证覆盖安装可用，主推重新克隆
                if (engine.installedGameVersionCode() > 0 && updateCfg.minSupportedVersionCode > 0
                        && engine.installedGameVersionCode() < updateCfg.minSupportedVersionCode) {
                    b.setPositiveButton("重新克隆分身", (d2, w) -> recloneThenRelaunch());
                } else {
                    // M3c：主路径改为「商店更新原生游戏 → 回来重新克隆」，无在线下载项
                    b.setPositiveButton("更新原生游戏", (d2, w) -> openNativeGameStore());
                    b.setNeutralButton("从本地 APK 更新", (d2, w) -> startLocalApkUpdate());
                }
                break;
            default:
                b.setPositiveButton("重试", (d2, w) -> doLaunch());
                break;
        }
        b.setNegativeButton("复制诊断", (d2, w) -> copyDiagnostics(null));
        b.show();
    }

    /** 前置检查全过 → 真正拉起（原 launchEntry 链路不变）。 */
    private void startLaunch() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在启动 «" + acc.label + "» …");
        pd.setCancelable(false);
        pd.show();
        String lastId = store.getLastEntry();
        new AsyncTask<Void, Void, Boolean>() {
            @Override protected Boolean doInBackground(Void... p) { return engine.launchEntry(lastId, acc); }
            @Override protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                if (ok) {
                    store.setLastEntry(acc.id);
                    store.markLogin(acc.id);
                    toast("已启动，从任务栏切到游戏");
                    refreshInfo();
                } else {
                    // 契约 §4.1：展示 lastLaunchError 具体值（分类+原文），按类给修复动作
                    showLaunchFailure(engine.diagnoseLaunchFailure());
                }
            }
        }.execute();
    }

    /** 引擎内启动失败（launchEntry 返回 false）的诊断弹窗。 */
    private void showLaunchFailure(VaultEngine.Diagnostic d) {
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("启动失败（" + VaultEngine.categoryLabel(d.category) + "）")
                .setMessage(d.display());
        switch (d.category) {
            case LICENSE:
                b.setPositiveButton("去激活", (d2, w) -> LicenseUi.showActivateDialog(
                        EntryDetailActivity.this, LicenseManager.get(EntryDetailActivity.this),
                        null));
                break;
            case ENGINE:
                b.setPositiveButton("重新初始化", (d2, w) -> doLaunch());
                break;
            case CLONE:
                b.setPositiveButton("去克隆", (d2, w) -> cloneThenLaunch());
                break;
            case VERSION:
                b.setPositiveButton("更新原生游戏", (d2, w) -> openNativeGameStore());
                b.setNeutralButton("从本地 APK 更新", (d2, w) -> startLocalApkUpdate());
                break;
            case DOWNLOAD:
            case INSTALL:
                b.setPositiveButton("重新克隆分身", (d2, w) -> recloneThenRelaunch());
                b.setNeutralButton("从本地 APK 更新", (d2, w) -> startLocalApkUpdate());
                break;
            default:
                b.setPositiveButton("重试", (d2, w) -> doLaunch());
                break;
        }
        b.setNegativeButton("复制诊断", (d2, w) -> copyDiagnostics(null));
        b.show();
    }

    /** 从系统原版克隆进容器，成功后自动重走前置检查并启动。 */
    private void cloneThenLaunch() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在克隆游戏…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            @Override protected String doInBackground(Void... p) {
                return engine.installGameFromSystem();
            }
            @Override protected void onPostExecute(String err) {
                pd.dismiss();
                refreshInfo();
                if (err == null) {
                    toast("克隆完成，继续启动…");
                    doLaunch();
                } else {
                    // 本方法就是克隆流程，失败一律归 CLONE（原文保留）
                    showLaunchFailure(new VaultEngine.Diagnostic(
                            VaultEngine.FailureCategory.CLONE, err));
                }
            }
        }.execute();
    }

    // ---------- 分身游戏更新（M3c：更新原生游戏 + 重新克隆分身，保数据；在线下载入口已移除） ----------

    /**
     * 版本过旧弹窗（M3c/M3d）：更新原生游戏（主）/ 重新克隆分身 /
     * 从本地 APK 更新（高级兜底）/ 稍后。选项集合与文案由
     * {@link GameRecloneFlow#updateDialogOptions} 锁定，不再出现「立即更新(888MB)」下载项。
     *
     * <p>M3d（vivo/Android 14 真机 P1）：Builder.setMessage+setItems 组合在该 ROM 上
     * 选项列表不渲染（无障碍树止于 textSpacerNoButtons，用户无选项可点）。
     * 改为自定义视图渲染（同 {@link UiStyle} 输入/确认弹窗的「透明窗 + 白卡」模式）：
     * 标题/正文 TextView + 4 个 56dp 可点选项行（分隔线 + 右箭头，行挂
     * contentDescription），动作经 {@link GameRecloneFlow#actionForOption}
     * 与原 setItems 分支一一对应。
     */
    private void showUpdateDialog() {
        String cur = engine.installedGameVersionName();
        final String[] options = GameRecloneFlow.updateDialogOptions();

        // 先建「透明窗 + 空 ScrollView 根」的弹窗再填内容：选项行点击要先关本弹窗
        // 再执行动作，需捕获 dlg（先建后填，避免 lambda 前向引用局部变量）
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(scroll)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRectDp(this, UiStyle.CARD, 20));
        box.setPadding(UiStyle.dp(this, 24), UiStyle.dp(this, 20),
                UiStyle.dp(this, 24), UiStyle.dp(this, 12));

        TextView title = new TextView(this);
        title.setText(GameVersionCheck.outdatedText(cur, updateCfg.latestVersionName));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(GameRecloneFlow.updateDialogMessage(
                updateCfg.latestVersionName, updateCfg.latestVersionCode));
        body.setTextSize(14);
        body.setTextColor(UiStyle.INK2);
        body.setLineSpacing(UiStyle.dp(this, 3), 1f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = UiStyle.dp(this, 10);
        box.addView(body, bodyLp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            if (i > 0) list.addView(UiStyle.divider(this));
            final int idx = i;
            final GameRecloneFlow.UpdateAction action = GameRecloneFlow.actionForOption(idx);
            list.addView(updateOptionRow(options[idx], action, () -> {
                dlg.dismiss();
                dispatchUpdateAction(action);
            }));
        }
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = UiStyle.dp(this, 8);
        box.addView(list, listLp);

        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlg.show();
        clampUpdateDialogHeight(dlg);
    }

    /**
     * 弹窗选项行：56dp 高、左侧选项文字 + 右侧箭头，按压整行浅蓝
     * （StateListDrawable 原生按压态，复用 UiStyle token）。
     * 主推项（OPEN_STORE）蓝字加粗，「稍后」（NONE）弱化为次级灰；
     * contentDescription 挂选项全文，保证无障碍树里四行均可读、可点（M3d 起因）。
     */
    private LinearLayout updateOptionRow(String label, GameRecloneFlow.UpdateAction action,
                                         Runnable onClick) {
        boolean primary = action == GameRecloneFlow.UpdateAction.OPEN_STORE;
        boolean muted = action == GameRecloneFlow.UpdateAction.NONE;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label);
        row.setBackground(UiStyle.pressRound(this, Color.TRANSPARENT, UiStyle.BRAND_50, 10));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTypeface(primary ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setTextColor(primary ? UiStyle.BRAND_TEXT : (muted ? UiStyle.INK2 : UiStyle.INK));
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        primary ? UiStyle.BRAND_TEXT : UiStyle.INK3, 16),
                new LinearLayout.LayoutParams(UiStyle.dp(this, 16), UiStyle.dp(this, 16)));

        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiStyle.dp(this, 56)));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** 选项动作分发（与原 setItems 分支一一对应；NONE=稍后，仅关闭弹窗）。 */
    private void dispatchUpdateAction(GameRecloneFlow.UpdateAction action) {
        switch (action) {
            case OPEN_STORE: openNativeGameStore(); break;
            case RECLONE:    recloneThenRelaunch(); break;
            case LOCAL_APK:  startLocalApkUpdate(); break;
            case NONE: default: break;
        }
    }

    /** 小屏兜底：decor 高度超过可视区 85% 时把窗口压到该高度，滚动交给根 ScrollView。 */
    private void clampUpdateDialogHeight(AlertDialog dlg) {
        if (dlg.getWindow() == null) return;
        Window w = dlg.getWindow();
        Rect frame = new Rect();
        w.getDecorView().getWindowVisibleDisplayFrame(frame);
        int maxH = (int) (frame.height() * 0.85f);
        if (maxH > 0 && w.getDecorView().getHeight() > maxH) {
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, maxH);
        }
    }

    /**
     * 「更新原生游戏」：打开应用商店的原生游戏详情页；无 market:// 处理器时
     * 退到官方下载/更新页（配置 shortLink，T1b 取证的官方短链）；再不行弹指引，
     * 提示用户到应用商店/官方渠道更新『无尽冬日』后回来点「重新克隆分身」。
     */
    private void openNativeGameStore() {
        if (tryStartView(GameRecloneFlow.GAME_MARKET_URI)) return;
        if (updateCfg.shortLink != null && tryStartView(updateCfg.shortLink)) return;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("请手动更新原生游戏")
                .setMessage("本机没有可用的应用商店。请到应用商店/官方渠道更新『无尽冬日』原生游戏，"
                        + "更新完成后回到本页点「重新克隆分身」。")
                .setPositiveButton("好的", null)
                .show();
    }

    /** ACTION_VIEW 打开 URI；无处理器/异常返回 false（供商店→网页→指引逐级兜底）。 */
    private boolean tryStartView(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「重新克隆分身」：从手机上已更新的原生游戏覆盖进容器（保数据，见
     * {@link VaultEngine#recloneGameFromSystem}），成功后回读版本并自动重新拉起；
     * 失败按原文分类走诊断弹窗（尚未更新→VERSION 引导去商店 / 未装原生→CLONE /
     * 安装→INSTALL）。
     */
    private void recloneThenRelaunch() {
        if (VaultEngine.launchBusy()) {
            toast("正在切换/启动账号，请稍候再试");
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在从原生游戏重新克隆分身…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            @Override protected String doInBackground(Void... p) {
                return engine.recloneGameFromSystem();
            }
            @Override protected void onPostExecute(String err) {
                pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                refreshInfo();
                if (err == null) {
                    long vc = engine.installedGameVersionCode();
                    boolean dataKept = engine.hasLiveContent();
                    toast("重新克隆完成：分身已同步原生 " + engine.installedGameVersionName()
                            + "(" + vc + ")" + (dataKept ? "，游戏数据已保留" : ""));
                    doLaunch();   // 重新拉起游戏（版本闸门此时应放行）
                } else {
                    showLaunchFailure(new VaultEngine.Diagnostic(
                            VaultEngine.categorizeFailure(err), err));
                }
            }
        }.execute();
    }

    /**
     * onResume 检测（M3c）：原生 versionCode &gt; 容器 versionCode（用户刚从商店更新
     * 原生游戏回来）→ 提示「检测到原生游戏已更新，是否重新克隆分身？」。
     * 同一原生版本只提示一次（点了「稍后」不反复骚扰，原生再升一级会重新提示）。
     */
    private void maybePromptReclone() {
        if (isFinishing() || isDestroyed() || VaultEngine.launchBusy()) return;
        long nativeVc = engine.nativeGameVersionCode();
        long containerVc = engine.installedGameVersionCode();
        if (!GameRecloneFlow.shouldPromptReclone(nativeVc, containerVc)) return;
        if (nativeVc == reclonePromptShownForNativeVc) return;
        reclonePromptShownForNativeVc = nativeVc;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("检测到原生游戏已更新")
                .setMessage(GameRecloneFlow.reclonePromptText(engine.nativeGameVersionName(),
                        nativeVc, engine.installedGameVersionName(), containerVc))
                .setPositiveButton("重新克隆分身", (d, w) -> recloneThenRelaunch())
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 更新包落点：应用私有目录（免存储权限，installPackage 直接可读）。 */
    private File updateApkFile() {
        File dir = new File(getFilesDir(), "game_update");
        return new File(dir, VaultEngine.GAME_PKG + "_update.apk");
    }

    /** 更新流程结果（AsyncTask 只能在 onPostExecute 交给 UI，故打包成对象）。 */
    private static final class UpdateOutcome {
        final boolean ok;
        final boolean cancelled;
        final String phase;      // download / verify / install
        final String error;      // 失败原文（分类前的原始信息）
        final VaultEngine.FailureCategory category;
        final File apk;

        UpdateOutcome(boolean ok, boolean cancelled, String phase, String error,
                      VaultEngine.FailureCategory category, File apk) {
            this.ok = ok;
            this.cancelled = cancelled;
            this.phase = phase;
            this.error = error;
            this.category = category;
            this.apk = apk;
        }
    }

    /** 更新链路失败弹窗：分类 + 原文 + 重新克隆 / 本地导入 / 复制诊断。 */
    private void showUpdateFailure(UpdateOutcome r) {
        String title = "download".equals(r.phase) ? "下载失败（"
                + VaultEngine.categoryLabel(r.category) + "）"
                : "verify".equals(r.phase) ? "更新包校验未通过"
                : "安装失败（" + VaultEngine.categoryLabel(r.category) + "）";
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(title)
                .setMessage(r.error)
                .setPositiveButton("重新克隆分身", (d, w) -> recloneThenRelaunch())
                .setNeutralButton("从本地 APK 更新", (d, w) -> startLocalApkUpdate())
                .setNegativeButton("复制诊断", (d, w) -> copyDiagnostics(
                        "phase=" + r.phase + " " + r.error))
                .show();
    }

    /**
     * 覆盖安装完成后的收尾：回读版本确认已提升、确认游戏数据仍在（保数据验证），
     * 然后<b>自动重新拉起</b>（重走前置检查，版本闸门此时应放行）。
     */
    private void afterUpdateInstalled(File apk) {
        long vc = engine.installedGameVersionCode();
        String vn = engine.installedGameVersionName();
        boolean dataKept = engine.hasLiveContent();
        refreshInfo();
        if (vc > 0 && vc < updateCfg.latestVersionCode) {
            // 引擎返回成功但版本没变：按安装失败处理，避免用户误以为已升级
            // （文案避开「版本」关键词，防止诊断分类器误归 VERSION 类）
            showUpdateFailure(new UpdateOutcome(false, false, "install",
                    "安装未生效：覆盖安装后容器内游戏仍是 " + vn + "(" + vc + ")，预期 "
                            + updateCfg.latestVersionName + "(" + updateCfg.latestVersionCode + ")",
                    VaultEngine.FailureCategory.INSTALL, apk));
            return;
        }
        StringBuilder msg = new StringBuilder("已更新到 ").append(vn == null ? "?" : vn)
                .append("（").append(vc).append("）");
        if (!dataKept) {
            msg.append("\n注意：未检测到已有游戏数据（新克隆/数据为空时属正常）");
        } else {
            msg.append("，游戏数据已保留");
        }
        toast(msg.toString());
        // 校验通过的包已落位，删除本地安装包（.part 断点文件一并清掉）
        try {
            if (apk != null && apk.exists()) //noinspection ResultOfMethodCallIgnored
                apk.delete();
            File part = new File(updateApkFile().getPath() + ".part");
            if (part.exists()) //noinspection ResultOfMethodCallIgnored
                part.delete();
        } catch (Throwable ignored) {
        }
        doLaunch();   // 重新拉起游戏（版本闸门此时放行）
    }

    // ---------- 从本地 APK 更新（P0 兜底：用户把官方包放进手机「下载」目录） ----------
    // M3b（M4 真机验证 §3.2）：扫描「下载」目录需 READ_EXTERNAL_STORAGE，未授权时
    // listFiles() 恒空、永远提示「未找到」—— 入口处先做运行时权限闸门：
    // 未授予 → 请求；拒绝 → 弹窗引导「去设置 / 打开应用商店」；授予 → 自动续跑扫描。

    /**
     * 本地 APK 入口：先过存储权限闸门（Android 6+ 未授予则请求，授权/拒绝结果在
     * {@link #onRequestPermissionsResult} 续跑），再后台扫描「下载」目录并进入确认流程。
     */
    private void startLocalApkUpdate() {
        boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (LocalApkScanner.needsReadPermission(Build.VERSION.SDK_INT, granted)) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE_LOCAL_APK);
            return;
        }
        continueLocalApkUpdate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_READ_STORAGE_LOCAL_APK) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("已授权，正在扫描「下载」目录…");
            continueLocalApkUpdate();
        } else {
            showLocalApkPermissionDenied();
        }
    }

    /** 拒绝授权：说清权限用途，给「去设置 / 打开应用商店」两条出路，不落到「未找到」。 */
    private void showLocalApkPermissionDenied() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("需要存储权限")
                .setMessage("需要读取「下载」目录权限才能使用本地 APK 更新。\n\n"
                        + "可到系统设置里授予本应用存储权限后返回重试；"
                        + "也可以到应用商店更新原生游戏后使用「重新克隆分身」。")
                .setPositiveButton("去设置", (d, w) -> {
                    resumeLocalApkUpdateAfterSettings = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null)));
                    } catch (Throwable t) {
                        resumeLocalApkUpdateAfterSettings = false;
                        toast("无法打开系统设置：" + t.getMessage());
                    }
                })
                .setNeutralButton("打开应用商店", (d, w) -> openNativeGameStore())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 已有权限（或系统无需权限，API&lt;23）：后台扫描「下载」目录里最新的
     * wjdr*.apk；直接读为空且 Android 10+ 时再走 MediaStore.Downloads 兜底
     * （命中复制进 cache）。找到 → 确认弹窗进原有 校验 → 安装 → 重拉起 链路。
     */
    private void continueLocalApkUpdate() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在扫描「下载」目录…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, File>() {
            @Override protected File doInBackground(Void... p) {
                File apk = LocalApkScanner.scanDownloads();
                if (apk == null) {
                    apk = LocalApkScanner.queryMediaStoreDownloads(
                            getApplicationContext().getContentResolver(), getCacheDir());
                }
                return apk;
            }
            @Override protected void onPostExecute(File apk) {
                pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (apk == null) {
                    // 权限已就绪才可能走到这里：确实没有候选包，给出放置指引
                    new AlertDialog.Builder(EntryDetailActivity.this,
                            AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                            .setTitle("未找到本地更新包")
                            .setMessage("请先把官方安装包（如 wjdr_1-16-12_"
                                    + updateCfg.latestVersionCode + "_1.apk）放进手机「下载」目录，再试一次。")
                            .setPositiveButton("好的", null)
                            .show();
                    return;
                }
                UiStyle.showConfirmDialog(EntryDetailActivity.this, "使用本地更新包",
                        "将校验并覆盖安装：\n" + apk.getName() + "（"
                                + GameApkDownloader.formatBytes(apk.length()) + "）\n\n游戏数据会保留。",
                        "开始更新", UiStyle.BRAND, () -> verifyAndInstallLocal(apk));
            }
        }.execute();
    }

    /** 本地包链路：跳过下载；size/sha 与 CDN 包不一定一致（渠道差异），只校验
     *  包名/versionCode 提升/签名，再覆盖安装并重拉起。 */
    private void verifyAndInstallLocal(final File apk) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在校验本地更新包…");
        pd.setCancelable(false);
        pd.show();
        final long installedVcBefore = engine.installedGameVersionCode();
        new AsyncTask<Void, Void, UpdateOutcome>() {
            @Override protected UpdateOutcome doInBackground(Void... p) {
                try {
                    GameClientUpdateConfig local = GameClientUpdateConfig.defaults();
                    local.sizeBytes = 0;      // 本地包大小/摘要不与 CDN 配置比对
                    local.sha256 = "";
                    GameApkVerifier.Result vr = GameApkVerifier.verify(EntryDetailActivity.this,
                            apk, local, installedVcBefore, engine.installedGameSignature());
                    if (!vr.ok()) {
                        return new UpdateOutcome(false, false, "verify", vr.summary(),
                                VaultEngine.FailureCategory.INSTALL, apk);
                    }
                    String instErr = engine.installGameUpdate(apk);
                    if (instErr != null) {
                        return new UpdateOutcome(false, false, "install", instErr,
                                VaultEngine.categorizeFailure(instErr), apk);
                    }
                    return new UpdateOutcome(true, false, "install", null, null, apk);
                } catch (Throwable t) {
                    return new UpdateOutcome(false, false, "verify",
                            String.valueOf(t.getMessage()),
                            VaultEngine.FailureCategory.OTHER, apk);
                }
            }
            @Override protected void onPostExecute(UpdateOutcome r) {
                pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (r.ok) afterUpdateInstalled(r.apk);
                else showUpdateFailure(r);
            }
        }.execute();
    }

    // ---------- 复制诊断 ----------

    /** 把当前版本/配置/最近失败原文拼成诊断文本并复制（失败弹窗「复制诊断」按钮）。 */
    private void copyDiagnostics(String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("game: ").append(VaultEngine.GAME_PKG).append("\n");
        long vc = engine.installedGameVersionCode();
        sb.append("installed: ").append(engine.installedGameVersionName())
                .append("(").append(vc).append(")\n");
        sb.append("native: ").append(engine.nativeGameVersionName())
                .append("(").append(engine.nativeGameVersionCode()).append(")\n");
        sb.append("config: ").append(updateCfg.describe()).append("\n");
        if (engine.getLastLaunchError() != null) {
            sb.append("launchError: ").append(engine.getLastLaunchError()).append("\n");
        }
        if (extra != null && !extra.isEmpty()) {
            sb.append("updateError: ").append(extra).append("\n");
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("game-update-diag", sb.toString()));
            toast("诊断信息已复制");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    /**
     * 登录并运行自动化：选该账号<b>工作区第一个角色</b>（无则第一个角色）钉扎为当前角色，
     * 再走共享后半程 {@link VaultSync#loginRoleOnline}（开自动化 → 上传 → 服务器建会话
     * 并排队整轮）。与角色行「登录」、账号页登录同一实现，不再内联重复。
     * 按钮在请求期间禁用以防重复触发。
     */
    private void loginAndRunAutomation(android.view.View btn, TextView label) {
        if (acc == null || acc.fpid == null || acc.fpid.isEmpty()
                || acc.token == null || acc.token.isEmpty()) {
            toast("该账号没有可用 token");
            return;
        }
        RoleStore.Role first = null;
        try {
            List<RoleStore.Role> rs = RoleStore.roles(this, acc.fpid);
            first = RoleDisplay.firstWorkspaceRole(rs, r -> ((RoleStore.Role) r).enabled);
            if (first == null && !rs.isEmpty()) first = rs.get(0);
        } catch (Throwable ignored) {
        }
        if (first != null) {
            loginRoleOnline(first, btn);
            return;
        }
        // 无任何角色行：不钉扎，直接按账号上传并触发（服务器按自身状态执行）
        btn.setEnabled(false);
        label.setText("登录中…");
        VaultSync.loginRoleOnline(this, acc.fpid, (code, message, data) -> {
            runOnUiThread(() -> {
                btn.setEnabled(true);
                label.setText("登录并运行自动化");
                if (code != 0) {
                    toast("配置同步失败：" + message);
                    return;
                }
                boolean ok = data != null && data.optBoolean("accepted", false);
                String state = data == null ? "" : data.optString("state", "");
                String sidText = data == null ? "" : data.optString("sid", "");
                if (ok) {
                    toast("已登录并触发服务器自动化"
                            + (sidText.isEmpty() ? "" : " · sid=" + sidText)
                            + (state.isEmpty() ? "" : " · 会话=" + state));
                    refreshInfo();
                    VaultSync.fetchState(this);
                } else {
                    toast("服务器未受理：" + message);
                }
            });
        });
    }

    // ---------- token ----------

    private void showTokenDialog() {
        String prefill = (acc.fpid != null && !acc.fpid.isEmpty()) ? acc.fpid + "----" : "";
        UiStyle.showInputDialog(this, "设置 token",
                "粘贴：号ID----token", prefill, false,
                text -> {
                    int sep = text.indexOf("----");
                    if (sep <= 0) { toast("格式: 号ID----token"); return; }
                    String fpid = AccountStore.extractFpid(text.substring(0, sep));
                    if (fpid.isEmpty()) { toast("号ID必须为数字"); return; }
                    store.setToken(acc.id, fpid, text.substring(sep + 4).trim());
                    // 【#70 F1】只存 token 是不够的：若 live 仍归属该入口，下次 launchEntry 会走
                    // keepLive 分支（不 wipe、不还原、**不注入**）⇒ 游戏沿用旧的 live 会话，
                    // 于是"改了 token 却没生效"。这里摘掉 live 归属，强制下次走 wipe→基线→注入。
                    // 不删快照（SessionProbe 会按 fpid 判无效），也不在这里杀游戏。
                    engine.invalidateEntrySession(acc.id);
                    // 【#72 F1】根因修复：acc 只在 onCreate 取过一次，这里必须重新读。
                    // 否则紧接着点「▶ 启 动 分 身」时 doLaunch() 把**陈旧 acc**（token 还是旧值/空）
                    // 交给 engine.launchEntry ⇒ useToken=false ⇒ 跳过注入 ⇒ 要求验证码。
                    // 主页之所以正常，是因为它每次都会重读库 —— 这就是"主页能用、管理页不行"的原因。
                    AccountStore.Account fresh = store.byId(acc.id);
                    if (fresh != null) acc = fresh;
                    toast("token 已保存，下次打开该入口时重新登录");
                    refreshInfo();
                });
    }

    // ---------- 提取并复制当前登录 token ----------

    private void confirmExtractToken() {
        UiStyle.showConfirmDialog(this, "提取登录 token",
                "将读取当前游戏里登录的那个账号的 token，并复制为「号ID----token」。\n\n"
                        + "token 等同登录凭据，可粘贴到另一台设备的号库导入框迁移账号。请勿分享给他人。",
                "复制", UiStyle.BRAND, this::doExtractToken);
    }

    private void doExtractToken() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在读取当前登录…（会先关闭游戏）");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, Object>() {
            @Override protected Object doInBackground(Void... p) {
                try {
                    return engine.extractCurrentSession();
                } catch (Throwable t) {
                    return t;
                }
            }
            @Override protected void onPostExecute(Object result) {
                pd.dismiss();
                if (result instanceof Throwable) {
                    toast("提取失败：" + ((Throwable) result).getMessage());
                    return;
                }
                VaultEngine.SessionToken st = (VaultEngine.SessionToken) result;
                String line = st.asLine();
                try {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("号库token", line));
                } catch (Throwable t) {
                    toast("复制失败：" + t.getMessage());
                    return;
                }
                // 与当前入口号ID一致则同步刷新 token（保留备注名），否则只复制并提示
                if (acc.fpid != null && acc.fpid.equals(st.fpid)) {
                    store.setToken(acc.id, st.fpid, st.token);
                    refreshInfo();
                    toast("已提取并复制：号ID " + st.fpid + "（已更新本入口 token）");
                } else if (acc.fpid == null || acc.fpid.isEmpty()) {
                    store.setToken(acc.id, st.fpid, st.token);
                    refreshInfo();
                    toast("已提取并复制：号ID " + st.fpid + "（已绑定本入口）");
                } else {
                    toast("已复制 token。注意：当前登录的是 " + st.fpid
                            + "，与本入口(号ID " + acc.fpid + ")不同，未覆盖入口");
                }
            }
        }.execute();
    }

    // ---------- 保存现场 ----------

    private void captureSnapshot() {
        // 【#72 F4】保存前先显式校验，并把**具体原因**说清楚（而不是一句笼统的"保存失败"）：
        // 无条件打包 live 会把"别人的号"或"未登录态"固化进本入口快照 —— 这正是历史上
        // D-4 类错位（A 入口快照里存着 B 账号的会话）的同源写法。
        String why = engine.captureGuardReason(acc.id);
        if (why != null) {
            toast(why);
            return;
        }
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在保存登录状态…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<String, Void, Boolean>() {
            @Override protected Boolean doInBackground(String... p) { return engine.captureEntrySnapshot(p[0]); }
            @Override protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                toast(ok ? "已保存 ✓" : "保存失败：请先登录好游戏");
                refreshInfo();
            }
        }.execute(acc.id);
    }

    // ---------- 改名 ----------

    private void showRenameDialog() {
        UiStyle.showInputDialog(this, "修改备注名", "输入新名称", acc.label, false,
                text -> {
                    String label = text.trim();
                    if (label.isEmpty()) return;
                    store.updateLabel(acc.id, label);
                    acc.label = label;
                    toast("已改名");
                    refreshInfo();
                });
    }

    // ---------- 删除 ----------

    private void confirmDelete() {
        UiStyle.showConfirmDialog(this, "删除入口",
                "将删除「" + acc.label + "」及登录数据，不可恢复。",
                "删除", UiStyle.ERR_INK,
                () -> {
                    engine.deleteEntryData(acc.id);
                    if (acc.id.equals(store.getLastEntry())) store.setLastEntry(null);
                    store.remove(acc.id);
                    toast("已删除");
                    finish();
                });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
