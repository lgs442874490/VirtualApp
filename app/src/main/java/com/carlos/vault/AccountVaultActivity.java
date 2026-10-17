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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

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

    /** 网格列数：入口卡片占 1 格；分组表头经 SpanSizeLookup 占满 {@link #SPAN} 格（通铺）。 */
    private static final int SPAN = 3;
    /** 两类行的 viewType —— 直接复用 {@link EntryLayout} 的行类型常量，避免两套编号对不上。 */
    private static final int VH_HEADER = EntryLayout.TYPE_HEADER;
    private static final int VH_ENTRY = EntryLayout.TYPE_ENTRY;

    protected VaultEngine engine;
    protected AccountStore store;
    private RecyclerView recycler;
    /** 3 列网格；分组表头通过 {@link GridLayoutManager.SpanSizeLookup} 占满整行（通铺）。 */
    private GridLayoutManager layoutManager;
    /** 排序模式内的拖拽（⇅ 把手触发，长按仍留给"打开详情"）。 */
    private ItemTouchHelper itemTouchHelper;
    private TextView status;
    protected List<AccountStore.Account> accounts;
    private TileAdapter adapter;
    private Drawable gameIcon;

    // ---------- ②排序 / ③分组的展示状态 ----------

    /** 展示行（分组表头 / 入口 tile），由 accounts + groupMeta 重建。 */
    private List<EntryLayout.Row> displayItems;
    /** 布局视图（只含 id + group）；拖拽时在它上面搬移，松手后写回 accounts。 */
    private List<EntryLayout.Item> layoutItems;
    /** 分组元数据（分组顺序 + 折叠态）。 */
    private List<EntryLayout.Group> groupMeta;
    /** 卡片悬浮操作弹层（▶启动 / ⇅排序 / ⋯管理），同一时刻至多一个。 */
    private PopupWindow tilePopup;
    /** 「排序中」标记：⇅ 按下后置位——长按卡片=直接拖拽（ITH 标准手势），松手自动退出。 */
    private boolean dragArm = false;

    /** 授权门面（门禁 + 头部状态环 + 节流校验）。 */
    protected LicenseManager licenseManager;
    /** 头部授权状态圆环（剩余时长百分比换色），onResume 原地刷新。 */
    private LicenseUi.LicenseRingView licenseRing;

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
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }

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
        if (this instanceof HomeActivity) {
            ((HomeActivity) this).buildHomeUi();
            requestNotifPermission();
            com.carlos.vault.daily.DailySchedulerService.ensureStarted(this);
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... p) {
                    engine.ensureEngineReady();
                    return null;
                }

                @Override
                protected void onPostExecute(Void v) {
                    ((HomeActivity) AccountVaultActivity.this).onEngineReady();
                }
            }.execute();
            return;
        }
        buildUi();
        refresh();
        requestNotifPermission();
        // 日常自动执行服务：App 打开即确保在跑（开机另有广播兜底）
        com.carlos.vault.daily.DailySchedulerService.ensureStarted(this);

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
                    status.postDelayed(() -> autoWizardIfIdle(0), 300);
                }
            }
        }.execute();
    }

    /**
     * 引擎就绪后的向导自动弹出（仅用户空闲时）。
     *
     * <p>引擎就绪的回调可能落在用户操作过程中（如批量导入正在输入/结果弹窗正显示），
     * 此时活动窗口失去焦点（对话框/输入法各自成窗）。直接弹向导会覆盖用户正在看
     * 的弹窗（实测事故：导入结果汇总被向导顶掉）。这里以 {@link #hasWindowFocus()}
     * 为"用户空闲"信号：忙则 1.5s 后重试（带上限，超限放弃，仍可从 ☰ 菜单进入），
     * 并在每次触发前复核向导是否仍必要（用户可能已通过菜单完成初始化）。
     */
    private static final int AUTO_WIZARD_MAX_RETRY = 60;   // 60 × 1.5s ≈ 1.5 分钟

    private void autoWizardIfIdle(int retry) {
        if (isFinishing() || isDestroyed()) return;
        if (engine.isGameInstalled() && engine.hasBaseline()) return;  // 已配置完成，不再打扰
        if (!hasWindowFocus()) {                                       // 弹窗/输入法在前：顺延
            if (retry < AUTO_WIZARD_MAX_RETRY) {
                status.postDelayed(() -> autoWizardIfIdle(retry + 1), 1500);
            }
            return;
        }
        showWizard();
    }

    private String reason(String e) {
        return (e == null || e.isEmpty()) ? "未知原因" : e;
    }

    // ---------- 授权（顶部状态条 / 换码入口） ----------

    /** 原地刷新头部授权圆环（颜色/百分比/环心文字）。 */
    protected void refreshLicenseBar() {
        if (licenseRing == null || licenseManager == null) return;
        licenseRing.updateFrom(licenseManager);
    }

    // ---------- 右上角 ☰ 功能菜单 ----------

    /** 功能菜单：初始化向导 / 克隆软件 / 微信支付 / 授权管理（替换原头部三按钮）。 */
    /** 子类可向主菜单追加条目（HomeActivity 用于「一键帮助全盟」）。 */
    protected void addMainMenuExtras(LinearLayout box, LinearLayout.LayoutParams rowLp) {
    }

    protected void showMainMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 16));
        box.setPadding(dp(22), dp(18), dp(22), dp(8));

        TextView title = new TextView(this);
        title.setText("功能菜单");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(0, 0, 0, dp(8));
        box.addView(title);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);

        LinearLayout r1 = UiStyle.makeRowBtn(this, "🧭", "初始化向导（首次配置四步走）",
                UiStyle.BRAND, this::showWizard);
        box.addView(r1, rowLp);

        LinearLayout r2 = UiStyle.makeRowBtn(this, "📥", "克隆软件（无尽冬日装进号库）",
                UiStyle.BRAND, () -> onWizardStep(0));
        box.addView(r2, new LinearLayout.LayoutParams(rowLp));

        LinearLayout r3 = UiStyle.makeRowBtn(this, "💚", "微信支付（容器微信登录/充值）",
                UiStyle.WECHAT_GREEN, this::doWechatPay);
        box.addView(r3, new LinearLayout.LayoutParams(rowLp));

        LinearLayout r4 = UiStyle.makeRowBtn(this, "🔑", "授权管理（兑换码/到期详情）",
                UiStyle.RUN_INK, this::showLicenseMenu);
        box.addView(r4, new LinearLayout.LayoutParams(rowLp));

        addMainMenuExtras(box, rowLp);

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(UiStyle.INK2);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(14), 0, dp(8));

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box)
                .create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        UiStyle.press(close);
        close.setOnClickListener(v -> dlg.dismiss());
        box.addView(close);
        dlg.show();
    }

    // ---------- 协议 PoC：号库账号直连游戏服务器登录 ----------

    /** 列出有 token 的号库账号，选中即用其 fpid+token 跑协议登录验证。 */
    private void showProtocolPoc() {
        List<AccountStore.Account> candidates = new ArrayList<>();
        if (accounts != null) {
            for (AccountStore.Account a : accounts) {
                if (a != null && a.token != null && !a.token.isEmpty()
                        && a.fpid != null && !a.fpid.isEmpty()) {
                    candidates.add(a);
                }
            }
        }
        if (candidates.isEmpty()) {
            toast("没有带 token 的账号可测，请先导入");
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).label + "（" + candidates.get(i).fpid + "）";
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("选择账号 · 协议登录验证")
                .setItems(names, (d, which) -> runProtocolPoc(candidates.get(which)))
                .show();
    }

    private void runProtocolPoc(AccountStore.Account account) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("直连游戏服务器登录中…\n账号 " + account.fpid);
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<AccountStore.Account, Void, String>() {
            @Override
            protected String doInBackground(AccountStore.Account... p) {
                com.carlos.vault.wjdr.WjdrEngine engine =
                        com.carlos.vault.daily.WjdrAndroid.newEngine(AccountVaultActivity.this);
                try {
                    engine.initialize();
                    com.carlos.vault.wjdr.WjdrEngine.LoginResult r =
                            engine.login(p[0].fpid, p[0].token);
                    return "✅ 登录成功\n\nuid: " + r.uid + "\n区服: " + r.serverId
                            + "\n角色名: " + (r.gameName == null || r.gameName.isEmpty() ? "-" : r.gameName)
                            + "\n\n载荷摘要: " + r.loadSummary;
                } catch (Throwable t) {
                    StringBuilder sb = new StringBuilder("❌ 失败: ")
                            .append(t.getClass().getSimpleName()).append('\n')
                            .append(t.getMessage());
                    Throwable c = t.getCause();
                    while (c != null) {
                        sb.append("\n← 因: ").append(c.getClass().getSimpleName())
                                .append(": ").append(c.getMessage());
                        StackTraceElement[] st = c.getStackTrace();
                        for (int i = 0; i < Math.min(6, st.length); i++) {
                            sb.append("\n    at ").append(st[i]);
                        }
                        c = c.getCause();
                    }
                    return sb.toString();
                } finally {
                    engine.close();
                }
            }

            @Override
            protected void onPostExecute(String result) {
                try { pd.dismiss(); } catch (Throwable ignored) { }
                // vivo 压制应用日志：结果同步落文件（shell 可读）
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "poc_result.txt"));
                    w.write(result);
                    w.close();
                } catch (Throwable ignored) { }
                UiStyle.showConfirmDialog(AccountVaultActivity.this, "协议 PoC 结果", result,
                        "知道了", UiStyle.BRAND, null);
            }
        }.execute(account);
    }

    // ---------- 卡片悬浮操作组（▶启动 / ⇅排序 / ⋯管理） ----------

    /** 在被点卡片下方弹出悬浮操作组；点击任一按钮或空白处关闭。 */
    protected void showTileActions(View anchorView, EntryLayout.Row row) {
        dismissTilePopup();
        final AccountStore.Account ac = accountById(row.itemId);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 20));
        box.setElevation(dp(8));
        box.setPadding(dp(5), dp(5), dp(5), dp(5));

        addTileAction(box, "▶ 启动", UiStyle.BRAND, () -> {
            if (ac != null) doSwitch(ac);
        });
        addTileAction(box, "⇅ 排序", UiStyle.RUN_INK, () -> {
            expandAllGroups();
            dragArm = true;
            String hint = "排序中：长按任意卡片拖到目标位置，松手自动保存";
            toast(hint);
            if (status != null) status.setText(hint);
        });
        addTileAction(box, "⋯ 管理", UiStyle.BRAND_TEXT, () -> {
            Intent it = new Intent(this, EntryDetailActivity.class);
            it.putExtra(EntryDetailActivity.EXTRA_ID, row.itemId);
            startActivity(it);
        });
        addTileAction(box, "🎁 每日任务", UiStyle.OK_INK, () -> {
            Intent it = new Intent(this, com.carlos.vault.daily.FunctionActivity.class);
            it.putExtra(com.carlos.vault.daily.FunctionActivity.EXTRA_ACCOUNT_ID, row.itemId);
            it.putExtra(com.carlos.vault.daily.FunctionActivity.EXTRA_FN_ID, "daily_mission");
            startActivity(it);
        });

        tilePopup = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        tilePopup.setOutsideTouchable(true);
        tilePopup.setElevation(dp(8));
        tilePopup.setBackgroundDrawable(new GradientDrawable());
        tilePopup.showAsDropDown(anchorView, 0, -dp(8));
    }

    private void addTileAction(LinearLayout box, String label, int color, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UiStyle.roundRect(color, 16));
        b.setPadding(dp(13), dp(9), dp(13), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        box.addView(b, lp);
        UiStyle.press(b);
        b.setOnClickListener(v -> {
            dismissTilePopup();
            action.run();
        });
    }

    private void dismissTilePopup() {
        if (tilePopup != null) {
            try {
                tilePopup.dismiss();
            } catch (Throwable ignored) {
            }
            tilePopup = null;
        }
    }

    /** 授权菜单：查看当前状态，或更换兑换码重新激活。 */
    protected void showLicenseMenu() {
        if (licenseManager == null) return;
        StringBuilder msg = new StringBuilder(licenseManager.describeRemain());
        String expire = licenseManager.expireDateText();
        if (!expire.isEmpty()) {
            msg.append("\n到期日：").append(expire);
        }
        msg.append("\n\n如已购买新的兑换码，可点「更换」重新激活。");

        UiStyle.showConfirmDialog(this, "授权状态", msg.toString(), "更换", UiStyle.BRAND,
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
        // HomeActivity 例外：它不走 buildUi()（status 恒为 null），不能被这条守卫拦掉，
        // 否则从功能页/游戏返回时永远不刷新（三页数据停留在离开前的旧状态）。
        if (status == null && !(this instanceof HomeActivity)) return;

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
        dismissTilePopup();
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
        frame.setBackgroundColor(UiStyle.BG_V2);

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
        title.setTextColor(UiStyle.BRAND_TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 授权状态圆环：绿=充足 黄=≤3天 红=过期/废除 ∞=永久；点击直达授权管理。
        licenseRing = LicenseUi.buildRing(this, licenseManager);
        UiStyle.press(licenseRing);
        licenseRing.setOnClickListener(v -> showLicenseMenu());
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ringLp.rightMargin = dp(12);
        header.addView(licenseRing, ringLp);

        // 功能菜单（替换原 排序/授权/？ 三按钮）：向导 / 克隆 / 微信支付 / 授权。
        TextView menuBtn = new TextView(this);
        menuBtn.setText("☰");
        menuBtn.setTextSize(16);
        menuBtn.setTextColor(UiStyle.BRAND);
        menuBtn.setGravity(Gravity.CENTER);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setShape(GradientDrawable.OVAL);
        menuBg.setColor(UiStyle.WAIT_50);
        menuBtn.setBackground(menuBg);
        UiStyle.press(menuBtn);
        menuBtn.setOnClickListener(v -> showMainMenu());
        header.addView(menuBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(header);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(UiStyle.INK2);
        status.setPadding(dp(20), dp(4), dp(20), dp(12));
        root.addView(status);

        // 九宫格：RecyclerView + 3 列 GridLayoutManager（androidx，项目里已有，零新增依赖）。
        //
        // 为什么从 GridView 换成 RecyclerView：**GridView 不支持跨列**，分组表头只能占 1/3 格宽，
        // 做不出"表头整行通铺"的手风琴面板。换过来后用 SpanSizeLookup 让表头占满 SPAN 格。
        recycler = new RecyclerView(this);
        // 底部留出悬浮加号（56dp + 28dp 边距）的高度，最后一行滚到底时不被遮挡
        recycler.setPadding(dp(6), dp(4), dp(6), dp(96));
        recycler.setClipToPadding(false);
        recycler.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        // 展开/收起要有逐项动画（手风琴感），别关掉默认动画器。
        recycler.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        layoutManager = new GridLayoutManager(this, SPAN);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                EntryLayout.Row r = rowAt(position);
                // 分组表头 ⇒ 通铺整行；入口卡片 ⇒ 占 1 格。
                return (r != null && r.type == EntryLayout.TYPE_HEADER) ? SPAN : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);
        // GridView 的 vertical/horizontal spacing 在 RecyclerView 里没有直接对应项：
        // 用 ItemDecoration 四边各留半格（dp(6)）⇒ 相邻元素之间仍是 dp(12)，边缘也是 dp(12)。
        recycler.addItemDecoration(new SpaceDecoration(dp(6)));
        root.addView(recycler, new LinearLayout.LayoutParams(
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
        fabBg.setColor(UiStyle.BRAND);
        fab.setBackground(fabBg);
        fab.setElevation(dp(6));
        UiStyle.press(fab);
        fab.setOnClickListener(v -> showCreateEntry());
        fab.setOnLongClickListener(v -> { showBatchImport(); return true; });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabLp.gravity = Gravity.END | Gravity.BOTTOM;
        fabLp.setMargins(0, 0, dp(24), dp(28));
        frame.addView(fab, fabLp);

        setContentView(frame);

        adapter = new TileAdapter();
        recycler.setAdapter(adapter);
        // 排序模式内的拖拽：ItemTouchHelper（androidx 自带，零新增依赖）。
        // ⚠️ 长按已被"打开详情"占用 ⇒ 关掉 ITH 自带的"长按起拖"，改由 tile 右上角 ⇅ 把手手动
        // startDrag（见 TileHolder 的把手 onTouch）。单击/长按的语义与 GridView 时代完全一致。
        itemTouchHelper = new ItemTouchHelper(new DragCallback());
        itemTouchHelper.attachToRecyclerView(recycler);
    }

    private void refresh() {
        // 守卫：新版四 Tab（HomeActivity）不构建经典九宫格（adapter/status 均为 null），
        // 直接 notifyDataSetChanged() 会 NPE——实测事故：批量导入完成回调与启动分身成功回调
        // 都会走到这里，曾导致导入后 App 崩溃重启（被误判为"向导顶掉结果弹窗"）。
        // 数据变化交由子类刷新对应页面。
        if (adapter == null) {
            if (this instanceof HomeActivity) {
                // 四 Tab 主界面的账号列表是内存快照（仅 onCreate / 批量删除会重载），
                // 导入、新增入口、token 校准等落盘后必须先重载，否则三页展示与磁盘割裂：
                // 账号数不变、旧 fpid 查不到角色表（实测：导入成功 46 个，界面仍显示 45）。
                if (store != null) accounts = store.all();
                ((HomeActivity) this).onAccountsChanged();
            }
            return;
        }
        accounts = store.all();
        rebuildDisplayItems();
        adapter.notifyDataSetChanged();
        String s;
        if (!engine.isGameInstalled()) {
            s = "游戏未克隆 · 点右上角 ☰ → 克隆软件 开始初始化";
        } else if (!engine.hasBaseline()) {
            s = "分身就绪 · 完成初始化后即可添加账号";
        } else {
            s = "就绪 · 点卡片出操作（启动 / 排序 / 管理 / 日常）";
        }
        status.setText(s);
    }

    /**
     * 由 {@link #accounts} 重建展示行。
     *
     * 展示顺序 = 分组顺序（"未分组"置顶）+ 组内顺序；分组顺序/折叠态来自 {@code group_meta}。
     * 全部规则（含"空组仍显示""孤儿分组不丢条目"）都在 {@link EntryLayout#buildRows} 里，
     * 这里不重复实现。
     */
    private void rebuildDisplayItems() {
        layoutItems = new ArrayList<>();
        if (accounts != null) {
            for (AccountStore.Account a : accounts) {
                layoutItems.add(new EntryLayout.Item(a.id, a.group));
            }
        }
        groupMeta = store.getGroupMeta();
        displayItems = EntryLayout.buildRows(layoutItems, groupMeta);
    }

    // ---------- ②排序模式 / 拖拽 ----------

    private EntryLayout.Row rowAt(int position) {
        if (displayItems == null || position < 0 || position >= displayItems.size()) return null;
        return displayItems.get(position);
    }

    private AccountStore.Account accountById(String id) {
        if (accounts == null || id == null) return null;
        for (AccountStore.Account a : accounts) {
            if (a != null && id.equals(a.id)) return a;
        }
        return null;
    }

    /** 某个条目 id 当前在展示列表里的行下标（拖拽过程中要重新定位被拖的条目）。 */
    private int rowIndexOfItem(String itemId) {
        if (displayItems == null || itemId == null) return -1;
        for (int i = 0; i < displayItems.size(); i++) {
            EntryLayout.Row r = displayItems.get(i);
            if (r.type == EntryLayout.TYPE_ENTRY && itemId.equals(r.itemId)) return i;
        }
        return -1;
    }

    /**
     * 拖拽排序前展开全部分组：跨组拖拽要求目标组可见（表头不可作落点）。
     * 取代原「排序模式」——拖拽由卡片悬浮操作组的 ⇅ 按钮直接发起。
     */
    private void expandAllGroups() {
        boolean changed = false;
        if (groupMeta != null) {
            for (EntryLayout.Group g : groupMeta) {
                if (g != null && g.name != null
                        && !EntryLayout.UNGROUPED_TITLE.equals(g.name) && g.collapsed) {
                    g.collapsed = false;
                    changed = true;
                }
            }
        }
        if (changed) {
            store.setGroupMeta(EntryLayout.toMeta(groupMeta));
            rebuildDisplayItems();
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 排序模式内的拖拽回调（{@link ItemTouchHelper}，androidx 自带 ⇒ 零新增依赖）。
     *
     * 三条硬规则：
     * <ol>
     *   <li>非排序模式 ⇒ 一律不可拖；</li>
     *   <li>分组表头**不可作为拖拽源**（它是分组，不是条目）；</li>
     *   <li>分组表头**也不可作为落点**（用户拍板）。</li>
     * </ol>
     *
     * <p>⚠️ 与"单开手风琴"叠加会产生一个坑，本实现已规避：非排序模式下一次只展开一组，
     * 而表头又不能作落点 ⇒ 若排序模式也做单开互斥，**跨组拖拽将完全不可达**（要落到的目标组
     * 必须展开，可一展开源组就收起、被拖的条目就看不见了）。因此 {@link #onHeaderClickAt}
     * 在**排序模式内不做单开互斥** —— 进排序模式后可以同时展开多组，再往下拖，
     * 于是"表头不可落"与"可跨组"能同时成立。代价：想拖进**空组**做不到（空组只剩表头），
     * 需先把该组改成有成员或改用详情页的「移动到分组…」。已上报给 team-lead 知悉。
     */
    private class DragCallback extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
            EntryLayout.Row row = rowAt(vh.getAdapterPosition());
            if (row == null || row.type != EntryLayout.TYPE_ENTRY) return 0;   // 表头不可作源
            int dir = ItemTouchHelper.UP | ItemTouchHelper.DOWN
                    | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
            return makeMovementFlags(dir, 0);   // 不需要滑动删除
        }

        /** 仅「排序中」（dragArm）允许 ITH 默认长按起拖；平时长按弹操作组。 */
        @Override
        public boolean isLongPressDragEnabled() {
            return dragArm;
        }

        @Override
        public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from,
                              RecyclerView.ViewHolder to) {
            int f = from.getAdapterPosition();
            int t = to.getAdapterPosition();
            if (f < 0 || t < 0 || f == t) return false;
            EntryLayout.Row src = rowAt(f);
            EntryLayout.Row dst = rowAt(t);
            if (src == null || src.type != EntryLayout.TYPE_ENTRY) return false;
            if (dst == null) return false;
            if (dst.type == EntryLayout.TYPE_HEADER) return false;   // 表头不可作落点（用户拍板）
            if (!EntryLayout.applyDrag(layoutItems, displayItems, f, t)) return false;
            String movedId = src.itemId;
            displayItems = EntryLayout.buildRows(layoutItems, groupMeta);
            // applyDrag 只重排、不增减行（表头数不变、条目数不变）⇒ 新列表是原列表的一个排列，
            // 因此用 notifyItemMoved 精确通知即可，RecyclerView 自带位移动画。
            int np = rowIndexOfItem(movedId);
            if (np >= 0 && np != f) adapter.notifyItemMoved(f, np);
            return true;
        }

        /** 松手：写回 accounts 并**同步**落盘（commit —— 拖完立刻划掉 App 也不丢），
         *  同时退出「排序中」状态并恢复状态栏文案。 */
        @Override
        public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            persistOrder();
            if (dragArm) {
                dragArm = false;
                refresh();
            }
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
            // 不支持滑动删除
        }
    }

    /**
     * 松手落盘：把 {@link #layoutItems} 的顺序与分组写回 accounts，并**同步**保存
     * （{@code commit()}）——拖完立刻把 App 划掉都不会丢。
     *
     * 用"逐条 consume"的方式重建 accounts 而不是直接映射，是为了保证**一条都不会丢**：
     * 万一 layoutItems 与 accounts 对不齐（理论上不会），落单的条目会原样追加到末尾。
     */
    private void persistOrder() {
        if (accounts == null || layoutItems == null) return;
        List<AccountStore.Account> remaining = new ArrayList<>(accounts);
        List<AccountStore.Account> ordered = new ArrayList<>();
        for (EntryLayout.Item it : layoutItems) {
            for (int i = 0; i < remaining.size(); i++) {
                AccountStore.Account a = remaining.get(i);
                if (a != null && a.id != null && a.id.equals(it.id)) {
                    remaining.remove(i);
                    a.group = it.group == null ? "" : it.group;
                    ordered.add(a);
                    break;
                }
            }
        }
        ordered.addAll(remaining);   // 兜底：一条都不能丢
        accounts = ordered;
        store.saveOrder(accounts);
    }

    // ---------- 创建入口 / 批量导入 ----------

    protected void showCreateEntry() {
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
    protected void showBatchImport() {
        final AccountImport.ConflictPolicy[] policy = {AccountImport.ConflictPolicy.SKIP};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, dp(20)));
        box.setPadding(dp(20), dp(20), dp(20), dp(18));

        TextView title = new TextView(this);
        title.setText("批量导入 token");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        box.addView(title);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, dp(8), 0, 0);
        TextView hint = new TextView(this);
        hint.setText("每行一条，格式：备注|号ID----token");
        hint.setTextSize(12);
        hint.setTextColor(UiStyle.INK2);
        subRow.addView(hint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pick = new TextView(this);
        pick.setText("选择文件");
        pick.setTextSize(11);
        pick.setTextColor(UiStyle.BRAND_TEXT);
        UiStyle.press(pick);
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
        subRow.addView(pick);
        box.addView(subRow);

        final EditText et = new EditText(this);
        et.setHint("温柔888|91347205----eyJhbGciOi...\n丢丢888|91351620----eyJhbGciOi...\n老大888|91359041----eyJhbGciOi...");
        et.setTextSize(12);
        et.setTextColor(UiStyle.INK);
        et.setHintTextColor(UiStyle.INK3);
        et.setGravity(Gravity.TOP);
        et.setMinLines(5);
        et.setMaxLines(8);
        et.setBackground(UiStyle.roundRectDp(this, UiStyle.SURFACE_2, 12));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        etLp.topMargin = dp(14);
        box.addView(et, etLp);
        importInput = et;

        TextView policyTitle = new TextView(this);
        policyTitle.setText("冲突策略（号 ID 已存在时）");
        policyTitle.setTextSize(13);
        policyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        policyTitle.setTextColor(UiStyle.INK);
        policyTitle.setPadding(0, dp(16), 0, dp(6));
        box.addView(policyTitle);

        final LinearLayout policyBox = new LinearLayout(this);
        policyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(policyBox);
        final Runnable[] renderPolicies = new Runnable[1];
        renderPolicies[0] = () -> {
            policyBox.removeAllViews();
            String[] labels = {"跳过冲突（推荐）", "覆盖旧 token", "仅新增，冲突报错"};
            AccountImport.ConflictPolicy[] values = {
                    AccountImport.ConflictPolicy.SKIP,
                    AccountImport.ConflictPolicy.OVERWRITE,
                    AccountImport.ConflictPolicy.ERROR,
            };
            for (int i = 0; i < labels.length; i++) {
                final AccountImport.ConflictPolicy val = values[i];
                boolean sel = policy[0] == val;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                android.widget.FrameLayout radio = new android.widget.FrameLayout(this);
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(sel ? UiStyle.BRAND_50 : UiStyle.WHITE);
                ring.setStroke(dp(2), sel ? UiStyle.BRAND : UiStyle.LINE_STRONG);
                radio.setBackground(ring);
                if (sel) {
                    View dot = new View(this);
                    dot.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 999));
                    radio.addView(dot, new android.widget.FrameLayout.LayoutParams(
                            dp(8), dp(8), Gravity.CENTER));
                }
                row.addView(radio, new LinearLayout.LayoutParams(dp(20), dp(20)));
                TextView tx = new TextView(this);
                tx.setText(labels[i]);
                tx.setTextSize(13.5f);
                tx.setTextColor(sel ? UiStyle.INK : UiStyle.INK2);
                tx.setPadding(dp(10), 0, 0, 0);
                row.addView(tx);
                UiStyle.press(row);
                row.setOnClickListener(v -> {
                    policy[0] = val;
                    renderPolicies[0].run();
                });
                policyBox.addView(row);
            }
        };
        renderPolicies[0].run();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(16), 0, 0);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(UiStyle.INK2);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 14, UiStyle.LINE));
        cancel.setPadding(0, dp(13), 0, dp(13));
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView go = new TextView(this);
        go.setText("导入");
        go.setTextSize(14);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setTextColor(UiStyle.WHITE);
        go.setGravity(Gravity.CENTER);
        go.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 14));
        go.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        glp.leftMargin = dp(10);
        btnRow.addView(go, glp);
        box.addView(btnRow);

        TextView note = new TextView(this);
        note.setText("导入后可导出失败明细；token 等同登录凭证，请勿外传");
        note.setTextSize(11);
        note.setTextColor(UiStyle.INK3);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.setOnDismissListener(d -> {
            if (importInput == et) importInput = null;
        });
        UiStyle.press(cancel);
        cancel.setOnClickListener(v -> dlg.dismiss());
        UiStyle.press(go);
        go.setOnClickListener(v -> {
            String text = et.getText() == null ? "" : et.getText().toString();
            dlg.dismiss();
            runImport(text, policy[0]);
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
        title.setTextColor(UiStyle.INK);
        box.addView(title);

        TextView sum = new TextView(this);
        sum.setText("成功 " + r.ok + " 个 · 失败 " + r.failed + " 条 · 跳过 " + r.skipped + " 条");
        sum.setTextSize(14);
        sum.setTextColor(r.failed > 0 ? UiStyle.ERR_INK : UiStyle.INK);
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
            det.setTextColor(UiStyle.ERR_INK);
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
            exp.setTextColor(UiStyle.BRAND);
            exp.setPadding(dp(16), dp(10), dp(16), dp(10));
            btnRow.addView(exp);
            UiStyle.press(exp);
            exp.setOnClickListener(v -> exportFailures());
        }

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(UiStyle.INK2);
        close.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnRow.addView(close);
        box.addView(btnRow);

        AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        UiStyle.press(close);
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

    protected void doSwitch(AccountStore.Account acc) {
        doSwitch(acc, null);   // 无角色语境的调用方：沿用账号备注名
    }

    /** @param labelOverride 角色语境的显示名（如"K913·铁柱"）；null 沿用账号备注名。 */
    protected void doSwitch(AccountStore.Account acc, String labelOverride) {
        if (!engine.isGameInstalled()) {
            toast("请先完成初始化");
            return;
        }
        String show = labelOverride == null || labelOverride.isEmpty() ? acc.label : labelOverride;
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在切换到 «" + show + "» …");
        pd.setCancelable(false);
        pd.show();
        String lastId = store.getLastEntry();
        new AsyncTask<AccountStore.Account, Void, Boolean>() {
            final AccountStore.Account target = acc;
            final String showName = show;

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
                    toast("已启动 «" + showName + "»");
                } else {
                    toast("启动失败：请从任务栏划掉游戏后重试");
                }
            }
        }.execute(acc);
    }

    // ---------- 初始化向导 ----------

    protected void showWizard() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(14), dp(6));
        android.widget.FrameLayout back = new android.widget.FrameLayout(this);
        back.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.INK, 22),
                new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(back);
        back.setOnClickListener(v -> dlg.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("初始化向导");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(2), dp(18), dp(28));
        sc.addView(box);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView subtitle = new TextView(this);
        subtitle.setText("只需一次 · 把无尽冬日装进号库并锁定干净登录态");
        subtitle.setTextSize(12);
        subtitle.setTextColor(UiStyle.INK2);
        box.addView(subtitle);

        boolean installed = engine.isGameInstalled();
        boolean hasBase = engine.hasBaseline();
        int curStep = !installed ? 0 : !hasBase ? 1 : 3;

        LinearLayout segs = new LinearLayout(this);
        segs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        segsLp.topMargin = dp(16);
        box.addView(segs, segsLp);
        for (int i = 0; i < 4; i++) {
            View seg = new View(this);
            seg.setBackground(UiStyle.roundRectDp(this,
                    i <= curStep ? UiStyle.BRAND : UiStyle.LINE, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            if (i > 0) lp.leftMargin = dp(8);
            segs.addView(seg, lp);
        }
        String[] labels = {"① 克隆", "② 首启", "③ 重置", "④ 基线"};
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < 4; i++) {
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(11.5f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(i <= curStep ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            t.setTypeface(i == curStep ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            labelRow.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(labelRow);

        // 步骤 1 卡
        LinearLayout step1 = UiStyle.outlineCard(this);
        step1.setOrientation(LinearLayout.VERTICAL);
        step1.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams s1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s1lp.topMargin = dp(16);
        step1.setLayoutParams(s1lp);
        LinearLayout s1head = new LinearLayout(this);
        s1head.setOrientation(LinearLayout.HORIZONTAL);
        s1head.setGravity(Gravity.CENTER_VERTICAL);
        s1head.addView(UiStyle.iconTile(this, UiStyle.Icon.BOX, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 36, 18, 10),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView s1title = new TextView(this);
        s1title.setText("克隆游戏");
        s1title.setTextSize(18);
        s1title.setTypeface(Typeface.DEFAULT_BOLD);
        s1title.setTextColor(UiStyle.INK);
        s1title.setPadding(dp(10), 0, dp(8), 0);
        s1head.addView(s1title);
        s1head.addView(UiStyle.badge(this, installed ? "ok" : "run",
                installed ? "已完成" : "进行中"));
        step1.addView(s1head);
        TextView s1desc = new TextView(this);
        s1desc.setText("从手机已安装的无尽冬日引用克隆，秒完成，不重复下载 2.2GB 资源。");
        s1desc.setTextSize(12);
        s1desc.setTextColor(UiStyle.INK2);
        s1desc.setPadding(0, dp(8), 0, 0);
        step1.addView(s1desc);
        TextView s1btn = UiStyle.primaryBtn(this, installed ? "重新克隆" : "开始克隆", null);
        LinearLayout.LayoutParams s1blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        s1blp.topMargin = dp(12);
        s1btn.setLayoutParams(s1blp);
        UiStyle.press(s1btn);
        s1btn.setOnClickListener(v -> onWizardStep(0));
        step1.addView(s1btn);
        box.addView(step1);

        // 步骤 2-4 卡
        LinearLayout step2 = UiStyle.outlineCard(this);
        step2.setOrientation(LinearLayout.VERTICAL);
        step2.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams s2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s2lp.topMargin = dp(12);
        step2.setLayoutParams(s2lp);
        String[][] rows = {
                {"首次启动", "进游戏下载约 2.2GB 资源（仅此一次）"},
                {"重置到登录界面", "清数据并重启，出现登录界面后退回"},
                {"保存基线", "锁定干净登录态，之后即可添加账号"},
        };
        for (int i = 0; i < rows.length; i++) {
            final int step = i + 1;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            TextView num = new TextView(this);
            num.setText(String.valueOf(step + 1));
            num.setTextSize(13);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setTextColor(UiStyle.INK2);
            num.setGravity(Gravity.CENTER);
            num.setBackground(UiStyle.roundRectDp(this, UiStyle.SURFACE_3, 999));
            row.addView(num, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            TextView t = new TextView(this);
            t.setText(rows[i][0]);
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(UiStyle.INK);
            mid.addView(t);
            TextView s = new TextView(this);
            s.setText(rows[i][1]);
            s.setTextSize(11.5f);
            s.setTextColor(UiStyle.INK2);
            s.setPadding(0, dp(3), 0, 0);
            mid.addView(s);
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON, UiStyle.INK3, 16),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
            UiStyle.press(row);
            row.setOnClickListener(v -> onWizardStep(step));
            step2.addView(row);
        }
        box.addView(step2);

        LinearLayout why = new LinearLayout(this);
        why.setOrientation(LinearLayout.VERTICAL);
        why.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND_50, 16));
        why.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(12);
        why.setLayoutParams(wlp);
        TextView w1 = new TextView(this);
        w1.setText("为什么需要初始化？");
        w1.setTextSize(15);
        w1.setTypeface(Typeface.DEFAULT_BOLD);
        w1.setTextColor(UiStyle.BRAND_TEXT);
        why.addView(w1);
        TextView w2 = new TextView(this);
        w2.setText("基线 = 一份干净的未登录存档。之后每个账号都从基线还原再注入 token，才能做到秒切号、不串号。");
        w2.setTextSize(11.5f);
        w2.setTextColor(UiStyle.INK2);
        w2.setPadding(0, dp(5), 0, 0);
        why.addView(w2);
        box.addView(why);

        TextView skip = new TextView(this);
        skip.setText("跳过 · 稍后在「我的 → 初始化」完成");
        skip.setTextSize(12);
        skip.setTextColor(UiStyle.INK2);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(16), 0, dp(4));
        UiStyle.press(skip);
        skip.setOnClickListener(v -> dlg.dismiss());
        box.addView(skip);

        TextView wx = new TextView(this);
        wx.setText("⑤ 容器微信（登录后游戏内充值可拉起）");
        wx.setTextSize(11.5f);
        wx.setTextColor(UiStyle.BRAND_TEXT);
        wx.setGravity(Gravity.CENTER);
        wx.setPadding(0, dp(8), 0, 0);
        UiStyle.press(wx);
        wx.setOnClickListener(v -> doWechatPay());
        box.addView(wx);

        dlg.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.show();
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            try {
                win.setStatusBarColor(UiStyle.BG_V2);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 微信支付入口（菜单与向导⑤共用）：保数据降级到 8.0.53（原生收银台）→ 未装则从下载目录
     * 装入 → 打开容器微信。登录后游戏内充值可拉起微信支付。
     */
    protected void doWechatPay() {
        toast("检查容器微信版本…");
        new AsyncTask<Void, Void, String>() {
            boolean installed;
            @Override protected String doInBackground(Void... p) {
                // 支付收银台试验：8.0.67 支付页为 LiteApp 组件（容器内基础库无法自举），
                // 8.0.53 为原生收银台。已登录状态下保数据降级到 8.0.53。
                String err = engine.downgradeWechatKeepData();
                if (err == null && !engine.isWechatInstalled()) {
                    err = engine.ensureWechatFromApk();
                }
                installed = err == null && engine.isWechatInstalled();
                return err;
            }
            @Override protected void onPostExecute(String err) {
                if (err != null) {
                    toast(err);
                    return;
                }
                if (!installed) {
                    toast("微信尚未装入，请先放好APK再点一次");
                    return;
                }
                toast(engine.launchWechat() ? "已打开容器微信" : "容器微信启动失败");
            }
        }.execute();
    }

    protected void onWizardStep(int step) {
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

    private class TileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return displayItems == null ? 0 : displayItems.size();
        }

        @Override
        public int getItemViewType(int position) {
            EntryLayout.Row r = rowAt(position);
            return r == null ? VH_ENTRY : r.type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VH_HEADER) return new HeaderHolder(buildHeaderView());
            return new TileHolder(buildTileView());
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            EntryLayout.Row row = rowAt(position);
            if (row == null) return;
            if (row.type == VH_HEADER) ((HeaderHolder) holder).bind(row);
            else ((TileHolder) holder).bind(row);
        }
    }

    // ---------- 两种 ViewHolder ----------

    /** 分组表头（通铺整行）：左 = 组名 + 计数，右 = ▾/▸ 箭头（150ms 旋转过渡）。 */
    private class HeaderHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView cnt;
        private final TextView arrow;

        HeaderHolder(View v) {
            super(v);
            LinearLayout box = (LinearLayout) v;      // HORIZONTAL: [组名] [数量] [箭头]
            name = (TextView) box.getChildAt(0);
            cnt = (TextView) box.getChildAt(1);
            arrow = (TextView) box.getChildAt(2);
            // 点击按 **position** 取行，而不是 bind 时捕获的 Row 对象：
            // 后者在列表重建后可能是过期对象 ⇒ 点了没反应。
            UiStyle.press(itemView);
            itemView.setOnClickListener(v2 -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) onHeaderClickAt(pos);
            });
        }

        void bind(EntryLayout.Row row) {
            name.setText(row.title);
            // 用户拍板格式：`大号 (6) ▾`。数量在折叠时也显示 —— 否则用户不知道藏了多少条。
            cnt.setText("(" + row.count + ")");
            if (canCollapse(row)) {
                arrow.setVisibility(View.VISIBLE);
                arrow.setRotation(row.collapsed ? -90f : 0f);
                itemView.setAlpha(1f);
            } else {
                // 未分组（置顶、恒定展开）/ 孤儿分组（meta 里没有它 ⇒ 折叠态无处持久化）：
                // 不给箭头、点不动，并轻微置灰 —— 让它明确"不是可折叠面板"，而不是看起来像坏了。
                arrow.setVisibility(View.GONE);
                itemView.setAlpha(0.8f);
            }
        }

        /**
         * 就地更新表头（展开/收起时用）：改计数文案 + 箭头旋转动画。
         *
         * <p>与 {@link #bind} 的区别：bind 是**瞬时**置到终态（rebind 场景，不该有动画），
         * 这里是**带过渡**地更新（用户点了一下，要有 150ms 的箭头转动）。
         */
        void applyState(EntryLayout.Row row) {
            cnt.setText("(" + row.count + ")");
            animateArrow(row.collapsed);
        }

        /** 手风琴感：箭头 150ms 旋转（▾ ⇄ ▸），而不是整屏刷新。 */
        void animateArrow(boolean collapsed) {
            arrow.animate().cancel();
            arrow.animate().rotation(collapsed ? -90f : 0f).setDuration(150).start();
        }
    }

    /** 入口卡片：图标 + 名称 + 状态；排序模式内右上角 ⇅ 把手（按住起拖）。 */
    private class TileHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        /** 卡片底部左段：状态（token / 原生 / 无）。 */
        private final TextView subStatus;
        /** 卡片底部右段：最近一次登录时间（无则"未用"）。 */
        private final TextView subTime;

        TileHolder(View v) {
            super(v);
            FrameLayout tile = (FrameLayout) v;
            LinearLayout content = (LinearLayout) tile.getChildAt(0);
            icon = (ImageView) content.getChildAt(0);
            name = (TextView) content.getChildAt(1);
            // 第 3 个子视图已由单个居中 TextView 换成"状态 / 时间"横向两段容器（#71）。
            LinearLayout subRow = (LinearLayout) content.getChildAt(2);
            subStatus = (TextView) subRow.getChildAt(0);
            subTime = (TextView) subRow.getChildAt(1);
            // 单击 / 长按统一弹悬浮操作组（▶启动 / ⇅排序 / ⋯管理）——不再有隐藏手势差异。
            UiStyle.press(itemView);
            itemView.setOnClickListener(v2 -> {
                EntryLayout.Row r = rowAt(getAdapterPosition());
                if (r == null || r.type != EntryLayout.TYPE_ENTRY) return;
                showTileActions(itemView, r);
            });
            itemView.setOnLongClickListener(v2 -> {
                if (dragArm) return true;   // 排序中：长按交给 ITH 起拖
                EntryLayout.Row r = rowAt(getAdapterPosition());
                if (r == null || r.type != EntryLayout.TYPE_ENTRY) return true;
                showTileActions(itemView, r);
                return true;
            });
        }

        void bind(EntryLayout.Row row) {
            AccountStore.Account a = accountById(row.itemId);
            if (gameIcon != null) icon.setImageDrawable(gameIcon);
            icon.setAlpha(1f);
            name.setText(a == null ? "入口" : a.label);
            // 【#71】状态三态以 **token** 为准。
            // ⚠️ 修一处 bug：原写法判的是 fpid 而不是 token ⇒ "有 fpid、没 token"的入口
            // （例如备战号888：fpid=44714253、token 为空）会被**误显示成 token**，
            // 用户据此以为会用 token 登录，实际根本没有 token 可用。
            // 三态：有 token ⇒ "token"；无 token 但已初始化（有快照）⇒ "原生"；都没有 ⇒ "无"。
            boolean hasToken = a != null && a.token != null && !a.token.isEmpty();
            boolean hasSnap = a != null && engine.hasEntrySnapshot(a.id);
            subStatus.setText(hasToken ? "token" : (hasSnap ? "原生" : "无"));
            long lastLogin = a == null ? 0L : a.lastLogin;
            subTime.setText(lastLogin > 0 ? FMT.format(new Date(lastLogin)) : "未用");
        }
    }

    // ---------- 两种 item 视图的构造 ----------

    /** 分组表头视图：横向一行 `组名 (N) ▾`，通铺整行（由 SpanSizeLookup 给满 span）。 */
    private View buildHeaderView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(UiStyle.roundRect(UiStyle.CARD, 12));
        box.setElevation(dp(2));

        // [0] 组名（占满剩余宽度；过长省略，避免把数量/箭头挤出屏外）
        TextView name = new TextView(this);
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiStyle.INK);
        box.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // [1] 入口数量：`(6)`
        TextView cnt = new TextView(this);
        cnt.setTextSize(12);
        cnt.setTextColor(UiStyle.INK2);
        box.addView(cnt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // [2] 折叠箭头：▾（展开）/ ▸（收起）。用同一个 "▾" 旋转 -90° 得到 ▸，
        //     这样展开/收起之间能走 150ms 的旋转过渡（换字符是瞬变、没有过渡感）。
        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextSize(15);
        arrow.setTextColor(UiStyle.BRAND);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(24), dp(24));
        alp.leftMargin = dp(6);
        box.addView(arrow, alp);
        return box;
    }

    /**
     * 入口 tile：FrameLayout(卡片背景/阴影)
     *          ├ LinearLayout(VERTICAL) 图标/名称/状态（内容）
     *          └ TextView "⇅"（把手，END|TOP；非排序模式 GONE）
     * 用 FrameLayout 是为了让把手"浮"在内容右上角，同时不影响原有 tile 外观。
     */
    private View buildTileView() {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(UiStyle.roundRect(UiStyle.CARD, 14));
        tile.setElevation(dp(2));
        tile.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(8), dp(14), dp(8), dp(14));
        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));

        TextView name = new TextView(this);
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setTextColor(UiStyle.INK);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        content.addView(name, nlp);

        // 【#71】底部改为"状态（左） / 最近登录时间（右）"一行两段：
        // 状态段 weight=1 吃掉剩余宽度并省略尾部，时间段 WRAP_CONTENT 靠右 ⇒
        // 状态文案再长也挤不掉时间。
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView subStatus = new TextView(this);
        subStatus.setTextSize(10);
        subStatus.setMaxLines(1);
        subStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subStatus.setTextColor(UiStyle.INK2);
        subStatus.setGravity(Gravity.START);
        subRow.addView(subStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subTime = new TextView(this);
        subTime.setTextSize(10);
        subTime.setMaxLines(1);
        subTime.setTextColor(UiStyle.INK2);
        subTime.setGravity(Gravity.END);
        subRow.addView(subTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(subRow);
        return tile;
    }

    /** 相邻元素之间的间距：四边各半格 ⇒ 相邻 = 一整格（对齐 GridView 时代的 dp(12)）。 */
    private static class SpaceDecoration extends RecyclerView.ItemDecoration {
        private final int half;

        SpaceDecoration(int half) {
            this.half = half;
        }

        @Override
        public void getItemOffsets(android.graphics.Rect outRect, View view,
                                   RecyclerView parent, RecyclerView.State state) {
            outRect.set(half, half, half, half);
        }
    }

    /**
     * 点表头 = 手风琴折叠/展开。**单开**：展开某组时，其余可折叠组一并收起（用户拍板）。
     *
     * <p>持久化仍走既有的 {@code group_meta.collapsed}（{@code apply}，丢了不致命），
     * 字段名与序列化格式**完全不变** ⇒ 真机上已建好的「大号」等分组不会丢。
     *
     * <p>动画：不整屏刷新，而是把"展示行的变化"翻译成区间通知（{@link #notifyRowsChanged}），
     * RecyclerView 会为收起/展开的每一条做逐项动画；被点的那一行再就地做箭头 150ms 过渡
     * （{@link HeaderHolder#applyState}）。
     *
     * <p>两条边界：
     * <ul>
     *   <li>「未分组」置顶、恒定展开、**不参与单开互斥**，点了不折叠（见 {@link #canCollapse}）；</li>
     *   <li>**排序模式内不做单开互斥**（只切换被点的那一组）—— 否则源组与目标组无法同时展开，
     *       而表头又不可作拖拽落点 ⇒ 跨组拖拽会完全不可达。</li>
     * </ul>
     */
    private void onHeaderClickAt(int headerPos) {
        EntryLayout.Row row = rowAt(headerPos);
        if (!canCollapse(row)) return;                  // 未分组 / 孤儿分组：不可折叠

        boolean collapsing = !row.collapsed;            // 已展开 ⇒ 再点即收起
        List<EntryLayout.Row> before = displayItems;    // 旧行列表（留引用，供 diff 用）

        if (!collapsing) {
            // 展开：先把其余可折叠组全部收起（经典手风琴：一次只开一组；
            // 跨组拖拽的可达性由拖拽前 expandAllGroups 保证，与此不冲突）
            for (EntryLayout.Group g : groupMeta) {
                if (g != null && g.name != null && !EntryLayout.UNGROUPED_TITLE.equals(g.name)) {
                    g.collapsed = true;
                }
            }
        }
        EntryLayout.Group target = EntryLayout.findGroup(groupMeta, row.group);
        if (target == null) return;                     // 双保险
        target.collapsed = collapsing;

        store.setGroupMeta(EntryLayout.toMeta(groupMeta));
        displayItems = EntryLayout.buildRows(layoutItems, groupMeta);

        // ① 区间通知：收起/展开的那些条目走逐项动画
        notifyRowsChanged(before);
        // ② 被"单开"连带收起的表头，箭头方向也变了 ⇒ 逐行 rebind
        for (int j = 0; j < displayItems.size(); j++) {
            EntryLayout.Row r = displayItems.get(j);
            if (r.type != EntryLayout.TYPE_HEADER || sameGroupKey(r, row)) continue;
            EntryLayout.Row old = findHeaderByGroup(before, r.group);
            if (old != null && old.collapsed != r.collapsed) adapter.notifyItemChanged(j);
        }
        // ③ 被点的那一行：就地更新（数量 + 箭头过渡）。
        //    **刻意不 notifyItemChanged** —— 那会 rebind，bind() 把箭头瞬时置到终态，150ms 过渡就没了。
        int np = findHeaderIndex(row.group);
        RecyclerView.ViewHolder vh = np < 0 ? null : recycler.findViewHolderForAdapterPosition(np);
        if (vh instanceof HeaderHolder) {
            EntryLayout.Row nr = rowAt(np);
            ((HeaderHolder) vh).applyState(nr == null ? row : nr);
        }
    }

    /**
     * 该表头是否可折叠。
     *
     * <p>「未分组」永远置顶、恒定展开（不变式①）；**孤儿分组**（成员 group 指向 meta 未登记的
     * 组名，只在脏数据下出现）在 {@code group_meta} 里没有对应项、折叠态无处持久化，
     * 故同样按"不可折叠"处理（UI 上不给箭头、点击无反应，并轻微置灰以免看起来像坏了）。
     */
    private boolean canCollapse(EntryLayout.Row row) {
        if (row == null || row.type != EntryLayout.TYPE_HEADER) return false;
        if (row.ungrouped) return false;
        return EntryLayout.findGroup(groupMeta, row.group) != null;
    }

    private static boolean sameGroupKey(EntryLayout.Row a, EntryLayout.Row b) {
        String ga = a.group == null ? "" : a.group;
        String gb = b.group == null ? "" : b.group;
        return ga.equals(gb);
    }

    private static EntryLayout.Row findHeaderByGroup(List<EntryLayout.Row> rows, String group) {
        String g = group == null ? "" : group;
        if (rows == null) return null;
        for (EntryLayout.Row r : rows) {
            if (r.type == EntryLayout.TYPE_HEADER && g.equals(r.group == null ? "" : r.group)) return r;
        }
        return null;
    }

    /** 某个组在展示行里的表头下标（-1 = 没有）。 */
    private int findHeaderIndex(String group) {
        String g = group == null ? "" : group;
        if (displayItems == null) return -1;
        for (int i = 0; i < displayItems.size(); i++) {
            EntryLayout.Row r = displayItems.get(i);
            if (r.type == EntryLayout.TYPE_HEADER && g.equals(r.group == null ? "" : r.group)) return i;
        }
        return -1;
    }

    /** 某个组在展示行里的条目数。 */
    private static int countEntriesOfGroup(List<EntryLayout.Row> rows, String group) {
        String g = group == null ? "" : group;
        int n = 0;
        if (rows == null) return 0;
        for (EntryLayout.Row r : rows) {
            if (r.type == EntryLayout.TYPE_ENTRY && g.equals(r.group == null ? "" : r.group)) n++;
        }
        return n;
    }

    /**
     * 把"展示行从 before 变成现在"翻译成**区间通知**，而不是全量 {@code notifyDataSetChanged}。
     *
     * <p>本列表的行结构只有两种变化：某一组的条目**整段消失**（收起）或**整段出现**（展开）。
     * 于是：
     * <ol>
     *   <li>先按**旧下标降序**发 {@code notifyItemRangeRemoved} —— 从后往前删，前面待删的下标才不失效；</li>
     *   <li>再按**新下标升序**发 {@code notifyItemRangeInserted}。</li>
     * </ol>
     * 这样既有逐项动画（手风琴感），也不会出现"通知的位置与真实数据对不上"导致的错位或崩溃。
     * （单开时一次点击会同时收起一组、展开另一组，位置变化不止一处，所以必须按这个顺序发。）
     */
    private void notifyRowsChanged(List<EntryLayout.Row> before) {
        List<EntryLayout.Row> now = displayItems == null ? new ArrayList<>() : displayItems;

        List<int[]> removals = new ArrayList<>();        // {startInOld, count}
        collectEntryBlocks(before, now, removals);
        for (int k = removals.size() - 1; k >= 0; k--) {
            if (removals.get(k)[1] > 0) {
                adapter.notifyItemRangeRemoved(removals.get(k)[0], removals.get(k)[1]);
            }
        }

        List<int[]> insertions = new ArrayList<>();      // {startInNew, count}
        collectEntryBlocks(now, before, insertions);
        for (int[] r : insertions) {
            if (r[1] > 0) adapter.notifyItemRangeInserted(r[0], r[1]);
        }
    }

    /**
     * 收集 {@code list} 里那些"该组有条目、但在 {@code other} 里一条都没有"的**连续条目段**。
     *
     * <p>产出 {@code {起始下标, 条数}}。"在 other 里没有"这个条件用来区分"这段是新出现的"
     * 与"这段一直都在" —— 只有前者才需要发插入/移除通知。
     */
    private static void collectEntryBlocks(List<EntryLayout.Row> list,
                                           List<EntryLayout.Row> other, List<int[]> out) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            EntryLayout.Row h = list.get(i);
            if (h.type != EntryLayout.TYPE_HEADER) continue;
            int start = i + 1;
            int n = 0;
            while (start + n < list.size() && list.get(start + n).type == EntryLayout.TYPE_ENTRY) n++;
            if (n > 0 && countEntriesOfGroup(other, h.group) == 0) out.add(new int[]{start, n});
            i = start + n - 1;   // 跳到本组块末尾（for 里还会 ++）
        }
    }

}
