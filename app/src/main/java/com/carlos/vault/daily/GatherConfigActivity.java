package com.carlos.vault.daily;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.carlos.vault.AccountStore;
import com.carlos.vault.UiStyle;
import com.carlos.vault.VaultSync;
import com.carlos.vault.daily.gather.GatherConfigV2;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 采集设置页（批次12，2026-10-24）：6 队列 × (启用/资源/等级/编队/兵量) + 全局项。
 *
 * <p>数据链路：本页只通过 {@link GatherConfigV2} 读写 {@code FnParams.params(...,"gather")}，
 * 保存时 {@code applyTo()}（写 v2 + 旧键双写投影）→ {@code FnParams.saveParams} →
 * {@link VaultSync#onAccountsChanged}（3s 去抖同步服务器）；服务端从
 * {@code roleParams.<sid>.gather} 读取同一份配置。页面顶部的资产摘要读服务端
 * state 投影 {@code daily_state / srv:asset:{fpid}:{sid}}（缺失时静默显示“同步中”，
 * 等级上限回退 8，保存不受影响）。
 *
 * <p>UI 全代码构建（无 XML，复用 {@link UiStyle}）；Activity 主题/导出与同包
 * {@link FunctionActivity} 一致（manifest：exported=false + AppTheme）。
 */
public class GatherConfigActivity extends Activity {

    private static final String PREFS_STATE = "daily_state";
    private static final String STATE_KEY_PREFIX = "srv:asset:";
    /** 资产投影缺失/损坏时的等级上限兜底（与 GatherConfigV2 语义一致：未知按 8）。 */
    private static final int DEFAULT_MAX_RESOURCE_LEVEL = 8;

    private AccountStore store;
    private AccountStore.Account account;
    private String fpid = "";
    private String fnId = "gather";
    private long serverid;

    private GatherConfigV2 cfg;
    /** 页面上是否为“旧版参数迁移预览”（尚未写入 v2）。 */
    private boolean hadV2;
    private boolean dirty;

    private LinearLayout box;
    /** 未启用队列的“展开配置”状态（可折叠/紧凑）。 */
    private final Set<Integer> expanded = new LinkedHashSet<Integer>();

    private int maxResourceLevel = DEFAULT_MAX_RESOURCE_LEVEL;
    private String assetSummary = "";

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }

        store = new AccountStore(this);
        String accId = getIntent().getStringExtra(FunctionActivity.EXTRA_ACCOUNT_ID);
        String extraFn = getIntent().getStringExtra(FunctionActivity.EXTRA_FN_ID);
        if (extraFn != null && !extraFn.trim().isEmpty()) fnId = extraFn.trim();
        long sid = getIntent().getLongExtra(FunctionActivity.EXTRA_SERVERID, 0L);
        for (AccountStore.Account a : store.all()) {
            if (a != null && a.id != null && accId != null && a.id.equals(accId)) {
                account = a;
                break;
            }
        }
        if (account == null) {
            UiStyle.toast(this, "账号不存在，无法打开采集设置");
            finish();
            return;
        }
        fpid = account.fpid == null ? "" : account.fpid;
        if (sid <= 0) sid = RoleStore.activeRole(this, fpid);
        serverid = sid;
        if (fpid.isEmpty() || serverid <= 0) {
            UiStyle.toast(this, "请先选择角色区服后再配置采集");
            finish();
            return;
        }

        loadFromParams();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cfg == null || box == null) return;
        // 每次回到本页重读资产投影（服务端可能刚回写队列/等级上限）；有未保存修改时不重载配置。
        if (!dirty) {
            updateAssetSummary();
            renderContent();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    // ==================== 数据 ====================

    private void loadFromParams() {
        JSONObject params = FnParams.params(this, fpid, serverid, fnId);
        hadV2 = GatherConfigV2.hasV2(params);
        cfg = GatherConfigV2.parse(params);
        dirty = false;
        expanded.clear();
        updateAssetSummary();
    }

    /** 保存：validate → applyTo（v2+旧键双写）→ FnParams → 同步服务器 → 关页。 */
    private void save() {
        if (cfg == null) return;
        try {
            cfg.validate();
        } catch (IllegalArgumentException e) {
            UiStyle.toast(this, "无法保存：" + (e.getMessage() == null ? "配置不合法" : e.getMessage()));
            return;
        } catch (Throwable t) {
            UiStyle.toast(this, "无法保存：" + t.getMessage());
            return;
        }
        try {
            JSONObject params = FnParams.params(this, fpid, serverid, fnId);
            JSONObject next = cfg.applyTo(params);
            FnParams.saveParams(this, fpid, serverid, fnId, next);
            VaultSync.onAccountsChanged(this);
            dirty = false;
            UiStyle.toast(this, "采集配置已保存并同步服务器（下次执行生效）");
            finish();
        } catch (Throwable t) {
            UiStyle.toast(this, "保存失败：" + t.getMessage());
        }
    }

    private void markDirty() {
        dirty = true;
    }

    private void goBack() {
        if (!dirty) {
            finish();
            return;
        }
        UiStyle.showConfirmDialog(this, "放弃修改？",
                "采集配置尚未保存，返回将丢失本次修改。", "放弃修改", UiStyle.ERR_INK, this::finish);
    }

    // ==================== 资产摘要（srv:asset 投影） ====================

    /**
     * 读服务端资产投影（daily_state 的 {@code srv:asset:{fpid}:{sid}}）：
     * 王国天数 / 可采等级上限 / 普通队列占用；缺失或解析失败静默回退（上限 8）。
     */
    private void updateAssetSummary() {
        maxResourceLevel = DEFAULT_MAX_RESOURCE_LEVEL;
        assetSummary = "";
        try {
            String raw = getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
                    .getString(STATE_KEY_PREFIX + fpid + ":" + serverid, null);
            if (raw == null || raw.trim().isEmpty()) return;
            JSONObject o = new JSONObject(raw.trim());
            int kingdomDay = o.optInt("kingdomDay", o.optInt("kingdom_day", 0));
            int maxLevel = extractMaxResourceLevel(o);
            if (maxLevel <= 0) {
                maxLevel = kingdomDay > 0
                        ? GatherConfigV2.maxLevelForKingdomDay(kingdomDay) : DEFAULT_MAX_RESOURCE_LEVEL;
            }
            maxResourceLevel = GatherConfigV2.clampInt(maxLevel, GatherConfigV2.MIN_LEVEL,
                    GatherConfigV2.MAX_RESOURCE_LEVEL);

            StringBuilder sb = new StringBuilder();
            if (kingdomDay > 0) {
                sb.append("王国第 ").append(kingdomDay).append(" 天");
            }
            if (kingdomDay > 0 || maxLevel > 0) {
                appendSep(sb);
                sb.append("可采 1-").append(maxResourceLevel).append(" 级");
            }
            JSONObject queue = o.optJSONObject("queue");
            if (queue != null) {
                int cap = queue.optInt("capacity", 0);
                int occ = queue.optInt("occupied", 0);
                int idle = queue.optInt("idle", Math.max(0, cap - occ));
                if (cap > 0) {
                    appendSep(sb);
                    sb.append("普通队列 ").append(cap).append('/').append(occ)
                            .append("（空闲 ").append(idle).append('）');
                }
            }
            if (sb.length() > 0) assetSummary = sb.toString();
        } catch (Throwable ignored) {
            // 投影缺失/损坏：静默隐藏摘要，页面照常可用（等级上限按 8 提示）
        }
    }

    private static void appendSep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" · ");
    }

    /** 投影里的 maxResourceLevel 兼容 数字 / 字符串 / 按资源分组的对象三种形状。 */
    private static int extractMaxResourceLevel(JSONObject o) {
        int direct = o.optInt("maxResourceLevel", o.optInt("max_resource_level", 0));
        if (direct > 0) return direct;
        JSONObject perRes = o.optJSONObject("maxResourceLevel");
        if (perRes == null) perRes = o.optJSONObject("max_resource_level");
        if (perRes == null) return 0;
        int max = 0;
        try {
            Iterator<String> it = perRes.keys();
            while (it.hasNext()) {
                String key = it.next();
                Object v = perRes.opt(key);
                int level = 0;
                if (v instanceof Number) {
                    level = ((Number) v).intValue();
                } else if (v != null && !JSONObject.NULL.equals(v)) {
                    try {
                        level = Integer.parseInt(String.valueOf(v).trim());
                    } catch (Throwable ignored) {
                        JSONObject sub = perRes.optJSONObject(key);
                        if (sub != null) {
                            level = sub.optInt("maxLevel", sub.optInt("level", 0));
                        }
                    }
                }
                if (level > max) max = level;
            }
        } catch (Throwable ignored) {
        }
        return max;
    }

    // ==================== 页面骨架 ====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);
        root.addView(topBar());

        ScrollView scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(2), dp(18), dp(32));
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        renderContent();
        return root;
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(6));
        android.widget.FrameLayout backWrap = new android.widget.FrameLayout(this);
        backWrap.addView(new UiStyle.Icon(this, UiStyle.Icon.ARROW_LEFT, UiStyle.INK, 22),
                new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        UiStyle.press(backWrap);
        backWrap.setOnClickListener(v -> goBack());
        bar.addView(backWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("采集设置");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView save = new TextView(this);
        save.setText("保存");
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(UiStyle.WHITE);
        save.setGravity(Gravity.CENTER);
        save.setBackground(UiStyle.roundRectDp(this, UiStyle.BRAND, 12));
        UiStyle.press(save);
        save.setOnClickListener(v -> save());
        bar.addView(save, new LinearLayout.LayoutParams(dp(72), dp(38)));
        return bar;
    }

    private void renderContent() {
        if (box == null || cfg == null) return;
        box.removeAllViews();
        box.addView(explainCard());
        box.addView(assetCard());
        box.addView(globalCard());

        box.addView(UiStyle.sectionTitle(this, "采集队列"));
        for (int id = 1; id <= GatherConfigV2.QUEUE_COUNT; id++) {
            GatherConfigV2.QueueCfg q = cfg.queue(id);
            if (q == null) continue;
            box.addView(queueCard(q));
        }

        box.addView(UiStyle.sectionTitle(this, "批量操作"));
        box.addView(actionButton("应用队列1到全部", this::applyQueueOneToAll));
        box.addView(actionButton("恢复默认（旧行为等价）", this::resetDefaults));
        box.addView(actionButton("旧版参数（兼容） →", this::openLegacyParams));

        if (!hadV2) {
            TextView migrate = hintText("当前为旧版参数迁移预览（尚未写入 v2）：点右上角「保存」后启用 6 队列配置。");
            migrate.setTextColor(UiStyle.WARN_INK);
            migrate.setPadding(dp(4), dp(10), dp(4), 0);
            box.addView(migrate);
        }
        TextView footer = hintText("配置写入 roleParams.gather.v2（旧键双写兼容）· 保存后 3 秒内同步服务器");
        footer.setTextColor(UiStyle.INK3);
        footer.setPadding(dp(4), dp(14), dp(4), 0);
        box.addView(footer);
    }

    // ==================== 卡片：说明 / 资产 / 全局 ====================

    private View explainCard() {
        LinearLayout card = shellCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(UiStyle.iconTile(this, UiStyle.Icon.LIGHTNING, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 40, 20, 12),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        col.addView(titleText("采集设置", 15));
        TextView desc = hintText("6 条队列按空闲顺序依次派队，服务端执行。每个资源点从目标等级逐级向下搜索，保存后下次执行生效。");
        desc.setPadding(0, dp(3), 0, 0);
        col.addView(desc);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return card;
    }

    private View assetCard() {
        LinearLayout card = shellCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(UiStyle.iconTile(this, UiStyle.Icon.CLOUD, UiStyle.BRAND_TEXT,
                UiStyle.BRAND_50, 36, 18, 10),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        col.addView(titleText("资产摘要", 13));
        TextView line = hintText(assetSummary.isEmpty()
                ? "资产数据同步中（配置仍可保存，等级上限暂按 8 级提示）" : assetSummary);
        line.setTextColor(assetSummary.isEmpty() ? UiStyle.WARN_INK : UiStyle.INK2);
        line.setPadding(0, dp(3), 0, 0);
        col.addView(line);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return card;
    }

    private View globalCard() {
        final LinearLayout card = shellCard();
        fillGlobalCard(card);
        return card;
    }

    private void fillGlobalCard(final LinearLayout card) {
        card.removeAllViews();
        card.addView(titleText("全局", 15));
        TextView sum = hintText(cfg.summary());
        sum.setPadding(0, dp(2), 0, dp(6));
        card.addView(sum);

        final UiStyle.Switch[] fullRef = new UiStyle.Switch[1];
        fullRef[0] = new UiStyle.Switch(this, cfg.fullResourceOnly, () -> {
            cfg.fullResourceOnly = fullRef[0].isOn();
            markDirty();
            fillGlobalCard(card);
        });
        card.addView(switchRow("只采满矿",
                "只给已满矿的资源点派队（对齐旧版「只采满矿」策略）", fullRef[0]));
        card.addView(UiStyle.divider(this));
        card.addView(UiStyle.dropdownRow(this, "最多使用队列数", maxQueuesText(),
                () -> chooseMaxQueues(card)));
        card.addView(UiStyle.divider(this));
        final UiStyle.Switch[] amRef = new UiStyle.Switch[1];
        amRef[0] = new UiStyle.Switch(this, cfg.allianceMineEnabled, () -> {
            cfg.allianceMineEnabled = amRef[0].isOn();
            markDirty();
            fillGlobalCard(card);
        });
        card.addView(switchRow("联盟矿采集",
                "联盟矿专属队列（配置仅保存，本期未开放自动执行）", amRef[0]));
        TextView amTip = hintText("※ 联盟矿自动执行本期未开放，开关仅保存供后续版本使用。");
        amTip.setTextColor(UiStyle.WARN_INK);
        amTip.setPadding(0, dp(2), 0, dp(2));
        card.addView(amTip);
    }

    private String maxQueuesText() {
        return cfg.maxQueues <= 0 ? "不限（按启用队列）" : cfg.maxQueues + " 队";
    }

    private void chooseMaxQueues(final LinearLayout card) {
        final String[] options = new String[GatherConfigV2.QUEUE_COUNT + 1];
        options[0] = "不限（按启用队列数）";
        for (int i = 1; i <= GatherConfigV2.QUEUE_COUNT; i++) options[i] = i + " 队";
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("最多使用队列数")
                .setSingleChoiceItems(options, cfg.maxQueues, (d, which) -> {
                    cfg.maxQueues = which;
                    markDirty();
                    d.dismiss();
                    fillGlobalCard(card);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 卡片：单队列 ====================

    private View queueCard(final GatherConfigV2.QueueCfg q) {
        final LinearLayout card = shellCard();
        fillQueueCard(card, q);
        return card;
    }

    private void fillQueueCard(final LinearLayout card, final GatherConfigV2.QueueCfg q) {
        card.removeAllViews();
        final Runnable refresh = () -> fillQueueCard(card, q);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headCol = new LinearLayout(this);
        headCol.setOrientation(LinearLayout.VERTICAL);
        headCol.addView(titleText("#" + q.id + " 队列", 15));
        TextView sum = hintText(queueSummary(q));
        sum.setPadding(0, dp(2), 0, 0);
        headCol.addView(sum);
        head.addView(headCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final UiStyle.Switch[] swRef = new UiStyle.Switch[1];
        swRef[0] = new UiStyle.Switch(this, q.enabled, () -> {
            q.enabled = swRef[0].isOn();
            markDirty();
            if (q.enabled) expanded.add(q.id);
            refresh.run();
        });
        head.addView(swRef[0]);
        card.addView(head);

        final boolean showBody = q.enabled || expanded.contains(q.id);
        if (!showBody) {
            TextView open = new TextView(this);
            open.setText("展开配置 ▾");
            open.setTextSize(12);
            open.setTypeface(Typeface.DEFAULT_BOLD);
            open.setTextColor(UiStyle.BRAND_TEXT);
            open.setPadding(0, dp(6), 0, dp(2));
            UiStyle.press(open);
            open.setOnClickListener(v -> {
                expanded.add(q.id);
                refresh.run();
            });
            card.addView(open);
            return;
        }

        card.addView(UiStyle.divider(this));

        // 资源
        card.addView(UiStyle.dropdownRow(this, "资源",
                GatherConfigV2.resourceLabel(q.resourceType), () -> chooseResource(q, refresh)));
        // 目标等级（>可采上限的选项加提示，但允许保存：服务端逐级下搜天然兜底）
        card.addView(UiStyle.dropdownRow(this, "目标等级", levelText(q.level),
                () -> chooseLevel(q, refresh)));
        // 编队模式
        TextView modeLabel = hintText("编队模式");
        modeLabel.setPadding(0, dp(8), 0, dp(4));
        card.addView(modeLabel);
        card.addView(UiStyle.segmented(this, new String[]{"自动编队", "指定编队"},
                q.isRole() ? 1 : 0, idx -> {
                    if (idx == 0) {
                        q.formationMode = GatherConfigV2.MODE_AUTOMATIC;
                        q.formationId = 0L;
                        markDirty();
                        refresh.run();
                    } else {
                        pickFormationForQueue(q, refresh);
                    }
                }));
        if (q.isRole()) {
            card.addView(UiStyle.dropdownRow(this, "使用编队",
                    formationText(q.formationId), () -> pickFormationForQueue(q, refresh)));
            if (FormationStore.list(this, fpid, serverid).isEmpty()) {
                TextView go = new TextView(this);
                go.setText("暂无编队：请先在功能页「管理编队」新建 ›");
                go.setTextSize(11.5f);
                go.setTypeface(Typeface.DEFAULT_BOLD);
                go.setTextColor(UiStyle.BRAND_TEXT);
                go.setPadding(0, dp(6), 0, dp(2));
                UiStyle.press(go);
                go.setOnClickListener(v -> FormationPicker.showLibrary(this, fpid, serverid,
                        () -> refresh.run()));
                card.addView(go);
            }
        }
        // 兵量（0=默认，保存时回填默认 40000/30000/30000）
        card.addView(UiStyle.dropdownRow(this, "兵量", soldiersText(q),
                () -> showSoldiersDialog(q, refresh)));

        if (!q.enabled) {
            TextView collapse = new TextView(this);
            collapse.setText("收起 ▴");
            collapse.setTextSize(12);
            collapse.setTypeface(Typeface.DEFAULT_BOLD);
            collapse.setTextColor(UiStyle.INK2);
            collapse.setPadding(0, dp(8), 0, dp(2));
            UiStyle.press(collapse);
            collapse.setOnClickListener(v -> {
                expanded.remove(q.id);
                refresh.run();
            });
            card.addView(collapse);
        }
    }

    private String queueSummary(GatherConfigV2.QueueCfg q) {
        String mode;
        if (q.isRole()) {
            mode = "指定编队" + (q.formationId > 0 ? "#" + q.formationId : "未选");
        } else {
            mode = "自动编队";
        }
        return GatherConfigV2.resourceLabel(q.resourceType) + q.level + " · " + mode
                + " · " + (q.enabled ? "已启用" : "未启用");
    }

    private String levelText(int level) {
        String text = level + " 级";
        if (level > maxResourceLevel) text += "（需开服701天）";
        return text;
    }

    private String formationText(long formationId) {
        if (formationId <= 0) return "未选择";
        FormationStore.Formation f = FormationStore.byId(this, fpid, serverid, formationId);
        if (f == null) return "#" + formationId + "（编队已删除，请重选）";
        return "#" + f.id + " " + f.name;
    }

    private String soldiersText(GatherConfigV2.QueueCfg q) {
        if (q.infantry == GatherConfigV2.DEFAULT_INFANTRY
                && q.lancer == GatherConfigV2.DEFAULT_LANCER
                && q.marksman == GatherConfigV2.DEFAULT_MARKSMAN) {
            return "默认 " + GatherConfigV2.DEFAULT_INFANTRY + "/"
                    + GatherConfigV2.DEFAULT_LANCER + "/"
                    + GatherConfigV2.DEFAULT_MARKSMAN;
        }
        return "自定义 盾" + q.infantry + "/矛" + q.lancer + "/弓" + q.marksman;
    }

    private void chooseResource(final GatherConfigV2.QueueCfg q, final Runnable refresh) {
        final String[] keys = {GatherConfigV2.RES_MEAT, GatherConfigV2.RES_WOOD,
                GatherConfigV2.RES_COAL, GatherConfigV2.RES_IRON};
        final String[] names = {"肉", "木", "煤", "铁"};
        int cur = 0;
        String normalized = GatherConfigV2.normalizeResource(q.resourceType);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(normalized)) {
                cur = i;
                break;
            }
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("采集资源")
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    q.resourceType = keys[which];
                    markDirty();
                    d.dismiss();
                    refresh.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseLevel(final GatherConfigV2.QueueCfg q, final Runnable refresh) {
        final String[] options = new String[GatherConfigV2.MAX_RESOURCE_LEVEL];
        for (int i = 0; i < options.length; i++) {
            int level = i + 1;
            options[i] = level + " 级" + (level > maxResourceLevel ? "（需开服701天）" : "");
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("目标等级（从该级逐级向下搜索）")
                .setSingleChoiceItems(options, q.level - 1, (d, which) -> {
                    q.level = which + 1;
                    markDirty();
                    d.dismiss();
                    refresh.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickFormationForQueue(final GatherConfigV2.QueueCfg q, final Runnable refresh) {
        FormationPicker.pickFormationFor(this, fpid, serverid,
                (formationId, formation) -> {
                    if (formationId > 0) {
                        q.formationMode = GatherConfigV2.MODE_ROLE;
                        q.formationId = formationId;
                        markDirty();
                        if (formation != null) {
                            UiStyle.toast(this, "队列" + q.id + " 使用编队 #"
                                    + formation.id + " " + formation.name);
                        }
                    }
                    refresh.run();
                });
    }

    /** 兵量自定义：三输入弹窗（0/空 = 用默认 40000/30000/30000）。 */
    private void showSoldiersDialog(final GatherConfigV2.QueueCfg q, final Runnable refresh) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText infantry = numField("盾兵", q.infantry);
        final EditText lancer = numField("矛兵", q.lancer);
        final EditText marksman = numField("弓兵", q.marksman);
        row.addView(labeledField("盾兵", infantry), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labeledField("矛兵", lancer), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labeledField("弓兵", marksman), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        TextView tip = hintText("留空或 0 = 使用默认 " + GatherConfigV2.DEFAULT_INFANTRY + "/"
                + GatherConfigV2.DEFAULT_LANCER + "/" + GatherConfigV2.DEFAULT_MARKSMAN
                + "；自定义值按队列生效（指定编队时覆盖编队库兵量）。");
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle("队列 " + q.id + " 兵量")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    long inf = numOf(infantry);
                    long lan = numOf(lancer);
                    long mar = numOf(marksman);
                    q.infantry = inf > 0 ? inf : GatherConfigV2.DEFAULT_INFANTRY;
                    q.lancer = lan > 0 ? lan : GatherConfigV2.DEFAULT_LANCER;
                    q.marksman = mar > 0 ? mar : GatherConfigV2.DEFAULT_MARKSMAN;
                    markDirty();
                    refresh.run();
                    UiStyle.toast(this, "队列" + q.id + " 兵量已更新（保存后同步）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 批量操作 ====================

    /** 队列1的资源/等级/兵量复制到全部（指定编队不可复用，编队设置保持原样）。 */
    private void applyQueueOneToAll() {
        GatherConfigV2.QueueCfg first = cfg.queue(1);
        if (first == null) return;
        boolean role = first.isRole();
        for (int id = 2; id <= GatherConfigV2.QUEUE_COUNT; id++) {
            GatherConfigV2.QueueCfg q = cfg.queue(id);
            if (q == null) continue;
            q.resourceType = first.resourceType;
            q.level = first.level;
            q.infantry = first.infantry;
            q.lancer = first.lancer;
            q.marksman = first.marksman;
            if (!role) {
                q.formationMode = GatherConfigV2.MODE_AUTOMATIC;
                q.formationId = 0L;
            }
        }
        markDirty();
        renderContent();
        UiStyle.toast(this, role
                ? "已将队列1的资源/等级/兵量应用到全部（指定编队不可复用，编队保持原样）"
                : "已将队列1配置应用到全部队列");
    }

    /** 恢复旧行为等价默认（legacyDefaults）：队列1-3启用、肉/木、5级、自动编队、任意存量。 */
    private void resetDefaults() {
        UiStyle.showConfirmDialog(this, "恢复默认（旧行为等价）？",
                "将恢复为旧版行为等价默认：队列 1-3 启用、肉/木、5 级、自动编队、任意存量。\n"
                        + "恢复后需点右上角「保存」才生效。",
                "恢复默认", UiStyle.BRAND_TEXT, () -> {
                    cfg = GatherConfigV2.legacyDefaults();
                    dirty = true;
                    expanded.clear();
                    renderContent();
                    UiStyle.toast(this, "已恢复默认配置，点右上角「保存」生效");
                });
    }

    /** 旧版参数弹窗（兼容/回滚）：保存后重新读取旧键（v2 不被改动）。 */
    private void openLegacyParams() {
        if (dirty) {
            UiStyle.showConfirmDialog(this, "打开旧版参数？",
                    "旧版参数保存后会重新加载配置，本页未保存的修改将被丢弃。",
                    "继续", UiStyle.BRAND_TEXT, this::showLegacyParams);
            return;
        }
        showLegacyParams();
    }

    private void showLegacyParams() {
        FunctionActivity.showLegacyGatherDialog(this, fpid, serverid, () -> {
            try {
                loadFromParams();
                renderContent();
                UiStyle.toast(this, "旧版参数已保存（兼容旧服务端）；新版 6 队列配置未改动");
            } catch (Throwable ignored) {
            }
        });
    }

    // ==================== 基础组件 ====================

    private LinearLayout shellCard() {
        LinearLayout card = UiStyle.outlineCard(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView titleText(String text, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiStyle.INK);
        return t;
    }

    private TextView hintText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTextColor(UiStyle.INK2);
        return t;
    }

    /** 行：标题/副题 + 右侧控件（Switch）。 */
    private LinearLayout switchRow(String title, String sub, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(titleText(title, 13));
        TextView s = hintText(sub);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(right);
        return row;
    }

    /** 底部批量操作按钮（白底 brand 描边，整行）。 */
    private TextView actionButton(String text, Runnable onClick) {
        TextView btn = UiStyle.secondaryBtn(this, text, onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private LinearLayout labeledField(String label, EditText et) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        col.setLayoutParams(lp);
        TextView t = hintText(label);
        col.addView(t);
        col.addView(et);
        return col;
    }

    private EditText numField(String hint, long value) {
        EditText et = UiStyle.searchInput(this, hint);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(value > 0 ? String.valueOf(value) : "");
        return et;
    }

    private long numOf(EditText et) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) return 0L;
            return Math.max(0L, Long.parseLong(s));
        } catch (Throwable t) {
            return 0L;
        }
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
