package com.carlos.vault.daily;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色展示纯函数（<b>纯 Java，无 Android 依赖</b>——JVM 单测直跑）。
 *
 * <p>第二轮 UI 统一（2026-10-20）的共享展示层：账号页角色表格、账号详情页角色盒、
 * 角色管理弹层三处原本各写一份格式化（「同服第N角色」/ uid 补缀/城堡战力/状态文案
 * 口径各不相同），本类把口径收敛成一处：
 * <ul>
 *   <li><b>名称</b>：空（未同步）统一显示「未同步」；</li>
 *   <li><b>元数据行</b>：{@code K888 · fid 88280792 · 城堡 70 级 · 战力 20784万}，
 *       任一段缺失显示对应「未同步」（uid<=0 → {@code fid 未同步}；
 *       level<=0 → {@code 城堡未同步}；power<=0 → {@code 战力未同步}）；</li>
 *   <li><b>服务器状态文案</b>：online→在线 / recovering→重连中 /
 *       takeover→被顶号暂停 / need_token→需重导token / 其余→离线。
 *       账号页、今日页常驻状态、角色管理行共用；</li>
 *   <li><b>工作区过滤</b>：只渲染已加入工作区（enabled）的角色。</li>
 * </ul>
 *
 * <p>同服多角色的可区分性由 {@code fid xxx} 段承担——不再使用「同服第N角色」
 * （序号随行序漂移，且与 (sid,uid) 复合身份无稳定对应）。
 */
public final class RoleDisplay {

    private RoleDisplay() {
    }

    // ---------- 名称 ----------

    /** 角色昵称；null/空（未同步）显示「未同步」。 */
    public static String roleName(String name) {
        return name == null || name.isEmpty() ? "未同步" : name;
    }

    // ---------- 战力格式化 ----------

    /**
     * 战力格式化：{@code >=10000 → "20784万" / "1234.6万"}（整数万去掉小数点），
     * 否则原值。账号页/详情页/角色管理行共用同一口径。
     */
    public static String wan(long n) {
        if (n < 10000) return String.valueOf(n);
        String s = String.format(java.util.Locale.CHINA, "%.1f", n / 10000.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "万";
    }

    // ---------- 角色列表统一标签 ----------

    /**
     * 角色列表统一展示：{@code 888-cch-小鹿矿工5号}（服务器号不带 K、联盟简称、角色昵称）。
     * 缺失段用「未同步」占位（不编造）；同服多角色靠昵称/联盟区分。
     */
    public static String roleListLabel(long sid, String allianceAbbr, String name) {
        String zone = sid > 0 ? String.valueOf(sid) : "未同步";
        String abbr = allianceAbbr == null || allianceAbbr.trim().isEmpty()
                ? "未同步" : allianceAbbr.trim();
        return zone + "-" + abbr + "-" + roleName(name);
    }

    /** 角色列表统一展示（Role 版）。 */
    public static String roleListLabel(RoleStore.Role r) {
        if (r == null) return "未同步";
        return roleListLabel(r.serverid, r.allianceAbbr, r.name);
    }

    // ---------- 元数据行 ----------

    /**
     * 标准元数据行：{@code K888 · fid 88280792 · 城堡 70 级 · 战力 20784万}。
     *
     * <p>{@code K<sid>} 前缀保留区号信息（同服多角色靠 fid 段区分）。
     * 任一段缺失显示对应「未同步」文案，不编造数值。
     *
     * @param sid    区服（serverid）
     * @param uid    角色 fid（{@code <=0}=未同步）
     * @param level  熔炉等级（{@code <=0}=未同步）
     * @param power  战力（{@code <=0}=未同步）
     */
    public static String roleMetaLine(long sid, long uid, long level, long power) {
        StringBuilder sb = new StringBuilder();
        sb.append("K").append(sid);
        sb.append(" · ").append(uid > 0 ? "fid " + uid : "fid 未同步");
        sb.append(" · ").append(level > 0 ? "城堡 " + level + " 级" : "城堡未同步");
        sb.append(" · ").append(power > 0 ? "战力 " + wan(power) : "战力未同步");
        return sb.toString();
    }

    // ---------- 服务器状态文案 ----------

    /**
     * 服务器会话态文案（{@code /v1/vault/state} 回传的 state 字段口径）：
     * online→在线 / recovering→重连中 / takeover→被顶号暂停 /
     * need_token→需重导token / null 或未知值→离线。
     */
    public static String serverStateLabel(String state) {
        if (state == null) return "离线";
        switch (state) {
            case "online":
                return "在线";
            case "recovering":
                return "重连中";
            case "takeover":
                return "被顶号暂停";
            case "need_token":
                return "需重导token";
            default:
                return "离线";
        }
    }

    /** 服务器态是否在线（{@code online}）。 */
    public static boolean isServerOnline(String state) {
        return "online".equals(state);
    }

    /**
     * 服务器账号态（{@code sid}/{@code activeUid}）是否描述该角色行（纯函数，供
     * 服务器模式下角色行/角色管理行渲染在线徽标）。
     *
     * <p>规则：
     * <ul>
     *   <li>状态无 sid（服务端尚未登录）或 sid 不同 → false；</li>
     *   <li>新服务器（{@code statusUid>0}）：必须 uid 均已知且相等——行 uid 未知
     *       （未同步）时无法确认，保守判 false（该行随后续登录/发现升级）；</li>
     *   <li>旧服务器（{@code statusUid<=0}，无 activeUid 字段）：退到激活角色口径
     *       （与账号页 {@code roleOnline} 同思路）——激活 uid 已知按 uid 比，
     *       否则按 sid 比（已知限制：同服第二角色本地无法细分）。</li>
     * </ul>
     *
     * @param activeSid/activeUid 本地当前激活角色（兜底口径）
     */
    public static boolean roleMatchesServerStatus(long roleSid, long roleUid,
                                                  long statusSid, long statusUid,
                                                  long activeSid, long activeUid) {
        if (statusSid <= 0 || roleSid != statusSid) return false;
        if (statusUid > 0) return roleUid > 0 && roleUid == statusUid;
        if (activeUid > 0 && roleUid > 0) return roleUid == activeUid;
        return roleSid == activeSid;
    }

    // ---------- 工作区过滤 ----------

    /**
     * 工作区角色（enabled）子表；probe 为空时全量返回。调用方传
     * {@code r -> r.enabled}（RoleStore.Role）即可。
     */
    public static <T extends RoleIdentity.Identity> List<T> workspaceRoles(
            List<T> list, RoleIdentity.EnabledProbe probe) {
        List<T> out = new ArrayList<>();
        if (list == null) return out;
        for (T r : list) {
            if (probe == null || probe.enabled(r)) out.add(r);
        }
        return out;
    }

    /** 工作区第一个角色（无则 null）。 */
    public static <T extends RoleIdentity.Identity> T firstWorkspaceRole(
            List<T> list, RoleIdentity.EnabledProbe probe) {
        if (list == null) return null;
        for (T r : list) {
            if (probe == null || probe.enabled(r)) return r;
        }
        return null;
    }
}
