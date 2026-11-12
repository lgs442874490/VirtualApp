package com.carlos.vault.daily;

import java.util.List;

/**
 * 角色身份工具（<b>纯 Java，无 Android 依赖</b>——JVM 单测直跑，vault-server 亦可复用）。
 *
 * <p>角色身份 = <b>(serverid, uid)</b>。同一 token 在同一个区服建多个角色是常态
 * （真机：账号 48460403，888 服「隆科多」uid=35371154 与「小鹿矿工5号」uid=88280792），
 * 历史上号库按 {@code serverid} 单键去重/定位，同服第二个角色会被覆盖合并、开关与
 * 登录目标串到另一个角色上。本类把「身份语义」集中成无副作用函数，RoleStore 与
 * 各 UI/登录调用点都经它匹配，避免同一规则散落多处再次漂移。
 *
 * <p>兼容口径：
 * <ul>
 *   <li>{@code uid<=0} = 未知（旧数据/未同步角色）。匹配时一侧未知即视为同一角色的
 *       <b>候选</b>，用于旧记录原地升级（学到 uid 后补登到同一行）；</li>
 *   <li>两侧 uid 均已知且同 sid 但 uid 不同 → <b>不同角色</b>，绝不再合并。</li>
 * </ul>
 */
public final class RoleIdentity {

    private RoleIdentity() {
    }

    /** 角色身份的最小视图（RoleStore.Role 与服务器侧角色记录都实现它）。 */
    public interface Identity {
        long serverid();

        long uid();
    }

    // ---------- 复合键 ----------

    /** 复合键 {@code sid:uid}（uid<=0 序列化为 0）。 */
    public static String key(long sid, long uid) {
        return sid + ":" + Math.max(0L, uid);
    }

    /** 解析复合键；非法/空返回 {@code null}，否则 {@code long[]{sid, uid}}。 */
    public static long[] parseKey(String s) {
        if (s == null || s.isEmpty()) return null;
        int i = s.indexOf(':');
        if (i <= 0 || i >= s.length() - 1) return null;
        try {
            long sid = Long.parseLong(s.substring(0, i).trim());
            long uid = Long.parseLong(s.substring(i + 1).trim());
            if (sid < 0 || uid < 0) return null;
            return new long[]{sid, uid};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 身份匹配 ----------

    /** 严格同角色：sid 相等且 uid 均已知且相等。 */
    public static boolean sameIdentity(long sidA, long uidA, long sidB, long uidB) {
        return sidA > 0 && sidA == sidB && uidA > 0 && uidB > 0 && uidA == uidB;
    }

    /**
     * 宽松匹配（登记/升级用）：sid 必须相等，且两侧 uid 未全知或相等。
     *
     * <p>旧数据 {@code uid=0} 的记录在 uid 已知时原地升级（返回 true 命中同一行）；
     * 同 sid 两个已知 uid 互不匹配（返回 false → 新建行）。
     */
    public static boolean matches(long sidA, long uidA, long sidB, long uidB) {
        if (sidA <= 0 || sidA != sidB) return false;
        return uidA <= 0 || uidB <= 0 || uidA == uidB;
    }

    /** 严格查找（uid 已知）；uid<=0 时退化为「该 sid 的第一行」（旧口径）。 */
    public static int indexOf(List<? extends Identity> list, long sid, long uid) {
        if (list == null || sid <= 0) return -1;
        if (uid > 0) {
            for (int i = 0; i < list.size(); i++) {
                Identity r = list.get(i);
                if (r.serverid() == sid && r.uid() == uid) return i;
            }
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).serverid() == sid) return i;
        }
        return -1;
    }

    /** 宽松查找（登记去重/旧记录升级用）。 */
    public static int indexOfLoose(List<? extends Identity> list, long sid, long uid) {
        if (list == null || sid <= 0) return -1;
        for (int i = 0; i < list.size(); i++) {
            Identity r = list.get(i);
            if (matches(r.serverid(), r.uid(), sid, uid)) return i;
        }
        return -1;
    }

    /** 同服角色数（该 sid 出现在角色表里的行数；含 uid=0 的未同步行）。 */
    public static int countOfServer(List<? extends Identity> list, long sid) {
        if (list == null || sid <= 0) return 0;
        int n = 0;
        for (Identity r : list) {
            if (r.serverid() == sid) n++;
        }
        return n;
    }

