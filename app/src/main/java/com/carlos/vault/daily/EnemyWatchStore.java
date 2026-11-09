package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敌情监控（EnemyWatch）全局配置与事件留痕。
 * 与 FnParams 的按角色存储不同：被标记敌人是"账号域"的对象（跨角色的同服敌人），
 * 且监控挂在所有在线会话上，因此用全局 prefs（enemy_watch）单源。
 * 名单行格式："kid:fid 备注"，如 "888:57347368 仇人A"（备注可空）。
 */
public final class EnemyWatchStore {

    private static final String PREFS = "enemy_watch";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WEBHOOK = "wecom_webhook";
    private static final String KEY_SMS_PHONE = "sms_phone";
    private static final String KEY_WECOM_ON = "wecom_on";
    private static final String KEY_SMS_ON = "sms_on";
    private static final String KEY_SMS_ESCALATE_ONLY = "sms_escalate_only";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_SEEDED = "seeded";
    /** 本地 seed 是否已上报到服务端新名单（一次性迁移标记）。 */
    private static final String KEY_MIGRATED = "migrated_to_alert";
    private static final String KEY_RECENT = "recent";

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

        public String display() {
            return (note.isEmpty() ? "" : note + "-") + uid + "@" + kid;
        }
    }

    private EnemyWatchStore() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 首次使用时预置用户给定的首批标记（888 服 4 个 uid），总开关默认关。 */
    private static void seedIfNeeded(Context ctx) {
        SharedPreferences sp = sp(ctx);
        if (sp.getBoolean(KEY_SEEDED, false)) return;
        String[] uids = {"57347368", "123861751", "64872459", "146604981"};
        try {
            JSONArray arr = new JSONArray();
            for (String u : uids) arr.put(new JSONObject().put("kid", 888).put("uid", Long.parseLong(u)).put("note", ""));
            sp.edit().putString(KEY_ENTRIES, arr.toString())
                    .putBoolean(KEY_WECOM_ON, true)
                    .putBoolean(KEY_SMS_ON, true)
                    .putBoolean(KEY_SMS_ESCALATE_ONLY, true)
                    .putBoolean(KEY_SEEDED, true)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    public static boolean enabled(Context ctx) {
        seedIfNeeded(ctx);
        return sp(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static String webhook(Context ctx) {
        return sp(ctx).getString(KEY_WEBHOOK, "");
    }

    public static String smsPhone(Context ctx) {
        return sp(ctx).getString(KEY_SMS_PHONE, "");
    }

    public static boolean wecomOn(Context ctx) {
        return sp(ctx).getBoolean(KEY_WECOM_ON, true);
    }

    public static boolean smsOn(Context ctx) {
        return sp(ctx).getBoolean(KEY_SMS_ON, true);
    }

    /** 短信仅随升级事件（加速中）发送；false = 所有敌情事件都发短信。 */
    public static boolean smsEscalateOnly(Context ctx) {
        return sp(ctx).getBoolean(KEY_SMS_ESCALATE_ONLY, true);
    }

    /** 名单，按 kid 分组（kid → uid → 备注）。 */
    public static Map<Long, Map<Long, String>> byKid(Context ctx) {
        seedIfNeeded(ctx);
        Map<Long, Map<Long, String>> out = new HashMap<>();
        for (Entry e : entries(ctx)) {
            Map<Long, String> m = out.get(e.kid);
            if (m == null) {
                m = new HashMap<>();
                out.put(e.kid, m);
            }
            m.put(e.uid, e.note);
        }
        return out;
    }

    public static List<Entry> entries(Context ctx) {
        seedIfNeeded(ctx);
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

    /** 解析名单文本（每行 "kid:fid 备注"）并整体保存。返回非法行数（-1=uid 解析失败行数）。 */
    public static int save(Context ctx, boolean enabled, String webhook, String smsPhone,
                           boolean wecomOn, boolean smsOn, boolean smsEscalateOnly, String entriesText) {
        JSONArray arr = new JSONArray();
        int bad = 0;
        if (entriesText != null) {
            for (String line : entriesText.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                String[] parts = s.split("\\s+", 2);
                String[] head = parts[0].split(":");
                try {
                    long kid, uid;
                    String note = parts.length > 1 ? parts[1].trim() : "";
                    if (head.length >= 2) {
                        kid = Long.parseLong(head[0].trim());
                        uid = Long.parseLong(head[1].trim());
                    } else {
                        kid = 0;
                        uid = Long.parseLong(head[0].trim());
                    }
                    if (uid <= 0) throw new NumberFormatException();
                    if (kid <= 0) kid = 888;   // 未写服号时按首批标记的服务器兜底
                    arr.put(new JSONObject().put("kid", kid).put("uid", uid).put("note", note));
                } catch (Throwable t) {
                    bad++;
                }
            }
        }
        sp(ctx).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_WEBHOOK, webhook == null ? "" : webhook.trim())
                .putString(KEY_SMS_PHONE, smsPhone == null ? "" : smsPhone.trim())
                .putBoolean(KEY_WECOM_ON, wecomOn)
                .putBoolean(KEY_SMS_ON, smsOn)
                .putBoolean(KEY_SMS_ESCALATE_ONLY, smsEscalateOnly)
                .putString(KEY_ENTRIES, arr.toString())
                .apply();
        return bad;
    }

    /** 最近事件留痕（新事件插最前，cap 30），供配置界面展示。 */
    public static void log(Context ctx, String line) {
        try {
            SharedPreferences sp = sp(ctx);
            List<String> list = new ArrayList<>();
            JSONArray arr = new JSONArray(sp.getString(KEY_RECENT, "[]"));
            for (int i = 0; i < arr.length() && i < 29; i++) list.add(arr.getString(i));
            list.add(0, nowStamp() + " " + line);
            JSONArray out = new JSONArray();
            for (String s : list) out.put(s);
            sp.edit().putString(KEY_RECENT, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static List<String> recent(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(KEY_RECENT, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String nowStamp() {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }
}
