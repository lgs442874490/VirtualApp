package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.monitor.TaskTrace;

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
        // 2026-10-25 P0：启用任务集合带进上下文（采集×情报让位判定需要知道 intel 是否在跑）
        TaskContext tc = new TaskContext(engine, fpid, sid, store, params, trigger,
                TaskContext.enabledPredicate(config));
        StringBuilder summary = new StringBuilder();
        int done = 0, claimed = 0, skipped = 0, abnormal = 0;
        List<ServerTasks.TaskDef> defs = ServerTasks.all();
        for (ServerTasks.TaskDef def : defs) {
            if (fnOnly != null && !fnOnly.equals(def.id)) continue;
            Boolean enabled = config == null ? null : config.get(def.id);
            boolean on = enabled == null ? def.enabledByDefault : enabled;
            TaskOutcome r = null;
            // P7 战时任务闸（E1 对等）：allowlist 外任务一律 SKIPPED（不消耗游戏内次数）
            String warNote = on ? com.carlos.vaultserver.ServerWarState.gateNote(fpid, sid, def.id) : null;
            if (!on) {
                r = new TaskOutcome(def.id, def.name, TaskOutcome.Category.SKIPPED, "配置未启用");
            } else if (warNote != null) {
                r = new TaskOutcome(def.id, def.name, TaskOutcome.Category.SKIPPED, warNote);
            } else {
                long t0 = System.currentTimeMillis();
                // 真机监控（2026-10-25）：legacy 整轮每个 def 前后 task.start/task.end，只观测不改控制流
                TaskTrace.Context trace = TaskTrace.begin(fpid, sid, def.id, def.name, tc.trigger);
                try {
                    try {
                        r = def.executor.run(tc);
                    } catch (Throwable t) {
                        r = ServerTasks.classifyException(t, def.name);
                    }
                    r.durationMs = System.currentTimeMillis() - t0;
                } finally {
                    trace.end(r);
                }
            }
            // 落键一律用目录 id（classify* 生成的 taskId 可能是协议名/任务名，仅展示用）
            applyResult(store, fpid, sid, def.id, r, trigger, fnOnly != null && !fnOnly.isEmpty());
            if (r.successLike()) done++;
            if (r.category == TaskOutcome.Category.CLAIMED) claimed++;
            else if (r.category == TaskOutcome.Category.SKIPPED) skipped++;
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
                .format(new Date()) + " · 成功" + done + "(领取" + claimed + " 跳过" + skipped + ")"
                + (abnormal > 0 ? " 异常" + abnormal : "");
        String text = head + "\n" + summary;
        store.putString("summary:" + fpid, text);
        // 只有整轮才消耗“今日已跑”；单任务（补给补领/账号页单功能）不能把整轮标记为已完成。
        if (fnOnly == null) {
            store.putLong("srv:lastrun:" + fpid, System.currentTimeMillis());
        }
        store.flush();
        FileLog.info("整轮完成 fpid={} sid={}：{}", fpid, String.valueOf(sid), head);
        return text;
    }

    /** 落一条任务结果：日志（log: 前缀，最新在前，保留50）+ 当日状态机（st:date:…）。 */
    static void applyResult(StateStore store, String fpid, long sid, String fnId,
                            TaskOutcome r, String trigger) {
        applyResult(store, fpid, sid, fnId, r, trigger, false);
    }

    /**
     * 落一条任务结果（C3 扩展）：额外在<b>单任务模式</b>下落盘任务自报的业务态唤醒时刻。
     *
     * @param singleTaskMode true=只跑单个任务（fnOnly/节奏派单/手动单功能）；false=全量整轮。
     *                       整轮模式不写 {@code srv:cad:*}，避免把单任务节奏覆盖成整轮结果。
     */
    static void applyResult(StateStore store, String fpid, long sid, String fnId,
                            TaskOutcome r, String trigger, boolean singleTaskMode) {
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
            // detail 带领取明细（与 App LogStore.Entry.detail 同键型），App「领取内容」直接展示
            e.put("detail", r.rewards == null ? "" : r.rewards);
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
            // 2=已领取，4=已检查但无可领（SKIPPED），1=会话中断，3=异常。
            // SKIPPED 不再伪装成 DONE，避免“界面已完成但游戏内没领到奖励”。
            int st = r.category == TaskOutcome.Category.CLAIMED ? 2
                    : r.category == TaskOutcome.Category.SKIPPED ? 4
                    : r.category == TaskOutcome.Category.SESSION_CLOSED ? 1 : 3;
            store.putLong(stKey, st);

            // 子项级结果（App LogStore.applySubResults 同款：fn:sub 日志 + 子项当日状态）
            for (Map.Entry<String, String> sub : r.subResults.entrySet()) {
                try {
                    String v = sub.getValue();
                    boolean fail = v.startsWith("fail:");
                    boolean skip = v.startsWith("skip:");
                    String note = v.substring(v.indexOf(':') + 1);
                    // P2-1：skip 是"已检查但无可领"，不能记成已领取（cat=CLAIMED/ok=true）；
                    // fail 记异常。cat 与 st 口径保持一致（2/4/3）。
                    String cat = fail ? "ERROR" : (skip ? "SKIPPED" : "CLAIMED");
                    appendSub(store, fpid, sid, fnId + ":" + sub.getKey(), cat, note, trigger);
                    // skip 是“已检查但无可领”，不能记成已领取；fail 记异常。
                    int subStatus = v.startsWith("skip:") ? 4 : (fail ? 3 : 2);
                    store.putLong("st:" + day + ":" + fpid + ":" + sid + ":"
                            + fnId + ":" + sub.getKey(), subStatus);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        applyNextWake(store, fpid, sid, fnId, r, singleTaskMode);
    }

    /** 业务态唤醒最大提前量：24 小时。超出视为任务算错/缓存脏数据，丢弃以免把节奏"冻"住。 */
    static final long NEXT_WAKE_MAX_AHEAD_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * C3 业务态唤醒边界判定（纯函数，网页版 {@code TaskDecision.SleepUntil} 语义的安全闸）：
     * 只接受 {@code now <= x <= now+24h}；其余（0/过去/超 24h）一律丢弃。
     *
     * @param nowMillis        落账时刻
     * @param nextWakeAtMillis 任务自报的下次唤醒时刻（epoch millis）
     */
    static boolean acceptNextWakeAt(long nowMillis, long nextWakeAtMillis) {
        if (nextWakeAtMillis <= 0L) return false;
        long delta = nextWakeAtMillis - nowMillis;
        return delta >= 0L && delta <= NEXT_WAKE_MAX_AHEAD_MILLIS;
    }

    /**
     * C3：单任务模式下落盘任务自报的下次唤醒时刻到
     * {@code srv:cad:{fpid}:{sid}:{fn}}（{@link TaskCadenceScheduler} 唯一的到期扫描键）。
     * 整轮模式不写；边界不合法不写；任何异常吞掉（不破坏落账主流程）。
     */
    static void applyNextWake(StateStore store, String fpid, long sid, String fnId,
                              TaskOutcome r, boolean singleTaskMode) {
        if (!singleTaskMode || r == null || r.nextWakeAtMillis <= 0L) return;
        try {
            long now = System.currentTimeMillis();
            if (!acceptNextWakeAt(now, r.nextWakeAtMillis)) {
                FileLog.warn("业务态唤醒越界丢弃 fpid={} fn={} at={} now={}",
                        fpid, fnId, String.valueOf(r.nextWakeAtMillis), String.valueOf(now));
                return;
            }
            store.putLong("srv:cad:" + fpid + ":" + sid + ":" + fnId, r.nextWakeAtMillis);
        } catch (Throwable ignored) {
        }
    }

    private static void appendSub(StateStore store, String fpid, long sid, String fnSub,
                                  String cat, String note, String trigger) {
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
            // 仅 CLAIMED 记 ok=true；SKIPPED（已检查无可领）/ERROR 都不是领取成功
            e.put("ok", "CLAIMED".equals(cat));
            e.put("reason", note);
            e.put("detail", "");
            e.put("trg", trigger);
            e.put("dur", 0);
            e.put("cat", cat);
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
