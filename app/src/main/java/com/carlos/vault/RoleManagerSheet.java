package com.carlos.vault;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.daily.HotConfig;
import com.carlos.vault.daily.RoleDisplay;
import com.carlos.vault.daily.RoleIdentity;
import com.carlos.vault.daily.RoleLogin;
import com.carlos.vault.daily.RoleSessionKeeper;
import com.carlos.vault.daily.RoleStore;
import com.carlos.vault.daily.WjdrAndroid;
import com.carlos.vault.wjdr.WjdrEngine;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ⚙ 角色管理<b>底部弹层</b>（第二轮 UI 统一，2026-10-20 从 HomeActivity 抽成可复用组件：
 * 账号页账号框、账号页 ⋯ 菜单、账号详情页「管理」三处共用同一份实现）。
 *
 * <p>结构：拖拽条 → 头（账号名 + 副题「共 N 个角色，已加入 M」+ 关闭）
 * → 角色列表（全部角色：名称 + {@link RoleDisplay#roleMetaLine} 标准元数据行 +
 * 统一状态行 + 开关；开关按 (sid,uid) 独立）→ 底部「+ 添加角色（发现）/ 完成」。
 *
 * <p>行内状态口径（需求3 统一）：服务器模式读 {@link VaultSync#cachedStatusOf}
 * （按 sid+activeUid 匹配角色行，{@link RoleDisplay#roleMatchesServerStatus}）；
 * 本地模式读 {@link RoleSessionKeeper} 会话快照（fpid+sid）。文案一律走
 * {@link RoleDisplay#serverStateLabel}，与账号页/今日页同源。
 *
 * <p>实现说明：不用 BottomSheetDialog（那要 material 依赖），直接往宿主窗口 DecorView
 * 盖一层半透明遮罩 + 白色圆角面板（MATCH_PARENT 盖全窗，压住底部导航/悬浮按钮），
 * 带 320ms 上滑入场动画。
 */
public final class RoleManagerSheet {

    private RoleManagerSheet() {
    }

    /**
     * 打开角色管理弹层。
     *
     * @param act       宿主 Activity（弹层挂到其窗口 DecorView，盖全窗）
     * @param a         账号
     * @param roles     打开时的角色表快照（行内实时读盘重建，快照仅用于首帧）
     * @param onChanged 开关/发现角色落盘后的回调（调用方刷新自己的列表；可为 null）
     */
    public static void show(final Activity act, final AccountStore.Account a,
                            final List<RoleStore.Role> roles, final Runnable onChanged) {
        if (act == null || a == null) return;
        if (roles == null || roles.isEmpty()) {
            toast(act, "暂无角色数据 —— 登录一次游戏后自动发现");
            return;
        }

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setBackgroundColor(UiStyle.SCRIM);   // rgba(8,12,23,35%)，压暗背景
        overlay.setElevation(dp(act, 24));           // 必须高于悬浮导航的 elevation
        // 点遮罩空白处关闭
        overlay.setOnClickListener(v -> dismiss(overlay));

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(UiStyle.WHITE);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(UiStyle.WHITE);
        pbg.setCornerRadii(new float[]{dp(act, 24), dp(act, 24), dp(act, 24), dp(act, 24), 0, 0, 0, 0});
        panel.setBackground(pbg);
        panel.setElevation(dp(act, 16));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                UiLayoutRules.contentWidthPx(
                        act.getResources().getDisplayMetrics().widthPixels,
                        act.getResources().getDisplayMetrics().density, 640, 0),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panel.setLayoutParams(plp);

        // —— 拖拽条 ——
        View grab = new View(act);
        grab.setBackgroundColor(UiStyle.LINE_STRONG);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                dp(act, 36), dp(act, 4), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        glp.topMargin = dp(act, 9);
        FrameLayout grabWrap = new FrameLayout(act);
        grabWrap.addView(grab, glp);
        panel.addView(grabWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 13)));

        // —— 头部：角色管理 + 账号/角色数 ——
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(act, 18), dp(act, 6), dp(act, 18), dp(act, 12));
        LinearLayout hb = new LinearLayout(act);
        hb.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(act);
        title.setText("角色管理");
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        hb.addView(title);
        final TextView sub = new TextView(act);
        sub.setTextSize(12);
        sub.setTextColor(UiStyle.INK2);
        sub.setPadding(0, dp(act, 3), 0, 0);
        hb.addView(sub);
        head.addView(hb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 「发现角色」保留为隐藏入口（底部 + 添加角色 触发）
        final TextView discover = new TextView(act);
        discover.setText("发现角色");
        discover.setVisibility(View.GONE);
        panel.addView(head);

        final List<Runnable> syncSubs = new ArrayList<>();
        syncSubs.add(() -> {
            // 统计行实时读盘：行开关/发现角色落盘后重跑 syncSubs 才能反映最新数
            // （旧实现数的是打开弹层时捕获的 roles 快照，开关切了计数不动）。
            int n = roles.size(), en = 0;
            try {
                List<RoleStore.Role> cur = RoleStore.roles(act, a.fpid);
                n = cur.size();
                for (RoleStore.Role r : cur) if (r.enabled) en++;
            } catch (Throwable ignored) {
                for (RoleStore.Role r : roles) if (r.enabled) en++;
            }
            sub.setText((a.label == null ? "" : a.label) + " · " + n + " 个角色 · 已加入 " + en);
        });
        syncSubs.get(0).run();

        // —— 角色列表（可重建：发现角色后重拉全量表） ——
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        final java.util.concurrent.atomic.AtomicReference<List<RoleStore.Role>> rolesRef =
                new java.util.concurrent.atomic.AtomicReference<>(roles);
        final Runnable rebuildList = () -> {
            list.removeAllViews();
            List<RoleStore.Role> cur = rolesRef.get();
            for (final RoleStore.Role r : cur) {
                list.addView(row(act, a, r, syncSubs, cur, onChanged));
            }
        };
        rebuildList.run();
        ScrollView sc = new ScrollView(act);
        sc.addView(list);
        panel.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // —— 底部按钮：+ 添加角色（发现）/ 完成 ——
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(act, 16), dp(act, 11), dp(act, 16), dp(act, 28));
        TextView all = new TextView(act);
        all.setText("+ 添加角色");
        all.setTextSize(14);
        all.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        all.setTextColor(UiStyle.INK);
        all.setGravity(Gravity.CENTER);
        all.setBackground(UiStyle.roundRectStroke(act, UiStyle.WHITE, 14, UiStyle.LINE));
        all.setPadding(0, dp(act, 13), 0, dp(act, 13));
        UiStyle.press(all);
        all.setOnClickListener(v -> discover.performClick());
        foot.addView(all, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = new TextView(act);
        done.setText("完成");
        done.setTextSize(14);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setTextColor(UiStyle.WHITE);
        done.setGravity(Gravity.CENTER);
        done.setBackground(UiStyle.roundRect(UiStyle.BRAND, dp(act, 14)));
        done.setPadding(0, dp(act, 13), 0, dp(act, 13));
        UiStyle.press(done);
        done.setOnClickListener(v -> dismiss(overlay));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
        dlp.leftMargin = dp(act, 9);
        foot.addView(done, dlp);
        panel.addView(foot);

        // 「发现角色」：req_server_list_simple + req_role_list 全量查询（本地表只含登录过的角色，
        // 玩家在游戏里新开的区服角色不会自动出现——2026-10-12 用户反馈角色管理列表不全）。
        discover.setOnClickListener(v -> {
            discover.setEnabled(false);
            discover.setAlpha(0.5f);
            sub.setText("发现角色中…（登录游戏服务器查询全角色清单）");
            new Thread(() -> {
                String err = null;
                int found = 0, added = 0;
                try {
                    List<WjdrEngine.RoleInfo> roles0 = null;
                    // 优先借常驻会话（免登录不顶号）；无会话且容器游戏没挂本账号时才临时登录
                    RoleSessionKeeper.LiveSession live = RoleSessionKeeper.liveOf(act, a.fpid, 0);
                    if (live != null) {
                        roles0 = live.engine.discoverRoles();
                    } else {
                        String liveFpid = new VaultEngine(act).readLiveSessionFpid();
                        if (a.fpid != null && a.fpid.equals(liveFpid)) {
                            err = "该账号正在容器游戏里运行，退出游戏后再发现";
                        } else {
                            WjdrEngine eng = WjdrAndroid.newEngine(act);
                            try {
                                HotConfig.init(act);
                                eng.initialize();
                                RoleLogin.loginPinned(act, eng, a.fpid, a.token, 0);
                                roles0 = eng.discoverRoles();
                            } finally {
                                eng.close();
                            }
                        }
                    }
                    if (roles0 != null) {
                        found = roles0.size();
                        for (WjdrEngine.RoleInfo ri : roles0) {
                            int before = RoleStore.roles(act, a.fpid).size();
                            // 已存在角色不改变 enabled；新角色默认未启用；
                            // 行内名字被修正时清零残留元数据（发现路径只带 name，
                            // 旧 level/power/alliance 可能被串行污染写过别人的值）
                            RoleStore.registerDiscoveredRole(act, a.fpid, ri.serverid, ri.uid, ri.name);
                            if (RoleStore.roles(act, a.fpid).size() > before) added++;
                        }
                    }
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final String fErr = err;
                final int fFound = found, fAdded = added;
                act.runOnUiThread(() -> {
                    // 弹层已关闭（dismiss 动画结束即移除 overlay）或宿主正在 finishing：
                    // 跳过 UI 更新。挂载点改为 DecorView 后视图对象不变、回调逻辑不变，
                    // 此处仅为防御——发现角色期间用户关弹层/退页面不再触碰已分离视图。
                    if (overlay.getParent() == null || act.isFinishing()) return;
                    discover.setEnabled(true);
                    discover.setAlpha(1f);
                    if (fErr != null) {
                        sub.setText("发现失败：" + fErr);
                        return;
                    }
                    rolesRef.set(RoleStore.roles(act, a.fpid));
                    for (Runnable rr : syncSubs) rr.run();
                    rebuildList.run();
                    if (onChanged != null) onChanged.run();
                    sub.setText("发现完成：服务器共 " + fFound
                            + " 个角色，新增 " + fAdded + "（新角色默认未启用）");
                });
            }, "role-discover").start();
        });

        overlay.addView(panel, plp);

        // 高度约束：内容自适应，但不超过屏高 80%（原型 spec）
        overlay.addOnLayoutChangeListener((v2, l, t2, r2, b2, ol, ot, or2, ob) -> {
            ViewGroup.LayoutParams lp = panel.getLayoutParams();
            int max = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.8f);
            if (panel.getHeight() > max) {
                lp.height = max;
                panel.setLayoutParams(lp);
            }
        });

        // 盖到窗口 DecorView（FrameLayout，addView 直接盖全窗：内容区 + 底部导航/悬浮按钮之上）
        // ⚠️ 不能用 act.findViewById(android.R.id.content).getParent()：那是 DecorView 级
        // 竖直 LinearLayout（statusBar/content/nav 纵向排布），overlay 会被排到全屏 content
        // 之后 → 零高度落到屏幕外，永不显示（QA v5 §7.2 P1-1 真机复现，view 树 +7 但无像素）。
        // 也不走「调用方传宿主根容器」：账号页与详情页宿主根结构不同，DecorView 是两者
        // 统一且改动最小的宿主（旧版 HomeActivity 成员 content.getParent() = App 根 FrameLayout）。
        ViewGroup root = (ViewGroup) act.getWindow().getDecorView();
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 上滑入场（320ms ease-out，近似原型 ease-out-expo）
        panel.setTranslationY(dp(act, 48));
        panel.setAlpha(0.5f);
        panel.animate().translationY(0).alpha(1f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f))
                .start();
        overlay.setAlpha(0);
        overlay.animate().alpha(1f).setDuration(200).start();
    }

    /** 关闭底部弹层：面板下滑淡出后移除整层。 */
    private static void dismiss(final FrameLayout overlay) {
        View panel = null;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View c = overlay.getChildAt(i);
            if (c instanceof LinearLayout) { panel = c; break; }
        }
        final View p2 = panel;
        Runnable remove = () -> {
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        };
        if (p2 != null) {
            p2.animate().translationY(dp((Activity) overlay.getContext(), 48)).alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(remove).start();
        } else {
            remove.run();
        }
        overlay.animate().alpha(0f).setDuration(200).start();
    }

    /**
     * 弹层内单个角色行：名称（{@link RoleDisplay#roleName}）·
     * 标准元数据行（{@link RoleDisplay#roleMetaLine}，不再有「同服第N角色」）·
     * 统一状态行 · 开关（加入/移出工作区）。
     *
     * <p>同服多角色（一个 token 同区服多个角色）时行内靠 {@code fid xxx} 段区分，
     * 开关按 (sid,uid) 定位——两个角色互不影响（旧实现按 sid 会串到另一行）。
     */
    private static View row(final Activity act, final AccountStore.Account a, final RoleStore.Role r,
                            final List<Runnable> syncSubs, final List<RoleStore.Role> allRoles,
                            final Runnable onChanged) {
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));
        row.setBackgroundColor(UiStyle.WHITE);

        // 头像种子带 uid：同服两个角色头像不再相同
        row.addView(UiStyle.snowAvatar(act, 56, a.id + ":" + r.serverid + ":" + r.uid),
                new LinearLayout.LayoutParams(dp(act, 56), dp(act, 56)));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(act, 12), 0, dp(act, 8), 0);
        TextView nm = new TextView(act);
        nm.setText(RoleDisplay.roleListLabel(r));
        nm.setTextSize(16);
        nm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nm.setTextColor(UiStyle.INK);
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(nm);

        // （统一角色标签已含服务器/联盟/昵称，不再追加 fid/城堡/战力元数据行）

        // 统一状态行（服务器模式 cachedStatusOf / 本地模式会话快照；口径同账号页）
        TextView stv = new TextView(act);
        String[] st = roleStatusLabel(act, a, r);
        stv.setText(st[0]);
        stv.setTextSize(11);
        stv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stv.setTextColor("on".equals(st[1]) ? UiStyle.OK_INK : UiStyle.INK3);
        stv.setPadding(0, dp(act, 3), 0, 0);
        mid.addView(stv);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 角色管理只保留「是否加入工作区」开关：开=加入工作区，关=移出工作区。
        // 按 (sid,uid) 定位：同服两个角色开关互不影响。
        final UiStyle.Switch[] swRef = new UiStyle.Switch[1];
        swRef[0] = new UiStyle.Switch(act, r.enabled, () -> {
            boolean now = swRef[0].isOn();
            r.enabled = now;
            RoleStore.setEnabled(act, a.fpid, r.serverid, r.uid, now);
            VaultSync.onAccountsChanged(act);
            toast(act, now ? "已加入工作区" : "已移出工作区");
            for (Runnable rr : syncSubs) rr.run();
            if (onChanged != null) onChanged.run();
        });
        row.addView(swRef[0]);

        // P1 角色管理增强（长按 = 动作菜单）：重命名 / 移除角色（不删游戏内角色）/
        // 启用·收纳（op enabled）。仅服务器模式且服务端有池接口时挂接；
        // 收纳/启用状态以服务端池数据为准（缺池数据回落本地开关）。
        if (VaultSync.isServerMode(act) && !VaultSync.isPoolUnsupported(act)) {
            JSONObject poolAccByFpid = a.fpid == null
                    ? null : com.carlos.vault.AccountPoolCache.accountsByFpid(
                            VaultSync.cachedAccountPool(act)).get(a.fpid);
            JSONObject poolRole = com.carlos.vault.AccountPoolCache.findRole(
                    poolAccByFpid, r.serverid, r.uid);
            final boolean poolEnabled = poolRole == null
                    ? r.enabled : poolRole.optBoolean("enabled", r.enabled);
            row.setOnLongClickListener(v -> {
                PoolRoleActions.showMenu(act, a, r, poolEnabled, () -> {
                    act.runOnUiThread(() -> {
                        for (Runnable rr : syncSubs) rr.run();
                        if (onChanged != null) onChanged.run();
                    });
                });
                return true;
            });
        }

        View div = new View(act);
        div.setBackgroundColor(UiStyle.LINE);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 1));
        dlp2.leftMargin = dp(act, 84);
        wrap.addView(div, dlp2);
        return wrap;
    }

    /**
     * 角色行状态文案（返回 [文案, "on"/"off"]），与账号页角色行、今日页常驻状态同源
     * （账号详情页角色盒复用本方法，保证三处口径一致）：
     * <ul>
     *   <li>服务器模式：{@link VaultSync#cachedStatusOf} 的账号态按 sid+activeUid
     *       匹配本行（{@link RoleDisplay#roleMatchesServerStatus}）；命中则显示该态
     *       （在线/重连中/被顶号暂停/需重导token），未命中（服务器会话在别的角色上）
     *       显示离线；无缓存显示「同步中」；</li>
     *   <li>本地模式：{@link RoleSessionKeeper} 协议常驻会话（fpid+sid，LiveSession
     *       无 uid——同服第二角色本地无法细分，已知限制）；</li>
     * </ul>
     */
    public static String[] roleStatusLabel(Activity act, AccountStore.Account a, RoleStore.Role r) {
        try {
            if (VaultSync.isServerMode(act)) {
                JSONObject o = a.fpid == null ? null : VaultSync.cachedStatusOf(act, a.fpid);
                if (o == null) return new String[]{"● 同步中", "off"};
                String state = o.optString("state", "");
                long statusSid = parseLong(o.optString("sid", ""));
                long statusUid = parseLong(o.optString("activeUid", ""));
                RoleStore.ActiveRole active = RoleStore.activeRoleEx(act, a.fpid);
                boolean match = RoleDisplay.roleMatchesServerStatus(
                        r.serverid, r.uid, statusSid, statusUid, active.sid, active.uid);
                if (!match) return new String[]{"● 离线", "off"};
                boolean on = RoleDisplay.isServerOnline(state);
                return new String[]{"● " + RoleDisplay.serverStateLabel(state), on ? "on" : "off"};
            }
            for (RoleSessionKeeper.LiveSession ls : RoleSessionKeeper.get(act).sessionsSnapshot()) {
                if (a.fpid != null && a.fpid.equals(ls.fpid) && ls.sid == r.serverid
                        && WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) {
                    return new String[]{"● 日常在线", "on"};
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{"● 离线", "off"};
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int dp(Activity act, int v) {
        return (int) (act.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private static void toast(Activity act, String s) {
        UiStyle.toast(act, s, Toast.LENGTH_LONG);
    }
}
