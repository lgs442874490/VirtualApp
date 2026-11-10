package com.carlos.vault.daily;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;

/**
 * 工具箱「真机监控」独立入口（薄壳）：页面内容由 {@link MonitorPanel} 提供。
 *
 * <p>工具箱现在统一走「诊断」页（{@link DiagnosticActivity}，内含「客户端抓包 / 服务端监控」两个 tab）；
 * 本类保留以兼容旧入口/外部 Intent。
 */
public class MonitorActivity extends Activity {
    private MonitorPanel panel;

    @Override
    public void onBackPressed() {
        if (isTaskRoot()) {
            try {
                startActivity(new Intent(this, com.carlos.vault.HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return;
            } catch (Throwable ignored) {
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        panel = new MonitorPanel(this);
        LinearLayout root = UiComponents.pageRoot(this);
        root.addView(topBar());
        root.addView(panel.create(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        title.setText("真机监控");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }
}
