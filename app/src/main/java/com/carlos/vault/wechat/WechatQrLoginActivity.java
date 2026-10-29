package com.carlos.vault.wechat;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.AccountStore;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultEngine;
import com.carlos.vault.VaultSync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 微信扫码登录：显示二维码 → 手机微信扫码确认 → 自动换游戏账号（fpid + token）并入号库。
 *
 * <p>全链路（照抄 wjdr-tool release 434）：
 * 微信扫 App 二维码 → wx_code → 微信 access_token/openid/unionid → 游戏 passport
 * {@code login_with_sns} → fpid + session_key → 写入 {@link AccountStore}。
 * 之后在账号页点该账号即可走现有 token 注入自动登录。
 *
 * <p>微信 appid/app_key 取自容器内游戏缓存的远端配置，不硬编码到号库。
 */
public class WechatQrLoginActivity extends Activity {

    private static final String TAG = "WechatQrLogin";

    /** 入口详情页发起时传入账号入口 id：扫码结果直接回填到该入口。 */
    public static final String EXTRA_ENTRY_ID = "entry_id";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WechatQrClient client = new WechatQrClient();

    private ImageView qrView;
    private TextView statusView;
    private TextView subView;
    private View actionsRow;

    private volatile boolean running;
    private volatile int generation;
    private Thread worker;
    /** 非空 = 从入口详情页发起：扫码结果回填到该入口，而不是新建账号。 */
    private String entryId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        entryId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ENTRY_ID);
        buildUi();
        startFlow();
    }

    @Override
    protected void onDestroy() {
        running = false;
        generation++;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(UiStyle.BG_V2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setFitsSystemWindows(true);

        // 顶栏：返回 + 标题
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(UiStyle.BRAND);
        back.setGravity(Gravity.CENTER);
        back.setWidth(dp(40));
        back.setHeight(dp(40));
        UiStyle.press(back);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("微信扫码登录");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        header.addView(title);

        root.addView(header);

        TextView desc = new TextView(this);
        desc.setText(entryId == null || entryId.isEmpty()
                ? "用微信扫码授权，直接拿到游戏账号（fpid + token）；不需要在容器里装微信，也不需要走原生登录。"
                : "扫码后把号 ID 和 token 直接回填到本入口；不需要在容器里装微信，也不需要走原生登录。");
        desc.setTextSize(12.5f);
        desc.setTextColor(UiStyle.INK2);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(6), 0, dp(14));
        root.addView(desc);

        // 二维码卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(UiStyle.roundRectDp(this, UiStyle.CARD, 18));
        card.setPadding(dp(18), dp(18), dp(18), dp(14));

        FrameLayout qrBox = new FrameLayout(this);
        qrBox.setBackground(UiStyle.roundRectDp(this, 0xFFFFFFFF, 14));
        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrBox.addView(qrView, new FrameLayout.LayoutParams(dp(228), dp(228), Gravity.CENTER));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(252), dp(252));
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrBox, qrLp);

        statusView = new TextView(this);
        statusView.setText("正在获取微信登录二维码…");
        statusView.setTextSize(14);
        statusView.setTextColor(UiStyle.INK);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, dp(14), 0, dp(2));
        card.addView(statusView);

        subView = new TextView(this);
        subView.setText("二维码约 2 分钟有效；过期会自动换新。");
        subView.setTextSize(11.5f);
        subView.setTextColor(UiStyle.INK2);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(0, 0, 0, dp(4));
        card.addView(subView);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(2);
        root.addView(card, cardLp);

        // 操作按钮
        actionsRow = buildActions();
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(16);
        root.addView(actionsRow, actionLp);

        TextView tip = new TextView(this);
        tip.setText("· 扫码后请尽快在微信里点「确认授权」\n"
                + "· 登录会占用该微信绑定游戏号的会话（可能顶掉它当前的在线）；号库小号请自行判断\n"
                + "· 成功后自动加入账号页「未分组」，点它即可注入登录");
        tip.setTextSize(11.5f);
        tip.setTextColor(UiStyle.INK3);
        tip.setLineSpacing(dp(3), 1f);
        tip.setPadding(0, dp(18), 0, 0);
        root.addView(tip);

        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    private View buildActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView refresh = UiStyle.secondaryBtn(this, "刷新二维码", this::startFlow);
        row.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView close = UiStyle.ghostBtn(this, "关闭", () -> finish());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        closeLp.leftMargin = dp(10);
        row.addView(close, closeLp);
        return row;
    }

    private void setStatus(final String text, final boolean error) {
        ui.post(() -> {
            if (isFinishing()) return;
            statusView.setText(text);
            statusView.setTextColor(error ? 0xFFDC2626 : UiStyle.INK);
        });
    }

    private void setSub(final String text) {
        ui.post(() -> {
            if (isFinishing()) return;
            subView.setText(text);
        });
    }

    private void showQr(final byte[] image) {
        ui.post(() -> {
            if (isFinishing()) return;
            try {
                qrView.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.length));
            } catch (Throwable t) {
                Log.w(TAG, "decode qr failed", t);
            }
        });
    }

    // ---------------- 流程 ----------------

    /** 重新开始一轮扫码（刷新按钮/首次进入共用）。 */
    private void startFlow() {
        running = false;
        generation++;
        if (worker != null) worker.interrupt();
        final int gen = generation;
        running = true;
        if (qrView != null) qrView.setImageDrawable(null);
        setStatus("正在获取微信登录二维码…", false);
        worker = new Thread(() -> flow(gen), "wechat-qr-login");
        worker.start();
    }

    private void flow(int gen) {
        WechatQrLogin.Config config = loadConfig();
        if (config == null) {
            setStatus("没找到微信登录配置", true);
            setSub("请先在号库容器里启动一次游戏（生成游戏配置），再回来扫码。");
            return;
        }
        int failures = 0;
        while (running && gen == generation) {
            try {
                WechatQrClient.Qr qr = client.fetchQr(config, WechatQrLogin.randomState());
                if (!running || gen != generation) return;
                showQr(qr.image);
                setStatus("请用微信扫码并确认授权「无尽冬日」", false);
                setSub("二维码约 2 分钟有效；过期会自动换新。");
                failures = 0;

                long qrStart = System.currentTimeMillis();
                boolean refreshed = false;
                while (running && gen == generation && !refreshed) {
                    WechatQrLogin.Poll poll = client.poll(qr.uuid);
                    if (!running || gen != generation) return;
                    switch (poll.state) {
                        case READY:
                            doExchange(config, poll.code, gen);
                            return;
                        case SCANNED:
                            setStatus("已扫码，请在微信里点「确认授权」", false);
                            break;
                        case EXPIRED:
                            setStatus("二维码已过期，正在换新…", false);
                            refreshed = true;
                            break;
                        case ERROR:
                            setStatus("扫码状态异常，正在换新…", false);
                            refreshed = true;
                            break;
                        case WAITING:
                        default:
                            long age = (System.currentTimeMillis() - qrStart) / 1000;
                            if (age > 70) {
                                setSub("二维码即将过期，可点「刷新二维码」换一张。");
                            }
                            break;
                    }
                }
            } catch (Throwable t) {
                if (!running || gen != generation) return;
                failures++;
                Log.w(TAG, "wechat login failed", t);
                setStatus("微信登录失败：" + shortMsg(t), true);
                setSub("2 秒后自动重试；也可以点「刷新二维码」。");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
                if (failures >= 5) {
                    setStatus("多次失败，已停止重试", true);
                    return;
                }
            }
        }
    }

    private void doExchange(WechatQrLogin.Config config, String code, int gen) {
        setStatus("已确认，正在换取游戏账号…", false);
        setSub("微信 code → openid/unionid → 游戏 passport 换 fpid/token");
        try {
            WechatQrLogin.Identity identity = client.exchange(config, code);
            WechatQrLogin.Session session = client.loginPassport(identity);
            if (!running || gen != generation) return;
            String label = buildLabel(identity, session);
            boolean bound = entryId != null && !entryId.isEmpty();
            boolean ok = saveAccount(session, label);
            if (ok) {
                String shownLabel = bound ? "本入口" : label;
                setStatus("✅ 已写入" + shownLabel + "：" + label, false);
                setSub("fpid " + maskFpid(session.fpid) + " · "
                        + (bound ? "token 已回填，返回详情页即可看到" : "已写入号库（未分组），点它即可注入登录"));
                ui.post(() -> UiStyle.toast(this, "已写入" + shownLabel));
                ui.postDelayed(() -> {
                    if (!isFinishing()) finish();
                }, 1400);
            } else {
                setStatus("⚠️ 账号已登录但写入号库失败", true);
                setSub("请重试，或手动导入 token。");
            }
        } catch (Throwable t) {
            Log.w(TAG, "exchange/passport failed", t);
            setStatus("换取游戏账号失败：" + shortMsg(t), true);
            setSub("点「刷新二维码」重新扫码（code 可能已过期）。");
        }
    }

    // ---------------- 配置 / 落库 ----------------

    /** 从容器内游戏的远端配置缓存里读微信 appid/app_key。 */
    private WechatQrLogin.Config loadConfig() {
        try {
            File gameData = new VaultEngine(getApplicationContext()).gameDataDir();
            File prefs = new File(new File(gameData, "shared_prefs"), "com.cg.sdk.xml");
            if (!prefs.isFile() || prefs.length() <= 0) return null;
            return WechatQrLogin.parseConfig(readText(prefs));
        } catch (Throwable t) {
            Log.w(TAG, "load wechat config failed", t);
            return null;
        }
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            in.close();
        }
    }

    private String buildLabel(WechatQrLogin.Identity identity, WechatQrLogin.Session session) {
        String nick = identity == null ? "" : identity.nickname;
        if (nick != null && !nick.trim().isEmpty()) {
            String n = nick.trim();
            return "微信·" + (n.length() > 8 ? n.substring(0, 8) : n);
        }
        String fpid = session.fpid;
        return "微信·" + (fpid.length() > 4 ? fpid.substring(fpid.length() - 4) : fpid);
    }

    /**
     * 写入账号库：入口详情页发起时回填到该入口；独立发起时按 fpid 覆盖/新增「未分组」。
     * 绑定规则见 {@link WechatQrLogin#applySession}（纯逻辑，已单测）。
     */
    private boolean saveAccount(WechatQrLogin.Session session, String label) {
        try {
            AccountStore store = new AccountStore(this);
            List<AccountStore.Account> list = store.all();
            AccountStore.Account target = WechatQrLogin.applySession(list, entryId, session, label);
            if (target == null) return false;
            boolean ok = store.saveSync(list);
            if (ok) {
                try {
                    VaultSync.onAccountsChanged(this);
                } catch (Throwable ignored) {
                }
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "save account failed", t);
            return false;
        }
    }

    private static String maskFpid(String fpid) {
        if (fpid == null || fpid.isEmpty()) return "";
        if (fpid.length() <= 5) return fpid;
        return fpid.substring(0, 3) + "***" + fpid.substring(fpid.length() - 2);
    }

    private static String shortMsg(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.isEmpty()) m = t == null ? "未知错误" : t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }
}
