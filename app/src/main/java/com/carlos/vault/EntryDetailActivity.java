package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.daily.RoleDisplay;
import com.carlos.vault.daily.RoleStore;

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
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshInfo();
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
                .putString("current_role", acc.fpid + ":" + sid + (uid > 0 ? ":" + uid : "")).apply();
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
        rebuildRoles();
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

    // ---------- 启动 ----------

    private void doLaunch() {
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
                    toast("启动失败：请从任务栏划掉游戏后重试");
                }
            }
        }.execute();
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
