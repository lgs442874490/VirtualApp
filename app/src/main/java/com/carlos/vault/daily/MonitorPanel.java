package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「服务端监控」面板：从 {@link MonitorActivity} 抽出的可复用 UI/逻辑，
 * 供工具箱「诊断」页（{@link DiagnosticActivity}）内嵌切换。
 *
 * <p>面板不含顶栏/返回按钮——由宿主页面提供；生命周期由宿主转发。
 */
public final class MonitorPanel {

    private static final int MEM_CAP = 800;
    private static final int PAGE_LIMIT = 200;
    private static final long POLL_MS = 2000L;

    private final Activity host;
    private final List<JSONObject> events = new ArrayList<>();
    private long cursor;
    private long serverTime;
    private int received;
    private int category = MonitorFormat.CAT_ALL;
    private String lastError = "";
    private String enabledText = "未知";
    private boolean polling;
    private boolean autoRefresh = true;
    private boolean resumed;
    private boolean tailLocated;

    private TextView statusView;
    private ScrollView eventScroll;
    private LinearLayout eventBox;
    private LinearLayout chipsRow;
    private UiStyle.Switch autoSw;
    private View emptyHint;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed || !autoRefresh) return;
            poll(false);
            if (resumed && autoRefresh) ui.postDelayed(this, POLL_MS);
        }
    };

    public MonitorPanel(Activity host) {
        this.host = host;
    }

    private int dp(int v) {
        return UiStyle.dp(host, v);
    }

    /** 构建面板内容视图（不含页面顶栏）。 */
    public View create() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- 状态卡：状态行 + 自动刷新/刷新 ----
        LinearLayout statusCard = UiComponents.card(host);
        statusView = new TextView(host);
        statusView.setTextSize(12);
        statusView.setTextColor(UiStyle.INK2);
        statusView.setLineSpacing(dp(3), 1f);
        statusCard.addView(statusView);

        LinearLayout ctrl = new LinearLayout(host);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        ctrl.setPadding(0, dp(10), 0, 0);
        TextView autoLabel = new TextView(host);
        autoLabel.setText("自动刷新（2s）");
        autoLabel.setTextSize(12);
        autoLabel.setTextColor(UiStyle.INK2);
        ctrl.addView(autoLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        autoSw = new UiStyle.Switch(host, autoRefresh, () -> {
            autoRefresh = autoSw.isOn();
            if (autoRefresh) {
                lastError = "";
                updateStatus();
                startAuto();
                poll(true);
            } else {
                ui.removeCallbacks(pollTick);
            }
        });
        ctrl.addView(autoSw, new LinearLayout.LayoutParams(dp(46), dp(44)));
        View spacer = new View(host);
        ctrl.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView refresh = UiStyle.secondaryBtn(host, "刷新", () -> {
            lastError = "";
            updateStatus();
            poll(true);
        });
        ctrl.addView(refresh, new LinearLayout.LayoutParams(dp(88), dp(44)));
        statusCard.addView(ctrl);
        root.addView(UiComponents.contentHost(host, statusCard));

        // ---- 过滤 chips（横向可滚动，窄屏不挤压）----
        HorizontalScrollView chipScroll = new HorizontalScrollView(host);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(host);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, dp(8), 0, 0);
        chipScroll.addView(chipsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(UiComponents.contentHost(host, chipScroll), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildChips();

        // ---- 事件列表 ----
        eventScroll = new ScrollView(host);
        eventScroll.setVerticalScrollBarEnabled(true);
        eventBox = new LinearLayout(host);
        eventBox.setOrientation(LinearLayout.VERTICAL);
        eventBox.setPadding(0, dp(8), 0, dp(16));
        eventScroll.addView(UiComponents.contentHost(host, eventBox), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(eventScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部：打点 / 导出 ----
        root.addView(UiStyle.divider(host));
        LinearLayout bottom = new LinearLayout(host);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(UiStyle.CARD);
        bottom.setPadding(0, dp(10), 0, dp(10));
        TextView markBtn = UiStyle.secondaryBtn(host, "打点", this::onMark);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        mlp.rightMargin = dp(10);
        bottom.addView(markBtn, mlp);
        TextView exportBtn = UiStyle.secondaryBtn(host, "导出", this::onExport);
        bottom.addView(exportBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        FrameLayout bottomHost = UiComponents.contentHost(host, bottom);
        bottomHost.setBackgroundColor(UiStyle.CARD);
        root.addView(bottomHost);

        updateStatus();
        renderEvents();
        return root;
    }

    /** 宿主 onResume 时调用：拉一次并启动 2s 轮询。 */
    public void resume() {
        resumed = true;
        poll(true);
        if (autoRefresh) startAuto();
    }

    /** 宿主 onPause 时调用：停止轮询。 */
    public void pause() {
        resumed = false;
        ui.removeCallbacks(pollTick);
    }

    /** 宿主 onDestroy 时调用：彻底移除回调。 */
    public void destroy() {
        ui.removeCallbacksAndMessages(null);
    }

    /** 重建过滤 chips（选中态切换后整行重画，chip 数量固定 6）。 */
    private void buildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        for (int i = 0; i < MonitorFormat.CATEGORY_LABELS.length; i++) {
            final int cat = i;
            TextView chip = UiStyle.chip(host, MonitorFormat.CATEGORY_LABELS[i], cat == category);
            chip.setOnClickListener(v -> {
                if (category == cat) return;
                category = cat;
                buildChips();
                renderEvents();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chipsRow.addView(chip, lp);
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("服务端监控 ").append(enabledText)
                .append(" · 已收 ").append(received).append(" 条")
                .append(" · 游标 ").append(cursor);
        if (serverTime > 0L) {
            sb.append(" · 服务端 ").append(MonitorFormat.formatTime(serverTime));
        }
        if (lastError != null && !lastError.isEmpty()) {
            sb.append('\n').append("最近错误：").append(lastError);
        }
        statusView.setText(sb.toString());
    }

    private static String describeError(int code, String message) {
        String m = message == null ? "" : message.trim();
        if ("server_mode_off".equals(m)) {
            return "未启用服务器模式（App 未连接服务端）";
        }
        if (code == -1) {
            if (m.contains("404") || m.contains("405")) {
                return "服务端未升级：监控接口不可用（" + m + "）";
            }
            if (m.startsWith("HTTP ")) {
                return "服务端异常：" + m;
            }
            return "网络失败：" + (m.isEmpty() ? "无法连接服务端" : m);
        }
        return "服务端返回 code=" + code + (m.isEmpty() ? "" : " " + m);
    }

    /** 拉一次增量（since=cursor）；manual=true 时清错误并立即反馈。回调切回主线程。 */
    private void poll(final boolean manual) {
        if (polling) return;
        polling = true;
        if (manual) {
            lastError = "";
            updateStatus();
        }
        if (!tailLocated && cursor <= 0L) {
            tailLocated = true;
            Map<String, Object> f = new java.util.LinkedHashMap<>();
            f.put("action", "status");
            try {
                VaultSync.fetchMonitor(host, f, (code, message, data) -> host.runOnUiThread(() -> {
                    polling = false;
                    if (host.isFinishing()) return;
                    if (code == 0 && data != null) {
                        long last = data.optLong("lastSeq", 0L);
                        if (last > 0L) cursor = Math.max(0L, last - PAGE_LIMIT);
                    }
                    poll(manual);
                }));
                return;
            } catch (Throwable ignored) {
                // status 失败则回落普通增量拉取（cursor=0）
            }
        }
        try {
            VaultSync.fetchMonitor(host, cursor, PAGE_LIMIT, "", "", true,
                    (code, message, data) -> host.runOnUiThread(() -> {
                        polling = false;
                        if (host.isFinishing()) return;
                        onMonitorResult(code, message, data);
                    }));
        } catch (Throwable t) {
            polling = false;
            onMonitorResult(-1, "启动监控请求失败：" + t.getClass().getSimpleName(), null);
        }
    }

    private void startAuto() {
        ui.removeCallbacks(pollTick);
        if (resumed && autoRefresh) ui.postDelayed(pollTick, POLL_MS);
    }

    private void stopAuto() {
        autoRefresh = false;
        if (autoSw != null) autoSw.setOn(false);
        ui.removeCallbacks(pollTick);
    }

    private void onMonitorResult(int code, String message, JSONObject data) {
        if (code != 0) {
            enabledText = "重试中";
            lastError = describeError(code, message) + "（自动重试中…）";
            updateStatus();
            return;
        }
        if (data == null) {
            enabledText = "不可用";
            lastError = "服务端未升级：监控响应缺少 data";
            stopAuto();
            updateStatus();
            return;
        }
        long lastSeq = data.optLong("lastSeq", 0L);
        if (cursor > lastSeq) {
            long minSeq = data.optLong("minSeq", 0L);
            cursor = minSeq > 0L ? minSeq - 1L : 0L;
        }
        boolean enabled = data.optBoolean("enabled", false);
        enabledText = enabled ? "已启用" : "未启用";
        long st = data.optLong("serverTime", 0L);
        if (st > 0L) serverTime = st;

        JSONArray arr = data.optJSONArray("events");
        List<JSONObject> incoming = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ev = arr.optJSONObject(i);
                if (ev != null) incoming.add(ev);
            }
        }
        MonitorFormat.Delta delta = MonitorFormat.advance(cursor, incoming);
        cursor = delta.cursor;
        if (!delta.isEmpty()) {
            received += delta.events.size();
            events.addAll(delta.events);
            if (events.size() > MEM_CAP) {
                MonitorFormat.trimTo(events, MEM_CAP);
                renderEvents();
            } else {
                appendRows(delta.events);
            }
            lastError = "";
        }
        if (!enabled) {
            lastError = "服务端监控未启用（需在服务端开 VAULT_MONITOR=on 并重启）";
        } else if (delta.isEmpty()) {
            lastError = "";
        }
        updateStatus();
    }

    private void renderEvents() {
        if (eventBox == null) return;
        eventBox.removeAllViews();
        emptyHint = null;
        List<JSONObject> shown = MonitorFormat.filter(events, category);
        if (shown.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(events.isEmpty()
                    ? "暂无事件 · 服务端开启监控（VAULT_MONITOR=on）后，在原生游戏操作或跑任务即可看到"
                    : "当前筛选下暂无事件");
            empty.setTextSize(12);
            empty.setTextColor(UiStyle.INK3);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(dp(24), dp(40), dp(24), dp(24));
            eventBox.addView(empty);
            emptyHint = empty;
            return;
        }
        for (JSONObject ev : shown) addRow(ev);
        scrollToBottom();
    }

    private void appendRows(List<JSONObject> added) {
        if (eventBox == null || added == null) return;
        boolean appended = false;
        for (JSONObject ev : added) {
            if (!MonitorFormat.accept(category, ev)) continue;
            if (emptyHint != null) {
                eventBox.removeView(emptyHint);
                emptyHint = null;
            }
            addRow(ev);
            appended = true;
        }
        if (appended) scrollToBottom();
    }

    private void addRow(final JSONObject ev) {
        TextView row = new TextView(host);
        row.setText(MonitorFormat.formatEvent(ev));
        row.setTextSize(11);
        row.setTypeface(Typeface.MONOSPACE);
        row.setTextColor(rowColor(ev.optString("kind", "")));
        row.setLineSpacing(dp(1), 1f);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setBackground(UiStyle.roundRectDp(host, UiStyle.SURFACE_2, 8));
        row.setOnClickListener(v -> showJson(ev));
        row.setOnLongClickListener(v -> {
            copyToClipboard(rawJson(ev));
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        eventBox.addView(row, lp);
    }

    private int rowColor(String kind) {
        switch (MonitorFormat.categoryOf(kind)) {
            case MonitorFormat.CAT_TASK:
            case MonitorFormat.CAT_MARK:
                return UiStyle.INK;
            case MonitorFormat.CAT_PROTO:
                return UiStyle.INK2;
            case MonitorFormat.CAT_SESSION:
                return UiStyle.BRAND_TEXT;
            case MonitorFormat.CAT_NOTIFY:
                return UiStyle.PURPLE;
            default:
                return UiStyle.INK3;
        }
    }

    private void scrollToBottom() {
        if (eventScroll == null) return;
        eventScroll.post(() -> {
            try {
                eventScroll.fullScroll(View.FOCUS_DOWN);
            } catch (Throwable ignored) {
            }
        });
    }

    private void showJson(final JSONObject ev) {
        final String raw = rawJson(ev);
        ScrollView sc = new ScrollView(host);
        TextView tv = new TextView(host);
        tv.setText(prettyJson(ev));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(UiStyle.INK);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(12));
        sc.addView(tv);
        new AlertDialog.Builder(host, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("事件 #" + ev.optLong("seq", 0L) + " " + ev.optString("kind", ""))
                .setView(sc)
                .setPositiveButton("复制", (d, w) -> copyToClipboard(raw))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("monitor_event", text));
            UiStyle.toast(host, "已复制到剪贴板");
        } catch (Throwable t) {
            UiStyle.toast(host, "复制失败");
        }
    }

    private static String rawJson(JSONObject ev) {
        try {
            return ev.toString();
        } catch (Throwable t) {
            return String.valueOf(ev);
        }
    }

    private static String prettyJson(JSONObject ev) {
        try {
            return ev.toString(2);
        } catch (Throwable t) {
            return rawJson(ev);
        }
    }

    private void onMark() {
        final String msg = "用户打点 " + MonitorFormat.formatTime(System.currentTimeMillis());
        try {
            VaultSync.markMonitor(host, msg, (code, message, data) -> host.runOnUiThread(() -> {
                if (host.isFinishing()) return;
                if (code != 0) {
                    lastError = "打点失败：" + describeError(code, message);
                    updateStatus();
                    UiStyle.toast(host, lastError);
                    return;
                }
                boolean enabled = data != null && data.optBoolean("enabled", false);
                long seq = data == null ? 0L : data.optLong("seq", 0L);
                if (!enabled) {
                    String hint = data == null ? "" : data.optString("message", "");
                    lastError = "打点未写入：服务端监控未启用"
                            + (hint.isEmpty() ? "" : "（" + hint + "）");
                    updateStatus();
                    UiStyle.toast(host, lastError);
                    return;
                }
                lastError = "";
                updateStatus();
                UiStyle.toast(host, seq > 0L ? "已打点 seq=" + seq : "已打点");
                poll(false);
            }));
        } catch (Throwable t) {
            lastError = "打点失败：" + t.getClass().getSimpleName();
            updateStatus();
            UiStyle.toast(host, lastError);
        }
    }

    private void onExport() {
        if (events.isEmpty()) {
            UiStyle.toast(host, "暂无事件可导出");
            return;
        }
        final List<JSONObject> snapshot = new ArrayList<>(events);
        final String[] path = new String[1];
        final String[] err = new String[1];
        new Thread(() -> {
            try {
                File f = new File(host.getFilesDir(),
                        "monitor_export_" + System.currentTimeMillis() + ".jsonl");
                FileOutputStream fos = new FileOutputStream(f);
                OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
                try {
                    for (JSONObject o : snapshot) {
                        w.write(rawJson(o));
                        w.write('\n');
                    }
                } finally {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
                path[0] = f.getAbsolutePath();
            } catch (Throwable t) {
                err[0] = t.getClass().getSimpleName() + "：" + t.getMessage();
            }
            host.runOnUiThread(() -> {
                if (host.isFinishing()) return;
                if (path[0] != null) {
                    UiStyle.toast(host, "已导出 " + snapshot.size() + " 条 → " + path[0]);
                } else {
                    UiStyle.toast(host, "导出失败：" + err[0]);
                }
            });
        }, "monitor-export").start();
    }
}
