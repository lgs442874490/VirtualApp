package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.license.LicenseManager;
import com.carlos.vault.license.LicenseState;
import com.carlos.vault.license.LicenseUi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 号库主界面：Carlos 风格九宫格 + 蓝色悬浮加号 */
public class AccountVaultActivity extends Activity {

    private VaultEngine engine;
    private AccountStore store;
    private GridView grid;
    private TextView status;
    private List<AccountStore.Account> accounts;
    private TileAdapter adapter;
    private Drawable gameIcon;

    /** 授权门面（门禁 + 顶部状态条 + 节流校验）。 */
    private LicenseManager licenseManager;
    /** 主界面顶部的授权状态条，便于 onResume 时原地刷新。 */
    private View licenseBar;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    /** 批量导入：选择文件（SAF ACTION_OPEN_DOCUMENT）。 */
    private static final int REQ_IMPORT_FILE = 4711;
    /** 批量导入：导出失败/冲突明细（SAF ACTION_CREATE_DOCUMENT）。 */
    private static final int REQ_EXPORT_FAILURES = 4712;

    /** 导入弹窗里正在使用的输入框，「选择文件」用它回填内容；弹窗关闭即置空。 */
    private EditText importInput = null;
    /** 最近一次导入的失败/冲突明细，供「导出失败明细」写出。 */
    private String lastFailureReport = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // R3：注册全局生命周期回调（尽最大努力补快照；内部一次性保护，重复调用安全）。
        // 应用级 Application（com.carlos.common.App）来自预编译依赖、源码不在本仓库，无法就地
        // 注册，故从 Activity 侧拿 getApplication() 注册。放在门禁判断之前，保证无论是否被门禁
        // 拦截都能装上（否则门禁页期间的"回到前台"也会漏掉一次存档机会）。
        SessionSaver.install(getApplication());

        // —— 授权门禁 ——
        // 未激活 / 票据被篡改 / 已失效时，只渲染门禁页（激活页或锁定页）并 return，
        // 不执行 buildUi() / refresh() / 向导，因此界面上不存在任何进入主功能的入口。
        // 判定为纯本地（无网络），冷启动不会因此卡顿。
        licenseManager = LicenseManager.get(this);
        licenseManager.ensureLoaded();
        if (!licenseManager.localUsable()) {
            setContentView(LicenseUi.buildGateView(this, licenseManager,
                    new LicenseUi.OnChanged() {
                        @Override
                        public void onChanged() {
                            recreate();
                        }
                    }));
            // 后台刷新一次：离线超期等可恢复态在联网后能自动解锁。
            licenseManager.verifyAsync(null);
            return;
        }

        engine = new VaultEngine(this);
        store = new AccountStore(this);
        accounts = store.all();
        List<String> validIds = new ArrayList<>();
        for (AccountStore.Account a : accounts) validIds.add(a.id);
        engine.pruneOrphanSnapshots(validIds);
        gameIcon = loadGameIcon();
        buildUi();
        refresh();
        requestNotifPermission();

        // —— 启动时授权检测（一次性、强制联网）——
        // 取消 60s 复核后，服务端吊销/到期不再由后台改状态；冷启动时本地票据可能仍是
        // active，故此处主动校验一次。校验结果由 LicenseManager 落盘，随后用 localUsable()
        // 二次确认：仅当本地判定确实转为不可用时才 recreate()，从结构上杜绝 recreate() 死循环。
        // 回调已由 LicenseManager.callback() 切回主线程，recreate() 安全。
        licenseManager.verifyAsync(new LicenseManager.Callback() {
            @Override
            public void onResult(LicenseState state, String message) {
                if (!licenseManager.localUsable()) {
                    recreate();   // 重跑 onCreate → 本地判定不可用 → 渲染门禁页
                } else {
                    refreshLicenseBar();
                }
            }
        });

