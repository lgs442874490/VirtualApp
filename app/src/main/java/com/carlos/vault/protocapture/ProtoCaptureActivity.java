package com.carlos.vault.protocapture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiStyle;

/**
 * 工具箱「真机抓包」独立入口（薄壳）：页面内容由 {@link ProtoCapturePanel} 提供。
 *
 * <p>工具箱现在统一走「诊断」页（{@link com.carlos.vault.daily.DiagnosticActivity}，
 * 内含「客户端抓包 / 服务端监控」两个 tab）；本类保留以兼容旧入口/外部 Intent。
 */
public class ProtoCaptureActivity extends Activity {
    private ProtoCapturePanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < 21) {
            UiStyle.toast(this, "Android 5.0 以下不支持 VPN 抓包");
            finish();
            return;
        }
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        panel = new ProtoCapturePanel(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);
        root.addView(topBar());
        root.addView(panel.create(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel != null) panel.resume();
    }

    @Override
    protected void onPause() {
        if (panel != null) panel.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (panel != null) panel.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (panel != null) panel.onActivityResult(requestCode, resultCode, data);
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiStyle.dp(this, 18), UiStyle.dp(this, 14), UiStyle.dp(this, 18),
                UiStyle.dp(this, 8));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(UiStyle.INK);
        back.setPadding(UiStyle.dp(this, 4), 0, UiStyle.dp(this, 10), 0);
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
}
