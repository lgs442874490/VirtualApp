package com.carlos.vault.daily.guardian;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敌情板 ThreatBoard（设计 §6.11.8 v1.4）：战争态期间的全部敌方威胁源，多敌跟踪，
 * 是"全敌静默/全敌开盾"退出判据的数据底座。
 *
 * <ul>
 *   <li>威胁源只增不减（本次战争态内）：第 29 分钟出现的第二个敌人，其 30 分钟静默
 *       从零起算——"先后侦查"的多敌场景天然满足"任一未静默则不退"（用户规则 3）；</li>
 *   <li>该敌人的任何信号都刷新 lastSignalAt：侦查行军、进攻行军（含加减速/召回）、
 *       佯攻、以及"开盾后撤盾"（撤盾=恢复攻击能力，视为新信号【A-26】）；</li>
 *   <li>战争态退出时敌情板整体归档（快照文本交 Listener），重入战争态时重建为空板。</li>
 * </ul>
 *
 * 纯 java.util 实现：App 与 vault-server 共用同一份（vault-server build.gradle 按
 * 单一事实源直编本文件，禁止复制漂移）。
 */
public final class ThreatBoard {

    /** 最近信号类型（够 WarState 判定与展示用，不照抄全部 MarchKind）。 */
    public enum Kind { SCOUT, ATTACK, FEINT, OTHER }

    /** 一个敌方威胁源。 */
    public static final class Enemy {
        public final long ownerUid;
        /** 该敌人的最近信号时刻（epoch ms）——任何信号刷新。 */
        public volatile long lastSignalAt;
        /** 当前是否开盾【A-26 观测】：从未观测到开盾= false（保守，不参与全敌开盾退出）。 */
        public volatile boolean shielded = false;
        public volatile long shieldSeenAt;
        /** 本次战争态期间该敌人的信号总数（骚扰度展示）。 */
        public volatile int signalCount;
        public volatile Kind lastKind = Kind.OTHER;

        Enemy(long ownerUid, long at) {
            this.ownerUid = ownerUid;
            this.lastSignalAt = at;
        }
    }

    private final Map<Long, Enemy> enemies = new ConcurrentHashMap<>();
    /** 全板最近任一信号时刻（ALERT 退出静默判定用；0=板空）。 */
    private volatile long anySignalAt = 0;

    /** 信号入账（get-or-create 威胁源 + 刷新 lastSignalAt/signalCount）。 */
    public synchronized void onSignal(long ownerUid, Kind kind, long at) {
        if (ownerUid <= 0) return;
        Enemy e = enemies.get(ownerUid);
        if (e == null) {
            e = new Enemy(ownerUid, at);
            enemies.put(ownerUid, e);
        }
        e.lastSignalAt = at;
        e.signalCount++;
        if (kind != null) e.lastKind = kind;
        if (at > anySignalAt) anySignalAt = at;
    }

    /**
     * 开盾/撤盾入账（§6.11.5【A-26】）：开盾=true（静默，具备退出判据②资格，但<b>不</b>刷新
     * lastSignalAt——开盾不是攻击信号）；撤盾=恢复攻击能力=新信号，刷新 lastSignalAt。
     */
    public synchronized void onShield(long ownerUid, boolean on, long at) {
        if (ownerUid <= 0) return;
        Enemy e = enemies.get(ownerUid);
        if (e == null) {
            if (!on) return;   // 从未见过的敌人撤盾无从入账（板条目由行军/战斗信号创建）
            e = new Enemy(ownerUid, at);
            enemies.put(ownerUid, e);
        }
        if (on) {
            e.shielded = true;
            e.shieldSeenAt = at;
        } else {
            e.shielded = false;
            e.shieldSeenAt = 0;
            onSignal(ownerUid, Kind.ATTACK, at);   // 撤盾=新信号，重置静默计时
        }
    }

    public Enemy enemy(long ownerUid) {
        return ownerUid <= 0 ? null : enemies.get(ownerUid);
    }

    /** 本次战争态累计出现过的全部威胁源（只读快照）。 */
    public List<Enemy> enemies() {
        return new ArrayList<>(enemies.values());
    }

    public int size() {
        return enemies.size();
    }

    /** 全板最近任一信号时刻（0=板空）。 */
    public long anySignalAt() {
        return anySignalAt;
    }

    /** 最活跃威胁源（signalCount 最大；并列取最近信号者；板空 null）。 */
    public Enemy mostActive() {
        Enemy top = null;
        for (Enemy e : enemies.values()) {
            if (top == null || e.signalCount > top.signalCount
                    || (e.signalCount == top.signalCount && e.lastSignalAt > top.lastSignalAt)) {
                top = e;
            }
        }
        return top;
    }

    /**
     * 退出判据①：∀e ∈ 威胁源: now − e.lastSignalAt ≥ quietMs。
     * 多敌语义：9 个静默 2 小时 + 1 个 5 分钟前还在侦查 → false，不退。板空=true（防御：
     * 人工强制入战时无信号的场景由 manualLock 兜底，不靠空板挡）。
     */
    public boolean allQuiet(long now, long quietMs) {
        for (Enemy e : enemies.values()) {
            if (now - e.lastSignalAt < quietMs) return false;
        }
        return true;
    }

    /** 已静默满 quietMs 的威胁源数（静默进度展示）。 */
    public int quietCount(long now, long quietMs) {
        int n = 0;
        for (Enemy e : enemies.values()) {
            if (now - e.lastSignalAt >= quietMs) n++;
        }
        return n;
    }

    /**
     * 退出判据②（敌情板半边）：∀e: e.shielded。板空=false（无人可判不成立）。
     * "且无在途进攻行军"的另一半在 WarState（在途进攻表不在板上）。
     */
    public boolean allShielded() {
        if (enemies.isEmpty()) return false;
        for (Enemy e : enemies.values()) {
            if (!e.shielded) return false;
        }
        return true;
    }

    /** 归档快照（X4：退出时交 ProtoLogger/guardian 日志；重入战争态前整板重建）。 */
    public String archiveText(long now, long quietMs) {
        StringBuilder sb = new StringBuilder("敌情板归档×").append(enemies.size());
        for (Enemy e : enemies.values()) {
            sb.append(" | uid").append(e.ownerUid)
                    .append(" 信号").append(e.signalCount)
                    .append(" 次/最近 ").append(kindText(e.lastKind))
                    .append(" 静默 ").append((now - e.lastSignalAt) / 60_000L).append("min")
                    .append(e.shielded ? " 开盾" : "");
        }
        return sb.toString();
    }

    /** 状态行/通知摘要（E5）：威胁源×N、最活跃者、静默进度。 */
    public String summary(long now, long quietMs) {
        if (enemies.isEmpty()) return "敌情板空";
        Enemy top = mostActive();
        return "威胁源×" + enemies.size()
                + " · 最活跃 uid" + top.ownerUid + "(" + kindText(top.lastKind)
                + "×" + top.signalCount + ")"
                + " · 静默进度 " + quietCount(now, quietMs) + "/" + enemies.size();
    }

    /** 退出即归档：整体清板（归档文本先经 archiveText 交宿主；重入战争态重建空板）。 */
    public synchronized void reset() {
        enemies.clear();
        anySignalAt = 0;
    }

    private static String kindText(Kind k) {
        switch (k == null ? Kind.OTHER : k) {
            case SCOUT: return "侦查";
            case ATTACK: return "进攻";
            case FEINT: return "佯攻";
            default: return "信号";
        }
    }
}
