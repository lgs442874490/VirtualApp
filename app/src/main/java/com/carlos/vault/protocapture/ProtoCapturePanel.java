package com.carlos.vault.protocapture;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.carlos.BuildConfig;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;

import java.io.File;
import java.util.List;

/**
 * 「客户端抓包」面板：从 {@link ProtoCaptureActivity} 抽出的可复用 UI/逻辑，
 * 供工具箱「诊断」页（{@link com.carlos.vault.daily.DiagnosticActivity}）内嵌切换，
 * 也可被 {@link ProtoCaptureActivity} 单独作为整页使用。
 *
 * <p>面板不含顶栏/返回按钮——由宿主页面提供；生命周期与 onActivityResult 由宿主转发。
 */
public final class ProtoCapturePanel {
    /** VPN 授权请求码（宿主 onActivityResult 必须转发到 {@link #onActivityResult}）。 */
    public static final int REQ_VPN = 0x7A32;

    private static final String PREFS = "proto_capture";
    private static final String KEY_TARGET = "target_package";

    private final Activity host;
    private TextView statusText;
    private TextView targetText;
    private TextView countText;
    private LinearLayout listBox;
    private Spinner targetSpinner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            renderList();
            handler.postDelayed(this, 800L);
        }
    };

    public ProtoCapturePanel(Activity host) {
        this.host = host;
    }

    private int dp(int v) {
        return UiStyle.dp(host, v);
    }

    private void toast(String s) {
        UiStyle.toast(host, s);
    }

    /** 构建面板内容视图（不含页面顶栏）。 */
    public View create() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(30));

        LinearLayout card = UiStyle.outlineCard(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        statusText = new TextView(host);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(UiStyle.INK);
        card.addView(statusText);
        TextView hint = new TextView(host);
        hint.setText("免 root：分裂 VPN 仅导入 43/101 网段（游戏服务器），其它流量/DNS 走真实网络；"
                + "tun2socks 转发目标游戏 TCP，首次开始需系统授权。目标端口 30101-30105。");
        hint.setTextSize(11.5f);
        hint.setTextColor(UiStyle.INK2);
        hint.setPadding(0, dp(4), 0, dp(8));
        card.addView(hint);

        targetText = new TextView(host);
        targetText.setTextSize(12);
        targetText.setTextColor(UiStyle.INK2);
        card.addView(targetText);

        targetSpinner = new Spinner(host);
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(host,
                android.R.layout.simple_spinner_item, ProtoCaptureTargets.labels());
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(targetAdapter);
        boolean nativeGame = nativeGameInstalled();
        // 原生没装 → 默认按容器内游戏抓（号库的主要用途）；原生装了 → 按运行进程判断
        int fallback = ProtoCaptureTargets.defaultIndex(containerGameRunning() || !nativeGame, nativeGame);
        int savedIndex = ProtoCaptureTargets.indexOfValue(
                prefs().getString(KEY_TARGET, ""), host.getPackageName(), fallback);
        targetSpinner.setSelection(savedIndex);
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etlp.topMargin = dp(8);
        card.addView(targetSpinner, etlp);

        LinearLayout btnRow = new LinearLayout(host);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);
        TextView start = UiStyle.primaryBtn(host, "开始抓包", this::startCapture);
        TextView stop = UiStyle.secondaryBtn(host, "停止", () -> {
            ProtoCaptureService.stop(host);
            handler.postDelayed(this::renderStatus, 500L);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.rightMargin = dp(8);
        btnRow.addView(start, blp);
        btnRow.addView(stop, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(btnRow);

        LinearLayout expRow = new LinearLayout(host);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setPadding(0, dp(8), 0, 0);
        TextView export = UiStyle.ghostBtn(host, "导出并分享", this::exportShare);
        TextView clear = UiStyle.ghostBtn(host, "清空列表", () -> {
            ProtoCaptureLog.clear();
            renderList();
        });
        expRow.addView(export, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        expRow.addView(clear, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(expRow);
        box.addView(card);

        LinearLayout head = UiComponents.sectionHeader(host, "实时协议", "最多 500 条");
        box.addView(head);
        countText = new TextView(host);
        countText.setTextSize(11.5f);
        countText.setTextColor(UiStyle.INK3);
        countText.setPadding(dp(2), 0, 0, dp(4));
        box.addView(countText);
        listBox = new LinearLayout(host);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);

        ScrollView sc = new ScrollView(host);
        sc.addView(box);
        return sc;
    }

    /** 宿主 onResume 时调用：刷新状态并启动 800ms 轮询。 */
    public void resume() {
        renderStatus();
        renderList();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    /** 宿主 onPause 时调用：停止轮询。 */
    public void pause() {
        handler.removeCallbacks(refresh);
    }

    /** 宿主 onDestroy 时调用：彻底移除回调。 */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
    }

    /** 宿主 onActivityResult 转发（VPN 授权返回）。 */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN) {
            if (resultCode == Activity.RESULT_OK) {
                launchService(selectedTarget());
            } else {
                toast("未授权 VPN，无法抓包");
            }
        }
    }

    /** 当前下拉选中的实际 target 包名（空串=全部应用）。 */
    private String selectedTarget() {
        if (targetSpinner == null) return "";
        int pos = targetSpinner.getSelectedItemPosition();
        String[] values = ProtoCaptureTargets.values(host.getPackageName());
        if (pos < 0 || pos >= values.length) return "";
        return values[pos];
    }

    /** 容器内是否正在跑无尽冬日（进程 UID 与号库自身相同）。 */
    private boolean containerGameRunning() {
        try {
            ActivityManager am = (ActivityManager) host.getSystemService(Activity.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p == null || p.processName == null) continue;
                if (ProtoCaptureTargets.isGamePackage(p.processName)
                        || p.processName.startsWith("com.gof.")) {
                    if (p.uid == android.os.Process.myUid()) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 是否原生安装了无尽冬日（非容器场景）。 */
    private boolean nativeGameInstalled() {
        for (String pkg : ProtoCaptureTargets.GAME_PACKAGES) {
            try {
                host.getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private SharedPreferences prefs() {
        return host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void startCapture() {
        if (ProtoCaptureService.isRunning()) {
            toast("抓包已在运行");
            return;
        }
        String target = selectedTarget();
        prefs().edit().putString(KEY_TARGET, target).apply();
        Intent prep = VpnService.prepare(host);
        if (prep != null) {
            host.startActivityForResult(prep, REQ_VPN);
            return;
        }
        launchService(target);
    }

    private void launchService(String target) {
        Intent i = new Intent(host, ProtoCaptureService.class)
                .setAction(ProtoCaptureService.ACTION_START)
                .putExtra(ProtoCaptureService.EXTRA_TARGET_PACKAGE, target);
        try {
            if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(i);
            else host.startService(i);
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
            return;
        }
        handler.postDelayed(this::renderStatus, 800L);
    }

    private void exportShare() {
        try {
            File f = ProtoCaptureLog.export(host);
            Uri uri = FileProvider.getUriForFile(host,
                    BuildConfig.APPLICATION_ID + ".provider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "真机抓包 " + f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            host.startActivity(Intent.createChooser(send, "分享抓包文件"));
            toast("已导出：" + f.getAbsolutePath());
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    private void renderStatus() {
        boolean running = ProtoCaptureService.isRunning();
        if (statusText != null) {
            statusText.setText(running ? "● 抓包中" : "○ 已停止");
            statusText.setTextColor(running ? UiStyle.BRAND_TEXT : UiStyle.INK2);
        }
        if (targetText != null) {
            String target = ProtoCaptureService.targetPackage();
            targetText.setText("目标应用：" + (target == null || target.isEmpty()
                    ? "全部应用（自动/未指定）" : target));
        }
    }

    private void renderList() {
        renderStatus();
        if (listBox == null) return;
        List<ProtoCaptureRecord> rows = ProtoCaptureLog.snapshot();
        if (countText != null) {
            countText.setText("共 " + rows.size() + " 条（内存上限 500，完整内容导出文件）");
        }
        listBox.removeAllViews();
        int shown = 0;
        for (ProtoCaptureRecord r : rows) {
            if (shown++ >= 120) break;
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(UiStyle.roundRectDp(host, UiStyle.WHITE, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            LinearLayout top = new LinearLayout(host);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = new TextView(host);
            t.setText(r.time() + "  " + r.direction);
            t.setTextSize(11);
            t.setTextColor("C2S".equals(r.direction) ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            top.addView(t);
            TextView p = new TextView(host);
            p.setText("  " + r.proto);
            p.setTextSize(11.5f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextColor(UiStyle.INK);
            top.addView(p);
            row.addView(top);

            TextView s = new TextView(host);
            s.setText(r.summary);
            s.setTextSize(10.5f);
            s.setTextColor(UiStyle.INK2);
            s.setPadding(0, dp(3), 0, 0);
            row.addView(s);
            listBox.addView(row);
        }
        if (rows.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText("暂无记录：点「开始抓包」后登录游戏/操作功能，协议会实时出现。");
            empty.setTextSize(12);
            empty.setTextColor(UiStyle.INK3);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            listBox.addView(empty);
        }
    }
}
