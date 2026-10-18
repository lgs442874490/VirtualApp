package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志（每 角色×功能 一份，最近50条）+ 每日完成状态。
 * 状态机：0未开始 / 1进行中 / 2已完成 / 3失败（当日）。
 */
public class LogStore {

    public static class Entry {
        public long ts;
        public boolean ok;
        public String reason = "";     // 失败原因 / 结果摘要
        public String detail = "";
        // 以下为扩展字段（对标原版 daily_execution_log 的 trigger/failureCategory/durationMillis/attempt）。
        // 旧条目没有这些字段，读取用 opt* 兜底为零值，零迁移。
        public String trigger = "";    // 触发来源：手动/定时/通知（对齐 modState 的 _trg）
        public long durMs;             // 单任务执行耗时；0=未进入执行（登录失败/配置未启用的跳过）
        public String category = "";   // DailyResult.Category 名（CLAIMED/SKIPPED/DEGRADED/SESSION_CLOSED/ERROR）
        public int attempt;            // 执行尝试序号；0=未执行即跳过，1=首次执行
    }

    private final SharedPreferences prefs;

    public LogStore(Context ctx) {
        prefs = ctx.getSharedPreferences("daily_logs", Context.MODE_PRIVATE);
    }

    private static String key(String fpid, long serverid, String fnId) {
        return fpid + ":" + serverid + ":" + fnId;
    }

    /** 追加一条日志（保留最近50条，扩展字段版：触发来源/耗时/结果分级/尝试序号）。 */
    public void append(String fpid, long serverid, String fnId, boolean ok, String reason, String detail,
                       String trigger, long durMs, String category, int attempt) {
        try {
            List<Entry> list = logs(fpid, serverid, fnId);
            Entry e = new Entry();
            e.ts = System.currentTimeMillis();
            e.ok = ok;
            e.reason = reason == null ? "" : reason;
            e.detail = detail == null ? "" : detail;
            e.trigger = trigger == null ? "" : trigger;
            e.durMs = durMs;
            e.category = category == null ? "" : category;
            e.attempt = attempt;
            list.add(0, e);
            writeEntries(fpid, serverid, fnId, list);
        } catch (Exception ignored) {
        }
    }

    /** 追加一条日志（保留最近50条）。子项级等无扩展信息场景用；触发来源取当前 DailyRunner.SOURCE。 */
    public void append(String fpid, long serverid, String fnId, boolean ok, String reason, String detail) {
        append(fpid, serverid, fnId, ok, reason, detail, DailyRunner.SOURCE, 0, "", 0);
    }

