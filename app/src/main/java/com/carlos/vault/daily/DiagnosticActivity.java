package com.carlos.vault.daily;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.carlos.vault.FeatureGate;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;
import com.carlos.vault.protocapture.ProtoCapturePanel;

/**
 * 工具箱「诊断」页（2026-11-10）：把「真机抓包」与「真机监控」合并为一个入口，
 * 顶部两个 tab 切换，避免同区同图标两个入口让用户分不清。
 *
 * <ul>
 *   <li>客户端抓包：{@link ProtoCapturePanel}（本机 VPN 抓 TCP + sproto 实时解码）；</li>
 *   <li>服务端监控：{@link MonitorPanel}（拉服务端 /v1/vault/monitor 任务事件流）。</li>
 * </ul>
 *
 * <p>两个 tab 的可见性分别受 {@code FeatureGate.PROTO_CAPTURE} / {@code DEV_MONITOR} 控制；
 * 只隐藏对应 tab，不影响另一个。只转发**当前可见**面板的生命周期，避免后台无谓轮询。
 */
public class DiagnosticActivity extends Activity {
    public static final String EXTRA_TAB = "tab";
    public static final String TAB_CAPTURE = "capture";
    public static final String TAB_MONITOR = "monitor";

    private boolean canCapture;
    private boolean canMonitor;

    private ProtoCapturePanel capturePanel;
    private MonitorPanel monitorPanel;
    private View captureView;
    private View monitorView;

    private TextView captureTab;
    private TextView monitorTab;
    private FrameLayout content;
    private String currentTab = TAB_CAPTURE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }

        // 2026-11-10：诊断页只对 GM 账户开放（本机永久 GM 或服务端 GM 票据）。
        if (!com.carlos.vault.GmMode.isGm(this)
                && FeatureGate.currentTier(this) != FeatureGate.TIER_GM) {
            UiStyle.toast(this, "诊断工具仅对 GM 账户开放");
            finish();
            return;
        }
        canCapture = FeatureGate.visible(this, FeatureGate.Feature.PROTO_CAPTURE)
                && Build.VERSION.SDK_INT >= 21;
        canMonitor = FeatureGate.visible(this, FeatureGate.Feature.DEV_MONITOR);
        if (!canCapture && !canMonitor) {
            UiStyle.toast(this, "诊断工具不可用（需 T3）");
            finish();
            return;
        }

        String want = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TAB);
        if (TAB_CAPTURE.equals(want) && canCapture) currentTab = TAB_CAPTURE;
        else if (TAB_MONITOR.equals(want) && canMonitor) currentTab = TAB_MONITOR;
        else currentTab = canCapture ? TAB_CAPTURE : TAB_MONITOR;

        LinearLayout root = UiComponents.pageRoot(this);
        root.addView(UiComponents.topBar(this, "诊断", null, this::finish));
        root.addView(buildTabs());
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        renderTab();
    }

    /** tab 行：两个胶囊，按可见性显示。 */
    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(UiStyle.dp(this, 18), UiStyle.dp(this, 2), UiStyle.dp(this, 18),
                UiStyle.dp(this, 6));
        captureTab = tab("客户端抓包", canCapture, () -> select(TAB_CAPTURE));
        monitorTab = tab("服务端监控", canMonitor, () -> select(TAB_MONITOR));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = UiStyle.dp(this, 8);
        row.addView(captureTab, lp);
        row.addView(monitorTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView tab(String text, boolean visible, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, UiStyle.dp(this, 10), 0, UiStyle.dp(this, 10));
        tv.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        tv.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) tv.setOnClickListener(null);
        return tv;
    }

    private void select(String tab) {
        if (tab == null || tab.equals(currentTab)) return;
        if (TAB_CAPTURE.equals(tab) && !canCapture) return;
        if (TAB_MONITOR.equals(tab) && !canMonitor) return;
        // 离开当前面板：暂停其刷新
        if (TAB_CAPTURE.equals(currentTab) && capturePanel != null) capturePanel.pause();
        if (TAB_MONITOR.equals(currentTab) && monitorPanel != null) monitorPanel.pause();
        currentTab = tab;
        renderTab();
        resumeCurrent();   // 切过去的面板立即刷新（否则服务端监控切过去不轮询）
    }

    /** 恢复当前 tab 面板的刷新。 */
    private void resumeCurrent() {
        if (TAB_CAPTURE.equals(currentTab)) {
            if (capturePanel != null) capturePanel.resume();
        } else if (monitorPanel != null) {
            monitorPanel.resume();
        }
    }

    /** 按当前 tab 显示对应面板并启动其刷新。 */
    private void renderTab() {
        if (content == null) return;
        if (captureView == null && canCapture) {
            capturePanel = new ProtoCapturePanel(this);
            captureView = capturePanel.create();
            content.addView(captureView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (monitorView == null && canMonitor) {
            monitorPanel = new MonitorPanel(this);
            monitorView = monitorPanel.create();
            content.addView(monitorView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        boolean captureSel = TAB_CAPTURE.equals(currentTab);
        if (captureView != null) captureView.setVisibility(captureSel ? View.VISIBLE : View.GONE);
        if (monitorView != null) monitorView.setVisibility(captureSel ? View.GONE : View.VISIBLE);
        styleTab(captureTab, captureSel);
        styleTab(monitorTab, !captureSel);
    }

    private void styleTab(TextView tv, boolean selected) {
        if (tv == null) return;
        tv.setTextColor(selected ? UiStyle.WHITE : UiStyle.INK2);
        tv.setBackground(UiStyle.roundRectDp(this,
                selected ? UiStyle.BRAND : UiStyle.SURFACE_2, UiStyle.RADIUS_PILL));
        UiStyle.press(tv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumeCurrent();
    }

    @Override
    protected void onPause() {
        if (TAB_CAPTURE.equals(currentTab) && capturePanel != null) capturePanel.pause();
        if (TAB_MONITOR.equals(currentTab) && monitorPanel != null) monitorPanel.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (capturePanel != null) capturePanel.destroy();
        if (monitorPanel != null) monitorPanel.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (capturePanel != null) capturePanel.onActivityResult(requestCode, resultCode, data);
    }
}
