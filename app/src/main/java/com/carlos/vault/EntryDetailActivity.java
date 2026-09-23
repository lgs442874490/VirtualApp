package com.carlos.vault;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 入口管理页：卡片式布局 */
public class EntryDetailActivity extends Activity {

    public static final String EXTRA_ID = "entry_id";

    private VaultEngine engine;
    private AccountStore store;
    private AccountStore.Account acc;
    private TextView modeView;
    private TextView infoView;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiStyle.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(24));

        // 顶部标题栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.WHITE);
        topBar.setPadding(dp(16), dp(28), dp(16), dp(14));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(UiStyle.BLUE);
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("应用管理");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.TEXT_PRI);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 头像卡片：游戏图标 + 入口名 + 模式
        LinearLayout headCard = new LinearLayout(this);
        headCard.setOrientation(LinearLayout.HORIZONTAL);
        headCard.setGravity(Gravity.CENTER_VERTICAL);
        headCard.setBackground(UiStyle.roundRect(UiStyle.CARD, 14));
        headCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hclp.setMargins(dp(16), dp(14), dp(16), 0);
        headCard.setLayoutParams(hclp);

        ImageView icon = new ImageView(this);
        try {
            Drawable d = getPackageManager().getApplicationIcon(VaultEngine.GAME_PKG);
            icon.setImageDrawable(d);
        } catch (Throwable ignored) {}
        headCard.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.setPadding(dp(16), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(acc.label);
        name.setTextSize(18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.TEXT_PRI);
        headText.addView(name);

        modeView = new TextView(this);
        modeView.setTextSize(13);
        modeView.setTextColor(UiStyle.BLUE);
        modeView.setPadding(0, dp(4), 0, 0);
        headText.addView(modeView);

        headCard.addView(headText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(headCard);

        // 信息卡片
        LinearLayout infoCard = UiStyle.card(this);
        infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setTextColor(UiStyle.TEXT_SEC);
        infoCard.addView(infoView);
        root.addView(infoCard);

        // 启动按钮
        TextView launchBtn = new TextView(this);
        launchBtn.setText("▶  启 动 分 身");
        launchBtn.setTextSize(17);
        launchBtn.setTextColor(Color.WHITE);
        launchBtn.setTypeface(Typeface.DEFAULT_BOLD);
        launchBtn.setGravity(Gravity.CENTER);
        launchBtn.setBackground(UiStyle.roundRect(UiStyle.BLUE, 14));
        launchBtn.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblp.setMargins(dp(16), dp(14), dp(16), 0);
        launchBtn.setLayoutParams(lblp);
        launchBtn.setOnClickListener(v -> doLaunch());
        root.addView(launchBtn);

        // 登录设置卡片
        LinearLayout loginCard = UiStyle.card(this);
        loginCard.addView(sectionTitle("登录设置"));
        loginCard.addView(UiStyle.makeRowBtn(this, "🔑", "设置 token（号ID----token）", UiStyle.TEXT_PRI, this::showTokenDialog));
        addDivider(loginCard);
        loginCard.addView(UiStyle.makeRowBtn(this, "📋", "提取并复制当前登录 token", UiStyle.TEXT_PRI, this::confirmExtractToken));
        addDivider(loginCard);
        loginCard.addView(UiStyle.makeRowBtn(this, "💾", "保存当前登录现场到本入口", UiStyle.TEXT_PRI, this::captureSnapshot));
        root.addView(loginCard);

        // 入口管理卡片
        LinearLayout mgmtCard = UiStyle.card(this);
        mgmtCard.addView(sectionTitle("入口管理"));
        mgmtCard.addView(UiStyle.makeRowBtn(this, "✏️", "修改备注名", UiStyle.TEXT_PRI, this::showRenameDialog));
        addDivider(mgmtCard);
        mgmtCard.addView(UiStyle.makeRowBtn(this, "📱", "设备伪装（下一版）", UiStyle.TEXT_SEC, () -> toast("设备伪装将在下一版提供")));
        addDivider(mgmtCard);
        mgmtCard.addView(UiStyle.makeRowBtn(this, "🗑", "删除本入口", UiStyle.RED, this::confirmDelete));
        root.addView(mgmtCard);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refreshInfo();
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(UiStyle.TEXT_SEC);
        t.setPadding(dp(4), dp(2), 0, dp(10));
        return t;
    }

    private void addDivider(LinearLayout card) {
        TextView div = new TextView(this);
        div.setBackgroundColor(UiStyle.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(16), dp(4), dp(16), dp(4));
        div.setLayoutParams(lp);
        card.addView(div);
    }

    private void refreshInfo() {
        boolean hasToken = acc.fpid != null && !acc.fpid.isEmpty() && acc.token != null && !acc.token.isEmpty();
        boolean hasSnap = engine.hasEntrySnapshot(acc.id);
        String mode = hasSnap ? "已保存登录" + (hasToken ? " · 备有token" : "")
                : (hasToken ? "token 登录" : "原生登录（未绑定）");
        modeView.setText(mode);
        String when = acc.lastLogin > 0 ? FMT.format(new Date(acc.lastLogin)) : "未使用";
        infoView.setText((hasToken ? "号ID: " + acc.fpid + "\n" : "") + "上次使用: " + when);
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
                    toast("token 已保存");
                    refreshInfo();
                });
    }

    // ---------- 提取并复制当前登录 token ----------

    private void confirmExtractToken() {
        UiStyle.showConfirmDialog(this, "提取登录 token",
                "将读取当前游戏里登录的那个账号的 token，并复制为「号ID----token」。\n\n"
                        + "token 等同登录凭据，可粘贴到另一台设备的号库导入框迁移账号。请勿分享给他人。",
                "复制", UiStyle.BLUE, this::doExtractToken);
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
                "删除", UiStyle.RED,
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
