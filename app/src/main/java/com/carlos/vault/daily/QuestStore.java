package com.carlos.vault.daily;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** 每日任务进度持久化（daily_state，按 角色=fpid:serverid 隔离）。 */
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

    public static State load(Context ctx, String fpid, long serverid) {
        State s = new State();
        String suffix = fpid + ":" + serverid;
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    public static void save(Context ctx, String fpid, long serverid, State s) {
        String suffix = fpid + ":" + serverid;
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
