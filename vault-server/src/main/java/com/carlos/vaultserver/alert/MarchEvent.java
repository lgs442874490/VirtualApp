package com.carlos.vaultserver.alert;

import java.util.Map;

/**
 * 统一事件模型（设计 §6）。
 *
 * <p>P1 只定义字段与最小构造，供判定/文案使用；P2/P3 再接两个引擎的归一化工厂。
 * 坐标未知用 -1（0,0 是合法地图坐标）。
 */
public final class MarchEvent {

    public long ownerUid;
    public String ownerFid = "";
    public long marchId;
    public long marchType;
    public String behavior = "";
    public int sx = -1;
    public int sy = -1;
    public int ex = -1;
    public int ey = -1;
    public long startSec;
    public long endSec;
    public long troops;
    public long troopsTotal;
    /** 空=非 PvE；非空=命中特征（顶层键/target 子对象/master_type）。 */
    public String pveReason = "";
    public boolean selfMarch;
    public long ownerAid;
    public long targetUid;
    public long targetAid;
    public String rawProto = "";
    public Source source = Source.PUSH;
    public String observerFpid = "";
    public long observerSid;
    public long detectedAt;

    public enum Source { PUSH, SCAN }
}
