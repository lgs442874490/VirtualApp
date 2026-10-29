package com.carlos.vaultserver.task;

/**
 * 角色执行单元（R2 Phase 2，2026-10-29）：同账号同服多角色并行的最小调度单位。
 *
 * <p>为什么需要：会话层早已按 {@code (fpid, sid, uid)} 组织（SessionKeeper 多角色容器），
 * 但任务调度/状态键/采集仍按账号 {@code fpid} 或 {@code fpid:sid}，同服双角色会互相覆盖。
 * Phase 2 起调度与状态统一用本对象表达“某个角色的某个区服”。
 *
 * <p>兼容约定（与 Phase 1 一致）：{@code uid<=0} 表示旧/兼容路径，状态键退回
 * {@code fpid:sid} 形态，行为与 Phase 1 之前的现网一致。
 */
public final class RoleKey {

    public final String fpid;
    public final long sid;
    public final long uid;

    public RoleKey(String fpid, long sid, long uid) {
        this.fpid = fpid == null ? "" : fpid;
        this.sid = sid;
        this.uid = uid;
    }

    /** 调度内存键：{@code fpid|sid|uid}（uid<=0 时以 0 占位，不与真实角色混淆）。 */
    public String key() {
        return fpid + "|" + sid + "|" + uid;
    }

    /** 状态键后缀：{@code fpid:sid:uid}（roleScoped 才应使用）。 */
    public String suffix() {
        return fpid + ":" + sid + ":" + uid;
    }

    /** 是否按角色隔离（uid>0）。 */
    public boolean roleScoped() {
        return uid > 0L;
    }

    /** 是否有效（fpid 非空且 sid>0）。 */
    public boolean valid() {
        return !fpid.isEmpty() && sid > 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoleKey)) return false;
        RoleKey r = (RoleKey) o;
        return sid == r.sid && uid == r.uid && fpid.equals(r.fpid);
    }

    @Override
    public int hashCode() {
        return (fpid.hashCode() * 31 + Long.hashCode(sid)) * 31 + Long.hashCode(uid);
    }

    @Override
    public String toString() {
        return key();
    }
}
