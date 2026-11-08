package com.carlos.vault.daily;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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

import com.carlos.vault.AccountPoolCache;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.FormationQuickSetup;
import com.carlos.vault.daily.formation.FormationRules;
import com.carlos.vault.daily.formation.PresetFormations;
import com.carlos.vault.daily.formation.ui.FormationBoardView;
import com.carlos.vault.daily.formation.ui.FormationManagerDialogs;
import com.carlos.vault.daily.formation.ui.FormationRatioDialog;
import com.carlos.vault.daily.formation.ui.HeroPickerDialog;
import com.carlos.vault.daily.formation.ui.ManagerUi;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编队管理页（1:1 网页版 {@code 编队管理}，契约 §4.3）。
 *
 * <p>4 主 tab（自动编队 / 游戏编队 / 预设编队 / 我的编队；默认「游戏编队」）+
 * 我的子 tab（全部/采集/巨熊/自定义，2026-11-06 由「全部/预设/自定义/巨熊编队」调整：
 * 采集=预设采集肉/木/煤/铁，巨熊=预设打熊队+熊上车1-6，预设分类由主 tab「预设编队」承载，
 * 避免同一页出现两个「预设编队」入口），
 * 复用 {@link FormationBoardView} 编队板；游戏编队只读、编辑走副本；我的编队提供新建/编辑/
 * 复制/删除（编辑器内）。保存后经 {@link VaultSync#onAccountsChanged} 触发既有同步链路；
 * 容量未同步时按规格 §1.1 每 1500ms 轮询、最多 8 次；离线只读。
 * <p>已删除网页版「内置编队」这一层：只剩 19 套预设编队（{@link PresetFormations}）与用户自定义编队。
 */
public class FormationManagerActivity extends Activity {

    public static final String EXTRA_FPID = "formation_fpid";
    public static final String EXTRA_SID = "formation_sid";
    public static final String EXTRA_UID = "formation_uid";
    public static final String EXTRA_FOCUS_FN = "formation_focus_fn";

    private static final String TAB_AUTO = "AUTO";
    private static final String TAB_GAME = "GAME";
    private static final String TAB_PRESET = "PRESET";
    private static final String TAB_MINE = "MINE";
    /** 入口直达「新建编队」编辑器（功能页/选择器的 ＋新建 入口）。 */
    public static final String FOCUS_NEW = "__new__";

    private static final String MINE_ALL = "ALL";
    /** 我的编队子筛选：采集（预设采集肉/木/煤/铁，{@link PresetFormations#isGatherTeam(long)}）。 */
    private static final String MINE_GATHER = "GATHER";
    private static final String MINE_CUSTOM = "CUSTOM";
    /** 我的编队子筛选：巨熊（预设打熊队 + 熊上车1-6；兼容旧「巨熊·」生成队）。 */
    private static final String MINE_BEAR = "BEAR";

    private static final int POLL_MAX = 8;
    private static final long POLL_INTERVAL = 1500L;

    // ===== 身份 =====
    private String fpid = "";
    private long sid;
    private long uid;
    private String focusFnId = "";

    // ===== 数据 =====
    private FormationCatalogStore catalog = FormationCatalogStore.empty();
    private List<FormationModel> lib = new ArrayList<FormationModel>();
    private int[] autoRatios = new int[]{50, 20, 30};

    // ===== 状态 =====
    /** 默认进入「游戏编队」：用户要求先读取游戏已有编队供选择（契约 §4.1）。 */
    private String tab = TAB_GAME;
    private String mineFilter = MINE_ALL;
    private long selectedGameId;
    private long selectedPresetId;
    private long selectedMineId;
    private boolean loading;
    private boolean saving;
    private boolean destroyed;

    // 编辑器
    private boolean editorOpen;
    private FormationModel draft;
    private String editorBaseline;
    private boolean editorGameCopy;
    /** 编辑的是 19 套预设之一：保存时另存为新编队，预设本体保持不变。 */
    private boolean editorPresetCopy;

    // 轮询
    private int pollRound;
    private boolean pollPending;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout rootFrame;

    // ==================== 对外入口（契约 §4.3） ====================

    /** 打开编队管理页。 */
    public static void launch(Activity from, String fpid, long sid, String uid, String focusFnId) {
        if (from == null) return;
        // 2026-11-06 决策6：统一入口门控（T2 日常脚本；防外部 Intent/深链越权直达）。
        if (!com.carlos.vault.FeatureGate.visible(from,
                com.carlos.vault.FeatureGate.Feature.FORMATION_MANAGE)) {
            UiStyle.toast(from, com.carlos.vault.FeatureGate.reasonText(
                    com.carlos.vault.FeatureGate.Feature.FORMATION_MANAGE));
            return;
        }
        try {
            Intent i = new Intent(from, FormationManagerActivity.class);
            i.putExtra(EXTRA_FPID, fpid == null ? "" : fpid);
            i.putExtra(EXTRA_SID, sid);
            i.putExtra(EXTRA_UID, uid == null ? "" : uid);
            i.putExtra(EXTRA_FOCUS_FN, focusFnId == null ? "" : focusFnId);
            from.startActivity(i);
        } catch (Throwable t) {
            UiStyle.toast(from, "打开编队管理失败：" + t.getMessage());
        }
    }

    /** 打开编队管理页并<b>直接进入新建编队编辑器</b>（功能页/队列选择器的「＋新建」入口）。 */
    public static void launchNew(Activity from, String fpid, long sid, String uid, String focusFnId) {
        launch(from, fpid, sid, uid, FOCUS_NEW);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(0xFF000000);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        fpid = safe(getIntent().getStringExtra(EXTRA_FPID));
        sid = getIntent().getLongExtra(EXTRA_SID, 0L);
        uid = parseLong(safe(getIntent().getStringExtra(EXTRA_UID)));
        focusFnId = safe(getIntent().getStringExtra(EXTRA_FOCUS_FN));
        if (fpid.isEmpty()) {
            UiStyle.toast(this, "缺少账号信息，无法打开编队管理");
            finish();
            return;
        }
        if (sid <= 0) sid = RoleStore.activeRole(this, fpid);
        if (uid <= 0) uid = RoleStore.activeRoleUid(this, fpid);
        if (sid <= 0) {
            UiStyle.toast(this, "请先选择角色区服后再管理编队");
            finish();
            return;
        }

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(0x99080C17);
        setContentView(rootFrame);

        // 底部入口 focusFnId=bear → 直达我的编队→巨熊编队（T5 约定）
        if ("bear".equals(focusFnId)) {
            tab = TAB_MINE;
            mineFilter = MINE_BEAR;
        }
        ensureData();
        // 账号池缓存异步刷新（只读；失败不影响本页），避免 online() 用旧缓存误判离线
        try {
            VaultSync.fetchAccountPool(this, null);
        } catch (Throwable ignored) {
        }
        render();
        // 直达「新建编队」：入口方（功能页编队卡 / 队列选择器 / 采集设置）期望立刻看到编辑器，
        // 而不是先让用户在管理页里找按钮（用户反馈：只看到“选择编队”，找不到新增入口）。
        if (FOCUS_NEW.equals(focusFnId)) {
            if (!TAB_MINE.equals(tab)) {
                tab = TAB_MINE;
                mineFilter = MINE_ALL;
                render();
            }
            ui.postDelayed(() -> {
                if (!destroyed && !editorOpen) openEditor(null);
            }, 60L);
        }
        if (!catalog.configured()) startCapacityPoll();
        fetchState(false);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (editorOpen) {
            closeEditor();
            return;
        }
        super.onBackPressed();
    }

    // ==================== 数据装载 ====================

    private void ensureData() {
        try {
            FormationStore.ensurePresets(this, fpid, sid, uidText());   // 按角色目录懒建 19 套预设编队
        } catch (Throwable ignored) {
        }
        reload();
    }

    private void reload() {
        // 改完自动补齐预设编队（幂等；目录就绪时才建，只在需要时落盘）
        try {
            FormationStore.ensurePresets(this, fpid, sid, uidText());
        } catch (Throwable ignored) {
        }
        try {
            lib = FormationStore.formations(this, fpid, sid);
        } catch (Throwable t) {
            lib = new ArrayList<FormationModel>();
        }
        try {
            // T7：uid 优先读 v2 目录键，缺失回退旧键（多角色同区服不串英雄目录）
            catalog = FormationStore.catalogOf(this, fpid, sid, uidText());
        } catch (Throwable t) {
            catalog = FormationCatalogStore.empty();
        }
        try {
            autoRatios = FormationStore.automaticSettings(this, fpid, sid, uidText());   // T7
        } catch (Throwable t) {
            autoRatios = new int[]{50, 20, 30};
        }
        FormationBoardView.installCatalog(catalog);
        // 预取头像（异步，不阻塞主线程）
        try {
            List<Long> ids = new ArrayList<Long>();
            for (FormationStore.Hero h : catalog.heroOptions()) {
                if (h != null && h.id > 0) ids.add(h.id);
            }
            for (FormationModel f : lib) {
                for (Long h : f.heroIds) {
                    if (h != null && h > 0 && !ids.contains(h)) ids.add(h);
                }
            }
            com.carlos.vault.daily.formation.FormationIcons.warmupAsync(this, ids);
        } catch (Throwable ignored) {
        }
    }

    /** 拉取服务器 state（capacity/catalog/gameFormations/memory 等）。 */
    private void fetchState(final boolean silent) {
        if (!silent) loading = true;
        VaultSync.fetchState(this, (code, message, data) -> ui.post(() -> {
            if (destroyed) return;
            loading = false;
            reload();
            render();
            if (code != 0 && !silent) {
                UiStyle.toast(FormationManagerActivity.this,
                        message == null || message.isEmpty()
                                ? "编队数据加载失败，已保留上次数据" : message);
            }
            if (!catalog.configured() && pollRound < POLL_MAX) startCapacityPoll();
        }));
    }

    /** 容量未同步：1500ms × 8 轮询（规格 §1.1）。 */
    private void startCapacityPoll() {
        if (destroyed || pollPending || pollRound >= POLL_MAX || catalog.configured()) return;
        pollPending = true;
        ui.postDelayed(() -> {
            pollPending = false;
            if (destroyed || catalog.configured() || pollRound >= POLL_MAX) return;
            pollRound++;
            VaultSync.fetchState(this, (code, message, data) -> ui.post(() -> {
                if (destroyed) return;
                reload();
                render();
                if (!catalog.configured() && pollRound < POLL_MAX) startCapacityPoll();
            }));
        }, POLL_INTERVAL);
    }

    private void syncNow() {
        try {
            VaultSync.onAccountsChanged(this);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 在线/能力判定 ====================

    private boolean online() {
        boolean cached = false;
        try {
            JSONObject data = AccountPoolCache.load(this);
            if (data != null && !AccountPoolCache.isUnsupported(this)) {
                Map<String, JSONObject> accs = AccountPoolCache.accountsByFpid(data);
                JSONObject acc = accs == null ? null : accs.get(fpid);
                JSONObject role = acc == null ? null : AccountPoolCache.findRole(acc, sid, uid);
                if (role != null) cached = AccountPoolCache.isRoleOnline(role);
                else cached = catalog.configured();
            } else {
                cached = catalog.configured();
            }
        } catch (Throwable ignored) {
        }
        if (cached) return true;
        // 账号池缓存可能是本页打开前的旧值（生产实测：App 首页显示“游戏在线”，
        // 编队页却因 AccountPoolCache 过期判定离线 → 新建编队被拒「账号离线」）。
        // 目录新鲜（≤3 分钟捕获）说明服务端角色会话在线（只有在线会话才会写入该目录）。
        try {
            if (catalog.configured() && catalog.capturedAt() > 0L
                    && System.currentTimeMillis() - catalog.capturedAt() < 180_000L) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean editableNow() {
        return online() && catalog.configured();
    }

    private List<FormationModel> gameFormations() {
        try {
            List<FormationModel> g = catalog.gameFormations();
            return g == null ? new ArrayList<FormationModel>() : g;
        } catch (Throwable t) {
            return new ArrayList<FormationModel>();
        }
    }

    private List<FormationModel> mineFormations() {
        List<FormationModel> out = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (f == null || f.isGame()) continue;
            if (MINE_GATHER.equals(mineFilter)) {
                if (!isGatherTeam(f)) continue;
            } else if (MINE_CUSTOM.equals(mineFilter)) {
                // 自定义 = 非预设、非旧「巨熊·」生成队（预设/巨熊分别有独立分类）
                if (isPreset(f) || f.isBear()) continue;
            } else if (MINE_BEAR.equals(mineFilter)) {
                if (!isBearTeam(f)) continue;
            }
            out.add(f);
        }
        return out;
    }

    /** 是否采集队（预设采集肉/木/煤/铁）。 */
    private static boolean isGatherTeam(FormationModel f) {
        if (f == null) return false;
        try {
            return PresetFormations.isGatherTeam(f.id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否巨熊编队模板队（预设打熊队+熊上车1-6；旧「巨熊·」生成队保留归类）。 */
    private static boolean isBearTeam(FormationModel f) {
        if (f == null) return false;
        try {
            return PresetFormations.isBearTemplateTeam(f.id) || f.isBear();
        } catch (Throwable t) {
            return f.name != null && f.name.startsWith("巨熊·");
        }
    }

    /** 是否预设编队（19 套内置预设，由 T1 PresetFormations 判定，目录就绪后 ensure 落库）。 */
    private static boolean isPreset(FormationModel f) {
        if (f == null) return false;
        try {
            return PresetFormations.isPreset(f.id);
        } catch (Throwable t) {
            return false;
        }
    }

    private List<FormationModel> presetFormations() {
        List<FormationModel> out = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (f == null || f.isGame()) continue;
            if (isPreset(f)) out.add(f);
        }
        return out;
    }

    private FormationModel findLib(long id) {
        for (FormationModel f : lib) {
            if (f != null && f.id == id) return f;
        }
        return null;
    }

    private FormationModel selectedGameFormation() {
        List<FormationModel> games = gameFormations();
        for (FormationModel f : games) {
            if (f != null && f.id == selectedGameId) return f;
        }
        if (!games.isEmpty()) {
            selectedGameId = games.get(0).id;
            return games.get(0);
        }
        selectedGameId = 0;
        return null;
    }

    private FormationModel selectedMineFormation() {
        List<FormationModel> mine = mineFormations();
        for (FormationModel f : mine) {
            if (f != null && f.id == selectedMineId) return f;
        }
        if (!mine.isEmpty()) {
            selectedMineId = mine.get(0).id;
            return mine.get(0);
        }
        selectedMineId = 0;
        return null;
    }

    private FormationModel selectedPresetFormation() {
        List<FormationModel> presets = presetFormations();
        for (FormationModel f : presets) {
            if (f != null && f.id == selectedPresetId) return f;
        }
        if (!presets.isEmpty()) {
            selectedPresetId = presets.get(0).id;
            return presets.get(0);
        }
        selectedPresetId = 0;
        return null;
    }

    /** 当前「库内编队」页（预设/我的）的选中编队。 */
    private FormationModel selectedPageFormation() {
        if (TAB_PRESET.equals(tab)) return selectedPresetFormation();
        return selectedMineFormation();
    }

    private int[] currentAutoRatios() {
        int[] r = autoRatios;
        if (r == null || r.length < 3) return new int[]{50, 20, 30};
        return new int[]{Math.max(0, r[0]), Math.max(0, r[1]), Math.max(0, r[2])};
    }

    private int autoRatioSum() {
        int[] r = currentAutoRatios();
        return r[0] + r[1] + r[2];
    }

    // ==================== 渲染 ====================

    private void render() {
        if (rootFrame == null || destroyed) return;
        rootFrame.removeAllViews();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int cardH = Math.min(screenH - ManagerUi.dp(this, 40), ManagerUi.dp(this, 780));
        if (cardH < ManagerUi.dp(this, 480)) cardH = screenH - ManagerUi.dp(this, 24);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ManagerUi.stroke(this, ManagerUi.CARD, 16, ManagerUi.LINE));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ManagerUi.designWidth(this), cardH);
        cardLp.gravity = Gravity.CENTER;

        card.addView(buildHeader());

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(false);
        sc.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(ManagerUi.PAGE);
        body.setPadding(0, 0, 0, ManagerUi.dp(this, 10));
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (editorOpen) buildEditorBody(body);
        else buildManagerBody(body);

        rootFrame.addView(card, cardLp);
    }

    private View buildHeader() {
        FrameLayout head = new FrameLayout(this);
        head.setBackgroundColor(ManagerUi.CARD);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ManagerUi.dp(this, 56)));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        TextView title = ManagerUi.text(this, editorOpen ? "编队设置" : "编队管理",
                15, ManagerUi.TEXT, true);
        title.setGravity(Gravity.CENTER);
        center.addView(title);
        if (editorOpen) {
            String name = draft == null || draft.name == null || draft.name.isEmpty()
                    ? "新建编队" : draft.name;
            TextView sub = ManagerUi.text(this, name, 10.5f, ManagerUi.TEXT3, false);
            sub.setGravity(Gravity.CENTER);
            center.addView(sub);
        }
        head.addView(center, clp);

        TextView close = ManagerUi.text(this, "×", 18, ManagerUi.TEXT3, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(editorOpen ? "关闭编队设置" : "关闭编队管理");
        close.setBackground(ManagerUi.round(this, ManagerUi.SOFT, 999));
        close.setOnClickListener(v -> {
            if (editorOpen) closeEditor();
            else finish();
        });
        UiStyle.press(close);
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(
                ManagerUi.dp(this, 44), ManagerUi.dp(this, 44),
                Gravity.CENTER_VERTICAL | Gravity.END);
        xlp.rightMargin = ManagerUi.dp(this, 4);
        head.addView(close, xlp);

        if (!editorOpen) {
            TextView add = ManagerUi.text(this, "＋", 22, ManagerUi.PRIMARY, true);
            add.setGravity(Gravity.CENTER);
            add.setContentDescription("新建编队");
            add.setOnClickListener(v -> openEditor(null));
            UiStyle.press(add);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(this, 44), ManagerUi.dp(this, 44),
                    Gravity.CENTER_VERTICAL | Gravity.END);
            alp.rightMargin = ManagerUi.dp(this, 44);
            head.addView(add, alp);
        }

        if (editorOpen) {
            TextView back = ManagerUi.text(this, "‹", 24, ManagerUi.TEXT3, false);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("返回编队管理");
            back.setOnClickListener(v -> closeEditor());
            UiStyle.press(back);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ManagerUi.dp(this, 44), ManagerUi.dp(this, 44),
                    Gravity.CENTER_VERTICAL | Gravity.START);
            blp.leftMargin = 0;
            head.addView(back, blp);
        }
        return head;
    }

    // ==================== 管理页主体 ====================

    private void buildManagerBody(LinearLayout body) {
        body.addView(buildSourceTabs());
        if (TAB_MINE.equals(tab)) body.addView(buildMineSubTabs());
        if (TAB_GAME.equals(tab)) body.addView(buildMemoryStrip());
        if (TAB_MINE.equals(tab) && MINE_BEAR.equals(mineFilter)) body.addView(buildBearEntry());

        // 编队板 / 空态
        List<FormationBoardView.BoardTab> tabs = boardTabs();
        if (tabs.isEmpty()) {
            body.addView(emptyStateForTab());
        } else {
            FormationBoardView board = new FormationBoardView(this, boardHost);
            body.addView(board.view(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (TAB_GAME.equals(tab) && !gameFormations().isEmpty()) {
            LinearLayout refreshRow = ManagerUi.row(this);
            refreshRow.setPadding(ManagerUi.dp(this, 12), ManagerUi.dp(this, 4),
                    ManagerUi.dp(this, 12), 0);
            refreshRow.addView(new View(this), ManagerUi.lp(0, 1, 1f));
            TextView refresh = ManagerUi.smallButton(this,
                    loading ? "同步中" : "刷新游戏编队", false, this::refreshGameFormations);
            refreshRow.addView(refresh);
            body.addView(refreshRow);
        }

        body.addView(buildFooter());
    }

    private View buildSourceTabs() {
        LinearLayout row = ManagerUi.row(this);
        row.setPadding(ManagerUi.dp(this, 6), ManagerUi.dp(this, 8),
                ManagerUi.dp(this, 6), ManagerUi.dp(this, 5));
        String[][] tabs = {{TAB_AUTO, "自动编队"}, {TAB_GAME, "游戏编队"},
                {TAB_PRESET, "预设编队"}, {TAB_MINE, "我的编队"}};
        for (String[] t : tabs) {
            final String key = t[0];
            boolean active = key.equals(tab);
            TextView btn = ManagerUi.text(this, t[1], 13.5f,
                    active ? ManagerUi.PRIMARY_DEEP : ManagerUi.TEXT3, active);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(active
                    ? ManagerUi.round(this, ManagerUi.PRIMARY_50, 12)
                    : ManagerUi.round(this, ManagerUi.CARD, 12));
            btn.setMinHeight(ManagerUi.dp(this, 44));
            btn.setOnClickListener(v -> {
                if (editorOpen) return;
                if (key.equals(tab)) return;
                tab = key;
                if (TAB_MINE.equals(tab)) {
                    mineFilter = MINE_ALL;
                    selectedMineId = 0;
                }
                if (TAB_PRESET.equals(tab)) selectedPresetId = 0;
                if (TAB_GAME.equals(tab)) selectedGameId = 0;
                render();
            });
            UiStyle.press(btn);
            LinearLayout.LayoutParams lp = ManagerUi.lp(0,
                    ManagerUi.dp(this, 44), 1f);
            if (!t[0].equals(TAB_MINE)) lp.rightMargin = ManagerUi.dp(this, 6);
            row.addView(btn, lp);
        }
        return row;
    }

    private View buildMineSubTabs() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(ManagerUi.dp(this, 4), 0, ManagerUi.dp(this, 4),
                ManagerUi.dp(this, 4));
        int total = 0;
        int gather = 0;
        int custom = 0;
        int bear = 0;
        for (FormationModel f : lib) {
            if (f == null || f.isGame()) continue;
            total++;
            if (isGatherTeam(f)) gather++;
            if (!isPreset(f) && !f.isBear()) custom++;
            if (isBearTeam(f)) bear++;
        }
        // 子 tab：全部 / 采集 / 巨熊 / 自定义（2026-11-06；「预设」已由主 tab「预设编队」承载）
        String[][] subs = {{MINE_ALL, "全部 " + total}, {MINE_GATHER, "采集 " + gather},
                {MINE_BEAR, "巨熊 " + bear}, {MINE_CUSTOM, "自定义 " + custom}};
        LinearLayout line = ManagerUi.row(this);
        for (int i = 0; i < subs.length; i++) {
            final String key = subs[i][0];
            TextView btn = ManagerUi.subTab(this, subs[i][1], key.equals(mineFilter), () -> {
                if (key.equals(mineFilter)) return;
                mineFilter = key;
                selectedMineId = 0;
                render();
            });
            LinearLayout.LayoutParams lp = ManagerUi.lp(0,
                    ManagerUi.dp(this, 44), 1f);
            if (i < subs.length - 1) lp.rightMargin = ManagerUi.dp(this, 6);
            line.addView(btn, lp);
        }
        wrap.addView(line);

        // 显式「＋ 新建编队」入口（用户反馈：管理页只有“选择编队”，找不到新增入口）
        LinearLayout createRow = ManagerUi.row(this);
        createRow.setPadding(0, ManagerUi.dp(this, 6), 0, 0);
        TextView createBtn = ManagerUi.smallButton(this, "＋ 新建编队", true,
                () -> openEditor(null));
        createRow.addView(createBtn, ManagerUi.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ManagerUi.dp(this, 44), 1f));
        wrap.addView(createRow);
        return wrap;
    }

    private View buildMemoryStrip() {
        LinearLayout row = ManagerUi.row(this);
        row.setPadding(ManagerUi.dp(this, 12), ManagerUi.dp(this, 4),
                ManagerUi.dp(this, 12), ManagerUi.dp(this, 4));
        TextView mem = ManagerUi.text(this, memorySummary(), 11.5f, ManagerUi.TEXT3, false);
        mem.setSingleLine(true);
        row.addView(mem, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean hasMemory = hasAnyMemory();
        TextView save = ManagerUi.smallButton(this, "记忆", false, () -> openMemoryDialog(false));
        LinearLayout.LayoutParams slp = ManagerUi.lp(
                ManagerUi.dp(this, 64), ManagerUi.dp(this, 44));
        row.addView(save, slp);
        TextView restore = ManagerUi.smallButton(this, "恢复", false, () -> {
            if (!hasMemory) {
                UiStyle.toast(this, "还没有编队记忆");
                return;
            }
            if (!online()) {
                UiStyle.toast(this, "账号离线，无法恢复编队");
                return;
            }
            if (!catalog.configured()) {
                UiStyle.toast(this, "等待登录容量同步");
                return;
            }
            openMemoryDialog(true);
        });
        LinearLayout.LayoutParams rlp = ManagerUi.lp(
                ManagerUi.dp(this, 64), ManagerUi.dp(this, 44));
        rlp.leftMargin = ManagerUi.dp(this, 6);
        if (!hasMemory) restore.setAlpha(0.45f);
        row.addView(restore, rlp);
        return row;
    }

    private String memorySummary() {
        JSONObject mem = null;
        try {
            mem = catalog.memoryStatus();
        } catch (Throwable ignored) {
        }
        return "日常：" + memoryLabel(mem, "daily") + " ｜ 战斗：" + memoryLabel(mem, "battle");
    }

    private String memoryLabel(JSONObject mem, String key) {
        JSONObject o = mem == null ? null : mem.optJSONObject(key);
        if (o == null || !o.optBoolean("exists", false)) return "未保存";
        int count = o.optInt("slotCount", 0);
        long at = o.optLong("savedAt", 0);
        String time = at <= 0 ? "时间未知"
                : new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(at));
        return count + " 支 · " + time;
    }

    private boolean hasAnyMemory() {
        try {
            JSONObject mem = catalog.memoryStatus();
            if (mem == null) return false;
            JSONObject d = mem.optJSONObject("daily");
            JSONObject b = mem.optJSONObject("battle");
            return (d != null && d.optBoolean("exists", false))
                    || (b != null && b.optBoolean("exists", false));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 我的编队·巨熊分类头部卡片（2026-11-06）：模板 = 预设「打熊队」（发起）+「熊上车1-6」（上车），
     * 不再生成「巨熊·」新编队；「一键应用」把这两组预设写到巨熊行动参数（服务端只读顶层编队键）。
     */
    private View buildBearEntry() {
        LinearLayout card = ManagerUi.row(this);
        card.setPadding(ManagerUi.dp(this, 12), ManagerUi.dp(this, 10),
                ManagerUi.dp(this, 12), ManagerUi.dp(this, 10));
        card.setBackground(ManagerUi.stroke(this, ManagerUi.CARD, 14, ManagerUi.LINE_SOFT));
        LinearLayout.LayoutParams clp = ManagerUi.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = ManagerUi.dp(this, 6);
        clp.rightMargin = ManagerUi.dp(this, 6);
        clp.topMargin = ManagerUi.dp(this, 4);
        card.setLayoutParams(clp);

        boolean applied = false;
        try {
            applied = FormationStore.bearPresetApplied(this, fpid, sid, "bear");
        } catch (Throwable ignored) {
        }

        LinearLayout col = ManagerUi.column(this);
        col.addView(ManagerUi.text(this, "巨熊编队模板", 14, ManagerUi.TEXT, true));
        String subText = applied
                ? "已应用：打熊队（发起）+ 熊上车1-6（上车）；预设随角色最强英雄实时更新"
                : "模板 = 预设「打熊队」（发起）+「熊上车1-6」（上车），应用后巨熊行动按此出征";
        TextView sub = ManagerUi.text(this, subText, 11, ManagerUi.TEXT3, false);
        sub.setPadding(0, ManagerUi.dp(this, 2), 0, 0);
        sub.setLineSpacing(ManagerUi.dp(this, 1), 1f);
        col.addView(sub);
        card.addView(col, ManagerUi.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView choose = ManagerUi.smallButton(this, applied ? "重新应用" : "一键应用",
                false, this::applyBearPresetTemplate);
        LinearLayout.LayoutParams blp = ManagerUi.lp(
                ManagerUi.dp(this, 84), ManagerUi.dp(this, 36));
        blp.leftMargin = ManagerUi.dp(this, 8);
        card.addView(choose, blp);
        return card;
    }

    /** 一键应用巨熊编队模板（预设打熊队 + 熊上车1-6，服务端顶层编队参数）。 */
    private void applyBearPresetTemplate() {
        if (saving) return;
        saving = true;
        render();
        String msg = null;
        try {
            msg = FormationStore.applyBearPresetTemplate(this, fpid, sid, uidText());
        } catch (Throwable t) {
            UiStyle.toast(this, "应用模板失败：" + t.getMessage());
        }
        saving = false;
        if (msg == null) {
            render();   // 恢复底部按钮（saving=true 时显示“处理中…”）
            UiStyle.toast(this, "等待角色英雄数据同步完成后再应用模板");
        } else {
            mineFilter = MINE_BEAR;
            selectedMineId = 0;
            reload();
            render();
            syncNow();
            // 编队库（打熊队/上车队英雄）需随全量 sync 上传服务端
            VaultSync.syncAll(this);
            UiStyle.toast(this, msg);
        }
    }

    private View emptyStateForTab() {
        String text;
        if (TAB_GAME.equals(tab)) {
            text = "暂未同步到游戏内编队；请先登录游戏并保存编队";
        } else if (TAB_PRESET.equals(tab)) {
            return ManagerUi.emptyBlock(this, "✦", "暂无预设编队",
                    "登录后自动生成 19 套预设编队");
        } else if (MINE_BEAR.equals(mineFilter)) {
            text = "还没有巨熊编队；登录后自动生成预设中的打熊队与熊上车1-6";
        } else if (MINE_GATHER.equals(mineFilter)) {
            text = "还没有采集队；登录后自动生成预设中的采集肉/木/煤/铁";
        } else if (MINE_CUSTOM.equals(mineFilter)) {
            text = "还没有自定义编队；自动编队无需预先创建编队";
        } else {
            text = "还没有编队；可同步游戏编队或创建自定义编队";
        }
        return ManagerUi.emptyBlock(this, "🧩", "暂无编队", text);
    }

    private View buildFooter() {
        LinearLayout wrap = ManagerUi.column(this);
        wrap.setPadding(ManagerUi.dp(this, 4), ManagerUi.dp(this, 4),
                ManagerUi.dp(this, 4), ManagerUi.dp(this, 4));

        LinearLayout row = ManagerUi.row(this);
        row.setPadding(ManagerUi.dp(this, 8), 0, ManagerUi.dp(this, 8), 0);
        boolean configured = catalog.configured();
        row.addView(ManagerUi.tag(this, configured ? "容量已同步" : "同步中",
                configured ? ManagerUi.OK_BG : ManagerUi.WARN_BG,
                configured ? ManagerUi.OK_INK : ManagerUi.WARN_INK));
        int count = gameFormations().size() + lib.size();
        TextView countT = ManagerUi.text(this, count + " 支编队", 11, ManagerUi.TEXT4, false);
        countT.setPadding(ManagerUi.dp(this, 8), 0, 0, 0);
        row.addView(countT);
        row.addView(new View(this), ManagerUi.lp(0, 1, 1f));

        final boolean quickEnabled = editableNow();
        TextView quick = ManagerUi.smallButton(this,
                saving ? "处理中…" : "一键编队", true, () -> {
                    if (!quickEnabled) {
                        UiStyle.toast(this, !online() ? "账号离线，无法一键编队"
                                : "等待登录容量同步");
                        return;
                    }
                    runQuickFormation();
                });
        if (!quickEnabled) quick.setAlpha(0.5f);
        LinearLayout.LayoutParams qlp = ManagerUi.lp(
                ManagerUi.dp(this, 96), ManagerUi.dp(this, 36));
        qlp.rightMargin = ManagerUi.dp(this, 8);
        row.addView(quick, qlp);
        TextView memBtn = ManagerUi.smallButton(this, "编队记忆", false,
                () -> openMemoryDialog(false));
        row.addView(memBtn, ManagerUi.lp(
                ManagerUi.dp(this, 84), ManagerUi.dp(this, 36)));
        wrap.addView(row);

        LinearLayout statusRow = ManagerUi.row(this);
        statusRow.setPadding(ManagerUi.dp(this, 8), ManagerUi.dp(this, 6),
                ManagerUi.dp(this, 8), 0);
        TextView status = ManagerUi.text(this, entryStatusLine(), 10.5f,
                ManagerUi.TEXT3, false);
        status.setLineSpacing(ManagerUi.dp(this, 1), 1f);
        statusRow.addView(status, ManagerUi.lp(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(statusRow);
        return wrap;
    }

    /** 入口状态行 4 文案（规格 §1.1）。 */
    private String entryStatusLine() {
        if (!online()) return "账号离线，可查看最近同步的角色编队设置";
        if (!catalog.configured()) {
            String msg = "等待登录容量同步";
            try {
                JSONObject mc = catalog.marchCapacityMessage();
                if (mc != null && mc.optString("message", "").length() > 0) {
                    msg = mc.optString("message", msg);
                }
            } catch (Throwable ignored) {
            }
            return msg;
        }
        int games = gameFormations().size();
        int customs = 0;
        for (FormationModel f : lib) {
            if (f != null && !f.isGame() && !isPreset(f)) customs++;
        }
        if (games == 0 && customs == 0) {
            return "容量已就绪，可同步游戏编队或创建自定义编队";
        }
        return games + " 支游戏编队 · " + customs + " 支自定义编队";
    }

    // ==================== 编队板 Host ====================

    private List<FormationBoardView.BoardTab> boardTabs() {
        List<FormationBoardView.BoardTab> out = new ArrayList<FormationBoardView.BoardTab>();
        if (editorOpen) {
            for (FormationModel f : lib) {
                if (f == null || f.isGame()) continue;
                out.add(new FormationBoardView.BoardTab("e:" + f.id, displayName(f),
                        isPreset(f) ? "预设" : "自定义", firstHero(f)));
            }
            out.add(new FormationBoardView.BoardTab("new", "新建编队", "", 0));
            return out;
        }
        if (TAB_GAME.equals(tab)) {
            List<FormationModel> games = gameFormations();
            for (int i = 0; i < games.size(); i++) {
                FormationModel f = games.get(i);
                if (f == null) continue;
                String label;
                long slot = Math.abs(f.id);
                if (slot > 0) label = String.format(Locale.ROOT, "G%02d", slot);
                else label = String.format(Locale.ROOT, "G%02d", i + 1);
                String badge = f.name == null || f.name.isEmpty()
                        ? heroName(firstHero(f)) : f.name;
                out.add(new FormationBoardView.BoardTab("g:" + f.id, label, badge,
                        firstHero(f)));
            }
            return out;
        }
        if (TAB_PRESET.equals(tab)) {
            for (FormationModel f : presetFormations()) {
                out.add(new FormationBoardView.BoardTab("p:" + f.id, displayName(f), "预设",
                        firstHero(f)));
            }
            return out;
        }
        if (TAB_MINE.equals(tab)) {
            for (FormationModel f : mineFormations()) {
                String badge = isPreset(f) ? "预设"
                        : (f.isBear() ? "巨熊" : "自定义");
                out.add(new FormationBoardView.BoardTab("m:" + f.id, displayName(f), badge,
                        firstHero(f)));
            }
            return out;
        }
        // AUTO
        int[] r = currentAutoRatios();
        out.add(new FormationBoardView.BoardTab("auto", "自动编队",
                FormationRules.ratioCode(r[0], r[1], r[2]), 0));
        return out;
    }

    private static String displayName(FormationModel f) {
        if (f == null) return "";
        return f.name == null || f.name.isEmpty() ? "未命名编队" : f.name;
    }

    private static long firstHero(FormationModel f) {
        if (f == null) return 0;
        for (Long h : f.heroIds) {
            if (h != null && h > 0) return h;
        }
        return 0;
    }

    private String heroName(long heroId) {
        try {
            FormationStore.Hero h = catalog.hero(heroId);
            if (h != null && h.name != null && !h.name.isEmpty()) return h.name;
        } catch (Throwable ignored) {
        }
        return "英雄" + heroId;
    }

    private String boardKey() {
        if (editorOpen) return "editor";
        if (TAB_GAME.equals(tab)) return "game";
        if (TAB_PRESET.equals(tab)) return "preset";
        if (TAB_MINE.equals(tab)) return "mine:" + mineFilter;
        return "auto";
    }

    private long boardCapacity() {
        FormationModel f = boardFormation();
        try {
            if (f == null) return catalog.capacity();
            return catalog.capacityFor(f.heroIds);
        } catch (Throwable t) {
            return 0;
        }
    }

    private FormationModel boardFormation() {
        if (editorOpen) return draft;
        if (TAB_AUTO.equals(tab)) return null;
        if (TAB_GAME.equals(tab)) return selectedGameFormation();
        return selectedPageFormation();
    }

    private boolean boardAutomatic() {
        return !editorOpen && TAB_AUTO.equals(tab);
    }

    private int boardBannerTone() {
        String t = boardBannerText();
        if (t == null || t.isEmpty()) return 0;
        return boardBannerWarn ? 2 : 1;
    }

    private boolean boardBannerWarn;

    private String boardBannerText() {
        FormationModel f = boardFormation();
        if (editorOpen) {
            String err = null;
            try {
                err = FormationRules.validate(f, catalog);
            } catch (Throwable ignored) {
            }
            if (err != null) {
                boardBannerWarn = true;
                return err;
            }
            String away = null;
            try {
                away = FormationRules.availabilityWarning(f, catalog);
            } catch (Throwable ignored) {
            }
            if (away != null) {
                boardBannerWarn = true;
                return away;
            }
            boardBannerWarn = false;
            return "";
        }
        if (f != null) {
            String away = null;
            try {
                away = FormationRules.availabilityWarning(f, catalog);
            } catch (Throwable ignored) {
            }
            if (away != null) {
                boardBannerWarn = true;
                return away;
            }
            if (!catalog.configured()) {
                boardBannerWarn = false;
                return "等待登录容量同步";
            }
            boardBannerWarn = false;
            return "";
        }
        // AUTO
        int[] r = currentAutoRatios();
        int sum = r[0] + r[1] + r[2];
        if (sum != 100) {
            boardBannerWarn = true;
            return "比例合计为 " + sum + "%，请调整为 100%";
        }
        boardBannerWarn = false;
        if (!catalog.configured()) return "等待登录容量同步";
        return "出征时按 " + FormationRules.ratioCode(r[0], r[1], r[2]) + " 自动分配盾、矛、弓";
    }

    private String boardPrimaryLabel() {
        if (saving) return "处理中…";
        if (editorOpen) return editorPresetCopy ? "另存为新编队" : "保存编队";
        if (TAB_AUTO.equals(tab)) return "保存比例";
        if (TAB_GAME.equals(tab)) return "记忆当前编队";
        FormationModel f = selectedPageFormation();
        if (f == null) return "新建编队";
        return "编辑编队";
    }

    private String boardPrimaryHint() {
        if (editorOpen) return (draft == null ? 0 : draft.distinctHeroIds().size()) + " 英雄";
        if (TAB_AUTO.equals(tab)) {
            int[] r = currentAutoRatios();
            return FormationRules.ratioCode(r[0], r[1], r[2]);
        }
        if (TAB_GAME.equals(tab)) return gameFormations().size() + " 支";
        FormationModel f = selectedPageFormation();
        if (f == null) return "0 英雄";
        return f.distinctHeroIds().size() + " 英雄";
    }

    /** 主按钮校验：新建编队用占位名跳过「请填写编队名称」，其余错误照常返回。 */
    private String validateDraftForPrimary() {
        if (draft == null) return "编队配置不完整";
        final String saved = draft.name;
        final boolean needName = draft.id <= 0 && (saved == null || saved.trim().isEmpty());
        if (needName) draft.name = "新编队";
        try {
            return FormationRules.validate(draft, catalog);
        } catch (Throwable t) {
            return "编队配置不完整";
        } finally {
            if (needName) draft.name = saved == null ? "" : saved;
        }
    }

    private boolean boardPrimaryEnabled() {
        if (saving) return false;
        if (editorOpen) {
            if (draft == null) return false;
            // 新建编队：名称由「编队阵容」弹窗输入（网页 §1.6），主按钮不能因名称为空而禁用，
            // 否则用户永远点不进名称弹窗（生产实测：保存编队按钮一直灰色不可点）。
            return validateDraftForPrimary() == null;
        }
        if (TAB_AUTO.equals(tab)) {
            // T7：自动比例是 App 本地设置（保存即上传），不依赖容量同步 → 在线即可保存
            return online() && autoRatioSum() == 100;
        }
        if (TAB_GAME.equals(tab)) {
            return editableNow() && !gameFormations().isEmpty();
        }
        return true;
    }

    private int[] boardAutomaticRatios() {
        if (editorOpen && draft != null) {
            return new int[]{draft.infantryRatio, draft.lancerRatio, draft.marksmanRatio};
        }
        return currentAutoRatios();
    }

    private final FormationBoardView.Host boardHost = new FormationBoardView.Host() {
        @Override public String boardKey() {
            return FormationManagerActivity.this.boardKey();
        }

        @Override public List<FormationBoardView.BoardTab> tabs() {
            return boardTabs();
        }

        @Override public String selectedTab() {
            if (editorOpen) {
                return draft != null && draft.id > 0 ? "e:" + draft.id : "new";
            }
            if (TAB_GAME.equals(tab)) return "g:" + selectedGameFormationId();
            if (TAB_PRESET.equals(tab)) return "p:" + selectedPresetFormationId();
            if (TAB_MINE.equals(tab)) return "m:" + selectedMineFormationId();
            return "auto";
        }

        @Override public FormationModel formation() {
            return boardFormation();
        }

        @Override public boolean automatic() {
            return boardAutomatic();
        }

        @Override public long capacity() {
            return boardCapacity();
        }

        @Override public long[] capacityLimits() {
            try {
                return catalog.limits();
            } catch (Throwable t) {
                return new long[]{0, 0, 0, 0};
            }
        }

        @Override public String bannerText() {
            return boardBannerText();
        }

        @Override public int bannerTone() {
            return boardBannerTone();
        }

        @Override public boolean editable() {
            // T7：编辑器/自动比例不依赖容量（configured()=false 时比例模式仍可编辑保存；
            // 手动数量模式的容量校验在 FormationRules.validate 内按 cat.configured() 决定是否执行）
            return editorOpen ? online() : (TAB_AUTO.equals(tab) && online());
        }

        @Override public boolean heroesInteractive() {
            return editorOpen;
        }

        @Override public boolean secondaryActions() {
            return editorOpen || TAB_AUTO.equals(tab);
        }

        @Override public String primaryLabel() {
            return boardPrimaryLabel();
        }

        @Override public String primaryHint() {
            return boardPrimaryHint();
        }

        @Override public boolean primaryEnabled() {
            return boardPrimaryEnabled();
        }

        @Override public int[] automaticRatios() {
            return boardAutomaticRatios();
        }

        @Override public void onTabSelected(String key) {
            handleTabSelected(key);
        }

        @Override public void onHeroSlot(int professionType) {
            handleHeroSlot(professionType);
        }

        @Override public void onHeroLongPress(long heroId) {
            if (!editorOpen || draft == null) return;
            FormationStore.Hero h = null;
            try {
                h = catalog.hero(heroId);
            } catch (Throwable ignored) {
            }
            if (h == null) return;
            UiStyle.toast(FormationManagerActivity.this, h.label());
        }

        @Override public void onCaptainToggle(long heroId) {
            if (!editorOpen || draft == null) return;
            Long eff = draft.effectiveCaptainId();
            draft.captainHeroId = eff != null && eff.longValue() == heroId
                    ? null : Long.valueOf(heroId);
            touchDraft();
            render();
        }

        @Override public void onTroopChanged(String key, long value) {
            if (!editorOpen || draft == null) return;
            long cap = boardCapacity();
            long other = 0;
            if (!FormationBoardView.KEY_INFANTRY.equals(key)) other += draft.infantry;
            if (!FormationBoardView.KEY_LANCER.equals(key)) other += draft.lancer;
            if (!FormationBoardView.KEY_MARKSMAN.equals(key)) other += draft.marksman;
            long max = cap > 0 ? Math.max(0, cap - other) : value;
            long v = Math.max(0, Math.min(max, value));
            if (FormationBoardView.KEY_INFANTRY.equals(key)) draft.infantry = v;
            else if (FormationBoardView.KEY_LANCER.equals(key)) draft.lancer = v;
            else draft.marksman = v;
            draft.allocationMode = FormationModel.MODE_MANUAL;
            refreshDraftRatios();
            touchDraft();
            render();
        }

        @Override public void onRatioChanged(String key, int pct) {
            int p = Math.max(0, Math.min(100, pct));
            if (editorOpen && draft != null) {
                int[] r = new int[]{draft.infantryRatio, draft.lancerRatio,
                        draft.marksmanRatio};
                r = rebalance(r, key, p);
                draft.infantryRatio = r[0];
                draft.lancerRatio = r[1];
                draft.marksmanRatio = r[2];
                draft.allocationMode = FormationModel.MODE_PERCENTAGE;
                applyRatiosToCounts(draft, boardCapacity());
                touchDraft();
                render();
            } else if (TAB_AUTO.equals(tab)) {
                int[] r = rebalance(currentAutoRatios(), key, p);
                autoRatios = r;
                touchDraft();
                render();
            }
        }

        @Override public void onSecondaryAction(String action) {
            if ("clear".equals(action)) {
                if (editorOpen && draft != null) {
                    draft.allocationMode = FormationModel.MODE_MANUAL;
                    draft.infantry = 0;
                    draft.lancer = 0;
                    draft.marksman = 0;
                    draft.infantryRatio = 0;
                    draft.lancerRatio = 0;
                    draft.marksmanRatio = 0;
                    touchDraft();
                    render();
                } else if (TAB_AUTO.equals(tab)) {
                    autoRatios = new int[]{0, 0, 0};
                    touchDraft();
                    render();
                }
            } else if ("average".equals(action)) {
                if (editorOpen && draft != null) {
                    long cap = boardCapacity();
                    long each = Math.max(0, cap / 3);
                    draft.allocationMode = FormationModel.MODE_MANUAL;
                    draft.infantry = each + Math.max(0, cap - each * 3);
                    draft.lancer = each;
                    draft.marksman = each;
                    refreshDraftRatios();
                    touchDraft();
                    render();
                } else if (TAB_AUTO.equals(tab)) {
                    autoRatios = new int[]{34, 33, 33};
                    touchDraft();
                    render();
                }
            } else if ("ratio".equals(action)) {
                openRatioDialog();
            }
        }

        @Override public void onPrimary() {
            handlePrimary();
        }

        @Override public void onCapacityDetail() {
            try {
                FormationManagerDialogs.showCapacityDialog(FormationManagerActivity.this,
                        catalog);
            } catch (Throwable t) {
                UiStyle.toast(FormationManagerActivity.this, "打开容量详情失败：" + t.getMessage());
            }
        }

        @Override public void onPetCapacity() {
            try {
                FormationManagerDialogs.showPetCapacityDialog(FormationManagerActivity.this,
                        catalog);
            } catch (Throwable t) {
                UiStyle.toast(FormationManagerActivity.this, "打开宠物增益失败：" + t.getMessage());
            }
        }

        @Override public void onOpenSettings() {
            if (editorOpen) {
                openEditorSettings();
                return;
            }
            if (TAB_AUTO.equals(tab)) {
                openRatioDialog();
                return;
            }
            if (TAB_MINE.equals(tab) || TAB_PRESET.equals(tab)) {
                FormationModel f = selectedPageFormation();
                if (f == null) {
                    UiStyle.toast(FormationManagerActivity.this,
                            TAB_PRESET.equals(tab)
                                    ? "登录后自动生成 19 套预设编队"
                                    : "还没有编队；可同步游戏编队或创建自定义编队");
                    return;
                }
                openEditor(f);
                return;
            }
            FormationModel game = selectedGameFormation();
            if (game == null) {
                UiStyle.toast(FormationManagerActivity.this,
                        "暂未同步到游戏内编队；请先登录游戏并保存编队");
                return;
            }
            if (!online()) {
                UiStyle.toast(FormationManagerActivity.this,
                        "账号离线，可查看最近同步的角色编队设置");
                return;
            }
            openEditor(game);
        }
    };

    private long selectedGameFormationId() {
        FormationModel f = selectedGameFormation();
        return f == null ? 0 : f.id;
    }

    private long selectedMineFormationId() {
        FormationModel f = selectedMineFormation();
        return f == null ? 0 : f.id;
    }

    private long selectedPresetFormationId() {
        FormationModel f = selectedPresetFormation();
        return f == null ? 0 : f.id;
    }

    // ==================== 交互 ====================

    private void handleTabSelected(String key) {
        if (key == null) return;
        if (editorOpen) {
            if ("new".equals(key)) {
                switchEditorDraft(null);
            } else if (key.startsWith("e:")) {
                long id = parseLong(key.substring(2));
                FormationModel f = findLib(id);
                if (f != null) switchEditorDraft(f);
            }
            return;
        }
        if (key.startsWith("g:")) {
            long id = parseLong(key.substring(2));
            if (id != selectedGameId) {
                selectedGameId = id;
                render();
            }
        } else if (key.startsWith("p:")) {
            long id = parseLong(key.substring(2));
            if (id != selectedPresetId) {
                selectedPresetId = id;
                render();
            }
        } else if (key.startsWith("m:")) {
            long id = parseLong(key.substring(2));
            if (id != selectedMineId) {
                selectedMineId = id;
                render();
            }
        }
    }

    private void handleHeroSlot(int professionType) {
        if (!editorOpen || draft == null) return;
        if (professionType <= 0) {
            // 板内移除英雄后的刷新（× 按钮）
            if (draft.captainHeroId != null && !draft.hasHero(draft.captainHeroId)) {
                draft.captainHeroId = null;
            }
            clampDraftToCapacity();
            touchDraft();
            render();
            return;
        }
        // T7：uid 感知英雄选项（同 fpid:sid 多角色不串目录）
        HeroPickerDialog.show(this, fpid, sid, uidText(), draft.heroIds, draft.captainHeroId,
                professionType, (heroIds, captainHeroId) -> {
                    if (draft == null) return;
                    draft.heroIds.clear();
                    if (heroIds != null) {
                        for (Long h : heroIds) {
                            if (h != null && h > 0) draft.heroIds.add(h);
                        }
                    }
                    draft.captainHeroId = captainHeroId;
                    clampDraftToCapacity();
                    touchDraft();
                    render();
                });
    }

    private void handlePrimary() {
        if (saving) return;
        if (editorOpen) {
            saveEditor();
            return;
        }
        if (TAB_AUTO.equals(tab)) {
            saveAutoRatio();
            return;
        }
        if (TAB_GAME.equals(tab)) {
            openMemoryDialog(false);
            return;
        }
        // MINE / PRESET
        FormationModel f = selectedPageFormation();
        if (f == null) openEditor(null);
        else openEditor(f);
    }

    private void saveAutoRatio() {
        int[] r = currentAutoRatios();
        if (r[0] + r[1] + r[2] != 100) {
            UiStyle.toast(this, "比例合计为 " + (r[0] + r[1] + r[2]) + "%，请调整为 100%");
            return;
        }
        try {
            FormationStore.saveAutomaticSettings(this, fpid, sid, r[0], r[1], r[2]);
        } catch (Throwable t) {
            UiStyle.toast(this, "保存失败：" + t.getMessage());
            return;
        }
        syncNow();
        UiStyle.toast(this, "自动编队比例已保存为 " + FormationRules.ratioCode(r[0], r[1], r[2]));
        // 从功能页进入时：可选择把这套比例应用到哪些功能队列（保存目标·功能 tab）
        if (focusFnId != null && !focusFnId.isEmpty()) {
            openFunctionTargetDialog(r);
        }
    }

    private void openFunctionTargetDialog(final int[] ratios) {
        List<String> ids = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        for (FnRegistry.Fn fn : FnRegistry.all()) {
            if (fn == null || !fn.formation) continue;
            if ("guardian".equals(fn.id) || "bear".equals(fn.id)) continue;
            ids.add(fn.id);
            labels.add(fn.name);
        }
        FormationManagerDialogs.showFunctionTargetDialog(this,
                ids.toArray(new String[0]), labels.toArray(new String[0]),
                new String[]{focusFnId}, chosen -> {
                    for (String fnId : chosen) {
                        try {
                            FormationStore.saveFnAutoRatio(FormationManagerActivity.this,
                                    fpid, sid, fnId, ratios[0], ratios[1], ratios[2], 0);
                        } catch (Throwable ignored) {
                        }
                    }
                    syncNow();
                    UiStyle.toast(FormationManagerActivity.this, "已应用到 " + chosen.size() + " 个功能队列");
                });
    }

    private void openRatioDialog() {
        int[] current = editorOpen && draft != null
                ? new int[]{draft.infantryRatio, draft.lancerRatio, draft.marksmanRatio}
                : currentAutoRatios();
        // 勾选「开启出征界面默认使用当前比例分配」即写全局自动比例（FormationStore.automaticSettings）
        int[] auto = currentAutoRatios();
        boolean defaultOn = current[0] == auto[0] && current[1] == auto[1]
                && current[2] == auto[2];
        FormationRatioDialog.show(this, current, catalog.automaticAvailable(),
                true, defaultOn, (inf, lancer, marksman, useAsDefault) -> {
                    if (useAsDefault) {
                        // 勾选：保存全局自动比例，并同步当前编辑/自动比例（契约 §4.3）
                        try {
                            FormationStore.saveAutomaticSettings(FormationManagerActivity.this,
                                    fpid, sid, inf, lancer, marksman);
                        } catch (Throwable ignored) {
                        }
                        autoRatios = new int[]{inf, lancer, marksman};
                        syncNow();
                    }
                    if (editorOpen && draft != null) {
                        draft.infantryRatio = inf;
                        draft.lancerRatio = lancer;
                        draft.marksmanRatio = marksman;
                        draft.allocationMode = FormationModel.MODE_PERCENTAGE;
                        applyRatiosToCounts(draft, boardCapacity());
                        touchDraft();
                    } else {
                        autoRatios = new int[]{inf, lancer, marksman};
                        touchDraft();
                    }
                    render();
                    if (useAsDefault) {
                        UiStyle.toast(FormationManagerActivity.this,
                                "已开启出征界面默认使用当前比例分配");
                    }
                });
    }

    private void openMemoryDialog(boolean restore) {
        FormationManagerDialogs.showMemoryDialog(this, catalog, restore,
                new FormationManagerDialogs.MemoryAction() {
                    @Override public void onSave(final String kind, boolean overwrite) {
                        saving = true;
                        render();
                        VaultSync.formationMemorySave(FormationManagerActivity.this, fpid,
                                String.valueOf(sid), uidText(), kind, overwrite,
                                (code, message, data) -> ui.post(() -> {
                                    saving = false;
                                    if (destroyed) return;
                                    if (code == 0) {
                                        int count = data == null ? 0
                                                : data.optInt("slotCount", 0);
                                        UiStyle.toast(FormationManagerActivity.this,
                                                "已保存 " + count + " 支游戏编队到"
                                                        + kindLabel(kind) + "记忆");
                                        refreshAfterAction();
                                    } else {
                                        UiStyle.toast(FormationManagerActivity.this,
                                                message == null || message.isEmpty()
                                                        ? "保存失败" : message);
                                        render();
                                    }
                                }));
                    }

                    @Override public void onRestore(final String kind) {
                        saving = true;
                        render();
                        VaultSync.formationMemoryRestore(FormationManagerActivity.this, fpid,
                                String.valueOf(sid), uidText(), kind,
                                (code, message, data) -> ui.post(() -> {
                                    saving = false;
                                    if (destroyed) return;
                                    if (code != 0) {
                                        UiStyle.toast(FormationManagerActivity.this,
                                                message == null || message.isEmpty()
                                                        ? "恢复失败" : message);
                                        render();
                                        return;
                                    }
                                    int total = data == null ? 0 : data.optInt("total", 0);
                                    int restored = data == null ? 0 : data.optInt("restored", 0);
                                    int fail = data == null ? 0
                                            : (data.optJSONArray("failures") == null
                                            ? 0 : data.optJSONArray("failures").length());
                                    if (fail > 0) {
                                        UiStyle.toast(FormationManagerActivity.this,
                                                "已恢复 " + restored + "/" + total + " 支编队，"
                                                        + fail + " 支失败，请查看游戏内状态");
                                    } else {
                                        UiStyle.toast(FormationManagerActivity.this,
                                                "已恢复 " + total + " 支" + kindLabel(kind)
                                                        + "编队；游戏客户端若一直开着，请退出重新登录后再查看编队");
                                    }
                                    refreshAfterAction();
                                }));
                    }
                });
    }

    private static String kindLabel(String kind) {
        return "BATTLE".equals(kind) ? "战斗" : "日常";
    }

    private void refreshAfterAction() {
        fetchState(true);
    }

    /** 服务端 uid 入参（0=未知，传空串，避免污染 state 键）。 */
    private String uidText() {
        return uid > 0 ? String.valueOf(uid) : "";
    }

    private void refreshGameFormations() {
        if (saving) return;
        saving = true;
        render();
        VaultSync.formationCatalogRefresh(this, fpid, String.valueOf(sid),
                uidText(), (code, message, data) -> ui.post(() -> {
                    saving = false;
                    if (destroyed) return;
                    if (code != 0) {
                        UiStyle.toast(FormationManagerActivity.this,
                                message == null || message.isEmpty()
                                        ? "刷新游戏编队失败" : message);
                        render();
                        return;
                    }
                    UiStyle.toast(FormationManagerActivity.this, "游戏编队已刷新");
                    fetchState(true);
                }));
    }

    private void runQuickFormation() {
        if (saving) return;
        saving = true;
        render();
        final List<FormationQuickSetup.Step> steps;
        try {
            steps = FormationQuickSetup.run(this, fpid, sid, uidText());
        } catch (Throwable t) {
            saving = false;
            render();
            UiStyle.toast(this, "一键编队失败：" + t.getMessage());
            return;
        }
        saving = false;
        reload();
        render();
        syncNow();
        FormationManagerDialogs.showQuickFormationResult(this, steps);
    }

    // ==================== 编辑器 ====================

    private void buildEditorBody(LinearLayout body) {
        // 编辑器的编队 tab 条由编队板自身渲染（host.tabs() = 我的编队 + 新建编队，网页 §1.4）
        FormationBoardView board = new FormationBoardView(this, boardHost);
        body.addView(board.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 编辑提示
        LinearLayout tips = ManagerUi.column(this);
        tips.setPadding(ManagerUi.dp(this, 12), ManagerUi.dp(this, 4),
                ManagerUi.dp(this, 12), 0);
        String tipText;
        int tipColor;
        if (editorGameCopy) {
            tipText = "游戏编队不可直接覆盖，已按副本编辑，保存后生成自定义编队";
            tipColor = ManagerUi.WARN_INK;
        } else if (editorPresetCopy) {
            tipText = "预设编队始终按当前最强英雄实时更新；保存修改会另存为新编队"
                    + "（名字默认用上阵英雄名），预设本身保持不变";
            tipColor = ManagerUi.WARN_INK;
        } else {
            tipText = "点击英雄格选择英雄；长按英雄可查看信息；主按钮保存编队";
            tipColor = ManagerUi.TEXT4;
        }
        TextView tip = ManagerUi.text(this, tipText, 10.5f, tipColor, false);
        tip.setLineSpacing(ManagerUi.dp(this, 1), 1f);
        tips.addView(tip);
        body.addView(tips);

        body.addView(buildEditorFooter());
    }

    private View buildEditorFooter() {
        LinearLayout wrap = ManagerUi.column(this);
        wrap.setPadding(ManagerUi.dp(this, 4), ManagerUi.dp(this, 4),
                ManagerUi.dp(this, 4), ManagerUi.dp(this, 4));
        LinearLayout row = ManagerUi.row(this);
        row.setPadding(ManagerUi.dp(this, 8), 0, ManagerUi.dp(this, 8), 0);
        // 预设/自定义均可编辑、复制、删除（删除后下次 ensure 重建），不再有「内置」保护。
        TextView del = ManagerUi.smallButton(this, "删除编队", false, () -> {
            if (draft == null) return;
            if (PresetFormations.isPreset(draft.id)) {
                UiStyle.toast(this, "预设编队始终保留；如需自定义请编辑后「另存为新编队」");
                return;
            }
            if (draft.id <= 0) {
                UiStyle.toast(this, "新建编队尚未保存");
                return;
            }
            FormationManagerDialogs.showDeleteConfirm(this, draft, () -> {
                try {
                    FormationStore.deleteFormation(this, fpid, sid, draft.id);
                } catch (Throwable ignored) {
                }
                syncNow();
                UiStyle.toast(this, FormationRules.MSG_SAVED_DELETED);
                closeEditor();
                reload();
                render();
            });
        });
        del.setAlpha(draft != null && draft.id > 0
                && !PresetFormations.isPreset(draft.id) ? 1f : 0.45f);
        row.addView(del);
        TextView copy = ManagerUi.smallButton(this, "复制编队", false, this::duplicateDraft);
        copy.setAlpha(draft != null && draft.id > 0 ? 1f : 0.45f);
        LinearLayout.LayoutParams clp0 = ManagerUi.lp(
                ManagerUi.dp(this, 84), ManagerUi.dp(this, 36));
        clp0.leftMargin = ManagerUi.dp(this, 8);
        row.addView(copy, clp0);
        row.addView(new View(this), ManagerUi.lp(0, 1, 1f));
        TextView cancel = ManagerUi.smallButton(this, "取消", false, this::closeEditor);
        LinearLayout.LayoutParams clp = ManagerUi.lp(
                ManagerUi.dp(this, 84), ManagerUi.dp(this, 36));
        row.addView(cancel, clp);
        wrap.addView(row);
        return wrap;
    }

    /** 预设另存为新编队时的默认名：上阵英雄名（≤3）用「·」连接，空则「预设副本」。 */
    private String presetCopyName(FormationModel f) {
        List<Long> heroes = f == null ? new ArrayList<Long>() : f.distinctHeroIds();
        StringBuilder sb = new StringBuilder();
        for (Long id : heroes) {
            if (id == null || id <= 0L) continue;
            String n = heroName(id);
            if (n == null || n.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("·");
            sb.append(n.trim());
        }
        return sb.length() == 0 ? "预设副本" : FormationModel.truncateName(sb.toString());
    }

    /** 复制当前编队（新建副本并进入编辑）；预设与自定义均可复制。 */
    private void duplicateDraft() {
        if (draft == null || draft.id <= 0) {
            UiStyle.toast(this, "新建编队尚未保存，无法复制");
            return;
        }
        FormationModel copy = null;
        try {
            copy = FormationStore.duplicateFormation(this, fpid, sid, draft.id);
        } catch (Throwable ignored) {
        }
        if (copy == null) {
            UiStyle.toast(this, "复制失败：源编队不存在");
            return;
        }
        syncNow();
        reload();
        UiStyle.toast(this, "已复制为 " + copy.name);
        openEditor(copy);
    }

    private void openEditor(FormationModel source) {
        // T7：配置未同步（configured()=false）不再阻止编辑——编队库存 App 本地、比例模式不依赖容量、
        // 手动数量模式由 FormationRules.validate 只在 configured 时才做容量校验；离线仍只读（网页同款）。
        if (!online()) {
            UiStyle.toast(this, "账号离线，可查看最近同步的角色编队设置");
            return;
        }
        editorOpen = true;
        editorGameCopy = false;
        editorPresetCopy = source != null && !source.isGame()
                && PresetFormations.isPreset(source.id);
        if (source == null) {
            draft = new FormationModel();
            draft.allocationMode = FormationModel.MODE_PERCENTAGE;
            draft.infantryRatio = 50;
            draft.lancerRatio = 20;
            draft.marksmanRatio = 30;
            applyRatiosToCounts(draft, catalog.capacity());
            draft.name = "";
        } else if (source.isGame()) {
            editorGameCopy = true;
            draft = source.copy();
            draft.id = 0;
            draft.source = FormationModel.SOURCE_CUSTOM;
            draft.name = (source.name == null ? "" : source.name) + "（副本）";
            UiStyle.toast(this, FormationRules.MSG_GAME_READONLY);
        } else {
            draft = source.copy();
        }
        if (draft.captainHeroId == null || !draft.hasHero(draft.captainHeroId)) {
            draft.captainHeroId = draft.heroIds.isEmpty() ? null : draft.heroIds.get(0);
        }
        editorBaseline = draftJson(draft);
        render();
    }

    private void switchEditorDraft(final FormationModel next) {
        Runnable action = () -> {
            if (next == null) {
                draft = new FormationModel();
                draft.allocationMode = FormationModel.MODE_PERCENTAGE;
                draft.infantryRatio = 50;
                draft.lancerRatio = 20;
                draft.marksmanRatio = 30;
                applyRatiosToCounts(draft, catalog.capacity());
                draft.name = "";
                editorGameCopy = false;
                editorPresetCopy = false;
            } else {
                draft = next.copy();
                editorGameCopy = false;
                editorPresetCopy = PresetFormations.isPreset(next.id);
                if (draft.captainHeroId == null || !draft.hasHero(draft.captainHeroId)) {
                    draft.captainHeroId = draft.heroIds.isEmpty() ? null : draft.heroIds.get(0);
                }
            }
            editorBaseline = draftJson(draft);
            render();
        };
        if (editorDirty()) {
            FormationManagerDialogs.showSwitchConfirm(this, action);
        } else {
            action.run();
        }
    }

    private void closeEditor() {
        if (editorOpen && editorDirty()) {
            FormationManagerDialogs.showSwitchConfirm(this, () -> {
                editorOpen = false;
                draft = null;
                editorBaseline = null;
                editorGameCopy = false;
                reload();
                render();
            });
            return;
        }
        editorOpen = false;
        draft = null;
        editorBaseline = null;
        editorGameCopy = false;
        editorPresetCopy = false;
        reload();
        render();
    }

    private void openEditorSettings() {
        if (draft == null) return;
        boolean canDelete = draft.id > 0 && !PresetFormations.isPreset(draft.id);
        FormationManagerDialogs.showEditorSettingsDialog(this, draft, canDelete,
                new FormationManagerDialogs.EditorSettings() {
                    @Override public void onConfirm(String name, String allocationMode) {
                        if (draft == null) return;
                        boolean modeChanged = !FormationModel.normalizeAllocationMode(
                                draft.allocationMode).equals(allocationMode);
                        draft.name = name;
                        draft.allocationMode = allocationMode;
                        if (modeChanged && FormationModel.MODE_PERCENTAGE.equals(allocationMode)) {
                            if (draft.ratioSum() <= 0) {
                                draft.infantryRatio = 50;
                                draft.lancerRatio = 20;
                                draft.marksmanRatio = 30;
                            }
                            applyRatiosToCounts(draft, boardCapacity());
                        } else if (modeChanged) {
                            if (draft.soldiersTotal() <= 0) {
                                long cap = boardCapacity();
                                long each = cap / 3;
                                draft.infantry = each + Math.max(0, cap - each * 3);
                                draft.lancer = each;
                                draft.marksman = each;
                            }
                            refreshDraftRatios();
                        }
                        touchDraft();
                        render();
                    }

                    @Override public void onDelete() {
                        if (draft == null || draft.id <= 0) return;
                        if (PresetFormations.isPreset(draft.id)) {
                            UiStyle.toast(FormationManagerActivity.this,
                                    "预设编队始终保留；如需自定义请「另存为新编队」");
                            return;
                        }
                        FormationManagerDialogs.showDeleteConfirm(
                                FormationManagerActivity.this, draft, () -> {
                                    try {
                                        FormationStore.deleteFormation(
                                                FormationManagerActivity.this, fpid, sid, draft.id);
                                    } catch (Throwable ignored) {
                                    }
                                    syncNow();
                                    UiStyle.toast(FormationManagerActivity.this,
                                            FormationRules.MSG_SAVED_DELETED);
                                    closeEditor();
                                    reload();
                                    render();
                                });
                    }
                });
    }

    private void saveEditor() {
        if (draft == null || saving) return;
        String err = validateDraftForPrimary();
        if (err != null) {
            UiStyle.toast(this, err);
            return;
        }
        clampDraftToCapacity();
        // 新建与编辑都先选保存目标（覆盖当前 / 目标编队 / 新建+名称），与网页版一致。
        FormationManagerDialogs.showSaveTargetDialog(this, lib, draft, draft.id,
                (targetId, name) -> doSaveEditor(targetId, name));
    }

    private void doSaveEditor(long targetId, String name) {
        if (draft == null) return;
        saving = true;
        render();
        FormationModel f = draft.copy();
        // 2026-11-03：预设编队是「实时最强」模板，永远保留；在预设上保存 = 另存为新编队，
        // 名字默认取上阵英雄名（用户若在保存弹窗里手填了其它名字则尊重）。
        boolean presetDraft = draft.id > 0 && PresetFormations.isPreset(draft.id);
        boolean presetTarget = targetId > 0 && PresetFormations.isPreset(targetId);
        boolean fromPreset = presetDraft || presetTarget;
        String presetName = "";
        if (presetTarget) {
            FormationModel target = findLib(targetId);
            if (target != null) presetName = target.name == null ? "" : target.name.trim();
        } else if (presetDraft) {
            FormationModel self = findLib(draft.id);
            if (self != null) presetName = self.name == null ? "" : self.name.trim();
        }
        if (fromPreset) {
            f.id = 0;
            f.source = FormationModel.SOURCE_CUSTOM;
            f.builtIn = false;
            f.templateKey = null;   // 副本是普通自定义编队，不再享受实时重算
            f.version = 1;
            f.updatedAt = 0L;
        } else if (targetId > 0) {
            f.id = targetId;
        } else {
            f.id = 0;
        }
        if (!FormationModel.MODE_PERCENTAGE.equals(
                FormationModel.normalizeAllocationMode(f.allocationMode))) {
            f.allocationMode = FormationModel.MODE_MANUAL;
        } else {
            // 「编队阵容」弹窗可在保存前切换为按比例储存：按当前容量重算三兵种数量，
            // 保证 allocationMode 与 ratio/counts 一致。
            f.allocationMode = FormationModel.MODE_PERCENTAGE;
            if (f.ratioSum() <= 0) {
                f.infantryRatio = 50;
                f.lancerRatio = 20;
                f.marksmanRatio = 30;
            }
            applyRatiosToCounts(f, boardCapacity());
        }
        String pickedName = name == null ? "" : name.trim();
        boolean nameUnset = pickedName.isEmpty()
                || (!presetName.isEmpty() && pickedName.equals(presetName))
                || (fromPreset && f.name != null && pickedName.equals(f.name.trim()));
        if (!pickedName.isEmpty()) f.name = pickedName;
        if (fromPreset && nameUnset) f.name = presetCopyName(f);
        if (f.name == null) f.name = "";
        boolean created = f.id <= 0;
        FormationModel saved = null;
        try {
            saved = FormationStore.saveFormation(this, fpid, sid, uidText(), f);   // T7：uid 感知可用性
        } catch (Throwable t) {
            saved = null;
        }
        saving = false;
        if (saved == null) {
            if (created) {
                // 可能因名称/校验失败
                UiStyle.toast(this, "保存失败：编队名称或配置不合法");
            } else {
                UiStyle.toast(this, "保存失败：编队不存在或受保护");
            }
            render();
            return;
        }
        syncNow();
        toastCrossRefs(saved);
        if (fromPreset) {
            UiStyle.toast(this, "已另存为新编队「" + saved.name + "」，预设编队保持不变");
        } else {
            UiStyle.toast(this, created ? FormationRules.MSG_SAVED_CREATED
                    : FormationRules.MSG_SAVED_UPDATED);
        }
        editorOpen = false;
        draft = null;
        editorBaseline = null;
        editorGameCopy = false;
        editorPresetCopy = false;
        reload();
        tab = TAB_MINE;
        mineFilter = fromPreset ? MINE_CUSTOM : MINE_ALL;
        selectedMineId = saved.id;
        render();
    }

    private void toastCrossRefs(FormationModel saved) {
        // 引用校验提示（不阻止保存；删除/改名后队列可能需重选）
        try {
            for (FnRegistry.Fn fn : FnRegistry.all()) {
                if (fn == null || !fn.formation) continue;
                long[] ids = FormationStore.fnFormationIds(this, fpid, sid, fn.id);
                if (ids == null || ids.length == 0) continue;
                if (FormationStore.validateBinding(catalog, lib, fn.id,
                        FormationStore.MODE_ROLE, ids) != null) {
                    // 交由用户下次打开功能页时提示，这里只做静默
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 编辑草稿工具 ====================

    private void touchDraft() {
        if (draft != null) {
            draft.updatedAt = System.currentTimeMillis();
        }
    }

    private boolean editorDirty() {
        if (!editorOpen || draft == null) return false;
        return editorBaseline != null && !editorBaseline.equals(draftJson(draft));
    }

    private static String draftJson(FormationModel f) {
        if (f == null) return "";
        try {
            return f.toJson(false).toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 比例变更后重算三项兵力（最大余数法，容量=当前编制英雄容量）。 */
    private void applyRatiosToCounts(FormationModel f, long capacity) {
        int sum = f.ratioSum();
        if (sum <= 0) return;
        long[] c = FormationRules.allocateByRatio(capacity, f.infantryRatio,
                f.lancerRatio, f.marksmanRatio);
        f.infantry = c[0];
        f.lancer = c[1];
        f.marksman = c[2];
    }

    /** 数量变更后反算比例（数量模式，供只读展示/上传参考）。 */
    private void refreshDraftRatios() {
        if (draft == null) return;
        long total = draft.soldiersTotal();
        if (total <= 0) {
            draft.infantryRatio = 0;
            draft.lancerRatio = 0;
            draft.marksmanRatio = 0;
            return;
        }
        int inf = (int) Math.round(draft.infantry * 100.0 / total);
        int lanc = (int) Math.round(draft.lancer * 100.0 / total);
        inf = Math.max(0, Math.min(100, inf));
        lanc = Math.max(0, Math.min(100 - inf, lanc));
        draft.infantryRatio = inf;
        draft.lancerRatio = lanc;
        draft.marksmanRatio = Math.max(0, 100 - inf - lanc);
    }

    private void clampDraftToCapacity() {
        if (draft == null) return;
        long cap = boardCapacity();
        if (cap <= 0) return;
        long[] c = FormationRules.clampCounts(cap, draft.infantry, draft.lancer,
                draft.marksman);
        draft.infantry = c[0];
        draft.lancer = c[1];
        draft.marksman = c[2];
        if (draft.isPercentage()) {
            applyRatiosToCounts(draft, cap);
        }
    }

    /** 比例重分配：changed 项设为 pct，其余两项按原比例分摊 (100-pct)，余数给弓。 */
    private static int[] rebalance(int[] cur, String key, int pct) {
        int[] r = new int[]{Math.max(0, cur[0]), Math.max(0, cur[1]), Math.max(0, cur[2])};
        int idx = 0;
        if (FormationBoardView.KEY_LANCER.equals(key)) idx = 1;
        else if (FormationBoardView.KEY_MARKSMAN.equals(key)) idx = 2;
        r[idx] = Math.max(0, Math.min(100, pct));
        int remain = 100 - r[idx];
        int a = (idx + 1) % 3;
        int b = (idx + 2) % 3;
        int oldA = r[a];
        int oldB = r[b];
        int oldSum = oldA + oldB;
        if (remain <= 0) {
            r[a] = 0;
            r[b] = 0;
        } else if (oldSum <= 0) {
            r[a] = remain / 2;
            r[b] = remain - r[a];
        } else {
            int na = (int) Math.round(remain * (oldA * 1.0 / oldSum));
            na = Math.max(0, Math.min(remain, na));
            r[a] = na;
            r[b] = remain - na;
        }
        return r;
    }

    // ==================== 小工具 ====================

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}
