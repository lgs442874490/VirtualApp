package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务器整轮执行：按 App 端 daily_state/daily_logs 同键型落结果（log:/st:/summary:/mod:），
 * App 经 fetchState 回写后，运行日志/当日完成状态机/功能行摘要零改动渲染。
 * 未迁移任务（配置开启但不在本批）记 SKIPPED"待迁移"，不静默丢失。
 */
public final class RoundRunner {

    private RoundRunner() {
    }

    /** 在会话线程上执行整轮（fn=null）或单任务。返回汇总文本。 */
    public static String runRound(WjdrEngine engine, VaultStore.Account acc, long sid,
                                  StateStore store, Map<String, JSONObject> params,
                                  String fnOnly, String trigger) {
        String fpid = acc.fpid;
        Map<String, Boolean> config = acc.roleConfig.get(String.valueOf(sid));
        TaskContext tc = new TaskContext(engine, fpid, sid, store, params, trigger);
        StringBuilder summary = new StringBuilder();
        int done = 0, claimed = 0, abnormal = 0;
        List<ServerTasks.TaskDef> defs = ServerTasks.all();
        for (ServerTasks.TaskDef def : defs) {
            if (fnOnly != null && !fnOnly.equals(def.id)) continue;
            Boolean enabled = config == null ? null : config.get(def.id);
            boolean on = enabled == null ? def.enabledByDefault : enabled;
            TaskOutcome r;
            if (!on) {
                r = new TaskOutcome(def.id, def.name, TaskOutcome.Category.SKIPPED, "配置未启用");
            } else {
                long t0 = System.currentTimeMillis();
                try {
                    r = def.executor.run(tc);
                } catch (Throwable t) {
                    r = ServerTasks.classifyException(t, def.name);
                }
                r.durationMs = System.currentTimeMillis() - t0;
            }
            // 落键一律用目录 id（classify* 生成的 taskId 可能是协议名/任务名，仅展示用）
            applyResult(store, fpid, sid, def.id, r, trigger);
            if (r.successLike()) done++;
            if (r.category == TaskOutcome.Category.CLAIMED) claimed++;
            if (r.category == TaskOutcome.Category.DEGRADED
                    || r.category == TaskOutcome.Category.ERROR) abnormal++;
            if (summary.length() > 0) summary.append('\n');
            summary.append(r.toString());
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) {
                FileLog.warn("整轮中断 fpid={} fn={}：{}", fpid, def.id, r.message);
                break;   // 会话已断，后续任务无意义
            }
        }
        // 配置开启但未迁移的任务：记待迁移（单任务模式不记）
        if (fnOnly == null && config != null) {
            for (Map.Entry<String, Boolean> e : config.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) continue;
                if (ServerTasks.byId(e.getKey()) != null) continue;
                TaskOutcome r = new TaskOutcome(e.getKey(), e.getKey(),
                        TaskOutcome.Category.SKIPPED, "待迁移到服务器（P3B 批次）");
                applyResult(store, fpid, sid, e.getKey(), r, trigger);
            }
        }
        // 收尾：重采一遍状态（任务动作会改变任务进度/补给/集结等）
        try {
            CaptureTasks.captureAll(engine, store, fpid, sid);
        } catch (Throwable ignored) {
        }
        String head = "服务器整轮 · " + com.carlos.vaultserver.Beijing.fmt("MM-dd HH:mm")
                .format(new Date()) + " · 成功" + done + "(领取" + claimed + ")"
                + (abnormal > 0 ? " 异常" + abnormal : "");
        String text = head + "\n" + summary;
        store.putString("summary:" + fpid, text);
        store.putLong("srv:lastrun:" + fpid, System.currentTimeMillis());
        store.flush();
        FileLog.info("整轮完成 fpid={} sid={}：{}", fpid, String.valueOf(sid), head);
        return text;
    }

    /** 落一条任务结果：日志（log: 前缀，最新在前，保留50）+ 当日状态机（st:date:…）。 */
    static void applyResult(StateStore store, String fpid, long sid, String fnId,
                            TaskOutcome r, String trigger) {
        try {
            String logKey = "log:" + fpid + ":" + sid + ":" + fnId;
            JSONArray arr;
            String raw = store.getString(logKey);
            if (raw != null) {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable t) {
                    arr = new JSONArray();
                }
            } else {
                arr = new JSONArray();
            }
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ok", r.successLike());
            e.put("reason", r.message);
            e.put("detail", "");
            e.put("trg", trigger);
            e.put("dur", r.durationMs);
            e.put("cat", r.category.name());
            e.put("att", 1);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && i < 49; i++) out.put(arr.get(i));
            store.putString(logKey, out.toString());

            String day = com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd").format(new Date());
            String stKey = "st:" + day + ":" + fpid + ":" + sid + ":" + fnId;
            int st = r.category == TaskOutcome.Category.CLAIMED || r.category == TaskOutcome.Category.SKIPPED ? 2
                    : r.category == TaskOutcome.Category.SESSION_CLOSED ? 1 : 3;
            store.putLong(stKey, st);

            // 子项级结果（App LogStore.applySubResults 同款：fn:sub 日志 + 子项当日状态）
            for (Map.Entry<String, String> sub : r.subResults.entrySet()) {
                try {
                    String v = sub.getValue();
                    boolean ok = v.startsWith("ok:") || v.startsWith("skip:");
                    String note = v.substring(v.indexOf(':') + 1);
                    appendSub(store, fpid, sid, fnId + ":" + sub.getKey(), ok, note, trigger);
                    store.putLong("st:" + day + ":" + fpid + ":" + sid + ":"
                            + fnId + ":" + sub.getKey(), 2);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendSub(StateStore store, String fpid, long sid, String fnSub,
                                  boolean ok, String note, String trigger) {
        try {
            String logKey = "log:" + fpid + ":" + sid + ":" + fnSub;
            JSONArray arr = new JSONArray();
            String raw = store.getString(logKey);
            if (raw != null) {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable ignored) {
                }
            }
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ok", ok);
            e.put("reason", note);
            e.put("detail", "");
            e.put("trg", trigger);
            e.put("dur", 0);
            e.put("cat", ok ? "CLAIMED" : "ERROR");
            e.put("att", 1);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && i < 49; i++) out.put(arr.get(i));
            store.putString(logKey, out.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 补给唤醒时刻：从已采集的 supply 状态读 online_ts（epoch 秒；0=无）。 */
    public static long supplyWakeAt(StateStore store, String fpid, long sid) {
        try {
            String raw = store.getString("mod:" + fpid + ":" + sid + ":supply");
            if (raw == null) return 0;
            return new JSONObject(raw).optLong("online_ts", 0) * 1000L;
        } catch (Throwable t) {
            return 0;
        }
    }
}