    /**
     * 同服第 N 角色（1 起；按角色表顺序）。未找到返回 0。
     *
     * <p>uid 已知时按 (sid,uid) 精确定位；uid<=0 取该 sid 第一行——与 {@link #indexOf} 同口径，
     * 保证「行显示第 N」与「点击命中第 N」永远一致。
     */
    public static int ordinalOf(List<? extends Identity> list, long sid, long uid) {
        int idx = indexOf(list, sid, uid);
        if (idx < 0) return 0;
        int n = 0;
        for (int i = 0; i <= idx; i++) {
            if (list.get(i).serverid() == sid) n++;
        }
        return n;
    }

    // ---------- 当前角色（active）复合键 ----------

    /** 工作区开关探针（RoleStore.Role 传 r -> r.enabled；纯表结构可传 {@code r -> false}）。 */
    public interface EnabledProbe {
        boolean enabled(Identity r);
    }

    /**
     * 旧 {@code active:<fpid>} 只有 sid。解析旧值时调用方需补 uid——本方法按角色表解析：
     * 优先已加入工作区（enabled，经 probe 判定）的同 sid 角色，再取第一个；都找不到返回 0。
     */
    public static long resolveUidForSid(List<? extends Identity> list, long sid, EnabledProbe probe) {
        if (list == null || sid <= 0) return 0;
        if (probe != null) {
            for (Identity r : list) {
                if (r.serverid() == sid && probe.enabled(r)) return r.uid();
            }
        }
        for (Identity r : list) {
            if (r.serverid() == sid) return r.uid();
        }
        return 0;
    }

    // ---------- 角色键展示 ----------

    /** 行内可区分信息：{@code 888服 · 同服第2角色}（同服多角色时）或 {@code 888服}。 */
    public static String serverLabel(List<? extends Identity> list, long sid, long uid) {
        StringBuilder sb = new StringBuilder();
        sb.append(sid).append("服");
        if (countOfServer(list, sid) > 1) {
            int n = ordinalOf(list, sid, uid);
            sb.append(" · 同服第").append(n > 0 ? n : 1).append("角色");
        }
        return sb.toString();
    }

    // ---------- 服务器角色回写门控（防回滚，纯函数） ----------

    /**
     * 是否采纳服务器回传的「当前角色」（防回滚判定，纯函数——JVM 单测直跑）。
     *
     * <p><b>背景</b>：切角色只写本地（{@code active2:<fpid>} / {@code current_role}），
     * 不等于已上传；上传完成前服务端状态仍是旧角色。若无条件采纳，{@code fetchState} /
     * 状态接口的旧回传会把刚切的角色回滚（UI 表现为切完又跳回；同服多角色
     * 时旧 {@code activeUid} 还会落到同服另一个角色上）。
     *
     * <p><b>判定规则</b>（全部满足才采纳，任一不满足即跳过）：
     * <ol>
     *   <li>服务端 sid 有效（{@code serverSid > 0}）——服务端尚未登录/无 sid 时无可学；</li>
     *   <li>服务端身份与本地当前激活身份不同（{@link #sameIdentity} 严格口径；本地
     *       {@code uid=0} 的旧数据不算同身份，放行以便升级学习）；</li>
     *   <li>本地已知 uid 时，服务端 uid 不允许缺失（{@code serverUid <= 0}）——不把已知
     *       身份降级回未知，避免旧服务器回传时按 sid 解析落到同服另一个角色；</li>
     *   <li>本地激活时间戳<b>严格早于</b>请求发起时间（{@code localTs < requestTs}）——
     *       本地切换发生在请求发出之后 ⇒ 该回传是切换前的旧快照，必须跳过。
     *       取严格小于（同时刻按跳过处理）：误跳过的代价是下次同步自愈，误采纳的代价
     *       是用户可见的角色回滚，风险不对称故取保守侧；</li>
     * </ol>
     * {@code localTs=0}（从未记录过激活时间，旧数据）按「早于任何请求」处理，保持可学习。
     *
     * @param localSid/localUid   本地当前激活角色（{@code sid=-1}/{@code uid=0}=从未选过）
     * @param localTs             本地激活身份的写入时间（epoch ms；0=无记录）
     * @param serverSid/serverUid 服务器回传的当前角色（{@code uid<=0}=旧服务器无此字段）
     * @param requestTs           本次状态请求的发起的时刻（epoch ms）
     */
    public static boolean shouldLearnActiveRole(long localSid, long localUid, long localTs,
                                                long serverSid, long serverUid, long requestTs) {
        if (serverSid <= 0) return false;
        if (sameIdentity(localSid, localUid, serverSid, serverUid)) return false;
        if (localUid > 0 && serverUid <= 0) return false;
        return localTs < requestTs;
    }

