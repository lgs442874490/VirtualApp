package com.carlos.vault.daily;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 每日任务进度持久化（daily_state）。
 *
 * <p>键后缀：{@code fpid:serverid[:uid]}。
 * <ul>
 *   <li>{@code uid>0}：按角色隔离（R2 Phase2 口径，同账号同服多角色并行时不串号）；</li>
 *   <li>{@code uid<=0}：旧键 {@code fpid:serverid}（兼容存量数据/未迁移调用方）。</li>
 * </ul>
 *
 * <p>迁移：uid 已知但三段键从未写入（{@code questts:} 缺失且无任何进度）时，读取回退旧两段键，
 * 让存量数据的角色至少还能看到最近一次同步结果；下一次 save 即落三段键。
 */
public final class QuestStore {

    public static class State {
        /** questId → 当前进度 */
        public final Map<Long, Integer> progress = new HashMap<>();
        /** 已领取奖励的 questId 集合（finishs） */
        public final Set<Long> claimed = new HashSet<>();
        /** 活跃度积分，未取到为 -1 */
        public int integral = -1;
        /** 已达成宝箱档数，未取到为 -1 */
        public int boxes = -1;
        /** 数据抓取时间（0=从未同步） */
        public long ts;
    }

    private static final String PREFS = "daily_state";

    /** 键后缀：uid>0 → {@code fpid:sid:uid}；否则旧键 {@code fpid:sid}。 */
    static String suffix(String fpid, long serverid, long uid) {
        return fpid + ":" + serverid + (uid > 0L ? ":" + uid : "");
    }

    /** 兼容旧签名（不区分同服多角色；仅 uid 未知时使用）。 */
    public static State load(Context ctx, String fpid, long serverid) {
        return load(ctx, fpid, serverid, 0L);
    }

    public static State load(Context ctx, String fpid, long serverid, long uid) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        State s = read(sp, suffix(fpid, serverid, uid));
        // 迁移回退：uid 已知但三段键无任何数据 → 读旧两段键（下一次 save 后即用三段键）。
        if (uid > 0L && s.ts == 0L && s.integral < 0 && s.progress.isEmpty() && s.claimed.isEmpty()) {
            State legacy = read(sp, suffix(fpid, serverid, 0L));
            if (legacy.ts > 0L || legacy.integral >= 0 || !legacy.progress.isEmpty()
                    || !legacy.claimed.isEmpty()) {
                return legacy;
            }
        }
        return s;
    }

    private static State read(android.content.SharedPreferences sp, String suffix) {
        State s = new State();
        s.integral = sp.getInt("act:" + suffix, -1);
        s.boxes = sp.getInt("boxes:" + suffix, -1);
        s.ts = sp.getLong("questts:" + suffix, 0);
        try {
            String p = sp.getString("questprog:" + suffix, null);
            if (p != null) {
                JSONObject o = new JSONObject(p);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    s.progress.put(Long.parseLong(k), o.optInt(k));
                }
            }
            String c = sp.getString("questdone:" + suffix, null);
            if (c != null) {
                JSONArray a = new JSONArray(c);
                for (int i = 0; i < a.length(); i++) s.claimed.add(a.getLong(i));
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** 兼容旧签名（uid 未知 → 写旧两段键）。 */
    public static void save(Context ctx, String fpid, long serverid, State s) {
        save(ctx, fpid, serverid, 0L, s);
    }

    public static void save(Context ctx, String fpid, long serverid, long uid, State s) {
        String suffix = suffix(fpid, serverid, uid);
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Long, Integer> e : s.progress.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue().intValue());
            }
            JSONArray a = new JSONArray();
            for (Long qid : s.claimed) a.put(qid.longValue());
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("questprog:" + suffix, o.toString())
                    .putString("questdone:" + suffix, a.toString())
                    .putLong("questts:" + suffix, s.ts)
                    .putInt("act:" + suffix, s.integral)
                    .putInt("boxes:" + suffix, s.boxes)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