    private void writeEntries(String fpid, long serverid, String fnId, List<Entry> list) {
        try {
            while (list.size() > 50) list.remove(list.size() - 1);
            JSONArray arr = new JSONArray();
            for (Entry x : list) {
                JSONObject o = new JSONObject();
                o.put("ts", x.ts);
                o.put("ok", x.ok);
                o.put("reason", x.reason);
                o.put("detail", x.detail);
                o.put("trg", x.trigger);
                o.put("dur", x.durMs);
                o.put("cat", x.category);
                o.put("att", x.attempt);
                arr.put(o);
            }
            prefs.edit().putString(key(fpid, serverid, fnId), arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<Entry> logs(String fpid, long serverid, String fnId) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(key(fpid, serverid, fnId), "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.ts = o.optLong("ts", 0);
                e.ok = o.optBoolean("ok", false);
                e.reason = o.optString("reason", "");
                e.detail = o.optString("detail", "");
                e.trigger = o.optString("trg", "");
                e.durMs = o.optLong("dur", 0);
                e.category = o.optString("cat", "");
                e.attempt = o.optInt("att", 0);
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- 每日完成状态（角色×功能×日期） ----------

    public static final int ST_NOT_STARTED = 0;
    public static final int ST_RUNNING = 1;
    /** 已领取：任务成功且有到账证据。 */
    public static final int ST_DONE = 2;
    public static final int ST_FAILED = 3;
    /**
     * 已检查但无可领/已领/活动未开放（SKIPPED）。
     *
     * <p>2026-10 新增：历史上 SKIPPED 与 CLAIMED 都写 ST_DONE，UI 显示“已完成”，
     * 造成“日常任务显示已完成但游戏内奖励未触发领取”的误判。独立状态让界面能显示
     * “无可领/已领”，而不是“已完成”。
     */
    public static final int ST_SKIPPED = 4;

    private static String today() {
        return dayKey(0);
    }

    /** 偏移天数对应的日期键（dayKey(0)=今天，dayKey(-1)=昨天）。 */
    private static String dayKey(int dayOffset) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(c.getTime());
    }

    /**
     * 清理过期的当日状态键（{@code st:<date>:…}）。
     *
     * <p>当日状态键按日期分键，跑一次就多一批；没有清理会无限堆积。
     * 只保留今天与昨天（昨天仅作跨零点缓冲），更早的一律删除。
     * 调度服务启动时调用（开机/应用更新/页面拉活都会走 onCreate，频率足够）。
     *
     * @return 清理掉的键数量（0=无需清理）
     */
    public int pruneStaleDayKeys() {
        try {
            String keepToday = dayKey(0);
            String keepYesterday = dayKey(-1);
            List<String> remove = new ArrayList<>();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith("st:") && !k.startsWith(keepToday) && !k.startsWith(keepYesterday)) {
                    remove.add(k);
                }
            }
            if (remove.isEmpty()) return 0;
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : remove) ed.remove(k);
            ed.apply();
            return remove.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String stKey(String fpid, long serverid) {
        return "st:" + today() + ":" + fpid + ":" + serverid;
    }

    public int status(String fpid, long serverid, String fnId) {
        return prefs.getInt(stKey(fpid, serverid) + ":" + fnId, ST_NOT_STARTED);
    }

    public void setStatus(String fpid, long serverid, String fnId, int st) {
        prefs.edit().putInt(stKey(fpid, serverid) + ":" + fnId, st).apply();
    }

    // ---------- B表子项级（六维②③）：每 子项 当日状态 + 最近动作 ----------

    /** 子项当日状态（0未开始/2已领取/3失败/4已检查无可领）。 */
    public int subStatus(String fpid, long serverid, String fnId, String subId) {
        return prefs.getInt(stKey(fpid, serverid) + ":" + fnId + ":" + subId, ST_NOT_STARTED);
    }

    /** 子项最近一条动作日志。 */
    public Entry subLast(String fpid, long serverid, String fnId, String subId) {
        List<Entry> l = logs(fpid, serverid, fnId + ":" + subId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 把 DailyResult.subResults 落盘：当日子项状态 + 动作日志（复用 append，保留最近50条）。 */
    public void applySubResults(String fpid, long serverid, String fnId,
                                java.util.Map<String, String> subs) {
        if (subs == null || subs.isEmpty()) return;
        for (java.util.Map.Entry<String, String> e : subs.entrySet()) {
            try {
                String v = e.getValue();
                boolean skip = v.startsWith("skip:");
                boolean fail = v.startsWith("fail:");
                String note = v.substring(v.indexOf(':') + 1);
                append(fpid, serverid, fnId + ":" + e.getKey(), !fail, note, "");
                // “已检查但无可领”不能再标成已完成，否则界面显示完成、游戏里却没奖励。
                int st = skip ? ST_SKIPPED : (fail ? ST_FAILED : ST_DONE);
                setStatus(fpid, serverid, fnId + ":" + e.getKey(), st);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当日某角色完成统计：{claimed, failed, running, total(implemented), skipped}。 */
    public int[] dayStats(String fpid, long serverid) {
        int done = 0, failed = 0, running = 0, total = 0, skipped = 0;
        for (FnRegistry.Fn f : FnRegistry.all()) {
            if (!f.implemented) continue;
            total++;
            int st = status(fpid, serverid, f.id);
            if (st == ST_DONE) done++;
            else if (st == ST_FAILED) failed++;
            else if (st == ST_RUNNING) running++;
            else if (st == ST_SKIPPED) skipped++;
        }
        return new int[]{done, failed, running, total, skipped};
    }
}
