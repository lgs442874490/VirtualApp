package com.carlos.vaultserver;

import java.util.ArrayList;
import java.util.List;

/** HTTP API 与会话管理器的解耦接口（单测可注入假实现，不连游戏服）。 */
public interface SessionControl {

    /** 账号库变更后重对齐会话（sync / token 更新后调用）。 */
    void reconcile();

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

    /** 全局在线角色数（所有账号 engine online 的会话）。 */
    default int onlineRoleCount() {
        return 0;
    }
}