    // ---------- 服务器元数据学习门控（防同服串行，纯函数） ----------

    /**
     * 是否允许把服务器回传的角色元数据（name/level/power/alliance）登记进角色表。
     *
     * <p><b>背景</b>（2026-10-20 P1-2，QA 报告 §3.4）：旧服务器 status 无
     * {@code activeUid} 时 App 解析为 {@code uid=0}，而 {@code registerRole} 的
     * {@link #indexOfLoose} 宽松匹配「一侧 uid 未知即命中」——会把服务器<b>会话角色</b>
     * 的元数据覆盖到同服<b>第一行</b>（无论那是谁）。真机复现：小鹿行被反复覆盖成
     * 隆科多的 lv70/战力/KFC，灰度窗口内每次 fetchState 必现。
     *
     * <p><b>规则</b>：
     * <ul>
     *   <li>{@code sid<=0}：false（防御，不产生 sid=0 行）；</li>
     *   <li>{@code uid>0}：true——身份完整，{@code registerRole} 按 (sid,uid) 精确
     *       命中或新建，绝不串行（新服务器下发 activeUid 的正常路径）；</li>
     *   <li>{@code uid<=0}：仅当该 sid <b>尚无任何 uid 已知行</b>时为 true
     *       （单角色账号首次引导：uid=0 登记后由登录/发现升级）；已有已知行时
     *       false——此时 uid=0 的宽松命中必然落到别人的行上，必须跳过。</li>
     * </ul>
     *
     * @param rows 该凭证的角色表（{@link RoleStore.Role} 或其它的 Identity 视图）
     */
    public static boolean shouldLearnServerMeta(long sid, long uid, List<? extends Identity> rows) {
        if (sid <= 0) return false;
        if (uid > 0) return true;
        return !hasKnownUidRow(rows, sid);
    }

    /** 该 sid 是否存在 uid 已知（{@code uid>0}）的角色行。 */
    public static boolean hasKnownUidRow(List<? extends Identity> rows, long sid) {
        if (rows == null || sid <= 0) return false;
        for (Identity r : rows) {
            if (r.serverid() == sid && r.uid() > 0) return true;
        }
        return false;
    }

    // ---------- 启动双源当前角色对齐（纯函数，2026-10-20） ----------

    /**
     * 启动时对齐两处当前角色记录（{@code daily_roles.active2} 与 {@code home:current_role}）。
     *
     * <p>旧实现无条件用 current_role 覆盖 active2——若 current_role 较旧（历史写入路径
     * 未同步、或 learnServerRole 曾只改其中一处），重启会把用户刚切的角色回滚，再同步给
     * 服务器落错角色（真机：切隆科多后重启变回小鹿）。按写入时间取较新者即可消除该回滚。
     *
     * @return {@link #SOURCE_ACTIVE}=active2 更新（以 active2 为准）；{@link #SOURCE_CURRENT}=
     *         current_role 更新或时间相同/缺失（保留旧行为）
     */
    public static final int SOURCE_CURRENT = 0;
    public static final int SOURCE_ACTIVE = 1;

    public static int pickNewerRoleSource(long activeSid, long activeUid, long activeAt,
                                          long currentSid, long currentUid, long currentAt) {
        if (currentSid <= 0) return SOURCE_ACTIVE;
        if (activeSid <= 0) return SOURCE_CURRENT;
        if (activeAt > currentAt) return SOURCE_ACTIVE;
        return SOURCE_CURRENT;
    }

    // ---------- 发现角色元数据清洗判定（纯函数） ----------

    /**
     * 发现角色（req_role_list 全量发现）修正行内名字时，是否清零行内残留的
     * level/power/alliance（2026-10-20 P3，QA 报告 §3.5）。
     *
     * <p>发现路径只带 name，不带等级/战力/联盟。若行内<b>旧名字非空且与新名字不同</b>，
     * 说明旧名字是错的（典型：被 P1-2 串行污染），随行的旧元数据同样无可信来源，
     * 一并清零避免两行更难分辨；名字未变（或旧名字为空=补全而非修正、新名字为空=
     * 不会覆盖）则不动，保住登录/采集学到的可信元数据。
     */
    public static boolean shouldClearStaleMeta(String oldName, String newName) {
        return oldName != null && !oldName.isEmpty()
                && newName != null && !newName.isEmpty()
                && !oldName.equals(newName);
    }
}