        // 引擎启动是异步的：冷启动后立刻查询会把"已克隆"误判为"未克隆"从而误弹向导，
        // 也会让启动接口直接失败。旧入口 splashActivity 在跳主界面前会 waitForEngine，
        // 新入口接管 LAUNCHER 后这一步被丢掉了，这里补回来。
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... p) {
                engine.ensureEngineReady();
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                refresh();
                if (!engine.isGameInstalled() || !engine.hasBaseline()) {
                    status.postDelayed(AccountVaultActivity.this::showWizard, 300);
                }
            }
        }.execute();
    }

    private String reason(String e) {
        return (e == null || e.isEmpty()) ? "未知原因" : e;
    }

    // ---------- 授权（顶部状态条 / 换码入口） ----------

    /** 构建并插入顶部授权状态条。 */
    private void installLicenseBar(LinearLayout root) {
        if (licenseManager == null) return;
        licenseBar = LicenseUi.buildStatusBar(this, licenseManager);
        root.addView(licenseBar);
    }

    /** 原地刷新状态条：remove 后按原位置重新插入，避免打乱布局顺序。 */
    private void refreshLicenseBar() {
        if (licenseBar == null || licenseManager == null) return;
        android.view.ViewParent parentView = licenseBar.getParent();
        if (!(parentView instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) parentView;
        int index = parent.indexOfChild(licenseBar);
        parent.removeViewAt(index);
        licenseBar = LicenseUi.buildStatusBar(this, licenseManager);
        parent.addView(licenseBar, index);
    }

    /** 授权菜单：查看当前状态，或更换兑换码重新激活。 */
    private void showLicenseMenu() {
        if (licenseManager == null) return;
        StringBuilder msg = new StringBuilder(licenseManager.describeRemain());
        String expire = licenseManager.expireDateText();
        if (!expire.isEmpty()) {
            msg.append("\n到期日：").append(expire);
        }
        msg.append("\n\n如已购买新的兑换码，可点「更换」重新激活。");

        UiStyle.showConfirmDialog(this, "授权状态", msg.toString(), "更换", UiStyle.BLUE,
                new Runnable() {
                    @Override
                    public void run() {
                        LicenseUi.showActivateDialog(AccountVaultActivity.this, licenseManager,
                                new LicenseUi.OnChanged() {
                                    @Override
                                    public void onChanged() {
                                        recreate();
                                    }
                                });
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /** Android 13+ 前台服务通知需要 POST_NOTIFICATIONS；此权限缺失只影响保活通知，不影响登录 */
    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 门禁页路径下主界面未构建（status 仍为 null），此时不做刷新；
        // 门禁页的联网刷新已在 onCreate 中触发。
        if (status == null) return;

        refresh();
        refreshLicenseBar();

        // 节流联网校验：距上次尝试不足 VERIFY_THROTTLE_MS 则直接回调当前态，不发请求。
        if (licenseManager != null) {
            licenseManager.verifyThrottled(new LicenseManager.Callback() {
                @Override
                public void onResult(LicenseState state, String message) {
                    refreshLicenseBar();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // R3 尽最大努力：离开号库界面时请求一次后台存档（内部有节流，且**不会**杀游戏进程）。
        // ⚠️ Android 不保证 onPause/onStop 在强杀时被调用（任务栏划掉、am force-stop 都可能直接
        //    杀进程而不回调）。真正的兜底是 VaultEngine 的 R1+R2 —— 只要 live 目录还在，会话就还在；
        //    R3 只是"有机会时多存一份备份"，绝不能被当成强杀场景的依赖项。
        SessionSaver.requestSave(this);
    }

    private Drawable loadGameIcon() {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(VaultEngine.GAME_PKG, 0);
            return info.loadIcon(pm);
        } catch (Throwable t) {
            return null;
        }
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiStyle.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(28), dp(20), dp(4));

        TextView title = new TextView(this);
        title.setText("无尽号库");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.BLUE_DARK);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 授权入口（查看状态 / 更换兑换码）
        TextView licenseBtn = new TextView(this);
        licenseBtn.setText("授权");
        licenseBtn.setTextSize(13);
        licenseBtn.setTextColor(UiStyle.BLUE);
        licenseBtn.setGravity(Gravity.CENTER);
        GradientDrawable licenseBg = new GradientDrawable();
        licenseBg.setShape(GradientDrawable.RECTANGLE);
        licenseBg.setCornerRadius(dp(14));
        licenseBg.setColor(0xFFE8EEFF);
        licenseBtn.setBackground(licenseBg);
        licenseBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        licenseBtn.setOnClickListener(v -> showLicenseMenu());
        LinearLayout.LayoutParams licenseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        licenseLp.rightMargin = dp(8);
        header.addView(licenseBtn, licenseLp);

        // 向导按钮（问号图标）
        TextView helpBtn = new TextView(this);
        helpBtn.setText("？");
        helpBtn.setTextSize(16);
        helpBtn.setTextColor(UiStyle.BLUE);
        helpBtn.setGravity(Gravity.CENTER);
        GradientDrawable helpBg = new GradientDrawable();
        helpBg.setShape(GradientDrawable.OVAL);
        helpBg.setColor(0xFFE8EEFF);
        helpBtn.setBackground(helpBg);
        helpBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        helpBtn.setOnClickListener(v -> showWizard());
        header.addView(helpBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(header);

        // 授权状态条：常驻展示剩余天数 / 永久有效 / 离线状态（PRD 4.3）
        installLicenseBar(root);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(UiStyle.TEXT_SEC);
        status.setPadding(dp(20), dp(4), dp(20), dp(12));
        root.addView(status);

        // 九宫格
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setPadding(dp(12), dp(4), dp(12), dp(16));
        grid.setVerticalSpacing(dp(12));
        grid.setHorizontalSpacing(dp(12));
        grid.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 蓝色悬浮加号
        TextView fab = new TextView(this);
        fab.setText("＋");
        fab.setTextSize(26);
        fab.setTextColor(Color.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setShadowLayer(8, 0, 3, 0x40000000);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(UiStyle.BLUE);
        fab.setBackground(fabBg);
        fab.setElevation(dp(6));
        fab.setOnClickListener(v -> showCreateEntry());
        fab.setOnLongClickListener(v -> { showBatchImport(); return true; });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabLp.gravity = Gravity.END | Gravity.BOTTOM;
        fabLp.setMargins(0, 0, dp(24), dp(28));
        frame.addView(fab, fabLp);

        setContentView(frame);

        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            if (accounts == null || pos >= accounts.size()) return;
            doSwitch(accounts.get(pos));
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (accounts == null || pos >= accounts.size()) return false;
            android.content.Intent it = new android.content.Intent(this, EntryDetailActivity.class);
            it.putExtra(EntryDetailActivity.EXTRA_ID, accounts.get(pos).id);
            startActivity(it);
            return true;
        });
    }

    private void refresh() {
        accounts = store.all();
        adapter.notifyDataSetChanged();
        String s;
        if (!engine.isGameInstalled()) {
            s = "游戏未克隆 · 点右上角 ？ 开始初始化";
        } else if (!engine.hasBaseline()) {
            s = "分身就绪 · 完成初始化后即可添加账号";
        } else {
            s = "就绪 · 单击启动 · 长按管理";
        }
        status.setText(s);
    }

    // ---------- 创建入口 / 批量导入 ----------

    private void showCreateEntry() {
        UiStyle.showInputDialog(this, "创建账号入口",
                "输入备注名（如：3号低保）", "", false,
                text -> {
                    String label = text.trim();
                    if (label.isEmpty()) label = "未命名";
                    store.createEntry(label);
                    refresh();
                    toast("入口「" + label + "」已创建");
                });
    }

    /**
     * 批量导入（长按悬浮「＋」进入）。
     *
     * 支持两种格式：
     * <ol>
     *   <li>既有单行 {@code 备注|号ID----token} / {@code 号ID----token}；</li>
     *   <li>表格粘贴 —— {@code 备注⇥account=号ID}，紧跟一行 {@code sdkToken=...}。</li>
     * </ol>
     * 解析 / 校验 / 去重 / 冲突判定全在 {@link AccountImport}（纯逻辑）里完成，这里只负责 UI 编排
     * 与一次事务化提交。冲突策略默认 <b>跳过</b>，绝不覆盖已有数据。
     */
    private void showBatchImport() {
        final boolean[] overwrite = {false};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 16));
        box.setPadding(dp(24), dp(18), dp(24), dp(8));

        TextView title = new TextView(this);
        title.setText("批量导入账号");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.TEXT_PRI);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("格式一：备注|号ID----token\n"
                + "格式二：表格粘贴 —— 备注⇥account=号ID，紧跟一行 sdkToken=...\n"
                + "空行与 # 开头的行会被忽略；与库内重复的号ID 默认跳过、不覆盖。");
        hint.setTextSize(12);
        hint.setTextColor(UiStyle.TEXT_SEC);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        box.addView(hint, hintLp);

        final EditText et = new EditText(this);
        et.setHint("在此粘贴账号列表，或点「选择文件」…");
        et.setTextSize(13);
        et.setTextColor(UiStyle.TEXT_PRI);
        et.setMinLines(6);
        et.setMaxLines(12);
        et.setGravity(Gravity.TOP);
        et.setBackground(UiStyle.roundRect(0xFFF8F9FC, 10));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(14);
        box.addView(et, etLp);
        importInput = et;

        // —— 冲突策略（默认「跳过」，绝不覆盖已有数据）——
        final TextView policy = new TextView(this);
        policy.setTextSize(13);
        policy.setPadding(dp(10), dp(10), dp(10), dp(10));
        policy.setBackground(UiStyle.roundRect(0xFFF1F5F9, 10));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = dp(10);
        box.addView(policy, pLp);
        final Runnable syncPolicy = new Runnable() {
            @Override
            public void run() {
                if (overwrite[0]) {
                    policy.setText("冲突策略：覆盖已有账号（同号ID 将被改写）— 点此切回");
                    policy.setTextColor(UiStyle.RED);
                } else {
                    policy.setText("冲突策略：跳过冲突（默认，不覆盖任何已有数据）— 点此切换");
                    policy.setTextColor(UiStyle.TEAL);
                }
            }
        };
        syncPolicy.run();
        policy.setOnClickListener(v -> {
            overwrite[0] = !overwrite[0];
            syncPolicy.run();
        });

        // —— 选择文件（SAF）——
        TextView pick = new TextView(this);
        pick.setText("选择文件");
        pick.setTextSize(14);
        pick.setTextColor(UiStyle.BLUE);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = dp(8);
        box.addView(pick, pickLp);
        pick.setOnClickListener(v -> {
            try {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                it.setType("*/*");
                startActivityForResult(it, REQ_IMPORT_FILE);
            } catch (Throwable t) {
                toast("无法打开文件选择器");
            }
        });

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(14), 0, 0);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(UiStyle.TEXT_SEC);
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnRow.addView(cancel);

        TextView go = new TextView(this);
        go.setText("开始导入");
        go.setTextSize(14);
        go.setTextColor(UiStyle.BLUE);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnRow.addView(go);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.setOnDismissListener(d -> {
            if (importInput == et) importInput = null;
        });
        cancel.setOnClickListener(v -> dlg.dismiss());
        go.setOnClickListener(v -> {
            String text = et.getText() == null ? "" : et.getText().toString();
            dlg.dismiss();
            runImport(text, overwrite[0]
                    ? AccountImport.ConflictPolicy.OVERWRITE
                    : AccountImport.ConflictPolicy.SKIP);
        });
        dlg.show();
    }

    /**
     * 执行导入：沿用 {@link #doSwitch()} 的 ProgressDialog + AsyncTask 范式，
     * 解析与提交都放后台线程，最后只做一次事务化落盘。
     */
    private void runImport(String text, AccountImport.ConflictPolicy policy) {
        if (store == null) store = new AccountStore(this);
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在导入…");
        pd.setCancelable(false);
        pd.show();

        new AsyncTask<String, Void, AccountImport.Result>() {
            @Override
            protected AccountImport.Result doInBackground(String... p) {
                List<AccountImport.Row> rows = AccountImport.parse(p[0]);
                return AccountImport.apply(store, rows, policy);
            }

            @Override
            protected void onPostExecute(AccountImport.Result r) {
                pd.dismiss();
                if (r == null) {
                    toast("导入失败");
                    return;
                }
                if (r.total == 0) {
                    toast("没有可导入的内容");
                    return;
                }
                refresh();
                showImportResult(r);
            }
        }.execute(text);
    }

    /** 结果弹窗：成功 N / 失败 M / 跳过 K + 失败明细 + 导出按钮。 */
    private void showImportResult(final AccountImport.Result r) {
        // 供导出用的明细文本：第X行 \t 原因 \t 原文
        StringBuilder report = new StringBuilder();
        report.append("行号\t结果\t原文\n");
        for (AccountImport.Row f : r.failures) {
            report.append("第").append(f.lineNo).append("行\t失败：").append(f.error)
                    .append("\t").append(f.raw == null ? "" : f.raw).append("\n");
        }
        for (AccountImport.Row c : r.conflicts) {
            report.append("第").append(c.lineNo).append("行\t跳过：").append(c.conflict)
                    .append("\t").append(c.raw == null ? "" : c.raw).append("\n");
        }
        lastFailureReport = report.toString();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 16));
        box.setPadding(dp(24), dp(18), dp(24), dp(8));

        TextView title = new TextView(this);
        title.setText("导入结果");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.TEXT_PRI);
        box.addView(title);

        TextView sum = new TextView(this);
        sum.setText("成功 " + r.ok + " 个 · 失败 " + r.failed + " 条 · 跳过 " + r.skipped + " 条");
        sum.setTextSize(14);
        sum.setTextColor(r.failed > 0 ? UiStyle.RED : UiStyle.TEXT_PRI);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(12);
        box.addView(sum, sumLp);

        if (!r.failures.isEmpty() || !r.conflicts.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (AccountImport.Row f : r.failures) {
                detail.append("第 ").append(f.lineNo).append(" 行：").append(f.error).append("\n");
            }
            for (AccountImport.Row c : r.conflicts) {
                detail.append("第 ").append(c.lineNo).append(" 行：").append(c.conflict).append("\n");
            }
            TextView det = new TextView(this);
            det.setText(detail.toString().trim());
            det.setTextSize(12);
            det.setTextColor(UiStyle.RED);
            det.setMaxLines(10);
            LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detLp.topMargin = dp(10);
            box.addView(det, detLp);
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(14), 0, 0);

        if (!r.failures.isEmpty() || !r.conflicts.isEmpty()) {
            TextView exp = new TextView(this);
            exp.setText("导出失败明细");
            exp.setTextSize(14);
            exp.setTextColor(UiStyle.BLUE);
            exp.setPadding(dp(16), dp(10), dp(16), dp(10));
            btnRow.addView(exp);
            exp.setOnClickListener(v -> exportFailures());
        }

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(UiStyle.TEXT_SEC);
        close.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnRow.addView(close);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    /** 导出失败/冲突明细（SAF）；SAF 不可用时降级为复制到剪贴板。 */
    private void exportFailures() {
        if (lastFailureReport == null || lastFailureReport.isEmpty()) {
            toast("没有可导出的明细");
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_TITLE, "import-failures.txt");
            startActivityForResult(it, REQ_EXPORT_FAILURES);
        } catch (Throwable t) {
            copyFailuresToClipboard();
        }
    }

    private void copyFailuresToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("导入结果明细", lastFailureReport));
                toast("已复制明细到剪贴板");
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("导出失败，且无法复制到剪贴板");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_FILE) {
            String text = readText(uri);
            if (text == null) {
                toast("读取文件失败");
                return;
            }
            if (importInput != null) {
                importInput.setText(text);
                toast("已读入文件");
            } else {
                toast("文件已读取，请重新打开导入框");
            }
        } else if (requestCode == REQ_EXPORT_FAILURES) {
            boolean ok = writeText(uri, lastFailureReport);
            if (ok) toast("明细已导出");
            else copyFailuresToClipboard();
        }
    }

    /** 从 SAF Uri 读 UTF-8 文本（清洗交给 AccountImport.parse）。 */
    private String readText(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean writeText(Uri uri, String text) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return false;
            os.write(text == null ? new byte[0] : text.getBytes("UTF-8"));
            os.flush();
            os.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 启动 ----------

    private void doSwitch(AccountStore.Account acc) {
        if (!engine.isGameInstalled()) {
            toast("请先完成初始化");
            return;
        }
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在切换到 «" + acc.label + "» …");
        pd.setCancelable(false);
        pd.show();
        String lastId = store.getLastEntry();
        new AsyncTask<AccountStore.Account, Void, Boolean>() {
            final AccountStore.Account target = acc;

            @Override
            protected Boolean doInBackground(AccountStore.Account... a) {
                return engine.launchEntry(lastId, a[0]);
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                if (ok) {
                    store.setLastEntry(target.id);
                    store.markLogin(target.id);
                    refresh();
                    toast("已启动 «" + target.label + "»");
                } else {
                    toast("启动失败：请从任务栏划掉游戏后重试");
                }
            }
        }.execute(acc);
    }

    // ---------- 初始化向导 ----------

    private void showWizard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 16));
        box.setPadding(dp(24), dp(20), dp(24), dp(8));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("初始化向导（只需一次）");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(UiStyle.TEXT_PRI);
        box.addView(tvTitle);

        String[] steps = {
                "① 克隆游戏：从手机已装的无尽冬日引用，秒完成",
                "② 首次启动：进游戏下载约 2.2GB 资源（仅此一次）",
                "③ 重置到登录界面：清数据并重启，出现登录界面后退回",
                "④ 保存基线：锁定干净登录状态，之后即可添加账号"
        };
        for (int i = 0; i < steps.length; i++) {
            TextView t = new TextView(this);
            t.setText(steps[i]);
            t.setTextSize(13);
            t.setTextColor(UiStyle.TEXT_SEC);
            t.setPadding(0, dp(10), 0, 0);
            box.addView(t);
        }

        // 步骤按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(18), 0, dp(4));
        String[] labels = {"① 克隆", "② 首开", "③ 重置", "④ 存基线"};
        int[] colors = {UiStyle.BLUE, UiStyle.BLUE, UiStyle.TEAL, 0xFF6A1B9A};
        for (int i = 0; i < 4; i++) {
            final int step = i;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setBackground(UiStyle.roundRect(colors[i], 10));
            b.setPadding(dp(6), dp(12), dp(6), dp(12));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(44), 1);
            blp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> onWizardStep(step));
            row.addView(b);
        }
        box.addView(row);

        // 关闭按钮
        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(UiStyle.TEXT_SEC);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(16), 0, dp(8));

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box)
                .create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        close.setOnClickListener(v -> dlg.dismiss());
        box.addView(close);
        dlg.show();
    }

    private void onWizardStep(int step) {
        switch (step) {
            case 0: // 克隆
                if (engine.isGameInstalled()) { toast("已克隆，可跳过"); return; }
                ProgressDialog cloning = new ProgressDialog(this);
                cloning.setMessage("正在克隆游戏…（首次需要解包，请稍候）");
                cloning.setCancelable(false);
                cloning.show();
                new AsyncTask<Void, Void, String>() {
                    @Override protected String doInBackground(Void... p) { return engine.installGameFromSystem(); }
                    @Override protected void onPostExecute(String err) {
                        cloning.dismiss();
                        toast(err == null ? "克隆完成 ✓" : ("克隆失败：" + err));
                        refresh();
                    }
                }.execute();
                break;
            case 1: // 首开
                if (!engine.isGameInstalled()) { toast("请先完成①"); return; }
                if (!engine.launchGame()) toast("启动失败：" + reason(engine.getLastLaunchError()));
                break;
            case 2: // 重置
                if (!engine.isGameInstalled()) { toast("请先完成①②"); return; }
                engine.killGame();
                new AsyncTask<Void, Void, Void>() {
                    @Override protected Void doInBackground(Void... p) {
                        try { Thread.sleep(800); engine.wipeGameInternal(); } catch (InterruptedException ignored) {}
                        return null;
                    }
                    @Override protected void onPostExecute(Void r) {
                        toast(engine.launchGame() ? "已重启，到登录界面后回来点④"
                                : ("启动失败：" + reason(engine.getLastLaunchError())));
                    }
                }.execute();
                break;
            case 3: // 存基线
                engine.killGame();
                new AsyncTask<Void, Void, Boolean>() {
                    @Override protected Boolean doInBackground(Void... p) {
                        try { Thread.sleep(800); return engine.saveBaseline(); } catch (Exception e) { return false; }
                    }
                    @Override protected void onPostExecute(Boolean ok) {
                        toast(ok ? "基线已保存 ✓ 初始化完成！" : "保存失败：请确认已完成③");
                        refresh();
                    }
                }.execute();
                break;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ---------- 九宫格 ----------

    private class TileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return accounts == null ? 0 : accounts.size();
        }

        @Override
        public Object getItem(int position) {
            return accounts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout tile;
            if (convertView instanceof LinearLayout) {
                tile = (LinearLayout) convertView;
            } else {
                tile = new LinearLayout(AccountVaultActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(dp(8), dp(14), dp(8), dp(14));
                tile.setBackground(UiStyle.roundRect(UiStyle.CARD, 14));
                tile.setElevation(dp(2));

                ImageView icon = new ImageView(AccountVaultActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));

                TextView name = new TextView(AccountVaultActivity.this);
                name.setTextSize(13);
                name.setMaxLines(1);
                name.setTextColor(UiStyle.TEXT_PRI);
                name.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.topMargin = dp(8);
                tile.addView(name, nlp);

                TextView sub = new TextView(AccountVaultActivity.this);
                sub.setTextSize(10);
                sub.setMaxLines(1);
                sub.setTextColor(UiStyle.TEXT_SEC);
                sub.setGravity(Gravity.CENTER);
                tile.addView(sub);
            }

            ImageView icon = (ImageView) tile.getChildAt(0);
            TextView name = (TextView) tile.getChildAt(1);
            TextView sub = (TextView) tile.getChildAt(2);

            name.setTextSize(13);
            AccountStore.Account a = accounts.get(position);
            if (gameIcon != null) icon.setImageDrawable(gameIcon);
            icon.setAlpha(1f);
            name.setText(a.label);
            String tag = (a.fpid != null && !a.fpid.isEmpty()) ? "token" :
                    (engine.hasEntrySnapshot(a.id) ? "已存" : "原生");
            sub.setText(tag + " · " + (a.lastLogin > 0 ? FMT.format(new Date(a.lastLogin)) : "未用"));
            return tile;
        }
    }
}
