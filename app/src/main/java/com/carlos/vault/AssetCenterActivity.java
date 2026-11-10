package com.carlos.vault;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 资产中心（核心资产库可视化，W9）：把服务端资产投影 {@code daily_state / srv:asset:{fpid}:{sid}}
 * 的 9 大域一次性展示——角色/王国/队列/资源/体力/情报/巨兽/巨熊/战争/驻防/活动/背包/数据健康。
 *
 * <p>同一份投影被采集设置页、功能页摘要复用；本页只读、不写配置（配置入口仍在功能页）。
 * 投影缺失时显示「同步中」，不影响 App 其它页面。
 */
public class AssetCenterActivity extends Activity {

    private static final String PREFS_STATE = "daily_state";
    private static final String STATE_KEY_PREFIX = "srv:asset:";

    private String fpid = "";
    private long serverid;
    private long uid;
    private LinearLayout box;
    private ScrollView scrollView;
    private JSONObject projection;
    private String lastProjectionRaw = "";
    private long lastServerFetchAt;
    private final Handler autoRefresh = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            try {
                syncRoleAndReload();
            } catch (Throwable ignored) {
            }
            if (!isFinishing()) autoRefresh.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        resolveRole(getIntent());
        setContentView(buildUi());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 切换角色后返回本页 / 前台期间切角色：跟随 home:current_role 重新加载投影
        syncRoleAndReload();
        refreshFromServer();
        autoRefresh.removeCallbacks(refreshTick);
        autoRefresh.postDelayed(refreshTick, 3000L);
    }

    @Override
    protected void onPause() {
        autoRefresh.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        autoRefresh.removeCallbacks(refreshTick);
        super.onDestroy();
    }

    /**
     * 角色跟随：每次以前台 home:current_role 为准（切换角色会立即改写该键），
     * 角色变化 → 重新解析投影；角色不变但投影更新（version/updatedAt 变化）→ 原地刷新。
     * 刷新时保留滚动位置，避免 3s 自动刷新把页面拉回顶部。
     */
    /**
     * 主动拉一次服务端 state（server 模式的 daily_state 回写）：切换角色/刚登录时本地
     * prefs 可能还是旧角色的投影，必须拉最新再渲染；10s 内重复调用只加载本地缓存。
     */
    private void refreshFromServer() {
        long now = System.currentTimeMillis();
        if (now - lastServerFetchAt > 10_000L) {
            lastServerFetchAt = now;
            try {
                com.carlos.vault.VaultSync.fetchState(this, (code, message, data) ->
                        runOnUiThread(this::load));
                return;
            } catch (Throwable ignored) {
            }
        }
        load();
    }

    private void syncRoleAndReload() {
        String key = currentRoleKey();
        if (key != null && key.contains(":")) {
            try {
                int i = key.indexOf(':');
                String fp = key.substring(0, i);
                String rest = key.substring(i + 1);
                int j = rest.indexOf(':');
                long sid = Long.parseLong(j < 0 ? rest : rest.substring(0, j));
                long u = 0L;
                if (j >= 0) {
                    String us = rest.substring(j + 1);
                    int k = us.indexOf(':');
                    u = Long.parseLong(k < 0 ? us : us.substring(0, k));
                }
                if (!fp.isEmpty() && sid > 0L
                        && (!fp.equals(fpid) || sid != serverid || (u > 0L && u != uid))) {
                    fpid = fp;
                    serverid = sid;
                    if (u > 0L) uid = u;
                    load();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 角色未变：投影有更新才重绘
        try {
            String raw = readProjectionRaw();
            if (raw != null && !raw.equals(lastProjectionRaw)) {
                int y = scrollView == null ? 0 : scrollView.getScrollY();
                lastProjectionRaw = raw;
                load();
                if (scrollView != null) {
                    final int restoreY = y;
                    scrollView.post(() -> scrollView.scrollTo(0, restoreY));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String currentRoleKey() {
        try {
            return getSharedPreferences("home", MODE_PRIVATE)
                    .getString("current_role", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 角色解析：优先前台 current_role（切换角色立即生效），其次 Intent extras 兜底。 */
    private void resolveRole(Intent intent) {
        try {
            if (intent != null) {
                fpid = intent.getStringExtra("fpid");
                serverid = intent.getLongExtra("sid", 0L);
                uid = intent.getLongExtra("uid", 0L);
            }
        } catch (Throwable ignored) {
        }
        if (fpid == null || fpid.trim().isEmpty() || serverid <= 0L) {
            try {
                // 回退当前角色：home:current_role 格式 "fpid:sid" / "fpid:sid:uid"
                String key = getSharedPreferences("home", MODE_PRIVATE)
                        .getString("current_role", null);
                if (key != null && key.contains(":")) {
                    int i = key.indexOf(':');
                    fpid = key.substring(0, i);
                    String rest = key.substring(i + 1);
                    int j = rest.indexOf(':');
                    serverid = Long.parseLong(j < 0 ? rest : rest.substring(0, j));
                    if (j >= 0) {
                        String u = rest.substring(j + 1);
                        int k = u.indexOf(':');
                        uid = Long.parseLong(k < 0 ? u : u.substring(0, k));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (fpid == null) fpid = "";
    }

    // ==================== 生命周期/数据 ====================

    private String readProjectionRaw() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS_STATE, MODE_PRIVATE);
            String raw = null;
            if (uid > 0L) {
                raw = sp.getString(STATE_KEY_PREFIX + fpid + ":" + serverid + ":" + uid, null);
            }
            if (raw == null || raw.trim().isEmpty()) {
                raw = sp.getString(STATE_KEY_PREFIX + fpid + ":" + serverid, null);
            }
            return raw == null ? "" : raw;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void load() {
        projection = null;
        try {
            String raw = readProjectionRaw();
            if (raw != null && !raw.trim().isEmpty()) {
                projection = new JSONObject(raw.trim());
            }
            lastProjectionRaw = raw == null ? "" : raw;
        } catch (Throwable ignored) {
        }
        renderContent();
    }

    // ==================== UI ====================

    private View buildUi() {
        TextView refresh = UiComponents.compactSecondaryButton(this, "刷新", this::refreshFromServer);
        UiComponents.Page page = UiComponents.scrollPage(
                this, "资产中心", null, this::finish, 40, refresh);
        scrollView = page.scroll;
        box = page.body;
        return page.root;
    }

    private void renderContent() {
        if (box == null) return;
        box.removeAllViews();
        if (projection == null) {
            box.addView(emptyCard("资产库同步中",
                    "服务端资产投影尚未返回（角色登录后 3-5 秒内生成）。下拉刷新或稍后重试。"));
            return;
        }
        boolean ready = projection.optBoolean("ready", false);
        int version = projection.optInt("version", 0);
        box.addView(healthCard(ready, version));
        box.addView(roleCard());
        box.addView(kingdomCard());
        box.addView(queueCard());
        box.addView(resourceCard());
        box.addView(intelCard());
        box.addView(behemothBearCard());
        box.addView(warGuardianCard());
        box.addView(calendarBackpackCard());
    }

    private View healthCard(boolean ready, int version) {
        LinearLayout card = UiComponents.card(this);
        card.addView(title("数据健康", 15));
        card.addView(hint("角色键 " + fpid + ":" + serverid + (uid > 0L ? ":" + uid : "")
                + (uid > 0L ? "（按 fid 独立投影）" : "（旧键，可能同服多角色覆盖）")));
        String status = ready ? "就绪 · 版本 " + version : "同步中 · 版本 " + version;
        TextView line = hint(status + " · 更新于 " + fmtTs(projection.optLong("updatedAt", 0)));
        line.setTextColor(ready ? UiStyle.INK2 : UiStyle.WARN_INK);
        card.addView(line);
        JSONArray missing = projection.optJSONArray("missing");
        if (missing != null && missing.length() > 0) {
            StringBuilder sb = new StringBuilder("缺协议：");
            for (int i = 0; i < missing.length() && i < 8; i++) {
                if (i > 0) sb.append("、");
                sb.append(missing.optString(i));
            }
            TextView miss = hint(sb.toString());
            miss.setTextColor(UiStyle.WARN_INK);
            card.addView(miss);
        }
        return card;
    }

    private View roleCard() {
        JSONObject meta = projection.optJSONObject("meta");
        JSONObject city = projection.optJSONObject("city");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("角色", 15));
        if (meta == null) {
            card.addView(hint("角色信息未同步"));
            return card;
        }
        String nick = meta.optString("nickName", "");
        card.addView(kv("昵称", nick.isEmpty() ? "（未同步）" : nick
                + "  (fid " + projection.optLong("uid", 0) + ")"));
        String alliance = meta.optString("allianceName", "");
        if (!alliance.isEmpty()) {
            card.addView(kv("联盟", alliance + meta.optString("allianceAbbr", "")));
        }
        card.addView(kv("等级/熔炉/战力", meta.optLong("level", 0) + " / "
                + meta.optLong("stoveLv", 0) + " / " + meta.optLong("power", 0)));
        long x = city == null ? 0 : city.optLong("x", 0);
        long y = city == null ? 0 : city.optLong("y", 0);
        if (x > 0 || y > 0) card.addView(kv("城市坐标", "(" + x + ", " + y + ")"));
        return card;
    }

    private View kingdomCard() {
        LinearLayout card = UiComponents.card(this);
        card.addView(title("王国", 15));
        int day = projection.optInt("kingdomDay", 0);
        int maxLevel = projection.optInt("maxResourceLevel", 0);
        card.addView(kv("开服天数", day > 0 ? "第 " + day + " 天" : "未知"));
        card.addView(kv("可采资源等级", maxLevel > 0 ? "1-" + maxLevel + " 级" : "未知"));
        card.addView(kv("服务器时间", fmtTs(projection.optLong("serverNow", 0) * 1000L)));
        return card;
    }

    private View queueCard() {
        JSONObject q = projection.optJSONObject("queue");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("队列", 15));
        if (q == null) {
            card.addView(hint("队列未同步"));
            return card;
        }
        int cap = q.optInt("capacity", 0);
        int occ = q.optInt("occupied", 0);
        int idle = q.optInt("idle", Math.max(0, cap - occ));
        card.addView(kv("普通队列", (cap > 0 ? cap : "?") + " 上限 · "
                + occ + " 占用 · " + idle + " 空闲"));
        JSONObject byType = q.optJSONObject("byType");
        if (byType != null && byType.length() > 0) {
            card.addView(kv("在途类型", joinCounts(byType)));
        }
        JSONObject special = q.optJSONObject("specialByType");
        if (special != null && special.length() > 0) {
            card.addView(kv("特殊队列", joinCounts(special)));
        }
        card.addView(kv("数据来源", q.optString("source", "?") + " · "
                + fmtTs(q.optLong("updatedAt", 0))));
        return card;
    }

    private View resourceCard() {
        JSONObject r = projection.optJSONObject("resources");
        JSONObject st = projection.optJSONObject("stamina");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("资源", 15));
        if (r != null) {
            card.addView(kv("钻石", fmtNum(r.optLong("diamond", 0))));
            card.addView(kv("肉/木/煤/铁", fmtNum(r.optLong("meat", 0)) + " / "
                    + fmtNum(r.optLong("wood", 0)) + " / " + fmtNum(r.optLong("coal", 0))
                    + " / " + fmtNum(r.optLong("iron", 0))));
            card.addView(kv("钢/联盟币", fmtNum(r.optLong("steel", 0)) + " / "
                    + fmtNum(r.optLong("allianceCoin", 0))));
        }
        if (st != null) {
            long cur = st.optLong("currentAt", st.optLong("current", 0));
            long max = st.optLong("max", 0);
            card.addView(kv("体力", cur + (max > 0 ? " / " + max : "")));
        }
        return card;
    }

    private View intelCard() {
        JSONObject i = projection.optJSONObject("intel");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("情报", 15));
        if (i == null) {
            card.addView(hint("情报未同步"));
            return card;
        }
        card.addView(kv("雷达/战力等级", i.optLong("radarLevel", 0) + " / "
                + i.optLong("powerLevel", 0)));
        card.addView(kv("待办事件", "可派 " + i.optInt("dispatchable", 0)
                + " · 可领 " + i.optInt("claimable", 0)
                + " · 战斗中 " + i.optInt("fighting", 0)
                + "（共 " + i.optInt("total", 0) + "）"));
        long next = i.optLong("nextEventTime", 0);
        if (next > 0) card.addView(kv("下次刷新", fmtTs(next * 1000L)));
        JSONArray events = i.optJSONArray("events");
        if (events != null && events.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < events.length() && k < 5; k++) {
                JSONObject ev = events.optJSONObject(k);
                if (ev == null) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append("· #").append(ev.optLong("id", 0))
                        .append(" status=").append(ev.optLong("status", 0))
                        .append(" (").append(ev.optLong("x", 0)).append(",")
                        .append(ev.optLong("y", 0)).append(")");
            }
            TextView ev = hint(sb.toString());
            card.addView(ev);
        }
        return card;
    }

    private View behemothBearCard() {
        JSONObject b = projection.optJSONObject("behemoth");
        JSONObject bear = projection.optJSONObject("bear");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("巨兽 / 巨熊", 15));
        if (b != null) {
            card.addView(kv("巨兽", "最高 " + b.optLong("monsterMaxLv", 0) + " 级 · 可加入 "
                    + b.optInt("joinCount", 0) + " 场 · 可发起 " + b.optInt("masterCount", 0)));
        }
        if (bear != null) {
            boolean known = bear.optBoolean("reserveKnown", false);
            card.addView(kv("巨熊预约", known
                    ? fmtTs(bear.optLong("reserveTime", 0) * 1000L) : "未预约/未知"));
            long massStart = bear.optLong("massStartTime", 0);
            if (massStart > 0) {
                card.addView(kv("集火窗口", fmtTs(massStart * 1000L) + " → "
                        + fmtTs(bear.optLong("massEndTime", 0) * 1000L)));
            }
        }
        return card;
    }

    private View warGuardianCard() {
        JSONObject war = projection.optJSONObject("war");
        JSONObject g = projection.optJSONObject("guardian");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("战争 / 驻防", 15));
        if (war != null) {
            card.addView(kv("态势行军", "联盟域 " + war.optInt("allianceMarches", 0)
                    + " · 世界域 " + war.optInt("worldMarches", 0)
                    + " · 战力条目 " + war.optInt("powers", 0)));
            long mailAt = war.optLong("lastMailAt", 0);
            if (mailAt > 0) {
                card.addView(kv("最近战报", "类型 " + war.optLong("lastMailFirstType", 0)
                        + "/" + war.optLong("lastMailSecondType", 0)
                        + " · " + fmtTs(mailAt)));
            }
        }
        if (g != null) {
            card.addView(kv("驻防队友", "盟友 " + g.optInt("allies", 0)
                    + " · 驻防队 " + g.optInt("garrisons", 0)
                    + " · 常驻戒备 " + g.optInt("holds", 0)));
            JSONArray list = g.optJSONArray("garrisonList");
            if (list != null && list.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < list.length() && k < 5; k++) {
                    JSONObject it = list.optJSONObject(k);
                    if (it == null) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("· march#").append(it.optLong("marchId", 0))
                            .append(" target=").append(it.optLong("targetUid", 0))
                            .append(" state=").append(it.optLong("state", 0))
                            .append(" 结束=").append(fmtTs(it.optLong("endTime", 0) * 1000L));
                }
                card.addView(hint(sb.toString()));
            }
        }
        return card;
    }

    private View calendarBackpackCard() {
        JSONObject cal = projection.optJSONObject("calendar");
        JSONObject bp = projection.optJSONObject("backpack");
        LinearLayout card = UiComponents.card(this);
        card.addView(title("活动 / 背包", 15));
        if (cal != null) {
            card.addView(kv("活动", "活跃 " + cal.optInt("active", 0)
                    + " 个 · 时间段 " + cal.optInt("schedules", 0)
                    + (cal.optLong("nextEnd", 0) > 0
                    ? " · 最近结束 " + fmtTs(cal.optLong("nextEnd", 0) * 1000L) : "")));
        }
        if (bp != null) {
            card.addView(kv("背包", bp.optInt("items", 0) + " 种道具"
                    + " · 加速 8h=" + bp.optLong("speedup302000", 0)
                    + " 24h=" + bp.optLong("speedup302001", 0)
                    + " · 体力药=" + bp.optLong("stamina610002", 0)));
        }
        return card;
    }

    private View emptyCard(String title, String message) {
        LinearLayout card = UiComponents.card(this);
        card.addView(title(title, 16));
        card.addView(hint(message));
        return card;
    }

    // ==================== 小工具 ====================

    private TextView title(String text, int size) {
        TextView tv = UiComponents.text(this, text, size, UiStyle.INK, true);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = UiComponents.text(this, text, 12.5f, UiStyle.INK2, false);
        tv.setPadding(0, dp(1), 0, dp(3));
        return tv;
    }

    private View kv(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(12.5f);
        k.setTextColor(UiStyle.INK3);
        row.addView(k, new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = UiComponents.numeric(this, value, 13f, UiStyle.INK, true);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private static String joinCounts(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        try {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (sb.length() > 0) sb.append(" · ");
                sb.append("type").append(key).append("=").append(o.optInt(key, 0));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String fmtTs(long ms) {
        if (ms <= 0) return "—";
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
        } catch (Throwable ignored) {
            return String.valueOf(ms / 1000L);
        }
    }

    private static String fmtNum(long v) {
        if (v >= 1_000_000L) return String.format(Locale.CHINA, "%.1fM", v / 1_000_000.0);
        if (v >= 10_000L) return String.format(Locale.CHINA, "%.1fW", v / 10_000.0);
        return String.valueOf(v);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
