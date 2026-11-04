package com.carlos.vaultserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP API 与会话管理器的解耦接口（单测可注入假实现，不连游戏服）。 */
public interface SessionControl {

    /** 账号库变更后重对齐会话（sync / token 更新后调用）。 */
    void reconcile();

    /**
     * 玩家信息解析（2026-11-02 观察点查询工具）：借在线会话发 req_players_base_info，
     * 返回该 uid 的 result 条目（nickName/alliance{aid,abbr,name}/stove_lv 等）。默认不支持返回 null。
     */
    default Map<String, Object> resolvePlayerInfo(String fpid, long sid, long uid) {
        return null;
    }

    /**
     * 批量玩家信息解析（2026-11-02 观察点查询工具）：一次 req_players_base_info 可带多个 uid。
     * 返回 uid → 条目；默认不支持返回空表。
     */
    default Map<Long, Map<String, Object>> resolvePlayers(String fpid, long sid, List<Long> uids) {
        return java.util.Collections.emptyMap();
    }

    /**
     * 联盟简称 → aid（2026-11-02）：先查玩家目录缓存，再用官方联盟搜索协议
     * {@code req_alliance_search_by_key{key}} 反查。默认不支持返回 0。
     */
    default long resolveAllianceAid(String fpid, long sid, String abbr) {
        return 0L;
    }

    /**
     * FID（游戏内玩家ID）→ 服务器 uid（2026-11-04）：App 侧只让用户填 FID，
     * 需要 uid 的接口（如监视名单 add）用本方法换算。默认不支持返回 0。
     */
    default long resolveUidByFid(String fpid, String fid) {
        return 0L;
    }

    /** 按需建会话并开始登录（账号页登录/手动触发用）；账号不存在或无 token 返回 false。 */
    boolean ensureSession(String fpid);

    /** 单账号状态快照。 */
    List<SessionStatus> status();

    /**
     * 让位指令（App 端用户要进游戏前调用）：断开会话，probeMillis 后自动探测回连。
     *
     * @return 账号存在且常驻开启返回 true
     */
    boolean yield(String fpid, long probeMillis);

    /** 会话状态行（App 端渲染 ● 日常在线 / △ 被顶号暂停 / ⚠ 需重导token 等）。 */
    final class SessionStatus {
        public String fpid;
        public String label;
        public String group;
        /** 引擎态：online / recovering / takeover / need_token / offline（未建会话或未开常驻）。 */
        public String state;
        /** 最近一次失败/让位原因（中文，直接展示）。 */
        public String lastFailure;
        /** 当前会话角色 sid（无会话时给 activeSid）。 */
        public String sid;
        /**
         * 当前会话角色 uid（同服多角色区分依据）。
         *
         * <p>有会话且已登录：实际落地角色 uid；无会话：账号库里 App 下发的 activeUid。
         * null/"" = 未知（旧数据/旧客户端）——消费方按 0 处理，走 sid 口径。
         */
        public String activeUid;
        /** 服务器上是否有可用 token。 */
        public boolean hasToken;
        /** 登录后学到的角色元数据（App 端角色卡回填用；未登录时为空/0）。 */
        public String roleName = "";
        public long roleLevel;
        public long rolePower;
        public String roleAlliance = "";
        /** 状态进入时刻（epoch ms）。 */
        public long sinceTs;
    }

    // ---------- 账号池控制面（M1 2026-10-21；default 方法保持旧假实现兼容） ----------

    /** 每角色运行态视图（字段对齐 M1 契约 §2.1 state）。 */
    final class RoleRuntime {
        public boolean online;
        /** RUNNING | PAUSED | STOPPED。 */
        public String runState = "STOPPED";
        /** AVAILABLE | DISABLED | ACTION_REQUIRED | WAITING_RETRY。 */
        public String recoveryState = "AVAILABLE";
        public String statusMessage = "";
        public boolean takeoverRecoveryActive;
        /** epoch ms；WAITING_RETRY 倒计时用（0 = 未知，App 显示「即将恢复」）。 */
        public long nextRecoveryAt;
        public boolean startupInProgress;
        public boolean loginInProgress;
        /** ACTIVE | PAUSED（计划休眠 P0 未实现，恒 ACTIVE）。 */
        public String runScheduleState = "ACTIVE";

