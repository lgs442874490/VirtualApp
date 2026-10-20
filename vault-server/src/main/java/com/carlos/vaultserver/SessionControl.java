package com.carlos.vaultserver;

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
}
