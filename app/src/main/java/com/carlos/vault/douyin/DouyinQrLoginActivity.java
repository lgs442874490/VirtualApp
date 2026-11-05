package com.carlos.vault.douyin;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.AccountStore;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiLayoutRules;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 抖音扫码登录：显示二维码 → 手机抖音扫码确认 → 自动换游戏账号（fpid + token）并入号库。
 *
 * <p>全链路（照抄自营网页工具箱 wjdr-tool 2026-11-04 版的抖音登录）：
 * 抖音网页 get_qrcode → 扫码确认（check_qrconnect）→ 网页 sessionid →
 * 抖音小游戏授权码 → 游戏 passport {@code login_with_sns}（tt_mini）→ fpid + session_key
 * → 写入 {@link AccountStore}。之后在账号页点该账号即可走现有 token 注入自动登录。
 *
 * <p>不需要在容器里安装抖音，也不走原生登录。
 */
public class DouyinQrLoginActivity extends Activity {

    private static final String TAG = "DouyinQrLogin";

    /** 入口详情页发起时传入账号入口 id：扫码结果直接回填到该入口。 */
    public static final String EXTRA_ENTRY_ID = "entry_id";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView qrView;
    private TextView statusView;
    private TextView subView;

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
        // 统一页面骨架：19sp 顶栏 + 44dp 返回热区 + 18dp 正文边距（宽屏 640dp 限宽居中）
        UiComponents.Page page = UiComponents.scrollPage(this, "抖音扫码登录", null,
                this::finish, 24);
        page.root.setFitsSystemWindows(true);
        LinearLayout body = page.body;

        TextView desc = UiComponents.body(this, entryId == null || entryId.isEmpty()
                ? "用抖音扫码授权，直接拿到游戏账号（fpid + token）；不需要在容器里装抖音，也不需要走原生登录。"
                : "扫码后把号 ID 和 token 直接回填到本入口；不需要在容器里装抖音，也不需要走原生登录。");
        desc.setPadding(dp(2), dp(2), dp(2), dp(10));
        body.addView(desc);

