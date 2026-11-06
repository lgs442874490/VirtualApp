package com.carlos.vaultserver.movecity;

import java.util.Map;

/**
 * 迁城引擎与会话层的解耦接口（单测可注入假实现，不连游戏服）。
 *
 * <p>生产实现挂在 {@code SessionKeeper} 上（按 fpid 寻址 active 角色会话，
 * 对应原版 {@code GameAccountSessionService.onlineSession / requestFast} 语义）。
 *
 * <p>互斥语义映射（需求 NFR5）：原版「迁城独占窗 + waitForNoPendingRequest」在本项目
 * 由两层等价物承担——{@code engine.requestLock} 保证同会话请求天然串行；
 * {@link #holdForMoveCity} 在准备~释放窗口内抑制该账号的整轮任务提交。
 */
public interface MoveCityTransport {

    /** 该账号会话当前是否在线（原版 {@code onlineSession(roleId)} 成功语义）。 */
    boolean online(String fpid);

    /**
     * 按需建会话并开始登录（原版 {@code loginAutomaticallyForFrozenIdentity} 抢占登录 /
     * 「执行前自动上线」语义 → 本项目 {@code SessionKeeper.ensureSession}）。
     *
     * @return 账号存在且已受理登录返回 true
     */
    boolean ensureOnline(String fpid);

    /**
     * 经该账号会话发协议请求（原版 {@code requestFast}，6s 级超时）。
     *
     * @throws IllegalStateException 账号不在线
     * @throws Exception             传输层失败（超时/断连）
     */
    Response request(String fpid, String proto, Map<String, Object> args, int timeoutSec)
            throws Exception;

    /** 服务器毫秒时钟（已校准 {@code GameServerClock.nowServerMs()}；未校准回退本机钟）。 */
    long serverNowMs(String fpid);

    /**
     * 迁城优先窗（原版 {@code enterMoveCityExclusiveWindow} 语义的等价物）：
     * 窗口内该账号的整轮/单任务提交被 {@code SessionKeeper} 跳过，避免迁城释放瞬间
     * 被其他任务插队抢锁。到时自动失效。
     */
    void holdForMoveCity(String fpid, long untilMs);

    /**
     * 提前结束迁城优先窗（审阅修复 P0）：执行完成后立即解除，避免
     * 「准备→执行」结束后仍让位到窗口上限（原版账号池隔离，本项目账号复用，
     * 长窗口会让日常任务停摆）。
     */
    void clearMoveCityHold(String fpid);

    /**
     * 当前会话角色标识 {@code "sid:uid"}（审阅修复 P0：任务角色锁定校验用）。
     * 无会话/未登录返回空串。
     */
    String roleOf(String fpid);

    /** 账号是否仍存在于号库（账号删除联动取消定时任务，原版 removeParentAccount）。 */
    boolean accountExists(String fpid);

    /** 协议响应：{@code content.code}（0=成功，其余为游戏错误码）+ 原始 content。 */
    record Response(int code, Map<String, Object> content) {

        public static Response of(Map<String, Object> content) {
            if (content == null) return new Response(-1, Map.of());
            Object code = content.get("code");
            int c = code instanceof Number ? ((Number) code).intValue() : -1;
            return new Response(c, content);
        }
    }

    /** 协议业务失败（对应原版 GameProtocolException：带游戏错误码）。 */
    class ProtocolException extends RuntimeException {
        public final int code;

        public ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
