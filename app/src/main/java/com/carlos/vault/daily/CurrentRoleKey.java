package com.carlos.vault.daily;

/**
 * {@code home:current_role} 键的纯逻辑（全 App 唯一口径，便于单测）。
 *
 * <p>键格式（历史兼容）：{@code fpid:sid}；uid>0 时 {@code fpid:sid:uid}
 * （同服多角色，2026-10-20 起）。
 *
 * <p><b>2026-11-03 扩展</b>：允许 {@code sid=0}（{@code fpid:0}）表示
 * 「当前账号暂无角色数据」——键仍指向该账号，今日/日常页按该账号渲染空数据。
 * 旧实现切到无角色账号时只改 {@code current_account} 不写 {@code current_role}，
 * 页面继续按旧账号的角色渲染，表现为「提示切换成功、数据仍是旧账号」（真机 bug）。
 */
public final class CurrentRoleKey {

    private CurrentRoleKey() {
    }

    /**
     * 组装键：{@code fpid:sid}；sid>0 且 uid>0 时追加 {@code :uid}。
     * fpid 为空返回空串（调用方按「无当前角色」处理）。
     */
    public static String of(String fpid, long sid, long uid) {
        String f = fpid == null ? "" : fpid.trim();
        if (f.isEmpty()) return "";
        long s = Math.max(0L, sid);
        StringBuilder sb = new StringBuilder(f).append(':').append(s);
        if (s > 0L && uid > 0L) sb.append(':').append(uid);
        return sb.toString();
    }

    /** 拆解键；非法（空/只有 fpid/非数字）返回 null。 */
    public static Parts parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String[] p = s.split(":");
        if (p.length < 2) return null;
        String fpid = p[0].trim();
        if (fpid.isEmpty()) return null;
        long sid;
        try {
            sid = Long.parseLong(p[1].trim());
        } catch (Throwable t) {
            return null;
        }
        if (sid < 0L) return null;
        long uid = 0L;
        if (p.length >= 3) {
            try {
                uid = Long.parseLong(p[2].trim());
            } catch (Throwable ignored) {
                uid = 0L;
            }
            if (uid < 0L) uid = 0L;
        }
        return new Parts(fpid, sid, uid);
    }

    /** 键是否属于某账号（fpid 相等；fpid 为空视为不匹配）。 */
    public static boolean belongsTo(String raw, String fpid) {
        Parts p = parse(raw);
        return p != null && fpid != null && p.fpid.equals(fpid);
    }

    /** 拆解结果：账号 fpid + 角色 sid/uid（sid=0 表示该账号暂无角色数据）。 */
    public static final class Parts {
        public final String fpid;
        public final long sid;
        public final long uid;

        public Parts(String fpid, long sid, long uid) {
            this.fpid = fpid;
            this.sid = sid;
            this.uid = uid;
        }

        /** 是否已选中具体角色（sid>0）。 */
        public boolean hasRole() {
            return sid > 0L;
        }

        @Override
        public String toString() {
            return fpid + ":" + sid + (sid > 0L && uid > 0L ? ":" + uid : "");
        }
    }
}