        public static RoleRuntime offline() {
            return new RoleRuntime();
        }
    }

    /** 角色引用（stoppedRoles 回传 / 角色定位）。 */
    final class RoleRef {
        public final String sid;
        public final String uid;

        public RoleRef(String sid, String uid) {
            this.sid = sid == null ? "" : sid;
            this.uid = uid == null ? "" : uid;
        }
    }

    /** 角色动作结果（code!=0 时 message 面向 App 提示）。 */
    final class RoleOpResult {
        public static final int CODE_ROLE_NOT_FOUND = 1401;
        public static final int CODE_ROLE_NOT_ONLINE = 1402;
        public static final int CODE_ROLE_DISABLED = 1403;
        public static final int CODE_TOKEN_MISSING = 1404;
        public static final int CODE_CONCURRENCY_LIMIT = 1429;

        public boolean accepted;
        public int code;
        public String message = "";
        /** 动作后的运行态（RUNNING/PAUSED/STOPPED）。 */
        public String runState = "";
        /**
         * login 顶掉的同 fpid 在线角色（供 App 提示互斥）。
         *
         * <p>2026-10-21 M1d 多角色并行：login 不再停止同账号其它角色，该字段恒为空，
         * 仅为 API 兼容保留（旧 App v4.1.4 响应解析不受影响）。
         */
        public List<RoleRef> stoppedRoles = new ArrayList<>();
        // ---------- P1 扩展（2026-10-21；按 op 选填） ----------
        /** pause：自动恢复时刻（epoch millis；0=无）。 */
        public long pauseResumeAt;
        /** rename：应用后的角色名。 */
        public String name;
        /** rename：是否消耗钻石（改名道具不足时 use_diamond 重试）。 */
        public boolean usedDiamond;
        /** rename：钻石消耗（usedDiamond ? 400 : 0）。 */
        public long diamondCost;
        /** rename：改名道具 id（610001）。 */
        public long itemId;

        static RoleOpResult ok(String runState, List<RoleRef> stoppedRoles) {
            RoleOpResult r = new RoleOpResult();
            r.accepted = true;
            r.runState = runState == null ? "" : runState;
            if (stoppedRoles != null) r.stoppedRoles = stoppedRoles;
            return r;
        }

        static RoleOpResult ok(String runState) {
            return ok(runState, null);
        }

        static RoleOpResult fail(int code, String message) {
            RoleOpResult r = new RoleOpResult();
            r.code = code;
            r.message = message;
            return r;
        }
    }

    /** 每角色运行态（无会话 = 离线）。默认实现供旧假实现/旧客户端降级。 */
    default RoleRuntime roleRuntime(String fpid, String sid, String uid) {
        return RoleRuntime.offline();
    }

    /**
     * 角色显式动作（M1 契约 §3.2）：login/logout/pause/resume/stop。
     * 动作串行化（实现方加锁）、幂等；不可执行状态返回 code!=0 + message。
     */
    default RoleOpResult roleAction(String fpid, String sid, String uid, String action) {
        return RoleOpResult.fail(3002, "角色动作未启用");
    }

    /**
     * 角色显式动作（P1 2026-10-21 带参重载）：在 M1 五动作之上新增
     * pause（升级为按恢复策略自动恢复）/ rename（payload.name）/
     * remove / enabled（payload.enabled）；旧假实现走 4 参默认降级。
     */
    default RoleOpResult roleAction(String fpid, String sid, String uid, String action,
                                    Map<String, Object> payload) {
        return roleAction(fpid, sid, uid, action);
    }

    /**
     * 账号级恢复设置变更后的回调（P1 op recovery-settings 持久化后调用）：
     * 实现方按新策略重算暂停角色的自动恢复计划（如关闭后取消待恢复）。
     */
    default void onRecoverySettingsChanged(String fpid) {
    }

    /** 全局在线角色数（所有账号 engine online 的会话）。 */
    default int onlineRoleCount() {
        return 0;
    }
}
