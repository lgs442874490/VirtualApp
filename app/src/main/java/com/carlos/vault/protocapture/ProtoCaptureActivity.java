package com.carlos.vault.protocapture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.carlos.BuildConfig;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;

import java.io.File;
import java.util.List;

/**
 * 工具箱「真机抓包」（T3）：免 root 抓取游戏 TCP 流量并用 PortableSprotoCodec 实时解码。
 * 仅 Android 5.0+ 可用；首次开始需系统 VpnService 授权。
 */
public class ProtoCaptureActivity extends Activity {
    private static final int REQ_VPN = 0x7A32;
    private static final String PREFS = "proto_capture";
    private static final String KEY_TARGET = "target_package";

    private TextView statusText;
    private TextView targetText;
    private TextView countText;
    private LinearLayout listBox;
    private EditText targetEt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            renderList();
            handler.postDelayed(this, 800L);
        }
    };

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private void toast(String s) {
        UiStyle.toast(this, s);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < 21) {
            toast("Android 5.0 以下不支持 VPN 抓包");
            finish();
            return;
        }
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        renderList();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(14), dp(18), dp(8));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(UiStyle.INK);
        back.setPadding(dp(4), 0, dp(10), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("真机抓包");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

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

        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(UiStyle.INK);
        card.addView(statusText);
        TextView hint = new TextView(this);
        hint.setText("免 root：分裂 VPN 仅导入 43/101 网段（游戏服务器），其它流量/DNS 走真实网络；"
                + "tun2socks 转发目标游戏 TCP，首次开始需系统授权。目标端口 30101-30105。");
        hint.setTextSize(11.5f);
        hint.setTextColor(UiStyle.INK2);
        hint.setPadding(0, dp(4), 0, dp(8));
        card.addView(hint);

        targetText = new TextView(this);
        targetText.setTextSize(12);
        targetText.setTextColor(UiStyle.INK2);
        card.addView(targetText);

        targetEt = new EditText(this);
        targetEt.setHint("目标包名（留空=自动检测 com.gof.china/global）");
        targetEt.setTextSize(12);
        targetEt.setSingleLine(true);
        targetEt.setText(prefs().getString(KEY_TARGET, defaultTargetPackage()));
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etlp.topMargin = dp(8);
        card.addView(targetEt, etlp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);
        TextView start = UiStyle.primaryBtn(this, "开始抓包", this::startCapture);
        TextView stop = UiStyle.secondaryBtn(this, "停止", () -> {
            ProtoCaptureService.stop(this);
            handler.postDelayed(this::renderStatus, 500L);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.rightMargin = dp(8);
        btnRow.addView(start, blp);
        btnRow.addView(stop, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(btnRow);

        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setPadding(0, dp(8), 0, 0);
        TextView export = UiStyle.ghostBtn(this, "导出并分享", this::exportShare);
        TextView clear = UiStyle.ghostBtn(this, "清空列表", () -> {
            ProtoCaptureLog.clear();
            renderList();
        });
        expRow.addView(export, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        expRow.addView(clear, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(expRow);
        box.addView(card);

        LinearLayout head = UiComponents.sectionHeader(this, "实时协议", "最多 500 条");
        box.addView(head);
        countText = new TextView(this);
        countText.setTextSize(11.5f);
        countText.setTextColor(UiStyle.INK3);
        countText.setPadding(dp(2), 0, 0, dp(4));
        box.addView(countText);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);
        return root;
    }

    /** 默认目标包：优先游戏（无尽冬日国服/国际服），未安装则留空=全部应用。 */
    private String defaultTargetPackage() {
        for (String pkg : new String[]{"com.gof.china", "com.gof.global"}) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void startCapture() {
        if (ProtoCaptureService.isRunning()) {
            toast("抓包已在运行");
            return;
        }
        String target = targetEt.getText().toString().trim();
        prefs().edit().putString(KEY_TARGET, target).apply();
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        launchService(target);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                launchService(targetEt.getText().toString().trim());
            } else {
                toast("未授权 VPN，无法抓包");
            }
        }
    }

    private void launchService(String target) {
        Intent i = new Intent(this, ProtoCaptureService.class)
                .setAction(ProtoCaptureService.ACTION_START)
                .putExtra(ProtoCaptureService.EXTRA_TARGET_PACKAGE, target);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
            return;
        }
        handler.postDelayed(this::renderStatus, 800L);
    }

    private void exportShare() {
        try {
            File f = ProtoCaptureLog.export(this);
            Uri uri = FileProvider.getUriForFile(this,
                    BuildConfig.APPLICATION_ID + ".provider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "真机抓包 " + f.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享抓包文件"));
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
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(UiStyle.roundRectDp(this, UiStyle.WHITE, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = new TextView(this);
            t.setText(r.time() + "  " + r.direction);
            t.setTextSize(11);
            t.setTextColor("C2S".equals(r.direction) ? UiStyle.BRAND_TEXT : UiStyle.INK2);
            top.addView(t);
            TextView p = new TextView(this);
            p.setText("  " + r.proto);
            p.setTextSize(11.5f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextColor(UiStyle.INK);
            top.addView(p);
            row.addView(top);

            TextView s = new TextView(this);
            s.setText(r.summary);
            s.setTextSize(10.5f);
            s.setTextColor(UiStyle.INK2);
            s.setPadding(0, dp(3), 0, 0);
            row.addView(s);
            listBox.addView(row);
        }
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无记录：点「开始抓包」后登录游戏/操作功能，协议会实时出现。");
            empty.setTextSize(12);
            empty.setTextColor(UiStyle.INK3);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            listBox.addView(empty);
        }
    }
}
