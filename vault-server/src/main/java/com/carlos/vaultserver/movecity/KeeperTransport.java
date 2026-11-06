package com.carlos.vaultserver.movecity;

import com.carlos.vaultserver.SessionKeeper;

import java.util.Map;

/**
 * {@link MoveCityTransport} 生产实现：包一层 {@link SessionKeeper} 迁城传输原语
 * （{@code moveCityOnline / moveCityRequest / moveCityNowMs / holdForMoveCity /
 * vaultAccountExists / ensureSession}）。
 *
 * <p>响应适配：{@code GameMessage.content.code}（0=成功，其余为游戏错误码）→
 * {@link Response}；协议错误码由引擎侧判定（道具短缺短路等）。
 */
public final class KeeperTransport implements MoveCityTransport {

    private final SessionKeeper keeper;

    public KeeperTransport(SessionKeeper keeper) {
        this.keeper = keeper;
    }

    @Override
    public boolean online(String fpid) {
        return keeper.moveCityOnline(fpid);
    }

    @Override
    public boolean ensureOnline(String fpid) {
        return keeper.ensureSession(fpid);
    }

    @Override
    public Response request(String fpid, String proto, Map<String, Object> args, int timeoutSec)
            throws Exception {
        return Response.of(keeper.moveCityRequest(fpid, proto, args, timeoutSec).getContent());
    }

    @Override
    public long serverNowMs(String fpid) {
        return keeper.moveCityNowMs(fpid);
    }

    @Override
    public void holdForMoveCity(String fpid, long untilMs) {
        keeper.holdForMoveCity(fpid, untilMs);
    }

    @Override
    public void clearMoveCityHold(String fpid) {
        keeper.clearMoveCityHold(fpid);
    }

    @Override
    public String roleOf(String fpid) {
        return keeper.moveCityRoleOf(fpid);
    }

    @Override
    public boolean accountExists(String fpid) {
        return keeper.vaultAccountExists(fpid);
    }
}
