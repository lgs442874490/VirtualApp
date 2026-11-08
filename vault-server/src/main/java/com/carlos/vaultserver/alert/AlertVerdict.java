package com.carlos.vaultserver.alert;

/**
 * 告警判定结果。
 *
 * <p>P1（口径统一）阶段先保持与 {@code WarNotify.MarchVerdict} 字段/拒绝码完全一致，
 * 保证旧测试断言不改；路由/触发通道字段留给 P3 出口统一时再填。
 */
public final class AlertVerdict {

    public final boolean accepted;
    /** 拒绝码；accepted=true 时为 null。 */
    public final String reject;
    public final long marchId;
    public final long ownerUid;
    public final long type;
    public final long state;

    private AlertVerdict(boolean accepted, String reject, long marchId, long ownerUid,
                         long type, long state) {
        this.accepted = accepted;
        this.reject = reject;
        this.marchId = marchId;
        this.ownerUid = ownerUid;
        this.type = type;
        this.state = state;
    }

    public static AlertVerdict ok(long marchId, long ownerUid, long type, long state) {
        return new AlertVerdict(true, null, marchId, ownerUid, type, state);
    }

    public static AlertVerdict no(String reject, long marchId, long ownerUid, long type, long state) {
        return new AlertVerdict(false, reject, marchId, ownerUid, type, state);
    }
}