        // 二维码卡片：统一描边卡（16dp 圆角 + 1px 描边），尺寸随窗口自适应，窄屏不溢出
        LinearLayout card = UiComponents.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(14));

        int qrSize = Math.min(dp(252), qrAvailableWidth() - dp(36));
        FrameLayout qrBox = new FrameLayout(this);
        qrBox.setBackground(UiStyle.roundRectDp(this, 0xFFFFFFFF, 14));
        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrBox.addView(qrView, new FrameLayout.LayoutParams(qrSize - dp(24), qrSize - dp(24),
                Gravity.CENTER));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrBox, qrLp);

        statusView = UiComponents.text(this, "正在获取抖音登录二维码…",
                UiComponents.TEXT_BODY, UiStyle.INK, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(14), 0, dp(2));
        card.addView(statusView);

        subView = UiComponents.caption(this, "二维码过期会自动换新。", UiStyle.INK2);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(0, 0, 0, dp(4));
        card.addView(subView);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(2);
        body.addView(card, cardLp);

        // 操作按钮（44dp 热区）
        LinearLayout actionsRow = buildActions();
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(14);
        body.addView(actionsRow, actionLp);

        TextView tip = UiComponents.caption(this, "· 扫码后需在抖音 App 里点「确认」；若提示身份验证，按提示完成即可\n"
                + "· 登录会占用该抖音号当前的网页会话（可能顶掉它正在使用的设备）；号库小号请自行判断\n"
                + "· 成功后自动加入账号页「未分组」，点它即可注入登录", UiStyle.INK3);
        tip.setPadding(dp(2), dp(14), dp(2), 0);
        body.addView(tip);

        setContentView(page.root);
    }

    /** 二维码可用宽度 = 正文限宽后的卡片内宽（窄屏按实际窗口收缩，宽屏不超过 640dp）。 */
    private int qrAvailableWidth() {
        return UiLayoutRules.contentWidthPx(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().density);
    }

    private LinearLayout buildActions() {
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
            statusView.setTextColor(error ? UiStyle.ERR_INK : UiStyle.INK);
        });
    }

    private void setSub(final String text) {
        ui.post(() -> {
            if (isFinishing()) return;
            subView.setText(text);
        });
    }

    private void showQr(final String imageBase64) {
        if (imageBase64 == null || imageBase64.isEmpty()) return;
        ui.post(() -> {
            if (isFinishing()) return;
            try {
                byte[] image = Base64.decode(imageBase64, Base64.DEFAULT);
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
        setStatus("正在获取抖音登录二维码…", false);
        setSub("二维码过期会自动换新。");
        worker = new Thread(() -> flow(gen), "douyin-qr-login");
        worker.start();
    }

    private void flow(int gen) {
        // 防御性兜底：loadTemplate() 总会回退内置模板，正常情况下不会走到这里
        DouyinQrLogin.Template template = loadTemplate();
        if (template == null || !template.isValid()) {
            setStatus("抖音扫码模板缺失", true);
            setSub("请更新号库到包含抖音登录的版本。");
            return;
        }
        DouyinQrClient client = new DouyinQrClient(template);
        int failures = 0;
        while (running && gen == generation) {
            try {
                DouyinQrLogin.Qr qr = client.fetchQr();
                if (!running || gen != generation) return;
                showQr(qr.imageBase64);
                setStatus("请用抖音 App 扫码并确认", false);
                long ttlSeconds = Math.max(0L,
                        (qr.expireAtMillis - System.currentTimeMillis()) / 1000L);
                setSub(ttlSeconds > 0
                        ? "二维码约 " + ttlSeconds + " 秒有效；过期会自动换新。"
                        : "二维码过期会自动换新。");
                failures = 0;

                long qrStart = System.currentTimeMillis();
                boolean refreshed = false;
                int pollErrors = 0;
                while (running && gen == generation && !refreshed) {
                    DouyinQrLogin.Poll poll = client.poll();
                    if (!running || gen != generation) return;
                    long now = System.currentTimeMillis();
                    switch (poll.state) {
                        case CONFIRMED:
                            doExchange(client, poll.sessionId, gen);
                            return;
                        case SCANNED:
                            pollErrors = 0;
                            setStatus(poll.message.isEmpty()
                                    ? "已扫码，请在抖音 App 里点「确认」" : poll.message, false);
                            break;
                        case EXPIRED:
                            pollErrors = 0;
                            setStatus("二维码已过期，正在换新…", false);
                            refreshed = true;
                            break;
                        case ERROR:
                            pollErrors++;
                            setStatus(poll.message, true);
                            if (poll.message.contains("验证")) {
                                setSub("请在抖音 App 内完成验证后，点「刷新二维码」重新扫码。");
                                return;
                            }
                            if (pollErrors >= 3) {
                                setSub("多次失败，已停止自动重试；请点「刷新二维码」重新扫码。");
                                return;
                            }
                            setSub("2 秒后自动换新；也可以点「刷新二维码」。");
                            sleep(2000);
                            refreshed = true;
                            break;
                        case WAITING:
                        default:
                            pollErrors = 0;
                            if (!poll.imageBase64.isEmpty()) {
                                showQr(poll.imageBase64);
                                setStatus("二维码已刷新，请重新扫码", false);
                            }
                            long age = (now - qrStart) / 1000;
                            if (age > 80) {
                                setSub("二维码即将过期，可点「刷新二维码」换一张。");
                            }
                            if (qr.expired(now)) refreshed = true;
                            break;
                    }
                    if (refreshed) break;
                    sleep(poll.retryAfterMillis > 0 ? poll.retryAfterMillis : 900L);
                }
            } catch (Throwable t) {
                if (!running || gen != generation) return;
                failures++;
                Log.w(TAG, "douyin qr login failed", t);
                setStatus("抖音登录失败：" + shortMsg(t), true);
                setSub("2 秒后自动重试；也可以点「刷新二维码」。");
                sleep(2000);
                if (failures >= 5) {
                    setStatus("多次失败，已停止重试", true);
                    return;
                }
            }
        }
    }

    private void doExchange(DouyinQrClient client, String sessionId, int gen) {
        // 旧流程（已刷新/已销毁）不再动 UI
        if (!running || gen != generation) return;
        setStatus("已确认，正在换取游戏账号…", false);
        setSub("抖音小游戏授权码 → 游戏 passport 换 fpid/token");
        try {
            String code = client.exchangeGameCode(sessionId);
            DouyinQrLogin.Session session = client.loginPassport(code);
            if (!running || gen != generation) return;
            String label = DouyinQrLogin.labelFor(session);
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
            if (!running || gen != generation) return;
            Log.w(TAG, "exchange/passport failed", t);
            setStatus("换取游戏账号失败：" + shortMsg(t), true);
            setSub("点「刷新二维码」重新扫码（授权码可能已过期）。");
        }
    }

    // ---------------- 配置 / 落库 ----------------

    /** 优先读应用目录下可覆盖的模板（douyin-login.json），否则用内置模板。 */
    private DouyinQrLogin.Template loadTemplate() {
        try {
            File override = new File(getFilesDir(), "douyin-login.json");
            if (override.isFile() && override.length() > 0) {
                DouyinQrLogin.Template t = DouyinQrLogin.Template.parse(readText(override));
                if (t != null) return t;
            }
        } catch (Throwable t) {
            Log.w(TAG, "load douyin template failed", t);
        }
        return DouyinQrLogin.Template.builtIn();
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

    /**
     * 写入账号库：入口详情页发起时回填到该入口；独立发起时按 fpid 覆盖/新增「未分组」。
     * 绑定规则见 {@link DouyinQrLogin#applySession}（纯逻辑，已单测）。
     */
    private boolean saveAccount(DouyinQrLogin.Session session, String label) {
        try {
            AccountStore store = new AccountStore(this);
            List<AccountStore.Account> list = store.all();
            AccountStore.Account target = DouyinQrLogin.applySession(list, entryId, session, label);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
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
