package com.carlos.vault.daily.movecity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import com.carlos.vault.AccountPoolCache;
import com.carlos.vault.AccountStore;
import com.carlos.vault.UiComponents;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 迁城页（2026-11-06，网页版迁城 1:1 复刻 · App 侧）。
 *
 * <p>链路：服务端 movecity 引擎（12 种协议 / 立即批量 / 定时抢位 / 任务管理）经
 * {@code POST /v1/vault/movecity}（{@link VaultSync#movecityExecute} 等，op=vaultMovecity）
 * 执行；本页只做配置提交与任务展示，不在本地发任何游戏协议。
 *
 * <p>页面结构（UiStyle/UiComponents，纯代码构建）：
 * <ul>
 *   <li>顶部提示条：服务端未升级/不可用时显示，禁止提交但可浏览；</li>
 *   <li>模式分段：立即迁城（多账号批量）/ 定时迁城（单账号，精确到秒 + 提前量 0-100ms）；</li>
 *   <li>迁城类型：12 种协议下拉单选（与服务端注册表同表，客户端硬编码兜底）；</li>
 *   <li>迁城坐标：按选中账号逐行 x/y 输入（needs_point 协议）；目标王国单输入（needs_kid）；</li>
 *   <li>迁城账号：号库账号多选（定时模式单选），显示 名称 + fid + 在线徽标；</li>
 *   <li>提交前二次确认（列出账号/协议/坐标/定时参数）——迁城消耗真实道具；</li>
 *   <li>任务列表：定时中 / 已完成 两组，条目点击看详情（逐次 attempt_details），
 *       未开始（cancelable）可取消。</li>
 * </ul>
 *
 * <p>校验文案与网页版逐字对齐：请选择迁城账号 / 请选择迁城类型 / 请填写迁城坐标 /
 * 请填写目标王国 / 请选择定时时间 / 定时时间必须晚于当前时间 / 账号当前离线，请开启执行前自动上线。
 */
public class MoveCityActivity extends Activity {

    // ================= 协议表（与服务端 movecity 注册表 1:1；页面兜底，进入后再向服务端对齐） =================

    /** 协议定义：label 中文名 / proto 协议名 / needsPoint 是否需要坐标 / needsKid 是否需要王国。 */
    private static final class Proto {
        final String label;
        final String proto;
        final boolean needsPoint;
        final boolean needsKid;

        Proto(String label, String proto, boolean needsPoint, boolean needsKid) {
            this.label = label;
            this.proto = proto;
            this.needsPoint = needsPoint;
            this.needsKid = needsKid;
        }
    }

    /** 12 种迁城协议（顺序与网页版 MoveCityService.PROTOCOLS 一致，勿改顺序）。 */
    private static final Proto[] FALLBACK_PROTOCOLS = {
            new Proto("迁城", "req_world_move_city", true, false),
            new Proto("随机迁城", "req_world_random_move_city", false, false),
            new Proto("霜龙迁城", "req_supremacy_war_move_city", true, false),
            new Proto("燃霜迁城", "req_frostfire_war_move_city", true, false),
            new Proto("大乱斗迁城", "req_brawl_war_move_city", true, false),
            new Proto("联盟迁城", "req_world_alliance_move_city", true, false),
            new Proto("领地迁城", "req_world_alliance_territory_move_city", true, false),
            new Proto("兵工厂迁城", "req_arsenal_war_move_city", true, false),
            new Proto("跨王国随机迁城", "req_world_wormhole_jumping_random", false, true),
            new Proto("跨王国回家", "req_world_gohome", false, false),
            new Proto("跨王国高级迁城", "req_world_wormhole_jumping_to_point", true, true),
            new Proto("跨王国高级回家", "req_world_gohome_to_point", true, false),
    };

    /** 定时执行时间格式（北京时间，与网页版一致；秒由选择器固定 :00，也支持手输完整格式）。 */
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // ================= 账号行（号库账号；在线状态 null=未知，本地兜底数据源） =================

    private static final class Acc {
        final String fpid;
        final String name;
        final Boolean online;
        boolean selected;

        Acc(String fpid, String name, Boolean online) {
            this.fpid = fpid;
            this.name = name;
            this.online = online;
        }
    }

    // ================= 页面状态 =================

    private final List<Proto> protocols = new ArrayList<>();
    private Proto selProto;
    /** 0=立即迁城（多账号批量）；1=定时迁城（单账号）。 */
    private int mode = 0;
    private final List<Acc> accs = new ArrayList<>();
    /** 坐标草稿：fpid → [x,y]。重渲染坐标区前存回，避免重建输入框丢字。 */
    private final Map<String, String[]> coordDraft = new LinkedHashMap<>();
    /** 渲染中的坐标输入框：fpid → [xEdit,yEdit]（仅 renderCoords 与提交读用）。 */
    private final Map<String, EditText[]> coordEdits = new HashMap<>();
    /** 服务端迁城端点是否可用（protocols 探测成功）。 */
    private boolean serverReady;
    /** 提交防重。 */
    private boolean busy;

    // ================= UI 引用 =================

    private TextView warnBanner;
    private LinearLayout protoRow;
    private TextView protoValue;
    private LinearLayout coordSection;
    private LinearLayout coordBox;
    private LinearLayout kidCard;
    private EditText kidEt;
    private LinearLayout accBox;
    private LinearLayout scheduleCard;
    private EditText timeEt;
    private EditText advanceEt;
    private UiStyle.Switch autoSw;
    private TextView submitBtn;
    private LinearLayout scheduledBox;
    private LinearLayout finishedBox;
    private TextView jobsHint;

    private int dp(int v) {
        return UiStyle.dp(this, v);
    }

    private void toast(String msg) {
        UiStyle.toast(this, msg);
    }

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
        // 防旁路（与本项目 ToolBox/FormationManager 同口径）：门控在页面自身再判一次，
        // 即使有人从别处深链/构造 Intent 直达本页，也不渲染未授权功能界面。
        if (!com.carlos.vault.FeatureGate.visible(this,
                com.carlos.vault.FeatureGate.Feature.MOVE_CITY)) {
            toast(com.carlos.vault.FeatureGate.reasonText(
                    com.carlos.vault.FeatureGate.Feature.MOVE_CITY));
            finish();
            return;
        }
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        for (Proto p : FALLBACK_PROTOCOLS) protocols.add(p);
        selProto = protocols.get(0);
        setContentView(buildUi());
        detectServer();
        loadAccounts();
        loadJobs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前台刷新：账号池（在线状态）+ 任务列表
        refreshPool();
        if (serverReady) loadJobs();
    }

    // ================= UI 构建 =================

    private View buildUi() {
        UiComponents.Page page = UiComponents.scrollPage(
                this, "迁城", "12 种协议 · 立即批量 / 定时抢位（服务器执行）", this::finish, 24);
        LinearLayout box = page.body;

        // ---- 顶部提示条（服务端不可用；默认不占位） ----
        warnBanner = new TextView(this);
        warnBanner.setTextSize(12.5f);
        warnBanner.setTypeface(Typeface.DEFAULT_BOLD);
        warnBanner.setTextColor(UiStyle.WARN_INK);
        warnBanner.setBackground(UiStyle.roundRectDp(this, UiStyle.WARN_BG, 12));
        warnBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        warnBanner.setVisibility(View.GONE);
        box.addView(warnBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 模式分段：立即 | 定时 ----
        box.addView(UiComponents.sectionHeader(this, "迁城模式", "迁城会消耗真实道具"));
        box.addView(UiStyle.segmented(this, new String[]{"立即迁城", "定时迁城"}, mode,
                idx -> {
                    if (mode == idx) return;
                    mode = idx;
                    if (mode == 1) {
                        // 定时 = 单账号：保留第一个选中
                        boolean kept = false;
                        for (Acc a : accs) {
                            if (a.selected && !kept) {
                                kept = true;
                            } else {
                                a.selected = false;
                            }
                        }
                    }
                    renderAccs();
                    renderCoords();
                    renderModeSections();
                }));

        // ---- 迁城类型（12 种协议，单选下拉） ----
        box.addView(UiComponents.sectionHeader(this, "迁城类型", null));
        protoRow = new LinearLayout(this);
        protoRow.setOrientation(LinearLayout.VERTICAL);
        protoRow.setBackground(UiStyle.roundRectStroke(this, UiStyle.CARD, 16, UiStyle.LINE));
        protoRow.setPadding(dp(16), dp(6), dp(16), dp(6));
        protoValue = UiStyle.dropdownRow(this, "协议", protoLabel(), this::showProtoPicker);
        protoRow.addView(protoValue);
        box.addView(protoRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 迁城坐标（needs_point：按选中账号逐行 x/y） ----
        coordSection = new LinearLayout(this);
        coordSection.setOrientation(LinearLayout.VERTICAL);
        coordSection.addView(UiComponents.sectionHeader(this, "迁城坐标", "每个账号一个目标点"));
        coordBox = new LinearLayout(this);
        coordBox.setOrientation(LinearLayout.VERTICAL);
        coordSection.addView(coordBox);
        box.addView(coordSection);

        // ---- 目标王国（needs_kid：全局一个） ----
        kidCard = UiComponents.card(this);
        TextView kidTitle = UiComponents.text(this, "目标王国", UiComponents.TEXT_LABEL, UiStyle.INK, true);
        kidCard.addView(kidTitle);
        TextView kidHint = UiComponents.caption(this, "跨王国协议需要的目标王国 ID（kid，正整数）", UiStyle.INK3);
        kidHint.setPadding(0, dp(4), 0, dp(8));
        kidCard.addView(kidHint);
        kidEt = numInput("目标王国 kid");
        kidCard.addView(kidEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(kidCard);

        // ---- 迁城账号（多选；定时单选） ----
        box.addView(UiComponents.sectionHeader(this, "迁城账号", "定时模式仅可选一个"));
        accBox = new LinearLayout(this);
        accBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(accBox);

        // ---- 定时设置 ----
        scheduleCard = UiComponents.card(this);
        buildScheduleCard(scheduleCard);
        box.addView(scheduleCard);

        // ---- 提交 ----
        submitBtn = UiStyle.primaryBtn(this, "立即迁城", this::onSubmit);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(18);
        box.addView(submitBtn, slp);

        // ---- 任务列表 ----
        box.addView(UiComponents.sectionHeader(this, "迁城任务", null));
        jobsHint = new TextView(this);
        jobsHint.setTextSize(12);
        jobsHint.setTextColor(UiStyle.INK3);
        jobsHint.setPadding(dp(2), dp(2), dp(2), dp(4));
        box.addView(jobsHint);

        box.addView(UiComponents.sectionHeader(this, "定时中", null));
        scheduledBox = new LinearLayout(this);
        scheduledBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(scheduledBox);

        box.addView(UiComponents.sectionHeader(this, "已完成", null));
        finishedBox = new LinearLayout(this);
        finishedBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(finishedBox);

        renderModeSections();
        renderProtoSections();   // 走查修复：首屏按默认协议隐藏「目标王国」（迁城协议不需要 kid）
        renderAccs();
        renderCoords();
        return page.root;
    }

    /** 定时设置卡：执行时间（选择器/手输）+ 提前量 + 执行前自动上线。 */
    private void buildScheduleCard(LinearLayout card) {
        TextView title = UiComponents.text(this, "定时设置", UiComponents.TEXT_LABEL, UiStyle.INK, true);
        card.addView(title);
        TextView hint = UiComponents.caption(this,
                "执行时间为北京时间，精确到秒（选择器秒固定 00，可手输完整 yyyy-MM-dd HH:mm:ss）",
                UiStyle.INK3);
        hint.setPadding(0, dp(4), 0, dp(8));
        card.addView(hint);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeEt = numInput("yyyy-MM-dd HH:mm:ss");
        timeEt.setInputType(InputType.TYPE_CLASS_TEXT);
        timeRow.addView(timeEt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = UiComponents.compactSecondaryButton(this, "选时间", this::pickTime);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(8);
        timeRow.addView(pick, plp);
        card.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout advRow = new LinearLayout(this);
        advRow.setOrientation(LinearLayout.HORIZONTAL);
        advRow.setGravity(Gravity.CENTER_VERTICAL);
        advRow.setPadding(0, dp(10), 0, 0);
        TextView advLabel = UiComponents.text(this, "提前量(ms)", UiComponents.TEXT_LABEL, UiStyle.INK2, false);
        advRow.addView(advLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        advanceEt = numInput("0-100");
        advanceEt.setText("0");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.leftMargin = dp(10);
        advRow.addView(advanceEt, alp);
        card.addView(advRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(8), 0, 0);
        TextView autoLabel = new TextView(this);
        autoLabel.setText("执行前自动上线（离线账号自动抢登）");
        autoLabel.setTextSize(13f);
        autoLabel.setTextColor(UiStyle.INK);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoSw = new UiStyle.Switch(this, false, null);
        autoRow.addView(autoSw, new LinearLayout.LayoutParams(dp(46), dp(44)));
        card.addView(autoRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 模式相关的显隐：定时卡与提交按钮文案。 */
    private void renderModeSections() {
        scheduleCard.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        submitBtn.setText(mode == 1 ? "创建定时迁城" : "立即迁城");
    }

    /** 协议驱动的显隐：坐标区（needs_point）/ 王国输入（needs_kid）。 */
    private void renderProtoSections() {
        boolean point = selProto != null && selProto.needsPoint;
        boolean kid = selProto != null && selProto.needsKid;
        coordSection.setVisibility(point ? View.VISIBLE : View.GONE);
        kidCard.setVisibility(kid ? View.VISIBLE : View.GONE);
        if (protoValue != null) protoValue.setText(protoLabelText());
    }

    private String protoLabel() {
        return selProto == null ? "" : selProto.label;
    }

    /** dropdownRow 是 SpannableString 构造，直接 setText 会丢样式 → 重建整行。 */
    private String protoLabelText() {
        return protoLabel() + " ▾";
    }

    // ================= 协议选择 =================

    private void showProtoPicker() {
        String[] labels = new String[protocols.size()];
        int checked = 0;
        for (int i = 0; i < protocols.size(); i++) {
            labels[i] = protocols.get(i).label;
            if (selProto != null && protocols.get(i).proto.equals(selProto.proto)) checked = i;
        }
        final int[] pick = {checked};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("请选择迁城类型")
                .setSingleChoiceItems(labels, checked, (d, which) -> pick[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    selProto = protocols.get(pick[0]);
                    renderProtoSections();
                    renderCoords();
                })
                .show();
    }

    // ================= 账号 =================

    /** 从账号池缓存（主源，含在线状态）→ 本地账号库（兜底，在线未知）加载账号列表。 */
    private void loadAccounts() {
        Set<String> selectedBefore = new LinkedHashSet<>();
        for (Acc a : accs) {
            if (a.selected) selectedBefore.add(a.fpid);
        }
        accs.clear();
        JSONObject pool = VaultSync.cachedAccountPool(this);
        JSONArray arr = pool == null ? null : pool.optJSONArray("accounts");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String fpid = o.optString("fpid", "");
                if (fpid.isEmpty()) continue;
                String name = o.optString("label", "");
                boolean online = false;
                JSONArray roles = o.optJSONArray("roles");
                if (roles != null) {
                    for (int j = 0; j < roles.length(); j++) {
                        JSONObject r = roles.optJSONObject(j);
                        if (r == null) continue;
                        if (AccountPoolCache.isRoleOnline(r)) online = true;
                        if (name.isEmpty()) name = r.optString("name", "");
                    }
                }
                if (name.isEmpty()) name = fpid;
                accs.add(new Acc(fpid, name, online));
            }
        }
        if (accs.isEmpty()) {
            try {
                for (AccountStore.Account a : new AccountStore(this).all()) {
                    if (a == null || a.fpid == null || a.fpid.isEmpty()) continue;
                    String name = a.label == null || a.label.isEmpty() ? a.fpid : a.label;
                    accs.add(new Acc(a.fpid, name, null));
                }
            } catch (Throwable ignored) {
            }
        }
        for (Acc a : accs) {
            a.selected = selectedBefore.contains(a.fpid);
        }
        renderAccs();
        renderCoords();
    }

    /** 刷新账号池缓存（回调后按最新池重渲染；本地兜底时保底拉一次状态）。 */
    private void refreshPool() {
        VaultSync.fetchAccountPool(this, (code, msg, data) -> runOnUiThread(this::loadAccounts));
    }

    private void renderAccs() {
        if (accBox == null) return;
        accBox.removeAllViews();
        if (accs.isEmpty()) {
            accBox.addView(emptyText("暂无号库账号（先在账号页导入账号）"));
            return;
        }
        for (final Acc a : accs) {
            LinearLayout row = UiStyle.outlineCard(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(4), dp(12), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            row.setLayoutParams(lp);
            if (a.selected) {
                row.setBackground(UiStyle.roundRectStroke(this, UiStyle.BRAND_50, 16, UiStyle.BRAND_200));
            }
            // 选中标记（对勾/空位）
            row.addView(a.selected
                            ? new UiStyle.Icon(this, UiStyle.Icon.CHECK, UiStyle.BRAND_TEXT, 18)
                            : new UiStyle.Icon(this, UiStyle.Icon.CHECK, UiStyle.LINE_STRONG, 18),
                    new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), dp(8), dp(8), dp(8));
            TextView name = UiComponents.text(this, a.name, UiComponents.TEXT_BODY, UiStyle.INK, true);
            name.setSingleLine(true);
            col.addView(name);
            TextView fid = UiComponents.caption(this, "fid " + a.fpid, UiStyle.INK3);
            fid.setPadding(0, dp(2), 0, 0);
            col.addView(fid);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (a.online != null) {
                row.addView(UiStyle.badge(this, a.online ? "ok" : "idle", a.online ? "在线" : "离线"));
            } else {
                row.addView(UiStyle.badge(this, "idle", "未知"));
            }
            UiStyle.press(row);
            row.setOnClickListener(v -> {
                if (mode == 1) {
                    // 定时 = 单账号：单选语义
                    boolean turnOn = !a.selected;
                    for (Acc x : accs) x.selected = false;
                    a.selected = turnOn;
                } else {
                    a.selected = !a.selected;
                }
                renderAccs();
                renderCoords();
            });
            accBox.addView(row);
        }
    }

    /** 坐标区：每个选中账号一行 [名称 | x | y]（needs_point 协议）。 */
    private void renderCoords() {
        if (coordBox == null) return;
        // 存回草稿（重渲染不丢已输入值）
        for (Map.Entry<String, EditText[]> e : coordEdits.entrySet()) {
            String[] draft = coordDraft.get(e.getKey());
            if (draft == null) {
                draft = new String[]{"", ""};
                coordDraft.put(e.getKey(), draft);
            }
            draft[0] = text(e.getValue()[0]);
            draft[1] = text(e.getValue()[1]);
        }
        coordEdits.clear();
        coordBox.removeAllViews();
        if (selProto == null || !selProto.needsPoint) return;
        List<Acc> sel = selected();
        if (sel.isEmpty()) {
            coordBox.addView(emptyText("先选择迁城账号，再为每个账号填目标坐标"));
            return;
        }
        for (Acc a : sel) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = UiComponents.text(this, a.name, UiComponents.TEXT_LABEL, UiStyle.INK, true);
            name.setSingleLine(true);
            name.setEms(6);
            row.addView(name, new LinearLayout.LayoutParams(dp(96),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText x = numInput("x");
            EditText y = numInput("y");
            String[] draft = coordDraft.get(a.fpid);
            if (draft != null) {
                x.setText(draft[0]);
                y.setText(draft[1]);
            }
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            elp.rightMargin = dp(6);
            row.addView(x, elp);
            row.addView(y, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            coordEdits.put(a.fpid, new EditText[]{x, y});
            coordBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private List<Acc> selected() {
        List<Acc> out = new ArrayList<>();
        for (Acc a : accs) {
            if (a.selected) out.add(a);
        }
        return out;
    }

    // ================= 服务端可用性 =================

    /** protocols 探测：成功即放行提交；失败显示顶部提示条（可浏览不可提交）。 */
    private void detectServer() {
        VaultSync.movecityProtocols(this, (code, msg, data) -> runOnUiThread(() -> {
            if (code == 0 && data != null) {
                JSONArray arr = data.optJSONArray("protocols");
                if (arr != null && arr.length() > 0) {
                    List<Proto> parsed = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String proto = o.optString("proto", "");
                        String label = o.optString("label", "");
                        if (proto.isEmpty() || label.isEmpty()) continue;
                        parsed.add(new Proto(label, proto,
                                o.optBoolean("needs_point", false),
                                o.optBoolean("needs_kid", false)));
                    }
                    if (!parsed.isEmpty()) {
                        String keep = selProto == null ? "" : selProto.proto;
                        protocols.clear();
                        protocols.addAll(parsed);
                        selProto = parsed.get(0);
                        for (Proto p : parsed) {
                            if (p.proto.equals(keep)) selProto = p;
                        }
                        renderProtoSections();
                        renderCoords();
                    }
                }
                serverReady = true;
                hideWarn();
                loadJobs();
            } else {
                showWarn("服务端未升级，迁城暂不可用（/v1/vault/movecity 不可达："
                        + describeFail(code, msg) + "）");
                loadJobs();
            }
        }));
    }

    private void showWarn(String text) {
        warnBanner.setText("⚠ " + text);
        warnBanner.setVisibility(View.VISIBLE);
        if (submitBtn != null) submitBtn.setEnabled(false);
    }

    private void hideWarn() {
        warnBanner.setVisibility(View.GONE);
        if (submitBtn != null) submitBtn.setEnabled(true);
    }

    /** 失败原因归一（code=3002 / HTTP 404 → 未升级；其余网络/业务错）。 */
    /**
     * 失败文案映射。
     *
     * <p>审阅修复：服务端把**全部业务校验错误**统一为 code=3002（与「未启用迁城」同码），
     * 因此必须**优先用服务端中文文案**，否则「请选择迁城账号」之类的校验提示会被误报成
     * 「服务端未启用迁城功能」。
     */
    private static String describeFail(int code, String msg) {
        String m = msg == null ? "" : msg.trim();
        if (!m.isEmpty() && !"null".equals(m)) {
            if (code == -1 && (m.contains("404") || m.contains("405"))) {
                return "服务端未升级，缺少该接口";
            }
            return m;
        }
        if (code == -1) return "网络异常，请稍后重试";
        if (code == AccountPoolCache.CODE_NOT_FOUND) return "服务端未启用迁城功能";
        return "code=" + code;
    }

    // ================= 提交 =================

    private void onSubmit() {
        if (busy) return;
        if (!serverReady) {
            toast("服务端未升级，迁城暂不可用");
            return;
        }
        // ---- 校验（文案与网页版逐字对齐；顺序：账号 → 类型 → 坐标 → 王国 → 时间 → 离线） ----
        List<Acc> sel = selected();
        if (sel.isEmpty()) {
            toast("请选择迁城账号");
            return;
        }
        if (selProto == null) {
            toast("请选择迁城类型");
            return;
        }
        int kid = 0;
        if (selProto.needsKid) {
            kid = parseInt(text(kidEt));
            if (kid <= 0) {
                toast("请填写目标王国");
                return;
            }
        }
        // 组装账号数组（坐标/校验一并完成）
        JSONArray accounts = new JSONArray();
        Set<String> points = new LinkedHashSet<>();
        for (Acc a : sel) {
            int x = 0, y = 0;
            if (selProto.needsPoint) {
                EditText[] eds = coordEdits.get(a.fpid);
                String xs = eds == null ? "" : text(eds[0]);
                String ys = eds == null ? "" : text(eds[1]);
                if (xs.isEmpty() || ys.isEmpty()) {
                    toast("请填写迁城坐标");
                    return;
                }
                x = parseInt(xs);
                y = parseInt(ys);
                if (x <= 0 || y <= 0) {
                    toast("请填写迁城坐标");
                    return;
                }
                if (!points.add(x + "," + y)) {
                    toast("多个账号不能迁移到同一坐标: " + x + "," + y);
                    return;
                }
            }
            JSONObject o = new JSONObject();
            try {
                o.put("fpid", a.fpid);
                o.put("name", a.name);
                o.put("x", x);
                o.put("y", y);
                o.put("kid", kid);
                o.put("selected", true);
            } catch (Throwable ignored) {
            }
            accounts.put(o);
        }
        boolean autoLogin = autoSw != null && autoSw.isOn();
        String executeAt = "";
        int advanceMillis = 0;
        if (mode == 1) {
            executeAt = text(timeEt);
            long at = parseTime(executeAt);
            if (at <= 0) {
                toast("请选择定时时间");
                return;
            }
            if (at <= System.currentTimeMillis()) {
                toast("定时时间必须晚于当前时间");
                return;
            }
            String advRaw = text(advanceEt);
            advanceMillis = advRaw.isEmpty() ? 0 : Math.max(0, Math.min(100, parseInt(advRaw)));
        } else {
            // 立即迁城：服务端与原版一致，**不执行**「执行前自动上线」，离线账号会被跳过。
            // 审阅修复：原先用 autoLogin 开关放行会误导用户（开关开了也没用），这里统一拦下并说明出路。
            for (Acc a : sel) {
                if (Boolean.FALSE.equals(a.online)) {
                    toast("账号「" + a.name + "」当前离线：立即迁城不自动上线，请先上线或改用定时迁城");
                    return;
                }
            }
        }

        // ---- 二次确认（迁城消耗真实道具） ----
        confirmSubmit(sel, accounts, autoLogin, executeAt, advanceMillis);
    }

    private void confirmSubmit(List<Acc> sel, JSONArray accounts, boolean autoLogin,
                               String executeAt, int advanceMillis) {
        StringBuilder sb = new StringBuilder();
        if (sel.size() == 1) {
            Acc a = sel.get(0);
            sb.append("账号：").append(a.name).append("（fid ").append(a.fpid).append("）\n");
            if (selProto.needsPoint) {
                JSONObject o = accounts.optJSONObject(0);
                sb.append("目标：(").append(o.optInt("x")).append(",").append(o.optInt("y")).append(")\n");
            }
            if (selProto.needsKid) sb.append("王国：").append(parseInt(text(kidEt))).append("\n");
        } else {
            sb.append("账号（").append(sel.size()).append(" 个）：\n");
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.optJSONObject(i);
                sb.append(" · ").append(o.optString("name")).append("（fid ")
                        .append(o.optString("fpid")).append("）");
                if (selProto.needsPoint) {
                    sb.append(" → (").append(o.optInt("x")).append(",").append(o.optInt("y")).append(")");
                }
                sb.append('\n');
            }
        }
        sb.append("协议：").append(selProto.label).append('\n');
        if (mode == 1) {
            sb.append("执行时间：").append(executeAt).append('\n');
            sb.append("提前量：").append(advanceMillis).append("ms\n");
        }
        sb.append("执行前自动上线：").append(autoLogin ? "开" : "关").append('\n');
        sb.append('\n').append("迁城将消耗对应迁城道具，确认执行？");

        final boolean schedule = mode == 1;
        UiStyle.showConfirmDialog(this, schedule ? "创建定时迁城" : "立即迁城", sb.toString(),
                schedule ? "创建定时迁城" : "确认执行", UiStyle.BRAND_TEXT,
                () -> doSubmit(accounts, autoLogin, executeAt, advanceMillis));
    }

    private void doSubmit(JSONArray accounts, boolean autoLogin, String executeAt, int advanceMillis) {
        if (busy) return;
        busy = true;
        submitBtn.setEnabled(false);
        submitBtn.setText(mode == 1 ? "创建中…" : "执行中…");
        VaultSync.Callback done = (code, msg, data) -> runOnUiThread(() -> {
            busy = false;
            submitBtn.setEnabled(true);
            renderModeSections();
            if (code == 0) {
                toast(mode == 1 ? "定时迁城已创建" : "迁城任务已执行");
                loadJobs();
            } else {
                toast("提交失败：" + describeFail(code, msg));
            }
        });
        if (mode == 1) {
            VaultSync.movecitySchedule(this, selProto.proto, accounts, executeAt,
                    advanceMillis, autoLogin, done);
        } else {
            VaultSync.movecityExecute(this, selProto.proto, accounts, autoLogin, done);
        }
    }

    // ================= 任务列表 =================

    private void loadJobs() {
        VaultSync.movecityJobs(this, (code, msg, data) -> runOnUiThread(() -> {
            if (code != 0 || data == null) {
                jobsHint.setText("任务列表获取失败：" + describeFail(code, msg));
                return;
            }
            jobsHint.setText("");
            renderJobList(scheduledBox, data.optJSONArray("scheduled"));
            renderJobList(finishedBox, data.optJSONArray("finished"));
        }));
    }

    private void renderJobList(LinearLayout box, JSONArray jobs) {
        box.removeAllViews();
        if (jobs == null || jobs.length() == 0) {
            box.addView(emptyText("暂无任务"));
            return;
        }
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null) continue;
            box.addView(jobRow(job));
        }
    }

    /** 任务条目：账号/协议/坐标 + 状态徽标 + 成功计数；未开始可取消；点击看详情。 */
    private View jobRow(final JSONObject job) {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        String status = job.optString("status", "");
        JSONObject protocol = job.optJSONObject("protocol");
        String protoLabel = protocol == null ? "" : protocol.optString("label", "");
        JSONArray details = job.optJSONArray("account_details");
        StringBuilder names = new StringBuilder();
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONObject d = details.optJSONObject(i);
                if (d == null) continue;
                if (names.length() > 0) names.append("、");
                names.append(d.optString("account_name", d.optString("fpid", "")));
            }
        }
        int total = job.optInt("total", 0);
        int success = job.optInt("success_count", 0);
        String executeAt = job.optString("execute_at", "");
        String type = job.optString("type", "");

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiComponents.text(this, names.length() == 0 ? "（账号已移除）" : names.toString(),
                UiComponents.TEXT_BODY, UiStyle.INK, true);
        name.setSingleLine(true);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(UiStyle.badge(this, badgeType(status), status));
        card.addView(head);

        StringBuilder line2 = new StringBuilder();
        line2.append(protoLabel.isEmpty() ? "" : protoLabel);
        if (details != null && details.length() > 0) {
            JSONObject d0 = details.optJSONObject(0);
            if (d0 != null && (d0.optInt("x", 0) > 0 || d0.optInt("y", 0) > 0)) {
                line2.append("  (").append(d0.optInt("x", 0)).append(",")
                        .append(d0.optInt("y", 0)).append(")");
            }
            if (d0 != null && d0.optInt("kid", 0) > 0) {
                line2.append("  王国 ").append(d0.optInt("kid", 0));
            }
        }
        if (!executeAt.isEmpty()) {
            line2.append(line2.length() == 0 ? "" : "  ").append(executeAt);
        }
        if (line2.length() > 0) {
            TextView l2 = UiComponents.caption(this, line2.toString(), UiStyle.INK2);
            l2.setPadding(0, dp(4), 0, 0);
            card.addView(l2);
        }
        // 计数/错误行（有结果或错误才显示）
        if (total > 0 || !job.optString("error", "").isEmpty()) {
            StringBuilder line3 = new StringBuilder();
            line3.append(type).append(" · 成功 ").append(success).append("/").append(
                    total > 0 ? total : (details == null ? 0 : details.length()));
            String error = job.optString("error", "");
            if (!error.isEmpty()) line3.append(" · ").append(error);
            TextView l3 = UiComponents.caption(this, line3.toString(),
                    error.isEmpty() ? UiStyle.INK3 : UiStyle.ERR_INK);
            l3.setPadding(0, dp(4), 0, 0);
            card.addView(l3);
        }

        UiStyle.press(card);
        card.setOnClickListener(v -> showJobDetail(job.optString("job_id", "")));
        // 未开始的任务可取消（cancelable=true；服务端校验「已开始不能取消」并回中文错误）
        if (job.optBoolean("cancelable", false)) {
            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setGravity(Gravity.END);
            actRow.setPadding(0, dp(8), 0, 0);
            TextView cancel = UiComponents.compactSecondaryButton(this, "取消任务", () ->
                    confirmCancel(job.optString("job_id", ""), names.toString()));
            actRow.addView(cancel);
            card.addView(actRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    /** 任务状态 → 徽章色（ok/run/wait/warn/err/idle）。 */
    private static String badgeType(String status) {
        if ("已完成".equals(status)) return "ok";
        if ("执行中".equals(status)) return "run";
        if ("准备中".equals(status) || "等待执行".equals(status)) return "wait";
        if ("等待恢复".equals(status)) return "warn";
        if ("失败".equals(status)) return "err";
        return "idle";   // 已创建 / 已取消 / 未知
    }

    private void confirmCancel(String jobId, String name) {
        UiStyle.showConfirmDialog(this, "取消定时任务",
                "确定取消「" + (name.isEmpty() ? jobId : name) + "」的定时迁城任务？",
                "确认取消", UiStyle.ERR_INK, () -> doCancel(jobId));
    }

    private void doCancel(String jobId) {
        VaultSync.movecityCancel(this, jobId, (code, msg, data) -> runOnUiThread(() -> {
            if (code == 0) {
                toast("定时任务已取消");
                loadJobs();
            } else {
                toast("取消失败：" + describeFail(code, msg));
            }
        }));
    }

    // ================= 任务详情 =================

    private void showJobDetail(String jobId) {
        if (jobId.isEmpty()) return;
        VaultSync.movecityJob(this, jobId, (code, msg, data) -> runOnUiThread(() -> {
            if (code != 0 || data == null) {
                toast("详情获取失败：" + describeFail(code, msg));
                return;
            }
            renderJobDetail(data);
        }));
    }

    private void renderJobDetail(JSONObject job) {
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setTextColor(UiStyle.INK2);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(18), dp(12), dp(18), dp(6));
        tv.setText(jobDetailText(job));
        sc.addView(tv);
        AlertDialog.Builder b = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("迁城任务详情")
                .setView(sc)
                .setPositiveButton("关闭", null);
        // 增量（2026-11-07）：未开始任务可把账号从任务中移除（原版 remove-account 的 1:1 对应）。
        // 本项目定时任务限单账号，移除后服务端会自动取消该任务，确认文案已如实说明。
        final String jobId = job.optString("job_id", "");
        JSONArray details = job.optJSONArray("account_details");
        if (job.optBoolean("cancelable", false) && details != null && details.length() > 0) {
            JSONObject d0 = details.optJSONObject(0);
            final String fpid = d0 == null ? "" : d0.optString("fpid", "");
            final String accName = d0 == null ? "" : d0.optString("account_name", "");
            if (!fpid.isEmpty() && !jobId.isEmpty()) {
                b.setNeutralButton("移除该账号", (dlg, w) -> confirmRemoveAccount(jobId, fpid, accName));
            }
        }
        b.show();
    }

    /** 从定时任务移除账号（服务端 remove-account）；单账号任务移除后自动取消。 */
    private void confirmRemoveAccount(String jobId, String fpid, String name) {
        UiStyle.showConfirmDialog(this, "移除账号",
                "确定把「" + (name.isEmpty() ? fpid : name) + "」从该定时迁城任务中移除？\n"
                        + "（定时任务为单账号，移除后该任务会自动取消）",
                "确认移除", UiStyle.ERR_INK, () -> VaultSync.movecityRemoveAccount(this, jobId, fpid,
                        (code, msg, data) -> runOnUiThread(() -> {
                            if (code == 0) {
                                toast("账号已从定时任务中移除");
                                loadJobs();
                            } else {
                                toast("移除失败：" + describeFail(code, msg));
                            }
                        })));
    }

    /** 详情文本：任务元数据 + 逐账号结果 + 逐次尝试（attempt_details）。 */
    static String jobDetailText(JSONObject job) {
        StringBuilder sb = new StringBuilder();
        JSONObject protocol = job.optJSONObject("protocol");
        sb.append("任务 ").append(job.optString("job_id", "")).append('\n');
        sb.append("类型：").append(job.optString("type", "")).append('\n');
        sb.append("状态：").append(job.optString("status", "")).append('\n');
        sb.append("协议：").append(protocol == null ? "" : protocol.optString("label", ""))
                .append("（").append(protocol == null ? "" : protocol.optString("proto", ""))
                .append("）\n");
        String executeAt = job.optString("execute_at", "");
        if (!executeAt.isEmpty()) {
            sb.append("执行时间：").append(executeAt)
                    .append("（提前 ").append(job.optLong("advance_millis", 0L)).append("ms）\n");
        }
        sb.append("执行前自动上线：").append(job.optBoolean("auto_login", false) ? "开" : "关").append('\n');
        String error = job.optString("error", "");
        if (!error.isEmpty()) sb.append("错误：").append(error).append('\n');

        JSONArray results = job.optJSONArray("results");
        if (results != null && results.length() > 0) {
            sb.append("\n———— 结果（成功 ").append(job.optInt("success_count", 0))
                    .append('/').append(job.optInt("total", results.length())).append("）————\n");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                sb.append('\n').append('[').append(r.optString("account_name",
                                r.optString("fpid", ""))).append(']')
                        .append(r.optBoolean("success", false) ? " ✅ 成功" : " ❌ 失败");
                if (r.optInt("x", 0) > 0 || r.optInt("y", 0) > 0) {
                    sb.append("  (").append(r.optInt("x", 0)).append(",")
                            .append(r.optInt("y", 0)).append(")");
                }
                sb.append("  尝试 ").append(r.optInt("attempts", 0)).append(" 次\n");
                String message = r.optString("message", "");
                if (!message.isEmpty()) sb.append("  ").append(message).append('\n');
                JSONArray attempts = r.optJSONArray("attempt_details");
                if (attempts != null) {
                    for (int j = 0; j < attempts.length(); j++) {
                        JSONObject at = attempts.optJSONObject(j);
                        if (at == null) continue;
                        JSONObject ed = at.optJSONObject("error_detail");
                        sb.append("  #").append(at.optInt("attempt", j + 1))
                                .append(' ').append(at.optString("attempt_at", ""))
                                .append(at.optBoolean("success", false) ? " 成功" : " 失败");
                        if (ed != null && !ed.optString("description", "").isEmpty()) {
                            sb.append(" · ").append(ed.optString("description", ""));
                            if (ed.opt("code") != null && !"0".equals(String.valueOf(ed.opt("code")))) {
                                sb.append("（code=").append(ed.opt("code")).append('）');
                            }
                        }
                        sb.append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    // ================= 时间选择（日期 + 时间两步；秒固定 00） =================

    private void pickTime() {
        final Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (DatePicker dp, int year, int month, int day) ->
                new TimePickerDialog(MoveCityActivity.this, (TimePicker tp, int hour, int minute) -> {
                    timeEt.setText(String.format(Locale.CHINA, "%04d-%02d-%02d %02d:%02d:00",
                            year, month + 1, day, hour, minute));
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * 解析执行时间（严格格式；非法返回 0）。
     *
     * <p>时区固定 GMT+8（北京时间）：服务端 {@code MoveCityEngine} 用
     * {@code Beijing.ZONE_ID} 解析 executeAt，两端必须同口径；
     * 否则设备时区非中国时会把「未来时间」判成过去（或反之）导致服务端拒绝/提前执行。
     */
    static long parseTime(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(TIME_FORMAT, Locale.CHINA);
            fmt.setLenient(false);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));
            return fmt.parse(s.trim()).getTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ================= 小工具 =================

    private TextView emptyText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12.5f);
        tv.setTextColor(UiStyle.INK3);
        tv.setPadding(dp(6), dp(8), dp(6), dp(8));
        return tv;
    }

    /** 数字输入框：白底描边（对齐 UiStyle.inputBox 视觉）+ 数字键盘。 */
    private EditText numInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13f);
        et.setTextColor(UiStyle.INK);
        et.setHintTextColor(UiStyle.INK3);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setBackground(UiStyle.roundRectStroke(this, UiStyle.WHITE, 12, UiStyle.LINE_STRONG));
        et.setMinHeight(dp(44));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        return et;
    }

    private static String text(EditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
