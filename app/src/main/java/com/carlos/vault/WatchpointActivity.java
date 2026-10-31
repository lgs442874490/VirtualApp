package com.carlos.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.daily.WatchpointRules;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 观察点配置页（批次 B，2026-10-30）：App 内可视化查看/编辑服务器 {@code data/watchpoint.json}。
 *
 * <p>链路：进入页面 {@link VaultSync#watchpointGet} 拉取；保存 {@link VaultSync#watchpointSet}
 * （服务端原子写 + {@link VaultSync.watchpointEnable} 只改开关），WatchPointEngine 按 mtime 5s 热载。
 * 校验与 {@code WatchPointStore} 1:1（{@link WatchpointRules}）：uid&gt;0、坐标 0~1200、矩形跨度≤55；
 * 服务端兜底 fail-closed（非法输入旧配置不变）。
 *
 * <p>注意：服务器环境变量 {@code VAULT_WATCHPOINT} 未开启时，页面开关/保存均不会让观察点真正运行，
 * 顶部会显式提示。
 */
public class WatchpointActivity extends Activity {

    private final List<JSONObject> targets = new ArrayList<>();
    private final List<JSONObject> rects = new ArrayList<>();

    private LinearLayout targetsBox;
    private LinearLayout rectsBox;
    private EditText friendsEt;
    private EditText alertsEt;
    private UiStyle.Switch enabledSw;
    private TextView envHint;
    private TextView statusText;
    private boolean enabled = true;
    private boolean envEnabled;
    private boolean wecomEnabled = true;
    private boolean busy;

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private void toast(String msg) {
        UiStyle.toast(this, msg);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ============================== UI ==============================

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
        title.setText("观察点");
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

        // 环境提示
        LinearLayout hintCard = UiStyle.outlineCard(this);
        hintCard.setOrientation(LinearLayout.VERTICAL);
        hintCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView hintTitle = new TextView(this);
        hintTitle.setText("生效条件");
        hintTitle.setTextSize(13.5f);
        hintTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hintTitle.setTextColor(UiStyle.INK);
        hintCard.addView(hintTitle);
        envHint = new TextView(this);
        envHint.setTextSize(12.5f);
        envHint.setTextColor(UiStyle.INK3);
        envHint.setPadding(0, dp(6), 0, 0);
        envHint.setText("正在读取服务器配置…");
        hintCard.addView(envHint);
        box.addView(hintCard);

        // 开关
        box.addView(UiStyle.sectionTitle(this, "总开关"));
        LinearLayout swCard = UiStyle.outlineCard(this);
        swCard.setOrientation(LinearLayout.HORIZONTAL);
        swCard.setGravity(Gravity.CENTER_VERTICAL);
        swCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView swLabel = new TextView(this);
        swLabel.setText("启用观察点（服务器执行）");
        swLabel.setTextSize(13.5f);
        swLabel.setTextColor(UiStyle.INK);
        swCard.addView(swLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        enabledSw = new UiStyle.Switch(this, enabled, () -> enabled = enabledSw.isOn());
        swCard.addView(enabledSw);
        box.addView(swCard);

        // 监视目标
        box.addView(sectionHeader("监视目标（敌方城）", "添加目标", this::showAddTarget));
        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(targetsBox);

        // 保护矩形
        box.addView(sectionHeader("保护矩形（友盟聚集地）", "添加矩形", this::showAddRect));
        rectsBox = new LinearLayout(this);
        rectsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(rectsBox);

        // 友盟 aid / 告警类型
        box.addView(UiStyle.sectionTitle(this, "友盟 aid（逗号分隔）"));
        friendsEt = input("例如 89700055,89700652");
        box.addView(cardWrap(friendsEt));

        box.addView(UiStyle.sectionTitle(this, "告警类型（逗号分隔）"));
        alertsEt = input("例如 1,2,99,100,102");
        box.addView(cardWrap(alertsEt));

        // 保存
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setPadding(0, dp(18), 0, 0);
        TextView save = UiStyle.primaryBtn(this, "保存到服务器", this::save);
        saveRow.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        box.addView(saveRow);

        statusText = new TextView(this);
        statusText.setTextSize(12.5f);
        statusText.setTextColor(UiStyle.INK3);
        statusText.setPadding(dp(2), dp(10), dp(2), 0);
        box.addView(statusText);

        TextView note = new TextView(this);
        note.setTextSize(11.5f);
        note.setTextColor(UiStyle.INK3);
        note.setPadding(dp(2), dp(14), dp(2), 0);
        note.setText("保存后服务器 5 秒内自动热载，无需重启；非法配置会被服务端拒绝且旧配置不变。");
        box.addView(note);
        return root;
    }

    private View sectionHeader(String title, String actionText, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, 0);
        TextView t = UiStyle.sectionTitle(this, title);
        t.setPadding(0, 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView btn = UiStyle.ghostBtn(this, actionText, action);
        row.addView(btn);
        return row;
    }

    private EditText input(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(null);
        et.setPadding(dp(4), dp(4), dp(4), dp(4));
        return et;
    }

    private View cardWrap(View v) {
        LinearLayout c = UiStyle.outlineCard(this);
        c.setPadding(dp(10), dp(6), dp(10), dp(6));
        c.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    // ============================== 数据加载 ==============================

    private void load() {
        VaultSync.watchpointGet(this, (code, msg, data) -> runOnUiThread(() -> {
            if (code != 0 || data == null) {
                String hint = code == -1
                        ? "服务端未升级（无 /v1/vault/watchpoint），请部署新版 jar 后重试"
                        : ("读取失败：" + safe(msg));
                statusText.setText(hint);
                envHint.setText("⚠️ " + hint);
                envHint.setTextColor(0xFFC0392B);
                return;
            }
            applySnapshot(data);
        }));
    }

    private void applySnapshot(JSONObject data) {
        enabled = data.optBoolean("enabled", true);
        envEnabled = data.optBoolean("envEnabled", false);
        wecomEnabled = data.optBoolean("wecomEnabled", true);
        if (enabledSw != null) enabledSw.setOn(enabled);
        targets.clear();
        rects.clear();
        JSONArray ta = data.optJSONArray("monitorTargets");
        if (ta != null) {
            for (int i = 0; i < ta.length(); i++) {
                JSONObject o = ta.optJSONObject(i);
                if (o != null) targets.add(o);
            }
        }
        JSONArray ra = data.optJSONArray("protectRects");
        if (ra != null) {
            for (int i = 0; i < ra.length(); i++) {
                JSONObject o = ra.optJSONObject(i);
                if (o != null) rects.add(o);
            }
        }
        friendsEt.setText(csv(data.optJSONArray("friendAids")));
        alertsEt.setText(csv(data.optJSONArray("alertTypes")));
        renderTargets();
        renderRects();
        if (!envEnabled) {
            envHint.setText("⚠️ 服务器环境变量 VAULT_WATCHPOINT 未开启（当前不生效）；"
                    + "文件 enabled=" + enabled + "。请联系运维开启后重启一次服务端。");
            envHint.setTextColor(0xFFC0392B);
        } else {
            envHint.setText("服务器已启用（VAULT_WATCHPOINT=on）；当前文件 enabled=" + enabled
                    + "，保存后 5 秒内热载。");
            envHint.setTextColor(UiStyle.INK3);
        }
        long at = data.optLong("updatedAt", 0L);
        statusText.setText(at > 0 ? ("上次更新：" + fmt(at)) : "尚未保存过配置");
    }

    private void renderTargets() {
        targetsBox.removeAllViews();
        if (targets.isEmpty()) {
            targetsBox.addView(emptyText("暂无监视目标（至少需要 1 个）"));
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            JSONObject o = targets.get(i);
            final int index = i;
            String line = "uid" + o.optLong("uid")
                    + (o.optString("nick", "").isEmpty() ? "" : " · " + o.optString("nick"))
                    + "   (" + o.optInt("x") + "," + o.optInt("y") + ")";
            targetsBox.addView(itemRow(line, () -> showEditTarget(index),
                    () -> confirmDelete("监视目标", line, () -> {
                        targets.remove(index);
                        renderTargets();
                    })));
        }
    }

    private void renderRects() {
        rectsBox.removeAllViews();
        if (rects.isEmpty()) {
            rectsBox.addView(emptyText("暂无保护矩形（至少需要 1 个）"));
            return;
        }
        for (int i = 0; i < rects.size(); i++) {
            JSONObject o = rects.get(i);
            final int index = i;
            String line = o.optString("name", "矩形")
                    + "  (" + o.optInt("x1") + "," + o.optInt("y1") + ")-("
                    + o.optInt("x2") + "," + o.optInt("y2") + ")";
            rectsBox.addView(itemRow(line, () -> showEditRect(index),
                    () -> confirmDelete("保护矩形", line, () -> {
                        rects.remove(index);
                        renderRects();
                    })));
        }
    }

    private View itemRow(String text, Runnable edit, Runnable delete) {
        LinearLayout row = UiStyle.outlineCard(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(UiStyle.INK);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView ed = UiStyle.ghostBtn(this, "编辑", edit);
        row.addView(ed);
        TextView del = UiStyle.dangerBtn(this, "删除", delete);
        row.addView(del);
        return row;
    }

    private TextView emptyText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12.5f);
        tv.setTextColor(UiStyle.INK3);
        tv.setPadding(dp(6), dp(8), dp(6), dp(8));
        return tv;
    }

    // ============================== 编辑表单 ==============================

    private void showAddTarget() {
        showTargetDialog(-1);
    }

    private void showEditTarget(int index) {
        showTargetDialog(index);
    }

    private void showTargetDialog(int index) {
        JSONObject cur = index >= 0 ? targets.get(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), 0);
        EditText uid = field("uid（必填，>0）", cur == null ? "" : String.valueOf(cur.optLong("uid")));
        EditText nick = field("昵称（可选）", cur == null ? "" : cur.optString("nick", ""));
        EditText x = field("x 坐标（0~1200）", cur == null ? "" : String.valueOf(cur.optInt("x")));
        EditText y = field("y 坐标（0~1200）", cur == null ? "" : String.valueOf(cur.optInt("y")));
        for (EditText e : new EditText[]{uid, nick, x, y}) form.addView(e);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑监视目标" : "添加监视目标")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String err = WatchpointRules.validateTarget(text(uid), text(nick), text(x), text(y));
                    if (err != null) {
                        toast(err);
                        return;
                    }
                    JSONObject o = WatchpointRules.target(Long.parseLong(text(uid).trim()),
                            text(nick).trim(), parseInt(text(x)), parseInt(text(y)));
                    if (index >= 0) {
                        targets.set(index, o);
                    } else {
                        long uidVal = o.optLong("uid");
                        for (JSONObject t : targets) {
                            if (t.optLong("uid") == uidVal) {
                                toast("uid 已存在: " + uidVal);
                                return;
                            }
                        }
                        targets.add(o);
                    }
                    renderTargets();
                    dlg.dismiss();
                }));
        dlg.show();
    }

    private void showAddRect() {
        showRectDialog(-1);
    }

    private void showEditRect(int index) {
        showRectDialog(index);
    }

    private void showRectDialog(int index) {
        JSONObject cur = index >= 0 ? rects.get(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), 0);
        EditText name = field("名称", cur == null ? "" : cur.optString("name", ""));
        EditText x1 = field("x1", cur == null ? "" : String.valueOf(cur.optInt("x1")));
        EditText y1 = field("y1", cur == null ? "" : String.valueOf(cur.optInt("y1")));
        EditText x2 = field("x2（x2-x1 ≤ 55）", cur == null ? "" : String.valueOf(cur.optInt("x2")));
        EditText y2 = field("y2（y2-y1 ≤ 55）", cur == null ? "" : String.valueOf(cur.optInt("y2")));
        for (EditText e : new EditText[]{name, x1, y1, x2, y2}) form.addView(e);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑保护矩形" : "添加保护矩形")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String err = WatchpointRules.validateRect(text(name), text(x1), text(y1),
                            text(x2), text(y2));
                    if (err != null) {
                        toast(err);
                        return;
                    }
                    JSONObject o = WatchpointRules.rect(text(name).trim(), parseInt(text(x1)),
                            parseInt(text(y1)), parseInt(text(x2)), parseInt(text(y2)));
                    if (index >= 0) {
                        rects.set(index, o);
                    } else {
                        rects.add(o);
                    }
                    renderRects();
                    dlg.dismiss();
                }));
        dlg.show();
    }

    private EditText field(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setInputType(hint.contains("坐标") || hint.startsWith("x") || hint.startsWith("y")
                ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        et.setPadding(dp(8), dp(10), dp(8), dp(10));
        return et;
    }

    private void confirmDelete(String what, String line, Runnable onOk) {
        new AlertDialog.Builder(this)
                .setTitle("删除" + what)
                .setMessage("确定删除？\n" + line)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> onOk.run())
                .show();
    }

    // ============================== 保存 ==============================

    private void save() {
        if (busy) return;
        JSONArray ta = new JSONArray();
        for (JSONObject o : targets) ta.put(o);
        JSONArray ra = new JSONArray();
        for (JSONObject o : rects) ra.put(o);
        String friends = text(friendsEt);
        String alerts = text(alertsEt);
        String err = WatchpointRules.validateAll(ta, ra, friends, alerts);
        if (err != null) {
            toast(err);
            return;
        }
        busy = true;
        statusText.setText("正在保存到服务器…");
        VaultSync.watchpointSet(this, enabledSw == null ? enabled : enabledSw.isOn(), ta, ra,
                friends, alerts, wecomEnabled, (code, msg, data) -> runOnUiThread(() -> {
                    busy = false;
                    if (code == 0 && data != null) {
                        toast("已保存（服务器 5 秒内热载）");
                        applySnapshot(data);
                    } else {
                        String m = code == -1
                                ? "保存失败：服务端未升级或网络异常"
                                : ("保存失败（" + code + "）：" + safe(msg));
                        statusText.setText(m);
                        toast(m);
                    }
                }));
    }

    // ============================== 工具 ==============================

    private static String text(EditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static String csv(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(arr.optLong(i));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(long ts) {
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(ts));
        } catch (Throwable t) {
            return String.valueOf(ts);
        }
    }
}
