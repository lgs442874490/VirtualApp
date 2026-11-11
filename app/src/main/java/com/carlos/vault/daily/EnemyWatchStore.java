package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地敌情 seed 数据存储（仅保留一次性迁移用途）。
 *
 * <p>2026-11-12：App 本地敌情监测（EnemyWatch）已随旧本地模式整体移除，本类只保留
 * 「把本地历史上真实存在过的名单一次性上报到服务端告警名单」所需的最小读取能力；
 * 名单行格式仍为 "kid:fid 备注"，历史 prefs（{@code enemy_watch}）保持不变以便迁移。
 * P2-13：不再对全新设备预置 seed（4 个硬编码 uid）——没有本地名单的设备迁移为空。
 */
public final class EnemyWatchStore {

    private static final String PREFS = "enemy_watch";
    private static final String KEY_ENTRIES = "entries";
    /** 本地名单是否已上报到服务端新名单（一次性迁移标记）。 */
    private static final String KEY_MIGRATED = "migrated_to_alert";

    /** 单个被标记敌人。 */
    public static final class Entry {
        public final long kid;
        public final long uid;
        public final String note;

        public Entry(long kid, long uid, String note) {
            this.kid = kid;
            this.uid = uid;
            this.note = note == null ? "" : note;
        }
    }

    private EnemyWatchStore() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Entry> entries(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(KEY_ENTRIES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optLong("kid"), o.optLong("uid"), o.optString("note")));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean migratedToAlert(Context ctx) {
        return sp(ctx).getBoolean(KEY_MIGRATED, false);
    }

    public static void setMigratedToAlert(Context ctx, boolean done) {
        sp(ctx).edit().putBoolean(KEY_MIGRATED, done).apply();
    }
}
