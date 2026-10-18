package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;

/**
 * 角色钉扎登录（防 913/888 串区事故）：所有登录调用点统一走这里。
 *
 * <p>背景（WjdrEngine.loginByRole 注释，2026-10-09 实证）：loginauth 的选角参数是
 * <b>uid（角色 uid）</b>——serverid 参数被服务器忽略，恒落「最近角色」。历史上所有
 * 调用点只传 serverid，导致"点 913 登录落 888"，且落地后无人校验、lastServerId 还
 * 把请求值掩蔽成落地值，造成配置/状态跨区污染。
 *
 * <p>钉扎语义：
 * <ul>
 * <li>RoleStore 已登记该 (fpid, sid) 角色的 uid → loginByRole(uid)，并校验落地
 *     uid/serverId 与期望一致；</li>
 * <li>未登记 uid（新角色首登）→ 按 sid 登录，校验落地 serverId；一致则回填登记
 *     uid（学会绑定，此后走钉扎路径）；</li>
 * <li>落错角色 → 把<b>实际落地</b>的角色如实登记（它确实存在于该账号），然后抛
 *     IllegalStateException——绝不静默在错误角色上执行任务/写状态。</li>
 * </ul>
 */
public final class RoleLogin {

    /**
     * 钉扎登录 + 落地校验。
     *
     * @param sid 期望区服；≤0 表示无指定（发现式登录，不校验不登记）
     * @return 登录结果（uid/serverId 与期望一致）
     * @throws IllegalStateException 落错角色（已登记实际角色，信息里带期望/实际）
     */
    public static WjdrEngine.LoginResult loginPinned(Context ctx, WjdrEngine engine,
                                                      String fpid, String sdkToken, long sid) throws Exception {
        if (sid <= 0) {
            return engine.login(fpid, sdkToken, null);
        }
        RoleStore.Role role = RoleStore.roleOf(ctx, fpid, sid);
        long knownUid = role == null ? 0 : role.uid;
        WjdrEngine.LoginResult lr;
        if (knownUid > 0) {
            lr = engine.loginByRole(fpid, sdkToken, knownUid, sid);
            if (lr.uid != knownUid || (lr.serverId > 0 && lr.serverId != sid)) {
                throw wrongRole(ctx, engine, fpid, sid, knownUid, lr);
            }
            return lr;
        }
        lr = engine.login(fpid, sdkToken, sid);
        if (lr.serverId > 0 && lr.serverId != sid) {
            throw wrongRole(ctx, engine, fpid, sid, 0, lr);
        }
        if (lr.uid > 0 && lr.serverId > 0) {
            RoleStore.registerRole(ctx, fpid, lr.serverId, lr.uid,
                    lr.gameName == null ? "" : lr.gameName, lr.level, lr.power,
                    lr.alliance == null ? "" : lr.alliance);
        }
        return lr;
    }

    /**
     * 落错角色处理：①把实际落地的角色如实登记（它确实存在）；
     * ②登录连接还活着——顺手 req_role_list 探测期望区服的<b>正确 uid</b> 并回写，
     * 让历史串区登记的坏绑定在下一次退避重试时自愈；③抛错，绝不在错误角色上执行任务。
     */
    private static IllegalStateException wrongRole(Context ctx, WjdrEngine engine, String fpid,
                                                   long wantSid, long knownUid, WjdrEngine.LoginResult lr) {
        if (lr.uid > 0 && lr.serverId > 0) {
            RoleStore.registerRole(ctx, fpid, lr.serverId, lr.uid,
                    lr.gameName == null ? "" : lr.gameName, lr.level, lr.power,
                    lr.alliance == null ? "" : lr.alliance);
        }
        String heal = "";
        try {
            Long trueUid = engine.discoverRoleUid(wantSid);
            if (trueUid != null && trueUid > 0 && trueUid != lr.uid) {
                RoleStore.updateUid(ctx, fpid, wantSid, trueUid);
                heal = "；已探测到该区正确 uid=" + trueUid + " 并回写，下次重试自动纠正";
            } else if (knownUid > 0 && trueUid == null) {
                // 探测失败但现存 uid 已证实是别人的：清 0 回到按区登录的发现路径
                RoleStore.updateUid(ctx, fpid, wantSid, 0);
                heal = "；原 uid 绑定已证伪清空，下次按区服重新发现";
            }
        } catch (Throwable ignored) {
        }
        return new IllegalStateException("落错角色：期望 K" + wantSid + (knownUid > 0 ? "(uid=" + knownUid + ")" : "(uid未知)")
                + " 实际 K" + lr.serverId + " uid=" + lr.uid
                + (lr.gameName == null || lr.gameName.isEmpty() ? "" : "「" + lr.gameName + "」")
                + heal);
    }

    private RoleLogin() {
    }
}
