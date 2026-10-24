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

    /** fpid:sid 主键（同角色跨会话复用库时用它查）。 */
    public String roleKey() {
        return fpid + ":" + sid;
    }

    @Override
    public String toString() {
        return "AssetKey{" + fpid + ":" + sid + "/" + uid + "@" + sessionEpoch + "}";
    }
}
