package com.carlos.vault.wjdr.asset;

/**
 * 资产库实例键：一个游戏角色一条会话一个库。
 *
 * <p>同一 fpid+sid 在不同会话代次（断线重连/被顶号）复用同一个库实例时，
 * {@code sessionEpoch} 用于丢弃旧会话迟到的推送帧（{@link CoreAssetLibrary#accept} 会校验）。
 * App 原生模式与 vault-server 共用本类。
 */
public final class AssetKey {

    public final String fpid;
    public final long sid;
    public final long uid;
    public final long sessionEpoch;

    public AssetKey(String fpid, long sid, long uid, long sessionEpoch) {
        this.fpid = fpid == null ? "" : fpid.trim();
        this.sid = sid;
        this.uid = uid;
        this.sessionEpoch = sessionEpoch;
    }

    /**
     * 资产库角色主键。
     *
     * <p>R2 Phase 2（2026-10-29）：同账号同服多角色（fpid:sid 相同、uid 不同）各自一条会话、
     * 各持一个资产库，主键必须带 uid 才能共存；uid&lt;=0（旧/身份未落地）退回旧 {@code fpid:sid}。
     */
    public String roleKey() {
        return uid > 0L ? fpid + ":" + sid + ":" + uid : fpid + ":" + sid;
    }

    /** 旧角色主键（fpid:sid，无 uid）：仅旧调用/回退查询用。 */
    public String legacyRoleKey() {
        return fpid + ":" + sid;
    }

    @Override
    public String toString() {
        return "AssetKey{" + fpid + ":" + sid + "/" + uid + "@" + sessionEpoch + "}";
    }
}
