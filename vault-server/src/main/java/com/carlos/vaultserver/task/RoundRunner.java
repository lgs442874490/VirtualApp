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
 *
 * <p>R2 Phase 2（2026-10-29）：同账号同服多角色各自执行整轮。任务结果键按角色 uid 隔离
 * （{@link StateKeys} 角色键），App 仍读的旧键只由 <b>active 角色镜像写</b>（{@code activeWriter}）；
 * {@code roleUid<=0} 的旧调用路径全部退回旧键，行为与 Phase 2 之前 100% 一致。
 */
public final class RoundRunner {

    private RoundRunner() {
    }

    /** 在会话线程上执行整轮（fn=null）或单任务。返回汇总文本（旧签名：uid=0 兼容路径）。 */
    public static String runRound(WjdrEngine engine, VaultStore.Account acc, long sid,
                                  StateStore store, Map<String, JSONObject> params,
                                  String fnOnly, String trigger) {
        return runRound(engine, acc, sid, store, params, fnOnly, trigger, 0L, true);
    }

    /**
     * R2 Phase 2（2026-10-29）：按角色执行整轮/单任务。
     *
     * @param roleUid     角色 uid（>0 时任务状态键按角色隔离；0=旧/兼容路径）
     * @param activeWriter 是否为该账号当前 App 选中角色（仅它镜像写旧键，App 显示链路不变）
     */
    public static String runRound(WjdrEngine engine, VaultStore.Account acc, long sid,
                                  StateStore store, Map<String, JSONObject> params,
                                  String fnOnly, String trigger,
                                  long roleUid, boolean activeWriter) {
        String fpid = acc.fpid;
        RoleKey role = new RoleKey(fpid, sid, roleUid);
        Map<String, Boolean> config = configForRole(acc, sid, roleUid);
        // 2026-10-25 P0：启用任务集合带进上下文（采集×情报让位判定需要知道 intel 是否在跑）
        TaskContext tc = new TaskContext(engine, fpid, sid, store, params, trigger,
                TaskContext.enabledPredicate(config), roleUid);
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
            applyResult(store, role, def.id, r, trigger, fnOnly != null && !fnOnly.isEmpty(), activeWriter);
            if (r.successLike()) done++;
            if (r.category == TaskOutcome.Category.CLAIMED) claimed++;
            else if (r.category == TaskOutcome.Category.SKIPPED) skipped++;
            if (r.category == TaskOutcome.Category.DEGRADED
                    || r.category == TaskOutcome.Category.ERROR) abnormal++;
            if (summary.length() > 0) summary.append('\n');
            summary.append(r.toString());
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) {
                FileLog.warn("整轮中断 role={} fn={}：{}", role.key(), def.id, r.message);
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
                applyResult(store, role, e.getKey(), r, trigger, false, activeWriter);
            }
        }
        // 收尾：重采一遍状态（任务动作会改变任务进度/补给/集结等）
        try {
            CaptureTasks.captureAll(engine, store, fpid, sid, roleUid, activeWriter);
        } catch (Throwable ignored) {
        }
        String head = "服务器整轮 · " + com.carlos.vaultserver.Beijing.fmt("MM-dd HH:mm")
                .format(new Date()) + " · 成功" + done + "(领取" + claimed + " 跳过" + skipped + ")"
                + (abnormal > 0 ? " 异常" + abnormal : "");
        String text = head + "\n" + summary;
        putSummary(store, role, text, activeWriter);
        // 只有整轮才消耗“今日已跑”；单任务（补给补领/账号页单功能）不能把整轮标记为已完成。
        if (fnOnly == null) {
            long now = System.currentTimeMillis();
            store.putLong(lastRunKey(role), now);
            if (activeWriter && role.roleScoped()) store.putLong(lastRunLegacyKey(role), now);
        }
        store.flush();
        FileLog.info("整轮完成 role={}：{}", role.key(), head);
        return text;
    }

    // ---------------- 角色配置/键型（R2 Phase 2） ----------------

    /**
     * 角色启用配置：{@code roleConfigByUid[sid:uid]} → {@code roleConfig[sid]}（该区服默认）→ 目录默认。
     *
     * <p>同服多角色的开关各自落 roleConfigByUid；旧表保留为“该区服默认配置”，
     * 旧 App/回滚继续可用（无按 uid 配置时行为不变）。
     */
    static Map<String, Boolean> configForRole(VaultStore.Account acc, long sid, long uid) {
        if (acc == null) return null;
        if (uid > 0L) {
            Map<String, Boolean> byUid = acc.roleConfigByUid.get(sid + ":" + uid);
            if (byUid != null && !byUid.isEmpty()) return byUid;
        }
        return acc.roleConfig.get(String.valueOf(sid));
    }

    /** 整轮摘要键：角色键（uid>0）或旧账号键 {@code summary:<fpid>}。 */
    static String summaryKey(RoleKey role) {
        return role.roleScoped() ? StateKeys.summary(role)
                : StateKeys.account("summary", role.fpid, null);
    }

    /** 整轮摘要旧键（App 显示链路）：{@code summary:<fpid>}。 */
    static String summaryLegacyKey(RoleKey role) {
        return StateKeys.account("summary", role.fpid, null);
    }

    /** 整轮最近执行键：角色键 {@code srv:lastrun:<fpid>:<sid>:<uid>} 或旧账号键。 */
    static String lastRunKey(RoleKey role) {
        return role.roleScoped() ? StateKeys.lastRun(role)
                : StateKeys.account("srv:lastrun", role.fpid, null);
    }

    /** 整轮最近执行旧键（App 显示链路）：{@code srv:lastrun:<fpid>}。 */
    static String lastRunLegacyKey(RoleKey role) {
        return StateKeys.account("srv:lastrun", role.fpid, null);
    }

    /** 补给补领去重键：角色键 {@code srv:supplyfire:<fpid>:<sid>:<uid>} 或旧账号键。 */
    static String supplyFiredKey(RoleKey role) {
        return role.roleScoped() ? StateKeys.supplyFired(role)
                : StateKeys.account("srv:supplyfire", role.fpid, null);
    }

    /** 补给补领去重旧键（App 显示链路）：{@code srv:supplyfire:<fpid>}。 */
    static String supplyFiredLegacyKey(RoleKey role) {
        return StateKeys.account("srv:supplyfire", role.fpid, null);
    }

    /** 写整轮摘要：角色键 + active 角色镜像旧键（App 读 {@code summary:<fpid>}）。 */
    private static void putSummary(StateStore store, RoleKey role, String text, boolean activeWriter) {
        store.putString(summaryKey(role), text);
        if (activeWriter && role.roleScoped()) store.putString(summaryLegacyKey(role), text);
    }

    /** 落一条任务结果：日志（log: 前缀，最新在前，保留50）+ 当日状态机（st:date:…）。 */
    static void applyResult(StateStore store, String fpid, long sid, String fnId,
                            TaskOutcome r, String trigger) {
        applyResult(store, fpid, sid, fnId, r, trigger, false);
    }

    /**
     * 落一条任务结果（C3 扩展，旧签名）：等价 uid=0 active=true 兼容路径。
     *
     * @param singleTaskMode true=只跑单个任务（fnOnly/节奏派单/手动单功能）；false=全量整轮。
     *                       整轮模式不写 {@code srv:cad:*}，避免把单任务节奏覆盖成整轮结果。
     */
    static void applyResult(StateStore store, String fpid, long sid, String fnId,
                            TaskOutcome r, String trigger, boolean singleTaskMode) {
        applyResult(store, new RoleKey(fpid, sid, 0L), fnId, r, trigger, singleTaskMode, true);
    }

    /**
     * R2 Phase 2：按角色落一条任务结果（角色键 + active 角色镜像旧键）。
     *
     * @param activeWriter 是否 App 当前选中角色（仅它镜像写旧 {@code log:/st:} 键）
     */
    static void applyResult(StateStore store, RoleKey role, String fnId, TaskOutcome r,
                            String trigger, boolean singleTaskMode, boolean activeWriter) {
        if (role == null || fnId == null) return;
        boolean scoped = role.roleScoped();
        try {
            String legacyLogKey = StateKeys.taskLogLegacy(role, fnId);
            String logKey = scoped ? StateKeys.taskLog(role, fnId) : legacyLogKey;
            JSONArray arr;
            String raw = store.getString(logKey);
            // 迁移连续性：active 角色首轮没有角色键 → 以旧（主控）日志为初始值，随后只写角色键+镜像旧键。
            if (raw == null && scoped && activeWriter) raw = store.getString(legacyLogKey);
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
            if (scoped && activeWriter) store.putString(legacyLogKey, out.toString());

            String day = com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd").format(new Date());
            String legacyStKey = StateKeys.dayStateLegacy(day, role, fnId);
            String stKey = scoped ? StateKeys.dayState(day, role, fnId) : legacyStKey;
            // 2=已领取，4=已检查但无可领（SKIPPED），1=会话中断，3=异常。
            // SKIPPED 不再伪装成 DONE，避免“界面已完成但游戏内没领到奖励”。
            int st = r.category == TaskOutcome.Category.CLAIMED ? 2
                    : r.category == TaskOutcome.Category.SKIPPED ? 4
                    : r.category == TaskOutcome.Category.SESSION_CLOSED ? 1 : 3;
            store.putLong(stKey, st);
            if (scoped && activeWriter) store.putLong(legacyStKey, st);

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
                    String fnSub = fnId + ":" + sub.getKey();
                    appendSub(store, role, fnSub, cat, note, trigger, activeWriter);
                    // skip 是“已检查但无可领”，不能记成已领取；fail 记异常。
                    int subStatus = v.startsWith("skip:") ? 4 : (fail ? 3 : 2);
                    String legacySubStKey = StateKeys.dayStateLegacy(day, role, fnSub);
                    String subStKey = scoped ? StateKeys.dayState(day, role, fnSub) : legacySubStKey;
                    store.putLong(subStKey, subStatus);
                    if (scoped && activeWriter) store.putLong(legacySubStKey, subStatus);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        applyNextWake(store, role, fnId, r, singleTaskMode, activeWriter);
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

    /** 旧签名（uid=0 兼容路径）：单任务模式落盘任务自报唤醒到旧节奏键。 */
    static void applyNextWake(StateStore store, String fpid, long sid, String fnId,
                              TaskOutcome r, boolean singleTaskMode) {
        applyNextWake(store, new RoleKey(fpid, sid, 0L), fnId, r, singleTaskMode, true);
    }

    /**
     * C3：单任务模式下落盘任务自报的下次唤醒时刻到角色节奏键
     * {@code srv:cad:{fpid}:{sid}:{uid}:{fn}}（旧键由 active 角色镜像）；整轮模式不写。
     * 边界不合法不写；任何异常吞掉（不破坏落账主流程）。
     */
    static void applyNextWake(StateStore store, RoleKey role, String fnId,
                              TaskOutcome r, boolean singleTaskMode, boolean activeWriter) {
        if (role == null || !singleTaskMode || r == null || r.nextWakeAtMillis <= 0L) return;
        try {
            long now = System.currentTimeMillis();
            if (!acceptNextWakeAt(now, r.nextWakeAtMillis)) {
                FileLog.warn("业务态唤醒越界丢弃 role={} fn={} at={} now={}",
                        role.key(), fnId, String.valueOf(r.nextWakeAtMillis), String.valueOf(now));
                return;
            }
            String legacyKey = StateKeys.cadenceLegacy(role, fnId);
            store.putLong(role.roleScoped() ? StateKeys.cadence(role, fnId) : legacyKey,
                    r.nextWakeAtMillis);
            if (activeWriter && role.roleScoped()) store.putLong(legacyKey, r.nextWakeAtMillis);
        } catch (Throwable ignored) {
        }
    }

    private static void appendSub(StateStore store, RoleKey role, String fnSub,
                                  String cat, String note, String trigger, boolean activeWriter) {
        try {
            boolean scoped = role.roleScoped();
            String legacyLogKey = StateKeys.taskLogLegacy(role, fnSub);
            String logKey = scoped ? StateKeys.taskLog(role, fnSub) : legacyLogKey;
            JSONArray arr = new JSONArray();
            String raw = store.getString(logKey);
            if (raw == null && scoped && activeWriter) raw = store.getString(legacyLogKey);
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
            if (scoped && activeWriter) store.putString(legacyLogKey, out.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 补给唤醒时刻：从已采集的 supply 状态读 online_ts（epoch 秒；0=无）。旧签名。 */
    public static long supplyWakeAt(StateStore store, String fpid, long sid) {
        return supplyWakeAt(store, new RoleKey(fpid, sid, 0L), true);
    }

    /**
     * 补给唤醒时刻（R2 Phase 2 按角色）：读角色级 {@code mod:<fpid>:<sid>:<uid>:supply}；
     * active 角色（allowLegacy）miss 时回退旧键 {@code mod:<fpid>:<sid>:supply}（迁移兼容）。
     */
    public static long supplyWakeAt(StateStore store, RoleKey role, boolean allowLegacy) {
        if (store == null || role == null) return 0;
        try {
            String roleKey = StateKeys.captureMod(role, "supply");
            String raw = store.getString(roleKey);
            if (raw == null && role.roleScoped() && allowLegacy) {
                raw = store.getString(StateKeys.legacy("mod", role, "supply"));
            }
            if (raw == null) return 0;
            return new JSONObject(raw).optLong("online_ts", 0) * 1000L;
        } catch (Throwable t) {
            return 0;
        }
    }
}
