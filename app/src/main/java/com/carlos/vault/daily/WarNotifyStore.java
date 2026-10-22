package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/**
 * 战争通知 App 侧配置与状态（prefs "war_notify"）：
 * 短信开关/手机号、已发 eventId 环形记录（{@link WarNotifyGate#SENT_CAP}）、
 * 事件游标、上次发送时刻（{@link WarNotifyGate#SMS_INTERVAL_MS} 节流）。
 * 监视名单/企微配置存服务器侧（/v1/vault/watch），此处只存 App 本地的短信通道状态。
 */
public final class WarNotifyStore {

    private static final String PREFS = "war_notify";
    private static final String KEY_SMS_ON = "sms_on";
    private static final String KEY_SMS_PHONE = "sms_phone";
    private static final String KEY_SENT_IDS = "sent_ids";
    private static final String KEY_LAST_SMS_AT = "last_sms_at";
    private static final String KEY_CURSOR = "events_cursor";
    private static final String KEY_LAST_FPID = "last_fpid";

    private WarNotifyStore() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean smsEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_SMS_ON, false);
    }

    public static void setSmsEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_SMS_ON, on).apply();
    }

    public static String smsPhone(Context ctx) {
        String p = sp(ctx).getString(KEY_SMS_PHONE, "");
        return p == null ? "" : p.trim();
    }

    public static void setSmsPhone(Context ctx, String phone) {
        sp(ctx).edit().putString(KEY_SMS_PHONE, phone == null ? "" : phone.trim()).apply();
    }

    /** 已发 eventId 集合快照（门控去重读）。 */
    public static Set<String> sentIds(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(KEY_SENT_IDS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 记录一条已发事件（eventId 环形 cap 200）+ 刷新节流时刻。 */
    public static void markSent(Context ctx, String eventId, long now) {
        try {
            SharedPreferences sp = sp(ctx);
            java.util.List<String> list = new java.util.ArrayList<>();
            JSONArray arr = new JSONArray(sp.getString(KEY_SENT_IDS, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i));
            if (eventId != null && !eventId.isEmpty()) {
                list.remove(eventId);   // 防重复条目，保持环形语义
                list.add(eventId);
            }
            while (list.size() > WarNotifyGate.SENT_CAP) list.remove(0);
            JSONArray out = new JSONArray();
            for (String s : list) out.put(s);
            sp.edit().putString(KEY_SENT_IDS, out.toString())
                    .putLong(KEY_LAST_SMS_AT, now).apply();
        } catch (Throwable ignored) {
        }
    }

    public static long lastSmsAt(Context ctx) {
        return sp(ctx).getLong(KEY_LAST_SMS_AT, 0L);
    }

    /** 事件游标（/v1/vault/events ackCursor 回传值）。 */
    public static long cursor(Context ctx) {
        return sp(ctx).getLong(KEY_CURSOR, 0L);
    }

    public static void setCursor(Context ctx, long cursor) {
        sp(ctx).edit().putLong(KEY_CURSOR, cursor).apply();
    }

    /** UI 记忆的监视账号（fpid）。 */
    public static String lastFpid(Context ctx) {
        return sp(ctx).getString(KEY_LAST_FPID, "");
    }

    public static void setLastFpid(Context ctx, String fpid) {
        sp(ctx).edit().putString(KEY_LAST_FPID, fpid == null ? "" : fpid).apply();
    }
}
