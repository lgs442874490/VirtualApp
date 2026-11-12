package com.carlos.vault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 工具箱（设计稿 S9）：诊断/监控能力的独立页。
 *
 * <p>批次7（2026-10-23）按 {@link FeatureGate} 分级门控重排：
 * <ul>
 *   <li><b>敌情监控</b>（T3 高级会员）：推送渠道 + 开关 + 配置；无权限不渲染；</li>
 *   <li><b>真机监控</b>（T3 高级会员）：服务端任务级协议监控页（daily/MonitorActivity）；</li>
 *   <li><b>历史「规划中 · 14」整段下架</b>：三消/爆兵/宠物洗练/移民抢位/侦察战报/迁城/
 *       坐标查询/充值记录/巨兽批量等均无实现，未开发/未立项功能一律不显示；
 *       同一条目在 {@code design/全流程行为与分级门控-20261023.md} 中登记。</li>
 * </ul>
 * （2026-10-31）工具箱不再提供敌情监控：敌情监控/战争通知统一走 我的-敌情监控（服务器观察点）。
 * HomeActivity 不再保留副本，避免两处逻辑漂移。
 */
public class ToolBoxActivity extends Activity {

    /** 兜底：进程被回收后本页可能成为栈底，按返回应回主页而不是退出 App。 */
    @Override
    public void onBackPressed() {
        if (isTaskRoot()) {
            try {
                startActivity(new Intent(this, HomeActivity.class)
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

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void toast(String s) {
        UiStyle.toast(this, s);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 批次7 防旁路：本页只有 T3（敌情监控/真机监控/迁城/移民等）与调试工具；无任何可用工具时
        // （含直接 Intent 启动绕过 Home 入口门控）直接返回，不放空壳页也不泄露功能清单。
        if (!FeatureGate.anyToolVisible(this)) {
            toast(FeatureGate.reasonText(FeatureGate.Feature.TOOLBOX));
            finish();
            return;
        }
        try {
            getWindow().setStatusBarColor(UiStyle.BG_V2);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
        setContentView(buildUi());
    }

    // ================= UI =================

    private View buildUi() {
        UiComponents.Page page = UiComponents.scrollPage(
                this, "工具箱", null, this::finish, 24);
        LinearLayout box = page.body;

        // ===== 批次7 功能门控（单一事实源 FeatureGate，2026-10-23；2026-11-06 接服务端矩阵） =====
        // 原则：① 无权限功能不显示；② 未开发/未立项功能一律隐藏（历史“规划中 · 14”整段下架，
        // 三消/爆兵/宠物洗练/移民抢位/侦察战报/迁城/坐标查询/充值记录/巨兽批量等均无实现）；
        // ③ 工具箱内调试/实测项仅调试包可见。
        // 注意：统一走 FeatureGate.visible(this, feature)（服务端矩阵优先），不要再传 tier 纯函数。
        final boolean canMoveCity = FeatureGate.visible(this, FeatureGate.Feature.MOVE_CITY);     // T3 高阶（迁城）
        final boolean canFarm = FeatureGate.visible(this, FeatureGate.Feature.ACCOUNT_FARM);     // T3 起号（M5）
        final boolean canWatchpoint = FeatureGate.visible(this, FeatureGate.Feature.WATCHPOINT);   // T3 高阶
        final boolean canCapture = FeatureGate.visible(this, FeatureGate.Feature.PROTO_CAPTURE)
                && android.os.Build.VERSION.SDK_INT >= 21;   // 真机抓包：T3 + API21+
        // 移民（藏宝阁-自由移民，2026-11-06 1:1 复刻）：服务端 14 action + 准备窗口
        // （开放前 15 分钟上号/退盟/召回）+ 50ms 抢位 + 重登对账；危险动作均二次确认。
        // 服务端任务型模块 → 追加服务端可用性前置。
        final boolean canMigrant = FeatureGate.visible(this, FeatureGate.Feature.IMMIGRATION);
        // D2（真机 2026-11-06，重放时被 master 版覆盖，2026-11-10 恢复）：
        // 计数必须等于下方实际渲染的卡片数（迁城/观察点/移民/诊断）。
        final int toolCount = (canMoveCity ? 1 : 0)
                + (canWatchpoint ? 1 : 0)
                + ((FeatureGate.currentTier(this) == FeatureGate.TIER_GM) ? 1 : 0)
                + (canMigrant ? 1 : 0)
                + (canFarm ? 1 : 0);
        if (toolCount > 0) {
            box.addView(sectionRow("可用工具 · " + toolCount, "诊断与监控"));
            // 迁城（T3 高级会员，2026-11-06）：网页版迁城 1:1 复刻——12 种协议/立即批量/
            // 定时抢位/任务管理，服务端执行。旧服务端无该端点时页内提示「服务端未升级」。
            if (canMoveCity) {
                LinearLayout moveCard = toolCard(UiStyle.Icon.COMPASS, "迁城",
                        "12 种迁城协议 · 立即批量/定时抢位（服务器执行）", UiStyle.BRAND_50);
                moveCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                moveCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.daily.movecity.MoveCityActivity.class)));
                box.addView(moveCard);
            }
            // 起号（M5，2026-11-07）：BootstrapActivity 注册接码/批量起号/账号回流三区页。
            if (canFarm) {
                LinearLayout farmCard = toolCard(UiStyle.Icon.USERS, "起号",
                        "注册接码 · 批量起号 · 账号回流", UiStyle.BRAND_50);
                farmCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                farmCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.bootstrap.BootstrapActivity.class)));
                box.addView(farmCard);
            }
            // 移民（藏宝阁-自由移民，2026-11-06 1:1 复刻）：服务端 14 action + 准备窗口
            // （开放前 15 分钟上号/退盟/召回）+ 50ms 抢位 + 重登对账；页面内危险动作均二次确认。
            if (canMigrant) {
                LinearLayout migrantCard = toolCard(UiStyle.Icon.COMPASS, "移民",
                        "自由移民 · 资格预检、边界任务与毫秒级抢位", UiStyle.BRAND_50);
                migrantCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                migrantCard.setOnClickListener(v -> {
                    // 身份口径与资产中心一致：前台 home:current_role（fpid:sid[:uid]）。
                    // 解析失败时交回 Activity 自身回退（MigrantWorkspaceActivity 内部同源兜底）。
                    String raw = getSharedPreferences("home", MODE_PRIVATE)
                            .getString("current_role", "");
                    com.carlos.vault.daily.CurrentRoleKey.Parts p =
                            com.carlos.vault.daily.CurrentRoleKey.parse(raw);
                    if (p != null) {
                        com.carlos.vault.migrant.MigrantWorkspaceActivity.launch(
                                this, p.fpid, p.sid, p.uid);
                    } else {
                        startActivity(new Intent(this,
                                com.carlos.vault.migrant.MigrantWorkspaceActivity.class));
                    }
                });
                box.addView(migrantCard);
            }
            // 观察点（批次 B 2026-10-30）：App 内可视化编辑服务器 data/watchpoint.json（get/set/enable）。
            if (canWatchpoint) {
                LinearLayout wpCard = toolCard(UiStyle.Icon.EYE, "观察点",
                        "监视敌方城/保护友盟聚集地（告警配置，保存即热载）", UiStyle.BRAND_50);
                wpCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                wpCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.WatchpointActivity.class)));
                box.addView(wpCard);
            }
            // 诊断（2026-11-10）：把「真机抓包」与「真机监控」合并为一个入口，页内两个 tab 切换。
            // 客户端抓包=本机 VPN 抓 TCP 并 sproto 解码；服务端监控=拉 /v1/vault/monitor 任务事件流。
            // 两者数据源不同（本机网络层 vs 服务端任务层），不能互相替代，只是入口聚合。
            // GM 账户一定可见（不依赖服务端矩阵是否下发 proto_capture/dev_monitor）
            if (FeatureGate.currentTier(this) == FeatureGate.TIER_GM) {
                LinearLayout diagCard = toolCard(UiStyle.Icon.TARGET, "诊断",
                        "客户端抓包 + 服务端监控（页内 tab 切换）", UiStyle.BRAND_50);
                diagCard.addView(new UiStyle.Icon(this, UiStyle.Icon.CHEVRON,
                        UiStyle.INK3, 18));
                final String diagTab = canCapture
                        ? com.carlos.vault.daily.DiagnosticActivity.TAB_CAPTURE
                        : com.carlos.vault.daily.DiagnosticActivity.TAB_MONITOR;
                diagCard.setOnClickListener(v -> startActivity(
                        new Intent(this, com.carlos.vault.daily.DiagnosticActivity.class)
                                .putExtra(com.carlos.vault.daily.DiagnosticActivity.EXTRA_TAB, diagTab)));
                box.addView(diagCard);
            }
        } else {
            // 批次8（P0-1）：无任何可见工具时**整组/占位都不渲染**（不放空卡片、不放“暂无”占位）。
            // 正常路径下 onCreate 已 finish（防直开旁路），此处仅防御异常路径。
        }


        return page.root;
    }

    /** 分区标题行：左主标题 + 右提示。 */
    private LinearLayout sectionRow(String title, String hint) {
        return UiComponents.sectionHeader(this, title, hint);
    }

    /** 工具行卡：统一图标卡；开关、箭头及点击回调仍由本页绑定。 */
    private LinearLayout toolCard(int iconType, String title, String sub, int iconBg) {
        return UiComponents.iconCard(this, iconType, title, sub, iconBg);
    }
}
