package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
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
 * 真机监控页（2026-10-25）：实时查看服务端「任务级协议监控」事件流。
 *
 * <p>入口：我的 → 工具箱 → 「真机监控」（{@code FeatureGate.Feature.DEV_MONITOR}，T3 正式包可见）。
 * 数据源：{@code POST /v1/vault/monitor}（{@link VaultSync#fetchMonitor}，op=vaultMonitor），
 * 契约见 {@code design/真机监控-服务端任务级协议监控-20261025.md} §3.3 / §4。
 *
 * <p>页面结构（纯代码构建，UiStyle 主题，无 XML / 无第三方库）：
 * <ul>
 *   <li>顶栏：返回 + 标题「真机监控」；</li>
 *   <li>状态卡：启用状态 / 已收条数 / seq 游标 / 服务端时间 / 最近错误；
 *       自动刷新开关（默认开，前台 2s 轮询，onPause 停、onResume 恢复）+ 刷新按钮；</li>
 *   <li>过滤 chips：全部 / 任务(task.*) / 协议(io.*) / 会话(session) / 推送(notify) / 打点(mark)，
 *       只在本地过滤已拉取事件，不重复请求；</li>
 *   <li>事件列表：ScrollView 内逐行 TextView（等宽小字），seq 升序、新事件到达自动滚到底；
 *       点击行弹完整 JSON（可复制），长按行直接复制到剪贴板；</li>
 *   <li>底部：「打点」写一条 mark 事件（文案含当前时间 HH:mm:ss）、「导出」把已拉取事件
 *       写 {@code filesDir/monitor_export_<ts>.jsonl}（toast 绝对路径，可 adb pull）。</li>
 * </ul>
 *
 * <p><b>容错</b>：服务端未部署该接口（旧服务端 404/非 JSON）或未开启监控（{@code VAULT_MONITOR=off}）
 * 时只在状态行提示，不崩溃；错误会停止自动刷新，可点「刷新」或重开开关手动重试。
 * 页面不做权限门控（入口由 FeatureGate / 工具箱控制），内部最多保留最近
 * {@link #MEM_CAP} 条事件避免长期常驻内存膨胀。
 */
public class MonitorActivity extends Activity {

    /** 内存保留上限（最近 N 条；导出/筛选都基于该窗口）。 */
    private static final int MEM_CAP = 800;
    /** 单次拉取条数（契约 limit 1..1000，页面首屏 200）。 */
    private static final int PAGE_LIMIT = 200;
    /** 自动刷新间隔：前台 2s。 */
    private static final long POLL_MS = 2000L;

    /** 已拉取事件（seq 升序；最多 MEM_CAP 条）。 */
    private final List<JSONObject> events = new ArrayList<>();
    /** 增量游标：已拉取的最大 seq（首次 0 = 从头拉 200 条）。 */
    private long cursor;
    /** 服务端时间（响应 serverTime，毫秒）。 */
    private long serverTime;
    /** 本页累计收到的事件条数（去重后，含已被内存窗口裁掉的）。 */
    private int received;
    /** 当前过滤分类（MonitorFormat.CAT_*）。 */
    private int category = MonitorFormat.CAT_ALL;
    /** 最近错误/提示（空串 = 无）。 */
    private String lastError = "";
    /** 服务端监控启用状态文案。 */
    private String enabledText = "未知";
    /** 是否有请求在途（避免 2s 轮询叠加请求）。 */
    private boolean polling;
    /** 自动刷新开关（默认开；出错时自动关）。 */
    private boolean autoRefresh = true;
    /** 页面是否在前台（onResume~onPause）。 */
    private boolean resumed;

    private TextView statusView;
    private ScrollView eventScroll;
    private LinearLayout eventBox;
    private LinearLayout chipsRow;
    private UiStyle.Switch autoSw;
    /** 列表空态提示（有事件追加时移除）。 */
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

    /** 兜底：进程被回收后本页可能成为栈底，按返回应回主页而不是退出 App。 */
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
        setContentView(buildUi());
        updateStatus();
        renderEvents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 进前台先拉一次（也作为手动重试后的恢复），再按开关挂 2s 轮询
        poll(true);
        if (autoRefresh) startAuto();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(pollTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(pollTick);
        super.onDestroy();
    }

    // ================= UI 构建 =================

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);

        // ---- 顶栏：‹ + 标题 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(6));
        FrameLayout backWrap = new FrameLayout(this);
        backWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.BRAND_TEXT, 22),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(backWrap);
        backWrap.setOnClickListener(v -> finish());
        bar.addView(backWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("真机监控");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        // ---- 状态卡：状态行 + 自动刷新/刷新 ----
        LinearLayout statusCard = UiStyle.card(this);
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(UiStyle.INK2);
        statusView.setLineSpacing(dp(3), 1f);
        statusCard.addView(statusView);

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        ctrl.setPadding(0, dp(10), 0, 0);
        TextView autoLabel = new TextView(this);
        autoLabel.setText("自动刷新（2s）");
        autoLabel.setTextSize(12);
        autoLabel.setTextColor(UiStyle.INK2);
        ctrl.addView(autoLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        autoSw = new UiStyle.Switch(this, autoRefresh, () -> {
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
        View spacer = new View(this);
        ctrl.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView refresh = UiStyle.secondaryBtn(this, "刷新", () -> {
            lastError = "";
            updateStatus();
            poll(true);
        });
        ctrl.addView(refresh, new LinearLayout.LayoutParams(dp(88), dp(40)));
        statusCard.addView(ctrl);
        root.addView(statusCard);

        // ---- 过滤 chips（横向可滚动，窄屏不挤压）----
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(16), dp(8), dp(16), 0);
        chipScroll.addView(chipsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildChips();

        // ---- 事件列表 ----
        eventScroll = new ScrollView(this);
        eventScroll.setVerticalScrollBarEnabled(true);
        eventBox = new LinearLayout(this);
        eventBox.setOrientation(LinearLayout.VERTICAL);
        eventBox.setPadding(dp(16), dp(8), dp(16), dp(16));
        eventScroll.addView(eventBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(eventScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部：打点 / 导出 ----
        root.addView(UiStyle.divider(this));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(UiStyle.CARD);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView markBtn = UiStyle.secondaryBtn(this, "打点", this::onMark);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        mlp.rightMargin = dp(10);
        bottom.addView(markBtn, mlp);
        TextView exportBtn = UiStyle.secondaryBtn(this, "导出", this::onExport);
        bottom.addView(exportBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(bottom);

        return root;
    }

    /** 重建过滤 chips（选中态切换后整行重画，chip 数量固定 6）。 */
    private void buildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        for (int i = 0; i < MonitorFormat.CATEGORY_LABELS.length; i++) {
            final int cat = i;
            TextView chip = UiStyle.chip(this, MonitorFormat.CATEGORY_LABELS[i], cat == category);
            chip.setOnClickListener(v -> {
                if (category == cat) return;
                category = cat;
                buildChips();
                renderEvents();   // 本地过滤，不发请求
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chipsRow.addView(chip, lp);
        }
    }

    // ================= 状态行 =================

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

    /** 错误/未升级文案（区分：接口不存在 / 网络失败 / 服务端业务码 / 未开服务器模式）。 */
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

    // ================= 拉取 / 轮询 =================

    /** 首次打开是否已用 action=status 定位到服务端事件尾部（避免从环形缓冲最旧一条开始追）。 */
    private boolean tailLocated;

    /** 拉一次增量（since=cursor）；manual=true 时清错误并立即反馈。回调切回主线程。 */
    private void poll(final boolean manual) {
        if (polling) return;
        polling = true;
        if (manual) {
            lastError = "";
            updateStatus();
        }
        // 2026-10-25：首屏先取 status.lastSeq，游标直接落到“最近 PAGE_LIMIT 条”，
        // 否则 since=0 会从环形缓冲最旧一条开始、要轮询十几轮才追上实时尾部。
        if (!tailLocated && cursor <= 0L) {
            tailLocated = true;
            Map<String, Object> f = new java.util.LinkedHashMap<>();
            f.put("action", "status");
            try {
                VaultSync.fetchMonitor(this, f, (code, message, data) -> runOnUiThread(() -> {
                    polling = false;
                    if (isFinishing()) return;
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
            VaultSync.fetchMonitor(this, cursor, PAGE_LIMIT, "", "", true,
                    (code, message, data) -> runOnUiThread(() -> {
                        polling = false;
                        if (isFinishing()) return;
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

    /** 出错停止自动刷新（开关置关，可由用户手动重试）。 */
    private void stopAuto() {
        autoRefresh = false;
        if (autoSw != null) autoSw.setOn(false);
        ui.removeCallbacks(pollTick);
    }

    private void onMonitorResult(int code, String message, JSONObject data) {
        if (code != 0) {
            // 2026-10-25 修复：服务端重启/网关 502 属于瞬时故障，不能因此永久停掉自动刷新
            // （真机实证：服务端两次热部署后页面停在「不可用」+ 502 HTML 解析错误，用户以为监控坏了）。
            // 保持 2s 轮询自动恢复；只在用户手动关闭开关时停止。
            enabledText = "重试中";
            lastError = describeError(code, message) + "（自动重试中…）";
            updateStatus();
            return;
        }
        if (data == null) {
            // 旧服务端可能返回 200 但信封无 data（或被网关改写）：按「未升级」提示，不崩溃
            enabledText = "不可用";
            lastError = "服务端未升级：监控响应缺少 data";
            stopAuto();
            updateStatus();
            return;
        }
        // 服务端重启（旧版 seq 归零）容错：游标超前于服务端最新 seq 时，回退到服务端窗口起点，
        // 避免游标永久卡死（新版服务端已做 seq 跨重启续号，这里只是兜底）。
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
                renderEvents();      // 溢出裁剪后整表重画，保证行与内存一致
            } else {
                appendRows(delta.events);
            }
            lastError = "";
        }
        if (!enabled) {
            // 服务端可达但监控未开：只提示，不停止自动刷新（部署方打开 VAULT_MONITOR 后自动恢复）
            lastError = "服务端监控未启用（需在服务端开 VAULT_MONITOR=on 并重启）";
        } else if (delta.isEmpty()) {
            lastError = "";
        }
        updateStatus();
    }

    // ================= 事件列表渲染 =================

    /** 整表重画（首次 / 切过滤 / 内存裁剪后）。 */
    private void renderEvents() {
        if (eventBox == null) return;
        eventBox.removeAllViews();
        emptyHint = null;
        List<JSONObject> shown = MonitorFormat.filter(events, category);
        if (shown.isEmpty()) {
            TextView empty = new TextView(this);
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

    /** 增量追加（只加当前过滤分类接受的事件）。 */
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
        TextView row = new TextView(this);
        row.setText(MonitorFormat.formatEvent(ev));
        row.setTextSize(11);
        row.setTypeface(Typeface.MONOSPACE);
        row.setTextColor(rowColor(ev.optString("kind", "")));
        row.setLineSpacing(dp(1), 1f);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setBackground(UiStyle.roundRectDp(this, UiStyle.SURFACE_2, 8));
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

    /** 行文字颜色：任务/打点主文本，协议次文本，会话品牌色，推送紫色，未识别弱化。 */
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

    // ================= 行详情 / 复制 =================

    /** 点击行：弹完整 JSON（等宽可选中，可复制）。 */
    private void showJson(final JSONObject ev) {
        final String raw = rawJson(ev);
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(prettyJson(ev));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(UiStyle.INK);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(12), dp(18), dp(12));
        sc.addView(tv);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("事件 #" + ev.optLong("seq", 0L) + " " + ev.optString("kind", ""))
                .setView(sc)
                .setPositiveButton("复制", (d, w) -> copyToClipboard(raw))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("monitor_event", text));
            UiStyle.toast(this, "已复制到剪贴板");
        } catch (Throwable t) {
            UiStyle.toast(this, "复制失败");
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

    // ================= 打点 / 导出 =================

    /** 打点：写一条 mark 事件（文案带当前 HH:mm:ss），用户手动操作原生游戏前后各点一次。 */
    private void onMark() {
        final String msg = "用户打点 " + MonitorFormat.formatTime(System.currentTimeMillis());
        try {
            VaultSync.markMonitor(this, msg, (code, message, data) -> runOnUiThread(() -> {
                if (isFinishing()) return;
                if (code != 0) {
                    lastError = "打点失败：" + describeError(code, message);
                    updateStatus();
                    UiStyle.toast(this, lastError);
                    return;
                }
                // 服务端监控未启用时 mark 也回 code=0，但 data.enabled=false 且 seq=0
                // （契约 §3.3）：不能当成功，按提示处理。
                boolean enabled = data != null && data.optBoolean("enabled", false);
                long seq = data == null ? 0L : data.optLong("seq", 0L);
                if (!enabled) {
                    String hint = data == null ? "" : data.optString("message", "");
                    lastError = "打点未写入：服务端监控未启用"
                            + (hint.isEmpty() ? "" : "（" + hint + "）");
                    updateStatus();
                    UiStyle.toast(this, lastError);
                    return;
                }
                lastError = "";
                updateStatus();
                UiStyle.toast(this, seq > 0L ? "已打点 seq=" + seq : "已打点");
                poll(false);   // 立即回拉，打点与后续事件同屏
            }));
        } catch (Throwable t) {
            lastError = "打点失败：" + t.getClass().getSimpleName();
            updateStatus();
            UiStyle.toast(this, lastError);
        }
    }

    /** 导出：把内存中已拉取事件写 filesDir/monitor_export_<ts>.jsonl（adbd pull 取证）。 */
    private void onExport() {
        if (events.isEmpty()) {
            UiStyle.toast(this, "暂无事件可导出");
            return;
        }
        final List<JSONObject> snapshot = new ArrayList<>(events);
        final String[] path = new String[1];
        final String[] err = new String[1];
        new Thread(() -> {
            try {
                File f = new File(getFilesDir(),
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
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (path[0] != null) {
                    UiStyle.toast(this, "已导出 " + snapshot.size() + " 条 → " + path[0]);
                } else {
                    UiStyle.toast(this, "导出失败：" + err[0]);
                }
            });
        }, "monitor-export").start();
    }
}
